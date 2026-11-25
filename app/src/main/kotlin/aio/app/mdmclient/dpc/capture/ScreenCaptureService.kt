package aio.app.mdmclient.dpc.capture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.ByteArrayOutputStream
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import aio.app.mdmclient.dpc.AgentApp
import aio.app.mdmclient.dpc.AgentBus
import aio.app.mdmclient.dpc.Notices
import aio.app.mdmclient.dpc.R
import okio.ByteString.Companion.toByteString

/**
 * Foreground (mediaProjection) service that mirrors the screen into an H.264 encoder and streams
 * the encoded NAL units as WebSocket binary frames via [AgentBus.acker] — the same wire shape the
 * dashboard already consumes from the system-app client.
 *
 * Unlike the system app, capture requires a one-time MediaProjection consent obtained by
 * [ScreenCaptureConsentActivity]; this service is started with the resulting token.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var configBytes: ByteArray? = null // H.264 SPS/PPS, prepended to each key frame
    private var imageReader: ImageReader? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var lastFrameMs = 0L
    private lateinit var callbackThread: HandlerThread
    // The live session, kept so a dead encoder can be swapped without a new projection.
    private var encoderName: String? = null
    private var vdW = 0
    private var vdH = 0
    private var sessionDpi = 0
    private var sessionFps = 15
    private var sessionBitrate = 2_000_000
    private var sessionQuality = 70
    // The video format of this session: H.264, or VP8 for browsers that have no H.264
    // decoder (Zen, Edge on Linux; prototype 0.17.1, 25 Nov 2026).
    private var sessionMime = MediaFormat.MIMETYPE_VIDEO_AVC
    // Encode stats, logged every 5 s: frames out, encode delay (input to output), bytes.
    private var statFrames = 0
    private var statBytes = 0L
    private var statDelaySumUs = 0L
    private var statDelayMaxUs = 0L
    private var statStartMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtraCompat(EXTRA_DATA, Intent::class.java)
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "missing projection token")
            stopSelf()
            return START_NOT_STICKY
        }
        val fps = intent.getIntExtra(EXTRA_FPS, 15).coerceIn(1, 30)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 4_000_000).coerceIn(250_000, 20_000_000)
        val scale = intent.getFloatExtra(EXTRA_SCALE, 0.75f).coerceIn(0.1f, 1.0f)
        val codecName = intent.getStringExtra(EXTRA_CODEC) ?: "h264"
        val quality = intent.getIntExtra(EXTRA_QUALITY, 70).coerceIn(10, 100)
        val stillCmd = intent.getStringExtra(EXTRA_STILL_CMD)

        try {
            if (!stillCmd.isNullOrBlank()) {
                captureStill(resultCode, data, scale, stillCmd)
                return START_NOT_STICKY
            }
            startCapture(resultCode, data, fps, bitrate, scale, codecName, quality)
        } catch (e: Exception) {
            Log.e(TAG, "capture start failed: ${e.message}", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent, fps: Int, bitrate: Int, scale: Float, codecName: String, quality: Int) {
        // One session at a time. The browser reconnects (codec fallback, a dropped socket) while
        // the old session is still up; left running, its projection's onStop later tore down
        // the new one through the shared fields — "Invalid media projection", a frozen frame,
        // then a native crash (22 Oct, HK1 box).
        stopCapture()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("no projection")
        projection = proj

        callbackThread = HandlerThread("cap-cb").apply { start() }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Only this session's own projection ending stops capture.
                if (projection === proj) { stopCapture(); stopSelf() }
            }
        }, Handler(callbackThread.looper))

        val metrics = displayMetrics()
        val dpi = metrics.densityDpi
        var w = (metrics.widthPixels * scale).toInt().roundDownEven()
        var h = (metrics.heightPixels * scale).toInt().roundDownEven()
        if (w < 2) w = 2; if (h < 2) h = 2

        running = true
        sessionDpi = dpi; sessionFps = fps; sessionBitrate = bitrate; sessionQuality = quality
        sessionMime = if (codecName == "vp8") MediaFormat.MIMETYPE_VIDEO_VP8 else MediaFormat.MIMETYPE_VIDEO_AVC
        if (codecName == "jpeg") {
            startJpeg(proj, w, h, dpi, fps, quality)
            Log.i(TAG, "capture started ${w}x$h @${fps}fps codec=jpeg")
            return
        }
        try {
            startH264(proj, w, h, dpi, fps, bitrate)
        } catch (e: Exception) {
            // The operator would otherwise sit on "Waking the device screen" for good: the
            // session is up but no frame ever comes. Stills are slower but always work, and
            // the dashboard tells the two apart per frame.
            Log.w(TAG, "$sessionMime unavailable (${e.message}); streaming JPEG stills instead")
            releaseEncoder()
            val (jw, jh) = capLongSide(w, h, JPEG_FALLBACK_MAX)
            startJpeg(proj, jw, jh, dpi, fps, quality)
            Log.i(TAG, "capture started ${jw}x$jh @${fps}fps codec=jpeg (fallback)")
        }
    }

    private fun releaseEncoder() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    /**
     * One frame, as a base64 PNG in the command ack — what the dashboard's screenshot
     * view expects (`data:image/png;base64,...`). Streaming is a different shape:
     * a still is a command with a result, so it never starts the encoder and tears the
     * projection down as soon as it has its picture.
     */
    private fun captureStill(resultCode: Int, data: Intent, scale: Float, cmdId: String) {
        val ack = AgentBus.acker
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        if (proj == null) {
            ack?.ackCommand(cmdId, "failed", output = "no projection token")
            stopSelf()
            return
        }
        stopCapture()
        projection = proj
        callbackThread = HandlerThread("cap-cb").apply { start() }
        val handler = Handler(callbackThread.looper)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (projection === proj) { stopCapture(); stopSelf() } }
        }, handler)

        val metrics = displayMetrics()
        var w = (metrics.widthPixels * scale).toInt().roundDownEven()
        var h = (metrics.heightPixels * scale).toInt().roundDownEven()
        if (w < 2) w = 2; if (h < 2) h = 2

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (done.get()) return@setOnImageAvailableListener
                val png = imageToPng(img, w, h)
                done.set(true)
                if (png != null) {
                    ack?.ackCommand(cmdId, "completed", output = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP))
                } else {
                    ack?.ackCommand(cmdId, "failed", output = "could not encode the frame")
                }
            } finally {
                img.close()
                if (done.get()) { stopCapture(); stopSelf() }
            }
        }, handler)

        running = true
        virtualDisplay = proj.createVirtualDisplay(
            "aio-still", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )
        // A display that never produces a frame would otherwise leave the command hanging.
        handler.postDelayed({
            if (done.compareAndSet(false, true)) {
                ack?.ackCommand(cmdId, "failed", output = "no frame within 10s")
                stopCapture(); stopSelf()
            }
        }, 10_000)
    }

    /** RGBA_8888 [Image] -> PNG bytes, undoing the row padding the reader may add. */
    private fun imageToPng(img: Image, w: Int, h: Int): ByteArray? = try {
        val plane = img.planes[0]
        val bmpW = plane.rowStride / plane.pixelStride
        var bmp = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        if (bmpW != w) { val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h); bmp.recycle(); bmp = cropped }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        Log.w(TAG, "still encode failed: ${e.message}")
        null
    }

    /** Hardware H.264 (best on real devices) — VirtualDisplay -> MediaCodec input Surface. */
    private fun startH264(proj: MediaProjection, reqW: Int, reqH: Int, dpi: Int, fps: Int, bitrate: Int) {
        // Each encoder that takes the size, hardware first, until one configures: the RK3568's
        // OMX.rk.video_encoder.avc lists 528x960 as supported and then refuses it
        // (0x80001001, 25 Nov 2026, Sunmi D2s and rk3568_r), which sent every session there
        // to JPEG stills although Codec2's software encoder works.
        var started: Triple<MediaCodec, Surface, Pair<Int, Int>>? = null
        var lastErr: Exception? = null
        for ((name, cw, ch) in encoderCandidates(sessionMime, reqW, reqH)) {
            Log.i(TAG, "$sessionMime encoder $name at ${cw}x$ch (asked ${reqW}x$reqH)")
            try {
                val (e, surf) = configureEncoder(name, cw, ch, fps, bitrate)
                started = Triple(e, surf, cw to ch)
                break
            } catch (e: Exception) {
                Log.w(TAG, "$name would not start (${e.message}); trying the next encoder")
                runCatching { codec?.release() }
                codec = null
                lastErr = e
            }
        }
        val (enc, inputSurface, size) = started
            ?: throw (lastErr ?: IllegalStateException("no $sessionMime encoder takes ${reqW}x$reqH or smaller"))
        val (w, h) = size
        vdW = w; vdH = h
        virtualDisplay = proj.createVirtualDisplay(
            "mdm-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null,
        )
        drainThread = Thread { drainLoop(enc) }.also { it.start() }
    }

    /** A started encoder and its input surface. Sets [codec] first so a failure releases it. */
    private fun configureEncoder(name: String, w: Int, h: Int, fps: Int, bitrate: Int): Pair<MediaCodec, Surface> {
        val format = MediaFormat.createVideoFormat(sessionMime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // The virtual display hands over every screen refresh (40-50 a second on a busy
            // KDS); without a cap a software encoder falls behind and the picture lags by a
            // second or more. KEY_FRAME_RATE is only a hint.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            }
            if (sessionMime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
        val enc = MediaCodec.createByCodecName(name)
        codec = enc
        encoderName = name
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = enc.createInputSurface()
        enc.start()
        return enc to inputSurface
    }

    /**
     * The encoder died mid-stream (22 Oct, HK1 box: c2.rk.avc.encoder "died" 1.4 s in, every
     * session). Skip it on this build from now on and carry on with the next one on the
     * same VirtualDisplay — a second createVirtualDisplay on one projection is refused from
     * Android 14 — or with stills when no other encoder takes the size.
     */
    private fun swapEncoder(enc: MediaCodec, dead: String, why: String?) {
        if (!running || codec !== enc) return   // a newer session, or already stopped
        val vd = virtualDisplay ?: return
        rememberBad(dead)
        Log.w(TAG, "H.264 encoder $dead died mid-stream ($why); switching")
        runCatching { codec?.release() }
        codec = null
        configBytes = null
        try {
            val (name, w, h) = pickEncoder(sessionMime, vdW, vdH)
            val (enc, surface) = configureEncoder(name, w, h, sessionFps, sessionBitrate)
            if (w != vdW || h != vdH) { vd.resize(w, h, sessionDpi); vdW = w; vdH = h }
            vd.surface = surface
            drainThread = Thread { drainLoop(enc) }.also { it.start() }
            Log.i(TAG, "H.264 resumed on $name at ${w}x$h")
        } catch (e: Exception) {
            Log.w(TAG, "no other H.264 encoder (${e.message}); streaming JPEG stills instead")
            runCatching { codec?.release() }
            codec = null
            val (jw, jh) = capLongSide(vdW, vdH, JPEG_FALLBACK_MAX)
            val reader = jpegReader(jw, jh, sessionFps, sessionQuality)
            vd.resize(jw, jh, sessionDpi); vdW = jw; vdH = jh
            vd.surface = reader.surface
        }
    }

    /** JPEG stills via ImageReader — robust fallback that works where H.264 encode/decode
     *  doesn't (e.g. software-GPU emulators). Throttled to the requested fps. */
    private fun startJpeg(proj: MediaProjection, w: Int, h: Int, dpi: Int, fps: Int, quality: Int) {
        val reader = jpegReader(w, h, fps, quality)
        vdW = w; vdH = h
        virtualDisplay = proj.createVirtualDisplay(
            "mdm-capture-jpeg", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, Handler(callbackThread.looper),
        )
    }

    /** An ImageReader that sends each frame as a JPEG, throttled to [fps]. */
    private fun jpegReader(w: Int, h: Int, fps: Int, quality: Int): ImageReader {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        val minIntervalMs = (1000L / fps).coerceAtLeast(33L)
        val handler = Handler(callbackThread.looper)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!running) return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastFrameMs < minIntervalMs) return@setOnImageAvailableListener
                lastFrameMs = now
                imageToJpeg(img, w, h, quality)?.let { AgentBus.acker?.sendBinary(it.toByteString()) }
            } finally {
                img.close()
            }
        }, handler)
        return reader
    }

    private fun imageToJpeg(img: Image, w: Int, h: Int, quality: Int): ByteArray? = try {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * w
        val bmpW = w + rowPadding / pixelStride
        var bmp = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        if (bmpW != w) { val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h); bmp.recycle(); bmp = cropped }
        val out = ByteArrayOutputStream(64 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        Log.w(TAG, "jpeg encode failed: ${e.message}"); null
    }

    private fun drainLoop(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                enc.dequeueOutputBuffer(info, 100_000)
            } catch (e: IllegalStateException) {
                val dead = encoderName
                val looper = if (this::callbackThread.isInitialized) callbackThread.looper else null
                if (running && enc === codec && dead != null && looper != null) {
                    Handler(looper).post { swapEncoder(enc, dead, e.message) }
                }
                break
            }
            if (index >= 0) {
                val buf = enc.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    // Wire format the dashboard expects: [1-byte type][payload].
                    //   type 1 = key frame — payload = SPS/PPS + IDR (so the decoder can start)
                    //   type 0 = delta
                    // Codec-config buffers (SPS/PPS) are cached, not sent on their own.
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        configBytes = bytes
                    } else {
                        noteEncoded(info.presentationTimeUs, bytes.size)
                        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val cfg = if (isKey) (configBytes ?: ByteArray(0)) else ByteArray(0)
                        val frame = ByteArray(1 + cfg.size + bytes.size)
                        frame[0] = if (isKey) 1 else 0
                        System.arraycopy(cfg, 0, frame, 1, cfg.size)
                        System.arraycopy(bytes, 0, frame, 1 + cfg.size, bytes.size)
                        AgentBus.acker?.sendBinary(frame.toByteString())
                    }
                }
                enc.releaseOutputBuffer(index, false)
            }
        }
    }

    /**
     * Encode stats for the VP8 trial: frames per second out of the encoder, how long a frame
     * took from the screen to the encoder's output (the frame's own timestamp, which the
     * virtual display sets from the monotonic clock), and the bytes. Logged every 5 s; read
     * back with the device's log capture.
     */
    private fun noteEncoded(ptsUs: Long, size: Int) {
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (statStartMs == 0L) statStartMs = nowMs
        val delayUs = System.nanoTime() / 1000 - ptsUs
        if (delayUs in 0..5_000_000) {
            statDelaySumUs += delayUs
            if (delayUs > statDelayMaxUs) statDelayMaxUs = delayUs
        }
        statFrames++; statBytes += size
        val span = nowMs - statStartMs
        if (span >= 5000) {
            val fps = statFrames * 1000.0 / span
            val avgMs = if (statFrames > 0) statDelaySumUs / 1000.0 / statFrames else 0.0
            Log.i(TAG, String.format(java.util.Locale.US,
                "encode stats %s %s %dx%d: %.1f fps, delay avg %.1f ms max %.1f ms, %.0f kB/s",
                sessionMime, encoderName, vdW, vdH, fps, avgMs, statDelayMaxUs / 1000.0,
                statBytes / 1024.0 * 1000 / span))
            statFrames = 0; statBytes = 0; statDelaySumUs = 0; statDelayMaxUs = 0; statStartMs = nowMs
        }
    }

    private fun stopCapture() {
        val proj = projection
        projection = null           // before stop(): its onStop sees it is no longer current
        running = false
        runCatching { drainThread?.join(500) }
        drainThread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { proj?.stop() }
        if (this::callbackThread.isInitialized) runCatching { callbackThread.quitSafely() }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            m.widthPixels = b.width()
            m.heightPixels = b.height()
            m.densityDpi = resources.configuration.densityDpi
        } else {
            wm.defaultDisplay.getRealMetrics(m)
        }
        return m
    }

    private fun goForeground() {
        val notif = Notices.base(this, AgentApp.CHANNEL_STATUS, "Managed device")
            .setContentTitle("Screen shared with AIO")
            .setContentText("Someone at AIO is viewing this screen")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun Int.roundDownEven(): Int = this and 1.inv()

    private fun rememberBad(name: String) {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putStringSet(KEY_BAD, badEncoders(this) + name).apply()
    }

    /**
     * The H.264 encoder to use and the size it can take. `createEncoderByType` hands back
     * whichever encoder ranks first, and on Android 11 that is Codec2's software one, capped
     * at 1280x720: a 4K panel at the default half scale (1920x1080) was refused outright
     * (22 Oct, Mega ZC-3568K, "configure failed 0x80001001") although the board's own
     * OMX.rk encoder takes 1920x1088. Hardware first; each gets the asked size, or the
     * largest smaller one it accepts, aspect kept.
     */
    private fun pickEncoder(mime: String, w: Int, h: Int): Triple<String, Int, Int> =
        encoderCandidates(mime, w, h).firstOrNull()
            ?: throw IllegalStateException("no $mime encoder takes ${w}x$h or smaller")

    private fun encoderCandidates(mime: String, w: Int, h: Int): List<Triple<String, Int, Int>> {
        val avc = mime
        val out = mutableListOf<Triple<String, Int, Int>>()
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(avc, true) } }
            .filter { it.name !in badEncoders(this) }
            .sortedBy { if (isSoftware(it)) 1 else 0 }
        for (info in encoders) {
            val vc = runCatching { info.getCapabilitiesForType(avc).videoCapabilities }.getOrNull() ?: continue
            val size = fitSize(vc, w, h) ?: continue
            out += Triple(info.name, size.first, size.second)
        }
        return out
    }

    private fun isSoftware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isSoftwareOnly
        else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")

    /** The asked size if supported, else the largest smaller one (16-aligned, aspect kept). */
    private fun fitSize(vc: MediaCodecInfo.VideoCapabilities, w: Int, h: Int): Pair<Int, Int>? {
        if (vc.isSizeSupported(w, h)) return w to h
        var f = minOf(1.0, vc.supportedWidths.upper.toDouble() / w, vc.supportedHeights.upper.toDouble() / h)
        repeat(24) {
            val fw = ((w * f).toInt() / 16) * 16
            val fh = ((h * f).toInt() / 16) * 16
            if (fw >= 16 && fh >= 16 && vc.isSizeSupported(fw, fh)) return fw to fh
            f *= 0.9
        }
        return null
    }

    private fun capLongSide(w: Int, h: Int, max: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= max) return w to h
        val f = max.toDouble() / long
        return (w * f).toInt().roundDownEven() to (h * f).toInt().roundDownEven()
    }

    companion object {
        private const val TAG = "ScreenCapture"
        private const val NOTIF_ID = 1002
        const val ACTION_START = "aio.app.mdmclient.dpc.CAPTURE_START"
        const val ACTION_STOP = "aio.app.mdmclient.dpc.CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_CODEC = "codec"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_SCALE = "scale"
        private const val JPEG_FALLBACK_MAX = 1280   // long side of the stills when H.264 fails
        private const val PREFS = "capture"
        // Encoders that died mid-stream on this build. Kept on disk so the next session — even
        // after the agent restarts — goes straight to one that works; a new build (an OTA may
        // fix the encoder) starts the list over.
        private val KEY_BAD = "bad_avc_encoders@" + Build.FINGERPRINT

        private fun badEncoders(ctx: Context): Set<String> =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_BAD, emptySet()) ?: emptySet()
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        /** Command id for a one-shot still: capture one frame, ack it, stop. */
        const val EXTRA_STILL_CMD = "still_cmd"

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}

private fun <T> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(name, clazz)
    else @Suppress("DEPRECATION") getParcelableExtra(name) as? T
