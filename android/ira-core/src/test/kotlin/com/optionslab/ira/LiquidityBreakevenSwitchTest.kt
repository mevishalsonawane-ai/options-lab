package com.optionslab.ira

import com.optionslab.engine.orb.LiquidityBreakeven
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boss's breakeven at +10% on the Liquidity PAPER books (22 Oct: "if the 10% is received, make the stoploss to the start point";
 * research R100 A4 did not pass, so it is their own switch): off by default and kept in the paper test's small file; logged in
 * the change timeline as Boss's ([AutoChange.Kind.LIQ_BREAKEVEN]), paper only, undone by its own tap and never counted as
 * Autopilot's change; and its trades kept out of the plain-rules record and labelled as its own wherever they are said.
 */
class LiquidityBreakevenSwitchTest {
    private val day = LocalDate.of(2026, 10, 22)

    @Test fun offByDefaultAndKeptInTheFile() {
        assertFalse(LiquidityFreeze.State().breakeven)
        assertFalse(LiquidityFreeze.decode("v1\nunfrozen=true\ntrail=true\nnoTwin=true\n").breakeven, "a file from before it: off")
        val on = LiquidityFreeze.State(unfrozen = true, trail = false, breakeven = true)
        assertEquals(on, LiquidityFreeze.decode(LiquidityFreeze.encode(on)))
        assertTrue(LiquidityFreeze.encode(on).contains("breakeven=true"))
    }

    @Test fun theCardSaysWhatItDoesThatItDidNotPassAndItsNumbers() {
        val off = LiquidityFreeze.breakevenNote(LiquidityFreeze.State())
        assertTrue(off.startsWith("Your breakeven: once a trade is +10%, its stop moves to the price paid plus charges (did not pass research, your choice): off."), off)
        assertTrue(off.contains("Rs 2,45,083 at a 38% win rate") && off.contains("Rs 2,68,404 at 42%"), off)
        val on = LiquidityFreeze.breakevenNote(LiquidityFreeze.State(breakeven = true))
        assertTrue(on.contains("ON for new Liquidity PAPER trades") && on.contains("\"with breakeven at +10%\"") && on.contains("live trades never use it"), on)
        assertTrue(LiquidityFreeze.breakevenSaid(true).contains("Live trades never use it"))
        assertTrue(LiquidityFreeze.breakevenSaid(false).startsWith("Your breakeven off"))
    }

    @Test fun switchingItOnIsBosssPaperChangeUndoneWithoutThePin() {
        val on = AutoChange.liqBreakeven(true)
        assertEquals(AutoChange.Kind.LIQ_BREAKEVEN, on.kind)
        assertEquals(AutoChange.BREAKEVEN_TARGET, on.target)
        assertFalse("liquidity" in on.target, "the paper-test freeze never holds Boss's own Undo")
        assertEquals(AutoChange.SWITCHED_OFF, on.from); assertEquals(AutoChange.ON, on.to)
        assertEquals(AutoChange.Scope.PAPER, on.scope)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on))
        assertFalse(AutoChange.never(on)); assertFalse(AutoChange.undoNeedsPin(on))
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(AutoChange.inverse(on, "undone by you")))
        assertTrue(on.reason.contains("did not pass research") && on.reason.contains("26 Oct"), on.reason)
        assertTrue(AutoChange.bossOwn(AutoChange.Kind.LIQ_BREAKEVEN))
        // In the timeline: Boss's, undoable by its own tap, never Autopilot's; an older flip is superseded by a newer one.
        var b = AutoChange.Book()
        val (b1, first) = AutoChange.record(b, on, AutoChange.Status.KEPT, 1_000L, day, by = "your choice")
        b = b1
        val item = ChangeTimeline.fromAutopilot(b, day).single { it.key == first.id }
        assertTrue(item.own); assertEquals("Liquidity's breakeven at +10% (paper): off → on", item.what)
        assertNull(AutoChange.lastUndoable(b))
        assertEquals(0, AutoChange.summary(b, day).kept)
        val (b2, _) = AutoChange.record(b, AutoChange.liqBreakeven(false), AutoChange.Status.KEPT, 2_000L, day, by = "your choice")
        assertTrue(AutoChange.superseded(b2, b2.entries.first { it.id == first.id }))
        // The ₹500 trail's own flips are not the breakeven's (another target).
        val (b3, trail) = AutoChange.record(b, AutoChange.liqTrail(true), AutoChange.Status.KEPT, 3_000L, day, by = "your choice")
        assertFalse(AutoChange.superseded(b3, b3.entries.first { it.id == first.id }))
        assertFalse(AutoChange.superseded(b3, trail))
    }

    private fun t(h: Int, entry: Double, exit: Double, why: String, rules: String?): BotTrades.Trade {
        val at = LocalDateTime.of(day, java.time.LocalTime.of(h, 0))
        return BotTrades.Trade("liquidity5_mid", "MIDCPNIFTY27OCT2613300PE", "PE", 120, entry, at, at.minusMinutes(5), exit, at.plusMinutes(20), why, 90.0,
            lot = 120, trail = rules != null, rules = rules)
    }

    private val book = listOf(
        t(10, 172.95, 164.00, "time_stop", null),                                            // plain rules: -1,164
        t(11, 172.95, 173.80, LiquidityBreakeven.WHY, LiquidityBreakeven.LABEL),              // breakeven: +12
        t(12, 150.00, 180.00, "next_liquidity", LiquidityBreakeven.LABEL),                    // breakeven rule, ran on: +3,510
    )

    @Test fun itsTradesAreKeptOutOfThePlainRulesRecordAndLabelledAsTheirOwn() {
        val plain = LiquidityRecord.rows(book)
        assertEquals(1, plain.size)
        val own = LiquidityRecord.rows(book, withTrail = true).filter { it.trailed }
        assertEquals(2, own.size)
        assertEquals(LiquidityBreakeven.LABEL, LiquidityRecord.ownLabel(own))
        val line = LiquidityRecord.trailLine(LiquidityRecord.Q(), own, day)!!
        assertTrue(line.startsWith("With your breakeven at +10% (did not pass research, your choice), kept apart from the plain-rules record above: 2 trades"), line)
        // A mix of Boss's own rules is said as such; the old trail-only trades keep their words.
        val mixed = own + LiquidityRecord.rows(listOf(t(13, 300.0, 320.0, "give_back_trail", null).copy(trail = true)), withTrail = true)
        assertEquals("on your own exit rules", LiquidityRecord.ownLabel(mixed))
        assertTrue(LiquidityRecord.trailLine(LiquidityRecord.Q(), mixed, day)!!.startsWith(
            "On your own exit rules (your ₹500 trail and your breakeven at +10%; your choice), kept apart from the plain-rules record above: 3 trades"))
    }

    @Test fun theDaysListAndTheEveningNoteLabelThem() {
        val rows = LiquidityRecord.rows(book, withTrail = true)
        val line = DayRecap.liquidityLine(DayRecap.Facts(day = day, today = day, liquidity = rows))
        assertTrue(line.contains("(with breakeven at +10%)"), line)
        assertTrue(line.endsWith("Of them, 2 with breakeven at +10% (not the plain rules): net +Rs 3,522 a lot."), line)
        val d = TomorrowPlan.dayOf(rows, day)
        assertEquals(TomorrowPlan.Day(3, 2, rows.sumOf { it.net }, trailed = 2, rules = LiquidityBreakeven.LABEL), d)
        val f = TomorrowPlan.Facts(today = day, next = day.plusDays(1), liquidity = d)
        assertTrue(TomorrowPlan.lines(f).any { it.endsWith("(2 with breakeven at +10%, not the plain rules).") }, TomorrowPlan.lines(f).joinToString("\n"))
    }
}
