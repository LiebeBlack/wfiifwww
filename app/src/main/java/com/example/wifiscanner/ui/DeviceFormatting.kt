package com.example.wifiscanner.ui

import com.example.wifiscanner.model.NetworkDevice

/**
 * Small, pure presentation helpers shared by the list, the detail sheet and
 * the export. Kept free of Android APIs so they can be unit-tested.
 */

/** Numeric IPv4 sort key so 192.168.1.2 sorts before 192.168.1.10. */
internal fun String.ipv4SortKey(): Long =
    split('.').mapNotNull { it.toLongOrNull() }
        .takeIf { it.size == 4 }
        ?.fold(0L) { acc, octet -> acc * 256 + octet }
        ?: Long.MAX_VALUE

/**
 * Case-insensitive match across everything the user can see about a device:
 * address, hardware address, name, brand, type, services and ports.
 */
internal fun NetworkDevice.matchesQuery(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    val haystack = buildString {
        append(ip.lowercase()).append(' ')
        mac?.let { append(it.lowercase()).append(' ') }
        vendor?.let { append(it.lowercase()).append(' ') }
        hostname?.let { append(it.lowercase()).append(' ') }
        location?.let { append(it.lowercase()).append(' ') }
        append(kind.label.lowercase()).append(' ')
        services.forEach { append(it.lowercase()).append(' ') }
        models.forEach { append(it.lowercase()).append(' ') }
        openPorts.forEach { append(it).append(' ') }
    }
    return haystack.contains(needle)
}
