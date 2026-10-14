package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Boss's OI-wall framework in shadow ([OiWallBlast], R26 rule P, 14 Oct 2026): parity with r26lib.py, the ticket, the words. */
class OiWallBlastTest {
    private val B = OiWallBlast

    // ---- parity with research/hunt/r26/r26lib.py ----

    private class Fx(val name: String, val index: String) {
        var spot: List<String> = emptyList()
        val opts = ArrayList<Opt>()
        val signals = HashMap<String, String>()
        val cond = HashMap<Int, List<String>>()
        val walls = HashMap<String, String>()
    }

    private class Opt(val strike: Double, val right: String) {
        var has: List<String> = emptyList(); var close: List<String> = emptyList(); var oi: List<String> = emptyList()
        var vol0 = 0.0; var dvol: List<String> = emptyList()
    }

    private fun unrle(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split(",").flatMap { t ->
        val star = t.lastIndexOf('*')
        if (star >= 0 && star < t.length - 1 && t.substring(star + 1).all { it.isDigit() }) List(t.substring(star + 1).toInt()) { t.substring(0, star) } else listOf(t)
    }

    private fun fixtures(): List<Fx> {
        val out = ArrayList<Fx>()
        for (raw in javaClass.getResource("/oiwall/r26_parity.txt")!!.readText().lines()) {
            if (raw.isBlank() || raw.startsWith("#")) continue
            val head = raw.substringBefore(' '); val rest = raw.substringAfter(' ', "")
            val f = out.lastOrNull()
            when (head) {
                "case" -> rest.split(" ").let { out += Fx(it[0], it[1]) }
                "spot" -> f!!.spot = unrle(rest)
                "opt" -> rest.split(" ").let { f!!.opts += Opt(it[0].toDouble(), it[1]) }
                "has" -> f!!.opts.last().has = unrle(rest)
                "close" -> f!!.opts.last().close = unrle(rest)
                "oi" -> f!!.opts.last().oi = unrle(rest)
                "vol0" -> f!!.opts.last().vol0 = rest.toDouble()
                "dvol" -> f!!.opts.last().dvol = unrle(rest)
                "signal" -> f!!.signals[rest.substringBefore(' ')] = rest.substringAfter(' ')
                "cond" -> f!!.cond[rest.substringBefore(' ').toInt()] = unrle(rest.substringAfter(' '))
                "wall" -> f!!.walls[rest.substringBefore(' ')] = rest.substringAfter(' ')
            }
        }
        return out
    }

    private val FROM = 150

    /** The fixture's day, recorded a row at a time as the app does (cumulative volume, OI, a close when traded). */
    private fun dayOf(f: Fx, upTo: Int = B.N - 1): OiWallBlast.Day {
        val d = OiWallBlast.Day(f.index)
        val cum = f.opts.map { it.vol0 }.toDoubleArray()
        for (r in FROM..upTo) {
            val i = r - FROM
            val obs = ArrayList<OiWallBlast.Obs>()
            f.opts.forEachIndexed { j, o ->
                if (o.has.getOrNull(i).isNullOrEmpty()) return@forEachIndexed
                cum[j] += o.dvol.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
                obs += OiWallBlast.Obs(o.strike, o.right, o.oi.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDouble()?.toLong(),
                    cum[j].toLong(), o.close.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDouble())
            }
            d.record(r, f.spot.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDouble(), obs)
        }
        return d
    }

    @Test fun matchesTheResearchRuleOnItsOwnFixture() {
        val fs = fixtures()
        assertEquals(listOf("blast-up", "no-oi-fall", "flat", "blast-down"), fs.map { it.name })
        for (f in fs) {
            val d = dayOf(f)
            val want = f.signals.getValue("P")
            val s = B.signal(d)
            if (want == "none") assertNull(s, f.name)
            else {
                val p = want.split(" ")
                assertNotNull(s, f.name)
                assertEquals(p[0].toInt(), s.row, f.name); assertEquals(p[1].toInt(), s.side, f.name)
                assertEquals(p[2].toDouble(), s.strike, f.name); assertEquals(p[3].toDouble(), s.ref, f.name)
                assertEquals(p[4].toDouble(), s.wall, f.name); assertEquals(p[5] == "1", s.edge, f.name)
            }
            for (sgn in intArrayOf(1, -1)) {
                val flags = f.cond.getValue(sgn)
                assertEquals(B.LAST - B.FIRST + 1, flags.size)
                for ((i, t) in (B.FIRST..B.LAST).withIndex()) {
                    val c = B.conditions(d, sgn, t)
                    val got = listOf(c.brk, c.oiFall, c.vol, c.price, c.sumOi).joinToString("") { if (it) "1" else "0" }
                    assertEquals(flags[i], got, "${f.name} sgn $sgn row $t")
                }
            }
            for (right in listOf("CE", "PE")) {
                val (k, edge) = f.walls.getValue(right).split(" ")
                assertEquals(k.toDouble() to (edge == "1"), B.wall(d, right, B.ANCHOR), "${f.name} $right")
            }
        }
    }

    @Test fun theTwelveSeptemberShapeFiresUpAfterSixteenClosesNotFifteen() {
        val f = fixtures().first { it.name == "blast-up" }
        val d = dayOf(f)
        val s = B.signal(d)!!
        assertEquals(1, s.side); assertEquals("CE", s.right)
        assertEquals(LocalTime.of(14, 13), s.time)
        assertTrue(s.oiFallPct!! >= 15.0, "${s.oiFallPct}")
        // 15 closes above the wall is not a held break.
        val firstAbove = (0 until B.N).first { d.spot[it] > s.wall }
        assertFalse(B.conditions(d, 1, firstAbove + B.HOLD - 2).brk)
        assertTrue(B.conditions(d, 1, firstAbove + B.HOLD - 1).brk)
        // The bought strike is ATM + 1 step, priced in Rs 15-30.
        assertEquals(Math.floor(d.spot[s.row] / 50 + 0.5) * 50 + 50, s.strike)
        assertTrue(s.ref in 15.0..30.0)
    }

    @Test fun lookAheadAndAPartialWindow() {
        val f = fixtures().first { it.name == "blast-up" }
        val full = B.signal(dayOf(f))!!
        // Only the rows recorded so far (a restart, or the window still running): never a later trigger, the same one once in.
        for (t in listOf(B.FIRST, full.row - 1)) {
            assertNull(B.signal(dayOf(f, t), t), "$t")
            assertNotNull(B.gaps(dayOf(f, t), t))
            assertTrue("the window was not over" in B.gaps(dayOf(f, t), t)!!)
        }
        val at = B.signal(dayOf(f, full.row), full.row)!!
        assertEquals(full.row to full.strike, at.row to at.strike)
        // Checked a minute at a time (the live log): the trigger's own minute finds it, a later start never goes back.
        assertEquals(full.row, B.signal(dayOf(f), full.row, from = full.row)!!.row)
        assertNull(B.signal(dayOf(f), B.LAST, from = full.row + 1)?.takeIf { it.row <= full.row })
        // A day recorded from 13:10 has no wall at 13:00: it can never fire, and says why.
        val late = OiWallBlast.Day("NIFTY")
        val src = dayOf(f)
        for (r in B.closeRow(LocalTime.of(13, 10)) until B.N) late.record(r, src.spot[r], emptyList())
        assertNull(B.signal(late))
        assertTrue("no option chain at 13:00" in B.gaps(late)!!, B.gaps(late))
    }

    // ---- missing data, market closed ----

    @Test fun anEmptyOrClosedDayNeverFires() {
        val empty = OiWallBlast.Day("NIFTY")
        assertNull(B.signal(empty))
        assertNull(B.wall(empty, "CE", B.ANCHOR))
        assertFalse(B.volSpike(empty, B.FIRST))
        assertTrue(B.sumOiChange(empty, "CE", B.FIRST).isNaN())
        val g = B.gaps(empty)!!
        assertTrue("no option chain at 13:00" in g && "no spot at 13:00" in g && "minutes of spot missing" in g, g)
        // An index R26 did not study: never.
        assertNull(B.signal(OiWallBlast.Day("MIDCPNIFTY")))
        // Spot missing for a minute inside the 16 closes: no held break.
        val f = fixtures().first { it.name == "blast-up" }
        val d = dayOf(f)
        val s = B.signal(d)!!
        d.spot[s.row - 3] = Double.NaN
        assertFalse(B.conditions(d, 1, s.row).brk)
        // Rows past the end or before the window: nothing.
        assertFalse(B.conditions(d, 1, B.N).fires)
        assertFalse(B.conditions(d, 1, B.ANCHOR + 5).fires)
        d.record(-1, 1.0, emptyList()); d.record(B.N, 1.0, emptyList())
    }

    @Test fun theBoughtOptionMustHaveTradedInTheLastFiveBars() {
        val f = fixtures().first { it.name == "blast-up" }
        val d = OiWallBlast.Day("NIFTY")
        val src = dayOf(f)
        val s = B.signal(src)!!
        // The same day, but the bought strike's last trade 6 rows before the trigger: no pick at that minute.
        for (r in FROM until B.N) {
            val obs = listOf(OiWallBlast.Obs(s.strike, "CE", close = if (r <= s.row - 5) 20.0 else null))
            d.record(r, src.spot[r], obs)
        }
        assertNull(B.pick(d, 1, s.row))
        assertNotNull(B.pick(d, 1, s.row - 1))
        // Outside Rs 15-30: no pick.
        val e = OiWallBlast.Day("NIFTY")
        e.record(s.row, src.spot[s.row], listOf(OiWallBlast.Obs(s.strike, "CE", close = 31.0)))
        assertNull(B.pick(e, 1, s.row))
        e.record(s.row, src.spot[s.row], listOf(OiWallBlast.Obs(s.strike, "CE", close = 30.0)))
        assertNotNull(B.pick(e, 1, s.row))
    }

    // ---- theta, volume and walls on a small hand-made day ----

    /** NIFTY / BANKNIFTY-like day: spot [path], a call wall at [wall] with OI falling from 3 lakh to [lo] from 13:35. */
    private fun handDay(index: String, step: Double, base: Double, wall: Double, lo: Double, spike: Boolean = true): OiWallBlast.Day {
        val d = OiWallBlast.Day(index)
        val strikes = (-6..6).map { base + it * step }
        val cum = HashMap<Pair<Double, String>, Long>()
        val from = B.minute(LocalTime.of(13, 35)); val to = B.minute(LocalTime.of(14, 30))
        for (r in 0 until B.N) {
            val spot = base + (((r - B.minute(LocalTime.of(13, 30))).toDouble() / 40).coerceIn(0.0, 1.0)) * 3 * step
            val obs = strikes.flatMap { k -> listOf("CE", "PE").map { right ->
                val v = if (spike && r >= from) 25_000L else 5_000L
                val c = cum.merge(k to right, v, Long::plus)!!
                val oi = if (right == "CE" && k == wall) (300_000 - (((r - from).toDouble() / (to - from)).coerceIn(0.0, 1.0)) * (300_000 - lo)).toLong() else 100_000L
                OiWallBlast.Obs(k, right, oi, c, if (k == Math.floor(spot / step + 0.5) * step + step && right == "CE") 20.0 else 50.0)
            } }
            d.record(r, spot, obs)
        }
        return d
    }

    @Test fun thetaIs15PercentForNiftyAnd25ForBankNifty() {
        assertEquals(mapOf("NIFTY" to 0.15, "SENSEX" to 0.15, "BANKNIFTY" to 0.25, "FINNIFTY" to 0.25), B.THETA)
        // A 20% fall: NIFTY's rule fires, BANKNIFTY's does not.
        val n = handDay("NIFTY", 50.0, 25000.0, 25100.0, 240_000.0)
        assertNotNull(B.signal(n))
        val b = handDay("BANKNIFTY", 100.0, 55000.0, 55200.0, 240_000.0)
        assertNull(B.signal(b))
        assertFalse(B.conditions(b, 1, B.LAST).oiFall)
        // A 50% fall: both.
        assertNotNull(B.signal(handDay("BANKNIFTY", 100.0, 55000.0, 55200.0, 150_000.0)))
        // No volume spike: no trigger.
        assertNull(B.signal(handDay("NIFTY", 50.0, 25000.0, 25100.0, 150_000.0, spike = false)))
    }

    @Test fun theWallTieGoesToTheStrikeNearestSpotAndTheEdgeIsFlagged() {
        val d = OiWallBlast.Day("NIFTY")
        d.record(B.ANCHOR, 25010.0, listOf(24900.0, 25000.0, 25100.0).map { OiWallBlast.Obs(it, "PE", 100_000) } +
            listOf(OiWallBlast.Obs(25200.0, "CE", 900_000), OiWallBlast.Obs(25100.0, "CE", 10)))
        assertEquals(25000.0 to false, B.wall(d, "PE", B.ANCHOR))
        assertEquals(25200.0 to true, B.wall(d, "CE", B.ANCHOR))
        // OI carried forward from the past only; zero OI is no reading.
        d.record(B.ANCHOR + 1, 25010.0, listOf(OiWallBlast.Obs(25200.0, "CE", 0)))
        assertEquals(900_000.0, d.oiAt(25200.0, "CE", B.ANCHOR + 1))
        assertTrue(d.oiAt(25200.0, "CE", B.ANCHOR - 1).isNaN())
        // Stored for 5 rows after a reading, not 6.
        assertTrue(d.present(25000.0, "PE", B.ANCHOR + 4)); assertFalse(d.present(25000.0, "PE", B.ANCHOR + 5))
    }

    // ---- the ticket (the app's paper cost model) ----

    private fun ticketOn(px: (Int) -> Double, lot: Int = 65, high: ((Int) -> Double)? = null): OiWallBlast.Ticket? {
        val d = OiWallBlast.Day("NIFTY")
        val sig = OiWallBlast.Signal(B.FIRST, 1, 25100.0, 20.0, 25050.0, false, 30.0)
        for (r in B.FIRST until B.N) {
            val c = px(r); val o = if (r > B.FIRST) px(r - 1) else c
            d.bar(r, 25100.0, "CE", o, high?.invoke(r) ?: maxOf(o, c), minOf(o, c), c)
        }
        return B.ticket(d, sig, lot)
    }

    @Test fun sizingIsWholeLotsUnderRs2000AndAtLeastOne() {
        val t = ticketOn({ 20.0 })!!
        assertEquals(B.FIRST + 1, t.fillRow)
        assertEquals(B.paperBuy("NIFTY", 20.0), t.fill)
        assertEquals(20.05, t.fill, 1e-9)          // max(5 bps, NIFTY's 0.16% half-spread), up to the tick
        assertEquals(1, t.lots)                    // 2,000 / (20.05 x 65) = 1.53
        assertEquals(65, t.units)
        assertEquals(3, ticketOn({ 20.0 }, lot = 30)!!.lots)
        assertEquals(1, ticketOn({ 20.0 }, lot = 1800)!!.lots, "one lot over budget is still one lot")
        assertNull(ticketOn({ 20.0 }, lot = 0))
    }

    @Test fun theTicketIsNotChasedPastItsCap() {
        // The option jumps to 40 right after the trigger: the 1.2x + 0.10 cap (24.10) is never met in 3 bars.
        assertNull(ticketOn({ r -> if (r >= B.FIRST) 40.0 else 20.0 }))
    }

    @Test fun fourTimesTheFillFillsOnlyOnAConfirmedPrice() {
        val t = ticketOn({ r -> if (r >= B.FIRST + 10) 90.0 else 20.0 })!!
        val o = t.of(OiWallBlast.Exit.TARGET_4X)!!
        assertEquals("4x", o.sales.last().why)
        assertEquals(80.2, o.sales.last().price, 1e-9)                         // floor_tick(4 x 20.05), resting: no spread
        assertTrue(o.points > 55 && o.points < 60.15, "${o.points}")
        assertTrue(o.peakMult >= 4.0)
        // One freak print (close and next open back at 20): no target, out at 15:05.
        val f = ticketOn({ 20.0 }, high = { r -> if (r == B.FIRST + 10) 200.0 else 20.0 })!!.of(OiWallBlast.Exit.TARGET_4X)!!
        assertEquals("4x", f.exit.code)
        assertEquals(B.EXIT_1505, f.sales.single().row)
        assertTrue(f.peakMult < 1.1)
    }

    @Test fun holdingTo1505And1520() {
        val t = ticketOn({ 20.0 })!!
        assertEquals(B.EXIT_1505, t.of(OiWallBlast.Exit.HOLD_1505)!!.sales.single().row)
        assertEquals(B.EXIT_1520, t.of(OiWallBlast.Exit.HOLD_1520)!!.sales.single().row)
        assertEquals(LocalTime.of(15, 5), LocalTime.of(9, 15).plusMinutes(B.EXIT_1505.toLong()))
        // Flat: the costs make it a small loss in points.
        val o = t.of(OiWallBlast.Exit.HOLD_1505)!!
        assertTrue(o.points < 0 && o.points > -1.5, "${o.points}")
        assertEquals(o.net / t.units, o.points, 1e-9)
        assertTrue(o.charges > 40, "${o.charges}")                              // two Rs 20 orders plus taxes
    }

    @Test fun halfAtTwiceThenTheStepLock() {
        val px = { r: Int -> when { r >= B.FIRST + 20 -> 30.0; r >= B.FIRST + 10 -> 65.0; else -> 20.0 } }
        val t = ticketOn(px, lot = 30)!!
        assertEquals(3, t.lots)
        val o = t.of(OiWallBlast.Exit.STEP_LOCK)!!
        assertEquals(listOf("2x", "lock"), o.sales.map { it.why })
        assertEquals(40.1, o.sales[0].price, 1e-9)
        assertEquals(60, o.sales[0].units)                                      // round(3 x 0.5) = 2 lots (half to even)
        assertEquals(B.FIRST + 21, o.sales[1].row)                             // the close under the lock, sold at the next open
        // One lot: the 2x sale is the whole position.
        val one = ticketOn(px, lot = 65)!!.of(OiWallBlast.Exit.STEP_LOCK)!!
        assertEquals(listOf("2x"), one.sales.map { it.why })
    }

    @Test fun worthlessAtTheExitSellsNothing() {
        val t = ticketOn({ r -> if (r >= B.FIRST + 5) 0.05 else 20.0 })!!
        val o = t.of(OiWallBlast.Exit.HOLD_1505)!!
        assertEquals(0.05, o.sales.single().price, 1e-9) // a 0.05 bid still sells at the tick floor
        // No bar after the entry (the stream stopped): sold at the last close seen, at its own row.
        val d = OiWallBlast.Day("NIFTY")
        d.bar(B.FIRST + 1, 25100.0, "CE", 20.0, 20.0, 20.0, 20.0)
        val sig = OiWallBlast.Signal(B.FIRST, 1, 25100.0, 20.0, 25050.0, false, null)
        val z = B.ticket(d, sig, 65)!!.of(OiWallBlast.Exit.HOLD_1505)!!
        assertEquals(B.FIRST + 1, z.sales.single().row)
        assertEquals(B.paperSell("NIFTY", 20.0), z.sales.single().price, 1e-9)
        // No bar for the bought option at all: no ticket.
        assertNull(B.ticket(OiWallBlast.Day("NIFTY"), sig, 65))
    }

    // ---- the words: points only ----

    private fun result(fired: Boolean, index: String = "BANKNIFTY"): OiWallBlast.Result {
        val day = LocalDate.of(2026, 10, 28)
        if (!fired) return OiWallBlast.Result(index, day, null, null, null)
        val sig = OiWallBlast.Signal(B.FIRST + 20, 1, 55200.0, 22.4, 55100.0, false, 30.0)
        val t = ticketOn({ r -> if (r >= B.FIRST + 10) 90.0 else 20.0 })!!
        return OiWallBlast.Result(index, day, sig, t)
    }

    @Test fun theEveningLineSaysFiredOrNotInPoints() {
        val yes = B.eveningLine(listOf(result(true)))
        assertTrue(yes.startsWith("OI-wall check (shadow, not traded): fired on BankNifty at 14:05 (55200 CE at 22.40); would have made +"), yes)
        assertTrue("points a unit after costs" in yes && "held to 15:20" in yes && "half at 2x, step lock" in yes, yes)
        assertTrue("nothing trades on it" in yes)
        val no = B.eveningLine(listOf(result(false, "NIFTY")))
        assertEquals("OI-wall check (shadow, not traded): didn't fire on Nifty. A record only: R26 found no edge, so nothing trades on it.", no)
        assertTrue("no expiry today" in B.eveningLine(emptyList()))
        val gap = B.eveningLine(listOf(OiWallBlast.Result("NIFTY", LocalDate.of(2026, 10, 20), null, null, "no option chain at 13:00")))
        assertTrue("didn't fire on Nifty (no option chain at 13:00)" in gap, gap)
        val unfilled = B.eveningLine(listOf(OiWallBlast.Result("NIFTY", LocalDate.of(2026, 10, 20),
            OiWallBlast.Signal(B.FIRST, -1, 24900.0, 18.0, 25000.0, true, 20.0), null)))
        assertTrue("would not have filled" in unfilled, unfilled)
        // No rupee amount anywhere (the diagnostics' privacy rule).
        for (s in listOf(yes, no, B.diagLine(listOf(result(true), result(false, "NIFTY"))), B.tally(listOf(result(true), result(false)))))
            assertFalse(Regex("Rs|₹|rupee").containsMatchIn(s), s)
        assertTrue(B.diagLine(listOf(result(true))).startsWith("oi_wall: BANKNIFTY fired at 14:05, 55200 CE at 22.40 (wall 55100)"))
        assertEquals("oi_wall: no expiry watched today", B.diagLine(emptyList()))
        val tally = B.tally(listOf(result(true), result(false)))
        assertTrue("2 expiry-index days logged, 1 trigger" in tally && "over 1 ticket" in tally && "30 triggers" in tally, tally)
        assertTrue(B.summary(OiWallBlast.Result("NIFTY", LocalDate.of(2026, 10, 20),
            OiWallBlast.Signal(B.FIRST, -1, 24900.0, 18.0, 25000.0, true, 20.0), null, "restarted")).contains("the edge strike"))
    }

    @Test fun askedAndTheClock() {
        assertTrue(B.asked("did the oi wall fire today?"))
        assertTrue(B.asked("how is the OI-wall check doing"))
        assertFalse(B.asked("what is an oi wall"))
        assertFalse(B.asked("where is the oi wall now"), "the walls themselves stay the chain's answer")
        assertTrue(B.asked("oi wall shadow log"))
        assertFalse(B.asked("how is nifty"))
        assertEquals(224, B.ANCHOR); assertEquals(164, B.NOON); assertEquals(269, B.FIRST); assertEquals(314, B.LAST)
        assertEquals(LocalTime.of(13, 45), B.closeTime(B.FIRST)); assertEquals(LocalTime.of(14, 30), B.closeTime(B.LAST))
        assertEquals(350, B.EXIT_1505); assertEquals(365, B.EXIT_1520)
        assertTrue(abs(B.paperSell("NIFTY", 20.0) - 19.95) < 1e-9)
        assertEquals(0.0, B.paperSell("NIFTY", 0.0))
    }
}
