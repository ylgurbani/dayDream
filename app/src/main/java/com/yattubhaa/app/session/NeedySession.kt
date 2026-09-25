package com.yattubhaa.app.session

import com.yattubhaa.app.net.ControlState
import com.yattubhaa.app.net.Protocol
import com.yattubhaa.app.net.Role
import com.yattubhaa.app.net.TouchPhase
import com.yattubhaa.app.net.VideoCodec
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * The helper's receiver reports and keyframe requests, for the screen-sharing service. A
     * direct callback on the connection's own thread rather than [incoming]: that flow drops
     * messages when its buffer is full, and these must not be dropped.
     */
    @Volatile var videoFeedback: ((Protocol.Message) -> Unit)? = null

    /** Called when the secure channel comes back after a dropped connection: anything sent
     *  before the drop is not going to arrive now (see SendTracker.onReconnected). */
    @Volatile var onReconnected: (() -> Unit)? = null

    /** What the helper's phone says it can decode in hardware; H.264 until it says otherwise. */
    @Volatile var helperDecoders: Set<VideoCodec> = setOf(VideoCodec.Avc)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wrongTries = 0

    /**
     * Taps, drags and navigation from the helper are carried out here, one at a time and in
     * order — not on the connection's own thread, where they used to run. Carrying one out means
     * asking Android which apps are on screen, which waits on those apps; done on the connection's
     * thread, that wait also held up every video frame going to the helper (sending and receiving
     * share one lock), which is how a slow step mid-drag could freeze the picture on both phones.
     */
    private val input = Executors.newSingleThreadExecutor { r -> Thread(r, "yattu-input") }
    /** The newest drag step not yet carried out. Only the newest matters: if steps have piled
     *  up behind something slow, the finger goes straight to where the helper's finger is now
     *  instead of replaying the backlog. */
    private val pendingMove = AtomicReference<Protocol.Message.Touch?>(null)
    private val inputSteps = AtomicInteger()
    private val inputFailed = AtomicInteger()
    private val slowestInputMs = AtomicLong()

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
        if (_state.value.phase == NeedyPhase.Secured || sharing) onReconnected?.invoke()
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
            is Protocol.Message.Touch -> if (message.phase == TouchPhase.Move) queueMove(message) else queueGesture(message)
            is Protocol.Message.Tap, is Protocol.Message.LongPress, is Protocol.Message.GesturePath,
            is Protocol.Message.Nav -> queueGesture(message)
            is Protocol.Message.ReceiverReport, Protocol.Message.KeyframeRequest -> videoFeedback?.invoke(message)
            is Protocol.Message.Decoders -> {
                helperDecoders = message.codecs + VideoCodec.Avc
                videoFeedback?.invoke(message)
            }
            is Protocol.Message.Pointer -> _incoming.tryEmit(message)
            else -> Unit
        }
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
        // A finger the helper was holding down on this screen must never outlive their control:
        // lifted now, and again after anything already waiting on the input thread.
        if (state != ControlState.On) {
            RemoteInput.cancelTouch()
            runCatching { input.execute { RemoteInput.cancelTouch() } }
        }
        if (tellPeer) send(Protocol.controlStatus(state))
    }

    private fun queueGesture(message: Protocol.Message) {
        runCatching { input.execute { applyGesture(message) } } // refused only once the session has ended
    }

    private fun queueMove(move: Protocol.Message.Touch) {
        if (pendingMove.getAndSet(move) != null) return // a step already waiting will take this one instead
        runCatching { input.execute { pendingMove.getAndSet(null)?.let { applyGesture(it) } } }
    }

    /** Steps carried out and steps that failed since the session began, and the slowest single
     *  step since the last call — shown in the helper's stats overlay. */
    fun takeInputStats(): Triple<Int, Int, Int> =
        Triple(inputSteps.get(), inputFailed.get(), slowestInputMs.getAndSet(0).toInt())

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
        val started = System.nanoTime()
        val result = RemoteInput.apply(message)
        slowestInputMs.accumulateAndGet((System.nanoTime() - started) / 1_000_000, ::maxOf)
        inputSteps.incrementAndGet()
        if (result != RemoteInput.Result.Done) inputFailed.incrementAndGet()
        when (result) {
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
        RemoteInput.cancelTouch()
        SessionHub.needyEnded()
        videoFeedback = null
        onReconnected = null
        input.shutdown()
        scope.cancel()
    }

    /** Told once, the moment sharing begins, so the helper has something to show other than
     *  silence while the actual picture is still on its way. */
    fun notifySharingStarted() = send(Protocol.sharingStarted())

    /** How much of the picture is queued on this phone but not yet actually sent. */
    fun outgoingBacklogBytes(): Long = backlogBytes()

    fun sendStats(stats: Protocol.Message.SenderStats) = send(Protocol.senderStats(stats))

    /** One encoded frame, already approved by the service's [com.yattubhaa.app.service.SendGate].
     *  False if the connection refused it (mid-reconnect, say). */
    fun sendVideoFrame(keyframe: Boolean, codec: VideoCodec, width: Int, height: Int, seq: Int, sentAt: Int, data: ByteArray): Boolean =
        send(Protocol.videoFrame(keyframe, codec, width, height, seq, sentAt, data))

    private companion object {
        const val EXPIRES_AFTER_MS = 10 * 60 * 1000L
        const val MAX_WRONG_TRIES = 5
        const val IDLE_AFTER_CONNECT_MS = 3 * 60 * 1000L

        fun newCode(): String = "%06d".format(SecureRandom().nextInt(1_000_000))
    }
}
