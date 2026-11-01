package com.niftyoi.dhan.data.dhan

import android.content.Context

/** Non-secret preferences. Credentials live in CredentialStore. */
data class AppSettings(
    val strikeRange: Int = 15,            // ATM +/- N strikes for profile views (0 = full chain)
    val premiumMin: Double = 0.0,
    val premiumMax: Double = 0.0,         // 0 = no cap
    val priceBin: Double = 25.0,
    val premiumBin: Double = 5.0,
    val vaPct: Int = 70,
    val snapshotSec: Int = 60,
    val chartTf: Int = 5,
    val topN: Int = 3,
    val autoRefresh: Boolean = false,
    val darkChart: Boolean = true,
)

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun load() = AppSettings(
        strikeRange = p.getInt("strikeRange", 15),
        premiumMin = p.getFloat("premiumMin", 0f).toDouble(),
        premiumMax = p.getFloat("premiumMax", 0f).toDouble(),
        priceBin = p.getFloat("priceBin", 25f).toDouble(),
        premiumBin = p.getFloat("premiumBin", 5f).toDouble(),
        vaPct = p.getInt("vaPct", 70),
        snapshotSec = p.getInt("snapshotSec", 60),
        chartTf = p.getInt("chartTf", 5),
        topN = p.getInt("topN", 3),
        autoRefresh = p.getBoolean("autoRefresh", false),
        darkChart = p.getBoolean("darkChart", true),
    )

    fun save(s: AppSettings) = p.edit()
        .putInt("strikeRange", s.strikeRange)
        .putFloat("premiumMin", s.premiumMin.toFloat()).putFloat("premiumMax", s.premiumMax.toFloat())
        .putFloat("priceBin", s.priceBin.toFloat()).putFloat("premiumBin", s.premiumBin.toFloat())
        .putInt("vaPct", s.vaPct).putInt("snapshotSec", s.snapshotSec).putInt("chartTf", s.chartTf)
        .putInt("topN", s.topN).putBoolean("autoRefresh", s.autoRefresh).putBoolean("darkChart", s.darkChart)
        .apply()
}
