package com.niftyoi.dhan.presentation.heatmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niftyoi.dhan.domain.calculations.OiCalculator
import com.niftyoi.dhan.domain.models.Side
import com.niftyoi.dhan.presentation.AppState
import com.niftyoi.dhan.presentation.common.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun HeatmapScreen(s: AppState) {
    var side by remember { mutableStateOf(Side.COMBINED) }
    val hist = s.history
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Side.values().forEach { sd -> FilterChip(side == sd, { side = sd }, { Text(if (sd == Side.COMBINED) "CE+PE" else sd.name) }) }
        }
        Note("X = time (stored snapshots), Y = strike, colour = OI change vs the previous snapshot. Green = addition, red = reduction.")
        if (hist.size < 2) { Note("Need at least 2 snapshots. Keep auto-refresh on during market hours."); return@Column }
        val range = if (s.settings.strikeRange > 0) s.settings.strikeRange else 20
        val latest = OiCalculator.limitAroundAtm(hist.last().second, s.spot ?: 0.0, range)
        val strikes = latest.map { it.strike }.sortedDescending()
        val hm = OiCalculator.heatmap(hist, side, strikes)
        val maxAbs = (hm.values.flatten().maxOfOrNull { abs(it) } ?: 1L).coerceAtLeast(1L).toFloat()
        val cellW = 8.dp; val cellH = 14.dp
        val hs = rememberScrollState()
        Row(Modifier.verticalScroll(rememberScrollState())) {
            Column {
                strikes.forEach { Text(k(it), fontSize = 10.sp, modifier = Modifier.height(cellH).width(48.dp)) }
            }
            Column(Modifier.horizontalScroll(hs)) {
                Canvas(Modifier.width(cellW * hm.times.size).height(cellH * strikes.size)) {
                    val w = cellW.toPx(); val h = cellH.toPx()
                    for (r in strikes.indices) for (c in hm.times.indices) {
                        val v = hm.values[r][c]
                        if (v == 0L) continue
                        val a = (abs(v) / maxAbs).coerceIn(0.08f, 1f)
                        drawRect((if (v > 0) Green else Red).copy(alpha = a), Offset(c * w, r * h), Size(w - 1f, h - 1f))
                    }
                }
                val f = SimpleDateFormat("HH:mm", Locale.US)
                Text("${f.format(Date(hm.times.first()))} → ${f.format(Date(hm.times.last()))}", fontSize = 10.sp)
            }
        }
    }
}
