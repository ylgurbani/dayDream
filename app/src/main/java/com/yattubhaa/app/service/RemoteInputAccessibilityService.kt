package com.yattubhaa.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.yattubhaa.app.net.NavAction
import com.yattubhaa.app.net.Protocol
import com.yattubhaa.app.net.TouchPhase
import kotlin.math.roundToInt

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

    // A finger the helper is holding down right now (see touch()). Guarded by `this`: messages
    // arrive on the connection's thread, gesture callbacks and the watchdog on the main thread.
    private var heldStroke: GestureDescription.StrokeDescription? = null
    private var heldX = 0f
    private var heldY = 0f
    /** When Android last cut the helper's held finger short mid-drag, if the drag is still on. */
    private var cutShortAt: Long? = null
    private val main = Handler(Looper.getMainLooper())
    private val liftIfAbandoned = Runnable { cancelTouch() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        RemoteInput.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        RemoteInput.onWindowsChanged()
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
        cancelTouch()
        RemoteInput.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cancelTouch()
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

    /**
     * Press, hold and drag as one unbroken touch — what reordering a list or dragging an icon
     * needs, and what a one-shot [gesturePath] cannot do, because it only arrives once the
     * helper's finger has already lifted. Built from Android's continued strokes: each step is its
     * own gesture, marked to be continued, so Android keeps this finger down between steps
     * (however long the next one takes to arrive over the network) instead of lifting it.
     *
     * Down presses and holds for a little longer than this phone's own long-press delay (which he
     * may have lengthened in accessibility settings), so the item underneath is picked up; the
     * moves that follow are queued straight after it. If the helper's connection goes quiet
     * mid-drag, the finger is lifted on its own after a few seconds rather than left pressed.
     *
     * Android can also cut a held drag short itself: any real touch on his screen does, and so
     * does Android rebuilding the machinery that injects these touches, which it can do a few
     * times while settling just after the service is switched on. A real test showed the result
     * on one phone's launcher: the icon left floating mid-drag, both phones apparently frozen,
     * until the next separate tap — because every later step of that drag was then ignored. Now,
     * if the helper's finger is still moving once [RESUME_AFTER_MS] has passed (long enough for a
     * brief touch of his own to finish undisturbed), the finger is pressed again where the
     * helper's now is and carries on from there. That also clears whatever was left frozen.
     */
    @Synchronized
    override fun touch(phase: TouchPhase, x: Float, y: Float): Boolean {
        val (px, py) = toPixels(x, y)
        val stroke = when (phase) {
            TouchPhase.Down -> {
                cutShortAt = null
                heldStroke?.let { liftAt(it) }
                val holdMs = ViewConfiguration.getLongPressTimeout() + LONG_PRESS_MARGIN_MS
                GestureDescription.StrokeDescription(Path().apply { moveTo(px, py) }, 0, holdMs, true)
            }
            TouchPhase.Move, TouchPhase.Up -> {
                val previous = heldStroke
                if (previous == null) {
                    // Android cut this drag short while the helper's finger is still going; see
                    // the note on resuming below. Anything else (no drag at all) is a real failure.
                    val cut = cutShortAt ?: return false
                    if (phase == TouchPhase.Up) {
                        cutShortAt = null
                        return true // nothing left pressed to lift
                    }
                    if (SystemClock.uptimeMillis() - cut < RESUME_AFTER_MS) return true
                    cutShortAt = null
                    RemoteInput.onDragResumed()
                    GestureDescription.StrokeDescription(Path().apply { moveTo(px, py) }, 0, STEP_MS, true)
                } else {
                    // Must start exactly where the last step ended, or Android refuses to continue it.
                    val path = Path().apply { moveTo(heldX, heldY); lineTo(px, py) }
                    previous.continueStroke(path, 0, STEP_MS, phase == TouchPhase.Move)
                }
            }
        }
        main.removeCallbacks(liftIfAbandoned)
        if (phase == TouchPhase.Up) {
            heldStroke = null
        } else {
            heldStroke = stroke
            heldX = px
            heldY = py
            main.postDelayed(liftIfAbandoned, ABANDONED_AFTER_MS)
        }
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                // A real touch on his screen, or Android refusing the continuation: either way the
                // helper's finger is no longer down, and later moves must not pretend it is.
                synchronized(this@RemoteInputAccessibilityService) {
                    if (heldStroke === stroke) {
                        heldStroke = null
                        cutShortAt = SystemClock.uptimeMillis()
                        RemoteInput.onStepCancelled()
                    }
                }
            }
        }, main)
    }

    @Synchronized
    override fun cancelTouch() {
        cutShortAt = null
        main.removeCallbacks(liftIfAbandoned)
        heldStroke?.let { liftAt(it) }
        heldStroke = null
    }

    private fun liftAt(stroke: GestureDescription.StrokeDescription) {
        val lift = stroke.continueStroke(Path().apply { moveTo(heldX, heldY) }, 0, 1, false)
        runCatching { dispatchGesture(GestureDescription.Builder().addStroke(lift).build(), null, null) }
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

    /** Fractions of the screen to pixels, on the same full-screen size the picture is captured
     *  at ([ScreenSize], which follows rotation). Whole pixels: a continued stroke must start at
     *  exactly the point the previous one ended, compared as floats, and whole numbers survive
     *  Android's own path arithmetic unchanged where fractional ones may not. */
    private fun toPixels(fx: Float, fy: Float): Pair<Float, Float> {
        val m = ScreenSize.real(this)
        return (fx * (m.widthPixels - 1)).roundToInt().toFloat() to (fy * (m.heightPixels - 1)).roundToInt().toFloat()
    }

    private companion object {
        const val TAP_MS = 60L
        const val LONG_PRESS_MS = 700L
        const val LONG_PRESS_MARGIN_MS = 200L
        const val STEP_MS = 40L // how often the helper's phone sends a step of a held drag
        const val ABANDONED_AFTER_MS = 5000L
        const val RESUME_AFTER_MS = 300L
    }
}
