package com.optionslab.ira

import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * What the "Open" home-screen widget shows (the owner's wish, 2026-10-05: "only open order and on top P&L"), pure:
 * today's P&L on top (Zerodha and Paper each on their own line when both are in play, never silently added up), then
 * only what is open - the open positions, then the orders still pending at the broker or in paper (OPEN, TRIGGER
 * PENDING, AMO). Closed positions and completed, cancelled or rejected orders are left out. A venue that could not be
 * read says so instead of looking empty. Also the small text codec the widget keeps its figures in between redraws.
 */
object OpenBook {
    const val ZERODHA = "Zerodha"
    const val PAPER = "Paper"
    const val NOTHING_OPEN = "No open positions or orders"
    const val WAITING = "Waiting for the app or the live watch to update"

    /** An open position: [qty] signed (negative = short), [ltp] null when unknown. */
    data class Pos(val symbol: String, val qty: Int, val ltp: Double?, val pnl: Double)

    /** An order as the book reads it: [status] and [variety] raw; [qty] the quantity still to fill. */
    data class Ord(val symbol: String, val side: String, val qty: Int, val type: String, val price: Double, val trigger: Double,
                   val status: String, val variety: String = "")

    /**
     * One account's books. [pnl] today's P&L as Home shows it; [problem] set ("could not read", "not logged in") when its
     * books are unknown; [primary] the account the app is in (shown even when it holds nothing).
     */
    data class Venue(val name: String, val pnl: Double?, val positions: List<Pos> = emptyList(), val orders: List<Ord> = emptyList(),
                     val problem: String? = null, val primary: Boolean = false)

    enum class Tone { GAIN, LOSS, PLAIN }

    data class Row(val title: String, val detail: String, val figure: String, val tone: Tone)

    /**
     * The widget's content: [headline] the P&L in large type with its [tone]; [caption] what it adds up; [split] one
     * line per account when more than one is in play; [rows] at most the cap; [more] the "+N more" line; [note] the
     * line shown instead of rows (nothing open, or nothing read yet).
     */
    data class Screen(val caption: String, val headline: String, val tone: Tone, val split: List<String>, val rows: List<Row>,
                      val more: String?, val note: String?)

    private val CLOSED = setOf("COMPLETE", "COMPLETED", "FILLED", "CANCELLED", "CANCELED", "REJECTED", "CANCELLED AMO", "EXPIRED", "LAPSED")
    private val OPEN = setOf("OPEN", "OPEN PENDING", "VALIDATION PENDING", "PUT ORDER REQ RECEIVED", "MODIFY PENDING",
        "MODIFY VALIDATION PENDING", "PENDING")

    /** "OPEN", "TRIGGER PENDING" or "AMO" for an order still pending; null for one that is done (filled, cancelled, rejected). */
    fun pendingLabel(status: String, variety: String = ""): String? {
        val s = status.trim().uppercase(Locale.ROOT)
        return when {
            s.isEmpty() || s in CLOSED -> null
            s.contains("TRIGGER") -> "TRIGGER PENDING"
            s.contains("AMO") -> "AMO"
            s in OPEN -> if (variety.equals("amo", true)) "AMO" else "OPEN"
            else -> null
        }
    }

    /** "+₹1,234" / "−₹800" (the position cards' style). */
    fun rs(x: Double): String = (if (x < 0) "−₹" else "+₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))

    private fun px(x: Double) = String.format(Locale.ENGLISH, "%.2f", x)
    private fun tone(x: Double) = if (x > 0) Tone.GAIN else if (x < 0) Tone.LOSS else Tone.PLAIN

    private fun inPlay(v: Venue) = v.problem != null || v.primary || v.positions.any { it.qty != 0 } ||
        v.orders.any { pendingLabel(it.status, it.variety) != null } || (v.pnl ?: 0.0) != 0.0

    fun positionRow(p: Pos, venue: String?): Row = Row(p.symbol,
        String.format(Locale.ENGLISH, "%+d", p.qty) + " · LTP " + (p.ltp?.let(::px) ?: "—") + (venue?.let { " · $it" } ?: ""),
        rs(p.pnl), tone(p.pnl))

    fun orderRow(o: Ord, label: String, venue: String?): Row {
        val type = o.type.trim().uppercase(Locale.ROOT).ifEmpty { "LIMIT" }
        val detail = "${o.side.trim().uppercase(Locale.ROOT)} ${abs(o.qty)} · $type · $label" +
            (if (o.trigger > 0 && o.price > 0) " · trg ${px(o.trigger)}" else "") + (venue?.let { " · $it" } ?: "")
        val figure = when {
            o.price > 0 -> "@ ${px(o.price)}"
            o.trigger > 0 -> "trg ${px(o.trigger)}"
            else -> "MKT"
        }
        return Row(o.symbol, detail, figure, Tone.PLAIN)
    }

    /** The widget's content from the accounts read (Zerodha before Paper, as given), at most [maxRows] rows. */
    fun screen(venues: List<Venue>, maxRows: Int = 8): Screen {
        val shown = venues.filter(::inPlay)
        if (shown.isEmpty()) return Screen("Today's P&L", "—", Tone.PLAIN, emptyList(), emptyList(), null, WAITING)
        val known = shown.filter { it.problem == null && it.pnl != null }
        val total = known.sumOf { it.pnl!! }
        val caption = "Today's P&L" + if (known.isEmpty()) "" else " · " + known.joinToString(" + ") { it.name }
        val split = if (shown.size < 2 && shown.all { it.problem == null }) emptyList()
            else shown.map { v -> v.problem?.let { "${v.name}: $it" } ?: "${v.name}  ${v.pnl?.let(::rs) ?: "—"}" }
        val read = shown.filter { it.problem == null }
        val tag = read.size > 1
        val all = read.flatMap { v -> v.positions.filter { it.qty != 0 }.map { positionRow(it, if (tag) v.name else null) } } +
            read.flatMap { v -> v.orders.mapNotNull { o -> pendingLabel(o.status, o.variety)?.let { orderRow(o, it, if (tag) v.name else null) } } }
        val cap = maxRows.coerceAtLeast(1)
        val rows = all.take(cap)
        val more = (all.size - rows.size).takeIf { it > 0 }?.let { "+$it more" }
        val note = when {
            all.isNotEmpty() -> null
            read.isEmpty() -> null                                  // nothing could be read: the split line says why
            read.size == shown.size -> NOTHING_OPEN
            else -> "No open ${read.joinToString(" or ") { it.name }} positions or orders"
        }
        return Screen(caption, if (known.isEmpty()) "—" else rs(total), if (known.isEmpty()) Tone.PLAIN else tone(total),
            split, rows, more, note)
    }

    /** "as of 14:05" today, "as of 3 Oct 15:29" for another day. */
    fun asOf(at: LocalDateTime, now: LocalDateTime): String =
        if (at.toLocalDate() == now.toLocalDate()) String.format(Locale.ENGLISH, "as of %02d:%02d", at.hour, at.minute)
        else String.format(Locale.ENGLISH, "as of %d %s %02d:%02d", at.dayOfMonth,
            at.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH), at.hour, at.minute)

    // ---- the codec: tab-separated lines, one record each -------------------------------------------------------------

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    private fun num(x: Double) = x.toString()

    private fun ordLine(o: Ord) = listOf("O", clean(o.symbol), clean(o.side), o.qty.toString(), clean(o.type), num(o.price), num(o.trigger),
        clean(o.status), clean(o.variety)).joinToString("\t")

    private fun ord(f: List<String>): Ord? = if (f.size < 9 || f[0] != "O") null else runCatching {
        Ord(f[1], f[2], f[3].toInt(), f[4], f[5].toDouble(), f[6].toDouble(), f[7], f[8])
    }.getOrNull()

    fun encode(v: Venue): String = (listOf(listOf("V", clean(v.name), v.pnl?.let(::num) ?: "", clean(v.problem ?: ""), if (v.primary) "1" else "0").joinToString("\t")) +
        v.positions.map { listOf("P", clean(it.symbol), it.qty.toString(), it.ltp?.let(::num) ?: "", num(it.pnl)).joinToString("\t") } +
        v.orders.map(::ordLine)).joinToString("\n")

    /** The venue [encode] wrote, or null for nothing or a damaged record. */
    fun decode(s: String?): Venue? {
        if (s.isNullOrEmpty()) return null
        val lines = s.split('\n').map { it.split('\t') }
        val h = lines.first()
        if (h.size < 5 || h[0] != "V") return null
        return runCatching {
            Venue(h[1], h[2].takeIf { it.isNotEmpty() }?.toDouble(),
                lines.drop(1).filter { it.size >= 5 && it[0] == "P" }.map { Pos(it[1], it[2].toInt(), it[3].takeIf { x -> x.isNotEmpty() }?.toDouble(), it[4].toDouble()) },
                lines.drop(1).mapNotNull(::ord), h[3].takeIf { it.isNotEmpty() }, h[4] == "1")
        }.getOrNull()
    }

    /** A day's order list (read separately from the positions), stamped with [day] (yyyy-MM-dd). */
    fun encodeOrders(day: String, orders: List<Ord>): String = (listOf(clean(day)) + orders.map(::ordLine)).joinToString("\n")

    /** The orders [encodeOrders] wrote on [today]; empty for another day's list or nothing. */
    fun decodeOrders(s: String?, today: String): List<Ord> {
        if (s.isNullOrEmpty()) return emptyList()
        val lines = s.split('\n')
        if (lines.first() != today) return emptyList()
        return lines.drop(1).mapNotNull { ord(it.split('\t')) }
    }
}
