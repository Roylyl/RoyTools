package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.view.animation.AnimationUtils
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.github.kr328.clash.common.ui.TouchFeedback
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.core.util.trafficUploadRate
import com.github.kr328.clash.core.util.trafficDownloadRate
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    companion object {
        const val TAB_MUSIC = 0
        const val TAB_PROXY = 1
        const val TAB_REFRESH = 2
        const val TAB_SETTINGS = 3
    }
    enum class Request {
        ToggleStatus, OpenLanSharing, OpenProxy, OpenProfiles, OpenAppSettings,
        OpenNetworkSettings, OpenOverrideSettings, OpenProviders,
        ModeDefault, ModeRule, ModeGlobal, ModeDirect, AddProxyTile, AddProxyWidget
    }

    var onHeaderBack: (() -> Unit)? = null
    var onTabSelected: ((Int) -> Unit)? = null
    private val binding = DesignMainBinding.inflate(context.layoutInflater, context.root, false)
    private val shell = LinearLayout(context)
    private val pageHost = FrameLayout(context)
    private var musicView: View? = null
    private val subtitle = TextView(context)
    private val titleView = TextView(context)
    private val backButton = ImageButton(context)
    private val accent = context.resolveThemedColor(androidx.appcompat.R.attr.colorPrimary)
    private val tabIcons = mutableListOf<ImageView>()
    private val tabLabels = mutableListOf<TextView>()
    private var trackingProxyMode = false
    private val proxyModes = listOf(Request.ModeDefault, Request.ModeRule, Request.ModeGlobal, Request.ModeDirect)
    override val root: View get() = shell
    private fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()

    init {
        binding.self = this
        binding.uploadSpeed = "0.00KB/s"
        binding.downloadSpeed = "0.00KB/s"
        binding.mode = context.getString(R.string.rule_mode)
        binding.proxyModeSlider.alignLabels(binding.proxyModeLabels)
        binding.proxyModeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    TouchFeedback.step(seekBar)
                    if (!trackingProxyMode) request(proxyModes[progress])
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                trackingProxyMode = true
                seekBar.parent.requestDisallowInterceptTouchEvent(true)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                trackingProxyMode = false
                seekBar.parent.requestDisallowInterceptTouchEvent(false)
                request(proxyModes[seekBar.progress])
            }
        })
        for (index in 0..3) binding.proxyModeLabels.getChildAt(index).setOnClickListener {
            if (binding.proxyModeSlider.progress != index) TouchFeedback.step(binding.proxyModeSlider)
            binding.proxyModeSlider.progress = index
            request(proxyModes[index])
        }
        shell.orientation = LinearLayout.VERTICAL
        shell.setBackgroundColor(context.resolveThemedColor(R.attr.glassCanvasStart))
        shell.fitsSystemWindows = false
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        backButton.apply {
            setImageResource(R.drawable.roytools_back)
            imageTintList = ColorStateList.valueOf(accent)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = "返回"
            setOnClickListener { onHeaderBack?.invoke() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(44), dp(48)))
        titleView.textSize = 25f
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        titleView.setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface))
        titleView.setTypeface(null, Typeface.BOLD)
        subtitle.textSize = 12f
        subtitle.setTextColor(accent)
        header.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(subtitle)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        shell.addView(header)
        shell.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(View(context).apply { setBackgroundColor(context.resolveThemedColor(androidx.appcompat.R.attr.colorControlHighlight)) },
            LinearLayout.LayoutParams(-1, dp(1)))
        val nav = LinearLayout(context).apply {
            setBackgroundColor(context.resolveThemedColor(com.google.android.material.R.attr.colorSurface))
            setPadding(dp(12), dp(3), dp(12), dp(3))
        }
        listOf("音乐", "代理", "刷新率", "设置").forEachIndexed { index, label ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = label
                setOnClickListener { onTabSelected?.invoke(index) }
            }
            val icon = ImageView(context).apply {
                setImageResource(when (index) {
                    TAB_MUSIC -> R.drawable.roytools_nav_music
                    TAB_PROXY -> R.drawable.roytools_nav_proxy
                    TAB_REFRESH -> R.drawable.roytools_nav_refresh
                    else -> R.drawable.roytools_nav_settings
                })
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val title = TextView(context).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            }
            tabIcons.add(icon)
            tabLabels.add(title)
            item.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
            item.addView(title)
            nav.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
        shell.addView(nav, LinearLayout.LayoutParams(-1, dp(64)))
        showProxy()
    }

    private fun showPage(view: View, selected: Int) {
        pageHost.clearAnimation()
        // Keep Chromium attached to its Window across module changes. Detaching
        // the retained music page discards its compositor surface and flashes
        // a blank frame on every return, even though the document stays loaded.
        for (index in pageHost.childCount - 1 downTo 0) {
            val child = pageHost.getChildAt(index)
            if (child === view) continue
            child.clearAnimation()
            if (child === musicView) child.visibility = View.INVISIBLE
            else pageHost.removeViewAt(index)
        }
        if (view.parent !== pageHost) {
            (view.parent as? ViewGroup)?.removeView(view)
            pageHost.addView(view, FrameLayout.LayoutParams(-1, -1))
        }
        view.visibility = View.VISIBLE
        view.bringToFront()
        subtitle.text = listOf("音乐", "代理", "刷新率", "设置")[selected]
        headerTitle(null)
        tabLabels.indices.forEach { index ->
            val color = if (index == selected) accent else context.resolveThemedColor(R.attr.glassTextSecondary)
            (tabIcons[index].parent as ViewGroup).isSelected = index == selected
            tabIcons[index].imageTintList = ColorStateList.valueOf(color)
            tabLabels[index].setTextColor(color)
        }
    }

    fun showMusic(view: View) {
        if (musicView !== view) musicView?.let(pageHost::removeView)
        musicView = view
        showPage(view, TAB_MUSIC)
    }
    fun showProxy() = showPage(binding.root, TAB_PROXY)
    fun resetProxyScroll() { (binding.root as? ScrollView)?.scrollTo(0, 0) }
    fun animateReturnToHome() {
        pageHost.clearAnimation()
        pageHost.startAnimation(AnimationUtils.loadAnimation(context, R.anim.roytools_pop_in))
    }
    fun animateOpenPage() {
        pageHost.clearAnimation()
        pageHost.startAnimation(AnimationUtils.loadAnimation(context, R.anim.roytools_push_in))
    }
    fun showRefresh(view: View) = showPage(view, TAB_REFRESH)
    fun request(request: Request) {
        val sent = requests.trySend(request)
        com.github.kr328.clash.common.log.Log.d("主页收到点击：$request，入队=${sent.isSuccess}")
    }

    suspend fun setProfileName(name: String?) = withContext(Dispatchers.Main) { binding.profileName = name }
    suspend fun setClashRunning(running: Boolean) = withContext(Dispatchers.Main) {
        binding.clashRunning = running
        binding.proxyModeSlider.isEnabled = running
        binding.proxyModeSlider.alpha = if (running) 1f else 0.45f
        for (index in 0..3) binding.proxyModeLabels.getChildAt(index).isEnabled = running
        if (!running) {
            binding.uploadSpeed = "0.00KB/s"
            binding.downloadSpeed = "0.00KB/s"
        }
    }
    suspend fun setTrafficRates(value: Long) = withContext(Dispatchers.Main) {
        binding.uploadSpeed = if (binding.clashRunning) value.trafficUploadRate() else "0.00KB/s"
        binding.downloadSpeed = if (binding.clashRunning) value.trafficDownloadRate() else "0.00KB/s"
    }
    suspend fun setForwarded(value: Long) = withContext(Dispatchers.Main) { binding.forwarded = value.trafficTotal() }
    suspend fun setProxyMode(override: TunnelState.Mode?) = withContext(Dispatchers.Main) {
        if (!trackingProxyMode) binding.proxyModeSlider.progress = when (override) {
            TunnelState.Mode.Rule -> 1
            TunnelState.Mode.Global -> 2
            TunnelState.Mode.Direct -> 3
            else -> 0
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) = withContext(Dispatchers.Main) {
        binding.mode = context.getString(when (mode) {
            TunnelState.Mode.Direct -> R.string.direct_mode
            TunnelState.Mode.Global -> R.string.global_mode
            else -> R.string.rule_mode
        })
    }

    fun headerTitle(secondaryTitle: String?) {
        titleView.text = secondaryTitle ?: "RoyTools"
        backButton.visibility = if (secondaryTitle == null) View.GONE else View.VISIBLE
        subtitle.visibility = if (secondaryTitle == null) View.VISIBLE else View.GONE
    }

    private fun section(layout: Int, title: String?, transition: Int = 0): View {
        val view = context.layoutInflater.inflate(layout, pageHost, false)
        showPage(view, TAB_SETTINGS)
        headerTitle(title)
        if (transition != 0) view.startAnimation(AnimationUtils.loadAnimation(context, transition))
        return view
    }

    fun showSettings(mode: DarkMode, returning: Boolean, onTheme: () -> Unit, onAbout: () -> Unit,
                     onBackground: () -> Unit, onCheckUpdates: () -> Unit) {
        val view = section(R.layout.roytools_settings_home, null, if (returning) R.anim.roytools_pop_in else 0)
        val themeLabel = when (mode) {
            DarkMode.Auto -> "跟随系统"
            DarkMode.ForceLight -> "浅色模式"
            DarkMode.ForceDark -> "深色模式"
        }
        view.findViewById<TextView>(R.id.roytools_choose_theme).apply {
            text = "主题 · $themeLabel"
            setOnClickListener { onTheme() }
        }
        view.findViewById<View>(R.id.roytools_open_about).setOnClickListener { onAbout() }
        view.findViewById<View>(R.id.roytools_open_background).setOnClickListener { onBackground() }
        view.findViewById<View>(R.id.roytools_check_updates).setOnClickListener { onCheckUpdates() }
        view.findViewById<View>(R.id.roytools_add_proxy_tile).setOnClickListener { request(Request.AddProxyTile) }
        view.findViewById<View>(R.id.roytools_add_proxy_widget).setOnClickListener { request(Request.AddProxyWidget) }
    }

    fun showBackground(autoRestart: Boolean, batteryExempt: Boolean,
                       onAutoRestart: (Boolean) -> Unit, onBattery: () -> Unit,
                       onAppBackground: () -> Unit, onVpn: () -> Unit,
                       keepAlive: Boolean, hideIcon: Boolean, hideRecents: Boolean,
                       onKeepAlive: (Boolean) -> Unit, onHideIcon: (Boolean) -> Unit, onHideRecents: (Boolean) -> Unit) {
        val view = section(R.layout.roytools_background, "后台运行设置", R.anim.roytools_push_in)
        fun bindSwitch(id: Int, initial: Boolean, changed: (Boolean) -> Unit) {
            view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(id).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, value -> changed(value) }
                (parent as View).setOnClickListener { performClick() }
            }
        }
        bindSwitch(R.id.roytools_keep_alive, keepAlive, onKeepAlive)
        bindSwitch(R.id.roytools_hide_icon, hideIcon, onHideIcon)
        bindSwitch(R.id.roytools_hide_recents, hideRecents, onHideRecents)
        view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.roytools_auto_restart).apply {
            isChecked = autoRestart
            setOnCheckedChangeListener { _, checked -> onAutoRestart(checked) }
            (parent as View).setOnClickListener { performClick() }
        }
        view.findViewById<TextView>(R.id.roytools_battery).apply {
            text = if (batteryExempt) "电池限制已解除" else "解除电池限制"
            setOnClickListener { onBattery() }
        }
        view.findViewById<View>(R.id.roytools_app_background).setOnClickListener { onAppBackground() }
        view.findViewById<View>(R.id.roytools_always_vpn).setOnClickListener { onVpn() }
    }

    fun refreshBackgroundStatus(batteryExempt: Boolean) {
        pageHost.findViewById<TextView>(R.id.roytools_battery)?.text =
            if (batteryExempt) "电池限制已解除" else "解除电池限制"
    }

    fun showTheme(mode: DarkMode, onSelect: (DarkMode) -> Unit) {
        val group = section(R.layout.roytools_theme, "主题", R.anim.roytools_push_in)
            .findViewById<RadioGroup>(R.id.roytools_theme_options)
        val choices = mapOf(
            R.id.roytools_theme_system to DarkMode.Auto,
            R.id.roytools_theme_light to DarkMode.ForceLight,
            R.id.roytools_theme_dark to DarkMode.ForceDark
        )
        group.check(choices.entries.first { it.value == mode }.key)
        group.setOnCheckedChangeListener { _, id -> choices[id]?.let(onSelect) }
    }

    fun showAbout(version: String, onViewLogs: (String) -> Unit, onExportLogs: (String) -> Unit,
                  onClearLogs: () -> Unit): TextView {
        val view = section(R.layout.roytools_about, "关于RoyTools", R.anim.roytools_push_in)
        view.findViewById<View>(R.id.roytools_open_licenses).setOnClickListener {
            val notices = listOf("roytools_proxy_license.txt", "licenses.txt").joinToString("\n\n") { file ->
                context.assets.open(file).bufferedReader().use { it.readText() }
            }
            com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder(context).setTitle("开源许可").setMessage(notices)
                .setPositiveButton(R.string.close, null).show()
        }
        listOf("Music", "Proxy", "Refresh", "App").forEachIndexed { index, module ->
            view.findViewById<View>(listOf(R.id.roytools_music_logs, R.id.roytools_proxy_logs,
                R.id.roytools_refresh_logs, R.id.roytools_app_logs)[index]).setOnClickListener { onViewLogs(module) }
            view.findViewById<View>(listOf(R.id.roytools_music_logs_export, R.id.roytools_proxy_logs_export,
                R.id.roytools_refresh_logs_export, R.id.roytools_app_logs_export)[index]).setOnClickListener { onExportLogs(module) }
        }
        view.findViewById<View>(R.id.roytools_clear_logs).setOnClickListener { onClearLogs() }
        return view.findViewById<TextView>(R.id.roytools_version).apply { text = "版本$version" }
    }
}

