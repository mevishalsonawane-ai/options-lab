package com.optionslab.engine

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KiteTicksTest {
    private fun packet(len: Int, fill: ByteBuffer.() -> Unit): ByteArray = ByteBuffer.allocate(len).apply(fill).array()

    private fun message(vararg packets: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(2 + packets.sumOf { 2 + it.size })
        b.putShort(packets.size.toShort())
        packets.forEach { b.putShort(it.size.toShort()); b.put(it) }
        return b.array()
    }

    // An NFO option token (segment 2) and the NIFTY BANK index token (segment 9).
    private val option = 12_345_602L
    private val bank = 260_105L

    @Test fun ltpPacket() {
        val t = KiteTicks.parse(message(packet(8) { putInt(option.toInt()); putInt(21_255) })).single()
        assertEquals(option, t.token)
        assertEquals(212.55, t.last)
    }

    @Test fun indexQuote() {
        val p = packet(32) {
            putInt(bank.toInt()); putInt(5_523_010); putInt(5_540_000); putInt(5_500_000); putInt(5_510_000); putInt(5_490_000); putInt(0)
            putInt(1_790_000_000)
        }
        val t = KiteTicks.parse(message(p)).single()
        assertEquals(55230.10, t.last, 1e-9)
        assertEquals(55400.0, t.high, 1e-9)
        assertEquals(54900.0, t.close, 1e-9)
        assertEquals(1_790_000_000L, t.exchangeTime)
        assertTrue(!t.tradable)
        assertEquals((55230.10 - 54900.0) / 54900.0, t.changePct, 1e-12)
    }

    @Test fun fullPacketWithDepth() {
        val p = packet(184) {
            putInt(option.toInt()); putInt(21_255); putInt(15); putInt(21_000); putInt(123_456); putInt(900); putInt(800)
            putInt(20_000); putInt(22_000); putInt(19_500); putInt(20_500)          // open high low close
            putInt(1_790_000_000); putInt(654_321); putInt(700_000); putInt(600_000); putInt(1_790_000_001)
            // depth: best bid 212.50, best offer 212.60
            putInt(75); putInt(21_250); putShort(3); putShort(0)
            repeat(4) { putInt(0); putInt(0); putShort(0); putShort(0) }
            putInt(150); putInt(21_260); putShort(2); putShort(0)
        }
        val t = KiteTicks.parse(message(p)).single()
        assertEquals(212.55, t.last)
        assertEquals(123_456L, t.volume)
        assertEquals(200.0, t.open)
        assertEquals(205.0, t.close)
        assertEquals(654_321L, t.oi)
        assertEquals(212.50, t.bid)
        assertEquals(212.60, t.ask)
    }

    @Test fun severalPacketsAndHeartbeat() {
        val a = packet(8) { putInt(option.toInt()); putInt(100) }
        val b = packet(8) { putInt((option + 256).toInt()); putInt(200) }
        assertEquals(listOf(1.0, 2.0), KiteTicks.parse(message(a, b)).map { it.last })
        assertTrue(KiteTicks.parse(byteArrayOf(0)).isEmpty())            // heartbeat
        assertTrue(KiteTicks.parse(byteArrayOf(0, 3, 0, 8, 1)).isEmpty()) // truncated: nothing invented
    }

    @Test fun currencyDivisor() {
        assertEquals(10_000_000.0, KiteTicks.divisor(0x0103))
        assertEquals(100.0, KiteTicks.divisor(option))
    }

    @Test fun controlMessages() {
        assertEquals("""{"a":"subscribe","v":[1,2]}""", KiteTicks.subscribe(listOf(1L, 2L)))
        assertEquals("""{"a":"mode","v":["full",[1]]}""", KiteTicks.mode("full", listOf(1L)))
    }
}
