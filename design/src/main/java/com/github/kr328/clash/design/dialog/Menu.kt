package com.github.kr328.clash.design.dialog

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import com.github.kr328.clash.design.R

fun showRoyToolsMenu(context: Context, menu: Menu, selected: (MenuItem) -> Boolean, title: CharSequence = "选项") {
    val items = (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible }
    RoyToolsDialogBuilder(context).setTitle(title)
        .setItems(items.map { if (it.isChecked) "✓" + it.title else it.title }.toTypedArray()) { _, index ->
            val item = items[index]
            if (item.isEnabled) {
                val subMenu = item.subMenu
                if (subMenu != null) showRoyToolsMenu(context, subMenu, selected, item.title ?: title)
                else selected(item)
            }
        }.setNegativeButton(R.string.close, null).show()
}
