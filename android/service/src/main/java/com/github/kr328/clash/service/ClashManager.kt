package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel

internal val selectorLock = Any()

class ClashManager(private val context: Context) : IClashManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private var logReceiver: ReceiveChannel<LogMessage>? = null

    override fun queryTunnelState(): TunnelState {
        return Clash.queryTunnelState()
    }

    override fun queryTrafficTotal(): Long {
        return Clash.queryTrafficTotal()
    }
    override fun queryTrafficNow(): Long = Clash.queryTrafficNow()

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String> {
        return Clash.queryGroupNames(excludeNotSelectable).ifEmpty {
            Clash.queryGroup("GLOBAL",ProxySort.Default).proxies.filter{it.isGroup && (!excludeNotSelectable || it.type=="Selector")}.map{it.name}
        }
    }

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup {
        return Clash.queryGroup(name, proxySort)
    }

    override fun queryConfiguration(): UiConfiguration {
        return Clash.queryConfiguration()
    }

    override fun queryProviders(): ProviderList {
        return ProviderList(Clash.queryProviders())
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return Clash.queryOverride(slot)
    }

    override fun patchSelector(group: String, name: String): Boolean = synchronized(selectorLock) {
        val current = store.activeProfile ?: return@synchronized false
        if (name.isEmpty() || !Clash.patchSelector(group, name)) return@synchronized false
        SelectionDao().setSelected(Selection(current, group, name))
        true
    }

    override fun querySelectedProxyNames(): List<String> = synchronized(selectorLock) {
        store.activeProfile?.let { id -> SelectionDao().querySelections(id).map { it.selected } }.orEmpty()
    }

    override fun resetProxySelections(): Boolean = synchronized(selectorLock) {
        val current = store.activeProfile ?: return@synchronized false
        val selections = SelectionDao()
        val groups = selections.querySelections(current).map { it.proxy }
        selections.clearSelections(current)
        for (group in groups) {
            Clash.patchSelector(group, "")
        }
        true
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        if (slot == Clash.OverrideSlot.Session && configuration.routingPreset in setOf("ai", "roylyl")) {
            store.activeProfile?.let { id ->
                BundledRoutingRules.install(context, java.io.File(context.filesDir, "imported/$id"))
            }
        }
        Clash.patchOverride(slot, configuration)
        if(slot==Clash.OverrideSlot.Session)com.github.kr328.clash.service.util.ProxyModePreferences.save(context,configuration)

        context.sendOverrideChanged()
    }

    override fun clearOverride(slot: Clash.OverrideSlot) {
        Clash.clearOverride(slot)
        context.sendOverrideChanged()
    }

    override suspend fun healthCheck(group: String) {
        return Clash.healthCheck(group).await()
    }

    override suspend fun updateProvider(type: Provider.Type, name: String) {
        return Clash.updateProvider(type, name).await()
    }

    override fun setLogObserver(observer: ILogObserver?) {
        synchronized(this) {
            logReceiver?.apply {
                cancel()

                Clash.forceGc()
            }

            if (observer != null) {
                logReceiver = Clash.subscribeLogcat().also { c ->
                    launch {
                        try {
                            while (isActive) {
                                observer.newItem(c.receive())
                            }
                        } catch (e: CancellationException) {
                            // intended behavior
                            // ignore
                        } catch (e: Exception) {
                            Log.w("UI crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()

                                Clash.forceGc()
                            }
                        }
                    }
                }
            }
        }
    }
}
