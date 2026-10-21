package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.sandbox.Quote
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate

/**
 * 20 Oct, review of the tick path and open-position watching:
 *  - SENSEX options (BFO) have stream tokens from today's BFO list in memory, so a live SENSEX trade's exits stream too (T5);
 *  - the fast path's live-exit tokens are read from memory only on its consumer thread: after a restart the instruments file
 *    is parsed in the background, never on the tick path, and the token follows on the next look (T6);
 *  - the NSE pass's "holding" also counts the paper book and the Night, VIX divergence and MCX paper arms' trades, so their
 *    resting stops are checked every 15 s, not only on the ~60 s full pass (T3; open positions come first).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class TickPathReviewTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private var kite: FakeKite? = null

    @After fun down() {
        liveKeys(emptyList())
        setStatic("bfoOptions", null)
        mcxMem(null)
        FastPath.resetForTest()
        Market.testClock = null
        kite?.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** The active live protections' keys as [Protections] keeps them (its setter is private: memory only, as the app sets it). */
    private fun liveKeys(keys: List<String>) =
        Protections::class.java.getDeclaredField("liveKeysHint").apply { isAccessible = true }.set(null, keys)

    /** The MCX list in memory as [McxMarket] keeps it (null: forgotten). */
    private fun mcxMem(v: Any?) = McxMarket::class.java.getDeclaredField("mem").apply { isAccessible = true }.set(null, v)

    private fun setStatic(name: String, v: Any?) =
        Broker::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, v)

    @Test fun aSensexOptionHasItsStreamTokenFromTodaysBfoList() {
        val sym = "SENSEX26OCT81000CE"
        assertNull("no list read today: unknown, never a guess", Broker.tokenOf("BFO:$sym"))
        setStatic("bfoOptions", Market.today() to listOf(
            Kite.Instrument(1_234_567L, sym, "SENSEX", LocalDate.now().plusDays(3), 81_000.0, 20, Right.CE, 0.05)))
        assertEquals(1_234_567L, Broker.tokenOf("BFO:$sym"))
        assertEquals("memory only, the same answer", 1_234_567L, Broker.tokenKnown("BFO:$sym"))
        assertNull("another symbol", Broker.tokenKnown("BFO:SENSEX26OCT82000CE"))
        assertNull("an NFO key is never answered from the BFO list", Broker.tokenKnown("NFO:$sym"))
        // Yesterday's list is not today's: not used.
        setStatic("bfoOptions", Market.today().minusDays(1) to listOf(
            Kite.Instrument(1_234_567L, sym, "SENSEX", LocalDate.now().plusDays(3), 81_000.0, 20, Right.CE, 0.05)))
        assertNull(Broker.tokenOf("BFO:$sym"))
        // And a live SENSEX trade's key now reaches the stream's live exits.
        setStatic("bfoOptions", Market.today() to listOf(
            Kite.Instrument(1_234_567L, sym, "SENSEX", LocalDate.now().plusDays(3), 81_000.0, 20, Right.CE, 0.05)))
        liveKeys(listOf("BFO:$sym"))
        assertTrue(1_234_567L in FastPath.liveTokens())
    }

    /**
     * 20 Oct, second review: the tick path's memory-only lookup had no MCX branch, so a live MCX trade's exits left the stream
     * (REST every 2 s) and its key was looked up again every 5 s all session. MCX now answers from its list in memory.
     */
    @Test fun anMcxLiveTradesTokenReachesTheLiveExitsFromMemory() {
        val sym = "CRUDEOIL26NOVFUT"
        assertNull("no MCX list in memory: unknown", Broker.tokenKnown("MCX:$sym"))
        mcxMem(Market.today().toString() to listOf(
            com.optionslab.engine.mcx.McxContract(7_654_321L, 29_899L, sym, "CRUDEOIL", LocalDate.now().plusDays(30), 0.0, null, 1.0, 100)))
        assertEquals(7_654_321L, Broker.tokenKnown("MCX:$sym"))
        assertNull("another contract", Broker.tokenKnown("MCX:GOLDM26NOVFUT"))
        liveKeys(listOf("MCX:$sym"))
        assertTrue(7_654_321L in FastPath.liveTokens())
    }

    @Test fun afterARestartTheLiveTokensNeverParseTheInstrumentsFileOnTheTickPath() {
        val k = FakeKite().also { kite = it }
        AutomationSupport.niftyOptions(k)
        k.login()
        runBlocking { Broker.instruments() }                         // the day's file on disk, as the watch leaves it
        val key = "NFO:${AutomationSupport.NIFTY_CE}"
        assertEquals(12_345_678L, Broker.tokenKnown(key))
        // A restart: nothing in memory, the file still there.
        setStatic("tokenMap", null)
        assertNull(Broker.tokenKnown(key))
        liveKeys(listOf(key))
        val first = FastPath.liveTokens()
        assertFalse("the first look reads memory only: the token is not there yet", 12_345_678L in first)
        // The lookup ran in the background; the next look has it.
        val until = System.currentTimeMillis() + 5_000
        while (12_345_678L !in FastPath.liveTokens() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("found by the background lookup", 12_345_678L in FastPath.liveTokens())
    }

    @Test fun thePaperBookAndTheMinuteArmsCountAsHeldForTheFifteenSecondCheck() {
        val realNow = java.time.ZonedDateTime.now(com.optionslab.engine.IST)
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST),
            java.time.Duration.between(realNow, realNow.toLocalDate().atTime(12, 0).atZone(com.optionslab.engine.IST)))
        runBlocking { Paper.snapshot() }
        assertTrue("loaded", Paper.stateIfLoaded() != null)
        assertFalse("nothing open anywhere", com.optionslab.app.work.HeldBooks.paperOrMinuteArms())
        // A paper trade nobody's arm holds (Boss's own, or one only Night, VIX divergence or an MCX arm follows).
        val exp = LocalDate.now().plusDays(6)
        val sym = "NIFTY" + exp.format(java.time.format.DateTimeFormatter.ofPattern("ddMMMyy", java.util.Locale.ENGLISH))
            .uppercase(java.util.Locale.ENGLISH) + "25000PE"
        val c = Paper.Contract(sym, "NIFTY", exp, 25_000.0, Right.PE, 65, "NSE_FO|$sym", "NFO")
        assertTrue(runBlocking { Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, Quote(100.0, bid = 99.9, ask = 100.1)) }.ok)
        assertTrue("held: its resting stop is checked every 15 s", com.optionslab.app.work.HeldBooks.paperOrMinuteArms())
        // A paper book not read yet counts as held (open positions come first).
        Paper.forgetForTest()
        assertTrue(com.optionslab.app.work.HeldBooks.paperOrMinuteArms())
    }
}
