package com.yattubhaa.app.ui.helper

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.yattubhaa.app.BuildConfig
import com.yattubhaa.app.data.Prefs
import com.yattubhaa.app.net.Crypto
import com.yattubhaa.app.pairing.PairingLink
import com.yattubhaa.app.pairing.PairingStore
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.Body
import com.yattubhaa.app.ui.components.ButtonKind
import com.yattubhaa.app.ui.components.Gap
import com.yattubhaa.app.ui.components.Heading
import com.yattubhaa.app.ui.components.Screen

/**
 * Makes a new pairing and shows the link to send. Nothing is sent anywhere by the app itself:
 * the helper chooses how (WhatsApp, SMS, showing the QR code) and the other phone must still
 * confirm before it stores anything.
 */
@Composable
fun PairNewPhoneScreen(onDone: () -> Unit) {
    var label by rememberSaveable { mutableStateOf("") }
    var helperName by rememberSaveable { mutableStateOf(Prefs.helperName) }
    var relay by rememberSaveable { mutableStateOf(Prefs.relayUrl) }
    val relayOk = PairingLink.normalizeRelayUrl(relay, allowCleartext = BuildConfig.DEBUG)
    var created by remember { mutableStateOf<String?>(null) } // the share URL once made; holds the secret, so never saved
    val context = LocalContext.current

    if (created == null) {
        Screen(
            buttons = {
                BigButton(
                    "Make the link",
                    enabled = label.isNotBlank() && helperName.isNotBlank() && relayOk != null,
                    onClick = {
                        val relayUrl = relayOk ?: return@BigButton
                        Prefs.helperName = helperName.trim()
                        Prefs.relayUrl = relayUrl
                        val secret = Crypto.randomBytes(PairingLink.SECRET_BYTES)
                        PairingStore.add(PairingStore.newRecord(label.trim(), relayUrl, secret))
                        created = PairingLink(helperName.trim(), relayUrl, secret).toShareUrl()
                    },
                )
                BigButton("Cancel", onDone, kind = ButtonKind.Secondary)
            },
        ) {
            Heading("Set up a new phone")
            Gap(16)
            Body("Who are you setting this up for, and what should they see your name as?")
            Gap(24)
            OutlinedTextField(
                value = label, onValueChange = { label = it },
                label = { Text("Their name, e.g. Grandad") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Gap(12)
            OutlinedTextField(
                value = helperName, onValueChange = { helperName = it },
                label = { Text("Your name, shown to them") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Gap(12)
            OutlinedTextField(
                value = relay, onValueChange = { relay = it.trim() },
                label = { Text("Relay address, e.g. wss://relay.example.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = relay.isNotEmpty() && relayOk == null,
                supportingText = { if (relay.isNotEmpty() && relayOk == null) Text("It must start with wss://") },
            )
        }
    } else {
        val url = created!!
        val qr by produceState<Bitmap?>(initialValue = null, url) { value = renderQrCode(url) }
        Screen(
            buttons = {
                BigButton("Send the link", onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(send, "Send link"))
                })
                BigButton("Done", onDone, kind = ButtonKind.Secondary)
            },
        ) {
            Heading("Send this to $label")
            Gap(16)
            Body("Send the link over WhatsApp, or let them scan this code, then have them tap it on their phone.")
            Gap(24)
            qr?.let {
                Image(it.asImageBitmap(), contentDescription = "QR code for the pairing link", modifier = Modifier.padding(8.dp))
            }
            Gap(16)
            Text(url, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
        }
    }
}

private fun renderQrCode(text: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) {
        bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    return bitmap
}
