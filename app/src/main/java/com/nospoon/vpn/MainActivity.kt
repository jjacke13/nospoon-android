package com.nospoon.vpn

import android.app.Activity
import android.content.Intent
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
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button

    private var isConnected = false
    private var pendingServerKey: String? = null
    private var pendingSeed: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverKeyInput = findViewById(R.id.serverKeyInput)
        seedInput = findViewById(R.id.seedInput)
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
                connect(serverKey, seed)
            }
        }
    }

    private fun connect(serverKey: String, seed: String?) {
        pendingServerKey = serverKey
        pendingSeed = seed

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
            putExtra(NospoonVpnService.EXTRA_IP, "10.0.0.2/24")
            putExtra(NospoonVpnService.EXTRA_MTU, 1400)
            pendingSeed?.let { putExtra(NospoonVpnService.EXTRA_SEED, it) }
        }
        startService(intent)

        isConnected = true
        connectButton.text = "Disconnect"
        statusText.text = "Connecting..."
    }

    private fun disconnect() {
        val intent = Intent(this, NospoonVpnService::class.java).apply {
            action = NospoonVpnService.ACTION_STOP
        }
        startService(intent)

        isConnected = false
        connectButton.text = "Connect"
        statusText.text = "Disconnected"
    }
}
