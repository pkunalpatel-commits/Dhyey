package com.niftyoi.dhan.domain.calculations

import com.niftyoi.dhan.domain.models.*
import kotlin.math.abs
import kotlin.math.floor

object OiCalculator {

    // ---------- binning / aggregation ----------

    /** Bin = floor(price / size) * size. */
    fun bin(price: Double, size: Double): Double {
        require(size > 0) { "bin size must be positive" }
        val b = floor(price / size + 1e-9) * size
        return Math.round(b * 1_000_000.0) / 1_000_000.0
    }

    /** Aggregate (price, oi) observations into bins (sum of OI weights per bin). */
    fun aggregate(obs: List<Pair<Double, Long>>, binSize: Double): Map<Double, Long> {
        val out = sortedMapOf<Double, Long>()
        for ((p, oi) in obs) {
            val b = bin(p, binSize)
            out[b] = (out[b] ?: 0L) + oi
        }
        return out
    }

    fun poc(profile: Map<Double, Long>): Double? =
        profile.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key

    /**
     * Value area: start at POC, repeatedly add the higher-weight adjacent bin
     * until cumulative >= pct of total. Ties go to the upper bin.
     */
    fun valueArea(profile: Map<Double, Long>, pct: Double = 0.70): ValueArea? {
        val keys = profile.keys.sorted()
        if (keys.isEmpty()) return null
        val total = profile.values.sum()
        if (total <= 0) return null
        val pocKey = poc(profile) ?: return null
        var lo = keys.indexOf(pocKey)
        var hi = lo
        var cum = profile.getValue(pocKey)
        val target = total * pct
        while (cum < target && (lo > 0 || hi < keys.lastIndex)) {
            val up = if (hi < keys.lastIndex) profile.getValue(keys[hi + 1]) else -1L
            val down = if (lo > 0) profile.getValue(keys[lo - 1]) else -1L
            if (up >= down) { hi++; cum += up } else { lo--; cum += down }
        }
        return ValueArea(keys[lo], keys[hi])
    }

    fun stats(profile: Map<Double, Long>, pct: Double = 0.70): ProfileStats =
        ProfileStats(profile, profile.values.sum(), poc(profile), valueArea(profile, pct))

    // ---------- futures profile ----------

    /** Snapshot-observation-weighted futures OI profile: each (price, OI) observation adds its OI to its price bin. */
    fun futuresProfile(obs: List<Pair<Double, Long>>, binSize: Double, pct: Double = 0.70): ProfileStats =
        stats(aggregate(obs.filter { it.first > 0 && it.second > 0 }, binSize), pct)

    // ---------- strike profiles ----------

    fun oiOf(r: StrikeRow, side: Side): Long = when (side) {
        Side.CE -> r.ceOi; Side.PE -> r.peOi; Side.COMBINED -> r.ceOi + r.peOi
    }

    fun changeOf(r: StrikeRow, side: Side): Long = when (side) {
        Side.CE -> r.ceChange; Side.PE -> r.peChange; Side.COMBINED -> r.ceChange + r.peChange
    }

    fun strikeProfile(rows: List<StrikeRow>, side: Side): Map<Double, Long> {
        val m = sortedMapOf<Double, Long>()
        for (r in rows) m[r.strike] = oiOf(r, side)
        return m
    }

    fun strikeStats(rows: List<StrikeRow>, side: Side, pct: Double = 0.70) = stats(strikeProfile(rows, side), pct)

    // ---------- premium profile ----------

    /**
     * Premium-wise OI profile. Observations are (option LTP, OI). This is an observation-weighted
     * profile, NOT historical opening transactions at each premium.
     * High node: weight >= 1.5 x mean of non-empty bins. Low node: bin inside the value area with weight <= 0.5 x mean.
     */
    fun premiumProfile(obs: List<Pair<Double, Long>>, binSize: Double, pct: Double = 0.70): PremiumStats {
        val agg = aggregate(obs.filter { it.first > 0 && it.second > 0 }, binSize)
        val st = stats(agg, pct)
        if (agg.isEmpty()) return PremiumStats(st, emptyList(), emptyList())
        val mean = agg.values.average()
        val hvn = agg.filter { it.value >= 1.5 * mean }.keys.sorted()
        val va = st.va
        val lvn = if (va == null) emptyList()
        else agg.filter { it.key >= va.low && it.key <= va.vah && it.value <= 0.5 * mean }.keys.sorted()
        return PremiumStats(st, hvn, lvn)
    }

    /** (premium, OI) observations from one chain for one side. */
    fun premiumObservations(rows: List<StrikeRow>, side: Side): List<Pair<Double, Long>> = rows.flatMap { r ->
        when (side) {
            Side.CE -> listOf(r.ceLtp to r.ceOi)
            Side.PE -> listOf(r.peLtp to r.peOi)
            Side.COMBINED -> listOf(r.ceLtp to r.ceOi, r.peLtp to r.peOi)
        }
    }

    // ---------- PCR ----------

    fun pcr(totalPe: Long, totalCe: Long): Double? =
        if (totalCe <= 0L) null else totalPe.toDouble() / totalCe.toDouble()

    fun atmStrike(spot: Double, rows: List<StrikeRow>): Double =
        rows.minByOrNull { abs(it.strike - spot) }?.strike ?: 0.0

    fun pcrAround(rows: List<StrikeRow>, atm: Double, n: Int): Double? {
        val sorted = rows.sortedBy { it.strike }
        val idx = sorted.indexOfFirst { it.strike == atm }
        if (idx < 0) return null
        val sub = sorted.subList(maxOf(0, idx - n), minOf(sorted.size, idx + n + 1))
        return pcr(sub.sumOf { it.peOi }, sub.sumOf { it.ceOi })
    }

    fun pcrRange(rows: List<StrikeRow>, from: Double, to: Double): Double? {
        val sub = rows.filter { it.strike in from..to }
        return pcr(sub.sumOf { it.peOi }, sub.sumOf { it.ceOi })
    }

    /** Keep only ATM +/- n strikes (n <= 0 means whole chain). */
    fun limitAroundAtm(rows: List<StrikeRow>, spot: Double, n: Int): List<StrikeRow> {
        if (n <= 0) return rows
        val sorted = rows.sortedBy { it.strike }
        val atm = atmStrike(spot, sorted)
        val idx = sorted.indexOfFirst { it.strike == atm }
        if (idx < 0) return sorted
        return sorted.subList(maxOf(0, idx - n), minOf(sorted.size, idx + n + 1))
    }

    // ---------- ranking ----------

    fun topBy(rows: List<StrikeRow>, n: Int, selector: (StrikeRow) -> Long): List<LevelOi> =
        rows.map { LevelOi(it.strike, selector(it)) }
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(n)

    /** (current - previous) / previous * 100; null when previous is zero. */
    fun oiChangePct(current: Long, previous: Long): Double? =
        if (previous <= 0L) null else (current - previous).toDouble() / previous * 100.0

    fun highOi(rows: List<StrikeRow>, topN: Int = 3, vaPct: Double = 0.70): HighOiLevels {
        val ceAdd = topBy(rows, topN) { it.ceChange }
        val peAdd = topBy(rows, topN) { it.peChange }
        val byStrike = rows.associateBy { it.strike }
        return HighOiLevels(
            ceTop = topBy(rows, topN) { it.ceOi },
            peTop = topBy(rows, topN) { it.peOi },
            ceAdd = ceAdd, peAdd = peAdd,
            ceAddPct = ceAdd.associate { it.strike to byStrike[it.strike]?.let { r -> oiChangePct(r.ceOi, r.cePrevOi) } },
            peAddPct = peAdd.associate { it.strike to byStrike[it.strike]?.let { r -> oiChangePct(r.peOi, r.pePrevOi) } },
            cePoc = poc(strikeProfile(rows, Side.CE)),
            pePoc = poc(strikeProfile(rows, Side.PE)),
            combinedPoc = poc(strikeProfile(rows, Side.COMBINED)),
            ceVa = valueArea(strikeProfile(rows, Side.CE), vaPct),
            peVa = valueArea(strikeProfile(rows, Side.PE), vaPct),
            combinedVa = valueArea(strikeProfile(rows, Side.COMBINED), vaPct),
        )
    }

    // ---------- OI change ----------

    /** Replace "previous OI" with the OI observed in an earlier snapshot (for 1/3/5/15/30-minute change). */
    fun withPrior(cur: List<StrikeRow>, prior: List<StrikeRow>): List<StrikeRow> {
        val p = prior.associateBy { it.strike }
        return cur.map { c -> p[c.strike]?.let { c.copy(cePrevOi = it.ceOi, pePrevOi = it.peOi) } ?: c }
    }

    fun changeProfile(rows: List<StrikeRow>, side: Side): ChangeProfile {
        val add = sortedMapOf<Double, Long>()
        val unw = sortedMapOf<Double, Long>()
        for (r in rows) {
            val c = changeOf(r, side)
            if (c > 0) add[r.strike] = c else if (c < 0) unw[r.strike] = -c
        }
        val totalAdd = add.values.sum()
        val totalUnw = unw.values.sum()
        return ChangeProfile(
            additions = add, unwinding = unw,
            totalAdded = totalAdd, totalUnwound = totalUnw, net = totalAdd - totalUnw,
            topAddition = add.maxByOrNull { it.value }?.let { LevelOi(it.key, it.value) },
            topReduction = unw.maxByOrNull { it.value }?.let { LevelOi(it.key, -it.value) },
            developingPoc = poc(add), // positive additions only; unwinding is never mixed in
        )
    }

    /** Developing OI POC: for each snapshot, POC of positive OI additions since the first snapshot. */
    fun developingPocSeries(snaps: List<Pair<Long, List<StrikeRow>>>, side: Side): List<Pair<Long, Double?>> {
        if (snaps.isEmpty()) return emptyList()
        val base = snaps.first().second
        return snaps.map { (t, rows) -> t to changeProfile(withPrior(rows, base), side).developingPoc }
    }

    /** Time x strike matrix of OI change versus the previous snapshot (first column = 0). */
    fun heatmap(snaps: List<Pair<Long, List<StrikeRow>>>, side: Side, strikes: List<Double>): Heatmap {
        val oi = snaps.map { (_, rows) -> rows.associate { it.strike to oiOf(it, side) } }
        val values = strikes.map { k ->
            oi.mapIndexed { i, m ->
                if (i == 0) 0L else (m[k] ?: 0L) - (oi[i - 1][k] ?: 0L)
            }
        }
        return Heatmap(snaps.map { it.first }, strikes, values)
    }

    // ---------- summary ----------

    fun summarize(spot: Double, rows: List<StrikeRow>, topN: Int = 3, vaPct: Double = 0.70): OiSummary {
        val atm = atmStrike(spot, rows)
        val totalCe = rows.sumOf { it.ceOi }
        val totalPe = rows.sumOf { it.peOi }
        val combined = strikeProfile(rows, Side.COMBINED)
        return OiSummary(
            atmStrike = atm,
            totalCeOi = totalCe,
            totalPeOi = totalPe,
            pcrFull = pcr(totalPe, totalCe),
            pcrAtm5 = pcrAround(rows, atm, 5),
            pcrAtm10 = pcrAround(rows, atm, 10),
            topCeOi = topBy(rows, topN) { it.ceOi },
            topPeOi = topBy(rows, topN) { it.peOi },
            topCeAdd = topBy(rows, topN) { it.ceChange },
            topPeAdd = topBy(rows, topN) { it.peChange },
            ceChangeTotal = rows.sumOf { it.ceChange },
            peChangeTotal = rows.sumOf { it.peChange },
            combinedPoc = poc(combined),
            combinedVa = valueArea(combined, vaPct),
        )
    }
}
