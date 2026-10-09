package com.example.wifiscanner.net

import java.io.File

/**
 * Parses the kernel's ARP/neighbour table exposed at `/proc/net/arp`.
 *
 * Every IP that the device has recently exchanged L2/L3 frames with ends up
 * here, which makes this the fastest possible source of "who is on my LAN":
 * no packets need to be sent at all. The classic Android trick is to *prime*
 * the table with a quick probe sweep and then read it.
 *
 * **Android 10+ usually hides this file** (SELinux blocks app access), so the
 * reader is strictly best-effort:
 *
 *  - it never throws — every I/O or permission error degrades to
 *    [ArpReadResult.Unavailable] so the scanner can fall back to its other
 *    strategies instead of showing an error;
 *  - it also understands `ip neigh` output, which is what we get if a future
 *    reader pipes the table through a shell or an injected test file.
 *
 * `/proc/net/arp` (one header line, then entries):
 *
 * ```
 * IP address       HW type   Flags       HW address            Mask     Device
 * 192.168.1.1      0x1       0x2         aa:bb:cc:dd:ee:ff     0x0      wlan0
 * ```
 *
 * Flags bit 0x2 (ATF_COM) means the entry is *complete* (a real MAC is known);
 * 0x0 entries are incomplete placeholders and are skipped.
 *
 * `ip neigh` output (same information, different columns):
 *
 * ```
 * 192.168.1.1 dev wlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
 * ```
 */
object ArpTableReader {

    /** One resolved neighbor: IP + normalized (uppercase, colon-separated) MAC. */
    data class ArpEntry(val ip: String, val mac: String) {
        /** True when this is a real, non-zero hardware address. */
        val isRealMac: Boolean
            get() = mac != "00:00:00:00:00:00" && mac.count { it == ':' } == 5
    }

    /** Result of a table read, so callers can tell "empty" from "no access". */
    sealed interface ArpReadResult {
        /** The table was readable; [entries] may still be empty. */
        data class Ok(val entries: Map<String, ArpEntry>) : ArpReadResult

        /** The file is missing or the OS denies access (Android 10+). */
        data object Unavailable : ArpReadResult
    }

    private const val ARP_FILE = "/proc/net/arp"
    private const val SELF_NET_ARP_FILE = "/proc/self/net/arp"
    private const val FLAG_COMPLETE = 0x2 // ATF_COM

    /**
     * Reads the table and reports whether it was readable at all.
     *
     * @param arpFile Injected for unit tests; production always uses
     *                `/proc/net/arp` and the `/proc/self/net/arp` alias (some
     *                builds expose only one of the two).
     */
    fun readWithStatus(arpFile: File = File(ARP_FILE)): ArpReadResult {
        val candidates = if (arpFile.path == ARP_FILE) {
            listOf(arpFile, File(SELF_NET_ARP_FILE))
        } else {
            listOf(arpFile)
        }
        for (candidate in candidates) {
            tryRead(candidate)?.let { return ArpReadResult.Ok(it) }
        }
        return ArpReadResult.Unavailable
    }

    /**
     * Legacy map-only accessor: complete entries keyed by IP, or an empty map
     * when the table is unreadable. Never throws.
     *
     * @param arpFile Injected for unit tests; production always uses /proc/net/arp.
     */
    fun read(arpFile: File = File(ARP_FILE)): Map<String, ArpEntry> =
        when (val result = readWithStatus(arpFile)) {
            is ArpReadResult.Ok -> result.entries
            ArpReadResult.Unavailable -> emptyMap()
        }

    /**
     * Reads one file, returning null when it is missing/unreadable.
     *
     * Catches [Throwable] on purpose: besides `IOException`/`SecurityException`,
     * some vendor ROMs throw unchecked errors from `/proc` reads, and none of
     * them should ever surface as a scan error.
     */
    private fun tryRead(file: File): Map<String, ArpEntry>? {
        return try {
            if (!file.exists() || !file.canRead()) return null
            val result = HashMap<String, ArpEntry>(64)
            file.forEachLine { line ->
                parseLine(line)?.let { entry -> result[entry.ip] = entry }
            }
            result
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Parses one table line in either supported format, or null when the line
     * is a header, incomplete, or malformed.
     */
    internal fun parseLine(line: String): ArpEntry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("IP address", ignoreCase = true)) return null

        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 4) return null

        // ---- Format 1: /proc/net/arp  -> IP HWtype Flags MAC Mask Device ----
        if (parts.size >= 6 && parts[1].startsWith("0x")) {
            val flags = parseFlags(parts[2]) ?: return null
            if (flags and FLAG_COMPLETE == 0) return null
            return entryOf(parts[0], parts[3])
        }

        // ---- Format 2: `ip neigh`  -> IP dev IFACE lladdr MAC STATE --------
        val lladdrIndex = parts.indexOfFirst { it.equals("lladdr", ignoreCase = true) }
        if (lladdrIndex > 0 && lladdrIndex + 1 < parts.size) {
            return entryOf(parts[0], parts[lladdrIndex + 1])
        }
        return null
    }

    private fun entryOf(ip: String, mac: String): ArpEntry? {
        // Cheap sanity check: the first column must really be an IPv4 address.
        if (LocalNetworkInfo.ipv4ToInt(ip) == null) return null
        val entry = ArpEntry(ip = ip, mac = mac.uppercase())
        return entry.takeIf { it.isRealMac }
    }

    /**
     * Parses the Flags column, which is hexadecimal with a `0x` prefix in
     * `/proc/net/arp` (`0x2`) but decimal in some vendor variants (`2`).
     */
    internal fun parseFlags(raw: String): Int? {
        val value = raw.trim()
        return when {
            value.startsWith("0x", ignoreCase = true) ->
                value.substring(2).toIntOrNull(radix = 16)
            value.isEmpty() -> null
            else -> value.toIntOrNull()
        }
    }
}
