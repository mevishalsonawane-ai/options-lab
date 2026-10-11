package com.optionslab.ira

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The line-by-line review's fixes in the strategy lab (G7) and in risk, regime, history and Jarvis's routing (G9), one test a
 * finding: each failed before its fix. Fixed dates only (never the real day).
 */
class LabRiskReviewFixesTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val strong = List(30) { 600.0 + (it % 5) * 50 }

    /** A variant of [key] moving parameter [param] (default: the first) by -1 step, with [diffs] as its record. */
    private fun variant(id: String, key: String, diffs: List<Double>, status: VariantLab.Status = VariantLab.Status.ALIVE,
                        values: Map<String, Double>? = null): VariantLab.Variant {
        val s = VariantLab.strategy(key)!!
        val p = s.params.first()
        val v = values ?: (s.defaults + (p.key to p.clamp(s.defaults.getValue(p.key) - p.step)))
        return VariantLab.Variant(id, key, v, s.defaults, day.minusDays(40), 1.0, status, diffs, diffs.sumOf { it.coerceAtLeast(0.0) },
            -diffs.sumOf { it.coerceAtMost(0.0) }, DiscoveryStats.alwaysValidP(diffs, 1, VariantLab.MIN_SD), DiscoveryStats.alwaysValidP(diffs, -1, VariantLab.MIN_SD))
    }

    private fun proposedAndAccepted(key: String, values: Map<String, Double>? = null): Pair<VariantLab.Book, VariantLab.Change> {
        val b = VariantLab.Book(runs = mapOf(key to VariantLab.Run.LIVE), variants = listOf(variant("V1", key, strong, values = values)), tried = 1, nextId = 2)
        val p = VariantLab.judge(b, day)
        val c = p.events.filterIsInstance<VariantLab.Event.Proposed>().single().change
        return VariantLab.accept(p.book, c.id, pinConfirmed = true, from = day.plusDays(1))!! to c
    }

    // ---- G7: the strategy lab ----------------------------------------------------------------------------------------------

    @Test fun g7_2_aDayIsSettledOnlyOnceEveryIndexThatKeepsDaysHasIt() {
        val d = day
        val week = (1L..5L).map { d.minusDays(it) }
        // BANKNIFTY stored first, NIFTY not yet: wait.
        assertFalse(VariantLab.readyToSettle(d, mapOf("BANKNIFTY" to week + d, "NIFTY" to week), d))
        // Both: settle. An index the store does not keep (no day in the week before) never holds it up.
        assertTrue(VariantLab.readyToSettle(d, mapOf("BANKNIFTY" to week + d, "NIFTY" to week + d, "FINNIFTY" to emptyList()), d))
        // Nothing of the day: wait. Two days on, what is there is settled (a missing index is given up).
        assertFalse(VariantLab.readyToSettle(d, mapOf("BANKNIFTY" to week, "NIFTY" to week), d.plusDays(5)))
        assertTrue(VariantLab.readyToSettle(d, mapOf("BANKNIFTY" to week + d, "NIFTY" to week), d.plusDays(2)))
    }

    @Test fun g7_3_theSafetyNetRetiresTheSiblingsMeasuredAgainstTheRulesItThrewOut() {
        val (yes, _) = proposedAndAccepted("orb")
        val sibling = variant("V9", "orb", List(25) { 400.0 }, values = VariantLab.strategy("orb")!!.defaults + ("targetPts" to 70.0))
        val b = yes.copy(variants = yes.variants + sibling, scales = yes.scales + ("orb" to 0.25))
        val from = day.plusDays(1)
        val net = VariantLab.safety(b, from, mapOf("orb" to VariantLab.Run.LIVE), from.plusDays(1)) { _, _ -> -1e6 }
        assertTrue(net.events.single() is VariantLab.Event.Reverted)
        val v9 = net.book.variants.first { it.id == "V9" }
        assertEquals(VariantLab.Status.RETIRED, v9.status)
        assertTrue("safety net" in v9.why, v9.why)
        assertEquals(b.retiredCount + 1, net.book.retiredCount)
        assertEquals(1.0, net.book.scales.getValue("orb"), 1e-12)
        // So the next judgement cannot promote it on its lead over the thrown-out rules.
        val next = VariantLab.judge(net.book.copy(runs = mapOf("orb" to VariantLab.Run.PAPER)), from.plusDays(1))
        assertTrue(next.events.none { it is VariantLab.Event.Applied || it is VariantLab.Event.Proposed })
    }

    @Test fun g7_4_aSafetyRevertNeverDelaysOrDropsALiveExitAndItsUndoNeedsThePin() {
        val s = VariantLab.strategy("liquidity")!!
        // Liquidity's accepted live variant: time stop after 10 minutes (default 20), a profit lock at 30% (default none).
        val values = s.defaults + (ArmParams.TS to 10.0) + (ArmParams.LOCK to 0.30)
        val (yes, c) = proposedAndAccepted("liquidity", values)
        assertEquals(10.0, c.to.getValue(ArmParams.TS), 1e-12)
        // Undoing it would delay the time stop and drop the lock on live: the PIN.
        assertFalse(VariantLab.restoresDefault(yes, c.id))
        val from = day.plusDays(1)
        val net = VariantLab.safety(yes, from, mapOf("liquidity" to VariantLab.Run.LIVE), from.plusDays(1)) { _, _ -> -1e6 }
        val r = net.events.filterIsInstance<VariantLab.Event.Reverted>().single()
        assertTrue(r.liveStopKept)
        val live = VariantLab.valuesFor(net.book, "liquidity", true, from.plusDays(1))!!
        assertEquals(10.0, live.getValue(ArmParams.TS), 1e-12)
        assertEquals(0.30, live.getValue(ArmParams.LOCK), 1e-12)
        // Paper goes all the way back.
        assertNull(VariantLab.valuesFor(net.book, "liquidity", false, from.plusDays(1)))
        // Which way each exit loosens.
        assertTrue(VariantLab.widensStop(s, s.defaults + (ArmParams.TS to 10.0), s.defaults))
        assertTrue(VariantLab.widensStop(s, s.defaults + (ArmParams.TSG to 0.08), s.defaults))
        assertTrue(VariantLab.widensStop(s, s.defaults + (ArmParams.LOCK to 0.15), s.defaults))
        assertFalse(VariantLab.widensStop(s, s.defaults, s.defaults + (ArmParams.LOCK to 0.15) + (ArmParams.TS to 10.0)))
    }

    @Test fun g7_5_anOlderPaperChangeALaterOneReplacedCannotBeUndone() {
        val b0 = VariantLab.Book(runs = mapOf("orb" to VariantLab.Run.PAPER), variants = listOf(variant("V1", "orb", strong)), tried = 1, nextId = 2)
        val p1 = VariantLab.judge(b0, day)
        val c1 = p1.events.filterIsInstance<VariantLab.Event.Applied>().single().change
        assertTrue(VariantLab.undoable(p1.book, c1.id))
        // A later paper promotion of the same strategy.
        val s = VariantLab.strategy("orb")!!
        val v2 = variant("V2", "orb", strong, values = c1.to + ("targetPts" to 70.0)).copy(base = c1.to)
        val p2 = VariantLab.judge(p1.book.copy(variants = p1.book.variants + v2, tried = 2), day.plusDays(2))
        val c2 = p2.events.filterIsInstance<VariantLab.Event.Applied>().single().change
        assertFalse(VariantLab.undoable(p2.book, c1.id))
        assertNull(VariantLab.undo(p2.book, c1.id, day.plusDays(5)), "the replaced change brings stale rules back")
        // The newest one still undoes; then the older one is the one in force again.
        val back = VariantLab.undo(p2.book, c2.id, day.plusDays(5))!!
        assertEquals(c1.to, back.currentOf("orb"))
        assertTrue(VariantLab.undoable(back, c1.id))
        assertNotEquals(s.defaults, back.currentOf("orb"))
    }

    @Test fun g7_7_theIndexMovesStepsAreSaid() {
        val move = VariantLab.strategy("vix_div")!!.param(ArmParams.MOVE)!!
        assertNotEquals(move.say(0.00175), move.say(0.002))
        assertNotEquals(move.say(0.002), move.say(0.00225))
    }

    @Test fun g7_8_aSafetyRevertIsNotSaidAsAPaperChangeToUndo() {
        val (yes, _) = proposedAndAccepted("orb")
        val from = day.plusDays(1)
        val net = VariantLab.safety(yes, from, mapOf("orb" to VariantLab.Run.LIVE), from.plusDays(1)) { _, _ -> -1e6 }
        val said = LabTalk.improving(net.book)
        assertFalse("Changed on paper by itself" in said, said)
        assertTrue("Put back to its defaults by the safety net: ORB" in said, said)
    }

    @Test fun g7_9_aWithheldPromotionLeavesItsSiblingsShadowing() {
        val sib = variant("V2", "orb", List(5) { 0.0 }, values = VariantLab.strategy("orb")!!.defaults + ("targetPts" to 70.0))
        val b0 = VariantLab.Book(runs = mapOf("orb" to VariantLab.Run.PAPER), variants = listOf(variant("V1", "orb", strong), sib), tried = 2, nextId = 3)
        val pass = VariantLab.judge(b0, day)
        val c = pass.events.filterIsInstance<VariantLab.Event.Applied>().single().change
        assertEquals(VariantLab.Status.RETIRED, pass.book.variants.first { it.id == "V2" }.status)
        val held = VariantLab.withhold(pass.book, c.id)!!
        assertEquals(VariantLab.Status.ALIVE, held.variants.first { it.id == "V2" }.status)
        assertEquals(VariantLab.Status.ALIVE, held.variants.first { it.id == "V1" }.status)
        assertEquals(b0.retiredCount, held.retiredCount)
        assertTrue("withheld" in held.change(c.id)!!.why)
        assertNull(VariantLab.valuesFor(held, "orb", false, day.plusDays(1)))
    }

    @Test fun g7_10_aRetiredOrFailedNeighbourIsNeverSpawnedAgain() {
        val hist = LabTestData.days(12, 9)
        val b0 = VariantLab.Book(runs = mapOf("orb_sweep" to VariantLab.Run.PAPER))
        val first = VariantLab.spawn(b0, "orb_sweep", day, hist, DiscoveryStats.Rng(5))
        val made = first.variants.map { it.values }
        assertTrue(made.isNotEmpty())
        // All of them retired; the same seed again must not bring any back.
        val dead = first.copy(variants = first.variants.map { it.copy(status = VariantLab.Status.RETIRED) })
        val again = VariantLab.spawn(dead, "orb_sweep", day.plusDays(1), hist, DiscoveryStats.Rng(5))
        val fresh = again.variants.drop(first.variants.size).map { it.values }
        assertTrue(fresh.none { f -> made.any { m -> m == f } }, "a retired neighbour came back")
    }

    @Test fun g7_15_theNoProgressExitFiresAcrossAGapInTheOptionsMinutes() {
        val mins = (600..700).toList()
        val ix = ShadowSim.Series(mins.toIntArray(), DoubleArray(mins.size) { 50_000.0 }, DoubleArray(mins.size) { 50_010.0 },
            DoubleArray(mins.size) { 49_990.0 }, DoubleArray(mins.size) { 50_000.0 })
        // The option has no bar at entry + 10 minutes (609): a thin strike.
        val om = mins.filter { it != 609 }
        val flat = { v: Double -> DoubleArray(om.size) { v } }
        val leg = ShadowSim.Leg(50_000.0, 1, ShadowSim.Series(om.toIntArray(), flat(100.0), flat(100.5), flat(99.5), flat(100.0)))
        val d = ShadowSim.Day(day, "BANKNIFTY", 30, false, ix, listOf(leg))
        val r = ShadowSim.Rules(ShadowSim.Entry.ORB_BREAK, stopPct = 0.5, targetPct = 1.0, noProgressMin = 10)
        val t = ShadowSim.enter(r, d, 1, 600, 50_000.0)!!
        assertEquals("NO_PROGRESS", t.why)
        assertEquals(610, t.exitMin)
    }

    @Test fun g9_6_anEventDayIsNotEvidenceButTheSafetyNetStillRuns() {
        val hist = LabTestData.days(20, 5)
        val today = LabTestData.days(1, 6, start = LocalDate.of(2025, 3, 3)).single()
        val alive = variant("V1", "orb", List(5) { 100.0 })
        val b = VariantLab.Book(runs = mapOf("orb" to VariantLab.Run.PAPER), variants = listOf(alive), tried = 1, nextId = 2)
        val counted = VariantLab.day(b, today.date, b.runs, mapOf("BANKNIFTY" to today), hist, DiscoveryStats.Rng(1))
        val skipped = VariantLab.day(b, today.date, b.runs, mapOf("BANKNIFTY" to today), hist, DiscoveryStats.Rng(1), evidence = false)
        val v1 = { p: VariantLab.Pass -> p.book.variants.first { it.id == "V1" } }
        assertEquals(alive.diffs, v1(skipped).diffs, "an event day is never a compared day")
        assertEquals(today.date, skipped.book.lastDay)
        // (The ordinary day compares when either side traded.)
        assertTrue(v1(counted).diffs.size >= alive.diffs.size)
        // The safety net is not skipped on an event day.
        val (yes, _) = proposedAndAccepted("orb")
        val from = day.plusDays(1)
        val ev = VariantLab.day(yes, from, mapOf("orb" to VariantLab.Run.LIVE), emptyMap(), hist, DiscoveryStats.Rng(1),
            actual = mapOf("orb" to -1e6), evidence = false)
        assertTrue(ev.events.any { it is VariantLab.Event.Reverted })
        // The learning filter for a record's entry time: its IST day.
        val cal = EventCalendar.calendar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), { false },
            listOf(EventCalendar.Entry(day, EventCalendar.Kind.OWNER, "RBI policy", "added by you")))
        try {
            LearningFilter.use(cal)
            val ms = day.atTime(0, 30).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()   // still 11 Oct in UTC
            assertFalse(LearningFilter.includeMs(ms))
            assertFalse(LearningFilter.include(day))
            assertEquals(LearningFilter.include(day.minusDays(1)), LearningFilter.includeMs(ms - 24 * 3_600_000L))
        } finally { LearningFilter.resetForTest() }
    }

    // ---- G9: risk, regime, history, routing --------------------------------------------------------------------------------

    @Test fun g9_1_aRebuildBeforeTheLessonsAreReadKeepsEveryOverride() {
        val o = RegimeParams.Override("arm:liquidity", RegimeParams.Regime.BUSY, mapOf(RegimeParams.SIZE_KEY to 0.0), "strat:liquidity", 0.04,
            "Liquidity: sit out busy days", shadowDays = (1L..10L).map { day.minusDays(it) }, liveApproved = true)
        val b = RegimeParams.Book(listOf(o))
        assertEquals(b, RegimeParams.refresh(b, emptyList()))
    }

    @Test fun g9_2_aRegimeSettingOnPaperIsListedForBossesPin() {
        val shadow = RegimeParams.Override("arm:orb", RegimeParams.Regime.BUSY, mapOf(RegimeParams.SIZE_KEY to 0.0), "strat:orb", 0.04, "ORB: sit out busy days")
        val paper = shadow.copy(target = "arm:liquidity", shadowDays = (1L..10L).map { day.minusDays(it) })
        val live = paper.copy(target = "arm:hero", liveApproved = true)
        val b = RegimeParams.Book(listOf(shadow, paper, live))
        assertEquals(listOf(paper), RegimeParams.readyForLive(b))
        assertEquals(RegimeParams.Stage.LIVE, RegimeParams.approveLive(b, paper.key).overrides.first { it.key == paper.key }.stage)
    }

    @Test fun g9_4_theBotsResultsAreNotSaidOnALockedPhone() {
        for (k in listOf(SelfTalk.Kind.SCORECARD, SelfTalk.Kind.TIMELINE)) {
            assertEquals(SelfTalk.LOCKED_BOTS, SelfTalk.lockedRefusal(k, locked = true))
            assertNull(SelfTalk.lockedRefusal(k, locked = false))
        }
        assertEquals(SelfTalk.LOCKED, SelfTalk.lockedRefusal(SelfTalk.Kind.HABITS, true))
        assertNull(SelfTalk.lockedRefusal(SelfTalk.Kind.STUDY, true))
        for (k in LabTalk.Kind.entries)
            assertEquals(SelfTalk.LOCKED_BOTS, LabTalk.answer(LabTalk.Ask(k, "F1"), StrategyForge.Book(), VariantLab.Book(), locked = true))
        assertNotEquals(SelfTalk.LOCKED_BOTS, LabTalk.answer(LabTalk.Ask(LabTalk.Kind.TRIED), StrategyForge.Book(), VariantLab.Book()))
    }

    @Test fun g9_5_anOptionsIvIsWorkedFromItsPriceAtTheIndexsLastMinute() {
        val t0 = 1_000_000L
        val bars = (0 until 10).map { HistoryCodec.Bar(t0 + 60L * it, 1.0, 1.0, 1.0, 100.0 + it, 0, 0) }
        assertEquals(103.0, HistoryKeep.pairedBar(bars, t0 + 180)!!.close, 1e-12)
        assertEquals(103.0, HistoryKeep.pairedBar(bars, t0 + 200)!!.close, 1e-12)
        assertNull(HistoryKeep.pairedBar(bars, t0 - 1))
    }

    @Test fun g9_7_aPieceWithFailedReadsIsTriedAgainBeforeItIsKept() {
        assertTrue(HistoryKeep.keepPartial(0, 1))
        assertFalse(HistoryKeep.keepPartial(1, 1))
        assertFalse(HistoryKeep.keepPartial(2, HistoryKeep.PARTIAL_TRIES - 1))
        assertTrue(HistoryKeep.keepPartial(2, HistoryKeep.PARTIAL_TRIES))
    }

    @Test fun g9_8_aPastDaysNearestExpiryThatHasExpiredSinceIsNotReplacedByLaterOnes() {
        // Wednesday 14 Oct; weekly Tuesday expiries listed from 20 Oct (13 Oct's is gone).
        val today = LocalDate.of(2026, 10, 14)
        val listed = listOf(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 24))
        assertTrue(HistoryKeep.missesNearest(LocalDate.of(2026, 10, 12), today, listed))
        assertTrue(HistoryKeep.missesNearest(LocalDate.of(2026, 10, 13), today, listed))
        // Today, and a past day after the last expiry: its nearest is still listed.
        assertFalse(HistoryKeep.missesNearest(today, today, listed))
        assertFalse(HistoryKeep.missesNearest(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 16), listed))
    }

    private val vaults = ArrayList<File>()
    @AfterTest fun cleanUp() { vaults.forEach { it.deleteRecursively() } }

    @Test fun g9_9_theCapSkipsTheDayJustWrittenAndDropsTheNextOldest() {
        val v = HistoryVault(Files.createTempDirectory("vault").toFile().also { vaults += it }, capMb = 100)
        v.capBytesForTest(70 * 1024L)
        val days = (0 until 3).map { day.minusDays(10L - it) }
        val chunk = ByteArray(30 * 1024)
        for (d in days) { File(v.root, d.toString()).mkdirs(); File(File(v.root, d.toString()), "idx.ihv").writeBytes(chunk) }
        // The oldest is the catch-up day just written: kept; the next oldest goes, and the vault fits.
        assertEquals(listOf(days[1]), v.enforceCap(keep = days[0]))
        assertEquals(listOf(days[0], days[2]), v.days())
        assertTrue(v.usageBytes() <= v.capBytes)
    }

    @Test fun g9_10_whyWasAnArmBlockedNeverAnswersWithAnotherArmsBlock() {
        val b = PortfolioGuard.Block("liquidity:BANKNIFTY", 1, false, 0L, listOf("orb_fresh"), 9_000.0, 5_000.0, "Liquidity BANKNIFTY was blocked: correlated arms")
        val said = PortfolioRisk.answerBlocked("why was hero blocked", listOf(b))
        assertTrue("has not blocked Hero" in said && "it blocked Liquidity BANKNIFTY" in said, said)
        assertFalse(said.startsWith("Boss, Liquidity BANKNIFTY was blocked"), said)
        assertEquals("Boss, Liquidity BANKNIFTY was blocked: correlated arms.", PortfolioRisk.answerBlocked("why was liquidity blocked", listOf(b)))
    }
}
