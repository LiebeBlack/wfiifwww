package com.example.wifiscanner.model

/**
 * Which discovery strategy found (or confirmed) a device.
 *
 * The scanner is deliberately multi-strategy: no single technique works on
 * every Android build, so a device is usually confirmed by several of these
 * and [NetworkDevice.sources] keeps the whole set. The UI renders the labels
 * so the user can see *why* a device is in the list.
 *
 *  - [Arp]  — read from the kernel neighbour table (`/proc/net/arp`). Hidden
 *             on Android 10+, so it is never required.
 *  - [Icmp] — the host answered a system `ping` probe.
 *  - [Tcp]  — the host accepted a TCP connection on one of the common LAN
 *             service ports (brute-force port sweep). *  - [Ssdp]    — the host answered an SSDP/UPnP M-SEARCH (multicast).
 *  - [Wsd]     — the host answered a WS-Discovery Probe: the protocol Windows
 *                itself uses for "Network" in the Explorer.
 *  - [Mdns]    — a DNS-SD/mDNS query was answered by this address.
 *  - [Netbios] — the host answered a NetBIOS NBSTAT query (legacy/Windows,
 *                and the only non-ARP method that also returns a MAC).
 *  - [Rdns]    — a reverse-DNS (PTR) lookup returned a name for this address.
 *  - [Self]    — this is the scanning device itself.
 */
enum class DiscoverySource(val label: String) {
    Self("this device"),
    Arp("ARP"),
    Netbios("NetBIOS"),
    Icmp("ICMP"),
    Tcp("TCP"),
    Ssdp("SSDP"),
    Wsd("WSD"),
    Mdns("mDNS"),
    Rdns("DNS");

    companion object {
        /** Display order: fastest / strongest evidence first. */
        private val ORDER = listOf(Self, Arp, Netbios, Icmp, Tcp, Ssdp, Wsd, Mdns, Rdns)

        /**
         * The given [sources] in display order, so summaries and labels are
         * stable across recompositions instead of following Set iteration.
         */
        fun ordered(sources: Collection<DiscoverySource>): List<DiscoverySource> =
            ORDER.filter { it in sources }

        /**
         * Human-readable, de-duplicated and ordered labels for [sources],
         * e.g. `"ARP · SSDP"`. Empty string when nothing is known.
         */
        fun labels(sources: Set<DiscoverySource>): String =
            ordered(sources).joinToString(" · ") { it.label }
    }
}
