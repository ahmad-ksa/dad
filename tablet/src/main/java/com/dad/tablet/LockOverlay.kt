package com.dad.tablet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * The real "fake shutdown" surface: a full-screen WindowManager overlay.
 *
 * Unlike an Activity, an overlay (TYPE_APPLICATION_OVERLAY) reliably appears ON
 * TOP of whatever app is running — a game, YouTube — even when shown from the
 * background service, as long as "Display over other apps" is granted. It shows
 * a red empty-battery glyph for a few seconds, then goes fully black (and asks
 * Device Admin to turn the screen off, if enabled). Two fingers swiped down
 * dismiss it.
 *
 * A single global instance, so showing twice is harmless.
 */
object LockOverlay {

    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var battery: ImageView? = null

    private var gestureActive = false
    private var gestureStartY = 0f

    fun isShowing(): Boolean = root != null

    /** The locking surface: covers and blocks the running app until unlocked. */
    fun show(context: Context, onUnlock: () -> Unit) =
        present(context, onUnlock, blockTouch = true, lockCycle = true, autoHideMs = null)

    /** A brief, non-blocking battery flash for the 20%/10% warnings. */
    fun flash(context: Context) {
        if (root != null) return
        present(context, {}, blockTouch = false, lockCycle = false, autoHideMs = FLASH_MS)
    }

    fun hide(context: Context) {
        handler.removeCallbacksAndMessages(null)
        val v = root ?: return
        runCatching { wm(context).removeView(v) }
        root = null
        battery = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun present(
        context: Context,
        onUnlock: () -> Unit,
        blockTouch: Boolean,
        lockCycle: Boolean,
        autoHideMs: Long?,
    ) {
        val app = context.applicationContext
        if (root != null) {
            if (lockCycle) startCycle(app)
            return
        }

        val container = FrameLayout(app).apply { setBackgroundColor(Color.BLACK) }
        val size = (150 * app.resources.displayMetrics.density).toInt()
        val glyph = ImageView(app).apply {
            setImageResource(R.drawable.ic_battery_empty)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }
        container.addView(glyph)

        if (blockTouch) {
            val threshold = app.resources.displayMetrics.heightPixels * SWIPE_FRACTION
            container.setOnTouchListener { _, ev -> onTouch(ev, threshold, onUnlock) }
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (!blockTouch) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val added = runCatching { wm(app).addView(container, params) }.isSuccess
        if (!added) return   // overlay permission not granted
        root = container
        battery = glyph
        immersive(container)

        handler.removeCallbacksAndMessages(null)
        if (lockCycle) startCycle(app)
        if (autoHideMs != null) handler.postDelayed({ hide(app) }, autoHideMs)
    }

    private fun startCycle(context: Context) {
        battery?.visibility = View.VISIBLE
        handler.postDelayed({ goDark(context) }, SHOW_MS)
    }

    private fun goDark(context: Context) {
        battery?.visibility = View.GONE
        LockController(context).lockScreenNow()
    }

    private fun onTouch(ev: MotionEvent, threshold: Float, onUnlock: () -> Unit): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                if (ev.pointerCount >= FINGERS) {
                    gestureActive = true
                    gestureStartY = avgY(ev)
                }
            MotionEvent.ACTION_MOVE ->
                if (gestureActive && ev.pointerCount >= FINGERS &&
                    avgY(ev) - gestureStartY > threshold
                ) {
                    gestureActive = false
                    onUnlock()
                }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (ev.pointerCount - 1 < FINGERS) gestureActive = false
        }
        return true
    }

    private fun avgY(ev: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until ev.pointerCount) sum += ev.getY(i)
        return sum / ev.pointerCount
    }

    private fun immersive(v: View) {
        @Suppress("DEPRECATION")
        v.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    private fun wm(context: Context) =
        context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private const val SHOW_MS = 4000L
    private const val FLASH_MS = 2000L
    private const val SWIPE_FRACTION = 0.25f
    private const val FINGERS = 2
}
