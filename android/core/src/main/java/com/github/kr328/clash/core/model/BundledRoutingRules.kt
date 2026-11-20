package com.github.kr328.clash.core.model

import android.content.Context
import kotlinx.serialization.json.*
import java.io.File


object BundledRoutingRules {
    private const val ASSET="roylyl-rules-3.0.1"

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
