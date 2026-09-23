package com.yattubhaa.app.service

import com.yattubhaa.app.net.ConnectionQuality
import com.yattubhaa.app.service.CongestionController.Companion.TIERS
import com.yattubhaa.app.service.CongestionController.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CongestionControllerTest {
    private val bitrates = mutableListOf<Int>()
    private val tiers = mutableListOf<Int>()
    private val tierChangedAt = mutableListOf<Long>()
    private var now = 0L
    private val c = CongestionController(
        onBitrateChanged = { bitrates += it },
        onTierChanged = { tiers += it; tierChangedAt += now },
    )

    private var sent = 0

    /** One second passes: ten more frames go out, and (unless [confirmed] is given) the helper
     *  has confirmed everything up to halfway through them. */
    private fun sample(
        delay: Int? = 0, backlog: Long = 0, stale: Boolean = false, stalled: Boolean = false, confirmed: Int? = sent + 5,
        paused: Double = 0.0, keyframe: Boolean = false,
    ) {
        now += 1000
        sent += 10
        c.onSample(Sample(now, backlog, delay, stale, stalled, sentSeq = sent, confirmedSeq = confirmed, paused, keyframe))
    }

    @Test
    fun startsModestlyAtFullSizeWithNothingReportedYet() {
        assertEquals(1_000_000, c.bitrate)
        assertEquals(0, c.tierIndex)
        assertEquals(ConnectionQuality.Good, c.quality)
        assertTrue(bitrates.isEmpty() && tiers.isEmpty())
    }

    @Test
    fun aQueueBuildingUpAnywhereOnTheWayHalvesTheBitrateAtOnce() {
        sample(delay = 800)
        assertEquals(500_000, c.bitrate)
        assertEquals(ConnectionQuality.Poor, c.quality)
    }

    @Test
    fun aQueueAlreadyDrainingAfterACutIsLeftToDrain() {
        sample(delay = 1500) // 1M -> 500k, the bottom of the full-size range
        assertEquals(500_000, c.bitrate)
        sample(delay = 900) // still high, but falling fast: the last cut is working, so not counted
        sample(delay = 900) // stopped falling: the first sample that counts at the floor
        assertTrue(tiers.isEmpty())
        sample(delay = 900) // the second: step down
        assertEquals(listOf(1), tiers)
    }

    @Test
    fun framesNotArrivingAtAllCountsAsCongestionEvenWithNoDelayReading() {
        sample(delay = null, stalled = true)
        assertEquals(500_000, c.bitrate)
        sample(delay = null, stale = true)
        assertEquals(ConnectionQuality.Poor, c.quality)
    }

    @Test
    fun itsOwnQueueStillCountsAndIsJudgedInSecondsOfPicture() {
        sample(backlog = 100_000) // 0.8s at 1 Mbps
        assertEquals(500_000, c.bitrate)
        sample(backlog = 40_000) // draining: leave it be
        assertEquals(500_000, c.bitrate)
    }

    @Test
    fun sustainedCongestionStepsDownToSmallerFewerFramesNeverBelowTheLast() {
        repeat(30) { sample(delay = 2000, stalled = true) }
        assertEquals(listOf(1, 2), tiers)
        assertEquals(TIERS.last().minBitrate, c.bitrate)
        assertEquals(ConnectionQuality.Poor, c.quality)
    }

    @Test
    fun atTheBottomOfARangeItWaitsASecondSampleBeforeChangingSize() {
        sample(stalled = true) // 1M -> 500k, the bottom of the full-size range
        assertEquals(500_000, c.bitrate)
        sample(stalled = true)
        assertTrue("one sample at the floor is not enough", tiers.isEmpty())
        sample(stalled = true)
        assertEquals(listOf(1), tiers)
        assertEquals(TIERS[1].minBitrate, c.bitrate)
    }

    @Test
    fun aClearConnectionClimbsGraduallyAndHoldsSteadyWhenInBetween() {
        sample(delay = 800) // down to 500k
        bitrates.clear()
        sample(); assertTrue("one clear sample is not enough", bitrates.isEmpty())
        sample(); assertEquals(listOf(575_000), bitrates)
        sample(delay = 250); sample(delay = 250); sample(delay = 250)
        assertEquals("some delay, but not congested: hold", listOf(575_000), bitrates)
        assertEquals(ConnectionQuality.Fair, c.quality)
        repeat(60) { sample() }
        assertEquals(TIERS[0].maxBitrate, c.bitrate)
        assertEquals(ConnectionQuality.Good, c.quality)
    }

    @Test
    fun itStepsBackUpOnlyAfterHoldingAndAQuicklyRegrettedStepMakesTheNextWaitLonger() {
        repeat(4) { sample(stalled = true) } // down to the second size
        assertEquals(listOf(1), tiers)
        while (tiers.size == 1) sample() // climbs the second size's range, then steps back up
        assertEquals(listOf(1, 0), tiers)
        assertTrue("held at least 20s", tierChangedAt[1] - tierChangedAt[0] >= 20_000)

        repeat(4) { sample(stalled = true) } // straight back down: the step up did not hold
        assertEquals(listOf(1, 0, 1), tiers)
        while (tiers.size == 3) sample()
        assertEquals(listOf(1, 0, 1, 0), tiers)
        assertTrue("a quick retreat doubles the wait", tierChangedAt[3] - tierChangedAt[2] >= 40_000)
        assertTrue("but not more than that", tierChangedAt[3] - tierChangedAt[2] < 45_000)
    }

    @Test
    fun afterACutItWaitsForTheHelperToSeeFramesSentAfterItBeforeCuttingAgain() {
        sample(delay = 2000) // 1M -> 500k; the last frame sent before the cut is number 10
        val stuckAt = sent - 5
        sample(delay = 2000, confirmed = stuckAt) // still the old rate's queue: not cut again
        sample(delay = 2000, confirmed = stuckAt)
        assertEquals(500_000, c.bitrate)
        assertTrue(tiers.isEmpty())
        sample(delay = 2000, confirmed = 11) // a frame sent after the cut has arrived: judge again
        sample(delay = 2000, confirmed = 21)
        assertEquals("two congested samples at the floor, both after the cut showed", listOf(1), tiers)
    }

    @Test
    fun ifNothingSentAfterACutEverArrivesItActsAgainAfterAWhileAnyway() {
        sample(delay = 2000)
        repeat(3) { sample(delay = 2000, confirmed = 5) }
        assertTrue("within the settling time", tiers.isEmpty())
        repeat(2) { sample(delay = 2000, confirmed = 5) }
        assertEquals(listOf(1), tiers)
    }

    @Test
    fun captureHeldBackMostOfTheTimeCountsAsCongestionButNotForAKeyframe() {
        sample(paused = 0.3)
        assertEquals("held back a little: fine", 1_000_000, c.bitrate)
        sample(paused = 0.8, keyframe = true)
        assertEquals("a keyframe always holds things up briefly", 1_000_000, c.bitrate)
        sample(paused = 0.8)
        assertEquals(500_000, c.bitrate)
        assertEquals(ConnectionQuality.Poor, c.quality)
    }

    @Test
    fun starvingOnceTheOldBacklogHasArrivedNeedNotWaitForAPostCutFrame() {
        sample(paused = 0.8) // 1M -> 500k; the last frame before the cut is number 10
        sample(paused = 0.8, confirmed = 10) // held back, but everything from before the cut is in
        sample(paused = 0.8, confirmed = 10)
        assertEquals("two starved samples at the floor, without a post-cut frame confirmed", listOf(1), tiers)
    }
}
