package com.niftyoi.dhan.data.dhan

import com.niftyoi.dhan.domain.calculations.FeedParser
import com.niftyoi.dhan.domain.models.FeedTick
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class FeedState { STOPPED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

data class FeedStatus(val state: FeedState = FeedState.STOPPED, val detail: String = "", val attempt: Int = 0)

data class LiveQuote(val ltp: Double? = null, val oi: Long? = null, val volume: Long? = null, val prevClose: Double? = null, val prevOi: Long? = null, val receivedMs: Long = 0)

/** Subscription: segment is a Dhan segment string (IDX_I, NSE_FNO), mode 15 ticker / 17 quote / 21 full. */
data class Sub(val segment: String, val securityId: String, val requestCode: Int)

/**
 * DhanHQ v2 WebSocket market feed with automatic reconnection (exponential backoff 1s..30s) and a
 * silence watchdog. The access token is part of the connection URL by Dhan's protocol; it is never logged.
 */
class LiveFeed(private val creds: CredentialStore, private val scope: CoroutineScope) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _status = MutableStateFlow(FeedStatus())
    val status: StateFlow<FeedStatus> = _status.asStateFlow()

    private val _quotes = MutableStateFlow<Map<Long, LiveQuote>>(emptyMap())
    val quotes: StateFlow<Map<Long, LiveQuote>> = _quotes.asStateFlow()

    private val _ticks = MutableSharedFlow<FeedTick>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val ticks: SharedFlow<FeedTick> = _ticks.asSharedFlow()

    private val _lastMessageMs = MutableStateFlow<Long?>(null)
    val lastMessageMs: StateFlow<Long?> = _lastMessageMs.asStateFlow()

    @Volatile private var wanted = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var subs: List<Sub> = emptyList()
    private var job: Job? = null

    fun start() {
        if (wanted) return
        wanted = true
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        wanted = false
        job?.cancel(); job = null
        ws?.close(1000, "bye"); ws = null
        _status.value = FeedStatus(FeedState.STOPPED)
    }

    /** Replace the subscription set. Sends immediately when connected. */
    fun setSubscriptions(list: List<Sub>) {
        val old = subs
        subs = list
        val w = ws ?: return
        if (_status.value.state != FeedState.CONNECTED) return
        val removed = old.filter { o -> list.none { it.segment == o.segment && it.securityId == o.securityId } }
        if (removed.isNotEmpty()) sendMessages(w, removed, unsubscribe = true)
        val added = list.filter { n -> old.none { it.segment == n.segment && it.securityId == n.securityId && it.requestCode == n.requestCode } }
        if (added.isNotEmpty()) sendMessages(w, added, unsubscribe = false)
    }

    private fun sendMessages(w: WebSocket, items: List<Sub>, unsubscribe: Boolean) {
        items.groupBy { it.requestCode }.forEach { (code, group) ->
            group.chunked(100).forEach { chunk ->
                val list = JSONArray()
                chunk.forEach { list.put(JSONObject().put("ExchangeSegment", it.segment).put("SecurityId", it.securityId)) }
                val rc = if (unsubscribe) code + 1 else code // 16/18/22 are the unsubscribe codes
                w.send(JSONObject().put("RequestCode", rc).put("InstrumentCount", chunk.size).put("InstrumentList", list).toString())
            }
        }
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (wanted && currentCoroutineContext().isActive) {
            if (!creds.isConfigured) {
                _status.value = FeedStatus(FeedState.ERROR, "Credentials missing"); delay(3000); continue
            }
            _status.value = FeedStatus(if (attempt == 0) FeedState.CONNECTING else FeedState.RECONNECTING, "", attempt)
            val closed = CompletableDeferred<String>()
            val url = "wss://api-feed.dhan.co?version=2&token=${creds.accessToken}&clientId=${creds.clientId}&authType=2"
            val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                    _status.value = FeedStatus(FeedState.CONNECTED)
                    _lastMessageMs.value = System.currentTimeMillis()
                    sendMessages(webSocket, subs, unsubscribe = false)
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    _lastMessageMs.value = System.currentTimeMillis()
                    for (t in FeedParser.parseAll(bytes.toByteArray())) handle(t, closed)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); closed.complete("closed $code") }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete("closed $code") }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // message only; the request URL (which contains the token) is never included
                    closed.complete("failure: ${t.javaClass.simpleName}${response?.code?.let { " HTTP $it" } ?: ""}")
                }
            })
            ws = socket
            // silence watchdog: reconnect when connected but nothing arrives for 45 s
            val watchdog = scope.launch {
                while (isActive) {
                    delay(5_000)
                    val last = _lastMessageMs.value ?: continue
                    if (_status.value.state == FeedState.CONNECTED && System.currentTimeMillis() - last > 45_000) {
                        closed.complete("silent"); break
                    }
                }
            }
            val why = closed.await()
            watchdog.cancel()
            socket.cancel()
            ws = null
            if (!wanted) break
            attempt++
            val backoff = minOf(30_000L, 1_000L shl minOf(attempt, 5))
            _status.value = FeedStatus(FeedState.RECONNECTING, why, attempt)
            delay(backoff)
        }
    }

    private fun handle(t: FeedTick, closed: CompletableDeferred<String>) {
        if (t.code == FeedParser.DISCONNECT) {
            val msg = when (t.disconnectReason) {
                805 -> "Too many connections / websockets"
                806 -> "Data API subscription required"
                807 -> "Access token expired"
                808 -> "Authentication failed"
                809 -> "Access token invalid"
                else -> "Server disconnect (${t.disconnectReason})"
            }
            _status.value = FeedStatus(FeedState.ERROR, msg)
            closed.complete(msg)
            return
        }
        val now = System.currentTimeMillis()
        _quotes.update { m ->
            val old = m[t.securityId] ?: LiveQuote()
            m + (t.securityId to old.copy(
                ltp = t.ltp ?: old.ltp, oi = t.oi ?: old.oi, volume = t.volume ?: old.volume,
                prevClose = t.prevClose ?: old.prevClose, prevOi = t.prevOi ?: old.prevOi, receivedMs = now,
            ))
        }
        _ticks.tryEmit(t)
    }
}
