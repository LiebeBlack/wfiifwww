package com.example.wifiscanner

import com.example.wifiscanner.model.DeviceKind
import com.example.wifiscanner.model.DiscoverySource
import com.example.wifiscanner.net.NetworkStatistics
import com.example.wifiscanner.net.WsDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Windows-style discovery path (WS-Discovery) and the
 * network statistics maths. Pure JVM, no Android and no network.
 */
class WindowsDiscoveryAndStatsTest {

    /** A realistic `ProbeMatch` from a Windows PC that shares a printer. */
    private val printerProbeMatch = """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                       xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                       xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                       xmlns:wsdp="http://schemas.xmlsoap.org/ws/2006/02/devprof">
          <soap:Header>
            <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</wsa:Action>
            <wsa:MessageID>urn:uuid:11111111-2222-3333-4444-555555555555</wsa:MessageID>
            <wsa:RelatesTo>urn:uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</wsa:RelatesTo>
          </soap:Header>
          <soap:Body>
            <wsd:ProbeMatches>
              <wsd:ProbeMatch>
                <wsa:EndpointReference>
                  <wsa:Address>urn:uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</wsa:Address>
                </wsa:EndpointReference>
                <wsd:Types>wsdp:Device pub:Computer</wsd:Types>
                <wsd:Scopes>ldap:///ou=Printers http://schemas.microsoft.com/windows/pub/2005/07/computer</wsd:Scopes>
                <wsd:XAddrs>http://192.168.1.42:5357/9c6d1e0a-1111/ http://192.168.1.42:5357/9c6d1e0a-2222/</wsd:XAddrs>
              </wsd:ProbeMatch>
            </wsd:ProbeMatches>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent()

    // ------------------------------------------------------------------
    // WS-Discovery
    // ------------------------------------------------------------------

    @Test
    fun `probe match is decoded into endpoints, types, scopes and addresses`() {
        val match = requireNotNull(WsDiscovery.WsdProtocol.parseProbeMatch(printerProbeMatch))

        assertEquals("urn:uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", match.endpoint)
        assertEquals("urn:uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", match.relatesTo)
        assertTrue(match.types.contains("pub:Computer"))
        assertTrue(match.scopes.contains("ldap:///ou=Printers"))
        assertEquals(2, match.xaddrs.size)
        assertEquals("http://192.168.1.42:5357/9c6d1e0a-1111/", match.xaddrs.first())
    }

    @Test
    fun `probe match addresses are turned into device IPs`() {
        assertEquals(
            "192.168.1.42",
            WsDiscovery.WsdProtocol.ipFromUri("http://192.168.1.42:5357/9c6d1e0a-1111/"),
        )
        assertEquals("10.0.0.7", WsDiscovery.WsdProtocol.ipFromUri("http://10.0.0.7/wsd"))
        assertEquals("192.168.0.9", WsDiscovery.WsdProtocol.ipFromUri("192.168.0.9:5357"))
        assertNull(WsDiscovery.WsdProtocol.ipFromUri("http://[fe80::1]:5357/"))
        assertNull(WsDiscovery.WsdProtocol.ipFromUri("http://example.local:5357/"))
    }

    @Test
    fun `device class is derived from the advertised types and scopes`() {
        assertEquals(
            "Computer",
            WsDiscovery.WsdProtocol.describeKind(
                listOf("wsdp:Device", "pub:Computer"),
            ),
        )
        assertEquals(
            "Printer",
            WsDiscovery.WsdProtocol.describeKind(listOf("wprt:PrintDeviceType")),
        )
        assertEquals(
            "Camera",
            WsDiscovery.WsdProtocol.describeKind(
                listOf("http://www.onvif.org/ver10/device/wsdl/NetworkVideoTransmitter"),
            ),
        )
        assertEquals("Media player", WsDiscovery.WsdProtocol.describeKind(listOf("MediaRenderer")))
        assertNull(WsDiscovery.WsdProtocol.describeKind(emptyList()))
        assertNull(WsDiscovery.WsdProtocol.describeKind(listOf("wsdp:Device", "foo:Bar")))
    }

    @Test
    fun `wsd hints feed the device classifier`() {
        // A printer found only through WS-Discovery must still be recognized.
        assertEquals(
            DeviceKind.Printer,
            DeviceKind.classify(isSelf = false, services = listOf("Printer")),
        )
        assertEquals(
            DeviceKind.Camera,
            DeviceKind.classify(isSelf = false, services = listOf("Camera")),
        )
        assertEquals(
            DeviceKind.Computer,
            DeviceKind.classify(isSelf = false, services = listOf("Computer")),
        )
    }

    @Test
    fun `probe declares the discovery action and our message id`() {
        val messageId = "urn:uuid:" + WsDiscovery.WsdProtocol.randomUuid()
        val probe = WsDiscovery.WsdProtocol.buildProbe(messageId)

        assertTrue(probe.contains(WsDiscovery.WsdProtocol.PROBE_ACTION))
        assertTrue(probe.contains(WsDiscovery.WsdProtocol.DISCOVERY_TO))
        assertTrue(probe.contains(messageId))
        assertTrue(probe.contains("<wsd:Probe/>"))
        assertFalse(probe.contains("__"))
    }

    @Test
    fun `random uuids look like uuids and differ`() {
        val first = WsDiscovery.WsdProtocol.randomUuid()
        val second = WsDiscovery.WsdProtocol.randomUuid()

        assertEquals(36, first.length)
        assertEquals('-', first[8])
        assertEquals('-', first[13])
        assertEquals('-', first[18])
        assertEquals('-', first[23])
        assertTrue(first.all { it.isDigit() || it in 'a'..'f' || it == '-' })
        assertNotNull(first)
        assertFalse(first == second)
    }

    @Test
    fun `foreign or malformed payloads are ignored`() {
        assertNull(WsDiscovery.WsdProtocol.parseProbeMatch(""))
        assertNull(WsDiscovery.WsdProtocol.parseProbeMatch("HTTP/1.1 200 OK"))
        assertNull(WsDiscovery.WsdProtocol.parseProbeMatch("<soap:Envelope/>"))
        assertTrue(WsDiscovery.WsdProtocol.elements("<a:XAddrs>http://x/</a:XAddrs>", "XAddrs").isEmpty().not())
    }

    @Test
    fun `wsd is a first-class discovery source label`() {
        assertTrue(DiscoverySource.labels(setOf(DiscoverySource.Wsd, DiscoverySource.Arp)).contains("WSD"))
        assertEquals(
            listOf(DiscoverySource.Ssdp, DiscoverySource.Wsd, DiscoverySource.Mdns),
            DiscoverySource.ordered(
                setOf(DiscoverySource.Mdns, DiscoverySource.Wsd, DiscoverySource.Ssdp),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Network statistics maths
    // ------------------------------------------------------------------

    @Test
    fun `channel is derived from the centre frequency`() {
        assertEquals(1, NetworkStatistics.channelOf(2412))
        assertEquals(6, NetworkStatistics.channelOf(2437))
        assertEquals(13, NetworkStatistics.channelOf(2472))
        assertEquals(36, NetworkStatistics.channelOf(5180))
        assertEquals(44, NetworkStatistics.channelOf(5220))
        assertEquals(1, NetworkStatistics.channelOf(5955))
        assertNull(NetworkStatistics.channelOf(900))
    }

    @Test
    fun `band is derived from the centre frequency`() {
        assertEquals("2.4 GHz", NetworkStatistics.bandOf(2412))
        assertEquals("5 GHz", NetworkStatistics.bandOf(5220))
        assertEquals("6 GHz", NetworkStatistics.bandOf(5975))
        assertNull(NetworkStatistics.bandOf(1000))
    }

    @Test
    fun `signal level maps rssi onto five buckets`() {
        assertEquals(4, NetworkStatistics.signalLevelOf(-30))
        assertEquals(4, NetworkStatistics.signalLevelOf(-50))
        assertEquals(3, NetworkStatistics.signalLevelOf(-51))
        assertEquals(2, NetworkStatistics.signalLevelOf(-65))
        assertEquals(1, NetworkStatistics.signalLevelOf(-75))
        assertEquals(0, NetworkStatistics.signalLevelOf(-90))
    }

    @Test
    fun `usable host count follows the prefix length and the scanner cap`() {
        assertEquals(254, NetworkStatistics.usableHosts(24))
        assertEquals(6, NetworkStatistics.usableHosts(29))
        assertEquals(2, NetworkStatistics.usableHosts(30))
        // Wider subnets are capped at the scanner's own limit (1024 addresses).
        assertEquals(1022, NetworkStatistics.usableHosts(16))
    }
}
