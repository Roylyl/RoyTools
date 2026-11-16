package com.github.kr328.clash.core.model

import android.content.Context
import kotlinx.serialization.json.*
import java.io.File


object BundledRoutingRules {
    private const val ASSET="roylyl-rules-3.0.1"
    fun apply(c:Context,override:ConfigurationOverride,target:String,groups:List<String>) {
        require(target !in listOf("DIRECT","REJECT","GLOBAL") && target.isNotBlank() && !target.contains(','))
        val bundle=RoutingRuleStore.current(c)
        val rules=bundle.rules(bundle.rule,"roytools-rule")
        if(override.routingPreset==null){override.originalRules=override.rules;override.originalRuleProviders=override.ruleProviders;override.originalFindProcessMode=override.findProcessMode}
        override.findProcessMode=override.originalFindProcessMode
        override.rules=mapRules(rules,groups.toSet(),target)
        val providers=(override.originalRuleProviders ?: emptyMap()).toMutableMap()
        for(rule in rules) if(rule.startsWith("RULE-SET,roytools-rule-direct-")) {
            val name=rule.split(',')[1]
            providers[name]=ConfigurationOverride.RuleProviderOverride(type="file",behavior="domain",format="text",path="$ASSET/${name.removePrefix("roytools-rule-")}.txt")
        }


        override.ruleProviders=providers
        override.routingPreset="roylyl"
        override.routingTarget=target
    }
    fun mapRules(rules:List<String>,groups:Set<String>,target:String):List<String> = rules.map{rule->
        val parts=rule.split(',').toMutableList()
        val index=if(parts.last()=="no-resolve")parts.lastIndex-1 else parts.lastIndex
        val policy=parts[index]
        if(policy !in setOf("DIRECT","REJECT","REJECT-DROP","PASS"))parts[index]=target
        parts.joinToString(",")
    }

    fun install(c:Context,profile:File) {
        RoutingRuleStore.installAll(c,profile)
        for(asset in listOf("ai-routing",ASSET)) {
            val json=Json.parseToJsonElement(c.assets.open("$asset/rules.json").bufferedReader().use { it.readText() }).jsonObject
            val directory=File(profile,"providers/$asset").apply { check(mkdirs()||isDirectory) }
            for(i in 1..json.getValue("providers").jsonPrimitive.int) {
                val name="direct-${i.toString().padStart(2,'0')}.txt"
                val out=File(directory,name)
                if(out.isFile && out.length()>0)continue
                val temp=File.createTempFile("rule-",".tmp",directory)
                try { c.assets.open("$asset/$name").use { input -> temp.outputStream().use { input.copyTo(it) } };check(temp.renameTo(out)) } finally { temp.delete() }
            }
        }
    }

}
