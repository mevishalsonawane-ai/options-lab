package com.optionslab.ira

import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * Liquidity 15+5's open position, live, as its panel on the Strategies card shows it: the contract, size, entry, the
 * premium now and the P&L (per lot and in all, before charges and an estimate after them), each exit as it stands with
 * how far away it is, the best and worst premium so far, and where the index sits between the index stop and the target.
 *
 * The exits are described exactly as the arm runs them (OrbArms.priceCheck / liquidityExits, [LiquidityRules]); nothing
 * here decides or changes one:
 *  - premium stop  the position's own [Input.stopTrigger] (15% below the fill, [LiquidityRules.stopTrigger]); it rests as
 *                  an order, and with no resting order the app sells when the premium is at or below it
 *  - index stop    a 1-minute bar trading beyond the broken level by [LiquidityRules.indexStopPoints] (BANKNIFTY 30,
 *                  FINNIFTY 15, MIDCPNIFTY 8): below level − pts for a call, above level + pts for a put
 *  - target        a 1-minute bar touching the next liquidity level ([Input.target]); none when there was no level ahead
 *  - time stop     at entry + [LiquidityRules.TIME_STOP_MINUTES], out unless the premium is [LiquidityRules.TIME_STOP_GAIN]
 *                  above the price paid ([LiquidityRules.timeStopFails]); decided once, then held ([Input.timed])
 *  - 15:10         [OrbRules.SQUARE_OFF]: sold at the session's end
 *  - and a completed bar closing back through the level (failed break) or new liquidity forming on its side.
 *
 * Charges after the exit are an estimate on the app's own paper charges ([SandboxCosts.charge]): the buy's charges as
 * booked (or estimated when none were), plus a sell of the whole quantity at the premium now. Pure: no clock, no I/O.
 */
object LiquidityOpen {
    /**
     * What the panel reads: the position's fields, [ltp] the premium now (the arm's mark; null when not read yet),
     * [index] / [indexAt] the last index price the arm read and its minute (null: none read), [lots] the lots bought
     * (null: not known, then counted as 1), [buyCharges] the entry's charges as booked (0: none booked).
     */
    data class Input(
        val underlying: String, val right: String, val symbol: String, val qty: Int, val lots: Double?, val live: Boolean,
        val entry: Double, val entryTime: LocalDateTime, val ltp: Double?, val stopTrigger: Double?, val stopResting: Boolean,
        val level: Double?, val target: Double?, val index: Double?, val indexAt: LocalDateTime?,
        val peak: Double?, val low: Double?, val timed: Boolean, val buyCharges: Double,
    )

    /** One exit's row: its [name], the [rule] it follows, [away] how far it is now (or its state), [near] when it is close or due. */
    data class Exit(val name: String, val rule: String, val away: String, val near: Boolean = false)

    /**
     * The index between the index stop ([stop], 0) and the target ([target], 1): [fraction] where it is now, clamped to 0..1;
     * [label] the bar's words.
     */
    data class Progress(val fraction: Float, val stop: Double, val target: Double, val index: Double, val label: String)

    data class View(
        val title: String, val contract: String, val entered: String, val now: String, val pnl: String, val afterCharges: String?,
        val exits: List<Exit>, val range: String, val progress: Progress?, val also: String, val asOf: String,
        /** Before charges, in all and a lot (null: no price yet), and the estimate after charges. */
        val gross: Double?, val grossPerLot: Double?, val net: Double?,
    )

    /** Side of the trade: a call +1, a put −1. */
    fun side(right: String): Int = if (right.uppercase() == "PE") -1 else 1

    /** The index stop's level: the broken level less (call) or plus (put) the index's stop points. */
    fun indexStopLevel(right: String, level: Double, underlying: String): Double =
        level - side(right) * LiquidityRules.indexStopPoints(underlying)

    /** Index points from [index] to the index stop (negative: already beyond it). */
    fun pointsToIndexStop(right: String, level: Double, underlying: String, index: Double): Double =
        side(right) * (index - indexStopLevel(right, level, underlying))

    /** Index points from [index] to the target (negative: already past it). */
    fun pointsToTarget(right: String, target: Double, index: Double): Double = side(right) * (target - index)

    /** Where [index] sits between the index stop (0) and the target (1), clamped; null when there is no room between them. */
    fun fraction(right: String, level: Double, target: Double, underlying: String, index: Double): Float? {
        val stop = indexStopLevel(right, level, underlying)
        val span = side(right) * (target - stop)
        if (!(span > 0)) return null
        return (side(right) * (index - stop) / span).coerceIn(0.0, 1.0).toFloat()
    }

    /** When the time stop is checked: [LiquidityRules.TIME_STOP_MINUTES] after the entry. */
    fun timeStopAt(entryTime: LocalDateTime): LocalDateTime = entryTime.plusMinutes(LiquidityRules.TIME_STOP_MINUTES)

    /** The premium the time stop asks for: [LiquidityRules.TIME_STOP_GAIN] above the price paid. */
    fun timeStopPrice(entry: Double): Double = entry * (1 + LiquidityRules.TIME_STOP_GAIN)

    /** The time stop's gain condition met at [ltp] (exactly the arm's test, [LiquidityRules.timeStopFails]). */
    fun gainMet(entry: Double, ltp: Double): Boolean = !LiquidityRules.timeStopFails(entry, ltp)

    /** "mm:ss" (or "h:mm:ss" from an hour) to [until] from [now]; "00:00" once it has come. */
    fun countdown(now: LocalDateTime, until: LocalDateTime): String {
        val s = Duration.between(now, until).seconds.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.ENGLISH, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ENGLISH, "%02d:%02d", m, sec)
    }

    /** Estimated charges of the round trip: the buy's as booked (estimated when 0) plus a sell of [qty] at [ltp]. */
    fun chargesEstimate(entry: Double, ltp: Double, qty: Int, buyCharges: Double): Double {
        val buy = if (buyCharges > 0) buyCharges else SandboxCosts.charge("BUY", BigDecimal(entry), qty).toDouble()
        return buy + SandboxCosts.charge("SELL", BigDecimal(ltp), qty).toDouble()
    }

    fun rupees(x: Double): String {
        val r = Math.round(x)
        return (if (r < 0) "−₹" else if (r > 0) "+₹" else "₹") + String.format(Locale.ENGLISH, "%,d", abs(r))
    }
    private fun px(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x)
    private fun pts(x: Double) = String.format(Locale.ENGLISH, "%,.1f", abs(x))
    private fun pct(x: Double) = (if (x < 0) "−" else "+") + String.format(Locale.ENGLISH, "%.1f%%", abs(x) * 100)
    private fun hm(t: LocalDateTime) = String.format(Locale.ENGLISH, "%02d:%02d", t.hour, t.minute)
    private fun hms(t: LocalDateTime) = String.format(Locale.ENGLISH, "%02d:%02d:%02d", t.hour, t.minute, t.second)
    private fun lotsWord(n: Double): String {
        val s = if (n == Math.floor(n)) n.toLong().toString() else String.format(Locale.ENGLISH, "%.2f", n).trimEnd('0').trimEnd('.')
        return "$s lot" + if (n == 1.0) "" else "s"
    }

    /** The panel's words and figures for [i] at [now]. */
    fun view(i: Input, now: LocalDateTime): View {
        val ltp = i.ltp
        val lots = i.lots?.takeIf { it > 0 } ?: 1.0
        val known = i.lots != null && i.lots > 0
        // The index is the arm's read of today only (an earlier day's last minute is no price for now).
        val index = i.index?.takeIf { i.indexAt == null || i.indexAt.toLocalDate() == now.toLocalDate() }
        val pts = LiquidityRules.indexStopPoints(i.underlying)
        val title = "OPEN POSITION · ${if (i.live) "LIVE" else "PAPER"}"
        val contract = "${i.underlying} ${i.right} · ${i.symbol} · ${if (known) lotsWord(lots) + ", " else ""}${i.qty} qty"
        val entered = "Bought at ${px(i.entry)} at ${hms(i.entryTime)}"
        val gross = ltp?.let { (it - i.entry) * i.qty }
        val perLot = gross?.let { it / lots }
        val net = if (ltp == null || gross == null) null else gross - chargesEstimate(i.entry, ltp, i.qty, i.buyCharges)
        val nowLine = if (ltp == null) "Premium now: not read yet" else
            "Premium now ${px(ltp)} (${pct((ltp - i.entry) / i.entry)} on the price paid)"
        val pnl = if (gross == null || perLot == null) "P&L: waiting for a price" else
            "P&L ${rupees(gross)} before charges · ${rupees(perLot)} a lot"
        val after = net?.let { "About ${rupees(it)} after charges if sold now (estimate)" }

        val exits = ArrayList<Exit>()
        // Premium stop: the position's own trigger.
        val stop = i.stopTrigger
        exits += if (stop == null) Exit("Premium stop", "none recorded on this position", "—")
        else {
            val rule = "${px(stop)}, ${String.format(Locale.ENGLISH, "%.0f%%", LiquidityRules.PREMIUM_STOP * 100)} under ${px(i.entry)}" + if (i.stopResting) " (a resting order)" else " (no resting order: the app sells at it)"
            val away = when {
                ltp == null -> "no price yet"
                ltp <= stop -> "at or under it now"
                else -> "${px(ltp - stop)} away (${pct((stop - ltp) / ltp)})"
            }
            Exit("Premium stop", rule, away, ltp != null && (ltp - stop) <= 0.05 * ltp)
        }
        // Index stop: the broken level ± the index's stop points.
        val level = i.level
        exits += if (level == null) Exit("Index stop", "no broken level recorded", "—")
        else {
            val at = indexStopLevel(i.right, level, i.underlying)
            val rule = "${i.underlying} ${if (side(i.right) > 0) "under" else "over"} ${px(at)} (level ${px(level)} ${if (side(i.right) > 0) "−" else "+"} ${pts(pts)})"
            val d = index?.let { pointsToIndexStop(i.right, level, i.underlying, it) }
            val away = when {
                index == null || d == null -> "index not read yet"
                d < 0 -> "index ${px(index)} · ${pts(d)} pts beyond it"
                else -> "index ${px(index)} · ${pts(d)} pts away"
            }
            Exit("Index stop", rule, away, d != null && d <= pts / 3)
        }
        // Target: the next liquidity level.
        val target = i.target
        exits += if (target == null) Exit("Target", "no target: no liquidity level was ahead", "—")
        else {
            val d = index?.let { pointsToTarget(i.right, target, it) }
            val away = when {
                index == null || d == null -> "index not read yet"
                d <= 0 -> "index ${px(index)} · reached"
                else -> "index ${px(index)} · ${pts(d)} pts away"
            }
            Exit("Target", "next liquidity ${px(target)}", away, d != null && d <= pts / 3)
        }
        // Time stop: checked once at entry + 20 min.
        val due = timeStopAt(i.entryTime)
        val need = timeStopPrice(i.entry)
        val met = ltp?.let { gainMet(i.entry, it) }
        val gainPct = String.format(Locale.ENGLISH, "%.0f%%", LiquidityRules.TIME_STOP_GAIN * 100)
        val timeRule = "at ${hms(due)} out unless up $gainPct (${px(need)} or more)"
        exits += when {
            i.timed -> Exit("Time stop", timeRule, "passed: it was up $gainPct, held")
            now.isBefore(due) -> Exit("Time stop", timeRule, "in ${countdown(now, due)} · " + when (met) {
                null -> "no price yet"
                true -> "met now (${px(ltp!!)})"
                false -> "not met now (${px(ltp!!)})"
            }, near = met == false && Duration.between(now, due).seconds <= 300)
            else -> Exit("Time stop", timeRule, "due: checked on the next fresh price · " + when (met) {
                null -> "no price yet"
                true -> "met now"
                false -> "not met now"
            }, near = met != true)
        }
        // 15:10.
        val end = now.toLocalDate().atTime(OrbRules.SQUARE_OFF)
        exits += Exit("Square-off", "sold at ${hm(end)}",
            if (now.isBefore(end)) "in ${countdown(now, end)}" else "now: selling at the session's end", !now.isBefore(end.minusMinutes(10)))

        val hi = maxOf(i.peak ?: i.entry, ltp ?: i.entry)
        val lo = minOf(i.low ?: i.entry, ltp ?: i.entry)
        val range = "Best ${px(hi)} (${pct((hi - i.entry) / i.entry)}) · worst ${px(lo)} (${pct((lo - i.entry) / i.entry)}) so far"

        val progress = if (level == null || target == null || index == null) null else
            fraction(i.right, level, target, i.underlying, index)?.let { f ->
                val s = indexStopLevel(i.right, level, i.underlying)
                Progress(f, s, target, index, "Index stop ${px(s)} · index ${px(index)} · target ${px(target)}")
            }
        val also = "Also out when a completed bar closes back through the level (failed break) or new liquidity forms on its side. " +
            "Read only: nothing here sells."
        val asOf = "As of ${hms(now)}" + (i.indexAt?.takeIf { index != null }?.let { " · index minute ${hm(it)}" } ?: "")
        return View(title, contract, entered, nowLine, pnl, after, exits, range, progress, also, asOf, gross, perLot, net)
    }
}
