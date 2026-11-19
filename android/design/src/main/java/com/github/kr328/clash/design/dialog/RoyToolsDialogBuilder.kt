package com.github.kr328.clash.design.dialog

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.common.ui.TouchFeedback
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/** Inflate themed Material buttons before AlertDialogLayout measures the action panel. */
class RoyToolsDialogBuilder(context: Context) :
    MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_RoyTools_Dialog) {
    init {
        val radius = context.resources.getDimension(com.github.kr328.clash.common.R.dimen.roytools_dialog_radius)
        setBackground(MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(radius).build()).apply {
            fillColor = ColorStateList.valueOf(context.resolveThemedColor(com.google.android.material.R.attr.colorSurface))
        })
    }

    override fun create(): AlertDialog {
        val dialog = super.create()
        dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                dialog.window?.let(TouchFeedback::install)
            }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
        return dialog
    }
}
