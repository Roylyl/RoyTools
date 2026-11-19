package com.github.kr328.clash.design.preference

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.kr328.clash.design.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.KMutableProperty0

interface SelectableListPreference<T> : ClickablePreference {
    var selected: Int

    var listener: OnChangedListener?
}

fun <T> PreferenceScreen.selectableList(
    value: KMutableProperty0<T>,
    values: Array<T>,
    valuesText: Array<Int>,
    @StringRes title: Int,
    @DrawableRes icon: Int? = null,
    configure: SelectableListPreference<T>.() -> Unit = {},
): SelectableListPreference<T> {
    val impl = object : SelectableListPreference<T>, ClickablePreference by clickable(title, icon) {
        override var selected: Int = 0
            set(value) {
                field = value

                this.summary = context.getText(valuesText[value])
            }
        override var listener: OnChangedListener? = null
    }

    impl.configure()

    launch(Dispatchers.Main) {
        val initial = withContext(Dispatchers.IO) {
            value.get()
        }

        impl.selected = values.indexOf(initial)

        impl.clicked {
            popupSelectMenu(impl, value, valuesText.map { context.getText(it) }, values)
        }
    }

    return impl
}

private fun <T> PreferenceScreen.popupSelectMenu(
    impl: SelectableListPreference<T>,
    value: KMutableProperty0<T>,
    valuesText: List<CharSequence>,
    values: Array<T>,
) {
    com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder(context)
        .setTitle(impl.title)
        .setSingleChoiceItems(valuesText.toTypedArray(), impl.selected) { dialog, position ->
            dialog.dismiss()
            launch(Dispatchers.Main) {
                withContext(Dispatchers.IO) { value.set(values[position]) }
                impl.selected = position
                impl.listener?.onChanged()
            }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}