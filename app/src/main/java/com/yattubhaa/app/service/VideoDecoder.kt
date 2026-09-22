package com.yattubhaa.app.service

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.view.Surface

/**
 * The helper side of [ScreenEncoder]: turns the H.264 chunks arriving over the wire back into a
 * picture, decoded straight onto a [Surface] (a `SurfaceView`'s) by the phone's video hardware —
 * no bitmap ever changes hands. [ScreenEncoder] prepends the SPS/PPS config to every keyframe it
 * sends, so nothing extra needs to be supplied here to start decoding from any of them.
 *
 * A delta chunk that arrives before the codec has ever been fed a keyframe is dropped — decoding
 * one first would just fail, since it only makes sense relative to a keyframe the codec has
 * already seen. This matters because the codec can come into existence (surface ready, size
 * known) independently of any particular chunk arriving, so without this guard the very first
 * chunk fed to a freshly created codec could easily be a delta frame with nothing to decode
 * against — a real bug found here, not a hypothetical one: it produced a codec that accepted
 * input forever and decoded nothing, ever. The sender resends its last keyframe every few
 * seconds on its own, so the wait for the next one is always short.
 *
 * Every public method is safe to call from any thread: the actual work always runs on
 * [handler], the same thread the codec's own callbacks arrive on, so nothing here needs its own
 * locking.
 */
class VideoDecoder(private val handler: Handler) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private val freeInputBuffers = ArrayDeque<Int>()
    private val pending = ArrayDeque<Chunk>()
    private var nextPts = 0L
    /** True until a keyframe has actually been fed to the *current* codec instance. */
    private var needsKeyframe = true

    private class Chunk(val data: ByteArray)

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

    fun submit(keyframe: Boolean, chunkWidth: Int, chunkHeight: Int, data: ByteArray) {
        handler.post {
            if (chunkWidth != width || chunkHeight != height) {
                // A genuine resolution change (rotation, or the start of a fresh session): restart
                // clean rather than feed a codec configured for the old size.
                stopCodec()
                width = chunkWidth
                height = chunkHeight
            }
            startIfPossible() // no-op if the codec already exists, or the surface isn't ready yet
            if (codec == null) return@post
            if (needsKeyframe && !keyframe) return@post // nothing to usefully decode yet
            if (keyframe) needsKeyframe = false
            pending.addLast(Chunk(data))
            drain()
        }
    }

    /** Full teardown: called once the session itself has ended, not just its view. */
    fun release() {
        handler.post {
            stopCodec()
            surface = null
            width = 0
            height = 0
        }
    }

    private fun startIfPossible() {
        if (codec != null) return
        val s = surface ?: return
        if (width <= 0 || height <= 0) return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        freeInputBuffers.addLast(index)
                        drain()
                    }
                    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        runCatching { codec.releaseOutputBuffer(index, info.size > 0) }
                    }
                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = Unit
                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
                }, handler)
                configure(format, s, null, 0)
                start()
            }
        } catch (e: Exception) {
            null // a bad surface or an unsupported size; the next keyframe will try again
        }
        needsKeyframe = true // a fresh instance has never been fed anything yet
    }

    private fun drain() {
        val c = codec ?: return
        while (freeInputBuffers.isNotEmpty() && pending.isNotEmpty()) {
            val index = freeInputBuffers.removeFirstOrNull() ?: break
            val chunk = pending.removeFirstOrNull() ?: break
            val ok = runCatching {
                val buffer = c.getInputBuffer(index) ?: return@runCatching false
                buffer.clear()
                buffer.put(chunk.data)
                c.queueInputBuffer(index, 0, chunk.data.size, nextPts, 0)
                nextPts += FRAME_PTS_STEP_US
                true
            }.getOrDefault(false)
            if (!ok) stopCodec() // something is wrong with this codec instance; wait for the next keyframe to retry clean
        }
    }

    private fun stopCodec() {
        freeInputBuffers.clear()
        pending.clear()
        codec?.let { c -> runCatching { c.stop() }; runCatching { c.release() } }
        codec = null
        needsKeyframe = true
    }

    private companion object {
        const val FRAME_PTS_STEP_US = 66_000L // ~15fps worth of spacing; only needs to be increasing
    }
}
