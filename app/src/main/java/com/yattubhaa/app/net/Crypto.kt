package com.yattubhaa.app.net

import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The few primitives the secure channel needs. No Android APIs on purpose, so the whole
 * handshake can be unit-tested on the JVM. X25519 comes from Tink; everything else is the
 * JDK's own HMAC, SHA-256 and AES-GCM.
 */
object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    /** RFC 5869 HKDF with SHA-256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32))
        val prk = hmacSha256(salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            previous = hmacSha256(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - pos)
            previous.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    fun newX25519PrivateKey(): ByteArray = X25519.generatePrivateKey()
    fun x25519Public(private: ByteArray): ByteArray = X25519.publicFromPrivate(private)
    fun x25519Shared(private: ByteArray, peerPublic: ByteArray): ByteArray =
        X25519.computeSharedSecret(private, peerPublic)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun fromB64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    /**
     * Name of the relay room for a pairing. Derived from the pairing secret with a one-way hash,
     * so the relay learns a stable room name but nothing that helps recover the secret.
     */
    fun roomId(pairingSecret: ByteArray): String =
        b64url(sha256("yattu-room-v1".toByteArray(), pairingSecret))
}
