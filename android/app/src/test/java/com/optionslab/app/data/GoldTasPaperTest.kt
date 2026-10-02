package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.gold.GoldLiquidity
import com.optionslab.engine.gold.GoldTas
import com.optionslab.engine.orb.Bar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

/** IraGoldAlgo's TAS arm, pass by pass through GoldPaper's pass, on made-up days: a long fall, then a rise. */
@RunWith(RobolectricTestRunner::class)
class GoldTasPaperTest : RobolectricTest() {
    private val monday = LocalDate.of(2026, 8, 31).atStartOfDay()
    private lateinit var minutes: List<Bar>
    private lateinit var hourStarts: List<LocalDateTime>
    private val fallHours = 120

    @Before fun up() {
        // Trading hours from Monday: 120 falling hours, 45 rising ones, then 25 falling again.
        val steps = List(fallHours) { if (it % 3 == 2) 2.0 else -4.0 } + List(45) { if (it % 3 == 2) -2.0 else 8.0 } +
            List(25) { if (it % 3 == 2) 2.0 else -8.0 }
        val hs = ArrayList<LocalDateTime>()
        var h = monday
        while (hs.size < steps.size) { if (GoldLiquidity.inSession(h)) hs += h; h = h.plusHours(1) }
        hourStarts = hs
        var px = 2400.0
        minutes = steps.flatMapIndexed { i, d ->
            val o = px; px += d; val c = px
            (0 until 60).map { m ->
                val p = o + (c - o) * m / 59.0
                Bar(hs[i].plusMinutes(m.toLong()), p, p + if (m == 15) 1.0 else 0.0, p - if (m == 45) 1.0 else 0.0, p)
            }
        }
        GoldPaper.testMinutes = { now -> minutes.filter { !it.start.plusMinutes(1).isAfter(now) } }
        runBlocking {
            GoldPaper.reset(1_000.0); GoldPaper.setLots(0.01); GoldPaper.setArmed(false)
            GoldTrendPaper.setArmed(false); GoldDipPaper.setArmed(false); GoldTasPaper.setArmed(false); GoldTasPaper.reset()
        }
    }

    @After fun down() { GoldPaper.testMinutes = null; GoldPaper.testNow = null; runBlocking { GoldTasPaper.setArmed(false); GoldTasPaper.reset() } }

    /** A pass 5 minutes after the [i]th hour closes. */
    private fun after(i: Int) { GoldPaper.testNow = hourStarts[i].plusMinutes(65); runBlocking { GoldPaper.tick() } }

    @Test fun armedItBuysTheTurnAndSellsWhenTheTrackerTurnsDown() {
        after(fallHours - 5)
        runBlocking { GoldTasPaper.setArmed(true) }
        after(fallHours - 4)
        assertEquals(false, GoldTasPaper.book.value.up)
        assertNull(GoldTasPaper.book.value.position)
        assertTrue(GoldTasPaper.book.value.status, GoldTasPaper.book.value.status.startsWith("Tracker down"))
        var bought: Int? = null
        for (i in fallHours - 3 until fallHours + 45) {
            after(i)
            if (bought == null && GoldTasPaper.book.value.position != null) bought = i
        }
        assertNotNull("bought once the tracker turned up", bought)
        assertTrue("within the late window of the turn", bought!! - fallHours <= GoldTas.LATE_BARS)
        var b = GoldTasPaper.book.value
        assertNotNull(b.usedTurn)
        assertTrue(b.lastSignal!!, b.lastSignal!!.contains("tracker up"))
        // No targets: the whole lot is still held at the top of the rise, the stop where it was put.
        val pos = assertNotNullPos(b.position)
        assertEquals(0.01, pos.left, 1e-12)
        assertEquals(0, pos.hit)
        assertTrue(b.trades.isEmpty())
        assertTrue(pos.stop < pos.entry)
        // The fall turns the tracker down: all of it sold, once, on this arm.
        for (i in fallHours + 45 until hourStarts.size - 1) after(i)
        b = GoldTasPaper.book.value
        assertNull(b.position)
        val tr = b.trades.single()
        assertTrue(tr.why, tr.why in setOf("tas_down", "tas_stop"))
        assertEquals(0.01, tr.lots, 1e-12)
        assertEquals(GoldTasPaper.NAME, GoldPaper.arm(tr))
        assertEquals(GoldLiquidity.pnl(tr.entry, tr.exit, tr.lots), tr.pnl, 1e-9)
        assertTrue(GoldPaper.book.value.trades.isEmpty())
    }

    private fun assertNotNullPos(p: GoldTasPaper.Position?): GoldTasPaper.Position { assertNotNull("a buy is held", p); return p!! }

    @Test fun notArmedItNeverBuys() {
        for (i in fallHours - 3 until hourStarts.size - 1) after(i)
        assertNull(GoldTasPaper.book.value.position)
        assertTrue(GoldTasPaper.book.value.trades.isEmpty())
        assertFalse(GoldTasPaper.book.value.armed)
    }
}
