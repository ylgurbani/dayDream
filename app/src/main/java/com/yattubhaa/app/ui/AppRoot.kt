package com.yattubhaa.app.ui

import androidx.compose.runtime.Composable
import com.yattubhaa.app.Route
import com.yattubhaa.app.data.Prefs
import com.yattubhaa.app.pairing.PairingStore
import com.yattubhaa.app.ui.components.MessageScreen
import com.yattubhaa.app.ui.helpneeded.HelpNeededHomeScreen
import com.yattubhaa.app.ui.helpneeded.NeedyHelpScreen
import com.yattubhaa.app.ui.helpneeded.PairConfirmScreen
import com.yattubhaa.app.ui.helper.HelperCodeScreen
import com.yattubhaa.app.ui.helper.HelperDashboardScreen
import com.yattubhaa.app.ui.helper.HelperPinLockScreen
import com.yattubhaa.app.ui.helper.HelperSessionScreen
import com.yattubhaa.app.ui.onboarding.OnboardingScreen
import com.yattubhaa.app.ui.theme.YattuTheme

@Composable
fun AppRoot(route: Route, onRoute: (Route) -> Unit) {
    val home = if (Prefs.onboardingDone) Route.HelpNeededHome else Route.Onboarding
    YattuTheme {
        when (route) {
            Route.Onboarding -> OnboardingScreen(onFinished = {
                Prefs.onboardingDone = true
                onRoute(Route.HelpNeededHome)
            })
            Route.HelpNeededHome -> HelpNeededHomeScreen(
                onGetHelp = {
                    onRoute(if (PairingStore.all().isEmpty()) Route.NotPaired else Route.NeedyHelp)
                },
                onOpenHelperMode = { onRoute(Route.HelperPinLock) },
            )
            is Route.PairConfirm -> PairConfirmScreen(
                link = route.link,
                onDone = { onRoute(home) },
            )
            Route.BadLink -> MessageScreen(
                title = "That link did not work",
                body = "Please ask your family member to send it to you again.",
                primary = "OK",
                onPrimary = { onRoute(home) },
            )
            Route.NotPaired -> MessageScreen(
                title = "One more step first",
                body = "Ask your family member to send you a link, then tap it. " +
                    "After that, this button will work.",
                primary = "OK",
                onPrimary = { onRoute(Route.HelpNeededHome) },
            )
            Route.NeedyHelp -> NeedyHelpScreen(onExit = { onRoute(Route.HelpNeededHome) })

            Route.HelperPinLock -> HelperPinLockScreen(
                onUnlocked = { onRoute(Route.HelperDashboard) },
                onCancel = { onRoute(Route.HelpNeededHome) },
            )
            Route.HelperDashboard -> HelperDashboardScreen(
                onConnect = { onRoute(Route.HelperCode(it)) },
                onExit = { onRoute(Route.HelpNeededHome) },
            )
            is Route.HelperCode -> HelperCodeScreen(
                pairingId = route.pairingId,
                tryAgain = route.tryAgain,
                onConnect = { onRoute(Route.HelperSessionScreen(route.pairingId)) },
                onBack = { onRoute(Route.HelperDashboard) },
            )
            is Route.HelperSessionScreen -> HelperSessionScreen(
                onExit = { wrongCode ->
                    onRoute(if (wrongCode) Route.HelperCode(route.pairingId, tryAgain = true) else Route.HelperDashboard)
                },
            )
        }
    }
}
