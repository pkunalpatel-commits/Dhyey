package com.niftyoi.dhan.data.dhan

import com.niftyoi.dhan.domain.calculations.CandleUtils
import com.niftyoi.dhan.domain.models.Candle
import com.niftyoi.dhan.domain.models.FuturesQuote
import com.niftyoi.dhan.domain.models.OptionChain
import com.niftyoi.dhan.domain.models.StrikeRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class DhanException(val httpCode: Int, message: String) : Exception(message)

data class InstrumentRow(
    val securityId: String, val symbol: String, val instrument: String, val expiry: String,
    val strike: Double = 0.0, val optType: String = "",
)

/** Enforces a minimum gap between calls to one endpoint family. */
private class RateGate(private val minGapMs: Long) {
    private val mutex = Mutex()
    private var last = 0L
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val wait = minGapMs - (System.currentTimeMillis() - last)
        if (wait > 0) delay(wait)
        try { block() } finally { last = System.currentTimeMillis() }
    }
}

/**
 * Dhan API v2 client (https://dhanhq.co/docs/v2/). Endpoints:
 *  POST /optionchain/expirylist, POST /optionchain (1 request / 3 s),
 *  POST /marketfeed/quote (1 / s), POST /charts/intraday (Data API).
 * Credentials are sent only as headers and are never logged.
 */
class DhanApi(
    private val creds: CredentialStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val base = "https://api.dhan.co/v2"
    private val json = "application/json".toMediaType()
    private val chainGate = RateGate(3_100)
    private val quoteGate = RateGate(1_100)
    private val dataGate = RateGate(300)

    // NIFTY 50 index: security id 13, segment IDX_I
    private val niftyId = 13
    private val niftySeg = "IDX_I"

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(base + path)
            .header("access-token", creds.accessToken)
            .header("client-id", creds.clientId)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = when (resp.code) {
                    401, 403 -> "Authentication failed (HTTP ${resp.code}). Check Client ID / Access Token (tokens expire)."
                    429 -> "Rate limit hit (HTTP 429). Slow down refresh."
                    else -> "HTTP ${resp.code}: ${text.take(200)}"
                }
                throw DhanException(resp.code, msg)
            }
            JSONObject(text)
        }
    }

    suspend fun expiryList(): List<String> = chainGate.run {
        val r = post("/optionchain/expirylist",
            JSONObject().put("UnderlyingScrip", niftyId).put("UnderlyingSeg", niftySeg))
        val arr = r.optJSONArray("data") ?: return@run emptyList()
        List(arr.length()) { arr.getString(it) }
    }

    suspend fun optionChain(expiry: String): OptionChain = chainGate.run {
        val r = post("/optionchain",
            JSONObject().put("UnderlyingScrip", niftyId).put("UnderlyingSeg", niftySeg).put("Expiry", expiry))
        val data = r.optJSONObject("data") ?: throw DhanException(200, "Empty option chain response")
        val oc = data.optJSONObject("oc") ?: throw DhanException(200, "Option chain missing 'oc'")
        val rows = mutableListOf<StrikeRow>()
        val keys = oc.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val strike = k.toDoubleOrNull() ?: continue
            val o = oc.getJSONObject(k)
            val ce = o.optJSONObject("ce")
            val pe = o.optJSONObject("pe")
            fun g(leg: JSONObject?, name: String) = leg?.optJSONObject("greeks")?.optDouble(name, 0.0) ?: 0.0
            fun sid(leg: JSONObject?) = leg?.opt("security_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            rows += StrikeRow(
                strike = strike,
                ceLtp = ce?.optDouble("last_price", 0.0) ?: 0.0,
                ceOi = ce?.optLong("oi", 0L) ?: 0L,
                cePrevOi = ce?.optLong("previous_oi", 0L) ?: 0L,
                peLtp = pe?.optDouble("last_price", 0.0) ?: 0.0,
                peOi = pe?.optLong("oi", 0L) ?: 0L,
                pePrevOi = pe?.optLong("previous_oi", 0L) ?: 0L,
                ceVolume = ce?.optLong("volume", 0L) ?: 0L, peVolume = pe?.optLong("volume", 0L) ?: 0L,
                ceIv = ce?.optDouble("implied_volatility", 0.0) ?: 0.0, peIv = pe?.optDouble("implied_volatility", 0.0) ?: 0.0,
                ceDelta = g(ce, "delta"), peDelta = g(pe, "delta"),
                ceGamma = g(ce, "gamma"), peGamma = g(pe, "gamma"),
                ceTheta = g(ce, "theta"), peTheta = g(pe, "theta"),
                ceVega = g(ce, "vega"), peVega = g(pe, "vega"),
                ceSecId = sid(ce), peSecId = sid(pe),
            )
        }
        OptionChain(data.optDouble("last_price", 0.0), rows.sortedBy { it.strike })
    }

    /** Futures quote via Market Quote API (segment NSE_FNO). Returns null if fields are absent. */
    suspend fun futuresQuote(securityId: String): FuturesQuote? = quoteGate.run {
        val id = securityId.toLongOrNull() ?: return@run null
        val r = post("/marketfeed/quote", JSONObject().put("NSE_FNO", JSONArray().put(id)))
        val item = r.optJSONObject("data")?.optJSONObject("NSE_FNO")?.optJSONObject(securityId)
            ?: return@run null
        FuturesQuote(
            ltp = item.optDouble("last_price", 0.0),
            oi = if (item.has("oi")) item.optLong("oi") else null,
        )
    }

    /**
     * 1-minute intraday candles (real Dhan data only; nothing is filled in).
     * Dhan serves intraday history for recent trading days, so we request the last 5 calendar days.
     * Timestamps are normalised to Unix seconds.
     */
    suspend fun intradayCandles(
        securityId: String, segment: String, instrument: String, withOi: Boolean, days: Int = 5,
    ): List<Candle> = dataGate.run {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val nowIst = LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))
        val body = JSONObject()
            .put("securityId", securityId).put("exchangeSegment", segment).put("instrument", instrument)
            .put("interval", "1").put("oi", withOi)
            .put("fromDate", nowIst.minusDays(days.toLong()).withHour(9).withMinute(15).withSecond(0).format(fmt))
            .put("toDate", nowIst.format(fmt))
        val r = post("/charts/intraday", body)
        val ts = r.optJSONArray("timestamp") ?: return@run emptyList()
        val o = r.optJSONArray("open"); val h = r.optJSONArray("high")
        val l = r.optJSONArray("low"); val c = r.optJSONArray("close")
        val v = r.optJSONArray("volume"); val oi = r.optJSONArray("open_interest")
        if (o == null || h == null || l == null || c == null) return@run emptyList()
        val n = minOf(ts.length(), o.length(), h.length(), l.length(), c.length())
        List(n) { i ->
            Candle(
                time = CandleUtils.normalizeEpoch(ts.getLong(i)),
                open = o.getDouble(i), high = h.getDouble(i), low = l.getDouble(i), close = c.getDouble(i),
                volume = v?.optLong(i, 0L) ?: 0L,
                oi = oi?.let { if (i < it.length()) it.optLong(i, 0L) else null },
            )
        }
    }

    /** Streams the compact instrument master CSV and returns NIFTY index futures and options rows. */
    suspend fun downloadNiftyInstruments(): List<InstrumentRow> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://images.dhan.co/api-data/api-scrip-master.csv").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw DhanException(resp.code, "Instrument master download failed (HTTP ${resp.code})")
            val reader = resp.body!!.charStream().buffered()
            val header = reader.readLine()?.split(",")?.map { it.trim() } ?: return@use emptyList<InstrumentRow>()
            val iEx = header.indexOf("SEM_EXM_EXCH_ID"); val iId = header.indexOf("SEM_SMST_SECURITY_ID")
            val iInst = header.indexOf("SEM_INSTRUMENT_NAME"); val iSym = header.indexOf("SEM_TRADING_SYMBOL")
            val iExp = header.indexOf("SEM_EXPIRY_DATE")
            val iStrike = header.indexOf("SEM_STRIKE_PRICE"); val iType = header.indexOf("SEM_OPTION_TYPE")
            if (listOf(iEx, iId, iInst, iSym, iExp).any { it < 0 })
                throw DhanException(200, "Unexpected instrument master columns")
            val need = maxOf(iEx, iId, iInst, iSym, iExp, iStrike, iType)
            val out = mutableListOf<InstrumentRow>()
            reader.forEachLine { line ->
                val c = line.split(",")
                if (c.size > need && c[iEx] == "NSE" && c[iSym].startsWith("NIFTY-") &&
                    (c[iInst] == "FUTIDX" || c[iInst] == "OPTIDX")) {
                    out += InstrumentRow(
                        securityId = c[iId].trim(), symbol = c[iSym].trim(), instrument = c[iInst].trim(),
                        expiry = c[iExp].trim().take(10),
                        strike = if (iStrike >= 0) c[iStrike].trim().toDoubleOrNull() ?: 0.0 else 0.0,
                        optType = if (iType >= 0 && c[iInst] == "OPTIDX") c[iType].trim() else "",
                    )
                }
            }
            out
        }
    }
}
