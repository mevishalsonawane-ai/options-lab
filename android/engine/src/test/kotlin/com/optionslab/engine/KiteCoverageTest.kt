package com.optionslab.engine

import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.jupiter.api.Disabled as Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exhaustive branches of [Kite]: the order gates, freeze splitting, GTT and parsing. */
class KiteCoverageTest {
    private fun order(
        sym: String = "NIFTY26SEP24500PE", side: Kite.Side = Kite.Side.SELL, qty: Int = 65, lot: Int = 65,
        product: String = "NRML", type: String = "LIMIT", price: Double? = 100.0, tick: Double = 0.05,
        exchange: String = "NFO", trigger: Double? = null, tag: String = "iraalgo",
    ) = Kite.Order(sym, side, qty, lot, product, type, price, tick, exchange, tag, trigger)

    private val lim = Kite.Limits()
    private fun ref(o: Kite.Order, sent: Int = 0, hold: Boolean = false, exit: Boolean = false, refPrice: Double? = null) =
        Kite.refusals(o, lim, sent, hold, exit, refPrice)

    // ------------------------------------------------------------- login

    @Test fun `login url and redirect edge cases`() {
        assertEquals("https://kite.zerodha.com/connect/login?v=3&api_key=a%2Bb", Kite.loginUrl("a+b"))
        assertEquals(Kite.Redirect.NotOurs, Kite.readRedirect("http://a b/", "http://a/"))
        assertEquals(Kite.Redirect.NotOurs, Kite.readRedirect("http://a/", "http://a b/"))
        // Default ports are the same port.
        assertEquals(Kite.Redirect.Token("t1"), Kite.readRedirect("http://127.0.0.1/cb?request_token=t1", "http://127.0.0.1:80/cb"))
        assertEquals(Kite.Redirect.Token("t1"), Kite.readRedirect("https://x.in:443/?request_token=t1", "https://X.IN"))
        assertEquals(Kite.Redirect.NotOurs, Kite.readRedirect("http://127.0.0.1:8788/?request_token=t1", "http://127.0.0.1:8787/"))
        assertEquals(Kite.Redirect.NotOurs, Kite.readRedirect("https://x.in/other?request_token=t1", "https://x.in/cb"))
        assertEquals(Kite.Redirect.Token("t2"), Kite.readRedirect("https://x.in?request_token=t2", "https://x.in"))
        assertEquals(Kite.Redirect.Refused("the redirect carried no request_token"), Kite.readRedirect("https://x.in/?request_token=%20&status=success", "https://x.in/"))
        assertEquals(Kite.Redirect.Refused("Zerodha returned status 'error'"), Kite.readRedirect("https://x.in/?status=error&request_token=t", "https://x.in/"))
        assertEquals(Kite.Redirect.NotOurs, Kite.readRedirect("mailto:someone@x.in", "https://x.in/"))
        assertEquals(emptyMap(), Kite.query(null))
        assertEquals(mapOf("a" to "1=2", "b" to ""), Kite.query("a=1%3D2&flag&b="))
        assertEquals("a=1&c=x+y", Kite.form(listOf("a" to "1", "b" to null, "c" to "x y")))
    }

    @Test fun `token expiry boundary is 06_00 IST`() {
        val six = ZonedDateTime.of(2026, 9, 24, 6, 0, 0, 0, IST)
        assertEquals(six.plusDays(1), Kite.expiresAt(six))
        assertEquals(six, Kite.expiresAt(six.minusNanos(1)))
        // A UTC clock at 00:15 is 05:45 IST the same day.
        assertEquals(six, Kite.expiresAt(ZonedDateTime.of(2026, 9, 24, 0, 15, 0, 0, java.time.ZoneOffset.UTC)))
    }

    // ------------------------------------------------------------- instruments

    private val dump = """
        instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange

        101,1,NIFTY26SEP24500PE,NIFTY,0,2026-09-29,24500,0.05,65,PE,NFO-OPT,NFO
        102,1,NIFTY26SEP24500CE,NIFTY,0,2026-09-29,24500,,65.0,CE,NFO-OPT,NFO
        103,1,NIFTY26SEPFUT,NIFTY,0,2026-09-29,0,0.05,65,FUT,NFO-FUT,NFO
        104,1,BANKNIFTY26SEP50000PE,BANKNIFTY,0,2026-09-29,50000,0.05,30,PE,NFO-OPT,NFO
        105,1,NIFTY26SEP24600PE,NIFTY,0,2026-09-29,24600,0.05,65,PE,BFO-OPT,NFO
        106,1,"GOLD,M26OCTFUT",GOLDM,0,2026-10-05,0,1,10,FUT,MCX-FUT,MCX
        ,1,USDINR26SEPFUT,USDINR,0,2026-09-26,0,0.0025,1000,FUT,CDS-FUT,CDS
        107,1,RELIANCE,RELIANCE,0,,0,0,0,EQ,NSE,NSE
        108,1,TCS,TCS,0,,0,-1,abc,EQ,NSE,NSE
    """.trimIndent()

    @Test fun `parseInstruments keeps only the underlyings' NFO options`() {
        val got = Kite.parseInstruments(dump.lineSequence(), setOf("NIFTY"))
        assertEquals(listOf("NIFTY26SEP24500PE", "NIFTY26SEP24500CE"), got.map { it.tradingSymbol })
        assertEquals(Right.PE, got[0].right); assertEquals(Right.CE, got[1].right)
        assertEquals(0.05, got[1].tickSize, "blank tick defaults")
        assertEquals(65, got[1].lotSize); assertEquals(LocalDate.of(2026, 9, 29), got[0].expiry); assertEquals(101L, got[0].token)
        assertEquals(24500.0, got[0].strike); assertEquals("NIFTY", got[0].name)
        assertEquals(emptyList(), Kite.parseInstruments(emptySequence(), setOf("NIFTY")))
    }

    @Test fun `lookup reads lot and tick for any exchange`() {
        val want = setOf("GOLD,M26OCTFUT", "USDINR26SEPFUT", "RELIANCE", "TCS", "NIFTY26SEP24500PE", "MISSING")
        val got = Kite.lookup(dump.lineSequence(), want)
        assertEquals(Kite.Spec("MCX", "GOLD,M26OCTFUT", 106, 1, 1.0), got["GOLD,M26OCTFUT"], "MCX is lot-quoted; quoted comma kept")
        assertEquals(Kite.Spec("CDS", "USDINR26SEPFUT", 0, 1, 0.0025), got["USDINR26SEPFUT"], "missing token reads 0")
        assertEquals(Kite.Spec("NSE", "RELIANCE", 107, 1, 0.05), got["RELIANCE"], "lot 0 -> 1, tick 0 -> 0.05")
        assertEquals(Kite.Spec("NSE", "TCS", 108, 1, 0.05), got["TCS"], "unparseable lot -> 1, negative tick -> 0.05")
        assertEquals(65, got.getValue("NIFTY26SEP24500PE").lotSize)
        assertNull(got["MISSING"])
        // Stops early once every symbol is found; a header missing a column reads it blank.
        val early = Kite.lookup(sequenceOf("tradingsymbol,exchange", "A,NSE", "B,NSE"), setOf("A"))
        assertEquals(Kite.Spec("NSE", "A", 0, 1, 0.05), early["A"])
        assertEquals(setOf("A"), early.keys)
    }

    // ------------------------------------------------------------- order bodies

    @Test fun `form bodies per order type`() {
        val l = order(tag = "iraalgo-2026/strategy#1-with-long-tag")
        assertEquals("tradingsymbol=NIFTY26SEP24500PE&exchange=NFO&transaction_type=SELL&order_type=LIMIT&quantity=65&product=NRML&price=100.00&validity=DAY&tag=iraalgo2026strategy1",
            l.formBody())
        assertTrue(order(type = "MARKET", price = null).formBody().contains("market_protection=-1"))
        val sl = order(type = "SL", price = 99.0, trigger = 100.0).formBody()
        assertTrue(sl.contains("price=99.00") && sl.contains("trigger_price=100.00") && !sl.contains("market_protection"))
        val slm = order(type = "SL-M", price = null, trigger = 100.0).formBody()
        assertTrue(slm.contains("trigger_price=100.00") && slm.contains("market_protection=-1") && !slm.contains("&price="))
        assertEquals(0, order(lot = 0).lots); assertEquals(2, order(qty = 130).lots)
    }

    @Test fun `money keeps sub-paisa ticks and non-finite values print`() {
        assertEquals("100.00", Kite.money(100.0)); assertEquals("0.0025", Kite.money(0.0025))
        assertEquals("1.2346", Kite.money(1.23456)); assertEquals("0.10", Kite.money(0.1))
        assertEquals("NaN", Kite.money(Double.NaN)); assertEquals("Infinity", Kite.money(Double.POSITIVE_INFINITY))
        assertEquals("-Infinity", Kite.money(Double.NEGATIVE_INFINITY))
    }

    @Test fun `basket json prices only priced orders`() {
        val j = Kite.basketJson(listOf(order(), order(type = "MARKET", price = 5.0), order(type = "SL", price = 99.0, trigger = 100.0),
            order(type = "SL-M", price = null, trigger = null), order(type = "LIMIT", price = null), order(sym = "A\"B\\C")))
        assertTrue(j.startsWith("[{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY26SEP24500PE\",\"transaction_type\":\"SELL\",\"variety\":\"regular\",\"product\":\"NRML\",\"order_type\":\"LIMIT\",\"quantity\":65,\"price\":100.00,\"trigger_price\":0}"), j)
        assertTrue(j.contains("\"order_type\":\"MARKET\",\"quantity\":65,\"price\":0,\"trigger_price\":0"))
        assertTrue(j.contains("\"order_type\":\"SL\",\"quantity\":65,\"price\":99.00,\"trigger_price\":100.00"))
        assertTrue(j.contains("\"order_type\":\"SL-M\",\"quantity\":65,\"price\":0,\"trigger_price\":0"))
        assertTrue(j.contains("\"tradingsymbol\":\"A\\\"B\\\\C\""))
        assertEquals("[]", Kite.basketJson(emptyList()))
    }

    @Test fun `modify body per order type`() {
        assertEquals("quantity=65&order_type=LIMIT&price=10.50&validity=DAY", Kite.modifyBody(65, "LIMIT", 10.5, null))
        assertEquals("quantity=65&order_type=SL&price=10.50&trigger_price=11.00&validity=DAY", Kite.modifyBody(65, "SL", 10.5, 11.0))
        assertEquals("quantity=65&order_type=SL-M&trigger_price=11.00&validity=DAY", Kite.modifyBody(65, "SL-M", null, 11.0))
        assertEquals("quantity=65&order_type=MARKET&validity=DAY", Kite.modifyBody(65, "MARKET", null, null))
    }

    // ------------------------------------------------------------- refusals

    @Test fun `a clean order has no refusals`() {
        assertEquals(emptyList(), ref(order()))
        assertEquals(emptyList(), ref(order(side = Kite.Side.BUY, qty = 130), sent = 3, hold = true))
        assertEquals(emptyList(), ref(order(sym = "RELIANCE", exchange = "NSE", product = "CNC", lot = 1, qty = 5, price = 2500.0, type = "LIMIT").copy(price = 99.95)))
    }

    @Test fun `identity, quantity and whole-lot refusals`() {
        assertTrue("no trading symbol" in ref(order(sym = " ")))
        assertTrue(ref(order(qty = 0)).contains("quantity must be positive, not 0"))
        assertTrue(ref(order(qty = -65)).contains("quantity must be positive, not -65"))
        assertTrue(ref(order(qty = 100)).contains("quantity 100 is not a whole number of lots of 65"))
        assertTrue(ref(order(lot = 0)).contains("quantity 65 is not a whole number of lots of 0"))
        assertTrue(ref(order(lot = -65)).any { it.startsWith("quantity 65 is not a whole number") })
    }

    @Test fun `new-risk caps apply to entries but never trap an exit`() {
        val big = order(qty = 195, price = 10.0)
        assertTrue(ref(big).any { it.startsWith("3 lots exceeds the 2-lot cap") })
        assertEquals(emptyList(), ref(big, exit = true))
        assertTrue(ref(order(), sent = 4).contains("daily order limit reached (4)"))
        assertEquals(emptyList(), ref(order(), sent = 99, exit = true))
        // The lot cap is a derivatives rule only.
        assertEquals(emptyList(), ref(order(sym = "ITC", exchange = "NSE", product = "MIS", lot = 1, qty = 500, price = 400.0)))
        // Value caps: priced orders by their price, market orders by the reference price.
        assertTrue(ref(order(price = 4000.0, qty = 130)).any { it.startsWith("order value Rs 520,000 exceeds the Rs 500,000 cap") })
        assertEquals(emptyList(), ref(order(price = 4000.0, qty = 130), exit = true))
        val mkt = order(type = "MARKET", price = null, qty = 130)
        assertTrue(ref(mkt, refPrice = 4000.0).any { it.startsWith("order value about Rs 520,000 (at the last price)") })
        assertEquals(emptyList(), ref(mkt, refPrice = 4000.0, exit = true))
        assertEquals(emptyList(), ref(mkt, refPrice = null))
        assertEquals(emptyList(), ref(mkt, refPrice = 0.0))
        assertEquals(emptyList(), ref(mkt, refPrice = 3000.0))
    }

    @Test fun `freeze quantity is refused even on an exit`() {
        val over = order(qty = 1820, price = 1.0)
        val r = ref(over, exit = true)
        assertEquals(listOf("quantity 1820 exceeds the exchange freeze quantity of 1800 for NIFTY; split it into orders of at most 1800"), r)
        assertEquals(emptyList(), ref(order(qty = 1755, price = 1.0), exit = true))
        assertTrue(ref(order(sym = "SENSEX26SEP80000PE", exchange = "BFO", lot = 20, qty = 1020, price = 1.0), exit = true).single().contains("freeze quantity of 1000 for SENSEX"))
        assertEquals(emptyList(), ref(order(sym = "NIFTY26SEPFUT", exchange = "MCX", lot = 65, qty = 1950, price = 1.0), exit = true))
    }

    @Test fun `order type, price, trigger and tick refusals`() {
        assertTrue(ref(order(type = "ICEBERG")).contains("order type ICEBERG is not supported"))
        assertTrue(ref(order(price = null)).contains("a LIMIT order needs a positive price"))
        assertTrue(ref(order(price = 0.0)).contains("a LIMIT order needs a positive price"))
        assertTrue(ref(order(price = -1.0)).contains("a LIMIT order needs a positive price"))
        assertTrue(ref(order(price = 100.03)).contains("price 100.03 is not on the 0.05 tick"))
        assertEquals(emptyList(), ref(order(price = 100.05)), "float noise within 1e-6 of a tick is on it")
        assertTrue(ref(order(type = "SL-M", price = null, trigger = null)).contains("a SL-M order needs a positive trigger price"))
        assertTrue(ref(order(type = "SL-M", price = null, trigger = -2.0)).contains("a SL-M order needs a positive trigger price"))
        assertTrue(ref(order(type = "SL-M", price = null, trigger = 50.01)).contains("trigger 50.01 is not on the 0.05 tick"))
        assertEquals(emptyList(), ref(order(type = "SL-M", price = null, trigger = 50.0)))
        // Stop-limit legality by side.
        assertTrue(ref(order(type = "SL", side = Kite.Side.BUY, price = 99.0, trigger = 100.0)).single().startsWith("a stop-loss BUY needs its limit (99.00) at or above the trigger (100.00)"))
        assertEquals(emptyList(), ref(order(type = "SL", side = Kite.Side.BUY, price = 100.0, trigger = 100.0)))
        assertTrue(ref(order(type = "SL", side = Kite.Side.SELL, price = 101.0, trigger = 100.0)).single().startsWith("a stop-loss SELL needs its limit (101.00) at or below the trigger (100.00)"))
        assertEquals(emptyList(), ref(order(type = "SL", side = Kite.Side.SELL, price = 99.0, trigger = 100.0)))
        // An SL with no usable price is refused for the price, not the side rule.
        assertEquals(listOf("a SL order needs a positive price"), ref(order(type = "SL", price = null, trigger = 100.0)))
    }

    @Test fun `product refusals`() {
        assertTrue(ref(order(product = "CO")).contains("product CO is not supported"))
        assertTrue(ref(order(product = "CNC")).contains("CNC is for delivery equity; use NRML or MIS for NFO"))
        assertTrue(ref(order(product = "NRML", exchange = "NSE", sym = "ITC", lot = 1, qty = 1)).contains("NRML is for F&O; use CNC or MIS for NSE"))
        assertTrue(ref(order(product = "MIS"), hold = true).single().startsWith("MIS is squared off by the broker"))
        assertEquals(emptyList(), ref(order(product = "MIS"), hold = false))
    }

    @Ignore("BUG: Kite.refusals lets a NaN price or trigger through - p <= 0 is false for NaN, and onGrid's abs(NaN - round(NaN)) > 1e-6 is false - so a LIMIT/SL order priced NaN (e.g. from \"NaN\".toDouble() on user input) passes every gate and is sent as price=NaN")
    @Test fun `BUG - a non-finite price or trigger is refused`() {
        assertTrue(ref(order(price = Double.NaN)).isNotEmpty(), "NaN price must be refused")
        assertTrue(ref(order(type = "SL-M", price = null, trigger = Double.NaN)).isNotEmpty(), "NaN trigger must be refused")
    }

    @Test fun `infinite prices are refused by the grid and value checks`() {
        assertTrue(ref(order(price = Double.POSITIVE_INFINITY)).isNotEmpty())
        assertTrue(ref(order(type = "SL-M", price = null, trigger = Double.POSITIVE_INFINITY)).isNotEmpty())
    }

    // ------------------------------------------------------------- ticks, square-off, slices

    @Test fun `onTick rounds toward the fill`() {
        assertEquals(100.05, Kite.onTick(100.07, 0.05, Kite.Side.SELL))
        assertEquals(100.1, Kite.onTick(100.07, 0.05, Kite.Side.BUY))
        assertEquals(100.05, Kite.onTick(100.05, 0.05, Kite.Side.BUY), "on the tick stays")
        assertEquals(100.05, Kite.onTick(100.05, 0.05, Kite.Side.SELL))
        assertEquals(83.2525, Kite.onTick(83.2530, 0.0025, Kite.Side.SELL))
        assertEquals(0.0, Kite.onTick(0.0, 0.05, Kite.Side.BUY))
    }

    private val spec = Kite.Spec("NFO", "NIFTY26SEP24500PE", 101, 65, 0.05)

    @Test fun `square off closes the whole position at the side that fills`() {
        assertNull(Kite.squareOff(spec, "NRML", 0, 1.0, 2.0, 1.5))
        val long = Kite.squareOff(spec, "NRML", 130, 99.97, 100.2, 100.0)!!
        assertEquals(Kite.Side.SELL, long.side); assertEquals(130, long.quantity); assertEquals(99.95, long.price); assertEquals("LIMIT", long.orderType)
        val short = Kite.squareOff(spec, "MIS", -65, 99.9, 100.02, 100.0)!!
        assertEquals(Kite.Side.BUY, short.side); assertEquals(65, short.quantity); assertEquals(100.05, short.price); assertEquals("MIS", short.product)
        assertEquals(100.0, Kite.squareOff(spec, "NRML", 65, null, null, 100.0)!!.price, "no bid: last")
        assertEquals(100.0, Kite.squareOff(spec, "NRML", 65, 0.0, 5.0, 100.0)!!.price, "zero bid: last")
        assertEquals(100.0, Kite.squareOff(spec, "NRML", -65, 5.0, -1.0, 100.0)!!.price, "negative ask: last")
    }

    @Test fun `square-off slices obey the freeze quantity in whole lots`() {
        assertEquals(emptyList(), Kite.squareOffSlices(spec, "NRML", 0, 1.0, 1.0, 1.0))
        assertEquals(listOf(1755, 1755, 390), Kite.squareOffSlices(spec, "NRML", 3900, 1.0, 1.0, 1.0).map { it.quantity })
        assertEquals(listOf(1800), Kite.squareOffSlices(spec, "NRML", -1800, 1.0, 1.0, 1.0).map { it.quantity })
        val bank = spec.copy(tradingSymbol = "BANKNIFTY26SEP50000PE", lotSize = 30)
        assertEquals(listOf(900, 300), Kite.squareOffSlices(bank, "NRML", 1200, 1.0, 1.0, 1.0).map { it.quantity })
        assertEquals(listOf(5000), Kite.squareOffSlices(spec.copy(exchange = "NSE", tradingSymbol = "ITC"), "MIS", 5000, 1.0, 1.0, 1.0).map { it.quantity })
    }

    @Test fun `slices edge cases`() {
        assertEquals(emptyList(), Kite.slices(0, 65, 1800)); assertEquals(emptyList(), Kite.slices(-5, 65, 1800))
        assertEquals(listOf(5000), Kite.slices(5000, 65, null))
        assertEquals(listOf(1800), Kite.slices(1800, 65, 1800))
        assertEquals(listOf(3, 3, 1), Kite.slices(7, 0, 3), "lot 0 is read as 1")
        assertEquals(listOf(100, 100, 50), Kite.slices(250, 100, 60), "freeze below one lot: one lot per order")
    }

    @Test fun `underlying and freeze lookup never confuse neighbours`() {
        assertEquals("BANKNIFTY", Kite.underlyingOf("BANKNIFTY26SEP50000PE"))
        assertEquals("NIFTY", Kite.underlyingOf("NIFTY26SEP24500PE"))
        assertEquals("NIFTYNXT50", Kite.underlyingOf("NIFTYNXT5026SEP70000CE"))
        assertEquals("MIDCPNIFTY", Kite.underlyingOf("MIDCPNIFTY26SEP13000CE"))
        assertNull(Kite.underlyingOf("NIFTY")); assertNull(Kite.underlyingOf("NIFTYIT26SEP1CE")); assertNull(Kite.underlyingOf(""))
        assertEquals(900, Kite.freezeQuantity("BFO", "BANKEX26SEP60000CE"))
        assertEquals(2800, Kite.freezeQuantity("NFO", "MIDCPNIFTY26SEP13000CE"))
        assertNull(Kite.freezeQuantity("NSE", "NIFTY26SEP24500PE"))
        assertNull(Kite.freezeQuantity("NFO", "RELIANCE26SEPFUT"))
    }

    // ------------------------------------------------------------- GTT

    @Test fun `protect refuses what Zerodha would refuse`() {
        assertEquals(listOf("nothing is open"), Kite.protect(spec, "NRML", 0, 100.0, 90.0, null).second)
        assertEquals(listOf("give a stop, a target, or both"), Kite.protect(spec, "NRML", 65, 100.0, null, null).second)
        assertEquals(listOf("no last price"), Kite.protect(spec, "NRML", 65, 0.0, 90.0, null).second)
        assertEquals(listOf("no last price"), Kite.protect(spec, "NRML", 65, -1.0, 90.0, null).second)
        assertEquals(listOf("the stop must be below the last price 100.00"), Kite.protect(spec, "NRML", 65, 100.0, 105.0, null).second)
        assertEquals(listOf("the stop must be above the last price 100.00"), Kite.protect(spec, "NRML", -65, 100.0, 90.0, null).second)
        assertEquals(listOf("the target must be above the last price 100.00"), Kite.protect(spec, "NRML", 65, 100.0, null, 99.0).second)
        assertEquals(listOf("the target must be below the last price 100.00"), Kite.protect(spec, "NRML", -65, 100.0, null, 101.0).second)
        assertEquals(listOf("the stop is within 0.25% of the last price; Zerodha refuses that"), Kite.protect(spec, "NRML", 65, 100.0, 99.8, null).second)
        assertEquals(listOf("the target is within 0.25% of the last price; Zerodha refuses that"), Kite.protect(spec, "NRML", -65, 100.0, null, 99.8).second)
        val both = Kite.protect(spec, "NRML", 65, 100.0, 100.0, 100.0).second
        assertEquals(4, both.size)
    }

    @Test fun `protect builds single and two-leg GTTs with slippage on the filling side`() {
        val (long, e1) = Kite.protect(spec, "NRML", 130, 100.0, 90.02, 120.0)
        assertEquals(emptyList(), e1)
        val g = long!!
        assertEquals("two-leg", g.type)
        assertEquals(listOf(90.0, 120.0), g.triggers)
        assertEquals(listOf(Kite.Side.SELL, Kite.Side.SELL), g.orders.map { it.side })
        assertEquals(listOf(85.5, 114.0), g.orders.map { it.price })
        assertEquals(listOf(130, 130), g.orders.map { it.quantity })
        val body = g.formBody()
        assertTrue(body.startsWith("type=two-leg&condition="), body)
        val decoded = java.net.URLDecoder.decode(body, "UTF-8")
        assertTrue(decoded.contains("\"trigger_values\":[90.00,120.00],\"last_price\":100.00"), decoded)
        assertTrue(decoded.contains("{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY26SEP24500PE\",\"transaction_type\":\"SELL\",\"quantity\":130,\"order_type\":\"LIMIT\",\"product\":\"NRML\",\"price\":85.50}"), decoded)

        val (short, _) = Kite.protect(spec, "MIS", -65, 100.0, 110.0, null, slippage = 0.1)
        assertEquals("single", short!!.type)
        assertEquals(Kite.Side.BUY, short.orders.single().side)
        assertEquals(121.0, short.orders.single().price)
        val (tgtOnly, _) = Kite.protect(spec, "NRML", -65, 100.0, null, 80.0)
        assertEquals(listOf(80.0), tgtOnly!!.triggers); assertEquals(84.0, tgtOnly.orders.single().price)
        // A trigger that slips below one tick is floored at the tick.
        val cheap = Kite.protect(spec, "NRML", 65, 0.2, 0.05, null, slippage = 0.99).first!!
        assertEquals(0.05, cheap.orders.single().price)
    }

    // ------------------------------------------------------------- ticket legs

    private fun ticket(wing: Double? = null, debit: Double? = null) = Live.Ticket(
        LocalDate.of(2026, 9, 29), "NIFTY", LocalDate.of(2026, 9, 29), "SELL", "PE", 24500.0, 65, 1, 65,
        20.0, 24700.0, 24480.0, 1e5, null, wing, debit,
    )
    private val shortI = Kite.Instrument(1, "NIFTY26SEP24500PE", "NIFTY", LocalDate.of(2026, 9, 29), 24500.0, 65, Right.PE, 0.05)
    private val wingI = shortI.copy(token = 2, tradingSymbol = "NIFTY26SEP24300PE", strike = 24300.0)

    @Test fun `ticket legs buy the wing first and price on the tick`() {
        val naked = Kite.legsFor(ticket(), shortI, null, "NRML", 20.03, null)
        assertEquals(1, naked.size); assertEquals(Kite.Side.SELL, naked[0].side); assertEquals(20.0, naked[0].price)
        val hedged = Kite.legsFor(ticket(24300.0, 8.0), shortI, wingI, "NRML", 20.0, 8.01)
        assertEquals(listOf(Kite.Side.BUY, Kite.Side.SELL), hedged.map { it.side })
        assertEquals(8.05, hedged[0].price); assertEquals("NIFTY26SEP24300PE", hedged[0].tradingSymbol)
        assertEquals(8.0, Kite.legsFor(ticket(24300.0, 8.0), shortI, wingI, "NRML", 20.0, null)[0].price, "the ticket's own debit")
        assertEquals(0.0, Kite.legsFor(ticket(24300.0, null), shortI, wingI, "NRML", 20.0, null)[0].price)
        assertTrue(Kite.refusals(Kite.legsFor(ticket(24300.0, null), shortI, wingI, "NRML", 20.0, null)[0], lim, 0, true)
            .contains("a LIMIT order needs a positive price"), "a wing with no price never goes out")
        assertEquals("hedged ticket but no wing instrument",
            assertFailsWith<IllegalArgumentException> { Kite.legsFor(ticket(24300.0, 8.0), shortI, null, "NRML", 20.0, 8.0) }.message)
    }
}
