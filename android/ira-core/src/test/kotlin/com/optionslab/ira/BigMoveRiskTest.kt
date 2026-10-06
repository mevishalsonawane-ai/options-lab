package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BigMoveRiskTest {
    private val today = LocalDate.of(2026, 10, 6)   // a Tuesday
    private val open = LocalTime.of(9, 15)

    private fun weekdaysBefore(n: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < n) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d
            d = d.minusDays(1)
        }
        return out.reversed()
    }

    /** One day's 1-minute candles from 09:15 up to (not including) [until]: a seeded walk of about [step] % a minute. */
    internal fun walk(d: LocalDate, seed: Long, until: LocalTime = LocalTime.of(15, 30), step: Double = 0.03, start: Double = 25000.0,
        drift: Double = 0.0): List<Candle> {
        val r = Random(seed)
        val out = ArrayList<Candle>()
        var px = start
        var t = open
        while (t.isBefore(until)) {
            val o = px
            val c = o * (1 + (r.nextGaussian() * step + drift) / 100)
            out += Candle(d.atTime(t), o, maxOf(o, c) + o * step / 400, minOf(o, c) - o * step / 400, c)
            px = c; t = t.plusMinutes(1)
        }
        return out
    }

    internal fun past(n: Int = 25): List<Candle> = weekdaysBefore(n).flatMapIndexed { i, d -> walk(d, 100L + i) }

    private val NO_TRADE = Regex("(?i)\\b(buy|sell|short it|go long|enter now|exit now)\\b")

    private fun saneAnswer(s: String) {
        assertTrue(BigMoveRisk.CAVEAT in s, s)
        assertFalse(NO_TRADE.containsMatchIn(s), s)
    }

    @Test fun theOpeningCandleIsTheRiskiest() {
        val now = today.atTime(9, 16)
        val bars = past() + walk(today, 7, LocalTime.of(9, 16))
        val r = BigMoveRisk.read(Market.NIFTY, bars, emptyList(), now)
        assertEquals(BigMoveRisk.Level.VERY_HIGH, r.level, "$r")
        assertTrue(r.reasons.first().contains("09:15"), "${r.reasons}")
        assertTrue(r.times!! >= BigMoveRisk.OPEN_X * 0.9)
        val said = BigMoveRisk.say(r, now)
        saneAnswer(said)
        assertTrue(said.startsWith("Big-move risk for Nifty now: very high"), said)
        // Before the open: the 09:15 candle to come, from past days only.
        val pre = BigMoveRisk.read(Market.BANKNIFTY, past(), emptyList(), today.atTime(9, 5))
        assertEquals(BigMoveRisk.Level.VERY_HIGH, pre.level)
        assertTrue(BigMoveRisk.say(pre, today.atTime(9, 5)).startsWith("Big-move risk for BankNifty at the open: very high"))
    }

    @Test fun threePmHasAboutFourTimesTheRate() {
        val now = today.atTime(14, 58)
        val bars = past() + walk(today, 11, LocalTime.of(14, 58))
        val r = BigMoveRisk.read(Market.SENSEX, bars, emptyList(), now)
        assertTrue(r.level == BigMoveRisk.Level.HIGH || r.level == BigMoveRisk.Level.VERY_HIGH, "$r")
        assertTrue(r.reasons.any { "15:00-15:05" in it }, "${r.reasons}")
        saneAnswer(BigMoveRisk.say(r, now))
        // The same day an hour earlier is an ordinary time of day: no 15:00 reason, a smaller multiple.
        val earlier = BigMoveRisk.read(Market.SENSEX, past() + walk(today, 11, LocalTime.of(13, 58)), emptyList(), today.atTime(13, 58))
        assertTrue(earlier.reasons.none { "15:00" in it })
        assertTrue(earlier.times!! < r.times!!)
    }

    @Test fun rightAfterABigCandleTheNextIsBigMoreOften() {
        val now = today.atTime(10, 56)
        val base = walk(today, 21, LocalTime.of(10, 50), step = 0.02)
        val last = base.last().c
        // 10:50-10:54: a 1% fall in five minutes (several times the usual 5-minute move).
        val big = (0 until 5).map { k ->
            val o = last * (1 - 0.002 * k); val c = last * (1 - 0.002 * (k + 1))
            Candle(today.atTime(10, 50 + k), o, o, c, c)
        }
        val tail = Candle(today.atTime(10, 55), big.last().c, big.last().c, big.last().c, big.last().c)
        val r = BigMoveRisk.read(Market.NIFTY, past() + base + big + tail, emptyList(), now)
        assertEquals(BigMoveRisk.Level.VERY_HIGH, r.level, "$r")
        assertTrue(r.reasons.first().contains("10:50") && r.reasons.first().contains("big one"), "${r.reasons}")
        // A falling market after it: the jumpier side is named too, still no direction said.
        assertTrue(r.reasons.any { it.startsWith("falling markets are jumpier") }, "${r.reasons}")
        val said = BigMoveRisk.say(r, now)
        saneAnswer(said)
        // Only candles that ended before now: the same big candle still forming at 10:53 is not "the last candle".
        val forming = BigMoveRisk.read(Market.NIFTY, past() + base + big + tail, emptyList(), today.atTime(10, 53))
        assertTrue(forming.reasons.none { "big one" in it }, "${forming.reasons}")
    }

    @Test fun aQuietMiddayIsLowRisk() {
        val now = today.atTime(12, 30)
        // A slow, steady rise all morning: tiny 1-minute moves, at the day's high, above its average price.
        val bars = (0 until (now.toLocalTime().toSecondOfDay() - open.toSecondOfDay()) / 60).map { k ->
            val o = 25000.0 + k * 0.2; val c = o + 0.2
            Candle(today.atTime(open.plusMinutes(k.toLong())), o, c, o, c)
        }
        val r = BigMoveRisk.read(Market.FINNIFTY, past() + bars, emptyList(), now)
        assertEquals(BigMoveRisk.Level.LOW, r.level, "$r")
        assertTrue(r.reasons.any { "quietest" in it }, "${r.reasons}")
        val said = BigMoveRisk.say(r, now)
        saneAnswer(said)
        assertTrue(said.startsWith("Big-move risk for FinNifty now: low"), said)
    }

    @Test fun vixUpSinceTheOpenCounts() {
        val now = today.atTime(12, 30)
        val bars = (0 until 195).map { k -> val o = 25000.0 + k * 0.2; Candle(today.atTime(open.plusMinutes(k.toLong())), o, o + 0.2, o, o + 0.2) }
        val vix = (0 until 195).map { k -> val o = 13.0 + k * 0.01; Candle(today.atTime(open.plusMinutes(k.toLong())), o, o + 0.01, o, o + 0.01) }
        val r = BigMoveRisk.read(Market.NIFTY, past() + bars, vix, now)
        assertTrue(r.reasons.any { "India VIX up since the open" in it }, "${r.reasons}")
        val iv = BigMoveRisk.read(Market.NIFTY, past() + bars, emptyList(), now, ivUp = 1.2)
        assertTrue(iv.reasons.any { "ATM IV up" in it }, "${iv.reasons}")
    }

    @Test fun noDataIsSaidAsUnknown() {
        val noon = today.atTime(11, 0)
        val none = BigMoveRisk.read(Market.NIFTY, emptyList(), emptyList(), noon)
        assertNull(none.level); assertNotNull(none.unknown)
        assertTrue(BigMoveRisk.say(none, noon).contains("too few"), BigMoveRisk.say(none, noon))
        val noToday = BigMoveRisk.read(Market.NIFTY, past(), emptyList(), noon)
        assertNull(noToday.level)
        assertTrue(noToday.unknown!!.contains("don't have today's Nifty candles"), noToday.unknown)
        assertNull(BigMoveRisk.read(Market.NIFTY, past(), emptyList(), noon, tradingDay = false).level)
        assertTrue(BigMoveRisk.read(Market.NIFTY, past() + walk(today, 3), emptyList(), today.atTime(15, 40)).unknown!!.contains("closed"))
        // Candles from the future (after now) are never read: at 11:00 a day file holding the whole day reads to 10:59 only.
        val whole = BigMoveRisk.read(Market.NIFTY, past() + walk(today, 3), emptyList(), noon)
        assertEquals(today.atTime(10, 59), whole.asOf)
    }

    @Test fun aStaleReadSaysSo() {
        val now = today.atTime(13, 0)
        val r = BigMoveRisk.read(Market.NIFTY, past() + walk(today, 5, LocalTime.of(12, 0)), emptyList(), now)
        assertTrue(BigMoveRisk.say(r, now).contains("My last Nifty candle is from 11:59"))
    }

    @Test fun indicesOnly() {
        assertEquals(Market.NIFTY, BigMoveRisk.market(emptyList()))
        assertEquals(Market.SENSEX, BigMoveRisk.market(listOf(Market.SENSEX)))
        assertNull(BigMoveRisk.market(listOf(Market.GOLD)))
    }

    private val ASKED = listOf(
        "is a big move likely now", "is a big move coming", "any chance of a big move right now", "big move aa sakta hai kya",
        "abhi kitna risk hai", "kya abhi bada move aayega", "bada move aane wala hai kya", "volatile hai kya", "is the market volatile right now",
        "is nifty volatile now", "banknifty abhi volatile hai kya", "is the market risky right now", "market risky hai kya abhi",
        "how risky is the market now", "is a sharp move likely in sensex", "finnifty mein abhi kitna risk hai",
    )
    private val NOT_ASKED = listOf(
        "how much risk am i taking", "what are my risk limits", "what is volatility", "what is india vix", "is my position risky",
        "why did nifty make a big move", "how often does a big candle follow through", "will nifty go up or down",
        "what is the expected range today", "how much can nifty move today", "big candle record for banknifty", "is it risky to trade now",
        "what's the risk reward on this", "how volatile was nifty yesterday", "set an alert for a big move",
        // Risk-on / risk-off, appetite, a buy or a sell: not a big candle's chance. "Risk in the market" stays the account's risk read.
        "is it risk on or risk off", "risk on hai kya abhi", "risk off in the market now", "market mein risk appetite kaisa hai",
        "is it risky to sell now", "is it risky to buy calls now", "market mein risk hai kya", "what is the risk in the market now",
    )

    @Test fun theQuestion() {
        for (s in ASKED) assertTrue(BigMoveRisk.asked(s), s)
        for (s in NOT_ASKED) assertFalse(BigMoveRisk.asked(s), s)
    }
}
