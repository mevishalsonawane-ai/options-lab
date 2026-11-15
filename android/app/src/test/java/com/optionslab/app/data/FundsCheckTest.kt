package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 15 Nov (Boss: "Yes" to building it in shadow): the funds check on its own - a live entry the free cash cannot pay for even one
 * lot. Off (shadow, the default) it only logs and counts; on, it refuses; an unknown figure says nothing; off again takes the PIN;
 * its diagnostics carry no rupees.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class FundsCheckTest : RobolectricTest() {
    private val owner = "R72 Breadth Donchian"
    private val sym = "NIFTY26N0324000CE"

    private fun diagHas(t: String): Boolean {
        val end = System.currentTimeMillis() + 10_000
        while (Diag.lines().none { it.contains(t) } && System.currentTimeMillis() < end) Thread.sleep(20)
        return Diag.lines().any { it.contains(t) }
    }

    @Test fun offByDefaultItOnlyLogsAndCountsAWouldSkip() {
        assertFalse("shadow by default", FundsCheck.isOn())
        // 1 lot = Rs 6,000 of premium; Rs 5,000 free: short of one lot. In shadow the entry is let through (null).
        assertNull(FundsCheck.check(owner, sym, 1, 6_000.0, 5_000.0, 92.0))
        val t = FundsCheck.recsOf()
        assertEquals(1, t.size); assertFalse("logged only", t.single().applied)
        assertEquals(sym, t.single().symbol); assertEquals(1, t.single().lots)
        assertTrue(FundsCheck.line(), FundsCheck.line().contains("Today: 1 live entry it would have skipped (sent as before)"))
        assertTrue(diagHas("$owner: $sym, 1 lot wanted - ${FundsCheck.WORDS} (shadow: logged only, sent as before)"))
        // Each later signal of the day is counted too (the rejections it would have saved).
        assertNull(FundsCheck.check(owner, sym, 2, 6_000.0, 5_000.0, 92.0))
        assertEquals(2, FundsCheck.recsOf().count { !it.applied })
        // No rupee figure in what the diagnostics show.
        val lines = Diag.lines().filter { it.contains(FundsCheck.WORDS) } + FundsCheck.line()
        assertTrue(lines.toString(), lines.none { it.contains("Rs") || it.contains("₹") || it.contains("5,000") || it.contains("6,000") })
    }

    @Test fun cashThatCoversOneLotOrAnUnknownFigureSaysNothing() {
        assertNull(FundsCheck.check(owner, sym, 1, 6_000.0, 500_000.0, 92.0))
        assertNull("the cash not read (a failed read)", FundsCheck.check(owner, sym, 1, 6_000.0, null, 92.0))
        assertNull(FundsCheck.check(owner, sym, 1, 6_000.0, Double.NaN, 92.0))
        assertNull("no price", FundsCheck.check(owner, sym, 1, 0.0, 5_000.0, null))
        FundsCheck.set(true)
        assertNull("on too: unknown is never a skip", FundsCheck.check(owner, sym, 1, 6_000.0, null, 92.0))
        assertTrue("nothing recorded", FundsCheck.recsOf().isEmpty())
    }

    @Test fun onItRefusesAndKeepsAWouldBeEntryAndOffAgainTakesThePin() {
        assertEquals("On: a live arm entry the free cash cannot pay for even one lot is not sent; it is kept as a would-be entry.", FundsCheck.set(true))
        assertTrue(FundsCheck.isOn())
        val why = FundsCheck.check(owner, sym, 1, 6_000.0, 5_000.0, 92.0)
        assertEquals("refused: ${FundsCheck.WORDS} (funds check on; kept as a would-be entry)", why)
        assertTrue(FundsCheck.recsOf().single().applied)
        assertEquals(92.0, FundsCheck.recsOf().single().price!!, 0.0)
        assertTrue(diagHas("(funds check on: not sent, kept as a would-be entry)"))
        // Off adds live entries back: never without the PIN.
        assertTrue(FundsCheck.set(false).endsWith("it takes your PIN. Nothing changed."))
        assertTrue(FundsCheck.isOn())
        assertEquals("Off: in shadow - such entries go to Zerodha as before; each is logged.", FundsCheck.set(false, pinConfirmed = true))
        assertFalse(FundsCheck.isOn())
        assertNull("back in shadow", FundsCheck.check(owner, sym, 1, 6_000.0, 5_000.0, 92.0))
        assertEquals("The funds check is already off (shadow).", FundsCheck.set(false, pinConfirmed = true))
    }

    @Test fun theSwitchSurvivesARestartAndAnEraseClearsIt() {
        FundsCheck.set(true)
        SecurePrefs.flush()
        FundsCheck.resetForTest(on = false, loaded = false)
        FundsCheck.load()
        assertTrue("read back from the settings", FundsCheck.isOn())
        assertTrue(FundsCheck.holdsForErase())
        FundsCheck.wipe()
        assertFalse(FundsCheck.isOn()); assertFalse(FundsCheck.holdsForErase())
    }

    @Test fun theKeptFreeCashIsOnlyAFreshReadFromMemory() {
        Broker.resetForTest()
        assertNull("nothing kept: unknown", Broker.keptFreeCash())
    }
}
