package com.niftyoi.dhan.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niftyoi.dhan.domain.models.LevelOi
import com.niftyoi.dhan.domain.models.ValueArea
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

private val nf: NumberFormat = NumberFormat.getInstance(Locale("en", "IN"))
fun n(v: Long?) = v?.let { nf.format(it) } ?: "—"
fun d(v: Double?, dp: Int = 2) = v?.let { String.format(Locale.US, "%.${dp}f", it) } ?: "—"
fun k(v: Double) = nf.format(v.toLong())
fun levels(l: List<LevelOi>) = if (l.isEmpty()) "—" else l.joinToString("\n") { "${k(it.strike)}  (${n(it.value)})" }

val Green = Color(0xFF26A69A)
val Red = Color(0xFFEF5350)
val Orange = Color(0xFFF5A623)
val Blue = Color(0xFF5C6BC0)

@Composable
fun rememberNow(): Long {
    var t by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); t = System.currentTimeMillis() } }
    return t
}

@Composable
fun Stat(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Horizontal-bar profile. POC highlighted orange, value area blue, rest grey. */
@Composable
fun ProfileBars(
    profile: Map<Double, Long>, poc: Double?, va: ValueArea?,
    label: (Double) -> String = { k(it) }, color: Color? = null, atm: Double? = null,
) {
    val keys = profile.keys.sortedDescending()
    val max = (profile.values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (key in keys.take(150)) {
            val v = profile.getValue(key)
            val inVa = va != null && key >= va.low && key <= va.vah
            val c = color ?: when { key == poc -> Orange; inVa -> Blue; else -> Color(0xFF9E9E9E) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(key) + if (atm != null && key == atm) " ◄" else "", fontSize = 11.sp, modifier = Modifier.width(72.dp))
                Box(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth((v / max).coerceIn(0.005f, 1f)).height(12.dp).background(c))
                }
                Text(n(v), fontSize = 10.sp, modifier = Modifier.width(78.dp))
            }
        }
    }
}
