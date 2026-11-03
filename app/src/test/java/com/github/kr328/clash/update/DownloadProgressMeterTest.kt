package com.github.kr328.clash.update

import org.junit.Assert.*
import org.junit.Test

class DownloadProgressMeterTest {
    @Test fun speedUsesActualElapsedTimeAndDecimalMegabytes() {
        val meter = DownloadProgressMeter()
        assertEquals(0.0, meter.sample(500_000, 4_000_000, 1000).megabytesPerSecond, 0.0)
        val frame = meter.sample(2_500_000, 4_000_000, 2000)
        assertEquals(2.0, frame.megabytesPerSecond, 0.0001)
        assertEquals(62, frame.percent)
    }
    @Test fun resetCountersAndRepeatedTimesNeverProduceNegativeOrInfiniteSpeed() {
        val meter = DownloadProgressMeter()
        meter.sample(10_000_000, 20_000_000, 1000)
        assertEquals(0.0, meter.sample(100, -1, 2000).megabytesPerSecond, 0.0)
        val waiting = meter.sample(200, -1, 2000)
        assertEquals(0.0, waiting.megabytesPerSecond, 0.0)
        assertNull(waiting.percent)
    }
    @Test fun percentHandlesCompletionAndOversizedByteCountsWithoutOverflow() {
        val meter = DownloadProgressMeter()
        assertEquals(100, meter.sample(Long.MAX_VALUE, Long.MAX_VALUE, 1).percent)
        assertEquals(100, meter.sample(150, 100, 2).percent)
        assertEquals(0, meter.sample(-1, 100, 3).percent)
    }
}
