package com.yattubhaa.app.ui.helper

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yattubhaa.app.net.ControlState
import com.yattubhaa.app.net.NavAction
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.ButtonKind
import kotlin.math.max

/**
 * Their screen, fitted whole into the space available. Two modes:
 *  - **Point** (always available): tap anywhere to put a ring on that spot on their screen.
 *  - **Control** (only once they have said yes): tap sends a tap, drag sends a swipe.
 *
 * Kept deliberately more compact than the rest of the app: this is the one screen the helper
 * (not the person being helped) uses, so it trades some of the app's usual large-text, big-button
 * accessibility margin for more room to actually see and work with the mirrored screen.
 */
@Composable
fun HelperFrameView(
    name: String,
    frame: Bitmap,
    pointer: Pair<Float, Float>?,
    controlState: ControlState,
    onPoint: (Float, Float) -> Unit,
    onClearPointer: () -> Unit,
    onTap: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Int) -> Unit,
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
        Text(controlStatusLine(name, controlState, wantControl), style = STATUS_STYLE)

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val aspect = frame.width.toFloat() / frame.height
            val fitWidth: Dp
            val fitHeight: Dp
            if (maxWidth / maxHeight < aspect) {
                fitWidth = maxWidth; fitHeight = maxWidth / aspect
            } else {
                fitHeight = maxHeight; fitWidth = maxHeight * aspect
            }
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "$name's screen.",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(fitWidth, fitHeight)
                    .pointerInput(controlling) {
                        if (controlling) {
                            detectDragOrTap(
                                onTap = { p -> onTap(p.x / size.width, p.y / size.height) },
                                onSwipe = { s, e, ms ->
                                    onSwipe(s.x / size.width, s.y / size.height, e.x / size.width, e.y / size.height, ms)
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

/** A short touch is a tap; a longer move is a swipe from where it started to where it ended. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDragOrTap(
    onTap: (Offset) -> Unit,
    onSwipe: (Offset, Offset, Int) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val start = System.currentTimeMillis()
        var last = down.position
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if ((change.position - down.position).getDistance() > SWIPE_THRESHOLD_PX) moved = true
            last = change.position
            change.consume()
        }
        val durationMs = max(1, (System.currentTimeMillis() - start).toInt())
        if (moved) onSwipe(down.position, last, durationMs) else onTap(down.position)
    }
}

private const val SWIPE_THRESHOLD_PX = 24f
