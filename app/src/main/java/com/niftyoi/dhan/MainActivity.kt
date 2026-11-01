package com.niftyoi.dhan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niftyoi.dhan.data.dhan.FeedState
import com.niftyoi.dhan.domain.models.Side
import com.niftyoi.dhan.presentation.AppState
import com.niftyoi.dhan.presentation.AppViewModel
import com.niftyoi.dhan.presentation.common.Green
import com.niftyoi.dhan.presentation.common.Orange
import com.niftyoi.dhan.presentation.common.Red
import com.niftyoi.dhan.presentation.common.rememberNow
import com.niftyoi.dhan.presentation.dashboard.DashboardScreen
import com.niftyoi.dhan.presentation.futures.FuturesScreen
import com.niftyoi.dhan.presentation.heatmap.HeatmapScreen
import com.niftyoi.dhan.presentation.history.HistoryScreen
import com.niftyoi.dhan.presentation.individualchart.IndividualChartScreen
import com.niftyoi.dhan.presentation.optionchain.OptionChainScreen
import com.niftyoi.dhan.presentation.optionprofile.OptionProfileScreen
import com.niftyoi.dhan.presentation.scanner.ScannerScreen
import com.niftyoi.dhan.presentation.settings.SettingsScreen

private val TABS = listOf("Dashboard", "Futures OI", "Options OI", "Option Chain", "Option Chart", "High OI", "Heatmap", "History", "Settings")

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val s by vm.state.collectAsStateWithLifecycle()
                val chart by vm.chart.collectAsStateWithLifecycle()
                var tab by remember { mutableIntStateOf(if (vm.creds.isConfigured) 0 else 8) }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        Column(Modifier.statusBarsPadding()) {
                            Text("NIFTY OI PROFILE – DHAN", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
                            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                                TABS.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t, fontSize = 12.sp) }) }
                            }
                            if (tab != 8) Header(s, vm)
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> DashboardScreen(s)
                            1 -> FuturesScreen(s)
                            2 -> OptionProfileScreen(s)
                            3 -> OptionChainScreen(s) { strike, side -> vm.openChart(strike, side); tab = 4 }
                            4 -> IndividualChartScreen(vm, chart, s.settings.darkChart)
                            5 -> ScannerScreen(s)
                            6 -> HeatmapScreen(s)
                            7 -> HistoryScreen(s)
                            else -> SettingsScreen(
                                initialClientId = vm.creds.clientId, hasToken = vm.creds.accessToken.isNotBlank(), settings = s.settings,
                                onSaveCreds = { id, tok -> vm.saveCredentials(id, tok) }, onClear = vm::clearCredentials, onSaveSettings = vm::updateSettings,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(s: AppState, vm: AppViewModel) {
    val now = rememberNow()
    val tickAge = s.lastTickMs?.let { (now - it) / 1000 }
    val (fColor, fText) = when {
        s.feed.state != FeedState.CONNECTED -> Red to "Feed ${s.feed.state.name.lowercase()}"
        tickAge == null -> Orange to "Feed: waiting"
        tickAge < 10 -> Green to "Live ${tickAge}s"
        tickAge < 60 -> Orange to "Stale ${tickAge}s"
        else -> Red to "Stale ${tickAge}s"
    }
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(color = if (s.connected) Green else Red, shape = MaterialTheme.shapes.small) {
                Text(if (s.connected) "Dhan REST ok" else "Dhan REST off", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Surface(color = fColor, shape = MaterialTheme.shapes.small) {
                Text(fText, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("Auto", fontSize = 11.sp); Switch(s.settings.autoRefresh, { vm.toggleAuto(it) })
            Button(onClick = { vm.refresh() }, enabled = !s.loading, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text(if (s.loading) "…" else "Refresh", fontSize = 12.sp) }
        }
        if (s.expiries.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(s.expiries.take(8)) { e -> FilterChip(e == s.selectedExpiry, { vm.selectExpiry(e) }, { Text(e, fontSize = 11.sp) }) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            item { Text("ΔOI:", fontSize = 11.sp) }
            items(listOf(0, 1, 3, 5, 15, 30)) { w -> FilterChip(s.changeWindowMin == w, { vm.setChangeWindow(w) }, { Text(if (w == 0) "prev close" else "${w}m", fontSize = 11.sp) }) }
        }
    }
}
