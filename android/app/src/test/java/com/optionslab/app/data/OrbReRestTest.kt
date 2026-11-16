package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime

/**
 * 16 Nov 2026 (Boss: "All the stops and profit locks should be on zerodha", then "yes" to the ORB arms too): every ORB-family live
 * trade (ORB and ORB Fresh trade live) now puts a stop back at Zerodha that the exchange or Zerodha cancelled or refused, as
 * Liquidity 15+5 does since that morning ([StepTrailLiquidityLiveTest]) - whatever "Keep a stop at the exchange" said (it is no longer
 * read). A stop Boss cancelled in Kite is never put back (kept across a restart) and the app sells at the level itself; never beside
 * a square-off's order, at the 15:05 expiry square-off, at or after 15:10, with Stop for today, beside its own working sale or its own
 * orphan stop; another book's stop on the contract never holds it back. Against the fake Kite (a lot of 30 bought at 200, its -40
 * stop at 160, the price at 200). Never on a fast look; a stop Zerodha keeps refusing is said once; a trade open with no stop at
 * Zerodha when the update came keeps being watched by the app (the earlier build never noted whether Boss had cancelled it).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrbReRestTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val paperCe = "BANKNIFTY-TEST-52000CE"
    private val paperPe = "BANKNIFTY-TEST-52000PE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private lateinit var now: LocalDateTime

    /** A cancel in the exchange's / Zerodha's risk system's words ([com.optionslab.ira.ManualCoverRule.endedByExchange]). */
    private val exchangeCancel = "Cancelled by the exchange (RMS)"

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_002, "BANKNIFTY26OCT52000PE", "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        // The setting off (its old default): the re-rest no longer depends on it.
        AppSettings.save(AppSettings.load().copy(exchangeStops = false))
        val t = AutomationSupport.earlierToday()
        OrbArms.testNow = t
        now = t.toLocalDateTime().withNano(0)
    }

    @After fun down() {
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun contract(symbol: String, right: String, exp: java.time.LocalDate) =
        JSONArray().put(symbol).put("BANKNIFTY").put(exp.toString()).put(52_000.0).put(right).put(30).put("NSE_FO|TEST$right")

    private fun ago(minutes: Long) = maxOf(now.minusMinutes(minutes), now.toLocalDate().atStartOfDay())

    private fun stopOrder(qty: Int = 30, trigger: Double = 160.0, limit: Double = 152.0) = runBlocking {
        Broker.placeOrder(Kite.Order(sym, Kite.Side.SELL, qty, 30, "MIS", "SL", limit, 0.05, "NFO", "iraorb", triggerPrice = trigger), exit = true)
    }

    /** [arm]'s live lot of 30 at 200 with its stop [stopId] resting at 160, held at Zerodha. */
    private fun held(arm: String = "orb", exp: java.time.LocalDate = expiry): String {
        val s = stopOrder()
        runBlocking { Strategies.tagOwner("kite:$s", "${label(arm)} · stop") }
        val p = JSONObject().put("arm", arm).put("symbol", paperCe).put("right", "CE").put("qty", 30).put("entry", 200.0)
            .put("entryTime", ago(30).toString()).put("signalBar", ago(35).toString())
            .put("entryOrderId", "E1").put("stopOrderId", s).put("stopTrigger", 160.0)
            .put("exitTime", "").put("why", "").put("charges", 0.0).put("live", true).put("kite", sym).put("unconfirmed", false)
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", JSONObject().put(arm, true)).put("auto", JSONObject().put(arm, true)).put("liveOk", JSONObject().put(arm, true))
            .put("legs", JSONObject().put("day", now.toLocalDate().toString()).put("strike", 52_000).put("expiry", exp.toString())
                .put("ce", contract(paperCe, "CE", exp)).put("pe", contract(paperPe, "PE", exp)))
            .put("positions", JSONArray(listOf(p))))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.requests.clear()
        return s
    }

    private fun label(arm: String) = mapOf("orb" to "ORB", "orb_fresh" to "ORB Fresh").getValue(arm)
    private fun row(arm: String = "orb") = runBlocking { OrbArms.view() }.arms.single { it.arm.source == arm }
    private fun pass() = runBlocking { OrbArms.priceCheckOnly() }
    private fun slCount() = kite.placed.count { it.form["order_type"] == "SL" }
    private val marketSells: List<FakeKite.Req> get() = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }
    private fun alerts() = AutomationSupport.alerts().joinToString("\n")

    /** Ended by the exchange (its words) or refused: every ORB-family arm puts it back at its own 160, the setting off; said so. */
    @Test fun aStopTheExchangeCancelledOrRefusedIsPutBackAtZerodhaForEveryOrbArm() {
        for ((arm, status) in listOf("orb" to "CANCELLED", "orb" to "REJECTED", "orb_fresh" to "CANCELLED", "orb_fresh" to "REJECTED")) {
            AutomationSupport.clearAlerts()
            val s = held(arm)
            kite.reject(s, message = if (status == "CANCELLED") exchangeCancel else "RMS:Trigger price is outside the circuit limit", status = status)
            pass()
            val sl = kite.placed.singleOrNull { it.form["order_type"] == "SL" } ?: throw AssertionError("$arm: one SL placed again, got ${kite.placed.map { it.form }}")
            assertEquals(arm, "160.00", sl.form["trigger_price"]); assertEquals(arm, "30", sl.form["quantity"]); assertEquals(arm, "SELL", sl.form["transaction_type"])
            val p = row(arm).open!!
            assertEquals(arm, kite.orders.keys.last(), p.stopOrderId)
            assertEquals(arm, 160.0, p.stopTrigger!!, 1e-9)
            assertFalse(arm, p.stopByYou)
            assertTrue("$arm: ${alerts()}", AutomationSupport.alerts().any { it.contains("${label(arm)}: the stop at Zerodha was ${status.lowercase()}") &&
                it.contains("places it again at Zerodha on its next check") })
            assertTrue(arm, marketSells.isEmpty())
            // The next pass places no second stop.
            pass()
            assertEquals(arm, 1, slCount())
            kite.requests.clear()
        }
    }

    /**
     * Cancelled by Boss in Kite (no exchange words): never put back, said so, kept across a restart; the app watches 160 itself and
     * sells there with one MARKET sale.
     */
    @Test fun aStopBossCancelledIsNeverPutBackAndTheAppSellsAtTheLevel() {
        val s = held("orb")
        kite.reject(s, message = "", status = "CANCELLED")
        pass(); pass()
        assertEquals("never put back", 0, slCount())
        assertTrue(row().open!!.stopByYou)
        assertTrue(alerts(), AutomationSupport.alerts().any { it.contains("ORB: the stop at Zerodha was cancelled") && it.contains("It looks cancelled by you (in Kite)") })
        // A restart keeps it.
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue(row().open!!.stopByYou)
        pass()
        assertEquals("still not put back after the restart", 0, slCount())
        // The price at the level: the app sells itself, once.
        kite.quote("NFO:$sym", 159.0, 158.95, 159.05)
        pass()
        assertEquals(0, slCount())
        assertEquals(1, marketSells.size)
        assertEquals("stop", row().today.single().why)
    }

    /** A square-off's order working on the contract (the expiry's or the flat square-off's): no new stop beside it. */
    @Test fun aSquareOffsWorkingOrderHoldsTheReRestBack() {
        for (tag in SquareOffExit.TAGS) {
            val s = held("orb")
            kite.orders["SQ-$tag"] = FakeKite.Order("SQ-$tag", sym, "NFO", "SELL", 30, "MIS", "MARKET", 0.0, 0.0, tag, "OPEN")
            kite.reject(s, message = exchangeCancel, status = "CANCELLED")
            pass()
            assertEquals("$tag: never two exits", 0, slCount())
            kite.orders.remove("SQ-$tag")
        }
    }

    /** On expiry day from 15:05 (the app's expiry square-off sells it): no stop put back. */
    @Test fun atTheExpirySquareOffNoStopIsPutBack() {
        val u = FakeUpstox()
        try {
            val today = now.toLocalDate()
            val s = held("orb", exp = today)
            Paper.rememberContract(Paper.Contract(paperCe, "BANKNIFTY", today, 52_000.0, Right.CE, 30, "NSE_FO|TESTCE"))
            kite.reject(s, message = exchangeCancel, status = "CANCELLED")
            OrbArms.testNow = today.atTime(15, 6).atZone(IST)
            pass()
            assertEquals(0, slCount())
        } finally { u.close() }
    }

    /** At or after 15:10: no stop put back; the session's square-off sells it at market on the same look. */
    @Test fun atOrAfterTheSquareOffItSellsInsteadOfReResting() {
        val s = held("orb")
        kite.reject(s, message = exchangeCancel, status = "CANCELLED")
        OrbArms.testNow = now.toLocalDate().atTime(15, 11).atZone(IST)
        pass()
        assertEquals(0, slCount())
        assertEquals(1, marketSells.size)
        assertEquals("session_end", row().today.single().why)
    }

    /** With Stop for today on: no stop put back; the operator's stop sells it at market. */
    @Test fun stopForTodaySellsInsteadOfReResting() {
        val s = held("orb")
        kite.reject(s, message = exchangeCancel, status = "CANCELLED")
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        pass()
        assertEquals(0, slCount())
        assertEquals(1, marketSells.size)
    }

    /** Another book's stop on the same contract never holds this arm's stop back. */
    @Test fun anotherBooksStopOnTheContractDoesNotBlock() {
        val s = held("orb")
        kite.orders["OTHER1"] = FakeKite.Order("OTHER1", sym, "NFO", "SELL", 30, "MIS", "SL", 140.0, 145.0, "iraorb", "TRIGGER PENDING")
        runBlocking { Strategies.tagOwner("kite:OTHER1", "Another book · stop") }
        kite.reject(s, message = exchangeCancel, status = "CANCELLED")
        pass()
        assertEquals("put back beside another book's stop", 1, slCount())
        assertEquals("160.00", kite.placed.last().form["trigger_price"])
    }

    /** Its own exit sale still working (an earlier pass's, its reply lost - no label kept): no new stop beside it. */
    @Test fun itsOwnWorkingSaleBlocks() {
        val s = held("orb")
        kite.orders["MINE1"] = FakeKite.Order("MINE1", sym, "NFO", "SELL", 30, "MIS", "MARKET", 0.0, 0.0, "iraorb", "OPEN")
        kite.reject(s, message = exchangeCancel, status = "CANCELLED")
        pass()
        assertEquals("never beside its own working sale", 0, slCount())
        // That sale gone: the stop goes back.
        kite.orders.remove("MINE1")
        pass()
        assertEquals(1, slCount())
    }

    /** A resting stop of the arms' own with no other book's label (this position's own, its reply lost): no second stop. */
    @Test fun itsOwnOrphanStopBlocks() {
        val s = held("orb")
        kite.orders["ORPHAN1"] = FakeKite.Order("ORPHAN1", sym, "NFO", "SELL", 30, "MIS", "SL", 152.0, 160.0, "iraorb", "TRIGGER PENDING")
        kite.reject(s, message = exchangeCancel, status = "CANCELLED")
        pass()
        assertEquals("never a second stop beside its own", 0, slCount())
    }

    /** The app's own cancel (an exit taking its stop out first) is never taken as Boss's: no "cancelled by you", never stopByYou. */
    @Test fun theAppsOwnCancelIsNeverTakenAsBosss() {
        held("orb")
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)                    // the ORB's +40 target: the app takes its stop out and sells
        pass()
        assertNotNull(row().today.single().exit)
        assertFalse(alerts(), AutomationSupport.alerts().any { it.contains("cancelled by you") })
    }

    /** A fast look (a stream tick's) never places a stop - only the regular pass does. */
    @Test fun aFastLookNeverPutsTheStopBack() {
        val s = held("orb")
        kite.reject(s, message = exchangeCancel, status = "CANCELLED")
        runBlocking { OrbArms.priceCheckOnly(fast = true) }
        assertEquals("no stop placed on a fast look", 0, slCount())
        pass()
        assertEquals(1, slCount())
    }

    /** The put-back stop refused again in the same words: the alert is said once, not at every minute's try. */
    @Test fun aStopZerodhaKeepsRefusingIsSaidOnce() {
        val s = held("orb")
        val why = "RMS:Trigger price is outside the circuit limit"
        kite.reject(s, message = why, status = "REJECTED")
        pass()
        assertEquals(1, slCount())
        val again = row().open!!.stopOrderId!!
        kite.reject(again, message = why, status = "REJECTED")
        pass()
        assertEquals("said once: ${alerts()}", 1, AutomationSupport.alerts().count { it.contains("ORB: the stop at Zerodha was rejected") })
        assertFalse(row().open!!.stopByYou)
    }

    /**
     * A book saved by the earlier build (before this update): an ORB live trade open with no stop at Zerodha may be one Boss cancelled
     * (that build never noted it) - it keeps being watched by the app, never put back by itself; one with its stop resting is untouched.
     */
    @Test fun aTradeOpenWithNoStopAtTheUpdateKeepsBeingWatched() {
        val p = JSONObject().put("arm", "orb").put("symbol", paperCe).put("right", "CE").put("qty", 30).put("entry", 200.0)
            .put("entryTime", ago(30).toString()).put("signalBar", ago(35).toString())
            .put("entryOrderId", "E1").put("stopOrderId", "").put("stopTrigger", 160.0)
            .put("exitTime", "").put("why", "").put("charges", 0.0).put("live", true).put("kite", sym).put("unconfirmed", false)
        val migrated = JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION).put(com.optionslab.engine.orb.RetiredArms.UNRETIRE)
            .put(com.optionslab.engine.orb.LiquidityRules.MIDCP_JOIN).put(com.optionslab.engine.orb.ParkedArms.MIGRATION)
            .put(com.optionslab.engine.orb.FamilyPark.MIGRATION).put(OrbArms.LIQ_SWITCH_CLEAR)
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", JSONObject().put("orb", true)).put("auto", JSONObject().put("orb", true)).put("liveOk", JSONObject().put("orb", true))
            .put("legs", JSONObject().put("day", now.toLocalDate().toString()).put("strike", 52_000).put("expiry", expiry.toString())
                .put("ce", contract(paperCe, "CE", expiry)).put("pe", contract(paperPe, "PE", expiry)))
            .put("positions", JSONArray(listOf(p))).put("migrated", migrated))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.requests.clear()
        assertTrue(row().open!!.stopByYou)
        pass()
        assertEquals("not put back", 0, slCount())
        // Marked once: a later load (a restart) changes nothing more, and the app still sells at 160 itself.
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue(row().open!!.stopByYou)
        kite.quote("NFO:$sym", 159.0, 158.95, 159.05)
        pass()
        assertEquals(1, marketSells.size)
        assertEquals("stop", row().today.single().why)
    }
}
