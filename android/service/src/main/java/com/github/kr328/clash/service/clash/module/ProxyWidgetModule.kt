package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.os.SystemClock
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.util.ProxyUsageStore
import com.github.kr328.clash.service.util.ProxyWidgetState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Reuses the proxy runtime; no second service, wake lock or connectivity observer. */
class ProxyWidgetModule(service: Service) : Module<Unit>(service) {
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun run() {
        val changes = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }
        val clockChanges = receiveBroadcast(requireSelf = false, capacity = Channel.CONFLATED) {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        try {
            update(sample = true)
            var nextSample = SystemClock.elapsedRealtime() + SAMPLE_INTERVAL
            var nextMidnight = nextLocalMidnight()
            while (true) {
                val sampleDelay = (nextSample - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                val midnightDelay = (nextMidnight - System.currentTimeMillis()).coerceAtLeast(0L)
                select<Unit> {
                    changes.onReceive { update(sample = false) }
                    clockChanges.onReceive {
                        nextMidnight = nextLocalMidnight()
                        update(sample = false)
                    }
                    onTimeout(minOf(sampleDelay, midnightDelay)) {
                        val sampleDue = SystemClock.elapsedRealtime() >= nextSample
                        update(sample = sampleDue)
                        if (sampleDue) nextSample = SystemClock.elapsedRealtime() + SAMPLE_INTERVAL
                        nextMidnight = nextLocalMidnight()
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { update(sample = true, stopped = true) }
        }
    }

    private fun nextLocalMidnight(): Long {
        val zone = ZoneId.systemDefault()
        return LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun update(sample: Boolean, stopped: Boolean = false) {
        try {
            if (sample) ProxyUsageStore.sample(service)
            if (!ProxyWidgetState.hasWidgets(service)) return
            val state = ProxyWidgetState.query(service)
            if (stopped) {
                state.putBoolean(ProxyWidgetState.KEY_RUNNING, false)
                state.putBoolean(ProxyWidgetState.KEY_READY, false)
                state.putString(ProxyWidgetState.KEY_MODE, "default")
                state.remove(ProxyWidgetState.KEY_EFFECTIVE_MODE)
                state.remove(ProxyWidgetState.KEY_NODE)
                state.remove(ProxyWidgetState.KEY_NODE_GROUP)
            }
            ProxyWidgetState.publish(service, state)
        } catch (e: Exception) {
            // Widget/accounting failures must not bring down the proxy runtime.
            Log.w("Proxy widget snapshot update failed", e)
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL = 5_000L
    }
}
