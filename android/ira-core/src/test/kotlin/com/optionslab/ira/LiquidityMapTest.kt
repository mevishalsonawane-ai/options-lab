package com.optionslab.ira

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.LiquidityRules.Zone
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Liquidity 15+5's map in words: the levels each side, room or not, no data, outside hours, and the heads-up's limits. */
class LiquidityMapTest {
    private val day: LocalDate = LocalDate.of(2026, 10, 5)
    private val bars: List<Bar> = (0 until 50).map { Bar(day.atTime(9, 15).plusMinutes(5L * it), 54_100.0, 54_130.0, 54_090.0, 54_110.0) }

    private fun z(kind: String, side: Int, top: Double, bottom: Double, known: Int = 10, broken: Int = -1) =
        Zone(kind, side, top, bottom, known - 1, known).also { it.broken = broken }

    /** A pool on a swing high at 54,180 with the next level 54,260 above; a pool on a swing low at 54,000, nothing beyond. */
    private fun zones(nextAbove: Double = 54_260.0) = listOf(
        z("swing", 1, 54_180.0, 54_130.0), z("pool", 1, 54_180.0, 54_165.0), z("pool", 1, nextAbove, nextAbove - 10),
        z("swing", -1, 54_060.0, 54_000.0), z("pool", -1, 54_030.0, 54_000.0),
        // Taken, or not known yet at the last bar: never read.
        z("pool", 1, 54_150.0, 54_140.0, broken = 30), z("swing", 1, 54_140.0, 54_120.0, known = 60),
    )

    private fun read(price: Double, zs: List<Zone> = zones(), und: String = "BANKNIFTY", minutes: Int = 5, open: Boolean = true,
                     at: LocalDateTime = day.atTime(10, 31)) =
        LiquidityMap.read(bars, zs, und, minutes, price, at, open)
    private val t31 = day.atTime(10, 31, 40)

    @Test fun bothSidesWithRoom() {
        val r = read(54_120.0)
        assertEquals(LiquidityMap.State.OK, r.state)
        val up = r.above!!
        assertEquals(54_180.0, up.trigger!!.edge); assertEquals("pool on a swing high", up.trigger!!.name)
        assertEquals(54_260.0, up.target); assertEquals(80.0, up.room); assertTrue(up.enough)
        val down = r.below!!
        assertEquals(54_000.0, down.trigger!!.edge); assertNull(down.target); assertTrue(down.enough)
        val s = LiquidityMap.say(r, day.atTime(10, 32))
        assertEquals("BankNifty 5-min: price 54,120. Above: a close above 54,180 (pool on a swing high, 60 pts away) would buy a call; " +
            "room to the next level 54,260 is 80 pts - enough. Below: a close below 54,000 (pool on a swing low, 120 pts away) would buy a put; " +
            "no level beyond it, so room enough.", s)
    }

    @Test fun tooLittleRoomIsSaidAsSkipped() {
        val r = read(54_120.0, zones(nextAbove = 54_200.0))
        assertFalse(r.above!!.enough)
        assertEquals(20.0, r.above!!.room)
        val s = LiquidityMap.say(r, day.atTime(10, 32))
        assertTrue(s.contains("Above: a close above 54,180 (pool on a swing high, 60 pts away) would be a break, but room to the next level 54,200 is only 20 pts (needs 30) - it would be skipped."), s)
        // FINNIFTY's room is one 15-point index stop: 20 points is enough there.
        assertTrue(read(54_120.0, zones(nextAbove = 54_200.0), und = "FINNIFTY").above!!.enough)
    }

    @Test fun aNearerLevelAndALevelWithNoPoolOnASwing() {
        val zs = zones() + z("swing", 1, 54_150.0, 54_100.0)
        val s = LiquidityMap.say(read(54_120.0, zs), day.atTime(10, 32))
        assertTrue(s.contains("First comes the swing high at 54,150 (30 pts away)."), s)
        // Only a swing above: said, but a close through it would not trade.
        val only = listOf(z("swing", 1, 54_300.0, 54_250.0))
        val o = LiquidityMap.say(read(54_120.0, only), day.atTime(10, 32))
        assertTrue(o.contains("Above: the nearest level is 54,300 (swing high, 180 pts away); no pool sits on a swing there, so a close above it would not trade."), o)
        assertTrue(o.contains("Below: no active level."), o)
        // A plain pool, and a price already past a level its bar has not closed through.
        val p = LiquidityMap.say(read(54_200.0, listOf(z("pool", 1, 54_190.0, 54_185.0))), day.atTime(10, 32))
        assertTrue(p.contains("54,190 (pool, price already 10 pts past it, its bar not yet closed)"), p)
    }

    @Test fun noDataLoadingAndAnOldPrice() {
        val now = day.atTime(10, 32)
        assertEquals(LiquidityMap.State.NO_DATA, LiquidityMap.read(emptyList(), "BANKNIFTY", 5, now).state)
        assertEquals("BankNifty 5-min: no candles to read the levels from just now.", LiquidityMap.say(LiquidityMap.read(emptyList(), "BANKNIFTY", 5, now), now))
        val few = (0 until 60).map { Bar(day.atTime(9, 15).plusMinutes(it.toLong()), 1.0, 2.0, 0.5, 1.5) }
        val l = LiquidityMap.read(few, "FINNIFTY", 30, now)
        assertEquals(LiquidityMap.State.LOADING, l.state)
        assertTrue(LiquidityMap.say(l, now).startsWith("FinNifty 30-min: only 2 closed bars so far - the levels need 42"), LiquidityMap.say(l, now))
        // A price from an earlier day is said with its time.
        val old = LiquidityMap.say(read(54_120.0), day.plusDays(1).atTime(9, 1))
        assertTrue(old.startsWith("BankNifty 5-min: price 54,120 (as of 5 Oct 10:31)."), old)
        // Today's, more than 2 minutes old (the feed behind): with its time; within 2 minutes: none.
        val late = LiquidityMap.say(read(54_120.0), day.atTime(10, 34))
        assertTrue(late.startsWith("BankNifty 5-min: price 54,120 (as of 10:31)."), late)
        assertTrue(LiquidityMap.say(read(54_120.0), day.atTime(10, 33)).startsWith("BankNifty 5-min: price 54,120. "))
    }

    /** Three sessions of 1-minute bars on a wavy series. */
    private fun ones(): List<Bar> {
        val days = listOf(day.minusDays(3), day.minusDays(2), day)
        val n = days.size * 375
        val c = (0 until n).map { 54_000 + 120 * sin(it / 45.0) + 60 * sin(it / 17.0) + 20 * sin(it / 6.0) }
        return (0 until n).map {
            val o = if (it == 0) c[0] else c[it - 1]
            Bar(days[it / 375].atTime(9, 15).plusMinutes((it % 375).toLong()), o, maxOf(o, c[it]) + 4 + 6 * abs(sin(it / 2.3)),
                minOf(o, c[it]) - 4 - 6 * abs(cos(it / 2.9)), c[it])
        }
    }

    @Test fun readFromTheArmsMinutesAsTheArmFoldsThem() {
        val all = ones()
        val now = day.atTime(11, 2, 30)
        val r = LiquidityMap.read(all, "BANKNIFTY", 15, now)
        assertEquals(LiquidityMap.State.OK, r.state)
        assertEquals(day.atTime(10, 45), r.lastBar)                              // the 11:00 bar has not closed
        val last = all.last { it.start.isBefore(now) }
        assertEquals(last.close, r.price); assertEquals(day.atTime(11, 2), r.priceAt)
        assertTrue(r.entryOpen)
        // Bars after now change nothing; and the same as the zones of the closed bars read directly.
        assertEquals(r, LiquidityMap.read(all.filter { it.start.isBefore(now) }, "BANKNIFTY", 15, now))
        val closed = com.optionslab.engine.orb.LiquidityOverlay.closedBars(all, 15, now, inputMinutes = 1)
        assertEquals(r, LiquidityMap.read(closed, LiquidityRules.zones(closed), "BANKNIFTY", 15, last.close, last.start, true))
        for (s in listOfNotNull(r.above, r.below)) s.trigger?.let { assertEquals("pool", it.kind); assertTrue(it.onSwing) }
    }

    @Test fun entryHoursOfTheBarFormingNow() {
        assertTrue(LiquidityMap.entryOpen(day.atTime(10, 2), 5))
        assertTrue(LiquidityMap.entryOpen(day.atTime(9, 16), 5))                 // the 09:15 bar closes at 09:20
        assertFalse(LiquidityMap.entryOpen(day.atTime(9, 10), 5))
        assertTrue(LiquidityMap.entryOpen(day.atTime(13, 58), 15))               // closes 14:00
        assertFalse(LiquidityMap.entryOpen(day.atTime(13, 58), 30))              // FINNIFTY's 13:45 bar closes 14:15
        assertFalse(LiquidityMap.entryOpen(day.atTime(14, 1), 5))
        assertEquals(day.atTime(10, 0), LiquidityMap.barEnd(day.atTime(9, 45), 15))
    }

    @Test fun theAnswerSaysArmedAndHoursEitherWay() {
        val reads = listOf(read(54_120.0, minutes = 15), read(54_120.0), read(26_000.0, emptyList(), "FINNIFTY", 30), read(26_000.0, emptyList(), "FINNIFTY", 5))
        val q = LiquidityMap.Q(listOf("BANKNIFTY"))
        val inHours = LiquidityMap.answer(q, reads, true, day.atTime(10, 32))
        assertTrue(inHours.startsWith("Boss, Liquidity 15+5 is armed and in its entry hours (09:20-14:00)."), inHours)
        assertTrue(inHours.contains("BankNifty 15-min:") && inHours.contains("BankNifty 5-min:") && !inHours.contains("FinNifty"), inHours)
        assertTrue(inHours.endsWith("Information only - the arm decides on its own."), inHours)
        val late = LiquidityMap.answer(q, reads, true, day.atTime(14, 40))
        assertTrue(late.contains("armed, but outside its entry hours (09:20-14:00): no entry now") && late.contains("54,180"), late)
        assertTrue(LiquidityMap.answer(q, reads, false, day.atTime(10, 32)).contains("switched off - this is what it would watch."))
        assertTrue(LiquidityMap.answer(q, reads, false, day.atTime(15, 0)).contains("switched off - and outside its entry hours"))
        assertTrue(LiquidityMap.answer(q, reads, null, day.atTime(15, 0)).contains("Outside Liquidity 15+5's entry hours"))
        assertTrue(LiquidityMap.answer(q, reads, null, day.atTime(10, 0)).startsWith("Boss, Liquidity 15+5's levels"))
        // One book asked; both indices; an index the arm does not read; nothing read.
        val five = LiquidityMap.answer(LiquidityMap.Q(listOf("BANKNIFTY"), 5), reads, true, day.atTime(10, 32))
        assertTrue(five.contains("BankNifty 5-min") && !five.contains("15-min"), five)
        val fin = LiquidityMap.answer(LiquidityMap.Q(LiquidityRules.UNDERLYINGS), reads, true, day.atTime(10, 32))
        assertTrue(fin.contains("FinNifty 30-min: price 26,000. Above: no active level. Below: no active level."), fin)
        assertEquals(LiquidityMap.NOT_HERE, LiquidityMap.answer(LiquidityMap.Q(emptyList()), reads, true, day.atTime(10, 32)))
        assertTrue(LiquidityMap.answer(q, emptyList(), true, day.atTime(10, 32)).endsWith("No candles to read the levels from just now, Boss."))
    }

    @Test fun theHeadsUpComesNearALevelWithRoomInTheEntryHours() {
        val c = LiquidityMap.cue(read(54_168.0), t31)
        assertNotNull(c)
        assertEquals("BankNifty is 12 pts from 54,180 - a 5-min close above it would make Liquidity buy a call.", c.text)
        assertEquals("BankNifty is 15 pts from 54,000 - a 5-min close below it would make Liquidity buy a put.", LiquidityMap.cue(read(54_015.0), t31)!!.text)
        assertNull(LiquidityMap.cue(read(54_160.0), t31))                              // 20 points away
        assertNull(LiquidityMap.cue(read(54_168.0, open = false), t31))                // outside the entry hours
        assertNull(LiquidityMap.cue(read(54_168.0, zones(nextAbove = 54_200.0)), t31)) // no room: never a heads-up
        assertNull(LiquidityMap.cue(read(54_185.0), t31))                              // already past it
        // FINNIFTY: 8 points.
        val fz = listOf(z("swing", 1, 26_100.0, 26_050.0), z("pool", 1, 26_100.0, 26_090.0))
        assertNotNull(LiquidityMap.cue(read(26_092.0, fz, "FINNIFTY", 30), t31))
        assertNull(LiquidityMap.cue(read(26_091.0, fz, "FINNIFTY", 30), t31))
        assertNull(LiquidityMap.cue(LiquidityMap.Read("BANKNIFTY", 5, LiquidityMap.State.NO_DATA), t31))
        // A stale price (its minute started more than 2 minutes ago: the feed is behind) is never a heads-up.
        assertNotNull(LiquidityMap.cue(read(54_168.0), day.atTime(10, 33)))
        assertNull(LiquidityMap.cue(read(54_168.0), day.atTime(10, 33, 1)))
        assertNull(LiquidityMap.cue(read(54_168.0, at = day.minusDays(1).atTime(14, 0)), t31))
        assertTrue(LiquidityMap.cues(listOf(read(54_168.0)), day.atTime(10, 40), LiquidityMap.Told()).first.isEmpty())
    }

    @Test fun onceALevelADayAndOncePerIndexInTenMinutes() {
        val t0 = day.atTime(10, 31)
        val (c1, told1) = LiquidityMap.cues(listOf(read(54_168.0, minutes = 15), read(54_168.0)), t0, LiquidityMap.Told())
        assertEquals(1, c1.size)                                                  // one per index, the two books one level
        // The same level again later: never twice a day.
        assertTrue(LiquidityMap.cues(listOf(read(54_170.0, at = t0.plusMinutes(30))), t0.plusMinutes(30), told1).first.isEmpty())
        // Another level within 10 minutes: held; after 10 minutes: told.
        assertTrue(LiquidityMap.cues(listOf(read(54_010.0, at = t0.plusMinutes(9))), t0.plusMinutes(9), told1).first.isEmpty())
        val (c2, told2) = LiquidityMap.cues(listOf(read(54_010.0, at = t0.plusMinutes(10))), t0.plusMinutes(10), told1)
        assertEquals(listOf(54_000.0), c2.map { it.level })
        // FINNIFTY has its own limit.
        val fz = listOf(z("swing", -1, 26_010.0, 26_000.0), z("pool", -1, 26_005.0, 26_000.0))
        assertEquals(1, LiquidityMap.cues(listOf(read(26_004.0, fz, "FINNIFTY", at = t0.plusMinutes(11))), t0.plusMinutes(11), told2).first.size)
        // A new day starts afresh (its own price time).
        val next = LiquidityMap.read(bars, zones(), "BANKNIFTY", 5, 54_168.0, day.plusDays(1).atTime(10, 0), true)
        val (c3, told3) = LiquidityMap.cues(listOf(next), day.plusDays(1).atTime(10, 0), told2)
        assertEquals(1, c3.size)
        assertTrue(told3.keys.all { it.startsWith(day.plusDays(1).toString()) })
    }

    // ---- the question ----

    @Test fun theQuestionAndItsIndex() {
        assertEquals(LiquidityMap.Q(LiquidityRules.UNDERLYINGS), LiquidityMap.asked("where are the liquidity levels"))
        assertEquals(LiquidityMap.Q(listOf("BANKNIFTY")), LiquidityMap.asked("banknifty liquidity levels"))
        assertEquals(LiquidityMap.Q(listOf("FINNIFTY")), LiquidityMap.asked("finnifty liquidity level kahan hai"))
        assertEquals(LiquidityMap.Q(listOf("BANKNIFTY"), 15), LiquidityMap.asked("bank nifty 15 min liquidity levels"))
        assertEquals(LiquidityMap.Q(LiquidityRules.UNDERLYINGS, 5), LiquidityMap.asked("liquidity levels on the five minute chart"))
        assertEquals(LiquidityMap.Q(emptyList()), LiquidityMap.asked("nifty liquidity levels"))
        assertEquals(LiquidityMap.Q(emptyList()), LiquidityMap.asked("sensex liquidity levels"))
        // MIDCPNIFTY: its own books since research h4 (07 Oct).
        assertEquals(LiquidityMap.Q(listOf("MIDCPNIFTY")), LiquidityMap.asked("midcap nifty liquidity levels"))
        assertEquals(LiquidityMap.Q(listOf("MIDCPNIFTY"), 15), LiquidityMap.asked("midcpnifty 15 min liquidity level kahan hai"))
        assertEquals(LiquidityMap.Q(listOf("FINNIFTY", "MIDCPNIFTY")), LiquidityMap.asked("finnifty and midcap liquidity levels"))
        assertEquals("Midcap Nifty", LiquidityMap.indexName("MIDCPNIFTY"))
        assertEquals(4.0, LiquidityMap.near("MIDCPNIFTY"))
        assertTrue(LiquidityMap.NOT_HERE.contains("Midcap Nifty"))
        for (s in listOf("what is liquidity waiting for", "how far is the next pool", "liquidity kis level ka wait kar raha hai",
            "next liquidity level kitna door hai", "where is the nearest pool on finnifty")) assertNotNull(LiquidityMap.asked(s), s)
        for (s in listOf("how is liquidity doing", "liquidity ko 3 lot karo", "set liquidity to 2 lots", "how many lots is liquidity trading",
            "why did the liquidity bot exit", "what is a liquidity pool", "is there liquidity in the 52000 ce", "what are the levels",
            "where is support", "liquidity", "pool party")) assertNull(LiquidityMap.asked(s), s)
    }
}
