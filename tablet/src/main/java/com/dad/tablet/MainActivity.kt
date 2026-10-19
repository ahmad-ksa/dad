package com.dad.tablet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.tokenValue).text = prefs.pairingToken

        findViewById<Button>(R.id.btnOverlay).setOnClickListener { requestOverlay() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener { openAccessibility() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { ignoreBatteryOptimizations() }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            ControlService.start(this)
            Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnTestLock).setOnClickListener {
            LockController(this).lock(showBatteryFirst = true)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val status = buildString {
            appendLine(statusLine(R.string.perm_overlay, hasOverlayPermission()))
            appendLine(statusLine(R.string.perm_accessibility, DadAccessibilityService.instance != null))
        }
        findViewById<TextView>(R.id.statusValue).text = status
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
}
