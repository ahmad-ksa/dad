package com.dad.tablet

import android.content.Context

/**
 * Small persisted state holder. Survives reboots so the lock can be restored,
 * and holds the parent PIN that protects the settings screen.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("dad_prefs", Context.MODE_PRIVATE)

    /** True while the fake-shutdown lock is active. */
    var locked: Boolean
        get() = sp.getBoolean(KEY_LOCKED, false)
        set(value) = sp.edit().putBoolean(KEY_LOCKED, value).apply()

    /** The media volume we saw before muting, so we can restore it on unlock. */
    var savedMediaVolume: Int
        get() = sp.getInt(KEY_VOLUME, -1)
        set(value) = sp.edit().putInt(KEY_VOLUME, value).apply()

    /** Parent PIN required to open settings / unlock locally from the tablet. */
    var pin: String
        get() = sp.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        set(value) = sp.edit().putString(KEY_PIN, value).apply()

    /** Shared secret the phone must send with every command. */
    var pairingToken: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_TOKEN, value).apply()

    companion object {
        private const val KEY_LOCKED = "locked"
        private const val KEY_VOLUME = "saved_media_volume"
        private const val KEY_PIN = "pin"
        private const val KEY_TOKEN = "pairing_token"
        const val DEFAULT_PIN = "0000"
    }
}
