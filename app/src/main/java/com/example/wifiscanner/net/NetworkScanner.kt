package com.example.wifiscanner.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.example.wifiscanner.model.DiscoverySource
import com.example.wifiscanner.model.NetworkDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates one full LAN scan and emits incremental results as a [Flow].
 *
 * The design goal is **never to depend on a single technique**: Android
 * versions differ wildly in what they allow an unprivileged app to do, so
 * every strategy is independent, individually guarded, and simply skipped
 * when the platform blocks it. Nothing here throws unless there is genuinely
 * no network to scan.
 *
 * Discovery strategies, in the order they run:
 *
 *  1. **ARP / neighbour table** (instant, no packets) — richest data (MAC +
 *     vendor), but hidden on Android 10+. When it is hidden the reader simply
 *     returns nothing: the UI never warns about it, because the other
 *     strategies recover the same information.
 *  2. **SSDP / UPnP M-SEARCH** (multicast) — routers, TVs, consoles, printers,
 *     NAS and IoT announce themselves with a device class. Works with no
 *     subnet knowledge and no MAC address, so it is the safety net when ARP
 *     and ICMP are both blocked.
 *  3. **WS-Discovery** (SOAP Probe over multicast) — the protocol Windows uses
 *     for "Network" in the Explorer: modern PCs, printers, scanners, ONVIF
 *     cameras, NAS and IP phones answer with their types, scopes and URLs.
 *  4. **Raw mDNS / DNS-SD** (multicast, own parser) — service types, hostnames
 *     and model strings, independent of the platform `NsdManager`.
 *  5. **NetBIOS NBSTAT** (UDP broadcast) — machine names *and MAC addresses*
 *     from Windows/legacy devices: the only way to recover a MAC when Android
 *     hides the ARP table.
 *  6. **ICMP + TCP sweep** of the subnet (needs a subnet) — catches everything
 *     else, including hosts with no services at all (neighbour priming), and
 *     fingerprints the open service ports.
 *  7. **mDNS via the platform NsdManager** — friendly names, when available.
 *  8. **Reverse DNS** for the hosts that still have no name.
 *
 * Each phase emits a [ScanUpdate] so the UI fills in progressively and can
 * tell the user which strategy is running right now.
 */
class NetworkScanner(context: Context) {

    /** One progressive emission of a scan. */
    data class ScanUpdate(
        /** Human-readable phase, e.g. "SSDP/UPnP…". */
        val phase: String,
        /** Everything discovered so far, sorted by IP. */
        val devices: List<NetworkDevice>,
        /**
         * Non-fatal advisory: the scan ran in a degraded mode (no subnet
         * detected, ...). Rendered as a banner, never as an error.
         */
        val note: String? = null,
    )

    private val appContext = context.applicationContext
    private val hostnameResolver = HostnameResolver(appContext)
    private val ssdp = SsdpDiscovery(appContext)
    private val wsd = WsDiscovery(appContext)
    private val mdns = MdnsDiscovery(appContext)
    private val netbios = NetBiosDiscovery(appContext)
    // Lazy: the table is ~150 KB of CSV, so it is read on the scan's IO thread
    // instead of during ViewModel creation on the main thread.
    private val oui by lazy { OuiDatabase.load(appContext) }

    /** True when the bundled OUI table loaded (else vendors show "Desconocido"). */
    val hasOuiData: Boolean by lazy { oui.lookup("B8:27:EB") != null } // Raspberry Pi

    /**
     * Emits progressive device lists over the course of a single scan.
     *
     * Only [ScanError.WifiOff] / [ScanError.NotOnWifi] can abort a scan, and
     * only when *nothing at all* was discovered — every other failure is
     * reported as a [ScanUpdate.note] so the user always sees a result list.
     */
    fun scan(): Flow<ScanUpdate> = flow {
        val devices = LinkedHashMap<String, NetworkDevice>()
        val notes = LinkedHashSet<String>()

        // ---- Environment ---------------------------------------------------
        val subnet = runCatching { LocalNetworkInfo.detect(appContext) }.getOrNull()
        val selfIp = subnet?.let { s -> runCatching { LocalNetworkInfo.selfIp(s) }.getOrNull() }
        // The default gateway: the one device whose role is certain, so the
        // classifier can name it router instead of guessing from open ports.
        val gatewayIp = runCatching { LocalNetworkInfo.gatewayAddress(appContext) }.getOrNull()
        // Exactly the addresses that will be probed: 1..254 on a /24.
        val hostCount = subnet?.let { s -> s.hostAddresses().count() } ?: 0
        val arp = runCatching { ArpTableReader.readWithStatus() }
            .getOrElse { ArpTableReader.ArpReadResult.Unavailable }

        suspend fun publish(phase: String) {
            this@flow.emit(
                ScanUpdate(
                    phase = phase,
                    devices = devices.values.sortedBy { it.ip.toSortKey() },
                    note = notes.takeIf { it.isNotEmpty() }?.joinToString(" "),
                )
            )
        }

        fun merge(
            ip: String,
            source: DiscoverySource? = null,
            service: String? = null,
            services: List<String>? = null,
            hostname: String? = null,
            mac: String? = null,
            ports: Set<Int>? = null,
            models: List<String>? = null,
            vendor: String? = null,
        ) {
            val base = devices[ip] ?: NetworkDevice(ip = ip, mac = null)
            devices[ip] = base.copy(
                mac = mac ?: base.mac,
                // First name wins: SSDP's friendly name is the nicest, then
                // mDNS, then NetBIOS, and reverse DNS only fills blanks.
                hostname = base.hostname ?: hostname,
                vendor = vendor ?: base.vendor,
                isSelf = base.isSelf || ip == selfIp,
                // Every discovery path funnels through here, so the gateway
                // IP is tagged exactly once no matter which method found it.
                isGateway = base.isGateway || ip == gatewayIp,
                sources = if (source != null) base.sources + source else base.sources,
                services = (base.services + listOfNotNull(service) + services.orEmpty()).distinct(),
                openPorts = base.openPorts + ports.orEmpty(),
                models = (base.models + models.orEmpty()).distinct(),
            )
        }

        publish(phase = "Preparando el análisis…")

        if (subnet == null) notes += NOTE_NO_SUBNET

        // The phone itself is part of the network: listing it first makes the
        // results complete and gives the user a reference point for "this
        // device" labels. Build.MODEL is the only friendly name available
        // without extra permissions.
        if (selfIp != null) {
            merge(
                ip = selfIp,
                source = DiscoverySource.Self,
                hostname = Build.MODEL?.takeIf { it.isNotBlank() },
            )
        }

        // ---- Phase 1: whatever the neighbour cache already knows ------------
        if (arp is ArpTableReader.ArpReadResult.Ok) {
            for ((ip, entry) in arp.entries) {
                if (!inSubnet(subnet, ip)) continue
                merge(ip = ip, source = DiscoverySource.Arp, mac = entry.mac)
            }
        }
        publish(phase = "Revisando la caché local…")

        // ---- Phases 2-4: multicast + active sweep, in parallel --------------
        var ssdpResults: Map<String, SsdpDiscovery.SsdpResponse> = emptyMap()
        var wsdResults: Map<String, WsDiscovery.WsdResponse> = emptyMap()
        var mdnsNames: Map<String, String> = emptyMap()
        var mdnsAnswers: Map<String, MdnsDiscovery.MdnsAnswer> = emptyMap()
        var netbiosResults: Map<String, NetBiosDiscovery.NbstatResult> = emptyMap()

        coroutineScope {
            val ssdpJob = async(Dispatchers.IO) { safeSsdp() }
            val wsdJob = async(Dispatchers.IO) { safeWsd() }
            val mdnsJob = async(Dispatchers.IO) { safeMdns() }
            val mdnsRawJob = async(Dispatchers.IO) { safeMdnsRaw() }
            val netbiosJob = async(Dispatchers.IO) { safeNetbios(subnet) }

            if (subnet != null) {
                val basePhase =
                    "Sondeando ${subnet.networkAddress}/${subnet.prefixLength} ($hostCount direcciones)"
                publish(phase = "$basePhase…")
                // The sweep runs in a worker while this coroutine keeps merging
                // whatever has answered so far: the list fills in live during
                // the ~30 s a /24 takes instead of staying frozen until the
                // very end (the callback fires on worker threads, so the
                // arrival detection happens here, not inside the callback).
                val alive = ConcurrentHashMap<String, PingSweep.HostResult>()
                val merged = HashSet<String>()
                val sweepJob = async(Dispatchers.IO) {
                    PingSweep.sweep(subnet) { host -> alive[host.ip] = host }
                }
                // Merges every host that has arrived since the last call.
                fun drainAlive(): Boolean {
                    var added = false
                    for (host in alive.values) {
                        if (merged.add(host.ip)) {
                            merge(ip = host.ip, source = host.source, ports = host.openPorts)
                            added = true
                        }
                    }
                    return added
                }
                while (sweepJob.isActive) {
                    if (drainAlive()) {
                        publish(phase = "$basePhase · ${merged.size} respuestas…")
                    }
                    // Wake up as soon as the sweep ends, or every 500 ms to
                    // show the hosts found meanwhile.
                    withTimeoutOrNull(SWEEP_POLL_MILLIS) { sweepJob.join() }
                }
                sweepJob.await()
                drainAlive()
                // The sweep forced the kernel to resolve every live host, so a
                // second read now recovers MAC addresses (and therefore OUI
                // vendors) that were missing before the probes. Merged only
                // for devices that actually answered, so stale entries cannot
                // add phantom hosts.
                val primed = runCatching { ArpTableReader.readWithStatus() }.getOrNull()
                if (primed is ArpTableReader.ArpReadResult.Ok) {
                    for ((ip, entry) in primed.entries) {
                        if (devices.containsKey(ip)) {
                            merge(ip = ip, source = DiscoverySource.Arp, mac = entry.mac)
                        }
                    }
                }
                publish(phase = "Clasificando los dispositivos…")
            } else {
                publish(phase = "Buscando anuncios de red…")
            }

            // Await the parallel jobs here; the flow itself stays single-coroutine.
            ssdpResults = ssdpJob.await()
            wsdResults = wsdJob.await()
            mdnsNames = mdnsJob.await()
            mdnsAnswers = mdnsRawJob.await()
            netbiosResults = netbiosJob.await()
        }

        // ---- Phase 5: SSDP / UPnP responders -------------------------------
        // Only the short device class is shown ("InternetGatewayDevice"); the
        // full URNs stay on the model for diagnostics.
        for ((ip, response) in ssdpResults) {
            merge(
                ip = ip,
                source = DiscoverySource.Ssdp,
                service = response.kind,
                hostname = response.friendlyName,
                vendor = response.manufacturer,
                models = listOfNotNull(response.model),
            )
        }
        publish(phase = "Anuncios SSDP/UPnP…")

        // ---- Phase 6: WS-Discovery (the Windows "Network" view) -------------
        for ((ip, response) in wsdResults) {
            merge(ip = ip, source = DiscoverySource.Wsd, service = response.kind)
        }
        publish(phase = "Dispositivos WS-Discovery…")

        // ---- Phase 7: mDNS / DNS-SD service names ---------------------------
        for ((ip, name) in mdnsNames) {
            merge(ip = ip, source = DiscoverySource.Mdns, hostname = name)
        }
        publish(phase = "Nombres de servicio mDNS…")

        // ---- Phase 8: raw mDNS answers (service types, names, models) --------
        for ((ip, answer) in mdnsAnswers) {
            merge(
                ip = ip,
                source = DiscoverySource.Mdns,
                hostname = answer.hostname,
                services = answer.services,
                models = answer.models,
            )
        }
        publish(phase = "Servicios mDNS…")

        // ---- Phase 9: NetBIOS answers (machine name, MAC without ARP) -------
        for ((ip, result) in netbiosResults) {
            val alreadyHasMac = devices[ip]?.mac != null
            merge(
                ip = ip,
                source = DiscoverySource.Netbios,
                hostname = result.machineName,
                mac = if (alreadyHasMac) null else result.mac,
            )
        }
        publish(phase = "Nombres NetBIOS…")

        // ---- Phase 10: reverse DNS for hosts that still have no name --------
        val unnamed = devices.values.filter { it.hostname == null && !it.isSelf }
        if (unnamed.isNotEmpty()) {
            publish(phase = "Resolviendo ${unnamed.size} nombres…")
            coroutineScope {
                val lookups = unnamed.map { device ->
                    async(Dispatchers.IO) { device.ip to safeRdns(device.ip) }
                }
                for (lookup in lookups) {
                    val (ip, hostname) = lookup.await()
                    if (hostname != null) merge(ip = ip, source = DiscoverySource.Rdns, hostname = hostname)
                }
            }
        }

        // ---- Finalize: offline OUI vendor lookup ---------------------------
        // A manufacturer advertised over UPnP is more precise than the OUI
        // owner (which is often the chipset vendor), so OUI only fills blanks.
        for ((ip, device) in devices.entries.toList()) {
            if (device.vendor != null) continue
            val mac = device.mac ?: continue
            val vendor = oui.lookup(mac) ?: continue
            devices[ip] = device.copy(vendor = vendor)
        }

        publish(phase = "Listo")

        // ---- The only hard stop: nothing found and no Wi-Fi ----------------
        // Note that a missing subnet is *not* a reason to fail: multicast-only
        // discovery is a legitimate degraded mode. The error is chosen from
        // the actual Wi-Fi state, not from subnet detection.
        if (devices.isEmpty()) {
            val wifiEnabled = runCatching {
                (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
            }.getOrNull()
            val onWifi = runCatching { LocalNetworkInfo.isOnWifi(appContext) }.getOrDefault(false)
            if (!onWifi) {
                // Wi-Fi off *and* no other LAN (Ethernet/hotspot) to scan is a
                // real failure; Wi-Fi off with Ethernet connected is not.
                if (wifiEnabled == false && subnet == null) {
                    throw ScanException(ScanError.WifiOff)
                }
                throw ScanException(ScanError.NotOnWifi)
            }
            // On Wi-Fi with nothing answering is a valid empty result, not an
            // error: the list simply shows the empty state.
        }
    }.flowOn(Dispatchers.IO)

    /** Clears cached mDNS names (call when the user changes networks). */
    fun reset() = hostnameResolver.clearCache()

    // ------------------------------------------------------------------
    // Guarded strategy wrappers: a blocked method must never fail a scan
    // ------------------------------------------------------------------

    private suspend fun safeSsdp(): Map<String, SsdpDiscovery.SsdpResponse> = try {
        ssdp.discover()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }

    private suspend fun safeMdns(): Map<String, String> = try {
        hostnameResolver.discoverMdns()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }

    private suspend fun safeWsd(): Map<String, WsDiscovery.WsdResponse> = try {
        wsd.discover()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }

    private suspend fun safeMdnsRaw(): Map<String, MdnsDiscovery.MdnsAnswer> = try {
        mdns.discover()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }

    private suspend fun safeNetbios(
        subnet: LocalNetworkInfo.Subnet?,
    ): Map<String, NetBiosDiscovery.NbstatResult> = try {
        netbios.discover(subnet?.broadcastAddress() ?: NetBiosDiscovery.GLOBAL_BROADCAST)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }

    private suspend fun safeRdns(ip: String): String? = try {
        hostnameResolver.reverseDns(ip)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** True when [ip] belongs to [subnet] (or when no subnet is known). */
    private fun inSubnet(subnet: LocalNetworkInfo.Subnet?, ip: String): Boolean {
        if (subnet == null) return true
        val address = LocalNetworkInfo.ipv4ToInt(ip) ?: return false
        val base = LocalNetworkInfo.ipv4ToInt(subnet.networkAddress) ?: return false
        val prefix = subnet.prefixLength.coerceIn(0, 32)
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (address and mask) == (base and mask)
    }

    /** Compact numeric key so 192.168.1.2 sorts before 192.168.1.10. */
    private fun String.toSortKey(): Long =
        split('.').mapNotNull { it.toLongOrNull() }
            .takeIf { it.size == 4 }
            ?.fold(0L) { acc, octet -> acc * 256 + octet }
            ?: Long.MAX_VALUE

    /** Typed scan failure for the UI to render. */
    class ScanException(val error: ScanError) : Exception(error.name)

    enum class ScanError { WifiOff, NotOnWifi, PermissionDenied, Unknown }

    companion object {
        private const val NOTE_NO_SUBNET =
            "No se pudo determinar la subred local, así que se omitió el barrido de " +
                "direcciones; los dispositivos se buscaron mediante anuncios multicast " +
                "(SSDP/mDNS)."

        /** How often the scan reports the sweep's progress while it runs. */
        private const val SWEEP_POLL_MILLIS = 500L
    }
}
