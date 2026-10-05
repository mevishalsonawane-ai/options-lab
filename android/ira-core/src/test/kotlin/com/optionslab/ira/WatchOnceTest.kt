package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The market-watch readers made cheaper for each pass ([Moments.look], [Moments.alerts], [SharpMove.latest]): the same
 * answer as the code before, which read every earlier session at every look - checked against that code, kept here.
 */
class WatchOnceTest {
    private val today = LocalDate.of(2026, 10, 2)

    /** [days] sessions of 1-minute candles up to [today] (09:15 on), a random walk; [vol] the size of a minute's step (%). */
    private fun bars(r: Random, days: Int, vol: Double, todayMinutes: Int = 120, shuffled: Boolean = false): List<Candle> {
        val out = ArrayList<Candle>()
        var px = 24_000.0
        for (d in days downTo 0) {
            val day = today.minusDays(d.toLong())
            val n = if (d == 0) todayMinutes else 375
            for (i in 0 until n) {
                val o = px
                px *= 1 + (r.nextDouble() - 0.5) * 2 * vol / 100
                if (r.nextInt(400) == 0) px *= 1 + (if (r.nextBoolean()) 1 else -1) * 0.006   // now and then a jump
                out += Candle(day.atTime(9, 15).plusMinutes(i.toLong()), o, maxOf(o, px) + r.nextDouble() * 3, minOf(o, px) - r.nextDouble() * 3, px)
            }
        }
        return if (shuffled) out.shuffled(r) else out
    }

    private fun snap(r: Random, prev: Double?, trading: Boolean = true, m: Market = Market.NIFTY): Snapshot {
        val open = (prev ?: 24_000.0) * (1 + (r.nextDouble() - 0.5) / 50)
        val high = open * (1 + r.nextDouble() / 100); val low = open * (1 - r.nextDouble() / 100)
        return Snapshot(m, today.atTime(11, 0), trading, low + (high - low) * r.nextDouble(), prev, open, high, low, null, null,
            emptyList(), null, null, emptyList(), emptyList(), emptyList())
    }

    // ---- the code before (2026-10-05), word for word --------------------------------------------------------------

    private fun lookBefore(s: Snapshot, bars: List<Candle>): Moments.Look? {
        if (s.market == Market.GOLD || s.market == Market.VIX || !s.trading) return null
        val prev = s.prevClose ?: return null
        val y = Moments.earlier(bars, s.at.toLocalDate()).lastOrNull() ?: return null
        val g = s.prevClose.let { (s.open - it) / it * 100 }
        val gapOpen = abs(g) >= Moments.GAP_MIN_PCT && (if (g > 0) s.low > prev else s.high < prev)
        return Moments.Look(gapOpen, s.price > y.high, s.price < y.low)
    }

    private fun latestBefore(m: Market, bars: List<Candle>): SharpMove.Move? {
        val last = bars.maxByOrNull { it.t } ?: return null
        val day = bars.filter { it.t.toLocalDate() == last.t.toLocalDate() }.sortedBy { it.t }
        if (day.size <= SharpMove.WINDOW) return null
        val w = SharpMove.WINDOW
        val u = SharpMove.usual(bars, last.t.toLocalDate())
        fun at(i: Int) = SharpMove.Move(m, day[i - w].t, day[i].t, day[i - w].c, day[i].c, u)
        var end = -1
        for (i in day.size - 1 downTo w) if (day[i - w].c > 0 && SharpMove.sharp(at(i).pct, u)) { end = i; break }
        if (end < 0) return null
        val up = at(end).up
        var best = at(end)
        var i = end - 1
        while (i >= w) {
            val mv = at(i)
            if (!SharpMove.sharp(mv.pct, u) || mv.up != up) break
            if (abs(mv.pct) > abs(best.pct)) best = mv
            i--
        }
        return best
    }

    // ---- the same answers -----------------------------------------------------------------------------------------

    @Test fun theLastEarlierSessionIsTheLastOfAllEarlierSessions() {
        val r = Random(7)
        for (k in 0 until 60) {
            val b = bars(r, r.nextInt(0, 6), 0.05, todayMinutes = r.nextInt(0, 40), shuffled = k % 2 == 0)
            for (d in listOf(today, today.plusDays(1), today.minusDays(2), today.minusDays(30)))
                assertEquals(Moments.earlier(b, d).lastOrNull(), Moments.lastEarlier(b, d))
        }
        assertEquals(null, Moments.lastEarlier(emptyList(), today))
    }

    @Test fun everyLookAndMomentAsBefore() {
        val r = Random(11)
        var told = 0
        for (k in 0 until 300) {
            val b = bars(r, r.nextInt(0, 4), 0.04, shuffled = k % 3 == 0)
            val y = Moments.earlier(b, today).lastOrNull()
            val prev = if (k % 17 == 0) null else (y?.let { (it.high + it.low) / 2 } ?: 24_000.0) * (1 + (r.nextDouble() - 0.5) / 100)
            val m = listOf(Market.NIFTY, Market.BANKNIFTY, Market.VIX, Market.GOLD)[if (k % 13 == 0) 2 + k % 2 else k % 2]
            val s = snap(r, prev, trading = k % 19 != 0, m = m)
            assertEquals(lookBefore(s, b), Moments.look(s, b))
            val before = Moments.Look(r.nextBoolean(), r.nextBoolean(), r.nextBoolean())
            val now = Moments.Look(r.nextBoolean(), r.nextBoolean(), r.nextBoolean())
            // The code before read every session whatever the looks, and found a high or a low only when newly passed.
            val all = Moments.alerts(s, b, before, now)
            told += all.size
            val high = !before.aboveHigh && now.aboveHigh
            val low = !before.belowLow && now.belowLow
            if (!high && !low) assertTrue(all.none { it.key.startsWith("prevhigh") || it.key.startsWith("prevlow") })
        }
        assertTrue(told > 50)
    }

    @Test fun everySharpMoveAsBefore() {
        val r = Random(3)
        var found = 0
        for (k in 0 until 200) {
            val vol = listOf(0.005, 0.02, 0.05, 0.12)[k % 4]
            val b = bars(r, r.nextInt(0, 5), vol, todayMinutes = r.nextInt(5, 200), shuffled = k % 5 == 0)
            val want = latestBefore(Market.NIFTY, b)
            assertEquals(want, SharpMove.latest(Market.NIFTY, b))
            if (want != null) found++
        }
        assertEquals(null, SharpMove.latest(Market.NIFTY, emptyList()))
        assertTrue(found in 20..180, "both quiet and sharp days are checked ($found)")
    }
}
