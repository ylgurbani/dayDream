package com.yattubhaa.app.session

import com.yattubhaa.app.net.Crypto
import com.yattubhaa.app.net.Protocol
import com.yattubhaa.app.net.RelayClient
import com.yattubhaa.app.net.Role
import com.yattubhaa.app.net.SecureChannel
import com.yattubhaa.app.pairing.PairingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Wires one relay connection to one secure channel and turns their callbacks into the few
 * events a session cares about. The two roles subclass this and only decide what to show.
 *
 * Two kinds of blip are absorbed here without the person needing to notice or do anything:
 *  - **This phone's own connection drops** (switching wifi, a brief DNS hiccup): retried a few
 *    times with a growing delay before the session is actually given up on.
 *  - **The other phone briefly is not in the room**, after both of them just recovered from the
 *    same kind of blip: since each side reconnects independently, one of them typically rejoins
 *    a moment before the other. Without a grace period, the first one back would immediately
 *    treat the other as "gone" and end a session that was actually about to recover on its own —
 *    this was a real bug, found by killing and restarting the relay mid-session.
 */
abstract class BaseSession(
    private val role: Role,
    protected val pairing: PairingStore.Record,
    protected val code: String,
) {
    private lateinit var relay: RelayClient
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var retryCount = 0
    private var everSecured = false
    private var peerGoneJob: Job? = null
    private val channel: SecureChannel = SecureChannel(
        role = role,
        pairingSecret = pairing.secretBytes,
        code = code,
        sendRaw = { relay.send(it) },
        listener = object : SecureChannel.Listener {
            override fun onEstablished() {
                everSecured = true
                this@BaseSession.onSecured()
            }
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
        retryCount = 0
        connect()
    }

    private fun connect() {
        relay = RelayClient(
            relayUrl = pairing.relay,
            roomId = Crypto.roomId(pairing.secretBytes),
            role = role,
            listener = object : RelayClient.Listener {
                override fun onPeerPresence(present: Boolean) {
                    if (ended) return
                    retryCount = 0 // a connection that actually works resets the retry budget
                    if (present) {
                        peerGoneJob?.cancel(); peerGoneJob = null
                        channel.onPeerPresent()
                        this@BaseSession.onPeer(true)
                    } else {
                        channel.onPeerGone()
                        // The very first time (never yet secured), there is nothing to be
                        // graceful about: say so immediately, same as always. Once a session has
                        // been secured at least once, give the other phone a little time to
                        // reappear — it may just be in the middle of the very same reconnect —
                        // before actually treating it as gone.
                        if (!everSecured) {
                            this@BaseSession.onPeer(false)
                        } else {
                            peerGoneJob?.cancel()
                            peerGoneJob = retryScope.launch {
                                delay(PEER_GONE_GRACE_MS)
                                if (!ended) this@BaseSession.onPeer(false)
                            }
                        }
                    }
                }

                override fun onBytes(bytes: ByteArray) {
                    if (!ended) channel.onMessage(bytes)
                }

                override fun onClosed(reason: String) {
                    if (ended) return
                    relay.close() // release this attempt's connection before making another
                    val delayMs = RETRY_DELAYS_MS.getOrNull(retryCount)
                    if (delayMs == null) {
                        end("Lost the connection ($reason).", notifyPeer = false)
                        return
                    }
                    retryCount++
                    retryScope.launch {
                        delay(delayMs)
                        if (!ended) connect()
                    }
                }
            },
        )
        relay.connect()
    }

    protected fun send(message: ByteArray): Boolean = channel.sendData(message)

    protected fun backlogBytes(): Long = relay.backlogBytes()

    fun end(reason: String, notifyPeer: Boolean = true) {
        if (ended) return
        ended = true
        retryScope.cancel()
        if (notifyPeer) channel.sendData(Protocol.stop())
        onEnded(reason)
        relay.close()
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(1500, 3000, 6000, 6000)
        // Long enough to ride out a real shared blip (one or two retry attempts, 1.5-4.5s
        // typically) without falsely declaring the other phone gone; short enough that an
        // actually-closed app is not reported 15+ seconds late. The relay itself notices a
        // closed connection and tells the other side almost instantly (see server.js's `ws.on
        // ('close', ...)`), so this grace period is the dominant, directly controllable part of
        // that total delay.
        const val PEER_GONE_GRACE_MS = 8_000L
    }
}
