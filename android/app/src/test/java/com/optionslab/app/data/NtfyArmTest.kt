package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.signals.NtfyRules
import com.optionslab.ira.AutoChange
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * ntfy alerts ([NtfyArm], 25 Oct): with the app in Live, logged in to Zerodha, real orders allowed and "AI trades go live" on,
 * a clear message is bought on PAPER only (its own Trade live switch is off), each message is read once whatever happens (a
 * duplicate, a restart, out-of-order ids), stale / out-of-hours / ambiguous ones are not traded with the reason shown, its stop
 * rests in the paper book, an exit message never sells short, the kill switch and the day's limit refuse, the expiry day squares
 * off at 15:05, the removed Telegram arm's open trade is still managed, and not one order reaches Zerodha.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class NtfyArmTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val ce = "NSE_FO|SP25000CE"
    private val pe = "NSE_FO|SP25000PE"
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")

    private fun at(t: LocalTime, s: Long = 0) { Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(s).atZone(IST).toInstant(), IST) }

    /** The options' minutes to [until]: [px] by the minute each starts; NIFTY flat at 25,010 (ATM 25,000). */
    private fun market(until: LocalTime, px: (LocalTime) -> Double) {
        val o = ArrayList<FakeUpstox.Candle>(); val i = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { val c = px(t); o += FakeUpstox.Candle(t, c, c, c, c); i += FakeUpstox.Candle(t, 25_010.0, 25_010.0, 25_010.0, 25_010.0); t = t.plusMinutes(1) }
        upstox.minutes[ce] = o; upstox.minutes[pe] = o; upstox.minutes[niftyKey] = i
    }
    private val rally: (LocalTime) -> Double = { t -> if (t.isBefore(LocalTime.of(9, 35))) 121.0 else 141.0 }

    private fun listContracts(expiry: LocalDate) = AutomationSupport.contracts(context, listOf(
        Upstox.Contract("NIFTY", expiry, 25_000.0, Right.CE, 65, ce, "NIFTY-SP-25000CE"),
        Upstox.Contract("NIFTY", expiry, 25_000.0, Right.PE, 65, pe, "NIFTY-SP-25000PE")))

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        day = AutomationSupport.tradingDayAt(9, 20).toLocalDate()
        at(LocalTime.of(9, 20))
        listContracts(day.plusDays(6))
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    private fun ev(id: String, text: String, t: LocalTime = LocalTime.of(9, 28), s: Long = 4, title: String? = null) =
        NtfyRules.Event(id, day.atTime(t).plusSeconds(s).atZone(IST).toEpochSecond(), "message", text, title)

    /** [text] arrives as message [id] at [t] (and is read at once). New messages counted. */
    private fun msg(id: String, text: String, t: LocalTime = LocalTime.of(9, 28), s: Long = 4, title: String? = null): Int {
        at(t, s + 1); market(t.plusMinutes(1), rally)
        return runBlocking { NtfyArm.receive(listOf(ev(id, text, t, s, title)), SecurePrefs.getString(NtfyReader.K_PREFIX).orEmpty()) }
    }
    private fun tick(t: LocalTime, s: Long = 0, px: (LocalTime) -> Double = rally) { at(t, s); market(t, px); runBlocking { NtfyArm.tick() } }
    private fun v() = NtfyArm.view.value
    private fun alert(id: String) = v().alerts.single { it.id == id }
    private fun on() = runBlocking { NtfyArm.setOn(true) }

    @Test fun offByDefaultNothingIsBought() {
        assertEquals(1, msg("m1", "BUY NIFTY 25000 CE"))
        assertFalse(v().on)
        assertEquals(NtfyArm.NOT_TRADED, alert("m1").state); assertTrue(alert("m1").why.contains("off"))
        tick(LocalTime.of(9, 29))
        assertTrue(v().open.isEmpty()); assertTrue(Paper.state.orders.isEmpty())
        assertFalse(NtfyArm.wantsPass())
        assertNothingAtZerodha()
    }

    @Test fun aClearMessageIsBoughtOnPaperItsStopRestsAndTheTargetSellsIt() {
        assertTrue(runBlocking { NtfyArm.setOn(true) }.contains("not a researched or proven strategy"))
        msg("m1", "NIFTY 25000 CE BUY SL 100 TGT 140")
        assertEquals(NtfyArm.PENDING, alert("m1").state)
        assertTrue(NtfyArm.wantsPass())
        tick(LocalTime.of(9, 28), 30)
        val p = v().open.single()
        assertTrue(p.symbol, p.symbol.startsWith("NIFTY") && p.symbol.endsWith("25000CE"))
        assertEquals(65, p.qty); assertEquals(100.0, p.stop, 1e-9); assertFalse(p.ourStop); assertEquals(140.0, p.target)
        assertEquals(NtfyArm.TRADED, alert("m1").state); assertTrue(alert("m1").why, alert("m1").why.startsWith("bought"))
        val stop = Paper.state.orders.single { it.orderId == p.stopOrderId }
        assertEquals("SL-M", stop.priceType); assertEquals("SELL", stop.action); assertEquals(100.0, stop.triggerPrice!!.toDouble(), 1e-9)
        // A second pass never buys it twice.
        tick(LocalTime.of(9, 31))
        assertEquals(1, v().open.size + v().closed.size)
        // 09:35 trades 141: the target (140) sells it all; the resting stop is taken out.
        at(LocalTime.of(9, 36)); market(LocalTime.of(9, 36), rally); runBlocking { NtfyArm.manageOnly() }
        val done = v().closed.single()
        assertEquals("target", done.why); assertTrue(done.net!! > 0)
        assertEquals("cancelled", Paper.state.orders.single { it.orderId == p.stopOrderId }.status)
        assertNothingAtZerodha()
    }

    @Test fun theStrikeRuleAndOurStopWhenTheMessageNamesNeither() {
        on()
        msg("m1", "NIFTY bearish")
        tick(LocalTime.of(9, 29))
        val p = v().open.single()
        assertTrue(p.symbol, p.symbol.endsWith("25000PE"))
        assertTrue(p.ourStop); assertEquals(NtfyRules.stop(null, null, p.entry, NtfyRules.Settings()).first, p.stop, 1e-9)
        assertEquals(NtfyRules.target(null, p.entry), p.target!!, 1e-9)
        assertNothingAtZerodha()
    }

    @Test fun everyMessageIsReadOnceAfterDuplicatesRestartsAndOutOfOrderIds() {
        on()
        assertEquals(1, msg("m2", "BUY NIFTY 25000 CE"))
        assertEquals("a duplicate delivery", 0, msg("m2", "BUY NIFTY 25000 CE"))
        // Written before anything is decided: a restart (the book read again from its file) still knows it.
        NtfyArm.wipe()
        assertEquals("after a restart", 0, msg("m2", "BUY NIFTY 25000 CE"))
        assertEquals("an older id arriving late is still read once", 1, msg("m1", "NIFTY CE PE"))
        assertEquals(0, msg("m1", "NIFTY CE PE"))
        assertEquals(2, v().alerts.size)
        assertEquals("m1", v().last)
        tick(LocalTime.of(9, 29))
        assertEquals(1, v().open.size)
        assertTrue(alert("m1").why, alert("m1").why.contains("both CE and PE"))
        // Keepalive and open events are not messages.
        assertEquals(0, runBlocking { NtfyArm.receive(listOf(NtfyRules.Event("k", 1, "keepalive", null, null), NtfyRules.Event("o", 1, "open", null, null))) })
        assertNothingAtZerodha()
    }

    @Test fun staleAndOutOfHoursMessagesAreNotTraded() {
        on()
        // Sent 10 minutes before it was read.
        at(LocalTime.of(9, 40)); market(LocalTime.of(9, 40), rally)
        runBlocking { NtfyArm.receive(listOf(ev("old", "BUY NIFTY 25000 CE", LocalTime.of(9, 30)))) }
        assertTrue(alert("old").why, alert("old").why.startsWith("stale, not traded"))
        // Yesterday's.
        runBlocking { NtfyArm.receive(listOf(NtfyRules.Event("y", day.minusDays(1).atTime(14, 0).atZone(IST).toEpochSecond(), "message", "BUY NIFTY CE", null))) }
        assertTrue(alert("y").why.contains("earlier day"))
        // Before the market opens.
        msg("early", "BUY NIFTY 25000 CE", LocalTime.of(9, 5))
        tick(LocalTime.of(9, 6))
        assertTrue(alert("early").why, alert("early").why.contains("before the market opens"))
        // After the square-off.
        msg("late", "BUY NIFTY 25000 CE", LocalTime.of(15, 20))
        tick(LocalTime.of(15, 20), 30)
        assertTrue(alert("late").why, alert("late").why.contains("after the square-off"))
        assertTrue(v().open.isEmpty() && Paper.state.orders.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun aHolidayOrWeekendIsMarketClosed() {
        on()
        var d = day.plusDays(1)
        while (Market.isTradingDay(d)) d = d.plusDays(1)
        day = d
        msg("w", "BUY NIFTY 25000 CE", LocalTime.of(10, 0))
        tick(LocalTime.of(10, 0), 30)
        assertTrue(alert("w").why, alert("w").why.contains("market closed"))
        assertTrue(Paper.state.orders.isEmpty())
    }

    @Test fun ambiguousMessagesAreRefusedWithTheReason() {
        on()
        for ((id, text, why) in listOf(Triple("a", "NIFTY BANKNIFTY CE", "two indices"), Triple("b", "BUY RELIANCE 2500 CE", "no index"),
                Triple("c", "CRUDEOIL CE", "MCX"), Triple("d", "BTC call", "crypto"), Triple("e", "NIFTY FUT buy", "futures"))) {
            msg(id, text)
            assertEquals(text, NtfyArm.NOT_TRADED, alert(id).state); assertTrue(alert(id).why, alert(id).why.contains(why))
        }
        assertFalse(NtfyArm.wantsPass())
    }

    @Test fun anExitMessageClosesWhatItHoldsAndNeverSellsShort() {
        on()
        msg("x0", "EXIT NIFTY")
        tick(LocalTime.of(9, 28), 20)
        assertTrue(alert("x0").why, alert("x0").why.contains("nothing held on NIFTY") && alert("x0").why.contains("never a short"))
        assertTrue("no sale on a flat book", Paper.state.orders.none { it.action == "SELL" })
        msg("b1", "BUY NIFTY 25000 CE", LocalTime.of(9, 29))
        tick(LocalTime.of(9, 29), 30)
        val p = v().open.single()
        msg("x1", "sell nifty", LocalTime.of(9, 31))
        tick(LocalTime.of(9, 31), 30)
        assertEquals("exit message", v().closed.single().why)
        assertEquals(NtfyArm.CLOSED, alert("x1").state)
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        // Another exit with nothing left: no order, never a short.
        val orders = Paper.state.orders.size
        msg("x2", "close nifty", LocalTime.of(9, 33))
        tick(LocalTime.of(9, 33), 30)
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(Paper.state.positions.none { it.quantity < 0 })
        assertNothingAtZerodha()
    }

    @Test fun killSwitchTheDaysLimitAndOneTradeAnIndex() {
        on()
        runBlocking { NtfyArm.setSettings(NtfyRules.Settings(maxPerDay = 1)) }
        SecurePrefs.put("g.kill", true)
        msg("k", "BUY NIFTY 25000 CE")
        tick(LocalTime.of(9, 28), 30)
        assertTrue(alert("k").why, alert("k").why.contains("kill switch"))
        SecurePrefs.put("g.kill", false)
        msg("b1", "BUY NIFTY 25000 CE", LocalTime.of(9, 29))
        tick(LocalTime.of(9, 29), 30)
        assertEquals(1, v().open.size)
        msg("b2", "BUY NIFTY 25000 PE", LocalTime.of(9, 31))
        tick(LocalTime.of(9, 31), 30)
        assertTrue(alert("b2").why, alert("b2").why.contains("1 trades today already"))
        runBlocking { NtfyArm.setSettings(NtfyRules.Settings(maxPerDay = 5)) }
        msg("b3", "BUY NIFTY 25000 PE", LocalTime.of(9, 32))
        tick(LocalTime.of(9, 32), 30)
        assertTrue(alert("b3").why, alert("b3").why.contains("already holds a NIFTY trade"))
        assertNothingAtZerodha()
    }

    @Test fun theExpiryDaySquaresOffAt1505() {
        listContracts(day)
        on()
        msg("e1", "BUY NIFTY 25000 CE TGT 400")
        tick(LocalTime.of(9, 28), 30)
        assertTrue(v().open.single().expiryDay)
        tick(LocalTime.of(15, 5))
        assertEquals("square-off", v().closed.single().why)
        assertNothingAtZerodha()
    }

    @Test fun theLiveSwitchOffMeansPaperOnlyAndTheLivePathOnlyHearsIt() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        try {
            on()
            assertFalse("its own Trade live starts off", ArmLive.view.value.on(LiveMirror.NTFY_ALERTS))
            msg("m1", "BUY NIFTY 25000 CE SL 100")
            tick(LocalTime.of(9, 28), 30)
            val p = v().open.single()
            assertTrue(heard.toString(), heard.any { it == "entered|${LiveMirror.NTFY_ALERTS}|${p.ref}|65|100.0" })
            assertEquals(listOf(ArmLive.PaperLeg(p.ref, LiveMirror.NTFY_ALERTS, 65)), ArmLive.legsForTest(LiveMirror.NTFY))
            assertNothingAtZerodha()
        } finally { ArmLive.testHeard = null }
    }

    @Test fun jsonSignalsTestOnesAreNeverTradedAndASignalIdTradesOnce() {
        on()
        fun sig(test: Boolean, id: String) = """{"type":"signal","test":$test,"source":"IraThinker","version":51,"strategy":"S","signalId":"$id",""" +
            """"symbol":"NIFTY","yahooSymbol":"^NSEI","side":"BUY_PUT","entry":null,"stop":null,"target1":null,"target2":null,"score":null,""" +
            """"option":{"strike":25000,"expiry":null,"kind":"PE","premium":null}}"""
        msg("t", sig(true, "TEST"), title = "signal")
        assertTrue(alert("t").why, alert("t").why.contains("test signal"))
        msg("s1", sig(false, "SIG-1"), title = "signal")
        assertEquals(NtfyArm.PENDING, alert("s1").state)
        msg("s2", sig(false, "SIG-1"), LocalTime.of(9, 28), 20, title = "signal")
        assertTrue(alert("s2").why, alert("s2").why.contains("same signal id"))
        tick(LocalTime.of(9, 28), 30)
        assertTrue(v().open.single().symbol.endsWith("25000PE"))
        assertTrue(alert("s1").why, alert("s1").why.contains("IraThinker"))
        assertNothingAtZerodha()
    }

    /** 25 Oct (Boss: "add normal stocks"): Boss's own stock signal buys the option it names; the stock's own stop closes it. */
    @Test fun aStockSignalBuysItsOptionAndTheStocksLevelClosesIt() {
        val opt = "NSE_FO|ADANI2500PE"; val cash = "NSE_EQ|INE423A01024"
        StockOptions.testFetch = { listOf(com.optionslab.engine.Upstox.Contract("ADANIENT", day.plusDays(2), 2500.0, Right.PE, 300, opt, "ADANIENT 2500 PE")) to
            mapOf("ADANIENT" to cash) }
        StockOptions.refreshNow()
        on()
        fun stock(px: Double, until: LocalTime) {
            val o = ArrayList<FakeUpstox.Candle>(); val e = ArrayList<FakeUpstox.Candle>()
            var t = LocalTime.of(9, 15)
            while (t.isBefore(until)) { o += FakeUpstox.Candle(t, 26.0, 26.0, 26.0, 26.0); e += FakeUpstox.Candle(t, px, px, px, px); t = t.plusMinutes(1) }
            upstox.minutes[opt] = o; upstox.minutes[cash] = e
        }
        val j = """{"type":"signal","test":false,"source":"IraThinker","version":51,"strategy":"MANUAL","signalId":"MANUAL|ADANIENT.NS|ORB_DOWN|1",""" +
            """"tsIST":"${day.atTime(9, 28)}+05:30","symbol":"Adani Enterprises","yahooSymbol":"ADANIENT.NS","side":"BUY_PUT","entry":2503.0,""" +
            """"stop":2522.9,"target1":2463.2,"target2":2443.3,"score":0.6,"option":{"strike":2500,"expiry":"${day.plusDays(2)}","kind":"PE","premium":26.4}}"""
        msg("s1", j, title = "signal")
        stock(2503.0, LocalTime.of(9, 29))
        at(LocalTime.of(9, 28), 30); runBlocking { NtfyArm.tick() }
        val p = v().open.single()
        assertTrue(p.symbol, p.symbol.startsWith("ADANIENT") && p.symbol.endsWith("2500PE"))
        assertEquals(300, p.qty); assertEquals(2522.9, p.idxStop!!, 1e-9)
        // The stock rises through the signal's own stop (2522.9): sold.
        stock(2525.0, LocalTime.of(9, 32)); at(LocalTime.of(9, 32)); runBlocking { NtfyArm.tick() }
        assertEquals("index stop", v().closed.single().why)
        // Stock options switched off: a stock signal is not traded; a strike not listed is not traded.
        runBlocking { NtfyArm.setSettings(NtfyRules.Settings(stocks = false)) }
        msg("s2", j.replace("|1\"", "|2\""), LocalTime.of(9, 33), title = "signal")
        at(LocalTime.of(9, 33), 30); runBlocking { NtfyArm.tick() }
        assertTrue(alert("s2").why, alert("s2").why.contains("stock options are switched off"))
        runBlocking { NtfyArm.setSettings(NtfyRules.Settings(stocks = true)) }
        msg("s3", j.replace("|1\"", "|3\"").replace("\"strike\":2500", "\"strike\":2600"), LocalTime.of(9, 34), title = "signal")
        at(LocalTime.of(9, 34), 30); runBlocking { NtfyArm.tick() }
        assertTrue(alert("s3").why, alert("s3").why.contains("not listed"))
        assertNothingAtZerodha()
    }

    @Test fun aPrefixFiltersMessages() {
        on()
        SecurePrefs.put(NtfyReader.K_PREFIX, "IRA:")
        msg("p1", "BUY NIFTY 25000 CE")
        assertTrue(alert("p1").why.contains("prefix"))
        msg("p2", "ira: BUY NIFTY 25000 CE", LocalTime.of(9, 28), 10)
        assertEquals(NtfyArm.PENDING, alert("p2").state)
    }

    @Test fun theBookIsRestoredFirstAfterARestart() {
        on()
        msg("m1", "BUY NIFTY 25000 CE")
        tick(LocalTime.of(9, 28), 30)
        assertTrue(File(context.noBackupFilesDir, "ntfy_arm.vault").exists())
        NtfyArm.wipe()
        assertFalse(NtfyArm.holding())
        assertTrue(runBlocking { PositionsFirst.restore() })
        assertTrue(NtfyArm.holding())
        assertTrue(NtfyArm.view.value.on)
    }

    @Test fun theRemovedTelegramArmsOpenTradeIsMovedAndStillManaged() {
        // A paper position the old arm opened, and its book as it saved it.
        val c = Paper.contractFor("NIFTY", day.plusDays(6), 25_000.0, Right.CE)!!
        market(LocalTime.of(9, 21), rally); at(LocalTime.of(9, 21))
        val buy = runBlocking { Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null) }
        assertTrue(buy.message, buy.ok)
        val pos = JSONObject().put("chat", "c0a1b2c3d4").put("call", "r1").put("index", "NIFTY").put("symbol", c.symbol).put("right", "CE")
            .put("lots", 1).put("qty", 65).put("entry", 121.0).put("at", day.atTime(9, 21).toString()).put("stop", 90.0).put("ours", true)
            .put("rule", "t1").put("exp", false).put("target", 300.0)
        val old = File(context.noBackupFilesDir, NtfyArm.OLD_FILE)
        Vault.LastWrite().write(old, JSONObject().put("positions", JSONArray().put(pos)).toString().toByteArray())
        NtfyArm.wipe()
        val moved = runBlocking { NtfyArm.refresh() }.open.single()
        assertTrue(moved.legacy); assertEquals("telegram:c0a1b2c3d4:r1", moved.ref)
        assertFalse("the old book goes once the new one holds it", old.exists())
        // An exit message of ntfy never touches it; its own square-off does.
        on()
        msg("x", "EXIT NIFTY", LocalTime.of(9, 30))
        tick(LocalTime.of(9, 30), 30)
        assertTrue(v().open.single().legacy)
        tick(LocalTime.of(15, 15))
        assertEquals("square-off", v().closed.single().why)
        assertEquals(0, Paper.state.positions.filter { it.symbol == c.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    @Test fun theChangeLogsUndoSwitchesItOff() {
        on()
        val change = AutoChange.ntfyPaperOn()
        assertEquals(AutoChange.NTFY_PAPER, change.target)
        assertTrue(Autopilot.apply(AutoChange.inverse(change, "undone by you"), false)!!.contains("off"))
        assertFalse(NtfyArm.view.value.on)
        assertTrue(Autopilot.apply(change, false)!!.contains("on"))
        assertTrue(NtfyArm.view.value.on)
        // The removed Telegram chats' old entries: nothing to undo.
        assertEquals(null, Autopilot.apply(AutoChange.Change(AutoChange.Kind.PAPER_ARM_ON, AutoChange.REMOVED_CHAT_PAPER + "c1", AutoChange.SWITCHED_OFF,
            AutoChange.ON, "old", AutoChange.Evidence(0, 0.0, com.optionslab.ira.AgentLessons.Confidence.STRONG), AutoChange.Risk.RAISE, AutoChange.Scope.PAPER), false))
    }

    @Test fun jarvisSaysTheLastMessagesAndWhatItIs() {
        on()
        msg("m1", "NIFTY CE PE")
        val said = runBlocking { NtfyArm.talk() }
        assertTrue(said, said.startsWith("ntfy alerts is on, on paper - an external message, not a researched or proven strategy."))
        assertTrue(said, said.contains("NIFTY CE PE") && said.contains("both CE and PE"))
    }

    @Test fun theArmHasNoLivePathOfItsOwnAndNoNetworkOrDiskOnTheOrderPath() {
        val dir = listOf(File("src/main/java/com/optionslab/app/data"), File("app/src/main/java/com/optionslab/app/data")).first { it.exists() }
        for (name in listOf("NtfyArm.kt", "NtfyReader.kt", "RemovedFeatureCleanup.kt")) {
            val code = File(dir, name).readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }.joinToString("\n")
            for (bad in listOf("Broker.", "Kite.", "KiteOrders", "placeLive", "LiveOrders", "Zerodha", "live = true")) assertFalse("$name: $bad", code.contains(bad))
        }
        // The order door (Broker / Paper.place) never calls into the reader or the arm's book.
        for (name in listOf("Broker.kt", "Paper.kt", "FastPath.kt")) {
            val code = File(dir, name).readText()
            assertFalse("$name reads ntfy", code.contains("NtfyReader") || code.contains("NtfyArm.receive") || code.contains("NtfyArm.refresh"))
        }
    }
}
