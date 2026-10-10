package com.optionslab.ira

import com.optionslab.engine.orb.Arm
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.ProfitLock
import com.optionslab.engine.orb.RangeFadeRules
import com.optionslab.engine.orb.SweepRules
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * Boss's bots' trades today, each explained as a chain of facts (Jarvis reasoning, round 13, 2026-10-05): "explain my
 * bots' trades today", "why did ORB take that trade?", "what did my bots do today?", "did my bots follow their rules?",
 * "mere bots ne aaj kya kiya". On 5 Oct the paper arms traded while the morning check said ORB's two-year test lost in both
 * years and ORB stayed armed; nothing put the day's trades and the rules side by side. For each of today's arm trades:
 * the signal that triggered the entry (the opening range and the break bar, the sweep, the fade of an edge, the liquidity
 * level broken), the exit and its reason (stop, target, the profit lock, the index stop, the time stop, the 15:10
 * square-off, Boss's own close), the points and rupees, and whether each matches the arm's written rule ([OrbRules],
 * [SweepRules], [RangeFadeRules], [LiquidityRules], [ProfitLock]) and its tested record ([TradeCheck.RECORD]). Then what does
 * not fit: an arm armed though its test lost in both years, two arms holding opposite sides of the same index at once, an
 * exit earlier than its rule, an entry its rule would not take, an entry outside its hours, more entries than its day's
 * limit. Facts from the arms' own books and the candles on the phone - never advice: what stays armed is Boss's call, and
 * nothing here arms, stops, places or closes anything. Pure.
 */
object BotTrades {
    /** What was asked: one arm's trades ([bot]: "orb", "orb_fresh", "orb_sweep", "range_fade", "liquidity"), or all (null). */
    data class Q(val bot: String? = null)

    /**
     * One arm trade today, as the arms' book keeps it: [source] the arm's id ("orb", "liquidity15"...), [right] "CE" or
     * "PE", [signalBar] the start of the bar that gave the signal, [why] the exit reason as booked, [live] at Zerodha,
     * [level]/[target] the index level broken and the next one (Liquidity only), [ladder] under the profit lock, [peak]
     * its best premium since entry, [trough] its worst (Liquidity 15+5: both kept while held, for [TradeLesson]; null: not
     * recorded), [lot] the contract's lot size at the entry (null: not known - [qty] is taken as one lot).
     */
    data class Trade(
        val source: String, val symbol: String, val right: String, val qty: Int, val entry: Double, val entryTime: LocalDateTime,
        val signalBar: LocalDateTime, val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null,
        val charges: Double = 0.0, val live: Boolean = false, val level: Double? = null, val target: Double? = null,
        val ladder: Boolean = false, val peak: Double? = null, val trough: Double? = null, val lot: Int? = null,
    )

    /** An arm's switch as the app shows it ([label] "ORB", "Liquidity 15+5"...). */
    data class Switch(val label: String, val armed: Boolean)

    enum class Flag { ARMED_TESTED_LOSING, OPPOSITE_SIDES, EARLY_EXIT, EXIT_OFF_RULE, ENTRY_OFF_RULE, OUTSIDE_HOURS, OVER_DAY_LIMIT }

    /** Something that does not fit, in words. */
    data class Note(val flag: Flag, val text: String)

    /** One trade's chain: who, the signal, the exit, the result; [notes] what in it does not fit its rule. */
    data class Chain(val trade: Trade, val label: String, val signal: String, val exit: String, val result: String, val notes: List<Note>)

    data class Read(val chains: List<Chain>, val notes: List<Note>)

    /** Points of slack for a fill against a rule's level (market orders fill a little past the trigger). */
    const val SLACK = 5.0
    /** At most this many trades are told one by one. */
    const val MAX_TOLD = 8

    const val NOTE = "Facts from the arms' own books and the candles on the phone, Boss - not advice; what stays armed is your call."
    const val LOCKED = "Unlock the phone for that, Boss."

    private val ARMS: List<Arm> = OrbRules.ARMS + SweepRules.ARM + RangeFadeRules.ARM + LiquidityRules.BOOKS

    private fun armOf(source: String): Arm? = (LiquidityRules.RENAMED[source] ?: source).let { s -> ARMS.firstOrNull { it.source == s } }

    /** The arm's name as Boss hears it; the tested record's name ([TradeCheck.RECORD]). */
    fun label(source: String): String = armOf(source)?.label ?: source
    internal fun recordName(source: String): String = armOf(source)?.let { if (it.liquidity) LiquidityRules.ARM.label else it.label } ?: source
    internal fun group(source: String): String = armOf(source)?.let { if (it.liquidity) "liquidity" else it.source } ?: source
    /** The arm group's name as its tested record names it ("orb" -> "ORB", "liquidity" -> "Liquidity 15+5"), or null. */
    internal fun groupName(bot: String): String? = ARMS.firstOrNull { group(it.source) == bot }?.let { recordName(it.source) }
    internal fun underlying(t: Trade): String = armOf(t.source)?.takeIf { it.liquidity }?.let { LiquidityRules.underlyingOf(it) }
        ?: t.symbol.uppercase(Locale.ENGLISH).let { s -> listOf("FINNIFTY", "MIDCPNIFTY").firstOrNull { s.startsWith(it) } } ?: OrbRules.UNDERLYING
    private fun index(u: String) = when (u) { "FINNIFTY" -> "FinNifty"; "MIDCPNIFTY" -> "Midcap Nifty"; else -> "BankNifty" }


    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%+,.2f".format(Locale.ENGLISH, x)
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.2f".format(Locale.ENGLISH, abs(x))
    private fun rs0(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun leg(right: String) = if (right.equals("PE", true)) "put" else "call"
    private fun side(right: String) = if (right.equals("PE", true)) -1 else 1

    // ---- the candles ---------------------------------------------------------------------------------------------

    /** The 5-minute bar starting at [start] from the 1-minute candles (at least three of its minutes), or null. */
    internal fun bar5(ones: List<Candle>, start: LocalDateTime): Candle? {
        val end = start.plusMinutes(5)
        val w = ones.filter { !it.t.isBefore(start) && it.t.isBefore(end) }
        if (w.size < 3) return null
        return Candle(start, w.first().o, w.maxOf { it.h }, w.minOf { it.l }, w.last().c)
    }

    /** The opening range (high, low) of the bars labelled 09:15 to 10:00 on [day]'s candles, once the 10:00 bar is in. */
    internal fun openingRange(ones: List<Candle>, day: java.time.LocalDate): Pair<Double, Double>? {
        val from = day.atTime(OrbRules.OR_START); val to = day.atTime(OrbRules.OR_END).plusMinutes(5)
        val w = ones.filter { !it.t.isBefore(from) && it.t.isBefore(to) }
        if (w.isEmpty() || w.last().t.isBefore(day.atTime(OrbRules.OR_END))) return null
        return w.maxOf { it.h } to w.minOf { it.l }
    }

    // ---- one trade's chain ---------------------------------------------------------------------------------------

    private fun signal(t: Trade, arm: Arm?, range: Pair<Double, Double>?, ones: List<Candle>, notes: MutableList<Note>): String {
        val name = label(t.source)
        val s = side(t.right)
        val barT = hm(t.signalBar)
        val tod = t.signalBar.toLocalTime()
        if (arm == null) return "Signal: the $barT bar."
        if (arm.liquidity) {
            val und = LiquidityRules.underlyingOf(arm)
            val mins = LiquidityRules.minutesOf(arm)
            val et = t.entryTime.toLocalTime()
            if (!LiquidityRules.mayEnterAt(t.entryTime))
                notes += Note(Flag.OUTSIDE_HOURS, "$name entered at ${hm(t.entryTime)}, outside its entry hours (${hm(LiquidityRules.FIRST_ENTRY)}-${hm(LiquidityRules.LAST_ENTRY)}).")
            val lv = t.level?.let { "the liquidity level at ${n(it)}" } ?: "a liquidity level (its price is not kept)"
            val way = if (s > 0) "above" else "below"
            val tgt = t.target?.let { " The next level beyond, ${n(it)}, is its index target." } ?: " No level lay beyond, so it had no index target."
            if (t.level != null && t.target != null && s * (t.target - t.level) <= 0)
                notes += Note(Flag.ENTRY_OFF_RULE, "$name's target ${n(t.target)} is not beyond the level it broke (${n(t.level)}) on the ${leg(t.right)}'s side.")
            return "Signal: the $barT bar on its $mins-minute ${index(und)} chart closed $way $lv (a pool on a swing zone), so it took the ${leg(t.right)} at ${hm(et)}.$tgt"
        }
        val rng = range ?: openingRange(ones, t.signalBar.toLocalDate())
        val (orh, orl) = rng ?: return "Signal: the $barT five-minute bar (the opening range is not on the phone)."
        val rangeWords = "the opening range ${n(orh)}-${n(orl)} (09:15-10:00)"
        // Its hours: the ORB and ORB Fresh decide on bars after 10:00 and before 14:00, ORB Sweep before 14:30, Range Fade 10:30-13:55.
        val (from, until) = when {
            arm.fade -> RangeFadeRules.FIRST_BAR to RangeFadeRules.LAST_BAR
            arm.sweep -> OrbRules.OR_END.plusMinutes(5) to OrbRules.SWEEP_LAST_ENTRY_BAR
            else -> OrbRules.OR_END.plusMinutes(5) to OrbRules.LAST_ENTRY_BAR
        }
        if (tod.isBefore(from) || !tod.isBefore(until))
            notes += Note(Flag.OUTSIDE_HOURS, "$name's signal bar $barT is outside the bars its rule decides on (${hm(from)} to ${hm(until.minusMinutes(5))}).")
        val b = bar5(ones, t.signalBar) ?: return "Signal: the $barT five-minute bar against $rangeWords (that bar's candles are not on the phone)."
        val feeds = " (the arm decides on its own feed; the candles here may differ a little)"
        val w = orh - orl
        return when {
            arm.fade -> {
                val ok = if (s < 0) b.h >= orh - RangeFadeRules.EDGE * w - 1e-9 && b.c < orh else b.l <= orl + RangeFadeRules.EDGE * w + 1e-9 && b.c > orl
                if (!ok) notes += Note(Flag.ENTRY_OFF_RULE, "On the phone's candles $name's $barT bar (high ${n(b.h)}, low ${n(b.l)}, close ${n(b.c)}) " +
                    "did not reach the ${if (s < 0) "top" else "bottom"} tenth of the range and close back inside$feeds.")
                if (s < 0) "Signal: the $barT bar reached ${n(b.h)}, in the top tenth of $rangeWords, and closed back below the high at ${n(b.c)}: a fade of the high, so it took the put."
                else "Signal: the $barT bar reached ${n(b.l)}, in the bottom tenth of $rangeWords, and closed back above the low at ${n(b.c)}: a fade of the low, so it took the call."
            }
            arm.sweep -> {
                val ok = if (s < 0) b.h > orh && b.c < orh else b.l < orl && b.c > orl
                if (!ok) notes += Note(Flag.ENTRY_OFF_RULE, "On the phone's candles $name's $barT bar (high ${n(b.h)}, low ${n(b.l)}, close ${n(b.c)}) " +
                    "did not go through the range ${if (s < 0) "high" else "low"} and close back inside$feeds.")
                if (s < 0) "Signal: the $barT bar's high ${n(b.h)} went above the range high ${n(orh)} ($rangeWords) and it closed back below at ${n(b.c)}: a failed break up, so it took the put."
                else "Signal: the $barT bar's low ${n(b.l)} went below the range low ${n(orl)} ($rangeWords) and it closed back above at ${n(b.c)}: a failed break down, so it took the call."
            }
            else -> {
                val ok = if (s > 0) b.c > orh else b.c < orl
                if (!ok) notes += Note(Flag.ENTRY_OFF_RULE, "On the phone's candles $name's $barT bar closed at ${n(b.c)}, " +
                    "${if (b.c in orl..orh) "inside the range" else "on the other side of the range"} - not a break ${if (s > 0) "up" else "down"} by its rule$feeds.")
                val fresh = if (arm.freshOnly) bar5(ones, t.signalBar.minusMinutes(5))?.let { p ->
                    val before = if (s > 0) p.c > orh else p.c < orl
                    if (before) { notes += Note(Flag.ENTRY_OFF_RULE, "$name takes only a fresh break, but on the phone's candles the bar before (${hm(p.t)}) had already closed ${if (s > 0) "above" else "below"} the range$feeds."); "" }
                    else " The bar before closed inside the range, so it was a fresh break."
                }.orEmpty() else ""
                if (s > 0) "Signal: the $barT bar closed at ${n(b.c)}, above the range high ${n(orh)} ($rangeWords): a break up, so it took the call.$fresh"
                else "Signal: the $barT bar closed at ${n(b.c)}, below the range low ${n(orl)} ($rangeWords): a break down, so it took the put.$fresh"
            }
        }
    }

    private fun exit(t: Trade, arm: Arm?, notes: MutableList<Note>): String {
        val name = label(t.source)
        val bought = "Bought at ${n(t.entry)} at ${hm(t.entryTime)}"
        val target = arm?.let { ProfitLock.targetOf(it) }
        val stopLevel: Double? = when {
            arm == null -> null
            arm.liquidity -> LiquidityRules.stopTrigger(t.entry)
            else -> (t.entry - OrbRules.STOP_POINTS).takeIf { it > 0 }
        }
        val stopWords = when {
            arm == null || stopLevel == null -> "its stop"
            arm.liquidity -> "its stop 15% under the price paid (${n(stopLevel)})"
            else -> "its ${OrbRules.STOP_POINTS.toInt()}-point stop (${n(stopLevel)})"
        }
        val px = t.exit ?: return "$bought; still open, with $stopWords" +
            (target?.let { "and its +${it.toInt()} target (${n(t.entry + it)})" }?.let { " $it" } ?: "") + "."
        val at = t.exitTime?.let { " at ${hm(it)}" } ?: ""
        val sold = "sold at ${n(px)}$at"
        val why = when (t.why) {
            "stop" -> {
                if (stopLevel != null && px > stopLevel + SLACK)
                    notes += Note(Flag.EARLY_EXIT, "$name's exit is booked as its stop, but it $sold - ${n(px - stopLevel)} above the stop level ${n(stopLevel)}.")
                "out at $stopWords, $sold"
            }
            "target" -> {
                if (target != null && px < t.entry + target - SLACK)
                    notes += Note(Flag.EXIT_OFF_RULE, "$name's exit is booked as its target, but it $sold - short of its +${target.toInt()} level ${n(t.entry + target)}.")
                "out at its target" + (target?.let { " (+${it.toInt()} points, ${n(t.entry + it)})" } ?: "") + ", $sold"
            }
            "profit_lock" -> {
                val lock = if (target != null && t.peak != null) ProfitLock.level(t.entry, target, t.peak) else null
                if (target != null && t.peak != null && lock == null)
                    notes += Note(Flag.EXIT_OFF_RULE, "$name's exit is booked as the profit lock, but its best price ${n(t.peak)} never reached the first rung (${n(t.entry + ProfitLock.LADDER.first().first * target)}).")
                if (lock != null && px > lock + SLACK)
                    notes += Note(Flag.EARLY_EXIT, "$name's exit is booked as the profit lock at ${n(lock)}, but it $sold, ${n(px - lock)} above it.")
                if (lock != null && target != null) {
                    val share = ((t.peak!! - t.entry) / target * 100).toInt().coerceAtLeast(0)
                    "out at the profit lock: its best price ${n(t.peak)} was $share% of the way to its target, which moved the stop to ${n(lock)}; $sold"
                } else "out at the profit lock, $sold"
            }
            "session_end" -> {
                if (t.exitTime != null && t.exitTime.toLocalTime().isBefore(OrbRules.SQUARE_OFF))
                    notes += Note(Flag.EARLY_EXIT, "$name's exit is booked as the ${hm(OrbRules.SQUARE_OFF)} square-off, but it sold at ${hm(t.exitTime)}.")
                "out at the ${hm(OrbRules.SQUARE_OFF)} square-off, $sold"
            }
            "time_stop" -> {
                val held = t.exitTime?.let { Duration.between(t.entryTime, it).toMinutes() }
                if (held != null && held < LiquidityRules.TIME_STOP_MINUTES)
                    notes += Note(Flag.EARLY_EXIT, "$name's exit is booked as its ${LiquidityRules.TIME_STOP_MINUTES}-minute time stop, but it sold after $held minute${if (held == 1L) "" else "s"}.")
                "out at its time stop (not up ${(LiquidityRules.TIME_STOP_GAIN * 100).toInt()}% ${LiquidityRules.TIME_STOP_MINUTES} minutes after entry), $sold"
            }
            "index_stop" -> {
                val p = LiquidityRules.indexStopPoints(underlying(t)).toInt()
                "out at its index stop (the index traded $p points back through the level it broke" + (t.level?.let { ", ${n(it)}" } ?: "") + "), $sold"
            }
            "failed_break" -> "out on a failed break (a bar closed back through the level it broke" + (t.level?.let { ", ${n(it)}" } ?: "") + "), $sold"
            "new_liquidity" -> "out on new liquidity (a new level formed on its side), $sold"
            "operator_stop" -> "out when you stopped it for today, $sold"
            "closed_by_you" -> "closed by you, $sold"
            "backstop_square_off" -> "out at the app's backstop square-off, $sold"
            null -> sold
            else -> "out (${t.why.replace('_', ' ')}), $sold"
        }
        if (t.exitTime != null && t.why != "session_end" && !t.exitTime.toLocalTime().isBefore(OrbRules.SQUARE_OFF.plusMinutes(5)))
            notes += Note(Flag.EXIT_OFF_RULE, "$name still held at ${hm(t.exitTime)}, past its ${hm(OrbRules.SQUARE_OFF)} square-off.")
        return "$bought; $why."
    }

    private fun result(t: Trade, now: LocalDateTime): String {
        val where = if (t.live) "at Zerodha" else "paper"
        val px = t.exit ?: return "Result: open ($where, ${t.qty} units)."
        val p = px - t.entry
        val gross = p * t.qty
        return "Result: ${pts(p)} points on ${t.qty} units ($where), ${rs(gross)}" +
            (if (t.charges > 0) ", ${rs(gross - t.charges)} after ${n(t.charges)} charges" else "") + "."
    }

    /**
     * A closed Liquidity 15+5 trade against its research ([TradeLesson]): per lot, with the best and worst premium seen
     * while held when kept. Null for an open trade or another arm.
     */
    fun lesson(t: Trade): TradeLesson.Lesson? {
        val arm = armOf(t.source)?.takeIf { it.liquidity } ?: return null
        val px = t.exit ?: return null
        val held = t.exitTime?.let { Duration.between(t.entryTime, it).toMinutes().coerceAtLeast(0) } ?: return null
        val lots = t.lot?.takeIf { it > 0 }?.let { t.qty.toDouble() / it }?.takeIf { it > 0 } ?: 1.0
        val lotSize = t.lot?.takeIf { it > 0 } ?: t.qty.coerceAtLeast(1)
        val net = (px - t.entry) * t.qty - t.charges
        return TradeLesson.of(TradeLesson.Closed("${index(LiquidityRules.underlyingOf(arm))} ${LiquidityRules.minutesOf(arm)}-min", t.right,
            t.entry, px, t.why ?: "exit", held, net / lots, t.charges / lots, lotSize, t.peak, t.trough))
    }

    /** Each of [trades] chained, and what does not fit across the day. */
    fun read(trades: List<Trade>, switches: List<Switch>, range: Pair<Double, Double>?, ones: List<Candle>, now: LocalDateTime): Read {
        val sorted = trades.sortedBy { it.entryTime }
        val chains = sorted.map { t ->
            val arm = armOf(t.source)
            val notes = ArrayList<Note>()
            val sig = signal(t, arm, range, ones, notes)
            val ex = exit(t, arm, notes)
            Chain(t, label(t.source), sig, ex, result(t, now), notes)
        }
        val out = ArrayList<Note>()
        chains.forEach { out += it.notes }
        // More entries than the day's limit (ORB Sweep and Range Fade take at most two a day).
        sorted.groupBy { it.source }.forEach { (src, ts) ->
            val arm = armOf(src) ?: return@forEach
            val cap = when { arm.sweep -> SweepRules.MAX_ENTRIES; arm.fade -> RangeFadeRules.MAX_ENTRIES; else -> null } ?: return@forEach
            if (ts.size > cap) out += Note(Flag.OVER_DAY_LIMIT, "${arm.label} took ${ts.size} trades today; its rule takes at most $cap a day.")
        }
        // Two arms on opposite sides of the same index at the same time.
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            val a = sorted[i]; val b = sorted[j]
            if (a.source == b.source || side(a.right) == side(b.right) || underlying(a) != underlying(b)) continue
            val from = maxOf(a.entryTime, b.entryTime)
            val to = minOf(a.exitTime ?: now, b.exitTime ?: now)
            val mins = Duration.between(from, to).toMinutes()
            if (mins <= 0) continue
            out += Note(Flag.OPPOSITE_SIDES, "${label(a.source)} held a ${leg(a.right)} (${hm(a.entryTime)}-${a.exitTime?.let { hm(it) } ?: "open"}) while " +
                "${label(b.source)} held a ${leg(b.right)} (${hm(b.entryTime)}-${b.exitTime?.let { hm(it) } ?: "open"}) on ${index(underlying(a))}: " +
                "opposite sides for $mins minute${if (mins == 1L) "" else "s"}.")
        }
        // An arm armed though its tested record lost in both years.
        switches.filter { it.armed }.forEach { s ->
            val rec = TradeCheck.RECORD[s.label] ?: return@forEach
            if (rec.first < 0 && rec.second < 0)
                out += Note(Flag.ARMED_TESTED_LOSING, "${s.label} is armed, though its two-year BankNifty test lost in both years (${rs0(rec.first)}, ${rs0(rec.second)} a lot).")
        }
        return Read(chains, out.distinct())
    }

    private fun tested(name: String): String? {
        val rec = TradeCheck.RECORD[name] ?: return null
        val both = "${rs0(rec.first)}, ${rs0(rec.second)} a lot"
        return when {
            rec.first > 0 && rec.second > 0 -> "$name made money in both tested years ($both)"
            rec.first < 0 && rec.second < 0 -> "$name lost in both tested years ($both)"
            else -> "$name made money in one tested year and lost in the other ($both)"
        }
    }

    /** The answer to [q] from today's arm trades. */
    fun answer(q: Q, trades: List<Trade>, switches: List<Switch>, range: Pair<Double, Double>?, ones: List<Candle>, now: LocalDateTime): String {
        val day = now.toLocalDate()
        val todays = trades.filter { it.entryTime.toLocalDate() == day }
        val asked = q.bot?.let { b -> todays.filter { group(it.source) == b } } ?: todays
        val askedSwitches = q.bot?.let { b -> switches.filter { s -> ARMS.any { a -> group(a.source) == b && recordName(a.source) == s.label } } } ?: switches
        val r = read(asked, askedSwitches, range, ones, now)
        val out = ArrayList<String>()
        val who = q.bot?.let { b -> ARMS.firstOrNull { group(it.source) == b }?.let { recordName(it.source) } } ?: "your bots"
        if (asked.isEmpty()) {
            out += if (q.bot != null) "$who has not traded today, Boss." else "None of your bots has traded today, Boss."
            val armed = askedSwitches.filter { it.armed }.map { it.label }
            out += if (armed.isEmpty()) "None is armed now." else "Armed now: ${armed.joinToString(", ")}."
        } else {
            val closed = asked.filter { it.exit != null }
            val net = closed.sumOf { (it.exit!! - it.entry) * it.qty - it.charges }
            val live = asked.count { it.live }
            out += "Boss, ${if (q.bot != null) who else "your bots"} took ${asked.size} trade${if (asked.size == 1) "" else "s"} today" +
                (if (live > 0) " ($live at Zerodha, ${asked.size - live} on paper)" else " (paper)") +
                (if (closed.isNotEmpty()) ", ${closed.size} closed for ${rs(net)} after charges" else "") +
                (if (closed.size < asked.size) ", ${asked.size - closed.size} still open" else "") + "."
            r.chains.take(MAX_TOLD).forEachIndexed { i, c ->
                out += "${i + 1}. ${c.label}, ${c.trade.symbol}: ${c.signal} ${c.exit} ${c.result}" +
                    // A closed Liquidity 15+5 trade: what it looks like against the arm's research (the line Jarvis said at its close).
                    (lesson(c.trade)?.let { " Against the research: ${it.text}" } ?: "")
            }
            if (r.chains.size > MAX_TOLD) out += "And ${r.chains.size - MAX_TOLD} more trades; ask for one arm by name for those."
        }
        val names = (asked.map { recordName(it.source) } + askedSwitches.filter { it.armed }.map { it.label }).distinct()
        val recs = names.mapNotNull { tested(it) }
        if (recs.isNotEmpty()) out += "Tested over two BankNifty years: " + recs.joinToString("; ") + "."
        out += if (r.notes.isEmpty()) {
            if (asked.isEmpty()) "Nothing contradicts." else "What doesn't fit: nothing - each trade matches its arm's written rule as far as the phone shows."
        } else "What doesn't fit: " + r.notes.joinToString(" ") { it.text }
        out += NOTE
        return out.joinToString("\n")
    }

    // ---- the question --------------------------------------------------------------------------------------------

    private fun norm(text: String) = Spaced.joined(text)

    private const val BOT = "((paper bots?|bots?|algos?|arms?|orb fresh|orb sweep|orb|range fade|liquidity( 15 5| 15| 5| 30| bot| arm| books?)?)s?)"
    private const val MY = "(my |our |the |mere |meri |mera |hamare |todays |today s )?"
    private const val DID = "(trades?|entries|exits|signals?|positions?)"
    private val ASK = listOf(
        // "Explain my bots' trades today", "walk me through ORB's trades", "break down the Range Fade trade"
        " (explain|walk me through|walk through|talk me through|take me through|break down|go through|go over|review|tell me about) (all |each of )?$MY(paper )?$BOT (s )?(todays |today s )?$DID ",
        " (explain|walk me through|break down|go through|go over|review) (the |todays |today s )?$DID (my |the |our )?$BOT (took|made|placed|did|had) ",
        " (explain|walk me through|break down) (what )?$MY$BOT (did|has done|have done)( today)? ",
        // "Why did ORB take that trade?", "why did my bots trade today?", "why did the liquidity bot exit?"
        " why did $MY$BOT (trade|take|took|enter|exit|get out|close|square off|lose|win|fire|trigger|go long|go short|make (a |that |this |the |its |those )?trades?)",
        " why (was|were) $MY$BOT (trades?|entry|exit|entries|exits) ",
        // "What did my bots do today?", "what trades did ORB take?"
        " what (did|have|has) $MY$BOT (do|done|trade|traded)( today| aaj)? ",
        " what (trades|entries|positions) (did|have|has) $MY$BOT (take|taken|make|made|do|done) ",
        // "Did my bots follow their rules?", "did ORB stick to its rules today?"
        " (did|have|has) $MY$BOT (follow|followed|keep to|kept to|stick to|stuck to|stick with|obey|obeyed|break|broken|broke|keep|kept) (their|its|the) (own )?rules? ",
        " (were|was|are|is) $MY$BOT (trades?|entries|exits) (by|within|per|according to|in line with|following) (the |their |its )?rules? ",
        // "Any contradictions in my bots?", "are my bots fighting each other?", "did my bots take opposite sides?"
        " (any )?(contradictions?|conflicts?|clash|clashes) (in|between|among|with) $MY$BOT ",
        " (are|were) $MY$BOT (contradicting|fighting|against|trading against) (each other|one another) ",
        " (did|have) $MY(two )?$BOT (take|taken|took) opposite (sides|trades|positions) ",
        // "Today's bot trades", "my bots' trades today"
        "^ $MY(paper )?$BOT (s )?trades( today| aaj| explained)? $",
        // Hinglish: "mere bots ne aaj kya kiya", "ORB ne trade kyun liya", "bots ke trades samjhao"
        " $MY$BOT ne (aaj )?(kya kiya|kya trade (kiya|liya)|kaun se trades? (liye|liya|kiye)|trades? kyun|trade kyon|trade kyu|kyun (liya|kiya|trade)|kyon (liya|kiya)|kyu (liya|kiya)|exit kyun|exit kyon|exit kyu) ",
        " $MY$BOT (ke|ka|ki) (aaj ke )?(trades?|entry|exit) (samjhao|samjha do|explain karo|batao|bata do|explain) ",
        " $MY$BOT (ne )?(apne )?(rules?|niyam) (follow|maane|mana|tode|toda) ",
    ).map { rx(it) }
    /** What every alternative of [BOT] holds: no [ASK] can match words without one of these. */
    private val BOT_WORDS = listOf("bot", "algo", "arm", "orb", "range fade", "liquidity")
    /** Not this: a command, a backtest or the regime fit, how they are doing (BotHealth's), another day, a forecast or advice. */
    private val NOT = rx(" (stop|start|disarm|arm it|switch|turn on|turn off|pause|backtest|back test|create|build|write|add|set up|suit|suits|should|shall|will|would|tomorrow|yesterday|kal|week|month|how are|doing|performing|health|behaving) ")

    /** "Explain my bots' trades today", "why did ORB take that trade?", "what did my bots do today?", "did my bots follow their rules?". */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        for (s in listOf(text, Ask.reading(text))) {
            val t = norm(s)
            // (Every [ASK] names a bot ([BOT]): words naming none are passed over before the patterns - speed round 10.)
            if (BOT_WORDS.none { t.contains(it) }) continue
            if (NOT.containsMatchIn(t) || ASK.none { it.containsMatchIn(t) }) continue
            val bot = when {
                rx(" orb fresh ").containsMatchIn(t) -> "orb_fresh"
                rx(" orb sweep ").containsMatchIn(t) -> "orb_sweep"
                rx(" range fade ").containsMatchIn(t) -> "range_fade"
                rx(" liquidity ").containsMatchIn(t) -> "liquidity"
                rx(" orbs? ").containsMatchIn(t) -> "orb"
                else -> null
            }
            return Q(bot)
        }
        return null
    }
}
