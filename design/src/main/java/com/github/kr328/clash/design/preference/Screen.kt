package com.github.kr328.clash.design.preference

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.CoroutineScope

interface PreferenceScreen : CoroutineScope {
    val context: Context
    val root: ViewGroup
    var section: LinearLayout?
}

fun CoroutineScope.preferenceScreen(
    context: Context,
    configure: PreferenceScreen.() -> Unit
): PreferenceScreen {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val spacing = context.getPixels(R.dimen.glass_page_margin)
        setPadding(0, spacing / 2, 0, spacing)
        clipToPadding = false
    }

    val impl = object : PreferenceScreen, CoroutineScope by this {
        override val context: Context
            get() = context
        override val root: ViewGroup
            get() = root
        override var section: LinearLayout? = null
    }

    impl.configure()

    return impl
}

fun PreferenceScreen.addElement(preference: Preference) {
    val spacing = context.getPixels(R.dimen.glass_page_margin)
    if (preference.view is TextView) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (18 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundResource(R.drawable.roytools_card)
        }
        root.addView(card, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(spacing, spacing / 4, spacing, spacing / 2)
        })
        section = card
        (preference.view as TextView).apply {
            setPadding(0, 0, 0, spacing / 2)
            textSize = 18f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface))
        }
        card.addView(preference.view)
        return
    }
    val params = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    if (section == null) {
        val vertical = context.getPixels(R.dimen.glass_row_spacing)
        params.setMargins(spacing, vertical, spacing, vertical)
    }
    (section ?: root).addView(preference.view, params)
}
