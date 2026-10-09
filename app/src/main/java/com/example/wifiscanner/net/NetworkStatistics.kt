package com.example.wifiscanner.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Everything the app can legitimately know about the current Wi-Fi/LAN, in one
 * immutable snapshot for the UI.
 *
 * All of it comes from public APIs — no root, no privileged access:
 *
 *  - **Wi-Fi link**  : SSID, BSSID, signal strength/level, frequency, band,
 *    channel, negotiated link speeds (WifiManager + WifiInfo).
 *  - **IPv4 config**: address, prefix, network, broadcast, gateway, DNS,
 *    DHCP server and lease time (WifiManager.DhcpInfo, little-endian fields).
 *  - **Capabilities**: transport, metered flag, VPN presence and the
 *    link bandwidth the platform reports (ConnectivityManager).
 *
 * Every field is nullable and every read is guarded: on Android 10+ the SSID
 * and BSSID require the Location permission, and a denied read simply leaves
 * those fields null instead of failing the scan.
 */
object NetworkStatistics {

    /** Immutable snapshot of the current network. */
    data class WifiStatistics(
        // --- Wi-Fi link ---------------------------------------------------
        val ssid: String? = null,
        val bssid: String? = null,
        val signalDbm: Int? = null,
        /** 0..4, derived from [signalDbm]. */
        val signalLevel: Int? = null,
        val frequencyMhz: Int? = null,
        val channel: Int? = null,
        val band: String? = null,
        val linkSpeedMbps: Int? = null,
        val rxLinkSpeedMbps: Int? = null,
        val txLinkSpeedMbps: Int? = null,
        val wifiEnabled: Boolean? = null,
        // --- IPv4 configuration -------------------------------------------
        val ipAddress: String? = null,
        val prefixLength: Int? = null,
        val networkAddress: String? = null,
        val broadcastAddress: String? = null,
        val gateway: String? = null,
        val dns1: String? = null,
        val dns2: String? = null,
        val dhcpServer: String? = null,
        val leaseSeconds: Int? = null,
        /** Usable host addresses in the subnet (2^(32-prefix) - 2). */
        val subnetHosts: Int? = null,
        // --- Capabilities --------------------------------------------------
        val transport: String? = null,
        val metered: Boolean? = null,
        val vpnActive: Boolean? = null,
        val downstreamKbps: Int? = null,
        val upstreamKbps: Int? = null,
    )

    /** Reads the current state; never throws, missing data stays null. */
    fun snapshot(context: Context): WifiStatistics {
        val subnet = runCatching { LocalNetworkInfo.detect(context) }.getOrNull()
        val wifiInfo = readWifiInfo(context)
        val dhcp = readDhcpInfo(context)
        val capabilities = readCapabilities(context)
        val frequency = wifiInfo?.frequency?.takeIf { it > 0 }
        val selfIp = try {
            subnet?.let { LocalNetworkInfo.selfIp(it) }
        } catch (_: Exception) {
            null
        }

        return WifiStatistics(
            ssid = wifiInfo?.ssid?.stripQuotes()?.takeIf { it.isNotBlank() && it != "<unknown ssid>" },
            bssid = wifiInfo?.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" },
            signalDbm = wifiInfo?.rssi?.takeIf { it != 0 },
            signalLevel = wifiInfo?.rssi?.takeIf { it != 0 }?.let { signalLevelOf(it) },
            frequencyMhz = frequency,
            channel = frequency?.let { channelOf(it) },
            band = frequency?.let { bandOf(it) },
            linkSpeedMbps = wifiInfo?.linkSpeed?.takeIf { it > 0 },
            // API 30+ replaced the single linkSpeed with per-direction rates.
            rxLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wifiInfo?.rxLinkSpeedMbps?.takeIf { it > 0 }
            } else {
                null
            },
            txLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wifiInfo?.txLinkSpeedMbps?.takeIf { it > 0 }
            } else {
                null
            },
            wifiEnabled = runCatching { (context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled }.getOrNull(),
            // Interface enumeration first; the DHCP lease is the backstop.
            ipAddress = selfIp ?: dhcp?.ip?.takeIf { it != "0.0.0.0" },
            prefixLength = subnet?.prefixLength,
            networkAddress = subnet?.networkAddress,
            broadcastAddress = subnet?.broadcastAddress(),
            gateway = dhcp?.gateway?.takeIf { it != "0.0.0.0" },
            dns1 = dhcp?.dns1?.takeIf { it != "0.0.0.0" },
            dns2 = dhcp?.dns2?.takeIf { it != "0.0.0.0" },
            dhcpServer = dhcp?.server?.takeIf { it != "0.0.0.0" },
            leaseSeconds = dhcp?.leaseSeconds?.takeIf { it > 0 },
            subnetHosts = subnet?.let { usableHosts(it.prefixLength) },
            transport = capabilities?.transport,
            metered = capabilities?.metered,
            vpnActive = capabilities?.vpn,
            downstreamKbps = capabilities?.downstreamKbps,
            upstreamKbps = capabilities?.upstreamKbps,
        )
    }

    // ------------------------------------------------------------------
    // Pure helpers (unit-testable, no Android)
    // ------------------------------------------------------------------

    /**
     * 802.11 channel from the centre frequency: 2.4 GHz channels are
     * 5 MHz-spaced from 2407 MHz, 5/6 GHz channels from 5000 MHz.
     */
    internal fun channelOf(frequencyMhz: Int): Int? {
        return when (frequencyMhz) {
            in 2412..2484 -> (frequencyMhz - 2407) / 5
            in 5000..5895 -> (frequencyMhz - 5000) / 5
            in 5925..7125 -> (frequencyMhz - 5950) / 5
            else -> null
        }
    }

    /** Human band name for a frequency. */
    internal fun bandOf(frequencyMhz: Int): String? {
        return when (frequencyMhz) {
            in 2400..2500 -> "2.4 GHz"
            in 4900..5895 -> "5 GHz"
            in 5925..7125 -> "6 GHz"
            else -> null
        }
    }

    /** Coarse 0..4 signal level from RSSI in dBm (5 = excellent). */
    internal fun signalLevelOf(rssiDbm: Int): Int {
        return when {
            rssiDbm >= -50 -> 4
            rssiDbm >= -60 -> 3
            rssiDbm >= -70 -> 2
            rssiDbm >= -80 -> 1
            else -> 0
        }
    }

    /** Usable host addresses in a subnet, capped at the scanner's own cap. */
    internal fun usableHosts(prefixLength: Int): Int {
        return (prefixLength.coerceIn(0, 32))
            .let { prefix ->
                val hosts = (1L shl (32 - prefix)).coerceAtMost(1024L)
                (hosts - 2).coerceAtLeast(0).toInt()
            }
    }

    // ------------------------------------------------------------------
    // Guarded platform reads
    // ------------------------------------------------------------------

    /**
     * `WifiInfo`, when readable: since Android 10 the SSID/BSSID fields need
     * the Location permission and otherwise throw [SecurityException].
     */
    @Suppress("DEPRECATION")
    private fun readWifiInfo(context: Context): android.net.wifi.WifiInfo? = try {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.connectionInfo
    } catch (_: Exception) {
        null
    }

    /** DHCP lease details; null on setups without DHCP (static IP). */
    @Suppress("DEPRECATION")
    private fun readDhcpInfo(context: Context): DhcpSnapshot? {
        return try {
            val dhcp = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.dhcpInfo ?: return null
            if (dhcp.ipAddress == 0) return null
            DhcpSnapshot(
                ip = LocalNetworkInfo.littleEndianIntToIpv4(dhcp.ipAddress),
                gateway = LocalNetworkInfo.littleEndianIntToIpv4(dhcp.gateway),
                dns1 = LocalNetworkInfo.littleEndianIntToIpv4(dhcp.dns1),
                dns2 = LocalNetworkInfo.littleEndianIntToIpv4(dhcp.dns2),
                server = LocalNetworkInfo.littleEndianIntToIpv4(dhcp.serverAddress),
                leaseSeconds = dhcp.leaseDuration,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Transport, metered flag, VPN presence and reported link bandwidth. */
    private fun readCapabilities(context: Context): Capabilities? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val active = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(active) ?: return null
            Capabilities(
                transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                    else -> "Other"
                },
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            vpn = anyVpnActive(cm),
            downstreamKbps = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 },
            upstreamKbps = caps.linkUpstreamBandwidthKbps.takeIf { it > 0 },
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun anyVpnActive(cm: ConnectivityManager): Boolean? = try {
        cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (_: Exception) {
        null
    }

    private fun String.stripQuotes(): String = trim().removeSurrounding("\"")

    private data class DhcpSnapshot(
        val ip: String,
        val gateway: String,
        val dns1: String,
        val dns2: String,
        val server: String,
        val leaseSeconds: Int,
    )

    private data class Capabilities(
        val transport: String,
        val metered: Boolean,
        val vpn: Boolean?,
        val downstreamKbps: Int?,
        val upstreamKbps: Int?,
    )
}
