package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.doOnTextChanged
import com.github.kr328.clash.core.model.UiConfiguration
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.databinding.DialogTextFieldBinding
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.requestTextInput
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.util.selectableItemBackground
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

class LanSharingDesign(
    context: Context,
    initialSettings: Settings,
    private val saveSettings: suspend (allowLan: Boolean?, port: Int?) -> Settings,
) : Design<Unit>(context) {
    data class Settings(val enabled: Boolean, val port: Int, val bindAddress: String, val fixed: Boolean)
    data class Address(val ip: String, val network: Network)
    enum class Network(val title: Int) {
        Wifi(R.string.lan_wifi_address),
        Hotspot(R.string.lan_hotspot_address),
        Local(R.string.lan_local_address),
    }

    private val binding = DesignSettingsCommonBinding.inflate(context.layoutInflater, context.root, false)
    override val root: View get() = binding.root
    private val primary = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
    private val secondary = context.resolveThemedColor(R.attr.glassTextSecondary)
    private val accent = context.resolveThemedColor(androidx.appcompat.R.attr.colorPrimary)
    private val touchTarget = context.getPixels(R.dimen.glass_touch_target)
    private val pageMargin = context.getPixels(R.dimen.glass_page_margin)
    private val content = column().apply {
        setPadding(pageMargin, dp(8), pageMargin, pageMargin)
    }
    private val allowSwitch = MaterialSwitch(ContextThemeWrapper(context, R.style.ThemeOverlay_RoyTools_MaterialSwitch)).apply {
        minHeight = touchTarget
        contentDescription = context.getText(R.string.allow_lan)
    }
    private val portRow = row()
    private val portValue = text("", 15f, secondary).apply {
        includeFontPadding = false
        typeface = Typeface.MONOSPACE
    }
    private val status = text("", 15f, secondary)
    private val issue = text("", 13f, secondary).apply { visibility = View.GONE }
    private val noAddress = text(context.getText(R.string.lan_no_address), 13f, secondary)
    private val endpointCards = column()
    private var settings = initialSettings
    private var effective: UiConfiguration? = null
    private var running = false
    private var addresses = emptyList<Address>()
    private var rendering = false
    private var busy = false
    private var displayedEndpoints: Triple<List<Address>, Int, Int>? = null

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        binding.content.addView(content)

        val controls = card()
        val allowRow = row().apply {
            addView(text(context.getText(R.string.allow_lan), 16f), weighted())
            addView(allowSwitch)
            setOnClickListener { allowSwitch.performClick() }
        }
        controls.addView(allowRow)
        portRow.apply {
            addView(text(context.getText(R.string.lan_fixed_port), 16f), weighted())
            addView(portValue)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_glass_chevron)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(4) })
            isFocusable = true
            foreground = context.selectableItemBackground
            setOnClickListener { editPort() }
        }
        controls.addView(portRow)
        controls.addView(text(context.getText(R.string.lan_port_persists), 13f, secondary))

        val connection = card(context.getText(R.string.lan_connection_details))
        connection.addView(row().apply {
            addView(text(context.getText(R.string.lan_listening_state), 16f), weighted())
            addView(status)
        })
        connection.addView(text(context.getText(R.string.lan_sharing_instructions), 13f, secondary))
        connection.addView(noAddress)
        connection.addView(issue)
        content.addView(endpointCards)

        allowSwitch.setOnCheckedChangeListener { _, checked ->
            if (!rendering) update(allowLan = checked)
        }
        render()
    }

    fun setRuntimeState(running: Boolean, configuration: UiConfiguration? = null) {
        this.running = running
        if (configuration != null || !running) effective = configuration
        render()
    }

    fun setAddresses(addresses: List<Address>) {
        if (this.addresses == addresses) return
        this.addresses = addresses
        render()
    }

    private fun update(allowLan: Boolean? = null, port: Int? = null) {
        if (busy) return
        busy = true
        issue.visibility = View.GONE
        render()
        launch(Dispatchers.Main) {
            try {
                settings = saveSettings(allowLan, port)
            } catch (_: TimeoutCancellationException) {
                showSaveFailure()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showSaveFailure()
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun showSaveFailure() {
        issue.setText(R.string.lan_save_failed)
        issue.visibility = View.VISIBLE
    }

    private fun editPort() {
        val field = DialogTextFieldBinding.inflate(context.layoutInflater, null, false)
        val editor = field.textField
        editor.inputType = InputType.TYPE_CLASS_NUMBER
        editor.setText(settings.port.toString())
        val dialog = RoyToolsDialogBuilder(context)
            .setTitle(R.string.lan_fixed_port)
            .setMessage(R.string.lan_invalid_port)
            .setView(field.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lan_save) { _, _ ->
                val port = editor.text?.toString()?.toIntOrNull()
                if (port != null && port in 1..65535 && (port != settings.port || !settings.fixed)) update(port = port)
            }
            .create()
        dialog.setOnShowListener {
            editor.setSelection(editor.length())
            editor.doOnTextChanged { text, _, _, _ ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = text?.toString()?.toIntOrNull() in 1..65535
            }
            editor.requestTextInput()
        }
        dialog.show()
    }

    private fun render() {
        rendering = true
        allowSwitch.isChecked = settings.enabled
        allowSwitch.isEnabled = !busy
        (allowSwitch.parent as? View)?.isEnabled = !busy
        portRow.isEnabled = !busy
        portValue.text = settings.port.toString()
        rendering = false

        val active = effective
        val activeListener = active?.takeIf { running && settings.enabled && it.allowLan }
        val bindAddress = activeListener?.bindAddress ?: settings.bindAddress
        val configuredAddresses = addresses.filter { matchesBindAddress(bindAddress, it.ip) }
        val mixedPort = activeListener?.mixedPort?.takeIf { it > 0 }
        val httpPort = mixedPort ?: activeListener?.httpPort ?: settings.port
        val socksPort = mixedPort ?: activeListener?.socksPort ?: settings.port
        val applied = active != null && active.allowLan == settings.enabled &&
            (!settings.enabled || !settings.fixed || active.mixedPort == settings.port)
        status.setText(when {
            busy -> R.string.lan_applying
            running && !applied -> R.string.lan_applying
            !settings.enabled -> R.string.lan_status_off
            !running -> R.string.lan_status_waiting
            httpPort <= 0 && socksPort <= 0 -> R.string.lan_status_no_listener
            !matchesBindAddress(bindAddress, "0.0.0.0") && configuredAddresses.isEmpty() ->
                R.string.lan_status_restricted
            else -> R.string.lan_status_listening
        })
        noAddress.visibility = if (addresses.isEmpty()) View.VISIBLE else View.GONE
        val endpoints = Triple(configuredAddresses, httpPort, socksPort)
        if (endpoints == displayedEndpoints) return
        displayedEndpoints = endpoints
        endpointCards.removeAllViews()
        if (httpPort <= 0 && socksPort <= 0) return
        for (address in configuredAddresses) {
            val group = card(context.getString(R.string.lan_endpoint_heading, context.getText(address.network.title), address.ip), endpointCards)
            if (httpPort > 0) group.addView(endpoint("HTTP", "http://${address.ip}:$httpPort"))
            if (socksPort > 0) group.addView(endpoint("SOCKS5", "socks5://${address.ip}:$socksPort"))
        }
    }

    private fun endpoint(protocol: String, url: String) = row().apply {
        addView(column().apply {
            addView(text(protocol, 13f, secondary))
            addView(text(url, 14f).apply { typeface = Typeface.MONOSPACE })
        }, weighted())
        val copyLabel = context.getString(R.string.lan_copy_endpoint, protocol)
        addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_baseline_content_copy)
            imageTintList = ColorStateList.valueOf(accent)
            background = context.selectableItemBackground
            contentDescription = copyLabel
            setOnClickListener {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(protocol, url))
            }
        }, LinearLayout.LayoutParams(touchTarget, touchTarget))
    }

    private fun card(title: CharSequence? = null, host: LinearLayout = content) = column().apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setBackgroundResource(R.drawable.roytools_card)
        if (title != null) addView(text(title, 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        host.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun row() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        minimumHeight = touchTarget
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun text(value: CharSequence, size: Float, color: Int = primary) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun matchesBindAddress(bind: String, ip: String) = bind in setOf("", "*", "0.0.0.0", "::", "[::]") || bind == ip
}
