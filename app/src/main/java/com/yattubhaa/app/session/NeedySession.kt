package com.yattubhaa.app.session

import com.yattubhaa.app.net.ControlState
import com.yattubhaa.app.net.Protocol
import com.yattubhaa.app.net.Role
import com.yattubhaa.app.pairing.PairingStore
import com.yattubhaa.app.service.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

enum class NeedyPhase { Connecting, Waiting, Secured, Ended }

data class NeedyState(
    val phase: NeedyPhase,
    val code: String,
    val helperName: String,
    val message: String = "",
    /** Whether the helper has asked to tap and swipe, and what was decided. */
    val controlState: ControlState = ControlState.Off,
)

/**
 * The person being helped taps Get Help. This shows them a one-time six digit number to read
 * out over the call, and connects only once the helper types the same number.
 */
class NeedySession private constructor(pairing: PairingStore.Record, code: String) :
    BaseSession(Role.Needy, pairing, code) {

    constructor(pairing: PairingStore.Record) : this(pairing, newCode())

    private val _state = MutableStateFlow(NeedyState(NeedyPhase.Connecting, code, pairing.name))
    val state: StateFlow<NeedyState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Protocol.Message>(extraBufferCapacity = 16)

    /** Pointer requests from the helper, for the overlay to draw. */
    val incoming: SharedFlow<Protocol.Message> = _incoming.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wrongTries = 0
    private var reportedBlocked = false

    init {
        // The number is only good for a while, so an old one left on screen cannot be reused later.
        scope.launch {
            delay(EXPIRES_AFTER_MS)
            if (_state.value.phase != NeedyPhase.Secured) {
                end("Nobody joined in time. Tap Get Help to try again.")
            }
        }
    }

    override fun onPeer(present: Boolean) {
        val s = _state.value
        if (present) {
            if (s.phase == NeedyPhase.Connecting) _state.value = s.copy(phase = NeedyPhase.Waiting)
        } else if (s.phase == NeedyPhase.Secured) {
            end("${pairing.name} disconnected.", notifyPeer = false)
        } else {
            _state.value = s.copy(phase = NeedyPhase.Waiting)
        }
    }

    /** Set by the screen-sharing service once pictures are actually being sent. */
    @Volatile var sharing = false

    override fun onSecured() {
        _state.value = _state.value.copy(phase = NeedyPhase.Secured, message = "")
        // Connected but never shared means nothing visible is happening on this phone, so do
        // not leave the connection open in the background.
        scope.launch {
            delay(IDLE_AFTER_CONNECT_MS)
            if (!sharing && !ended) end("Nothing was shared, so the connection was closed. Tap Get Help to start again.")
        }
    }

    override fun onBadCode() {
        wrongTries++
        if (wrongTries >= MAX_WRONG_TRIES) {
            end("Too many wrong numbers were tried. Tap Get Help to start again.")
        } else {
            _state.value = _state.value.copy(message = "A wrong number was tried.")
        }
    }

    override fun onMessage(message: Protocol.Message) {
        when (message) {
            is Protocol.Message.ControlRequest -> when {
                // Nothing is being shared, so there is nothing to take control of.
                !sharing -> send(Protocol.controlStatus(ControlState.Off))
                _state.value.controlState != ControlState.On ->
                    _state.value = _state.value.copy(controlState = ControlState.Asked)
            }
            is Protocol.Message.ControlRelease -> setControl(ControlState.Off, tellPeer = false)
            is Protocol.Message.Tap, is Protocol.Message.LongPress, is Protocol.Message.Swipe, is Protocol.Message.Nav ->
                applyGesture(message)
            else -> Unit
        }
        _incoming.tryEmit(message)
    }

    /** The person being helped answering a control request. */
    fun acceptControl() {
        // Only ever an answer to a request that is actually waiting.
        if (_state.value.controlState != ControlState.Asked) return
        setControl(if (RemoteInput.isAvailable) ControlState.On else ControlState.Unavailable, tellPeer = true)
    }

    fun declineControl() {
        if (_state.value.controlState == ControlState.Asked) setControl(ControlState.Off, tellPeer = true)
    }

    /** Lets them take it back at any moment, not only when asked. */
    fun revokeControl() = setControl(ControlState.Off, tellPeer = true)

    private fun setControl(state: ControlState, tellPeer: Boolean) {
        reportedBlocked = false
        _state.value = _state.value.copy(controlState = state)
        if (tellPeer) send(Protocol.controlStatus(state))
    }

    private fun applyGesture(message: Protocol.Message) {
        if (_state.value.controlState != ControlState.On) return
        when (RemoteInput.apply(message)) {
            RemoteInput.Result.Blocked -> if (!reportedBlocked) {
                reportedBlocked = true
                send(Protocol.controlStatus(ControlState.Blocked))
            }
            RemoteInput.Result.Done -> if (reportedBlocked) {
                reportedBlocked = false
                send(Protocol.controlStatus(ControlState.On))
            }
            RemoteInput.Result.Unavailable ->
                // They switched the accessibility setting off mid-session.
                setControl(ControlState.Unavailable, tellPeer = true)
            else -> Unit
        }
    }

    override fun onEnded(reason: String) {
        _state.value = _state.value.copy(phase = NeedyPhase.Ended, message = reason)
        scope.cancel()
    }

    /** Drops the frame if the connection is behind, so a slow link shows a slightly old
     *  picture instead of an ever-growing delay. */
    fun sendFrame(width: Int, height: Int, jpeg: ByteArray): Boolean =
        backlogBytes() < MAX_BACKLOG_BYTES && send(Protocol.frame(width, height, jpeg))

    private companion object {
        const val EXPIRES_AFTER_MS = 10 * 60 * 1000L
        const val MAX_WRONG_TRIES = 5
        const val IDLE_AFTER_CONNECT_MS = 3 * 60 * 1000L
        const val MAX_BACKLOG_BYTES = 512 * 1024L

        fun newCode(): String = "%06d".format(SecureRandom().nextInt(1_000_000))
    }
}
