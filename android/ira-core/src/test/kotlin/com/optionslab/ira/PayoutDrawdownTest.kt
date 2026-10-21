package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 21 Oct, enhancement round 4 (idea 3): the payout-aware drawdown ([PayoutDrawdown]). */
class PayoutDrawdownTest {
    private val d1 = LocalDate.of(2026, 10, 19)
    private val d2 = LocalDate.of(2026, 10, 20)
    private val d3 = LocalDate.of(2026, 10, 21)

    @Test fun aDaysPayoutKeepsTheLatestReadAndTheFirstPeak() {
        var l = PayoutDrawdown.record(emptyList(), d1, 50_000.0, 500_000.0)
        l = PayoutDrawdown.record(l, d1, 80_000.0, 520_000.0)
        l = PayoutDrawdown.record(l, d1, 30_000.0, 510_000.0)          // part of the request cancelled
        assertEquals(listOf(PayoutDrawdown.Payout(d1, 30_000.0, 500_000.0)), l)
    }

    @Test fun aPayoutCancelledTheSameDayIsRemoved() {
        var l = PayoutDrawdown.record(emptyList(), d1, 50_000.0, 500_000.0)
        l = PayoutDrawdown.record(l, d2, 20_000.0, 500_000.0)
        l = PayoutDrawdown.record(l, d1, 0.0, 500_000.0)
        assertEquals(listOf(d2), l.map { it.day })
        assertEquals(l, PayoutDrawdown.record(l, d2, Double.NaN, 1.0), "an unknown read changes nothing")
    }

    @Test fun anUnknownPeakIsFilledByALaterRead() {
        var l = PayoutDrawdown.record(emptyList(), d1, 50_000.0, Double.NaN)
        l = PayoutDrawdown.record(l, d1, 50_000.0, 500_000.0)
        assertEquals(500_000.0, l.single().peak)
    }

    @Test fun nothingIsRecordedForZeroNegativeOrUnknownAmounts() {
        for (a in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY)) assertTrue(PayoutDrawdown.record(emptyList(), d1, a, 1.0).isEmpty())
    }

    @Test fun onlyPastDaysUnderTheSamePeakCount() {
        val l = listOf(PayoutDrawdown.Payout(d1, 50_000.0, 500_000.0), PayoutDrawdown.Payout(d2, 20_000.0, 500_000.0),
            PayoutDrawdown.Payout(d3, 10_000.0, 500_000.0))
        assertEquals(70_000.0, PayoutDrawdown.paidOutSince(l, 500_000.0, d3), "today's not yet (the opening balance drops tomorrow)")
        assertEquals(0.0, PayoutDrawdown.paidOutSince(l, 520_000.0, d3), "the peak rose since: already on the lower balance")
        assertEquals(0.0, PayoutDrawdown.paidOutSince(l, Double.NaN, d3))
        assertEquals(0.0, PayoutDrawdown.paidOutSince(l, 0.0, d3))
        assertEquals(0.0, PayoutDrawdown.paidOutSince(listOf(PayoutDrawdown.Payout(d1, 50_000.0, Double.NaN)), 500_000.0, d3))
        assertEquals(0.0, PayoutDrawdown.paidOutSince(emptyList(), 500_000.0, d3))
    }

    @Test fun aPayoutThatAloneExplainsTheLimitIsExplained() {
        // Peak 5L, Rs 1L paid out: equity 4L is 20% down (limit 10%), 0% counting the payout.
        val j = assertNotNull(PayoutDrawdown.judge(500_000.0, 400_000.0, 100_000.0, 10.0))
        assertTrue(j.reached); assertFalse(j.reachedLessPayouts); assertTrue(j.explained)
        assertEquals(20.0, j.pct, 1e-9); assertEquals(0.0, j.pctLessPayouts, 1e-9)
    }

    @Test fun aRealLossBesideAPayoutIsNotExplained() {
        // Rs 50k paid out but equity 3.9L: 12% down counting it - still a real loss over the limit.
        val j = assertNotNull(PayoutDrawdown.judge(500_000.0, 390_000.0, 50_000.0, 10.0))
        assertTrue(j.reached); assertTrue(j.reachedLessPayouts); assertFalse(j.explained)
        assertNull(PayoutDrawdown.words(j, on = true))
    }

    @Test fun noPayoutIsNeverExplained() {
        val j = assertNotNull(PayoutDrawdown.judge(500_000.0, 400_000.0, 0.0, 10.0))
        assertFalse(j.explained)
        val nan = assertNotNull(PayoutDrawdown.judge(500_000.0, 400_000.0, Double.NaN, 10.0))
        assertEquals(0.0, nan.paidOut)
    }

    @Test fun unknownsAreNotJudged() {
        assertNull(PayoutDrawdown.judge(Double.NaN, 1.0, 1.0, 10.0))
        assertNull(PayoutDrawdown.judge(0.0, 1.0, 1.0, 10.0))
        assertNull(PayoutDrawdown.judge(1.0, Double.NaN, 1.0, 10.0))
        assertNull(PayoutDrawdown.judge(1.0, 1.0, 1.0, 0.0))
    }

    @Test fun thePeakIsLoweredByThePayoutsNeverUnderEquityNeverRaised() {
        assertEquals(400_000.0, PayoutDrawdown.lowered(500_000.0, 100_000.0, 380_000.0))
        assertEquals(450_000.0, PayoutDrawdown.lowered(500_000.0, 100_000.0, 450_000.0), "never under equity")
        assertEquals(500_000.0, PayoutDrawdown.lowered(500_000.0, 100_000.0, 600_000.0), "never raised")
        assertEquals(500_000.0, PayoutDrawdown.lowered(500_000.0, 0.0, 380_000.0))
        assertEquals(400_000.0, PayoutDrawdown.lowered(500_000.0, 100_000.0, Double.NaN))
    }

    @Test fun usedPayoutsAreDroppedOthersKept() {
        val l = listOf(PayoutDrawdown.Payout(d1, 50_000.0, 500_000.0), PayoutDrawdown.Payout(d2, 20_000.0, 480_000.0),
            PayoutDrawdown.Payout(d3, 10_000.0, 500_000.0))
        assertEquals(listOf(l[1], l[2]), PayoutDrawdown.used(l, 500_000.0, d3))
    }

    @Test fun wordsSayShadowOrTheOfferPlainly() {
        val j = PayoutDrawdown.judge(500_000.0, 400_000.0, 100_000.0, 10.0)
        assertTrue(PayoutDrawdown.words(j, on = false)!!.contains("Shadow: logged only"))
        assertTrue(PayoutDrawdown.words(j, on = true)!!.contains("lower the peak by them in Bot settings (PIN)"))
        assertNull(PayoutDrawdown.words(null, on = true))
    }

    @Test fun encodeAndDecodeRoundTripAndSkipBadRows() {
        val l = listOf(PayoutDrawdown.Payout(d1, 50_000.0, 500_000.0), PayoutDrawdown.Payout(d2, 20_000.5, Double.NaN))
        val back = PayoutDrawdown.decode(PayoutDrawdown.encode(l))
        assertEquals(l[0], back[0]); assertEquals(20_000.5, back[1].amount); assertTrue(back[1].peak.isNaN())
        assertEquals(1, PayoutDrawdown.decode("bad;2026-10-19|10|5;2026-10-20|-1|5;x|1|1;2026-10-21|nan|1").size)
        assertTrue(PayoutDrawdown.decode(null).isEmpty())
        assertTrue(PayoutDrawdown.decode("").isEmpty())
    }

    @Test fun onlyTheLastDaysAreKept() {
        var l = emptyList<PayoutDrawdown.Payout>()
        for (i in 0 until PayoutDrawdown.KEEP + 5) l = PayoutDrawdown.record(l, d1.plusDays(i.toLong()), 1.0, 1.0)
        assertEquals(PayoutDrawdown.KEEP, l.size)
        assertEquals(d1.plusDays(5), l.first().day)
    }
}

/** 21 Oct, enhancement round 5 (idea 5): a payout confirmed by the next day's opening balance ([PayoutDrawdown.left]). */
class PayoutLeftTest {
    private val d1 = LocalDate.of(2026, 10, 19)
    private val d2 = LocalDate.of(2026, 10, 20)
    private val d3 = LocalDate.of(2026, 10, 21)
    private fun pay(day: LocalDate, rs: Double) = PayoutDrawdown.Payout(day, rs, 500_000.0)

    @Test fun aDaysBalancesKeepTheFirstOpeningAndTheLastExpectedNext() {
        var b = PayoutDrawdown.recordBalance(emptyList(), d1, 400_000.0, 400_000.0)
        b = PayoutDrawdown.recordBalance(b, d1, 401_000.0, 395_000.0)
        assertEquals(listOf(PayoutDrawdown.Balance(d1, 400_000.0, 395_000.0)), b)
        assertEquals(b, PayoutDrawdown.recordBalance(b, d1, Double.NaN, Double.NaN), "nothing known changes nothing")
        b = PayoutDrawdown.recordBalance(b, d1, 0.0, 394_000.0)                      // an opening of 0 is not known
        assertEquals(400_000.0, b.single().opening)
        assertEquals(394_000.0, b.single().expectedNext)
        val nan = PayoutDrawdown.recordBalance(emptyList(), d2, -1.0, 390_000.0)
        assertTrue(nan.single().opening.isNaN())
        var many = emptyList<PayoutDrawdown.Balance>()
        val n = PayoutDrawdown.KEEP_BALANCES + 5L
        for (i in 0L until n) many = PayoutDrawdown.recordBalance(many, d1.plusDays(i), 1.0, 1.0)
        assertEquals(PayoutDrawdown.KEEP_BALANCES, many.size)
        assertEquals(d1.plusDays(n - 1), many.last().day)
        assertTrue(PayoutDrawdown.KEEP_BALANCES >= PayoutDrawdown.KEEP, "kept at least as long as the payouts")
    }

    @Test fun aPayoutThatLeftIsConfirmed() {
        val b = listOf(PayoutDrawdown.Balance(d1, 400_000.0, 402_000.0), PayoutDrawdown.Balance(d2, 351_900.0, 351_900.0))
        assertEquals(PayoutDrawdown.Left.CONFIRMED, PayoutDrawdown.left(pay(d1, 50_000.0), b))   // dropped 50,100 (the payout and charges)
    }

    @Test fun aPayoutCancelledAfterItsDaysLastReadIsNotSeen() {
        val b = listOf(PayoutDrawdown.Balance(d1, 400_000.0, 402_000.0), PayoutDrawdown.Balance(d2, 401_900.0, 401_900.0))
        assertEquals(PayoutDrawdown.Left.NOT_SEEN, PayoutDrawdown.left(pay(d1, 50_000.0), b))
        // Half of it left: not confirmed either.
        val half = listOf(PayoutDrawdown.Balance(d1, 400_000.0, 402_000.0), PayoutDrawdown.Balance(d2, 377_000.0, 377_000.0))
        assertEquals(PayoutDrawdown.Left.NOT_SEEN, PayoutDrawdown.left(pay(d1, 50_000.0), half))
    }

    @Test fun theWholePayoutMustShowLessOnlyRupeeRounding() {
        val b = listOf(PayoutDrawdown.Balance(d1, 100_000.0, 100_000.0), PayoutDrawdown.Balance(d2, 98_000.0, 98_000.0))
        assertEquals(PayoutDrawdown.Left.CONFIRMED, PayoutDrawdown.left(pay(d1, 2_000.0), b))
        assertEquals(PayoutDrawdown.Left.CONFIRMED, PayoutDrawdown.left(pay(d1, 2_000.9), b))         // rounding
        assertEquals(PayoutDrawdown.Left.NOT_SEEN, PayoutDrawdown.left(pay(d1, 2_002.0), b))           // Rs 2 short: not confirmed
    }

    @Test fun noReadOfItsDayOrOfALaterOpeningIsUnknown() {
        assertEquals(PayoutDrawdown.Left.UNKNOWN, PayoutDrawdown.left(pay(d1, 50_000.0), emptyList()))
        assertEquals(PayoutDrawdown.Left.UNKNOWN, PayoutDrawdown.left(pay(d1, 50_000.0), listOf(PayoutDrawdown.Balance(d1, 400_000.0, 400_000.0))))
        assertEquals(PayoutDrawdown.Left.UNKNOWN, PayoutDrawdown.left(pay(d1, 50_000.0), listOf(PayoutDrawdown.Balance(d2, 350_000.0, 350_000.0))))
        assertEquals(PayoutDrawdown.Left.UNKNOWN, PayoutDrawdown.left(pay(d1, 50_000.0),
            listOf(PayoutDrawdown.Balance(d1, 400_000.0, Double.NaN), PayoutDrawdown.Balance(d2, 350_000.0, 350_000.0))))
    }

    @Test fun theFirstLaterOpeningIsUsedAcrossAWeekendOrHoliday() {
        // Friday's payout, the next read on Monday (the weekend has no reads).
        val fri = LocalDate.of(2026, 10, 16); val mon = LocalDate.of(2026, 10, 19)
        val b = listOf(PayoutDrawdown.Balance(fri, 400_000.0, 400_000.0), PayoutDrawdown.Balance(mon, 350_000.0, 350_000.0),
            PayoutDrawdown.Balance(LocalDate.of(2026, 10, 20), 500_000.0, 500_000.0))
        assertEquals(PayoutDrawdown.Left.CONFIRMED, PayoutDrawdown.left(pay(fri, 50_000.0), b))
    }

    @Test fun theSplitCountsOnlyPayoutsBeforeTodayUnderThePeak() {
        val payouts = listOf(pay(d1, 50_000.0), pay(d2, 20_000.0), pay(d3, 10_000.0), PayoutDrawdown.Payout(d1, 7_000.0, 480_000.0))
        val b = listOf(PayoutDrawdown.Balance(d1, 400_000.0, 400_000.0), PayoutDrawdown.Balance(d2, 350_000.0, 350_000.0))
        val s = PayoutDrawdown.paidOutSplit(payouts, b, 500_000.0, d3)
        assertEquals(50_000.0, s.confirmed)
        assertEquals(0.0, s.notSeen)
        assertEquals(20_000.0, s.unknown)          // d2's: no later opening read yet
        assertEquals(70_000.0, s.all)
        assertEquals(0.0, PayoutDrawdown.paidOutSplit(payouts, b, Double.NaN, d3).all)
        assertEquals(0.0, PayoutDrawdown.paidOutSplit(payouts, b, 0.0, d3).all)
    }

    @Test fun waitingWordsOnlyWhenAllWouldExplainButTheConfirmedDoNot() {
        val all = PayoutDrawdown.judge(500_000.0, 380_000.0, 50_000.0, 20.0)       // 24% raw, 14% less payouts
        val none = PayoutDrawdown.judge(500_000.0, 380_000.0, 0.0, 20.0)
        val split = PayoutDrawdown.Split(0.0, 50_000.0, 0.0)
        val on = PayoutDrawdown.waitingWords(all, none, split, on = true)
        assertNotNull(on)
        assertTrue(on.contains("has not shown Rs 50,000 of them leaving") && on.contains("Nothing is offered"))
        val shadow = PayoutDrawdown.waitingWords(all, none, PayoutDrawdown.Split(0.0, 0.0, 50_000.0), on = false)
        assertNotNull(shadow)
        assertTrue(shadow.contains("them leaving yet") && shadow.contains("Shadow: logged only"))
        val mixed = PayoutDrawdown.waitingWords(all, none, PayoutDrawdown.Split(0.0, 30_000.0, 20_000.0), on = true)
        assertTrue(mixed!!.contains("Rs 30,000 of them leaving (Rs 20,000 not read yet)"))
        assertNull(PayoutDrawdown.waitingWords(all, all, split, on = true), "the confirmed explain it: nothing to wait for")
        assertNull(PayoutDrawdown.waitingWords(none, none, split, on = true))
        assertNull(PayoutDrawdown.waitingWords(null, null, split, on = true))
    }

    @Test fun balancesEncodeAndDecode() {
        val b = listOf(PayoutDrawdown.Balance(d1, 400_000.0, 395_500.5), PayoutDrawdown.Balance(d2, Double.NaN, 350_000.0))
        val back = PayoutDrawdown.decodeBalances(PayoutDrawdown.encodeBalances(b))
        assertEquals(2, back.size)
        assertEquals(395_500.5, back[0].expectedNext)
        assertTrue(back[1].opening.isNaN())
        assertTrue(PayoutDrawdown.decodeBalances(null).isEmpty())
        assertEquals(listOf(d1), PayoutDrawdown.decodeBalances("x;2026-10-19|1|2;2026-10-20|NaN|NaN;bad|1|2;2026-10-21|1").map { it.day })
    }
}
