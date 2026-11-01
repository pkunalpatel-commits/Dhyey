package com.niftyoi.dhan.presentation.scanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niftyoi.dhan.domain.calculations.OiCalculator
import com.niftyoi.dhan.domain.models.LevelOi
import com.niftyoi.dhan.presentation.AppState
import com.niftyoi.dhan.presentation.common.*

@Composable
fun ScannerScreen(s: AppState) {
    val top = s.settings.topN
    val h = OiCalculator.highOi(s.rows, top, s.settings.vaPct / 100.0)
    val byStrike = s.rows.associateBy { it.strike }
    fun lines(l: List<LevelOi>, ce: Boolean, pct: Boolean) = if (l.isEmpty()) "—" else l.mapIndexed { i, x ->
        val r = byStrike[x.strike]
        val p = r?.let { if (ce) OiCalculator.oiChangePct(it.ceOi, it.cePrevOi) else OiCalculator.oiChangePct(it.peOi, it.pePrevOi) }
        "#${i + 1}  ${k(x.strike)}  ${n(x.value)}" + if (pct) "  (${p?.let { d(it, 1) + "%" } ?: "new"})" else ""
    }.joinToString("\n")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Note("Top $top shown (change in Settings: 3 / 5 / 10). Addition = OI change in the selected window; % shows 'new' when previous OI was zero.")
        s.windowNote?.let { Note(it) }
        Stat("Highest CE OI strikes", lines(h.ceTop, true, false))
        Stat("Highest PE OI strikes", lines(h.peTop, false, false))
        Stat("Top CE OI additions", lines(h.ceAdd, true, true))
        Stat("Top PE OI additions", lines(h.peAdd, false, true))
        Stat("OI POC · CE POC · PE POC", "${d(h.combinedPoc, 0)} · ${d(h.cePoc, 0)} · ${d(h.pePoc, 0)}")
        Stat("Combined VAL – VAH", h.combinedVa?.let { "${d(it.low, 0)} – ${d(it.vah, 0)}" } ?: "—")
    }
}
