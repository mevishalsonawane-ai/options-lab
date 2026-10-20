package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 20 Oct, enhancement round 1 (idea 3): Liquidity's no-twin rule (the 19 Oct triple stop). */
class LiquidityTwinTest {
    private val day = LocalDate.of(2026, 10, 21)
    private val put = "BANKNIFTY26OCT52000PE"

    @Test fun theOtherBookHoldingTheSameContractIsATwin() {
        val open = listOf(LiquidityTwin.Holding("liquidity15", put, live = false))
        assertEquals(open.single(), LiquidityTwin.twinOf("liquidity5", put, live = false, open = open))
        assertEquals(open.single(), LiquidityTwin.twinOf("liquidity5", put.lowercase(), live = false, open = open), "case never hides a twin")
    }

    @Test fun anotherStrikeTheSameBookOrTheOtherAccountIsNoTwin() {
        val open = listOf(LiquidityTwin.Holding("liquidity15", put, live = false))
        assertNull(LiquidityTwin.twinOf("liquidity5", "BANKNIFTY26OCT52100PE", false, open), "another strike")
        assertNull(LiquidityTwin.twinOf("liquidity15", put, false, open), "its own book (that book buys nothing more while it holds)")
        assertNull(LiquidityTwin.twinOf("liquidity5", put, live = true, open = open), "a live entry beside a paper holding")
        assertNull(LiquidityTwin.twinOf("liquidity5", put, false, emptyList()), "nothing open")
    }

    @Test fun recordsRoundTripAndJunkIsDropped() {
        val r = LiquidityTwin.Rec(123L, day, "liquidity5", put, 210.5, live = false, applied = true, by = "Liquidity 15m | BN")
        val back = LiquidityTwin.decode(r.encode())!!
        assertEquals(r.copy(by = "Liquidity 15m / BN"), back)
        assertEquals(null, LiquidityTwin.decode("junk"))
        assertEquals(null, LiquidityTwin.decode("x|2026-10-21|a|b||false|true|c"))
        assertNull(LiquidityTwin.decode(r.copy(price = null).encode())!!.price)
    }

    @Test fun theLineCountsPaperWouldBeTradesApartFromLiveShadows() {
        val recs = listOf(
            LiquidityTwin.Rec(1L, day, "liquidity5", put, 200.0, live = false, applied = true, by = "15m"),
            LiquidityTwin.Rec(2L, day, "liquidity5", put, 200.0, live = true, applied = false, by = "15m"),
            LiquidityTwin.Rec(3L, day.minusDays(1), "liquidity5", put, 200.0, live = false, applied = true, by = "15m"),
        )
        val line = LiquidityTwin.line(recs, day, on = true)
        assertTrue(line.contains("today 1 would-be trade on paper, 1 only logged; 2 in all"), line)
        assertTrue(LiquidityTwin.line(emptyList(), day, on = false).contains("(off; live in shadow): today 0 would-be trades"))
    }

    @Test fun theWordsSayPaperOrShadow() {
        assertTrue(LiquidityTwin.words("Liquidity 15m BANKNIFTY", put, live = false).contains("would-be trade (not bought)"))
        assertTrue(LiquidityTwin.words("Liquidity 15m BANKNIFTY", put, live = true).contains("shadow: logged only"))
    }

    /** The switch is saved with the paper test's state; a file from before it reads "on" (this update turns it on). */
    @Test fun theSwitchIsKeptAndAnOldFileReadsOn() {
        assertTrue(LiquidityFreeze.State().noTwin)
        assertTrue(LiquidityFreeze.decode("v1\nunfrozen=true\ntrail=true\n").noTwin)
        val off = LiquidityFreeze.State(unfrozen = true, noTwin = false)
        assertEquals(off, LiquidityFreeze.decode(LiquidityFreeze.encode(off)))
    }
}
