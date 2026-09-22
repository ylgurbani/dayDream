package com.yattubhaa.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateLimiterTest {
    private val limiter = FrameRateLimiter(minIntervalMs = 40)

    @Test
    fun theVeryFirstChunkIsNeverHeldBack() {
        assertTrue(limiter.shouldForward(nowMs = 0, isKeyFrame = false))
    }

    @Test
    fun aDeltaFrameArrivingTooSoonIsDropped() {
        limiter.shouldForward(0, isKeyFrame = false)
        assertFalse(limiter.shouldForward(10, isKeyFrame = false))
        assertFalse(limiter.shouldForward(39, isKeyFrame = false))
    }

    @Test
    fun aDeltaFrameArrivingAfterTheIntervalIsForwarded() {
        limiter.shouldForward(0, isKeyFrame = false)
        assertTrue(limiter.shouldForward(40, isKeyFrame = false))
        assertTrue(limiter.shouldForward(81, isKeyFrame = false))
    }

    @Test
    fun aKeyframeAlwaysGoesThroughRegardlessOfTiming() {
        limiter.shouldForward(0, isKeyFrame = false)
        assertTrue("a keyframe one millisecond later must still be sent", limiter.shouldForward(1, isKeyFrame = true))
    }

    @Test
    fun aKeyframeResetsTheTimerForTheNextDeltaFrame() {
        limiter.shouldForward(0, isKeyFrame = true)
        assertFalse(limiter.shouldForward(20, isKeyFrame = false))
        assertTrue(limiter.shouldForward(41, isKeyFrame = false))
    }

    @Test
    fun aBurstOfDeltaFramesIsThinnedToRoughlyTheTargetRate() {
        var forwarded = 0
        for (t in 0 until 1000 step 4) { // a burst arriving every 4ms - ~250 "frames" a second
            if (limiter.shouldForward(t.toLong(), isKeyFrame = false)) forwarded++
        }
        // At a 40ms minimum interval that is ~25/s; allow a little slack either side.
        assertTrue("forwarded $forwarded chunks in one second", forwarded in 20..30)
    }
}
