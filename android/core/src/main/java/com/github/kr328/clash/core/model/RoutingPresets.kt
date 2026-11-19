package com.github.kr328.clash.core.model

import android.content.Context

/** Session-only replacement rules; the imported subscription remains untouched. */
object RoutingPresets {
    fun rules(
        preset: String,
        target: String,
        aiRules: List<String> = emptyList(),
        music: MusicRoutingPolicy = MusicRoutingPolicy.DEFAULT,
    ): List<String> {
        require(target.isNotBlank() && target !in listOf("DIRECT","REJECT","GLOBAL") && !target.contains(',') && !target.contains('\n')){"请先选择有效代理组或节点"}
        return when(preset){
            "music" -> music.domainSuffixes.map {
                "AND,((PROCESS-NAME,com.roylyl.tools),(DOMAIN-SUFFIX,$it)),$target"
            } + "MATCH,DIRECT"
            "ai" -> {
                require(aiRules.isNotEmpty()) { "AI规则尚未加载" }
                BundledRoutingRules.mapRules(aiRules,emptySet(),target)
            }
            else -> error("未知代理模式")
        }
    }
    fun apply(context: Context, override: ConfigurationOverride, preset: String, target: String) {
        when (preset) {
            "music" -> apply(override, preset, target, music = RoutingRuleStore.currentMusic(context))
            "ai" -> {
                val bundle = RoutingRuleStore.current(context)
                apply(override, preset, target, aiRules = bundle.rules(bundle.ai, "roytools-ai"))
            }
            else -> error("未知代理模式")
        }
    }

    fun apply(
        override: ConfigurationOverride,
        preset: String,
        target: String,
        aiRules: List<String> = emptyList(),
        music: MusicRoutingPolicy = MusicRoutingPolicy.DEFAULT,
    ) {
        val next = rules(preset, target, aiRules, music)
        if(override.routingPreset==null){override.originalRules=override.rules;override.originalRuleProviders=override.ruleProviders;override.originalFindProcessMode=override.findProcessMode}
        override.ruleProviders=if(preset=="ai") {
            val providers=(override.originalRuleProviders ?: emptyMap()).toMutableMap()
            for(rule in aiRules) if(rule.startsWith("RULE-SET,roytools-ai-direct-")) {
                val name=rule.split(',')[1]
                val file=name.removePrefix("roytools-ai-")
                providers[name]=ConfigurationOverride.RuleProviderOverride(type="file",behavior="domain",format="text",path="ai-routing/$file.txt")
            }
            providers
        } else override.originalRuleProviders
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
