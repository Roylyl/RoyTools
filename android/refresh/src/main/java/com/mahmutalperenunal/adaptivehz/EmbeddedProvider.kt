package com.mahmutalperenunal.adaptivehz

import android.os.Bundle
import android.os.Binder
import com.github.kr328.clash.common.log.RefreshLog as Log
import moe.shizuku.api.BinderContainer
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider

class EmbeddedProvider : ShizukuProvider() {
    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "sendUserService") return super.call(method, arg, extras)
        // User services are launched by our shell/root backend.
        if (Binder.getCallingUid() !in listOf(0, 2000)) return null
        return runCatching {
            extras ?: return null
            extras.classLoader = BinderContainer::class.java.classLoader
            val token = extras.getString(USER_SERVICE_ARG_TOKEN) ?: return null
            val key = "moe.shizuku.privileged.api.intent.extra.BINDER"
            val remote = extras.getParcelable<BinderContainer>(key)?.binder ?: return null
            val options = Bundle().apply { putString(USER_SERVICE_ARG_TOKEN, token) }
            Shizuku.attachUserService(remote, options)
            Bundle().apply { putParcelable(key, BinderContainer(Shizuku.getBinder())) }
        }.onFailure { Log.e("EmbeddedProvider", "Unable to attach input service", it) }.getOrNull()
    }
}

