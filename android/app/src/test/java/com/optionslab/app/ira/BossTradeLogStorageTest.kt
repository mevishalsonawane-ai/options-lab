package com.optionslab.app.ira

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.BossTrades
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Boss's trade record on disk (review R4 #1): two saves at once never corrupt the vault file, and a file that cannot be
 * read is moved aside - never overwritten by the next save.
 */
class BossTradeLogStorageTest : RobolectricTest() {
    private fun trade(i: Int) = BossTrades.Trade("paper:$i", "NIFTY26OCT25000CE", false, 1_760_000_000_000L + i * 60_000L, BossTrades.Why.LEVEL,
        note = "n$i")

    @Before fun up() {
        BossTradeLog.init(context)
        BossTradeLog.resetForTest()
        checkNotNull(BossTradeLog.fileForTest()).let { f -> f.parentFile?.listFiles()?.forEach { it.delete() } }
    }

    @After fun down() { BossTradeLog.resetForTest() }

    @Test fun concurrentDrainsNeverCorruptTheFile() {
        val seed = (0 until 300).map(::trade)
        BossTradeLog.setForTest(seed)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val threads = (0 until 8).map {
            Thread {
                try { start.await(); repeat(20) { BossTradeLog.drain() } } catch (t: Throwable) { failure.compareAndSet(null, t) }
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(60_000) }
        failure.get()?.let { throw it }
        val f = checkNotNull(BossTradeLog.fileForTest())
        assertTrue(f.isFile)
        // A new process reads exactly what was kept, and nothing was set aside as unreadable.
        BossTradeLog.reloadForTest()
        assertEquals(seed, BossTradeLog.all())
        assertFalse(f.parentFile!!.listFiles()!!.any { it.name.contains(".unreadable.") })
    }

    @Test fun anUnreadableFileIsMovedAsideAndNeverOverwritten() {
        val f = checkNotNull(BossTradeLog.fileForTest())
        f.parentFile?.mkdirs()
        val garbage = ByteArray(64) { (it * 7 + 3).toByte() }
        f.writeBytes(garbage)
        BossTradeLog.reloadForTest()
        assertTrue("an unreadable record reads as empty", BossTradeLog.all().isEmpty())
        val aside = f.parentFile!!.listFiles()!!.single { it.name.startsWith(f.name + ".unreadable.") }
        assertArrayEquals("the unreadable file is kept as it was", garbage, aside.readBytes())
        // The next save writes a fresh file and leaves the one set aside alone.
        BossTradeLog.setForTest(listOf(trade(1)))
        BossTradeLog.drain()
        assertArrayEquals(garbage, aside.readBytes())
        BossTradeLog.reloadForTest()
        assertEquals(listOf(trade(1)), BossTradeLog.all())
    }
}
