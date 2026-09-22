package com.yattubhaa.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.yattubhaa.app.data.Prefs
import com.yattubhaa.app.pairing.PairingLink
import com.yattubhaa.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val route = mutableStateOf<Route>(Route.Onboarding)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // status and navigation bar icons follow light/dark
        route.value = routeFor(intent) ?: startRoute()
        setContent { AppRoot(route = route.value, onRoute = { route.value = it }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFor(intent)?.let { route.value = it }
    }

    private fun startRoute(): Route =
        if (Prefs.onboardingDone) Route.HelpNeededHome else Route.Onboarding

    /** A pairing link, if this launch came from one. Always goes to a confirmation first. */
    private fun routeFor(intent: Intent?): Route? {
        val data = intent?.data ?: return null
        if (data.scheme != "yattubhaa" || data.host != "pair") return null
        val link = PairingLink.parse(data.encodedQuery, allowCleartext = BuildConfig.DEBUG)
        return if (link != null) Route.PairConfirm(link) else Route.BadLink
    }
}
