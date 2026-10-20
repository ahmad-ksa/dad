package com.dad.tablet

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
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
        val pkg = event.packageName?.toString() ?: return

        // Remember the last real foreground app (not us, not the launcher), so a
        // lock can terminate exactly that app.
        if (pkg != packageName && pkg != launcherPackage()) {
            lastForegroundPackage = pkg
        }

        // While locked, if anything other than our lock surface surfaces, re-assert.
        if (prefs.locked && pkg != packageName) {
            LockController(this).reassertIfLocked()
        }
    }

    private var cachedLauncher: String? = null
    private fun launcherPackage(): String? {
        cachedLauncher?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        cachedLauncher = packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return cachedLauncher
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

        /** The last app that was in the foreground, so a lock can close it. */
        @Volatile
        var lastForegroundPackage: String? = null
    }
}
