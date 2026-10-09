package com.optionslab.ira

import com.optionslab.engine.KiteTicks
import com.optionslab.ira.OrderFlow.Agreement
import com.optionslab.ira.OrderFlow.BuildUp
import com.optionslab.ira.OrderFlow.Mode
import com.optionslab.ira.OrderFlow.Role
import com.optionslab.ira.OrderFlow.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The live order flow's pure parts ([OrderFlow]): each formula, one instrument's seconds, the combined read, the gate. */
class OrderFlowTest {
    /** 9 Oct 2026, 10:45 IST (epoch ms): inside NSE's hours, clear of its time traps for the next 15 minutes. */
    private val T0 = java.time.LocalDateTime.of(2026, 10, 9, 10, 45).atZone(java.time.ZoneId.of("Asia/Kolkata")).toEpochSecond() * 1000

    // ---- OFI (Cont-Kukanov-Stoikov) ----

    @Test fun ofiFollowsTheBestBidAndOffer() {
        // Same prices: the size changes alone (bid +30, offer -20: both buying pressure) = 30 + 20.
        assertEquals(50.0, OrderFlow.ofi(100.0, 70, 100.5, 80, 100.0, 100, 100.5, 60))
        // The bid steps up: its whole new queue counts; the offer unchanged.
        assertEquals(40.0, OrderFlow.ofi(100.0, 70, 100.5, 80, 100.05, 40, 100.5, 80))
        // The bid drops away: its old queue was taken out (sell pressure).
        assertEquals(-70.0, OrderFlow.ofi(100.0, 70, 100.5, 80, 99.95, 55, 100.5, 80))
        // The offer is lifted (moves up): its old queue counts as buying.
        assertEquals(80.0, OrderFlow.ofi(100.0, 70, 100.5, 80, 100.0, 70, 100.55, 90))
        // The offer drops (sellers come down): its new queue is selling pressure.
        assertEquals(-25.0, OrderFlow.ofi(100.0, 70, 100.5, 80, 100.0, 70, 100.45, 25))
        // Nothing changed: no flow.
        assertEquals(0.0, OrderFlow.ofi(100.0, 70, 100.5, 80, 100.0, 70, 100.5, 80))
    }

    // ---- depth, queue and the total quantities ----

    @Test fun imbalancesAndTheTotalRatio() {
        assertEquals(0.5, OrderFlow.imbalance(300, 100)!!, 1e-12)
        assertEquals(-1.0, OrderFlow.imbalance(0, 100)!!, 1e-12)
        assertEquals(0.0, OrderFlow.imbalance(50, 50)!!, 1e-12)
        assertNull(OrderFlow.imbalance(0, 0)); assertNull(OrderFlow.imbalance(-1, 5))
        assertEquals(2.0, OrderFlow.ratio(200, 100)!!, 1e-12)
        assertNull(OrderFlow.ratio(null, 100)); assertNull(OrderFlow.ratio(100, 0))
    }

    // ---- tick and quote rules ----

    @Test fun tradesAreSignedByTheQuoteRuleThenTheTickRule() {
        // At or above the offer: a buy; at or below the bid: a sell.
        assertEquals(1, OrderFlow.sign(100.5, 100.0, 100.5, 100.2, 0))
        assertEquals(1, OrderFlow.sign(100.6, 100.0, 100.5, 100.2, -1))
        assertEquals(-1, OrderFlow.sign(100.0, 100.0, 100.5, 100.2, 1))
        // Inside the spread: by the midpoint (100.25).
        assertEquals(1, OrderFlow.sign(100.3, 100.0, 100.5, 100.4, -1))
        assertEquals(-1, OrderFlow.sign(100.2, 100.0, 100.5, 100.0, 1))
        // On the mid: the tick rule; a zero tick keeps the last sign.
        assertEquals(1, OrderFlow.sign(100.25, 100.0, 100.5, 100.2, -1))
        assertEquals(-1, OrderFlow.sign(100.25, 100.0, 100.5, 100.3, 1))
        assertEquals(-1, OrderFlow.sign(100.25, 100.0, 100.5, 100.25, -1))
        // No book at all: the tick rule.
        assertEquals(1, OrderFlow.sign(10.0, null, null, 9.0, 0))
        assertEquals(0, OrderFlow.sign(10.0, null, null, null, 0))
    }

    // ---- OI build-up ----

    @Test fun theMinutesBuildUp() {
        assertEquals(BuildUp.LONG_BUILDUP, OrderFlow.buildUp(12.0, 3000))
        assertEquals(BuildUp.SHORT_BUILDUP, OrderFlow.buildUp(-12.0, 3000))
        assertEquals(BuildUp.SHORT_COVERING, OrderFlow.buildUp(12.0, -3000))
        assertEquals(BuildUp.LONG_UNWINDING, OrderFlow.buildUp(-12.0, -3000))
        assertEquals(BuildUp.NEUTRAL, OrderFlow.buildUp(0.0, 3000))
        assertEquals(BuildUp.NEUTRAL, OrderFlow.buildUp(5.0, 0))
        assertEquals(1, OrderFlow.lean(BuildUp.SHORT_COVERING)); assertEquals(-1, OrderFlow.lean(BuildUp.LONG_UNWINDING))
        assertEquals(0, OrderFlow.lean(BuildUp.NEUTRAL)); assertEquals(0, OrderFlow.lean(null))
    }

    @Test fun minuteChangeComparesTheLastTwoCompletedMinutes() {
        fun bar(sec: Long, last: Double, oi: Long) = OrderFlow.Bar(sec, last, last, 0.0, 0, 0, 0, 0.0, 0.0, 1.0, oi)
        val bars = listOf(bar(600, 100.0, 1000), bar(659, 101.0, 1100), bar(700, 103.0, 1300), bar(719, 104.0, 1500), bar(725, 99.0, 900))
        // Now in minute 12 (720..779): minute 11 closed at 104 / 1500, minute 10 at 101 / 1100.
        assertEquals(3.0 to 400L, OrderFlow.minuteChange(bars, 725))
        assertNull(OrderFlow.minuteChange(bars.take(2), 665))
    }

    // ---- z-score and the strength ----

    @Test fun zScoreAgainstTheInstrumentsOwnHistory() {
        val h = DoubleArray(100) { (it % 10).toDouble() }        // mean 4.5, sd ~2.87
        assertEquals((9.0 - 4.5) / 2.8722813232690143, OrderFlow.z(9.0, h)!!, 1e-9)
        assertEquals(4.0, OrderFlow.z(1e6, h)!!, 0.0)               // clipped
        assertNull(OrderFlow.z(1.0, DoubleArray(10)))                // too little history
        assertEquals(0.0, OrderFlow.z(3.0, DoubleArray(40) { 3.0 })!!, 0.0)
        assertEquals(-4.0, OrderFlow.z(2.0, DoubleArray(40) { 3.0 })!!, 0.0)
        assertNull(OrderFlow.z(Double.NaN, h))
        assertNull(OrderFlow.z(1.0, DoubleArray(40) { Double.NaN }))
    }

    @Test fun strengthAndSide() {
        assertEquals(50, OrderFlow.buyers(0.0))
        assertEquals(84, OrderFlow.buyers(1.0))
        assertEquals(16, OrderFlow.buyers(-1.0))
        assertEquals(0.9772, OrderFlow.cdf(2.0), 1e-4)
        assertEquals(Side.BUYERS, OrderFlow.sideOf(72)); assertEquals(72, OrderFlow.strength(72))
        assertEquals(Side.SELLERS, OrderFlow.sideOf(36)); assertEquals(64, OrderFlow.strength(36))
        assertEquals(Side.BALANCED, OrderFlow.sideOf(52)); assertEquals(52, OrderFlow.strength(52)); assertEquals(53, OrderFlow.strength(47))
        assertEquals(Side.BUYERS, OrderFlow.sideOf(55)); assertEquals(Side.SELLERS, OrderFlow.sideOf(45))
        // The known parts averaged by their weights; none known: 0.
        assertEquals(1.0, OrderFlow.score(mapOf("cvd60" to 1.0, "depth60" to 1.0, "ofi" to null)), 1e-12)
        assertEquals((0.22 * 2 + 0.22 * -1) / 0.44, OrderFlow.score(mapOf("cvd60" to 2.0, "depth60" to -1.0)), 1e-12)
        // CONFIRM's core is the future's 5-level imbalance and tick-rule volume over 60 s and 300 s (HUNT R7).
        assertEquals(0.68, listOf("cvd60", "cvd300", "depth60", "depth300").sumOf { OrderFlow.WEIGHTS.getValue(it) }, 1e-9)
        assertEquals(1.0, OrderFlow.WEIGHTS.values.sum(), 1e-9)
        assertTrue("ratio" !in OrderFlow.WEIGHTS, "Kite's totals are shown only (easy to spoof)")
        assertEquals(0.0, OrderFlow.score(mapOf("depth" to null, "nope" to 3.0)), 0.0)
    }

    // ---- the gate ----

    /** A read with [buyers] and the executed flow (tick-rule volume) on the same side. */
    private fun read(buyers: Int, warm: Boolean = true, at: Long = 1000) = OrderFlow.Read("BANKNIFTY", at, OrderFlow.sideOf(buyers),
        OrderFlow.strength(buyers), buyers, warm, 100.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, Double.NaN, null, null, emptyMap())
        .copy(cvd60 = (buyers - 50) * 10.0)

    @Test fun aCallNeedsBuyersAndAPutNeedsSellers() {
        assertEquals(Agreement.AGREES, OrderFlow.agreement(read(55), +1, 55, 1000))
        assertEquals(Agreement.DISAGREES, OrderFlow.agreement(read(54), +1, 55, 1000))
        assertEquals(Agreement.AGREES, OrderFlow.agreement(read(40), -1, 55, 1000))        // sellers 60
        assertEquals(Agreement.DISAGREES, OrderFlow.agreement(read(72), -1, 55, 1000))
        // A higher bar: buyers 72 is not enough for 75.
        assertEquals(Agreement.DISAGREES, OrderFlow.agreement(read(72), +1, 75, 1000))
        // The bar is kept within 50-80.
        assertEquals(Agreement.AGREES, OrderFlow.agreement(read(80), +1, 99, 1000))
        // No read, a cold one, a stale one, no direction: unknown.
        assertEquals(Agreement.UNKNOWN, OrderFlow.agreement(null, +1, 55, 1000))
        assertEquals(Agreement.UNKNOWN, OrderFlow.agreement(read(90, warm = false), +1, 55, 1000))
        assertEquals(Agreement.UNKNOWN, OrderFlow.agreement(read(90, at = 990), +1, 55, 1000))
        assertEquals(Agreement.UNKNOWN, OrderFlow.agreement(read(90), 0, 55, 1000))
        // The trap guard: depth alone never agrees - the executed flow must be on the trade's side.
        assertEquals(Agreement.DISAGREES, OrderFlow.agreement(read(80).copy(cvd60 = -5.0), +1, 55, 1000))
        assertEquals(Agreement.DISAGREES, OrderFlow.agreement(read(80).copy(cvd60 = 0.0), +1, 55, 1000))
        // A stop run in the last minute: no entry agrees; an unreliable feed or a time trap: no opinion.
        assertEquals(Agreement.DISAGREES, OrderFlow.agreement(read(80).copy(flags = setOf(TrapGuard.Trap.STOP_HUNT), huntDir = 1), +1, 55, 1000))
        assertEquals(Agreement.UNKNOWN, OrderFlow.agreement(read(80).copy(flags = setOf(TrapGuard.Trap.UNRELIABLE)), +1, 55, 1000))
        assertEquals(Agreement.UNKNOWN, OrderFlow.agreement(read(20).copy(flags = setOf(TrapGuard.Trap.TIME_WINDOW)), +1, 55, 1000))
    }

    @Test fun onlyConfirmSkipsAndOnlyWhenTheFlowDoesNotAgree() {
        for (a in Agreement.entries) {
            assertFalse(OrderFlow.skips(Mode.OFF, a)); assertFalse(OrderFlow.skips(Mode.SHADOW, a))
        }
        assertFalse(OrderFlow.skips(Mode.CONFIRM, Agreement.AGREES))
        assertTrue(OrderFlow.skips(Mode.CONFIRM, Agreement.DISAGREES))
        // No opinion (no read, an unreliable feed, a time trap) never skips: the trade follows the strategy, logged so.
        assertFalse(OrderFlow.skips(Mode.CONFIRM, Agreement.UNKNOWN))
        // Confirm on a strategy that can reach Zerodha, in Live: the PIN first. Paper, or a paper-only bot: no PIN.
        assertTrue(OrderFlow.needsPin(Mode.CONFIRM, live = true, liveCapable = true))
        assertFalse(OrderFlow.needsPin(Mode.CONFIRM, live = false, liveCapable = true))
        assertFalse(OrderFlow.needsPin(Mode.CONFIRM, live = true, liveCapable = false))
        assertFalse(OrderFlow.needsPin(Mode.SHADOW, live = true, liveCapable = true))
    }

    @Test fun atmStrikesTwoEachSide() {
        val s = (0..20).map { 54_000.0 + 100 * it }
        assertEquals(listOf(54_800.0, 54_900.0, 55_000.0, 55_100.0, 55_200.0), OrderFlow.atmStrikes(s, 55_030.0))
        assertEquals(listOf(54_000.0, 54_100.0, 54_200.0), OrderFlow.atmStrikes(s, 53_000.0))
        assertTrue(OrderFlow.atmStrikes(emptyList(), 55_000.0).isEmpty())
        assertTrue(OrderFlow.atmStrikes(s, 0.0).isEmpty())
    }

    // ---- one instrument, then the board ----

    private fun tick(token: Long, last: Double, volume: Long, bid: Double, bidQty: Long, ask: Double, askQty: Long, oi: Long = 1_000_000,
                     buy: Long = 5000, sell: Long = 5000, levels: Long = 0) = KiteTicks.Tick(token, last, volume = volume, oi = oi, bid = bid, ask = ask,
        bidQty = bidQty, askQty = askQty, buyQty = buy, sellQty = sell,
        depth = KiteTicks.Depth(listOf(KiteTicks.Level(bid, bidQty, 3), KiteTicks.Level(bid - 0.05, levels, 1)),
            listOf(KiteTicks.Level(ask, askQty, 3), KiteTicks.Level(ask + 0.05, 0, 0))))

    @Test fun aQuoteNeedsTheFullModeBook() {
        assertNull(OrderFlow.quoteOf(KiteTicks.Tick(1, 100.0), 0))
        assertNull(OrderFlow.quoteOf(KiteTicks.Tick(1, 100.0, depth = KiteTicks.Depth(emptyList(), listOf(KiteTicks.Level(1.0, 1, 1)))), 0))
        val q = OrderFlow.quoteOf(tick(1, 100.0, 10, 99.95, 30, 100.05, 20, levels = 70), 5)!!
        assertEquals(100L, q.bidDepth); assertEquals(20L, q.askDepth); assertEquals(30L, q.bidQty)
    }

    @Test fun aTrackerBuildsSecondsWithSignedVolume() {
        val t = OrderFlow.Tracker(7, "BANKNIFTY", Role.FUTURE)
        t.offer(OrderFlow.quoteOf(tick(7, 100.0, 1000, 99.95, 50, 100.05, 50), 10_000)!!)
        // 30 traded at the offer (a buy), the offer's queue eaten 50 -> 20.
        t.offer(OrderFlow.quoteOf(tick(7, 100.05, 1030, 99.95, 50, 100.05, 20), 10_400)!!)
        // Next second: 40 traded at the bid (a sell).
        t.offer(OrderFlow.quoteOf(tick(7, 99.95, 1070, 99.95, 10, 100.05, 20), 11_100)!!)
        val bars = t.bars()
        assertEquals(listOf(10L, 11L), bars.map { it.sec })
        assertEquals(30L, bars[0].buyVol); assertEquals(0L, bars[0].sellVol); assertEquals(30.0, bars[0].ofi)
        assertEquals(40L, bars[1].sellVol); assertEquals(-40L, bars[1].delta); assertEquals(-40.0, bars[1].ofi)
        // The quote rule agrees here (at the offer, then at the bid): kept beside the tick rule's.
        assertEquals(30L, bars[0].qDelta); assertEquals(-40L, bars[1].qDelta)
        assertEquals(100.0, bars[1].mid, 1e-9)
        assertEquals(listOf(10L), t.closedAfter(0).map { it.sec })
        assertTrue(t.closedAfter(10).isEmpty())
        assertEquals(3L, t.quotes)
        // Feed hygiene: an exact repeat is dropped, and so is an older state re-sent (its volume below the running maximum).
        assertFalse(t.offer(OrderFlow.quoteOf(tick(7, 99.95, 1070, 99.95, 10, 100.05, 20), 11_500)!!))
        assertFalse(t.offer(OrderFlow.quoteOf(tick(7, 100.05, 1030, 99.95, 50, 100.05, 20), 11_600)!!))
        assertEquals(1L, t.repeats); assertEquals(1L, t.stale); assertEquals(3L, t.quotes)
        assertEquals(40L, t.bars().last().sellVol)
        // A book-only update (same volume, the queue changed) is taken: no trade, the OFI moves.
        assertTrue(t.offer(OrderFlow.quoteOf(tick(7, 99.95, 1070, 99.95, 60, 100.05, 20), 11_700)!!))
        assertEquals(10.0, t.bars().last().ofi)
        // After half an hour of silence a lower volume is a new session: taken, and no trade counted.
        assertTrue(t.offer(OrderFlow.quoteOf(tick(7, 99.95, 5, 99.95, 10, 100.05, 20), 11_700 + 31 * 60_000L)!!))
        assertEquals(0L, t.bars().last().vol)
    }

    @Test fun rollingAndWindowSums() {
        fun b(sec: Long, ofi: Double) = OrderFlow.Bar(sec, 1.0, 1.0, ofi, 0, 0, 0, 0.0, 0.0, 1.0, 0)
        val bars = listOf(b(1, 1.0), b(2, 2.0), b(5, 4.0), b(70, 8.0))
        assertEquals(listOf(1.0, 3.0, 7.0, 8.0), OrderFlow.rolling(bars, 60) { it.ofi }.toList())
        assertEquals(12.0, OrderFlow.window(bars, 70, 66) { it.ofi })
        assertEquals(8.0, OrderFlow.window(bars, 70, 10) { it.ofi })
        assertEquals(7.0, OrderFlow.window(bars, 60, 300) { it.ofi })
    }

    /** A future and two options fed [secs] seconds of quiet two-way flow, then [push] seconds of one-sided buying (or selling). */
    private fun board(secs: Int, push: Int, up: Boolean): OrderFlow.Board {
        val b = OrderFlow.Board()
        b.layout(listOf(OrderFlow.Board.Entry(1, "BANKNIFTY", Role.FUTURE), OrderFlow.Board.Entry(2, "BANKNIFTY", Role.CE, 55_000.0),
            OrderFlow.Board.Entry(3, "BANKNIFTY", Role.PE, 55_000.0)))
        var vol = 0L; var ceVol = 0L; var peVol = 0L
        var px = 55_000.0
        for (s in 0 until secs + push) {
            val ms = T0 + s * 1000L
            val pushing = s >= secs
            val dir = if (up) 1 else -1
            repeat(2) { k ->
                vol += 10
                // Quiet: alternate trades at the bid and the offer with an even book; pushing: all at one side, a lopsided book.
                val buy = if (pushing) up else (s + k) % 2 == 0
                if (pushing) px += dir * 0.5
                val last = if (buy) px + 0.5 else px - 0.5
                val bidQ = if (pushing) (if (up) 400L else 40L) else 100L
                val askQ = if (pushing) (if (up) 40L else 400L) else 100L
                b.offer(tick(1, last, vol, px - 0.5, bidQ + (if (pushing) dir * s.toLong() else 0L).coerceAtLeast(0), px + 0.5, askQ,
                    oi = 1_000_000L + (if (pushing) 50L * s else 0L), buy = if (pushing && up) 9000 else 5000, sell = if (pushing && !up) 9000 else 5000),
                    ms + k * 400)
                ceVol += 5; peVol += 5
                val ceBuy = if (pushing) up else k == 0
                b.offer(tick(2, if (ceBuy) 201.0 else 199.0, ceVol, 199.0, 50, 201.0, 50), ms + k * 400 + 10)
                b.offer(tick(3, if (ceBuy) 199.0 else 201.0, peVol, 199.0, 50, 201.0, 50), ms + k * 400 + 20)
            }
            if (s % 5 == 0) b.read("BANKNIFTY", ms)
        }
        return b
    }

    @Test fun theBoardReadsBuyersWhenTheFutureIsBought() {
        val b = board(600, 80, up = true)
        val now = T0 + 679 * 1000L + 900
        val r = b.read("BANKNIFTY", now)!!
        assertTrue(r.warm)
        assertEquals(Side.BUYERS, r.side)
        assertTrue(r.buyers >= 70, "${r.buyers}")
        assertTrue(r.ofi60 > 0 && r.cvd60 > 0, "${r.ofi60} ${r.cvd60}")
        assertTrue(r.optionNet > 0, "calls bought: ${r.optionNet}")
        assertEquals(BuildUp.LONG_BUILDUP, r.buildUp)
        assertTrue(r.depth > 0 && r.ratio > 1)
        assertEquals(Agreement.AGREES, OrderFlow.agreement(r, +1, 55, now / 1000))
        assertEquals(Agreement.DISAGREES, OrderFlow.agreement(r, -1, 55, now / 1000))
        assertTrue(b.history("BANKNIFTY").isNotEmpty())
        assertEquals(3, b.coverage(now).followed)
        assertEquals("Flow BN: buyers ${r.strength}" + if (r.flags.isEmpty()) "" else " ⚠", OrderFlow.line("BANKNIFTY", r))
        assertEquals("Flow NF: no flow", OrderFlow.line("NIFTY", null))
        assertEquals(11, OrderFlow.components(r).size)
        // The guard let it through: nothing neutralised it (a moving book is pulled on the side the price runs through).
        assertEquals(r.rawBuyers, r.buyers)
        assertTrue(TrapGuard.Trap.ABSORPTION_BUY !in r.flags && TrapGuard.Trap.STOP_HUNT !in r.flags && TrapGuard.Trap.UNRELIABLE !in r.flags, "${r.flags}")
        OrderFlow.bookText(tick(1, 100.0, 1, 99.95, 30, 100.05, 20).depth!!).let { b ->
            assertTrue(b.startsWith("b 99.95x30/3 ") && ";a 100.05x20/3 " in b && '|' !in b, b)
        }
        assertTrue(b.closedAfter(0).any { it.first.role == Role.CE })
    }

    @Test fun theBoardReadsSellersWhenTheFutureIsSold() {
        val b = board(600, 80, up = false)
        val r = b.read("BANKNIFTY", T0 + 679 * 1000L + 900)!!
        assertEquals(Side.SELLERS, r.side)
        assertTrue(r.buyers <= 30, "${r.buyers}")
        assertEquals(BuildUp.SHORT_BUILDUP, r.buildUp)
        assertTrue(r.optionNet < 0)
    }

    @Test fun aNewBoardIsColdAndKeepsHistoryAcrossALayout() {
        val b = board(30, 0, up = true)
        val r = b.read("BANKNIFTY", T0 + 29_900)!!
        assertFalse(r.warm)
        assertEquals("warming up", OrderFlow.word(r))
        assertNull(b.read("NIFTY", 0))
        // The same instruments laid out again keep their seconds; one dropped is forgotten.
        b.layout(listOf(OrderFlow.Board.Entry(1, "BANKNIFTY", Role.FUTURE)))
        assertEquals(setOf(1L), b.tokens())
        assertNotNull(b.read("BANKNIFTY", T0 + 29_900))
        assertFalse(b.offer(KiteTicks.Tick(99, 1.0), 0), "an instrument not followed")
        assertFalse(b.offer(KiteTicks.Tick(1, 1.0), 0), "no book: not full mode")
        assertEquals(1, b.entries().size)
    }

    // ---- words and Jarvis ----

    @Test fun jarvisIsAskedTheOrderFlow() {
        assertEquals("BANKNIFTY", OrderFlow.asked("what is the order flow on banknifty?"))
        assertEquals("BANKNIFTY", OrderFlow.asked("Order flow Bank Nifty"))
        assertEquals("NIFTY", OrderFlow.asked("nifty me buyers or sellers?"))
        assertEquals("FINNIFTY", OrderFlow.asked("orderflow finnifty"))
        assertEquals("MIDCPNIFTY", OrderFlow.asked("order flow on midcap nifty"))
        assertEquals("", OrderFlow.asked("what's the order flow"))
        assertEquals("BANKNIFTY", OrderFlow.asked("bank nifty mein kaun kharid raha hai"))
        assertNull(OrderFlow.asked("how is order flow helping?"))
        assertNull(OrderFlow.asked("turn on order flow confirm for orb"))
        assertNull(OrderFlow.asked("what is the price of banknifty"))
        assertNull(OrderFlow.asked("what is an order flow"))
        // Never read as an order or a command (the hub's route asks for neither).
        for (q in listOf("what is the order flow on banknifty?", "order flow nifty", "how is order flow helping?", "bank nifty mein kaun kharid raha hai"))
            Ask.parse(q).let { assertTrue(it.order == null && it.command == null, q) }
    }

    @Test fun jarvisSaysTheReadAsFacts() {
        assertTrue(OrderFlow.answer("BANKNIFTY", null, 100).startsWith("I have no live order flow for BankNifty"))
        assertTrue("warming up" in OrderFlow.answer("NIFTY", read(70, warm = false, at = 100), 100))
        val r = read(72, at = 100).copy(ofi60 = 1200.0, cvd60 = 800.0, depth = 0.2, optionNet = 0.4, buildUp = BuildUp.LONG_BUILDUP)
        val a = OrderFlow.answer("BANKNIFTY", r, 101)
        assertTrue(a.startsWith("BankNifty's order flow, from its future: buyers are in control, strength 72."), a)
        assertTrue("calls bought more than puts" in a && "long build-up" in a && "not a forecast" in a, a)
        assertTrue("sellers are in control" in OrderFlow.answer("BANKNIFTY", read(30, at = 100).copy(optionNet = -0.5), 100))
        assertTrue("balanced" in OrderFlow.answer("BANKNIFTY", read(50, at = 100), 100))
        assertTrue(OrderFlow.answer("BANKNIFTY", read(70, at = 10), 100).startsWith("I have no live"))
        assertEquals("BankNifty", OrderFlow.label("BANKNIFTY")); assertEquals("Crudeoil", OrderFlow.label("CRUDEOIL"))
        assertEquals("CRUDEOIL", OrderFlow.short("CRUDEOIL")); assertEquals("no flow", OrderFlow.word(null))
        val c = OrderFlow.components(read(50).copy(ratio = Double.NaN))
        assertTrue(c.any { it.second == "no options followed" } && c.any { it.second == "not known yet" })
    }
}
