package com.yattubhaa.app.service

/**
 * Keeps the picture flowing at the rate the connection can actually carry, on the phone sharing
 * its screen, without ever sending a frame the helper could not decode.
 *
 * Every frame except a keyframe only describes what changed since the frame before it, so the
 * helper's decoder can only use it if it got every frame since the last keyframe. Two earlier
 * versions of this app got that wrong in turn:
 *  - The first threw away single encoded frames (to protect a slow connection, and separately to
 *    cap the frame rate) and carried on sending the ones after. Each of those was then decoded
 *    against the wrong picture: exactly the smearing, tearing and blockiness a real long-distance
 *    test showed.
 *  - The next held back everything after a thrown-away frame until a fresh keyframe — correct, but
 *    on a throttled test link it meant a new keyframe every few seconds, each one many times the
 *    size of an ordinary frame, congesting the link all over again.
 *
 * So now nothing already encoded is thrown away in the normal course of things. When the link is
 * backed up — this phone's own queue is deep, or frames already sent are not reaching the helper
 * in reasonable time (`pathClear`, from [SendTracker], which catches queues this phone cannot
 * see) — the *source* is paused instead ([setSourcePaused]): the capture skips frames before they
 * are ever encoded. When the link clears, capture resumes with an ordinary frame built on the last
 * one sent; the chain is never broken, so no keyframe is needed. Only if the queue somehow grows
 * far past that (or the connection refuses a send outright) is a frame dropped — and then, as
 * before, everything until a fresh keyframe with it.
 *
 * Pure, so it is tested directly.
 */
class SendGate(
    private val requestKeyframe: () -> Unit,
    private val setSourcePaused: (Boolean) -> Unit,
) {
    /** True from the first dropped frame until a keyframe actually goes out. */
    var awaitingKeyframe = false
        private set
    var sourcePaused = false
        private set
    private var lastRequestAt: Long? = null

    /** For every frame as it comes out of the encoder. Returns whether to send it. */
    fun shouldSend(keyframe: Boolean, backlogBytes: Long, bitrate: Int, pathClear: Boolean, nowMs: Long): Boolean {
        check(backlogBytes, bitrate, pathClear, nowMs)
        // Frames already encoded when the source was paused still go out: dropping them is what
        // would break the chain. Only a queue far past the pause point drops anything.
        val room = backlogBytes < dropLimit(bitrate)
        if (keyframe) {
            awaitingKeyframe = !room
            return room
        }
        if (!awaitingKeyframe && room) return true
        awaitingKeyframe = true
        return false
    }

    /**
     * Re-judges the link between frames too, several times a second: while the source is paused
     * no frames come out to prompt it, and it must resume as soon as there is room.
     */
    fun check(backlogBytes: Long, bitrate: Int, pathClear: Boolean, nowMs: Long) {
        val congested = !pathClear || backlogBytes >= pauseLimit(bitrate)
        if (congested != sourcePaused) {
            sourcePaused = congested
            setSourcePaused(congested)
        }
        // After a drop, ask for a keyframe once there is room to carry it, not before: one asked
        // for sooner would just be dropped too.
        if (awaitingKeyframe && !congested && lastRequestAt.let { it == null || nowMs - it >= REQUEST_INTERVAL_MS }) {
            lastRequestAt = nowMs
            requestKeyframe()
        }
    }

    /** The frame was allowed through, but the connection itself refused it (mid-reconnect, say). */
    fun onSendFailed() {
        awaitingKeyframe = true
    }

    companion object {
        const val REQUEST_INTERVAL_MS = 1000L

        /** Pause the source once half a second of picture is queued on this phone. */
        fun pauseLimit(bitrate: Int): Long = maxOf(16 * 1024L, bitrate / 8L / 2)

        /** Last resort: past three seconds of picture queued here, drop. */
        fun dropLimit(bitrate: Int): Long = maxOf(128 * 1024L, bitrate / 8L * 3)
    }
}

/**
 * Hardware encoders do not always hit the bitrate they are given — during scrolling, a test
 * encoder produced two to three times its target — and a controller that believes it asked for
 * 120 kbps while 350 goes out is steering blind. This measures what the encoder actually produced
 * (over the time it was not paused) against the target, and scales the number handed to the
 * encoder so the real output lands near the target. Never above the target itself: a still screen
 * producing far less than asked for is not a reason to ask for more. The same idea as WebRTC's
 * encoder bitrate adjuster. Pure, so it is tested directly.
 */
class OvershootCorrector {
    /** What to multiply the wanted bitrate by before handing it to the encoder; 0.3 to 1. */
    var factor = 1.0
        private set

    fun onSample(producedBytes: Long, activeMs: Long, targetBps: Int) {
        if (activeMs < MIN_ACTIVE_MS || targetBps <= 0 || producedBytes <= 0) return
        val actualBps = producedBytes * 8000.0 / activeMs
        val ideal = factor * targetBps / actualBps
        factor = (factor * (1 - SMOOTHING) + ideal * SMOOTHING).coerceIn(MIN_FACTOR, 1.0)
    }

    private companion object {
        const val MIN_ACTIVE_MS = 500L
        const val SMOOTHING = 0.3
        const val MIN_FACTOR = 0.3
    }
}
