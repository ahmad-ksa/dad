package com.dad.tablet

import android.app.Application
import java.util.UUID

/** Generates a pairing token on first run if none exists. */
class DadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        if (prefs.pairingToken.isEmpty()) {
            prefs.pairingToken = UUID.randomUUID().toString().replace("-", "").take(16)
        }
    }
}
