package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.mcx.McxContract
import com.optionslab.engine.mcx.McxNr7Rules
import com.optionslab.engine.mcx.McxSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * NR7 crude, RECORD ONLY (21 Oct; Boss: "Implement nr7, current exit"): off by default; switched on, it finds the NR7 day
 * from the future's minutes, books the would-be 1-ITM call at the option's next minute open after the break, and closes it
 * by the research's exits (edge, future stop, time) with real charges - and never places an order: its code has no path
 * to the paper account, the live mirror or the broker.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class McxNr7RecordTest : RobolectricTest() {
    private val today = LocalDate.of(2026, 10, 21)              // a Wednesday; the 7 sessions before: 12-16 and 19-20 Oct
    private val optExpiry = LocalDate.of(2026, 11, 16)
    private val futKey = "MCX_FO|800001"
    private fun ceKey(k: Int) = "MCX_FO|81${k}"
    private fun peKey(k: Int) = "MCX_FO|82${k}"

    private class Fake(var now: LocalDateTime, val contracts: List<McxContract>, val bars: MutableMap<String, MutableList<Upstox.Bar>>) : McxNr7Record.Feed {
        override suspend fun contracts() = contracts
        override suspend fun bars(key: String, from: LocalDate, to: LocalDate, now: LocalDateTime): List<Upstox.Bar> {
            val nowSec = now.atZone(IST).toEpochSecond()
            return bars[key].orEmpty().filter { !it.istDate.isBefore(from) && !it.istDate.isAfter(to) && it.epochSecond + 60 <= nowSec }
                .sortedBy { it.epochSecond }
        }
        override fun calendar() = McxSession.Calendar()
        override fun now() = now
        override fun isOpen() = calendar().isOpen(now)
    }

    private lateinit var feed: Fake

    private fun ep(d: LocalDate, t: LocalTime) = d.atTime(t).atZone(IST).toEpochSecond()
    private fun bar(d: LocalDate, t: LocalTime, o: Double, h: Double, l: Double, c: Double) = Upstox.Bar(ep(d, t), o, h, l, c, 1, 0)
    private fun flat(d: LocalDate, t: LocalTime, c: Double) = bar(d, t, c, c, c, c)

    private fun contracts(): List<McxContract> {
        val out = ArrayList<McxContract>()
        out += McxContract(1, 800001, "CRUDEOIL26NOVFUT", "CRUDEOIL", LocalDate.of(2026, 11, 19), 0.0, null, 1.0, 100)
        for (k in 5800..6200 step 50) {
            out += McxContract(10L + k, ("81$k").toLong(), "CRUDEOIL26NOV${k}CE", "CRUDEOIL", optExpiry, k.toDouble(), Right.CE, 0.1, 100)
            out += McxContract(20L + k, ("82$k").toLong(), "CRUDEOIL26NOV${k}PE", "CRUDEOIL", optExpiry, k.toDouble(), Right.PE, 0.1, 100)
        }
        return out                                              // no CRUDEOILM chain: that book decides "no option chain"
    }

    /** Seven sessions of the future: six with a 100-point range, the last ([narrow]) 20 points (an NR7 day) or 120 (not). */
    private fun history(narrow: Boolean = true): MutableList<Upstox.Bar> {
        val days = listOf(12, 13, 14, 15, 16, 19, 20).map { LocalDate.of(2026, 10, it) }
        val out = ArrayList<Upstox.Bar>()
        for ((i, d) in days.withIndex()) {
            val r = if (i == days.lastIndex) (if (narrow) 20.0 else 120.0) else 100.0 + i
            out += bar(d, LocalTime.of(10, 0), 6000.0, 6000.0 + r / 2, 6000.0 - r / 2, 6005.0)
            out += bar(d, LocalTime.of(11, 0), 6000.0, 6000.0, 6000.0, 5995.0)
        }
        return out
    }

    private fun at(t: LocalTime, d: LocalDate = today) { feed.now = d.atTime(t) }

    @Before fun setUp() {
        feed = Fake(today.atTime(9, 0), contracts(), HashMap())
        McxNr7Record.resetForTest(context, feed)
    }

    private fun tick() = runBlocking { McxNr7Record.tick() }

    @Test fun offByDefaultItDecidesNothing() {
        feed.bars[futKey] = history()
        at(LocalTime.of(10, 30))
        tick()
        val v = runBlocking { McxNr7Record.refresh() }
        assertFalse(v.on)
        assertTrue(v.status.isEmpty() && v.open.isEmpty() && v.closed.isEmpty())
    }

    @Test fun anNr7BreakBooksTheWouldBeCallAndTheEdgeClosesIt() {
        val fut = history()
        fut += flat(today, LocalTime.of(10, 0), 6000.0)
        fut += flat(today, LocalTime.of(10, 1), 6010.0)             // the first close above 6005: the break (call)
        fut += flat(today, LocalTime.of(10, 2), 6020.0)
        fut += flat(today, LocalTime.of(10, 3), 6060.0)
        fut += flat(today, LocalTime.of(10, 4), 6110.0)             // ATM 6100: the 5950 call three strikes in the money
        feed.bars[futKey] = fut
        feed.bars[ceKey(5950)] = mutableListOf(
            bar(today, LocalTime.of(10, 2), 100.0, 104.0, 99.0, 103.0),
            bar(today, LocalTime.of(10, 3), 103.0, 125.0, 103.0, 124.0),
            bar(today, LocalTime.of(10, 4), 124.0, 182.0, 124.0, 180.0),
        )
        assertTrue(runBlocking { McxNr7Record.setOn(true) }.contains("places no order"))
        at(LocalTime.of(10, 6))
        tick()
        val v = McxNr7Record.view.value
        assertTrue(v.on)
        val t = v.closed.single()
        assertEquals("CRUDEOIL26NOV5950CE", t.symbol)
        assertEquals(100.0, t.fill, 1e-9)                           // the next minute's open after the 10:01 break
        assertEquals("edge", t.why)
        assertEquals(180.0, t.exit!!, 1e-9)
        assertEquals(LocalDate.of(2026, 10, 20), t.nr7Day)
        assertEquals(5995.0, t.futureStop, 1e-9)
        assertTrue(t.cost > 0)
        assertEquals((180.0 - 100.0) * 100 - t.cost, t.net!!, 1e-6)
        assertTrue(v.status["CRUDEOILM"]!!.contains("no option chain"))
        // Once a day: a later pass books nothing more.
        at(LocalTime.of(11, 0)); tick()
        assertEquals(1, McxNr7Record.view.value.closed.size)
        // Kept in its own file, read again as after a restart.
        McxNr7Record.resetCacheForTest()
        val again = runBlocking { McxNr7Record.refresh() }
        assertTrue(again.on); assertEquals(t, again.closed.single())
    }

    @Test fun theFutureStopClosesItAtTheOptionsClose() {
        val fut = history()
        fut += flat(today, LocalTime.of(10, 1), 5990.0)             // the first close below 5995: the break (put)
        fut += flat(today, LocalTime.of(10, 2), 5992.0)
        fut += flat(today, LocalTime.of(10, 3), 6006.0)             // back above the NR7 day's high close (6005)
        feed.bars[futKey] = fut
        feed.bars[peKey(6050)] = mutableListOf(
            bar(today, LocalTime.of(10, 2), 80.0, 82.0, 79.0, 81.0),
            bar(today, LocalTime.of(10, 3), 81.0, 81.0, 70.0, 72.0),
        )
        runBlocking { McxNr7Record.setOn(true) }
        at(LocalTime.of(10, 10)); tick()
        val t = McxNr7Record.view.value.closed.single()
        assertEquals(-1, t.side); assertEquals("CRUDEOIL26NOV6050PE", t.symbol)
        assertEquals("future_stop", t.why); assertEquals(72.0, t.exit!!, 1e-9)
        assertTrue(t.net!! < 0)
    }

    @Test fun aHeldWouldBeTradeIsTimedOutAfterThreeSessionsAndNoNr7DayDecidesNothing() {
        val fut = history()
        fut += flat(today, LocalTime.of(10, 1), 6010.0)
        feed.bars[futKey] = fut
        feed.bars[ceKey(5950)] = mutableListOf(bar(today, LocalTime.of(10, 2), 100.0, 101.0, 99.0, 100.0))
        runBlocking { McxNr7Record.setOn(true) }
        at(LocalTime.of(10, 5)); tick()
        assertEquals(1, McxNr7Record.view.value.open.size)
        // Thursday and Friday: quiet; Friday's square-off (US summer time: close 23:30, out at 23:14) ends it.
        val fri = LocalDate.of(2026, 10, 23)
        for (d in listOf(LocalDate.of(2026, 10, 22), fri)) {
            fut += flat(d, LocalTime.of(10, 0), 6015.0)
            feed.bars[ceKey(5950)]!! += bar(d, LocalTime.of(10, 0), 101.0, 102.0, 100.0, 101.0)
        }
        at(LocalTime.of(12, 0), LocalDate.of(2026, 10, 22)); tick()
        assertEquals(1, McxNr7Record.view.value.open.size)
        at(LocalTime.of(23, 20), fri); tick()                        // MCX still open; past the square-off
        val t = McxNr7Record.view.value.closed.single()
        assertEquals("time", t.why); assertEquals(101.0, t.exit!!, 1e-9)
        // A day after a session that was not an NR7 day: nothing booked.
        McxNr7Record.resetForTest(context, Fake(today.atTime(10, 30), contracts(), hashMapOf(futKey to history(narrow = false))))
        runBlocking { McxNr7Record.setOn(true) }
        tick()
        assertTrue(McxNr7Record.view.value.status["CRUDEOIL"]!!.contains("not an NR7 day"))
        assertTrue(McxNr7Record.view.value.open.isEmpty())
    }

    @Test fun itsCodeHasNoOrderPath() {
        val src = File("src/main/java/com/optionslab/app/data/McxNr7Record.kt").readLines()
            .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }
            .joinToString("\n")
        for (bad in listOf("Paper.", "ArmLive", "Broker.", "TradeManagerHost", "FlowGate", "place(", "Kite")) {
            assertFalse("record only: no $bad", src.contains(bad))
        }
        assertTrue(McxNr7Rules.LABEL.contains("record only"))
    }
}
