package com.optionslab.app.ira

import com.optionslab.engine.Right
import com.optionslab.ira.Ask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** An order asked of Ira, matched to one listed contract: never a guessed strike or a past expiry. */
class IraOrdersTest {
    private val today = LocalDate.of(2026, 10, 2)
    private val listed = listOf(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 14)).flatMap { e ->
        (0..8).map { IraOrders.Listed(e, 24_000.0 + it * 50, 75) }
    }
    private fun pick(text: String, spot: Double? = null, max: Int = 2) = IraOrders.pick(Ask.parse(text).order!!, listed, spot, today, max)

    @Test fun theNearestExpiryAndTheNamedStrike() {
        val t = pick("buy 2 lots nifty 24100 ce").getOrThrow()
        assertEquals(IraOrders.Ticket("NIFTY", LocalDate.of(2026, 10, 7), 24_100.0, Right.CE, 2, true, 75), t)
        assertEquals("BUY 2 lots NIFTY 7 OCT 24100 CE", t.title)
        assertEquals(Right.PE, pick("sell 1 lot nifty 24000 put").getOrThrow().right)
    }

    @Test fun atmIsTheListedStrikeNearestThePrice() {
        assertEquals(24_150.0, pick("buy 1 lot nifty atm pe", spot = 24_162.0).getOrThrow().strike, 0.0)
        assertTrue(pick("buy 1 lot nifty atm pe").exceptionOrNull()!!.message!!.contains("ATM"))
    }

    @Test fun refusedRatherThanGuessed() {
        val off = pick("buy 1 lot nifty 24120 ce").exceptionOrNull()!!.message!!
        assertTrue(off, off.startsWith("24120 is not a listed NIFTY strike") && off.contains("24100 and 24150"))
        assertTrue(pick("buy 5 lots nifty 24100 ce").exceptionOrNull()!!.message!!.contains("over your limit of 2"))
        assertEquals(5, pick("buy 5 lots nifty 24100 ce", max = 0).getOrThrow().lots)
        assertTrue(pick("buy 1 lot gold").isFailure)
        assertTrue(pick("buy 1 lot sensex 80000 ce").isFailure)
        assertTrue(pick("buy nifty").exceptionOrNull()!!.message!!.startsWith("I still need"))
        assertTrue(IraOrders.pick(Ask.parse("buy 1 lot nifty 24100 ce").order!!, listed, null, LocalDate.of(2026, 10, 15), 2).isFailure)
    }
}
