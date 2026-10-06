package com.optionslab.ira

import com.optionslab.engine.options.OptionMath
import com.optionslab.engine.options.OptionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reasoning, round 32: each open option's estimated price at a move, against its own stop and target. */
class MovePremiumTest {
    private val ce = Exposure.Leg("Paper", "NIFTY24OCT25000CE", 75, 100.0, 120.0, "NIFTY", 0.5, 0.001, stop = 80.0, target = 150.0)
    private val shortPe = Exposure.Leg("Zerodha", "NIFTY24OCT24800PE", -75, 90.0, 60.0, "NIFTY", -0.4, 0.001, stop = 90.0, target = 30.0)
    private val fall = Exposure.Shock(Market.NIFTY, 100.0, null, -1)

    @Test fun priceFromDeltaAndGamma() {
        // 120 + 0.5*(-100) + 0.5*0.001*10000 = 75; up: 175.
        assertEquals(75.0, Exposure.price(ce, -100.0), 1e-9)
        assertEquals(175.0, Exposure.price(ce, 100.0), 1e-9)
        // Unchanged rupees from before: 75 * (75 - 120).
        assertEquals(-3375.0, Exposure.change(ce, -100.0), 1e-9)
        // A put's estimate is held at the bottom of the curve: delta -0.2, gamma 0.002 turns at +100 (30 - 20 + 10 = 20),
        // never climbing back to 30 at +200; and never below zero.
        val pe = Exposure.Leg("Paper", "NIFTY24OCT24000PE", 75, 30.0, 30.0, "NIFTY", -0.2, 0.002)
        assertEquals(20.0, Exposure.price(pe, 200.0), 1e-9)
        val cheap = Exposure.Leg("Paper", "NIFTY24OCT26000CE", 75, 5.0, 5.0, "NIFTY", 0.1, 0.0)
        assertEquals(0.0, Exposure.price(cheap, -100.0), 1e-9)
        // The index itself (no CE/PE) moves point for point and gets no price line.
        assertFalse(Exposure.option(Exposure.Leg("Paper", "NIFTY", 75, 24000.0, 24000.0, "NIFTY", 1.0, 0.0)))
        assertTrue(Exposure.option(ce))
    }

    @Test fun repricedWithTheEnginesOptionMathWhereItsIvIsKnown() {
        val f = 24000.0; val k = 24000.0; val t = 7.0 / 365; val iv = 0.13
        val now = OptionMath.price(OptionType.CE, f, k, t, 0.0, iv)
        val g = OptionMath.greeks(OptionType.CE, f, k, t, 0.0, iv)
        val leg = Exposure.Leg("Paper", "NIFTY24OCT24000CE", 75, now, now, "NIFTY", g.delta, g.gamma,
            model = Exposure.Model(true, k, t, iv, f))
        val down = Exposure.price(leg, -300.0)
        assertEquals(OptionMath.price(OptionType.CE, f - 300, k, t, 0.0, iv), down, 1e-9)
        // Close to delta-gamma's figure for a small move, and the full curve for a big one stays above zero.
        val dg = leg.copy(model = null)
        assertEquals(Exposure.price(dg, -50.0), Exposure.price(leg, -50.0), 1.0)
        assertTrue(Exposure.price(leg, -1500.0) > 0)
    }

    @Test fun stopAndTargetAtTheMove() {
        val l = Exposure.move(fall, listOf(ce, shortPe), 25000.0)
        // Fall 100: the CE to about 75, under its 80 stop; the short PE to about 105, over its 90 stop.
        assertTrue(l.any { it == "Paper NIFTY24OCT25000CE (75, now 120.00): its 80.00 stop would be hit if Nifty falls (about 75.00 by this estimate) - " +
            "-Rs 3,000 from here if it filled at the stop; its 150.00 target would not be reached at that move (about 75.00)." }, l.joinToString("\n"))
        assertTrue(l.any { it.startsWith("Zerodha NIFTY24OCT24800PE (-75, now 60.00): its 90.00 stop would be hit if Nifty falls (about 105.00") &&
            it.contains("-Rs 2,250 from here") }, l.joinToString("\n"))
        assertTrue(l.any { it.startsWith("Paper NIFTY24OCT25000CE (75): about -Rs 3,375, delta 0.50, its price about 120.00 now, about 75.00 then") })
        assertTrue(l.any { it.startsWith("A stop is a trigger, not a price") })
        val last = l.last()
        assertTrue(last.startsWith("A rough guide, Boss") && last.contains("before charges") && last.contains("not advice"))
        // A rise reaches both targets; either way says both.
        val up = Exposure.move(Exposure.Shock(Market.NIFTY, 100.0, null, 1), listOf(ce, shortPe), 25000.0)
        assertTrue(up.any { it.contains("its 150.00 target would be reached if Nifty rises (about 175.00) - +Rs 2,250 from here") }, up.joinToString("\n"))
        assertTrue(up.any { it.contains("its 30.00 target would be reached if Nifty rises (about 25.00) - +Rs 2,250 from here") }, up.joinToString("\n"))
        val both = Exposure.levels(ce, 100.0, listOf(1, -1), Market.NIFTY)!!
        assertTrue(both.contains("target would be reached if Nifty rises") && both.contains("stop would be hit if Nifty falls"), both)
        // A stop already crossed is said so; a leg with neither, or the index, says nothing.
        assertTrue(Exposure.levels(ce.copy(stop = 125.0), 100.0, listOf(-1), Market.NIFTY)!!.contains("already at or past its 125.00 stop"))
        assertNull(Exposure.levels(ce.copy(stop = null, target = null), 100.0, listOf(-1), Market.NIFTY))
        assertNull(Exposure.levels(Exposure.Leg("Paper", "NIFTY", 75, 24000.0, 24000.0, "NIFTY", 1.0, 0.0, stop = 23900.0), 100.0, listOf(-1), Market.NIFTY))
        // A small move touches nothing.
        val small = Exposure.levels(ce, 10.0, listOf(-1), Market.NIFTY)!!
        assertEquals("Paper NIFTY24OCT25000CE (75, now 120.00): its 80.00 stop and 150.00 target would not be reached at that move (about 115.05).", small)
        // No stop or target anywhere: the answer is as before, with no stop note.
        assertFalse(Exposure.move(fall, listOf(ce.copy(stop = null, target = null)), 25000.0).any { it.startsWith("A stop is a trigger") })
    }

    @Test fun hinglishAsksTheMoveOnHisBook() {
        for ((q, sign) in listOf("agar nifty 50 point gire to mera kya hoga" to -1, "agar nifty 100 point gira to mere positions ka kya hoga" to -1,
                "agar banknifty 1% chadhe to meri positions ka kya hoga" to 1, "nifty 200 points gir jaye to mera p&l kya hoga" to -1)) {
            val s = assertNotNull(Exposure.moveAsked(q), q)
            assertEquals(sign, s.sign, q)
            val p = Ask.parse(q)
            assertEquals(setOf(Topic.ACCOUNT), p.topics, q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertEquals(setOf(Section.MOVE), AppAnswers.sections(q), q)
            assertNull(Scenarios.asked(q), q)
        }
        assertEquals(50.0, Exposure.moveAsked("agar nifty 50 point gire to mera kya hoga")!!.points)
        assertEquals(Market.BANKNIFTY, Exposure.moveAsked("agar banknifty 1% chadhe to meri positions ka kya hoga")!!.market)
        // An order or advice in Hindi, a forecast, the market's own what-if: never this.
        for (q in listOf("agar nifty 100 point gira to mera call bech do", "agar nifty 1% gira to kya buy karu", "agar nifty 100 point gire to meri puts nikalo",
                "agar nifty 1% gir jaye to kya hoga", "kal nifty ka kya hoga",
                // Gold is IraGoldAlgo's: never taken as a Nifty move on his book.
                "agar gold 100 point gire to mera kya hoga", "what happens to my p&l if gold falls 100 points"))
            assertNull(Exposure.moveAsked(q), q)
    }

    @Test fun anIfWithAnOrderStaysWhatItWas() {
        // "If nifty falls 100 points sell" names nobody's positions: not the what-if, and read exactly as before.
        for (q in listOf("if nifty falls 100 points sell", "agar nifty 100 point gire to sell karo")) {
            assertNull(Exposure.moveAsked(q), q)
            assertNull(Scenarios.asked(q), q)
            assertFalse(Section.MOVE in AppAnswers.sections(q), q)
        }
        val p = Ask.parse("if nifty falls 100 points sell")
        assertNull(p.order); assertNull(p.command)
        assertFalse(Topic.ACCOUNT in p.topics)
    }
}
