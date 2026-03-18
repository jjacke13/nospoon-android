package com.nospoon.vpn

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
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var worklet: Worklet? = null
    private var ipc: IPC? = null
    private var ipcBuffer = StringBuilder()

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

    private fun startVpn(serverKey: String, seed: String?, ip: String, mtu: Int) {
        // Parse IP address and prefix
        val parts = ip.split("/")
        val ipAddr = parts[0]
        val prefix = if (parts.size > 1) parts[1].toInt() else 24

        // Create TUN interface via VpnService.Builder
        val builder = Builder()
            .setSession("nospoon")
            .setMtu(mtu)
            .addAddress(ipAddr, prefix)
            .addRoute("0.0.0.0", 1)     // split route: 0.0.0.0 - 127.255.255.255
            .addRoute("128.0.0.0", 1)   // split route: 128.0.0.0 - 255.255.255.255
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }

        val tunFd = vpnInterface!!.fd
        Log.i(TAG, "VPN interface established, TUN fd: $tunFd")

        // Start Bare worklet
        worklet = Worklet(null)
        ipc = IPC(worklet)

        // Listen for messages from the worklet
        setupIpcListener()

        // Start the JS worklet
        val bundle = assets.open("client.bundle")
        worklet!!.start("/client.bundle", bundle, null)

        // Send start command with TUN fd and config
        val startMsg = JSONObject().apply {
            put("type", "start")
            put("tunFd", tunFd)
            put("serverKey", serverKey)
            put("ip", "$ipAddr/$prefix")
            if (seed != null) put("seed", seed)
        }
        sendToWorklet(startMsg)
    }

    private fun setupIpcListener() {
        ipc?.read { data ->
            if (data == null) return@read

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
    }

    private fun handleWorkletMessage(msg: JSONObject) {
        when (msg.getString("type")) {
            "status" -> {
                val connected = msg.getBoolean("connected")
                Log.i(TAG, if (connected) "Connected to server" else "Disconnected from server")
                // TODO: broadcast status to UI
            }
            "protect" -> {
                // Exempt DHT socket from VPN tunnel
                val fd = msg.getInt("fd")
                val ok = protect(fd)
                Log.i(TAG, "protect(fd=$fd): $ok")
            }
            "identity" -> {
                val publicKey = msg.getString("publicKey")
                Log.i(TAG, "Client public key: $publicKey")
            }
            "error" -> {
                val message = msg.getString("message")
                Log.e(TAG, "Worklet error: $message")
            }
            "stopped" -> {
                Log.i(TAG, "Worklet stopped")
                cleanup()
            }
        }
    }

    private fun sendToWorklet(msg: JSONObject) {
        val bytes = (msg.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        ipc?.write(ByteBuffer.wrap(bytes)) { _, _ -> }
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
