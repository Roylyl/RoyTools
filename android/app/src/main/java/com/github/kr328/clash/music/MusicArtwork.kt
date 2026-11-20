package com.github.kr328.clash.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.webkit.WebResourceResponse
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong

/** Notification/widget artwork uses only the configured public cover host, off the main thread. */
internal object MusicArtwork {
    private const val COVER_PATH = "/Roylyl/Music/main/"
    private const val COVER_HOST = "raw.githubusercontent.com"
    private const val LEGACY_COVER_PATH = "/music/share-covers/"
    private const val LEGACY_COVER_HOST = "roylyl.github.io"
    private const val MAX_BYTES = 16L * 1024 * 1024
    private const val MAX_BITMAP_BYTES = 8 * 1024 * 1024
    private const val BITMAP_EDGE = 320
    private val cacheLock = Any()
    private val commitLock = Any()
    private val sourceLocks = Array(32) { Any() }
    private val cacheGeneration = AtomicLong(0)
    private var clearedGeneration = 0L // Protected by cacheLock.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var legacyCoverPaths: Map<String, Source>? = null
    // The mini-player, notifications and widgets share one sampled bitmap per cover.
    private val bitmaps = object : LruCache<String, Bitmap>(MAX_BITMAP_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private data class Source(val relative: String, val revision: String?, val legacy: Boolean) {
        val cachePath: String get() = if (revision == null) relative else "$relative.rev-$revision"
    }

    // Callers compare this identity without doing IO on the main thread.
    fun assetPath(url: String): String? {
        val request = parseSource(url) ?: return null
        val original = if (request.legacy) legacyCoverPaths?.get(request.relative) else null
        val source = original?.copy(revision = request.revision ?: original.revision) ?: request
        return "${cacheGeneration.get()}:${source.cachePath}"
    }

    fun peek(cacheKey: String?): Bitmap? =
        cacheKey?.let(bitmaps::get)?.takeUnless { it.isRecycled }

    private fun parseSource(url: String): Source? {
        if (url.length > 4096 || runCatching { URI(url) }.isFailure) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val cloud = uri.scheme.equals("https", true) &&
            (uri.host.equals(COVER_HOST, true) || uri.host.equals(LEGACY_COVER_HOST, true)) &&
            (uri.port == -1 || uri.port == 443) && uri.userInfo == null
        if ((!cloud && !MusicAssets.isOrigin(uri)) || uri.encodedFragment != null) return null
        val query = uri.encodedQuery
        val revision = if (query == null) null
            else Regex("v=([A-Za-z0-9._-]{1,128})").matchEntire(query)?.groupValues?.get(1) ?: return null
        val encoded = uri.encodedPath ?: return null
        val legacy = !uri.host.equals(COVER_HOST, true)
        val prefix = if (legacy) LEGACY_COVER_PATH else COVER_PATH
        if (!encoded.startsWith(prefix)) return null
        val relative = validRelativePath(Uri.decode(encoded.removePrefix(prefix))) ?: return null
        return Source(relative, revision, legacy)
    }

    private fun validRelativePath(relative: String): String? {
        if (relative.isBlank() || relative.length > 1024 ||
            relative.any { it == '\\' || it.code < 32 || it.code == 127 } ||
            relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        if (relative.substringAfterLast('.').lowercase() !in setOf("jpg", "jpeg", "png", "webp")) return null
        return relative
    }

    /** GET acquires an original, while HEAD looks up that same file without a network request. */
    fun openResponse(context: Context, url: String, method: String): WebResourceResponse? {
        val request = parseSource(url) ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val headers = mapOf("Access-Control-Allow-Origin" to MusicAssets.ORIGIN,
            "Access-Control-Expose-Headers" to "X-RoyTools-Cover-Cache",
            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
        val head = method == "HEAD"
        val response = withOriginal(context, request, !head) { source, cached, _ ->
            val mime = when (source.relative.substringAfterLast('.').lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            WebResourceResponse(mime, null, 200, "OK", headers + mapOf(
                "Content-Length" to cached.length().toString(), "X-RoyTools-Cover-Cache" to "1"
            ), if (head) ByteArrayInputStream(ByteArray(0)) else cached.inputStream())
        }
        // Do not fall through to a second network request when the shared fetch fails.
        return response ?: WebResourceResponse("text/plain", "UTF-8", if (head) 404 else 502,
            if (head) "Not Found" else "Artwork unavailable", headers, ByteArrayInputStream(ByteArray(0)))
    }

    fun decode(context: Context, url: String): Bitmap? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val request = parseSource(url) ?: return null
        return withOriginal(context, request, true) { source, cached, generation ->
            val bitmapKey = "$generation:${source.cachePath}"
            bitmaps.get(bitmapKey)?.takeUnless { it.isRecycled } ?: decodeFile(cached, BITMAP_EDGE)?.also {
                if (generation == cacheGeneration.get()) bitmaps.put(bitmapKey, it)
            }?.takeIf { generation == cacheGeneration.get() }
        }
    }

    private inline fun <T> withOriginal(context: Context, request: Source, allowFetch: Boolean,
                                        use: (Source, File, Long) -> T?): T? {
        val app = context.applicationContext
        val generation = cacheGeneration.get()
        val source = if (!request.legacy) request else synchronized(cacheLock) {
            val original = originalCoverPaths(app)[request.relative] ?: return null
            original.copy(revision = request.revision ?: original.revision)
        }
        val stripe = sourceLocks[(source.relative.hashCode() and Int.MAX_VALUE) % sourceLocks.size]
        return synchronized(stripe) {
            runCatching {
                synchronized(cacheLock) {
                    if (generation != cacheGeneration.get()) return@runCatching null
                    clearInvalidatedCache(app, generation)
                }
                val relative = source.cachePath
                val directory = File(app.filesDir, "music-artwork").canonicalFile
                val cached = File(directory, relative).canonicalFile
                if (!cached.path.startsWith(directory.path + File.separator)) return@runCatching null
                // Reuse originals downloaded by earlier versions instead of fetching again.
                if (!cached.isFile) {
                    val legacyRoot = File(app.cacheDir, "music-artwork").canonicalFile
                    val legacy = File(legacyRoot, relative).canonicalFile
                    if (legacy.path.startsWith(legacyRoot.path + File.separator) && legacy.isFile) {
                        synchronized(commitLock) {
                            if (generation != cacheGeneration.get()) return@runCatching null
                            cached.parentFile?.mkdirs()
                            if (!legacy.renameTo(cached)) runCatching {
                                legacy.copyTo(cached, overwrite = false)
                                legacy.delete()
                            }
                        }
                    }
                }
                if (!cached.isFile) {
                    if (!allowFetch) return@runCatching null
                    val parent = cached.parentFile ?: return@runCatching null
                    if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return@runCatching null
                    val temporary = File.createTempFile("cover-", ".part", parent)
                    try {
                        if (!download(source.relative, source.revision, temporary, generation)) return@runCatching null
                        // Validate once before storing. WebView can decode from the original directly.
                        if (decodeFile(temporary, 64) == null) return@runCatching null
                        val stored = synchronized(commitLock) {
                            generation == cacheGeneration.get() && temporary.renameTo(cached)
                        }
                        if (!stored) return@runCatching null
                    } finally {
                        temporary.delete()
                    }
                }
                if (generation != cacheGeneration.get() || cached.length() !in 1L..MAX_BYTES) return@runCatching null
                use(source, cached, generation)
            }.onFailure { Log.w("原生封面读写失败", it) }.getOrNull()
        }
    }

    internal fun cacheEntries(context: Context): String = synchronized(cacheLock) {
        val app = context.applicationContext
        clearInvalidatedCache(app, cacheGeneration.get())
        val directory = File(app.filesDir, "music-artwork")
        val entries = JSONArray()
        if (directory.isDirectory) Files.walk(directory.toPath()).use { tree ->
            tree.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.forEach { path ->
                val file = path.toFile()
                val size = file.length()
                if (size !in 1L..MAX_BYTES) return@forEach
                val key = file.relativeTo(directory).invariantSeparatorsPath
                val match = Regex("^(.+\\.(?:jpe?g|png|webp))(?:\\.rev-([A-Za-z0-9._-]{1,128}))?$",
                    RegexOption.IGNORE_CASE).matchEntire(key) ?: return@forEach
                val relative = validRelativePath(match.groupValues[1]) ?: return@forEach
                val revision = match.groupValues[2].ifBlank { null }
                entries.put(JSONArray().put(relative).put(revision).put(size))
            }
        }
        entries.toString()
    }

    /** Called only by the artwork IO worker while holding cacheLock. */
    private fun originalCoverPaths(context: Context): Map<String, Source> {
        legacyCoverPaths?.let { return it }
        val paths = runCatching {
            val catalog = context.applicationContext.assets.open("music/data/catalog.json")
                .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val tracks = catalog.getJSONArray("tracks")
            buildMap {
                for (index in 0 until tracks.length()) {
                    val track = tracks.getJSONObject(index)
                    val cover = validRelativePath(track.optString("cover")) ?: continue
                    val revision = track.optString("coverRevision").takeIf {
                        Regex("[A-Za-z0-9._-]{1,128}").matches(it)
                    }
                    put(cover.substringBeforeLast('.') + ".jpg", Source(cover, revision, false))
                }
            }
        }.getOrDefault(emptyMap())
        legacyCoverPaths = paths
        return paths
    }

    /** Invalidates pending writes immediately; file deletion stays on the shared IO dispatcher. */
    internal fun clearCache(context: Context) {
        val app = context.applicationContext
        val generation = synchronized(commitLock) { cacheGeneration.incrementAndGet() }
        cleanupScope.launch {
            runCatching {
                synchronized(cacheLock) {
                    clearInvalidatedCache(app, generation)
                }
            }.onFailure { Log.w("原生专辑封面缓存清理失败", it) }
        }
    }

    /** Under cacheLock; a new decode can finish pending cleanup before writing fresh artwork. */
    private fun clearInvalidatedCache(context: Context, generation: Long) {
        if (generation <= clearedGeneration) return
        bitmaps.evictAll()
        for (root in listOf(context.filesDir, context.cacheDir)) {
            val cacheRoot = root.canonicalFile
            val target = File(cacheRoot, "music-artwork").absoluteFile
            if (target.canonicalFile != target || target.parentFile != cacheRoot) {
                throw IOException("封面缓存目录超出应用存储路径")
            }
            if (!target.exists()) continue
            // Do not follow links outside this application's artwork storage.
            Files.walkFileTree(target.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        // Older queued cleanup jobs now leave artwork fetched in this generation alone.
        clearedGeneration = generation
    }

    private fun download(relative: String, revision: String?, destination: File, generation: Long): Boolean {
        val address = Uri.Builder().scheme("https").authority(COVER_HOST).path(COVER_PATH).apply {
            relative.split('/').forEach { appendPath(it) }
            revision?.let { appendQueryParameter("v", it) }
        }.build().toString()
        val connection = URL(address).openConnection() as HttpURLConnection
        val startedAt = SystemClock.elapsedRealtime()
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp")
            if (connection.responseCode != HttpURLConnection.HTTP_OK || connection.contentLengthLong > MAX_BYTES) {
                Log.w("封面服务器返回${connection.responseCode}，文件长度${connection.contentLengthLong}")
                return false
            }
            var received = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (generation != cacheGeneration.get() ||
                            SystemClock.elapsedRealtime() - startedAt > 15_000L) return false
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > MAX_BYTES) return false
                        output.write(buffer, 0, count)
                    }
                }
            }
            return received > 0
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeFile(file: File, size: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth !in 1..32768 || options.outHeight !in 1..32768) return null
        val limit = size.coerceIn(16, 320)
        var sample = 1
        while (options.outWidth / sample > limit || options.outHeight / sample > limit) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        return BitmapFactory.decodeFile(file.path, options)
    }

}
