package com.nospoon.vpn

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class DocumentationTest {
    private lateinit var controller: ActivityController<MainActivity>

    @Before
    fun setUp() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    @After
    fun tearDown() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(false)
        controller.pause().stop().destroy()
    }

    @Test
    fun emptyScreenAndMenuOpenTheDocumentation() {
        val activity = controller.get()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyState).visibility)
        activity.findViewById<View>(R.id.documentationButton).performClick()
        assertDocumentationIntent(shadowOf(activity).nextStartedActivity)
        val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
        assertTrue(toolbar.menu.performIdentifierAction(R.id.action_documentation, 0))
        assertDocumentationIntent(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun menuRemainsAvailableAfterAddingAProfile() {
        val activity = controller.get()
        activity.onConfigSaved(VpnConfig(name = "Test profile", server = "ab".repeat(32)))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyState).visibility)
        assertFalse(activity.findViewById<View>(R.id.documentationButton).isShown)
        activity.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_documentation, 0)
        assertDocumentationIntent(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun missingBrowserOffersCopyingThePublicLink() {
        val activity = controller.get()
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        activity.findViewById<View>(R.id.documentationButton).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(activity.getString(R.string.documentation_no_browser),
            activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).text.toString())
        activity.findViewById<Button>(com.google.android.material.R.id.snackbar_action).performClick()
        assertEquals(activity.getString(R.string.documentation_url),
            activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    @Config(sdk = [35], qualifiers = "el-sw600dp-w800dp-h1280dp")
    fun tabletShowsTheGreekDocumentationShortcut() {
        val button = controller.get().findViewById<Button>(R.id.documentationButton)
        assertEquals("Βοήθεια και τεκμηρίωση", button.text.toString())
        button.performClick()
        assertDocumentationIntent(shadowOf(controller.get()).nextStartedActivity)
    }

    @Test
    fun infoIsAvailableWithProfilesAndOpensDocsOnlyOnRequest() {
        val activity = controller.get()
        activity.onConfigSaved(VpnConfig(name = "Example server", server = "ab".repeat(32)))
        val info = activity.findViewById<FloatingActionButton>(R.id.fabInfo)
        val add = activity.findViewById<FloatingActionButton>(R.id.fabAdd)
        assertTrue(info.isShown)
        assertEquals(add.customSize, info.customSize)
        assertTrue(info.customSize > 56 * activity.resources.displayMetrics.density)
        val sheet = openInfo()
        assertNull(shadowOf(activity).nextStartedActivity)
        sheet.requireView().findViewById<View>(R.id.infoDocumentationButton).performClick()
        assertDocumentationIntent(shadowOf(activity).nextStartedActivity)
        sheet.requireView().findViewById<View>(R.id.closeInfoButton).performClick()
        activity.supportFragmentManager.executePendingTransactions()
        assertFalse(sheet.isAdded)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyState).visibility)
    }

    @Test
    fun rapidInfoTapsOpenOnlyOneSheet() {
        val activity = controller.get()
        repeat(2) { activity.findViewById<View>(R.id.fabInfo).performClick() }
        activity.supportFragmentManager.executePendingTransactions()
        assertEquals(1, activity.supportFragmentManager.fragments.count { it is InfoBottomSheet })
    }

    @Test
    fun infoSurvivesRotationAndKeepsTheDocsAction() {
        openInfo()
        controller.recreate()
        val activity = controller.get()
        val sheet = activity.supportFragmentManager.findFragmentByTag("nospoon_info") as InfoBottomSheet
        assertTrue(sheet.requireDialog().isShowing)
        sheet.requireView().findViewById<View>(R.id.infoDocumentationButton).performClick()
        assertDocumentationIntent(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun infoMissingBrowserOffersCopyInsideTheSheet() {
        val activity = controller.get()
        val sheet = openInfo()
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        sheet.requireView().findViewById<View>(R.id.infoDocumentationButton).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = sheet.requireDialog()
        val action = dialog.findViewById<Button>(com.google.android.material.R.id.snackbar_action)
        assertNotNull(action)
        assertTrue(action.isShown)
        action.performClick()
        assertEquals(activity.getString(R.string.documentation_url),
            activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    @Config(sdk = [35], qualifiers = "el-w640dp-h360dp-land")
    fun shortGreekInfoSheetScrollsWithoutHidingItsActions() {
        RuntimeEnvironment.setFontScale(2f)
        controller.recreate()
        val sheet = openInfo()
        val root = sheet.requireView()
        val density = root.resources.displayMetrics.density
        val width = (600 * density).toInt()
        val height = (280 * density).toInt()
        fun assertUsableLayout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val scroll = root.findViewById<NestedScrollView>(R.id.infoScroll)
            assertTrue("Body must have a scroll viewport", scroll.height > 0)
            assertTrue(scroll.getChildAt(0).height > scroll.height)
            for (id in listOf(R.id.closeInfoButton, R.id.infoDocumentationButton)) {
                val action = root.findViewById<View>(id)
                val bounds = Rect()
                action.getDrawingRect(bounds)
                (root as ViewGroup).offsetDescendantRectToMyCoords(action, bounds)
                assertTrue("Action must fit above the bottom inset", bounds.bottom <= height - root.paddingBottom)
                assertTrue("Action must fit below the top inset", bounds.top >= root.paddingTop)
            }
        }
        assertUsableLayout()
        assertEquals("Ξεκινήστε με το nospoon", root.findViewById<TextView>(R.id.infoTitle).text.toString())
        val padding = root.paddingLeft
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(24, 48, 12, 36)).build()
        repeat(2) { ViewCompat.dispatchApplyWindowInsets(root, insets) }
        assertEquals(padding + 24, root.paddingLeft)
        assertEquals(padding + 36, root.paddingBottom)
        assertUsableLayout()
        assertTrue(root.findViewById<View>(R.id.closeInfoButton).performClick())
    }

    private fun openInfo(): InfoBottomSheet {
        val activity = controller.get()
        activity.findViewById<View>(R.id.fabInfo).performClick()
        activity.supportFragmentManager.executePendingTransactions()
        return activity.supportFragmentManager.findFragmentByTag("nospoon_info") as InfoBottomSheet
    }

    private fun assertDocumentationIntent(intent: Intent?) {
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("https://jjacke13.github.io/nospoon/", intent.dataString)
    }
}
