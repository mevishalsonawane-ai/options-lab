package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * A check of the app's rule on history, not a unit test: with GOLD_REPLAY_CSV set (Dukascopy XAUUSD 1-minute bid,
 * epoch ms,open,high,low,close,...), the gold engine is run the way IraGoldAlgo's pass runs it - every 5 minutes,
 * 30 s past the mark, on the last three months of 1-hour candles (what the app's feed gives it), deciding each candle
 * once, buying at the last minute's price, checking the exits on the minutes since the buy - over the whole file.
 * Trades go to build/gold-replay.csv (to compare with research/gold_1h_plus.py) and a summary to build/gold-replay.txt.
 * Without the variable it does nothing.
 */
class GoldReplayTest {
    @Test fun replayTheAppsPassOverHistory() {
        val path = System.getenv("GOLD_REPLAY_CSV") ?: return
        val minutes = ArrayList<Bar>(1_200_000)
        GZIPInputStream(File(path).inputStream()).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { l ->
                val c = l.split(',')
                val t = LocalDateTime.ofEpochSecond(c[0].toLong() / 1000, 0, ZoneOffset.UTC)
                val h = GoldLiquidity.SPREAD / 2                      // bid -> mid, as the app's feed is a mid price
                minutes += Bar(t, c[1].toDouble() + h, c[2].toDouble() + h, c[3].toDouble() + h, c[4].toDouble() + h)
            }
        }
        minutes.sortBy { it.start }
        val hours = GoldLiquidity.hourly(minutes)
        val window = 1_500                                         // ~3 months of 1-hour candles, as Yahoo's 3mo range
        val out = StringBuilder("signal_bar,entry_time,entry,exit_time,exit,why,usd_per_lot\n")
        var pos: Triple<LocalDateTime, Double, Pair<LocalDateTime, Pair<Double, Double?>>>? = null   // entry time, price, (signal bar, (level, target))
        var posMinute = 0
        var decided: LocalDateTime? = null
        var hi = 0                                                 // hours[0 until hi] have closed
        var mi = 0                                                 // minutes[0 until mi] have closed
        var t = minutes.first().start.withMinute(0).withSecond(30)
        val end = minutes.last().start
        var trades = 0; var total = 0.0; var wins = 0
        while (!t.isAfter(end)) {
            while (mi < minutes.size && !minutes[mi].start.plusMinutes(1).isAfter(t)) mi++
            while (hi < hours.size && !hours[hi].start.plusHours(1).isAfter(t)) hi++
            val last = if (mi > 0) minutes[mi - 1] else null
            if (last != null && hi > 2 * 20 + 2) {
                val done = hours.subList(maxOf(0, hi - window), hi)
                val p = pos
                if (p != null) {
                    val (entryTime, entry, sig) = p
                    val since = minutes.subList(posMinute, mi)
                    val why = GoldLiquidity.exitReason(sig.second.first, sig.second.second, sig.first, entryTime, done, since, t)
                    if (why != null) {
                        val px = GoldLiquidity.exitPrice(why, sig.second.second, last.close)
                        val usd = GoldLiquidity.pnl(entry, px, 1.0)
                        out.append("${sig.first},$entryTime,%.2f,$t,%.2f,$why,%.2f\n".format(entry, px, usd))
                        trades++; total += usd; if (usd > 0) wins++
                        pos = null
                    }
                } else if (GoldLiquidity.inSession(t)) {
                    val bar = done.last()
                    val entryAt = bar.start.plusHours(1)
                    if (decided != bar.start) {
                        decided = bar.start
                        if (GoldLiquidity.mayEnterAt(entryAt) && !t.isAfter(entryAt.plusMinutes(20))) {
                            GoldLiquidity.signal(done)?.let { s ->
                                pos = Triple(t, GoldLiquidity.buyPrice(last.close), bar.start to (s.level to s.target))
                                posMinute = mi
                            }
                        }
                    }
                }
            }
            t = t.plusMinutes(5)
        }
        File("build/gold-replay.csv").writeText(out.toString())
        val summary = "app pass replayed ${minutes.first().start} .. $end: $trades trades, ${if (trades > 0) 100 * wins / trades else 0}% won, " +
            "%+,.0f USD per lot".format(total)
        File("build/gold-replay.txt").writeText(summary + "\n")
        println(summary)
    }
}
