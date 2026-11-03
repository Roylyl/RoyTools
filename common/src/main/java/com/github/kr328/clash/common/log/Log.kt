package com.github.kr328.clash.common.log

object Log {
    private const val TAG = "RoyTools"

    fun i(message: String, throwable: Throwable? = null) =
        write(android.util.Log.INFO, message, throwable)

    fun w(message: String, throwable: Throwable? = null) =
        write(android.util.Log.WARN, message, throwable)

    fun e(message: String, throwable: Throwable? = null) =
        write(android.util.Log.ERROR, message, throwable)

    fun d(message: String, throwable: Throwable? = null) =
        write(android.util.Log.DEBUG, message, throwable)

    fun v(message: String, throwable: Throwable? = null) =
        write(android.util.Log.VERBOSE, message, throwable)

    fun f(message: String, throwable: Throwable): Int {
        RuntimeLogs.append("app", android.util.Log.ASSERT, TAG, message, throwable)
        return android.util.Log.wtf(message, throwable)
    }

    private fun write(priority: Int, message: String, throwable: Throwable?): Int {
        RuntimeLogs.append("app", priority, TAG, message, throwable)
        val text = if (throwable == null) message else message + "\n" + android.util.Log.getStackTraceString(throwable)
        return android.util.Log.println(priority, TAG, text)
    }
}
