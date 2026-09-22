package com.yattubhaa.app.ui.helper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yattubhaa.app.data.Prefs

/**
 * Gate in front of Helper mode. First time through it asks the helper to choose a PIN;
 * afterwards it asks for that PIN. This is a "keep it out of the way" gate, not a security
 * boundary — see Prefs.
 */
@Composable
fun HelperPinLockScreen(onUnlocked: () -> Unit, onCancel: () -> Unit) {
    val creating = remember { !Prefs.hasHelperPin }
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (creating) "Choose a helper PIN" else "Helper PIN",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(8); wrong = false },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = wrong,
            supportingText = { if (wrong) Text("That PIN is not right") },
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (creating) {
                    if (pin.length >= 4) {
                        Prefs.setHelperPin(pin)
                        onUnlocked()
                    }
                } else if (Prefs.checkHelperPin(pin)) {
                    onUnlocked()
                } else {
                    wrong = true
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            Text(if (creating) "Save PIN" else "Open", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            Text("Back", style = MaterialTheme.typography.labelLarge)
        }
    }
}
