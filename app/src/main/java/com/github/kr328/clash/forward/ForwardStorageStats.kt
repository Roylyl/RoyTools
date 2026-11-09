package com.github.kr328.clash.forward

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

object ForwardStorageStats {
    private data class Count(val bytes:Long,val modified:Long,val records:Long)
    private val counts=mutableMapOf<String,Count>()
    @Synchronized fun read(c:Context):Pair<Long,Long> {
        val bytes=listOf(File(c.cacheDir,"chat-media"),File(c.filesDir,"chat-downloads"))
            .sumOf{folder->if(folder.exists())folder.walkTopDown().filter{it.isFile}.sumOf{it.length()} else 0L}
        val histories=File(c.noBackupFilesDir,"telegram-chat").listFiles()?.filter{it.isFile&&it.extension=="jsonl"}.orEmpty()
        counts.keys.retainAll(histories.map{it.name}.toSet())
        val records=histories.sumOf{file->
            val cached=counts[file.name]
            if(cached!=null&&cached.bytes==file.length()&&cached.modified==file.lastModified()) cached.records
            else {
                val size=file.length();val modified=file.lastModified()
                val count=file.useLines{lines->lines.count{runCatching{JSONObject(it).has("id")}.getOrDefault(false)}.toLong()}
                counts[file.name]=Count(size,modified,count)
                count
            }
        }
        return bytes to records
    }
    fun formatBytes(bytes:Long):String {
        if(bytes<1024)return bytes.toString()+"B"
        var value=bytes.toDouble();var unit=0
        val units=arrayOf("B","KB","MB","GB","TB")
        while(value>=1024&&unit<units.lastIndex){value/=1024;unit++}
        return String.format(Locale.ROOT,"%.1f%s",value,units[unit])
    }
}
