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

    @Test fun aChangeBeforeTheSpecialistsFileIsReadIsReplayedOntoIt() {
        // Review G4 M2: Boss's mute of OI on Pine is in the file (its frozen live book too). At a cold start Autopilot mutes VWAP
        // before the file is read: the file's book is kept and the new mute added - never the file thrown away for it.
        try {
            ManagerSpecialists.reloadForTest(context)
            ManagerSpecialists.mute("pine", "oi", by = "you")
            ManagerSpecialists.save()
            ManagerSpecialists.unloadForTest(context)
            ManagerSpecialists.mute("pine", "vwap", by = "Autopilot")
            ManagerSpecialists.load()
            val b = ManagerSpecialists.book.value
            assertTrue("Boss's frozen live mute kept", b.frozen["pine"]?.muted?.containsKey("oi") == true)
            assertTrue(b.frozen["pine"]?.muted?.containsKey("vwap") == true)
            assertTrue(b.tuned["pine"]?.muted?.keys == setOf("oi", "vwap"))
            // And both written: read back as a new process would.
            ManagerSpecialists.save()
            ManagerSpecialists.reloadForTest(context)
            assertEquals(setOf("oi", "vwap"), ManagerSpecialists.book.value.frozen["pine"]?.muted?.keys)
        } finally {
            ManagerSpecialists.resetForTest()
        }
    }

    @Test fun aModeSetBeforeTheStoredModesAreReadNeverWritesOverThem() {
        // Review G4 M3: Boss set Solo's paper trades OFF. Before his modes are read (the vault not readable yet), a new Solo
        // paper trade is not taken on the default ACT, and a mode change is refused rather than written over his.
        com.optionslab.app.security.SecurePrefs.putAll(mapOf("trademanager.policy" to "solo.paper=OFF", "trademanager.policy.perarm" to "1"))
        TradeManagerHost.unloadPolicyForTest()
        TradeManagerHost.testPolicyRead = { error("the settings vault could not be read") }
        val solo = TradeManager.ManagedTrade("solo:x", "s1", "Solo", "NIFTY", 1, "NIFTY25OCT25000CE",
            100.0, 75, 70.0, 160.0, TradeManager.Caps(t0 + 3 * 3_600_000L), TradeManager.Account.PAPER, t0, 1.0)
        assertEquals(TradeManager.Mode.OFF, TradeManagerHost.attach(TradeManagerHost.Registration(solo, premium = { 100.0 })))
        assertTrue(TradeManagerHost.records.value.isEmpty())
        assertEquals(TradeManagerHost.NOT_READ, TradeManagerHost.setMode("pine", TradeManager.Account.PAPER, TradeManager.Mode.SHADOW))
        assertEquals("solo.paper=OFF", com.optionslab.app.security.SecurePrefs.getString("trademanager.policy"))
        // Readable: his modes read first, then the change on top of them.
        TradeManagerHost.testPolicyRead = null
        TradeManagerHost.setMode("pine", TradeManager.Account.PAPER, TradeManager.Mode.SHADOW)
        assertEquals(TradeManager.Mode.OFF, TradeManagerHost.policy.value.mode("solo", TradeManager.Account.PAPER))
        assertEquals(TradeManager.Mode.SHADOW, TradeManagerHost.policy.value.mode("pine", TradeManager.Account.PAPER))
        assertEquals(TradeManager.Mode.OFF, TradeManagerHost.attach(TradeManagerHost.Registration(solo, premium = { 100.0 })))
    }

    @Test fun aRecordThatCannotBeReadIsKeptAsideNotDroppedForGood() {
        // Review G4 L7: one record in records.json that cannot be read - the rest are read, and the file is set aside as it
        // was (with that record), never rewritten without it.
        TradeManagerHost.reloadForTest(context)
        TradeManagerHost.recordsForTest(listOf(closed("a")))
        TradeManagerHost.save()
        val f = checkNotNull(TradeManagerHost.fileForTest())
        val a = org.json.JSONArray(f.readText())
        a.put(org.json.JSONObject().put("tid", "broken"))
        val text = a.toString()
        f.writeText(text)
        TradeManagerHost.reloadForTest(context)
        assertEquals(listOf("a"), TradeManagerHost.records.value.map { it.trade.tradeId })
        val aside = f.parentFile!!.listFiles()!!.single { it.name.startsWith("records.json.unreadable.") }
        assertEquals(text, aside.readText())
        TradeManagerHost.save()
        assertEquals("the file set aside is left alone", text, aside.readText())
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
