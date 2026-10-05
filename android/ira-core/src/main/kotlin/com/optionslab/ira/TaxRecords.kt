package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Boss's records for the tax year (usefulness round 11, 2026-10-05): "what's my F&O turnover this year?", "my tax
 * summary", "export my trades for tax". The financial year (1 April to 31 March) from his own trade books: the F&O
 * turnover counted the way the tax-audit turnover usually is (each trade's profit or loss taken as positive and added
 * up; an option's premium is not added) - always said to be an estimate to confirm with his CA - the realised P&L by
 * month, the charges (the app's own, as [Charges] sums them), the number of trades, and a CSV of the year's trades
 * (dates, symbol, quantity, buy and sell values, P&L and charges - never an order ID, account ID or key) for him to
 * share. Facts only, never tax advice; an account answer, so never on a locked phone, and the export only on an
 * unlocked phone after Boss says yes. Nothing here places, changes or closes anything. Pure.
 */
object TaxRecords {
    /** A closed trade: [qty] the units traded (positive); [gross] before charges, [charges] its own (both legs). */
    data class Trade(val symbol: String, val openedAt: LocalDateTime, val closedAt: LocalDateTime, val qty: Int,
                     val buyValue: Double, val sellValue: Double, val charges: Double) {
        val gross: Double get() = sellValue - buyValue
        val net: Double get() = gross - charges
    }

    /** A round trip as the trade book keeps it: [direction] +1 bought first, -1 sold first. */
    fun trade(symbol: String, direction: Int, qty: Int, entry: Double, exit: Double, openedAt: LocalDateTime, closedAt: LocalDateTime, charges: Double): Trade {
        val q = kotlin.math.abs(qty)
        val (buy, sell) = if (direction >= 0) entry * q to exit * q else exit * q to entry * q
        return Trade(symbol, openedAt, closedAt, q, buy, sell, charges)
    }

    /** A financial year, 1 April [start] to 31 March the year after. */
    data class Fy(val start: Int) {
        val from: LocalDate get() = LocalDate.of(start, 4, 1)
        val to: LocalDate get() = LocalDate.of(start + 1, 3, 31)
        val label: String get() = "FY $start-${"%02d".format(Locale.ENGLISH, (start + 1) % 100)}"
        fun holds(d: LocalDate) = !d.isBefore(from) && !d.isAfter(to)
    }

    /** The financial year [day] falls in. */
    fun fyOf(day: LocalDate): Fy = Fy(if (day.monthValue >= 4) day.year else day.year - 1)

    enum class Segment(val label: String) { OPTION("option"), FUTURE("future"), OTHER("other") }

    /** An option (ends in CE or PE after a strike), a future (ends in FUT), else something else (shares...). */
    fun segment(symbol: String): Segment {
        val s = symbol.uppercase().trim()
        return when {
            rx("\\d(CE|PE)$").containsMatchIn(s) -> Segment.OPTION
            s.endsWith("FUT") -> Segment.FUTURE
            else -> Segment.OTHER
        }
    }

    /**
     * The F&O turnover as the tax-audit turnover is usually counted: each F&O trade's profit or loss before charges taken
     * as positive, added up (an option's premium is not added). An estimate: Boss's CA confirms it.
     */
    fun turnover(trades: List<Trade>): Double = trades.filter { segment(it.symbol) != Segment.OTHER }.sumOf { kotlin.math.abs(it.gross) }

    private fun norm(text: String) = " " + text.lowercase().replace("p&l", "p l").replace("f&o", " fno ").replace("f & o", " fno ")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private val TURNOVER = Regex(" (turnover|turn over|turnovers) ")
    private val MINE = Regex(" (my|mine|i|me|our|mera|meri|mere|mujhe) ")
    private val TAXY = Regex(" (tax|taxes|itr|income tax|tax audit|ca|chartered accountant|financial year|fy|fiscal year|fno|f o|futures and options) ")
    private val RECORD = Regex(" ((tax|itr) (report|records?|summary|statement|numbers|figures|details|p l|pnl|profit)" +
        "|(for|of) (my |the )?(itr|income tax|tax filing|tax return|tax returns|taxes|tax)( this year| for the year| this fy)? " +
        "|(realised|realized|booked) (p l|pnl|profit|profits|loss|losses)( for| of)? (this|the|last|previous) (year|fy|financial year)" +
        "|(my )?(p l|pnl|profit|loss|trades|trading)( for| of| in)? (this|the|last|previous) (financial year|fy|fiscal year)|tax audit|tax ke liye|itr ke liye)")
    private val EXPORT = Regex(" (export|exported|download|csv|excel|spreadsheet|sheet|file) ")
    private val SHARE = Regex(" (send|share|email|mail|whatsapp|give) ")
    private val TRADES = Regex(" (trades|trade|trade book|tradebook|trade list|p l|pnl|records|record|statement|report|turnover|transactions) ")
    private val FOR_TAX = Regex(" (tax|taxes|itr|income tax|ca|accountant|chartered accountant|fy|financial year|tax filing|tax return) ")
    /** One order's charges or a market's turnover: not Boss's year. */
    private val NOT = Regex(" (per order|per lot|calculator|nifty s turnover|market turnover|exchange turnover|nse turnover|company s turnover) ")

    /** Does [text] ask for a CSV of the year's trades (for tax)? */
    fun exportAsked(text: String): Boolean {
        val t = norm(Ask.reading(text))
        if (!TRADES.containsMatchIn(t) || NOT.containsMatchIn(t)) return false
        return EXPORT.containsMatchIn(t) && (MINE.containsMatchIn(t) || FOR_TAX.containsMatchIn(t)) ||
            SHARE.containsMatchIn(t) && FOR_TAX.containsMatchIn(t) && MINE.containsMatchIn(t)
    }

    /** Does [text] ask for the year's turnover or tax facts (not the export)? */
    fun asked(text: String): Boolean {
        if (exportAsked(text)) return false
        val t = norm(Ask.reading(text))
        if (NOT.containsMatchIn(t)) return false
        if (TURNOVER.containsMatchIn(t) && (MINE.containsMatchIn(t) || TAXY.containsMatchIn(t)) && Market.mentioned(text).isEmpty()) return true
        if (TURNOVER.containsMatchIn(t) && MINE.containsMatchIn(t)) return true
        return RECORD.containsMatchIn(t)
    }

    /** The year asked about: "last year" / "previous FY" the one before, "FY 2025-26" or "2025-26" that one, else this one. */
    fun fy(text: String, today: LocalDate): Fy {
        val t = norm(text)
        val now = fyOf(today)
        rx(" (?:fy )?(20\\d\\d) (\\d\\d|20\\d\\d) ").find(t.replace(rx("(20\\d\\d) ?- ?"), "$1 "))?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt() % 100
            if ((a + 1) % 100 == b && a <= now.start) return Fy(a)
        }
        rx(" fy ?(\\d\\d) ?(\\d\\d) ").find(t)?.let { m ->
            val a = 2000 + m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            if ((a + 1) % 100 == b && a <= now.start) return Fy(a)
        }
        return if (rx(" (last|previous|past|pichle|pichla|prior) (year|years|fy|financial year|fiscal year|saal) ").containsMatchIn(t)) Fy(now.start - 1) else now
    }

    private fun amt(x: Double) = "Rs " + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun day(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.year}"
    private fun mon(m: YearMonth) = m.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + m.year

    /** The trades of [fy] that closed by [today]. */
    fun inYear(trades: List<Trade>, fy: Fy, today: LocalDate): List<Trade> =
        trades.filter { fy.holds(it.closedAt.toLocalDate()) && !it.closedAt.toLocalDate().isAfter(today) }.sortedBy { it.closedAt }

    /** The realised P&L (after charges) of each month of [w] that had a trade, in order. */
    fun byMonth(w: List<Trade>): List<Pair<YearMonth, Double>> =
        w.groupBy { YearMonth.from(it.closedAt) }.toSortedMap().map { (m, l) -> m to l.sumOf { it.net } }

    const val CONFIRM = "These are facts from the trades this app recorded, not tax advice: confirm the turnover and what to file with your CA."

    /**
     * The year's facts for the chat. [zerodha]: the Zerodha round trips the app recorded (null when there are none to
     * show); [paper]: the paper account's (practice - no real money, shown for the record).
     */
    fun lines(zerodha: List<Trade>?, paper: List<Trade>, fy: Fy, today: LocalDate): List<String> {
        val z = zerodha?.let { inYear(it, fy, today) }.orEmpty()
        val p = inYear(paper, fy, today)
        val end = if (fy.to.isAfter(today)) today else fy.to
        val span = "${fy.label} (${day(fy.from)} to ${day(end)}${if (fy.to.isAfter(today)) ", so far" else ""})"
        if (z.isEmpty() && p.isEmpty()) return listOf("No closed trades recorded in $span, Boss.", CONFIRM)
        val out = ArrayList<String>()
        if (z.isNotEmpty()) {
            out += year("Zerodha", z, span, estimated = true)
            if (p.isNotEmpty()) out += "Paper (practice, not real money): ${plural(p.size, "trade")} in ${fy.label}, net ${rs(p.sumOf { it.net })} - not part of the figures above."
        } else {
            out += "No Zerodha trades recorded by this app in $span; your paper account's (practice, not real money) for the record:"
            out += year("Paper", p, span, estimated = false)
        }
        out += CONFIRM + if (z.isNotEmpty()) " Zerodha's own tax P&L report (in Console) is the full record, with trades placed outside this app." else ""
        out += "Say \"export my trades for tax\" for a CSV of ${fy.label}'s trades to share."
        return out
    }

    private fun year(label: String, w: List<Trade>, span: String, estimated: Boolean): List<String> {
        val out = ArrayList<String>()
        val opts = w.count { segment(it.symbol) == Segment.OPTION }
        val futs = w.count { segment(it.symbol) == Segment.FUTURE }
        val other = w.filter { segment(it.symbol) == Segment.OTHER }
        val kinds = listOfNotNull(opts.takeIf { it > 0 }?.let { plural(it, "option trade") }, futs.takeIf { it > 0 }?.let { plural(it, "futures trade") },
            other.size.takeIf { it > 0 }?.let { plural(it, "other trade") })
        out += "$label, $span: ${plural(w.size, "closed trade")} (${kinds.joinToString(", ")})."
        val fno = w - other.toSet()
        if (fno.isNotEmpty()) out += "$label F&O turnover, estimated: ${amt(turnover(w))} - each F&O trade's profit or loss taken as positive and added up " +
            "(an option's premium is not added), the way the tax-audit turnover is usually counted. An estimate: confirm it with your CA."
        val charges = w.sumOf { it.charges }
        out += "$label realised P&L: ${rs(w.sumOf { it.gross })} before charges, ${amt(charges)} in charges, ${rs(w.sumOf { it.net })} after." +
            (if (estimated) " (The charges are the app's estimate; your contract notes have the exact figures.)" else "")
        val months = byMonth(w)
        out += "$label by month (after charges): " + months.joinToString(", ") { (m, x) -> "${mon(m)} ${rs(x)}" } + "."
        if (other.isNotEmpty()) out += "$label: ${plural(other.size, "trade")} not in F&O (${rs(other.sumOf { it.net })} after charges) left out of the F&O turnover."
        return out
    }

    /** The CSV file's name for [fy] and [label] ("Zerodha", "Paper"). */
    fun fileName(fy: Fy, label: String): String = "trades-FY${fy.start}-${"%02d".format(Locale.ENGLISH, (fy.start + 1) % 100)}-${label.lowercase()}.csv"

    const val HEADER = "opened,closed,account,symbol,segment,qty,buy_value,sell_value,pnl_before_charges,charges,pnl_after_charges,turnover"

    /** A cell kept as text: a spreadsheet never reads it as a formula, and a comma or quote never splits it. */
    private fun cell(s: String): String {
        val safe = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
    private fun n(x: Double) = "%.2f".format(Locale.ENGLISH, x)

    /**
     * The year's trades as a CSV, one row a trade, oldest first: dates, the account's name, symbol, quantity, buy and sell
     * values, P&L and charges - nothing else (never an order ID, an account ID or a key).
     */
    fun csv(label: String, trades: List<Trade>, fy: Fy, today: LocalDate): String = buildString {
        append(HEADER).append('\n')
        for (t in inYear(trades, fy, today)) {
            val seg = segment(t.symbol)
            append(listOf(t.openedAt.toLocalDate().toString(), t.closedAt.toLocalDate().toString(), cell(label), cell(t.symbol), seg.label, t.qty.toString(),
                n(t.buyValue), n(t.sellValue), n(t.gross), n(t.charges), n(t.net), if (seg == Segment.OTHER) "" else n(kotlin.math.abs(t.gross))).joinToString(","))
            append('\n')
        }
    }

    /** What Boss is asked before the export (no amount: it may be heard). Null when there is nothing to export. */
    fun offer(label: String, count: Int, fy: Fy): String? = if (count == 0) null else
        "Boss, shall I put your ${fy.label} ${if (label == "Paper") "paper (practice) " else "$label "}trades (${plural(count, "trade")}) in a CSV file for you to share? " +
            "It holds each trade's dates, symbol, quantity, buy and sell values, P&L and charges - no order IDs, account IDs or keys."

    /** Said when the file is ready (the share sheet opens). */
    fun done(label: String, count: Int, fy: Fy): String =
        "Done, Boss: ${plural(count, "trade")} from ${fy.label} ($label) in a CSV - pick where to send it. The turnover column is an estimate; confirm with your CA."

    /** Nothing to export. */
    fun nothing(fy: Fy): String = "No closed trades recorded in ${fy.label}, Boss, so there is nothing to export."
}
