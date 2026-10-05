package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExposureTest {
    private val ce = Exposure.Leg("Paper", "NIFTY24OCT25000CE", 75, 100.0, 120.0, "NIFTY", 0.5, 0.001)
    private val pe = Exposure.Leg("Paper", "NIFTY24OCT24800PE", -75, 90.0, 60.0, "NIFTY", -0.4, 0.001)
    private val bank = Exposure.Leg("Zerodha", "BANKNIFTY24OCT52000CE", 30, 300.0, 250.0, "BANKNIFTY", 0.5, 0.0005)
    private val odd = Exposure.Leg("Zerodha", "RELIANCE", 10, 2900.0, 2950.0)

    @Test fun moveAskedReadsTheMove() {
        val s = Exposure.moveAsked("What happens to my P&L if Nifty moves 100 points?")!!
        assertEquals(Market.NIFTY, s.market); assertEquals(100.0, s.points); assertEquals(0, s.sign)
        val d = Exposure.moveAsked("Jarvis, if BankNifty falls 1.5% what do I lose")!!
        assertEquals(Market.BANKNIFTY, d.market); assertEquals(1.5, d.pct); assertNull(d.points); assertEquals(-1, d.sign)
        assertEquals(1, Exposure.moveAsked("suppose nifty goes up 200 points, how do my positions do")!!.sign)
        assertNull(Exposure.moveAsked("how much did nifty move in the last hour"))        // the market's, not Boss's
        assertNull(Exposure.moveAsked("if nifty falls 100 points"))                       // nobody's positions named
        assertNull(Exposure.moveAsked("what is my p&l today"))
    }

    @Test fun moveIsTheRoughDeltaSum() {
        val s = Exposure.Shock(Market.NIFTY, 100.0, null, 0)
        // CE: 75 * (0.5*100 + 0.5*0.001*10000) = 75 * 55 = 4125; PE: -75 * (-0.4*100 + 5) = 2625. Up +6,750.
        // Down: CE 75 * (-50 + 5) = -3375; PE -75 * (40 + 5) = -3375. Down -6,750.
        val l = Exposure.move(s, listOf(ce, pe, bank, odd), 25000.0)
        assertEquals("If Nifty rises 100 points: about +Rs 6,750 on your 2 Nifty positions.", l[0])
        assertEquals("If Nifty falls 100 points: about -Rs 6,750 on your 2 Nifty positions.", l[1])
        assertTrue(l.any { it.startsWith("Not counted: Zerodha BANKNIFTY24OCT52000CE, Zerodha RELIANCE") })
        assertTrue(l.last().startsWith("A rough guide, Boss"))
        val pct = Exposure.move(Exposure.Shock(Market.NIFTY, null, 0.4, -1), listOf(ce), 25000.0)
        assertEquals("If Nifty falls 0.4% (about 100 points): about -Rs 3,375 on your 1 Nifty position.", pct[0])
        assertFalse(pct.any { it.startsWith("If Nifty rises") })
        assertTrue(Exposure.move(Exposure.Shock(Market.NIFTY, null, 1.0, 0), listOf(ce), null)[0].contains("in points"))
        assertEquals("None of your open positions are on FinNifty, Boss.", Exposure.move(Exposure.Shock(Market.FINNIFTY, 50.0, null, 0), listOf(ce), null)[0])
        assertTrue(Exposure.move(s, emptyList(), null)[0].startsWith("You have no open positions"))
    }

    @Test fun rankWorstFirst() {
        assertTrue(Exposure.rankAsked("Which of my positions is losing most?"))
        assertTrue(Exposure.rankAsked("Jarvis, what's my worst position"))
        assertTrue(Exposure.rankAsked("rank my open positions"))
        assertFalse(Exposure.rankAsked("which positions are losing most in the market"))   // not Boss's
        assertFalse(Exposure.rankAsked("what was my worst trade this month"))               // the record, not open positions
        val l = Exposure.rank(listOf(ce, bank, pe))
        assertEquals("Losing most: Zerodha BANKNIFTY24OCT52000CE, 30 at 300.00, now 250.00: -Rs 1,500.00 (-16.7% on what it cost).", l[0])
        assertTrue(l[1].startsWith("Doing best: Paper NIFTY24OCT24800PE, -75 at 90.00, now 60.00: +Rs 2,250.00"))
        assertEquals("Worst first: Zerodha BANKNIFTY24OCT52000CE -Rs 1,500.00; Paper NIFTY24OCT25000CE +Rs 1,500.00; Paper NIFTY24OCT24800PE +Rs 2,250.00.", l[2])
        assertEquals("Open P&L on all 3 together: +Rs 2,250.00 (before charges).", l[3])
        assertTrue(Exposure.rank(listOf(ce))[0].startsWith("None of your open positions is losing"))
    }

    @Test fun routedToTheAccountOnly() {
        for (q in listOf("What happens to my P&L if Nifty moves 100 points?", "If BankNifty falls 1% what do I lose?",
                "Which of my positions is losing most?", "my worst position")) {
            val p = Ask.parse(q)
            assertEquals(setOf(Topic.ACCOUNT), p.topics, q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertNull(Intents.quick(q), q)
        }
        assertEquals(setOf(Section.MOVE), AppAnswers.sections("What happens to my P&L if Nifty moves 100 points?"))
        assertEquals(setOf(Section.RANK), AppAnswers.sections("Which of my positions is losing most?"))
        val a = AppAnswers.answer(Ask.parse("my worst position"), AppView("Paper", mapOf(Section.RANK to Exposure.rank(listOf(bank)))))
        assertTrue(a.text.startsWith("Losing most: Zerodha BANKNIFTY"))
        // Unchanged: the stops on positions and the plain positions list.
        assertTrue(Section.PROTECTIONS in AppAnswers.sections("what are my stop losses on open positions"))
        assertEquals(setOf(Section.POSITIONS), AppAnswers.sections("show my positions"))
    }
}
