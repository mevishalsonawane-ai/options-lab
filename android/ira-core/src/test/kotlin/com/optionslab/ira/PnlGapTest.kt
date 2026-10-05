package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PnlGapTest {
    @Test fun asked() {
        listOf("why is my p&l different from what i expected", "why is my pnl lower than i expected", "why is my p&l not what i expected",
            "my p&l doesn't add up", "break down my p&l", "today's p&l breakdown", "how much of my p&l is realised", "realised vs unrealised",
            "where did my p&l come from", "mera p&l expected se alag kyun hai", "aaj ka pnl itna kam kyun hai", "mera p&l ka breakdown do",
            "jarvis why is today's p&l worse than i thought").forEach {
            assertTrue(PnlGap.asked(it), it)
            assertNull(Ask.parse(it).order, it); assertNull(Ask.parse(it).command, it)
        }
        listOf("what's my p&l", "how much did i pay in charges", "why is my p&l different from last week", "break down my zerodha p&l",
            "why did orb lose today", "what's the trend", "close my position").forEach { assertFalse(PnlGap.asked(it), it) }
    }

    @Test fun slippageOnlyWhereThereWasAnIntendedLevel() {
        assertEquals(150.0, PnlGap.slippage(PnlGap.Fill("X", false, "SL-M", 100.0, 98.0, 75)))
        assertEquals(-75.0, PnlGap.slippage(PnlGap.Fill("X", true, "LIMIT", 100.0, 99.0, 75)))
        assertNull(PnlGap.slippage(PnlGap.Fill("X", true, "MARKET", null, 99.0, 75)))
        assertNull(PnlGap.slippage(PnlGap.Fill("X", true, "LIMIT", 0.0, 99.0, 75)))
    }

    @Test fun takesTheDayApartAndNamesTheBiggest() {
        val d = PnlGap.Day(
            legs = listOf(PnlGap.Leg("NIFTY24500PE", 1200.0, 0.0), PnlGap.Leg("NIFTY24600CE", 0.0, -3000.0)),
            charges = 180.0, tradeLegs = 3,
            fills = listOf(PnlGap.Fill("NIFTY24500PE", false, "SL-M", 100.0, 98.0, 75), PnlGap.Fill("NIFTY24600CE", true, "MARKET", null, 120.0, 75)))
        val a = PnlGap.answer(d)
        assertTrue(a.startsWith("Boss, today's paper P&L is -Rs 1,800 before charges, -Rs 1,980 after charges"), a)
        assertTrue(a.contains("realised) Rs 1,200"), a)
        assertTrue(a.contains("unrealised) -Rs 3,000"), a)
        assertTrue(a.contains("-Rs 180 on 3 trade legs"), a)
        assertTrue(a.contains("Rs 150 worse on 1 order"), a)
        assertTrue(a.contains("1 market order had no intended level kept"), a)
        assertTrue(a.contains("The biggest part is the open (unrealised) part"), a)
        assertTrue(a.contains("NIFTY24600CE"), a)
        assertFalse(a.contains("should"), a)
    }

    @Test fun nothingToday() {
        assertTrue(PnlGap.answer(PnlGap.Day(emptyList(), 0.0, 0, emptyList())).contains("Rs 0"))
    }
}
