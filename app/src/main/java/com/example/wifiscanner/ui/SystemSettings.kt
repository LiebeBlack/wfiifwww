package com.example.wifiscanner.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens the exact system screen the user needs to fix a permission or the
 * location switch. Centralised so the permission gate, the network screen and
 * settings all behave identically (and no screen builds its own intent).
 */
object SystemSettings {

    /** App details page, where a denied runtime permission can be re-granted. */
    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * System location switch. Android redacts the SSID/BSSID unless location
     * services are on, even when the runtime permission is already granted.
     */
    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
