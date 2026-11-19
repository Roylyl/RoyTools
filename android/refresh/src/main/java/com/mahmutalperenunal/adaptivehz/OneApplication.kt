package com.mahmutalperenunal.adaptivehz

import android.app.Application
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import com.mahmutalperenunal.adaptivehz.core.prefs.AdaptiveHzPrefs
import com.mahmutalperenunal.adaptivehz.core.service.StabilityForegroundService
import rikka.shizuku.Shizuku

open class OneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/")
        Shizuku.addBinderReceivedListenerSticky {
            Handler(Looper.getMainLooper()).post { OneSetup.configure(this) }
        }
    }
}

object OneSetup {
    private var lastRecoveryAt = 0L
    fun isKeepAliveEnabled(context: Context): Boolean =
        context.getSharedPreferences("roytools_settings", Context.MODE_PRIVATE).getBoolean("keep_alive", true)

    fun setKeepAliveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("roytools_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("keep_alive", enabled).apply()
        AdaptiveHzPrefs.setKeepAliveEnabled(context, enabled)
        if (enabled) StabilityForegroundService.start(context)
        else context.stopService(Intent(context, StabilityForegroundService::class.java))
    }
    fun recoverAccessibility(context: Context) {
        if (!AdaptiveHzPrefs.isAppEnabled(context) ||
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
                PackageManager.PERMISSION_GRANTED) return
        val state = com.mahmutalperenunal.adaptivehz.core.engine.AdaptiveHzRuntimeState.getAccessibilityState(context)
        if (state == com.mahmutalperenunal.adaptivehz.core.engine.AdaptiveHzRuntimeState.AccessibilityState.WORKING) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRecoveryAt < 30_000L) return
        lastRecoveryAt = now
        val component = context.packageName +
            "/com.mahmutalperenunal.adaptivehz.core.service.AdaptiveHzService"
        val enabled = Settings.Secure.getString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':')
            .filter { it.isNotBlank() && it != component }
        Settings.Secure.putString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled.joinToString(":"))
        Handler(Looper.getMainLooper()).postDelayed({ configure(context) }, 500)
    }

    fun configure(context: Context) {
        if (!AdaptiveHzPrefs.isControlsEnabled(context)) return
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED) return
        AdaptiveHzPrefs.setAdbGranted(context, true)
        AdaptiveHzPrefs.setInitialSetupCompleted(context, true)
        AdaptiveHzPrefs.setKeepAliveEnabled(context, isKeepAliveEnabled(context))
        val component = context.packageName +
            "/com.mahmutalperenunal.adaptivehz.core.service.AdaptiveHzService"
        val enabled = Settings.Secure.getString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':')
            .filter { it.isNotBlank() }.toMutableList()
        if (component !in enabled) {
            enabled.add(component)
            Settings.Secure.putString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled.joinToString(":"))
        }
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        if (isKeepAliveEnabled(context)) runCatching { StabilityForegroundService.start(context) }
    }
}



