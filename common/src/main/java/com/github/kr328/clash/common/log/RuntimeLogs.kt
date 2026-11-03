package com.github.kr328.clash.common.log

/** A process-local sink: libraries never depend on the app or start a logging service. */
object RuntimeLogs {
    @Volatile var sink: ((module: String, priority: Int, tag: String, message: String) -> Unit)? = null

    fun append(module: String, priority: Int, tag: String, message: String, error: Throwable? = null) {
        val text = if (error == null) message else message + "\n" + android.util.Log.getStackTraceString(error)
        runCatching { sink?.invoke(module, priority, tag, text) }
    }
}

/** Drop-in AndroidLog API for refresh core/service imports; Android logging is preserved. */
object RefreshLog {
    fun d(tag: String, message: String, error: Throwable? = null): Int = write(android.util.Log.DEBUG, tag, message, error)
    fun i(tag: String, message: String, error: Throwable? = null): Int = write(android.util.Log.INFO, tag, message, error)
    fun w(tag: String, message: String, error: Throwable? = null): Int = write(android.util.Log.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null): Int = write(android.util.Log.ERROR, tag, message, error)
    fun v(tag: String, message: String, error: Throwable? = null): Int = write(android.util.Log.VERBOSE, tag, message, error)
    fun isLoggable(tag: String, priority: Int): Boolean = android.util.Log.isLoggable(tag, priority)

    private fun write(priority: Int, tag: String, message: String, error: Throwable?): Int {
        RuntimeLogs.append("refresh", priority, tag, message, error)
        val text = if (error == null) message else message + "\n" + android.util.Log.getStackTraceString(error)
        return android.util.Log.println(priority, tag, text)
    }
}
