package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reasoning, round 25: NeedsTrue sets today's move in the underlying beside its comeback record and its usual range. */
class NeedsTrueTodayTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday
    private val now = today.atTime(12, 0)
    private val thu = LocalDate.of(2026, 10, 8)

    /** One session from 09:15 to [until]: a straight walk from [open] to [ext] at [at], then on to [close] at 15:29. */
    private fun session(d: LocalDate, open: Double, ext: Double, at: LocalTime, close: Double, until: LocalTime = LocalTime.of(15, 29)): List<Candle> {
        val first = LocalTime.of(9, 15).toSecondOfDay(); val mid = at.toSecondOfDay(); val last = LocalTime.of(15, 29).toSecondOfDay()
        fun px(t: LocalTime): Double {
            val s = t.toSecondOfDay()
            return if (s <= mid) open + (ext - open) * (s - first).toDouble() / (mid - first)
            else ext + (close - ext) * (s - mid).toDouble() / (last - mid)
        }
        val out = ArrayList<Candle>()
        var t = LocalTime.of(9, 15)
        while (!t.isAfter(until)) {
            val o = if (t == LocalTime.of(9, 15)) open else px(t.minusMinutes(1))
            val c = px(t)
            out += Candle(d.atTime(t), o, maxOf(o, c), minOf(o, c), c)
            t = t.plusMinutes(1)
        }
        return out
    }

    private fun weekdays(n: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < n) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d; d = d.minusDays(1) }
        return out.reversed()
    }

    /**
     * A flat first day, then [n] days by turns: a fall to -1.5% that ends +0.2% (back), a fall to -1.2% that ends -1.1%
     * (held), a rally to +1.3% that ends +0.3%, a quiet day; then today to [todayExt]% of the last close at noon.
     */
    private fun nifty(n: Int, todayExt: Double): Pair<List<Candle>, Double> {
        val ds = weekdays(n + 1)
        val out = ArrayList<Candle>()
        out += session(ds[0], 25000.0, 24990.0, LocalTime.of(12, 0), 25000.0)
        var pc = 25000.0
        for (i in 1..n) {
            val (ext, at, end) = when ((i - 1) % 4) {
                0 -> Triple(-1.5, LocalTime.of(10, 30), 0.2)
                1 -> Triple(-1.2, LocalTime.of(13, 0), -1.1)
                2 -> Triple(1.3, LocalTime.of(11, 0), 0.3)
                else -> Triple(-0.3, LocalTime.of(12, 0), 0.1)
            }
            val close = pc * (1 + end / 100)
            out += session(ds[i], pc, pc * (1 + ext / 100), at, close)
            pc = close
        }
        out += session(today, pc, pc * (1 + todayExt / 100), LocalTime.of(12, 0), pc, until = LocalTime.of(12, 0))
        return out to pc
    }

    private fun put(spot: Double) = PositionHealth.Pos("Paper", "NIFTY2610824500PE", 75, 80.0, 60.0, "NIFTY", 24500.0, "PE", thu,
        spot = spot, avgRange = 200.0, rangeDays = 10)
    private fun call(spot: Double) = PositionHealth.Pos("Paper", "NIFTY2610825000CE", 75, 100.0, 60.0, "NIFTY", 25000.0, "CE", thu,
        spot = spot, avgRange = 200.0, rangeDays = 10)

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|hold it|will (rise|fall|go|recover)|likely|expect)\\b")

    @Test fun aFallBesideTheRecordForAPutAndACall() {
        val (bars, pc) = nifty(24, -1.2)
        val spot = pc * 0.988
        val l = NeedsTrue.todayBeside("NIFTY", bars, now, listOf(put(spot), call(spot)))
        assertTrue(l[0].startsWith("Beside today: Nifty is at "), l[0])
        assertTrue(l[0].contains("-1.20% on the previous close of"), l[0])
        assertTrue(l[0].contains("(today's low -1.20%, high 0.00%); its range so far is "), l[0])
        assertTrue(l[0].contains("of its average day's range (200, last 10 sessions)."), l[0])
        // 24 sessions; 12 fell 1% or more: 6 ended back above (the -1.5% days), 6 held (the -1.2% days), 6 won back half.
        assertEquals("Its comeback record: over the last 24 whole sessions on the phone, Nifty fell 1% or more below the previous close in the day on 12; " +
            "6 of them ended back above it (50%), 6 won back at least half of the day's fall (50%) and 6 ended still 1% or more down (50%). " +
            "Only 12 days, so a few days move these figures a lot.", l[1])
        assertEquals("Your NIFTY2610824500PE needs Nifty below 24,420 at expiry: today's fall is its way, and on the record such a move held to the close " +
            "on 6 of 12 days (50%) and came back across the previous close on 6 (50%).", l[2])
        assertEquals("Your NIFTY2610825000CE needs Nifty above 25,100 at expiry: today's fall is against it; on the record such a move came back across " +
            "the previous close on 6 of 12 days (50%) and held to the close on 6 (50%).", l[3])
        assertFalse(l.any { ADVICE.containsMatchIn(it) }, l.toString())
    }

    @Test fun joinedIntoTheAnswer() {
        val (bars, pc) = nifty(24, -1.2)
        val l = NeedsTrue.lines("for my 24500 put to work, what needs to happen?", listOf(put(pc * 0.988)), mapOf("NIFTY" to bars), now)
        val i = l.indexOfFirst { it.startsWith("Beside today: Nifty") }
        assertTrue(i > 1, l.toString())
        assertTrue(l[i + 1].startsWith("Its comeback record:"), l.toString())
        assertTrue(l[i + 2].startsWith("Your NIFTY2610824500PE needs Nifty below 24,420 at expiry: today's fall is its way"), l.toString())
        assertEquals("Facts and arithmetic, not a forecast or advice - your call, Boss.", l.last())
    }

    @Test fun smallMovesTooFewDaysAndNoToday() {
        val (bars, pc) = nifty(24, -0.3)
        val small = NeedsTrue.todayBeside("NIFTY", bars, now, listOf(put(pc)))
        assertEquals(2, small.size, small.toString())
        assertEquals("That is under 0.5% from the previous close, too small a move to set beside its comeback record.", small[1])
        val (few, fpc) = nifty(8, -1.2)
        val l = NeedsTrue.todayBeside("NIFTY", few, now, listOf(put(fpc)))
        assertEquals("Too few whole sessions of Nifty on the phone (8) to set today's move beside its comeback record (I need 20).", l[1])
        // Nothing of today on the phone: nothing said.
        assertTrue(NeedsTrue.todayBeside("NIFTY", bars.filter { it.t.toLocalDate() != today }, now, listOf(put(pc))).isEmpty())
        assertTrue(NeedsTrue.todayBeside("NIFTY", emptyList(), now, listOf(put(pc))).isEmpty())
    }

    @Test fun thePreviousSessionMustBeWholeAndRecentAndTheUnderlyingAnIndex() {
        val (bars, pc) = nifty(24, -1.2)
        val before = today.minusDays(1)
        // A partial previous day (its last bar at 13:00): no previous close to set today against.
        val partial = bars.filter { it.t.toLocalDate() != before || !it.t.toLocalTime().isAfter(LocalTime.of(13, 0)) }
        assertTrue(NeedsTrue.todayBeside("NIFTY", partial, now, listOf(put(pc))).isEmpty())
        // Started late (first bar at 10:00): not whole either.
        val late = bars.filter { it.t.toLocalDate() != before || !it.t.toLocalTime().isBefore(LocalTime.of(10, 0)) }
        assertTrue(NeedsTrue.todayBeside("NIFTY", late, now, listOf(put(pc))).isEmpty())
        // A stale previous day: the phone's last session before today is 5 days back (more than 4).
        val stale = bars.filter { it.t.toLocalDate() == today || it.t.toLocalDate().isBefore(today.minusDays(4)) }
        assertTrue(stale.any { it.t.toLocalDate().isBefore(today) })
        assertTrue(NeedsTrue.todayBeside("NIFTY", stale, now, listOf(put(pc))).isEmpty())
        // Yesterday missing on the phone (Monday's close two days back): never bridged (round 22 review)...
        val gap = bars.filter { it.t.toLocalDate() != before }
        assertTrue(NeedsTrue.todayBeside("NIFTY", gap, now, listOf(put(pc))).isEmpty())
        // ...unless the exchange calendar says yesterday did not trade.
        assertTrue(NeedsTrue.todayBeside("NIFTY", gap, now, listOf(put(pc))) { it.dayOfWeek.value <= 5 && it != before }.isNotEmpty())
        // Gold (and anything but the four indices): nothing said, however whole the sessions.
        assertTrue(NeedsTrue.todayBeside("GOLD", bars, now, listOf(put(pc))).isEmpty())
        assertTrue(NeedsTrue.todayBeside("INDIAVIX", bars, now, listOf(put(pc))).isEmpty())
        // The whole recent one still counts.
        assertTrue(NeedsTrue.todayBeside("NIFTY", bars, now, listOf(put(pc))).isNotEmpty())
    }

    @Test fun aRallyAfterTheClose() {
        val (bars, pc) = nifty(24, 1.4)
        val l = NeedsTrue.todayBeside("NIFTY", bars, today.atTime(16, 0), listOf(put(pc * 1.014)))
        assertTrue(l[0].startsWith("Beside today: Nifty ended at "), l[0])
        assertTrue(l[0].contains("its range today is"), l[0])
        // Only the 6 rally days (+1.3%) reached 1%; each ended +0.3%: half given back, none back below, none held.
        assertEquals("Its comeback record: over the last 24 whole sessions on the phone, Nifty rose 1% or more above the previous close in the day on 6; " +
            "0 of them ended back below it (0%), 6 gave back at least half of the day's rise (100%) and 0 ended still 1% or more up (0%). " +
            "Only 6 days, so a few days move these figures a lot.", l[1])
        assertTrue(l[2].contains("today's rise is against it"), l[2])
    }
}
