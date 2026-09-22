package com.yattubhaa.app.ui.helpneeded

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.yattubhaa.app.pairing.PairingStore
import com.yattubhaa.app.session.NeedyPhase
import com.yattubhaa.app.session.SessionHub
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.Body
import com.yattubhaa.app.ui.components.ButtonKind
import com.yattubhaa.app.ui.components.Gap
import com.yattubhaa.app.ui.components.Heading
import com.yattubhaa.app.ui.components.Screen

/** What the person being helped sees from tapping Get Help until the session finishes. */
@Composable
fun NeedyHelpScreen(onExit: () -> Unit) {
    val session = remember {
        SessionHub.needy?.takeIf { !it.ended } ?: SessionHub.startNeedy(PairingStore.all().first())
    }
    val state by session.state.collectAsState()
    val leave = { SessionHub.endNeedy(); onExit() }

    when (state.phase) {
        NeedyPhase.Connecting -> Screen(
            buttons = { BigButton("Cancel", leave, kind = ButtonKind.Secondary) },
        ) {
            Heading("Connecting")
            Gap()
            Body("One moment please.")
        }

        NeedyPhase.Waiting -> Screen(
            buttons = { BigButton("Cancel", leave, kind = ButtonKind.Secondary) },
        ) {
            Heading("Tell ${state.helperName} this number")
            Gap(24)
            Text(
                text = "${state.code.take(3)} ${state.code.drop(3)}",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 52.sp, lineHeight = 64.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = state.code.toList().joinToString(" ") },
            )
            Gap(24)
            Body("Read it out loud on your call. Then wait.")
            if (state.message.isNotEmpty()) {
                Gap(16)
                Body(state.message)
            }
        }

        NeedyPhase.Secured -> SecuredScreen(state.helperName, leave)

        NeedyPhase.Ended -> Screen(
            buttons = { BigButton("OK", leave) },
        ) {
            Heading("Finished")
            Gap()
            Body(state.message.ifEmpty { "The session has ended." })
        }
    }
}
