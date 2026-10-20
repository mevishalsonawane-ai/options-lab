package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * 20 Oct, enhancement round 1 (idea 3): Liquidity's no-twin rule. On 19 Oct at 09:30 its 15-minute and 5-minute BANKNIFTY
 * books both held the same put and both were stopped (with FINNIFTY, -191.6 points in all). Since 12 Oct the two books do not
 * block each other on the index (research X1: about +16-18k a year); this rule only stops them doubling ONE contract: a book's
 * entry on an option another Liquidity book already holds on the same account becomes a would-be trade (kept here with its
 * price, never bought).
 *
 * PAPER only: on Boss's paper books it applies (a risk-lowering paper change, allowed on BANKNIFTY since Boss's 13 Oct unfreeze;
 * logged in the Autopilot timeline, undone in one tap). LIVE: shadow only (what it would refuse is recorded, nothing changes).
 * Each would-be trade is counted apart, so Liquidity's plain-rules record stays separable for the 26 Oct live decision; the
 * evening replay still replays every signal of both books. Pure.
 */
object LiquidityTwin {
    /** An open Liquidity position: its book (the arm's source), its contract and its account. */
    data class Holding(val book: String, val symbol: String, val live: Boolean)

    /** The open position that makes [book]'s entry on [symbol] (on Zerodha when [live]) a twin, or null when it is none. */
    fun twinOf(book: String, symbol: String, live: Boolean, open: List<Holding>): Holding? =
        open.firstOrNull { it.book != book && it.symbol.equals(symbol, ignoreCase = true) && it.live == live }

    /** The entry's result word the arm keeps (a would-be trade, not bought). */
    const val SKIPPED = "would_be_twin"

    /** One would-be trade: when, the book, the contract, the price at the signal, the account, whether it was [applied] (paper). */
    data class Rec(val atMs: Long, val day: LocalDate, val book: String, val symbol: String, val price: Double?, val live: Boolean,
                   val applied: Boolean, val by: String,
                   /** The signal's bar (its start): the go-live check's replay matches it, so a would-be trade is never a "miss". */
                   val signalBar: LocalDateTime? = null) {
        fun encode(): String = listOf(atMs, day, book, symbol, price?.let { "%.2f".format(Locale.ROOT, it) } ?: "", live, applied, by.replace('|', '/'),
            signalBar?.toString() ?: "").joinToString("|")
    }

    fun decode(line: String): Rec? = runCatching {
        val p = line.split('|')
        if (p.size < 8) return null
        Rec(p[0].toLong(), LocalDate.parse(p[1]), p[2], p[3], p[4].toDoubleOrNull(), p[5] == "true", p[6] == "true", p[7],
            p.getOrNull(8)?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() })
    }.getOrNull()

    /** The (book, signal bar) of each paper would-be trade the rule made: the go-live fidelity check leaves them out of its misses. */
    fun excused(recs: List<Rec>): Set<Pair<String, LocalDateTime>> =
        recs.filter { it.applied && !it.live && it.signalBar != null }.map { it.book to it.signalBar!! }.toSet()

    /** Kept at most (the newest). */
    const val KEEP = 200

    /** What the arm's why-not and the log say. */
    fun words(byLabel: String, symbol: String, live: Boolean): String =
        if (live) "no-twin rule (live, shadow: logged only): $byLabel already holds $symbol"
        else "no-twin rule: $byLabel already holds $symbol, so this entry is a would-be trade (not bought)"

    /** The Strategy lab card's and the diagnostics' line. */
    fun line(recs: List<Rec>, today: LocalDate, on: Boolean): String {
        val t = recs.filter { it.day == today }
        val paper = t.count { !it.live && it.applied }
        val logged = t.count { it.live || !it.applied }
        val mode = if (on) "on for paper" else "off"
        return "Liquidity no-twin rule ($mode; live in shadow): today $paper would-be ${if (paper == 1) "trade" else "trades"} on paper" +
            (if (logged > 0) ", $logged only logged" else "") + "; ${recs.count { !it.live && it.applied }} in all, counted apart from the plain-rules record."
    }
}
