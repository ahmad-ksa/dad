package com.dad.tablet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Long-running service that:
 *  1. Advertises the tablet on the local network via NSD, so the phone finds it
 *     without the parent ever typing an IP address.
 *  2. Runs a tiny HTTP server that accepts the parent's commands.
 *
 * Every request must carry the pairing token, so another device on the same WiFi
 * cannot drive the tablet.
 */
class ControlService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var lock: LockController
    private lateinit var timer: SessionTimer
    private var server: CommandServer? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        lock = LockController(this)
        timer = SessionTimer(lock, prefs)
        startForeground(NOTIF_ID, buildNotification())
        startServer()
        registerOnNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TIMER -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 60)
                main.post { timer.start(minutes) }
            }
            ACTION_CANCEL_TIMER -> main.post { timer.cancel() }
            else -> {
                // Normal start or a restart after being killed: restore the lock
                // if needed and re-arm any session that was still running.
                if (prefs.locked) main.post { lock.reassertIfLocked() }
                main.post { timer.resume() }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        unregisterFromNetwork()
        super.onDestroy()
    }

    // --- HTTP server -------------------------------------------------------

    private fun startServer() {
        runCatching {
            server = CommandServer(Protocol.PORT).also { it.start(SOCKET_TIMEOUT, true) }
            Log.i(TAG, "Control server listening on ${Protocol.PORT}")
        }.onFailure { Log.e(TAG, "Failed to start control server", it) }
    }

    private fun stopServer() {
        runCatching { server?.stop() }
        server = null
    }

    private inner class CommandServer(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val expected = prefs.pairingToken
            val provided = session.headers[Protocol.HEADER_TOKEN]
            if (expected.isNotEmpty() && provided != expected) {
                return json(Response.Status.FORBIDDEN, mapOf("error" to "bad_token"))
            }

            val command = session.uri.trim('/').removePrefix("cmd/")
            Log.i(TAG, "command: $command")

            return when (command) {
                Protocol.CMD_LOCK -> {
                    main.post { timer.cancel(); lock.lock(showBatteryFirst = true) }
                    json(Response.Status.OK, mapOf("ok" to true, "state" to "locked"))
                }
                Protocol.CMD_BATTERY -> {
                    main.post { lock.showBatteryWarning() }
                    json(Response.Status.OK, mapOf("ok" to true, "state" to "warned"))
                }
                Protocol.CMD_UNLOCK -> {
                    main.post { timer.cancel(); lock.unlock() }
                    json(Response.Status.OK, mapOf("ok" to true, "state" to "unlocked"))
                }
                Protocol.CMD_START_TIMER -> {
                    val minutes = session.parameters[Protocol.PARAM_MINUTES]
                        ?.firstOrNull()?.toIntOrNull() ?: 60
                    main.post { timer.start(minutes) }
                    json(Response.Status.OK, mapOf("ok" to true, "state" to "timer", "minutes" to minutes))
                }
                Protocol.CMD_CANCEL_TIMER -> {
                    main.post { timer.cancel() }
                    json(Response.Status.OK, mapOf("ok" to true, "state" to "timer_cancelled"))
                }
                Protocol.CMD_STATUS -> json(Response.Status.OK, statusPayload())
                else -> json(Response.Status.NOT_FOUND, mapOf("error" to "unknown_command"))
            }
        }

        private fun json(status: Response.Status, body: Map<String, Any>): Response {
            val payload = JSONObject(body).toString()
            return newFixedLengthResponse(status, "application/json", payload)
        }
    }

    private fun statusPayload(): Map<String, Any> {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return mapOf(
            "locked" to prefs.locked,
            "battery" to level,
            "accessibility" to (DadAccessibilityService.instance != null),
            "timer_remaining_ms" to timer.remainingMs(),
            "timer_total_ms" to prefs.sessionTotalMs
        )
    }

    // --- Network service discovery ----------------------------------------

    private fun registerOnNetwork() {
        val info = NsdServiceInfo().apply {
            serviceName = Protocol.SERVICE_NAME
            serviceType = Protocol.SERVICE_TYPE
            port = Protocol.PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD registered as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).also {
            runCatching { it.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { e -> Log.e(TAG, "registerService failed", e) }
        }
    }

    private fun unregisterFromNetwork() {
        val mgr = nsdManager ?: return
        val listener = registrationListener ?: return
        runCatching { mgr.unregisterService(listener) }
        registrationListener = null
        nsdManager = null
    }

    // --- Notification ------------------------------------------------------

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.service_running))
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ControlService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "dad_control"
        private const val SOCKET_TIMEOUT = 10_000

        const val ACTION_START_TIMER = "com.dad.tablet.action.START_TIMER"
        const val ACTION_CANCEL_TIMER = "com.dad.tablet.action.CANCEL_TIMER"
        const val EXTRA_MINUTES = "minutes"

        fun start(context: Context) = deliver(context, null)

        /** Start an auto-lock session straight from the tablet UI. */
        fun startTimer(context: Context, minutes: Int) =
            deliver(context, Intent().setAction(ACTION_START_TIMER).putExtra(EXTRA_MINUTES, minutes))

        /** Cancel a running session from the tablet UI. */
        fun cancelTimer(context: Context) =
            deliver(context, Intent().setAction(ACTION_CANCEL_TIMER))

        private fun deliver(context: Context, template: Intent?) {
            val intent = (template ?: Intent()).setClass(context, ControlService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
