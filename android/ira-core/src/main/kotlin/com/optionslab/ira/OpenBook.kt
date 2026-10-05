package com.optionslab.ira

import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * What the "Open" home-screen widget shows (the owner's wish, 2026-10-05: "only open order and on top P&L"), pure:
 * today's P&L on top - the account the app is in only (Zerodha in live mode, Paper in paper mode; the other on its own
 * line below, never added in, so real and paper money are never summed), then
 * only what is open - the open positions, then the orders still pending at the broker or in paper (OPEN, TRIGGER
 * PENDING, AMO). Closed positions and completed, cancelled or rejected orders are left out. A venue that could not be
 * read says so instead of looking empty. Each account carries its own time of reading: one read on an earlier day is never
 * shown as today's ("Zerodha: not updated today"), and the "as of" stamp is the oldest of the accounts shown. Also the
 * small text codec the widget keeps its figures in between redraws.
 */
object OpenBook {
    const val ZERODHA = "Zerodha"
    const val PAPER = "Paper"
    const val NOTHING_OPEN = "No open positions or orders"
    const val WAITING = "Waiting for the app or the live watch to update"
    /** Said for the account the app is in when its last reading is from an earlier day. */
    const val NOT_TODAY = "not updated today"

    /** An open position: [qty] signed (negative = short), [ltp] null when unknown. */
    data class Pos(val symbol: String, val qty: Int, val ltp: Double?, val pnl: Double)

    /** An order as the book reads it: [status] and [variety] raw; [qty] the quantity still to fill. */
    data class Ord(val symbol: String, val side: String, val qty: Int, val type: String, val price: Double, val trigger: Double,
                   val status: String, val variety: String = "")

    /**
     * One account's books. [pnl] today's P&L as Home shows it; [problem] set ("could not read", "not logged in") when its
     * books are unknown; [primary] the account the app is in (shown even when it holds nothing; its P&L is the
     * headline); [at] when it was read (null: unknown, never taken for today's).
     */
    data class Venue(val name: String, val pnl: Double?, val positions: List<Pos> = emptyList(), val orders: List<Ord> = emptyList(),
                     val problem: String? = null, val primary: Boolean = false, val at: LocalDateTime? = null)

    enum class Tone { GAIN, LOSS, PLAIN }

    data class Row(val title: String, val detail: String, val figure: String, val tone: Tone)

    /**
     * The widget's content: [headline] the P&L in large type with its [tone], the account the app is in only; [caption]
     * whose it is; [split] one line for each other account in play (and for the main one when it could not be read); [rows] at most the cap; [more] the "+N more" line; [note] the
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

    /**
     * The widget's content from the accounts read (Zerodha before Paper, as given), at most [maxRows] rows. The headline is
     * the [Venue.primary] account's P&L alone (the first shown when none is marked); every other account in play is on a
     * split line of its own, never added in.
     */
    fun screen(venues: List<Venue>, maxRows: Int = 8): Screen {
        val shown = venues.filter(::inPlay)
        if (shown.isEmpty()) return Screen("Today's P&L", "—", Tone.PLAIN, emptyList(), emptyList(), null, WAITING)
        val head = shown.firstOrNull { it.primary } ?: shown.first()
        val headPnl = if (head.problem == null) head.pnl else null
        val caption = "Today's P&L · ${head.name}"
        val others = shown.filter { it !== head }
        val split = (if (head.problem != null) listOf(head) else emptyList()) + others
        val splitLines = split.map { v -> v.problem?.let { "${v.name}: $it" } ?: "${v.name}  ${v.pnl?.let(::rs) ?: "—"}" }
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
        return Screen(caption, headPnl?.let(::rs) ?: "—", headPnl?.let(::tone) ?: Tone.PLAIN, splitLines, rows, more, note)
    }

    /**
     * Only today's readings: an account read on an earlier day (or at an unknown time) is never shown with its old
     * figures - the account the app is in says [NOT_TODAY]; another one is left out.
     */
    fun current(venues: List<Venue>, now: LocalDateTime): List<Venue> = venues.mapNotNull { v ->
        val at = v.at
        when {
            at != null && at.toLocalDate() == now.toLocalDate() -> v
            v.primary -> Venue(v.name, null, problem = NOT_TODAY, primary = true, at = at)
            else -> null
        }
    }

    /** The "as of" stamp: the oldest reading among the accounts shown (an account [NOT_TODAY] says so instead); null for none. */
    fun stamp(venues: List<Venue>, now: LocalDateTime): String? =
        venues.filter { inPlay(it) && it.problem != NOT_TODAY }.mapNotNull { it.at }.minOrNull()?.let { asOf(it, now) }

    /** The widget's content and its stamp from what it kept, at [now]: [current] first, then [screen] and [stamp]. */
    fun view(venues: List<Venue>, now: LocalDateTime, maxRows: Int = 8): Pair<Screen, String?> {
        val c = current(venues, now)
        return screen(c, maxRows) to stamp(c, now)
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

    private fun minute(t: LocalDateTime) = t.withSecond(0).withNano(0)

    fun encode(v: Venue): String = (listOf(listOf("V", clean(v.name), v.pnl?.let(::num) ?: "", clean(v.problem ?: ""), if (v.primary) "1" else "0",
        v.at?.let { minute(it).toString() } ?: "").joinToString("\t")) +
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
                lines.drop(1).mapNotNull(::ord), h[3].takeIf { it.isNotEmpty() }, h[4] == "1",
                h.getOrNull(5)?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() })
        }.getOrNull()
    }

    /**
     * Do two kept records ([encode]'s or [encodeOrders]') hold the same figures on the same day? Only the minute of the
     * reading may differ: the vault copy is rewritten when the figures or the day change, not every minute.
     */
    fun sameFigures(a: String?, b: String?): Boolean {
        if (a == b) return true
        val va = decode(a); val vb = decode(b)
        if (va == null || vb == null) return false
        fun day(v: Venue) = v.copy(at = v.at?.toLocalDate()?.atStartOfDay())
        return day(va) == day(vb)
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
