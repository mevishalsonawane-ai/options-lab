package com.optionslab.engine.orb

import java.util.Locale

/**
 * The arms Boss retired on 06 Oct 2026, after six years of real Dhan option and index minutes (Aug 2021 - 5 Oct 2026, the
 * app's own rules, 1 lot, real charges): ORB, ORB Fresh, ORB Sweep and Range Fade lost before and after charges, and no
 * change to them held up out of sample. "Keep only Liquidity on paper." Their rules stay (the backtests and the evening
 * replay still run them); they can no longer be switched on. An open position of one is still managed to its exit.
 *
 * The one-time change on this update ([migrate]): the retired arms still armed are switched off, and Liquidity 15+5's
 * books are switched back on, on paper only (never cleared for Zerodha: Live still takes Boss's PIN or fingerprint),
 * reversing the 06 Oct switch-off for Liquidity alone. Pure: no clock, no storage.
 */
object RetiredArms {
    /** One retired arm and what it lost on the real data, in rupees after charges. */
    data class Retired(val arm: Arm, val lost: Double)

    /** The four, with their real-data losses (research replay of 06 Oct: the maxloss baseline, current rules). */
    val ALL: List<Retired> = listOf(
        Retired(OrbRules.ORB, 977_000.0), Retired(OrbRules.ORB_FRESH, 253_000.0),
        Retired(SweepRules.ARM, 291_000.0), Retired(RangeFadeRules.ARM, 274_000.0),
    )
    private val BY_SOURCE = ALL.associateBy { it.arm.source }

    fun isRetired(source: String): Boolean = source in BY_SOURCE
    fun of(source: String): Retired? = BY_SOURCE[source]

    /** The one-time change's key in the arms' book (each runs once, never again). */
    const val MIGRATION = "retire_2026_10_06"
    /** What a retired arm switched off by [migrate] logs and shows. */
    const val SWITCHED_OFF = "switched off: lost on 6 years of real data (Boss's choice 06 Oct)"
    /** What Liquidity 15+5's books log when [migrate] switches them back on. */
    const val BACK_ON = "switched back on, paper only (Boss's choice 06 Oct)"

    /** "₹9.77 lakh" (two decimals), or "₹53,000" under a lakh. */
    fun rupees(x: Double): String = if (x >= 100_000) "₹" + String.format(Locale.ENGLISH, "%.2f", x / 100_000) + " lakh"
        else "₹" + String.format(Locale.ENGLISH, "%,.0f", x)

    /** The Retired section's line: "lost ₹9.77 lakh over 2021–2026 on real data; no fix held up out of sample". */
    fun line(r: Retired): String = "lost ${rupees(r.lost)} over 2021–2026 on real data; no fix held up out of sample"

    /** Why arming [r] is refused (every path: the switch, Jarvis, a plan, a restore). */
    fun refusal(r: Retired): String = "${r.arm.label} is retired: it ${line(r)}. Only Liquidity 15+5 stays on, on paper (Boss's choice 06 Oct)."

    /** What Jarvis says when asked about [r]. */
    fun answer(r: Retired): String = "${r.arm.label} is retired, Boss: it ${line(r)}. You switched it off on 06 Oct; its rules stay " +
        "for the backtests and the evening replay, but it cannot be armed."

    /**
     * The retired arms named in [text] (any case): "orb fresh", "orb sweep", "range fade", and "orb" said alone (not inside
     * one of the longer names). Each once, in [ALL]'s order.
     */
    fun named(text: String): List<Retired> {
        var t = " " + text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]+"), " ") + " "
        val out = LinkedHashSet<Retired>()
        fun take(rx: String, source: String) {
            val r = Regex(rx)
            if (r.containsMatchIn(t)) { out += BY_SOURCE.getValue(source); t = r.replace(t, " ") }
        }
        take(" orb fresh ", OrbRules.ORB_FRESH.source)
        take(" orb sweep ", SweepRules.ARM.source)
        take(" range fade ", RangeFadeRules.ARM.source)
        take(" orbs? ", OrbRules.ORB.source)
        return ALL.filter { it in out }
    }

    /** One arm's switch in the arms' book before [migrate]: [armed] now, and whether it holds an [open] position. */
    data class Switch(val source: String, val armed: Boolean, val open: Boolean = false)

    /**
     * One change [migrate] makes: [source] switched to [armed] (paper only: never cleared for Zerodha), and the [log] line
     * for the diagnostics.
     */
    data class Change(val source: String, val armed: Boolean, val log: String)

    /**
     * The one-time change, or an empty list when [done] (it has run in this book already). Every retired arm still armed is
     * switched off (an open position is still managed to its exit, and the line says so). Liquidity 15+5's [books] are
     * switched on, paper only - unless [restoring] (a restore not yet disarmed: everything restored stays off).
     */
    fun migrate(switches: List<Switch>, books: List<Arm>, done: Boolean, restoring: Boolean): List<Change> {
        if (done) return emptyList()
        val out = ArrayList<Change>()
        for (r in ALL) {
            val s = switches.firstOrNull { it.source == r.arm.source } ?: continue
            if (!s.armed) continue
            out += Change(r.arm.source, false, "${r.arm.label}: $SWITCHED_OFF" +
                if (s.open) "; its open position is still managed to its exit" else "")
        }
        if (!restoring) for (b in books) {
            if (switches.any { it.source == b.source && it.armed }) continue
            out += Change(b.source, true, "${b.label}: $BACK_ON")
        }
        return out
    }
}
