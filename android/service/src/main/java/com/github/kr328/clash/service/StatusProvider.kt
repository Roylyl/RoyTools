package com.github.kr328.clash.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.service.util.ProxyWidgetState

class StatusProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            METHOD_WIDGET_STATE -> {
                enforceWidgetCaller()
                ProxyWidgetState.query(requireNotNull(context))
            }
            METHOD_WIDGET_MODE -> {
                enforceWidgetCaller()
                ProxyWidgetState.setMode(requireNotNull(context), arg ?: extras?.getString(ProxyWidgetState.KEY_MODE))
            }
            METHOD_CURRENT_PROFILE -> {
                return if (serviceRunning)
                    Bundle().apply {
                        putString("name", currentProfile)
                    }
                else
                    null
            }
            else -> super.call(method, arg, extras)
        }
    }

    private fun enforceWidgetCaller() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("Only this application may access proxy widget state")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw IllegalArgumentException("Stub!")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        throw IllegalArgumentException("Stub!")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw IllegalArgumentException("Stub!")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw IllegalArgumentException("Stub!")
    }

    override fun getType(uri: Uri): String? {
        throw IllegalArgumentException("Stub!")
    }

    override fun onCreate(): Boolean {
        return true
    }

    companion object {
        const val METHOD_CURRENT_PROFILE = "currentProfile"
        const val METHOD_WIDGET_STATE = "widgetState"
        const val METHOD_WIDGET_MODE = "widgetMode"

        private const val CLASH_SERVICE_RUNNING_FILE = "service_running.lock"

        @Volatile
        var serviceRunning: Boolean = false

        // Persist user intent independently of the current process. A system kill must not
        // erase recovery state, while explicit stop, VPN revocation and runtime errors do.
        var shouldStartClashOnBoot: Boolean
            get() = Global.application.filesDir.resolve(CLASH_SERVICE_RUNNING_FILE).exists()
            set(value) {
                Global.application.filesDir.resolve(CLASH_SERVICE_RUNNING_FILE).apply {
                    if (value)
                        createNewFile()
                    else
                        delete()
                }
            }
        @Volatile
        var currentProfile: String? = null
    }
}
