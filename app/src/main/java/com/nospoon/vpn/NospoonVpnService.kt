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
        const val EXTRA_SERVER_KEY = "serverKey"
        const val EXTRA_SEED = "seed"
        const val EXTRA_IP = "ip"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_FULL_TUNNEL = "fullTunnel"
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
    private var pendingServerKey: String? = null
    private var pendingSeed: String? = null
    private var pendingIp: String? = null
    private var pendingPrefix: Int = 24
    private var pendingMtu: Int = 1400
    private var pendingFullTunnel: Boolean = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val serverKey = intent.getStringExtra(EXTRA_SERVER_KEY) ?: return START_NOT_STICKY
                val seed = intent.getStringExtra(EXTRA_SEED)
                val ip = intent.getStringExtra(EXTRA_IP) ?: "10.0.0.2"
                val mtu = intent.getIntExtra(EXTRA_MTU, 1400)
                val fullTunnel = intent.getBooleanExtra(EXTRA_FULL_TUNNEL, true)
                startVpn(serverKey, seed, ip, mtu, fullTunnel)
            }
            ACTION_STOP -> stopVpn()
            ACTION_QUERY -> broadcastStatus(currentStatusText, currentConnected)
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        // Create notification channel
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows VPN connection status"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        // Intent to open the app
        val tapIntent = Intent(this, MainActivity::class.java)
        val pendingTap = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to stop VPN
        val stopIntent = Intent(this, NospoonVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build persistent notification
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("nospoon VPN")
            .setContentText("Connecting...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingTap)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                pendingStop
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        
        // Intent to open the app
        val tapIntent = Intent(this, MainActivity::class.java)
        val pendingTap = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to stop VPN
        val stopIntent = Intent(this, NospoonVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("nospoon VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingTap)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                pendingStop
            )
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun startVpn(serverKey: String, seed: String?, ip: String, mtu: Int, fullTunnel: Boolean = true) {
        // Tear down any existing connection before starting a new one
        if (worklet != null) {
            Log.i(TAG, "Cleaning up previous connection before restart")
            worklet?.terminate()
            worklet = null
            ipc = null
            vpnInterface?.close()
            vpnInterface = null
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
        pendingServerKey = serverKey
        pendingSeed = seed
        val parts = ip.split("/")
        pendingIp = parts[0]
        pendingPrefix = if (parts.size > 1) parts[1].toInt() else 24
        pendingMtu = mtu
        pendingFullTunnel = fullTunnel

        // Start worklet — don't create IPC yet, native pipe isn't ready.
        // The worklet will send { type: "ready" } when IPC is initialized.
        worklet = Worklet(null)

        val bundle = assets.open("client.bundle")
        worklet!!.start("/client.bundle", bundle, null)

        // IPC must be created AFTER worklet.start() returns
        ipc = IPC(worklet)
        setupIpcListener()
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

        if (pendingFullTunnel) {
            // Full tunnel: route all traffic through VPN
            builder.addRoute("0.0.0.0", 0)

            // Exclude our own app — our DHT socket must go direct (not through VPN).
            builder.addDisallowedApplication(packageName)

            // DNS through the tunnel
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")

            Log.i(TAG, "Full tunnel: routing all traffic through VPN")
        } else {
            // Subnet only: route only VPN subnet traffic
            builder.addRoute(subnetAddress(ip, prefix), prefix)

            Log.i(TAG, "Subnet only: routing ${subnetAddress(ip, prefix)}/$prefix through VPN")
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
            val ok = protect(protectedFd)
            Log.i(TAG, "protect(fd=$protectedFd): $ok (post-establish)")
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
            // Fallback: dup + clear O_NONBLOCK
            Log.w(TAG, "/proc/self/fd open failed, falling back to dup: ${e.message}")
            val dupPfd = vpnInterface!!.dup()
            val fd = dupPfd.detachFd()
            val tmpFd = FileDescriptor()
            fdField.setInt(tmpFd, fd)
            val flags = Os.fcntlInt(tmpFd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(tmpFd, OsConstants.F_SETFL, flags and OsConstants.O_NONBLOCK.inv())
            fd
        }
        Log.i(TAG, "VPN established, TUN fd: $tunFd (blocking)")

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
            // Post to next event loop tick to avoid stack overflow —
            // IPC.read() calls the callback synchronously when data
            // is already available, which would recurse infinitely.
            handler.post { readNextIpcMessage() }
        }
    }

    private fun handleWorkletMessage(msg: JSONObject) {
        when (msg.getString("type")) {
            "ready" -> {
                // Worklet IPC is initialized — send the start config
                Log.i(TAG, "Worklet ready, sending start config")
                val startMsg = JSONObject().apply {
                    put("type", "start")
                    put("serverKey", pendingServerKey)
                    pendingSeed?.let { put("seed", it) }
                }
                sendToWorklet(startMsg)
            }
            "protect" -> {
                // Exempt DHT socket from VPN routing (split-tunnel mode).
                // Store the fd — it will be re-protected after establish()
                // since protect() only takes effect with an active VPN.
                var fd = msg.getInt("fd")
                if (fd < 0 && msg.has("port")) {
                    fd = findUdpFdByPort(msg.getInt("port"))
                }
                protectedFd = fd
                val ok = if (fd >= 0) protect(fd) else false
                Log.i(TAG, "protect(fd=$fd): $ok (pre-establish)")
                val reply = JSONObject().apply {
                    put("type", "protected")
                    put("fd", fd)
                    put("ok", ok)
                }
                sendToWorklet(reply)
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
            "stats" -> {
                val tunRead = msg.optInt("tunRead", 0)
                val tunWrite = msg.optInt("tunWrite", 0)
                val tunReadErr = msg.optInt("tunReadErr", 0)
                val tunWriteErr = msg.optInt("tunWriteErr", 0)
                Log.i(TAG, "Stats: tunRead=$tunRead tunWrite=$tunWrite readErr=$tunReadErr writeErr=$tunWriteErr")
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
        currentStatusText = text
        currentConnected = connected
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_TEXT, text)
            putExtra(EXTRA_CONNECTED, connected)
        })
    }

    // Compute the network address from a host IP and prefix length.
    // e.g. subnetAddress("10.0.0.2", 24) → "10.0.0.0"
    private fun subnetAddress(hostIp: String, prefix: Int): String {
        val parts = hostIp.split(".").map { it.toInt() }
        val ipInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = ipInt and mask
        return "${(network shr 24) and 0xFF}.${(network shr 16) and 0xFF}.${(network shr 8) and 0xFF}.${network and 0xFF}"
    }

    // Find a UDP socket's fd by its local port number.
    // Used when the Bare runtime can't expose the fd directly.
    private fun findUdpFdByPort(port: Int): Int {
        val fdField = FileDescriptor::class.java.getDeclaredField("descriptor")
        fdField.isAccessible = true
        for (candidate in 3..1023) {
            try {
                val fd = FileDescriptor()
                fdField.setInt(fd, candidate)
                val addr = Os.getsockname(fd)
                if (addr is InetSocketAddress && addr.port == port) {
                    Log.i(TAG, "Found UDP socket: fd=$candidate port=$port")
                    return candidate
                }
            } catch (_: ErrnoException) {
                // EBADF or ENOTSOCK — skip
            }
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
        val stopMsg = JSONObject().apply { put("type", "stop") }
        sendToWorklet(stopMsg)
    }

    private fun cleanup() {
        worklet?.terminate()
        worklet = null
        ipc = null
        vpnInterface?.close()
        vpnInterface = null
        protectedFd = -1
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
        // User revoked VPN permission
        stopVpn()
        super.onRevoke()
    }
}
