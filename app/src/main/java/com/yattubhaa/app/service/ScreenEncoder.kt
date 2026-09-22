package com.yattubhaa.app.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.view.Surface

/**
 * Wraps a hardware H.264 encoder in "surface input" mode: whatever is drawn to [inputSurface]
 * (here, the VirtualDisplay mirroring the screen) is encoded directly by the phone's video
 * hardware, with no CPU bitmap copy in between at all — unlike the JPEG-per-frame pipeline this
 * replaced, where every frame meant a CPU readback plus a CPU-only JPEG encode. This is the same
 * technique screen-mirroring tools such as scrcpy use.
 *
 * [onChunk] is called from [callbackHandler]'s thread with each encoded chunk as it becomes
 * available. Every keyframe handed to [onChunk] carries its own copy of the SPS/PPS config
 * (cached below and prepended by hand — see [onOutputBuffer]), so a decoder that starts or
 * restarts mid-stream only ever has to wait for the next keyframe, never the very first one.
 * `MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES` would normally do this inside the codec, but a
 * real device was found where `configure()` rejects that key outright (`BAD_VALUE`); doing it by
 * hand here works everywhere and does not depend on that key being supported at all.
 */
class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    callbackHandler: Handler,
    private val onChunk: (keyframe: Boolean, width: Int, height: Int, data: ByteArray) -> Unit,
) {
    val inputSurface: Surface
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    /** The most recent SPS/PPS config bytes, reused for every keyframe (they do not change
     *  within a session), not just the one immediately after they were (re-)emitted. */
    private var configBytes: ByteArray? = null

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            // Deliberately not requesting a bitrate mode (e.g. CBR): unlike the other keys here,
            // an unsupported value for this one is rejected by configure() itself rather than
            // just being ignored — found the hard way, failing configure() outright on an
            // emulator's software encoder. KEY_BIT_RATE alone is still respected as a target.
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input: nothing to feed by hand
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) =
                this@ScreenEncoder.onOutputBuffer(index, info)
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = Unit
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, callbackHandler)
    }

    fun start() = codec.start()

    /** Asks for a fresh keyframe soon — useful right when a viewer newly needs one. */
    fun requestKeyframe() {
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    private fun onOutputBuffer(index: Int, info: MediaCodec.BufferInfo) {
        val buffer = codec.getOutputBuffer(index)
        if (buffer == null || info.size <= 0) {
            runCatching { codec.releaseOutputBuffer(index, false) }
            return
        }
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(data)
        runCatching { codec.releaseOutputBuffer(index, false) }

        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (isConfig && !isKeyFrame) {
            // A standalone SPS/PPS buffer, normally seen once right after start(): cache it
            // rather than send it alone, so it can be prepended to every keyframe from here on.
            configBytes = data
            return
        }
        if (!isKeyFrame) {
            onChunk(false, width, height, data)
            return
        }
        val payload = configBytes?.let { it + data } ?: data
        onChunk(true, width, height, payload)
    }

    private companion object {
        const val BIT_RATE = 1_600_000 // ~1.6 Mbps: comfortable for a mostly-static phone screen
        const val FRAME_RATE = 15
        const val I_FRAME_INTERVAL_SECONDS = 2
    }
}
