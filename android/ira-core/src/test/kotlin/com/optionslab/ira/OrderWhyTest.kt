package com.optionslab.ira

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderWhyTest {
    private fun t(h: Int, m: Int) = LocalTime.of(h, m)
    private val sym = "FINNIFTY27OCT2624800CE"

    /** Boss's day (5 Oct): the Liquidity entry and its resting stop at 09:21, the index-stop exit at 09:23. */
    private fun day(noted: String? = null) = listOf(
        OrderWhy.Ord("Paper", t(9, 15), "NIFTY13OCT2625000CE", "BUY", 75, "COMPLETE", 120.0, "ORB · entry"),
        OrderWhy.Ord("Paper", t(9, 21), sym, "BUY", 60, "COMPLETE", 455.0, "Liquidity 15+5 · entry"),
        OrderWhy.Ord("Paper", t(9, 21), sym, "SELL", 60, "CANCELLED", 0.0, "Liquidity 15+5 · stop", noted = noted, ended = t(9, 23)),
        OrderWhy.Ord("Paper", t(9, 23), sym, "SELL", 60, "COMPLETE", 471.75, "Liquidity 5m FINNIFTY · index_stop"),
    )

    @Test fun bossesQuestionsAreAsked() {
        for (q in listOf("why was my last order canceled", "why was my last order cancelled?", "Why did my order get rejected?",
            "why was the BankNifty order cancelled", "mera order cancel kyun hua", "what happened to my last order",
            "why did you cancel my order", "why did my stop order get cancelled", "why was my stop loss cancelled",
            "why did my zerodha order fail", "why didn't my order go through", "order reject kyon ho gaya", "kyun cancel hua mera order",
            "what was the rejection reason", "reason for the cancellation of my order", "what happened to the 9:21 order",
            "why was the 24800 ce sell order cancelled", "mere last order ka kya hua", "jarvis why was my order rejected"))
            assertNotNull(OrderWhy.asked(q), q)
        for (q in listOf("cancel my last order", "cancel the last order", "cancel all orders", "please cancel my order", "cancel order 2",
            "why don't you cancel my order", "should i cancel my order", "what happened to my orders today", "show my orders",
            "why was i logged out of zerodha", "what happened to my zerodha session", "why did my trade lose", "why is nifty down",
            "why did you exit my position", "saare orders cancel karo", "order cancel karo", "how many orders did i place", "what is a stop loss"))
            assertNull(OrderWhy.asked(q), q)
        assertEquals(OrderWhy.Want.CANCELLED, OrderWhy.asked("why was my last order canceled")!!.want)
        assertEquals(OrderWhy.Want.REJECTED, OrderWhy.asked("why did my order get rejected")!!.want)
        assertEquals(OrderWhy.Want.ANY, OrderWhy.asked("what happened to my last order")!!.want)
        val b = OrderWhy.asked("why was the BankNifty order cancelled")!!
        assertEquals(Market.BANKNIFTY, b.market)
        val s = OrderWhy.asked("why was the 9:21 paper 24800 ce sell stop order cancelled")!!
        assertEquals(t(9, 21), s.time); assertEquals("24800", s.strike); assertEquals("CE", s.right); assertEquals("SELL", s.side)
        assertEquals("Paper", s.venue); assertEquals("stop", s.leg)
    }

    @Test fun theQuestionNeverActsAndTheCommandStillDoes() {
        for (q in listOf("why was my last order cancelled", "why did my order get rejected", "why was the banknifty order cancelled",
            "mera order cancel kyun hua", "what happened to my last order", "why did you cancel my order")) {
            val p = Ask.parse(q)
            assertNull(p.command, q); assertNull(p.order, q)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, q)
            assertFalse(Bundle.acts(q), q)
            assertEquals("OrderWhy", CoverageTest().feature(q), q)
        }
        assertEquals(Command.Kind.CANCEL_ONE, Ask.parse("cancel my last order").command?.kind)
        assertEquals("Act", CoverageTest().feature("cancel my last order"))
        assertEquals(Command.Kind.CANCEL_ALL, Ask.parse("cancel all orders").command?.kind)
        // Said with something to do: left to the multi-step plan, never answered here with the action dropped.
        assertTrue(Bundle.acts("why was my order cancelled, then close all positions") || Ask.parse("why was my order cancelled, then close all positions").command != null)
    }

    @Test fun theStopCancelledBecauseTheIndexStopClosedThePosition() {
        // Read from the orders (before the app noted reasons).
        val a = OrderWhy.answer(OrderWhy.asked("why was my last order canceled")!!, day())
        assertTrue(a.startsWith("Boss, your last cancelled order: Paper SELL 60 $sym placed at 09:21, the stop-loss order of Liquidity 15+5 - cancelled at 09:23."), a)
        assertTrue(a.contains("The position was closed at 09:23 by Liquidity 5m FINNIFTY's index stop (SELL 60 at 471.75), so the stop-loss order was no longer needed"), a)
        assertTrue(a.contains("read from the orders"), a)
        assertFalse(a.contains("#"), a)
        // Noted by the app when it cancelled it.
        val n = OrderWhy.answer(OrderWhy.asked("why was my last order cancelled")!!, day("exit:index_stop"))
        assertTrue(n.contains("It was cancelled because the arm was closing the position itself on its index stop (the index went back through the level"), n)
        assertTrue(n.contains("The position was closed at 09:23 by Liquidity 5m FINNIFTY's index stop (SELL 60 at 471.75)."), n)
        // "What happened to my last order": the last one was the exit, filled; the cancelled stop is said too.
        val w = OrderWhy.answer(OrderWhy.asked("what happened to my last order")!!, day())
        assertTrue(w.startsWith("Boss, your last order: Paper SELL 60 $sym placed at 09:23, the index stop of Liquidity 5m FINNIFTY - filled at 471.75."), w)
        assertTrue(w.contains("The last cancelled or rejected one: Paper SELL 60 $sym placed at 09:21"), w)
    }

    @Test fun rejectionsSayTheMessagePlainly() {
        val os = day() + listOf(
            OrderWhy.Ord("Zerodha", t(10, 2), "BANKNIFTY27OCT2652000PE", "BUY", 35, "REJECTED", 0.0, "Manual · Option chain",
                "RMS:Margin Exceeds,Required:52000, Available:12000 for entity account-XY1234"),
            OrderWhy.Ord("Zerodha", t(10, 5), "NIFTY13OCT2625000CE", "BUY", 1950, "REJECTED", 0.0, "ORB · entry", "Quantity exceeds the freeze limit"),
        )
        val a = OrderWhy.answer(OrderWhy.asked("why did my order get rejected")!!, os)
        assertTrue(a.startsWith("Boss, your last rejected order: Zerodha BUY 1950 NIFTY13OCT2625000CE placed at 10:05, the entry order of ORB."), a)
        assertTrue(a.contains("because the quantity was above the exchange's freeze limit") && a.contains("Zerodha said: \"Quantity exceeds the freeze limit\""), a)
        assertTrue(a.contains("latest of 2 rejected orders"), a)
        val b = OrderWhy.answer(OrderWhy.asked("why was the banknifty order rejected")!!, os)
        assertTrue(b.contains("BANKNIFTY27OCT2652000PE") && b.contains("not enough margin") && b.contains("placed by hand from Option chain"), b)
        // Asked "cancelled" when only a rejection fits: the rejection, said as such.
        val c = OrderWhy.answer(OrderWhy.asked("why was the banknifty order cancelled")!!, os)
        assertTrue(c.startsWith("None of today's BankNifty orders was cancelled, Boss. The last rejected BankNifty one: Zerodha BUY 35"), c)
        assertTrue(OrderWhy.plain("Markets are closed right now") == "the market was closed")
        assertTrue(OrderWhy.plain("Order price is outside the circuit limit")!!.contains("band"))
        assertTrue(OrderWhy.plain("Order not allowed from this IP; register a static IP")!!.contains("static IP"))
        assertTrue(OrderWhy.plain("Kill switch is on")!!.contains("kill switch"))
    }

    @Test fun notRecordedIsSaidSo() {
        val os = listOf(OrderWhy.Ord("Zerodha", t(11, 0), "NIFTY13OCT2625000CE", "BUY", 75, "CANCELLED", 0.0, "Manual · Chart"))
        val a = OrderWhy.answer(OrderWhy.asked("why was my zerodha order cancelled")!!, os)
        assertTrue(a.contains("The app has no reason recorded for it, and Zerodha gave no message"), a)
        val late = listOf(OrderWhy.Ord("Paper", t(15, 10), "NIFTY13OCT2625000CE", "SELL", 75, "CANCELLED", 0.0, null, ended = t(15, 15), product = "MIS"))
        assertTrue(OrderWhy.answer(OrderWhy.asked("why was my order cancelled")!!, late).contains("at or after 15:15"))
        val sq = listOf(OrderWhy.Ord("Paper", t(15, 10), "NIFTY13OCT2625000CE", "SELL", 75, "CANCELLED", 0.0, null, noted = "square_off"))
        assertTrue(OrderWhy.answer(OrderWhy.asked("why was my order cancelled")!!, sq).contains("15:15 intraday square-off"))
        assertEquals("There are no orders today, Boss, so nothing was cancelled or rejected. Zerodha is not logged in.",
            OrderWhy.answer(OrderWhy.asked("why was my order cancelled")!!, emptyList(), listOf("Zerodha is not logged in.")))
        val none = OrderWhy.answer(OrderWhy.asked("why was my order rejected")!!, listOf(day()[0]))
        assertEquals("None of today's orders was rejected, Boss.", none)
        for (k in listOf("position_closed", "oco:stop", "oco:target", "unfilled_market", "you", "jarvis", "strategy", "protection_removed",
            "protection_replaced", "square_off", "expiry", "day_end")) assertFalse(OrderWhy.noted(k) == k, k)
    }
}
