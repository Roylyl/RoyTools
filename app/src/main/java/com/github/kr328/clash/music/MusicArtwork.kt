package com.github.kr328.clash.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
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
    private const val COVER_PATH = "/music/share-covers/"
    private const val COVER_HOST = "roylyl.github.io"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_CACHE_BYTES = 32L * 1024 * 1024
    private const val MAX_CACHE_FILES = 256
    private val cacheLock = Any()
    private val commitLock = Any()
    private val cacheGeneration = AtomicLong(0)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Kept for existing callers as a validated relative cache identity, not an AssetManager path.
    fun assetPath(url: String): String? {
        if (url.length > 4096 || runCatching { URI(url) }.isFailure) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val cloud = uri.scheme.equals("https", true) && uri.host.equals(COVER_HOST, true) &&
            (uri.port == -1 || uri.port == 443) && uri.userInfo == null
        // Existing saved appassets cover URLs map to the same cloud-relative image.
        if ((!cloud && !MusicAssets.isOrigin(uri)) || uri.encodedQuery != null || uri.encodedFragment != null) return null
        val encoded = uri.encodedPath ?: return null
        if (!encoded.startsWith(COVER_PATH)) return null
        val relative = Uri.decode(encoded.removePrefix(COVER_PATH))
        if (relative.isBlank() || relative.length > 1024 ||
            relative.any { it == '\\' || it.code < 32 || it.code == 127 } ||
            relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        if (relative.substringAfterLast('.').lowercase() !in setOf("jpg", "jpeg", "png", "webp")) return null
        return relative
    }

    fun decode(context: Context, url: String, size: Int): Bitmap? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val relative = assetPath(url) ?: return null
        val generation = cacheGeneration.get()
        return synchronized(cacheLock) {
            if (generation != cacheGeneration.get()) return@synchronized null
            runCatching {
                val directory = File(context.applicationContext.cacheDir, "music-artwork").canonicalFile
                val cached = File(directory, relative).canonicalFile
                if (!cached.path.startsWith(directory.path + File.separator)) return@runCatching null
                if (cached.isFile && cached.length() in 1L..MAX_BYTES) {
                    decodeFile(cached, size)?.let { bitmap ->
                        if (generation != cacheGeneration.get()) return@runCatching null
                        cached.setLastModified(System.currentTimeMillis())
                        return@runCatching bitmap
                    }
                }
                cached.delete()
                val parent = cached.parentFile ?: return@runCatching null
                if (!parent.isDirectory && !parent.mkdirs()) return@runCatching null
                val temporary = File.createTempFile("cover-", ".part", parent)
                try {
                    if (!download(relative, temporary, generation)) return@runCatching null
                    val bitmap = decodeFile(temporary, size) ?: return@runCatching null
                    val stored = synchronized(commitLock) {
                        if (generation != cacheGeneration.get()) return@runCatching null
                        temporary.renameTo(cached)
                    }
                    if (stored) trimCache(directory, cached)
                    bitmap
                } finally {
                    temporary.delete()
                }
            }.getOrNull()
        }
    }

    /** Invalidates pending writes immediately; file deletion stays on the shared IO dispatcher. */
    internal fun clearCache(context: Context) {
        val app = context.applicationContext
        synchronized(commitLock) { cacheGeneration.incrementAndGet() }
        cleanupScope.launch {
            runCatching {
                synchronized(cacheLock) {
                    val cacheRoot = app.cacheDir.canonicalFile
                    val target = File(cacheRoot, "music-artwork").absoluteFile
                    if (target.canonicalFile != target || target.parentFile != cacheRoot) {
                        throw IOException("封面缓存目录超出应用缓存路径")
                    }
                    if (!target.exists()) return@synchronized
                    // walkFileTree does not follow symbolic links, so nested links are
                    // removed as entries without visiting anything outside this directory.
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
            }.onFailure { Log.w("原生专辑封面缓存清理失败", it) }
        }
    }

    private fun download(relative: String, destination: File, generation: Long): Boolean {
        val address = Uri.Builder().scheme("https").authority(COVER_HOST).path(COVER_PATH).apply {
            relative.split('/').forEach { appendPath(it) }
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
