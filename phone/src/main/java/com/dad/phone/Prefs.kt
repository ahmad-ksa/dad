package com.dad.phone

import android.content.Context

/** Stores the pairing token and the last known tablet address. */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("dad_phone", Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    /** Cached host:port so we can act instantly before discovery re-resolves. */
    var lastHost: String
        get() = sp.getString("last_host", "") ?: ""
        set(v) = sp.edit().putString("last_host", v).apply()

    var lastPort: Int
        get() = sp.getInt("last_port", Protocol.PORT)
        set(v) = sp.edit().putInt("last_port", v).apply()
}
