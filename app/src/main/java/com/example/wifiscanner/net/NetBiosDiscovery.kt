package com.example.wifiscanner.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * NetBIOS name-service discovery (UDP 137, "NBSTAT" query).
 *
 * Windows PCs, many NAS boxes, printers and SMB routers answer a wildcard
 * NBSTAT query with their **machine name and their MAC address**. That makes
 * this the only unprivileged method that recovers a hardware address when
 * Android hides the ARP table (API 29+), which in turn restores vendor lookup,
 * stable identities and location labels on those devices.
 *
 * The query is a single UDP datagram sent to the subnet's directed broadcast
 * address (and the global broadcast as a backup). Answers arrive unicast from
 * each device. Devices that disabled NetBIOS simply stay silent — they are
 * found by the other strategies — and every failure here degrades to an empty
 * map, never an error.
 */
class NetBiosDiscovery(
    // The context is part of every strategy's signature for symmetry and for
    // future use (a Wi-Fi lock or network callback would need it); NetBIOS
    // itself is plain UDP broadcast, so nothing here consumes it yet.
    @Suppress("unused") context: Context,
) {

    /** What one NBSTAT answer told us. */
    data class NbstatResult(
        val ip: String,
        /** Machine name (the unique `<00>` name), e.g. "DESKTOP-ABC123". */
        val machineName: String? = null,
        /** Uppercase colon-separated MAC address, or null when all zeros. */
        val mac: String? = null,
        /** Workgroup / domain name, when advertised. */
        val groupName: String? = null,
    )

    /**
     * Sends one NBSTAT query and collects answers.
     *
     * @param broadcastAddress subnet broadcast (e.g. "192.168.1.255"); the
     *                         global broadcast is tried as well.
     * @return answers keyed by the responder's IPv4 address.
     */
    suspend fun discover(
        broadcastAddress: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): Map<String, NbstatResult> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<String, NbstatResult>()
        val transactionId = Random.nextInt(1, 0xFFFF)
        val payload = NetBiosProtocol.buildQuery(transactionId)
        val targets = listOf(broadcastAddress, GLOBAL_BROADCAST).distinct()

        var socket: DatagramSocket? = null
        try {
            val active = DatagramSocket()
            socket = active
            active.broadcast = true
            active.soTimeout = RECEIVE_TIMEOUT_MILLIS
            for (target in targets) {
                try {
                    val address = InetAddress.getByName(target)
                    active.send(DatagramPacket(payload, payload.size, address, NETBIOS_PORT))
                } catch (_: Exception) {
                    // A broadcast address that cannot be used is simply skipped.
                }
            }

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
                val ip = packet.address?.hostAddress ?: continue
                val parsed = NetBiosProtocol.parseResponse(packet.data, packet.length, transactionId)
                    ?: continue
                if (results.containsKey(ip)) continue
                results[ip] = NbstatResult(
                    ip = ip,
                    machineName = parsed.machineName,
                    mac = parsed.mac,
                    groupName = parsed.groupName,
                )
            }
        } catch (_: Exception) {
            // No broadcast permission / no socket: report nothing found.
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
                // Closing a broken socket must not hide the results.
            }
        }
        results
    }

    /**
     * NetBIOS wire format helpers: the wildcard NBSTAT query and its parser.
     * Pure functions, so the whole protocol is unit-testable on the JVM.
     */
    internal object NetBiosProtocol {

        /** RR type of a node-status (NBSTAT) record. */
        const val TYPE_NBSTAT = 0x0021
        const val CLASS_IN = 0x0001

        /** Node-status name suffixes we care about. */
        private const val SUFFIX_WORKSTATION = 0x00
        private const val GROUP_BIT = 0x80

        private const val MAX_NAMES = 32
        private const val WILDCARD = 0x2A.toByte() // '*'
        private const val HEX_DIGITS = "0123456789ABCDEF"

        /** Decoded NBSTAT payload: machine/group names plus the MAC. */
        internal data class NbstatPayload(
            val machineName: String?,
            val groupName: String?,
            val mac: String?,
        )

        /**
         * Builds an NBSTAT query for the wildcard name (`*`), which asks every
         * responder on the link to report all of its names.
         */
        internal fun buildQuery(transactionId: Int): ByteArray {
            val out = ByteArrayOutputStream()
            writeU16(out, transactionId)
            writeU16(out, 0x0000) // standard query
            writeU16(out, 1)      // QDCOUNT
            writeU16(out, 0)      // ANCOUNT
            writeU16(out, 0)      // NSCOUNT
            writeU16(out, 0)      // ARCOUNT
            out.write(encodeNetBiosName())
            writeU16(out, TYPE_NBSTAT)
            writeU16(out, CLASS_IN)
            return out.toByteArray()
        }

        /**
         * Encodes the 16-byte wildcard name in NetBIOS first-level encoding:
         * a length byte (32), each nibble mapped to 'A'..'P', then a terminator.
         */
        private fun encodeNetBiosName(): ByteArray {
            val raw = ByteArray(16)
            raw[0] = WILDCARD
            val encoded = ByteArray(1 + raw.size * 2 + 1)
            encoded[0] = (raw.size * 2).toByte()
            for (index in raw.indices) {
                val value = raw[index].toInt() and 0xFF
                encoded[1 + index * 2] = ('A'.code + ((value shr 4) and 0x0F)).toByte()
                encoded[2 + index * 2] = ('A'.code + (value and 0x0F)).toByte()
            }
            encoded[encoded.size - 1] = 0
            return encoded
        }

        /**
         * Parses one NBSTAT answer.
         *
         * @param expectedId transaction id of our query; unrelated datagrams
         *                   (or replies to somebody else's scan) are rejected.
         * @return the decoded payload, or null when the packet is not a
         *         well-formed NBSTAT answer.
         */
        internal fun parseResponse(
            data: ByteArray,
            length: Int,
            expectedId: Int,
        ): NbstatPayload? {
            if (length < 12 || data.size < 12) return null
            if (readU16(data, 0) != expectedId) return null

            val questions = readU16(data, 4)
            val answers = readU16(data, 6)
            if (answers < 1) return null

            // Skip the question section: encoded name plus QTYPE + QCLASS.
            var offset = 12
            repeat(questions) {
                if (offset >= length) return null
                offset += 1 + (data[offset].toInt() and 0xFF) + 1 + 4
            }
            if (offset + 10 > length) return null

            // Answer record: encoded owner name, then type/class/ttl/rdlength.
            offset += 1 + (data[offset].toInt() and 0xFF) + 1
            if (offset + 10 > length) return null
            if (readU16(data, offset) != TYPE_NBSTAT) return null

            val dataLength = readU16(data, offset + 8)
            val dataStart = offset + 10
            if (dataLength < 7 || dataStart + dataLength > length) return null

            val nameCount = (data[dataStart].toInt() and 0xFF).coerceAtMost(MAX_NAMES)
            val unique = ArrayList<Pair<String, Int>>(nameCount)
            val groups = ArrayList<String>(2)
            var cursor = dataStart + 1
            repeat(nameCount) {
                if (cursor + 16 > length) return@repeat
                val name = String(data, cursor, 15, Charsets.US_ASCII).trim()
                val flags = data[cursor + 15].toInt() and 0xFF
                if (name.isNotEmpty() && name != "*") {
                    if (flags and GROUP_BIT != 0) {
                        groups.add(name)
                    } else {
                        unique.add(name to (flags and 0x7F))
                    }
                }
                cursor += 16
            }
            if (cursor + 6 > length) return null

            val machineName = unique.firstOrNull { it.second == SUFFIX_WORKSTATION }?.first
                ?: unique.firstOrNull()?.first

            return NbstatPayload(
                machineName = machineName,
                groupName = groups.firstOrNull(),
                mac = formatMac(data, cursor),
            )
        }

        private fun formatMac(data: ByteArray, offset: Int): String? {
            if (offset + 6 > data.size) return null
            val bytes = IntArray(6) { index -> data[offset + index].toInt() and 0xFF }
            if (bytes.all { it == 0 }) return null
            return bytes.joinToString(":") { value ->
                "${HEX_DIGITS[(value shr 4) and 0x0F]}${HEX_DIGITS[value and 0x0F]}"
            }
        }

        private fun readU16(data: ByteArray, offset: Int): Int {
            if (offset + 1 >= data.size) return 0
            return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        }

        private fun writeU16(out: ByteArrayOutputStream, value: Int) {
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }
    }

    companion object {
        const val NETBIOS_PORT = 137
        const val GLOBAL_BROADCAST = "255.255.255.255"

        private const val DEFAULT_TIMEOUT_MILLIS = 1_800L
        private const val RECEIVE_TIMEOUT_MILLIS = 600
        private const val MAX_DATAGRAM = 1_500
    }
}
