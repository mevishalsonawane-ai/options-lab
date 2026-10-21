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

    @Test fun aDaysPayoutKeepsTheLargestReadAndTheFirstPeak() {
        var l = PayoutDrawdown.record(emptyList(), d1, 50_000.0, 500_000.0)
        l = PayoutDrawdown.record(l, d1, 30_000.0, 510_000.0)
        l = PayoutDrawdown.record(l, d1, 80_000.0, 520_000.0)
        assertEquals(listOf(PayoutDrawdown.Payout(d1, 80_000.0, 500_000.0)), l)
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
