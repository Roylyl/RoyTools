package com.github.kr328.clash.service.util

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.appwidget.AppWidgetManager
import android.os.Bundle
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Small IPC snapshots; all native core access stays in the proxy process. */
object ProxyWidgetState {
    const val KEY_RUNNING = "running"
    const val KEY_ACTIVE_PROFILE = "activeProfile"
    const val KEY_MODE = "mode"
    const val KEY_EFFECTIVE_MODE = "effectiveMode"
    const val KEY_NODE = "node"
    const val KEY_NODE_GROUP = "nodeGroup"
    const val KEY_READY = "ready"
    const val KEY_SUCCESS = "success"
    const val KEY_ERROR = "error"
    const val KEY_WIFI_BYTES = "wifiBytes"
    const val KEY_MOBILE_BYTES = "mobileBytes"
    const val KEY_USAGE_DAY = "usageDay"

    val ACTION_CHANGED: String
        get() = "${Global.application.packageName}.intent.action.PROXY_WIDGET_CHANGED"

    fun hasWidgets(context: Context): Boolean = AppWidgetManager.getInstance(context).getAppWidgetIds(
        ComponentName(context.packageName, "com.github.kr328.clash.widget.ProxyWidgetProvider")
    ).isNotEmpty()

    fun query(context: Context): Bundle {
        val running = StatusProvider.serviceRunning
        val usage = ProxyUsageStore.readSnapshot(context)
        val snapshot = Bundle().apply {
            putBoolean(KEY_RUNNING, running)
            putString(KEY_ACTIVE_PROFILE, if (running) StatusProvider.currentProfile else selectedProfile(context))
            putString(KEY_MODE, ProxyModePreferences.read(context))
            putBoolean(KEY_READY, false)
            putLong(KEY_WIFI_BYTES, usage.wifi)
            putLong(KEY_MOBILE_BYTES, usage.mobile)
            putString(KEY_USAGE_DAY, usage.day)
        }
        if (!running || StatusProvider.currentProfile == null) return snapshot

        val override = runCatching { Clash.queryOverride(Clash.OverrideSlot.Session) }.getOrNull()
        if (override != null) snapshot.putString(KEY_MODE, ProxyModePreferences.choice(override))
        val effective = runCatching { Clash.queryTunnelState().mode }.getOrNull() ?: return snapshot
        snapshot.putString(KEY_EFFECTIVE_MODE, effective.name.lowercase())
        snapshot.putBoolean(KEY_READY, true)
        runCatching { selectedNode(effective, override?.mode == TunnelState.Mode.Global) }.getOrNull()?.let { (group, node) ->
            snapshot.putString(KEY_NODE_GROUP, group)
            snapshot.putString(KEY_NODE, node)
        }
        return snapshot
    }

    @Synchronized
    fun setMode(context: Context, value: String?): Bundle {
        val choice = value?.takeIf { it in ProxyModePreferences.choices } ?: return failure("invalid_mode")
        if (!StatusProvider.serviceRunning) return failure("stopped")
        if (StatusProvider.currentProfile == null) return failure("not_ready")
        return try {
            // Preserve the other session overrides and all persisted selector choices.
            val override = Clash.queryOverride(Clash.OverrideSlot.Session)
            if (ProxyModePreferences.choice(override) != choice || choice == "rule") {
                ProxyModePreferences.apply(context, override, choice)
                Clash.patchOverride(Clash.OverrideSlot.Session, override)
                ProxyModePreferences.save(context, choice)
                context.sendOverrideChanged()
            }
            query(context).apply {
                putBoolean(KEY_SUCCESS, true)
                publish(context, this)
            }
        } catch (error: Exception) {
            Log.w("桌面小组件切换代理模式失败", error)
            failure("unavailable")
        }
    }

    fun publish(context: Context, snapshot: Bundle = query(context)) {
        if (!hasWidgets(context)) return
        context.sendBroadcastSelf(Intent(ACTION_CHANGED).putExtras(snapshot))
    }

    private fun failure(error: String) = Bundle().apply {
        putBoolean(KEY_SUCCESS, false)
        putString(KEY_ERROR, error)
    }

    private fun selectedProfile(context: Context): String? = runCatching {
        val uuid = ServiceStore(context).activeProfile ?: return@runCatching null
        runBlocking(Dispatchers.IO) { ImportedDao().queryByUUID(uuid)?.name }
    }.getOrNull()

    private fun selectedNode(mode: TunnelState.Mode, proxyOnly: Boolean): Pair<String?, String>? {
        if (mode == TunnelState.Mode.Direct) return null to "DIRECT"
        if (mode != TunnelState.Mode.Global) return null to "按规则分流"
        val root = "GLOBAL"
        val known = Clash.queryGroupNames(false).toHashSet()
        val visited = hashSetOf(root)
        var node = Clash.queryGroup(root, ProxySort.Default).now
        while (node in known && visited.size < 16 && visited.add(node)) {
            val next = Clash.queryGroup(node, ProxySort.Default).now
            if (next.isBlank()) break
            node = next
        }
        val usableName = node.isNotBlank() && node != "?" && node !in known
        if (proxyOnly && (!usableName || node in Proxy.nonProxyRoutes)) return root to "自动"
        return node.takeIf { usableName }?.let { root to it }
    }
}
