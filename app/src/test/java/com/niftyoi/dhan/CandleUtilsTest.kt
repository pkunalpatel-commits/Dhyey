package com.niftyoi.dhan

import com.niftyoi.dhan.domain.calculations.CandleUtils
import com.niftyoi.dhan.domain.models.Candle
import org.junit.Assert.*
import org.junit.Test

class CandleUtilsTest {
    // 2026-11-02 09:15 IST = 03:45 UTC
    private val open = 1_793_591_100L - 0L

    private fun c(min: Int, o: Double, h: Double, l: Double, cl: Double, v: Long = 10) =
        Candle(sessionStart() + min * 60L, o, h, l, cl, v, 100L + min)

    private fun sessionStart(): Long {
        // compute 09:15 IST on 2026-11-02 independently
        val utc = java.time.ZonedDateTime.of(2026, 11, 2, 9, 15, 0, 0, java.time.ZoneId.of("Asia/Kolkata"))
        return utc.toEpochSecond()
    }

    @Test fun resampleTo5Minutes() {
        val src = (0 until 10).map { c(it, 100.0 + it, 101.0 + it, 99.0 + it, 100.5 + it) }
        val out = CandleUtils.resample(src, 5)
        assertEquals(2, out.size)
        assertEquals(sessionStart(), out[0].time)
        assertEquals(100.0, out[0].open, 0.0)
        assertEquals(105.0, out[0].high, 0.0)  // max of 101..105
        assertEquals(99.0, out[0].low, 0.0)
        assertEquals(104.5, out[0].close, 0.0)
        assertEquals(50L, out[0].volume)
        assertEquals(104L, out[0].oi)
        assertEquals(sessionStart() + 300, out[1].time)
    }

    @Test fun resampleTo3And30() {
        val src = (0 until 60).map { c(it, 1.0, 2.0, 0.5, 1.5) }
        assertEquals(20, CandleUtils.resample(src, 3).size)
        assertEquals(2, CandleUtils.resample(src, 30).size)
        assertEquals(60, CandleUtils.resample(src, 1).size)
    }

    @Test fun vwapIsVolumeWeighted() {
        val a = c(0, 10.0, 10.0, 10.0, 10.0, 100)
        val b = c(1, 20.0, 20.0, 20.0, 20.0, 300)
        val v = CandleUtils.vwap(listOf(a, b))
        assertEquals(10.0, v[0].second, 1e-9)
        assertEquals((10.0 * 100 + 20.0 * 300) / 400, v[1].second, 1e-9)
    }

    @Test fun vwapSkipsZeroVolumeStart() {
        val v = CandleUtils.vwap(listOf(c(0, 1.0, 1.0, 1.0, 1.0, 0), c(1, 2.0, 2.0, 2.0, 2.0, 10)))
        assertEquals(1, v.size)
    }

    @Test fun bucketStartMatchesResample() {
        val t = sessionStart() + 7 * 60 + 13
        assertEquals(sessionStart() + 5 * 60, CandleUtils.bucketStart(t, 5))
        assertEquals(sessionStart() + 6 * 60, CandleUtils.bucketStart(t, 3))
    }

    @Test fun normalizeEpochHandles1980Base() {
        val unix = sessionStart()
        assertEquals(unix, CandleUtils.normalizeEpoch(unix))
        assertEquals(unix, CandleUtils.normalizeEpoch(unix - 315_513_000L))
    }
}
