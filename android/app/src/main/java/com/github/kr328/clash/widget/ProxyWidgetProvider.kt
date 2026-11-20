package com.github.kr328.clash.widget

import android.app.PendingIntent
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.os.BundleCompat
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.ProxyActivity
import com.github.kr328.clash.R
import com.github.kr328.clash.RoyToolsShortcutActivity
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.util.ProxyWidgetState as State
import com.github.kr328.clash.util.RoyToolsShortcuts
import java.util.Locale
import java.util.concurrent.Executors
import java.time.LocalDate
import java.time.ZoneId

/** One 4×2 proxy widget. Sampling belongs to the existing proxy runtime, not the launcher. */
class ProxyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
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
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                val ids = widgetIds(context)
                if (ids.isEmpty()) return
                scheduleNextDay(context)
                refreshAsync(context, ids)
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
                if (mode !in modeViews) return
                com.github.kr328.clash.common.ui.TouchFeedback.click(View(context))
                val pending = goAsync()
                worker.execute {
                    try {
                        val result = context.contentResolver.call(statusUri, StatusProvider.METHOD_WIDGET_MODE, mode, null)
                        if (result?.getBoolean(State.KEY_SUCCESS) == true) render(context, result, widgetIds(context))
                        else {
                            val text = when (result?.getString(State.KEY_ERROR)) {
                                "stopped" -> "请先开启代理"
                                "not_ready" -> "正在加载代理配置，请稍后再试"
                                else -> "模式切换失败，请打开RoyTools检查"
                            }
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
            WidgetMusicState.ACTION_CHANGED,
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
        private val modeViews = mapOf(
            "default" to R.id.widget_mode_default, "rule" to R.id.widget_mode_rule,
            "music" to R.id.widget_mode_music, "ai" to R.id.widget_mode_ai,
            "global" to R.id.widget_mode_global, "direct" to R.id.widget_mode_direct,
        )
        private val worker = Executors.newSingleThreadExecutor()
        private val statusUri = Uri.Builder().scheme("content").authority(Authorities.STATUS_PROVIDER).build()
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun dayIntent(context: Context) = PendingIntent.getBroadcast(context, 840,
            Intent(context, ProxyWidgetProvider::class.java).setAction(ACTION_NEW_DAY), FLAGS)

        private fun scheduleNextDay(context: Context) {
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
            val node = snapshot.getString(State.KEY_NODE).orEmpty()
            val nodeTitle = when (node) {
                "DIRECT" -> "直连"
                "REJECT" -> "拒绝"
                "REJECT-DROP" -> "丢弃"
                "PASS" -> "跳过"
                "COMPATIBLE" -> "兼容"
                else -> node
            }
            val profile = snapshot.getString(State.KEY_ACTIVE_PROFILE).orEmpty()
            val mode = snapshot.getString(State.KEY_MODE, "default")
            val day = LocalDate.now().toString()
            val sameDay = snapshot.getString(State.KEY_USAGE_DAY) == day
            val wifi = if (sameDay) snapshot.getLong(State.KEY_WIFI_BYTES).coerceAtLeast(0) else 0
            val mobile = if (sameDay) snapshot.getLong(State.KEY_MOBILE_BYTES).coerceAtLeast(0) else 0
            prefs.edit().putString(State.KEY_ACTIVE_PROFILE, profile)
                .putString(State.KEY_MODE, mode).putLong(State.KEY_WIFI_BYTES, wifi)
                .putLong(State.KEY_MOBILE_BYTES, mobile).putString(State.KEY_USAGE_DAY, day).apply()
            val dark = when (UiStore(context).darkMode) {
                DarkMode.ForceDark -> true
                DarkMode.ForceLight -> false
                else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            val appearance = ContextThemeWrapper(context, if (dark) DesignR.style.AppThemeDark else DesignR.style.AppThemeLight)
            val foreground = appearance.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
            val secondary = appearance.resolveThemedColor(DesignR.attr.glassTextSecondary)
            val music = WidgetMusicState.read(context)
            val secondaryTint = ColorStateList.valueOf(secondary)
            val foregroundTint = ColorStateList.valueOf(foreground)
            val modeTrackColor = appearance.resolveThemedColor(if (dark) DesignR.attr.glassSurfaceHighlight else DesignR.attr.glassCanvasStart)
            val readyToSwitch = running && ready
            val selectedModeTint = ColorStateList.valueOf(if (readyToSwitch) context.getColor(R.color.roytools_accent)
                else appearance.resolveThemedColor(DesignR.attr.glassSurface))
            val neutralControl = if (dark) R.drawable.roytools_widget_media_button_dark else R.drawable.roytools_widget_media_button_light
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.roytools_proxy_widget)
                views.setInt(R.id.widget_root, "setBackgroundResource", if (dark)
                    R.drawable.roytools_widget_background_dark else R.drawable.roytools_widget_background_light)
                val statusText = when {
                    !running -> "未连接"
                    !ready -> "正在连接…"
                    nodeTitle.isNotBlank() -> nodeTitle
                    else -> "已连接"
                }
                views.setTextViewText(R.id.widget_node, statusText)
                views.setTextColor(R.id.widget_node, foreground)
                val statusColor = when {
                    readyToSwitch -> appearance.resolveThemedColor(DesignR.attr.glassSuccess)
                    running -> context.getColor(R.color.roytools_accent)
                    else -> secondary
                }
                views.setColorStateList(R.id.widget_status_dot, "setImageTintList", ColorStateList.valueOf(statusColor))
                views.setContentDescription(R.id.widget_node, listOf(statusText, profile).filter { it.isNotBlank() }.joinToString("·"))
                views.setTextViewText(R.id.widget_wifi_usage, bytes(wifi))
                views.setTextViewText(R.id.widget_mobile_usage, bytes(mobile))
                views.setContentDescription(R.id.widget_wifi_usage, "今日Wi-Fi代理用量${bytes(wifi)}")
                views.setContentDescription(R.id.widget_mobile_usage, "今日移动数据代理用量${bytes(mobile)}")
                views.setTextColor(R.id.widget_wifi_usage, foreground)
                views.setTextColor(R.id.widget_mobile_usage, foreground)
                views.setTextColor(R.id.widget_wifi_label, secondary)
                views.setTextColor(R.id.widget_mobile_label, secondary)
                views.setColorStateList(R.id.widget_wifi_icon, "setImageTintList", secondaryTint)
                views.setColorStateList(R.id.widget_mobile_icon, "setImageTintList", secondaryTint)
                views.setColorStateList(R.id.widget_mode_track, "setBackgroundTintList", ColorStateList.valueOf(modeTrackColor))
                modeViews.entries.forEachIndexed { index, (name, button) ->
                    val selected = name == mode
                    views.setInt(button, "setBackgroundResource", if (selected) {
                        if (dark) R.drawable.roytools_widget_mode_selected_dark else R.drawable.roytools_widget_mode_selected_light
                    } else android.R.color.transparent)
                    views.setColorStateList(button, "setBackgroundTintList", if (selected) selectedModeTint else null)
                    views.setTextColor(button, if (selected) { if (readyToSwitch) Color.WHITE else foreground } else secondary)
                    views.setBoolean(button, "setEnabled", readyToSwitch)
                    views.setFloat(button, "setAlpha", if (readyToSwitch || selected) 1f else 0.8f)
                    val change = Intent(context, ProxyWidgetProvider::class.java).setAction(ACTION_MODE)
                        .setData(Uri.parse("roytools://widget/mode/$name")).putExtra(EXTRA_MODE, name)
                    views.setOnClickPendingIntent(button, PendingIntent.getBroadcast(context, index, change, FLAGS))
                }
                views.setInt(R.id.widget_music_divider, "setBackgroundColor", (secondary and 0x00ffffff) or 0x30000000)
                views.setTextViewText(R.id.widget_music_title, music.title.ifBlank { "未播放音乐" })
                views.setTextColor(R.id.widget_music_title, foreground)
                views.setTextViewText(R.id.widget_music_artist, music.artist)
                views.setTextColor(R.id.widget_music_artist, secondary)
                views.setViewVisibility(R.id.widget_music_artist, if (music.artist.isBlank()) View.GONE else View.VISIBLE)
                views.setInt(R.id.widget_music_cover, "setBackgroundResource", if (dark)
                    R.drawable.roytools_widget_cover_background_dark else R.drawable.roytools_widget_cover_background)
                if (music.cover != null) views.setImageViewBitmap(R.id.widget_music_cover, music.cover)
                else views.setImageViewResource(R.id.widget_music_cover, R.drawable.widget_music_placeholder)
                views.setImageViewResource(R.id.widget_music_toggle, if (music.playing) R.drawable.widget_pause else R.drawable.widget_play)
                views.setContentDescription(R.id.widget_music_toggle, if (music.playing) "暂停" else "播放")
                val mediaViews = intArrayOf(R.id.widget_music_previous, R.id.widget_music_toggle, R.id.widget_music_next)
                listOf("previous", "toggle", "next").forEachIndexed { index, command ->
                    if (index != 1) views.setColorStateList(mediaViews[index], "setImageTintList", foregroundTint)
                    val enabled = when (command) { "previous" -> music.canPrevious; "next" -> music.canNext; else -> true }
                    views.setBoolean(mediaViews[index], "setEnabled", enabled)
                    views.setFloat(mediaViews[index], "setAlpha", if (enabled) 1f else 0.45f)
                    val control = Intent(context, ProxyWidgetProvider::class.java).setAction(ACTION_MUSIC)
                        .setData(Uri.parse("roytools://widget/music/$command")).putExtra("command", command)
                    views.setOnClickPendingIntent(mediaViews[index], PendingIntent.getBroadcast(context, 830 + index, control, FLAGS))
                }
                views.setInt(R.id.widget_toggle, "setBackgroundResource",
                    if (running) R.drawable.roytools_widget_media_button_primary else neutralControl)
                views.setColorStateList(R.id.widget_toggle, "setImageTintList",
                    ColorStateList.valueOf(if (running) Color.WHITE else foreground))
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
                views.setOnClickPendingIntent(R.id.widget_music_info, openPlayer)
                manager.updateAppWidget(id, sizedViews(context, views, manager.getAppWidgetOptions(id)))
            }
        }

        private fun sizedViews(context: Context, views: RemoteViews, options: Bundle): RemoteViews {
            val sizes = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                .orEmpty().distinct()
            if (sizes.isNotEmpty() && sizes.all { it.height >= 144f }) return views
            if (sizes.size in 2..16) {
                return RemoteViews(sizes.associateWith { size ->
                    RemoteViews(views).apply { fitCompact(context, this, size.height) }
                })
            }
            val height = sizes.singleOrNull()?.height
                ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat()
            return views.apply { fitCompact(context, this, height) }
        }

        /** Keep the three rows legible on launchers with short grid cells. */
        private fun fitCompact(context: Context, views: RemoteViews, height: Float) {
            if (height <= 0f || height >= 144f) return
            val scale = ((height - 17f) / 120f).coerceIn(.55f, 1f)
            val density = context.resources.displayMetrics.density
            fun height(id: Int, dp: Float) = views.setViewLayoutHeight(id, dp * scale, TypedValue.COMPLEX_UNIT_DIP)
            fun square(id: Int, dp: Float) {
                height(id, dp)
                views.setViewLayoutWidth(id, dp * scale, TypedValue.COMPLEX_UNIT_DIP)
            }
            fun text(id: Int, sp: Float) = views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp * scale)
            val padding = (8 * density).toInt()
            views.setViewPadding(R.id.widget_root, padding, padding, padding, padding)
            height(R.id.widget_status, 20f)
            height(R.id.widget_usage_row, 18f)
            text(R.id.widget_node, 14f)
            text(R.id.widget_music_title, 14f)
            text(R.id.widget_music_artist, 12f)
            intArrayOf(R.id.widget_wifi_label, R.id.widget_mobile_label,
                R.id.widget_wifi_usage, R.id.widget_mobile_usage).forEach { text(it, 12f) }
            modeViews.values.forEach { view ->
                text(view, 12f)
                height(view, 32f)
            }
            height(R.id.widget_mode_track, 38f)
            square(R.id.widget_status_dot, 6f)
            square(R.id.widget_music_cover, 40f)
            intArrayOf(R.id.widget_toggle, R.id.widget_music_previous, R.id.widget_music_toggle, R.id.widget_music_next).forEach {
                square(it, 36f)
                val inset = (7 * scale * density).toInt()
                views.setViewPadding(it, inset, inset, inset, inset)
            }
            intArrayOf(R.id.widget_wifi_icon, R.id.widget_mobile_icon).forEach { square(it, 12f) }
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
