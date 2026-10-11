package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Learning from Boss's own trades ([BossTrades]): the rules with n and BH q, the soft warning, his habits, the file. */
class BossTradesTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val d0 = LocalDate.of(2026, 9, 1)
    private fun at(day: Int, h: Int, m: Int = 0) = d0.plusDays(day.toLong()).atTime(h, m).atZone(ist).toInstant().toEpochMilli()

    /** 11 gut trades after 14:00 (2 won, 9 lost) and 14 level trades in the morning (12 won). */
    private fun trades(): List<BossTrades.Trade> {
        val out = ArrayList<BossTrades.Trade>()
        for (i in 0 until 11) out += BossTrades.Trade("paper:g$i", "NIFTY", false, at(i, 14, 20), BossTrades.Why.GUT, exitWhy = BossTrades.ExitWhy.FEAR,
            closedMs = at(i, 14, 50), net = if (i < 2) 300.0 else -500.0, regime = "busy")
        for (i in 0 until 14) out += BossTrades.Trade("paper:l$i", "BANKNIFTY", false, at(i, 10, 30), BossTrades.Why.LEVEL, exitWhy = BossTrades.ExitWhy.TARGET,
            closedMs = at(i, 11), net = if (i < 12) 700.0 else -400.0, regime = "quiet", rr = 2.0)
        out += BossTrades.Trade("paper:open", "NIFTY", false, at(20, 9, 30), BossTrades.Why.NEWS)
        return out
    }

    @Test fun groupsAreTestedAgainstACoinTossAndCorrectedForManyTests() {
        val rules = BossTrades.rules(trades())
        val gutLate = rules.single { it.key == "why:GUT:LATE" }
        assertEquals(11, gutLate.n); assertEquals(2, gutLate.wins); assertEquals(-3_900.0, gutLate.net, 1e-9)
        assertTrue(gutLate.q >= gutLate.p, "q is never below p")
        val level = rules.single { it.key == "why:LEVEL" }
        assertTrue(level.holds, "12 of 14 holds: q ${level.q}")
        assertTrue(rules.none { it.n < BossTrades.MIN_N }, "a group needs at least ${BossTrades.MIN_N}")
        assertTrue(rules.none { it.key == "why:NEWS" }, "one open news trade is no rule")
        val said = BossTrades.sayRules(rules, 26)
        assertTrue(said.contains("What works: your level trades made ₹7,600 over 14 trades, won 86%"), said)
    }

    @Test fun aSoftWarningComesOnlyFromHisOwnLosingRecord() {
        val rules = BossTrades.rules(trades())
        val w = assertNotNull(BossTrades.warn(rules, 14 * 60 + 30))
        assertTrue(w.startsWith("Heads up, Boss: your gut trades after 14:00 lost 9 of 11") || w.startsWith("Heads up, Boss: your gut trades lost 9 of 11") ||
            w.startsWith("Heads up, Boss: your trades after 14:00 lost 9 of 11"), w)
        assertTrue(w.contains("nothing is blocked"))
        assertNull(BossTrades.warn(rules, 10 * 60 + 30, BossTrades.Why.LEVEL), "his winning morning level trades get no warning")
        assertNull(BossTrades.warn(BossTrades.rules(trades().take(5)), 14 * 60 + 30), "too few trades: nothing to warn on")
    }

    @Test fun habitsTheRiskToRewardAndTheFile() {
        val ts = trades()
        val h = BossTrades.sayHabits(ts)
        assertTrue(h.contains("you trade most on level (14), gut (11), news (1)"), h)
        assertTrue(h.contains("1:2.0"), h)
        assertEquals(2.0, BossTrades.rr(100.0, 90.0, 120.0, buy = true)!!, 1e-9)
        assertNull(BossTrades.rr(100.0, 110.0, 120.0, buy = true))
        assertEquals(ts, BossTrades.decode(BossTrades.encode(ts)))
        assertTrue(BossTrades.sayHabits(emptyList()).contains("none of your own trades"))
        assertNotNull(BossTrades.todayInsight(BossTrades.rules(ts), ts.take(1)))
    }
}
