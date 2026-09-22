package com.yattubhaa.app

import android.app.Application
import com.yattubhaa.app.data.Prefs
import com.yattubhaa.app.pairing.PairingStore

class YattuBhaaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        PairingStore.init(this)
    }
}
