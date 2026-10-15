package com.optionslab.app.data

import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.OrbRules
import com.optionslab.ira.DangerMeter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The R17 shadow log (TODO I3 / I5) and the danger meter's switch (I4): memory only, Liquidity never, the switch off by default. */
class PreTradeShadowTest {
    private val day = LocalDate.of(2031, 3, 4)          // a Tuesday no other test writes
    private val ce = Paper.Contract("BANKNIFTY31MAR57000CE", "BANKNIFTY", day.plusDays(2), 57_000.0, Right.CE, 30, "k")
    private val weekdays: (LocalDate) -> Boolean = { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }

    private fun bars(): List<Bar> = (0 until 20).map { i ->
        val t = day.atTime(9, 15).plusMinutes(5L * i)
        Bar(t, 57_000.0 + i, 57_030.0 + i, 56_980.0 + i, 57_010.0 + i)
    }

    @Test fun anOrbEntryIsNotedWithItsChecksAndLiquidityNever() {
        PreTradeShadow.vixFrom(13.0, 12.5, day.minusDays(1).atTime(15, 29), day, weekdays)   // the previous session's bar: its last price
        PreTradeShadow.orbBars(bars())
        val signal = day.atTime(10, 45)
        PreTradeShadow.orb(OrbRules.ORB, ce, 420.0, 30, signal, 40.0)
        PreTradeShadow.orb(LiquidityRules.ARM, ce, 420.0, 30, signal, 40.0)
        PreTradeShadow.orb(HeroRules.ARM, ce, 420.0, 30, signal, 40.0)
        val notes = PreTradeShadow.day(day)
        assertEquals(1, notes.size)
        val n = notes.single()
        assertEquals("orb", n.arm)
        assertEquals(10 * 60 + 50, n.minuteOfDay)
        assertTrue(n.line(), n.charges.ratio != null && n.smartStop != null && n.fixedStop == 40.0)
        assertTrue(PreTradeShadow.lines("orb", day).single().startsWith("10:50 ORB: Costs take"))
        assertTrue(PreTradeShadow.lines(LiquidityRules.ARM.source, day).isEmpty())
        val review = PreTradeShadow.reviewNotes(day)
        assertTrue(review.first(), review.first().startsWith("Charges check (shadow, R17 — failed its holdout test on 499 trades"))
        assertEquals(2, review.size)
    }

    @Test fun heroAndVixDivisionNotesReadTheirOwnMinutes() {
        val d = day.plusDays(1)
        PreTradeShadow.vixFrom(13.0, 12.0, d.atTime(9, 30), d, weekdays)                // today's bar: its prevClose
        // NIFTY's minutes as the Hero arm reads them (keyed by the minute each closes), with highs and lows.
        val spot = (0 until 70).associate { i ->
            val closes = LocalTime.of(13, 0).plusMinutes(i.toLong())
            val t = d.atTime(closes).minusMinutes(1).atZone(IST).toEpochSecond()
            closes to Upstox.Bar(t, 25_000.0 + i, 25_008.0 + i, 24_992.0 + i, 25_000.0 + i, 0, 0)
        }
        val pe = ce.copy(underlying = "NIFTY", expiry = d, strike = 24_900.0, right = Right.PE)
        PreTradeShadow.hero(HeroRules.ARM, spot, pe, 3.0, 1300, d, LocalTime.of(14, 0))
        val idx = (0 until 60).map { i ->
            val t = d.atTime(10, 0).plusMinutes(i.toLong()).atZone(IST).toEpochSecond()
            Upstox.Bar(t, 25_000.0, 25_010.0 + i, 24_990.0, 25_000.0 + i, 0, 0)
        }
        PreTradeShadow.vixDiv("vix_div_nifty", "VIX divergence NIFTY", idx, pe.copy(expiry = d.plusDays(6), strike = 25_050.0), 120.0, 65,
            d.atTime(10, 30), 18.0)
        val n = PreTradeShadow.day(d)
        assertEquals(listOf("hero", "vix_div_nifty"), n.map { it.arm })
        assertEquals(1.8, n[0].fixedStop!!, 1e-9)
        assertTrue(n.all { it.charges.expectedPoints != null })
        assertTrue(n[0].line(), n[0].line().contains("Hero's R17 sample is tiny"))
        // Hero's window read the highs and lows: wider than its closes alone would give.
        val closesOnly = spot.mapValues { (_, b) -> b.copy(high = b.close, low = b.close) }
        val d2 = d.plusDays(1)
        PreTradeShadow.vixFrom(13.0, 12.0, d2.atTime(9, 30), d2, weekdays)
        PreTradeShadow.hero(HeroRules.ARM, closesOnly.mapValues { (_, b) -> b.copy(epochSecond = b.epochSecond + 86_400) }, pe.copy(expiry = d2), 3.0, 1300, d2,
            LocalTime.of(14, 0))
        assertTrue(n[0].charges.expectedPoints!! > PreTradeShadow.day(d2).single().charges.expectedPoints!!)
    }

    @Test fun noVixMeansTheMoveIsNotKnownNotGuessed() {
        val d = day.plusDays(30)
        PreTradeShadow.orbBars(bars().map { it.copy(start = it.start.plusDays(30)) })
        PreTradeShadow.orb(OrbRules.ORB_FRESH, ce, 420.0, 30, d.atTime(10, 45), 40.0)
        assertTrue(PreTradeShadow.day(d).single().charges.line().contains("not known"))
    }

    @Test fun aStaleVixSnapshotIsNotUsed() {
        val d = day.plusDays(35)                                                         // a Tuesday
        // Five days old (the phone did not read since): VIX stays unknown, so the move is "not known".
        PreTradeShadow.vixFrom(13.0, 12.0, d.minusDays(5).atTime(15, 29), d, weekdays)
        PreTradeShadow.orbBars(bars().map { it.copy(start = it.start.plusDays(35)) })
        PreTradeShadow.orb(OrbRules.ORB, ce, 420.0, 30, d.atTime(10, 45), 40.0)
        assertTrue(PreTradeShadow.day(d).single().charges.line().contains("not known"))
        // Over a holiday: Monday a holiday, so Friday is Tuesday's previous session and Friday's close counts.
        val e = d.plusDays(7)
        PreTradeShadow.vixFrom(13.0, 12.0, e.minusDays(4).atTime(15, 29), e) { weekdays(it) && it != e.minusDays(1) }
        PreTradeShadow.orbBars(bars().map { it.copy(start = it.start.plusDays(42)) })
        PreTradeShadow.orb(OrbRules.ORB, ce, 420.0, 30, e.atTime(10, 45), 40.0)
        assertTrue(PreTradeShadow.day(e).single().charges.ratio != null)
    }

    @Test fun halfSizeIsOffUntilBossTurnsItOnAndOnlyLowers() {
        val d = day.plusDays(60)
        DangerToday.halfSize = false
        DangerToday.read(DangerMeter.Inputs(0.05, 11.0, 0.0, event = true, expiry = true, day = DayOfWeek.TUESDAY), d)
        assertEquals(25, DangerToday.paperLots(25, d))
        DangerToday.halfSize = true
        try {
            assertEquals(12, DangerToday.paperLots(25, d))
            assertEquals(25, DangerToday.paperLots(25, d.plusDays(1)))                 // no reading for that day: unchanged
            assertEquals(1, DangerToday.paperLots(1, d))
        } finally { DangerToday.halfSize = false }
    }

    @Test fun theReadingTheDayAndItsInputsAreOneObject() {
        val d = day.plusDays(70)
        val early = DangerMeter.Inputs(null, 12.0, 0.08, event = false, expiry = false, day = d.dayOfWeek)
        val r0 = DangerToday.read(early, d)
        assertTrue(r0.provisional)
        val held = DangerToday.state.value!!
        assertEquals(d, held.day); assertSame(early, held.inputs); assertSame(r0, held.reading)
        assertSame(early, DangerToday.inputs(d))
        // The 09:16 re-read replaces it whole: the card sees the new reading, the next day sees none.
        val r1 = DangerToday.read(early.copy(gapPct = 0.05), d)
        assertFalse(r1.provisional)
        assertSame(r1, DangerToday.today(d))
        assertNull(DangerToday.today(d.plusDays(1)))
        assertNull(DangerToday.inputs(d.plusDays(1)))
    }
}
