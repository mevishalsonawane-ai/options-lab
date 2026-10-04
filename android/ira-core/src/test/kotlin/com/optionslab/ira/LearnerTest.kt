package com.optionslab.ira

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearnerTest {
    private val d = LocalDate.of(2026, 10, 1)

    @Test fun itLearnsAPatternThatIsThereAndDoesNotTradeBeforeProvingIt() {
        // A market where the last 5 minutes' direction carries on: the learner should find it.
        val l = Learner(Learner.Cfg(minScored = 50))
        var price = 24_000.0
        val rnd = java.util.Random(7)
        val days = (0 until 30).map { day ->
            var drift = 0.0
            (0 until 375).map { m ->
                if (m % 20 == 0) drift = if (rnd.nextBoolean()) 2.0 else -2.0
                val o = price; price += drift + rnd.nextGaussian() * 3
                Candle(d.plusDays(day.toLong()).atTime(9, 15).plusMinutes(m.toLong()), o, maxOf(o, price) + 1, minOf(o, price) - 1, price)
            }
        }
        assertNull(l.decide(0.9), "no trade before it has proven itself")
        var prev: Double? = null
        for (day in days) {
            val pending = ArrayDeque<Triple<Int, DoubleArray, Double>>()
            for (m in 1 until 375) {
                l.tick(day[m - 1], day[m])
                while (pending.isNotEmpty() && pending.first().first + 15 <= m) { val (m0, x, g) = pending.removeFirst(); l.learn(x, day[m0 + 15].c > day[m0].c, g) }
                val x = l.features(day, m, prev) ?: continue
                if (m + 15 < 375) pending.addLast(Triple(m, x, l.p(x)))
            }
            prev = day.last().c
        }
        assertTrue(l.scored >= 50, "${l.scored}")
        assertTrue(l.hitRate > 0.55, "it found the pattern: ${l.hitRate}")
        assertTrue(l.ready)
        assertEquals(true, l.decide(0.8)); assertEquals(false, l.decide(0.2)); assertNull(l.decide(0.55))
        // Kept and brought back: the same model.
        val back = Learner(Learner.Cfg(minScored = 50))
        assertTrue(back.load(l.save()))
        assertEquals(l.save(), back.save())
        assertTrue(back.ready && !Learner().load("junk"))
        assertTrue(l.say("Nifty").startsWith("Nifty: "), l.say("Nifty"))
        // In words: in a market where the last minutes' direction carries on, it names momentum.
        val e = l.explain("Nifty")!!
        assertTrue(e.startsWith("Nifty has learned: ") && e.contains("momentum"), e)
        assertNull(Learner().explain("Nifty"), "too early to say")
    }

    /** Over real history (SOLO_DATA): prints how the learner would have done. */
    @Test fun learnerOverHistory() {
        val dir = System.getenv("SOLO_DATA")?.let(::File) ?: return
        val edge = System.getenv("LEARN_EDGE")?.toDouble() ?: 0.10
        val hz = System.getenv("LEARN_H")?.toInt() ?: 15
        for ((name, f, sl) in listOf(Triple("NIFTY Apr 2024-Apr 2025", "nifty_a.csv.gz", 50 to 75), Triple("NIFTY Apr 2025-Apr 2026", "nifty_b.csv.gz", 50 to 75),
                Triple("BANKNIFTY Feb 2025-Feb 2026", "banknifty_b.csv.gz", 100 to 30))) {
            val hit = System.getenv("LEARN_HIT")?.toDouble() ?: 0.55
            val r = Learner(Learner.Cfg(edge = edge, horizon = hz, minHit = hit)).backtest(SoloHistoryTest().days(File(dir, f)), sl.first, sl.second)
            println("LEARN $name edge=$edge h=$hz: ${r.trades.size} trades over ${r.days} days, ${r.wins} won, net Rs %,.0f; its hit rate %.1f%% over its last %d guesses"
                .format(r.net, r.hitRate * 100, r.scored))
        }
    }
}
