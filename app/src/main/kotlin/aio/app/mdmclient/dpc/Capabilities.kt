package aio.app.mdmclient.dpc

import android.content.Context

/**
 * What this DPC agent can and cannot do, reported to the server on checkin as
 * `extra.agent_type` + `extra.capabilities` so the dashboard can gray out unsupported actions
 * instead of letting operators fire commands that will only fail.
 *
 * A Device Owner on a stock (non-platform-signed) device loses a handful of the system-app
 * client's powers — see the capability matrix in the plan. Keep this list honest: only advertise
 * a capability once its handler is actually implemented.
 */
object Capabilities {

    const val AGENT_TYPE = "dpc"

    /** Fully supported via DevicePolicyManager / PackageInstaller. */
    private val base: List<String> = listOf(
        "kiosk",
        "install_apk",
        "uninstall",
        // Agent OTA: install a newer build of this app over itself (AgentUpdater).
        "self_update",
        "reboot",
        "wipe",
        // Hand the device back: drop Device Owner and stop reporting, keeping the data.
        "unenroll",
        // The lost screen, pinned until the server lifts it (LostMode).
        "lost_mode",
        // Saved Wi-Fi networks: add/update in place, remove (WifiConfigurator).
        "wifi_config",
        "config",
        "telemetry",
        // Implemented by ShellSession, but always the unprivileged app UID — so it is
        // advertised and also listed as degraded, rather than withheld entirely.
        "shell",
        // Save to library: upload an installed app's APK (ApkPuller, 0.11.0).
        "apk_pull",
        // Move to another MDM server: enroll there, then switch (0.13.4).
        "move_server",
        // Split APK bundles (.xapk / .apks / .apkm): every part in one session (0.15.0).
        "apk_bundle",
    )

    /**
     * Degraded relative to the system app — unless the adb grants from tools/enroll-adb.sh
     * are in place, which lift each one to full support without root:
     *  - screen_capture: MediaProjection consent per session; PROJECT_MEDIA appop = silent
     *  - input: AccessibilityService, taps/swipes + nav/D-pad keys; full once it is enabled
     *  - logcat: app-scoped only; READ_LOGS = whole-device logcat
     *  - shell: real persistent sh, always the unprivileged app UID
     */
    private fun upgraded(ctx: Context): Map<String, Boolean> = mapOf(
        "screen_capture" to Grants.projectMediaAllowed(ctx),
        "input" to Grants.accessibilityEnabled(ctx),
        "logcat" to Grants.readLogs(ctx),
        "shell" to false, // never "full": an app UID shell, not root or system
    )

    /**
     * Lost-device Bluetooth (protocol B), only on hardware that has it: listening for other
     * lost devices' beacons, and advertising this device's own while it is lost.
     */
    private fun bluetooth(ctx: Context): List<String> {
        val radio = aio.app.mdmclient.dpc.device.BleRadio(ctx, DeviceOwner(ctx))
        if (!radio.hasLe) return emptyList()
        return if (radio.canAdvertise) listOf("ble_listen", "ble_beacon") else listOf("ble_listen")
    }

    fun supported(ctx: Context): List<String> = base + bluetooth(ctx) + upgraded(ctx).filterValues { it }.keys

    fun degraded(ctx: Context): List<String> = upgraded(ctx).filterValues { !it }.keys.toList()

    /** Not possible without system UID / platform signature. */
    val unsupported: List<String> = listOf(
        "logcat_full",    // whole-system READ_LOGS
        "update_splash",  // init-broker partition write
    )
}
