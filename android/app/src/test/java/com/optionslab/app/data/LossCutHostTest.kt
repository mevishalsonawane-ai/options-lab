package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.AutoChange
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 1 Nov (Boss: "Okay go ahead, but dont break any thing, as i am going live tomorrow"): the two paper loss-cut switches
 * ([LossCutHost]) are OFF by default, read back after a restart, and each flip from Bot settings is in the Autopilot change
 * timeline as Boss's own, undone in one tap with no PIN (paper only).
 */
class LossCutHostTest : RobolectricTest() {
    @Before fun up() { if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false } }
    @After fun down() { LossCutHost.setForTest(); if (Autopilot.enabled()) Autopilot.resetForTest() }

    @Test fun bothAreOffByDefaultAndAfterAFreshRead() {
        assertFalse(LossCutHost.wideDayOn()); assertFalse(LossCutHost.noProgressOn())
        LossCutHost.load()
        assertFalse(LossCutHost.wideDayOn()); assertFalse(LossCutHost.noProgressOn())
    }

    @Test fun aFlipIsSavedAndReadBackAfterARestart() {
        assertTrue(LossCutHost.setWideDay(true, log = false).contains("Paper only"))
        assertTrue(LossCutHost.setNoProgress(true, log = false).contains("Paper only"))
        assertTrue(LossCutHost.wideDayOn() && LossCutHost.noProgressOn())
        SecurePrefs.flush()
        LossCutHost.setForTest()                 // a restart: memory forgotten
        LossCutHost.load()
        assertTrue(LossCutHost.wideDayOn() && LossCutHost.noProgressOn())
        LossCutHost.setNoProgress(false, log = false); SecurePrefs.flush()
        LossCutHost.setForTest(); LossCutHost.load()
        assertTrue(LossCutHost.wideDayOn()); assertFalse(LossCutHost.noProgressOn())
    }

    @Test fun eachFlipIsInTheTimelineAndItsUndoSwitchesItBackWithNoPin() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        for ((target, flip) in listOf<Pair<String, (Boolean) -> String>>(
            AutoChange.WIDE_DAY_TARGET to { on -> LossCutHost.setWideDay(on) },
            AutoChange.NO_PROGRESS_TARGET to { on -> LossCutHost.setNoProgress(on) })) {
            flip(true)
            fun entries() = Autopilot.book.value.entries.filter { it.change.target == target && it.change.to == AutoChange.ON }
            val deadline = System.currentTimeMillis() + 10_000
            while (entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val e = entries().single()
            assertEquals(AutoChange.Status.KEPT, e.status)
            assertEquals("your choice", e.by)
            assertTrue(e.note, e.note.contains("paper only"))
            assertFalse("no PIN: paper only", Autopilot.undoNeedsPin(e.id))
            assertTrue(Autopilot.undo(e.id).startsWith("Undone"))
            assertFalse(if (target == AutoChange.WIDE_DAY_TARGET) LossCutHost.wideDayOn() else LossCutHost.noProgressOn())
        }
    }
}
