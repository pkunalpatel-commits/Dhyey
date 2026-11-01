package com.niftyoi.dhan.domain.calculations

import com.niftyoi.dhan.domain.models.FeedTick
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses DhanHQ v2 binary market-feed packets (little-endian).
 * Header (8 bytes): code(1) length(2) segment(1) securityId(4).
 * Codes: 2 ticker, 4 quote, 5 OI, 6 prev-close, 8 full, 50 disconnect.
 */
object FeedParser {
    const val TICKER = 2; const val QUOTE = 4; const val OI = 5; const val PREV_CLOSE = 6; const val FULL = 8; const val DISCONNECT = 50

    fun parseAll(data: ByteArray): List<FeedTick> {
        val out = mutableListOf<FeedTick>()
        var off = 0
        while (off + 8 <= data.size) {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val len = bb.getShort(off + 1).toInt() and 0xFFFF
            val end = if (len >= 8 && off + len <= data.size) off + len else data.size
            parseOne(data.copyOfRange(off, end))?.let { out += it }
            if (end <= off) break
            off = end
        }
        return out
    }

    fun parseOne(d: ByteArray): FeedTick? {
        if (d.size < 8) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val code = d[0].toInt() and 0xFF
        val seg = d[3].toInt() and 0xFF
        val id = b.getInt(4).toLong() and 0xFFFFFFFFL
        fun f(o: Int) = if (d.size >= o + 4) b.getFloat(o).toDouble() else null
        fun i(o: Int) = if (d.size >= o + 4) b.getInt(o).toLong() else null
        return when (code) {
            TICKER -> FeedTick(code, seg, id, ltp = f(8), ltt = i(12))
            QUOTE -> FeedTick(code, seg, id, ltp = f(8), ltt = i(14), volume = i(22))
            OI -> FeedTick(code, seg, id, oi = i(8))
            PREV_CLOSE -> FeedTick(code, seg, id, prevClose = f(8), prevOi = i(12))
            FULL -> FeedTick(code, seg, id, ltp = f(8), ltt = i(14), volume = i(22), oi = i(34))
            DISCONNECT -> FeedTick(code, seg, id, disconnectReason = if (d.size >= 10) b.getShort(8).toInt() else null)
            else -> null
        }
    }
}
