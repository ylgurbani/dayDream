package com.yattubhaa.app.service

import com.yattubhaa.app.net.NavAction
import com.yattubhaa.app.net.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    fun navigate(action: NavAction): Boolean
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

    fun attach(t: RemoteInputTarget) {
        target = t
        _connected.value = true
    }

    fun detach(t: RemoteInputTarget) {
        if (target === t) {
            target = null
            _connected.value = false
        }
    }

    /**
     * Applies one message, unless a banking or payment app is on screen (or we cannot tell). Going back, home or to
     * recents is always allowed, because that is how someone gets out of such an app.
     */
    fun apply(message: Protocol.Message): Result {
        val t = target ?: return Result.Unavailable
        if (message is Protocol.Message.Nav) {
            return if (t.navigate(message.action)) Result.Done else Result.Failed
        }
        // If we cannot tell which apps are open, refuse rather than guess.
        val visible = t.visiblePackages() ?: return Result.Blocked
        if (visible.any(SecureAppPolicy::isBlocked)) return Result.Blocked
        val ok = when (message) {
            is Protocol.Message.Tap -> t.tap(message.x, message.y, longPress = false)
            is Protocol.Message.LongPress -> t.tap(message.x, message.y, longPress = true)
            is Protocol.Message.GesturePath -> t.gesturePath(message.points, message.durationMs)
            else -> return Result.Failed
        }
        return if (ok) Result.Done else Result.Failed
    }
}
