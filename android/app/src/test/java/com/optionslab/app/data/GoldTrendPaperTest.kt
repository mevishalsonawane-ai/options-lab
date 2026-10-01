package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.gold.GoldLiquidity
import com.optionslab.engine.gold.GoldTrend
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** IraGoldAlgo's trend arm, run pass by pass (through GoldPaper's pass) on a made-up gold chart: a fall, a rise, a crash. */
@RunWith(RobolectricTestRunner::class)
class GoldTrendPaperTest : RobolectricTest() {
    private val monday = LocalDate.of(2026, 9, 7)
    private lateinit var hours: List<Bar>
    private lateinit var minutes: List<Bar>
    private val FALL = 200
    private val RISE = 150

    @Before fun up() {
        val t = ArrayList<LocalDateTime>()
        var d = monday
        while (t.size < FALL + RISE + 80) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY)
                for (h in 0 until 24) if (h != 21 && !(d.dayOfWeek == DayOfWeek.FRIDAY && h >= 21)) t += d.atTime(h, 0)
            d = d.plusDays(1)
        }
        var px = 2400.0
        hours = t.mapIndexed { i, s ->
            val o = px
            px += when { i < FALL -> -1.0; i < FALL + RISE -> 3.0; else -> -15.0 }
            Bar(s, o, maxOf(o, px) + 2, minOf(o, px) - 2, px)
        }
        minutes = hours.flatMap { b ->
            (0 until 60).map { m ->
                val p = b.open + (b.close - b.open) * m / 59.0
                Bar(b.start.plusMinutes(m.toLong()), p, if (m == 20) b.high else p, if (m == 40) b.low else p, p)
            }
        }
        GoldPaper.testMinutes = { now -> minutes.filter { !it.start.plusMinutes(1).isAfter(now) } }
        runBlocking { GoldPaper.reset(1_000.0); GoldPaper.setLots(0.01); GoldPaper.setArmed(false); GoldTrendPaper.setArmed(false); GoldTrendPaper.reset() }
    }

    @After fun down() { GoldPaper.testMinutes = null; GoldPaper.testNow = null }

    private fun at(t: LocalDateTime) { GoldPaper.testNow = t; runBlocking { GoldPaper.tick() } }

    @Test fun theNextDecisionIsTheNextFourHourCloseAfterTheFeedsDelay() {
        val mon = LocalDate.of(2026, 9, 28)
        assertEquals("12:10 UTC (17:40 IST)", GoldTrendPaper.nextDecision(mon.atTime(10, 0)))
        assertEquals("the 12:00 close is still pending at 12:05", "12:10 UTC (17:40 IST)", GoldTrendPaper.nextDecision(mon.atTime(12, 5)))
        assertEquals("16:10 UTC (21:40 IST)", GoldTrendPaper.nextDecision(mon.atTime(12, 10)))
        assertEquals("the night candle closes at midnight", "Tue 00:10 UTC (05:40 IST)", GoldTrendPaper.nextDecision(mon.atTime(21, 0)))
        assertEquals("Friday's last candle is decided on Monday", "Mon 00:10 UTC (05:40 IST)",
            GoldTrendPaper.nextDecision(LocalDate.of(2026, 10, 2).atTime(20, 30)))
    }

    @Test fun armedItWaitsForTheTrendBuysOnTheRiseAndTheLockSellsInTheCrash() {
        at(hours[150].start.plusMinutes(10))
        runBlocking { GoldTrendPaper.setArmed(true) }
        at(hours[150].start.plusMinutes(11))
        assertEquals(false, GoldTrendPaper.book.value.up)
        var bought: GoldTrendPaper.Position? = null
        var afterLock = false
        for (h in hours.drop(151)) {
            at(h.start.plusMinutes(10))
            val b = GoldTrendPaper.book.value
            if (bought == null) bought = b.position
            if (b.trades.isNotEmpty() && b.trades.last().why == "giveback" && b.up == true) {
                afterLock = true
                assertNull("no new buy after the lock while the trend is still up", b.position)
            }
        }
        assertNotNull("bought on the rise", bought)
        val pos = bought!!
        assertTrue("not in the fall: ${pos.entryTime}", !pos.entryTime.isBefore(hours[FALL].start))
        assertTrue("on a 4-hour decision", pos.entryTime.minute == 10 && pos.entryTime.hour % 4 == 0)
        val b = GoldTrendPaper.book.value
        val tr = b.trades.first()
        assertTrue(tr.why, tr.why in setOf("giveback", "trend_down"))
        assertTrue("sold in the crash", !tr.exitTime.isBefore(hours[FALL + RISE].start))
        assertTrue("a profit kept", tr.pnl > 0)
        assertEquals(GoldLiquidity.pnl(tr.entry, tr.exit, 0.01), tr.pnl, 1e-9)
        if (tr.why == "giveback") {
            assertTrue(afterLock || b.up == false)
            // Sold where the lock was: 4 ATRs under the top of the rise (the bid), or lower if the price jumped past it.
            val top = GoldLiquidity.sellPrice(hours[FALL + RISE - 1].high)
            assertTrue("${tr.exit} vs top $top", tr.exit <= top - GoldTrend.LOCK_GIVEBACK * pos.atr + 1e-9)
        }
        // Its trades count on the shared paper account, and are named for the arm.
        assertEquals(GoldTrendPaper.NAME, GoldPaper.arm(tr))
        assertTrue(GoldPaper.book.value.trades.isEmpty())
    }

    @Test fun armedInAnUptrendItBuysOnlyAtTheNextFourHourClose() {
        val i = FALL + 60
        at(hours[i].start.plusMinutes(10))
        runBlocking { GoldTrendPaper.setArmed(true) }
        at(hours[i].start.plusMinutes(11))
        assertEquals(true, GoldTrendPaper.book.value.up)
        assertNull(GoldTrendPaper.book.value.position)
        assertTrue(GoldTrendPaper.book.value.status, GoldTrendPaper.book.value.status.startsWith("Armed: the trend is up"))
        var j = i + 1
        while (GoldTrendPaper.book.value.position == null && j < i + 10) { at(hours[j].start.plusMinutes(10)); j++ }
        val pos = GoldTrendPaper.book.value.position
        assertNotNull(pos)
        assertEquals(0, pos!!.entryTime.hour % 4)
        assertEquals(0.01, pos.lots, 1e-12)
        // A reset of the paper account clears the arm's trade too; the switch stays.
        runBlocking { GoldPaper.reset(2_000.0) }
        assertNull(GoldTrendPaper.book.value.position)
        assertTrue(GoldTrendPaper.book.value.armed)
    }
}
