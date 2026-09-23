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
    fun gesturePathRoundTripsAndClampsItsDuration() {
        val pts = listOf(Protocol.Point(0.1f, 0.8f), Protocol.Point(0.3f, 0.5f), Protocol.Point(0.1f, 0.2f))
        val path = Protocol.parse(Protocol.gesturePath(pts, 400)) as Message.GesturePath
        assertEquals(3, path.points.size)
        near(0.1f, path.points[0].x); near(0.8f, path.points[0].y)
        near(0.3f, path.points[1].x)
        near(0.1f, path.points[2].x); near(0.2f, path.points[2].y)
        assertEquals(400, path.durationMs)
        val twoPts = listOf(Protocol.Point(0f, 0f), Protocol.Point(1f, 1f))
        assertEquals(Protocol.MIN_SWIPE_MS, (Protocol.parse(Protocol.gesturePath(twoPts, 1)) as Message.GesturePath).durationMs)
        assertEquals(Protocol.MAX_SWIPE_MS, (Protocol.parse(Protocol.gesturePath(twoPts, 60000)) as Message.GesturePath).durationMs)
    }

    @Test
    fun gesturePathIsThinnedButKeepsTheExactLiftOffPoint() {
        val lastPoint = Protocol.Point(0.9f, 0.05f)
        val pts = (0 until 200).map { Protocol.Point(it / 200f, it / 200f) } + lastPoint
        val path = Protocol.parse(Protocol.gesturePath(pts, 500)) as Message.GesturePath
        assertEquals(Protocol.MAX_PATH_POINTS, path.points.size)
        near(lastPoint.x, path.points.last().x); near(lastPoint.y, path.points.last().y)
    }

    @Test
    fun gesturePathPadsFewerThanTwoPointsRatherThanProduceAMeaninglessMessage() {
        val one = Protocol.parse(Protocol.gesturePath(listOf(Protocol.Point(0.4f, 0.6f)), 200)) as Message.GesturePath
        assertEquals(2, one.points.size)
        near(0.4f, one.points[0].x); near(0.4f, one.points[1].x)
        val none = Protocol.parse(Protocol.gesturePath(emptyList(), 200)) as Message.GesturePath
        assertEquals(2, none.points.size)
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
        val pts = listOf(Protocol.Point(0.1f, 0.1f), Protocol.Point(0.2f, 0.2f))
        val path = Protocol.gesturePath(pts, 300).also { it[2] = 0x7F }
        assertNull(Protocol.parse(path))
    }

    @Test
    fun wrongLengthsAndUnknownValuesAreRejected() {
        assertNull(Protocol.parse(byteArrayOf()))
        assertNull(Protocol.parse(Protocol.tap(0.5f, 0.5f).copyOf(4)))
        val twoPts = listOf(Protocol.Point(0f, 0f), Protocol.Point(1f, 1f))
        assertNull(Protocol.parse(Protocol.gesturePath(twoPts, 300).copyOf(10)))
        assertNull(Protocol.parse(byteArrayOf(4, 0)))            // request with a payload
        assertNull(Protocol.parse(byteArrayOf(6, 99)))           // unknown control state
        assertNull(Protocol.parse(byteArrayOf(10, 99)))          // unknown nav action
        assertNull(Protocol.parse(byteArrayOf(12, 0)))           // retired CONNECTION_QUALITY
        assertNull(Protocol.parse(byteArrayOf(1, 1, 0, 1, 0, 1, 9))) // retired VIDEO_CHUNK
        assertNull(Protocol.parse(byteArrayOf(17, 9, 0, 0, 0, 0))) // unknown touch phase
        assertNull(Protocol.parse(byteArrayOf(15, 0)))           // keyframe request with a payload
        assertNull(Protocol.parse(byteArrayOf(120)))             // unknown type
        val bytes = Protocol.gesturePath(twoPts, 300)
        val zeroDuration = bytes.also { it[it.size - 2] = 0; it[it.size - 1] = 0 }
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
    fun videoFrameRoundTripsWithItsSequenceTimestampAndCodec() {
        val key = Protocol.parse(
            Protocol.videoFrame(true, VideoCodec.Avc, 720, 1600, seq = 41, sentAt = -5, data = byteArrayOf(9, 8, 7)),
        ) as Message.VideoFrame
        assertTrue(key.keyframe)
        assertEquals(VideoCodec.Avc, key.codec)
        assertEquals(720, key.width); assertEquals(1600, key.height)
        assertEquals(41, key.seq)
        assertEquals("a sender clock past 2^31 ms still round-trips exactly", -5, key.sentAt)
        assertEquals(listOf<Byte>(9, 8, 7), key.data.toList())

        val delta = Protocol.parse(Protocol.videoFrame(false, VideoCodec.Hevc, 544, 1208, 42, 7, byteArrayOf(1))) as Message.VideoFrame
        assertFalse(delta.keyframe)
        assertEquals(VideoCodec.Hevc, delta.codec)
        assertEquals(42, delta.seq)
    }

    @Test
    fun videoFrameRejectsZeroDimensionsAndEmptyData() {
        assertNull(Protocol.parse(Protocol.videoFrame(true, VideoCodec.Avc, 0, 100, 1, 1, byteArrayOf(1))))
        assertNull(Protocol.parse(Protocol.videoFrame(true, VideoCodec.Avc, 100, 100, 1, 1, byteArrayOf())))
    }

    @Test
    fun receiverReportRoundTripsAndClampsWhatDoesNotFit() {
        val r = Message.ReceiverReport(highestSeq = 1_000_000, echoSentAt = -123, holdMs = 40, queueDelayMs = 250, lostFrames = 3)
        assertEquals(r, Protocol.parse(Protocol.receiverReport(r)))
        val huge = Message.ReceiverReport(1, 1, holdMs = 999_999, queueDelayMs = -4, lostFrames = 70_000)
        assertEquals(Message.ReceiverReport(1, 1, 65535, 0, 65535), Protocol.parse(Protocol.receiverReport(huge)))
    }

    @Test
    fun keyframeRequestDecodersAndSenderStatsRoundTrip() {
        assertEquals(Message.KeyframeRequest, Protocol.parse(Protocol.keyframeRequest()))
        for (set in listOf(setOf(VideoCodec.Avc), setOf(VideoCodec.Avc, VideoCodec.Hevc), emptySet())) {
            assertEquals(Message.Decoders(set), Protocol.parse(Protocol.decoders(set)))
        }
        for (q in ConnectionQuality.entries) {
            val s = Message.SenderStats(
                q, bitrateKbps = 1600, tier = 2, rttMs = 380, droppedFrames = 12, encoderSetup = 1,
                inputSteps = 900, inputFailed = 3, inputSlowestMs = 240, inputCancelled = 1, inputResumed = 1,
            )
            assertEquals(s, Protocol.parse(Protocol.senderStats(s)))
        }
    }

    @Test
    fun touchRoundTripsEveryPhase() {
        for (phase in TouchPhase.entries) {
            val t = Protocol.parse(Protocol.touch(phase, 0.25f, 0.5f)) as Message.Touch
            assertEquals(phase, t.phase)
            near(0.25f, t.x); near(0.5f, t.y)
        }
    }

    @Test
    fun sharingStartedRoundTrips() {
        assertEquals(Message.SharingStarted, Protocol.parse(Protocol.sharingStarted()))
    }
}
