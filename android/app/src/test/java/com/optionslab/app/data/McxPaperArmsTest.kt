package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.mcx.McxMorningRules
import com.optionslab.engine.mcx.McxTrendRules
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

    /** Today's MCX list on the phone, as [McxMarket.contracts] keeps it: the NATURALGAS future and its chain, a CRUDEOILM future. */
    private fun mcxList(today: LocalDate = day, gasOptExpiry: LocalDate = optExpiry) {
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

    /** The 305 CE's minutes 19:30 to [until] (exclusive), at [px] with a low 1% under it (never near its -15% stop). */
    private fun optionMinutes(until: LocalTime, px: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(19, 30)
        while (t.isBefore(until)) { val p = px(t); out += FakeUpstox.Candle(t, p, p * 1.01, p * 0.99, p); t = t.plusMinutes(1) }
        upstox.minutes[ceKey] = out
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
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun tick(t: LocalTime, on: LocalDate = day) { at(t, on = on); runBlocking { McxPaperArms.tick() } }

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

    @Test fun theWatchIsWantedOnlyInsideAnArmedArmsMinutes() {
        at(LocalTime.of(18, 0))
        assertFalse(McxPaperArms.wantsWatch())
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }
        assertTrue(McxPaperArms.wantsWatch())
        assertTrue(McxMarket.watchDue())
        at(LocalTime.of(16, 0))
        assertFalse(McxPaperArms.wantsWatch())
        // The next wake is 17:00 today.
        assertEquals(day.atTime(17, 0).atZone(IST).toInstant().toEpochMilli(), McxPaperArms.nextWakeMillis())
        runBlocking { McxPaperArms.setArmed(McxMorningRules.SOURCE, true) }
        at(LocalTime.of(9, 30))
        assertTrue(McxPaperArms.wantsWatch())
    }
}
