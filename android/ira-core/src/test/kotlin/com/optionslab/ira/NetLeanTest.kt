package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetLeanTest {
    private fun leg(where: String, sym: String, qty: Int, u: String?, delta: Double?, gamma: Double? = 0.0) =
        Exposure.Leg(where, sym, qty, 100.0, 110.0, u, delta, gamma)

    @Test fun asksAreRead() {
        for (s in listOf("am I net long or short?", "am i long or short right now", "which way am I leaning?", "what's my net delta on BankNifty?",
            "do my positions cancel each other out?", "do my bots contradict each other right now?", "are my bots on opposite sides right now",
            "is my book long or short", "main long hoon ya short", "mera net position kis taraf hai", "which side am i on", "are my positions offsetting"))
            assertTrue(NetLean.asked(s), s)
        for (s in listOf("what happens to my P&L if Nifty falls 1%", "should I go long or short", "are my bots fighting each other",
            "did my bots take opposite sides", "any contradictions between my bots", "what is net delta", "should i hedge my positions",
            "is nifty long or short term bullish", "buy nifty", "what if nifty falls 1%", "how are my bots doing"))
            assertFalse(NetLean.asked(s), s)
    }

    @Test fun theBookLeansAndOwnersOffset() {
        val legs = listOf(
            leg("Paper", "BANKNIFTY07OCT2654400CE", 35, "BANKNIFTY", 0.5),     // ORB: +17.5 a point
            leg("Paper", "BANKNIFTY07OCT2654000PE", 35, "BANKNIFTY", -0.4),    // Range Fade: -14 a point
            leg("Zerodha", "NIFTY07OCT2624500PE", 75, "NIFTY", -0.5),          // Boss: -37.5 a point
            leg("Zerodha", "NIFTY07OCT2625000CE", 75, "NIFTY", null),          // no delta
        )
        val arms = listOf(NetLean.ArmLeg("orb", "BANKNIFTY07OCT2654400CE", 35, false), NetLean.ArmLeg("range_fade", "BANKNIFTY07OCT2654000PE", 35, false))
        val i = NetLean.Input(legs, arms, spots = mapOf("NIFTY" to 24_600.0, "BANKNIFTY" to 54_000.0))
        val ls = NetLean.leans(i)
        assertEquals(listOf("NIFTY", "BANKNIFTY"), ls.map { it.underlying })
        assertEquals(-37.5, ls[0].net, 1e-9)
        assertEquals(3.5, ls[1].net, 1e-9)
        assertEquals(31.5, ls[1].gross, 1e-9)
        val a = NetLean.answer(i)
        assertTrue("Nifty: net short" in a, a)
        assertTrue("BankNifty: about flat" in a, a)
        assertTrue("your bots are on opposite sides of BankNifty right now" in a, a)
        assertTrue("ORB leans long while Range Fade leans short" in a, a)
        assertTrue("If Nifty moved 1% from 24,600" in a, a)
        assertTrue("not what will happen" in a, a)
        assertTrue("Left out - no delta known: Zerodha NIFTY07OCT2625000CE" in a, a)
        assertTrue(a.contains("Boss"))
        assertFalse(a.contains("should"))
    }

    @Test fun aSymbolSharedByAnArmAndBossIsSplit() {
        val legs = listOf(leg("Paper", "BANKNIFTY07OCT2654400CE", 70, "BANKNIFTY", 0.5))
        val arms = listOf(NetLean.ArmLeg("orb", "BANKNIFTY07OCT2654400CE", 35, false))
        val owned = NetLean.owned(NetLean.Input(legs, arms))
        assertEquals(listOf("ORB" to 35, "your own Paper trades" to 35), owned.map { it.first to it.third.qty })
    }

    @Test fun oneIndexAskedAndZerodhaUnread() {
        val legs = listOf(leg("Paper", "NIFTY07OCT2624500CE", 75, "NIFTY", 0.5), leg("Paper", "BANKNIFTY07OCT2654400CE", 35, "BANKNIFTY", 0.5))
        val a = NetLean.answer(NetLean.Input(legs, market = Market.BANKNIFTY, zerodha = SinceMorning.Zerodha.FAILED))
        assertTrue("BankNifty: net long" in a, a)
        assertFalse(Regex("\\bNifty: net").containsMatchIn(a), a)
        assertTrue("Zerodha didn't answer" in a, a)
        assertEquals("You hold no open positions right now, Boss, so the book leans neither way.", NetLean.answer(NetLean.Input(emptyList())))
    }
}
