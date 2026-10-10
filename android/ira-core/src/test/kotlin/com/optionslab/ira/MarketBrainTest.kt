package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared market brain ([MarketBrain]): regime, event windows (DST), the global entry pause. */
class MarketBrainTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun ms(day: LocalDate, h: Int, m: Int) = day.atTime(h, m).atZone(ist).toInstant().toEpochMilli()
    private fun hhmm(ms: Long) = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ist).let { "%02d:%02d".format(it.hour, it.minute) }

    /** 31 closes moving [stepBp] each minute, alternating up and down (no trend) or all one way ([trend]). */
    private fun closes(stepBp: Double, trend: Boolean = false): List<Double> {
        var p = 50_000.0
        return List(31) { i -> if (i > 0) p *= exp((if (trend || i % 2 == 0) 1 else -1) * stepBp / 1e4); p }
    }

    @Test fun busyQuietAndNormalByR11sTwoSdRule() {
        val normal = listOf(3.0)
        val busy = MarketBrain.regime("BANKNIFTY", closes(7.0), normal)       // 2.3x normal: z ≈ +2.4
        assertEquals(MarketBrain.State.BUSY, busy.state)
        assertTrue(busy.rvZ!! >= MarketBrain.BUSY_Z)
        val quiet = MarketBrain.regime("BANKNIFTY", closes(1.0), normal)      // a third of normal
        assertEquals(MarketBrain.State.QUIET, quiet.state)
        val usual = MarketBrain.regime("BANKNIFTY", closes(3.0), normal)
        assertEquals(MarketBrain.State.NORMAL, usual.state)
        assertEquals(MarketBrain.Trend.RANGE, usual.trend, "back and forth: ranging (shown only)")
        // A steady climb at the normal pace: the range is far wider than a random walk's - busy by range alone.
        val climb = MarketBrain.regime("NIFTY", closes(3.0, trend = true), normal)
        assertEquals(MarketBrain.Trend.UP, climb.trend)
        assertEquals(MarketBrain.State.BUSY, climb.state)
        assertTrue(climb.rangeZ!! > climb.rvZ!!)
        // Too few minutes: not known.
        assertEquals(MarketBrain.State.UNKNOWN, MarketBrain.regime("NIFTY", closes(3.0).take(15), normal).state)
        assertEquals(MarketBrain.State.UNKNOWN, MarketBrain.regime("NIFTY", closes(3.0), emptyList()).state)
        assertTrue(MarketBrain.regimeLine(busy).startsWith("BANKNIFTY: busy (range z"))
    }

    @Test fun usDataTimesFollowNewYorksDaylightSaving() {
        // US CPI 08:30 New York: 18:00 IST in summer (EDT), 19:00 IST in winter (EST).
        val summer = LocalDate.of(2026, 10, 14)
        val winter = LocalDate.of(2026, 11, 12)
        val s = MarketBrain.windows(summer, listOf("US CPI"), mcx = false).single()
        assertEquals(ms(summer, 17, 55), s.startMs); assertEquals(ms(summer, 18, 5), s.endMs)
        val w = MarketBrain.windows(winter, listOf("US CPI"), mcx = false).single()
        assertEquals(ms(winter, 18, 55), w.startMs)
        // The week New York changes clocks (1 Nov 2026) - the Friday jobs report before and after.
        assertEquals(ms(LocalDate.of(2026, 10, 30), 17, 55), MarketBrain.windows(LocalDate.of(2026, 10, 30), listOf("US jobs (NFP)"), mcx = false).single().startMs)
        assertEquals(ms(LocalDate.of(2026, 11, 6), 18, 55), MarketBrain.windows(LocalDate.of(2026, 11, 6), listOf("US jobs (NFP)"), mcx = false).single().startMs)
        // FOMC on the calendar's Indian day after: the decision itself, 14:00 New York the day before (23:30 IST in summer,
        // 00:30 IST on the Indian day itself in winter).
        val fomcSummer = MarketBrain.windows(LocalDate.of(2026, 9, 17), listOf("US Fed decision overnight (FOMC)"), mcx = false).single()
        assertEquals(ms(LocalDate.of(2026, 9, 16), 23, 30), fomcSummer.startMs + 5 * 60_000L)
        val fomcWinter = MarketBrain.windows(LocalDate.of(2026, 12, 10), listOf("US Fed decision overnight (FOMC)"), mcx = false).single()
        assertEquals(ms(LocalDate.of(2026, 12, 10), 0, 30), fomcWinter.startMs + 5 * 60_000L)
        // EIA for MCX: crude on Wednesday, gas on Thursday, 10:30 New York; scoped to the commodities.
        val eia = MarketBrain.windows(summer, emptyList()).single()
        assertEquals("EIA crude stocks", eia.name)
        assertEquals(ms(summer, 20, 0), eia.startMs + 5 * 60_000L)
        assertTrue(eia.concerns("CRUDEOIL26OCTFUT")); assertFalse(eia.concerns("BANKNIFTY"))
        assertEquals("EIA gas storage", MarketBrain.windows(LocalDate.of(2026, 11, 12), emptyList()).single().name)
        assertEquals(ms(LocalDate.of(2026, 11, 12), 21, 0), MarketBrain.windows(LocalDate.of(2026, 11, 12), emptyList()).single().startMs + 5 * 60_000L)
    }

    @Test fun indianEventsAndTheExpiryHour() {
        val d = LocalDate.of(2026, 12, 4)
        val ws = MarketBrain.windows(d, listOf("RBI policy", "Union Budget", "Diwali muhurat"), expiries = listOf("NIFTY"), mcx = false)
        assertEquals(listOf("RBI policy", "Union Budget", "NIFTY expiry hour"), ws.map { it.name })
        assertEquals(ms(d, 9, 55), ws[0].startMs); assertEquals(ms(d, 10, 5), ws[0].endMs)
        assertEquals(ms(d, 10, 55), ws[1].startMs)
        val exp = ws[2]
        assertEquals(MarketBrain.Kind.EXPIRY_HOUR, exp.kind)
        assertEquals(ms(d, 14, 40), exp.startMs); assertEquals(ms(d, 15, 40), exp.endMs)
        assertTrue(exp.concerns("NIFTY")); assertFalse(exp.concerns("BANKNIFTY"))
        // An Indian CPI is not the US one.
        assertTrue(MarketBrain.windows(d, listOf("India CPI"), mcx = false).isEmpty())
    }

    private val day = LocalDate.of(2026, 12, 4)
    private val ctx = MarketBrain.Context(atMs = ms(day, 10, 0), windows = MarketBrain.windows(day, listOf("RBI policy"), listOf("NIFTY"), mcx = false))

    @Test fun newsWindowsPauseNewEntriesByDefaultAndTheOthersAreOnlyShadowed() {
        val p = MarketBrain.Policy()
        val inside = MarketBrain.decide(ctx, p, "BANKNIFTY", ms(day, 10, 2))
        assertTrue(inside.block); assertEquals("paused: RBI policy window", inside.why)
        val before = MarketBrain.decide(ctx, p, "BANKNIFTY", ms(day, 9, 54))
        assertFalse(before.block); assertNull(before.why)
        // The expiry hour is shown, never a pause.
        assertFalse(MarketBrain.decide(ctx, p, "NIFTY", ms(day, 15, 0)).block)
        // Traps, an unreliable feed, a big move's first 60 s: shadow by default - noted, not blocked.
        val noisy = ctx.copy(traps = mapOf("BANKNIFTY" to setOf("PULL_BID", "UNRELIABLE")),
            bigMove = MarketBrain.BigMove("BANKNIFTY", 1, ms(day, 11, 0)))
        val v = MarketBrain.decide(noisy, p, "BANKNIFTY", ms(day, 11, 0) + 30_000)
        assertFalse(v.block)
        assertEquals(listOf(MarketBrain.Cause.TRAPS, MarketBrain.Cause.FEED, MarketBrain.Cause.BIG_MOVE), v.shadowCauses)
        assertTrue(v.shadow[0].contains("pull bid"))
        assertFalse(MarketBrain.decide(noisy, p, "BANKNIFTY", ms(day, 11, 0) + 61_000).shadowCauses.contains(MarketBrain.Cause.BIG_MOVE))
        // Boss can switch one ON (it blocks) or news OFF (it never pauses).
        val on = MarketBrain.Policy(MarketBrain.DEFAULTS + (MarketBrain.Cause.TRAPS to MarketBrain.Mode.ON))
        assertTrue(MarketBrain.decide(noisy, on, "BANKNIFTY", ms(day, 11, 5)).block)
        val off = MarketBrain.Policy(MarketBrain.DEFAULTS + (MarketBrain.Cause.NEWS to MarketBrain.Mode.OFF))
        val vo = MarketBrain.decide(ctx, off, "BANKNIFTY", ms(day, 10, 2))
        assertFalse(vo.block); assertTrue(vo.shadow.isEmpty())
    }

    @Test fun thePolicyIsKeptAsTextAndTheDiagnosticsSayItAll() {
        val p = MarketBrain.Policy(MarketBrain.DEFAULTS + (MarketBrain.Cause.FEED to MarketBrain.Mode.OFF))
        val text = MarketBrain.encode(p)
        assertEquals("news=ON;traps=SHADOW;feed=OFF;bigmove=SHADOW", text)
        assertEquals(p.modes, MarketBrain.decode(text).modes)
        assertEquals(MarketBrain.DEFAULTS, MarketBrain.decode("junk;news=BAD;=x").modes)
        val c = ctx.copy(regimes = mapOf("NIFTY" to MarketBrain.Regime("NIFTY", MarketBrain.State.BUSY, 2.5, 1.0, MarketBrain.Trend.UP, 0.6)))
        val lines = MarketBrain.lines(c, ms(day, 10, 0), MarketBrain.Policy(), ::hhmm)
        assertTrue(lines[0].startsWith("Regime: NIFTY: busy"), lines[0])
        assertEquals("Windows now: RBI policy 09:55-10:05", lines[1])
        assertEquals("Windows later today: NIFTY expiry hour 14:40-15:40", lines[2])
        assertTrue(lines.last().startsWith("Pause new entries during: news windows on, trap alerts shadow"), lines.last())
        // FINNIFTY has no regime of its own: NIFTY's stands for it.
        assertTrue(c.busy("FINNIFTY")); assertFalse(c.quiet("NIFTY")); assertTrue(c.anyBusy()); assertFalse(c.allQuiet())
    }
}
