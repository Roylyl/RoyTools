package com.github.kr328.clash.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class MusicRoutingPolicy private constructor(val domainSuffixes: List<String>) {
    fun encode(): String = buildJsonObject {
        put("domainSuffixes", JsonArray(domainSuffixes.map(::JsonPrimitive)))
    }.toString()

    companion object {
        val DEFAULT = MusicRoutingPolicy(listOf("roylyl.github.io", "raw.githubusercontent.com"))
        private val dnsLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val finalLabel = Regex("[a-z]+|xn--[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")

        fun parse(json: JsonElement): MusicRoutingPolicy {
            val policy = json as? JsonObject
            require(policy != null && policy.keys == setOf("domainSuffixes")) { "音乐规则格式无效" }
            val entries = policy["domainSuffixes"] as? JsonArray
            require(entries != null && entries.size in 1..32) { "音乐规则域名数量无效" }
            val domains = entries.map { entry ->
                require(entry is JsonPrimitive && entry.isString) { "音乐规则域名无效" }
                val suffix = entry.content
                val labels = suffix.split('.')
                require(suffix.length <= 253 && labels.size >= 2 &&
                    labels.all(dnsLabel::matches) && finalLabel.matches(labels.last())) {
                    "音乐规则域名无效"
                }
                suffix
            }
            require(domains.distinct().size == domains.size) { "音乐规则包含重复域名" }
            return MusicRoutingPolicy(domains)
        }
    }
}
