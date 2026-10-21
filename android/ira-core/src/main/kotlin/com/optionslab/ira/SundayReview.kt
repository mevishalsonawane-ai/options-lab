package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * Jarvis's spoken Sunday review (TODO I15; 20 Oct, enhancement round 2): about two minutes aloud, at most, on what worked
 * and what cost money over the trading week just ended, PAPER and LIVE kept strictly apart - each its own section, never
 * added together, and a week with no live trades said plainly. For each book: its trades, wins and net after charges, the
 * best and the worst arm by net (with their trade counts: the sample size is always said), the biggest single cost, and
 * when charges took more than the trades made. Then one thing to look at - words only, never to trade more or bigger, and
 * nothing is switched (Boss decides).
 *
 * The figures are in it, so the app says it only on an unlocked phone; a locked phone gets [READY_TITLE] / [READY_TEXT]
 * (no figure) and the review waits until Boss asks ([asked]). Facts from plain inputs: nothing here reads a clock, a file
 * or the network, and nothing places, changes, arms or stops anything. Pure.
 */
object SundayReview {
    /**
     * The words the text may hold (20 Oct): said aloud each rupee figure gains a word or two ("₹3,200" is "3,200 rupees"),
     * so 250 written words stay under about 300 spoken - two minutes at an unhurried 150 words a minute.
     */
    const val MAX_WORDS = 250
    /** A name longer than this (in letters or words) is cut at a word, so one arm cannot eat the two minutes. */
    const val NAME_CHARS = 32
    const val NAME_WORDS = 4

    /** The notification on a locked phone: that it is ready, nothing more (it may show on the lock screen). */
    const val READY_TITLE = "Sunday review ready"
    const val READY_TEXT = "Jarvis's review of the week is ready. Unlock the phone and ask for the Sunday review to hear it."

    enum class Book(val label: String) { PAPER("Paper"), LIVE("Live") }

    /** One closed round trip: its [book], the [arm] (or strategy) that took it, the [day] it closed, its [net] rupees after [charges]. */
    data class Trade(val book: Book, val arm: String, val day: LocalDate, val net: Double, val charges: Double)

    /** One arm's week in one book. */
    data class Row(val arm: String, val trades: Int, val won: Int, val net: Double, val charges: Double) {
        val gross: Double get() = net + charges
    }

    /** [text]: what is said (and put in the chat); [words] its count; each book's rows, best first (empty: none, or unread). */
    data class Said(val text: String, val words: Int, val paper: List<Row>, val live: List<Row>)

    // ---- the week ------------------------------------------------------------------------------------------------------

    /** The review is made on Sundays. */
    fun due(today: LocalDate): Boolean = today.dayOfWeek == DayOfWeek.SUNDAY

    /**
     * The Monday of the week reviewed on [today]: on a Saturday or Sunday the week just ending; on a weekday (Boss asked
     * mid-week) the last whole week, before this one.
     */
    fun weekFor(today: LocalDate): LocalDate {
        val monday = today.with(DayOfWeek.MONDAY)
        return if (today.dayOfWeek.value >= 6) monday else monday.minusWeeks(1)
    }

    // ---- words ---------------------------------------------------------------------------------------------------------

    private val SPACE = Regex("\\s+")
    private val WORDY = Regex("[\\p{L}\\p{N}]")
    private val SENTENCE = Regex("(?<=[.!?\\u0964])\\s+")

    /** The words in [text]: runs between spaces holding a letter or a digit ("₹3,200" is one, a lone dash none). */
    fun words(text: String): Int = text.trim().split(SPACE).count { WORDY.containsMatchIn(it) }

    /** The sentences in [text], as [Aloud.say] counts them (so the whole of it is said, none cut to "the rest is in the chat"). */
    fun sentences(text: String): Int = SENTENCE.split(text.trim()).count { it.isNotBlank() }

    private fun fmt(n: Long) = String.format(Locale.ENGLISH, "%,d", n)
    /** "up ₹3,200", "down ₹700", "level" (to the rupee: words that read aloud as they are written). */
    internal fun money(x: Double): String {
        val r = Math.round(x)
        return when { r > 0 -> "up ₹" + fmt(r); r < 0 -> "down ₹" + fmt(-r); else -> "level" }
    }
    private fun amt(x: Double) = "₹" + fmt(Math.round(abs(x)))
    private fun count(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"

    private val END_MARK = Regex("[.!?;:]+(?=\\s|$)")

    /**
     * An arm's name as it is said: its sentence marks dropped (a name never ends a sentence), spaces tidied, and cut at a
     * word to [NAME_WORDS] words and [NAME_CHARS] letters; a blank name is "an unnamed arm".
     */
    fun spoken(name: String): String {
        val clean = name.replace(END_MARK, " ").replace(SPACE, " ").trim()
        if (clean.isEmpty()) return "Unnamed arm"
        var ws = clean.split(' ').take(NAME_WORDS)
        while (ws.size > 1 && ws.joinToString(" ").length > NAME_CHARS) ws = ws.dropLast(1)
        val one = ws.joinToString(" ")
        return if (one.length > NAME_CHARS) one.take(NAME_CHARS).trimEnd() else one
    }

    // ---- one book ------------------------------------------------------------------------------------------------------

    /** Each arm's row, best net first (to the rupee), ties by name. */
    fun rows(trades: List<Trade>): List<Row> =
        trades.groupBy { spoken(it.arm) }.map { (arm, ts) -> Row(arm, ts.size, ts.count { Math.round(it.net) > 0 }, ts.sumOf { it.net }, ts.sumOf { it.charges }) }
            .sortedWith(compareByDescending<Row> { Math.round(it.net) }.thenBy { it.arm })

    /** A book's sentences: [must] always said, [extra] (the arms between the best and the worst) dropped first for time. */
    private class Part(val must: List<String>, val extra: List<String>)

    private fun book(b: Book, trades: List<Trade>?): Part {
        val word = b.label.lowercase()
        if (trades == null) return Part(listOf("${b.label}: the $word record could not be read this time, so I won't guess at it."), emptyList())
        if (trades.isEmpty()) return Part(listOf("${b.label}: no $word trades this week."), emptyList())
        val rows = rows(trades)
        val net = trades.sumOf { it.net }
        val charges = trades.sumOf { it.charges }
        val won = trades.count { Math.round(it.net) > 0 }
        val must = ArrayList<String>()
        must += "${b.label}: ${count(trades.size, "trade")}, $won won, net ${money(net)} after ${amt(charges)} charges."
        fun line(r: Row) = "${r.arm}, ${money(r.net)} on ${count(r.trades, "trade")}, ${r.won} won"
        val best = rows.first()
        val worst = rows.sortedWith(compareBy<Row> { Math.round(it.net) }.thenBy { it.arm }).first()
        when {
            rows.size == 1 -> must += "All of it was ${best.arm}."
            Math.round(best.net) == Math.round(worst.net) ->
                must += "${if (rows.size == 2) "Both arms" else "All ${rows.size} arms"} ended the same, ${money(best.net)} each" +
                    (if (rows.size <= 3) ": " + rows.joinToString(", ") { "${it.arm} on ${count(it.trades, "trade")}" } else "") + "."
            else -> {
                must += if (Math.round(best.net) > 0) "What worked: ${line(best)}." else if (Math.round(best.net) == 0L) "Nothing made money; the best was ${line(best)}."
                    else "Nothing made money; the smallest loss was ${line(best)}."
                must += if (Math.round(worst.net) < 0) "What cost money: ${line(worst)}." else "Nothing lost money; the weakest was ${line(worst)}."
            }
        }
        // The single trade that lost the most (its charges in it); ties: the earlier day, then the arm.
        val cost = trades.filter { Math.round(it.net) < 0 }.minWithOrNull(compareBy<Trade> { Math.round(it.net) }.thenBy { it.day }.thenBy { spoken(it.arm) })
        must += if (cost == null) "No single $word trade lost money."
            else "Biggest single cost: a ${spoken(cost.arm)} trade on ${WeeklyReview.dd(cost.day)}, ${money(cost.net)}, ${amt(cost.charges)} of that in charges."
        val gross = net + charges
        if (Math.round(gross) > 0 && Math.round(net) < 0)
            must += "Charges of ${amt(charges)} were more than the ${amt(gross)} the trades made before charges."
        val others = rows.filter { it !== best && it !== worst }
        val extra = if (others.isEmpty() || rows.size == 1) emptyList() else listOf("Also: " + others.joinToString("; ") { line(it) } + ".")
        return Part(must, extra)
    }

    // ---- the review ----------------------------------------------------------------------------------------------------

    private fun weekLine(monday: LocalDate, tradingDay: (LocalDate) -> Boolean): String {
        val days = (0L..4L).map { monday.plusDays(it) }
        val weekend = (5L..6L).map { monday.plusDays(it) }.filter(tradingDay)
        val sessions = days.count(tradingDay) + weekend.size
        val shut = days.filter { !tradingDay(it) }
        val head = "Boss, your Sunday review for the " + WeeklyReview.title(monday).replaceFirstChar { it.lowercase() }
        return when {
            sessions == 0 -> "$head: the market had no sessions that week."
            shut.isEmpty() -> "$head, ${count(sessions, "session")}."
            shut.size <= 2 -> "$head, a short week of ${count(sessions, "session")} (${shut.joinToString(" and ") { WeeklyReview.dd(it) }} shut)."
            else -> "$head, a short week of ${count(sessions, "session")}."
        }
    }

    /** The one thing to look at: real money first, then the costs, then paper - never to trade more or bigger; nothing switched. */
    private fun change(paper: List<Trade>?, live: List<Trade>?): String {
        // Each book's own count: the two are never added together, not even for the sample size.
        val small = listOfNotNull(paper?.size?.takeIf { it in 1 until 20 }?.let { "$it paper trade" + if (it == 1) "" else "s" },
            live?.size?.takeIf { it in 1 until 20 }?.let { "$it live trade" + if (it == 1) "" else "s" })
        val sample = when (small.size) {
            0 -> ""
            1 -> " ${small[0]} is a small sample: a note, not a verdict."
            else -> " ${small[0]} and ${small[1]} are small samples: a note, not a verdict."
        }
        fun worst(ts: List<Trade>?) = ts?.takeIf { it.isNotEmpty() }?.let { rows(it) }?.sortedWith(compareBy<Row> { Math.round(it.net) }.thenBy { it.arm })?.first()
            ?.takeIf { Math.round(it.net) < 0 }
        fun costsWon(ts: List<Trade>?) = ts != null && ts.isNotEmpty() && Math.round(ts.sumOf { it.net + it.charges }) > 0 && Math.round(ts.sumOf { it.net }) < 0
        val what = worst(live)?.let { "One thing to look at: ${it.arm} cost real money this week; go through its live trades before Monday." }
            ?: (if (costsWon(live)) "One thing to look at: live charges took more than the trades made; worth checking which of them were needed." else null)
            ?: (if (costsWon(paper)) "One thing to look at: paper charges took more than the trades made; worth checking which of them were needed." else null)
            ?: worst(paper)?.let { "One thing to look at: ${it.arm}'s paper trades, where the week's money went." }
            ?: "Nothing to change from one week's numbers."
        return "$what I changed nothing; that's your call.$sample"
    }

    /**
     * The review of the week from [monday] (to Sunday), from [paper] and [live] closed round trips (any order, any dates:
     * only that week's are used; null: that book could not be read, said as such - never as "no trades"). Every [Trade]
     * goes to the book it names, whichever list it came in. [tradingDay]: the exchange calendar (a short week is said).
     */
    fun compose(monday: LocalDate, paper: List<Trade>?, live: List<Trade>?, tradingDay: (LocalDate) -> Boolean): Said {
        val sunday = monday.plusDays(6)
        val all = (paper.orEmpty() + live.orEmpty()).filter { !it.day.isBefore(monday) && !it.day.isAfter(sunday) }
        val p = if (paper == null) null else all.filter { it.book == Book.PAPER }
        val l = if (live == null) null else all.filter { it.book == Book.LIVE }
        val pp = book(Book.PAPER, p)
        val lp = book(Book.LIVE, l)
        val head = weekLine(monday, tradingDay)
        val apart = if (!p.isNullOrEmpty() && !l.isNullOrEmpty()) listOf("Paper and live are kept apart; I never add them together.") else emptyList()
        val tail = listOf(change(p, l), "The full figures are in the chat.")
        // The arms between the best and the worst go first when it runs long: live's kept over paper's.
        var paperExtra = pp.extra
        var liveExtra = lp.extra
        fun text() = (listOf(head) + pp.must + paperExtra + lp.must + liveExtra + apart + tail).joinToString(" ")
        var t = text()
        if (words(t) > MAX_WORDS) { paperExtra = emptyList(); t = text() }
        if (words(t) > MAX_WORDS) { liveExtra = emptyList(); t = text() }
        // Never past the limit, whatever the names: cut at a word, and the chat named for the rest.
        if (words(t) > MAX_WORDS) {
            val end = "The rest is in the chat."
            val keep = t.trim().split(SPACE).take(MAX_WORDS - words(end))
            t = keep.joinToString(" ").trimEnd(',', ';', ':').let { if (it.endsWith(".")) it else "$it." } + " " + end
        }
        return Said(t, words(t), p?.let { rows(it) }.orEmpty(), l?.let { rows(it) }.orEmpty())
    }

    // ---- the question --------------------------------------------------------------------------------------------------

    private val ASK = Regex(" sunday (review|summary|recap|report|wrap|wrap up|briefing) |" +
        " (say|speak|read|tell) (me )?(out )?(the |my |this )?(weekly|week s|weeks|sunday) (review|summary|recap) (aloud|out loud) |" +
        " (say|speak|read) (me )?(out )?(the |my |this )?(weekly|week s|weeks|sunday) (review|summary|recap) |" +
        " (weekly|week s|weeks) (review|summary|recap) (aloud|out loud|bolo|suna do|sunao) ")

    /** Is the spoken Sunday review asked ("Sunday review", "read me the weekly review", "weekly review aloud")? */
    fun asked(text: String): Boolean = ASK.containsMatchIn(Spaced.joined(text))
}
