package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*

object ProxyModePreferences {
    fun choice(config:ConfigurationOverride):String = when(config.routingPreset){
        "roylyl"->"rule";"music"->"music";"ai"->"ai"
        else->when(config.mode){TunnelState.Mode.Rule->"rule";TunnelState.Mode.Global->"global";TunnelState.Mode.Direct->"direct";else->"default"}
    }
    fun save(c:Context,config:ConfigurationOverride){c.getSharedPreferences("proxy_mode",Context.MODE_PRIVATE).edit().putString("choice",choice(config)).apply()}
    fun read(c:Context)=c.getSharedPreferences("proxy_mode",Context.MODE_PRIVATE).getString("choice","default").orEmpty()
    fun apply(c:Context,config:ConfigurationOverride,choice:String){
        RoutingPresets.clear(config)
        if(choice in listOf("rule","music","ai")){
            val groups=Clash.queryGroupNames(false).ifEmpty{Clash.queryGroup("GLOBAL",ProxySort.Default).proxies.filter{it.isGroup}.map{it.name}}
            val target=groups.firstOrNull{it!="GLOBAL" && it!="DIRECT" && it!="REJECT"}
                ?: Clash.queryGroup("GLOBAL",ProxySort.Default).now
            if(choice=="rule")BundledRoutingRules.apply(c,config,target,groups)
            else RoutingPresets.apply(config,choice,target)
        }
        if(choice=="global"){
            val global=Clash.queryGroup("GLOBAL",ProxySort.Default)
            if(global.now in listOf("DIRECT","REJECT"))global.proxies.firstOrNull{it.isGroup && it.name!="GLOBAL"}?.let{Clash.patchSelector("GLOBAL",it.name)}
        }
        config.mode=when(choice){"rule","music","ai"->TunnelState.Mode.Rule;"global"->TunnelState.Mode.Global;"direct"->TunnelState.Mode.Direct;else->null}
    }
}
