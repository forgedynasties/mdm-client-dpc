package aio.app.mdmclient.dpc.net

import android.content.Context
import android.content.Intent
import android.util.Log
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.DeviceOwner
import aio.app.mdmclient.dpc.Grants
import aio.app.mdmclient.dpc.capture.ScreenCaptureConsentActivity
import aio.app.mdmclient.dpc.device.AgentUpdater
import aio.app.mdmclient.dpc.device.ApkInstaller
import aio.app.mdmclient.dpc.device.ApkPuller
import aio.app.mdmclient.dpc.device.KioskManager
import aio.app.mdmclient.dpc.device.LostMode
import aio.app.mdmclient.dpc.device.WifiConfigurator
import aio.app.mdmclient.dpc.device.ShellSession
import aio.app.mdmclient.dpc.device.AppRestrictionsManager
import aio.app.mdmclient.dpc.device.NetworkProvisioner
import aio.app.mdmclient.dpc.device.UpdatePolicyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import aio.app.mdmclient.dpc.kiosk.StaffMode
import org.json.JSONObject
import java.util.Collections

/**
 * Routes a server `command` frame to the right Device Owner action.
 *
 * Implemented (Phase 2): install_apk, app_update, uninstall, reboot, wipe, config (kiosk);
 * since 0.3.0 lost_mode and wifi_config.
 * Screen/input and diagnostics land in later phases. Unsupported types (update_splash, anything
 * unknown) fail with a clear reason so the dashboard shows why instead of hanging. Firmware OTA
 * (the system partition) is still not here — app_update is this agent updating itself.
 */
class CommandExecutor(
    private val ctx: Context,
    private val deviceOwner: DeviceOwner,
    private val acker: Acker,
    private val scope: CoroutineScope,
) {
    private val config = AgentConfig.get(ctx)
    private val installer = ApkInstaller(ctx, config)
    private val kiosk = KioskManager(ctx, deviceOwner)
    private val lostMode = LostMode(ctx, deviceOwner, kiosk)
    private val wifi = WifiConfigurator(ctx, deviceOwner)
    private val shell = ShellSession(acker)
    private val cancelled = Collections.synchronizedSet(mutableSetOf<String>())

    fun handleCommand(frame: JSONObject) {
        val id = frame.optString("id")
        val type = frame.optString("command_type").ifBlank { frame.optString("type") }
        if (id.isBlank()) {
            Log.w(TAG, "command frame missing id: $frame")
            return
        }
        val payload = frame.optJSONObject("payload") ?: JSONObject()
        Log.i(TAG, "command $id type=$type")
        acker.ackCommand(id, "received")

        when (type) {
            "install_apk" -> installApk(id, frame, payload)
            "app_update" -> selfUpdate(id, frame, payload)
            "uninstall" -> uninstall(id, payload)
            "reboot" -> reboot(id)
            "wipe" -> wipe(id)
            "unenroll" -> unenroll(id)
            "config" -> applyConfigCommand(id, payload)
            "screenshot" -> screenshot(id)
            "shell" -> shellCmd(id, frame, payload)
            "lost_mode" -> lostModeCmd(id, payload)
            "wifi_config" -> wifiConfig(id, payload)
            "pull_apk" -> pullApk(id, payload)
            "move_server" -> moveServer(id, payload)

            "update_splash" -> unsupported(id, "boot splash requires system partition access")
            else -> unsupported(id, "unknown command type: $type")
        }
    }

    /** Put the lock back as the config has it (0.12.1): the lock guard and an update of the locked app. */
    fun reassertLock() {
        if (config.lostOn) lostMode.reassert() else kiosk.apply(config)
    }

    /** Apply a server `config` frame/payload (kiosk etc.). Shared by WS config messages. */
    fun applyConfig(cfg: JSONObject) {
        config.applyServerConfig(cfg)
        // Staff mode (0.10.0): the venue's staff PIN, the answer to an exit, and whether the
        // lock is back on at the server (which answers a re-lock done on the device).
        StaffMode.applyServer(
            ctx,
            cfg.optJSONObject("offline_exit")?.optString("pin"),
            cfg.optLong("offline_exit_ack", 0),
            if (cfg.has("kiosk_enabled")) cfg.optBoolean("kiosk_enabled") else null,
            cfg.optJSONObject("offline_exit")?.let { if (it.has("venue")) it.optString("venue") else null },
        )
        // While lost, the lock is stored but not applied: applying it would put the locked
        // app back over the lost screen. Lifting lost mode applies it then.
        if (config.lostOn) lostMode.reassert() else kiosk.apply(config)
        UpdatePolicyManager.apply(deviceOwner, config.updatePolicyJson)
        deviceOwner.ensureLocationAccess() // kept on: the Wi-Fi scan needs it (0.9.1)
        NetworkProvisioner.apply(ctx, deviceOwner, config.networkConfigJson)
        AppRestrictionsManager.apply(deviceOwner, config.appRestrictionsJson)
    }

    fun cancel(commandId: String) {
        cancelled.add(commandId)
    }

    // ---- handlers ----

    /**
     * Save to library (0.11.0): upload an installed app's APK; see ApkPuller.
     *
     * 0.11.1: a pull runs once per command id. The server hands a command out again when
     * its lease runs out, and 0.11.0 started a second upload of the same APK each time,
     * the two competing until S3 timed one out. A repeat while it runs is ignored (the
     * heartbeat below keeps the lease), and a repeat after it ended gets the same answer.
     */
    private val pulls = Collections.synchronizedMap(LinkedHashMap<String, Pair<String, String>?>())

    private fun pullApk(id: String, payload: JSONObject) {
        val pkg = payload.optString("package")
        synchronized(pulls) {
            if (pulls.containsKey(id)) {
                pulls[id]?.let { (status, out) -> acker.ackCommand(id, status, output = out, pkg = pkg) }
                return
            }
            pulls[id] = null
            while (pulls.size > 50) pulls.remove(pulls.keys.first())
        }
        scope.launch(Dispatchers.IO) {
            val pct = java.util.concurrent.atomic.AtomicInteger(0)
            acker.ackCommand(id, "running", progress = 0)
            // A heartbeat every 10 s whatever the upload does, so the lease never lapses.
            val beat = launch {
                while (true) {
                    delay(10_000)
                    acker.ackCommand(id, "running", progress = pct.get())
                }
            }
            var last = -1
            val result = try {
                ApkPuller(ctx, config).pull(id, pkg) { p ->
                    pct.set(p)
                    if (p != last) {
                        last = p
                        acker.ackCommand(id, "running", progress = p)
                    }
                }
            } finally {
                beat.cancel()
            }
            val final = if (result.ok) "completed" to result.message else "failed" to result.message
            pulls[id] = final
            acker.ackCommand(id, final.first, output = final.second, pkg = if (result.ok) pkg else null)
        }
    }

    private fun installApk(id: String, frame: JSONObject, payload: JSONObject) {
        val url = frame.optString("apk_url").ifBlank { payload.optString("apk_url") }
        if (url.isBlank()) {
            acker.ackCommand(id, "failed", output = "missing apk_url")
            return
        }
        // Installing our own package is an agent update, not an app install: it has to be
        // verified and acknowledged by the version that replaces us.
        if (AgentUpdater.isSelf(ctx, payload.optString("package"))) {
            selfUpdate(id, frame, payload)
            return
        }
        scope.launch(Dispatchers.IO) {
            acker.ackCommand(id, "downloading", progress = 0)
            var lastReported = -1
            val result = installer.install(url) { pct ->
                if (cancelled.contains(id)) return@install
                if (pct - lastReported >= 5 || pct == 100) {
                    lastReported = pct
                    val status = if (pct >= 100) "installing" else "downloading"
                    acker.ackCommand(id, status, progress = pct)
                }
            }
            when {
                cancelled.remove(id) -> acker.ackCommand(id, "cancelled")
                result.success -> acker.ackCommand(id, "installed", output = "ok", pkg = result.pkg)
                else -> acker.ackCommand(id, "failed", output = result.message)
            }
        }
    }

    /**
     * One frame of the screen, acked as a base64 PNG. Goes through the consent activity
     * like live capture does: with the PROJECT_MEDIA appop granted (tools/enroll-adb.sh)
     * it is silent, otherwise someone confirms the projection prompt once. The ack comes
     * from the capture service, not from here.
     */
    private fun screenshot(id: String) {
        // The accessibility service takes a still with no prompt (0.14.1); MediaProjection, the
        // fallback, asks on the device every time on Android 14.
        val a11y = aio.app.mdmclient.dpc.AgentBus.input as? aio.app.mdmclient.dpc.input.MdmAccessibilityService
        if (a11y != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            a11y.screenshotPng { png, why ->
                if (png != null) acker.ackCommand(id, "completed", output = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP))
                else { Log.w(TAG, "screenshot $id: $why, trying MediaProjection"); projectionScreenshot(id) }
            }
            return
        }
        projectionScreenshot(id)
    }

    private fun projectionScreenshot(id: String) {
        if (!Grants.projectMediaAllowed(ctx)) {
            // Still possible — it just needs the prompt — so this is a note, not a failure.
            Log.i(TAG, "screenshot $id: no PROJECT_MEDIA appop, the device will show the consent prompt")
        }
        try {
            ScreenCaptureConsentActivity.launchStill(ctx, id)
        } catch (e: Exception) {
            acker.ackCommand(id, "failed", output = "could not start capture: ${e.message}")
        }
    }

    /**
     * Agent OTA: install a newer build of this app over the running one. Silent, because a
     * Device Owner installs without a prompt. No terminal ack is sent from here on success —
     * the install kills this process and the new version settles the command at startup.
     */
    private fun selfUpdate(id: String, frame: JSONObject, payload: JSONObject) {
        val url = frame.optString("apk_url").ifBlank { payload.optString("apk_url") }
        scope.launch(Dispatchers.IO) {
            acker.ackCommand(id, "downloading", progress = 0)
            var lastReported = -1
            AgentUpdater.run(
                ctx = ctx,
                installer = installer,
                id = id,
                apkUrl = url,
                payload = payload,
                onProgress = { pct ->
                    if (pct - lastReported >= 5 || pct == 100) {
                        lastReported = pct
                        val status = if (pct >= 100) "installing" else "downloading"
                        acker.ackCommand(id, status, progress = pct)
                    }
                },
            ) { status, output -> acker.ackCommand(id, status, output = output) }
        }
    }

    private fun uninstall(id: String, payload: JSONObject) {
        val pkg = payload.optString("package")
        if (pkg.isBlank()) {
            acker.ackCommand(id, "failed", output = "missing package")
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = installer.uninstall(pkg)
            if (result.success) acker.ackCommand(id, "completed", output = "uninstalled $pkg", pkg = pkg)
            else acker.ackCommand(id, "failed", output = result.message, pkg = pkg)
        }
    }

    private fun reboot(id: String) {
        if (!deviceOwner.isDeviceOwner) {
            acker.ackCommand(id, "failed", output = "not device owner")
            return
        }
        // dpm.reboot() requires no activity in the foreground to actually take effect; on at
        // least one Sunmi unit it otherwise returned normally (no exception) and never
        // rebooted at all, with no way to tell from here. Send the device home first so a
        // kiosk screen or our own status screen doesn't silently block it.
        sendHome()
        // Ack before rebooting; the server confirms completion on the device's reconnect.
        acker.ackCommand(id, "completed", output = "rebooting")
        scope.launch(Dispatchers.Default) {
            delay(400) // let the home transition land before the foreground-activity check
            try {
                deviceOwner.dpm.reboot(deviceOwner.admin)
            } catch (e: Exception) {
                acker.ackCommand(id, "failed", output = "reboot rejected: ${e.message}")
            }
        }
    }

    private fun sendHome() {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }.onFailure { Log.w(TAG, "sendHome failed: ${it.message}") }
    }

    private fun wipe(id: String) {
        if (!deviceOwner.isDeviceOwner) {
            acker.ackCommand(id, "failed", output = "not device owner")
            return
        }
        acker.ackCommand(id, "completed", output = "wiping")
        try {
            deviceOwner.dpm.wipeData(0)
        } catch (e: Exception) {
            acker.ackCommand(id, "failed", output = "wipe rejected: ${e.message}")
        }
    }

    /**
     * Hand the device back: drop Device Owner, forget the server, stop reporting — and keep
     * everything that is on the device. The opposite of [wipe], for a unit leaving the fleet
     * rather than being erased.
     *
     * The ack goes first, because every step after it takes away the means of sending one.
     *
     * Removing this app is best effort and usually will not happen on its own: Android
     * refuses to uninstall the active Device Owner, and once Device Owner is cleared a silent
     * uninstall is no longer ours to make — the dialog needs somebody at the screen. So the
     * agent makes itself inert instead (no server, no key, and [AgentConfig.unenrolled] set so
     * a reboot cannot start it again) and offers the uninstall for whoever is standing there.
     */
    /**
     * Move to another MDM (0.13.4): the firmware client's move-server, for a normal app.
     * The command names the new server and an enrollment token minted there. The device
     * enrolls with it first, while still talking to this server, so a bad URL or token fails
     * here and changes nothing; only once the new server has issued a key does it report
     * success, switch address and key, and restart its connection there.
     */
    private fun moveServer(id: String, payload: JSONObject) {
        val target = payload.optString("server_url").trim().trimEnd('/')
        val token = payload.optString("enroll_token").trim()
        if (!target.startsWith("https://") || android.net.Uri.parse(target).host.isNullOrBlank()) {
            acker.ackCommand(id, "failed", output = "not a server URL: $target")
            return
        }
        if (token.isBlank()) {
            acker.ackCommand(id, "failed", output = "no enrollment token for $target")
            return
        }
        if (target == config.serverUrl) {
            acker.ackCommand(id, "completed", output = "already on $target")
            return
        }
        scope.launch(Dispatchers.IO) {
            acker.ackCommand(id, "running", output = "enrolling with $target")
            val identity = JSONObject()
                .put("serial", aio.app.mdmclient.dpc.DeviceIdentity.serial(ctx))
                .put("product", aio.app.mdmclient.dpc.DeviceIdentity.product())
                .put("model", android.os.Build.MODEL)
                .put("os_version", android.os.Build.VERSION.RELEASE ?: "")
            val api = ApiClient(config)
            val resp = api.enroll(token, identity, target)
            val key = resp?.optString("device_key").orEmpty()
            if (key.isBlank()) {
                acker.ackCommand(id, "failed", output = "$target refused: ${api.lastError.ifBlank { "no key" }}")
                return@launch
            }
            acker.ackCommand(id, "completed", output = "moved to $target")
            delay(3_000) // let the ack leave over this server's socket before it closes
            config.serverUrl = target
            config.apiKey = key
            config.enrollToken = ""
            config.lastEnrollToken = token
            config.enrollSummary = "Moved to " + (android.net.Uri.parse(target).host ?: target)
            Log.i(TAG, "moved to $target")
            // Restart from the main thread: stopping the service cancels this coroutine's scope.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val svc = android.content.Intent(ctx, aio.app.mdmclient.dpc.MdmService::class.java)
                ctx.stopService(svc)
                aio.app.mdmclient.dpc.MdmService.start(ctx)
            }
        }
    }

    private fun unenroll(id: String) {
        acker.ackCommand(id, "completed", output = "unenrolling")
        val wasOwner = deviceOwner.isDeviceOwner
        // A lost device being handed back stops being lost: no one could lift it afterwards.
        if (config.lostOn) runCatching { lostMode.exit() }
        if (wasOwner) {
            try {
                // Leave kiosk first: lock task outlives Device Owner on some builds, and a
                // device stuck in a locked app with no way to manage it is worse than enrolled.
                kiosk.apply(enabled = false, allowedPackages = emptyList())
            } catch (e: Exception) {
                Log.w(TAG, "kiosk off before unenroll: ${e.message}")
            }
            try {
                deviceOwner.dpm.clearDeviceOwnerApp(ctx.packageName)
            } catch (e: Exception) {
                Log.w(TAG, "clearDeviceOwnerApp: ${e.message}")
            }
        }
        config.forgetServer()
        Log.i(TAG, "unenrolled (was device owner: $wasOwner)")
        try {
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_DELETE)
                    .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.i(TAG, "no uninstall prompt: ${e.message}")
        }
        ctx.stopService(android.content.Intent(ctx, aio.app.mdmclient.dpc.MdmService::class.java))
    }

    /**
     * "shell" command routed to the persistent [ShellSession] — a real long-lived `sh` where
     * cd/env/state persist across commands. Runs as the app's unprivileged UID. Output streams
     * live via command_output; completion + exit code via command_done (both emitted by the
     * session).
     */
    private fun shellCmd(id: String, frame: JSONObject, payload: JSONObject) {
        val cmd = payload.optString("cmd").ifBlank { payload.optString("command") }
            .ifBlank { frame.optString("cmd") }
        scope.launch(Dispatchers.IO) { shell.run(id, cmd) }
    }

    /**
     * `lost_mode` {"on": true, "message", "phone"} / {"on": false}. Acked once the lost screen
     * is up (or after a few seconds, saying whether it is), so the dashboard's "done" means
     * the device is actually showing it.
     */
    private fun lostModeCmd(id: String, payload: JSONObject) {
        if (!payload.has("on")) {
            acker.ackCommand(id, "failed", output = JSONObject().put("error", "missing on").toString())
            return
        }
        val on = payload.optBoolean("on", false)
        scope.launch(Dispatchers.Main) {
            if (!on) {
                lostMode.exit()
                acker.ackCommand(id, "completed", output = JSONObject().put("lost_mode", false).toString())
                return@launch
            }
            val message = payload.optString("message").trim().take(280)
            val phone = payload.optString("phone").trim().take(40)
            // The session's Bluetooth LE token, when the server made one (protocol B).
            val beacon = payload.optString("beacon").trim()
            if (!lostMode.enter(message, phone, beacon)) {
                acker.ackCommand(id, "failed", output = JSONObject().put("error", "not device owner").toString())
                return@launch
            }
            val shown = kotlinx.coroutines.withContext(Dispatchers.IO) { lostMode.awaitShown(5_000) }
            acker.ackCommand(id, "completed",
                output = JSONObject().put("lost_mode", true).put("shown", shown).toString())
        }
    }

    /** `wifi_config` {"action": "add"|"remove", "ssid", "security", "password", "hidden"}. */
    private fun wifiConfig(id: String, payload: JSONObject) {
        val action = payload.optString("action")
        val ssid = payload.optString("ssid")
        scope.launch(Dispatchers.IO) {
            val r = when (action) {
                "add" -> wifi.addOrUpdate(
                    ssid, payload.optString("security", "wpa2"), payload.optString("password"),
                    payload.optBoolean("hidden", false),
                )
                "remove" -> wifi.remove(ssid)
                "connect" -> {
                    // Reversible switch (0.7.0): up to 90 s, acked once back online.
                    val out = wifi.connect(ssid)
                    acker.ackCommand(id, if (out.optBoolean("ok", false)) "completed" else "failed", output = out.toString())
                    return@launch
                }
                else -> WifiConfigurator.Result(false, "unknown action: $action")
            }
            if (r.ok) {
                acker.ackCommand(id, "completed", output = JSONObject()
                    .put("ok", true).put("ssid", ssid).put("action", action).toString())
            } else {
                acker.ackCommand(id, "failed", output = JSONObject().put("error", r.error).toString())
            }
        }
    }

    private fun applyConfigCommand(id: String, payload: JSONObject) {
        try {
            applyConfig(payload)
            acker.ackCommand(id, "completed", output = "config applied")
        } catch (e: Exception) {
            acker.ackCommand(id, "failed", output = "config error: ${e.message}")
        }
    }

    private fun notYetImplemented(id: String, type: String) {
        acker.ackCommand(id, "failed", output = "'$type' not implemented yet")
    }

    private fun unsupported(id: String, reason: String) {
        acker.ackCommand(id, "failed", output = "unsupported on DPC agent: $reason")
    }

    companion object {
        private const val TAG = "CommandExec"
    }
}
