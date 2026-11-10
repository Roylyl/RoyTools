package com.github.kr328.clash.core.model

import android.content.Context
import kotlinx.serialization.json.*
import java.io.File

object BundledRoutingRules {
    private const val ASSET="roylyl-rules-2.5.0"
    fun apply(c:Context,override:ConfigurationOverride,target:String,groups:List<String>) {
        require(target !in listOf("DIRECT","REJECT","GLOBAL") && target.isNotBlank() && !target.contains(','))
        val rules=Json.parseToJsonElement(c.assets.open("$ASSET/rules.json").bufferedReader().use{it.readText()}).jsonObject.getValue("rules").jsonArray.map{it.jsonPrimitive.content}
        if(override.routingPreset==null){override.originalRules=override.rules;override.originalRuleProviders=override.ruleProviders}
        override.rules=mapRules(rules,groups.toSet(),target)
        val providers=(override.originalRuleProviders ?: emptyMap()).toMutableMap()
        providers["WeChatIPs"]=provider("ipcidr","text","wechat-ip.txt")
        providers["Mainland"]=provider("domain","mrs","cn.mrs")
        override.ruleProviders=providers
        override.routingPreset="roylyl"
    }
    fun mapRules(rules:List<String>,groups:Set<String>,target:String):List<String> = rules.map{rule->
        val parts=rule.split(',').toMutableList()
        val index=if(parts.last()=="no-resolve")parts.lastIndex-1 else parts.lastIndex
        val policy=parts[index]
        if(policy !in setOf("DIRECT","REJECT","REJECT-DROP","PASS") && policy !in groups)parts[index]=target
        parts.joinToString(",")
    }
    private fun provider(behavior:String,format:String,name:String)=ConfigurationOverride.RuleProviderOverride(type="file",behavior=behavior,format=format,path="$ASSET/$name")
    fun install(c:Context,profile:File){
        val directory=File(profile,"providers/$ASSET").apply{check(mkdirs()||isDirectory)}
        for(name in listOf("wechat-ip.txt","cn.mrs")){
            val out=File(directory,name)
            if(out.isFile&&out.length()>0)continue
            val temp=File.createTempFile("rule-",".tmp",directory)
            try{c.assets.open("$ASSET/$name").use{input->temp.outputStream().use{input.copyTo(it)}};check(temp.renameTo(out))}finally{temp.delete()}
        }
    }
}
