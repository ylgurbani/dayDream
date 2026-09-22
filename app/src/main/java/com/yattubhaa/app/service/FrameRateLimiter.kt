package com.yattubhaa.app.service

/**
 * Caps how often delta (non-keyframe) chunks are forwarded; keyframes always go through
 * regardless of timing. Exists because a surface-input encoder has no throttle of its own:
 * `MediaFormat.KEY_FRAME_RATE` is only a hint used for bitrate math, not an actual limit — the
 * encoder processes every frame the compositor draws to its input surface, however often that
 * turns out to be. Most of the time that is fine (a phone screen is mostly still), but real-world
 * testing found a genuine case where it is not: the pointer ring's own pulsing animation is drawn
 * on his screen, so it is captured too, and while it is on, the compositor can be redrawing at
 * the display's full refresh rate — potentially far more video chunks per second than the relay's
 * own per-connection rate limit allows, getting the whole connection closed for "rate limit" the
 * moment a helper points at something. This bounds the output rate well under that limit,
 * independent of whatever the actual screen is doing.
 *
 * Dropping an excess delta frame can leave the picture briefly corrupted on the other end, the
 * same tradeoff [com.yattubhaa.app.session.NeedySession.sendVideoChunk]'s own backlog-based
 * dropping already accepts — it self-heals at the next keyframe, which is never held back here.
 *
 * Pure and tiny enough to test directly: fed a monotonic clock reading and whether a given chunk
 * is a keyframe, it decides whether to forward it — always true for a keyframe, which (like a
 * forwarded delta frame) also resets the clock, so the very next delta frame is timed from the
 * keyframe's own arrival rather than from whatever delta frame happened to precede it.
 */
class FrameRateLimiter(private val minIntervalMs: Long) {
    private var lastForwardedAt = -minIntervalMs // so the very first chunk is never held back

    fun shouldForward(nowMs: Long, isKeyFrame: Boolean): Boolean {
        if (!isKeyFrame && nowMs - lastForwardedAt < minIntervalMs) return false
        lastForwardedAt = nowMs
        return true
    }
}
