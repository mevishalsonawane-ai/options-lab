package com.optionslab.ira

import kotlin.test.Test

/**
 * What one background pass costs the ira-core helpers it calls over the full candle histories (speed round 9): the
 * refresh's folds for the book and [Brain.read] for each index, and the sharp-move watch ([SharpMove.latest]) - once
 * with the market shut (the same candles every pass) and once with a new minute every pass; and the asked-for readers
 * that read the whole history ([Structure.read], [DayClock.answer], [NewsMoves.usual]). A measure, not a gate. Run with
 * `-Dira.bench=1` for steadier numbers.
 */
class BackgroundBenchTest {
    private val nifty = Fixtures.indexDays(30, seed = 7)
    private val bank = Fixtures.indexDays(30, start = 52_000.0, step = 12.0, seed = 9)
    private val vix = Fixtures.indexDays(30, start = 13.0, step = 0.05, seed = 11)

    /** A sharp last half hour on Nifty so the watch reads the usual move (the costly path). */
    private val sharp = nifty.dropLast(30) + nifty.takeLast(30).mapIndexed { i, c -> c.copy(c = c.c + i * 12, h = c.h + i * 12 + 2) }

    /** [b] with [k] more minutes, as the next passes see it. */
    private fun later(b: List<Candle>, k: Int): List<Candle> {
        val out = ArrayList(b)
        repeat(k) { val l = out.last(); out += l.copy(t = l.t.plusMinutes(1), o = l.c, c = l.c + 1, h = l.c + 2, l = l.c - 1) }
        return out
    }

    /** One refresh pass as IraHub runs it over [hs], then the sharp-move watch. */
    private fun pass(hs: Map<Market, List<Candle>>) {
        for ((m, b) in hs) {
            val h = History(m, b)
            for (minutes in listOf(15, 60)) Candles.closed(Candles.fold(h.bars, minutes, m), minutes, h.bars.last().t.plusMinutes(1))
            Brain.read(h)
        }
        for (m in listOf(Market.NIFTY, Market.BANKNIFTY)) SharpMove.latest(m, hs.getValue(m))
    }

    @Test fun passCost() {
        val long = System.getProperty("ira.bench") != null
        val runs = if (long) 30 else 3
        val shut = mapOf(Market.NIFTY to sharp, Market.BANKNIFTY to bank, Market.VIX to vix)
        val minutes = List(runs * 2 + 1) { k -> shut.mapValues { later(it.value, k) } }
        val today = sharp.last().t.toLocalDate()
        val now = sharp.last().t.plusMinutes(1)
        val steps: List<Pair<String, (Int) -> Any?>> = listOf(
            "pass, market shut" to { _ -> pass(shut) },
            "pass, a new minute each" to { k -> pass(minutes[k]) },
            "SharpMove.latest (sharp)" to { _ -> SharpMove.latest(Market.NIFTY, sharp) },
            "Structure.read x2" to { _ -> Structure.read(Market.NIFTY, sharp, today); Structure.read(Market.BANKNIFTY, bank, today) },
            "DayClock.answer" to { _ -> DayClock.answer(DayClock.Ask.ALL, Market.NIFTY, sharp, today, now) },
            "NewsMoves.usual" to { _ -> NewsMoves.usual(Market.NIFTY, sharp) },
        )
        println("BackgroundBench (%d bars a market, 3 markets, %d runs):".format(sharp.size, runs))
        for ((name, f) in steps) {
            repeat(if (long) 10 else 2) { runCatching { f(0) } }
            val s = System.nanoTime(); for (k in 1..runs) runCatching { f(k) }
            println("  %-26s %.3f ms".format(name, (System.nanoTime() - s) / 1e6 / runs))
        }
    }
}
