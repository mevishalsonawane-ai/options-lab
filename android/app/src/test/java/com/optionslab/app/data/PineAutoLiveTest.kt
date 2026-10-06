package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Reply
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
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
import java.time.ZonedDateTime

/**
 * Pine auto-trade in Live ([PineAuto]) against the fake Kite, on candles and a clock the test controls
 * ([PineAuto.testBars], [PineAuto.testNow]): it waits for the next change of signal, never trades on stale
 * candles, buys the ATM option on a change, sells only what is held less other working sells, settles a buy
 * whose reply was lost, pauses for the day at its loss limit, and in alerts-only mode sends nothing.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PineAutoLiveTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val ce = "BANKNIFTY26OCT52000CE"
    private val pe = "BANKNIFTY26OCT52000PE"
    private lateinit var now: ZonedDateTime
    private var closes: List<Double> = emptyList()
    private var candles: (() -> List<Upstox.Bar>)? = null
    private var id = 0L

    private val code = """
        //@version=5
        indicator("Level signals", overlay = true)
        level = input.float(52000, "Level")
        buy = close > level
        sell = close < level
        plotshape(buy, "Buy", shape.labelup, location.belowbar, color.green)
        plotshape(sell, "Sell", shape.labeldown, location.abovebar, color.red)
    """.trimIndent()

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        AutomationSupport.freshPine(context)
        now = AutomationSupport.tradingDayAt(11, 2)
        PineAuto.testNow = now
        PineAuto.testBars = { _, _ -> candles?.invoke() ?: AutomationSupport.bars(now, closes) }
        val expiry = now.toLocalDate().plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|1", ce),
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.PE, 30, "NSE_FO|2", pe)))
        kite.instruments += FakeKite.Ins(33_000_001, ce, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(33_000_002, pe, "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$ce", 300.0, 299.95, 300.05)
        kite.quote("NFO:$pe", 280.0, 279.95, 280.05)
        id = PineScripts.put(PineScripts.Item(0, "Level", code)).id
        auto()
    }

    @After fun down() {
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun auto(change: (PineScripts.Auto) -> PineScripts.Auto = { it }) =
        PineScripts.setAuto(id, change(PineScripts.Auto(symbol = "BANKNIFTY", interval = "5m", lots = 1, buy = "Buy", sell = "Sell", shortWith = "exit")))

    private fun switchOn(pin: Boolean = true) = assertEquals("ok", runBlocking { PineAuto.arm(id, true, pinConfirmed = pin) })

    /** One pass of the watch, [minutes] after the last, on candles whose last close is [last]. */
    private fun pass(last: Double, minutes: Long = 5) {
        now = now.plusMinutes(minutes); PineAuto.testNow = now
        closes = List(9) { 51_900.0 } + last
        runBlocking { PineAuto.tick() }
    }

    private fun log() = PineAuto.log.value.filter { it.script == id }.joinToString("\n") { it.text }
    private fun held() = PineAuto.held.value[id]
    private fun side(s: String) = kite.placed.filter { it.form["transaction_type"] == s }

    /** Switched on while the signal says SELL, then it turns to BUY: the ATM call is bought. */
    private fun bought(lots: Int = 1) {
        if (lots != 1) auto { it.copy(lots = lots) }
        switchOn()
        pass(51_900.0)
        pass(52_010.0)
    }

    @Test fun aScriptSwitchedOnWaitsForTheNextChangeOfSignal() {
        switchOn()
        pass(52_010.0)                                             // already BUY when switched on: not jumped into
        pass(52_020.0)
        assertTrue(kite.requests.none { it.method == "POST" })
        assertTrue(log(), log().contains("Watching: the signal now is BUY; it trades on the next change"))
    }

    @Test fun aChangeOfSignalBuysTheAtmCallLive() {
        bought()
        val keys = setOf("tradingsymbol", "exchange", "transaction_type", "order_type", "quantity", "product", "tag")
        assertEquals(mapOf("tradingsymbol" to ce, "exchange" to "NFO", "transaction_type" to "BUY", "order_type" to "MARKET",
            "quantity" to "30", "product" to "MIS", "tag" to "irapine"), kite.placed.single().form.filterKeys { it in keys })
        val h = held()!!
        assertTrue(h.live); assertFalse(h.unconfirmed); assertEquals(30, h.qty); assertEquals(300.0, h.entry, 0.0)
        assertTrue(log(), log().contains("Bought 30 $ce at 300.00 (LIVE)"))
        pass(52_030.0)
        assertEquals("the same signal again buys nothing more", 1, kite.placed.size)
    }

    @Test fun staleCandlesAreNeverTradedOn() {
        switchOn()
        pass(51_900.0)
        // The feed failed today and handed back yesterday's candles, with a BUY on the last one.
        candles = { AutomationSupport.bars(now.minusDays(1), List(9) { 51_900.0 } + 52_010.0) }
        pass(52_010.0)
        // A stalled feed: its last candle is 40 minutes old.
        candles = { AutomationSupport.bars(now.minusMinutes(40), List(9) { 51_900.0 } + 52_010.0) }
        pass(52_010.0)
        assertTrue("nothing traded on stale candles", kite.placed.isEmpty())
        candles = null
        pass(52_010.0)
        assertEquals("fresh candles with the same signal do trade", 1, kite.placed.size)
    }

    @Test fun aChangeMissedWhileTheAppWasAwayIsCaughtUp() {
        switchOn()
        pass(51_900.0)
        // The watch did not run for 15 minutes; the signal turned BUY two candles ago, while it was away. Missed data is
        // not a pause: the day's candles are read again and the signal as it is now is traded.
        candles = { AutomationSupport.bars(now, List(7) { 51_900.0 } + List(3) { 52_010.0 }) }
        pass(52_010.0, minutes = 15)
        assertEquals("missed candles are caught up, not skipped", 1, kite.placed.size)
    }

    @Test fun aSlowPassThatSkippedACandleIsNotAPause() {
        switchOn()
        pass(51_900.0)
        // One pass took 10 minutes (a slow phone network): the signal turned BUY on the candle it skipped. Still bought.
        candles = { AutomationSupport.bars(now, List(8) { 51_900.0 } + List(2) { 52_010.0 }) }
        pass(52_010.0, minutes = 10)
        assertEquals("a skipped candle is not a pause", 1, kite.placed.size)
    }

    @Test fun aChangeOnTheNewestCandleAfterAPauseStillTrades() {
        switchOn()
        pass(51_900.0)
        // Away for 15 minutes, but the signal stayed SELL until the newest candle turned it BUY.
        pass(52_010.0, minutes = 15)
        assertEquals(1, kite.placed.size)
    }

    @Test fun nothingIsDecidedOutsideMarketHours() {
        switchOn()
        pass(51_900.0)
        now = now.withHour(8).withMinute(0)
        pass(52_010.0)
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun anExitSellsWhatIsHeldLessOtherWorkingSells() {
        bought(lots = 2)
        assertEquals("60", side("BUY").single().form["quantity"])
        // A protection's stop already rests on one lot of it.
        val stop = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 30, 30, "MIS", "SL", 240.0, 0.05, "NFO", "iraprotect", triggerPrice = 250.0), exit = true) }
        kite.requests.clear()
        pass(51_900.0)                                             // SELL signal; shortWith = exit
        assertEquals("60 held, 30 already covered: 30 sold", listOf("30"), side("SELL").map { it.form["quantity"] })
        assertEquals("the other exit is left resting", "TRIGGER PENDING", kite.order(stop).status)
        assertTrue(kite.requests.none { it.method == "DELETE" && it.path.endsWith("/$stop") })
    }

    @Test fun itsOwnEarlierExitIsCancelledNotAddedTo() {
        bought()
        // A sell of its own from an earlier pass (reply lost) is still resting at Zerodha.
        val own = runBlocking { Broker.placeOrder(Kite.Order(ce, Kite.Side.SELL, 30, 30, "MIS", "LIMIT", 900.0, 0.05, "NFO", "irapine"), exit = true) }
        kite.requests.clear()
        pass(51_900.0)
        assertTrue(kite.requests.any { it.method == "DELETE" && it.path == "/orders/regular/$own" })
        assertEquals("one market sell of what is held", listOf("30"), side("SELL").map { it.form["quantity"] })
        assertNull(held())
    }

    @Test fun aLostBuyReplyIsTrackedAndSettledFromTheOrderBook() {
        switchOn()
        pass(51_900.0)
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        kite.down += "/orders"
        pass(52_010.0)
        val h = held()!!
        assertTrue("held, unconfirmed: nothing more is bought meanwhile", h.unconfirmed)
        assertNull(h.order)
        assertTrue(AutomationSupport.alerts().any { it.contains("Zerodha did not confirm the buy of $ce") })
        kite.down.clear()
        pass(52_020.0)
        val settled = held()!!
        assertFalse(settled.unconfirmed)
        assertEquals(30, settled.qty); assertEquals(300.0, settled.entry, 0.0)
        assertEquals("one buy, never two", 1, side("BUY").size)
        assertTrue(log(), log().contains("Zerodha confirmed the buy"))
    }

    @Test fun theDailyLossLimitSellsAndPausesTheScriptForTheDay() {
        auto { it.copy(maxDayLoss = 1_000.0, stopPts = 100.0) }     // a stop wider than the fall: the day's limit sells
        bought()
        kite.quote("NFO:$ce", 250.0, 249.95, 250.05)              // (250 - 300) x 30 = -1,500
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("Daily loss limit reached: no more trades today"))
        assertEquals(-1_500.0, PineAuto.todayOf(id)!!, 0.01)
        pass(51_900.0); pass(52_010.0)                             // new signals: no new trade today
        assertEquals(1, side("BUY").size)
    }

    @Test fun resettingThePaperAccountNeverLiftsALiveDailyLossPause() {
        auto { it.copy(maxDayLoss = 1_000.0, stopPts = 100.0) }
        bought()
        kite.quote("NFO:$ce", 250.0, 249.95, 250.05)
        pass(52_010.0)
        assertTrue(log(), log().contains("Daily loss limit reached: no more trades today"))
        runBlocking { PineAuto.resetPaper() }
        assertEquals("the live day's loss is kept", -1_500.0, PineAuto.todayOf(id)!!, 0.01)
        pass(51_900.0); pass(52_010.0)
        assertEquals("still paused: no new live buy today", 1, side("BUY").size)
    }

    @Test fun alertsOnlyPlacesNothing() {
        auto { it.copy(mode = "alert") }
        switchOn()
        pass(51_900.0); pass(52_010.0)
        assertTrue(kite.requests.none { it.method == "POST" })
        assertTrue(log(), log().contains("Signal: BUY at 52010.00"))
        assertNull(held())
    }

    @Test fun liveNeedsThePinOnce() {
        switchOn(pin = false)
        pass(51_900.0); pass(52_010.0)
        assertTrue(kite.placed.isEmpty())
        assertTrue(log(), log().contains("Live needs your PIN once"))
    }

    @Test fun whatIsHeldIsSoldAt1515() {
        bought()
        now = now.withHour(15).withMinute(11)
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("15:15 square-off"))
        assertNull(held())
    }

    @Test fun theKillSwitchSellsAndStandsStill() {
        bought()
        SecurePrefs.put("g.kill", true)
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("the day's stop"))
        pass(51_900.0); pass(52_010.0)
        assertEquals("nothing new while the kill switch is on", 1, side("BUY").size)
    }

    @Test fun switchingOffSellsWhatItHolds() {
        bought()
        assertEquals("ok", runBlocking { PineAuto.arm(id, false) })
        assertEquals(1, side("SELL").size)
        assertNull(held())
        assertFalse(PineScripts.get(id)!!.auto.on)
    }

    // ---- Boss's 06 Oct fixes ------------------------------------------------------------------------------------

    /** A second script on the same symbol and chart, armed, with [code2] (another strategy unless it is the same code). */
    private fun second(code2: String, name: String = "Level 2"): Long {
        val id2 = PineScripts.put(PineScripts.Item(0, name, code2)).id
        PineScripts.setAuto(id2, PineScripts.Auto(on = true, symbol = "BANKNIFTY", interval = "5m", lots = 1, buy = "Buy", sell = "Sell", shortWith = "exit"))
        runBlocking { PineAuto.arm(id2, true, pinConfirmed = true) }
        return id2
    }

    @Test fun twoScriptsNeverHoldTheSameSideOfOneIndexAtOnce() {
        // Another strategy (its level differs) on the same index: both say BUY on the same candle; only the first buys.
        val id2 = second(code.replace("52000", "51950"))
        switchOn()
        pass(51_900.0)
        pass(52_010.0)
        assertEquals("one buy, never two", 1, side("BUY").size)
        assertTrue(held() != null)
        assertNull(PineAuto.held.value[id2])
        val log2 = PineAuto.log.value.filter { it.script == id2 }.joinToString("\n") { it.text }
        assertTrue(log2, log2.contains("Not bought: same_side_already_held: Pine #$id · Level holds "))
    }

    @Test fun anIdenticalScriptSavedBeforeThisUpdateIsSwitchedOffAndItsHoldingManagedToItsExit() {
        bought()                                                   // #id holds the call
        val id2 = second("// the same strategy, added again at 09:50\n" + code.replace("\n", "\n\n"), name = "Level again")
        // As saved before this update: the newer copy holds the call and the duplicates switch-off has not run yet.
        val f = java.io.File(context.noBackupFilesDir, "pine_auto.vault")
        val o = org.json.JSONObject(String(com.optionslab.app.security.Vault.readFileSteady(f)!!, Charsets.UTF_8))
        val h = o.getJSONObject("held").getJSONObject(id.toString())
        o.put("held", org.json.JSONObject().put(id2.toString(), h))
        o.remove("migrated")
        PineAuto.wipe()
        com.optionslab.app.security.Vault.writeFile(f, o.toString().toByteArray(Charsets.UTF_8))
        runBlocking { PineAuto.load() }
        pass(52_010.0)
        assertTrue("the oldest stays armed", PineScripts.get(id)!!.auto.on)
        assertFalse("the repeat is switched off", PineScripts.get(id2)!!.auto.on)
        assertNotNull("never deleted", PineScripts.get(id2))
        val log2 = { PineAuto.log.value.filter { it.script == id2 }.joinToString("\n") { it.text } }
        assertTrue(log2(), log2().contains("Switched off: the same strategy as #$id"))
        assertNotNull("its call is still held, managed to its exit", PineAuto.held.value[id2])
        assertTrue("not dumped", side("SELL").isEmpty())
        assertEquals("nothing new bought", 1, side("BUY").size)
        pass(51_900.0)                                             // its signal turns: the held call is sold (its exit)
        assertEquals(1, side("SELL").size)
        assertNull(PineAuto.held.value[id2])
        pass(52_010.0)                                             // BUY again: only the armed original buys
        assertEquals(2, side("BUY").size)
        assertNotNull(held())
        assertNull(PineAuto.held.value[id2])
        pass(51_900.0); pass(52_010.0)
        assertTrue("the switch-off runs once and never re-arms", !PineScripts.get(id2)!!.auto.on)
    }

    @Test fun aLosingScriptSwitchedOffOnBosssYesKeepsItsHoldingToItsExit() {
        bought()
        assertEquals("ok", runBlocking { PineAuto.windDown(id, "paper record negative (15 trades after charges; Boss approved)") })
        assertFalse(PineScripts.get(id)!!.auto.on)
        assertNotNull("not sold now", held())
        assertTrue(side("SELL").isEmpty())
        assertTrue(log(), log().contains("Switched off: paper record negative"))
        pass(52_020.0)
        assertEquals("nothing more bought", 1, side("BUY").size)
        pass(51_900.0)                                             // its exit: the signal turns
        assertEquals(1, side("SELL").size)
        assertNull(held())
        pass(52_010.0)
        assertEquals("never switched back on", 1, side("BUY").size)
        assertEquals("already off", runBlocking { PineAuto.windDown(id, "again") })
    }

    @Test fun aHoldingClosedOutsideTheAppIsForgotten() {
        bought()
        kite.position(ce, 0, 0.0, product = "MIS")                 // closed in the Kite app
        pass(52_010.0)
        assertNull(held())
        assertTrue(side("SELL").isEmpty())
        assertTrue(log(), log().contains("is no longer held"))
    }

    @Test fun theProfitLockSellsWhatGaveBackPastItsLockedLevel() {
        auto { it.copy(targetPts = 100.0, profitLock = true) }
        bought()                                                   // 30 at 300
        // 55% of the way: the ladder locks 25 (325); the trail at +18.3% keeps half of 55 (327.50): the higher counts,
        // from the next look on.
        kite.quote("NFO:$ce", 355.0, 354.95, 355.05)
        pass(52_010.0)
        assertTrue("not sold on the look that set the best", side("SELL").isEmpty())
        assertEquals(355.0, held()!!.peak, 0.0)
        assertTrue(log(), log().contains("profit lock now at 327.50"))
        kite.quote("NFO:$ce", 320.0, 319.95, 320.05)
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("profit lock (locked at 327.50)"))
        assertNull(held())
        assertEquals("nothing new is bought by the lock", 1, side("BUY").size)
    }

    @Test fun withTheDefaultStopAndTargetTheLockSellsATenPercentGainThatFalls() {
        // Boss's case (2026-10-06): +10% fell back to +2%. Every script now has the stop 30, target 60 and the lock.
        val a = PineScripts.get(id)!!.auto
        assertTrue(a.profitLock); assertEquals(30.0, a.stopPts, 0.0); assertEquals(60.0, a.targetPts, 0.0)
        bought()                                                   // 30 at 300
        kite.quote("NFO:$ce", 330.0, 329.95, 330.05)              // +10%: half of the 30 gained is kept (315)
        pass(52_010.0)
        assertTrue("not sold on the look that set the best", side("SELL").isEmpty())
        assertEquals(330.0, held()!!.peak, 0.0)
        assertTrue(log(), log().contains("profit lock now at 315.00"))
        kite.quote("NFO:$ce", 320.0, 319.95, 320.05)              // still above 315: held
        pass(52_010.0)
        assertTrue(side("SELL").isEmpty())
        kite.quote("NFO:$ce", 306.0, 305.95, 306.05)              // +2%: below the lock
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("at 306.00: profit lock (locked at 315.00)"))
        assertNull(held())
        assertEquals("nothing new is bought by the lock", 1, side("BUY").size)
    }

    @Test fun theProfitLockCannotBeSwitchedOff() {
        // Boss's 06 Oct rule: a lock written off (an old screen, Jarvis, a restore) is saved on, and the same move is sold.
        auto { it.copy(targetPts = 100.0, profitLock = false, profitLockChosen = true) }
        assertTrue(PineScripts.get(id)!!.auto.profitLock)
        bought()
        kite.quote("NFO:$ce", 355.0, 354.95, 355.05)
        pass(52_010.0)
        kite.quote("NFO:$ce", 320.0, 319.95, 320.05)
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("profit lock (locked at 327.50)"))
    }

    // ---- Boss's 06 Oct rule: a stop-loss, a target and the profit lock on every Pine trade ---------------------------

    @Test fun aScriptWithoutStrategyExitIsSoldAtTheStop() {
        // The Level script is an indicator: no strategy.exit of its own. The app's stop (entry - 30) still sells it.
        bought()                                                   // 30 at 300
        kite.quote("NFO:$ce", 275.0, 274.95, 275.05)
        pass(52_010.0)
        assertTrue("above 270: held", side("SELL").isEmpty())
        kite.quote("NFO:$ce", 269.0, 268.95, 269.05)
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("at 269.00: stop-loss"))
        assertNull(held())
    }

    @Test fun aScriptWithoutStrategyExitIsSoldAtTheTargetAndTheLadderMovesOnTheWay() {
        bought()                                                   // 30 at 300
        kite.quote("NFO:$ce", 346.0, 345.95, 346.05)              // 77% of the way to +60: the ladder locks
        pass(52_010.0)
        assertTrue(side("SELL").isEmpty())
        assertTrue(log(), log().contains("profit lock now at"))
        kite.quote("NFO:$ce", 361.0, 360.95, 361.05)
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("at 361.00: target"))
    }

    @Test fun aZeroStopOrTargetIsNeverSavedAndArmingWithoutThemIsRefused() {
        auto { it.copy(stopPts = 0.0, targetPts = 0.0) }
        val a = PineScripts.get(id)!!.auto
        assertEquals("a zero keeps what was there", 30.0, a.stopPts, 0.0); assertEquals(60.0, a.targetPts, 0.0)
        assertEquals(com.optionslab.ira.PineProtection.ARM_REFUSAL,
            com.optionslab.ira.PineProtection.armRefusal("trade", 0.0, a.targetPts))
        assertTrue(com.optionslab.ira.PineProtection.ARM_REFUSAL.startsWith("Set a stop-loss and a target first"))
        // A narrower stop is kept as typed.
        auto { it.copy(stopPts = 12.0) }
        assertEquals(12.0, PineScripts.get(id)!!.auto.stopPts, 0.0)
        switchOn()
        assertTrue(PineScripts.get(id)!!.auto.on)
    }

    @Test fun anArmedScriptSavedWithoutAStopOrTargetIsProtectedAndToldOnce() {
        val f = java.io.File(context.filesDir, "pine.vault")
        val text = org.json.JSONArray()
            .put(org.json.JSONObject().put("id", 1).put("name", "EMA").put("code", code)
                .put("auto", org.json.JSONObject().put("on", true).put("stopPts", 0.0).put("targetPts", 0.0).put("profitLock", false)
                    .put("profitLockChosen", true).put("buy", "Buy").put("sell", "Sell")))
            .put(org.json.JSONObject().put("id", 2).put("name", "Narrow").put("code", code)
                .put("auto", org.json.JSONObject().put("on", true).put("stopPts", 10.0).put("targetPts", 0.0)))
            .toString()
        PineScripts.wipe()
        com.optionslab.app.security.Vault.writeFile(f, text.toByteArray(Charsets.UTF_8))
        // As after a restart: the scripts are read from the file again.
        PineScripts::class.java.getDeclaredField("loaded").apply { isAccessible = true }.set(null, false)
        val items = PineScripts.loadNow()!!.associateBy { it.id }
        val ema = items.getValue(1).auto
        assertTrue("kept armed", ema.on); assertEquals(30.0, ema.stopPts, 0.0); assertEquals(60.0, ema.targetPts, 0.0); assertTrue(ema.profitLock)
        assertEquals("a nonzero stop is never widened", 10.0, items.getValue(2).auto.stopPts, 0.0)
        assertEquals(60.0, items.getValue(2).auto.targetPts, 0.0)
        runBlocking { PineAuto.load() }
        val lines = PineAuto.log.value.map { it.text }
        assertTrue(lines.toString(), lines.contains("Pine 'EMA': stop-loss 30 and target 60 added, profit lock on — Boss's 06 Oct rule"))
        assertTrue(lines.toString(), lines.contains("Pine 'Narrow': target 60 added, profit lock on — Boss's 06 Oct rule"))
        // Written back: read again, nothing more is said.
        val again = PineScripts.parseNoting(String(com.optionslab.app.security.Vault.readFileSteady(f)!!, Charsets.UTF_8))
        assertTrue(again.second.isEmpty()); assertFalse(again.third)
        runBlocking { PineAuto.load() }
        assertEquals(1, PineAuto.log.value.count { it.text.startsWith("Pine 'EMA':") })
    }

    @Test fun aHoldingSavedBeforeTheProfitLockStartsItsBestAtTheBuyPriceThenTheLtp() {
        auto { it.copy(targetPts = 100.0, profitLock = true) }
        bought()
        val f = java.io.File(context.noBackupFilesDir, "pine_auto.vault")
        val o = org.json.JSONObject(String(com.optionslab.app.security.Vault.readFileSteady(f)!!, Charsets.UTF_8))
        o.getJSONObject("held").getJSONObject(id.toString()).remove("peak")      // as an older app saved it
        PineAuto.wipe()
        com.optionslab.app.security.Vault.writeFile(f, o.toString().toByteArray(Charsets.UTF_8))
        runBlocking { PineAuto.load() }
        assertEquals(300.0, held()!!.peak, 0.0)
        kite.quote("NFO:$ce", 340.0, 339.95, 340.05)
        pass(52_010.0)
        assertEquals("the first look sets the best to max(entry, LTP)", 340.0, held()!!.peak, 0.0)
        assertTrue(side("SELL").isEmpty())
    }

    @Test fun scriptsSavedBeforeTheProfitLockAreMigrated() {
        val jarvisCode = "//@version=5\nstrategy(\"x\")\n// Written by Jarvis from the Hammer pattern: entry after the pattern's candle closes"
        val text = org.json.JSONArray()
            .put(org.json.JSONObject().put("id", 1).put("name", "Jarvis: Hammer NIFTY 5m").put("code", jarvisCode)
                .put("auto", org.json.JSONObject().put("on", true).put("symbol", "NIFTY")))
            .put(org.json.JSONObject().put("id", 2).put("name", "Renamed by Boss").put("code", jarvisCode)
                .put("auto", org.json.JSONObject().put("on", false)))
            .put(org.json.JSONObject().put("id", 3).put("name", "Mine with a stop").put("code", code)
                .put("auto", org.json.JSONObject().put("on", true).put("stopPts", 30.0)))
            .put(org.json.JSONObject().put("id", 4).put("name", "Mine, bare").put("code", code)
                .put("auto", org.json.JSONObject().put("on", false)))
            .put(org.json.JSONObject().put("id", 5).put("name", "Saved off by the old default").put("code", code)
                .put("auto", org.json.JSONObject().put("targetPts", 80.0).put("profitLock", false)))
            .put(org.json.JSONObject().put("id", 6).put("name", "Switched off by Boss").put("code", code)
                .put("auto", org.json.JSONObject().put("profitLock", false).put("profitLockChosen", true)))
            .put(org.json.JSONObject().put("id", 7).put("name", "His own trail").put("code", code)
                .put("auto", org.json.JSONObject().put("trail", org.json.JSONObject().put("be", 4.0)
                    .put("steps", org.json.JSONArray().put(org.json.JSONArray().put(6.0).put(140.0)).put(org.json.JSONArray().put(-1.0).put(70.0))))))
            .put(org.json.JSONObject().put("id", 8).put("name", "No auto at all").put("code", code))
            .toString()
        val items = PineScripts.parse(text).associateBy { it.id }
        assertTrue("Jarvis's, armed now", items.getValue(1).auto.profitLock); assertTrue(items.getValue(1).auto.byJarvis)
        assertTrue("Jarvis's by its own comment", items.getValue(2).auto.profitLock); assertTrue(items.getValue(2).auto.byJarvis)
        assertTrue("the owner's with a stop", items.getValue(3).auto.profitLock); assertFalse(items.getValue(3).auto.byJarvis)
        assertEquals("his stop kept", 30.0, items.getValue(3).auto.stopPts, 0.0); assertEquals(60.0, items.getValue(3).auto.targetPts, 0.0)
        assertTrue("the owner's with no stop or target", items.getValue(4).auto.profitLock)
        assertEquals(30.0, items.getValue(4).auto.stopPts, 0.0); assertEquals(60.0, items.getValue(4).auto.targetPts, 0.0)
        assertTrue("a false the old default wrote is not his choice", items.getValue(5).auto.profitLock)
        assertEquals(80.0, items.getValue(5).auto.targetPts, 0.0)
        // Boss's 06 Oct rule: even a lock he once switched off himself is on now (the field is kept, not counted).
        assertTrue(items.getValue(6).auto.profitLock); assertTrue(items.getValue(6).auto.profitLockChosen)
        assertEquals(com.optionslab.engine.orb.ProfitLock.Trail(), items.getValue(4).auto.trail)
        assertEquals(com.optionslab.engine.orb.ProfitLock.Trail(4.0, listOf(com.optionslab.engine.orb.ProfitLock.Trail.Step(6.0, 95.0),
            com.optionslab.engine.orb.ProfitLock.Trail.Step(0.0, 70.0))), items.getValue(7).auto.trail)
        assertTrue(items.getValue(8).auto.profitLock)
        assertTrue("a new script starts with it on", PineScripts.Auto().profitLock)
        assertEquals(30.0, PineScripts.Auto().stopPts, 0.0); assertEquals(60.0, PineScripts.Auto().targetPts, 0.0)
        // Saved and read back: the trail survives; the lock is saved on whatever was written.
        val mine = PineScripts.Auto(profitLock = false, profitLockChosen = true,
            trail = com.optionslab.engine.orb.ProfitLock.Trail(6.0, listOf(com.optionslab.engine.orb.ProfitLock.Trail.Step(12.0, 60.0))))
        PineScripts.setAuto(id, mine)
        val saved = String(com.optionslab.app.security.Vault.readFileSteady(java.io.File(context.filesDir, "pine.vault"))!!, Charsets.UTF_8)
        assertEquals(mine.copy(profitLock = true), PineScripts.parse(saved).single { it.id == id }.auto)
    }
}
