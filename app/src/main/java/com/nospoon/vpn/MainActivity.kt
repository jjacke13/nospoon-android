package com.nospoon.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        const val VPN_REQUEST_CODE = 1
    }

    private lateinit var serverKeyInput: EditText
    private lateinit var seedInput: EditText
    private lateinit var ipInput: EditText
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button

    private var isConnected = false
    private var pendingServerKey: String? = null
    private var pendingSeed: String? = null
    private var pendingIp: String? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(NospoonVpnService.EXTRA_STATUS_TEXT) ?: return
            val connected = intent.getBooleanExtra(NospoonVpnService.EXTRA_CONNECTED, false)

            statusText.text = text
            isConnected = connected
            connectButton.text = if (connected) "Disconnect" else "Connect"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverKeyInput = findViewById(R.id.serverKeyInput)
        seedInput = findViewById(R.id.seedInput)
        ipInput = findViewById(R.id.ipInput)
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)

        connectButton.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                val serverKey = serverKeyInput.text.toString().trim()
                if (serverKey.length != 64) {
                    statusText.text = "Server key must be 64 hex characters"
                    return@setOnClickListener
                }
                val seed = seedInput.text.toString().trim().ifEmpty { null }
                if (seed != null && seed.length != 64) {
                    statusText.text = "Seed must be 64 hex characters"
                    return@setOnClickListener
                }
                val ip = ipInput.text.toString().trim().ifEmpty { "10.0.0.2/24" }
                connect(serverKey, seed, ip)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            statusReceiver,
            IntentFilter(NospoonVpnService.ACTION_STATUS),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        unregisterReceiver(statusReceiver)
        super.onPause()
    }

    private fun connect(serverKey: String, seed: String?, ip: String) {
        pendingServerKey = serverKey
        pendingSeed = seed
        pendingIp = ip

        // Request VPN permission (shows system dialog on first use)
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            // Permission already granted
            startVpnService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpnService()
        } else {
            statusText.text = "VPN permission denied"
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, NospoonVpnService::class.java).apply {
            action = NospoonVpnService.ACTION_START
            putExtra(NospoonVpnService.EXTRA_SERVER_KEY, pendingServerKey)
            putExtra(NospoonVpnService.EXTRA_IP, pendingIp)
            putExtra(NospoonVpnService.EXTRA_MTU, 1400)
            pendingSeed?.let { putExtra(NospoonVpnService.EXTRA_SEED, it) }
        }
        startForegroundService(intent)

        statusText.text = "Connecting..."
    }

    private fun disconnect() {
        val intent = Intent(this, NospoonVpnService::class.java).apply {
            action = NospoonVpnService.ACTION_STOP
        }
        startService(intent)

        isConnected = false
        connectButton.text = "Connect"
        statusText.text = "Disconnecting..."
    }
}
