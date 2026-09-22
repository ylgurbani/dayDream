package com.yattubhaa.app.net

import com.yattubhaa.app.net.Protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun near(a: Float, b: Float) = assertTrue("$a vs $b", kotlin.math.abs(a - b) < 0.0002f)

    @Test
    fun tapAndLongPressRoundTrip() {
        val tap = Protocol.parse(Protocol.tap(0.25f, 0.75f)) as Message.Tap
        near(0.25f, tap.x); near(0.75f, tap.y)
        val long = Protocol.parse(Protocol.longPress(1f, 0f)) as Message.LongPress
        near(1f, long.x); near(0f, long.y)
    }

    @Test
    fun swipeRoundTripsAndClampsItsDuration() {
        val swipe = Protocol.parse(Protocol.swipe(0.1f, 0.8f, 0.1f, 0.2f, 400)) as Message.Swipe
        near(0.1f, swipe.x1); near(0.8f, swipe.y1); near(0.2f, swipe.y2)
        assertEquals(400, swipe.durationMs)
        assertEquals(Protocol.MIN_SWIPE_MS, (Protocol.parse(Protocol.swipe(0f, 0f, 1f, 1f, 1)) as Message.Swipe).durationMs)
        assertEquals(Protocol.MAX_SWIPE_MS, (Protocol.parse(Protocol.swipe(0f, 0f, 1f, 1f, 60000)) as Message.Swipe).durationMs)
    }

    @Test
    fun navControlAndStatusRoundTrip() {
        for (a in NavAction.entries) assertEquals(Message.Nav(a), Protocol.parse(Protocol.nav(a)))
        for (s in ControlState.entries) assertEquals(Message.ControlStatus(s), Protocol.parse(Protocol.controlStatus(s)))
        assertEquals(Message.ControlRequest, Protocol.parse(Protocol.controlRequest()))
        assertEquals(Message.ControlRelease, Protocol.parse(Protocol.controlRelease()))
    }

    @Test
    fun outOfRangeCoordinatesAreRejected() {
        val bad = Protocol.tap(0.5f, 0.5f).also { it[1] = 0x7F; it[2] = 0x7F } // 32639 > 10000
        assertNull(Protocol.parse(bad))
        val swipe = Protocol.swipe(0.1f, 0.1f, 0.2f, 0.2f, 300).also { it[7] = 0x7F }
        assertNull(Protocol.parse(swipe))
    }

    @Test
    fun wrongLengthsAndUnknownValuesAreRejected() {
        assertNull(Protocol.parse(byteArrayOf()))
        assertNull(Protocol.parse(Protocol.tap(0.5f, 0.5f).copyOf(4)))
        assertNull(Protocol.parse(Protocol.swipe(0f, 0f, 1f, 1f, 300).copyOf(10)))
        assertNull(Protocol.parse(byteArrayOf(4, 0)))            // request with a payload
        assertNull(Protocol.parse(byteArrayOf(6, 99)))           // unknown control state
        assertNull(Protocol.parse(byteArrayOf(10, 99)))          // unknown nav action
        assertNull(Protocol.parse(byteArrayOf(120)))             // unknown type
        val zeroDuration = Protocol.swipe(0f, 0f, 1f, 1f, 300).also { it[9] = 0; it[10] = 0 }
        assertNull(Protocol.parse(zeroDuration))
    }

    @Test
    fun earlierMessagesStillWork() {
        val p = Protocol.parse(Protocol.pointer(0.5f, 0.5f)) as Message.Pointer
        near(0.5f, p.x!!)
        assertEquals(Message.Pointer(null, null), Protocol.parse(Protocol.clearPointer()))
        assertEquals(Message.Stop, Protocol.parse(Protocol.stop()))
    }

    @Test
    fun videoChunkRoundTripsAndKeepsItsKeyframeFlag() {
        val key = Protocol.parse(Protocol.videoChunk(true, 720, 1600, byteArrayOf(9, 8, 7))) as Message.VideoChunk
        assertTrue(key.keyframe)
        assertEquals(720, key.width); assertEquals(1600, key.height)
        assertEquals(listOf<Byte>(9, 8, 7), key.data.toList())

        val delta = Protocol.parse(Protocol.videoChunk(false, 720, 1600, byteArrayOf(1))) as Message.VideoChunk
        assertFalse(delta.keyframe)
    }

    @Test
    fun videoChunkRejectsZeroDimensionsAndEmptyData() {
        assertNull(Protocol.parse(Protocol.videoChunk(true, 0, 100, byteArrayOf(1))))
        assertNull(Protocol.parse(Protocol.videoChunk(true, 100, 100, byteArrayOf())))
    }

    @Test
    fun sharingStartedRoundTrips() {
        assertEquals(Message.SharingStarted, Protocol.parse(Protocol.sharingStarted()))
    }
}
