package com.optionslab.ira

import com.optionslab.engine.orb.LiquidityRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * Liquidity's fill quality (Boss, 12 Oct; research h24 and HUNT_FINAL #4): at every Liquidity signal and every fill, paper
 * and live, the option's book as the stream had it - best bid and ask, the spread, the quantity at the best bid and offer
 * and the five levels' sums (full mode) - the signal's time against its bar's close, when the order was sent, the fill
 * against the signal's price (% and rupees) and whether the quote was stale. It feeds the go-live checklist (entry within
 * 10 s of the bar close, fill at most 0.5% over the signal, half-spread at most 0.3%), the row's details, the order review,
 * the diagnostics and Jarvis's "how are Liquidity fills". Records only: it never changes an order. Pure.
 */
object FillQuality {
    /** Kept at most (the oldest dropped). */
    const val KEEP = 500
    /** A quote older than this at the moment is stale. */
    const val STALE_MS = 5_000L

    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    enum class Kind(val key: String, val word: String) {
        SIGNAL("s", "signal"), ENTRY("e", "entry"), EXIT("x", "exit");
        companion object { fun of(k: String): Kind? = entries.firstOrNull { it.key == k } }
    }

    /**
     * One record: [kind] at [atMs] by [book] (its source: "liquidity15") on [symbol], [live] or paper; [price] the signal's
     * price (a signal: the option's last price then) or the fill's; [signalPrice] what a fill meant to pay; the book then
     * ([bid], [ask], [bidQty], [askQty], the five levels' [bid5] / [ask5], [quoteAgeMs]: null when the stream had no quote);
     * [barCloseMs] the signal bar's close, [sentMs] when the order went, [qty] units filled.
     */
    data class Rec(
        val kind: Kind, val atMs: Long, val book: String, val symbol: String, val live: Boolean, val price: Double?,
        val signalPrice: Double? = null, val bid: Double? = null, val ask: Double? = null, val bidQty: Long? = null, val askQty: Long? = null,
        val bid5: Long? = null, val ask5: Long? = null, val quoteAgeMs: Long? = null, val barCloseMs: Long? = null, val sentMs: Long? = null,
        val qty: Int = 0,
    ) {
        val stale: Boolean get() = quoteAgeMs == null || quoteAgeMs > STALE_MS
        val mid: Double? get() = if (bid != null && ask != null && bid > 0 && ask >= bid) (bid + ask) / 2 else null
        /** The whole spread as % of the mid. */
        val spreadPct: Double? get() = mid?.let { m -> (ask!! - bid!!) / m * 100 }
        /** Half the spread as % of the mid (the research's measure, h24). */
        val halfSpreadPct: Double? get() = spreadPct?.let { it / 2 }
        /** Milliseconds after the signal bar's close (the decision for a signal, the fill for a fill). */
        val lagMs: Long? get() = barCloseMs?.let { atMs - it }
        /** The fill against the signal's price, % (positive: paid more / sold for more). */
        val vsSignalPct: Double? get() = if (price != null && signalPrice != null && signalPrice > 0) (price - signalPrice) / signalPrice * 100 else null
        val vsSignalRs: Double? get() = if (price != null && signalPrice != null) (price - signalPrice) * qty else null
        val index: String get() = indexOf(book)
        val bankNifty: Boolean get() = index == "BANKNIFTY"
        val day: LocalDate get() = Instant.ofEpochMilli(atMs).atZone(IST).toLocalDate()
    }

    private fun bookArm(book: String) = (LiquidityRules.BOOKS + LiquidityRules.ARM).firstOrNull { it.source == book }
    fun indexOf(book: String): String = bookArm(book)?.let { LiquidityRules.underlyingOf(it) } ?: "?"
    fun bookWords(book: String): String = bookArm(book)?.let { "${LiquidityRules.minutesOf(it)}m ${LiquidityRules.underlyingOf(it)}" } ?: book

    // ---- storage lines ----------------------------------------------------------------------------------------------------

    private fun n(x: Double?) = x?.let { "%.4f".format(Locale.ENGLISH, it).trimEnd('0').trimEnd('.') } ?: "-"
    private fun n(x: Long?) = x?.toString() ?: "-"
    private fun d(s: String) = s.takeIf { it != "-" }?.toDoubleOrNull()
    private fun l(s: String) = s.takeIf { it != "-" }?.toLongOrNull()

    fun encode(r: Rec): String = listOf("q1", r.kind.key, r.atMs.toString(), r.book.replace('|', '/'), r.symbol.replace('|', '/'), if (r.live) "1" else "0",
        n(r.price), n(r.signalPrice), n(r.bid), n(r.ask), n(r.bidQty), n(r.askQty), n(r.bid5), n(r.ask5), n(r.quoteAgeMs), n(r.barCloseMs),
        n(r.sentMs), r.qty.toString()).joinToString("|")

    fun decode(line: String): Rec? {
        val f = line.trim().split('|')
        if (f.size < 18 || f[0] != "q1") return null
        val kind = Kind.of(f[1]) ?: return null
        return Rec(kind, f[2].toLongOrNull() ?: return null, f[3], f[4], f[5] == "1", d(f[6]), d(f[7]), d(f[8]), d(f[9]), l(f[10]), l(f[11]),
            l(f[12]), l(f[13]), l(f[14]), l(f[15]), l(f[16]), f[17].toIntOrNull() ?: 0)
    }

    // ---- words ----------------------------------------------------------------------------------------------------------

    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun pct0(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun px(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun secs(ms: Long) = "%.1f s".format(Locale.ENGLISH, ms / 1000.0)
    private fun qty(x: Long) = "%,d".format(Locale.ENGLISH, x)
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(IST).let { "%d %s %02d:%02d:%02d".format(Locale.ENGLISH, it.dayOfMonth,
        it.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }, it.hour, it.minute, it.second) }

    fun median(xs: List<Double>): Double? = xs.sorted().let { s -> if (s.isEmpty()) null else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** One record in a line for the row's details. */
    fun line(r: Rec): String {
        val parts = ArrayList<String>()
        parts += "${time(r.atMs)} · ${bookWords(r.book)} ${r.kind.word} · ${if (r.live) "live" else "paper"}"
        if (r.bid != null && r.ask != null) parts += "bid ${px(r.bid)} / ask ${px(r.ask)}" + (r.spreadPct?.let { " (spread ${pct0(it)})" } ?: "")
        if (r.bidQty != null || r.askQty != null) parts += "top ${r.bidQty?.let(::qty) ?: "-"} / ${r.askQty?.let(::qty) ?: "-"}" +
            (if (r.bid5 != null || r.ask5 != null) " (5 levels ${r.bid5?.let(::qty) ?: "-"} / ${r.ask5?.let(::qty) ?: "-"})" else "")
        r.lagMs?.let { parts += "${secs(it)} after the bar close" }
        if (r.sentMs != null) parts += "sent ${secs(r.sentMs - (r.barCloseMs ?: r.sentMs))} after the close"
        if (r.kind == Kind.SIGNAL) r.price?.let { parts += "price ${px(it)}" }
        else if (r.price != null) parts += "fill ${px(r.price)}" + (r.signalPrice?.let { s -> " vs ${px(s)} at the signal" } ?: "") +
            (r.vsSignalPct?.let { " (${pct(it)}" + (r.vsSignalRs?.let { x -> ", ${rs(x)}" } ?: "") + ")" } ?: "")
        parts += when { r.quoteAgeMs == null -> "no stream quote"; r.stale -> "quote stale (${secs(r.quoteAgeMs)} old)"; else -> "quote fresh" }
        return parts.joinToString(" · ")
    }

    /** The figures the checklist and the summaries use, over [recs] (BANKNIFTY's books when [bankNifty]) from [from]. */
    data class Stats(val signals: Int, val entries: Int, val exits: Int, val halfSpread: Double?, val worstHalfSpread: Double?,
                     val entryLagS: Double?, val worstEntryLagS: Double?, val fillVsSignal: Double?, val worstFillVsSignal: Double?, val stale: Int)

    fun stats(recs: List<Rec>, from: LocalDate = Vetting.START, bankNifty: Boolean = true): Stats {
        val mine = recs.filter { !it.day.isBefore(from) && (!bankNifty || it.bankNifty) }
        val sig = mine.filter { it.kind == Kind.SIGNAL }
        val ent = mine.filter { it.kind == Kind.ENTRY }
        val spreads = sig.filter { !it.stale }.mapNotNull { it.halfSpreadPct }
        val lags = ent.mapNotNull { it.lagMs?.let { ms -> ms / 1000.0 } }
        val slips = ent.mapNotNull { it.vsSignalPct }
        return Stats(sig.size, ent.size, mine.count { it.kind == Kind.EXIT }, median(spreads), spreads.maxOrNull(), median(lags), lags.maxOrNull(),
            median(slips), slips.maxOrNull(), mine.count { it.stale })
    }

    /** "Liquidity BANKNIFTY fills since 13 Oct: ..." */
    fun summary(recs: List<Rec>, from: LocalDate = Vetting.START): String {
        val s = stats(recs, from)
        if (s.signals + s.entries + s.exits == 0) return "Liquidity BANKNIFTY fills since ${from.dayOfMonth} Oct: none recorded yet."
        val parts = ArrayList<String>()
        parts += "${s.signals} signal${if (s.signals == 1) "" else "s"}, ${s.entries} entr${if (s.entries == 1) "y" else "ies"}, ${s.exits} exit${if (s.exits == 1) "" else "s"}"
        s.halfSpread?.let { parts += "median half-spread ${pct0(it)} (worst ${pct0(s.worstHalfSpread!!)})" }
        s.entryLagS?.let { parts += "median entry %.1f s after the bar close (worst %.1f s)".format(Locale.ENGLISH, it, s.worstEntryLagS!!) }
        s.fillVsSignal?.let { parts += "median fill ${pct(it)} against the signal (worst ${pct(s.worstFillVsSignal!!)})" }
        parts += "${s.stale} stale quote${if (s.stale == 1) "" else "s"}"
        return "Liquidity BANKNIFTY fills since ${from.dayOfMonth} Oct: " + parts.joinToString(" · ") + "."
    }

    /** The diagnostics' line: every index, counts and the BANKNIFTY figures. */
    fun diagLine(recs: List<Rec>): String = "Liquidity fill quality: ${recs.size} kept (" +
        LiquiditySplit.INDICES.joinToString(", ") { i -> "$i ${recs.count { it.index == i }}" } + "); " + summary(recs)

    /** Jarvis's answer to "how are Liquidity fills". */
    fun answer(recs: List<Rec>): String {
        val s = stats(recs)
        val head = "Boss, " + summary(recs).replaceFirstChar { it.lowercase() }
        if (s.entries == 0 && s.signals == 0) return "$head I record the bid, ask, depth and timing at every Liquidity signal and fill."
        val checks = ArrayList<String>()
        s.halfSpread?.let { checks += if (it <= 0.3) "the half-spread is within the 0.3% the research allows" else "the half-spread is above the 0.3% the research allows" }
        s.entryLagS?.let { checks += if (it <= 10) "entries come within 10 seconds of the bar close" else "entries come later than 10 seconds after the bar close" }
        s.fillVsSignal?.let { checks += if (it <= 0.5) "fills are within 0.5% of the signal's price" else "fills are more than 0.5% over the signal's price" }
        return head + if (checks.isEmpty()) "" else " So far " + checks.joinToString(", ") + "."
    }

    private val ASKED = Regex("(?i)\\bliquidity\\b.{0,40}\\b(fills?|fill quality|filled|spreads?|bid and ask|bid/ask|bid-ask)\\b|" +
        "\\b(fills?|fill quality|spreads?|bid and ask|bid/ask|bid-ask)\\b.{0,40}\\bliquidity\\b")

    /** "How are Liquidity fills?", "Liquidity fill quality", "what spread did Liquidity see". */
    fun asked(q: String): Boolean = ASKED.containsMatchIn(q)

    /** The order review's notes for [day]: the day's Liquidity fills against the go-live limits (facts only). */
    fun reviewNotes(recs: List<Rec>, day: LocalDate): List<String> {
        val today = recs.filter { it.day == day }
        if (today.isEmpty()) return emptyList()
        val s = stats(today, day, bankNifty = false)
        val out = ArrayList<String>()
        out += "Liquidity fill quality today: ${today.size} record${if (today.size == 1) "" else "s"}" +
            (s.halfSpread?.let { ", median half-spread ${pct0(it)}" } ?: "") + (s.entryLagS?.let { ", median entry %.1f s after the bar close".format(Locale.ENGLISH, it) } ?: "") +
            (s.fillVsSignal?.let { ", median fill ${pct(it)} against the signal" } ?: "") + "."
        // 25 Oct (app audit): "+0.5%" is the go-live check (study h14's capped limit), not an order the app sends: every Liquidity
        // entry is a MARKET order (OrbArms), so the words say so.
        today.filter { it.kind == Kind.ENTRY && (it.vsSignalPct ?: 0.0) > 0.5 }.forEach { out += "Liquidity ${bookWords(it.book)} entry filled ${pct(it.vsSignalPct!!)} over its signal's price (go-live check: at most +0.5%; the entry is a plain market order, not a capped limit)." }
        today.filter { it.kind == Kind.SIGNAL && !it.stale && (it.halfSpreadPct ?: 0.0) > 0.3 }.forEach { out += "Liquidity ${bookWords(it.book)} signal saw a ${pct0(it.halfSpreadPct!!)} half-spread (worry above 0.3%)." }
        today.filter { it.kind == Kind.ENTRY && (it.lagMs ?: 0L) > 10_000L }.forEach { out += "Liquidity ${bookWords(it.book)} entry came ${secs(it.lagMs!!)} after the bar close (aim: within 10 s)." }
        if (s.stale > 0) out += "${s.stale} Liquidity record${if (s.stale == 1) "" else "s"} today had a stale or missing stream quote."
        return out
    }
}
