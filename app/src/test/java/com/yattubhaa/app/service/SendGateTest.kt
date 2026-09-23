package com.yattubhaa.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendGateTest {
    private var requests = 0
    private val pauses = mutableListOf<Boolean>()
    private val gate = SendGate(requestKeyframe = { requests++ }, setSourcePaused = { pauses += it })
    private val bitrate = 1_000_000 // 125KB/s: pause at 62.5KB queued, drop at 375KB

    @Test
    fun anUncongestedStreamGoesStraightThrough() {
        assertTrue(gate.shouldSend(keyframe = true, backlogBytes = 0, bitrate = bitrate, pathClear = true, nowMs = 0))
        repeat(10) { assertTrue(gate.shouldSend(false, 1_000, bitrate, true, it * 50L)) }
        assertEquals(0, requests)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun aBackedUpLinkPausesTheSourceButStillSendsWhatWasAlreadyEncoded() {
        assertTrue(gate.shouldSend(true, 0, bitrate, true, 0))
        assertTrue("already encoded: dropping it would break the chain", gate.shouldSend(false, 70_000, bitrate, true, 50))
        assertEquals(listOf(true), pauses)
        assertTrue(gate.shouldSend(false, 80_000, bitrate, true, 100))
        gate.check(10_000, bitrate, pathClear = true, nowMs = 400) // drained: resume
        assertEquals(listOf(true, false), pauses)
        assertTrue(gate.shouldSend(false, 0, bitrate, true, 450))
        assertEquals("the chain was never broken, so no keyframe needed", 0, requests)
    }

    @Test
    fun framesNotReachingTheHelperPauseTheSourceEvenWithNothingQueuedHere() {
        gate.check(0, bitrate, pathClear = false, nowMs = 0)
        assertTrue(gate.sourcePaused)
        gate.check(0, bitrate, pathClear = true, nowMs = 300)
        assertFalse(gate.sourcePaused)
        assertEquals(0, requests)
    }

    @Test
    fun onlyAQueueFarPastThePausePointDropsAndThenEverythingWaitsForAKeyframe() {
        assertTrue(gate.shouldSend(true, 0, bitrate, true, 0))
        assertFalse(gate.shouldSend(false, 400_000, bitrate, true, 100))
        assertFalse("the dropped frame is missing from the chain", gate.shouldSend(false, 0, bitrate, true, 150))
        assertTrue(gate.awaitingKeyframe)
        assertEquals("asked once there was room", 1, requests)
        gate.check(0, bitrate, true, 200)
        assertEquals("not again straight away", 1, requests)
        gate.check(0, bitrate, true, 150 + SendGate.REQUEST_INTERVAL_MS)
        assertEquals("but again if it has still not come", 2, requests)
        assertTrue(gate.shouldSend(true, 0, bitrate, true, 1_200))
        assertFalse(gate.awaitingKeyframe)
        assertTrue(gate.shouldSend(false, 0, bitrate, true, 1_250))
    }

    @Test
    fun noKeyframeIsAskedForWhileTheLinkIsStillBackedUp() {
        gate.shouldSend(false, 400_000, bitrate, true, 0)
        gate.check(300_000, bitrate, true, 100)
        gate.check(0, bitrate, pathClear = false, nowMs = 200)
        assertEquals(0, requests)
        gate.check(0, bitrate, true, 300)
        assertEquals(1, requests)
    }

    @Test
    fun aSendTheConnectionRefusedAlsoNeedsAKeyframeAfterIt() {
        assertTrue(gate.shouldSend(false, 0, bitrate, true, 0))
        gate.onSendFailed()
        assertFalse(gate.shouldSend(false, 0, bitrate, true, 50))
        assertEquals(1, requests)
    }

    @Test
    fun limitsAreSecondsOfPictureNotAFixedByteCount() {
        assertEquals(156_250L, SendGate.pauseLimit(2_500_000))
        assertEquals("never absurdly small at the lowest bitrates", 16 * 1024L, SendGate.pauseLimit(120_000))
        assertEquals(937_500L, SendGate.dropLimit(2_500_000))
    }

    @Test
    fun anEncoderOvershootingItsTargetIsAskedForLessUntilItsOutputFits() {
        val c = OvershootCorrector()
        val target = 120_000
        // A made-up encoder that always produces three times whatever it is given.
        repeat(20) { c.onSample(producedBytes = (3 * target * c.factor / 8).toLong(), activeMs = 1000, targetBps = target) }
        assertEquals(1.0 / 3, c.factor, 0.02)
    }

    @Test
    fun aStillScreenProducingLittleNeverRaisesTheRequestAboveTheTarget() {
        val c = OvershootCorrector()
        c.onSample(producedBytes = 30_000, activeMs = 1000, targetBps = 120_000) // 2x over
        val lowered = c.factor
        assertTrue(lowered < 1.0)
        repeat(10) { c.onSample(producedBytes = 500, activeMs = 1000, targetBps = 120_000) }
        assertEquals(1.0, c.factor, 0.0)
        c.onSample(producedBytes = 1_000_000, activeMs = 100, targetBps = 120_000)
        assertEquals("too short a window to judge", 1.0, c.factor, 0.0)
    }
}
