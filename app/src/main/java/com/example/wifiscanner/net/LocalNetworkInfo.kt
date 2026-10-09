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
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
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

        /**
         * Directed broadcast address (e.g. `192.168.1.255`), used by the
         * NetBIOS query so every host on the link hears it in one datagram.
         */
        fun broadcastAddress(): String {
            val base = ipv4ToInt(networkAddress) ?: return networkAddress
            val prefix = prefixLength.coerceIn(0, 32)
            val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            return intToIpv4((base or mask.inv()) and 0xFFFFFFFFL)
        }
    }

    /**
     * Best-effort detection of the local IPv4 subnet.
     *
     * Four independent strategies are chained, cheapest and most reliable
     * first. Each one is fully guarded, so a ROM that blocks (or lies about)
     * one source can never stop us from finding a subnet by another route —
     * and finding *no* subnet is not an error, the scanner simply switches to
     * multicast-only discovery.
     *
     * 1. Wi-Fi interfaces (`wlan*`, `ap*`, `swlan*`) via [NetworkInterface].
     * 2. [ConnectivityManager] link properties of the active network
     *    (authoritative on API 23+, also covers USB tethering/VPN).
     * 3. Any other up, non-loopback LAN interface holding a private IPv4
     *    (hotspot interfaces on vendor ROMs use names like `softap0`).
     * 4. Legacy [WifiManager.getDhcpInfo], whose fields are little-endian
     *    ints on virtually every device and may be stale but rarely absent.
     *
     * @return the subnet, or null when nothing usable was found.
     */
    fun detect(context: Context): Subnet? =
        viaWifiInterface()
            ?: viaConnectivityManager(context)
            ?: viaAnyLanInterface()
            ?: viaDhcpInfo(context)

    /** Strategy 1: real Wi-Fi interface enumeration (works on hotspot too). */
    private fun viaWifiInterface(): Subnet? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { !isMobileOrVpn(it.name) }
            .firstNotNullOfOrNull { iface ->
                if (iface.isUp && !iface.isLoopback && isWifiLike(iface.name)) {
                    subnetOf(iface)
                } else null
            }
    } catch (_: Exception) {
        null
    }

    /** Strategy 2: whatever the ConnectivityManager says the link is. */
    private fun viaConnectivityManager(context: Context): Subnet? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val properties = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
        val link = properties.linkAddresses.firstOrNull { candidate ->
            val addr = candidate.address
            addr is Inet4Address && !addr.isLoopbackAddress && isPrivateIpv4(addr.hostAddress)
        } ?: return null
        val ip = link.address?.hostAddress ?: return null
        val prefix = link.prefixLength.takeIf { it in 1..32 } ?: 24
        Subnet(networkAddress = networkOf(ip, prefix), prefixLength = prefix)
    } catch (_: Exception) {
        null
    }

    /**
     * Strategy 3: any other LAN-like interface with a private IPv4 address.
     * Catches vendor hotspot interfaces (`softap0`, `wlan1`, USB `eth0`) that
     * strategy 1's name heuristics miss.
     */
    private fun viaAnyLanInterface(): Subnet? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .firstNotNullOfOrNull { iface ->
                if (iface.isUp && !iface.isLoopback && !isMobileOrVpn(iface.name)) {
                    subnetOf(iface)
                } else null
            }
    } catch (_: Exception) {
        null
    }

    /** Strategy 4: legacy WifiManager DHCP info. */
    private fun viaDhcpInfo(context: Context): Subnet? = try {
        @Suppress("DEPRECATION")
        val dhcp = (context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.dhcpInfo
        if (dhcp == null || dhcp.ipAddress == 0 || dhcp.netmask == 0) return null

        val ip = littleEndianIntToIpv4(dhcp.ipAddress)
        val mask = littleEndianIntToIpv4(dhcp.netmask)
        val prefix = maskToInt(mask) ?: 24
        Subnet(networkAddress = networkOf(ip, prefix), prefixLength = prefix)
    } catch (_: Exception) {
        null
    }

    /**
     * First usable IPv4 subnet of [iface]; [privateOnly] restricts the search
     * to LAN ranges so a carrier/VPN address is never mistaken for a LAN.
     */
    private fun subnetOf(iface: NetworkInterface, privateOnly: Boolean = true): Subnet? = try {
        val prefixByAddress = iface.interfaceAddresses
            .mapNotNull { link -> link.address?.let { it to link.networkPrefixLength.toInt() } }
            .toMap()
        iface.inetAddresses.toList()
            .filterIsInstance<Inet4Address>()
            .firstNotNullOfOrNull { addr ->
                val ip = addr.hostAddress
                if (addr.isLoopbackAddress || ip == null) return@firstNotNullOfOrNull null
                if (privateOnly && !isPrivateIpv4(ip)) return@firstNotNullOfOrNull null
                val prefix = prefixByAddress[addr]?.takeIf { it in 1..32 } ?: 24
                Subnet(networkAddress = networkOf(ip, prefix), prefixLength = prefix)
            }
    } catch (_: Exception) {
        null
    }

    private fun isWifiLike(name: String): Boolean =
        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")

    /** Interfaces that belong to mobile data or a VPN, never to a LAN. */
    private fun isMobileOrVpn(name: String): Boolean =
        MOBILE_OR_VPN_PREFIXES.any { name.startsWith(it) }

    /**
     * True for the RFC1918 LAN ranges. Link-local 169.254/16 is deliberately
     * rejected: it means DHCP failed, so there is no LAN to scan.
     */
    private fun isPrivateIpv4(ip: String?): Boolean {
        val value = ipv4ToInt(ip ?: return false) ?: return false
        val first = (value shr 24) and 0xFF
        val second = (value shr 16) and 0xFF
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
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

    /** Interface name prefixes used by mobile data and VPN tunnels. */
    private val MOBILE_OR_VPN_PREFIXES =
        listOf("rmnet", "ccmni", "pdp", "tun", "tap", "ppp", "clat")
}
