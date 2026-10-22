package com.nospoon.vpn

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class InfoBottomSheet : BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.dialog_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityHeading(view.findViewById(R.id.infoTitle), true)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.info_title))
        val padding = view.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = padding + bars.left, top = padding + bars.top,
                right = padding + bars.right, bottom = padding + bars.bottom)
            insets
        }
        view.findViewById<View>(R.id.infoDocumentationButton).setOnClickListener {
            (requireActivity() as MainActivity).openDocumentation(view)
        }
        view.findViewById<View>(R.id.closeInfoButton).setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        val dialog = requireDialog() as BottomSheetDialog
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.updateLayoutParams { height = ViewGroup.LayoutParams.MATCH_PARENT }
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
