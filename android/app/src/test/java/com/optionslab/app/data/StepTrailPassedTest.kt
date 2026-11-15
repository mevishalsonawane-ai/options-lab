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
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.BreadthDonchianArm
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.StepTrail
import com.optionslab.engine.orb.OovArm
import com.optionslab.ira.AutoChange
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
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * 15 Nov 2026, third version (Boss: "Switch to + picker + breakeven at +10% (V4)"; CLAUDE.md's fifth exception): R51 Donchian 20 and R72
 * Breadth Donchian with the breakeven at +10% ([StepTrail], [StepTrailExits]) - on paper (the arm's own -15% stop at the entry, the
 * breakeven once the option is 10% up, switch off = the old exits, a trade keeping its rule and its breakeven across a restart), and live
 * (the Zerodha stop rested at the arm's own level and MODIFIED up to the breakeven, never down, its fill the breakeven lock's exit with no
 * second sale, Boss's hands-off respected), and the switch with its timeline entry. NIFTY's lot is 65 here: at 200 the arm's -15% is 170;
 * the breakeven is earned at 220.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class StepTrailPassedTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")
    private val ce = "NSE_FO|FX25050CE"
    private val pe = "NSE_FO|FX25150PE"
    private val sym = "NIFTY26OCT25050CE"

    private fun at(t: LocalTime, seconds: Long = 20) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun tick(t: LocalTime, seconds: Long = 20) { at(t, seconds); runBlocking { PassedArmsHost.tick() } }
    private fun manage(t: LocalTime) { at(t); runBlocking { PassedArmsHost.manageOnly() } }

    private fun minutes(key: String, until: LocalTime, px: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { val c = px(t); out += FakeUpstox.Candle(t, c, c + 1, c - 1, c); t = t.plusMinutes(1) }
        upstox.minutes[key] = out
    }

    private fun yesterday(): List<Bar> {
        val d = day.minusDays(1)
        return (0 until 375).map { Bar(d.atTime(9, 15).plusMinutes(it.toLong()), 25_000.0, 25_001.0, 24_999.0, 25_000.0) }
    }

    /** NIFTY: 25,000 until 09:18, then 25,100 (R51's 09:20 break up); the 25,050 CE at [call] by minute. */
    private fun market(until: LocalTime, call: (LocalTime) -> Double) {
        minutes(niftyKey, until) { t -> if (t.isBefore(LocalTime.of(9, 19))) 25_000.0 else 25_100.0 }
        minutes(ce, until, call)
        minutes(pe, until) { 90.0 }
    }

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
        day = AutomationSupport.tradingDayAt(9, 20).toLocalDate()
        at(LocalTime.of(9, 0))
        val exp = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("NIFTY", exp, 25_050.0, Right.CE, 65, ce, "NIFTY-FX-25050CE"),
            Upstox.Contract("NIFTY", exp, 25_150.0, Right.PE, 65, pe, "NIFTY-FX-25150PE"),
            Upstox.Contract("NIFTY", exp.plusDays(7), 25_000.0, Right.CE, 65, "NSE_FO|FXNEXT", "NIFTY-FX-NEXT")))
        PassedArmsHost.testHistory = { u -> if (u == "NIFTY") yesterday() else emptyList() }
        PassedArmsHost.testDaily = { emptyList() }
        runBlocking { PassedArmsHost.prefetchNow(day) }
        OrphanWatch.wipe()
        kite.requests.clear()
        for (s in PassedArmsHost.SOURCES) runBlocking { PassedArmsHost.setArmed(s, s == DonchianArm.SOURCE, log = false) }
    }

    @After fun down() {
        com.optionslab.ira.ArmExits.clear()
        StepTrailExits.resetForTest(on = false)
        Market.testClock = null
        PassedArmsHost.testNoAutoOn = true
        if (Autopilot.enabled()) Autopilot.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun open() = PassedArmsHost.view.value.open.single { it.shadow == null }
    private fun closed() = PassedArmsHost.view.value.closed.single { it.shadow == null }

    /** R51's 09:20 break bought at about [px] with the switch [on]. */
    private fun bought(px: Double, on: Boolean = true): PassedArmsHost.Pos {
        StepTrailExits.resetForTest(on = on)
        market(LocalTime.of(9, 20)) { px }
        tick(LocalTime.of(9, 20))
        return open()
    }

    // ---- paper ------------------------------------------------------------------------------------------------------------------

    private fun be(entry: Double, tick: Double = StepTrail.TICK): Double = StepTrail.breakevenLevel(entry, 65, tick)!!

    private fun popupStop(p: PassedArmsHost.Pos): Double? = com.optionslab.ira.ArmExits.of(false, p.symbol).single().stop

    @Test fun onPaperItKeepsTheArmsOwnStopAndSaysSo() {
        val p = bought(200.0)
        assertTrue("entered with the rule", p.fx)
        assertEquals("its lot kept with it", 65, p.lot)
        assertNull("no rule stop at the entry", StepTrail.stopLevel(p.entry, 65))
        // The position popup: R51's own -15% and +30% target, labelled with where the breakeven starts.
        val ex = PassedArmsHost.exitsOf(p, null)
        assertEquals(p.entry * 0.85, ex.stop!!, 1e-9)
        assertEquals("the arm's own target stays", p.entry * 1.3, ex.target!!, 1e-9)
        assertTrue(ex.note!!, ex.note!!.contains("failed gates, Boss's exception") && ex.note!!.contains("breakeven"))
        // Its line on the row says it too.
        assertTrue(PassedArmsHost.tradeLine(p, emptyMap()).contains(StepTrail.LABEL))
        // 09:24 trades under the -15%: sold by its own stop.
        market(LocalTime.of(9, 25)) { t -> if (t.isBefore(LocalTime.of(9, 24))) 200.0 else p.entry * 0.85 - 0.5 }
        manage(LocalTime.of(9, 25))
        val sold = closed()
        assertEquals("stop", sold.why)
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        assertTrue("paper only: nothing at Zerodha", kite.placed.isEmpty())
    }

    @Test fun onPaperTheStopMovesToBreakevenAtTenPercentAndSellsAsTheBreakevenLock() {
        val p = bought(200.0)
        val e = p.entry
        val b = be(e)
        assertTrue("over the fill: $b", b > e)
        assertEquals("the fill plus the charges", e + StepTrail.chargesPerUnit(e, 65), b, 0.05 + 1e-9)
        // 09:22 trades at +7% (not enough); 09:23 at +10.5% (the breakeven from 09:24); from 09:24 at +12.5% (no further step).
        val up = { t: LocalTime -> when { t.isBefore(LocalTime.of(9, 22)) -> 200.0; t.isBefore(LocalTime.of(9, 23)) -> e + 14.0
            t.isBefore(LocalTime.of(9, 24)) -> e + 21.0; else -> e + 25.0 } }
        market(LocalTime.of(9, 23), up)
        manage(LocalTime.of(9, 23))
        assertEquals("+7%: still its own stop", e * 0.85, popupStop(open())!!, 1e-9)
        market(LocalTime.of(9, 24), up)
        manage(LocalTime.of(9, 24))
        assertEquals("the stop at breakeven", b, popupStop(open())!!, 1e-9)
        market(LocalTime.of(9, 27), up)
        manage(LocalTime.of(9, 27))
        assertEquals("still the breakeven: one step only", b, popupStop(open())!!, 1e-9)
        assertTrue("still under R51's own +30%", e + 26.0 < e * 1.3)
        // 09:27 opens 3 under the fill (through the breakeven): sold as the breakeven lock at the minute's open.
        market(LocalTime.of(9, 28)) { t -> if (t.isBefore(LocalTime.of(9, 27))) up(t) else e - 3.0 }
        manage(LocalTime.of(9, 28))
        val sold = closed()
        assertEquals(StepTrail.WHY_TRAIL, sold.why)
        assertTrue("${sold.exit} at or under $b", sold.exit!! <= b + 1e-9)
    }

    @Test fun withTheSwitchOffTheArmKeepsItsOldExits() {
        val p = bought(200.0, on = false)
        assertFalse(p.fx)
        assertEquals(p.entry * 0.85, PassedArmsHost.exitsOf(p, null).stop!!, 1e-9)
        assertEquals(p.entry * 1.3, PassedArmsHost.exitsOf(p, null).target!!, 1e-9)
        // A fall to above the -15%, a rise past +10% and a give-back under where the breakeven would have been: held.
        market(LocalTime.of(9, 27)) { t -> when { t.isBefore(LocalTime.of(9, 22)) -> 200.0; t.isBefore(LocalTime.of(9, 23)) -> 191.0
            t.isBefore(LocalTime.of(9, 25)) -> 224.0; else -> 199.0 } }
        manage(LocalTime.of(9, 27))
        assertTrue(PassedArmsHost.view.value.open.any { it.shadow == null })
    }

    @Test fun anOpenTradeKeepsTheRuleAndItsBreakevenAcrossARestartAndASwitchOffNeverDown() {
        val p = bought(200.0)
        val e = p.entry
        val b = be(e)
        val up = { t: LocalTime -> if (t.isBefore(LocalTime.of(9, 23))) 200.0 else e + 22.5 }
        market(LocalTime.of(9, 26), up)
        manage(LocalTime.of(9, 26))
        assertEquals(b, popupStop(open())!!, 1e-9)
        // Boss switches it off (with the PIN): new trades only; this trade keeps its rule.
        assertTrue(StepTrailExits.set(false, pinConfirmed = true, log = false).startsWith("Off"))
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        val back = runBlocking { PassedArmsHost.refresh() }.open.single { it.shadow == null }
        assertTrue("persisted", back.fx); assertEquals(65, back.lot)
        // 09:26 opens 3 under the fill: sold at the breakeven's minute open (it never fell back to its own -15%).
        market(LocalTime.of(9, 27)) { t -> if (t.isBefore(LocalTime.of(9, 26))) up(t) else e - 3.0 }
        manage(LocalTime.of(9, 27))
        val sold = closed()
        assertEquals(StepTrail.WHY_TRAIL, sold.why)
        assertTrue("${sold.exit} between ${e * 0.85} and $b", sold.exit!! > e * 0.85 && sold.exit!! <= b + 1e-9)
    }

    @Test fun anR72TradeUsesItToo() {
        StepTrailExits.resetForTest(on = true)
        val c = Paper.ticketContract("NIFTY", day.plusDays(7), 25_050.0, Right.CE)!!
        Paper.rememberContract(c)
        // A would-be R72 trade (nothing held on the account) entered with the rule: its own exits close it.
        runBlocking { PassedArmsHost.addOpenForTest(PassedArmsHost.Pos(BreadthDonchianArm.SOURCE, "NIFTY", c.symbol, "CE", 65, 200.0, day.atTime(9, 20, 5), null,
            shadow = PassedArmsHost.SHADOW_OFF, fx = true)) }
        // +11% at 09:22, then back to 195 (above its own -15%, 170, under the breakeven): sold as the breakeven lock.
        market(LocalTime.of(9, 25)) { t -> when { t.isBefore(LocalTime.of(9, 22)) -> 200.0; t.isBefore(LocalTime.of(9, 24)) -> 222.0; else -> 195.0 } }
        manage(LocalTime.of(9, 25))
        val done = PassedArmsHost.view.value.closed.single { it.arm == BreadthDonchianArm.SOURCE }
        assertEquals(StepTrail.WHY_TRAIL, done.why)
    }

    @Test fun otherArmsNeverGetIt() {
        StepTrailExits.resetForTest(on = true)
        assertTrue(StepTrailExits.forEntry(DonchianArm.SOURCE)); assertTrue(StepTrailExits.forEntry(BreadthDonchianArm.SOURCE))
        assertTrue(StepTrailExits.forEntry("liquidity5"))
        for (other in listOf(OovArm.SOURCE, com.optionslab.engine.orb.Solo2.SOURCE, "orb", "s5_trail", "mcx_eve", "ntfy_alerts")) assertFalse(other, StepTrailExits.forEntry(other))
        // An R52 trade flagged by mistake still keeps its own exits (the arm is checked again where the levels are made).
        val oov = PassedArmsHost.Pos(OovArm.SOURCE, "NIFTY", "X", "CE", 65, 200.0, day.atTime(9, 20), null, fx = true)
        assertEquals(140.0, PassedArmsHost.exitsOf(oov, null).stop!!, 1e-9)
    }

    // ---- live -------------------------------------------------------------------------------------------------------------------

    private fun goLive() {
        ArmLive.testDefaultOff = false
        ArmLive.testSync = true
        runBlocking { ArmLive.setShadow(DonchianArm.SOURCE, false, pinConfirmed = true, log = false) }
        kite.instruments += FakeKite.Ins(26_000_001, sym, "NIFTY", day.plusDays(7), 25_050.0, "CE", 65)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        File(context.filesDir, "kite_instruments.json").delete()
        runBlocking { Broker.instruments() }
        kite.requests.clear()
    }

    private val marketSells: List<FakeKite.Req> get() = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }

    private val modifies: List<FakeKite.Req> get() = kite.requests.filter { it.method == "PUT" && it.path.startsWith("/orders/regular/") }

    private fun quoted(px: Double) { kite.quote("NFO:$sym", px, px - 0.05, px + 0.05); runBlocking { ArmLive.watch() } }

    /** The live trade's best now 10.5% up: its Zerodha stop moved to the breakeven. */
    private fun earnedLive(): ArmLive.Pos {
        val live = ArmLive.view.value.open.single()
        quoted(live.entry * 1.105)
        assertEquals(be(live.entry, live.tick), ArmLive.view.value.open.single().stopTrigger!!, 1e-9)
        return live
    }

    @Test fun liveTheStopRestsAtTheArmsOwnLevel() {
        goLive()
        bought(200.0)
        val (buy, stop) = kite.placed
        assertEquals("BUY", buy.form["transaction_type"])
        val live = ArmLive.view.value.open.single()
        assertTrue("the live trade carries the rule", live.fixed)
        assertNull(StepTrail.stopLevel(live.entry, 65, live.tick))
        val own = stop.form["trigger_price"]!!.toDouble()
        assertEquals("the arm's -15% on the live fill", live.entry * 0.85, own, 0.051)
        assertEquals("SL", stop.form["order_type"])
        assertTrue(ArmLive.armLine(DonchianArm.SOURCE).contains(StepTrail.LABEL))
    }

    @Test fun liveTheBreakevenModifiesTheZerodhaStopUpNeverDownAndItsFillIsTheBreakevenLockWithOneExit() {
        goLive()
        bought(200.0)
        val live = ArmLive.view.value.open.single()
        val e = live.entry
        val id = live.stopOrderId!!
        val b = be(e, live.tick)
        // Zerodha's price (the paper option has not moved): +5.5% and +9.5% - nothing yet.
        quoted(e * 1.055)
        quoted(e * 1.095)
        assertTrue(modifies.isEmpty())
        // +10.5%: the same SL is modified up to breakeven.
        quoted(e * 1.105)
        assertEquals(1, modifies.size)
        assertEquals("/orders/regular/$id", modifies.single().path)
        assertEquals(b, kite.order(id).trigger, 1e-9)
        assertEquals(b, ArmLive.view.value.open.single().stopTrigger!!, 1e-9)
        // A dip above it, then a higher best: never down, and no further step.
        quoted(e * 1.03)
        quoted(e * 1.15)
        assertEquals(1, modifies.size)
        assertEquals(b, kite.order(id).trigger, 1e-9)
        assertEquals("the same order moved: never a second stop", 1, kite.placed.count { it.form["order_type"] == "SL" })
        // Zerodha fills it there: the breakeven lock's exit, no MARKET sell.
        kite.trigger(id, b - 0.5)
        runBlocking { ArmLive.watch() }
        assertEquals(StepTrail.WHY_TRAIL, ArmLive.view.value.closed.single().why)
        assertTrue(marketSells.isEmpty())
        // Later the paper trade's own exit: nothing more is sent (the live trade is closed already).
        market(LocalTime.of(9, 25)) { t -> if (t.isBefore(LocalTime.of(9, 24))) 200.0 else 150.0 }
        manage(LocalTime.of(9, 25))
        runBlocking { ArmLive.watch() }
        assertTrue("never two exits for one position", marketSells.isEmpty())
    }

    @Test fun liveTheBreakevenAndItsBestPriceSurviveARestart() {
        goLive()
        bought(200.0)
        val live = earnedLive()
        val e = live.entry
        val b = be(e, live.tick)
        ArmLive.forgetMemoryForTest()
        quoted(e * 1.03)
        val back = ArmLive.view.value.open.single()
        assertTrue(back.fixed)
        assertEquals("its best price kept", e * 1.105, back.best!!, 0.051)
        assertEquals("never down after a restart", b, back.stopTrigger!!, 1e-9)
        assertEquals(1, modifies.size)
        assertEquals(b, kite.order(live.stopOrderId!!).trigger, 1e-9)
    }

    @Test fun liveTheAppSaleWaitsWhileBossIsExitingByHand() {
        goLive()
        bought(200.0)
        val live = earnedLive()
        val b = be(live.entry, live.tick)
        kite.reject(live.stopOrderId!!, message = "cancelled", status = "CANCELLED")
        kite.quote("NFO:$sym", b - 0.5, b - 0.55, b - 0.45)
        ArmLive.handsOff("NFO", sym)
        runBlocking { ArmLive.watch() }
        runBlocking { ArmLive.watch() }
        assertTrue("hands off: nothing sold", marketSells.isEmpty())
        assertNotNull(ArmLive.view.value.open.singleOrNull())
        ArmLive.handsBack("NFO", sym)
        runBlocking { ArmLive.watch() }
        runBlocking { ArmLive.watch() }
        assertEquals(1, marketSells.size)
        assertEquals(StepTrail.WHY_TRAIL, ArmLive.view.value.closed.single().why)
    }

    @Test fun liveWithTheSwitchOffTheStopIsTheArmsOwnAsBefore() {
        goLive()
        bought(200.0, on = false)
        val (_, stop) = kite.placed
        val live = ArmLive.view.value.open.single()
        assertFalse(live.fixed)
        assertEquals("the -15% scaled to the live fill", live.entry * 0.85, stop.form["trigger_price"]!!.toDouble(), 0.051)
        quoted(live.entry * 1.12)
        assertTrue("no breakeven", modifies.isEmpty())
        assertTrue(marketSells.isEmpty())
    }

    // ---- the switch and the timeline ------------------------------------------------------------------------------------------

    @Test fun offTakesThePinIsSavedAndLoggedAndItsUndoTakesThePinToo() {
        StepTrailExits.resetForTest(on = true)
        val refused = StepTrailExits.set(false)
        assertTrue(refused, refused.contains("PIN") && refused.contains("Nothing changed"))
        assertTrue(StepTrailExits.isOn())
        assertTrue(StepTrailExits.set(false, pinConfirmed = true).startsWith("Off"))
        assertFalse(StepTrailExits.isOn())
        assertFalse(SecurePrefs.getBoolean(StepTrailExits.K_ON, true))
        assertTrue(StepTrailExits.line(), StepTrailExits.line().contains("off"))
        // A restart reads it back off; a fresh phone reads it on (the default).
        StepTrailExits.resetForTest(on = true, loaded = false)
        StepTrailExits.load()
        assertFalse(StepTrailExits.isOn()); assertTrue(StepTrailExits.view.value.loaded)
        assertTrue("on again: at once", StepTrailExits.set(true).startsWith("On"))
        assertTrue(SecurePrefs.getBoolean(StepTrailExits.K_ON, false))
        if (!Autopilot.enabled()) return
        // Both flips are in the change timeline as Boss's; the newest one's Undo (back off) takes the PIN.
        val end = System.currentTimeMillis() + 5_000
        fun flips() = Autopilot.book.value.entries.filter { it.change.target == AutoChange.BE10_TARGET }
        while (flips().size < 2 && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(listOf(AutoChange.SWITCHED_OFF, AutoChange.ON), flips().map { it.change.to })
        val newest = flips().last()
        assertTrue(Autopilot.undoNeedsPin(newest.id))
        assertFalse("never without the PIN", Autopilot.undo(newest.id).startsWith("Undone"))
        assertTrue(StepTrailExits.isOn())
        assertTrue(Autopilot.undo(newest.id, pinConfirmed = true).startsWith("Undone"))
        assertFalse(StepTrailExits.isOn())
    }

    @Test fun theUpdatesSwitchOnIsLoggedOnceAsBosss() {
        if (!Autopilot.enabled()) return
        SecurePrefs.put(StepTrailExits.K_ON, true)
        StepTrailExits.resetForTest(on = true, loaded = false)
        StepTrailExits.load()
        StepTrailExits.resetForTest(on = true, loaded = false)
        StepTrailExits.load()
        val end = System.currentTimeMillis() + 5_000
        fun ons() = Autopilot.book.value.entries.filter { it.change.target == AutoChange.BE10_TARGET }
        while (ons().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        Thread.sleep(200)
        assertEquals("once", 1, ons().size)
        assertEquals(AutoChange.ON, ons().single().change.to)
        assertTrue(ons().single().change.reason.contains("Switch to + picker + breakeven at +10% (V4)"))
        assertNull(AutoChange.lastUndoable(Autopilot.book.value))
    }
    /**
     * Review (CLAUDE.md: nothing on the order or tick path touches the settings vault). What this measures, exactly: with the rule on and a
     * fixed live trade already open (the entry itself is not counted: its paper book and settings reads were there before the rule), the
     * rule's own switch reads ([StepTrailExits.forEntry], [StepTrailExits.isOn]) and a price tick on the stream's thread read nothing.
     */
    @Test fun withTheRuleOnItsSwitchReadsAndAStreamTickReadNothingFromTheSettingsVault() {
        goLive()
        bought(200.0)
        assertTrue(ArmLive.view.value.open.single().fixed)
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        try {
            repeat(50) { StepTrailExits.forEntry(DonchianArm.SOURCE); StepTrailExits.isOn() }
            KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(26_000_001, 230.0, bid = 229.95, ask = 230.05,
                exchangeTime = System.currentTimeMillis() / 1000, bidQty = 750, askQty = 750)))
        } finally { SecurePrefs.countedThread = null }
        assertEquals("settings reads on the tick path with the rule on", 0L, SecurePrefs.readsForTest.get())
        KiteStream.stop()
    }

    /** A live trade's stop that Zerodha cancelled after the breakeven was earned: the app sells at the breakeven itself, as the breakeven lock. */
    @Test fun liveAStopGoneAtZerodhaIsSoldByTheAppAtTheBreakeven() {
        goLive()
        bought(200.0)
        val live = earnedLive()
        val b = be(live.entry, live.tick)
        kite.reject(live.stopOrderId!!, message = "cancelled", status = "CANCELLED")
        kite.quote("NFO:$sym", b - 0.5, b - 0.55, b - 0.45)
        runBlocking { ArmLive.watch() }
        runBlocking { ArmLive.watch() }
        assertEquals(1, marketSells.size)
        assertEquals(StepTrail.WHY_TRAIL, ArmLive.view.value.closed.single().why)
    }

    /** A live book damaged and rebuilt from Zerodha keeps the paper trade's rule (its flag read from the paper book), whatever the switch says now. */
    @Test fun aRebuiltLiveTradeKeepsTheRule() {
        goLive()
        bought(200.0)
        val live = ArmLive.view.value.open.single()
        // Boss switches the rule off meanwhile (new trades only): the rebuilt trade still keeps what its paper trade entered with.
        assertTrue(StepTrailExits.set(false, pinConfirmed = true, log = false).startsWith("Off"))
        // Its stop gone at Zerodha and the book damaged: rebuilt from Zerodha's position with a safety stop.
        kite.reject(live.stopOrderId!!, message = "cancelled", status = "CANCELLED")
        val f = File(context.noBackupFilesDir, "arm_live.vault")
        val bytes = f.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5A).toByte(); f.writeBytes(bytes)
        ArmLive.forgetMemoryForTest()
        runBlocking { ArmLive.watch() }
        runBlocking { ArmLive.watch() }
        val all = ArmLive.view.value.closed + ArmLive.view.value.open
        val rebuilt = all.single { it.ref.startsWith("recovered:") }
        assertTrue("the paper trade's flag", rebuilt.fixed)
    }
    /** Re-review: a rebuilt trade whose own paper trade (the same contract) entered WITHOUT the rule stays without it. */
    @Test fun aRebuiltLiveTradeWhosePaperTradeHadNoRuleStaysWithout() {
        goLive()
        bought(200.0, on = false)
        val live = ArmLive.view.value.open.single()
        assertFalse(live.fixed)
        StepTrailExits.resetForTest(on = true)
        assertFalse("its paper trade, matched by contract", ArmLive.rebuiltFixed(com.optionslab.engine.orb.LiveMirror.spec(DonchianArm.SOURCE)!!, sym))
        // No confident match (a contract no paper trade holds): keeps the rule, the risk-lowering side, whatever the switch says now.
        assertTrue(ArmLive.rebuiltFixed(com.optionslab.engine.orb.LiveMirror.spec(DonchianArm.SOURCE)!!, "NIFTY26OCT99999CE"))
        StepTrailExits.resetForTest(on = false)
        assertTrue("off is for new trades only", ArmLive.rebuiltFixed(com.optionslab.engine.orb.LiveMirror.spec(DonchianArm.SOURCE)!!, "NIFTY26OCT99999CE"))
        StepTrailExits.resetForTest(on = true)
        assertFalse("never another arm", ArmLive.rebuiltFixed(com.optionslab.engine.orb.LiveMirror.spec(OovArm.SOURCE)!!, sym))
    }

    /** A stop at the breakeven is booked "breakeven lock" whatever its kind (a rebuilt trade's safety stop moved there), run through or not. */
    @Test fun aStopAtTheBreakevenIsTheRulesWhateverItsKind() {
        val t0 = day.atTime(10, 0)
        val s1 = StepTrail.trailLevel(200.0, 65, 221.0, 0.05)!!
        assertNull("+8% earns nothing", StepTrail.trailLevel(200.0, 65, 216.0, 0.05))
        val p = ArmLive.Pos(DonchianArm.SOURCE, "recovered:1", sym, "NFO", "MIS", 1, 65, 65, 65, 0.05, 1, 200.0, t0, 200.0, 65, null, "E1",
            stopOrderId = "S1", stopTrigger = s1, stopKind = ArmLive.SAFETY, fixed = true)
        for (w in listOf("stop", "stop_run_through")) assertEquals(w, StepTrail.WHY_TRAIL, ArmLive.sellWhy(w, p))
        assertEquals(StepTrail.WHY_TRAIL, ArmLive.stopWhyOf(p))
        assertEquals("safety_stop", ArmLive.sellWhy("stop", p.copy(stopTrigger = 140.0)))
        assertEquals("disaster_stop", ArmLive.sellWhy("stop", p.copy(stopTrigger = 140.0, stopKind = ArmLive.DISASTER)))
        assertEquals("stop_run_through", ArmLive.sellWhy("stop_run_through", p.copy(stopKind = ArmLive.OWN, fixed = false)))
        assertEquals(StepTrail.WHY_TRAIL, ArmLive.sellWhy("stop", p.copy(stopKind = ArmLive.OWN)))
        assertEquals("a level that is neither", "stop", ArmLive.sellWhy("stop", p.copy(stopTrigger = s1 + 1.0, stopKind = ArmLive.OWN)))
        // The best price only rises, and only on a rule trade.
        assertEquals(221.0, ArmLive.withBest(p, 221.0).best!!, 1e-9)
        assertEquals(221.0, ArmLive.withBest(p.copy(best = 221.0), 210.0).best!!, 1e-9)
        assertNull(ArmLive.withBest(p.copy(fixed = false), 221.0).best)
        assertNull(ArmLive.withBest(p, null).best)
        assertEquals("exit", ArmLive.sellWhy(null, p))
    }
}
