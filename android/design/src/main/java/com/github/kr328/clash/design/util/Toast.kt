package com.github.kr328.clash.design.util

import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun Design<*>.showExceptionToast(message: CharSequence) {
    withContext(Dispatchers.Main) {
        RoyToolsDialogBuilder(context)
            .setTitle(R.string.error)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}

suspend fun Design<*>.showExceptionToast(exception: Exception) {
    showExceptionToast(exception.message ?: "Unknown")
}
