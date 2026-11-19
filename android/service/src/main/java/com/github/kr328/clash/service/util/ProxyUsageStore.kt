package com.github.kr328.clash.service.util

import android.content.Context
import android.content.SharedPreferences
import com.github.kr328.clash.core.Clash
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Proxy-process accounting. Reading a widget snapshot never samples or resets the core. */
object ProxyUsageStore {
    private const val FILE = "roytools_proxy_usage"
    private const val DAY = "day"
    private const val SESSION = "session"
    private const val WIFI = "wifi"
    private const val MOBILE = "mobile"
    private const val LAST_WIFI = "last_native_wifi"
    private const val LAST_MOBILE = "last_native_mobile"
    private val processSession = UUID.randomUUID().toString()
    private var preferences: SharedPreferences? = null
    private var ledger: ProxyUsageLedger? = null
    private var dirty = false

    /** Today's local Wi-Fi/mobile proxy bytes; no native work. Midnight rollover is persisted. */
    @Synchronized
    fun read(context: Context): Pair<Long, Long> {
        val current = readSnapshot(context)
        return current.wifi to current.mobile
    }

    /** One immutable day/count snapshot, so an IPC reply cannot mix values from two dates. */
    @Synchronized
    fun readSnapshot(context: Context): ProxyUsageLedger {
        update(state(context).forDay(today()))
        persist()
        return requireNotNull(ledger)
    }

    /** Call on the runtime's IO coroutine every five seconds and once during shutdown. */
    @Synchronized
    fun sample(context: Context): Pair<Long, Long> {
        val current = state(context)
        val native = runCatching { Clash.queryProxyUsage() }.getOrNull()
        // Read the local date after the native call as the call itself can span midnight.
        val day = today()
        val bucket = current.forDay(day)
        val next = if (native != null && native.day == day)
            bucket.sample(native.day, processSession, native.wifi, native.mobile)
        else bucket // Ignore a stale native day, including a date/time-zone transition.
        update(next)
        persist()
        return next.wifi to next.mobile
    }

    private fun update(next: ProxyUsageLedger) {
        if (ledger?.sameState(next) != true) {
            ledger = next
            dirty = true
        }
    }

    private fun persist() {
        val latest = requireNotNull(ledger)
        if (dirty) {
            // A failed commit remains dirty even if the next native sample is unchanged.
            dirty = !runCatching {
                requireNotNull(preferences).edit()
                    .putString(DAY, latest.day)
                    .putString(SESSION, latest.session)
                    .putLong(WIFI, latest.wifi)
                    .putLong(MOBILE, latest.mobile)
                    .putLong(LAST_WIFI, latest.lastNativeWiFi)
                    .putLong(LAST_MOBILE, latest.lastNativeMobile)
                    .commit()
            }.getOrDefault(false)
        }
    }

    private fun today(): String = LocalDate.now(ZoneId.systemDefault()).toString()

    private fun state(context: Context): ProxyUsageLedger {
        ledger?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        preferences = prefs
        return ProxyUsageLedger(
            prefs.getString(DAY, "") ?: "",
            prefs.getString(SESSION, "") ?: "",
            prefs.getLong(WIFI, 0L), prefs.getLong(MOBILE, 0L),
            prefs.getLong(LAST_WIFI, 0L), prefs.getLong(LAST_MOBILE, 0L)
        ).also { ledger = it }
    }
}
