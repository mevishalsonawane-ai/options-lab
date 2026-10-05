package com.optionslab.ira

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * What the trading paid in charges (usefulness round 7, 2026-10-05): "how much did I pay in charges this week?",
 * "my brokerage this month", "which trades cost me most in charges?". The closed trades of the span (every owner: the
 * bots trade on the same account) - what their charges came to, their share of the profit before charges, and which
 * kind of trading paid most: quick trades or long holds, small trades (moved less than twice their own charges) or
 * big ones, and who placed them. The charges are the app's own (the paper account's, the same sum the app uses to
 * estimate Zerodha's). Facts only, never what to trade; an account answer, so never on a locked phone. Pure.
 */
object Charges {
    /** A closed trade: [gross] before charges, [charges] its own (both legs); [owner] who placed it ("Manual", "ORB"...). */
    data class Trip(val openedAt: LocalDateTime, val closedAt: LocalDateTime, val gross: Double, val charges: Double, val owner: String) {
        val net: Double get() = gross - charges
    }

    enum class Span(val label: String) { TODAY("today"), WEEK("this week"), LAST_WEEK("last week"), MONTH("this month"), LAST_MONTH("last month") }

    /** How long a trade was held: the scalp against the long hold. */
    enum class Kind(val label: String, val what: String) {
        QUICK("Quick trades", "held under 5 minutes"),
        SHORT("Short trades", "held 5 to 30 minutes"),
        LONG("Longer trades", "held 30 minutes or more in the day"),
        OVERNIGHT("Overnight trades", "held overnight"),
    }

    /** A trade is "small" when its move before charges was less than this many times its own charges. */
    const val SMALL_RATIO = 2.0

    data class Group(val name: String, val trades: Int, val gross: Double, val charges: Double) {
        val net: Double get() = gross - charges
        /** The charges' share of the profit before charges, or null when there was no profit before charges. */
        val share: Double? get() = if (gross > 0) charges / gross else null
    }

    private fun norm(text: String) = " " + spacedWords(text.lowercase().replace("p&l", "p l")) + " "

    private const val CHARGE = "(charges|charge|brokerage|brokerages|fees|transaction costs?|trading costs?|stt|gst|stamp duty|taxes on (my )?trades|tax on (my )?trades)"
    private val ASKED = Regex(" (how much (did|have|do) i (pay|paid|spend|spent|give|given|lose|lost)( in| on| for| as| to)? (my |the |all |all the )?$CHARGE" +
        "|(my|mine|our) (total |overall |weekly |monthly )?$CHARGE|$CHARGE (did i|have i|i have|i) (pay|paid|spend|spent)" +
        "|(total|overall) $CHARGE|$CHARGE (this|last|previous|the) (week|month)|$CHARGE (today|so far)|(weekly|monthly) $CHARGE" +
        "|(what|how much) (did|have|do) (the |my )?$CHARGE (cost|take|eat|taken|eaten|come to|came to|add up)" +
        "|(which|what) (kind of |type of )?(trades?|trading) (cost|costs) (me )?(the )?most in $CHARGE|$CHARGE (ate|eat|eats|took|take|takes) (my|into my|of my)" +
        "|(how much|kitna|kitni|kitne) (in |on )?$CHARGE|$CHARGE (how much|kitna|kitni|kitne)) ")
    /** One order's charges ("charges for one lot", "charges per order"): the cost calculator's question, not the account's. */
    private val ONE = Regex(" (per order|per lot|per trade|for (a|one|1) (lot|order|trade)|on (a|one|1) (lot|order|trade)|calculator|calculate|if i (buy|sell)|what is brokerage|what are charges) ")

    /** Does [text] ask what Boss's trading paid in charges? */
    fun asked(text: String): Boolean {
        val t = norm(Ask.reading(text))
        return ASKED.containsMatchIn(t) && !ONE.containsMatchIn(t)
    }

    /** The span asked about; this month when none is said. */
    fun span(text: String): Span {
        val t = norm(text)
        return when {
            rx(" (today|today s|todays|aaj|aaj ka|aaj ke) ").containsMatchIn(t) -> Span.TODAY
            rx(" (last|previous|past|pichle|pichla) (week|hafte|hafta) ").containsMatchIn(t) -> Span.LAST_WEEK
            rx(" (last|previous|past|pichle|pichla) (month|mahina|mahine) ").containsMatchIn(t) -> Span.LAST_MONTH
            rx(" (week|weekly|hafte|hafta) ").containsMatchIn(t) -> Span.WEEK
            else -> Span.MONTH
        }
    }

    /** The days of [span] up to [today], first and last. */
    fun range(span: Span, today: LocalDate): Pair<LocalDate, LocalDate> {
        val monday = today.with(DayOfWeek.MONDAY)
        return when (span) {
            Span.TODAY -> today to today
            Span.WEEK -> monday to today
            Span.LAST_WEEK -> monday.minusWeeks(1) to monday.minusDays(1)
            Span.MONTH -> today.withDayOfMonth(1) to today
            Span.LAST_MONTH -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
        }
    }

    /** The calendar month [span] covers whole, for the app's charges by kind (brokerage, STT...); null for a day or a week. */
    fun month(span: Span, today: LocalDate): YearMonth? = when (span) {
        Span.MONTH -> YearMonth.from(today)
        Span.LAST_MONTH -> YearMonth.from(today).minusMonths(1)
        else -> null
    }

    fun kind(t: Trip): Kind {
        if (t.closedAt.toLocalDate() != t.openedAt.toLocalDate()) return Kind.OVERNIGHT
        val m = Duration.between(t.openedAt, t.closedAt).toMinutes().coerceAtLeast(0)
        return when { m < 5 -> Kind.QUICK; m < 30 -> Kind.SHORT; else -> Kind.LONG }
    }

    /** A trade whose move before charges was less than [SMALL_RATIO] times its own charges. */
    fun small(t: Trip): Boolean = t.charges > 0 && kotlin.math.abs(t.gross) < SMALL_RATIO * t.charges

    private fun group(name: String, w: List<Trip>) = Group(name, w.size, w.sumOf { it.gross }, w.sumOf { it.charges })

    /** The trades by how long they were held, in [Kind]'s order (the kinds with none left out). */
    fun byKind(w: List<Trip>): List<Pair<Kind, Group>> =
        Kind.entries.mapNotNull { k -> w.filter { kind(it) == k }.takeIf { it.isNotEmpty() }?.let { k to group(k.label, it) } }

    private fun amt(x: Double) = "Rs " + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun pct(x: Double) = "%.0f%%".format(Locale.ENGLISH, x * 100)
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun day(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    /** What the group's charges did to its result before charges. */
    private fun effect(g: Group): String = when {
        g.gross > 0 && g.net < 0 -> "${rs(g.gross)} before charges, ${rs(g.net)} after: the charges turned it to a loss"
        g.gross > 0 -> "${rs(g.gross)} before charges, so the charges took ${pct(g.share!!)} of it"
        else -> "${rs(g.gross)} before charges, so the charges added ${amt(g.charges)} to the loss"
    }

    /**
     * The charges of [label]'s ("Paper", "Zerodha") trades closed in [span]: the total and a trade's average, the share of
     * the profit before charges, by how long the trades were held, the small trades against the rest, who placed them,
     * and what cost most. [byCharge]: the app's charges by kind (brokerage, STT...) on the fills of a whole month.
     * [estimated]: the charges are the app's estimate (Zerodha's), not the paper account's own.
     */
    fun lines(label: String, trips: List<Trip>, span: Span, today: LocalDate, byCharge: Map<String, Double> = emptyMap(), estimated: Boolean = false): List<String> {
        val (from, to) = range(span, today)
        val w = trips.filter { t -> t.closedAt.toLocalDate().let { !it.isBefore(from) && !it.isAfter(to) } }
        val period = if (from == to) "${span.label} (${day(from)})" else "${span.label} (${day(from)} to ${day(to)})"
        if (w.isEmpty()) return listOf("$label: no closed trades ${period}, so no charges on them.")
        val all = group("All", w)
        val out = ArrayList<String>()
        out += "$label charges ${period}: ${amt(all.charges)} on ${plural(all.trades, "closed trade")}, about ${amt(all.charges / all.trades)} a trade" +
            (if (estimated) " (the app's estimate of Zerodha's charges)." else ".")
        out += "$label all together: ${effect(all)}; ${rs(all.net)} after charges."

        val kinds = byKind(w)
        if (kinds.size > 1) kinds.forEach { (k, g) ->
            out += "$label ${k.label.lowercase()} (${k.what}): ${plural(g.trades, "trade")}, ${amt(g.charges)} in charges (${amt(g.charges / g.trades)} a trade); ${effect(g)}."
        }
        val smalls = w.filter { small(it) }
        if (smalls.isNotEmpty() && smalls.size < w.size) {
            val s = group("Small", smalls); val b = group("Big", w - smalls.toSet())
            out += "$label small trades (moved less than twice their own charges): ${smalls.size} of ${w.size}, ${amt(s.charges)} in charges (${pct(s.charges / all.charges)} of all); ${effect(s)}."
            out += "$label the other ${plural(b.trades, "trade")}: ${amt(b.charges)} in charges; ${effect(b)}."
        } else if (smalls.size == w.size && w.size > 1) out += "$label: every trade moved less than twice its own charges."

        val owners = w.groupBy { it.owner }.map { (o, l) -> group(o, l) }.sortedByDescending { it.charges }
        if (owners.size > 1) out += "$label charges by who placed the trades: " +
            owners.joinToString(", ") { "${it.name} ${amt(it.charges)} (${plural(it.trades, "trade")})" } + "."

        // What cost most: the kind of holding that paid the most in charges, and the one where they took the biggest share.
        if (kinds.size > 1) {
            val (k, g) = kinds.maxBy { it.second.charges }
            out += "$label cost most in charges: ${k.label.lowercase()} (${k.what}) - ${amt(g.charges)} of the ${amt(all.charges)} (${pct(g.charges / all.charges)}) " +
                "on ${g.trades} of ${all.trades} trades."
            kinds.filter { it.second.share != null }.maxByOrNull { it.second.share!! }?.takeIf { it.first != k }?.let { (k2, g2) ->
                out += "$label: the charges took the biggest share of the profit on ${k2.label.lowercase()} (${pct(g2.share!!)})."
            }
        }
        if (byCharge.values.any { it >= 0.5 }) out += "$label every fill in ${month(span, today)?.month?.getDisplayName(TextStyle.FULL, Locale.ENGLISH) ?: span.label}, by kind of charge: " +
            byCharge.entries.filter { it.value >= 0.5 }.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${amt(it.value)}" } + "."
        return out
    }
}
