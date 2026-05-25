package com.nospoon.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hyperdht.KeyPair
import java.security.SecureRandom
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONObject

class ConfigEditorBottomSheet : BottomSheetDialogFragment() {

    interface ConfigEditorListener {
        fun onConfigSaved(config: VpnConfig)
        fun onConfigDeleted(configId: String)
        fun onScanRequested(target: ScanTarget)
        fun onImportFileRequested()
    }

    enum class ScanTarget { SERVER_KEY, CLIENT_SEED, FULL_CONFIG }

    companion object {
        private const val TAG = "ConfigEditor"
        private const val ARG_ID = "config_id"
        private const val ARG_NAME = "config_name"
        private const val ARG_SERVER = "config_server"
        private const val ARG_SEED = "config_seed"
        private const val ARG_IP = "config_ip"
        private const val ARG_MTU = "config_mtu"
        private const val ARG_FULL_TUNNEL = "config_full_tunnel"

        private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
        private val CIDR_V4 = Regex(
            """^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)/(3[0-2]|[12]?\d)$"""
        )

        fun newInstance(config: VpnConfig): ConfigEditorBottomSheet {
            val fragment = ConfigEditorBottomSheet()
            fragment.arguments = Bundle().apply {
                putString(ARG_ID, config.id)
                putString(ARG_NAME, config.name)
                putString(ARG_SERVER, config.server)
                putString(ARG_SEED, config.seed)
                putString(ARG_IP, config.ip)
                putInt(ARG_MTU, config.mtu)
                putBoolean(ARG_FULL_TUNNEL, config.fullTunnel)
            }
            return fragment
        }
    }

    var listener: ConfigEditorListener? = null

    private lateinit var inputServerKey: EditText
    private lateinit var inputSeed: EditText
    private var inputIpField: EditText? = null
    private var fullTunnelSwitch: MaterialSwitch? = null

    fun setScannedText(target: ScanTarget, text: String) {
        when (target) {
            ScanTarget.SERVER_KEY -> {
                inputServerKey.setText(text)
                Toast.makeText(context, R.string.scanned, Toast.LENGTH_SHORT).show()
            }
            ScanTarget.CLIENT_SEED -> {
                inputSeed.setText(text)
                Toast.makeText(context, R.string.scanned, Toast.LENGTH_SHORT).show()
            }
            ScanTarget.FULL_CONFIG -> applyScannedConfig(text)
        }
    }

    private fun applyScannedConfig(text: String) {
        // nospoon configs are JSONC: strip `//` line comments and `/* */`
        // block comments before parsing. Harmless for plain JSON input.
        val stripped = stripJsonComments(text)
        Log.d(TAG, "applyScannedConfig: ${text.length} chars in, ${stripped.length} after strip")
        val json = try {
            JSONObject(stripped)
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed: ${e.message}; first 200 chars: ${stripped.take(200)}")
            Toast.makeText(context, R.string.invalid_json_qr, Toast.LENGTH_LONG).show()
            return
        }

        if (!json.has("server")) {
            Log.w(TAG, "JSON keys: ${json.keys().asSequence().toList()} — no 'server'")
            Toast.makeText(context, R.string.qr_missing_server, Toast.LENGTH_LONG).show()
            return
        }

        val config = VpnConfig.fromJson(json)
        inputServerKey.setText(config.server)
        config.seed?.let { inputSeed.setText(it) }
        inputIpField?.setText(config.ip)
        fullTunnelSwitch?.isChecked = config.fullTunnel

        Log.d(TAG, "config applied: server=${config.server.take(8)}…, ip=${config.ip}")
        Toast.makeText(context, R.string.config_imported, Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_config_editor, container, false)

        val title = view.findViewById<TextView>(R.id.editorTitle)
        inputServerKey = view.findViewById(R.id.inputServerKey)
        inputSeed = view.findViewById(R.id.inputSeed)
        val inputName = view.findViewById<EditText>(R.id.inputName)
        val inputIp = view.findViewById<EditText>(R.id.inputIp)
        inputIpField = inputIp
        val btnPasteKey = view.findViewById<ImageButton>(R.id.btnPasteKey)
        val btnPasteSeed = view.findViewById<ImageButton>(R.id.btnPasteSeed)
        val btnScanKey = view.findViewById<ImageButton>(R.id.btnScanKey)
        val btnScanSeed = view.findViewById<ImageButton>(R.id.btnScanSeed)
        val switchFullTunnel = view.findViewById<MaterialSwitch>(R.id.switchFullTunnel)
        fullTunnelSwitch = switchFullTunnel
        val btnScanConfig = view.findViewById<MaterialButton>(R.id.btnScanConfig)
        val btnImportFile = view.findViewById<MaterialButton>(R.id.btnImportFile)
        val btnGenerateSeed = view.findViewById<MaterialButton>(R.id.btnGenerateSeed)
        val publicKeyDisplay = view.findViewById<TextView>(R.id.publicKeyDisplay)
        val btnCopyPublicKey = view.findViewById<ImageButton>(R.id.btnCopyPublicKey)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)
        val btnDelete = view.findViewById<MaterialButton>(R.id.btnDelete)

        val configId = arguments?.getString(ARG_ID)
        val isEditing = configId != null

        if (isEditing) {
            title.setText(R.string.edit_configuration)
            inputName.setText(arguments?.getString(ARG_NAME, "") ?: "")
            inputServerKey.setText(arguments?.getString(ARG_SERVER, "") ?: "")
            inputSeed.setText(arguments?.getString(ARG_SEED, "") ?: "")
            inputIp.setText(arguments?.getString(ARG_IP, "10.0.0.2/24") ?: "10.0.0.2/24")
            switchFullTunnel.isChecked = arguments?.getBoolean(ARG_FULL_TUNNEL, false) ?: false
            btnDelete.visibility = View.VISIBLE
        }

        btnPasteKey.setOnClickListener {
            getClipboardText()?.let {
                inputServerKey.setText(it)
                Toast.makeText(context, R.string.pasted, Toast.LENGTH_SHORT).show()
            }
        }

        btnPasteSeed.setOnClickListener {
            getClipboardText()?.let {
                inputSeed.setText(it)
                Toast.makeText(context, R.string.pasted, Toast.LENGTH_SHORT).show()
            }
        }

        btnScanKey.setOnClickListener { listener?.onScanRequested(ScanTarget.SERVER_KEY) }
        btnScanSeed.setOnClickListener { listener?.onScanRequested(ScanTarget.CLIENT_SEED) }
        btnScanConfig.setOnClickListener { listener?.onScanRequested(ScanTarget.FULL_CONFIG) }
        btnImportFile.setOnClickListener {
            Log.d(TAG, "btnImportFile tapped — listener=${listener?.javaClass?.simpleName ?: "NULL"}")
            listener?.onImportFileRequested()
        }

        // Generate a fresh 32-byte client seed using SecureRandom. Drops
        // straight into the seed field; the TextWatcher below will derive
        // and display the matching public key.
        btnGenerateSeed.setOnClickListener {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            inputSeed.setText(bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        }

        // Live-update the public-key readout as the user types/pastes a seed.
        fun refreshPublicKey() {
            val seed = inputSeed.text.toString().trim()
            if (!seed.matches(HEX_64)) {
                publicKeyDisplay.setText(R.string.public_key_placeholder)
                btnCopyPublicKey.visibility = View.GONE
                return
            }
            try {
                val bytes = ByteArray(32)
                for (i in bytes.indices) {
                    bytes[i] = seed.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                val pk = KeyPair.fromSeed(bytes).publicKey
                val hex = pk.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                publicKeyDisplay.text = hex
                btnCopyPublicKey.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.w(TAG, "pubkey derive failed: ${e.message}")
                publicKeyDisplay.setText(R.string.public_key_placeholder)
                btnCopyPublicKey.visibility = View.GONE
            }
        }
        inputSeed.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshPublicKey() }
        })

        btnCopyPublicKey.setOnClickListener {
            val hex = publicKeyDisplay.text?.toString() ?: return@setOnClickListener
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
                as? ClipboardManager ?: return@setOnClickListener
            cm.setPrimaryClip(ClipData.newPlainText("nospoon public key", hex))
            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        // Render initial state (covers editing-existing-config case where
        // inputSeed was pre-populated from arguments before the listener
        // was attached).
        refreshPublicKey()

        btnCancel.setOnClickListener { dismiss() }

        // Delete immediately + dismiss; MainActivity surfaces a Snackbar
        // with Undo so the user can recover from a misclick.
        btnDelete.setOnClickListener {
            val id = configId ?: return@setOnClickListener
            listener?.onConfigDeleted(id)
            dismiss()
        }

        btnSave.setOnClickListener {
            val name = inputName.text.toString().trim()
            val server = inputServerKey.text.toString().trim()
            val seed = inputSeed.text.toString().trim().ifEmpty { null }
            val ip = inputIp.text.toString().trim().ifEmpty { "10.0.0.2/24" }

            if (!server.matches(HEX_64)) {
                inputServerKey.error = getString(R.string.must_be_64_hex)
                return@setOnClickListener
            }
            if (seed != null && !seed.matches(HEX_64)) {
                inputSeed.error = getString(R.string.must_be_64_hex)
                return@setOnClickListener
            }
            if (!ip.matches(CIDR_V4)) {
                inputIp.error = getString(R.string.err_invalid_cidr)
                return@setOnClickListener
            }

            val config = VpnConfig(
                id = configId ?: java.util.UUID.randomUUID().toString(),
                name = name,
                server = server,
                seed = seed,
                ip = ip,
                mtu = arguments?.getInt(ARG_MTU, 1400) ?: 1400,
                fullTunnel = switchFullTunnel.isChecked
            )
            listener?.onConfigSaved(config)
            dismiss()
        }

        return view
    }

    /**
     * Strip JSONC comments while preserving comment-like substrings that
     * appear inside string literals. Single state machine over the input.
     */
    private fun stripJsonComments(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        var inString = false
        var stringQuote = ' '
        while (i < input.length) {
            val c = input[i]
            if (inString) {
                out.append(c)
                if (c == '\\' && i + 1 < input.length) {
                    out.append(input[i + 1])
                    i += 2
                    continue
                }
                if (c == stringQuote) inString = false
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                inString = true
                stringQuote = c
                out.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < input.length) {
                val next = input[i + 1]
                if (next == '/') {
                    i += 2
                    while (i < input.length && input[i] != '\n') i++
                    continue
                }
                if (next == '*') {
                    i += 2
                    while (i + 1 < input.length && !(input[i] == '*' && input[i + 1] == '/')) i++
                    i += 2  // skip the closing */
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun getClipboardText(): String? {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager ?: return null
        if (!clipboard.hasPrimaryClip()) return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()?.trim()
    }
}
