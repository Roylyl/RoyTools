package com.github.kr328.clash.log

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.github.kr328.clash.common.log.RuntimeLogs
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.util.logsDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object UnifiedLogs {
    enum class Module(val title: String) { Music("音乐"), Proxy("代理"), Refresh("刷新率"), App("应用") }

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val exportSequence = AtomicLong()
    private val legacyLock = Any()
    @Volatile private var legacyImportCancelled = false
    @Volatile private var storage: UnifiedLogStorage? = null
    @Volatile private var app: Context? = null
    private var bound = false

    /** Main process only. Binding the controller does not start VPN/tunnel or another FGS. */
    fun start(context: Context) {
        store(context)
        if (!started.compareAndSet(false, true)) return
        app = context.applicationContext
        RuntimeLogs.sink = { module, priority, tag, message ->
            append(context.applicationContext, if (module == "refresh") Module.Refresh else Module.App,
                "[$tag] $message", androidLevel(priority))
        }
        append(context, Module.App, "默认日志采集已启动")
        scope.launch {
            try { migrateLegacy(context.applicationContext) }
            catch (failure: Exception) { append(context, Module.App, "旧日志迁移未完成：${failure.message}", LogMessage.Level.Warning) }
            finally { main.post { bindProxy() } }
        }
    }

    fun read(context: Context, module: Module): List<LogMessage> = store(context).read(module.id).map { entry ->
        LogMessage(level(entry.level), entry.message, Date(entry.time))
    }

    fun exportText(context: Context, module: Module): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
        return buildString {
            append("RoyTools").append(module.title).append("日志\n")
            for (entry in store(context).readAll(module.id)) append(format.format(Date(entry.time))).append(" [")
                .append(entry.level).append("] ").append(entry.message).append('\n')
        }
    }

    fun snapshotFile(context: Context, module: Module): File {
        val text = exportText(context, module)
        val directory = context.cacheDir.resolve("unified-log-exports").apply { mkdirs() }
        return directory.resolve("RoyTools-${module.id}-${System.currentTimeMillis()}-${exportSequence.incrementAndGet()}.log")
            .also { it.writeText(text) }
    }

    /** Call on IO. No observer is stopped; writes submitted before clear cannot reappear. */
    fun clearAll(context: Context) {
        synchronized(legacyLock) {
            legacyImportCancelled = true
            store(context).clearAll()
        }
        // The former log screen used this separate cache; clear its closed files as well.
        context.logsDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        context.cacheDir.resolve("unified-log-exports").listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    /** One playback diagnostic event, not a timeupdate/state snapshot. */
    fun appendMusic(context: Context, payloadJson: String) {
        if (payloadJson.length > 65_536) return
        val event = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return
        val at = runCatching { Instant.parse(event.optString("at")).toEpochMilli() }.getOrNull()
            ?: System.currentTimeMillis()
        val name = event.optString("event")
        if (name.isBlank()) return
        val severity = when {
            name.contains("error", true) || name.contains("failed", true) || name.contains("rejected", true) -> LogMessage.Level.Error
            name.contains("timeout", true) || name.contains("interrupted", true) -> LogMessage.Level.Warning
            else -> LogMessage.Level.Info
        }
        val fields = buildMap<String, String> {
            for (key in listOf("title", "artist", "track", "position", "duration", "readyState", "error", "hidden", "paused", "detail")) {
                if (event.has(key)) put(key, event.opt(key).toString())
            }
        }
        store(context).append(Module.Music.id, at, severity.name, MusicLogSummary.format(name, fields, payloadJson))
    }

    fun appendMusicJson(context: Context, payloadJson: String) = appendMusic(context, payloadJson)
    fun appendRefresh(context: Context, message: String, level: LogMessage.Level = LogMessage.Level.Info) =
        append(context, Module.Refresh, message, level)

    fun append(context: Context, module: Module, message: String, level: LogMessage.Level = LogMessage.Level.Info) {
        store(context).append(module.id, System.currentTimeMillis(), level.name, message.take(65_536))
    }

    private val Module.id: String get() = name.lowercase(Locale.ROOT)
    private fun store(context: Context): UnifiedLogStorage = storage ?: synchronized(this) {
        storage ?: UnifiedLogStorage(context.applicationContext.filesDir.resolve("unified-logs")).also { storage = it }
    }

    private fun level(name: String): LogMessage.Level = runCatching { LogMessage.Level.valueOf(name) }
        .getOrDefault(LogMessage.Level.Info)
    private fun androidLevel(priority: Int): LogMessage.Level = when {
        priority >= android.util.Log.ERROR -> LogMessage.Level.Error
        priority >= android.util.Log.WARN -> LogMessage.Level.Warning
        priority >= android.util.Log.INFO -> LogMessage.Level.Info
        else -> LogMessage.Level.Debug
    }

    /** Existing archives are streamed once, before the live proxy observer is attached. */
    private class LegacyImportCancelled : RuntimeException()
    private fun migrateLegacy(context: Context) {
        val directory = context.filesDir.resolve("unified-logs").apply { mkdirs() }
        val marker = directory.resolve("legacy-imported")
        if (marker.isFile || directory.resolve("cleared-at").isFile) return
        val files = context.logsDir.listFiles()?.filter { it.isFile }
            ?.mapNotNull { file -> LogFile.parseFromFileName(file.name) }
            ?.sortedBy { it.date.time }.orEmpty()
        val target = store(context)
        var complete = true
        for (file in files) {
            if (legacyImportCancelled) return
            val imported = directory.resolve("legacy-${file.fileName}.imported")
            if (imported.isFile) continue
            try {
                var batch = 0
                LogcatReader(context, file).use { reader ->
                    reader.forEachMessage { message ->
                        synchronized(legacyLock) {
                            if (legacyImportCancelled) throw LegacyImportCancelled()
                            target.append(Module.Proxy.id, message.time.time, message.level.name, message.message.take(65_536))
                        }
                        if (++batch % 256 == 0) target.flush()
                    }
                }
                target.flush()
                synchronized(legacyLock) { if (!legacyImportCancelled) imported.writeText("1") }
            } catch (_: LegacyImportCancelled) {
                return
            } catch (failure: Exception) {
                complete = false
                append(context, Module.App, "旧代理日志读取失败：${file.fileName}；${failure.message}", LogMessage.Level.Warning)
            }
        }
        target.flush()
        synchronized(legacyLock) {
            if (!legacyImportCancelled && complete) marker.writeText("1")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            scope.launch {
                try {
                    binder.unwrap(IRemoteService::class).clash().setLogObserver(object : ILogObserver {
                        override fun newItem(log: LogMessage) {
                            val context = app ?: return
                            store(context).append(Module.Proxy.id, log.time.time, log.level.name, log.message.take(65_536))
                        }
                    })
                } catch (failure: Exception) {
                    app?.let { append(it, Module.App, "代理日志连接失败：${failure.message}", LogMessage.Level.Warning) }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            app?.let { append(it, Module.App, "代理日志连接中断，等待系统重连", LogMessage.Level.Warning) }
        }
        override fun onBindingDied(name: ComponentName?) {
            val context = app ?: return
            if (bound) runCatching { context.unbindService(this) }
            bound = false
            main.post { bindProxy() }
        }
        override fun onNullBinding(name: ComponentName?) {
            val context = app ?: return
            if (bound) runCatching { context.unbindService(this) }
            bound = false
        }
    }

    private fun bindProxy() {
        val context = app ?: return
        if (bound) return
        try { bound = context.bindService(Intent(context, RemoteService::class.java), connection, Context.BIND_AUTO_CREATE) }
        catch (failure: RuntimeException) {
            append(context, Module.App, "代理日志采集不可用：${failure.message}", LogMessage.Level.Warning)
        }
    }
}
