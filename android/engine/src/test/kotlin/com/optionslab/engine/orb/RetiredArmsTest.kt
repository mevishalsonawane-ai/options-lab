package com.optionslab.engine.orb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetiredArmsTest {
    @Test fun theFourHaveARecordAndLiquidityAndHeroHaveNone() {
        assertEquals(listOf("orb", "orb_fresh", "orb_sweep", "range_fade"), RetiredArms.ALL.map { it.arm.source })
        RetiredArms.ALL.forEach { assertEquals(it, RetiredArms.of(it.arm.source)) }
        for (s in listOf("liquidity", "liquidity15", "liquidity5", "liquidity30_fin", "liquidity5_fin", "hero", "nope"))
            assertNull(RetiredArms.of(s), s)
        assertEquals(OrbRules.ORB_FRESH, RetiredArms.of("orb_fresh")!!.arm)
    }

    @Test fun eachLineSaysWhatItLostAndWhy() {
        val orb = RetiredArms.of("orb")!!
        assertEquals("lost ₹9.77 lakh over 2021–2026 on real data; no fix held up out of sample", RetiredArms.line(orb))
        assertEquals("lost ₹2.53 lakh over 2021–2026 on real data; no fix held up out of sample", RetiredArms.line(RetiredArms.of("orb_fresh")!!))
        assertEquals("₹53,000", RetiredArms.rupees(53_000.0))
        assertEquals("₹1.00 lakh", RetiredArms.rupees(100_000.0))
        // Un-retired 07 Oct: the record is information, never a refusal.
        assertEquals("ORB is back on paper, Boss: you un-retired it on 07 Oct 2026. For the record, it lost ₹9.77 lakh over " +
            "2021–2026 on real data; no fix held up out of sample. Its own rules are unchanged, and Zerodha still takes your PIN or fingerprint.",
            RetiredArms.history(orb))
        val a = RetiredArms.history(RetiredArms.of("range_fade")!!)
        assertTrue(a.startsWith("Range Fade is back on paper, Boss: you un-retired it on 07 Oct 2026. For the record, it lost ₹2.74 lakh"), a)
        for (w in listOf("is retired", "cannot be armed", "can't be", "stays retired")) assertFalse(a.contains(w), w)
    }

    @Test fun theArmsNamedInAQuestion() {
        fun n(t: String) = RetiredArms.named(t).map { it.arm.source }
        assertEquals(listOf("orb"), n("How is the ORB arm doing?"))
        assertEquals(listOf("orb"), n("orbs today?"))
        assertEquals(listOf("orb_fresh"), n("what about ORB Fresh"))
        assertEquals(listOf("orb", "orb_fresh"), n("ORB and orb-fresh"))
        assertEquals(listOf("orb_sweep", "range_fade"), n("orb sweep vs range fade"))
        assertEquals(emptyList(), n("how is liquidity doing? a sweep, a fade"))
        assertEquals(emptyList(), n("forbid the orbit"))
    }

    private val books = LiquidityRules.BOOKS

    @Test fun theMigrationSwitchesTheRetiredOffAndLiquidityBackOnOnPaper() {
        val sw = listOf(RetiredArms.Switch("orb", true, open = true), RetiredArms.Switch("orb_fresh", true), RetiredArms.Switch("orb_sweep", false),
            RetiredArms.Switch("liquidity5", true), RetiredArms.Switch("hero", true))
        val ch = RetiredArms.migrate(sw, books, done = false, restoring = false)
        assertEquals(listOf("orb" to false, "orb_fresh" to false, "liquidity15" to true, "liquidity30_fin" to true, "liquidity5_fin" to true,
            "liquidity15_mid" to true, "liquidity5_mid" to true),
            ch.map { it.source to it.armed })
        assertEquals("ORB: switched off: lost on 6 years of real data (Boss's choice 06 Oct); its open position is still managed to its exit", ch[0].log)
        assertEquals("ORB Fresh: switched off: lost on 6 years of real data (Boss's choice 06 Oct)", ch[1].log)
        assertEquals("Liquidity 15m: switched back on, paper only (Boss's choice 06 Oct)", ch[2].log)
    }

    @Test fun theMigrationRunsOnceAndARestoreStaysOff() {
        assertEquals(emptyList(), RetiredArms.migrate(listOf(RetiredArms.Switch("orb", true)), books, done = true, restoring = false))
        val r = RetiredArms.migrate(listOf(RetiredArms.Switch("orb", true), RetiredArms.Switch("range_fade", true)), books, done = false, restoring = true)
        assertEquals(listOf("orb" to false, "range_fade" to false), r.map { it.source to it.armed })
        // A fresh book (nothing saved): only the liquidity books, all six.
        assertEquals(books.map { it.source }, RetiredArms.migrate(emptyList(), books, done = false, restoring = false).map { it.source })
    }

    @Test fun theUnretirementSwitchesTheFourBackOnOnPaperOnce() {
        val sw = listOf(RetiredArms.Switch("orb", false, open = true), RetiredArms.Switch("orb_fresh", false), RetiredArms.Switch("orb_sweep", true),
            RetiredArms.Switch("liquidity5", true), RetiredArms.Switch("hero", false))
        val ch = RetiredArms.unretire(sw, done = false, restoring = false)
        // ORB Sweep, already armed, is left as it is; Liquidity and Hero are never touched.
        assertEquals(listOf("orb" to true, "orb_fresh" to true, "range_fade" to true), ch.map { it.source to it.armed })
        assertEquals("ORB: switched back on, paper only (Boss un-retired it 07 Oct 2026)", ch[0].log)
        assertEquals("Range Fade: switched back on, paper only (Boss un-retired it 07 Oct 2026)", ch[2].log)
        // A book with nothing saved for them: all four.
        assertEquals(RetiredArms.ALL.map { it.arm.source }, RetiredArms.unretire(emptyList(), done = false, restoring = false).map { it.source })
        // Once only, and a restore not yet disarmed switches nothing on.
        assertEquals(emptyList(), RetiredArms.unretire(emptyList(), done = true, restoring = false))
        assertEquals(emptyList(), RetiredArms.unretire(emptyList(), done = false, restoring = true))
        assertEquals("unretire_2026_10_07", RetiredArms.UNRETIRE)
        assertTrue(RetiredArms.UNRETIRE != RetiredArms.MIGRATION)
        assertEquals(java.time.LocalDate.of(2026, 10, 7), RetiredArms.UNRETIRED_ON)
    }
}
