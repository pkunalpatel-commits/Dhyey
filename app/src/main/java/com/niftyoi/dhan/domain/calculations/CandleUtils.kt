package com.niftyoi.dhan.domain.calculations

import com.niftyoi.dhan.domain.models.Candle

object CandleUtils {
    private const val IST = 19_800L
    /** Seconds between 1970-01-01 and 1980-01-01 (IST-based epoch used by some Dhan payloads). */
    private const val EPOCH_1980_SHIFT = 315_513_000L

    /** Defensive normalisation: values that are too small for a current Unix time are treated as 1980-based. */
    fun normalizeEpoch(ts: Long): Long = if (ts in 1..1_600_000_000L) ts + EPOCH_1980_SHIFT else ts

    private fun sessionOpen(ts: Long): Long {
        val dayStart = Math.floorDiv(ts + IST, 86_400L) * 86_400L - IST
        return dayStart + 9 * 3600L + 15 * 60L // 09:15 IST
    }

    /** Aggregate candles into N-minute buckets aligned to the 09:15 IST session open. */
    fun resample(c: List<Candle>, minutes: Int): List<Candle> {
        if (minutes <= 1 || c.isEmpty()) return c.sortedBy { it.time }
        val step = minutes * 60L
        val out = LinkedHashMap<Long, Candle>()
        for (x in c.sortedBy { it.time }) {
            val open = sessionOpen(x.time)
            val key = open + Math.floorDiv(x.time - open, step) * step
            val cur = out[key]
            out[key] = if (cur == null) x.copy(time = key) else cur.copy(
                high = maxOf(cur.high, x.high), low = minOf(cur.low, x.low), close = x.close,
                volume = cur.volume + x.volume, oi = x.oi ?: cur.oi,
            )
        }
        return out.values.toList()
    }

    /** Session VWAP (typical price weighted by volume), reset every IST day. Skips candles until volume > 0. */
    fun vwap(c: List<Candle>): List<Pair<Long, Double>> {
        val out = mutableListOf<Pair<Long, Double>>()
        var day = Long.MIN_VALUE
        var pv = 0.0
        var v = 0.0
        for (x in c.sortedBy { it.time }) {
            val d = Math.floorDiv(x.time + IST, 86_400L)
            if (d != day) { day = d; pv = 0.0; v = 0.0 }
            pv += (x.high + x.low + x.close) / 3.0 * x.volume
            v += x.volume
            if (v > 0) out += x.time to pv / v
        }
        return out
    }

    /** Bucket start for a live tick at the given timeframe. */
    fun bucketStart(ts: Long, minutes: Int): Long {
        val open = sessionOpen(ts)
        val step = minutes * 60L
        return open + Math.floorDiv(ts - open, step) * step
    }
}
