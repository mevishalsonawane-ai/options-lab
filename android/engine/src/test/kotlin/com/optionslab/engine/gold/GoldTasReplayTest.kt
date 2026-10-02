package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A check of the TAS arm's rule on history, not a unit test: with GOLD_REPLAY_CSV set (Dukascopy XAUUSD 1-minute bid),
 * [GoldTas] is run on 1-hour candles as research/gold_tas.py runs it (decide on each completed candle, buy at the next
 * one's open, the stop (and the targets, if any) checked on each candle, out at the next open after the tracker turns down; one
 * buy per up-turn). Trades go to build/gold-tas-replay.csv. Without the variable it does nothing.
 */
class GoldTasReplayTest {
    @Test fun replayTheTasArmOverHistory() {
        val path = System.getenv("GOLD_REPLAY_CSV") ?: return
        val minutes = ArrayList<Bar>(1_200_000)
        val hs = GoldLiquidity.SPREAD / 2
        GZIPInputStream(File(path).inputStream()).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { l ->
                val c = l.split(',')
                val t = LocalDateTime.ofEpochSecond(c[0].toLong() / 1000, 0, ZoneOffset.UTC)
                minutes += Bar(t, c[1].toDouble() + hs, c[2].toDouble() + hs, c[3].toDouble() + hs, c[4].toDouble() + hs)
            }
        }
        minutes.sortBy { it.start }
        val b = GoldLiquidity.hourly(minutes)
        val (d, trk) = GoldTas.tracker(b)
        val pct = GoldTas.score(b, d)
        // read() on a window agrees with the whole history's arrays once the averages have run in
        for (end in listOf(3_000, 9_000, 15_000)) {
            val r = GoldTas.read(b.subList(0, end + 1))!!
            assertEquals(d[end] == 1, r.up); assertEquals(trk[end], r.line, 1e-9); assertEquals(pct[end], r.score!!, 1e-6)
        }
        val n = b.size
        val out = StringBuilder("signal,entry,exit_time,pnl\n")
        var count = 0; var total = 0.0
        var i = 1; var flipAt: Int? = null
        while (i < n - 1) {
            if (d[i] == 1 && d[i - 1] != 1) flipAt = i
            if (d[i] != 1) flipAt = null
            val f = flipAt
            if (f == null || i - f > GoldTas.LATE_BARS || !pct[i].isFinite() || pct[i] < GoldTas.MIN_SCORE) { i++; continue }
            val e = GoldLiquidity.buyPrice(b[i + 1].open)
            var stop = trk[i]
            val r = e - stop
            if (r <= 0) { i++; continue }
            val tg = GoldTas.targets(e, stop)
            var left = 1.0; var pnl = 0.0; var be = false; var j = i + 1; var done = false
            val hit = BooleanArray(tg.size)
            while (j < n) {
                val lo = GoldLiquidity.sellPrice(b[j].low); val hi = GoldLiquidity.sellPrice(b[j].high)
                if (lo <= stop) { pnl += left * (minOf(stop, GoldLiquidity.sellPrice(b[j].open)) - e); left = 0.0; done = true; break }
                var moved = false
                for (k in tg.indices) if (!hit[k] && hi >= tg[k]) {
                    val q = if (k == tg.size - 1) left else GoldTas.TARGETS[k].second
                    pnl += q * (tg[k] - e); left -= q; hit[k] = true; moved = true
                }
                if (left <= 1e-9) { done = true; break }
                if (moved && !be) { stop = e; be = true }
                if (d[j] != 1 && j + 1 < n) { pnl += left * (GoldLiquidity.sellPrice(b[j + 1].open) - e); left = 0.0; j++; done = true; break }
                j++
            }
            if (!done) pnl += left * (GoldLiquidity.sellPrice(b[n - 1].close) - e)
            val usd = 100 * pnl - GoldLiquidity.COMMISSION_PER_LOT
            out.append("${b[i].start},%.2f,${b[minOf(j, n - 1)].start},%.2f\n".format(e, usd))
            count++; total += usd
            flipAt = null
            i = if (d[minOf(j, n - 1)] == 1) j + 1 else j
            while (i < n && d[i] == 1 && d[i - 1] == 1) i++
        }
        File("build/gold-tas-replay.csv").writeText(out.toString())
        println("tas replay: $count trades, %+,.0f USD per lot".format(total))
    }
}
