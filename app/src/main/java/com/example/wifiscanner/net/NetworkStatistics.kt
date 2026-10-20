package com.example.wifiscanner.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

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
 * Two details make this reliable in practice:
 *
 *  1. **VPN-aware Wi-Fi detection.** When a VPN is active it becomes the
 *     *active* network, so checking only `activeNetwork` reports "VPN" and the
 *     UI used to conclude there was no Wi-Fi. Instead every network is
 *     inspected for a Wi-Fi transport.
 *  2. **SSID redaction is explained, not hidden.** Android returns
 *     `<unknown ssid>` unless the location permission is granted *and* location
 *     services are on. The snapshot carries both states so the UI can offer a
 *     fix instead of pretending the phone is offline.
 *
 * Every field is nullable and every read is guarded: a denied or unsupported
 * read simply leaves the field null instead of failing the scan.
 */
object NetworkStatistics {

    /** Transport of the link that actually carries LAN traffic. */
    enum class Transport { Wifi, Cellular, Ethernet, Vpn, Bluetooth, Other }

    /** Immutable snapshot of the current network. */
    data class WifiStatistics(
        // --- Wi-Fi link ---------------------------------------------------
        val ssid: String? = null,
        val bssid: String? = null,
        /** True when a Wi-Fi interface is connected (VPN does not hide it). */
        val wifiConnected: Boolean = false,
        /** Connected to Wi-Fi but the SSID is redacted by the platform. */
        val ssidHidden: Boolean = false,
        /** Whether ACCESS_FINE_LOCATION is currently granted. */
        val locationPermissionGranted: Boolean = true,
        /** Whether the system location switch is on (needed for the SSID). */
        val locationServicesEnabled: Boolean = true,
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
        val transport: Transport? = null,
        val metered: Boolean? = null,
        val vpnActive: Boolean? = null,
        val downstreamKbps: Int? = null,
        val upstreamKbps: Int? = null,
    )

    /** Reads the current state; never throws, missing data stays null. */
    fun snapshot(context: Context): WifiStatistics {
        val app = context.applicationContext
        val wifiManager = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiInfo = readWifiInfo(app, wifiManager)
        val dhcp = readDhcpInfo(wifiManager)
        val subnet = runCatching { LocalNetworkInfo.detect(app) }.getOrNull()
        val selfIp = subnet?.let { runCatching { LocalNetworkInfo.selfIp(it) }.getOrNull() }
        val networks = readNetworks(app)

        // VPN or a transient active-network change must not look like "no Wi-Fi".
        val wifiConnected = networks?.wifi == true ||
            runCatching { LocalNetworkInfo.isOnWifi(app) }.getOrDefault(false)

        // WifiInfo keeps the last network after Wi-Fi is turned off; showing
        // that stale SSID/signal is exactly what made the app look wrong.
        // Reading SSID/BSSID can throw SecurityException on some ROMs even
        // with the permission granted; a null simply means "redactado".
        val ssid = if (wifiConnected) {
            runCatching { sanitizeSsid(wifiInfo?.ssid) }.getOrNull()
        } else {
            null
        }
        val bssid = if (wifiConnected) {
            runCatching {
                wifiInfo?.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
            }.getOrNull()
        } else {
            null
        }
        val signalDbm = if (wifiConnected) {
            wifiInfo?.rssi?.takeIf { it != 0 && it > -127 }
        } else {
            null
        }
        val frequency = if (wifiConnected) wifiInfo?.frequency?.takeIf { it > 0 } else null

        return WifiStatistics(
            ssid = ssid,
            bssid = bssid,
            wifiConnected = wifiConnected,
            ssidHidden = wifiConnected && ssid == null,
            locationPermissionGranted = hasLocationPermission(app),
            locationServicesEnabled = isLocationEnabled(app),
            signalDbm = signalDbm,
            signalLevel = signalDbm?.let { signalLevelOf(it) },
            frequencyMhz = frequency,
            channel = frequency?.let { channelOf(it) },
            band = frequency?.let { bandOf(it) },
            linkSpeedMbps = if (wifiConnected) wifiInfo?.linkSpeed?.takeIf { it > 0 } else null,
            // API 30+ replaced the single linkSpeed with per-direction rates.
            rxLinkSpeedMbps = if (wifiConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wifiInfo?.rxLinkSpeedMbps?.takeIf { it > 0 }
            } else {
                null
            },
            txLinkSpeedMbps = if (wifiConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wifiInfo?.txLinkSpeedMbps?.takeIf { it > 0 }
            } else {
                null
            },
            wifiEnabled = runCatching { wifiManager?.isWifiEnabled }.getOrNull(),
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
            transport = networks?.transport,
            metered = networks?.metered,
            vpnActive = networks?.vpn,
            downstreamKbps = networks?.downstreamKbps,
            upstreamKbps = networks?.upstreamKbps,
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

    /** Coarse 0..4 signal level from RSSI in dBm (4 = excellent). */
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

    /**
     * Cleans the raw `WifiInfo.ssid`: strips quotes, rejects the redacted
     * `<unknown ssid>` sentinel and decodes the `0x…` hex form some OEM ROMs
     * (and hidden/UTF-8 SSIDs) return.
     */
    internal fun sanitizeSsid(raw: String?): String? {
        val trimmed = raw?.trim()?.removeSurrounding("\"")?.trim()
        if (trimmed.isNullOrEmpty()) return null
        if (trimmed.equals("<unknown ssid>", ignoreCase = true)) return null
        // "0x" alone is what a hidden network looks like on some ROMs: it is
        // not a name, so the UI must fall back to its "red oculta" state.
        if (trimmed.equals("0x", ignoreCase = true)) return null
        if (trimmed.length > 2 && trimmed.startsWith("0x", ignoreCase = true)) {
            val hex = trimmed.drop(2)
            if (hex.length % 2 == 0) {
                val bytes = hex.chunked(2).mapNotNull { pair -> pair.toIntOrNull(16)?.toByte() }
                if (bytes.size * 2 == hex.length) {
                    var payload = bytes
                    // Some ROMs prefix the payload with a 16-bit length.
                    if (payload.size >= 2) {
                        val declared = ((payload[0].toInt() and 0xFF) shl 8) or
                            (payload[1].toInt() and 0xFF)
                        if (declared == payload.size - 2) payload = payload.drop(2)
                    }
                    val decoded = runCatching {
                        String(payload.toByteArray(), Charsets.UTF_8)
                    }.getOrNull()
                        ?.trim()
                        ?.takeIf { text -> text.isNotEmpty() && text.all { it >= ' ' } }
                    if (decoded != null) return decoded
                }
            }
        }
        return trimmed
    }

    // ------------------------------------------------------------------
    // Guarded platform reads
    // ------------------------------------------------------------------

    /**
     * The live `WifiInfo`, when readable.
     *
     * On Android 10+ the `NetworkCapabilities` of the Wi-Fi network carry the
     * authoritative `WifiInfo` for that link; reading it first avoids the
     * deprecated `connectionInfo`, which may describe the last network the
     * phone used rather than the one it is on. Both paths degrade to null
     * (and the SSID stays redacted) when the Location permission is missing.
     */
    @Suppress("DEPRECATION") // connectionInfo is the only fallback below API 29
    private fun readWifiInfo(context: Context, wifiManager: WifiManager?): WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val viaCapabilities = runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? ConnectivityManager ?: return@runCatching null
                cm.allNetworks.firstNotNullOfOrNull { network ->
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        caps.transportInfo as? WifiInfo
                    } else {
                        null
                    }
                }
            }.getOrNull()
            if (viaCapabilities != null) return viaCapabilities
        }
        return try {
            wifiManager?.connectionInfo
        } catch (_: Exception) {
            null
        }
    }

    /** DHCP lease details; null on setups without DHCP (static IP). */
    @Suppress("DEPRECATION")
    private fun readDhcpInfo(wifiManager: WifiManager?): DhcpSnapshot? {
        return try {
            val dhcp = wifiManager?.dhcpInfo ?: return null
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

    /**
     * Transport, metered flag, VPN presence and reported link bandwidth.
     *
     * Deliberately inspects **all** networks: with a VPN up, the active network
     * is the VPN, but the underlying Wi-Fi network still exists and is the one
     * whose link speed and metered state matter.
     */
    private fun readNetworks(context: Context): NetworkSnapshot? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            null
        } else {
            val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val allCaps = cm.allNetworks.mapNotNull { network -> cm.getNetworkCapabilities(network) }
            val wifiCaps = allCaps.firstOrNull { caps ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
            val vpn = allCaps.any { caps -> caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            val chosen = wifiCaps ?: activeCaps
            NetworkSnapshot(
                transport = when {
                    wifiCaps != null -> Transport.Wifi
                    activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                        Transport.Cellular
                    activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ->
                        Transport.Ethernet
                    activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ->
                        Transport.Vpn
                    activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true ->
                        Transport.Bluetooth
                    activeCaps != null -> Transport.Other
                    else -> null
                },
                wifi = wifiCaps != null,
                metered = chosen?.let {
                    !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                },
                vpn = vpn,
                downstreamKbps = chosen?.linkDownstreamBandwidthKbps?.takeIf { it > 0 },
                upstreamKbps = chosen?.linkUpstreamBandwidthKbps?.takeIf { it > 0 },
            )
        }
    } catch (_: Exception) {
        null
    }

    /** True when the app may read the SSID (location permission granted). */
    internal fun hasLocationPermission(context: Context): Boolean = try {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /**
     * Whether the system location switch is on. The SSID and BSSID are
     * redacted when it is off, even if the permission is granted.
     */
    @Suppress("DEPRECATION")
    internal fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: Exception) {
            false
        }
    }

    private data class NetworkSnapshot(
        val transport: Transport?,
        val wifi: Boolean,
        val metered: Boolean?,
        val vpn: Boolean,
        val downstreamKbps: Int?,
        val upstreamKbps: Int?,
    )

    private data class DhcpSnapshot(
        val ip: String,
        val gateway: String,
        val dns1: String,
        val dns2: String,
        val server: String,
        val leaseSeconds: Int,
    )
}
