package aio.app.mdmclient.dpc.net

import android.os.SystemClock
import android.util.Log
import aio.app.mdmclient.dpc.AgentConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket to /api/v1/ws?serial=... with the shared X-API-Key header. OkHttp handles RFC-6455
 * framing, masking, and ping keepalive, so this is far smaller than the AOSP client's hand-rolled
 * MdmWebSocketClient. Reconnect/backoff is driven by [MdmService] via the [Listener] callbacks.
 */
class WsClient(
    private val config: AgentConfig,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onMessage(msg: JSONObject)
        /** [code] is the server's close code, 0 when the socket failed without one. */
        fun onClosed(reason: String, code: Int)
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived
        .build()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile var isOpen: Boolean = false
        private set

    // When the current dial started (0 once it opened or reported). OkHttp's connectTimeout
    // covers the TCP connect only; with readTimeout 0 a TLS handshake or upgrade reply that
    // loses the network waits forever and reports neither onOpen nor onFailure, so nothing
    // ever reconnects (ADRB0AASI00006 and 16 lab T7s, Wi-Fi blip 30 Oct 2026, 03:10 UTC).
    @Volatile private var dialStartedAt = 0L

    /** A dial has been going far longer than any dial takes: the service cancels and redials. */
    fun isStuckDialing(): Boolean {
        val t = dialStartedAt
        return t != 0L && !isOpen && SystemClock.elapsedRealtime() - t > DIAL_STUCK_MS
    }

    /** Abandon whatever this socket is doing (a hung dial included) without a close handshake. */
    fun cancel() {
        isOpen = false
        dialStartedAt = 0L
        webSocket?.cancel()
        webSocket = null
    }

    fun connect(serial: String) {
        val req = Request.Builder()
            .url(config.wsUrl(serial))
            .header("X-API-Key", config.apiKey)
            .build()
        dialStartedAt = SystemClock.elapsedRealtime()
        webSocket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                dialStartedAt = 0L
                isOpen = true
                Log.i(TAG, "WS open")
                listener.onOpen()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    listener.onMessage(JSONObject(text))
                } catch (e: Exception) {
                    Log.w(TAG, "Bad WS message: ${e.message}")
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                // A peer that closes without a status code surfaces here as 1005, which is
                // reserved and throws if handed back to close() — OkHttp then reports a
                // failure, and the service reconnects a second later, forever. Echo a
                // normal close instead.
                ws.close(if (code == 1005 || code == 1006) 1000 else code, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                dialStartedAt = 0L
                isOpen = false
                listener.onClosed("closed $code $reason", code)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                dialStartedAt = 0L
                isOpen = false
                Log.w(TAG, "WS failure: ${t.message}")
                listener.onClosed("failure ${t.message}", 0)
            }
        })
    }

    fun send(obj: JSONObject): Boolean = webSocket?.send(obj.toString()) ?: false

    fun sendBinary(bytes: ByteString): Boolean = webSocket?.send(bytes) ?: false

    fun close() {
        isOpen = false
        webSocket?.close(1000, "client closing")
        webSocket = null
    }

    companion object {
        private const val TAG = "WsClient"
        private const val DIAL_STUCK_MS = 60_000L
    }
}
