package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Zerodha reconciliation ([PositionSync]): each kind of mismatch, the debounce and dedupe, the words (never rupees). */
class PositionSyncTest {
    private val ce = "NIFTY26OCT25000CE"
    private val pe = "NIFTY26OCT25000PE"
    private fun working(id: String, sym: String = ce) = PositionSync.Order(id, sym, "TRIGGER PENDING", true)

    @Test fun inSyncWhenTheBooksAgree() {
        val b = listOf(PositionSync.Belief("ORB", ce, 75, stopOrderId = "S1"), PositionSync.Belief("Protection", ce, 75, holds = false))
        assertEquals(emptyList(), PositionSync.check(b, mapOf(ce to 75), listOf(working("S1"))))
        assertEquals(emptyList(), PositionSync.check(emptyList(), mapOf(ce to 0), emptyList()))
    }

    @Test fun eachKindOfMismatch() {
        // Zerodha holds what nobody in the app follows.
        val u = PositionSync.check(emptyList(), mapOf(pe to -50), null).single()
        assertEquals(PositionSync.Kind.UNTRACKED, u.kind); assertEquals(-50, u.brokerQty)
        // The app holds, Zerodha is flat.
        val flat = PositionSync.check(listOf(PositionSync.Belief("Pine", ce, 75)), emptyMap(), null).single()
        assertEquals(PositionSync.Kind.FLAT_AT_BROKER, flat.kind)
        // Both hold, in different quantities (two holders of one symbol add up).
        val q = PositionSync.check(listOf(PositionSync.Belief("ORB", ce, 75), PositionSync.Belief("Pine", ce, 75)), mapOf(ce to 75), null).single()
        assertEquals(PositionSync.Kind.QTY_DIFFERS, q.kind); assertEquals(150, q.appQty); assertEquals(75, q.brokerQty)
        // A protection guarding a position Zerodha shows flat, or on the other side.
        assertEquals(PositionSync.Kind.FLAT_AT_BROKER, PositionSync.check(listOf(PositionSync.Belief("Protection", ce, 75, holds = false)), emptyMap(), null).single().kind)
        assertEquals(PositionSync.Kind.FLAT_AT_BROKER, PositionSync.check(listOf(PositionSync.Belief("Protection", ce, 75, holds = false)), mapOf(ce to -75), null).single().kind)
        // A protection on a manual position of another size is not a mismatch (it may cover part of it).
        assertEquals(emptyList(), PositionSync.check(listOf(PositionSync.Belief("Protection", ce, 75, holds = false)), mapOf(ce to 150), null))
    }

    @Test fun aStopTheAppCountsOnMustBeWorking() {
        val b = listOf(PositionSync.Belief("ORB", ce, 75, stopOrderId = "S1"))
        val gone = PositionSync.check(b, mapOf(ce to 75), listOf(PositionSync.Order("S1", ce, "CANCELLED", false))).single()
        assertEquals(PositionSync.Kind.STOP_MISSING, gone.kind)
        assertEquals(PositionSync.Kind.STOP_MISSING, PositionSync.check(b, mapOf(ce to 75), emptyList()).single().kind)
        // A stop that filled is the exit (the next read shows the position flat), not a missing stop.
        assertEquals(emptyList(), PositionSync.check(b, mapOf(ce to 75), listOf(PositionSync.Order("S1", ce, "COMPLETE", false))))
        // No fresh order book: stops are not judged.
        assertEquals(emptyList(), PositionSync.check(b, mapOf(ce to 75), null))
        // A position still settling (unconfirmed) is not judged at all.
        assertEquals(emptyList(), PositionSync.check(listOf(PositionSync.Belief("ORB", ce, 75, unconfirmed = true)), emptyMap(), emptyList()))
    }

    @Test fun twoChecksInARowThenOnceADay() {
        val t = PositionSync.Tracker()
        val m = PositionSync.check(emptyList(), mapOf(pe to 50), null)
        val s1 = t.step("2026-10-12", m)
        assertTrue(s1.confirmed.isEmpty() && s1.fresh.isEmpty(), "a single check may be a moment's lag")
        val s2 = t.step("2026-10-12", m)
        assertEquals(1, s2.confirmed.size); assertEquals(1, s2.fresh.size)
        val s3 = t.step("2026-10-12", m)
        assertEquals(1, s3.confirmed.size); assertTrue(s3.fresh.isEmpty(), "noted once")
        // Gone, then back: confirmed again but not noted again the same day.
        t.step("2026-10-12", emptyList()); t.step("2026-10-12", m)
        assertTrue(t.step("2026-10-12", m).fresh.isEmpty())
        // A new day notes it afresh (it stood in the last check already), once.
        assertEquals(1, t.step("2026-10-13", m).fresh.size)
        assertTrue(t.step("2026-10-13", m).fresh.isEmpty())
    }

    @Test fun wordsAndQuestions() {
        val ms = PositionSync.check(listOf(PositionSync.Belief("ORB", ce, 75)), mapOf(pe to 50), null)
        val line = PositionSync.line(ms, "10:15")
        assertTrue(line.startsWith("Zerodha sync (10:15): 2 mismatches"), line)
        assertFalse(line.contains("₹") || line.contains("Rs"), line)
        assertEquals("Zerodha sync (10:15): in sync", PositionSync.line(emptyList(), "10:15"))
        assertEquals("Zerodha sync: not checked yet (not logged in)", PositionSync.line(emptyList(), null, "not logged in"))
        val a = PositionSync.answer(ms, "10:15", null)
        assertTrue(a.startsWith("Not fully, Boss.") && a.contains("nothing was placed, changed or cancelled"), a)
        assertTrue(PositionSync.answer(emptyList(), "10:15", null).startsWith("Yes, Boss"))
        assertTrue(PositionSync.asked("are my positions in sync"))
        assertTrue(PositionSync.asked("Jarvis, do my positions match Zerodha?"))
        assertTrue(PositionSync.asked("reconcile the app with zerodha"))
        assertFalse(PositionSync.asked("what are my positions"))
        assertFalse(PositionSync.asked("sync my watchlist"))
    }
}
