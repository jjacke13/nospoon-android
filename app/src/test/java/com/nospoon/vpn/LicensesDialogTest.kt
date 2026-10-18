package com.nospoon.vpn

import android.app.Application
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.descendants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class LicensesDialogTest {
    @Test
    fun navigationOnlyAcceptsActualBundledAssets() {
        val files = setOf("index.html", "GPL-3.0.txt")
        assertTrue(LicensesDialogFragment.isBundledAsset(Uri.parse("file:///android_asset/licenses/GPL-3.0.txt"), files))
        for (url in listOf("file:///data/data/com.nospoon.vpn/shared_prefs/private.xml",
                "file:///android_asset/licenses/../GPL-3.0.txt",
                "file:///android_asset/licenses/%2e%2e/GPL-3.0.txt",
                "file://host/android_asset/licenses/GPL-3.0.txt",
                "file:///android_asset/licenses/missing.txt", "https://example.org/GPL-3.0.txt",
                "content://example/GPL-3.0.txt")) {
            assertFalse(url, LicensesDialogFragment.isBundledAsset(Uri.parse(url), files))
        }
        val wrapped = LicensesDialogFragment.wrapText("<script>example</script> & \"quoted\"\nlicense")
        assertFalse(wrapped.contains("<script>"))
        assertTrue(wrapped.contains("&lt;script&gt;"))
        assertTrue(wrapped.contains("white-space:pre-wrap"))
    }

    @Test
    fun readerIsOfflineAndIndexActionKeepsDialogOpen() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val fragment = LicensesDialogFragment()
            fragment.showNow(controller.get().supportFragmentManager, "licenses-test")
            val dialog = fragment.requireDialog() as AlertDialog
            val webView = (dialog.window!!.decorView as ViewGroup).descendants.filterIsInstance<WebView>().first()
            val height = controller.get().window.decorView.height.takeIf { it > 0 }
                ?: controller.get().resources.displayMetrics.heightPixels
            assertEquals((height * 0.55).toInt(), webView.layoutParams.height)
            assertFalse(webView.settings.javaScriptEnabled)
            assertFalse(webView.settings.allowFileAccess)
            assertFalse(webView.settings.allowContentAccess)
            assertTrue(webView.settings.blockNetworkLoads)
            assertTrue(webView.settings.builtInZoomControls)
            assertEquals("file:///android_asset/licenses/index.html", shadowOf(webView).lastLoadedUrl)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertTrue(dialog.isShowing)
            assertEquals("file:///android_asset/licenses/index.html", shadowOf(webView).lastLoadedUrl)
            assertTrue(controller.get().assets.open("licenses/NDK-NOTICE.toolchain.txt").use { it.readBytes().size } > 800000)
            fragment.dismissNow()
            assertFalse(dialog.isShowing)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
