package com.example.wifiscanner

import com.example.wifiscanner.model.DeviceKind
import com.example.wifiscanner.model.DiscoverySource
import com.example.wifiscanner.model.NetworkDevice
import com.example.wifiscanner.net.MdnsDiscovery
import com.example.wifiscanner.net.NetBiosDiscovery
import com.example.wifiscanner.ui.ScanExport
import com.example.wifiscanner.ui.ipv4SortKey
import com.example.wifiscanner.ui.matchesQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Unit tests for the wire-format parsers (mDNS DNS-SD and NetBIOS NBSTAT), the
 * device-kind classifier and the pure presentation helpers.
 *
 * These run on the JVM with no Android and no network. Both protocols are
 * parsed from hostile input in the field, so the tests include malformed and
 * truncated packets: the parsers must degrade, never throw.
 */
class ProtocolAndClassificationTest {

    // ------------------------------------------------------------------
    // mDNS / DNS-SD
    // ------------------------------------------------------------------

    /** Minimal DNS message writer, mirroring what a real responder sends. */
    private class DnsWriter {
        val out = ByteArrayOutputStream()

        fun u16(value: Int) = apply {
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        fun u32(value: Long) = apply {
            for (shift in intArrayOf(24, 16, 8, 0)) out.write(((value shr shift) and 0xFF).toInt())
        }

        fun bytes(vararg values: Int) = apply { values.forEach { out.write(it) } }

        fun name(value: String) = apply {
            for (label in value.split('.')) {
                val encoded = label.toByteArray(Charsets.UTF_8)
                out.write(encoded.size)
                out.write(encoded, 0, encoded.size)
            }
            out.write(0)
        }

        /** Compression pointer to a previously written name. */
        fun pointer(offset: Int) = apply { out.write(0xC0 or ((offset shr 8) and 0x3F)); out.write(offset and 0xFF) }

        fun txt(vararg entries: String) = apply {
            for (entry in entries) {
                val encoded = entry.toByteArray(Charsets.UTF_8)
                out.write(encoded.size)
                out.write(encoded, 0, encoded.size)
            }
        }

        /** Wire length of a name (labels + their length bytes + the root). */
        fun nameLength(value: String): Int =
            value.split('.').sumOf { it.toByteArray(Charsets.UTF_8).size + 1 } + 1

        /** Wire length of a TXT payload (each entry is length-prefixed). */
        fun txtLength(vararg entries: String): Int =
            entries.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }

        fun recordHeader(type: Int, dataLength: Int) = apply {
            u16(type); u16(1); u32(120); u16(dataLength)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** A realistic Chromecast answer set, with a compression pointer. */
    private fun chromecastPacket(): ByteArray {
        val writer = DnsWriter()
        writer.u16(0).u16(0x8400).u16(1).u16(3).u16(0).u16(1) // header: 1 question, 3 answers, 1 extra

        val questionOffset = 12
        val instance = "Chromecast-abc._googlecast._tcp.local"
        writer.name("_googlecast._tcp.local").u16(12).u16(1)

        // PTR: service type -> instance name
        writer.pointer(questionOffset).recordHeader(12, writer.nameLength(instance))
            .name(instance)
        // SRV: instance -> host:port (6 bytes of priority/weight/port + target)
        writer.name(instance).recordHeader(33, 6 + writer.nameLength("Chromecast-abc.local"))
            .u16(0).u16(0).u16(8009).name("Chromecast-abc.local")
        // TXT: model
        writer.name(instance).recordHeader(16, writer.txtLength("md=Chromecast Ultra"))
            .txt("md=Chromecast Ultra")
        // A: host -> ip
        writer.name("Chromecast-abc.local").recordHeader(1, 4).bytes(192, 168, 1, 50)

        return writer.toByteArray()
    }

    @Test
    fun `mdns packet is decoded including compression pointers`() {
        val packet = chromecastPacket()
        val records = MdnsDiscovery.MdnsProtocol.parseMessage(packet, packet.size)

        val ptr = records.first { it.type == MdnsDiscovery.MdnsProtocol.TYPE_PTR }
        assertEquals("_googlecast._tcp.local", ptr.name)
        assertEquals("chromecast-abc._googlecast._tcp.local", ptr.value)

        val srv = records.first { it.type == MdnsDiscovery.MdnsProtocol.TYPE_SRV }
        assertEquals("chromecast-abc._googlecast._tcp.local", srv.name)
        assertEquals("chromecast-abc.local", srv.value)
        assertEquals(8009, srv.port)

        val txt = records.first { it.type == MdnsDiscovery.MdnsProtocol.TYPE_TXT }
        assertEquals("md=Chromecast Ultra", txt.value)

        val a = records.first { it.type == MdnsDiscovery.MdnsProtocol.TYPE_A }
        assertEquals("chromecast-abc.local", a.name)
        assertEquals("192.168.1.50", a.value)
    }

    @Test
    fun `mdns service type and model are derived from record names`() {
        assertEquals(
            "_googlecast._tcp",
            MdnsDiscovery.MdnsProtocol.serviceTypeOf("Chromecast-abc._googlecast._tcp.local"),
        )
        assertEquals("_ipp._tcp", MdnsDiscovery.MdnsProtocol.serviceTypeOf("_ipp._tcp.local"))
        assertNull(MdnsDiscovery.MdnsProtocol.serviceTypeOf("DESKTOP-ABC.local"))
        assertNull(MdnsDiscovery.MdnsProtocol.serviceTypeOf("_tcp.local"))

        assertEquals("Chromecast-abc", MdnsDiscovery.MdnsProtocol.shortName("Chromecast-abc.local"))
        assertNull(MdnsDiscovery.MdnsProtocol.shortName(".local"))

        assertEquals(listOf("Chromecast Ultra"), MdnsDiscovery.MdnsProtocol.modelsOf("md=Chromecast Ultra"))
        assertEquals(listOf("X1"), MdnsDiscovery.MdnsProtocol.modelsOf("path=/x,model=X1"))
        assertTrue(MdnsDiscovery.MdnsProtocol.modelsOf("fn=Friendly Name").isEmpty())
    }

    @Test
    fun `mdns query declares every question and asks for unicast replies`() {
        val query = MdnsDiscovery.MdnsProtocol.buildQuery(listOf("_ipp._tcp.local", "_http._tcp.local"))
        val questionCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        assertEquals(2, questionCount)
        // Each question ends with QTYPE + QCLASS, so the packet's last two
        // bytes are the class of the final question.
        val lastClass = ((query[query.size - 2].toInt() and 0xFF) shl 8) or
            (query[query.size - 1].toInt() and 0xFF)
        assertEquals(0x8001, lastClass)
    }

    @Test
    fun `truncated mdns packets degrade instead of throwing`() {
        val packet = chromecastPacket()
        assertTrue(MdnsDiscovery.MdnsProtocol.parseMessage(ByteArray(0), 0).isEmpty())
        assertTrue(MdnsDiscovery.MdnsProtocol.parseMessage(ByteArray(5), 5).isEmpty())
        // Cut inside the records: whatever is complete is still reported.
        val truncated = MdnsDiscovery.MdnsProtocol.parseMessage(packet, 40)
        assertTrue(truncated.isEmpty() || truncated.size < 4)
    }

    // ------------------------------------------------------------------
    // NetBIOS NBSTAT
    // ------------------------------------------------------------------

    /** Builds a fake NBSTAT answer for a machine named `DESKTOP-ABC`. */
    private fun nbstatResponse(transactionId: Int, name: String = "DESKTOP-ABC"): ByteArray {
        val encodedWildcard = NetBiosDiscovery.NetBiosProtocol.buildQuery(transactionId)
            .copyOfRange(12, 46) // the 34-byte encoded question name

        val writer = DnsWriter()
        writer.u16(transactionId).u16(0x8400).u16(1).u16(1).u16(0).u16(0)
        writer.out.write(encodedWildcard)          // question echoed back
        writer.u16(0x0021).u16(0x0001)             // NBSTAT type, class IN
        writer.out.write(encodedWildcard)          // answer record owner name

        val rdata = ByteArrayOutputStream()
        rdata.write(2)                             // two names
        rdata.write(netbiosName(name, 0x00))
        rdata.write(netbiosName("WORKGROUP", 0x80))
        rdata.write(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()))

        writer.recordHeader(0x0021, rdata.size())
        rdata.writeTo(writer.out)
        return writer.toByteArray()
    }

    /** 15 space-padded ASCII bytes plus the suffix/flags byte. */
    private fun netbiosName(name: String, suffix: Int): ByteArray {
        val bytes = ByteArray(16) { ' '.code.toByte() }
        val encoded = name.toByteArray(Charsets.US_ASCII)
        encoded.copyInto(bytes, 0, 0, minOf(encoded.size, 15))
        bytes[15] = suffix.toByte()
        return bytes
    }

    @Test
    fun `nbstat query encodes the wildcard name and its type`() {
        val query = NetBiosDiscovery.NetBiosProtocol.buildQuery(0x1234)
        assertEquals(50, query.size)
        assertEquals(0x12, query[0].toInt() and 0xFF)
        assertEquals(0x34, query[1].toInt() and 0xFF)
        assertEquals(1, ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF))
        assertEquals(32, query[12].toInt() and 0xFF)         // encoded length
        assertEquals('C'.code, query[13].toInt() and 0xFF)   // high nibble of '*'
        assertEquals('K'.code, query[14].toInt() and 0xFF)   // low nibble of '*'
        assertEquals('A'.code, query[15].toInt() and 0xFF)   // first zero byte
        assertEquals(0, query[45].toInt() and 0xFF)          // terminator
        assertEquals(0x0021, ((query[46].toInt() and 0xFF) shl 8) or (query[47].toInt() and 0xFF))
        assertEquals(1, ((query[48].toInt() and 0xFF) shl 8) or (query[49].toInt() and 0xFF))
    }

    @Test
    fun `nbstat answer yields the machine name and the MAC`() {
        val response = nbstatResponse(transactionId = 0x1234)
        val parsed = requireNotNull(
            NetBiosDiscovery.NetBiosProtocol.parseResponse(response, response.size, 0x1234)
        )

        assertEquals("DESKTOP-ABC", parsed.machineName)
        assertEquals("WORKGROUP", parsed.groupName)
        assertEquals("AA:BB:CC:DD:EE:FF", parsed.mac)
    }

    @Test
    fun `nbstat answers for another transaction or malformed packets are ignored`() {
        val response = nbstatResponse(transactionId = 0x1234)
        assertNull(NetBiosDiscovery.NetBiosProtocol.parseResponse(response, response.size, 0x4321))
        assertNull(NetBiosDiscovery.NetBiosProtocol.parseResponse(ByteArray(4), 4, 0x1234))
        assertNull(NetBiosDiscovery.NetBiosProtocol.parseResponse(response, 20, 0x1234))
    }

    // ------------------------------------------------------------------
    // Device classification
    // ------------------------------------------------------------------

    @Test
    fun `device kind is inferred from services and open ports`() {
        fun kind(services: List<String> = emptyList(), ports: Set<Int> = emptySet(), name: String? = null) =
            DeviceKind.classify(isSelf = false, services = services, openPorts = ports, hostname = name)

        assertEquals(DeviceKind.Self, DeviceKind.classify(isSelf = true))
        assertEquals(DeviceKind.Router, kind(services = listOf("InternetGatewayDevice")))
        assertEquals(DeviceKind.Printer, kind(ports = setOf(9100)))
        assertEquals(DeviceKind.Camera, kind(ports = setOf(554)))
        assertEquals(DeviceKind.Speaker, kind(services = listOf("_raop._tcp")))
        assertEquals(DeviceKind.MediaPlayer, kind(services = listOf("_googlecast._tcp")))
        assertEquals(DeviceKind.Nas, kind(ports = setOf(5000)))
        assertEquals(DeviceKind.Phone, kind(ports = setOf(62078)))
        assertEquals(DeviceKind.Computer, kind(ports = setOf(445)))
        assertEquals(DeviceKind.Iot, kind(services = listOf("_hap._tcp")))
        assertEquals(DeviceKind.Unknown, kind())
    }

    @Test
    fun `device exposes derived kind and display name`() {
        val printer = NetworkDevice(
            ip = "192.168.1.30",
            mac = "AA:BB:CC:DD:EE:30",
            openPorts = setOf(9100),
        )
        assertEquals(DeviceKind.Printer, printer.kind)
        assertEquals("192.168.1.30", printer.displayName)

        val named = printer.copy(hostname = "HP-Office")
        assertEquals("HP-Office", named.displayName)
    }

    @Test
    fun `discovery sources keep a stable display order`() {
        assertEquals(
            listOf(DiscoverySource.Arp, DiscoverySource.Netbios, DiscoverySource.Ssdp),
            DiscoverySource.ordered(setOf(DiscoverySource.Ssdp, DiscoverySource.Netbios, DiscoverySource.Arp)),
        )
        assertEquals("ARP · SSDP", DiscoverySource.labels(setOf(DiscoverySource.Ssdp, DiscoverySource.Arp)))
    }

    // ------------------------------------------------------------------
    // Export + list helpers
    // ------------------------------------------------------------------

    @Test
    fun `csv export has a header row and quotes special fields`() {
        val device = NetworkDevice(
            ip = "192.168.1.7",
            mac = "AA:BB:CC:DD:EE:07",
            vendor = "Acme, Inc.",
            hostname = "Living Room \"TV\"",
            location = "Sala",
            sources = setOf(DiscoverySource.Arp, DiscoverySource.Ssdp),
            services = listOf("_googlecast._tcp"),
            openPorts = setOf(8009, 80),
        )

        val csv = ScanExport.buildCsv(listOf(device))
        val lines = csv.split("\n")

        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("ip,mac,vendor,hostname,type,location,services,ports,detected_via"))
        assertTrue(lines[1].contains("\"Acme, Inc.\""))
        assertTrue(lines[1].contains("\"Living Room \"\"TV\"\"\""))
        assertTrue(lines[1].contains("80 8009"))
        assertTrue(lines[1].contains("ARP · SSDP"))
        assertEquals("ip,mac,vendor,hostname,type,location,services,ports,detected_via", ScanExport.buildCsv(emptyList()))
    }

    @Test
    fun `search matches every visible field and ip sorts numerically`() {
        val device = NetworkDevice(
            ip = "192.168.1.20",
            mac = "AA:BB:CC:DD:EE:20",
            vendor = "Samsung Electronics",
            hostname = "Sala-TV",
            services = listOf("_googlecast._tcp"),
            openPorts = setOf(8009),
        )

        assertTrue(device.matchesQuery("sala"))
        assertTrue(device.matchesQuery("samsung"))
        assertTrue(device.matchesQuery("192.168.1.20"))
        assertTrue(device.matchesQuery("aa:bb"))
        assertTrue(device.matchesQuery("8009"))
        assertTrue(device.matchesQuery("  "))
        assertFalse(device.matchesQuery("printer"))

        assertTrue("192.168.1.2".ipv4SortKey() < "192.168.1.10".ipv4SortKey())
        assertEquals(Long.MAX_VALUE, "not-an-ip".ipv4SortKey())
    }
}
