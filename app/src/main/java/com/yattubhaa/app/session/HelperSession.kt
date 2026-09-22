package com.yattubhaa.app.session

import android.os.Handler
import android.os.Looper
import com.yattubhaa.app.net.ControlState
import com.yattubhaa.app.net.NavAction
import com.yattubhaa.app.net.Protocol
import com.yattubhaa.app.net.Role
import com.yattubhaa.app.pairing.PairingStore
import com.yattubhaa.app.service.VideoDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Where the helper last pointed, as fractions of the screen, or null. */
    val pointer: Pair<Float, Float>? = null,
    /** What their phone last said about control: asked, on, blocked, refused... */
    val controlState: ControlState = ControlState.Off,
)

/** The helper's side: connect with the number the other person reads out, then watch and point. */
class HelperSession(pairing: PairingStore.Record, code: String) : BaseSession(Role.Helper, pairing, code) {
    private val _state = MutableStateFlow(HelperState(HelperPhase.Connecting, pairing.name))
    val state: StateFlow<HelperState> = _state.asStateFlow()

    /** Feeds their mirrored screen straight to whatever SurfaceView the UI attaches. */
    val videoDecoder = VideoDecoder(Handler(Looper.getMainLooper()))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    }

    override fun onBadCode() {
        // Their hello did not match the number typed here. Tell the helper straight away.
        end("That number does not match. Ask ${pairing.name} to read it out again.", notifyPeer = false, wrongCode = true)
    }

    override fun onMessage(message: Protocol.Message) {
        when (message) {
            Protocol.Message.SharingStarted -> _state.value = _state.value.copy(sharingStarted = true)
            is Protocol.Message.VideoChunk -> {
                val size = message.width to message.height
                if (_state.value.frameSize != size) {
                    _state.value = _state.value.copy(sharingStarted = true, frameSize = size)
                }
                videoDecoder.submit(message.keyframe, message.width, message.height, message.data)
            }
            // Only their phone decides whether control is on; this just shows what it said.
            is Protocol.Message.ControlStatus -> _state.value = _state.value.copy(controlState = message.state)
            else -> Unit
        }
    }

    override fun onEnded(reason: String) {
        _state.value = _state.value.copy(phase = HelperPhase.Ended, message = reason, pointer = null)
        videoDecoder.release()
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
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) =
        send(Protocol.swipe(x1, y1, x2, y2, durationMs))
    fun navigate(action: NavAction) = send(Protocol.nav(action))

    private companion object {
        const val CONNECT_TIMEOUT_MS = 25_000L
    }
}
