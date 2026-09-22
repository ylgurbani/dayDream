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
        const val MIN_BITRATE = 400_000
        const val MAX_BITRATE = 2_500_000
        const val DECREASE_FACTOR = 0.7
        const val INCREASE_STEP = 200_000
        // Roughly one keyframe's worth queued and not yet out the door: a real sign the link
        // cannot keep up right now, not just an ordinarily large frame passing through.
        const val CONGESTED_BYTES = 150_000L
        const val GOOD_SAMPLES_TO_INCREASE = 3
    }
}
