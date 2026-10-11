package com.optionslab.ira

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * "Today's lesson" (S20): after the close, one card on the Ira page built from TODAY's real records only - never a generic
 * tip. In order of preference:
 *
 *  1. the day's biggest move the recorder saved, with what preceded it (what stood out first, and how long before);
 *  2. the bot trade with the clearest lesson: the trade manager against the original rules by at least [CLEAR_RS], or the
 *     day's biggest bot loss with how it exited;
 *  3. a habit of Boss's own that today's hand trades touched ([BossTrades.todayInsight]).
 *
 * When nothing qualifies there is no card. Pure: the app hands over today's records.
 */
object DailyLesson {
    /** A manager-vs-original gap (or a loss) at least this big makes a bot trade's lesson clear enough. */
    const val CLEAR_RS = 300.0
    /** A saved move at least this many times its normal size counts. */
    const val MIN_X = 4.7
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** A saved big move: index, its candle's start (epoch seconds), its size (bp, signed) and how many times normal, and what came first. */
    data class Move(val name: String, val startSec: Long, val retBp: Double, val x: Double, val first: List<Pair<String, Long>>)

    /** A closed bot trade today: its label, net, the manager against the original rules (null: not settled), how it exited, the original's exit. */
    data class BotTrade(val label: String, val net: Double, val vsOriginal: Double?, val exitWhy: String, val originalWhy: String?, val acted: Boolean)

    enum class Kind(val words: String) { MOVE("the day's biggest move"), BOT("a bot trade"), HABIT("your own trades") }

    data class Lesson(val day: LocalDate, val kind: Kind, val title: String, val text: String)

    private fun hhmm(sec: Long) = Instant.ofEpochSecond(sec).atZone(IST).toLocalTime().toString().take(5)
    private fun rs(x: Double): String = (if (x < 0) "−₹" else "₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))

    /** Plain words for what stood out first ("the future's buying", "VIX") from the recorder's series names. */
    fun seriesWords(s: String): String = when {
        s.contains("option", true) -> "the options' signed volume"
        s.contains("future", true) || s.contains("delta", true) -> "the future's order flow"
        s.contains("thinning", true) || s.contains("book", true) || s.contains("depth", true) -> "the order book in the way thinning"
        s.contains("vix", true) -> "India VIX"
        s.contains("cash index", true) -> "the cash index"
        else -> s.replace('_', ' ')
    }

    /** Today's lesson from [moves], [bots] and [habit], or null when nothing of today's qualifies. */
    fun of(day: LocalDate, moves: List<Move>, bots: List<BotTrade>, habit: String?): Lesson? {
        val m = moves.filter { it.x >= MIN_X && !it.retBp.isNaN() }.maxByOrNull { abs(it.retBp) }
        if (m != null) {
            val dir = if (m.retBp >= 0) "up" else "down"
            val pct = String.format(Locale.ENGLISH, "%.2f%%", abs(m.retBp) / 100)
            val before = m.first.filter { it.second < 0 }.sortedBy { it.second }.take(2)
            val lead = if (before.isEmpty()) "Nothing the recorder follows stood out before it: it came without warning - the kind of move a stop has to survive."
                else "Before it: " + before.joinToString(", then ") { "${seriesWords(it.first)} ${abs(it.second)} s ahead" } +
                    ". One case is not a rule: the recorder keeps collecting these to see whether the same signs come first again."
            return Lesson(day, Kind.MOVE, "${OrderFlow.label(m.name)} ${dir} $pct at ${hhmm(m.startSec)}",
                "${OrderFlow.label(m.name)} moved $pct $dir in one minute at ${hhmm(m.startSec)}, ${String.format(Locale.ENGLISH, "%.1f", m.x)}× its normal minute. $lead")
        }
        val clear = bots.filter { it.vsOriginal != null && abs(it.vsOriginal) >= CLEAR_RS }.maxByOrNull { abs(it.vsOriginal!!) }
        if (clear != null) {
            val v = clear.vsOriginal!!
            val who = if (clear.acted) "The trade manager's exit" else "The trade manager (in shadow)"
            val text = "$who on ${clear.label} ${if (v >= 0) "beat" else "trailed"} the original rules by ${rs(abs(v))}: it exited on ${clear.exitWhy.lowercase(Locale.ENGLISH)}" +
                (clear.originalWhy?.let { ", the original rules would have exited on ${it.lowercase(Locale.ENGLISH)}" } ?: "") + ". " +
                (if (v >= 0) "One trade is not proof; its record across trades decides." else "One trade is not proof - but it is why the manager stays on probation.")
            return Lesson(day, Kind.BOT, "${clear.label}: manager ${if (v >= 0) "+" else "−"}${rs(abs(v)).removePrefix("−")} against the original rules", text)
        }
        val loss = bots.filter { it.net <= -CLEAR_RS }.minByOrNull { it.net }
        if (loss != null) return Lesson(day, Kind.BOT, "${loss.label} lost ${rs(-loss.net)}",
            "${loss.label} lost ${rs(-loss.net)} today and exited on ${loss.exitWhy.lowercase(Locale.ENGLISH)}. Replay it (Trade manager record → tap the trade) to see what the specialists said on the way.")
        if (!habit.isNullOrBlank()) return Lesson(day, Kind.HABIT, "Your own trades", habit)
        return null
    }

    /** "What's today's lesson?" */
    /** Said on a locked phone for a lesson from Boss's own trades or a bot's rupees (review R5 #4); a market move's is said. */
    const val LOCKED = "Unlock the phone for today's lesson, Boss: it is from your own trades and the bots' rupees."

    fun say(l: Lesson?, closed: Boolean, locked: Boolean = false): String = when {
        l != null && locked && l.kind != Kind.MOVE -> LOCKED
        l != null -> "Today's lesson, from ${l.kind.words}: ${l.text}"
        !closed -> "Today's lesson comes after the close, Boss, from today's own records."
        else -> "No lesson today, Boss: nothing in today's records was clear enough to learn from - no big move saved, no bot trade with a clear lesson, none of your own trades touching a habit."
    }

    // ---- the file (one lesson a day) ---------------------------------------------------------------------------------------

    fun encode(l: Lesson): String = listOf("L1", l.day, l.kind.name, l.title.replace('|', ' '), l.text.replace('|', ' ').replace('\n', ' ')).joinToString("|")
    fun decode(s: String?): Lesson? {
        val p = s?.trim()?.split('|') ?: return null
        if (p.size < 5 || p[0] != "L1") return null
        return runCatching { Lesson(LocalDate.parse(p[1]), Kind.valueOf(p[2]), p[3], p[4]) }.getOrNull()
    }
}
