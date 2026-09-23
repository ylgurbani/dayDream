package com.yattubhaa.app.session

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.yattubhaa.app.net.ConnectionQuality
import com.yattubhaa.app.net.ControlState
import com.yattubhaa.app.net.NavAction
import com.yattubhaa.app.net.Protocol
import com.yattubhaa.app.net.Role
import com.yattubhaa.app.net.TouchPhase
import com.yattubhaa.app.net.VideoCodec
import com.yattubhaa.app.pairing.PairingStore
import com.yattubhaa.app.service.ReceiveTracker
import com.yattubhaa.app.service.VideoCodecs
import com.yattubhaa.app.service.VideoDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class HelperPhase { Connecting, WaitingForPhone, Secured, Ended }

data class HelperState(
    val phase: HelperPhase,
    val name: String,
    val message: String = "",
    /** True when the session ended because the typed number did not match. */
    val wrongCode: Boolean = false,
    /** True once they have started sharing — the picture itself may still be a moment away, but
     *  this is enough to show "connecting" instead of leaving the screen looking like nothing
     *  is happening. */
    val sharingStarted: Boolean = false,
    /** Width and height of their screen, once the first video chunk has arrived — the pixels
     *  themselves go straight from [VideoDecoder] to the SurfaceView, never through this state. */
    val frameSize: Pair<Int, Int>? = null,
    /** How well the picture is getting through, as their phone judges it from this phone's
     *  receiver reports — shown only to the helper. Starts optimistic rather than unknown, so
     *  nothing alarming flashes up before the first real reading arrives. */
    val connectionQuality: ConnectionQuality = ConnectionQuality.Good,
    /** Where the helper last pointed, as fractions of the screen, or null. */
    val pointer: Pair<Float, Float>? = null,
    /** What their phone last said about control: asked, on, blocked, refused... */
    val controlState: ControlState = ControlState.Off,
)

/** What the hidden stats overlay on the helper's screen shows: this phone's view of the picture
 *  arriving, plus the other phone's latest [Protocol.Message.SenderStats]. */
data class VideoStats(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val fps: Int,
    val kbps: Int,
    val queueDelayMs: Int,
    val lostFrames: Int,
    val keyframes: Long,
    val keyframeRequests: Int,
    val decoderRestarts: Int,
    val sender: Protocol.Message.SenderStats?,
)

/** The helper's side: connect with the number the other person reads out, then watch and point. */
class HelperSession(pairing: PairingStore.Record, code: String) : BaseSession(Role.Helper, pairing, code) {
    private val _state = MutableStateFlow(HelperState(HelperPhase.Connecting, pairing.name))
    val state: StateFlow<HelperState> = _state.asStateFlow()

    /** Feeds their mirrored screen straight to whatever SurfaceView the UI attaches. Given its
     *  own thread, not the main one: found the hard way that `MediaCodec.stop()`/`release()` on
     *  a decoder can itself take several real seconds on some devices (a software codec, on this
     *  evidence), and running the decoder on the main thread meant that blocked Compose from
     *  recomposing anything at all for that whole time — including the "session ended" screen
     *  this very teardown is part of showing. A slow decoder teardown should never be able to
     *  freeze the rest of the UI.  */
    private val decoderThread = HandlerThread("yattu-decode").also { it.start() }
    val videoDecoder = VideoDecoder(Handler(decoderThread.looper)) { send(Protocol.keyframeRequest()) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guarded by itself: frames arrive on the connection's thread, reports go out from [scope]. */
    private val tracker = ReceiveTracker()
    @Volatile private var lastFrame: Protocol.Message.VideoFrame? = null
    @Volatile private var senderStats: Protocol.Message.SenderStats? = null
    private val _videoStats = MutableStateFlow<VideoStats?>(null)
    val videoStats: StateFlow<VideoStats?> = _videoStats.asStateFlow()

    init {
        // Without this, a phone that never shows up (off, out of range, or it dropped mid
        // handshake — for example its app closed) leaves this screen saying "Waiting for
        // Grandad" forever, with nothing to say that is not actually going to change.
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_state.value.phase != HelperPhase.Secured) {
                end("Could not connect to ${pairing.name}. Check they are still on the Get Help screen and try again.")
            }
        }
    }

    override fun onPeer(present: Boolean) {
        val s = _state.value
        when {
            present && s.phase == HelperPhase.Connecting -> Unit // wait for the handshake result
            !present && s.phase == HelperPhase.Secured -> end("${pairing.name} disconnected.", notifyPeer = false)
            !present -> _state.value = s.copy(phase = HelperPhase.WaitingForPhone)
        }
    }

    override fun onSecured() {
        _state.value = _state.value.copy(phase = HelperPhase.Secured, message = "")
        // Well before they could have started sharing, so their phone can pick the best format.
        send(Protocol.decoders(VideoCodecs.hardwareDecoders()))
        startReporting()
    }

    private var reporting = false

    /**
     * Four times a second, tells their phone what has actually arrived and how much queueing delay it
     * picked up on the way — the only way their phone can see congestion that is not in its own
     * queue (see SendTracker) — and refreshes the stats overlay once a second.
     */
    private fun startReporting() {
        if (reporting) return
        reporting = true
        scope.launch {
            var ticks = 0
            var lastFrames = 0L
            var lastBytes = 0L
            var lastAt = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(REPORT_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                val report = synchronized(tracker) { tracker.report(now) }
                if (report != null) send(Protocol.receiverReport(report))
                if (++ticks % STATS_EVERY_REPORTS != 0) continue
                val frame = lastFrame ?: continue
                val (frames, bytes, keyframes) = synchronized(tracker) { Triple(tracker.frames, tracker.bytes, tracker.keyframes) }
                val seconds = ((now - lastAt).coerceAtLeast(1)) / 1000f
                _videoStats.value = VideoStats(
                    codec = frame.codec, width = frame.width, height = frame.height,
                    fps = ((frames - lastFrames) / seconds).toInt(),
                    kbps = ((bytes - lastBytes) * 8 / 1000 / seconds).toInt(),
                    queueDelayMs = report?.queueDelayMs ?: 0,
                    lostFrames = report?.lostFrames ?: 0,
                    keyframes = keyframes,
                    keyframeRequests = videoDecoder.keyframeRequests,
                    decoderRestarts = videoDecoder.codecRestarts,
                    sender = senderStats,
                )
                if (ticks % (STATS_EVERY_REPORTS * 5) == 0) Log.i("HelperVideo", _videoStats.value.toString())
                lastFrames = frames; lastBytes = bytes; lastAt = now
            }
        }
    }

    override fun onBadCode() {
        // Their hello did not match the number typed here. Tell the helper straight away.
        end("That number does not match. Ask ${pairing.name} to read it out again.", notifyPeer = false, wrongCode = true)
    }

    override fun onMessage(message: Protocol.Message) {
        when (message) {
            Protocol.Message.SharingStarted -> _state.value = _state.value.copy(sharingStarted = true)
            is Protocol.Message.SenderStats -> {
                senderStats = message
                if (_state.value.connectionQuality != message.quality) {
                    _state.value = _state.value.copy(connectionQuality = message.quality)
                }
            }
            is Protocol.Message.VideoFrame -> {
                val gap = synchronized(tracker) {
                    tracker.onFrame(message.seq, message.sentAt, message.keyframe, message.data.size, SystemClock.elapsedRealtime())
                }
                lastFrame = message
                val size = message.width to message.height
                if (_state.value.frameSize != size) {
                    _state.value = _state.value.copy(sharingStarted = true, frameSize = size)
                }
                // After a gap the decoder freezes on the last good picture and asks for a
                // keyframe, instead of decoding the frames after it against the wrong picture.
                videoDecoder.submit(message.codec, message.keyframe, message.width, message.height, message.data, discontinuity = gap)
            }
            // Only their phone decides whether control is on; this just shows what it said.
            is Protocol.Message.ControlStatus -> _state.value = _state.value.copy(controlState = message.state)
            else -> Unit
        }
    }

    override fun onEnded(reason: String) {
        _state.value = _state.value.copy(phase = HelperPhase.Ended, message = reason, pointer = null)
        // release() only posts the teardown to the decoder's own thread and returns immediately,
        // so this state update reaching Compose is never held up by however long that teardown
        // actually takes — see the comment on videoDecoder.
        videoDecoder.release()
        decoderThread.quitSafely()
        scope.cancel()
    }

    fun end(reason: String, notifyPeer: Boolean, wrongCode: Boolean) {
        if (wrongCode) _state.value = _state.value.copy(wrongCode = true)
        end(reason, notifyPeer)
    }

    fun point(x: Float, y: Float) {
        if (send(Protocol.pointer(x, y))) _state.value = _state.value.copy(pointer = x to y)
    }

    fun clearPointer() {
        if (send(Protocol.clearPointer())) _state.value = _state.value.copy(pointer = null)
    }

    /** Ask to tap and swipe for them. Nothing happens unless they say yes on their phone. */
    fun requestControl() {
        if (send(Protocol.controlRequest())) _state.value = _state.value.copy(controlState = ControlState.Asked)
    }

    fun releaseControl() {
        if (send(Protocol.controlRelease())) _state.value = _state.value.copy(controlState = ControlState.Off)
    }

    /** These only do anything once their phone has said yes; it ignores them otherwise. */
    fun tap(x: Float, y: Float) = send(Protocol.tap(x, y))
    fun longPress(x: Float, y: Float) = send(Protocol.longPress(x, y))
    /** [points] is the actual path a dragged finger took, not just where it started and ended —
     *  see [Protocol.gesturePath]. */
    fun gesturePath(points: List<Protocol.Point>, durationMs: Int) = send(Protocol.gesturePath(points, durationMs))
    fun navigate(action: NavAction) = send(Protocol.nav(action))
    /** One step of a press-hold-drag, sent live while the finger is still down. */
    fun touch(phase: TouchPhase, x: Float, y: Float) = send(Protocol.touch(phase, x, y))

    private companion object {
        const val CONNECT_TIMEOUT_MS = 25_000L
        // Often enough that their phone notices frames not getting through within a fraction of a
        // second (see SendTracker.pathClear); each report is 15 bytes.
        const val REPORT_INTERVAL_MS = 250L
        const val STATS_EVERY_REPORTS = 4
    }
}
