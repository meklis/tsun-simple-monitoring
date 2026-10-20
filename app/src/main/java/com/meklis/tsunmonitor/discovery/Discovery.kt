package com.meklis.tsunmonitor.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.meklis.tsunmonitor.protocol.EmsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import kotlin.coroutines.resume

data class Found(val host: String, val port: Int, val serial: String?, val source: String)

/**
 * Finds inverters on the local network two ways:
 *  - mDNS: service `_http._tcp`, instance name `SXD-mDNS…`, TXT s_sn / s_ip / s_port;
 *  - scan of the phone's subnet on port 8080 (40 parallel, 3 s timeouts) with an
 *    EnergyParameter probe.
 */
class Discovery(private val context: Context) {

    fun scan(): Flow<Found> = channelFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tsun-discovery").apply { setReferenceCounted(false); acquire() }
        // Older NsdManager resolves one service at a time; queue them.
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                Log.d(TAG, "mDNS service: ${info.serviceName}")
                if (info.serviceName.contains("SXD", ignoreCase = true)) toResolve.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS started")
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS start failed $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        val resolver = launch {
            for (info in toResolve) {
                val si = resolve(nsd, info) ?: continue
                val attrs = si.attributes.mapValues { it.value?.toString(Charsets.UTF_8) }
                @Suppress("DEPRECATION")
                val host = attrs["s_ip"] ?: si.host?.hostAddress ?: continue
                val port = attrs["s_port"]?.toIntOrNull() ?: si.port
                Log.d(TAG, "mDNS resolved ${si.serviceName} -> $host:$port $attrs")
                send(Found(host, port, attrs["s_sn"], "mDNS"))
            }
        }
        try {
            subnetScan { send(it) }
            delay(3_000) // give late mDNS answers a moment
        } finally {
            resolver.cancel()
            runCatching { nsd.stopServiceDiscovery(listener) }
            lock.release()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS resolve failed ${si.serviceName}: $errorCode")
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(si)
                }
            })
        }

    private suspend fun subnetScan(onFound: suspend (Found) -> Unit) {
        val (own, prefix) = localIpv4() ?: return
        Log.d(TAG, "subnet scan from $own/$prefix")
        scanHosts(Subnet.ownHosts(own, prefix) - own, onFound)
    }

    private suspend fun scanHosts(hosts: List<String>, onFound: suspend (Found) -> Unit) = withContext(Dispatchers.IO) {
        val gate = Semaphore(PARALLEL)
        coroutineScope {
            hosts.map { host ->
                async {
                    gate.withPermit {
                        probe(host, EmsSession.DEFAULT_PORT)?.let { sn ->
                            Log.d(TAG, "scan found $host sn=$sn")
                            onFound(Found(host, EmsSession.DEFAULT_PORT, sn.ifEmpty { null }, "scan"))
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * One connection per attempt: a short connect timeout weeds out empty addresses;
     * a host that accepted but did not answer (busy with another client) gets one retry.
     */
    private suspend fun probe(host: String, port: Int): String? {
        repeat(2) { attempt ->
            try {
                return EmsSession(host, port, connectTimeoutMs = TIMEOUT_MS, readTimeoutMs = TIMEOUT_MS).use { s ->
                    s.energyParameter().optJSONArray("Storage_list")?.optJSONObject(0)?.optString("StorageSN") ?: ""
                }
            } catch (e: java.net.SocketTimeoutException) {
                if (e.message?.contains("connect", ignoreCase = true) == true) return null
                Log.d(TAG, "$host accepted but no answer (attempt ${attempt + 1})")
            } catch (e: java.net.ConnectException) {
                return null
            } catch (e: java.net.NoRouteToHostException) {
                return null
            } catch (e: IOException) {
                Log.d(TAG, "$host: ${e.message}")
            } catch (e: Exception) {
                return null // not an EMS device
            }
            delay(1_500)
        }
        return null
    }

    /** Own IPv4 address and prefix length of the active network, e.g. 192.168.1.73/24. */
    fun ownNetwork(): String? = localIpv4()?.let { (ip, prefix) -> "$ip/$prefix" }

    private fun localIpv4(): Pair<String, Int>? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return la.address.hostAddress!! to la.prefixLength
    }

    private companion object {
        const val TAG = "TsunDiscovery"
        const val PARALLEL = 40
        const val TIMEOUT_MS = 3_000
    }
}
