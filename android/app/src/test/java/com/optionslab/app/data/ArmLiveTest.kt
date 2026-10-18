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
        FillSlippage.resetForTest()                           // no measured slippage left by another test (final review F4)
        if (com.optionslab.app.ira.Autopilot.enabled()) { com.optionslab.app.ira.Autopilot.resetForTest(); com.optionslab.app.ira.Autopilot.init(context); com.optionslab.app.ira.Autopilot.testLive = false }
        // Every arm out of shadow (Boss's PIN), so these tests are about the live orders; Solo (midday) cannot leave it (not proven).
        for (a in LiveMirror.ARMS) if (a.key != LiveMirror.SOLO_MIDDAY) runBlocking { ArmLive.setShadow(a.key, false, pinConfirmed = true, log = false) }
        for (f in LiveMirror.ARMS.map { it.family }.distinct())
            ArmLive.register(f) { legs.entries.filter { e -> LiveMirror.spec(e.value.first)?.family == f }.map { ArmLive.PaperLeg(it.key, it.value.first, it.value.second) } }
        kite.requests.clear()
    }

    @After fun down() {
        ArmLive.testSync = false
        ArmLive.testNow = null
        ArmLive.testReadFails = null; ArmLive.testAccountFails = false; ArmLive.testSecurityFails = false; ArmLive.testHeard = null
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
        // Solo (midday) stays in shadow (Jarvis's Solo, not proven): its own test below.
        val intraday = LiveMirror.ARMS.filter { it.exchange == "NFO" && it.product == "MIS" && it.key != LiveMirror.SOLO_MIDDAY }
        assertTrue(intraday.size >= 11)
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
            // -15% of the paper fill on the live fill (the same points for the ORB family: 200 -> 170 both ways), SL with its limit; a
            // close-judged arm (Hero, the Hero ticket) rests only its -80% disaster stop (40, limit 2 under it).
            val want = if (spec.closeStop) mapOf("transaction_type" to "SELL", "order_type" to "SL", "trigger_price" to "40.00", "price" to "38.00", "quantity" to "30")
                else mapOf("transaction_type" to "SELL", "order_type" to "SL", "trigger_price" to "170.00", "price" to "161.50", "quantity" to "30")
            assertEquals(spec.key, want, form(stop, "transaction_type", "order_type", "trigger_price", "price", "quantity"))
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
        assertEquals("on again only with the PIN", "Turning ${LiveMirror.spec(key)!!.label}'s live trading on needs your PIN. Nothing changed.",
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
        // Behaviour (review D8): whatever key Jarvis's own ideas would carry - its news picks, the forge's, the grammar arm's - the
        // live path sends nothing, records no shadow trade and notes nothing; no mirrored arm is one of them.
        for (k in listOf("jarvis", "news", "forge", "grammar", com.optionslab.app.ira.IraNewsTrades::class.java.simpleName)) {
            ArmLive.entered(ArmLive.Ask(k, "j:$k", contract, 1, 30, 200.0))
            assertNull(LiveMirror.spec(k))
        }
        assertTrue(kite.requests.isEmpty())
        assertTrue(view().open.isEmpty() && view().notMirrored.isEmpty() && view().refused.isEmpty())
        assertTrue(LiveMirror.ARMS.none { it.family in setOf("jarvis", "news", "forge") })
        fun src(f: String) = listOf(java.io.File("src/main/java/com/optionslab/app/$f"), java.io.File("app/src/main/java/com/optionslab/app/$f")).first { it.isFile }.readText()
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
        // Review C4: the stop is MODIFIED down to what is left (never cancelled and left bare), and it is the same order.
        val sid = view().open.single().stopOrderId!!
        assertEquals(30, kite.order(sid).qty)
        assertTrue(kite.order(sid).working)
        assertEquals("never more resting than held", 30, kite.orders.values.filter { it.working && it.side == "SELL" }.sumOf { it.qty - it.filled })
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
        watch()
        // Review C1: a fired GTT whose order did not fill never blocks the exit - its order is cancelled first, then a MARKET sell.
        assertEquals("CANCELLED", kite.order(oid).status)
        val sell = kite.placed.last()
        assertEquals(mapOf("transaction_type" to "SELL", "order_type" to "MARKET", "quantity" to "30"), form(sell, "transaction_type", "order_type", "quantity"))
        assertEquals("flat, never short", 0, nrmlHeld())
        assertEquals("paper_exit", view().closed.single().why)
        watch()
        assertEquals("nothing more", 2, kite.placed.size)
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

    // ---- the Shadow switch (18 Oct, Boss: "add a shadow button, so that even i move to live, until is on, it will not trade") ----

    private val donchian = com.optionslab.engine.orb.DonchianArm.SOURCE

    @Test fun everyArmStartsInShadowAndAFreshBookSendsNothing() {
        ArmLive.wipe(); java.io.File(context.noBackupFilesDir, "arm_live.vault").delete()
        SecurePrefs.put(ArmLive.K_SHADOW, null)
        runBlocking { ArmLive.refresh() }
        for (a in LiveMirror.ARMS) assertTrue(a.key, view().shadowOn(a.key))
        ArmLive.entered(ask(donchian, "fresh"))
        assertTrue("nothing sent in shadow, with Live, AI trades and Trade live all on", kite.placed.isEmpty())
        assertEquals(1, view().openShadow.size)
    }

    @Test fun inShadowNothingIsSentAndEachWouldBeTradeIsRecordedWithItsShadowPnl() {
        assertTrue(ArmLive.done(runBlocking { ArmLive.setShadow(donchian, true, pinConfirmed = false) }))   // on: no PIN
        assertTrue(view().shadowOn(donchian))
        ArmLive.entered(ask(donchian, "sh"))
        assertTrue("nothing sent", kite.placed.isEmpty())
        val p = view().openShadow.single()
        assertEquals("a MARKET buy pays Zerodha's ask (final review F4)", 200.05, p.entry, 1e-9)
        assertEquals(170.0, p.stopTrigger!!, 1e-9)
        assertTrue(view().openReal.isEmpty())
        watch()                                                    // seen open on paper
        kite.quote("NFO:$sym", 168.0, 167.95, 168.05)              // through its stop, not through its SL's limit (161.50)
        watch()
        val c = view().shadowClosed.single()
        assertEquals("stop", c.why)
        assertEquals("filled as the resting SL would: at its trigger less BANKNIFTY's 0.16% half-spread", 169.70, c.exit!!, 1e-9)
        assertTrue("still nothing sent", kite.placed.isEmpty())
        val r = view().shadowRecord(donchian)
        assertEquals(1, r.trades)
        assertTrue("-900 and the charges: ${r.net}", r.net < -900 && r.net > -1_000)
        assertEquals(0.0, r.winRate, 1e-9)
        assertEquals("its live record stays apart", 0 to 0.0, view().record(donchian))
        assertTrue(ArmLive.armLine(donchian).contains("in shadow: 1 trade"))
    }

    @Test fun aShadowTradeFollowsItsPaperExitAtTheMarket() {
        runBlocking { ArmLive.setShadow(donchian, true, pinConfirmed = false) }
        ArmLive.entered(ask(donchian, "sx"))
        watch()
        kite.quote("NFO:$sym", 230.0, 229.95, 230.05)
        legs["sx"] = donchian to 0
        watch()
        val c = view().shadowClosed.single()
        assertEquals("paper_exit", c.why); assertEquals("a MARKET sale gets the bid", 229.95, c.exit!!, 1e-9)
        assertEquals(1.0, view().shadowRecord(donchian).winRate, 1e-9)
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun shadowOffNeedsThePinOnIsFreeAndSoloStaysInShadow() {
        val key = com.optionslab.engine.orb.OovArm.SOURCE
        runBlocking { ArmLive.setShadow(key, true, pinConfirmed = false) }
        val said = runBlocking { ArmLive.setShadow(key, false, pinConfirmed = false) }
        assertFalse(said, ArmLive.done(said)); assertTrue(said, said.contains("PIN"))
        assertTrue(view().shadowOn(key))
        assertTrue(ArmLive.done(runBlocking { ArmLive.setShadow(key, false, pinConfirmed = true) }))
        assertFalse(view().shadowOn(key))
        val solo = runBlocking { ArmLive.setShadow(LiveMirror.SOLO_MIDDAY, false, pinConfirmed = true) }
        assertFalse(solo, ArmLive.done(solo)); assertTrue(solo, solo.contains("stays in shadow"))
        assertNotNull("the reason is shown", ArmLive.soloWhy())
        assertTrue(view().shadowOn(LiveMirror.SOLO_MIDDAY))
        val log = view().log.joinToString("\n")
        assertTrue(log, log.contains("Shadow ON (you)") && log.contains("Shadow OFF (you)"))
    }

    @Test fun eachShadowChangeIsInTheChangeLogAndAnUndoThatLeavesShadowNeedsThePin() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", com.optionslab.app.ira.Autopilot.enabled())
        val key = com.optionslab.engine.orb.OovArm.SOURCE
        runBlocking { ArmLive.setShadow(key, true, pinConfirmed = false) }
        fun entries() = com.optionslab.app.ira.Autopilot.book.value.entries.filter { it.change.kind == com.optionslab.ira.AutoChange.Kind.ARM_SHADOW &&
            it.change.target == "armshadow:$key" }
        val deadline = System.currentTimeMillis() + 10_000
        while (entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val on = entries().single()
        assertTrue(com.optionslab.app.ira.Autopilot.undoNeedsPin(on.id))
        assertEquals(com.optionslab.app.ira.Autopilot.PIN_UNDO, com.optionslab.app.ira.Autopilot.undo(on.id))
        assertTrue("nothing changed without the PIN", view().shadowOn(key))
        assertTrue(com.optionslab.app.ira.Autopilot.undo(on.id, pinConfirmed = true).startsWith("Undone"))
        assertFalse(view().shadowOn(key))
        // Trade live's own entry is logged too.
        runBlocking { ArmLive.setLive(key, false, pinConfirmed = false) }
        val d2 = System.currentTimeMillis() + 10_000
        fun live() = com.optionslab.app.ira.Autopilot.book.value.entries.filter { it.change.kind == com.optionslab.ira.AutoChange.Kind.ARM_LIVE && it.change.target == "armlive:$key" }
        while (live().isEmpty() && System.currentTimeMillis() < d2) Thread.sleep(20)
        assertEquals(1, live().size)
    }

    @Test fun shadowOrTradeLiveTurnedOnOrOffWithALiveTradeOpenLeavesItsStopAndExitsRunning() {
        ArmLive.entered(ask(donchian, "op"))
        val sid = view().openReal.single().stopOrderId!!
        runBlocking { ArmLive.setShadow(donchian, true, pinConfirmed = false); ArmLive.setLive(donchian, false, pinConfirmed = false) }
        ArmLive.level("op", 190.0)
        watch()
        assertEquals("its stop still moves with the lock", 190.0, kite.order(sid).trigger, 1e-9)
        legs["op"] = donchian to 0
        watch()
        assertEquals("paper_exit", view().closed.single().why)
        assertEquals("flat, never short", 0, held())
        assertEquals("CANCELLED", kite.order(sid).status)
    }

    @Test fun switchesAndShadowSurviveARestartAndALostBookFile() {
        val oov = com.optionslab.engine.orb.OovArm.SOURCE
        val breadth = com.optionslab.engine.orb.BreadthDonchianArm.SOURCE
        runBlocking { ArmLive.setLive(oov, false, pinConfirmed = false); ArmLive.setShadow(donchian, true, pinConfirmed = false) }
        fun same() {
            assertFalse(view().on(oov)); assertTrue(view().on(donchian))
            assertTrue(view().shadowOn(donchian)); assertFalse(view().shadowOn(breadth))
        }
        ArmLive.wipe()
        assertFalse("loading until read", view().loaded)
        runBlocking { ArmLive.refresh() }
        same()
        // The file gone (a reinstall's restore, a damaged disk): the second copy in the settings, never fresh defaults.
        java.io.File(context.noBackupFilesDir, "arm_live.vault").delete()
        ArmLive.wipe(); runBlocking { ArmLive.refresh() }
        same()
    }

    @Test fun aDamagedBookIsSetAsideItsSwitchesKeptAndItsOpenLiveTradeTakenBackFromZerodha() {
        val breadth = com.optionslab.engine.orb.BreadthDonchianArm.SOURCE
        runBlocking { ArmLive.setLive(breadth, false, pinConfirmed = false) }
        ArmLive.entered(ask(donchian, "dmg"))
        val sid = view().openReal.single().stopOrderId!!
        java.io.File(context.noBackupFilesDir, "arm_live.vault").writeBytes(ByteArray(64) { 7 })
        ArmLive.wipe()
        runBlocking { ArmLive.refresh() }
        assertFalse("its switches from the second copy", view().on(breadth))
        assertTrue(view().openReal.isEmpty())
        assertTrue("the watch is due for it although nothing is held in the book", ArmLive.recoverHint && ArmLive.lookDue && !ArmLive.holdingHint)
        // Positions first at the start: taken back before any other work (review: no scheduled look ran for it before).
        assertTrue(runBlocking { ArmLive.holding() })
        assertFalse(ArmLive.recoverHint)
        val p = view().openReal.single()
        assertEquals(donchian, p.key)
        assertEquals(30, p.qty)
        assertEquals("its own stop still resting is kept", sid, p.stopOrderId)
        assertTrue(ArmLive.holdingHint)
        assertEquals("no second stop", 1, kite.orders.values.count { it.working && it.type == "SL" })
    }

    // ---- the safety and disaster stops -------------------------------------------------------------------------------------

    @Test fun aCarriedArmWithNoPremiumStopRestsASafetyGtt30PercentBelowTheFillThatNeverMovesDown() {
        val ref = "nsafe"
        ArmLive.entered(ask(com.optionslab.engine.orb.NightRules.SOURCE, ref, stop = null))
        val g = kite.gtts.values.single()
        assertEquals(140.0, g.getJSONObject("condition").getJSONArray("trigger_values").getDouble(0), 1e-9)
        assertEquals(ArmLive.SAFETY, view().open.single().stopKind)
        assertTrue(view().log.joinToString().contains("safety stop"))
        assertEquals("no SL order beside the GTT", 1, kite.placed.size)
        ArmLive.level(ref, 120.0)                                  // a lower paper level: never down
        watch()
        assertTrue(kite.requests.none { it.method == "PUT" })
        ArmLive.level(ref, 175.0)                                  // the arm's own level above it: up, and labelled the arm's stop
        watch()
        assertEquals(175.0, view().open.single().stopTrigger!!, 1e-9)
        assertEquals(ArmLive.OWN, view().open.single().stopKind)
    }

    @Test fun anIntradayArmWithNoPremiumStopRestsASafetySlOrder() {
        ArmLive.entered(ask(com.optionslab.engine.orb.Solo2.SOURCE, "s2safe", stop = null))
        val stop = kite.placed.last()
        assertEquals(mapOf("order_type" to "SL", "trigger_price" to "140.00", "transaction_type" to "SELL"), form(stop, "order_type", "trigger_price", "transaction_type"))
        // Its own exit takes it out first: never two resting exits.
        watch(); legs["s2safe"] = legs.getValue("s2safe").first to 0; watch()
        assertEquals(0, held()); assertEquals("paper_exit", view().closed.single().why)
    }

    @Test fun aCloseJudgedArmRestsOnlyItsDisasterStopAndIgnoresItsPaperLevels() {
        ArmLive.entered(ask(com.optionslab.engine.orb.HeroRules.ARM.source, "hero", stop = 80.0))
        val stop = kite.placed.last()
        assertEquals(mapOf("order_type" to "SL", "trigger_price" to "40.00"), form(stop, "order_type", "trigger_price"))
        assertEquals(ArmLive.DISASTER, view().open.single().stopKind)
        ArmLive.level("hero", 150.0)
        watch()
        assertTrue("its close-judged trail never becomes a tick stop", kite.requests.none { it.method == "PUT" })
    }

    // ---- order-safety review fixes ----------------------------------------------------------------------------------------

    @Test fun aFiredGttThroughItsLegPriceIsCancelledAndSoldAtMarket() {
        ArmLive.entered(ask(com.optionslab.engine.orb.NightRules.SOURCE, "run"))
        val gid = kite.gtts.keys.single()
        watch()
        kite.quote("NFO:$sym", 120.0, 119.95, 120.05)
        val oid = kite.triggerGtt(gid)
        assertEquals("OPEN", kite.order(oid).status)
        watch()
        assertEquals("CANCELLED", kite.order(oid).status)
        assertEquals("stop_run_through", view().closed.single().why)
        assertEquals(0, nrmlHeld())
    }

    @Test fun aPartSoldByHandShrinksTheRestingStopSoItNeverTurnsTheLongShort() {
        ArmLive.entered(ask(donchian, "hand", qty = 60))
        val sid = view().open.single().stopOrderId!!
        assertEquals(60, kite.order(sid).qty)
        watch()
        kite.positions["NFO:$sym:MIS"] = 30 to 200.0               // Boss sold one of the two lots in Kite
        watch()
        assertEquals(30, kite.order(sid).qty)
        assertTrue(kite.order(sid).working)
        assertEquals(30, view().open.single().qty)
        assertEquals("closed_at_zerodha", view().closed.single().why)
        kite.positions["NFO:$sym:MIS"] = 0 to 0.0                  // and then the rest
        watch()
        assertFalse("taken out on a flat position", kite.order(sid).working)
        assertTrue(view().open.isEmpty())
    }

    @Test fun noLiveEntryAgainstWhatZerodhaHoldsTheOtherWay() {
        kite.positions["NFO:$sym:MIS"] = -30 to 200.0
        ArmLive.entered(ask(donchian, "against"))
        assertTrue(kite.placed.isEmpty())
        assertEquals(true, view().refused[donchian]?.contains("other way"))
    }

    @Test fun noLiveEntryAt1515OrForAPaperTradeAlreadyClosed() {
        ArmLive.testNow = Market.today().atTime(15, 15).atZone(com.optionslab.engine.IST)
        ArmLive.entered(ask(donchian, "late"))
        assertTrue(kite.placed.isEmpty()); assertEquals(true, view().refused[donchian]?.contains("too late"))
        ArmLive.testNow = AutomationSupport.earlierToday()
        ArmLive.closed("gone", "stop")
        ArmLive.entered(ask(donchian, "gone"))
        assertTrue(kite.placed.isEmpty()); assertEquals(true, view().refused[donchian]?.contains("already closed"))
        val (n, why) = view().notMirroredToday(donchian, Market.today())
        assertEquals(2, n); assertTrue(why!!.contains("already closed"))
    }

    @Test fun aCarriedOptionIsSquaredOffAt1505OnItsExpiryDay() {
        val today = Market.today()
        val s = "BANKNIFTY26OCTX52000CE"
        kite.instruments += FakeKite.Ins(22_000_900, s, "BANKNIFTY", today, 52_000.0, "CE", 30)
        kite.quote("NFO:$s", 200.0, 199.95, 200.05)
        java.io.File(context.filesDir, "kite_instruments.json").delete(); runBlocking { Broker.instruments() }
        val c = Paper.Contract("BANKNIFTY-TEST-X", "BANKNIFTY", today, 52_000.0, Right.CE, 30, "NSE_FO|X")
        ArmLive.testNow = today.atTime(10, 0).atZone(com.optionslab.engine.IST)
        ArmLive.entered(ask(com.optionslab.engine.orb.NightRules.SOURCE, "exp", c = c))
        assertEquals(1, view().openReal.size)
        ArmLive.testNow = today.atTime(15, 5).atZone(com.optionslab.engine.IST)
        watch()
        assertEquals("square_off", view().closed.single().why)
        assertEquals(0, kite.positions["NFO:$s:NRML"]?.first ?: 0)
        assertTrue("its GTT went first", kite.gtts.values.none { it.optString("status", "active") == "active" })
    }

    @Test fun aPaperTradeTheGuardRefusesIsRecordedAsNotMirroredOnItsRow() {
        // The account guard's open-positions cap (not raised): three positions already open.
        for (i in 1..3) kite.position("OTHER$i", 30, 100.0, "MIS")
        ArmLive.entered(ask(donchian, "capped"))
        assertTrue(kite.placed.isEmpty())
        val (n, why) = view().notMirroredToday(donchian, Market.today())
        assertEquals(1, n); assertTrue(why!!, why.contains("account guard"))
    }

    // ---- Jarvis -------------------------------------------------------------------------------------------------------------

    @Test fun jarvisSaysHowAnArmIsDoingInShadowAndLiveLabelledApart() {
        val night = LiveMirror.spec(com.optionslab.engine.orb.NightRules.SOURCE)!!.label
        val q = "how is $night doing in shadow?"
        assertTrue(LiveMirror.shadowAsked(q))
        val a = ArmLive.talk(q)!!
        assertTrue(a, a.startsWith("$night in shadow:"))
        assertTrue(a, a.contains("Live (real orders): no live trade yet"))
        assertNull(ArmLive.talk("how is the weather in shadow"))
        assertFalse(LiveMirror.shadowAsked("turn shadow off for $night"))
    }

    // ---- review fixes of the review (18 Oct) ------------------------------------------------------------------------------

    @Test fun aDamagedBooksCarriedTradeKeepsItsOwnGttAndGetsNoSecond() {
        ArmLive.entered(ask(com.optionslab.engine.orb.NightRules.SOURCE, "dmgn"))
        val gid = kite.gtts.keys.single()
        java.io.File(context.noBackupFilesDir, "arm_live.vault").writeBytes(ByteArray(64) { 9 })
        ArmLive.wipe()
        runBlocking { ArmLive.refresh(); ArmLive.holding() }
        assertEquals("gtt:$gid", view().openReal.single().stopOrderId)
        assertEquals("one GTT, never two", 1, kite.gtts.values.count { it.optString("status", "active") == "active" })
    }

    @Test fun aGttAlreadyRestingOnTheContractCountsSoNoSecondOneRestsBesideIt() {
        // Boss's own GTT already protects the whole 30 held: the arm's stop is watched by the app, never a second GTT.
        kite.positions["NFO:$sym:NRML"] = 30 to 200.0
        runBlocking { Broker.placeGtt(com.optionslab.engine.Kite.protect(com.optionslab.engine.Kite.Spec("NFO", sym, 0L, 30, 0.05), "NRML", 30, 200.0, 150.0, null).first!!) }
        kite.requests.clear()
        ArmLive.entered(ask(com.optionslab.engine.orb.NightRules.SOURCE, "cov"))
        assertEquals(60, nrmlHeld())
        assertEquals("each GTT for its own 30", listOf(30, 30), kite.gtts.values.map { it.getJSONArray("orders").getJSONObject(0).getInt("quantity") }.sorted())
        assertTrue(kite.gtts.values.sumOf { it.getJSONArray("orders").getJSONObject(0).getInt("quantity") } <= nrmlHeld())
    }

    @Test fun aPositionBookLaggingASecondFillNeverClosesTheOlderTrade() {
        ArmLive.entered(ask(donchian, "a1"))
        watch()
        ArmLive.entered(ask(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, "b1"))
        assertEquals(60, held())
        kite.positions["NFO:$sym:MIS"] = 30 to 200.0             // the book still shows only the first fill
        watch()
        assertTrue("nothing booked closed", view().closed.isEmpty())
        assertEquals(2, view().openReal.size)
        kite.positions["NFO:$sym:MIS"] = 60 to 200.0
        watch()
        assertEquals(2, view().openReal.size)
        assertTrue(view().openReal.all { it.heldSeen })
    }

    @Test fun aHandSaleOfOneTradesShareLeavesTheSiblingsStopResting() {
        ArmLive.entered(ask(donchian, "a2"))
        ArmLive.entered(ask(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, "b2"))
        watch()
        val bStop = view().openReal.single { it.ref == "b2" }.stopOrderId!!
        kite.positions["NFO:$sym:MIS"] = 30 to 200.0             // Boss sold 30 by hand: the oldest trade's (the paper rule)
        watch()
        assertEquals("closed_at_zerodha", view().closed.single().why)
        assertEquals("a2", view().closed.single().ref)
        assertTrue("the sibling keeps its stop", kite.order(bStop).working)
        assertEquals(30, kite.order(bStop).qty)
        assertEquals("never more resting than held", 30, kite.orders.values.filter { it.working && it.side == "SELL" }.sumOf { it.qty - it.filled })
    }

    @Test fun aShadowStopReachedIsBookedBeforeThePaperExitAndNothingIsBookedWithoutAPrice() {
        runBlocking { ArmLive.setShadow(donchian, true, pinConfirmed = false) }
        ArmLive.entered(ask(donchian, "shs"))
        watch()
        kite.quote("NFO:$sym", 165.0, 164.95, 165.05)
        legs["shs"] = donchian to 0                                // the paper trade closed in the same minute
        watch()
        assertEquals("stop", view().shadowClosed.single().why)
        assertEquals(169.70, view().shadowClosed.single().exit!!, 1e-9)
        // No price at all (not logged in, nothing seen): nothing booked, never at the price paid.
        ArmLive.entered(ask(donchian, "shn"))
        SecurePrefs.put("kite.accessToken", null)                   // logged out of Zerodha
        ArmLive.forgetMemoryForTest()
        legs["shn"] = donchian to 0
        watch()
        assertEquals(1, view().openShadow.size)
    }

    @Test fun aPartPaperSaleAgainstAFiredGttCancelsItsOrderSellsThePartAndRestsANewStopForTheRest() {
        val ref = "pfired"
        ArmLive.entered(ask(com.optionslab.engine.orb.NightRules.SOURCE, ref, qty = 60))
        val gid = kite.gtts.keys.single()
        watch()
        kite.quote("NFO:$sym", 150.0, 149.95, 150.05)
        val oid = kite.triggerGtt(gid)                              // fired, its LIMIT 161.50 above the bid: unfilled
        assertEquals("OPEN", kite.order(oid).status)
        kite.quote("NFO:$sym", 163.0, 162.95, 163.05)              // back above its limit (not through it)
        legs[ref] = legs.getValue(ref).first to 30                  // the paper trade sold half
        watch()
        assertEquals("CANCELLED", kite.order(oid).status)
        assertEquals("the part sold, never blocked by the fired order", 30, nrmlHeld())
        assertEquals(30, view().open.single().qty)
        // The price (163) is under its stop (170): no stop can rest there, so the rest is sold as stopped on the next look.
        assertNull(view().open.single().stopOrderId)
        watch()
        assertEquals(0, nrmlHeld())
        assertTrue(view().open.isEmpty())
        assertEquals(setOf("paper_part", "stop"), view().closed.map { it.why }.toSet())
    }

    @Test fun aWholeExitWhoseStopPartFilledSellsTheRestInTheSameLook() {
        ArmLive.entered(ask(donchian, "pf", qty = 60))
        val sid = view().open.single().stopOrderId!!
        watch()
        kite.fillPart(sid, 30, 170.0)                              // half the stop filled at the exchange
        legs["pf"] = donchian to 0
        watch()
        assertEquals("flat in one look", 0, held())
        assertEquals(setOf("stop", "paper_exit"), view().closed.map { it.why }.toSet())
        assertEquals(60, view().closed.sumOf { it.qty })
        assertTrue(view().open.isEmpty())
    }

    @Test fun aStopForLessThanTheTradeThatFillsLeavesTheRestWithANewStop() {
        ArmLive.entered(ask(donchian, "small", qty = 60))
        val sid = view().open.single().stopOrderId!!
        watch()
        kite.order(sid).qty = 30                                    // the stop rests for 30 of the 60 (shrunk earlier)
        kite.trigger(sid, 170.0)                                    // and fills, all of it
        assertEquals(30, held())
        watch()
        assertEquals("stop", view().closed.single().why); assertEquals(30, view().closed.single().qty)
        watch()
        val rest = view().open.single()
        assertEquals(30, rest.qty)
        assertTrue("a new stop for the rest", rest.stopOrderId != null && rest.stopOrderId != sid)
        assertEquals(30, kite.order(rest.stopOrderId!!).qty)
        watch()
        assertEquals("booked once", 1, view().closed.size)
    }

    @Test fun aFiredGttForLessThanTheTradeIsBookedOnceAndTheRestGetsANewGtt() {
        ArmLive.entered(ask(com.optionslab.engine.orb.NightRules.SOURCE, "gsmall", qty = 60))
        val gid = kite.gtts.keys.single()
        watch()
        kite.gtts.getValue(gid).getJSONArray("orders").getJSONObject(0).put("quantity", 30)   // the GTT is for 30 of the 60
        kite.quote("NFO:$sym", 165.0, 164.95, 165.05)
        kite.triggerGtt(gid)                                        // its LIMIT 161.50 fills: 30 sold
        assertEquals(30, nrmlHeld())
        kite.quote("NFO:$sym", 190.0, 189.95, 190.05)
        watch(); watch()
        assertEquals("booked once", 1, view().closed.size)
        assertEquals(30, view().closed.single().qty)
        assertEquals(30, view().open.single().qty)
        val active = kite.gtts.values.filter { it.optString("status", "active") == "active" }
        assertEquals(1, active.size)
        assertEquals(30, active.single().getJSONArray("orders").getJSONObject(0).getInt("quantity"))
    }

    // ---- the final review (18 Oct) ----------------------------------------------------------------------------------------

    /** F1: a book that cannot be read at the start is read again by the watch, and its open live trade is watched and exits. */
    @Test fun anUnreadableBookIsReadAgainByEveryLaneAndItsOpenLiveTradeIsWatchedAndExits() {
        val ref = "unread"
        ArmLive.entered(ask(donchian, ref))
        assertEquals(30, held())
        watch()                                                    // seen open on paper
        ArmLive.wipe()                                             // a restart: the memory gone, the book on disk
        ArmLive.testReadFails = java.util.concurrent.atomic.AtomicInteger(1)
        assertTrue("held while it cannot be read (positions first)", runBlocking { ArmLive.holding() })
        assertTrue(view().unreadable)
        assertFalse(ArmLive.holdingHint)
        assertTrue("every lane and keep-alive keeps coming for it", ArmLive.recoverHint && ArmLive.lookDue)
        legs[ref] = donchian to 0                                  // the paper trade closes meanwhile
        watch()                                                    // within 5 s of the failed read: not hammered
        assertTrue(view().unreadable)
        ArmLive.testNow = ArmLive.testNow!!.plusSeconds(10)
        watch()                                                    // read now: the trade is watched and follows its paper exit
        assertFalse(view().unreadable)
        assertEquals("paper_exit", view().closed.single().why)
        assertEquals("flat, never short", 0, held())
        assertFalse(ArmLive.recoverHint)
        assertTrue(kite.placed.any { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" })
    }

    /** F2: Shadow turned on while the entry waits in the entry door: the door asks again and nothing is sent. */
    @Test fun shadowTurnedOnWhileTheEntryWaitsInTheDoorSendsNothing() {
        val gate = Broker::class.java.getDeclaredField("entryGate").apply { isAccessible = true }.get(null)
        val door = gate.javaClass.getDeclaredField("door").apply { isAccessible = true }.get(gate) as kotlinx.coroutines.sync.Mutex
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        assertTrue("the door held, as another entry being sent would", door.tryLock())
        val t = Thread { ArmLive.entered(ask(donchian, "door")) }  // its checks pass, then it waits at the door
        try {
            t.start()
            val deadline = System.currentTimeMillis() + 20_000
            while (heard.none { it.startsWith("door|$donchian|door") } && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue("the entry reached the door", heard.any { it.startsWith("door|$donchian|door") })
            runBlocking { ArmLive.setShadow(donchian, true, pinConfirmed = false) }
        } finally { door.unlock() }
        t.join(20_000)
        assertFalse(t.isAlive)
        assertTrue("nothing sent", kite.placed.isEmpty())
        assertTrue(view().open.isEmpty())
        assertEquals(view().refused[donchian], true, view().refused[donchian]?.contains("in shadow"))
    }

    /** F2: the door's own look, from memory: the app in Paper, AI trades off, Trade live off or Shadow on each refuse. */
    @Test fun theDoorAsksLiveAiTradesTradeLiveAndShadowAgain() {
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.BUY, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", LiveMirror.TAG)
        runBlocking { ArmLive.refresh() }
        assertNull(Broker.entryRecheck(o, "arm:$donchian"))
        assertEquals("refused: a mirrored entry with no arm", Broker.entryRecheck(o, null))
        IraNewsTrades.paperFirst = true
        assertEquals("refused: \"AI trades go live\" is off", Broker.entryRecheck(o, "arm:$donchian"))
        IraNewsTrades.paperFirst = false
        AutomationSupport.liveSettings(live = false)
        assertEquals("refused: the app is in Paper", Broker.entryRecheck(o, "arm:$donchian"))
        AutomationSupport.liveSettings()
        runBlocking { ArmLive.setLive(donchian, false, pinConfirmed = false) }
        assertEquals(true, Broker.entryRecheck(o, "arm:$donchian")?.contains("Trade live"))
        runBlocking { ArmLive.setLive(donchian, true, pinConfirmed = true); ArmLive.setShadow(donchian, true, pinConfirmed = false) }
        assertEquals(true, Broker.entryRecheck(o, "arm:$donchian")?.contains("in shadow"))
        // Another bot's entry is not asked about the arms' switches.
        assertNull(Broker.entryRecheck(o.copy(tag = "iraalgo")))
    }

    /** F3: an account stop rule or a security check that cannot be read refuses the entry, never lets it through. */
    @Test fun anAccountStopOrASecurityCheckThatCannotBeReadRefusesTheEntry() {
        ArmLive.testAccountFails = true
        ArmLive.entered(ask(donchian, "acct"))
        assertTrue(kite.placed.isEmpty())
        assertEquals(view().refused[donchian], true, view().refused[donchian]?.contains("account stop could not be checked"))
        ArmLive.testAccountFails = false
        ArmLive.testSecurityFails = true
        ArmLive.entered(ask(donchian, "sec"))
        assertTrue(kite.placed.isEmpty())
        assertEquals(view().refused[donchian], true, view().refused[donchian]?.contains("security check"))
        ArmLive.testSecurityFails = false
        ArmLive.entered(ask(donchian, "ok"))
        assertEquals("both readable: it goes", 2, kite.placed.size)
    }

    /** F4: a shadow trade bought at the ask, and its stop judged on the minute's low: a wick through it fills it. */
    @Test fun aShadowStopIsJudgedOnTheMinutesLowNotJustThePriceAtTheLook() {
        runBlocking { ArmLive.setShadow(donchian, true, pinConfirmed = false) }
        ArmLive.entered(ask(donchian, "wick"))
        assertEquals(200.05, view().openShadow.single().entry, 1e-9)
        watch()                                                    // a look: its moment noted
        val t0 = ArmLive.testNow!!
        val minute = t0.withSecond(0).withNano(0).plusMinutes(1)
        // Inside the next minute it dipped to 165 (through the 170 stop) and was back at 180 by the next look.
        kite.candles = org.json.JSONArray().put(org.json.JSONArray().put(minute.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")))
            .put(190.0).put(191.0).put(165.0).put(180.0).put(1000).put(0))
        kite.quote("NFO:$sym", 180.0, 179.95, 180.05)
        ArmLive.testNow = t0.plusMinutes(2)
        watch()
        val c = view().shadowClosed.single()
        assertEquals("stop", c.why)
        assertEquals("at its trigger less the half-spread, as the resting SL would have filled", 169.70, c.exit!!, 1e-9)
        assertTrue(kite.placed.isEmpty())
        // A minute from before the last look is never judged against a stop (it may have moved since).
        runBlocking { ArmLive.setShadow(donchian, true, pinConfirmed = false) }
        ArmLive.entered(ask(donchian, "wick2"))
        watch()
        kite.candles = org.json.JSONArray().put(org.json.JSONArray().put(ArmLive.testNow!!.withSecond(0).minusMinutes(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"))).put(190.0).put(191.0).put(100.0).put(180.0).put(1000).put(0))
        ArmLive.testNow = ArmLive.testNow!!.plusMinutes(2)
        watch()
        assertEquals(1, view().openShadow.size)
    }

    /** F5: Solo (midday) trades in shadow while it is not proven, even when its stored Shadow says off. */
    @Test fun soloMiddayStaysInShadowWhateverItsStoredSwitchSays() {
        SecurePrefs.put(ArmLive.K_SHADOW, org.json.JSONObject().put(LiveMirror.SOLO_MIDDAY, false).toString())
        java.io.File(context.noBackupFilesDir, "arm_live.vault").delete()
        ArmLive.wipe(); runBlocking { ArmLive.refresh() }
        assertFalse("its stored switch says out of shadow", view().shadowOn(LiveMirror.SOLO_MIDDAY))
        assertNotNull(ArmLive.soloWhy())
        ArmLive.entered(ask(LiveMirror.SOLO_MIDDAY, "solo"))
        assertTrue("nothing sent", kite.placed.isEmpty())
        assertEquals(1, view().openShadow.count { it.key == LiveMirror.SOLO_MIDDAY })
    }

    /** F6: a part closed by hand is booked at what Zerodha's trade book (else its order book) says it sold for. */
    @Test fun aPartClosedByHandIsBookedAtWhatZerodhaSaysItSoldFor() {
        ArmLive.entered(ask(donchian, "byhand", qty = 60))
        assertEquals(60, held())
        watch()
        kite.tradesFromFills = true
        kite.orders["H1"] = FakeKite.Order("H1", sym, "NFO", "SELL", 30, "MIS", "MARKET", 0.0, 0.0, "", "COMPLETE", filled = 30, avg = 214.0)
        kite.positions["NFO:$sym:MIS"] = 30 to 200.0               // Boss sold one lot in Kite at 214 (the price now is 200)
        watch()
        assertEquals("closed_at_zerodha", view().closed.single().why)
        assertEquals("the trade book's price", 214.0, view().closed.single().exit!!, 1e-9)
        assertEquals(30, view().open.single().qty)
        kite.tradesFromFills = false                               // the trade book shows nothing: the order book's average
        kite.orders["H2"] = FakeKite.Order("H2", sym, "NFO", "SELL", 30, "MIS", "MARKET", 0.0, 0.0, "", "COMPLETE", filled = 30, avg = 221.0,
            at = java.time.LocalDateTime.now(com.optionslab.engine.IST).plusMinutes(1))
        kite.positions["NFO:$sym:MIS"] = 0 to 0.0                  // and then the rest, at 221
        watch()
        assertEquals(listOf(214.0, 221.0), view().closed.map { it.exit!! })
        assertTrue(view().open.isEmpty())
    }

    /** F6 (its review): parts closed outside in one look each take the next fill, never the same one twice. */
    @Test fun eachPartClosedOutsideTakesTheNextFill() {
        val rows = listOf(30 to 220.0, 30 to 214.0, 60 to 210.0)          // newest first
        assertEquals(220.0, ArmLive.takeAvg(rows, 0, 30)!!, 1e-9)
        assertEquals(214.0, ArmLive.takeAvg(rows, 30, 30)!!, 1e-9)
        assertEquals((214.0 * 15 + 210.0 * 45) / 60, ArmLive.takeAvg(rows, 45, 60)!!, 1e-9)
        assertEquals(210.0, ArmLive.takeAvg(rows, 60, 90)!!, 1e-9)       // only what is left
        assertNull(ArmLive.takeAvg(rows, 120, 30))
        assertNull(ArmLive.takeAvg(emptyList(), 0, 30))
    }

    /** F8: an exit due before the day's Zerodha login: Boss is told once, and nothing is sold. */
    @Test fun anExitDueWithNoZerodhaLoginTellsBossOnce() {
        ArmLive.entered(ask(donchian, "nologin"))
        watch()
        SecurePrefs.put("kite.accessToken", null)                   // no Zerodha login (a new day before the login)
        AutomationSupport.clearAlerts()
        watch()
        assertTrue("nothing due: nothing said", AutomationSupport.alerts().none { it.contains("waiting for the Zerodha login") })
        legs["nologin"] = donchian to 0                            // its paper trade closed
        watch(); watch()
        val said = AutomationSupport.alerts().filter { it.contains("live trade waiting for the Zerodha login to exit") }
        assertEquals(said.toString(), 1, said.size)
        assertTrue(said.single().contains(LiveMirror.spec(donchian)!!.label))
        assertEquals("nothing sold", 1, view().openReal.size)
    }
}
