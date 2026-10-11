package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.TradeManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The trade manager's files and memory (review R4 #3, #4): an unreadable specialists.json or records.json is moved aside,
 * never overwritten by the next save; the records in memory are trimmed to [TradeManagerHost.KEEP], oldest closed first,
 * never an open trade.
 */
class ManagerStorageTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L

    private fun trade(id: String) = TradeManager.ManagedTrade("pine:1", id, "Pine · Level", "NIFTY", 1, "NIFTY25OCT25000CE",
        100.0, 75, 70.0, 160.0, TradeManager.Caps(t0 + 3 * 3_600_000L), TradeManager.Account.PAPER, t0, 1.0)

    private fun closed(id: String) = TradeManager.Record(trade(id), TradeManager.Mode.ACT, actual = TradeManager.Exit(t0, 110.0, "x"))

    @Before fun up() {
        TradeManagerHost.resetForTest()
        TradeManagerHost.testNoWake = true
    }

    @After fun down() {
        TradeManagerHost.resetForTest()
    }

    @Test fun aCorruptSpecialistsFileIsMovedAsideNotOverwritten() {
        ManagerSpecialists.reloadForTest(context)
        val f = checkNotNull(ManagerSpecialists.fileForTest())
        f.parentFile?.mkdirs()
        f.writeText("{\"tuned\": {cut short")
        ManagerSpecialists.reloadForTest(context)
        assertFalse("the corrupt file is no longer in the way", f.exists())
        val aside = f.parentFile!!.listFiles()!!.single { it.name.startsWith("specialists.json.unreadable.") }
        assertEquals("{\"tuned\": {cut short", aside.readText())
        ManagerSpecialists.save()
        assertTrue(f.isFile)
        assertEquals("the file set aside is left alone", "{\"tuned\": {cut short", aside.readText())
    }

    @Test fun aCorruptRecordsFileIsMovedAsideNotOverwritten() {
        TradeManagerHost.reloadForTest(context)
        val f = checkNotNull(TradeManagerHost.fileForTest())
        f.parentFile?.mkdirs()
        f.writeText("[{\"sid\": cut short")
        TradeManagerHost.reloadForTest(context)
        assertFalse(f.exists())
        val aside = f.parentFile!!.listFiles()!!.single { it.name.startsWith("records.json.unreadable.") }
        TradeManagerHost.recordsForTest(listOf(closed("a")))
        TradeManagerHost.save()
        assertEquals("[{\"sid\": cut short", aside.readText())
        TradeManagerHost.reloadForTest(context)
        assertEquals(listOf("a"), TradeManagerHost.records.value.map { it.trade.tradeId })
    }

    @Test fun theRecordsInMemoryAreTrimmedToKeepOldestClosedFirst() {
        val open = TradeManager.Record(trade("open-oldest"), TradeManager.Mode.ACT)
        val rs = listOf(open) + (1..TradeManagerHost.KEEP + 50).map { closed("c$it") }
        TradeManagerHost.recordsForTest(rs)
        val kept = TradeManagerHost.records.value
        assertEquals(TradeManagerHost.KEEP, kept.size)
        assertEquals("an open trade is never dropped", "open-oldest", kept.first().trade.tradeId)
        assertFalse("the oldest closed go first", kept.any { it.trade.tradeId == "c51" })
        assertEquals("c52", kept[1].trade.tradeId)
        assertEquals("c${TradeManagerHost.KEEP + 50}", kept.last().trade.tradeId)
    }
}
