package com.niftyoi.dhan.domain.calculations

import com.niftyoi.dhan.domain.models.Candle

/**
 * Serialises chart data for the Lightweight Charts WebView. Times are shifted by +05:30 so that the
 * chart's UTC-based axis shows IST clock time.
 */
object ChartJson {
    const val IST_SHIFT = 19_800L

    private fun num(d: Double) = if (d.isNaN() || d.isInfinite()) "0" else d.toString()

    fun candles(c: List<Candle>): String = c.joinToString(",", "[", "]") {
        "{\"time\":${it.time + IST_SHIFT},\"open\":${num(it.open)},\"high\":${num(it.high)},\"low\":${num(it.low)},\"close\":${num(it.close)}}"
    }

    fun volume(c: List<Candle>): String = c.joinToString(",", "[", "]") {
        "{\"time\":${it.time + IST_SHIFT},\"value\":${it.volume},\"up\":${it.close >= it.open}}"
    }

    fun line(l: List<Pair<Long, Double>>): String = l.joinToString(",", "[", "]") {
        "{\"time\":${it.first + IST_SHIFT},\"value\":${num(it.second)}}"
    }

    fun oi(c: List<Candle>): String = c.filter { it.oi != null }.joinToString(",", "[", "]") {
        "{\"time\":${it.time + IST_SHIFT},\"value\":${it.oi}}"
    }

    /** OI change per candle = OI - previous candle's OI. */
    fun oiChange(c: List<Candle>): String {
        val withOi = c.filter { it.oi != null }
        return withOi.mapIndexed { i, x ->
            val prev = if (i == 0) x.oi!! else withOi[i - 1].oi!!
            "{\"time\":${x.time + IST_SHIFT},\"value\":${x.oi!! - prev}}"
        }.joinToString(",", "[", "]")
    }

    fun payload(und: List<Candle>, opt: List<Candle>, vwap: List<Pair<Long, Double>>): String =
        "{\"und\":${candles(und)},\"opt\":${candles(opt)},\"vol\":${volume(opt)},\"vwap\":${line(vwap)}," +
            "\"oi\":${oi(opt)},\"oiChg\":${oiChange(opt)}}"
}
