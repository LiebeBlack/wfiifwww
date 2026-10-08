package com.example.wifiscanner.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Determines the local subnet (network address + prefix length) and the
 * device's own IP using both the legacy WifiManager DHCP info and modern
 * [java.net.NetworkInterface] enumeration (which also works on VPN/hotspot
 * setups where DhcpInfo is stale).
 *
 * Also exposes [isOnWifi] so the scanner can emit a sharp error when Wi-Fi
 * is off or the device is on mobile data only (the spec asks for graceful
 * handling of Wi-Fi-disabled and data-only states).
 */
object LocalNetworkInfo {

    /** Whether the device currently has an active Wi-Fi connection. */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        @Suppress("DEPRECATION")
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // Modern path: check NetworkCapabilities for Wi-Fi.
        cm.activeNetwork?.let { network ->
            cm.getNetworkCapabilities(network)?.takeIf { caps ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }?.let { return true }
        }
        // Fallback for older APIs / devices that expose Wi-Fi via WifiManager.
        @Suppress("DEPRECATION")
        return wifiManager?.isWifiEnabled == true &&
            cm.activeNetworkInfo?.type == android.net.NetworkInfo.TYPE_WIFI
    }

    /** The IPv4 subnet to scan, e.g. [192.168.1.0, /24]. */
    data class Subnet(val networkAddress: String, val prefixLength: Int) {
        /**
         * All usable host addresses (excludes network & broadcast).
         * Prefixes wider than /22 would mean tens of thousands of pings, so the
         * iteration is capped at the first 1022 hosts of such subnets.
         */
        fun hostAddresses(): Sequence<String> = sequence {
            val base = ipv4ToInt(networkAddress) ?: return@sequence
            val hosts = (1L shl (32 - prefixLength.coerceIn(0, 32)))
                .coerceAtMost(1024L) // safety cap for very wide prefixes
            val first = base + 1
            val last = base + hosts - 2
            for (addr in first..last) yield(intToIpv4(addr))
        }
    }

    /**
     * Best-effort detection of the Wi-Fi IPv4 subnet.
     *
     * Strategy:
     * 1. Enumerate real interfaces via NetworkInterface (authoritative, works
     *    on tethering/hotspot too).
     * 2. Fall back to WifiManager.getDhcpInfo() whose fields are
     *    little-endian ints on virtually every device.
     *
     * @return the subnet, or null when not connected to Wi-Fi.
     */
    fun detect(context: Context): Subnet? {
        // --- Strategy 1: real interface enumeration -----------------------
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                // wlan0 is the classic Wi-Fi interface; also accept any
                // interface that is not loopback/tether when Wi-Fi is active.
                val name = iface.name
                val isWifiLike = name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
                if (!isWifiLike) continue

                for (addr in iface.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val prefix = iface.interfaceAddresses
                        .firstOrNull { it.address == addr }
                        ?.networkPrefixLength?.toInt() ?: 24
                    return Subnet(networkAddress = networkOf(addr.hostAddress!!, prefix), prefixLength = prefix)
                }
            }
        } catch (_: Exception) {
            // Fall through to DHCP-info strategy.
        }

        // --- Strategy 2: WifiManager DHCP info ----------------------------
        return try {
            @Suppress("DEPRECATION")
            val dhcp = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
                .dhcpInfo
            if (dhcp == null || dhcp.ipAddress == 0 || dhcp.netmask == 0) return null

            val ip = littleEndianIntToIpv4(dhcp.ipAddress)
            val mask = littleEndianIntToIpv4(dhcp.netmask)
            val prefix = maskToInt(mask) ?: 24
            Subnet(networkAddress = networkOf(ip, prefix), prefixLength = prefix)
        } catch (_: Exception) {
            null
        }
    }

    /** The device's own non-loopback IPv4 on the given subnet's interface. */
    fun selfIp(subnet: Subnet): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isInSubnet(subnet) }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Computes the network (base) address for [ip] under [prefix] bits. */
    internal fun networkOf(ip: String, prefix: Int): String {
        val addr = ipv4ToInt(ip) ?: return ip
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return intToIpv4(addr and mask)
    }

    /** Parses "a.b.c.d" to an unsigned 32-bit int, or null. */
    internal fun ipv4ToInt(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (p in parts) {
            val octet = p.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    internal fun intToIpv4(value: Long): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"

    /** Android's DhcpInfo stores IPs little-endian; reverse the byte order. */
    internal fun littleEndianIntToIpv4(v: Int): String {
        val u = v.toLong() and 0xFFFFFFFFL
        return "${u and 0xFF}.${(u shr 8) and 0xFF}.${(u shr 16) and 0xFF}.${(u shr 24) and 0xFF}"
    }

    /** Converts a dotted mask ("255.255.255.0") to its prefix length. */
    internal fun maskToInt(mask: String): Int? {
        val value = ipv4ToInt(mask) ?: return null
        // Count leading 1 bits, then verify the mask is exactly that many
        // contiguous ones followed by zeros (rejects non-contiguous masks).
        val prefix = value.toString(2).padStart(32, '0').takeWhile { it == '1' }.length
        val expected = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return prefix.takeIf { expected == value }
    }

    private fun Inet4Address.isInSubnet(subnet: Subnet): Boolean {
        val me = ipv4ToInt(hostAddress ?: return false) ?: return false
        val base = ipv4ToInt(subnet.networkAddress) ?: return false
        val mask = (0xFFFFFFFFL shl (32 - subnet.prefixLength)) and 0xFFFFFFFFL
        return me and mask == base and mask
    }
}
