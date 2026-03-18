package com.nospoon.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import org.json.JSONObject
import to.holepunch.bare.kit.IPC
import to.holepunch.bare.kit.Worklet
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class NospoonVpnService : VpnService() {

    companion object {
        const val TAG = "NospoonVPN"
        const val ACTION_START = "com.nospoon.vpn.START"
        const val ACTION_STOP = "com.nospoon.vpn.STOP"
        const val EXTRA_SERVER_KEY = "serverKey"
        const val EXTRA_SEED = "seed"
        const val EXTRA_IP = "ip"
        const val EXTRA_MTU = "mtu"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "nospoon_vpn"
        const val ACTION_STATUS = "com.nospoon.vpn.STATUS"
        const val EXTRA_STATUS_TEXT = "statusText"
        const val EXTRA_CONNECTED = "connected"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var worklet: Worklet? = null
    private var ipc: IPC? = null
    private var ipcBuffer = StringBuilder()

    // Config stored for deferred VPN establishment (after DHT connects)
    private var pendingIp: String? = null
    private var pendingPrefix: Int = 24
    private var pendingMtu: Int = 1400

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val serverKey = intent.getStringExtra(EXTRA_SERVER_KEY) ?: return START_NOT_STICKY
                val seed = intent.getStringExtra(EXTRA_SEED)
                val ip = intent.getStringExtra(EXTRA_IP) ?: "10.0.0.2"
                val mtu = intent.getIntExtra(EXTRA_MTU, 1400)
                startVpn(serverKey, seed, ip, mtu)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channel = NotificationChannel(
            CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val tapIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("nospoon")
            .setContentText("Connecting...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String) {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("nospoon")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun startVpn(serverKey: String, seed: String?, ip: String, mtu: Int) {
        startForegroundNotification()

        // Store config — VPN is established later, after DHT connects
        val parts = ip.split("/")
        pendingIp = parts[0]
        pendingPrefix = if (parts.size > 1) parts[1].toInt() else 24
        pendingMtu = mtu

        // Phase 1: Start worklet and connect DHT over regular internet.
        // No VPN routes are installed yet, so DHT can reach the server.
        worklet = Worklet(null)
        ipc = IPC(worklet)
        setupIpcListener()

        val bundle = assets.open("client.bundle")
        worklet!!.start("/client.bundle", bundle, null)

        // Send start command — no TUN fd yet
        val startMsg = JSONObject().apply {
            put("type", "start")
            put("serverKey", serverKey)
            if (seed != null) put("seed", seed)
        }
        sendToWorklet(startMsg)
    }

    // Phase 2: Called when worklet reports DHT is connected.
    // The DHT socket is already protected, so it bypasses VPN routing.
    private fun establishVpn() {
        val ip = pendingIp ?: return
        val prefix = pendingPrefix
        val mtu = pendingMtu

        val builder = Builder()
            .setSession("nospoon")
            .setMtu(mtu)
            .addAddress(ip, prefix)
            .addRoute("0.0.0.0", 1)     // split route: 0–127.*
            .addRoute("128.0.0.0", 1)   // split route: 128–255.*
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }

        val tunFd = vpnInterface!!.fd
        Log.i(TAG, "VPN established, TUN fd: $tunFd")

        // Send TUN fd to worklet so it can start packet forwarding
        val tunMsg = JSONObject().apply {
            put("type", "tun")
            put("tunFd", tunFd)
        }
        sendToWorklet(tunMsg)
    }

    // Continuous IPC listener — re-registers after each read so we
    // receive all messages, not just the first one.
    private fun setupIpcListener() {
        readNextIpcMessage()
    }

    private fun readNextIpcMessage() {
        ipc?.read { data, _ ->
            if (data != null) {
                val text = StandardCharsets.UTF_8.decode(data).toString()
                ipcBuffer.append(text)

                val content = ipcBuffer.toString()
                val lines = content.split("\n")
                ipcBuffer.clear()
                ipcBuffer.append(lines.last()) // keep incomplete line

                for (i in 0 until lines.size - 1) {
                    val line = lines[i].trim()
                    if (line.isEmpty()) continue

                    try {
                        val msg = JSONObject(line)
                        handleWorkletMessage(msg)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse IPC message: $line")
                    }
                }
            }
            // Re-register for next message
            readNextIpcMessage()
        }
    }

    private fun handleWorkletMessage(msg: JSONObject) {
        when (msg.getString("type")) {
            "protect" -> {
                // Exempt DHT socket from VPN routing before routes are installed
                val fd = msg.getInt("fd")
                val ok = protect(fd)
                Log.i(TAG, "protect(fd=$fd): $ok")
            }
            "connected" -> {
                // DHT connected over regular internet — now safe to install VPN routes
                Log.i(TAG, "DHT connected, establishing VPN...")
                updateNotification("Connected")
                broadcastStatus("Connected", true)
                establishVpn()
            }
            "status" -> {
                val connected = msg.getBoolean("connected")
                val text = if (connected) "Connected" else "Reconnecting..."
                Log.i(TAG, if (connected) "Connected to server" else "Disconnected from server")
                updateNotification(text)
                broadcastStatus(text, connected)
            }
            "identity" -> {
                val publicKey = msg.getString("publicKey")
                Log.i(TAG, "Client public key: $publicKey")
            }
            "error" -> {
                val message = msg.getString("message")
                Log.e(TAG, "Worklet error: $message")
                broadcastStatus("Error: $message", false)
            }
            "stopped" -> {
                Log.i(TAG, "Worklet stopped")
                broadcastStatus("Disconnected", false)
                cleanup()
            }
        }
    }

    private fun broadcastStatus(text: String, connected: Boolean) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_TEXT, text)
            putExtra(EXTRA_CONNECTED, connected)
        })
    }

    private fun sendToWorklet(msg: JSONObject) {
        val bytes = (msg.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        ipc?.write(ByteBuffer.wrap(bytes)) { _ -> }
    }

    private fun stopVpn() {
        val stopMsg = JSONObject().apply { put("type", "stop") }
        sendToWorklet(stopMsg)
    }

    private fun cleanup() {
        worklet?.terminate()
        worklet = null
        ipc = null
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        // User revoked VPN permission
        stopVpn()
        super.onRevoke()
    }
}
