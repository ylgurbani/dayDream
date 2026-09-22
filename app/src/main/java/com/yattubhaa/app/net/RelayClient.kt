package com.yattubhaa.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
