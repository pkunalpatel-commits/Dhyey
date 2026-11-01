package com.niftyoi.dhan.presentation.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niftyoi.dhan.presentation.AppState
import com.niftyoi.dhan.presentation.common.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(s: AppState) {
    val sum = s.summary
    val f = s.fut
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        s.error?.let { item { Note("⚠ $it") } }
        s.windowNote?.let { item { Note(it) } }
        item { Stat("NIFTY Spot", d(s.spot)) }
        item { Stat("NIFTY Futures" + (f.symbol?.let { " ($it)" } ?: ""), d(f.ltp)) }
        item { Stat("Futures OI", n(f.oi)) }
        item { Stat("Futures OI change (${f.changeBase.ifBlank { "n/a" }})", n(f.oiChange) + (f.priceChange?.let { "   price Δ ${d(it)}" } ?: "")) }
        item { Stat("Market interpretation (futures)", (s.futSignal ?: "—") + "\nAnalytical indication only, not a prediction.") }
        item { Stat("Total CE OI", n(sum?.totalCeOi)) }
        item { Stat("Total PE OI", n(sum?.totalPeOi)) }
        item { Stat("PCR full / ATM±5 / ATM±10", "${d(sum?.pcrFull)} / ${d(sum?.pcrAtm5)} / ${d(sum?.pcrAtm10)}") }
        item { Stat("ATM strike", d(sum?.atmStrike, 0)) }
        item { Stat("Highest CE OI strike", levels(sum?.topCeOi.orEmpty().take(1))) }
        item { Stat("Highest PE OI strike", levels(sum?.topPeOi.orEmpty().take(1))) }
        item { Stat("Highest CE OI addition", levels(sum?.topCeAdd.orEmpty().take(1))) }
        item { Stat("Highest PE OI addition", levels(sum?.topPeAdd.orEmpty().take(1))) }
        item { Stat("Combined OI POC", d(sum?.combinedPoc, 0)) }
        item { Stat("Combined OI VAL – VAH", sum?.combinedVa?.let { "${d(it.low, 0)} – ${d(it.vah, 0)}" } ?: "—") }
        item { Stat("Dhan connection", (if (s.connected) "REST ok" else "REST not connected") + " · feed ${s.feed.state}" + (s.feed.detail.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")) }
        item { Stat("Last chain update", s.lastChainMs?.let { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)) } ?: "—") }
        item { Stat("Snapshots stored (all)", s.snapshotsTotal.toString()) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
