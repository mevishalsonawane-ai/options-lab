package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.signals.SignalPaperRules
import com.optionslab.ira.AutoChange
import kotlinx.coroutines.runBlocking
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
 * Paper-trading a Telegram chat's calls ([SignalPaperArm], 15 Oct, Boss's choice - not proven, paper only). With the app in
 * Live, logged in to Zerodha, real orders allowed and "AI trades go live" on: a confirmed call of a chat switched on is bought
 * on PAPER only once its premium trades at the entry after the reaction delay (never when it does not), its stop rests in the
 * paper book as a SELL SL-M (the call's, else our -20%), T1 sells it (or half at T1 moves the stop to cost), the expiry day's
 * 15:05 squares it off, the daily cap stops a chat's day, the switch is in the change log with a one-tap undo, the book is
 * restored first after a restart - and not one order reaches Zerodha.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class SignalPaperArmTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val chat = "Calls Pro"
    private val opt = "NSE_FO|SP25000CE"
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")
    private val call = "NIFTY 25000 CE above 120 sl 100 tgt 140 160"

    private fun at(t: LocalTime, s: Long = 0) { Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(s).atZone(IST).toInstant(), IST) }

    /** The option's minutes to [until]: [px] by the minute each starts; NIFTY flat at 25,010. */
    private fun market(until: LocalTime, px: (LocalTime) -> Double) {
        val o = ArrayList<FakeUpstox.Candle>(); val i = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { val c = px(t); o += FakeUpstox.Candle(t, c, c, c, c); i += FakeUpstox.Candle(t, 25_010.0, 25_010.0, 25_010.0, 25_010.0); t = t.plusMinutes(1) }
        upstox.minutes[opt] = o; upstox.minutes[niftyKey] = i
    }
    private val rally: (LocalTime) -> Double = { t -> if (t.isBefore(LocalTime.of(9, 29))) 115.0 else if (t.isBefore(LocalTime.of(9, 35))) 121.0 else 141.0 }

    private fun listContracts(expiry: LocalDate) =
        AutomationSupport.contracts(context, listOf(Upstox.Contract("NIFTY", expiry, 25_000.0, Right.CE, 65, opt, "NIFTY-SP-25000CE")))

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
        SignalTesterHost.setOn(true); SignalTesterHost.allow(chat)
        SignalTesterHost.testHolding = false
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        SignalTesterHost.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    /** [text] from the chat at [t] (09:28:04 by default), read and checked by the Signal tester. */
    private fun message(text: String = call, t: LocalTime = LocalTime.of(9, 28), s: Long = 4) {
        at(t, s); market(t.plusMinutes(1), rally)
        SignalTesterHost.ingest(chat, text, day.atTime(t).plusSeconds(s), SignalTesterHost.PASTED)
        runBlocking { SignalTesterHost.round(day.atTime(t).plusSeconds(s + 10)) }
        assertTrue(SignalTesterHost.view.value.recs.last().checked)
    }
    private fun tick(t: LocalTime, s: Long = 0, px: (LocalTime) -> Double = rally) { at(t, s); market(t, px); runBlocking { SignalPaperArm.tick() } }
    private fun v() = SignalPaperArm.view.value

    @Test fun offByDefaultNothingIsBought() {
        message()
        assertFalse(runBlocking { SignalPaperArm.refresh() }.isOn(chat))
        tick(LocalTime.of(9, 31))
        assertTrue(v().open.isEmpty()); assertTrue(Paper.state.orders.isEmpty())
        assertFalse(SignalPaperArm.wantsPass())
    }

    @Test fun aCallIsBoughtOnPaperWhenItTriggersItsStopRestsAndT1SellsIt() {
        assertTrue(runBlocking { SignalPaperArm.setOn(chat, true) }.contains(SignalPaperRules.BOSS_CHOICE))
        message()
        assertTrue(SignalPaperArm.wantsPass())
        // Within the 30 s reaction delay: nothing.
        tick(LocalTime.of(9, 28), 20)
        assertTrue(v().open.isEmpty())
        // 09:29 trades 121 (at or above 120): bought on paper, its stop (the call's 100) resting as a SELL SL-M.
        tick(LocalTime.of(9, 30), 10)
        val p = v().open.single()
        assertTrue(p.symbol, p.symbol.startsWith("NIFTY") && p.symbol.endsWith("25000CE")); assertEquals(65, p.qty); assertEquals(100.0, p.stop, 1e-9); assertFalse(p.ourStop)
        assertEquals(140.0, p.target)
        val stop = Paper.state.orders.single { it.orderId == p.stopOrderId }
        assertEquals("SL-M", stop.priceType); assertEquals("SELL", stop.action); assertEquals(100.0, stop.triggerPrice!!.toDouble(), 1e-9)
        // A second pass never buys it twice.
        tick(LocalTime.of(9, 31))
        assertEquals(1, v().open.size + v().closed.size)
        // 09:35 trades 141: T1 (140) sells it all; the resting stop is taken out.
        at(LocalTime.of(9, 36)); market(LocalTime.of(9, 36), rally); runBlocking { SignalPaperArm.manageOnly() }
        val done = v().closed.single()
        assertEquals("target", done.why); assertTrue(done.net!! > 0)
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        assertEquals("cancelled", Paper.state.orders.single { it.orderId == p.stopOrderId }.status)
        assertNothingAtZerodha()
    }

    /**
     * 18 Oct, review D8: its paper trades reach the live path. The call's paper buy tells [ArmLive] it entered (the Telegram calls'
     * own key, 1 lot of 65, the call's stop 100) and T1's paper sale tells it the trade closed; the registered book shows it at 0.
     */
    @Test fun aCallsPaperTradeReachesTheLivePath() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        runBlocking { SignalPaperArm.setOn(chat, true) }
        message()
        tick(LocalTime.of(9, 30), 10)
        val p = v().open.single()
        val ref = "telegram:${p.chat}:${p.callId}"
        val key = com.optionslab.engine.orb.LiveMirror.TELEGRAM_CALLS
        assertTrue(heard.toString(), heard.any { it == "entered|$key|$ref|65|100.0" })
        assertEquals(listOf(ArmLive.PaperLeg(ref, key, 65)), ArmLive.legsForTest(com.optionslab.engine.orb.LiveMirror.TELEGRAM))
        // 09:35 trades 141: T1 (140) sells it all on paper; the live path hears it closed.
        at(LocalTime.of(9, 36)); market(LocalTime.of(9, 36), rally); runBlocking { SignalPaperArm.manageOnly() }
        assertEquals("target", v().closed.single().why)
        assertTrue(heard.toString(), heard.any { it == "closed|$ref|target" })
        assertEquals(listOf(ArmLive.PaperLeg(ref, key, 0)), ArmLive.legsForTest(com.optionslab.engine.orb.LiveMirror.TELEGRAM))
        assertNothingAtZerodha()
    }

    @Test fun notTriggeredNoOrder() {
        runBlocking { SignalPaperArm.setOn(chat, true) }
        message()
        val flat: (LocalTime) -> Double = { 115.0 }
        tick(LocalTime.of(9, 35), px = flat)
        assertTrue(v().status[SignalPaperArm.chatId(chat)]!!.contains("waiting for 120"))
        tick(LocalTime.of(9, 43), 10, px = flat)
        assertTrue(v().open.isEmpty() && v().closed.isEmpty())
        assertTrue(v().status[SignalPaperArm.chatId(chat)]!!.contains("not triggered"))
        assertTrue(Paper.state.orders.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun noStopGivenOurStopAndItsFill() {
        runBlocking { SignalPaperArm.setOn(chat, true) }
        message("NIFTY 25000 CE above 120 tgt 140")
        tick(LocalTime.of(9, 30), 10)
        val p = v().open.single()
        assertTrue(p.ourStop); assertEquals(SignalPaperRules.stop(null, p.entry, SignalPaperRules.Settings()).first, p.stop, 1e-9)
        // The premium falls through our stop: the paper book fills the resting stop; the arm books it.
        val crash: (LocalTime) -> Double = { t -> if (t.isBefore(LocalTime.of(9, 32))) rally(t) else 60.0 }
        at(LocalTime.of(9, 34)); market(LocalTime.of(9, 34), crash); runBlocking { Paper.tick() }
        runBlocking { SignalPaperArm.manageOnly() }
        val done = v().closed.single()
        assertEquals("our stop", done.why); assertTrue(done.net!! < 0)
        assertNothingAtZerodha()
    }

    @Test fun withNoStopRestingTheArmPutsItBackAndSellsThroughItItself() {
        // 18 Oct, review: the resting SL-M is gone (refused, not put back, or cancelled). Above the stop the arm puts it back;
        // through it the arm sells at once - never riding to the 15:15 square-off without a stop.
        runBlocking { SignalPaperArm.setOn(chat, true) }
        message()
        tick(LocalTime.of(9, 30), 10)
        val p = v().open.single()
        runBlocking { Paper.cancel(p.stopOrderId!!, "test") }
        assertEquals("cancelled", Paper.state.orders.single { it.orderId == p.stopOrderId }.status)
        // 09:31 trades 121, above the stop (100): a new SL-M at the same stop rests, nothing sold.
        at(LocalTime.of(9, 31)); market(LocalTime.of(9, 31), rally); runBlocking { SignalPaperArm.manageOnly() }
        val back = v().open.single()
        assertTrue(back.stopOrderId != null && back.stopOrderId != p.stopOrderId)
        val again = Paper.state.orders.single { it.orderId == back.stopOrderId }
        assertEquals("SL-M", again.priceType); assertEquals("SELL", again.action); assertEquals(100.0, again.triggerPrice!!.toDouble(), 1e-9)
        assertEquals(65, again.quantity)
        // A second pass with it resting puts no second one beside it.
        at(LocalTime.of(9, 32)); market(LocalTime.of(9, 32), rally); runBlocking { SignalPaperArm.manageOnly() }
        assertEquals(back.stopOrderId, v().open.single().stopOrderId)
        assertEquals(1, Paper.state.orders.count { it.priceType == "SL-M" && it.status != "cancelled" })
        // Gone again, and the premium is through the stop (the paper book never filled a stop): the arm sells it itself.
        runBlocking { Paper.cancel(back.stopOrderId!!, "test") }
        val crash: (LocalTime) -> Double = { t -> if (t.isBefore(LocalTime.of(9, 33))) rally(t) else 90.0 }
        at(LocalTime.of(9, 35)); market(LocalTime.of(9, 35), crash); runBlocking { SignalPaperArm.manageOnly() }
        val done = v().closed.single()
        assertEquals("stop", done.why); assertTrue(done.net!! < 0)
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    @Test fun halfAtT1MovesTheStopToCostAndTheLastTargetSellsTheRest() {
        runBlocking { SignalPaperArm.setOn(chat, true); SignalPaperArm.setExit(SignalPaperRules.Exit.HALF_COST) }
        message()
        tick(LocalTime.of(9, 30), 10)
        val p = v().open.single()
        at(LocalTime.of(9, 36)); market(LocalTime.of(9, 36), rally); runBlocking { SignalPaperArm.manageOnly() }
        // One lot cannot be halved: the stop moves to cost and the lot rides on.
        val half = v().open.single()
        assertTrue(half.halfDone); assertEquals(0, half.soldQty); assertEquals(p.entry, half.stop, 1e-9)
        assertEquals(p.entry, Paper.state.orders.single { it.orderId == p.stopOrderId }.triggerPrice!!.toDouble(), 1e-9)
        val up: (LocalTime) -> Double = { t -> if (t.isBefore(LocalTime.of(9, 40))) rally(t) else 161.0 }
        at(LocalTime.of(9, 41)); market(LocalTime.of(9, 41), up); runBlocking { SignalPaperArm.manageOnly() }
        assertEquals("last target", v().closed.single().why)
    }

    @Test fun theExpiryDaySquaresOffAt1505AndTheDailyCapStopsAChat() {
        listContracts(day)
        runBlocking { SignalPaperArm.setOn(chat, true); SignalPaperArm.setLimits(1, 3_000.0) }
        message("NIFTY 25000 CE above 120 sl 100 tgt 400")
        tick(LocalTime.of(9, 30), 10)
        assertTrue(v().open.single().expiryDay)
        // A second call the same day: the cap of 1 a day.
        message("NIFTY 25000 CE above 125 sl 100 tgt 400", LocalTime.of(10, 0))
        tick(LocalTime.of(10, 1))
        assertEquals("1 paper trades today already", v().status[SignalPaperArm.chatId(chat)]!!.substringAfter(": "))
        tick(LocalTime.of(15, 5))
        assertEquals("square-off", v().closed.single().why)
        assertNothingAtZerodha()
    }

    @Test fun aStatusUpdateNeverTradesOrChangesTheTrade() {
        runBlocking { SignalPaperArm.setOn(chat, true) }
        message()
        tick(LocalTime.of(9, 30), 10)
        val p = v().open.single()
        // The channel re-posts the call with its entry ticked, then "T1 done": notes on the call, never a second trade.
        SignalTesterHost.ingest(chat, "NIFTY 25000 CE above 120✅ sl 100 tgt 140 160", day.atTime(9, 32), SignalTesterHost.PASTED)
        SignalTesterHost.ingest(chat, "T1 done ✅", day.atTime(9, 33), SignalTesterHost.PASTED)
        assertEquals(1, SignalTesterHost.view.value.recs.count { it.kind == SignalTesterHost.CALL })
        assertEquals(2, SignalTesterHost.view.value.recs.count { it.kind == SignalTesterHost.FOLLOW })
        tick(LocalTime.of(9, 34))
        assertEquals(listOf(p), v().open)
        assertTrue(v().closed.isEmpty())
        // The month check counts from the day it was switched on.
        assertEquals(day, v().sinceOf(chat))
        assertTrue(v().monthCheck(chat, null).first().detail == "0 paper trades")
        assertNothingAtZerodha()
    }

    @Test fun theBookIsRestoredFirstAfterARestart() {
        runBlocking { SignalPaperArm.setOn(chat, true) }
        message()
        tick(LocalTime.of(9, 30), 10)
        assertTrue(File(context.noBackupFilesDir, "signal_paper.vault").exists())
        SignalPaperArm.wipe()
        assertFalse(SignalPaperArm.holding())
        assertTrue(runBlocking { PositionsFirst.restore() })
        assertTrue(SignalPaperArm.holding())
        assertTrue(SignalPaperArm.view.value.isOn(chat))
    }

    @Test fun theChangeLogsUndoSwitchesTheChatOff() {
        runBlocking { SignalPaperArm.setOn(chat, true) }
        val on = AutoChange.telegramPaperOn(SignalPaperArm.chatId(chat))
        assertTrue(on.target.startsWith("paperarm:telegram:")); assertFalse(on.target.contains(chat))
        val off = Autopilot.apply(AutoChange.inverse(on, "undone by you"), false)
        assertTrue(off!!.contains("off"))
        assertFalse(SignalPaperArm.view.value.isOn(chat))
        assertTrue(Autopilot.apply(on, false)!!.contains(SignalPaperRules.BOSS_CHOICE))
        assertTrue(SignalPaperArm.view.value.isOn(chat))
    }

    @Test fun thePaperArmHasNoLivePath() {
        val dir = listOf(File("src/main/java/com/optionslab/app/data"), File("app/src/main/java/com/optionslab/app/data")).first { it.exists() }
        for (name in listOf("SignalPaperArm.kt", "SignalTesterHost.kt", "SignalShareActivity.kt", "MessageSource.kt", "TelegramCapture.kt")) {
            val code = File(dir, name).readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }.joinToString("\n")
            for (bad in listOf("Broker.", "Kite.", "KiteOrders", "placeLive", "LiveOrders", "Zerodha", "live = true")) assertFalse("$name: $bad", code.contains(bad))
        }
    }
}
