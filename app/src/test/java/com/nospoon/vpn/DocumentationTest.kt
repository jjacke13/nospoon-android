package com.nospoon.vpn

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
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

    private fun assertDocumentationIntent(intent: Intent?) {
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("https://jjacke13.github.io/nospoon/", intent.dataString)
    }
}
