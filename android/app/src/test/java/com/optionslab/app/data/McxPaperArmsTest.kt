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
import com.optionslab.engine.mcx.McxUsSilverRules
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
        UsCues.testFetch = null
        UsCues.resetForTest()
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
     * today; USD/INR flat at 88 (its close bar left out with [noFxClose]). Every URL the arm asked for is kept in [asked].
     */
    private fun usFeed(siPrev: Double, siNow: Double, noFxClose: Boolean = false, asked: MutableList<String> = ArrayList()) {
        val prev = LocalDate.of(2026, 10, 7).atTime(23, 30)
        val ref = day.atTime(8, 30)
        UsCues.resetForTest()
        UsCues.testFetch = { url ->
            asked += url
            when {
                "SI%3DF" in url -> chart(listOf(prev to siPrev, ref to siNow))
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

    @Test fun usNightSilverSkipsTheDayWhenAUsPriceIsMissing() {
        mcxList(silver = true)
        usFeed(siPrev = 30.0, siNow = 30.6, noFxClose = true)
        silverMinutes(LocalTime.of(9, 5))
        runBlocking { McxPaperArms.setArmed(McxUsSilverRules.SOURCE, true) }
        val orders = Paper.state.orders.size
        tick(LocalTime.of(9, 5))
        assertEquals(orders, Paper.state.orders.size)
        val s = McxPaperArms.view.value.status[McxUsSilverRules.SOURCE].orEmpty()
        assertTrue(s, s.startsWith("silver_no_us_price: USD/INR at the 23:30 close not read"))
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
