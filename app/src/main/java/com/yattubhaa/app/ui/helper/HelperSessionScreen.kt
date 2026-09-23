package com.yattubhaa.app.ui.helper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.yattubhaa.app.session.HelperPhase
import com.yattubhaa.app.session.SessionHub
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.Body
import com.yattubhaa.app.ui.components.ButtonKind
import com.yattubhaa.app.ui.components.Gap
import com.yattubhaa.app.ui.components.Heading
import com.yattubhaa.app.ui.components.Screen

/** The helper's live view: their screen (once shared), tap anywhere on it to point there. */
@Composable
fun HelperSessionScreen(onExit: (wrongCode: Boolean) -> Unit) {
    val session = remember { SessionHub.helper }
    if (session == null) {
        onExit(false)
        return
    }
    val state by session.state.collectAsState()
    val stats by session.videoStats.collectAsState()
    val leave = { SessionHub.endHelper(); onExit(state.wrongCode) }

    when (state.phase) {
        HelperPhase.Connecting, HelperPhase.WaitingForPhone -> Screen(
            buttons = { BigButton("Cancel", leave, kind = ButtonKind.Secondary) },
        ) {
            Heading(if (state.phase == HelperPhase.Connecting) "Connecting" else "Waiting for ${state.name}")
            Gap()
            Body("This screen will change as soon as ${state.name} connects.")
        }

        HelperPhase.Secured -> {
            val frameSize = state.frameSize
            if (frameSize == null) {
                Screen(buttons = { BigButton("Stop", leave, kind = ButtonKind.Danger) }) {
                    Heading(if (state.sharingStarted) "Connecting to their screen" else "Connected to ${state.name}")
                    Gap()
                    Body(
                        if (state.sharingStarted) {
                            "They have started sharing. The picture can take a few moments to " +
                                "arrive — this is not stuck."
                        } else {
                            "Connected privately. Their screen will appear here once they choose to share it."
                        },
                    )
                }
            } else {
                HelperFrameView(
                    name = state.name,
                    frameSize = frameSize,
                    videoDecoder = session.videoDecoder,
                    pointer = state.pointer,
                    controlState = state.controlState,
                    connectionQuality = state.connectionQuality,
                    stats = stats,
                    onPoint = { x, y -> session.point(x, y) },
                    onClearPointer = { session.clearPointer() },
                    onTap = { x, y -> session.tap(x, y) },
                    onGesturePath = { points, ms -> session.gesturePath(points, ms) },
                    onTouch = { phase, x, y -> session.touch(phase, x, y) },
                    onRequestControl = { session.requestControl() },
                    onReleaseControl = { session.releaseControl() },
                    onNavigate = { session.navigate(it) },
                    onStop = leave,
                )
            }
        }

        HelperPhase.Ended -> Screen(
            buttons = { BigButton(if (state.wrongCode) "Try again" else "OK", leave) },
        ) {
            Heading(if (state.wrongCode) "Wrong number" else "Finished")
            Gap()
            Body(state.message.ifEmpty { "The session has ended." })
        }
    }
}
