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
    private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    private const val MAX_CACHE_FILES = 256
    private const val MAX_BITMAP_BYTES = 8 * 1024 * 1024
    private const val BITMAP_EDGE = 320
    private val cacheLock = Any()
    private val commitLock = Any()
    private val cacheGeneration = AtomicLong(0)
    private var clearedGeneration = 0L // Protected by cacheLock.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var legacyCoverPaths: Map<String, Source>? = null
    // All native consumers share one sampled bitmap per original file and revision.
    // Loading stays serialized with disk writes, so concurrent requests also share
    // the first fetch/decode instead of storing a copy for each song or surface.
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

    /** WebView, notifications and widgets share the same original file and fetch lock. */
    fun openResponse(context: Context, url: String): WebResourceResponse? {
        val request = parseSource(url) ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val headers = mapOf("Access-Control-Allow-Origin" to MusicAssets.ORIGIN,
            "Cache-Control" to "no-cache", "X-Content-Type-Options" to "nosniff")
        val generation = cacheGeneration.get()
        val response = synchronized(cacheLock) {
            // decode also validates the downloaded image before committing its original.
            if (decode(context, url) == null || generation != cacheGeneration.get()) return@synchronized null
            val original = if (request.legacy) legacyCoverPaths?.get(request.relative) else null
            val source = original?.copy(revision = request.revision ?: original.revision) ?: request
            val directory = File(context.applicationContext.cacheDir, "music-artwork").canonicalFile
            val cached = File(directory, source.cachePath).canonicalFile
            if (!cached.path.startsWith(directory.path + File.separator) || !cached.isFile) return@synchronized null
            runCatching {
                val mime = when (source.relative.substringAfterLast('.').lowercase()) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> "image/jpeg"
                }
                WebResourceResponse(mime, null, 200, "OK",
                    headers + ("Content-Length" to cached.length().toString()), cached.inputStream())
            }.getOrNull()
        }
        // Do not fall through to a second network request when the shared fetch fails.
        return response ?: WebResourceResponse("text/plain", "UTF-8", 502, "Artwork unavailable",
            headers, ByteArrayInputStream(ByteArray(0)))
    }

    fun decode(context: Context, url: String): Bitmap? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val request = parseSource(url) ?: return null
        val generation = cacheGeneration.get()
        return synchronized(cacheLock) {
            if (generation != cacheGeneration.get()) return@synchronized null
            runCatching {
                clearInvalidatedCache(context.applicationContext, generation)
                val source = if (!request.legacy) request else {
                    val original = originalCoverPaths(context)[request.relative] ?: return@runCatching null
                    original.copy(revision = request.revision ?: original.revision)
                }
                val relative = source.cachePath
                val bitmapKey = "$generation:$relative"
                val directory = File(context.applicationContext.cacheDir, "music-artwork").canonicalFile
                val cached = File(directory, relative).canonicalFile
                if (!cached.path.startsWith(directory.path + File.separator)) return@runCatching null
                if (cached.isFile && cached.length() in 1L..MAX_BYTES) {
                    bitmaps.get(bitmapKey)?.takeUnless { it.isRecycled }?.let { return@runCatching it }
                    decodeFile(cached, BITMAP_EDGE)?.let { bitmap ->
                        if (generation != cacheGeneration.get()) return@runCatching null
                        cached.setLastModified(System.currentTimeMillis())
                        bitmaps.put(bitmapKey, bitmap)
                        return@runCatching bitmap
                    }
                }
                cached.delete()
                // Old saved notifications contain the website's derived JPG URL. Resolve
                // those covers through the bundled track metadata, then fetch the original.
                val parent = cached.parentFile ?: return@runCatching null
                if (!parent.isDirectory && !parent.mkdirs()) return@runCatching null
                val temporary = File.createTempFile("cover-", ".part", parent)
                try {
                    if (!download(source.relative, source.revision, temporary, generation)) return@runCatching null
                    val bitmap = decodeFile(temporary, BITMAP_EDGE) ?: return@runCatching null
                    val stored = synchronized(commitLock) {
                        if (generation != cacheGeneration.get()) return@runCatching null
                        temporary.renameTo(cached)
                    }
                    if (stored) trimCache(directory, cached)
                    bitmaps.put(bitmapKey, bitmap)
                    bitmap
                } finally {
                    temporary.delete()
                }
            }.getOrNull()
        }
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
        val cacheRoot = context.cacheDir.canonicalFile
        val target = File(cacheRoot, "music-artwork").absoluteFile
        if (target.canonicalFile != target || target.parentFile != cacheRoot) {
            throw IOException("封面缓存目录超出应用缓存路径")
        }
        bitmaps.evictAll()
        if (target.exists()) {
            // Do not follow links outside this application's artwork cache.
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
            if (connection.responseCode != HttpURLConnection.HTTP_OK || connection.contentLengthLong > MAX_BYTES) return false
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

    private fun trimCache(directory: File, keep: File) {
        val files = directory.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }
            .toList().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (total <= MAX_CACHE_BYTES && count <= MAX_CACHE_FILES) break
            if (file == keep) continue
            val length = file.length()
            if (file.delete()) { total -= length; count-- }
        }
    }
}
