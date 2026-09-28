package com.optionslab.app.data

import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.sandbox.SandboxConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The paper account's book: fresh start, reset, persistence in the vault, an unreadable file set
 * aside, symbols. (Orders need the price feed; the order flows are tested with the screens.)
 */
class PaperTest : RobolectricTest() {
    private fun file() = File(context.filesDir, "paper.vault")

    /** A cold start: the in-memory book is dropped, the vault file stays. */
    private fun restart() = Paper::class.java.getDeclaredField("cache").apply { isAccessible = true }.set(null, null)

    @After fun offline() = assertEquals(emptyList<String>(), NetworkGuard.blocked.toList())

    @Test fun aFreshAccountHasTheDefaultCapitalAndNothingElse() {
        assertEquals(0, Paper.capital.compareTo(SandboxConfig().startingCapital))
        val s = Paper.state
        assertTrue(s.orders.isEmpty() && s.trades.isEmpty() && s.positions.isEmpty())
        val snap = runBlocking { Paper.snapshot() }
        assertTrue(snap.priced)
        assertEquals(0.0, snap.funds.totalPnl, 0.0)
        assertTrue(snap.orders.orders.isEmpty())
    }

    @Test fun resetSetsTheCapitalAndIsKeptInTheVault() {
        Paper.reset(BigDecimal("250000"))
        assertEquals(0, Paper.capital.compareTo(BigDecimal("250000")))
        assertTrue(file().exists())
        assertFalse("sealed", String(file().readBytes(), Charsets.ISO_8859_1).contains("250000"))
        restart()
        assertEquals(0, Paper.capital.compareTo(BigDecimal("250000")))
    }

    @Test fun wipeStartsAgain() {
        Paper.reset(BigDecimal("250000"))
        Paper.wipe()
        assertFalse(file().exists())
        assertEquals(0, Paper.capital.compareTo(SandboxConfig().startingCapital))
    }

    @Test fun anUnreadableAccountIsSetAsideNeverOverwritten() {
        file().writeBytes("not the vault".toByteArray())
        restart()
        assertEquals(0, Paper.capital.compareTo(SandboxConfig().startingCapital))
        assertFalse(file().exists())
        assertTrue(context.filesDir.listFiles()!!.any { it.name.startsWith("paper.unreadable.") })
    }

    @Test fun modifyingAnUnknownOrderIsRefused() {
        val r = Paper.modify("no-such-order", 75, 10.0, null)
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("not found"))
        assertTrue(r.events.isEmpty())
    }

    @Test fun symbolsCarryUnderlyingExpiryStrikeAndRight() {
        val c = Upstox.Contract("NIFTY", LocalDate.of(2026, 9, 29), 24_500.0, Right.PE, 75, "NSE_FO|40001", "NIFTY26SEP24500PE")
        assertEquals("NIFTY29SEP2624500PE", Paper.symbolOf(c))
        assertEquals("BANKNIFTY30OCT2651250.5CE", Paper.symbolOf(c.copy(underlying = "BANKNIFTY", expiry = LocalDate.of(2026, 10, 30), strike = 51_250.5, right = Right.CE)))
        assertNull("never traded here", Paper.contractOf("NIFTY29SEP2624500PE"))
    }
}
