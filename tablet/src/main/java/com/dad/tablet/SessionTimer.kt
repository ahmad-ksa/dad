package com.dad.tablet

import android.os.Handler
import android.os.Looper

/**
 * Runs an auto-lock session entirely on the tablet.
 *
 * The phone only sends "start a session of N minutes"; from then on the tablet
 * owns the countdown, so it still fires even if the phone leaves the WiFi or is
 * switched off. During a session:
 *   - at 20% of the time remaining  -> a fake low-battery warning
 *   - at 10% of the time remaining  -> another fake low-battery warning
 *   - when the time is up           -> the fake shutdown lock
 *
 * The end time is persisted, so if the service is killed and restarted the
 * session is re-armed (or the lock applied immediately if the time already
 * passed) via [resume].
 */
class SessionTimer(
    private val lock: LockController,
    private val prefs: Prefs,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scheduled = mutableListOf<Runnable>()

    /** Begin a new session, replacing any running one. */
    fun start(minutes: Int) {
        clearCallbacks()
        val total = minutes.coerceAtLeast(1).toLong() * 60_000L
        val endAt = System.currentTimeMillis() + total
        prefs.sessionTotalMs = total
        prefs.sessionEndAt = endAt
        schedule(endAt, total)
    }

    /** Re-arm a persisted session after a service (re)start. */
    fun resume() {
        clearCallbacks()
        val endAt = prefs.sessionEndAt
        val total = prefs.sessionTotalMs
        if (endAt <= 0L || total <= 0L) return
        if (System.currentTimeMillis() >= endAt) finishNow() else schedule(endAt, total)
    }

    /** Stop the session without locking (e.g. parent cancels or unlocks). */
    fun cancel() {
        clearCallbacks()
        prefs.sessionEndAt = 0L
        prefs.sessionTotalMs = 0L
    }

    /** Milliseconds left in the current session, or 0 if none is running. */
    fun remainingMs(): Long {
        val endAt = prefs.sessionEndAt
        if (endAt <= 0L) return 0L
        return (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    // --- internals ---------------------------------------------------------

    private fun schedule(endAt: Long, total: Long) {
        val now = System.currentTimeMillis()
        val warn20At = endAt - total * 20 / 100   // 20% of the time remaining
        val warn10At = endAt - total * 10 / 100   // 10% of the time remaining
        arm(warn20At - now) { lock.showBatteryWarning() }
        arm(warn10At - now) { lock.showBatteryWarning() }
        arm(endAt - now) { finishNow() }
    }

    /** Schedule [action] after [delay] ms; skip marks already in the past. */
    private fun arm(delay: Long, action: () -> Unit) {
        if (delay < 0L) return
        val runnable = Runnable { action() }
        scheduled.add(runnable)
        handler.postDelayed(runnable, delay)
    }

    private fun finishNow() {
        prefs.sessionEndAt = 0L
        prefs.sessionTotalMs = 0L
        lock.lock(showBatteryFirst = true)
    }

    private fun clearCallbacks() {
        scheduled.forEach { handler.removeCallbacks(it) }
        scheduled.clear()
    }
}
