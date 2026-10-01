package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * A check of the trend arm's rule on history, not a unit test: with GOLD_REPLAY_CSV set (Dukascopy XAUUSD 1-minute bid),
 * [GoldTrend] is run hour by hour as research/gold_trend_lock.py runs it (decide at each hour's open on the 4-hour
 * candles closed by then, the lock checked on each hour's low) - once on all the history before each decision, once on
 * the last [WINDOW] 4-hour candles only (what the app's three months of Yahoo candles give it). Trades go to
 * build/gold-trend-replay*.csv to compare with the research's. Without the variable it does nothing.
 */
class GoldTrendReplayTest {
    private val WINDOW = 380

    @Test fun replayTheTrendArmOverHistory() {
        val path = System.getenv("GOLD_REPLAY_CSV") ?: return
        val minutes = ArrayList<Bar>(1_200_000)
        val h = GoldLiquidity.SPREAD / 2
        GZIPInputStream(File(path).inputStream()).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { l ->
                val c = l.split(',')
                val t = LocalDateTime.ofEpochSecond(c[0].toLong() / 1000, 0, ZoneOffset.UTC)
                minutes += Bar(t, c[1].toDouble() + h, c[2].toDouble() + h, c[3].toDouble() + h, c[4].toDouble() + h)
            }
        }
        minutes.sortBy { it.start }
        val hours = GoldLiquidity.hourly(minutes)
        val four = GoldTrend.chart(hours)
        for ((label, window) in listOf("" to Int.MAX_VALUE, "-window" to WINDOW)) {
            val out = StringBuilder("entry_time,entry,exit_time,exit,why\n")
            var k = 0                                         // four[0 until k] closed
            var st: GoldTrend.State? = null
            var entry: Double? = null; var entryTime: LocalDateTime? = null; var a = 0.0; var peak = 0.0
            var waitFlip = false
            var n = 0; var total = 0.0
            fun sell(t: LocalDateTime, px: Double, why: String) {
                out.append("$entryTime,%.2f,$t,%.2f,$why\n".format(entry, px))
                n++; total += (px - entry!!) * 100 - GoldLiquidity.COMMISSION_PER_LOT
                entry = null
            }
            for (bar in hours) {
                val before = k
                while (k < four.size && !four[k].start.plusHours(GoldTrend.CHART_HOURS).isAfter(bar.start)) k++
                if (k != before) st = GoldTrend.state(four.subList(maxOf(0, k - window), k))
                val s = st ?: continue
                if (entry == null) {
                    if (!s.up) { waitFlip = false; continue }
                    if (waitFlip) continue
                    entry = GoldLiquidity.buyPrice(bar.open); entryTime = bar.start; a = s.atr; peak = GoldLiquidity.sellPrice(bar.high)
                    // as the research: the first hour's own stop is not checked (no lock yet), its high counts
                    continue
                }
                if (!s.up) { sell(bar.start, GoldLiquidity.sellPrice(bar.open), "trend_down"); waitFlip = false; continue }
                val stop = GoldTrend.lockStop(entry!!, peak, a)
                if (stop != null && GoldLiquidity.sellPrice(bar.low) <= stop) {
                    sell(bar.start, minOf(stop, GoldLiquidity.sellPrice(bar.open)), "giveback"); waitFlip = true; continue
                }
                peak = maxOf(peak, GoldLiquidity.sellPrice(bar.high))
            }
            File("build/gold-trend-replay$label.csv").writeText(out.toString())
            println("trend replay$label: $n trades, %+,.0f USD per lot before swap".format(total))
        }
    }
}
