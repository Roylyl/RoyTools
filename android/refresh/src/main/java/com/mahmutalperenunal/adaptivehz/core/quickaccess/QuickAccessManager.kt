package com.mahmutalperenunal.adaptivehz.core.quickaccess

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.ContextCompat
import com.mahmutalperenunal.adaptivehz.R
import com.mahmutalperenunal.adaptivehz.core.prefs.AdaptiveHzPrefs
import com.mahmutalperenunal.adaptivehz.core.service.AdaptiveHzTileService

/** Platform integration for installing the Adaptive Hz Quick Settings tile. */
object QuickAccessManager {

    enum class RequestResult {
        ADDED,
        ALREADY_ADDED,
        NOT_ADDED,
        UNSUPPORTED,
        FAILED
    }

    fun isQuickSettingsTileAdded(context: Context): Boolean {
        return AdaptiveHzPrefs.isQuickSettingsTileAdded(context.applicationContext)
    }

    fun setQuickSettingsTileAdded(context: Context, added: Boolean) {
        AdaptiveHzPrefs.setQuickSettingsTileAdded(context.applicationContext, added)
    }

    fun requestQuickSettingsTile(
        context: Context,
        onResult: (RequestResult) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < 33) {
            onResult(RequestResult.UNSUPPORTED)
            return
        }

        val appContext = context.applicationContext
        val statusBarManager = appContext.getSystemService(StatusBarManager::class.java)

        if (statusBarManager == null) {
            onResult(RequestResult.FAILED)
            return
        }

        runCatching {
            statusBarManager.requestAddTileService(
                ComponentName(appContext, AdaptiveHzTileService::class.java),
                appContext.getString(R.string.qs_refresh_label),
                Icon.createWithResource(appContext, R.drawable.ic_qs_refresh_phone),
                ContextCompat.getMainExecutor(appContext)
            ) { result ->
                val mapped = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> RequestResult.ADDED
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> RequestResult.ALREADY_ADDED
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> RequestResult.NOT_ADDED
                    else -> RequestResult.FAILED
                }

                if (mapped == RequestResult.ADDED || mapped == RequestResult.ALREADY_ADDED) {
                    setQuickSettingsTileAdded(appContext, true)
                }

                onResult(mapped)
            }
        }.onFailure {
            onResult(RequestResult.FAILED)
        }
    }
}
