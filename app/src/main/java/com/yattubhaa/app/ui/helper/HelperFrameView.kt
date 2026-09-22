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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yattubhaa.app.net.ControlState
import com.yattubhaa.app.net.NavAction
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.ButtonKind
import kotlin.math.max

/**
 * Their screen, fitted whole into the space available. Two modes:
 *  - **Point** (always available): tap anywhere to put a ring on that spot on their screen.
 *  - **Control** (only once they have said yes): tap sends a tap, drag sends a swipe.
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

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        Text(controlStatusLine(name, controlState, wantControl), style = MaterialTheme.typography.bodyLarge)

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
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
            // Going back or home always works, even with a bank app open: it is how you get out of
            // one. A 2x2 grid rather than one cramped row of four, since swiping down for the
            // notification shade is otherwise nearly impossible to trigger through the mirror.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Back", { onNavigate(NavAction.Back) }, Modifier.weight(1f), ButtonKind.Secondary, minHeight = 56.dp)
                BigButton("Home", { onNavigate(NavAction.Home) }, Modifier.weight(1f), ButtonKind.Secondary, minHeight = 56.dp)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Recent", { onNavigate(NavAction.Recents) }, Modifier.weight(1f), ButtonKind.Secondary, minHeight = 56.dp)
                BigButton("Notifications", { onNavigate(NavAction.Notifications) }, Modifier.weight(1f), ButtonKind.Secondary, minHeight = 56.dp)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (controlState == ControlState.On) {
                    BigButton(
                        if (wantControl) "Point instead" else "Tap for them",
                        { wantControl = !wantControl }, Modifier.weight(1f), ButtonKind.Secondary, minHeight = 56.dp,
                    )
                }
                BigButton(
                    "Give back", { wantControl = false; onReleaseControl() },
                    Modifier.weight(1f), ButtonKind.Secondary, minHeight = 56.dp,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (controlState) {
                    ControlState.Asked -> BigButton(
                        "Waiting\u2026", {}, Modifier.weight(1f), ButtonKind.Secondary, enabled = false, minHeight = 56.dp,
                    )
                    else -> BigButton(
                        "Ask to tap for them", onRequestControl, Modifier.weight(1f), ButtonKind.Secondary, minHeight = 56.dp,
                    )
                }
                BigButton("Clear ring", onClearPointer, Modifier.weight(1f), ButtonKind.Secondary, enabled = pointer != null, minHeight = 56.dp)
            }
        }
        BigButton("Stop", onStop, Modifier.padding(top = 8.dp), ButtonKind.Danger, minHeight = 56.dp)
    }
}

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
