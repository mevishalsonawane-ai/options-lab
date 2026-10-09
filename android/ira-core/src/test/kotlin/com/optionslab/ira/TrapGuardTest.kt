package com.optionslab.ira

import com.optionslab.engine.KiteTicks
import com.optionslab.engine.KiteTicks.Level
import com.optionslab.ira.TrapGuard.Trap
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The order flow's trap guard ([TrapGuard], HUNT R8's parameters), each guard on a synthetic tick sequence. */
class TrapGuardTest {
    private fun book(bids: List<Pair<Double, Long>>, asks: List<Pair<Double, Long>>) =
        KiteTicks.Depth(bids.map { Level(it.first, it.second, 2) }, asks.map { Level(it.first, it.second, 2) })

    private fun tick(token: Long, last: Double, volume: Long, d: KiteTicks.Depth, tradeSec: Long? = null) =
        KiteTicks.Tick(token, last, volume = volume, oi = 1_000, buyQty = 1_000, sellQty = 1_000, depth = d, lastTradeTime = tradeSec)

    /** 9 Oct 2026, 09:03 IST: before NSE's open, so no time trap in the board tests. */
    private val t0 = java.time.LocalDateTime.of(2026, 10, 9, 9, 3).atZone(java.time.ZoneId.of("Asia/Kolkata")).toEpochSecond() * 1000

    private fun board(): OrderFlow.Board = OrderFlow.Board().also { it.layout(listOf(OrderFlow.Board.Entry(1, "NIFTY", OrderFlow.Role.FUTURE))) }

    // ---- 3. pulls ----

    @Test fun aLevelThatHalvesWithoutTradingIsAPull() {
        val before = listOf(Level(100.05, 500, 3), Level(100.10, 50, 1))
        assertEquals(500L, TrapGuard.pulled(before, listOf(Level(100.10, 50, 1)), bids = false, traded = 0))
        // 120 traded: under half of the 500 drop, so 380 was pulled.
        assertEquals(380L, TrapGuard.pulled(before, listOf(Level(100.10, 50, 1)), bids = false, traded = 120))
        // 300 traded of a 500 drop: it was taken, not pulled.
        assertEquals(0L, TrapGuard.pulled(before, listOf(Level(100.10, 50, 1)), bids = false, traded = 300))
        // A 30% drop is not a pull.
        assertEquals(0L, TrapGuard.pulled(before, listOf(Level(100.05, 350, 3), Level(100.10, 50, 1)), bids = false, traded = 0))
        // A full book that moved: a level now outside its range is out of view, not pulled.
        fun lv(vararg p: Double) = p.map { Level(it, 100, 1) }
        val bids = lv(100.0, 99.95, 99.9, 99.85, 99.8)
        assertEquals(0L, TrapGuard.pulled(bids, lv(100.1, 100.05, 100.0, 99.95, 99.9), bids = true, traded = 0))
        assertEquals(200L, TrapGuard.pulled(bids, lv(99.9, 99.85, 99.8, 99.75, 99.7), bids = true, traded = 0))
        assertEquals(0L, TrapGuard.pulled(emptyList(), bids, bids = true, traded = 0))
    }

    @Test fun aLastSecondPullTakesThatSidesDepthAwayFor10Seconds() {
        val b = board()
        var vol = 0L
        for (s in 0 until 600) {
            vol += 10
            b.offer(tick(1, if (s % 2 == 0) 100.05 else 100.0, vol, book(listOf(100.0 to 100L), listOf(100.05 to 100L))), t0 + s * 1000L)
        }
        // A big bid shows for 20 s (distinct snapshots: its size moves a little), then is pulled with nothing traded.
        for (s in 600 until 620) b.offer(tick(1, 100.0, vol, book(listOf(100.0 to 100L, 99.95 to 5_000L + s), listOf(100.05 to 100L))), t0 + s * 1000L)
        b.offer(tick(1, 100.0, vol, book(listOf(100.0 to 100L), listOf(100.05 to 100L))), t0 + 620_500L)
        val r = b.read("NIFTY", t0 + 620_900L)!!
        assertTrue(Trap.PULL_BID in r.flags, "${r.flags}")
        assertTrue((r.z["depth60"] ?: 0.0) <= 0.0, "the pulled side gets no credit: ${r.z["depth60"]}")
        assertTrue(r.pulledBid60 >= 5_000, "${r.pulledBid60}")
        assertTrue(r.cancelToTrade > 1.0, "${r.cancelToTrade}")
        // Ten seconds on (the feed still ticking), the pull no longer counts.
        for (s in 621 until 633) { vol += 10; b.offer(tick(1, if (s % 2 == 0) 100.05 else 100.0, vol, book(listOf(100.0 to 100L), listOf(100.05 to 100L))), t0 + s * 1000L) }
        assertFalse(Trap.PULL_BID in b.read("NIFTY", t0 + 632_900L)!!.flags)
    }

    // ---- 1, 2. flicker and walls ----

    @Test fun sizeCountsOnlyAfterThreeSecondsAndThreeSnapshots() {
        val since = HashMap<Double, TrapGuard.Seen>()
        val l = listOf(Level(100.0, 100, 2))
        TrapGuard.keepSince(since, l, 0)
        TrapGuard.keepSince(since, l, 1_000)
        assertEquals(0.0, TrapGuard.persistentDepth(l, since, 4_000, median = null), "two snapshots, however long: not yet")
        TrapGuard.keepSince(since, l, 1_500)
        assertEquals(0.0, TrapGuard.persistentDepth(l, since, 2_500, median = null), "three snapshots in 2.5 s: not yet")
        assertEquals(100.0, TrapGuard.persistentDepth(l, since, 3_000, median = null))
        // A price that leaves is forgotten; a level of no size starts nothing.
        TrapGuard.keepSince(since, emptyList(), 4_000)
        assertTrue(since.isEmpty())
        TrapGuard.keepSince(since, listOf(Level(98.0, 0, 0)), 5_000)
        assertTrue(since.isEmpty())
    }

    @Test fun wallsWaitFiveSecondsCountHalfForOneOrderAndAreCapped() {
        val old = TrapGuard.Seen(0, 10)
        // The median level is 100: a wall is 300 or more.
        assertEquals(0.0, TrapGuard.levelWeight(Level(99.95, 1_000, 4), TrapGuard.Seen(1_000, 10), 5_000, 100.0), "a wall alive 4 s: nothing")
        assertEquals(200.0, TrapGuard.levelWeight(Level(99.95, 1_000, 4), old, 6_000, 100.0), "alive 6 s: capped at 2x the median")
        assertEquals(200.0, TrapGuard.levelWeight(Level(99.95, 1_000, 1), old, 6_000, 100.0), "one order: half, then capped")
        assertEquals(175.0, TrapGuard.levelWeight(Level(99.95, 350, 1), old, 6_000, 100.0), "one order: half of 350")
        assertEquals(150.0, TrapGuard.levelWeight(Level(99.95, 150, 1), old, 6_000, 100.0), "not a wall: as it is")
        assertEquals(5_000.0, TrapGuard.levelWeight(Level(99.95, 5_000, 1), old, 6_000, null), "no median yet: no cap")
        // Levels 4-5 over 3x the top three's mean are left out.
        val deep = listOf(Level(100.0, 100, 1), Level(99.95, 100, 1), Level(99.9, 100, 1), Level(99.85, 400, 1), Level(99.8, 250, 1))
        assertEquals(listOf(100L, 100L, 100L, 250L), TrapGuard.walls(deep).map { it.qty })
        assertEquals(deep.take(2), TrapGuard.walls(deep.take(2)))
        assertEquals(150.0, TrapGuard.median(listOf(100.0, 200.0, Double.NaN))!!, 1e-9)
        assertNull(TrapGuard.median(emptyList()))
    }

    // ---- 6. absorption and exhaustion ----

    /** Buyers lifting the 100.05 offer [n] seconds running, 100 at a time, the offer refilling, the bid stepping down. */
    private fun attack(d: TrapGuard.Defence, n: Int, from: Long = 0) {
        for (s in 0 until n) {
            val bid = if (s < 5) 100.0 else 99.9
            d.update(100.05, 100, (bid + 100.05) / 2, listOf(Level(100.05, 100, 3)), bid, 100.05, 100.05, 100, (bid + 100.05) / 2, from + s * 1000L)
        }
    }

    @Test fun aLevelThatTakesThreeTimesWhatItShowsHolds10SAndPushesBackIsAbsorption() {
        val d = TrapGuard.Defence(ask = true)
        attack(d, 9)
        assertTrue(d.events.isEmpty(), "under 10 s: not yet")
        attack(d, 12, 9_000)
        assertEquals(1, d.events.size)
        assertEquals(100.05, d.events.single().price, 1e-9)
        // Within 30 s the buyers break through: exhaustion.
        d.update(100.05, 100, 100.0, listOf(Level(100.10, 50, 1)), 100.05, 100.10, 100.10, 100, 100.075, 25_000)
        assertTrue(d.events.single().exhausted)
        // Traded under 3x what it showed: no absorption.
        val small = TrapGuard.Defence(ask = true)
        for (s in 0 until 12) small.update(100.05, 500, 100.025, listOf(Level(100.05, 500, 3)), 99.9, 100.05, 100.05, 100, 99.975, s * 1000L)
        assertTrue(small.events.isEmpty())
        // The bid side mirrors it: sellers hitting a 100.0 bid that holds while the offer steps up.
        val bid = TrapGuard.Defence(ask = false)
        for (s in 0 until 12) {
            val ask = if (s < 5) 100.05 else 100.15
            bid.update(100.0, 100, (100.0 + ask) / 2, listOf(Level(100.0, 100, 3)), 100.0, ask, 100.0, 100, (100.0 + ask) / 2, s * 1000L)
        }
        assertEquals(1, bid.events.size)
    }

    @Test fun absorptionOnTheBoardNeutralisesTheBuying() {
        val b = board()
        var vol = 0L
        for (s in 0 until 600) b.offer(tick(1, 100.0 - (s % 2) * 0.05, vol, book(listOf(100.0 to 100L), listOf(100.05 to 100L))), t0 + s * 1000L)
        // A minute of heavy buying at the 100.05 offer that never gives way, the bid stepping down.
        for (s in 600 until 660) {
            vol += 500
            val bid = if (s < 605) 100.0 else 99.9
            b.offer(tick(1, 100.05, vol, book(listOf(bid to 100L), listOf(100.05 to 100L))), t0 + s * 1000L)
        }
        val r = b.read("NIFTY", t0 + 659_900L)!!
        assertTrue(Trap.ABSORPTION_BUY in r.flags, "${r.flags} raw ${r.rawBuyers}")
        assertTrue(r.buyers <= 50, "${r.buyers}")
    }

    // ---- 7, 12. price confirmation and the cross-check ----

    @Test fun aLeanThePriceMovedAgainstOrTheOptionsContradictIsNeutral() {
        assertTrue(TrapGuard.against(1, -0.2))
        assertFalse(TrapGuard.against(1, -0.05), "one tick against is allowed")
        assertFalse(TrapGuard.against(1, 3.0)); assertFalse(TrapGuard.against(0, -5.0))
        assertTrue(TrapGuard.against(-1, 0.5))
        assertTrue(TrapGuard.contradicts(70, -0.6)); assertTrue(TrapGuard.contradicts(30, 0.7))
        assertFalse(TrapGuard.contradicts(70, -0.2)); assertFalse(TrapGuard.contradicts(50, -0.9)); assertFalse(TrapGuard.contradicts(70, Double.NaN))
    }

    // ---- 8. stop hunts and failed breaks ----

    private fun bar(sec: Long, mid: Double, delta: Long) =
        OrderFlow.Bar(sec, mid, mid, 0.0, maxOf(delta, 0), maxOf(-delta, 0), kotlin.math.abs(delta), 0.0, 0.0, 1.0, 0)

    private val quiet = (0L until 120L).map { bar(it, 100.0, if (it % 2 == 0L) 5 else -5) }
    private val burst = (120L until 126L).map { bar(it, 100.0 + (it - 119) * 1.0, 3_000) }       // to 106 on heavy buying

    @Test fun aBurstBackWithin60SIsAStopHuntAndWithin120SAFailedBreak() {
        val fast = quiet + burst + (126L until 160L).map { bar(it, 106.0 - (it - 125) * 0.5, 0) }          // back under 100 by 137
        val h = TrapGuard.stopHunt(fast, 159)!!
        assertTrue(h.backSec in 126L..140L && h.dir == 1 && !h.failedBreak, "$h")
        val slow = quiet + burst + (126L until 260L).map { bar(it, 106.0 - (it - 125) * 0.07, 0) }         // back by ~211
        val f = TrapGuard.stopHunt(slow, 259)!!
        assertTrue(f.failedBreak && f.dir == 1, "$f")
        // Two minutes after it came back: the lockout is over.
        assertNull(TrapGuard.stopHunt(fast + (160L until 300L).map { bar(it, 99.0, 0) }, 299))
        // A burst that holds its gain is a real move.
        assertNull(TrapGuard.stopHunt(quiet + burst + (126L until 160L).map { bar(it, 106.0, 0) }, 159))
        assertNull(TrapGuard.stopHunt(quiet.take(20), 19))
        // CONFIRM's lockout is only in the burst's direction.
        val r = OrderFlow.Read("NIFTY", 1, OrderFlow.Side.BUYERS, 80, 80, true, 100.0, 0.0, 0.0, 0.0, 0.0, 50.0, 0.0, 0.0, 0.0, 1.0,
            0.0, 0.0, Double.NaN, null, null, emptyMap(), flags = setOf(Trap.STOP_HUNT), huntDir = 1)
        assertEquals(OrderFlow.Agreement.DISAGREES, OrderFlow.agreement(r, +1, 55, 1))
        val down = r.copy(side = OrderFlow.Side.SELLERS, buyers = 20, strength = 80, cvd60 = -50.0)
        assertEquals(OrderFlow.Agreement.AGREES, OrderFlow.agreement(down, -1, 55, 1))
    }

    // ---- 9. time traps ----

    private fun ist(h: Int, m: Int, s: Int = 0, day: LocalDate = LocalDate.of(2026, 10, 9)): Long =
        day.atTime(h, m, s).atZone(java.time.ZoneId.of("Asia/Kolkata")).toEpochSecond()

    @Test fun theOpensTheClosesTheExpiryAndScheduledNewsGiveNoOpinion() {
        val nse = TrapGuard.Session.NSE; val mcx = TrapGuard.Session.MCX
        assertEquals(9 * 60 + 16, TrapGuard.istMinute(ist(9, 16)))
        assertEquals("the first 3 minutes after the open", TrapGuard.timeWindow(ist(9, 17, 30), nse))
        assertNull(TrapGuard.timeWindow(ist(9, 18), nse))
        assertFalse(TrapGuard.optionsReady(ist(9, 19), nse)); assertTrue(TrapGuard.optionsReady(ist(9, 20), nse))
        // F&O trades to 15:40 since 3 Aug 2026: its closing-price window is 15:10-15:40.
        assertEquals(15 * 60 + 40, nse.closeMin)
        assertNull(TrapGuard.timeWindow(ist(15, 1), nse))
        assertEquals("the closing settlement window (from 15:10)", TrapGuard.timeWindow(ist(15, 11), nse))
        assertEquals("the closing settlement window (from 15:10)", TrapGuard.timeWindow(ist(15, 39), nse))
        assertNull(TrapGuard.timeWindow(ist(15, 40), nse))
        assertEquals(nse, TrapGuard.Session.nse(LocalDate.of(2026, 10, 9)))
        // An older day keeps its own hours: 15:00-15:30.
        val july = TrapGuard.Session.nse(LocalDate.of(2026, 7, 31))
        assertEquals("the closing settlement window (from 15:00)", TrapGuard.timeWindow(ist(15, 1, day = LocalDate.of(2026, 7, 31)), july))
        assertNull(TrapGuard.timeWindow(ist(15, 35, day = LocalDate.of(2026, 7, 31)), july))
        assertNull(TrapGuard.timeWindow(ist(14, 45), nse))
        assertEquals("the expiry day's last hour", TrapGuard.timeWindow(ist(14, 31), nse.copy(expiryDay = true)))
        assertEquals("minutes around scheduled news", TrapGuard.timeWindow(ist(10, 4), nse.copy(eventMins = listOf(600))))
        assertNull(TrapGuard.timeWindow(ist(10, 6), nse.copy(eventMins = listOf(600))))
        assertNull(TrapGuard.timeWindow(ist(11, 0), nse)); assertNull(TrapGuard.timeWindow(ist(8, 0), nse))
        assertEquals("the first 5 minutes after the open", TrapGuard.timeWindow(ist(9, 4), mcx))
        assertEquals("the last 5 minutes before the close", TrapGuard.timeWindow(ist(23, 27), mcx))
        assertNull(TrapGuard.timeWindow(ist(20, 0), mcx))
        // News minutes: RBI 10:00 IST; US CPI 8:30 New York (18:00 IST in October, 19:00 in December); EIA on Wed/Thu for MCX.
        val oct = LocalDate.of(2026, 10, 14)                                  // a Wednesday
        assertEquals(listOf(600, 18 * 60), TrapGuard.eventMinutes(oct, listOf("RBI policy", "US CPI"), mcx = false))
        assertEquals(listOf(19 * 60), TrapGuard.eventMinutes(LocalDate.of(2026, 12, 10), listOf("US CPI"), mcx = false))
        assertEquals(listOf(20 * 60), TrapGuard.eventMinutes(oct, emptyList(), mcx = true), "EIA crude, 10:30 New York")
        assertTrue(TrapGuard.eventMinutes(LocalDate.of(2026, 10, 12), emptyList(), mcx = true).isEmpty(), "a Monday")
        assertEquals(listOf(23 * 60 + 30), TrapGuard.eventMinutes(oct, listOf("US Fed decision (FOMC)"), mcx = false))
        // No opinion in a time window.
        val r = OrderFlow.Read("NIFTY", 1, OrderFlow.Side.BUYERS, 80, 80, true, 100.0, 0.0, 0.0, 0.0, 0.0, 50.0, 0.0, 0.0, 0.0, 1.0,
            0.0, 0.0, Double.NaN, null, null, emptyMap(), flags = setOf(Trap.TIME_WINDOW), timeWhy = "the first 3 minutes after the open")
        assertEquals(OrderFlow.Agreement.UNKNOWN, OrderFlow.agreement(r, +1, 55, 1))
        assertEquals("time window: the first 3 minutes after the open", OrderFlow.trapWords(r))
    }

    // ---- 10, 11. the feed and illiquid prints ----

    @Test fun feedHealth() {
        assertTrue(TrapGuard.crossed(100.05, 100.05)); assertTrue(TrapGuard.crossed(100.1, 100.0)); assertFalse(TrapGuard.crossed(100.0, 100.05))
        assertFalse(TrapGuard.crossed(0.0, 100.0))
        assertTrue(TrapGuard.illiquidPrint(600, 100.0, priceMoved = false))
        assertFalse(TrapGuard.illiquidPrint(600, 100.0, priceMoved = true))
        assertFalse(TrapGuard.illiquidPrint(400, 100.0, priceMoved = false)); assertFalse(TrapGuard.illiquidPrint(600, null, false))
        val steady = List(200) { 2 }
        assertTrue(TrapGuard.stuffing(steady, 30, 0)); assertFalse(TrapGuard.stuffing(steady, 30, 5)); assertFalse(TrapGuard.stuffing(steady, 8, 0))
        assertFalse(TrapGuard.stuffing(emptyList(), 30, 0))
        // Lateness above the steady offset (a phone clock 4 s off is not late).
        assertEquals(0.0, TrapGuard.lateness(listOf(4_000L, 4_000L, 4_000L))!!, 1e-9)
        assertEquals(3_500.0, TrapGuard.lateness(listOf(4_000L, 7_500L, 7_500L, 8_000L))!!, 1e-9)
        assertNull(TrapGuard.lateness(listOf(1L)))
    }

    @Test fun olderStatesCrossedBooksQuietFuturesAndLateTradesOnTheBoard() {
        val b = board()
        var vol = 0L
        for (s in 0 until 300) { vol += 10; b.offer(tick(1, 100.0 + (s % 2) * 0.05, vol, book(listOf(100.0 to 100L), listOf(100.05 to 100L)), t0 / 1000 + s), t0 + s * 1000L) }
        assertTrue(b.read("NIFTY", t0 + 299_500L)!!.flags.none { it == Trap.UNRELIABLE || it == Trap.BOOK_OFF })
        // A crossed book and an older state re-sent are dropped.
        assertFalse(b.offer(tick(1, 100.0, vol + 10, book(listOf(100.1 to 100L), listOf(100.05 to 100L))), t0 + 299_600L))
        assertFalse(b.offer(tick(1, 100.0, vol - 50, book(listOf(100.0 to 100L), listOf(100.05 to 100L))), t0 + 299_700L))
        assertFalse(b.offer(tick(1, 100.0, vol - 60, book(listOf(100.0 to 100L), listOf(100.05 to 100L))), t0 + 299_800L))
        assertTrue(Trap.UNRELIABLE in b.read("NIFTY", t0 + 299_900L)!!.flags, "three bad packets in a minute")
        // The future quiet for 6 s: the book's parts are off.
        assertTrue(Trap.BOOK_OFF in b.read("NIFTY", t0 + 306_000L)!!.flags)
        // Trades arriving 5 s late against their usual: the book's parts off; 12 s: unreliable.
        val late = board()
        var v = 0L
        for (s in 0 until 300) { v += 10; late.offer(tick(1, 100.0 + (s % 2) * 0.05, v, book(listOf(100.0 to 100L), listOf(100.05 to 100L)), t0 / 1000 + s), t0 + s * 1000L) }
        for (s in 300 until 335) { v += 10; late.offer(tick(1, 100.0 + (s % 2) * 0.05, v, book(listOf(100.0 to 100L), listOf(100.05 to 100L)), t0 / 1000 + s - 5), t0 + s * 1000L) }
        val r = late.read("NIFTY", t0 + 334_500L)!!
        assertTrue(Trap.BOOK_OFF in r.flags && Trap.UNRELIABLE !in r.flags, "${r.flags} ${r.lateMs}")
        for (s in 335 until 370) { v += 10; late.offer(tick(1, 100.0 + (s % 2) * 0.05, v, book(listOf(100.0 to 100L), listOf(100.05 to 100L)), t0 / 1000 + s - 12), t0 + s * 1000L) }
        assertTrue(Trap.UNRELIABLE in late.read("NIFTY", t0 + 369_500L)!!.flags)
    }
}
