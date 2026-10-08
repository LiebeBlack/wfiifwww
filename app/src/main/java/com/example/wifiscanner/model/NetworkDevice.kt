package com.example.wifiscanner.model

/**
 * Immutable domain model for a device discovered on the LAN.
 *
 * @param ip        IPv4 address, e.g. "192.168.1.23".
 * @param mac       Hardware address from the ARP table (uppercase, colon
 *                  separated). Null when the sweep found the host but the
 *                  neighbor cache has no L2 entry yet.
 * @param vendor    Manufacturer resolved offline from the MAC OUI prefix.
 * @param hostname  Friendly name from mDNS or reverse DNS, when available.
 * @param isSelf    True for the Android device running the scanner.
 * @param location  User-defined physical label ("Living Room") persisted in
 *                  Room, keyed by MAC. Editable in the UI.
 */
data class NetworkDevice(
    val ip: String,
    val mac: String?,
    val vendor: String? = null,
    val hostname: String? = null,
    val isSelf: Boolean = false,
    val location: String? = null,
) {
    /** Stable identity for list diffs: prefer MAC (survives DHCP changes). */
    val stableId: String get() = mac ?: ip

    /** True when we know at least the L2/L3 identity. */
    val isIdentified: Boolean get() = mac != null
}
