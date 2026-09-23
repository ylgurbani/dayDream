package com.yattubhaa.app.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Surface
import com.yattubhaa.app.net.VideoCodec

/**
 * Wraps a hardware video encoder in "surface input" mode: whatever is drawn to [inputSurface]
 * (here, the VirtualDisplay mirroring the screen) is encoded directly by the phone's video
 * hardware, with no CPU bitmap copy in between — the same technique scrcpy uses.
 *
 * [onChunk] is called from [callbackHandler]'s thread with each encoded frame. Every keyframe
 * handed to it carries its own copy of the codec config (SPS/PPS, plus VPS for H.265), cached and
 * prepended by hand, so a decoder that starts or restarts mid-stream only ever needs the next
 * keyframe. (`KEY_PREPEND_HEADER_TO_SYNC_FRAMES` would do this inside the codec, but a real
 * encoder was found that rejects that key in `configure()` outright.)
 *
 * Two settings do what earlier versions of this app got wrong by hand, further down the line:
 *  - **The frame rate is capped before encoding, not after.** A surface-input encoder has no
 *    frame-rate limit of its own (`KEY_FRAME_RATE` is only a hint for bitrate maths), so it
 *    encodes every frame the screen draws. The earlier fix threw away encoded frames over the
 *    cap — but each frame only describes changes since the previous one, so the helper then
 *    decoded every frame after a dropped one against the wrong picture: the tearing and
 *    blockiness seen on a real long-distance test, during any movement at all.
 *    `KEY_MAX_FPS_TO_ENCODER` (Android 10+) has the input surface skip excess frames before
 *    they are encoded, so every frame that is encoded is also sent.
 *  - **A still screen keeps producing frames.** A screen that is not changing draws nothing new,
 *    so a plain encoder falls silent, and the earlier workaround — resending the last cached
 *    keyframe — could rewind the helper's picture to how the screen looked up to two seconds
 *    earlier, over and over. `KEY_REPEAT_PREVIOUS_FRAME_AFTER` has the encoder repeat the current
 *    picture instead (tiny frames, since nothing changed), so the stream never goes quiet, a
 *    requested keyframe always shows the screen as it is *now*, and a still picture keeps getting
 *    sharper rather than staying as blurry as its last moving frame left it.
 *
 * Optional settings are tried in layers: if an encoder rejects one (some do, in `configure()`
 * itself rather than ignoring it), the next layer drops it. [setupLevel] says which layer was
 * accepted, for the helper's stats overlay: 0 is everything, [SETUP_LEVELS] - 1 the bare minimum.
 */
class ScreenEncoder(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    initialBitRate: Int,
    maxFps: Int,
    callbackHandler: Handler,
    private val onChunk: (keyframe: Boolean, data: ByteArray) -> Unit,
    private val onFailed: () -> Unit,
) {
    val inputSurface: Surface
    val setupLevel: Int
    private val mediaCodec: MediaCodec = MediaCodec.createEncoderByType(codec.mime)
    /** The most recent codec config bytes, reused for every keyframe (they do not change within
     *  one encoder's lifetime), not just the one immediately after they were emitted. */
    private var configBytes: ByteArray? = null
    @Volatile private var released = false

    init {
        var accepted = -1
        for (level in 0 until SETUP_LEVELS) {
            try {
                mediaCodec.configure(format(level, initialBitRate, maxFps), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                accepted = level
                break
            } catch (e: Exception) {
                Log.w(TAG, "${codec.label} ${width}x$height rejected setup level $level", e)
                runCatching { mediaCodec.reset() }
            }
        }
        if (accepted < 0) {
            mediaCodec.release()
            throw IllegalStateException("${codec.label} encoder accepted no configuration at ${width}x$height")
        }
        setupLevel = accepted
        inputSurface = mediaCodec.createInputSurface()
        mediaCodec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input: nothing to feed by hand
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) =
                this@ScreenEncoder.onOutputBuffer(index, info)
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "${this@ScreenEncoder.codec.label} encoder failed", e)
                if (!released) onFailed()
            }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, callbackHandler)
    }

    private fun format(level: Int, bitRate: Int, maxFps: Int) =
        MediaFormat.createVideoFormat(codec.mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
            // Rare on purpose: the helper asks for a keyframe whenever it actually needs one (a
            // missed frame, a fresh decoder), so periodic ones are only a safety net — and each is
            // a burst many times the size of an ordinary frame, which on a slow link means a
            // latency spike. The earlier 2 seconds meant one of those every 2 seconds.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            // Deliberately not requesting a bitrate mode (e.g. CBR): an unsupported value for that
            // one fails configure() on some encoders, and KEY_BIT_RATE alone is respected as a target.
            if (level <= 1) setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
            if (level == 0 && Build.VERSION.SDK_INT >= 29) {
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, maxFps.toFloat())
                // No reordered frames: they add latency, and the decoder side assumes none.
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }

    fun start() = mediaCodec.start()

    /** Asks for a keyframe on the very next frame — soon, since frames never stop coming. */
    fun requestKeyframe() {
        if (released) return
        runCatching { mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /**
     * Pauses or resumes the capture feeding this encoder: while paused, the screen's frames are
     * skipped before they are ever encoded. Resuming continues with an ordinary frame built on the
     * last one encoded, so — unlike throwing away frames already encoded — the picture's chain of
     * frames is never broken and no keyframe is needed. See [SendGate].
     */
    fun setPaused(paused: Boolean) {
        if (released) return
        runCatching { mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (paused) 1 else 0) }) }
    }

    /** Changes the target bitrate of the running encoder — `MediaCodec` supports this live. */
    fun setBitrate(bitRate: Int) {
        if (released) return
        runCatching { mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitRate) }) }
    }

    /** Stops this encoder producing anything at once, and lets it finish releasing in the
     *  background ([CodecReleaser]): that part can take a second or more on some phones. */
    fun release() {
        released = true
        CodecReleaser.release {
            runCatching { mediaCodec.stop() }
            runCatching { mediaCodec.release() }
            inputSurface.release()
        }
    }

    private fun onOutputBuffer(index: Int, info: MediaCodec.BufferInfo) {
        if (released) return
        val data = runCatching {
            val buffer = mediaCodec.getOutputBuffer(index)
            val bytes = if (buffer == null || info.size <= 0) {
                null
            } else {
                ByteArray(info.size).also {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(it)
                }
            }
            mediaCodec.releaseOutputBuffer(index, false)
            bytes
        }.getOrNull() ?: return

        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (isConfig && !isKeyFrame) {
            // A standalone config buffer, normally seen once right after start(): cache it rather
            // than send it alone, so it can be prepended to every keyframe from here on.
            configBytes = data
            return
        }
        onChunk(isKeyFrame, if (isKeyFrame) configBytes?.let { it + data } ?: data else data)
    }

    companion object {
        const val SETUP_LEVELS = 3
        private const val TAG = "ScreenEncoder"
        private const val I_FRAME_INTERVAL_SECONDS = 10
        // How long a still screen goes before its picture is repeated: ~10 tiny frames a second.
        private const val REPEAT_AFTER_US = 100_000L
    }
}
