package com.mahmutalperenunal.adaptivehz.core.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.mahmutalperenunal.adaptivehz.R
import com.mahmutalperenunal.adaptivehz.core.engine.AdaptiveHzRuntimeState
import com.mahmutalperenunal.adaptivehz.core.engine.model.AdaptiveHzMode
import com.mahmutalperenunal.adaptivehz.core.engine.model.RefreshRateApplyResult
import com.mahmutalperenunal.adaptivehz.core.engine.model.isOperationalSuccess
import com.mahmutalperenunal.adaptivehz.core.prefs.AdaptiveHzPrefs
import com.mahmutalperenunal.adaptivehz.core.quickaccess.QuickAccessManager
import com.mahmutalperenunal.adaptivehz.core.shizuku.ShizukuAccess
import com.mahmutalperenunal.adaptivehz.core.shizuku.ShizukuAccessState

/** A single Adaptive/System switch, independent of the manual minimum/maximum modes. */
class AdaptiveHzTileService : TileService() {
    private var stopObservingMode: (() -> Unit)? = null
    private var changingMode = false
    private var failedMode: AdaptiveHzMode? = null

    override fun onTileAdded() {
        super.onTileAdded()
        QuickAccessManager.setQuickSettingsTileAdded(applicationContext, true)
        updateTile()
    }

    override fun onTileRemoved() {
        QuickAccessManager.setQuickSettingsTileAdded(applicationContext, false)
        super.onTileRemoved()
    }

    override fun onStartListening() {
        super.onStartListening()
        QuickAccessManager.setQuickSettingsTileAdded(applicationContext, true)
        if (stopObservingMode == null) {
            stopObservingMode = AdaptiveHzPrefs.observeCurrentMode(applicationContext) { mode ->
                if (failedMode != mode) failedMode = null
                updateTile()
            }
        }
        updateTile()
    }

    override fun onStopListening() {
        stopObserving()
        super.onStopListening()
    }

    override fun onDestroy() {
        stopObserving()
        super.onDestroy()
    }

    private fun stopObserving() {
        stopObservingMode?.invoke()
        stopObservingMode = null
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggleAdaptive() } else toggleAdaptive()
    }

    private fun toggleAdaptive() {
        if (changingMode) return
        val appContext = applicationContext
        if (!AdaptiveHzPrefs.isControlsEnabled(appContext)) {
            openRefreshSettings()
            return
        }
        val nextMode = if (AdaptiveHzPrefs.getCurrentMode(appContext) == AdaptiveHzMode.ADAPTIVE) {
            AdaptiveHzMode.OFF
        } else {
            AdaptiveHzMode.ADAPTIVE
        }

        // Disabling must remain possible when setup is incomplete. Enabling
        // requires the same live prerequisites as the refresh-rate panel.
        if (nextMode == AdaptiveHzMode.ADAPTIVE && !isReady()) {
            Toast.makeText(this, R.string.qs_refresh_setup_hint, Toast.LENGTH_LONG).show()
            openRefreshSettings()
            updateTile()
            return
        }

        changingMode = true
        failedMode = null
        updateTile()
        try {
            AdaptiveHzActionHandler.applyModeAsync(
                context = appContext,
                mode = nextMode,
                onComplete = { result ->
                    if (AdaptiveHzPrefs.isControlsEnabled(appContext) && AdaptiveHzPrefs.getCurrentMode(appContext) == nextMode &&
                        !result.isOperationalSuccess && result != RefreshRateApplyResult.NoOperation
                    ) {
                        failedMode = nextMode
                        Toast.makeText(appContext, R.string.qs_refresh_action_failed, Toast.LENGTH_LONG).show()
                    }
                },
                onSettled = {
                    changingMode = false
                    updateTile()
                }
            )
        } catch (_: Exception) {
            changingMode = false
            failedMode = nextMode
            Toast.makeText(this, R.string.qs_refresh_action_failed, Toast.LENGTH_LONG).show()
            updateTile()
        }
    }

    private fun isReady(): Boolean = runCatching {
        ShizukuAccess.readState() == ShizukuAccessState.READY &&
            AdaptiveHzRuntimeState.isAdbReady(applicationContext) &&
            AdaptiveHzRuntimeState.getAccessibilityState(applicationContext) ==
            AdaptiveHzRuntimeState.AccessibilityState.WORKING
    }.getOrDefault(false)

    private fun openRefreshSettings() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?.putExtra("roytools_tab", "refresh")
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        702,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.qs_refresh_action_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val mode = AdaptiveHzPrefs.getCurrentMode(applicationContext)
        val adaptive = mode == AdaptiveHzMode.ADAPTIVE
        val ready = isReady()
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_refresh_phone)
        tile.label = getString(R.string.qs_refresh_label)
        tile.subtitle = when {
            changingMode -> getString(R.string.qs_refresh_changing)
            failedMode == mode -> getString(R.string.qs_refresh_action_failed)
            adaptive && !ready -> getString(R.string.qs_refresh_setup_required)
            adaptive -> getString(R.string.qs_refresh_on)
            mode == AdaptiveHzMode.OFF -> getString(R.string.qs_refresh_system_default)
            else -> getString(R.string.qs_refresh_manual)
        }
        tile.state = when {
            changingMode -> Tile.STATE_UNAVAILABLE
            adaptive && ready -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
