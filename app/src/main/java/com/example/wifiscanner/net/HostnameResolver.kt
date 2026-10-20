package com.example.wifiscanner.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
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
        "_matter._tcp",
        "_nanoleafapi._tcp",
        "_workstation._tcp",
        "_ssh._tcp",
        "_afpovertcp._tcp",
        "_companion-link._tcp",
        "_device-info._tcp",
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
        // Concurrent on purpose: a resolve that is still in flight when the
        // timeout fires can call back *after* this function returned, and the
        // caller iterates the returned map — a plain HashMap would then throw
        // ConcurrentModificationException on the scanner thread.
        val discovered = ConcurrentHashMap<String, String>(16)
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { cont ->
                val pending = linkedSetOf<String>().apply { addAll(interestingServices) }
                if (pending.isEmpty()) {
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }

                // One listener per service type: NsdManager refuses to reuse a
                // single listener for several discoveries ("listener already
                // in use"). Sharing one listener used to leave every type but
                // the first permanently deaf.
                val listeners = LinkedHashMap<String, NsdManager.DiscoveryListener>(pending.size)
                val lock = Any()
                var stopped = false
                var completed = false
                // API 34+ resolve callbacks registered but not answered yet.
                // A resolve still pending when this pass ends (timeout, stop
                // button, network change) would otherwise stay registered in
                // the system service and keep this resolver alive.
                val pendingResolves = CopyOnWriteArrayList<NsdManager.ServiceInfoCallback>()

                fun stopAll() {
                    val snapshot = synchronized(lock) {
                        if (stopped) {
                            emptyList()
                        } else {
                            stopped = true
                            listeners.values.toList()
                        }
                    }
                    snapshot.forEach { listener ->
                        try {
                            manager.stopServiceDiscovery(listener)
                        } catch (_: Exception) {
                            // Already stopped or never registered: nothing to do.
                        }
                    }
                    // Release the resolves that never delivered. Copy first:
                    // an answer arriving concurrently removes itself from the
                    // list and must not see it half-cleared.
                    val resolving = pendingResolves.toList()
                    pendingResolves.clear()
                    resolving.forEach { callback ->
                        try {
                            manager.unregisterServiceInfoCallback(callback)
                        } catch (_: Exception) {
                            // Never registered or already released.
                        }
                    }
                }

                /** Claims completion exactly once, then releases every listener. */
                fun completeIfDone() {
                    val shouldComplete = synchronized(lock) {
                        if (completed || pending.isNotEmpty()) {
                            false
                        } else {
                            completed = true
                            true
                        }
                    }
                    if (!shouldComplete) return
                    stopAll()
                    if (cont.isActive) cont.resume(Unit)
                }

                fun finish(type: String?) {
                    val key = type ?: return
                    synchronized(lock) { pending.remove(key) }
                    completeIfDone()
                }

                for (type in interestingServices) {
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String?) { /* no-op */ }

                        override fun onServiceFound(info: NsdServiceInfo) {
                            // A found event can still arrive just after
                            // stopAll(); registering a resolve then would
                            // leave it registered with nobody to release it.
                            if (synchronized(lock) { stopped }) return
                            // Resolve each found service to get its host IP + name.
                            resolveService(info, pendingResolves) { resolvedIp, friendlyName ->
                                if (resolvedIp != null && friendlyName != null) {
                                    mdnsCache[resolvedIp] = friendlyName
                                    discovered[resolvedIp] = friendlyName
                                }
                            }
                        }

                        override fun onServiceLost(info: NsdServiceInfo) { /* cache keeps last-known */ }

                        override fun onDiscoveryStopped(serviceType: String?) = finish(type)

                        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) =
                            finish(type)

                        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) =
                            finish(type)
                    }
                    synchronized(lock) { listeners[type] = listener }
                    try {
                        manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                    } catch (_: Exception) {
                        finish(type)
                    }
                }

                // Timeout/cancellation (stop button, network change): release
                // every registration so no discovery outlives the scan.
                cont.invokeOnCancellation { stopAll() }
            }
        }
        return discovered
    }

    /**
     * Resolves a single discovered service to its host IP + display name.
     *
     * Android 14 (API 34) deprecated the one-shot [NsdManager.resolveService]
     * listener in favour of [NsdManager.registerServiceInfoCallback], which
     * resolves the service and then streams updates until unregistered. The
     * first update (or the service disappearing) completes this one-shot
     * request; older devices keep the legacy listener — the only one that
     * exists below API 34.
     */
    private fun resolveService(
        info: NsdServiceInfo,
        /** Collects API 34+ callbacks so the caller can release them. */
        pendingResolves: MutableList<NsdManager.ServiceInfoCallback>?,
        onResolved: (ip: String?, name: String?) -> Unit,
    ) {
        val manager = nsdManager ?: run {
            onResolved(null, null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = OneShotServiceInfoCallback(manager, onResolved, pendingResolves)
            try {
                // Direct executor: callbacks arrive on the system's handler
                // thread — exactly where the legacy ResolveListener ran.
                pendingResolves?.add(callback)
                manager.registerServiceInfoCallback(info, Executor { it.run() }, callback)
            } catch (_: Exception) {
                callback.onRegistrationAborted()
            }
        } else {
            resolveViaLegacyListener(manager, info, onResolved)
        }
    }

    /** API < 34: the original one-shot resolve (deprecated from API 34 on). */
    @Suppress("DEPRECATION") // below API 34 this is the only one-shot resolver
    private fun resolveViaLegacyListener(
        manager: NsdManager,
        info: NsdServiceInfo,
        onResolved: (ip: String?, name: String?) -> Unit,
    ) {
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    onResolved(serviceHostAddress(resolved), nameOf(resolved))
                }

                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                    onResolved(null, null)
                }
            })
        } catch (_: Exception) {
            onResolved(null, null)
        }
    }

    /**
     * One-shot wrapper around the API 34+ [NsdManager.ServiceInfoCallback]:
     * the first resolved update (or the service being lost) delivers the
     * result and unregisters, so the continuous callback behaves exactly like
     * the single-shot resolve it replaced. Delivery happens at most once, so a
     * late update or a double failure can never call back twice.
     */
    private inner class OneShotServiceInfoCallback(
        private val manager: NsdManager,
        private val onResolved: (ip: String?, name: String?) -> Unit,
        /** Registration list owned by the discovery pass; null outside one. */
        private val pendingResolves: MutableList<NsdManager.ServiceInfoCallback>?,
    ) : NsdManager.ServiceInfoCallback {
        private val delivered = AtomicBoolean(false)

        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
            if (!delivered.compareAndSet(false, true)) return
            unregisterQuietly()
            onResolved(serviceHostAddress(serviceInfo), nameOf(serviceInfo))
        }

        override fun onServiceLost() {
            if (delivered.compareAndSet(false, true)) {
                unregisterQuietly()
                onResolved(null, null)
            }
        }

        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
            // The system already dropped the registration, so there is nothing
            // to unregister — only to stop tracking it.
            pendingResolves?.remove(this)
            if (delivered.compareAndSet(false, true)) onResolved(null, null)
        }

        override fun onServiceInfoCallbackUnregistered() {
            // Unregistering happens exactly once, right before delivering.
        }

        /** Registration never reached the system (synchronous failure). */
        fun onRegistrationAborted() {
            pendingResolves?.remove(this)
            if (delivered.compareAndSet(false, true)) onResolved(null, null)
        }

        private fun unregisterQuietly() {
            pendingResolves?.remove(this)
            try {
                manager.unregisterServiceInfoCallback(this)
            } catch (_: Exception) {
                // Already unregistered / never registered: nothing to do.
            }
        }
    }

    /** Display name of a resolved service (service name, else its type). */
    private fun nameOf(service: NsdServiceInfo): String? =
        service.serviceName?.takeIf { it.isNotBlank() } ?: service.serviceType

    /**
     * Address of a resolved service. `getHost()` was deprecated in API 34 in
     * favour of `getHostAddresses()`, which does not exist on older devices,
     * so both paths are kept and only the legacy one is suppressed.
     */
    @Suppress("DEPRECATION") // the only accessor below API 34
    private fun serviceHostAddress(service: NsdServiceInfo): String? {
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.hostAddresses.firstOrNull()
        } else {
            service.host
        }
        return address?.hostAddress
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
