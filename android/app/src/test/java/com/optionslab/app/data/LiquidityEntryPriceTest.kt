package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.app.testing.awaitValue
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 22 Oct: a bar-close paper entry is priced at its signal and handed that price, and a fill that finds no price is tried once
 * more (21 Oct, 09:30:03, Liquidity 5m: "no price to fill at; the order was cancelled" - a real signal lost).
 *
 * The day is [LiquidityArmTest]'s: flat BANKNIFTY bars at 54,000, a swing high at 10:55 (54,100), its wick rejected again at
 * 11:25, and the 13:00 bar closing at 54,140 - taking that pool - so the 5-minute book buys the 54,000 CE on the 13:05 pass.
 * Paper only, no Zerodha session: the option is never on the stream, its price is the fake Upstox feed's.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiquidityEntryPriceTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|LIQCE"
    private val peKey = "NSE_FO|LIQPE"
    private val pe0Key = "NSE_FO|LIQPE0"

    /** The 5-minute bar k (09:15 + 5k) as (open, high, low, close); after the 13:00 break the index holds at 54,150. */
    private fun bar(k: Int): DoubleArray = when {
        k == 20 -> doubleArrayOf(54_050.0, 54_100.0, 54_040.0, 54_060.0)
        k == 26 -> doubleArrayOf(54_040.0, 54_070.0, 54_020.0, 54_030.0)
        k == 45 -> doubleArrayOf(54_020.0, 54_150.0, 54_015.0, 54_140.0)
        k >= 46 -> doubleArrayOf(54_150.0, 54_150.0, 54_145.0, 54_150.0)
        else -> doubleArrayOf(54_000.0, 54_010.0, 53_990.0, 54_000.0)
    }

    private fun feed(t: LocalDateTime): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }
        .filter { !it.plusMinutes(1).isAfter(t) }
        .map { start ->
            val b = bar((start.hour * 60 + start.minute - (9 * 60 + 15)) / 5)
            Upstox.Bar(start.atZone(IST).toEpochSecond(), b[0], b[1], b[2], b[3], 1000, 0)
        }

    @Before fun up() {
        upstox = FakeUpstox()
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        day = AutomationSupport.tradingDayAt(12, 50).toLocalDate()
        at(LocalTime.of(12, 50))
        val expiry = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.CE, 30, ceKey, "BANKNIFTY-LIQ-54000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_200.0, Right.PE, 30, peKey, "BANKNIFTY-LIQ-54200PE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.PE, 30, pe0Key, "BANKNIFTY-LIQ-54000PE")))
        upstox.price(ceKey, 300.0)
        upstox.price(peKey, 280.0)
        OrbArms.testIndexBars = { t -> feed(t) }
        OrbArms.testHistoryBars = { emptyList() }
        runBlocking { OrbArms.setLiquidityLots(1, "test") }
    }

    @After fun down() {
        SecurePrefs.countedThread = null
        com.optionslab.ira.ArmExits.clear()
        AutomationSupport.realClocks()
        Market.testClock = null
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(t: LocalTime) {
        val z = day.atTime(t).atZone(IST)
        OrbArms.testNow = z
        Market.testClock = Clock.fixed(z.toInstant(), IST)
    }

    private fun tick(t: LocalTime) { at(t); runBlocking { OrbArms.tick() } }

    private fun passes(from: LocalTime, until: LocalTime) {
        var t = from
        while (!t.isAfter(until)) { tick(t); t = t.plusMinutes(1) }
    }

    private fun row() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }

    private fun armLiquidity() {
        val msg = runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        assertTrue(msg, msg.startsWith("Liquidity 15+5 armed on paper, fully automatic"))
    }

    /** The 13:05 entry's own contract (the 1-ITM call) as the paper account names it. */
    private fun theCall(): Paper.Contract = Paper.contractFor("BANKNIFTY", day.plusDays(7), 54_000.0, Right.CE)!!

    /** The fake feed's requests for the call since [from] (each one read of its 1-minute candles). */
    private fun callReads(from: Int): Int = upstox.requests.drop(from).count { it.contains("LIQCE") }

    /** A candle whose close lies outside its own high and low: the paper book sees that price as stale and cannot fill on it. */
    private val unusable = FakeUpstox.Scripted(listOf(FakeUpstox.Candle(LocalTime.of(13, 4), 300.0, 301.0, 299.0, 320.0)))

    /** Up to the pass before the signal: armed, nothing bought, the call never read. */
    private fun beforeTheSignal() {
        armLiquidity()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 4))
        assertTrue("nothing before the 13:00 bar closes", Paper.state.orders.isEmpty())
    }

    @Test fun aBarCloseEntryWithNoStreamTickIsPricedOnceAtTheSignalAndFillsAtThatPrice() {
        beforeTheSignal()
        val from = upstox.requests.size
        tick(LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue(p.open); assertFalse("paper", p.live); assertEquals(30, p.qty)
        assertFalse("bought on its first read and order: no label", p.priceRetry)
        // The read price (300), slipped as paper fills are - never a price from anywhere else.
        assertTrue("filled at the read price: ${p.entry}", p.entry in 300.0..303.0)
        // Read once at the signal and handed to the fill: no second read of the same price.
        assertEquals(upstox.requests.drop(from).toString(), 1, callReads(from))
        val buys = Paper.state.orders.filter { it.action == "BUY" }
        assertEquals(1, buys.size); assertEquals("complete", buys.single().status)
    }

    @Test fun aFirstReadThatFailsIsReadOnceMoreAndTheEntryFills() {
        beforeTheSignal()
        // The first read fails (its own two tries both answer 503); the one more read finds the price.
        upstox.scriptNext(ceKey, FakeUpstox.Scripted(null), FakeUpstox.Scripted(null))
        val from = upstox.requests.size
        tick(LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue(p.open); assertEquals(30, p.qty)
        assertTrue("filled at the price read on the retry: ${p.entry}", p.entry in 300.0..303.0)
        assertEquals("two failed tries, then one more read", 3, callReads(from))
        assertEquals(1, Paper.state.orders.count { it.action == "BUY" && it.status == "complete" })
        assertNotNull("its resting stop", p.stopOrderId)
        assertTrue("labelled: priced again at its signal", p.priceRetry)
    }

    @Test fun aPriceTheBookCannotFillOnIsPricedAgainAndTheSameEntryFillsOnTheSecondTry() {
        beforeTheSignal()
        upstox.scriptNext(ceKey, unusable)
        val from = upstox.requests.size
        tick(LocalTime.of(13, 5))
        val p = row().today.single()
        assertTrue(p.open); assertEquals(30, p.qty); assertEquals(theCall().symbol, p.symbol)
        assertTrue("labelled: priced again for its fill", p.priceRetry)
        assertTrue("filled at the fresh price: ${p.entry}", p.entry in 300.0..303.0)
        assertEquals("the signal's read, then one fresh read", 2, callReads(from))
        val buys = Paper.state.orders.filter { it.action == "BUY" }
        assertEquals("the same option bought once: the first order cancelled, the second filled", listOf("cancelled", "complete"), buys.map { it.status })
        assertTrue(buys.all { it.symbol == p.symbol && it.quantity == 30 })
        assertEquals(30, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        // Logged: an execution retry, said in the diagnostics.
        awaitValue("the retry's diagnostics line") {
            Diag.lines().firstOrNull { it.contains("Liquidity 5m: ${p.symbol} had no price to fill at; priced again and filled on the second try") }
        }
    }

    @Test fun aPriceNeverUsableGivesTheClearRefusalAndNothingIsFilled() {
        beforeTheSignal()
        upstox.scriptNext(ceKey, unusable, unusable)
        val sym = theCall().symbol
        val from = upstox.requests.size
        tick(LocalTime.of(13, 5))
        assertTrue("nothing held", row().today.isEmpty())
        assertEquals("no more than one retry", 2, callReads(from))
        val buys = Paper.state.orders.filter { it.action == "BUY" }
        assertEquals(listOf("cancelled", "cancelled"), buys.map { it.status })
        // 22 Oct (arms audit; the 21 Oct diagnostics showed this order as "IraAlgo-Android"): both cancelled orders carry the arm's name.
        val owners = runBlocking { Strategies.owners() }
        for (o in buys) assertEquals("Liquidity 15+5 · entry", owners["paper:${o.orderId}"])
        assertTrue("nothing filled", Paper.state.trades.isEmpty())
        assertEquals(0, Paper.state.positions.sumOf { it.quantity })
        assertTrue(row().status, row().status.contains("The order was refused: no price to fill at for $sym after 2 orders; both were cancelled"))
        awaitValue("the refusal's diagnostics line") {
            Diag.lines().firstOrNull { it.contains("Liquidity 5m: order_refused: no price to fill at for $sym after 2 orders") }
        }
        awaitValue("the retry's diagnostics line") { Diag.lines().firstOrNull { it.contains("$sym had no price to fill at; priced again, still not filled") } }
    }

    /**
     * 22 Oct, review (CLAUDE.md: open positions come first): while the book holds a position, its stop looks share the arms' lock,
     * so the one more read (up to 3 s) is never made then - the entry is refused at once, said so.
     */
    @Test fun withAPositionOfTheseArmsOpenThePriceIsNeverReadAgain() {
        beforeTheSignal()
        upstox.scriptNext(ceKey, FakeUpstox.Scripted(null), FakeUpstox.Scripted(null))
        val from = upstox.requests.size
        val got = runBlocking { OrbArms.entryQuote(theCall(), mayRetry = false) }
        assertNull(got.quote); assertFalse(got.retried)
        assertEquals("only the signal's own read (its two tries), never the one more", 2, callReads(from))
    }

    @Test fun noPriceAtAllIsRefusedNamingTheOptionAndNothingIsPlaced() {
        beforeTheSignal()
        upstox.prices.remove(ceKey)                                          // the feed has no candle for the call today
        val sym = theCall().symbol
        val from = upstox.requests.size
        tick(LocalTime.of(13, 5))
        assertTrue(row().today.isEmpty())
        assertTrue("nothing placed", Paper.state.orders.isEmpty())
        assertEquals("the signal's read and one more", 2, callReads(from))
        assertTrue(row().status, row().status.contains("Refused: no quote for $sym after 2 reads"))
    }

    /** The new price reads are memory or the network only: no settings-vault read on the deciding thread, nothing else asked. */
    @Test fun theEntrysPriceReadsTouchNoVaultAndOnlyTheFeed() {
        val c = theCall()
        runCatching { Broker.loggedIn }                                       // the session's own read, kept in memory after
        upstox.scriptNext(ceKey, FakeUpstox.Scripted(null), FakeUpstox.Scripted(null))
        val from = upstox.requests.size
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        val got = try { runBlocking { OrbArms.entryQuote(c) } } finally { SecurePrefs.countedThread = null }
        assertEquals(300.0, got.quote!!.ltp, 0.0)
        assertTrue("found on the one more read", got.retried)
        assertEquals("settings reads while pricing the entry", 0L, SecurePrefs.readsForTest.get())
        assertEquals("only the call's candles were asked for", 3, upstox.requests.size - from)
        assertEquals(3, callReads(from))
        // A price read a moment ago is used again from memory: no request at all.
        val again = upstox.requests.size
        val kept = runBlocking { OrbArms.entryQuote(c) }
        assertEquals(300.0, kept.quote!!.ltp, 0.0); assertFalse(kept.retried)
        assertEquals(again, upstox.requests.size)
        // None at all: null after the one more read, within its bound.
        upstox.prices.remove(ceKey)
        val t0 = System.currentTimeMillis()
        assertNull(runBlocking { OrbArms.retryQuote(c) })
        assertTrue("bounded", System.currentTimeMillis() - t0 < OrbArms.ENTRY_RETRY_MS + 2_000)
    }
}
