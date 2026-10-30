package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.Supervisor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 30 Oct (CI review; CLAUDE.md "open positions come first"): the arms that are switched off and flat only decide for the
 * switched-off shadow, so the watch's pass hands their passes to [OffShadow.runLate]: one job of their own, after the stops, never
 * waited on, one run at a time, each step bounded and failing alone. An arm with real work (on, or holding) stays in the pass.
 */
class OffShadowLateTest : RobolectricTest() {
    private fun step(name: String, body: suspend () -> Unit) = Supervisor.Step(name, body)

    @Test fun theTestAppOnlyNotesTheHandOver() {
        OffShadow.runLate(listOf(step("a") { error("never run in a test's pass") }, step("b") {}))
        assertEquals(listOf("a", "b"), AutomationSupport.lateShadow.toList())
        OffShadow.runLate(emptyList())
        assertEquals(2, AutomationSupport.lateShadow.size)
    }

    @Test fun eachStepRunsInItsOwnJobAFailureIsItsOwnAndAStepStillGoingSkipsItsNextHandOver() = runBlocking {
        OffShadow.testLate = null
        val ran = Collections.synchronizedList(ArrayList<String>())
        val release = CompletableDeferred<Unit>()
        val others = CountDownLatch(1)
        // Returns at once: the caller (the watch's pass) never waits on the steps.
        val t0 = System.nanoTime()
        OffShadow.runLate(listOf(
            step("slow") { ran += "slow"; release.await(); ran += "slow done" },
            step("broken") { ran += "broken"; error("a shadow step failed") },
            step("quick") { ran += "quick"; others.countDown() },
        ))
        assertTrue("never waited on", (System.nanoTime() - t0) / 1_000_000 < 1_000)
        // A slow step never holds the others up, and a failing one is its own.
        assertTrue(others.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { while (!("broken" in ran && "slow" in ran)) kotlinx.coroutines.delay(5) }
        assertFalse("slow done" in ran)
        // The same step handed over again while it still runs is skipped; another step is not.
        val again = CountDownLatch(1)
        OffShadow.runLate(listOf(step("slow") { ran += "second slow" }, step("quick") { ran += "quick again"; again.countDown() }))
        assertTrue(again.await(5, TimeUnit.SECONDS))
        assertFalse("second slow" in ran)
        release.complete(Unit)
        withTimeout(5_000) { while ("slow done" !in ran) kotlinx.coroutines.delay(5) }
        // Free again: its next hand-over runs.
        val third = CountDownLatch(1)
        withTimeout(5_000) {
            while (true) {
                OffShadow.runLate(listOf(step("slow") { third.countDown() }))
                if (third.await(50, TimeUnit.MILLISECONDS)) break
            }
        }
    }

    @Test fun aStepIsBoundedAndAStepThatNeverEndedIsGivenUpLater() {
        assertEquals(45_000L, OffShadow.LATE_STEP_MS)
        assertTrue(OffShadow.LATE_STALE_MS > OffShadow.LATE_STEP_MS)
    }

    @Test fun anArmIsInThePassOnlyWithRealWork() = runBlocking {
        // Read and switched off, nothing held: shadow only.
        S5Arm.refresh(); CisdArm.refresh(); PassedArmsHost.refresh(); NightArm.refresh(); VixDivArm.refresh()
        assertFalse(S5Arm.realDue())
        assertFalse(CisdArm.realDue())
        assertFalse(NightArm.realDue())
        assertFalse(VixDivArm.realDue())
        // Switched on: in the pass.
        CisdArm.setArmed(true)
        assertTrue(CisdArm.realDue())
        VixDivArm.setArmed(true)
        assertTrue(VixDivArm.realDue())
    }

    @Test fun aBookNotReadYetCountsAsRealWork() {
        // Before an arm's book is read (a restart) the pass keeps it: what it holds is watched first, never left to a late job.
        S5Arm.wipe(); CisdArm.wipe()
        assertTrue(S5Arm.realDue())
        assertTrue(CisdArm.realDue())
    }
}
