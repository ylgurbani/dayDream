package com.yattubhaa.app.session

import com.yattubhaa.app.net.Crypto
import com.yattubhaa.app.net.Protocol
import com.yattubhaa.app.net.RelayClient
import com.yattubhaa.app.net.Role
import com.yattubhaa.app.net.SecureChannel
import com.yattubhaa.app.pairing.PairingStore

/**
 * Wires one relay connection to one secure channel and turns their callbacks into the few
 * events a session cares about. The two roles subclass this and only decide what to show.
 */
abstract class BaseSession(
    private val role: Role,
    protected val pairing: PairingStore.Record,
    protected val code: String,
) {
    private lateinit var relay: RelayClient
    private val channel: SecureChannel = SecureChannel(
        role = role,
        pairingSecret = pairing.secretBytes,
        code = code,
        sendRaw = { relay.send(it) },
        listener = object : SecureChannel.Listener {
            override fun onEstablished() = this@BaseSession.onSecured()
            override fun onBadHello() = this@BaseSession.onBadCode()
            override fun onFailed(reason: String) = end("The connection was not safe ($reason).")
            override fun onData(plain: ByteArray) {
                when (val m = Protocol.parse(plain)) {
                    null -> Unit // malformed: ignore rather than trust it
                    Protocol.Message.Stop -> end("The other phone ended the session.", notifyPeer = false)
                    else -> onMessage(m)
                }
            }
        },
    )

    @Volatile var ended = false
        private set

    protected abstract fun onPeer(present: Boolean)
    protected abstract fun onSecured()
    protected abstract fun onBadCode()
    protected abstract fun onMessage(message: Protocol.Message)
    protected abstract fun onEnded(reason: String)

    fun start() {
        relay = RelayClient(
            relayUrl = pairing.relay,
            roomId = Crypto.roomId(pairing.secretBytes),
            role = role,
            listener = object : RelayClient.Listener {
                override fun onPeerPresence(present: Boolean) {
                    if (ended) return
                    if (present) channel.onPeerPresent() else channel.onPeerGone()
                    this@BaseSession.onPeer(present)
                }

                override fun onBytes(bytes: ByteArray) {
                    if (!ended) channel.onMessage(bytes)
                }

                override fun onClosed(reason: String) = end("Lost the connection ($reason).", notifyPeer = false)
            },
        )
        relay.connect()
    }

    protected fun send(message: ByteArray): Boolean = channel.sendData(message)

    protected fun backlogBytes(): Long = relay.backlogBytes()

    fun end(reason: String, notifyPeer: Boolean = true) {
        if (ended) return
        ended = true
        if (notifyPeer) channel.sendData(Protocol.stop())
        onEnded(reason)
        relay.close()
    }
}
