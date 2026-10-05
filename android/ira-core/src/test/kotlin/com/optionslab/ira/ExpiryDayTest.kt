package com.optionslab.ira

import com.optionslab.engine.options.ChainRow
import com.optionslab.engine.options.ChainSnapshot
import com.optionslab.engine.options.OptLeg
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpiryDayTest {
    /** Tuesday 6 October 2026: Nifty's weekly expiry. */
    private val day = LocalDate.of(2026, 10, 6)

    /** A chain around [spot] with the ATM call and put at [ce] / [pe] and the biggest OI at 24,500 both sides (max pain there). */
    private fun chain(spot: Double, ce: Double, pe: Double, expiry: LocalDate = day, at: LocalDateTime = day.atTime(9, 30)): ChainSnapshot {
        val atm = Math.round(spot / 50) * 50.0
        val rows = (0..16).map { i ->
            val k = 24_100.0 + i * 50
            val c = if (k == atm) ce else maxOf(0.05, atm - k + ce)
            val p = if (k == atm) pe else maxOf(0.05, k - atm + pe)
            ChainRow(k, OptLeg("CE$i", c, oi = if (k == 24_500.0) 2_000_000 else 100_000L), OptLeg("PE$i", p, oi = if (k == 24_500.0) 2_000_000 else 100_000L))
        }
        return ChainSnapshot.of("NIFTY", expiry, spot, 75, rows, at.atZone(ZoneId.of("Asia/Kolkata")))
    }

    /** 1-minute candles from 09:15 to [until], the price at minute i given by [px]. */
    private fun bars(until: LocalTime, px: (Int) -> Double): List<Candle> {
        val n = java.time.Duration.between(LocalTime.of(9, 15), until).toMinutes().toInt() + 1
        return (0 until n).map { i -> val c = px(i); Candle(day.atTime(9, 15).plusMinutes(i.toLong()), c, c + 1, c - 1, c) }
    }

    @Test fun slotsAreDueOnlyNearTheirTime() {
        assertNull(ExpiryDay.due(LocalTime.of(9, 29)))
        assertEquals(ExpiryDay.Slot.OPEN, ExpiryDay.due(LocalTime.of(9, 30)))
        assertEquals(ExpiryDay.Slot.OPEN, ExpiryDay.due(LocalTime.of(9, 39)))
        assertNull(ExpiryDay.due(LocalTime.of(9, 40)))
        assertEquals(ExpiryDay.Slot.LAST_HOUR, ExpiryDay.due(LocalTime.of(14, 35)))
        assertEquals(ExpiryDay.Slot.CLOSE, ExpiryDay.due(LocalTime.of(15, 21)))
        assertNull(ExpiryDay.due(LocalTime.of(15, 31)))
    }

    @Test fun onlyTheChainExpiringTodayIsRead() {
        assertNull(ExpiryDay.read(chain(24_560.0, 90.0, 80.0, expiry = day.plusDays(7)), day.atTime(9, 30)))
        val r = assertNotNull(ExpiryDay.read(chain(24_560.0, 90.0, 80.0), day.atTime(9, 30)))
        assertEquals(24_550.0, r.strike); assertEquals(170.0, r.straddle, 1e-9); assertEquals(24_500.0, r.maxPain)
    }

    @Test fun theFirstReadTellsTheStraddleAndMaxPain() {
        val r = ExpiryDay.read(chain(24_560.0, 90.0, 80.0), day.atTime(9, 30))!!
        val s = ExpiryDay.say(Market.NIFTY, ExpiryDay.Slot.OPEN, r, r, emptyList())
        assertEquals("Nifty expires today, Boss: the at-the-money straddle (24,550) is at 170.00 - call 90.00, put 80.00. " +
            "Spot 24,560.00 is 60 points above max pain 24,500 - within 0.3%, where traders watch for a pin.", s)
    }

    @Test fun laterReadsTellTheDecaySinceTheFirst() {
        val first = ExpiryDay.read(chain(24_640.0, 100.0, 90.0), day.atTime(9, 30))!!
        val now = ExpiryDay.read(chain(24_520.0, 30.0, 25.0, at = day.atTime(12, 0)), day.atTime(12, 0))!!
        val s = ExpiryDay.say(Market.NIFTY, ExpiryDay.Slot.MIDDAY, first, now, emptyList())
        assertTrue(s.startsWith("Nifty expiry, Boss, 12:00: the at-the-money straddle (24,500) is at 55.00 - call 30.00, put 25.00; " +
            "down 71% from 190.00 at 09:30 (then the 24,650 strike)."), s)
        assertTrue(s.contains("Spot 24,520.00 is 20 points above max pain 24,500 - within 0.3%"), s)
        assertTrue(s.endsWith("At 09:30 it was 140 points above max pain 24,500."), s)
        assertFalse(s.contains("advice") || s.contains("will "), s)
    }

    @Test fun farFromMaxPainIsNotCalledAPin() {
        val first = ExpiryDay.read(chain(24_560.0, 90.0, 80.0), day.atTime(9, 30))!!
        val now = ExpiryDay.read(chain(24_850.0, 40.0, 20.0, at = day.atTime(13, 30)), day.atTime(13, 30))!!
        val s = ExpiryDay.say(Market.NIFTY, ExpiryDay.Slot.AFTERNOON, first, now, emptyList())
        assertTrue(s.contains("Spot 24,850.00 is 350 points above max pain 24,500."), s)
        assertFalse(s.contains("pin"), s)
    }

    @Test fun theLastHourStartsWithTheDayRangeAndEndsWithItsOwn() {
        // 24,500 rising 1 point a minute to 14:30 (minute 315), then flat to 15:20.
        val b = bars(LocalTime.of(15, 20)) { i -> 24_500.0 + minOf(i, 315) - if (i > 330) 20.0 else 0.0 }
        val first = ExpiryDay.read(chain(24_560.0, 90.0, 80.0), day.atTime(9, 30))!!
        val at1430 = ExpiryDay.read(chain(24_815.0, 20.0, 15.0, at = day.atTime(14, 30)), day.atTime(14, 30))!!
        val s = ExpiryDay.say(Market.NIFTY, ExpiryDay.Slot.LAST_HOUR, first, at1430, b)
        assertTrue(s.endsWith("The last hour starts with the day's range at 24,499.00 to 24,816.00, 317 points."), s)
        val at1520 = ExpiryDay.read(chain(24_795.0, 8.0, 6.0, at = day.atTime(15, 20)), day.atTime(15, 20))!!
        val c = ExpiryDay.say(Market.NIFTY, ExpiryDay.Slot.CLOSE, first, at1520, b)
        assertTrue(c.endsWith("The last hour so far: 24,794.00 to 24,816.00, 22 points, 7% of the day's 317-point range."), c)
    }

    @Test fun askedAndAnsweredFromTodaysReads() {
        for (q in listOf("how is expiry going", "Jarvis, how's the nifty expiry going today?", "expiry update", "give me the bank nifty expiry update",
            "how much has the straddle decayed"))
            assertTrue(ExpiryDay.asked(q), q)
        for (q in listOf("when is the next expiry", "what expires this week", "explain straddle", "how is nifty"))
            assertFalse(ExpiryDay.asked(q), q)
        for (q in listOf("how is expiry going", "expiry update", "how much has the straddle decayed"))
            assertFalse(Topic.ACCOUNT in Ask.parse(q).topics, q)
        assertNull(ExpiryDay.soFar(emptyMap()))
        val first = ExpiryDay.read(chain(24_560.0, 90.0, 80.0), day.atTime(9, 30))!!
        val now = ExpiryDay.read(chain(24_540.0, 40.0, 45.0, at = day.atTime(11, 0)), day.atTime(11, 0))!!
        val s = assertNotNull(ExpiryDay.soFar(mapOf(Market.NIFTY to listOf(first, now))))
        assertTrue(s.startsWith("Nifty expiry, Boss, 11:00: the at-the-money straddle (24,550) is at 85.00 - call 40.00, put 45.00; down 50% from 170.00 at 09:30."), s)
    }
}
