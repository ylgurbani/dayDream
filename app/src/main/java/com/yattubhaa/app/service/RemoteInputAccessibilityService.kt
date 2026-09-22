package com.yattubhaa.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.yattubhaa.app.data.Prefs
import com.yattubhaa.app.net.NavAction
import com.yattubhaa.app.net.Protocol

/**
 * Lets a connected helper tap, swipe and press Back/Home on this phone, but only when the person
 * here has said yes for the current session (that decision lives in NeedySession, and this class
 * is only ever reached through [RemoteInput]).
 *
 * It never reads what is on the screen. It asks Android only which apps have a window showing (a
 * package name, nothing else), so that taps are never sent while a banking or payment app is open.
 * It is off unless the person switches it on in Settings > Accessibility.
 */
class RemoteInputAccessibilityService : AccessibilityService(), RemoteInputTarget {
    /** Fallback only: the last app whose window came to the front. */
    @Volatile
    private var lastForeground: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        RemoteInput.attach(this)
        // From here on, a future request only needs to wait for a reconnect, never Settings again.
        Prefs.accessibilityGrantedSinceLastOff = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        if (SecureAppPolicy.countsAsForeground(pkg, packageName)) lastForeground = pkg
    }

    override fun onInterrupt() = Unit

    override fun visiblePackages(): List<String>? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (apps.isEmpty()) return lastForeground?.let { listOf(it) }
        val packages = ArrayList<String>()
        for (window in apps) {
            val root = window.root ?: return null // a window we cannot identify: do not guess
            val pkg = root.packageName?.toString()
            @Suppress("DEPRECATION") root.recycle()
            packages += pkg ?: return null
        }
        return packages
    }

    override fun onUnbind(intent: Intent?): Boolean {
        RemoteInput.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        RemoteInput.detach(this)
        super.onDestroy()
    }

    override fun tap(x: Float, y: Float, longPress: Boolean): Boolean {
        val (px, py) = toPixels(x, y)
        // A path with only a starting point is a finger that touches down and lifts without moving.
        return dispatch(Path().apply { moveTo(px, py) }, if (longPress) LONG_PRESS_MS else TAP_MS)
    }

    /** Traces the actual path a dragged finger took, not just a straight line between its ends
     *  — needed for anything that reads the shape or direction of the drag itself, such as
     *  reordering a list by dragging an item past its neighbours. [points] always has at least
     *  two entries (see [RemoteInputTarget.gesturePath]). */
    override fun gesturePath(points: List<Protocol.Point>, durationMs: Int): Boolean {
        val path = Path()
        val (startX, startY) = toPixels(points[0].x, points[0].y)
        path.moveTo(startX, startY)
        for (i in 1 until points.size) {
            val (px, py) = toPixels(points[i].x, points[i].y)
            path.lineTo(px, py)
        }
        return dispatch(path, durationMs.toLong())
    }

    override fun navigate(action: NavAction): Boolean = performGlobalAction(
        when (action) {
            NavAction.Back -> GLOBAL_ACTION_BACK
            NavAction.Home -> GLOBAL_ACTION_HOME
            NavAction.Recents -> GLOBAL_ACTION_RECENTS
            NavAction.Notifications -> GLOBAL_ACTION_NOTIFICATIONS
        },
    )

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Fractions of the screen to pixels, on the same full-screen size the picture is captured at. */
    private fun toPixels(fx: Float, fy: Float): Pair<Float, Float> {
        val m = resources.displayMetrics
        return (fx * (m.widthPixels - 1)) to (fy * (m.heightPixels - 1))
    }

    private companion object {
        const val TAP_MS = 60L
        const val LONG_PRESS_MS = 700L
    }
}
