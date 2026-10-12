package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The order review's detectors for what no agent caught on 12 Oct: charges eating the day, same-side re-entries, a protection
 * target on the wrong side, a stop replaced by nothing, a guard on a bot's own position, the diary's errors; and a paper speed
 * warning kept apart from live.
 */
class OrderReviewGapsTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int): Long = day.atTime(h, m).atZone(ist).toInstant().toEpochMilli()
    private val P = OrderReview.Venue.PAPER
    private val R = OrderReview

    private fun t(id: String, inAt: Long, outAt: Long, gross: Double, why: String = "profit_lock", strategy: String = "ORB", symbol: String = "BANKNIFTY26OCT55000CE",
                  charges: Double = 80.0, entry: Double = 300.0) =
        OrderReview.Trade(id, P, strategy, symbol, 30, entry, inAt, entry + gross / 30, outAt, stop = null, target = null, exitWhy = why,
            net = gross - charges, charges = charges)

    /** 12 Oct's ORB: nine entries on one side, about zero before charges. */
    private fun orbDay(): List<OrderReview.Trade> = (0 until 9).map { k ->
        val inAt = at(9, 30) + k * 25 * 60_000L
        t("o$k", inAt, inAt + 10 * 60_000L, if (k % 2 == 0) 15.0 else -12.0)
    }

    @Test fun chargesEatingTheDayAreAFlawWithTheNumbers() {
        val d = R.review(OrderReview.Inputs(day, trades = orbDay()))
        val f = d.flaws.single { it.category == OrderReview.Category.CHARGES_EAT }
        assertEquals("ORB", f.strategy)
        assertEquals(OrderReview.Severity.HIGH, f.severity)       // 9 trades: serious
        assertTrue("9 trades" in f.evidence, f.evidence)
        assertTrue("paid ₹720 in charges" in f.evidence, f.evidence)
        assertEquals(720.0, f.rupees, 1e-6)
        assertTrue(R.inSight(f))
    }

    @Test fun aRealEdgeOrAPlainLosingDayIsNotChurn() {
        // Gross well above twice the charges: an edge, not churn.
        val good = (0 until 5).map { k -> t("g$k", at(10, k * 10), at(10, k * 10 + 5), 400.0) }
        assertTrue(R.review(OrderReview.Inputs(day, trades = good)).flaws.none { it.category == OrderReview.Category.CHARGES_EAT })
        // Gross losses far bigger than the charges: the trades' own flaws, not charges eating anything.
        val bad = (0 until 5).map { k -> t("b$k", at(10, k * 10), at(10, k * 10 + 5), -900.0, why = "stop") }
        assertTrue(R.review(OrderReview.Inputs(day, trades = bad)).flaws.none { it.category == OrderReview.Category.CHARGES_EAT })
        // Three trades only: too few to say.
        assertTrue(R.review(OrderReview.Inputs(day, trades = orbDay().take(3))).flaws.none { it.category == OrderReview.Category.CHARGES_EAT })
    }

    @Test fun sameSideReEntriesAreCountedWithThoseRightAfterATarget() {
        val d = R.review(OrderReview.Inputs(day, trades = orbDay()))
        val f = d.flaws.single { it.category == OrderReview.Category.REENTRY }
        assertTrue("9 times today" in f.evidence, f.evidence)
        assertTrue("8 right after a target or lock" in f.evidence, f.evidence)
        assertTrue("the 8 re-entries made" in f.evidence, f.evidence)
        // A CE and a PE are different sides; two entries a side with no target before: nothing.
        val two = listOf(t("a", at(10, 0), at(10, 5), 10.0, why = "stop"), t("b", at(10, 40), at(10, 45), 10.0, why = "stop"),
            t("c", at(11, 0), at(11, 5), 10.0, symbol = "BANKNIFTY26OCT55000PE"))
        assertTrue(R.review(OrderReview.Inputs(day, trades = two)).flaws.none { it.category == OrderReview.Category.REENTRY })
        // Boss's own hand trades are never judged by a bot's rules.
        assertTrue(R.reentries(orbDay().map { it.copy(manual = true) }, day).isEmpty())
    }

    @Test fun theSideOfATrade() {
        assertEquals(1, R.sideOf(t("x", at(10, 0), at(10, 5), 0.0)))
        assertEquals(-1, R.sideOf(t("x", at(10, 0), at(10, 5), 0.0, symbol = "NIFTY26OCT25000PE")))
        assertEquals(-1, R.sideOf(t("x", at(10, 0), at(10, 5), 0.0).copy(long = false)))
    }

    /** 12 Oct's SILVERM call, bought 4243.50 by the MCX morning arm. */
    private val silver = OrderReview.Trade("s1", P, "MCX morning call", "SILVERM27OCT26230000CE", 1, 4243.5, at(9, 16), 3900.0, at(11, 23),
        exitWhy = "target filled", net = -1795.0, charges = 30.0)

    @Test fun aTargetUnderThePricePaidAStopReplacedByNothingAndAGuardOnAnArmsPosition() {
        val ps = listOf(
            OrderReview.Protect(at(10, 44), P, silver.symbol, long = true, stop = null, target = null, replaced = true),
            OrderReview.Protect(at(10, 45), P, silver.symbol, long = true, stop = null, target = 3900.0),
            OrderReview.Protect(at(9, 17), P, silver.symbol, long = true, stop = 3607.0, target = null, by = "Jarvis guard"),
        )
        val d = R.review(OrderReview.Inputs(day, trades = listOf(silver), protections = ps))
        val by = d.flaws.groupBy { it.category }
        val empty = by.getValue(OrderReview.Category.EMPTY_PROTECT).single()
        assertEquals(OrderReview.Severity.HIGH, empty.severity)
        assertTrue("nothing replaced it" in empty.evidence)
        val wrong = by.getValue(OrderReview.Category.WRONG_SIDE).single()
        assertTrue("3900.00 is under the 4243.50 paid" in wrong.evidence, wrong.evidence)
        assertEquals(OrderReview.Fix.NEEDS_FIX, wrong.fix)
        // The guard and the hand target both landed on the arm's position.
        val arm = by.getValue(OrderReview.Category.ARM_PROTECT)
        assertTrue(arm.any { "Jarvis guard's protection" in it.evidence && "MCX morning call's" in it.evidence })
        // A short's target above the price it sold at is the wrong side too; under it is right.
        val short = silver.copy(long = false, exit = null, exitMs = null)
        assertTrue(R.protections(listOf(OrderReview.Protect(at(10, 0), P, silver.symbol, false, null, 4300.0, by = "MCX morning call")), listOf(short))
            .any { it.category == OrderReview.Category.WRONG_SIDE })
        assertTrue(R.protections(listOf(OrderReview.Protect(at(10, 0), P, silver.symbol, false, null, 4000.0, by = "MCX morning call")), listOf(short)).isEmpty())
    }

    @Test fun theDiarysErrorsAreFlawsByKindAndAPaperSpeedWarningIsPaper() {
        val d = R.review(OrderReview.Inputs(day, errors = mapOf("missing required field: quantity" to 3, "x" to 1),
            speed = listOf("Paper (simulated orders): decision to sent 13.8 s", "Signal to sent 2.1 s")))
        val errs = d.flaws.filter { it.category == OrderReview.Category.ERRORS }
        assertEquals(OrderReview.Severity.HIGH, errs.first().severity)
        assertTrue("3 times" in errs.first().evidence)
        assertEquals(OrderReview.Severity.WARN, errs[1].severity)
        assertEquals(P, errs.first().venue)
        val slow = d.flaws.filter { it.category == OrderReview.Category.SLOW }
        assertEquals(listOf(P, OrderReview.Venue.LIVE), slow.map { it.venue })
    }

    @Test fun recurringChurnBecomesAProposalNeverALabChangeAndTheNewCategoriesAreKept() {
        val logs = (0 until 3).map { k ->
            val dd = day.minusDays(k.toLong())
            OrderReview.DayLog(dd, mapOf("ORB|p|chg" to OrderReview.Tally(1, 700.0)), mapOf("ORB|p" to 9), mapOf("ORB|p" to -700.0))
        }
        val p = R.patterns(logs).single()
        assertEquals(OrderReview.Category.CHARGES_EAT, p.category)
        val imp = R.improvement(p)
        assertEquals(OrderReview.Route.PROPOSAL, imp.route)
        assertTrue("nothing was changed" in imp.words)
        // The kept book reads the new categories back.
        val d = R.review(OrderReview.Inputs(day, trades = orbDay()))
        val back = R.decode(R.encode(OrderReview.Book(last = d)))
        assertEquals(d.flaws.map { it.category }.toSet(), back.last!!.flaws.map { it.category }.toSet())
        assertFalse(back.last!!.flaws.isEmpty())
    }
}
