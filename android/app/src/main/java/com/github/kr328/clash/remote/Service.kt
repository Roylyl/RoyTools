package com.github.kr328.clash.remote

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.net.Uri
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.util.unbindServiceSilent
import com.github.kr328.clash.util.startClashService
import java.util.concurrent.TimeUnit

class Service(private val context: Application, val crashed: () -> Unit) {
    val remote = Resource<IRemoteService>("proxy-service")
    private var bound = false
    private var wanted = false
    private var reconnectAttempts = 0
    private val main = Handler(Looper.getMainLooper())
    private val reconnect = Runnable {
        if (wanted && !bound) bind()
    }

    private val connection = object : ServiceConnection {
        private var lastCrashed: Long = -1

        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            Log.d("Remote onConnected bound=$bound wanted=$wanted alive=${service.isBinderAlive}")
            if (!bound)
                return
            remote.set(service.unwrap(IRemoteService::class))
            reconnectAttempts = 0
            Log.d("Remote connection published")
            recoverRequestedRuntime()
        }

        override fun onBindingDied(name: ComponentName?) {
            reconnectBinding("binding-died")
        }

        override fun onNullBinding(name: ComponentName?) {
            reconnectBinding("null-binding")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w("Remote onDisconnected bound=$bound wanted=$wanted")
            if (!bound)
                return
            remote.set(null)

            if (System.currentTimeMillis() - lastCrashed < TOGGLE_CRASHED_INTERVAL) {
                unbind()

                crashed()
            }

            lastCrashed = System.currentTimeMillis()
            Log.w("RemoteService killed or crashed")
        }
    }

    fun bind() {
        wanted = true
        if (bound) {
            Log.d("Remote bind already requested")
            return
        }
        Log.d("Remote bind begin attempt=$reconnectAttempts")
        try {
            bound = context.bindService(
                RemoteService::class.intent, connection, Context.BIND_AUTO_CREATE
            )
            Log.d("Remote bind result=$bound")
            if (!bound) reconnectBinding("bind-returned-false")
        } catch (e: Exception) {
            Log.e("Remote bind failed", e)
            unbind()

            crashed()
        }
    }

    fun unbind() {
        wanted = false
        reconnectAttempts = 0
        main.removeCallbacks(reconnect)
        Log.d("Remote unbind bound=$bound")
        if (bound) {
            bound = false
            context.unbindServiceSilent(connection)
        }
        remote.set(null)
    }

    private fun reconnectBinding(reason: String) {
        Log.w("Remote connection unavailable reason=$reason bound=$bound wanted=$wanted")
        remote.set(null)
        if (bound) {
            bound = false
            context.unbindServiceSilent(connection)
        }
        main.removeCallbacks(reconnect)
        if (!wanted) return
        if (reconnectAttempts >= 2) {
            Log.e("Remote reconnect stopped after two attempts; retry when application becomes visible")
            return
        }
        reconnectAttempts++
        main.postDelayed(reconnect, 500)
    }

    private fun recoverRequestedRuntime() {
        // UI visibility owns this binding. Make one attempt on connection/reconnection,
        // including after Android loses a sticky VPN during interface-removal cleanup.
        // This is not a timer or an invisible-background restart loop.
        try {
            if (!bound || !ServiceStore(context).aggressiveKeepAlive ||
                !StatusProvider.shouldStartClashOnBoot)
                return

            val status = context.contentResolver.call(
                Uri.parse("content://${Authorities.STATUS_PROVIDER}"),
                StatusProvider.METHOD_CURRENT_PROFILE,
                null,
                null
            )
            // A non-null bundle means a runtime exists, even while its profile is loading.
            if (status != null || !StatusProvider.shouldStartClashOnBoot)
                return

            if (context.startClashService() != null) {
                StatusProvider.shouldStartClashOnBoot = false
                Log.w("Service recovery needs renewed VPN permission")
            } else {
                Log.i("Requested service recovery after UI connection")
            }
        } catch (e: RuntimeException) {
            // The system may deny foreground-service starts; leave retries to a later
            // user-visible connection, never spin or bypass the platform restriction.
            Log.w("Service recovery unavailable: ${e.message}", e)
        }
    }

    companion object {
        private val TOGGLE_CRASHED_INTERVAL = TimeUnit.SECONDS.toMillis(10)
    }
}
