package com.github.kr328.clash.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficRateTest {
    @Test fun idleAndSmallRatesUseKilobytesWithoutLosingTheRawByteScale() {
        assertEquals("0.00KB/s", 0L.trafficUploadRate())
        assertEquals("0.50KB/s", 500L.trafficDownloadRate())
    }
    @Test fun uploadAndDownloadDecodeIndependentlyAndSwitchToMegabytes() {
        val upload = (1L shl 30) or 200L
        val download = (2L shl 30) or 200L
        val packed = (upload shl 32) or download
        assertEquals("2.05KB/s", packed.trafficUploadRate())
        assertEquals("2.10MB/s", packed.trafficDownloadRate())
    }
    @Test fun gigabyteNativeScaleStaysInTheRequestedMegabyteUnit() {
        assertEquals("1073.74MB/s", ((3L shl 30) or 100L).trafficDownloadRate())
    }
}
