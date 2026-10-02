package com.optionslab.ira

import java.util.Locale

/**
 * Jarvis's watch in market hours: what is worth a pop-up - India VIX jumping, an index moving a lot on the day or
 * suddenly within 30 minutes, an arm losing half the day's loss limit. Facts only, never advice; each alert carries a
 * key so it is given once (per day, or per hour for sudden moves). Pure.
 */
object Watch {
    data class Alert(val key: String, val title: String, val text: String)
    data class ArmDay(val name: String, val pnl: Double)

    const val VIX_DAY_PCT = 8.0
    const val VIX_JUMP_PCT = 5.0
    const val DAY_MOVE_PCT = 1.5
    /** A 30-minute move this many times its usual size (and at least [BURST_MIN_PCT]) is sudden. */
    const val BURST_X = 3.0
    const val BURST_MIN_PCT = 0.4
    const val ARM_LOSS_SHARE = 0.5

    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun rs(x: Double) = "Rs " + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    /** [bars]: each market's 1-minute candles, oldest first (today's last). [limit]: the day's loss limit in rupees. */
    fun check(snaps: Map<Market, Snapshot>, bars: Map<Market, List<Candle>>, arms: List<ArmDay>, limit: Double): List<Alert> {
        val out = ArrayList<Alert>()
        snaps[Market.VIX]?.let { v ->
            v.changePct?.takeIf { it >= VIX_DAY_PCT }?.let { out += Alert("vix-day", "India VIX ${pct(it)} today",
                "India VIX is at ${"%.2f".format(Locale.ENGLISH, v.price)}, ${pct(it)} on the day: option prices are rising.") }
        }
        bars[Market.VIX]?.let { b -> move30(b)?.let { (mv, hour) -> if (mv >= VIX_JUMP_PCT) out += Alert("vix-jump|$hour", "India VIX ${pct(mv)} in 30 minutes",
            "India VIX rose ${pct(mv)} in the last 30 minutes, to ${"%.2f".format(Locale.ENGLISH, b.last().c)}.") } }
        for (m in listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)) {
            val s = snaps[m]
            s?.changePct?.takeIf { kotlin.math.abs(it) >= DAY_MOVE_PCT }?.let {
                out += Alert("day|$m|${if (it > 0) "up" else "down"}", "${m.label} ${pct(it)} today",
                    "${m.label} is at ${m.price(s.price)}, ${pct(it)} on the day.")
            }
            val b = bars[m] ?: continue
            val typical = typical30(b) ?: continue
            move30(b)?.let { (mv, hour) ->
                if (kotlin.math.abs(mv) >= BURST_MIN_PCT && kotlin.math.abs(mv) >= BURST_X * typical)
                    out += Alert("burst|$m|$hour", "${m.label} ${pct(mv)} in 30 minutes",
                        "${m.label} moved ${pct(mv)} in the last 30 minutes (about ${"%.0f".format(Locale.ENGLISH, kotlin.math.abs(mv) / typical)} times its usual 30-minute move), to ${m.price(b.last().c)}.")
            }
        }
        if (limit > 0) arms.filter { it.pnl <= -ARM_LOSS_SHARE * limit }.forEach { a ->
            out += Alert("arm|${a.name}", "${a.name} is down ${rs(a.pnl)} today", "${a.name} has lost ${rs(a.pnl)} today, at least half the daily loss limit of ${rs(limit)}.")
        }
        return out
    }

    /** The last 30 minutes' move (%) of today's candles and the hour it ends in, or null. */
    fun move30(b: List<Candle>): Pair<Double, Int>? {
        if (b.size < 31) return null
        val last = b.last(); val ago = b[b.size - 31]
        if (ago.t.toLocalDate() != last.t.toLocalDate()) return null
        return (last.c - ago.c) / ago.c * 100 to last.t.hour
    }

    /** The usual size (median, %) of a 30-minute move in [b], sampled every 30 candles within each day; null with too few. */
    fun typical30(b: List<Candle>): Double? {
        val moves = ArrayList<Double>()
        var i = 30
        while (i < b.size) {
            val a = b[i - 30]; val c = b[i]
            if (a.t.toLocalDate() == c.t.toLocalDate()) moves += kotlin.math.abs((c.c - a.c) / a.c * 100)
            i += 30
        }
        if (moves.size < 20) return null
        moves.sort()
        return moves[moves.size / 2].takeIf { it > 0 }
    }
}
