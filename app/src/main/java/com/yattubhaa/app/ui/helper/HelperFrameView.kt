package com.yattubhaa.app.ui.helper

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
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
import com.yattubhaa.app.net.TouchPhase
import com.yattubhaa.app.service.VideoDecoder
import com.yattubhaa.app.session.VideoStats
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.ButtonKind
import kotlin.math.max

/**
 * Their screen, fitted whole into the space available. Two modes:
 *  - **Point** (always available): tap anywhere to put a ring on that spot on their screen.
 *  - **Control** (only once they have said yes): tap sends a tap; a drag sends the whole path
 *    the finger took once it lifts, which is right for swipes and scrolls (their speed survives
 *    a laggy connection intact); and press, hold, then drag streams the finger live, as one
 *    unbroken touch on their phone — what picking something up and moving it (reordering a list,
 *    moving an icon) needs. Holding without moving is a long-press.
 *
 * The picture itself is decoded straight onto a `SurfaceView` by [videoDecoder] — it never
 * passes through Compose as a bitmap — with the pointer ring and gesture detection layered on
 * top in an ordinary transparent Box the same size, which draws above the SurfaceView by default
 * Android z-order (nothing here asks for `setZOrderOnTop`).
 *
 * Kept deliberately more compact than the rest of the app: this is the one screen the helper
 * (not the person being helped) uses, so it trades some of the app's usual large-text, big-button
 * accessibility margin for more room to actually see and work with the mirrored screen.
 *
 * Long-pressing the Good/Fair/Poor indicator shows a small stats overlay (what is arriving, how
 * late, what their phone is sending) — for reporting what happened in a real test, not for him.
 */
@Composable
fun HelperFrameView(
    name: String,
    frameSize: Pair<Int, Int>,
    videoDecoder: VideoDecoder,
    pointer: Pair<Float, Float>?,
    controlState: ControlState,
    connectionQuality: ConnectionQuality,
    stats: VideoStats?,
    onPoint: (Float, Float) -> Unit,
    onClearPointer: () -> Unit,
    onTap: (Float, Float) -> Unit,
    onGesturePath: (List<Protocol.Point>, Int) -> Unit,
    onTouch: (TouchPhase, Float, Float) -> Unit,
    onRequestControl: () -> Unit,
    onReleaseControl: () -> Unit,
    onNavigate: (NavAction) -> Unit,
    onStop: () -> Unit,
) {
    var wantControl by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var holdingAt by remember { mutableStateOf<Offset?>(null) }
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
            ConnectionIndicator(
                connectionQuality,
                Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { showStats = !showStats }) },
            )
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
                                detectControlGestures(
                                    onTap = { p -> onTap(p.x / size.width, p.y / size.height) },
                                    onPath = { points, ms ->
                                        onGesturePath(
                                            points.map { Protocol.Point(it.x / size.width, it.y / size.height) },
                                            ms,
                                        )
                                    },
                                    onTouch = { phase, p -> onTouch(phase, p.x / size.width, p.y / size.height) },
                                    onHolding = { holdingAt = it },
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
                            // Where the finger held down on their screen is right now.
                            holdingAt?.let { drawCircle(Color(0x802962FF), radius = 20.dp.toPx(), center = it) }
                        },
                )
                if (showStats && stats != null) {
                    Text(
                        statsText(stats),
                        style = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, color = Color.White),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(Color(0xB0000000), RoundedCornerShape(4.dp))
                            .padding(4.dp),
                    )
                }
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
 *  quick read on whether the picture might be lagging, not a diagnostics panel (that is the
 *  hidden stats overlay, behind a long-press on this). Reused straight from what
 *  [com.yattubhaa.app.service.CongestionController] already decided on the other phone. */
@Composable
private fun ConnectionIndicator(quality: ConnectionQuality, modifier: Modifier = Modifier) {
    val (color, label) = when (quality) {
        ConnectionQuality.Good -> Color(0xFF2E7D32) to "Good"
        ConnectionQuality.Fair -> Color(0xFFF9A825) to "Fair"
        ConnectionQuality.Poor -> Color(0xFFC62828) to "Poor"
    }
    Row(modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
    ControlState.On -> if (wantControl) {
        "Tap to tap for them. Drag to swipe. Hold, then drag, to move something."
    } else {
        "$name said yes. Tap to point, or switch to tapping for them."
    }
    ControlState.Blocked -> "Paused: $name has a bank or payment app open."
    ControlState.Unavailable -> "$name said yes, but needs to turn on a setting first."
}

private fun statsText(s: VideoStats): String = buildString {
    append("${s.codec.label} ${s.width}×${s.height} · ${s.fps} fps · ${s.kbps} kbps\n")
    append("queue +${s.queueDelayMs} ms · lost ${s.lostFrames} · keyframes ${s.keyframes} (asked ${s.keyframeRequests})")
    if (s.decoderRestarts > 0) append(" · restarts ${s.decoderRestarts}")
    s.sender?.let {
        append("\nsent: tier ${it.tier} · ${it.bitrateKbps} kbps · rtt ${it.rttMs} ms · dropped ${it.droppedFrames}")
        if (it.encoderSetup > 0) append(" · setup ${it.encoderSetup}")
        if (it.inputSteps > 0) {
            append("\ninput: ${it.inputSteps} steps · failed ${it.inputFailed} · slowest ${it.inputSlowestMs} ms")
            append(" · drags cut short ${it.inputCancelled}, resumed ${it.inputResumed}")
        }
    }
}

private enum class Early { Lifted, Moved }

/**
 * Tap, swipe, or press-and-hold — whichever happens first: the finger lifting, moving past a
 * small threshold, or staying put for [HOLD_MS].
 *  - A swipe is sampled along the way (throttled to [SAMPLE_INTERVAL_MS], always keeping the
 *    exact lift-off point) and sent as one path once it lifts, so its speed survives any lag.
 *  - A hold is streamed live: pressed down on their phone straight away, moved as the finger
 *    moves, lifted when it lifts — and lifted even if this gesture is interrupted part way.
 */
private suspend fun PointerInputScope.detectControlGestures(
    onTap: (Offset) -> Unit,
    onPath: (List<Offset>, Int) -> Unit,
    onTouch: (TouchPhase, Offset) -> Unit,
    onHolding: (Offset?) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val start = System.currentTimeMillis()
        var lastPos = down.position
        when (withTimeoutOrNull(HOLD_MS) { awaitLiftOrMove(down) { lastPos = it } }) {
            Early.Lifted -> onTap(down.position)
            Early.Moved -> {
                val points = mutableListOf(down.position, lastPos)
                var lastSampleAt = System.currentTimeMillis()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    lastPos = change.position
                    val now = System.currentTimeMillis()
                    if (now - lastSampleAt >= SAMPLE_INTERVAL_MS) {
                        points += lastPos
                        lastSampleAt = now
                    }
                    change.consume()
                }
                if (points.last() != lastPos) points += lastPos // the exact lift-off point, always
                onPath(points, max(1, (System.currentTimeMillis() - start).toInt()))
            }
            null -> {
                onTouch(TouchPhase.Down, down.position)
                onHolding(down.position)
                var lastSentAt = System.currentTimeMillis()
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        lastPos = change.position
                        onHolding(lastPos)
                        val now = System.currentTimeMillis()
                        if (now - lastSentAt >= STEP_INTERVAL_MS) {
                            onTouch(TouchPhase.Move, lastPos)
                            lastSentAt = now
                        }
                    }
                } finally {
                    onTouch(TouchPhase.Up, lastPos)
                    onHolding(null)
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitLiftOrMove(down: PointerInputChange, onPosition: (Offset) -> Unit): Early {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return Early.Lifted
        onPosition(change.position)
        change.consume()
        if (!change.pressed) return Early.Lifted
        if ((change.position - down.position).getDistance() > SWIPE_THRESHOLD_PX) return Early.Moved
    }
}

private const val SWIPE_THRESHOLD_PX = 24f
private const val SAMPLE_INTERVAL_MS = 30L
private const val HOLD_MS = 450L
/** Matches the step length the other phone plays each move back at (RemoteInputAccessibilityService). */
private const val STEP_INTERVAL_MS = 40L
