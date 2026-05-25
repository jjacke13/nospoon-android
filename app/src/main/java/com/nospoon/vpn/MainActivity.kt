package com.nospoon.vpn

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(),
    VpnConfigAdapter.OnConfigClickListener,
    ConfigEditorBottomSheet.ConfigEditorListener {

    companion object {
        const val VPN_REQUEST_CODE = 1
        const val NOTIFICATION_PERMISSION_CODE = 2
    }

    private lateinit var configList: RecyclerView
    private lateinit var adapter: VpnConfigAdapter
    private lateinit var repository: VpnConfigRepository
    private lateinit var statusLabel: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusIndicator: View
    private lateinit var disconnectButton: MaterialButton
    private lateinit var emptyState: View
    private lateinit var fabAdd: FloatingActionButton

    // Connection state
    private var isConnected = false
    private var activeConfigId: String? = null

    // Pending connection (waiting for VPN permission)
    private var pendingConfig: VpnConfig? = null

    // Once true, skip the rationale dialog before VpnService.prepare. Reset
    // on process death — first user gesture per launch re-explains.
    private var vpnConsentExplained = false

    // Current bottom sheet for scan result delivery
    private var currentSheet: ConfigEditorBottomSheet? = null

    // Scan state
    private var pendingScanTarget: ConfigEditorBottomSheet.ScanTarget? = null

    // Picker for SAF-based config import. Registered on the Activity so it
    // survives BottomSheet lifecycle transitions.
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.d("ConfigImport", "filePicker returned uri=$uri")
        if (uri == null) return@registerForActivityResult
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Log.w("ConfigImport", "read failed: ${e.message}", e)
            null
        }
        if (text.isNullOrBlank()) {
            Snackbar.make(
                findViewById(R.id.rootContainer),
                R.string.file_read_failed,
                Snackbar.LENGTH_LONG
            ).show()
            return@registerForActivityResult
        }
        Log.d("ConfigImport", "delivered ${text.length} chars to BottomSheet")
        currentSheet?.setScannedText(ConfigEditorBottomSheet.ScanTarget.FULL_CONFIG, text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() MUST run before super.onCreate so the
        // SplashScreen API can swap the splash theme to the post-splash
        // theme atomically with the first frame.
        installSplashScreen()
        // SDK 35 enforces edge-to-edge; opt in explicitly so behavior is
        // consistent across older versions too.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = VpnConfigRepository(this)

        configList = findViewById(R.id.configList)
        statusLabel = findViewById(R.id.statusLabel)
        statusDetail = findViewById(R.id.statusDetail)
        statusIndicator = findViewById(R.id.statusIndicator)
        disconnectButton = findViewById(R.id.disconnectButton)
        emptyState = findViewById(R.id.emptyState)
        fabAdd = findViewById(R.id.fabAdd)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_about -> { showAboutDialog(); true }
                else -> false
            }
        }

        // Apply system-bar insets as padding on the root so the status bar,
        // gesture pill, and 3-button nav don't overlap content.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        adapter = VpnConfigAdapter(repository.loadAll(), this)
        configList.layoutManager = LinearLayoutManager(this)
        configList.adapter = adapter

        updateEmptyState()

        fabAdd.setOnClickListener { showConfigEditor(null) }

        disconnectButton.setOnClickListener {
            disconnect()
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

        // Collect connection state from the in-process StateFlow. The
        // collector is auto-paused when the Activity stops and re-resumed
        // when STARTED; on (re-)attach it immediately receives the latest
        // value, so no manual "query the service" round-trip is needed.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionStateRepository.state.collect { state ->
                    updateConnectionUI(state)
                }
            }
        }
    }

    // --- Config list callbacks ---

    override fun onConfigClick(config: VpnConfig) {
        if (isConnected && activeConfigId == config.id) {
            disconnect()
        } else if (isConnected) {
            disconnect()
            pendingConfig = config
            configList.postDelayed({ connectWithConfig(config) }, 500)
        } else {
            connectWithConfig(config)
        }
    }

    override fun onConfigEdit(config: VpnConfig) {
        showConfigEditor(config)
    }

    // --- Config editor callbacks ---

    override fun onConfigSaved(config: VpnConfig) {
        repository.save(config)
        adapter.updateData(repository.loadAll())
        updateEmptyState()
        Snackbar.make(
            findViewById(R.id.rootContainer),
            R.string.config_saved,
            Snackbar.LENGTH_SHORT
        ).show()
    }

    override fun onConfigDeleted(configId: String) {
        val deleted = repository.getById(configId) ?: return
        if (activeConfigId == configId && isConnected) disconnect()
        repository.delete(configId)
        adapter.updateData(repository.loadAll())
        updateEmptyState()
        Snackbar.make(
            findViewById(R.id.rootContainer),
            R.string.config_deleted,
            Snackbar.LENGTH_LONG
        ).setAction(R.string.undo) {
            repository.save(deleted)
            adapter.updateData(repository.loadAll())
            updateEmptyState()
        }.show()
    }

    override fun onScanRequested(target: ConfigEditorBottomSheet.ScanTarget) {
        pendingScanTarget = target
        launchScanner()
    }

    override fun onImportFileRequested() {
        Log.d("ConfigImport", "onImportFileRequested received, launching picker")
        try {
            filePickerLauncher.launch(arrayOf("*/*"))
            Log.d("ConfigImport", "launcher.launch returned (picker should be opening)")
        } catch (e: Exception) {
            Log.e("ConfigImport", "launch failed: ${e.message}", e)
            Snackbar.make(
                findViewById(R.id.rootContainer),
                R.string.file_read_failed,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun launchScanner() {
        // ML Kit's standalone scanner ships its own camera UI and handles
        // CAMERA permission internally via Play Services — no runtime
        // request needed from us. The first scan after install may pause
        // briefly while Play Services downloads the barcode-ui module.
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue ?: return@addOnSuccessListener
                val target = pendingScanTarget ?: return@addOnSuccessListener
                currentSheet?.setScannedText(target, raw.trim())
                pendingScanTarget = null
            }
            .addOnCanceledListener {
                Log.d("Scan", "scan cancelled")
                pendingScanTarget = null
            }
            .addOnFailureListener { e ->
                Log.w("Scan", "scan failed: ${e.message}", e)
                pendingScanTarget = null
            }
    }

    // --- Config editor ---

    private fun showConfigEditor(config: VpnConfig?) {
        val sheet = if (config != null) {
            ConfigEditorBottomSheet.newInstance(config)
        } else {
            ConfigEditorBottomSheet()
        }
        sheet.listener = this
        currentSheet = sheet
        sheet.show(supportFragmentManager, "config_editor")
    }

    // --- VPN connection ---

    private fun connectWithConfig(config: VpnConfig) {
        pendingConfig = config
        val intent = VpnService.prepare(this)
        if (intent == null) {
            startVpnService()
            return
        }
        // First-time consent: explain why we need the OS dialog before
        // showing the scary "monitor all network traffic" system prompt.
        if (vpnConsentExplained) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vpn_permission_title)
            .setMessage(R.string.vpn_permission_message)
            .setNegativeButton(R.string.cancel) { _, _ -> pendingConfig = null }
            .setPositiveButton(R.string.allow) { _, _ ->
                vpnConsentExplained = true
                startActivityForResult(intent, VPN_REQUEST_CODE)
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // MUST call super so AndroidX ActivityResultRegistry dispatches
        // results to registerForActivityResult() callbacks (file picker etc.)
        super.onActivityResult(requestCode, resultCode, data)
        // VPN permission result (ML Kit scan results come through its own
        // Task callback, not via onActivityResult)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpnService()
        } else if (requestCode == VPN_REQUEST_CODE) {
            ConnectionStateRepository.update(
                ConnectionState.Error(getString(R.string.vpn_permission_denied))
            )
            pendingConfig = null
        }
    }

    private fun startVpnService() {
        val config = pendingConfig ?: return
        activeConfigId = config.id

        val intent = Intent(this, NospoonVpnService::class.java).apply {
            action = NospoonVpnService.ACTION_START
            putExtra(NospoonVpnService.EXTRA_CONFIG_JSON, config.toJson().toString())
        }
        startForegroundService(intent)

        // Optimistic UI hint — the service will publish Connecting via the
        // repository moments later, but pushing it here means the user sees
        // "Connecting…" before the service starts emitting.
        ConnectionStateRepository.update(ConnectionState.Connecting)
        adapter.setConnectionState(activeConfigId, "connecting")
    }

    private fun disconnect() {
        startService(Intent(this, NospoonVpnService::class.java).apply {
            action = NospoonVpnService.ACTION_STOP
        })
        isConnected = false
        activeConfigId = null
        pendingConfig = null
        ConnectionStateRepository.update(ConnectionState.Disconnected)
        adapter.setConnectionState(null, "disconnected")
    }

    // --- UI updates ---

    private fun updateConnectionUI(state: ConnectionState) {
        isConnected = state is ConnectionState.Connected

        // Reset active config on a hard disconnect so the row indicators
        // clear and the next tap starts a fresh flow.
        if (state is ConnectionState.Disconnected) {
            activeConfigId = null
        }

        // Top status label — localized per state.
        statusLabel.text = when (state) {
            is ConnectionState.Error -> state.message
            ConnectionState.Connected -> getString(R.string.status_connected)
            ConnectionState.Connecting -> getString(R.string.status_connecting)
            ConnectionState.Reconnecting -> getString(R.string.status_reconnecting)
            ConnectionState.Disconnected -> getString(R.string.status_disconnected)
        }

        // Indicator dot + detail line + disconnect button visibility.
        when (state) {
            is ConnectionState.Connected -> {
                statusIndicator.setBackgroundResource(R.drawable.circle_status_connected)
                ViewCompat.setStateDescription(statusIndicator, getString(R.string.state_connected))
                statusDetail.text = repository.getById(activeConfigId ?: "")?.displayName() ?: ""
                disconnectButton.text = getString(R.string.disconnect)
                disconnectButton.visibility = View.VISIBLE
                disconnectButton.setTextColor(getColor(R.color.status_error))
                adapter.setConnectionState(activeConfigId, "connected")
            }
            is ConnectionState.Connecting, is ConnectionState.Reconnecting -> {
                statusIndicator.setBackgroundResource(R.drawable.circle_status_connecting)
                ViewCompat.setStateDescription(statusIndicator, getString(R.string.state_connecting))
                statusDetail.text = getString(R.string.status_establishing_tunnel)
                disconnectButton.visibility = View.VISIBLE
                disconnectButton.text = getString(R.string.cancel)
                disconnectButton.setTextColor(getColor(R.color.status_connecting))
                adapter.setConnectionState(activeConfigId, "connecting")
            }
            is ConnectionState.Error -> {
                statusIndicator.setBackgroundResource(R.drawable.circle_status_disconnected)
                ViewCompat.setStateDescription(statusIndicator, getString(R.string.state_disconnected))
                statusDetail.text = getString(R.string.status_tap_to_connect)
                disconnectButton.visibility = View.GONE
                adapter.setConnectionState(null, "disconnected")
            }
            ConnectionState.Disconnected -> {
                statusIndicator.setBackgroundResource(R.drawable.circle_status_disconnected)
                ViewCompat.setStateDescription(statusIndicator, getString(R.string.state_disconnected))
                statusDetail.text = getString(R.string.status_tap_to_connect)
                disconnectButton.visibility = View.GONE
                adapter.setConnectionState(null, "disconnected")
            }
        }
    }

    private fun updateEmptyState() {
        val hasConfigs = adapter.itemCount > 0
        emptyState.visibility = if (hasConfigs) View.GONE else View.VISIBLE
        configList.visibility = if (hasConfigs) View.VISIBLE else View.GONE
    }

    private fun showAboutDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_message, version))
            .setNeutralButton(R.string.about_github) { _, _ ->
                val url = getString(R.string.github_url)
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Log.w("About", "no browser to open $url: ${e.message}")
                }
            }
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
