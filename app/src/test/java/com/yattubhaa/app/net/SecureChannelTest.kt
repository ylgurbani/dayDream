package com.yattubhaa.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two channels wired back to back through in-memory queues, standing in for the relay. */
private class Harness(
    needyCode: String = "123456",
    helperCode: String = "123456",
    needySecret: ByteArray = SECRET,
    helperSecret: ByteArray = SECRET,
) {
    val toHelper = ArrayDeque<ByteArray>()
    val toNeedy = ArrayDeque<ByteArray>()
    val needyEvents = mutableListOf<String>()
    val helperEvents = mutableListOf<String>()
    val needyData = mutableListOf<ByteArray>()
    val helperData = mutableListOf<ByteArray>()

    private fun listener(events: MutableList<String>, data: MutableList<ByteArray>) =
        object : SecureChannel.Listener {
            override fun onEstablished() { events += "established" }
            override fun onData(plain: ByteArray) { data += plain }
            override fun onBadHello() { events += "badHello" }
            override fun onFailed(reason: String) { events += "failed" }
        }

    val needy = SecureChannel(Role.Needy, needySecret, needyCode, { toHelper.addLast(it) }, listener(needyEvents, needyData))
    val helper = SecureChannel(Role.Helper, helperSecret, helperCode, { toNeedy.addLast(it) }, listener(helperEvents, helperData))

    fun pump() {
        while (toHelper.isNotEmpty() || toNeedy.isNotEmpty()) {
            toHelper.removeFirstOrNull()?.let(helper::onMessage)
            toNeedy.removeFirstOrNull()?.let(needy::onMessage)
        }
    }

    fun connect() {
        needy.onPeerPresent()
        helper.onPeerPresent()
        pump()
    }

    companion object {
        val SECRET = ByteArray(32) { it.toByte() }
    }
}

class SecureChannelTest {
    @Test
    fun handshakeEstablishesAndDataFlowsBothWays() {
        val h = Harness()
        h.connect()
        assertEquals(listOf("established"), h.needyEvents)
        assertEquals(listOf("established"), h.helperEvents)

        assertTrue(h.needy.sendData("screen frame".toByteArray()))
        assertTrue(h.helper.sendData("point here".toByteArray()))
        h.pump()
        assertArrayEquals("screen frame".toByteArray(), h.helperData.single())
        assertArrayEquals("point here".toByteArray(), h.needyData.single())
    }

    @Test
    fun cannotSendBeforeTheChannelIsEstablished() {
        val h = Harness()
        assertFalse(h.needy.sendData(byteArrayOf(1)))
    }

    @Test
    fun wrongSessionCodeNeverEstablishes() {
        val h = Harness(needyCode = "123456", helperCode = "654321")
        h.connect()
        assertTrue("needy should reject", h.needyEvents.contains("badHello"))
        assertTrue("helper should reject", h.helperEvents.contains("badHello"))
        assertFalse(h.needyEvents.contains("established"))
        assertFalse(h.helperEvents.contains("established"))
        assertFalse(h.helper.sendData(byteArrayOf(1)))
    }

    @Test
    fun wrongPairingSecretNeverEstablishesEvenWithTheRightCode() {
        val h = Harness(helperSecret = ByteArray(32) { 99 })
        h.connect()
        assertTrue(h.needyEvents.contains("badHello"))
        assertFalse(h.needyEvents.contains("established"))
    }

    @Test
    fun relayCannotReadOrAlterRecords() {
        val h = Harness()
        h.connect()
        val secret = "my bank balance is 12345".toByteArray()
        h.needy.sendData(secret)
        val onTheWire = h.toHelper.first()
        assertFalse(String(onTheWire, Charsets.ISO_8859_1).contains("bank balance"))

        onTheWire[onTheWire.size - 1] = (onTheWire.last().toInt() xor 1).toByte() // flip one bit
        h.pump()
        assertTrue(h.helperEvents.contains("failed"))
        assertTrue(h.helperData.isEmpty())
    }

    @Test
    fun replayedRecordsAreDeliveredOnlyOnce() {
        val h = Harness()
        h.connect()
        h.needy.sendData("once".toByteArray())
        val record = h.toHelper.first().copyOf()
        h.pump()
        h.helper.onMessage(record) // the relay replays it
        h.helper.onMessage(record)
        assertEquals(1, h.helperData.size)
    }

    @Test
    fun aHelloReflectedBackToItsSenderIsIgnored() {
        val h = Harness()
        h.needy.onPeerPresent()
        val ownHello = h.toHelper.first()
        h.needy.onMessage(ownHello)
        assertTrue(h.needyEvents.isEmpty())
        assertFalse(h.needy.isEstablished)
    }

    @Test
    fun everySessionUsesFreshKeys() {
        val first = Harness().also { it.connect() }
        val second = Harness().also { it.connect() }
        first.needy.sendData("same plaintext".toByteArray())
        second.needy.sendData("same plaintext".toByteArray())
        assertNotEquals(
            first.toHelper.first().toList(),
            second.toHelper.first().toList(),
        )
    }

    @Test
    fun aPeerThatLeavesAndReturnsGetsACleanNewHandshake() {
        val h = Harness()
        h.connect()
        h.helper.onPeerGone()
        h.needy.onPeerGone()
        assertFalse(h.needy.isEstablished)
        h.helperEvents.clear(); h.needyEvents.clear()
        h.connect()
        assertEquals(listOf("established"), h.needyEvents)
        h.needy.sendData("after reconnect".toByteArray())
        h.pump()
        assertArrayEquals("after reconnect".toByteArray(), h.helperData.last())
    }

    @Test
    fun aHelperWhoMistypedTheCodeCanRetryAndConnect() {
        val needyEvents = mutableListOf<String>()
        val toHelper = ArrayDeque<ByteArray>()
        val toNeedy = ArrayDeque<ByteArray>()
        val needy = SecureChannel(Role.Needy, Harness.SECRET, "123456", { toHelper.addLast(it) },
            object : SecureChannel.Listener {
                override fun onEstablished() { needyEvents += "established" }
                override fun onData(plain: ByteArray) {}
                override fun onBadHello() { needyEvents += "badHello" }
                override fun onFailed(reason: String) { needyEvents += "failed" }
            })
        fun helperAttempt(code: String): SecureChannel {
            toHelper.clear(); toNeedy.clear()
            val helper = SecureChannel(Role.Helper, Harness.SECRET, code, { toNeedy.addLast(it) },
                object : SecureChannel.Listener {
                    override fun onEstablished() {}
                    override fun onData(plain: ByteArray) {}
                    override fun onBadHello() {}
                    override fun onFailed(reason: String) {}
                })
            needy.onPeerPresent()
            helper.onPeerPresent()
            while (toHelper.isNotEmpty() || toNeedy.isNotEmpty()) {
                toHelper.removeFirstOrNull()?.let(helper::onMessage)
                toNeedy.removeFirstOrNull()?.let(needy::onMessage)
            }
            return helper
        }
        helperAttempt("000000")
        assertTrue(needyEvents.contains("badHello"))
        assertFalse(needy.isEstablished)
        helperAttempt("123456")
        assertTrue(needy.isEstablished)
    }

    @Test
    fun hkdfMatchesRfc5869TestVector() {
        // RFC 5869 appendix A.1
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = Crypto.hkdf(ikm, salt, info, 42)
        val expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        assertEquals(expected, okm.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun roomIdDoesNotRevealTheSecretAndIsUrlSafe() {
        val id = Crypto.roomId(Harness.SECRET)
        assertTrue(id.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertNotEquals(Crypto.b64url(Harness.SECRET), id)
    }
}
