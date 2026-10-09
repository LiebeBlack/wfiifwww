package com.example.wifiscanner.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Raw multicast-DNS (mDNS / DNS-SD) discovery — the "belt" for the platform
 * `NsdManager` "braces".
 *
 * [NsdManager] is convenient but unreliable across ROMs (it can be missing,
 * rate-limited, or silently drop service types). Speaking DNS-SD directly over
 * multicast gives us the same information with no platform dependency:
 *
 *  - **service types** per host (`_googlecast._tcp`, `_ipp._tcp`, ...), which
 *    feed device classification;
 *  - **hostnames** (`Chromecast-abc.local` -> `Chromecast-abc`);
 *  - **model strings** from TXT records (`md=Chromecast Ultra`).
 *
 * Queries are sent with the unicast-response (QU) bit set, so responders reply
 * straight to our socket even when we could not bind port 5353. Everything is
 * best-effort: any failure yields an empty map, never an exception.
 */
class MdnsDiscovery(context: Context) {

    private val appContext = context.applicationContext

    /** What the responders collectively told us about one address. */
    data class MdnsAnswer(
        val ip: String,
        val hostname: String? = null,
        val services: List<String> = emptyList(),
        val models: List<String> = emptyList(),
    )

    /**
     * Sends one DNS-SD query set and listens for answers.
     *
     * @return answers keyed by the device's IPv4 address.
     */
    suspend fun discover(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Map<String, MdnsAnswer> =
        withContext(Dispatchers.IO) {
            val hostToIp = HashMap<String, String>()          // "chromecast-abc" -> ip
            val hostServices = HashMap<String, MutableSet<String>>()
            val hostModels = HashMap<String, MutableSet<String>>()
            val byIp = HashMap<String, MutableAnswer>()

            fun answerFor(ip: String): MutableAnswer =
                byIp.getOrPut(ip) { MutableAnswer(ip) }

            val lock = acquireMulticastLock()
            var socket: MulticastSocket? = null
            try {
                val group = InetAddress.getByName(MDNS_ADDRESS)
                val active = MulticastSocket(null)
                socket = active
                active.reuseAddress = true
                active.soTimeout = RECEIVE_TIMEOUT_MILLIS
                // 5353 is the canonical port; fall back to an ephemeral one
                // (unicast answers still reach us).
                try {
                    active.bind(InetSocketAddress(MDNS_PORT))
                } catch (_: Exception) {
                    active.bind(InetSocketAddress(0))
                }
                joinGroup(active, group)
                try {
                    active.timeToLive = 2
                } catch (_: Exception) {
                    // Hint only; link-local multicast works without it.
                }
                sendQueries(active, group)

                val deadline = System.currentTimeMillis() + timeoutMillis
                val buffer = ByteArray(MAX_DATAGRAM)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        active.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    val sourceIp = packet.address?.hostAddress ?: continue
                    if (!isIpv4(sourceIp)) continue
                    val records = MdnsProtocol.parseMessage(packet.data, packet.length)
                    for (record in records) {
                        when (record.type) {
                            MdnsProtocol.TYPE_A -> {
                                hostToIp[record.name] = record.value
                                val existing = answerFor(record.value)
                                if (existing.hostname == null) {
                                    existing.hostname = MdnsProtocol.shortName(record.name)
                                }
                            }

                            MdnsProtocol.TYPE_SRV -> {
                                val hostKey = record.value
                                val type = MdnsProtocol.serviceTypeOf(record.name) ?: continue
                                hostServices.getOrPut(hostKey) { mutableSetOf() }.add(type)
                            }

                            MdnsProtocol.TYPE_PTR -> {
                                // Owner is the service type; attribute it to the
                                // responder, which is the device that announced it.
                                MdnsProtocol.serviceTypeOf(record.name)?.let { type ->
                                    answerFor(sourceIp).services += type
                                }
                            }

                            MdnsProtocol.TYPE_TXT -> {
                                for (model in MdnsProtocol.modelsOf(record.value)) {
                                    hostModels.getOrPut(record.name) { mutableSetOf() }.add(model)
                                    answerFor(sourceIp).models += model
                                }
                            }
                        }
                    }
                }

                // Resolve SRV/TXT data that was keyed by hostname into IPs.
                for ((host, services) in hostServices) {
                    val ip = hostToIp[host] ?: continue
                    answerFor(ip).services += services
                }
                for ((host, models) in hostModels) {
                    val ip = hostToIp[host] ?: continue
                    answerFor(ip).models += models
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

            byIp.mapValues { (_, answer) -> answer.freeze() }
        }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private fun sendQueries(socket: MulticastSocket, group: InetAddress) {
        val payload = MdnsProtocol.buildQuery(QUERY_NAMES)
        val packet = DatagramPacket(payload, payload.size, InetSocketAddress(group, MDNS_PORT))
        repeat(QUERY_SENDS) {
            try {
                socket.send(packet)
            } catch (_: Exception) {
                // A dropped query is retried by the next send.
            }
        }
    }

    private fun joinGroup(socket: MulticastSocket, group: InetAddress) {
        val iface = lanInterface()
        var joined = false
        if (iface != null) {
            joined = try {
                socket.joinGroup(InetSocketAddress(group, MDNS_PORT), iface)
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
                // Unicast answers do not need the join to succeed.
            }
        }
    }

    /** First up, non-loopback interface with an IPv4 address, wlan-like first. */
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

    private fun isIpv4(value: String): Boolean = try {
        InetAddress.getByName(value) is Inet4Address
    } catch (_: Exception) {
        false
    }

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

    /** Mutable accumulator for one IP; frozen into [MdnsAnswer] at the end. */
    private class MutableAnswer(val ip: String) {
        var hostname: String? = null
        val services = LinkedHashSet<String>()
        val models = LinkedHashSet<String>()

        fun freeze() = MdnsAnswer(
            ip = ip,
            hostname = hostname,
            services = services.toList(),
            models = models.toList(),
        )
    }

    companion object {
        const val MDNS_ADDRESS = "224.0.0.251"
        const val MDNS_PORT = 5353

        private const val DEFAULT_TIMEOUT_MILLIS = 2_500L
        private const val RECEIVE_TIMEOUT_MILLIS = 700
        private const val MAX_DATAGRAM = 9_000
        private const val QUERY_SENDS = 2
        private const val LOCK_TAG = "wifiscanner-mdns"

        private val MOBILE_OR_VPN_PREFIXES =
            listOf("rmnet", "ccmni", "pdp", "tun", "tap", "ppp", "clat")

        /** Service types worth asking about; responders answer what they run. */
        private val QUERY_NAMES = listOf(
            "_services._dns-sd._udp.local", // enumerates every service type
            "_googlecast._tcp.local",
            "_airplay._tcp.local",
            "_raop._tcp.local",
            "_spotify-connect._tcp.local",
            "_sonos._tcp.local",
            "_smb._tcp.local",
            "_workstation._tcp.local",
            "_afpovertcp._tcp.local",
            "_ipp._tcp.local",
            "_printer._tcp.local",
            "_http._tcp.local",
            "_homekit._tcp.local",
            "_hap._tcp.local",
            "_rtsp._tcp.local",
        )
    }

    /**
     * A self-contained DNS message reader/writer.
     *
     * Only what DNS-SD needs is implemented: name decoding (with compression
     * pointers), A / PTR / SRV / TXT records, and query construction. Pure
     * functions on byte arrays, so the whole parser is unit-testable.
     */
    internal object MdnsProtocol {

        const val TYPE_A = 1
        const val TYPE_PTR = 12
        const val TYPE_TXT = 16
        const val TYPE_SRV = 33

        /** Stops a malformed loop of compression pointers. */
        private const val MAX_POINTER_HOPS = 16

        /** One resource record, decoded into a string payload. */
        internal data class Record(
            /** Owner name, e.g. `_googlecast._tcp.local` or `Chromecast-abc.local`. */
            val name: String,
            val type: Int,
            /** A record: dotted IP. PTR/SRV: target name. TXT: joined text. */
            val value: String,
            /** SRV target port (0 for every other record type). */
            val port: Int = 0,
        )

        /**
         * Decodes a (possibly compressed) DNS name starting at [start].
         *
         * @return the dotted name (lower-cased) and the offset just past the
         *         name *as encoded at [start]* — after a pointer, that is the
         *         offset following the pointer, not the target.
         */
        internal fun readName(data: ByteArray, start: Int): Pair<String, Int> {
            val labels = ArrayList<String>(4)
            var offset = start
            var afterFirstPointer = -1
            var hops = 0
            while (offset in data.indices) {
                val length = data[offset].toInt() and 0xFF
                if (length == 0) {
                    offset += 1
                    break
                }
                if (length and 0xC0 == 0xC0) { // 11xxxxxx = compression pointer
                    if (offset + 1 >= data.size) break
                    val target = ((length and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    if (afterFirstPointer < 0) afterFirstPointer = offset + 2
                    offset = target
                    hops += 1
                    if (hops > MAX_POINTER_HOPS) break
                    continue
                }
                val next = offset + 1 + length
                if (next > data.size) break
                labels.add(String(data, offset + 1, length, Charsets.UTF_8))
                offset = next
            }
            val name = labels.joinToString(".").lowercase()
            val resume = if (afterFirstPointer >= 0) afterFirstPointer else offset
            return name to resume
        }

        /**
         * Parses the answer/authority/additional sections of an mDNS message.
         *
         * A truncated or malformed packet returns whatever was decoded before
         * the problem — a hostile or broken responder must never throw here.
         */
        internal fun parseMessage(data: ByteArray, length: Int): List<Record> {
            if (length < 12) return emptyList()
            val answers = readU16(data, 6)
            val authorities = readU16(data, 8)
            val additionals = readU16(data, 10)
            val records = ArrayList<Record>(answers + authorities + additionals)

            var offset = skipQuestions(data, 12, readU16(data, 4), length)
            repeat(answers + authorities + additionals) {
                val (name, afterName) = readName(data, offset)
                if (name.isEmpty() || afterName + 10 > length) return records
                val type = readU16(data, afterName)
                val dataLength = readU16(data, afterName + 8)
                val dataStart = afterName + 10
                val dataEnd = dataStart + dataLength
                if (dataEnd > length) return records

                when (type) {
                    TYPE_A -> if (dataLength == 4) {
                        records.add(Record(name, type, ipv4(data, dataStart)))
                    }

                    TYPE_PTR -> records.add(
                        Record(name, type, readName(data, dataStart).first)
                    )

                    TYPE_SRV -> if (dataLength >= 7) {
                        val port = readU16(data, dataStart + 4)
                        val target = readName(data, dataStart + 6).first
                        if (target.isNotEmpty()) records.add(Record(name, type, target, port))
                    }

                    TYPE_TXT -> records.add(
                        Record(name, type, readTxt(data, dataStart, dataLength))
                    )
                }
                offset = dataEnd
            }
            return records
        }

        /** Skips the question section; returns the first offset past it. */
        private fun skipQuestions(data: ByteArray, start: Int, count: Int, length: Int): Int {
            var offset = start
            repeat(count) {
                val (_, afterName) = readName(data, offset)
                offset = afterName + 4
                if (offset > length) offset = length
            }
            return offset
        }

        /**
         * Builds a query for [names]. The QU bit (0x8000) in the class field
         * asks responders to answer by unicast, which works even when we could
         * not bind the canonical mDNS port.
         */
        internal fun buildQuery(names: List<String>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0); out.write(0) // transaction id: 0 for mDNS
            out.write(0); out.write(0) // flags: standard query
            writeU16(out, names.size)
            writeU16(out, 0)
            writeU16(out, 0)
            writeU16(out, 0)
            for (name in names) {
                for (label in name.split('.')) {
                    val bytes = label.toByteArray(Charsets.UTF_8)
                    if (bytes.isEmpty() || bytes.size > 63) continue
                    out.write(bytes.size)
                    out.write(bytes, 0, bytes.size)
                }
                out.write(0)
                writeU16(out, TYPE_PTR)
                writeU16(out, 0x8001) // class IN + unicast response requested
            }
            return out.toByteArray()
        }

        /**
         * `"Chromecast-abc._googlecast._tcp.local"` -> `"_googlecast._tcp"`.
         * Returns null when the name carries no service type.
         */
        internal fun serviceTypeOf(recordName: String): String? {
            val labels = recordName.trimEnd('.').removeSuffix(".local")
                .split('.').filter { it.isNotEmpty() }
            val start = labels.indexOfFirst { it.startsWith("_") }
            if (start < 0 || start + 1 >= labels.size) return null
            return labels.subList(start, labels.size).joinToString(".")
        }

        /** `"Chromecast-abc.local"` -> `"Chromecast-abc"`; null when empty. */
        internal fun shortName(recordName: String): String? =
            recordName.trimEnd('.').removeSuffix(".local").takeIf { it.isNotBlank() }

        /**
         * Extracts `md=` / `model=` values from a TXT record payload, which is
         * how DNS-SD services advertise their model.
         */
        internal fun modelsOf(txt: String): List<String> {
            if (txt.isBlank()) return emptyList()
            val models = ArrayList<String>(2)
            for (pair in txt.split(',')) {
                val separator = pair.indexOf('=')
                if (separator <= 0) continue
                val key = pair.substring(0, separator).trim().lowercase()
                val value = pair.substring(separator + 1).trim()
                if (key in MODEL_KEYS && value.isNotEmpty()) models.add(value)
            }
            return models
        }

        private val MODEL_KEYS = setOf("md", "model")

        private fun readTxt(data: ByteArray, start: Int, length: Int): String {
            val out = StringBuilder()
            var index = start
            val end = (start + length).coerceAtMost(data.size)
            while (index < end) {
                val entryLength = data[index].toInt() and 0xFF
                if (entryLength == 0 || index + 1 + entryLength > end) break
                if (out.isNotEmpty()) out.append(',')
                out.append(String(data, index + 1, entryLength, Charsets.UTF_8))
                index += 1 + entryLength
            }
            return out.toString()
        }

        private fun ipv4(data: ByteArray, offset: Int): String =
            "${data[offset].toInt() and 0xFF}.${data[offset + 1].toInt() and 0xFF}." +
                "${data[offset + 2].toInt() and 0xFF}.${data[offset + 3].toInt() and 0xFF}"

        private fun readU16(data: ByteArray, offset: Int): Int {
            if (offset + 1 >= data.size) return 0
            return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        }

        private fun writeU16(out: ByteArrayOutputStream, value: Int) {
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }
    }
}
