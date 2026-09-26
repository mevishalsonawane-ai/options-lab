package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Live
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The paper ledger and the price alarms, both in the vault. */
class LedgerAlarmsTest : RobolectricTest() {
    private val d1 = LocalDate.of(2026, 1, 6)
    private val d2 = LocalDate.of(2026, 1, 13)
    private fun ticket(day: LocalDate, wing: Double? = null) = Live.Ticket(day, "NIFTY", day, "SELL", "PE", 24_000.0, 75, 1, 75,
        20.0, 24_300.0, 23_980.0, 90_000.0, if (wing != null) 75.0 * 100 else null, wing, if (wing != null) 5.0 else null)

    @Test fun recordSettleDeleteRoundTrip() {
        assertTrue(Ledger.all().isEmpty())
        assertNull(Ledger.openTicket())
        Ledger.record(ticket(d1), "NSE_FO|1", null)
        Ledger.record(ticket(d2, wing = 23_900.0), "NSE_FO|2", "NSE_FO|3")
        val all = Ledger.all()
        assertEquals("newest first", listOf(d2, d1), all.map { it.row.ticket.session })
        assertEquals("NSE_FO|3", all.first().wingKey)
        assertTrue(all.all { it.row.status == "open" && !it.live })
        val settled = Ledger.settle(d1, 24_100.0, "quoted")
        assertEquals("settled", settled.row.status)
        assertTrue("expired worthless: the credit is kept", settled.row.trade!!.won)
        assertEquals(1, Ledger.settledRows().size)
        assertEquals(d2, Ledger.openTicket()!!.row.ticket.session)
        // Read back from the vault as written.
        assertEquals(Ledger.all(), Ledger.all())
        assertEquals(24_100.0, Ledger.all().first { it.row.ticket.session == d1 }.row.settlement!!, 0.0)
        Ledger.delete(d2)
        assertEquals(listOf(d1), Ledger.all().map { it.row.ticket.session })
    }

    @Test fun aSessionIsRecordedOnceAndOnlyAnOpenOneSettles() {
        Ledger.record(ticket(d1), null, null)
        try { Ledger.record(ticket(d1), null, null); fail("recorded twice") } catch (_: Live.AlreadyRecorded) {}
        try { Ledger.settle(d2, 24_000.0, "quoted"); fail() } catch (_: Live.NotRecorded) {}
        assertEquals(1, Ledger.all().size)
    }

    @Test fun sentOrdersTakeTheActualFills() {
        Ledger.record(ticket(d1, wing = 23_900.0), null, null)
        Ledger.attachOrders(d1, listOf("250106000000001", "250106000000002"), shortFill = 22.0, wingFill = 4.0)
        val e = Ledger.all().single()
        assertTrue(e.live)
        assertEquals(18.0, e.row.ticket.credit, 1e-9)
        assertEquals(4.0, e.row.ticket.wingDebit!!, 1e-9)
        assertEquals(24_000.0 - 18.0, e.row.ticket.breakeven, 1e-9)
        val csv = Ledger.csv().lines()
        assertTrue(csv.first().startsWith("session,underlying"))
        assertTrue(csv[1].endsWith(",false,250106000000001 250106000000002"))
    }

    @Test fun anUnreadableLedgerIsSetAsideNeverOverwritten() {
        val f = File(context.noBackupFilesDir, "ledger.vault")
        f.writeBytes("not a vault file".toByteArray())
        assertTrue(Ledger.all().isEmpty())
        assertFalse(f.exists())
        assertTrue("the bad file is kept aside", context.noBackupFilesDir.listFiles()!!.any { it.name.startsWith("ledger.vault") && it != f })
        Ledger.record(ticket(d1), null, null)
        assertEquals(1, Ledger.all().size)
    }

    private fun alarm(id: Long, level: Double = 24_000.0) = PriceAlarm(id, "NIFTY", above = false, level = level, note = "test note")

    @Test fun alarmsRoundTripUpsertAndRemove() {
        assertTrue(Alarms.all().isEmpty())
        Alarms.upsert(alarm(1)); Alarms.upsert(alarm(2).copy(symbol = "NSE:INFY", above = true, enabled = false))
        assertEquals(listOf(alarm(1), alarm(2).copy(symbol = "NSE:INFY", above = true, enabled = false)), Alarms.all())
        Alarms.upsert(alarm(1, level = 23_500.0))
        assertEquals(2, Alarms.all().size)
        assertEquals(23_500.0, Alarms.all().first { it.id == 1L }.level, 0.0)
        Alarms.remove(2)
        assertEquals(listOf(1L), Alarms.all().map { it.id })
    }

    @Test fun markFiredKeepsAnEditMadeMeanwhile() {
        Alarms.upsert(alarm(7))
        val seenByTheWatch = Alarms.all().single()
        // The owner edits the alarm while the watch is deciding it rang.
        Alarms.upsert(seenByTheWatch.copy(level = 23_000.0, note = "edited"))
        Alarms.markFired(seenByTheWatch.id, 1_767_600_000_000L)
        val now = Alarms.all().single()
        assertEquals(23_000.0, now.level, 0.0)
        assertEquals("edited", now.note)
        assertEquals(1_767_600_000_000L, now.firedAtMillis)
        // Marking an alarm that was removed meanwhile does not bring it back.
        Alarms.remove(7)
        Alarms.markFired(7, 1L)
        assertTrue(Alarms.all().isEmpty())
    }

    @Test fun alarmWordsAndTriggers() {
        val below = alarm(1)
        assertTrue(below.hit(24_000.0)); assertTrue(below.hit(23_999.95)); assertFalse(below.hit(24_000.05))
        val above = below.copy(above = true)
        assertTrue(above.hit(24_000.0)); assertFalse(above.hit(23_999.95))
        assertEquals("NIFTY falls below 24,000.00", below.describe())
        assertEquals("BANKNIFTY rises above 51,000.50", PriceAlarm(3, PriceAlarm.CHART + "BANKNIFTY", true, 51_000.5).describe())
    }

    @Test fun anUnreadableAlarmsFileIsSetAside() {
        val f = File(context.noBackupFilesDir, "alarms.vault")
        f.writeBytes(ByteArray(64) { 7 })
        assertTrue(Alarms.all().isEmpty())
        assertFalse(f.exists())
        Alarms.upsert(alarm(1))
        assertEquals(1, Alarms.all().size)
    }
}
