package com.optionslab.ira

import com.optionslab.engine.KiteTicks
import com.optionslab.ira.MoveEvents.Kind
import com.optionslab.ira.MoveEvents.Signal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The big-move recorder's pure parts ([MoveEvents]): the normal move and the 4.7× trigger, merging, the random controls,
 * the ring buffer, the order-flow tracker's new recorded fields and its bounded book, the files' formats read back, the
 * rotation, the summary ("what came first") and Jarvis's question.
 */
class MoveEventsTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun sec(h: Int, m: Int, s: Int = 0, day: LocalDate = LocalDate.of(2026, 10, 9)) =
        LocalDateTime.of(day.year, day.month, day.dayOfMonth, h, m, s).atZone(ist).toEpochSecond()
    /** Session minute [idx]'s start (0 = 09:15). */
    private fun minute(idx: Int, day: LocalDate = LocalDate.of(2026, 10, 9)) = sec(9, 15, day = day) + idx * 60L

    // ---- the normal move ----

    @Test fun theNormalMoveIsTheTablesScaledByVix() {
        assertEquals(0, MoveEvents.minuteIndex(sec(9, 15)))
        assertEquals(77, MoveEvents.minuteIndex(sec(10, 32, 59)))
        assertEquals(-15, MoveEvents.minuteIndex(sec(9, 0)))
        assertEquals("10:32", MoveEvents.hhmm(sec(10, 32, 40)))
        assertEquals(LocalDate.of(2026, 10, 9), MoveEvents.date(sec(9, 15)))
        assertEquals(5.06, MoveEvents.normalBp("NIFTY", 0, null)!!, 1e-9)
        assertEquals(6.35, MoveEvents.normalBp("BANKNIFTY", 0, null)!!, 1e-9)
        // After the table's last minute (15:29): its last value (the futures trade to 15:40).
        assertEquals(2.41, MoveEvents.normalBp("NIFTY", 380, null)!!, 1e-9)
        assertEquals(MoveEvents.normalBp("NIFTY", 374, null), MoveEvents.normalBp("NIFTY", 900, null))
        // Today's VIX against the table's: twice the VIX, twice the normal; the scale is kept within 0.5-3.
        assertEquals(2 * 5.06, MoveEvents.normalBp("NIFTY", 0, 2 * MoveEvents.VIX_REF)!!, 1e-9)
        assertEquals(0.5 * 5.06, MoveEvents.normalBp("NIFTY", 0, 1.0)!!, 1e-9)
        assertEquals(5.06, MoveEvents.normalBp("NIFTY", 0, Double.NaN)!!, 1e-9)
        assertNull(MoveEvents.normalBp("FINNIFTY", 10, null)); assertNull(MoveEvents.normalBp("NIFTY", -1, null))
        // The open is busier than midday (the U-shape the table carries).
        assertTrue(MoveEvents.normalBp("BANKNIFTY", 1, null)!! > 2 * MoveEvents.normalBp("BANKNIFTY", 200, null)!!)
    }

    // ---- the trigger and merging ----

    /** Feeds [name]'s minutes [from]..[to] with each one's return in bp ([bp]) from [close]; returns the signals with their minute. */
    private fun feed(d: MoveEvents.Detector, name: String, from: Int, to: Int, close: DoubleArray, vix: Double? = null,
                     day: LocalDate = LocalDate.of(2026, 10, 9), bp: (Int) -> Double): List<Pair<Int, Signal>> {
        val out = ArrayList<Pair<Int, Signal>>()
        for (i in from..to) {
            close[0] *= exp(bp(i) / 1e4)
            d.onMinute(name, minute(i, day), close[0], vix).forEach { out += i to it }
        }
        return out
    }

    private fun quiet(i: Int) = if (i % 2 == 0) 0.6 else -0.6

    /** A big move's signal (a start that is not a control, or a merge). */
    private fun isBig(s: Signal) = (s is Signal.Start && s.t.kind != Kind.CONTROL) || s is Signal.Merge

    @Test fun aCandleOf4Point7TimesItsNormalStartsAnEventAndTheFirstMinuteNever() {
        val d = MoveEvents.Detector(java.util.Random(7))
        val c = doubleArrayOf(25_000.0)
        // The 09:15 candle has no minute before it today: never a trigger, however big.
        val s0 = feed(d, "NIFTY", 0, 0, c) { 80.0 }
        assertTrue(s0.isEmpty())
        val sig = feed(d, "NIFTY", 1, 120, c) { i -> if (i == 77) 12.0 else quiet(i) }.filter { isBig(it.second) }
        assertEquals(1, sig.size, "$sig")
        val t = (sig.single().second as Signal.Start).t
        assertEquals(77, sig.single().first)
        assertEquals(Kind.BIG_UP, t.kind)
        assertEquals(minute(77), t.startSec)
        assertEquals(12.0, t.retBp, 1e-6)
        assertEquals(12.0 / MoveEvents.normalBp("NIFTY", 77, null)!!, t.x, 1e-6)
        assertTrue(t.x >= MoveEvents.N)
        assertEquals("2026-10-09_NIFTY_1032_big_up", t.id)
        assertEquals("2026-10-09_NIFTY_1032_big_up.csv.gz", MoveEvents.fileName(t))
        assertEquals(t.startSec - 300, t.fromSec); assertEquals(t.startSec + 360, t.endSec)
        assertEquals(1, t.dir)
        // Just under 4.7×: nothing. A down candle: big_down. VIX doubles the normal: the same candle is no longer big.
        val d2 = MoveEvents.Detector(java.util.Random(7))
        val n77 = MoveEvents.normalBp("BANKNIFTY", 77, null)!!
        val c2 = doubleArrayOf(55_000.0)
        val under = feed(d2, "BANKNIFTY", 1, 100, c2) { i -> if (i == 77) -(4.6 * n77) else quiet(i) }.filter { isBig(it.second) }
        assertTrue(under.isEmpty(), "$under")
        val down = feed(d2, "BANKNIFTY", 101, 130, c2) { i -> if (i == 110) -40.0 else quiet(i) }.map { it.second }.filterIsInstance<Signal.Start>().filter { it.t.kind != Kind.CONTROL }
        assertEquals(Kind.BIG_DOWN, down.single().t.kind)
        assertEquals(-1, down.single().t.dir)
        val d3 = MoveEvents.Detector(java.util.Random(7))
        val vixed = feed(d3, "NIFTY", 1, 100, doubleArrayOf(25_000.0), vix = 2 * MoveEvents.VIX_REF) { i -> if (i == 77) 12.0 else quiet(i) }
            .filter { isBig(it.second) }
        assertTrue(vixed.isEmpty(), "VIX at twice the table's: 12 bp is about 3x, not big")
        // Another index, a gap of a minute, or a bad price: nothing.
        assertTrue(d.onMinute("FINNIFTY", minute(130), 20_000.0, null).isEmpty())
        assertTrue(d.onMinute("NIFTY", minute(135), c[0] * 1.01, null).isEmpty(), "after a missing minute there is no return")
        assertTrue(d.onMinute("NIFTY", minute(136), 0.0, null).isEmpty())
    }

    @Test fun triggersUnderTenMinutesApartMergeIntoOneEvent() {
        val d = MoveEvents.Detector(java.util.Random(3))
        val bigs = setOf(76, 80, 89, 100)
        val sig = feed(d, "BANKNIFTY", 1, 140, doubleArrayOf(55_000.0)) { i -> if (i in bigs) (if (i % 2 == 0) 25.0 else -25.0) else quiet(i) }
            .filter { isBig(it.second) }
        // 76 starts; 80 (4 min later) and 89 (9 after 80: the chain) merge; 100 (11 after 89) starts again.
        assertEquals(listOf(76, 80, 89, 100), sig.map { it.first })
        assertTrue(sig[0].second is Signal.Start && sig[3].second is Signal.Start)
        val m1 = sig[1].second as Signal.Merge; val m2 = sig[2].second as Signal.Merge
        assertEquals((sig[0].second as Signal.Start).t, m1.into)
        assertEquals((sig[0].second as Signal.Start).t, m2.into)
        assertEquals(minute(80), m1.startSec)
        assertTrue(m2.retBp < 0 && m2.x >= MoveEvents.N)
        // A new day starts afresh: the same minute's candle is a new event.
        val next = feed(d, "BANKNIFTY", 1, 80, doubleArrayOf(55_000.0), day = LocalDate.of(2026, 10, 12)) { i -> if (i == 77) 25.0 else quiet(i) }
            .map { it.second }.filterIsInstance<Signal.Start>().filter { it.t.kind != Kind.CONTROL }
        assertEquals(1, next.size)
        assertEquals(LocalDate.of(2026, 10, 12), MoveEvents.date(next.single().t.startSec))
    }

    // ---- the controls ----

    @Test fun twoRandomQuietControlsADayPerIndex() {
        for (seed in 1L..20L) {
            val d = MoveEvents.Detector(java.util.Random(seed))
            val sig = feed(d, "NIFTY", 1, 384, doubleArrayOf(25_000.0)) { quiet(it) } + feed(d, "BANKNIFTY", 1, 384, doubleArrayOf(55_000.0)) { quiet(it) }
            val ctl = sig.map { it.second }.filterIsInstance<Signal.Start>().map { it.t }
            assertTrue(ctl.all { it.kind == Kind.CONTROL })
            for (n in listOf("NIFTY", "BANKNIFTY")) {
                val mine = ctl.filter { it.name == n }
                assertEquals(2, mine.size, "seed $seed $n: $mine")
                assertEquals(2, mine.map { it.startSec }.distinct().size)
                for (t in mine) assertTrue(MoveEvents.minuteIndex(t.startSec) in MoveEvents.CONTROL_FROM..MoveEvents.CONTROL_TO)
                assertTrue(mine.all { it.id.endsWith("_control") })
            }
        }
        // Random: across seeds the minutes differ (not one fixed time).
        val firsts = (1L..20L).map { seed -> MoveEvents.Detector(java.util.Random(seed)).also { it.onMinute("NIFTY", minute(1), 25_000.0, null) }.candidates("NIFTY").first() }
        assertTrue(firsts.distinct().size >= 10, "$firsts")
    }

    @Test fun aControlNeedsThirtyQuietMinutesBeforeAndTenAfterElseAnotherIsDrawn() {
        val d = MoveEvents.Detector(java.util.Random(11))
        val c = doubleArrayOf(25_000.0)
        d.onMinute("NIFTY", minute(1), c[0], null)
        val first = d.candidates("NIFTY").first()
        // A top-5% (but not big) minute 5 minutes before the first candidate: that minute is not a control.
        val n = { i: Int -> MoveEvents.normalBp("NIFTY", i, null)!! }
        val busy = first - 5
        val s1 = feed(d, "NIFTY", 2, first, c) { i -> if (i == busy) 3.5 * n(i) else quiet(i) }
        assertTrue(s1.none { it.first == first }, "not quiet before: $s1")
        assertTrue(d.candidates("NIFTY").all { it > first }, "${d.candidates("NIFTY")}")
        // Now let a control start, then a top-5% minute 3 minutes after it: dropped, and another drawn.
        var i = first + 1
        val s2 = ArrayList<Pair<Int, Signal>>()
        while (s2.none { it.second is Signal.Start } && i <= MoveEvents.CONTROL_TO) { s2 += feed(d, "NIFTY", i, i, c) { quiet(it) }; i++ }
        val started = s2.map { it.second }.filterIsInstance<Signal.Start>().single().t
        val next = MoveEvents.minuteIndex(started.startSec)
        assertTrue(next - busy > MoveEvents.QUIET_BEFORE_MIN, "the first quiet candidate after the busy minute: $next")
        val s3 = feed(d, "NIFTY", next + 1, next + 3, c) { i2 -> if (i2 == next + 3) -3.5 * n(i2) else quiet(i2) }
        val drop = s3.map { it.second }.filterIsInstance<Signal.Drop>().single { it.t == started }
        assertEquals(Kind.CONTROL, drop.t.kind)
        // Another minute is drawn in its place (after the busy one), and the day never ends with more than two.
        val after = d.candidates("NIFTY")
        assertTrue((after.isNotEmpty() && after.all { it > next + 3 }) || next + 3 >= MoveEvents.CONTROL_TO, "$after")
        val rest = feed(d, "NIFTY", next + 4, 384, c) { quiet(it) }
        val all = (s1 + s2 + s3 + rest).map { it.second }
        val starts = all.filterIsInstance<Signal.Start>().map { it.t }
        val drops = all.filterIsInstance<Signal.Drop>().map { it.t }
        assertTrue(starts.size - drops.size in 0..2, "starts $starts drops $drops")
        assertTrue(drops.all { it in starts })
    }

    // ---- the ring buffer ----

    @Test fun theRingKeepsTheLastThirtyMinutesInFixedMemory() {
        val r = MoveEvents.Ring<Double>(1800)
        assertNull(r.get(5)); assertTrue(r.range(0, 10).isEmpty()); assertNull(r.at(10))
        for (s in 0L until 4000L) r.put(s, s.toDouble())
        assertEquals(3999.0, r.get(3999)); assertEquals(2200.0, r.get(2200))
        assertNull(r.get(2199), "older than 30 minutes is gone"); assertNull(r.get(4000))
        assertEquals((2200L..2205L).toList(), r.range(2190, 2205).map { it.first })
        assertEquals(1800, r.range(0, 10_000).size)
        // A later value in the same second replaces it; one older than the ring holds is ignored.
        r.put(3999, -1.0); assertEquals(-1.0, r.get(3999))
        r.put(100, 5.0); assertNull(r.get(100)); assertEquals(3999L, r.newest)
        // A gap: at() looks back to the last print.
        r.put(4010, 7.0)
        assertEquals(7.0, r.at(4012)); assertEquals(-1.0, r.at(4005)); assertNull(r.at(4005, maxBack = 2))
        assertNull(r.get(2205), "the newest moved on: the oldest seconds left")
    }

    // ---- the tracker's recorded fields and its bounded book ----

    private fun lv(p: Double, q: Long, o: Int) = KiteTicks.Level(p, q, o)
    private fun quote(ms: Long, last: Double, vol: Long, bid: Double, ask: Double, bq: Long = 100, aq: Long = 120) = OrderFlow.Quote(
        ms, last, vol, 5_000, bid, bq, ask, aq, bq + 300, aq + 330, 9_000, 7_000,
        listOf(lv(bid, bq, 3), lv(bid - 0.05, 100, 2), lv(bid - 0.1, 100, 2), lv(bid - 0.15, 50, 1), lv(bid - 0.2, 50, 1)),
        listOf(lv(ask, aq, 4), lv(ask + 0.05, 110, 2), lv(ask + 0.1, 100, 2), lv(ask + 0.15, 60, 1), lv(ask + 0.2, 60, 1)))

    @Test fun theTrackerRecordsTheSecondsTradesAndBookAndKeepsBooksOnlyForTheLastSevenMinutes() {
        val t = OrderFlow.Tracker(1L, "BANKNIFTY", OrderFlow.Role.FUTURE)
        val t0 = sec(10, 0) * 1000
        t.offer(quote(t0, 100.0, 1_000, 99.95, 100.05))
        // Two prints in the second: 30 at the offer (a buy by both rules), then 50 lower at the bid (a sell).
        t.offer(quote(t0 + 300, 100.05, 1_030, 100.0, 100.1, bq = 140))
        t.offer(quote(t0 + 600, 99.95, 1_080, 99.95, 100.05, aq = 90))
        t.offer(quote(t0 + 1_000, 99.95, 1_080, 99.95, 100.05))
        val b = t.bars().first()
        assertEquals(100.05, b.high, 1e-9); assertEquals(99.95, b.low, 1e-9)
        assertEquals(2, b.prints); assertEquals(50L, b.maxPrint)
        assertEquals(30L, b.qBuy); assertEquals(50L, b.qSell); assertEquals(b.qBuy - b.qSell, b.qDelta)
        assertEquals(99.95, b.bid, 1e-9); assertEquals(100.05, b.ask, 1e-9); assertEquals(100L, b.bidQty); assertEquals(90L, b.askQty)
        assertEquals(400L, b.bid5); assertEquals(420L, b.ask5); assertEquals(9_000L, b.tbq); assertEquals(7_000L, b.tsq)
        val k = assertNotNull(b.book)
        assertEquals(99.95, k.bidPx(0), 1e-9); assertEquals(100L, k.bidQty(0)); assertEquals(3, k.bidOrders(0))
        assertEquals(100.25, k.askPx(4), 1e-9); assertEquals(60L, k.askQty(4)); assertEquals(1, k.askOrders(4))
        assertTrue(b.mofi != 0.0)
        // 600 more seconds: the book is kept on the last BOOK_SEC bars only; every bar stays (30 minutes).
        for (s in 2..600) t.offer(quote(t0 + s * 1000L, 100.0, 1_080L + s, 99.95, 100.05))
        val bars = t.bars()
        val withBook = bars.count { it.book != null }
        assertTrue(withBook in OrderFlow.BOOK_SEC..OrderFlow.BOOK_SEC + 1, "$withBook")
        assertTrue(bars.takeLast(OrderFlow.BOOK_SEC).all { it.book != null })
        assertNull(bars.first().book)
        assertEquals(601, bars.size, "600 closed seconds and the one being built")
        // The per-instrument cursor of the board: a late instrument's seconds are not skipped.
        val board = OrderFlow.Board()
        board.layout(listOf(OrderFlow.Board.Entry(1, "NIFTY", OrderFlow.Role.FUTURE), OrderFlow.Board.Entry(2, "NIFTY", OrderFlow.Role.REC_CE, 25_000.0)))
        assertTrue(board.closedAfter { 0L }.isEmpty())
        assertTrue(board.window(0) { true }.isEmpty())
    }

    @Test fun theRecorderOnlyOptionsAreNeverPartOfTheRead() {
        val b = OrderFlow.Board()
        b.layout(listOf(OrderFlow.Board.Entry(1, "NIFTY", OrderFlow.Role.FUTURE), OrderFlow.Board.Entry(2, "NIFTY", OrderFlow.Role.REC_CE, 25_000.0),
            OrderFlow.Board.Entry(3, "NIFTY", OrderFlow.Role.REC_PE, 25_000.0)))
        val t0 = sec(11, 0) * 1000
        fun tick(token: Long, last: Double, vol: Long, ms: Long) = KiteTicks.Tick(token, last, volume = vol, oi = 10,
            buyQty = 900, sellQty = 700, depth = KiteTicks.Depth(listOf(lv(last - 0.05, 100, 2)), listOf(lv(last + 0.05, 100, 2))))
        for (s in 0..200) {
            b.offer(tick(1, 25_000.0 + s * 0.05, 1_000L + s * 10, t0 + s * 1000), t0 + s * 1000)
            b.offer(tick(2, 100.0 + s, 1_000L + s * 50, t0 + s * 1000), t0 + s * 1000)
            b.offer(tick(3, 100.0 - s * 0.1, 1_000L + s * 5, t0 + s * 1000), t0 + s * 1000)
        }
        val r = assertNotNull(b.read("NIFTY", t0 + 200_000))
        assertTrue(r.optionNet.isNaN(), "the recorder's options never enter the read: ${r.optionNet}")
        // ...but their seconds are recorded.
        val rows = b.window(sec(11, 1)) { it.role == OrderFlow.Role.REC_CE }
        assertTrue(rows.size > 100 && rows.all { it.first.token == 2L })
    }

    // ---- the files ----

    private fun bar(s: Long, book: Boolean = true) = OrderFlow.Bar(s, 55_000.05, 55_000.1, 12.5, 30, 50, 80, 0.1234, -0.25,
        OrderFlow.ratio(9_000, 7_000)!!, 1_000_000, -20, 0.5, 40, 0, 7, 1, Double.NaN, 85.0,
        high = 55_000.1, low = 54_999.95, bid = 55_000.0, ask = 55_000.1, bidQty = 300, askQty = 120, bid5 = 900, ask5 = 700,
        qBuy = 30, qSell = 50, prints = 3, maxPrint = 40, mofi = -15.25, tbq = 9_000, tsq = 7_000,
        book = if (!book) null else OrderFlow.Book.of(
            listOf(lv(55_000.0, 300, 4), lv(54_999.9, 200, 3), lv(54_999.8, 150, 2), lv(54_999.7, 150, 2), lv(54_999.6, 100, 1)),
            listOf(lv(55_000.1, 120, 2), lv(55_000.2, 180, 3), lv(55_000.3, 200, 2), lv(55_000.4, 100, 1), lv(55_000.5, 100, 1))))

    @Test fun anSLineReadsBackExactly() {
        val s0 = sec(10, 31)
        for (withBook in listOf(true, false)) {
            val r = MoveEvents.Row(13_000_001, "BANKNIFTY", "FUTURE", 0.0, "2026-10-28", bar(s0 + 7, withBook), oiChange = 1_500, gap = 2)
            val line = MoveEvents.sLine(7, r)
            assertEquals(MoveEvents.COLUMNS.split(',').size, line.split(',').size, line)
            val (rel, back) = assertNotNull(MoveEvents.parseS(line))
            assertEquals(7L, rel)
            assertEquals(r, back)
        }
        // An option row with a strike; an index row (last print only).
        val o = MoveEvents.Row(1, "NIFTY", "REC_CE", 25_050.0, "2026-10-14", bar(s0))
        assertEquals(o, MoveEvents.parseS(MoveEvents.sLine(-300, o))!!.second)
        val ix = MoveEvents.Row(256265, "NIFTY", MoveEvents.INDEX_ROLE, 0.0, "", MoveEvents.indexBar(s0, 25_012.35))
        val back = MoveEvents.parseS(MoveEvents.sLine(0, ix))!!.second
        assertEquals(ix.copy(bar = ix.bar.copy(mofi = 0.0)), back)
        assertNull(MoveEvents.parseS("S,1,2")); assertNull(MoveEvents.parseS("X," + MoveEvents.sLine(0, ix).drop(2)))
        assertEquals("", MoveEvents.num(Double.NaN)); assertEquals("3", MoveEvents.num(3.0)); assertEquals("0.1", MoveEvents.num(0.1))
        assertEquals("1.23", MoveEvents.num(1.2345, 2))
    }

    private fun trigger(kind: Kind = Kind.BIG_UP) = MoveEvents.Trigger("BANKNIFTY", sec(10, 31), kind, 42.0, 18.4, 2.28, 13.2)

    @Test fun theHeaderAndTheWholeEventReadBack() {
        val t = trigger()
        val h = MoveEvents.headerLine(t, MoveEvents.Context(vix = 13.2, oi = 1_000_000, price = 55_000.1, vwapSd = 1.24, valuePos = "outside above (accepted)",
            gamma = "negative", zeroGammaDist = -120.0, ibHigh = 55_100.0, ibLow = 54_800.0, openType = "an open-drive", dayType = "a trend day",
            minutesOpen = 76, expiryDay = false, events = listOf("RBI policy, 10:00"), flow = "buyers 64", buyers = 64,
            traps = listOf("pull detected (offers)"), headline = "Banks rally, = on rate cut"), 1_790_000_000_000)
        val m = assertNotNull(MoveEvents.parseH(h))
        assertEquals(t.id, m["id"]); assertEquals("BANKNIFTY", m["name"]); assertEquals("big_up", m["kind"]); assertEquals(t.startSec.toString(), m["candle_start"])
        assertEquals("10:31", m["time"]); assertEquals("42", m["ret_bp"]); assertEquals("18.4", m["x"]); assertEquals("4.7", m["n"])
        assertEquals("13.2", m["vix"]); assertEquals("1.24", m["vwap_sd"]); assertEquals("RBI policy  10:00", m["events"])
        assertEquals("pull detected (offers)", m["traps"]); assertEquals("Banks rally    on rate cut", m["headline"]); assertEquals("0", m["expiry_day"])
        assertNull(MoveEvents.parseH("S,1"))
        val rows = (-300L until 360L).map { s -> MoveEvents.sLine(s, MoveEvents.Row(1, "BANKNIFTY", "FUTURE", 0.0, "", bar(t.startSec + s))) }
        val text = listOf(h, MoveEvents.COLUMNS) + rows.take(360) + MoveEvents.mLine(sec(10, 35), -30.0, 6.1) + rows.drop(360) + MoveEvents.eLine(t.endSec, 660, 1)
        val e = assertNotNull(MoveEvents.parse(text.asSequence()))
        assertEquals(660, e.rows.size); assertTrue(e.complete); assertEquals(listOf("10:35"), e.merges)
        assertEquals(Kind.BIG_UP, e.kind); assertEquals(t.startSec, e.startSec); assertEquals(42.0, e.retBp); assertEquals(18.4, e.x)
        assertEquals(-300L, e.rows.first().first); assertEquals(359L, e.rows.last().first)
        assertFalse(MoveEvents.parse((listOf(h) + rows.take(10)).asSequence())!!.complete, "no E line: the window did not close")
        assertFalse(MoveEvents.parse((listOf(h) + rows.take(10) + MoveEvents.eLine(t.endSec, 10, 0, complete = false)).asSequence())!!.complete,
            "closed without its last seconds")
        assertEquals("E,${t.endSec},10,0,partial", MoveEvents.eLine(t.endSec, 10, 0, complete = false))
        assertNull(MoveEvents.parse(rows.asSequence()), "no H line: not an event")
    }

    @Test fun theDailyLineKeepsItsOldColumnsAndAddsTheNew() {
        val r = MoveEvents.Row(13_000_001, "BANKNIFTY", "FUTURE", 0.0, "", bar(sec(10, 31)), gap = 1)
        val line = MoveEvents.dailyLine(r, "10:31:00")
        val head = MoveEvents.DAILY_HEADER.trimEnd().split(',')
        val c = line.trimEnd().split(',')
        assertEquals(head.size, c.size)
        assertTrue(line.endsWith("\n"))
        // The first 16 as before (the auction's profile reads name, role, last and vol by position).
        assertEquals(listOf("epoch_sec", "time_ist", "token", "name", "role", "strike", "mid", "last", "ofi", "buy_vol", "sell_vol", "vol",
            "depth_imb", "queue_imb", "buy_sell_ratio", "oi"), head.take(16))
        assertEquals(listOf(sec(10, 31).toString(), "10:31:00", "13000001", "BANKNIFTY", "FUTURE", "", "55000.0500", "55000.1000", "12.5000", "30", "50", "80",
            "0.1234", "-0.2500", "1.2857", "1000000"), c.take(16))
        val at = head.withIndex().associate { it.value to it.index }
        assertEquals("55000", c[at.getValue("bid")]); assertEquals("300", c[at.getValue("b1q")]); assertEquals("100", c[at.getValue("a5q")])
        assertEquals("4", c[at.getValue("b1o")]); assertEquals("1", c[at.getValue("a5o")]); assertEquals("40", c[at.getValue("pull_bid")])
        assertEquals("50", c[at.getValue("q_sell_vol")]); assertEquals("40", c[at.getValue("max_print")]); assertEquals("54999.95", c[at.getValue("low")])
        assertEquals("85", c[at.getValue("trade_age_ms")]); assertEquals("1", c[at.getValue("gap_s")]); assertEquals("7", c[at.getValue("updates")])
        // An index row: the last print, no book.
        val ix = MoveEvents.dailyLine(MoveEvents.Row(256265, "NIFTY", "INDEX", 0.0, "", MoveEvents.indexBar(sec(10, 31), 25_012.35)), "10:31:00")
        assertEquals(head.size, ix.trimEnd().split(',').size)
        // The footprint's minute lines.
        val f = MoveEvents.footLines("NIFTY", sec(10, 31) / 60, listOf(Auction.Foot(25_010.5, 75, 0), Auction.Foot(25_010.0, 150, 225)))
        assertEquals("${sec(10, 31)},10:31,NIFTY,25010.5,75,0\n${sec(10, 31)},10:31,NIFTY,25010,150,225\n", f)
        assertEquals(MoveEvents.FOOT_HEADER.trimEnd().split(',').size, f.lines().first().split(',').size)
    }

    @Test fun theSequencerGivesEachRowItsGapAndOiChange() {
        val q = MoveEvents.Sequencer()
        val b = bar(1_000)
        assertEquals(0, q.row(1, "N", "FUTURE", 0.0, "", b).gap)
        val r = q.row(1, "N", "FUTURE", 0.0, "", b.copy(sec = 1_004, oi = 1_000_300))
        assertEquals(3, r.gap); assertEquals(300L, r.oiChange)
        assertEquals(0, q.row(1, "N", "FUTURE", 0.0, "", b.copy(sec = 1_005, oi = 1_000_300)).gap)
        assertEquals(0L, q.row(2, "X", "CE", 1.0, "", b.copy(oi = 0)).oiChange)
        for (k in 3L..500L) q.row(k, "X", "CE", 1.0, "", b)
        assertEquals(0, q.row(1, "N", "FUTURE", 0.0, "", b.copy(sec = 1_100)).gap, "forgotten past 400 instruments: no gap claimed")
    }

    @Test fun eventFilesAreKeptTwelveMonthsWithinTheBudget() {
        val today = LocalDate.of(2026, 10, 9)
        val files = listOf("2025-10-08_NIFTY_1032_big_up.csv.gz" to 500L, "2025-10-10_NIFTY_1100_control.csv.gz" to 500L,
            "2026-10-08_BANKNIFTY_0931_big_down.csv.gz" to 500L, "2026-10-09_BANKNIFTY_1032_big_up.csv.gz" to 500L, "notes.txt" to 9L)
        assertEquals(listOf("2025-10-08_NIFTY_1032_big_up.csv.gz"), MoveEvents.rotate(files, today))
        // Over the budget: the oldest go next, never today's.
        assertEquals(listOf("2025-10-08_NIFTY_1032_big_up.csv.gz", "2025-10-10_NIFTY_1100_control.csv.gz", "2026-10-08_BANKNIFTY_0931_big_down.csv.gz"),
            MoveEvents.rotate(files, today, budget = 100))
        val n = assertNotNull(MoveEvents.parseName("2026-10-09_BANKNIFTY_1032_big_up.csv.gz"))
        assertEquals(LocalDate.of(2026, 10, 9), n.day); assertEquals("BANKNIFTY", n.name); assertEquals("1032", n.hhmm); assertEquals(Kind.BIG_UP, n.kind)
        assertNull(MoveEvents.parseName("2026-10-09.csv.gz")); assertNull(MoveEvents.parseName("2026-10-09_FINNIFTY_1032_big_up.csv.gz"))
    }

    // ---- the summary: what came first ----

    /** A synthetic BankNifty up move at 10:31: futures buying from −20 s, calls from −8 s, offers thin from −3 s, the index from +2, VIX falls from +5. */
    private fun syntheticEvent(): MoveEvents.Event {
        val t = trigger()
        val rows = ArrayList<Pair<Long, MoveEvents.Row>>()
        for (s in -300L until 360L) {
            val at = t.startSec + s
            val delta = if (s >= -20) 200L else if (s % 2 == 0L) 5L else -5L
            val ask5 = if (s >= -3) 500L else if (s % 2 == 0L) 1_010L else 990L
            rows += s to MoveEvents.Row(1, "BANKNIFTY", "FUTURE", 0.0, "", OrderFlow.Bar(at, 55_000.0, 55_000.0, 0.0,
                if (delta > 0) delta else 0, if (delta < 0) -delta else 0, kotlin.math.abs(delta), Double.NaN, Double.NaN, Double.NaN, 1, bid5 = 900, ask5 = ask5))
            rows += s to MoveEvents.Row(2, "BANKNIFTY", "CE", 55_000.0, "", OrderFlow.Bar(at, 300.0, 300.0, 0.0, if (s >= -8) 400 else 0, 0, if (s >= -8) 400 else 0,
                Double.NaN, Double.NaN, Double.NaN, 1))
            rows += s to MoveEvents.Row(3, "BANKNIFTY", "REC_PE", 55_000.0, "", OrderFlow.Bar(at, 280.0, 280.0, 0.0, 0, 0, 0, Double.NaN, Double.NaN, Double.NaN, 1))
            rows += s to MoveEvents.Row(260105, "BANKNIFTY", "INDEX", 0.0, "", MoveEvents.indexBar(at, 54_900.0 + (if (s >= 2) (s - 1) * 2.0 else 0.0)))
            rows += s to MoveEvents.Row(264969, MoveEvents.VIX, "INDEX", 0.0, "", MoveEvents.indexBar(at, 14.0 - (if (s >= 5) (s - 4) * 0.01 else 0.0)))
        }
        val h = MoveEvents.parseH(MoveEvents.headerLine(t, MoveEvents.Context(vix = 14.0, vwapSd = -0.5, valuePos = "inside value", gamma = "negative",
            ibHigh = 55_100.0, ibLow = 54_850.0, dayType = "a normal day", flow = "buyers 61", traps = listOf("time window")), 0))!!
        return MoveEvents.Event(h, rows, emptyList(), complete = true)
    }

    @Test fun theSummarySaysWhatStoodOutFirstAndTheBookThinning() {
        val s = MoveEvents.summarize(syntheticEvent())
        assertEquals(listOf("futures flow" to -20L, "options flow" to -8L, "the book in the way thinning" to -3L, "the cash index" to 2L, "VIX" to 5L), s.first)
        assertEquals(1, s.dir)
        assertEquals(0L, s.flowBefore); assertEquals(20 * 200L, s.flowLead); assertEquals(60 * 200L, s.flowDuring)
        assertEquals(0L, s.optBefore); assertEquals(8 * 400L, s.optLead); assertEquals(60 * 400L, s.optDuring)
        assertEquals(1_000.0, s.wayBefore, 1e-9)
        assertTrue(s.thinning(true) < -0.02 && s.thinning(false) <= -0.49, "${s.thinning(true)} ${s.thinning(false)}")
        assertEquals(14.0, s.vixBefore, 1e-9); assertEquals(14.0, s.vixAt, 1e-9); assertTrue(s.vixAfter < 14.0)
        assertEquals("10:31 BankNifty up 0.42% (18.4× usual)", MoveEvents.listLine(s))
        val lines = MoveEvents.lines(s).toMap()
        assertEquals("+0 / +4,000 / +12,000", lines["Futures delta: 5-1 min before / last minute / candle"])
        assertEquals("futures flow −20 s, options flow −8 s, the book in the way thinning −3 s, the cash index +2 s, VIX +5 s", lines["Stood out first"])
        assertTrue(lines.getValue("Book in the way (offers, 5 levels)").startsWith("1,000 / "), lines.toString())
        assertEquals("VIX 14 · 0.5 SD below VWAP · inside value · gamma negative · IB 250 pts · a normal day · events: none · flow buyers 61 · traps: time window", lines["Context"])
        // A quiet control: nothing stands out.
        val quiet = syntheticEvent().let { e -> e.copy(header = e.header + ("kind" to "control"),
            rows = e.rows.map { (s, r) -> s to r.copy(bar = r.bar.copy(buyVol = 0, sellVol = 0, vol = 0, ask5 = 1_000, last = if (r.role == "INDEX") 100.0 else r.bar.last)) }) }
        val q = MoveEvents.summarize(quiet)
        assertEquals(Kind.CONTROL, q.kind); assertTrue(q.first.isEmpty(), "${q.first}")
        assertTrue(MoveEvents.listLine(q).contains("control minute"))
        assertEquals("nothing before the candle itself", MoveEvents.lines(q).toMap()["Stood out first"])
    }

    // ---- Jarvis ----

    @Test fun jarvisReadsTheQuestion() {
        assertEquals(MoveEvents.Ask("BANKNIFTY", 10 * 60 + 32, 1), MoveEvents.asked("why did banknifty jump at 10:32?"))
        assertEquals(MoveEvents.Ask("BANKNIFTY", 10 * 60 + 32, 0), MoveEvents.asked("What happened in Bank Nifty at 10 32"))
        assertEquals(MoveEvents.Ask("NIFTY", 14 * 60 + 15, -1), MoveEvents.asked("why did nifty fall at 2:15"))
        assertEquals(MoveEvents.Ask("NIFTY", 10 * 60 + 5, 1), MoveEvents.asked("why did nifty 50 spike at 10.05"))
        assertEquals(MoveEvents.Ask(null, 11 * 60, -1), MoveEvents.asked("why did the market crash at 11:00"))
        assertEquals(MoveEvents.Ask("BANKNIFTY", 13 * 60 + 40, -1), MoveEvents.asked("banknifty 1:40 pm pe kyun gira"))
        for (s in listOf("why did banknifty jump", "why did my order fail at 10:32", "remind me at 10:32 about banknifty", "why did banknifty jump at 18:30",
            "why did gold jump at 10:32", "what is the order flow on banknifty", "why did nifty fall today", "why did banknifty jump at 10:75"))
            assertNull(MoveEvents.asked(s), s)
    }

    @Test fun jarvisAnswersFromTheNearestSavedEvent() {
        val s = MoveEvents.summarize(syntheticEvent())
        val a = MoveEvents.answer(MoveEvents.asked("why did banknifty jump at 10:32")!!, listOf(s))
        assertTrue(a.startsWith("BankNifty's big move at 10:31: up 0.42% in the minute, 18.4 times the usual move then. "), a)
        assertTrue("What came first: futures flow 20 s before the candle, then options flow 8 s before the candle, then the book in the way thinning 3 s before the candle" in a, a)
        assertTrue("the offers in the way were" in a && "Context: VIX 14" in a, a)
        assertTrue(a.endsWith("Timing only, Boss: what came first is not proof of what caused it, and it is not a forecast."), a)
        // Said the other way: corrected.
        assertTrue("(it went up, not down)" in MoveEvents.answer(MoveEvents.asked("why did banknifty fall at 10:30")!!, listOf(s)))
        // Too far, another index, or only a control: no saved move, said so, with what was saved.
        val far = MoveEvents.answer(MoveEvents.asked("why did banknifty jump at 13:00")!!, listOf(s))
        assertTrue(far.startsWith("I have no saved big move of BankNifty near 13:00 today, Boss.") && "Saved today: 10:31 BN up." in far, far)
        val none = MoveEvents.answer(MoveEvents.asked("why did nifty jump at 10:32")!!, listOf(s))
        assertTrue("None saved today yet." in none && "4.7 times" in none, none)
        val ctl = MoveEvents.summarize(syntheticEvent().let { it.copy(header = it.header + ("kind" to "control")) })
        assertTrue(MoveEvents.answer(MoveEvents.Ask(null, 631, 0), listOf(ctl)).startsWith("I have no saved big move of Nifty or BankNifty near 10:31"))
        // Never a word of acting.
        assertFalse(Regex("(?i)\\b(buy|sell|enter|exit)\\b").containsMatchIn(a), a)
    }
}
