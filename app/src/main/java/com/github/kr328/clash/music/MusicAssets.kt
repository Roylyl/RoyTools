package com.github.kr328.clash.music

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale

/** The WebView and its service worker share a real HTTPS origin backed only by music assets. */
internal class MusicAssets(context: Context) {
    private val assets = context.applicationContext.assets

    fun intercept(request: WebResourceRequest): WebResourceResponse? =
        open(request.url, request.method, request.requestHeaders)

    fun open(uri: Uri, method: String = "GET", requestHeaders: Map<String, String> = emptyMap()): WebResourceResponse? {
        if (!isOrigin(uri)) return null
        val path = Uri.decode(uri.encodedPath ?: "")
        // Decode once, then keep AssetManager inside the music root even for encoded traversals.
        if (!path.startsWith("/music/") || path.indexOf('\\') >= 0 || path.indexOf('\u0000') >= 0 ||
            path.split('/').any { it == "." || it == ".." }) return error(404, "Not Found")
        if (method != "GET" && method != "HEAD") return error(405, "Method Not Allowed")
        val name = path.removePrefix("/").let { if (it.endsWith('/')) it + "index.html" else it }
        val input = try { assets.open(name, AssetManager.ACCESS_STREAMING) }
        catch (_: java.io.IOException) { return error(404, "Not Found") }
        try {
            // AssetInputStream.available() is the remaining uncompressed asset length.
            val length = input.available().toLong()
            val mime = mimeType(name)
            val encoding = if (mime.startsWith("text/") || mime in setOf(
                    "application/javascript", "application/json", "application/manifest+json", "image/svg+xml")) "UTF-8" else null
            val headers = mutableMapOf(
                "Cache-Control" to "no-cache",
                "Accept-Ranges" to "bytes",
                "X-Content-Type-Options" to "nosniff",
                // Remote frames must never receive the JavaScript interface exposed to this trusted page.
                "Content-Security-Policy" to "frame-src 'none'; object-src 'none'; base-uri 'self'; form-action 'none'",
            )
            val range = requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
            var start = 0L
            var end = length - 1
            var partial = false
            if (range != null && range.startsWith("bytes=") && !range.contains(',')) {
                val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range.trim())
                if (match != null && (match.groupValues[1].isNotEmpty() || match.groupValues[2].isNotEmpty())) {
                    if (match.groupValues[1].isEmpty()) {
                        val suffix = match.groupValues[2].toLongOrNull() ?: 0
                        start = (length - suffix).coerceAtLeast(0)
                    } else {
                        start = match.groupValues[1].toLongOrNull() ?: length
                        if (match.groupValues[2].isNotEmpty()) end =
                            (match.groupValues[2].toLongOrNull() ?: -1).coerceAtMost(length - 1)
                    }
                    if (start >= length || end < start) {
                        input.close()
                        return WebResourceResponse("text/plain", "UTF-8", 416, "Range Not Satisfiable",
                            mapOf("Content-Range" to "bytes */$length"), ByteArrayInputStream(ByteArray(0)))
                    }
                    partial = true
                }
            }
            val count = if (length == 0L) 0L else end - start + 1
            headers["Content-Length"] = count.toString()
            if (partial) headers["Content-Range"] = "bytes $start-$end/$length"
            val body: InputStream = if (method == "HEAD") {
                input.close()
                ByteArrayInputStream(ByteArray(0))
            } else {
                var remaining = start
                while (remaining > 0) {
                    val skipped = input.skip(remaining)
                    if (skipped > 0) remaining -= skipped
                    else if (input.read() == -1) break else remaining--
                }
                BoundedInputStream(input, count)
            }
            return WebResourceResponse(mime, encoding, if (partial) 206 else 200,
                if (partial) "Partial Content" else "OK", headers, body)
        } catch (_: java.io.IOException) {
            input.close()
            return error(500, "Asset Read Error")
        }
    }

    private fun mimeType(path: String): String {
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "webmanifest" -> "application/manifest+json"
            "svg" -> "image/svg+xml"
            "lrc", "txt", "vtt" -> "text/plain"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> if (extension == "aac") "audio/aac" else "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "avif" -> "image/avif"
            "wasm" -> "application/wasm"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }
    }

    private class BoundedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            return super.read().also { if (it >= 0) remaining-- }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining <= 0) return -1
            return `in`.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                .also { if (it > 0) remaining -= it }
        }
        override fun available(): Int = minOf(`in`.available().toLong(), remaining).toInt()
        override fun skip(count: Long): Long = `in`.skip(minOf(count, remaining)).also { remaining -= it }
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val HOME = "$ORIGIN/music/index.html"

        fun isOrigin(uri: Uri): Boolean = uri.scheme.equals("https", true) &&
            uri.host.equals("appassets.androidplatform.net", true) &&
            (uri.port == -1 || uri.port == 443) && uri.userInfo == null

        fun isPage(uri: Uri): Boolean = isOrigin(uri) &&
            Uri.decode(uri.encodedPath ?: "") in setOf("/music/", "/music/index.html")

        fun error(code: Int, reason: String): WebResourceResponse = WebResourceResponse(
            "text/plain", "UTF-8", code, reason, mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(reason.toByteArray(Charsets.UTF_8)))
    }
}
