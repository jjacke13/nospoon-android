package com.nospoon.vpn

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class LicensesDialogFragment : DialogFragment() {
    private var reader: WebView? = null
    private val assetRoot = "file:///android_asset/licenses/"

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val files = context.assets.list("licenses")!!.toSet()
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.textZoom = (resources.configuration.fontScale * 100).toInt()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val file = uri.lastPathSegment
                    if (isBundledAsset(uri, files)) {
                        if (file!!.endsWith(".txt")) {
                            val text = context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
                            view.loadDataWithBaseURL(assetRoot, wrapText(text), "text/html", "UTF-8", null)
                            return true
                        }
                        return false
                    }
                    if (request.isForMainFrame && request.hasGesture() && uri.scheme == "https") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, R.string.licenses_no_browser, Toast.LENGTH_SHORT).show()
                        }
                    }
                    return true
                }
            }
            loadUrl(assetRoot + "index.html")
        }
        reader = webView
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.licenses_title)
            .setView(webView)
            .setNeutralButton(R.string.licenses_list, null)
            .setPositiveButton(R.string.licenses_close, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        // AlertDialog replaces the supplied layout params while inserting its custom view.
        val height = requireActivity().window.decorView.height
            .takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        reader?.updateLayoutParams { this.height = (height * 0.55).toInt() }
        (requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            reader?.loadUrl(assetRoot + "index.html")
        }
    }

    override fun onDestroyView() {
        (reader?.parent as? ViewGroup)?.removeView(reader)
        reader?.apply { stopLoading(); destroy() }
        reader = null
        super.onDestroyView()
    }

    companion object {
        internal fun isBundledAsset(uri: Uri, files: Set<String>): Boolean =
            uri.scheme == "file" && uri.authority.isNullOrEmpty() &&
                uri.lastPathSegment in files && uri.path == "/android_asset/licenses/${uri.lastPathSegment}"

        internal fun wrapText(text: String): String = """<!doctype html><html lang="en"><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>:root{color-scheme:light dark}body{padding:1rem;font:1rem sans-serif}
            pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}</style>
            <pre>${TextUtils.htmlEncode(text)}</pre></html>""".trimIndent()
    }
}
