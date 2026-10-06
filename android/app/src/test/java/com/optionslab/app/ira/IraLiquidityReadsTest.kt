package com.optionslab.app.ira

import com.optionslab.ira.LiquidityMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Why no liquidity trade": each index's levels from the arm's own read, else read for that index alone. */
class IraLiquidityReadsTest {
    private fun read(u: String, minutes: Int) = LiquidityMap.Read(u, minutes, LiquidityMap.State.NO_DATA)

    @Test fun anIndexMissingFromTheArmsReadIsReadOnItsOwn() = runBlocking {
        val unds = listOf("BANKNIFTY", "FINNIFTY")
        val asked = ArrayList<List<String>>()
        // BankNifty kept, FinNifty not: FinNifty alone is read.
        val rs = IraLiquidity.keptElseFetched(unds, { listOf(read("BANKNIFTY", 15), read("BANKNIFTY", 5)) }) { u ->
            asked.add(u); u.flatMap { listOf(read(it, 30), read(it, 5)) }
        }
        assertEquals(listOf(listOf("FINNIFTY")), asked)
        assertEquals(listOf("BANKNIFTY" to 15, "BANKNIFTY" to 5, "FINNIFTY" to 30, "FINNIFTY" to 5), rs.map { it.underlying to it.minutes })
        // Both kept: nothing read.
        asked.clear()
        IraLiquidity.keptElseFetched(unds, { listOf(read("BANKNIFTY", 15), read("FINNIFTY", 30)) }) { u -> asked.add(u); emptyList() }
        assertEquals(emptyList<List<String>>(), asked)
        // Neither kept: both read, once.
        val both = IraLiquidity.keptElseFetched(unds, { emptyList() }) { u -> asked.add(u); u.map { read(it, 5) } }
        assertEquals(listOf(unds), asked)
        assertEquals(2, both.size)
    }
}
