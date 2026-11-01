package com.niftyoi.dhan

import com.niftyoi.dhan.domain.calculations.ChartLevelBuilder
import com.niftyoi.dhan.domain.models.Side
import com.niftyoi.dhan.domain.models.StrikeRow
import org.junit.Assert.*
import org.junit.Test

class ChartLevelBuilderTest {
    private val rows = listOf(25300.0, 25350.0, 25400.0, 25450.0).mapIndexed { i, k ->
        StrikeRow(k, 150.0 - i * 40, 1000L * (i + 1), 500, 20.0 + i * 45, 4000L - i * 700, 1000)
    }

    private fun levels() = ChartLevelBuilder.build(
        rows, premiumObs = rows.map { it.ceLtp to it.ceOi }, selectedSide = Side.CE,
        topN = 3, premiumBin = 5.0, vaPct = 0.7,
    )

    @Test fun coordinateSeparation() {
        val l = levels()
        val strikes = rows.map { it.strike }.toSet()
        assertTrue(l.underlying.isNotEmpty())
        // every underlying level is a strike-space value
        assertTrue(l.underlying.all { it.value in 25300.0..25450.0 })
        // no premium-panel level may be a strike-space value, and all must be premium magnitudes
        assertTrue(l.premium.isNotEmpty())
        assertTrue(l.premium.none { it.value in strikes })
        assertTrue(l.premium.all { it.value < 1000.0 })
    }

    @Test fun includesAllRequiredUnderlyingLevels() {
        val labels = levels().underlying.map { it.label }
        assertTrue(labels.any { it.startsWith("CE OI #1") })
        assertTrue(labels.any { it.startsWith("CE OI #3") })
        assertTrue(labels.any { it.startsWith("PE OI #1") })
        assertTrue(labels.any { it.startsWith("CE add #1") })
        assertTrue(labels.any { it.startsWith("PE add #1") })
        assertTrue(labels.any { it.startsWith("OI POC") })
        assertTrue(labels.any { it.startsWith("CE POC") })
        assertTrue(labels.any { it.startsWith("PE POC") })
        assertTrue(labels.any { it.startsWith("OI VAH") })
        assertTrue(labels.any { it.startsWith("OI VAL") })
    }

    @Test fun stripReportsOiChangeAndPct() {
        val s = levels().strip.first { it.label == "Highest CE OI" }
        assertEquals(25450.0, s.strike, 0.0)
        assertEquals(4000L, s.oi); assertEquals(3500L, s.change)
        assertEquals(700.0, s.pct!!, 1e-9)
    }

    @Test fun frozenLevelsAreMarkedPrev() {
        val first = levels()
        val second = ChartLevelBuilder.build(rows, rows.map { it.ceLtp to it.ceOi }, Side.CE, 3, 5.0, 0.7, frozen = first)
        assertTrue(second.underlying.any { it.kind == "prev" && it.label.startsWith("prev ") })
    }

    @Test fun jsonIsWellFormedEnough() {
        val j = levels().toJson()
        assertTrue(j.startsWith("{\"underlying\":["))
        assertTrue(j.contains("\"premium\":["))
        assertTrue(j.contains("\"strip\":["))
    }
}
