package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boss's diagnostics, 17 Oct 01:07: "MCX is open with 2 armed ... but the market watch is not running" was still listed long
 * after MCX's 23:30 close. The coverage problems now clear when a check finds no gap ([SessionCoverage.keepOpen]), and the
 * watch's own "should I run?" ([SessionCoverage.watchShouldRun]) agrees with the gaps for every market state.
 */
class SessionCoverageAgreeTest {
    private val mcxArms = listOf("NG evening breakout", "US-night silver (not proven, futures)")

    @Test fun theWatchsRunRuleAndTheGapsAgreeForEveryMarketState() {
        // NSE in its window or not (a holiday or after 15:40: not), MCX open or shut (its own calendar: a holiday morning,
        // after 23:30), something held there or not, an arm armed or not.
        var states = 0
        for (nse in listOf(true, false)) for (open in listOf(true, false)) for (held in listOf(0, 1, 2)) for (armed in listOf(emptyList(), mcxArms.take(1), mcxArms)) {
            states++
            val mcx = SessionCoverage.Market("MCX", open, held, armed)
            var asked = false
            val run = SessionCoverage.watchShouldRun(nse) { asked = true; listOf(mcx) }
            // In NSE's window MCX's reading is not even made (the loop's short cut, as before).
            if (nse) assertFalse(asked)
            val gaps = SessionCoverage.gaps(listOf(mcx), watchAlive = false, streamUp = null)
            val needs = open && (held > 0 || armed.isNotEmpty())
            assertEquals(needs, mcx.needs, "$nse $open $held $armed")
            // A gap is found only where the watch's own rule says it should run: the two can never disagree.
            if (gaps.isNotEmpty()) assertTrue(run, "a gap where the watch would not run: $nse $open $held $armed")
            assertEquals(needs, gaps.isNotEmpty(), "$nse $open $held $armed")
            // The watch runs whenever MCX needs it, whatever NSE does; and in NSE's window whatever MCX does.
            assertEquals(nse || needs, run, "$nse $open $held $armed")
            // With the watch running, no gap at all.
            assertTrue(SessionCoverage.gaps(listOf(mcx), watchAlive = true, streamUp = null).isEmpty())
        }
        assertEquals(36, states)
    }

    @Test fun aGapToldOnceIsClearedWhenAnyCheckFindsNone() {
        val mcx = SessionCoverage.Market("MCX", true, 0, mcxArms)
        val gaps = SessionCoverage.gaps(listOf(mcx), watchAlive = false, streamUp = null)
        assertEquals(listOf("coverage:MCX:watch"), gaps.map { it.key })
        // Told: kept open (and only those).
        assertEquals(setOf("coverage:MCX:watch"), SessionCoverage.keepOpen(gaps, told = true))
        // Still in its grace: nothing touched (an earlier one stays until a check finds none).
        assertNull(SessionCoverage.keepOpen(gaps, told = false))
        // MCX closed at 23:30, or the watch running again, or nothing armed: no gap - every coverage problem is over.
        for (now in listOf(mcx.copy(open = false), mcx.copy(armed = emptyList()))) {
            val none = SessionCoverage.gaps(listOf(now), watchAlive = false, streamUp = null)
            assertTrue(none.isEmpty())
            assertEquals(emptySet(), SessionCoverage.keepOpen(none, told = false))
            assertEquals(emptySet(), SessionCoverage.keepOpen(none, told = true))
        }
        assertEquals(emptySet(), SessionCoverage.keepOpen(SessionCoverage.gaps(listOf(mcx), watchAlive = true, streamUp = null), told = false))
    }

    @Test fun theGapTimerForgetsAGapThatClosed() {
        val t = SessionCoverage.GapTimer(graceMs = 1_000, repeatMs = 0)
        assertFalse(t.report(true, 10_000))
        assertTrue(t.report(true, 11_000))
        // Closed (MCX shut): not told, and nothing pending.
        assertFalse(t.report(false, 12_000))
        assertFalse(t.pending())
    }

    @Test fun aFreezesPlaceIsCountedUnderItsStableKey() {
        val w = "BinderProxy.transactNative in Vault.encrypt from a.invoke · SecurePrefs.put jarvis.auto.guard by b.c"
        assertEquals("BinderProxy.transactNative in Vault.encrypt", HealthBook.placeKey(HealthBook.place(w)))
        val b = HealthBook.stall(HealthBook.stall(HealthBook.Book(), 1_791_800_000_000L, 3_000, w), 1_791_800_060_000L, 2_000,
            "BinderProxy.transactNative in Vault.encrypt from x.y · SecurePrefs.put ui.theme by z.w")
        val d = b.days.single()
        assertEquals(mapOf("BinderProxy.transactNative in Vault.encrypt" to 2), d.places)
        // The sample keeps the whole text (the problem's words).
        assertEquals(w, d.samples.first().where)
        assertEquals("Choreographer.doFrame", HealthBook.placeKey("Choreographer.doFrame"))
    }

    @Test fun aBinderFreezeKeepsTheCallerAndTheWriteTheGuardCaught() {
        // 17 Oct: the freeze's place keeps up to 200 characters, so "from ... · SecurePrefs.put x by y" is not cut off.
        val w = "BinderProxy.transactNative in Vault.encrypt from SecurePrefs.put < a.invoke · SecurePrefs.put jarvis.auto.guard by IraPageKt.AutomationsCard"
        assertEquals(w, HealthBook.place(w))
        assertEquals(200, HealthBook.place("BinderProxy.transactNative in " + "x".repeat(400)).length)
        // Not a Binder call: the app's own frame, as before.
        assertEquals("BrainSection.invoke", HealthBook.place("Choreographer.doFrame in BrainSection.invoke"))
    }
}
