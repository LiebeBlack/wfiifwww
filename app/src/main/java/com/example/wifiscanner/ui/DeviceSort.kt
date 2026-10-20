package com.example.wifiscanner.ui

import androidx.annotation.StringRes
import com.example.wifiscanner.R
import com.example.wifiscanner.model.NetworkDevice

/**
 * Sort orders offered in the Devices screen and in Settings.
 *
 * The enum lives outside the UI so the choice can be persisted and unit-tested
 * without Compose. Sorting is stable and case-insensitive; unknown brands sort
 * last instead of first.
 */
enum class DeviceSort(@StringRes val labelRes: Int) {
    Ip(R.string.sort_ip),
    Name(R.string.sort_name),
    Kind(R.string.sort_kind),
    Vendor(R.string.sort_vendor),
    Services(R.string.sort_services),
    Mac(R.string.sort_mac);

    /** Applies this sort order to [devices]. */
    fun apply(devices: List<NetworkDevice>): List<NetworkDevice> = when (this) {
        Ip -> devices.sortedBy { it.ip.ipv4SortKey() }
        Name -> devices.sortedBy { it.displayName.lowercase() }
        Kind -> devices.sortedWith(compareBy({ it.kind.ordinal }, { it.displayName.lowercase() }))
        // "~" is above every letter in ASCII, so unknown brands end the list.
        Vendor -> devices.sortedWith(
            compareBy(
                { (it.vendor ?: it.models.firstOrNull() ?: "~").lowercase() },
                { it.displayName.lowercase() },
            )
        )
        Services -> devices.sortedWith(
            compareBy(
                { it.services.size },
                { it.displayName.lowercase() },
            )
        ).reversed() // Most services first
        Mac -> devices.sortedWith(
            compareBy(
                { it.mac ?: "zzzzzzzzzzzz" },
                { it.displayName.lowercase() },
            )
        )
    }
}
