package com.example.wifiscanner.model

/**
 * Immutable domain model for a device discovered on the LAN.
 *
 * @param ip        IPv4 address, e.g. "192.168.1.23".
 * @param mac       Hardware address (uppercase, colon separated) from the ARP
 *                  table or a NetBIOS NBSTAT reply. Null when the sweep found
 *                  the host but no method could recover a hardware address.
 * @param vendor    Manufacturer resolved offline from the MAC OUI prefix.
 * @param hostname  Friendly name from mDNS, NetBIOS or reverse DNS.
 * @param isSelf    True for the Android device running the scanner.
 * @param location  User-defined physical label ("Living Room") persisted in
 *                  Room, keyed by MAC. Editable in the UI.
 * @param sources   Every strategy that saw this device (ARP, ICMP, TCP,
 *                  SSDP, mDNS, NetBIOS, DNS). Lets the UI explain how it was
 *                  found and makes degraded scans visible instead of silent.
 * @param services  Device classes announced over SSDP/mDNS (e.g.
 *                  "InternetGatewayDevice", "_googlecast._tcp") — useful
 *                  recognition data when no MAC is available.
 * @param openPorts TCP ports that answered — a cheap fingerprint that feeds
 *                  [DeviceKind.classify].
 * @param models    Human-readable model strings advertised over mDNS
 *                  ("model=Chromecast Ultra"), when a device exposes them.
 */
data class NetworkDevice(
    val ip: String,
    val mac: String?,
    val vendor: String? = null,
    val hostname: String? = null,
    val isSelf: Boolean = false,
    val location: String? = null,
    val sources: Set<DiscoverySource> = emptySet(),
    val services: List<String> = emptyList(),
    val openPorts: Set<Int> = emptySet(),
    val models: List<String> = emptyList(),
) {
    /** Stable identity for list diffs: prefer MAC (survives DHCP changes). */
    val stableId: String get() = mac ?: ip

    /** True when we know at least the L2/L3 identity. */
    val isIdentified: Boolean get() = mac != null

    /** Inferred device type from all the evidence we gathered. */
    val kind: DeviceKind
        get() = DeviceKind.classify(
            isSelf = isSelf,
            services = services,
            openPorts = openPorts,
            hostname = hostname,
            vendor = vendor,
        )

    /**
     * Best display name we have: hostname, then model, then the raw address.
     */
    val displayName: String get() = hostname ?: models.firstOrNull() ?: ip
}
