package com.example.wifiscanner

import com.example.wifiscanner.model.DiscoverySource
import com.example.wifiscanner.net.ArpTableReader
import com.example.wifiscanner.net.SsdpDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the multi-strategy discovery parsing helpers: SSDP/UPnP replies,
 * both supported neighbour-table formats, and the UI source labels.
 *
 * These run on the JVM with no Android and no network. The parsing is where
 * the resilience lives, so it has to stay *total*: every malformed or hostile
 * input must degrade to "nothing found" rather than throw.
 */
class MultiStrategyDiscoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ------------------------------------------------------------------
    // SSDP / UPnP
    // ------------------------------------------------------------------

    /** A realistic reply from a home router. */
    private val gatewayReply = listOf(
        "HTTP/1.1 200 OK",
        "CACHE-CONTROL: max-age=120",
        "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "USN: uuid:8f6a-1234::urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "SERVER: Linux/3.14 UPnP/1.0 IpBridge/1.20",
        "LOCATION: http://192.168.1.1:1900/igd.xml",
        "",
    ).joinToString("\r\n")

    @Test
    fun `ssdp reply is parsed into a response with its device kind`() {
        val response = requireNotNull(SsdpDiscovery.parseResponse(gatewayReply, "192.168.1.1"))

        assertEquals("192.168.1.1", response.ip)
        assertEquals("InternetGatewayDevice", response.kind)
        assertEquals("Linux/3.14 UPnP/1.0 IpBridge/1.20", response.server)
        assertEquals("http://192.168.1.1:1900/igd.xml", response.location)
    }

    @Test
    fun `ssdp notify messages parse and generic urns carry no device kind`() {
        val notify = listOf(
            "NOTIFY * HTTP/1.1",
            "HOST: 239.255.255.250:1900",
            "NT: upnp:rootdevice",
            "USN: uuid:8f6a-1234::upnp:rootdevice",
            "",
        ).joinToString("\r\n")

        val response = requireNotNull(SsdpDiscovery.parseResponse(notify, "192.168.1.1"))

        assertEquals("192.168.1.1", response.ip)
        assertNull(response.kind)
    }

    @Test
    fun `device kind is read from the last urn segment`() {
        assertEquals(
            "MediaRenderer",
            SsdpDiscovery.deviceKind("uuid:1111::urn:schemas-upnp-org:device:MediaRenderer:1"),
        )
        assertEquals(
            "AVTransport",
            SsdpDiscovery.deviceKind("urn:schemas-upnp-org:service:AVTransport:1"),
        )
        assertNull(SsdpDiscovery.deviceKind("upnp:rootdevice"))
        assertNull(SsdpDiscovery.deviceKind("uuid:8f6a-1234"))
        assertNull(SsdpDiscovery.deviceKind(null))
        assertNull(SsdpDiscovery.deviceKind(""))
    }

    @Test
    fun `header keys are lowercased and the first duplicate wins`() {
        val headers = SsdpDiscovery.parseHeaders(
            listOf(
                "HTTP/1.1 200 OK",
                "ST: urn:first",
                "ST: urn:second",
                "",
            ).joinToString("\r\n")
        )

        assertEquals("urn:first", headers["st"])
        assertEquals(1, headers.size)
    }

    @Test
    fun `malformed ssdp payloads yield nothing instead of throwing`() {
        assertNull(SsdpDiscovery.parseResponse("", "10.0.0.1"))
        assertNull(SsdpDiscovery.parseResponse("HTTP/1.1 200 OK\r\n", "10.0.0.1"))
        assertTrue(SsdpDiscovery.parseHeaders("garbage").isEmpty())
    }

    // ------------------------------------------------------------------
    // ARP / neighbour table
    // ------------------------------------------------------------------

    @Test
    fun `hexadecimal flag column is parsed, incomplete entries dropped`() {
        val file = arpFile(
            "IP address       HW type     Flags       HW address            Mask     Device",
            "192.168.1.1      0x1         0x2         aa:bb:cc:dd:ee:01     0x0      wlan0",
            "192.168.1.2      0x1         0x0         00:00:00:00:00:00     0x0      wlan0",
        )

        val entries = ArpTableReader.read(file)

        assertEquals("AA:BB:CC:DD:EE:01", entries["192.168.1.1"]?.mac)
        assertNull(entries["192.168.1.2"])
    }

    @Test
    fun `ip neigh output is understood as a second table format`() {
        val file = arpFile(
            "192.168.1.20 dev wlan0 lladdr aa:bb:cc:dd:ee:20 REACHABLE",
            "192.168.1.21 dev wlan0  FAILED",
            "fe80::1 dev wlan0 lladdr aa:bb:cc:dd:ee:21 REACHABLE",
        )

        val entries = ArpTableReader.read(file)

        // Only the complete IPv4 entry survives; IPv6 and FAILED are skipped.
        assertEquals(1, entries.size)
        assertEquals("AA:BB:CC:DD:EE:20", entries["192.168.1.20"]?.mac)
    }

    @Test
    fun `an unreadable table reports unavailable instead of throwing`() {
        val missing = File(tempFolder.root, "no-arp.txt")

        assertTrue(
            ArpTableReader.readWithStatus(missing) is ArpTableReader.ArpReadResult.Unavailable
        )
        assertTrue(ArpTableReader.read(missing).isEmpty())
    }

    @Test
    fun `flag parsing tolerates prefixed, plain and invalid values`() {
        assertEquals(2, ArpTableReader.parseFlags("0x2"))
        assertEquals(2, ArpTableReader.parseFlags("2"))
        assertEquals(0, ArpTableReader.parseFlags("0x0"))
        assertNull(ArpTableReader.parseFlags("not-a-number"))
        assertNull(ArpTableReader.parseFlags(""))
    }

    // ------------------------------------------------------------------
    // UI source labels
    // ------------------------------------------------------------------

    @Test
    fun `discovery source labels are ordered and joined`() {
        assertEquals(
            "ARP · ICMP · SSDP",
            DiscoverySource.labels(
                setOf(DiscoverySource.Ssdp, DiscoverySource.Icmp, DiscoverySource.Arp),
            ),
        )
        assertEquals("this device", DiscoverySource.labels(setOf(DiscoverySource.Self)))
        assertEquals("", DiscoverySource.labels(emptySet()))
    }

    private fun arpFile(vararg lines: String): File =
        tempFolder.newFile().apply { writeText(lines.joinToString("\n")) }
}
