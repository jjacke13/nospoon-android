package com.nospoon.vpn

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

class ConfigEditorBottomSheet : BottomSheetDialogFragment() {

    interface ConfigEditorListener {
        fun onConfigSaved(config: VpnConfig)
        fun onConfigDeleted(configId: String)
        fun onScanRequested(target: ScanTarget)
    }

    enum class ScanTarget { SERVER_KEY, CLIENT_SEED }

    interface ScanRequestListener {
        fun onScanRequested(target: ScanTarget)
    }

    companion object {
        private const val ARG_ID = "config_id"
        private const val ARG_NAME = "config_name"
        private const val ARG_SERVER_KEY = "config_server_key"
        private const val ARG_SEED = "config_seed"
        private const val ARG_IP = "config_ip"

        fun newInstance(config: VpnConfig): ConfigEditorBottomSheet {
            val fragment = ConfigEditorBottomSheet()
            fragment.arguments = Bundle().apply {
                putString(ARG_ID, config.id)
                putString(ARG_NAME, config.name)
                putString(ARG_SERVER_KEY, config.serverKey)
                putString(ARG_SEED, config.seed)
                putString(ARG_IP, config.ip)
            }
            return fragment
        }
    }

    var listener: ConfigEditorListener? = null

    private lateinit var inputServerKey: EditText
    private lateinit var inputSeed: EditText
    private var scanRequestListener: ScanRequestListener? = null

    fun setScanRequestListener(listener: ScanRequestListener) {
        this.scanRequestListener = listener
    }

    fun setScannedText(target: ScanTarget, text: String) {
        when (target) {
            ScanTarget.SERVER_KEY -> inputServerKey.setText(text)
            ScanTarget.CLIENT_SEED -> inputSeed.setText(text)
        }
        Toast.makeText(context, "Scanned", Toast.LENGTH_SHORT).show()
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
        val btnPasteKey = view.findViewById<ImageButton>(R.id.btnPasteKey)
        val btnPasteSeed = view.findViewById<ImageButton>(R.id.btnPasteSeed)
        val btnScanKey = view.findViewById<ImageButton>(R.id.btnScanKey)
        val btnScanSeed = view.findViewById<ImageButton>(R.id.btnScanSeed)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)
        val btnDelete = view.findViewById<MaterialButton>(R.id.btnDelete)

        // Check if editing existing config
        val configId = arguments?.getString(ARG_ID)
        val isEditing = configId != null

        if (isEditing) {
            title.text = "Edit Configuration"
            inputName.setText(arguments?.getString(ARG_NAME, "") ?: "")
            inputServerKey.setText(arguments?.getString(ARG_SERVER_KEY, "") ?: "")
            inputSeed.setText(arguments?.getString(ARG_SEED, "") ?: "")
            inputIp.setText(arguments?.getString(ARG_IP, "10.0.0.2/24") ?: "10.0.0.2/24")
            btnDelete.visibility = View.VISIBLE
        }

        // Paste buttons
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

        // Scan buttons - delegate to activity via listener
        btnScanKey.setOnClickListener {
            listener?.onScanRequested(ScanTarget.SERVER_KEY)
        }

        btnScanSeed.setOnClickListener {
            listener?.onScanRequested(ScanTarget.CLIENT_SEED)
        }

        // Cancel
        btnCancel.setOnClickListener { dismiss() }

        // Delete
        btnDelete.setOnClickListener {
            configId?.let { listener?.onConfigDeleted(it) }
            dismiss()
        }

        // Save
        btnSave.setOnClickListener {
            val name = inputName.text.toString().trim()
            val serverKey = inputServerKey.text.toString().trim()
            val seed = inputSeed.text.toString().trim().ifEmpty { null }
            val ip = inputIp.text.toString().trim().ifEmpty { "10.0.0.2/24" }

            // Validate
            if (serverKey.length != 64) {
                inputServerKey.error = "Must be 64 hex characters"
                return@setOnClickListener
            }
            if (!serverKey.matches(Regex("^[0-9a-fA-F]+$"))) {
                inputServerKey.error = "Must be hexadecimal"
                return@setOnClickListener
            }
            if (seed != null && seed.length != 64) {
                inputSeed.error = "Must be 64 hex characters"
                return@setOnClickListener
            }
            if (seed != null && !seed.matches(Regex("^[0-9a-fA-F]+$"))) {
                inputSeed.error = "Must be hexadecimal"
                return@setOnClickListener
            }

            val config = VpnConfig(
                id = configId ?: java.util.UUID.randomUUID().toString(),
                name = name,
                serverKey = serverKey,
                seed = seed,
                ip = ip
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
