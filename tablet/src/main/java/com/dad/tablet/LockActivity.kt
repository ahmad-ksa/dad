package com.dad.tablet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * The full-screen "fake shutdown" surface.
 *
 * - Shows over the keyguard and turns the screen on (so pressing power to wake
 *   lands the child right back here instead of the real home screen).
 * - MODE_BATTERY_* briefly shows an empty-battery glyph, mimicking a device
 *   whose battery just died, then fades to pure black.
 * - It swallows Back/Recent and keeps itself immersive. It does NOT pretend to
 *   block the hardware power menu; see README.
 */
class LockActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var batteryGlyph: View
    private lateinit var blackLayer: View

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LockController.ACTION_DISMISS_LOCK) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWhenLockedAndTurnScreenOn()
        setContentView(R.layout.activity_lock)
        batteryGlyph = findViewById(R.id.batteryGlyph)
        blackLayer = findViewById(R.id.blackLayer)

        registerReceiver(
            dismissReceiver,
            IntentFilter(LockController.ACTION_DISMISS_LOCK),
            receiverFlags()
        )

        applyMode(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyMode(intent)
    }

    private fun applyMode(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_MODE)) {
            // Both the end-of-timer lock and a power-press re-show keep the red
            // empty-battery glyph on a black screen until the secret gesture.
            MODE_BATTERY_THEN_BLACK, MODE_BLACK -> showLocked()
            MODE_BATTERY_TOAST -> playBatteryToast()
            else -> showLocked()
        }
    }

    /** Red empty-battery glyph centered on black; stays until the secret gesture. */
    private fun showLocked() {
        batteryGlyph.visibility = View.VISIBLE
        blackLayer.alpha = 0f
    }

    /** Only the warning: flash the glyph, then close (device keeps working). */
    private fun playBatteryToast() {
        batteryGlyph.visibility = View.VISIBLE
        blackLayer.alpha = 0f
        handler.postDelayed({ finishAndRemoveTask() }, BATTERY_GLYPH_MS)
    }

    private fun showWhenLockedAndTurnScreenOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // --- Secret unlock gesture: three fingers, swipe down -----------------

    private var gestureActive = false
    private var gestureStartY = 0f
    private val unlockThresholdPx by lazy { resources.displayMetrics.heightPixels * UNLOCK_SWIPE_FRACTION }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detectUnlockGesture(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun detectUnlockGesture(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= REQUIRED_FINGERS) {
                    gestureActive = true
                    gestureStartY = averageY(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureActive && ev.pointerCount >= REQUIRED_FINGERS &&
                    averageY(ev) - gestureStartY > unlockThresholdPx
                ) {
                    gestureActive = false
                    secretUnlock()
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (ev.pointerCount - 1 < REQUIRED_FINGERS) gestureActive = false
            }
        }
    }

    private fun averageY(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return sum / ev.pointerCount
    }

    /** Release the fake shutdown: cancel any session and clear the lock. */
    private fun secretUnlock() {
        ControlService.unlock(this)
        finishAndRemoveTask()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    @Deprecated("Back is intentionally swallowed while locked")
    override fun onBackPressed() {
        // Do nothing: the child cannot dismiss the fake-shutdown with Back.
        if (!Prefs(this).locked) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(dismissReceiver) }
        super.onDestroy()
    }

    private fun receiverFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Context.RECEIVER_NOT_EXPORTED else 0

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_BLACK = "black"
        const val MODE_BATTERY_THEN_BLACK = "battery_then_black"
        const val MODE_BATTERY_TOAST = "battery_toast"
        private const val BATTERY_GLYPH_MS = 2000L

        /** Three fingers swiped down by this fraction of the screen unlocks. */
        private const val REQUIRED_FINGERS = 3
        private const val UNLOCK_SWIPE_FRACTION = 0.25f
    }
}
