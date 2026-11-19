package com.github.kr328.clash.core.model

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class RoutingRuleDownload(private val baseUrl: String = SOURCE) {
    data class Release(
        val version: String,
        val bytes: Int,
        val sha256: String,
        val notes: String,
        val music: MusicRoutingPolicy? = null,
    )

    fun check(): Release {
        val json = Json.parseToJsonElement(get("routing-version.json", 16_384).toString(Charsets.UTF_8)).jsonObject
        val version = json.getValue("version").jsonPrimitive.content
        RoutingRuleBundle.compareVersions(version, version)
        val bytes = json.getValue("bytes").jsonPrimitive.int
        val hash = json.getValue("sha256").jsonPrimitive.content
        require(bytes in 1..MAX_BYTES && Regex("[a-f0-9]{64}").matches(hash)) { "规则更新信息无效" }
        val music = json["music"]?.let(MusicRoutingPolicy::parse)
        return Release(version, bytes, hash, json["notes"]?.jsonPrimitive?.content.orEmpty().take(2000), music)
    }

    fun download(release: Release, progress: (Int, Int) -> Unit = { _, _ -> }): String {
        val bytes = get("routing-rules.json", release.bytes, progress)
        require(bytes.size == release.bytes) { "规则下载不完整，请重试" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == release.sha256) { "规则文件与版本信息不一致，请稍后重试" }
        val text = bytes.toString(Charsets.UTF_8)
        require(RoutingRuleBundle.parse(text).version == release.version) { "规则版本不一致" }
        return text
    }

    private fun get(name: String, limit: Int, progress: (Int, Int) -> Unit = { _, _ -> }): ByteArray {
        val connection = URL("$baseUrl/$name?check=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("User-Agent", "RoyTools-RoutingUpdater")
        try {
            when (val status = connection.responseCode) {
                200 -> Unit
                404 -> throw IOException("GitHub仓库尚未发布规则更新文件")
                else -> throw IOException("连接GitHub失败（HTTP$status）")
            }
            require(connection.contentLengthLong <= limit) { "规则文件超过允许大小" }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                val deadline = System.nanoTime() + 120_000_000_000L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    if (System.nanoTime() > deadline) throw IOException("规则下载超时，请重试")
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "规则文件超过允许大小" }
                    output.write(buffer, 0, count)
                    progress(output.size(), limit)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    companion object {
        const val SOURCE = "https://raw.githubusercontent.com/Roylyl/RoyTools/main"
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
