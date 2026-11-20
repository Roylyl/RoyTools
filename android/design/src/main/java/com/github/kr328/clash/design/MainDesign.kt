package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.github.kr328.clash.common.ui.TouchFeedback
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.core.util.trafficUploadRate
import com.github.kr328.clash.core.util.trafficDownloadRate
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.padForSystemBars
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    companion object {
        const val TAB_MUSIC = 0
        const val TAB_PROXY = 1
        const val TAB_FORWARD = 4
        const val TAB_REFRESH = 2
        const val TAB_SETTINGS = 3
    }
    enum class Request {
        ToggleStatus, CheckRoutingRules, OpenLanSharing, OpenProxy, OpenProfiles, AddProxyTile, AddProxyWidget
    }

    var onHeaderBack: (() -> Unit)? = null
    var onTabSelected: ((Int) -> Unit)? = null
    private val binding = DesignMainBinding.inflate(context.layoutInflater, context.root, false)
    private val stage = FrameLayout(context)
    private val shell = LinearLayout(context)
    private val pageHost = FrameLayout(context)
    private val pageMotion = PageMotion(pageHost)
    private var visibleTab = -1
    private var musicView: View? = null
    private val subtitle = TextView(context)
    private val titleView = TextView(context)
    private val backButton = ImageButton(context)
    private val headerActionButton = ImageButton(context)
    private val navigationDivider = View(context)
    private val navigation = LinearLayout(context)
    private var musicHeaderMenu: androidx.appcompat.widget.PopupMenu? = null
    private val accent = context.resolveThemedColor(androidx.appcompat.R.attr.colorPrimary)
    private val tabTitles = mapOf(
        TAB_MUSIC to "音乐",
        TAB_PROXY to "代理",
        TAB_FORWARD to "通知转发",
        TAB_REFRESH to "刷新率",
        TAB_SETTINGS to "设置",
    )
    private val tabIcons = mutableListOf<ImageView>()
    private val tabLabels = mutableListOf<TextView>()
    private var proxyRunning = false
    private var hasProxyProfile = false
    private var proxyChanging = false
    private var proxyModeChanging = false
    private var ruleChecking = false
    private var trackingProxyMode = false
    private var proxyMode = "default"
    private data class ProxyModeOption(val key: String, val title: String, val detail: String)
    private val proxyModes = listOf(
        ProxyModeOption("default", "默认", "按当前订阅或配置的原规则分流。"),
        ProxyModeOption("rule", "规则", "使用仓库发布的分流规则和所选节点。"),
        ProxyModeOption("music", "音乐", "仅代理RoyTools中匹配音乐域名的请求。"),
        ProxyModeOption("ai", "AI", "使用仓库发布的AI规则，未匹配流量直连。"),
        ProxyModeOption("global", "全局", "进入隧道的流量统一走所选代理节点。"),
        ProxyModeOption("direct", "直连", "进入隧道的流量直接连接目的地址。"),
    )
    var onProxyModeSelected: ((String) -> Unit)? = null
    private val profileRow: ProxyRow
    private val nodeRow: ProxyRow
    private val lanRow: ProxyRow
    private val sessionTrafficRow: ProxyRow
    private val localRuleRow: ProxyRow
    private val effectiveRuleRow: ProxyRow
    private val ruleCheckRow: ProxyRow
    override val root: View get() = stage
    private fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()

    init {
        binding.proxyEnabled.setOnClickListener { request(Request.ToggleStatus) }
        binding.proxyModeSlider.apply {
            max = proxyModes.lastIndex
            tickCount = proxyModes.size
            alignLabels(binding.proxyModeLabels)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(slider: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        TouchFeedback.step(slider)
                        if (!trackingProxyMode) selectProxyMode(proxyModes[progress].key)
                    }
                }

                override fun onStartTrackingTouch(slider: SeekBar) {
                    trackingProxyMode = true
                    slider.parent.requestDisallowInterceptTouchEvent(true)
                }

                override fun onStopTrackingTouch(slider: SeekBar) {
                    trackingProxyMode = false
                    slider.parent.requestDisallowInterceptTouchEvent(false)
                    selectProxyMode(proxyModes[slider.progress].key)
                    renderProxyMode()
                }
            })
        }
        proxyModes.forEachIndexed { index, mode ->
            val label = binding.proxyModeLabels.getChildAt(index) as TextView
            label.text = mode.title
            label.setOnClickListener {
                if (binding.proxyModeSlider.progress != index) TouchFeedback.step(it)
                selectProxyMode(mode.key)
            }
        }
        binding.proxyModeMenu.setOnClickListener {
            RoyToolsDialogBuilder(context).setTitle("代理模式")
                .setSingleChoiceItems(proxyModes.map { it.title }.toTypedArray(), proxyModes.indexOfFirst { it.key == proxyMode }) { dialog, which ->
                    selectProxyMode(proxyModes[which].key)
                    dialog.dismiss()
                }.setNegativeButton(R.string.cancel, null).show()
        }
        val configuration = context.resources.configuration
        if (configuration.fontScale >= 1.5f || configuration.screenWidthDp in 1..319) {
            binding.proxyModeScale.visibility = View.GONE
            binding.proxyModeMenu.visibility = View.VISIBLE
        }
        if (configuration.fontScale >= 1.5f) {
            binding.proxyTraffic.orientation = LinearLayout.VERTICAL
            for (index in 0 until binding.proxyTraffic.childCount) (binding.proxyTraffic.getChildAt(index).layoutParams as LinearLayout.LayoutParams).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
                if (index > 0) topMargin = dp(8)
            }
        }
        profileRow = addProxyRow(binding.proxyConnections, "配置", R.drawable.ic_outline_article, Request.OpenProfiles)
        nodeRow = addProxyRow(binding.proxyConnections, "代理节点", R.drawable.roytools_nav_proxy, Request.OpenProxy)
        lanRow = addProxyRow(binding.proxyConnections, "局域网共享", R.drawable.roytools_proxy_wifi, Request.OpenLanSharing)
        sessionTrafficRow = addProxyRow(binding.proxySessionTraffic, "会话代理流量", compact = true)
        binding.proxyRules.removeView(binding.proxyRuleMessage)
        localRuleRow = addProxyRow(binding.proxyRules, "本地版本")
        effectiveRuleRow = addProxyRow(binding.proxyRules, "生效版本")
        ruleCheckRow = addProxyRow(binding.proxyRules, "检查规则更新", R.drawable.ic_baseline_sync, Request.CheckRoutingRules)
        binding.proxyRules.addView(binding.proxyRuleMessage)
        profileRow.value.text = "未导入"
        nodeRow.value.text = "连接后可选"
        lanRow.value.text = "—"
        localRuleRow.value.text = "未加载"
        effectiveRuleRow.view.visibility = View.GONE
        renderProxyMode()
        updateProxyControls()
        shell.orientation = LinearLayout.VERTICAL
        val canvas = context.resolveThemedColor(R.attr.glassCanvasStart)
        shell.setBackgroundColor(canvas)
        stage.setBackgroundColor(canvas)
        stage.addView(shell, FrameLayout.LayoutParams(-1, -1))
        stage.padForSystemBars()
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
        headerActionButton.apply {
            imageTintList = ColorStateList.valueOf(accent)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        header.addView(headerActionButton, LinearLayout.LayoutParams(dp(44), dp(48)))
        shell.addView(header)
        shell.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(navigationDivider.apply { setBackgroundColor(context.resolveThemedColor(androidx.appcompat.R.attr.colorControlHighlight)) },
            LinearLayout.LayoutParams(-1, dp(1)))
        navigation.apply {
            setBackgroundColor(context.resolveThemedColor(com.google.android.material.R.attr.colorSurface))
            setPadding(dp(12), dp(3), dp(12), dp(3))
            minimumHeight = dp(64)
        }
        tabTitles.forEach { (index, label) ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                minimumHeight = dp(58)
                setPadding(0, dp(5), 0, dp(3))
                contentDescription = label
                setOnClickListener { onTabSelected?.invoke(index) }
            }
            val icon = ImageView(context).apply {
                setImageResource(when (index) {
                    TAB_MUSIC -> R.drawable.roytools_nav_music
                    TAB_PROXY -> R.drawable.roytools_tab_proxy
                    TAB_FORWARD -> R.drawable.roytools_tab_forward
                    TAB_REFRESH -> R.drawable.roytools_tab_refresh
                    else -> R.drawable.roytools_nav_settings
                })
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val title = TextView(context).apply {
                text = label
                textSize = 11f
                maxLines = 2
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            }
            tabIcons.add(icon)
            tabLabels.add(title)
            item.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
            item.addView(title)
            navigation.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        shell.addView(navigation, LinearLayout.LayoutParams(-1, -2))
        showProxy()
    }

    private fun showPage(view: View, selected: Int) {
        pageMotion.cancel()
        visibleTab = selected
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
        subtitle.text = tabTitles.getValue(selected)
        headerTitle(null)
        val inactiveColor = context.resolveThemedColor(R.attr.glassTextSecondary)
        tabTitles.keys.forEachIndexed { index, tab ->
            val isSelected = tab == selected
            val color = if (isSelected) accent else inactiveColor
            (tabIcons[index].parent as ViewGroup).isSelected = isSelected
            tabIcons[index].imageTintList = ColorStateList.valueOf(color)
            tabLabels[index].setTextColor(color)
        }
    }

    fun showMusic(view: View) {
        if (musicView !== view) musicView?.let(pageHost::removeView)
        musicView = view
        showPage(view, TAB_MUSIC)
    }

    fun updatePageMotion(expectedTab: Int, direction: String?) {
        if (visibleTab != expectedTab) return
        if (direction == null) pageMotion.cancel() else pageMotion.start(direction)
    }
    fun showProxy() = showPage(binding.root, TAB_PROXY)
    fun resetProxyScroll() { (binding.root as? ScrollView)?.scrollTo(0, 0) }
    fun scrollVisiblePageToTop() {
        for (index in pageHost.childCount - 1 downTo 0) {
            val child = pageHost.getChildAt(index)
            if (child.visibility == View.VISIBLE) { (child as? ScrollView)?.scrollTo(0, 0); return }
        }
    }
    fun showForward(view: View) = showPage(view, TAB_FORWARD)
    fun showRefresh(view: View) = showPage(view, TAB_REFRESH)
    fun request(request: Request) {
        val sent = requests.trySend(request)
        com.github.kr328.clash.common.log.Log.d("主页收到点击：$request，入队=${sent.isSuccess}")
    }

    private data class ProxyRow(val view: LinearLayout, val value: TextView)

    private fun addProxyRow(
        parent: LinearLayout, label: String, icon: Int? = null, request: Request? = null, compact: Boolean = false,
    ): ProxyRow {
        val action = request != null
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (parent.childCount > 0 && !action) {
            row.addView(View(context).apply {
                setBackgroundColor(context.resolveThemedColor(androidx.appcompat.R.attr.colorControlHighlight))
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        val primaryText = context.resolveThemedColor(
            if (action) com.google.android.material.R.attr.colorOnPrimary
            else com.google.android.material.R.attr.colorOnSurface
        )
        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            minimumHeight = dp(if (action) 48 else 44)
            val horizontal = if (action) dp(16) else 0
            setPadding(horizontal, dp(8), horizontal, dp(8))
        }
        if (icon != null) line.addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(primaryText)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
        line.addView(TextView(context).apply {
            text = label
            textSize = if (compact) 14f else 16f
            setTextColor(primaryText)
        }, LinearLayout.LayoutParams(-2, -2))
        val value = TextView(context).apply {
            includeFontPadding = false
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            setTextColor(if (action) primaryText else context.resolveThemedColor(R.attr.glassTextSecondary))
        }
        line.addView(value, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        if (request != null && request != Request.CheckRoutingRules) {
            line.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_glass_chevron)
                imageTintList = ColorStateList.valueOf(primaryText)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(4) })
        }
        row.addView(line, LinearLayout.LayoutParams(-1, -2))
        if (request != null) {
            row.setOnClickListener { this@MainDesign.request(request) }
            row.isFocusable = true
            row.setBackgroundResource(R.drawable.roytools_proxy_action)
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (action) topMargin = dp(8) })
        return ProxyRow(row, value)
    }

    private fun selectProxyMode(choice: String) {
        if (choice == proxyMode || proxyModeChanging || proxyChanging || ruleChecking) return
        proxyMode = choice
        renderProxyMode()
        onProxyModeSelected?.invoke(choice)
    }

    private fun renderProxyMode() {
        val index = proxyModes.indexOfFirst { it.key == proxyMode }.coerceAtLeast(0)
        val option = proxyModes[index]
        if (!trackingProxyMode) binding.proxyModeSlider.progress = index
        binding.proxyModeMenu.text = option.title
        binding.proxyModeHint.text = option.detail
    }

    private fun updateProxyControls() {
        val busy = proxyChanging || proxyModeChanging || ruleChecking
        binding.proxyEnabled.isEnabled = !busy && (hasProxyProfile || proxyRunning)
        nodeRow.view.isEnabled = hasProxyProfile
        nodeRow.view.alpha = if (hasProxyProfile) 1f else .55f
        binding.proxyModeSlider.isEnabled = !busy
        binding.proxyModeScale.alpha = if (busy) .65f else 1f
        for (index in proxyModes.indices) binding.proxyModeLabels.getChildAt(index).isEnabled = !busy
        binding.proxyModeMenu.isEnabled = !busy
        ruleCheckRow.view.isEnabled = !busy
        ruleCheckRow.view.alpha = if (busy) .65f else 1f
        ruleCheckRow.value.text = if (ruleChecking) "正在检查…" else null
    }

    suspend fun setProxyOverview(running: Boolean, profile: String?, node: String?, lanStatus: String) =
        withContext(Dispatchers.Main) {
            proxyRunning = running
            hasProxyProfile = profile != null
            profileRow.value.text = profile ?: "未导入"
            nodeRow.value.text = if (running) node ?: "等待连接返回节点" else "连接后可选"
            lanRow.value.text = lanStatus
            binding.proxyProfileHint.visibility = if (hasProxyProfile) View.GONE else View.VISIBLE
            renderProxyStatus()
            updateProxyControls()
            if (!running) {
                binding.proxyUploadSpeed.text = "—"
                binding.proxyDownloadSpeed.text = "—"
                binding.proxySessionTraffic.visibility = View.GONE
            }
        }

    private fun renderProxyStatus() {
        if (!proxyChanging) binding.proxyEnabled.isChecked = proxyRunning
        binding.proxyStatus.text = when {
            proxyChanging && proxyRunning -> "正在断开"
            proxyChanging -> "正在连接"
            proxyRunning -> "已连接"
            else -> "未连接"
        }
    }

    suspend fun setProxyChanging(changing: Boolean) = withContext(Dispatchers.Main) {
        proxyChanging = changing
        renderProxyStatus()
        updateProxyControls()
    }

    fun setProxyModeChanging(changing: Boolean) {
        proxyModeChanging = changing
        updateProxyControls()
    }

    fun setProxyIssue(message: String?) {
        binding.proxyIssue.text = message
        binding.proxyIssue.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    suspend fun setTrafficRates(value: Long) = withContext(Dispatchers.Main) {
        if (proxyRunning) {
            binding.proxyUploadSpeed.text = value.trafficUploadRate()
            binding.proxyDownloadSpeed.text = value.trafficDownloadRate()
        }
    }

    suspend fun setForwarded(value: Long) = withContext(Dispatchers.Main) {
        if (proxyRunning) {
            sessionTrafficRow.value.text = value.trafficTotal()
            binding.proxySessionTraffic.visibility = View.VISIBLE
        }
    }

    suspend fun setProxyMode(choice: String) = withContext(Dispatchers.Main) {
        proxyMode = choice
        renderProxyMode()
    }

    suspend fun setRuleVersions(local: String?, effective: String?) = withContext(Dispatchers.Main) {
        localRuleRow.value.text = local ?: "未加载"
        effectiveRuleRow.value.text = effective
        effectiveRuleRow.view.visibility = if (effective == null) View.GONE else View.VISIBLE
    }

    fun setRuleCheckStatus(checking: Boolean, message: String?) {
        ruleChecking = checking
        binding.proxyRuleMessage.text = message
        binding.proxyRuleMessage.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        updateProxyControls()
    }

    fun headerTitle(secondaryTitle: String?) {
        resetMusicHeader()
        titleView.text = secondaryTitle ?: "RoyTools"
        titleView.maxLines = if (secondaryTitle == null) 1 else 2
        titleView.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        backButton.rotation = 0f
        backButton.contentDescription = "返回"
        backButton.visibility = if (secondaryTitle == null) View.GONE else View.VISIBLE
        subtitle.visibility = if (secondaryTitle == null) View.VISIBLE else View.GONE
    }

    private fun resetMusicHeader() {
        musicHeaderMenu?.dismiss()
        musicHeaderMenu = null
        headerActionButton.visibility = View.GONE
        headerActionButton.setOnClickListener(null)
        navigation.visibility = View.VISIBLE
        navigationDivider.visibility = View.VISIBLE
    }

    fun musicHeader(title: String?, page: String?, canLocate: Boolean, inPlayerFlow: Boolean, onAction: (String, View) -> Unit) {
        val fullPlayer = page == "player"
        val playlist = page == "favorites" || page?.startsWith("playlist:") == true
        val menu = musicHeaderMenu
        // The current track can change while this screen stays open. Keep the same anchor.
        val oldPageIsPlaylist = headerActionButton.visibility == View.VISIBLE && headerActionButton.contentDescription == "快速定位"
        if (menu != null && oldPageIsPlaylist && playlist && titleView.text == title) {
            menu.menu.findItem(2)?.isVisible = canLocate
            return
        }
        headerTitle(title)
        if (title == null) return
        titleView.gravity = Gravity.CENTER
        headerActionButton.visibility = if (playlist || fullPlayer) View.VISIBLE else View.INVISIBLE
        if (inPlayerFlow) {
            navigation.visibility = View.GONE
            navigationDivider.visibility = View.GONE
        }
        if (fullPlayer) {
            backButton.rotation = -90f
            backButton.contentDescription = "退出全屏播放"
            headerActionButton.setImageResource(R.drawable.ic_baseline_more_vert)
            headerActionButton.contentDescription = "更多播放选项"
            headerActionButton.setOnClickListener { onAction("player-options", it) }
        } else if (playlist) {
            headerActionButton.setImageResource(R.drawable.roytools_locate)
            headerActionButton.contentDescription = "快速定位"
            headerActionButton.setOnClickListener { anchor ->
                val popup = androidx.appcompat.widget.PopupMenu(context, anchor, Gravity.END, 0, R.style.RoyTools_AnchoredMenu)
                popup.menu.add(0, 1, 0, "回到顶部")
                popup.menu.add(0, 2, 1, "定位当前曲目").isVisible = canLocate
                popup.setOnMenuItemClickListener {
                    onAction(if (it.itemId == 1) "playlist-top" else "playlist-current", anchor)
                    true
                }
                popup.setOnDismissListener { if (musicHeaderMenu === popup) musicHeaderMenu = null }
                musicHeaderMenu = popup
                popup.show()
            }
        }
    }

    private fun section(layout: Int, title: String?): View {
        val view = context.layoutInflater.inflate(layout, pageHost, false)
        showPage(view, TAB_SETTINGS)
        headerTitle(title)
        return view
    }

    fun showSettings(mode: DarkMode, onTheme: () -> Unit, onAbout: () -> Unit,
                     onBackground: () -> Unit, onCheckUpdates: () -> Unit) {
        val view = section(R.layout.roytools_settings_home, null)
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
        val view = section(R.layout.roytools_background, "后台运行设置")
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
        val group = section(R.layout.roytools_theme, "主题")
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
        val view = section(R.layout.roytools_about, "关于RoyTools")
        view.findViewById<View>(R.id.roytools_open_licenses).setOnClickListener {
            launch(Dispatchers.Main) {
                it.isEnabled = false
                val notices = try {
                    withContext(Dispatchers.IO) {
                        listOf("roytools_proxy_license.txt", "licenses.txt").joinToString("\n\n") { file ->
                            context.assets.open(file).bufferedReader().use { it.readText() }
                        }
                    }
                } catch (_: java.io.IOException) {
                    "无法读取开源许可。"
                } finally {
                    it.isEnabled = true
                }
                if (view.isShown && view.isAttachedToWindow) {
                    val dialog = com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder(context)
                        .setTitle("开源许可").setMessage(notices).setPositiveButton(R.string.close, null).show()
                    dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
                }
            }
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

