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
 * estimate Zerodha's). "Why are my charges so high?" ([whyAsked], [whyLines]): the orders, fills and round trips of the
 * day (or the span said), the charges by kind, who placed the most orders. Facts only, never what to trade; an account
 * answer, so never on a locked phone. Pure.
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
        "|(how much|kitna|kitni|kitne) (in |on )?$CHARGE|$CHARGE (how much|kitna|kitni|kitne)" +
        // Understanding round 25: "how much went in charges", "today's charges", "charges ne kitna khaya".
        "|how much (went|has gone|was gone|is gone|goes|got eaten) (in|on|to|into|as) (the |my )?$CHARGE|(today|today s|todays|aaj ke|aaj ka|aaj ki) $CHARGE" +
        "|$CHARGE (ne )?(kitna|kitne|kitni|how much) (khaya|kha liya|kha gaye|kha gayi|liya|le liya|kata|kaata|kat gaya|gaya|gaye)" +
        // Understanding round 26: "charges lage kitne", "charges ka total kya hai", "charges batao", "how much tax did I pay on
        // trades", "how much did I pay Zerodha".
        "|$CHARGE (lage|laga|lagi|hue|hua|kate|kaate|gaye|gaya) (kitne|kitna|kitni|how much)|$CHARGE (ka |ki )?(total|hisaab|hisab|jod)" +
        "|(show|tell|batao|bataao|dikhao|bolo) (me )?(my |the |today s |todays |aaj ke )?$CHARGE|$CHARGE (batao|bataao|dikhao|bolo)" +
        "|how much (tax|taxes) (did|have|do) i (pay|paid) (on|for) (my )?(trades|trading|f o|options)" +
        "|how much (did|have|do) i (pay|paid|give|given) (to )?(zerodha|kite|the broker|my broker)) ")
    /** One order's charges ("charges for one lot", "charges per order"): the cost calculator's question, not the account's. */
    private val ONE = Regex(" (per order|per lot|per trade|for (a|one|1) (lot|order|trade)|on (a|one|1) (lot|order|trade)|calculator|calculate|if i (buy|sell)|what is brokerage|what are charges) ")

    /** Does [text] ask what Boss's trading paid in charges (or why they were so high, [whyAsked])? */
    fun asked(text: String): Boolean {
        val t = norm(Ask.reading(text))
        return (ASKED.containsMatchIn(t) || WHY.containsMatchIn(t)) && !ONE.containsMatchIn(t) || whyAsked(text)
    }

    // Usefulness round 34 (Boss saw about Rs 2,500 a day in charges and was surprised): "why are my charges so high",
    // "charges itne zyada kyun", "what is eating my charges", "where are my charges going".
    private const val HIGH = "(so |this |that |too |very |such |itne |itna |itni |bahut |kaafi )?(high|much|big|large|huge|expensive|heavy|zyada|jyada|jada|zada|more|higher)"
    private const val KYUN = "(kyun|kyu|kyon|kiyon|kiu|why)"
    private val WHY = Regex(" (why (are|is|were|was|have|has) (my |the |all |all the |today s |todays |aaj ke )?$CHARGE (been |gone |become )?$HIGH" +
        "|why (so much|so many|such high|such big|this much|that much|such huge) (in |on )?(my |the )?$CHARGE" +
        "|why (do|did|am|was|have) i (pay|paying|paid|spend|spending|spent) (so much|this much|that much|such high|such big|so many|so high) (in |on |as |for )?(the |my )?$CHARGE" +
        "|what (is|s|are) (eating|driving|causing|pushing|making|behind|inflating) (up )?(my |the |all |all the |today s |todays )?$CHARGE" +
        "|what (is|s) (eating|driving up|pushing up|inflating) (my |the )?(money|profit|pnl|p l) (in|on|as|with) (the )?$CHARGE" +
        "|what makes (my |the )?$CHARGE (so )?(high|big|much)|where (are|is|do|does) (my |the |all |all my )?$CHARGE (going|go|coming from|come from)" +
        "|(my |the )$CHARGE (are|is|were|was) (too|so|very|way too) (high|much|big)" +
        "|$CHARGE ($HIGH )?$KYUN|$CHARGE ($KYUN )?(itne|itna|itni|bahut|kaafi) |$KYUN (itne|itna|itni|bahut|kaafi|zyada|jyada) (zyada |jyada )?$CHARGE" +
        "|(itne|itna|itni|bahut|kaafi) (zyada |jyada )?$CHARGE ($KYUN|lage|lag rahe|kat rahe|gaye)" +
        "|(break ?down|breakup|break up|split) (of )?(my |today s |todays |this week s )$CHARGE|(my |today s |todays )$CHARGE (break ?down|breakup|break up|split)" +
        // Understanding round 27: "charges itne kyun lage", "charges itne kaise lage", "charges bahut zyada lag rahe hain", "how
        // come charges are so high", "why charges so high", "why high charges", "reason for high charges", "why are my charges more".
        "|$CHARGE (itne|itna|itni|bahut|kaafi) (zyada |jyada )?($KYUN|kaise|kese)" +
        "|$CHARGE (itne|itna|itni|bahut|kaafi|zyada|jyada) (zyada |jyada )?(lag rahe|lag raha|lag rahi|lagte|lagta|kat rahe|kat raha|ja rahe|ja raha|aa rahe|aa raha|aaye|aaya)" +
        "|how come (my |the |all |all the |today s |todays )?$CHARGE (are|is|were|was) (so |too |this |that |very |such )?(high|much|big|huge|expensive|heavy|more|higher)" +
        "|why (my |the )?$CHARGE (are |is |were |was )?$HIGH|why (so |too |such )?(high|heavy|huge|big) $CHARGE" +
        "|(reason|reasons|wajah|vajah) (for|of|behind|ki) (the |my |such |these )?(high|heavy|huge|big|so much|so many|more|zyada) $CHARGE) ")

    /**
     * The day's charges asked in detail (understanding round 27): what Zerodha actually charged - its contract note's figure
     * ("what did Zerodha actually charge", "contract note ke hisaab se charges", "exact charges", "is that charge an
     * estimate") - and the charges of one kind of trade ("charges on my futures", "delivery charges", "DP charges kitne
     * lage"). Answered as [whyAsked]'s is: the orders, fills and charges by kind of the day (or the span said), each fill on
     * its own schedule, with the contract note's figure for today when Zerodha gave one.
     */
    private const val SEG = "(dp|demat|delivery|futures|future|fut|stock|stocks|share|shares|equity|intraday|f o|fno|cnc|mis)"
    private const val NOTE = "(contract|contact|contracts|contacts) notes?"
    private val DETAIL = Regex(" ((what|how much) (did|has|have) (zerodha|kite|the broker|my broker) (actually |really |exactly |finally )?(charge|charged|take|took|cut|deduct|deducted|bill|billed)" +
        "|zerodha ne (actually |asal mein |asal me |sach mein )?(kitna|kitne|kitni|kya) $CHARGE? ?(kiya|kiye|liya|liye|kaata|kaate|kata|kate|lagaya|lagaye|charge kiya|charge kiye)" +
        "|$NOTE ((ke )?(hisaab|hisab) se |ke mutabik |ke anusar |according |wale |ka |ke |ki |says? |shows? )?$CHARGE" +
        "|$CHARGE (as per|according to|from|in|per|on|by|as on) (the |my |today s |todays |aaj ke |aaj ka |aaj ki )?$NOTE" +
        "|(what|kya) (does|did|do) (the |my |today s |todays |aaj ka |aaj ke |aaj ki )?$NOTE (say|show|says|shows)|$NOTE (kya|what) (kehta|kehti|bolta|bolti|batata|batati|says)" +
        // ("The actual charge of a Nifty option" is the schedule's question, not the day's: "for today" and "on my" only.)
        "|(exact|actual|real|final|precise|accurate|asli|sahi) (zerodha |broker )?$CHARGE(?! (of|for|on|per) (?!my |mere |today|todays|the day))" +
        "|(is|are) (this |that |these |those |the |my |today s |todays )?$CHARGE (figure |number |amount )?(exact|accurate|real|final|an estimate|estimated|just an estimate)" +
        "|(my|mine|today s|todays|aaj ke|aaj ka|aaj ki|total|how much|kitna|kitne|kitni) (in |on )?$SEG $CHARGE" +
        "|$SEG (ke |ka |ki |wale |par |pe )?$CHARGE (kitne|kitna|kitni|how much|lage|laga|lagi|today|this week|this month|last week|last month|so far)" +
        "|$CHARGE (on|for|of|in) (my|mere|the) $SEG|$CHARGE (on|for|in) $SEG( trades| trade| positions| orders)? (today|this week|this month|last week|last month|so far)) ")
    /** "DP charges", "delivery charges today", "futures charges": the kind of trade and the charges said alone. */
    private val DETAIL_ALONE = rx("^ $SEG $CHARGE( today| this week| this month| so far)?( please| boss| jarvis)? $|^ $CHARGE (break ?down|breakup|break up)( today| please| boss)? $")

    /**
     * Does [text] ask WHY the charges are so high (what drove them: orders, fills, who placed them, the kinds of charge), or
     * for the day's charges in that detail - Zerodha's exact figure, or one kind of trade's ([DETAIL])?
     */
    fun whyAsked(text: String): Boolean {
        val raw = norm(text)
        if (ONE.containsMatchIn(raw)) return false
        val read = norm(Ask.reading(text))
        return WHY.containsMatchIn(raw) || WHY.containsMatchIn(read) || DETAIL.containsMatchIn(raw) || DETAIL.containsMatchIn(read) ||
            DETAIL_ALONE.containsMatchIn(raw)
    }

    /** The span asked about; this month when none is said. */
    fun span(text: String): Span = said(text) ?: Span.MONTH

    /** The span said in [text], or null when none is. */
    private fun said(text: String): Span? {
        val t = norm(text)
        return when {
            rx(" (today|today s|todays|aaj|aaj ka|aaj ke) ").containsMatchIn(t) -> Span.TODAY
            rx(" (last|previous|past|pichle|pichla) (week|hafte|hafta) ").containsMatchIn(t) -> Span.LAST_WEEK
            rx(" (last|previous|past|pichle|pichla) (month|mahina|mahine) ").containsMatchIn(t) -> Span.LAST_MONTH
            rx(" (week|weekly|hafte|hafta) ").containsMatchIn(t) -> Span.WEEK
            rx(" (month|monthly|mahina|mahine) ").containsMatchIn(t) -> Span.MONTH
            else -> null
        }
    }

    /**
     * The span of a "why so high" question: the one said, or null when none is - then the day ([whyLines] takes today, or
     * the last day with fills when today has none).
     */
    fun whySpan(text: String): Span? = said(text)

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

    /**
     * One filled leg for "why are my charges so high": when, the order it belongs to, who placed that order ([owner]:
     * "Manual", "ORB", a Pine arm, "Jarvis"...), and the leg itself. An order that filled in several pieces is several
     * legs with one [orderId] (a blank one counts as its own order).
     */
    data class Leg(val at: LocalDateTime, val orderId: String, val owner: String, val side: String, val price: Double, val qty: Int,
                   /** The contract or share, for its schedule ([PnlCharges.segment]); blank: an option's. */
                   val symbol: String = "")

    /** The kinds of charge said, in this order, as said; SEBI's fee is put with the exchange's (it is on turnover too). */
    val KINDS: List<Pair<String, String>> = listOf("Brokerage" to "brokerage", "STT" to "STT", "Exchange" to "exchange", "GST" to "GST", "Stamp duty" to "stamp")

    /** Who placed an order, from its label in the app's owners ("ORB · entry", "Strategy: Pine X", null): "ORB", "Pine X", "Manual". */
    fun owner(label: String?): String =
        label?.substringBefore(" · ")?.removePrefix("Strategy: ")?.trim()?.ifEmpty { null }?.let { ArmOwners.arm(it) } ?: "Manual"

    private fun fills(legs: List<Leg>) =
        legs.map { PnlCharges.Fill(it.side, it.price, it.qty, it.orderId, symbol = it.symbol, day = it.at.toLocalDate().toString()) }

    /** [legs]' charges by kind ([KINDS]), with Zerodha's Rs 20 brokerage once per order ([PnlCharges.perFill]). */
    fun split(legs: List<Leg>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        KINDS.forEach { out[it.first] = 0.0 }
        PnlCharges.perFill(fills(legs)).forEach { m ->
            m.forEach { (k, v) -> val key = if (k == "SEBI") "Exchange" else k; out[key] = (out[key] ?: 0.0) + v }
        }
        return out
    }

    /** The orders among [legs]: each order id once, a blank one each its own. */
    fun orders(legs: List<Leg>): Int = legs.count { it.orderId.isBlank() } + legs.filter { it.orderId.isNotBlank() }.map { it.orderId }.distinct().size

    /** A source's orders, fills and charges ("ORB": 62 orders, 70 fills, Rs 1,500). */
    data class Source(val name: String, val orders: Int, val fills: Int, val charges: Double)

    /** Who placed [legs]' orders: each source's orders, fills and charges, the most orders first (then the most charges). */
    fun sources(legs: List<Leg>): List<Source> {
        val perLeg = PnlCharges.perFill(fills(legs)).map { it.values.sum() }
        return legs.indices.groupBy { legs[it].owner }.map { (o, ix) ->
            val mine = ix.map { legs[it] }
            Source(o, orders(mine), mine.size, ix.sumOf { perLeg[it] })
        }.sortedWith(compareByDescending<Source> { it.orders }.thenByDescending { it.charges })
    }

    /** The GST on brokerage and on the exchange's fees: Zerodha's Rs 20 an order comes to Rs 23.60. */
    private const val GST_RATE = 0.18

    /**
     * Why [label]'s ("Paper", "Zerodha") charges are what they are, from the fills of [span] (null: today, or the last day
     * with fills when today has none): the biggest driver first, in one sentence (the short answer), then the orders,
     * fills and round trips, the split into brokerage / STT / exchange / GST / stamp, a round trip's average, who placed
     * the orders and which source placed the most. [trips]: the round trips (those closed in the span are counted).
     * [estimated]: the charges are the app's estimate of Zerodha's; [exact]: Zerodha's own contract-note figure for the
     * day, when it answered. Facts only, never what to trade.
     */
    fun whyLines(label: String, legs: List<Leg>, trips: List<Trip>, span: Span?, today: LocalDate, estimated: Boolean = false, exact: Double? = null): List<String> {
        val (from, to) = when {
            span != null -> range(span, today)
            legs.any { it.at.toLocalDate() == today } -> today to today
            else -> (legs.map { it.at.toLocalDate() }.filter { !it.isAfter(today) }.maxOrNull() ?: today).let { it to it }
        }
        val period = when {
            span != null && from == to -> "${span.label} (${day(from)})"
            span != null -> "${span.label} (${day(from)} to ${day(to)})"
            from == today -> "today (${day(from)})"
            else -> "on ${day(from)}, the last day with fills"
        }
        val w = legs.filter { it.at.toLocalDate().let { d -> !d.isBefore(from) && !d.isAfter(to) } }
        if (w.isEmpty()) return listOf("$label: no fills $period, so no charges.")
        val parts = split(w)
        val total = parts.values.sum()
        val orders = orders(w)
        val rounds = trips.count { t -> t.closedAt.toLocalDate().let { !it.isBefore(from) && !it.isAfter(to) } }
        val who = sources(w)
        val top = who.first()

        // The biggest driver: brokerage with its GST (Rs 23.60 an order), STT on the sells, or the exchange's fees with theirs.
        val brokerage = parts.getValue("Brokerage") * (1 + GST_RATE)
        val stt = parts.getValue("STT")
        val exchange = parts.getValue("Exchange") * (1 + GST_RATE)
        val sells = w.filter { it.side.uppercase() != "BUY" }.sumOf { it.price * kotlin.math.abs(it.qty) }
        val driver = when {
            brokerage >= stt && brokerage >= exchange -> "mostly brokerage on ${plural(orders, "order")} (${amt(brokerage)} with GST)"
            stt >= exchange -> "mostly STT on ${amt(sells)} of sells (${amt(stt)})"
            else -> "mostly exchange fees on the turnover (${amt(exchange)} with GST)"
        }
        val placed = if (who.size > 1) "; ${top.name} placed ${top.orders} of the $orders orders" else "; all placed by ${top.name}"
        val out = ArrayList<String>()
        out += "$label charges $period: ${amt(total)}${if (estimated) " (the app's estimate)" else ""}, $driver$placed."
        out += "$label $period: ${plural(orders, "order")}, ${plural(w.size, "fill")}" +
            (if (w.size > orders) " (an order filled in pieces pays its Rs 20 brokerage once)" else "") + ", ${plural(rounds, "round trip")} closed."
        // A delivery sale's DP charge (round 27: "DP charges kitne lage") is said beside the kinds when there was one.
        val dp = parts["DP charges"]?.takeIf { it >= 0.005 }
        out += "$label split: " + KINDS.joinToString(", ") { (k, said) -> "$said ${amt(parts.getValue(k))}" } +
            (if (dp != null) ", DP ${amt(dp)}" else "") + "; ${amt(total)} in all."
        if (rounds > 0) out += "$label about ${amt(total / rounds)} in charges a round trip, " +
            "${"%.1f".format(Locale.ENGLISH, orders.toDouble() / rounds)} orders a round trip (one order in and one out is Rs 40 brokerage, Rs 47 with GST)."
        out += "$label orders by who placed them: " + who.joinToString(", ") { "${it.name} ${plural(it.orders, "order")} (${amt(it.charges)})" } + "."
        if (who.size > 1) out += "$label most orders: ${top.name}, ${top.orders} of $orders (${pct(top.orders.toDouble() / orders)}), ${amt(top.charges)} of the ${amt(total)} in charges."
        exact?.takeIf { PnlCharges.shown(it) && (span == null || span == Span.TODAY) && from == today }?.let { out += "$label: Zerodha's own contract note for ${day(from)} says ${amt(it)}." }
        return out
    }
}
