package com.nospoon.vpn

import android.app.Application
import android.net.VpnService
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27, 35], application = Application::class, shadows = [CapturingVpnBuilder::class])
class VpnBlockingTest {
    @Test
    fun bothRoutingModesRequestBlockingTunBeforeEstablish() {
        val controller = Robolectric.buildService(NospoonVpnService::class.java).create()
        try {
            val establish = NospoonVpnService::class.java.getDeclaredMethod("establishVpn", JSONObject::class.java)
            establish.isAccessible = true
            for (fullTunnel in listOf(false, true)) {
                CapturingVpnBuilder.blocking = null
                establish.invoke(controller.get(), JSONObject().put("fullTunnel", fullTunnel))
                assertEquals(true, CapturingVpnBuilder.blocking)
            }
        } finally {
            controller.destroy()
        }
    }
}

@Implements(VpnService.Builder::class)
@Suppress("UNUSED_PARAMETER")
class CapturingVpnBuilder {
    @RealObject private lateinit var builder: VpnService.Builder

    // Capture TUN mode without Android's network parser or package-manager Binder service.
    @Implementation
    fun addAddress(address: String, prefixLength: Int): VpnService.Builder = builder

    @Implementation
    fun addRoute(address: String, prefixLength: Int): VpnService.Builder = builder

    @Implementation
    fun addDnsServer(address: String): VpnService.Builder = builder

    @Implementation
    fun addDisallowedApplication(packageName: String): VpnService.Builder = builder

    @Implementation
    fun establish(): ParcelFileDescriptor? {
        val config = ReflectionHelpers.getField<Any>(builder, "mConfig")
        blocking = ReflectionHelpers.getField(config, "blocking")
        return null // Capture builder settings without creating a real VPN or opening sockets.
    }

    companion object {
        var blocking: Boolean? = null
    }
}
