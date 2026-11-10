package com.github.kr328.clash.forward
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.RoutingPresets
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import com.github.kr328.clash.core.model.BundledRoutingRules
class RoutingPresetsTest {
    @Test fun fileRuleProviderKeepsRequiredNativeFields(){
        val config=ConfigurationOverride(ruleProviders=mapOf("Mainland" to ConfigurationOverride.RuleProviderOverride(type="file",behavior="domain",format="mrs",path="rules/cn.mrs")))
        val text=Json.encodeToString(ConfigurationOverride.serializer(),config)
        assertTrue(text.contains("\"type\":\"file\""));assertTrue(text.contains("\"behavior\":\"domain\""))
        val restored=Json.decodeFromString(ConfigurationOverride.serializer(),text)
        assertEquals("mrs",restored.ruleProviders!!.getValue("Mainland").format)
    }

    @Test fun nativeJsonUsesSingularRuleField(){
        val config=ConfigurationOverride(rules=listOf("MATCH,DIRECT"))
        val text=Json.encodeToString(ConfigurationOverride.serializer(),config)
        assertTrue(text.contains("\"rule\":["));assertFalse(text.contains("\"rules\":"))
    }
    @Test fun bundledRulesPreserveDirectAndMapMissingGroups(){
        val rules=listOf("DOMAIN,example.ai,AI-AutoFallback","IP-CIDR,1.1.1.0/24,DIRECT,no-resolve","MATCH,Other-AutoFallback")
        assertEquals(listOf("DOMAIN,example.ai,AI-AutoFallback","IP-CIDR,1.1.1.0/24,DIRECT,no-resolve","MATCH,new-group"),BundledRoutingRules.mapRules(rules,setOf("AI-AutoFallback"),"new-group"))
    }
    @Test fun musicOnlyRoutesItsHosts(){
        val rules=RoutingPresets.rules("music","节点选择")
        assertEquals(listOf("DOMAIN-SUFFIX,roylyl.github.io,节点选择","DOMAIN-SUFFIX,raw.githubusercontent.com,节点选择","MATCH,DIRECT"),rules)
    }
    @Test fun switchingAndLeavingRestoresOriginalRules(){
        val original=listOf("MATCH,original")
        val config=ConfigurationOverride(rules=original)
        RoutingPresets.apply(config,"music","proxy")
        RoutingPresets.apply(config,"ai","proxy")
        assertTrue(config.rules!!.contains("DOMAIN-SUFFIX,chatgpt.com,proxy"))
        assertEquals("MATCH,DIRECT",config.rules!!.last())
        RoutingPresets.clear(config)
        assertEquals(original,config.rules);assertNull(config.routingPreset)
    }
    @Test fun absentOverrideRestoresSubscriptionRules(){
        val config=ConfigurationOverride()
        RoutingPresets.apply(config,"ai","proxy");RoutingPresets.clear(config)
        assertNull(config.rules)
    }
    @Test(expected=IllegalArgumentException::class) fun cannotUseDirectAsProxy(){RoutingPresets.rules("ai","DIRECT")}
}
