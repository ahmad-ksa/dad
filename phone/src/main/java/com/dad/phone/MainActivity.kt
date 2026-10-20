package com.dad.phone

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The parent's remote. Discovers the tablet on the same WiFi and exposes:
 *   - fake shutdown, fake battery warning, unlock
 *   - an auto-lock timer (15 / 30 / 45 / 60 min) whose countdown the tablet owns
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var discovery: TabletDiscovery

    private var host: String = ""
    private var port: Int = Protocol.PORT
    private var online = false

    private var selectedMinutes = 60
    private var timerEndAt = 0L   // phone-local estimate of when the tablet locks

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateTimerText()
            if (timerEndAt > System.currentTimeMillis()) uiHandler.postDelayed(this, 1000L)
        }
    }

    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var ipField: EditText
    private lateinit var durationButtons: Map<Int, Button>

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        discovery = TabletDiscovery(this)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        timerText = findViewById(R.id.timerText)
        ipField = findViewById(R.id.ipField)

        host = prefs.lastHost
        port = prefs.lastPort
        if (host.isNotEmpty()) ipField.setText(host)

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectManually() }

        val tokenField = findViewById<EditText>(R.id.tokenField)
        tokenField.setText(prefs.token)
        findViewById<Button>(R.id.btnSaveToken).setOnClickListener {
            prefs.token = tokenField.text.toString().trim()
            Toast.makeText(this, R.string.token_saved, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnLock).setOnClickListener { send(Protocol.CMD_LOCK) }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { send(Protocol.CMD_BATTERY) }
        findViewById<Button>(R.id.btnUnlock).setOnClickListener {
            stopLocalCountdown(); send(Protocol.CMD_UNLOCK)
        }

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
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { Updater.checkAndInstall(this) }

        renderStatus()
        updateTimerText()
    }

    override fun onResume() {
        super.onResume()
        acquireMulticastLock()
        discovery.start(
            onResolved = { h, p ->
                host = h; port = p; online = true
                prefs.lastHost = h; prefs.lastPort = p
                runOnUiThread { if (ipField.text.isEmpty()) ipField.setText(h); renderStatus() }
                syncStatus()
            },
            onLost = {
                online = false
                runOnUiThread { renderStatus() }
            }
        )
        if (host.isNotEmpty()) syncStatus()
    }

    override fun onPause() {
        discovery.stop()
        releaseMulticastLock()
        uiHandler.removeCallbacks(ticker)
        super.onPause()
    }

    /** Some routers filter mDNS unless the app holds a multicast lock. */
    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("dad-nsd").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
    }

    /** Manual fallback: connect to the IP shown on the tablet's setup screen. */
    private fun connectManually() {
        val ip = ipField.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, R.string.ip_hint, Toast.LENGTH_SHORT).show()
            return
        }
        host = ip
        port = Protocol.PORT
        prefs.lastHost = ip
        prefs.lastPort = port
        Toast.makeText(this, R.string.connecting, Toast.LENGTH_SHORT).show()
        syncStatus()
    }

    // --- Commands ----------------------------------------------------------

    private fun send(command: String) {
        if (host.isEmpty()) {
            Toast.makeText(this, R.string.no_tablet, Toast.LENGTH_SHORT).show()
            return
        }
        val client = CommandClient(prefs.token)
        lifecycleScope.launch {
            val result = client.send(host, port, command)
            val msg = if (result.ok) messageFor(command) else getString(R.string.failed, result.body)
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTimer() {
        if (host.isEmpty()) {
            Toast.makeText(this, R.string.no_tablet, Toast.LENGTH_SHORT).show()
            return
        }
        val minutes = selectedMinutes
        val client = CommandClient(prefs.token)
        lifecycleScope.launch {
            val cmd = "${Protocol.CMD_START_TIMER}?${Protocol.PARAM_MINUTES}=$minutes"
            val result = client.send(host, port, cmd)
            if (result.ok) {
                startLocalCountdown(minutes.toLong() * 60_000L)
                Toast.makeText(this@MainActivity, getString(R.string.timer_started, minutes), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.failed, result.body), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cancelTimer() {
        stopLocalCountdown()
        if (host.isEmpty()) return
        val client = CommandClient(prefs.token)
        lifecycleScope.launch {
            client.send(host, port, Protocol.CMD_CANCEL_TIMER)
            Toast.makeText(this@MainActivity, R.string.timer_cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    /** Pull the tablet's authoritative state and sync the local countdown. */
    private fun syncStatus() {
        if (host.isEmpty()) return
        val client = CommandClient(prefs.token)
        lifecycleScope.launch {
            val r = client.send(host, port, Protocol.CMD_STATUS)
            val reachableButBadToken = !r.ok && r.body.contains("bad_token")
            online = r.ok || reachableButBadToken
            if (r.ok) runCatching {
                val remaining = JSONObject(r.body).optLong("timer_remaining_ms", 0L)
                if (remaining > 0L) startLocalCountdown(remaining) else stopLocalCountdown()
            }
            if (reachableButBadToken) {
                Toast.makeText(this@MainActivity, R.string.token_needed, Toast.LENGTH_LONG).show()
            }
            renderStatus()
        }
    }

    // --- Countdown ---------------------------------------------------------

    private fun startLocalCountdown(remainingMs: Long) {
        timerEndAt = System.currentTimeMillis() + remainingMs
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
    }

    private fun stopLocalCountdown() {
        timerEndAt = 0L
        uiHandler.removeCallbacks(ticker)
        updateTimerText()
    }

    private fun updateTimerText() {
        val remaining = timerEndAt - System.currentTimeMillis()
        when {
            timerEndAt <= 0L -> timerText.text = getString(R.string.timer_idle)
            remaining <= 0L -> {
                timerEndAt = 0L
                timerText.text = getString(R.string.timer_done)
            }
            else -> timerText.text = getString(R.string.timer_running, formatDuration(remaining))
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

    // --- UI helpers --------------------------------------------------------

    private fun highlightDuration() {
        durationButtons.forEach { (minutes, button) ->
            val selected = minutes == selectedMinutes
            button.backgroundTintList = getColorStateList(if (selected) R.color.accent else R.color.surface)
            button.setTextColor(getColor(if (selected) R.color.white else R.color.text_primary))
        }
    }

    private fun messageFor(command: String): String = when {
        command == Protocol.CMD_LOCK -> getString(R.string.sent_lock)
        command == Protocol.CMD_BATTERY -> getString(R.string.sent_battery)
        command == Protocol.CMD_UNLOCK -> getString(R.string.sent_unlock)
        else -> getString(R.string.sent_ok)
    }

    private fun renderStatus() {
        val connected = online && host.isNotEmpty()
        statusDot.setTextColor(getColor(if (connected) R.color.ok_green else R.color.off_gray))
        statusText.text = getString(
            if (connected) R.string.connected_to else R.string.searching,
            host.ifEmpty { "…" }
        )
    }
}
