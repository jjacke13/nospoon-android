package com.nospoon.vpn

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Application entry point. Wires up Material You dynamic colors so that on
 * devices that support it (Android 12+ with theming enabled) the app
 * adopts the system accent. On older / non-themed devices, our static
 * Material 3 palette is used unchanged.
 */
class NospoonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
