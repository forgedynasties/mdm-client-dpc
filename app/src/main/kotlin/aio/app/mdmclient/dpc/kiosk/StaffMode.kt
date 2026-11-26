package aio.app.mdmclient.dpc.kiosk

import android.content.Context
import android.content.SharedPreferences
import aio.app.mdmclient.dpc.AgentConfig

/**
 * Staff mode (0.10.0), the Standard client's version of the tablets' staff exit: on a locked
 * device, holding the top-left corner for 5 seconds (or, on a TV box, the D-pad code) opens the
 * staff PIN pad; the venue's staff PIN takes the device out of the lock and opens the locked
 * app normally, with a "Back to guest mode" pill that locks it again.
 *
 * It speaks the tablets' protocol, so the server needs nothing new for it:
 *  - offline_exit.pin    the venue's staff PIN (default 147147, like the tablets)
 *  - offline_exit_at     reported once staff mode starts; the server answers with the lock
 *                        off and offline_exit_ack, and raises the "taken out on site" alert
 *  - kiosk_staff_mode    true while staff mode is on (the dashboard's Staff mode chip)
 *  - kiosk_relocked_at   reported after Back to guest mode until the config has the lock on
 *
 * Until each answer arrives a stale config could undo what staff just did, so the lock the
 * device should be in is decided here ([lockedTo]) rather than straight from the config.
 */
object StaffMode {
    private const val PREFS = "mdm_staff"
    private const val K_PIN = "pin"
    private const val K_VENUE = "venue"
    private const val K_PKG = "staff_pkg"          // host app staff mode left, "" = not in staff mode
    private const val K_EXIT_AT = "exit_at"        // epoch secs, 0 = none waiting for the server
    private const val K_RELOCK_AT = "relock_at"    // epoch secs, 0 = none waiting
    private const val K_RELOCK_PKG = "relock_pkg"
    private const val K_FAILS = "fails"
    private const val K_LOCKOUT = "lockout_until"
    private const val K_LOCK_OFF = "lock_off"      // the server says locking is switched off (0.17.3)

    const val DEFAULT_PIN = "147147"
    private const val MAX_FAILS = 5
    private const val LOCKOUT_MS = 60_000L

    /** Called when staff mode starts or ends, so the accessibility service redraws its pill. */
    @Volatile var onChange: (() -> Unit)? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pin(ctx: Context): String = prefs(ctx).getString(K_PIN, DEFAULT_PIN)!!.ifEmpty { DEFAULT_PIN }
    /** The venue whose PIN the pad wants ("" when the device has none or the server is older). */
    fun venue(ctx: Context): String = prefs(ctx).getString(K_VENUE, "")!!
    fun staffPackage(ctx: Context): String = prefs(ctx).getString(K_PKG, "")!!
    fun isOn(ctx: Context): Boolean = staffPackage(ctx).isNotEmpty()
    fun pendingExitAt(ctx: Context): Long = prefs(ctx).getLong(K_EXIT_AT, 0)
    fun pendingRelockAt(ctx: Context): Long = prefs(ctx).getLong(K_RELOCK_AT, 0)

    /**
     * The server has locking switched off (config lock_allowed false, 26 Nov 2026): the
     * device is never locked, staff mode and a re-lock it set itself are dropped, and the
     * pill, the corner and the D-pad code stay away. Before this, "Back to guest mode" kept a
     * device locked until the server said kiosk_enabled true, which a server with locking off
     * never says. Kept on the device so it holds offline and across reboots.
     */
    fun lockOff(ctx: Context): Boolean = prefs(ctx).getBoolean(K_LOCK_OFF, false)

    /** The server's lock_allowed (null when an older server doesn't send it). */
    fun applyLockAllowed(ctx: Context, allowed: Boolean?) {
        if (allowed == null) return
        val p = prefs(ctx)
        if (allowed) {
            if (p.getBoolean(K_LOCK_OFF, false)) p.edit().putBoolean(K_LOCK_OFF, false).apply()
            return
        }
        val had = isOn(ctx) || pendingRelockAt(ctx) > 0
        p.edit().putBoolean(K_LOCK_OFF, true)
            .putString(K_PKG, "").putLong(K_RELOCK_AT, 0).putString(K_RELOCK_PKG, "").putLong(K_EXIT_AT, 0)
            .commit()
        if (had) onChange?.invoke()
    }

    /** The server's offline_exit block (a venue PIN) and offline_exit_ack. */
    fun applyServer(ctx: Context, offlineExitPin: String?, ack: Long, kioskEnabled: Boolean?, venue: String? = null) {
        val p = prefs(ctx)
        val e = p.edit()
        if (!offlineExitPin.isNullOrBlank()) e.putString(K_PIN, offlineExitPin)
        if (venue != null) e.putString(K_VENUE, venue)
        if (ack > 0 && ack == p.getLong(K_EXIT_AT, 0)) e.putLong(K_EXIT_AT, 0)
        // The lock is back on at the server: the re-lock is answered.
        if (kioskEnabled == true && p.getLong(K_RELOCK_AT, 0) > 0) e.putLong(K_RELOCK_AT, 0).putString(K_RELOCK_PKG, "")
        e.apply()
        // Someone locked it again from the dashboard after the exit was answered: staff mode ends.
        if (kioskEnabled == true && isOn(ctx) && pendingExitAt(ctx) == 0L) {
            p.edit().putString(K_PKG, "").apply()
            onChange?.invoke()
        }
    }

    /**
     * The app(s) the device should be locked to right now, or null for unlocked: the config's
     * lock, except while staff mode or a re-lock is still waiting for the server's answer.
     */
    fun lockedTo(ctx: Context, cfg: AgentConfig): List<String>? {
        if (lockOff(ctx)) return null
        val relock = prefs(ctx).getString(K_RELOCK_PKG, "")!!
        // Our own package stands for the browser lock (it has no app of its own to name).
        if (pendingRelockAt(ctx) > 0 && relock.isNotEmpty()) return if (relock == ctx.packageName) emptyList() else listOf(relock)
        if (isOn(ctx)) return null
        if (!cfg.kioskEnabled) return null
        val browser = cfg.kioskMode == "browser"
        if (browser) return if (cfg.kioskUrl.isNotBlank()) emptyList() else null
        val apps = (listOf(cfg.kioskPackage) + cfg.kioskExtraPackages()).filter { it.isNotBlank() }.distinct()
        return apps.ifEmpty { null }
    }

    fun isLockedOut(ctx: Context): Boolean = System.currentTimeMillis() < prefs(ctx).getLong(K_LOCKOUT, 0)
    fun lockoutRemainingMs(ctx: Context): Long = (prefs(ctx).getLong(K_LOCKOUT, 0) - System.currentTimeMillis()).coerceAtLeast(0)

    /** Checks a PIN; five wrong ones in a row wait a minute. */
    fun verify(ctx: Context, code: String): Boolean {
        if (isLockedOut(ctx)) return false
        val p = prefs(ctx)
        if (code == pin(ctx)) {
            p.edit().putInt(K_FAILS, 0).apply()
            return true
        }
        val fails = p.getInt(K_FAILS, 0) + 1
        if (fails >= MAX_FAILS) {
            p.edit().putInt(K_FAILS, 0).putLong(K_LOCKOUT, System.currentTimeMillis() + LOCKOUT_MS).apply()
        } else {
            p.edit().putInt(K_FAILS, fails).apply()
        }
        return false
    }

    /** The right PIN: staff mode on, remembering the app it was locked to. */
    fun enter(ctx: Context, hostPackage: String) {
        prefs(ctx).edit()
            .putString(K_PKG, hostPackage.ifEmpty { ctx.packageName })
            .putLong(K_EXIT_AT, System.currentTimeMillis() / 1000)
            .putLong(K_RELOCK_AT, 0).putString(K_RELOCK_PKG, "")
            .commit()
        onChange?.invoke()
    }

    /** Back to guest mode: lock the app staff mode left, and say so until the server agrees. */
    fun backToGuest(ctx: Context): String {
        val pkg = staffPackage(ctx)
        if (lockOff(ctx)) {
            // Nothing to go back to: just leave staff mode.
            prefs(ctx).edit().putString(K_PKG, "").putLong(K_EXIT_AT, 0).commit()
            onChange?.invoke()
            return ""
        }
        prefs(ctx).edit()
            .putString(K_PKG, "")
            .putLong(K_RELOCK_AT, System.currentTimeMillis() / 1000)
            .putString(K_RELOCK_PKG, pkg)
            .commit()
        onChange?.invoke()
        return pkg
    }
}
