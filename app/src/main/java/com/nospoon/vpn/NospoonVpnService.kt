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
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.hyperdht.DhtException
import com.hyperdht.DhtOptions
import com.hyperdht.HyperDHT
import com.hyperdht.KeyPair
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
        const val EXTRA_CONFIG_JSON = "configJson"
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

        // After this many consecutive connect failures we tear down the
        // HyperDHT instance and create a fresh one — the underlying UDP
        // socket has likely been left bound to a dead interface (Wi-Fi →
        // mobile-data switch, or it never got a valid local interface on
        // a cold start).  Matches the JS impl and cpp/ client.
        private const val MAX_FAILURES_BEFORE_RESTART = 3
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var dht: HyperDHT? = null

    // The currently-active stream.  Read by the tun→stream pump; replaced
    // by the reconnect loop on every new connection.
    @Volatile private var activeStream: Stream? = null

    private var vpnInterface: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_nospoon)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.disconnect),
                stopIntent
            )
            .build()
    }

    private fun startForegroundNotification() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notif_connecting_default))
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Publish a new state to [ConnectionStateRepository] and refresh the
     * foreground notification to match. Safe to call from any thread —
     * notification update is hopped to the main looper.
     */
    private fun setStatus(state: ConnectionState) {
        ConnectionStateRepository.update(state)
        val text = stateToText(state)
        mainHandler.post { updateNotification(text) }
    }

    /** Localized presentation string for a given [ConnectionState]. */
    private fun stateToText(state: ConnectionState): String = when (state) {
        ConnectionState.Disconnected -> getString(R.string.status_disconnected)
        ConnectionState.Connecting -> getString(R.string.status_connecting_short)
        ConnectionState.Connected -> getString(R.string.status_connected)
        ConnectionState.Reconnecting -> getString(R.string.status_reconnecting)
        is ConnectionState.Error -> state.message
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
        setStatus(ConnectionState.Connecting)

        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nospoon:vpn").apply {
                acquire()
            }
        }

        // NOTE: deliberately do not establish the VPN here — we want the
        // DHT bootstrap + first peer connect to happen *before* any VPN
        // routes/DNS overrides are in place, mirroring the bare-runtime
        // architecture.  See runVpnLoop().

        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        s.launch {
            try {
                runVpnLoop(config)
            } catch (e: Exception) {
                Log.e(TAG, "VPN loop crashed: ${e.javaClass.simpleName}: ${e.message}", e)
                setStatus(ConnectionState.Error(translateError(e)))
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
            setStatus(ConnectionState.Error(
                getString(R.string.status_error_prefix, getString(R.string.vpn_permission_denied))
            ))
            return false
        }
        vpnInterface = pfd
        return true
    }

    /**
     * DHT-first / VPN-later lifecycle.
     *
     *   1. Bring up the DHT and bootstrap on the *real* network (no VPN
     *      routes in the way; the bare-runtime worklet did exactly this).
     *   2. Try `dht.connect(serverPk)` until one succeeds and the
     *      SecretStream opens.
     *   3. Only THEN call VpnService.Builder.establish(): we now know we
     *      have a working tunnel, the kernel routes are safe to install,
     *      and the freshly-opened DHT socket has already bound to the
     *      correct underlying interface.
     *   4. Spin up tun→stream pump and stream→tun forwarder.
     *   5. On stream close (peer disconnect, network change), keep the
     *      VpnService TUN alive and just reconnect the SecretStream.
     */
    private suspend fun runVpnLoop(config: JSONObject) = coroutineScope {
        val serverPk = hexToBytes(config.getString("server"))
        val seedBytes = config.optString("seed", "")
            .takeIf { it.isNotEmpty() }
            ?.let { hexToBytes(it) }

        // Print the client's public key on startup so the user can verify
        // it matches whatever's whitelisted on the server's firewall.
        // (LAN test working today already proves these match — but mobile
        // data troubleshooting needs visibility.)
        if (seedBytes != null) {
            try {
                val pk = KeyPair.fromSeed(seedBytes).publicKey
                Log.i(TAG, "Our public key (must be in server peers config): " +
                        bytesToHex(pk))
            } catch (e: Exception) {
                Log.w(TAG, "Could not derive public key from seed: ${e.message}")
            }
        } else {
            Log.i(TAG, "No seed in config — using ephemeral keypair " +
                    "(server with firewall will reject)")
        }
        Log.i(TAG, "Connecting to server pubkey: ${bytesToHex(serverPk)}")

        // Factory — used both for the initial DHT and for restart_dht-style
        // rebuilds after persistent connect failures (network switch leaves
        // the UDP socket bound to a dead interface).
        fun makeDht(): HyperDHT = HyperDHT(DhtOptions(
            usePublicBootstrap = true,
            connectionKeepAlive = DHT_KEEPALIVE_MS,
            seed = seedBytes,
        ))

        // We DON'T act on dht.onNetworkChange — it fires when our own
        // VpnService.Builder.establish() registers the TUN as a new
        // network, which would self-trigger a reconnect.  Instead we
        // detect a dead UDP socket by counting consecutive connect
        // failures and rebuild the whole HyperDHT instance from scratch.
        fun installNetChangeLogger(d: HyperDHT) {
            d.onNetworkChange {
                Log.d(TAG, "DHT reports network change (ignored; failure-counted)")
            }
        }

        var dhtNode = makeDht()
        dht = dhtNode
        dhtNode.start()
        Log.i(TAG, "DHT listening on port ${dhtNode.port} — bootstrapping (no VPN yet)")
        dhtNode.awaitBootstrapped()
        Log.i(TAG, "DHT bootstrapped")
        installNetChangeLogger(dhtNode)
        protectDhtSockets(dhtNode)

        var tunReaderJob: Job? = null
        var retryMs = INITIAL_RETRY_MS
        var consecutiveFailures = 0

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
                    consecutiveFailures = 0

                    // Phase 2: first successful connect → bring up the TUN.
                    // Subsequent reconnects keep the existing interface.
                    if (vpnInterface == null) {
                        if (!establishVpn(config)) {
                            Log.e(TAG, "VPN establish failed after first connect — aborting")
                            try { s.close() } catch (_: Exception) {}
                            break
                        }
                        Log.i(TAG, "VPN interface up; starting forwarders")

                        // VpnService hands us a non-blocking fd by default
                        // on some Android versions.  FileInputStream.read()
                        // on a non-blocking fd can return 0 immediately
                        // when no packet is queued, which our loop would
                        // misinterpret as EOF and exit silently — every
                        // subsequent ping would then be dropped on read.
                        // Force blocking mode (same as the bare-runtime
                        // impl) so read() suspends until a packet arrives.
                        try {
                            val flags = Os.fcntlInt(
                                vpnInterface!!.fileDescriptor,
                                OsConstants.F_GETFL, 0)
                            Os.fcntlInt(
                                vpnInterface!!.fileDescriptor,
                                OsConstants.F_SETFL,
                                flags and OsConstants.O_NONBLOCK.inv())
                            Log.d(TAG, "TUN fd set to blocking (was flags=$flags)")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to clear O_NONBLOCK on TUN: ${e.message}")
                        }

                        // Lifetime-of-VPN tun→stream pump.  Always writes
                        // to whatever stream is currently active; drops
                        // while activeStream is null (between reconnects).
                        // Cancellation comes via closing vpnInterface in
                        // cleanup(), which makes the blocking read() throw.
                        val pfd = vpnInterface!!
                        tunReaderJob = launch {
                            val tunIn = FileInputStream(pfd.fileDescriptor)
                            val readBuf = ByteArray(64 * 1024)  // > any plausible MTU
                            var packetCount = 0L
                            while (isActive) {
                                val n = try {
                                    tunIn.read(readBuf)
                                } catch (e: Exception) {
                                    Log.d(TAG, "tun read ended: ${e.message}")
                                    break
                                }
                                if (n < 0) {
                                    Log.d(TAG, "tun read returned $n — EOF")
                                    break
                                }
                                if (n == 0) continue  // shouldn't happen on a blocking fd, but tolerate
                                val cur = activeStream
                                if (cur == null) continue  // drop while reconnecting
                                if (packetCount < 5L || packetCount % 200L == 0L) {
                                    Log.d(TAG, "tun→stream: $n bytes (#${packetCount + 1})")
                                }
                                packetCount++
                                try {
                                    cur.write(Framing.encode(readBuf, 0, n))
                                } catch (e: Exception) {
                                    Log.d(TAG, "stream write failed: ${e.message}")
                                }
                            }
                        }
                    }

                    activeStream = s
                    setStatus(ConnectionState.Connected)
                    retryMs = INITIAL_RETRY_MS

                    forwardStreamToTun(s, vpnInterface!!)  // suspends until stream ends

                    Log.i(TAG, "Stream closed")
                } catch (e: Exception) {
                    Log.w(TAG, "Forward error: ${e.message}")
                } finally {
                    activeStream = null
                    try { s.close() } catch (_: Exception) {}
                }
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_FAILURES_BEFORE_RESTART && isActive) {
                    Log.w(TAG, "$consecutiveFailures consecutive connect failures — " +
                            "rebuilding DHT (UDP socket likely on a dead interface)")
                    setStatus(ConnectionState.Reconnecting)

                    val oldDht = dhtNode
                    dhtNode = makeDht()
                    dht = dhtNode
                    dhtNode.start()
                    Log.i(TAG, "Bootstrapping replacement DHT...")
                    dhtNode.awaitBootstrapped()
                    Log.i(TAG, "Replacement DHT bootstrapped on port ${dhtNode.port}")
                    installNetChangeLogger(dhtNode)
                    protectDhtSockets(dhtNode)

                    // Tear down the old DHT off the loop thread (close()
                    // does runBlocking internally).  Fire-and-forget.
                    @Suppress("OPT_IN_USAGE")
                    GlobalScope.launch(Dispatchers.IO) {
                        try { oldDht.close() } catch (e: Exception) {
                            Log.w(TAG, "old dht.close error: ${e.message}")
                        }
                    }

                    consecutiveFailures = 0
                    retryMs = INITIAL_RETRY_MS
                    continue  // try connect again immediately on the new DHT
                }
            }

            if (!isActive) break

            setStatus(ConnectionState.Reconnecting)
            val jitter = (Math.random() * 1000).toLong()
            delay(retryMs + jitter)
            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
        }

        tunReaderJob?.cancel()
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
            var packetCount = 0L

            val keepalive = launch {
                while (isActive) {
                    delay(FRAMING_KEEPALIVE_INTERVAL_MS)
                    try { s.write(Framing.keepalive) } catch (_: Exception) { return@launch }
                }
            }

            try {
                s.data.collect { chunk ->
                    decoder.feed(chunk) { pkt ->
                        if (packetCount < 5L || packetCount % 200L == 0L) {
                            Log.d(TAG, "stream→tun: ${pkt.size}-byte packet (#${packetCount + 1})")
                        }
                        packetCount++
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
        setStatus(ConnectionState.Disconnected)
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

    /**
     * Map low-level connection failures to user-facing localized text.
     * Pattern-matches well-known token strings emitted by hyperdht-cpp
     * (e.g. "HOLEPUNCH_FAILED", "-5") and falls back to a generic
     * "Connection error: <raw>" for anything we don't recognize.
     */
    private fun translateError(e: Throwable): String {
        val raw = (e.message ?: "").lowercase()
        val key = when {
            "holepunch" in raw || "-5" in raw -> R.string.err_holepunch_failed
            "unreachable" in raw || "no route" in raw || "enetunreach" in raw ->
                R.string.err_network_unreachable
            "permission" in raw -> R.string.err_permission_denied
            "invalid" in raw && ("cidr" in raw || "config" in raw || "hex" in raw) ->
                R.string.err_invalid_config
            else -> null
        }
        return if (key != null) {
            getString(R.string.status_error_prefix, getString(key))
        } else {
            getString(R.string.err_generic, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /**
     * Mark the DHT's UDP sockets as bypassing the VPN tunnel.  Called once
     * after each [HyperDHT.awaitBootstrapped] (initial + restart-on-failure).
     *
     * Without this, sockets created after VpnService.Builder.establish()
     * get steered through the VPN's routing rules.  DHT bootstrap (one-way
     * chatter to public bootstrap nodes) often still works, but holepunch
     * RTT requirements blow past timeouts and connect() fails with
     * HOLEPUNCH_FAILED on every attempt.  This was the root cause of the
     * "Wi-Fi → mobile-data switch + reconnect = -5 forever" symptom.
     *
     * We protect both sockets — the client (ephemeral outbound, used while
     * firewalled — i.e. always on a phone) and the server (used if the
     * node ever transitions out of firewalled state).
     */
    private fun protectDhtSockets(dhtNode: HyperDHT) {
        val cFd = dhtNode.clientSocketFd
        val sFd = dhtNode.serverSocketFd
        if (cFd >= 0) {
            val ok = protect(cFd)
            Log.i(TAG, "protect(clientSocketFd=$cFd) → $ok")
        } else {
            Log.w(TAG, "clientSocketFd unavailable — protect skipped")
        }
        if (sFd >= 0) {
            val ok = protect(sFd)
            Log.i(TAG, "protect(serverSocketFd=$sFd) → $ok")
        } else {
            Log.w(TAG, "serverSocketFd unavailable — protect skipped")
        }
    }
}
