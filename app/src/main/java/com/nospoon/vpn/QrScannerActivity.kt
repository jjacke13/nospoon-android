package com.nospoon.vpn

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.journeyapps.barcodescanner.CaptureActivity

class QrScannerActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = findViewById<View>(android.R.id.content)
        val padding = Rect(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = padding.left + bars.left, top = padding.top + bars.top,
                right = padding.right + bars.right, bottom = padding.bottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }
}
