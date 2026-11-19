package com.github.kr328.clash.design.component

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.PopupMenu
import com.github.kr328.clash.common.ui.TouchFeedback
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.channels.Channel

/** Display-only controls for the node page; routing mode belongs to the proxy home page. */
class ProxyMenu(
    private val context: Context,
    private val menuView: View,
    private val uiStore: UiStore,
    private val requests: Channel<ProxyDesign.Request>,
    private val updateConfig: () -> Unit,
) {
    private val menu = PopupMenu(context, menuView).apply {
        menuInflater.inflate(R.menu.menu_proxy, this.menu)
    }.menu
    private var popup: PopupWindow? = null
    private var options: AlertDialog? = null
    private val accent get() = context.resolveThemedColor(androidx.appcompat.R.attr.colorPrimary)
    private val surface get() = context.resolveThemedColor(com.google.android.material.R.attr.colorSurface)
    private val textColor get() = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
    private val muted get() = context.resolveThemedColor(R.attr.glassTextSecondary)
    private val secondarySurface get() = context.resolveThemedColor(R.attr.glassIconSurface)
    private val menuRadius get() = context.resources.getDimension(com.github.kr328.clash.common.R.dimen.roytools_menu_radius)
    private val controlRadius get() = context.resources.getDimension(com.github.kr328.clash.common.R.dimen.roytools_control_radius)
    private val iconRadius get() = context.resources.getDimension(com.github.kr328.clash.common.R.dimen.roytools_icon_radius)

    init {
        menuView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                popup?.dismiss()
                options?.dismiss()
            }
        })
    }

    fun show() {
        if (!menuView.isAttachedToWindow || popup?.isShowing == true) return
        syncSelection()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        for (index in 0 until menu.size()) {
            val item = menu.getItem(index)
            if (!item.isVisible) continue
            val row = menuRow(item)
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(4)
            })
            row.setOnClickListener {
                // PopupWindow has its own window, outside the Activity's TouchFeedback callback.
                TouchFeedback.click(row)
                popup?.dismiss()
                item.subMenu?.let { showOptions(item.title ?: "", it) }
            }
        }
        val available = menuView.rootView.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        popup = PopupWindow(content, minOf(dp(280), available - dp(32)), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(rounded(surface, menuRadius, withAlpha(muted, 24)))
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setOnDismissListener { popup = null }
            showAsDropDown(menuView, 0, dp(8), Gravity.END)
        }
    }

    private fun menuRow(item: MenuItem): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(68)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isFocusable = true
        background = ripple(surface)
        val isLayout = item.itemId == R.id.proxy_layout
        addView(ImageView(context).apply {
            setImageResource(if (isLayout) R.drawable.ic_baseline_view_list else R.drawable.roytools_proxy_sort)
            imageTintList = ColorStateList.valueOf(accent)
            background = rounded(secondarySurface, iconRadius)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(item.title, 16f, textColor))
            val subMenu = item.subMenu
            val selected = subMenu?.let { group -> (0 until group.size()).map(group::getItem).firstOrNull { it.isChecked } }
            addView(label(selected?.title, 13f, muted).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_glass_chevron)
            imageTintList = ColorStateList.valueOf(muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
    }

    private fun showOptions(title: CharSequence, group: Menu) {
        options?.dismiss()
        syncSelection()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        for (index in 0 until group.size()) {
            val item = group.getItem(index)
            val checked = item.isChecked
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                isFocusable = true
                isSelected = checked
                stateDescription = if (checked) "已选中" else "未选中"
                background = ripple(if (checked) withAlpha(accent, 22) else secondarySurface,
                    if (checked) withAlpha(accent, 100) else Color.TRANSPARENT)
                setPadding(dp(16), dp(8), dp(12), dp(8))
                addView(label(item.title, 16f, if (checked) accent else textColor), LinearLayout.LayoutParams(0, -2, 1f))
                addView(AppCompatRadioButton(context).apply {
                    isChecked = checked
                    isClickable = false
                    isFocusable = false
                    buttonTintList = ColorStateList.valueOf(if (checked) accent else muted)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                setOnClickListener {
                    if (!item.isChecked) select(item)
                    options?.dismiss()
                }
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(8) })
        }
        val scroll = ScrollView(context).apply { isFillViewport = false; addView(content) }
        options = RoyToolsDialogBuilder(context).setTitle(title).setView(scroll)
            .setNegativeButton(R.string.close, null).create().apply {
                setOnDismissListener { options = null }
                show()
            }
    }

    private fun select(item: MenuItem) {
        item.isChecked = true
        when (item.itemId) {
            R.id.single -> uiStore.proxyLine = 1
            R.id.doubles -> uiStore.proxyLine = 2
            R.id.multiple -> uiStore.proxyLine = 3
            R.id.default_ -> uiStore.proxySort = ProxySort.Default
            R.id.name -> uiStore.proxySort = ProxySort.Title
            R.id.delay -> uiStore.proxySort = ProxySort.Delay
            else -> return
        }
        updateConfig()
        requests.trySend(ProxyDesign.Request.ReloadAll)
    }

    private fun syncSelection() {
        menu.findItem(when (uiStore.proxyLine) { 1 -> R.id.single; 3 -> R.id.multiple; else -> R.id.doubles }).isChecked = true
        menu.findItem(when (uiStore.proxySort) {
            ProxySort.Default -> R.id.default_
            ProxySort.Title -> R.id.name
            ProxySort.Delay -> R.id.delay
        }).isChecked = true
    }

    private fun label(value: CharSequence?, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    private fun rounded(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (Color.alpha(stroke) > 0) setStroke(dp(1), stroke)
    }

    private fun ripple(color: Int, stroke: Int = Color.TRANSPARENT) = RippleDrawable(
        ColorStateList.valueOf(withAlpha(accent, 34)), rounded(color, controlRadius, stroke), rounded(Color.WHITE, controlRadius))

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00ffffff) or (alpha shl 24)
    private fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()
}
