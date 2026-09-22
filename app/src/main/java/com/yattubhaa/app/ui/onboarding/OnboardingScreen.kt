package com.yattubhaa.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yattubhaa.app.ui.components.BigButton
import com.yattubhaa.app.ui.components.ButtonKind

/**
 * First-run flow. The permission gets its own page in plain language before Android's own
 * dialog appears, so he never meets an unexplained system popup. The other setting the app
 * needs (a red Stop button that can sit on top of other apps) is explained at the moment it
 * is first needed, because it takes him to a Settings screen and is easier to follow then.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pages = remember {
        listOf(
            OnboardingPageData(
                icon = Icons.Filled.Wifi,
                title = "Welcome",
                body = "This app lets a family member help you with your phone when you call them, " +
                    "the same as if they were sitting next to you.",
            ),
            OnboardingPageData(
                icon = Icons.Filled.Notifications,
                title = "One thing to allow",
                body = "Next, your phone will ask to let this app show you a notice. " +
                    "That is only so you can see when your family member is connected.",
            ),
            OnboardingPageData(
                icon = Icons.Filled.PhonelinkLock,
                title = "You are always in control",
                body = "You choose when to ask for help. While your family member can see your " +
                    "screen, a big red Stop button stays on it. Nothing happens on your phone " +
                    "unless you tap Get Help.",
            ),
        )
    }
    var index by remember { mutableIntStateOf(0) }
    val last = pages.lastIndex
    val next = { if (index < last) index++ else onFinished() }

    // Android only asks this on version 13 and up; earlier versions just carry on.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    val onNotificationPage = index == 1

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        OnboardingPage(data = pages[index], modifier = Modifier.weight(1f).fillMaxWidth())
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            BigButton(
                text = when {
                    onNotificationPage -> "Allow"
                    index < last -> "Next"
                    else -> "Start"
                },
                onClick = {
                    if (onNotificationPage && Build.VERSION.SDK_INT >= 33) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        next()
                    }
                },
            )
            if (onNotificationPage) {
                BigButton("Not now", onClick = { next() }, kind = ButtonKind.Secondary, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}
