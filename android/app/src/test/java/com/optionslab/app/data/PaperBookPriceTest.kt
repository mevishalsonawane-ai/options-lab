package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.engine.Kite
import com.optionslab.engine.KiteTicks
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.sandbox.SandboxEvent
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
import java.time.LocalDate

/**
 * 28 Oct (Boss: "Yes fix finnifty"; the phone's diagnostics: "R72 Breadth Donchian · FINNIFTY 09:20 put break not bought:
 * FINNIFTY23NOV2624250PE: no fresh price"). The cause: Zerodha reports a last price of 0 for an option that has not traded, the
 * paper price layer dropped every such quote, and the candle feed has nothing for an untraded option - though its live bid and
 * ask were there. Now [Paper] prices it on the book: the ltp is the bid (a held long's stops, targets and marks read it), an
 * entry fills at the ask on a sane book only, a one-sided, empty, wide or stale book is refused with its reason, the contract
 * lookup is the same for every index (FINNIFTY's holiday-shifted monthly expiry included), and quotes with a last price are
 * unchanged. The feed checks stay on (a book read is never mistaken for a stale candle). Market clock pinned at noon.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PaperBookPriceTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private lateinit var upstox: FakeUpstox
    private val expiry get() = TradeFixtures.nearExpiry
    private val made = ArrayList<Paper.Contract>()

    /** FINNIFTY [strike] [right], listed on both lists (Upstox's and Zerodha's), with its Zerodha token; no candles today. */
    private fun fin(strike: Int, right: Right = Right.PE, token: Long = 61_000_000L + strike): Pair<Paper.Contract, Long> {
        val c = Paper.contractFor("FINNIFTY", expiry, strike.toDouble(), right) ?: throw AssertionError("FINNIFTY $strike not listed")
        made += c
        return c to token
    }

    private fun tick(token: Long, last: Double, bid: Double?, ask: Double?, atMs: Long = System.currentTimeMillis()) =
        KiteStream.feedForTest(listOf(KiteTicks.Tick(token, last, bid = bid, ask = ask, exchangeTime = System.currentTimeMillis() / 1000)), atMs)

    private fun kiteSym(u: String, strike: Int, right: Right) = "${u}TEST$strike${right.name}"

    @Before fun up() {
        TradeFixtures.paperSettings()
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.keepAlive = true
        kite.login(live = false)
        val rows = ArrayList<Upstox.Contract>()
        for (k in listOf(24_150, 24_200, 24_250, 24_300, 24_350, 24_400, 24_450, 24_500)) for (r in listOf(Right.CE, Right.PE)) {
            rows += Upstox.Contract("FINNIFTY", expiry, k.toDouble(), r, 65, "NSE_FO|FN$k${r.name}", "FINNIFTY $k ${r.name}")
            kite.instruments += FakeKite.Ins(61_000_000L + k + (if (r == Right.CE) 100_000 else 0), kiteSym("FINNIFTY", k, r), "FINNIFTY", expiry, k.toDouble(), r.name, 65)
        }
        rows += Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|BN52000CE", "BANKNIFTY 52000 CE")
        kite.instruments += FakeKite.Ins(62_000_001L, kiteSym("BANKNIFTY", 52_000, Right.CE), "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        AutomationSupport.contracts(context, rows)
        runBlocking { Broker.instruments() }
        KiteStream.resetForTest()
        BookPriceHost.setForTest(true)
    }

    @After fun down() {
        made.forEach { Paper.tokenForTest(it.symbol, null) }
        Paper.bookFillForTest(null)
        BookPriceHost.setForTest(true)
        KiteStream.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun held(c: Paper.Contract) = Paper.state.positions.filter { it.symbol == c.symbol }.sumOf { it.quantity }

    private fun buy(c: Paper.Contract, q: com.optionslab.engine.sandbox.Quote?) =
        runBlocking { Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null, known = q) }

    // ---- the contract lookup (every index the same way) ----------------------------------------------------------------------

    /** FINNIFTY's November monthly expiry is Monday 23 Nov 2026 (24 Nov is a holiday): taken from the lists, as every index's. */
    @Test fun finniftyResolvesOnItsListedHolidayShiftedExpiryLikeEveryIndex() {
        val nov23 = LocalDate.of(2026, 11, 23)
        assertEquals(java.time.DayOfWeek.MONDAY, nov23.dayOfWeek)
        // The nearest listed expiry after 28 Oct is the one the exchange listed, never a weekday rule.
        assertEquals(nov23, OrbRules.expiryAfter(LocalDate.of(2026, 10, 28), listOf(LocalDate.of(2026, 12, 29), nov23)))
        assertEquals("FINNIFTY23NOV2624250PE", Paper.symbolOf(Upstox.Contract("FINNIFTY", nov23, 24_250.0, Right.PE, 65, "k", "x")))
        assertEquals("BANKNIFTY23NOV2652000CE", Paper.symbolOf(Upstox.Contract("BANKNIFTY", nov23, 52_000.0, Right.CE, 30, "k", "x")))
        val list = listOf(
            Kite.Instrument(1, "FINNIFTY26NOV24250PE", "FINNIFTY", nov23, 24_250.0, 65, Right.PE, 0.05),
            Kite.Instrument(2, "BANKNIFTY26NOV52000CE", "BANKNIFTY", nov23, 52_000.0, 30, Right.CE, 0.05),
            Kite.Instrument(3, "NIFTY26N0324500CE", "NIFTY", LocalDate.of(2026, 11, 3), 24_500.0, 75, Right.CE, 0.05))
        assertEquals(1L, Broker.find(list, "FINNIFTY", nov23, 24_250.0, Right.PE)?.token)
        assertEquals(2L, Broker.find(list, "BANKNIFTY", nov23, 52_000.0, Right.CE)?.token)
        assertEquals(3L, Broker.find(list, "NIFTY", LocalDate.of(2026, 11, 3), 24_500.0, Right.CE)?.token)
        assertNull("the unshifted Tuesday is not listed", Broker.find(list, "FINNIFTY", LocalDate.of(2026, 11, 24), 24_250.0, Right.PE))
        // On the phone: the FINNIFTY contract finds its Zerodha token in the day's list and streams.
        val (c, token) = fin(24_250)
        tick(token, 0.0, 149.0, 150.0)
        assertTrue("FINNIFTY's option follows the stream by its own token", Paper.streams(c))
    }

    // ---- entries -------------------------------------------------------------------------------------------------------------

    @Test fun noLastPriceWithASaneBookIsPricedAtTheBidAndBuysAtTheAsk() {
        val (c, token) = fin(24_200)
        tick(token, 0.0, 149.0, 150.0)
        val q = runBlocking { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) } ?: throw AssertionError("no price")
        assertTrue(q.noLast)
        assertEquals("a held long is worth the bid", 149.0, q.ltp, 1e-9)
        assertEquals("a buyer pays the ask", 150.0, Paper.entryPrice(c, q)!!, 1e-9)
        val r = buy(c, q)
        assertTrue(r.message, r.ok)
        val f = r.events.filterIsInstance<SandboxEvent.Fill>().single()
        assertEquals("filled at the ask, no second spread on top", 150.0, f.price, 1e-9)
        assertEquals(65, held(c))
        assertTrue("noted for the live mirror (never mirrored)", Paper.filledOnBook(c.symbol))
    }

    @Test fun aOneSidedBookIsRefusedWithItsReason() {
        val (c, token) = fin(24_300)
        tick(token, 0.0, 149.0, null)
        val q = runBlocking { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) }
        assertNotNull("the bid still prices a held long", q)
        assertNull(Paper.entryPrice(c, q))
        assertEquals("no last price and a one-sided book (bid 149.00, no ask)", Paper.noPriceWhy(c.symbol))
        val r = buy(c, q)
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("one-sided book"))
        assertEquals(0, held(c))
        assertFalse(Paper.filledOnBook(c.symbol))
    }

    @Test fun anEmptyBookHasNoPriceAndSaysSo() {
        val (c, token) = fin(24_350)
        tick(token, 0.0, null, null)
        assertNull(runBlocking { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) })
        assertEquals("no last price and an empty book (no bid, no ask)", Paper.noPriceWhy(c.symbol))
        val r = buy(c, null)
        assertFalse(r.ok)
        assertEquals(0, held(c))
    }

    @Test fun aWideSpreadIsRefused() {
        val (c, token) = fin(24_400)
        tick(token, 0.0, 140.0, 150.0)
        val q = runBlocking { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) }
        assertNull(Paper.entryPrice(c, q))
        assertTrue(Paper.noPriceWhy(c.symbol)!!, Paper.noPriceWhy(c.symbol)!!.contains("spread is too wide"))
        val r = buy(c, q)
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("spread is too wide"))
        assertEquals(0, held(c))
    }

    @Test fun aStaleTickIsNotUsedAndZerodhasFreshBookIsReadInstead() {
        val (c, token) = fin(24_450)
        // A book 30 s old: not a price (the stream's 5 s). No candles today and no quote from Zerodha either: no price, said.
        tick(token, 0.0, 149.0, 150.0, atMs = System.currentTimeMillis() - 30_000)
        assertNull(runBlocking { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) })
        assertEquals("no trade today and Zerodha's quote did not answer", Paper.noPriceWhy(c.symbol))
        // A second try within 5 s does not call Zerodha again (its rate limit): said so.
        assertNull(runBlocking { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) })
        assertEquals("no trade today; its book was read moments ago", Paper.noPriceWhy(c.symbol))
        Paper.forgetCandlesForTest()
        // Zerodha's quote answers with a live book (no last price): read now, priced on it.
        kite.quote("NFO:" + kiteSym("FINNIFTY", 24_450, Right.PE), 0.0, 149.0, 150.0)
        val q = runBlocking { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) } ?: throw AssertionError("no price")
        assertTrue(q.noLast)
        assertEquals(150.0, Paper.entryPrice(c, q)!!, 1e-9)
        assertNull("a price came: the reason is cleared", Paper.noPriceWhy(c.symbol))
    }

    @Test fun withBossSwitchOffABookEntryIsRefusedAsBefore() {
        val (c, token) = fin(24_150)
        BookPriceHost.setForTest(false)
        tick(token, 0.0, 149.0, 150.0)
        val q = runBlocking { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) }
        assertNull(Paper.entryPrice(c, q))
        assertEquals("no last price, and paper entries on the live book are switched off", Paper.noPriceWhy(c.symbol))
        assertFalse(buy(c, q).ok)
        assertEquals(0, held(c))
    }

    /** Review: a resting entry never fills on a book an entry may not fill on (wide, or the switch off); on a sane one it does. */
    @Test fun aRestingBuyLimitWaitsOnARefusedBookAndFillsOnASaneOne() {
        val (c, token) = fin(24_300, Right.CE, token = 61_000_000L + 24_300 + 100_000)
        tick(token, 100.0, 99.0, 101.0)
        val r = runBlocking { Paper.place(c, "BUY", 1, "LIMIT", "NRML", 95.0, null) }
        assertTrue(r.message, r.ok)
        val id = r.orderId!!
        // No last price, a wide book whose ask is under the limit: held, never filled there.
        tick(token, 0.0, 85.0, 94.0)
        runBlocking { Paper.tick() }
        assertEquals("open", Paper.state.orders.first { it.orderId == id }.status)
        // With the switch off, a sane book holds it too (the timeline's Undo holds for orders already resting).
        BookPriceHost.setForTest(false)
        tick(token, 0.0, 93.9, 94.0)
        runBlocking { Paper.tick() }
        assertEquals("open", Paper.state.orders.first { it.orderId == id }.status)
        // Switch on, a sane book with the ask through the limit: filled at the limit, and noted as a book fill (never mirrored live).
        BookPriceHost.setForTest(true)
        runBlocking { Paper.tick() }
        assertEquals("complete", Paper.state.orders.first { it.orderId == id }.status)
        assertEquals(65, held(c))
        assertTrue(Paper.filledOnBook(c.symbol))
    }

    // ---- exits (open positions first: never weakened) -------------------------------------------------------------------------

    @Test fun aHeldPositionWithNoLastPriceIsMarkedAtTheBidAndItsStopFiresOnTheBid() {
        val (c, token) = fin(24_500, Right.CE, token = 61_000_000L + 24_500 + 100_000)
        tick(token, 100.0, 99.95, 100.05)
        assertTrue(buy(c, runBlocking { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) }).ok)
        assertEquals(65, held(c))
        val stop = runBlocking { Paper.place(c, "SELL", 1, "SL-M", "NRML", null, 90.0) }
        assertTrue(stop.message, stop.ok)
        // The option stops trading: no last price, bid 95. Not at the stop; marked at the bid.
        tick(token, 0.0, 95.0, 96.0)
        runBlocking { Paper.tick() }
        assertEquals(65, held(c))
        assertEquals(95.0, Paper.state.positions.first { it.symbol == c.symbol }.ltp!!.toDouble(), 1e-9)
        // Bid 89 (the ask 89.5 still over the stop): the stop fires on the bid and sells there.
        tick(token, 0.0, 89.0, 89.5)
        val events = runBlocking { Paper.tick() }
        val f = events.filterIsInstance<SandboxEvent.Fill>().single { it.symbol == c.symbol }
        assertEquals("SELL", f.action)
        assertEquals(89.0, f.price, 1e-9)
        assertEquals(0, held(c))
    }

    @Test fun aMarketExitOfAPositionWithNoLastPriceSellsAtTheBid() {
        val (c, token) = fin(24_250, Right.CE, token = 61_000_000L + 24_250 + 100_000)
        tick(token, 100.0, 99.95, 100.05)
        assertTrue(buy(c, runBlocking { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) }).ok)
        // Wide and one-sided books never hold an exit back: whoever bids, the long sells there.
        tick(token, 0.0, 80.0, null)
        val r = runBlocking { Paper.place(c, "SELL", 1, "MARKET", "NRML", null, null, exit = true) }
        assertTrue(r.message, r.ok)
        assertEquals(80.0, r.events.filterIsInstance<SandboxEvent.Fill>().single().price, 1e-9)
        assertEquals(0, held(c))
    }

    // ---- other indices unchanged ----------------------------------------------------------------------------------------------

    @Test fun aQuoteWithALastPriceIsUnchangedOnEveryIndex() {
        val c = Paper.contractFor("BANKNIFTY", expiry, 52_000.0, Right.CE)!!
        made += c
        tick(62_000_001L, 200.0, 199.95, 200.05)
        val q = runBlocking { Paper.recentQuote(c, Paper.PLACE_REUSE_MS) }!!
        assertFalse(q.noLast)
        assertEquals(200.0, q.ltp, 1e-9)
        assertEquals("the last price, as before", 200.0, Paper.entryPrice(c, q)!!, 1e-9)
        val r = buy(c, q)
        assertEquals(200.05, r.events.filterIsInstance<SandboxEvent.Fill>().single().price, 1e-9)
        assertFalse(Paper.filledOnBook(c.symbol))
    }
}
