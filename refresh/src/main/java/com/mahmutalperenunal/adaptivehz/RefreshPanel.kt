package com.mahmutalperenunal.adaptivehz

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.Context
import com.google.android.material.materialswitch.MaterialSwitch
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import com.google.android.material.button.MaterialButton
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import com.mahmutalperenunal.adaptivehz.core.engine.model.AdaptiveHzMode
import com.mahmutalperenunal.adaptivehz.core.engine.model.isOperationalSuccess
import com.mahmutalperenunal.adaptivehz.core.prefs.AdaptiveHzPrefs
import com.mahmutalperenunal.adaptivehz.core.service.AdaptiveHzActionHandler
import rikka.shizuku.Shizuku
import com.github.kr328.clash.common.ui.TouchFeedback
import com.github.kr328.clash.common.R as CommonR
import kotlin.math.roundToInt

class RefreshPanel(private val activity: Activity, private val switchContext: Context = activity) : android.content.ContextWrapper(activity) {
    val view = FrameLayout(this)
    private val blue get() = themeColor(androidx.appcompat.R.attr.colorPrimary, Color.rgb(0, 122, 255))
    private val primaryTextColor get() = themeColor(android.R.attr.textColorPrimary, Color.BLACK)
    private val surfaceColor get() = themeColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
    private val bgColor get() = if (Color.luminance(primaryTextColor) > 0.5f) Color.rgb(18, 18, 20) else Color.rgb(242, 242, 247)
    private fun themeColor(attribute: Int, fallback: Int): Int {
        val colors = theme.obtainStyledAttributes(intArrayOf(attribute))
        return try { colors.getColor(0, fallback) } finally { colors.recycle() }
    }
    fun scrollToTop() {
        val layout = view.getChildAt(0) as? android.view.ViewGroup
        (layout?.getChildAt(0) as? ScrollView)?.scrollTo(0, 0)
    }
    private val muted get() = themeColor(android.R.attr.textColorSecondary, Color.rgb(116, 116, 124))
    private val handler = Handler(Looper.getMainLooper())
    private var attemptedReconnect = false
    private var stoppedWhileDisabled = false
    private var updatingSwitch = false
    private var masterSwitch: MaterialSwitch? = null
    private val controlledCards = mutableListOf<View>()
    private var trackingMode = false
    private var modeSlider: SeekBar? = null
    private var modeHint: TextView? = null
    private lateinit var content: LinearLayout
    private lateinit var state: TextView
    private lateinit var rate: TextView
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    fun onStart() {
        val enabled = AdaptiveHzPrefs.isControlsEnabled(this)
        if (!enabled) {
            WirelessSetupService.cancelForDisabledMode(this)
            if (!stoppedWhileDisabled) {
                stoppedWhileDisabled = true
                AdaptiveHzActionHandler.turnOffAsync(this)
            }
        } else OneSetup.configure(this)
        if (enabled && AdaptiveHzActionHandler.getCurrentMode(this) != AdaptiveHzMode.OFF && !attemptedReconnect && !Shizuku.pingBinder() &&
            getSharedPreferences("wireless_setup", MODE_PRIVATE).getBoolean("paired", false)) {
            attemptedReconnect = true
            startForegroundService(Intent(this, WirelessSetupService::class.java)
                .setAction(WirelessSetupService.ACTION_CONNECT))
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun onStop() {
        handler.removeCallbacks(ticker)

    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun dimension(resource: Int) = resources.getDimensionPixelSize(resource)
    private fun rounded(color: Int, radius: Int = dimension(CommonR.dimen.roytools_card_radius)) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }
    private fun text(value: String, size: Int = 15, color: Int = primaryTextColor) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
    private fun card(controlled: Boolean = true): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(surfaceColor)
        val padding = dimension(CommonR.dimen.roytools_card_padding)
        setPadding(padding, padding, padding, padding)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dimension(CommonR.dimen.roytools_section_spacing) }
        content.addView(this)
        if (controlled) controlledCards.add(this)
    }
    private fun button(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(MaterialButton(this).apply {
            text = label
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun render() {
        controlledCards.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)

        }
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margin = dimension(CommonR.dimen.roytools_page_margin)
            setPadding(margin, dp(8), margin, dp(8))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        controls()
        configuration()
        view.removeAllViews()
        view.addView(root, FrameLayout.LayoutParams(-1, -1))
        refreshStatus()
    }

    private fun controls() {
        val statusCard = card(false)
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48) }
        heading.addView(text("刷新率控制", 18).apply { setTypeface(null, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
        masterSwitch = MaterialSwitch(switchContext).apply {
            contentDescription = "刷新率总开关"
            minimumHeight = dp(48)
            isChecked = AdaptiveHzPrefs.isControlsEnabled(this@RefreshPanel)
            setOnCheckedChangeListener { _, checked ->
                if (!updatingSwitch) {
                    AdaptiveHzPrefs.setControlsEnabled(this@RefreshPanel, checked)
                    if (checked) {
                        stoppedWhileDisabled = false
                        applyMode(AdaptiveHzPrefs.getControlMode(this@RefreshPanel))
                    } else {
                        stoppedWhileDisabled = true
                        attemptedReconnect = false
                        AdaptiveHzActionHandler.turnOffAsync(this@RefreshPanel, onSettled = { refreshStatus() })
                    }
                    refreshStatus()
                }
            }
        }.also { heading.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        statusCard.addView(heading)
        state = text("", 14, blue)
        rate = text("", 48).apply { setTypeface(null, Typeface.BOLD) }
        statusCard.addView(state)
        statusCard.addView(rate)
        val modes = card()
        modes.addView(text("刷新率模式", 18).apply { setTypeface(null, Typeface.BOLD) })
        val slider = ModeSlider().apply {
            progress = modeIndex()
            contentDescription = "刷新率模式，自适应、60Hz、120Hz"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, position: Int, fromUser: Boolean) {
                    if (fromUser) {
                        TouchFeedback.step(seekBar)
                        // Keyboard and accessibility changes do not send a touch-stop callback.
                        if (!trackingMode) applyModeAt(position)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    trackingMode = true
                    seekBar.parent.requestDisallowInterceptTouchEvent(true)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    trackingMode = false
                    seekBar.parent.requestDisallowInterceptTouchEvent(false)
                    applyModeAt(seekBar.progress)
                }
            })
        }
        modeSlider = slider
        modes.addView(slider, LinearLayout.LayoutParams(-1, dp(56)))
        val labels = LinearLayout(this)
        listOf("自适应", "60Hz", "120Hz").forEachIndexed { index, label ->
            labels.addView(text(label, 13, muted).apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setOnClickListener {
                    if (slider.progress != index) TouchFeedback.step(slider)
                    slider.progress = index
                    applyModeAt(index)
                }
            },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        modes.addView(labels)
        slider.alignLabels(labels)
        modeHint = text("请点击下方“配置辅助服务”完成激活", 13, muted).apply {
            setPadding(0, dp(14), 0, 0)
            visibility = View.GONE
        }.also {
            modes.addView(it)
        }
    }

    private fun showSetupHint() {
        modeHint?.visibility = View.VISIBLE
    }

    private fun ready(): Boolean = Shizuku.pingBinder() &&
        hasWritePermission() &&
        com.mahmutalperenunal.adaptivehz.core.engine.AdaptiveHzRuntimeState.getAccessibilityState(this) ==
            com.mahmutalperenunal.adaptivehz.core.engine.AdaptiveHzRuntimeState.AccessibilityState.WORKING

    private fun hasWritePermission() =
        checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun modeIndex(): Int = when (AdaptiveHzPrefs.getControlMode(this)) {
        AdaptiveHzMode.FORCE_MIN -> 1
        AdaptiveHzMode.FORCE_MAX -> 2
        else -> 0
    }
    private fun applyMode(mode: AdaptiveHzMode) {
        if (!AdaptiveHzPrefs.isControlsEnabled(this)) return
        AdaptiveHzPrefs.setControlMode(this, mode)
        if (mode != AdaptiveHzMode.OFF) OneSetup.configure(this)
        val permitted = when (mode) {
            // Stopping must stay available even when the helper service has disconnected.
            AdaptiveHzMode.OFF -> true
            AdaptiveHzMode.FORCE_MIN, AdaptiveHzMode.FORCE_MAX -> hasWritePermission()
            AdaptiveHzMode.ADAPTIVE -> ready()
        }
        if (!permitted) {
            showSetupHint()
            refreshStatus()
            return
        }
        AdaptiveHzActionHandler.applyModeAsync(this, mode, onComplete = { result ->
            if (!result.isOperationalSuccess && AdaptiveHzPrefs.isControlsEnabled(this)) {
                val message = if (mode == AdaptiveHzMode.OFF)
                    "已停止自动控制，恢复系统刷新率失败，请配置辅助服务后重试"
                else "刷新率设置未生效，请配置辅助服务后重试"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }, onSettled = { refreshStatus() })
    }

    private fun applyModeAt(position: Int) {
        applyMode(listOf(AdaptiveHzMode.ADAPTIVE,
            AdaptiveHzMode.FORCE_MIN, AdaptiveHzMode.FORCE_MAX)[position])
    }

    private fun configuration() {
        val delay = card()
        delay.addView(text("静置降频延迟", 18).apply { setTypeface(null, Typeface.BOLD) })
        val storedDelay = AdaptiveHzPrefs.getInteractionDropDelayMs(this)
        val currentDelay = ((storedDelay + 125L) / 250L).coerceIn(1L, 40L) * 250L
        if (storedDelay != currentDelay) AdaptiveHzPrefs.setInteractionDropDelayMs(this, currentDelay)
        val value = text(delayLabel(currentDelay), 24, blue).apply {
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        delay.addView(value)
        val slider = DelaySlider().apply {
            progress = ((currentDelay - 250L) / 250L).toInt().coerceIn(0, max)
            contentDescription = "静置降频延迟"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, position: Int, fromUser: Boolean) {
                    value.text = delayLabel(250L + position * 250L)
                    if (fromUser) {
                        TouchFeedback.step(seekBar)
                        AdaptiveHzPrefs.setInteractionDropDelayMs(this@RefreshPanel, 250L + position * 250L)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    seekBar.parent.requestDisallowInterceptTouchEvent(true)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    seekBar.parent.requestDisallowInterceptTouchEvent(false)
                    AdaptiveHzPrefs.setInteractionDropDelayMs(this@RefreshPanel, 250L + seekBar.progress * 250L)
                }
            })
        }
        delay.addView(slider, LinearLayout.LayoutParams(-1, dp(56)))
        val ends = LinearLayout(this)
        ends.addView(text("0.25秒", 12, muted), LinearLayout.LayoutParams(0, -2, 1f))
        ends.addView(text("10秒", 12, muted).apply { gravity = Gravity.END })
        delay.addView(ends)
        val activation = card()
        activation.addView(text("辅助服务", 18).apply { setTypeface(null, Typeface.BOLD) })
        activation.addView(text("刷新率控制所需的辅助服务。", 14, muted))
        button(activation, "配置辅助服务") { phoneActivation() }

    }

    private fun phoneActivation() {
        if (!AdaptiveHzPrefs.isControlsEnabled(this)) return
        startForegroundService(Intent(this, WirelessSetupService::class.java))
        modal("手机激活", """1.连接Wi-Fi，打开无线调试。
2.选择“使用配对码配对设备”。
3.在顶部横幅通知中输入6位配对码。

配对成功后会自动激活。请保持系统配对码窗口打开，直接在通知中输入。""", "打开无线调试") {
            val intent = Intent().setClassName("com.android.settings",
                "com.android.settings.Settings\$WirelessDebuggingActivity")
            runCatching { startActivity(intent) }.onFailure {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .putExtra(":settings:fragment_args_key", "toggle_adb_wireless"))
            }
        }
    }

    private fun modal(title: String, message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = rounded(surfaceColor, dimension(CommonR.dimen.roytools_dialog_radius))
        }
        panel.addView(text(title, 21).apply { setTypeface(null, Typeface.BOLD) })
        val scroll = ScrollView(this).apply {
            addView(text(message, 15, muted).apply { setPadding(0, dp(14), 0, dp(14)) })
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply {
            height = if (message.length > 1500) (resources.displayMetrics.heightPixels * 0.5f).toInt() else -2
        })
        if (actionLabel != null) button(panel, actionLabel) { dialog.dismiss(); action?.invoke() }
        button(panel, "关闭") { dialog.dismiss() }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.let(TouchFeedback::install)
        dialog.window?.setLayout(resources.displayMetrics.widthPixels - dp(48), -2)
    }

    private fun delayLabel(milliseconds: Long): String {
        return java.text.DecimalFormat("0.##").format(milliseconds / 1000.0) + "秒"
    }

    private inner class ModeSlider : com.github.kr328.clash.common.ui.FourStepSlider(this@RefreshPanel) {
        init { max = 2; tickCount = 3 }
    }

    private inner class DelaySlider : com.github.kr328.clash.common.ui.CapsuleSlider(this@RefreshPanel) {
        init {
            max = 39
            tickCount = 5
        }
    }
    private fun refreshStatus() {
        if (!::state.isInitialized) return
        val activeHz = getSystemService(DisplayManager::class.java).getDisplay(0)?.mode?.refreshRate?.roundToInt()
        val rateLabel = if (activeHz == null) "—" else "${activeHz}Hz"
        if (rate.text.toString() != rateLabel) rate.text = rateLabel
        if (!trackingMode) modeSlider?.progress = modeIndex()
        modeSlider?.contentDescription = "刷新率模式：${listOf("自适应", "60Hz", "120Hz")[modeIndex()]}，可选自适应、60Hz、120Hz"
        val enabled = AdaptiveHzPrefs.isControlsEnabled(this)
        updatingSwitch = true
        masterSwitch?.isChecked = enabled
        updatingSwitch = false
        fun enableTree(node: View) {
            node.isEnabled = enabled
            if (node is android.view.ViewGroup) for (i in 0 until node.childCount) enableTree(node.getChildAt(i))
        }
        for (card in controlledCards) { enableTree(card); card.alpha = if (enabled) 1f else 0.45f }
        modeHint?.visibility = if (enabled && !ready()) View.VISIBLE else View.GONE
        val stateLabel = when {
            !enabled -> "刷新率控制已关闭"
            !Shizuku.pingBinder() -> "辅助服务待激活"
            checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED ->
                "辅助服务缺少系统设置权限"
            !ready() -> "辅助功能尚未就绪"
            else -> "辅助服务运行中"
        }
        if (state.text.toString() != stateLabel) state.text = stateLabel
        // Complete the requested mode automatically when an explicit setup finishes.
        if (enabled && !trackingMode && AdaptiveHzPrefs.getCurrentMode(this) == AdaptiveHzMode.OFF) {
            val selected = AdaptiveHzPrefs.getControlMode(this)
            if (if (selected == AdaptiveHzMode.ADAPTIVE) ready() else hasWritePermission()) applyMode(selected)
        }
    }
    init { render() }
}












