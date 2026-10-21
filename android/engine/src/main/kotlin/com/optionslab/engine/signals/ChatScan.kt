package com.optionslab.engine.signals

import com.optionslab.engine.signals.CallParser.Reading
import java.time.LocalDate

/**
 * The Signal tester's chat picker (15 Oct 2026, Boss: "select the chat, or any simpler way"): a chat's recent messages read by
 * the rules ([CallParser]) and counted - how many were option calls, how many follow-ups - so the picker can show every chat a
 * source has seen with "looks like a trade-call channel" on the ones whose recent messages are mostly calls ([looksLikeCalls]).
 * Only these counts are kept for a chat Boss has not chosen: never its messages. Pure.
 */
object ChatScan {
    /** The most recent messages read per chat. */
    const val RECENT = 100
    /** "Looks like a trade-call channel": at least this many calls, and calls (with their follow-ups) this share of the messages. */
    const val MIN_CALLS = 5
    const val CALL_SHARE = 0.30

    data class Stats(val messages: Int, val calls: Int, val followUps: Int) {
        /** Calls and follow-ups as a share of the messages read (0 when none). */
        val share: Double get() = if (messages == 0) 0.0 else (calls + followUps).toDouble() / messages
        val looksLikeCalls: Boolean get() = calls >= MIN_CALLS && share >= CALL_SHARE
        /** "42 of 100 messages looked like calls". */
        val line: String get() = "$calls of $messages recent messages looked like calls" + if (followUps > 0) " ($followUps follow-ups)" else ""
    }

    /** [msgs] (text, day; oldest first): the last [RECENT] read and counted. */
    fun scan(msgs: List<Pair<String, LocalDate>>): Stats {
        val recent = msgs.takeLast(RECENT)
        var calls = 0; var follow = 0
        for ((text, day) in recent) {
            val r = CallParser.read(text, day)
            when {
                r.any { it is Reading.NewCall } -> calls++
                r.any { it is Reading.FollowUp } -> follow++
            }
        }
        return Stats(recent.size, calls, follow)
    }

    const val BADGE = "looks like a trade-call channel"
}
