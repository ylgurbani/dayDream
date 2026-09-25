package com.yattubhaa.app.service

import com.yattubhaa.app.net.NavAction
import com.yattubhaa.app.net.Protocol
import com.yattubhaa.app.net.TouchPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** What the accessibility service can do on this phone. Kept as an interface so the rules below can be tested. */
interface RemoteInputTarget {
    /**
     * The apps that have a window on screen right now, or null if that cannot be worked out.
     * Asked for at the moment of each gesture: remembering the last window event is not safe,
     * because Android can rebind the service (for example when any app is installed) and a
     * pop-up over a bank app fires no event for the bank app underneath.
     */
    fun visiblePackages(): List<String>?
    fun tap(x: Float, y: Float, longPress: Boolean): Boolean
    /** [points] is the whole path a dragged finger took, not just where it started and ended —
     *  at least two points, fractions of the screen. */
    fun gesturePath(points: List<Protocol.Point>, durationMs: Int): Boolean
    /** One step of a finger pressed, held and moved live (see [Protocol.touch]): Down presses and
     *  holds long enough to count as a long-press, Move drags it, Up lifts it. */
    fun touch(phase: TouchPhase, x: Float, y: Float): Boolean
    /** Lifts a finger held down by [touch], if there is one. Safe to call at any time. */
    fun cancelTouch()
    fun navigate(action: NavAction): Boolean
    /** Switches the service off in Android's own accessibility settings, as if he had. */
    fun switchOff()
}

/**
 * The one place a request to tap, swipe or navigate is turned into an action on this phone.
 * The accessibility service registers itself here when Android connects it, and unregisters when
 * it is switched off, so "available" simply means "the person has turned it on".
 */
object RemoteInput {
    enum class Result { Done, Unavailable, Blocked, Failed }

    @Volatile private var target: RemoteInputTarget? = null
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    val isAvailable: Boolean get() = target != null

    // When the banking-app check last ran, and whether the app on screen may have changed since.
    @Volatile private var checkedAt: Long? = null
    @Volatile private var windowsChanged = true
    private val cancelled = AtomicInteger()

    /** Steps of a held drag that Android refused or cut short, since the app started. */
    val cancelledSteps: Int get() = cancelled.get()

    /** Android says a different window came to the front: check again before the next step. */
    fun onWindowsChanged() {
        windowsChanged = true
    }

    /** A step of a held drag was refused or cut short by Android. For the helper's stats. */
    fun onStepCancelled() {
        cancelled.incrementAndGet()
    }

    private val resumed = AtomicInteger()

    /** Drags carried on after Android cut them short, since the app started. */
    val resumedDrags: Int get() = resumed.get()

    fun onDragResumed() {
        resumed.incrementAndGet()
    }

    fun attach(t: RemoteInputTarget) {
        checkedAt = null
        windowsChanged = true
        target = t
        _connected.value = true
    }

    fun detach(t: RemoteInputTarget) {
        if (target === t) {
            target = null
            _connected.value = false
        }
    }

    /** Lifts any held finger and switches the service off, if it is connected. See [ControlCapability.switchOff]. */
    fun switchOff() {
        val t = target ?: return
        t.cancelTouch()
        t.switchOff()
    }

    /**
     * Applies one message, unless a banking or payment app is on screen (or we cannot tell). Going back, home or to
     * recents is always allowed, because that is how someone gets out of such an app.
     *
     * The check asks every app on screen for its window, which means waiting on each app in
     * turn; for a held drag, arriving many times a second, doing that on every step made each
     * step wait on the launcher mid-animation. So a held drag is checked when it is pressed, and
     * then again whenever Android reports a different window coming to the front, or every
     * [MOVE_RECHECK_MS] regardless — never trusting a drag to stay out of a bank app for long.
     */
    fun apply(message: Protocol.Message, nowMs: Long = System.nanoTime() / 1_000_000): Result {
        val t = target ?: return Result.Unavailable
        if (message is Protocol.Message.Nav) {
            return if (t.navigate(message.action)) Result.Done else Result.Failed
        }
        // Lifting a held finger is always allowed, like going back or home: it can only let go.
        if (message is Protocol.Message.Touch && message.phase == TouchPhase.Up) {
            return if (t.touch(TouchPhase.Up, message.x, message.y)) Result.Done else Result.Failed
        }
        // If we cannot tell which apps are open, refuse rather than guess.
        val dragStep = message is Protocol.Message.Touch && message.phase == TouchPhase.Move
        val due = !dragStep || windowsChanged || checkedAt.let { it == null || nowMs - it >= MOVE_RECHECK_MS }
        if (due) {
            windowsChanged = false
            checkedAt = nowMs
            val visible = t.visiblePackages()
            if (visible == null || visible.any(SecureAppPolicy::isBlocked)) {
                if (message is Protocol.Message.Touch) t.cancelTouch()
                checkedAt = null // the next step checks again rather than ride on this one
                return Result.Blocked
            }
        }
        val ok = when (message) {
            is Protocol.Message.Tap -> t.tap(message.x, message.y, longPress = false)
            is Protocol.Message.LongPress -> t.tap(message.x, message.y, longPress = true)
            is Protocol.Message.GesturePath -> t.gesturePath(message.points, message.durationMs)
            is Protocol.Message.Touch -> t.touch(message.phase, message.x, message.y)
            else -> return Result.Failed
        }
        return if (ok) Result.Done else Result.Failed
    }

    /** Lifts any finger the helper is holding down on this screen. */
    fun cancelTouch() {
        target?.cancelTouch()
    }

    const val MOVE_RECHECK_MS = 750L
}
