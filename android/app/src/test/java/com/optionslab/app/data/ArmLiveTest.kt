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
        // 22 Oct audit (A-F4): the expiry Hero's live copy now starts OFF (its signal reads the option's own price); these tests
        // are about the live orders of every arm, so Boss's PIN turns it on here.
        runBlocking { ArmLive.setLive(com.optionslab.engine.orb.HeroRules.ARM.source, true, pinConfirmed = true) }
        for (f in LiveMirror.ARMS.map { it.family }.distinct())
            ArmLive.register(f) { legs.entries.filter { e -> LiveMirror.spec(e.value.first)?.family == f }.map { ArmLive.PaperLeg(it.key, it.value.first, it.value.second) } }
        kite.requests.clear()
    }

    @After fun down() {
        ArmLive.testSync = false
        ArmLive.testNow = null
        ArmLive.testReadFails = null; ArmLive.testAccountFails = false; ArmLive.testSecurityFails = false; ArmLive.testHeard = null
        ArmLive.testLotCap = null
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
        runBlocking { ArmLive.setLive(LiveMirror.NTFY_ALERTS, true, pinConfirmed = true) }   // its switch starts off (Boss's own)
        runBlocking { ArmLive.setLive(com.optionslab.engine.signals.FlowRules.SOURCE, true, pinConfirmed = true) }   // 28 Oct: IraThinker's flow's too
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

    /**
     * 28 Oct (Boss: "Yes fix finnifty"): a paper buy priced on the live book alone (the option had no last price) is never mirrored
     * live - before, such an entry was not bought at all, so live stays as it was; another arm's ordinary fill still goes.
     */
    @Test fun aPaperBuyFilledOnTheBookAloneIsNeverMirroredLive() {
        try {
            Paper.bookFillForTest(contract.symbol)
            ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "book"))
            assertTrue("nothing sent to Zerodha", kite.placed.isEmpty())
            assertTrue(view().open.isEmpty())
            // An ordinary fill goes as before.
            Paper.bookFillForTest(null)
            ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "plain"))
            assertEquals(2, kite.placed.size)
        } finally { Paper.bookFillForTest(null) }
    }

    @Test fun aLimitEntryPaysAtMostThePaperLimitAndTwoLotsBuyTwoLots() {
        ArmLive.testLotCap = 0                                       // 30 Oct: a multi-lot trade (live is 1 lot by default)
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
        for (f in listOf("data/PassedArmsHost.kt", "data/OrbArms.kt", "data/HeroTicketArm.kt", "data/Ft3Arm.kt", "data/VixDivArm.kt", "data/NightArm.kt",
                "data/NtfyArm.kt", "data/McxPaperArms.kt", "data/ShadowArms.kt", "ira/IraSolo.kt")) {
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

    /** 10 Nov (the 3 Nov refusal, for an exit): a MARKET exit Zerodha blocks (low open interest) goes once as a LIMIT at the bid. */
    @Test fun aBlockedMarketExitGoesOnceAsALimitAtTheBid() {
        val ref = "lowoi-exit"
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, ref))
        watch()                                                    // seen open on paper
        kite.requests.clear()
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "NRML MARKET orders are blocked for this BANKNIFTY contract due to its open interest (OI) being less than 100 lots.")
        legs[ref] = legs.getValue(ref).first to 0                 // the paper trade sold
        watch()
        val sells = kite.placed.filter { it.form["transaction_type"] == "SELL" }
        assertEquals(listOf("MARKET", "LIMIT"), sells.map { it.form["order_type"] })
        assertEquals("at the bid", "199.95", sells[1].form["price"])
        assertEquals("flat, never short", 0, held())
        assertEquals("paper_exit", view().closed.single().why)
        watch()
        assertEquals("nothing more", 2, kite.placed.count { it.form["transaction_type"] == "SELL" })
    }

    /**
     * 15 Nov (audit: 27 live orders Zerodha rejected, "Insufficient funds", shown with no arm): an entry Zerodha refuses with no id is
     * noted with its arm before it went ([ArmOrderLabels]), so the REJECTED order Zerodha keeps in its book is shown with the arm; an
     * entry Zerodha takes is named by its id at once. Nothing about the entry changes (refused: nothing held, said as before).
     */
    @Test fun anEntryZerodhaRefusesIsStillShownWithItsArm() {
        ArmOrderLabels.resetForTest()
        val keepWas = ArmOrderLabels.keep
        ArmOrderLabels.keep = { _, _ -> }                          // no background write of the label outliving this test
        try {
            val key = com.optionslab.engine.orb.DonchianArm.SOURCE
            val label = LiveMirror.spec(key)!!.label
            kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Insufficient funds. Required margin is 6000.00 but available margin is 120.00.")
            ArmLive.entered(ask(key, "nofunds"))
            assertEquals("one try, as before", 1, kite.placed.size)
            assertTrue(view().open.isEmpty())
            assertEquals(true, view().refused[key]?.contains("Insufficient funds"))
            // Zerodha's book keeps it as REJECTED (its id never reached the phone).
            val at = com.optionslab.app.data.Market.now().toLocalDateTime().withNano(0).toString().replace('T', ' ')
            val row = Broker.OrderRow("990001", sym, "BUY", 30, 0, 0.0, 0.0, "REJECTED", "Insufficient funds", at, "NFO", "MIS", "MARKET", tag = "iraarm")
            assertEquals("$label · live entry", ArmOrderLabels.labelOf(row))
            // An entry Zerodha takes: named by its id at once. (No day cap in its way: this test is about the label.)
            SecurePrefs.putAll(mapOf("g.trades" to 0, "k.maxOrders" to 500))
            kite.requests.clear()
            ArmLive.entered(ask(key, "taken"))
            assertEquals(view().refused[key], 1, view().openReal.size)
            val id = kite.orders.values.first { it.side == "BUY" && it.status == "COMPLETE" }.id
            assertEquals("$label · live entry", ArmOrderLabels.labelOf(Broker.OrderRow(id, sym, "BUY", 30, 30, 0.0, 200.0, "COMPLETE", "", at, "NFO", "MIS", "MARKET", tag = "iraarm")))
        } finally { ArmOrderLabels.keep = keepWas; ArmOrderLabels.resetForTest() }
    }

    /** 30 Oct (Part C): the kill switch, both split day stops and the paper-only period block live ENTRIES, never an exit. */
    @Test fun theKillSwitchAndEachSplitStopNeverBlockAnExit() {
        val ref = "k"
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, ref))
        assertEquals(1, view().openReal.size)
        watch()                                                    // seen open on paper
        SecurePrefs.put("g.kill", true)
        runBlocking { Strategies.stopForToday(false, false, log = false); Strategies.stopCommodities(sell = false, log = false) }
        val t = Market.today()
        PaperOnly.resetForTest(com.optionslab.ira.PaperOnlyPeriod.Period(t.plusDays(3), "test", t))
        try {
            ArmLive.entered(ask(com.optionslab.engine.orb.OovArm.SOURCE, "k2", c = other(7).first))
            assertTrue("no new entry", view().openReal.none { it.ref == "k2" })
            kite.requests.clear()
            legs[ref] = legs.getValue(ref).first to 0             // the paper trade sold
            watch()
            assertTrue("the exit still goes", kite.placed.any { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" })
            assertEquals("flat", 0, held())
            // (Boss's index stop sells what its side holds: the exit is "operator_stop" or the paper exit - an exit either way.)
            assertTrue(view().closed.single { it.ref == ref }.why in setOf("paper_exit", "operator_stop"))
        } finally {
            SecurePrefs.put("g.kill", false); PaperOnly.resetForTest(null)
            runBlocking { Strategies.startAgain(log = false); Strategies.startCommoditiesAgain(log = false) }
        }
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
        ArmLive.testLotCap = 0                                       // 30 Oct: a multi-lot trade (live is 1 lot by default)
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

    /**
     * 2 Nov (Boss: "when any live order is placed, I see 2 notifications, 1 of paper and 1 of live"): the live fill takes the paper
     * copy's card and its "bought · Paper" notice down; they stay down while the live trade is open (after a restart too) and the
     * paper card may come back once it has closed.
     */
    @Test fun aLiveFillLeavesOneNoticeThePaperCopysCardAndNoticeGoDown() {
        com.optionslab.app.testing.Background.grantNotifications(context)
        com.optionslab.app.work.Notifier.resetLiveCopiesForTest()
        val nm = com.optionslab.app.testing.Background.notifications(context)
        val paperCard = com.optionslab.app.work.PositionCards.idOf("Paper", contract.symbol)
        com.optionslab.app.work.PositionCards.card(context, "Paper", contract.symbol, 30, 200.0, 200.0, 0.0, alert = true)
        com.optionslab.app.work.Notifier.post(context, 6123, com.optionslab.app.work.Notifier.BUY, "R72 Breadth Donchian bought ${contract.symbol} · Paper", "Bought 30 @ 200.00.")
        assertTrue(nm.getNotification(paperCard) != null && nm.getNotification(6123) != null)
        ArmLive.entered(ask(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, "one"))
        assertEquals(1, view().open.size)
        assertEquals(contract.symbol, view().open.single().paperSymbol)
        assertTrue(contract.symbol in ArmLive.paperMirroredHint)
        assertEquals("the paper card is down", null, nm.getNotification(paperCard))
        assertEquals("the paper notice is down", null, nm.getNotification(6123))
        // A paper fill or notice on it while the live copy is open is not shown again.
        com.optionslab.app.work.Notifier.orderFilled(context, "BUY", 30, contract.symbol, 200.0, "Paper", "R51 Donchian 20 · entry")
        assertEquals(null, nm.getNotification(paperCard))
        // A restart reads it back from the book: still one notice.
        ArmLive.wipe()
        com.optionslab.app.work.Notifier.resetLiveCopiesForTest()
        assertFalse(com.optionslab.app.work.Notifier.liveCopyShown(contract.symbol))
        assertTrue(runBlocking { PositionsFirst.restore() })
        assertTrue("kept in the book", com.optionslab.app.work.Notifier.liveCopyShown(contract.symbol))
        // Closed live: nothing holds the paper card down any more.
        com.optionslab.app.work.Notifier.resetLiveCopiesForTest()
        ArmLive.testNow = Market.today().atTime(15, 16).atZone(com.optionslab.engine.IST)
        watch()
        assertEquals("square_off", view().closed.single().why)
        assertFalse(contract.symbol in ArmLive.paperMirroredHint)
        assertFalse(com.optionslab.app.work.Notifier.liveCopyShown(contract.symbol))
    }

    /** A live order Zerodha refuses leaves the paper notices as they were (there is no live card to stand for them). */
    @Test fun aRefusedLiveOrderLeavesThePaperNotice() {
        com.optionslab.app.testing.Background.grantNotifications(context)
        com.optionslab.app.work.Notifier.resetLiveCopiesForTest()
        val nm = com.optionslab.app.testing.Background.notifications(context)
        com.optionslab.app.work.Notifier.post(context, 6124, com.optionslab.app.work.Notifier.BUY, "R51 Donchian 20 bought ${contract.symbol} · Paper", "Bought 30 @ 200.00.")
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "MCX is disabled for your account.")
        ArmLive.entered(ask(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, "no"))
        assertTrue(view().open.isEmpty())
        assertTrue("the paper notice stays", nm.getNotification(6124) != null)
        assertFalse(com.optionslab.app.work.Notifier.liveCopyShown(contract.symbol))
    }

    /**
     * 2 Nov (live): R51's MIDCPNIFTY entry at 11:30:03 was sold 16 s later as "paper_exit" while its paper trade was open. A stream
     * look reads the paper legs kept from the last regular look, which predate a new entry: a trade missing from that copy is not gone.
     * Only a fresh read says so, and then the live trade is sold.
     */
    @Test fun aStreamLookNeverSellsATradeNewerThanItsKeptPaperLegs() {
        val arm = com.optionslab.engine.orb.DonchianArm.SOURCE
        ArmLive.entered(ask(arm, "older"))
        watch()                                                    // a regular look: the paper legs kept are [older]
        val (c2, s2) = other(3)
        java.io.File(context.filesDir, "kite_instruments.json").delete(); runBlocking { Broker.instruments() }
        ArmLive.entered(ask(arm, "newer", c = c2))
        assertEquals(2, view().open.size)
        kite.requests.clear()
        runBlocking { ArmLive.watch(stream = true) }               // the kept legs do not hold "newer" yet
        assertEquals("not sold on a stream look", 2, view().open.size)
        assertTrue(kite.placed.none { it.form["tradingsymbol"] == s2 && it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" })
        watch()                                                    // a fresh read sees it open: still held
        assertEquals(2, view().open.size)
        legs["newer"] = arm to 0                                   // its paper trade closes: the fresh read sells it
        watch()
        assertEquals("paper_exit", view().closed.single { it.ref == "newer" }.why)
        assertEquals(1, view().open.size)
    }

    /**
     * 2 Nov: while Boss exits a contract by hand ([ArmLive.handsOff]) the arm leaves it alone: a stop gone at Zerodha is not rested
     * again (it would sit beside his exit); once his exit is done ([ArmLive.handsBack]) the arm watches it as before.
     */
    @Test fun whileBossExitsByHandTheArmLeavesTheContractAlone() {
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "hand"))
        val stop = view().open.single().stopOrderId!!
        kite.orders.getValue(stop).status = "CANCELLED"                   // the stop taken out for Boss's exit
        ArmLive.handsOff("NFO", sym)
        assertTrue(ArmLive.handsOffNow("NFO", sym))
        kite.requests.clear()
        watch()
        assertTrue("no stop rested beside Boss's exit", kite.placed.isEmpty())
        ArmLive.handsBack("NFO", sym)
        assertFalse(ArmLive.handsOffNow("NFO", sym))
        watch()
        assertEquals("watched again: its stop rests again", 1, kite.placed.count { it.form["order_type"] == "SL" })
    }

    /** 3 Nov (Boss): the live lots per arm trade from Bot settings: 2 lots live for a 1-lot paper trade, said on its line. */
    @Test fun anArmsLiveTradeBuysBossesLotsFromBotSettings() {
        AppSettings.save(AppSettings.load().copy(armLiveLots = 2))
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "two", qty = 30))
        val (buy, stop) = kite.placed
        assertEquals("2 lots of 30", "60", buy.form["quantity"]); assertEquals("BUY", buy.form["transaction_type"])
        assertEquals("its stop covers both lots", "60", stop.form["quantity"])
        assertEquals(60, view().open.single().qty)
        // Its paper trade closes: both lots are sold.
        kite.requests.clear()
        legs["two"] = com.optionslab.engine.orb.DonchianArm.SOURCE to 0
        watch()
        assertEquals("paper_exit", view().closed.single().why)
        assertEquals("both lots sold", "60", kite.placed.single { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }.form["quantity"])
    }

    /**
     * 4 Nov (Boss: "if I select 3 lots, buy 3 if the money is there, else try 2, else 1"; always on): a 3-lot live buy the free
     * cash cannot pay for buys the 2 it can (1 lot = 30 at the 200.05 offer = about Rs 6,000), said on its line.
     */
    @Test fun anEntryBuysOnlyTheLotsTheFreeCashFits() {
        AppSettings.save(AppSettings.load().copy(armLiveLots = 3, maxLotsPerOrder = 5, guardMaxLots = 10))
        kite.cash = 13_000.0
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "fit"))
        val (buy, stop) = kite.placed
        assertEquals("2 of the 3 lots of 30", "60", buy.form["quantity"]); assertEquals("BUY", buy.form["transaction_type"])
        assertEquals("its stop covers what was bought", "60", stop.form["quantity"])
        assertEquals(60, view().open.single().qty)
        assertTrue(view().log.toString(), view().log.any { "2 of your 3 lots: the free cash (Rs 13,000) fits 2" in it })
    }

    /** 4 Nov: a LIMIT entry is priced at its own limit (Zerodha blocks the premium there), not the lower last price. */
    @Test fun aLimitEntryIsFittedAtItsLimitPrice() {
        AppSettings.save(AppSettings.load().copy(armLiveLots = 3, maxLotsPerOrder = 5, guardMaxLots = 10))
        kite.cash = 12_500.0                                         // 2 lots at 200 fit; at the 210 limit only 1 does
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "lim", limit = 210.0))
        val buy = kite.placed.first()
        assertEquals("LIMIT", buy.form["order_type"]); assertEquals("1 lot", "30", buy.form["quantity"])
    }

    @Test fun whenNotEvenOneLotFitsTheEntryGoesAsBefore() {
        AppSettings.save(AppSettings.load().copy(armLiveLots = 3, maxLotsPerOrder = 5, guardMaxLots = 10))
        kite.cash = 5_000.0                                          // under one lot's Rs 6,000
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "none"))
        assertEquals("all 3 lots sent: Zerodha says why", "90", kite.placed.first().form["quantity"])
        assertTrue(view().log.none { "of your 3 lots" in it })
    }

    // ---- 15 Nov: the funds check (Boss: "Yes" to it in shadow) ----------------------------------------------------------------

    /**
     * Shadow (its switch off, the default): a 1-lot buy the free cash cannot pay for (Rs 5,000 against about Rs 6,000) goes to Zerodha
     * exactly as before; the would-skip is logged and counted - from the margins the entry's own account read just read.
     */
    @Test fun theFundsCheckInShadowLogsTheEntryAndSendsItAsBefore() {
        kite.cash = 5_000.0
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "short1"))
        assertEquals("sent as before", "30", kite.placed.first().form["quantity"])
        assertEquals("BUY", kite.placed.first().form["transaction_type"])
        val r = FundsCheck.recsOf().single()
        assertFalse("logged only", r.applied); assertEquals(sym, r.symbol); assertEquals(1, r.lots)
        assertEquals(LiveMirror.spec(com.optionslab.engine.orb.DonchianArm.SOURCE)!!.label, r.owner)
    }

    /** Shadow, 3 lots wanted and not one fitting: the fit's own read is the figure (the order goes with all 3, as before), logged once. */
    @Test fun theFundsCheckReusesTheFitsReadForSeveralLots() {
        AppSettings.save(AppSettings.load().copy(armLiveLots = 3, maxLotsPerOrder = 5, guardMaxLots = 10))
        kite.cash = 5_000.0
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "short3"))
        assertEquals("90", kite.placed.first().form["quantity"])
        assertEquals(3, FundsCheck.recsOf().single().lots)
    }

    /** On (Boss's switch): the entry is not sent to Zerodha, kept as a would-be entry and said on the arm's row. */
    @Test fun theFundsCheckOnSendsNothingTheCashCannotPayFor() {
        FundsCheck.set(true)
        kite.cash = 5_000.0
        val key = com.optionslab.engine.orb.DonchianArm.SOURCE
        ArmLive.entered(ask(key, "short-on"))
        assertTrue("nothing sent", kite.placed.isEmpty())
        assertTrue(view().open.isEmpty())
        assertEquals(true, view().refused[key]?.contains(FundsCheck.WORDS))
        assertTrue(FundsCheck.recsOf().single().applied)
        // Cash enough again: the next entry goes (the check never resizes or adds).
        kite.cash = 500_000.0
        ArmLive.entered(ask(key, "short-on-2"))
        assertEquals("30", kite.placed.first().form["quantity"])
        assertEquals(1, FundsCheck.recsOf().size)
    }

    /** On, but every other gate first: the kill switch refuses before it, and enough cash is never touched by it. */
    @Test fun theFundsCheckNeverWeakensAnotherGate() {
        FundsCheck.set(true)
        SecurePrefs.put("g.kill", true)
        kite.cash = 5_000.0
        val key = com.optionslab.engine.orb.DonchianArm.SOURCE
        ArmLive.entered(ask(key, "kill"))
        assertTrue(kite.placed.isEmpty()); assertEquals(true, view().refused[key]?.contains("kill switch"))
        assertTrue("the kill switch refused it first: the check never ran", FundsCheck.recsOf().isEmpty())
        SecurePrefs.put("g.kill", false)
        // In Paper nothing is asked at all.
        AutomationSupport.liveSettings(live = false)
        ArmLive.entered(ask(key, "paper"))
        assertTrue(kite.placed.isEmpty()); assertTrue(FundsCheck.recsOf().isEmpty())
    }

    @Test fun allTheLotsGoWhenTheFreeCashFitsThem() {
        AppSettings.save(AppSettings.load().copy(armLiveLots = 3, maxLotsPerOrder = 5, guardMaxLots = 10))
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "plenty"))   // the fake account's Rs 5 lakh
        assertEquals("90", kite.placed.first().form["quantity"])
        assertTrue(view().log.none { "of your 3 lots" in it })
    }

    /** 3 Nov (live): Zerodha blocks MIS MARKET orders on a low-OI contract; the entry goes once as a LIMIT at the offer instead. */
    @Test fun aMarketOrderZerodhaBlocksGoesOnceAsALimitAtTheOffer() {
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "MIS MARKET orders are blocked for this FINNIFTY contract due to its open interest (OI) being less than 100 lots.")
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "lowoi"))
        val sent = kite.placed
        assertEquals(listOf("MARKET", "LIMIT", "SL"), sent.map { it.form["order_type"] })
        assertEquals("at the offer", "200.05", sent[1].form["price"]); assertEquals("BUY", sent[1].form["transaction_type"])
        assertEquals(1, view().open.size)
    }

    /** 3 Nov (Boss): an index not ticked in Bot settings → Indices gets no live order from an arm; it is kept in shadow. */
    @Test fun anIndexNotTickedForLiveIsKeptInShadowAndNothingIsSent() {
        AppSettings.save(AppSettings.load().copy(liveMarkets = setOf("NIFTY")))
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "bnf"))     // a BANKNIFTY call
        assertTrue("nothing sent to Zerodha", kite.placed.isEmpty())
        assertTrue(view().open.none { !it.shadow })
        assertEquals("kept in shadow, recorded", 1, view().open.count { it.shadow })
        // Ticked again: the next one goes live.
        AppSettings.save(AppSettings.load().copy(liveMarkets = setOf("NIFTY", "BANKNIFTY")))
        val (c2, _) = other(4)
        java.io.File(context.filesDir, "kite_instruments.json").delete(); runBlocking { Broker.instruments() }
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, "bnf2", c = c2))
        assertEquals("BUY", kite.placed.first().form["transaction_type"])
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

    /**
     * 30 Oct (Boss: "make all arms as live ... make all live if i move to live market"): a fresh book starts every arm OUT of shadow
     * and with its Trade live on (Solo (midday) excepted: Jarvis's Solo stays in shadow until proven), so the first paper fill in Live
     * with AI trades on goes to Zerodha with its stop.
     */
    @Test fun everyArmStartsOutOfShadowAndAFreshBookTradesLive() {
        ArmLive.wipe(); java.io.File(context.noBackupFilesDir, "arm_live.vault").delete()
        SecurePrefs.put(ArmLive.K_SHADOW, null); SecurePrefs.put(ArmLive.K_SWITCHES, null)
        runBlocking { ArmLive.refresh() }
        for (a in LiveMirror.ARMS) { assertEquals(a.key, a.key == LiveMirror.SOLO_MIDDAY, view().shadowOn(a.key)); assertTrue(a.key, view().on(a.key)) }
        ArmLive.entered(ask(donchian, "fresh"))
        assertEquals("the entry and its stop", 2, kite.placed.size)
        assertEquals(1, view().openReal.size); assertEquals(0, view().openShadow.size)
    }

    /** 30 Oct (migration): an arm Boss put in live shadow himself keeps it after the default changed; a restart keeps it too. */
    @Test fun anArmBossPutInShadowStaysInShadowAfterTheNewDefault() {
        runBlocking { ArmLive.setShadow(donchian, true, pinConfirmed = false) }
        ArmLive.wipe()
        runBlocking { ArmLive.refresh() }
        assertTrue(view().shadowOn(donchian))
        assertFalse(view().shadowOn(com.optionslab.engine.orb.OovArm.SOURCE))
        ArmLive.entered(ask(donchian, "kept"))
        assertTrue("nothing sent for the arm Boss keeps in shadow", kite.placed.isEmpty())
        assertEquals(1, view().openShadow.size)
    }

    /**
     * 30 Oct (Part C): for EVERY mirrored arm, with the app in Live, AI trades on and its own switch on, its entry reaches the broker
     * door; with any ONE gate off - Paper, AI trades off, its Trade live off, the kill switch, its side's day stop, the paper-only
     * period - nothing is sent. (The day lock, the loss limit, the account stop and the security check are refused by the same gate:
     * their own tests.)
     */
    @Test fun everyArmReachesTheDoorWithEveryGateOnAndNothingGoesWithAnyOneGateOff() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        val arms = LiveMirror.ARMS.filter { it.key != LiveMirror.SOLO_MIDDAY }
        val gates = listOf<Pair<String, (Boolean) -> Unit>>(
            "Paper" to { off -> AutomationSupport.liveSettings(live = !off) },
            "AI trades go live" to { off -> IraNewsTrades.paperFirst = off },
            "its Trade live" to { off -> },                                      // per arm, below
            "kill switch" to { off -> SecurePrefs.put("g.kill", off) },
            "day stop" to { off -> runBlocking { if (off) { Strategies.stopForToday(false, false, log = false); Strategies.stopCommodities(sell = false, log = false) } else { Strategies.startAgain(log = false); Strategies.startCommoditiesAgain(log = false) } } },
            "paper-only period" to { off -> val t = Market.today(); PaperOnly.resetForTest(if (off) com.optionslab.ira.PaperOnlyPeriod.Period(t.plusDays(3), "test", t) else null) },
        )
        var n = 0
        for (spec in arms) for ((name, set) in gates) {
            n++
            val ref = "gate:${spec.key}:$name"
            if (name == "its Trade live") runBlocking { ArmLive.setLive(spec.key, false, pinConfirmed = false) } else set(true)
            try {
                ArmLive.entered(ask(spec.key, ref))
                assertTrue("${spec.key} with $name off: nothing sent", kite.placed.isEmpty())
                assertFalse("${spec.key} with $name off: never at the door", heard.any { it == "door|${spec.key}|$ref" })
                assertTrue("${spec.key} with $name off: no live trade", view().openReal.none { it.ref == ref })
            } finally {
                if (name == "its Trade live") runBlocking { ArmLive.setLive(spec.key, true, pinConfirmed = true, log = false) } else set(false)
            }
        }
        assertEquals(arms.size * gates.size, n)
        // Every gate back on: each intraday option arm's entry reaches the door and is sent (the carried and MCX arms: their own tests).
        SecurePrefs.putAll(mapOf("g.trades" to 0, "k.maxOrders" to 500))
        val intraday = arms.filter { it.exchange == "NFO" && it.product == "MIS" }
        val cs = intraday.indices.map { other(it + 1) }
        java.io.File(context.filesDir, "kite_instruments.json").delete()
        runBlocking { Broker.instruments() }
        for ((i, spec) in intraday.withIndex()) {
            kite.requests.clear()
            val ref = "open:${spec.key}"
            ArmLive.entered(ask(spec.key, ref, c = cs[i].first))
            assertTrue("${spec.key}: at the door - ${view().refused[spec.key]}", heard.any { it == "door|${spec.key}|$ref" })
            assertTrue(spec.key, kite.placed.any { it.form["transaction_type"] == "BUY" && it.form["tag"] == "iraarm" })
            kite.flat(); kite.orders.values.filter { it.working }.forEach { it.status = "CANCELLED" }
        }
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

    /**
     * 30 Oct (Boss: "1 lot per arm entry, never more unless Boss sets it"): FT3 buys Rs 5,000 of lots on paper (9 lots here); its live
     * and its shadow trade are 1 lot, said on its own lines. Every other arm is cut the same way (OOV's 9 paper lots go live as 1).
     */
    @Test fun aManyLotPaperTicketIsMirroredAtOneLotInShadowAndLive() {
        val ft3 = com.optionslab.engine.orb.Ft3Rules.SOURCE
        val o1 = other(1)
        val (c1, c2) = o1.first to other(2).first
        java.io.File(context.filesDir, "kite_instruments.json").delete()          // the day's list read again, with them
        runBlocking { Broker.instruments() }
        runBlocking { ArmLive.setShadow(ft3, true, pinConfirmed = false) }
        ArmLive.entered(ask(ft3, "ft3:sh", qty = 9 * 30, stop = null, limit = 201.0))
        assertTrue("nothing sent in shadow", kite.placed.isEmpty())
        val p = view().openShadow.singleOrNull() ?: error("not mirrored: ${view().notMirrored} ${view().log}")
        assertEquals(30, p.qty); assertEquals(270, p.paperQty)
        assertEquals("the -80% disaster stop of a close-judged arm", 40.0, p.stopTrigger!!, 0.05)
        // Out of shadow (Boss's PIN): the live order is 1 lot, not 9.
        runBlocking { ArmLive.setShadow(ft3, false, pinConfirmed = true, log = false) }
        ArmLive.entered(ask(ft3, "ft3:live", qty = 9 * 30, stop = null, limit = 201.0, c = c1))
        val o = kite.placed.singleOrNull { it.form["tag"] == com.optionslab.engine.orb.LiveMirror.TAG && it.form["transaction_type"] == "BUY" }
            ?: error("not placed: ${kite.placed.map { it.form }} ${view().notMirrored} ${view().refused}")
        assertEquals("30", o.form["quantity"])
        // Its -80% disaster stop rests for the live 30, and the cut is said on its own lines (shadow and live).
        val stop = kite.placed.single { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "SL" }
        assertEquals("30", stop.form["quantity"])
        val lines = view().log.filter { "FT3" in it }
        assertTrue(lines.toString(), lines.count { "1 of the paper's 9 lots (1 lot per arm entry live)" in it } == 2)
        // The paper ticket sold: the live 30 are sold (the stop out first), never the paper's 270.
        watch()
        legs["ft3:live"] = ft3 to 0
        watch()
        val sell = kite.placed.single { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }
        assertEquals("30", sell.form["quantity"])
        assertEquals("flat", 0, kite.positions["NFO:${o1.second}:MIS"]?.first ?: 0)
        assertEquals("paper_exit", view().closed.single { it.ref == "ft3:live" }.why)
        // Another arm is cut the same way: OOV's 9 paper lots are 1 lot live (in shadow here).
        val oov = com.optionslab.engine.orb.OovArm.SOURCE
        runBlocking { ArmLive.setShadow(oov, true, pinConfirmed = false) }
        ArmLive.entered(ask(oov, "oov:sh", qty = 9 * 30, stop = null, limit = 201.0, c = c2))
        assertEquals(30, (view().openShadow.singleOrNull { it.ref == "oov:sh" } ?: error("${view().notMirrored}")).qty)
    }

    /** 30 Oct: a 1-lot paper trade is its own size with no cut words, whatever the Bot settings' lot cap. */
    @Test fun aOneLotPaperTradeKeepsItsSizeWithNoCutWords() {
        val ft3 = com.optionslab.engine.orb.Ft3Rules.SOURCE
        java.io.File(context.filesDir, "kite_instruments.json").delete()          // the day's list read again, with it
        runBlocking { Broker.instruments() }
        runBlocking { ArmLive.setShadow(ft3, true, pinConfirmed = false) }
        ArmLive.entered(ask(ft3, "ft3:one", qty = 30, stop = null, limit = 201.0))
        assertEquals(30, (view().openShadow.singleOrNull { it.ref == "ft3:one" } ?: error("${view().notMirrored}")).qty)
        assertTrue(view().log.toString(), view().log.none { "lot per arm entry" in it })
        assertTrue("nothing sent in shadow", kite.placed.isEmpty())
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
        ArmLive.testLotCap = 0                                       // 30 Oct: a multi-lot trade (live is 1 lot by default)
        ArmLive.entered(ask(donchian, "hand", qty = 60))
        val sid = view().open.single().stopOrderId!!
        assertEquals(60, kite.order(sid).qty)
        watch()
        kite.soldInKite(sym, 30, 200.0)                            // Boss sold one of the two lots in Kite (18 Nov: its sale is in the book)
        watch()
        assertEquals(30, kite.order(sid).qty)
        assertTrue(kite.order(sid).working)
        assertEquals(30, view().open.single().qty)
        assertEquals("closed_at_zerodha", view().closed.single().why)
        kite.soldInKite(sym, 30, 200.0)                            // and then the rest
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
        kite.soldInKite(sym, 30, 200.0)                          // Boss sold 30 by hand: the oldest trade's (the paper rule)
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
        ArmLive.testLotCap = 0                                       // 30 Oct: a multi-lot trade (live is 1 lot by default)
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
        ArmLive.testLotCap = 0                                       // 30 Oct: a multi-lot trade (live is 1 lot by default)
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
        ArmLive.testLotCap = 0                                       // 30 Oct: a multi-lot trade (live is 1 lot by default)
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
        ArmLive.testLotCap = 0                                       // 30 Oct: a multi-lot trade (live is 1 lot by default)
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

    /**
     * F6: a part closed by hand is booked at what Zerodha's trade book (else its order book) says it sold for.
     * 18 Oct: two lots, not three - the account guard's 2-lot cap on one option refused a 3-lot entry, so nothing was ever
     * bought and nothing could be booked. The first lot sold by hand is a part (the trade book's price, though the order book
     * says otherwise); the last one closes the trade (the order book's average), so both kinds of outside close are covered.
     */
    @Test fun aPartClosedByHandIsBookedAtWhatZerodhaSaysItSoldFor() {
        ArmLive.testLotCap = 0                                       // 30 Oct: a multi-lot trade (live is 1 lot by default)
        ArmLive.entered(ask(donchian, "byhand", qty = 60))
        assertEquals("bought live", 60, held())
        watch()
        kite.tradesFromFills = true
        // The trade book says 214 and the order book 216: the trade book comes first.
        kite.orders["H1"] = FakeKite.Order("H1", sym, "NFO", "SELL", 30, "MIS", "MARKET", 0.0, 0.0, "", "COMPLETE", filled = 30, avg = 216.0,
            tradePrice = 214.0)
        kite.positions["NFO:$sym:MIS"] = 30 to 200.0               // Boss sold one lot in Kite at 214 (the price now is 200)
        watch()
        assertEquals("closed_at_zerodha", view().closed.single().why)
        assertEquals("the trade book's price", 214.0, view().closed.single().exit!!, 1e-9)
        assertEquals(30, view().open.single().qty)
        kite.tradesFromFills = false                               // the trade book shows nothing: the order book's average
        kite.orders["H2"] = FakeKite.Order("H2", sym, "NFO", "SELL", 30, "MIS", "MARKET", 0.0, 0.0, "", "COMPLETE", filled = 30, avg = 221.0,
            at = Market.now().toLocalDateTime().plusMinutes(1))
        kite.positions["NFO:$sym:MIS"] = 0 to 0.0                  // and then the last lot at 221
        watch()
        assertEquals(listOf(214.0, 221.0), view().closed.map { it.exit!! })
        assertTrue(view().closed.all { it.why == "closed_at_zerodha" })
        assertTrue(view().open.isEmpty())
        assertTrue("no stop left resting on a flat position", kite.orders.values.none { it.working && it.side == "SELL" })
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

    // ---- 21 Oct, enhancement round 3 (idea 1): the positions-lag guard on a real exit ---------------------------------------

    /** Boss sells the 30 from the Trade screen; it fills, but Zerodha's positions still show it held for [trail] reads. */
    private fun bossSoldWhileThePositionsTrail(ref: String, trail: Int) {
        ArmLive.entered(ask(com.optionslab.engine.orb.DonchianArm.SOURCE, ref))
        watch()                                                    // seen open on paper
        kite.positionsTrailReads = trail
        runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", ""), exit = true) }
        assertEquals("Boss's sell filled", 0, held())
        kite.requests.clear()
        legs[ref] = legs.getValue(ref).first to 0                 // and the paper trade exits now
    }

    @Test fun withTheLagGuardOnAnExitThatFilledIsNeverSoldAgainWhileThePositionsTrail() {
        ExitCoordinator.resetForTest(lagOn = true)
        try {
            bossSoldWhileThePositionsTrail("lag-on", 50)
            watch()
            assertEquals("no second market sell", 0, kite.placed.count { it.form["order_type"] == "MARKET" && it.form["transaction_type"] == "SELL" })
            assertEquals("flat, never short", 0, held())
            assertTrue("no stop rested again on lots already sold",
                kite.placed.none { it.form["order_type"] == "SL" && it.form["transaction_type"] == "SELL" })
            assertTrue(ExitCoordinator.countsForTest().lagSubtracted >= 1)
        } finally { ExitCoordinator.resetForTest() }
    }

    @Test fun inShadowTheLagGuardOnlyLogsAndTheExitGoesAsBefore() {
        ExitCoordinator.resetForTest(lagOn = false)
        try {
            bossSoldWhileThePositionsTrail("lag-shadow", 50)
            watch()
            // As before this round (the hole the guard closes once Boss switches it on): the trailing read is sold again.
            assertEquals(1, kite.placed.count { it.form["order_type"] == "MARKET" && it.form["transaction_type"] == "SELL" })
            assertTrue(ExitCoordinator.countsForTest().lagShadow >= 1)
            assertEquals(0, ExitCoordinator.countsForTest().lagSubtracted)
        } finally { ExitCoordinator.resetForTest() }
    }

    /** 28 Oct (shadow logging): a paper trade's result in percent of its entry, for the time machine's forward log. */
    @Test fun aPaperResultInPercentOfTheEntry() {
        assertEquals(50.0, ArmLive.pct(100.0, 150.0)!!, 1e-9)
        assertEquals(-15.0, ArmLive.pct(100.0, 85.0)!!, 1e-9)
        assertEquals(10.0, ArmLive.pct(100.0, 90.0, side = -1)!!, 1e-9)     // a sale (MCX short) that fell
        assertNull(ArmLive.pct(100.0, null)); assertNull(ArmLive.pct(0.0, 5.0)); assertNull(ArmLive.pct(Double.NaN, 5.0))
        assertNull(ArmLive.pct(null, 5.0))
    }

    // ---- 18 Nov 2026: live trades lost from the book while Zerodha still held them ----------------------------------------------

    /**
     * 18 Nov 2026 (Boss, live: R51's FINNIFTY lot, its buy's reply lost, was dropped as "never placed" three minutes on while Zerodha
     * held it - no stop, no step lock, nothing watching it): an unconfirmed entry whose order the search cannot find, while Zerodha's
     * positions hold it, is taken back at once - its stop rested at Zerodha, its rule and step lock as it entered, its best from the
     * fill - and never dropped afterwards.
     */
    @Test fun anUnconfirmedEntryZerodhaHoldsIsTakenBackWithItsStopAndNeverDropped() {
        kite.trailReads = 1_000                                       // the order book never lists the buy (as on the phone)
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)      // and its reply is lost
        ArmLive.entered(ask(donchian, "lost").copy(fixed = true, lock = true))
        val waiting = view().openReal.single()
        assertTrue("held as unconfirmed", waiting.unconfirmed)
        assertEquals("bought at Zerodha", 30, held())
        assertTrue("no stop yet", kite.orders.values.none { it.side == "SELL" })
        watch()
        val p = view().openReal.single()
        assertFalse("taken back from Zerodha's positions", p.unconfirmed)
        assertEquals(30, p.qty); assertEquals("Zerodha's average", 200.0, p.entry, 1e-9)
        assertTrue("its rule as it entered", p.fixed); assertTrue("and its step lock", p.lock)
        assertTrue(p.heldSeen)
        val stop = kite.orders.values.single { it.side == "SELL" && it.type == "SL" }
        assertEquals(p.stopOrderId, stop.id)
        assertEquals(30, stop.qty); assertEquals("the arm's own -15% on the fill", 170.0, stop.trigger, 1e-9)
        assertTrue(AutomationSupport.alerts().joinToString(), AutomationSupport.alerts().any { "managed as this arm's trade" in it })
        // Past the three minutes' grace, still held at Zerodha: kept and watched, never dropped as "never placed".
        ArmLive.testNow = ArmLive.testNow!!.plusMinutes(5)
        watch(); watch()
        assertEquals(1, view().openReal.size); assertTrue(view().closed.isEmpty())
        assertTrue(kite.order(stop.id).working)
        assertTrue("watched by every stop lane", ArmLive.holdingHint)
    }

    /** 18 Nov 2026: a position another trade of the book explains is never taken for a lost entry's fill (no second stop on it). */
    @Test fun aLostEntryNeverTakesAnotherTradesPosition() {
        ArmLive.entered(ask(donchian, "first"))
        watch()
        kite.trailReads = 1_000
        kite.nextPlace(FakeKite.Scenario(reply = FakeKite.Reply.DROP_AFTER_ACCEPT, outcome = FakeKite.Outcome.REJECT))   // nothing bought
        ArmLive.entered(ask(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, "second"))
        assertEquals("only the first trade's lot", 30, held())
        watch()
        val second = view().openReal.single { it.ref == "second" }
        assertTrue("still waiting for its order, never adopted", second.unconfirmed)
        assertEquals("one stop, the first trade's", 1, kite.orders.values.count { it.side == "SELL" && it.type == "SL" })
    }

    /**
     * 18 Nov 2026 (the same morning: the trade booked "closed_at_zerodha" on one positions read, its stop cancelled): a read with no row
     * for the contract never counts; a read short of what is held, with no sale in Zerodha's order book, closes nothing and leaves the
     * stop resting until reads 30 s apart agree; a read showing it held again starts the count over.
     */
    @Test fun oneShortPositionsReadNeverClosesTheTradeOrTakesItsStopOut() {
        ArmLive.entered(ask(donchian, "short"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        val t0 = ArmLive.testNow!!
        kite.requests.clear()
        // An empty read (no row at all), again minutes later: nothing.
        kite.positions.remove("NFO:$sym:MIS")
        watch(); ArmLive.testNow = t0.plusMinutes(2); watch()
        assertTrue(view().closed.isEmpty()); assertEquals(30, view().openReal.single().qty)
        assertTrue("its stop rests", kite.order(sid).working); assertEquals(30, kite.order(sid).qty)
        // A row at 0 with no sale in the order book: not booked within 30 s, nothing sent, the stop never touched.
        kite.positions["NFO:$sym:MIS"] = 0 to 0.0
        ArmLive.testNow = t0.plusMinutes(3); watch()
        ArmLive.testNow = t0.plusMinutes(3).plusSeconds(20); watch()
        assertTrue(view().closed.isEmpty())
        assertTrue(kite.order(sid).working)
        assertTrue("nothing sent or changed at Zerodha", kite.requests.none { it.method != "GET" })
        // Held again: the count starts over.
        kite.positions["NFO:$sym:MIS"] = 30 to 200.0
        ArmLive.testNow = t0.plusMinutes(4); watch()
        kite.positions["NFO:$sym:MIS"] = 0 to 0.0
        ArmLive.testNow = t0.plusMinutes(4).plusSeconds(10); watch()
        ArmLive.testNow = t0.plusMinutes(4).plusSeconds(39); watch()
        assertTrue("29 s of short reads: still held", view().closed.isEmpty())
        assertTrue(kite.order(sid).working)
        // 18 Nov 2026 (review F3): 30 s of reads agreeing, with no sale at all in the order book: still held, its stop resting, and Boss
        // told once.
        fun told() = AutomationSupport.alerts().count { "for 30 s" in it && sym in it }
        assertEquals(0, told())
        ArmLive.testNow = t0.plusMinutes(4).plusSeconds(40); watch()
        assertTrue("30 s with nothing sold: never dropped", view().closed.isEmpty())
        assertTrue(kite.order(sid).working); assertEquals(30, kite.order(sid).qty)
        assertEquals("Boss told", 1, told())
        ArmLive.testNow = t0.plusMinutes(7); watch()
        ArmLive.testNow = t0.plusMinutes(9).plusSeconds(9); watch()
        assertTrue("4 min 59 s: still held", view().closed.isEmpty())
        assertTrue(kite.order(sid).working)
        assertEquals("told once", 1, told())
        assertTrue("nothing sent or changed at Zerodha", kite.requests.none { it.method != "GET" })
        // Five minutes of reads agreeing: closed outside, its stop confirmed out first.
        ArmLive.testNow = t0.plusMinutes(9).plusSeconds(10); watch()
        assertEquals("closed_at_zerodha", view().closed.single().why)
        assertFalse(kite.order(sid).working)
        assertTrue(view().openReal.isEmpty())
    }

    /**
     * 18 Nov 2026 (review F3): a sale of the app's own whose fill no book has taken yet (its reply lost) is a sale: then 30 s of short
     * reads are enough, as before.
     */
    @Test fun aSaleNotBookedYetLetsThirtySecondsOfShortReadsCloseIt() {
        ArmLive.entered(ask(donchian, "unbooked"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        val t0 = ArmLive.testNow!!
        // An exit of the app's own (tagged, known as this path's) filled at Zerodha, its reply lost: no book has it.
        val own = kite.soldInKite(sym, 30, 205.0)
        runBlocking { Strategies.tagOwner("kite:$own", "test · live paper_exit") }
        ArmLive.testNow = t0.plusMinutes(1); watch()
        ArmLive.testNow = t0.plusMinutes(1).plusSeconds(29); watch()
        assertTrue("not at once: the app's own sale is no proof", view().closed.isEmpty())
        ArmLive.testNow = t0.plusMinutes(1).plusSeconds(30); watch()
        assertEquals("closed_at_zerodha", view().closed.single().why)
        assertFalse(kite.order(sid).working)
        assertTrue("no alarm: a sale explains it", AutomationSupport.alerts().none { "for 30 s" in it })
    }

    /**
     * 18 Nov 2026 (review F1): Boss's own buy and sale of the same contract in Kite (a round trip of his, untagged) is no proof that the
     * arm's trade closed: one short read then closes nothing and leaves the stop resting.
     */
    @Test fun bossesOwnRoundTripInKiteNeverConfirmsAShortRead() {
        ArmLive.entered(ask(donchian, "trip"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        kite.boughtInKite(sym, 30, 201.0)
        kite.soldInKite(sym, 30, 203.0)
        assertEquals("the arm's lot still held", 30, held())
        kite.positions["NFO:$sym:MIS"] = 0 to 0.0                    // then one bad read
        watch()
        assertTrue("kept", view().closed.isEmpty())
        assertEquals(30, view().openReal.single().qty)
        assertTrue("its stop rests", kite.order(sid).working); assertEquals(30, kite.order(sid).qty)
    }

    /**
     * 18 Nov 2026 (review F1): Boss's sale of the ORB arm's own lot in Kite, already booked "closed_by_you" in the ORB book, is no proof that
     * this book's trade on the same contract closed.
     */
    @Test fun aSaleTheOrbBookAlreadyBookedNeverConfirmsAShortReadHere() {
        ArmLive.entered(ask(donchian, "orbsale"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        kite.soldInKite(sym, 30, 203.0)                              // Boss sells the ORB arm's lot (it was bought outside this book)
        OrbArms.liveClosedHintForTest(listOf(LiveMirror.Booked("NFO", sym, "MIS", ArmLive.testNow!!.toLocalDateTime(), 30, outside = true)))
        try {
            kite.positions["NFO:$sym:MIS"] = 0 to 0.0
            watch()
            assertTrue("kept", view().closed.isEmpty())
            assertTrue("its stop rests", kite.order(sid).working)
        } finally { OrbArms.liveClosedHintForTest(emptyList()) }
    }

    /**
     * 18 Nov 2026 (review F5): two trades of 30 on one contract and one read showing 30 (not confirmed): neither stop is shrunk or taken
     * out on that read.
     */
    @Test fun aPartlyShortReadNeverShrinksASiblingsStop() {
        ArmLive.entered(ask(donchian, "s1"))
        ArmLive.entered(ask(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, "s2"))
        watch()
        val stops = view().openReal.map { it.stopOrderId!! }
        assertEquals(2, stops.size)
        kite.positions["NFO:$sym:MIS"] = 30 to 200.0                 // one read short by a lot, no sale anywhere
        watch()
        assertEquals(2, view().openReal.size)
        for (id in stops) { assertTrue("stop $id rests", kite.order(id).working); assertEquals(30, kite.order(id).qty) }
    }

    /**
     * 18 Nov 2026 (review F2): the MARKET Zerodha refused and the LIMIT sent the same second: the search finds the refused one first, and
     * the LIMIT still working is found too, cancelled and confirmed out - never left working to fill later unmanaged.
     */
    @Test fun aLostEntrysWorkingTryIsFoundAndCancelledNotLeftWorking() {
        kite.trailReads = 1_000                                       // the order book does not list it while it is entered
        kite.nextPlace(FakeKite.Scenario(reply = FakeKite.Reply.DROP_AFTER_ACCEPT, outcome = FakeKite.Outcome.OPEN))
        ArmLive.entered(ask(donchian, "tries"))
        assertTrue(view().openReal.single().unconfirmed)
        val working = kite.orders.values.single { it.side == "BUY" && it.working }
        kite.rejectedInBook(sym, "BUY", 30, LiveMirror.TAG, at = working.at, before = working.id)
        kite.showInBook()
        watch()
        assertEquals("the working try cancelled", "CANCELLED", kite.order(working.id).status)
        assertEquals("nothing held", 0, held())
        assertTrue("nothing unmanaged: no trade, no stop", view().openReal.isEmpty() && kite.orders.values.none { it.side == "SELL" })
    }

    /** 18 Nov 2026 (review F2): the same, the LIMIT already filled: settled from that order (its id, its fill), with its stop. */
    @Test fun aLostEntrysFilledTryIsSettledFromItsOwnOrder() {
        kite.trailReads = 1_000
        kite.nextPlace(FakeKite.Scenario(reply = FakeKite.Reply.DROP_AFTER_ACCEPT, outcome = FakeKite.Outcome.OPEN))
        ArmLive.entered(ask(donchian, "tries2"))
        val working = kite.orders.values.single { it.side == "BUY" && it.working }
        kite.rejectedInBook(sym, "BUY", 30, LiveMirror.TAG, at = working.at, before = working.id)
        kite.fill(working.id, 201.0)
        kite.showInBook()
        watch()
        val p = view().openReal.single()
        assertFalse(p.unconfirmed)
        assertEquals("settled from its own order", working.id, p.entryOrderId)
        assertEquals(30, p.qty); assertEquals(201.0, p.entry, 1e-9)
        assertEquals(1, kite.orders.values.count { it.side == "SELL" && it.type == "SL" && it.working })
    }

    /**
     * 18 Nov 2026 (second review): Boss's own lot bought in Kite BEFORE the arm's entry and sold after it is his round trip, not the arm's
     * close: one short read then closes nothing.
     */
    @Test fun bossesLotBoughtBeforeTheEntryAndSoldAfterNeverConfirmsAShortRead() {
        val t0 = ArmLive.testNow!!.toLocalDateTime()
        kite.orders["B0"] = FakeKite.Order("B0", sym, "NFO", "BUY", 30, "MIS", "MARKET", 0.0, 0.0, "", "COMPLETE", filled = 30, avg = 190.0,
            at = t0.minusMinutes(20))
        kite.positions["NFO:$sym:MIS"] = 30 to 190.0                  // his own lot, from before
        ArmLive.entered(ask(donchian, "after"))
        watch()
        assertEquals(60, held())
        val sid = view().openReal.single().stopOrderId!!
        kite.soldInKite(sym, 30, 205.0)                              // he sells his own lot
        watch()
        kite.positions["NFO:$sym:MIS"] = 0 to 0.0                    // then one bad read
        watch()
        assertTrue("kept", view().closed.isEmpty())
        assertTrue("its stop rests", kite.order(sid).working); assertEquals(30, kite.order(sid).qty)
    }

    /**
     * 18 Nov 2026 (second review): Boss converting the arm's MIS lot to NRML in Kite leaves no sale, but the NRML row holds it: booked after
     * 30 s, not left five minutes with its MIS stop on a flat MIS position.
     */
    @Test fun aConversionInKiteIsBookedAfterThirtySecondsNotFiveMinutes() {
        ArmLive.entered(ask(donchian, "conv"))
        watch()
        val sid = view().openReal.single().stopOrderId!!
        val t0 = ArmLive.testNow!!
        kite.positions["NFO:$sym:MIS"] = 0 to 0.0
        kite.positions["NFO:$sym:NRML"] = 30 to 200.0
        ArmLive.testNow = t0.plusMinutes(1); watch()
        assertTrue(view().closed.isEmpty())
        ArmLive.testNow = t0.plusMinutes(1).plusSeconds(30); watch()
        assertEquals("closed_at_zerodha", view().closed.single().why)
        assertFalse("its MIS stop taken out", kite.order(sid).working)
    }

    /**
     * 18 Nov 2026 (second review): a lost entry one book took back a moment ago counts for the other book's lost entry on the same
     * contract (never one fill taken twice); a book's own earlier claims never block it; another contract is apart.
     */
    @Test fun aLostEntryTakenBackByOneBookCountsForTheOther() {
        assertEquals(30, ArmLive.takeLost("arm", "NFO", sym, "MIS") { other -> (30 - other).coerceAtLeast(0) })
        assertEquals("the ORB book sees the arms' claim", 0, ArmLive.takeLost("orb", "NFO", sym, "MIS") { other -> (30 - other).coerceAtLeast(0) })
        assertEquals("its own claims never count", 30, ArmLive.takeLost("arm", "NFO", sym, "MIS") { other -> (30 - other).coerceAtLeast(0) })
        assertEquals("another product apart", 30, ArmLive.takeLost("orb", "NFO", sym, "NRML") { other -> (30 - other).coerceAtLeast(0) })
        ArmLive.wipe()
        assertEquals("forgotten with the erase", 30, ArmLive.takeLost("orb", "NFO", sym, "MIS") { other -> (30 - other).coerceAtLeast(0) })
    }

    /** 18 Nov 2026: the app's own sale of another trade on the contract never confirms a short read of this one. */
    @Test fun anotherTradesOwnExitNeverConfirmsAShortRead() {
        ArmLive.entered(ask(donchian, "x1"))
        ArmLive.entered(ask(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, "x2"))
        watch()
        legs["x1"] = donchian to 0                                   // x1's paper exit: the app sells its 30
        watch()
        assertEquals("paper_exit", view().closed.single().why)
        assertEquals(30, held())
        val sid = view().openReal.single().stopOrderId!!
        kite.positions["NFO:$sym:MIS"] = 0 to 0.0                  // then one read short of x2's 30
        watch()
        assertEquals("x2 kept", "x2", view().openReal.single().ref)
        assertEquals(1, view().closed.size)
        assertTrue("its stop rests", kite.order(sid).working)
    }

    /** 18 Nov 2026 (review): a part Boss sold earlier, already booked, never confirms a later short read of the rest. */
    @Test fun anEarlierHandSaleAlreadyBookedNeverConfirmsALaterShortRead() {
        ArmLive.testLotCap = 0
        ArmLive.entered(ask(donchian, "part", qty = 60))
        watch()
        kite.soldInKite(sym, 30, 205.0)                                  // Boss sells one lot in Kite
        ArmLive.testNow = Market.now().plusMinutes(1)                    // booked a moment after his sale
        watch()
        assertEquals("closed_at_zerodha", view().closed.single().why)
        val sid = view().openReal.single().stopOrderId!!
        kite.positions["NFO:$sym:MIS"] = 0 to 0.0                      // later, one read short of the rest
        ArmLive.testNow = ArmLive.testNow!!.plusMinutes(5)
        watch()
        assertEquals("the rest kept", 30, view().openReal.single().qty)
        assertEquals(1, view().closed.size)
        assertTrue("its stop rests", kite.order(sid).working)
    }

    /** A FINNIFTY option (lot 60) at 309, as on 18 Nov. */
    private fun finnifty(): Pair<Paper.Contract, String> {
        val s = "FINNIFTY26NOV28100PE"
        kite.instruments += FakeKite.Ins(22_000_777, s, "FINNIFTY", expiry, 28_100.0, "PE", 60)
        kite.quote("NFO:$s", 309.0, 308.95, 309.05)
        java.io.File(context.filesDir, "kite_instruments.json").delete(); runBlocking { Broker.instruments() }
        return Paper.Contract("FINNIFTY-TEST-28100PE", "FINNIFTY", expiry, 28_100.0, Right.PE, 60, "NSE_FO|FINPE") to s
    }

    /**
     * 18 Nov 2026 (Boss's live R51 FINNIFTY 28100 PE, 60 @ 309.00): the full step lock on its own fill and lot - each +₹500 a lot of
     * its best from +₹1,000 MODIFIES the same Zerodha stop up (never down, never a second one), up to +₹4,000 a lot at 377.35 (the
     * +₹3,500 lock, 369.45); falling back, Zerodha's stop sells it there - the step lock's exit, no sale of the app's own.
     */
    @Test fun aLiveFinniftyLotsStepLockIsMovedUpStepByStepAndSoldAtTheThreeAndAHalfThousandLock() {
        val (c, s) = finnifty()
        legs["fin"] = donchian to 60
        ArmLive.entered(ArmLive.Ask(donchian, "fin", c, 1, 60, 309.0, stop = 262.65, fixed = true, lock = true))
        val p0 = view().openReal.singleOrNull() ?: error("not bought: ${view().refused} ${view().notMirrored}")
        assertEquals(60, p0.qty); assertEquals(309.0, p0.entry, 1e-9); assertTrue(p0.fixed && p0.lock)
        val id = p0.stopOrderId!!
        assertEquals("its own -15%", 262.65, kite.order(id).trigger, 1e-9)
        val ladder = com.optionslab.engine.orb.StepTrail.Ladder(309.0, 60, 60, com.optionslab.engine.orb.StepTrail.Lock.L500, 0.05)
        assertEquals("+₹4,000 a lot", 377.35, ladder.trigger(6)!!, 1e-9)
        assertEquals("+₹3,500 a lot locked", 369.45, ladder.keep(6)!!, 1e-9)
        fun at(px: Double) { kite.quote("NFO:$s", px, px - 0.05, px + 0.05); watch() }
        for (k in 0..6) {
            at(ladder.trigger(k)!!)
            assertEquals("step $k", ladder.keep(k)!!, kite.order(id).trigger, 1e-9)
            assertEquals(ladder.keep(k)!!, view().openReal.single().stopTrigger!!, 1e-9)
            at(ladder.keep(k)!! + 1.0)                                       // a dip above it: never down
            assertEquals(ladder.keep(k)!!, kite.order(id).trigger, 1e-9)
        }
        assertEquals("the same order each time: never a second stop", 1, kite.placed.count { it.form["order_type"] == "SL" })
        assertEquals("never a sale of its own", 0, kite.placed.count { it.form["order_type"] == "MARKET" && it.form["transaction_type"] == "SELL" })
        // Down through the lock: Zerodha's stop sells it at 369.40.
        kite.fill(id, 369.40)
        watch()
        val done = view().closed.single()
        assertEquals(com.optionslab.engine.orb.StepTrail.WHY_LOCK, done.why)
        assertEquals(369.40, done.exit!!, 1e-9)
        assertTrue("about +₹3,500 after charges: ${done.net}", done.net!! > 3_400 && done.net!! < 3_700)
        assertEquals("no second exit", 0, kite.placed.count { it.form["order_type"] == "MARKET" && it.form["transaction_type"] == "SELL" })
        assertEquals(0, kite.positions["NFO:$s:MIS"]?.first ?: 0)
    }

    /**
     * 18 Nov 2026: the last step's modify refused by Zerodha - the stop stays one step lower at Zerodha, and the app sells itself (its
     * stop out first, one MARKET sale) when the price comes back to the +₹3,500 lock.
     */
    @Test fun aRefusedStepLockModifyIsSoldByTheAppAtItsLevel() {
        val (c, s) = finnifty()
        legs["fin2"] = donchian to 60
        ArmLive.entered(ArmLive.Ask(donchian, "fin2", c, 1, 60, 309.0, stop = 262.65, fixed = true, lock = true))
        val id = view().openReal.single().stopOrderId!!
        val ladder = com.optionslab.engine.orb.StepTrail.Ladder(309.0, 60, 60, com.optionslab.engine.orb.StepTrail.Lock.L500, 0.05)
        fun at(px: Double) { kite.quote("NFO:$s", px, px - 0.05, px + 0.05); watch() }
        for (k in 0..5) at(ladder.trigger(k)!!)
        assertEquals(361.1, kite.order(id).trigger, 1e-9)
        kite.modifyRefusals = 1
        at(377.35)
        assertEquals("refused: still one step lower at Zerodha", 361.1, kite.order(id).trigger, 1e-9)
        assertEquals("its best is kept", 377.35, view().openReal.single().best!!, 1e-9)
        at(369.40)                                                          // at or under the +₹3,500 lock (369.45)
        val sells = kite.placed.filter { it.form["order_type"] == "MARKET" && it.form["transaction_type"] == "SELL" }
        assertEquals("one sale of the app's own", 1, sells.size); assertEquals("60", sells.single().form["quantity"])
        assertEquals("its stop taken out first", "CANCELLED", kite.order(id).status)
        assertEquals(com.optionslab.engine.orb.StepTrail.WHY_LOCK, view().closed.single().why)
        assertEquals(0, kite.positions["NFO:$s:MIS"]?.first ?: 0)
    }
}
