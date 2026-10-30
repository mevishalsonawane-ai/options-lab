package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.ManagerShadow
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.mcx.McxArmRules
import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.mcx.McxMorningRules
import com.optionslab.engine.mcx.McxTrendRules
import com.optionslab.engine.mcx.McxUsSilverRules
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The MCX paper arms (9 Oct, research M3 / M4 / M2) are PAPER ONLY: with the app in Live, logged in to Zerodha, real orders
 * allowed and "AI trades go live" switched on, the NATURALGAS evening breakout buys its 1-ITM call on paper when the
 * 17:00-19:00 range breaks, sells it at its target, and not one order reaches Zerodha. They are off by default, the kill
 * switch and MCX's expiry rule refuse an entry, and the 12-month trend buys its leg on paper only.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class McxPaperArmsTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private val day = LocalDate.of(2026, 10, 8)             // a Thursday: MCX 09:00-23:30
    private val optExpiry = LocalDate.of(2026, 10, 26)
    private val futExpiry = LocalDate.of(2026, 10, 27)
    private val futKey = "MCX_FO|900001"
    private val ceKey = "MCX_FO|900305"                      // NATURALGAS 305 CE: 1-ITM when the future breaks up at 311
    private val crudeMiniKey = "MCX_FO|900500"

    private fun at(t: LocalTime, seconds: Long = 30, on: LocalDate = day) {
        Market.testClock = Clock.fixed(on.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    /**
     * Today's MCX list on the phone, as [McxMarket.contracts] keeps it: the NATURALGAS future and its chain, a CRUDEOILM future;
     * with [silver], SILVERMIC's near future (expiring [silverNear]) and the next one.
     */
    private fun mcxList(today: LocalDate = day, gasOptExpiry: LocalDate = optExpiry, silver: Boolean = false, silverNear: LocalDate = LocalDate.of(2026, 11, 27)) {
        val a = JSONArray()
        fun row(tok: Long, xt: Long, sym: String, name: String, exp: LocalDate, strike: Double, right: String, tick: Double, mult: Int) =
            a.put(JSONArray().put(tok).put(xt).put(sym).put(name).put(exp.toString()).put(strike).put(right).put(tick).put(mult))
        row(1, 900001, "NATURALGAS26OCTFUT", "NATURALGAS", futExpiry, 0.0, "", 0.10, 1250)
        for (k in listOf(295, 300, 305, 310, 315, 320)) {
            row(10L + k, 900000L + k, "NATURALGAS26OCT${k}CE", "NATURALGAS", gasOptExpiry, k.toDouble(), "CE", 0.05, 1250)
            row(20L + k, 910000L + k, "NATURALGAS26OCT${k}PE", "NATURALGAS", gasOptExpiry, k.toDouble(), "PE", 0.05, 1250)
        }
        row(2, 900500, "CRUDEOILM26OCTFUT", "CRUDEOILM", LocalDate.of(2026, 10, 19), 0.0, "", 1.0, 10)
        row(3, 900501, "CRUDEOILM26NOVFUT", "CRUDEOILM", LocalDate.of(2026, 11, 19), 0.0, "", 1.0, 10)
        if (silver) {
            row(4, 900600, "SILVERMIC-NEAR", "SILVERMIC", silverNear, 0.0, "", 1.0, 1)
            row(5, 900601, "SILVERMIC27FEBFUT", "SILVERMIC", LocalDate.of(2027, 2, 26), 0.0, "", 1.0, 1)
        }
        File(context.filesDir, "mcx_contracts.json").writeText(JSONObject().put("day", today.toString()).put("c", a).toString())
        McxMarket.wipe()
    }

    /** The NATURALGAS future's minutes 17:00 to [until] (exclusive): 300-310 to 18:59, inside after, [late] from 19:31. */
    private fun futureMinutes(until: LocalTime, late: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(17, 0)
        while (t.isBefore(until)) {
            val c = when {
                t == LocalTime.of(17, 30) -> 310.0
                t == LocalTime.of(18, 10) -> 300.0
                t.isBefore(LocalTime.of(19, 31)) -> 305.0
                else -> late(t)
            }
            out += FakeUpstox.Candle(t, c, c + 2, c - 2, c)
            t = t.plusMinutes(1)
        }
        upstox.minutes[futKey] = out
    }

    /** The 305 CE's minutes ([key]: another option's) 19:30 to [until] (exclusive), at [px] with a low 1% under it (never near its -15% stop). */
    private fun optionMinutes(until: LocalTime, key: String = ceKey, px: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(19, 30)
        while (t.isBefore(until)) { val p = px(t); out += FakeUpstox.Candle(t, p, p * 1.01, p * 0.99, p); t = t.plusMinutes(1) }
        upstox.minutes[key] = out
    }

    @Before fun up() {
        upstox = FakeUpstox()                                // (clears Market.testClock: set it after)
        kite = FakeKite()
        // Everything that could send an order to Zerodha is on: Live, real orders allowed, logged in, AI trades live.
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        at(LocalTime.of(19, 32))
        mcxList()
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        McxPaperArms.testDaily = null
        McxPaperArms.testStoppedWhy = null
        McxPaperArms.testMcxStoppedWhy = null
        McxPaperArms.testMcxSells = true
        UsCues.testFetch = null
        UsCues.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** [looks]: the trade manager takes a look after each pass (the SHADOW re-runs, [shadowed]). */
    private var looks = false

    private fun tick(t: LocalTime, on: LocalDate = day) { at(t, on = on); runBlocking { McxPaperArms.tick() }; if (looks) ManagerShadow.look() }

    /** Not one order, modify or cancel was sent to Zerodha. */
    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    @Test fun everyArmIsOffByDefaultAndDoesNothing() {
        futureMinutes(LocalTime.of(19, 32)) { 311.0 }
        optionMinutes(LocalTime.of(19, 32)) { 20.0 }
        val v = runBlocking { McxPaperArms.refresh() }
        for (arm in McxPaperArms.ARMS) assertFalse(arm, v.isArmed(arm))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(19, 32))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        assertTrue(McxPaperArms.diagLine().contains("off"))
        assertNothingAtZerodha()
    }

    @Test fun theEveningBreakBuysAndSellsOnPaperOnlyEvenWithEverythingLive() {
        assertTrue(runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }.contains("paper only"))
        // Before 19:00: only waiting.
        futureMinutes(LocalTime.of(18, 0)) { 305.0 }
        tick(LocalTime.of(18, 0))
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        // 19:31 closes at 311, above the 300-310 range: at 19:32 the 1-ITM call (305 CE) is bought on paper, 1 lot.
        futureMinutes(LocalTime.of(19, 32)) { 311.0 }
        optionMinutes(LocalTime.of(19, 32)) { 20.0 }
        tick(LocalTime.of(19, 32))
        val pos = McxPaperArms.view.value.open.single()
        assertEquals(McxEveRules.SOURCE, pos.arm)
        assertEquals("NATURALGAS26OCT26305CE", pos.symbol)
        assertEquals(1250, pos.qty)
        assertEquals(305.0, pos.stopF!!, 1e-9); assertEquals(320.0, pos.targetF!!, 1e-9)
        assertTrue("paid the MCX spread", pos.entry > 20.0)
        assertTrue("held in the paper book", Paper.state.positions.any { it.symbol == pos.symbol && it.quantity == 1250 && it.exchange == "MCX" })
        // Its -15% stop rests in the paper book (fills on a minute's low).
        val stop = Paper.state.orders.single { it.orderId == pos.stopOrderId }
        assertEquals("SL-M", stop.priceType)
        assertNothingAtZerodha()
        // A second pass the same day buys nothing more.
        tick(LocalTime.of(19, 33))
        assertEquals(1, McxPaperArms.view.value.open.size)
        // 19:35 closes at 321: the target (edge + one width, 320) is reached; the 19:36 pass sells on paper.
        futureMinutes(LocalTime.of(19, 36)) { t -> if (t.isBefore(LocalTime.of(19, 35))) 312.0 else 321.0 }
        optionMinutes(LocalTime.of(19, 36)) { t -> if (t.isBefore(LocalTime.of(19, 35))) 21.0 else 26.0 }
        tick(LocalTime.of(19, 36))
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        val done = McxPaperArms.view.value.closed.single()
        assertEquals("target", done.why)
        assertTrue(done.exit!! < 26.0)
        assertEquals(0, Paper.state.positions.filter { it.symbol == pos.symbol }.sumOf { it.quantity })
        assertTrue("the stop came out of the book", Paper.state.orders.single { it.orderId == pos.stopOrderId }.status == "cancelled")
        // Restart: the record is read back from its encrypted file.
        AutomationSupport.reloadFromDisk(McxPaperArms)
        assertEquals(done, runBlocking { McxPaperArms.refresh() }.closed.single())
        assertNothingAtZerodha()
    }

    /**
     * 22 Oct audit: a DOWN break buys the 1-ITM PUT, and its exit is judged with the BREAK's side (-1), not the option's (+1).
     * Before the fix every down-break put was sold "range_mid_stop" on its first finished minute, because the future (below the
     * range middle after a down break) looked like "back at the middle" to a +1 holder (20 and 21 Oct in the diagnostics).
     */
    @Test fun theEveningDownBreakBuysAPutAndHoldsItWhileTheFutureStaysBelowTheMiddle() {
        val peKey = "MCX_FO|910305"                      // NATURALGAS 305 PE: 1-ITM when the future breaks down at 299
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        // 19:31 closes at 299, below the 300-310 range: at 19:32 the 1-ITM put (305 PE) is bought on paper, 1 lot.
        futureMinutes(LocalTime.of(19, 32)) { 299.0 }
        optionMinutes(LocalTime.of(19, 32), peKey) { 20.0 }
        tick(LocalTime.of(19, 32))
        val pos = McxPaperArms.view.value.open.single()
        assertEquals("NATURALGAS26OCT26305PE", pos.symbol)
        assertEquals(305.0, pos.stopF!!, 1e-9); assertEquals(290.0, pos.targetF!!, 1e-9)
        // 19:32 and 19:33 close at 298, still below the middle (305): the put is HELD (the bug sold it here).
        futureMinutes(LocalTime.of(19, 34)) { t -> if (t.isBefore(LocalTime.of(19, 32))) 299.0 else 298.0 }
        optionMinutes(LocalTime.of(19, 34), peKey) { 21.0 }
        tick(LocalTime.of(19, 34))
        assertEquals("held while the future stays below the middle", 1, McxPaperArms.view.value.open.size)
        assertTrue(McxPaperArms.view.value.closed.isEmpty())
        // 19:35 closes at 289: the target (edge - one width, 290) is reached; the 19:36 pass sells on paper.
        futureMinutes(LocalTime.of(19, 36)) { t -> if (t.isBefore(LocalTime.of(19, 35))) 298.0 else 289.0 }
        optionMinutes(LocalTime.of(19, 36), peKey) { t -> if (t.isBefore(LocalTime.of(19, 35))) 21.0 else 28.0 }
        tick(LocalTime.of(19, 36))
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        assertEquals("target", McxPaperArms.view.value.closed.single().why)
        assertNothingAtZerodha()
    }

    /** 22 Oct audit: a down-break put sold "range_mid_stop" only when the future comes BACK UP to the middle. */
    @Test fun theEveningDownBreaksPutIsSoldWhenTheFutureComesBackToTheMiddle() {
        val peKey = "MCX_FO|910305"
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        futureMinutes(LocalTime.of(19, 32)) { 299.0 }
        optionMinutes(LocalTime.of(19, 32), peKey) { 20.0 }
        tick(LocalTime.of(19, 32))
        assertEquals("NATURALGAS26OCT26305PE", McxPaperArms.view.value.open.single().symbol)
        futureMinutes(LocalTime.of(19, 36)) { t -> if (t.isBefore(LocalTime.of(19, 35))) 299.0 else 305.0 }
        optionMinutes(LocalTime.of(19, 36), peKey) { 19.0 }
        tick(LocalTime.of(19, 36))
        assertEquals("range_mid_stop", McxPaperArms.view.value.closed.single().why)
        assertNothingAtZerodha()
    }

    /**
     * 22 Oct audit: when the stop-for-today cannot be read, a held position is NOT sold as "operator_stop" (an entry still
     * treats the unknown as stopped). Before, an unreadable stop sold every held MCX position.
     */
    @Test fun anUnreadableStopForTodayNeverSellsAHeldPositionButRefusesANewEntry() {
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        futureMinutes(LocalTime.of(19, 32)) { 311.0 }
        optionMinutes(LocalTime.of(19, 32)) { 20.0 }
        tick(LocalTime.of(19, 32))
        assertEquals(1, McxPaperArms.view.value.open.size)
        McxPaperArms.testStoppedWhy = { throw IllegalStateException("the stop could not be read") }
        futureMinutes(LocalTime.of(19, 34)) { 312.0 }
        optionMinutes(LocalTime.of(19, 34)) { 21.0 }
        tick(LocalTime.of(19, 34))
        assertEquals("still held", 1, McxPaperArms.view.value.open.size)
        assertTrue(McxPaperArms.view.value.closed.none { it.why == "operator_stop" })
        // A real stop for today still sells it (30 Oct: the commodity side's own stop).
        McxPaperArms.testStoppedWhy = null
        McxPaperArms.testMcxStoppedWhy = { com.optionslab.ira.DayStop.Why.BOSS }
        futureMinutes(LocalTime.of(19, 35)) { 312.0 }
        optionMinutes(LocalTime.of(19, 35)) { 21.0 }
        tick(LocalTime.of(19, 35))
        assertEquals("operator_stop", McxPaperArms.view.value.closed.single().why)
        assertNothingAtZerodha()
    }

    /**
     * 25 Oct (app audit; 23 Oct's US-night silver short bought back at 14:39, its rule holding it to 23:20): a loss stop whose
     * account cannot be read (here no account kept at all) never sells a held MCX position - the commodities keep their own
     * limit - while a new entry still counts it as stopped.
     */
    @Test fun aLossStopOfAnUnknownAccountNeverSellsAHeldPosition() {
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        futureMinutes(LocalTime.of(19, 32)) { 311.0 }
        optionMinutes(LocalTime.of(19, 32)) { 20.0 }
        tick(LocalTime.of(19, 32))
        assertEquals(1, McxPaperArms.view.value.open.size)
        McxPaperArms.testStoppedWhy = { com.optionslab.ira.DayStop.Why.LOSS }
        futureMinutes(LocalTime.of(19, 34)) { 312.0 }
        optionMinutes(LocalTime.of(19, 34)) { 21.0 }
        tick(LocalTime.of(19, 34))
        assertEquals("still held", 1, McxPaperArms.view.value.open.size)
        assertTrue(McxPaperArms.view.value.closed.none { it.why == "operator_stop" })
        // Boss's own commodity stop still sells it (30 Oct: the index side's own stop no longer does).
        McxPaperArms.testStoppedWhy = { com.optionslab.ira.DayStop.Why.BOSS }
        futureMinutes(LocalTime.of(19, 35)) { 312.0 }
        optionMinutes(LocalTime.of(19, 35)) { 21.0 }
        tick(LocalTime.of(19, 35))
        assertEquals("Boss's index stop: still held", 1, McxPaperArms.view.value.open.size)
        McxPaperArms.testMcxStoppedWhy = { com.optionslab.ira.DayStop.Why.BOSS }
        futureMinutes(LocalTime.of(19, 36)) { 312.0 }
        optionMinutes(LocalTime.of(19, 36)) { 21.0 }
        tick(LocalTime.of(19, 36))
        assertEquals("operator_stop", McxPaperArms.view.value.closed.single().why)
        assertNothingAtZerodha()
    }

    // ---- 30 Oct (Boss: "Make stop bot different for index and comodities"): each side's own stop, through the real book ----------

    /** The 19:32 entry (the future breaks up at 311), as in the evening break's own test. */
    private fun eveningEntry(): McxPaperArms.Pos? {
        futureMinutes(LocalTime.of(19, 32)) { 311.0 }
        optionMinutes(LocalTime.of(19, 32)) { 20.0 }
        tick(LocalTime.of(19, 32))
        return McxPaperArms.view.value.open.singleOrNull()
    }

    /** The target reached at 19:35 (321): the 19:36 pass sells it at its target. */
    private fun targetAt1936() {
        futureMinutes(LocalTime.of(19, 36)) { t -> if (t.isBefore(LocalTime.of(19, 35))) 312.0 else 321.0 }
        optionMinutes(LocalTime.of(19, 36)) { t -> if (t.isBefore(LocalTime.of(19, 35))) 21.0 else 26.0 }
        tick(LocalTime.of(19, 36))
    }

    @Test fun theMainIndexStopLeavesTheMcxArmsTradingAndExiting() {
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        at(LocalTime.of(19, 0))
        runBlocking { Strategies.stopForToday(stopRunning = true, compromised = false) }
        assertTrue(runBlocking { Strategies.stoppedToday() })
        assertTrue("the MCX arm still enters", eveningEntry() != null)
        // The tile's index stop too: still held, then sold at its own target.
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false, why = com.optionslab.ira.DayStop.Why.TILE) }
        tick(LocalTime.of(19, 33))
        assertEquals(1, McxPaperArms.view.value.open.size)
        targetAt1936()
        assertEquals("target", McxPaperArms.view.value.closed.single().why)
        assertNothingAtZerodha()
    }

    @Test fun theCommodityStopRefusesNewEntriesAndSellsWhenAsked() {
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        at(LocalTime.of(19, 0))
        runBlocking { Strategies.stopCommodities(sell = true) }
        assertFalse("the index side is not stopped", runBlocking { Strategies.stoppedToday() })
        assertNull("no new MCX entry", eveningEntry())
        val st = McxPaperArms.view.value.status[McxEveRules.SOURCE].orEmpty()
        assertTrue(st, st.contains("stopped_for_today") && st.contains("commodity bots are stopped"))
        assertEquals("Commodity bots running: the armed MCX arms trade again.", runBlocking { Strategies.startCommoditiesAgain() })
        assertNothingAtZerodha()
    }

    @Test fun theCommodityStopSellsWhatIsHeldWhenAsked() {
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        assertTrue(eveningEntry() != null)
        runBlocking { Strategies.stopCommodities(sell = true) }
        futureMinutes(LocalTime.of(19, 34)) { 312.0 }
        optionMinutes(LocalTime.of(19, 34)) { 21.0 }
        tick(LocalTime.of(19, 34))
        assertEquals("operator_stop", McxPaperArms.view.value.closed.single().why)
        assertNothingAtZerodha()
    }

    /** OPEN POSITIONS FIRST: a commodity stop without the sale keeps watching what is held - its target still sells it. */
    @Test fun theCommodityStopWithoutTheSaleKeepsWatchingTheHeldPosition() {
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        val pos = eveningEntry()!!
        runBlocking { Strategies.stopCommodities(sell = false) }
        tick(LocalTime.of(19, 33))
        assertEquals("held, not sold", 1, McxPaperArms.view.value.open.size)
        assertEquals("its stop still rests", "trigger pending", Paper.state.orders.single { it.orderId == pos.stopOrderId }.status)
        targetAt1936()
        assertEquals("target", McxPaperArms.view.value.closed.single().why)
        assertNothingAtZerodha()
    }

    @Test fun jarvisStopAllStopsTheMcxArmsAndSellsWhatTheyHold() {
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        assertTrue(eveningEntry() != null)
        runBlocking { Strategies.stopAll(stopRunning = true, compromised = false) }
        assertTrue(runBlocking { Strategies.stoppedToday() })
        futureMinutes(LocalTime.of(19, 34)) { 312.0 }
        optionMinutes(LocalTime.of(19, 34)) { 21.0 }
        tick(LocalTime.of(19, 34))
        assertEquals("operator_stop", McxPaperArms.view.value.closed.single().why)
        assertNothingAtZerodha()
    }

    /** The kill switch stays global: neither side's stop or start touches it, and it still refuses an MCX entry. */
    @Test fun theKillSwitchStaysGlobalThroughEachSidesStopAndStart() {
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        TradeFixtures.killSwitch(true)
        runBlocking { Strategies.stopCommodities(sell = false); Strategies.startCommoditiesAgain() }
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false); Strategies.startAgain() }
        assertTrue(AppSettings.load().guardKill)
        assertNull(eveningEntry())
        assertTrue(McxPaperArms.view.value.status[McxEveRules.SOURCE].orEmpty().startsWith("eve_kill_switch"))
        assertNothingAtZerodha()
    }

    /**
     * 18 Oct, review D8: its paper trades reach the live path. The evening break's paper buy tells [ArmLive] it entered (its own
     * arm key, its paper quantity) and its paper target exit tells it the trade closed; the registered MCX book shows it at 0.
     */
    @Test fun theEveningBreaksPaperTradeReachesTheLivePath() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        futureMinutes(LocalTime.of(19, 32)) { 311.0 }
        optionMinutes(LocalTime.of(19, 32)) { 20.0 }
        tick(LocalTime.of(19, 32))
        val pos = McxPaperArms.view.value.open.single()
        val ref = "mcx:${pos.arm}:${pos.orderId ?: pos.entryTime}"
        assertTrue(heard.toString(), heard.any { it.startsWith("entered|${McxEveRules.SOURCE}|$ref|1250|") })
        assertEquals(listOf(ArmLive.PaperLeg(ref, McxEveRules.SOURCE, 1250)), ArmLive.legsForTest(com.optionslab.engine.orb.LiveMirror.MCX))
        // The target (320) is reached at 19:35: the 19:36 pass sells on paper, and the live path hears it closed.
        futureMinutes(LocalTime.of(19, 36)) { t -> if (t.isBefore(LocalTime.of(19, 35))) 312.0 else 321.0 }
        optionMinutes(LocalTime.of(19, 36)) { t -> if (t.isBefore(LocalTime.of(19, 35))) 21.0 else 26.0 }
        tick(LocalTime.of(19, 36))
        assertEquals("target", McxPaperArms.view.value.closed.single().why)
        assertTrue(heard.toString(), heard.any { it == "closed|$ref|target" })
        assertEquals(listOf(ArmLive.PaperLeg(ref, McxEveRules.SOURCE, 0)), ArmLive.legsForTest(com.optionslab.engine.orb.LiveMirror.MCX))
        assertNothingAtZerodha()
    }

    // ---- the trade manager in SHADOW (10 Oct; every leg hooked, SHADOW by default) ------------------------------------------

    /**
     * [scenario] run again with the trade manager in SHADOW disagreeing at every pass ([ManagerShadow.adverse]): its own exact
     * assertions on the trades, their exits and the paper book hold unchanged, and the manager recorded would-have exits for
     * [family] that nothing acted on - its arm's real exit stands as the original rules'.
     */
    private fun shadowed(family: String, scenario: () -> Unit) {
        assertEquals(com.optionslab.ira.TradeManager.Mode.SHADOW, TradeManagerHost.policy.value.mode(family, com.optionslab.ira.TradeManager.Account.PAPER))
        ManagerShadow.adverse()
        looks = true
        try { scenario() } finally { looks = false }
        val notes = ManagerShadow.wouldHaveExited(family)
        assertTrue("the manager disagreed with $family", notes.isNotEmpty())
        assertTrue(notes.none { it.acted })
        for (r in TradeManagerHost.records.value.filter { it.trade.family == family && it.closed }) assertEquals(r.actual, r.original)
        TradeManagerHost.resetForTest()
    }

    @Test fun inShadowTheEveningBreakTradesExactlyAsBefore() = shadowed("mcx-eve") { theEveningBreakBuysAndSellsOnPaperOnlyEvenWithEverythingLive() }

    // MCX trend is OFF by default since R12 (multi-month holds: nothing for a minute manager); switched to SHADOW here.
    @Test fun inShadowTheTrendLegTradesExactlyAsBefore() {
        com.optionslab.app.testing.ManagerShadow.shadow("mcx-trend")
        shadowed("mcx-trend") { theTrendLegBuysItsMiniFutureOnPaperOnly() }
    }

    @Test fun inShadowUsNightSilverTradesExactlyAsBefore() = shadowed("silver-night") { usNightSilverBuysOnPaperOnlyWithEverythingLiveAndItsDisasterStopReadsTheMinutesLow() }

    @Test fun theKillSwitchRefusesAnEntry() {
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        TradeFixtures.killSwitch(true)
        futureMinutes(LocalTime.of(19, 32)) { 311.0 }
        optionMinutes(LocalTime.of(19, 32)) { 20.0 }
        val orders = Paper.state.orders.size
        tick(LocalTime.of(19, 32))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(McxPaperArms.view.value.status[McxEveRules.SOURCE].orEmpty(), McxPaperArms.view.value.status[McxEveRules.SOURCE].orEmpty().startsWith("eve_kill_switch"))
        assertNothingAtZerodha()
    }

    @Test fun noOptionBuyTheEveningBeforeExpiry() {
        // The NATURALGAS options expire tomorrow (Fri 9 Oct): after 15:00 today no new buy (it would be closed again by 23:00).
        mcxList(gasOptExpiry = LocalDate.of(2026, 10, 9))
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        futureMinutes(LocalTime.of(19, 32)) { 311.0 }
        upstox.minutes["MCX_FO|900305"] = listOf(FakeUpstox.Candle(LocalTime.of(19, 31), 20.0, 20.0, 20.0, 20.0))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(19, 32))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(McxPaperArms.view.value.status[McxEveRules.SOURCE].orEmpty(), McxPaperArms.view.value.status[McxEveRules.SOURCE].orEmpty().startsWith("eve_expiry_rule"))
        assertNothingAtZerodha()
    }

    @Test fun theTrendLegBuysItsMiniFutureOnPaperOnly() {
        // 253 rising closes to 30 Sep: October's 12-month signal is long for CRUDEOILM.
        val days = generateSequence(LocalDate.of(2026, 9, 30)) { it.minusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(253).toList().reversed()
        val closes = days.mapIndexed { i, d -> d to 5_000.0 + i }
        McxPaperArms.testDaily = { leg -> if (leg == "CRUDEOILM") closes else emptyList() }
        upstox.minutes[crudeMiniKey] = listOf(FakeUpstox.Candle(LocalTime.of(9, 59), 5_600.0, 5_600.0, 5_600.0, 5_600.0))
        runBlocking { McxPaperArms.setArmed(McxTrendRules.SOURCE, true) }
        tick(LocalTime.of(10, 0))
        val leg = McxPaperArms.view.value.open.single()
        assertEquals("CRUDEOILM", leg.leg)
        assertEquals(1, leg.side)
        assertEquals(10, leg.qty)
        assertEquals(LocalDate.of(2026, 10, 19), leg.expiry)
        assertTrue(Paper.state.positions.any { it.symbol == leg.symbol && it.quantity == 10 })
        // The next pass holds; nothing more is bought.
        tick(LocalTime.of(10, 1))
        assertEquals(1, McxPaperArms.view.value.open.size)
        assertTrue(McxPaperArms.view.value.status[McxTrendRules.SOURCE].orEmpty().contains("CRUDEOILM: hold"))
        assertNothingAtZerodha()
    }

    /** Line-by-line review G3 (M3): a trend leg's close (a flip) that did not fill is tried again on the next pass, not the next day. */
    @Test fun aTrendLegsCloseThatDidNotFillIsTriedAgainTheSameDay() {
        val d1 = LocalDate.of(2026, 10, 30)                   // a Friday
        val d2 = LocalDate.of(2026, 11, 2)                    // the Monday after
        val novKey = "MCX_FO|900501"
        fun crudeList(today: LocalDate) {
            val a = JSONArray()
            a.put(JSONArray().put(3).put(900501).put("CRUDEOILM26NOVFUT").put("CRUDEOILM").put("2026-11-19").put(0.0).put("").put(1.0).put(10))
            a.put(JSONArray().put(6).put(900502).put("CRUDEOILM26DECFUT").put("CRUDEOILM").put("2026-12-18").put(0.0).put("").put(1.0).put(10))
            File(context.filesDir, "mcx_contracts.json").writeText(JSONObject().put("day", today.toString()).put("c", a).toString())
            McxMarket.wipe()
        }
        // Rising to 30 Sep (October's signal: long), then down through October (November's: short).
        val days = generateSequence(d1) { it.minusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(300).toList().reversed()
        val closes = days.mapIndexed { i, d -> d to if (d.isAfter(LocalDate.of(2026, 9, 30))) 1_000.0 else 5_000.0 + i }
        McxPaperArms.testDaily = { leg -> if (leg == "CRUDEOILM") closes else emptyList() }
        crudeList(d1)
        upstox.minutes[novKey] = listOf(FakeUpstox.Candle(LocalTime.of(9, 59), 5_600.0, 5_600.0, 5_600.0, 5_600.0))
        runBlocking { McxPaperArms.setArmed(McxTrendRules.SOURCE, true) }
        tick(LocalTime.of(10, 0), on = d1)
        val long = McxPaperArms.view.value.open.single()
        assertEquals(1, long.side)
        // Monday: November's signal is short - the flip closes the long first. No price yet: the close does not fill.
        crudeList(d2)
        upstox.minutes[novKey] = emptyList()
        tick(LocalTime.of(10, 0), on = d2)
        assertEquals("still long: the close did not fill", 1, McxPaperArms.view.value.open.single().side)
        assertEquals(10, Paper.state.positions.filter { it.symbol == long.symbol }.sumOf { it.quantity })
        // A price now: the next pass closes it (and the short is opened), the same day.
        upstox.minutes[novKey] = listOf(FakeUpstox.Candle(LocalTime.of(10, 0), 5_500.0, 5_500.0, 5_500.0, 5_500.0))
        tick(LocalTime.of(10, 1), on = d2)
        val closed = McxPaperArms.view.value.closed.single { it.symbol == long.symbol }
        assertEquals("trend_flip", closed.why)
        assertEquals(-1, McxPaperArms.view.value.open.single().side)
        assertEquals(-10, Paper.state.positions.filter { it.symbol == long.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    // ---- US-night silver (9 Oct, research R2 rule 1; futures, paper only) -------------------------------------------------

    private val silverKey = "MCX_FO|900600"

    /** Yahoo's chart JSON for 5-minute bars at the given starts (IST) and opens (each bar's close = its open). */
    private fun chart(bars: List<Pair<java.time.LocalDateTime, Double>>): JSONObject {
        val ts = JSONArray(); val px = JSONArray(); val vol = JSONArray()
        bars.forEach { (t, v) -> ts.put(t.atZone(IST).toEpochSecond()); px.put(v); vol.put(0) }
        val quote = JSONObject().put("open", px).put("high", px).put("low", px).put("close", px).put("volume", vol)
        return JSONObject().put("chart", JSONObject().put("result", JSONArray().put(JSONObject().put("timestamp", ts)
            .put("indicators", JSONObject().put("quote", JSONArray().put(quote))))))
    }

    /**
     * The US feed: COMEX silver [siPrev] at the previous MCX close (Wed 7 Oct 23:30 IST, US summer time) and [siNow] at 08:30
     * today; USD/INR flat at 88 (its close bar left out of the 5-minute bars with [noFxClose], as Yahoo's night series is; 21 Oct:
     * its 1-hour bars hold the close unless [noFxHourly]; [noFx]: the USD/INR feed does not answer; [noSiClose]: silver's close
     * bar missing). Every URL the arm asked for is kept in [asked].
     */
    private fun usFeed(siPrev: Double, siNow: Double, noFxClose: Boolean = false, asked: MutableList<String> = ArrayList(),
                       noFxHourly: Boolean = noFxClose, noFx: Boolean = false, noSiClose: Boolean = false) {
        val prev = LocalDate.of(2026, 10, 7).atTime(23, 30)
        val ref = day.atTime(8, 30)
        UsCues.resetForTest()
        UsCues.testFetch = { url ->
            asked += url
            when {
                "SI%3DF" in url -> chart(if (noSiClose) listOf(ref to siNow) else listOf(prev to siPrev, ref to siNow))
                "INR%3DX" in url && noFx -> throw java.io.IOException("test: the feed did not answer")
                "INR%3DX" in url && "interval=1h" in url ->
                    chart(if (noFxHourly) listOf(day.atTime(8, 0) to 88.0) else listOf(prev.withMinute(0) to 88.0, prev to 88.0, day.atTime(8, 0) to 88.0))
                "INR%3DX" in url -> chart(if (noFxClose) listOf(ref to 88.0) else listOf(prev to 88.0, ref to 88.0))
                else -> throw AssertionError(url)
            }
        }
    }

    /** SILVERMIC's minutes from 09:00 to [until] (exclusive): [px] each, with a low of [low] at [lowAt]. */
    private fun silverMinutes(until: LocalTime, px: Double = 75_000.0, lowAt: LocalTime? = null, low: Double = px) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 0)
        while (t.isBefore(until)) { out += FakeUpstox.Candle(t, px, px, if (t == lowAt) low else px, px); t = t.plusMinutes(1) }
        upstox.minutes[silverKey] = out
    }

    @Test fun usNightSilverBuysOnPaperOnlyWithEverythingLiveAndItsDisasterStopReadsTheMinutesLow() {
        mcxList(silver = true)
        val asked = ArrayList<String>()
        usFeed(siPrev = 30.0, siNow = 30.6, asked = asked)                 // silver +2% overnight, the rupee flat: s > 0, a buy
        silverMinutes(LocalTime.of(9, 5))
        assertTrue(runBlocking { McxPaperArms.setArmed(McxUsSilverRules.SOURCE, true) }.contains("paper only"))
        // Before 09:05: nothing read, nothing bought.
        tick(LocalTime.of(9, 3))
        assertTrue(asked.isEmpty())
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        tick(LocalTime.of(9, 5))
        val pos = McxPaperArms.view.value.open.single()
        assertEquals(McxUsSilverRules.SOURCE, pos.arm)
        assertEquals(1, pos.side)
        assertEquals("1 lot of SILVERMIC (1 kg)", 1, pos.qty)
        assertFalse(pos.option)
        assertEquals(LocalDate.of(2026, 11, 27), pos.expiry)
        assertEquals(23 * 60 + 20, pos.flatBy)
        assertTrue("paid the spread", pos.entry > 75_000.0)
        assertTrue(asked.any { "SI%3DF" in it } && asked.any { "INR%3DX" in it })
        // Its disaster stop (not part of the research) rests in the paper book: a SELL 3% under the fill.
        val stop = Paper.state.orders.single { it.orderId == pos.stopOrderId }
        assertEquals("SL-M", stop.priceType); assertEquals("SELL", stop.action)
        assertEquals(pos.entry * 0.97, stop.triggerPrice!!.toDouble(), 1.0)
        val status = McxPaperArms.view.value.status[McxUsSilverRules.SOURCE].orEmpty()
        assertTrue(status, status.contains("bought") && status.contains("not part of the research") && status.contains("out at 23:20"))
        assertNothingAtZerodha()
        // Once a day: the next pass buys nothing more.
        tick(LocalTime.of(9, 6))
        assertEquals(1, McxPaperArms.view.value.open.size)
        // A minute's LOW 4% down (its close back up) is the disaster stop: sold on paper at the next pass.
        silverMinutes(LocalTime.of(10, 1), lowAt = LocalTime.of(10, 0), low = 72_000.0)
        tick(LocalTime.of(10, 1))
        val done = McxPaperArms.view.value.closed.single { it.arm == McxUsSilverRules.SOURCE }
        assertEquals("disaster_stop", done.why)
        assertEquals(0, Paper.state.positions.filter { it.symbol == pos.symbol }.sumOf { it.quantity })
        assertTrue("the resting stop is out of the book", Paper.state.orders.single { it.orderId == pos.stopOrderId }.status in setOf("cancelled", "complete"))
        assertTrue(McxPaperArms.diagLine().contains(McxUsSilverRules.LABEL))
        assertNothingAtZerodha()
    }

    @Test fun usNightSilverSellsWhenTheUsMoveIsDownAndClosesTenMinutesBeforeMcxCloses() {
        mcxList(silver = true)
        usFeed(siPrev = 30.0, siNow = 29.4)                                 // silver -2% overnight: a sell
        silverMinutes(LocalTime.of(9, 5))
        runBlocking { McxPaperArms.setArmed(McxUsSilverRules.SOURCE, true) }
        tick(LocalTime.of(9, 5))
        val pos = McxPaperArms.view.value.open.single()
        assertEquals(-1, pos.side)
        assertEquals("BUY", Paper.state.orders.single { it.orderId == pos.stopOrderId }.action)
        assertTrue(Paper.state.positions.any { it.symbol == pos.symbol && it.quantity == -1 })
        // Held through the day (no wick near 3%), sold back at 23:20 - 10 minutes before MCX's 23:30 close.
        silverMinutes(LocalTime.of(23, 19))
        tick(LocalTime.of(23, 19))
        assertEquals(1, McxPaperArms.view.value.open.size)
        silverMinutes(LocalTime.of(23, 20))
        tick(LocalTime.of(23, 20))
        assertEquals("close_10_min", McxPaperArms.view.value.closed.single().why)
        assertEquals(0, Paper.state.positions.filter { it.symbol == pos.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    /**
     * 21 Oct (Boss's diagnostics, every day: "USD/INR at the 23:30 close not read"): Yahoo's 5-minute USD/INR is empty at night;
     * its 1-hour bars (the research's own) hold the close, so the arm trades when the rule fires.
     */
    @Test fun usNightSilverReadsUsdInrAtTheCloseFromItsHourlyBars() {
        mcxList(silver = true)
        val asked = ArrayList<String>()
        usFeed(siPrev = 30.0, siNow = 30.6, noFxClose = true, noFxHourly = false, asked = asked)
        silverMinutes(LocalTime.of(9, 5))
        runBlocking { McxPaperArms.setArmed(McxUsSilverRules.SOURCE, true) }
        tick(LocalTime.of(9, 5))
        val pos = McxPaperArms.view.value.open.single()
        assertEquals(1, pos.side)
        assertTrue(asked.any { "INR%3DX" in it && "interval=1h" in it })
        val log = McxPaperArms.view.value.log.last { McxUsSilverRules.LABEL in it }
        assertTrue(log, log.contains("USD/INR 88.0000 to 88.0000"))
        assertNothingAtZerodha()
    }

    /** No USD/INR from either feed: counted as no rupee move, as the research did (cand.py), and said plainly in the log. */
    @Test fun usNightSilverWithoutUsdInrTradesOnSilverAloneAndSaysSo() {
        mcxList(silver = true)
        usFeed(siPrev = 30.0, siNow = 29.4, noFx = true)
        silverMinutes(LocalTime.of(9, 5))
        runBlocking { McxPaperArms.setArmed(McxUsSilverRules.SOURCE, true) }
        tick(LocalTime.of(9, 5))
        assertEquals(-1, McxPaperArms.view.value.open.single().side)
        val log = McxPaperArms.view.value.log.last { McxUsSilverRules.LABEL in it }
        assertTrue(log, log.contains("USD/INR not read (the feed did not answer): counted as no move, as the research did"))
        assertNothingAtZerodha()
    }

    @Test fun usNightSilverSkipsTheDayWhenASilverPriceIsMissing() {
        mcxList(silver = true)
        usFeed(siPrev = 30.0, siNow = 30.6, noSiClose = true)
        silverMinutes(LocalTime.of(9, 5))
        runBlocking { McxPaperArms.setArmed(McxUsSilverRules.SOURCE, true) }
        val orders = Paper.state.orders.size
        tick(LocalTime.of(9, 5))
        assertEquals(orders, Paper.state.orders.size)
        val s = McxPaperArms.view.value.status[McxUsSilverRules.SOURCE].orEmpty()
        assertTrue(s, s.startsWith("silver_no_us_price: COMEX silver at the 23:30 close not read (not in the feed's history)"))
        assertTrue(McxPaperArms.view.value.log.any { "silver_no_us_price" in it })
        // Decided for the day: a later pass in the window does not try again.
        usFeed(siPrev = 30.0, siNow = 30.6)
        tick(LocalTime.of(9, 6))
        assertEquals(orders, Paper.state.orders.size)
        assertNothingAtZerodha()
    }

    @Test fun usNightSilverSkipsTheDayAfterTheNearContractRolled() {
        // Wed 7 Oct: the near SILVERMIC is the one expiring that day (no trade that day), and it is remembered.
        val wed = LocalDate.of(2026, 10, 7)
        at(LocalTime.of(9, 5), on = wed)
        mcxList(today = wed, silver = true, silverNear = wed)
        usFeed(siPrev = 30.0, siNow = 30.6)
        runBlocking { McxPaperArms.setArmed(McxUsSilverRules.SOURCE, true) }
        tick(LocalTime.of(9, 5), on = wed)
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        // Thu 8 Oct: the near one is November's now - it rolled overnight: no trade today, said why.
        at(LocalTime.of(9, 5))
        mcxList(silver = true)
        usFeed(siPrev = 30.0, siNow = 30.6)
        silverMinutes(LocalTime.of(9, 5))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(9, 5))
        assertEquals(orders, Paper.state.orders.size)
        val s = McxPaperArms.view.value.status[McxUsSilverRules.SOURCE].orEmpty()
        assertTrue(s, s.startsWith("silver_rolled: the near SILVERMIC changed overnight (2026-10-07 to 2026-11-27)"))
        assertNothingAtZerodha()
    }

    @Test fun usNightSilverOnlyEntersAt0905() {
        mcxList(silver = true)
        usFeed(siPrev = 30.0, siNow = 30.6)
        silverMinutes(LocalTime.of(9, 30))
        runBlocking { McxPaperArms.setArmed(McxUsSilverRules.SOURCE, true) }
        assertTrue(run { at(LocalTime.of(9, 5)); McxPaperArms.wantsWatch() })
        tick(LocalTime.of(9, 30))
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        assertTrue(McxPaperArms.view.value.status[McxUsSilverRules.SOURCE].orEmpty().startsWith("silver_late"))
        assertNothingAtZerodha()
    }

    /**
     * 12 Oct: with an arm armed the watch runs through the whole MCX session, to its close - the SILVERM call's stop filled at
     * 16:30, between the arms' entry minutes, and the watch and the stream ended with MCX open until 17:01.
     */
    @Test fun theWatchRunsTheWholeMcxSessionWhileAnArmIsArmed() {
        at(LocalTime.of(18, 0))
        assertFalse(McxPaperArms.wantsWatch())
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        assertTrue(McxPaperArms.wantsWatch())
        assertTrue(McxMarket.watchDue())
        // Between the morning call's minutes and the evening break's: still watched (it was not, before).
        at(LocalTime.of(16, 30))
        assertTrue(McxPaperArms.wantsWatch())
        assertTrue(McxMarket.watchDue())
        at(LocalTime.of(23, 20))
        assertTrue(McxPaperArms.wantsWatch())
        // MCX shut (after its close, before its open): not wanted; the next wake is the next session's open.
        at(LocalTime.of(23, 45))
        assertFalse(McxPaperArms.wantsWatch())
        assertEquals(day.plusDays(1).atTime(9, 0).atZone(IST).toInstant().toEpochMilli(), McxPaperArms.nextWakeMillis())
        at(LocalTime.of(8, 30))
        assertFalse(McxPaperArms.wantsWatch())
        assertEquals(day.atTime(9, 0).atZone(IST).toInstant().toEpochMilli(), McxPaperArms.nextWakeMillis())
        // Every arm off and nothing held in their book: not wanted at any minute.
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, false) }
        at(LocalTime.of(16, 30))
        assertFalse(McxPaperArms.wantsWatch())
    }

    // ---- 22 Oct (arms audit) ------------------------------------------------------------------------------------------------

    /**
     * The 12-month trend arm (hidden since 21 Oct, its legs still held) and US-night silver can both want the near SILVERMIC
     * future. The paper book nets by symbol, so a silver SELL against the trend's long closed the trend's leg instead of opening
     * a trade (both then "closed elsewhere", silver's at Rs 0). Now silver lets that day go, says why, and the leg stays open.
     */
    @Test fun usNightSilverNeverTradesAgainstTheTrendArmsLegOnTheSameFuture() {
        mcxList(silver = true)
        val days = generateSequence(LocalDate.of(2026, 9, 30)) { it.minusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(253).toList().reversed()
        val closes = days.mapIndexed { i, d -> d to 60_000.0 + i * 10 }
        McxPaperArms.testDaily = { leg -> if (leg == "SILVERMIC") closes else emptyList() }
        silverMinutes(LocalTime.of(9, 4))
        runBlocking { McxPaperArms.setArmed(McxTrendRules.SOURCE, true) }
        tick(LocalTime.of(9, 4))
        val leg = McxPaperArms.view.value.open.single()
        assertEquals(McxTrendRules.SOURCE, leg.arm); assertEquals(1, leg.side)
        // The US night was down: silver would SELL the same future.
        usFeed(siPrev = 30.0, siNow = 29.4)
        silverMinutes(LocalTime.of(9, 5))
        runBlocking { McxPaperArms.setArmed(McxUsSilverRules.SOURCE, true) }
        val orders = Paper.state.orders.size
        tick(LocalTime.of(9, 5))
        assertEquals("no silver order", orders, Paper.state.orders.size)
        val status = McxPaperArms.view.value.status[McxUsSilverRules.SOURCE].orEmpty()
        assertTrue(status, status.startsWith("silver_symbol_held_by_"))
        assertEquals("the trend's leg is still open", leg.symbol, McxPaperArms.view.value.open.single().symbol)
        assertEquals(1, Paper.state.positions.filter { it.symbol == leg.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    /** An exit that cannot fill (no price) leaves the disaster stop resting in the paper book at its trigger, never none. */
    @Test fun anExitThatDoesNotFillLeavesTheStopRestingAtItsTrigger() {
        mcxList(silver = true)
        usFeed(siPrev = 30.0, siNow = 30.6)
        silverMinutes(LocalTime.of(9, 5))
        runBlocking { McxPaperArms.setArmed(McxUsSilverRules.SOURCE, true) }
        tick(LocalTime.of(9, 5))
        val pos = McxPaperArms.view.value.open.single()
        val trigger = Paper.state.orders.single { it.orderId == pos.stopOrderId }.triggerPrice!!.toDouble()
        // 23:20, the clock's exit: no price at all, so the market sale does not fill.
        upstox.minutes[silverKey] = emptyList()
        tick(LocalTime.of(23, 20))
        val still = McxPaperArms.view.value.open.single()
        val stop = Paper.state.orders.single { it.orderId == still.stopOrderId }
        assertEquals("trigger pending", stop.status)
        assertEquals(trigger, stop.triggerPrice!!.toDouble(), 1e-9)
        assertEquals(1, Paper.state.positions.filter { it.symbol == pos.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    /**
     * 25 Oct (merge review): a sale already working for the symbol (here a resting limit sell, as the expiry exit's market order
     * waiting for a price would be) makes the arm's exit a refusal - and then NO second stop goes into the book beside it: when
     * that sale fills the book is flat, and a stop left behind would have sold a second time, short.
     */
    @Test fun aRefusedExitBesideAWorkingSalePutsNoSecondStopIn() {
        mcxList(silver = true)
        usFeed(siPrev = 30.0, siNow = 30.6)
        silverMinutes(LocalTime.of(9, 5))
        runBlocking { McxPaperArms.setArmed(McxUsSilverRules.SOURCE, true) }
        tick(LocalTime.of(9, 5))
        val pos = McxPaperArms.view.value.open.single()
        assertEquals(1, pos.side)
        val c = Paper.contractOf(pos.symbol)!!
        at(LocalTime.of(23, 19))
        val resting = runBlocking { Paper.place(c, "SELL", McxArmRules.LOTS, "LIMIT", "NRML", 999_999.0, null) }
        assertTrue(resting.message, resting.ok)
        assertEquals("open", Paper.state.orders.single { it.orderId == resting.orderId }.status)
        // 23:20, the clock's exit, with no price: the sale is refused (that limit already covers the lot) and no stop is put back.
        upstox.minutes[silverKey] = emptyList()
        tick(LocalTime.of(23, 20))
        val still = McxPaperArms.view.value.open.single()
        assertNull("no stop rests beside the working sale", still.stopOrderId)
        assertTrue(Paper.state.orders.none { it.symbol == pos.symbol && it.status == "trigger pending" })
        assertEquals("the one working sale", 1, Paper.state.orders.count { it.symbol == pos.symbol && it.action == "SELL" && it.status == "open" })
        assertEquals("still held, never short", 1, Paper.state.positions.filter { it.symbol == pos.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    /** A position the paper book no longer holds (the expiry exit, the sweeper or Boss closed it) is booked closed elsewhere. */
    @Test fun aPositionClosedOutsideTheArmIsBookedClosedElsewhereNeverReversed() {
        mcxList(silver = true)
        usFeed(siPrev = 30.0, siNow = 29.4)
        silverMinutes(LocalTime.of(9, 5))
        runBlocking { McxPaperArms.setArmed(McxUsSilverRules.SOURCE, true) }
        tick(LocalTime.of(9, 5))
        val pos = McxPaperArms.view.value.open.single()
        assertEquals(-1, pos.side)
        silverMinutes(LocalTime.of(10, 1))
        at(LocalTime.of(10, 0))
        assertTrue(runBlocking { Paper.close(pos.symbol, "NRML") }.ok)
        tick(LocalTime.of(10, 1))
        assertEquals("closed_elsewhere", McxPaperArms.view.value.closed.single().why)
        assertEquals("flat, never long", 0, Paper.state.positions.filter { it.symbol == pos.symbol }.sumOf { it.quantity })
        assertTrue("its stop is out of the book", Paper.state.orders.none { it.symbol == pos.symbol && it.status == "trigger pending" })
        assertNothingAtZerodha()
    }
}
