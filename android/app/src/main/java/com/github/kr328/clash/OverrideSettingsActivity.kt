package com.github.kr328.clash

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.UiConfiguration
import com.github.kr328.clash.core.model.pinLanSharing
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.LanSharingDesign
import com.github.kr328.clash.design.OverrideSettingsDesign
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.Inet4Address
import java.net.NetworkInterface

class OverrideSettingsActivity : BaseActivity<Design<*>>() {
    companion object {
        const val EXTRA_LAN_ONLY = "lan_only"
    }

    override suspend fun main() {
        if (intent.getBooleanExtra(EXTRA_LAN_ONLY, false)) {
            showLanSharing()
            return
        }

        val configuration = withClash { queryOverride(Clash.OverrideSlot.Persist) }
        defer {
            withClash {
                patchOverride(Clash.OverrideSlot.Persist, configuration)
            }
        }

        val design = OverrideSettingsDesign(this, configuration)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        OverrideSettingsDesign.Request.ResetOverride -> {
                            if (design.requestResetConfirm()) {
                                defer {
                                    withClash {
                                        clearOverride(Clash.OverrideSlot.Persist)
                                    }
                                }

                                finish()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun showLanSharing() {
        setTitle(com.github.kr328.clash.design.R.string.lan_sharing)
        val (persisted, effective) = withTimeout(10_000L) {
            withClash { queryOverride(Clash.OverrideSlot.Persist) to queryConfiguration() }
        }
        val desired = persisted.withFixedPort(effective).sharingSettings(fixed = persisted.mixedPort in 1..65535)
        val design = LanSharingDesign(this, desired, ::setLanSharing)
        design.setRuntimeState(clashRunning, effective)
        setContentDesign(design)
        launch {
            while (isActive) {
                if (activityStarted) design.setAddresses(readLanAddresses())
                delay(5_000L)
            }
        }
        while (isActive) {
            when (events.receive()) {
                Event.ActivityStart, Event.ClashStart, Event.ClashStop, Event.ProfileLoaded, Event.ServiceRecreated -> {
                    design.setRuntimeState(clashRunning)
                    if (clashRunning) {
                        try {
                            val runtime = withTimeout(5_000L) { withClash { queryConfiguration() } }
                            design.setRuntimeState(clashRunning, runtime.takeIf { clashRunning })
                        } catch (_: TimeoutCancellationException) {
                            // The status remains pending until the next successful service refresh.
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // Restart and load events request another snapshot.
                        }
                    }
                    if (activityStarted) design.setAddresses(readLanAddresses())
                }
                else -> Unit
            }
        }
    }

    private suspend fun setLanSharing(allowed: Boolean?, port: Int?): LanSharingDesign.Settings =
        withTimeout(10_000L) {
            withClash {
                val persisted = queryOverride(Clash.OverrideSlot.Persist)
                val effective = queryConfiguration()
                val updated = persisted.copy(
                    allowLan = allowed ?: persisted.allowLan,
                    mixedPort = port ?: persisted.mixedPort,
                ).withFixedPort(effective)
                patchOverride(Clash.OverrideSlot.Persist, updated)
                updated.sharingSettings(fixed = true)
            }
        }

    private fun ConfigurationOverride.withFixedPort(effective: UiConfiguration): ConfigurationOverride =
        copy().apply {
            pinLanSharing(effective)
            // An inherited loopback address is usable from other devices only after enabling the LAN listener.
            if (this@withFixedPort.bindAddress == null && bindAddress in setOf("127.0.0.1", "::1", "[::1]", "localhost")) {
                bindAddress = "*"
            }
        }

    private fun ConfigurationOverride.sharingSettings(fixed: Boolean) =
        LanSharingDesign.Settings(allowLan == true, checkNotNull(mixedPort), bindAddress.orEmpty(), fixed)

    private suspend fun readLanAddresses(): List<LanSharingDesign.Address> = withContext(Dispatchers.IO) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val wifiNames = runCatching {
            connectivity?.allNetworks.orEmpty().mapNotNull { network ->
                val capabilities = connectivity?.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    connectivity?.getLinkProperties(network)?.interfaceName
                } else null
            }.toSet()
        }.getOrDefault(emptySet())
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isPointToPoint &&
                    excludedInterfacePrefixes.none(it.name::startsWith) }
                .flatMap { device ->
                    val network = when {
                        device.name in wifiNames -> LanSharingDesign.Network.Wifi
                        hotspotInterfacePrefixes.any(device.name::startsWith) -> LanSharingDesign.Network.Hotspot
                        else -> LanSharingDesign.Network.Local
                    }
                    device.inetAddresses.toList().filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress || (network == LanSharingDesign.Network.Wifi && !it.isLinkLocalAddress) }
                        .mapNotNull { it.hostAddress?.let { ip -> LanSharingDesign.Address(ip, network) } }
                }
                .sortedWith(compareBy<LanSharingDesign.Address> { it.network.ordinal }.thenBy { it.ip })
                .distinctBy { it.ip }
        }.getOrDefault(emptyList())
    }

    private val excludedInterfacePrefixes = listOf("tun", "rmnet", "ccmni", "pdp", "p2p", "ipsec", "v4-rmnet")
    private val hotspotInterfacePrefixes = listOf("ap", "softap", "swlan", "hotspot")
}

