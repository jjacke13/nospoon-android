package com.nospoon.vpn

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONObject

class ConfigEditorBottomSheet : BottomSheetDialogFragment() {

    interface ConfigEditorListener {
        fun onConfigSaved(config: VpnConfig)
        fun onConfigDeleted(configId: String)
        fun onScanRequested(target: ScanTarget)
    }

    enum class ScanTarget { SERVER_KEY, CLIENT_SEED, FULL_CONFIG }

    companion object {
        private const val ARG_ID = "config_id"
        private const val ARG_NAME = "config_name"
        private const val ARG_SERVER = "config_server"
        private const val ARG_SEED = "config_seed"
        private const val ARG_IP = "config_ip"
        private const val ARG_MTU = "config_mtu"
        private const val ARG_FULL_TUNNEL = "config_full_tunnel"

        private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")

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
                Toast.makeText(context, "Scanned", Toast.LENGTH_SHORT).show()
            }
            ScanTarget.CLIENT_SEED -> {
                inputSeed.setText(text)
                Toast.makeText(context, "Scanned", Toast.LENGTH_SHORT).show()
            }
            ScanTarget.FULL_CONFIG -> applyScannedConfig(text)
        }
    }

    private fun applyScannedConfig(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Toast.makeText(context, "Invalid JSON in QR code", Toast.LENGTH_LONG).show()
            return
        }

        if (!json.has("server")) {
            Toast.makeText(context, "QR config missing \"server\" field", Toast.LENGTH_LONG).show()
            return
        }

        val config = VpnConfig.fromJson(json)
        inputServerKey.setText(config.server)
        config.seed?.let { inputSeed.setText(it) }
        inputIpField?.setText(config.ip)
        fullTunnelSwitch?.isChecked = config.fullTunnel

        Toast.makeText(context, "Config imported", Toast.LENGTH_SHORT).show()
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
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)
        val btnDelete = view.findViewById<MaterialButton>(R.id.btnDelete)

        val configId = arguments?.getString(ARG_ID)
        val isEditing = configId != null

        if (isEditing) {
            title.text = "Edit Configuration"
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
                Toast.makeText(context, "Pasted", Toast.LENGTH_SHORT).show()
            }
        }

        btnPasteSeed.setOnClickListener {
            getClipboardText()?.let {
                inputSeed.setText(it)
                Toast.makeText(context, "Pasted", Toast.LENGTH_SHORT).show()
            }
        }

        btnScanKey.setOnClickListener { listener?.onScanRequested(ScanTarget.SERVER_KEY) }
        btnScanSeed.setOnClickListener { listener?.onScanRequested(ScanTarget.CLIENT_SEED) }
        btnScanConfig.setOnClickListener { listener?.onScanRequested(ScanTarget.FULL_CONFIG) }

        btnCancel.setOnClickListener { dismiss() }

        btnDelete.setOnClickListener {
            configId?.let { listener?.onConfigDeleted(it) }
            dismiss()
        }

        btnSave.setOnClickListener {
            val name = inputName.text.toString().trim()
            val server = inputServerKey.text.toString().trim()
            val seed = inputSeed.text.toString().trim().ifEmpty { null }
            val ip = inputIp.text.toString().trim().ifEmpty { "10.0.0.2/24" }

            if (!server.matches(HEX_64)) {
                inputServerKey.error = "Must be 64 hex characters"
                return@setOnClickListener
            }
            if (seed != null && !seed.matches(HEX_64)) {
                inputSeed.error = "Must be 64 hex characters"
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

    private fun getClipboardText(): String? {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager ?: return null
        if (!clipboard.hasPrimaryClip()) return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()?.trim()
    }
}
