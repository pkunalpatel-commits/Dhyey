package com.niftyoi.dhan.presentation.individualchart

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.niftyoi.dhan.domain.models.Side
import com.niftyoi.dhan.presentation.AppViewModel
import com.niftyoi.dhan.presentation.ChartState
import com.niftyoi.dhan.presentation.common.*
import kotlinx.coroutines.flow.SharedFlow
import com.niftyoi.dhan.presentation.ChartEvent
import org.json.JSONObject

@Composable
fun IndividualChartScreen(vm: AppViewModel, chart: ChartState, dark: Boolean) {
    var replayOn by remember { mutableStateOf(false) }
    var replayN by remember { mutableFloatStateOf(1f) }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        if (chart.strike == null) {
            Note("Open a chart from the Option Chain tab (tap a CE/PE LTP), or use ATM below.")
            Button(onClick = { vm.openAtm() }) { Text("Open ATM ${chart.side.name}") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.stepStrike(-1) }, contentPadding = PaddingValues(8.dp)) { Text("−") }
            Text(chart.strike?.let { k(it) } ?: "—", fontSize = 14.sp)
            OutlinedButton(onClick = { vm.stepStrike(1) }, contentPadding = PaddingValues(8.dp)) { Text("+") }
            TextButton(onClick = { vm.openAtm() }) { Text("ATM") }
            FilterChip(chart.side == Side.CE, { vm.setSide(Side.CE) }, { Text("CE") })
            FilterChip(chart.side == Side.PE, { vm.setSide(Side.PE) }, { Text("PE") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1, 3, 5, 15, 30).forEach { FilterChip(chart.timeframe == it, { vm.setTimeframe(it) }, { Text("${it}m", fontSize = 11.sp) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.freezeLevels() }, contentPadding = PaddingValues(8.dp)) { Text("Freeze levels", fontSize = 11.sp) }
            if (chart.frozen) OutlinedButton(onClick = { vm.clearFrozen() }, contentPadding = PaddingValues(8.dp)) { Text("Clear frozen", fontSize = 11.sp) }
            Spacer(Modifier.weight(1f))
            Text("Replay", fontSize = 11.sp); Switch(replayOn, { replayOn = it; replayN = chart.optCount.toFloat() })
        }
        if (replayOn && chart.optCount > 1)
            Slider(replayN, { replayN = it }, valueRange = 1f..chart.optCount.toFloat())
        if (chart.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        chart.error?.let { Note("⚠ $it") }
        chart.note?.let { Note(it) }
        chart.info?.let { i ->
            Text("LTP ${d(i.ltp)} · OI ${n(i.oi)} (Δ ${n(i.oiChange)}) · Vol ${n(i.volume)} · VWAP ${d(i.vwap)}", fontSize = 11.sp)
            i.interpretation?.let { Text(it, fontSize = 10.sp) }
        }
        ChartWebView(chart, dark, vm.chartEvents, if (replayOn) replayN.toInt() else null, Modifier.weight(1f).fillMaxWidth())
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChartWebView(chart: ChartState, dark: Boolean, events: SharedFlow<ChartEvent>, replay: Int?, modifier: Modifier) {
    var ready by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    fun js(code: String) { web?.evaluateJavascript(code, null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                webViewClient = WebViewClient()
                addJavascriptInterface(object { @JavascriptInterface fun onReady() { ready = true } }, "Android")
                loadUrl("file:///android_asset/chart/index.html")
                web = this
            }
        },
        onRelease = { it.removeJavascriptInterface("Android"); it.destroy(); web = null; ready = false },
    )
    LaunchedEffect(ready, dark) { if (ready) js("app.setTheme($dark)") }
    LaunchedEffect(ready, chart.dataVersion) {
        if (ready && chart.dataVersion > 0) { js("app.setData(${chart.dataJson}, ${chart.timeframe})"); js("app.setMeta(${chart.metaJson})") }
    }
    LaunchedEffect(ready, chart.levelsVersion) {
        if (ready && chart.levelsVersion > 0) js("app.setLevels(${chart.levelsJson}, ${JSONObject.quote(chart.levelsStamp)})")
    }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        events.collect { e ->
            when (e.kind) {
                "und" -> js("app.tick('und', ${e.value}, null)")
                "opt" -> js("app.tick('opt', ${e.value}, ${e.volume ?: "null"})")
                "oi" -> js("app.oiTick(${e.value})")
            }
        }
    }
    LaunchedEffect(ready, replay, chart.dataVersion) {
        if (!ready) return@LaunchedEffect
        if (replay != null) js("app.replay($replay)") else js("app.replayOff()")
    }
}
