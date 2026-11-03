package com.github.kr328.clash

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService as AndroidTileService
import android.widget.Toast
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.util.RoyToolsShortcuts
import com.github.kr328.clash.util.stopClashService

/** One proxy switch; only the proxy runtime can mark it as active. */
class TileService : AndroidTileService() {
    private var currentProfile = ""
    private var clashRunning = false
    private var statusAvailable = true
    private var receiverRegistered = false

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggleProxy() } else toggleProxy()
    }

    private fun toggleProxy() {
        readRuntimeStatus()
        if (!statusAvailable) {
            Toast.makeText(this, R.string.qs_proxy_status_unavailable, Toast.LENGTH_LONG).show()
            updateTile()
            return
        }

        try {
            if (clashRunning) {
                stopClashService()
            } else {
                // Complete profile checks and VPN consent before starting; opening
                // the permission activity must not optimistically light the tile.
                val intent = Intent(this, RoyToolsShortcutActivity::class.java)
                    .setAction(RoyToolsShortcuts.ACTION_START_PROXY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(
                        PendingIntent.getActivity(
                            this,
                            701,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.qs_proxy_action_failed, Toast.LENGTH_LONG).show()
        }

        readRuntimeStatus()
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!receiverRegistered) {
            registerReceiverCompat(
                receiver,
                IntentFilter().apply {
                    addAction(Intents.ACTION_CLASH_STARTED)
                    addAction(Intents.ACTION_CLASH_STOPPED)
                    addAction(Intents.ACTION_PROFILE_LOADED)
                    addAction(Intents.ACTION_SERVICE_RECREATED)
                },
                Permissions.RECEIVE_SELF_BROADCASTS,
                null
            )
            receiverRegistered = true
        }
        readRuntimeStatus()
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
        if (!receiverRegistered) return
        unregisterReceiver(receiver)
        receiverRegistered = false
    }

    private fun readRuntimeStatus() {
        try {
            val status = contentResolver.call(
                Uri.Builder().scheme("content").authority(Authorities.STATUS_PROVIDER).build(),
                StatusProvider.METHOD_CURRENT_PROFILE,
                null,
                null
            )
            // A running service may not have loaded a profile name yet. The
            // provider's non-null bundle is the authoritative running signal.
            clashRunning = status != null
            currentProfile = status?.getString("name").orEmpty()
            statusAvailable = true
        } catch (_: Exception) {
            statusAvailable = false
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = when {
            !statusAvailable -> Tile.STATE_UNAVAILABLE
            clashRunning -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.qs_proxy_label)
        tile.subtitle = when {
            !statusAvailable -> getString(R.string.qs_proxy_status_unavailable)
            clashRunning && currentProfile.isNotEmpty() -> currentProfile
            clashRunning -> getString(R.string.qs_proxy_on)
            else -> getString(R.string.qs_proxy_off)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_proxy_globe)
        tile.updateTile()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            readRuntimeStatus()
            updateTile()
        }
    }
}
