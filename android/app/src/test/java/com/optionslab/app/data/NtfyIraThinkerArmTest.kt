package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.signals.NtfyParser
import com.optionslab.engine.signals.NtfyRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * 26 Oct (IraThinker review, irathinker-app/REVIEW_2026-10-26.md "Compatibility"): IraThinker's System Feed on the ntfy alerts arm.
 * A mom60 "paper": true signal is bought on paper with the source rule's exits where they are tighter or earlier (its 60-minute
 * hold sells it; its 30% stop is wider than IraAlgo's 20%, so IraAlgo's stays), labelled "IraThinker mom60", never told to the live
 * path, and restored with its exit time after a restart. A strangle is information (read once, never an order); a stock strike off
 * the grid says so; the send time drives the stale rule. Not one order reaches Zerodha.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class NtfyIraThinkerArmTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val ce = "NSE_FO|SP25000CE"
    private val pe = "NSE_FO|SP25000PE"
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")

    private fun at(t: LocalTime, s: Long = 0) { Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(s).atZone(IST).toInstant(), IST) }

    /** The options flat at 121 to [until]; NIFTY flat at 25,010 (ATM 25,000). */
    private fun market(until: LocalTime, px: Double = 121.0) {
        val o = ArrayList<FakeUpstox.Candle>(); val i = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { o += FakeUpstox.Candle(t, px, px, px, px); i += FakeUpstox.Candle(t, 25_010.0, 25_010.0, 25_010.0, 25_010.0); t = t.plusMinutes(1) }
        upstox.minutes[ce] = o; upstox.minutes[pe] = o; upstox.minutes[niftyKey] = i
    }

    private fun listContracts(expiry: LocalDate) = AutomationSupport.contracts(context, listOf(
        Upstox.Contract("NIFTY", expiry, 25_000.0, Right.CE, 65, ce, "NIFTY-SP-25000CE"),
        Upstox.Contract("NIFTY", expiry, 25_000.0, Right.PE, 65, pe, "NIFTY-SP-25000PE")))

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        day = AutomationSupport.tradingDayAt(9, 20).toLocalDate()
        at(LocalTime.of(9, 20))
        listContracts(day.plusDays(6))
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        ArmLive.testHeard = null
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    /** [text] arrives as ntfy message [id], ntfy's time [t] + [s] seconds, and is read a second later. New messages counted. */
    private fun msg(id: String, text: String, t: LocalTime, s: Long = 4): Int {
        at(t, s + 1); market(t.plusMinutes(1))
        val e = NtfyRules.Event(id, day.atTime(t).plusSeconds(s).atZone(IST).toEpochSecond(), "message", text, "signal")
        return runBlocking { NtfyArm.receive(listOf(e)) }
    }
    private fun tick(t: LocalTime, s: Long = 0) { at(t, s); market(t); runBlocking { NtfyArm.tick() } }
    private fun manage(t: LocalTime) { at(t); market(t); runBlocking { NtfyArm.manageOnly() } }
    private fun v() = NtfyArm.view.value
    private fun alert(id: String) = v().alerts.single { it.id == id }
    private fun on() = runBlocking { NtfyArm.setOn(true) }

    /** SystemFeed.mom60Payload's shape, on NIFTY: sent at [sent], its 30% premium stop on a 120 premium, out at [exitBy]. */
    private fun mom60(id: String, sent: LocalDateTime, exitBy: LocalDateTime?, paper: Boolean = true, premiumStop: String = "84",
                      expiry: LocalDate? = null) =
        """{"type":"signal","test":false,"source":"IraThinker","version":51,"strategy":"MOM60","signalId":"$id","tsIST":"$sent:00+05:30",""" +
            """"paper":$paper,"symbol":"NIFTY","yahooSymbol":"^NSEI","side":"BUY_CALL","entry":25010.0,""" +
            """"option":{"strike":25000,"expiry":${expiry?.let { "\"$it\"" } ?: "null"},"kind":"CE","premium":120},"premiumStop":$premiumStop""" +
            (exitBy?.let { ""","exitBy":"$it:00+05:30"""" } ?: "") + ""","dte":6,"riskRs":2340}"""

    @Test fun mom60IsBoughtOnPaperLabelledAndItsHoldSellsIt() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        on()
        assertEquals(1, msg("m1", mom60("MOM60|^NSEI|1", day.atTime(9, 28), day.atTime(10, 28)), LocalTime.of(9, 28)))
        assertEquals(NtfyArm.PENDING, alert("m1").state)
        assertTrue(alert("m1").why, alert("m1").why.contains("the source rule: stop 30%, out by 10:28 · IraThinker mom60 · paper only"))
        tick(LocalTime.of(9, 28), 30)
        val p = v().open.single()
        assertTrue(p.symbol, p.symbol.endsWith("25000CE"))
        // mom60's 30% stop (84.7 on a 121 fill) is WIDER than IraAlgo's 20% (96.8): IraAlgo's stays. Its 60-minute hold is used.
        assertEquals(96.8, p.stop, 1e-9); assertTrue(p.ourStop)
        assertEquals(day.atTime(10, 28), p.exitAt)
        assertEquals("IraThinker mom60", p.strategy); assertTrue(p.paperOnly)
        val why = alert("m1").why
        assertTrue(why, why.contains("out by 10:28"))
        assertTrue(why, why.contains("IraThinker mom60 (paper only; not a proven strategy; research R110: about breakeven after costs on real options)"))
        assertTrue(why, why.contains("the source rule's stop (30%, 84.7) is wider than IraAlgo's own (96.8): IraAlgo's kept"))
        // "paper": true - never told to the live path.
        assertTrue(heard.toString(), heard.none { it.startsWith("entered|") })
        // A restart: the book read again first (positions first) - its exit time and label come back with it.
        NtfyArm.wipe()
        runBlocking { NtfyArm.refresh() }
        val back = v().open.single()
        assertEquals(day.atTime(10, 28), back.exitAt); assertEquals("IraThinker mom60", back.strategy); assertTrue(back.paperOnly)
        // The stop check's own lane (full cadence): still held at 10:27, sold at 10:28 by the source rule's hold.
        manage(LocalTime.of(10, 27))
        assertEquals(1, v().open.size)
        manage(LocalTime.of(10, 28))
        val done = v().closed.single()
        assertEquals("time exit (the source rule's hold)", done.why)
        assertEquals("cancelled", Paper.state.orders.single { it.orderId == p.stopOrderId }.status)
        assertNothingAtZerodha()
    }

    @Test fun aTighterSourceStopIsUsedAndAPlainSignalStillGoesToTheLivePath() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        on()
        // A 10% premium stop (108 of 120): tighter than IraAlgo's 20% - used. Not marked paper: told to the live path as before.
        msg("m1", mom60("MOM60|^NSEI|2", day.atTime(9, 28), null, paper = false, premiumStop = "108"), LocalTime.of(9, 28))
        tick(LocalTime.of(9, 28), 30)
        val p = v().open.single()
        assertEquals(108.9, p.stop, 1e-9); assertFalse(p.ourStop); assertNull(p.exitAt); assertFalse(p.paperOnly)
        assertTrue(alert("m1").why, alert("m1").why.contains("the source rule's stop (10%) used"))
        assertTrue(heard.toString(), heard.any { it == "entered|${LiveMirror.NTFY_ALERTS}|${p.ref}|65|108.9" })
        assertEquals(108.9, Paper.state.orders.single { it.orderId == p.stopOrderId }.triggerPrice!!.toDouble(), 1e-9)
        assertNothingAtZerodha()
    }

    @Test fun aHoldPastTheSquareOffAndOnExpiryDayLeavesTheSquareOffFirst() {
        listContracts(day)
        on()
        msg("e1", mom60("MOM60|^NSEI|3", day.atTime(14, 50), day.atTime(15, 50)), LocalTime.of(14, 50))
        tick(LocalTime.of(14, 50), 30)
        val p = v().open.single()
        assertTrue(p.expiryDay); assertNull("15:50 is after the 15:05 expiry square-off", p.exitAt)
        assertTrue(alert("e1").why, alert("e1").why.contains("the square-off comes first"))
        manage(LocalTime.of(15, 4))
        assertEquals(1, v().open.size)
        tick(LocalTime.of(15, 5))
        assertEquals("square-off", v().closed.single().why)
        assertNothingAtZerodha()
    }

    @Test fun anExitTimeAlreadyPastDuplicatesStaleAndAClosedMarketAreNotTraded() {
        on()
        // Its exitBy passes before the pass decides it: never bought.
        msg("x1", mom60("MOM60|^NSEI|4", day.atTime(9, 28), day.atTime(9, 29)), LocalTime.of(9, 28))
        tick(LocalTime.of(9, 29), 10)
        assertEquals(NtfyArm.NOT_TRADED, alert("x1").state)
        assertTrue(alert("x1").why, alert("x1").why.contains("the source rule's exit time (09:29) has passed"))
        // The same signal posted twice (two ntfy ids): acted on once.
        msg("d1", mom60("MOM60|^NSEI|5", day.atTime(9, 31), day.atTime(10, 31)), LocalTime.of(9, 31))
        msg("d2", mom60("MOM60|^NSEI|5", day.atTime(9, 31), day.atTime(10, 31)), LocalTime.of(9, 31), 20)
        assertTrue(alert("d2").why, alert("d2").why.contains("same signal id"))
        // tsIST is the send time: stamped 09:28, delivered at 09:36 - 8 minutes old, stale (limit 5).
        msg("s1", mom60("MOM60|^NSEI|6", day.atTime(9, 28), day.atTime(10, 28)), LocalTime.of(9, 36))
        assertTrue(alert("s1").why, alert("s1").why.startsWith("stale, not traded: 8 minutes old"))
        // Bad numbers in its exits: refused with the reason, never guessed.
        msg("b1", mom60("MOM60|^NSEI|7", day.atTime(9, 37), day.atTime(10, 37), premiumStop = "\"thirty\""), LocalTime.of(9, 37))
        assertTrue(alert("b1").why, alert("b1").why.contains("premiumStop not understood"))
        // A Sunday: the market is closed.
        val sunday = day.with(TemporalAdjusters.next(DayOfWeek.SUNDAY))
        Market.testClock = Clock.fixed(sunday.atTime(10, 0, 5).atZone(IST).toInstant(), IST)
        runBlocking { NtfyArm.receive(listOf(NtfyRules.Event("w1", sunday.atTime(10, 0).atZone(IST).toEpochSecond(), "message",
            mom60("MOM60|^NSEI|8", sunday.atTime(10, 0), sunday.atTime(11, 0)), "signal"))) }
        assertTrue(alert("w1").why, alert("w1").why.contains("market closed"))
        assertTrue(v().open.isEmpty() && Paper.state.orders.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun aStrangleIsInformationReadOnceNeverAnOrder() {
        on()
        val strangle = """{"type":"signal","test":false,"source":"IraThinker","version":51,"strategy":"STRANGLE","signalId":"STRANGLE|^NSEBANK|$day",""" +
            """"tsIST":"${day.atTime(9, 30)}:05+05:30","paper":true,"symbol":"BANKNIFTY","yahooSymbol":"^NSEBANK","side":"SELL_STRANGLE","entry":56100,""" +
            """"legs":[{"kind":"CE","strike":56300,"premium":310.2},{"kind":"PE","strike":55900,"premium":295.4}],"creditPts":605.6,"creditRs":18168,""" +
            """"breakevenUpper":56905.6,"breakevenLower":55294.4,"expiry":"${day.plusDays(1)}","dte":1,"exitDate":"${day.plusDays(1)}","exitPlan":"SETTLE_AT_EXPIRY"}"""
        assertEquals(1, msg("st", strangle, LocalTime.of(9, 30), 6))
        val a = alert("st")
        assertEquals(NtfyArm.INFO, a.state)
        assertTrue(a.why, a.why.startsWith("information, not traded: IraAlgo only buys options"))
        assertFalse("information never waits for a pass", NtfyArm.wantsPass())
        assertEquals("read once", 0, msg("st", strangle, LocalTime.of(9, 30), 6))
        tick(LocalTime.of(9, 31))
        assertEquals(NtfyArm.INFO, alert("st").state)
        assertTrue(v().open.isEmpty() && Paper.state.orders.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun aStockStrikeOffTheGridIsRefusedWithTheGridAndTheSendersEstimate() {
        val opt = "NSE_FO|INFY1140CE"; val cash = "NSE_EQ|INE009A01021"
        StockOptions.testFetch = { listOf(
            Upstox.Contract("INFY", day.plusDays(2), 1120.0, Right.CE, 400, "NSE_FO|INFY1120CE", "INFY 1120 CE"),
            Upstox.Contract("INFY", day.plusDays(2), 1140.0, Right.CE, 400, opt, "INFY 1140 CE")) to mapOf("INFY" to cash) }
        StockOptions.refreshNow()
        upstox.minutes[cash] = (0 until 20).map { val t = LocalTime.of(9, 15).plusMinutes(it.toLong()); FakeUpstox.Candle(t, 1131.0, 1131.0, 1131.0, 1131.0) }
        on()
        fun infy(id: String, extra: String) = """{"type":"signal","test":false,"source":"IraThinker","version":51,"strategy":"ORB_BREAKOUT","signalId":"$id",""" +
            """"tsIST":"${day.atTime(9, 28)}:00+05:30","symbol":"Infosys","yahooSymbol":"INFY.NS","side":"BUY_CALL","entry":1131,"stop":1120,""" +
            """"target1":1153,"target2":1164,"score":0.6,"option":{"strike":1130,"expiry":"${day.plusDays(2)}","kind":"CE","premium":14.2$extra}}"""
        msg("i1", infy("INFY-1", ""","strikeEstimated":true"""), LocalTime.of(9, 28))
        tick(LocalTime.of(9, 28), 30)
        val why = alert("i1").why
        assertEquals(NtfyArm.NOT_TRADED, alert("i1").state)
        assertTrue(why, why.contains("INFY 1130 CE is not listed for ${day.plusDays(2)} - not on the exchange's strike grid (listed either side: 1120 and 1140)"))
        assertTrue(why, why.contains("the message says this strike is estimated by the sender"))
        msg("i2", infy("INFY-2", ""), LocalTime.of(9, 29))
        tick(LocalTime.of(9, 29), 30)
        assertTrue(alert("i2").why, alert("i2").why.contains("strike grid") && !alert("i2").why.contains("estimated"))
        assertTrue(v().open.isEmpty() && Paper.state.orders.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun theRecordWordsOfASourceStrategy() {
        val b = NtfyParser.Parsed.Buy("NIFTY", Right.CE, strategy = "IraThinker mom60", paper = true)
        assertEquals(" · IraThinker mom60 (paper only; not a proven strategy; research R110: about breakeven after costs on real options)", NtfyArm.strategyWords(b))
        assertEquals(" · IraThinker ORB breakout (not a proven strategy)", NtfyArm.strategyWords(b.copy(strategy = "IraThinker ORB breakout", paper = false)))
        assertEquals(" · paper only · strike estimated by the sender", NtfyArm.strategyWords(b.copy(strategy = null, strikeGuess = true)))
        assertEquals("", NtfyArm.strategyWords(NtfyParser.Parsed.Buy("NIFTY", Right.CE)))
    }
}
