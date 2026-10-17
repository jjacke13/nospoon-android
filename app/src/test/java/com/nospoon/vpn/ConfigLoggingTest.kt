package com.nospoon.vpn

import android.app.Application
import android.app.Service
import android.content.Intent
import android.widget.EditText
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class ConfigLoggingTest {
    @Test
    fun importsDoNotLogInputOrParserExceptions() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java)
        activity.get().setTheme(R.style.Theme_Nospoon)
        activity.setup()
        try {
            val sheet = ConfigEditorBottomSheet()
            sheet.showNow(activity.get().supportFragmentManager, "editor")
            val marker = "SYNTHETIC_SECRET_NEVER_LOG"
            for (input in listOf("{\"seed\":\"$marker\",", "{\"$marker\":true}")) {
                ShadowLog.clear()
                sheet.setScannedText(ConfigEditorBottomSheet.ScanTarget.FULL_CONFIG, input)
                val logs = ShadowLog.getLogsForTag("ConfigEditor")
                assertTrue("Rejection should retain a diagnostic", logs.isNotEmpty())
                assertTrue(logs.none { marker in it.msg || it.throwable != null })
                assertEquals("", sheet.requireView().findViewById<EditText>(R.id.inputServerKey).text.toString())
                val error = if (input.endsWith(",")) R.string.invalid_json_qr else R.string.qr_missing_server
                assertEquals(activity.get().getString(error), ShadowToast.getTextOfLatestToast())
            }
            ShadowLog.clear()
            sheet.setScannedText(
                ConfigEditorBottomSheet.ScanTarget.FULL_CONFIG,
                "{\"server\":\"${"ab".repeat(32)}\",\"ip\":\"$marker\"}"
            )
            assertEquals(marker, sheet.requireView().findViewById<EditText>(R.id.inputIp).text.toString())
            assertTrue(ShadowLog.getLogsForTag("ConfigEditor").none { marker in it.msg || it.throwable != null })
            assertEquals(activity.get().getString(R.string.config_imported), ShadowToast.getTextOfLatestToast())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun invalidServiceConfigIsRejectedWithoutLoggingItsContents() {
        val controller = Robolectric.buildService(NospoonVpnService::class.java).create()
        try {
            ShadowLog.clear()
            val marker = "SYNTHETIC_SECRET_NEVER_LOG"
            val intent = Intent().setAction(NospoonVpnService.ACTION_START)
                .putExtra(NospoonVpnService.EXTRA_CONFIG_JSON, "{\"seed\":\"$marker\",")
            assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(intent, 0, 1))
            val logs = ShadowLog.getLogsForTag(NospoonVpnService.TAG)
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.none { marker in it.msg || it.throwable != null })
        } finally {
            controller.destroy()
        }
    }
}
