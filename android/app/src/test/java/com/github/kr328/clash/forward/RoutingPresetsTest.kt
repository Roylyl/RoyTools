package com.github.kr328.clash.forward

import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.MusicRoutingPolicy
import com.github.kr328.clash.core.model.RoutingPresets
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RoutingPresetsTest {
    private val ai = listOf(
        "RULE-SET,roytools-ai-direct-01-v3-0-1,DIRECT",
        "DOMAIN-SUFFIX,chatgpt.com,AI-AutoFallback",
        "IP-CIDR,1.2.3.4/32,AI-AutoFallback,no-resolve",
        "MATCH,DIRECT",
    )

    @Test fun fileRuleProviderKeepsRequiredNativeFields() {
        val config = ConfigurationOverride(
            ruleProviders = mapOf("Mainland" to ConfigurationOverride.RuleProviderOverride(
                type = "file", behavior = "domain", format = "mrs", path = "rules/cn.mrs",
            )),
        )
        val text = Json.encodeToString(ConfigurationOverride.serializer(), config)
        assertTrue(text.contains("\"type\":\"file\""))
        assertTrue(text.contains("\"behavior\":\"domain\""))
        val restored = Json.decodeFromString(ConfigurationOverride.serializer(), text)
        assertEquals("mrs", restored.ruleProviders!!.getValue("Mainland").format)
    }

    @Test fun nativeJsonSeparatesSubscriptionAndPresetRules() {
        val config = ConfigurationOverride(rules = listOf("MATCH,subscription-group"))
        RoutingPresets.apply(config, "ai", ai)
        val text = Json.encodeToString(ConfigurationOverride.serializer(), config)
        assertTrue(text.contains("\"rule\":[\"MATCH,subscription-group\"]"))
        assertTrue(text.contains("\"roytools-proxy-rules\":["))
        assertTrue(text.contains("\"roytools-proxy-rule-providers\":{\"roytools-ai-direct-01-v3-0-1\":"))
    }

    @Test fun bundledRulesDescribeProxyEligibilityWithoutChoosingAGroup() {
        val rules = listOf(
            "DOMAIN,chatgpt.com,AI-Group",
            "DOMAIN,youtube.com,Video-Group",
            "DOMAIN,ads.example.com,REJECT",
            "IP-CIDR,1.1.1.0/24,DIRECT,no-resolve",
            "MATCH,Other-AutoFallback",
        )
        assertEquals(listOf(
            "DOMAIN,chatgpt.com,GLOBAL",
            "DOMAIN,youtube.com,GLOBAL",
            "DOMAIN,ads.example.com,REJECT",
            "IP-CIDR,1.1.1.0/24,DIRECT,no-resolve",
            "MATCH,GLOBAL",
        ), RoutingPresets.rules("roylyl", rules))
    }

    @Test fun musicOnlyRoutesItsHostsAndApp() {
        val rules = RoutingPresets.rules("music")
        assertEquals(listOf(
            "AND,((PROCESS-NAME,com.roylyl.tools),(DOMAIN-SUFFIX,roylyl.github.io)),GLOBAL",
            "AND,((PROCESS-NAME,com.roylyl.tools),(DOMAIN-SUFFIX,raw.githubusercontent.com)),GLOBAL",
            "MATCH,DIRECT",
        ), rules)
    }

    @Test fun downloadedMusicPolicyUsesItsOwnAppDomains() {
        val policy = MusicRoutingPolicy.parse(Json.parseToJsonElement(
            """{"domainSuffixes":["library.example.org","media.example.net"]}""",
        ))
        assertEquals(listOf(
            "AND,((PROCESS-NAME,com.roylyl.tools),(DOMAIN-SUFFIX,library.example.org)),GLOBAL",
            "AND,((PROCESS-NAME,com.roylyl.tools),(DOMAIN-SUFFIX,media.example.net)),GLOBAL",
            "MATCH,DIRECT",
        ), RoutingPresets.rules("music", music = policy))
    }

    @Test fun switchingAndLeavingOnlyReplacePresetRules() {
        val originalRules = listOf("RULE-SET,original,subscription-group", "MATCH,DIRECT")
        val originalProviders = mapOf("original" to ConfigurationOverride.RuleProviderOverride(path = "original.yaml"))
        val config = ConfigurationOverride(
            rules = originalRules,
            ruleProviders = originalProviders,
            findProcessMode = ConfigurationOverride.FindProcessMode.Off,
        )
        RoutingPresets.apply(config, "ai", ai)
        assertEquals(listOf(
            "RULE-SET,roytools-ai-direct-01-v3-0-1,DIRECT",
            "DOMAIN-SUFFIX,chatgpt.com,GLOBAL",
            "IP-CIDR,1.2.3.4/32,GLOBAL,no-resolve",
            "MATCH,DIRECT",
        ), config.routingRules)
        assertEquals("ai-routing/direct-01-v3-0-1.txt",
            config.routingRuleProviders!!.getValue("roytools-ai-direct-01-v3-0-1").path)
        RoutingPresets.apply(config, "music")
        assertNull(config.routingRuleProviders)
        assertNotNull(config.routingRules)
        RoutingPresets.clear(config)
        assertNull(config.routingPreset)
        assertNull(config.routingRules)
        assertEquals(originalRules, config.rules)
        assertEquals(originalProviders, config.ruleProviders)
        assertEquals(ConfigurationOverride.FindProcessMode.Off, config.findProcessMode)
    }
}
