package com.niftyoi.dhan.domain.calculations

import com.niftyoi.dhan.domain.models.*

/** A horizontal level. Kind drives colour in the chart (ce, pe, poc, vah, val, add, hvn, lvn). */
data class ChartLevel(val label: String, val value: Double, val kind: String)

data class StripRow(val label: String, val strike: Double, val oi: Long, val change: Long, val pct: Double?)

/**
 * Coordinate rule: strike-based levels live in UNDERLYING price space and go only to the underlying panel
 * (and the reference strip). Premium-based levels live in OPTION PREMIUM space and go only to the
 * option-premium panel. The two lists have different types of origin and are never merged.
 */
data class ChartLevels(
    val underlying: List<ChartLevel>,
    val premium: List<ChartLevel>,
    val strip: List<StripRow>,
) {
    fun toJson(): String {
        fun lv(l: ChartLevel) = "{\"label\":${q(l.label)},\"value\":${l.value},\"kind\":${q(l.kind)}}"
        fun sr(s: StripRow) = "{\"label\":${q(s.label)},\"strike\":${s.strike},\"oi\":${s.oi},\"change\":${s.change},\"pct\":${s.pct ?: "null"}}"
        return "{\"underlying\":[${underlying.joinToString(",") { lv(it) }}]," +
            "\"premium\":[${premium.joinToString(",") { lv(it) }}]," +
            "\"strip\":[${strip.joinToString(",") { sr(it) }}]}"
    }

    companion object {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

object ChartLevelBuilder {
    private fun k(v: Double) = v.toLong().toString()

    fun build(
        rows: List<StrikeRow>,
        premiumObs: List<Pair<Double, Long>>,
        selectedSide: Side,           // CE or PE
        topN: Int,
        premiumBin: Double,
        vaPct: Double,
        frozen: ChartLevels? = null,  // previously frozen levels, kept for comparison
    ): ChartLevels {
        val h = OiCalculator.highOi(rows, topN, vaPct)
        val byStrike = rows.associateBy { it.strike }
        val under = mutableListOf<ChartLevel>()
        h.ceTop.forEachIndexed { i, l -> under += ChartLevel("CE OI #${i + 1} ${k(l.strike)}", l.strike, "ce") }
        h.peTop.forEachIndexed { i, l -> under += ChartLevel("PE OI #${i + 1} ${k(l.strike)}", l.strike, "pe") }
        h.ceAdd.forEachIndexed { i, l -> under += ChartLevel("CE add #${i + 1} ${k(l.strike)}", l.strike, "add") }
        h.peAdd.forEachIndexed { i, l -> under += ChartLevel("PE add #${i + 1} ${k(l.strike)}", l.strike, "add") }
        h.combinedPoc?.let { under += ChartLevel("OI POC ${k(it)}", it, "poc") }
        h.cePoc?.let { under += ChartLevel("CE POC ${k(it)}", it, "ce") }
        h.pePoc?.let { under += ChartLevel("PE POC ${k(it)}", it, "pe") }
        h.combinedVa?.let {
            under += ChartLevel("OI VAH ${k(it.vah)}", it.vah, "vah")
            under += ChartLevel("OI VAL ${k(it.low)}", it.low, "val")
        }

        val prem = mutableListOf<ChartLevel>()
        val ps = OiCalculator.premiumProfile(premiumObs, premiumBin, vaPct)
        ps.stats.poc?.let { prem += ChartLevel("Prem POC ${it}", it, "poc") }
        ps.stats.va?.let {
            prem += ChartLevel("Prem VAH ${it.vah}", it.vah, "vah")
            prem += ChartLevel("Prem VAL ${it.low}", it.low, "val")
        }
        ps.highNodes.take(5).forEach { prem += ChartLevel("HVN $it", it, "hvn") }
        ps.lowNodes.take(5).forEach { prem += ChartLevel("LVN $it", it, "lvn") }

        val strip = mutableListOf<StripRow>()
        fun add(label: String, l: LevelOi?, ce: Boolean) {
            l ?: return
            val r = byStrike[l.strike] ?: return
            val oi = if (ce) r.ceOi else r.peOi
            val ch = if (ce) r.ceChange else r.peChange
            val prev = if (ce) r.cePrevOi else r.pePrevOi
            strip += StripRow(label, l.strike, oi, ch, OiCalculator.oiChangePct(oi, prev))
        }
        add("Highest CE OI", h.ceTop.getOrNull(0), true)
        add("2nd CE OI", h.ceTop.getOrNull(1), true)
        add("3rd CE OI", h.ceTop.getOrNull(2), true)
        add("Highest PE OI", h.peTop.getOrNull(0), false)
        add("2nd PE OI", h.peTop.getOrNull(1), false)
        add("3rd PE OI", h.peTop.getOrNull(2), false)
        add("Top CE addition", h.ceAdd.getOrNull(0), true)
        add("Top PE addition", h.peAdd.getOrNull(0), false)

        // Frozen (previous) levels are carried over with a "prev" marker for comparison.
        val frozenUnder = frozen?.underlying?.map { it.copy(label = "prev " + it.label.removePrefix("prev "), kind = "prev") } ?: emptyList()
        return ChartLevels(under + frozenUnder, prem, strip)
    }
}
