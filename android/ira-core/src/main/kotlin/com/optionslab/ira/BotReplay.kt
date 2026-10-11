package com.optionslab.ira

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * A closed bot trade replayed with commentary (S21): the option's minute candles over the hold with the entry and the exit
 * marked, a timeline of what the trade manager's specialists said on the way (its record's notes, each with who voted),
 * the order flow at the entry, why it exited, and the counterfactual - "the original rules would have exited at X for ₹Y"
 * (the record's own walk of the original rules on the option's 1-minute wicks). Reads only; it changes nothing. Pure.
 */
object BotReplay {
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    /** Minutes of candles shown before the entry and after the exit. */
    const val PAD_MIN = 5

    data class Replay(
        val title: String, val bars: List<TradeManager.Bar>, val entryMs: Long, val entry: Double, val exitMs: Long?, val exit: Double?,
        val timeline: List<String>, val flowAtEntry: String?, val exitWhy: String, val counterfactual: String?, val net: Double?,
        val best: Double?, val worst: Double?, val originalMs: Long?, val original: Double?,
    )

    private fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(IST).toLocalTime().toString().take(5)
    private fun px(x: Double) = TradeManager.num(x)

    /** The replay of [r] from [bars] (the option's minutes; any span: cut here to the hold and [PAD_MIN] around it). */
    fun of(r: TradeManager.Record, bars: List<TradeManager.Bar>, flowAtEntry: String? = null): Replay {
        val t = r.trade
        val exitMs = r.actual?.atMs
        val to = (exitMs ?: bars.lastOrNull()?.startMs ?: t.entryMs) + PAD_MIN * 60_000L
        val shown = bars.filter { it.startMs >= t.entryMs - PAD_MIN * 60_000L && it.startMs <= to }.sortedBy { it.startMs }
        val held = shown.filter { it.startMs >= t.entryMs - 60_000L && (exitMs == null || it.startMs <= exitMs) }
        // Always a bought option (the manager's rule): the premium's best is its high, its worst its low.
        val sideBest = held.maxOfOrNull { it.high }
        val sideWorst = held.minOfOrNull { it.low }
        val timeline = ArrayList<String>()
        timeline += "${hhmm(t.entryMs)} entry at ${px(t.entry)}" + (t.originalStop?.let { " · stop ${px(it)}" } ?: "") + (t.originalTarget?.let { " · target ${px(it)}" } ?: "")
        for (n in r.notes.sortedBy { it.atMs }) {
            val who = n.lead?.let { "${ManagerTeam.nameOf(it)}: " } ?: ""
            val votes = n.voters.filter { it != n.lead }.takeIf { it.isNotEmpty() }?.joinToString(", ") { ManagerTeam.nameOf(it) }?.let { " (also: $it)" } ?: ""
            timeline += "${hhmm(n.atMs)} ${if (n.acted) "" else "(shadow) "}${n.kind.lowercase(Locale.ENGLISH).replace('_', ' ')} - $who${n.words}$votes" +
                (n.premium?.let { " · option ${px(it)}" } ?: "")
        }
        r.actual?.let { timeline += "${hhmm(it.atMs)} exit at ${px(it.price)}: ${it.why}" }
        val net = r.actual?.let { (it.price - t.entry) * t.qty }
        val cf = r.original?.let { o ->
            val rs = (o.price - t.entry) * t.qty
            "The original rules would have exited at ${px(o.price)} (${o.why.lowercase(Locale.ENGLISH)}, ${hhmm(o.atMs)}) for ${TradeManager.rs(rs)}" +
                (net?.let { " - the trade made ${TradeManager.rs(it)}, ${TradeManager.rs(it - rs)} against them." } ?: ".")
        }
        val exitWhy = r.actual?.why?.let { why ->
            val early = r.notes.lastOrNull { it.acted && (it.kind == TradeManager.EXIT_EARLY || it.kind == TradeManager.LOCK_NOTE) }
            if (early != null) "The trade manager took it out: ${early.words}" else "Its own rules: ${why.lowercase(Locale.ENGLISH)}"
        } ?: "Still open."
        val title = "${t.label} · ${t.instrument} · ${t.account.name.lowercase(Locale.ENGLISH)}"
        return Replay(title, shown, t.entryMs, t.entry, exitMs, r.actual?.price, timeline, flowAtEntry, exitWhy, cf, net, sideBest, sideWorst,
            r.original?.atMs, r.original?.price)
    }

    /** Words for the order flow at the entry, from the flow's signal log nearest it ([agreement] AGREES / DISAGREES / UNKNOWN). */
    fun flowWords(agreement: String?, buyers: Int?, flags: List<String>): String? {
        if (agreement == null) return null
        val a = when (agreement.uppercase(Locale.ENGLISH)) { "AGREES" -> "agreed with the trade"; "DISAGREES" -> "was against the trade"; else -> "was unclear" }
        return "Order flow at the entry $a" + (buyers?.let { " (buyers $it of 100)" } ?: "") +
            (if (flags.isNotEmpty()) "; the trap guard flagged " + flags.joinToString(", ") { it.lowercase(Locale.ENGLISH).replace('_', ' ') } else "") + "."
    }

    /** The last closed bot trade whose strategy matches [words] ("liquidity", "orb fresh"; null: any). */
    fun pick(records: List<TradeManager.Record>, words: String?): TradeManager.Record? {
        val closed = records.filter { it.closed }.sortedBy { it.actual?.atMs ?: 0L }
        if (words.isNullOrBlank()) return closed.lastOrNull()
        val w = spacedWords(words.lowercase(Locale.ENGLISH))
        val fam = TradeManager.FAMILIES.filter { f ->
            val name = spacedWords(f.name.lowercase(Locale.ENGLISH)); val key = f.key.replace('_', ' ').replace('-', ' ')
            w.contains(key) || name.contains(w) || w.contains(name) || (w.split(' ').firstOrNull()?.let { it.length >= 3 && name.startsWith(it) } == true)
        }.sortedByDescending { it.key.length }.map { it.key }
        if (fam.isEmpty()) return null
        return closed.lastOrNull { it.trade.family == fam.first() } ?: closed.lastOrNull { it.trade.family in fam }
    }

    /** "Replay my last Liquidity trade": what Jarvis says as he opens it. */
    fun say(r: Replay?, words: String?): String {
        if (r == null) return "I have no closed ${words?.let { "$it " } ?: ""}bot trade to replay, Boss."
        return "Replaying ${r.title}. ${r.exitWhy}" + (r.counterfactual?.let { " $it" } ?: "")
    }

    // ---- the chart --------------------------------------------------------------------------------------------------------

    data class Candle(val x: Float, val top: Float, val bottom: Float, val high: Float, val low: Float, val up: Boolean)
    data class Dot(val x: Float, val y: Float)
    data class Layout(val candles: List<Candle>, val candleWidth: Float, val entry: Dot?, val exit: Dot?, val original: Dot?, val holdFrom: Float, val holdTo: Float)

    /** Where everything goes in a [w]×[h] box with [pad] around: the candles, the entry, the exit and the original rules' exit. */
    fun layout(r: Replay, w: Int, h: Int, pad: Int): Layout? {
        if (r.bars.isEmpty() || w <= 2 * pad || h <= 2 * pad) return null
        val lo = minOf(r.bars.minOf { it.low }, r.entry, r.exit ?: r.entry, r.original ?: r.entry)
        val hi = maxOf(r.bars.maxOf { it.high }, r.entry, r.exit ?: r.entry, r.original ?: r.entry)
        val span = max(1e-9, hi - lo)
        val t0 = r.bars.first().startMs; val t1 = max(t0 + 60_000L, r.bars.last().startMs + 60_000L)
        val iw = (w - 2 * pad).toFloat(); val ih = (h - 2 * pad).toFloat()
        fun x(ms: Long) = pad + iw * ((ms - t0).toFloat() / (t1 - t0).toFloat()).coerceIn(0f, 1f)
        fun y(p: Double) = pad + ih * (1f - ((p - lo) / span).toFloat())
        val cw = max(1f, iw / r.bars.size * 0.6f)
        val candles = r.bars.map { b -> Candle(x(b.startMs + 30_000L), y(max(b.open, b.close)), y(minOf(b.open, b.close)), y(b.high), y(b.low), b.close >= b.open) }
        return Layout(candles, cw, Dot(x(r.entryMs), y(r.entry)), r.exitMs?.let { e -> r.exit?.let { Dot(x(e), y(it)) } },
            r.originalMs?.let { o -> r.original?.let { Dot(x(o), y(it)) } }, x(r.entryMs), x(r.exitMs ?: t1))
    }

    /** What the chart's marks are, in a line. */
    fun legend(r: Replay): String = listOfNotNull("shaded: held", "● entry", r.exit?.let { "■ exit" }, r.original?.let { "◆ original rules' exit" }).joinToString(" · ")

    /** The best and worst prices while held, in words. */
    fun rangeLine(r: Replay): String? {
        val b = r.best ?: return null; val w = r.worst ?: return null
        return "While held the option went from ${px(w)} to ${px(b)} (entry ${px(r.entry)}" + (r.exit?.let { ", exit ${px(it)}" } ?: "") + ")" +
            (if (r.exit != null && abs(b - r.entry) > 1e-9) ", the exit took ${String.format(Locale.ENGLISH, "%.0f%%", 100 * ((r.exit - r.entry) / (b - r.entry)).coerceIn(-9.99, 9.99))} of the best move." else ".")
    }
}
