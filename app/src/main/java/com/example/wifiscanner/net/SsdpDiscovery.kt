package com.example.wifiscanner.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
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
 *    `urn:schemas-upnp-org:device:InternetGatewayDevice:1` ("un router").
 *  - `SERVER`     — the vendor's UPnP stack string.
 *  - `LOCATION`   — an HTTP URL to the device description. Fetching it (only
 *    when it points at a private LAN address) yields the real manufacturer,
 *    model and friendly name, which is richer than anything a MAC prefix can
 *    tell us.
 *
 * Discovery is strictly best-effort: any socket, interface, permission or
 * HTTP failure simply leaves a field null so the scan continues with its other
 * methods.
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
        /** Manufacturer read from the UPnP description (`<manufacturer>`). */
        val manufacturer: String? = null,
        /** Model read from the UPnP description (`<modelName>`). */
        val model: String? = null,
        /** Human name advertised by the device (`<friendlyName>`). */
        val friendlyName: String? = null,
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

            // Only after the socket is closed: the description fetches run in
            // parallel with short timeouts and must never delay the multicast
            // window or keep the lock held.
            enrichWithDescriptions(results)
            results
        }

    // ------------------------------------------------------------------
    // UPnP device description (manufacturer / model / friendly name)
    // ------------------------------------------------------------------

    /**
     * Fetches the UPnP description of every responder that advertised a local
     * `LOCATION`, in parallel and with a hard per-request timeout.
     *
     * Failures are ignored per device: a responder that blocks the HTTP port
     * still contributes its ST/SERVER data.
     */
    private suspend fun enrichWithDescriptions(results: MutableMap<String, SsdpResponse>) {
        val targets = results.values.filter { response ->
            response.location?.let { isLocalDescriptionUrl(it) } == true
        }
        if (targets.isEmpty()) return

        val limit = Semaphore(DESCRIPTION_CONCURRENCY)
        coroutineScope {
            targets.map { response ->
                async(Dispatchers.IO) {
                    limit.withPermit {
                        val details = fetchDescription(response.location.orEmpty()) ?: return@withPermit
                        // Several descriptions are fetched in parallel, so the
                        // shared map is only touched inside the lock.
                        synchronized(results) {
                            val current = results[response.ip] ?: return@withPermit
                            results[response.ip] = current.copy(
                                manufacturer = current.manufacturer ?: details.manufacturer,
                                model = details.model,
                                friendlyName = current.friendlyName ?: details.friendlyName,
                            )
                        }
                    }
                }
            }.forEach { it.await() }
        }
    }

    /** Fields decoded from a UPnP device description document. */
    internal data class SsdpDescription(
        val manufacturer: String? = null,
        val model: String? = null,
        val friendlyName: String? = null,
    )

    /**
     * True when [location] is an `http://` URL pointing at a private IPv4
     * address. This is what keeps the cleartext request inside the LAN.
     */
    private fun isLocalDescriptionUrl(location: String): Boolean {
        return try {
            val url = URL(location)
            if (!url.protocol.equals("http", ignoreCase = true)) return false
            val value = LocalNetworkInfo.ipv4ToInt(url.host) ?: return false
            val first = (value shr 24) and 0xFF
            val second = (value shr 16) and 0xFF
            first == 10L ||
                (first == 100L && second in 64L..127L) ||
                (first == 172L && second in 16L..31L) ||
                (first == 192L && second == 168L)
        } catch (_: Exception) {
            false
        }
    }

    /** GETs and parses one description document; null on any failure. */
    private fun fetchDescription(location: String): SsdpDescription? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(location)
            val active = (url.openConnection() as? HttpURLConnection) ?: return null
            connection = active
            active.connectTimeout = DESCRIPTION_TIMEOUT_MILLIS
            active.readTimeout = DESCRIPTION_TIMEOUT_MILLIS
            // No redirects: a LAN device must not be able to point the app at
            // an internet URL. A 3xx simply leaves the fields empty.
            active.instanceFollowRedirects = false
            active.requestMethod = "GET"
            active.setRequestProperty("Connection", "close")
            active.setRequestProperty("User-Agent", "WifiScanner/1.0 UPnP/1.1")
            if (active.responseCode !in 200..299) return null
            val body = readCapped(active.inputStream, MAX_DESCRIPTION_BYTES)
            parseDescription(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
                // Disconnecting a broken connection is best-effort.
            }
        }
    }

    /** Reads at most [limit] bytes so a hostile device cannot exhaust memory. */
    private fun readCapped(stream: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(1024)
        val buffer = ByteArray(4_096)
        try {
            stream.use { input ->
                while (out.size() < limit) {
                    val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
        } catch (_: Exception) {
            // Keep whatever arrived before the connection dropped.
        }
        return out.toByteArray()
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
        private const val DESCRIPTION_TIMEOUT_MILLIS = 900
        private const val DESCRIPTION_CONCURRENCY = 6
        private const val MAX_DESCRIPTION_BYTES = 32 * 1024

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
         * Extracts the interesting fields from a UPnP device description.
         * Pure function (internal for tests); tolerant of namespaces, extra
         * attributes and entity escapes.
         */
        internal fun parseDescription(xml: String): SsdpDescription? {
            if (xml.isBlank()) return null
            val manufacturer = tagValue(xml, "manufacturer")
            val model = tagValue(xml, "modelName")
                ?: tagValue(xml, "modelNumber")
                ?: tagValue(xml, "model")
                ?: tagValue(xml, "modelDescription")
            val friendlyName = tagValue(xml, "friendlyName")
            if (manufacturer == null && model == null && friendlyName == null) return null
            return SsdpDescription(
                manufacturer = manufacturer,
                model = model,
                friendlyName = friendlyName,
            )
        }

        /** First value of `<name>…</name>`, decoded and trimmed. */
        private fun tagValue(xml: String, name: String): String? {
            val match = Regex(
                "<$name(?:\\s[^>]*)?>(.*?)</$name>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            ).find(xml) ?: return null
            return decodeEntities(match.groupValues[1]).trim().takeIf { it.isNotEmpty() }
        }

        /** Minimal XML entity decoding for the handful of fields we display. */
        private fun decodeEntities(value: String): String = value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

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
