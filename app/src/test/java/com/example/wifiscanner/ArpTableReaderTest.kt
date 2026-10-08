package com.example.wifiscanner

import com.example.wifiscanner.net.ArpTableReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [ArpTableReader] against a synthetic /proc/net/arp file.
 */
class ArpTableReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeArp(vararg lines: String): File =
        tempFolder.newFile("arp.txt").apply { writeText(lines.joinToString("\n")) }

    @Test
    fun `parses complete entries and drops incomplete ones`() {
        val file = writeArp(
            "IP address       HW type     Flags       HW address            Mask     Device",
            "192.168.1.1      0x1         0x2         aa:bb:cc:dd:ee:ff     0x0      wlan0",
            "192.168.1.50     0x1         0x0         00:00:00:00:00:00     0x0      wlan0",
            "192.168.1.60     0x1         0x2         11:22:33:44:55:66     0x0      wlan0",
        )
        val result = ArpTableReader.read(file)

        assertEquals(2, result.size)
        assertEquals("AA:BB:CC:DD:EE:FF", result["192.168.1.1"]?.mac) // uppercased
        assertEquals("11:22:33:44:55:66", result["192.168.1.60"]?.mac)
        assertFalse(result.containsKey("192.168.1.50")) // incomplete flag
    }

    @Test
    fun `header-only file yields empty map`() {
        val file = writeArp(
            "IP address       HW type     Flags       HW address            Mask     Device",
        )
        assertTrue(ArpTableReader.read(file).isEmpty())
    }

    @Test
    fun `zero mac is rejected as not real`() {
        val zero = ArpTableReader.ArpEntry("10.0.0.1", "00:00:00:00:00:00")
        val real = ArpTableReader.ArpEntry("10.0.0.2", "DE:AD:BE:EF:00:01")
        assertFalse(zero.isRealMac)
        assertTrue(real.isRealMac)
    }

    @Test
    fun `missing file returns empty map`() {
        val missing = File(tempFolder.root, "nope.txt")
        assertTrue(ArpTableReader.read(missing).isEmpty())
    }
}
