package aio.app.mdmclient.dpc.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import aio.app.mdmclient.dpc.AgentBus
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.DeviceOwner
import aio.app.mdmclient.dpc.device.KioskManager
import aio.app.mdmclient.dpc.kiosk.StaffMode
import aio.app.mdmclient.dpc.kiosk.StaffPinActivity
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Remote input via the accessibility gesture API — the DPC-agent replacement for the system app's
 * INJECT_EVENTS. Enabled once, by the user or — when adb granted WRITE_SECURE_SETTINGS at
 * enrollment — by the agent itself (see Grants.ensureAccessibility). Touch
 * streams are collapsed into taps/swipes (down..up); keys map to global navigation actions
 * (incl. D-pad on Android 13+) and volume.
 */
class MdmAccessibilityService : AccessibilityService(), AgentBus.InputSink {

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentBus.input = this
        StaffMode.onChange = { main.post { refreshStaffUi() } }
        refreshStaffUi()
        Log.i(TAG, "accessibility input connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (AgentBus.input === this) AgentBus.input = null
        StaffMode.onChange = null
        removeCorner(); removePill()
        return super.onUnbind(intent)
    }

    // Window changes are when the lock starts or stops: keep the corner and the pill in step.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) refreshStaffUi()
    }

    // ---- Staff mode (0.10.0): what opens the staff PIN pad on a locked device ----
    // Touchscreens: hold the top-left corner for 5 seconds (an invisible patch over the app, so
    // the app never sees that touch). TV boxes (no touchscreen): the D-pad code. In staff mode a
    // pill offers Back to guest mode (on a TV box, the code again).

    private val main = Handler(Looper.getMainLooper())
    private var corner: View? = null
    private var pill: View? = null
    private val keyLog = ArrayDeque<Pair<Int, Long>>()

    // A TV box uses the D-pad code even when it claims a touchscreen: the HK1 RBOX D8 lists
    // android.hardware.touchscreen, so until 0.12.6 it waited for a corner hold nobody can do
    // and ignored the code. Leanback is Android's own "this is a TV" flag.
    private val touch: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun locked(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun refreshStaffUi() {
        val lost = AgentConfig.get(this).lostOn
        if (touch && locked() && !lost && !StaffMode.isOn(this)) addCorner() else removeCorner()
        if (StaffMode.isOn(this) && !lost) addPill() else removePill()
    }

    private fun overlayParams(w: Int, h: Int, gravity: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val openPad = Runnable { openPinPad() }

    private fun addCorner() {
        if (corner != null) return
        val v = View(this)
        var downAt = 0L
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downAt = SystemClock.elapsedRealtime(); main.postDelayed(openPad, CORNER_HOLD_MS) }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(openPad)
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(openPad)
                    // A short tap was meant for the app (a back or menu button lives up here):
                    // lift the patch, hand the tap on, and put the patch back.
                    if (SystemClock.elapsedRealtime() - downAt < CORNER_HOLD_MS) passTap(e.rawX, e.rawY)
                }
            }
            true
        }
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .addView(v, overlayParams(dp(64), dp(64), Gravity.TOP or Gravity.START, true))
            corner = v
        }.onFailure { Log.w(TAG, "corner overlay: ${it.message}") }
    }

    private fun passTap(x: Float, y: Float) {
        removeCorner()
        val stroke = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 50)
        val done = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { main.postDelayed({ refreshStaffUi() }, 50) }
            override fun onCancelled(g: GestureDescription?) { main.postDelayed({ refreshStaffUi() }, 50) }
        }
        if (!dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), done, main)) refreshStaffUi()
    }

    private fun removeCorner() {
        main.removeCallbacks(openPad)
        corner?.let { runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } }
        corner = null
    }

    private fun openPinPad() {
        if (!locked() || StaffMode.isOn(this) || AgentConfig.get(this).lostOn) return
        Log.i(TAG, "staff: opening the PIN pad")
        runCatching {
            startActivity(Intent(this, StaffPinActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }.onFailure { Log.w(TAG, "PIN pad: ${it.message}") }
    }

    private fun addPill() {
        if (pill != null) return
        val tv = TextView(this).apply {
            text = if (touch) "Staff mode   Back to guest mode  ›" else "Staff mode · enter the code again for guest mode"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply { cornerRadius = dp(99).toFloat(); setColor(Color.argb(240, 28, 24, 40)); setStroke(dp(2), Color.parseColor("#F9674E")) }
            contentDescription = "Staff mode. Back to guest mode"
            if (touch) setOnClickListener { backToGuest() }
        }
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(tv,
                overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL, touch).apply { y = dp(10) })
            pill = tv
        }.onFailure { Log.w(TAG, "staff pill: ${it.message}") }
    }

    private fun removePill() {
        pill?.let { runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } }
        pill = null
    }

    private fun backToGuest() {
        StaffMode.backToGuest(this)
        KioskManager(this, DeviceOwner(this)).apply(AgentConfig.get(this))
        Log.i(TAG, "staff: back to guest mode")
    }

    // TV boxes: the D-pad code, watched without taking the keys from the app.
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (touch || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        staffCodeKey(event.keyCode)
        return false
    }

    /**
     * One key press towards the staff code: from the box's own remote (onKeyEvent) or from the
     * dashboard's on-screen D-pad (handleKey). The dashboard's presses are global actions, which
     * never pass through onKeyEvent, so until 0.12.5 the code could not be entered from there.
     */
    private fun staffCodeKey(keyCode: Int) {
        val now = SystemClock.elapsedRealtime()
        keyLog.addLast(keyCode to now)
        while (keyLog.size > STAFF_CODE.size) keyLog.removeFirst()
        if (keyLog.size == STAFF_CODE.size &&
            keyLog.map { it.first } == STAFF_CODE &&
            now - keyLog.first().second <= STAFF_CODE_WINDOW_MS
        ) {
            keyLog.clear()
            when {
                StaffMode.isOn(this) -> main.post { backToGuest() }
                locked() -> main.post { openPinPad() }
            }
        }
    }
    override fun onInterrupt() { /* no-op */ }

    override fun handle(event: JSONObject) {
        when (event.optString("action")) {
            "touch" -> handleTouch(event)
            // The dashboard sends a down and an up per key press; a global action is one
            // shot, so act on the up only (or on a bare key event with no phase).
            "key" -> if (event.optString("event", "up") == "up") handleKey(event.optInt("keycode", -1))
        }
    }

    private fun handleTouch(e: JSONObject) {
        val m = metrics()
        val x = (e.optDouble("x", 0.0).toFloat()).coerceIn(0f, 1f) * m.widthPixels
        val y = (e.optDouble("y", 0.0).toFloat()).coerceIn(0f, 1f) * m.heightPixels
        when (e.optString("event")) {
            "down" -> {
                downX = x; downY = y; downTime = System.currentTimeMillis()
                endHold(false)
                main.postDelayed(startHold, HOLD_AFTER_MS)
            }
            "move" -> { /* intermediate points ignored; reconstructed as a swipe on up */ }
            "up" -> {
                main.removeCallbacks(startHold)
                // A press held on the remote is held on the device until now (0.12.0).
                if (holdStroke != null) { endHold(true); return }
                val moved = hypot((x - downX).toDouble(), (y - downY).toDouble())
                if (moved > TAP_SLOP) swipe(downX, downY, x, y) else tap(x, y)
            }
        }
    }

    // ---- Holding a press from the remote screen (0.12.0, 27 Oct 2026) ----
    // Before this a press held on the remote arrived as a 60 ms tap, so nothing that needs a
    // long press worked from the dashboard, the staff corner's 5 s hold included. Now, once
    // the remote's button has been down HOLD_AFTER_MS without an up, the device puts its own
    // touch down at that spot and keeps it there with continued strokes (Android 8+) until
    // the remote lets go, then lifts it. A hold the remote never ends lifts after
    // HOLD_MAX_MS. Moving while held is not followed: a drag is still a swipe.

    private var holdStroke: GestureDescription.StrokeDescription? = null
    private var holdSince = 0L
    private var holdRelease = false

    private val startHold = Runnable {
        val first = GestureDescription.StrokeDescription(Path().apply { moveTo(downX, downY) }, 0, HOLD_STEP_MS, true)
        holdStroke = first
        holdSince = SystemClock.elapsedRealtime()
        holdRelease = false
        sendHold(first)
    }

    private fun sendHold(stroke: GestureDescription.StrokeDescription) {
        val cb = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                val cur = holdStroke ?: return
                if (!cur.willContinue()) { holdStroke = null; return }
                val stop = holdRelease || SystemClock.elapsedRealtime() - holdSince > HOLD_MAX_MS
                val next = cur.continueStroke(Path().apply { moveTo(downX, downY) }, 0, if (stop) 50 else HOLD_STEP_MS, !stop)
                holdStroke = next
                sendHold(next)
            }
            override fun onCancelled(g: GestureDescription?) { holdStroke = null }
        }
        if (!dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), cb, main)) holdStroke = null
    }

    /** Lift a held press at the next step (release = the remote let go); without one, nothing. */
    private fun endHold(release: Boolean) {
        if (holdStroke != null) holdRelease = true else if (!release) holdRelease = false
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 1, 60)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val dur = (System.currentTimeMillis() - downTime).coerceIn(50, 1000)
        dispatch(path, 1, dur)
    }

    private fun dispatch(path: Path, start: Long, duration: Long) {
        val stroke = GestureDescription.StrokeDescription(path, start, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun handleKey(keycode: Int) {
        if (!touch) staffCodeKey(keycode)
        // Volume is not a global action; adjust the music stream directly (TV boxes have
        // no hardware volume keys the operator could otherwise reach).
        val dir = when (keycode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> null
        }
        if (dir != null) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            return
        }
        val action = when (keycode) {
            KeyEvent.KEYCODE_BACK -> GLOBAL_ACTION_BACK
            KeyEvent.KEYCODE_HOME -> GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_APP_SWITCH -> GLOBAL_ACTION_RECENTS
            else -> dpadAction(keycode) ?: run { Log.d(TAG, "unmapped keycode $keycode"); return }
        }
        performGlobalAction(action)
    }

    /**
     * D-pad for remote-driven devices (TV boxes / dongles have no touchscreen, so taps do
     * nothing useful there). The dashboard sends arrows as DPAD_* and Enter as ENTER.
     * The DPAD global actions exist from Android 13; older devices get nothing.
     */
    private fun dpadAction(keycode: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return when (keycode) {
            KeyEvent.KEYCODE_DPAD_UP -> GLOBAL_ACTION_DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> GLOBAL_ACTION_DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> GLOBAL_ACTION_DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> GLOBAL_ACTION_DPAD_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> GLOBAL_ACTION_DPAD_CENTER
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun metrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            m.widthPixels = b.width(); m.heightPixels = b.height()
        } else {
            wm.defaultDisplay.getRealMetrics(m)
        }
        return m
    }

    /**
     * A still screenshot as PNG through the accessibility service (0.14.1): no consent prompt,
     * unlike MediaProjection, which Android 14 asks about on every capture ("Start recording or
     * casting with AIO MDM?"). Android 11+; [done] gets the PNG, or null and why.
     */
    fun screenshotPng(done: (ByteArray?, String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { done(null, "needs Android 11"); return }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                Thread {
                    val png = runCatching {
                        shot.hardwareBuffer.use { hb ->
                            val bmp = android.graphics.Bitmap.wrapHardwareBuffer(hb, shot.colorSpace)
                                ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return@use null
                            java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                        }
                    }.getOrNull()
                    done(png, if (png == null) "could not encode the screenshot" else "")
                }.start()
            }
            override fun onFailure(errorCode: Int) { done(null, "accessibility screenshot failed (code $errorCode)") }
        })
    }

    companion object {
        private const val TAG = "MdmA11y"
        private const val TAP_SLOP = 16.0
        private const val HOLD_AFTER_MS = 350L   // a remote press longer than this is a hold
        private const val HOLD_STEP_MS = 200L    // each continued stroke while held
        private const val HOLD_MAX_MS = 30_000L  // a hold the remote never ends lifts by itself
        private const val CORNER_HOLD_MS = 5_000L
        /** Up Up Down Down Left Right Left Right, within 8 seconds. */
        private val STAFF_CODE = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        private const val STAFF_CODE_WINDOW_MS = 8_000L
    }
}
