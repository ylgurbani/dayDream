package com.yattubhaa.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** One WebSocket to one room on the relay. Reports presence of the other phone and raw bytes. */
class RelayClient(
    private val relayUrl: String,
    private val roomId: String,
    private val role: Role,
    private val listener: Listener,
) {
    interface Listener {
        fun onPeerPresence(present: Boolean)
        fun onBytes(bytes: ByteArray)
        fun onClosed(reason: String)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .socketFactory(NoDelaySocketFactory)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closedByUs = false

    fun connect() {
        val request = Request.Builder().url("${relayUrl.trimEnd('/')}/room/$roomId").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val who = if (role == Role.Needy) "needy" else "helper"
                webSocket.send("""{"type":"join","role":"$who"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val present = try {
                    JSONObject(text).takeIf { it.optString("type") == "peer" }?.optBoolean("present")
                } catch (e: Exception) {
                    null
                } ?: return
                listener.onPeerPresence(present)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onBytes(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closedByUs) listener.onClosed(reason.ifBlank { "connection closed" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closedByUs) listener.onClosed(t.message ?: "could not connect")
            }
        })
    }

    fun send(bytes: ByteArray): Boolean = socket?.send(bytes.toByteString()) ?: false

    /** Bytes queued but not yet sent, so callers can skip frames on a slow connection. */
    fun backlogBytes(): Long = socket?.queueSize() ?: 0L

    fun close() {
        closedByUs = true
        socket?.close(1000, "bye")
        client.dispatcher.executorService.shutdown()
    }
}

/**
 * Plain sockets with Nagle's algorithm switched off. Left on (Java's default, and OkHttp does not
 * change it), a small message written while earlier data is still unacknowledged — a tap, a tiny
 * video frame — waits for that acknowledgement before it goes, up to a whole round trip to the
 * relay: latency added on exactly the kind of long-distance link this app is for. TLS sits on top
 * of these sockets, so this applies to wss:// as well.
 */
private object NoDelaySocketFactory : SocketFactory() {
    private val system = getDefault()
    private fun Socket.noDelay() = apply { tcpNoDelay = true }
    override fun createSocket(): Socket = system.createSocket().noDelay()
    override fun createSocket(host: String?, port: Int): Socket = system.createSocket(host, port).noDelay()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        system.createSocket(host, port, localHost, localPort).noDelay()
    override fun createSocket(host: InetAddress?, port: Int): Socket = system.createSocket(host, port).noDelay()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        system.createSocket(address, port, localAddress, localPort).noDelay()
}
