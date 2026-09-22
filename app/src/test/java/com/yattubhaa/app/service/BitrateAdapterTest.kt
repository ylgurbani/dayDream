package com.yattubhaa.app.service

import com.yattubhaa.app.net.ConnectionQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateAdapterTest {
    private val bitrates = mutableListOf<Int>()
    private val qualities = mutableListOf<ConnectionQuality>()
    private val adapter = BitrateAdapter(onBitrateChanged = { bitrates += it }, onQualityChanged = { qualities += it })

    @Test
    fun startsAtAComfortableDefaultAndReportsNoChangesYet() {
        assertEquals(1_600_000, adapter.bitrate)
        assertEquals(ConnectionQuality.Good, adapter.quality)
        assertTrue(bitrates.isEmpty())
        assertTrue(qualities.isEmpty())
    }

    @Test
    fun aBackedUpQueueDropsTheBitrateImmediatelyAndReportsPoor() {
        val before = adapter.bitrate
        adapter.onSample(200_000)
        assertTrue("$before -> ${adapter.bitrate}", adapter.bitrate < before)
        assertEquals(listOf(adapter.bitrate), bitrates)
        assertEquals(ConnectionQuality.Poor, adapter.quality)
        assertEquals(listOf(ConnectionQuality.Poor), qualities)
    }

    @Test
    fun repeatedCongestionNeverDropsBelowTheFloor() {
        repeat(20) { adapter.onSample(500_000) }
        assertEquals(400_000, adapter.bitrate)
    }

    @Test
    fun onlyASustainedEmptyQueueRaisesTheBitrateAgain() {
        adapter.onSample(300_000) // knock it down first
        val reduced = adapter.bitrate
        bitrates.clear()
        adapter.onSample(0)
        adapter.onSample(0)
        assertEquals("two good samples should not be enough yet", reduced, adapter.bitrate)
        adapter.onSample(0)
        assertTrue("the third good sample should raise it", adapter.bitrate > reduced)
        assertEquals(listOf(adapter.bitrate), bitrates)
    }

    @Test
    fun aNonCongestedButNonEmptyQueueHoldsSteadyRatherThanGuessing() {
        adapter.onSample(0); adapter.onSample(0)
        adapter.onSample(50_000) // some backlog, but under the congested threshold
        adapter.onSample(0)
        assertEquals("the streak toward an increase should have been reset", 1_600_000, adapter.bitrate)
    }

    @Test
    fun repeatedGoodSamplesNeverRiseAboveTheCeiling() {
        repeat(60) { adapter.onSample(0) }
        assertEquals(2_500_000, adapter.bitrate)
    }

    @Test
    fun qualityMovesThroughPoorFairAndBackToGoodAsTheConnectionRecovers() {
        adapter.onSample(300_000) // congested: drops the bitrate and reports Poor right away
        assertEquals(ConnectionQuality.Poor, adapter.quality)
        adapter.onSample(0) // no longer congested, but the bitrate is still reduced: Fair
        assertEquals(ConnectionQuality.Fair, adapter.quality)
        repeat(60) { adapter.onSample(0) } // sustained good samples climb back to the ceiling
        assertEquals(2_500_000, adapter.bitrate)
        assertEquals(ConnectionQuality.Good, adapter.quality)
    }

    @Test
    fun callbacksFireOnlyOnActualChangeNotEverySample() {
        adapter.onSample(0)
        adapter.onSample(0) // still under the streak needed to raise the bitrate
        assertTrue("no bitrate change yet, so no callback yet", bitrates.isEmpty())
    }
}
