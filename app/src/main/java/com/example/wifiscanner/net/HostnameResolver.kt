package com.example.wifiscanner.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Resolves human-readable hostnames for discovered IPs using two paths:
 *
 * 1. **Reverse DNS (rDNS)** — a PTR lookup via [InetAddress.getHostName].
 *    Runs off the main thread with a strict timeout because many LAN devices
 *    have no PTR record and the lookup would otherwise block for seconds.
 *
 * 2. **mDNS / DNS-SD** — asks the platform [NsdManager] to enumerate common
 *    service types (_googlecast, _smb, _printer, ...). When a service resolves
 *    we learn both a friendly name ("Chromecast- Living Room") and the target
 *    IP, giving us hostnames for devices that never answer PTR queries.
 */
class HostnameResolver(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Null on ROMs/builds without a DNS-SD service. Any mDNS work then
     * degrades to an empty result instead of taking the whole scan down.
     */
    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    /** IP -> hostname cache that survives across scans. */
    private val mdnsCache = ConcurrentHashMap<String, String>()

    /** Service types worth probing; each may reveal a device hostname. */
    private val interestingServices = listOf(
        "_googlecast._tcp",
        "_smb._tcp",
        "_http._tcp",
        "_https._tcp",
        "_ipp._tcp",
        "_printer._tcp",
        "_airplay._tcp",
        "_raop._tcp",
        "_spotify-connect._tcp",
        "_homekit._tcp",
        "_nanoleafapi._tcp",
        "_workstation._tcp",
    )

    // ------------------------------------------------------------------
    // Path 1: reverse DNS
    // ------------------------------------------------------------------

    /**
     * Best-effort reverse DNS lookup with a hard timeout.
     *
     * @return the PTR hostname (trailing dot stripped), or null if the lookup
     *         times out or the answer is just the IP echo.
     */
    suspend fun reverseDns(ip: String, timeoutMillis: Long = 1_500): String? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMillis) {
                try {
                    val host = InetAddress.getByName(ip).canonicalHostName
                    // getByName echoes the IP back when no PTR record exists.
                    if (host.isNullOrBlank() || host == ip || host.matches(IP_REGEX)) {
                        null
                    } else host.removeSuffix(".")
                } catch (_: Exception) {
                    null
                }
            }
        }

    // ------------------------------------------------------------------
    // Path 2: mDNS / DNS-SD discovery
    // ------------------------------------------------------------------

    /**
     * Runs one mDNS discovery pass over all [interestingServices] and fills
     * the IP -> hostname cache. Non-blocking overall: each service lookup is
     * capped, and failures are swallowed (most services simply won't exist).
     */
    suspend fun discoverMdns(timeoutMillis: Long = 4_000): Map<String, String> {
        val manager = nsdManager ?: return emptyMap()
        // Lives outside the timeout so partial results survive a timeout.
        val discovered = HashMap<String, String>(16)
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { cont ->
                val pending = interestingServices.toMutableSet()

                if (pending.isEmpty()) { cont.resume(Unit); return@suspendCancellableCoroutine }

                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String?) { /* no-op */ }

                    override fun onServiceFound(info: NsdServiceInfo) {
                        // Resolve each found service to get its host IP + name.
                        resolveService(info) { resolvedIp, friendlyName ->
                            if (resolvedIp != null && friendlyName != null) {
                                mdnsCache[resolvedIp] = friendlyName
                                synchronized(discovered) { discovered[resolvedIp] = friendlyName }
                            }
                        }
                    }

                    override fun onServiceLost(info: NsdServiceInfo) { /* cache keeps last-known */ }

                    override fun onDiscoveryStopped(serviceType: String?) = finishType(serviceType)

                    override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = finishType(serviceType)
                    override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = finishType(serviceType)

                    private fun finishType(serviceType: String?) {
                        synchronized(pending) {
                            pending.remove(serviceType)
                            if (pending.isEmpty() && cont.isActive) cont.resume(Unit)
                        }
                    }
                }

                // Kick off one discovery per service type.
                for (type in interestingServices) {
                    try {
                        manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                    } catch (_: Exception) {
                        synchronized(pending) {
                            pending.remove(type)
                            if (pending.isEmpty() && cont.isActive) cont.resume(Unit)
                        }
                    }
                }

                cont.invokeOnCancellation {
                    try { manager.stopServiceDiscovery(listener) } catch (_: Exception) {}
                }
            }
        }
        return discovered
    }

    /** Resolves a single discovered service to its host IP + display name. */
    private fun resolveService(
        info: NsdServiceInfo,
        onResolved: (ip: String?, name: String?) -> Unit,
    ) {
        val manager = nsdManager ?: run {
            onResolved(null, null)
            return
        }
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val ip = resolved.host?.hostAddress
                    val name = resolved.serviceName?.takeIf { it.isNotBlank() }
                        ?: resolved.serviceType
                    onResolved(ip, name)
                }

                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                    onResolved(null, null)
                }
            })
        } catch (_: Exception) {
            onResolved(null, null)
        }
    }

    /** Returns the cached mDNS name for [ip], if previously discovered. */
    fun cachedMdnsName(ip: String): String? = mdnsCache[ip]

    /**
     * Combined lookup: cached mDNS name first (instant), then reverse DNS.
     */
    suspend fun resolve(ip: String): String? {
        val cached = cachedMdnsName(ip)
        if (cached != null) return cached
        val viaRdns = reverseDns(ip)
        if (viaRdns != null) mdnsCache[ip] = viaRdns
        return viaRdns
    }

    /** Clears the mDNS cache (called when the user switches networks). */
    fun clearCache() = mdnsCache.clear()

    companion object {
        private const val TAG = "HostnameResolver"
        private val IP_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    }
}
