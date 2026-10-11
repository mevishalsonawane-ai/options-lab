package com.optionslab.ira

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The history vault (TODO S5): the codec's round trip, the cap and its eviction, the read API, and a day's speed. */
class HistoryVaultTest {
    private val dirs = ArrayList<File>()
    private fun tmp(): File = Files.createTempDirectory("vault").toFile().also { dirs += it }
    @AfterTest fun cleanUp() { dirs.forEach { it.deleteRecursively() } }

    private val day = LocalDate.of(2026, 10, 9)
    private fun open(d: LocalDate) = d.atTime(9, 15).atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond()

    /** A session of [n] minutes of [symbol] walking from [start], OI moving both ways. */
    private fun series(symbol: String, start: Double, n: Int = 375, d: LocalDate = day, seed: Int = 1): HistoryCodec.Series {
        val r = java.util.Random(seed.toLong() * 31 + symbol.hashCode())
        var px = start
        var oi = 1_000_000L
        val t0 = open(d)
        return HistoryCodec.Series(symbol, (0 until n).map { i ->
            val o = Math.round(px * 20) / 20.0
            val c = Math.round((o + r.nextGaussian() * start * 0.001) * 20) / 20.0
            val h = Math.round((maxOf(o, c) + 0.05 * r.nextInt(10)) * 100) / 100.0
            val l = Math.round((minOf(o, c) - 0.05 * r.nextInt(10)) * 100) / 100.0
            px = c
            oi += (r.nextInt(2001) - 1000).toLong()
            HistoryCodec.Bar(t0 + 60L * i, o, h, l, c, r.nextInt(50_000).toLong(), oi)
        })
    }

    @Test fun barsRoundTripExactlyToThePaise() {
        val s = listOf(series("NIFTY", 25_123.45), series("INDIAVIX", 11.37), series("NIFTY26OCT25100CE", 143.65, n = 3),
            HistoryCodec.Series("EMPTY", emptyList()))
        val back = HistoryCodec.decodeBars(HistoryCodec.encodeBars(s))
        assertEquals(s, back)
    }

    @Test fun aGapInTheMinutesAndAnUnsortedInputComeBackSorted() {
        val b = listOf(HistoryCodec.Bar(2_000, 10.0, 11.0, 9.5, 10.5, 5, 7), HistoryCodec.Bar(1_000, 9.0, 9.0, 9.0, 9.0, 0, 9))
        val back = HistoryCodec.decodeBars(HistoryCodec.encodeBars(listOf(HistoryCodec.Series("X", b)))).single().bars
        assertEquals(b.sortedBy { it.epochSec }, back)
    }

    @Test fun chainsRoundTripWithAnUnknownIv() {
        val exp = LocalDate.of(2026, 10, 13)
        val c = HistoryCodec.Chain("NIFTY", open(day) + 22_500, 25_101.2, listOf(
            HistoryCodec.ChainRow(exp, 25_100.0, true, 120.5, 4_500_000, 0.1234),
            HistoryCodec.ChainRow(exp, 25_100.0, false, 101.05, 3_100_000, null),
            HistoryCodec.ChainRow(exp.plusDays(7), 25_050.0, true, 190.0, 10, 0.15)))
        val back = HistoryCodec.decodeChains(HistoryCodec.encodeChains(listOf(c))).single()
        assertEquals(c.underlying, back.underlying); assertEquals(c.atEpochSec, back.atEpochSec); assertEquals(c.spot, back.spot, 1e-9)
        assertEquals(c.rows.sortedWith(compareBy({ it.expiry }, { it.strike }, { !it.call })), back.rows)
    }

    @Test fun aDamagedOrForeignFileIsRefused() {
        assertFailsWith<java.io.IOException> { HistoryCodec.decodeBars(HistoryCodec.encodeChains(emptyList())) }
        val gz = java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { z -> z.write("nope!".toByteArray()) } }.toByteArray()
        assertFailsWith<java.io.IOException> { HistoryCodec.decodeBars(gz) }
        val cut = HistoryCodec.encodeBars(listOf(series("NIFTY", 25_000.0))).let { full ->
            val raw = java.util.zip.GZIPInputStream(full.inputStream()).readBytes()
            java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { z -> z.write(raw, 0, raw.size / 2) } }.toByteArray()
        }
        assertFailsWith<java.io.IOException> { HistoryCodec.decodeBars(cut) }
    }

    @Test fun aKeptFileThatCannotBeDecodedIsRemovedSoTheDayIsFetchedAgain() {
        val root = tmp()
        val v = HistoryVault(root)
        v.putBars(HistoryVault.Family.INDEX, day, listOf(series("NIFTY", 25_000.0)))
        v.putChains("NIFTY", day, listOf(HistoryCodec.Chain("NIFTY", 1, 25_000.0, listOf(HistoryCodec.ChainRow(day.plusDays(2), 25_000.0, true, 120.0, 9, 0.12)))))
        assertTrue(v.has(HistoryVault.Family.INDEX, day))
        // Torn by a crash: half of each file.
        for (fam in listOf(HistoryVault.Family.INDEX, HistoryVault.Family.chain("NIFTY"))) {
            val f = File(File(root, day.toString()), fam + HistoryVault.EXT)
            f.writeBytes(f.readBytes().copyOf(f.length().toInt() / 2))
        }
        assertTrue(v.bars("NIFTY", day).isEmpty())
        assertFalse(v.has(HistoryVault.Family.INDEX, day), "removed, so the keeper fetches it again")
        assertNull(v.chain("NIFTY", day))
        assertFalse(v.has(HistoryVault.Family.chain("NIFTY"), day))
        // Fetched again: read as normal.
        v.putBars(HistoryVault.Family.INDEX, day, listOf(series("NIFTY", 25_000.0)))
        assertEquals(375, v.bars("NIFTY", day).size)
        // An empty file is not "kept".
        val empty = File(File(root, day.toString()), HistoryVault.Family.FUTURES + HistoryVault.EXT).also { it.writeBytes(ByteArray(0)) }
        assertTrue(empty.isFile)
        assertFalse(v.has(HistoryVault.Family.FUTURES, day))
    }

    @Test fun theReadApiFindsEachSymbolInItsFamilyAndTheChain() {
        val v = HistoryVault(tmp())
        v.putBars(HistoryVault.Family.INDEX, day, listOf(series("NIFTY", 25_000.0), series("INDIAVIX", 11.0)))
        v.putBars(HistoryVault.Family.FUTURES, day, listOf(series("NIFTY26OCTFUT", 25_080.0)))
        v.putBars(HistoryVault.Family.options("NIFTY"), day, listOf(series("NIFTY26OCT25000CE", 120.0)))
        v.putBars(HistoryVault.Family.options("BANKNIFTY"), day, listOf(series("BANKNIFTY26OCT56000PE", 300.0)))
        v.putBars(HistoryVault.Family.MCX, day, listOf(series("CRUDEOIL26NOVFUT", 5_400.0, n = 900)))
        val exp = LocalDate.of(2026, 10, 13)
        v.putChains("NIFTY", day, listOf(HistoryCodec.Chain("NIFTY", 1, 25_000.0, listOf(HistoryCodec.ChainRow(exp, 25_000.0, true, 120.0, 9, 0.12)))))
        assertEquals(series("NIFTY26OCT25000CE", 120.0).bars, v.bars("nifty26oct25000ce", day))
        assertEquals(series("BANKNIFTY26OCT56000PE", 300.0).bars, v.bars("BANKNIFTY26OCT56000PE", day))
        assertEquals(900, v.bars("CRUDEOIL26NOVFUT", day).size)
        assertEquals(375, v.bars("INDIAVIX", day).size)
        assertTrue(v.bars("SENSEX", day).isEmpty())
        assertTrue(v.bars("NIFTY", day.minusDays(1)).isEmpty())
        assertEquals(0.12, v.chain("NIFTY", day)!!.rows.single().iv!!, 1e-9)
        assertNull(v.chain("BANKNIFTY", day))
        assertTrue(v.has(HistoryVault.Family.MCX, day)); assertFalse(v.has(HistoryVault.Family.MCX, day.plusDays(1)))
        assertEquals(listOf("chain-NIFTY", "fut", "idx", "mcx", "opt-BANKNIFTY", "opt-NIFTY"), v.families(day))
        assertEquals(setOf("NIFTY", "INDIAVIX"), v.symbols(day, HistoryVault.Family.INDEX).toSet())
        assertEquals(6, v.symbols(day).size)
        // Written again: replaced, never appended (the cache too).
        v.putBars(HistoryVault.Family.INDEX, day, listOf(series("NIFTY", 26_000.0, n = 10)))
        assertEquals(10, v.bars("NIFTY", day).size)
        assertTrue(v.bars("INDIAVIX", day).isEmpty())
        assertTrue(v.usageLine().contains("1 day"), v.usageLine())
        assertFailsWith<IllegalArgumentException> { v.putBars("../escape", day, emptyList()) }
    }

    @Test fun theCapDropsWholeOldestDaysFirstAndKeepsTheNewest() {
        val v = HistoryVault(tmp(), capMb = 100)
        assertEquals(100, v.capMb)
        // Shrink the cap below a day's size by asking for less than the minimum: clamped to 100 MB.
        v.setCapMb(5)
        assertEquals(HistoryVault.MIN_CAP_MB, v.capMb)
        v.setCapMb(5000)
        assertEquals(HistoryVault.MAX_CAP_MB, v.capMb)
        // Days of 30 KB each against a 100 KB cap (the test's own small cap).
        val big = HistoryVault(tmp(), capMb = 100)
        big.capBytesForTest(100 * 1024L)
        val days = (0 until 6).map { day.minusDays(10L - it) }
        val chunk = ByteArray(30 * 1024).also { java.util.Random(7).nextBytes(it) }
        for (d in days) {
            File(big.root, d.toString()).mkdirs()
            File(File(big.root, d.toString()), "idx.ihv").writeBytes(chunk)
        }
        assertEquals(6, big.days().size)
        val dropped = big.enforceCap()
        assertEquals(days.take(3), dropped)
        assertEquals(days.drop(3), big.days())
        assertTrue(big.usageBytes() <= big.capBytes)
        // A write enforces the cap and never drops the day it wrote, nor the newest.
        big.putBars(HistoryVault.Family.INDEX, days.first(), listOf(series("NIFTY", 25_000.0)))
        assertTrue(days.first() in big.days())
        assertTrue(days.last() in big.days())
    }

    @Test fun oneDayOverTheCapIsKept() {
        val v = HistoryVault(tmp(), capMb = 100)
        File(v.root, day.toString()).mkdirs()
        File(File(v.root, day.toString()), "idx.ihv").writeBytes(ByteArray(1))
        assertTrue(v.enforceCap().isEmpty())
        assertEquals(listOf(day), v.days())
        assertTrue(HistoryVault(tmp()).usageLine().startsWith("0.0 MB of 300 MB"))
    }

    @Test fun aDayEncodesAndDecodesQuickly() {
        // A full day: 6 indices, 5 futures, 5 indices x 2 expiries x 11 strikes x 2 rights of options, 6 MCX futures.
        val idx = HistoryKeep.INDICES.map { series(it, 25_000.0) }
        val opts = (0 until 220).map { series("OPT$it", 150.0, seed = it) }
        val mcx = (0 until 6).map { series("MCX$it", 5_000.0, n = 900, seed = it) }
        val all = idx + opts + mcx
        repeat(2) { HistoryCodec.decodeBars(HistoryCodec.encodeBars(all)) }        // warm the JIT
        val t0 = System.nanoTime()
        val bytes = HistoryCodec.encodeBars(all)
        val t1 = System.nanoTime()
        val back = HistoryCodec.decodeBars(bytes)
        val t2 = System.nanoTime()
        assertEquals(all, back)
        val encMs = (t1 - t0) / 1e6; val decMs = (t2 - t1) / 1e6
        val bars = all.sumOf { it.bars.size }
        println("HISTORY VAULT: a day of $bars bars -> ${bytes.size / 1024} KB; encode %.0f ms, decode %.0f ms".format(encMs, decMs))
        assertTrue(encMs < 2_000 && decMs < 2_000, "encode $encMs ms, decode $decMs ms")
        // Compact: well under 16 bytes a bar (a raw bar is 7 x 8 = 56 bytes).
        assertTrue(bytes.size < bars * 16, "${bytes.size} bytes for $bars bars")
    }

    @Test fun theKeeperPlansTodayFirstThenTheMissingDaysAndWaitsForMcx() {
        val today = LocalDate.of(2026, 10, 9)
        val days = (0 until 8).map { today.minusDays(it.toLong()) }
        val kept = setOf(HistoryVault.Family.INDEX to today.minusDays(1))
        val p = HistoryKeep.plan(today, 16 * 60, days, { f, d -> (f to d) in kept }, { f, d -> f == "fut" && d == today.minusDays(2) })
        assertEquals(today, p.first().day)
        assertTrue(p.none { it.day == today && it.family == HistoryVault.Family.MCX })
        assertTrue(p.any { it.day == today.minusDays(1) && it.family == HistoryVault.Family.MCX })
        assertTrue(p.none { it.day == today.minusDays(1) && it.family == HistoryVault.Family.INDEX })
        assertTrue(p.none { it.day == today.minusDays(2) && it.family == "fut" })
        assertEquals(HistoryKeep.LOOKBACK, p.map { it.day }.distinct().size)
        assertTrue(HistoryKeep.plan(today, 15 * 60, days, { _, _ -> false }).none { it.day == today })
        assertTrue(HistoryKeep.plan(today, 23 * 60 + 40, days, { _, _ -> false }).any { it.day == today && it.family == "mcx" })
        assertTrue(HistoryKeep.due(15 * 60 + 45, true)); assertFalse(HistoryKeep.due(15 * 60, true)); assertTrue(HistoryKeep.due(600, false))
        assertEquals(13, HistoryKeep.families().size)
    }

    @Test fun theWatchedStrikesAreAtmPlusMinusFive() {
        val listed = (0 until 60).map { 24_000.0 + 50 * it }
        val s = HistoryKeep.strikes(listed.shuffled(java.util.Random(3)), 25_012.0)
        assertEquals(11, s.size)
        assertEquals(25_000.0, s[5])
        assertEquals(6, HistoryKeep.strikes(listed, 23_000.0).size)          // at the edge: what there is
        assertTrue(HistoryKeep.strikes(emptyList(), 25_000.0).isEmpty())
        assertTrue(HistoryKeep.strikes(listed, 0.0).isEmpty())
        val b = HistoryKeep.Budget(1_000, 500)
        assertFalse(b.spent(1_200)); assertTrue(b.spent(1_500)); assertTrue(b.spent(900))
    }
}
