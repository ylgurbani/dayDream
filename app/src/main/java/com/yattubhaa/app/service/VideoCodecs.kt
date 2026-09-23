package com.yattubhaa.app.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import com.yattubhaa.app.net.VideoCodec
import java.util.concurrent.Executors

/**
 * Which video formats this phone can handle in hardware. H.265 needs roughly a third fewer bits
 * than H.264 for the same picture, which is worth having on a slow link, but only when *both*
 * phones have it in hardware: a software codec on a phone is too slow for live video. The helper
 * says what it can decode (see `Protocol.decoders`); the sharing phone then picks.
 */
object VideoCodecs {
    fun hardwareDecoders(): Set<VideoCodec> =
        VideoCodec.entries.filter { hasHardware(it, encoder = false) }.toSet() + VideoCodec.Avc // H.264 always works, somehow

    /** H.265 if the helper can decode it and this phone can encode it in hardware at this size,
     *  else H.264. */
    fun choose(helperDecodes: Set<VideoCodec>, hevcFailedHere: Boolean, width: Int, height: Int): VideoCodec =
        if (!hevcFailedHere && VideoCodec.Hevc in helperDecodes && hasHardware(VideoCodec.Hevc, encoder = true) &&
            encoderCaps(VideoCodec.Hevc)?.isSizeSupported(width, height) == true
        ) {
            VideoCodec.Hevc
        } else {
            VideoCodec.Avc
        }

    /**
     * The largest size no bigger than [width] x [height], same shape, in multiples of 8, that this
     * phone's encoder for [codec] says it supports. Encoders differ — some older ones will not go
     * taller than 1088 pixels, for example — and asking for a size one does not support fails
     * outright, so it is checked here rather than found out mid-session. (Found on an emulator,
     * whose H.265 encoder rejected a phone-shaped 720x1600 at every setting.)
     */
    fun fitSize(codec: VideoCodec, width: Int, height: Int): Pair<Int, Int> {
        val caps = encoderCaps(codec) ?: return width to height
        var w = width
        var h = height
        repeat(12) {
            if (caps.isSizeSupported(w, h)) return w to h
            w = (w * 0.9f).toInt() / 8 * 8
            h = (h * 0.9f).toInt() / 8 * 8
        }
        return width to height // nothing found: try as asked, and let the fallbacks deal with it
    }

    /** The capabilities of the encoder `MediaCodec.createEncoderByType` would pick: the first listed. */
    private fun encoderCaps(codec: VideoCodec): MediaCodecInfo.VideoCapabilities? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .first { info -> info.isEncoder && info.supportedTypes.any { it.equals(codec.mime, ignoreCase = true) } }
            .getCapabilitiesForType(codec.mime).videoCapabilities
    }.getOrNull()

    private fun hasHardware(codec: VideoCodec, encoder: Boolean): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder == encoder && isHardware(info) &&
                info.supportedTypes.any { it.equals(codec.mime, ignoreCase = true) }
        }
    }.getOrDefault(false)

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated && !info.isAlias
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains(".sw.")
        }
}

/** The whole physical screen in its current orientation, in pixels — status and navigation bars
 *  included, because those are captured and can be tapped too. Used by both the capture and the
 *  remote taps, so the two always agree on what "a fraction of the screen" means. */
object ScreenSize {
    fun real(context: Context): DisplayMetrics {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return DisplayMetrics().also {
            @Suppress("DEPRECATION") // its replacement needs a window; this is a service
            display.getRealMetrics(it)
        }
    }
}

/**
 * Where replaced encoders and decoders are released. Releasing a codec can take a second or two
 * on some phones, and done on the thread that is meant to be starting its replacement it held up
 * the new picture by exactly that long — measured at over two seconds per screen rotation on an
 * emulator. The old codec's callbacks are ignored from the moment it is replaced, so nothing waits
 * on it any more; one background thread lets it finish in its own time.
 */
object CodecReleaser {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "yattu-codec-release") }

    fun release(block: () -> Unit) {
        thread.execute { runCatching(block) }
    }
}
