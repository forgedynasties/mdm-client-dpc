package aio.app.mdmclient.dpc

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import aio.app.mdmclient.dpc.kiosk.KioskState
import aio.app.mdmclient.dpc.kiosk.StaffMode
import aio.app.mdmclient.dpc.capture.ScreenCaptureConsentActivity
import aio.app.mdmclient.dpc.capture.ScreenCaptureService
import aio.app.mdmclient.dpc.device.AppUsageReport
import aio.app.mdmclient.dpc.device.BleListener
import aio.app.mdmclient.dpc.device.BleProtocol
import aio.app.mdmclient.dpc.device.ScreenControl
import aio.app.mdmclient.dpc.device.AgentUpdater
import aio.app.mdmclient.dpc.device.LogcatManager
import aio.app.mdmclient.dpc.net.Acker
import aio.app.mdmclient.dpc.net.AdbTunnel
import aio.app.mdmclient.dpc.net.ApiClient
import aio.app.mdmclient.dpc.net.CommandExecutor
import aio.app.mdmclient.dpc.net.OfflineQueue
import aio.app.mdmclient.dpc.net.Telemetry
import aio.app.mdmclient.dpc.net.TelemetryDelta
import aio.app.mdmclient.dpc.net.WsClient
import okio.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * Long-running foreground service: hosts the periodic HTTP checkin loop and the live WebSocket,
 * routes server messages to [CommandExecutor], and acks results.
 *
 * The WS carries realtime push (commands, config, pings) and, while it is up, telemetry as
 * deltas ([TelemetryDelta]) — the firmware client's model. The HTTP check-in is the full
 * keyframe and app inventory: every [HTTP_SAFETY_NET_MS] while the socket is live, every
 * tick while it is not.
 */
class MdmService : LifecycleService(), WsClient.Listener, Acker {

    private lateinit var config: AgentConfig
    private lateinit var deviceOwner: DeviceOwner
    private lateinit var api: ApiClient
    private lateinit var ws: WsClient
    private lateinit var executor: CommandExecutor
    private lateinit var adbTunnel: AdbTunnel
    private lateinit var logcat: LogcatManager
    private lateinit var serial: String

    @Volatile private var wantConnected = false
    @Volatile private var lastKnownAppsHash: String? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private val delta = TelemetryDelta()
    @Volatile private var lastHttpCheckinAt = 0L
    // Offline queue (0.5.0): when the last HTTP check-in failed and the socket is down, a
    // reading goes into OfflineQueue every OFFLINE_SAMPLE_MS; it is flushed on reconnect.
    @Volatile private var lastCheckinOk = true
    @Volatile private var lastOfflineSampleAt = 0L
    private val flushing = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Set by a server "wake" frame so the next poll tick runs even with a healthy socket. */
    private val pollCommandsNow = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastCommandPollAt = 0L
    // Touched on every sign the service's checkin loop and WS reader are actually making
    // progress (loop tick, inbound WS message). A shell command starting a persistent `sh`
    // process via ProcessBuilder forks this process — on at least two of the device families
    // tested (RBOX, Sunmi KDS), that intermittently wedges the whole process with no
    // exception or crash, so nothing else here can detect it from the inside. The watchdog
    // below is the backstop: if nothing has ticked in watchdogTimeoutMs, the process is
    // killed outright so Android restarts it (START_STICKY) rather than leaving a device
    // silently dark until someone finds it and reboots it by hand.
    @Volatile private var lastProgressAtMs = SystemClock.elapsedRealtime()
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        config = AgentConfig.get(this)
        deviceOwner = DeviceOwner(this)
        api = ApiClient(config).also { it.onUnauthorized = { onUnauthorized() } }
        ws = WsClient(config, this)
        executor = CommandExecutor(this, deviceOwner, this, lifecycleScope)
        adbTunnel = AdbTunnel(config)
        deviceOwner.ensureIdentityAccess() // grant READ_PHONE_STATE before resolving the serial
        deviceOwner.ensureNotificationAccess()
        // Location like the firmware client (0.9.1): the permission and location on, so the
        // Wi-Fi scan it sends can be turned into a position by the server. A GPS fix is
        // still only reported while the fleet's location_enabled policy is on (or lost).
        deviceOwner.ensureLocationAccess()
        deviceOwner.ensureAutoTime()
        Grants.ensureAccessibility(this)
        serial = DeviceIdentity.serial(this)
        logcat = LogcatManager(this, serial)

        goForeground()
        Notices.clearStale(this)
        Log.i(TAG, "MdmService started (deviceOwner=${deviceOwner.isDeviceOwner}, configured=${config.isConfigured})")

        AgentBus.acker = this

        // An agent update the previous version started: this one says how it went.
        AgentUpdater.settlePending(this) { id, status, output ->
            ackCommand(id, status, output = output)
        }

        // Re-enforce any persisted kiosk state after a (re)boot.
        if (deviceOwner.isDeviceOwner) executor.applyConfig(JSONObject())
        startLockGuard()

        maybeStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Handed back by an unenroll command. Whatever restarted us — boot, the alarm, the
        // launcher — this device is not managed any more and must not start reporting again.
        if (config.unenrolled) {
            Log.i(TAG, "unenrolled: not connecting")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CHECKIN_NOW && wantConnected) {
            lifecycleScope.launch(Dispatchers.IO) { doCheckin() }
        }
        // A restart (e.g. after Save & connect, or provisioning extras landing) may have added config.
        if (!wantConnected) maybeStart()
        return START_STICKY
    }

    /** Connect if configured; if we only hold an enrollment token, exchange it first. */
    private fun maybeStart() {
        when {
            config.isConfigured -> startTransport()
            config.needsEnrollment -> enrollThenStart()
        }
    }

    /**
     * The server rejected our device key. A key is per-device and never comes back, so
     * the only way forward is to enroll again — and this device can, whenever it still
     * knows the token it enrolled with. Without this the agent sits on a dead key and
     * repeats a 401 forever, which is exactly what it did after the onboarding screen
     * cleared a working key.
     */
    fun onUnauthorized() {
        val token = config.enrollToken.ifBlank { config.lastEnrollToken }
        if (token.isBlank()) return
        Log.w(TAG, "Device key rejected — re-enrolling with the token this device came with")
        config.apiKey = ""
        config.enrollToken = token
        enrollThenStart()
    }

    @Volatile private var enrolling = false

    private fun enrollThenStart() {
        if (enrolling) return
        enrolling = true
        AgentStatus.enrolling = true
        lifecycleScope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && config.needsEnrollment) {
                val identity = JSONObject()
                    .put("serial", serial)
                    .put("product", DeviceIdentity.product())
                    .put("model", Build.MODEL)
                    .put("os_version", Build.VERSION.RELEASE ?: "")
                val resp = api.enroll(config.enrollToken, identity)
                val key = resp?.optString("device_key").orEmpty()
                if (key.isNotBlank()) {
                    config.apiKey = key
                    config.enrollToken = ""
                    // Where the device landed, per the server (profile intent): class, site,
                    // group. Empty fields mean the profile did not set them.
                    val r = resp ?: JSONObject() // non-null here: the key came out of it
                    val parts = mutableListOf(if (r.optBoolean("re_enrolled")) "Re-enrolled" else "Enrolled")
                    r.optString("device_class").takeIf { it.isNotBlank() }?.let { parts += it.uppercase() }
                    r.optString("site").takeIf { it.isNotBlank() }?.let { parts += it }
                    r.optString("group").takeIf { it.isNotBlank() }?.let { parts += it }
                    r.optString("profile").takeIf { it.isNotBlank() }?.let { parts += "via $it" }
                    if (!r.optBoolean("onboarded", true)) parts += "awaiting a site on the dashboard"
                    config.enrollSummary = parts.joinToString(" · ")
                    Log.i(TAG, "Enrolled — device key issued (${config.enrollSummary})")
                    startTransport()
                    break
                }
                // Server unreachable or token rejected: back off and retry (a revoked token
                // keeps failing here, visible in the onboarding screen's status).
                AgentStatus.checkinResult(false, api.lastError)
                Notices.refreshStatus(this@MdmService)
                attempt++
                delay((30_000L * attempt).coerceAtMost(300_000L))
            }
            enrolling = false
            AgentStatus.enrolling = false
        }
    }

    // ---- Transport lifecycle ----

    private fun startTransport() {
        wantConnected = true
        connectWs()
        startCheckinLoop()
        startAppUsageLoop()
        startWatchdog()
        startCommandPollLoop()
        startBleListenLoop()
    }

    /**
     * Lost-device listening (protocol B): while the server's `lost_listen` names lost devices at
     * this venue, scan every `every_s` for `scan_s` and report at once when one was heard.
     * Nothing touches the radio while there is nothing to listen for.
     */
    private fun startBleListenLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && wantConnected) {
                val cfg = BleProtocol.parseListen(config.lostListenJson)
                if (cfg == null) {
                    BleListener.idle()
                    delay(BLE_IDLE_CHECK_MS)
                    continue
                }
                val heard = runCatching { BleListener.scanOnce(this@MdmService, deviceOwner, cfg) }
                    .getOrElse { Log.w(TAG, "ble scan: ${it.message}"); false }
                // A hit is worth reporting now, not at the next check-in tick.
                if (heard) { if (ws.isOpen) pushTelemetry() else doCheckin() }
                delay((cfg.everyS - cfg.scanS).coerceAtLeast(5) * 1000L)
            }
        }
    }

    // ---- Lock guard (0.12.1, 27 Oct 2026) ----
    // DP02253X11001: locked from the dashboard, then the POS app updated itself, and the
    // device stayed out of the lock while the dashboard said locked: nothing re-applied it,
    // because the config had not changed. Two things put it back now: an install or update of
    // a locked app re-applies the lock a few seconds later, and every LOCK_GUARD_MS the lock
    // the config asks for is compared with the lock task the device is really in.

    private var lockGuardJob: Job? = null
    private var lastReassertMs = 0L
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            val locked = StaffMode.lockedTo(c, AgentConfig.get(c)) ?: return
            if (pkg in locked) {
                Log.i(TAG, "locked app $pkg (re)installed: lock back on")
                lifecycleScope.launch(Dispatchers.Main) { delay(3_000L); reassertLock() }
            }
        }
    }

    private fun startLockGuard() {
        runCatching {
            registerReceiver(pkgReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            })
        }.onFailure { Log.w(TAG, "package receiver: ${it.message}") }
        lockGuardJob?.cancel()
        lockGuardJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(LOCK_GUARD_MS)
                if (!deviceOwner.isDeviceOwner) continue
                val cfg = AgentConfig.get(this@MdmService)
                if (cfg.lostOn || StaffMode.lockedTo(this@MdmService, cfg) == null) continue
                if (!KioskState.inLockTask(this@MdmService) &&
                    SystemClock.elapsedRealtime() - lastReassertMs > 60_000L) {
                    Log.w(TAG, "lock guard: the config locks this device, but it is not in lock task: putting it back")
                    reassertLock()
                }
            }
        }
    }

    private fun reassertLock() {
        lastReassertMs = SystemClock.elapsedRealtime()
        runCatching { executor.reassertLock() }.onFailure { Log.w(TAG, "re-lock: ${it.message}") }
    }

    /** Self-heal a wedged process — see [lastProgressAtMs]. */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && wantConnected) {
                delay(WATCHDOG_CHECK_MS)
                val stuckFor = SystemClock.elapsedRealtime() - lastProgressAtMs
                if (stuckFor >= WATCHDOG_TIMEOUT_MS) {
                    Log.e(TAG, "watchdog: no progress in ${stuckFor}ms, killing process to recover")
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    private fun connectWs() {
        if (!wantConnected) return
        Log.i(TAG, "Connecting WS...")
        ws.connect(serial)
    }

    /**
     * Ask the server for queued commands on its own cadence, independent of telemetry.
     *
     * Deliberately not the check-in schedule: command latency and telemetry volume are
     * different problems, and making check-ins minutely to carry commands would multiply
     * this fleet's telemetry writes to fix a delivery bug. While the socket is healthy this
     * does nothing — the server sends a content-free "wake" frame when there is work, which
     * is instant. The loop covers the case where that frame can never arrive: a dead
     * socket, a refused upgrade, a proxy that drops WebSockets. The watchdog can restart a
     * wedged process, but it cannot unblock a path that is blocked, which is why polling is
     * the thing that makes this agent reachable rather than merely restartable.
     */
    private fun startCommandPollLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            delay(15_000L) // let enrolment and the first check-in settle
            while (isActive && wantConnected) {
                try {
                    // A dial that hung (no timeout on the TLS handshake) never reports back, so
                    // nothing else would ever redial: cancel it and start over.
                    if (ws.isStuckDialing()) {
                        Log.w(TAG, "WS dial hung — cancelling and redialling")
                        ws.cancel()
                        withContext(Dispatchers.Main) { connectWs() }
                    }
                    val nudged = pollCommandsNow.getAndSet(false)
                    // An open socket is not proof that frames arrive: a half-open one reads
                    // as open and swallows every wake, which is exactly how a device ends up
                    // queued-but-untouched. Polling at a floor cadence even then makes that
                    // the worst case — minutes, not forever.
                    val floorDue = SystemClock.elapsedRealtime() - lastCommandPollAt >= COMMAND_POLL_FLOOR_MS
                    if (!ws.isOpen || nudged || floorDue) {
                        lastCommandPollAt = SystemClock.elapsedRealtime()
                        api.fetchPendingCommands(serial)?.let { runHttpCommands(it) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "command poll tick: ${e.message}")
                }
                delay(COMMAND_POLL_MS)
            }
        }
    }

    /**
     * Commands handed back by a poll or a check-in response. Everything downstream is the
     * code a pushed frame uses: the executor acks receipt, dedups by id and runs it, and
     * Acker already prefers HTTP when the socket is down — so a command delivered this way
     * behaves exactly like a pushed one, only later.
     */
    private fun runHttpCommands(resp: JSONObject) {
        val cmds = resp.optJSONArray("commands") ?: return
        if (cmds.length() == 0) return
        Log.i(TAG, "Took ${cmds.length()} command(s) over HTTP")
        for (i in 0 until cmds.length()) {
            val cmd = cmds.optJSONObject(i) ?: continue
            try {
                executor.handleCommand(cmd)
            } catch (e: Exception) {
                Log.e(TAG, "HTTP command ${cmd.optString("id")} failed: ${e.message}")
            }
        }
    }

    /** Per-app usage every AppUsageReport.INTERVAL_MS (0.6.0); the window waits for a 200. */
    private fun startAppUsageLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            delay(2 * 60 * 1000L)
            while (isActive && wantConnected) {
                runCatching {
                    val (body, to) = AppUsageReport.build(this@MdmService, DeviceIdentity.serial(this@MdmService))
                    if (api.appUsage(body)) AppUsageReport.sent(this@MdmService, to)
                }.onFailure { Log.w(TAG, "app usage: ${it.message}") }
                delay(AppUsageReport.INTERVAL_MS)
            }
        }
    }

    private fun startCheckinLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && wantConnected) {
                lastProgressAtMs = SystemClock.elapsedRealtime()
                val httpDue = SystemClock.elapsedRealtime() - lastHttpCheckinAt >= HTTP_SAFETY_NET_MS
                // The app list only travels in an HTTP check-in. Until the server has it
                // (first check-in failed, or it asked again with send_apps), check in over
                // HTTP every cycle instead of waiting for the 5-minute safety net: a new
                // device showed no apps and got no host app for 6 minutes (28 Oct 2026).
                val appsOwed = lastKnownAppsHash == null
                if (!ws.isOpen || httpDue || lastHttpCheckinAt == 0L || appsOwed) doCheckin() else pushTelemetry()
                maybeQueueOffline()
                delay(config.checkinIntervalSeconds * 1000L)
            }
        }
    }

    private fun doCheckin() {
        // Send the app inventory only when the hash changed (or the server last asked for it).
        val currentHash = Telemetry.appsHash(this)
        val includeApps = currentHash != lastKnownAppsHash
        val payload = Telemetry.buildCheckin(this, includeApps)
        lastHttpCheckinAt = SystemClock.elapsedRealtime()
        val resp = api.checkin(payload)
        AgentStatus.checkinResult(resp != null, api.lastError)
        Notices.refreshStatus(this@MdmService)
        lastCheckinOk = resp != null
        if (resp == null) return
        flushOfflineQueue()
        // The server replaced latest_extra with this snapshot: it is the new delta baseline.
        delta.remember(payload)
        lastKnownAppsHash = currentHash
        if (resp.optBoolean("send_apps", false) && !includeApps) {
            // Server wants the full list next time regardless of hash.
            lastKnownAppsHash = null
        }
        resp.optJSONObject("config")?.let { executor.applyConfig(it) }
        // Free backup channel: if the response already carries queued work, run it rather
        // than waiting for the next poll.
        runHttpCommands(resp)
    }

    // ---- WsClient.Listener ----

    override fun onOpen() {
        AgentStatus.wsConnected = true
        Notices.refreshStatus(this@MdmService)
        reconnectAttempt = 0
        sendKeyframe()
        lifecycleScope.launch(Dispatchers.IO) { flushOfflineQueue() }
    }

    /** Keeps a reading while nothing reaches the server: socket down and the last check-in failed. */
    private fun maybeQueueOffline() {
        if (ws.isOpen || lastCheckinOk) return
        val now = SystemClock.elapsedRealtime()
        if (lastOfflineSampleAt != 0L && now - lastOfflineSampleAt < OFFLINE_SAMPLE_MS) return
        lastOfflineSampleAt = now
        runCatching { OfflineQueue.add(this, Telemetry.offlineReading(this)) }
            .onFailure { Log.w(TAG, "offline reading failed: ${it.message}") }
    }

    /** Sends the kept readings, 200 at a time, removing each batch only once the server took it. */
    private fun flushOfflineQueue() {
        if (!flushing.compareAndSet(false, true)) return
        try {
            while (true) {
                val batch = OfflineQueue.peek(this, OFFLINE_BATCH)
                if (batch.isEmpty()) break
                val body = JSONObject().apply {
                    put("serial", DeviceIdentity.serial(this@MdmService))
                    put("now_ms", System.currentTimeMillis())
                    put("now_elapsed_ms", SystemClock.elapsedRealtime())
                    put("boot_id", Telemetry.bootId(this@MdmService))
                    put("dropped", OfflineQueue.dropped(this@MdmService))
                    put("readings", JSONArray(batch))
                }
                if (api.backfill(body) == null) break
                OfflineQueue.removeFirst(this, batch.size)
                OfflineQueue.clearDropped(this)
                Log.i(TAG, "Sent ${batch.size} offline reading(s)")
            }
        } finally {
            flushing.set(false)
            lastOfflineSampleAt = 0L
        }
    }

    override fun onMessage(msg: JSONObject) {
        lastProgressAtMs = SystemClock.elapsedRealtime()
        when (msg.optString("type")) {
            "command" -> executor.handleCommand(msg)
            "config" -> executor.applyConfig(msg)
            // The server asks on every check-in interval, so this is a delta tick like the
            // firmware client's, not a keyframe — a keyframe here was a full snapshot every 30 s.
            "telemetry_request" -> lifecycleScope.launch(Dispatchers.IO) { pushTelemetry() }
            "ping_request" -> sendWs(JSONObject().apply {
                put("type", "pong_response")
                put("nonce", msg.opt("nonce"))
            })
            // "There is work, come and get it" — no payload, so a frame lost on a
            // half-open socket costs one poll interval instead of a command.
            "wake" -> {
                Log.i(TAG, "Wake from server (${msg.optString("reason")}) — polling for commands")
                pollCommandsNow.set(true)
                lifecycleScope.launch(Dispatchers.IO) {
                    api.fetchPendingCommands(serial)?.let { runHttpCommands(it) }
                }
            }
            "checkin_now" -> lifecycleScope.launch(Dispatchers.IO) { doCheckin() }
            "cancel_command" -> executor.cancel(msg.optString("id"))
            // The server sends this on an operator's wake (remote control), and on its own
            // when kiosk is switched on — a device that wakes to a black locked screen is
            // the same as one that never woke.
            "wake_screen" -> ScreenControl.wake(this)
            "start_capture" -> startCapture(msg)
            // An admin's `adb connect` reached the server: carry adbd to it (AdbTunnel).
            "adb_tunnel_open" -> adbTunnel.open(serial, msg)
            "adb_tunnel_close" -> adbTunnel.closeSession(msg.optString("session"))
            "stop_capture" -> ScreenCaptureService.stop(this)
            "input_event" -> AgentBus.input?.handle(msg)
                ?: Log.w(TAG, "input_event ignored — accessibility service not enabled")
            "logcat_request" -> lifecycleScope.launch(Dispatchers.IO) { logcat.oneShot(msg) }
            "start_logcat_stream" -> logcat.startStream(msg)
            "stop_logcat_stream" -> logcat.stopStream(msg.optString("request_id"))
            else -> Log.d(TAG, "unhandled WS type=${msg.optString("type")}")
        }
    }

    override fun onClosed(reason: String, code: Int) {
        AgentStatus.wsConnected = false
        Notices.refreshStatus(this@MdmService)
        if (!wantConnected) return
        // Equal jitter, as the firmware client does: a server restart drops the whole fleet
        // at once, and without it every device retries at 1s, 2s, 4s… in lockstep.
        val base = BACKOFF_MS[reconnectAttempt.coerceIn(0, BACKOFF_MS.lastIndex)]
        // 1012 = the server is restarting (a deploy). The other copy is still up, so there is
        // no hurry: spread the whole fleet over RESTART_SPREAD_MS instead of every device
        // landing on it in the same second (0.13.5). Check-ins carry on over HTTP meanwhile.
        val delayMs = if (code == 1012) Random.nextLong(RESTART_SPREAD_MS + 1)
            else base / 2 + Random.nextLong(base / 2 + 1)
        reconnectAttempt++
        Log.i(TAG, "WS closed ($reason); reconnecting in ${delayMs}ms")
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            delay(delayMs)
            connectWs()
        }
    }

    // ---- Acker ----

    override fun sendWs(msg: JSONObject): Boolean = ws.send(msg)

    override fun sendBinary(bytes: ByteString): Boolean = ws.sendBinary(bytes)

    private fun startCapture(msg: JSONObject) {
        // A capture of a sleeping device is a black rectangle, and the operator cannot tell
        // that from a broken stream. Wake first — the system-app client does the same at
        // session start.
        ScreenControl.wake(this)
        ScreenCaptureConsentActivity.launch(
            ctx = this,
            codec = msg.optString("codec", "h264"),
            quality = msg.optInt("quality", 70),
            scale = msg.optDouble("scale", 0.75).toFloat(),
            fps = msg.optInt("max_fps", 15),
            bitrate = msg.optInt("bitrate", 4_000_000),
        )
    }

    override fun ackCommand(commandId: String, status: String, output: String?, progress: Int?, pkg: String?) {
        if (ws.isOpen) {
            val frame = JSONObject().apply {
                put("type", "command_ack")
                put("command_id", commandId)
                put("serial_number", serial)
                put("status", status)
                output?.let { put("output", it) }
                progress?.let { put("progress", it) }
                pkg?.let { put("package", it) }
            }
            if (ws.send(frame)) return
        }
        // HTTP fallback (endpoint doesn't want the type/command_id-in-body; id is in the path).
        val body = JSONObject().apply {
            put("serial_number", serial)
            put("status", status)
            output?.let { put("output", it) }
            progress?.let { put("progress", it) }
            pkg?.let { put("package", it) }
        }
        lifecycleScope.launch(Dispatchers.IO) { api.ackCommand(commandId, body) }
    }

    /** The whole snapshot over the socket, on (re)connect: it re-establishes the baseline. */
    private fun sendKeyframe() {
        delta.forceKeyframe = true
        lifecycleScope.launch(Dispatchers.IO) { pushTelemetry() }
    }

    /** One telemetry tick over the WS: a delta, or nothing when nothing changed. */
    @Synchronized
    private fun pushTelemetry() {
        if (!ws.isOpen) return
        val full = Telemetry.buildCheckin(this, includeApps = false)
        val frame = delta.frameFor(full)
        if (frame != null) {
            frame.put("type", "telemetry")
            if (!ws.send(frame)) {
                delta.forceKeyframe = true // the next send re-establishes the baseline
                return
            }
            delta.remember(full)
        }
        // Sent, or skipped because the server already has it: either way the device is
        // reporting over a live link, which is what the status screen's check-in row means.
        AgentStatus.checkinResult(true, null)
        Notices.refreshStatus(this@MdmService)
    }

    override fun onDestroy() {
        wantConnected = false
        AgentStatus.wsConnected = false
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        logcat.stopAll()
        ws.close()
        if (AgentBus.acker === this) AgentBus.acker = null
        super.onDestroy()
    }

    // ---- Foreground notification ----

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, Notices.status(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, Notices.status(this))
        }
    }

    companion object {
        /** How often to look at `lost_listen` while there is nothing to listen for. */
        private const val BLE_IDLE_CHECK_MS = 30_000L
        private const val TAG = "MdmService"
        private const val NOTIF_ID = 1001
        private val BACKOFF_MS = longArrayOf(1000, 2000, 4000, 8000, 30000)
        private const val RESTART_SPREAD_MS = 30_000L
        private const val OFFLINE_SAMPLE_MS = 5 * 60 * 1000L
        private const val OFFLINE_BATCH = 200
        /** Full HTTP keyframe cadence while the WS is live (the firmware client's safety net). */
        private const val HTTP_SAFETY_NET_MS = 5 * 60_000L
        // Command poll cadence while there is no usable socket. Telemetry keeps its own
        // five-minute keyframe: this dial is command latency, nothing else.
        private const val COMMAND_POLL_MS = 60_000L
        /** Poll at least this often even with an open socket — see the floor check. */
        private const val COMMAND_POLL_FLOOR_MS = 5 * 60_000L
        /** Watchdog poll cadence. */
        private const val WATCHDOG_CHECK_MS = 30_000L
        private const val LOCK_GUARD_MS = 30_000L
        /** No checkin-loop tick and no inbound WS message in this long = treat as wedged. */
        private const val WATCHDOG_TIMEOUT_MS = 3 * 60_000L

        /** Status screen's "Check in now": one check-in right away, outside the interval. */
        const val ACTION_CHECKIN_NOW = "aio.app.mdmclient.dpc.CHECKIN_NOW"

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, MdmService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
