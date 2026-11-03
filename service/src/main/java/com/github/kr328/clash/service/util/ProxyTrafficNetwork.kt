package com.github.kr328.clash.service.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.core.Clash
import java.util.TimeZone

/** Uses physical Internet networks only; a VPN's copied Wi-Fi/cellular transports are not evidence. */
internal object ProxyTrafficNetwork {
    fun isUnderlying(capabilities: NetworkCapabilities?): Boolean = capabilities != null &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    fun typeOf(capabilities: NetworkCapabilities?): Int = when {
        capabilities == null || !isUnderlying(capabilities) -> Clash.TRAFFIC_NETWORK_OTHER
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Clash.TRAFFIC_NETWORK_WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Clash.TRAFFIC_NETWORK_MOBILE
        else -> Clash.TRAFFIC_NETWORK_OTHER
    }

    // Shared with NetworkObserveModule so startup classification and later DNS selection agree.
    fun priority(capabilities: NetworkCapabilities?): Int = when {
        capabilities == null || !isUnderlying(capabilities) -> 100
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> 2
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 3
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 4
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> 5
        else -> 20
    }

    @Suppress("DEPRECATION")
    fun sync(context: Context) {
        val timeZone = TimeZone.getDefault()
        Clash.notifyTimeZoneChanged(timeZone.id, timeZone.getOffset(System.currentTimeMillis()) / 1000)
        val connectivity = context.getSystemService<ConnectivityManager>()
        val selected = connectivity?.allNetworks?.asSequence()
            ?.mapNotNull { connectivity.getNetworkCapabilities(it) }
            ?.filter(::isUnderlying)?.minByOrNull(::priority)
        Clash.setTrafficNetwork(typeOf(selected))
    }
}
