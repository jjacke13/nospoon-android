package com.nospoon.vpn

import android.app.Application
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27, 35], application = Application::class)
class ThemeCompatibilityTest {
    @Test
    fun versionedThemesKeepBothPalettesAndNavigationIconContrast() {
        val app = RuntimeEnvironment.getApplication()
        for (night in listOf(false, true)) {
            val config = Configuration(app.resources.configuration)
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_Nospoon)
            val attrs = context.obtainStyledAttributes(intArrayOf(
                com.google.android.material.R.attr.colorPrimary,
                android.R.attr.windowLightNavigationBar
            ))
            try {
                val expected = if (night) R.color.md_theme_dark_primary else R.color.md_theme_light_primary
                assertEquals(context.getColor(expected), attrs.getColor(0, 0))
                if (android.os.Build.VERSION.SDK_INT >= 27) {
                    assertTrue(attrs.hasValue(1))
                    assertEquals(!night, attrs.getBoolean(1, false))
                }
            } finally {
                attrs.recycle()
            }
        }
    }
}
