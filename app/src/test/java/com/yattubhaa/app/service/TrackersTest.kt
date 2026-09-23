package com.yattubhaa.app.service

import com.yattubhaa.app.net.Protocol.Message.ReceiverReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two ends of one measurement: what the helper sees arriving, and what the sender makes of it. */
class TrackersTest {
    // The helper's clock bears no relation to the sender's: a big, arbitrary offset between them.
    private val helperClockOffset = 7_654_321L

    @Test
    fun theHelperNoticesAMissingFrame() {
        val rx = ReceiveTracker()
        assertFalse(rx.onFrame(seq = 0, sentAt = 0, keyframe = true, size = 100, nowMs = 1_000))
        assertFalse(rx.onFrame(1, 50, false, 10, 1_050))
        assertTrue("frame 2 never came", rx.onFrame(3, 150, false, 10, 1_150))
        assertEquals(1, rx.lostFrames)
        assertFalse(rx.onFrame(4, 200, false, 10, 1_200))
    }

    @Test
    fun queueingDelayIsMeasuredWithoutTheClocksAgreeing() {
        val rx = ReceiveTracker()
        assertNull("nothing to report before any frame", rx.report(0))
        // 100ms of real transit at first, then a queue builds and frames take 700ms.
        rx.onFrame(0, sentAt = 1_000, keyframe = true, size = 1, nowMs = helperClockOffset + 1_100)
        rx.onFrame(1, sentAt = 1_500, keyframe = false, size = 1, nowMs = helperClockOffset + 2_200)
        val report = rx.report(helperClockOffset + 2_300)!!
        assertEquals(600, report.queueDelayMs)
        assertEquals(1, report.highestSeq)
        assertEquals("the sender's own timestamp, echoed", 1_500, report.echoSentAt)
        assertEquals("held for 100ms before reporting", 100, report.holdMs)
    }

    @Test
    fun theQuietestRecentMomentIsTheBaselineAndOldOnesExpire() {
        val rx = ReceiveTracker()
        rx.onFrame(0, 0, true, 1, helperClockOffset + 100)       // transit 100
        rx.onFrame(1, 40_000, false, 1, helperClockOffset + 40_300) // transit 300, 40s later
        // The 100ms reading is now older than the baseline window, so 300 is the new "no queue".
        assertEquals(0, rx.report(helperClockOffset + 40_300)!!.queueDelayMs)
    }

    @Test
    fun theSenderWorksOutTheRoundTripOnItsOwnClock() {
        val tx = SendTracker()
        tx.onSent(0, nowMs = 10_000, bytes = 100)
        tx.onReport(ReceiverReport(highestSeq = 0, echoSentAt = 10_000, holdMs = 150, queueDelayMs = 0, lostFrames = 0), nowMs = 10_550)
        assertEquals(400, tx.rttMs)
    }

    @Test
    fun theSenderPassesOnTheHelpersDelayOnlyWhileItIsFresh() {
        val tx = SendTracker()
        tx.onSent(0, 0, 100)
        assertNull(tx.sample(100, 0).queueDelayMs)
        assertFalse("no reports yet is not the same as reports stopping", tx.sample(100, 0).reportsStale)
        tx.onReport(ReceiverReport(0, 0, 0, queueDelayMs = 450, lostFrames = 0), nowMs = 200)
        assertEquals(450, tx.sample(1_000, 0).queueDelayMs)
        val later = tx.sample(5_000, 0)
        assertNull(later.queueDelayMs)
        assertTrue(later.reportsStale)
    }

    @Test
    fun framesTheHelperStillHasNotHadAfterAWhileMeanTheLinkHasStalled() {
        val tx = SendTracker()
        for (seq in 0..20) tx.onSent(seq, nowMs = seq * 100L, bytes = 100)
        // Reports keep coming, but they still only know about frame 3, sent at 300ms.
        tx.onReport(ReceiverReport(3, 300, 0, 0, 0), nowMs = 2_000)
        assertFalse(tx.sample(2_000, 0).stalled)
        tx.onReport(ReceiverReport(3, 300, 2_000, 0, 0), nowMs = 3_500)
        assertTrue("frame 4 went out 3.1s ago and still has not arrived", tx.sample(3_500, 0).stalled)
        tx.onReport(ReceiverReport(20, 2_000, 0, 0, 0), nowMs = 3_600)
        assertFalse("everything sent has arrived", tx.sample(3_600, 0).stalled)
    }

    @Test
    fun thePathIsClearUntilAFrameGoesUnconfirmedForMuchLongerThanARoundTrip() {
        val tx = SendTracker()
        assertTrue("nothing sent, nothing outstanding", tx.pathClear(bitrate = 1_000_000, nowMs = 0))
        for (seq in 0..9) tx.onSent(seq, nowMs = seq * 100L, bytes = 100)
        assertTrue("before any report, a cautious guess at the round trip", tx.pathClear(bitrate = 1_000_000, nowMs = 1_500))
        assertFalse("frame 0 unconfirmed for 1.6s, before any report at all", tx.pathClear(bitrate = 1_000_000, nowMs = 1_600))
        tx.onReport(ReceiverReport(highestSeq = 4, echoSentAt = 400, holdMs = 0, queueDelayMs = 0, lostFrames = 0), nowMs = 700)
        assertEquals(300, tx.rttMs)
        assertTrue(tx.pathClear(bitrate = 1_000_000, nowMs = 1_000)) // frame 5, sent at 500, has been out 0.5s
        assertTrue(tx.pathClear(bitrate = 1_000_000, nowMs = 1_600)) // 1.1s: exactly a quiet round trip plus the slack
        assertFalse(tx.pathClear(bitrate = 1_000_000, nowMs = 1_700))
        tx.onReport(ReceiverReport(9, 900, 0, 0, 0), nowMs = 1_750)
        assertTrue("everything sent has arrived", tx.pathClear(bitrate = 1_000_000, nowMs = 1_750))
    }

    @Test
    fun aBurstOfBytesClosesThePathBeforeAnyFrameLooksLate() {
        val tx = SendTracker()
        tx.onSent(0, nowMs = 0, bytes = 1_000)
        tx.onReport(ReceiverReport(0, 0, 0, 0, 0), nowMs = 300) // round trip 300ms: a 1.1s window
        // At 240 kbps (30KB/s), 1.1s is 33KB; the minimum is 32KB, so 33KB is the budget.
        for (seq in 1..6) tx.onSent(seq, nowMs = 300L + seq * 10, bytes = 5_000)
        assertEquals(30_000, tx.unconfirmedBytes())
        assertTrue(tx.pathClear(nowMs = 400, bitrate = 240_000))
        tx.onSent(7, nowMs = 370, bytes = 5_000)
        assertFalse("35KB out after 70ms: nothing looks late yet, but it is too much", tx.pathClear(nowMs = 400, bitrate = 240_000))
        tx.onReport(ReceiverReport(5, 350, 0, 0, 0), nowMs = 700)
        assertEquals(10_000, tx.unconfirmedBytes())
        assertTrue(tx.pathClear(nowMs = 700, bitrate = 240_000))
    }

    @Test
    fun framesThatWillNeverArriveAreGivenUpOnRatherThanHoldingThePictureBackForever() {
        val tx = SendTracker()
        for (seq in 0..4) tx.onSent(seq, nowMs = seq * 100L, bytes = 1_000)
        tx.onReport(ReceiverReport(highestSeq = 2, echoSentAt = 200, holdMs = 0, queueDelayMs = 0, lostFrames = 0), nowMs = 500)
        // 3 and 4 were lost with a dropped connection: reports keep coming, but never mention them.
        tx.onReport(ReceiverReport(2, 200, 2_000, 0, 0), nowMs = 2_500)
        tx.giveUpOnLostFrames(2_500)
        assertFalse(tx.pathClear(nowMs = 2_500, bitrate = 1_000_000))
        tx.giveUpOnLostFrames(3_600)
        assertTrue("given up on after 3s with no progress", tx.pathClear(nowMs = 3_600, bitrate = 1_000_000))
        assertEquals(0, tx.unconfirmedBytes())
        tx.onSent(5, nowMs = 3_700, bytes = 1_000)
        assertEquals("new frames count as normal again", 1_000, tx.unconfirmedBytes())
    }

    @Test
    fun aVerySlowButMovingLinkIsNotMistakenForLostFrames() {
        val tx = SendTracker()
        for (seq in 0..20) tx.onSent(seq, nowMs = seq * 100L, bytes = 1_000)
        for (i in 1..10) {
            tx.onReport(ReceiverReport(highestSeq = i, echoSentAt = i * 100, holdMs = 0, queueDelayMs = 0, lostFrames = 0), nowMs = i * 1_000L)
            tx.giveUpOnLostFrames(i * 1_000L)
        }
        assertEquals("still ten frames outstanding, still counted", 10_000, tx.unconfirmedBytes())
    }

    @Test
    fun aReconnectSettlesEverythingSentBeforeIt() {
        val tx = SendTracker()
        for (seq in 0..9) tx.onSent(seq, nowMs = seq * 100L, bytes = 1_000)
        tx.onReconnected(nowMs = 1_000)
        assertEquals(0, tx.unconfirmedBytes())
        assertTrue(tx.pathClear(nowMs = 5_000, bitrate = 1_000_000))
    }

    @Test
    fun evenAFirstFrameThatNeverArrivesIsEventuallyGivenUpOn() {
        val tx = SendTracker()
        tx.onSent(0, nowMs = 0, bytes = 50_000)
        tx.giveUpOnLostFrames(2_000)
        assertFalse(tx.pathClear(nowMs = 2_000, bitrate = 1_000_000))
        tx.giveUpOnLostFrames(3_100)
        assertTrue(tx.pathClear(nowMs = 3_100, bitrate = 1_000_000))
    }
}
