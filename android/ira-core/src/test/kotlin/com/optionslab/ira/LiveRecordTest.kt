package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveRecordTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val expiry = LocalDate.of(2026, 10, 28)
    private val now = LocalDateTime.of(2026, 10, 12, 11, 0)

    private fun raws(spot: Double, step: Double = 100.0, n: Int = 30): List<LiveRecord.Raw> = (-n..n).flatMap { i ->
        val k = spot + i * step
        val ce = maxOf(spot - k, 0.0) + 300.0 / (1 + kotlin.math.abs(i))
        val pe = maxOf(k - spot, 0.0) + 300.0 / (1 + kotlin.math.abs(i))
        listOf(LiveRecord.Raw(k, "CE", ce, 1_000L + i, 500, "tick"), LiveRecord.Raw(k, "PE", pe, 2_000L + i, 500, "tick"))
    }

    @Test fun theChainKeepsTenStrikesEachSideWithBothRightsSorted() {
        val legs = LiveRecord.chain(raws(52_000.0), 52_030.0, now, expiry)
        assertEquals(21 * 2, legs.size)
        assertEquals(51_000.0, legs.first().strike)
        assertEquals(53_000.0, legs.last().strike)
        assertEquals(listOf("CE", "PE"), legs.take(2).map { it.right })
        assertTrue(legs.all { it.iv != null && it.iv!! in 0.01..3.0 }, legs.filter { it.iv == null }.toString())
    }

    @Test fun oiChangeIsFromTheFirstReadingToday() {
        val legs = LiveRecord.chain(raws(52_000.0, n = 2), 52_000.0, now, expiry, firstOi = { k, r -> if (r == "CE") 900L to "09:16" else null })
        val ce = legs.first { it.right == "CE" && it.strike == 52_000.0 }
        assertEquals(100L, ce.oiChange)
        assertEquals("09:16", ce.oiFrom)
        assertNull(legs.first { it.right == "PE" }.oiChange)
    }

    @Test fun noSpotOrNoPricesIsAnEmptyChain() {
        assertTrue(LiveRecord.chain(raws(52_000.0), null, now, expiry).isEmpty())
        assertTrue(LiveRecord.chain(emptyList(), 52_000.0, now, expiry).isEmpty())
    }

    @Test fun theForwardIsParityElseSpotWithTheRate() {
        val r = listOf(LiveRecord.Raw(52_000.0, "CE", 400.0, null, 0, "tick"), LiveRecord.Raw(52_000.0, "PE", 300.0, null, 0, "tick"))
        assertEquals(52_100.0, LiveRecord.forward(r, 52_010.0, 0.05)!!, 1e-9)
        // A stale put: no parity, the rate's forward.
        val stale = listOf(r[0], r[1].copy(ageMs = LiveRecord.FRESH_MS + 1))
        assertEquals(52_010.0 * kotlin.math.exp(LiveRecord.RATE * 0.05), LiveRecord.forward(stale, 52_010.0, 0.05)!!, 1e-6)
        assertNull(LiveRecord.forward(r, null, 0.05))
    }

    @Test fun ivIsNullAtOrPastExpiry() {
        assertNull(LiveRecord.iv(100.0, "CE", 52_000.0, 52_000.0, expiry.atTime(15, 30), expiry))
        assertEquals(0.0, LiveRecord.years(expiry.atTime(16, 0), expiry))
    }

    @Test fun optionSymbolsAreRead() {
        assertEquals(Triple("BANKNIFTY", 52_000.0, "CE"), LiveRecord.parseOption("BANKNIFTY26OCT52000CE"))
        assertEquals(Triple("NIFTY", 25_150.0, "PE"), LiveRecord.parseOption("NIFTY2610825150PE"))
        assertEquals(Triple("BANKNIFTY", 52_000.0, "PE"), LiveRecord.parseOption("BANKNIFTY-TEST-52000PE"))
        assertEquals(Triple("FINNIFTY", 24_000.0, "CE"), LiveRecord.parseOption("FINNIFTY26OCT24000CE"))
        assertNull(LiveRecord.parseOption("BANKNIFTY26OCTFUT"))
        assertNull(LiveRecord.parseOption("CRUDEOIL26NOV6000CE"))
    }

    @Test fun aWeeklyExpiryDayEndingInOneNeverRunsIntoTheStrike() {
        // 18 Oct, review: days 01, 11, 21 and 31 read 1|25000 as 125000; the expiry's code is now read as itself.
        for (dd in listOf("01", "11", "21", "31")) {
            assertEquals(Triple("NIFTY", 25_000.0, "CE"), LiveRecord.parseOption("NIFTY26O${dd}25000CE"), dd)
            assertEquals(Triple("NIFTY", 25_000.0, "PE"), LiveRecord.parseOption("NIFTY261${dd}25000PE"), dd)
            assertEquals(Triple("BANKNIFTY", 52_000.0, "CE"), LiveRecord.parseOption("BANKNIFTY26N${dd}52000CE"), dd)
            assertEquals(Triple("SENSEX", 82_000.0, "PE"), LiveRecord.parseOption("SENSEX26D${dd}82000PE"), dd)
        }
        // Other weeklies, a half strike, a lower-case symbol and the monthlies still read.
        assertEquals(Triple("NIFTY", 25_150.0, "CE"), LiveRecord.parseOption("NIFTY26O2025150CE"))
        assertEquals(Triple("MIDCPNIFTY", 12_525.0, "CE"), LiveRecord.parseOption("MIDCPNIFTY2692812525CE"))
        assertEquals(Triple("NIFTY", 24_950.5, "CE"), LiveRecord.parseOption("NIFTY26O2124950.5CE"))
        assertEquals(Triple("NIFTY", 25_000.0, "CE"), LiveRecord.parseOption("nifty26o2125000ce"))
        assertEquals(Triple("NIFTY", 25_000.0, "PE"), LiveRecord.parseOption("NIFTY26DEC25000PE"))
        // A strike of 0 in the code is no strike: the old rule decides (nothing a multiple of 50 here: null).
        assertNull(LiveRecord.parseOption("NIFTY26O210CE"))
    }

    @Test fun linesAreOneJsonObjectWithTheSchema() {
        val h = LiveRecord.header(LiveRecord.MARKET_FILE, day)
        assertTrue(h.startsWith("{\"t\":\"H\",\"schema\":1,"), h)
        assertTrue("\"day\":\"2026-10-12\"" in h)
        val m = LiveRecord.minute(LiveRecord.Minute(LocalTime.of(9, 16), "NIFTY", LiveRecord.Bar(25_000.0, 25_010.5, 24_990.0, 25_005.0),
            25_005.0, 25_050.0, 11.2, expiry, listOf(LiveRecord.Leg(25_000.0, "CE", 120.5, 10L, 2L, 0.1234, 300, "tick")), listOf("VIX")))
        assertEquals("{\"t\":\"M\",\"m\":\"09:16\",\"ix\":\"NIFTY\",\"o\":25000,\"h\":25010.5,\"l\":24990,\"c\":25005,\"spot\":25005," +
            "\"fut\":25050,\"basis\":45,\"vix\":11.2,\"expiry\":\"2026-10-28\",\"chain\":[[25000,\"CE\",120.5,10,2,0.1234,300,\"tick\"]]," +
            "\"missing\":[\"VIX\"]}", m)
    }

    @Test fun aSnapshotCarriesTheBookAndTiming() {
        val s = LiveRecord.Snapshot(LiveRecord.Kind.FILL, 1_000L, "Liquidity \"15+5\"", "BANKNIFTY26OCT52000CE", "BANKNIFTY", "BUY", 30,
            201.5, 200.0, paper = true, exit = false, spot = 52_010.0, future = null, vix = 12.0, expiry = expiry, chain = emptyList(),
            book = LiveRecord.Book(201.0, 200.95, 201.05, 750, 600, listOf(LiveRecord.Level(200.95, 750, 3)), emptyList(), 120),
            timing = LiveRecord.Timing(decidedMs = 900, sentMs = 950, filledMs = 1_000))
        val j = LiveRecord.snapshot(s)
        assertTrue(j.startsWith("{\"t\":\"F\",\"at\":1000,\"arm\":\"Liquidity \\\"15+5\\\"\""), j)
        assertTrue("\"bids\":[[200.95,750,3]],\"asks\":[]" in j, j)
        assertTrue("\"decided\":900,\"sent\":950,\"filled\":1000" in j, j)
        assertFalse("\"fut\"" in j)
        assertFalse("basis" in j)
    }

    @Test fun stringsAreEscaped() {
        assertEquals("\"a\\nb\\u0001\"", LiveRecord.str("a\nb\u0001"))
        assertEquals("null", LiveRecord.num(Double.NaN))
    }

    @Test fun rotationKeepsTheSettingsDaysAndNeverToday() {
        val days = (0 until 100).map { LiveRecord.DayDir(day.minusDays(it.toLong()), 1_000L) }
        val p = LiveRecord.rotate(days, day, 60)
        assertEquals(40, p.delete.size)
        assertEquals(day.minusDays(99), p.delete.first())
        assertEquals(day.minusDays(60), p.delete.last())
        assertEquals(60_000L, p.keptBytes)
        // Over the cap: the oldest go, today never.
        val big = listOf(LiveRecord.DayDir(day.minusDays(2), 600), LiveRecord.DayDir(day.minusDays(1), 600), LiveRecord.DayDir(day, 900))
        val q = LiveRecord.rotate(big, day, 60, cap = 1_000)
        assertEquals(listOf(day.minusDays(2), day.minusDays(1)), q.delete)
        assertEquals(900L, q.keptBytes)
    }

    @Test fun retentionChoicesFallBackToTheDefault() {
        assertEquals(60, LiveRecord.keepDays(null))
        assertEquals(90, LiveRecord.keepDays(90))
        assertEquals(60, LiveRecord.keepDays(7))
    }

    /** Sizing: a full day of minute rows for both indices stays far inside the 20 MB budget once gzipped. */
    @Test fun aFullDayIsWellInsideTheBudget() {
        val bytes = java.io.ByteArrayOutputStream()
        val rnd = java.util.Random(7)
        // One gzip member a minute, as the app writes.
        for (m in 0 until 375) {
            val t = LocalTime.of(9, 15).plusMinutes(m.toLong())
            val lines = LiveRecord.MINUTE_INDICES.joinToString("") { ix ->
                val spot = (if (ix == "NIFTY") 25_000.0 else 52_000.0) + rnd.nextGaussian() * 50
                val legs = LiveRecord.chain(raws(spot, if (ix == "NIFTY") 50.0 else 100.0).map { it.copy(ltp = it.ltp + rnd.nextDouble(), oi = rnd.nextInt(5_000_000).toLong()) },
                    spot, LocalDate.of(2026, 10, 12).atTime(t), expiry, firstOi = { _, _ -> 1_000_000L to "09:15" })
                LiveRecord.minute(LiveRecord.Minute(t, ix, LiveRecord.Bar(spot, spot + 5, spot - 5, spot), spot, spot + 40, 12.0, expiry, legs)) + "\n"
            }
            java.util.zip.GZIPOutputStream(bytes).use { it.write(lines.toByteArray()) }.let { }
            // (GZIPOutputStream.close closed the ByteArrayOutputStream, which stays writable.)
        }
        println("A day of minute rows, gzipped: ${bytes.size()} bytes")
        assertTrue(bytes.size() < LiveRecord.DAY_BUDGET_BYTES / 4, "${bytes.size()}")
        assertFalse(LiveRecord.overDayBudget(bytes.size().toLong()))
        assertTrue(LiveRecord.overDayBudget(LiveRecord.DAY_BUDGET_BYTES))
        // And the members read back as one stream.
        val back = java.util.zip.GZIPInputStream(bytes.toByteArray().inputStream()).bufferedReader().readLines()
        assertEquals(375 * 2, back.size)
    }

    @Test fun theQueueDropsTheOldestPastItsCap() {
        val q = LiveRecord.Queue<Int>(3)
        (1..5).forEach { q.offer(it) }
        assertEquals(2L, q.dropped)
        assertEquals(listOf(3, 4, 5), q.drain())
        assertEquals(0, q.size())
    }

    @Test fun stalledOnlyWhileOpenWatchedAndOn() {
        val t = 10_000_000L
        assertTrue(LiveRecord.stalled(true, true, true, t - LiveRecord.STALL_MS - 1, 0, t))
        assertFalse(LiveRecord.stalled(true, true, true, t - 60_000, 0, t))
        assertFalse(LiveRecord.stalled(false, true, true, null, 0, t))
        assertFalse(LiveRecord.stalled(true, false, true, null, 0, t))
        assertFalse(LiveRecord.stalled(true, true, false, null, 0, t))
        // Just started recording: not stalled yet.
        assertFalse(LiveRecord.stalled(true, true, true, null, t - 60_000, t))
    }

    @Test fun theCardSaysDaysSizeAndLastWrite() {
        val lines = LiveRecord.card(LiveRecord.Status(3, 2_500_000, 1_000_000L - 5 * 60_000, 60, dropped = 2), 1_000_000L)
        assertEquals("3 (keeps 60 days)", lines[0].second)
        assertEquals("2.5 MB", lines[1].second)
        assertEquals("5 min ago", lines[2].second)
        assertEquals("2", lines[3].second)
        assertEquals("never", LiveRecord.card(LiveRecord.Status(0, 0, null, 60), 0)[2].second)
        assertNotNull(LiveRecord.README.lines().firstOrNull { "schema 1" in it })
    }
}
