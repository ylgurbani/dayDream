package com.yattubhaa.app.ui.helpneeded

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.yattubhaa.app.service.ScreenShareService
import com.yattubhaa.app.service.ShareState
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.Body
import com.yattubhaa.app.ui.components.ButtonKind
import com.yattubhaa.app.ui.components.Gap
import com.yattubhaa.app.ui.components.Heading
import com.yattubhaa.app.ui.components.MessageScreen
import com.yattubhaa.app.ui.components.Screen

/**
 * The helper is connected. Nothing on this phone is visible to them until the person here
 * taps the button and then accepts Android's own "start recording" question.
 */
@Composable
fun SecuredScreen(helperName: String, onStop: () -> Unit) {
    val context = LocalContext.current
    val sharing by ShareState.active.collectAsState()
    var needOverlaySetting by remember { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenShareService.start(context, result.resultCode, data)
        }
    }
    // Coming back from the settings screen: carry on if the setting is now on.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (needOverlaySetting && Settings.canDrawOverlays(context)) needOverlaySetting = false
    }

    when {
        needOverlaySetting -> MessageScreen(
            title = "One setting first",
            body = "This lets a red Stop button stay on your screen. On the next screen, " +
                "find Yattu Bhaa and switch it on, then come back.",
            primary = "Open the setting",
            onPrimary = { context.startActivity(overlaySettings(context)) },
            secondary = "Back",
            onSecondary = { needOverlaySetting = false },
        )

        sharing -> Screen(
            buttons = { BigButton("Stop", onStop, kind = ButtonKind.Danger) },
        ) {
            Heading("$helperName can see your screen")
            Gap()
            Body("Now go to the app you need help with. A red Stop button stays on your screen.")
        }

        else -> Screen(
            buttons = {
                BigButton("Let $helperName see my screen", onClick = {
                    if (Settings.canDrawOverlays(context)) {
                        val manager = context.getSystemService(MediaProjectionManager::class.java)
                        consent.launch(manager.createScreenCaptureIntent())
                    } else {
                        needOverlaySetting = true
                    }
                })
                BigButton("Stop", onStop, kind = ButtonKind.Danger)
            },
        ) {
            Heading("$helperName is connected")
            Gap()
            Body("Your connection is private. Tap the top button to let $helperName see your screen. You can stop at any time.")
        }
    }
}

private fun overlaySettings(context: Context) = Intent(
    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
    Uri.parse("package:${context.packageName}"),
)
