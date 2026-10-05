package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScenariosTest {
    private val today = LocalDate.of(2026, 10, 5)

    /** A whole Nifty session: [prev] the close before it; usual range 100 points. */
    private fun day(d: LocalDate, prev: Double, o: Double, h: Double, l: Double, c: Double, vix: Double? = null, vixPct: Double? = null,
                    m: Market = Market.NIFTY) =
        MarketMemory.Day(m, d, o, h, l, c, prev, (o + c) / 2, 100.0, true, false, vix, vixPct)

    /** 25 quiet sessions at 24,000 (VIX 13, flat) and three gap-down days among them. */
    private val days: List<MarketMemory.Day> = run {
        val dates = generateSequence(today.minusDays(1)) { it.minusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(25).toList().reversed()
        dates.mapIndexed { k, d ->
            when (k) {
                // -1.25% gap, filled (high back to 24,000), closed above the previous close.
                5 -> day(d, 24000.0, 23700.0, 24050.0, 23650.0, 24020.0, 15.0, 15.4)
                // -1.5% gap, not filled, closed lower: a wide day.
                12 -> day(d, 24000.0, 23640.0, 23800.0, 23450.0, 23500.0, 18.0, 38.5)
                // -0.6% gap: smaller than 1%.
                18 -> day(d, 24000.0, 23856.0, 24000.0, 23800.0, 23900.0, 13.5, 3.8)
                // A 2% fall on the day, VIX to 21.
                20 -> day(d, 24000.0, 23990.0, 24000.0, 23500.0, 23520.0, 21.0, 25.0)
                else -> day(d, 24000.0, 24000.0, 24050.0, 23950.0, 24000.0, 13.0, 0.0)
            }
        }
    }

    private val ce = Exposure.Leg("Zerodha", "NIFTY26OCT24000CE", 75, 100.0, 120.0, "NIFTY", 0.5, 0.001)
    private val pe = Exposure.Leg("Paper", "NIFTY26OCT23800PE", -75, 90.0, 60.0, "NIFTY", -0.4, 0.001)

    private fun input(s: Scenarios.Scenario, mine: Scenarios.Mine? = null, locked: Boolean = false, spot: Double? = 24000.0,
                      vix: Double? = 13.0, vixPrev: Double? = 12.5, expiry: Boolean = false) =
        Scenarios.Input(s, days, today, spot, vix, vixPrev, expiry, mine, locked)

    // ---- the question ---------------------------------------------------------------------------------------------

    @Test fun readsTheScenario() {
        val a = Scenarios.asked("What if Nifty opens 1% down tomorrow?")!!
        assertEquals(Scenarios.Kind.OPEN, a.kind); assertEquals(Market.NIFTY, a.market); assertEquals(1.0, a.pct); assertEquals(-1, a.sign); assertTrue(a.next)
        val b = Scenarios.asked("suppose BankNifty gaps up 300 points")!!
        assertEquals(Scenarios.Kind.OPEN, b.kind); assertEquals(Market.BANKNIFTY, b.market); assertEquals(300.0, b.points); assertEquals(1, b.sign); assertTrue(b.next)
        val c = Scenarios.asked("Jarvis, what if the market falls 2%")!!
        assertEquals(Scenarios.Kind.MOVE, c.kind); assertEquals(Market.NIFTY, c.market); assertEquals(-1, c.sign); assertFalse(c.next)
        val d = Scenarios.asked("what if VIX goes to 20?")!!
        assertEquals(Scenarios.Kind.VIX_LEVEL, d.kind); assertEquals(20.0, d.level); assertEquals(Market.NIFTY, d.market)
        val e = Scenarios.asked("what if india vix jumps 15%")!!
        assertEquals(Scenarios.Kind.VIX_MOVE, e.kind); assertEquals(15.0, e.pct); assertEquals(1, e.sign)
        val f = Scenarios.asked("what if nifty hits 23,500")!!
        assertEquals(Scenarios.Kind.LEVEL, f.kind); assertEquals(23500.0, f.level)
        assertEquals(Scenarios.Kind.OPEN, Scenarios.asked("what will happen if nifty opens 1 percent lower")!!.kind)
        assertEquals(Scenarios.Kind.MOVE, Scenarios.asked("if nifty drops 150 points, then what?")!!.kind)
        assertEquals(Scenarios.Kind.VIX_LEVEL, Scenarios.asked("what if VIX goes to 20, what about BankNifty?")!!.kind)
        assertEquals(Market.BANKNIFTY, Scenarios.asked("what if VIX goes to 20, what about BankNifty?")!!.market)
        assertEquals(Scenarios.Kind.MOVE, Scenarios.asked("what if Nifty falls 2% and VIX jumps")!!.kind)   // Nifty named first
    }

    @Test fun notAWhatIf() {
        assertNull(Scenarios.asked("Nifty opened 1% down today"))                       // no "what if"
        assertNull(Scenarios.asked("if nifty falls 100 points"))                         // a bare if, nothing asked
        assertNull(Scenarios.asked("what if gold falls 2%"))                             // gold: no session to read
        assertNull(Scenarios.asked("what if I had taken your trade"))                    // the replay of his ideas
        assertNull(Scenarios.asked("what if I buy the 24000 CE and Nifty rises 1%"))     // a new trade of his
        assertNull(Scenarios.asked("what if nifty falls 1%, should I sell?"))            // advice
        assertNull(Scenarios.asked("what if nifty falls 1%, will it recover?"))          // a forecast
        assertNull(Scenarios.asked("what if nifty does something"))                      // no size or price
        assertNull(Scenarios.asked("what if nifty falls 50%"))                           // not a day's move
        assertNull(Scenarios.asked("when did nifty last gap down 1%?"))                  // market memory
    }

    /** Exposure keeps "what happens to my P&L if Nifty moves 100", and the what-if questions reach no other answer first. */
    @Test fun routing() {
        val exposure = listOf("What happens to my P&L if Nifty moves 100 points?", "Jarvis, if BankNifty falls 1.5% what do I lose",
            "suppose nifty goes up 200 points, how do my positions do", "what if nifty falls 2%, what happens to my positions")
        for (q in exposure) { assertNotNull(Exposure.moveAsked(q), q); assertNull(Scenarios.asked(q), q) }
        val ours = listOf("What if Nifty opens 1% down tomorrow?", "what if VIX goes to 20?", "suppose BankNifty gaps up 300 points",
            "what if the market falls 2%", "what if nifty hits 23500", "what if india vix jumps 15%")
        for (q in ours) {
            assertNotNull(Scenarios.asked(q), q)
            assertNull(Exposure.moveAsked(q), q)
            assertNull(MarketMemory.asked(q), q)
            assertFalse(TradeCase.asked(q), q)
            assertFalse(Thinking.asked(q) != null, q)
            // Never an order or an app command (the app reaches this only when the words ask for neither).
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
        }
        // "What if I had taken it" stays the replay section.
        assertTrue(Section.WHATIF in AppAnswers.sections("what if I had taken your trade"))
    }

    // ---- the answer -----------------------------------------------------------------------------------------------

    @Test fun gapDownFromPastSessions() {
        val a = Scenarios.answer(input(Scenarios.asked("What if Nifty opens 1% down tomorrow?")!!))
        assertTrue(a.contains("a what-if, Boss, not a forecast"), a)
        assertTrue(a.contains("2 sessions opened 1.00% or more lower"), a)
        assertTrue(a.contains("filled (the price came back to the previous close) on 1 of 2"), a)
        assertTrue(a.contains("1 of 2 still closed on the gap's side"), a)
        assertTrue(a.contains(MarketMemory.NOT_A_PROMISE), a)
        // Locked by default here? No: mine is null and not locked - the positions could not be read.
        assertTrue(a.contains("couldn't read your positions"), a)
        assertTrue(a.contains("I tell a filled gap of 0.2% or more"), a)
        assertTrue(a.endsWith(Scenarios.YOURS), a)
    }

    @Test fun noneLikeIt() {
        val a = Scenarios.answer(input(Scenarios.asked("what if nifty opens 3% lower")!!))
        assertTrue(a.contains("None of the 25 sessions of Nifty on the phone"), a)
        assertTrue(a.contains("The biggest gap that way was -1.50%"), a)
    }

    @Test fun lockedPhoneLeavesTheAccountOut() {
        val mine = Scenarios.Mine(listOf(ce), notes = listOf("I don't trade Nifty"), lossLimits = mapOf("Zerodha" to 2000.0))
        val a = Scenarios.answer(input(Scenarios.asked("what if nifty falls 2%")!!, mine = mine, locked = true))
        assertTrue(a.contains(Scenarios.LOCKED), a)
        assertFalse(a.contains("Rs"), a)
        assertFalse(a.contains("I don't trade Nifty"), a)
        assertFalse(a.contains("NIFTY26OCT"), a)
    }

    @Test fun positionsRulesLimitsAndAlarms() {
        val mine = Scenarios.Mine(listOf(ce, pe), notes = listOf("I don't trade Nifty", "no trades before 9:30", "remember my wife's birthday"),
            lossLimits = mapOf("Zerodha" to 1000.0, "Paper" to 6000.0), dayPnl = mapOf("Zerodha" to -200.0),
            alarms = listOf(Scenarios.Alarm("NIFTY", false, 23800.0), Scenarios.Alarm("NIFTY", true, 24500.0), Scenarios.Alarm("BANKNIFTY", false, 23800.0)))
        val a = Scenarios.answer(input(Scenarios.asked("What if Nifty opens 1% down tomorrow?")!!, mine = mine))
        // Exposure's own lines: 240 points down.
        assertTrue(a.contains("If Nifty falls 240 points"), a)
        // Zerodha: 75 x (0.5 x -240 + 0.5 x 0.001 x 240^2) = -6,840: past the 1,000 limit (tomorrow: today's P&L not added).
        assertTrue(a.contains("your Zerodha day at about -Rs 6,840, past your daily loss limit of Rs 1,000"), a)
        // Paper (short put, delta -0.4): -75 x (-0.4 x -240 + 28.8) = -9,360: past 6,000 too.
        assertTrue(a.contains("Paper day at about -Rs 9,360"), a)
        assertTrue(a.contains("\"Nifty below 23,800.00\" would go off"), a)
        assertFalse(a.contains("24,500.00\" would"), a)
        assertTrue(a.contains("\"I don't trade Nifty\" (this is Nifty)"), a)
        assertTrue(a.contains("\"no trades before 9:30\" (it holds at the open)"), a)
        assertFalse(a.contains("birthday"), a)
        // Today's move adds today's P&L.
        val small = Scenarios.Mine(listOf(ce), lossLimits = mapOf("Zerodha" to 2000.0), dayPnl = mapOf("Zerodha" to -200.0))
        val b = Scenarios.answer(input(Scenarios.asked("what if nifty rises 20 points")!!, mine = small))
        assertTrue(b.contains("Your Zerodha day would be about +Rs 565, inside your daily loss limit of Rs 2,000"), b)
        assertTrue(b.contains("under my sharp-move bar"), b)
    }

    @Test fun expiryRuleOnlyOnAnExpiry() {
        val mine = Scenarios.Mine(emptyList(), notes = listOf("skip expiry days"))
        val s = Scenarios.asked("what if nifty opens 1% higher tomorrow")!!
        assertTrue(Scenarios.answer(input(s, mine = mine, expiry = true)).contains("a Nifty expiry day"))
        assertFalse(Scenarios.answer(input(s, mine = mine, expiry = false)).contains("skip expiry days"))
    }

    @Test fun vixLevel() {
        val mine = Scenarios.Mine(listOf(ce, pe), alarms = listOf(Scenarios.Alarm("INDIAVIX", true, 18.0)))
        val a = Scenarios.answer(input(Scenarios.asked("what if VIX goes to 20?")!!, mine = mine))
        assertTrue(a.contains("If India VIX goes to 20.00 (+53.85% from 13.00 now)"), a)
        assertTrue(a.contains("1 session where India VIX closed at or above 20.00"), a)
        assertTrue(a.contains("Nifty -2.00%"), a)
        assertTrue(a.contains("not vega"), a)
        assertTrue(a.contains("1 is long and 1 short"), a)
        assertTrue(a.contains("\"India VIX above 18.00\" would go off"), a)
        assertTrue(a.contains("My fear alert would go off"), a)
        // A small VIX move: the alert stays quiet.
        val b = Scenarios.answer(input(Scenarios.asked("what if india vix rises 5%")!!))
        assertTrue(b.contains("My fear alert stays quiet"), b)
        assertTrue(b.contains("3 sessions where India VIX rose 5.00% or more"), b)
    }

    @Test fun levelAgainstPastRanges() {
        val a = Scenarios.answer(input(Scenarios.asked("what if nifty hits 23,500")!!))
        assertTrue(a.contains("1 of the 25 sessions of Nifty on the phone (since 2026-08-31) ranged 500 points or more in the day"), a)
        assertTrue(a.contains("Nifty last traded at 23,500.00 on"), a)
        assertTrue(a.contains("past my sharp-move bar"), a)
        val far = Scenarios.answer(input(Scenarios.asked("what if nifty hits 22000")!!))
        assertTrue(far.contains("hasn't traded at 22,000.00"), far)
    }

    @Test fun noPriceNoSessions() {
        val s = Scenarios.asked("what if nifty falls 1%")!!
        val a = Scenarios.answer(Scenarios.Input(s, emptyList(), today, null, mine = Scenarios.Mine(listOf(ce))))
        assertTrue(a.contains("no whole Nifty sessions"), a)
        assertTrue(a.contains("don't have Nifty's price"), a)
    }

    /** Never advice, never a forecast: every answer is facts, and the decision is Boss's. */
    @Test fun noAdviceWording() {
        val mine = Scenarios.Mine(listOf(ce, pe), notes = listOf("I don't trade Nifty", "skip expiry days"), lossLimits = mapOf("Zerodha" to 1000.0),
            alarms = listOf(Scenarios.Alarm("NIFTY", false, 23800.0)))
        val qs = listOf("What if Nifty opens 1% down tomorrow?", "what if VIX goes to 20?", "what if the market falls 2%", "what if nifty hits 23500",
            "what if india vix jumps 15%", "suppose BankNifty gaps up 300 points", "what if nifty opens 3% lower", "what if vix goes to 9")
        val banned = Regex("(?i)\\b(should|recommend|suggest|advise|consider|buy|sell|hedge|exit now|better to|you must|will|likely|expect|probably|chance|odds|guarantee)\\b")
        for (q in qs) for (locked in listOf(false, true)) {
            val a = Scenarios.answer(input(Scenarios.asked(q)!!, mine = mine, locked = locked, expiry = true))
            assertNull(banned.find(a), "$q -> $a")
            assertTrue(a.contains("Boss"), a)
            assertTrue(a.contains("your call"), a)
            assertTrue(a.contains("not a forecast"), a)
        }
    }
}
