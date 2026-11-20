package com.github.kr328.clash.design.util

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.Insets as AndroidInsets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import com.github.kr328.clash.design.ui.Insets

fun View.padForSystemBars() {
    val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    fun applyPadding(insets: WindowInsetsCompat?) {
        val dispatched = insets?.getInsets(types) ?: AndroidInsets.NONE
        // A recreated Activity can receive already-consumed insets from AppCompat.
        val windowInsets = if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.windowInsets
                ?.let { WindowInsetsCompat.toWindowInsetsCompat(it).getInsets(types) }
        } else ViewCompat.getRootWindowInsets(this)?.getInsets(types)
        val safe = AndroidInsets.max(dispatched, windowInsets ?: AndroidInsets.NONE)
        if (paddingLeft != safe.left || paddingTop != safe.top ||
            paddingRight != safe.right || paddingBottom != safe.bottom) {
            setPadding(safe.left, safe.top, safe.right, safe.bottom)
        }
    }
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        applyPadding(insets)
        insets
    }
    doOnAttach {
        applyPadding(ViewCompat.getRootWindowInsets(it))
        ViewCompat.requestApplyInsets(it)
    }
}

fun View.setOnInsertsChangedListener(adaptLandscape: Boolean = true, listener: (Insets) -> Unit) {
    setOnApplyWindowInsetsListener { v, ins ->
        val compat = WindowInsetsCompat.toWindowInsetsCompat(ins, v)
        val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())

        val rInsets = if (ViewCompat.getLayoutDirection(v) == ViewCompat.LAYOUT_DIRECTION_LTR) {
            Insets(
                insets.left,
                insets.top,
                insets.right,
                insets.bottom,
            )
        } else {
            Insets(
                insets.right,
                insets.top,
                insets.left,
                insets.bottom,
            )
        }

        listener(if (adaptLandscape) rInsets.landscape(v.context) else rInsets)

        ins
    }

    requestApplyInsets()
}
