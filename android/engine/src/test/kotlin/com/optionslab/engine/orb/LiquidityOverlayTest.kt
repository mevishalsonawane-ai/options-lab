package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chart's Liquidity 15+5 overlay: levels to shapes, trades and room skips to markers, nothing after the last closed bar. */
class LiquidityOverlayTest {
    private val days = listOf(28, 29, 30).map { LocalDate.of(2026, 9, it) } + listOf(1, 2, 5).map { LocalDate.of(2026, 10, it) }

    /** Six sessions of 5-minute bars (09:15-15:25) on the series the research version was checked on, [amp] times as wide. */
    private fun sessions(amp: Double = 1.0): List<Bar> {
        val n = days.size * 75
        val c = (0 until n).map { 1000 + amp * (40 * sin(it / 9.0) + 25 * sin(it / 3.7) + 10 * sin(it / 1.3)) }
        val o = (0 until n).map { if (it == 0) c[0] else c[it - 1] }
        val h = (0 until n).map { maxOf(o[it], c[it]) + amp * (3 + 8 * abs(sin(it / 2.3))) }
        val l = (0 until n).map { minOf(o[it], c[it]) - amp * (3 + 8 * abs(cos(it / 2.9))) }
        return (0 until n).map { Bar(days[it / 75].atTime(9, 15).plusMinutes(5L * (it % 75)), o[it], h[it], l[it], c[it]) }
    }

    private val close = days.last().atTime(15, 40)

    @Test fun eachIndexShowsItsBooksCharts() {
        assertEquals(listOf(15, 5), LiquidityOverlay.timeframes("BANKNIFTY"))
        assertEquals(listOf(30, 5), LiquidityOverlay.timeframes("FINNIFTY"))
        assertEquals(emptyList(), LiquidityOverlay.timeframes("NIFTY"))
    }

    @Test fun levelsBecomeShapesOverTheLastThreeSessions() {
        val bars = sessions()
        val m = LiquidityOverlay.build(bars, 5, "BANKNIFTY", close, emptyList())
        assertEquals(5, m.minutes)
        assertEquals(days.takeLast(3), m.bars.map { it.start.toLocalDate() }.distinct())
        assertEquals(225, m.bars.size)
        assertEquals(days.last().atTime(15, 25), m.lastBar)
        // The same zones the arm reads, on all the closed bars; only those still on screen in the window.
        val all = LiquidityOverlay.closedBars(bars, 5, close)
        val first = all.size - 225
        val zones = LiquidityRules.zones(all).filter { (if (it.broken < 0) all.lastIndex else it.broken) >= first }
        assertEquals(zones.size, m.shapes.size)
        assertTrue(m.shapes.any { it.kind == LiquidityOverlay.Kind.SWING } && m.shapes.any { it.kind == LiquidityOverlay.Kind.POOL })
        assertTrue(m.shapes.any { it.taken } && m.shapes.any { !it.taken })
        for (s in m.shapes) {
            assertTrue(s.from in 0..s.to && s.to <= m.bars.lastIndex, "$s inside the window")
            assertEquals(if (s.side > 0) s.top else s.bottom, s.edge)
            assertEquals(if (s.kind == LiquidityOverlay.Kind.SWING) "swing" else "pool", s.label)
            // A taken level ends on the bar whose close took it; a live one runs to the last bar.
            if (s.taken) {
                val b = m.bars[s.to]
                assertTrue(if (s.side > 0) b.close > s.top else b.close < s.bottom)
            } else assertEquals(m.bars.lastIndex, s.to)
        }
        assertEquals(m.shapes.sortedBy { it.from }, m.shapes)
        // Capped: the most recently formed.
        val capped = LiquidityOverlay.build(bars, 5, "BANKNIFTY", close, emptyList(), maxZones = 4)
        assertEquals(4, capped.shapes.size)
        assertTrue(capped.shapes.all { it in m.shapes })
        // Fewer sessions: fewer bars.
        assertEquals(75, LiquidityOverlay.build(bars, 5, "BANKNIFTY", close, emptyList(), sessions = 1).bars.size)
    }

    @Test fun theLevelsAreReadFromTheArmsTenDaysOnly() {
        val bars = sessions()
        val m = LiquidityOverlay.build(bars, 5, "BANKNIFTY", close, emptyList())
        // A session 20 days back (with a swing far above everything since) is history the arm never reads: nothing changes.
        val old = bars.take(75).mapIndexed { i, b ->
            val h = if (i == 37) b.high + 500 else b.high
            b.copy(start = LocalDate.of(2026, 9, 15).atTime(b.start.toLocalTime()), high = h)
        }
        assertEquals(m, LiquidityOverlay.build(old + bars, 5, "BANKNIFTY", close, emptyList()))
        // Within the ten days it counts: the same session dated 8 days before is read.
        val near = old.map { it.copy(start = LocalDate.of(2026, 9, 27).atTime(it.start.toLocalTime())) }
        assertTrue(LiquidityOverlay.build(near + bars, 5, "BANKNIFTY", close, emptyList()) != m)
    }

    @Test fun nothingAfterTheLastClosedBar() {
        val bars = sessions()
        val now = days[4].atTime(11, 2)
        val m = LiquidityOverlay.build(bars, 5, "BANKNIFTY", now, emptyList())
        // The 10:55 bar has closed by 11:02; the 11:00 bar has not.
        assertEquals(days[4].atTime(10, 55), m.lastBar)
        // The bars after now change nothing: the same model as from the bars up to now alone.
        assertEquals(m, LiquidityOverlay.build(bars.filter { it.start.isBefore(now) }, 5, "BANKNIFTY", now, emptyList()))
        // And the levels are those of the closed bars alone (a later close taking a level is not known yet).
        val upTo = bars.filter { !it.start.plusMinutes(5).isAfter(now) }
        assertEquals(m.shapes, LiquidityOverlay.build(upTo, 5, "BANKNIFTY", now, emptyList()).shapes)
        // 15-minute: the 10:45 bar closes at 11:00, so it is the last one.
        assertEquals(days[4].atTime(10, 45), LiquidityOverlay.build(bars, 15, "BANKNIFTY", now, emptyList()).lastBar)
        // Nothing closed yet: an empty model.
        val empty = LiquidityOverlay.build(bars, 5, "BANKNIFTY", days[0].atTime(9, 16), emptyList())
        assertTrue(empty.bars.isEmpty() && empty.shapes.isEmpty() && empty.markers.isEmpty() && empty.lines.isEmpty())
        assertNull(empty.lastBar)
    }

    @Test fun aBarStillMissingItsLastMinuteWaitsTwoMinutes() {
        val d = days[0]
        val ones = (0 until 14).map { Bar(d.atTime(9, 15).plusMinutes(it.toLong()), 100.0, 101.0, 99.0, 100.0 + it) }
        // 09:15-09:28 present: the 09:15 bar is whole; the 09:25 bar misses 09:29.
        assertEquals(listOf(d.atTime(9, 15), d.atTime(9, 20)), LiquidityOverlay.closedBars(ones, 5, d.atTime(9, 30), inputMinutes = 1).map { it.start })
        assertEquals(3, LiquidityOverlay.closedBars(ones, 5, d.atTime(9, 32), inputMinutes = 1).size)
        val fifteen = LiquidityOverlay.closedBars(ones + Bar(d.atTime(9, 29), 100.0, 101.0, 99.0, 114.0), 15, d.atTime(9, 30), inputMinutes = 1)
        assertEquals(listOf(Bar(d.atTime(9, 15), 100.0, 101.0, 99.0, 114.0)), fifteen)
    }

    @Test fun roomSkipsAreDecidedOnTheBarsUpToThem() {
        val bars = sessions()
        val all = LiquidityOverlay.closedBars(bars, 5, close)
        val skips = LiquidityOverlay.skips(all, LiquidityRules.zones(all), 5, "BANKNIFTY")
        // Bar by bar on what was known then (as the arm's minute cycle decides): the same skips.
        val prefix = (2 * LiquidityRules.SWING_LOOKBACK + 1 until all.size).mapNotNull { i ->
            val upTo = all.subList(0, i + 1)
            val s = LiquidityRules.signal(upTo, LiquidityRules.zones(upTo)) ?: return@mapNotNull null
            if (!LiquidityRules.mayEnterAt(all[i].start.plusMinutes(5)) || LiquidityRules.hasRoom(s, all[i].close, "BANKNIFTY")) null else all[i].start
        }
        assertEquals(prefix, skips.map { it.bar })
        assertEquals(listOf(days[2].atTime(9, 30), days[3].atTime(10, 25), days[4].atTime(13, 35)), prefix)
        val s = skips.first()
        assertEquals(-1, s.side)
        assertEquals(30.0, s.need)
        assertTrue(s.room in 0.0..30.0)
        assertEquals(s.side * (s.target - s.close), s.room)
        // As markers: hollow, only in the window, below the bar for a break up and above for a break down.
        val m = LiquidityOverlay.build(bars, 5, "BANKNIFTY", close, emptyList())
        val marks = m.markers.filter { it.kind == LiquidityOverlay.MarkerKind.NO_ROOM }
        assertEquals(listOf(days[3].atTime(10, 25), days[4].atTime(13, 35)), marks.map { m.bars[it.bar].start })
        assertTrue(marks.all { it.text == "skip" && it.trade == null && it.skip != null && it.above == (it.side < 0) })
        // Wider swings on FINNIFTY (15-point stop): two of the three breaks have room and are not skips.
        val wide = sessions(3.0)
        val wall = LiquidityOverlay.closedBars(wide, 5, close)
        assertEquals(listOf(days[3].atTime(10, 25)), LiquidityOverlay.skips(wall, LiquidityRules.zones(wall), 5, "FINNIFTY").map { it.bar })
        // Outside entry hours nothing is a skip (the arm would not have decided there).
        assertTrue(LiquidityOverlay.skips(all.filter { it.start.toLocalTime() >= java.time.LocalTime.of(14, 0) },
            LiquidityRules.zones(all.filter { it.start.toLocalTime() >= java.time.LocalTime.of(14, 0) }), 5, "BANKNIFTY").isEmpty())
    }

    private fun trade(minutes: Int, side: Int, signal: LocalDateTime, exitAt: LocalDateTime? = null, why: String? = null) = LiquidityOverlay.Trade(
        book = if (minutes == 5) "liquidity5" else "liquidity15", minutes = minutes, side = side, signalBar = signal,
        entryTime = signal.plusMinutes(minutes.toLong()), entry = 120.5, qty = 30, strike = "52000 CE",
        exit = exitAt?.let { 140.0 }, exitTime = exitAt, why = why, level = 1050.0, target = 1077.25, pnl = exitAt?.let { 520.0 },
        near = false, volSkip = true, strong = null)

    @Test fun tradesBecomeMarkersAndLines() {
        val bars = sessions()
        val d = days.last()
        val open = trade(5, -1, d.atTime(10, 0))
        val closed = trade(15, 1, d.atTime(11, 0), exitAt = d.atTime(11, 47), why = "next_liquidity")
        val m = LiquidityOverlay.build(bars, 5, "BANKNIFTY", close, listOf(closed, open))
        // Only a closed paper trade is replayed on a tap: not an open one, not a live (broker) one.
        assertTrue(closed.replayable); assertFalse(open.replayable); assertFalse(closed.copy(live = true).replayable)
        fun at(i: Int) = m.bars[i].start
        val trades = m.markers.filter { it.trade != null }
        // Entries in time order, each tagged with its book's chart; a down break's marker above the bar.
        val e1 = trades.first { it.kind == LiquidityOverlay.MarkerKind.ENTRY && it.trade == open }
        assertEquals(d.atTime(10, 0), at(e1.bar)); assertTrue(e1.above); assertEquals("L5", e1.text)
        // The 15-minute book's 11:00 bar closes with the 5-minute 11:10 bar.
        val e2 = trades.first { it.kind == LiquidityOverlay.MarkerKind.ENTRY && it.trade == closed }
        assertEquals(d.atTime(11, 10), at(e2.bar)); assertFalse(e2.above); assertEquals("L15", e2.text)
        val x = trades.single { it.kind == LiquidityOverlay.MarkerKind.EXIT }
        assertEquals(d.atTime(11, 45), at(x.bar)); assertTrue(x.above); assertEquals("next liquidity", x.text)
        // The broken level for both; the target, dotted, only while the trade is open.
        assertEquals(listOf(LiquidityOverlay.Line(1050.0, e2.bar, x.bar, false, "L15 level")), m.lines.filter { it.label.startsWith("L15") })
        assertEquals(listOf(LiquidityOverlay.Line(1050.0, e1.bar, m.bars.lastIndex, false, "L5 level"),
            LiquidityOverlay.Line(1077.25, e1.bar, m.bars.lastIndex, true, "L5 target")), m.lines.filter { it.label.startsWith("L5") })
        // On the 15-minute chart the same trades sit on the 15-minute bars.
        val m15 = LiquidityOverlay.build(bars, 15, "BANKNIFTY", close, listOf(closed, open))
        assertEquals(listOf(d.atTime(10, 0), d.atTime(11, 0), d.atTime(11, 45)),
            m15.markers.filter { it.trade != null }.map { m15.bars[it.bar].start })
        // A trade before the window, or with no levels recorded, draws no marker / no line.
        val old = trade(5, 1, days[0].atTime(10, 0))
        val bare = trade(5, 1, d.atTime(12, 0)).copy(level = null, target = null)
        val m2 = LiquidityOverlay.build(bars, 5, "BANKNIFTY", close, listOf(old, bare))
        assertEquals(listOf(bare), m2.markers.mapNotNull { it.trade })
        assertTrue(m2.lines.isEmpty())
        // An exit after the last closed bar has no bar yet: no marker (never on an earlier bar); its line runs to the last bar.
        val late = trade(5, 1, d.atTime(15, 0), exitAt = d.atTime(15, 39), why = "session_end")
        val m3 = LiquidityOverlay.build(bars, 5, "BANKNIFTY", close, listOf(late))
        assertTrue(m3.markers.none { it.kind == LiquidityOverlay.MarkerKind.EXIT })
        assertEquals(m3.bars.lastIndex, m3.lines.first { it.label == "L5 level" }.to)
        // Mid-session: an entry and exit in the bar still forming wait for it to close.
        val at1103 = d.atTime(11, 3)
        val forming = trade(5, 1, d.atTime(10, 55), exitAt = d.atTime(11, 2), why = "stop")
        val m4 = LiquidityOverlay.build(bars, 5, "BANKNIFTY", at1103, listOf(forming))
        assertEquals(d.atTime(10, 55), m4.lastBar)
        assertEquals(listOf(LiquidityOverlay.MarkerKind.ENTRY), m4.markers.filter { it.trade != null }.map { it.kind })
        val m5 = LiquidityOverlay.build(bars, 5, "BANKNIFTY", d.atTime(11, 8), listOf(forming))
        assertEquals(d.atTime(11, 0), m5.bars[m5.markers.single { it.kind == LiquidityOverlay.MarkerKind.EXIT }.bar].start)
    }

    @Test fun aTapFindsTheMarkerOnOrBesideTheBar() {
        val bars = sessions()
        val d = days.last()
        val t = trade(5, 1, d.atTime(10, 0), exitAt = d.atTime(10, 5), why = "stop")
        val m = LiquidityOverlay.build(bars, 5, "BANKNIFTY", close, listOf(t))
        val e = m.markers.first { it.kind == LiquidityOverlay.MarkerKind.ENTRY }
        assertEquals(e, LiquidityOverlay.markerNear(m.markers, e.bar))
        assertEquals(LiquidityOverlay.MarkerKind.EXIT, LiquidityOverlay.markerNear(m.markers, e.bar + 1)?.kind)
        assertEquals(e, LiquidityOverlay.markerNear(m.markers, e.bar - 1))
        assertNull(LiquidityOverlay.markerNear(m.markers, e.bar - 5))
        assertNull(LiquidityOverlay.markerNear(emptyList(), 3))
        assertNull(LiquidityOverlay.barAt(emptyList(), d.atTime(10, 0), 5))
        assertNull(LiquidityOverlay.barAt(m.bars, days[0].atTime(10, 0), 5))
        // The last bar holds moments up to its end only; past it (its bar not closed) there is no bar.
        assertEquals(m.bars.lastIndex, LiquidityOverlay.barAt(m.bars, d.atTime(15, 29), 5))
        assertNull(LiquidityOverlay.barAt(m.bars, d.atTime(15, 30), 5))
        assertNull(LiquidityOverlay.barAt(m.bars, d.atTime(15, 39), 5))
    }

    @Test fun cardsSayWhatHappened() {
        val d = days.last()
        val t = trade(15, 1, d.atTime(11, 0), exitAt = d.atTime(11, 47), why = "index_stop")
        assertEquals(listOf("Liquidity 15m · L15", "52000 CE · qty 30", "Entry ₹120.50 at 11:15", "Exit ₹140.00 at 11:47 · index stop",
            "P&L +₹520.00", "Broke 1,050.00 · next level 1,077.25", "Shadow: room: ok · vol skip (c): would skip"), LiquidityOverlay.card(t))
        val open = trade(5, -1, d.atTime(10, 0)).copy(near = true, volSkip = false, strong = true, book = "liquidity5_fin")
        assertEquals(listOf("Liquidity 5m FINNIFTY · L5", "52000 CE · qty 30", "Entry ₹120.50 at 10:05", "Open · target 1,077.25",
            "Broke 1,050.00 · next level 1,077.25", "Shadow: room: too little · vol skip (c): would take · strong close (f): yes"), LiquidityOverlay.card(open))
        val bare = open.copy(book = "other", target = null, level = null, near = null, volSkip = null, strong = false,
            exit = 90.0, exitTime = null, pnl = -915.0, why = null)
        assertEquals(listOf("other · L5", "52000 CE · qty 30", "Entry ₹120.50 at 10:05", "Exit ₹90.00 · exit", "P&L -₹915.00",
            "Shadow: strong close (f): no"), LiquidityOverlay.card(bare))
        assertEquals(listOf("Open"), LiquidityOverlay.card(bare.copy(exit = null, pnl = null, strong = null)).filter { it.startsWith("Open") })
        assertNull(LiquidityOverlay.flags(bare.copy(strong = null)))
        assertTrue(t.copy(exit = null).open); assertFalse(t.open)
        val skip = LiquidityOverlay.Skip(d.atTime(10, 25), 1, 1070.0, 1065.5, 1080.0, 30.0)
        assertEquals(listOf("Skipped: too little room", "10:25 bar closed 1,070.00 through 1,065.50 (above)",
            "Next level 1,080.00 only 10.00 pts away (needs 30.00)"), LiquidityOverlay.card(skip))
        assertTrue(LiquidityOverlay.card(skip.copy(side = -1, target = 1060.0))[1].endsWith("(below)"))
    }

    @Test fun everyExitReasonInWords() {
        val words = mapOf(null to "exit", "stop" to "stop", "index_stop" to "index stop", "time_stop" to "time stop",
            "next_liquidity" to "next liquidity", "new_liquidity" to "new liquidity", "failed_break" to "failed break",
            "session_end" to "15:10", "backstop_square_off" to "15:15", "operator_stop" to "stopped", "closed_by_you" to "closed by you",
            "profit_lock" to "profit lock")
        words.forEach { (k, v) -> assertEquals(v, LiquidityOverlay.reason(k), "$k") }
        assertNotNull(LiquidityOverlay.reason("anything"))
    }
}
