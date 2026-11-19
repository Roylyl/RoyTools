package com.github.kr328.clash.core.util

import com.github.kr328.clash.core.model.Traffic

fun Traffic.trafficUploadRate(): String = rateString(this ushr 32)
fun Traffic.trafficDownloadRate(): String = rateString(this and 0xFFFFFFFFL)

private fun rateString(encoded: Long): String {
    val unit = (encoded ushr 30).toInt() and 3
    val data = encoded and 0x3FFFFFFFL
    // The native bridge stores raw bytes for unit 0; other units have two decimal places.
    val bytes = if (unit == 0) data.toDouble()
        else data * doubleArrayOf(1.0, 1024.0, 1048576.0, 1073741824.0)[unit] / 100.0
    return if (bytes >= 1_000_000.0) String.format(java.util.Locale.ROOT, "%.2fMB/s", bytes / 1_000_000.0)
        else String.format(java.util.Locale.ROOT, "%.2fKB/s", bytes / 1000.0)
}

fun Traffic.trafficUpload(): String {
    return trafficString(scaleTraffic(this ushr 32))
}

fun Traffic.trafficDownload(): String {
    return trafficString(scaleTraffic(this and 0xFFFFFFFF))
}

fun Traffic.trafficTotal(): String {
    val upload = scaleTraffic(this ushr 32)
    val download = scaleTraffic(this and 0xFFFFFFFF)

    return trafficString(upload + download)
}

private fun trafficString(scaled: Long): String {
    return when {
        scaled > 1024 * 1024 * 1024 * 100L -> {
            val data = scaled / 1024 / 1024 / 1024

            String.format("%.2f GiB", data.toFloat() / 100)
        }
        scaled > 1024 * 1024 * 100L -> {
            val data = scaled / 1024 / 1024

            String.format("%.2f MiB", data.toFloat() / 100)
        }
        scaled > 1024 * 100L -> {
            val data = scaled / 1024

            String.format("%.2f KiB", data.toFloat() / 100)
        }
        else -> {
            "$scaled Bytes"
        }
    }
}

private fun scaleTraffic(value: Long): Long {
    val type = (value ushr 30) and 0x3
    val data = value and 0x3FFFFFFF

    return when (type) {
        0L -> data
        1L -> data * 1024
        2L -> data * 1024 * 1024
        3L -> data * 1024 * 1024 * 1024
        else -> throw IllegalArgumentException("invalid value type")
    }
}
