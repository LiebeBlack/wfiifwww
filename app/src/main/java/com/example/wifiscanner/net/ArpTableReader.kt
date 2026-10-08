package com.example.wifiscanner.net

import java.io.File

/**
 * Parses the kernel's ARP neighbor table exposed at `/proc/net/arp`.
 *
 * Every IP that the device has recently exchanged L2/L3 frames with ends up
 * here, which makes this the fastest possible source of "who is on my LAN":
 * no packets need to be sent at all. The classic Android trick is to *prime*
 * the table with a quick ICMP sweep and then read it.
 *
 * File format (one header line, then entries):
 *
 * ```
 * IP address       HW type   Flags       HW address            Mask     Device
 * 192.168.1.1      0x1       0x2         aa:bb:cc:dd:ee:ff     0x0      wlan0
 * ```
 *
 * Flags bit 0x2 (ATF_COM) means the entry is *complete* (a real MAC is known);
 * 0x0 entries are incomplete placeholders and are skipped.
 */
object ArpTableReader {

    /** One resolved neighbor: IP + normalized (uppercase, colon-separated) MAC. */
    data class ArpEntry(val ip: String, val mac: String) {
        /** True when this is a real, non-zero hardware address. */
        val isRealMac: Boolean
            get() = mac != "00:00:00:00:00:00" && mac.count { it == ':' } == 5
    }

    private const val ARP_FILE = "/proc/net/arp"
    private const val FLAG_COMPLETE = 0x2 // ATF_COM

    /**
     * Reads and parses the ARP table.
     *
     * @param arpFile Injected for unit tests; production always uses /proc/net/arp.
     * @return complete entries only, keyed by IP for easy merging with sweep results.
     */
    fun read(arpFile: File = File(ARP_FILE)): Map<String, ArpEntry> {
        if (!arpFile.exists()) return emptyMap()

        val result = HashMap<String, ArpEntry>(64)
        // File reads on the main thread throw NetworkOnMainThreadException on
        // some devices; callers should invoke this from Dispatchers.IO.
        arpFile.forEachLine { line ->
            // First line is the header.
            if (line.startsWith("IP address")) return@forEachLine

            // Columns are whitespace-separated: IP, HW type, Flags, HW address, Mask, Device.
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6) return@forEachLine

            val ip = parts[0]
            val flags = parts[2].toIntOrNull() ?: return@forEachLine
            val mac = parts[3].uppercase()

            if (flags and FLAG_COMPLETE == 0) return@forEachLine

            val entry = ArpEntry(ip = ip, mac = mac)
            if (entry.isRealMac) result[ip] = entry
        }
        return result
    }
}
