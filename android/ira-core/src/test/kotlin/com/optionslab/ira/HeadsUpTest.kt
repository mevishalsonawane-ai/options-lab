package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadsUpTest {
    private fun pos(symbol: String, qty: Int, avg: Double, ltp: Double, live: Boolean = true) =
        HeadsUp.Pos((if (live) "L:" else "P:") + symbol, symbol, live, qty, avg, ltp)

    @Test fun lossShareOfTheDailyLimit() {
        val p = pos("NIFTY25O0725000CE", 75, 120.0, 105.0)          // -1,125 on a 2,000 limit: 56%
        val a = HeadsUp.check(listOf(p), 2_000.0, 6_000.0, emptySet())
        assertEquals(listOf(HeadsUp.Kind.LOSS_HALF), a.map { it.kind })
        assertTrue(a[0].text.startsWith("Boss, NIFTY25O0725000CE (Zerodha) is down Rs 1,125: half your Rs 2,000"))
        // Spoken: no symbol, no amount.
        assertFalse(a[0].spoken.contains("NIFTY") || a[0].spoken.contains("Rs"))
        // Told once: not again at the same level.
        assertTrue(HeadsUp.check(listOf(p), 2_000.0, null, a[0].marks).isEmpty())
        // Deeper: three quarters, once.
        val q = p.copy(ltp = 98.0)                                  // -1,650: 82%
        val b = HeadsUp.check(listOf(q), 2_000.0, null, a[0].marks)
        assertEquals(listOf(HeadsUp.Kind.LOSS_MOST), b.map { it.kind })
        assertTrue(HeadsUp.check(listOf(q), 2_000.0, null, a[0].marks + b[0].marks).isEmpty())
    }

    @Test fun straightToThreeQuartersSaysOnlyThat() {
        val a = HeadsUp.check(listOf(pos("BANKNIFTY25O0855000PE", 35, 300.0, 250.0)), 2_000.0, null, emptySet())
        assertEquals(listOf(HeadsUp.Kind.LOSS_MOST), a.map { it.kind })
        assertTrue(HeadsUp.mark(pos("BANKNIFTY25O0855000PE", 35, 300.0, 250.0), HeadsUp.Kind.LOSS_HALF) in a[0].marks)
    }

    @Test fun eachAccountItsOwnLimitAndNoLimitNoWord() {
        val paper = pos("NIFTY25O0725000CE", 75, 120.0, 100.0, live = false)   // -1,500
        assertTrue(HeadsUp.check(listOf(paper), 2_000.0, 6_000.0, emptySet()).isEmpty())   // 25% of the paper limit
        assertTrue(HeadsUp.check(listOf(paper), 2_000.0, 0.0, emptySet()).isEmpty())
        assertEquals(1, HeadsUp.check(listOf(paper), null, 2_500.0, emptySet()).size)
        assertTrue(HeadsUp.check(listOf(pos("X25O0725000CE", 75, 100.0, 140.0)), 1.0, 1.0, emptySet()).isEmpty())   // winning
    }

    @Test fun soldPremiumMostlyDecayed() {
        val s = pos("NIFTY25O0726000CE", -75, 40.0, 6.0)            // 85% kept
        val a = HeadsUp.check(listOf(s), 2_000.0, null, emptySet())
        assertEquals(listOf(HeadsUp.Kind.DECAYED), a.map { it.kind })
        assertTrue(a[0].text.contains("85% of the premium is yours") && a[0].text.contains("Rs 450 is left"))
        assertFalse(a[0].spoken.contains("NIFTY"))
        assertTrue(HeadsUp.check(listOf(s), 2_000.0, null, a[0].marks).isEmpty())
        // Not yet: 70% kept; a bought option or a sold future never.
        assertTrue(HeadsUp.check(listOf(s.copy(ltp = 12.0)), null, null, emptySet()).isEmpty())
        assertTrue(HeadsUp.check(listOf(pos("NIFTY25O0726000CE", 75, 40.0, 7.0)), null, null, emptySet()).none { it.kind == HeadsUp.Kind.DECAYED })
        assertTrue(HeadsUp.check(listOf(pos("NIFTY25OCTFUT", -75, 40.0, 7.0)), null, null, emptySet()).isEmpty())
    }

    @Test fun worstLossFirst() {
        val a = HeadsUp.check(listOf(pos("A25O0725000CE", 75, 100.0, 85.0), pos("B25O0725000CE", 75, 100.0, 75.0)), 2_000.0, null, emptySet())
        assertTrue(a[0].text.contains("B25O0725000CE"))
    }
}
