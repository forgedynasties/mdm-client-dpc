package aio.app.mdmclient.dpc.net

import aio.app.mdmclient.dpc.FactoryConfig
import aio.app.mdmclient.dpc.kiosk.StaffMode
import android.app.ActivityManager
import android.content.Context
import aio.app.mdmclient.dpc.device.ScreenControl
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.BuildConfig
import aio.app.mdmclient.dpc.Capabilities
import aio.app.mdmclient.dpc.DeviceIdentity
import aio.app.mdmclient.dpc.DeviceOwner
import aio.app.mdmclient.dpc.device.CrashEvents
import aio.app.mdmclient.dpc.device.BleBeacon
import aio.app.mdmclient.dpc.device.BleListener
import aio.app.mdmclient.dpc.device.LostMode
import aio.app.mdmclient.dpc.device.SecurityPosture
import aio.app.mdmclient.dpc.device.WifiConfigurator
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.zip.CRC32

/**
 * Builds the checkin / telemetry payload the server expects
 * (see server internal/api/handlers.go checkinRequest).
 *
 * `installed_apps` is only attached when [includeApps] is true — the server tells us via the
 * `send_apps` response flag (or we send when the [appsHash] changed), matching the original
 * client's hash-gated app inventory to keep checkins cheap.
 */
object Telemetry {

    /** Stable hash of the installed-app set so the server can detect changes without the full list. */
    fun appsHash(ctx: Context): String {
        val pm = ctx.packageManager
        val entries = pm.getInstalledPackages(0)
            .map { "${it.packageName}:${it.versionName ?: it.longVersionCodeCompat()}" }
            .sorted()
        val crc = CRC32()
        crc.update(entries.joinToString("\n").toByteArray())
        return crc.value.toString(16)
    }

    fun buildCheckin(ctx: Context, includeApps: Boolean): JSONObject {
        // A TV box / dongle / kiosk / KDS / POS has no battery: its broadcast is supposed to
        // say present=false, carrying 0% and 0 °C which the server would otherwise show as a
        // flat battery at freezing point. In practice several of these boards' battery HAL
        // misreports present=true with a synthetic level regardless — confirmed on the HK1
        // RBOX D8 (0%), Sunmi D3 Pro and D2s KDS (50% on both, unchanging) during the 2026-10-05
        // lab pass. EXTRA_PRESENT alone can't be trusted on this hardware, so this is defense
        // in depth matching ui/MainActivity's default (false, not true) rather than a full
        // fix — the server's CapsForDevice is the authoritative no-battery signal by device
        // class and hides/relabels the dashboard's battery widget independent of this value.
        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.takeIf { it.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) }
        val battery = readBattery(batteryIntent)
        val obj = JSONObject().apply {
            put("serial_number", DeviceIdentity.serial(ctx))
            put("build_id", DeviceIdentity.buildId())
            put("product", DeviceIdentity.product())
            if (battery != null) put("battery_pct", battery)
            put("apps_hash", appsHash(ctx))
            put("extra", buildExtra(ctx, batteryIntent))
        }
        if (includeApps) obj.put("installed_apps", installedApps(ctx))
        return obj
    }

    /**
     * One reading for the offline queue (OfflineQueue): the keys POST /api/v1/checkins/backfill
     * takes, the same the firmware client keeps. elapsed_ms + boot_id let the server place it
     * on its own clock even if the device's wall clock is wrong.
     */
    fun offlineReading(ctx: Context): JSONObject {
        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val present = batteryIntent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        return JSONObject().apply {
            put("at_ms", System.currentTimeMillis())
            put("elapsed_ms", SystemClock.elapsedRealtime())
            put("boot_id", bootId(ctx))
            if (present) readBattery(batteryIntent)?.let { put("battery_pct", it) }
            val tenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (present && tenths != null && tenths != Int.MIN_VALUE) put("temp_c", tenths / 10.0)
            else cpuTempC(ctx)?.let { put("temp_c", it) }
            if (batteryIntent != null && present) put("charging", isCharging(batteryIntent))
            put("ram_used_mb", (mem.totalMem - mem.availMem) / MB)
            put("ram_total_mb", mem.totalMem / MB)
            put("storage_free_gb", Math.round(stat.availableBytes / GB * 10.0) / 10.0)
            runCatching {
                @Suppress("DEPRECATION")
                val info = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
                val rssi = info?.rssi
                if (rssi != null && rssi < 0 && rssi > -127) put("wifi_rssi", rssi)
            }
            OfflineQueue.newCrashes(ctx, CrashEvents.recent(ctx))?.let { put("crashes", it) }
        }
    }

    /** This boot, as the server compares it: Android's boot count. */
    fun bootId(ctx: Context): String =
        runCatching { android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT).toString() }
            .getOrDefault("")

    private fun buildExtra(ctx: Context, batteryIntent: Intent?): JSONObject {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        return JSONObject().apply {
            put("agent_type", Capabilities.AGENT_TYPE)
            // This agent fetches queued commands over HTTP (GET /api/v1/commands/pending)
            // as well as taking them pushed over the socket. The server reads this to
            // decide what the socket carries: a puller gets a content-free "wake" frame
            // and fetches its own work, so a dead or half-open socket costs it a poll
            // interval instead of making it unreachable.
            put("http_commands", true)
            // wifi_config "connect" switches networks and goes back if the new one fails (0.7.0).
            put("wifi_connect", true)
            put("agent_version", BuildConfig.VERSION_NAME)
            put("agent_version_code", BuildConfig.VERSION_CODE)
            // A Device Owner installs without a prompt, so an agent update never needs
            // anyone at the device. The dashboard words the update button from this.
            put("self_update_silent", true)
            // Built into the vendor's system image (vendor kit) rather than installed over adb.
            put("preinstalled", FactoryConfig.preinstalled(ctx))
            put("capabilities", JSONArray(Capabilities.supported(ctx)))
            // Degraded = works with limits (consent, reduced scope). The dashboard shows
            // these as "limited" instead of hiding them.
            put("capabilities_degraded", JSONArray(Capabilities.degraded(ctx)))
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("android_release", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("uptime_seconds", SystemClock.elapsedRealtime() / 1000)
            put("timezone", TimeZone.getDefault().id)
            // Same keys and units as the firmware client (MdmService.java): the server's
            // RAM and storage surfaces read ram_usage_mb {total,available,used} and
            // storage_free_gb, and showed nothing for DPC devices while we sent bytes.
            put("storage_free_gb", Math.round(stat.availableBytes / GB * 10.0) / 10.0)
            put("storage_total_bytes", stat.totalBytes)
            put("ram_usage_mb", JSONObject().apply {
                put("total", mem.totalMem / MB)
                put("available", mem.availMem / MB)
                put("used", (mem.totalMem - mem.availMem) / MB)
            })
            // Same battery-broadcast telemetry the system client reports (MdmService.java):
            // temperature feeds the dashboard's hottest-device/running-hot surfaces, charging
            // feeds the on-charger vital and filters. EXTRA_TEMPERATURE is tenths of a °C.
            val tenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths != null && tenths != Int.MIN_VALUE) put("battery_temp_c", tenths / 10.0)
            if (batteryIntent != null) put("charging", isCharging(batteryIntent))
            // SoC temperature. The only reading a battery-less box has; the dashboard
            // shows it with CPU thresholds, not the battery's.
            liveSensor(ctx, "cpu_temp", cpuTempC(ctx))?.let { put("cpu_temp_c", it) }
            put("leanback", ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
            attachNetwork(ctx, this)
            CrashEvents.recent(ctx)?.let { put("crash_events", it) }
            attachLocation(ctx, this)
            attachSecurityPosture(ctx, this)
            val owner = DeviceOwner(ctx)
            runCatching { SecurityPosture.put(ctx, owner, this) }
            runCatching { WifiConfigurator(ctx, owner).report()?.let { put("wifi_networks", it) } }
            put("lost_mode", LostMode.report(ctx))
            // Staff mode (0.10.0), in the tablets' keys: the exit until the server answers it,
            // whether staff mode is on, and a re-lock until the config has the lock back.
            StaffMode.pendingExitAt(ctx).takeIf { it > 0 }?.let { put("offline_exit_at", it) }
            put("kiosk_staff_mode", StaffMode.isOn(ctx))
            // Whether lock task is really on (0.12.1): the config can say locked while it is not.
            runCatching { put("kiosk_active", aio.app.mdmclient.dpc.kiosk.KioskState.inLockTask(ctx)) }
            StaffMode.pendingRelockAt(ctx).takeIf { it > 0 }?.let { put("kiosk_relocked_at", it) }
            // Lost-device Bluetooth (protocol B): the beacon's state while lost, and what this
            // device heard of other lost devices at its venue (only while asked to listen).
            if (AgentConfig.get(ctx).lostOn) BleBeacon.report()?.let { put("lost_beacon", it) }
            BleListener.sightingsReport()?.let { put("ble_sightings", it) }
            BleListener.scanReport()?.let { put("ble_scan", it) }
            // What the device can hear, like the firmware client: a lost device is placed by
            // it, and the Wi-Fi card's Add network ranks the venue's networks by it (0.9.0).
            // An empty scan (no location permission, or nothing heard) is left out, so the
            // card does not read every network as "not seen nearby".
            wifiScan(ctx, AgentConfig.get(ctx).lostOn)?.takeIf { it.length() > 0 }?.let { put("wifi_scan", it) }
        }
    }

    @Volatile private var lastScanStartMs = 0L

    /**
     * The last scan's access points as {bssid, ssid, rssi} — the firmware client's shape —
     * and a fresh scan asked for at most once a minute (Android throttles more anyway). Needs
     * the location permission and location on, which the service turns on at start (0.9.1).
     */
    @Suppress("DEPRECATION")
    private fun wifiScan(ctx: Context, lost: Boolean): JSONArray? = runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val now = SystemClock.elapsedRealtime()
        // A fresh scan every minute while lost, every five otherwise (radio time).
        if (now - lastScanStartMs > if (lost) 60_000L else 5 * 60_000L) {
            lastScanStartMs = now
            runCatching { wm.startScan() }
        }
        val out = JSONArray()
        // The strongest 30: enough to place a device and name what is in range.
        for (sr in wm.scanResults.orEmpty().sortedByDescending { it.level }.take(30)) {
            out.put(JSONObject().apply {
                put("bssid", sr.BSSID)
                put("ssid", sr.SSID)
                put("rssi", sr.level)
            })
        }
        out
    }.getOrNull()

    /**
     * ip_address / wifi / wifi_rssi, the keys the firmware client reports. The SSID reads as
     * "<unknown ssid>" unless location is granted; that is reported as null, not as a name.
     */
    private fun attachNetwork(ctx: Context, extra: JSONObject) {
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val ip = cm.getLinkProperties(net)?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address && !it.address.isLoopbackAddress }
                ?.address?.hostAddress
            extra.put("ip_address", ip ?: JSONObject.NULL)
            val caps = cm.getNetworkCapabilities(net)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                @Suppress("DEPRECATION")
                val info = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
                val ssid = info?.ssid?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
                extra.put("wifi", ssid ?: JSONObject.NULL)
                extra.put("wifi_rssi", info?.rssi ?: JSONObject.NULL)
            } else {
                extra.put("wifi", JSONObject.NULL)
                extra.put("wifi_rssi", JSONObject.NULL)
            }
        }
    }

    /** Compliance signals the server's policy engine evaluates (all best-effort). */
    private fun attachSecurityPosture(ctx: Context, extra: JSONObject) {
        runCatching {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            extra.put("screen_lock_set", km.isDeviceSecure)
            // Whether a keyguard is currently up, which is the difference between a wake
            // that lit the screen and one the operator still cannot drive.
            extra.put("screen_locked", km.isKeyguardLocked)
        }
        // The same key the system-app client reports: the dashboard's screen state and the
        // standby rollup both read it, and until now no DPC device ever sent one.
        runCatching { extra.put("screen_on", ScreenControl.isScreenOn(ctx)) }
        runCatching {
            val adb = android.provider.Settings.Global.getInt(
                ctx.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0,
            )
            extra.put("adb_enabled", adb == 1)
        }
        // Same key the firmware client reports: whether adbd listens on the network. We can't
        // read service.adb.tcp.port from an app, so ask the port itself (loopback, 300 ms).
        runCatching { extra.put("adb_tcp", AdbTunnel.adbdListening()) }
        runCatching {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
            extra.put(
                "storage_encrypted",
                dpm.storageEncryptionStatus ==
                    android.app.admin.DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER ||
                    dpm.storageEncryptionStatus ==
                    android.app.admin.DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
            )
        }
    }

    /**
     * Attach the freshest last-known fix when the server has enabled location reporting (or
     * the device is lost) and
     * the DO-granted permission is actually in place. Reads are passive, but when no fix
     * exists (or it's stale) we request ONE fresh fix asynchronously — on a dedicated
     * device nothing else ever activates the providers, so a passive-only read would
     * never produce a location at all. The fresh fix lands in a later checkin.
     */
    private fun attachLocation(ctx: Context, extra: JSONObject) {
        val cfg = AgentConfig.get(ctx)
        if (!cfg.locationEnabled && !cfg.lostOn) return
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val best = lm.allProviders
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            val ageMs = best?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
            if (best == null || ageMs > STALE_FIX_MS) nudgeLocation(ctx, lm)
            if (best == null) return
            extra.put("location_lat", best.latitude)
            extra.put("location_lon", best.longitude)
            extra.put("location_acc_m", best.accuracy.toDouble())
            extra.put("location_age_s", (ageMs / 1000).coerceAtLeast(0))
        }
    }

    private const val STALE_FIX_MS = 10 * 60_000L
    @Volatile private var locationRequestInFlight = false

    /** Request one fresh fix (GPS, falling back to network) without blocking the checkin. */
    private fun nudgeLocation(ctx: Context, lm: android.location.LocationManager) {
        if (locationRequestInFlight) return
        locationRequestInFlight = true
        runCatching {
            val provider = when {
                lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ->
                    android.location.LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                    android.location.LocationManager.NETWORK_PROVIDER
                else -> { locationRequestInFlight = false; return }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, null, ctx.mainExecutor) {
                    locationRequestInFlight = false // fix (or null) cached by the provider
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, { locationRequestInFlight = false }, null)
            }
        }.onFailure { locationRequestInFlight = false }
    }

    private const val MB = 1024L * 1024L
    private const val GB = 1024.0 * 1024.0 * 1024.0

    /**
     * Hottest CPU core, in °C, from the thermal HAL via HardwarePropertiesManager (open to
     * the Device Owner). Falls back to the first sysfs thermal zone where the SELinux
     * policy lets an app read it. null when neither gives a plausible reading.
     */
    private fun cpuTempC(ctx: Context): Double? {
        val hal = runCatching {
            val hpm = ctx.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE) as android.os.HardwarePropertiesManager
            hpm.getDeviceTemperatures(
                android.os.HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                android.os.HardwarePropertiesManager.TEMPERATURE_CURRENT,
            ).filter { it.isFinite() && it > 0f }.maxOrNull()?.toDouble()
        }.getOrNull()
        val t = hal ?: runCatching {
            java.io.File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toDouble() / 1000.0
        }.getOrNull()
        return t?.takeIf { it in 1.0..150.0 }?.let { Math.round(it * 10.0) / 10.0 }
    }

    /**
     * A temperature sensor is believed once it has moved. Some thermal HALs answer a
     * fixed placeholder for ever (the Sunmi D3 Pro and the C8G POS both say exactly 37.0),
     * and a reading that never changes is no reading. Until the value differs from the
     * first one seen, nothing is sent; once it has, the sensor stays trusted (persisted,
     * so a restart doesn't hide a real sensor again).
     */
    private fun liveSensor(ctx: Context, key: String, value: Double?): Double? {
        if (value == null) return null
        val p = ctx.createDeviceProtectedStorageContext().getSharedPreferences(SENSOR_PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean("${key}_moved", false)) return value
        val first = p.getFloat("${key}_first", Float.NaN)
        if (first.isNaN()) {
            p.edit().putFloat("${key}_first", value.toFloat()).apply()
            return null
        }
        if (kotlin.math.abs(first - value) < 0.05) return null
        p.edit().putBoolean("${key}_moved", true).apply()
        return value
    }

    private const val SENSOR_PREFS = "sensors"

    private fun isCharging(batteryIntent: Intent): Boolean {
        if (batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) return true
        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun installedApps(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        // Packages that have a launcher (drawer) entry. The server's app-lock picker
        // only offers these — pinning to a package with no launch activity can't work.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = pm.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toHashSet()
        val arr = JSONArray()
        for (pkg in pm.getInstalledPackages(0)) {
            val ai = pkg.applicationInfo ?: continue
            val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val isLaunchable = launchable.contains(pkg.packageName)
            arr.put(JSONObject().apply {
                put("package", pkg.packageName)
                put("name", pm.getApplicationLabel(ai).toString())
                put("version_name", pkg.versionName ?: pkg.longVersionCodeCompat().toString())
                put("is_system", isSystem)
                put("launchable", isLaunchable)
                // Only ship an icon for drawer apps — that's all the picker renders, and
                // it keeps the checkin payload small. Best-effort; skip on any failure.
                if (isLaunchable) launcherIconBase64(pm, ai)?.let { put("icon", it) }
            })
        }
        return arr
    }

    /** Renders a package's launcher icon to a small base64 PNG (48dp) for the app-lock picker. */
    private fun launcherIconBase64(pm: android.content.pm.PackageManager,
                                   ai: android.content.pm.ApplicationInfo): String? = runCatching {
        val drawable = pm.getApplicationIcon(ai)
        val size = 96 // px — crisp at the picker's ~44px tile, still tiny on the wire
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    private fun readBattery(intent: Intent?): Int? {
        if (intent == null) return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else null
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else @Suppress("DEPRECATION") versionCode.toLong()
}
