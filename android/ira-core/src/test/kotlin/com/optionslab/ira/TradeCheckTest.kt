package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TradeCheckTest {
    private val calm = TradeCheck.Now(marketOpen = true, tradingDay = true, minute = 11 * 60, liveMode = false, zerodhaLoggedIn = false,
        staticIpOk = null, pricesFresh = true, killSwitch = false, breakerTripped = false, botsStopped = false, dayPnl = 0.0,
        dayLossLimit = 6000.0, vix = 13.0, vixChangePct = 1.0, indexChangePct = 0.3, gapPct = 0.2, burst30Pct = 0.05,
        usual30Pct = 0.08, openingRangeRatio = 1.0, eventsToday = emptyList(), expiryToday = false, armsOn = listOf("Liquidity 15+5"))

    @Test fun aNormalDayIsGo() {
        val v = TradeCheck.check(calm)
        assertEquals(TradeCheck.Level.GO, v.level)
        assertTrue(v.say().startsWith("Conditions are normal") && v.say().contains("Liquidity 15+5 made money in both years"), v.say())
    }

    @Test fun eachBlockerStops() {
        for ((what, now) in listOf(
            "closed" to calm.copy(tradingDay = false), "early" to calm.copy(marketOpen = false, minute = 9 * 60),
            "late" to calm.copy(minute = 15 * 60), "blind" to calm.copy(pricesFresh = false),
            "login" to calm.copy(liveMode = true), "ip" to calm.copy(liveMode = true, zerodhaLoggedIn = true, staticIpOk = false),
            "kill" to calm.copy(killSwitch = true), "breaker" to calm.copy(breakerTripped = true), "loss" to calm.copy(dayPnl = -5000.0),
        )) assertEquals(TradeCheck.Level.STOP, TradeCheck.check(now).level, what)
    }

    @Test fun eachRiskIsCareful() {
        for ((what, now) in listOf(
            "event" to calm.copy(eventsToday = listOf("US Fed decision overnight (FOMC)")), "expiry" to calm.copy(expiryToday = true),
            "vix" to calm.copy(vix = 24.0), "vixjump" to calm.copy(vixChangePct = 9.0), "gap" to calm.copy(gapPct = -1.4, minute = 9 * 60 + 25),
            "chase" to calm.copy(indexChangePct = 1.8), "burst" to calm.copy(burst30Pct = 0.6), "halfloss" to calm.copy(dayPnl = -3200.0),
            "orb" to calm.copy(armsOn = listOf("ORB")), "first5" to calm.copy(minute = 9 * 60 + 16), "lasthour" to calm.copy(minute = 14 * 60 + 40),
        )) assertEquals(TradeCheck.Level.CAREFUL, TradeCheck.check(now).level, what)
        val v = TradeCheck.check(calm.copy(armsOn = listOf("ORB", "Liquidity 15+5"), openingRangeRatio = 0.5, eventsToday = listOf("RBI policy")))
        val s = v.say()
        assertTrue(s.startsWith("Careful today. Event today: RBI policy.") && s.contains("ORB lost in both years") && s.contains("opening range is narrow"), s)
        assertTrue(s.contains("Liquidity 15+5 made money"), s)
    }

    @Test fun askedInWords() {
        for (t in listOf("should I trade now", "Jarvis, can I trade today?", "is it safe to trade", "good time to trade?", "should i stay out today"))
            assertEquals(setOf(Topic.TRADE_CHECK), Ask.parse(t).topics, t)
        assertTrue(Topic.TRADE_CHECK !in Ask.parse("should I buy nifty").topics)
        for (t in listOf("is the market bullish or bearish", "how is the market today", "is market good today"))
            assertEquals(setOf(Topic.TRADE_CHECK), Ask.parse(t).topics, t)
    }

    @Test fun theMarketReadDescribesNotPredicts() {
        val up = Brain.read(History(Market.NIFTY, Fixtures.indexDays(25, drift = 0.6, seed = 2)))!!
        val down = Brain.read(History(Market.BANKNIFTY, Fixtures.indexDays(25, drift = -0.6, seed = 2)))!!
        assertTrue(TradeCheck.read(up).startsWith("Nifty is bullish now"), TradeCheck.read(up))
        assertTrue(TradeCheck.read(down).startsWith("BankNifty is bearish now"), TradeCheck.read(down))
        val s = TradeCheck.check(calm.copy(reads = listOf(TradeCheck.read(up)))).say()
        assertTrue(s.startsWith("Nifty is bullish now") && s.endsWith("not a forecast."), s)
    }
}
