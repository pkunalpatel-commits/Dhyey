package com.niftyoi.dhan.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.niftyoi.dhan.data.database.*
import com.niftyoi.dhan.data.dhan.*
import com.niftyoi.dhan.domain.calculations.*
import com.niftyoi.dhan.domain.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.BufferOverflow
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

data class FutState(
    val symbol: String? = null, val secId: String? = null,
    val ltp: Double? = null, val oi: Long? = null,
    val oiChange: Long? = null, val priceChange: Double? = null,
    val changeBase: String = "",
    val obs: List<Pair<Double, Long>> = emptyList(),
)

data class AppState(
    val settings: AppSettings = AppSettings(),
    val configured: Boolean = false,
    val loading: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    val expiries: List<String> = emptyList(),
    val selectedExpiry: String? = null,
    val spot: Double? = null,
    val rawRows: List<StrikeRow> = emptyList(),   // change vs previous close
    val rows: List<StrikeRow> = emptyList(),      // change vs selected window
    val changeWindowMin: Int = 0,
    val windowNote: String? = null,
    val lastChainMs: Long? = null,
    val history: List<Pair<Long, List<StrikeRow>>> = emptyList(),
    val snapshotsTotal: Int = 0,
    val fut: FutState = FutState(),
    val futSignal: String? = null,
    val feed: FeedStatus = FeedStatus(),
    val lastTickMs: Long? = null,
    val summary: OiSummary? = null,
)

data class ChartInfo(
    val ltp: Double?, val oi: Long?, val oiChange: Long?, val volume: Long?, val vwap: Double?,
    val iv: Double, val delta: Double, val gamma: Double, val theta: Double, val vega: Double,
    val interpretation: String?,
)

data class ChartState(
    val strike: Double? = null,
    val side: Side = Side.CE,
    val timeframe: Int = 5,
    val loading: Boolean = false,
    val error: String? = null,
    val note: String? = null,
    val dataJson: String = "{}",
    val dataVersion: Int = 0,
    val levelsJson: String = "{\"underlying\":[],\"premium\":[],\"strip\":[]}",
    val levelsVersion: Int = 0,
    val levelsStamp: String = "",
    val metaJson: String = "{}",
    val optCount: Int = 0,
    val frozen: Boolean = false,
    val info: ChartInfo? = null,
)

/** Live event forwarded to the chart WebView. kind: und | opt | oi */
data class ChartEvent(val kind: String, val value: Double, val volume: Long? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val creds = CredentialStore(app)
    private val settingsStore = SettingsStore(app)
    private val api = DhanApi(creds)
    private val dao = AppDb.get(app).dao()
    private val prefs = app.getSharedPreferences("app_meta", android.content.Context.MODE_PRIVATE)
    val feed = LiveFeed(creds, viewModelScope)

    private val _state = MutableStateFlow(AppState(settings = settingsStore.load(), configured = creds.isConfigured,
        changeWindowMin = 0))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _chart = MutableStateFlow(ChartState(timeframe = settingsStore.load().chartTf))
    val chart: StateFlow<ChartState> = _chart.asStateFlow()

    private val _events = MutableSharedFlow<ChartEvent>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val chartEvents: SharedFlow<ChartEvent> = _events.asSharedFlow()

    private var loop: Job? = null
    private var chartJob: Job? = null
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()
    private val ist = ZoneId.of("Asia/Kolkata")

    // chart working data (real Dhan candles only)
    private var opt1m: List<Candle> = emptyList()
    private var und1m: List<Candle> = emptyList()
    private var optSecId: String? = null
    private var frozenLevels: ChartLevels? = null
    private var lastLevels: ChartLevels? = null

    init {
        viewModelScope.launch { feed.status.collect { s -> _state.update { it.copy(feed = s) } } }
        viewModelScope.launch { feed.lastMessageMs.sample(1000).collect { t -> _state.update { it.copy(lastTickMs = t) } } }
        viewModelScope.launch { feed.quotes.sample(300).collect { applyLiveQuotes(it) } }
        viewModelScope.launch { feed.ticks.collect { forwardToChart(it) } }
        if (creds.isConfigured) feed.start()
        if (_state.value.settings.autoRefresh) toggleAuto(true)
    }

    private fun dayStartMs(): Long = LocalDate.now(ist).atStartOfDay(ist).toInstant().toEpochMilli()
    private fun today(): String = LocalDate.now(ist).toString()

    // ---------- settings / credentials ----------

    fun saveCredentials(clientId: String, token: String) {
        creds.clientId = clientId
        if (token.isNotBlank()) creds.accessToken = token
        _state.update { it.copy(configured = creds.isConfigured, error = null, expiries = emptyList(), selectedExpiry = null) }
        feed.stop(); if (creds.isConfigured) feed.start()
    }

    fun clearCredentials() {
        creds.clear(); loop?.cancel(); feed.stop()
        _state.value = AppState(settings = settingsStore.load(), configured = false)
    }

    fun updateSettings(s: AppSettings) {
        settingsStore.save(s)
        val old = _state.value.settings
        _state.update { it.copy(settings = s) }
        if (s.autoRefresh != old.autoRefresh || s.snapshotSec != old.snapshotSec) toggleAuto(s.autoRefresh)
        recompute()
        if (_chart.value.strike != null) {
            if (s.chartTf != old.chartTf) setTimeframe(s.chartTf) else rebuildLevels()
        }
    }

    // ---------- chain refresh ----------

    fun selectExpiry(e: String) {
        _state.update { it.copy(selectedExpiry = e, rawRows = emptyList(), rows = emptyList(), history = emptyList(), summary = null) }
        viewModelScope.launch { loadHistory(e); refreshNow() }
    }

    fun setChangeWindow(min: Int) {
        _state.update { it.copy(changeWindowMin = min) }
        recompute()
        rebuildLevels()
    }

    fun toggleAuto(on: Boolean) {
        loop?.cancel()
        val s = _state.value.settings
        if (s.autoRefresh != on) { val n = s.copy(autoRefresh = on); settingsStore.save(n); _state.update { it.copy(settings = n) } }
        if (on) loop = viewModelScope.launch {
            while (isActive) { refreshNow(); delay(maxOf(10, _state.value.settings.snapshotSec) * 1000L) }
        }
    }

    fun refresh() { viewModelScope.launch { refreshNow() } }

    private suspend fun loadHistory(expiry: String) {
        val rows = dao.snapshotsSince(expiry, dayStartMs())
        val grouped = rows.groupBy { it.ts }.toSortedMap().map { (t, r) -> t to r.map { it.toStrikeRow() }.sortedBy { it.strike } }
        _state.update { it.copy(history = grouped) }
    }

    private suspend fun ensureInstruments() {
        val last = prefs.getString("instruments_date", null)
        if (dao.instrumentCount() > 0 && last == today()) return
        try {
            val rows = api.downloadNiftyInstruments()
            if (rows.isNotEmpty()) {
                dao.replaceInstruments(rows.map { InstrumentEntity(it.securityId, it.symbol, it.instrument, it.expiry, it.strike, it.optType) })
                prefs.edit().putString("instruments_date", today()).apply()
            }
        } catch (e: Exception) {
            if (dao.instrumentCount() == 0) throw e // no cached master to fall back on
        }
    }

    private suspend fun refreshNow() {
        if (!creds.isConfigured) {
            _state.update { it.copy(error = "Enter Dhan Client ID and Access Token in Settings.", configured = false) }; return
        }
        if (!refreshMutex.tryLock()) return
        _state.update { it.copy(loading = true, error = null) }
        try {
            ensureInstruments()
            var expiry = _state.value.selectedExpiry
            if (_state.value.expiries.isEmpty()) {
                val list = api.expiryList()
                expiry = expiry ?: list.firstOrNull()
                _state.update { it.copy(expiries = list, selectedExpiry = expiry) }
                expiry?.let { loadHistory(it) }
            }
            val exp = expiry ?: throw IllegalStateException("No expiries returned by Dhan")

            val chain = api.optionChain(exp)
            val now = System.currentTimeMillis()
            dao.insertSnapshot(chain.rows.map {
                OptionSnapshotRow(ts = now, expiry = exp, underlying = chain.underlyingLtp, strike = it.strike,
                    ceLtp = it.ceLtp, ceOi = it.ceOi, cePrevOi = it.cePrevOi, peLtp = it.peLtp, peOi = it.peOi, pePrevOi = it.pePrevOi,
                    ceVol = it.ceVolume, peVol = it.peVolume, ceIv = it.ceIv, peIv = it.peIv)
            })
            val hist = (_state.value.history + (now to chain.rows)).takeLast(2000)
            _state.update { it.copy(connected = true, spot = chain.underlyingLtp, rawRows = chain.rows, history = hist, lastChainMs = now) }
            recompute()
            refreshFutures(now)
            updateSubscriptions()
            val total = dao.snapshotCount()
            _state.update { it.copy(snapshotsTotal = total) }
            dao.pruneSnapshots(now - 30L * 86_400_000); dao.pruneFutures(now - 30L * 86_400_000); dao.pruneCandles(now / 1000 - 30L * 86_400)
            rebuildLevels()
        } catch (e: Exception) {
            _state.update { it.copy(connected = false, error = e.message ?: e.javaClass.simpleName) }
        } finally {
            refreshMutex.unlock()
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun refreshFutures(now: Long) {
        try {
            val fut = dao.nearestFuture(today()) ?: return
            val q = api.futuresQuote(fut.securityId)
            if (q != null && q.ltp > 0 && q.oi != null) dao.insertFutures(FuturesSnapshot(ts = now, securityId = fut.securityId, ltp = q.ltp, oi = q.oi))
            val snaps = dao.futuresSince(fut.securityId, dayStartMs())
            _state.update { it.copy(fut = it.fut.copy(symbol = fut.symbol, secId = fut.securityId, ltp = q?.ltp ?: it.fut.ltp, oi = q?.oi ?: it.fut.oi,
                obs = snaps.map { s -> s.ltp to s.oi })) }
            computeFuturesChange(snaps)
        } catch (_: Exception) {
            // best effort: option chain data stays valid
        }
    }

    private fun computeFuturesChange(snaps: List<FuturesSnapshot>) {
        val st = _state.value
        val cur = st.fut
        val ltp = cur.ltp ?: return
        val oi = cur.oi ?: return
        val win = st.changeWindowMin
        val base: FuturesSnapshot?
        val label: String
        if (win > 0) {
            val target = System.currentTimeMillis() - win * 60_000L
            base = snaps.lastOrNull { it.ts <= target }
            label = "vs ${win}m ago"
        } else {
            base = snaps.firstOrNull()
            label = "vs first snapshot today"
        }
        if (base == null) { _state.update { it.copy(fut = it.fut.copy(oiChange = null, priceChange = null, changeBase = "not enough history"), futSignal = null) }; return }
        val dOi = oi - base.oi
        val dPx = ltp - base.ltp
        val sig = Interpretation.classify(dPx, dOi)
        _state.update { it.copy(fut = it.fut.copy(oiChange = dOi, priceChange = dPx, changeBase = label), futSignal = Interpretation.label(sig)) }
    }

    /** Apply the selected change window and recompute the summary. */
    private fun recompute() {
        val st = _state.value
        val raw = st.rawRows
        if (raw.isEmpty()) return
        val (rows, note) = windowed(raw, st.history, st.changeWindowMin)
        val spot = st.spot ?: 0.0
        val summary = OiCalculator.summarize(spot, rows, st.settings.topN, st.settings.vaPct / 100.0)
        _state.update { it.copy(rows = rows, windowNote = note, summary = summary) }
    }

    private fun windowed(raw: List<StrikeRow>, history: List<Pair<Long, List<StrikeRow>>>, win: Int): Pair<List<StrikeRow>, String?> {
        if (win <= 0) return raw to null
        val target = System.currentTimeMillis() - win * 60_000L
        val prior = history.lastOrNull { it.first <= target }
        return if (prior == null) raw to "Not enough history for a ${win}-minute window yet; showing change vs previous close."
        else OiCalculator.withPrior(raw, prior.second) to null
    }

    // ---------- live feed ----------

    private fun updateSubscriptions() {
        val st = _state.value
        val list = mutableListOf(Sub("IDX_I", "13", 15))
        st.fut.secId?.let { list += Sub("NSE_FNO", it, 21) }
        optSecId?.let { list += Sub("NSE_FNO", it, 21) }
        feed.setSubscriptions(list)
    }

    private fun applyLiveQuotes(q: Map<Long, LiveQuote>) {
        val st = _state.value
        val spot = q[13L]?.ltp?.takeIf { it > 0 }
        val futId = st.fut.secId?.toLongOrNull()
        val fq = futId?.let { q[it] }
        if (spot == null && fq == null) return
        _state.update {
            it.copy(
                spot = spot ?: it.spot,
                fut = it.fut.copy(ltp = fq?.ltp?.takeIf { p -> p > 0 } ?: it.fut.ltp, oi = fq?.oi ?: it.fut.oi),
            )
        }
    }

    private fun forwardToChart(t: FeedTick) {
        if (_chart.value.strike == null) return
        if (t.securityId == 13L && t.ltp != null) { _events.tryEmit(ChartEvent("und", t.ltp)); return }
        val id = optSecId?.toLongOrNull() ?: return
        if (t.securityId != id) return
        t.ltp?.let { _events.tryEmit(ChartEvent("opt", it, t.volume)) }
        t.oi?.let { _events.tryEmit(ChartEvent("oi", it.toDouble())) }
    }

    // ---------- individual option chart ----------

    fun openChart(strike: Double, side: Side) {
        chartJob?.cancel()
        chartJob = viewModelScope.launch { loadChart(strike, side) }
    }

    fun stepStrike(delta: Int) {
        val c = _chart.value
        val strikes = _state.value.rawRows.map { it.strike }.sorted()
        val cur = c.strike ?: return
        val i = strikes.indexOf(cur)
        if (i < 0) return
        val n = strikes.getOrNull(i + delta) ?: return
        openChart(n, c.side)
    }

    fun openAtm() {
        val st = _state.value
        val atm = OiCalculator.atmStrike(st.spot ?: 0.0, st.rawRows)
        if (atm > 0) openChart(atm, _chart.value.side)
    }

    fun setSide(side: Side) { _chart.value.strike?.let { openChart(it, side) } }

    fun setTimeframe(tf: Int) {
        _chart.update { it.copy(timeframe = tf) }
        pushChartData()
    }

    fun freezeLevels() { frozenLevels = lastLevels; _chart.update { it.copy(frozen = frozenLevels != null) }; rebuildLevels() }
    fun clearFrozen() { frozenLevels = null; _chart.update { it.copy(frozen = false) }; rebuildLevels() }

    private suspend fun loadChart(strike: Double, side: Side) {
        val st = _state.value
        val expiry = st.selectedExpiry
        if (expiry == null || st.rawRows.isEmpty()) {
            _chart.update { it.copy(strike = strike, side = side, error = "Load the option chain first (Dashboard → Refresh).") }; return
        }
        _chart.update { it.copy(strike = strike, side = side, loading = true, error = null, note = null) }
        val row = st.rawRows.firstOrNull { it.strike == strike }
        val type = if (side == Side.CE) "CE" else "PE"
        val secId = (if (side == Side.CE) row?.ceSecId else row?.peSecId) ?: dao.findOption(expiry, strike, type)?.securityId
        if (secId == null) {
            _chart.update { it.copy(loading = false, error = "Security ID for NIFTY $strike $type not found in instrument master.") }; return
        }
        optSecId = secId
        updateSubscriptions()
        var note: String? = null
        opt1m = try {
            val c = api.intradayCandles(secId, "NSE_FNO", "OPTIDX", withOi = true)
            dao.upsertCandles(c.map { OptionCandleEntity(secId, it.time, it.open, it.high, it.low, it.close, it.volume, it.oi ?: 0L) })
            c
        } catch (e: Exception) {
            val cached = dao.candlesSince(secId, System.currentTimeMillis() / 1000 - 5 * 86_400).map { it.toCandle() }
            note = "Live candle request failed (${e.message}). " + if (cached.isEmpty()) "No cached candles." else "Showing cached real candles."
            cached
        }
        und1m = try { api.intradayCandles("13", "IDX_I", "INDEX", withOi = false) } catch (e: Exception) {
            note = (note ?: "") + " Underlying candles unavailable (${e.message})."; emptyList()
        }
        if (opt1m.isEmpty()) note = (note ?: "") + " No intraday candles returned by Dhan for this contract (market closed or no trades)."
        _chart.update { it.copy(loading = false, note = note?.trim()) }
        pushChartData()
        rebuildLevels()
    }

    private fun pushChartData() {
        val tf = _chart.value.timeframe
        val opt = CandleUtils.resample(opt1m, tf)
        val und = CandleUtils.resample(und1m, tf)
        val vwap = CandleUtils.vwap(opt)
        val st = _state.value
        val c = _chart.value
        val row = st.rawRows.firstOrNull { it.strike == c.strike }
        val isCe = c.side == Side.CE
        val title = "NIFTY ${c.strike?.toLong() ?: ""} ${if (isCe) "CE" else "PE"} · ${tf}m"
        val iv = if (isCe) row?.ceIv else row?.peIv
        val greeks = row?.let {
            "IV ${fmt(if (isCe) it.ceIv else it.peIv)} · Δ ${fmt(if (isCe) it.ceDelta else it.peDelta, 3)} · Γ ${fmt(if (isCe) it.ceGamma else it.peGamma, 5)} · Θ ${fmt(if (isCe) it.ceTheta else it.peTheta)} · V ${fmt(if (isCe) it.ceVega else it.peVega)}"
        }
        val meta = JSONObject().put("title", title).put("greeks", greeks ?: "").toString()
        val last = opt.lastOrNull()
        val lastOi = opt.lastOrNull { it.oi != null }?.oi
        val firstOi = opt.firstOrNull { it.oi != null }?.oi
        val interp = interpretation(opt, und, isCe)
        val info = ChartInfo(
            ltp = last?.close ?: (if (isCe) row?.ceLtp else row?.peLtp), oi = lastOi ?: (if (isCe) row?.ceOi else row?.peOi),
            oiChange = if (row != null) (if (isCe) row.ceChange else row.peChange) else if (lastOi != null && firstOi != null) lastOi - firstOi else null,
            volume = opt.sumOf { it.volume }.takeIf { opt.isNotEmpty() }, vwap = vwap.lastOrNull()?.second,
            iv = iv ?: 0.0, delta = row?.let { if (isCe) it.ceDelta else it.peDelta } ?: 0.0,
            gamma = row?.let { if (isCe) it.ceGamma else it.peGamma } ?: 0.0,
            theta = row?.let { if (isCe) it.ceTheta else it.peTheta } ?: 0.0,
            vega = row?.let { if (isCe) it.ceVega else it.peVega } ?: 0.0,
            interpretation = interp,
        )
        _chart.update { it.copy(dataJson = ChartJson.payload(und, opt, vwap), dataVersion = it.dataVersion + 1, metaJson = meta, optCount = opt.size, info = info) }
    }

    private fun interpretation(opt: List<Candle>, und: List<Candle>, isCe: Boolean): String? {
        val o = opt.filter { it.oi != null }
        if (o.size < 2) return null
        val back = minOf(3, o.size - 1)
        val a = o[o.size - 1 - back]; val b = o.last()
        val uA = und.lastOrNull { it.time <= a.time }; val uB = und.lastOrNull()
        val uChg = if (uA != null && uB != null) uB.close - uA.close else 0.0
        return Interpretation.optionContext(uChg, b.close - a.close, b.oi!! - a.oi!!, isCe)
    }

    private fun fmt(v: Double, d: Int = 2) = String.format(java.util.Locale.US, "%.${d}f", v)

    /** Recalculate strike levels (underlying panel) and premium levels (option panel) from the complete chain. */
    private fun rebuildLevels() {
        val c = _chart.value
        val st = _state.value
        if (c.strike == null || st.rows.isEmpty()) return
        val s = st.settings
        val hist = st.history.takeLast(60)
        var obs = (hist.map { it.second } + listOf(st.rawRows)).flatMap { OiCalculator.premiumObservations(it, c.side) }
        obs = obs.filter { (p, _) -> p >= s.premiumMin && (s.premiumMax <= 0.0 || p <= s.premiumMax) }
        val lv = ChartLevelBuilder.build(st.rows, obs, c.side, s.topN, s.premiumBin, s.vaPct / 100.0, frozenLevels)
        lastLevels = lv.copy(underlying = lv.underlying.filter { it.kind != "prev" })
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(st.lastChainMs ?: System.currentTimeMillis()))
        _chart.update { it.copy(levelsJson = lv.toJson(), levelsVersion = it.levelsVersion + 1, levelsStamp = stamp, frozen = frozenLevels != null) }
    }

    override fun onCleared() { feed.stop(); super.onCleared() }
}
