package com.optionslab.engine.orb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 9 Oct parking (Boss's OK): Liquidity 15+5 FINNIFTY and ORB Sweep off once, FINNIFTY kept off under the one switch. */
class ParkedArmsTest {
    @Test fun theChangeSwitchesOffWhatIsOnAndParksFinnifty() {
        val armed = mapOf("liquidity15" to true, "liquidity5" to true, "liquidity30_fin" to true, "liquidity5_fin" to false,
            "liquidity15_mid" to true, "orb_sweep" to true, "range_fade" to true)
        val p = ParkedArms.plan(armed, done = false)!!
        assertEquals(listOf("liquidity30_fin", "orb_sweep"), p.off)
        assertEquals(setOf("FINNIFTY"), p.parked)
        // Nothing on: FINNIFTY is still parked (the switch coming on later leaves it off); nothing to switch off.
        assertEquals(ParkedArms.Plan(emptyList(), setOf("FINNIFTY")), ParkedArms.plan(emptyMap(), done = false))
        // Once only.
        assertNull(ParkedArms.plan(armed, done = true))
        assertEquals(listOf("liquidity30_fin", "liquidity5_fin"), ParkedArms.books("FINNIFTY").map { it.source })
        assertEquals(listOf("orb_sweep"), ParkedArms.ARMS)
        assertEquals("parked_2026_10_09", ParkedArms.MIGRATION)
    }

    @Test fun theOneSwitchArmsEveryBookButAParkedIndexs() {
        assertEquals(LiquidityRules.BOOKS, ParkedArms.armable(emptySet()))
        assertEquals(listOf("liquidity15", "liquidity5", "liquidity15_mid", "liquidity5_mid"), ParkedArms.armable(setOf("FINNIFTY")).map { it.source })
        assertEquals(emptySet(), ParkedArms.unpark(setOf("FINNIFTY"), "FINNIFTY"))
        assertEquals(setOf("FINNIFTY"), ParkedArms.unpark(setOf("FINNIFTY"), "MIDCPNIFTY"))
    }

    @Test fun theNoticeSaysWhatBossAgreedTo() {
        assertEquals("Liquidity 15+5 FINNIFTY (−₹8,035 over 8 paper trades) and ORB Sweep (−₹4,498 over 6) were switched off on your OK; " +
            "switch them back on in Home → Strategies any time.", ParkedArms.NOTICE)
        assertEquals("−₹8,035 over 8 paper trades", ParkedArms.record("FINNIFTY"))
        assertEquals("paper record negative", ParkedArms.record("MIDCPNIFTY"))
        assertTrue("switch it back on any time" in ParkedArms.PARKED)
    }
}
