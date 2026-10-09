package com.example.wifiscanner.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * SSDP / UPnP discovery — the main "works even when ARP is hidden" strategy.
 *
 * Every router, smart TV, console, printer, NAS, speaker and most IoT devices
 * answers an SSDP `M-SEARCH` multicast on `239.255.255.250:1900`. Unlike ICMP
 * (blocked on many hosts) or a TCP port scan (useless against hosts with no
 * open ports), a *multicast* request makes the devices announce themselves,
 * and the reply arrives as a unicast datagram we can read without root.
 *
 * The reply also carries recognition data we can show without a MAC address:
 *
 *  - `ST` / `USN` — the device/service URN, e.g.
 *    `urn:schemas-upnp-org:device:InternetGatewayDevice:1` ("a router").
 *  - `SERVER`     — the vendor's UPnP stack string.
 *
 * Discovery is strictly best-effort: any socket, interface or permission
 * failure yields an empty map so the scan continues with its other methods.
 */
class SsdpDiscovery(context: Context) {

    private val appContext = context.applicationContext

    /** One device that answered an SSDP M-SEARCH. */
    data class SsdpResponse(
        val ip: String,
        val st: String? = null,
        val usn: String? = null,
        val server: String? = null,
        val location: String? = null,
    ) {
        /**
         * Device class parsed out of the ST/USN URN, e.g.
         * `"InternetGatewayDevice"` or `"MediaRenderer"`; null for generic
         * `upnp:rootdevice` replies.
         */
        val kind: String?
            get() = SsdpDiscovery.deviceKind(st) ?: SsdpDiscovery.deviceKind(usn)
    }

    /**
     * Broadcasts one M-SEARCH and collects unicast replies for up to
     * [timeoutMillis]. Never throws.
     *
     * @return responses keyed by the responder's IP address.
     */
    suspend fun discover(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Map<String, SsdpResponse> =
        withContext(Dispatchers.IO) {
            val results = LinkedHashMap<String, SsdpResponse>()
            val lock = acquireMulticastLock()
            var socket: MulticastSocket? = null
            try {
                val group = InetAddress.getByName(SSDP_ADDRESS)
                // An ephemeral local port: binding 1900 needs privileges and
                // UPnP replies are unicast back to our source port anyway.
                val active = MulticastSocket(null)
                socket = active // registered for cleanup before anything can fail
                active.reuseAddress = true
                active.soTimeout = RECEIVE_TIMEOUT_MILLIS
                active.bind(InetSocketAddress(0))

                joinGroup(active, group)
                try {
                    active.timeToLive = 2
                } catch (_: Exception) {
                    // TTL is only a hint; link-local multicast works without it.
                }
                sendSearch(active, group)

                val deadline = System.currentTimeMillis() + timeoutMillis
                val buffer = ByteArray(MAX_DATAGRAM)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        active.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue // no reply in this slice; keep waiting until deadline
                    } catch (_: Exception) {
                        break
                    }
                    val ip = packet.address?.hostAddress ?: continue
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val response = parseResponse(text, ip) ?: continue
                    val existing = results[ip]
                    // Prefer an answer that identifies the device class.
                    if (existing == null || (existing.kind == null && response.kind != null)) {
                        results[ip] = response
                    }
                }
            } catch (_: Exception) {
                // No multicast support / no permission: report nothing found.
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                    // Closing a broken socket must not mask the results.
                }
                releaseMulticastLock(lock)
            }
            results
        }

    // ------------------------------------------------------------------
    // Multicast plumbing
    // ------------------------------------------------------------------

    /**
     * Joins the SSDP group on the Wi-Fi interface when we can identify one;
     * falls back to the default route. Joining lets us also see proactive
     * `NOTIFY` announcements made while we are listening.
     */
    private fun joinGroup(socket: MulticastSocket, group: InetAddress) {
        val iface = wifiInterface()
        var joinedOnIface = false
        if (iface != null) {
            joinedOnIface = try {
                socket.joinGroup(InetSocketAddress(group, SSDP_PORT), iface)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!joinedOnIface) joinDefaultGroup(socket, group)
    }

    @Suppress("DEPRECATION")
    private fun joinDefaultGroup(socket: MulticastSocket, group: InetAddress) {
        try {
            socket.joinGroup(group)
        } catch (_: Exception) {
            // Receiving replies never depends on the join succeeding: UPnP
            // replies are unicast back to our source port anyway.
        }
    }

    /** Sends the M-SEARCH probe (twice — multicast on a busy link drops packets). */
    private fun sendSearch(socket: MulticastSocket, group: InetAddress) {
        val payload = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 1\r\n")
            append("ST: ssdp:all\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)

        val packet = DatagramPacket(payload, payload.size, InetSocketAddress(group, SSDP_PORT))
        repeat(SEARCH_SENDS) {
            try {
                socket.send(packet)
            } catch (_: Exception) {
                // Try the next send; a failure here is not fatal.
            }
        }
    }

    /** First up, non-loopback interface with an IPv4 address, wlan-like first. */
    private fun wifiInterface(): NetworkInterface? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .firstOrNull { iface -> isUpWithIpv4(iface) && isWifiLike(iface.name) }
            ?: NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .firstOrNull { iface -> isUpWithIpv4(iface) && !isMobileOrVpn(iface.name) }
    } catch (_: Exception) {
        null
    }

    private fun isUpWithIpv4(iface: NetworkInterface): Boolean = try {
        iface.isUp && !iface.isLoopback &&
            iface.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
    } catch (_: Exception) {
        false
    }

    private fun isWifiLike(name: String): Boolean =
        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")

    private fun isMobileOrVpn(name: String): Boolean =
        MOBILE_OR_VPN_PREFIXES.any { name.startsWith(it) }

    /**
     * Holds a Wi-Fi multicast lock while listening: without it the Wi-Fi
     * driver filters multicast frames and we would hear nothing.
     */
    private fun acquireMulticastLock(): WifiManager.MulticastLock? = try {
        (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock(LOCK_TAG)
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    } catch (_: Exception) {
        null
    }

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (_: Exception) {
            // Nothing we can do; the lock is reference-count-free anyway.
        }
    }

    companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900

        private const val DEFAULT_TIMEOUT_MILLIS = 2_500L
        private const val RECEIVE_TIMEOUT_MILLIS = 700
        private const val MAX_DATAGRAM = 8_192
        private const val SEARCH_SENDS = 2
        private const val LOCK_TAG = "wifiscanner-ssdp"

        private val MOBILE_OR_VPN_PREFIXES =
            listOf("rmnet", "ccmni", "pdp", "tun", "tap", "ppp", "clat")

        /** `...:device:MediaRenderer:1` at the end of a ST/USN URN. */
        private val URN_KIND = Regex(":([A-Za-z][A-Za-z0-9-]*):\\d+$")

        /**
         * Parses one SSDP datagram (a `200 OK` reply or a `NOTIFY`).
         *
         * @param fromIp the datagram's source address (the device's IP).
         * @return the response, or null when the payload has no headers.
         */
        internal fun parseResponse(text: String, fromIp: String): SsdpResponse? {
            val headers = parseHeaders(text)
            if (headers.isEmpty()) return null
            return SsdpResponse(
                ip = fromIp,
                st = headers["st"],
                usn = headers["usn"],
                server = headers["server"],
                location = headers["location"],
            )
        }

        /**
         * Lower-cased "key -> value" map of an SSDP message. The leading
         * status/request line and anything after the blank line are ignored.
         */
        internal fun parseHeaders(text: String): Map<String, String> {
            val headers = LinkedHashMap<String, String>(8)
            for ((index, rawLine) in text.lineSequence().withIndex()) {
                if (index == 0) continue // "HTTP/1.1 200 OK" / "NOTIFY * HTTP/1.1"
                val line = rawLine.trim()
                if (line.isEmpty()) break // end of the header block
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                val value = line.substring(separator + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty() && !headers.containsKey(key)) {
                    headers[key] = value
                }
            }
            return headers
        }

        /**
         * Extracts the device class from a UPnP URN:
         * `"urn:schemas-upnp-org:device:InternetGatewayDevice:1"` ->
         * `"InternetGatewayDevice"`. Returns null for generic URNs such as
         * `upnp:rootdevice`.
         */
        internal fun deviceKind(urn: String?): String? {
            if (urn.isNullOrBlank()) return null
            val lastUrn = urn.substringAfterLast("::", urn)
            val match = URN_KIND.find(lastUrn) ?: return null
            return match.groupValues[1].takeIf { it.isNotBlank() }
        }
    }
}
