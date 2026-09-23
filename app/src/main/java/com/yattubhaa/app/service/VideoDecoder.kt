package com.yattubhaa.app.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.yattubhaa.app.net.VideoCodec

/**
 * The helper side of [ScreenEncoder]: turns the frames arriving over the wire back into a
 * picture, decoded straight onto a [Surface] (a `SurfaceView`'s) by the phone's video hardware.
 *
 * The rule everything here follows: a frame other than a keyframe is only fed to the decoder if
 * every frame since the last keyframe was too. So after anything goes missing — a gap in the
 * frame numbers ([submit]'s `discontinuity`), a fresh or restarted codec, a codec error — it
 * waits for the next keyframe, keeps showing the last correct picture meanwhile, and asks the
 * other phone for a keyframe via [onNeedKeyframe] (at most once a second while still waiting).
 * The first version of this class fed a fresh codec whatever arrived first and decoded nothing,
 * ever; the version before this one ignored codec errors, so one error meant no picture for the
 * rest of the session.
 *
 * Frames that arrive before the surface exists (the helper's screen is still being laid out
 * when the very first keyframe lands) are held rather than dropped, from the latest keyframe
 * onwards, and fed in the moment the surface appears — so the first picture no longer waits a
 * whole round trip for another keyframe.
 *
 * Every public method is safe to call from any thread: the work always runs on [handler], the
 * same thread the codec's own callbacks arrive on, so nothing here needs its own locking.
 */
class VideoDecoder(private val handler: Handler, private val onNeedKeyframe: () -> Unit) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var mime: String? = null
    private var width = 0
    private var height = 0
    private val freeInputBuffers = ArrayDeque<Int>()
    private val pending = ArrayDeque<ByteArray>()
    private val held = ArrayDeque<ByteArray>()
    private var heldBytes = 0
    private var nextPts = 0L
    /** True until a keyframe has been accepted, and again after anything breaks the chain. */
    private var needsKeyframe = true
    private var lastRequestAt: Long? = null
    private var shownSinceStart = false

    /** For the helper's stats overlay only; read from other threads, so only ever approximate. */
    @Volatile var keyframeRequests = 0
        private set
    @Volatile var codecRestarts = 0
        private set

    /** Called once the view holding the surface exists (or is recreated). */
    fun attachSurface(newSurface: Surface) {
        handler.post {
            surface = newSurface
            startIfPossible()
        }
    }

    /** The view was torn down; decoding stops until a new surface (and a fresh keyframe) arrive. */
    fun detachSurface() {
        handler.post {
            surface = null
            stopCodec()
        }
    }

    fun submit(codecType: VideoCodec, keyframe: Boolean, frameWidth: Int, frameHeight: Int, data: ByteArray, discontinuity: Boolean) {
        handler.post {
            if (frameWidth != width || frameHeight != height || codecType.mime != mime) {
                // A new size (rotation, a quality step) or codec: restart clean rather than feed a
                // codec configured for the old one.
                stopCodec()
                clearHeld()
                width = frameWidth
                height = frameHeight
                mime = codecType.mime
            }
            if (discontinuity) needsKeyframe = true
            if (needsKeyframe && !keyframe) {
                requestKeyframe()
                return@post
            }
            needsKeyframe = false
            if (codec == null) {
                // Held first, then started: a codec created now starts from what is held (this
                // keyframe, or the one this delta follows), not from nothing.
                hold(keyframe, data)
                startIfPossible()
                return@post
            }
            pending.addLast(data)
            if (pending.size > MAX_PENDING) {
                // The codec has stopped taking input; start over from a fresh keyframe.
                Log.w(TAG, "decoder stopped accepting input; restarting")
                restartCodec()
                return@post
            }
            drain()
        }
    }

    /** Full teardown: called once the session itself has ended, not just its view. */
    fun release() {
        handler.post {
            stopCodec()
            clearHeld()
            surface = null
            width = 0
            height = 0
        }
    }

    private fun hold(keyframe: Boolean, data: ByteArray) {
        if (keyframe) clearHeld()
        held.addLast(data)
        heldBytes += data.size
        if (held.size > MAX_HELD_FRAMES || heldBytes > MAX_HELD_BYTES) {
            clearHeld()
            needsKeyframe = true
        }
    }

    private fun clearHeld() {
        held.clear()
        heldBytes = 0
    }

    private fun requestKeyframe() {
        val now = SystemClock.elapsedRealtime()
        if (lastRequestAt?.let { now - it < REQUEST_INTERVAL_MS } == true) return
        lastRequestAt = now
        keyframeRequests++
        onNeedKeyframe()
    }

    private fun startIfPossible() {
        if (codec != null) return
        val s = surface ?: return
        val type = mime ?: return
        if (width <= 0 || height <= 0) return
        codec = createCodec(type, s)
        if (codec == null) return
        shownSinceStart = false
        if (held.isNotEmpty()) {
            // Everything since the latest keyframe, kept while there was nowhere to show it.
            pending.addAll(held)
            clearHeld()
            drain()
        } else {
            needsKeyframe = true
            requestKeyframe()
        }
    }

    private fun createCodec(type: String, s: Surface): MediaCodec? {
        val c = try {
            MediaCodec.createDecoderByType(type)
        } catch (e: Exception) {
            Log.w(TAG, "no decoder for $type", e)
            return null
        }
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                if (codec !== this@VideoDecoder.codec) return
                freeInputBuffers.addLast(index)
                drain()
            }
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (codec !== this@VideoDecoder.codec) return
                // More frames are already waiting to go in: this one would be on screen for a
                // moment at most, so go straight to the newest instead — after a stall, this is
                // the difference between replaying the backlog and jumping to the present.
                val render = info.size > 0 && pending.isEmpty()
                runCatching { codec.releaseOutputBuffer(index, render) }
                if (render && !shownSinceStart) {
                    shownSinceStart = true
                    Log.i(TAG, "first picture shown (${width}x$height)")
                }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                if (codec !== this@VideoDecoder.codec) return
                Log.w(TAG, "decoder error; restarting", e)
                restartCodec()
            }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, handler)
        for (lowLatency in listOf(true, false)) {
            try {
                c.configure(format(type, c, lowLatency), s, null, 0)
                c.start()
                return c
            } catch (e: Exception) {
                Log.w(TAG, "decoder rejected ${width}x$height (lowLatency=$lowLatency)", e)
                runCatching { c.reset() }
            }
        }
        runCatching { c.release() }
        return null // a bad surface or an unsupported size; the next keyframe will try again
    }

    private fun format(type: String, c: MediaCodec, lowLatency: Boolean) =
        MediaFormat.createVideoFormat(type, width, height).apply {
            if (!lowLatency) return@apply
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
            // Some decoders otherwise hold several frames back before showing the first one.
            val supported = Build.VERSION.SDK_INT >= 30 && runCatching {
                c.codecInfo.getCapabilitiesForType(type).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false)
            if (supported && Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

    private fun drain() {
        val c = codec ?: return
        while (freeInputBuffers.isNotEmpty() && pending.isNotEmpty()) {
            val index = freeInputBuffers.removeFirstOrNull() ?: break
            val chunk = pending.removeFirstOrNull() ?: break
            val ok = runCatching {
                val buffer = c.getInputBuffer(index) ?: return@runCatching false
                buffer.clear()
                buffer.put(chunk)
                c.queueInputBuffer(index, 0, chunk.size, nextPts, 0)
                nextPts += FRAME_PTS_STEP_US
                true
            }.getOrDefault(false)
            if (!ok) {
                restartCodec()
                return
            }
        }
    }

    /** Throw this codec instance away and start over from the next keyframe, asking for one now. */
    private fun restartCodec() {
        codecRestarts++
        stopCodec()
        startIfPossible()
    }

    private fun stopCodec() {
        freeInputBuffers.clear()
        pending.clear()
        codec?.let { c -> CodecReleaser.release { runCatching { c.stop() }; c.release() } }
        codec = null
        needsKeyframe = true
    }

    private companion object {
        const val TAG = "VideoDecoder"
        const val FRAME_PTS_STEP_US = 50_000L // only needs to keep increasing
        const val REQUEST_INTERVAL_MS = 1000L
        const val MAX_PENDING = 60
        // Ten seconds between the sender's periodic keyframes at up to 20 frames a second.
        const val MAX_HELD_FRAMES = 240
        const val MAX_HELD_BYTES = 6 * 1024 * 1024
    }
}
