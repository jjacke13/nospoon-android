package com.nospoon.vpn

import android.app.Application
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
}
