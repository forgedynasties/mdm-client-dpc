package aio.app.mdmclient.dpc.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.Notices
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Silent APK install / uninstall via [PackageInstaller]. As Device Owner these proceed without any
 * user prompt (no INSTALL_PACKAGES / REQUEST_INSTALL_PACKAGES needed). Result is delivered to a
 * short-lived runtime receiver; the calling (IO) thread blocks on a latch until it arrives.
 */
class ApkInstaller(private val ctx: Context, config: AgentConfig) {

    data class Result(val success: Boolean, val message: String, val pkg: String? = null)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Download [apkUrl] and install it silently. [onProgress] gets 0..100 download percent.
     * Shows its own progress notice (N2); on success Android's "Updated by your admin" says
     * the rest, so ours goes, and on failure ours says so.
     */
    fun install(apkUrl: String, onProgress: (Int) -> Unit): Result {
        val file = File(ctx.cacheDir, "install_${System.nanoTime()}.apk")
        val notifId = 2000 + Math.floorMod(apkUrl.hashCode(), 100000)
        val note = Notices.InstallNote()
        Notices.installProgress(ctx, notifId, note, true, "Starting…", 0)
        try {
            var shown = -1
            downloadTo(apkUrl, file) { pct ->
                onProgress(pct)
                if (pct != shown && pct < 100) {
                    shown = pct
                    Notices.installProgress(ctx, notifId, note, true, "$pct %", pct)
                }
            }
            Notices.describe(ctx, file, note)
            Notices.installProgress(ctx, notifId, note, false, null, -1)
            val result = commitInstall(file)
            if (result.success) Notices.cancel(ctx, notifId) else Notices.installFailed(ctx, notifId, note)
            return result
        } catch (e: Exception) {
            Notices.installFailed(ctx, notifId, note)
            return Result(false, "install error: ${e.message}")
        } finally {
            file.delete()
        }
    }

    internal fun downloadTo(url: String, dest: File, onProgress: (Int) -> Unit) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("download HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var written = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } >= 0) {
                        output.write(buf, 0, read)
                        written += read
                        if (total > 0) {
                            val pct = (written * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
        }
    }

    internal fun commitInstall(apk: File): Result {
        // A split APK bundle (.xapk and the like, 0.15.0) goes in as all its parts at once.
        val parts = try {
            ApkBundle.parts(apk)
        } catch (e: IllegalArgumentException) {
            return Result(false, e.message ?: "not an installable bundle")
        }
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { session ->
            if (parts == null) {
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            } else {
                java.util.zip.ZipFile(apk).use { zip ->
                    for (name in parts) {
                        val e = zip.getEntry(name)
                        session.openWrite(name, 0, e.size).use { out ->
                            zip.getInputStream(e).use { it.copyTo(out) }
                            session.fsync(out)
                        }
                    }
                }
            }
            val result = awaitSessionResult(sessionId) { intentSender ->
                session.commit(intentSender)
            }
            return result
        }
    }

    fun uninstall(pkg: String): Result {
        val pi = ctx.packageManager.packageInstaller
        return try {
            // Reuse a session id namespace that won't collide with installs.
            awaitSessionResult(pkg.hashCode() and 0x7fffffff) { intentSender ->
                pi.uninstall(pkg, intentSender)
            }.let { if (it.success) it.copy(pkg = pkg) else it }
        } catch (e: Exception) {
            Result(false, "uninstall error: ${e.message}", pkg)
        }
    }

    /** Registers a one-shot receiver, hands its IntentSender to [commit], and waits for the result. */
    private fun awaitSessionResult(requestId: Int, commit: (android.content.IntentSender) -> Unit): Result {
        val action = "$ACTION_INSTALL_RESULT.$requestId"
        val latch = CountDownLatch(1)
        var result = Result(false, "timeout")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                result = when (status) {
                    PackageInstaller.STATUS_SUCCESS -> Result(true, "ok", pkg)
                    PackageInstaller.STATUS_PENDING_USER_ACTION ->
                        Result(false, "unexpected user-action prompt (not Device Owner?)")
                    else -> Result(false, "status=$status $msg")
                }
                latch.countDown()
            }
        }

        val filter = IntentFilter(action)
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val intent = Intent(action).setPackage(ctx.packageName)
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0)
            val pending = android.app.PendingIntent.getBroadcast(ctx, requestId, intent, flags)
            commit(pending.intentSender)
            latch.await(3, TimeUnit.MINUTES)
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
        }
        return result
    }

    companion object {
        private const val ACTION_INSTALL_RESULT = "aio.app.mdmclient.dpc.INSTALL_RESULT"
    }
}
