package com.nospoon.vpn

import android.app.Application
import android.view.View
import android.widget.EditText
import androidx.fragment.app.FragmentActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class ConfigImportTest {
    private lateinit var activity: ActivityController<FragmentActivity>
    private var saved: VpnConfig? = null
    private val server = "ab".repeat(32)
    private val replacementServer = "cd".repeat(32)

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(FragmentActivity::class.java)
        activity.get().setTheme(R.style.Theme_Nospoon)
        activity.setup()
    }

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
    }

    @Test
    fun importedMtuIsSavedForNewAndExistingProfiles() {
        for (original in listOf(null, VpnConfig(name = "Existing", server = server, mtu = 1300))) {
            val sheet = openEditor(original)
            sheet.setScannedText(ConfigEditorBottomSheet.ScanTarget.FULL_CONFIG,
                """{/* JSONC from a file or QR */ "server":"$replacementServer", "ip":"192.0.2.2/24", "mtu":1280, "fullTunnel":true} """)
            val result = save(sheet)
            assertEquals(1280, result.mtu)
            assertEquals(replacementServer, result.server)
            assertEquals("192.0.2.2/24", result.ip)
            assertTrue(result.fullTunnel)
            if (original != null) {
                assertEquals(original.id, result.id)
                assertEquals(original.name, result.name)
            }
        }
    }

    @Test
    fun omittedFieldsReplaceOldValuesWithSchemaDefaultsAndClearSeed() {
        // A non-hex marker exercises field replacement without invoking the ARM JNI library.
        val original = VpnConfig(name = "Existing", server = server,
            seed = "SYNTHETIC_OLD_SEED", ip = "192.0.2.7/24", mtu = 1280, fullTunnel = true)
        val sheet = openEditor(original)
        sheet.setScannedText(ConfigEditorBottomSheet.ScanTarget.FULL_CONFIG,
            """{"server":"$replacementServer"}""")
        assertEquals("", sheet.requireView().findViewById<EditText>(R.id.inputSeed).text.toString())
        val result = save(sheet)
        assertNull(result.seed)
        assertEquals(1400, result.mtu)
        assertEquals("10.0.0.2/24", result.ip)
        assertFalse(result.fullTunnel)
        assertEquals(original.id, result.id)
        assertEquals(original.name, result.name)
    }

    @Test
    fun editingWithoutImportPreservesExistingMtu() {
        val original = VpnConfig(server = server, mtu = 1320)
        assertEquals(original, save(openEditor(original)))
    }

    @Test
    fun invalidImportedMtuLeavesTheExistingDraftUntouched() {
        val original = VpnConfig(server = server, mtu = 1320)
        for (mtu in listOf(0, -1, Framing.MAX_FRAME_SIZE + 1)) {
            val sheet = openEditor(original)
            sheet.setScannedText(ConfigEditorBottomSheet.ScanTarget.FULL_CONFIG,
                """{"server":"$replacementServer","mtu":$mtu,"fullTunnel":true}""")
            assertEquals(activity.get().getString(R.string.err_invalid_config), ShadowToast.getTextOfLatestToast())
            assertEquals(original, save(sheet))
        }
    }

    private fun openEditor(original: VpnConfig?): ConfigEditorBottomSheet {
        val sheet = original?.let { ConfigEditorBottomSheet.newInstance(it) } ?: ConfigEditorBottomSheet()
        sheet.listener = object : ConfigEditorBottomSheet.ConfigEditorListener {
            override fun onConfigSaved(config: VpnConfig) { saved = config }
            override fun onConfigDeleted(configId: String) {}
            override fun onScanRequested(target: ConfigEditorBottomSheet.ScanTarget) {}
            override fun onImportFileRequested() {}
        }
        sheet.showNow(activity.get().supportFragmentManager, "editor")
        return sheet
    }

    private fun save(sheet: ConfigEditorBottomSheet): VpnConfig {
        saved = null
        sheet.requireView().findViewById<View>(R.id.btnSave).performClick()
        activity.get().supportFragmentManager.executePendingTransactions()
        return requireNotNull(saved) { "A valid configuration should reach the save callback" }
    }
}
