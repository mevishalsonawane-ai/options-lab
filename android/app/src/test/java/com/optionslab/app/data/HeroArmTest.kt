package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.ManagerShadow
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
 * the 25,200 CE (Rs 4.00, the nearest OTM call priced Rs 1-5), lot 65: limit 4.05, 18 lots within Rs 5,000. Its exits: half (9 lots) at 5x the fill (20.00),
 * the rest at 20x (80.00) or 15:05, and a stop when a minute of the option closes at or below 1.60 (-60%).
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

    /** The 25,200 CE's 1-minute closes (the bars the -60% stop reads) at the bar labelled [m]; Rs 4.00 unless a test moves it. */
    private var callPx: (Int) -> Double = { 4.0 }

    /** An option's price at the bar labelled [m]: the ATM legs make the straddle, the OTM calls the Rs 1-5 band. */
    private fun premium(strike: Double, right: Right, m: Int): Double = when {
        strike == 25_000.0 || strike == 25_100.0 -> if (m < 13 * 60 + 45) 15.0 else 18.0
        right == Right.CE && strike == 25_150.0 -> 9.0
        right == Right.CE && strike == 25_200.0 -> callPx(m)
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

    /** [looks]: the trade manager takes a look after each pass (the SHADOW re-runs, [ManagerShadow.rerun]). */
    private var looks = false

    private fun tick(t: LocalTime) { at(t); runBlocking { OrbArms.tick() }; if (looks) ManagerShadow.look() }

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
        assertEquals("4.00 plus the 0.16% spread, up to the tick, held to the 4.05 limit", 4.05, p.entry, 0.001)
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
        assertEquals("2.00 less the 0.16% spread, down to the tick", 1.95, closed.exit!!, 0.001)
        val sell = Paper.state.orders.single { it.action == "SELL" }
        assertEquals("LIMIT", sell.priceType)
        assertEquals("Hero · hero_exit", runBlocking { Strategies.owners() }["paper:${sell.orderId}"])
        assertEquals("never reached 5x: nothing sold early", 0, closed.sold)
        assertEquals(18 * lot, closed.qty)
        // The book at the signal and at the exit is kept with the trade; the Upstox candles have no depth: the last price alone.
        assertEquals(listOf("signal", "hero_exit"), closed.seen.map { it.what })
        assertEquals(4.0, closed.seen[0].ltp!!, 1e-9); assertEquals(2.0, closed.seen[1].ltp!!, 1e-9)
        assertTrue(closed.seen.all { it.bid == null && it.ask == null && it.spread == null })
        assertTrue(HeroRules.seenLine(closed.seen[1]).endsWith("LTP 2.00 only (no depth in the feed)"))
        passes(LocalTime.of(15, 6), LocalTime.of(15, 8))
        assertEquals("one entry, one exit", 2, Paper.state.orders.size)
    }

    @Test fun halfAt5xThenTheRestAt1505() {
        setUp(listOf(day, day.plusDays(7)))
        armHero()
        passes(LocalTime.of(13, 44), LocalTime.of(13, 46))
        val p = hero().today.single()
        assertEquals(18 * lot, p.qty); assertEquals(4.05, p.entry, 0.001)
        val ce = key(day, 25_200.0, Right.CE)
        // 20.00 is below 5x of the 4.05 fill (20.25): held.
        upstox.price(ce, 20.0)
        tick(LocalTime.of(13, 59))
        assertEquals(0, hero().today.single().sold)
        assertEquals(1, Paper.state.orders.size)
        // 14:00: it trades at 20.50: half the lots (9 of 18) go with a LIMIT SELL at 20.25, filled at the market.
        upstox.price(ce, 20.5)
        tick(LocalTime.of(14, 0))
        val h = hero().today.single()
        assertTrue("the rest is still held", h.open)
        assertEquals(9 * lot, h.sold); assertEquals(9 * lot, h.qty)
        assertEquals("20.50 less the 0.16% spread, down to the tick", 20.45, h.soldAt!!, 0.001); assertTrue("never below the 5x limit", h.soldAt!! >= 20.25)
        assertEquals(day.atTime(14, 0), h.soldTime)
        val half = Paper.state.orders.single { it.action == "SELL" }
        // 5x of the 4.05 fill (the spread-paid entry) is 20.25 on the tick.
        assertEquals("LIMIT", half.priceType); assertEquals(20.25, half.price!!.toDouble(), 1e-9); assertEquals(9 * lot, half.quantity)
        assertEquals("complete", half.status)
        assertEquals("Hero · hero_5x", runBlocking { Strategies.owners() }["paper:${half.orderId}"])
        assertEquals(9 * lot, Paper.state.positions.filter { it.symbol == h.symbol }.sumOf { it.quantity })
        // Up to 30 (below 20x = 80.00, and 5x is never sold twice): held to 15:05.
        upstox.price(ce, 30.0)
        passes(LocalTime.of(14, 1), LocalTime.of(15, 4))
        assertTrue(hero().today.single().open)
        assertEquals(1, Paper.state.orders.count { it.action == "SELL" })
        // 15:05: the rest at the bid less a tick.
        upstox.price(ce, 10.0)
        tick(LocalTime.of(15, 5))
        val c = hero().today.single()
        assertFalse(c.open)
        assertEquals("hero_exit", c.why); assertEquals(9.95, c.exit!!, 0.001)
        assertEquals(9 * lot, c.qty); assertEquals(9 * lot, c.sold)
        assertEquals("both parts: (5x fill - entry) x 585 + (15:05 fill - entry) x 585",
            (c.soldAt!! - c.entry) * 585 + (c.exit!! - c.entry) * 585, c.grossPnl!!, 1e-6)
        assertTrue(c.grossPnl!! > 9_000)
        val sells = Paper.state.orders.filter { it.action == "SELL" }
        assertEquals(2, sells.size); assertEquals(9 * lot, sells[1].quantity)
        assertEquals("Hero · hero_exit", runBlocking { Strategies.owners() }["paper:${sells[1].orderId}"])
        assertEquals("flat", 0, Paper.state.positions.filter { it.symbol == c.symbol }.sumOf { it.quantity })
        // The book at the signal, the 5x sale and the 15:05 exit (the last price alone: the fake feed has no depth).
        assertEquals(listOf("signal", "hero_5x", "hero_exit"), c.seen.map { it.what })
        assertEquals(listOf(4.0, 20.5, 10.0), c.seen.map { it.ltp!! }.map { Math.round(it * 100) / 100.0 })
        assertTrue(c.seen.all { it.spread == null })
        // Kept across a restart: the saved book read back from the file.
        val saved = String(com.optionslab.app.security.Vault.readFileSteady(java.io.File(context.filesDir, "orb.vault"))!!, Charsets.UTF_8)
        AutomationSupport.orbState(context, JSONObject(saved))
        val again = hero().today.single()
        assertEquals(9 * lot, again.sold); assertEquals(c.soldAt!!, again.soldAt!!, 1e-9); assertEquals(c.soldTime, again.soldTime)
        assertEquals(c.seen, again.seen)
        assertEquals(c.grossPnl!!, again.grossPnl!!, 1e-6)
        passes(LocalTime.of(15, 6), LocalTime.of(15, 8))
        assertEquals("one entry, two exits", 3, Paper.state.orders.size)
    }

    @Test fun aMinuteClosingAtMinus60PercentStopsIt() {
        setUp(listOf(day, day.plusDays(7)))
        armHero()
        passes(LocalTime.of(13, 44), LocalTime.of(13, 46))
        val ce = key(day, 25_200.0, Right.CE)
        // The last price dips to 1.50 but no minute has closed at or below 1.60 yet: held (the stop is on a minute's close).
        upstox.price(ce, 1.5)
        callPx = { m -> if (m >= 14 * 60) 1.5 else 1.7 }
        passes(LocalTime.of(13, 47), LocalTime.of(14, 0))
        assertTrue(hero().today.single().open)
        assertEquals(1, Paper.state.orders.size)
        // The bar labelled 14:00 closes at 1.50 (at 14:01): everything sells at the bid less a tick.
        tick(LocalTime.of(14, 1))
        val c = hero().today.single()
        assertFalse(c.open)
        assertEquals("hero_stop", c.why); assertEquals(1.45, c.exit!!, 0.001)
        assertEquals(18 * lot, c.qty); assertEquals(0, c.sold)
        val sell = Paper.state.orders.single { it.action == "SELL" }
        assertEquals("LIMIT", sell.priceType); assertEquals(1.45, sell.price!!.toDouble(), 1e-9); assertEquals(18 * lot, sell.quantity)
        assertEquals("Hero · hero_stop", runBlocking { Strategies.owners() }["paper:${sell.orderId}"])
        assertEquals(listOf("signal", "hero_stop"), c.seen.map { it.what })
        assertTrue(c.grossPnl!! < 0)
        passes(LocalTime.of(14, 2), LocalTime.of(15, 6))
        assertEquals("one entry, one exit: nothing more that day", 2, Paper.state.orders.size)
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
        // Even armed with the PIN in Live, the live entry refuses it outright (the check every live entry makes first).
        assertEquals("refused: Hero (expiry) is paper only; it never trades on Zerodha", OrbArms.liveRefusal(HeroRules.ARM.source))
        // Its exits stay on paper too: the 5x half is a paper order.
        upstox.price(key(day, 25_200.0, Right.CE), 20.5)
        tick(LocalTime.of(14, 0))
        assertEquals(9 * lot, hero().today.single().sold)
        assertTrue(Paper.state.orders.all { it.status == "complete" || it.status == "cancelled" })
        assertEquals("the entry and the 5x half, both paper", 2, Paper.state.orders.size)
        assertFalse(hero().today.single().live)
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

    // ---- the trade manager in SHADOW (10 Oct; hooked, SHADOW by default): the same trades and P&L, its would-have exits recorded only ----

    @Test fun inShadowTheHeroArmTradesExactlyAsBefore() = ManagerShadow.rerun("hero", { looks = it }) { halfAt5xThenTheRestAt1505() }
}
