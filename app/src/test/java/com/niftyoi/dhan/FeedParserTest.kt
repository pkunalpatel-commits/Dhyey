package com.niftyoi.dhan

import com.niftyoi.dhan.domain.calculations.FeedParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FeedParserTest {
    private fun header(code: Int, len: Int, seg: Int, id: Int): ByteBuffer {
        val b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        b.put(code.toByte()); b.putShort(len.toShort()); b.put(seg.toByte()); b.putInt(id)
        return b
    }

    @Test fun parsesTicker() {
        val b = header(2, 16, 0, 13); b.putFloat(25123.5f); b.putInt(1_700_000_000)
        val t = FeedParser.parseAll(b.array()).single()
        assertEquals(2, t.code); assertEquals(13L, t.securityId)
        assertEquals(25123.5, t.ltp!!, 0.001)
        assertEquals(1_700_000_000L, t.ltt)
    }

    @Test fun parsesOiPacket() {
        val b = header(5, 12, 2, 49081); b.putInt(1_234_500)
        val t = FeedParser.parseAll(b.array()).single()
        assertEquals(1_234_500L, t.oi); assertEquals(49081L, t.securityId)
    }

    @Test fun parsesFullPacketOiAndVolume() {
        val b = header(8, 62, 2, 777)
        b.position(8); b.putFloat(120.25f); b.putShort(75); b.putInt(1_700_000_001); b.putFloat(119.0f)
        b.putInt(5000) // volume @22
        b.putInt(100); b.putInt(200) // sell/buy qty
        b.putInt(987_654) // OI @34
        val t = FeedParser.parseAll(b.array()).single()
        assertEquals(120.25, t.ltp!!, 0.001)
        assertEquals(5000L, t.volume); assertEquals(987_654L, t.oi)
    }

    @Test fun parsesPrevCloseAndDisconnect() {
        val p = header(6, 16, 2, 5); p.putFloat(100.0f); p.putInt(4242)
        val t = FeedParser.parseAll(p.array()).single()
        assertEquals(100.0, t.prevClose!!, 0.001); assertEquals(4242L, t.prevOi)
        val d = header(50, 10, 0, 0); d.putShort(805)
        assertEquals(805, FeedParser.parseAll(d.array()).single().disconnectReason)
    }

    @Test fun splitsConcatenatedPackets() {
        val a = header(2, 16, 0, 13); a.putFloat(1f); a.putInt(1)
        val b = header(5, 12, 2, 9); b.putInt(55)
        val all = FeedParser.parseAll(a.array() + b.array())
        assertEquals(2, all.size); assertEquals(55L, all[1].oi)
    }

    @Test fun ignoresShortAndUnknown() {
        assertTrue(FeedParser.parseAll(ByteArray(4)).isEmpty())
        assertTrue(FeedParser.parseAll(header(99, 12, 0, 1).array()).isEmpty())
    }
}
