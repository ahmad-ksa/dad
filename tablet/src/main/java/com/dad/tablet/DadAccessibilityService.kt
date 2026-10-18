package com.dad.tablet

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Two jobs:
 *  - Provide a reliable "go Home" action that backgrounds the running game when
 *    the parent triggers a lock.
 *  - While locked, if the child somehow surfaces another app (e.g. a notification
 *    full-screen intent), bounce the lock screen back to the front.
 *
 * This is the honest, non-root way to keep the fake-shutdown in front. It is not
 * a keylogger and reads no screen content; it only reacts to window changes.
 */
class DadAccessibilityService : AccessibilityService() {

    private val prefs by lazy { Prefs(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!prefs.locked) return

        val pkg = event.packageName?.toString() ?: return
        // If anything other than our own lock screen comes to the foreground
        // while locked, re-assert the lock.
        if (pkg != packageName) {
            LockController(this).reassertIfLocked()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Send the device to the home screen, backgrounding the current app. */
    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    companion object {
        @Volatile
        var instance: DadAccessibilityService? = null
            private set
    }
}
