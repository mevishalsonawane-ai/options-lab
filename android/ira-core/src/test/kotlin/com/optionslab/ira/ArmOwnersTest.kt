package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals

class ArmOwnersTest {
    @Test fun liquidityBooksAreTheOneArm() {
        for (o in listOf("Liquidity 15m", "Liquidity 5m", "Liquidity 5m FINNIFTY", "Liquidity 30m FINNIFTY", "Liquidity 15m FINNIFTY",
            "liquidity 5m  finnifty", "Liquidity 15+5")) {
            assertEquals("Liquidity 15+5", ArmOwners.arm(o), o)
        }
    }

    @Test fun otherOwnersStayAsTheyAre() {
        for (o in listOf("ORB", "ORB Fresh", "ORB Sweep", "Range Fade", "Manual", "Jarvis solo", "Liquidity grab", "My Liquidity 5m", "Protection")) {
            assertEquals(o, ArmOwners.arm(o), o)
        }
    }

    /** Boss's paper book on 5 Oct: entries under the arm, exits under each book - one arm with every trade. */
    @Test fun switchOffSeesTheBooksTrades() {
        val owners = listOf("Liquidity 15+5", "Liquidity 15m", "Liquidity 5m", "Liquidity 5m FINNIFTY", "Liquidity 30m FINNIFTY", "ORB")
        val nets = listOf(-400.0, -300.0, 200.0, -250.0, -150.0, 100.0)
        val byArm = owners.zip(nets).groupBy({ ArmOwners.arm(it.first) }, { it.second })
        assertEquals(5, byArm["Liquidity 15+5"]!!.size)
        val a = SwitchOff.answer(SwitchOff.Q("liquidity"), listOf(SwitchOff.arm("Liquidity 15+5", true, byArm["Liquidity 15+5"]!!)))
        assert(!a.text.contains("no closed paper trades yet")) { a.text }
        assert(a.text.contains("on paper 5 trades")) { a.text }
    }
}
