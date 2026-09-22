package com.yattubhaa.app.ui.helper

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.yattubhaa.app.pairing.PairingStore
import com.yattubhaa.app.session.SessionHub
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.Body
import com.yattubhaa.app.ui.components.ButtonKind
import com.yattubhaa.app.ui.components.Gap
import com.yattubhaa.app.ui.components.Heading
import com.yattubhaa.app.ui.components.Screen

/** The helper types the six digits the other person read out, then connects. */
@Composable
fun HelperCodeScreen(pairingId: String, tryAgain: Boolean, onConnect: () -> Unit, onBack: () -> Unit) {
    val pairing = remember { PairingStore.find(pairingId) }
    var code by remember { mutableStateOf("") }

    if (pairing == null) {
        onBack()
        return
    }
    Screen(
        buttons = {
            BigButton("Connect", enabled = code.length == 6, onClick = {
                SessionHub.startHelper(pairing, code)
                onConnect()
            })
            BigButton("Back", onBack, kind = ButtonKind.Secondary)
        },
    ) {
        Heading("Connect to ${pairing.name}")
        Gap(16)
        Body(
            if (tryAgain) "That number did not match. Ask ${pairing.name} to read it out again."
            else "Ask ${pairing.name} to tap Get Help and read you the number on their screen.",
        )
        Gap(24)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("6 digit number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
