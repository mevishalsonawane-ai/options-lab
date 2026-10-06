package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.HeroRules
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The expiry-day Hero arm's whole minute cycle ([OrbArms.tick]) on the PAPER account, as [OrbArmsDayTest] runs the ORB:
 * a synthetic NIFTY day and option chain ([OrbArms.testHeroBars]) on a clock the test steps ([OrbArms.testNow],
 * [Market.testClock]), with the paper fills priced by the fake Upstox feed.
 *
 * The day: NIFTY 25,000 until the bar labelled 13:44, 25,100 from 13:45 (+0.4% in 15 minutes at the 13:46 close); the ATM
 * straddle 30 (15 + 15) until 13:44 and 36 from 13:45 (20% above its noon low). So the arm fires on the 13:46 pass and buys
 * the 25,200 CE (Rs 4.00, the nearest OTM call priced Rs 1-5), lot 65: limit 4.05, 18 lots within Rs 5,000. It sells at 15:05.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class HeroArmTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private val lot = 65
    private val strikes = (0..10).map { 24_850.0 + 50 * it }

    private fun key(expiry: LocalDate, strike: Double, right: Right) = "NSE_FO|HERO${expiry.dayOfYear}${strike.toInt()}$right"

    /** The index at the bar labelled [m] (minutes since midnight). */
    private fun index(m: Int): Double = if (m < 13 * 60 + 45) 25_000.0 else 25_100.0

    /** An option's price at the bar labelled [m]: the ATM legs make the straddle, the OTM calls the Rs 1-5 band. */
    private fun premium(strike: Double, right: Right, m: Int): Double = when {
        strike == 25_000.0 || strike == 25_100.0 -> if (m < 13 * 60 + 45) 15.0 else 18.0
        right == Right.CE && strike == 25_150.0 -> 9.0
        right == Right.CE && strike == 25_200.0 -> 4.0
        right == Right.CE && strike == 25_250.0 -> 2.0
        right == Right.CE && strike == 25_300.0 -> 1.0
        right == Right.CE && strike > 25_300.0 -> 0.5
        else -> 20.0
    }

    /** Every 1-minute bar that has closed by [t], each priced by [px]. */
    private fun bars(t: LocalDateTime, px: (Int) -> Double): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }.filter { !it.plusMinutes(1).isAfter(t) }
        .map { s -> val p = px(s.hour * 60 + s.minute); Upstox.Bar(s.atZone(IST).toEpochSecond(), p, p, p, p, 1000, 0) }

    private val requested = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Sets up the day: [expiries] are the NIFTY expiries in today's master (the first is the chain the bars price). */
    private fun setUp(expiries: List<LocalDate>) {
        val contracts = ArrayList<Upstox.Contract>()
        for (e in expiries) for (k in strikes) for (r in listOf(Right.CE, Right.PE))
            contracts += Upstox.Contract("NIFTY", e, k, r, lot, key(e, k, r), "NIFTY-HERO-${e.dayOfYear}-${k.toInt()}$r")
        AutomationSupport.contracts(context, contracts)
        val byKey = contracts.associateBy { it.instrumentKey }
        OrbArms.testHeroBars = { k, t ->
            requested += k
            if (k == Upstox.INDEX_KEYS.getValue("NIFTY")) bars(t) { index(it) }
            else byKey[k]?.let { c -> bars(t) { m -> premium(c.strike, c.right, m) } }.orEmpty()
        }
        // The paper fills: the chosen call at Rs 4.00 (the fake feed's last price).
        upstox.price(key(expiries.first(), 25_200.0, Right.CE), 4.0)
    }

    @Before fun up() {
        upstox = FakeUpstox()                               // (clears Market.testClock: set it after)
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        day = AutomationSupport.tradingDayAt(12, 0).toLocalDate()
        at(LocalTime.of(12, 0))
        OrbArms.testIndexBars = { emptyList() }             // no BANKNIFTY day: only the Hero arm is armed
    }

    @After fun down() {
        AutomationSupport.realClocks()
        Market.testClock = null
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(t: LocalTime) {
        val z = day.atTime(t).atZone(IST)
        OrbArms.testNow = z
        Market.testClock = Clock.fixed(z.toInstant(), IST)
    }

    private fun tick(t: LocalTime) { at(t); runBlocking { OrbArms.tick() } }

    private fun passes(from: LocalTime, until: LocalTime) {
        var t = from
        while (!t.isAfter(until)) { tick(t); t = t.plusMinutes(1) }
    }

    private fun armHero(): String {
        val msg = runBlocking { OrbArms.setArmed(HeroRules.ARM.source, true, automatic = true) }
        assertTrue(msg, msg.startsWith("Hero (expiry) armed on paper (it never trades on Zerodha) - Not proven — paper only."))
        return msg
    }

    private fun hero() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == HeroRules.ARM.source }

    @Test fun theHeroArmIsListedOffByDefaultAndPaperOnly() {
        val a = hero()
        assertFalse("off by default", a.armed)
        assertEquals("Hero (expiry)", a.arm.label)
        assertTrue(a.arm.paperOnly)
        // There is no live path: the live entry refuses every paper-only arm before anything else.
        assertNotNull(OrbArms.liveRefusal(HeroRules.ARM.source))
        assertNull("the ORB itself may still go live (after the PIN)", OrbArms.liveRefusal("orb"))
        // Its exit minute is the expiry square-off's: it sells itself before the square-off closes it under another name.
        assertEquals(ExpirySquareOff.AT_MINUTE, HeroRules.EXIT_AT.hour * 60 + HeroRules.EXIT_AT.minute)
    }

    @Test fun onANonExpiryDayItNeverTrades() {
        setUp(listOf(day.plusDays(1), day.plusDays(8)))
        armHero()
        passes(LocalTime.of(13, 25), LocalTime.of(14, 50))
        tick(LocalTime.of(15, 5))
        assertTrue("no paper order at all", Paper.state.orders.isEmpty())
        assertTrue(hero().today.isEmpty())
        assertEquals("hero_not_expiry_day", hero().status)
        assertTrue("not even the chain is read", requested.isEmpty())
    }

    @Test fun onAnExpiryDayTheTriggerBuysOnceWithinBudgetOnPaperAndSellsAt1505() {
        setUp(listOf(day, day.plusDays(7)))
        armHero()
        passes(LocalTime.of(13, 25), LocalTime.of(13, 45))
        assertTrue("no trigger before the index moves", Paper.state.orders.isEmpty())
        tick(LocalTime.of(13, 46))
        val p = hero().today.single()
        assertTrue(p.open); assertFalse("paper, never Zerodha", p.live)
        assertEquals("CE", p.right)
        assertEquals(Paper.contractFor("NIFTY", day, 25_200.0, Right.CE)!!.symbol, p.symbol)
        assertEquals(18 * lot, p.qty)
        assertEquals(4.0, p.entry, 0.05)
        assertTrue("within the Rs 5,000 budget", p.qty * p.entry <= HeroRules.BUDGET)
        assertEquals(day.atTime(13, 46), p.signalBar)
        val buy = Paper.state.orders.single()
        assertEquals("BUY", buy.action); assertEquals("LIMIT", buy.priceType); assertEquals("complete", buy.status)
        assertEquals(4.05, buy.price!!.toDouble(), 1e-9)
        assertEquals("Hero · entry", runBlocking { Strategies.owners() }["paper:${buy.orderId}"])

        // One entry a day: the rest of the window buys nothing more.
        passes(LocalTime.of(13, 47), LocalTime.of(14, 50))
        assertEquals(1, Paper.state.orders.count { it.action == "BUY" })
        tick(LocalTime.of(15, 4))
        assertTrue("held until 15:05", hero().today.single().open)

        // 15:05: a LIMIT SELL at the bid less a tick, owned by "Hero".
        upstox.price(key(day, 25_200.0, Right.CE), 2.0)
        tick(LocalTime.of(15, 5))
        val closed = hero().today.single()
        assertFalse(closed.open)
        assertEquals("hero_exit", closed.why)
        assertEquals(2.0, closed.exit!!, 0.05)
        val sell = Paper.state.orders.single { it.action == "SELL" }
        assertEquals("LIMIT", sell.priceType)
        assertEquals("Hero · hero_exit", runBlocking { Strategies.owners() }["paper:${sell.orderId}"])
        passes(LocalTime.of(15, 6), LocalTime.of(15, 8))
        assertEquals("one entry, one exit", 2, Paper.state.orders.size)
    }

    @Test fun inLiveModeItStillTradesOnlyOnPaper() {
        setUp(listOf(day, day.plusDays(7)))
        AutomationSupport.liveSettings()
        val msg = runBlocking { OrbArms.setArmed(HeroRules.ARM.source, true, automatic = true, pinConfirmed = true) }
        assertTrue(msg, msg.startsWith("Hero (expiry) armed on paper"))
        assertFalse("never armed for Zerodha", hero().liveOk)
        passes(LocalTime.of(13, 44), LocalTime.of(13, 47))
        val p = hero().today.single()
        assertFalse(p.live); assertNull(p.kite)
        assertEquals(1, Paper.state.orders.size)
        assertFalse("nothing went to Zerodha", Broker.loggedIn)
    }

    @Test fun theKillSwitchAndStopForTodayWin() {
        setUp(listOf(day, day.plusDays(7)))
        armHero()
        TradeFixtures.killSwitch(true)
        passes(LocalTime.of(13, 44), LocalTime.of(13, 50))
        assertTrue(Paper.state.orders.isEmpty())
        TradeFixtures.killSwitch(false)
        runBlocking { Strategies.stopForToday(stopRunning = true, compromised = false) }
        passes(LocalTime.of(13, 51), LocalTime.of(14, 0))
        assertTrue(Paper.state.orders.isEmpty())
        assertEquals("stopped_for_today", hero().status)
    }

    @Test fun staleDataStandsItDownForTheDay() {
        setUp(listOf(day, day.plusDays(7)))
        // The feed stops at 13:35: from then on no bar arrives.
        val feed = OrbArms.testHeroBars!!
        OrbArms.testHeroBars = { k, t -> feed(k, minOf(t, day.atTime(13, 35))) }
        armHero()
        passes(LocalTime.of(13, 30), LocalTime.of(13, 50))
        assertEquals("hero_stood_down", hero().status)
        OrbArms.testHeroBars = feed                          // the data comes back: still stood down today
        passes(LocalTime.of(13, 51), LocalTime.of(14, 0))
        assertEquals("hero_stood_down", hero().status)
        assertTrue(Paper.state.orders.isEmpty())
    }

    @Test fun itDisarmsItselfAfterTwelveLosingExpiryDays() {
        setUp(listOf(day, day.plusDays(7)))
        val since = day.minusDays(120).atStartOfDay()
        val positions = JSONArray()
        for (i in 1..12) {
            val d = day.minusDays(7L * i)
            positions.put(JSONObject().put("arm", "hero").put("symbol", "NIFTY-HERO-OLD-$i").put("right", "CE").put("qty", 1170)
                .put("entry", 4.0).put("entryTime", d.atTime(13, 50).toString()).put("signalBar", d.atTime(13, 50).toString())
                .put("entryOrderId", "E$i").put("stopOrderId", "").put("exit", 0.05).put("exitTime", d.atTime(15, 5).toString())
                .put("why", "hero_exit").put("charges", 30.0).put("live", false).put("kite", "").put("unconfirmed", false))
        }
        AutomationSupport.orbState(context, JSONObject().put("armed", JSONObject().put("hero", true)).put("auto", JSONObject().put("hero", true))
            .put("since", JSONObject().put("hero", since.toString())).put("positions", positions))
        assertTrue(hero().armed)
        tick(LocalTime.of(12, 5))
        val a = hero()
        assertFalse("disarmed itself", a.armed)
        assertTrue(a.status, a.status.startsWith("hero_disarmed: 12 losing firing days in a row"))
        assertTrue(OrbArms.describe(a.status).endsWith(HeroRules.NOT_PROVEN + "."))
        // Re-armed by hand: the counters start again from now.
        armHero()
        tick(LocalTime.of(12, 6))
        assertTrue(hero().armed)
    }
}
