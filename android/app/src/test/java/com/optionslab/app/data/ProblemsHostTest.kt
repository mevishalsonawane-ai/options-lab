package com.optionslab.app.data

import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.OpenProblems
import com.optionslab.ira.OrderReview
import com.optionslab.ira.PaperOnlyPeriod
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * What no agent told Boss on 12 Oct, told now: the open problems' board ([ProblemsHost]); the order review's charge-eating and
 * re-entry flaws on it and as Jarvis's coach line; the app's health findings on it; and Boss's paper-only period ([PaperOnly]),
 * whose earlier end asks the PIN. Never the real date; no network.
 */
class ProblemsHostTest : RobolectricTest() {
    private val day = LocalDate.of(2026, 10, 12)
    private fun at(h: Int, m: Int): ZonedDateTime = day.atTime(h, m).atZone(com.optionslab.engine.IST)
    private fun ms(h: Int, m: Int) = at(h, m).toInstant().toEpochMilli()

    @Before fun up() {
        ProblemsHost.resetForTest()
        PaperOnly.resetForTest()
        Market.testClock = java.time.Clock.fixed(at(16, 0).toInstant(), com.optionslab.engine.IST)
    }

    @After fun down() {
        ProblemsHost.resetForTest()
        PaperOnly.resetForTest()
        OrderReviewHost.resetForTest()
        Market.testClock = null
        com.optionslab.app.work.BackgroundBudget.resetForTest()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun reportsAreOneLineEachMostSeriousFirstAndAnAgentsSweepClearsItsOwn() {
        assumeTrue(ProblemsHost.enabled())
        ProblemsHost.report("orders:a", "Order review", OpenProblems.Severity.WARN, "ORB churn")
        ProblemsHost.report("health:start", "App health", OpenProblems.Severity.SERIOUS, "start 2.0 s")
        ProblemsHost.report("orders:a", "Order review", OpenProblems.Severity.WARN, "ORB churn, again")
        val open = ProblemsHost.open()
        assertEquals(listOf("health:start", "orders:a"), open.map { it.key })
        assertEquals("ORB churn, again", open[1].text)
        ProblemsHost.clearOthers("orders:", emptySet())
        assertEquals(listOf("health:start"), ProblemsHost.open().map { it.key })
        assertTrue(ProblemsHost.diagLines().first().startsWith("Problems found: 1 open"))
    }

    /** 12 Oct's ORB: nine entries on one side, about zero before charges. */
    private fun orbDay(d: LocalDate): List<OrderReview.Trade> = (0 until 9).map { k ->
        val inAt = ms(9, 30) + k * 25 * 60_000L
        OrderReview.Trade("o$k", OrderReview.Venue.PAPER, "ORB", "BANKNIFTY26OCT55000CE", 30, 300.0, inAt, 300.5, inAt + 10 * 60_000L,
            exitWhy = "profit_lock", net = (if (k % 2 == 0) 15.0 else -12.0) - 80.0, charges = 80.0)
    }

    @Test fun theOrderReviewPutsChargesEatingTheDayOnTheBoardAndInTheChat() = runBlocking {
        assumeTrue("the order review is in the Jarvis builds only", OrderReviewHost.enabled())
        OrderReviewHost.resetForTest()
        com.optionslab.app.work.BackgroundBudget.resetForTest()
        com.optionslab.app.work.BackgroundBudget.testPhone = com.optionslab.ira.BudgetRule.Phone(80, false, 30.0, false)
        OrderReviewHost.testInputs = { d, _, now -> OrderReview.Inputs(d, trades = orbDay(d), nowMs = now) }
        assertTrue(OrderReviewHost.runIfDue(context, at(15, 50)))
        val open = ProblemsHost.open()
        assertTrue(open.toString(), open.any { it.agent == "Order review" && "charges ate the day" in it.text && it.severity == OpenProblems.Severity.SERIOUS })
        assertTrue(open.toString(), open.any { "same side re-entered" in it.text && it.fix?.contains("cap its entries") == true })
        // In sight on the Automation card's Order review section too.
        val v = OrderReviewHost.view(OrderReviewHost.book.value, com.optionslab.ira.VariantLab.Book(), day)
        assertTrue(v.warnings.toString(), v.warnings.any { "charges ate the day" in it })
        // Jarvis's coach line, words only (no rupees).
        val notes = com.optionslab.app.ira.IraHub.state.value.messages.map { it.text }.filter { it.startsWith("Order review, Boss:") }
        assertTrue(notes.isNotEmpty())
        assertTrue(notes.last(), "ORB (paper)" in notes.last() && "₹" !in notes.last() && "Rs " !in notes.last())
    }

    @Test fun theHealthFindingsAreOnTheBoard() {
        assumeTrue(ProblemsHost.enabled())
        AppHealth.resetForTest()
        AppHealth.setForTest(com.optionslab.ira.HealthBook.stall(com.optionslab.ira.HealthBook.Book(), ms(16, 24), 3_900, "BinderProxy.transactNative in AppModel.switchArm"))
        val start = Speed.startMs; val slowest = Speed.startSlowest
        try {
            Speed.startMs = 2_000; Speed.startSlowest = "Jarvis's memory 900 ms"
            AppHealth.healthProblems(day, day.atTime(16, 30))
        } finally { Speed.startMs = start; Speed.startSlowest = slowest; AppHealth.resetForTest() }
        val keys = ProblemsHost.open().map { it.key }
        assertTrue(keys.toString(), "health:stall:$day" in keys && "health:start" in keys)
        assertTrue(ProblemsHost.open().first { it.key == "health:stall:$day" }.text.contains("BinderProxy.transactNative"))
    }

    @Test fun thePaperOnlyPeriodLowersRiskFreelyAndAsksThePinToEndEarly() {
        val said = PaperOnly.set(day.plusDays(9), "10 days on paper", pinConfirmed = false)
        assertTrue(said, said.startsWith("Paper only until Wed 21 Oct (10 days left)"))
        val p = checkNotNull(PaperOnly.activeNow())
        assertEquals(PaperOnlyPeriod.encode(p), SecurePrefs.getString(PaperOnly.KEY))
        // A later end: no PIN. An earlier end, or ending it: the PIN.
        assertTrue(PaperOnly.set(day.plusDays(12), "longer", pinConfirmed = false).startsWith("Paper only until Sat 24 Oct"))
        assertEquals("An earlier end needs your PIN.", PaperOnly.set(day.plusDays(3), "", pinConfirmed = false))
        assertEquals("Ending the paper-only period needs your PIN.", PaperOnly.end(pinConfirmed = false))
        assertNotNull(PaperOnly.activeNow())
        assertTrue(PaperOnly.end(pinConfirmed = true).startsWith("The paper-only period is over"))
        assertNull(PaperOnly.activeNow())
        assertNull(SecurePrefs.getString(PaperOnly.KEY))
        // Never carried by a backup.
        assertTrue(!com.optionslab.ira.Upkeep.carried(PaperOnly.KEY))
    }
}
