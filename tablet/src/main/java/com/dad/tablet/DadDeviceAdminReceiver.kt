package com.dad.tablet

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * Optional stronger lock. If the parent enables Device Admin, we can call
 * lockNow() to turn the real screen off instantly before showing the fake
 * shutdown on wake. Not required for the app to work.
 */
class DadDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context) =
            ComponentName(context, DadDeviceAdminReceiver::class.java)
    }
}
