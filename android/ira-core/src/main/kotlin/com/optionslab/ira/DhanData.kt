package com.optionslab.ira

import com.optionslab.ira.dhan.DhanApi
import com.optionslab.ira.dhan.ExpiredOptions
import com.optionslab.ira.dhan.Files
import com.optionslab.ira.dhan.Plan
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * What Jarvis has learned from Dhan's market data, downloaded to this phone (More -> Dhan data): "what Dhan data do you
 * have?", "what have you downloaded?", "dhan se kya data hai", "how did BankNifty move on the last expiry in the Dhan
 * data?". Read-only facts from the store ([Files]): what is kept (indices, companies, futures, expired options, from when,
 * how much space), and an index's last expiry day as the stored daily candles have it (open, high, low, end, the change
 * on the day before, the range). Facts from stored candles only - never a forecast or advice, nothing acts, nothing is
 * downloaded or deleted from here (those are Boss's taps on the Dhan data page). Pure: the app hands it the store.
 */
object DhanData {
    enum class Kind { HAVE, EXPIRY_MOVE }

    /** What was asked, and the index named (the app's name, "BANKNIFTY"; null when none). */
    data class Q(val kind: Kind, val index: String?)

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9& ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    /** Dhan named (never "dhanyavad", "dhanteras"). */
    private val DHAN = Regex(" dhan (s )?")
    /** The store named without Dhan. */
    private val STORE = Regex(" (downloaded data|data downloaded|downloaded history|downloaded candles|data you downloaded|you downloaded|have you downloaded|did you download|download kiya|download kia|downloaded hai|data store|stored market data|market data store) ")
    /** "What data do you have" said plainly. */
    private val WHAT_DATA = Regex(" ((what|which) (market |historical |history )?data (do you have|have you got|do you keep|is stored)|" +
        "(kya|kaun sa|kitna) (market )?data (hai tumhare paas|tumhare paas hai|rakha hai|store hai)) ")
    /** A change to the store, or something else downloaded: not this question. */
    private val NOT = Regex(" (delete|remove|wipe|erase|clear|hatao|hata do|model|voice|apk|app update|update the app|backup|restore|chart pattern|token|login|log in|password|key) ")
    private val EXPIRY = Regex(" (expiry|expiries|expiry day|expiry ke din|expiry wale din) ")
    private val MOVE = Regex(" (move|moved|moves|do|did|does|went|go|perform|performed|behave|behaved|chala|chali|gaya|gayi|kiya|range|close|end|ended|finish|finished) ")

    /** Index names as said, longest first, with the app's name. */
    private val INDEX_WORDS: List<Pair<Regex, String>> = listOf(
        " (nifty next 50|nifty next fifty|next 50|niftynxt50|nifty nxt 50|junior nifty) " to "NIFTYNXT50",
        " (midcap nifty|nifty midcap select|midcpnifty|midcap select|mid cap nifty|midcp nifty) " to "MIDCPNIFTY",
        " (bank nifty|banknifty|nifty bank|bnf) " to "BANKNIFTY",
        " (fin nifty|finnifty|nifty fin|nifty financial services) " to "FINNIFTY",
        " (bankex) " to "BANKEX",
        " (sensex) " to "SENSEX",
        " (india vix|vix) " to "INDIAVIX",
        " (nifty|nifty 50|nifty fifty) " to "NIFTY",
    ).map { (p, n) -> Regex(p) to n }

    fun index(text: String): String? {
        val t = norm(text)
        return INDEX_WORDS.firstOrNull { it.first.containsMatchIn(t) }?.second
    }

    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        val named = DHAN.containsMatchIn(t) || STORE.containsMatchIn(t)
        if (!named && !WHAT_DATA.containsMatchIn(t)) return null
        val ix = index(text)
        if (named && ix != null && EXPIRY.containsMatchIn(t) && MOVE.containsMatchIn(t)) return Q(Kind.EXPIRY_MOVE, ix)
        return Q(Kind.HAVE, ix)
    }

    // ---- the answers ---------------------------------------------------------------------------------------------------

    const val NONE = "I have no Dhan data on this phone yet, Boss. Add your Dhan client ID and access token in More, Dhan data, and tap Download; it stays on this phone and is only market data."
    const val GOLD = "IraGoldAlgo keeps no Dhan data, Boss: it only talks about gold."

    private fun mb(b: Long) = if (b >= 1_000_000_000L) "%.1f GB".format(Locale.ENGLISH, b / 1e9) else "%.0f MB".format(Locale.ENGLISH, b / 1e6)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.year}"
    private fun dateDay(d: LocalDate) = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${date(d)}"
    private fun px(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun label(u: String) = when (u) {
        "NIFTY" -> "Nifty"; "BANKNIFTY" -> "BankNifty"; "FINNIFTY" -> "FinNifty"; "MIDCPNIFTY" -> "Midcap Nifty"
        "NIFTYNXT50" -> "Nifty Next 50"; "SENSEX" -> "Sensex"; "BANKEX" -> "Bankex"; "INDIAVIX" -> "India VIX"; else -> u
    }

    /** The answer to [q] from the store under [files] (null: no store on this phone), on [today]. Reads only. */
    fun answer(q: Q, files: Files?, today: LocalDate): String {
        if (files == null || !files.root.isDirectory) return NONE
        return when (q.kind) {
            Kind.HAVE -> have(files.manifest(), q.index)
            Kind.EXPIRY_MOVE -> expiryMove(files, q.index ?: "NIFTY", today)
        }
    }

    /** What is kept, from the store's [m]anifest; [index] named: that index's part first. */
    fun have(m: Files.Manifest, index: String? = null): String {
        if (m.files == 0 || (m.indices.isEmpty() && m.stocks == 0)) return NONE
        val parts = ArrayList<String>()
        val order = if (index != null && index in m.indices) listOf(index) + (m.indices - index) else m.indices
        val daily = order.mapNotNull { u -> m.dailyFrom[u]?.let { "${label(u)} from ${it.year}" } }
        if (daily.isNotEmpty()) parts += "daily candles of ${daily.size} ${if (daily.size == 1) "index" else "indices"} (${daily.joinToString(", ")})"
        val minute = order.mapNotNull { u -> m.minuteFrom[u]?.let { "${label(u)} from ${date(it)}" } }
        if (minute.isNotEmpty()) parts += "one-minute candles of ${minute.take(3).joinToString(", ")}" + if (minute.size > 3) " and ${minute.size - 3} more" else ""
        if (m.stocks > 0) parts += "candles of ${m.stocks} ${if (m.stocks == 1) "company" else "companies"} in those indices"
        if (m.futures.isNotEmpty()) parts += "the near futures of ${m.futures.joinToString(", ") { label(it) }}"
        if (m.optionWindows.isNotEmpty()) {
            val o = m.optionWindows.entries.sortedByDescending { it.value }
            parts += "expired options of ${o.joinToString(", ") { "${label(it.key)} (about ${maxOf(1L, Math.round(it.value * Plan.OPT_WINDOW_DAYS / 30.0))} months)" }}"
        }
        val newest = m.lastDay?.let { ", newest day ${date(it)}" } ?: ""
        return "From Dhan, on this phone (${mb(m.bytes)}$newest): ${parts.joinToString("; ")}. I read them for the strategy replays and to answer from - market data only, nothing in it trades."
    }

    /** [u]'s last expiry before or on [today] whose daily candle is kept: the day as the candles have it. */
    fun expiryMove(files: Files, u: String, today: LocalDate): String {
        val ix = com.optionslab.ira.dhan.DhanUniverse.index(u)
        if (ix == null || !ix.options) return "${label(u)} has no options, Boss, so it has no expiry days."
        val expiries = (files.listedExpiries(u) + ExpiredOptions.flags(files, u).flatMap { ExpiredOptions.inferExpiries(files, u, it) })
            .distinct().filter { !it.isAfter(today) }.sorted()
        if (expiries.isEmpty()) return "I don't have ${label(u)}'s expiry dates in the Dhan data yet, Boss: they come with its options, on the next download."
        val daily = files.candles(Plan.Group.IDX, u, "day").associateBy { Instant.ofEpochSecond(it.t).atZone(com.optionslab.engine.IST).toLocalDate() }
        val e = expiries.lastOrNull { it in daily } ?: return "I don't have ${label(u)}'s daily candle for its last expiry (${date(expiries.last())}) in the Dhan data yet, Boss."
        val c = daily.getValue(e)
        val prev = daily.keys.filter { it.isBefore(e) }.maxOrNull()?.let { daily[it] }
        return expirySay(u, e, c, prev)
    }

    /** One expiry day said: open, high, low, end, the change on the day before, the range. */
    fun expirySay(u: String, day: LocalDate, c: DhanApi.Candle, prev: DhanApi.Candle?): String {
        val moved = if (prev == null || prev.close <= 0) "" else {
            val change = c.close - prev.close
            if (abs(change) < 0.005) ", unchanged on the day before"
            else ", ${if (change > 0) "up" else "down"} ${pts(change)} points (" + "%.2f".format(Locale.ENGLISH, abs(change) / prev.close * 100) + "%) on the day before"
        }
        return "${label(u)} on its last expiry, ${dateDay(day)}, as the Dhan data has it: opened ${px(c.open)}, high ${px(c.high)}, low ${px(c.low)}, " +
            "ended ${px(c.close)}$moved; a ${pts(c.high - c.low)}-point range. A past day's record, Boss, not a forecast."
    }
}
