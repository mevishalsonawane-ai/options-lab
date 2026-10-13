package com.optionslab.ira

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * "Explain this candle" (TODO I13, 13 Oct 2026): Boss long-presses a candle on the chart and Jarvis says what the app holds
 * for those minutes - the candle itself against the others on screen, India VIX, the volume, the future and its basis and
 * the option chain's OI and IV (from the live recordings, when that minute was recorded), the futures order flow (the tick
 * zone's minute rows) and the headlines read near then. Each missing piece is said plainly ("not recorded"), never guessed.
 * Facts beside facts: never a cause proven, never a forecast or advice. Pure: the app gathers the inputs off the main thread.
 */
object CandleExplain {
    /** One option of the recorded chain. */
    data class Leg(val strike: Double, val right: String, val ltp: Double?, val oi: Long?, val oiChange: Long?, val iv: Double?)

    /** What the live recording holds for one minute of the index (LiveRecord's "M" row). */
    data class Recorded(val spot: Double?, val future: Double?, val basis: Double?, val vix: Double?, val chain: List<Leg>)

    /** The futures order flow of one minute (TickZone's "A" row). */
    data class Flow(val volume: Long?, val delta: Long?, val vwap: Double?)

    data class Inputs(
        val symbol: String, val start: LocalDateTime, val minutes: Int,
        val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double?,
        /** The other candles of the same interval on screen that day: their ranges and volumes, for "typical". */
        val dayRanges: List<Double> = emptyList(), val dayVolumes: List<Double> = emptyList(),
        val vixStart: Double? = null, val vixEnd: Double? = null,
        val recStart: Recorded? = null, val recEnd: Recorded? = null,
        val flow: List<Flow> = emptyList(),
        val news: List<Pair<LocalDateTime, String>> = emptyList(),
        /** True when the symbol is an index the recorder follows (NIFTY, BANKNIFTY) or one of their options. */
        val recordable: Boolean = true,
    )

    const val NEWS_BEFORE_MIN = 15L
    const val END = "These are the facts the app holds for those minutes - not a cause proven, not a forecast."

    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private fun n(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x)
    private fun n0(x: Double) = String.format(Locale.ENGLISH, "%,.0f", x)
    private fun sp(x: Double) = String.format(Locale.ENGLISH, "%+.2f%%", x)
    private fun sn(x: Double) = String.format(Locale.ENGLISH, "%+,.0f", x)
    private fun median(xs: List<Double>): Double? { val s = xs.filter { it > 0 }.sorted(); if (s.isEmpty()) return null; return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun lakh(x: Double) = if (abs(x) >= 1e5) String.format(Locale.ENGLISH, "%.1f lakh", x / 1e5) else n0(x)

    fun span(i: Inputs): String = if (i.minutes <= 1) i.start.format(HM) else "${i.start.format(HM)}-${i.start.plusMinutes(i.minutes.toLong()).format(HM)}"

    fun explain(i: Inputs): List<String> {
        val out = ArrayList<String>()
        val move = i.close - i.open
        val range = i.high - i.low
        val label = if (i.minutes >= 1440) "daily" else if (i.minutes >= 60) "${i.minutes / 60}-hour" else "${i.minutes}-minute"
        out += "${i.symbol} $label candle of ${i.start.toLocalDate()} ${span(i)}: opened ${n(i.open)}, high ${n(i.high)}, low ${n(i.low)}, " +
            "closed ${n(i.close)} - ${if (move > 0) "up" else if (move < 0) "down" else "flat"} ${n(abs(move))} (${sp(move / i.open * 100)}), range ${n(range)}."
        median(i.dayRanges)?.let { m -> if (range > 0 && i.dayRanges.size >= 5)
            out += "Its range was ${String.format(Locale.ENGLISH, "%.1f", range / m)}x the median of that day's other ${i.dayRanges.size} candles on the chart." }
        // Body and wicks: where it closed inside its range.
        if (range > 0) {
            val pos = (i.close - i.low) / range
            out += when {
                pos >= 0.8 -> "It closed near its high (top fifth of the range)."
                pos <= 0.2 -> "It closed near its low (bottom fifth of the range)."
                else -> "It closed inside its range (${(pos * 100).toInt()}% of the way up)."
            }
        }
        out += when {
            i.volume != null && i.volume > 0 -> "Volume ${lakh(i.volume)}" + (median(i.dayVolumes)?.let { m -> ", ${String.format(Locale.ENGLISH, "%.1f", i.volume / m)}x that day's median candle" } ?: "") + "."
            else -> "Volume: none on this chart - an index has no volume of its own (its future's flow is below when recorded)."
        }
        out += when {
            i.vixStart != null && i.vixEnd != null -> "India VIX ${n(i.vixEnd)}" + (if (abs(i.vixEnd - i.vixStart) >= 0.005) " (from ${n(i.vixStart)} at the start)" else ", unchanged over the candle") + "."
            i.recStart?.vix != null -> "India VIX ${n(i.recStart.vix)} (from the recording)."
            else -> "India VIX: missing - the phone holds no VIX candle for those minutes."
        }
        if (!i.recordable) out += "Futures, basis, OI and IV: the app records them for NIFTY and BANKNIFTY (and their options) only."
        else {
            val r = i.recEnd ?: i.recStart
            if (r == null) out += "Futures, basis, option OI and IV: missing - those minutes were not recorded (the recording runs while the app is open with a Zerodha session)."
            else {
                out += if (r.future != null && r.basis != null) "Future ${n(r.future)}, basis ${sn(r.basis)} points over the index" +
                    (i.recStart?.basis?.takeIf { i.recEnd != null && abs(it - r.basis) >= 1 }?.let { " (from ${sn(it)})" } ?: "") + "."
                else "Future and basis: missing in the recording for that minute."
                out += chainLine(i.recStart, r) ?: "Option OI and IV: missing in the recording for that minute."
            }
        }
        if (i.recordable) {
            val f = i.flow.filter { it.volume != null || it.delta != null }
            out += if (f.isEmpty()) "Futures order flow: not recorded for those minutes."
            else {
                val v = f.mapNotNull { it.volume }.sum(); val d = f.mapNotNull { it.delta }.sum()
                "Futures order flow over ${f.size} recorded minute${if (f.size == 1) "" else "s"}: volume ${lakh(v.toDouble())}, delta ${sn(d.toDouble())} " +
                    "(${if (d > 0) "more bought at the offer" else if (d < 0) "more sold at the bid" else "balanced"})."
            }
        }
        val from = i.start.minusMinutes(NEWS_BEFORE_MIN)
        val to = i.start.plusMinutes(i.minutes.toLong())
        val near = i.news.filter { !it.first.isBefore(from) && !it.first.isAfter(to) }.sortedBy { it.first }
        out += if (near.isEmpty()) "News: no headline on the phone from ${from.format(HM)} to ${to.format(HM)} (the phone keeps the last few days of its feeds)."
        else "News near then: " + near.take(3).joinToString("; ") { "${it.first.format(HM)} \"${it.second.take(110)}\"" } +
            (if (near.size > 3) " and ${near.size - 3} more" else "") + " - read at the same time, not shown to be the cause."
        out += END
        return out
    }

    /** The ATM call and put of the recorded chain: OI, its change over the candle, IV. Null when the chain has none. */
    private fun chainLine(a: Recorded?, b: Recorded): String? {
        val spot = b.spot ?: return null
        val strikes = b.chain.map { it.strike }.distinct()
        if (strikes.isEmpty()) return null
        val atm = strikes.minBy { abs(it - spot) }
        val parts = listOf("CE", "PE").mapNotNull { r ->
            val leg = b.chain.firstOrNull { it.strike == atm && it.right == r } ?: return@mapNotNull null
            val was = a?.chain?.firstOrNull { it.strike == atm && it.right == r }?.oi
            val oi = leg.oi
            "${n0(atm)} $r" + (oi?.let { " OI ${lakh(it.toDouble())}" + (if (was != null && a !== b && oi != was) " (${sn((oi - was).toDouble())} over the candle)" else "") } ?: " OI missing") +
                (leg.iv?.let { ", IV ${String.format(Locale.ENGLISH, "%.1f%%", it * 100)}" } ?: ", IV missing")
        }
        if (parts.isEmpty()) return null
        val calls = b.chain.filter { it.right == "CE" && it.oi != null }.maxByOrNull { it.oi!! }
        val puts = b.chain.filter { it.right == "PE" && it.oi != null }.maxByOrNull { it.oi!! }
        return "Recorded chain at the money: " + parts.joinToString("; ") + "." +
            (if (calls != null && puts != null) " Largest OI then: ${n0(calls.strike)} CE and ${n0(puts.strike)} PE." else "")
    }
}
