package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The committee's settings ([ManagerConfig]): the defaults, the research's key=value and JSON blocks, the bounds. */
class ManagerConfigTest {
    @Test fun theDefaultsAreTheOriginalRulesAtFullWeightAndTheNewOnesAtAQuarter() {
        val c = ManagerConfig("pine")
        assertEquals(1.0, c.quorum)
        for (id in listOf("data", "news", "traps", "flow", "absorption", "delta", "bots")) assertEquals(1.0, c.weight(id), id)
        for (id in listOf("vwap", "volume", "profile", "oi", "vix", "momentum", "price", "time", "gamma")) assertEquals(0.25, c.weight(id), id)
        assertEquals(setOf("traps", "flow", "vwap", "profile", "oi", "vix", "price", "time"), ManagerTeam.IDS.filter { c.spec(it).gate }.toSet())
        assertEquals(2.0, c.th("vwap", "stretchSd", 9.0))
        assertEquals(9.0, c.th("vwap", "nothing", 9.0))
        assertEquals(ManagerTeam.IDS.toSet(), ManagerTeam.DEFAULT_SPECS.keys)
        assertEquals(setOf("news", "traps", "data"), ManagerTeam.VETOES)
    }

    @Test fun aKeyValueBlockDropsIn() {
        val block = """
            # The research's recommendation (R12)
            [pine]
            quorum = 1.5
            supermajority = 0.8
            exit_floor = 60
            vwap.weight = 1.4
            vwap.stretchSd = 2.5
            oi.enabled = false
            gamma.gate = true
            junk.weight = 3
            [solo]
            momentum.weight = 0.5
            liquidity.quorum = 2
            liquidity.volume.weight = 0.75
        """.trimIndent()
        val m = ManagerConfig.parse(block)
        assertEquals(setOf("pine", "solo", "liquidity"), m.keys)
        val p = m.getValue("pine")
        assertEquals(1.5, p.quorum); assertEquals(0.8, p.superMajority); assertEquals(60, p.exitFloor)
        assertEquals(1.4, p.weight("vwap")); assertEquals(2.5, p.th("vwap", "stretchSd", 0.0))
        assertFalse(p.counts("oi")); assertTrue(p.spec("gamma").gate)
        assertEquals(1.0, p.weight("flow"), "the rest keep their defaults")
        assertEquals(0.5, m.getValue("solo").weight("momentum"))
        assertEquals(2.0, m.getValue("liquidity").quorum)
        assertEquals(0.75, m.getValue("liquidity").weight("volume"))
        // Round trip.
        assertEquals(p, ManagerConfig.parse(ManagerConfig.encode(p)).getValue("pine"))
    }

    @Test fun aJsonBlockDropsIn() {
        val json = """{"pine": {"quorum": 1.2, "specialists": {"vwap": {"weight": 1.4, "enabled": true, "gate": true, "thresholds": {"stretchSd": 2.5}},
            "volume": {"weight": 0.5, "climaxX": 4}}}, "solo": {"exitFloor": 70}}"""
        val m = ManagerConfig.parse(json)
        assertEquals(1.2, m.getValue("pine").quorum)
        assertEquals(1.4, m.getValue("pine").weight("vwap"))
        assertEquals(2.5, m.getValue("pine").th("vwap", "stretchSd", 0.0))
        assertEquals(4.0, m.getValue("pine").th("volume", "climaxX", 0.0))
        assertEquals(70, m.getValue("solo").exitFloor)
        val one = ManagerConfig.parse("""{"strategy": "night", "quorum": 2, "specialists": {"oi": {"weight": 0}}}""")
        assertEquals(2.0, one.getValue("night").quorum); assertFalse(one.getValue("night").counts("oi"))
        val list = ManagerConfig.parse("""{"strategies": [{"strategy": "hero", "postAt": 90}]}""")
        assertEquals(90, list.getValue("hero").postAt)
        assertTrue(ManagerConfig.parse("{ not json").isEmpty())
        assertTrue(ManagerConfig.parse("").isEmpty())
    }

    @Test fun nothingInABlockLeavesItsRange() {
        val m = ManagerConfig.parse("[pine]\nquorum=0\nsupermajority=0.1\nexit_floor=500\nextend_hold_sec=1\nvwap.weight=99\nflow.weight=-3\nprice.giveBack=NaN")
        val p = m.getValue("pine")
        assertEquals(ManagerConfig.MIN_QUORUM, p.quorum)
        assertEquals(0.5, p.superMajority)
        assertEquals(100, p.exitFloor)
        assertEquals(30, p.extendHoldSec, "never a shorter extension hold than the original 30 s")
        assertEquals(ManagerConfig.MAX_WEIGHT, p.weight("vwap"))
        assertEquals(0.0, p.weight("flow"))
        assertEquals(0.6, p.th("price", "giveBack", 0.0), "a bad number keeps the default")
    }
}
