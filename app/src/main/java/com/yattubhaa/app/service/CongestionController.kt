package com.yattubhaa.app.service

import com.yattubhaa.app.net.ConnectionQuality

/** One rung of the quality ladder: the picture's shorter side in pixels, the most frames per
 *  second the encoder is given, and the bitrate range used at that size. */
data class QualityTier(val shortSide: Int, val maxFps: Int, val minBitrate: Int, val maxBitrate: Int)

/**
 * Fits the picture to the connection, on the phone sharing its screen. Replaces an earlier
 * version that watched nothing but this phone's own outgoing queue, and so could not see a queue
 * building up anywhere else — in the relay, or on the helper's own slow mobile downlink — which
 * is how a real long-distance test showed "Good" while the picture ran seconds behind.
 *
 * It now works from what the helper says is actually arriving ([SendTracker] turns the helper's
 * receiver reports into a [Sample]), and backs off the way TCP does: halve at once when the
 * picture is queueing up anywhere along the way, climb back slowly once it has stayed clear —
 * and, also like TCP, only once per round of evidence. After a cut, the queue built up before it
 * still has to drain, and until the helper confirms frames sent *after* the cut, what it reports
 * is still the old rate's doing. Found on a throttled test link: without this, one burst of
 * congestion walked the picture all the way down to the smallest size in five seconds, on a link
 * that could comfortably carry the middle one.
 *
 * Bitrate is only half of it. Below a certain bitrate a full-size picture at a full frame rate
 * just turns to mush, so once the bitrate is at the bottom of its range and the connection still
 * cannot keep up, it steps down to a smaller picture at a lower frame rate — fewer, crisper frames,
 * which for reading text on his screen beats many blurry ones — and back up once there is room.
 * A step means restarting the encoder (and a new keyframe), so stepping is deliberately slower and
 * more reluctant than changing the bitrate, and a step up that is quickly regretted makes the
 * next one wait longer.
 *
 * Pure, so it is tested directly: fed a sequence of samples, it reports changes through
 * [onBitrateChanged] and [onTierChanged], only when something actually changes.
 */
class CongestionController(
    private val onBitrateChanged: (Int) -> Unit,
    private val onTierChanged: (Int) -> Unit,
) {
    /**
     * One reading, about once a second. [queueDelayMs] is the helper's latest measurement (null
     * when there is no fresh report); [reportsStale] means reports were arriving but have
     * stopped; [stalled] means frames went out long ago that the helper still has not had;
     * [pausedFraction] is how much of the last second capture was held back (see SendGate), and
     * [keyframeSent] whether a keyframe went out in it.
     */
    data class Sample(
        val nowMs: Long,
        val localBacklogBytes: Long,
        val queueDelayMs: Int?,
        val reportsStale: Boolean,
        val stalled: Boolean,
        /** The newest frame sent so far, and the newest the helper has confirmed (null if none). */
        val sentSeq: Int,
        val confirmedSeq: Int?,
        val pausedFraction: Double = 0.0,
        val keyframeSent: Boolean = false,
    )

    var tierIndex = 0
        private set
    val tier: QualityTier get() = TIERS[tierIndex]
    var bitrate = START_BITRATE
        private set
    var quality = ConnectionQuality.Good
        private set

    private var goodStreak = 0
    private var floorStreak = 0
    private var previousBacklog: Long? = null
    private var previousDelay: Int? = null
    private var lastTierChangeAt: Long? = null
    private var lastStepUpAt: Long? = null
    private var stepUpHoldMs = STEP_UP_HOLD_MS
    /** Set by a cut: the last frame sent before it. Until the helper confirms something newer,
     *  congestion it reports is the old rate's, already acted on. */
    private var cutAtSeq: Int? = null
    private var cutAt = 0L

    fun onSample(s: Sample) {
        val bytesPerSecond = bitrate / 8L
        val localCongested = s.localBacklogBytes > maxOf(MIN_CONGESTED_BYTES, bytesPerSecond / 2)
        // A queue already shrinking after the last cut just needs time to drain; cutting again for
        // what is really the previous sample's congestion would overshoot all the way to the floor.
        val localDraining = previousBacklog?.let { s.localBacklogBytes < it * 3 / 4 } ?: false
        val delay = s.queueDelayMs
        val delayHigh = delay != null && delay > HIGH_DELAY_MS
        val delayDraining = delay != null && previousDelay?.let { delay < it - DRAIN_MARGIN_MS } ?: false
        // Capture held back for most of the last second: the link cannot carry the picture at this
        // size and rate, whatever the delay readings say — found on a throttled link where, at
        // the middle size, only two frames a second were getting out for seventeen seconds while
        // this waited for delay evidence that paused capture was barely producing. A second with
        // a keyframe in it does not count: a keyframe always holds things up briefly.
        val starved = s.pausedFraction > STARVED_FRACTION && !s.keyframeSent
        val congested = s.stalled || s.reportsStale || starved ||
            (localCongested && !localDraining) || (delayHigh && !delayDraining)
        // Clear means room to try for more: nothing queueing anywhere, and capture not being held
        // back either (a link that is already pausing the picture is no place to push harder).
        val clear = !s.stalled && !s.reportsStale && s.pausedFraction <= CLEAR_PAUSED_FRACTION &&
            s.localBacklogBytes <= bytesPerSecond / 10 && (delay ?: 0) < LOW_DELAY_MS
        previousBacklog = s.localBacklogBytes
        previousDelay = delay
        val waitingOnLastCut = cutAtSeq?.let { seq ->
            val confirmedSinceCut = s.confirmedSeq != null && s.confirmedSeq - seq > 0
            // Starving with everything from before the cut already arrived is the new rate's
            // doing, not the old backlog's: no need to wait for a post-cut frame to prove it.
            val backlogCleared = s.confirmedSeq != null && s.confirmedSeq - seq >= 0
            !(confirmedSinceCut || (starved && backlogCleared)) && s.nowMs - cutAt < CUT_SETTLE_TIMEOUT_MS
        } ?: false
        if (!waitingOnLastCut) cutAtSeq = null

        when {
            congested && waitingOnLastCut -> goodStreak = 0
            congested -> {
                goodStreak = 0
                if (backOff(s.nowMs)) {
                    cutAtSeq = s.sentSeq
                    cutAt = s.nowMs
                }
            }
            clear -> {
                floorStreak = 0
                if (++goodStreak >= GOOD_SAMPLES_TO_INCREASE) {
                    goodStreak = 0
                    probeUp(s.nowMs)
                }
            }
            else -> { // not congested, not clear either: hold steady rather than guess
                goodStreak = 0
                floorStreak = 0
            }
        }
        quality = when {
            congested || (tierIndex == TIERS.lastIndex && bitrate <= tier.minBitrate) -> ConnectionQuality.Poor
            tierIndex > 0 || !clear -> ConnectionQuality.Fair
            else -> ConnectionQuality.Good
        }
    }

    /** Returns whether anything was actually cut. */
    private fun backOff(nowMs: Long): Boolean {
        val current = tier
        if (bitrate > current.minBitrate) {
            floorStreak = 0
            setBitrate((bitrate * DECREASE_FACTOR).toInt().coerceAtLeast(current.minBitrate))
            return true
        }
        if (tierIndex == TIERS.lastIndex) return false // already as small and slow as it goes
        if (++floorStreak < FLOOR_SAMPLES_TO_STEP_DOWN) return false
        floorStreak = 0
        // Stepped up not long ago and it did not hold: wait longer before trying again.
        if (lastStepUpAt?.let { nowMs - it < QUICK_RETREAT_MS } == true) {
            stepUpHoldMs = (stepUpHoldMs * 2).coerceAtMost(MAX_STEP_UP_HOLD_MS)
        }
        tierIndex++
        lastTierChangeAt = nowMs
        setBitrate((bitrate * DECREASE_FACTOR).toInt().coerceIn(tier.minBitrate, tier.maxBitrate))
        onTierChanged(tierIndex)
        return true
    }

    private fun probeUp(nowMs: Long) {
        val current = tier
        if (bitrate < current.maxBitrate) {
            setBitrate(maxOf(bitrate + MIN_INCREASE, (bitrate * INCREASE_FACTOR).toInt()).coerceAtMost(current.maxBitrate))
            return
        }
        if (tierIndex == 0) return
        if (lastTierChangeAt?.let { nowMs - it < stepUpHoldMs } == true) return
        tierIndex--
        lastTierChangeAt = nowMs
        lastStepUpAt = nowMs
        setBitrate(bitrate.coerceIn(tier.minBitrate, tier.maxBitrate))
        onTierChanged(tierIndex)
    }

    private fun setBitrate(next: Int) {
        if (next == bitrate) return
        bitrate = next
        onBitrateChanged(next)
    }

    companion object {
        /** Largest first. Heights follow the phone's own shape; see ScreenShareService. */
        val TIERS = listOf(
            QualityTier(shortSide = 720, maxFps = 20, minBitrate = 500_000, maxBitrate = 2_500_000),
            QualityTier(shortSide = 544, maxFps = 15, minBitrate = 250_000, maxBitrate = 1_000_000),
            // Its top is kept low: stepping back up needs the current size's top bitrate to get
            // through cleanly, and at 450k a 400 kbps link could never leave this size, though the
            // middle one suits it better.
            QualityTier(shortSide = 432, maxFps = 10, minBitrate = 120_000, maxBitrate = 320_000),
        )

        // Lower than the earlier 1.6 Mbps: on a bad link the first seconds, before any report has
        // come back, were being spent pushing far more than it could carry.
        const val START_BITRATE = 1_000_000
        const val DECREASE_FACTOR = 0.5
        const val INCREASE_FACTOR = 1.15
        const val MIN_INCREASE = 50_000
        const val GOOD_SAMPLES_TO_INCREASE = 2
        // Queueing delay the helper measured, above the quietest this connection has been lately.
        // Generous enough for an ordinary VPN's jitter; far below "seconds behind".
        const val HIGH_DELAY_MS = 400
        const val LOW_DELAY_MS = 150
        const val DRAIN_MARGIN_MS = 100
        const val MIN_CONGESTED_BYTES = 16 * 1024L
        const val FLOOR_SAMPLES_TO_STEP_DOWN = 2
        const val STEP_UP_HOLD_MS = 20_000L
        const val MAX_STEP_UP_HOLD_MS = 300_000L
        const val QUICK_RETREAT_MS = 30_000L
        // How long to wait for the helper to confirm a frame sent after a cut before judging
        // again regardless: long enough for a slow link's backlog to drain, short enough that a
        // link that got even worse is not left waiting.
        const val CUT_SETTLE_TIMEOUT_MS = 4000L
        const val STARVED_FRACTION = 0.6
        const val CLEAR_PAUSED_FRACTION = 0.2
    }
}
