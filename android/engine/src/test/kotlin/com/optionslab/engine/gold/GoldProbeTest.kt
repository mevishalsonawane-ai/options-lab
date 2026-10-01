package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import kotlin.test.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * A diagnosis, not a check: with GOLD_PROBE_CSV set (epoch,open,high,low,close of Yahoo's GC=F 1-hour candles, as the
 * app reads them), the app's rule is replayed hour by hour over the last [DAYS] days - every decision, every buy and
 * its exit - into build/gold-probe.txt. Without the variable it does nothing.
 */
class GoldProbeTest {
    @Test fun replayTheLastDays() {
        val f = System.getenv("GOLD_PROBE_CSV") ?: return
        val raw = File(f).readLines().drop(1).filter { it.isNotBlank() }.map { l ->
            val c = l.split(',')
            Bar(LocalDateTime.ofEpochSecond(c[0].toLong(), 0, ZoneOffset.UTC), c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toDouble())
        }
        val hours = GoldLiquidity.hourly(raw)
        val out = StringBuilder()
        out.append("raw ${raw.size} bars ${raw.first().start} .. ${raw.last().start}; hourly ${hours.size}\n")
        val offHour = raw.count { it.start.minute != 0 }
        out.append("bars not on the hour: $offHour\n")
        val from = hours.last().start.minusDays(DAYS)
        var pos: Triple<Int, Double, LiquidityRulesSignal>? = null
        var signals = 0; var blocked = 0
        for (i in hours.indices) {
            val bar = hours[i]
            if (bar.start.isBefore(from)) continue
            val done = hours.subList(0, i + 1)
            val entryAt = bar.start.plusHours(1)
            val now = entryAt.plusSeconds(30)
            if (pos != null) {
                val (k, entry, s) = pos
                val since = hours.subList(k + 1, i + 1)
                val why = GoldLiquidity.exitReason(s.level, s.target, hours[k].start, hours[k + 1].start, done, since, now)
                if (why != null) {
                    out.append("  ${bar.start} SELL ($why) at ~%.2f, %+.2f a lot\n".format(bar.close, GoldLiquidity.pnl(entry, GoldLiquidity.sellPrice(bar.close), 1.0)))
                    pos = null
                }
                continue
            }
            val sig = GoldLiquidity.signal(done)
            if (sig != null) {
                signals++
                if (!GoldLiquidity.mayEnterAt(entryAt)) { blocked++; out.append("  ${bar.start} signal (level %.2f) but no entry at $entryAt\n".format(sig.level)); continue }
                val nextOpen = hours.getOrNull(i + 1)?.open ?: bar.close
                val px = GoldLiquidity.buyPrice(nextOpen)
                out.append("  ${bar.start} BUY at %.2f (level %.2f, target ${sig.target?.let { "%.2f".format(it) }})\n".format(px, sig.level))
                pos = Triple(i, px, LiquidityRulesSignal(sig.level, sig.target))
            }
        }
        out.append("signals $signals (of which $blocked outside entry hours) in the last $DAYS days\n")
        File("build/gold-probe.txt").writeText(out.toString())
        println(out)
    }

    private data class LiquidityRulesSignal(val level: Double, val target: Double?)

    companion object { const val DAYS = 10L }
}
