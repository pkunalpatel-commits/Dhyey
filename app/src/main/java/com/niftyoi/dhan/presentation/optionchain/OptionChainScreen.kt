package com.niftyoi.dhan.presentation.optionchain

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niftyoi.dhan.domain.calculations.OiCalculator
import com.niftyoi.dhan.domain.models.Side
import com.niftyoi.dhan.presentation.AppState
import com.niftyoi.dhan.presentation.common.*

private val cols = listOf("CE Vol", "CE IV", "CE Δ", "CE Θ", "CE OI", "CE ΔOI", "CE LTP", "STRIKE", "PE LTP", "PE ΔOI", "PE OI", "PE Θ", "PE Δ", "PE IV", "PE Vol")

@Composable
fun OptionChainScreen(s: AppState, onOpen: (Double, Side) -> Unit) {
    val scroll = rememberScrollState()
    val atm = OiCalculator.atmStrike(s.spot ?: 0.0, s.rows)
    Column(Modifier.fillMaxSize()) {
        Text("Tap a CE or PE LTP to open its chart. ΔOI uses the selected change window.", fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        Row(Modifier.horizontalScroll(scroll)) { cols.forEach { Text(it, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp).padding(2.dp)) } }
        LazyColumn {
            items(s.rows, key = { it.strike }) { r ->
                val bg = if (r.strike == atm) Color(0x33F5A623) else Color.Transparent
                Row(Modifier.horizontalScroll(scroll).background(bg)) {
                    Cell(n(r.ceVolume)); Cell(d(r.ceIv, 1)); Cell(d(r.ceDelta, 2)); Cell(d(r.ceTheta, 1))
                    Cell(n(r.ceOi)); Cell(n(r.ceChange))
                    Cell(d(r.ceLtp), Modifier.clickable { onOpen(r.strike, Side.CE) }, true)
                    Cell(k(r.strike), bold = true)
                    Cell(d(r.peLtp), Modifier.clickable { onOpen(r.strike, Side.PE) }, true)
                    Cell(n(r.peChange)); Cell(n(r.peOi)); Cell(d(r.peTheta, 1)); Cell(d(r.peDelta, 2)); Cell(d(r.peIv, 1)); Cell(n(r.peVolume))
                }
            }
        }
    }
}

@Composable
private fun Cell(t: String, mod: Modifier = Modifier, bold: Boolean = false) {
    Text(t, fontSize = 11.sp, fontWeight = if (bold) FontWeight.Bold else null, modifier = mod.width(64.dp).padding(2.dp))
}
