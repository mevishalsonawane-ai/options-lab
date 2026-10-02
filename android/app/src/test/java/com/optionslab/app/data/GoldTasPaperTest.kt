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
        // Trading hours from Monday: 120 falling hours, then 45 rising ones.
        val steps = List(fallHours) { if (it % 3 == 2) 2.0 else -4.0 } + List(45) { if (it % 3 == 2) -2.0 else 8.0 }
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

    @Test fun armedItBuysTheTurnAndSellsInParts() {
        after(fallHours - 5)
        runBlocking { GoldTasPaper.setArmed(true) }
        after(fallHours - 4)
        assertEquals(false, GoldTasPaper.book.value.up)
        assertNull(GoldTasPaper.book.value.position)
        assertTrue(GoldTasPaper.book.value.status, GoldTasPaper.book.value.status.startsWith("Tracker down"))
        var bought: Int? = null
        for (i in fallHours - 3 until hourStarts.size - 1) {
            after(i)
            if (bought == null && GoldTasPaper.book.value.position != null) bought = i
        }
        assertNotNull("bought once the tracker turned up", bought)
        assertTrue("within the late window of the turn", bought!! - fallHours <= GoldTas.LATE_BARS)
        val b = GoldTasPaper.book.value
        assertNotNull(b.usedTurn)
        assertTrue(b.lastSignal!!, b.lastSignal!!.contains("tracker up"))
        // Sold in parts at the targets; every part is this arm's, and the parts add up to the lot bought.
        assertTrue(b.trades.map { it.why }.toString(), b.trades.first().why == "tas_t1")
        val held = b.position?.left ?: 0.0
        assertEquals(0.01, b.trades.sumOf { it.lots } + held, 1e-9)
        b.trades.forEach { tr ->
            assertEquals(GoldTasPaper.NAME, GoldPaper.arm(tr))
            assertEquals(GoldLiquidity.pnl(tr.entry, tr.exit, tr.lots), tr.pnl, 1e-9)
            assertTrue(tr.pnl > 0)
        }
        b.position?.let { assertEquals("after the first target the stop is the buy price", it.entry, it.stop, 1e-9) }
        assertTrue(GoldPaper.book.value.trades.isEmpty())
        // One buy per up-turn: nothing more is bought while the same turn lasts.
        assertEquals(1, b.trades.map { it.entryTime }.toSet().size)
    }

    @Test fun notArmedItNeverBuys() {
        for (i in fallHours - 3 until hourStarts.size - 1) after(i)
        assertNull(GoldTasPaper.book.value.position)
        assertTrue(GoldTasPaper.book.value.trades.isEmpty())
        assertFalse(GoldTasPaper.book.value.armed)
    }
}
