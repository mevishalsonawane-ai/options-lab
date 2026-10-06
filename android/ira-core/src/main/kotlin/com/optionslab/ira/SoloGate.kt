package com.optionslab.ira

import java.time.LocalTime
import java.util.Locale

/**
 * When Solo may trade at all (Boss's paper diagnostics, 06 Oct): its paper trades were right 53-55% of the time (BankNifty
 * 49%), and its sureness ran backwards - when it said 70-100% it was right 43%. So Solo only places a trade when, for that
 * index, its rolling out-of-sample record (its last confident guesses, each scored only after its answer was known) is at
 * least [MIN_HIT] over at least [MIN_SCORED] of them, AND the band of sureness it is trading in has itself been right at
 * least [MIN_HIT] over at least [MIN_BAND] guesses; never on that index's expiry day (from the instrument master, as the
 * Hero arm and the expiry square-off read it), and never after [LAST_ENTRY] on any day. Otherwise it is "watching", and
 * says why. It only ever stops Solo's own paper trades: nothing here places, changes or closes anything. Pure.
 */
object SoloGate {
    const val MIN_HIT = 0.58
    const val MIN_SCORED = 200
    const val MIN_BAND = 50.0
    val LAST_ENTRY: LocalTime = LocalTime.of(14, 45)

    /**
     * One view's record: [scored] confident guesses in its rolling window and [hitRate] right of them; the band it would
     * trade in now, named [band] ("70-100% sure"), with [bandN] guesses (faded counts) and [bandHit] of them right.
     */
    data class Record(val scored: Int, val hitRate: Double, val band: String, val bandN: Double, val bandHit: Double)

    private fun pct(x: Double) = "%.0f%%".format(Locale.ENGLISH, x * 100)
    private fun n(x: Double) = "%.0f".format(Locale.ENGLISH, x)

    /** Why the index's overall record does not let Solo trade (null: it does). */
    fun overall(label: String, scored: Int, hitRate: Double): String? = when {
        scored < MIN_SCORED -> "watching: only $scored confident $label guesses scored so far (it trades from $MIN_SCORED, at ${pct(MIN_HIT)} right or better)"
        hitRate < MIN_HIT - 1e-9 -> "watching: its last $scored confident $label guesses were ${pct(hitRate)} right (it trades only from ${pct(MIN_HIT)})"
        else -> null
    }

    /** Why the band of sureness it would trade in does not let it (null: it does). */
    fun band(label: String, r: Record): String? = when {
        r.bandN < MIN_BAND - 1e-9 -> "watching: when ${r.band} on $label it has only ${n(r.bandN)} scored guesses (it trades a band from ${n(MIN_BAND)})"
        r.bandHit / r.bandN < MIN_HIT - 1e-9 -> "watching: when ${r.band} on $label it was right only ${pct(r.bandHit / r.bandN)} of ${n(r.bandN)} times " +
            "(it trades a band only from ${pct(MIN_HIT)})"
        else -> null
    }

    /** Why Solo may not trade on this record now (null: it may): the index's record first, then the band's. */
    fun why(label: String, r: Record): String? = overall(label, r.scored, r.hitRate) ?: band(label, r)

    /**
     * Why Solo may not buy an option on [label] at [now] (null: it may). [expiryDay]: today is that index's expiry by the
     * instrument master; null when the master could not be read - then it does not trade (the careful side).
     */
    fun clock(label: String, now: LocalTime, expiryDay: Boolean?): String? = when {
        !now.isBefore(LAST_ENTRY) -> "watching: no new trades after ${LAST_ENTRY.toString().take(5)}"
        expiryDay == null -> "watching: today's $label expiry could not be read from the instrument master, so no trade"
        expiryDay -> "watching: $label expires today - Solo never buys options on an expiry day"
        else -> null
    }
}
