package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The safety net for unwatched positions ([OrphanAudit], 16 Oct): a long an automatic arm opened, that no arm's record and
 * no exit order watches, is found from its opening fill (a re-entry after a closed trade on the same symbol included), and
 * acted on only after two sightings.
 */
class OrphanAuditTest {
    private val day = LocalDate.of(2026, 10, 16)
    private fun t(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private val sym = "NIFTY19OCT2623050CE"
    private val owners = mapOf("p1" to "R51 Donchian 20", "p2" to "R51 Donchian 20", "p3" to "R51 Donchian 20", "m1" to "Manual")
    private val auto: (String) -> Boolean = { it != "Manual" }

    /** The 16 Oct day: bought at 09:40 (p1), sold at its target (p2), bought again at 10:20 at 122.20 (p3). */
    private fun reEntry() = OrphanAudit.Held(sym, 65, 122.20, listOf(
        OrphanAudit.Fill("p1", true, 65, 95.0, t(9, 40)),
        OrphanAudit.Fill("p2", false, 65, 131.9, t(9, 58)),
        OrphanAudit.Fill("p3", true, 65, 122.20, t(10, 20))))

    @Test fun theReEntryIsOpenedByItsOwnBuyNotTheFirstTrades() {
        val o = OrphanAudit.opened(reEntry(), owners::get)
        assertEquals("p3", o.orderId); assertEquals("R51 Donchian 20", o.owner)
        assertEquals(122.20, o.fill, 1e-9); assertEquals(t(10, 20), o.at)
    }

    @Test fun anUnwatchedArmPositionIsAnOrphanAndAWatchedOneIsNot() {
        val found = OrphanAudit.find(listOf(reEntry()), emptySet(), owners::get, auto).single()
        assertEquals(sym, found.symbol); assertEquals(65, found.qty); assertTrue(found.known)
        assertEquals("p3", found.orderId); assertEquals(122.20, found.fill, 1e-9)
        // Its arm's record holds it, or an exit order works on it: watched.
        assertTrue(OrphanAudit.find(listOf(reEntry()), setOf(sym), owners::get, auto).isEmpty())
        // A hand order is never adopted; nor is a position with no owner kept, a short, or flat.
        val manual = OrphanAudit.Held("X", 50, 10.0, listOf(OrphanAudit.Fill("m1", true, 50, 10.0, t(10, 0))))
        val nobody = OrphanAudit.Held("Y", 50, 10.0, listOf(OrphanAudit.Fill("zz", true, 50, 10.0, t(10, 0))))
        val short = OrphanAudit.Held("Z", -50, 10.0, listOf(OrphanAudit.Fill("p1", false, 50, 10.0, t(10, 0))))
        assertTrue(OrphanAudit.find(listOf(manual, nobody, short), emptySet(), owners::get, auto).isEmpty())
    }

    @Test fun addsAveragedAndACarryFromAnEarlierSessionIsUnknown() {
        // Two buys of one trade: their average, the first buy's time and order.
        val two = OrphanAudit.Held(sym, 130, 110.0, listOf(OrphanAudit.Fill("p1", true, 65, 100.0, t(10, 0)), OrphanAudit.Fill("p2", true, 65, 120.0, t(10, 5))))
        val o = OrphanAudit.opened(two, owners::get)
        assertEquals("p1", o.orderId); assertEquals(110.0, o.fill, 1e-9); assertEquals(t(10, 0), o.at)
        // No fills today (an overnight carry), or fills that do not explain the quantity: unknown, the account's average.
        val carry = OrphanAudit.opened(OrphanAudit.Held(sym, 65, 99.0, emptyList()), owners::get)
        assertNull(carry.orderId); assertNull(carry.at); assertEquals(99.0, carry.fill, 1e-9)
        assertNull(OrphanAudit.opened(OrphanAudit.Held(sym, 130, 99.0, listOf(OrphanAudit.Fill("p1", true, 65, 95.0, t(9, 40)))), owners::get).orderId)
        // A zero-quantity fill is skipped; a full close then nothing: nothing opened.
        val closed = OrphanAudit.Held(sym, 65, 99.0, listOf(OrphanAudit.Fill("p1", true, 0, 95.0, t(9, 40)), OrphanAudit.Fill("p1", true, 65, 95.0, t(9, 41)), OrphanAudit.Fill("p2", false, 65, 96.0, t(9, 42))))
        assertNull(OrphanAudit.opened(closed, owners::get).orderId)
        // An unknown-time orphan: no orphan without an owner, so none.
        assertTrue(OrphanAudit.find(listOf(OrphanAudit.Held(sym, 65, 99.0, emptyList())), emptySet(), owners::get, auto).isEmpty())
    }

    @Test fun twoSightingsBeforeActingAndOnceOnly() {
        val p = OrphanAudit.Pending()
        val o = OrphanAudit.find(listOf(reEntry()), emptySet(), owners::get, auto)
        assertTrue(p.confirm(o, 0L).isEmpty(), "the first sighting: an entry may still be being booked")
        assertTrue(p.confirm(o, OrphanAudit.CONFIRM_MS - 1).isEmpty())
        assertEquals(1, p.confirm(o, OrphanAudit.CONFIRM_MS).size)
        // Acted on: not again while it stays the same position.
        p.done(o.single())
        assertTrue(p.confirm(o, OrphanAudit.CONFIRM_MS * 2).isEmpty())
        // Gone (watched again): forgotten; seen again later, it is a first sighting.
        assertTrue(p.confirm(emptyList(), OrphanAudit.CONFIRM_MS * 3).isEmpty())
        assertTrue(p.confirm(o, OrphanAudit.CONFIRM_MS * 4).isEmpty())
        p.clear()
        assertTrue(p.confirm(o, 0L).isEmpty())
    }

    @Test fun theNetsOwnLevelsAndAnUnknownTimeHasNoTimeExit() {
        assertEquals(103.85, OrphanAudit.netStop(122.20), 1e-9)
        assertEquals(158.90, OrphanAudit.netTarget(122.20), 1e-9)
        assertEquals(85.0, OrphanAudit.netStop(100.0), 1e-9); assertEquals(130.0, OrphanAudit.netTarget(100.0), 1e-9)
        val x = OrphanAudit.unknownTime(DonchianArm.EXITS)
        assertNull(x.tmaxMinutes); assertEquals(DonchianArm.EXITS.stop, x.stop); assertEquals(DonchianArm.EXITS.squareOff, x.squareOff)
        assertFalse(OrphanAudit.Orphan(sym, 65, "a", null, 1.0, null).known)
        assertEquals("$sym|?|65", OrphanAudit.Orphan(sym, 65, "a", null, 1.0, null).key)
    }
}
