package com.yattubhaa.app

import com.yattubhaa.app.pairing.PairingLink

/**
 * Every screen the app can show. Kept as a flat "what am I showing right now" rather than a
 * navigation stack on purpose: Help Needed mode should never have more than one obvious way
 * forward, and a stack invites "back" going somewhere surprising.
 */
sealed interface Route {
    data object Onboarding : Route
    data object HelpNeededHome : Route

    /** Someone tapped a pairing link. Nothing is stored until the person says yes. */
    class PairConfirm(val link: PairingLink) : Route
    data object BadLink : Route
    data object NotPaired : Route
    data object NeedyHelp : Route

    data object HelperPinLock : Route
    data object HelperDashboard : Route
    data class HelperCode(val pairingId: String, val tryAgain: Boolean = false) : Route
    data class HelperSessionScreen(val pairingId: String) : Route
}
