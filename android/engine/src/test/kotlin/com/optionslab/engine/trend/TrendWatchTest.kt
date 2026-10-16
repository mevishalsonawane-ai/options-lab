package com.optionslab.engine.trend

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.trend.TrendTurn.Rule
import com.optionslab.engine.trend.TrendWatch.Act
import com.optionslab.engine.trend.TrendWatch.Source
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrendWatchTest {
    private val day = LocalDate.of(2026, 10, 15)

    private fun session(d: LocalDate, closes: DoubleArray, spread: Double = 1.0): List<Bar> = closes.indices.map { r ->
        val o = if (r == 0) closes[0] else closes[r - 1]
        Bar(d.atTime(9, 15).plusMinutes(r.toLong()), o, maxOf(o, closes[r]) + spread, minOf(o, closes[r]) - spread, closes[r])
    }

    private fun held(s: Source, bias: Int = 1, live: Boolean = false, tested: Boolean = true, fill: LocalDateTime = day.atTime(10, 0),
                     und: String = "NIFTY", mcx: Boolean = false) =
        TrendWatch.Held(s, "NIFTY26OCT25000CE", und, bias, fill, live, 12.5, mcx, tested)

    @Test
    fun everySourceLabelIsClassified() {
        val cases = mapOf(
            null to Source.MANUAL, "" to Source.MANUAL, "Manual" to Source.MANUAL, "Manual · Option chart" to Source.MANUAL,
            "Outside IraAlgo (Kite)" to Source.MANUAL, "Manual · Jarvis" to Source.JARVIS,
            "Hero ₹1,000 · entry" to Source.HERO_TICKET, "Hero · entry" to Source.OTHER_ARM,
            "Liquidity 15+5 · entry" to Source.LIQUIDITY, "Liquidity 5m FINNIFTY · entry" to Source.LIQUIDITY,
            "R51 Donchian 20 · entry" to Source.DONCHIAN, "R52 Open outside value · entry" to Source.OOV,
            "Solo 2.0 · entry" to Source.SOLO2, "Jarvis solo · entry" to Source.SOLO, "Jarvis news · entry" to Source.OTHER_ARM,
            "Signal tester (paper) · entry" to Source.TELEGRAM, "NG evening breakout" to Source.MCX, "SILVERM morning call · entry" to Source.MCX,
            "MCX 12-month trend · entry" to Source.MCX, "US-night silver (not proven, futures) · disaster stop" to Source.MCX,
            "Protection · stop" to Source.EXIT_RULE, "Expiry square-off" to Source.EXIT_RULE, "Missed-lock sweep" to Source.EXIT_RULE,
            "Auto square-off 15:15" to Source.EXIT_RULE, "GTT" to Source.EXIT_RULE, "GTT trigger" to Source.EXIT_RULE,
            "ORB · entry" to Source.OTHER_ARM, "Pine" to Source.OTHER_ARM, "Strategy order" to Source.OTHER_ARM,
            "my basket · leg 1 buy" to Source.OTHER_ARM, "x1 (forge, paper) · entry" to Source.OTHER_ARM,
        )
        for ((label, want) in cases) assertEquals(want, TrendWatch.sourceOf(label), label.toString())
        assertEquals(Source.SOLO2, Source.of("solo2")); assertNull(Source.of("nope")); assertNull(Source.of(null))
        assertFalse(Source.EXIT_RULE in Source.SWITCHABLE)
        assertEquals(Source.entries.size - 1, Source.SWITCHABLE.size)
    }

    @Test
    fun biasOfEveryHolding() {
        assertEquals(1, TrendWatch.bias("CE", 75)); assertEquals(-1, TrendWatch.bias("ce", -75))
        assertEquals(-1, TrendWatch.bias("PE", 75)); assertEquals(1, TrendWatch.bias("PE", -75))
        assertEquals(1, TrendWatch.bias(null, 1)); assertEquals(-1, TrendWatch.bias("FUT", -1))
    }

    @Test
    fun onlyTheHeroTicketMayActOnPaperAndLiveNeverActs() {
        assertEquals(Rule.T3, TrendWatch.ruleFor(Source.HERO_TICKET))
        for (s in Source.entries.filter { it != Source.HERO_TICKET }) {
            assertFalse(TrendWatch.canAct(s), s.name)
            assertEquals(TrendWatch.SHADOW_RULE, TrendWatch.ruleFor(s), s.name)
        }
        assertNull(TrendWatch.POOLED_PASSED)
        assertEquals(Rule.T2, TrendWatch.SHADOW_RULE)
        val sw = TrendWatch.Switches()
        assertFalse(sw.live); assertFalse(sw.alerts); assertTrue(sw.on)
        assertTrue(sw.acts(Source.HERO_TICKET))
        for (s in Source.entries.filter { it != Source.HERO_TICKET }) assertFalse(sw.acts(s), s.name)
        assertEquals(Act.SELL, TrendWatch.act(held(Source.HERO_TICKET), sw))
        assertEquals(Act.NOTE, TrendWatch.act(held(Source.HERO_TICKET, tested = false), sw))
        assertEquals(Act.NOTE, TrendWatch.act(held(Source.HERO_TICKET), sw.copy(act = emptyMap())))
        assertEquals(Act.NOTE, TrendWatch.act(held(Source.LIQUIDITY), sw))
        // A switch turned on for a source whose rule failed still only writes a note.
        assertEquals(Act.NOTE, TrendWatch.act(held(Source.MANUAL), sw.copy(act = mapOf(Source.MANUAL to true))))
        // Live: nothing unless its switch is on, and then a note - never a sale.
        assertEquals(Act.NONE, TrendWatch.act(held(Source.HERO_TICKET, live = true), sw))
        assertEquals(Act.NOTE, TrendWatch.act(held(Source.MANUAL, live = true), sw.copy(live = true)))
        assertEquals(Act.NOTE, TrendWatch.act(held(Source.HERO_TICKET, live = true), sw.copy(live = true)))
        assertEquals(Act.NONE, TrendWatch.act(held(Source.EXIT_RULE), sw))
        assertEquals(Act.NONE, TrendWatch.act(held(Source.HERO_TICKET), sw.copy(on = false)))
    }

    @Test
    fun heroTestedSettingOnly() {
        assertTrue(TrendWatch.heroTested("2x", "trail"))
        assertTrue(TrendWatch.heroTested(null, null))           // the defaults: after 2x, trail only
        assertFalse(TrendWatch.heroTested("fill", "trail"))
        assertFalse(TrendWatch.heroTested("2x", "3x"))
    }

    @Test
    fun judgeTurnedHoldingWarmingStaleAndEmpty() {
        // A fall below the day's average from 11:00: T3 sells a call; T2 (the shadow rule) needs 11 bars of history.
        val c = DoubleArray(375) { if (it < 100) 100.0 + it * 0.01 else 95.0 }
        val ms = session(day, c, 0.1)
        val hero = held(Source.HERO_TICKET, fill = day.atTime(10, 50))
        val v = TrendWatch.judge(hero, ms, day.atTime(15, 0))
        assertEquals(Rule.T3, v.rule); assertEquals(day.atTime(11, 5), v.at); assertEquals("turned at 11:05", v.state)
        // Before the turn's bar completed: not yet.
        assertNull(TrendWatch.judge(hero, ms.filter { it.start.isBefore(day.atTime(11, 3)) }, day.atTime(11, 3)).at)
        // The put leg of the same ticket: the trend is with it.
        assertEquals("trend holding", TrendWatch.judge(hero.copy(bias = -1), ms, day.atTime(15, 0)).state)
        // Warming up: Supertrend needs 11 fifteen-minute bars.
        val early = ms.filter { it.start.isBefore(day.atTime(10, 0)) }
        assertTrue(TrendWatch.judge(held(Source.LIQUIDITY), early, day.atTime(10, 0)).state.startsWith("warming up"))
        // Stale: the last minute 10 minutes old.
        assertTrue(TrendWatch.judge(hero, early, day.atTime(10, 10)).state.contains("stale"))
        assertTrue(TrendWatch.stale(emptyList(), day.atTime(10, 0)))
        assertFalse(TrendWatch.stale(early, day.atTime(10, 3)))
        assertEquals("no NIFTY minutes yet", TrendWatch.judge(hero, emptyList(), day.atTime(10, 0)).state)
        // MCX: its own grid from 09:00.
        val mcxMs = (0 until 200).map { r -> Bar(day.atTime(9, 0).plusMinutes(r.toLong()), 100.0, 101.0, 99.0, if (r < 150) 100.0 else 90.0) }
        val mv = TrendWatch.judge(held(Source.MCX, mcx = true, fill = day.atTime(9, 30)), mcxMs, day.atTime(12, 20))
        assertEquals(Rule.T2, mv.rule)
        assertNotNull(mv.state)
    }

    @Test
    fun pendingOrdersAreOnlyNoted() {
        // Up for two sessions, then down: the Supertrend flips against a call.
        val up = DoubleArray(375) { 100.0 + it * 0.2 }
        val dn = DoubleArray(375) { 175.0 - it * 0.3 }
        val ms = session(day.minusDays(1), up) + session(day, dn)
        val at = TrendWatch.pendingTurned(1, day.atTime(9, 15), ms, day.atTime(15, 29))
        assertNotNull(at)
        assertNull(TrendWatch.pendingTurned(-1, day.atTime(9, 15), ms, day.atTime(15, 29)))
        assertNull(TrendWatch.pendingTurned(1, day.atTime(9, 15), emptyList(), day.atTime(15, 29)))
        assertNull(TrendWatch.pendingTurned(1, day.atTime(9, 15), ms, day.atTime(16, 0).plusDays(1)))
        assertNull(TrendWatch.pendingTurned(1, day.atTime(9, 15), (0 until 30).map { Bar(day.atTime(9, it), 1.0, 1.0, 1.0, 1.0) },
            day.atTime(9, 31), mcx = true))
        val line = TrendWatch.pendingLine(Source.MANUAL, "NIFTY26OCT25000CE", at!!)
        assertTrue(line.contains("would cancel") && line.contains("nothing cancelled"))
    }

    @Test
    fun linesSayWhatHappenedAndNeverAccountDetailsBeyondThePremium() {
        val v = TrendWatch.Verdict(Rule.T3, day.atTime(11, 5), "turned at 11:05")
        assertEquals("trend turned against Hero ₹1,000 ticket NIFTY26OCT25000CE at 11:05: selling on paper at about 12.50 (T3, passed research R63)",
            TrendWatch.line(held(Source.HERO_TICKET), v, Act.SELL))
        assertTrue(TrendWatch.line(held(Source.MANUAL, live = true), v, Act.NOTE).contains("live: alert only, never an order"))
        assertTrue(TrendWatch.line(held(Source.HERO_TICKET, tested = false), v, Act.NOTE).contains("did not test"))
        assertTrue(TrendWatch.line(held(Source.LIQUIDITY, und = "BANKNIFTY"), v, Act.NOTE).endsWith("[BANKNIFTY: kept apart for 26 Oct]"))
        assertTrue(TrendWatch.line(held(Source.DONCHIAN).copy(premium = null), v.copy(at = null), Act.NOTE).contains("at ?: would sell at the market"))
        assertTrue(TrendWatch.line(held(Source.DONCHIAN), v, Act.NOTE).contains("shadow: failed research"))
        assertEquals("live|NIFTY26OCT25000CE|2026-10-15T10:00", held(Source.MANUAL, live = true).key)
        assertEquals("paper|NIFTY26OCT25000CE|2026-10-15T10:00", held(Source.MANUAL).key)
    }

    @Test
    fun statusLine() {
        val sw = TrendWatch.Switches()
        assertEquals("Off: no position is watched", TrendWatch.status(sw.copy(on = false), 3, 1, 0))
        assertEquals("On · nothing open to watch (paper; live off)", TrendWatch.status(sw, 0, 0, 0))
        assertEquals("On · nothing open to watch (paper and live)", TrendWatch.status(sw.copy(live = true), 0, 0, 0))
        assertEquals("On · 4 watched, 2 turned today, 1 Hero leg sold on paper · live off", TrendWatch.status(sw, 4, 2, 1))
        assertEquals("On · 4 watched, 2 turned today, 2 Hero legs sold on paper", TrendWatch.status(sw.copy(live = true), 4, 2, 2))
        assertEquals("On · 4 watched, 0 turned today · live off", TrendWatch.status(sw, 4, 0, 0))
        assertTrue(TrendWatch.RECORD.contains("+₹14,730"))
    }
}
