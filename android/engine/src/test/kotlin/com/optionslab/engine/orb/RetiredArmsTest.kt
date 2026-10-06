package com.optionslab.engine.orb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetiredArmsTest {
    @Test fun theFourLosersAreRetiredAndLiquidityAndHeroAreNot() {
        assertEquals(listOf("orb", "orb_fresh", "orb_sweep", "range_fade"), RetiredArms.ALL.map { it.arm.source })
        RetiredArms.ALL.forEach { assertTrue(RetiredArms.isRetired(it.arm.source)) }
        for (s in listOf("liquidity", "liquidity15", "liquidity5", "liquidity30_fin", "liquidity5_fin", "hero", "nope")) {
            assertFalse(RetiredArms.isRetired(s), s)
            assertNull(RetiredArms.of(s))
        }
        assertEquals(OrbRules.ORB_FRESH, RetiredArms.of("orb_fresh")!!.arm)
    }

    @Test fun eachLineSaysWhatItLostAndWhy() {
        val orb = RetiredArms.of("orb")!!
        assertEquals("lost ₹9.77 lakh over 2021–2026 on real data; no fix held up out of sample", RetiredArms.line(orb))
        assertEquals("lost ₹2.53 lakh over 2021–2026 on real data; no fix held up out of sample", RetiredArms.line(RetiredArms.of("orb_fresh")!!))
        assertEquals("₹53,000", RetiredArms.rupees(53_000.0))
        assertEquals("₹1.00 lakh", RetiredArms.rupees(100_000.0))
        assertEquals("ORB is retired: it lost ₹9.77 lakh over 2021–2026 on real data; no fix held up out of sample. " +
            "Only Liquidity 15+5 stays on, on paper (Boss's choice 06 Oct).", RetiredArms.refusal(orb))
        val a = RetiredArms.answer(RetiredArms.of("range_fade")!!)
        assertTrue(a.startsWith("Range Fade is retired, Boss: it lost ₹2.74 lakh over 2021–2026"), a)
        assertTrue(a.contains("cannot be armed"), a)
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
        assertEquals(listOf("orb" to false, "orb_fresh" to false, "liquidity15" to true, "liquidity30_fin" to true, "liquidity5_fin" to true),
            ch.map { it.source to it.armed })
        assertEquals("ORB: switched off: lost on 6 years of real data (Boss's choice 06 Oct); its open position is still managed to its exit", ch[0].log)
        assertEquals("ORB Fresh: switched off: lost on 6 years of real data (Boss's choice 06 Oct)", ch[1].log)
        assertEquals("Liquidity 15m: switched back on, paper only (Boss's choice 06 Oct)", ch[2].log)
    }

    @Test fun theMigrationRunsOnceAndARestoreStaysOff() {
        assertEquals(emptyList(), RetiredArms.migrate(listOf(RetiredArms.Switch("orb", true)), books, done = true, restoring = false))
        val r = RetiredArms.migrate(listOf(RetiredArms.Switch("orb", true), RetiredArms.Switch("range_fade", true)), books, done = false, restoring = true)
        assertEquals(listOf("orb" to false, "range_fade" to false), r.map { it.source to it.armed })
        // A fresh book (nothing saved): only the liquidity books, all four.
        assertEquals(books.map { it.source }, RetiredArms.migrate(emptyList(), books, done = false, restoring = false).map { it.source })
    }
}
