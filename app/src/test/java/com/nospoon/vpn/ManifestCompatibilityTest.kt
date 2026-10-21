package com.nospoon.vpn

import android.app.Application
import android.content.ComponentName
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class ManifestCompatibilityTest {
    @Test
    fun manualConfigurationDoesNotRequireCameraHardware() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_CONFIGURATIONS)
        val cameras = info.reqFeatures.orEmpty().filter { it.name?.startsWith("android.hardware.camera") == true }
        assertTrue(cameras.isNotEmpty())
        assertTrue(cameras.all { it.flags and FeatureInfo.FLAG_REQUIRED == 0 })
    }

    @Test
    fun credentialsAreExcludedFromLegacyBackup() {
        val app = RuntimeEnvironment.getApplication()
        val rules = ReflectionHelpers.getField<Int>(app.applicationInfo, "fullBackupContent")
        assertProfileExcluded(rules, "full-backup-content")
    }

    @Test
    @Config(sdk = [35])
    fun credentialsAreExcludedFromCloudBackupAndDeviceTransfer() {
        val app = RuntimeEnvironment.getApplication()
        var rules = 0
        // Robolectric's legacy package parser omits this newer ApplicationInfo field.
        app.assets.openXmlResourceParser("AndroidManifest.xml").use { manifest ->
            while (manifest.next() != XmlPullParser.END_DOCUMENT) {
                if (manifest.eventType == XmlPullParser.START_TAG && manifest.name == "application") {
                    rules = manifest.getAttributeResourceValue(
                        "http://schemas.android.com/apk/res/android", "dataExtractionRules", 0
                    )
                }
            }
        }
        assertProfileExcluded(rules, "cloud-backup", "device-transfer")
    }

    @Test
    @Config(sdk = [27, 35])
    fun unsupportedAlwaysOnVpnIsExplicitlyDisabled() {
        val app = RuntimeEnvironment.getApplication()
        val service = app.packageManager.getServiceInfo(
            ComponentName(app, NospoonVpnService::class.java), PackageManager.GET_META_DATA
        )
        val key = "android.net.VpnService.SUPPORTS_ALWAYS_ON"
        assertNotNull(service.metaData)
        assertTrue(service.metaData.containsKey(key))
        assertFalse(service.metaData.getBoolean(key, true))
    }

    private fun assertProfileExcluded(rules: Int, vararg sections: String) {
        assertTrue("The manifest must reference backup rules", rules > 0)
        val paths = setOf("nospoon_configs.xml", "nospoon_configs.xml.bak")
        val excluded = mutableSetOf<Pair<String, String>>()
        RuntimeEnvironment.getApplication().resources.getXml(rules).use { xml ->
            var section = ""
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                if (xml.name in sections) section = xml.name
                if (xml.name == "exclude" && xml.getAttributeValue(null, "domain") == "sharedpref") {
                    val path = xml.getAttributeValue(null, "path") ?: continue
                    if (path in paths) excluded += section to path
                }
            }
        }
        assertEquals(sections.flatMap { section -> paths.map { section to it } }.toSet(), excluded)
    }
}
