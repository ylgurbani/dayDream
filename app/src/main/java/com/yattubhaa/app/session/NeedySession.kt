package com.yattubhaa.app.session

import com.yattubhaa.app.net.ConnectionQuality
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

    init {
        // The number is only good for a while, so an old one left on screen cannot be reused later.
        scope.launch {
            delay(EXPIRES_AFTER_MS)
            if (_state.value.phase != NeedyPhase.Secured) {
                end("Nobody joined in time. Tap Get Help to try again.")
            }
        }
        // Watches for the accessibility service coming back after Android rebinds or restarts
        // it mid-session, so Unavailable recovers on its own rather than needing the helper to
        // send another gesture first (see recheckAvailability).
        scope.launch {
            RemoteInput.connected.collect { if (it) recheckAvailability() }
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
            is Protocol.Message.Tap, is Protocol.Message.LongPress, is Protocol.Message.GesturePath, is Protocol.Message.Nav ->
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
        if (_state.value.controlState == state) return
        _state.value = _state.value.copy(controlState = state)
        if (tellPeer) send(Protocol.controlStatus(state))
    }

    /**
     * Applies one tap, swipe or nav message, and keeps this phone's own [controlState] (which
     * drives what he sees on his own screen, not just what the helper is told) in step with
     * what actually happened. Reachable from Blocked and Unavailable too, not just On:
     *  - Blocked, so a Nav message can still get through while paused, and so recovery back to
     *    On is noticed the moment he leaves the secure app.
     *  - Unavailable, so a real bug found on a real device has a way out: Android can rebind or
     *    restart the accessibility service mid-session on its own, which briefly makes
     *    [RemoteInput] report unavailable even though he already said yes. Excluding Unavailable
     *    here used to mean that once this happened, every future gesture was silently dropped
     *    forever — this method itself is the only place anything re-checks whether the service
     *    has come back, and it was never reached again. Only Off (never said yes) and Asked
     *    (hasn't answered yet) are still excluded: those are consent gates, not capability
     *    checks, and must never be bypassed by a message alone. See also [recheckAvailability].
     */
    private fun applyGesture(message: Protocol.Message) {
        val cs = _state.value.controlState
        if (cs == ControlState.Off || cs == ControlState.Asked) return
        when (RemoteInput.apply(message)) {
            RemoteInput.Result.Blocked -> setControl(ControlState.Blocked, tellPeer = true)
            RemoteInput.Result.Done -> setControl(ControlState.On, tellPeer = true)
            RemoteInput.Result.Unavailable ->
                // They switched the accessibility setting off, or Android rebound the service,
                // mid-session.
                setControl(ControlState.Unavailable, tellPeer = true)
            RemoteInput.Result.Failed -> Unit // a one-off failure; leave the state as it is
        }
    }

    /**
     * Recovers from Unavailable the moment the accessibility service is actually back, without
     * needing a gesture to arrive and trigger [applyGesture]'s own recovery — found on a real
     * device: Android had rebound the service mid-session, and once [RemoteInput] came back on
     * its own a few seconds later, nothing was watching for that, so the session stayed stuck
     * reporting Unavailable indefinitely.
     */
    private fun recheckAvailability() {
        if (_state.value.controlState == ControlState.Unavailable && RemoteInput.isAvailable) {
            setControl(ControlState.On, tellPeer = true)
        }
    }

    override fun onEnded(reason: String) {
        _state.value = _state.value.copy(phase = NeedyPhase.Ended, message = reason)
        scope.cancel()
    }

    /** Told once, the moment sharing begins, so the helper has something to show other than
     *  silence while the actual picture is still on its way. */
    fun notifySharingStarted() = send(Protocol.sharingStarted())

    /** How much of the picture is queued but not yet actually out the door — the same signal
     *  [ScreenShareService]'s bitrate adaptation and connection-quality reporting are both
     *  built on, so both react to the one real thing they can measure about the connection. */
    fun outgoingBacklogBytes(): Long = backlogBytes()

    fun sendConnectionQuality(quality: ConnectionQuality) = send(Protocol.connectionQuality(quality))

    /**
     * Drops the chunk if the connection is behind, so a slow link catches up instead of building
     * an ever-growing delay — but a keyframe gets a much larger allowance than a delta frame
     * before it is dropped, not the same one. A dropped delta frame is meant to be a temporary,
     * self-healing glitch, healed by the next keyframe — but on a genuinely, persistently
     * congested connection (found on a real link, not just a brief blip: two phones on opposite
     * sides of the world, one on a slow mobile connection), the backlog can stay high enough for
     * long enough that a keyframe subject to the very same threshold as everything else gets
     * dropped too, and with it the only way the picture was ever going to recover — reported as
     * corruption that never clears, and a picture that stops updating even once the backlog would
     * otherwise have let a smaller delta frame through. Letting keyframes wait in a bigger queue
     * instead is the deliberate trade: a little more latency for one, rare, large, critical chunk,
     * against the alternative of no way back at all.
     */
    fun sendVideoChunk(keyframe: Boolean, width: Int, height: Int, data: ByteArray): Boolean {
        val limit = if (keyframe) MAX_KEYFRAME_BACKLOG_BYTES else MAX_BACKLOG_BYTES
        return backlogBytes() < limit && send(Protocol.videoChunk(keyframe, width, height, data))
    }

    private companion object {
        const val EXPIRES_AFTER_MS = 10 * 60 * 1000L
        const val MAX_WRONG_TRIES = 5
        const val IDLE_AFTER_CONNECT_MS = 3 * 60 * 1000L
        // Tightened from an earlier 512KB: at BitrateAdapter's lowest floor, 512KB of backlog
        // could mean the queue itself is many seconds stale before a single delta frame gets
        // dropped to relieve it — too much added latency on a real slow link. A keyframe gets
        // the old, larger allowance instead (see sendVideoChunk's own doc for why).
        const val MAX_BACKLOG_BYTES = 128 * 1024L
        const val MAX_KEYFRAME_BACKLOG_BYTES = 512 * 1024L

        fun newCode(): String = "%06d".format(SecureRandom().nextInt(1_000_000))
    }
}
