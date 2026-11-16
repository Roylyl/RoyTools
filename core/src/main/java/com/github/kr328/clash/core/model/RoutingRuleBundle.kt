package com.github.kr328.clash.core.model

import kotlinx.serialization.json.*

/** A complete, data-only routing release. No subscription, nodes or remote providers. */
data class RoutingRuleBundle(val version: String, val ai: Section, val rule: Section) {
    data class Section(val rules: List<String>, val providers: Map<String, String>)

    fun rules(section: Section, prefix: String): List<String> = section.rules.map { line ->
        if (line.startsWith("RULE-SET,$prefix-direct-")) {
            val parts = line.split(',').toMutableList()
            parts[1] += "-v${version.replace('.', '-')}"
            parts.joinToString(",")
        } else line
    }

    companion object {
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String): List<Int> {
                require(Regex("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})").matches(v)) { "规则版本号无效" }
                return v.split('.').map(String::toInt)
            }
            return parts(a).zip(parts(b)).map { (x,y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0
        }

        fun parse(text: String): RoutingRuleBundle {
            val json = Json.parseToJsonElement(text).jsonObject
            require(json.keys == setOf("version", "ai", "rule")) { "规则包字段不受支持" }
            val version = json.getValue("version").jsonPrimitive.content
            compareVersions(version, version)
            fun section(key: String, prefix: String): Section {
                val value = json.getValue(key).jsonObject
                require(value.keys == setOf("rules", "providers")) { "规则包包含未知配置" }
                val providers = value.getValue("providers").jsonObject.mapValues { it.value.jsonPrimitive.content }
                require(providers.size <= 100)
                providers.forEach { (name, content) ->
                    require(Regex("direct-[0-9]{2}\\.txt").matches(name)) { "规则集文件名无效" }
                    require(content.isNotBlank() && content.length <= 8_000_000)
                    require(content.lineSequence().all { line ->
                        val domain = line.trim()
                        domain.isEmpty() || Regex("[+*a-zA-Z0-9_.-]+").matches(domain)
                    }) { "域名规则集格式无效" }
                }
                val rules = value.getValue("rules").jsonArray.map { it.jsonPrimitive.content }
                require(rules.isNotEmpty() && rules.size <= 10000) { "主规则数量无效" }
                rules.forEachIndexed { index, line ->
                    require(line.length <= 4096 && '\n' !in line && '\r' !in line)
                    val parts = line.split(',')
                    require(parts.size >= 2 && parts.none(String::isBlank)) { "规则格式无效" }
                    require(parts[0] in setOf("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-WILDCARD", "IP-CIDR", "IP-CIDR6", "GEOIP", "GEOSITE", "RULE-SET", "MATCH", "PROCESS-NAME", "DST-PORT", "SRC-IP-CIDR", "SRC-PORT", "NETWORK")) { "不支持的规则类型：${parts[0]}" }
                    if (parts[0] == "MATCH") require(index == rules.lastIndex && parts.size == 2)
                    if (parts[0] == "RULE-SET") {
                        require(parts.size == 3 && parts[1].startsWith("$prefix-direct-"))
                        require(providers.containsKey(parts[1].removePrefix("$prefix-") + ".txt")) { "缺少配套域名规则集" }
                    }
                }
                require(rules.last().startsWith("MATCH,")) { "规则缺少兜底策略" }
                // AI mode is selective: unknown destinations must remain direct.
                if (key == "ai") require(rules.last() == "MATCH,DIRECT") { "AI规则的兜底必须为直连" }
                return Section(rules, providers)
            }
            return RoutingRuleBundle(version, section("ai", "roytools-ai"), section("rule", "roytools-rule"))
        }
    }
}
