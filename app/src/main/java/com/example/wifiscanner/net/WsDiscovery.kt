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
import kotlin.random.Random

/**
 * WS-Discovery — the protocol behind "Network" in the Windows Explorer.
 *
 * This is literally how Windows finds devices on a LAN: a SOAP `Probe` sent to
 * the `239.255.255.250:3702` multicast group, answered by every WS-Discovery
 * device with a `ProbeMatch` describing itself. It is the discovery path used
 * by modern Windows PCs, network printers/scanners, ONVIF cameras, NAS boxes
 * and IP phones — exactly the devices that answer nothing over ICMP.
 *
 * The match carries everything needed for recognition:
 *
 *  - `XAddrs`   — the device's own URLs, which embed its IPv4 address;
 *  - `Types`    — e.g. `wsdp:Device`, `wprt:PrintDeviceType`, `pub:Computer`;
 *  - `Scopes`   — e.g. `.../NetworkVideoTransmitter` for cameras;
 *  - `EndpointReference/Address` — a stable `urn:uuid:...` identity.
 *
 * Like every other strategy here it is strictly best-effort: any socket or
 * parser failure returns nothing found, never an error.
 */
class WsDiscovery(context: Context) {

    private val appContext = context.applicationContext

    /** One WS-Discovery device that answered our Probe. */
    data class WsdResponse(
        val ip: String,
        /** Stable `urn:uuid:...` identity, when advertised. */
        val endpoint: String? = null,
        val types: List<String> = emptyList(),
        val scopes: List<String> = emptyList(),
        val xaddrs: List<String> = emptyList(),
    ) {
        /**
         * Short device class hint from the advertised types/scopes, e.g.
         * `"Printer"`, `"Camera"`, `"Computer"`; null when nothing matches.
         */
        val kind: String? get() = WsdProtocol.describeKind(types + scopes)
    }

    /**
     * Sends one Probe and collects `ProbeMatch` replies for [timeoutMillis].
     *
     * @return responses keyed by the device's IPv4 address (taken from the
     *         reply's `XAddrs` when possible, else from the packet source).
     */
    suspend fun discover(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Map<String, WsdResponse> =
        withContext(Dispatchers.IO) {
            val results = LinkedHashMap<String, WsdResponse>()
            val messageId = "urn:uuid:" + WsdProtocol.randomUuid()
            val payload = WsdProtocol.buildProbe(messageId).toByteArray(Charsets.UTF_8)

            val lock = acquireMulticastLock()
            var socket: MulticastSocket? = null
            try {
                val group = InetAddress.getByName(WSD_ADDRESS)
                val active = MulticastSocket(null)
                socket = active
                active.reuseAddress = true
                active.soTimeout = RECEIVE_TIMEOUT_MILLIS
                active.bind(InetSocketAddress(0))

                joinGroup(active, group)
                try {
                    active.timeToLive = 2
                } catch (_: Exception) {
                    // TTL is a hint; link-local multicast works without it.
                }

                val packet = DatagramPacket(payload, payload.size, InetSocketAddress(group, WSD_PORT))
                repeat(PROBE_SENDS) {
                    try {
                        active.send(packet)
                    } catch (_: Exception) {
                        // Retry with the next send.
                    }
                }

                val deadline = System.currentTimeMillis() + timeoutMillis
                val buffer = ByteArray(MAX_DATAGRAM)
                while (System.currentTimeMillis() < deadline) {
                    val reply = DatagramPacket(buffer, buffer.size)
                    try {
                        active.receive(reply)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    val sourceIp = reply.address?.hostAddress ?: continue
                    val text = String(reply.data, 0, reply.length, Charsets.UTF_8)
                    val parsed = WsdProtocol.parseProbeMatch(text) ?: continue
                    // Ignore replies that belong to somebody else's session
                    // (a `RelatesTo` that is not our MessageID).
                    if (!parsed.respondsTo(messageId)) continue
                    // Prefer the address the device advertises itself.
                    val ip = parsed.xaddrs.firstNotNullOfOrNull { WsdProtocol.ipFromUri(it) }
                        ?: sourceIp
                    if (results.containsKey(ip)) continue
                    results[ip] = WsdResponse(
                        ip = ip,
                        endpoint = parsed.endpoint,
                        types = parsed.types,
                        scopes = parsed.scopes,
                        xaddrs = parsed.xaddrs,
                    )
                }
            } catch (_: Exception) {
                // No multicast support / no permission: report nothing found.
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                    // Closing a broken socket must not hide the results.
                }
                releaseMulticastLock(lock)
            }
            results
        }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private fun joinGroup(socket: MulticastSocket, group: InetAddress) {
        val iface = lanInterface()
        var joined = false
        if (iface != null) {
            joined = try {
                socket.joinGroup(InetSocketAddress(group, WSD_PORT), iface)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!joined) {
            @Suppress("DEPRECATION")
            try {
                socket.joinGroup(group)
            } catch (_: Exception) {
                // Unicast ProbeMatch replies do not need the join to succeed.
            }
        }
    }

    private fun lanInterface(): NetworkInterface? = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        interfaces.firstOrNull { iface -> isUpWithIpv4(iface) && isWifiLike(iface.name) }
            ?: interfaces.firstOrNull { iface -> isUpWithIpv4(iface) && !isMobileOrVpn(iface.name) }
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

    /** Multicast lock: without it the Wi-Fi driver filters multicast frames. */
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
            // Nothing we can do here.
        }
    }

    /**
     * SOAP message construction and a minimal, dependency-free reader.
     *
     * A full XML parser is unnecessary (and heavy) here: WS-Discovery replies
     * are a handful of well-known elements, so we locate them by local tag
     * name, namespace prefix or not. Pure string/regex work keeps it fully
     * unit-testable and immune to parser-specific behaviour on odd ROMs.
     */
    internal object WsdProtocol {

        /** Namespaces of the WS-Discovery 2005/04 dialect used by Windows. */
        const val PROBE_ACTION = "http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe"
        const val DISCOVERY_TO = "urn:schemas-xmlsoap-org:ws:2005:04:discovery"

        private const val PROBE_TEMPLATE =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<soap:Envelope" +
                " xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"" +
                " xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\"" +
                " xmlns:wsd=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">" +
                "<soap:Header>" +
                "<wsa:To>__TO__</wsa:To>" +
                "<wsa:Action>__ACTION__</wsa:Action>" +
                "<wsa:MessageID>__MESSAGE_ID__</wsa:MessageID>" +
                "</soap:Header>" +
                "<soap:Body><wsd:Probe/></soap:Body>" +
                "</soap:Envelope>"

        /** A decoded `ProbeMatch` (or any SOAP message with these parts). */
        internal data class ProbeMatch(
            val endpoint: String?,
            val types: List<String>,
            val scopes: List<String>,
            val xaddrs: List<String>,
            val messageId: String?,
            val relatesTo: String?,
        ) {
            /** True when this reply belongs to the Probe we sent. */
            fun respondsTo(id: String): Boolean = relatesTo == null || relatesTo == id
        }

        /** Builds the Probe SOAP envelope for [messageId]. */
        internal fun buildProbe(messageId: String): String = PROBE_TEMPLATE
            .replace("__TO__", DISCOVERY_TO)
            .replace("__ACTION__", PROBE_ACTION)
            .replace("__MESSAGE_ID__", messageId)

        /**
         * Decodes a reply. Returns null when the payload carries no
         * WS-Discovery content at all (foreign datagrams, junk).
         */
        internal fun parseProbeMatch(xml: String): ProbeMatch? {
            if (!xml.contains("Envelope", ignoreCase = true)) return null
            val xaddrs = elements(xml, "XAddrs")
                .flatMap { it.split(Regex("\\s+")) }
                .filter { it.isNotEmpty() }
            val types = elements(xml, "Types").flatMap { it.split(Regex("\\s+")) }.filter { it.isNotEmpty() }
            val scopes = elements(xml, "Scopes").flatMap { it.split(Regex("\\s+")) }.filter { it.isNotEmpty() }
            val endpoint = elements(xml, "Address").firstOrNull()
            val messageId = elements(xml, "MessageID").firstOrNull()
            val relatesTo = elements(xml, "RelatesTo").firstOrNull()

            if (xaddrs.isEmpty() && types.isEmpty() && scopes.isEmpty() && endpoint == null) return null
            return ProbeMatch(
                endpoint = endpoint,
                types = types,
                scopes = scopes,
                xaddrs = xaddrs,
                messageId = messageId,
                relatesTo = relatesTo,
            )
        }

        /**
         * Text of every `<...:localName>` element, namespace prefix optional.
         * Regex-based on purpose: see the class note above.
         */
        internal fun elements(xml: String, localName: String): List<String> {
            val name = Regex.escape(localName)
            val pattern = Regex(
                "<(?:[A-Za-z0-9_.-]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_.-]+:)?$name>",
                RegexOption.DOT_MATCHES_ALL,
            )
            return pattern.findAll(xml)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }

        /** Extracts the host of an `http://192.168.1.5:5357/x` style URI. */
        internal fun ipFromUri(uri: String): String? {
            val trimmed = uri.trim()
            val schemeEnd = trimmed.indexOf("//")
            val rest = if (schemeEnd >= 0) trimmed.substring(schemeEnd + 2) else trimmed
            val hostEnd = rest.indexOfFirst { it == '/' || it == ':' || it == '?' }
            val host = if (hostEnd >= 0) rest.substring(0, hostEnd) else rest
            return host.takeIf { LocalNetworkInfo.ipv4ToInt(it) != null }
        }

        /**
         * Maps the advertised types/scopes to a short class name using the
         * vocabulary the device classifier understands.
         */
        internal fun describeKind(values: List<String>): String? {
            if (values.isEmpty()) return null
            val joined = values.joinToString(" ").lowercase()
            return when {
                joined.contains("print") || joined.contains("scan") -> "Printer"
                joined.contains("networkvideotransmitter") || joined.contains("onvif") -> "Camera"
                joined.contains("media") -> "Media player"
                joined.contains("computer") || joined.contains("workstation") -> "Computer"
                joined.contains("nas") || joined.contains("storage") -> "NAS"
                joined.contains("device") -> "Device"
                else -> null
            }
        }

        /** Random UUID for the MessageID (no Android dependency). */
        internal fun randomUuid(): String {
            val bytes = ByteArray(16)
            Random.nextBytes(bytes)
            val hex = "0123456789abcdef"
            val out = StringBuilder(36)
            for (index in bytes.indices) {
                if (index == 4 || index == 6 || index == 8 || index == 10) out.append('-')
                out.append(hex[(bytes[index].toInt() shr 4) and 0x0F])
                out.append(hex[bytes[index].toInt() and 0x0F])
            }
            return out.toString()
        }
    }

    companion object {
        const val WSD_ADDRESS = "239.255.255.250"
        const val WSD_PORT = 3702

        private const val DEFAULT_TIMEOUT_MILLIS = 3_000L
        private const val RECEIVE_TIMEOUT_MILLIS = 700
        private const val MAX_DATAGRAM = 16_384
        private const val PROBE_SENDS = 2
        private const val LOCK_TAG = "wifiscanner-wsd"

        private val MOBILE_OR_VPN_PREFIXES =
            listOf("rmnet", "ccmni", "pdp", "tun", "tap", "ppp", "clat")
    }
}
