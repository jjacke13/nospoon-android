package com.nospoon.vpn

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.View
import android.widget.EditText
import com.google.zxing.DecodeHintType
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class QrScannerTest {
    private lateinit var controller: ActivityController<MainActivity>
    private val server = "ab".repeat(32)

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().findViewById<View>(R.id.fabAdd).performClick()
        controller.get().supportFragmentManager.executePendingTransactions()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test
    fun bundledScannerDecodesQrKeysAndConfigsButRejectsLinearBarcodes() {
        val scan = launch(R.id.btnScanKey)
        assertEquals("QR_CODE", scan.intent.getStringExtra(Intents.Scan.FORMATS))
        assertFalse(scan.intent.getBooleanExtra(Intents.Scan.BEEP_ENABLED, true))
        assertFalse(scan.intent.getBooleanExtra(Intents.Scan.BARCODE_IMAGE_ENABLED, true))
        val info = controller.get().packageManager.getActivityInfo(scan.intent.component!!, 0)
        assertEquals(QrScannerActivity::class.java.name, info.name)
        assertEquals(com.google.zxing.client.android.R.style.zxing_CaptureTheme, info.theme)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN, info.softInputMode)
        assertFalse(info.exported)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, info.screenOrientation)

        val capture = Robolectric.buildActivity(QrScannerActivity::class.java, scan.intent).create()
        try {
            val view = capture.get().findViewById<DecoratedBarcodeView>(
                com.google.zxing.client.android.R.id.zxing_barcode_scanner)
            val decoder = view.barcodeView.decoderFactory.createDecoder(emptyMap<DecodeHintType, Any>())
            for (payload in listOf(server, """{"server":"$server","mtu":1280}""")) {
                assertEquals(payload, decoder.decode(image(payload, BarcodeFormat.QR_CODE))?.text)
            }
            assertNull(decoder.decode(image("1234567890", BarcodeFormat.CODE_128)))
        } finally {
            capture.destroy()
        }
    }

    @Test
    @Config(sdk = [35])
    fun scannerPromptStaysAboveNavigationBarWithoutAccumulatingInsets() {
        val capture = Robolectric.buildActivity(QrScannerActivity::class.java, launch(R.id.btnScanKey).intent).create()
        try {
            val content = capture.get().findViewById<ViewGroup>(android.R.id.content)
            for (bottom in listOf(32, 48, 32)) {
                ViewCompat.dispatchApplyWindowInsets(content, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(8, 24, 8, bottom))
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 40, 0, 0)).build())
                content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, 1080, 2400)
                val prompt = capture.get().findViewById<View>(com.google.zxing.client.android.R.id.zxing_status_view)
                val bounds = Rect(0, 0, prompt.width, prompt.height)
                content.offsetDescendantRectToMyCoords(prompt, bounds)
                assertFalse(bounds.isEmpty)
                assertTrue("Prompt must stay above system navigation", bounds.bottom <= content.height - bottom)
                assertEquals(bottom, content.paddingBottom)
                assertEquals(40, content.paddingTop)
            }
        } finally {
            capture.destroy()
        }
    }

    @Test
    fun resultsReachEachOfTheThreeEditorFields() {
        complete(launch(R.id.btnScanKey), "  $server\n")
        assertEquals(server, field(R.id.inputServerKey))
        // Non-hex text checks routing without invoking ARM JNI seed derivation on the host.
        complete(launch(R.id.btnScanSeed), " SYNTHETIC_SEED ")
        assertEquals("SYNTHETIC_SEED", field(R.id.inputSeed))
        complete(launch(R.id.btnScanConfig), """{"server":"${"cd".repeat(32)}","ip":"192.0.2.2/24"}""")
        assertEquals("cd".repeat(32), field(R.id.inputServerKey))
        assertEquals("192.0.2.2/24", field(R.id.inputIp))
        assertEquals("", field(R.id.inputSeed))
    }

    @Test
    fun cancellationAndPermissionDenialPreserveDraftAndAllowRetry() {
        complete(launch(R.id.btnScanKey), server)
        val cancelled = launch(R.id.btnScanKey)
        controller.get().activityResultRegistry.dispatchResult(cancelled.requestCode, Activity.RESULT_CANCELED, null)
        assertEquals(server, field(R.id.inputServerKey))
        val denied = launch(R.id.btnScanSeed)
        controller.get().activityResultRegistry.dispatchResult(denied.requestCode, Activity.RESULT_CANCELED,
            Intent().putExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, true))
        assertEquals(controller.get().getString(R.string.scan_camera_permission), ShadowToast.getTextOfLatestToast())
        assertEquals("", field(R.id.inputSeed))
        complete(launch(R.id.btnScanKey), "cd".repeat(32))
        assertEquals("cd".repeat(32), field(R.id.inputServerKey))
    }

    @Test
    fun pendingScanSurvivesActivityRecreationAndIgnoresNonQrResults() {
        val scan = launch(R.id.btnScanKey)
        controller.recreate()
        complete(scan, server)
        assertEquals(server, field(R.id.inputServerKey))
        val next = launch(R.id.btnScanKey)
        controller.get().activityResultRegistry.dispatchResult(next.requestCode, Activity.RESULT_OK,
            Intent().putExtra(Intents.Scan.RESULT, "1234567890")
                .putExtra(Intents.Scan.RESULT_FORMAT, "CODE_128"))
        assertEquals(server, field(R.id.inputServerKey))
    }

    private fun sheet() = controller.get().supportFragmentManager
        .findFragmentByTag("config_editor") as ConfigEditorBottomSheet

    private fun field(id: Int) = sheet().requireView().findViewById<EditText>(id).text.toString()

    private fun launch(button: Int): ShadowActivity.IntentForResult {
        sheet().requireView().findViewById<View>(button).performClick()
        return requireNotNull(shadowOf(controller.get()).nextStartedActivityForResult)
    }

    private fun complete(scan: ShadowActivity.IntentForResult, text: String) {
        controller.get().activityResultRegistry.dispatchResult(scan.requestCode, Activity.RESULT_OK,
            Intent().putExtra(Intents.Scan.RESULT, text).putExtra(Intents.Scan.RESULT_FORMAT, "QR_CODE"))
    }

    private fun image(text: String, format: BarcodeFormat): RGBLuminanceSource {
        val matrix = MultiFormatWriter().encode(text, format, 600, 600)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        return RGBLuminanceSource(matrix.width, matrix.height, pixels)
    }
}
