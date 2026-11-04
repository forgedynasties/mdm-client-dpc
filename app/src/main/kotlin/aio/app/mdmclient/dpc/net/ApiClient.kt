package aio.app.mdmclient.dpc.net

import android.net.Uri
import android.util.Log
import aio.app.mdmclient.dpc.AgentConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP device API client (checkin + command ack + logcat), speaking the same contract as
 * the AOSP client's MdmApiService. Auth is the shared `X-API-Key` header.
 *
 * Retries transient failures a few times; a 401 aborts immediately (bad key).
 */
class ApiClient(private val config: AgentConfig) {

    /** Called when the server rejects our key, so the service can enroll again. */
    var onUnauthorized: (() -> Unit)? = null
    /** Any authenticated call that went through: the key is good (resets the 401 count). */
    var onAuthorized: (() -> Unit)? = null

    /** Why the last request failed, for the status screen ("HTTP 502", a network error). */
    @Volatile var lastError: String = ""
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** POST /api/v1/checkin — returns parsed response ({status, config?, send_apps?}) or null. */
    fun checkin(payload: JSONObject): JSONObject? =
        postJson("/api/v1/checkin", payload)

    /**
     * POST /api/v1/enroll — exchange a one-per-profile enrollment token for this device's own
     * API key. Unauthenticated by design (the token IS the credential); no X-API-Key header.
     * Returns the response ({device_key, ...}) or null on failure. A 401 means the token is
     * invalid/revoked — the caller should surface that rather than retry forever.
     * [base] is the server to enroll with: this one, or the one a move_server command names.
     */
    fun enroll(token: String, identity: JSONObject, base: String = config.serverUrl): JSONObject? {
        val payload = JSONObject(identity.toString()).put("token", token)
        try {
            val req = Request.Builder()
                .url(base.trimEnd('/') + "/api/v1/enroll")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    return if (text.isBlank()) JSONObject() else JSONObject(text)
                }
                Log.w(TAG, "enroll failed: HTTP ${resp.code}")
                lastError = if (resp.code == 401) "Enrollment token rejected" else "Enrollment failed: HTTP ${resp.code}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "enroll failed: ${e.message}")
            lastError = "Enrollment failed: ${e.message ?: e.javaClass.simpleName}"
        }
        return null
    }

    /** POST /api/v1/commands/{id}/ack */
    fun ackCommand(commandId: String, body: JSONObject): Boolean =
        postJson("/api/v1/commands/$commandId/ack", body) != null

    /**
     * GET /api/v1/commands/pending?serial=… — this agent asking for its own work.
     *
     * Commands used to arrive only over the WebSocket, so an agent whose socket was gone
     * (or whose server refused the upgrade) could not be told anything, while its
     * check-ins kept arriving and the dashboard showed it alive. Asking is the half the
     * device can always do. Returns the parsed body, or null — a failed poll is a no-op
     * and the next one is a minute away.
     */
    fun fetchPendingCommands(serial: String): JSONObject? {
        val url = config.apiUrl("/api/v1/commands/pending?serial=" + Uri.encode(serial))
        return try {
            val req = Request.Builder().url(url).header("X-API-Key", config.apiKey).get().build()
            http.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        onAuthorized?.invoke()
                        val text = resp.body?.string().orEmpty()
                        if (text.isBlank()) JSONObject() else JSONObject(text)
                    }
                    resp.code == 401 -> {
                        Log.e(TAG, "401 on command poll — device key rejected")
                        lastError = "Device key rejected"
                        onUnauthorized?.invoke()
                        null
                    }
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "command poll failed: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /** POST /api/v1/checkins/backfill: readings kept while offline (OfflineQueue). One try:
     *  the queue keeps them for the next chance. */
    fun backfill(body: JSONObject): JSONObject? =
        postJson("/api/v1/checkins/backfill", body, attempts = 1)

    /** POST /api/v1/app-usage: the per-app summary (AppUsageReport). One try. */
    fun appUsage(body: JSONObject): Boolean =
        postJson("/api/v1/app-usage", body, attempts = 1) != null

    /** POST /api/v1/logcat */
    fun postLogcat(body: JSONObject): Boolean =
        postJson("/api/v1/logcat", body) != null

    private fun postJson(path: String, payload: JSONObject, attempts: Int = 3): JSONObject? {
        val url = config.apiUrl(path)
        var lastErr: String? = null
        repeat(attempts) { i ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("X-API-Key", config.apiKey)
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
                http.newCall(req).execute().use { resp ->
                    when {
                        resp.isSuccessful -> {
                            onAuthorized?.invoke()
                            val text = resp.body?.string().orEmpty()
                            return if (text.isBlank()) JSONObject() else JSONObject(text)
                        }
                        resp.code == 401 -> {
                            Log.e(TAG, "401 unauthorized for $path — device key rejected")
                            lastError = "Device key rejected"
                            onUnauthorized?.invoke()
                            return null
                        }
                        else -> lastErr = "HTTP ${resp.code}"
                    }
                }
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
            // linear-ish backoff with a little growth
            try { Thread.sleep(500L * (i + 1)) } catch (_: InterruptedException) { return null }
        }
        Log.w(TAG, "POST $path failed after $attempts attempts: $lastErr")
        lastError = lastErr.orEmpty()
        return null
    }

    // ---- Save to library (0.11.0): upload an installed APK for a pull_apk command ----

    /** A device-API answer: the parsed body, or why it failed ("HTTP 409: …"). */
    class Answer(val body: JSONObject?, val error: String)

    /** POST a JSON body and keep the server's error message on a refusal. */
    fun postForAnswer(path: String, payload: JSONObject, readSeconds: Long = 30): Answer = try {
        val client = http.newBuilder().readTimeout(readSeconds, TimeUnit.SECONDS).build()
        val req = Request.Builder()
            .url(config.apiUrl(path))
            .header("X-API-Key", config.apiKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { if (text.isBlank()) JSONObject() else JSONObject(text) }.getOrNull()
            if (resp.isSuccessful) Answer(json ?: JSONObject(), "")
            else Answer(null, "HTTP ${resp.code}" + (json?.optString("error")?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
        }
    } catch (e: Exception) {
        Answer(null, e.message ?: e.javaClass.simpleName)
    }

    /**
     * PUT a file to a presigned URL (the library's S3 bucket; no device key goes with it),
     * reporting progress 0-100. Returns "" on success or why it failed.
     */
    fun putFile(url: String, file: java.io.File, contentType: String, onProgress: (Int) -> Unit): String = try {
        val client = http.newBuilder()
            .writeTimeout(10, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
        val total = file.length()
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = contentType.toMediaType()
            override fun contentLength() = total
            override fun writeTo(sink: okio.BufferedSink) {
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        val pct = if (total > 0) (sent * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
        }
        val req = Request.Builder().url(url).put(body).build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) "" else {
                // S3 says why in an XML body: <Code>RequestTimeout</Code> and so on.
                val code = Regex("<Code>([^<]+)</Code>").find(resp.body?.string().orEmpty())?.groupValues?.get(1)
                "upload refused: HTTP ${resp.code}" + (code?.let { " ($it)" } ?: "")
            }
        }
    } catch (e: Exception) {
        "upload failed: " + (e.message ?: e.javaClass.simpleName)
    }

    companion object {
        private const val TAG = "ApiClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
