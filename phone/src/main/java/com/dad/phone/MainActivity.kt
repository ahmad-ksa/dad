package com.dad.phone

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The parent's remote. Discovers the tablet on the same WiFi and exposes three
 * actions: fake shutdown, battery warning, and unlock.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var discovery: TabletDiscovery

    private var host: String = ""
    private var port: Int = Protocol.PORT
    private var online = false

    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        discovery = TabletDiscovery(this)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)

        // Start from the cached address so buttons work immediately.
        host = prefs.lastHost
        port = prefs.lastPort

        val tokenField = findViewById<EditText>(R.id.tokenField)
        tokenField.setText(prefs.token)

        findViewById<Button>(R.id.btnSaveToken).setOnClickListener {
            prefs.token = tokenField.text.toString().trim()
            Toast.makeText(this, R.string.token_saved, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnLock).setOnClickListener { send(Protocol.CMD_LOCK) }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { send(Protocol.CMD_BATTERY) }
        findViewById<Button>(R.id.btnUnlock).setOnClickListener { send(Protocol.CMD_UNLOCK) }

        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        discovery.start(
            onResolved = { h, p ->
                host = h; port = p; online = true
                prefs.lastHost = h; prefs.lastPort = p
                runOnUiThread { renderStatus() }
            },
            onLost = {
                online = false
                runOnUiThread { renderStatus() }
            }
        )
    }

    override fun onPause() {
        discovery.stop()
        super.onPause()
    }

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
            refreshStatus(client)
        }
    }

    private fun refreshStatus(client: CommandClient) {
        if (host.isEmpty()) return
        lifecycleScope.launch {
            val r = client.send(host, port, Protocol.CMD_STATUS)
            online = r.ok
            renderStatus()
        }
    }

    private fun messageFor(command: String): String = when (command) {
        Protocol.CMD_LOCK -> getString(R.string.sent_lock)
        Protocol.CMD_BATTERY -> getString(R.string.sent_battery)
        Protocol.CMD_UNLOCK -> getString(R.string.sent_unlock)
        else -> getString(R.string.sent_ok)
    }

    private fun renderStatus() {
        val connected = online && host.isNotEmpty()
        statusDot.setTextColor(
            getColor(if (connected) R.color.ok_green else R.color.off_gray)
        )
        statusText.text = getString(
            if (connected) R.string.connected_to else R.string.searching,
            host.ifEmpty { "…" }
        )
    }
}
