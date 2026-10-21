package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Order speed ([OrderSpeed]): median and 95th percentile, the rolling window, the day's book kept and read back, the card. */
class OrderSpeedTest {
    @Test fun medianAndPercentiles() {
        assertEquals(0, OrderSpeed.median(emptyList()))
        assertEquals(0, OrderSpeed.p95(emptyList()))
        assertEquals(5, OrderSpeed.median(listOf(5)))
        assertEquals(20, OrderSpeed.median(listOf(30, 10, 20)))
        // Even count: the lower middle.
        assertEquals(20, OrderSpeed.median(listOf(40, 10, 30, 20)))
        val hundred = (1L..100L).toList().shuffled(kotlin.random.Random(7))
        assertEquals(95, OrderSpeed.p95(hundred))
        assertEquals(1, OrderSpeed.quantile(hundred, 0.0))
        assertEquals(100, OrderSpeed.quantile(hundred, 1.0))
        assertEquals(100, OrderSpeed.quantile(hundred, 7.0))
        assertEquals(OrderSpeed.Summary(3, 20, 30, 30), OrderSpeed.summary(listOf(10, 20, 30)))
        assertNull(OrderSpeed.summary(emptyList()))
    }

    @Test fun theWindowKeepsTheNewestAndRefusesNonsense() {
        val b = OrderSpeed.Book("2026-10-09")
        assertTrue(b.isEmpty())
        repeat(OrderSpeed.KEEP + 20) { b.add(OrderSpeed.Step.SENT_ACK, true, it.toLong()) }
        val kept = b.of(OrderSpeed.Step.SENT_ACK, true)
        assertEquals(OrderSpeed.KEEP, kept.size)
        assertEquals(20L, kept.first())
        assertFalse(b.add(OrderSpeed.Step.SENT_ACK, true, -1))
        assertFalse(b.add(OrderSpeed.Step.SENT_ACK, true, OrderSpeed.MAX_MS))
        // Only the clock offset may be negative.
        assertTrue(b.add(OrderSpeed.Step.CLOCK_OFFSET, false, -800))
        assertFalse(b.add(OrderSpeed.Step.CLOCK_OFFSET, false, -OrderSpeed.MAX_MS))
        assertEquals(emptyList(), b.of(OrderSpeed.Step.SENT_ACK, false))
        assertFalse(b.isEmpty())
    }

    @Test fun theBookIsKeptAndReadBackForTheSameDayOnly() {
        val b = OrderSpeed.Book("2026-10-09")
        b.add(OrderSpeed.Step.SIGNAL_DECISION, true, 12)
        b.add(OrderSpeed.Step.SIGNAL_DECISION, true, 15)
        b.add(OrderSpeed.Step.SENT_ACK, false, 3)
        b.add(OrderSpeed.Step.CLOCK_OFFSET, false, -250)
        val raw = b.encode()
        assertEquals("2026-10-09|sd:1:12,15;sa:0:3;co:0:-250", raw)
        val back = OrderSpeed.decode(raw, "2026-10-09")
        assertEquals(listOf(12L, 15L), back.of(OrderSpeed.Step.SIGNAL_DECISION, true))
        assertEquals(listOf(3L), back.of(OrderSpeed.Step.SENT_ACK, false))
        assertEquals(listOf(-250L), back.all(OrderSpeed.Step.CLOCK_OFFSET))
        assertTrue(OrderSpeed.decode(raw, "2026-10-10").isEmpty(), "a new day starts empty")
        assertTrue(OrderSpeed.decode(null, "2026-10-09").isEmpty())
        assertTrue(OrderSpeed.decode("garbage", "2026-10-09").isEmpty())
        // Unknown steps, broken parts and numbers are skipped.
        val odd = OrderSpeed.decode("2026-10-09|zz:1:5;sa:1;sa:1:x,7", "2026-10-09")
        assertEquals(listOf(7L), odd.of(OrderSpeed.Step.SENT_ACK, true))
    }

    @Test fun theCardSaysEachStepAndTheWarnings() {
        val b = OrderSpeed.Book("2026-10-09")
        assertEquals(listOf("No timings yet today: they appear with the first order, price or relay check in market hours."), OrderSpeed.lines(b))
        assertEquals(emptyList(), OrderSpeed.warnings(b))
        listOf(40L, 60L, 50L).forEach { b.add(OrderSpeed.Step.SENT_ACK, true, it) }
        b.add(OrderSpeed.Step.SENT_ACK, false, 2)
        listOf(180L, 200L, 170L).forEach { b.add(OrderSpeed.Step.RELAY_PING, true, it) }
        b.add(OrderSpeed.Step.DIRECT_PING, true, 25)
        b.add(OrderSpeed.Step.PRICE_AGE, true, 2_600)
        b.add(OrderSpeed.Step.SIGNAL_SENT, true, 1_400)
        b.add(OrderSpeed.Step.CLOCK_OFFSET, true, -1_500)
        val l = OrderSpeed.lines(b, fillsOnStream = 2, fillsKnown = 3)
        assertTrue("Live · Order sent → Zerodha's answer: p50 50 ms, p95 60 ms, 3 times" in l, l.toString())
        assertTrue("Paper · Order sent → Zerodha's answer: p50 2 ms, p95 2 ms, 1 time" in l, l.toString())
        assertTrue("Live fills first seen on Zerodha's stream: 2 of 3" in l, l.toString())
        assertTrue("Relay round trip: p50 180 ms, p95 200 ms, 3 times" in l, l.toString())
        assertTrue("Ticks arrive after the exchange's stamp: before it by 1.5 s (typical; network delay and Zerodha's batching, not the phone's clock)" in l, l.toString())
        // 16 Oct: the tick arrival figure never warns about the clock (it is not the clock's error).
        val w = OrderSpeed.warnings(b)
        assertEquals(3, w.size, w.toString())
        assertTrue(w[0].startsWith("Relay is slow: typical 180 ms"), w[0])
        assertTrue(w[1].startsWith("Prices were up to 2.6 s old"), w[1])
        assertTrue(w[2].startsWith("Signal to order took up to 1.4 s"), w[2])
        assertTrue(w.none { "automatic date and time" in it }, w.toString())
        val d = OrderSpeed.diagLine(b)
        assertTrue(d.startsWith("Order speed (2026-10-09, typical/worst/slowest): "), d)
        assertTrue("rp 180/200/200ms n3" in d && "WARN Relay is slow" in d, d)
        assertEquals("Order speed (2026-10-09, typical/worst/slowest): no timings yet", OrderSpeed.diagLine(OrderSpeed.Book("2026-10-09")))
        // 12 Oct: each mode's order steps apart, labelled; a paper warning says it is paper's.
        assertTrue("live sa 50/60/60ms n3" in d && "paper sa 2/2/2ms n1" in d, d)
        val paper = OrderSpeed.Book("2026-10-12").also { it.add(OrderSpeed.Step.SIGNAL_SENT, false, 9_863); it.add(OrderSpeed.Step.PRICE_AGE, false, 16_868) }
        val pw = OrderSpeed.warnings(paper)
        assertEquals(2, pw.size, pw.toString())
        assertTrue(pw.all { it.startsWith("Paper (simulated orders): ") }, pw.toString())
        assertTrue("paper ss 9863/9863/9863ms n1" in OrderSpeed.diagLine(paper))
        // The slowest is said only when it differs from the 95th percentile; long times in seconds.
        assertEquals("p50 12 s, p95 12 s, 1 time", OrderSpeed.said(OrderSpeed.Summary(1, 12_000, 12_000, 12_000)))
        assertEquals("p50 10 ms, p95 90 ms (slowest 120 ms), 30 times", OrderSpeed.said(OrderSpeed.Summary(30, 10, 90, 120)))
        // A phone ahead of the exchange.
        val ahead = OrderSpeed.Book("2026-10-09").also { it.add(OrderSpeed.Step.CLOCK_OFFSET, false, 300) }
        assertTrue("Ticks arrive after the exchange's stamp: by 300 ms (typical; network delay and Zerodha's batching, not the phone's clock)" in OrderSpeed.lines(ahead))
    }

    @Test fun theClockIsMeasuredAgainstZerodhasServerWithTheRoundTripAllowedFor() {
        // Sent at 10.000 s, answered at 10.200 s, dated second 10 (its middle 10.500 s): the phone is 400 ms behind.
        assertEquals(-400L, OrderSpeed.offsetFromDate(10_000, 10_200, 10))
        // Ahead: sent 12.900, answered 13.100 (middle 13.000) for a reply dated 11 (11.500): 1.5 s ahead.
        assertEquals(1_500L, OrderSpeed.offsetFromDate(12_900, 13_100, 11))
        // A slow round trip, a reply before its request (a clock jump) or no Date: no sample.
        assertNull(OrderSpeed.offsetFromDate(0, OrderSpeed.CLOCK_RTT_MAX_MS + 1, 0))
        assertNull(OrderSpeed.offsetFromDate(5_000, 4_000, 4))
        assertNull(OrderSpeed.offsetFromDate(5_000, 5_100, 0))
    }

    @Test fun theClockWarnsOnlyWhenItIsReallyOffWithEnoughReadingsAndNeverAsksForAutomaticTime() {
        // Boss's case: ticks arriving 2.5 s after their stamps with the clock right - no warning at all.
        val ok = OrderSpeed.Book("2026-10-16")
        repeat(20) { ok.add(OrderSpeed.Step.CLOCK_OFFSET, false, 2_500) }
        repeat(11) { ok.add(OrderSpeed.Step.CLOCK_SERVER, false, if (it % 2 == 0) 300L else -200L) }
        assertEquals(emptyList(), OrderSpeed.warnings(ok))
        // Within the measurement's own uncertainty (2 s): no warning.
        assertNull(OrderSpeed.clockWarning(List(10) { 1_900L }))
        // Really off, but too few readings: no warning yet.
        assertNull(OrderSpeed.clockWarning(List(OrderSpeed.CLOCK_MIN_N - 1) { 5_000L }))
        // Really off with enough readings: said, ahead or behind, without asking for what Boss already has on.
        val ahead = OrderSpeed.clockWarning(List(OrderSpeed.CLOCK_MIN_N) { 5_000L })!!
        assertTrue(ahead.startsWith("The phone's clock reads 5.0 s ahead of Zerodha's server (median of 5 readings"), ahead)
        assertFalse("switch on" in ahead, ahead)
        val behind = OrderSpeed.clockWarning(List(7) { -3_000L })!!
        assertTrue("3.0 s behind Zerodha's server" in behind, behind)
        assertTrue("Phone clock vs Zerodha's server: phone ahead by 300 ms (median of 11, round trip allowed for)" in OrderSpeed.lines(ok), OrderSpeed.lines(ok).toString())
        // Kept through the book's text like every step (negative values allowed).
        assertTrue(ok.add(OrderSpeed.Step.CLOCK_SERVER, false, -900))
        assertFalse(ok.add(OrderSpeed.Step.CLOCK_SERVER, false, OrderSpeed.MAX_MS))
    }

    @Test fun whereDecisionsCameFromTheOrderPathStepsAndTheStreamsDropsAreCountedAndKept() {
        val b = OrderSpeed.Book("2026-10-09")
        b.decidedFrom(OrderSpeed.Source.STREAM); b.decidedFrom(OrderSpeed.Source.STREAM)
        b.decidedFrom(OrderSpeed.Source.LOCAL_CANDLE)
        b.decidedFrom(OrderSpeed.Source.FEED, 0)
        b.dropped(); b.dropped(2)
        listOf(600L, 900L).forEach { b.add(OrderSpeed.Step.STREAM_GAP, false, it) }
        b.add(OrderSpeed.Step.MARGIN_CHECK, true, 3)
        b.add(OrderSpeed.Step.STOP_CANCEL, true, 45)
        assertFalse(b.isEmpty())
        val raw = b.encode()
        assertEquals("2026-10-09|sg:0:600,900;mc:1:3;sc:1:45;n:s:2;n:l:1;d:3", raw)
        val back = OrderSpeed.decode(raw, "2026-10-09")
        assertEquals(2, back.decidedCount(OrderSpeed.Source.STREAM))
        assertEquals(1, back.decidedCount(OrderSpeed.Source.LOCAL_CANDLE))
        assertEquals(0, back.decidedCount(OrderSpeed.Source.FEED))
        assertEquals(3, back.drops)
        val l = OrderSpeed.lines(back)
        assertTrue("Decided from the stream 2 · from a local candle 1 · from the feed / Zerodha's quote 0" in l, l.toString())
        assertTrue("Price stream drops today: 3 · gap p50 600 ms, p95 900 ms, 2 times" in l, l.toString())
        assertTrue("Live · Margin check (live entry): p50 3 ms, p95 3 ms, 1 time" in l, l.toString())
        assertTrue("Live · Exchange stop cancelled before an exit: p50 45 ms, p95 45 ms, 1 time" in l, l.toString())
        // Counts alone are not "no timings".
        val only = OrderSpeed.Book("2026-10-09").also { it.dropped() }
        assertEquals(listOf("Price stream drops today: 1"), OrderSpeed.lines(only))
        // Broken counts are skipped.
        val odd = OrderSpeed.decode("2026-10-09|n:z:4;n:s:x;d:y;n:f:2", "2026-10-09")
        assertEquals(2, odd.decidedCount(OrderSpeed.Source.FEED)); assertEquals(0, odd.drops)
        assertTrue(OrderSpeed.diagLine(back).contains("mc 3/3/3ms n1"))
    }

    @Test fun theRelayAdviceSaysMumbaiOnlyWhenFar() {
        assertNull(OrderSpeed.relayAdvice(null, "ap-mumbai-1"))
        assertEquals("The relay does not say its region. Its round trip (35 ms) is fine.", OrderSpeed.relayAdvice(35, null))
        val far = OrderSpeed.relayAdvice(140, "us-ashburn-1")!!
        assertTrue(far.startsWith("Relay region: us-ashburn-1. Move the relay to a Mumbai region"), far)
        assertTrue("ap-south-1" in far && "~20–40 ms faster per order" in far, far)
        assertTrue(OrderSpeed.relayAdvice(61, " ")!!.startsWith("The relay does not say its region. Move the relay"))
    }
}
