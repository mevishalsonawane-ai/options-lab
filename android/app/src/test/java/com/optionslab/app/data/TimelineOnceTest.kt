package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.AutoChange
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 22 Nov 2026 (B6 review, item 4): the change timeline's "switched on by the update" entries are logged once per phone. The timeline drops
 * its oldest kept entries past 400; before, the next start logged a dropped one again as new. Each now has a one-time mark in the settings
 * vault (never in a backup), written once its entry is in the saved timeline, so a later start never logs it again; a timeline that could
 * not be read starts afresh with its marks forgotten (the entries come back, with their Undo).
 */
class TimelineOnceTest : RobolectricTest() {
    @Before fun up() {
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false
            com.optionslab.app.testing.AutomationSupport.settleStartTimeline(context) }
    }

    @After fun down() {
        IdeaRuleHost.setForTest()
        StepTrailExits.resetForTest(on = false)
        if (Autopilot.enabled()) Autopilot.resetForTest()
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && !ok()) Thread.sleep(20)
        assertTrue("waited for $what", ok())
    }

    private fun logged(target: String) = Autopilot.book.value.entries.count { it.change.target == target }
    private val all = listOf(TimelineOnce.STEP_TRAIL, TimelineOnce.STEP_LOCK, TimelineOnce.BOOK_PRICE, TimelineOnce.IDEA_RULE,
        TimelineOnce.SAME_DIR_CAP, TimelineOnce.LIQ_NO_TWIN)

    @Test fun aMarkIsKeptOnThisPhoneOnlyAndForgottenWithTheTimeline() {
        assertEquals("one mark each", 6, all.toSet().size)
        for (k in all) assertFalse(k, com.optionslab.ira.Upkeep.carried(k))
        assertEquals(false, TimelineOnce.done(TimelineOnce.STEP_TRAIL))
        TimelineOnce.mark(TimelineOnce.STEP_TRAIL)
        assertEquals(true, TimelineOnce.done(TimelineOnce.STEP_TRAIL))
        SecurePrefs.putAll(mapOf(CheaperStrike.K_ONCE to true, "s.capital" to 5.0))
        TimelineOnce.forgetAll()
        assertEquals(false, TimelineOnce.done(TimelineOnce.STEP_TRAIL))
        assertFalse(SecurePrefs.getBoolean(CheaperStrike.K_ONCE, false))
        assertEquals("nothing else touched", 5.0, SecurePrefs.getDouble("s.capital", 0.0), 0.0)
    }

    @Test fun anUnreadableVaultTellsNothing() {
        SecurePrefs.put("x", 1)
        Vault.destroy()
        assertFalse(SecurePrefs.reload())
        assertNull(TimelineOnce.done(TimelineOnce.BOOK_PRICE))
    }

    /** The real start paths: logged (and marked) at the first start; pruned from the timeline; a later start never logs it again. */
    @Test fun aPrunedUpdateEntryIsNeverLoggedAgain() {
        Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        // The breakeven at +10% and its fourth version (StepTrailExits' start read; on, as a phone that never switched them).
        StepTrailExits.resetForTest(on = true, loaded = false)
        StepTrailExits.load()
        waitFor("the V4 and V5 entries") { logged(AutoChange.BE10_TARGET) == 1 && logged(AutoChange.STEP_LOCK_TARGET) == 1 }
        // Boss's win-rate rule, the paper book price and the same-direction cap (their starts).
        IdeaRuleHost.init(context)
        waitFor("the idea rule's entry") { logged(AutoChange.IDEA_RULE_TARGET) == 1 }
        BookPriceHost.init(context)
        waitFor("the book price's entry") { logged(AutoChange.BOOK_PRICE_TARGET) == 1 }
        ExposureCapHost.init(context)
        waitFor("the same-direction cap's entry") { logged(AutoChange.SAME_DIR_TARGET) == 1 }
        waitFor("their marks") { listOf(TimelineOnce.STEP_TRAIL, TimelineOnce.STEP_LOCK, TimelineOnce.IDEA_RULE, TimelineOnce.BOOK_PRICE,
            TimelineOnce.SAME_DIR_CAP).all { TimelineOnce.done(it) == true } }
        // The timeline prunes them (its oldest kept entries past 400 go), then the app starts again.
        Autopilot.setForTest(AutoChange.Book())
        StepTrailExits.resetForTest(on = true, loaded = false)
        StepTrailExits.load()
        IdeaRuleHost.init(context); BookPriceHost.init(context); ExposureCapHost.init(context)
        // Boss switching the rule on again (it was off) never brings the V4 -> V5 entry back either.
        StepTrailExits.resetForTest(on = false)
        StepTrailExits.set(true, log = false)
        Thread.sleep(1_500)
        for (t in listOf(AutoChange.BE10_TARGET, AutoChange.STEP_LOCK_TARGET, AutoChange.IDEA_RULE_TARGET, AutoChange.BOOK_PRICE_TARGET,
                AutoChange.SAME_DIR_TARGET)) assertEquals("$t not logged again", 0, logged(t))
    }

    /** A timeline that could not be read is set aside and starts afresh: its one-time marks go, so the entries (and their Undo) come back. */
    @Test fun aTimelineSetAsideLogsTheUpdatesEntriesAgain() {
        Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        TimelineOnce.mark(TimelineOnce.STEP_TRAIL)
        val dir = File(context.noBackupFilesDir, "autopilot").apply { mkdirs() }
        File(dir, "log.vault").writeBytes(ByteArray(48) { 5 })
        Autopilot.unloadForTest()
        Autopilot.logStepTrailOn()
        waitFor("the V4 entry logged again") { logged(AutoChange.BE10_TARGET) == 1 }
        waitFor("marked again") { TimelineOnce.done(TimelineOnce.STEP_TRAIL) == true }
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("log.vault.unreadable.") })
    }
}
