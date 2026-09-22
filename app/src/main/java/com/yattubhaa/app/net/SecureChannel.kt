package com.yattubhaa.app.net

import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class Role(val wire: Byte) {
    Needy(1),
    Helper(2);

    val other: Role get() = if (this == Needy) Helper else Needy
}

/**
 * End-to-end encrypted, mutually authenticated channel between the two phones, carried over
 * the relay (which only ever sees the bytes this class produces).
 *
 * Authentication: both sides prove they know the pairing secret AND the one-time session code
 * (the six digits shown on the phone of the person being helped). Someone who stole the
 * pairing link still cannot join without the code, and the code is only ever read out loud
 * over the call.
 *
 * Confidentiality and forward secrecy: a fresh X25519 key pair per session; the traffic keys
 * come from that exchange, so recording a session and later stealing the pairing secret does
 * not reveal it.
 *
 * Records: AES-256-GCM, one key per direction, a strictly increasing counter as the nonce.
 * A replayed, reordered or tampered record is rejected.
 *
 * Wire format
 *   hello  : 'H' | role | ephemeral public key (32) | nonce (16) | mac (32)
 *   record : 'R' | counter (8, big endian) | AES-GCM ciphertext + tag
 */
class SecureChannel(
    private val role: Role,
    pairingSecret: ByteArray,
    code: String,
    private val sendRaw: (ByteArray) -> Unit,
    private val listener: Listener,
) {
    interface Listener {
        fun onEstablished()
        fun onData(plain: ByteArray)

        /** The peer's hello did not check out: wrong session code or wrong pairing secret. */
        fun onBadHello()
        fun onFailed(reason: String)
    }

    private enum class State { Idle, Keyed, Established, Failed }

    private val macKey: ByteArray = Crypto.hkdf(
        ikm = pairingSecret,
        salt = Crypto.sha256("yattu-code-v1".toByteArray(), code.toByteArray()),
        info = "yattu-mac-v1".toByteArray(),
        length = 32,
    )

    private var state = State.Idle
    private var helloSent = false
    private var ephPrivate = ByteArray(0)
    private var ephPublic = ByteArray(0)
    private var myNonce = ByteArray(0)
    private var sendKey = ByteArray(0)
    private var recvKey = ByteArray(0)
    private var sendCounter = 0L
    private var lastRecvCounter = -1L

    val isEstablished: Boolean @Synchronized get() = state == State.Established

    /**
     * The relay says the other phone has arrived. Every arrival starts a clean handshake, so a
     * helper who mistyped the code and reconnects gets a fresh hello rather than a stalled one.
     * The relay delivers this notice before anything the newcomer sends, so it cannot wipe out
     * a handshake that is already under way.
     */
    @Synchronized
    fun onPeerPresent() {
        reset()
        sendHelloIfNeeded()
    }

    /** The other phone left; forget everything so a reconnect starts a clean handshake. */
    @Synchronized
    fun onPeerGone() = reset()

    @Synchronized
    fun sendData(plain: ByteArray): Boolean {
        if (state != State.Established) return false
        sendRaw(seal(plain))
        return true
    }

    @Synchronized
    fun onMessage(bytes: ByteArray) {
        if (bytes.isEmpty() || state == State.Failed) return
        when (bytes[0]) {
            HELLO -> onHello(bytes)
            RECORD -> onRecord(bytes)
            else -> Unit // ignore anything we do not recognise
        }
    }

    private fun reset() {
        state = State.Idle
        helloSent = false
        ephPrivate = ByteArray(0)
        sendCounter = 0
        lastRecvCounter = -1
        sendKey = ByteArray(0)
        recvKey = ByteArray(0)
    }

    private fun sendHelloIfNeeded() {
        if (helloSent) return
        ephPrivate = Crypto.newX25519PrivateKey()
        ephPublic = Crypto.x25519Public(ephPrivate)
        myNonce = Crypto.randomBytes(NONCE_LEN)
        val mac = helloMac(role, ephPublic, myNonce)
        val out = ByteBuffer.allocate(HELLO_LEN)
            .put(HELLO).put(role.wire).put(ephPublic).put(myNonce).put(mac)
        helloSent = true
        sendRaw(out.array())
    }

    private fun helloMac(sender: Role, pub: ByteArray, nonce: ByteArray): ByteArray =
        Crypto.hmacSha256(macKey, "hello".toByteArray(), byteArrayOf(sender.wire), pub, nonce)

    private fun onHello(bytes: ByteArray) {
        if (bytes.size != HELLO_LEN) return
        if (bytes[1] != role.other.wire) return // a hello from our own role is never valid
        val peerPub = bytes.copyOfRange(2, 34)
        val peerNonce = bytes.copyOfRange(34, 34 + NONCE_LEN)
        val peerMac = bytes.copyOfRange(34 + NONCE_LEN, HELLO_LEN)

        if (!Crypto.constantTimeEquals(peerMac, helloMac(role.other, peerPub, peerNonce))) {
            listener.onBadHello()
            return
        }
        // A fresh, valid hello while we were already keyed means the peer restarted.
        if (state == State.Keyed || state == State.Established) reset()
        sendHelloIfNeeded()

        val shared = try {
            Crypto.x25519Shared(ephPrivate, peerPub)
        } catch (e: Exception) {
            fail("bad key exchange"); return
        }
        val needyPub = if (role == Role.Needy) ephPublic else peerPub
        val helperPub = if (role == Role.Helper) ephPublic else peerPub
        val needyNonce = if (role == Role.Needy) myNonce else peerNonce
        val helperNonce = if (role == Role.Helper) myNonce else peerNonce
        val okm = Crypto.hkdf(
            ikm = shared,
            salt = macKey,
            info = "yattu-session-v1".toByteArray() + needyPub + helperPub + needyNonce + helperNonce,
            length = 64,
        )
        val needyToHelper = okm.copyOfRange(0, 32)
        val helperToNeedy = okm.copyOfRange(32, 64)
        sendKey = if (role == Role.Needy) needyToHelper else helperToNeedy
        recvKey = if (role == Role.Needy) helperToNeedy else needyToHelper
        state = State.Keyed
        // Key confirmation: the first thing we say under the new key. The peer only reaches
        // "established" once it can decrypt this.
        sendRaw(seal(FINISHED))
    }

    private fun onRecord(bytes: ByteArray) {
        if (state != State.Keyed && state != State.Established) return
        if (bytes.size < 1 + 8 + TAG_BYTES) return
        val counter = ByteBuffer.wrap(bytes, 1, 8).long
        if (counter <= lastRecvCounter) return // replayed or reordered
        val plain = try {
            cipher(Cipher.DECRYPT_MODE, recvKey, counter, role.other)
                .doFinal(bytes, 9, bytes.size - 9)
        } catch (e: AEADBadTagException) {
            fail("a message failed its integrity check"); return
        }
        lastRecvCounter = counter
        if (state == State.Keyed) {
            if (plain.contentEquals(FINISHED)) {
                state = State.Established
                listener.onEstablished()
            } else {
                fail("unexpected first message")
            }
            return
        }
        listener.onData(plain)
    }

    private fun seal(plain: ByteArray): ByteArray {
        val counter = sendCounter++
        val ct = cipher(Cipher.ENCRYPT_MODE, sendKey, counter, role).doFinal(plain)
        return ByteBuffer.allocate(1 + 8 + ct.size).put(RECORD).putLong(counter).put(ct).array()
    }

    private fun cipher(mode: Int, key: ByteArray, counter: Long, sender: Role): Cipher {
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD("yattu-record-v1".toByteArray())
            updateAAD(byteArrayOf(sender.wire))
        }
    }

    private fun fail(reason: String) {
        state = State.Failed
        listener.onFailed(reason)
    }

    private companion object {
        const val HELLO: Byte = 0x48 // 'H'
        const val RECORD: Byte = 0x52 // 'R'
        const val NONCE_LEN = 16
        const val TAG_BYTES = 16
        const val HELLO_LEN = 1 + 1 + 32 + NONCE_LEN + 32
        val FINISHED = "yattu-finished".toByteArray()
    }
}
