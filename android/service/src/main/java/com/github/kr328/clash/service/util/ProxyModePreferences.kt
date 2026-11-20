package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.core.model.*

object ProxyModePreferences {
    fun choice(config:ConfigurationOverride):String = when(config.routingPreset){
        "roylyl"->"rule";"music"->"music";"ai"->"ai"
        else->when(config.mode){TunnelState.Mode.Rule->"rule";TunnelState.Mode.Global->"global";TunnelState.Mode.Direct->"direct";else->"default"}
    }
    internal val choices = setOf("default", "rule", "music", "ai", "global", "direct")

    fun save(c: Context, config: ConfigurationOverride) = save(c, choice(config))

    fun save(c: Context, choice: String) {
        require(choice in choices)
        c.getSharedPreferences("proxy_mode", Context.MODE_PRIVATE).edit().putString("choice", choice).apply()
    }
    fun read(c:Context)=c.getSharedPreferences("proxy_mode",Context.MODE_PRIVATE).getString("choice","default").orEmpty()
    fun apply(context: Context, config: ConfigurationOverride, choice: String) {
        val preset = when (choice) {
            "rule" -> "roylyl"
            "music", "ai" -> choice
            else -> null
        }
        if (preset != null) RoutingPresets.apply(context, config, preset) else RoutingPresets.clear(config)
        config.mode = when (choice) {
            "rule", "music", "ai" -> TunnelState.Mode.Rule
            "global" -> TunnelState.Mode.Global
            "direct" -> TunnelState.Mode.Direct
            else -> null
        }
    }
}
