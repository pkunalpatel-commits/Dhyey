package com.niftyoi.dhan.domain.models

/** One strike row of the option chain (CE + PE). Extra fields default so tests stay compact. */
data class StrikeRow(
    val strike: Double,
    val ceLtp: Double,
    val ceOi: Long,
    val cePrevOi: Long,
    val peLtp: Double,
    val peOi: Long,
    val pePrevOi: Long,
    val ceVolume: Long = 0, val peVolume: Long = 0,
    val ceIv: Double = 0.0, val peIv: Double = 0.0,
    val ceDelta: Double = 0.0, val peDelta: Double = 0.0,
    val ceGamma: Double = 0.0, val peGamma: Double = 0.0,
    val ceTheta: Double = 0.0, val peTheta: Double = 0.0,
    val ceVega: Double = 0.0, val peVega: Double = 0.0,
    val ceSecId: String? = null, val peSecId: String? = null,
) {
    val ceChange: Long get() = ceOi - cePrevOi
    val peChange: Long get() = peOi - pePrevOi
}

data class OptionChain(val underlyingLtp: Double, val rows: List<StrikeRow>)

data class FuturesQuote(val ltp: Double, val oi: Long?)

data class LevelOi(val strike: Double, val value: Long)

data class ValueArea(val low: Double, val vah: Double)

enum class Side { CE, PE, COMBINED }

data class OiSummary(
    val atmStrike: Double,
    val totalCeOi: Long,
    val totalPeOi: Long,
    val pcrFull: Double?,
    val pcrAtm5: Double?,
    val pcrAtm10: Double?,
    val topCeOi: List<LevelOi>,
    val topPeOi: List<LevelOi>,
    val topCeAdd: List<LevelOi>,
    val topPeAdd: List<LevelOi>,
    val ceChangeTotal: Long,
    val peChangeTotal: Long,
    val combinedPoc: Double?,
    val combinedVa: ValueArea?,
)

data class ProfileStats(
    val profile: Map<Double, Long>,
    val total: Long,
    val poc: Double?,
    val va: ValueArea?,
)

data class PremiumStats(
    val stats: ProfileStats,
    val highNodes: List<Double>,
    val lowNodes: List<Double>,
)

data class ChangeProfile(
    val additions: Map<Double, Long>,
    val unwinding: Map<Double, Long>,   // magnitudes (positive numbers)
    val totalAdded: Long,
    val totalUnwound: Long,
    val net: Long,
    val topAddition: LevelOi?,
    val topReduction: LevelOi?,
    val developingPoc: Double?,
)

data class Heatmap(val times: List<Long>, val strikes: List<Double>, val values: List<List<Long>>) // [strike][time]

data class HighOiLevels(
    val ceTop: List<LevelOi>,
    val peTop: List<LevelOi>,
    val ceAdd: List<LevelOi>,
    val peAdd: List<LevelOi>,
    val ceAddPct: Map<Double, Double?>,
    val peAddPct: Map<Double, Double?>,
    val cePoc: Double?,
    val pePoc: Double?,
    val combinedPoc: Double?,
    val ceVa: ValueArea?,
    val peVa: ValueArea?,
    val combinedVa: ValueArea?,
)

/** time = epoch seconds (UTC). */
data class Candle(
    val time: Long,
    val open: Double, val high: Double, val low: Double, val close: Double,
    val volume: Long = 0, val oi: Long? = null,
)

enum class Signal { LONG_BUILDUP, SHORT_BUILDUP, SHORT_COVERING, LONG_UNWINDING, NEUTRAL }

data class FeedTick(
    val code: Int,
    val segment: Int,
    val securityId: Long,
    val ltp: Double? = null,
    val oi: Long? = null,
    val volume: Long? = null,
    val ltt: Long? = null,
    val prevClose: Double? = null,
    val prevOi: Long? = null,
    val disconnectReason: Int? = null,
)
