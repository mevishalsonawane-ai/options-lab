package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.sandbox.OrderStatus
import com.optionslab.engine.sandbox.Quote
import com.optionslab.engine.trend.TrendWatch
import com.optionslab.engine.trend.TrendWatch.Source
import com.optionslab.ira.MemoryPlan
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
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The Trend watch ([TrendWatchHost], 16 Oct, research R63): every open paper position of every source is judged on its index's
 * completed bars and, when the trend turns against it, written down ("would sell at ...") - and not one order is placed,
 * changed or cancelled, paper or live; a pending entry order gets "would cancel" and stays; Zerodha positions are watched only
 * with "Live positions too" on (off by default) and then only noted; only a source whose rule PASSED may sell (the Hero
 * ticket's legs, through the arm's own exit); the log is read back after a restart without telling a turn twice; a memory trim
 * never drops a sell request.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class TrendWatchHostTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val file get() = File(context.noBackupFilesDir, "trend_watch.vault")

    private fun at(t: LocalTime) { Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(20).atZone(IST).toInstant(), IST) }
    private fun now(t: LocalTime): LocalDateTime = day.atTime(t).plusSeconds(20)

    /**
     * NIFTY's minutes: two earlier sessions rising (24,300 -> 24,700), then today up to 10:30 and falling hard after it - the
     * 15-minute Supertrend flips down (against a call) in the afternoon, and the day's average is lost (T3). [until] cuts them.
     */
    private fun nifty(until: LocalDateTime): List<Bar> {
        val out = ArrayList<Bar>()
        var px = 24_300.0
        for (d in listOf(day.minusDays(2), day.minusDays(1), day)) for (r in 0 until 375) {
            val t = d.atTime(9, 15).plusMinutes(r.toLong())
            if (t.plusMinutes(1).isAfter(until)) break                 // finished minutes only
            val o = px
            px += if (d == day && r > 75) -1.6 else 0.55
            out += Bar(t, o, maxOf(o, px) + 1, minOf(o, px) - 1, px)
        }
        return out
    }

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        // Paper mode, the guard's time cut-off off and room for every source's one lot.
        TradeFixtures.paperSettings()
        com.optionslab.app.security.SecurePrefs.putAll(mapOf("g.open" to 50, "g.trades" to 500, "g.pTrades" to 500, "g.lots" to 100,
            "g.expo" to 1e9, "g.value" to 1e9))
        day = AutomationSupport.tradingDayAt(10, 0).toLocalDate()
        at(LocalTime.of(10, 0))
        TradeFixtures.seed(context, upstox)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context) }
        TrendWatchHost.resetForTest()
        TrendWatchHost.init(context)
        TrendWatchHost.testMinutes = { u, n -> if (u == "NIFTY") nifty(n) else emptyList() }
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        TrendWatchHost.resetForTest()
        if (Autopilot.enabled()) Autopilot.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** One lot of NIFTY [strike] [right] bought on paper at [t] by [label] (its order labelled as that source labels it). */
    private fun buy(strike: Double, right: Right, label: String, t: LocalTime = LocalTime.of(10, 0), limit: Double? = null): Paper.Result {
        at(t)
        val c = Paper.contractFor("NIFTY", TradeFixtures.nearExpiry, strike, right)!!
        val r = runBlocking { Paper.place(c, "BUY", 1, if (limit != null) "LIMIT" else "MARKET", "NRML", limit, null, Quote(100.0)) }
        assertTrue(r.message, r.ok)
        runBlocking { Strategies.tagOwner("paper:${r.orderId}", label) }
        return r
    }

    private fun round(t: LocalTime) { at(t); runBlocking { TrendWatchHost.round(now(t)) } }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    @Test fun switchesStartAsResearchAllows() {
        val sw = TrendWatchHost.switches()
        assertTrue(sw.on); assertFalse("live is off by default", sw.live); assertFalse("alerts are off by default", sw.alerts)
        assertTrue("the Hero ticket's exit passed research: on", sw.acts(Source.HERO_TICKET))
        for (s in Source.entries.filter { it != Source.HERO_TICKET }) {
            assertFalse(s.name, sw.acts(s))
            assertTrue(TrendWatchHost.setAct(s, true).contains("did not pass research"))
            assertFalse(s.name, TrendWatchHost.switches().acts(s))
        }
        assertTrue(TrendWatchHost.setLive(true).contains("never an order")); assertTrue(TrendWatchHost.switches().live)
        assertTrue(TrendWatchHost.setAlerts(true).contains("locked phone")); assertTrue(TrendWatchHost.switches().alerts)
        assertTrue(TrendWatchHost.setAct(Source.HERO_TICKET, false).contains("notes only"))
        // Read back after a restart.
        TrendWatchHost.resetForTest(); TrendWatchHost.init(context)
        val back = TrendWatchHost.switches()
        assertTrue(back.live && back.alerts && !back.acts(Source.HERO_TICKET) && !back.others)
        assertTrue(TrendWatchHost.setOthers(true).contains("never a sale"))
        TrendWatchHost.resetForTest(); TrendWatchHost.init(context)
        assertTrue(TrendWatchHost.switches().others)
        assertTrue(file.exists())
        assertTrue(TrendWatchHost.diagLine().contains("acting: none"))
    }

    @Test fun onlyTheHeroTicketIsWatchedByDefault() {
        // 16 Oct (Boss: "Can we make that only in Hero strategy r63"): another strategy's position is neither watched nor noted.
        assertFalse(TrendWatchHost.switches().others)
        buy(24_500.0, Right.CE, "R51 Donchian 20 · entry")
        buy(24_300.0, Right.CE, "Manual · Option chart", LocalTime.of(10, 1), limit = 5.0)
        TrendWatchHost.testLive = { listOf(TrendWatchHost.LiveHeld("NIFTY26OCT24500CE", "NFO", 75, 82.5, "Manual · Option chart", day.atTime(10, 0))) }
        TrendWatchHost.setLive(true)
        round(LocalTime.of(14, 30))
        assertTrue(TrendWatchHost.view.value.watched.isEmpty())
        assertTrue(TrendWatchHost.view.value.recent.isEmpty())
        assertEquals("On · Hero ticket only · nothing open", TrendWatchHost.view.value.status)
        assertNothingAtZerodha()
    }

    @Test fun aShadowNoteNeverPlacesChangesOrCancelsAnOrder() {
        TrendWatchHost.setOthers(true)
        buy(24_500.0, Right.CE, "R51 Donchian 20 · entry")
        val pending = buy(24_300.0, Right.CE, "Manual · Option chart", LocalTime.of(10, 1), limit = 5.0)
        val ordersBefore = Paper.state.orders.map { it.orderId to it.status }
        round(LocalTime.of(10, 30))
        assertTrue("not turned yet", TrendWatchHost.view.value.recent.isEmpty())
        assertEquals(Source.DONCHIAN, TrendWatchHost.view.value.watched.single().source)
        round(LocalTime.of(14, 30))
        val recs = TrendWatchHost.view.value.recent
        val note = recs.single { it.source == Source.DONCHIAN }
        assertEquals("NOTE", note.act)
        assertTrue(note.line, note.line.startsWith("trend turned against R51 Donchian 20 ") && note.line.contains("would sell at") &&
            note.line.contains("(T2, shadow: failed research)"))
        val p = recs.single { it.act == "PENDING" }
        assertTrue(p.line, p.line.contains("would cancel") && p.line.contains("nothing cancelled"))
        // The position and the pending order are exactly as they were; nothing at Zerodha.
        assertEquals(ordersBefore, Paper.state.orders.map { it.orderId to it.status })
        assertEquals(OrderStatus.OPEN, Paper.state.orders.single { it.orderId == pending.orderId }.status)
        assertEquals(75, Paper.state.positions.filter { it.quantity != 0 }.single().quantity)
        assertFalse(TrendWatchHost.sellRequested(note.symbol))
        assertNothingAtZerodha()
        // Told once: the next round and a restart write nothing new.
        round(LocalTime.of(14, 31))
        TrendWatchHost.resetForTest(); TrendWatchHost.init(context)
        TrendWatchHost.testMinutes = { u, n -> if (u == "NIFTY") nifty(n) else emptyList() }
        round(LocalTime.of(14, 32))
        assertEquals(recs.size, TrendWatchHost.view.value.recent.size)
    }

    @Test fun everySourceIsWatchedUnderItsOwnName() {
        TrendWatchHost.setOthers(true)
        val labels = listOf(24_300.0 to "Liquidity 15+5 · entry", 24_400.0 to "R52 Open outside value · entry", 24_500.0 to "Solo 2.0 · entry",
            24_600.0 to "Jarvis solo · entry", 24_700.0 to "ntfy alerts (paper) · entry")
        for ((k, l) in labels) buy(k, Right.CE, l)
        val puts = listOf(24_300.0 to "Manual · Jarvis", 24_400.0 to "Manual · Option chart", 24_500.0 to "ORB · entry",
            24_600.0 to "Hero ₹1,000 · entry")
        for ((k, l) in puts) buy(k, Right.PE, l)
        round(LocalTime.of(11, 0))
        val got = TrendWatchHost.view.value.watched.map { it.source }.toSet()
        assertEquals(setOf(Source.LIQUIDITY, Source.OOV, Source.SOLO2, Source.SOLO, Source.NTFY, Source.JARVIS, Source.MANUAL,
            Source.OTHER_ARM, Source.HERO_TICKET), got)
        // A Hero-labelled leg the Hero arm does not hold is not a tested ticket: never sold, only noted.
        assertTrue(TrendWatchHost.view.value.watched.all { it.act == "NOTE" })
        assertTrue(TrendWatchHost.view.value.status, TrendWatchHost.view.value.status.startsWith("On · 9 watched"))
        // Notes only: no sell request for any of them, the Hero-labelled one included.
        assertTrue(TrendWatchHost.view.value.recent.none { it.act == TrendWatch.Act.SELL.name })
        assertNothingAtZerodha()
    }

    @Test fun livePositionsAreWatchedOnlyWithTheirSwitchAndOnlyNoted() {
        TrendWatchHost.setOthers(true)
        TrendWatchHost.testLive = { listOf(TrendWatchHost.LiveHeld("NIFTY26OCT24500CE", "NFO", 75, 82.5, "Manual · Option chart", day.atTime(10, 0))) }
        round(LocalTime.of(14, 30))
        assertTrue("live off: nothing watched or noted", TrendWatchHost.view.value.watched.isEmpty() && TrendWatchHost.view.value.recent.isEmpty())
        TrendWatchHost.setLive(true)
        round(LocalTime.of(14, 31))
        val r = TrendWatchHost.view.value.recent.single()
        assertTrue(r.live && r.act == "NOTE")
        assertTrue(r.line, r.line.contains("live: alert only, never an order") && r.line.contains("82.50"))
        assertNothingAtZerodha()
    }

    @Test fun theWatchIsOffMeansNothingIsWatched() {
        buy(24_500.0, Right.CE, "R51 Donchian 20 · entry")
        assertTrue(TrendWatchHost.setOn(false).contains("off"))
        at(LocalTime.of(14, 30))
        runBlocking { TrendWatchHost.watch() }
        assertTrue(TrendWatchHost.view.value.recent.isEmpty())
        assertEquals("Off: no position is watched", TrendWatchHost.view.value.status)
        TrendWatchHost.setOn(true)
    }

    @Test fun staleOrMissingMinutesJudgeNothing() {
        TrendWatchHost.setOthers(true)
        buy(24_500.0, Right.CE, "R51 Donchian 20 · entry")
        TrendWatchHost.testMinutes = { _, n -> nifty(n.minusMinutes(30)) }
        round(LocalTime.of(14, 30))
        assertTrue(TrendWatchHost.view.value.watched.single().state.contains("stale"))
        TrendWatchHost.testMinutes = { _, _ -> emptyList() }
        round(LocalTime.of(14, 31))
        assertTrue(TrendWatchHost.view.value.watched.single().state.startsWith("no NIFTY minutes"))
        assertTrue(TrendWatchHost.view.value.recent.isEmpty())
    }

    @Test fun openingFillIsTheLastMoveAwayFromFlat() {
        val t = day.atTime(10, 0)
        assertEquals(0, TrendWatchHost.openingFill(listOf(Triple(t, true, 75))))
        assertEquals(2, TrendWatchHost.openingFill(listOf(Triple(t, true, 75), Triple(t.plusMinutes(5), false, 75), Triple(t.plusMinutes(9), true, 75))))
        assertEquals(null, TrendWatchHost.openingFill(listOf(Triple(t, true, 75), Triple(t.plusMinutes(5), false, 75))))
        assertEquals(null, TrendWatchHost.openingFill(emptyList()))
        assertEquals(0, TrendWatchHost.openingFill(listOf(Triple(t, false, 75), Triple(t.plusMinutes(1), false, 75))))
    }

    @Test fun aMemoryTrimLetsGoLastAndKeepsTheSellRequests() {
        TrendWatchHost.requestForTest("NIFTY-HERO-CE")
        assertEquals(MemoryPlan.Item.TREND_WATCH, MemoryPlan.Item.entries.last())
        val done = MemoryTrim.release(MemoryPlan.releases(MemoryPlan.BACKGROUND), MemoryPlan.BACKGROUND)
        assertEquals(MemoryPlan.Item.TREND_WATCH, done.last())
        assertTrue(TrendWatchHost.switches().on)           // read again from its file
        assertTrue(MemoryTrim.lastLine.orEmpty().contains("the position watch untouched"))
        assertTrue("a Hero leg's sell request is part of the position watch: never let go", TrendWatchHost.sellRequested("NIFTY-HERO-CE"))
    }
}
