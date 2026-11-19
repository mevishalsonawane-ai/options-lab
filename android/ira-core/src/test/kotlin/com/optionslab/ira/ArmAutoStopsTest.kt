package com.optionslab.ira

import com.optionslab.engine.orb.BreadthDonchianArm
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.ArmAutoStops.Rule
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 19 Nov 2026 (Boss: "i want it to run until i stop them"): the four automatic stops' switches on R51, R72 and Liquidity 15+5 - on (as
 * before) by default, off by his one-time choice, off only with the PIN and never by voice, every other arm untouched, and nothing switched
 * back on (a stop already reached when it went off is kept).
 */
class ArmAutoStopsTest {
    private val on = ArmAutoStops.State()
    private val allOff = ArmAutoStops.migrate(on, 1_000L, null)!!

    @Test fun theThreeArmsAreCoveredAndNoOther() {
        for (who in listOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE, LiquidityRules.ARM.source, ArmAutoStops.REVIEW_KEY) +
                LiquidityRules.BOOKS.map { it.source })
            assertTrue(ArmAutoStops.covers(who), who)
        for (who in listOf("orb", "R52 Open outside value", "oov", "solo2", "Hero", "hero", "", "liquidity ", "s5trail", "FT3"))
            assertFalse(ArmAutoStops.covers(who), who)
        // Review (19 Nov): by source only - a strategy of Boss's named like one of the arms is never taken for it.
        for (name in listOf(DonchianArm.LABEL, BreadthDonchianArm.LABEL, LiquidityRules.ARM.label) + LiquidityRules.BOOKS.map { it.label })
            assertFalse(ArmAutoStops.covers(name), name)
    }

    @Test fun onByDefaultEveryRuleActsAsBefore() {
        for (r in Rule.entries) {
            assertTrue(on.isOn(r))
            assertTrue(ArmAutoStops.acts(on, r, DonchianArm.SOURCE))
            assertTrue(ArmAutoStops.acts(on, r, "orb"))
        }
        assertTrue(on.off.isEmpty())
        assertNull(ArmAutoStops.rowLine(on, DonchianArm.SOURCE))
        assertTrue(ArmAutoStops.reviewMayStop(on, ArmAutoStops.REVIEW_KEY))
        assertEquals("Automatic stops on R51, R72 and Liquidity: all on (as before 19 Nov).", ArmAutoStops.line(on, loaded = true))
        assertEquals("Automatic stops on R51, R72 and Liquidity: not read yet (all on).", ArmAutoStops.line(allOff, loaded = false))
    }

    @Test fun offStopsActingOnTheThreeOnlyAndEveryOtherArmKeepsIt() {
        for (r in Rule.entries) {
            assertFalse(ArmAutoStops.acts(allOff, r, DonchianArm.SOURCE))
            assertFalse(ArmAutoStops.acts(allOff, r, LiquidityRules.ARM5.source))
            assertTrue(ArmAutoStops.acts(allOff, r, "R52 Open outside value"), "another arm keeps ${r.name}")
            assertTrue(ArmAutoStops.acts(allOff, r, "Hero ₹1,000"))
        }
        // Only one switch off: only that rule stops acting.
        val one = ArmAutoStops.set(on, Rule.FORWARD_BAR, false, 5L, pinConfirmed = true).state
        assertFalse(ArmAutoStops.acts(one, Rule.FORWARD_BAR, DonchianArm.SOURCE))
        assertTrue(ArmAutoStops.acts(one, Rule.PAPER_ACCOUNT, DonchianArm.SOURCE))
        // The self-review on Boss's own yes still may (his choice), Autopilot's own not.
        assertFalse(ArmAutoStops.reviewMayStop(allOff, ArmAutoStops.REVIEW_KEY))
        assertTrue(ArmAutoStops.reviewMayStop(allOff, ArmAutoStops.REVIEW_KEY, byBoss = true))
        assertTrue(ArmAutoStops.reviewMayStop(allOff, "hero"))
    }

    @Test fun offTakesThePinNeverAVoiceAndOnIsOneTap() {
        val noPin = ArmAutoStops.set(on, Rule.LIVE_STOP, false, 5L, pinConfirmed = false)
        assertFalse(noPin.changed); assertSame(on, noPin.state)
        assertTrue(noPin.said.contains("takes your PIN"), noPin.said)
        // Jarvis or a voice command: never off, not even with a PIN claimed.
        val voice = ArmAutoStops.set(on, Rule.LIVE_STOP, false, 5L, pinConfirmed = true, byVoice = true)
        assertFalse(voice.changed); assertEquals(ArmAutoStops.VOICE_OFF, voice.said)
        val off = ArmAutoStops.set(on, Rule.LIVE_STOP, false, 5L, pinConfirmed = true)
        assertTrue(off.changed); assertFalse(off.state.isOn(Rule.LIVE_STOP)); assertEquals(5L, off.state.offAtMs[Rule.LIVE_STOP])
        // On: no PIN, voice too.
        val back = ArmAutoStops.set(off.state, Rule.LIVE_STOP, true, 9L, pinConfirmed = false, byVoice = true)
        assertTrue(back.changed); assertTrue(back.state.isOn(Rule.LIVE_STOP)); assertNull(back.state.offAtMs[Rule.LIVE_STOP])
        assertTrue(back.said.startsWith("On:"), back.said)
        // Already so: nothing changes.
        val again = ArmAutoStops.set(back.state, Rule.LIVE_STOP, true, 10L, pinConfirmed = false)
        assertFalse(again.changed); assertTrue(again.said.contains("already on"), again.said)
        assertTrue(again.said.contains("Nothing changed"), "the timeline's Undo never marks it done")
        assertFalse(ArmAutoStops.set(off.state, Rule.LIVE_STOP, false, 10L, pinConfirmed = true).changed)
    }

    @Test fun theOneTimeChoiceRunsOnceAndKeepsTheHeldAccountStop() {
        val m = ArmAutoStops.migrate(on, 7L, "2026-10-25|2026-11-10|drawdown")!!
        assertTrue(m.migrated); assertEquals(Rule.entries, m.off)
        assertTrue(Rule.entries.all { m.offAtMs[it] == 7L })
        assertEquals("2026-10-25|2026-11-10|drawdown", m.heldAccount)
        assertNull(ArmAutoStops.migrate(m, 8L, null), "never twice")
        // A switch Boss turned back on stays on: the choice does not run again.
        val back = ArmAutoStops.set(m, Rule.SELF_REVIEW, true, 9L, pinConfirmed = false).state
        assertNull(ArmAutoStops.migrate(back, 10L, null))
        assertTrue(back.isOn(Rule.SELF_REVIEW))
    }

    private val day = LocalDate.of(2026, 11, 19)
    private fun t(ms: Long, net: Double) = LiquidityStopRule.Trade(ms, day, net)

    @Test fun theLiveStopRuleOffKeepsOnlyAStopReachedBeforeItWentOff() {
        val trades = listOf(t(100, -8_000.0), t(200, -6_000.0))   // the month reaches -13,000 on the second
        val raw = LiquidityStopRule.check(trades, day)
        assertTrue(raw.stopped)
        // On: as before.
        assertSame(raw, ArmAutoStops.liveStatus(on, raw, trades, day, 0L))
        // Off before the trade that reached it: not a stop (figures kept for the row).
        val offEarly = ArmAutoStops.set(on, Rule.LIVE_STOP, false, 150L, pinConfirmed = true).state
        val s = ArmAutoStops.liveStatus(offEarly, raw, trades, day, 0L)
        assertFalse(s.stopped); assertNull(s.why); assertNull(s.day); assertEquals(raw.drawdown, s.drawdown); assertEquals("", s.key)
        // Off after it was reached: kept, until Boss re-enables it with his PIN (the count from then: nothing reached).
        val offLate = ArmAutoStops.set(on, Rule.LIVE_STOP, false, 300L, pinConfirmed = true).state
        assertTrue(ArmAutoStops.liveStatus(offLate, raw, trades, day, 0L).stopped)
        val afterReenable = LiquidityStopRule.check(trades + t(400, -50_000.0), day, 350L)
        assertTrue(afterReenable.stopped, "a new loss after the re-enable reaches it again on its own")
        assertFalse(ArmAutoStops.liveStatus(offLate, afterReenable, trades + t(400, -50_000.0), day, 350L).stopped)
        // Not reached: nothing to change.
        val calm = LiquidityStopRule.check(listOf(t(100, 500.0)), day)
        assertSame(calm, ArmAutoStops.liveStatus(allOff, calm, listOf(t(100, 500.0)), day, 0L))
        // Review (19 Nov): when it went off is not known (an unreadable save): the stop is kept, never lifted on a guess.
        val noTime = allOff.copy(offAtMs = emptyMap())
        assertTrue(ArmAutoStops.liveStatus(noTime, raw, trades, day, 0L).stopped)
        // Empty record.
        val none = LiquidityStopRule.check(emptyList(), day)
        assertFalse(ArmAutoStops.liveStatus(allOff, none, emptyList(), day, 0L).stopped)
    }

    @Test fun thePaperAccountStopOffKeepsOnlyTheStopAlreadyReached() {
        val key = ArmAutoStops.accountKey(LocalDate.of(2026, 10, 25), "2026-11-10|drawdown -Rs 41,000")
        assertEquals("2026-10-25|2026-11-10|drawdown -Rs 41,000", key)
        assertNull(ArmAutoStops.accountKey(LocalDate.of(2026, 10, 25), null))
        // On: as before, for everyone.
        assertTrue(ArmAutoStops.paperAccountActs(on, DonchianArm.SOURCE, key))
        // Off with nothing reached then: a stop reached later does not stop the three; another arm still stops.
        val off = ArmAutoStops.set(on, Rule.PAPER_ACCOUNT, false, 5L, pinConfirmed = true, heldAccount = null).state
        assertFalse(ArmAutoStops.paperAccountActs(off, DonchianArm.SOURCE, key))
        assertFalse(ArmAutoStops.paperAccountActs(off, LiquidityRules.ARM15.source, key))
        assertTrue(ArmAutoStops.paperAccountActs(off, "oov", key))
        // Review (19 Nov): an entry that is not one of the arms' own (no source: a strategy named like an arm) is refused as before.
        assertTrue(ArmAutoStops.paperAccountActs(off, null, key))
        assertFalse(ArmAutoStops.paperAccountActs(off, DonchianArm.SOURCE, null))
        // Off while it was reached: still stops them, until Boss restarts its count (a new count: a new key, or none).
        val held = ArmAutoStops.set(on, Rule.PAPER_ACCOUNT, false, 5L, pinConfirmed = true, heldAccount = key).state
        assertTrue(ArmAutoStops.paperAccountActs(held, BreadthDonchianArm.SOURCE, key))
        assertFalse(ArmAutoStops.paperAccountActs(held, BreadthDonchianArm.SOURCE, null))
        assertFalse(ArmAutoStops.paperAccountActs(held, BreadthDonchianArm.SOURCE, ArmAutoStops.accountKey(LocalDate.of(2026, 11, 20), "2026-12-01|month")))
        // On again clears the hold; another rule never touches it.
        assertNull(ArmAutoStops.set(held, Rule.PAPER_ACCOUNT, true, 6L, pinConfirmed = false).state.heldAccount)
        assertEquals(key, ArmAutoStops.set(held, Rule.LIVE_STOP, false, 6L, pinConfirmed = true).state.heldAccount)
    }

    @Test fun rowsSayWhichStopsNoLongerApply() {
        val r51 = ArmAutoStops.rowLine(allOff, DonchianArm.SOURCE)!!
        assertTrue(r51.startsWith("Auto-stop off by Boss's choice (19 Nov): the forward-test switch-off, the paper account stop no longer stop it"), r51)
        assertFalse(r51.contains("live stop rule"), r51)
        val liq = ArmAutoStops.rowLine(allOff, LiquidityRules.ARM.source)!!
        assertTrue(liq.contains("the live stop rule, the self-review park and pause, the paper account stop, Jarvis's losing-arm cutoff"), liq)
        assertFalse(liq.contains("forward-test"), liq)
        assertNull(ArmAutoStops.rowLine(allOff, "orb"))
        // Only the live stop off: R51's row has nothing to say; Liquidity's names it alone.
        val one = ArmAutoStops.set(on, Rule.LIVE_STOP, false, 5L, pinConfirmed = true).state
        assertNull(ArmAutoStops.rowLine(one, BreadthDonchianArm.SOURCE))
        assertTrue(ArmAutoStops.rowLine(one, LiquidityRules.ARM5.source)!!.contains("the live stop rule no longer stops it"))
        assertTrue(ArmAutoStops.rowLine(one, LiquidityRules.ARM.source)!!.contains("the live stop rule no longer stops it"))
        // The live stop rule never covered FINNIFTY or MIDCPNIFTY: their rows never claim it.
        assertNull(ArmAutoStops.rowLine(one, LiquidityRules.MID5.source))
        assertNull(ArmAutoStops.rowLine(one, LiquidityRules.FIN30.source))
        assertFalse(ArmAutoStops.rowLine(allOff, LiquidityRules.FIN5.source)!!.contains("live stop rule"))
        assertTrue(ArmAutoStops.line(one, true).contains("off by Boss's choice (19 Nov): the live stop rule; on: the forward-test switch-off"))
    }

    @Test fun theTimelineWordsListWhatIsStillStopped() {
        val r = ArmAutoStops.offReason(Rule.FORWARD_BAR, listOf("R51 Donchian 20 (net −₹3,000 after 40 paper trades)"))
        assertTrue(r.startsWith(ArmAutoStops.CHOICE), r)
        assertTrue(r.contains("still stopped by it until you switch them on yourself - R51 Donchian 20 (net"), r)
        assertTrue(ArmAutoStops.offReason(Rule.LIVE_STOP, emptyList()).contains("it had stopped none of them"))
        assertTrue(ArmAutoStops.offReason(Rule.SELF_REVIEW, null).contains("could not be read just now"))
        assertTrue(ArmAutoStops.onReason(Rule.PAPER_ACCOUNT).contains("applies to R51, R72 and Liquidity again"))
        // The change: live scope, on lowers risk, off raises it (so its Undo of an on takes the PIN); never refused by the safety filter.
        for (rule in Rule.entries) {
            val off = AutoChange.armAutoStop(rule, false, "x")
            val onC = AutoChange.armAutoStop(rule, true, "y")
            assertTrue(AutoChange.isAutoStopTarget(off.target))
            assertFalse(AutoChange.never(off)); assertFalse(AutoChange.never(onC))
            assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(off)); assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(onC))
            assertEquals(AutoChange.Scope.LIVE, off.scope)
            assertFalse(AutoChange.undoNeedsPin(off), "the Undo of Boss's off (back on) takes no PIN")
            assertTrue(AutoChange.undoNeedsPin(onC), "the Undo of an on (off again) takes the PIN")
            assertFalse(off.target.contains("liquidity"), "the paper-test freeze never holds its Undo")
            assertEquals(rule, Rule.ofTarget(off.target))
            assertEquals(rule.switch, off.what)
        }
        assertFalse(AutoChange.isAutoStopTarget("autostop:else"))
    }

    /** Review (19 Nov): a phone whose first choice ran with four switches gets Jarvis's cutoff off too, once, while the choice stands. */
    @Test fun theFifthSwitchsOwnStepRunsOnceOnAPhoneThatRanTheFirstChoice() {
        val four = ArmAutoStops.State(on = (Rule.entries - Rule.JARVIS_CUTOFF).associateWith { false },
            offAtMs = (Rule.entries - Rule.JARVIS_CUTOFF).associateWith { 1L }, migrated = true, cutoffMigrated = false)
        assertFalse(four.ready)
        val m = ArmAutoStops.migrateCutoff(four, 9L)!!
        assertFalse(m.isOn(Rule.JARVIS_CUTOFF)); assertEquals(9L, m.offAtMs[Rule.JARVIS_CUTOFF]); assertTrue(m.ready)
        assertNull(ArmAutoStops.migrateCutoff(m, 10L), "never twice")
        // Boss had switched the other four back on himself: the cutoff stays on (marked done).
        val allBack = ArmAutoStops.State(migrated = true, cutoffMigrated = false)
        val k = ArmAutoStops.migrateCutoff(allBack, 9L)!!
        assertTrue(k.isOn(Rule.JARVIS_CUTOFF)); assertTrue(k.ready)
        // A phone where the choice never applied (the no-trades path) and Boss switched one stop off himself: the cutoff stays on.
        val own = ArmAutoStops.State(on = mapOf(Rule.PAPER_ACCOUNT to false), offAtMs = mapOf(Rule.PAPER_ACCOUNT to 5L), migrated = true,
            cutoffMigrated = false)
        assertTrue(ArmAutoStops.migrateCutoff(own, 9L)!!.isOn(Rule.JARVIS_CUTOFF))
        // The first choice not run yet: nothing here (the first choice switches all five).
        assertNull(ArmAutoStops.migrateCutoff(ArmAutoStops.State(), 9L))
        assertTrue(allOff.ready); assertFalse(allOff.isOn(Rule.JARVIS_CUTOFF))
        // Read back: the step's own key, missing on such a phone, reads as not run.
        val back = ArmAutoStops.decode({ r -> four.on[r] }, { r -> four.offAtMs[r]?.toString() }, null, migrated = true, cutoffMigrated = false)
        assertFalse(back.ready)
        assertEquals(true, ArmAutoStops.encode(m)[ArmAutoStops.CUTOFF_MIGRATION_KEY])
    }

    @Test fun aSwitchsChangeWritesOnlyItsOwnKeys() {
        val off = ArmAutoStops.set(ArmAutoStops.State(), Rule.LIVE_STOP, false, 9L, pinConfirmed = true).state
        assertEquals(mapOf(Rule.LIVE_STOP.prefKey to false, Rule.LIVE_STOP.offAtKey to "9"), ArmAutoStops.encodeRule(off, Rule.LIVE_STOP))
        val acct = ArmAutoStops.set(ArmAutoStops.State(), Rule.PAPER_ACCOUNT, false, 9L, pinConfirmed = true, heldAccount = "k").state
        assertEquals(mapOf(Rule.PAPER_ACCOUNT.prefKey to false, Rule.PAPER_ACCOUNT.offAtKey to "9", ArmAutoStops.HELD_KEY to "k"),
            ArmAutoStops.encodeRule(acct, Rule.PAPER_ACCOUNT))
        assertFalse(ArmAutoStops.MIGRATION_KEY in ArmAutoStops.encodeRule(acct, Rule.PAPER_ACCOUNT), "never the one-time choice's key")
    }

    @Test fun theSwitchesRoundTripThroughTheVault() {
        val s = ArmAutoStops.set(ArmAutoStops.migrate(on, 42L, "k")!!, Rule.SELF_REVIEW, true, 50L, pinConfirmed = false).state
        val m = ArmAutoStops.encode(s)
        val back = ArmAutoStops.decode({ r -> m[r.prefKey] as Boolean? }, { r -> m[r.offAtKey] as String? }, m[ArmAutoStops.HELD_KEY] as String?,
            m[ArmAutoStops.MIGRATION_KEY] as Boolean)
        assertEquals(s, back)
        assertTrue(back.isOn(Rule.SELF_REVIEW)); assertFalse(back.isOn(Rule.LIVE_STOP)); assertEquals(42L, back.offAtMs[Rule.LIVE_STOP])
        // Nothing saved (a fresh phone): all on, not migrated; an unreadable time is dropped.
        val fresh = ArmAutoStops.decode({ null }, { "x" }, "", false)
        assertTrue(Rule.entries.all { fresh.isOn(it) }); assertTrue(fresh.offAtMs.isEmpty()); assertNull(fresh.heldAccount); assertFalse(fresh.migrated)
        assertNotNull(Rule.ofTarget("autostop:forward_bar"))
    }
}
