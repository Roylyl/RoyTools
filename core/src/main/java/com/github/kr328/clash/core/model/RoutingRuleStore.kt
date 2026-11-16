package com.github.kr328.clash.core.model

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Immutable versions let running profiles retain their providers during an update. */
object RoutingRuleStore {
    const val BUNDLED_VERSION = "3.0.1"
    private val cache = ConcurrentHashMap<String, RoutingRuleBundle>()
    private fun directory(c: Context) = File(c.filesDir, "routing-updates").apply { mkdirs() }

    fun bundled(c: Context): RoutingRuleBundle = cache.getOrPut("bundled") {
        fun section(asset: String): RoutingRuleBundle.Section {
            val json = Json.parseToJsonElement(c.assets.open("$asset/rules.json").bufferedReader().use { it.readText() }).jsonObject
            val providers = (1..json.getValue("providers").jsonPrimitive.int).associate { index ->
                val name = "direct-${index.toString().padStart(2, '0')}.txt"
                name to c.assets.open("$asset/$name").bufferedReader().use { it.readText() }
            }
            return RoutingRuleBundle.Section(json.getValue("rules").jsonArray.map { it.jsonPrimitive.content }, providers)
        }
        RoutingRuleBundle(BUNDLED_VERSION, section("ai-routing"), section("roylyl-rules-3.0.1"))
    }

    private fun read(file: File) = cache.getOrPut(file.absolutePath) { RoutingRuleBundle.parse(file.readText()) }

    fun current(c: Context): RoutingRuleBundle {
        val dir = directory(c)
        val version = runCatching { File(dir, "active").readText().trim() }.getOrNull() ?: return bundled(c)
        return runCatching {
            require(RoutingRuleBundle.compareVersions(version, BUNDLED_VERSION) > 0)
            read(File(dir, "$version.json"))
        }.getOrElse { bundled(c) }
    }

    @Synchronized
    fun activate(c: Context, text: String): RoutingRuleBundle {
        val next = RoutingRuleBundle.parse(text)
        val dir = directory(c)
        RandomAccessFile(File(dir, "update.lock"), "rw").use { handle ->
            handle.channel.lock().use {
                val previous = current(c)
                if (RoutingRuleBundle.compareVersions(next.version, previous.version) <= 0) return previous
                val file = File(dir, "${next.version}.json")
                if (file.exists()) require(file.readText() == text) { "同版本规则内容发生变化，请发布新版本" }
                else writeAtomic(file, text)
                // Provider installation must succeed before changing the active version.
                File(c.filesDir, "imported").listFiles()?.filter { it.isDirectory }?.forEach { install(next, it) }
                writeAtomic(File(dir, "active"), next.version)
                cache[file.absolutePath] = next
                return next
            }
        }
    }

    private fun writeAtomic(file: File, text: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    fun install(bundle: RoutingRuleBundle, profile: File) {
        listOf("ai-routing" to bundle.ai, "roylyl-rules-3.0.1" to bundle.rule).forEach { (folder, section) ->
            val dir = File(profile, "providers/$folder").apply { check(mkdirs() || isDirectory) }
            section.providers.forEach { (name, content) ->
                val file = File(dir, name.removeSuffix(".txt") + "-v${bundle.version.replace('.', '-')}.txt")
                if (!file.exists()) {
                    val temp = File.createTempFile("routing-", ".tmp", dir)
                    try { temp.writeText(content); check(temp.renameTo(file) || file.exists()) }
                    finally { temp.delete() }
                }
            }
        }
    }

    fun installAll(c: Context, profile: File) {
        install(bundled(c), profile)
        directory(c).listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            val bundle = runCatching { read(file) }.getOrNull() ?: return@forEach
            install(bundle, profile)
        }
    }
}
