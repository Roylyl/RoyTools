package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.net.*
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.util.asSocketAddressText
import com.github.kr328.clash.service.util.ProxyTrafficNetwork
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class NetworkObserveModule(service: Service) : Module<Network?>(service) {
    private val connectivity = service.getSystemService<ConnectivityManager>()!!
    private val networks: Channel<Network?> = Channel(Channel.UNLIMITED)
    @Volatile private var observing = false
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
        }
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }.build()

    private data class NetworkInfo(
        @Volatile var losingMs: Long = 0,
        @Volatile var dnsList: List<InetAddress> = emptyList(),
        @Volatile var capabilities: NetworkCapabilities? = null,
    ) {
        fun isAvailable(): Boolean = losingMs < System.currentTimeMillis()
    }

    private val networkInfos = ConcurrentHashMap<Network, NetworkInfo>()

    @Volatile
    private var curDnsList = emptyList<String>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!observing) return
            Log.i("NetworkObserve onAvailable network=$network")
            networkInfos[network] = NetworkInfo(
                dnsList = connectivity.getLinkProperties(network)?.dnsServers ?: emptyList(),
                capabilities = connectivity.getNetworkCapabilities(network),
            )
            networks.trySend(notifyDnsChange())
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            if (!observing) return
            Log.i("NetworkObserve onLosing network=$network")
            networkInfos[network]?.losingMs = System.currentTimeMillis() + maxMsToLive
            networks.trySend(notifyDnsChange())
        }

        override fun onLost(network: Network) {
            if (!observing) return
            Log.i("NetworkObserve onLost network=$network")
            networkInfos.remove(network)
            networks.trySend(notifyDnsChange())
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (!observing) return
            Log.i("NetworkObserve onLinkPropertiesChanged network=$network $linkProperties")
            networkInfos[network]?.dnsList = linkProperties.dnsServers
            networks.trySend(notifyDnsChange())
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!observing) return
            networkInfos[network]?.capabilities = capabilities
            networks.trySend(notifyDnsChange())
        }

        override fun onUnavailable() {
            if (!observing) return
            Log.i("NetworkObserve onUnavailable")
            networkInfos.clear()
            networks.trySend(notifyDnsChange())
        }
    }

    private fun register(): Boolean {
        Log.i("NetworkObserve start register")
        return try {
            connectivity.registerNetworkCallback(request, callback)

            true
        } catch (e: Exception) {
            Log.w("NetworkObserve register failed", e)

            false
        }
    }

    private fun unregister(): Boolean {
        Log.i("NetworkObserve start unregister")
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w("NetworkObserve unregister failed", e)
        }

        return false
    }

    private fun networkToInt(entry: Map.Entry<Network, NetworkInfo>): Int {
        return ProxyTrafficNetwork.priority(capabilities(entry)) + (if (entry.value.isAvailable()) 0 else 10)
    }

    private fun capabilities(entry: Map.Entry<Network, NetworkInfo>) =
        entry.value.capabilities ?: connectivity.getNetworkCapabilities(entry.key)

    @Synchronized
    private fun notifyDnsChange(): Network? {
        if (!observing) return null
        val selected = networkInfos.asSequence()
            .filter { ProxyTrafficNetwork.isUnderlying(capabilities(it)) }
            .minByOrNull { networkToInt(it) }
        Clash.setTrafficNetwork(ProxyTrafficNetwork.typeOf(selected?.let { capabilities(it) }))
        val dnsList = (selected?.value?.dnsList
            ?: emptyList()).map { x -> x.asSocketAddressText(53) }
        val prevDnsList = curDnsList
        if (prevDnsList != dnsList) {
            Log.i("notifyDnsChange $prevDnsList -> $dnsList")
            curDnsList = dnsList
            Clash.notifyDnsChanged(dnsList)
        }
        return selected?.key
    }

    override suspend fun run() {
        observing = true
        register()

        try {
            for (network in networks) enqueueEvent(network)
        } finally {
            synchronized(this) { observing = false }
            withContext(NonCancellable) {
                unregister()
                networkInfos.clear()
                Clash.setTrafficNetwork(Clash.TRAFFIC_NETWORK_OTHER)

                Log.i("NetworkObserve dns = []")
                Clash.notifyDnsChanged(emptyList())
            }
        }
    }
}
