package com.niftyoi.dhan

import com.niftyoi.dhan.domain.calculations.ChartJson
import com.niftyoi.dhan.domain.models.Candle
import org.junit.Assert.*
import org.junit.Test

class ChartJsonTest {
    private val c = listOf(
        Candle(1000, 10.0, 12.0, 9.0, 11.0, 5, 100),
        Candle(1060, 11.0, 13.0, 10.0, 10.5, 7, 130),
    )

    @Test fun shiftsTimesToIst() {
        assertTrue(ChartJson.candles(c).startsWith("[{\"time\":${1000 + 19800},"))
    }

    @Test fun oiChangeIsDifferenceBetweenCandles() {
        val j = ChartJson.oiChange(c)
        assertTrue(j.contains("\"value\":0"))
        assertTrue(j.contains("\"value\":30"))
    }

    @Test fun volumeMarksUpDown() {
        val j = ChartJson.volume(c)
        assertTrue(j.contains("\"up\":true"))
        assertTrue(j.contains("\"up\":false"))
    }

    @Test fun payloadHasAllSeries() {
        val p = ChartJson.payload(c, c, listOf(1000L to 10.5))
        listOf("\"und\":", "\"opt\":", "\"vol\":", "\"vwap\":", "\"oi\":", "\"oiChg\":").forEach { assertTrue(p.contains(it)) }
    }

    @Test fun emptyInputGivesEmptyArrays() {
        assertEquals("[]", ChartJson.candles(emptyList()))
        assertEquals("[]", ChartJson.oiChange(emptyList()))
    }
}
