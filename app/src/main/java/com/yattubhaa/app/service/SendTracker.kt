package com.yattubhaa.app.service

import com.yattubhaa.app.net.Protocol

/**
 * The sharing phone's record of which frames it sent and when, matched against what the helper's
 * receiver reports say actually arrived. That match is the only way this phone can see congestion
 * that is not in its own queue: in the relay's buffer, or on the helper's own slow downlink. The
 * earlier version watched only its own queue, which is why it could report "Good" while the
 * helper's picture was seconds behind.
 *
 * It also decides whether there is room to send at all ([pathClear]). A throttled test showed
 * why this cannot be left to this phone's own queue: that queue stayed small while over a
 * megabyte sat in the operating system's network buffers beneath it — the "bufferbloat" real
 * mobile networks have too — and the helper's picture fell 25 seconds behind. Capping how long
 * any frame may go unconfirmed caps that delay, wherever the buffers happen to be.
 *
 * Only ever compares this phone's clock with itself — the helper echoes back the send time it was
 * given — so the two phones' clocks never need to agree. Pure, so it is tested directly.
 */
class SendTracker {
    /** The number the next frame actually sent will carry. */
    var nextSeq = 0
        private set
    private val sentAt = LongArray(RING)
    /** Total bytes sent up to and including each frame, so what is still unconfirmed is a subtraction. */
    private val sentBytesThrough = LongArray(RING)
    private var sentBytes = 0L
    private var lastReport: Protocol.Message.ReceiverReport? = null
    private var lastReportAt = 0L
    /** Frames up to here are settled — arrived, or given up as lost — whatever the reports say. */
    private var settledThrough = -1
    /** When the helper last confirmed anything new (or frames were last given up on). */
    private var lastProgressAt: Long? = null

    /** Round trip through the relay and back, from the latest report; null until one arrives. */
    var rttMs: Int? = null
        private set
    private val minRtt = WindowedMin(MIN_RTT_WINDOW_MS)

    fun onSent(seq: Int, nowMs: Long, bytes: Int) {
        if (lastProgressAt == null) lastProgressAt = nowMs
        sentBytes += bytes
        sentAt[seq and (RING - 1)] = nowMs
        sentBytesThrough[seq and (RING - 1)] = sentBytes
        nextSeq = seq + 1
    }

    fun onReport(report: Protocol.Message.ReceiverReport, nowMs: Long) {
        if (report.highestSeq - confirmedThrough() > 0) lastProgressAt = nowMs
        lastReport = report
        lastReportAt = nowMs
        // The helper says how long it sat on this frame before reporting; take that out, and
        // what is left is the time there and back.
        val rtt = (nowMs.toInt() - report.echoSentAt) - report.holdMs
        if (rtt in 0..MAX_BELIEVABLE_RTT_MS) {
            rttMs = rtt
            minRtt.add(nowMs, rtt.toLong())
        }
    }

    /**
     * Whether new frames may go out. False once what the helper has not yet confirmed is more than
     * a quiet round trip's worth (plus the report interval, plus slack), measured two ways:
     *  - by time: the oldest unconfirmed frame has been on its way that long;
     *  - by bytes: more than that long's worth, at [bitrate], is on its way. Time alone was not
     *    enough: an encoder can burst far above its target the moment scrolling starts (a test
     *    encoder produced four times its target), and in the time it took the oldest frame to
     *    look late, six seconds of the link's capacity had already been sent.
     * Anything sent past either point would only join a queue the helper is already seconds
     * behind. Applies from the very first frame — before any report has come back, the round trip
     * is assumed to be a cautious [RTT_GUESS_MS] — because the start of a session, a large
     * keyframe at a bitrate not yet fitted to the link, is exactly when a slow link is swamped.
     */
    fun pathClear(nowMs: Long, bitrate: Int): Boolean {
        val windowMs = (minRtt.min(nowMs) ?: RTT_GUESS_MS) + IN_FLIGHT_SLACK_MS
        val byteBudget = maxOf(MIN_IN_FLIGHT_BYTES, bitrate / 8L * windowMs / 1000)
        return unconfirmedAgeMs(nowMs) <= windowMs && unconfirmedBytes() <= byteBudget
    }

    /** Bytes sent that the helper has not yet confirmed. */
    fun unconfirmedBytes(): Long {
        val confirmed = confirmedThrough()
        if (confirmed < 0) return sentBytes
        val outstanding = nextSeq - (confirmed + 1)
        if (outstanding <= 0) return 0
        if (outstanding >= RING) return sentBytes
        return sentBytes - sentBytesThrough[confirmed and (RING - 1)]
    }

    /**
     * Frames that will never be confirmed must not hold the picture back forever. Found by
     * restarting the relay mid-session: the frames in flight when it went down were simply gone,
     * the helper could never confirm them, and capture stayed paused waiting for them — no picture
     * again, ever, though the connection itself had recovered. So: once nothing new has been
     * confirmed for [GIVE_UP_MS] with frames still outstanding, those frames are taken as lost.
     * Nothing else is needed to repair the picture: the next frame's number jumps, the helper
     * sees the gap, and it asks for a keyframe. Called several times a second.
     */
    fun giveUpOnLostFrames(nowMs: Long) {
        val since = lastProgressAt ?: return
        if (nextSeq - 1 - confirmedThrough() > 0 && nowMs - since > GIVE_UP_MS) settleAllSent(nowMs)
    }

    /** The connection dropped and came back: anything sent before now is as good as settled. */
    fun onReconnected(nowMs: Long) = settleAllSent(nowMs)

    private fun settleAllSent(nowMs: Long) {
        settledThrough = nextSeq - 1
        lastProgressAt = nowMs
    }

    private fun confirmedThrough(): Int {
        val reported = lastReport?.highestSeq ?: -1
        return if (settledThrough - reported > 0) settledThrough else reported
    }

    /** How long the oldest frame the helper has not confirmed has been on its way; 0 if none. */
    fun unconfirmedAgeMs(nowMs: Long): Long {
        val oldest = confirmedThrough() + 1
        val outstanding = nextSeq - oldest
        if (outstanding <= 0 || outstanding >= RING) return 0
        return nowMs - sentAt[oldest and (RING - 1)]
    }

    fun sample(nowMs: Long, localBacklogBytes: Long): CongestionController.Sample {
        val report = lastReport
        val reportAge = report?.let { nowMs - lastReportAt }
        val fresh = reportAge != null && reportAge <= STALE_REPORT_MS
        return CongestionController.Sample(
            nowMs = nowMs,
            localBacklogBytes = localBacklogBytes,
            queueDelayMs = if (fresh) report?.queueDelayMs else null,
            reportsStale = reportAge != null && !fresh,
            stalled = fresh && unconfirmedAgeMs(nowMs) > STALL_MS,
            sentSeq = nextSeq - 1,
            confirmedSeq = report?.highestSeq,
        )
    }

    private companion object {
        const val RING = 4096 // minutes of frames at any frame rate used here; a power of two
        const val STALE_REPORT_MS = 3000L // reports are sent four times a second
        // Frames the helper, though still reporting, has not had for this long mean the link has
        // stopped delivering: its reports can only describe frames that actually arrived, so
        // without this a stalled link would look frozen at its last (fine) reading.
        const val STALL_MS = 2500L
        const val MAX_BELIEVABLE_RTT_MS = 60_000
        const val MIN_RTT_WINDOW_MS = 30_000L
        // Covers the report interval (250ms) and ordinary jitter, with room to spare: on a link
        // that is keeping up this never comes into play. Everything sent within this window at the
        // old rate still has to drain if the link suddenly slows, so it is kept tight: tried at
        // 1.2s first, a sudden drop from 2.5 Mbps to 400 kbps still left 12 seconds of backlog.
        const val IN_FLIGHT_SLACK_MS = 800L
        const val RTT_GUESS_MS = 700L
        // Room for one keyframe at the smallest size, whatever the bitrate.
        const val MIN_IN_FLIGHT_BYTES = 32 * 1024L
        // Nothing new confirmed for this long, with frames still out: they are not coming. Even a
        // badly congested link still delivers something within this; a lost frame never does.
        const val GIVE_UP_MS = 3000L
    }
}
