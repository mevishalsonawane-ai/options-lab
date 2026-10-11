package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Today's changes across the app ([ChangeTimeline]): every source read, newest-first undo, live raises left for the PIN. */
class ChangeTimelineTest {
    @Test fun anUndoTheAgentHoldsForThePinIsListedAsNeedingIt() {
        // Review G5-2: a paper mute whose undo the app holds for the PIN (the manager acts on live): "needs your PIN".
        val day = LocalDate.of(2026, 10, 12)
        val c = AutoChange.Change(AutoChange.Kind.SPECIALIST_MUTE, "spec:pine:oi", AutoChange.COUNTS, AutoChange.MUTED, "hurt",
            AutoChange.Evidence(40, -4_000.0, AgentLessons.Confidence.STRONG, -1), AutoChange.Risk.LOWER, AutoChange.Scope.PAPER, what = "the OI specialist on Pine (paper)")
        val b = AutoChange.record(AutoChange.Book(), c, AutoChange.Status.PROBATION, 1L, day).first
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, ChangeTimeline.fromAutopilot(b, day).single().undo)
        val held = ChangeTimeline.fromAutopilot(b, day) { true }.single()
        assertEquals(ChangeTimeline.UndoState.NEEDS_PIN, held.undo)
        val p = ChangeTimeline.undoPlan(listOf(held))
        assertTrue(p.now.isEmpty()); assertEquals(listOf(held), p.needPin)
    }

    private val d0: LocalDate = LocalDate.of(2026, 10, 12)
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int = 0, d: LocalDate = d0) = d.atTime(h, m).atZone(ist).toInstant().toEpochMilli()
    private val ev = AutoChange.Evidence(20, -2000.0, AgentLessons.Confidence.STRONG, -1)

    private fun mute(scope: AutoChange.Scope, id: String) = AutoChange.Change(AutoChange.Kind.SPECIALIST_MUTE, "spec:solo:$id", AutoChange.COUNTS, AutoChange.MUTED,
        "it hurt", ev, AutoChange.Risk.LOWER, scope, expect = -1, effect = -1, what = "the $id specialist")

    private fun book(): AutoChange.Book {
        var b = AutoChange.Book()
        b = AutoChange.record(b, mute(AutoChange.Scope.PAPER, "oi"), AutoChange.Status.PROBATION, at(15, 50), d0).first
        // A live mute: its undo un-mutes on live, which adds live risk - only with the PIN.
        b = AutoChange.record(b, mute(AutoChange.Scope.LIVE, "vix"), AutoChange.Status.PROBATION, at(15, 51), d0).first
        b = AutoChange.record(b, mute(AutoChange.Scope.SHADOW, "flow"), AutoChange.Status.KEPT, at(15, 52), d0, by = "specialists' tuning").first
        b = AutoChange.record(b, mute(AutoChange.Scope.PAPER, "old"), AutoChange.Status.PROBATION, at(15, 0, d0.minusDays(1)), d0.minusDays(1)).first
        b = AutoChange.record(b, mute(AutoChange.Scope.PAPER, "asked"), AutoChange.Status.OFFERED, at(15, 53), d0).first
        return b
    }

    @Test fun everySourceIsReadWithItsAgentAndUndoState() {
        val ap = ChangeTimeline.fromAutopilot(book(), d0)
        assertEquals(4, ap.size, "yesterday's change is not today's")
        assertEquals(ChangeTimeline.UndoState.NEEDS_PIN, ap.single { it.what.startsWith("the vix") }.undo)
        assertEquals(ChangeTimeline.Source.SPECIALISTS, ap.single { it.what.startsWith("the flow") }.source)
        assertEquals(ChangeTimeline.UndoState.ASKED, ap.single { it.what.startsWith("the asked") }.undo)
        val lab = ChangeTimeline.fromLab(listOf(
            ChangeTimeline.LabChange("C1", "ORB Fresh", "stop 15% → 12%", "won", auto = true, live = false, status = "APPLIED", day = d0, atMs = at(16, 40)),
            ChangeTimeline.LabChange("C2", "Liquidity", "x", "won", auto = false, live = true, status = "OFFERED", day = d0, atMs = at(16, 40))), d0)
        assertEquals(listOf("lab:C1"), lab.map { it.id })
        val health = ChangeTimeline.fromHealth(listOf(ChangeTimeline.HealthChange("brain.consensus", "the Brain card's refresh slowed to 30 s", at(11), false, true)))
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, health.single().undo)
        val fs = listOf(
            Findings.Finding("Self-review", Findings.Kind.OTHER, Findings.ALL, atMs = at(15, 46), words = "ORB parked to paper: drifting", seq = 1),
            Findings.Finding("Autopilot", Findings.Kind.OTHER, Findings.ALL, atMs = at(15, 50), words = "Autopilot: x 1 → 2", seq = 2),
            Findings.Finding("Brain", Findings.Kind.OTHER, Findings.ALL, atMs = at(10), words = "the market is busy", seq = 3))
        val found = ChangeTimeline.fromFindings(fs, d0)
        assertEquals(listOf("Self-review"), found.map { it.agent }, "Autopilot's own findings are its book's; a plain note is no change")
        val all = ChangeTimeline.merge(ap, lab, health, found)
        assertEquals(all.sortedBy { it.atMs }, all)
    }

    @Test fun undoEverythingGoesNewestFirstAndLeavesLiveRaisesForThePin() {
        val items = ChangeTimeline.merge(ChangeTimeline.fromAutopilot(book(), d0),
            ChangeTimeline.fromHealth(listOf(ChangeTimeline.HealthChange("moves.flush", "slowed", at(11), false, true))))
        val p = ChangeTimeline.undoPlan(items)
        assertEquals(p.now.sortedByDescending { it.atMs }, p.now, "newest first")
        assertTrue(p.now.none { it.undo == ChangeTimeline.UndoState.NEEDS_PIN })
        assertEquals(listOf("the vix specialist: counts → muted"), p.needPin.map { it.what })
        assertTrue(p.now.none { it.undo == ChangeTimeline.UndoState.ASKED }, "an asked change has nothing to undo")
        val withPin = ChangeTimeline.undoPlan(items, pinConfirmed = true)
        assertTrue(withPin.needPin.isEmpty())
        assertTrue(withPin.now.any { it.what.startsWith("the vix") })
    }

    @Test fun itSaysWhatChangedAndWhatTheUndoDid() {
        val items = ChangeTimeline.fromAutopilot(book(), d0)
        val said = ChangeTimeline.say(items) { "15:5x" }
        assertTrue(said.startsWith("Today 3 automatic changes"), said)
        assertTrue(said.contains("only with your PIN"), said)
        assertTrue(ChangeTimeline.say(emptyList()) { "" }.contains("Nothing changed"))
        val p = ChangeTimeline.undoPlan(items)
        val out = ChangeTimeline.sayUndone(p.now.map { ChangeTimeline.Done(it, "ok") }, p.needPin, p.left)
        assertTrue(out.contains("Needs your PIN"), out)
        assertTrue(ChangeTimeline.askText(p).contains("only with your PIN"))
    }
}
