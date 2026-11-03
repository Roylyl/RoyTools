package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.design.ui.Surface
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.setOnInsertsChangedListener
import com.github.kr328.clash.design.dialog.RoyToolsDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

abstract class Design<R>(val context: Context) :
    CoroutineScope by CoroutineScope(Dispatchers.Unconfined) {
    abstract val root: View

    val surface = Surface()
    val requests: Channel<R> = Channel(Channel.UNLIMITED)

    suspend fun showToast(
        resId: Int,
        duration: ToastDuration
    ) {
        return showToast(context.getString(resId), duration)
    }

    suspend fun showToast(
        message: CharSequence,
        duration: ToastDuration
    ) {
        withContext(Dispatchers.Main) {
            if (message.isBlank()) return@withContext
            activeToast?.cancel()
            activeToast = Toast.makeText(context.applicationContext, message,
                if (duration == ToastDuration.Short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG)
                .also { it.show() }
        }
    }

    private var activeToast: Toast? = null

    suspend fun showActionDialog(message: CharSequence, action: Int, onAction: () -> Unit) {
        withContext(Dispatchers.Main) {
            RoyToolsDialogBuilder(context).setMessage(message)
                .setPositiveButton(action) { _, _ -> onAction() }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }

    init {
        when (context) {
            is AppCompatActivity -> {
                context.window.decorView.setOnInsertsChangedListener {
                    if (surface.insets != it) {
                        surface.insets = it
                    }
                }
            }
        }
    }
}
