package com.optionslab.ira

import com.optionslab.engine.orb.OrbParams
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.ReentryCap
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R14's re-entry cap through the strategy lab: on paper by itself (undoable), proposed for live (PIN), never loosened by itself. */
class ReentryCapLabTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val from = day.plusDays(1)

    @Test fun theParameterReachesTheArms() {
        assertEquals(1, ArmParams.orb("orb", mapOf(ArmParams.SIDE_CAP to 1.0)).sideCap)
        assertEquals(2, ArmParams.orb("orb_fresh", mapOf(ArmParams.SIDE_CAP to 2.4)).sideCap)
        assertEquals(0, ArmParams.orb("orb", mapOf(ArmParams.SIDE_CAP to -3.0)).sideCap, "never below none")
        assertEquals(0, ArmParams.orb("orb_sweep", mapOf(ArmParams.SIDE_CAP to 1.0)).sideCap, "Sweep has its own 2-a-day limit")
        assertEquals(0, ArmParams.orb("range_fade", mapOf(ArmParams.SIDE_CAP to 1.0)).sideCap)
        assertEquals(0, ArmParams.orb("orb", mapOf(ArmParams.SIDE_CAP to Double.NaN)).sideCap)
        assertEquals(OrbParams.ORB, ArmParams.orb("orb", ArmParams.defaults("orb")))
        val p = VariantLab.strategy("orb")!!.param(ArmParams.SIDE_CAP)!!
        assertEquals("no cap", p.say(0.0)); assertEquals("1 a side a day", p.say(1.0))
        assertEquals(3.0, p.clamp(7.0)); assertEquals(1.0, p.clamp(1.3))
        assertNull(VariantLab.strategy("orb_sweep")!!.param(ArmParams.SIDE_CAP))
    }

    @Test fun liftingTheCapIsARiskRaise() {
        val s = VariantLab.strategy("orb")!!
        val capped = s.defaults + (ArmParams.SIDE_CAP to 1.0)
        assertTrue(VariantLab.widensStop(s, capped, s.defaults), "no cap after a cap: more entries")
        assertTrue(VariantLab.widensStop(s, capped, s.defaults + (ArmParams.SIDE_CAP to 2.0)))
        assertFalse(VariantLab.widensStop(s, s.defaults, capped), "a cap only lowers risk")
        // The safety net's live revert keeps the live cap (a defaults' "no cap" would add risk).
        assertEquals(1.0, VariantLab.capStops(s, s.defaults, capped).getValue(ArmParams.SIDE_CAP))
    }

    @Test fun paperByItselfOnceAndUndoable() {
        val p = VariantLab.research(VariantLab.Book(runs = mapOf("orb" to VariantLab.Run.PAPER)), day, from)
        val applied = p.events.filterIsInstance<VariantLab.Event.Applied>().map { it.change }
        assertEquals(listOf("R14-orb", "R14-orb_fresh"), applied.map { it.id })
        assertTrue(applied.all { it.kind == VariantLab.ChangeKind.RESEARCH && it.status == VariantLab.ChangeStatus.APPLIED && it.effective == from })
        assertTrue(p.events.none { it is VariantLab.Event.Proposed }, "not live: nothing to propose")
        val b = p.book
        assertEquals(1.0, VariantLab.valuesFor(b, "orb", false, from)!!.getValue(ArmParams.SIDE_CAP))
        assertNull(VariantLab.valuesFor(b, "orb", false, day), "never mid-day: from the next session")
        assertNull(VariantLab.valuesFor(b, "orb", true, from), "live untouched")
        assertEquals(1.0, b.currentOf("orb_fresh").getValue(ArmParams.SIDE_CAP))
        assertEquals("entries a side a day no cap → 1 a side a day", VariantLab.moved(applied.first()))
        assertTrue(applied.first().why.startsWith("R14"))
        // Once: a second pass changes nothing.
        val again = VariantLab.research(b, day.plusDays(1), from.plusDays(1))
        assertTrue(again.events.isEmpty()); assertEquals(b, again.book)
        // Boss's Undo on the card: paper back to no cap from the next session - and never seeded again.
        assertTrue(VariantLab.undoable(b, "R14-orb"))
        val undone = VariantLab.undo(b, "R14-orb", from.plusDays(1))!!
        assertNull(VariantLab.valuesFor(undone, "orb", false, from.plusDays(1)), "the defaults (no cap) again")
        assertNotNull(VariantLab.effective(undone, "orb", false, from.plusDays(1)), "an explicit tune: the arm's paper default no longer applies")
        assertTrue(VariantLab.research(undone, day.plusDays(2), from.plusDays(2)).events.isEmpty())
    }

    @Test fun theSafetyNetLeavesTheResearchChange() {
        val b = VariantLab.research(VariantLab.Book(), day, from).book
        val runs = mapOf("orb" to VariantLab.Run.PAPER, "orb_fresh" to VariantLab.Run.PAPER)
        val p = VariantLab.safety(b.copy(streaks = mapOf("orb" to 2)), from, runs, from.plusDays(1)) { _, _ -> -50_000.0 }
        assertTrue(p.events.isEmpty(), "a big loss under the cap does not lift it")
        assertNull(p.book.streaks["orb"])
    }

    @Test fun liveIsProposedAndNeedsThePin() {
        val b0 = VariantLab.research(VariantLab.Book(runs = mapOf("orb" to VariantLab.Run.LIVE)), day, from)
        val prop = b0.events.filterIsInstance<VariantLab.Event.Proposed>().single().change
        assertEquals("R14L-orb", prop.id); assertEquals(VariantLab.ChangeKind.LIVE_PROPOSAL, prop.kind); assertEquals(VariantLab.ChangeStatus.OFFERED, prop.status)
        assertNull(VariantLab.valuesFor(b0.book, "orb", true, from), "nothing live until Boss says yes")
        assertNull(VariantLab.accept(b0.book, prop.id, pinConfirmed = false, from = from))
        val yes = VariantLab.accept(b0.book, prop.id, pinConfirmed = true, from = from)!!
        assertEquals(1.0, VariantLab.valuesFor(yes, "orb", true, from)!!.getValue(ArmParams.SIDE_CAP))
        // Taking it back adds live risk (more entries): the PIN.
        assertFalse(VariantLab.restoresDefault(yes, prop.id))
        assertNull(VariantLab.undoLive(yes, prop.id, pinConfirmed = false, from = from.plusDays(1)))
        assertNotNull(VariantLab.undoLive(yes, prop.id, pinConfirmed = true, from = from.plusDays(1)))
        // Not proposed twice, nor while another proposal waits.
        assertTrue(VariantLab.research(b0.book, day.plusDays(1), from).events.none { it is VariantLab.Event.Proposed })
        val waiting = VariantLab.Change("C9", "orb_fresh", "V1", VariantLab.ChangeKind.LIVE_PROPOSAL, VariantLab.ChangeStatus.OFFERED, day,
            emptyMap(), emptyMap(), "")
        val other = VariantLab.research(VariantLab.Book(runs = mapOf("orb_fresh" to VariantLab.Run.LIVE), changes = listOf(waiting)), day, from)
        assertTrue(other.events.none { it is VariantLab.Event.Proposed })
    }

    @Test fun aVariantFromBeforeTheCapShadowsOn() {
        val s = VariantLab.strategy("orb")!!
        val old = s.defaults - ArmParams.SIDE_CAP + (ArmParams.TARGET_PTS to 50.0)
        val v = VariantLab.Variant("V1", "orb", old, s.defaults - ArmParams.SIDE_CAP, day, 1.0, VariantLab.Status.ALIVE)
        val back = VariantLab.decode(VariantLab.encode(VariantLab.Book(variants = listOf(v)))).variants.single()
        assertEquals(VariantLab.Status.ALIVE, back.status)
        assertEquals(0.0, back.values.getValue(ArmParams.SIDE_CAP)); assertEquals(50.0, back.values.getValue(ArmParams.TARGET_PTS))
        // A key the strategy never had still retires it (the S3 rule).
        val alien = v.copy(values = old + ("zzz" to 1.0))
        assertEquals(VariantLab.Status.RETIRED, VariantLab.decode(VariantLab.encode(VariantLab.Book(variants = listOf(alien)))).variants.single().status)
        // R14 retires the living variants measured against the uncapped rules.
        val seeded = VariantLab.research(VariantLab.Book(variants = listOf(v)), day, from).book
        assertEquals(VariantLab.Status.RETIRED, seeded.variants.single().status)
    }

    @Test fun theLabsReplayHonoursTheCap() {
        assertEquals(1, ArmParams.orb("orb", VariantLab.strategy("orb")!!.values(mapOf(ArmParams.SIDE_CAP to 1.0))).sideCap)
        assertEquals(setOf(OrbRules.ORB.source, OrbRules.ORB_FRESH.source), ReentryCap.ARMS)
    }
}
