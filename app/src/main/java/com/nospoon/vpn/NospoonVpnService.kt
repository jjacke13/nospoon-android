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
import android.util.Log
import com.hyperdht.DhtException
import com.hyperdht.DhtOptions
import com.hyperdht.HyperDHT
import com.hyperdht.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Foreground VPN service that bridges the Android TUN device to a remote
 * peer over an encrypted SecretStream channel exposed by the
 * hyperdht-cpp Kotlin wrapper.
 *
 * Architecture (no native binary, no JNI fork+exec):
 *
 *   VpnService.Builder       — owns TUN fd; configures IP/MTU/routes
 *           │
 *           ▼
 *   FileInputStream(fd) ──► tun→stream coroutine ──► Stream.write(Framing.encode(pkt))
 *           ▲
 *           │
 *   FileOutputStream(fd) ◄─ stream→tun coroutine ◄── FrameDecoder.feed(Stream.data)
 *
 *   HyperDHT (com.hyperdht wrapper) owns its own libuv loop on a dedicated
 *   thread; we only touch its public suspend API. dht.onNetworkChange { … }
 *   is what keeps the connection alive across Wi-Fi ↔ mobile-data switches:
 *   we close the current stream, the outer reconnect loop opens a fresh
 *   one, and the underlying UDP socket is rebound by the wrapper.
 */
class NospoonVpnService : VpnService() {

    companion object {
        const val TAG = "NospoonVPN"
        const val ACTION_START = "com.nospoon.vpn.START"
        const val ACTION_STOP = "com.nospoon.vpn.STOP"
        const val ACTION_QUERY = "com.nospoon.vpn.QUERY"
        const val ACTION_STATUS = "com.nospoon.vpn.STATUS"
        const val EXTRA_CONFIG_JSON = "configJson"
        const val EXTRA_STATUS_TEXT = "statusText"
        const val EXTRA_CONNECTED = "connected"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "nospoon_vpn"

        // Match cpp/framing.hpp KEEPALIVE_INTERVAL_MS — keeps the SecretStream
        // alive across NATs / mobile carrier UDP timeouts.
        private const val FRAMING_KEEPALIVE_INTERVAL_MS = 25_000L

        // DHT-level keepalive (UDP-layer pings between peers).  Default in the
        // wrapper is 5 s; bump to 25 s to align with framing and reduce
        // mobile-radio wakeups.
        private const val DHT_KEEPALIVE_MS = 25_000

        // Reconnect backoff
        private const val INITIAL_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 30_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var dht: HyperDHT? = null

    // The currently-active stream.  Read by the tun→stream pump; replaced
    // by the reconnect loop on every new connection.
    @Volatile private var activeStream: Stream? = null

    private var vpnInterface: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentStatusText = "Disconnected"
    private var currentConnected = false

    // ─────────────────────────────────────────────────────────────────
    // Service entry points
    // ─────────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
                    ?: return START_NOT_STICKY
                val config = try {
                    JSONObject(configJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid config JSON: ${e.message}")
                    return START_NOT_STICKY
                }
                if (!config.has("server")) return START_NOT_STICKY
                startVpn(config)
                return START_STICKY
            }
            ACTION_STOP -> {
                cleanup()
                return START_NOT_STICKY
            }
            ACTION_QUERY -> {
                broadcastStatus(currentStatusText, currentConnected)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onRevoke() {
        cleanup()
        super.onRevoke()
    }

    // ─────────────────────────────────────────────────────────────────
    // Notification + status broadcast (unchanged)
    // ─────────────────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
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

    private fun broadcastStatus(text: String, connected: Boolean) {
        currentStatusText = text
        currentConnected = connected
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_TEXT, text)
            putExtra(EXTRA_CONNECTED, connected)
        })
    }

    private fun setStatus(text: String, connected: Boolean) {
        mainHandler.post {
            updateNotification(text)
            broadcastStatus(text, connected)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // VPN lifecycle
    // ─────────────────────────────────────────────────────────────────

    private fun startVpn(config: JSONObject) {
        if (scope != null) {
            Log.d(TAG, "Cleaning up previous connection before restart")
            cleanup()
        }

        startForegroundNotification()
        broadcastStatus("Connecting...", false)

        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nospoon:vpn").apply {
                acquire()
            }
        }

        if (!establishVpn(config)) {
            cleanup()
            return
        }

        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        s.launch {
            try {
                runVpnLoop(config)
            } catch (e: Exception) {
                Log.e(TAG, "VPN loop crashed: ${e.javaClass.simpleName}: ${e.message}", e)
                broadcastStatus("Error: ${e.message}", false)
            } finally {
                mainHandler.post { cleanup() }
            }
        }
    }

    /** Configure and bring up the TUN interface.  Sets [vpnInterface] on success. */
    private fun establishVpn(config: JSONObject): Boolean {
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
            // Our own DHT/IPC traffic must NOT loop through the VPN —
            // otherwise the bootstrap dies.  addDisallowedApplication is
            // the canonical way to carve our own UID out of the tunnel.
            builder.addDisallowedApplication(packageName)
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")
            Log.d(TAG, "Full tunnel mode")
        } else {
            builder.addRoute(subnetAddress(ip, prefix), prefix)
            Log.d(TAG, "Subnet mode: ${subnetAddress(ip, prefix)}/$prefix")
        }

        val pfd = builder.establish()
        if (pfd == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            broadcastStatus("Error: VPN permission denied", false)
            return false
        }
        vpnInterface = pfd
        return true
    }

    /**
     * Outer connect/forward/reconnect loop.  Stays in this coroutine for
     * the lifetime of the VPN session.  Returns when [scope] is cancelled.
     */
    private suspend fun runVpnLoop(config: JSONObject) = coroutineScope {
        val serverPk = hexToBytes(config.getString("server"))
        val seedBytes = config.optString("seed", "")
            .takeIf { it.isNotEmpty() }
            ?.let { hexToBytes(it) }

        val dhtNode = HyperDHT(DhtOptions(
            usePublicBootstrap = true,
            connectionKeepAlive = DHT_KEEPALIVE_MS,
            seed = seedBytes,
        ))
        dht = dhtNode
        dhtNode.start()
        Log.i(TAG, "DHT listening on port ${dhtNode.port} — bootstrapping")
        dhtNode.awaitBootstrapped()
        Log.i(TAG, "DHT bootstrapped")

        // Kick the reconnect path on network change — closing activeStream
        // unblocks the inner forwarding loop, which loops back up to
        // dhtNode.connect() against the freshly-bound socket.
        dhtNode.onNetworkChange {
            Log.i(TAG, "Network changed — forcing stream reconnect")
            try { activeStream?.close() } catch (_: Exception) {}
        }

        // Lifetime-of-VPN tun→stream pump.  Reads block in the IO thread
        // pool; cancellation is delivered by closing vpnInterface in
        // cleanup(), which makes read() throw.
        val pfd = vpnInterface!!
        val tunReaderJob = launch {
            val tunIn = FileInputStream(pfd.fileDescriptor)
            val readBuf = ByteArray(64 * 1024)  // > any plausible MTU
            while (isActive) {
                val n = try {
                    tunIn.read(readBuf)
                } catch (e: Exception) {
                    Log.d(TAG, "tun read ended: ${e.message}")
                    break
                }
                if (n <= 0) break
                val s = activeStream ?: continue  // drop while reconnecting
                try {
                    s.write(Framing.encode(readBuf, 0, n))
                } catch (e: Exception) {
                    Log.d(TAG, "stream write failed: ${e.message}")
                    // outer loop will detect via stream.data ending
                }
            }
        }

        // Connect/forward/reconnect inner loop.
        var retryMs = INITIAL_RETRY_MS
        while (isActive) {
            val s = try {
                Log.i(TAG, "Connecting to server...")
                dhtNode.connect(serverPk)
            } catch (e: DhtException) {
                Log.w(TAG, "Connect failed: ${e.code} ${e.message}")
                null
            } catch (e: Exception) {
                Log.w(TAG, "Connect error: ${e.javaClass.simpleName}: ${e.message}")
                null
            }

            if (s != null) {
                try {
                    s.awaitOpen()
                    Log.i(TAG, "Stream open — encrypted tunnel established")
                    activeStream = s
                    setStatus("Connected", true)
                    retryMs = INITIAL_RETRY_MS

                    forwardStreamToTun(s, pfd)  // suspends until stream ends

                    Log.i(TAG, "Stream closed")
                } catch (e: Exception) {
                    Log.w(TAG, "Forward error: ${e.message}")
                } finally {
                    activeStream = null
                    try { s.close() } catch (_: Exception) {}
                }
            }

            if (!isActive) break

            setStatus("Reconnecting...", false)
            val jitter = (Math.random() * 1000).toLong()
            delay(retryMs + jitter)
            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
        }

        tunReaderJob.cancel()
    }

    /**
     * Fan out [s.data] frames into the TUN, plus a 25 s framing keepalive.
     * Returns when the stream's data flow completes (peer closed) or the
     * scope is cancelled.
     */
    private suspend fun forwardStreamToTun(s: Stream, pfd: ParcelFileDescriptor) =
        coroutineScope {
            val tunOut = FileOutputStream(pfd.fileDescriptor)
            val decoder = FrameDecoder()

            val keepalive = launch {
                while (isActive) {
                    delay(FRAMING_KEEPALIVE_INTERVAL_MS)
                    try { s.write(Framing.keepalive) } catch (_: Exception) { return@launch }
                }
            }

            try {
                s.data.collect { chunk ->
                    decoder.feed(chunk) { pkt ->
                        try { tunOut.write(pkt) } catch (e: Exception) {
                            Log.w(TAG, "TUN write failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "stream.data ended: ${e.message}")
            } finally {
                keepalive.cancel()
            }
        }

    // ─────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────

    private fun cleanup() {
        // Order matters: cancel coroutines first so they don't try to use
        // resources we're about to free.
        scope?.cancel()
        scope = null

        try { activeStream?.close() } catch (_: Exception) {}
        activeStream = null

        // dht.close() does runBlocking internally — must run off the main
        // thread.  Hand it off; it's fire-and-forget.
        val d = dht
        dht = null
        if (d != null) {
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch(Dispatchers.IO) {
                try { d.close() } catch (e: Exception) {
                    Log.w(TAG, "dht.close error: ${e.message}")
                }
            }
        }

        // Closing vpnInterface unblocks any pending TUN read in
        // tunReaderJob (read() throws), which lets the outer scope unwind.
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        broadcastStatus("Disconnected", false)
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private fun subnetAddress(hostIp: String, prefix: Int): String {
        val parts = hostIp.split(".").map { it.toInt() }
        val ipInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = ipInt and mask
        return "${(network shr 24) and 0xFF}.${(network shr 16) and 0xFF}." +
                "${(network shr 8) and 0xFF}.${network and 0xFF}"
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
