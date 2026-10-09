package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Zerodha's order updates on the stream ([OrderUpdate]), from Kite Connect's documented postback shape. */
class OrderUpdateTest {
    private val complete = """{"type":"order","id":"","data":{"account_id":"XY0000","unfilled_quantity":0,"checksum":"","placed_by":"XY0000",
        "order_id":"220303000308932","exchange_order_id":"1000000001482421","parent_order_id":null,"status":"COMPLETE",
        "status_message":null,"status_message_raw":null,"order_timestamp":"2022-03-03 09:24:25","exchange_update_timestamp":"2022-03-03 09:24:25",
        "exchange_timestamp":"2022-03-03 09:24:25","variety":"regular","exchange":"NFO","tradingsymbol":"NIFTY26OCT24500CE",
        "instrument_token":12345678,"order_type":"LIMIT","transaction_type":"BUY","validity":"DAY","product":"MIS","quantity":75,
        "disclosed_quantity":0,"price":120.5,"trigger_price":0,"average_price":120.35,"filled_quantity":75,"pending_quantity":0,
        "cancelled_quantity":0,"market_protection":0,"meta":{},"tag":"orb","guid":"abc"}}"""

    @Test fun aFilledOrder() {
        val u = OrderUpdate.parse(complete)!!
        assertEquals("220303000308932", u.orderId)
        assertEquals("COMPLETE", u.status)
        assertEquals(120.35, u.avgPrice, 1e-9)
        assertEquals(75, u.filled)
        assertEquals("", u.message, "a null message is empty, never the word null")
        assertEquals("NIFTY26OCT24500CE", u.symbol)
        assertEquals("orb", u.tag)
        assertTrue(u.terminal)
        // Nothing of the account is kept.
        assertFalse(u.toString().contains("XY0000"))
    }

    @Test fun rejectedCancelledAndOpen() {
        val rej = OrderUpdate.parse("""{"type":"order","data":{"order_id":"1","status":"REJECTED","status_message":"RMS:Margin Exceeds","average_price":0,"filled_quantity":0}}""")!!
        assertTrue(rej.terminal); assertEquals("RMS:Margin Exceeds", rej.message); assertEquals(0, rej.filled)
        val cxl = OrderUpdate.parse("""{"type":"order","data":{"order_id":"2","status":"cancelled","average_price":"0","filled_quantity":"0"}}""")!!
        assertEquals("CANCELLED", cxl.status); assertTrue(cxl.terminal)
        val open = OrderUpdate.parse("""{"type":"order","data":{"order_id":3,"status":"OPEN","average_price":null,"tag":null}}""")!!
        assertEquals("3", open.orderId); assertFalse(open.terminal); assertEquals(0.0, open.avgPrice); assertEquals("", open.tag)
        val upd = OrderUpdate.parse("""{"type":"order","data":{"order_id":"4","status":"UPDATE","average_price":"bad","filled_quantity":25}}""")!!
        assertFalse(upd.terminal); assertEquals(25, upd.filled); assertEquals(0.0, upd.avgPrice)
    }

    @Test fun anythingElseIsNotAnOrderUpdate() {
        assertNull(OrderUpdate.parse("""{"type":"error","data":"Invalid token"}"""))
        assertNull(OrderUpdate.parse("""{"type":"message","data":"hello"}"""))
        assertNull(OrderUpdate.parse("""{"type":"order","data":"not an object"}"""))
        assertNull(OrderUpdate.parse("""{"type":"order","data":{"status":"COMPLETE"}}"""), "no order id")
        assertNull(OrderUpdate.parse("""{"type":"order","data":{"order_id":"5"}}"""), "no status")
        assertNull(OrderUpdate.parse("""{"type":"order","data":{"order_id":"","status":"OPEN"}}"""))
        assertNull(OrderUpdate.parse("[1,2]"))
        assertNull(OrderUpdate.parse("not json"))
        assertNull(OrderUpdate.parse(""))
        assertNull(OrderUpdate.parse("""{"type":"order","data":{"order_id":true,"status":"OPEN"}}"""))
    }
}
