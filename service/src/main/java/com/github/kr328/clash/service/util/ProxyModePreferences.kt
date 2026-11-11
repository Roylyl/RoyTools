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
        config.routingTarget=c.getSharedPreferences("proxy_mode",Context.MODE_PRIVATE).getString("target_"+com.github.kr328.clash.service.store.ServiceStore(c).activeProfile,null)
        if(choice in listOf("rule","music","ai")){
            val groups=Clash.queryGroupNames(false).ifEmpty{Clash.queryGroup("GLOBAL",ProxySort.Default).proxies.filter{it.isGroup}.map{it.name}}
            val target=RoutingPresets.target(config,groups,Clash.queryGroup("GLOBAL",ProxySort.Default))
            if(choice=="rule")BundledRoutingRules.apply(c,config,target,groups)
            else RoutingPresets.apply(config,choice,target,if(choice=="ai")RoutingPresets.loadAI(c) else emptyList())
        }
        config.mode=when(choice){"rule","music","ai"->TunnelState.Mode.Rule;"global"->TunnelState.Mode.Global;"direct"->TunnelState.Mode.Direct;else->null}
    }
}
