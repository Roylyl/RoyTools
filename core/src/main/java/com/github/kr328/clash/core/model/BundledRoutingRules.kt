package com.github.kr328.clash.core.model

import android.content.Context
import kotlinx.serialization.json.*
import java.io.File


object BundledRoutingRules {
    private const val ASSET="roylyl-rules-3.0.1"
    fun apply(c:Context,override:ConfigurationOverride,target:String,groups:List<String>) {
        require(target !in listOf("DIRECT","REJECT","GLOBAL") && target.isNotBlank() && !target.contains(','))
        val rules=Json.parseToJsonElement(c.assets.open("$ASSET/rules.json").bufferedReader().use{it.readText()}).jsonObject.getValue("rules").jsonArray.map{it.jsonPrimitive.content}
        if(override.routingPreset==null){override.originalRules=override.rules;override.originalRuleProviders=override.ruleProviders;override.originalFindProcessMode=override.findProcessMode}
        override.findProcessMode=override.originalFindProcessMode
        override.rules=mapRules(rules,groups.toSet(),target)
        val providers=(override.originalRuleProviders ?: emptyMap()).toMutableMap()
        addProviders(c, providers, ASSET, "roytools-rule")


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

    fun addProviders(c:Context, providers:MutableMap<String,ConfigurationOverride.RuleProviderOverride>, asset:String, prefix:String) {
        val json=Json.parseToJsonElement(c.assets.open("$asset/rules.json").bufferedReader().use { it.readText() }).jsonObject
        val count=json.getValue("providers").jsonPrimitive.int
        for(i in 1..count) {
            val name="direct-${i.toString().padStart(2,'0')}"
            providers["$prefix-$name"]=ConfigurationOverride.RuleProviderOverride(type="file",behavior="domain",format="text",path="$asset/$name.txt")
        }
    }

    fun install(c:Context,profile:File) {
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
