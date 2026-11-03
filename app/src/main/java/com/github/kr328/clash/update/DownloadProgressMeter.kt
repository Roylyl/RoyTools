package com.github.kr328.clash.update

data class DownloadProgress(val percent: Int?, val megabytesPerSecond: Double)

/** Uses elapsed time, so wall-clock changes cannot corrupt the displayed speed. */
class DownloadProgressMeter {
    private var previousBytes = 0L
    private var previousTime: Long? = null

    fun sample(bytes: Long, total: Long, elapsedMillis: Long): DownloadProgress {
        val before = previousTime
        val speed = if (before != null && elapsedMillis > before && bytes >= previousBytes)
            (bytes - previousBytes).toDouble() / (elapsedMillis - before) / 1000.0 else 0.0
        previousBytes = bytes
        previousTime = elapsedMillis
        val percent = if (total > 0) (bytes.toDouble() / total * 100).toInt().coerceIn(0, 100) else null
        return DownloadProgress(percent, speed)
    }
}
