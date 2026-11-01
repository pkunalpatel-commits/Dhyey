package com.niftyoi.dhan.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niftyoi.dhan.domain.calculations.OiCalculator
import com.niftyoi.dhan.domain.models.Side
import com.niftyoi.dhan.presentation.AppState
import com.niftyoi.dhan.presentation.common.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(s: AppState) {
    val hist = s.history
    var idx by remember { mutableFloatStateOf(0f) }
    var side by remember { mutableStateOf(Side.COMBINED) }
    val f = SimpleDateFormat("HH:mm:ss", Locale.US)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("Snapshots today (this expiry) / stored total", "${hist.size} / ${s.snapshotsTotal}")
        if (hist.isEmpty()) { Note("No snapshots yet."); return@Column }
        val i = idx.toInt().coerceIn(0, hist.lastIndex)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Side.values().forEach { sd -> FilterChip(side == sd, { side = sd }, { Text(if (sd == Side.COMBINED) "CE+PE" else sd.name) }) }
        }
        Text("Replay snapshot ${i + 1}/${hist.size} · ${f.format(Date(hist[i].first))}")
        Slider(value = idx, onValueChange = { idx = it }, valueRange = 0f..(hist.size - 1).coerceAtLeast(1).toFloat())
        val rows = OiCalculator.limitAroundAtm(hist[i].second, s.spot ?: 0.0, s.settings.strikeRange)
        val st = OiCalculator.strikeStats(rows, side, s.settings.vaPct / 100.0)
        Stat("POC / VAL / VAH at this snapshot", "${d(st.poc, 0)} / ${d(st.va?.low, 0)} / ${d(st.va?.vah, 0)}")
        ProfileBars(st.profile, st.poc, st.va)
        val dev = OiCalculator.developingPocSeries(hist, side).takeLast(12)
        Stat("Developing OI POC (additions since first snapshot)", dev.joinToString("\n") { "${f.format(Date(it.first))}  ${d(it.second, 0)}" })
    }
}
