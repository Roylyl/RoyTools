package com.github.kr328.clash.core.model

import android.content.Context

/** Session-only rules that decide whether traffic should go through the subscription's proxies. */
object RoutingPresets {
    private val nonProxyPolicies = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "PASS-RULE")

    fun rules(
        preset: String,
        bundledRules: List<String> = emptyList(),
        music: MusicRoutingPolicy = MusicRoutingPolicy.DEFAULT,
    ): List<String> = when (preset) {
        "music" -> music.domainSuffixes.map {
            "AND,((PROCESS-NAME,com.roylyl.tools),(DOMAIN-SUFFIX,$it)),GLOBAL"
        } + "MATCH,DIRECT"

        "ai", "roylyl" -> {
            require(bundledRules.isNotEmpty()) { "分流规则尚未加载" }
            bundledRules.map { rule ->
                val parts = rule.split(',').toMutableList()
                val policyIndex = if (parts.last() == "no-resolve") parts.lastIndex - 1 else parts.lastIndex
                // GLOBAL marks eligibility for proxying. The core resolves the actual outlet.
                if (parts[policyIndex] !in nonProxyPolicies) parts[policyIndex] = "GLOBAL"
                parts.joinToString(",")
            }
        }

        else -> error("未知代理模式")
    }

    fun apply(context: Context, override: ConfigurationOverride, preset: String) {
        if (preset == "music") {
            apply(override, preset, music = RoutingRuleStore.currentMusic(context))
            return
        }
        val bundle = RoutingRuleStore.current(context)
        val section = if (preset == "ai") bundle.ai else bundle.rule
        val prefix = if (preset == "ai") "roytools-ai" else "roytools-rule"
        apply(override, preset, bundle.rules(section, prefix))
    }

    fun apply(
        override: ConfigurationOverride,
        preset: String,
        bundledRules: List<String> = emptyList(),
        music: MusicRoutingPolicy = MusicRoutingPolicy.DEFAULT,
    ) {
        val next = rules(preset, bundledRules, music)
        val prefix = if (preset == "ai") "roytools-ai" else "roytools-rule"
        val directory = if (preset == "ai") "ai-routing" else "roylyl-rules-3.0.1"
        val providers = bundledRules.asSequence()
            .filter { it.startsWith("RULE-SET,$prefix-direct-") }
            .map { it.split(',')[1] }
            .associateWith { name ->
                val file = name.removePrefix("$prefix-")
                ConfigurationOverride.RuleProviderOverride(
                    type = "file", behavior = "domain", format = "text", path = "$directory/$file.txt",
                )
            }
        override.routingPreset = preset
        override.routingRules = next
        override.routingRuleProviders = providers.takeIf { it.isNotEmpty() }
    }

    fun clear(override: ConfigurationOverride) {
        override.routingPreset = null
        override.routingRules = null
        override.routingRuleProviders = null
    }
}
