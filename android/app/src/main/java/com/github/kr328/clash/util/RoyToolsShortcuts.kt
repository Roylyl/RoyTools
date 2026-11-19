package com.github.kr328.clash.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.R
import com.github.kr328.clash.RoyToolsShortcutActivity
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.store.UiStore.Companion.mainActivityAlias

/** Publishes the actions supported by the launcher without exceeding the device's limit. */
object RoyToolsShortcuts {
    const val ACTION_TOGGLE_PROXY = "com.roylyl.tools.shortcut.TOGGLE_PROXY"
    const val ACTION_TOGGLE_REFRESH_ADAPTIVE = "com.roylyl.tools.shortcut.TOGGLE_REFRESH_ADAPTIVE"

    // Explicit actions remain available to notification controls and Quick Settings tiles.
    const val ACTION_START_PROXY = "com.roylyl.tools.shortcut.START_PROXY"
    const val ACTION_STOP_PROXY = "com.roylyl.tools.shortcut.STOP_PROXY"
    const val ACTION_REFRESH_DEFAULT = "com.roylyl.tools.shortcut.REFRESH_DEFAULT"
    const val ACTION_REFRESH_60 = "com.roylyl.tools.shortcut.REFRESH_60"
    const val ACTION_REFRESH_120 = "com.roylyl.tools.shortcut.REFRESH_120"
    const val ACTION_REFRESH_ADAPTIVE = "com.roylyl.tools.shortcut.REFRESH_ADAPTIVE"

    private data class Action(val id: String, val label: String, val action: String, val icon: Int)

    private val actions = listOf(
        Action("toggle_proxy", "代理 开/关", ACTION_TOGGLE_PROXY, R.drawable.ic_shortcut_proxy_start),
        Action("toggle_adaptive_refresh", "自适应刷新率 开/关", ACTION_TOGGLE_REFRESH_ADAPTIVE,
            R.drawable.ic_shortcut_refresh_adaptive)
    )

    /** Returns the number published, or -1 when the system rejected this update. */
    fun update(context: Context): Int = try {
        if (UiStore(context).hideAppIcon) {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            0
        } else {
            val limit = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(0)
            val shortcuts = actions.take(limit).mapIndexed { rank, action ->
                ShortcutInfoCompat.Builder(context, action.id)
                    .setActivity(context.mainActivityAlias)
                    .setShortLabel(action.label)
                    .setLongLabel(action.label)
                    .setIcon(IconCompat.createWithResource(context, action.icon))
                    .setIntent(Intent(context, RoyToolsShortcutActivity::class.java)
                        .setAction(action.action)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION))
                    .setRank(rank)
                    .build()
            }
            // Replaces all six old dynamic entries with the two launcher toggles.
            if (ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)) shortcuts.size else -1
        }
    } catch (e: RuntimeException) {
        Log.w("RoyToolsShortcuts", "Unable to publish launcher shortcuts", e)
        -1
    }
}
