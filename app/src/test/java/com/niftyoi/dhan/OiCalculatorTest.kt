package com.niftyoi.dhan

import com.niftyoi.dhan.domain.calculations.OiCalculator
import com.niftyoi.dhan.domain.models.Side
import com.niftyoi.dhan.domain.models.StrikeRow
import org.junit.Assert.*
import org.junit.Test

class OiCalculatorTest {
    private fun row(k: Double, ce: Long, pe: Long, cePrev: Long = 0, pePrev: Long = 0, ceLtp: Double = 0.0, peLtp: Double = 0.0) =
        StrikeRow(k, ceLtp, ce, cePrev, peLtp, pe, pePrev)

    private val chain = listOf(
        row(100.0, 10, 50, 5, 40), row(200.0, 80, 20, 20, 10),
        row(300.0, 30, 90, 30, 10), row(400.0, 5, 5, 10, 5),
    )

    @Test fun priceBin() {
        assertEquals(25125.0, OiCalculator.bin(25127.0, 25.0), 0.0)
        assertEquals(125.0, OiCalculator.bin(127.0, 5.0), 0.0)
        assertEquals(25125.0, OiCalculator.bin(25125.0, 25.0), 0.0)
    }

    @Test fun aggregateSumsPerBin() {
        val m = OiCalculator.aggregate(listOf(25127.0 to 10L, 25130.0 to 5L, 25150.0 to 7L), 25.0)
        assertEquals(15L, m[25125.0])
        assertEquals(7L, m[25150.0])
        assertEquals(22L, m.values.sum())
    }

    @Test fun pocIsArgmax() {
        assertEquals(200.0, OiCalculator.poc(mapOf(100.0 to 5L, 200.0 to 50L, 300.0 to 20L))!!, 0.0)
        assertNull(OiCalculator.poc(emptyMap()))
    }

    @Test fun valueAreaExpandsToHigherNeighbour() {
        val p = mapOf(100.0 to 5L, 200.0 to 15L, 300.0 to 40L, 400.0 to 25L, 500.0 to 10L, 600.0 to 5L)
        val va = OiCalculator.valueArea(p, 0.70)!!
        assertEquals(200.0, va.low, 0.0)
        assertEquals(400.0, va.vah, 0.0)
    }

    @Test fun valueAreaConfigurablePercent() {
        val p = mapOf(100.0 to 10L, 200.0 to 80L, 300.0 to 10L)
        val va = OiCalculator.valueArea(p, 0.5)!!
        assertEquals(200.0, va.low, 0.0); assertEquals(200.0, va.vah, 0.0)
        val all = OiCalculator.valueArea(p, 1.0)!!
        assertEquals(100.0, all.low, 0.0); assertEquals(300.0, all.vah, 0.0)
    }

    @Test fun valueAreaSingleBin() {
        val va = OiCalculator.valueArea(mapOf(100.0 to 10L), 0.7)!!
        assertEquals(100.0, va.low, 0.0); assertEquals(100.0, va.vah, 0.0)
    }

    @Test fun totalOiAndFuturesProfile() {
        val st = OiCalculator.futuresProfile(listOf(25127.0 to 100L, 25130.0 to 50L, 25160.0 to 30L, 0.0 to 5L), 25.0)
        assertEquals(180L, st.total)
        assertEquals(25125.0, st.poc!!, 0.0)
    }

    @Test fun ceAndPeAndCombinedProfiles() {
        assertEquals(80L, OiCalculator.strikeProfile(chain, Side.CE)[200.0])
        assertEquals(90L, OiCalculator.strikeProfile(chain, Side.PE)[300.0])
        assertEquals(100L, OiCalculator.strikeProfile(chain, Side.COMBINED)[200.0])
        assertEquals(200.0, OiCalculator.strikeStats(chain, Side.CE).poc!!, 0.0)
        assertEquals(300.0, OiCalculator.strikeStats(chain, Side.PE).poc!!, 0.0)
        assertEquals(300.0, OiCalculator.strikeStats(chain, Side.COMBINED).poc!!, 0.0)
    }

    @Test fun premiumBinsAndProfile() {
        val obs = listOf(127.0 to 100L, 129.0 to 50L, 131.0 to 20L, 60.0 to 10L, 62.0 to 10L, 0.0 to 99L)
        val ps = OiCalculator.premiumProfile(obs, 5.0, 0.7)
        assertEquals(190L, ps.stats.total)
        assertEquals(125.0, ps.stats.poc!!, 0.0)
        assertEquals(125.0, ps.highNodes.first(), 0.0)
        assertNotNull(ps.stats.va)
    }

    @Test fun pcrHandlesZeroCe() {
        assertNull(OiCalculator.pcr(100, 0))
        assertEquals(2.0, OiCalculator.pcr(200, 100)!!, 1e-9)
    }

    @Test fun pcrAroundAtmAndRange() {
        assertEquals(1.0, OiCalculator.pcrRange(chain, 400.0, 400.0)!!, 1e-9)
        assertEquals(165.0 / 125.0, OiCalculator.pcrAround(chain, 200.0, 10)!!, 1e-9)
        assertEquals((50.0 + 20 + 90) / (10 + 80 + 30), OiCalculator.pcrAround(chain, 200.0, 1)!!, 1e-9)
    }

    @Test fun oiChangePctHandlesZeroPrev() {
        assertNull(OiCalculator.oiChangePct(100, 0))
        assertEquals(50.0, OiCalculator.oiChangePct(150, 100)!!, 1e-9)
    }

    @Test fun summaryRanksAndTotals() {
        val s = OiCalculator.summarize(210.0, chain, topN = 2)
        assertEquals(200.0, s.atmStrike, 0.0)
        assertEquals(125L, s.totalCeOi); assertEquals(165L, s.totalPeOi)
        assertEquals(165.0 / 125.0, s.pcrFull!!, 1e-9)
        assertEquals(listOf(200.0, 300.0), s.topCeOi.map { it.strike })
        assertEquals(listOf(300.0, 100.0), s.topPeOi.map { it.strike })
        assertEquals(200.0, s.topCeAdd.first().strike, 0.0)
        assertEquals(300.0, s.topPeAdd.first().strike, 0.0)
        assertEquals(300.0, s.combinedPoc!!, 0.0)
    }

    @Test fun additionRankingExcludesUnwinding() {
        val s = OiCalculator.summarize(100.0, listOf(row(100.0, 5, 0, 50, 0)))
        assertTrue(s.topCeAdd.isEmpty())
    }

    @Test fun changeProfileSeparatesAdditionAndUnwinding() {
        val cp = OiCalculator.changeProfile(chain, Side.CE)
        assertEquals(65L, cp.totalAdded)       // +5 +60
        assertEquals(5L, cp.totalUnwound)      // strike 400
        assertEquals(60L, cp.net)
        assertEquals(200.0, cp.topAddition!!.strike, 0.0)
        assertEquals(400.0, cp.topReduction!!.strike, 0.0)
        assertEquals(200.0, cp.developingPoc!!, 0.0)
        assertFalse(cp.additions.containsKey(400.0))
    }

    @Test fun highOiLevelsWithPct() {
        val h = OiCalculator.highOi(chain, 3)
        assertEquals(listOf(200.0, 300.0, 100.0), h.ceTop.map { it.strike })
        assertEquals(300.0, h.peTop.first().strike, 0.0)
        assertEquals(300.0, h.ceAddPct[200.0]!!, 1e-9)   // (80-20)/20 = 300%
        assertEquals(100.0, h.ceAddPct[100.0]!!, 1e-9)   // (10-5)/5 = 100%
        assertEquals(300.0, OiCalculator.changeProfile(chain, Side.COMBINED).developingPoc!!, 0.0)
    }

    @Test fun priorSnapshotChange() {
        val prior = listOf(row(100.0, 8, 45), row(200.0, 70, 20))
        val cur = OiCalculator.withPrior(chain, prior)
        assertEquals(2L, cur.first { it.strike == 100.0 }.ceChange)
        assertEquals(10L, cur.first { it.strike == 200.0 }.ceChange)
        assertEquals(chain.first { it.strike == 300.0 }.ceChange, cur.first { it.strike == 300.0 }.ceChange) // unchanged when absent
    }

    @Test fun heatmapDiffsAgainstPreviousSnapshot() {
        val s1 = 1L to listOf(row(100.0, 10, 10), row(200.0, 20, 20))
        val s2 = 2L to listOf(row(100.0, 15, 10), row(200.0, 10, 25))
        val hm = OiCalculator.heatmap(listOf(s1, s2), Side.CE, listOf(100.0, 200.0))
        assertEquals(listOf(0L, 5L), hm.values[0])
        assertEquals(listOf(0L, -10L), hm.values[1])
        val comb = OiCalculator.heatmap(listOf(s1, s2), Side.COMBINED, listOf(200.0))
        assertEquals(listOf(0L, -5L), comb.values[0])
    }

    @Test fun developingPocSeriesFollowsAdditions() {
        val s1 = 1L to listOf(row(100.0, 10, 0), row(200.0, 10, 0))
        val s2 = 2L to listOf(row(100.0, 50, 0), row(200.0, 12, 0))
        val s3 = 3L to listOf(row(100.0, 50, 0), row(200.0, 100, 0))
        val ser = OiCalculator.developingPocSeries(listOf(s1, s2, s3), Side.CE)
        assertNull(ser[0].second)
        assertEquals(100.0, ser[1].second!!, 0.0)
        assertEquals(200.0, ser[2].second!!, 0.0)
    }

    @Test fun limitAroundAtm() {
        val lim = OiCalculator.limitAroundAtm(chain, 210.0, 1)
        assertEquals(listOf(100.0, 200.0, 300.0), lim.map { it.strike })
        assertEquals(4, OiCalculator.limitAroundAtm(chain, 210.0, 0).size)
    }
}
