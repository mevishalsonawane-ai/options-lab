package com.optionslab.ira

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import com.optionslab.engine.options.OptionMath
import com.optionslab.engine.options.OptionType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchFTest {
    private val day = LocalDate.of(2026, 9, 1)

    /** A session whose ATM call and put are priced at [iv] (Black-Scholes), index flat at 25,000. */
    private fun session(d: LocalDate, iv: Double): Session {
        val mins = IntArray(376) { 555 + it }
        val expiry = d.plusDays(6)
        val t = (6.0 + (15.5 * 60 - IvRank.MINUTE) / (24 * 60)) / 365.0
        fun opt(r: Right, ot: OptionType): Series {
            val px = com.optionslab.engine.options.Payoff.bsPrice(ot, 25_000.0, 25_000.0, t, iv)
            return Series(expiry, 25_000.0, r, 75, mins, DoubleArray(mins.size) { px }, null, null, null, null, LongArray(mins.size))
        }
        val ix = Series(null, 0.0, Right.IX, 0, mins, DoubleArray(mins.size) { 25_000.0 }, null, null, null, null, LongArray(mins.size))
        return Session(d, 75, listOf(ix, opt(Right.CE, OptionType.CE), opt(Right.PE, OptionType.PE)))
    }

    @Test fun impliedVolatilityIsReadAndRankedOverTheYear() {
        assertEquals(0.14, IvRank.atm(session(day, 0.14), 50)!!, 0.002)
        val hist = (1..200).map { day.minusDays(it.toLong()) to 0.10 + (it % 10) * 0.01 }      // 10% .. 19%
        assertEquals(1.0, IvRank.rank(hist, 0.25, day)!!, 1e-9)
        assertEquals(0.0, IvRank.rank(hist, 0.05, day)!!, 1e-9)
        assertNull(IvRank.rank(hist.take(30), 0.2, day))
        assertTrue(IvRank.say(0.95, 0.25).endsWith("Not suggested."))
        assertTrue(IvRank.say(0.1, 0.1).startsWith("Options are cheap now"))
        val p = com.optionslab.engine.options.Payoff.bsPrice(OptionType.CE, 25_000.0, 25_000.0, 5.0 / 365, 0.15)
        assertEquals(0.15, IvRank.now(p, true, 25_000.0, 25_000.0, 5.0)!!, 0.002)
        assertNotNull(OptionMath)
    }

    @Test fun lotsFollowTheRupeeRisk() {
        assertEquals(1, RiskSizing.lots(null, 100.0, 75, 10))
        // 100 premium x 15% x 75 = 1,125 a lot: Rs 3,000 risk -> 2 lots; capped by the app's max lots.
        assertEquals(2, RiskSizing.lots(3_000.0, 100.0, 75, 10))
        assertEquals(0, RiskSizing.lots(500.0, 100.0, 75, 10))               // one lot would risk more than allowed
        assertEquals(3, RiskSizing.lots(50_000.0, 100.0, 75, 3))
        assertTrue(RiskSizing.say(2, 100.0, 75).startsWith("2 lots: the 15% stop risks about Rs 2,250"))
    }

    @Test fun eventDaysAreComparedWithOtherDays() {
        // Two years of weekdays; RBI days swing 2%, others 0.5%.
        val days = ArrayList<Study.Day>()
        var d = LocalDate.of(2024, 1, 1); var prev: Double? = null
        while (d.isBefore(LocalDate.of(2025, 12, 31))) {
            if (d.dayOfWeek.value <= 5) {
                val big = d in EventStudy.RBI
                val o = 22_000.0; val c = if (big) o * 1.015 else o * 1.001
                days += Study.Day(d, o, o * (if (big) 1.02 else 1.005), o, c, prev, null, null, null, null, null, null)
                prev = c
            }
            d = d.plusDays(1)
        }
        val lines = EventStudy.run(Market.NIFTY, days)
        val rbi = lines.single { it.kind == EventStudy.Kind.RBI }
        assertEquals(12, rbi.days); assertEquals(2.0, rbi.range, 1e-6); assertEquals(0.5, rbi.usualRange, 1e-6)
        assertTrue(rbi.text().startsWith("Nifty on RBI policy days (12 in two years): range 2.00% against 0.50% on other days"))
        assertEquals(EventStudy.Kind.FED, EventStudy.kindOf("US Fed decision overnight (FOMC)"))
        assertEquals(EventStudy.Kind.RBI, EventStudy.kindOf("RBI policy"))
        assertNull(EventStudy.kindOf("NIFTY expiry"))
        // The Fed is felt on the next Indian session.
        assertTrue(LocalDate.of(2024, 2, 1) in EventStudy.dates(EventStudy.Kind.FED, days.map { it.date }.toSet()))
    }

    @Test fun hinglishIsUnderstoodAndEnglishLeftAlone() {
        assertEquals(Command.Kind.STOP_ONE, Commands.parse("strategy 1 band karo")?.kind)
        assertEquals(1, Commands.parse("strategy 1 band karo")?.number)
        assertEquals(Command.Kind.STOP_ALL, Commands.parse("sab strategies band karo")?.kind)
        assertEquals(Command.Kind.CANCEL_ALL, Commands.parse("saare orders cancel karo")?.kind)
        assertEquals(setOf(Topic.TRADE_CHECK), Ask.parse("aaj trade karu kya").topics)
        assertTrue(Market.NIFTY in Ask.parse("Nifty kaisa hai").markets)
        assertEquals(Topic.ORDER, Ask.parse("1 lot nifty atm ce khareedo").topics.first())
        assertEquals(true, Wake.yesNo("haan")); assertEquals(true, Wake.yesNo("ji haan, kar do"))
        assertEquals(false, Wake.yesNo("nahi")); assertEquals(false, Wake.yesNo("mat karo"))
        // English is untouched.
        assertEquals("Show the Bollinger band, 24,500", Hinglish.normalize("Show the Bollinger band, 24,500"))
        assertEquals(Command.Kind.STOP_ONE, Commands.parse("stop strategy 2")?.kind)
        assertEquals(true, Wake.yesNo("yes")); assertEquals(false, Wake.yesNo("no"))
    }

    @Test fun anArmSlippingIsNamed() {
        val today = LocalDate.of(2026, 10, 1)
        val old = (1..80).map { ArmHealth.T(today.minusDays(100L + it), "ORB", 500.0) }
        val recent = (1..10).map { ArmHealth.T(today.minusDays(it.toLong()), "ORB", -300.0) }
        val good = (1..40).map { ArmHealth.T(today.minusDays(it * 3L), "Liquidity 15+5", 400.0) }
        val c = ArmHealth.check(old + recent + good, today)
        val orb = c.single { it.arm == "ORB" }
        assertTrue(orb.slipping); assertTrue(orb.text().endsWith("slipping, worth a look."))
        assertTrue(!c.single { it.arm == "Liquidity 15+5" }.slipping)
    }
}
