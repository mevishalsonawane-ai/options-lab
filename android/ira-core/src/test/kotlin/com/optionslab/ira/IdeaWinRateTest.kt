package com.optionslab.ira

import com.optionslab.engine.orb.ShadowRules
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 28 Oct (Boss: "Dont create a pine script or stratergies automatically, until 65% + winrate is found"): the shared gate
 * ([IdeaWinRate]) and each automatic creator wired to it - the forge's proposals, the lab's variant promotions, the shadow
 * re-arms and Jarvis's own Pine scripts - held at 64.9% and allowed at 65% with every other gate passing; a losing average,
 * too few trades, an in-sample or uncosted record held; Boss's own actions and the running arms untouched; the setting's PIN;
 * the rule's timeline entry and its undo.
 */
class IdeaWinRateTest {
    private val day = LocalDate.of(2026, 10, 28)
    private val rule = IdeaWinRate.Rule.DEFAULT

    private fun oos(trades: Int, wins: Int, avg: Double = 500.0) = IdeaWinRate.Evidence(trades, wins, avg, IdeaWinRate.Sample.OUT_OF_SAMPLE, afterCosts = true)

    // ---- the shared gate ---------------------------------------------------------------------------------------------------

    @Test fun everyCreatorIsHeldAt64Point9AndAllowedAt65() {
        for (c in IdeaWinRate.Creator.entries) {
            val low = IdeaWinRate.held(c, oos(1000, 649), rule)
            assertEquals("held: win rate 64.9% (1000) below your 65% rule", low, c.name)
            assertNull(IdeaWinRate.held(c, oos(1000, 650), rule), c.name)
            assertTrue(IdeaWinRate.passes(c, oos(1000, 650), rule), c.name)
        }
    }

    @Test fun aHighWinRateWithALosingAverageStaysHeld() {
        // 70 wins of Rs 10 and 30 losses of Rs 100: 70% won, and Rs -23 a trade after costs.
        val nets = List(70) { 10.0 } + List(30) { -100.0 }
        val e = IdeaWinRate.fromNets(nets)
        assertEquals(70.0, e.winPct, 1e-9)
        for (c in IdeaWinRate.Creator.entries) {
            val why = IdeaWinRate.held(c, e, rule)
            assertNotNull(why, c.name)
            assertTrue(why.startsWith("held: loses after costs") && "70%" in why, why)
        }
        // A zero average is no profit either.
        assertNotNull(IdeaWinRate.held(IdeaWinRate.Creator.FORGE, oos(100, 80, avg = 0.0), rule))
    }

    @Test fun tooFewTradesAreHeldAtEachCreatorsOwnMinimum() {
        assertEquals(StrategyForge.FWD_MIN_TRADES, IdeaWinRate.Creator.FORGE.minTrades)
        assertEquals(ShadowRules.PROMOTE_TRADES, IdeaWinRate.Creator.SHADOW.minTrades)
        assertEquals(StrategyLab.MIN_TRADES, IdeaWinRate.Creator.PINE.minTrades)
        // The lab's variants have a minimum of days, not trades: 30.
        assertEquals(IdeaWinRate.FALLBACK_MIN_TRADES, IdeaWinRate.Creator.VARIANT.minTrades)
        assertEquals(30, IdeaWinRate.FALLBACK_MIN_TRADES)
        for (c in IdeaWinRate.Creator.entries) {
            val n = c.minTrades
            val few = IdeaWinRate.held(c, oos(n - 1, n - 1), rule)
            assertNotNull(few, c.name)
            assertTrue(few.startsWith("held: only ${n - 1} trades, under the $n"), few)
            assertNull(IdeaWinRate.held(c, oos(n, n), rule), c.name)
        }
        assertEquals("held: only 1 trade, under the 30 your 65% rule needs", IdeaWinRate.held(IdeaWinRate.Creator.FORGE, oos(1, 1), rule))
        assertNotNull(IdeaWinRate.held(IdeaWinRate.Creator.FORGE, IdeaWinRate.fromNets(emptyList()), rule))
    }

    @Test fun anInSampleOrUncostedRecordIsHeld() {
        val inSample = IdeaWinRate.Evidence(500, 450, 800.0, IdeaWinRate.Sample.IN_SAMPLE, afterCosts = true)
        for (c in IdeaWinRate.Creator.entries) {
            val why = IdeaWinRate.held(c, inSample, rule)
            assertNotNull(why); assertTrue(why.startsWith("held: tested in-sample only (win rate 90% (500))"), why)
        }
        val gross = IdeaWinRate.Evidence(500, 450, 800.0, IdeaWinRate.Sample.OUT_OF_SAMPLE, afterCosts = false)
        assertTrue(IdeaWinRate.held(IdeaWinRate.Creator.SHADOW, gross, rule)!!.startsWith("held: not counted after charges and slippage"))
    }

    @Test fun theRuleUndoneIsTheOldBehaviourAndBossesNumberIsKeptInBounds() {
        // Off (the change log's undo): every idea goes by its creator's own gates, as before 28 Oct.
        val off = IdeaWinRate.Rule.OFF
        for (c in IdeaWinRate.Creator.entries) {
            assertNull(IdeaWinRate.held(c, oos(1000, 100, avg = -5.0), off))
            assertNull(IdeaWinRate.held(c, IdeaWinRate.Evidence(1, 0, 0.0, IdeaWinRate.Sample.IN_SAMPLE, false), off))
        }
        // Boss's own number: 70% holds 69.9%.
        assertTrue(IdeaWinRate.held(IdeaWinRate.Creator.FORGE, oos(1000, 699), IdeaWinRate.Rule(true, 70.0))!!.endsWith("below your 70% rule"))
        assertNull(IdeaWinRate.held(IdeaWinRate.Creator.FORGE, oos(1000, 700), IdeaWinRate.Rule(true, 70.0)))
        // A number outside the bounds (a bad file) never weakens it below 50% or makes it impossible above 90%.
        assertNotNull(IdeaWinRate.held(IdeaWinRate.Creator.FORGE, oos(1000, 499), IdeaWinRate.Rule(true, 10.0)))
        assertNull(IdeaWinRate.held(IdeaWinRate.Creator.FORGE, oos(1000, 900), IdeaWinRate.Rule(true, 200.0)))
        assertEquals("65%", IdeaWinRate.pct(65.0)); assertEquals("64.9%", IdeaWinRate.pct(64.91)); assertEquals("0%", IdeaWinRate.pct(0.0))
    }

    // ---- the forge: a proposal only at 65% -----------------------------------------------------------------------------------

    private val orb = StrategyForge.Candidate(7, day, "BANKNIFTY",
        ShadowSim.Rules(ShadowSim.Entry.ORB_BREAK, barMin = 1, orMin = 45, fromMin = 600, untilMin = 780, stopPct = 0.25, targetPct = 0.5, timeStopMin = 60),
        StrategyForge.ExitBlock.FIXED)

    /** [wins] winning trades of Rs 2,000 and [losses] losing ones of Rs 300, the losses spread out (a strong, steady record). */
    private fun record(wins: Int, losses: Int): List<Double> {
        val n = wins + losses
        return List(n) { i -> if ((i + 1) * losses / n != i * losses / n) -300.0 else 2000.0 }
    }

    private fun forward(fwd: List<Double>, why: String = "in forward test"): Pair<StrategyForge.Record, StrategyForge.Record> {
        val r = StrategyForge.Record(orb, StrategyForge.Stage.FORWARD, null, fwd, fwdDays = 24, why = why, since = day.minusDays(30))
        return r to r.copy(fwdDays = 25)
    }

    @Test fun theForgeProposesOnlyAt65AndHoldsAt64Point9() {
        // 39 of 60 won (65%): proposed, as before.
        val (r65, x65) = forward(record(39, 21))
        val (y65, e65) = StrategyForge.judgeForward(r65, x65, 1, day, rule)
        assertEquals(StrategyForge.Stage.PROPOSED, y65.stage, y65.why)
        assertTrue(e65 is StrategyForge.Event.Proposed)
        // 37 of 57 won (64.9%): every forward gate passes (the same gates without the rule propose it), but it is held.
        val (r649, x649) = forward(record(37, 20))
        assertEquals(StrategyForge.Stage.PROPOSED, StrategyForge.judgeForward(r649, x649, 1, day, IdeaWinRate.Rule.OFF).first.stage)
        val (y, e) = StrategyForge.judgeForward(r649, x649, 1, day, rule)
        assertEquals(StrategyForge.Stage.FORWARD, y.stage)
        assertTrue(y.why.startsWith("held: win rate 64.9% (57) below your 65% rule"), y.why)
        val held = e as StrategyForge.Event.Held
        assertEquals("held: win rate 64.9% (57) below your 65% rule", held.words)
        // Said once: the next day's held judgement adds no event.
        val (y2, e2) = StrategyForge.judgeForward(y, y.copy(fwdDays = 26), 1, day.plusDays(1), rule)
        assertEquals(StrategyForge.Stage.FORWARD, y2.stage); assertNull(e2)
        // At the end of its forward test still below the rule: retired, never proposed.
        val (y3, e3) = StrategyForge.judgeForward(y, y.copy(fwdDays = StrategyForge.FWD_MAX_DAYS), 1, day.plusDays(2), rule)
        assertEquals(StrategyForge.Stage.RETIRED, y3.stage)
        assertTrue("still held: win rate 64.9%" in y3.why, y3.why)
        assertTrue(e3 is StrategyForge.Event.Retired)
    }

    @Test fun theForgeHoldsAHighWinRateThatLosesAndAnIdeaThatFailsItsOwnGates() {
        // 70% won but losing after costs: its own gates already refuse it (average <= 0), and the rule would too.
        val loses = List(42) { 10.0 } + List(18) { -100.0 }
        val (r, x) = forward(loses)
        assertTrue(StrategyForge.judgeForward(r, x, 1, day, rule).first.stage != StrategyForge.Stage.PROPOSED)
        // 80% won but only 20 days: its own minimum of days still stands (the rule never replaces a gate).
        val strong = record(48, 12)
        val young = StrategyForge.Record(orb, StrategyForge.Stage.FORWARD, null, strong, fwdDays = 10)
        assertEquals(StrategyForge.Stage.FORWARD, StrategyForge.judgeForward(young, young.copy(fwdDays = 11), 1, day, rule).first.stage)
    }

    @Test fun theForgesManualApprovalAndItsPaperIdeasAreUntouched() {
        // A proposal already waiting (made before 28 Oct) is Boss's to approve, whatever its win rate.
        val waiting = StrategyForge.Record(orb, StrategyForge.Stage.PROPOSED, null, record(20, 20), fwdDays = 25)
        val book = StrategyForge.Book(tried = 1, records = listOf(waiting))
        assertEquals(StrategyForge.Stage.PAPER, StrategyForge.approve(book, orb.id, day).find(orb.id)!!.stage)
        // An idea Boss already put on paper keeps trading there with a poor record: the rule only blocks new ones.
        val paper = waiting.copy(stage = StrategyForge.Stage.PAPER)
        assertNull(GrammarPaper.hostRefusal(paper, emptyList()))
        val d = LabTestData.days(1, 42, start = day).single()
        val after = StrategyForge.forwardDay(StrategyForge.Book(tried = 1, records = listOf(paper)), d.date, mapOf("BANKNIFTY" to d), rule)
        assertEquals(StrategyForge.Stage.PAPER, after.book.find(orb.id)!!.stage)
        assertTrue(after.events.isEmpty())
    }

    // ---- the strategy lab's variants -----------------------------------------------------------------------------------------

    private val strong = List(30) { 600.0 + (it % 5) * 50 }

    private fun variant(trades: Int, wins: Int, diffs: List<Double> = strong, net: Double? = null, status: VariantLab.Status = VariantLab.Status.ALIVE,
                        key: String = "orb"): VariantLab.Variant {
        val s = VariantLab.strategy(key)!!
        val p = s.params.first()
        val v = s.defaults + (p.key to p.clamp(s.defaults.getValue(p.key) - p.step))
        return VariantLab.Variant("V1", key, v, s.defaults, day.minusDays(40), 1.0, status, diffs, net ?: diffs.sum(), 0.0,
            DiscoveryStats.alwaysValidP(diffs, 1, VariantLab.MIN_SD), DiscoveryStats.alwaysValidP(diffs, -1, VariantLab.MIN_SD), vTrades = trades, vWins = wins)
    }

    private fun lab(v: VariantLab.Variant, run: VariantLab.Run = VariantLab.Run.PAPER) =
        VariantLab.Book(runs = mapOf(v.strategy to run), variants = listOf(v), tried = 1, nextId = 2)

    @Test fun aVariantIsAppliedOnlyAt65AndHeldAt64Point9() {
        val ok = VariantLab.judge(lab(variant(60, 39)), day, rule = rule)
        assertEquals("orb", ok.events.filterIsInstance<VariantLab.Event.Applied>().single().change.strategy)
        val low = lab(variant(57, 37))
        // The lab's own gates pass: without the rule it is applied, as before 28 Oct.
        assertEquals(1, VariantLab.judge(low, day).events.filterIsInstance<VariantLab.Event.Applied>().size)
        val p = VariantLab.judge(low, day, rule = rule)
        assertTrue(p.events.none { it is VariantLab.Event.Applied || it is VariantLab.Event.Proposed })
        val h = p.events.filterIsInstance<VariantLab.Event.Held>().single()
        assertEquals("held: win rate 64.9% (57) below your 65% rule", h.words)
        val v = p.book.variants.single()
        assertEquals(VariantLab.Status.ALIVE, v.status); assertEquals(h.words, v.why)
        assertEquals(VariantLab.strategy("orb")!!.defaults, p.book.currentOf("orb"))
        assertTrue(p.book.tunes.isEmpty() && p.book.changes.isEmpty())
        // Said once.
        assertTrue(VariantLab.judge(p.book, day.plusDays(1), rule = rule).events.isEmpty())
        // Out of days, still below: retired, never applied.
        val old = VariantLab.judge(lab(variant(57, 37, diffs = List(VariantLab.MAX_N) { 600.0 + (it % 5) * 50 })), day, rule = rule)
        assertEquals(VariantLab.Status.RETIRED, old.book.variants.single().status)
        assertTrue(old.events.single() is VariantLab.Event.Retired)
    }

    @Test fun aLiveProposalIsHeldTooAndAVariantThatLosesOrHasFewTradesIsHeld() {
        val live = VariantLab.judge(lab(variant(57, 37), VariantLab.Run.LIVE), day, rule = rule)
        assertTrue(live.events.none { it is VariantLab.Event.Proposed }); assertTrue(live.events.single() is VariantLab.Event.Held)
        assertTrue(VariantLab.judge(lab(variant(60, 39), VariantLab.Run.LIVE), day, rule = rule).events.single() is VariantLab.Event.Proposed)
        // 70% won but its own trades lost after costs.
        val lost = VariantLab.judge(lab(variant(100, 70, net = -2_300.0)), day, rule = rule)
        assertTrue((lost.events.single() as VariantLab.Event.Held).words.startsWith("held: loses after costs"))
        // A variant from before 28 Oct (no trades counted yet) is held until it has 30.
        val fresh = VariantLab.judge(lab(variant(0, 0)), day, rule = rule)
        assertTrue((fresh.events.single() as VariantLab.Event.Held).words.startsWith("held: only 0 trades, under the 30"))
        // Not running: it waits, as before.
        assertTrue(VariantLab.judge(VariantLab.Book(variants = listOf(variant(57, 37)), tried = 1), day, rule = rule).events.single() is VariantLab.Event.Waiting)
    }

    @Test fun aVariantsTradesAreKeptAndAnOldLineReadsAsNone() {
        val b = lab(variant(57, 37))
        val back = VariantLab.decode(VariantLab.encode(b)).variants.single()
        assertEquals(57, back.vTrades); assertEquals(37, back.vWins)
        val old = VariantLab.encode(b).lines().joinToString("\n") { l -> if (l.startsWith("V|")) l.split('|').take(16).joinToString("|") else l }
        val v = VariantLab.decode(old).variants.single()
        assertEquals(0, v.vTrades); assertEquals(0, v.vWins)
        // A wins count over the trades (a bad file) never reads as more than 100%.
        assertEquals(100.0, IdeaWinRate.forVariant(10, 50, 1000.0).winPct, 1e-9)
    }

    @Test fun theLabCountsEachVariantsOwnTradesOnTheComparedDays() {
        // A day's pass through the lab: an alive variant's own trades and wins are added from the lab's replay.
        val days = LabTestData.days(3, 11, start = day.minusDays(3))
        val s = VariantLab.strategy("orb")!!
        val b0 = lab(variant(0, 0, diffs = emptyList()))
        val p = VariantLab.day(b0, days.last().date, mapOf("orb" to VariantLab.Run.PAPER), mapOf("BANKNIFTY" to days.last()), days.dropLast(1),
            DiscoveryStats.Rng(1), rule = rule)
        val v = p.book.variants.first { it.id == "V1" }
        val t = ArmLab.trades("orb", s.values(b0.variants.single().values), days.last(), days.dropLast(1))
        assertTrue(v.n > 0 && t.isNotEmpty(), "the day was compared and the variant traded")
        assertEquals(t.size, v.vTrades); assertEquals(t.count { it.net > 0 }, v.vWins)
    }

    @Test fun promotedVariantsAndTheirTunesAreUntouched() {
        val first = VariantLab.judge(lab(variant(60, 39)), day, rule = IdeaWinRate.Rule.OFF)
        val c = first.events.filterIsInstance<VariantLab.Event.Applied>().single().change
        // The rule on afterwards: the promotion already made stays, its tune trades from the next session.
        val again = VariantLab.judge(first.book, day.plusDays(1), rule = rule)
        assertEquals(first.book.tunes, again.book.tunes)
        assertEquals(c.to, VariantLab.valuesFor(again.book, "orb", false, day.plusDays(2)))
        assertEquals(VariantLab.ChangeStatus.APPLIED, again.book.change(c.id)!!.status)
    }

    // ---- shadow re-arms ------------------------------------------------------------------------------------------------------

    @Test fun aShadowIsPutToBossOnlyAt65() {
        // 74 of 114 won (64.9%) at Rs 100 / -50: it meets the old bar (60 trades, net above 0, PF 1.2) but is held.
        val low = List(74) { 100.0 } + List(40) { -50.0 }
        assertTrue(ShadowRules.promotionDue(ShadowRules.summarize(low)))
        assertEquals("held: win rate 64.9% (114) below your 65% rule", IdeaWinRate.held(IdeaWinRate.Creator.SHADOW, IdeaWinRate.forShadow(low), rule))
        val ok = List(78) { 100.0 } + List(42) { -50.0 }
        assertTrue(ShadowRules.promotionDue(ShadowRules.summarize(ok)))
        assertNull(IdeaWinRate.held(IdeaWinRate.Creator.SHADOW, IdeaWinRate.forShadow(ok), rule))
        // 59 trades at 80%: too few for the shadow's own 60.
        assertNotNull(IdeaWinRate.held(IdeaWinRate.Creator.SHADOW, IdeaWinRate.forShadow(List(47) { 100.0 } + List(12) { -50.0 }), rule))
    }

    // ---- Jarvis's own Pine scripts -------------------------------------------------------------------------------------------

    private fun pine(trades: Int, winRate: Double, rupees: Double? = 5_000.0) = StrategyLab.Result("Jarvis: Hammer NIFTY 15m", PatternKind.HAMMER, Market.NIFTY, 15, "//",
        day.minusYears(2), day, 500, trades, winRate, 900.0, 9.0, 300.0, 450.0, 450.0, rupees, if (rupees == null) 0 else trades, recommended = true, verdict = "")

    @Test fun jarvisOwnScriptsAreHeldTheirBacktestIsInSample() {
        // Two years with no holdout, the win rate in index points before charges: in-sample, so held even at 80%.
        val e = IdeaWinRate.forPine(pine(100, 80.0))
        assertEquals(IdeaWinRate.Sample.IN_SAMPLE, e.sample); assertEquals(80, e.wins); assertTrue(e.afterCosts)
        assertTrue(IdeaWinRate.held(IdeaWinRate.Creator.PINE, e, rule)!!.startsWith("held: tested in-sample only (win rate 80% (100))"))
        assertFalse(IdeaWinRate.forPine(pine(100, 80.0, rupees = null)).afterCosts)
        // The same creator with an out-of-sample record: held at 64.9%, allowed at 65%.
        assertNotNull(IdeaWinRate.held(IdeaWinRate.Creator.PINE, oos(57, 37), rule))
        assertNull(IdeaWinRate.held(IdeaWinRate.Creator.PINE, oos(40, 26), rule))
        // Rule off: the old behaviour (the two-year test alone).
        assertNull(IdeaWinRate.held(IdeaWinRate.Creator.PINE, e, IdeaWinRate.Rule.OFF))
    }

    // ---- the change timeline -------------------------------------------------------------------------------------------------

    @Test fun theRuleIsLoggedAsBossesOwnAndItsUndoRestoresTheOldBehaviour() {
        val on = AutoChange.ideaRule(true)
        assertEquals(AutoChange.Kind.IDEA_RULE, on.kind); assertEquals(AutoChange.IDEA_RULE_TARGET, on.target)
        assertEquals(AutoChange.Scope.PAPER, on.scope); assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on))
        assertTrue(AutoChange.bossOwn(on.kind)); assertFalse(AutoChange.never(on))
        assertTrue("65%" in on.reason && "not touched" in on.reason, on.reason)
        assertEquals("Automatic ideas: minimum win rate", on.what)
        val (book, entry) = AutoChange.record(AutoChange.Book(), on, AutoChange.Status.KEPT, 1L, day, by = "your choice", note = "28 Oct")
        val u = AutoChange.undo(book, entry.id) ?: throw AssertionError("no undo")
        assertEquals(AutoChange.SWITCHED_OFF, u.inverse.to); assertEquals(AutoChange.IDEA_RULE_TARGET, u.inverse.target)
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(u.inverse)); assertFalse(AutoChange.never(u.inverse))
        // Shown once in today's changes as Boss's, never undone by "undo everything today" (only its own tap).
        val items = ChangeTimeline.fromAutopilot(book, day)
        assertTrue(items.single().own); assertEquals(ChangeTimeline.UndoState.UNDOABLE, items.single().undo)
        assertTrue(ChangeTimeline.undoPlan(items).now.isEmpty())
        // A flip logged since: only the newest entry undoes.
        val (b2, _) = AutoChange.record(book, AutoChange.ideaRule(true), AutoChange.Status.KEPT, 2L, day, by = "your choice")
        assertTrue(AutoChange.superseded(b2, entry)); assertNull(AutoChange.undo(b2, entry.id))
    }

    @Test fun aHeldIdeaIsANoteInTheTimelineWithNothingToUndo() {
        val why = "held: win rate 64.9% (57) below your 65% rule"
        val c = AutoChange.ideaHeld(IdeaWinRate.Creator.PINE, "Jarvis: Hammer NIFTY 15m", "Jarvis: Hammer NIFTY 15m", why)
        assertEquals(AutoChange.Kind.IDEA_HELD, c.kind); assertEquals("idea:pine:jarvis_hammer_nifty_15m", c.target)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(c)); assertEquals(why, c.reason)
        val (book, e) = AutoChange.record(AutoChange.Book(), c, AutoChange.Status.SKIPPED, 5L, day, by = "your win-rate rule", note = why)
        assertNull(AutoChange.undo(book, e.id)); assertFalse(e.applied); assertEquals(0, book.trials)
        val items = ChangeTimeline.fromAutopilot(book, day)
        val i = items.single()
        assertEquals(ChangeTimeline.UndoState.HELD, i.undo); assertFalse(i.pending); assertFalse(i.own)
        assertEquals(why, i.why)
        val plan = ChangeTimeline.undoPlan(items)
        assertTrue(plan.now.isEmpty() && plan.needPin.isEmpty())
        val said = ChangeTimeline.say(items) { "10:00" }
        assertTrue("0 automatic changes; 1 automatic idea held by your win-rate rule" in said, said)
        // Other skipped changes stay out of today's changes, as before.
        val (b2, _) = AutoChange.record(book, AutoChange.ideaRule(false), AutoChange.Status.SKIPPED, 6L, day)
        assertEquals(1, ChangeTimeline.fromAutopilot(b2, day).size)
        // The Strategy lab's lines.
        assertEquals(listOf(e.id), IdeaWinRate.heldOf(b2).map { it.id })
        assertEquals("Jarvis's Pine script Jarvis: Hammer NIFTY 15m: $why", IdeaWinRate.heldLine(e))
        assertEquals("Held by your win-rate rule: 1 automatic idea (notes only, nothing made)", IdeaWinRate.heldSummary(listOf(e)))
        assertNull(IdeaWinRate.heldSummary(emptyList()))
    }

    // ---- Boss's setting --------------------------------------------------------------------------------------------------------

    @Test fun changingTheMinimumNeedsThePinUpOrDown() {
        assertEquals("Automatic ideas: minimum win rate 65%", IdeaWinRate.settingLine(IdeaWinRate.Rule.DEFAULT))
        assertTrue(IdeaWinRate.settingLine(IdeaWinRate.Rule.OFF).startsWith("Automatic ideas: minimum win rate off"))
        for (next in listOf(55.0, 60.0, 70.0, 80.0)) {
            val (no, said) = IdeaWinRate.change(rule, next, pinConfirmed = false)
            assertNull(no); assertEquals(IdeaWinRate.PIN_LINE, said)
            val (yes, ok) = IdeaWinRate.change(rule, next, pinConfirmed = true)
            assertEquals(next, yes!!.minPct); assertTrue(yes.on); assertTrue(IdeaWinRate.pct(next) in ok, ok)
        }
        for (bad in listOf(40.0, 95.0, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(IdeaWinRate.change(rule, bad, pinConfirmed = true).first)
        assertTrue(IdeaWinRate.CHOICES.contains(IdeaWinRate.DEFAULT_PCT))
        assertTrue(IdeaWinRate.CHOICES.all { it in IdeaWinRate.MIN_CHOICE..IdeaWinRate.MAX_CHOICE })
    }

    // ---- every automatic creator in the app asks the rule (the app's source beside this module) -------------------------------

    @Test fun everyAutomaticCreatorInTheAppAsksTheRule() {
        val app = File("../app/src/main/java/com/optionslab/app")
        fun src(rel: String) = File(app, rel).readText()
        val runner = src("data/VariantRunner.kt")
        assertTrue("val rule = IdeaRuleHost.rule()" in runner)
        assertTrue(Regex("VariantLab\\.day\\([^\\n]*\\n[^\\n]*rule = rule\\)").containsMatchIn(runner), "the lab's day gets the rule")
        assertTrue(Regex("StrategyForge\\.forwardDay\\([^\\n]*rule = rule\\)").containsMatchIn(runner), "the forge's forward day gets the rule")
        assertTrue("is VariantLab.Event.Held -> " in runner && "is StrategyForge.Event.Held -> IdeaRuleHost.held(" in runner)
        val bots = src("ira/IraBots.kt")
        val promo = bots.substringAfter("suspend fun shadowPromotions()").substringBefore("\n    }\n")
        assertTrue(promo.indexOf("heldShadow(row, rule)") in 0 until promo.indexOf("ShadowArms.asked(v.id)") && "continue" in promo, "held before it is asked")
        val hub = src("ira/IraHub.kt")
        val auto = hub.substringAfter("private suspend fun autoLab()").substringBefore("\n    }\n")
        assertTrue(auto.indexOf("heldPine(r)") in 0 until auto.indexOf("propose(r,"), "held before it is proposed or added")
        // Boss's own approval of a script never asks the rule.
        val approve = hub.substringAfter("private suspend fun approveOnce(").substringBefore("\n    }\n")
        assertFalse("IdeaWinRate" in approve || "IdeaRuleHost" in approve)
    }
}
