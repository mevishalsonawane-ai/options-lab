package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlin.test.Test

/**
 * A check of the Dip arm's rule on history, not a unit test: with GOLD_REPLAY_CSV set (Dukascopy XAUUSD 1-minute bid),
 * [GoldDip] is run 30-minute candle by candle as research/gold_mtf_lock.py runs it (decide on each completed candle,
 * buy at the next one's open, the lock checked on each candle's low then its high raising the top, out 8 hours after
 * the entry or at the candle that closes before the break). Trades go to build/gold-dip-replay.csv. Without the
 * variable it does nothing.
 */
class GoldDipReplayTest {
    @Test fun replayTheDipArmOverHistory() {
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
        val thirty = GoldDip.thirty(minutes)
        val out = StringBuilder("entry_time,entry,exit_time,exit,why\n")
        var n = 0; var total = 0.0
        var i = GoldDip.ATR_LENGTH + 2
        var hi = 0
        while (i < thirty.size - 1) {
            val closeAt = thirty[i].start.plusMinutes(GoldDip.MINUTES)
            while (hi < hours.size && !hours[hi].start.plusHours(1).isAfter(closeAt)) hi++
            val s = GoldDip.signal(thirty.subList(maxOf(0, i - 200), i + 1), hours.subList(maxOf(0, hi - 5), hi))
            // the research's "no gap after": the next candle must follow within the hour
            if (s == null || thirty[i + 1].start.isAfter(closeAt.plusMinutes(30))) { i++; continue }
            val k = i + 1
            val entryTime = thirty[k].start
            val e = GoldLiquidity.buyPrice(thirty[k].open)
            var peak = Double.NEGATIVE_INFINITY; var stop = Double.NEGATIVE_INFINITY
            var j = k; var px = 0.0; var why = ""
            while (true) {
                val b = thirty[j]
                if (GoldLiquidity.sellPrice(b.low) <= stop) { px = minOf(stop, GoldLiquidity.sellPrice(b.open)); why = "dip_lock"; break }
                peak = maxOf(peak, GoldLiquidity.sellPrice(b.high))
                GoldDip.lockStop(e, peak, s.atr)?.let { stop = maxOf(stop, it) }
                val end = b.start.plusMinutes(GoldDip.MINUTES)
                val gap = j + 1 >= thirty.size || thirty[j + 1].start.isAfter(end.plusMinutes(30))
                if (gap) { px = GoldLiquidity.sellPrice(b.close); why = "dip_break"; break }
                if (!end.isBefore(entryTime.plusHours(GoldDip.MAX_HOURS))) { px = GoldLiquidity.sellPrice(b.close); why = "dip_time"; break }
                j++
            }
            out.append("$entryTime,%.2f,${thirty[j].start},%.2f,$why\n".format(e, px))
            n++; total += (px - e) * 100 - GoldLiquidity.COMMISSION_PER_LOT
            i = j + 1
        }
        File("build/gold-dip-replay.csv").writeText(out.toString())
        println("dip replay: $n trades, %+,.0f USD per lot".format(total))
    }
}
