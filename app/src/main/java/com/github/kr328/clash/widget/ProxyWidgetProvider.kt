package com.github.kr328.clash.widget

import android.app.PendingIntent
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.Toast
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.ProxyActivity
import com.github.kr328.clash.R
import com.github.kr328.clash.RoyToolsShortcutActivity
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.util.ProxyWidgetState as State
import com.github.kr328.clash.util.RoyToolsShortcuts
import com.mahmutalperenunal.adaptivehz.core.prefs.AdaptiveHzPrefs
import com.mahmutalperenunal.adaptivehz.core.engine.model.AdaptiveHzMode
import java.util.Locale
import java.util.concurrent.Executors
import java.time.LocalDate
import java.time.ZoneId

/** One 4×2 proxy widget. Sampling belongs to the existing proxy runtime, not the launcher. */
class ProxyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        scheduleNextDay(context)
        refreshAsync(context, ids)
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(dayIntent(context))
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        refreshAsync(context, intArrayOf(id))

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_NEW_DAY, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                scheduleNextDay(context)
                refreshAsync(context, widgetIds(context))
            }
            ACTION_MUSIC -> {
                val command = intent.getStringExtra("command") ?: return
                if (command !in setOf("previous", "toggle", "next")) return
                com.github.kr328.clash.common.ui.TouchFeedback.click(View(context))
                val pending = goAsync()
                worker.execute {
                    try {
                        WidgetMusicState.action(context.applicationContext, command)
                        refreshNow(context, widgetIds(context))
                    } finally { pending.finish() }
                }
            }
            ACTION_MODE -> {
                val mode = intent.getStringExtra(EXTRA_MODE)
                if (mode !in modes) return
                com.github.kr328.clash.common.ui.TouchFeedback.click(View(context))
                val pending = goAsync()
                worker.execute {
                    try {
                        val result = context.contentResolver.call(statusUri, StatusProvider.METHOD_WIDGET_MODE, mode, null)
                        if (result?.getBoolean(State.KEY_SUCCESS) == true) render(context, result, widgetIds(context))
                        else {
                            val text = if (result?.getString(State.KEY_ERROR) == "stopped")
                                "请先开启代理" else "模式切换失败，请打开RoyTools检查"
                            android.os.Handler(context.mainLooper).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
                        }
                    } catch (_: Exception) {
                        android.os.Handler(context.mainLooper).post {
                            Toast.makeText(context, "暂时无法连接代理服务", Toast.LENGTH_SHORT).show()
                        }
                    } finally { pending.finish() }
                }
            }
            State.ACTION_CHANGED -> intent.extras?.let { snapshot ->
                val ids = widgetIds(context)
                if (ids.isNotEmpty()) {
                    val pending = goAsync()
                    worker.execute { try { render(context, snapshot, ids) } finally { pending.finish() } }
                }
            }
            WidgetMusicState.ACTION_CHANGED, "${context.packageName}.intent.action.REFRESH_WIDGET_CHANGED",
            Intent.ACTION_CONFIGURATION_CHANGED,
            "${context.packageName}.intent.action.CLASH_STARTED",
            "${context.packageName}.intent.action.CLASH_STOPPED",
            "${context.packageName}.intent.action.PROFILE_CHANGED",
            "${context.packageName}.intent.action.PROFILE_LOADED" -> refreshAsync(context, widgetIds(context))
            else -> super.onReceive(context, intent)
        }
    }

    private fun refreshAsync(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        worker.execute {
            try { refreshNow(context, ids) } finally { pending.finish() }
        }
    }

    companion object {
        private const val ACTION_MODE = "com.roylyl.tools.widget.SET_PROXY_MODE"
        private const val ACTION_MUSIC = "com.roylyl.tools.widget.MUSIC_COMMAND"
        private const val ACTION_NEW_DAY = "com.roylyl.tools.widget.NEW_DAY"
        private const val EXTRA_MODE = "mode"
        private const val CACHE = "roytools_proxy_widget"
        private val modes = listOf("default", "rule", "global", "direct")
        private val modeViews = intArrayOf(R.id.widget_mode_default, R.id.widget_mode_rule,
            R.id.widget_mode_global, R.id.widget_mode_direct)
        private val refreshViews = intArrayOf(R.id.widget_refresh_off, R.id.widget_refresh_60,
            R.id.widget_refresh_120, R.id.widget_refresh_adaptive)
        private val refreshActions = arrayOf(RoyToolsShortcuts.ACTION_REFRESH_DEFAULT,
            RoyToolsShortcuts.ACTION_REFRESH_60, RoyToolsShortcuts.ACTION_REFRESH_120,
            RoyToolsShortcuts.ACTION_REFRESH_ADAPTIVE)
        private val worker = Executors.newSingleThreadExecutor()
        private val statusUri = Uri.Builder().scheme("content").authority(Authorities.STATUS_PROVIDER).build()
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun dayIntent(context: Context) = PendingIntent.getBroadcast(context, 840,
            Intent(context, ProxyWidgetProvider::class.java).setAction(ACTION_NEW_DAY), FLAGS)

        private fun scheduleNextDay(context: Context) {
            if (widgetIds(context).isEmpty()) return
            val midnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            context.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, midnight, dayIntent(context))
        }

        fun refresh(context: Context) {
            val ids = widgetIds(context)
            if (ids.isNotEmpty()) worker.execute { refreshNow(context.applicationContext, ids) }
        }

        private fun widgetIds(context: Context) = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ProxyWidgetProvider::class.java))

        private fun refreshNow(context: Context, ids: IntArray) {
            val prefs = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
            val day = LocalDate.now().toString()
            val snapshot = runCatching {
                context.contentResolver.call(statusUri, StatusProvider.METHOD_WIDGET_STATE, null, null)
            }.getOrNull() ?: Bundle().apply {
                putBoolean(State.KEY_RUNNING, false)
                putBoolean(State.KEY_READY, false)
                putString(State.KEY_NODE, prefs.getString(State.KEY_NODE, ""))
                putString(State.KEY_ACTIVE_PROFILE, prefs.getString(State.KEY_ACTIVE_PROFILE, ""))
                putString(State.KEY_MODE, prefs.getString(State.KEY_MODE, "default"))
                putString(State.KEY_USAGE_DAY, day)
                val sameDay = prefs.getString(State.KEY_USAGE_DAY, "") == day
                putLong(State.KEY_WIFI_BYTES, if (sameDay) prefs.getLong(State.KEY_WIFI_BYTES, 0) else 0)
                putLong(State.KEY_MOBILE_BYTES, if (sameDay) prefs.getLong(State.KEY_MOBILE_BYTES, 0) else 0)
            }
            render(context, snapshot, ids)
        }

        private fun render(context: Context, snapshot: Bundle, ids: IntArray) {
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            val prefs = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
            val running = snapshot.getBoolean(State.KEY_RUNNING)
            val ready = snapshot.getBoolean(State.KEY_READY)
            val node = snapshot.getString(State.KEY_NODE).orEmpty().ifBlank { prefs.getString(State.KEY_NODE, "").orEmpty() }
            val profile = snapshot.getString(State.KEY_ACTIVE_PROFILE).orEmpty()
            val mode = snapshot.getString(State.KEY_MODE, "default")
            val day = LocalDate.now().toString()
            val sameDay = snapshot.getString(State.KEY_USAGE_DAY) == day
            val wifi = if (sameDay) snapshot.getLong(State.KEY_WIFI_BYTES).coerceAtLeast(0) else 0
            val mobile = if (sameDay) snapshot.getLong(State.KEY_MOBILE_BYTES).coerceAtLeast(0) else 0
            prefs.edit().putString(State.KEY_NODE, node).putString(State.KEY_ACTIVE_PROFILE, profile)
                .putString(State.KEY_MODE, mode).putLong(State.KEY_WIFI_BYTES, wifi)
                .putLong(State.KEY_MOBILE_BYTES, mobile).putString(State.KEY_USAGE_DAY, day).apply()
            val dark = when (UiStore(context).darkMode) {
                DarkMode.ForceDark -> true
                DarkMode.ForceLight -> false
                else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            val foreground = Color.parseColor(if (dark) "#F2F2F7" else "#1C1C1E")
            val secondary = Color.parseColor(if (dark) "#98989D" else "#6E6E73")
            val music = WidgetMusicState.read(context)
            val refreshMode = when (AdaptiveHzPrefs.getCurrentMode(context)) {
                AdaptiveHzMode.FORCE_MIN -> 1
                AdaptiveHzMode.FORCE_MAX -> 2
                AdaptiveHzMode.ADAPTIVE -> 3
                else -> 0
            }
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.roytools_proxy_widget)
                views.setInt(R.id.widget_root, "setBackgroundResource", if (dark)
                    R.drawable.roytools_widget_background_dark else R.drawable.roytools_widget_background_light)
                views.setTextViewText(R.id.widget_node, node.ifBlank {
                    if (running) "正在读取节点…" else "代理未开启"
                })
                views.setTextColor(R.id.widget_node, foreground)
                views.setContentDescription(R.id.widget_node, listOf(node, profile).filter { it.isNotBlank() }.joinToString("·"))
                val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 192)
                if (height in 1 until 192) fitCompact(context, views, height)
                views.setTextViewText(R.id.widget_wifi_usage, bytes(wifi))
                views.setTextViewText(R.id.widget_mobile_usage, bytes(mobile))
                views.setContentDescription(R.id.widget_wifi_usage, "今日Wi-Fi代理用量${bytes(wifi)}")
                views.setContentDescription(R.id.widget_mobile_usage, "今日移动数据代理用量${bytes(mobile)}")
                views.setTextColor(R.id.widget_wifi_usage, foreground)
                views.setTextColor(R.id.widget_mobile_usage, foreground)
                modes.forEachIndexed { index, name ->
                    val selected = name == mode
                    views.setInt(modeViews[index], "setBackgroundResource", if (selected) {
                        if (dark) R.drawable.roytools_widget_mode_selected_dark else R.drawable.roytools_widget_mode_selected_light
                    } else android.R.color.transparent)
                    views.setTextColor(modeViews[index], if (selected) Color.WHITE else secondary)
                    views.setBoolean(modeViews[index], "setEnabled", running && ready)
                    views.setFloat(modeViews[index], "setAlpha", if (running && ready) 1f else 0.5f)
                    val change = Intent(context, ProxyWidgetProvider::class.java).setAction(ACTION_MODE)
                        .setData(Uri.parse("roytools://widget/mode/$name")).putExtra(EXTRA_MODE, name)
                    views.setOnClickPendingIntent(modeViews[index], PendingIntent.getBroadcast(context, index, change, FLAGS))
                }
                refreshViews.forEachIndexed { index, view ->
                    val selected = index == refreshMode
                    views.setInt(view, "setBackgroundResource", if (selected) {
                        if (dark) R.drawable.roytools_widget_mode_selected_dark else R.drawable.roytools_widget_mode_selected_light
                    } else android.R.color.transparent)
                    views.setTextColor(view, if (selected) Color.WHITE else secondary)
                    val change = Intent(context, RoyToolsShortcutActivity::class.java).setAction(refreshActions[index])
                        .putExtra("roytools_notification_action", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    views.setOnClickPendingIntent(view, PendingIntent.getActivity(context, 820 + index, change, FLAGS))
                }
                views.setTextViewText(R.id.widget_music_title, if (music.hasTrack)
                    listOf(music.title, music.artist).filter { it.isNotBlank() }.joinToString("-") else "未播放音乐")
                views.setTextColor(R.id.widget_music_title, foreground)
                views.setContentDescription(R.id.widget_music_title, "${music.title}-${music.artist}")
                views.setInt(R.id.widget_music_cover, "setBackgroundResource", if (dark)
                    R.drawable.roytools_widget_cover_background_dark else R.drawable.roytools_widget_cover_background)
                if (music.cover != null) views.setImageViewBitmap(R.id.widget_music_cover, music.cover)
                else views.setImageViewResource(R.id.widget_music_cover, R.drawable.widget_music_placeholder)
                views.setImageViewResource(R.id.widget_music_toggle, if (music.playing) R.drawable.widget_pause else R.drawable.widget_play)
                views.setContentDescription(R.id.widget_music_toggle, if (music.playing) "暂停" else "播放")
                val mediaViews = intArrayOf(R.id.widget_music_previous, R.id.widget_music_toggle, R.id.widget_music_next)
                listOf("previous", "toggle", "next").forEachIndexed { index, command ->
                    if (index != 1) views.setInt(mediaViews[index], "setBackgroundResource", if (dark)
                        R.drawable.roytools_widget_media_button_dark else R.drawable.roytools_widget_media_button_light)
                    val enabled = when (command) { "previous" -> music.canPrevious; "next" -> music.canNext; else -> true }
                    views.setBoolean(mediaViews[index], "setEnabled", enabled)
                    views.setFloat(mediaViews[index], "setAlpha", if (enabled) 1f else 0.45f)
                    val control = Intent(context, ProxyWidgetProvider::class.java).setAction(ACTION_MUSIC)
                        .setData(Uri.parse("roytools://widget/music/$command")).putExtra("command", command)
                    views.setOnClickPendingIntent(mediaViews[index], PendingIntent.getBroadcast(context, 830 + index, control, FLAGS))
                }
                views.setInt(R.id.widget_toggle, "setImageAlpha", if (running) 255 else 140)
                views.setContentDescription(R.id.widget_toggle, if (running) "关闭代理" else "开启代理")
                val toggle = Intent(context, RoyToolsShortcutActivity::class.java)
                    .setAction(RoyToolsShortcuts.ACTION_TOGGLE_PROXY)
                    .putExtra("roytools_notification_action", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                views.setOnClickPendingIntent(R.id.widget_toggle, PendingIntent.getActivity(context, 800, toggle, FLAGS))
                val open = Intent(context, MainActivity::class.java).putExtra("roytools_tab", "proxy")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 801, open, FLAGS))
                val nodes = Intent(context, ProxyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                views.setOnClickPendingIntent(R.id.widget_node, PendingIntent.getActivity(context, 802, nodes, FLAGS))
                val player = Intent(context, MainActivity::class.java).putExtra("roytools_tab", "music")
                    .putExtra("roytools_music_player", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val openPlayer = PendingIntent.getActivity(context, 803, player, FLAGS)
                views.setOnClickPendingIntent(R.id.widget_music_cover, openPlayer)
                views.setOnClickPendingIntent(R.id.widget_music_title, openPlayer)
                val refresh = Intent(context, MainActivity::class.java).putExtra("roytools_tab", "refresh")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                views.setOnClickPendingIntent(R.id.widget_refresh_row, PendingIntent.getActivity(context, 804, refresh, FLAGS))
                manager.updateAppWidget(id, views)
            }
        }

        /** Four rows must fit a 4×2 host even when its cells are shorter than MIUI's. */
        private fun fitCompact(context: Context, views: RemoteViews, availableHeight: Int) {
            // At full compact size: 138dp content + 16dp outer padding = 154dp.
            val scale = ((availableHeight - 16f) / 138f).coerceIn(0.68f, 1f)
            val density = context.resources.displayMetrics.density
            fun height(id: Int, dp: Float) = views.setViewLayoutHeight(id, dp * scale, TypedValue.COMPLEX_UNIT_DIP)
            fun square(id: Int, dp: Float) {
                height(id, dp)
                views.setViewLayoutWidth(id, dp * scale, TypedValue.COMPLEX_UNIT_DIP)
            }
            fun margin(id: Int, side: Int, dp: Float) =
                views.setViewLayoutMargin(id, side, dp * scale, TypedValue.COMPLEX_UNIT_DIP)
            fun text(id: Int, sp: Float) = views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp * scale)
            val padding = (8 * density).toInt()
            views.setViewPadding(R.id.widget_root, padding, padding, padding, padding)
            height(R.id.widget_header, 30f)
            height(R.id.widget_node, 16f)
            height(R.id.widget_usage_row, 12f)
            margin(R.id.widget_usage_row, RemoteViews.MARGIN_TOP, 2f)
            height(R.id.widget_proxy_row, 30f)
            margin(R.id.widget_proxy_row, RemoteViews.MARGIN_TOP, 6f)
            height(R.id.widget_refresh_row, 26f)
            margin(R.id.widget_refresh_row, RemoteViews.MARGIN_TOP, 4f)
            height(R.id.widget_music_row, 36f)
            margin(R.id.widget_music_row, RemoteViews.MARGIN_TOP, 6f)
            text(R.id.widget_node, 14f)
            text(R.id.widget_music_title, 14f)
            intArrayOf(R.id.widget_wifi_label, R.id.widget_mobile_label,
                R.id.widget_proxy_label, R.id.widget_refresh_label).forEach { text(it, 10f) }
            intArrayOf(R.id.widget_wifi_usage, R.id.widget_mobile_usage).forEach { text(it, 12f) }
            (modeViews + refreshViews).forEach { text(it, 12f) }
            (modeViews + refreshViews).forEach { height(it, 26f) }
            square(R.id.widget_toggle, 30f)
            square(R.id.widget_music_cover, 30f)
            intArrayOf(R.id.widget_music_previous, R.id.widget_music_toggle, R.id.widget_music_next).forEach {
                square(it, 28f)
                val inset = (5 * scale * density).toInt()
                views.setViewPadding(it, inset, inset, inset, inset)
            }
            intArrayOf(R.id.widget_wifi_icon, R.id.widget_mobile_icon).forEach { square(it, 10f) }
        }

        private fun bytes(value: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
            var amount = value.toDouble()
            var index = 0
            while (amount >= 1024 && index < units.lastIndex) { amount /= 1024; index++ }
            return if (index == 0) "$value${units[index]}" else String.format(Locale.ROOT, "%.2f%s", amount, units[index])
        }
    }
}
