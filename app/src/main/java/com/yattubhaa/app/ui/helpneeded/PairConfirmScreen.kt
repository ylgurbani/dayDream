package com.yattubhaa.app.ui.helpneeded

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import com.yattubhaa.app.pairing.PairingLink
import com.yattubhaa.app.pairing.PairingStore
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.Body
import com.yattubhaa.app.ui.components.ButtonKind
import com.yattubhaa.app.ui.components.Gap
import com.yattubhaa.app.ui.components.Heading
import com.yattubhaa.app.ui.components.Screen

/**
 * Shown when a pairing link is tapped. A link can be fired at the phone by any web page or app,
 * and this is the same setup remote-access scammers use, so nothing is saved until the person
 * has read who is asking and said yes. Saying yes replaces any earlier helper.
 */
@Composable
fun PairConfirmScreen(link: PairingLink, onDone: () -> Unit) {
    val alreadyPaired = PairingStore.all().isNotEmpty()
    Screen(
        buttons = {
            BigButton("Yes, connect", onClick = {
                PairingStore.clear()
                PairingStore.add(PairingStore.newRecord(link.helperName, link.relayUrl, link.secret))
                onDone()
            })
            BigButton("No", onClick = onDone, kind = ButtonKind.Secondary)
        },
    ) {
        Heading("Connect to ${link.helperName}?")
        Gap(16)
        Body("Only tap Yes if ${link.helperName} just asked you to, on your call.")
        if (alreadyPaired) {
            Gap(12)
            Body("This will replace the family member you connected before.")
        }
        Gap(16)
        Text(
            text = link.relayUrl.substringAfter("://"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}
