package com.yattubhaa.app

import android.app.Application
import com.yattubhaa.app.data.Prefs
import com.yattubhaa.app.pairing.PairingStore
import com.yattubhaa.app.service.ControlCapability
import com.yattubhaa.app.session.SessionHub

class YattuBhaaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        PairingStore.init(this)
        SessionHub.init(this)
        // No session can be running yet, so remote tap and swipe must not be switched on either:
        // cleans up after a session that never got to end properly (see ControlCapability).
        ControlCapability.switchOff(this)
    }
}
