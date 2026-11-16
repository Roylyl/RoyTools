package com.github.kr328.clash.core.model

import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class RoutingRuleUpdateTest {
    private val text = """{"version":"3.0.2","ai":{"rules":["RULE-SET,roytools-ai-direct-01,DIRECT","DOMAIN,chatgpt.com,AI","MATCH,DIRECT"],"providers":{"direct-01.txt":"+.example.com\n"}},"rule":{"rules":["MATCH,Proxy"],"providers":{}}}"""

    @Test fun repositoryBundleAndManifestAreConsumable() {
        val root = File("..").canonicalFile
        val bundle = RoutingRuleBundle.parse(File(root, "routing-rules.json").readText())
        assertEquals("3.0.1", bundle.version)
        assertEquals("MATCH,DIRECT", bundle.ai.rules.last())
        assertEquals("MATCH,Other-AutoFallback", bundle.rule.rules.last())
        assertTrue(bundle.rules(bundle.ai, "roytools-ai").first().contains("-v3-0-1"))
        val bytes = File(root, "routing-rules.json").readBytes()
        val manifest = Json.parseToJsonElement(File(root, "routing-version.json").readText()).jsonObject
        assertEquals(bundle.version, manifest.getValue("version").jsonPrimitive.content)
        assertEquals(bytes.size, manifest.getValue("bytes").jsonPrimitive.int)
        assertFalse(bytes.contains(13.toByte()))
        assertEquals(manifest.getValue("sha256").jsonPrimitive.content,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }

    @Test fun versionsCompareNumericallyAndRejectPaths() {
        assertTrue(RoutingRuleBundle.compareVersions("3.0.10", "3.0.9") > 0)
        assertTrue(RoutingRuleBundle.compareVersions("2.9.9", "3.0.1") < 0)
        assertEquals(0, RoutingRuleBundle.compareVersions("3.0.1", "3.0.1"))
        assertThrows(IllegalArgumentException::class.java) { RoutingRuleBundle.compareVersions("../3.0.2", "3.0.1") }
    }

    @Test fun incompleteOrUnsafeBundlesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RoutingRuleBundle.parse(text.replace("direct-01.txt", "../direct-01.txt")) }
        assertThrows(IllegalArgumentException::class.java) { RoutingRuleBundle.parse(text.replace("MATCH,DIRECT", "MATCH,AI")) }
        assertThrows(IllegalArgumentException::class.java) { RoutingRuleBundle.parse(text.replace("roytools-ai-direct-01", "roytools-ai-direct-02")) }
    }

    @Test fun httpCheckDownloadProgressAndBadContent() {
        val body = text.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            repeat(4) { index ->
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val missing = request.contains("/missing/")
                    val response = when {
                        missing -> "not found".toByteArray()
                        request.contains("routing-version.json") -> """{"version":"3.0.2","bytes":${body.size},"sha256":"$sha","notes":"test"}""".toByteArray()
                        index == 2 -> text.replace("chatgpt.com", "invalid.com").toByteArray()
                        else -> body
                    }
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 ${if(missing) "404 Not Found" else "200 OK"}\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(response)
                    }
                }
            }
        }
        try {
            val repository = RoutingRuleDownload("http://127.0.0.1:${server.localPort}")
            val release = repository.check()
            var downloaded = 0
            assertEquals(text, repository.download(release) { count, _ -> downloaded = count })
            assertEquals(body.size, downloaded)
            assertThrows(IllegalArgumentException::class.java) { repository.download(release) }
            val missing = RoutingRuleDownload("http://127.0.0.1:${server.localPort}/missing")
            val error = assertThrows(java.io.IOException::class.java) { missing.check() }
            assertTrue(error.message!!.contains("尚未发布"))
        } finally { server.close(); worker.join(1000) }
    }
}
