package com.optionslab.engine.orb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Liquidity 15+5's size (Boss's 06 Oct choice: 2-3 lots): the quantity per book, the live cap, a restore, and per-lot shadows. */
class LiquidityLotsTest {
    @Test fun theQuantityIsTheLotsTimesEachContractsOwnLot() {
        // BANKNIFTY's lot (30) and FINNIFTY's (65), for each of the four books' contracts.
        assertEquals(60, LiquidityLots.qty(2, 30))
        assertEquals(90, LiquidityLots.qty(3, 30))
        assertEquals(130, LiquidityLots.qty(2, 65))
        assertEquals(65, LiquidityLots.qty(1, 65))
        assertFailsWith<IllegalArgumentException> { LiquidityLots.qty(4, 30) }
        assertFailsWith<IllegalArgumentException> { LiquidityLots.qty(0, 30) }
        assertFailsWith<IllegalArgumentException> { LiquidityLots.qty(2, 0) }
    }

    @Test fun onlyOneTwoOrThreeAndOneByDefault() {
        assertEquals(listOf(1, 2, 3), LiquidityLots.CHOICES)
        assertEquals(1, LiquidityLots.DEFAULT)
        assertTrue(LiquidityLots.valid(3)); assertFalse(LiquidityLots.valid(4)); assertFalse(LiquidityLots.valid(0))
        assertEquals(3, LiquidityLots.of(3)); assertNull(LiquidityLots.of(7)); assertNull(LiquidityLots.of(null))
        assertTrue(LiquidityLots.MIGRATED.contains("1 lot a trade (research X1"))
    }

    @Test fun twoLotsBossNeverChoseGoBackToOneOnce() {
        assertEquals(1, LiquidityLots.oneLot(2, chosen = false))
        assertNull(LiquidityLots.oneLot(2, chosen = true), "chosen by hand: kept")
        assertNull(LiquidityLots.oneLot(3, chosen = false), "3 was always Boss's own choice")
        assertNull(LiquidityLots.oneLot(1, chosen = false)); assertNull(LiquidityLots.oneLot(null, chosen = false))
        assertEquals("Liquidity size set to 1 lot (research X1); change it in Strategies if you want", LiquidityLots.ONE_LOT_NOTICE)
    }

    @Test fun moreLotsIsARaise() {
        assertTrue(LiquidityLots.raises(2, 3)); assertFalse(LiquidityLots.raises(3, 2)); assertFalse(LiquidityLots.raises(2, 2))
    }

    @Test fun liveIsRefusedByNameWhenTheBotSettingsAllowFewer() {
        assertEquals("refused: Bot settings allow 2 lots; Liquidity is set to 3. Nothing was bought - raise the max lots in Bot settings " +
            "or lower Liquidity's lots.", LiquidityLots.liveRefusal(3, 2))
        assertTrue(LiquidityLots.liveRefusal(2, 1)!!.startsWith("refused: Bot settings allow 1 lot; Liquidity is set to 2."))
        assertNull(LiquidityLots.liveRefusal(2, 2)); assertNull(LiquidityLots.liveRefusal(3, 3))
        assertNull(LiquidityLots.liveRefusal(3, 0), "no cap set")
    }

    @Test fun aRestoreNeverRaisesTheSize() {
        assertEquals(1 to 3, LiquidityLots.restored(1, 3), "kept at this phone's 1; the backup's 3 waits for Boss")
        assertEquals(1 to null, LiquidityLots.restored(3, 1), "lowering restores")
        assertEquals(2 to null, LiquidityLots.restored(2, 2))
        assertEquals(1 to 3, LiquidityLots.restored(null, 3), "no book here: the default of 1")
        assertEquals(1 to null, LiquidityLots.restored(1, null), "a backup from before the setting: the default, 1")
        assertEquals(1 to null, LiquidityLots.restored(9, null), "an unknown size here reads as the default")
        assertEquals(1 to 2, LiquidityLots.restored(null, 2), "never above the default without Boss")
    }

    @Test fun theRowsLine() {
        assertEquals("Size: 2 lots a trade (BANKNIFTY 60, FINNIFTY 130 qty) · an open position keeps its own",
            LiquidityLots.line(2, linkedMapOf("BANKNIFTY" to 30, "FINNIFTY" to 65)))
        assertEquals("Size: 1 lot a trade · an open position keeps its own", LiquidityLots.line(1, emptyMap()))
        assertEquals(150.0, LiquidityLots.perLot(300.0, 2.0)); assertEquals(300.0, LiquidityLots.perLot(300.0, 0.0))
    }

    @Test fun theShadowCountsPerLotSoTheSizeNeverMovesIt() {
        val d = LiquidityShadow.SINCE
        // The same trade at 1 lot and at 3 lots: each candidate's comparison is the same per lot.
        val one = LiquidityShadow.Trade(d, 1_000.0, false, "liquidity5", volSkip = false, exit1430 = 400.0, itm2 = -200.0, strong = true)
        val three = one.copy(net = 3_000.0, exit1430 = 1_200.0, itm2 = -600.0, lots = 3.0)
        val a = LiquidityShadow.summarize(listOf(one, one))
        val b = LiquidityShadow.summarize(listOf(one, three))
        assertEquals(a, b)
        assertEquals(LiquidityShadow.Cut(2, 2_000.0), b.all)
        assertEquals(LiquidityShadow.Cut(2, 800.0), b.at1430)
        assertEquals(LiquidityShadow.Cut(2, -400.0), b.itm2)
        assertEquals(LiquidityShadow.Cut(2, 2_000.0), b.withStrong)
        // Unpriced candidates stay unpriced.
        val bare = LiquidityShadow.Trade(d, 900.0, null, "liquidity5", lots = 2.0).perLot()
        assertEquals(450.0, bare.net); assertNull(bare.exit1430); assertNull(bare.itm2); assertEquals(1.0, bare.lots)
        assertTrue(LiquidityShadow.line(b).startsWith("Since 06 Oct, per lot: 2 paper trades, +₹2,000 net (+₹1,000 a trade)"), LiquidityShadow.line(b))
    }
}
