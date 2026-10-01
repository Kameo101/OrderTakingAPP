package com.ordertaking.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

const val KITCHEN_PORT = 8765
private const val SERVICE_TYPE = "_ordertaking._tcp."
private const val TAG = "Discovery"

/** IPv4 addresses of this tablet on the local network, for showing on the kitchen screen. */
fun localIpAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
}.getOrDefault(emptyList())

/**
 * Fallback for networks where mDNS discovery doesn't work (common on phone hotspots):
 * try every address on this tablet's local network for an open kitchen port.
 * Only the /24 around our own address is scanned, which covers phone hotspots.
 */
suspend fun scanForKitchen(): String? {
    val targets = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && SKIP_INTERFACES.none { p -> it.name.startsWith(p) } }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address && it.networkPrefixLength in 16..30 }
            .flatMap { ia ->
                val own = ia.address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                subnetHosts(own, ia.networkPrefixLength.toInt())
            }
            .distinct()
            .map(::ipToString)
    }.getOrDefault(emptyList())
    return probeForKitchen(targets)
}

/** Other host addresses in the /24 (or smaller) network around [own]. */
internal fun subnetHosts(own: Int, prefixLength: Int): List<Int> {
    val prefix = maxOf(prefixLength, 24)
    val base = own and (-1 shl (32 - prefix))
    return (1 until (1 shl (32 - prefix)) - 1).map { base + it }.filter { it != own }
}

internal fun ipToString(ip: Int) = "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

/** First of [targets] with [port] open, checking up to 64 at a time. */
internal suspend fun probeForKitchen(targets: List<String>, port: Int = KITCHEN_PORT): String? = coroutineScope {
    if (targets.isEmpty()) return@coroutineScope null
    val gate = Semaphore(64)
    val probes = targets.map { ip ->
        async(Dispatchers.IO) {
            gate.withPermit {
                runCatching {
                    Socket().use { it.connect(InetSocketAddress(ip, port), 400) }
                    ip
                }.getOrNull()
            }
        }
    }
    val found = probes.firstNotNullOfOrNull { it.await() }
    probes.forEach { it.cancel() }
    found
}

private val SKIP_INTERFACES = listOf("rmnet", "ccmni", "dummy", "tun", "ppp")

/** Announces the kitchen on the Wi-Fi so cashier tablets can find it without typing an IP. */
class KitchenAdvertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun start() {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "OrderTaking Kitchen"
            serviceType = SERVICE_TYPE
            port = KITCHEN_PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Advertising as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "Advertise failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        listener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "registerService", it); listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }
}

/** Looks for the kitchen on the Wi-Fi and reports its IP address. */
class KitchenFinder(context: Context, private val onFound: (String?) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed: $errorCode")
                listener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) = onFound(null)
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "discoverServices", it); listener = null }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host
                    if (host is Inet4Address) onFound(host.hostAddress)
                }
            })
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }
}
