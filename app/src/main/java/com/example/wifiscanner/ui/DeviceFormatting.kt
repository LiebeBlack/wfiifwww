package com.example.wifiscanner.ui

import com.example.wifiscanner.model.DeviceKind
import com.example.wifiscanner.model.DiscoverySource
import com.example.wifiscanner.model.NetworkDevice
import com.example.wifiscanner.net.NetworkStatistics

/**
 * Small, pure presentation helpers shared by the list, the detail sheet and
 * the export. Kept free of Android APIs so they can be unit-tested.
 */

/** Numeric IPv4 sort key so 192.168.1.2 sorts before 192.168.1.10. */
internal fun String.ipv4SortKey(): Long =
    split('.').mapNotNull { it.toLongOrNull() }
        .takeIf { it.size == 4 }
        ?.fold(0L) { acc, octet -> acc * 256 + octet }
        ?: Long.MAX_VALUE

/**
 * Case-insensitive match across everything the user can see about a device:
 * address, hardware address, name, brand, type, services and ports.
 */
internal fun NetworkDevice.matchesQuery(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    val haystack = buildString {
        append(ip.lowercase()).append(' ')
        mac?.let { append(it.lowercase()).append(' ') }
        vendor?.let { append(it.lowercase()).append(' ') }
        hostname?.let { append(it.lowercase()).append(' ') }
        location?.let { append(it.lowercase()).append(' ') }
        nickname?.let { append(it.lowercase()).append(' ') }
        append(kind.label.lowercase()).append(' ')
        // The UI shows Spanish type names, so searching them must work too.
        append(kind.displayName().lowercase()).append(' ')
        services.forEach { append(it.lowercase()).append(' ') }
        models.forEach { append(it.lowercase()).append(' ') }
        // Searching "ARP" or "mDNS" is a natural way to filter by evidence.
        sources.forEach { source -> append(source.label.lowercase()).append(' ') }
        openPorts.forEach { append(it).append(' ') }
    }
    return haystack.contains(needle)
}

/**
 * Spanish name of an inferred device type, for every surface that shows it.
 *
 * Kept in the presentation layer on purpose: [DeviceKind.label] stays the
 * stable, locale-independent identifier used by the CSV export and the tests.
 */
fun DeviceKind.displayName(): String = when (this) {
    DeviceKind.Self -> "Este dispositivo"
    DeviceKind.Router -> "Router / puerta de enlace"
    DeviceKind.Printer -> "Impresora"
    DeviceKind.Camera -> "Cámara"
    DeviceKind.Speaker -> "Altavoz"
    DeviceKind.Tv -> "Televisor"
    DeviceKind.MediaPlayer -> "Reproductor multimedia"
    DeviceKind.Nas -> "NAS / almacenamiento"
    DeviceKind.Phone -> "Móvil / tablet"
    DeviceKind.Computer -> "Computadora"
    DeviceKind.Iot -> "Dispositivo inteligente"
    DeviceKind.Unknown -> "Dispositivo no identificado"
}

/** Spanish label for a discovery method; protocol names stay as they are. */
fun DiscoverySource.displayLabel(): String = when (this) {
    DiscoverySource.Self -> "este dispositivo"
    else -> label
}

/** Spanish name of the transport carrying LAN traffic. */
fun NetworkStatistics.Transport.displayName(): String = when (this) {
    NetworkStatistics.Transport.Wifi -> "Wi-Fi"
    NetworkStatistics.Transport.Cellular -> "Datos móviles"
    NetworkStatistics.Transport.Ethernet -> "Ethernet"
    NetworkStatistics.Transport.Vpn -> "VPN"
    NetworkStatistics.Transport.Bluetooth -> "Bluetooth"
    NetworkStatistics.Transport.Other -> "Otra red"
}

/**
 * Spanish label for the few human-readable service classes that WS-Discovery
 * and SSDP return ("Computer", "Media player", ...). Protocol identifiers
 * such as `_googlecast._tcp` or `InternetGatewayDevice` stay untouched: they
 * are evidence, not prose.
 */
fun serviceLabel(raw: String): String = when (raw.trim()) {
    // WS-Discovery classes.
    "Computer" -> "Computadora"
    "Printer" -> "Impresora"
    "Scanner" -> "Escáner"
    "Camera" -> "Cámara"
    "Media player" -> "Reproductor multimedia"
    "Phone" -> "Teléfono"
    "Storage" -> "Almacenamiento"
    "NAS" -> "NAS / almacenamiento"
    // UPnP device classes (SSDP).
    "InternetGatewayDevice" -> "Router / puerta de enlace"
    "WANDevice" -> "Módem"
    "WANConnectionDevice" -> "Conexión WAN"
    "LANDevice" -> "Red local"
    "MediaRenderer" -> "Reproductor multimedia"
    "MediaServer" -> "Servidor multimedia"
    "BinaryLight" -> "Luz inteligente"
    "DimmableLight" -> "Luz regulable"
    "Thermostat" -> "Termostato"
    "Sensor" -> "Sensor"
    "Basic" -> "Dispositivo básico"
    else -> raw
}

/** Short quality word for a 0..4 signal bucket. */
fun signalQuality(level: Int?): String? = when (level) {
    null -> null
    4 -> "Excelente"
    3 -> "Buena"
    2 -> "Regular"
    1 -> "Débil"
    else -> "Muy débil"
}
