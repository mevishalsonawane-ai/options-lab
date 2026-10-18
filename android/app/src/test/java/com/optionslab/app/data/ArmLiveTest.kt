package com.optionslab.app.data

import com.optionslab.app.ira.IraNewsTrades
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.orb.LiveMirror
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

/**
 * 18 Oct (Boss: "What ever strategies are present for paper, should be present for live too"): the paper strategies' live path
 * ([ArmLive]) against the fake Kite. For every mirrored arm: its live entry goes with its paper trade's contract, lots and stop
 * when every switch allows it, and nothing goes in Paper, with AI trades off, with its own switch off, in the paper-only period or
 * with the kill switch on; its stop rests at Zerodha as an SL order (a GTT for a carried trade) and is MODIFIED by the paper
 * trade's lock; a paper exit sells it with the stop taken out first, so stop and exit never both fill; 15:15 squares it off; a
 * restart restores it and its watch; and its record is its own, labelled live, apart from the paper book's.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ArmLiveTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private val contract by lazy { Paper.Contract("BANKNIFTY-TEST-52000CE", "BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|TESTCE") }
    /** The paper books as the tests hold them: ref -> (arm, quantity still held). */
    private val legs = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        IraNewsTrades.paperFirst = false                      // AI trades go live (as going Live with the PIN sets it)
        ArmLive.testSync = true
        ArmLive.testDefaultOff = false                        // each arm's switch as in the app: on until Boss turns it off
        ArmLive.testNow = AutomationSupport.earlierToday()
        for (f in LiveMirror.ARMS.map { it.family }.distinct())
            ArmLive.register(f) { legs.entries.filter { e -> LiveMirror.spec(e.value.first)?.family == f }.map { ArmLive.PaperLeg(it.key, it.value.first, it.value.second) } }
        kite.requests.clear()
    }

    @After fun down() {
        ArmLive.testSync = false
        ArmLive.testNow = null
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun ask(key: String, ref: String = "t:$key", qty: Int = 30, stop: Double? = 170.0, limit: Double? = null,
                    c: Paper.Contract = contract): ArmLive.Ask {
        legs[ref] = key to qty
        return ArmLive.Ask(key, ref, c, 1, qty, 200.0, stop = stop, limit = limit)
    }

    /** Another BANKNIFTY call ([i] hundred points up), listed and quoted at 200 (the account guard's lots cap is per option). */
    private fun other(i: Int): Pair<Paper.Contract, String> {
        val k = 52_000.0 + 100 * i
        val s = "BANKNIFTY26OCT${k.toInt()}CE"
        kite.instruments += FakeKite.Ins(22_000_100L + i, s, "BANKNIFTY", expiry, k, "CE", 30)
        kite.quote("NFO:$s", 200.0, 199.95, 200.05)
        return Paper.Contract("BANKNIFTY-TEST-${k.toInt()}CE", "BANKNIFTY", expiry, k, Right.CE, 30, "NSE_FO|T$i") to s
    }
    private fun watch() = runBlocking { ArmLive.watch() }
    private fun view() = ArmLive.view.value
    private fun form(r: FakeKite.Req, vararg k: String) = r.form.filterKeys { it in k }
    private fun held() = kite.positions["NFO:$sym:MIS"]?.first ?: 0
    private fun nrmlHeld() = kite.positions["NFO:$sym:NRML"]?.first ?: 0

    // ---- entries --------------------------------------------------------------------------------------------------------------

    @Test fun everyIntradayArmsLiveEntryBuysItsPaperLotsThenRestsItsStopAtZerodha() {
        runBlocking { ArmLive.setLive(LiveMirror.TELEGRAM_CALLS, true, pinConfirmed = true) }   // its switch starts off (Boss's own)
        SecurePrefs.putAll(mapOf("g.trades" to 0, "k.maxOrders" to 500))          // every arm once in one day: no day caps in this test
        val intraday = LiveMirror.ARMS.filter { it.exchange == "NFO" && it.product == "MIS" }
        assertTrue(intraday.size >= 12)
        val cs = intraday.indices.map { other(it + 1) }
        java.io.File(context.filesDir, "kite_instruments.json").delete()          // the day's list read again, with them
        runBlocking { Broker.instruments() }
        for ((i, spec) in intraday.withIndex()) {
            kite.requests.clear()
            val (c, s) = cs[i]
            ArmLive.entered(ask(spec.key, c = c))
            val (buy, stop) = kite.placed.also { assertEquals(spec.key + " " + view().refused[spec.key], 2, it.size) }
            assertEquals(spec.key, mapOf("tradingsymbol" to s, "transaction_type" to "BUY", "order_type" to "MARKET", "quantity" to "30",
                "product" to "MIS", "tag" to "iraarm"), form(buy, "tradingsymbol", "transaction_type", "order_type", "quantity", "product", "tag"))
            // -15% of the paper fill on the live fill (the same points for the ORB family: 200 -> 170 both ways), SL with its limit.
            assertEquals(spec.key, mapOf("transaction_type" to "SELL", "order_type" to "SL", "trigger_price" to "170.00", "price" to "161.50", "quantity" to "30"),
                form(stop, "transaction_type", "order_type", "trigger_price", "price", "quantity"))
            // (The account guard allows 3 open positions: the next arm's starts from a flat account.)
            kite.flat(); kite.orders.values.filter { it.working }.forEach { it.status = "CANCELLED" }
        }
        assertEquals(intraday.size, view().open.size)
        assertTrue("every one labelled with its own arm", view().open.map { it.key }.toSet() == intraday.map { it.key }.toSet())
        assertTrue(view().open.all { it.stopOrderId != null })
    }

    @Test fun aLimitEntryPaysAtMostThePaperLimitAndTwoLotsBuyTwoLots() {
        ArmLive.entered(ask(com.optionslab.engine.orb.HeroTicketRules.SOURCE, qty = 60, limit = 200.0))
        assertEquals(mapOf("order_type" to "LIMIT", "price" to "200.00", "quantity" to "60"), form(kite.placed.first(), "order_type", "price", "quantity"))
    }

    @Test fun nothingGoesInPaperWithAiTradesOffWithItsSwitchOffInThePaperOnlyPeriodOrWithTheKillSwitch() {
        val key = com.optionslab.engine.orb.DonchianArm.SOURCE
        AutomationSupport.liveSettings(live = false)
        ArmLive.entered(ask(key, "p1"))
        assertTrue("Paper", kite.placed.isEmpty()); assertNull("nothing said in Paper", view().refused[key])
        AutomationSupport.liveSettings()
        IraNewsTrades.paperFirst = true
        ArmLive.entered(ask(key, "p2"))
        assertTrue(kite.placed.isEmpty()); assertEquals(true, view().refused[key]?.contains("AI trades go live"))
        IraNewsTrades.paperFirst = false
        runBlocking { ArmLive.setLive(key, false, pinConfirmed = false) }
        ArmLive.entered(ask(key, "p3"))
        assertTrue(kite.placed.isEmpty()); assertEquals(true, view().refused[key]?.contains("Trade live"))
        assertEquals("on again only with the PIN", "Turning ${LiveMirror.spec(key)!!.label}'s live trading on needs your PIN.",
            runBlocking { ArmLive.setLive(key, true, pinConfirmed = false) })
        runBlocking { ArmLive.setLive(key, true, pinConfirmed = true) }
        val today = Market.today()
        PaperOnly.resetForTest(com.optionslab.ira.PaperOnlyPeriod.Period(today.plusDays(8), "paper till 26 Oct", today.minusDays(5)))
        ArmLive.entered(ask(key, "p4"))
        assertTrue(kite.placed.isEmpty()); assertEquals(true, view().refused[key]?.contains("paper-only"))
        PaperOnly.resetForTest(null)
        SecurePrefs.put("g.kill", true)
        ArmLive.entered(ask(key, "p5"))
        assertTrue(kite.placed.isEmpty()); assertEquals(true, view().refused[key]?.contains("kill switch"))
        SecurePrefs.put("g.kill", false)
        AutomationSupport.security(compromised = true)
        ArmLive.entered(ask(key, "p6"))
        assertTrue(kite.placed.isEmpty())
        assertTrue(view().open.isEmpty())
    }

    @Test fun jarvisOwnIdeasHaveNoLivePath() {
        for (k in listOf("jarvis", "news", "forge")) ArmLive.entered(ArmLive.Ask(k, "j:$k", contract, 1, 30, 200.0))
        assertTrue(kite.requests.isEmpty())
        // The hosts of Jarvis's own ideas never call the live path.
        fun src(f: String) = listOf(java.io.File("src/main/java/com/optionslab/app/$f"), java.io.File("app/src/main/java/com/optionslab/app/$f")).first { it.isFile }.readText()
        assertFalse(src("ira/IraNewsTrades.kt").contains("ArmLive"))
        assertFalse(src("data/GrammarArm.kt").contains("ArmLive"))
        // Every mirrored host does: its entry, and its close or its book for the exits.
        for (f in listOf("data/PassedArmsHost.kt", "data/OrbArms.kt", "data/HeroTicketArm.kt", "data/VixDivArm.kt", "data/NightArm.kt",
                "data/SignalPaperArm.kt", "data/McxPaperArms.kt", "data/ShadowArms.kt", "ira/IraSolo.kt")) {
            val t = src(f)
            assertTrue("$f enters", t.contains("ArmLive.entered("))
            assertTrue("$f exits", t.contains("ArmLive.closed(") || t.contains("ArmLive.register("))
        }
    }

    // ---- the stop at Zerodha ----------------------------------------------------------------------------------------------

    @Test fun thePaperLockMovesTheZerodhaStopUpNeverDown() {
        val ref = "lock"
        ArmLive.entered(ask(com.optionslab.engine.orb.OovArm.SOURCE, ref))
        val sid = view().open.single().stopOrderId!!
        ArmLive.level(ref, 190.0)                                   // the paper trade's lock (30-60-100 / L25) moved its stop up
        watch()
        assertEquals(190.0, kite.order(sid).trigger, 1e-9)
        assertEquals(180.5, kite.order(sid).price, 1e-9)
        assertTrue("a modify of the same order", kite.requests.any { it.method == "PUT" && it.path.endsWith("/$sid") })
        assertEquals(1, kite.placed.count { it.form["order_type"] == "SL" })
        kite.requests.clear()
        ArmLive.level(ref, 180.0)
        watch()
        assertTrue("never down", kite.requests.none { it.method == "PUT" })
        assertEquals(190.0, view().open.single().stopTrigger!!, 1e-9)
    }

    @Test fun aPaperExitTakesTheStopOutFirstThenSellsSoTheyNeverBothFill() {
        val ref = "x"
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, ref))
        val sid = view().open.single().stopOrderId!!
        watch()                                                    // seen open on paper
        kite.requests.clear()
        legs[ref] = legs.getValue(ref).first to 0                 // the paper trade sold at its target
        watch()
        val cancel = kite.requests.indexOfFirst { it.method == "DELETE" && it.path.endsWith("/$sid") }
        val sell = kite.requests.indexOfFirst { it.method == "POST" && it.path == "/orders/regular" && it.form["transaction_type"] == "SELL" }
        assertTrue("the stop out before the sell", cancel in 0 until sell)
        assertEquals("CANCELLED", kite.order(sid).status)
        assertEquals("flat, never short", 0, held())
        val c = view().closed.single()
        assertEquals("paper_exit", c.why)
        watch()
        assertEquals("nothing more", 1, kite.placed.count { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" })
    }

    @Test fun aStopThatFilledAtZerodhaIsTheExitAndNoSecondSellGoes() {
        val ref = "s"
        ArmLive.entered(ask(com.optionslab.engine.orb.Solo2.SOURCE, ref))
        val sid = view().open.single().stopOrderId!!
        kite.trigger(sid, 170.0)                                   // filled at the exchange (the phone may be dead)
        assertEquals(0, held())
        legs[ref] = legs.getValue(ref).first to 0                 // and the paper trade's stop too
        watch()
        assertEquals("stop", view().closed.single().why)
        assertEquals("no market sell after the stop", 0, kite.placed.count { it.form["order_type"] == "MARKET" && it.form["transaction_type"] == "SELL" })
        assertEquals("flat, never short", 0, held())
    }

    @Test fun aTriggeredStopRestingAtItsLimitIsCancelledBeforeTheAppSells() {
        val ref = "r"
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, ref))
        val sid = view().open.single().stopOrderId!!
        kite.trigger(sid, 150.0)                                   // ran through its 161.50 limit: resting OPEN, unfilled
        kite.quote("NFO:$sym", 150.0, 149.95, 150.05)
        watch()
        assertEquals("CANCELLED", kite.order(sid).status)
        assertEquals("stop_run_through", view().closed.single().why)
        assertEquals(0, held())
    }

    @Test fun aPartPaperSaleSellsTheSameShareLive() {
        val ref = "half"
        ArmLive.entered(ask(com.optionslab.engine.orb.HeroRules.ARM.source, ref, qty = 60))
        watch()
        legs[ref] = legs.getValue(ref).first to 30                // half sold at 5x on paper
        watch()
        assertEquals(30, held())
        assertEquals(30, view().open.single().qty)
        assertNotNull("the rest keeps a stop", view().open.single().let { it.stopOrderId ?: it.stopTrigger })
    }

    @Test fun anIntradayTradeIsSquaredOffAt1515() {
        ArmLive.entered(ask(com.optionslab.engine.orb.VixDivRules.SOURCE, "sq"))
        ArmLive.testNow = Market.today().atTime(15, 15).atZone(com.optionslab.engine.IST)
        watch()
        assertEquals("square_off", view().closed.single().why)
        assertEquals(0, held())
    }

    @Test fun aCarriedTradeRestsItsStopAsOneGttMovedInPlaceAndDeletedBeforeTheExit() {
        val ref = "night"
        ArmLive.entered(ask(com.optionslab.engine.orb.NightRules.SOURCE, ref))
        assertEquals("NRML", kite.placed.single().form["product"])
        assertEquals("no SL order beside it", 1, kite.placed.size)
        val gid = kite.gtts.keys.single()
        assertEquals("gtt:$gid", view().open.single().stopOrderId)
        ArmLive.level(ref, 185.0)
        watch()
        assertTrue(kite.requests.any { it.method == "PUT" && it.path == "/gtt/triggers/$gid" })
        assertEquals("still one", 1, kite.gtts.size)
        legs[ref] = legs.getValue(ref).first to 0                 // 09:16 next session: the paper trade sold
        watch()
        assertTrue("the GTT is gone before the sell", kite.gtts.isEmpty())
        assertEquals(0, nrmlHeld())
    }

    @Test fun aFiredGttThatSoldEverythingWasTheStop() {
        ArmLive.entered(ask(com.optionslab.engine.orb.NightRules.SOURCE, "fired"))
        val gid = kite.gtts.keys.single()
        watch()
        kite.quote("NFO:$sym", 165.0, 164.95, 165.05)
        val oid = kite.triggerGtt(gid)                              // its LIMIT 161.50 is under the bid: it fills
        assertEquals("COMPLETE", kite.order(oid).status)
        watch()
        assertEquals("stop", view().closed.single().why)
        assertEquals("no sell beside it", 1, kite.placed.size)
        assertEquals("flat, never short", 0, nrmlHeld())
    }

    @Test fun aFiredGttsWorkingOrderIsTheOnlyExitAndOnceRefusedTheAppSells() {
        val ref = "fired2"
        ArmLive.entered(ask(com.optionslab.engine.orb.NightRules.SOURCE, ref))
        val gid = kite.gtts.keys.single()
        watch()
        kite.quote("NFO:$sym", 120.0, 119.95, 120.05)
        val oid = kite.triggerGtt(gid)                              // a fast fall: its LIMIT 161.50 rests unfilled
        assertEquals("OPEN", kite.order(oid).status)
        legs[ref] = legs.getValue(ref).first to 0                 // the paper trade sold
        watch(); watch()
        assertEquals("its working order covers it: nothing more sent, no new stop", 1, kite.placed.size)
        assertEquals(30, nrmlHeld())
        kite.reject(oid)
        watch()
        assertEquals(0, nrmlHeld())
        assertEquals("paper_exit", view().closed.single().why)
    }

    @Test fun twoArmsOnOneOptionEachRestTheirOwnStopNeverMoreThanHeld() {
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "d"))
        ArmLive.entered(ask(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, "b"))
        assertEquals(60, held())
        val stops = kite.orders.values.filter { it.type == "SL" && it.working }
        assertEquals(2, stops.size)
        assertEquals("exactly what is held", 60, stops.sumOf { it.qty })
        // A third resting exit would pass what is held: an arm's stop that went missing is not rested beside Boss's own sell.
        kite.orders.getValue(stops.first().id).status = "CANCELLED"
        runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 30, 30, "MIS", "LIMIT", 260.0, 0.05,
            "NFO", "manual"), exit = true) }
        watch()
        assertEquals("never more resting sells than held", 60, kite.orders.values.filter { it.working && it.side == "SELL" }.sumOf { it.qty })
    }

    // ---- restart, record ---------------------------------------------------------------------------------------------------

    @Test fun aRestartRestoresTheLiveTradeAndItsWatchFirst() {
        ArmLive.entered(ask(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, "rs"))
        ArmLive.wipe()                                             // the process died: memory gone, the book on disk
        assertFalse(ArmLive.holdingHint)
        assertTrue("positions first finds it", runBlocking { PositionsFirst.restore() })
        assertTrue(ArmLive.holdingHint)
        assertEquals(1, view().open.size)
        assertTrue(FastPath.ARMS_LIVE.isNotBlank())
        ArmLive.testNow = Market.today().atTime(15, 16).atZone(com.optionslab.engine.IST)
        watch()
        assertEquals("watched after the restart", "square_off", view().closed.single().why)
    }

    @Test fun eachLiveTradeIsRecordedAndLabelledLiveApartFromThePaperBook() {
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "a"))
        kite.quote("NFO:$sym", 220.0, 219.95, 220.05)
        watch()
        legs["a"] = legs.getValue("a").first to 0
        watch()
        val (n, net) = view().record(com.optionslab.engine.orb.DonchianArm.SOURCE)
        assertEquals(1, n)
        assertTrue("its own net after charges: $net", net > 500 && net < 600)
        assertEquals(0 to 0.0, view().record(com.optionslab.engine.orb.OovArm.SOURCE))
        assertTrue("its paper book holds nothing of it", PassedArmsHost.view.value.all.isEmpty())
        assertTrue("its orders are labelled live", runBlocking { Strategies.owners() }.values.any { it == "R51 Donchian 20 · live entry" })
        assertTrue(ArmLive.diagLine().startsWith("Arms live: 0 open"))
    }
}
