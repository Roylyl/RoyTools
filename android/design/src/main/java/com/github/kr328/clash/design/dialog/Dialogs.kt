package com.github.kr328.clash.design.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import com.github.kr328.clash.common.compat.isAllowForceDarkCompat
import com.github.kr328.clash.common.compat.isSystemBarsTranslucentCompat
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.Insets
import com.github.kr328.clash.design.ui.Surface
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.util.resolveThemedResourceId
import com.github.kr328.clash.design.util.setOnInsertsChangedListener
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class AppBottomSheetDialog(context: Context) : BottomSheetDialog(context) {
    private var insets: Insets? = null

    override fun setContentView(view: View) {
        val scrollable = (view as? NestedScrollView) ?: NestedScrollView(context).apply {
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        super.setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bar = View(context).apply {
                setBackgroundResource(R.drawable.roytools_sheet_handle)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(bar, LinearLayout.LayoutParams(
                context.getPixels(R.dimen.roytools_sheet_handle_width),
                context.getPixels(R.dimen.bottom_sheet_header_height),
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = context.getPixels(R.dimen.bottom_sheet_background_padding_top)
                bottomMargin = topMargin
            })
            addView(scrollable, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setCancelable(true)

        window!!.apply {
            com.github.kr328.clash.common.ui.TouchFeedback.install(this)
            isSystemBarsTranslucentCompat = true
            isAllowForceDarkCompat = false
            setDimAmount(0.32f)
        }

        findViewById<ViewGroup>(com.google.android.material.R.id.container)?.apply {
            fitsSystemWindows = false
        }

        findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            setOnInsertsChangedListener {
                if (insets != it) {
                    insets = it

                    (layoutParams as CoordinatorLayout.LayoutParams).also { params ->
                        if (ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_LTR) {
                            params.setMargins(it.start, it.top, it.end, 0)
                        } else {
                            params.setMargins(it.end, it.top, it.start, 0)
                        }

                        setPaddingRelative(0, 0, 0, it.bottom)
                    }
                }
            }
        }

        setOnShowListener {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }
}

class FullScreenDialog(
    context: Context
) : Dialog(context, context.resolveThemedResourceId(R.attr.fullScreenDialogTheme)) {
    val surface = Surface()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window!!.apply {
            com.github.kr328.clash.common.ui.TouchFeedback.install(this)
            isSystemBarsTranslucentCompat = true

            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )

            decorView.setOnInsertsChangedListener {
                if (surface.insets != it)
                    surface.insets = it
            }
        }
    }
}
