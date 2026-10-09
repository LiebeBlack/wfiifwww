package com.example.wifiscanner.net

import com.example.wifiscanner.model.DiscoverySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Active reachability sweep of every host in a subnet.
 *
 * Three probes are tried per host, in increasing order of intrusiveness, so a
 * host that ignores one still gets found by the next:
 *
 *  1. **Neighbour priming** — a tiny UDP datagram to a few ports. It needs no
 *     reply at all: the *kernel* resolves the MAC to send it, which fills the
 *     neighbour cache and gives an ARP-only host a chance to be detected.
 *  2. **ICMP** via the system `ping` binary. Android's
 *     [InetAddress.isReachable] silently degrades to a TCP port-7 probe for
 *     unprivileged apps (raw ICMP needs CAP_NET_RAW), so we shell out to
 *     `ping`, which *does* have the needed capability.
 *  3. **TCP brute force** — a parallel connect attempt across the common LAN
 *     service ports, for hosts that answer neither UDP nor ICMP. The ports
 *     that accept become the device's fingerprint.
 *
 * Every probe is individually guarded and cancellation-aware: a failing
 * method skips to the next one, and stopping the scan is never swallowed.
 */
object PingSweep {

    /**
     * A host that answered, plus the probe that proved it alive and the TCP
     * fingerprint gathered along the way.
     */
    data class HostResult(
        val ip: String,
        val source: DiscoverySource,
        val openPorts: Set<Int> = emptySet(),
    )

    /**
     * Probes every host address in [subnet] in parallel.
     *
     * @param onHostAlive optional callback (already on IO) fired per live host
     *                    so callers can stream partial results to the UI.
     * @return the hosts that answered, in ascending IP order.
     */
    suspend fun sweep(
        subnet: LocalNetworkInfo.Subnet,
        onHostAlive: ((HostResult) -> Unit)? = null,
    ): List<HostResult> = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENCY)

        subnet.hostAddresses()
            .toList() // eager: Sequence.map would defer the suspend bodies
            .map { ip ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        probe(ip)?.also { result -> onHostAlive?.invoke(result) }
                    }
                }
            }
            .mapNotNull { it.await() }
            .sortedBy { result -> result.ip.toSortKey() }
    }

    /**
     * Decides whether [ip] is alive, how we learned it, and which TCP ports
     * answered (a free device fingerprint that feeds the classifier).
     *
     * @return the host result, or null when no probe produced evidence.
     */
    internal suspend fun probe(ip: String): HostResult? = withContext(Dispatchers.IO) {
        primeNeighborCache(ip)
        val answeredPing = pingOnce(ip)
        // The port scan runs in both cases: even a ping-reachable host is worth
        // fingerprinting, and for a ping-blocked host it is the only proof.
        val openPorts = tcpScan(ip, PORTS_TO_TRY, TCP_WINDOW_MILLIS)
        when {
            answeredPing -> HostResult(ip, DiscoverySource.Icmp, openPorts)
            openPorts.isNotEmpty() -> HostResult(ip, DiscoverySource.Tcp, openPorts)
            else -> null
        }
    }

    /** Compact numeric key so 192.168.1.2 sorts before 192.168.1.10. */
    private fun String.toSortKey(): Long =
        split('.').fold(0L) { acc, octet -> acc * 256 + (octet.toLongOrNull() ?: 0L) }

    // ------------------------------------------------------------------
    // Probe 1: neighbour priming (UDP, no reply required)
    // ------------------------------------------------------------------

    /**
     * Sends a one-byte UDP datagram to a few well-known service ports.
     *
     * The datagram is irrelevant — sending it forces the *kernel* to resolve
     * the host's MAC address and install a complete neighbour entry. That is
     * exactly what an ARP read needs, and it is the only probe here that also
     * works for hosts which ignore ICMP *and* run no TCP services.
     */
    private suspend fun primeNeighborCache(ip: String) = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                val probe = byteArrayOf(0)
                val address = InetAddress.getByName(ip)
                for (port in PRIME_PORTS) {
                    try {
                        socket.send(DatagramPacket(probe, probe.size, address, port))
                    } catch (_: Exception) {
                        // Some addresses reject the send; the next port may work.
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No UDP socket available: skip priming, the other probes still run.
        }
    }

    // ------------------------------------------------------------------
    // Probe 2: ICMP through the system ping binary
    // ------------------------------------------------------------------

    /**
     * Single-host reachability check via the system ping binary.
     *
     * `-c 1`: one packet. `-w 1`: hard deadline in seconds so a filtering host
     * can't hang us. Returns false (rather than throwing) when the binary is
     * missing or the host never answers, so the caller falls back to TCP.
     */
    private suspend fun pingOnce(ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("ping", "-c", "1", "-w", "$PING_TIMEOUT_SEC", ip)
                .redirectErrorStream(true)
                .start()
            // Drain output so the ping buffer can't fill and block the child.
            process.inputStream.bufferedReader().use { it.readText() }
            val finished = withTimeoutOrNull(PING_WAIT_MILLIS) { process.waitFor() == 0 }
            if (finished == null) process.destroy()
            finished == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false // ping binary unavailable — the TCP probe takes over.
        }
    }

    // ------------------------------------------------------------------
    // Probe 3: parallel TCP connect brute force (reachability + fingerprint)
    // ------------------------------------------------------------------

    /**
     * Tries to open a TCP connection to [ip] on each of [ports] in parallel,
     * within one shared wall-clock window.
     *
     * @return every port that accepted the connection (empty when none did or
     *         when the window expired first) — a cheap OS/service fingerprint.
     */
    private suspend fun tcpScan(ip: String, ports: List<Int>, windowMillis: Long): Set<Int> {
        // The wall-clock window keeps a filtering host from stalling the sweep.
        return withTimeoutOrNull(windowMillis) {
            coroutineScope {
                val jobs = ports.map { port -> async(Dispatchers.IO) { port to tryConnect(ip, port) } }
                jobs.mapNotNull { job ->
                    val (port, open) = job.await()
                    port.takeIf { open }
                }.toSet()
            }
        } ?: emptySet()
    }

    private fun tryConnect(host: String, port: Int): Boolean = try {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS.toInt())
            true
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
                // Closing the probe socket is best-effort.
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Connection refused / filtered / timed out — try the next port.
        false
    }

    /**
     * Reads the ARP table after a sweep — the "instant" discovery path.
     * Pinging populates the kernel neighbor cache, so a sweep + ARP read
     * yields MAC addresses without any L2 probing of our own.
     */
    fun arpSnapshot(): Map<String, ArpTableReader.ArpEntry> = ArpTableReader.read()

    // ------------------------------------------------------------------
    // Tuning
    // ------------------------------------------------------------------

    private const val PING_TIMEOUT_SEC = 1
    private const val PING_WAIT_MILLIS = 2_500L
    private const val CONNECT_TIMEOUT_MILLIS = 400L
    private const val TCP_WINDOW_MILLIS = 1_200L
    private const val MAX_CONCURRENCY = 64

    /** Ports for the neighbour-priming datagrams (discard / DNS / SSDP). */
    private val PRIME_PORTS = listOf(9, 53, 1900)

    /**
     * Common LAN service ports for the TCP brute-force fallback. Kept to the
     * port classes that actually live on home networks (web UIs, SMB shares,
     * printers, Chromecast, iOS lockdown) so every host costs a bounded number
     * of sockets.
     */
    private val PORTS_TO_TRY = listOf(
        80,    // web UI (routers, cameras, NAS)
        443,   // HTTPS admin
        445,   // SMB (Windows, NAS)
        139,   // NetBIOS session service
        22,    // SSH (routers, Raspberry Pi)
        554,   // RTSP (IP cameras)
        8080,  // alternate HTTP
        8008,  // Chromecast
        9100,  // JetDirect printers
        62078, // iOS lockdown (iPhone / iPad)
    )
}
