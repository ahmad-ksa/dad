package com.dad.tablet

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import android.util.Log

/**
 * Central logic for the "fake shutdown" lock.
 *
 * Responsibilities:
 *  - Flip and persist the locked state.
 *  - Silence whatever is playing (the child's game / video) and keep it silenced.
 *  - Ask the foreground app to move to the background.
 *  - Bring the full-screen [LockActivity] to the front.
 *
 * What it deliberately does NOT claim to do: it cannot block the hardware power
 * button's long-press menu on a device that is not Device Owner / rooted. See
 * README for that limitation. Instead it re-asserts the lock whenever the screen
 * comes back on (handled by [DadAccessibilityService] and the screen receiver).
 */
class LockController(private val context: Context) {

    private val prefs = Prefs(context)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    val isLocked: Boolean get() = prefs.locked

    /** Enter the fake-shutdown lock: mute, stop the game, show the black screen. */
    fun lock(showBatteryFirst: Boolean) {
        prefs.locked = true
        silenceEverything()
        stopForegroundApp()
        showLockScreen(showBatteryFirst)
    }

    /** Only show the fake "battery almost empty" warning, without full lock. */
    fun showBatteryWarning() {
        val intent = Intent(context, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_BATTERY_TOAST)
        }
        context.startActivity(intent)
    }

    /** Release the lock, restore audio, and close the lock screen. */
    fun unlock() {
        prefs.locked = false
        restoreAudio()
        context.sendBroadcast(Intent(ACTION_DISMISS_LOCK).setPackage(context.packageName))
    }

    /** Re-assert the lock screen if we are supposed to be locked (used on screen-on). */
    fun reassertIfLocked() {
        if (prefs.locked) {
            silenceEverything()
            showLockScreen(showBatteryFirst = false)
        }
    }

    /** True if the parent enabled Device Admin, which lets us turn the screen off. */
    fun isDeviceAdminActive(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(DadDeviceAdminReceiver.component(context))
    }

    /** Turn the real screen off (like a dead battery). Needs Device Admin. */
    fun lockScreenNow() {
        if (!isDeviceAdminActive()) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        runCatching { dpm.lockNow() }
    }

    // --- Audio -------------------------------------------------------------

    private fun silenceEverything() {
        // Remember the real volume once, so unlock can restore it.
        if (prefs.savedMediaVolume < 0) {
            prefs.savedMediaVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        // Drop media volume to zero.
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }.onFailure { Log.w(TAG, "setStreamVolume failed", it) }

        // Grab exclusive audio focus so players pause/stop themselves.
        requestExclusiveFocus()

        // Send a media PAUSE key so active media sessions halt.
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
    }

    private fun requestExclusiveFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .build()
            focusRequest = req
            audio.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }

    private fun restoreAudio() {
        val saved = prefs.savedMediaVolume
        if (saved >= 0) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
            prefs.savedMediaVolume = -1
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    // --- Stopping the running app -----------------------------------------

    private fun stopForegroundApp() {
        // Accessibility route (reliable): go to Home, which backgrounds the game.
        // Most games pause rendering/audio once backgrounded.
        DadAccessibilityService.instance?.goHome()

        // Best-effort: ask the system to stop our own background processes is a
        // no-op for other apps on modern Android, but we try to trim what we can.
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // We only have permission to kill background of apps we could affect;
            // this is intentionally conservative and silently ignored if denied.
            am.runningAppProcesses?.forEach { proc ->
                if (proc.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND &&
                    proc.pkgList.none { it == context.packageName }
                ) {
                    proc.pkgList.forEach { pkg -> am.killBackgroundProcesses(pkg) }
                }
            }
        }
    }

    // --- Lock screen -------------------------------------------------------

    private fun showLockScreen(showBatteryFirst: Boolean) {
        val intent = Intent(context, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(
                LockActivity.EXTRA_MODE,
                if (showBatteryFirst) LockActivity.MODE_BATTERY_THEN_BLACK else LockActivity.MODE_BLACK
            )
        }
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "LockController"
        const val ACTION_DISMISS_LOCK = "com.dad.tablet.DISMISS_LOCK"
    }
}
