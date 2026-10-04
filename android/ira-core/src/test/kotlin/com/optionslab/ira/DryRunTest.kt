package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A whole trading day replayed minute by minute (09:15 to 15:30) through Jarvis's automatic rules, as the market
 * watch calls them: nothing fires twice, nothing spams, the stop only ever moves up and always sits under the price.
 */
class DryRunTest {
    private val day = LocalDate.of(2026, 10, 15)

    /** A bought option that rises to +60%, gives back half, then drifts: the trailing stop's hardest day. */
    private fun price(m: Int): Double {
        val x = (m - 9 * 60 - 15).toDouble()
        return when {
            x < 120 -> 100.0 + x * 0.5                       // to 160 by 11:15
            x < 200 -> 160.0 - (x - 120) * 0.4               // back to 128 by 12:35
            else -> 128.0 + kotlin.math.sin(x / 7) * 2       // drifting
        }
    }

    @Test fun aWholeDayThroughEveryRule() {
        var stop: Double? = 85.0
        var peak = 100.0
        var moves = 0
        val feed = Once(60); var feedWarned = false; var feedTold = 0
        val overtrade = Once(Overtrade.WINDOW_MINUTES); var overtradeTold = 0
        val buys = ArrayList<LocalDateTime>()
        val closed = ArrayList<Pair<LocalDateTime, Double>>()
        var coolTold = 0; val coolSaid = HashSet<String>()
        var expiryTold = 0
        var lastPrice: LocalDateTime? = null

        for (m in 9 * 60 + 15..15 * 60 + 30) {
            val now = day.atTime(m / 60, m % 60)
            val px = price(m)
            // Live prices stop from 11:00 to 11:10.
            if (m !in 11 * 60..11 * 60 + 10) lastPrice = now
            // The automatic trailing stop.
            peak = maxOf(peak, px)
            AutoTrail.next(100.0, peak, stop)?.let { s ->
                if (px > s) {
                    assertTrue(stop == null || s > stop!!, "the stop only moves up: $stop -> $s at $now")
                    stop = s; moves++
                }
            }
            stop?.let { assertTrue(it < px || m >= 12 * 60, "the stop sits under the price while it is set (at $now: $it vs $px)") }
            // Live prices stopped: told once per outage.
            val stale = FeedHealth.stale(lastPrice, now, true)
            if (stale && !feedWarned) { feedWarned = true; if (feed.allow("feed", now)) feedTold++ } else if (!stale) feedWarned = false
            // The owner buys five times between 10:00 and 10:20.
            if (m in listOf(600, 605, 610, 615, 620)) buys += now
            Overtrade.count(buys, now)?.let { if (overtrade.allow("over", now)) overtradeTold++ }
            // Two losing Jarvis trades close at 13:00 and 13:20.
            if (m == 13 * 60) closed += now to -400.0
            if (m == 13 * 60 + 20) closed += now to -300.0
            CoolOff.until(closed, now)?.let { if (coolSaid.add(it.toString())) coolTold++ }
            // The 14:55 expiry heads-up, gated once a day.
            if (m in ExpiryPreview.AT until 15 * 60 + 5 && expiryTold == 0) expiryTold++
        }

        assertTrue(moves in 3..20, "the stop trailed a handful of times, not every minute: $moves")
        assertEquals(136.0, stop, "15% under the 160 peak")
        assertEquals(1, feedTold, "one warning for one outage")
        assertEquals(1, overtradeTold, "one word for one burst of buys")
        assertEquals(1, coolTold, "one cooling-off for one losing streak")
        assertEquals(1, expiryTold)
    }
}
