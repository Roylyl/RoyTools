package com.github.kr328.clash.service.util

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.appwidget.AppWidgetManager
import android.os.Bundle
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.core.Clash
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
            putString(KEY_MODE, "default")
            putBoolean(KEY_READY, false)
            putLong(KEY_WIFI_BYTES, usage.wifi)
            putLong(KEY_MOBILE_BYTES, usage.mobile)
            putString(KEY_USAGE_DAY, usage.day)
        }
        if (!running) return snapshot

        val override = runCatching { Clash.queryOverride(Clash.OverrideSlot.Session) }.getOrNull()
        snapshot.putString(KEY_MODE, when (override?.mode) {
            TunnelState.Mode.Rule -> "rule"
            TunnelState.Mode.Global -> "global"
            TunnelState.Mode.Direct -> "direct"
            else -> "default"
        })
        val effective = runCatching { Clash.queryTunnelState().mode }.getOrNull() ?: return snapshot
        snapshot.putString(KEY_EFFECTIVE_MODE, effective.name.lowercase())
        snapshot.putBoolean(KEY_READY, true)
        runCatching { selectedNode(effective) }.getOrNull()?.let { (group, node) ->
            snapshot.putString(KEY_NODE_GROUP, group)
            snapshot.putString(KEY_NODE, node)
        }
        return snapshot
    }

    @Synchronized
    fun setMode(context: Context, value: String?): Bundle {
        val mode = when (value) {
            "default" -> null
            "rule" -> TunnelState.Mode.Rule
            "global" -> TunnelState.Mode.Global
            "direct" -> TunnelState.Mode.Direct
            else -> return failure("invalid_mode")
        }
        if (!StatusProvider.serviceRunning) return failure("stopped")
        return try {
            // Preserve the other session overrides and all persisted selector choices.
            val override = Clash.queryOverride(Clash.OverrideSlot.Session)
            if (override.mode != mode) {
                override.mode = mode
                Clash.patchOverride(Clash.OverrideSlot.Session, override)
                context.sendOverrideChanged()
            }
            query(context).apply {
                putBoolean(KEY_SUCCESS, true)
                publish(context, this)
            }
        } catch (_: Exception) {
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

    private fun selectedNode(mode: TunnelState.Mode): Pair<String?, String>? {
        if (mode == TunnelState.Mode.Direct) return null to "DIRECT"
        val groups = Clash.queryGroupNames(false)
        val root = if (mode == TunnelState.Mode.Global) groups.firstOrNull { it == "GLOBAL" }
            else groups.firstOrNull { it != "GLOBAL" }
        if (root == null) return null
        val known = groups.toHashSet()
        val visited = hashSetOf(root)
        var node = Clash.queryGroup(root, ProxySort.Default).now
        while (node in known && visited.size < 16 && visited.add(node)) {
            val next = Clash.queryGroup(node, ProxySort.Default).now
            if (next.isBlank()) break
            node = next
        }
        return node.takeIf { it.isNotBlank() && it !in known }?.let { root to it }
    }
}
