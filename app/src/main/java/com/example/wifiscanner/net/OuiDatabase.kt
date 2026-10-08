package com.example.wifiscanner.net

import android.content.Context
import java.io.BufferedReader

/**
 * Lightweight, fully offline MAC OUI (Organizationally Unique Identifier)
 * lookup.
 *
 * The first three octets of a MAC address are assigned by IEEE to a vendor
 * ("Apple, Inc.", "Samsung Electronics", ...). We ship a compact, sorted
 * prefix table in `assets/oui.csv` and binary-search it in memory — O(log n)
 * with zero network I/O and only ~150 KB of RAM for the whole table.
 *
 * CSV format (one record per line, `;` separated):
 * ```
 * 3C:22:FB;Apple, Inc.
 * B8:27:EB;Raspberry Pi Foundation
 * ```
 * The file must be sorted lexicographically by prefix (build tooling sorts it).
 */
class OuiDatabase private constructor(private val prefixes: Array<String>, private val vendors: Array<String>) {

    /**
     * Resolves the vendor name for [mac], or null when the prefix is unknown.
     *
     * Accepts any common MAC formatting (colon, dash, dot, or no separator,
     * any case) and normalizes to the canonical `XX:XX:XX` OUI key.
     */
    fun lookup(mac: String?): String? {
        val key = normalizeOui(mac) ?: return null
        // Binary search over the sorted prefix table.
        var lo = 0
        var hi = prefixes.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = prefixes[mid].compareTo(key, ignoreCase = true)
            when {
                cmp == 0 -> return vendors[mid]
                cmp < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return null
    }

    companion object {
        private const val ASSET_NAME = "oui.csv"

        /**
         * Loads the OUI table from assets. Safe to call from any thread;
         * reads once per process (~30 ms on a modern device).
         */
        fun load(context: Context): OuiDatabase = try {
            val prefixes = ArrayList<String>(4096)
            val vendors = ArrayList<String>(4096)
            context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
                parseCsv(reader) { prefix, vendor ->
                    prefixes.add(prefix)
                    vendors.add(vendor)
                }
            }
            OuiDatabase(prefixes.toTypedArray(), vendors.toTypedArray())
        } catch (_: Exception) {
            // Asset missing or corrupt: degrade to an empty table (lookups
            // return null and the UI shows "Unknown vendor").
            OuiDatabase(emptyArray(), emptyArray())
        }

        /** Parses CSV lines; internal so unit tests can feed a reader directly. */
        internal fun parseCsv(reader: BufferedReader, onEntry: (String, String) -> Unit) {
            reader.forEachLine { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                val sep = line.indexOf(';')
                if (sep <= 0) return@forEachLine
                val prefix = line.substring(0, sep).trim()
                val vendor = line.substring(sep + 1).trim()
                if (prefix.isNotEmpty() && vendor.isNotEmpty()) onEntry(prefix, vendor)
            }
        }

        /**
         * Normalizes a MAC to "XX:XX:XX" (uppercase), or null if malformed.
         *
         * "aa-bb-cc-dd-ee-ff", "AABB.CCDD.EEFF", "aabbccddeeff" all work.
         */
        internal fun normalizeOui(mac: String?): String? {
            if (mac.isNullOrBlank()) return null
            val hex = mac.uppercase().filter { it.isDigit() || it in 'A'..'F' }
            if (hex.length < 6) return null
            return "${hex.substring(0, 2)}:${hex.substring(2, 4)}:${hex.substring(4, 6)}"
        }
    }
}
