package com.niftyoi.dhan.presentation.optionprofile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niftyoi.dhan.domain.calculations.OiCalculator
import com.niftyoi.dhan.domain.models.Side
import com.niftyoi.dhan.presentation.AppState
import com.niftyoi.dhan.presentation.common.*

@Composable
fun OptionProfileScreen(s: AppState) {
    var mode by remember { mutableIntStateOf(0) }   // 0 strike, 1 premium, 2 change
    var side by remember { mutableStateOf(Side.COMBINED) }
    val set = s.settings
    val view = OiCalculator.limitAroundAtm(s.rows, s.spot ?: 0.0, set.strikeRange)
    val atm = OiCalculator.atmStrike(s.spot ?: 0.0, s.rows)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Strike OI", "Premium OI", "OI Change").forEachIndexed { i, t -> FilterChip(mode == i, { mode = i }, { Text(t) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Side.values().forEach { sd -> FilterChip(side == sd, { side = sd }, { Text(if (sd == Side.COMBINED) "CE+PE" else sd.name) }) }
        }
        s.windowNote?.let { Note(it) }
        if (s.rows.isEmpty()) { Note("No option chain loaded yet."); return@Column }
        when (mode) {
            0 -> {
                val st = OiCalculator.strikeStats(view, side, set.vaPct / 100.0)
                Stat("POC / VAL / VAH", "${st.poc?.let { k(it) } ?: "—"} / ${st.va?.let { k(it.low) } ?: "—"} / ${st.va?.let { k(it.vah) } ?: "—"}")
                Stat("Total OI in view", n(st.total))
                Note("Strikes shown: ATM ±${set.strikeRange} (0 = full chain). Current OI per strike; value area ${set.vaPct}%.")
                ProfileBars(st.profile, st.poc, st.va, atm = atm)
            }
            1 -> {
                val obs = OiCalculator.premiumObservations(s.rows, side).filter { (p, _) -> p >= set.premiumMin && (set.premiumMax <= 0 || p <= set.premiumMax) }
                val ps = OiCalculator.premiumProfile(obs, set.premiumBin, set.vaPct / 100.0)
                Stat("Premium OI POC / VAL / VAH", "${d(ps.stats.poc, 0)} / ${d(ps.stats.va?.low, 0)} / ${d(ps.stats.va?.vah, 0)}")
                Stat("High nodes", ps.highNodes.joinToString { d(it, 0) }.ifBlank { "—" })
                Stat("Low nodes (inside value area)", ps.lowNodes.joinToString { d(it, 0) }.ifBlank { "—" })
                Note("Observation-weighted: each strike's (option LTP, OI) from the current chain is binned by premium (₹${set.premiumBin.toInt()} bins). Not historical opening transactions at each premium.")
                ProfileBars(ps.stats.profile, ps.stats.poc, ps.stats.va, label = { "₹" + d(it, 0) })
            }
            else -> {
                val cp = OiCalculator.changeProfile(view, side)
                val win = if (s.changeWindowMin == 0) "vs previous close" else "vs ${s.changeWindowMin}m ago"
                Stat("OI change ($win)", "Added ${n(cp.totalAdded)} · Unwound ${n(cp.totalUnwound)} · Net ${n(cp.net)}")
                Stat("Highest addition / reduction", "${cp.topAddition?.let { k(it.strike) + " (" + n(it.value) + ")" } ?: "—"} / ${cp.topReduction?.let { k(it.strike) + " (" + n(it.value) + ")" } ?: "—"}")
                Stat("Developing OI POC (additions only)", d(cp.developingPoc, 0))
                Note("Change window is chosen in the header (prev close, 1/3/5/15/30 min from stored snapshots). Additions and unwinding are separate profiles; unwinding is never mixed into the POC.")
                Text("OI additions")
                ProfileBars(cp.additions, cp.developingPoc, null, color = Green, atm = atm)
                Text("OI unwinding")
                ProfileBars(cp.unwinding, null, null, color = Red, atm = atm)
            }
        }
    }
}
