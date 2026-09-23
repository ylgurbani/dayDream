package com.yattubhaa.app.service

import com.yattubhaa.app.net.Protocol
import kotlin.math.abs

/**
 * The helper's side of [SendTracker]: notices frames that went missing on the way (so the decoder
 * can wait for a keyframe instead of decoding garbage), and measures how much extra delay the
 * picture is picking up in queues along the way, for the receiver report sent back every half
 * second.
 *
 * The queueing delay is measured without the two phones' clocks ever having to agree: for each
 * frame, (arrival time here) - (send time there) is the real transit time plus some unknown but
 * constant clock offset. The lowest such value seen recently is as close to "no queue at all" as
 * this connection gets, so how far the latest frame is above it is how long it spent waiting in
 * queues — the same idea WebRTC's congestion control is built on.
 *
 * Not thread-safe; its owner synchronises. Pure, so it is tested directly.
 */
class ReceiveTracker {
    private var lastSeq: Int? = null
    private var lastSentAt = 0
    private var lastArrivedAt = 0L
    private var latestTransit = 0L
    private val baseline = WindowedMin(BASELINE_WINDOW_MS)

    var lostFrames = 0
        private set
    var frames = 0L
        private set
    var bytes = 0L
        private set
    var keyframes = 0L
        private set

    /** Returns true if something went missing just before this frame, so it (and everything after
     *  it until the next keyframe) cannot be decoded correctly. */
    fun onFrame(seq: Int, sentAt: Int, keyframe: Boolean, size: Int, nowMs: Long): Boolean {
        val previous = lastSeq
        val step = if (previous == null) 1 else seq - previous
        if (step > 1) lostFrames += step - 1
        lastSeq = seq
        lastSentAt = sentAt
        lastArrivedAt = nowMs
        frames++
        bytes += size
        if (keyframe) keyframes++

        val transit = nowMs - (sentAt.toLong() and 0xFFFFFFFFL)
        // The sender's 32-bit millisecond clock wraps every ~49 days; start the baseline afresh
        // rather than compare readings from either side of that.
        if (frames > 1 && abs(transit - latestTransit) > CLOCK_JUMP_MS) baseline.clear()
        latestTransit = transit
        baseline.add(nowMs, transit)
        return step != 1
    }

    /** Null until the first frame has arrived: there is nothing to report on before then. */
    fun report(nowMs: Long): Protocol.Message.ReceiverReport? {
        val seq = lastSeq ?: return null
        val floor = baseline.min(nowMs) ?: latestTransit
        return Protocol.Message.ReceiverReport(
            highestSeq = seq,
            echoSentAt = lastSentAt,
            holdMs = (nowMs - lastArrivedAt).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            queueDelayMs = (latestTransit - floor).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            lostFrames = lostFrames,
        )
    }

    private companion object {
        // Long enough to find a quiet moment to measure against even on a busy connection; short
        // enough that a route that genuinely got slower is not blamed on queues for long.
        const val BASELINE_WINDOW_MS = 30_000L
        const val CLOCK_JUMP_MS = 3_600_000L
    }
}

/** The minimum of values added over the last [windowMs], in amortised constant time. */
class WindowedMin(private val windowMs: Long) {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Long>()

    fun add(nowMs: Long, value: Long) {
        while (values.isNotEmpty() && values.last() >= value) {
            values.removeLast(); times.removeLast()
        }
        values.addLast(value); times.addLast(nowMs)
        expire(nowMs)
    }

    fun min(nowMs: Long): Long? {
        expire(nowMs)
        return values.firstOrNull()
    }

    fun clear() {
        times.clear(); values.clear()
    }

    private fun expire(nowMs: Long) {
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) {
            times.removeFirst(); values.removeFirst()
        }
    }
}
