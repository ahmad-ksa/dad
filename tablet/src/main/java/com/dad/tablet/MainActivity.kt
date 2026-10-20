package com.dad.tablet

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Parent-facing setup screen on the tablet. Walks through the three permissions
 * the app needs and shows the pairing token the phone must be given.
 *
 * In a shipping build this screen would sit behind the parent PIN; the PIN gate
 * is wired in [Prefs] and can be enforced here.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private var selectedMinutes = 60
    private lateinit var timerText: TextView
    private lateinit var durationButtons: Map<Int, Button>

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateTimerText()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.tokenValue).text = prefs.pairingToken

        findViewById<Button>(R.id.btnOverlay).setOnClickListener { requestOverlay() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener { openAccessibility() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { ignoreBatteryOptimizations() }
        findViewById<Button>(R.id.btnDeviceAdmin).setOnClickListener { requestDeviceAdmin() }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            ControlService.start(this)
            Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnTestLock).setOnClickListener {
            LockController(this).lock(showBatteryFirst = true)
        }

        timerText = findViewById(R.id.timerText)
        durationButtons = mapOf(
            1 to findViewById(R.id.btnMin1),
            15 to findViewById(R.id.btnMin15),
            30 to findViewById(R.id.btnMin30),
            45 to findViewById(R.id.btnMin45),
            60 to findViewById(R.id.btnMin60),
        )
        durationButtons.forEach { (minutes, button) ->
            button.setOnClickListener { selectedMinutes = minutes; highlightDuration() }
        }
        highlightDuration()
        findViewById<Button>(R.id.btnStartTimer).setOnClickListener { startTimer() }
        findViewById<Button>(R.id.btnCancelTimer).setOnClickListener { cancelTimer() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        findViewById<TextView>(R.id.ipValue).text = localIpv4() ?: getString(R.string.ip_unavailable)
        uiHandler.post(ticker)
    }

    /** The tablet's local IPv4 address, for typing into the phone manually. */
    private fun localIpv4(): String? {
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    override fun onPause() {
        uiHandler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refreshStatus() {
        val status = buildString {
            appendLine(statusLine(R.string.perm_overlay, hasOverlayPermission()))
            appendLine(statusLine(R.string.perm_accessibility, DadAccessibilityService.instance != null))
            appendLine(statusLine(R.string.perm_device_admin, isDeviceAdminActive()))
        }
        findViewById<TextView>(R.id.statusValue).text = status
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        return dpm?.isAdminActive(DadDeviceAdminReceiver.component(this)) == true
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DadDeviceAdminReceiver.component(this))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.device_admin_desc))
        startActivity(intent)
    }

    private fun statusLine(labelRes: Int, granted: Boolean): String {
        val mark = if (granted) "✓" else "✗"
        return "$mark ${getString(labelRes)}"
    }

    /** True if we may draw over other apps (always true before Android 6). */
    private fun hasOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(applicationContext)
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, R.string.enable_accessibility_hint, Toast.LENGTH_LONG).show()
    }

    private fun ignoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // --- Auto-lock timer ---------------------------------------------------

    private fun startTimer() {
        ControlService.startTimer(this, selectedMinutes)
        Toast.makeText(
            this,
            getString(R.string.timer_running, formatDuration(selectedMinutes * 60_000L)),
            Toast.LENGTH_SHORT
        ).show()
        updateTimerText()
    }

    private fun cancelTimer() {
        ControlService.cancelTimer(this)
        updateTimerText()
    }

    private fun highlightDuration() {
        durationButtons.forEach { (minutes, button) ->
            val selected = minutes == selectedMinutes
            button.backgroundTintList =
                getColorStateList(if (selected) R.color.dad_accent else R.color.battery_gray)
            button.setTextColor(getColor(if (selected) R.color.black else R.color.white))
        }
    }

    private fun updateTimerText() {
        val endAt = prefs.sessionEndAt
        val remaining = if (endAt > 0L) endAt - System.currentTimeMillis() else 0L
        timerText.text = when {
            endAt <= 0L -> getString(R.string.timer_idle)
            remaining <= 0L -> getString(R.string.timer_done)
            else -> getString(R.string.timer_running, formatDuration(remaining))
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
}
