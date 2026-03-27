package com.nospoon.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.json.JSONObject
import java.io.FileDescriptor
import java.net.InetSocketAddress
import to.holepunch.bare.kit.IPC
import to.holepunch.bare.kit.Worklet
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class NospoonVpnService : VpnService() {

    companion object {
        const val TAG = "NospoonVPN"
        const val ACTION_START = "com.nospoon.vpn.START"
        const val ACTION_STOP = "com.nospoon.vpn.STOP"
        const val EXTRA_CONFIG_JSON = "configJson"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "nospoon_vpn"
        const val ACTION_STATUS = "com.nospoon.vpn.STATUS"
        const val ACTION_QUERY = "com.nospoon.vpn.QUERY"
        const val EXTRA_STATUS_TEXT = "statusText"
        const val EXTRA_CONNECTED = "connected"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var worklet: Worklet? = null
    private var ipc: IPC? = null
    private var ipcBuffer = StringBuilder()
    private var wakeLock: PowerManager.WakeLock? = null

    // Tracked state so Activity can query on resume
    private var currentStatusText = "Disconnected"
    private var currentConnected = false

    // Socket fd to protect — must be re-protected after VPN establish()
    private var protectedFd: Int = -1

    // Config stored for deferred startup (sent when worklet reports ready)
    private var pendingConfig: JSONObject? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: return START_NOT_STICKY
                val config = try {
                    JSONObject(configJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid config JSON: ${e.message}")
                    return START_NOT_STICKY
                }
                if (!config.has("server")) return START_NOT_STICKY
                startVpn(config)
            }
            ACTION_STOP -> stopVpn()
            ACTION_QUERY -> broadcastStatus(currentStatusText, currentConnected)
        }
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, NospoonVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("nospoon VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopIntent)
            .build()
    }

    private fun startForegroundNotification() {
        val channel = NotificationChannel(
            CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows VPN connection status"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startVpn(config: JSONObject) {
        // Tear down any existing connection before starting a new one
        if (worklet != null) {
            Log.d(TAG, "Cleaning up previous connection before restart")
            worklet?.terminate()
            worklet = null
            ipc = null
            vpnInterface?.close()
            vpnInterface = null
            ipcBuffer = StringBuilder()
        }

        startForegroundNotification()

        // Keep CPU awake so DHT keepalives aren't killed by Doze
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nospoon:vpn").apply {
                acquire()
            }
        }

        // Store config — sent to worklet when it reports "ready"
        pendingConfig = config

        // Start worklet — don't create IPC yet, native pipe isn't ready.
        // The worklet will send { type: "ready" } when IPC is initialized.
        worklet = Worklet(null)

        val bundle = assets.open("client.bundle")
        worklet!!.start("/client.bundle", bundle, null)

        // IPC must be created AFTER worklet.start() returns
        ipc = IPC(worklet)
        readNextIpcMessage()
    }

    // Phase 2: Called when worklet reports DHT is connected.
    // The DHT socket is already protected, so it bypasses VPN routing.
    private fun establishVpn() {
        val config = pendingConfig ?: return
        val ipFull = config.optString("ip", "10.0.0.2/24")
        val parts = ipFull.split("/")
        val ip = parts[0]
        val prefix = if (parts.size > 1) parts[1].toInt() else 24
        val mtu = config.optInt("mtu", 1400)
        val fullTunnel = config.optBoolean("fullTunnel", false)

        val builder = Builder()
            .setSession("nospoon")
            .setMtu(mtu)
            .addAddress(ip, prefix)

        if (fullTunnel) {
            builder.addRoute("0.0.0.0", 0)
            builder.addDisallowedApplication(packageName)
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")
            Log.d(TAG, "Full tunnel mode")
        } else {
            builder.addRoute(subnetAddress(ip, prefix), prefix)
            Log.d(TAG, "Subnet mode: ${subnetAddress(ip, prefix)}/$prefix")
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }

        // Re-protect the DHT socket NOW that VPN routes are active.
        // protect() before establish() may not survive VPN activation.
        if (protectedFd >= 0) {
            protect(protectedFd)
        }

        // Open a fresh file description via /proc so the new fd has its
        // own O_NONBLOCK flag (default: blocking). Android creates the TUN
        // with O_NONBLOCK, and dup() shares the same flag — but libuv's
        // uv_fs_read needs a blocking fd.
        val origFd = vpnInterface!!.fileDescriptor
        val fdField = FileDescriptor::class.java.getDeclaredField("descriptor")
        fdField.isAccessible = true
        val origFdNum = fdField.getInt(origFd)
        val tunFd = try {
            Os.open("/proc/self/fd/$origFdNum", OsConstants.O_RDWR, 0)
        } catch (e: ErrnoException) {
            Log.w(TAG, "/proc/self/fd open failed, falling back to dup: ${e.message}")
            val dupPfd = vpnInterface!!.dup()
            val fd = dupPfd.detachFd()
            val tmpFd = FileDescriptor()
            fdField.setInt(tmpFd, fd)
            val flags = Os.fcntlInt(tmpFd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(tmpFd, OsConstants.F_SETFL, flags and OsConstants.O_NONBLOCK.inv())
            fd
        }

        pendingConfig = null
        sendToWorklet(JSONObject().apply {
            put("type", "tun")
            put("tunFd", tunFd)
        })
    }

    // Continuous IPC listener — re-registers after each read so we
    // receive all messages, not just the first one.
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
            // Post to next event loop tick to avoid stack overflow —
            // IPC.read() calls the callback synchronously when data
            // is already available, which would recurse infinitely.
            handler.post { readNextIpcMessage() }
        }
    }

    private fun handleWorkletMessage(msg: JSONObject) {
        when (msg.getString("type")) {
            "ready" -> {
                val config = pendingConfig ?: return
                sendToWorklet(JSONObject().apply {
                    put("type", "start")
                    put("config", config)
                })
            }
            "protect" -> {
                // Exempt DHT socket from VPN routing.
                // Store the fd — it will be re-protected after establish()
                // since protect() only takes effect with an active VPN.
                var fd = msg.getInt("fd")
                if (fd < 0 && msg.has("port")) {
                    fd = findUdpFdByPort(msg.getInt("port"))
                }
                protectedFd = fd
                val ok = if (fd >= 0) protect(fd) else false
                sendToWorklet(JSONObject().apply {
                    put("type", "protected")
                    put("fd", fd)
                    put("ok", ok)
                })
            }
            "connected" -> {
                Log.i(TAG, "DHT connected, establishing VPN...")
                updateNotification("Connected")
                broadcastStatus("Connected", true)
                establishVpn()
            }
            "status" -> {
                val connected = msg.getBoolean("connected")
                val text = if (connected) "Connected" else "Reconnecting..."
                updateNotification(text)
                broadcastStatus(text, connected)
            }
            "identity" -> {
                Log.d(TAG, "Client public key: ${msg.getString("publicKey")}")
            }
            "error" -> {
                Log.e(TAG, "Worklet error: ${msg.getString("message")}")
                broadcastStatus("Error: ${msg.getString("message")}", false)
            }
            "stopped" -> {
                broadcastStatus("Disconnected", false)
                cleanup()
            }
        }
    }

    private fun broadcastStatus(text: String, connected: Boolean) {
        currentStatusText = text
        currentConnected = connected
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_TEXT, text)
            putExtra(EXTRA_CONNECTED, connected)
        })
    }

    private fun subnetAddress(hostIp: String, prefix: Int): String {
        val parts = hostIp.split(".").map { it.toInt() }
        val ipInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = ipInt and mask
        return "${(network shr 24) and 0xFF}.${(network shr 16) and 0xFF}.${(network shr 8) and 0xFF}.${network and 0xFF}"
    }

    private fun findUdpFdByPort(port: Int): Int {
        val fdField = FileDescriptor::class.java.getDeclaredField("descriptor")
        fdField.isAccessible = true
        for (candidate in 3..1023) {
            try {
                val fd = FileDescriptor()
                fdField.setInt(fd, candidate)
                val addr = Os.getsockname(fd)
                if (addr is InetSocketAddress && addr.port == port) {
                    return candidate
                }
            } catch (_: ErrnoException) {}
        }
        Log.e(TAG, "Could not find UDP socket for port $port")
        return -1
    }

    private fun sendToWorklet(msg: JSONObject) {
        val bytes = (msg.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocateDirect(bytes.size)
        buf.put(bytes)
        buf.flip()
        ipc?.write(buf) { _ -> }
    }

    private fun stopVpn() {
        sendToWorklet(JSONObject().apply { put("type", "stop") })
    }

    private fun cleanup() {
        worklet?.terminate()
        worklet = null
        ipc = null
        vpnInterface?.close()
        vpnInterface = null
        protectedFd = -1
        pendingConfig = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
