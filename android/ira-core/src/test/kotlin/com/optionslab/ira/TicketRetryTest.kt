package com.optionslab.ira

import com.optionslab.engine.ExpiryPut
import com.optionslab.engine.Live
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The expiry-day ticket's retry when the 11:00 bar is late (13 Oct, NIFTY expiry). */
class TicketRetryTest {
    private val entry = LocalTime.of(11, 0)
    private val early = Live.TooEarly("latest bar is 10:59, entry is 11:00; the signal does not exist yet")
    private fun t(h: Int, m: Int, s: Int = 0) = LocalTime.of(h, m, s)

    /** Plays the job: one try per step, the bar published at [arrives] (null: never); returns the tries and the outcome. */
    private fun play(start: LocalTime, arrives: LocalTime?): Pair<Int, String> {
        var now = start
        var tries = 0
        while (true) {
            tries++
            if (arrives != null && !now.isBefore(arrives)) return tries to "recorded at $now"
            when (val n = TicketRetry.next(early, now, entry)) {
                is TicketRetry.Next.Wait -> now = now.plusSeconds(n.seconds)
                TicketRetry.Next.GiveUp -> return tries to "gave up at $now"
                TicketRetry.Next.PassOn -> return tries to "passed on at $now"
            }
        }
    }

    @Test fun theCapIsSixMinutesAfterTheEntry() {
        assertEquals(t(11, 6), TicketRetry.cap(entry))
        assertEquals(t(9, 21), TicketRetry.cap(t(9, 15)))
        assertEquals(LocalTime.MAX, TicketRetry.cap(t(23, 58)), "never wraps past midnight")
    }

    @Test fun theBarArrivesOnTheSecondTry() {
        assertEquals(TicketRetry.Next.Wait(30), TicketRetry.next(early, t(11, 1), entry))
        assertEquals(2 to "recorded at 11:01:30", play(t(11, 1), arrives = t(11, 1, 20)))
    }

    @Test fun theBarNeverArrivesBeforeTheCap() {
        // 11:01:00, :30, ... 11:05:30, then a last try exactly at 11:06:00, then no more.
        assertEquals(11 to "gave up at 11:06", play(t(11, 1), arrives = null))
        assertEquals(TicketRetry.Next.Wait(10), TicketRetry.next(early, t(11, 5, 50), entry), "the last wait never passes the cap")
        assertEquals(TicketRetry.Next.GiveUp, TicketRetry.next(early, t(11, 6), entry))
        // A bar at 11:05:59 is still caught by the try at the cap.
        assertEquals(11 to "recorded at 11:06", play(t(11, 1), arrives = t(11, 5, 59)))
    }

    @Test fun onlyTooEarlyIsRetried() {
        assertEquals(TicketRetry.Next.PassOn, TicketRetry.next(ExpiryPut.SessionSkipped("no PE quote at strike 24000"), t(11, 1), entry))
        assertEquals(TicketRetry.Next.PassOn, TicketRetry.next(IllegalStateException("nearest expiry is not today"), t(11, 1), entry))
        assertEquals(TicketRetry.Next.PassOn, TicketRetry.next(java.io.IOException("offline"), t(11, 1), entry))
        assertEquals(TicketRetry.Next.PassOn, TicketRetry.next(Live.AlreadyRecorded("2026-10-13"), t(11, 1), entry))
        assertTrue(TicketRetry.next(early, t(11, 1), entry) is TicketRetry.Next.Wait)
    }

    @Test fun beforeTheEntryTimeNothingIsLateSoNothingIsRetried() {
        // An entry set later than the 11:01 job: the signal does not exist yet by design, as before.
        assertEquals(TicketRetry.Next.PassOn, TicketRetry.next(early, t(11, 1), t(11, 30)))
        assertEquals(TicketRetry.Next.PassOn, TicketRetry.next(early, t(10, 59, 59), entry))
        assertTrue(TicketRetry.next(early, entry, entry) is TicketRetry.Next.Wait, "at the entry minute itself the bar is due")
    }

    @Test fun aJobRunningLateGivesUpAtOnceAndNeverPricesALaterBarAsEleven() {
        // WorkManager ran it at 11:20: one try; too early still means the 11:00 prices never came - no retry.
        assertEquals(1 to "gave up at 11:20", play(t(11, 20), arrives = null))
        assertEquals(TicketRetry.Next.GiveUp, TicketRetry.next(early, t(14, 0), entry))
        // Late but the bar is there: the first try records it, as before.
        assertEquals(1 to "recorded at 11:20", play(t(11, 20), arrives = t(11, 0)))
    }

    @Test fun theWordsSayWhy() {
        assertEquals("Ticket: the 11:00 prices are not out yet at 11:01:00 (try 1); trying again in 30 s, until 11:06",
            TicketRetry.retryLine(1, t(11, 1), entry, 30))
        assertEquals("Ticket: recorded at 11:01:30 on try 2, priced at the 11:00 bar", TicketRetry.recordedLine(2, t(11, 1, 30), entry))
        assertEquals("Ticket: try 3 at 11:02:00 stopped with SessionSkipped", TicketRetry.stoppedLine(3, t(11, 2), "SessionSkipped"))
        val why = TicketRetry.gaveUpText(entry, 11)
        assertEquals("The 11:00 prices never arrived: the data source had not published the 11:00 bar by 11:06 (11 tries). " +
            "No ticket today - it is never priced from older quotes.", why)
        assertTrue(TicketRetry.gaveUpText(entry, 1).contains("(1 try)"))
        assertEquals("Ticket: given up at 11:06:00. $why", TicketRetry.gaveUpLine(t(11, 6), why))
    }
}
