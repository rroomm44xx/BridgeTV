package dev.bridge

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One port serves the TV web app (HTTP) and the stream/input channel (WebSocket at /ws). */
class BridgeServer(
    private val ctx: Context,
    port: Int,
    private val onInput: (JSONObject) -> Unit,
    private val onClientNeedsKey: () -> Unit,
    private val onClientConnected: () -> Unit,
) : NanoWSD(port) {

    @Volatile var hello: String? = null
    @Volatile var config: ByteArray? = null
    private val clients = CopyOnWriteArrayList<Client>()

    val clientCount get() = clients.size

    override fun serveHttp(session: IHTTPSession): Response {
        var path = session.uri.trimStart('/')
        if (path.isEmpty()) path = "index.html"
        if (path.contains("..")) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "no")
        return try {
            val stream = ctx.assets.open("tv/$path")
            val mime = when (path.substringAfterLast('.', "")) {
                "html" -> "text/html; charset=utf-8"
                "js" -> "application/javascript; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                else -> "application/octet-stream"
            }
            newChunkedResponse(Response.Status.OK, mime, stream).also { it.addHeader("Cache-Control", "no-store") }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Client(handshake)

    /** type: 1 config, 2 key frame, 3 delta frame. */
    fun broadcast(type: Int, ptsMs: Long, data: ByteArray) {
        if (type == 1) config = data
        val msg = ByteArray(5 + data.size)
        msg[0] = type.toByte()
        msg[1] = (ptsMs shr 24).toByte(); msg[2] = (ptsMs shr 16).toByte()
        msg[3] = (ptsMs shr 8).toByte(); msg[4] = ptsMs.toByte()
        System.arraycopy(data, 0, msg, 5, data.size)
        for (c in clients) c.offer(msg, type != 3)
    }

    inner class Client(handshake: IHTTPSession) : WebSocket(handshake) {
        private val sender = Executors.newSingleThreadExecutor()
        private val pending = AtomicInteger(0)
        @Volatile private var waitKey = true

        fun offer(msg: ByteArray, isKeyOrConfig: Boolean) {
            if (waitKey && !isKeyOrConfig) return
            if (pending.get() > 20) { waitKey = true; onClientNeedsKey(); return } // slow TV: drop until next key frame
            if (isKeyOrConfig && msg[0].toInt() == 2) waitKey = false
            pending.incrementAndGet()
            sender.execute {
                try { send(msg) } catch (e: Exception) { try { close(WebSocketFrame.CloseCode.AbnormalClosure, "send failed", false) } catch (_: Exception) {} }
                finally { pending.decrementAndGet() }
            }
        }

        override fun onOpen() {
            clients.add(this)
            try {
                hello?.let { send(it) }
                config?.let { cfg ->
                    val m = ByteArray(5 + cfg.size); m[0] = 1; System.arraycopy(cfg, 0, m, 5, cfg.size); send(m)
                }
            } catch (_: Exception) {}
            onClientConnected()
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            clients.remove(this); sender.shutdown()
        }

        override fun onMessage(message: WebSocketFrame) {
            try {
                val o = JSONObject(message.textPayload)
                if (o.optString("t") == "sync") { waitKey = true; onClientNeedsKey() } else onInput(o)
            } catch (_: Exception) {}
        }

        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: java.io.IOException?) {}
    }
}
