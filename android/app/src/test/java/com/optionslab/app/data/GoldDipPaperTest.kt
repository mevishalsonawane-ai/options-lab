package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.gold.GoldLiquidity
import com.optionslab.engine.orb.Bar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

/** IraGoldAlgo's Dip arm, pass by pass through GoldPaper's pass, on a made-up day: two green hours, a dip that turns up. */
@RunWith(RobolectricTestRunner::class)
class GoldDipPaperTest : RobolectricTest() {
    private val day = LocalDate.of(2026, 9, 29)                     // a Tuesday
    private lateinit var minutes: List<Bar>

    @Before fun up() {
        // 30-minute moves from 00:00: quiet until 15:00, two green hours, a red half-hour then a green one (the set-up at
        // 17:30), then a gentle rise to the break.
        val steps = List(30) { if (it % 2 == 0) 1.0 else -1.0 } + listOf(2.0, 2.0, 2.0, 2.0, -1.0, 1.5) + List(6) { 0.5 }
        var px = 2400.0
        minutes = steps.flatMapIndexed { i, d ->
            val o = px; px += d; val c = px
            val start = day.atStartOfDay().plusMinutes(30L * i)
            (0 until 30).map { m ->
                val p = o + (c - o) * m / 29.0
                Bar(start.plusMinutes(m.toLong()), p, p + if (m == 10) 0.3 else 0.0, p - if (m == 20) 0.3 else 0.0, p)
            }
        }
        GoldPaper.testMinutes = { now -> minutes.filter { !it.start.plusMinutes(1).isAfter(now) } }
        runBlocking { GoldPaper.reset(1_000.0); GoldPaper.setLots(0.01); GoldPaper.setArmed(false); GoldDipPaper.setArmed(false); GoldDipPaper.reset() }
    }

    @After fun down() { GoldPaper.testMinutes = null; GoldPaper.testNow = null }

    private fun at(t: LocalDateTime) { GoldPaper.testNow = t; runBlocking { GoldPaper.tick() } }

    @Test fun armedItBuysTheDipAndSellsBeforeTheBreak() {
        at(day.atTime(17, 40))
        runBlocking { GoldDipPaper.setArmed(true) }
        at(day.atTime(17, 41))                                         // the candle already closed when it was armed: not traded
        assertEquals(GoldDipPaper.WAITING, GoldDipPaper.book.value.status)
        assertNull(GoldDipPaper.book.value.position)
        at(day.atTime(18, 5))                                          // the 17:30 candle (the dip turned up) has closed
        val pos = GoldDipPaper.book.value.position
        assertNotNull(GoldDipPaper.book.value.status, pos)
        assertEquals(0.01, pos!!.lots, 1e-12)
        assertTrue(pos.atr > 0)
        // The same candle is never bought twice; held while nothing fires.
        at(day.atTime(18, 10))
        assertEquals(pos.entryTime, GoldDipPaper.book.value.position?.entryTime)
        // Out before the daily break.
        at(day.atTime(20, 56))
        val b = GoldDipPaper.book.value
        assertNull(b.position)
        val tr = b.trades.single()
        assertTrue(tr.why, tr.why in setOf("dip_break", "dip_lock"))
        assertEquals(GoldLiquidity.pnl(tr.entry, tr.exit, 0.01), tr.pnl, 1e-9)
        assertEquals(GoldDipPaper.NAME, GoldPaper.arm(tr))
        assertTrue(GoldPaper.book.value.trades.isEmpty())
    }

    @Test fun withoutTheSetUpItDoesNotBuy() {
        at(day.atTime(14, 40))
        runBlocking { GoldDipPaper.setArmed(true) }
        at(day.atTime(14, 41))
        at(day.atTime(15, 5))
        assertNull(GoldDipPaper.book.value.position)
        assertTrue(GoldDipPaper.book.value.status, GoldDipPaper.book.value.status.startsWith("No dip set-up"))
    }
}
