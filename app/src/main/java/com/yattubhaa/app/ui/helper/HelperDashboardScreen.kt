package com.yattubhaa.app.ui.helper

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.yattubhaa.app.pairing.PairingStore
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.Body
import com.yattubhaa.app.ui.components.ButtonKind
import com.yattubhaa.app.ui.components.Gap
import com.yattubhaa.app.ui.components.Heading
import com.yattubhaa.app.ui.components.Screen

/** Helper mode home: who you can connect to right now, and setting up a new phone. */
@Composable
fun HelperDashboardScreen(onConnect: (pairingId: String) -> Unit, onExit: () -> Unit) {
    var pairing by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf(PairingStore.all()) }

    if (pairing) {
        PairNewPhoneScreen(onDone = { pairing = false; people = PairingStore.all() })
        return
    }

    Screen(
        buttons = {
            BigButton("Set up a new phone", onClick = { pairing = true })
            BigButton("Back", onExit, kind = ButtonKind.Secondary)
        },
    ) {
        Heading("Helper mode")
        Gap(16)
        if (people.isEmpty()) {
            Body("You have not set anyone up yet.")
        } else {
            Body("Tap someone to connect once they have told you their number.")
            Gap(24)
            people.forEach { person ->
                BigButton(person.name, onClick = { onConnect(person.id) }, modifier = Modifier.fillMaxWidth())
                Gap(12)
            }
        }
    }
}
