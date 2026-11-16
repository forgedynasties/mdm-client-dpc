package aio.app.mdmclient.dpc

import android.content.Context

/**
 * Prefs-backed runtime config: server URL + device API key, overridable from the onboarding
 * screen. Build-time defaults come from [BuildConfig]. Mirrors the system-app client's
 * `persist.sys.mdm.*` overrides, but a normal app can't read system props, so we persist here.
 *
 * Stored in device-protected storage so it survives Direct Boot (before the user unlocks),
 * matching the boot-time checkin behavior of the original client.
 */
class AgentConfig private constructor(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, BuildConfig.DEFAULT_SERVER_URL)!!
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, BuildConfig.DEFAULT_API_KEY)!!
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    /**
     * Pending enrollment token from a QR/zero-touch provisioning payload (or manual entry).
     * Exchanged once at POST /api/v1/enroll for a per-device key, then cleared — so a stolen
     * QR code can be revoked server-side without rotating the whole fleet's credentials.
     */
    var enrollToken: String
        get() = prefs.getString(KEY_ENROLL_TOKEN, "")!!
        set(value) = prefs.edit().putString(KEY_ENROLL_TOKEN, value.trim()).apply()

    /**
     * What the server said when this device enrolled ("Enrolled · MPOS · Bayside Pizza"),
     * shown on the status screen so the tech knows the device landed where intended.
     */
    var enrollSummary: String
        get() = prefs.getString(KEY_ENROLL_SUMMARY, "")!!
        set(value) = prefs.edit().putString(KEY_ENROLL_SUMMARY, value.trim()).apply()

    /** The enrollment token this device last exchanged for a key, so re-saving the same
     *  token (it stays in the onboarding field) cannot wipe the key it bought. */
    // A move to another server not yet proven (0.15.2): where the device came from, so it can
    // go back if the new server never accepts a call within MOVE_CONFIRM_MS. moveAtMs = 0 when
    // no move is pending. moveReverted is the report for the old server (JSON), sent with
    // the next check-in there and then cleared.
    var movedFromUrl: String
        get() = prefs.getString(KEY_MOVED_FROM_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MOVED_FROM_URL, value).apply()
    var movedFromKey: String
        get() = prefs.getString(KEY_MOVED_FROM_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MOVED_FROM_KEY, value).apply()
    var movedFromToken: String
        get() = prefs.getString(KEY_MOVED_FROM_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MOVED_FROM_TOKEN, value).apply()
    var moveAtMs: Long
        get() = prefs.getLong(KEY_MOVE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_MOVE_AT, value).apply()
    var moveReverted: String
        get() = prefs.getString(KEY_MOVE_REVERTED, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MOVE_REVERTED, value).apply()

    fun clearPendingMove() {
        prefs.edit().remove(KEY_MOVED_FROM_URL).remove(KEY_MOVED_FROM_KEY)
            .remove(KEY_MOVED_FROM_TOKEN).remove(KEY_MOVE_AT).apply()
    }

    var lastEnrollToken: String
        get() = prefs.getString(KEY_LAST_ENROLL_TOKEN, "")!!
        set(value) = prefs.edit().putString(KEY_LAST_ENROLL_TOKEN, value.trim()).apply()

    /** True once the user has saved a server URL + key (onboarding complete enough to connect). */
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    /** True when we have a server + enrollment token but no device key yet: enroll first. */
    val needsEnrollment: Boolean
        get() = serverUrl.isNotBlank() && apiKey.isBlank() && enrollToken.isNotBlank()

    /**
     * Seed config from a managed-provisioning admin-extras bundle (QR / zero-touch).
     * Recognised keys: `server_url`, `enroll_token`, and `api_key` (direct shared-key
     * fallback for closed setups without enrollment profiles). Ignores blanks so a
     * partial bundle can't wipe out already-working config.
     */
    fun seedFromProvisioningExtras(extras: android.os.PersistableBundle) {
        extras.getString("server_url")?.takeIf { it.isNotBlank() }?.let { serverUrl = it }
        extras.getString("enroll_token")?.takeIf { it.isNotBlank() }?.let { enrollToken = it }
        extras.getString("api_key")?.takeIf { it.isNotBlank() }?.let { apiKey = it }
    }

    // ---- Server-pushed config (checkin `config` object / WS `config` frame) ----

    var checkinIntervalSeconds: Int
        get() = prefs.getInt(KEY_CHECKIN_INTERVAL, DEFAULT_CHECKIN_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_CHECKIN_INTERVAL, value.coerceIn(10, 3600)).apply()

    var kioskEnabled: Boolean
        get() = prefs.getBoolean(KEY_KIOSK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_KIOSK_ENABLED, value).apply()

    var kioskPackage: String
        get() = prefs.getString(KEY_KIOSK_PACKAGE, "")!!
        set(value) = prefs.edit().putString(KEY_KIOSK_PACKAGE, value).apply()

    /** Raw server `update_policy` object (JSON string, "" = none) — persisted so boot re-applies it. */
    var updatePolicyJson: String
        get() = prefs.getString(KEY_UPDATE_POLICY, "")!!
        set(value) = prefs.edit().putString(KEY_UPDATE_POLICY, value).apply()

    // ---- Kiosk modes (single-app is kioskPackage; these extend it) ----

    /** "app" (lock to kioskPackage, plus kioskExtraPackages in a launcher grid) or "browser". */
    var kioskMode: String
        get() = prefs.getString(KEY_KIOSK_MODE, "app")!!
        set(value) = prefs.edit().putString(KEY_KIOSK_MODE, value.ifBlank { "app" }).apply()

    /** Extra allow-listed packages for multi-app kiosk (JSON array of package names). */
    var kioskPackagesJson: String
        get() = prefs.getString(KEY_KIOSK_PACKAGES, "[]")!!
        set(value) = prefs.edit().putString(KEY_KIOSK_PACKAGES, value.ifBlank { "[]" }).apply()

    var kioskUrl: String
        get() = prefs.getString(KEY_KIOSK_URL, "")!!
        set(value) = prefs.edit().putString(KEY_KIOSK_URL, value.trim()).apply()

    /** Allowed URL prefixes for browser kiosk (JSON array; empty = same-origin as kioskUrl). */
    var kioskUrlAllowJson: String
        get() = prefs.getString(KEY_KIOSK_URL_ALLOW, "[]")!!
        set(value) = prefs.edit().putString(KEY_KIOSK_URL_ALLOW, value.ifBlank { "[]" }).apply()

    /** Server-controlled switch for location telemetry (off = never touch location APIs). */
    var locationEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCATION_ENABLED, value).apply()

    /** Raw network-provisioning config: {"ca_certs":[...],"wifi_networks":[...],"vpn":{...}}. */
    var networkConfigJson: String
        get() = prefs.getString(KEY_NETWORK_CONFIG, "")!!
        set(value) = prefs.edit().putString(KEY_NETWORK_CONFIG, value).apply()

    /** Raw managed app configurations: [{"package": "...", "restrictions": {...}}]. */
    var appRestrictionsJson: String
        get() = prefs.getString(KEY_APP_RESTRICTIONS, "")!!
        set(value) = prefs.edit().putString(KEY_APP_RESTRICTIONS, value).apply()

    /**
     * A self-update this agent started, as "commandId|versionCode|versionName". Written
     * before the install (which kills this process) and settled by the new version at
     * startup — see AgentUpdater. Empty when no update is in flight.
     */
    var pendingUpdate: String
        get() = prefs.getString(KEY_PENDING_UPDATE, "")!!
        set(value) = prefs.edit().putString(KEY_PENDING_UPDATE, value).apply()

    /**
     * Set by an `unenroll` command: this device has been handed back. It is kept across a
     * restart on purpose — a device that stopped being managed must not start reporting again
     * because something restarted the service or the phone rebooted.
     */
    var unenrolled: Boolean
        get() = prefs.getBoolean(KEY_UNENROLLED, false)
        set(value) = prefs.edit().putBoolean(KEY_UNENROLLED, value).apply()

    // ---- Lost mode (the `lost_mode` command; see device/LostMode) ----
    // Kept apart from the kiosk keys on purpose: lost mode sits on top of the lock and must
    // not overwrite it, so lifting it can put the device back exactly as it was.

    val lostOn: Boolean get() = prefs.getBoolean(KEY_LOST_ON, false)
    val lostSinceMs: Long get() = prefs.getLong(KEY_LOST_SINCE, 0L)
    val lostMessage: String get() = prefs.getString(KEY_LOST_MESSAGE, "")!!
    val lostPhone: String get() = prefs.getString(KEY_LOST_PHONE, "")!!
    /** The server's `lost_listen` object as JSON, "" when there is nothing to listen for. */
    var lostListenJson: String
        get() = prefs.getString(KEY_LOST_LISTEN, "")!!
        set(value) = prefs.edit().putString(KEY_LOST_LISTEN, value).apply()
    /** This lost session's BLE token (16 hex chars), "" when the server sent none. */
    val lostBeacon: String get() = prefs.getString(KEY_LOST_BEACON, "")!!

    /** Written synchronously: a reboot right after the command must still find the device lost. */
    fun setLost(on: Boolean, message: String = "", phone: String = "", beacon: String = "") {
        prefs.edit()
            .putBoolean(KEY_LOST_ON, on)
            .putLong(KEY_LOST_SINCE, if (on) (lostSinceMs.takeIf { lostOn } ?: System.currentTimeMillis()) else 0L)
            .putString(KEY_LOST_MESSAGE, if (on) message else "")
            .putString(KEY_LOST_PHONE, if (on) phone else "")
            .putString(KEY_LOST_BEACON, if (on) beacon else "")
            .commit()
    }

    /**
     * Drop everything that would let this agent talk to a server again: the address, the key
     * and any token. Called on unenroll, so what is left on the device is inert.
     */
    fun forgetServer() {
        prefs.edit()
            .remove(KEY_SERVER_URL).remove(KEY_API_KEY).remove(KEY_ENROLL_TOKEN)
            .putBoolean(KEY_UNENROLLED, true)
            .apply()
    }

    fun kioskExtraPackages(): List<String> = jsonStringList(kioskPackagesJson)

    fun kioskUrlAllow(): List<String> = jsonStringList(kioskUrlAllowJson)

    private fun jsonStringList(json: String): List<String> = try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Absorb a server `config` object (from the checkin response or a WS `config` frame). Kiosk
     * enforcement itself happens in the service (Phase 2); here we just persist the desired state.
     * Returns true if the checkin interval changed (so the caller can reschedule).
     */
    fun applyServerConfig(cfg: org.json.JSONObject): Boolean {
        var intervalChanged = false
        if (cfg.has("checkin_interval_seconds")) {
            val newInterval = cfg.optInt("checkin_interval_seconds", checkinIntervalSeconds)
            if (newInterval != checkinIntervalSeconds) {
                checkinIntervalSeconds = newInterval
                intervalChanged = true
            }
        }
        if (cfg.has("kiosk_enabled")) kioskEnabled = cfg.optBoolean("kiosk_enabled", false)
        if (cfg.has("kiosk_package")) kioskPackage = cfg.optString("kiosk_package", "")
        if (cfg.has("kiosk_mode")) kioskMode = cfg.optString("kiosk_mode", "app")
        if (cfg.has("kiosk_packages")) kioskPackagesJson = cfg.optJSONArray("kiosk_packages")?.toString().orEmpty()
        if (cfg.has("kiosk_url")) kioskUrl = cfg.optString("kiosk_url", "")
        if (cfg.has("kiosk_url_allow")) kioskUrlAllowJson = cfg.optJSONArray("kiosk_url_allow")?.toString().orEmpty()
        if (cfg.has("update_policy")) {
            updatePolicyJson = cfg.optJSONObject("update_policy")?.toString().orEmpty()
        }
        if (cfg.has("location_enabled")) locationEnabled = cfg.optBoolean("location_enabled", false)
        if (cfg.has("network")) networkConfigJson = cfg.optJSONObject("network")?.toString().orEmpty()
        if (cfg.has("app_restrictions")) {
            appRestrictionsJson = cfg.optJSONArray("app_restrictions")?.toString().orEmpty()
        }
        // Lost-device listening (BleListener). The key is present only while a device at this
        // venue is lost; a whole config (one that carries the kiosk keys) without it means stop.
        if (cfg.has("lost_listen")) {
            lostListenJson = cfg.optJSONObject("lost_listen")?.toString().orEmpty()
        } else if (cfg.has("kiosk_enabled")) {
            lostListenJson = ""
        }
        return intervalChanged
    }

    /** Derived WebSocket URL: http(s) -> ws(s), path /api/v1/ws?serial=... */
    fun wsUrl(serial: String): String {
        val base = serverUrl
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$wsBase/api/v1/ws?serial=${android.net.Uri.encode(serial)}"
    }

    fun apiUrl(path: String): String = serverUrl + path

    companion object {
        private const val PREFS = "mdm_agent"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ENROLL_TOKEN = "enroll_token"
        private const val KEY_ENROLL_SUMMARY = "enroll_summary"
        private const val KEY_LAST_ENROLL_TOKEN = "last_enroll_token"
        private const val KEY_MOVED_FROM_URL = "moved_from_url"
        private const val KEY_MOVED_FROM_KEY = "moved_from_key"
        private const val KEY_MOVED_FROM_TOKEN = "moved_from_token"
        private const val KEY_MOVE_AT = "move_at_ms"
        private const val KEY_MOVE_REVERTED = "move_reverted"
        private const val KEY_CHECKIN_INTERVAL = "checkin_interval"
        private const val KEY_KIOSK_ENABLED = "kiosk_enabled"
        private const val KEY_KIOSK_PACKAGE = "kiosk_package"
        private const val KEY_UPDATE_POLICY = "update_policy"
        private const val KEY_KIOSK_MODE = "kiosk_mode"
        private const val KEY_KIOSK_PACKAGES = "kiosk_packages"
        private const val KEY_KIOSK_URL = "kiosk_url"
        private const val KEY_KIOSK_URL_ALLOW = "kiosk_url_allow"
        private const val KEY_LOCATION_ENABLED = "location_enabled"
        private const val KEY_NETWORK_CONFIG = "network_config"
        private const val KEY_PENDING_UPDATE = "pending_update"
        private const val KEY_APP_RESTRICTIONS = "app_restrictions"
        private const val KEY_UNENROLLED = "unenrolled"
        private const val KEY_LOST_ON = "lost_on"
        private const val KEY_LOST_SINCE = "lost_since_ms"
        private const val KEY_LOST_MESSAGE = "lost_message"
        private const val KEY_LOST_PHONE = "lost_phone"
        private const val KEY_LOST_BEACON = "lost_beacon"
        private const val KEY_LOST_LISTEN = "lost_listen"
        private const val DEFAULT_CHECKIN_INTERVAL = 30

        @Volatile private var instance: AgentConfig? = null

        fun get(context: Context): AgentConfig =
            instance ?: synchronized(this) {
                // Use device-protected storage context so config is readable pre-unlock at boot.
                val ctx = context.applicationContext
                val storageCtx = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    ctx.createDeviceProtectedStorageContext()
                } else ctx
                instance ?: AgentConfig(storageCtx).also { instance = it }
            }
    }
}
