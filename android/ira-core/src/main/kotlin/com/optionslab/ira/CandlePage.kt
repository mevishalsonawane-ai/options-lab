package com.optionslab.ira

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Candles page's plain rules (20 Oct 2026, enhancement round 2; Research → Candles): its "as of" line (when the shown
 * reads, table and lessons were last refreshed), its instrument chips and what each chip lists, and its lesson rows with
 * their instrument. Pure: no clock (the caller gives "now"), no file. The page only shows: nothing here trades.
 */
object CandlePage {
    /** The chip that lists every instrument. */
    const val ALL = "All"

    /** The chips' order: the indices first, then MCX's futures as the agent reads them; any other instrument after, A to Z. */
    val ORDER = listOf("NIFTY", "BANKNIFTY", "SENSEX", "CRUDEOIL", "NATURALGAS", "GOLDM", "SILVERM")

    /**
     * While a market it reads is open, a last batch older than this is late (a batch runs every 5 minutes, every 10 with the
     * battery saver: two missed saver batches).
     */
    const val STALE_MS = 20 * 60_000L

    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(IST)
    private val DAY_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(IST)

    /** One lesson or note on the page, with the instrument it is about (the chips filter by it). */
    data class Line(val inst: String, val text: String)

    /**
     * The "as of" line: when the agent last read candles ([lastMs], its last batch; null: never), said against [nowMs].
     * [open]: NSE or MCX is open now (a late batch is said only then). [released]: let go to free memory.
     */
    fun asOf(lastMs: Long?, nowMs: Long, open: Boolean, released: Boolean = false): String {
        if (released && lastMs == null) return "As of: nothing in memory (let go to free memory; it reads again with its next batch)."
        if (lastMs == null) return "As of: nothing read yet (its first batch runs while NSE or MCX is open)."
        val at = Instant.ofEpochMilli(lastMs)
        val sameDay = at.atZone(IST).toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(IST).toLocalDate()
        val head = "As of " + (if (sameDay) HM else DAY_HM).format(at)
        val age = nowMs - lastMs
        return when {
            open && age > STALE_MS -> "$head (${ago(age)} ago: late, the next batch is overdue; what is shown may be stale)."
            !open -> "$head (markets closed: the last batch's reads)."
            else -> "$head."
        }
    }

    /** [ms] in plain words: "25 min", "3 h 5 min". */
    fun ago(ms: Long): String {
        val m = (ms / 60_000L).coerceAtLeast(0L)
        return if (m < 60) "$m min" else "${m / 60} h" + (if (m % 60 == 0L) "" else " ${m % 60} min")
    }

    /** The chips for [insts] (what the page shows): [ALL] first, then each instrument once, in [ORDER] then A to Z. */
    fun chips(insts: Collection<String>): List<String> {
        val have = insts.map { it.trim().uppercase(Locale.ENGLISH) }.filter { it.isNotEmpty() && it != ALL.uppercase(Locale.ENGLISH) }.toSet()
        return listOf(ALL) + ORDER.filter { it in have } + (have - ORDER.toSet()).sorted()
    }

    /** The chip to show picked: [pick] while it is still one of [chips], else [ALL] (an instrument gone from the page). */
    fun picked(pick: String?, chips: List<String>): String = pick?.takeIf { it in chips } ?: ALL

    /** Whether a row about [inst] is listed under the chip [pick]. */
    fun shows(pick: String?, inst: String): Boolean = pick == null || pick == ALL || inst.equals(pick, ignoreCase = true)

    /** A lesson's row: its words as the page says them, with its instrument (from its key "nifty:15:shape"). */
    fun lessonLine(r: AgentLessons.CandleResult): Line =
        Line(r.key.substringBefore(':').uppercase(Locale.ENGLISH), "${r.name}: ${if (r.agrees) "agrees with" else "differs from"} the research (${r.n} counted)")
}
