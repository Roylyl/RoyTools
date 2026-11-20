package com.github.kr328.clash

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.clashDir
import com.github.kr328.clash.update.UpdateScheduler
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale

internal fun Context.withConfiguredAppLanguage(): Context {
    if (Build.VERSION.SDK_INT < 33) return this
    val requested = getSystemService(LocaleManager::class.java)?.applicationLocales ?: return this
    if (requested.isEmpty || resources.configuration.locales[0] == requested[0]) return this
    // The first Activity after the default is set may still have its pre-change configuration.
    return createConfigurationContext(Configuration().apply { setLocales(requested) })
}

@Suppress("unused")
class MainApplication : com.mahmutalperenunal.adaptivehz.OneApplication() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(if (Build.VERSION.SDK_INT < 33) base?.withLegacyAppLanguage() else base)

        Global.init(this)
    }

    override fun onCreate() {
        super.onCreate()

        val processName = currentProcessName
        if (processName == packageName && Build.VERSION.SDK_INT >= 33) {
            setDefaultAppLanguage()
        }
        extractGeoFiles()

        Log.d("Process $processName started")

        if (processName == packageName) {
            com.github.kr328.clash.log.UnifiedLogs.start(this)
            val settings = getSharedPreferences("roytools_settings", MODE_PRIVATE)
            if (!settings.contains("keep_alive")) {
                // Bootstrap from the persisted legacy file before remote providers are
                // available. Runtime ServiceStore access still uses the proxy provider.
                val legacyKeepAlive = getSharedPreferences("service", MODE_PRIVATE)
                    .getBoolean(ServiceStore.KEY_AGGRESSIVE_KEEP_ALIVE, true)
                settings.edit().putBoolean("keep_alive", legacyKeepAlive).apply()
            }
            KeepAliveCoordinator.start(this)
            Remote.launch()
            UpdateScheduler.schedulePeriodic(this)
        } else {
            sendServiceRecreated()
        }
    }

    @androidx.annotation.RequiresApi(33)
    private fun setDefaultAppLanguage() {
        val settings = getSharedPreferences("roytools_settings", MODE_PRIVATE)
        if (settings.getBoolean("app_language_initialized", false)) return
        val manager = getSystemService(LocaleManager::class.java) ?: return
        if (manager.applicationLocales.isEmpty) {
            val systemLocale = manager.systemLocales[0]
            val language = systemLocale?.takeIf { it.language == "zh" } ?: Locale.CHINESE
            manager.applicationLocales = LocaleList(language)
        }
        // Respect later choices in Android's App Languages settings, including
        // a deliberate return to the system language.
        settings.edit().putBoolean("app_language_initialized", true).apply()
    }

    private fun Context.withLegacyAppLanguage(): Context {
        val requested = AppCompatDelegate.getApplicationLocales()
        if (!requested.isEmpty) return createConfigurationContext(Configuration().apply {
            setLocales(LocaleList.forLanguageTags(requested.toLanguageTags()))
        })
        if (resources.configuration.locales[0]?.language == "zh") return this
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh"))
        return createConfigurationContext(Configuration().apply { setLocale(Locale.CHINESE) })
    }

    private fun extractGeoFiles() {
        val directory = clashDir
        try {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create Geo directory" }
            // Both the main and proxy processes prepare these same assets on startup.
            // Recheck timestamps only after holding the shared filesystem lock.
            RandomAccessFile(File(directory, ".geo-assets.lock"), "rw").use { lockFile ->
                val lock = lockFile.channel.lock()
                try {
                    val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
                    for (name in listOf("geoip.metadb", "geosite.dat", "ASN.mmdb", "BundleMRS.7z")) {
                        val destination = File(directory, name)
                        if (destination.isFile && destination.length() > 0 && destination.lastModified() >= updateDate) continue
                        val temporary = File.createTempFile(".$name-", ".tmp", directory)
                        try {
                            assets.open(name).use { input ->
                                FileOutputStream(temporary).use { output ->
                                    input.copyTo(output)
                                    output.fd.sync()
                                }
                            }
                            check(temporary.length() > 0) { "Empty Geo asset" }
                            check(temporary.setLastModified(maxOf(System.currentTimeMillis(), updateDate))) {
                                "Cannot timestamp Geo asset"
                            }
                            // Rename on the same filesystem replaces the name atomically;
                            // readers holding the old descriptor can finish using that file.
                            android.system.Os.rename(temporary.absolutePath, destination.absolutePath)
                        } catch (failure: Exception) {
                            Log.w("Geo资源${name}更新失败，保留现有文件", failure)
                        } finally {
                            temporary.delete()
                        }
                    }
                } finally {
                    lock.release()
                }
            }
        } catch (failure: Exception) {
            Log.w("Geo资源准备未完成，未清理现有文件", failure)
        }
    }

    fun finalize() {
        Global.destroy()
    }
}

