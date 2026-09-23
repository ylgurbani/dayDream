package com.yattubhaa.app.service

import com.yattubhaa.app.net.ConnectionQuality

/**
 * Adjusts the video bitrate to fit the connection, the same idea TCP congestion control uses:
 * step down fast when the outgoing queue is backing up (the connection cannot keep up with the
 * current rate), step up slowly once it has stayed comfortably empty for a while (there may be
 * room for a clearer picture). Deliberately leaves resolution and frame rate alone — changing
 * those means tearing down and recreating the encoder, the virtual display, and the helper's
 * decoder, a lot more moving parts for a smaller and riskier gain than simply asking the same
 * running encoder for fewer (or more) bits per frame, which `MediaCodec` supports live.
 *
 * Pure logic, no Android dependencies, so it is tested directly: fed a sequence of backlog
 * samples (see [onSample]), it decides the bitrate and reports it via [onBitrateChanged], and
 * how well the connection seems to be coping via [onQualityChanged] — both called only when they
 * actually change, not on every sample.
 */
class BitrateAdapter(
    private val onBitrateChanged: (Int) -> Unit,
    private val onQualityChanged: (ConnectionQuality) -> Unit,
) {
    var bitrate = START_BITRATE
        private set
    var quality = ConnectionQuality.Good
        private set
    private var goodStreak = 0

    /** Call periodically with how many bytes of picture are queued but not yet actually sent. */
    fun onSample(backlogBytes: Long) {
        when {
            backlogBytes > CONGESTED_BYTES -> {
                goodStreak = 0
                setBitrate((bitrate * DECREASE_FACTOR).toInt().coerceAtLeast(MIN_BITRATE))
            }
            backlogBytes == 0L -> {
                goodStreak++
                if (goodStreak >= GOOD_SAMPLES_TO_INCREASE) {
                    goodStreak = 0
                    setBitrate((bitrate + INCREASE_STEP).coerceAtMost(MAX_BITRATE))
                }
            }
            else -> goodStreak = 0 // some backlog, but not congested: hold steady rather than guess
        }
        setQuality(
            when {
                backlogBytes > CONGESTED_BYTES || bitrate <= MIN_BITRATE -> ConnectionQuality.Poor
                bitrate < MAX_BITRATE -> ConnectionQuality.Fair
                else -> ConnectionQuality.Good
            },
        )
    }

    private fun setBitrate(next: Int) {
        if (next == bitrate) return
        bitrate = next
        onBitrateChanged(next)
    }

    private fun setQuality(next: ConnectionQuality) {
        if (next == quality) return
        quality = next
        onQualityChanged(next)
    }

    private companion object {
        const val START_BITRATE = 1_600_000
        // Lowered from an earlier 400kbps after a real test — two phones genuinely on opposite
        // sides of the world, one on a slow mobile connection — showed visible tearing and
        // pixelation even once the adapter had reached its old floor: that floor still was not
        // low enough for a genuinely constrained real link. Still enough for legible text and
        // icons, the actual point of this app, even if motion looks rough at the bottom of the
        // range.
        const val MIN_BITRATE = 250_000
        const val MAX_BITRATE = 2_500_000
        // Halves on congestion rather than the earlier, gentler 0.7x — ordinary TCP-style AIMD,
        // and found to matter in practice: the gentler factor took roughly five congested samples
        // (about ten seconds, at how often this is sampled) to reach even the old floor, ten
        // seconds of a connection that was already struggling being asked for more than it could
        // carry. Recovery afterwards stays cautious and slow on purpose (see onSample) — this
        // only changes how fast it backs off, not how fast it climbs back.
        const val DECREASE_FACTOR = 0.5
        const val INCREASE_STEP = 200_000
        // Deliberately well under NeedySession's own MAX_BACKLOG_BYTES (the point past which a
        // delta frame is dropped outright): this should back the bitrate off in time to avoid
        // most of those drops actually happening, not just notice congestion once they already
        // are.
        const val CONGESTED_BYTES = 64_000L
        const val GOOD_SAMPLES_TO_INCREASE = 3
    }
}
