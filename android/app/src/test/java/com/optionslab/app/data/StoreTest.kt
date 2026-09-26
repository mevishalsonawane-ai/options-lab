package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Manifest
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The device's market-data store: every file is replaced atomically (temp, sync, rename), a
 * corrupt partition is set aside rather than overwritten, re-harvests converge, and the manifest's
 * read-modify-write never loses a concurrent update.
 */
class StoreTest : RobolectricTest() {
    private val day = LocalDate.of(2025, 10, 15)
    private val expiry = LocalDate.of(2025, 10, 21)

    private fun series(strike: Double, minutes: IntRange, px: Double, right: Right = Right.PE, exp: LocalDate? = expiry): Series {
        val m = minutes.toList().toIntArray()
        return Series(exp, strike, right, 75, m, DoubleArray(m.size) { px + it }, DoubleArray(m.size) { px }, DoubleArray(m.size) { px + 2 },
            DoubleArray(m.size) { px - 2 }, LongArray(m.size) { 10L }, LongArray(m.size) { 100L + it })
    }

    private fun barsDir(u: String) = File(context.filesDir, "bars/$u")

    @Test fun upsertIsAtomicAndLeavesNoTempFile() {
        barsDir("NIFTY").mkdirs()
        val stale = File(barsDir("NIFTY"), "$day.olx.tmp").apply { writeText("half a partition from a crash") }
        Store.upsertDay("NIFTY", day, listOf(series(24_500.0, 555..560, 100.0)))
        assertFalse("the temp file is renamed into place", stale.exists())
        assertEquals(listOf("$day.olx"), barsDir("NIFTY").list()!!.toList())
        assertEquals(6, Store.barSession("NIFTY", day)!!.series.single().size)
        assertEquals(listOf(day), Store.deviceBarDays("NIFTY"))
        Store.upsertDay("NIFTY", day, emptyList())
        assertEquals(6, Store.barSession("NIFTY", day)!!.series.single().size)
    }

    @Test fun reHarvestsConvergeAndNewBarsWin() {
        Store.upsertDay("NIFTY", day, listOf(series(24_500.0, 555..560, 100.0), series(24_600.0, 555..556, 50.0)))
        Store.upsertDay("NIFTY", day, listOf(series(24_500.0, 558..565, 200.0)))
        val s = Store.barSession("NIFTY", day)!!
        assertEquals(2, s.series.size)
        val merged = s.series.single { it.strike == 24_500.0 }
        assertArrayEquals((555..565).toList().toIntArray(), merged.minutes)
        assertEquals("an old minute keeps its bar", 100.0, merged.close[0], 0.0)
        assertEquals("a re-fetched minute takes the new bar", 200.0, merged.close[merged.indexOf(558)], 0.0)
        Store.upsertDay("NIFTY", day, listOf(series(24_500.0, 558..565, 200.0)))
        assertEquals("the same bars again change nothing", 11, Store.barSession("NIFTY", day)!!.series.single { it.strike == 24_500.0 }.size)
    }

    @Test fun aCorruptPartitionIsSetAsideNeverOverwritten() {
        barsDir("NIFTY").mkdirs()
        val f = File(barsDir("NIFTY"), "$day.olx")
        val junk = "power cut mid-write".toByteArray()
        f.writeBytes(junk)
        Store.markHarvested("NIFTY", day, listOf("NSE_FO|1", "NSE_FO|2"))
        Store.upsertDay("NIFTY", day, listOf(series(24_500.0, 555..557, 100.0)))
        val aside = barsDir("NIFTY").listFiles()!!.single { it.name.startsWith("$day.olx.corrupt.") }
        assertArrayEquals("kept byte for byte", junk, aside.readBytes())
        assertEquals(1, Store.barSession("NIFTY", day)!!.series.size)
        assertTrue("the done-list vouched for bars that are gone", Store.harvested("NIFTY", day).isEmpty())
    }

    @Test fun doneListsAreForOneSessionAtATime() {
        Store.markHarvested("NIFTY", day, listOf("a", "b"))
        Store.markHarvested("NIFTY", day, listOf("b", "c", ""))
        assertEquals(setOf("a", "b", "c"), Store.harvested("NIFTY", day))
        Store.markHarvested("NIFTY", day.plusDays(1), listOf("x"))
        assertTrue("an older day's list goes", Store.harvested("NIFTY", day).isEmpty())
        Store.markHarvested("NIFTY", day.plusDays(2), emptyList())
        assertEquals(setOf("x"), Store.harvested("NIFTY", day.plusDays(1)))
        assertFalse(File(context.filesDir, "harvest/NIFTY/${day.plusDays(1)}.done.tmp").exists())
    }

    @Test fun theManifestNeverDowngradesASameDayChain() {
        Store.recordManifest("TESTU", Manifest.Entry(day, 2, 100, Manifest.SAME_DAY, day))
        Store.recordManifest("TESTU", Manifest.Entry(day, 2, 120, Manifest.BACKFILL, day.plusDays(3)))
        val e = Store.manifest("TESTU").single()
        assertEquals(Manifest.SAME_DAY, e.scope)
        assertEquals(day, e.collectedOn)
        assertEquals(120, e.nContracts)
        assertFalse(File(context.filesDir, "manifest_TESTU.csv.tmp").exists())
    }

    @Test fun concurrentManifestUpdatesAreNeverLost() {
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val days = (0 until 40).map { day.minusDays(it.toLong()) }
        try {
            val futures = days.map { d -> pool.submit { go.await(); Store.recordManifest("TESTU", Manifest.Entry(d, 1, 10, Manifest.BACKFILL, day)) } }
            // Readers alongside: never a half-written file.
            val readers = (0 until 4).map { pool.submit { go.await(); repeat(50) { Store.manifest("TESTU") } } }
            go.countDown()
            (futures + readers).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(days.sorted(), Store.manifest("TESTU").map { it.session })
    }

    @Test fun expiryCapturesAndWipe() {
        assertEquals(0, Store.deviceExpirySize(day))
        Store.writeExpiry(Session(day, 75, listOf(series(24_500.0, 900..929, 10.0, exp = day), series(24_400.0, 900..929, 5.0, exp = day))))
        assertEquals(listOf(day), Store.deviceExpiryDays())
        assertEquals(2, Store.deviceExpirySize(day))
        File(context.filesDir, "expiry/NIFTY/${day.plusDays(7)}.olx").writeText("truncated")
        assertEquals("an unreadable capture counts as none", 0, Store.deviceExpirySize(day.plusDays(7)))
        Store.upsertDay("NIFTY", day, listOf(series(24_500.0, 555..557, 100.0)))
        Store.markHarvested("NIFTY", day, listOf("k"))
        Store.recordManifest("NIFTY", Manifest.Entry(day, 1, 1, Manifest.SAME_DAY, day))
        assertTrue(Store.deviceBytes() > 0)
        Store.wipeDeviceData()
        assertTrue(Store.deviceExpiryDays().isEmpty())
        assertTrue(Store.deviceBarDays("NIFTY").isEmpty())
        assertTrue(Store.harvested("NIFTY", day).isEmpty())
        assertEquals(0L, Store.deviceBytes())
        assertNull("the bundled manifest is back", Store.manifest("NIFTY").firstOrNull { it.session == day })
    }
}
