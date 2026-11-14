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
import com.optionslab.engine.orb.FixedRupee
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
 * 14 Nov 2026 (Boss: "Okay, now just make a change in liquidity, r51, r72. Make it 2k profit and 1k loss fix, dont break any thing";
 * CLAUDE.md's fifth exception): R51 Donchian 20 and R72 Breadth Donchian with the fixed ₹2,000 target / ₹1,000 stop ([FixedRupee],
 * [FixedRupeeExits]) - on paper (the ₹1k stop where it is tighter, the ₹2k target, the arm's own stop when that is tighter, switch off =
 * the old exits, a trade keeping the exits it entered with across a restart), and live (the Zerodha stop rested at the live trade's
 * own ₹1k level, the ₹2k target sold with exactly one exit, Boss's hands-off respected), and the switch with its timeline entry.
 * NIFTY's lot is 65 here: at 200 the ₹1k stop is 15.38 under the fill (the arm's -15% is 30 under), the ₹2k target 30.77 over.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class FixedRupeePassedTest : RobolectricTest() {
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
        FixedRupeeExits.resetForTest(on = false)
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
        FixedRupeeExits.resetForTest(on = on)
        market(LocalTime.of(9, 20)) { px }
        tick(LocalTime.of(9, 20))
        return open()
    }

    // ---- paper ------------------------------------------------------------------------------------------------------------------

    @Test fun onPaperTheOneThousandStopSellsWhereItIsTighterAndSaysSo() {
        val p = bought(200.0)
        assertTrue("entered with the rule", p.fx)
        val fs = FixedRupee.stopLevel(p.entry, p.qty)!!
        assertTrue("tighter than the -15%: $fs", fs > p.entry * 0.85)
        // The position popup: the ₹1k stop and the ₹2k target, labelled.
        val ex = PassedArmsHost.exitsOf(p, null)
        assertEquals(fs, ex.stop!!, 1e-9)
        assertEquals(FixedRupee.targetLevel(p.entry, p.qty)!!, ex.target!!, 1e-9)
        assertTrue(ex.note!!, ex.note!!.contains("failed gates, Boss's exception"))
        // Its line on the row says it too.
        assertTrue(PassedArmsHost.tradeLine(p, emptyMap()).contains(FixedRupee.LABEL))
        // 09:24 trades a rupee under the level: sold by the ₹1k stop, booked at the walk's price (the minute's open), never better.
        market(LocalTime.of(9, 25)) { t -> if (t.isBefore(LocalTime.of(9, 24))) 200.0 else fs - 1.0 }
        manage(LocalTime.of(9, 25))
        val sold = closed()
        assertEquals(FixedRupee.WHY_STOP, sold.why)
        assertTrue("${sold.exit} at or under $fs", sold.exit!! <= fs + 1e-9)
        assertTrue("about -₹1,000 before charges: ${(sold.exit!! - p.entry) * p.qty}", (sold.exit!! - p.entry) * p.qty in -1_100.0..-900.0)
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        assertTrue("paper only: nothing at Zerodha", kite.placed.isEmpty())
    }

    @Test fun onPaperTheTwoThousandTargetSellsFirstAndIsBookedAtItsLevelAtMost() {
        val p = bought(200.0)
        val tg = FixedRupee.targetLevel(p.entry, p.qty)!!
        assertTrue("before R51's own +30%: $tg", tg < p.entry * 1.3)
        market(LocalTime.of(9, 31)) { t -> if (t.isBefore(LocalTime.of(9, 30))) 205.0 else tg + 5.0 }
        manage(LocalTime.of(9, 31))
        val sold = closed()
        assertEquals(FixedRupee.WHY_TARGET, sold.why)
        assertTrue("${sold.exit} at the target at most", sold.exit!! <= tg + 1e-9)
        assertTrue("about +₹2,000 before charges", (sold.exit!! - p.entry) * p.qty >= FixedRupee.TARGET_RS - 1e-6)
    }

    @Test fun theArmsOwnStopWinsWhereItIsTighter() {
        // At 100 the -15% (85.0x, Rs 975) is tighter than the ₹1k level (84.6x).
        val p = bought(100.0)
        assertTrue(p.fx)
        assertEquals(p.entry * 0.85, PassedArmsHost.exitsOf(p, null).stop!!, 1e-9)
        market(LocalTime.of(9, 25)) { t -> if (t.isBefore(LocalTime.of(9, 24))) 100.0 else 84.0 }
        manage(LocalTime.of(9, 25))
        assertEquals("the arm's own stop", "stop", closed().why)
    }

    @Test fun withTheSwitchOffTheArmKeepsItsOldExits() {
        val p = bought(200.0, on = false)
        assertFalse(p.fx)
        assertEquals(p.entry * 0.85, PassedArmsHost.exitsOf(p, null).stop!!, 1e-9)
        assertEquals(p.entry * 1.3, PassedArmsHost.exitsOf(p, null).target!!, 1e-9)
        // A fall to where the ₹1k stop would have been, and a rise past where its target would have been: held.
        market(LocalTime.of(9, 26)) { t -> when { t.isBefore(LocalTime.of(9, 23)) -> 200.0; t.isBefore(LocalTime.of(9, 25)) -> 184.0; else -> 232.0 } }
        manage(LocalTime.of(9, 26))
        assertTrue(PassedArmsHost.view.value.open.any { it.shadow == null })
    }

    @Test fun anOpenTradeKeepsTheRuleItEnteredWithAcrossARestartAndASwitchOff() {
        val p = bought(200.0)
        // Boss switches it off (with the PIN): new trades only; this trade keeps its ₹2k / ₹1k.
        assertTrue(FixedRupeeExits.set(false, pinConfirmed = true, log = false).startsWith("Off"))
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        val back = runBlocking { PassedArmsHost.refresh() }.open.single { it.shadow == null }
        assertTrue("persisted", back.fx)
        val fs = FixedRupee.stopLevel(p.entry, p.qty)!!
        market(LocalTime.of(9, 25)) { t -> if (t.isBefore(LocalTime.of(9, 24))) 200.0 else fs - 0.5 }
        manage(LocalTime.of(9, 25))
        assertEquals(FixedRupee.WHY_STOP, closed().why)
    }

    @Test fun anR72TradeUsesItToo() {
        FixedRupeeExits.resetForTest(on = true)
        val c = Paper.ticketContract("NIFTY", day.plusDays(7), 25_050.0, Right.CE)!!
        Paper.rememberContract(c)
        // A would-be R72 trade (nothing held on the account) entered with the rule: its own exits close it.
        runBlocking { PassedArmsHost.addOpenForTest(PassedArmsHost.Pos(BreadthDonchianArm.SOURCE, "NIFTY", c.symbol, "CE", 65, 200.0, day.atTime(9, 20, 5), null,
            shadow = PassedArmsHost.SHADOW_OFF, fx = true)) }
        market(LocalTime.of(9, 25)) { t -> if (t.isBefore(LocalTime.of(9, 24))) 200.0 else 184.0 }
        manage(LocalTime.of(9, 25))
        val done = PassedArmsHost.view.value.closed.single { it.arm == BreadthDonchianArm.SOURCE }
        assertEquals(FixedRupee.WHY_STOP, done.why)
    }

    @Test fun otherArmsNeverGetIt() {
        FixedRupeeExits.resetForTest(on = true)
        assertTrue(FixedRupeeExits.forEntry(DonchianArm.SOURCE)); assertTrue(FixedRupeeExits.forEntry(BreadthDonchianArm.SOURCE))
        assertTrue(FixedRupeeExits.forEntry("liquidity5"))
        for (other in listOf(OovArm.SOURCE, com.optionslab.engine.orb.Solo2.SOURCE, "orb", "s5_trail", "mcx_eve", "ntfy_alerts")) assertFalse(other, FixedRupeeExits.forEntry(other))
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

    @Test fun liveTheStopRestsAtTheLiveTradesOwnLevelAndTheTargetSendsExactlyOneExit() {
        goLive()
        bought(200.0)
        val (buy, stop) = kite.placed
        assertEquals("BUY", buy.form["transaction_type"])
        val live = ArmLive.view.value.open.single()
        assertTrue("the live trade carries the rule", live.fixed)
        val fs = FixedRupee.stopLevel(live.entry, live.qty, live.tick)!!
        assertEquals("the resting SL at the live trade's own ₹1k level", "%.2f".format(java.util.Locale.ENGLISH, fs), stop.form["trigger_price"])
        assertEquals("SL", stop.form["order_type"])
        assertEquals("%.2f".format(java.util.Locale.ENGLISH, com.optionslab.engine.orb.LiveMirror.stopLimit(fs, live.tick, 1)), stop.form["price"])
        assertTrue(ArmLive.armLine(DonchianArm.SOURCE).contains(FixedRupee.LABEL))
        // Zerodha's price reaches the live ₹2k target (the paper option has not moved): one MARKET sell, the stop out first.
        val tg = FixedRupee.targetLevel(live.entry, live.qty, live.tick)!!
        kite.quote("NFO:$sym", tg + 1.0, tg + 0.95, tg + 1.05)
        runBlocking { ArmLive.watch() }
        assertEquals("CANCELLED", kite.order(live.stopOrderId!!).status)
        assertEquals(1, marketSells.size)
        assertEquals(FixedRupee.WHY_TARGET, ArmLive.view.value.closed.single().why)
        assertEquals("flat at Zerodha", 0, kite.positions["NFO:$sym:MIS"]?.first ?: 0)
        // Later the paper trade's own exit: nothing more is sent (the live trade is closed already).
        market(LocalTime.of(9, 25)) { t -> if (t.isBefore(LocalTime.of(9, 24))) 200.0 else 150.0 }
        manage(LocalTime.of(9, 25))
        runBlocking { ArmLive.watch() }
        assertEquals("never two exits for one position", 1, marketSells.size)
    }

    @Test fun liveTheTargetWaitsWhileBossIsExitingByHand() {
        goLive()
        bought(200.0)
        val live = ArmLive.view.value.open.single()
        val tg = FixedRupee.targetLevel(live.entry, live.qty, live.tick)!!
        kite.quote("NFO:$sym", tg + 1.0, tg + 0.95, tg + 1.05)
        ArmLive.handsOff("NFO", sym)
        runBlocking { ArmLive.watch() }
        assertTrue("hands off: nothing sold", marketSells.isEmpty())
        assertNotNull(ArmLive.view.value.open.singleOrNull())
        ArmLive.handsBack("NFO", sym)
        runBlocking { ArmLive.watch() }
        assertEquals(1, marketSells.size)
    }

    @Test fun liveWithTheSwitchOffTheStopIsTheArmsOwnAsBefore() {
        goLive()
        bought(200.0, on = false)
        val (_, stop) = kite.placed
        val live = ArmLive.view.value.open.single()
        assertFalse(live.fixed)
        val own = stop.form["trigger_price"]!!.toDouble()
        assertTrue("the -15% scaled to the live fill: $own", own < FixedRupee.stopLevel(live.entry, live.qty, live.tick)!!)
        val tg = FixedRupee.targetLevel(live.entry, live.qty, live.tick)!!
        kite.quote("NFO:$sym", tg + 1.0, tg + 0.95, tg + 1.05)
        runBlocking { ArmLive.watch() }
        assertTrue("no ₹2k target", marketSells.isEmpty())
    }

    // ---- the switch and the timeline ------------------------------------------------------------------------------------------

    @Test fun offTakesThePinIsSavedAndLoggedAndItsUndoTakesThePinToo() {
        FixedRupeeExits.resetForTest(on = true)
        val refused = FixedRupeeExits.set(false)
        assertTrue(refused, refused.contains("PIN") && refused.contains("Nothing changed"))
        assertTrue(FixedRupeeExits.isOn())
        assertTrue(FixedRupeeExits.set(false, pinConfirmed = true).startsWith("Off"))
        assertFalse(FixedRupeeExits.isOn())
        assertFalse(SecurePrefs.getBoolean(FixedRupeeExits.K_ON, true))
        assertTrue(FixedRupeeExits.line(), FixedRupeeExits.line().contains("off"))
        // A restart reads it back off; a fresh phone reads it on (the default).
        FixedRupeeExits.resetForTest(on = true, loaded = false)
        FixedRupeeExits.load()
        assertFalse(FixedRupeeExits.isOn()); assertTrue(FixedRupeeExits.view.value.loaded)
        assertTrue("on again: at once", FixedRupeeExits.set(true).startsWith("On"))
        assertTrue(SecurePrefs.getBoolean(FixedRupeeExits.K_ON, false))
        if (!Autopilot.enabled()) return
        // Both flips are in the change timeline as Boss's; the newest one's Undo (back off) takes the PIN.
        val end = System.currentTimeMillis() + 5_000
        fun flips() = Autopilot.book.value.entries.filter { it.change.target == AutoChange.FIXED_RUPEE_TARGET }
        while (flips().size < 2 && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(listOf(AutoChange.SWITCHED_OFF, AutoChange.ON), flips().map { it.change.to })
        val newest = flips().last()
        assertTrue(Autopilot.undoNeedsPin(newest.id))
        assertFalse("never without the PIN", Autopilot.undo(newest.id).startsWith("Undone"))
        assertTrue(FixedRupeeExits.isOn())
        assertTrue(Autopilot.undo(newest.id, pinConfirmed = true).startsWith("Undone"))
        assertFalse(FixedRupeeExits.isOn())
    }

    @Test fun theUpdatesSwitchOnIsLoggedOnceAsBosss() {
        if (!Autopilot.enabled()) return
        SecurePrefs.put(FixedRupeeExits.K_ON, true)
        FixedRupeeExits.resetForTest(on = true, loaded = false)
        FixedRupeeExits.load()
        FixedRupeeExits.resetForTest(on = true, loaded = false)
        FixedRupeeExits.load()
        val end = System.currentTimeMillis() + 5_000
        fun ons() = Autopilot.book.value.entries.filter { it.change.target == AutoChange.FIXED_RUPEE_TARGET }
        while (ons().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        Thread.sleep(200)
        assertEquals("once", 1, ons().size)
        assertEquals(AutoChange.ON, ons().single().change.to)
        assertTrue(ons().single().change.reason.contains("Make it 2k profit and 1k loss fix"))
        assertNull(AutoChange.lastUndoable(Autopilot.book.value))
    }
    /**
     * Review (CLAUDE.md: nothing on the order or tick path touches the settings vault). What this measures, exactly: with the rule on and a
     * fixed live trade already open (the entry itself is not counted: its paper book and settings reads were there before the rule), the
     * rule's own switch reads ([FixedRupeeExits.forEntry], [FixedRupeeExits.isOn]) and a price tick on the stream's thread read nothing.
     */
    @Test fun withTheRuleOnItsSwitchReadsAndAStreamTickReadNothingFromTheSettingsVault() {
        goLive()
        bought(200.0)
        assertTrue(ArmLive.view.value.open.single().fixed)
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        try {
            repeat(50) { FixedRupeeExits.forEntry(DonchianArm.SOURCE); FixedRupeeExits.isOn() }
            KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(26_000_001, 230.0, bid = 229.95, ask = 230.05,
                exchangeTime = System.currentTimeMillis() / 1000, bidQty = 750, askQty = 750)))
        } finally { SecurePrefs.countedThread = null }
        assertEquals("settings reads on the tick path with the rule on", 0L, SecurePrefs.readsForTest.get())
        KiteStream.stop()
    }

    /** Review: a live trade's stop that Zerodha cancelled: the app sells at the ₹1k level itself, booked as the ₹1k stop. */
    @Test fun liveAStopGoneAtZerodhaIsSoldByTheAppAtTheLevelAsTheOneThousandStop() {
        goLive()
        bought(200.0)
        val live = ArmLive.view.value.open.single()
        val fs = FixedRupee.stopLevel(live.entry, live.qty, live.tick)!!
        kite.reject(live.stopOrderId!!, message = "cancelled", status = "CANCELLED")
        kite.quote("NFO:$sym", fs - 0.5, fs - 0.55, fs - 0.45)
        runBlocking { ArmLive.watch() }
        runBlocking { ArmLive.watch() }
        assertEquals(1, marketSells.size)
        assertEquals(FixedRupee.WHY_STOP, ArmLive.view.value.closed.single().why)
    }

    /**
     * Review: a live book damaged and rebuilt from Zerodha keeps the paper trade's ₹2k / ₹1k (its flag read from the paper book); its
     * safety stop is lower than the ₹1k level, so the app sells at that level itself, booked as the ₹1k stop.
     */
    @Test fun aRebuiltLiveTradeKeepsTheRuleAndTheAppSellsAtTheLevelOverItsSafetyStop() {
        goLive()
        bought(200.0)
        val live = ArmLive.view.value.open.single()
        val fs = FixedRupee.stopLevel(live.entry, live.qty, live.tick)!!
        // Boss switches the rule off meanwhile (new trades only): the rebuilt trade still keeps what its paper trade entered with.
        assertTrue(FixedRupeeExits.set(false, pinConfirmed = true, log = false).startsWith("Off"))
        // Its stop gone at Zerodha and the book damaged: rebuilt from Zerodha's position with a safety stop (-30%).
        kite.reject(live.stopOrderId!!, message = "cancelled", status = "CANCELLED")
        val f = File(context.noBackupFilesDir, "arm_live.vault")
        val bytes = f.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5A).toByte(); f.writeBytes(bytes)
        ArmLive.forgetMemoryForTest()
        kite.quote("NFO:$sym", fs - 0.5, fs - 0.55, fs - 0.45)
        runBlocking { ArmLive.watch() }
        runBlocking { ArmLive.watch() }
        val all = ArmLive.view.value.closed + ArmLive.view.value.open
        val rebuilt = all.single { it.ref.startsWith("recovered:") }
        assertTrue("the paper trade's flag", rebuilt.fixed)
        assertEquals(1, marketSells.size)
        assertEquals(FixedRupee.WHY_STOP, rebuilt.why)
    }
    /** Re-review: a rebuilt trade whose own paper trade (the same contract) entered WITHOUT the rule stays without it. */
    @Test fun aRebuiltLiveTradeWhosePaperTradeHadNoRuleStaysWithout() {
        goLive()
        bought(200.0, on = false)
        val live = ArmLive.view.value.open.single()
        assertFalse(live.fixed)
        FixedRupeeExits.resetForTest(on = true)
        assertFalse("its paper trade, matched by contract", ArmLive.rebuiltFixed(com.optionslab.engine.orb.LiveMirror.spec(DonchianArm.SOURCE)!!, sym))
        // No confident match (a contract no paper trade holds): the side that only lowers risk.
        assertTrue(ArmLive.rebuiltFixed(com.optionslab.engine.orb.LiveMirror.spec(DonchianArm.SOURCE)!!, "NIFTY26OCT99999CE"))
        assertFalse("never another arm", ArmLive.rebuiltFixed(com.optionslab.engine.orb.LiveMirror.spec(OovArm.SOURCE)!!, sym))
    }

    /** Re-review: a stop at the ₹1k level is booked "₹1k stop" whatever its kind (a rebuilt trade's safety stop), run through or not. */
    @Test fun aStopAtTheLevelIsTheOneThousandStopWhateverItsKind() {
        val t0 = day.atTime(10, 0)
        val fs = FixedRupee.stopLevel(200.0, 65, 0.05)!!
        val p = ArmLive.Pos(DonchianArm.SOURCE, "recovered:1", sym, "NFO", "MIS", 1, 65, 65, 65, 0.05, 1, 200.0, t0, 200.0, 65, null, "E1",
            stopOrderId = "S1", stopTrigger = fs, stopKind = ArmLive.SAFETY, fixed = true)
        for (w in listOf("stop", "stop_run_through")) assertEquals(w, FixedRupee.WHY_STOP, ArmLive.sellWhy(w, p))
        assertEquals(FixedRupee.WHY_STOP, ArmLive.stopWhyOf(p))
        assertEquals("safety_stop", ArmLive.sellWhy("stop", p.copy(stopTrigger = 140.0)))
        assertEquals("disaster_stop", ArmLive.sellWhy("stop", p.copy(stopKind = ArmLive.DISASTER)))
        assertEquals("stop_run_through", ArmLive.sellWhy("stop_run_through", p.copy(stopKind = ArmLive.OWN, fixed = false)))
        assertEquals(FixedRupee.WHY_TARGET, ArmLive.sellWhy(FixedRupee.WHY_TARGET, p))
        assertEquals("exit", ArmLive.sellWhy(null, p))
    }
}
