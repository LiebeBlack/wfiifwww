package com.example.wifiscanner.ui

import android.content.Context
import android.content.Intent
import com.example.wifiscanner.model.DiscoverySource
import com.example.wifiscanner.model.NetworkDevice

/**
 * Exports a finished scan so the user can keep it, mail it or paste it into a
 * ticket: [buildCsv] is pure and testable, [share] hands the CSV to the
 * system share sheet (text-only, so no file permissions or providers are
 * needed).
 */
object ScanExport {

    private val HEADER = listOf(
        "ip",
        "mac",
        "vendor",
        "hostname",
        "type",
        "location",
        "services",
        "ports",
        "detected_via",
    )

    /** Comma-separated rows, one device per line, with a header row. */
    fun buildCsv(devices: List<NetworkDevice>): String {
        val rows = ArrayList<String>(devices.size + 1)
        rows.add(HEADER.joinToString(","))
        for (device in devices) {
            val fields = listOf(
                device.ip,
                device.mac.orEmpty(),
                device.vendor.orEmpty(),
                device.hostname.orEmpty(),
                device.kind.label,
                device.location.orEmpty(),
                device.services.joinToString(" "),
                device.openPorts.sorted().joinToString(" "),
                DiscoverySource.labels(device.sources),
            )
            rows.add(fields.joinToString(",") { escape(it) })
        }
        return rows.joinToString("\n")
    }

    /** Opens the system share sheet with the CSV of [devices]. */
    fun share(context: Context, devices: List<NetworkDevice>) {
        if (devices.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Wi-Fi LAN scan: ${devices.size} devices")
            putExtra(Intent.EXTRA_TEXT, buildCsv(devices))
        }
        val chooser = Intent.createChooser(intent, "Share scan results")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /**
     * RFC 4180 quoting: a field is wrapped in quotes only when it contains a
     * separator, a quote or a line break, and inner quotes are doubled.
     */
    private fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
