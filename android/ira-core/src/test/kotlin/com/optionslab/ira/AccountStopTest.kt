package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 20 Oct, enhancement round 1 (idea 4): Boss's account stop rule over the whole Zerodha account. */
class AccountStopTest {
    private val d0 = LocalDate.of(2026, 10, 20)

    @Test fun noDaysIsNotStoppedAndSaysSo() {
        val st = AccountStop.status(emptyList(), d0)
        assertFalse(st.stopped); assertNull(AccountStop.refusal(st))
        assertTrue(AccountStop.line(st, on = false, shadowRefused = 0).contains("no Zerodha day recorded yet"))
        assertTrue(AccountStop.line(st, on = false, shadowRefused = 0).contains("in shadow: logged only"))
    }

    @Test fun aLaterReadOfTheSameDayReplacesTheEarlier() {
        var days = AccountStop.record(emptyList(), d0, -2_000.0)
        days = AccountStop.record(days, d0, -5_000.0)
        assertEquals(listOf(AccountStop.Day(d0, -5_000.0)), days)
        assertEquals(days, AccountStop.record(days, d0.plusDays(1), Double.NaN), "a figure that is not a number is never recorded")
    }

    @Test fun theMonthLimitIsReachedAt13k() {
        var days = emptyList<AccountStop.Day>()
        listOf(-4_000.0, -4_000.0, -4_999.0).forEachIndexed { i, x -> days = AccountStop.record(days, d0.plusDays(i.toLong()), x) }
        assertFalse(AccountStop.status(days, d0.plusDays(2)).stopped, "-12,999 is under the limit")
        days = AccountStop.record(days, d0.plusDays(2), -5_000.0)
        val st = AccountStop.status(days, d0.plusDays(2))
        assertTrue(st.stopped)
        val why = AccountStop.refusal(st)!!
        assertTrue(why.startsWith("refused: the account stop rule (Oct net -Rs 13,000"), why)
        assertTrue(AccountStop.line(st, on = true, shadowRefused = 0).contains("REACHED on 2026-10-22"))
    }

    @Test fun theDrawdownLimitCountsFromTheBestAcrossMonths() {
        // +10k in October, then -12k, -12k, -12k, -5k in November... each month under -13k is avoided by splitting across months.
        val days = listOf(
            AccountStop.Day(LocalDate.of(2026, 10, 26), 10_000.0),
            AccountStop.Day(LocalDate.of(2026, 10, 30), -12_000.0),
            AccountStop.Day(LocalDate.of(2026, 11, 2), -12_000.0),
            AccountStop.Day(LocalDate.of(2026, 12, 1), -12_000.0),
            AccountStop.Day(LocalDate.of(2027, 1, 4), -4_000.0),
        )
        val st = AccountStop.status(days, LocalDate.of(2027, 1, 4))
        assertTrue(st.stopped, "-40k from the +10k best")
        assertTrue(AccountStop.refusal(st)!!.contains("from the account's best"), AccountStop.refusal(st))
        assertEquals(-40_000.0, st.drawdown, 1e-6)
    }

    @Test fun daysBeforeTheVettingStartAreNotCounted() {
        val st = AccountStop.status(listOf(AccountStop.Day(LocalDate.of(2026, 10, 1), -50_000.0)), d0)
        assertFalse(st.stopped); assertEquals(0, st.trades)
    }

    @Test fun theShadowLineSaysWhatWouldHaveBeenRefused() {
        val st = AccountStop.status(listOf(AccountStop.Day(d0, -14_000.0)), d0)
        assertTrue(AccountStop.line(st, on = false, shadowRefused = 2).contains("2 live entries would have been refused today"))
        assertFalse(AccountStop.line(st, on = true, shadowRefused = 2).contains("would have been refused"))
    }

    @Test fun encodeDecodeRoundTripsAndSkipsJunk() {
        val days = listOf(AccountStop.Day(d0, -1_234.5), AccountStop.Day(d0.plusDays(1), 99.0))
        assertEquals(days, AccountStop.decode(AccountStop.encode(days)))
        assertEquals(listOf(AccountStop.Day(d0, 1.0)), AccountStop.decode("junk\n2026-13-40=5\n2026-10-20=x\n2026-10-20=1\n=3"))
        assertTrue(AccountStop.decode(null).isEmpty())
        // At most KEEP days, the newest.
        val many = (0 until AccountStop.KEEP + 10).fold(emptyList<AccountStop.Day>()) { acc, i -> AccountStop.record(acc, d0.plusDays(i.toLong()), 1.0) }
        assertEquals(AccountStop.KEEP, many.size); assertEquals(d0.plusDays(10), many.first().day)
    }

    /** Boss's restart (with his PIN) counts from the next day: the old days no longer stop it. */
    @Test fun aRestartCountsOnlyFromTheNextDay() {
        val days = listOf(AccountStop.Day(d0, -14_000.0))
        assertTrue(AccountStop.status(days, d0).stopped)
        val from = AccountStop.restartFrom(d0)
        assertEquals(d0.plusDays(1), from)
        assertFalse(AccountStop.status(days, d0, from).stopped)
        // A later month's own losses still stop it.
        val later = AccountStop.record(days, d0.plusDays(1), -13_500.0)
        assertTrue(AccountStop.status(later, d0.plusDays(1), from).stopped)
    }

    @Test fun aReachedRuleStaysReachedUntilARestartPastIt() {
        val notNow = AccountStop.status(listOf(AccountStop.Day(d0, -5_000.0)), d0)
        val r = d0 to "Oct net -Rs 13,500 (limit -Rs 13,000 a month)"
        val held = AccountStop.latched(notNow, r, since = null)
        assertTrue(held.stopped); assertEquals(d0, held.day)
        assertFalse(AccountStop.latched(notNow, r, since = d0.plusDays(1)).stopped, "restarted past it")
        assertFalse(AccountStop.latched(notNow, null, null).stopped)
        assertEquals(r, AccountStop.decodeReached(AccountStop.encodeReached(r)))
        assertNull(AccountStop.decodeReached(null)); assertNull(AccountStop.decodeReached("junk"))
    }

    /** 20 Oct, enhancement round 2 (idea 6): the day's figure less the day's charges, as the arms' own rule reads it. */
    @Test fun theDaysFigureIsAfterCharges() {
        assertEquals(-12_600.0, AccountStop.afterCharges(-12_000.0, 600.0))
        assertEquals(400.0, AccountStop.afterCharges(1_000.0, 600.0), "charges larger than a small gain turn it down, never up")
        assertEquals(-12_000.0, AccountStop.afterCharges(-12_000.0, null), "charges not known: before charges, as before")
        assertEquals(-12_000.0, AccountStop.afterCharges(-12_000.0, -50.0), "odd charges ignored")
        assertEquals(-12_000.0, AccountStop.afterCharges(-12_000.0, Double.NaN))
        assertNull(AccountStop.afterCharges(Double.NaN, 10.0))
        assertEquals(-0.01, AccountStop.afterCharges(0.0, 0.011), "to the paisa")
        // The month's -Rs 13,000 is reached on the net figure: -12,600 before and -13,100 after charges.
        val d = LocalDate.of(2026, 10, 20)
        val days = AccountStop.record(emptyList(), d, AccountStop.afterCharges(-12_600.0, 500.0)!!)
        assertTrue(AccountStop.status(days, d).stopped)
        assertTrue(AccountStop.line(AccountStop.status(days, d), false, 0).contains("after charges"))
    }
}
