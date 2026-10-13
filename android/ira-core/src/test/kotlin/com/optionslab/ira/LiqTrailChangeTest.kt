package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boss's own Rs 500 give-back trail on the Liquidity PAPER books (13 Oct review): its switch is logged in the Autopilot change
 * timeline as Boss's ([AutoChange.Kind.LIQ_TRAIL]), undone only by its own tap and never counted as Autopilot's change; and
 * the trades it touched are kept out of the plain-rules record Jarvis reads ([LiquidityRecord.rows]), said on a labelled line
 * of their own, and labelled in a day's list ([DayRecap.liquidityLine]).
 */
class LiqTrailChangeTest {
    private val day = LocalDate.of(2026, 10, 13)

    @Test fun switchingItOnIsAPaperChangeWhoseUndoSwitchesItOffWithoutThePin() {
        val on = AutoChange.liqTrail(true)
        assertEquals(AutoChange.Kind.LIQ_TRAIL, on.kind)
        assertEquals(AutoChange.TRAIL_TARGET, on.target)
        assertFalse("liquidity" in on.target, "the paper-test freeze never holds Boss's own Undo")
        assertEquals(AutoChange.SWITCHED_OFF, on.from); assertEquals(AutoChange.ON, on.to)
        assertEquals(AutoChange.Scope.PAPER, on.scope)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on))
        assertFalse(AutoChange.never(on))
        assertFalse(AutoChange.undoNeedsPin(on), "paper only: no PIN")
        val back = AutoChange.inverse(on, "undone by you")
        assertEquals(AutoChange.SWITCHED_OFF, back.to)
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(back))
        assertTrue(on.reason.contains("failed research"), on.reason)
        assertTrue(on.reason.contains("26 Oct"), on.reason)
        val off = AutoChange.liqTrail(false)
        assertEquals(AutoChange.ON, off.from); assertEquals(AutoChange.SWITCHED_OFF, off.to)
        assertEquals(AutoChange.ON, AutoChange.inverse(off, "").to)
        assertFalse(AutoChange.undoNeedsPin(off))
        assertTrue(AutoChange.bossOwn(AutoChange.Kind.LIQ_TRAIL)); assertTrue(AutoChange.bossOwn(AutoChange.Kind.ARM_PARK))
        assertFalse(AutoChange.bossOwn(AutoChange.Kind.PAPER_ENTRIES))
    }

    private fun book(): Pair<AutoChange.Book, String> {
        var b = AutoChange.Book()
        val auto = AutoChange.Change(AutoChange.Kind.PAPER_ENTRIES, "paper:night_r3", AutoChange.ON, AutoChange.PAUSED, "loses on paper",
            AutoChange.Evidence(30, -3_000.0, AgentLessons.Confidence.STRONG), AutoChange.Risk.LOWER, AutoChange.Scope.PAPER, what = "Night (R3)'s new paper entries")
        b = AutoChange.record(b, auto, AutoChange.Status.PROBATION, 500L, day).first
        val (b1, trail) = AutoChange.record(b, AutoChange.liqTrail(true), AutoChange.Status.KEPT, 1_000L, day, by = "your choice")
        return b1 to trail.id
    }

    @Test fun itIsBosssInTheTimelineAndOnlyItsOwnTapUndoesIt() {
        val (b, id) = book()
        val items = ChangeTimeline.fromAutopilot(b, day)
        val it = items.single { i -> i.key == id }
        assertTrue(it.own); assertEquals("You (logged by Autopilot)", it.agent)
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, it.undo)
        assertEquals("Liquidity's ₹500 trail (paper): off → on", it.what)
        // "Undo everything today" leaves it alone; "undo the last change" picks Autopilot's own, though the trail came after.
        val plan = ChangeTimeline.undoPlan(items)
        assertTrue(plan.now.none { i -> i.own } && plan.left.none { i -> i.own } && plan.needPin.none { i -> i.own })
        assertEquals(1, plan.now.size)
        assertEquals("paper:night_r3", AutoChange.lastUndoable(b)!!.change.target)
        // Its own Undo switches it off.
        val u = AutoChange.undo(b, id)!!
        assertEquals(AutoChange.SWITCHED_OFF, u.inverse.to)
        assertFalse(u.needsPin)
        // Once undone it cannot be undone again (no loop).
        assertNull(AutoChange.undo(AutoChange.undone(b, id, day), id))
    }

    @Test fun itIsNeverCountedAsAutopilotsChange() {
        val (b, _) = book()
        val s = AutoChange.summary(b, day)
        assertEquals(1, s.today); assertEquals(0, s.kept)
        assertFalse("₹500 trail" in AutoChange.sayToday(b, day))
        val only = AutoChange.record(AutoChange.Book(), AutoChange.liqTrail(true), AutoChange.Status.KEPT, 1L, day).first
        assertNull(AutoChange.notice(AutoChange.summary(only, day)))
        assertNull(AutoChange.lastUndoable(only))
        assertEquals("I changed nothing by myself today, Boss.", AutoChange.sayToday(only, day))
    }

    // ---- the plain-rules record Jarvis reads -----------------------------------------------------------------------------

    private val today = LocalDate.of(2026, 10, 15)

    private fun t(day: LocalDate, h: Int, entry: Double, exit: Double, why: String, trail: Boolean = false, source: String = "liquidity15"): BotTrades.Trade {
        val at = LocalDateTime.of(day, java.time.LocalTime.of(h, 0))
        return BotTrades.Trade(source, "BANKNIFTY26OCT56000CE", "CE", 30, entry, at, at.minusMinutes(5), exit, at.plusMinutes(15), why, 60.0,
            lot = 30, trail = trail)
    }

    private val book = listOf(
        t(LocalDate.of(2026, 10, 13), 10, 300.0, 330.0, "next_liquidity"),                         // +840
        t(LocalDate.of(2026, 10, 14), 11, 300.0, 320.0, "give_back_trail", trail = true),             // +540, trailed
        t(LocalDate.of(2026, 10, 15), 10, 300.0, 290.0, "index_stop"),                                // -360
        t(LocalDate.of(2026, 10, 15), 12, 300.0, 310.0, "stop", trail = true),                        // +240, trailed
    )

    @Test fun trailedTradesAreLeftOutOfThePlainRulesRecordByDefault() {
        val plain = LiquidityRecord.rows(book)
        assertEquals(2, plain.size)
        assertTrue(plain.none { it.trailed })
        assertEquals(480.0, plain.sumOf { it.net }, 1e-9)
        val all = LiquidityRecord.rows(book, withTrail = true)
        assertEquals(4, all.size)
        assertEquals(2, all.count { it.trailed })
        // Every reader of the plain record (drawdown, patterns, hold times, lots what-if) gets the rules' trades only.
        assertEquals(2, LiquidityDrawdown.read(plain).n)
    }

    @Test fun theRecordAnswerSaysTheTrailedTradesOnALabelledLineOfTheirOwn() {
        val plain = LiquidityRecord.rows(book)
        val trailed = LiquidityRecord.rows(book, withTrail = true).filter { it.trailed }
        val a = LiquidityRecord.answer(LiquidityRecord.Q(), plain, today, trailed)
        assertTrue(a.startsWith("Boss, Liquidity 15+5's paper record so far: 2 trades, 1 won and 1 lost, net +Rs 480"), a)
        val line = a.lines().single { it.startsWith("With your ₹500 trail") }
        assertEquals("With your ₹500 trail (failed research, your choice), kept apart from the plain-rules record above: " +
            "2 trades, 2 won, net +Rs 780 a lot after charges (+Rs 780 in all).", line)
        assertTrue(a.endsWith(LiquidityRecord.NOTE), a)
        // Without trailed trades: no such line, the answer as before.
        assertFalse("₹500 trail" in LiquidityRecord.answer(LiquidityRecord.Q(), plain, today))
        // The span asked holds for the trail's line too: today only.
        val todayOnly = LiquidityRecord.answer(LiquidityRecord.Q(LiquidityRecord.Span.DAY, day = java.time.MonthDay.of(10, 15)), plain, today, trailed)
        assertTrue(todayOnly.contains("kept apart from the plain-rules record above: 1 trade, 1 won, net +Rs 240"), todayOnly)
        // Only trailed trades closed: said plainly, with their line.
        val onlyTrail = LiquidityRecord.answer(LiquidityRecord.Q(), emptyList(), today, trailed)
        assertTrue(onlyTrail.startsWith("Liquidity 15+5 has no closed paper trade on plain rules in its book yet"), onlyTrail)
        assertTrue(onlyTrail.contains("2 trades, 2 won"), onlyTrail)
        assertNull(LiquidityRecord.trailLine(LiquidityRecord.Q(), emptyList(), today))
    }

    @Test fun aDaysListKeepsTheTrailedTradesAndLabelsThem() {
        val rows = LiquidityRecord.rows(book, withTrail = true).filter { it.day == today }
        val f = DayRecap.Facts(day = today, today = today, liquidity = rows)
        val line = DayRecap.liquidityLine(f)
        assertTrue(line.startsWith("Liquidity 15+5 (paper): 2 trades"), line)
        assertTrue(line.contains("12:00 BankNifty 15-min CE +Rs 240 a lot (with ₹500 trail)"), line)
        assertTrue(line.endsWith("Of them, 1 with ₹500 trail (not the plain rules): net +Rs 240 a lot."), line)
        // One trailed trade alone: labelled on its own line.
        val one = DayRecap.liquidityLine(f.copy(liquidity = rows.filter { it.trailed }))
        assertEquals("Liquidity 15+5 (paper): 1 trade - 12:00 BankNifty 15-min CE +Rs 240 a lot (with ₹500 trail) after charges (15% premium stop).", one)
        // No trailed trade: as before.
        assertFalse("trail" in DayRecap.liquidityLine(f.copy(liquidity = rows.filter { !it.trailed })))
    }

    @Test fun theCardLineSaysWhatTheSwitchCostsTheRecord() {
        assertEquals("While it is on, new Liquidity BANKNIFTY paper trades use your trail, so the plain-rules record (used for the 26 Oct decision) does not grow.",
            LiquidityFreeze.TRAIL_RECORD)
    }
}
