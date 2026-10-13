package com.optionslab.ira

import com.optionslab.engine.Live
import java.time.Duration
import java.time.LocalTime

/**
 * The expiry-day paper ticket's retry when the entry bar is late (13 Oct, a NIFTY expiry: at 11:01:00 the data source had
 * not published its 11:00 bar yet, [Live.liveSnapshot] rightly refused with [Live.TooEarly], and nothing tried again, so
 * the day's ticket was lost).
 *
 * Only [Live.TooEarly] is tried again, and only once the entry time has passed (the bar is late, not the clock early):
 * every [EVERY_SECONDS] seconds up to [cap], [WINDOW_MINUTES] after the entry (11:06 for 11:00). Every try prices the
 * entry bar exactly as before - the rule in [Live.liveSnapshot] is unchanged, so a later bar is never labelled 11:00.
 * Every other error (a [com.optionslab.engine.ExpiryPut.SessionSkipped], no expiry today, the network) is passed on as
 * before. Pure: the caller reads the clock, waits and writes the diary.
 */
object TicketRetry {
    /** Seconds between tries. */
    const val EVERY_SECONDS = 30L

    /** The last try is this many minutes after the entry time. */
    const val WINDOW_MINUTES = 6L

    /** What to do after a try failed. */
    sealed interface Next {
        /** Try again in [seconds] (never past [cap]). */
        data class Wait(val seconds: Long) : Next
        /** Still too early at [cap]: stop and say the entry prices never arrived. */
        data object GiveUp : Next
        /** Not ours to retry: the error goes on as before. */
        data object PassOn : Next
    }

    /** The last moment a try is made for an [entry] time (never wraps past midnight). */
    fun cap(entry: LocalTime): LocalTime = entry.plusMinutes(WINDOW_MINUTES).let { if (it.isBefore(entry)) LocalTime.MAX else it }

    /** After a try failed with [e] at [now] (IST), for the strategy's [entry] time. */
    fun next(e: Throwable, now: LocalTime, entry: LocalTime): Next {
        if (e !is Live.TooEarly) return Next.PassOn
        // Before the entry time the signal does not exist yet by design (no bar is late): as before.
        if (now.isBefore(entry)) return Next.PassOn
        val left = Duration.between(now, cap(entry)).seconds
        if (left <= 0) return Next.GiveUp
        return Next.Wait(minOf(EVERY_SECONDS, left))
    }

    private fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
    private fun hms(t: LocalTime) = "%02d:%02d:%02d".format(t.hour, t.minute, t.second)

    /** The diary line for a retry: try [attempt] failed at [now]; the next one in [seconds]. */
    fun retryLine(attempt: Int, now: LocalTime, entry: LocalTime, seconds: Long): String =
        "Ticket: the ${hm(entry)} prices are not out yet at ${hms(now)} (try $attempt); trying again in $seconds s, until ${hm(cap(entry))}"

    /** The diary line when a try after the first one recorded the ticket. */
    fun recordedLine(attempt: Int, now: LocalTime, entry: LocalTime): String =
        "Ticket: recorded at ${hms(now)} on try $attempt, priced at the ${hm(entry)} bar"

    /** The diary line when a later try stopped with another error (named by its type only), passed on as before. */
    fun stoppedLine(attempt: Int, now: LocalTime, reason: String): String =
        "Ticket: try $attempt at ${hms(now)} stopped with $reason"

    /** The diary line when the tries stopped at [now] ([why]: [gaveUpText]). */
    fun gaveUpLine(now: LocalTime, why: String): String = "Ticket: given up at ${hms(now)}. $why"

    /** The diary line and notice text when the entry prices never came by [cap]. */
    fun gaveUpText(entry: LocalTime, attempts: Int): String =
        "The ${hm(entry)} prices never arrived: the data source had not published the ${hm(entry)} bar by ${hm(cap(entry))} " +
            "($attempts ${if (attempts == 1) "try" else "tries"}). No ticket today - it is never priced from older quotes."
}
