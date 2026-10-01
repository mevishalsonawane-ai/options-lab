package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.gold.GoldLiquidity
import com.optionslab.engine.orb.Bar
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** IraGoldAlgo's paper strategy, run pass by pass on a made-up gold chart (no network). */
@RunWith(RobolectricTestRunner::class)
class GoldPaperTest : RobolectricTest() {
    private val monday = LocalDate.of(2026, 9, 7)
    private lateinit var hours: List<Bar>
    private lateinit var minutes: List<Bar>

    @Before fun up() {
        val t = ArrayList<LocalDateTime>()
        var d = monday
        while (t.size < 400) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) for (h in 7 until 21) t += d.atTime(h, 0)
            d = d.plusDays(1)
        }
        val n = t.size
        val c = (0 until n).map { 2400 + 40 * sin(it / 9.0) + 25 * sin(it / 3.7) + 10 * sin(it / 1.3) }
        val o = (0 until n).map { if (it == 0) c[0] else c[it - 1] }
        val h = (0 until n).map { maxOf(o[it], c[it]) + 3 + 8 * abs(sin(it / 2.3)) }
        val l = (0 until n).map { minOf(o[it], c[it]) - 3 - 8 * abs(cos(it / 2.9)) }
        hours = (0 until n).map { Bar(t[it], o[it], h[it], l[it], c[it]) }
        // Each hour as 60 minutes: the open, the high at :20, the low at :40, the close at :59.
        minutes = hours.flatMap { b ->
            (0 until 60).map { m ->
                val px = when { m < 20 -> b.open; m < 40 -> (b.open + b.close) / 2; else -> b.close }
                Bar(b.start.plusMinutes(m.toLong()), px, if (m == 20) b.high else px, if (m == 40) b.low else px, px)
            }
        }
        GoldPaper.testMinutes = { now -> minutes.filter { !it.start.plusMinutes(1).isAfter(now) } }
        runBlocking { GoldPaper.reset(1_000.0); GoldPaper.setLots(0.01); GoldPaper.setArmed(false) }
    }

    @After fun down() { GoldPaper.testMinutes = null; GoldPaper.testNow = null }

    private fun at(t: LocalDateTime) { GoldPaper.testNow = t; runBlocking { GoldPaper.tick() } }

    @Test fun yahooChartJsonBecomesUtcBarsSkippingGaps() {
        val json = JSONObject("""{"chart":{"result":[{"meta":{"symbol":"GC=F"},"timestamp":[1790546400,1790550000,1790553600],
            "indicators":{"quote":[{"open":[4180.0,null,4185.5],"high":[4190.0,null,4188.0],"low":[4175.0,null,4181.0],"close":[4182.8,null,4186.2]}]}}],"error":null}}""")
        val b = GoldPaper.parse(json)
        assertEquals(2, b.size)
        assertEquals(LocalDateTime.of(2026, 9, 27, 22, 0), b[0].start)        // 1790546400 s, UTC
        assertEquals(4186.2, b[1].close, 1e-9)
    }

    @Test fun theStatusSaysWhenTheNextDecisionIsInUtcAndIst() {
        val mon = LocalDate.of(2026, 9, 28)
        assertEquals("11:10 UTC (16:40 IST)", GoldPaper.nextDecision(mon.atTime(10, 26)))
        assertEquals("the 03:00 close is decided at 03:10", "03:10 UTC (08:40 IST)", GoldPaper.nextDecision(mon.atTime(3, 0)))
        assertEquals("04:10 UTC (09:40 IST)", GoldPaper.nextDecision(mon.atTime(3, 10)))
        assertEquals("the 21:00 break is skipped", "22:10 UTC (03:40 IST)", GoldPaper.nextDecision(mon.atTime(20, 30)))
        assertEquals("not midnight", "Tue 01:10 UTC (06:40 IST)", GoldPaper.nextDecision(mon.atTime(23, 30)))
        assertEquals("the weekend", "Mon 01:10 UTC (06:40 IST)", GoldPaper.nextDecision(LocalDate.of(2026, 10, 2).atTime(20, 0)))
        // Armed at 10:26 (too late for the 10:00 decision): it says when it decides next, not "no entry".
        at(mon.plusDays(14).atTime(10, 26))
        runBlocking { GoldPaper.setArmed(true) }
        at(mon.plusDays(14).atTime(10, 27))
        assertEquals(GoldPaper.WAITING, GoldPaper.book.value.status)
    }

    @Test fun aCandleCheckedTooLateIsSaidToBeMissedNotPassedOverSilently() {
        // A candle mid-history whose next hour may take an entry, with the hour after it on the chart.
        val i = (60 until hours.size - 2).first { hours[it + 1].start == hours[it].start.plusHours(1) && GoldLiquidity.mayEnterAt(hours[it + 1].start) }
        val bar = hours[i]
        // Armed half an hour into the candle: the one before it came before the arming, so it is not "missed".
        at(bar.start.plusMinutes(30))
        runBlocking { GoldPaper.setArmed(true) }
        at(bar.start.plusMinutes(31))
        assertTrue(GoldPaper.book.value.status, GoldPaper.book.value.status == GoldPaper.WAITING)
        // The phone sleeps through the candle's close; the next check is 35 minutes after it.
        val late = bar.start.plusMinutes(95)
        at(late)
        assertEquals("Missed the ${GoldPaper.when_(bar.start)} candle: the phone checked 35 min late.",
            GoldPaper.book.value.status)
        assertNull("no buy on a stale candle", GoldPaper.book.value.position)
    }

    @Test fun unarmedItOnlyFollowsThePrice() {
        at(monday.plusDays(14).atTime(12, 0, 30))
        val b = GoldPaper.book.value
        assertNotNull(b.price)
        assertNull(b.position)
        assertTrue(b.trades.isEmpty())
    }

    @Test fun armedItBuysOnABreakAndSellsByTheWeekendCut() {
        // The first buy signal on a candle whose next open may take an entry, after enough history.
        val (i, sig) = (60 until hours.size).asSequence().mapNotNull { i ->
            val entry = hours[i].start.plusHours(1)
            if (!GoldLiquidity.mayEnterAt(entry)) null else GoldLiquidity.signal(hours.subList(0, i + 1))?.let { i to it }
        }.first()
        val bar = hours[i]
        at(bar.start.plusMinutes(55))                               // the candle still forming: nothing decided
        runBlocking { GoldPaper.setArmed(true) }
        at(bar.start.plusMinutes(59))
        assertNull(GoldPaper.book.value.position)
        at(bar.start.plusMinutes(60).plusSeconds(30))               // half a minute after it closed
        val pos = GoldPaper.book.value.position
        assertNotNull("bought on the ${bar.start} candle", pos)
        assertEquals(sig.level, pos!!.level, 1e-9)
        assertEquals(GoldLiquidity.buyPrice(bar.close), pos.entry, 1e-9)
        // The same candle is never decided twice.
        at(bar.start.plusMinutes(61))
        assertEquals(pos, GoldPaper.book.value.position)
        // Pass every five minutes until it is sold; held overnight if need be, sold by Friday's cut-off at the latest.
        var t = bar.start.plusMinutes(65)
        val cut = GoldLiquidity.weekendCut(pos.entryTime)
        while (GoldPaper.book.value.position != null && !t.isAfter(cut.plusMinutes(5))) { at(t); t = t.plusMinutes(5) }
        val b = GoldPaper.book.value
        assertNull(b.position)
        val tr = b.trades.single()
        assertTrue(tr.why, tr.why in setOf("next_liquidity", "failed_break", "new_liquidity", "cut_off"))
        assertFalse(tr.exitTime.isAfter(cut.plusMinutes(5)))
        assertEquals(GoldLiquidity.pnl(tr.entry, tr.exit, 0.01), tr.pnl, 1e-9)
        assertEquals(1_000.0 + tr.pnl, b.balance, 1e-9)
    }
}
