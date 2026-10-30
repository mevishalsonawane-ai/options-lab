package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only, so i can continuously observe it"):
 * the MCX paper arms switched OFF (their default; parked and hidden ones too) still decide, and each would-be entry goes to the
 * switched-off shadow ([OffShadow]) with the arm's own exits - the NG evening break's option stop, two hours and its target on
 * the FUTURE; US-night silver's SHORT future (side -1) with its disaster stop above; the trend's carried leg. Never a paper order,
 * never their live path. Switched ON, the paper path is unchanged. (Fixtures as [McxPaperArmsTest].)
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class McxPaperArmsOffShadowTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private val day = LocalDate.of(2026, 10, 8)             // a Thursday: MCX 09:00-23:30
    private val optExpiry = LocalDate.of(2026, 10, 26)
    private val futExpiry = LocalDate.of(2026, 10, 27)
    private val futKey = "MCX_FO|900001"
    private val ceKey = "MCX_FO|900305"                      // NATURALGAS 305 CE: 1-ITM when the future breaks up at 311
    private val crudeMiniKey = "MCX_FO|900500"
    private val silverKey = "MCX_FO|900600"

    private fun at(t: LocalTime, seconds: Long = 30, on: LocalDate = day) {
        Market.testClock = Clock.fixed(on.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun tick(t: LocalTime) { at(t); runBlocking { McxPaperArms.tick() } }

    private fun mcxList() {
        val a = JSONArray()
        fun row(tok: Long, xt: Long, sym: String, name: String, exp: LocalDate, strike: Double, right: String, tick: Double, mult: Int) =
            a.put(JSONArray().put(tok).put(xt).put(sym).put(name).put(exp.toString()).put(strike).put(right).put(tick).put(mult))
        row(1, 900001, "NATURALGAS26OCTFUT", "NATURALGAS", futExpiry, 0.0, "", 0.10, 1250)
        for (k in listOf(295, 300, 305, 310, 315, 320)) {
            row(10L + k, 900000L + k, "NATURALGAS26OCT${k}CE", "NATURALGAS", optExpiry, k.toDouble(), "CE", 0.05, 1250)
            row(20L + k, 910000L + k, "NATURALGAS26OCT${k}PE", "NATURALGAS", optExpiry, k.toDouble(), "PE", 0.05, 1250)
        }
        row(2, 900500, "CRUDEOILM26OCTFUT", "CRUDEOILM", LocalDate.of(2026, 10, 19), 0.0, "", 1.0, 10)
        row(3, 900501, "CRUDEOILM26NOVFUT", "CRUDEOILM", LocalDate.of(2026, 11, 19), 0.0, "", 1.0, 10)
        row(4, 900600, "SILVERMIC-NEAR", "SILVERMIC", LocalDate.of(2026, 11, 27), 0.0, "", 1.0, 1)
        row(5, 900601, "SILVERMIC27FEBFUT", "SILVERMIC", LocalDate.of(2027, 2, 26), 0.0, "", 1.0, 1)
        File(context.filesDir, "mcx_contracts.json").writeText(JSONObject().put("day", day.toString()).put("c", a).toString())
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

    private fun optionMinutes(until: LocalTime, px: Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(19, 30)
        while (t.isBefore(until)) { out += FakeUpstox.Candle(t, px, px * 1.01, px * 0.99, px); t = t.plusMinutes(1) }
        upstox.minutes[ceKey] = out
    }

    private fun chart(bars: List<Pair<java.time.LocalDateTime, Double>>): JSONObject {
        val ts = JSONArray(); val px = JSONArray(); val vol = JSONArray()
        bars.forEach { (t, v) -> ts.put(t.atZone(IST).toEpochSecond()); px.put(v); vol.put(0) }
        val quote = JSONObject().put("open", px).put("high", px).put("low", px).put("close", px).put("volume", vol)
        return JSONObject().put("chart", JSONObject().put("result", JSONArray().put(JSONObject().put("timestamp", ts)
            .put("indicators", JSONObject().put("quote", JSONArray().put(quote))))))
    }

    /** As [McxPaperArmsTest]'s: COMEX silver [siPrev] at the previous MCX close and [siNow] at 08:30, USD/INR flat at 88. */
    private fun usFeed(siPrev: Double, siNow: Double) {
        val prev = LocalDate.of(2026, 10, 7).atTime(23, 30)
        val ref = day.atTime(8, 30)
        UsCues.resetForTest()
        UsCues.testFetch = { url ->
            when {
                "SI%3DF" in url -> chart(listOf(prev to siPrev, ref to siNow))
                "INR%3DX" in url && "interval=1h" in url -> chart(listOf(prev.withMinute(0) to 88.0, prev to 88.0, day.atTime(8, 0) to 88.0))
                "INR%3DX" in url -> chart(listOf(prev to 88.0, ref to 88.0))
                else -> throw AssertionError(url)
            }
        }
    }

    private fun silverMinutes(until: LocalTime, px: Double = 75_000.0) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 0)
        while (t.isBefore(until)) { out += FakeUpstox.Candle(t, px, px, px, px); t = t.plusMinutes(1) }
        upstox.minutes[silverKey] = out
    }

    @Before fun up() {
        upstox = FakeUpstox()                                // (clears Market.testClock: set it after)
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        at(LocalTime.of(19, 32))
        mcxList()
        HiddenArms.resetForTest(context)
        OffShadow.init(context)
        OffShadow.wipe()
        OffShadow.testSession = true
        OffShadow.testPrice = { sym ->
            when {
                "SILVERMIC" in sym.uppercase() -> Triple(75_000.0, 74_999.0, 75_001.0)
                "CRUDEOILM" in sym.uppercase() -> Triple(5_600.0, 5_599.0, 5_601.0)
                else -> Triple(20.0, 19.95, 20.05)
            }
        }
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        McxPaperArms.testDaily = null
        UsCues.testFetch = null
        UsCues.resetForTest()
        ArmLive.testHeard = null
        OffShadow.wipe()
        HiddenArms.resetForTest(context)
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    @Test fun offTheEveningBreakIsAWouldBeTradeAndItsTargetOnTheFutureClosesIt() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        assertFalse(runBlocking { McxPaperArms.refresh() }.isArmed(McxEveRules.SOURCE))
        // 30 Oct (review): a switched-off arm never wakes the MCX watch by itself (battery; the watch's coverage would end the pass
        // at once): it decides in shadow whenever the MCX pass runs for something else.
        at(LocalTime.of(19, 32)); assertFalse(McxPaperArms.wantsWatch())
        futureMinutes(LocalTime.of(19, 32)) { 311.0 }
        optionMinutes(LocalTime.of(19, 32), 20.0)
        val orders = Paper.state.orders.size
        tick(LocalTime.of(19, 32))
        assertEquals("no paper order", orders, Paper.state.orders.size)
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        assertTrue(Paper.state.positions.none { it.quantity != 0 })
        assertTrue(heard.toString(), heard.none { it.startsWith("entered|") })
        val w = OffShadow.view.value.openOf(McxEveRules.SOURCE).single()
        assertTrue(w.symbol, w.symbol.contains("305") && w.symbol.uppercase().contains("CE"))
        assertEquals(1, w.view); assertEquals(1, w.side); assertEquals("MCX", w.exchange)
        assertNotNull("its -15% option stop", w.exits.stop)
        assertTrue(w.exits.stop!! < w.entry)
        assertEquals("two hours after the 19:32 entry minute", LocalTime.of(21, 32), w.exits.exitAt)
        val said = McxPaperArms.view.value.status[McxEveRules.SOURCE].orEmpty()
        assertTrue(said, said.startsWith("switched off - "))
        assertTrue(McxPaperArms.diagLine(), McxPaperArms.diagLine().contains(com.optionslab.engine.orb.OffShadow.OFF_WORDS))
        // 30 Oct (review): an open would-be trade does not wake the MCX watch by itself (battery); the MCX pass judges it when it runs.
        at(LocalTime.of(16, 30)); assertFalse(McxPaperArms.wantsWatch())
        // The future reaches the target (the range edge plus its width: 320) at 19:36: the arm's own exit closes the would-be trade.
        futureMinutes(LocalTime.of(19, 40)) { t -> if (t.isBefore(LocalTime.of(19, 36))) 311.0 else 321.0 }
        optionMinutes(LocalTime.of(19, 40), 26.0)
        tick(LocalTime.of(19, 40))
        assertTrue(OffShadow.view.value.openOf(McxEveRules.SOURCE).isEmpty())
        assertEquals("target", OffShadow.view.value.closedOf(McxEveRules.SOURCE).single().why)
        assertEquals(orders, Paper.state.orders.size)
        assertNothingAtZerodha()
    }

    @Test fun offUsNightSilverIsAWouldBeShortFutureWithItsDisasterStopAbove() {
        usFeed(siPrev = 30.0, siNow = 29.4)                // silver -2% overnight: a SELL
        silverMinutes(LocalTime.of(9, 5))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(9, 5))
        assertEquals("nothing sold on paper", orders, Paper.state.orders.size)
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        val w = OffShadow.view.value.openOf(McxUsSilverRules.SOURCE).single()
        assertEquals("a short future", -1, w.side)
        assertEquals("bearish", -1, w.view)
        assertTrue("sold at the bid side: ${w.entry}", w.entry < 75_000.0)
        assertTrue("its disaster stop rests above: ${w.exits.stop}", w.exits.stop!! > w.entry)
        assertEquals(LocalTime.of(23, 20), w.exits.exitAt)
        assertTrue(McxPaperArms.view.value.status[McxUsSilverRules.SOURCE].orEmpty().startsWith("switched off - "))
        // Once a day: the next pass asks nothing more.
        tick(LocalTime.of(9, 6))
        assertEquals(1, OffShadow.view.value.openOf(McxUsSilverRules.SOURCE).size)
        assertNothingAtZerodha()
    }

    @Test fun offTheTrendLegIsACarriedWouldBeLegAndNothingIsBought() {
        val days = generateSequence(LocalDate.of(2026, 9, 30)) { it.minusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(253).toList().reversed()
        val closes = days.mapIndexed { i, d -> d to 5_000.0 + i }
        McxPaperArms.testDaily = { leg -> if (leg == "CRUDEOILM") closes else emptyList() }
        upstox.minutes[crudeMiniKey] = listOf(FakeUpstox.Candle(LocalTime.of(9, 59), 5_600.0, 5_600.0, 5_600.0, 5_600.0))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(10, 0))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(McxPaperArms.view.value.open.isEmpty())
        val w = OffShadow.view.value.openOf(McxTrendRules.SOURCE).single()
        assertTrue(w.symbol, w.symbol.uppercase().contains("CRUDEOILM"))
        assertEquals(1, w.side); assertEquals(1, w.view)
        assertTrue(w.exits.carry)
        val on = w.exits.exitOn!!
        assertTrue("carried to its roll day or next month's first session: $on", on.isAfter(day) && !on.isAfter(LocalDate.of(2026, 11, 2)))
        // Held: the next pass opens no second leg.
        tick(LocalTime.of(10, 1))
        assertEquals(1, OffShadow.view.value.openOf(McxTrendRules.SOURCE).size)
        assertEquals(orders, Paper.state.orders.size)
        assertNothingAtZerodha()
    }

    @Test fun onTheEveningBreakIsBoughtOnPaperAsBeforeAndTheShadowGetsNothing() {
        assertTrue(runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }.contains("paper only"))
        futureMinutes(LocalTime.of(19, 32)) { 311.0 }
        optionMinutes(LocalTime.of(19, 32), 20.0)
        tick(LocalTime.of(19, 32))
        val pos = McxPaperArms.view.value.open.single()
        assertEquals(McxEveRules.SOURCE, pos.arm)
        assertTrue(Paper.state.positions.any { it.symbol == pos.symbol && it.quantity > 0 })
        assertTrue(OffShadow.view.value.openOf(McxEveRules.SOURCE).isEmpty())
        // The other arms are off and decide in shadow; none of them is due at 19:32 (the morning grid, 09:05, the trend's history).
        assertTrue(OffShadow.view.value.openOf(McxMorningRules.SOURCE).isEmpty())
        assertNothingAtZerodha()
    }
}
