package com.optionslab.ira

import com.optionslab.engine.strategy.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Zerodha's own charges for the day (POST /charges/orders) instead of the estimate, when it answers. */
class ExactChargesTest {
    private fun order(id: String, status: String = "COMPLETE", filled: Int = 75, avg: Double = 100.0, side: String = "BUY") =
        ExactCharges.Order(id, "NFO", "NIFTY26OCT24500CE", side, "regular", "NRML", "LIMIT", status, filled, avg)

    /** Kite Connect's documented reply shape, two orders. */
    private val sample = """
        {"status":"success","data":[
          {"transaction_type":"BUY","tradingsymbol":"NIFTY26OCT24500CE","exchange":"NFO","variety":"regular","product":"NRML",
           "order_type":"LIMIT","quantity":75,"price":100,
           "charges":{"transaction_tax":0,"transaction_tax_type":"stt","exchange_turnover_charge":2.6,"sebi_turnover_charge":0.0075,
             "brokerage":20,"stamp_duty":0.23,"gst":{"igst":4.0814,"cgst":0,"sgst":0,"total":4.0814},"total":26.92},
           "order_id":"251005000000001"},
          {"transaction_type":"SELL","tradingsymbol":"NIFTY26OCT24500CE","exchange":"NFO","variety":"regular","product":"NRML",
           "order_type":"MARKET","quantity":75,"price":120,
           "charges":{"transaction_tax":9,"transaction_tax_type":"stt","exchange_turnover_charge":3.12,"sebi_turnover_charge":0.009,
             "brokerage":20,"stamp_duty":0,"gst":{"igst":4.1632,"cgst":0,"sgst":0,"total":4.1632},"total":36.29},
           "order_id":"251005000000002"}
        ]}
    """.trimIndent()

    @Test fun theTotalsOfEveryOrderAreAddedUp() {
        assertEquals(63.21, ExactCharges.total(sample))
        assertEquals(63.21, ExactCharges.total(sample, listOf("251005000000002", "251005000000001")))
        // Its data array alone reads the same.
        val data = Json.write((Json.parse(sample) as Map<*, *>)["data"])
        assertEquals(63.21, ExactCharges.total(data))
    }

    @Test fun anythingOddInTheAnswerIsNoFigure() {
        assertNull(ExactCharges.total("""{"status":"error","error_type":"InputException","message":"bad","data":null}"""))
        assertNull(ExactCharges.total("not json"))
        assertNull(ExactCharges.total("""{"status":"success","data":[]}"""))
        assertNull(ExactCharges.total("""{"status":"success","data":[{"order_id":"1","charges":{"brokerage":20}}]}"""), "no total")
        assertNull(ExactCharges.total("""{"status":"success","data":[{"order_id":"1"}]}"""), "no charges")
        assertNull(ExactCharges.total(sample, listOf("251005000000001")), "answered for more orders than asked")
        assertNull(ExactCharges.total(sample, listOf("251005000000001", "251005000000003")), "answered for other orders")
    }

    @Test fun onlyCompleteOrdersThatFilledAreAskedAbout() {
        val orders = listOf(
            order("3"), order("1", avg = 120.0, side = "SELL"),
            order("2", status = "OPEN", filled = 0), order("4", status = "REJECTED", filled = 0),
            order("5", status = "COMPLETE", filled = 0), order("6", avg = 0.0), order("1"),
        )
        val bill = ExactCharges.billable(orders)
        assertEquals(listOf("1", "3"), bill.map { it.orderId })
        assertEquals("1,3", ExactCharges.key(bill))
        assertEquals("", ExactCharges.key(ExactCharges.billable(listOf(order("2", status = "CANCELLED", filled = 0)))))
    }

    @Test fun theRequestCarriesTheExecutedQuantityAndAveragePrice() {
        val body = Json.parse(ExactCharges.requestJson(listOf(order("7", filled = 150, avg = 101.25, side = "SELL")))) as List<*>
        val o = body.single() as Map<*, *>
        assertEquals(listOf("order_id", "exchange", "tradingsymbol", "transaction_type", "variety", "product", "order_type", "quantity", "average_price"),
            o.keys.map { it.toString() })
        assertEquals("7", o["order_id"]); assertEquals("SELL", o["transaction_type"]); assertEquals("regular", o["variety"])
        assertEquals(150L, o["quantity"]); assertEquals(101.25, o["average_price"])
    }

    @Test fun aPartFilledOrderNotCompleteMeansTheDayIsNotWhole() {
        assertTrue(ExactCharges.whole(listOf(order("1"), order("2", status = "REJECTED", filled = 0))))
        assertFalse(ExactCharges.whole(listOf(order("1"), order("2", status = "OPEN", filled = 25))))
        assertFalse(ExactCharges.whole(listOf(order("1"), order("2", status = "CANCELLED", filled = 25))))
    }

    @Test fun askedAtMostOnceAMinuteAndTheSameOrdersOnlyAfterAWhile() {
        val t = 1_000_000_000L
        assertTrue(ExactCharges.mayAsk(sameAsked = false, lastAskMs = 0, nowMs = t), "never asked")
        assertFalse(ExactCharges.mayAsk(sameAsked = false, lastAskMs = t, nowMs = t + 59_000), "a new order within the minute waits")
        assertTrue(ExactCharges.mayAsk(sameAsked = false, lastAskMs = t, nowMs = t + 60_000))
        assertFalse(ExactCharges.mayAsk(sameAsked = true, lastAskMs = t, nowMs = t + 5 * 60_000), "the same orders are not asked every minute")
        assertTrue(ExactCharges.mayAsk(sameAsked = true, lastAskMs = t, nowMs = t + ExactCharges.RETRY_MS))
    }

    @Test fun aKeptAnswerCoversTheDayOnlyWhenItHasEveryOrder() {
        assertTrue(ExactCharges.covers(setOf("1", "2"), listOf("1", "2", "2")))
        assertFalse(ExactCharges.covers(setOf("1"), listOf("1", "2")), "a fill of an order not in the answer")
        assertFalse(ExactCharges.covers(setOf("1"), emptyList()), "no fills: nothing to say")
    }

    @Test fun exactIsShownWithoutTheEstimateWords() {
        assertEquals("Charges ₹63", PnlCharges.line(ExactCharges.total(sample), estimate = false))
    }
}
