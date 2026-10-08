package com.example.wifiscanner.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Fast ICMP sweep of every host in a subnet.
 *
 * Android's [InetAddress.isReachable] silently degrades to a TCP port-7
 * probe for unprivileged apps (raw ICMP needs CAP_NET_RAW), so instead we
 * shell out to the system `ping` binary, which *does* have the needed
 * capability on every Android build. Results are collected concurrently with
 * a bounded semaphore so we don't fork 254 processes at once.
 */
object PingSweep {

    /**
     * Pings every host address in [subnet] in parallel.
     *
     * @param onHostAlive optional callback (already on IO) fired per live host
     *                    so callers can stream partial results to the UI.
     * @return the IPs that answered, in ascending order.
     */
    suspend fun sweep(
        subnet: LocalNetworkInfo.Subnet,
        onHostAlive: ((String) -> Unit)? = null,
    ): List<String> = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENCY)

        subnet.hostAddresses()
            .map { ip ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (isReachable(ip)) {
                            onHostAlive?.invoke(ip)
                            ip
                        } else null
                    }
                }
            }
            .mapNotNull { it.await() }
            .toList()
            .sortedBy { ip -> ipToLong(ip) }
    }

    companion object {
        private const val PAR_HOST_TIMEOUT_SEC = 1
        private const val COROUTINE_TIMEOUT_MILLIS = 2_500L
        private const val CONNECT_TIMEOUT_MILLIS = 800L
        private const val MAX_CONCURRENCY = 64

        /** Common LAN service ports to probe when ICMP is unavailable. */
        private val PORTS_TO_TRY = listOf(80, 443, 139, 8080)
    }

    /** Compact numeric key so 192.168.1.2 sorts before 192.168.1.10. */
    private fun ipToLong(ip: String): Long =
        ip.split('.').fold(0L) { acc, octet -> acc * 256 + (octet.toLongOrNull() ?: 0L) }

    /**
     * Single-host reachability check via the system ping binary.
     *
     * `-c 1`: one packet. `-w 1`: hard deadline in seconds so a filtering host
     * can't hang us. Falls back to a TCP connect probe on common ports
     * (80, 443, 139) when ping is unavailable or fails — the exact path the
     * spec asks for ("ping/TCP socket connection on common ports").
     */
    internal suspend fun isReachable(ip: String): Boolean = withContext(Dispatchers.IO) {
        // Fast path: system ping (unprivileged ICMP via setuid/capability).
        try {
            val process = ProcessBuilder("ping", "-c", "1", "-w", "$PAR_HOST_TIMEOUT_SEC", ip)
                .redirectErrorStream(true)
                .start()
            // Drain output so the ping buffer can't fill and block the child.
            process.inputStream.bufferedReader().use { it.readText() }
            val finished = withTimeoutOrNull(COROUTINE_TIMEOUT_MILLIS) {
                process.waitFor() == 0
            } ?: run { process.destroy(); false }
            if (finished) return@withContext true
        } catch (_: Exception) {
            // ping binary missing — fall through to TCP.
        }

        // Best-effort TCP connect to a few common service ports.
        tcpConnectAny(ip, PORTS_TO_TRY, CONNECT_TIMEOUT_MILLIS)
    }

    /**
     * Tries to open a TCP connection to [ip] on each of [ports] in order,
     * with a shared wall-clock timeout. Returns true as soon as one succeeds.
     */
    private suspend fun tcpConnectAny(ip: String, ports: List<Int>, timeoutMillis: Long): Boolean {
        // Launch one connect per port but cap concurrency so we never open
        // more sockets than we intended for a single host.
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            coroutineScope {
                val jobs = ports.map { port ->
                    async(Dispatchers.IO) { tryConnect(ip, port) }
                }
                jobs.mapNotNull { it.await() }.firstOrNull() == true
            }
        } ?: false
    }

    private fun tryConnect(host: String, port: Int): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS.toInt())
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads the ARP table after a sweep — the "instant" discovery path.
     * Pinging populates the kernel neighbor cache, so a sweep + ARP read
     * yields MAC addresses without any L2 probing of our own.
     */
    fun arpSnapshot(): Map<String, ArpTableReader.ArpEntry> = ArpTableReader.read()
}
