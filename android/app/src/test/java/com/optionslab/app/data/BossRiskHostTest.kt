package com.optionslab.app.data

import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.sandbox.Quote
import com.optionslab.ira.AccountStop
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 25 Oct (Boss: "Do whatever you think better for application", on the app audit's decisions): the new risk rules at the paper
 * book's entry (applied) and at Zerodha's entry door (shadow until switched on). Every rule only refuses or sizes down a NEW
 * automatic entry: exits and Boss's own orders always go.
 */
class BossRiskHostTest : RobolectricTest() {
    private val exp: LocalDate = Market.today().plusDays(6)

    private fun contract(u: String, strike: Int, right: Right, lot: Int = 30): Paper.Contract {
        val sym = u + exp.format(DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH)).uppercase(Locale.ENGLISH) + strike + right.name
        return Paper.Contract(sym, u, exp, strike.toDouble(), right, lot, "NSE_FO|$sym", if (u == "SENSEX") "BFO" else "NFO")
    }

    private fun buy(c: Paper.Contract, auto: String?, lots: Int = 1, px: Double = 100.0, mirror: String? = null): Paper.Result =
        runBlocking { Paper.place(c, "BUY", lots, "MARKET", "MIS", null, null, Quote(px, bid = px - 0.1, ask = px + 0.1), auto = auto, mirror = mirror) }

    private fun sell(c: Paper.Contract, lots: Int = 1): Paper.Result =
        runBlocking { Paper.place(c, "SELL", lots, "MARKET", "MIS", null, null, Quote(100.0, bid = 99.9, ask = 100.1), exit = true) }

    private fun held(c: Paper.Contract) = Paper.state.positions.filter { it.symbol == c.symbol }.sumOf { it.quantity }

    @Before fun up() {
        val realNow = java.time.ZonedDateTime.now(com.optionslab.engine.IST)
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST),
            java.time.Duration.between(realNow, Market.today().atTime(12, 0).atZone(com.optionslab.engine.IST)))
        Paper.reset(BigDecimal("400000"))
        BossRiskHost.resetForTest(paperOn = true)
    }

    @After fun down() {
        Market.testClock = null
        BossRiskHost.resetForTest(paperOn = false)
        AccountStopHost.resetForTest()
        ExposureCapHost.resetForTest(paperOn = false)
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    // ---- the account stop rule's paper copy ----------------------------------------------------------------------------------

    @Test fun theAccountStopRulesPaperCopyRefusesABotsNewEntryNeverAnExitOrBosssOwnOrder() {
        val c = contract("NIFTY", 25_000, Right.CE)
        assertTrue("before: it goes", buy(c, "R72 Breadth Donchian").ok)
        val today = Market.today()
        // Reached: -Rs 13,500 in this calendar month (counted from a day this month).
        BossRiskHost.setPaperDaysForTest(listOf(AccountStop.Day(today, -13_500.0)), since = today.withDayOfMonth(1))
        assertTrue(BossRiskHost.view.value.accountReached)
        val r = buy(contract("BANKNIFTY", 56_000, Right.PE), "Liquidity 15+5")
        assertFalse(r.ok); assertTrue(r.capped); assertEquals(BossRiskHost.Rule.ACCOUNT, r.rule)
        assertTrue(r.message, r.message.startsWith("refused: the account stop rule (") && r.message.endsWith("on paper"))
        // The open position keeps its exit; Boss's own order still goes.
        assertTrue("an exit always goes", sell(c).ok)
        assertEquals(0, held(c))
        assertTrue("Boss's own order is never refused", buy(contract("FINNIFTY", 26_500, Right.CE), null).ok)
        assertEquals(1, BossRiskHost.recordsToday(BossRiskHost.Rule.ACCOUNT).size)
        assertTrue(BossRiskHost.lines().first(), BossRiskHost.lines().first().contains("REACHED"))
        // Its switch off (with the PIN): the entry goes again.
        BossRiskHost.setPaper(BossRiskHost.Rule.ACCOUNT, false, pinConfirmed = true, log = false)
        assertTrue(buy(contract("BANKNIFTY", 56_000, Right.PE), "Liquidity 15+5").ok)
    }

    @Test fun onceReachedItHoldsUntilBossRestartsTheCountWithThePin() {
        val today = Market.today()
        BossRiskHost.setPaperDaysForTest(listOf(AccountStop.Day(today, -14_000.0)), since = today.withDayOfMonth(1))
        // A recovery the same day does not let the bots back in.
        BossRiskHost.setPaperDaysForTest(listOf(AccountStop.Day(today, -1_000.0)), since = today.withDayOfMonth(1))
        assertTrue(BossRiskHost.view.value.accountReached)
        assertEquals("Restarting the paper account stop rule's count takes your PIN.", BossRiskHost.restartAccount(pinConfirmed = false))
        assertTrue(BossRiskHost.restartAccount(pinConfirmed = true).startsWith("The paper account stop rule counts again from"))
        assertFalse(BossRiskHost.view.value.accountReached)
        assertTrue(buy(contract("NIFTY", 25_000, Right.CE), "R72 Breadth Donchian").ok)
    }

    @Test fun emptyDaysAndAnUnreadBookNeverRefuse() {
        BossRiskHost.setPaperDaysForTest(emptyList(), since = Market.today())
        assertTrue(buy(contract("NIFTY", 25_000, Right.CE), "R72 Breadth Donchian").ok)
        assertTrue(BossRiskHost.lines().first(), BossRiskHost.lines().first().contains("no paper day recorded yet"))
    }

    // ---- the weekly loss stop ----------------------------------------------------------------------------------------------

    @Test fun theWeeklyStopRefusesNewPaperEntriesForTheRestOfTheWeekAndLiveOnlyLogsIt() {
        val today = Market.today()
        val monday = com.optionslab.ira.BossRisk.weekStart(today)
        // Last week's big loss never counts; this week's does.
        BossRiskHost.setPaperDaysForTest(listOf(AccountStop.Day(monday.minusDays(3), -50_000.0)), since = today.plusDays(1))
        assertTrue(buy(contract("NIFTY", 25_000, Right.CE), "Solo 2.0").ok)
        BossRiskHost.setPaperDaysForTest(listOf(AccountStop.Day(today, -20_500.0)), since = today.plusDays(1))
        assertTrue(BossRiskHost.view.value.weekReached)
        val r = buy(contract("BANKNIFTY", 56_000, Right.CE), "R52 Open outside value")
        assertFalse(r.ok); assertEquals(BossRiskHost.Rule.WEEKLY, r.rule)
        assertTrue(r.message, r.message.startsWith("refused: weekly loss stop: this week -Rs 20,500"))
        // The MCX arms and a strategy's automatic start ask it too.
        assertNotNull(BossRiskHost.paperStartRefusal("NG evening breakout", "NATURALGAS"))
    }

    @Test fun theLiveWeeklyStopIsShadowUntilSwitchedOn() {
        // Zerodha's days as the daily loss limit read them (the session: today after the open).
        AccountStopHost.dayRead(-21_000.0)
        val o = Kite.Order("NIFTY26OCT24500CE", Kite.Side.BUY, 75, 75, "MIS", "MARKET", null, 0.05, "NFO", "iraorb")
        assertNull("shadow (the default): nothing changes", Broker.entryRecheck(o, "orb"))
        val rec = BossRiskHost.recordsToday(BossRiskHost.Rule.WEEKLY).single()
        assertTrue(rec.live); assertFalse(rec.applied); assertTrue(rec.why, rec.why.contains("shadow"))
        BossRiskHost.setLive(BossRiskHost.Rule.WEEKLY, true)
        assertTrue(Broker.entryRecheck(o, "orb")!!.startsWith("refused: weekly loss stop"))
        // A sell (an exit) is never asked; Boss's own order and a basket leg neither.
        assertNull(Broker.entryRecheck(o.copy(side = Kite.Side.SELL), "orb"))
        assertNull(Broker.entryRecheck(o.copy(tag = "iraalgo"), null))
        assertNull(Broker.entryRecheck(o.copy(tag = "iraalgostrat"), null))
    }

    // ---- opposite bets -----------------------------------------------------------------------------------------------------

    @Test fun aSecondArmsOppositeBetOnTheSameIndexIsSkippedOnPaper() {
        assertTrue(buy(contract("NIFTY", 25_000, Right.CE), "R52 Open outside value").ok)
        val r = buy(contract("NIFTY", 25_000, Right.PE), "Solo 2.0")
        assertFalse(r.ok); assertTrue(r.capped); assertEquals(BossRiskHost.Rule.OPPOSITE, r.rule)
        assertTrue(r.message, r.message.contains("R52 Open outside value already holds a call on NIFTY"))
        // The same way and another index: not this rule's business.
        assertTrue(buy(contract("NIFTY", 25_100, Right.CE), "Solo 2.0").ok)
        assertTrue(buy(contract("MIDCPNIFTY", 13_000, Right.PE), "Solo 2.0").ok)
        // Boss's own put is never refused (and never counted as an arm's bet).
        assertTrue(buy(contract("NIFTY", 24_800, Right.PE), null).ok)
        // A later arm's put is refused against both calls now held.
        assertTrue(buy(contract("NIFTY", 24_900, Right.PE), "R72 Breadth Donchian").message.contains("and 1 more"))
        // Switched off (with the PIN): the opposite bet goes.
        BossRiskHost.setPaper(BossRiskHost.Rule.OPPOSITE, false, pinConfirmed = true, log = false)
        assertTrue(buy(contract("NIFTY", 24_700, Right.PE), "R72 Breadth Donchian").ok)
    }

    /** CLAUDE.md's LIVE rule: while a paper entry could also be mirrored to Zerodha, the paper rules only LOG it (never skip a live trade). */
    @Test fun whileAnEntryCouldGoLiveThePaperRulesOnlyLog() {
        assertTrue(buy(contract("NIFTY", 25_000, Right.CE), "R52 Open outside value").ok)
        com.optionslab.app.testing.AutomationSupport.liveSettings()
        com.optionslab.app.security.SecurePrefs.put(com.optionslab.app.ira.IraNewsTrades.PAPER_KEY, false)
        PaperOnly.resetForTest(null)
        try {
            ArmLive.liveFlags()
            assertTrue(ExposureCapHost.liveMirrorPossible())
            val r = buy(contract("NIFTY", 25_000, Right.PE), "Solo 2.0")
            assertTrue("logged only: it goes", r.ok)
            val rec = BossRiskHost.recordsToday(BossRiskHost.Rule.OPPOSITE).single()
            assertFalse(rec.applied); assertTrue(rec.why, rec.why.contains("logged only: this entry may also go live"))
            // A paper-only entry never goes live: refused as in Paper.
            assertFalse(buy(contract("NIFTY", 24_900, Right.PE), "Jarvis news", mirror = ExposureCapHost.NO_LIVE).ok)
        } finally {
            com.optionslab.app.security.SecurePrefs.put(com.optionslab.app.ira.IraNewsTrades.PAPER_KEY, true)
            com.optionslab.app.testing.AutomationSupport.liveSettings(live = false)
            ArmLive.liveFlags()
        }
    }

    @Test fun anArmsOwnTwoSidesAreItsOwnRule() {
        assertTrue(buy(contract("NIFTY", 25_000, Right.CE), "R52 Open outside value").ok)
        assertTrue("the same arm's put", buy(contract("NIFTY", 24_900, Right.PE), "R52 Open outside value").ok)
        assertTrue(BossRiskHost.recordsToday(BossRiskHost.Rule.OPPOSITE).isEmpty())
    }

    // ---- the 2% risk cap ---------------------------------------------------------------------------------------------------

    @Test fun overTheCapThreeLotsAreSizedDownAndOneLotOverIsSkipped() {
        // Rs 4L account: 2% is Rs 8,000. BANKNIFTY 30 x Rs 600, -15% stop: Rs 2,760 a lot with charges -> 2 lots fit.
        val c = contract("BANKNIFTY", 56_000, Right.CE)
        val r = buy(c, "Liquidity 15+5", lots = 3, px = 600.0)
        assertTrue(r.message, r.ok)
        assertEquals(BossRiskHost.Rule.RISK, r.rule)
        assertEquals("sized down to 2 lots", 60, held(c))
        // Night (no stop: the whole premium) at Rs 400 x 30 = Rs 12,000 a lot: over 2%, skipped.
        val n = buy(contract("NIFTY", 25_000, Right.CE), "Night (R3)", px = 400.0)
        assertFalse(n.ok); assertEquals(BossRiskHost.Rule.RISK, n.rule)
        assertTrue(n.message, n.message.startsWith("refused: 2% risk cap: 1 lot risks"))
        // An exit is never capped.
        assertTrue(sell(c, lots = 2).ok)
        assertEquals(2, BossRiskHost.recordsToday(BossRiskHost.Rule.RISK).size)
    }

    @Test fun theLiveRiskCapIsShadowAndRefusesWholeWhenSwitchedOn() {
        BossRiskHost.liveCapital = 400_000.0
        val o = Kite.Order("BANKNIFTY26OCT56000CE", Kite.Side.BUY, 90, 30, "MIS", "LIMIT", 600.0, 0.05, "NFO", "iraorb")
        assertNull(Broker.entryRecheck(o, "liquidity15", intended = 600.0, stop = 510.0))
        val rec = BossRiskHost.recordsToday(BossRiskHost.Rule.RISK).single()
        assertTrue(rec.why, rec.live && !rec.applied && rec.why.contains("3 lots risk") && rec.why.contains("2 would fit"))
        BossRiskHost.setLive(BossRiskHost.Rule.RISK, true)
        assertTrue(Broker.entryRecheck(o.copy(tradingSymbol = "BANKNIFTY26OCT56100CE"), "liquidity15", intended = 600.0, stop = 510.0)!!.startsWith("refused: 2% risk cap"))
        // h14's capped limit on live: logged only, what the cap would be.
        assertTrue(BossRiskHost.recordsToday(BossRiskHost.Rule.CAPPED).any { it.live && !it.applied && it.why.contains("603.00") })
    }

    // ---- switches, PIN and words --------------------------------------------------------------------------------------------

    @Test fun switchingALimitOffAsksThePinWhenTheGuardWouldButSwitchingOnNever() {
        com.optionslab.app.security.PinLock.setPin("482915".toCharArray())
        com.optionslab.app.testing.BrokerArea.saveKeys("482915")
        assertEquals(BossRiskHost.PIN_LOOSEN, BossRiskHost.setPaper(BossRiskHost.Rule.WEEKLY, false, log = false))
        assertTrue(BossRiskHost.paperOn(BossRiskHost.Rule.WEEKLY))
        assertTrue(BossRiskHost.setPaper(BossRiskHost.Rule.WEEKLY, false, pinConfirmed = true, log = false).endsWith("off."))
        assertTrue(BossRiskHost.setPaper(BossRiskHost.Rule.WEEKLY, true, log = false).endsWith("on."))
        // A rule that is not a limit (h14's capped limit) never asks.
        assertTrue(BossRiskHost.setPaper(BossRiskHost.Rule.CAPPED, false, log = false).endsWith("off."))
        assertEquals("That rule has no live switch.", BossRiskHost.setLive(BossRiskHost.Rule.EXPIRY, true))
        assertEquals("live capped limit: logged only, no switch", "That rule has no live switch.", BossRiskHost.setLive(BossRiskHost.Rule.CAPPED, true))
        assertEquals("That rule has no paper switch.", BossRiskHost.setPaper(BossRiskHost.Rule.SPREAD, true, log = false))
    }

    @Test fun theSpreadCheckOnlyLogsAndTheLinesSayEachRule() {
        BossRiskHost.spreadShadow("Liquidity 15+5", "BANKNIFTY26OCT56000CE", 99.0, 101.0, live = false)
        val rec = BossRiskHost.recordsToday(BossRiskHost.Rule.SPREAD).single()
        assertFalse(rec.applied); assertTrue(rec.why, rec.why.contains("half-spread 1.00%") && rec.why.contains("shadow"))
        BossRiskHost.spreadShadow("Liquidity 15+5", "BANKNIFTY26OCT56000CE", 99.0, 101.0, live = false)
        assertEquals("the same one within 10 minutes is kept once", 1, BossRiskHost.recordsToday(BossRiskHost.Rule.SPREAD).size)
        val all = BossRiskHost.lines().joinToString("\n")
        for (w in listOf("Account stop rule on paper", "Weekly loss stop on paper", "2% risk cap", "opposite bets", "capped limit (h14)",
            "Liquidity expiry-day skip", "spread check (paper shadow, live shadow): today 0 paper applied, 1 logged", "IV at entry (record only): no entry today."))
            assertTrue(all, w in all)
    }

    // ---- IV at entry (record only) ------------------------------------------------------------------------------------------

    @Test fun eachAutomaticEntrysIvIsRecordedAndNeverChangesTheEntry() {
        val c = contract("NIFTY", 25_000, Right.CE, lot = 65)
        // The index on the stream (NIFTY 50's token): the IV is solved; none: recorded as not known.
        KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(256265L, 25_000.0)))
        val r = buy(c, "R72 Breadth Donchian", px = 150.0)
        assertTrue(r.message, r.ok)
        val iv = waitIv(1)
        assertEquals(c.symbol, iv.symbol)
        assertTrue("an ATM call at Rs 150 with six days left has a sensible IV: ${iv.iv}", iv.iv != null && iv.iv!! in 0.03..1.5)
        assertTrue(BossRiskHost.ivLine(), BossRiskHost.ivLine().startsWith("IV at entry (record only): 1 entry today, 1 solved, median"))
        // Boss's own order and an exit are not recorded.
        buy(contract("NIFTY", 25_100, Right.CE), null); sell(c)
        Thread.sleep(300)
        assertEquals(1, BossRiskHost.ivToday().size)
    }

    @Test fun aRestingEntryHasItsIvRecordedWhenItFillsAndALiveEntryFromItsSymbol() {
        KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(256265L, 25_000.0)))
        val c = contract("NIFTY", 25_000, Right.CE, lot = 65)
        BossRiskHost.ivRest("ORD-1", "Hero ₹1,000 ticket", c)
        // A sell, or another order, records nothing; the entry's own fill does.
        BossRiskHost.ivFills(listOf(com.optionslab.engine.sandbox.SandboxEvent.Fill("ORD-2", "T1", c.symbol, "NFO", "BUY", 65, 150.0, "MIS"),
            com.optionslab.engine.sandbox.SandboxEvent.Fill("ORD-1", "T2", c.symbol, "NFO", "SELL", 65, 150.0, "MIS")))
        Thread.sleep(300)
        assertTrue(BossRiskHost.ivToday().isEmpty())
        BossRiskHost.ivFills(listOf(com.optionslab.engine.sandbox.SandboxEvent.Fill("ORD-1", "T3", c.symbol, "NFO", "BUY", 65, 150.0, "MIS")))
        assertEquals("Hero ₹1,000 ticket", waitIv(1).owner)
        // A bot's live entry: read from Zerodha's weekly symbol (record only).
        val exp = Market.today().plusDays(6)
        val month = when (exp.monthValue) { 10 -> "O"; 11 -> "N"; 12 -> "D"; else -> exp.monthValue.toString() }
        BossRiskHost.liveEntryIv("NIFTY%02d%s%02d25000PE".format(exp.year % 100, month, exp.dayOfMonth), "orb", 140.0)
        val live = waitIv(2)
        assertTrue(live.live); assertEquals(140.0, live.premium, 0.0)
        assertTrue("${live.iv}", live.iv != null)
    }

    private fun waitIv(n: Int): BossRiskHost.Iv {
        val until = System.currentTimeMillis() + 5_000
        while (BossRiskHost.ivToday().size < n && System.currentTimeMillis() < until) Thread.sleep(20)
        return BossRiskHost.ivToday()[n - 1]
    }
}
