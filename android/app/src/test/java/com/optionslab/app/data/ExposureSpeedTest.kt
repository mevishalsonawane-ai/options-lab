package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.sandbox.Quote
import com.optionslab.ira.AutoChange
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 19 Oct (Boss: "Resolve the issues"), on the paper book itself:
 *  - SPEED: a slow vault write (1.5 s, as a Pixel's StrongBox) used to hold every paper order until it was on disk; now an order
 *    is answered from memory and the write follows on the paper writer - decision to sent and sent to answer stay well under a
 *    second with the slow store, and the book is still on disk right after;
 *  - the SAME-DIRECTION CAP: 11 automatic puts on one bar let 2 in and refuse 9 (each kept as a would-be entry, Liquidity's counted
 *    apart); exits are never refused and free a place; Boss's own orders are never capped or counted; live only logs (shadow)
 *    until its switch is on; the Autopilot entry's undo switches it off and on again; a capped order reads no setting.
 */
class ExposureSpeedTest : RobolectricTest() {
    private val exp: LocalDate = LocalDate.now().plusDays(6)

    private fun contract(u: String, strike: Int, right: Right): Paper.Contract {
        val sym = u + exp.format(DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH)).uppercase(Locale.ENGLISH) + strike + right.name
        return Paper.Contract(sym, u, exp, strike.toDouble(), right, 30, "NSE_FO|$sym", if (u == "SENSEX") "BFO" else "NFO")
    }

    private fun buy(c: Paper.Contract, auto: String?): Paper.Result =
        runBlocking { Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, Quote(100.0, bid = 99.9, ask = 100.1), auto = auto) }

    private fun sell(c: Paper.Contract): Paper.Result =
        runBlocking { Paper.place(c, "SELL", 1, "MARKET", "MIS", null, null, Quote(100.0, bid = 99.9, ask = 100.1)) }

    @After fun down() {
        Vault.testWriteDelayMs = 0L
        Paper.awaitWrites()
        Paper.writeInlineForTest = true
        PassedArmsHost.writeInlineForTest = true
        Strategies.writeInlineForTest = true
        SecurePrefs.countedThread = null
        ExposureCapHost.resetForTest(paperOn = false)
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    // ---- speed ------------------------------------------------------------------------------------------------------------

    /**
     * One bar-close run with a 1.5 s vault write: the arm decides once at the run's start, then buys on five indices one after
     * another, as R51 did at 09:30 on 19 Oct ([inline] true: the write on the caller's thread, as before). Each order's time
     * (ms) and the run's paper "decision -> sent" median.
     */
    private fun fiveOrders(inline: Boolean): Pair<List<Long>, Long> {
        OrderTiming.forget(); SecurePrefs.put("speed.day", null)
        Paper.writeInlineForTest = inline
        Vault.testWriteDelayMs = 1_500L
        try {
            val each = runBlocking {
                withContext(OrderTiming.Trigger(System.currentTimeMillis(), null)) {
                    OrderTiming.decided()
                    listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX").mapIndexed { i, u ->
                        val c = contract(u, 20_000 + i * 100, Right.PE)
                        val t0 = System.nanoTime()
                        val r = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, Quote(100.0, bid = 99.9, ask = 100.1), auto = "R51 Donchian 20")
                        assertTrue(r.message, r.ok)
                        (System.nanoTime() - t0) / 1_000_000
                    }
                }
            }
            val ds = OrderTiming.paperDecisionToSent()
            assertNotNull(ds); assertEquals(5, ds!!.second)
            return each to ds.first
        } finally { Vault.testWriteDelayMs = 0L }
    }

    @Test fun aSlowVaultWriteHeldEveryPaperOrderBeforeAndNoLongerDoes() {
        // Before (the write on the caller's thread): each order waited its write, and each later index's order waited for the ones
        // before it - the 19 Oct "decision to sent 5.7 s typical" in miniature.
        val (before, dsBefore) = fiveOrders(inline = true)
        assertTrue("$before", before.all { it >= 1_500L })
        assertTrue("decision to sent before: $dsBefore ms", dsBefore >= 2_500L)
        Paper.wipe()
        // Now: answered from memory; the writes follow on the paper writer.
        val (after, dsAfter) = fiveOrders(inline = false)
        assertTrue("each order well under a second with a 1.5 s vault write: $after", after.all { it < 500L })
        assertTrue("decision to sent after: $dsAfter ms", dsAfter < 1_000L)
        // Durable: the writer catches up, and a restart reads every order back from the file.
        assertTrue(Paper.awaitWrites(20_000L))
        assertTrue(File(context.filesDir, "paper.vault").exists())
        Paper.forgetForTest()
        assertEquals(5, Paper.state.positions.count { it.quantity == 30 })
    }

    @Test fun anExitIsAnsweredAtOnceWhileTheVaultIsSlow() {
        // Positions first: an exit never waits on a write either.
        val c = contract("NIFTY", 25_000, Right.CE)
        assertTrue(buy(c, null).ok)
        Paper.writeInlineForTest = false
        Vault.testWriteDelayMs = 1_500L
        val t0 = System.nanoTime()
        val r = sell(c)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(r.message, r.ok)
        assertTrue("an exit answered in $ms ms", ms < 500L)
    }

    // ---- the same-direction cap ----------------------------------------------------------------------------------------------

    /** 19 Oct 09:30: R51 and R72 on four indices each, Liquidity's 15m and 5m books on one BANKNIFTY put and one FINNIFTY. */
    private val nineThirty = listOf(
        "R51 Donchian 20" to contract("BANKNIFTY", 55_500, Right.PE), "R51 Donchian 20" to contract("FINNIFTY", 26_000, Right.PE),
        "R51 Donchian 20" to contract("MIDCPNIFTY", 13_000, Right.PE), "R51 Donchian 20" to contract("SENSEX", 81_000, Right.PE),
        "R72 Breadth Donchian" to contract("BANKNIFTY", 55_600, Right.PE), "R72 Breadth Donchian" to contract("FINNIFTY", 26_100, Right.PE),
        "R72 Breadth Donchian" to contract("MIDCPNIFTY", 13_100, Right.PE), "R72 Breadth Donchian" to contract("SENSEX", 81_100, Right.PE),
        "Liquidity 15m" to contract("BANKNIFTY", 55_500, Right.PE), "Liquidity 5m" to contract("BANKNIFTY", 55_500, Right.PE),
        "Liquidity 5m FINNIFTY" to contract("FINNIFTY", 26_000, Right.PE),
    )

    @Test fun elevenSameBarPutsLetTwoInAndRefuseNineEachKeptAsAWouldBeEntry() {
        ExposureCapHost.resetForTest(paperOn = true)
        val results = nineThirty.map { (arm, c) -> arm to buy(c, arm) }
        val went = results.filter { it.second.ok }
        assertEquals("first to reach the door wins", listOf("R51 Donchian 20", "R51 Donchian 20"), went.map { it.first })
        val refused = results.filter { !it.second.ok }
        assertEquals(9, refused.size)
        assertTrue(refused.all { it.second.capped && it.second.message.startsWith("same-direction cap: 2 puts already open") })
        assertTrue("nothing placed for a refused entry", refused.all { it.second.orderId == null && it.second.events.isEmpty() })
        assertEquals(2, Paper.state.orders.size)
        val kept = ExposureCapHost.today()
        assertEquals(9, kept.size)
        assertTrue(kept.all { !it.live && it.applied && it.dir == -1 && it.price == 100.0 })
        assertEquals("Liquidity's refused entries counted apart", 3, ExposureCapHost.liquidityToday())
        assertTrue(ExposureCapHost.line(), ExposureCapHost.line().contains("today 9 paper entries refused"))
        // The same arm retrying its bar a minute later is the same would-be entry, recorded once.
        assertFalse(buy(nineThirty[4].second, nineThirty[4].first).ok)
        assertEquals(9, ExposureCapHost.today().size)
        // A call bets the other way: it goes.
        assertTrue(buy(contract("NIFTY", 25_000, Right.CE), "R51 Donchian 20").ok)
        // Kept across a restart: the automatic orders are in the book's own file.
        Paper.forgetForTest()
        assertFalse(buy(contract("NIFTY", 24_000, Right.PE), "Hero").ok)
    }

    @Test fun anExitIsNeverRefusedAndFreesAPlace() {
        ExposureCapHost.resetForTest(paperOn = true)
        val a = contract("NIFTY", 25_000, Right.PE); val b = contract("BANKNIFTY", 55_500, Right.PE); val c = contract("FINNIFTY", 26_000, Right.PE)
        assertTrue(buy(a, "ORB").ok); assertTrue(buy(b, "Liquidity 15m").ok)
        assertTrue(buy(c, "Pine").capped)
        assertTrue("the exit goes", sell(a).ok)
        assertTrue("its place is free", buy(c, "Pine").ok)
        // A stop resting on the full cap still fills when touched (only BUYs that open are asked).
        assertTrue(runBlocking { Paper.place(c, "SELL", 1, "SL-M", "MIS", null, 90.0, Quote(100.0), auto = "Pine") }.ok)
    }

    @Test fun bossesOwnOrdersAreNeverCappedOrCounted() {
        ExposureCapHost.resetForTest(paperOn = true)
        for (i in 0 until 4) assertTrue("manual $i", buy(contract("NIFTY", 24_000 + i * 100, Right.PE), null).ok)
        assertTrue(Paper.capOpenNow().isEmpty())
        assertTrue(buy(contract("BANKNIFTY", 55_500, Right.PE), "ORB").ok)
        assertTrue(buy(contract("SENSEX", 81_000, Right.PE), "Hero ticket").ok)
        assertTrue(buy(contract("FINNIFTY", 26_000, Right.PE), "Pine").capped)
        // A stock or MCX option is never counted (only the index options).
        assertTrue(buy(contract("RELIANCE", 3_000, Right.PE), "Pine").ok)
    }

    @Test fun offOrAtAHigherNumberItRefusesNothingMore() {
        ExposureCapHost.resetForTest(paperOn = false)
        nineThirty.take(4).forEach { (arm, c) -> assertTrue(buy(c, arm).ok) }
        ExposureCapHost.setPaperOn(true, log = false)
        assertTrue(buy(nineThirty[4].second, nineThirty[4].first).capped)
        ExposureCapHost.setMax(5)
        assertTrue(buy(nineThirty[4].second, nineThirty[4].first).ok)
        assertTrue(buy(nineThirty[5].second, nineThirty[5].first).capped)
    }

    @Test fun aCappedOrderReadsNoSettingAndTouchesNoDisk() {
        ExposureCapHost.resetForTest(paperOn = true)
        assertTrue(buy(nineThirty[0].second, nineThirty[0].first).ok)
        assertTrue(buy(nineThirty[1].second, nineThirty[1].first).ok)
        Paper.writeInlineForTest = false
        Vault.testWriteDelayMs = 1_500L
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        val t0 = System.nanoTime()
        try {
            for ((arm, c) in nineThirty.drop(2)) assertTrue(buy(c, arm).capped)
        } finally { SecurePrefs.countedThread = null }
        assertEquals("settings reads while refusing", 0L, SecurePrefs.readsForTest.get())
        assertTrue("nine refusals answered at once", (System.nanoTime() - t0) / 1_000_000 < 1_000L)
    }

    @Test fun liveIsShadowOnlyUntilItsSwitchIsOn() {
        ExposureCapHost.resetForTest(paperOn = true)
        ExposureCapHost.liveEntered("NIFTY26OCT25000PE", "NFO", true, "orb")
        ExposureCapHost.liveEntered("BANKNIFTY26OCT55500PE", "NFO", true, "liquidity15")
        // Shadow (the default): the third live put is not refused - only recorded as what it would have refused.
        assertNull(ExposureCapHost.liveCheck("FINNIFTY26OCT26000PE", "NFO", true, "pine"))
        val w = ExposureCapHost.today().single()
        assertTrue(w.live && !w.applied)
        assertTrue(ExposureCapHost.line().contains("live: 1 would have been refused (logged only)"))
        // The entry door itself, in shadow: never the cap's refusal.
        val o = Kite.Order("FINNIFTY26OCT26000PE", Kite.Side.BUY, 65, 65, "MIS", "MARKET", null, tag = "iraorb")
        assertFalse(Broker.entryRecheck(o, "orb").orEmpty().contains("same-direction cap"))
        // An exit: never asked; it frees a place.
        assertNull(ExposureCapHost.liveCheck("NIFTY26OCT25000PE", "NFO", false, "orb"))
        // On: the bots' live entries past the cap are refused at the door.
        ExposureCapHost.setLiveOn(true)
        assertTrue(Broker.entryRecheck(o, "orb").orEmpty().contains("same-direction cap: 2 puts already open"))
        // A stop that filled at Zerodha: the next positions read frees its place.
        ExposureCapHost.livePositions(mapOf("BANKNIFTY26OCT55500PE" to 30), System.currentTimeMillis() + 1)
        assertNull(ExposureCapHost.liveCheck("FINNIFTY26OCT26000PE", "NFO", true, "pine"))
        // A saved strategy's basket leg is never asked.
        ExposureCapHost.liveEntered("SENSEX26OCT81000PE", "BFO", true, "orb")
        val leg = o.copy(tag = "iraalgostrat")
        assertFalse(Broker.entryRecheck(leg, null).orEmpty().contains("same-direction cap"))
    }

    @Test fun theAutopilotUndoSwitchesThePaperCapOffAndBackOn() {
        ExposureCapHost.resetForTest(paperOn = true)
        val on = AutoChange.sameDirCap(true)
        assertNotNull(com.optionslab.app.ira.Autopilot.apply(AutoChange.inverse(on, "undone by you"), false))
        assertNull("off: nothing refused", ExposureCapHost.paperMax())
        nineThirty.take(3).forEach { (arm, c) -> assertTrue(buy(c, arm).ok) }
        assertNotNull(com.optionslab.app.ira.Autopilot.apply(on, false))
        assertEquals(2, ExposureCapHost.paperMax())
        assertTrue(buy(nineThirty[3].second, nineThirty[3].first).capped)
    }
}
