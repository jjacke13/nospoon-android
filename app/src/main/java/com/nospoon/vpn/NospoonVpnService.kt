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
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStreamReader

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
    private var nospoonPid: Int = -1
    private var ipcSocketFd: Int = -1
    private var ipcReader: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pendingConfig: JSONObject? = null

    private var currentStatusText = "Disconnected"
    private var currentConnected = false
    private var tunFdForBinary: Int = -1

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
                return START_STICKY
            }
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_QUERY -> {
                broadcastStatus(currentStatusText, currentConnected)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
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
        if (nospoonPid > 0) {
            Log.d(TAG, "Cleaning up previous connection before restart")
            cleanup()
        }

        pendingConfig = config
        startForegroundNotification()

        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nospoon:vpn").apply {
                acquire()
            }
        }

        // Write config to temp file
        val configFile = File(cacheDir, "nospoon-config.jsonc")
        configFile.writeText(config.toString())

        val binaryPath = applicationInfo.nativeLibraryDir + "/libnospoon.so"
        if (!File(binaryPath).exists()) {
            Log.e(TAG, "nospoon binary not found at $binaryPath")
            broadcastStatus("Error: binary not found", false)
            cleanup()
            return
        }

        // Phase 1: Fork binary with --fd-socket (NO VPN yet).
        // Binary connects DHT over regular internet — works on same LAN.
        // The socketpair is used for IPC: binary sends "CONNECTED",
        // we send back the TUN fd via SCM_RIGHTS.
        val result = NativeHelper.exec(arrayOf(
            binaryPath, "up", "--fd-socket=CHILD_SOCK", configFile.absolutePath
        ))

        if (result == null || result[0] <= 0) {
            Log.e(TAG, "Failed to fork nospoon binary")
            broadcastStatus("Error: fork failed", false)
            cleanup()
            return
        }

        nospoonPid = result[0]
        ipcSocketFd = result[1]
        val childSockFd = result[2]

        // Fix up the --fd-socket argument with the actual child fd number.
        // The child already has it inherited; we need to tell it which fd.
        // Actually, we need to pass the fd number BEFORE exec...
        // Let's use a different approach: pass it as the last arg.

        Log.i(TAG, "nospoon child pid: $nospoonPid, ipc socket: $ipcSocketFd, child sock: $childSockFd")
        broadcastStatus("Connecting...", false)

        // Read IPC messages from child on a background thread
        ipcReader = Thread {
            val fdField = FileDescriptor::class.java.getDeclaredField("descriptor")
            fdField.isAccessible = true
            try {
                val fis = FileInputStream(FileDescriptor().also {
                    fdField.setInt(it, ipcSocketFd)
                })
                val reader = BufferedReader(InputStreamReader(fis))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!.trim()
                    if (l.isEmpty()) continue
                    Log.d(TAG, "ipc: $l")

                    if (l == "CONNECTED") {
                        // Phase 2: DHT connected — establish VPN and send TUN fd
                        handler.post { establishAndSendTunFd(config) }
                    } else if (l == "STATUS:connected") {
                        handler.post {
                            updateNotification("Connected")
                            broadcastStatus("Connected", true)
                        }
                    } else if (l == "STATUS:reconnecting") {
                        handler.post {
                            updateNotification("Reconnecting...")
                            broadcastStatus("Reconnecting...", false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "ipc reader ended: ${e.message}")
            }

            handler.post {
                Log.i(TAG, "nospoon process exited")
                broadcastStatus("Disconnected", false)
                cleanup()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    // Phase 2: Called when binary reports DHT is connected.
    // NOW establish VPN and send TUN fd to binary via SCM_RIGHTS.
    private fun establishAndSendTunFd(config: JSONObject) {
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
            broadcastStatus("Error: VPN permission denied", false)
            cleanup()
            return
        }

        // Get a blocking TUN fd
        val fdField = FileDescriptor::class.java.getDeclaredField("descriptor")
        fdField.isAccessible = true

        val dupPfd = vpnInterface!!.dup()
        val tunFd = dupPfd.detachFd()
        val tunFdObj = FileDescriptor()
        fdField.setInt(tunFdObj, tunFd)

        val fileFlags = Os.fcntlInt(tunFdObj, OsConstants.F_GETFL, 0)
        Os.fcntlInt(tunFdObj, OsConstants.F_SETFL, fileFlags and OsConstants.O_NONBLOCK.inv())

        tunFdForBinary = tunFd
        Log.d(TAG, "Sending TUN fd $tunFd to child via SCM_RIGHTS")

        // Send TUN fd to child via the socketpair
        val ok = NativeHelper.sendFd(ipcSocketFd, tunFd)
        if (!ok) {
            Log.e(TAG, "Failed to send TUN fd to child")
            broadcastStatus("Error: fd send failed", false)
            cleanup()
            return
        }

        Log.i(TAG, "VPN established, TUN fd sent to child")
        updateNotification("Connected")
        broadcastStatus("Connected", true)
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

    private fun stopVpn() {
        if (nospoonPid > 0) NativeHelper.kill(nospoonPid)
        handler.postDelayed({ cleanup() }, 2000)
    }

    private fun cleanup() {
        if (nospoonPid > 0) {
            NativeHelper.kill(nospoonPid)
            nospoonPid = -1
        }

        if (ipcSocketFd >= 0) {
            try { Os.close(FileDescriptor().also {
                val f = FileDescriptor::class.java.getDeclaredField("descriptor")
                f.isAccessible = true
                f.setInt(it, ipcSocketFd)
            }) } catch (_: ErrnoException) {}
            ipcSocketFd = -1
        }
        ipcReader = null

        if (tunFdForBinary >= 0) {
            try { Os.close(FileDescriptor().also {
                val f = FileDescriptor::class.java.getDeclaredField("descriptor")
                f.isAccessible = true
                f.setInt(it, tunFdForBinary)
            }) } catch (_: ErrnoException) {}
            tunFdForBinary = -1
        }
        vpnInterface?.close()
        vpnInterface = null
        pendingConfig = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onRevoke() {
        broadcastStatus("Disconnected", false)
        cleanup()
        super.onRevoke()
    }
}
