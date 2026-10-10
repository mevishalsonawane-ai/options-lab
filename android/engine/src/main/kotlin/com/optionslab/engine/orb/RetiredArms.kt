package com.optionslab.engine.orb

import java.time.LocalDate
import java.util.Locale

/**
 * The arms Boss retired on 06 Oct 2026, after six years of real Dhan option and index minutes (Aug 2021 - 5 Oct 2026, the
 * app's own rules, 1 lot, real charges): ORB, ORB Fresh, ORB Sweep and Range Fade lost before and after charges, and no
 * change to them held up out of sample - and un-retired on 07 Oct 2026 (Boss's explicit choice): they are normal arms
 * again, each with its own switch, on paper. Their record ([ALL], [line], [history]) stays as information, never a block.
 *
 * Two one-time changes, in this order: [migrate] (06 Oct: the four still armed switched off, Liquidity 15+5's books
 * switched back on, on paper only) and [unretire] (07 Oct: the four switched back on, on paper only). Neither ever clears
 * an arm for Zerodha: Live still takes Boss's PIN or fingerprint. Pure: no clock, no storage.
 */
object RetiredArms {
    /** One of the four and what it lost on the real data, in rupees after charges. */
    data class Retired(val arm: Arm, val lost: Double)

    /** The four, with their real-data losses (research replay of 06 Oct: the maxloss baseline, current rules). */
    val ALL: List<Retired> = listOf(
        Retired(OrbRules.ORB, 977_000.0), Retired(OrbRules.ORB_FRESH, 253_000.0),
        Retired(SweepRules.ARM, 291_000.0), Retired(RangeFadeRules.ARM, 274_000.0),
    )
    private val BY_SOURCE = ALL.associateBy { it.arm.source }

    /** One of the four (its record), or null. Information only: since 07 Oct none of them is refused anything. */
    fun of(source: String): Retired? = BY_SOURCE[source]

    /** The 06 Oct change's key in the arms' book (each runs once, never again). */
    const val MIGRATION = "retire_2026_10_06"
    /** What an arm switched off by [migrate] logs and shows. */
    const val SWITCHED_OFF = "switched off: lost on 6 years of real data (Boss's choice 06 Oct)"
    /** What Liquidity 15+5's books log when [migrate] switches them back on. */
    const val BACK_ON = "switched back on, paper only (Boss's choice 06 Oct)"

    /** The 07 Oct change's key in the arms' book ([unretire]: runs once, never again). */
    const val UNRETIRE = "unretire_2026_10_07"
    /** What an arm switched back on by [unretire] logs and shows. */
    const val UNRETIRED = "switched back on, paper only (Boss un-retired it 07 Oct 2026)"
    /** The day Boss un-retired the four: Jarvis's cut-off judges them only on paper trades entered from it. */
    val UNRETIRED_ON: LocalDate = LocalDate.of(2026, 10, 7)

    /** "₹9.77 lakh" (two decimals), or "₹53,000" under a lakh. */
    fun rupees(x: Double): String = if (x >= 100_000) "₹" + String.format(Locale.ENGLISH, "%.2f", x / 100_000) + " lakh"
        else "₹" + String.format(Locale.ENGLISH, "%,.0f", x)

    /** Its record: "lost ₹9.77 lakh over 2021–2026 on real data; no fix held up out of sample". */
    fun line(r: Retired): String = "lost ${rupees(r.lost)} over 2021–2026 on real data; no fix held up out of sample"

    /** What Jarvis says when asked about [r]: back on paper since Boss un-retired it, and its record as information. */
    fun history(r: Retired): String = "${r.arm.label} is back on paper, Boss: you un-retired it on 07 Oct 2026. For the record, it " +
        "${line(r)}. Its own rules are unchanged, and Zerodha still takes your PIN or fingerprint."

    /**
     * The four named in [text] (any case): "orb fresh", "orb sweep", "range fade", and "orb" said alone (not inside one of
     * the longer names). Each once, in [ALL]'s order.
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

    /** One arm's switch in the arms' book before a change: [armed] now, and whether it holds an [open] position. */
    data class Switch(val source: String, val armed: Boolean, val open: Boolean = false)

    /**
     * One change [migrate] or [unretire] makes: [source] switched to [armed] (paper only: never cleared for Zerodha), and
     * the [log] line for the diagnostics.
     */
    data class Change(val source: String, val armed: Boolean, val log: String)

    /**
     * The 06 Oct change, or an empty list when [done] (it has run in this book already). Every one of the four still armed
     * is switched off (an open position is still managed to its exit, and the line says so). Liquidity 15+5's [books] are
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

    /**
     * The 07 Oct change (Boss un-retired the four), or an empty list when [done] (it has run in this book already) or
     * [restoring] (a restore not yet disarmed: everything restored stays off). Each of the four not armed now is switched
     * on, automatic, PAPER ONLY (never cleared for Zerodha); one already armed is left exactly as it is. Nothing else changes.
     */
    fun unretire(switches: List<Switch>, done: Boolean, restoring: Boolean): List<Change> {
        if (done || restoring) return emptyList()
        return ALL.filter { r -> switches.none { it.source == r.arm.source && it.armed } }
            .map { Change(it.arm.source, true, "${it.arm.label}: $UNRETIRED") }
    }
}
