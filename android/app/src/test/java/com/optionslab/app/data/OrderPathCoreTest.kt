package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.KiteTicks
import com.optionslab.engine.Right
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The order path's core, line-by-line review G1 (11 Oct): the day's token map and the paper tokens follow the day's download
 * (G1-2), the fast path's paper cover never waits on the paper book's lock (G1-3), the spec cache is safe across lanes (G1-5),
 * the instruments file is written whole (G1-6), and a failing consumer pass never ends the event-driven checks (G1-7).
 * No test here depends on the real date: the old list is stamped with a day long past, the contracts expire on a fixed day.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrderPathCoreTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val expiry = LocalDate.of(2030, 1, 31)
    private val oldSym = "NIFTY30JAN24500PE"
    private val newSym = "NIFTY30JAN24600PE"
    private val file get() = File(context.filesDir, "kite_instruments.json")

    @Before fun up() { kite = FakeKite(); kite.login(live = false) }
    @After fun down() {
        FastPath.resetForTest()
        kite.close()
        assertEquals("a test reached the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun row(token: Long, sym: String, strike: Double) =
        JSONArray().put(token).put(sym).put("NIFTY").put(expiry.toString()).put(strike).put(75).put("PE").put(0.05)

    /** An instruments file from a day long past (never today's), holding [rows]; the day's token map forgotten. */
    private fun oldList(vararg rows: JSONArray) {
        val a = JSONArray(); rows.forEach { a.put(it) }
        file.writeText(JSONObject().put("day", "2000-01-03").put("i", a).toString())
        Broker::class.java.getDeclaredField("tokenMap").apply { isAccessible = true }.set(null, null)
    }

    private fun todaysListHasBoth() {
        kite.instruments += FakeKite.Ins(111, oldSym, "NIFTY", expiry, 24_500.0, "PE", 75)
        kite.instruments += FakeKite.Ins(222, newSym, "NIFTY", expiry, 24_600.0, "PE", 75)
    }

    // ---- G1-2 ---------------------------------------------------------------------------------------------------------

    @Test fun aContractListedSinceTheOldFileHasItsTokenOnceTheDaysListIsDownloaded() {
        oldList(row(111, oldSym, 24_500.0))
        assertEquals(111L, Broker.tokenOf("NFO:$oldSym"))          // the day's map, built from the old file
        assertNull(Broker.tokenOf("NFO:$newSym"))
        todaysListHasBoth()
        runBlocking { Broker.instruments() }
        assertEquals(222L, Broker.tokenOf("NFO:$newSym"))
        assertEquals("memory only, too", 222L, Broker.tokenKnown("NFO:$newSym"))
    }

    @Test fun paperRemembersNotListedOnlyFromTheDaysList() {
        oldList(row(111, oldSym, 24_500.0))
        val c = Paper.Contract(newSym, "NIFTY", expiry, 24_600.0, Right.PE, 75, "NSE_FO|1")
        val m = Paper::class.java.getDeclaredMethod("kiteToken", Paper.Contract::class.java).apply { isAccessible = true }
        assertNull("not in the old list", m.invoke(Paper, c))
        todaysListHasBoth()
        runBlocking { Broker.instruments() }
        assertEquals("looked up again from the day's list", 222L, m.invoke(Paper, c))
    }

    // ---- G1-3 ---------------------------------------------------------------------------------------------------------

    @Test fun theStreamCoverNeverWaitsOnThePaperBooksLock() {
        Paper.wipe()
        assertEquals("not loaded: memory only, not covered", Paper.StreamCover(emptySet(), covered = false, any = false), Paper.streamCover())
        runBlocking { Paper.snapshot() }                                    // the book loaded, as the watch pass does
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        // A paper vault write in progress: the monitor Paper.save holds.
        val writer = Thread { synchronized(Paper) { held.countDown(); release.await() } }.apply { start() }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val cover = pool.submit<Paper.StreamCover> { Paper.streamCover() }.get(2, TimeUnit.SECONDS)
            assertTrue("an empty book is covered", cover.covered)
            assertFalse(cover.any)
        } finally {
            release.countDown(); writer.join(5_000); pool.shutdownNow()
        }
    }

    // ---- G1-5 ---------------------------------------------------------------------------------------------------------

    @Test fun specsAreKeptInOneConcurrentMapPerDay() {
        val syms = (0 until 200).map { "NIFTY30JAN${20_000 + it * 50}PE" }
        oldList(*syms.mapIndexed { i, s -> row(1_000L + i, s, 20_000.0 + i * 50) }.toTypedArray())
        Broker::class.java.getDeclaredField("specsOfDay").apply { isAccessible = true }.set(null, null)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 8).map { k ->
                pool.submit<Int> { syms.indices.count { i -> runBlocking { Broker.spec("NFO", syms[(i + k * 25) % syms.size]) }.token == 1_000L + (i + k * 25) % syms.size } }
            }
            jobs.forEach { assertEquals(syms.size, it.get(30, TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow() }
        val kept = Broker::class.java.getDeclaredField("specsOfDay").apply { isAccessible = true }.get(null) as Pair<*, *>
        assertTrue(kept.second is java.util.concurrent.ConcurrentHashMap<*, *>)
        assertEquals(syms.size, (kept.second as Map<*, *>).size)
    }

    // ---- G1-6 ---------------------------------------------------------------------------------------------------------

    @Test fun aHalfWrittenInstrumentsFileNeverLosesTheListInMemory() {
        todaysListHasBoth()
        file.delete()
        runBlocking { Broker.instruments() }
        val list = Broker.cachedInstruments()
        assertEquals(2, list?.size)
        assertFalse("written through a temp file, renamed", File(context.filesDir, "kite_instruments.json.tmp").exists())
        file.writeText("{\"day\":\"2000-01-03\",\"i\":[[1,")                   // cut off mid-write
        assertEquals(list, Broker.cachedInstruments())
    }

    // ---- G1-7 ---------------------------------------------------------------------------------------------------------

    @Test fun aConsumerPassThatThrowsNeverEndsTheEventDrivenChecks() {
        val passes = AtomicInteger()
        FastPath.faultForTest = { if (passes.incrementAndGet() == 1) throw IllegalStateException("test fault") }
        FastPath.start(context)
        val tick = KiteTicks.Tick(256265L, 24_500.0, bid = null, ask = null, exchangeTime = System.currentTimeMillis() / 1000)
        FastPath.offer(listOf(tick), System.currentTimeMillis())
        awaitTrue("the faulty pass") { passes.get() >= 1 }
        Thread.sleep(400)
        assertTrue("the consumer is still running", FastPath.running)
        FastPath.offer(listOf(tick), System.currentTimeMillis())
        awaitTrue("a pass after the fault") { passes.get() >= 2 }
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(10) }
        throw AssertionError("timed out waiting for $what")
    }
}
