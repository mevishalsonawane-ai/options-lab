package com.optionslab.app.data

import com.optionslab.engine.Right
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.OrbRules
import com.optionslab.ira.DangerMeter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The R17 shadow log (TODO I3 / I5) and the danger meter's switch (I4): memory only, Liquidity never, the switch off by default. */
class PreTradeShadowTest {
    private val day = LocalDate.of(2031, 3, 4)          // a day no other test writes
    private val ce = Paper.Contract("BANKNIFTY31MAR57000CE", "BANKNIFTY", day.plusDays(2), 57_000.0, Right.CE, 30, "k")

    private fun bars(): List<Bar> = (0 until 20).map { i ->
        val t = day.atTime(9, 15).plusMinutes(5L * i)
        Bar(t, 57_000.0 + i, 57_030.0 + i, 56_980.0 + i, 57_010.0 + i)
    }

    @Test fun anOrbEntryIsNotedWithItsChecksAndLiquidityNever() {
        PreTradeShadow.vixFrom(13.0, 12.5, day.minusDays(1).atTime(15, 29), day)       // before today's first bar: the last price
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
        assertTrue(review.first(), review.first().startsWith("Charges check (shadow, R17"))
        assertEquals(2, review.size)
    }

    @Test fun heroAndVixDivisionNotesReadTheirOwnMinutes() {
        val d = day.plusDays(1)
        PreTradeShadow.vixFrom(13.0, 12.0, d.atTime(9, 30), d)                          // after today's first bar: prevClose
        val spot = (0 until 70).associate { LocalTime.of(13, 0).plusMinutes(it.toLong()) to 25_000.0 + it }
        val pe = ce.copy(underlying = "NIFTY", expiry = d, strike = 24_900.0, right = Right.PE)
        PreTradeShadow.hero(HeroRules.ARM, spot, pe, 3.0, 1300, d, LocalTime.of(14, 0))
        val idx = (0 until 60).map { i ->
            val t = d.atTime(10, 0).plusMinutes(i.toLong()).atZone(com.optionslab.engine.IST).toEpochSecond()
            com.optionslab.engine.Upstox.Bar(t, 25_000.0, 25_010.0 + i, 24_990.0, 25_000.0 + i, 0, 0)
        }
        PreTradeShadow.vixDiv("vix_div_nifty", "VIX divergence NIFTY", idx, pe.copy(expiry = d.plusDays(6), strike = 25_050.0), 120.0, 65,
            d.atTime(10, 30), 18.0)
        val n = PreTradeShadow.day(d)
        assertEquals(listOf("hero", "vix_div_nifty"), n.map { it.arm })
        assertEquals(1.8, n[0].fixedStop!!, 1e-9)
        assertTrue(n.all { it.charges.expectedPoints != null })
    }

    @Test fun noVixMeansTheMoveIsNotKnownNotGuessed() {
        val d = day.plusDays(30)
        PreTradeShadow.orbBars(bars().map { it.copy(start = it.start.plusDays(30)) })
        PreTradeShadow.orb(OrbRules.ORB_FRESH, ce, 420.0, 30, d.atTime(10, 45), 40.0)
        assertTrue(PreTradeShadow.day(d).single().charges.line().contains("not known"))
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
}
