package com.yattubhaa.app.ui.helpneeded

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.yattubhaa.app.data.Prefs
import com.yattubhaa.app.service.ControlCapability
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.ButtonKind

/**
 * The only screen the person being helped normally sees. One question, one giant answer.
 *
 * Layout rule for every screen in this app: explanatory content scrolls, but the button he
 * needs is pinned below it, outside the scroll area, so no font size can push it off screen.
 *
 * Helper mode is reachable only through a long-press on the small label at the very bottom:
 * deliberately not a button and not something a first-time user will stumble into.
 */
@Composable
fun HelpNeededHomeScreen(onGetHelp: () -> Unit, onOpenHelperMode: () -> Unit) {
    val context = LocalContext.current
    var remoteControlOn by remember { mutableStateOf(ControlCapability.isOffered(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { remoteControlOn = ControlCapability.isOffered(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.SupportAgent,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(120.dp).fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Need help with your phone?",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onGetHelp,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = "Get Help",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
        }

        // Only there while remote tap and swipe is switched on, so it can always be switched off
        // again in one tap, without going back into Settings.
        if (remoteControlOn) {
            Spacer(Modifier.height(12.dp))
            BigButton(
                text = "Turn off remote control",
                onClick = {
                    ControlCapability.setOffered(context, false)
                    Prefs.accessibilityGrantedSinceLastOff = false
                    remoteControlOn = false
                },
                kind = ButtonKind.Secondary,
                minHeight = 64.dp,
            )
        }

        LongPressHiddenLabel(onLongPress = onOpenHelperMode)
    }
}

/** A quiet, unlabelled long-press target: the deliberately hidden door into Helper mode. */
@Composable
private fun LongPressHiddenLabel(onLongPress: () -> Unit) {
    Text(
        text = "Yattu Bhaa",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
        modifier = Modifier
            .padding(top = 16.dp, bottom = 4.dp)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onLongPress() })
            },
    )
}
