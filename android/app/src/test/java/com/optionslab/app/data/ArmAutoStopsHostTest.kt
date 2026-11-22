package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.ArmAutoStops
import com.optionslab.ira.ArmAutoStops.Rule
import com.optionslab.ira.AutoChange
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 19 Nov 2026 (Boss: "i want it to run until i stop them"): the four automatic stops' switches in the app ([ArmAutoStopsHost]) - on by
 * default (as before), off only with the PIN and never by voice, on in one tap, kept across a restart; Boss's one-time choice runs once,
 * switches all four off and is logged in the change timeline (one entry each, undone in one tap with no PIN); the erase forgets it.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ArmAutoStopsHostTest : RobolectricTest() {
    @Before fun up() {
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
    }

    @After fun down() {
        ArmAutoStopsHost.resetForTest()
        if (Autopilot.enabled()) Autopilot.resetForTest()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun onByDefaultEveryRuleActsAsBeforeAndBeforeTheReadToo() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = false)
        for (r in Rule.entries) {
            assertTrue(ArmAutoStopsHost.isOn(r))
            assertTrue(ArmAutoStopsHost.acts(r, DonchianArm.SOURCE))
        }
        assertTrue(ArmAutoStopsHost.paperAccountActs(DonchianArm.LABEL, "k"))
        // The stops that latch for good wait on the three until the switches are read and Boss's choice has run (a later pass acts);
        // every other arm as before. Boss's own yes always may.
        assertFalse(ArmAutoStopsHost.reviewMayStop(ArmAutoStops.REVIEW_KEY))
        assertTrue(ArmAutoStopsHost.reviewMayStop(ArmAutoStops.REVIEW_KEY, byBoss = true))
        assertFalse(ArmAutoStopsHost.mayLatch(Rule.FORWARD_BAR, DonchianArm.SOURCE))
        assertTrue(ArmAutoStopsHost.mayLatch(Rule.FORWARD_BAR, "oov"))
        assertTrue(ArmAutoStopsHost.reviewMayStop("hero"))
        assertNull("no row line before the read", ArmAutoStopsHost.view.value.rowLine(DonchianArm.SOURCE))
        assertTrue(ArmAutoStopsHost.line(), ArmAutoStopsHost.line().contains("not read yet (all on)"))
        // A fresh phone's read: all on, the one-time choice not run.
        ArmAutoStopsHost.load()
        assertTrue(Rule.entries.all { ArmAutoStopsHost.isOn(it) })
        assertTrue(ArmAutoStopsHost.view.value.loaded)
        assertFalse(ArmAutoStopsHost.holdsForErase())
    }

    @Test fun offTakesThePinNeverAVoiceOnIsOneTapAndARestartKeepsIt() {
        for (r in Rule.entries) {
            assertTrue(ArmAutoStopsHost.set(r, false).contains("takes your PIN"))
            assertEquals("Jarvis or a voice: never off", ArmAutoStops.VOICE_OFF, ArmAutoStopsHost.set(r, false, pinConfirmed = true, byVoice = true))
            assertTrue(ArmAutoStopsHost.isOn(r))
        }
        assertTrue(ArmAutoStopsHost.set(Rule.LIVE_STOP, false, pinConfirmed = true, log = false).startsWith("Off"))
        assertFalse(ArmAutoStopsHost.isOn(Rule.LIVE_STOP))
        assertFalse(ArmAutoStopsHost.acts(Rule.LIVE_STOP, LiquidityRules.ARM15.source))
        assertTrue("another arm keeps it", ArmAutoStopsHost.acts(Rule.LIVE_STOP, "orb"))
        assertTrue(ArmAutoStopsHost.holdsForErase())
        assertTrue(ArmAutoStopsHost.view.value.rowLine(LiquidityRules.ARM.source)!!.contains("the live stop rule no longer stops it"))
        SecurePrefs.flush()
        assertFalse(SecurePrefs.getBoolean(Rule.LIVE_STOP.prefKey, true))
        // A restart reads it back off.
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = false)
        ArmAutoStopsHost.load()
        assertFalse(ArmAutoStopsHost.isOn(Rule.LIVE_STOP)); assertTrue(ArmAutoStopsHost.isOn(Rule.FORWARD_BAR))
        // On: one tap, Jarvis too.
        assertTrue(ArmAutoStopsHost.set(Rule.LIVE_STOP, true, byVoice = true, log = false).startsWith("On"))
        assertTrue(ArmAutoStopsHost.isOn(Rule.LIVE_STOP))
        assertTrue(ArmAutoStopsHost.set(Rule.LIVE_STOP, true, log = false).contains("already on"))
    }

    @Test fun bossesChoiceRunsOnceSwitchesAllFourOffAndIsLoggedWithAOneTapUndo() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = true)
        // This phone ran R51 before Boss's choice (a trade closed on 10 Nov): his choice applies.
        val t0 = java.time.LocalDateTime.of(2026, 11, 10, 10, 0)
        runBlocking { PassedArmsHost.addClosedForTest(listOf(PassedArmsHost.Pos(DonchianArm.SOURCE, "NIFTY", "X1", "CE", 65, 100.0, t0, null,
            exit = 98.0, exitTime = t0, why = "stop"))) }
        assertFalse("the latching stops wait for the choice", ArmAutoStopsHost.mayLatch(Rule.FORWARD_BAR, DonchianArm.SOURCE))
        assertTrue(runBlocking { ArmAutoStopsHost.migrateNow() })
        assertTrue(Rule.entries.none { ArmAutoStopsHost.isOn(it) })
        assertFalse(ArmAutoStopsHost.mayLatch(Rule.FORWARD_BAR, DonchianArm.SOURCE))
        assertTrue("another arm keeps it", ArmAutoStopsHost.mayLatch(Rule.FORWARD_BAR, "oov"))
        assertFalse("never twice", runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        SecurePrefs.flush()
        assertTrue(SecurePrefs.getBoolean(ArmAutoStops.MIGRATION_KEY, false))
        // A restart: read back as run (never again), all four off.
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = false)
        assertFalse(runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        assertTrue(Rule.entries.none { ArmAutoStopsHost.isOn(it) })
        assertTrue(ArmAutoStopsHost.line(), ArmAutoStopsHost.line().contains("off by Boss's choice (19 Nov)"))
        if (!Autopilot.enabled()) return
        val end = System.currentTimeMillis() + 10_000
        fun entries() = Autopilot.book.value.entries.filter { AutoChange.isAutoStopTarget(it.change.target) }
        while (entries().size < Rule.entries.size && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals("one entry each", Rule.entries.size, entries().size)
        for (e in entries()) {
            assertEquals(AutoChange.SWITCHED_OFF, e.change.to)
            assertTrue(e.change.reason, e.change.reason.startsWith(ArmAutoStops.CHOICE))
            assertTrue(e.change.reason, e.change.reason.contains("Nothing was switched back on"))
        }
        // The forward bar's entry: one tap undoes it (back on), with no PIN.
        val fwd = entries().single { it.change.target == Rule.FORWARD_BAR.target }
        assertFalse(Autopilot.undoNeedsPin(fwd.id))
        assertTrue(Autopilot.undo(fwd.id).startsWith("Undone"))
        assertTrue(ArmAutoStopsHost.isOn(Rule.FORWARD_BAR))
        assertFalse(ArmAutoStopsHost.isOn(Rule.LIVE_STOP))
        // Boss switches it off again in Bot settings (PIN): its Undo (back on) is one tap; the Undo of an "on" takes the PIN.
        ArmAutoStopsHost.set(Rule.SELF_REVIEW, true)
        val end2 = System.currentTimeMillis() + 10_000
        fun ons() = entries().filter { it.change.target == Rule.SELF_REVIEW.target && it.change.to == AutoChange.ON }
        while (ons().isEmpty() && System.currentTimeMillis() < end2) Thread.sleep(20)
        val on = ons().single()
        assertTrue(Autopilot.undoNeedsPin(on.id))
        assertFalse("never without the PIN", Autopilot.undo(on.id).startsWith("Undone"))
        assertTrue(ArmAutoStopsHost.isOn(Rule.SELF_REVIEW))
        assertTrue(Autopilot.undo(on.id, pinConfirmed = true).startsWith("Undone"))
        assertFalse(ArmAutoStopsHost.isOn(Rule.SELF_REVIEW))
    }

    @Test fun anUnreadableVaultNeverRunsTheChoiceNorIsWrittenOver() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = false)
        ArmAutoStopsHost.testReadFails = true
        assertFalse(runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        assertTrue(Rule.entries.all { ArmAutoStopsHost.isOn(it) })
        assertFalse("not read: the latching stops wait", ArmAutoStopsHost.mayLatch(Rule.FORWARD_BAR, DonchianArm.SOURCE))
        assertTrue(ArmAutoStopsHost.set(Rule.LIVE_STOP, false, pinConfirmed = true, log = false).contains("Could not read"))
        assertTrue(ArmAutoStopsHost.isOn(Rule.LIVE_STOP))
        SecurePrefs.flush()
        assertTrue("nothing written", SecurePrefs.getBoolean(Rule.LIVE_STOP.prefKey, true))
        assertFalse(SecurePrefs.getBoolean(ArmAutoStops.MIGRATION_KEY, false))
        // Readable again: the choice runs then.
        ArmAutoStopsHost.testReadFails = false
        assertTrue(runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        assertTrue(Rule.entries.none { ArmAutoStopsHost.isOn(it) })
    }

    /** CLAUDE.md: after a restart the open positions are restored and watched first - Boss's choice reads the arms' books only after. */
    @Test fun bossesChoiceWaitsForTheOpenPositionsAndTheStreamFirst() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = true)
        PositionsFirst.resetForTest(started = true)
        try {
            val done = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
            val t = Thread { done.set(runBlocking { ArmAutoStopsHost.migrateNow(existed = true) }) }
            t.start()
            Thread.sleep(300)
            assertNull("waits for the positions step", done.get())
            assertTrue(Rule.entries.all { ArmAutoStopsHost.isOn(it) })
            PositionsFirst.order.done(com.optionslab.ira.StartOrder.Step.POSITIONS, 1L)
            Thread.sleep(200)
            assertNull("and for the stream", done.get())
            PositionsFirst.order.done(com.optionslab.ira.StartOrder.Step.STREAM, 2L)
            t.join(10_000)
            assertEquals(true, done.get())
            assertTrue(Rule.entries.none { ArmAutoStopsHost.isOn(it) })
        } finally { PositionsFirst.resetForTest(started = false) }
    }

    /**
     * Review (19 Nov): a book set aside as unreadable is no evidence the phone ran the three (the ORB family's book holds every ORB arm):
     * with no trade of theirs before the choice, every stop stays on.
     */
    @Test fun aSetAsideBookIsNoEvidenceAndTheStopsStayOn() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = true)
        java.io.File(context.filesDir, "orb.vault.unreadable.123").writeText("x")
        assertEquals(false, runBlocking { ArmAutoStopsHost.tradedBeforeChoice() })
        assertFalse(runBlocking { ArmAutoStopsHost.migrateNow() })
        assertTrue(Rule.entries.all { ArmAutoStopsHost.isOn(it) })
    }

    /** Review (19 Nov): a restore marks the choice done in memory too, and a later read never undoes that mark. */
    @Test fun aRestoresMarkSurvivesARead() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = false)
        ArmAutoStopsHost.markChoiceRun()
        ArmAutoStopsHost.load()                                 // the vault still says not run
        assertFalse(runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        assertTrue(Rule.entries.all { ArmAutoStopsHost.isOn(it) })
        assertFalse(ArmAutoStopsHost.deferring())
    }

    /**
     * Review (19 Nov): the switches unreadable at the start are read again from the arms' and the review's workers (at most once a
     * minute, in the background), and Boss's choice then runs; until then the stops that latch wait on the three.
     */
    @Test fun anUnreadableStartIsReadAgainLaterAndTheChoiceThenRuns() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = false)
        ArmAutoStopsHost.testReadFails = true
        ArmAutoStopsHost.retryNowForTest()
        assertTrue("still waiting", ArmAutoStopsHost.ensureReady())
        Thread.sleep(500)                                       // the background try (it fails) has run
        assertTrue(ArmAutoStopsHost.deferring())
        assertFalse(ArmAutoStopsHost.mayLatch(Rule.FORWARD_BAR, DonchianArm.SOURCE))
        // Readable again: the next try (after a minute) reads the switches and runs the choice (here a phone that ran R72 before).
        ArmAutoStopsHost.testReadFails = false
        val t0 = java.time.LocalDateTime.of(2026, 11, 12, 11, 0)
        runBlocking { PassedArmsHost.addClosedForTest(listOf(PassedArmsHost.Pos(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, "NIFTY", "X2", "CE",
            65, 100.0, t0, null, exit = 98.0, exitTime = t0, why = "stop"))) }
        assertTrue("within the minute: not tried again", ArmAutoStopsHost.ensureReady())
        ArmAutoStopsHost.retryNowForTest()
        ArmAutoStopsHost.ensureReady()
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && ArmAutoStopsHost.deferring()) Thread.sleep(20)
        assertFalse(ArmAutoStopsHost.deferring())
        assertTrue(Rule.entries.none { ArmAutoStopsHost.isOn(it) })
        assertFalse("ready: nothing more to do", ArmAutoStopsHost.ensureReady())
    }

    /** Review (19 Nov): a phone whose first choice ran with four switches (the earlier build) gets Jarvis's cutoff off too, once, logged. */
    @Test fun aPhoneThatRanTheFourSwitchChoiceGetsTheCutoffOffToo() {
        // As the earlier build's choice saved them: the four off together, at one moment.
        for (r in Rule.entries - Rule.JARVIS_CUTOFF) { SecurePrefs.put(r.prefKey, false); SecurePrefs.put(r.offAtKey, "1000") }
        SecurePrefs.put(ArmAutoStops.MIGRATION_KEY, true)
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = false)
        ArmAutoStopsHost.load()
        assertTrue(ArmAutoStopsHost.isOn(Rule.JARVIS_CUTOFF))
        assertTrue("its own step not run yet: the latching stops wait", ArmAutoStopsHost.deferring())
        assertTrue(runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        assertFalse(ArmAutoStopsHost.isOn(Rule.JARVIS_CUTOFF))
        assertFalse(ArmAutoStopsHost.deferring())
        assertFalse("never twice", runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        SecurePrefs.flush()
        assertTrue(SecurePrefs.getBoolean(ArmAutoStops.CUTOFF_MIGRATION_KEY, false))
        assertFalse(SecurePrefs.getBoolean(Rule.JARVIS_CUTOFF.prefKey, true))
        if (!Autopilot.enabled()) return
        val end = System.currentTimeMillis() + 10_000
        fun cut() = Autopilot.book.value.entries.filter { it.change.target == Rule.JARVIS_CUTOFF.target }
        while (cut().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(AutoChange.SWITCHED_OFF, cut().single().change.to)
    }

    @Test fun aNewOrErasedPhoneKeepsEveryRuleOnAndMarksTheChoiceRun() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = true)
        // No trade of the three closed before 19 Nov in this (new) phone's books: nothing of Boss's to carry over.
        assertFalse(runBlocking { ArmAutoStopsHost.migrateNow() })
        assertTrue(Rule.entries.all { ArmAutoStopsHost.isOn(it) })
        assertTrue("the choice is marked run: the stops act as before", ArmAutoStopsHost.mayLatch(Rule.FORWARD_BAR, DonchianArm.SOURCE))
        SecurePrefs.flush()
        assertTrue(SecurePrefs.getBoolean(ArmAutoStops.MIGRATION_KEY, false))
        assertFalse(runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        assertTrue(Rule.entries.all { ArmAutoStopsHost.isOn(it) })
    }

    @Test fun theEraseForgetsBossesChoice() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.migrate(ArmAutoStops.State(), 1L, "k")!!, loaded = true)
        assertTrue(ArmAutoStopsHost.holdsForErase())
        ArmAutoStopsHost.wipe()
        assertFalse(ArmAutoStopsHost.holdsForErase())
        assertTrue(Rule.entries.all { ArmAutoStopsHost.isOn(it) })
        assertFalse("not run again in this process", runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        // A late start read never brings the old choice back.
        SecurePrefs.put(Rule.LIVE_STOP.prefKey, false)
        ArmAutoStopsHost.load()
        assertTrue(ArmAutoStopsHost.isOn(Rule.LIVE_STOP))
    }

    /** 20 Nov 2026 (minor fixes, item 3): why the choice was not applied reaches the card - a new phone (kept over a restart), a restore. */
    @Test fun theCardIsToldWhyTheChoiceWasNotApplied() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = true)
        assertTrue("not run yet: said as waiting on a read", ArmAutoStopsHost.view.value.notice()!!.contains("have not been read yet"))
        assertFalse(runBlocking { ArmAutoStopsHost.migrateNow() })            // no trade of the three before 19 Nov
        assertEquals(ArmAutoStops.NotApplied.NEW_PHONE, ArmAutoStopsHost.view.value.notApplied)
        assertTrue(ArmAutoStopsHost.view.value.notice()!!.contains("no trade of R51, R72 or Liquidity before 19 Nov"))
        SecurePrefs.flush()
        assertEquals("new", SecurePrefs.getString(ArmAutoStops.NOT_APPLIED_KEY))
        // A restart reads it back.
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = false)
        ArmAutoStopsHost.load()
        assertEquals(ArmAutoStops.NotApplied.NEW_PHONE, ArmAutoStopsHost.view.value.notApplied)
        // A restore's mark.
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = false)
        ArmAutoStopsHost.markChoiceRun(ArmAutoStops.NotApplied.RESTORE)
        assertEquals(ArmAutoStops.NotApplied.RESTORE, ArmAutoStopsHost.view.value.notApplied)
        // The choice applied: no notice.
        SecurePrefs.put(ArmAutoStops.NOT_APPLIED_KEY, "")
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = true)
        assertTrue(runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        assertNull(ArmAutoStopsHost.view.value.notice())
    }

    @Test fun anUnreadableVaultIsSaidOnTheCard() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = false)
        ArmAutoStopsHost.testReadFails = true
        ArmAutoStopsHost.load()
        assertTrue(ArmAutoStopsHost.view.value.readFailed)
        assertTrue(ArmAutoStopsHost.view.value.notice()!!.contains("Once they are read"))
        ArmAutoStopsHost.testReadFails = false
        ArmAutoStopsHost.load()
        assertFalse(ArmAutoStopsHost.view.value.readFailed)
    }

    /**
     * 20 Nov 2026 (review): Boss set a switch themselves - the notice is over, and neither the start's late read (a vault still holding the
     * old reason) nor the choice's new-phone step brings it back.
     */
    @Test fun bossesOwnSwitchEndsTheNoticeForGood() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(migrated = true), loaded = true)
        ArmAutoStopsHost.markChoiceRun(ArmAutoStops.NotApplied.RESTORE)
        assertEquals(ArmAutoStops.NotApplied.RESTORE, ArmAutoStopsHost.view.value.notApplied)
        assertTrue(ArmAutoStopsHost.set(Rule.LIVE_STOP, false, pinConfirmed = true, log = false).startsWith("Off"))
        assertNull(ArmAutoStopsHost.view.value.notApplied)
        SecurePrefs.flush()
        SecurePrefs.put(ArmAutoStops.NOT_APPLIED_KEY, ArmAutoStops.NotApplied.NEW_PHONE.key)   // the old reason, as a late read finds it
        ArmAutoStopsHost.load()
        assertNull("the late read never brings it back", ArmAutoStopsHost.view.value.notApplied)
        assertNull(ArmAutoStopsHost.view.value.notice())
        // The choice's new-phone step after Boss's own switch: no notice either.
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = true)
        assertTrue(ArmAutoStopsHost.set(Rule.FORWARD_BAR, false, pinConfirmed = true, log = false).startsWith("Off"))
        assertFalse(runBlocking { ArmAutoStopsHost.migrateNow(existed = false) })
        assertNull(ArmAutoStopsHost.view.value.notApplied)
        assertFalse(ArmAutoStopsHost.isOn(Rule.FORWARD_BAR))
        // ...and on disk: a restart reads no reason either.
        SecurePrefs.flush()
        assertTrue(SecurePrefs.getString(ArmAutoStops.NOT_APPLIED_KEY).isNullOrEmpty())
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = false)
        ArmAutoStopsHost.load()
        assertNull(ArmAutoStopsHost.view.value.notApplied)
    }

    /**
     * 22 Nov 2026 (B6 review, item 9a): the paper account stop's reach cannot be read - its switch-off is refused (never taken as "not
     * reached", losing a stop already reached), and Boss's one-time choice waits for a later pass with every stop still on.
     */
    @Test fun anUnreadableReachRefusesThePaperAccountSwitchOffAndHoldsTheChoice() {
        ArmAutoStopsHost.resetForTest()
        ArmAutoStopsHost.testHeldFails = true
        assertEquals(ArmAutoStopsHost.HELD_UNREAD, ArmAutoStopsHost.set(Rule.PAPER_ACCOUNT, false, pinConfirmed = true, log = false))
        assertTrue(ArmAutoStopsHost.isOn(Rule.PAPER_ACCOUNT))
        // Another switch never reads it: as before.
        assertTrue(ArmAutoStopsHost.set(Rule.LIVE_STOP, false, pinConfirmed = true, log = false).startsWith("Off"))
        // The one-time choice: not run on a guess - every stop on, the choice still due.
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = true)
        ArmAutoStopsHost.testHeldFails = true
        assertFalse(runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        assertTrue(Rule.entries.all { ArmAutoStopsHost.isOn(it) })
        assertTrue(ArmAutoStopsHost.deferring())
        SecurePrefs.flush()
        assertFalse(SecurePrefs.getBoolean(ArmAutoStops.MIGRATION_KEY, false))
        // Readable again: it runs (and the switch-off goes through).
        ArmAutoStopsHost.testHeldFails = false
        assertTrue(runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        assertTrue(Rule.entries.none { ArmAutoStopsHost.isOn(it) })
    }

    /**
     * 22 Nov 2026 (B6 review, item 9a): the paper account stop's record not read (an erase's fresh state, or a start's read that failed) - it
     * is read again before the switch goes off, so a stop already reached is kept, never taken as "not reached".
     */
    @Test fun anUnreadReachIsReadAgainAndAReachedStopKeptAsItGoesOff() {
        ArmAutoStopsHost.resetForTest()
        val since = Market.today().minusDays(20)
        SecurePrefs.putAll(mapOf("g.boss.acct.since" to since.toString(), "g.boss.acct.reached" to "${Market.today().minusDays(2)}|month"))
        BossRiskHost.wipe()
        assertFalse(BossRiskHost.readOk)
        assertTrue(ArmAutoStopsHost.set(Rule.PAPER_ACCOUNT, false, pinConfirmed = true, log = false).startsWith("Off"))
        assertTrue(BossRiskHost.readOk)
        val key = BossRiskHost.accountKeyNow()
        assertTrue("reached, as saved", key != null)
        assertTrue("reached before it went off: still holds R51", ArmAutoStopsHost.paperAccountActs(DonchianArm.SOURCE, key))
        BossRiskHost.resetForTest(paperOn = false)
    }

    /** 22 Nov 2026 (B6 review, item 9a): a settings vault that cannot be read now refuses the switch-off too (its saved reach is unknown). */
    @Test fun anUnreadableVaultRefusesThePaperAccountSwitchOff() {
        ArmAutoStopsHost.resetForTest()
        SecurePrefs.put("x", 1)
        com.optionslab.app.security.Vault.destroy()
        assertFalse(SecurePrefs.reload())
        assertEquals(ArmAutoStopsHost.HELD_UNREAD, ArmAutoStopsHost.set(Rule.PAPER_ACCOUNT, false, pinConfirmed = true, log = false))
        assertTrue(ArmAutoStopsHost.isOn(Rule.PAPER_ACCOUNT))
    }

    /**
     * 22 Nov 2026 (B6 review, item 9b): Boss switches a rule back on at the very moment his one-time choice is written - his switch is
     * the one kept, in memory and in the vault (the choice is written under its lock, before his).
     */
    @Test fun aSwitchSetAsTheChoiceIsWrittenIsNeverOverwritten() {
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = true)
        ArmAutoStopsHost.testAfterChoice = { assertTrue(ArmAutoStopsHost.set(Rule.FORWARD_BAR, true, log = false).startsWith("On")) }
        assertTrue(runBlocking { ArmAutoStopsHost.migrateNow(existed = true) })
        assertTrue(ArmAutoStopsHost.isOn(Rule.FORWARD_BAR))
        assertFalse(ArmAutoStopsHost.isOn(Rule.LIVE_STOP))
        SecurePrefs.flush()
        assertTrue("his on is the one written", SecurePrefs.getBoolean(Rule.FORWARD_BAR.prefKey, false))
        assertFalse(SecurePrefs.getBoolean(Rule.LIVE_STOP.prefKey, true))
        // A restart reads his switch back.
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(), loaded = false)
        ArmAutoStopsHost.load()
        assertTrue(ArmAutoStopsHost.isOn(Rule.FORWARD_BAR))
        assertFalse(ArmAutoStopsHost.isOn(Rule.LIVE_STOP))
    }
}
