package com.dad.tablet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restart the control service after a reboot. If the device was locked when it
 * was powered off, [ControlService] restores the lock on start, so a forced
 * restart does not become an escape hatch.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> ControlService.start(context)
        }
    }
}
