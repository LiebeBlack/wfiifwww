package com.example.wifiscanner

import com.example.wifiscanner.data.DeviceLabelRepository
import com.example.wifiscanner.net.LocalNetworkInfo
import com.example.wifiscanner.net.OuiDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic unit tests: OUI normalization, subnet arithmetic and the
 * repository's MAC normalizer. These run on the JVM with no Android deps.
 */
class OuiAndNetworkTest {

    // ------------------------------------------------------------------
    // OUI normalization
    // ------------------------------------------------------------------

    @Test
    fun `normalizeOui accepts colon, dash, dot and bare formats`() {
        assertEquals("AA:BB:CC", OuiDatabase.normalizeOui("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC", OuiDatabase.normalizeOui("AA-BB-CC-DD-EE-FF"))
        assertEquals("AA:BB:CC", OuiDatabase.normalizeOui("aabb.ccdd.eeff"))
        assertEquals("AA:BB:CC", OuiDatabase.normalizeOui("aabbccddeeff"))
    }

    @Test
    fun `normalizeOui rejects null, blank and short input`() {
        assertNull(OuiDatabase.normalizeOui(null))
        assertNull(OuiDatabase.normalizeOui(""))
        assertNull(OuiDatabase.normalizeOui("abc"))
    }

    // ------------------------------------------------------------------
    // Subnet arithmetic
    // ------------------------------------------------------------------

    @Test
    fun `networkOf masks host bits`() {
        assertEquals("192.168.1.0", LocalNetworkInfo.networkOf("192.168.1.37", 24))
        assertEquals("10.20.0.0", LocalNetworkInfo.networkOf("10.20.30.40", 16))
    }

    @Test
    fun `ipv4ToInt round trips`() {
        val value = LocalNetworkInfo.ipv4ToInt("192.168.1.37")
        requireNotNull(value)
        assertEquals("192.168.1.37", LocalNetworkInfo.intToIpv4(value))
        assertNull(LocalNetworkInfo.ipv4ToInt("999.1.1.1"))
        assertNull(LocalNetworkInfo.ipv4ToInt("not.an.ip.addr"))
    }

    @Test
    fun `littleEndianIntToIpv4 reverses dhcp byte order`() {
        // DhcpInfo stores 192.168.1.37 as little-endian 0x2501A8C0.
        assertEquals("192.168.1.37", LocalNetworkInfo.littleEndianIntToIpv4(0x2501A8C0.toInt()))
    }

    @Test
    fun `maskToInt computes prefix length`() {
        assertEquals(24, LocalNetworkInfo.maskToInt("255.255.255.0"))
        assertEquals(16, LocalNetworkInfo.maskToInt("255.255.0.0"))
        assertEquals(30, LocalNetworkInfo.maskToInt("255.255.255.252"))
    }

    @Test
    fun `hostAddresses excludes network and broadcast`() {
        val subnet = LocalNetworkInfo.Subnet("192.168.1.0", 30)
        // /30 -> 4 addresses; usable hosts = .1 and .2 only.
        assertEquals(listOf("192.168.1.1", "192.168.1.2"), subnet.hostAddresses().toList())
    }

    // ------------------------------------------------------------------
    // Repository MAC normalization
    // ------------------------------------------------------------------

    @Test
    fun `repository normalizeMac produces canonical form`() {
        assertEquals("AA:BB:CC:DD:EE:FF", DeviceLabelRepository.normalizeMac("aa-bb-cc-dd-ee-ff"))
        assertEquals("A1:B2:C3:D4:E5:F6", DeviceLabelRepository.normalizeMac("A1B2C3D4E5F6"))
        assertNull(DeviceLabelRepository.normalizeMac("zz:zz:zz"))
        assertNull(DeviceLabelRepository.normalizeMac("AA:BB:CC")) // too short
    }
}
