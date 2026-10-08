package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Liquidity 15+5 judged per index (research X1 change 3): BANKNIFTY apart from FINNIFTY and MIDCPNIFTY. */
class LiquiditySplitTest {
    @Test fun aLiquidityTradeIsJudgedByItsIndex() {
        assertEquals("Liquidity 15+5 BANKNIFTY", LiquiditySplit.owner("Liquidity 15+5", "BANKNIFTY28OCT2656000CE"))
        assertEquals("Liquidity 15+5 FINNIFTY", LiquiditySplit.owner("Liquidity 5m FINNIFTY", "FINNIFTY28OCT2626000PE"))
        assertEquals("Liquidity 15+5 MIDCPNIFTY", LiquiditySplit.owner("Liquidity 15m MIDCPNIFTY", "MIDCPNIFTY28OCT2613000PE"))
        assertEquals("Liquidity 15+5 BANKNIFTY", LiquiditySplit.owner("Liquidity 15m", "banknifty28oct2656000ce"))
        // No index read: the arm's own name; another owner as it is.
        assertEquals("Liquidity 15+5", LiquiditySplit.owner("Liquidity 15+5", "NIFTY28OCT2625000CE"))
        assertEquals("ORB", LiquiditySplit.owner("ORB", "BANKNIFTY28OCT2656000CE"))
    }

    @Test fun theIndicesAndNames() {
        assertEquals("BANKNIFTY", LiquiditySplit.indexOf("BANKNIFTY-LIQ-54000CE"))
        assertNull(LiquiditySplit.indexOf("SENSEX30OCT2682000PE"))
        assertEquals(listOf("Liquidity 15+5 BANKNIFTY", "Liquidity 15+5 FINNIFTY", "Liquidity 15+5 MIDCPNIFTY"), LiquiditySplit.NAMES)
    }
}
