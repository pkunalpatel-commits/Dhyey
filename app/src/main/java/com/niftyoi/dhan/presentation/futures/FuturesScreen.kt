package com.niftyoi.dhan.presentation.futures

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niftyoi.dhan.domain.calculations.OiCalculator
import com.niftyoi.dhan.presentation.AppState
import com.niftyoi.dhan.presentation.common.*

@Composable
fun FuturesScreen(s: AppState) {
    val st = OiCalculator.futuresProfile(s.fut.obs, s.settings.priceBin, s.settings.vaPct / 100.0)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("Futures ${s.fut.symbol ?: ""}", "LTP ${d(s.fut.ltp)} · OI ${n(s.fut.oi)}")
        Stat("OI POC / VAL / VAH", "${st.poc?.let { k(it) } ?: "—"} / ${st.va?.let { k(it.low) } ?: "—"} / ${st.va?.let { k(it.vah) } ?: "—"}")
        Stat("Total OI weight · observations", "${n(st.total)} · ${s.fut.obs.size}")
        Note("Snapshot-observation-weighted profile: each stored (futures price, OI) snapshot adds its OI to its price bin (bin ${s.settings.priceBin.toInt()} pts, value area ${s.settings.vaPct}%). It is NOT exchange-reported OI at each traded price and does not count unique contracts opened per price.")
        if (st.profile.isEmpty()) Note("No futures snapshots yet. Refresh a few times during market hours to build the profile.")
        else ProfileBars(st.profile, st.poc, st.va)
    }
}
