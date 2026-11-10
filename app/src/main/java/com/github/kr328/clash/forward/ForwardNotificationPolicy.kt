package com.github.kr328.clash.forward

/** Avoid WorkManager's 10KB Data limit, including serialization overhead. */
internal fun forwardPayload(text:String):String {
    val end=minOf(text.length,2000)
    return text.substring(0,if(end>0 && Character.isHighSurrogate(text[end-1]))end-1 else end)
}
internal class ForwardDeduplicator {
    private val recent=LinkedHashMap<String,String>()
    fun duplicate(key:String,text:String)=recent[key]==text
    fun accepted(key:String,text:String){recent[key]=text;if(recent.size>200)recent.remove(recent.keys.first())}
    fun removed(key:String){recent.remove(key)}
}
