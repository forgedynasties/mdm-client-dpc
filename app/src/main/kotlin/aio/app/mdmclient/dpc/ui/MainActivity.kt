package aio.app.mdmclient.dpc.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.AgentStatus
import aio.app.mdmclient.dpc.BuildConfig
import aio.app.mdmclient.dpc.Capabilities
import aio.app.mdmclient.dpc.DeviceIdentity
import aio.app.mdmclient.dpc.DeviceOwner
import aio.app.mdmclient.dpc.MdmDeviceAdminReceiver
import aio.app.mdmclient.dpc.MdmService
import aio.app.mdmclient.dpc.R
import aio.app.mdmclient.dpc.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import org.json.JSONObject
import java.net.Inet4Address

/**
 * Status screen, in the dashboard's design language: is this device managed, is it reporting,
 * and what the agent knows and enforces. Read-only on purpose — enrollment arrives by QR
 * provisioning or the adb extras below, so there is no server URL or key to type (or to see).
 * Refreshes every second while in front; works with a D-pad on a TV box.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AgentConfig
    private lateinit var deviceOwner: DeviceOwner

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 1000)
        }
    }

    /** "Check in now" stays in its busy state until a newer attempt lands, or 20 s pass. */
    private var checkinRequestedAt = 0L

    // Last rendered content per list, so a tick that changes nothing touches no views.
    private val rendered = mutableMapOf<Int, Any>()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // A TV box sits on a dark screen in a dim room: the dark theme there (S3, 3 Nov 2026).
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        }
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = AgentConfig.get(this)
        deviceOwner = DeviceOwner(this)

        layoutColumns()
        binding.buttonCheckin.setOnClickListener { checkInNow() }
        binding.buttonSettings.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        binding.buttonGroup.setOnClickListener { GroupPickActivity.open(this, ask = false) }
        binding.buttonDetails.setOnClickListener {
            val show = binding.columns.visibility != View.VISIBLE
            binding.columns.visibility = if (show) View.VISIBLE else View.GONE
            binding.buttonDetails.text = getString(if (show) R.string.action_hide_details else R.string.action_details)
            if (show) binding.scroll.post { binding.scroll.smoothScrollTo(0, binding.columns.top) }
        }
        askGroupOnce = savedInstanceState == null
        binding.footer.text = "AIO MDM agent ${BuildConfig.VERSION_NAME} · aioapp.com"
        binding.buttonCheckin.requestFocus()

        maybeRequestNotifPermission()
        // The service normally runs from boot or provisioning; opening the app also brings
        // it back after a force-stop, so the screen is never waiting on nothing.
        if (config.isConfigured || config.needsEnrollment) MdmService.start(this)
        applyLaunchExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyLaunchExtras(intent)
    }

    /**
     * Hands-free enrollment over adb (camera-less kiosks, bulk provisioning):
     *   adb shell am start -n aio.app.mdmclient.dpc/.ui.MainActivity \
     *       --es server_url https://mdm.example.com --es enroll_token enr_…
     * Seeds the config from the extras and starts the agent, which exchanges the token
     * for a device key. Nothing to type on the device.
     */
    private fun applyLaunchExtras(intent: Intent?) {
        val server = intent?.getStringExtra("server_url")?.trim().orEmpty()
        val token = intent?.getStringExtra("enroll_token")?.trim().orEmpty()
        val key = intent?.getStringExtra("api_key")?.trim().orEmpty()
        if (server.isEmpty() && token.isEmpty() && key.isEmpty()) return
        if (server.isNotEmpty()) config.serverUrl = server
        // A token this device already spent must not wipe the key it bought.
        if (token.isNotEmpty() && !(config.apiKey.isNotBlank() && token == config.lastEnrollToken)) {
            config.enrollToken = token
            config.lastEnrollToken = token
            config.apiKey = ""
        }
        if (key.isNotEmpty()) config.apiKey = key
        MdmService.start(this)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // The status screen is ours, so lock task would let it open while lost; send it
        // straight back to the lost screen instead.
        if (AgentConfig.get(this).lostOn) {
            runCatching {
                startActivity(Intent(this, aio.app.mdmclient.dpc.lost.LostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
            return
        }
        rendered.clear() // grants and policy may have changed while away
        ui.post(tick)
        loadGroup()
    }

    // ── Group (G3, picked 3 Nov 2026) ──────────────────────────────────────────────
    // A Lab device shows its group in a card with Change; while it has none, opening the app
    // opens the picker once ("Which team is this device for?", Not now). At a venue, or before
    // enrollment, the card stays hidden.

    /** Ask for a group on this launch of the screen (not on every resume, so Not now sticks). */
    private var askGroupOnce = false

    /** The device's role as the MDM names it ("POS terminal"), for the badge; "" until known. */
    private var role = ""

    /** The QR's link, so it is drawn again only when the server or serial changes. */
    private var qrFor = ""

    private fun loadGroup() {
        if (!config.isConfigured) { binding.groupCard.visibility = View.GONE; return }
        val ctx = applicationContext
        Thread {
            val answer = aio.app.mdmclient.dpc.net.ApiClient(AgentConfig.get(ctx))
                .postForAnswer("/api/v1/device-group", JSONObject().put("serial", DeviceIdentity.serial(ctx)), 15)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                val b = answer.body ?: return@post
                role = b.optString("role")
                val venue = b.optString("venue")
                val cur = b.optJSONObject("current")
                when {
                    b.optBoolean("lab") -> {
                        binding.groupCard.visibility = View.VISIBLE
                        binding.groupSub.text = "Lab · group"
                        binding.groupName.text = cur?.optString("name") ?: "No group yet"
                        binding.buttonGroup.visibility = View.VISIBLE
                        binding.buttonGroup.text = if (cur != null) getString(R.string.action_change) else "Pick a group"
                    }
                    venue.isNotBlank() -> {
                        binding.groupCard.visibility = View.VISIBLE
                        binding.groupSub.text = "Venue"
                        binding.groupName.text = venue
                        binding.buttonGroup.visibility = View.GONE
                    }
                    else -> binding.groupCard.visibility = View.GONE
                }
                if (!b.optBoolean("lab")) { askGroupOnce = false; return@post }
                if (cur == null && askGroupOnce) {
                    askGroupOnce = false
                    GroupPickActivity.open(this, ask = true)
                } else askGroupOnce = false
            }
        }.start()
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun checkInNow() {
        if (checkinBusy()) return
        checkinRequestedAt = System.currentTimeMillis()
        MdmService.start(this, MdmService.ACTION_CHECKIN_NOW)
        refreshStatus()
    }

    // ── Rendering ────────────────────────────────────────────────────────────────

    private enum class Tone { OK, WARN, BAD, INFO, NEUTRAL }

    /** One label/value line in a card; a [tone] renders the value as a status tag. */
    private data class Row(val label: String, val value: String, val tone: Tone? = null, val mono: Boolean = false)

    private fun refreshStatus() {
        val isOwner = deviceOwner.isDeviceOwner
        val serial = DeviceIdentity.serial(this)
        binding.badgeSerial.text = serial
        binding.deviceName.text = listOf(deviceName(), role).filter { it.isNotBlank() }.joinToString(" · ")
        renderQr(serial)
        styleTag(binding.headerTag, if (isOwner) "Managed" else "Not managed", if (isOwner) Tone.OK else Tone.WARN)

        val (tone, title, detail) = overallStatus(isOwner)
        (binding.statusDot.background as? GradientDrawable ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            binding.statusDot.background = this
        }).setColor(fg(tone))
        binding.statusTitle.text = title
        binding.statusDetail.text = detail

        binding.buttonCheckin.text = getString(if (checkinBusy()) R.string.action_checking else R.string.action_checkin)
        // Nothing to check in with until enrolled: hidden, not dimmed, so focus lands elsewhere.
        // While busy it stays enabled and ignores presses — a disabled view drops D-pad focus.
        binding.buttonCheckin.visibility = if (config.isConfigured) View.VISIBLE else View.GONE

        renderSetup(isOwner)
        renderRows(binding.rowsConnection, connectionRows())
        renderRows(binding.rowsHealth, healthRows())
        renderRows(binding.rowsLock, lockRows())
        renderRows(binding.rowsManagement, managementRows(isOwner))
        renderRows(binding.rowsDevice, deviceRows())
        renderRows(binding.rowsPolicies, policyRows())
        renderCapabilities()
        renderPrivacy()
    }

    private fun checkinBusy() = checkinRequestedAt > 0 && AgentStatus.lastAttemptAtMs < checkinRequestedAt &&
        System.currentTimeMillis() - checkinRequestedAt < 20_000

    /** The one line that says whether this device is fine, and what to look at if not. */
    private fun overallStatus(isOwner: Boolean): Triple<Tone, String, String> {
        val s = AgentStatus
        val link = if (s.wsConnected) "Live connection" else "HTTP check-ins only"
        return when {
            !config.isConfigured && !config.needsEnrollment ->
                Triple(Tone.BAD, "Not enrolled", "This device has no MDM server to report to yet.")
            config.needsEnrollment || s.enrolling ->
                Triple(Tone.INFO, "Enrolling…", s.lastError.ifBlank { "Exchanging the enrollment token for a device key." })
            s.lastAttemptAtMs == 0L ->
                Triple(Tone.INFO, "Connecting…", "Waiting for the first check-in with ${serverHost()}.")
            !s.lastAttemptOk ->
                Triple(Tone.BAD, "Can't reach the server", s.lastError + " · " +
                    if (s.lastOkAtMs > 0) "last successful check-in ${ago(s.lastOkAtMs)}" else "no successful check-in yet")
            !isOwner ->
                Triple(Tone.WARN, "Reporting · not device owner", "Last check-in ${ago(s.lastOkAtMs)} · $link")
            else ->
                Triple(Tone.OK, "Managed · reporting", "Last check-in ${ago(s.lastOkAtMs)} · $link")
        }
    }

    private fun renderSetup(isOwner: Boolean) {
        val card = binding.setupCard
        val pkg = packageName
        when {
            !isOwner -> {
                binding.setupTitle.setText(R.string.setup_do_title)
                binding.setupBody.setText(R.string.setup_do_body)
                binding.setupCommand.text = "adb shell dpm set-device-owner " +
                    ComponentName(this, MdmDeviceAdminReceiver::class.java).flattenToShortString()
                card.visibility = View.VISIBLE
            }
            !config.isConfigured && !config.needsEnrollment -> {
                binding.setupTitle.setText(R.string.setup_enroll_title)
                binding.setupBody.setText(R.string.setup_enroll_body)
                binding.setupCommand.text = "adb shell am start -n $pkg/${MainActivity::class.java.name} " +
                    "--es server_url <server> --es enroll_token <token>"
                card.visibility = View.VISIBLE
            }
            else -> card.visibility = View.GONE
        }
    }

    private fun managementRows(isOwner: Boolean): List<Row> {
        val s = AgentStatus
        val rows = mutableListOf(
            Row("Device owner", if (isOwner) "Active" else "Not set", if (isOwner) Tone.OK else Tone.BAD),
            Row("Enrollment", when {
                config.needsEnrollment || s.enrolling -> "In progress"
                config.enrollSummary.isNotBlank() -> config.enrollSummary
                config.isConfigured -> "Enrolled"
                else -> "Not enrolled"
            }),
            Row("Server", serverHost()),
        )
        if (config.isConfigured) {
            rows += Row("Connection", if (s.wsConnected) "Live" else "HTTP only", if (s.wsConnected) Tone.OK else Tone.WARN)
            rows += Row("Checks in every", duration(config.checkinIntervalSeconds * 1000L))
            rows += Row("Last check-in", if (s.lastAttemptAtMs == 0L) "Not yet" else ago(s.lastAttemptAtMs))
            if (s.lastAttemptAtMs > 0) {
                rows += if (s.lastAttemptOk) Row("Result", "OK", Tone.OK)
                else Row("Result", "Failed · ${s.lastError}", Tone.BAD)
            }
        }
        return rows
    }

    private fun deviceRows(): List<Row> = buildList {
        add(Row("Serial", DeviceIdentity.serial(this@MainActivity), mono = true))
        add(Row("Model", deviceName()))
        add(Row("Android", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}"))
        Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() }?.let { add(Row("Security patch", it)) }
        add(Row("Agent", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
        add(Row("Network", network()))
        add(Row("Storage", storage()))
        battery()?.let { add(Row("Battery", it)) }
        add(Row("Up for", duration(SystemClock.elapsedRealtime())))
    }

    private fun policyRows(): List<Row> = buildList {
        add(if (!config.kioskEnabled) Row("Kiosk", "Off", Tone.NEUTRAL) else Row("Kiosk", kiosk(), Tone.INFO))
        add(Row("System updates", updatePolicy()))
        add(Row("Location reporting", if (config.locationEnabled) "On" else "Off",
            if (config.locationEnabled) Tone.INFO else Tone.NEUTRAL))
        add(Row("Managed network", managedNetwork()))
        val configs = jsonArrayLength(config.appRestrictionsJson)
        add(Row("App configurations", if (configs == 0) "None" else plural(configs, "app")))
    }

    // S3's three short cards; the full lists stay under Details.
    private fun connectionRows(): List<Row> = buildList {
        add(Row("Network", network()))
        add(Row("Server", serverHost()))
        if (config.isConfigured) add(Row("Link", if (AgentStatus.wsConnected) "Live" else "HTTP only",
            if (AgentStatus.wsConnected) Tone.OK else Tone.WARN))
    }

    private fun healthRows(): List<Row> = buildList {
        add(Row("Storage", storage()))
        battery()?.let { add(Row("Battery", it)) }
        add(Row("Up for", duration(SystemClock.elapsedRealtime())))
    }

    private fun lockRows(): List<Row> = buildList {
        add(if (!config.kioskEnabled) Row("Lock", "Off", Tone.NEUTRAL) else Row("Lock", kiosk(), Tone.INFO))
        add(Row("System updates", updatePolicy()))
        add(Row("Agent", BuildConfig.VERSION_NAME))
    }

    /** The badge's QR: this device's page on the dashboard (signing in is needed to see it). */
    private fun renderQr(serial: String) {
        val base = config.serverUrl.trim().trimEnd('/')
        if (base.isEmpty() || serial.isEmpty()) { binding.qrRow.visibility = View.GONE; return }
        val url = "$base/devices/" + Uri.encode(serial)
        if (url == qrFor) return
        qrFor = url
        runCatching {
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 0))
            val bmp = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
            for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) 0xFF1D1B20.toInt() else 0xFFFFFFFF.toInt())
            binding.qrImage.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bmp).apply { isFilterBitmap = false })
            binding.qrHost.text = Uri.parse(base).host.orEmpty()
            binding.qrRow.visibility = View.VISIBLE
        }.onFailure { binding.qrRow.visibility = View.GONE }
    }

    private fun renderRows(container: LinearLayout, rows: List<Row>) {
        if (rendered[container.id] == rows) return
        rendered[container.id] = rows
        container.removeAllViews()
        rows.forEachIndexed { i, r ->
            if (i > 0) container.addView(View(this).apply { setBackgroundColor(color(R.color.ui_border_soft)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(40)
                setPadding(0, dp(8), 0, dp(8))
            }
            line.addView(text(r.label, 13f, R.color.ui_muted),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f))
            val value = text(r.value, 13f, R.color.ui_text, weight = 500).apply {
                gravity = Gravity.END
                if (r.mono) typeface = android.graphics.Typeface.MONOSPACE
                if (r.tone != null) styleTag(this, r.value, r.tone)
            }
            val holder = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            holder.addView(value)
            line.addView(holder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f))
            container.addView(line)
        }
    }

    /** Capabilities as tags: green for full support, amber where this device limits them. */
    private fun renderCapabilities() {
        val limited = Capabilities.degraded(this)
        val full = Capabilities.supported(this).filter { it !in limited }
        val key = full to limited
        if (rendered[R.id.capabilities] == key) return
        rendered[R.id.capabilities] = key
        val group = binding.capabilities
        group.removeAllViews()
        (full.map { it to Tone.OK } + limited.map { it to Tone.WARN }).forEach { (cap, tone) ->
            group.addView(Chip(this).apply {
                text = CAPABILITY_NAMES[cap] ?: cap.replace('_', ' ').replaceFirstChar { it.uppercase() }
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.poppins_medium)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(fg(tone))
                chipBackgroundColor = ColorStateList.valueOf(bg(tone))
                chipStrokeWidth = 0f
                chipMinHeight = dp(28).toFloat()
                chipCornerRadius = dp(40).toFloat()
                setEnsureMinTouchTargetSize(false)
                isClickable = false
                isFocusable = false
                isCheckable = false
            })
        }
    }

    /** The disclosure commercial MDMs show: what is collected, and when the screen is seen. */
    private fun renderPrivacy() {
        val lines = listOf(
            "Device details: model, serial number, Android version and this app's version",
            "Health: battery, storage, memory, network and uptime",
            "Which apps are installed, and how long each is on screen",
            "Nearby Wi-Fi networks, which the MDM turns into an approximate location",
            "A GPS location, only while location reporting is on (currently ${if (config.locationEnabled) "on" else "off"})",
            "During a remote support session: the screen, and device logs",
        )
        if (rendered[R.id.privacy] == lines) return
        rendered[R.id.privacy] = lines
        binding.privacy.removeAllViews()
        lines.forEach { l ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, dp(5))
            }
            line.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.ui_accent2)) }
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { topMargin = dp(7); marginEnd = dp(12) })
            line.addView(text(l, 13f, R.color.ui_text2))
            binding.privacy.addView(line)
        }
    }

    /** Two columns side by side on a wide screen (a TV, a tablet in landscape), stacked on a phone. */
    private fun layoutColumns() {
        if (resources.configuration.screenWidthDp < 720) return
        // S3: the badge beside the state and the short cards.
        binding.top.orientation = LinearLayout.HORIZONTAL
        binding.badge.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        binding.right.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(16) }
        binding.columns.orientation = LinearLayout.HORIZONTAL
        binding.col1.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(16) }
        binding.col2.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        binding.page.setPadding(dp(32), dp(24), dp(32), dp(24))
    }

    // ── What the device knows ────────────────────────────────────────────────────

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    private fun serverHost(): String =
        config.serverUrl.takeIf { it.isNotBlank() }?.let { Uri.parse(it).host } ?: "Not set"

    private fun network(): String = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(net)
        val kind = when {
            caps == null -> "Connected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifiName()?.let { "Wi-Fi · $it" } ?: "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            else -> "Connected"
        }
        val ip = cm.getLinkProperties(net)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }
        if (ip != null) "$kind · ${ip.hostAddress}" else kind
    }.getOrDefault("Unknown")

    /** The SSID, when the platform lets us read it (it needs location on newer Android). */
    @Suppress("DEPRECATION")
    private fun wifiName(): String? = runCatching {
        applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid
            ?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }.getOrNull()

    private fun storage(): String = runCatching {
        val st = StatFs(Environment.getDataDirectory().path)
        "${gb(st.availableBytes)} free of ${gb(st.totalBytes)}"
    }.getOrDefault("Unknown")

    /** Battery level, or null on a mains-powered box (TV sticks report no battery present). */
    private fun battery(): String? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        if (!i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return "None · mains powered"
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "${level * 100 / scale} %" + if (charging) " · charging" else ""
    }

    private fun kiosk(): String = when (config.kioskMode) {
        "browser" -> "Browser · " + (Uri.parse(config.kioskUrl).host ?: config.kioskUrl.ifBlank { "no URL" })
        else -> {
            val main = config.kioskPackage.ifBlank { null }?.let(::appLabel) ?: "Launcher"
            val extra = config.kioskExtraPackages().size
            "App · $main" + if (extra > 0) " +$extra" else ""
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun updatePolicy(): String {
        val p = runCatching { JSONObject(config.updatePolicyJson) }.getOrNull() ?: return "Device default"
        return when (p.optString("mode", "default")) {
            "automatic" -> "Install automatically"
            "windowed" -> "Install ${clock(p.optInt("window_start", 0))}–${clock(p.optInt("window_end", 240))}"
            "postpone" -> "Postponed up to 30 days"
            else -> "Device default"
        } + if ((p.optJSONArray("freeze_periods")?.length() ?: 0) > 0) " · freeze periods" else ""
    }

    private fun managedNetwork(): String {
        val n = runCatching { JSONObject(config.networkConfigJson) }.getOrNull() ?: return "None"
        val parts = buildList {
            n.optJSONArray("wifi_networks")?.length()?.takeIf { it > 0 }?.let { add(plural(it, "Wi-Fi network")) }
            n.optJSONArray("ca_certs")?.length()?.takeIf { it > 0 }?.let { add(plural(it, "CA certificate")) }
            n.optJSONObject("vpn")?.optString("package")?.takeIf { it.isNotBlank() }?.let { add("VPN") }
        }
        return parts.joinToString(" · ").ifBlank { "None" }
    }

    // ── Small helpers ────────────────────────────────────────────────────────────

    private fun jsonArrayLength(json: String): Int = runCatching { org.json.JSONArray(json).length() }.getOrDefault(0)

    private fun plural(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"

    private fun clock(minutes: Int) = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1e9)

    private fun ago(atMs: Long): String {
        if (atMs <= 0) return "never"
        val s = ((System.currentTimeMillis() - atMs) / 1000).coerceAtLeast(0)
        return when {
            s < 5 -> "just now"
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            s < 86_400 -> "${s / 3600} h ${(s % 3600) / 60} min"
            else -> "${s / 86_400} d ${(s % 86_400) / 3600} h"
        }
    }

    /** .cc-tag: a pill with a 10% wash of the tone and its text in the tone. */
    private fun styleTag(v: TextView, label: String, tone: Tone) {
        v.text = label
        v.setTextColor(fg(tone))
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        v.typeface = ResourcesCompat.getFont(this, R.font.poppins_semibold)
        v.setPadding(dp(10), dp(4), dp(10), dp(4))
        v.background = GradientDrawable().apply { cornerRadius = dp(40).toFloat(); setColor(bg(tone)) }
    }

    private fun fg(t: Tone) = color(when (t) {
        Tone.OK -> R.color.ui_ok
        Tone.WARN -> R.color.ui_warn
        Tone.BAD -> R.color.ui_bad
        Tone.INFO -> R.color.ui_info
        Tone.NEUTRAL -> R.color.ui_muted
    })

    private fun bg(t: Tone) = color(when (t) {
        Tone.OK -> R.color.ui_ok_bg
        Tone.WARN -> R.color.ui_warn_bg
        Tone.BAD -> R.color.ui_bad_bg
        Tone.INFO -> R.color.ui_info_bg
        Tone.NEUTRAL -> R.color.ui_neutral_bg
    })

    private fun text(s: String, sp: Float, colorRes: Int, weight: Int = 400) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color(colorRes))
        typeface = ResourcesCompat.getFont(this@MainActivity,
            if (weight >= 500) R.font.poppins_medium else R.font.poppins_regular)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        /** Capability keys as an operator would say them. */
        val CAPABILITY_NAMES = mapOf(
            "kiosk" to "Kiosk",
            "install_apk" to "App install",
            "uninstall" to "App removal",
            "self_update" to "Agent updates",
            "reboot" to "Reboot",
            "wipe" to "Remote wipe",
            "unenroll" to "Unenroll",
            "move_server" to "Move server",
            "config" to "Remote config",
            "telemetry" to "Health reporting",
            "shell" to "Shell",
            "screen_capture" to "Remote screen",
            "input" to "Remote input",
            "logcat" to "Device logs",
        )
    }
}
