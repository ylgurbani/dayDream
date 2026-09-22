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
                    Heading("Connected to ${state.name}")
                    Gap()
                    Body("Connected privately. Their screen will appear here once they choose to share it.")
                }
            } else {
                HelperFrameView(
                    name = state.name,
                    frameSize = frameSize,
                    videoDecoder = session.videoDecoder,
                    pointer = state.pointer,
                    controlState = state.controlState,
                    onPoint = { x, y -> session.point(x, y) },
                    onClearPointer = { session.clearPointer() },
                    onTap = { x, y -> session.tap(x, y) },
                    onSwipe = { x1, y1, x2, y2, ms -> session.swipe(x1, y1, x2, y2, ms) },
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
