package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.BrokerArea
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeLiveSources
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.sandbox.Quote
import com.optionslab.ira.LiveCheck
import com.optionslab.ira.LiveCheck.Group
import com.optionslab.ira.LiveCheck.Verdict
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate

/**
 * The Live check ([LiveSelfTest]) against the fake Kite (REST) and a fake price stream, step by step: pass, slow (warn),
 * fail, timeout and market closed. It only ever READS from Zerodha (no order, change, cancel or GTT may reach it, whatever
 * the account holds), its paper trade never reaches the paper book or any result, and nothing it shows carries an amount,
 * a token or an order id.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveSelfTestTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.Watchdog()
    private lateinit var kite: FakeKite
    private lateinit var stream: FakeLiveSources
    private val expiry: LocalDate get() = Market.today().plusDays(9)

    /** The short waits the tests run with (the app's are 10 s windows and timeouts). */
    private val quick = LiveSelfTest.Limits(windowMs = 400, sampleMs = 20, streamTimeoutMs = 600, reconnectTimeoutMs = 1_500)

    @Before fun up() {
        kite = FakeKite()
        kite.keepAlive = true
        stream = FakeLiveSources()
        BrokerArea.phoneIp(null)
        BrokerArea.listNifty(kite, listOf(expiry))
        kite.quote("NSE:NIFTY 50", 24_480.0)
        for (i in kite.instruments) kite.quote("NFO:${i.symbol}", 100.0, 99.95, 100.05)
    }

    @After fun down() {
        stream.close()
        kite.close()
        BrokerArea.phoneIp(null)
        assertEquals("the live check reached the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun keysAndLogin() {
        // Configured = key plus a sealed secret; the seal itself is not what is tested here.
        SecurePrefs.put("kite.apiSecretSealed", "sealed-test-not-real")
        kite.login()
    }

    /** Everything a full pass needs: the session, the static IP, candles of this minute on both feeds. */
    private fun allGood() {
        keysAndLogin()
        StaticIp.registered = "13.235.10.20"
        BrokerArea.phoneIp("13.235.10.20")
        val bar = System.currentTimeMillis() / 60_000 * 60 - 60
        kite.candles = JSONArray().put(JSONArray().put(java.time.Instant.ofEpochSecond(bar).atZone(com.optionslab.engine.IST).toOffsetDateTime().toString())
            .put(24_470.0).put(24_490.0).put(24_460.0).put(24_480.0).put(0).put(0))
        stream.upstox = listOf(Upstox.Bar(bar, 24_470.0, 24_490.0, 24_460.0, 24_480.0, 0, 0))
        val now = System.currentTimeMillis()
        stream.paperAt = now; stream.stopsAt = now
    }

    private fun run(auto: Boolean = false): Pair<List<LiveCheck.Step>, List<LiveCheck.Step>> {
        val seen = java.util.Collections.synchronizedList(ArrayList<LiveCheck.Step>())
        val out = runBlocking { LiveSelfTest.run({ seen += it }, auto = auto, src = stream, limits = quick) }
        return out to seen.toList()
    }

    private fun List<LiveCheck.Step>.of(name: String) = first { it.name == name }

    private fun onlyReads() {
        assertTrue("the live check may only read: ${kite.writes.map { it.method + " " + it.path }}", kite.writes.isEmpty())
        assertTrue("no order or GTT ever reaches Zerodha", kite.placed.isEmpty() && kite.orders.isEmpty() && kite.gtts.isEmpty())
        assertTrue("only GETs and the read-only calculators: ${kite.requests.map { it.method + " " + it.path }.distinct()}",
            kite.requests.all { it.method == "GET" || it.path in setOf("/margins/basket", "/margins/orders") })
    }

    private val account = listOf("Session", "Funds", "Holdings", "GTT triggers", "Quote (NIFTY 50)", "Contract list")
    private val chain = listOf("ATM strike (NIFTY)", "ATM CE and PE quotes", "Lot, tick and freeze quantity")
    private val streamNames = listOf("Connected", "First tick: NIFTY 50", "First tick: NIFTY BANK", "First tick: ATM option",
        "Ticks per second", "Largest gap between ticks", "Stream against a REST quote")
    private val order = listOf("1-lot ATM CE buy (built, not sent)", "Order margin (Zerodha, read-only)", "Basket margin (Zerodha, read-only)",
        "GTT stop and target (built, not placed)")
    private val positions = listOf("Position book (Zerodha)", "Order book (Zerodha)", "Paper book", "Every open position's stop watched")
    private val paper = listOf("Paper buy at the live price", "Paper fill", "Stop and target attached", "Paper position closed")
    private val tail = listOf("Static IP for orders", "Zerodha login", "Zerodha 1-minute candles (NIFTY)", "Upstox public 1-minute candles (NIFTY)", "Dhan",
        "Phone clock against the exchange")
    private val everyName = account + chain + streamNames + order + positions + listOf("Reconnect after a forced drop") + paper + tail

    @Test fun inMarketHoursEveryStepPassesAndNothingIsSent() {
        allGood()
        val (out, seen) = run()
        assertEquals(everyName, out.map { it.name })
        assertEquals("every step is reported as it finishes, in order", out, seen)
        val bad = out.filter { it.verdict != Verdict.PASS && it.name != "Dhan" }
        assertTrue(bad.joinToString("\n") { "${it.name}: ${it.verdict} ${it.detail}" }, bad.isEmpty())
        assertEquals(Verdict.SKIPPED, out.of("Dhan").verdict)
        assertTrue(out.all { it.ms >= 0 })
        assertEquals("24500, nearest expiry $expiry (in 9 days)", out.of("ATM strike (NIFTY)").detail)
        assertEquals("both quoted, with a bid and an ask on each", out.of("ATM CE and PE quotes").detail)
        assertTrue(out.of("Lot, tick and freeze quantity").detail, out.of("Lot, tick and freeze quantity").detail.startsWith("lot 75, tick 0.05, freeze 1800 units (24 lots;"))
        assertTrue(out.of("First tick: ATM option").detail.startsWith("after "))
        assertTrue(out.of("Ticks per second").detail.contains("ticks a second over 0 s"))
        assertEquals("nothing open", out.of("Every open position's stop watched").detail)
        assertTrue(out.of("Reconnect after a forced drop").detail.startsWith("back with a fresh tick after "))
        assertEquals("the stream was dropped once, on purpose, with nothing open", 1, stream.drops)
        assertTrue(out.of("Paper fill").detail, out.of("Paper fill").detail.startsWith("filled on the live price after "))
        assertEquals("flat: both exits cancelled and the position closed", out.of("Paper position closed").detail)
        assertEquals("phone ahead by 120 ms (Zerodha's server clock)", out.of("Phone clock against the exchange").detail)
        // The stream followed the two indices and the ATM CE, and the check let them go at the end.
        val ce = kite.instruments.first { it.strike == 24_500.0 && it.right == "CE" }.token
        assertEquals(listOf(256265L, 260105L, ce), stream.starts.single())
        assertEquals(1, stream.released)
        assertEquals("${out.size - 1} of ${out.size - 1} passed, 1 skipped, slowest: ", LiveCheck.summary(out).substringBefore("slowest: ") + "slowest: ")
        onlyReads()
        // The summary and every step are in the diagnostics diary, and the diagnostics' line carries the summary.
        val diary = Diag.lines()
        assertTrue(diary.any { it.contains("[live-check] summary: ${LiveCheck.summary(out)}") })
        assertEquals(out.size, diary.count { it.contains("[live-check] ") && !it.contains("summary: ") })
        assertEquals("Live check (last run in this session): ${LiveCheck.summary(out)}", LiveSelfTest.diagLine(diary))
        assertEquals(out, LiveSelfTest.last)
    }

    @Test fun thePaperSelfTestTradeIsInNoBookAndNoResult() {
        allGood()
        val before = Paper.state
        val (out, _) = run()
        assertEquals(Verdict.PASS, out.of("Paper position closed").verdict)
        val after = Paper.state
        assertEquals("the paper book is untouched: no order", before.orders, after.orders)
        assertEquals("no trade", before.trades, after.trades)
        assertEquals("no position", before.positions, after.positions)
        assertEquals("the funds and P&L as they were", before.funds.availableBalance, after.funds.availableBalance)
        assertEquals(before.funds.realizedPnl, after.funds.realizedPnl)
        assertTrue("no owner label (no arm's or hand order's result)", runBlocking { Strategies.owners() }.keys.none { it.startsWith("paper:") })
        assertTrue("no paper round trip in the trade book (every result and P&L reads it)", TradeBook.trips(live = false).isEmpty() && TradeBook.fills(live = false).isEmpty())
        assertTrue("no Zerodha order", kite.placed.isEmpty())
        // And the order speed and slippage records saw no paper order from it.
        assertTrue(OrderTiming.card().lines.none { it.contains("paper", ignoreCase = true) && Regex("\\d+ order").containsMatchIn(it) })
    }

    @Test fun outsideMarketHoursTheStreamAndPaperStepsSayMarketClosed() {
        allGood()
        stream.open = false
        val (out, _) = run()
        assertEquals(everyName, out.map { it.name })
        for (n in streamNames + paper + listOf("Reconnect after a forced drop", "Zerodha 1-minute candles (NIFTY)", "Upstox public 1-minute candles (NIFTY)"))
            assertEquals(n, LiveCheck.MARKET_CLOSED to Verdict.CLOSED, out.of(n).detail to out.of(n).verdict)
        assertTrue("nothing was asked of the stream", stream.starts.isEmpty())
        assertEquals(0, stream.drops)
        // The reads and the order readiness still run.
        for (n in account + chain + order + positions) assertEquals(n, Verdict.PASS, out.of(n).verdict)
        assertTrue(LiveCheck.summary(out), LiveCheck.summary(out).contains("14 waiting for market hours"))
        onlyReads()
    }

    @Test fun aSlowConnectWarnsAndTheRestStillPass() {
        allGood()
        stream.live = false
        stream.connectAfterMs = LiveCheck.Budgets.CONNECT_MS + 300
        val out = runBlocking { LiveSelfTest.run(src = stream, limits = quick.copy(streamTimeoutMs = 10_000)) }
        val c = out.of("Connected")
        assertEquals(c.detail, Verdict.WARN, c.verdict)
        assertTrue(c.detail, c.detail.startsWith("live · slow (budget 3000 ms)"))
        assertTrue(c.ms > LiveCheck.Budgets.CONNECT_MS)
        assertEquals(Verdict.PASS, out.of("Ticks per second").verdict)
    }

    @Test fun aStreamThatNeverConnectsFailsOnceAndSkipsTheRest() {
        allGood()
        stream.live = false
        stream.connectAfterMs = null
        val (out, _) = run()
        assertEquals(Verdict.FAIL, out.of("Connected").verdict)
        assertEquals("not live after 600 ms", out.of("Connected").detail)
        for (n in streamNames.drop(1) + "Reconnect after a forced drop") assertEquals(n, "the stream is not connected" to Verdict.SKIPPED, out.of(n).detail to out.of(n).verdict)
        // The paper round trip prices from Zerodha's quote instead, and passes.
        for (n in paper) assertEquals(n, Verdict.PASS, out.of(n).verdict)
    }

    @Test fun anInstrumentThatNeverTicksTimesOut() {
        allGood()
        stream.silent = setOf(kite.instruments.first { it.strike == 24_500.0 && it.right == "CE" }.token)
        val (out, _) = run()
        assertEquals("no tick in 600 ms" to Verdict.FAIL, out.of("First tick: ATM option").let { it.detail to it.verdict })
        assertEquals(Verdict.PASS, out.of("First tick: NIFTY 50").verdict)
        assertEquals(Verdict.PASS, out.of("First tick: NIFTY BANK").verdict)
    }

    @Test fun aZerodhaCallThatNeverAnswersFailsItsStepAndTheNextRuns() {
        allGood()
        kite.hang += "/margins/orders"
        val out = runBlocking { LiveSelfTest.run(src = stream, limits = quick) }
        val m = out.of("Order margin (Zerodha, read-only)")
        assertEquals(m.detail, Verdict.FAIL, m.verdict)
        assertEquals(Verdict.PASS, out.of("Basket margin (Zerodha, read-only)").verdict)
        kite.hang.clear()
    }

    @Test fun aSilentStreamFailsTheRateAndAGap() {
        allGood()
        val seen = java.util.Collections.synchronizedList(ArrayList<LiveCheck.Step>())
        val out = runBlocking {
            // The ticks stop once the first ticks are in: the counting window sees none.
            val r = async(kotlinx.coroutines.Dispatchers.IO) {
                LiveSelfTest.run({ st -> seen += st; if (st.name == "First tick: ATM option") stream.silent = stream.followed.toSet() }, src = stream, limits = quick)
            }
            r.await()
        }
        assertEquals("no ticks in 0 s" to Verdict.FAIL, out.of("Ticks per second").let { it.detail to it.verdict })
        // The whole window was silent: the gap is the window (a pass here only because the test's window is 0.4 s, not 10).
        val gap = Regex("largest gap (\\d+) ms").find(out.of("Largest gap between ticks").detail)!!.groupValues[1].toLong()
        assertTrue("gap $gap", gap >= quick.windowMs - 50)
    }

    @Test fun aStaleStreamPriceIsNeverComparedWithTheRestQuote() {
        allGood()
        // NIFTY's ticks stop after the gap is measured: by the comparison its last tick is older than the limit.
        val out = runBlocking {
            LiveSelfTest.run({ st -> if (st.name == "Largest gap between ticks") { stream.silent = setOf(256265L); Thread.sleep(300) } },
                src = stream, limits = quick.copy(freshTickMs = 100))
        }
        val a = out.of("Stream against a REST quote")
        assertEquals(a.detail, Verdict.FAIL, a.verdict)
        assertTrue(a.detail, a.detail.startsWith("the stream's last NIFTY 50 tick is ") && a.detail.endsWith(" ms old"))
    }

    @Test fun oneInstrumentStallingIsAGapEvenWhileTheOthersTick() {
        allGood()
        // The ATM option stops once its first tick is in; the indices keep ticking every 25 ms.
        val ce = kite.instruments.first { it.strike == 24_500.0 && it.right == "CE" }.token
        val out = runBlocking {
            LiveSelfTest.run({ st -> if (st.name == "First tick: ATM option") stream.silent = setOf(ce) }, src = stream, limits = quick)
        }
        val gap = Regex("largest gap (\\d+) ms").find(out.of("Largest gap between ticks").detail)!!.groupValues[1].toLong()
        assertTrue("the option's silence is the gap: $gap", gap >= quick.windowMs - 50)
        assertEquals(Verdict.PASS, out.of("Ticks per second").verdict)
    }

    @Test fun theReconnectFailsWhenTheStreamDoesNotComeBack() {
        allGood()
        stream.reconnects = false
        val (out, _) = run()
        assertEquals("not back in 1500 ms" to Verdict.FAIL, out.of("Reconnect after a forced drop").let { it.detail to it.verdict })
        stream.live = true
    }

    @Test fun theReconnectFailsWithNoConnectionToDrop() {
        allGood()
        stream.dropWorks = false
        val (out, _) = run()
        assertEquals("no open connection to drop" to Verdict.FAIL, out.of("Reconnect after a forced drop").let { it.detail to it.verdict })
    }

    @Test fun theStreamIsNeverDroppedWhileAPositionIsOpenNorInTheAutomaticRun() {
        allGood()
        kite.position(kite.instruments.first().symbol, 75, 100.0)
        val (out, _) = run()
        assertEquals("a position or order is open, so the stream is not dropped" to Verdict.SKIPPED,
            out.of("Reconnect after a forced drop").let { it.detail to it.verdict })
        kite.flat()
        val (auto, _) = run(auto = true)
        assertEquals("not in the automatic run: only a check you start drops the stream" to Verdict.SKIPPED,
            auto.of("Reconnect after a forced drop").let { it.detail to it.verdict })
        assertEquals(0, stream.drops)
    }

    @Test fun anArmsOwnOpenPaperPositionKeepsTheStreamUp() {
        allGood()
        // Zerodha and the paper account are flat, but an arm's own book holds a position (or could not be read).
        stream.booksOpen = true
        val (out, _) = run()
        assertEquals("an arm holds a position or order (or a book could not be read), so the stream is not dropped" to Verdict.SKIPPED,
            out.of("Reconnect after a forced drop").let { it.detail to it.verdict })
        assertEquals(0, stream.drops)
    }

    @Test fun anOpenLivePositionNotCheckedFor15sWarnsAndFor30sFails() {
        allGood()
        kite.position(kite.instruments.first().symbol, 75, 100.0)
        stream.stopsAt = System.currentTimeMillis() - 15_000
        val late = run().first.of("Every open position's stop watched")
        assertEquals(late.detail, Verdict.WARN, late.verdict)
        assertTrue(late.detail, late.detail.endsWith("1 not checked in the last 15 s"))
        stream.stopsAt = System.currentTimeMillis() - 30_000
        val w = run().first.of("Every open position's stop watched")
        assertEquals(w.detail, Verdict.FAIL, w.verdict)
        assertTrue(w.detail, w.detail.startsWith("1 open (0 paper, 1 live): 0 with a stop, 0 with a target, last checked at most 30 s ago"))
        // Never checked in this process.
        stream.stopsAt = 0L
        assertTrue(run().first.of("Every open position's stop watched").detail.contains("never checked"))
        // Checked just now but with no stop this check can see: a warning (an arm may hold its stop in its own book).
        stream.stopsAt = System.currentTimeMillis()
        val warn = run().first.of("Every open position's stop watched")
        assertEquals(warn.detail, Verdict.WARN, warn.verdict)
        // Outside market hours it waits for them.
        stream.open = false
        assertEquals(Verdict.CLOSED, run().first.of("Every open position's stop watched").verdict)
    }

    @Test fun anOpenPaperPositionWithARestingStopIsWatched() {
        allGood()
        TradeFixtures.paperSettings()
        val upstox = FakeUpstox()
        try {
            TradeFixtures.seed(context, upstox)
            Paper.testSkipFeedChecks = true
            val c = Paper.contractFor("NIFTY", TradeFixtures.nearExpiry, 24_500.0, Right.PE) ?: throw AssertionError("contract not listed")
            runBlocking {
                assertTrue(Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null, Quote(100.0, 99.95, 100.05)).ok)
                assertTrue(Paper.place(c, "SELL", 1, "SL-M", "NRML", null, 70.0, Quote(100.0, 99.95, 100.05)).ok)
            }
            stream.paperAt = System.currentTimeMillis() - 3_000
            val (out, _) = run()
            val w = out.of("Every open position's stop watched")
            assertEquals(w.detail, Verdict.PASS, w.verdict)
            assertTrue(w.detail, Regex("1 open \\(1 paper, 0 live\\): 1 with a stop, 0 with a target, last checked at most [3-6] s ago").matches(w.detail))
            assertEquals("1 open, 1 working", out.of("Paper book").detail)
            assertEquals("a resting paper stop is something open: the stream is not dropped", 0, stream.drops)
            stream.paperAt = System.currentTimeMillis() - 20_000
            assertEquals(Verdict.WARN, run().first.of("Every open position's stop watched").verdict)
            stream.paperAt = System.currentTimeMillis() - 31_000
            assertEquals(Verdict.FAIL, run().first.of("Every open position's stop watched").verdict)
        } finally {
            Paper.testSkipFeedChecks = false
            upstox.close()
        }
    }

    @Test fun aFailedReadDoesNotStopTheRest() {
        allGood()
        kite.down += "/portfolio/holdings"
        val (out, _) = run()
        assertEquals(everyName, out.map { it.name })
        val holdings = out.of("Holdings")
        assertEquals("Kite's own reason is shown", "Gateway timed out" to Verdict.FAIL, holdings.detail to holdings.verdict)
        assertTrue(out.filter { it.name != "Holdings" && it.name != "Dhan" }.joinToString { it.name + " " + it.verdict }, out.filter { it.name != "Holdings" && it.name != "Dhan" }.all { it.verdict == Verdict.PASS })
        onlyReads()
    }

    @Test fun notSetUpSkipsTheAccountAndTouchesNothing() {
        val (out, _) = run()
        assertEquals(listOf("Session", "Static IP for orders", "Zerodha login", "Zerodha 1-minute candles (NIFTY)", "Upstox public 1-minute candles (NIFTY)",
            "Dhan", "Phone clock against the exchange"), out.map { it.name })
        assertEquals("Zerodha is not set up on this phone" to Verdict.FAIL, out[0].detail to out[0].verdict)
        assertEquals("no static IP registered in the app (More → Zerodha → Static IP)", out[1].detail)
        assertEquals(Verdict.FAIL, out.of("Zerodha login").verdict)
        assertEquals(Verdict.SKIPPED, out.of("Zerodha 1-minute candles (NIFTY)").verdict)
        assertEquals("Upstox: no bar today" to Verdict.FAIL, out.of("Upstox public 1-minute candles (NIFTY)").let { it.detail to it.verdict })
        assertTrue("nothing asked of Zerodha", kite.requests.isEmpty())
        assertTrue("nor of the stream", stream.starts.isEmpty())
    }

    @Test fun keysButNoLoginToday() {
        SecurePrefs.put("kite.apiKey", "testkey")
        SecurePrefs.put("kite.apiSecretSealed", "sealed-test-not-real")
        val (out, _) = run()
        assertEquals("not logged in today: log in first", out[0].detail)
        assertTrue(kite.requests.isEmpty())
    }

    @Test fun aSessionEndedAtZerodhaFailsEveryReadWithTheReason() {
        allGood()
        kite.sessionExpired = true
        val (out, _) = run()
        assertTrue(out[0].ok)
        val funds = out.of("Funds")
        assertFalse(funds.ok)
        assertTrue(funds.detail, funds.detail.startsWith("Zerodha session ended"))
        // The session was dropped, so the later reads stop at the app without asking Zerodha again.
        val later = out.filter { it.name in listOf("Holdings", "GTT triggers", "Contract list", "Position book (Zerodha)", "Order book (Zerodha)") }
        assertTrue(later.joinToString { it.detail }, later.none { it.ok })
        assertTrue(later.joinToString { it.detail }, later.all { it.detail == "Log in to Zerodha for today first" })
        assertFalse(Broker.loggedIn)
        onlyReads()
    }

    @Test fun neverWritesEvenWithWorkingOrdersAndGttsAtZerodha() {
        allGood()
        runBlocking {
            kite.nextPlace(outcome = FakeKite.Outcome.OPEN)
            Broker.placeOrder(com.optionslab.engine.Kite.Order(kite.instruments.first().symbol, com.optionslab.engine.Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 1.0), exit = true)
        }
        kite.gtts[5L] = org.json.JSONObject().put("id", 5).put("type", "single").put("status", "active").put("created_at", "2026-01-01 10:00:00")
            .put("condition", org.json.JSONObject().put("exchange", "NFO").put("tradingsymbol", "X").put("trigger_values", org.json.JSONArray().put(1.0)))
            .put("orders", org.json.JSONArray())
        kite.requests.clear()
        val (out, _) = run()
        assertEquals("1 orders today, 1 working", out.of("Order book (Zerodha)").detail)
        assertEquals("1 triggers", out.of("GTT triggers").detail)
        assertTrue(kite.requests.isNotEmpty())
        assertTrue(kite.writes.isEmpty())
        assertEquals("the working order is untouched", "OPEN", kite.orders.values.single().status)
        assertEquals(1, kite.gtts.size)
        assertEquals("a working order is something open: the stream is not dropped", 0, stream.drops)
    }

    @Test fun aShortMarginWarnsWithoutAFigure() {
        allGood()
        kite.cash = 10.0
        val (out, _) = run()
        val b = out.of("Basket margin (Zerodha, read-only)")
        assertEquals("Zerodha priced the basket; the free margin does not cover it" to Verdict.WARN, b.detail to b.verdict)
        assertEquals(Verdict.PASS, out.of("Order margin (Zerodha, read-only)").verdict)
    }

    @Test fun anOrderOverTheValueCapFailsTheBuildWithNoAmountShown() {
        allGood()
        for (i in kite.instruments) kite.quote("NFO:${i.symbol}", 9_000.0, 8_999.95, 9_000.05)
        val (out, _) = run()
        val o = out.of("1-lot ATM CE buy (built, not sent)")
        assertEquals(o.detail, Verdict.FAIL, o.verdict)
        assertTrue(o.detail, o.detail.contains("exceeds"))
        assertFalse(o.detail, Regex("\\d{3,}").containsMatchIn(o.detail.replace("₹…", "")))
        // The margins and the GTT need the built order: they say so, they do not guess one.
        assertEquals("no order built", out.of("Order margin (Zerodha, read-only)").detail.let { if (it.contains("could not be built")) "no order built" else it })
    }

    @Test fun staleFeedsAndAFarClockFail() {
        allGood()
        val old = System.currentTimeMillis() / 1000 - 600
        stream.upstox = listOf(Upstox.Bar(old, 1.0, 1.0, 1.0, 1.0, 0, 0))
        stream.offset = 3_000L to "Zerodha's server clock"
        val (out, _) = run()
        assertEquals(Verdict.FAIL, out.of("Upstox public 1-minute candles (NIFTY)").verdict)
        assertTrue(out.of("Upstox public 1-minute candles (NIFTY)").detail.endsWith("the feed is stale"))
        assertEquals(Verdict.PASS, out.of("Zerodha 1-minute candles (NIFTY)").verdict)
        assertEquals(Verdict.FAIL, out.of("Phone clock against the exchange").verdict)
        stream.offset = null
        assertEquals(Verdict.WARN, run().first.of("Phone clock against the exchange").verdict)
    }

    @Test fun theWrongIpFailsTheStaticIpStepWithBothAddresses() {
        allGood()
        BrokerArea.phoneIp("49.36.1.2")
        val ip = run().first.of("Static IP for orders")
        assertFalse(ip.ok)
        assertTrue(ip.detail, ip.detail.startsWith("Orders would reach Zerodha from 49.36.1.2, not your registered static IP 13.235.10.20"))
        onlyReads()
    }

    @Test fun resultsCarryNoTokenKeyAmountOrOrderId() {
        allGood()
        val (good, _) = run()
        kite.sessionExpired = true
        val (bad, _) = run()
        val text = (good + bad).joinToString("\n") { it.name + " " + it.detail }
        for (secret in listOf("test-token-not-real", "testkey", "sealed-test-not-real", "api.kite.trade", "127.0.0.1", "localhost", "AB1234", "Test User"))
            assertFalse("'$secret' in the live check's output", text.contains(secret))
        assertFalse("no order id", Regex("\\d{9,}").containsMatchIn(text))
        assertFalse("no rupee figure", text.contains("₹") && Regex("₹\\s*\\d").containsMatchIn(text) || text.contains("Rs "))
        for (amount in listOf("500000", "500,000", "40000", "40,000", "100.05", "99.95", "24480", "24,480"))
            assertFalse("'$amount' in the live check's output", text.contains(amount))
    }

    @Test fun onlyOneCheckRunsAtATime() {
        allGood()
        val (first, second) = runBlocking {
            val a = async { LiveSelfTest.run(src = stream, limits = quick) }
            while (!LiveSelfTest.busy) delay(1)
            val b = LiveSelfTest.run(src = stream, limits = quick)
            a.await() to b
        }
        assertTrue(first.isNotEmpty())
        assertTrue("a second check while one runs does nothing", second.isEmpty())
    }

    @Test fun theAutomaticRunIsOffByDefaultAndRunsOnceAt0920() {
        val zone = com.optionslab.engine.IST
        val day = LocalDate.of(2026, 10, 14)    // a Wednesday, a trading day
        try {
            stream.open = false
            LiveSelfTest.sourcesForTest = stream
            Market.testClock = java.time.Clock.fixed(day.atTime(9, 21).atZone(zone).toInstant(), zone)
            assertFalse("off unless Boss turns it on", LiveSelfTest.autoOn())
            LiveSelfTest.autoMaybe()
            BrokerArea.settle(300)
            assertTrue("off: nothing ran", LiveSelfTest.last.isEmpty())
            LiveSelfTest.setAuto(true)
            assertTrue(LiveSelfTest.autoOn())
            // Before 09:20 nothing; at 09:21 it runs once.
            Market.testClock = java.time.Clock.fixed(day.atTime(9, 19).atZone(zone).toInstant(), zone)
            LiveSelfTest.autoMaybe(); BrokerArea.settle(200)
            assertTrue(LiveSelfTest.last.isEmpty())
            Market.testClock = java.time.Clock.fixed(day.atTime(9, 21).atZone(zone).toInstant(), zone)
            // The check runs on its own thread: the watch's worker that asks is never held up by it (a slow feed here).
            stream.open = true
            stream.upstoxDelayMs = 1_500
            val t0 = System.currentTimeMillis()
            LiveSelfTest.autoMaybe()
            assertTrue("autoMaybe returns at once", System.currentTimeMillis() - t0 < 500)
            BrokerArea.await("the automatic run to start") { LiveSelfTest.busy.takeIf { it } }
            val ran = BrokerArea.await("the automatic run") { LiveSelfTest.last.takeIf { it.isNotEmpty() && !LiveSelfTest.busy } }
            assertNotNull(ran.firstOrNull { it.name == "Session" })
            assertTrue(ran.of("Upstox public 1-minute candles (NIFTY)").ms >= 1_500)
            LiveSelfTest.resetForTest(); LiveSelfTest.sourcesForTest = stream
            LiveSelfTest.autoMaybe(); BrokerArea.settle(300)
            assertTrue("once a day", LiveSelfTest.last.isEmpty())
            assertTrue(Diag.lines().any { it.contains("[live-check] the 09:20 automatic run turned on") })
        } finally {
            Market.testClock = null
        }
    }
}
