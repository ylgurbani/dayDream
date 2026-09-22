package com.yattubhaa.app.ui.helper

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yattubhaa.app.net.ConnectionQuality
import com.yattubhaa.app.net.ControlState
import com.yattubhaa.app.net.NavAction
import com.yattubhaa.app.net.Protocol
import com.yattubhaa.app.service.VideoDecoder
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.ButtonKind
import kotlin.math.max

/**
 * Their screen, fitted whole into the space available. Two modes:
 *  - **Point** (always available): tap anywhere to put a ring on that spot on their screen.
 *  - **Control** (only once they have said yes): tap sends a tap, drag sends the whole path the
 *    finger actually took — not just its start and end — so things that respond to a drag's
 *    shape (dragging an item past its neighbours to reorder a list, say) work properly, not just
 *    a straight-line approximation between two points.
 *
 * The picture itself is decoded straight onto a `SurfaceView` by [videoDecoder] — it never
 * passes through Compose as a bitmap — with the pointer ring and gesture detection layered on
 * top in an ordinary transparent Box the same size, which draws above the SurfaceView by default
 * Android z-order (nothing here asks for `setZOrderOnTop`).
 *
 * Kept deliberately more compact than the rest of the app: this is the one screen the helper
 * (not the person being helped) uses, so it trades some of the app's usual large-text, big-button
 * accessibility margin for more room to actually see and work with the mirrored screen.
 */
@Composable
fun HelperFrameView(
    name: String,
    frameSize: Pair<Int, Int>,
    videoDecoder: VideoDecoder,
    pointer: Pair<Float, Float>?,
    controlState: ControlState,
    connectionQuality: ConnectionQuality,
    onPoint: (Float, Float) -> Unit,
    onClearPointer: () -> Unit,
    onTap: (Float, Float) -> Unit,
    onGesturePath: (List<Protocol.Point>, Int) -> Unit,
    onRequestControl: () -> Unit,
    onReleaseControl: () -> Unit,
    onNavigate: (NavAction) -> Unit,
    onStop: () -> Unit,
) {
    var wantControl by remember { mutableStateOf(false) }
    val controlling = controlState == ControlState.On && wantControl
    val hasRing = pointer != null

    fun switchToControl() {
        // A ring left over from Point mode has no meaning once tapping for them starts, and
        // there would be no way to clear it again until control is given back entirely.
        if (hasRing) onClearPointer()
        wantControl = true
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                controlStatusLine(name, controlState, wantControl),
                style = STATUS_STYLE,
                modifier = Modifier.weight(1f),
            )
            ConnectionIndicator(connectionQuality)
        }

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val aspect = frameSize.first.toFloat() / frameSize.second
            val fitWidth: Dp
            val fitHeight: Dp
            if (maxWidth / maxHeight < aspect) {
                fitWidth = maxWidth; fitHeight = maxWidth / aspect
            } else {
                fitHeight = maxHeight; fitWidth = maxHeight * aspect
            }
            Box(Modifier.size(fitWidth, fitHeight)) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    videoDecoder.attachSurface(holder.surface)
                                }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    videoDecoder.detachSurface()
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(controlling) {
                            if (controlling) {
                                detectDragOrTap(
                                    onTap = { p -> onTap(p.x / size.width, p.y / size.height) },
                                    onPath = { points, ms ->
                                        onGesturePath(
                                            points.map { Protocol.Point(it.x / size.width, it.y / size.height) },
                                            ms,
                                        )
                                    },
                                )
                            } else {
                                detectTapGestures { p -> onPoint(p.x / size.width, p.y / size.height) }
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            if (!controlling && pointer != null) {
                                drawCircle(
                                    color = Color.Red,
                                    radius = 24.dp.toPx(),
                                    center = Offset(pointer.first * size.width, pointer.second * size.height),
                                    style = Stroke(width = 5.dp.toPx()),
                                )
                            }
                        },
                )
            }
        }

        val hasControl = controlState == ControlState.On || controlState == ControlState.Blocked
        if (hasControl) {
            // Going back or home always works, even with a bank app open: it is how you get out
            // of one.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NavButton("Back") { onNavigate(NavAction.Back) }
                NavButton("Home") { onNavigate(NavAction.Home) }
                NavButton("Recent") { onNavigate(NavAction.Recents) }
                NavButton("Notif.") { onNavigate(NavAction.Notifications) }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (controlState == ControlState.On) {
                    NavButton(if (wantControl) "Point instead" else "Tap for them") {
                        if (wantControl) wantControl = false else switchToControl()
                    }
                }
                NavButton("Clear ring", enabled = hasRing, onClick = onClearPointer)
                NavButton("Give back") { wantControl = false; onReleaseControl() }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (controlState) {
                    ControlState.Asked -> NavButton("Waiting…", enabled = false) {}
                    else -> NavButton("Ask to tap for them", onClick = onRequestControl)
                }
                NavButton("Clear ring", enabled = hasRing, onClick = onClearPointer)
            }
        }
        BigButton(
            "Stop", onStop, Modifier.padding(top = 6.dp), ButtonKind.Danger,
            minHeight = COMPACT_HEIGHT, textStyle = COMPACT_STYLE,
        )
    }
}

@Composable
private fun RowScope.NavButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    BigButton(
        text, onClick, Modifier.weight(1f), ButtonKind.Secondary,
        enabled = enabled, minHeight = COMPACT_HEIGHT, textStyle = COMPACT_STYLE,
    )
}

/** A small dot and word, not a number or a graph — this screen is for the helper, who wants a
 *  quick read on whether the picture might be lagging, not a diagnostics panel. Reused straight
 *  from what [com.yattubhaa.app.service.BitrateAdapter] already decided on the other phone,
 *  rather than this screen trying to work anything out for itself. */
@Composable
private fun ConnectionIndicator(quality: ConnectionQuality) {
    val (color, label) = when (quality) {
        ConnectionQuality.Good -> Color(0xFF2E7D32) to "Good"
        ConnectionQuality.Fair -> Color(0xFFF9A825) to "Fair"
        ConnectionQuality.Poor -> Color(0xFFC62828) to "Poor"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = TextStyle(fontSize = 12.sp), color = Color.Gray)
    }
}

private val COMPACT_HEIGHT = 40.dp
private val COMPACT_STYLE = TextStyle(fontSize = 14.sp, lineHeight = 18.sp)
private val STATUS_STYLE = TextStyle(fontSize = 15.sp, lineHeight = 19.sp)

private fun controlStatusLine(name: String, state: ControlState, wantControl: Boolean) = when (state) {
    ControlState.Off -> "$name's screen. Tap to point."
    ControlState.Asked -> "Waiting for $name to say yes…"
    ControlState.On -> if (wantControl) "Tap to tap for them. Drag to swipe." else "$name said yes. Tap to point, or switch to tapping for them."
    ControlState.Blocked -> "Paused: $name has a bank or payment app open."
    ControlState.Unavailable -> "$name said yes, but needs to turn on a setting first."
}

/** A short touch is a tap; a longer move is a drag, sampled along the way (not just start and
 *  end) so the actual shape of the gesture survives — throttled to [SAMPLE_INTERVAL_MS] so a
 *  long or fast drag does not turn into hundreds of points, while the exact point the finger
 *  lifted at is always kept, sampled or not. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDragOrTap(
    onTap: (Offset) -> Unit,
    onPath: (List<Offset>, Int) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val start = System.currentTimeMillis()
        val points = mutableListOf(down.position)
        var lastPos = down.position
        var lastSampleAt = start
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if ((change.position - down.position).getDistance() > SWIPE_THRESHOLD_PX) moved = true
            lastPos = change.position
            val now = System.currentTimeMillis()
            if (now - lastSampleAt >= SAMPLE_INTERVAL_MS) {
                points += lastPos
                lastSampleAt = now
            }
            change.consume()
        }
        if (points.last() != lastPos) points += lastPos // the exact lift-off point, always
        val durationMs = max(1, (System.currentTimeMillis() - start).toInt())
        if (moved) onPath(points, durationMs) else onTap(down.position)
    }
}

private const val SWIPE_THRESHOLD_PX = 24f
private const val SAMPLE_INTERVAL_MS = 30L
