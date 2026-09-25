package com.optionslab.engine

import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KiteTest {
    @Test fun `checksum matches the PC's hashlib sha256`() {
        // python: hashlib.sha256(('abc123key'+'reqTOKEN789'+'s3cr3tXYZ').encode()).hexdigest()
        assertEquals("8b364c7c20148033e347109abdfb73c4578c97b12d7b2434efb38586047702e3",
            Kite.checksum("abc123key", "reqTOKEN789", "s3cr3tXYZ"))
    }

    @Test fun `the registered redirect yields its request token`() {
        val r = Kite.readRedirect("https://www.christianmarriageregistrar.in/?action=login&type=login&status=success&request_token=AbC123xyz",
            "https://www.christianmarriageregistrar.in/")
        assertEquals(Kite.Redirect.Token("AbC123xyz"), r)
        assertEquals(Kite.Redirect.Token("tok9"), Kite.readRedirect("http://127.0.0.1:8787/?request_token=tok9&status=success", "http://127.0.0.1:8787/"))
    }

    @Test fun `a lookalike page is not mistaken for the callback`() {
        val reg = "https://www.christianmarriageregistrar.in/"
        assertEquals(Kite.Redirect.NotOurs, Kite.readRedirect("https://evil.example/?request_token=abc&x=https://www.christianmarriageregistrar.in/", reg))
        assertEquals(Kite.Redirect.NotOurs, Kite.readRedirect("https://www.christianmarriageregistrar.in.evil.example/?request_token=abc", reg))
        assertEquals(Kite.Redirect.NotOurs, Kite.readRedirect("http://www.christianmarriageregistrar.in/?request_token=abc", reg))
        assertEquals(Kite.Redirect.NotOurs, Kite.readRedirect("https://kite.zerodha.com/connect/login?v=3&api_key=x", reg))
    }

    @Test fun `a failed or tampered callback is refused, not accepted`() {
        val reg = "https://example.in/cb"
        assertIs<Kite.Redirect.Refused>(Kite.readRedirect("https://example.in/cb?status=cancelled", reg))
        assertIs<Kite.Redirect.Refused>(Kite.readRedirect("https://example.in/cb?status=success", reg))
        assertIs<Kite.Redirect.Refused>(Kite.readRedirect("https://example.in/cb?request_token=a%27%3Bdrop&status=success", reg))
    }

    @Test fun `a token expires at the next 06_00 IST`() {
        val evening = ZonedDateTime.of(2026, 9, 24, 20, 0, 0, 0, IST)
        assertEquals(ZonedDateTime.of(2026, 9, 25, 6, 0, 0, 0, IST), Kite.expiresAt(evening))
        val early = ZonedDateTime.of(2026, 9, 25, 5, 30, 0, 0, IST)
        assertEquals(ZonedDateTime.of(2026, 9, 25, 6, 0, 0, 0, IST), Kite.expiresAt(early))
    }

    @Test fun `instruments keep only the underlying's options`() {
        val csv = """
            instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
            12345,48,NIFTY26SEP24500PE,"NIFTY",0,2026-09-29,24500,0.05,65,PE,NFO-OPT,NFO
            12346,49,NIFTY26SEPFUT,"NIFTY",0,2026-09-29,0,0.1,65,FUT,NFO-FUT,NFO
            12347,50,FINNIFTY26SEP24500PE,"FINNIFTY",0,2026-09-29,24500,0.05,65,PE,NFO-OPT,NFO
        """.trimIndent()
        val got = Kite.parseInstruments(csv.lineSequence(), setOf("NIFTY"))
        assertEquals(1, got.size)
        assertEquals(Kite.Instrument(12345, "NIFTY26SEP24500PE", "NIFTY", LocalDate.of(2026, 9, 29), 24500.0, 65, Right.PE, 0.05), got[0])
    }

    private fun order(qty: Int = 65, price: Double? = 4.85, product: String = "NRML", type: String = "LIMIT") =
        Kite.Order("NIFTY26SEP24500PE", Kite.Side.SELL, qty, 65, product, type, price)

    @Test fun `a sound order passes every gate`() {
        assertEquals(emptyList(), Kite.refusals(order(), Kite.Limits(), 0, holdToSettlement = true))
    }

    @Test fun `the gates refuse what the PC adapter refused, and more`() {
        val l = Kite.Limits(maxOrdersPerDay = 2, maxLotsPerOrder = 2, maxOrderValue = 1_000.0)
        assertTrue(Kite.refusals(order(qty = 100), l, 0, true).any { "whole number of lots" in it })
        assertTrue(Kite.refusals(order(qty = 195), l, 0, true).any { "lot cap" in it })
        assertTrue(Kite.refusals(order(), l, 2, true).any { "daily order limit" in it })
        assertTrue(Kite.refusals(order(price = 4.87), l, 0, true).any { "tick" in it })
        assertTrue(Kite.refusals(order(price = 40.0), l, 0, true).any { "exceeds" in it })
        assertTrue(Kite.refusals(order(price = null), l, 0, true).any { "needs a positive price" in it })
        assertTrue(Kite.refusals(order(product = "MIS"), l, 0, true).any { "must be NRML" in it })
        assertEquals(emptyList(), Kite.refusals(order(product = "MIS"), Kite.Limits(), 0, holdToSettlement = false))
    }

    @Test fun `the order body is exactly the Kite form`() {
        assertEquals("tradingsymbol=NIFTY26SEP24500PE&exchange=NFO&transaction_type=SELL&order_type=LIMIT&quantity=65&product=NRML&price=4.85&validity=DAY&tag=iraalgo",
            order().formBody())
        assertTrue(order(type = "MARKET", price = null).formBody().contains("market_protection=-1"))
    }

    @Test fun `prices land on the tick toward a fill`() {
        assertEquals(4.85, Kite.onTick(4.87, 0.05, Kite.Side.SELL))
        assertEquals(4.90, Kite.onTick(4.87, 0.05, Kite.Side.BUY))
        assertEquals(4.85, Kite.onTick(4.85, 0.05, Kite.Side.SELL))
    }

    @Test fun `a hedged ticket buys its wing before it sells the put`() {
        val tk = Live.Ticket(LocalDate.of(2026, 9, 29), "NIFTY", LocalDate.of(2026, 9, 29), "SELL", "PE", 24500.0, 65, 1, 65,
            credit = 3.0, forward = 24700.0, breakeven = 24497.0, margin = 0.0, maxLoss = 0.0, wingStrike = 24300.0, wingDebit = 1.5)
        val short = Kite.Instrument(1, "NIFTY26SEP24500PE", "NIFTY", tk.expiry, 24500.0, 65, Right.PE, 0.05)
        val wing = Kite.Instrument(2, "NIFTY26SEP24300PE", "NIFTY", tk.expiry, 24300.0, 65, Right.PE, 0.05)
        val legs = Kite.legsFor(tk, short, wing, "NRML", 4.5, 1.5)
        assertEquals(listOf(Kite.Side.BUY, Kite.Side.SELL), legs.map { it.side })
        assertEquals("NIFTY26SEP24300PE", legs[0].tradingSymbol)
    }

    @Test fun `the lot cap applies to derivatives, not to a 100-share equity order`() {
        val eq = Kite.Order("INFY", Kite.Side.BUY, 100, 1, "CNC", "LIMIT", 1500.0, exchange = "NSE")
        assertEquals(emptyList(), Kite.refusals(eq, Kite.Limits(maxOrderValue = 1_000_000.0), 0, holdToSettlement = false))
        assertTrue(Kite.refusals(eq.copy(product = "NRML"), Kite.Limits(maxOrderValue = 1_000_000.0), 0, false).any { "NRML is for F&O" in it })
        assertTrue(Kite.refusals(order(product = "CNC"), Kite.Limits(), 0, false).any { "CNC is for delivery" in it })
    }

    @Test fun `stop-loss orders carry their trigger and are checked`() {
        val sl = Kite.Order("NIFTY26SEP24500PE", Kite.Side.BUY, 65, 65, "NRML", "SL", 12.0, triggerPrice = 11.5)
        assertEquals(emptyList(), Kite.refusals(sl, Kite.Limits(), 0, false))
        assertTrue("trigger_price=11.50" in sl.formBody() && "price=12.00" in sl.formBody())
        val backwards = sl.copy(price = 11.0)
        assertTrue(Kite.refusals(backwards, Kite.Limits(), 0, false).any { "at or above the trigger" in it })
        val slm = sl.copy(orderType = "SL-M", price = null)
        assertEquals(emptyList(), Kite.refusals(slm, Kite.Limits(), 0, false))
        assertTrue("market_protection=-1" in slm.formBody() && "&price=" !in slm.formBody())
        assertTrue(Kite.refusals(slm.copy(triggerPrice = null), Kite.Limits(), 0, false).any { "trigger" in it })
    }

    @Test fun `a modify sends only what may change`() {
        assertEquals("quantity=130&order_type=LIMIT&price=5.10&validity=DAY", Kite.modifyBody(130, "LIMIT", 5.1, null))
        assertEquals("quantity=65&order_type=SL-M&trigger_price=9.00&validity=DAY", Kite.modifyBody(65, "SL-M", null, 9.0))
    }

    @Test fun `an exit is never trapped by the caps that limit new risk`() {
        val spec = Kite.Spec("NFO", "NIFTY26SEP24500PE", 1L, 65, 0.05)
        val out = Kite.squareOff(spec, "NRML", -260, bid = 4.8, ask = 4.93, last = 4.85)!!
        assertEquals(Kite.Side.BUY, out.side)
        assertEquals(260, out.quantity)
        assertEquals(4.95, out.price)
        val limits = Kite.Limits(maxOrdersPerDay = 4, maxLotsPerOrder = 2)
        assertTrue(Kite.refusals(out, limits, sentToday = 9, holdToSettlement = false).isNotEmpty())
        assertEquals(emptyList(), Kite.refusals(out, limits, sentToday = 9, holdToSettlement = false, exit = true))
        val long = Kite.squareOff(spec, "NRML", 65, bid = 4.8, ask = 4.93, last = 4.85)!!
        assertEquals(Kite.Side.SELL to 4.8, long.side to long.price)
        assertEquals(null, Kite.squareOff(spec, "NRML", 0, null, null, 1.0))
    }

    @Test fun `the lookup finds lot and tick on any exchange, in lots where Kite quotes lots`() {
        val csv = """
            instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
            408065,1594,INFY,"INFOSYS",0,,0,0.1,1,EQ,NSE,NSE
            12345,48,NIFTY26SEP24500PE,"NIFTY",0,2026-09-29,24500,0.05,65,PE,NFO-OPT,NFO
            5001,77,CRUDEOIL26OCTFUT,"CRUDEOIL",0,2026-10-19,0,1,100,FUT,MCX-FUT,MCX
        """.trimIndent()
        val m = Kite.lookup(csv.lineSequence(), setOf("INFY", "CRUDEOIL26OCTFUT", "NIFTY26SEP24500PE"))
        assertEquals(Kite.Spec("NSE", "INFY", 408065, 1, 0.1), m["INFY"])
        assertEquals(65, m["NIFTY26SEP24500PE"]!!.lotSize)
        assertEquals(1, m["CRUDEOIL26OCTFUT"]!!.lotSize)
    }

    @Test fun `a MARKET order is held to the value cap at its last price`() {
        val mkt = Kite.Order("NIFTY26SEP24500PE", Kite.Side.SELL, 130, 65, "NRML", "MARKET", null)
        val cap = Kite.Limits(maxOrderValue = 10_000.0)
        assertTrue(Kite.refusals(mkt, cap, 0, false, refPrice = 100.0).any { "cap" in it })
        assertEquals(emptyList(), Kite.refusals(mkt, cap, 0, false, refPrice = 50.0))
        assertEquals(emptyList(), Kite.refusals(mkt, cap, 0, false, exit = true, refPrice = 100.0))
    }

    @Test fun `the basket margin body is Kite's JSON`() {
        val legs = listOf(
            Kite.Order("NIFTY26SEP24300PE", Kite.Side.BUY, 65, 65, "NRML", "LIMIT", 2.35),
            Kite.Order("NIFTY26SEP24500PE", Kite.Side.SELL, 65, 65, "NRML", "MARKET", null),
        )
        assertEquals(
            """[{"exchange":"NFO","tradingsymbol":"NIFTY26SEP24300PE","transaction_type":"BUY","variety":"regular","product":"NRML","order_type":"LIMIT","quantity":65,"price":2.35,"trigger_price":0},""" +
            """{"exchange":"NFO","tradingsymbol":"NIFTY26SEP24500PE","transaction_type":"SELL","variety":"regular","product":"NRML","order_type":"MARKET","quantity":65,"price":0,"trigger_price":0}]""",
            Kite.basketJson(legs))
    }

    @Test fun `a short option is protected with a stop above and a target below, OCO`() {
        val spec = Kite.Spec("NFO", "NIFTY26SEP24500PE", 1L, 65, 0.05)
        val (g, why) = Kite.protect(spec, "NRML", -130, lastPrice = 40.0, stop = 60.0, target = 20.0)
        assertEquals(emptyList(), why)
        g!!
        assertEquals("two-leg", g.type)
        assertEquals(listOf(20.0, 60.0), g.triggers)
        assertTrue(g.orders.all { it.side == Kite.Side.BUY && it.quantity == 130 && it.orderType == "LIMIT" })
        assertEquals(listOf(21.0, 63.0), g.orders.map { it.price })
        val body = java.net.URLDecoder.decode(g.formBody(), "UTF-8")
        assertTrue("type=two-leg" in body && "\"trigger_values\":[20.00,60.00]" in body && "\"last_price\":40.00" in body, body)
        assertTrue("\"transaction_type\":\"BUY\",\"quantity\":130,\"order_type\":\"LIMIT\",\"product\":\"NRML\",\"price\":63.00" in body, body)
    }

    @Test fun `a GTT on the wrong side or too close is refused`() {
        val spec = Kite.Spec("NSE", "INFY", 1L, 1, 0.1)
        assertTrue(Kite.protect(spec, "CNC", 10, 1500.0, stop = 1600.0, target = null).second.any { "below" in it })
        assertTrue(Kite.protect(spec, "CNC", 10, 1500.0, stop = 1499.0, target = null).second.any { "0.25%" in it })
        val (g, _) = Kite.protect(spec, "CNC", 10, 1500.0, stop = 1400.0, target = null)
        assertEquals("single", g!!.type)
        assertEquals(Kite.Side.SELL, g.orders.single().side)
        assertEquals(1330.0, g.orders.single().price!!, 1e-9)
    }
}
