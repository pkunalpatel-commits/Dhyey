package com.niftyoi.dhan.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.niftyoi.dhan.data.dhan.AppSettings

@Composable
fun SettingsScreen(
    initialClientId: String, hasToken: Boolean, settings: AppSettings,
    onSaveCreds: (String, String) -> Unit, onClear: () -> Unit, onSaveSettings: (AppSettings) -> Unit,
) {
    var clientId by remember { mutableStateOf(initialClientId) }
    var token by remember { mutableStateOf("") }
    var strikeRange by remember(settings) { mutableStateOf(settings.strikeRange.toString()) }
    var premMin by remember(settings) { mutableStateOf(settings.premiumMin.toString()) }
    var premMax by remember(settings) { mutableStateOf(settings.premiumMax.toString()) }
    var priceBin by remember(settings) { mutableStateOf(settings.priceBin.toString()) }
    var premBin by remember(settings) { mutableStateOf(settings.premiumBin.toString()) }
    var va by remember(settings) { mutableStateOf(settings.vaPct.toString()) }
    var snap by remember(settings) { mutableIntStateOf(settings.snapshotSec) }
    var tf by remember(settings) { mutableIntStateOf(settings.chartTf) }
    var top by remember(settings) { mutableIntStateOf(settings.topN) }
    var auto by remember(settings) { mutableStateOf(settings.autoRefresh) }
    var dark by remember(settings) { mutableStateOf(settings.darkChart) }
    var msg by remember { mutableStateOf("") }

    @Composable fun field(label: String, v: String, set: (String) -> Unit) =
        OutlinedTextField(v, set, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Dhan credentials", style = MaterialTheme.typography.titleLarge)
        Text("Stored encrypted on this device. Generate the token in Dhan web → Profile → DhanHQ Trading APIs (Data APIs subscription needed). Tokens expire; re-enter when you see an auth error.", style = MaterialTheme.typography.bodySmall)
        field("Client ID", clientId) { clientId = it }
        OutlinedTextField(token, { token = it }, label = { Text(if (hasToken) "Access Token (saved – enter new to replace)" else "Access Token") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = clientId.isNotBlank() && (token.isNotBlank() || hasToken), onClick = { onSaveCreds(clientId, token); token = ""; msg = "Credentials saved." }) { Text("Save credentials") }
            OutlinedButton(onClick = { onClear(); clientId = ""; token = ""; msg = "Cleared." }) { Text("Clear") }
        }
        HorizontalDivider()
        Text("Analytics", style = MaterialTheme.typography.titleLarge)
        field("Strike range (ATM ± N, 0 = full chain)", strikeRange) { strikeRange = it }
        field("Price bin size (futures, points)", priceBin) { priceBin = it }
        field("Premium bin size (₹)", premBin) { premBin = it }
        field("Premium range min (₹)", premMin) { premMin = it }
        field("Premium range max (₹, 0 = none)", premMax) { premMax = it }
        field("Value area %", va) { va = it }
        Text("Snapshot / auto-refresh interval")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(30, 60, 180, 300).forEach { FilterChip(snap == it, { snap = it }, { Text(if (it < 60) "${it}s" else "${it / 60}m") }) } }
        Text("Default chart timeframe (min)")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1, 3, 5, 15, 30).forEach { FilterChip(tf == it, { tf = it }, { Text("$it") }) } }
        Text("High-OI list size")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(3, 5, 10).forEach { FilterChip(top == it, { top = it }, { Text("Top $it") }) } }
        Row { Text("Auto-refresh", Modifier.weight(1f)); Switch(auto, { auto = it }) }
        Row { Text("Dark chart theme", Modifier.weight(1f)); Switch(dark, { dark = it }) }
        Button(onClick = {
            onSaveSettings(AppSettings(
                strikeRange = strikeRange.toIntOrNull()?.coerceIn(0, 200) ?: settings.strikeRange,
                premiumMin = premMin.toDoubleOrNull() ?: 0.0, premiumMax = premMax.toDoubleOrNull() ?: 0.0,
                priceBin = priceBin.toDoubleOrNull()?.takeIf { it > 0 } ?: settings.priceBin,
                premiumBin = premBin.toDoubleOrNull()?.takeIf { it > 0 } ?: settings.premiumBin,
                vaPct = va.toIntOrNull()?.coerceIn(1, 100) ?: settings.vaPct,
                snapshotSec = snap, chartTf = tf, topN = top, autoRefresh = auto, darkChart = dark))
            msg = "Settings saved."
        }) { Text("Save settings") }
        if (msg.isNotBlank()) Text(msg)
    }
}
