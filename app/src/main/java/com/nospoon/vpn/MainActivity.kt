package com.nospoon.vpn

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : AppCompatActivity(),
    VpnConfigAdapter.OnConfigClickListener,
    ConfigEditorBottomSheet.ConfigEditorListener,
    ConfigEditorBottomSheet.ScanRequestListener {

    companion object {
        const val VPN_REQUEST_CODE = 1
        const val NOTIFICATION_PERMISSION_CODE = 2
        const val SCAN_REQUEST_CODE = 500
    }

    private lateinit var configList: RecyclerView
    private lateinit var adapter: VpnConfigAdapter
    private lateinit var repository: VpnConfigRepository
    private lateinit var statusLabel: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusIndicator: View
    private lateinit var connectButton: TextView
    private lateinit var emptyState: View
    private lateinit var fabAdd: FloatingActionButton

    // Connection state
    private var isConnected = false
    private var activeConfigId: String? = null

    // Pending connection (waiting for VPN permission)
    private var pendingConfig: VpnConfig? = null

    // Current bottom sheet for scan result delivery
    private var currentSheet: ConfigEditorBottomSheet? = null

    // Scan state
    private var pendingScanTarget: ConfigEditorBottomSheet.ScanTarget? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(NospoonVpnService.EXTRA_STATUS_TEXT) ?: return
            val connected = intent.getBooleanExtra(NospoonVpnService.EXTRA_CONNECTED, false)

            updateConnectionUI(text, connected)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = VpnConfigRepository(this)

        // Find views
        configList = findViewById(R.id.configList)
        statusLabel = findViewById(R.id.statusLabel)
        statusDetail = findViewById(R.id.statusDetail)
        statusIndicator = findViewById(R.id.statusIndicator)
        connectButton = findViewById(R.id.connectButton)
        emptyState = findViewById(R.id.emptyState)
        fabAdd = findViewById(R.id.fabAdd)

        // Setup RecyclerView
        adapter = VpnConfigAdapter(repository.loadAll(), this)
        configList.layoutManager = LinearLayoutManager(this)
        configList.adapter = adapter

        updateEmptyState()

        // FAB - add new config
        fabAdd.setOnClickListener {
            showConfigEditor(null)
        }

        // Connect button in status bar - disconnect
        connectButton.setOnClickListener {
            if (isConnected) {
                disconnect()
            }
        }

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_CODE
            )
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            statusReceiver,
            IntentFilter(NospoonVpnService.ACTION_STATUS),
            RECEIVER_NOT_EXPORTED
        )
        // Ask running service (if any) to re-broadcast its current status
        startService(Intent(this, NospoonVpnService::class.java).apply {
            action = NospoonVpnService.ACTION_QUERY
        })
    }

    override fun onPause() {
        unregisterReceiver(statusReceiver)
        super.onPause()
    }

    // ─── Config list callbacks ───────────────────────────────────────

    override fun onConfigClick(config: VpnConfig) {
        if (isConnected && activeConfigId == config.id) {
            // Already connected with this config - disconnect
            disconnect()
        } else if (isConnected) {
            // Connected with a different config - stop first, then start new
            disconnect()
            pendingConfig = config
            // Small delay to let VPN tear down before reconnecting
            configList.postDelayed({ connectWithConfig(config) }, 500)
        } else {
            // Not connected - connect
            connectWithConfig(config)
        }
    }

    override fun onConfigLongClick(config: VpnConfig) {
        showConfigEditor(config)
    }

    override fun onConfigEdit(config: VpnConfig) {
        showConfigEditor(config)
    }

    // ─── Config editor callbacks ─────────────────────────────────────

    override fun onConfigSaved(config: VpnConfig) {
        repository.save(config)
        adapter.updateData(repository.loadAll())
        updateEmptyState()
    }

    override fun onConfigDeleted(configId: String) {
        if (activeConfigId == configId && isConnected) {
            disconnect()
        }
        repository.delete(configId)
        adapter.updateData(repository.loadAll())
        updateEmptyState()
    }

    // ─── Scan request callback (from bottom sheet) ────────────────────

    override fun onScanRequested(target: ConfigEditorBottomSheet.ScanTarget) {
        pendingScanTarget = target

        // Check camera permission
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), SCAN_REQUEST_CODE)
            return
        }
        launchScanner()
    }

    private fun launchScanner() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Scan QR code")
        integrator.setBeepEnabled(false)
        integrator.setOrientationLocked(false)
        integrator.setCameraId(0)
        integrator.initiateScan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SCAN_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchScanner()
            } else {
                Toast.makeText(this, "Camera permission required to scan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── Config editor ───────────────────────────────────────────────

    private fun showConfigEditor(config: VpnConfig?) {
        val sheet = if (config != null) {
            ConfigEditorBottomSheet.newInstance(config)
        } else {
            ConfigEditorBottomSheet()
        }
        sheet.listener = this
        sheet.setScanRequestListener(this)
        currentSheet = sheet
        sheet.show(supportFragmentManager, "config_editor")
    }

    // ─── VPN connection ──────────────────────────────────────────────

    private fun connectWithConfig(config: VpnConfig) {
        pendingConfig = config

        // Request VPN permission (shows system dialog on first use)
        val intent = VpnService.prepare(this)
        Log.d("NospoonVPN", "VpnService.prepare() returned: $intent")
        if (intent != null) {
            Log.d("NospoonVPN", "Launching VPN consent dialog...")
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            Log.d("NospoonVPN", "VPN permission already granted, starting service")
            startVpnService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d("NospoonVPN", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        // Handle scan result
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            if (scanResult.contents != null) {
                pendingScanTarget?.let { target ->
                    currentSheet?.setScannedText(target, scanResult.contents.trim())
                }
            }
            pendingScanTarget = null
            return
        }

        // Handle VPN permission result
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpnService()
        } else if (requestCode == VPN_REQUEST_CODE) {
            statusLabel.text = "VPN permission denied"
            pendingConfig = null
        }
    }

    private fun startVpnService() {
        val config = pendingConfig ?: return
        activeConfigId = config.id

        val intent = Intent(this, NospoonVpnService::class.java).apply {
            action = NospoonVpnService.ACTION_START
            putExtra(NospoonVpnService.EXTRA_SERVER_KEY, config.serverKey)
            putExtra(NospoonVpnService.EXTRA_IP, config.ip)
            putExtra(NospoonVpnService.EXTRA_MTU, 1400)
            putExtra(NospoonVpnService.EXTRA_FULL_TUNNEL, config.fullTunnel)
            config.seed?.let { putExtra(NospoonVpnService.EXTRA_SEED, it) }
        }
        startForegroundService(intent)

        updateConnectionUI("Connecting...", false)
        adapter.setConnectionState(activeConfigId, "connecting")
    }

    private fun disconnect() {
        val intent = Intent(this, NospoonVpnService::class.java).apply {
            action = NospoonVpnService.ACTION_STOP
        }
        startService(intent)

        isConnected = false
        activeConfigId = null
        pendingConfig = null
        updateConnectionUI("Disconnected", false)
        adapter.setConnectionState(null, "disconnected")
    }

    // ─── UI updates ──────────────────────────────────────────────────

    private fun updateConnectionUI(statusText: String, connected: Boolean) {
        isConnected = connected

        statusLabel.text = statusText

        when {
            connected -> {
                statusIndicator.setBackgroundResource(R.drawable.circle_status_connected)
                val configName = repository.getById(activeConfigId ?: "")?.displayName() ?: ""
                statusDetail.text = configName
                connectButton.text = "Disconnect"
                connectButton.visibility = View.VISIBLE
                connectButton.setTextColor(getColor(R.color.status_error))
                adapter.setConnectionState(activeConfigId, "connected")
            }
            activeConfigId != null -> {
                // Connecting or reconnecting
                statusIndicator.setBackgroundResource(R.drawable.circle_status_connecting)
                statusDetail.text = "Establishing tunnel..."
                connectButton.visibility = View.VISIBLE
                connectButton.text = "Cancel"
                connectButton.setTextColor(getColor(R.color.status_connecting))
                adapter.setConnectionState(activeConfigId, "connecting")
            }
            else -> {
                statusIndicator.setBackgroundResource(R.drawable.circle_status_disconnected)
                statusDetail.text = "Tap a configuration to connect"
                connectButton.visibility = View.GONE
                adapter.setConnectionState(null, "disconnected")
            }
        }
    }

    private fun updateEmptyState() {
        val hasConfigs = adapter.itemCount > 0
        emptyState.visibility = if (hasConfigs) View.GONE else View.VISIBLE
        configList.visibility = if (hasConfigs) View.VISIBLE else View.GONE
    }
}
