package com.github.kr328.clash.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.github.kr328.clash.common.log.Log

class Resource<T>(private val label: String = "remote") {
    private interface Callback<T> {
        fun accept(value: T)
    }

    private val pending: MutableSet<Callback<T>> = mutableSetOf()

    private var value: T? = null

    suspend fun get(): T {
        return suspendCancellableCoroutine { ctx ->
            val callback = object : Callback<T> {
                override fun accept(value: T) {
                    ctx.resume(value)
                }
            }

            ctx.invokeOnCancellation {
                cancel(callback)
            }

            get(callback)
        }
    }

    fun set(v: T?) {
        setAndNotify(v)
    }

    fun reset(v: T) {
        resetIfMatched(v)
    }

    @Synchronized
    private fun get(callback: Callback<T>) {
        val v = value

        if (v == null) {
            pending.add(callback)
            Log.d("Resource $label waiting count=${pending.size}")
        } else {
            callback.accept(v)
        }
    }

    @Synchronized
    private fun setAndNotify(value: T?) {
        this.value = value

        if (value != null) {
            val callbacks = pending.toList()
            pending.clear()
            Log.d("Resource $label ready pending=${callbacks.size}")
            callbacks.forEach {
                it.accept(value)
            }
        } else {
            Log.d("Resource $label unavailable pending=${pending.size}")
        }
    }

    @Synchronized
    private fun resetIfMatched(value: T) {
        if (this.value === value) {
            this.value = null
        }
    }

    @Synchronized
    private fun cancel(callback: Callback<T>) {
        if (pending.remove(callback)) Log.d("Resource $label wait cancelled remaining=${pending.size}")
    }
}
