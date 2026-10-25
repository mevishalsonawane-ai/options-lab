package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.risk.TimeMachine
import com.optionslab.ira.TradeManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The time machine on the arm tickets (22 Oct, [TimeMachineHost]): a check from the store's own sessions, its line, the
 * forward log in the encrypted store (read back, settled with the trade's outcome), the premium conversions, an overnight arm
 * noted as such, the diagnostics and Jarvis's words. Shadow only: nothing here touches an order.
 */
class TimeMachineHostTest : RobolectricTest() {
    /** The market clock's day, read in [up] (a test instance is built before the clock rule runs). */
    private lateinit var today: LocalDate

    private lateinit var storeBars: TimeMachineHost.Bars

    /** The store's own read ([TestApp] hands every other test an empty phone instead). */
    @Before fun up() { today = Market.today(); TimeMachineHost.init(context); TimeMachineHost.resetForTest(); storeBars = TimeMachineHost.storeBarsForTest(); TimeMachineHost.bars = storeBars }
    @After fun down() {
        TimeMachineHost.resetForTest()
        assertEquals("the time machine never reaches the internet", emptyList<String>(), com.optionslab.app.testing.NetworkGuard.blocked.toList())
    }

    /** [n] past sessions of 5-minute bars climbing 20 points a bar from 09:15. */
    private fun climbing(n: Int): List<TimeMachine.Session> {
        val out = ArrayList<TimeMachine.Session>()
        var d = today.minusDays(1)
        while (out.size < n) {
            if (d.dayOfWeek.value <= 5) out += TimeMachine.Session(d, (0 until 75).map { i -> val c = 50_000.0 + 20.0 * i; Bar(d.atTime(9, 15).plusMinutes(5L * i), c, c + 10, c - 10, c) })
            d = d.minusDays(1)
        }
        return out
    }

    private fun entry(stop: Double? = 40.0, target: Double? = 40.0, premium: Boolean = true, delta: Double? = null, skip: String? = null, arm: String = "orb") =
        TimeMachineHost.Entry(arm, "ORB", "orb:$arm:P1", "BANKNIFTY", 1, today.atTime(10, 35), stop, target, premium, delta, OrbRules.SQUARE_OFF, skip = skip)

    @Test fun anEntryIsCheckedLinedLoggedAndSettled() {
        TimeMachineHost.bars = TimeMachineHost.Bars { _, _, _ -> climbing(30) to "30 sessions from the test" }
        val r = TimeMachineHost.check(entry())
        assertEquals("edge", r.verdict)
        val line = r.line()
        assertTrue(line, line.startsWith("ORB: Time machine (edge): target first 100%"))
        assertTrue(line, line.contains("premium stops: odds on the underlying move only (at-the-money delta 0.5 assumed)"))
        assertTrue(line, line.contains("30 sessions from the test"))
        assertTrue("the ORB rule re-run on the sessions", line.contains("the rule itself fired on"))
        assertEquals(r, TimeMachineHost.resultOf("orb:orb:P1"))
        assertEquals(line, TimeMachineHost.line("orb:orb:P1"))
        assertEquals(1, TimeMachineHost.latest.value.size)
        // The forward log: in the vault, read back by a fresh process, then settled with the trade's outcome.
        val kept = TimeMachineHost.forwardLog()
        assertEquals(1, kept.size)
        assertEquals("edge", kept[0].verdict); assertNull(kept[0].outcome); assertEquals(today, kept[0].day)
        // The diagnostics and Jarvis (this process's checks, memory).
        assertTrue(TimeMachineHost.diagLine(), TimeMachineHost.diagLine().contains("1 check(s) today") && TimeMachineHost.diagLine().contains("forward log 1 record(s)"))
        val said = TimeMachineHost.jarvisLine(today)
        assertTrue(said, said.startsWith("Time machine today, Boss: ORB edge (target first 100%"))
        assertTrue(said, said.endsWith("It only reports; it never skips or resizes a trade."))
        assertEquals("The time machine checked no entry today, Boss.", TimeMachineHost.jarvisLine(today.minusDays(1)))
        // The timeline's finding.
        assertTrue(SmartWorkers.bus.recent(System.currentTimeMillis()).any { it.who == "time machine" && it.words.contains("verdict edge") })
        // A fresh process (memory cleared): the log is read back from the encrypted file, then settled with the trade's outcome.
        TimeMachineHost.resetForTest()
        assertEquals("read back from the encrypted file", 1, TimeMachineHost.forwardLog().size)
        TimeMachineHost.settle("orb:orb:P1", "target")
        var waited = 0
        while (TimeMachineHost.forwardLog()[0].outcome == null && waited++ < 100) Thread.sleep(20)
        assertEquals("target", TimeMachineHost.forwardLog()[0].outcome)
    }

    @Test fun noBarsFewSessionsAndAnOvernightArmAreSaidHonestly() {
        TimeMachineHost.bars = TimeMachineHost.Bars { _, _, _ -> emptyList<TimeMachine.Session>() to "no bars" }
        val none = TimeMachineHost.check(entry())
        assertEquals("unknown", none.verdict)
        assertTrue(none.line(), none.line().contains("no past sessions of the underlying on the phone"))
        TimeMachineHost.bars = TimeMachineHost.Bars { _, _, _ -> climbing(4) to "4 sessions" }
        val few = TimeMachineHost.check(entry(arm = "orb_fresh"))
        assertEquals("unknown", few.verdict)
        assertTrue(few.line(), few.line().contains("only 4 past sessions"))
        val night = TimeMachineHost.check(entry(skip = "held overnight by design: no intraday replay", arm = "night_r3"))
        assertNull(night.odds)
        assertEquals("ORB: Time machine (unknown): held overnight by design: no intraday replay", night.line())
        assertEquals(3, TimeMachineHost.forwardLog().size)
    }

    /** The hook queues and returns; the check runs on the time machine's own low-priority thread, and a test can wait for it. */
    @Test fun afterEntryRunsOnItsOwnThreadAndTheTestWaitsForIt() {
        var thread: String? = null
        TimeMachineHost.bars = TimeMachineHost.Bars { _, _, _ -> thread = Thread.currentThread().name; climbing(30) to "30 sessions from the test" }
        TimeMachineHost.afterEntry(entry(arm = "orb_bg"))
        assertTrue("finished", TimeMachineHost.awaitIdleForTest())
        assertTrue("$thread", thread?.startsWith("time-machine") == true)
        assertEquals("edge", TimeMachineHost.resultOf("orb:orb_bg:P1")?.verdict)
    }

    /**
     * 25 Oct (CI): the time machine's finding on the bus is words only. It must never bring the trade manager's look forward -
     * a look reads the option's minutes from the feed (that read, running on after a test closed its fake feed, reached the
     * real host and ate the next test's scripted prices). A directional finding from anyone else still does.
     */
    @Test fun aTimeMachineFindingNeverBringsTheManagersLookForward() {
        SmartWorkers.resetForTest(); TradeManagerHost.resetForTest()
        val t0 = TradeManagerHost.nowMs()
        val looks = java.util.concurrent.atomic.AtomicInteger()
        TradeManagerHost.testSnapshot = { _, at -> looks.incrementAndGet(); TradeManager.Snapshot(at, 120.0) }
        TradeManagerHost.testBars = { emptyList() }
        TradeManagerHost.attach(TradeManagerHost.Registration(TradeManager.ManagedTrade("pine:liq", "orb:liq:P9", "Liquidity", "BANKNIFTY", 1,
            "BANKNIFTY25OCT54000CE", 300.0, 30, 240.0, 420.0, TradeManager.Caps(t0 + 3 * 3_600_000L), TradeManager.Account.PAPER, t0, 0.5), premium = { 310.0 }))
        TimeMachineHost.bars = TimeMachineHost.Bars { _, _, _ -> climbing(30) to "30 sessions from the test" }
        TimeMachineHost.check(entry(arm = "liquidity").copy(ref = "orb:liq:P9"))
        assertTrue(SmartWorkers.bus.recent(System.currentTimeMillis(), "BANKNIFTY").any { it.who == TimeMachineHost.WHO })
        Thread.sleep(400)
        assertEquals("no look brought forward by the time machine's words", 0, looks.get())
        // The control: a directional finding from another worker on the same index does wake it.
        SmartWorkers.bus.post(com.optionslab.ira.Findings.Finding("test worker", com.optionslab.ira.Findings.Kind.OTHER, "BANKNIFTY", 1, null, 60, System.currentTimeMillis()))
        var waited = 0
        while (looks.get() == 0 && waited++ < 200) Thread.sleep(20)
        assertEquals("a worker's finding brings the look forward", 1, looks.get())
        TradeManagerHost.resetForTest()
    }

    @Test fun premiumPointsAreConvertedWithTheDeltaOrSaidAsAssumed() {
        val (s0, t0, n0) = TimeMachineHost.points(entry(40.0, 80.0))
        assertEquals(80.0, s0!!, 1e-9); assertEquals(160.0, t0!!, 1e-9)
        assertTrue(n0!!.contains("delta 0.5 assumed"))
        val (s1, t1, n1) = TimeMachineHost.points(entry(40.0, 80.0, delta = 0.4))
        assertEquals(100.0, s1!!, 1e-9); assertEquals(200.0, t1!!, 1e-9)
        assertEquals("premium stop and target converted at delta 0.40", n1)
        // Underlying points pass through; no target: R 1 assumed; no stop: nothing to convert.
        assertEquals(Triple(30.0, 60.0, null), TimeMachineHost.points(entry(30.0, 60.0, premium = false)))
        val (s3, t3, n3) = TimeMachineHost.points(entry(30.0, null, premium = false))
        assertEquals(30.0, s3!!, 1e-9); assertEquals(30.0, t3!!, 1e-9); assertEquals("no target: R 1 assumed (target set to the stop)", n3)
        assertNull(TimeMachineHost.points(entry(null, null)).first)
    }

    @Test fun theStoresOwnSessionsAreReadAndFolded() {
        Store.init(context)
        // Twelve harvested 1-minute index days, the newest yesterday (weekdays), and a stale one dated today (never used).
        var d = today.minusDays(1); var n = 0
        while (n < 12) {
            if (d.dayOfWeek.value <= 5) {
                val minutes = (0 until 375).map { 9 * 60 + 15 + it }.toIntArray()
                val close = DoubleArray(375) { 50_000.0 + 4.0 * it }
                Store.upsertDay("BANKNIFTY", d, listOf(Series(null, 0.0, Right.IX, 0, minutes, close, close, DoubleArray(375) { close[it] + 2 }, DoubleArray(375) { close[it] - 2 }, null, LongArray(375))))
                n++
            }
            d = d.minusDays(1)
        }
        // The bundled BANKNIFTY partitions (the app's assets) count too: at least the twelve, the newest of them these.
        val (sessions, source) = storeBars.sessions("BANKNIFTY", today, 5)
        assertTrue("${sessions.size}", sessions.size >= 12)
        assertTrue(source, source.contains("sessions from the phone's harvested BANKNIFTY bars"))
        val mine = sessions.takeLast(12)
        assertEquals(75, mine[0].bars.size)
        assertEquals(LocalTime.of(9, 15), mine[0].bars[0].start.toLocalTime())
        assertEquals(50_000.0, mine[0].bars[0].open, 1e-9); assertEquals(50_016.0, mine[0].bars[0].close, 1e-9)
        assertEquals(50_018.0, mine[0].bars[0].high, 1e-9); assertEquals(49_998.0, mine[0].bars[0].low, 1e-9)
        assertTrue(sessions.all { it.date.isBefore(today) })
        TimeMachineHost.bars = TimeMachineHost.Bars { _, _, _ -> mine to "12 sessions" }
        val r = TimeMachineHost.check(entry())
        assertEquals(TimeMachine.Verdict.EDGE, r.odds!!.verdict)
        assertEquals(12, r.odds!!.sessions)
        TimeMachineHost.bars = storeBars
        // An underlying with nothing kept: the history keeper has nothing either.
        val (none, why) = storeBars.sessions("MIDCPNIFTY", today, 5)
        assertTrue(none.isEmpty()); assertEquals("no sessions saved yet on this phone for MIDCPNIFTY", why)
        // Said so on the ticket, and nothing else done (no odds, no verdict).
        val said = TimeMachineHost.check(entry(arm = "orb_midcp").copy(underlying = "MIDCPNIFTY"))
        assertEquals("unknown", said.verdict)
        assertTrue(said.line(), said.line().contains("no past sessions of the underlying on the phone") && said.line().contains("no sessions saved yet on this phone for MIDCPNIFTY"))
        // The fold itself.
        val ones = (0 until 10).map { i -> Bar(today.atTime(9, 15).plusMinutes(i.toLong()), 1.0 + i, 2.0 + i, 0.5 + i, 1.5 + i) }
        val fives = TimeMachineHost.fold(ones, 5)
        assertEquals(2, fives.size)
        assertEquals(Bar(today.atTime(9, 15), 1.0, 6.0, 0.5, 5.5), fives[0])
        assertEquals(ones, TimeMachineHost.fold(ones.shuffled(), 1))
    }

    @Test fun theLogRoundTripsAndABadFileIsNotTrusted() {
        val rec = TimeMachineHost.Record(LocalDate.of(2026, 10, 22), "orb", "orb:orb:1", "edge", "odds", null, false)
        val back = TimeMachineHost.decode(TimeMachineHost.encode(listOf(rec, rec.copy(outcome = "stop", live = true))))
        assertEquals(2, back.size); assertEquals(rec, back[0]); assertEquals("stop", back[1].outcome); assertTrue(back[1].live)
        assertTrue(TimeMachineHost.decode("not json").isEmpty())
        assertTrue(TimeMachineHost.decode(null).isEmpty())
        assertNotNull(TimeMachineHost.encode(emptyList()))
    }
}
