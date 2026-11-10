package com.github.kr328.clash.core.model

/** Session-only replacement rules; the imported subscription remains untouched. */
object RoutingPresets {
    val musicDomains=listOf("roylyl.github.io","raw.githubusercontent.com")
    val aiDomains=listOf("openai.com","chatgpt.com","oaistatic.com","oaiusercontent.com","anthropic.com","claude.ai","claude.com")
    fun rules(preset:String,target:String):List<String> {
        require(target.isNotBlank() && target !in listOf("DIRECT","REJECT","GLOBAL") && !target.contains(',') && !target.contains('\n')){"请先选择有效代理组或节点"}
        val domains=when(preset){"music"->musicDomains;"ai"->aiDomains;else->error("未知代理模式")}
        return domains.map{"DOMAIN-SUFFIX,$it,$target"}+"MATCH,DIRECT"
    }
    fun apply(override:ConfigurationOverride,preset:String,target:String){
        val next=rules(preset,target)
        if(override.routingPreset==null){override.originalRules=override.rules;override.originalRuleProviders=override.ruleProviders}
        override.ruleProviders=override.originalRuleProviders
        override.rules=next;override.routingPreset=preset
    }
    fun clear(override:ConfigurationOverride){
        if(override.routingPreset!=null){override.rules=override.originalRules;override.ruleProviders=override.originalRuleProviders;override.originalRules=null;override.originalRuleProviders=null;override.routingPreset=null}
    }
}
