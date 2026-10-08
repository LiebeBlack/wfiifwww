package com.example.wifiscanner.net

import android.content.Context
import com.example.wifiscanner.model.NetworkDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates one full LAN scan and emits incremental results as a [Flow].
 *
 * Pipeline per scan:
 *  1. Detect the Wi-Fi subnet ([LocalNetworkInfo.detect]) — error if none.
 *  2. Emit whatever the ARP table already knows (instant partial results).
 *  3. Run the ICMP sweep; as hosts answer, re-read ARP and emit merged
 *     device lists so the UI fills in live.
 *  4. In parallel, kick off an mDNS discovery pass and, per device, a
 *     reverse-DNS lookup + OUI vendor lookup; emit again with hostnames.
 *
 * The flow never completes on its own until the scan is finished, and
 * cancellation (user leaves the screen) stops the sweep cleanly.
 */
class NetworkScanner(context: Context) {

    private val appContext = context.applicationContext
    private val hostnameResolver = HostnameResolver(appContext)
    private val oui = OuiDatabase.load(appContext)

    /** True when the bundled OUI table loaded (else vendors show "Unknown"). */
    val hasOuiData: Boolean = oui.lookup("B8:27:EB") != null // Raspberry Pi prefix

    /**
     * Emits progressive device lists over the course of a single scan.
     *
     * Emission 1 is immediate (ARP snapshot); later emissions add devices
     * found by the sweep and hostnames found by mDNS/rDNS.
     */
    fun scan(): Flow<List<NetworkDevice>> = flow {
        if (!LocalNetworkInfo.isOnWifi(appContext)) {
            throw ScanException(ScanError.WifiOff)
        }
        val subnet = LocalNetworkInfo.detect(appContext)
            ?: throw ScanException(ScanError.NotOnWifi)
        val selfIp = LocalNetworkInfo.selfIp(subnet)

        // Mutable "current truth" keyed by IP, merged across phases.
        val devices = LinkedHashMap<String, NetworkDevice>()

        // Helper: fold an ARP snapshot into the device map and emit.
        suspend fun emitMerged() {
            val snapshot = PingSweep.arpSnapshot()
            for ((ip, entry) in snapshot) {
                if (ip !in devices && !subnetContains(subnet, ip)) continue
                val existing = devices[ip]
                devices[ip] = (existing ?: NetworkDevice(ip = ip, mac = null)).copy(
                    mac = entry.mac ?: existing?.mac,
                    vendor = oui.lookup(entry.mac) ?: existing?.vendor,
                    isSelf = ip == selfIp || existing?.isSelf == true,
                )
            }
            emit(devices.values.sortedBy { it.ip.toLongOrMax() })
        }

        // ---- Phase 1: instant ARP-based discovery ----------------------
        emitMerged()

        // ---- Phase 2: mDNS discovery (runs alongside the sweep) --------
        coroutineScope {
            val mdnsJob = async(Dispatchers.IO) { hostnameResolver.discoverMdns() }

            // ---- Phase 3: ICMP sweep, streaming results ----------------
            // The sweep's callback fires concurrently from worker coroutines,
            // so results go into a thread-safe set rather than `devices`.
            val aliveIps = ConcurrentHashMap<String, Unit>()
            PingSweep.sweep(subnet) { aliveIp -> aliveIps[aliveIp] = Unit }
            for (ip in aliveIps.keys) {
                devices.getOrPut(ip) { NetworkDevice(ip = ip, mac = null) }
            }

            // Collect mDNS results into the device map.
            for ((ip, name) in mdnsJob.await()) {
                devices[ip]?.let { devices[ip] = it.copy(hostname = name) }
                    ?: run { devices[ip] = NetworkDevice(ip = ip, mac = null, hostname = name) }
            }
        }

        // ---- Phase 4: reverse DNS + finalize vendors --------------------
        coroutineScope {
            val lookups = devices.values
                .filter { it.hostname == null }
                .map { dev ->
                    async(Dispatchers.IO) { dev.ip to hostnameResolver.reverseDns(dev.ip) }
                }
            for (deferred in lookups) {
                val (ip, host) = deferred.await()
                if (host != null) devices[ip]?.let { devices[ip] = it.copy(hostname = host) }
            }
        }

        // Final emission with hostnames resolved.
        emitMerged()
    }.flowOn(Dispatchers.IO)

    /** Clears cached mDNS names (call when the user changes networks). */
    fun reset() = hostnameResolver.clearCache()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun subnetContains(subnet: LocalNetworkInfo.Subnet, ip: String): Boolean =
        subnet.hostAddresses().contains(ip) || ip == subnet.networkAddress

    private fun String.toLongOrMax(): Long =
        split('.').mapNotNull { it.toLongOrNull() }
            .takeIf { it.size == 4 }
            ?.fold(0L) { acc, o -> acc * 256 + o }
            ?: Long.MAX_VALUE

    /** Typed scan failure for the UI to render. */
    class ScanException(val error: ScanError) : Exception(error.name)

    enum class ScanError { WifiOff, NotOnWifi, PermissionDenied, Unknown }
}
