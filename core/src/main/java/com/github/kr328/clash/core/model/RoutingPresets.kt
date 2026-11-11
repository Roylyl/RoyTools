package com.github.kr328.clash.core.model

import android.content.Context
import kotlinx.serialization.json.*

/** Session-only replacement rules; the imported subscription remains untouched. */
object RoutingPresets {
    val musicDomains=listOf("roylyl.github.io","raw.githubusercontent.com")
    fun rules(preset:String,target:String,aiRules:List<String> = emptyList()):List<String> {
        require(target.isNotBlank() && target !in listOf("DIRECT","REJECT","GLOBAL") && !target.contains(',') && !target.contains('\n')){"请先选择有效代理组或节点"}
        return when(preset){
            "music" -> musicDomains.map{"AND,((PROCESS-NAME,com.roylyl.tools),(DOMAIN-SUFFIX,$it)),$target"}+"MATCH,DIRECT"
            "ai" -> {
                require(aiRules.isNotEmpty()) { "AI规则尚未加载" }
                BundledRoutingRules.mapRules(aiRules,emptySet(),target)
            }
            else -> error("未知代理模式")
        }
    }
    fun loadAI(context:Context):List<String> = Json.parseToJsonElement(context.assets.open("ai-routing/rules.json").bufferedReader().use{it.readText()}).jsonObject.getValue("rules").jsonArray.map{it.jsonPrimitive.content}
    fun apply(override:ConfigurationOverride,preset:String,target:String,aiRules:List<String> = emptyList()){
        val next=rules(preset,target,aiRules)
        if(override.routingPreset==null){override.originalRules=override.rules;override.originalRuleProviders=override.ruleProviders;override.originalFindProcessMode=override.findProcessMode}
        override.ruleProviders=override.originalRuleProviders
        override.findProcessMode=if(preset=="music") ConfigurationOverride.FindProcessMode.Strict else override.originalFindProcessMode
        override.rules=next;override.routingPreset=preset;override.routingTarget=target
    }
    fun clear(override:ConfigurationOverride){
        if(override.routingPreset!=null){override.rules=override.originalRules;override.ruleProviders=override.originalRuleProviders;override.findProcessMode=override.originalFindProcessMode;override.originalRules=null;override.originalRuleProviders=null;override.originalFindProcessMode=null;override.routingPreset=null}
    }
    /** Select the routing destination without mutating any selector or node choice. */
    fun target(config:ConfigurationOverride,groups:List<String>,global:ProxyGroup):String {
        val available=groups.toSet()+global.proxies.map{it.name}
        fun valid(name:String?)= !name.isNullOrBlank() && name !in setOf("GLOBAL","DIRECT","REJECT") && name in available
        return config.routingTarget?.takeIf(::valid) ?: global.now.takeIf(::valid)
            ?: groups.firstOrNull{it !in setOf("GLOBAL","DIRECT","REJECT")}
            ?: error("请先选择可用代理节点")
    }
}
