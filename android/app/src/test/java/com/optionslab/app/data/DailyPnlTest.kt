package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** The calendar's day figures: a reading belongs to the account's trading day, not the phone's date. */
class DailyPnlTest : RobolectricTest() {
    private val tue = LocalDate.of(2026, 9, 29)
    private val wed = tue.plusDays(1)

    private fun at(t: LocalDateTime) { Market.testClock = Clock.fixed(t.atZone(IST).toInstant(), IST) }

    @After fun down() { Market.testClock = null }

    @Test fun paperReadingAfterMidnightStaysOnTheDayBefore() {
        at(tue.atTime(15, 20)); DailyPnl.record(false, 589.0, 2)
        at(wed.atTime(0, 40)); DailyPnl.record(false, 589.0, 2)
        at(wed.atTime(2, 59)); DailyPnl.record(false, 589.0, 2)
        val days = DailyPnl.all(false)
        assertEquals(589.0, days.getValue(tue).pnl, 0.0)
        assertNull("no copy of Tuesday on Wednesday", days[wed])
        at(wed.atTime(3, 0)); assertEquals(wed, DailyPnl.sessionDay(false))
    }

    @Test fun zerodhaNothingRecordedBeforeNine() {
        at(wed.atTime(8, 59)); DailyPnl.record(true, 1200.0, -1)
        assertNull(DailyPnl.all(true)[wed])
        at(wed.atTime(9, 30)); DailyPnl.record(true, -150.0, -1)
        assertEquals(-150.0, DailyPnl.all(true).getValue(wed).pnl, 0.0)
    }

    @Test fun olderBuildsCopyOfYesterdayIsHidden() {
        SecurePrefs.put("pnl.days.paper", """{"$tue":[589.0,2],"$wed":[589.0,2]}""")
        at(wed.atTime(9, 42))
        val days = DailyPnl.all(false)
        assertEquals(589.0, days.getValue(tue).pnl, 0.0)
        assertNull(days[wed])
    }

    @Test fun shownBeforeChargesWithTheChargesBeside() {
        at(wed.atTime(10, 0))
        DailyPnl.record(false, 400.0, 2, 180.0)     // paper keeps its figure after charges
        DailyPnl.record(true, 900.0, 3, 75.5)       // Zerodha keeps its own m2m, before them
        val paper = DailyPnl.all(false).getValue(wed)
        assertEquals(580.0, paper.pnl, 0.0)
        assertEquals(180.0, paper.charges, 0.0)
        assertEquals(400.0, paper.net, 0.0)
        assertEquals(900.0, DailyPnl.all(true).getValue(wed).pnl, 0.0)
        DailyPnl.record(true, 950.0, -1)            // a reading with no trades read keeps the charges
        assertEquals(75.5, DailyPnl.all(true).getValue(wed).charges, 0.0)
        assertEquals(950.0, DailyPnl.all(true).getValue(wed).pnl, 0.0)
    }

    /**
     * ANR fix (9 Oct): the same reading writes nothing, and a figure that only moved with the price is kept in memory
     * (readable at once) instead of re-encrypting the whole settings vault on every pass; a flush (the app leaving the
     * screen, the watch ending) writes it, once. A new trade is written straight away, as before.
     */
    @Test fun aFigureThatOnlyMovesIsNotReEncryptedEveryPass() {
        val prefs = java.io.File(context.noBackupFilesDir, "prefs.vault")
        at(wed.atTime(10, 0))
        DailyPnl.record(false, 400.0, 2, 180.0)
        assertTrue(SecurePrefs.flush())
        val written = Vault.writeCount(prefs)
        DailyPnl.record(false, 400.0, 2, 180.0)       // the same reading again
        DailyPnl.record(false, 412.5, 2, 180.0)       // the price moved, nothing else
        DailyPnl.record(false, 398.0, 2, 180.0)
        Thread.sleep(300)
        assertEquals("no encryption per pass", written, Vault.writeCount(prefs))
        assertEquals(578.0, DailyPnl.all(false).getValue(wed).pnl, 0.0)      // readable at once (before charges)
        assertTrue(SecurePrefs.flush())
        assertEquals("one write carries them", written + 1, Vault.writeCount(prefs))
        assertTrue(SecurePrefs.reload())
        assertEquals(578.0, DailyPnl.all(false).getValue(wed).pnl, 0.0)
        // A new trade: on its way to disk at once (in the background), not held for the minute.
        DailyPnl.record(false, 398.0, 3, 200.0)
        Background.await("the new trade written") { Vault.writeCount(prefs) == written + 2 }
        assertTrue(SecurePrefs.reload())
        assertEquals(3, DailyPnl.all(false).getValue(wed).trades)
    }

    @Test fun anIdenticalSettingIsNotWrittenAgain() {
        val prefs = java.io.File(context.noBackupFilesDir, "prefs.vault")
        SecurePrefs.put("t.same", "a")
        val written = Vault.writeCount(prefs)
        SecurePrefs.put("t.same", "a")
        SecurePrefs.putAll(mapOf("t.same" to "a", "t.none" to null))
        SecurePrefs.putAllSoon(mapOf("t.same" to "a"))
        Thread.sleep(200)
        assertEquals(written, Vault.writeCount(prefs))
        SecurePrefs.put("t.n", 5L)
        assertTrue(SecurePrefs.reload())
        val again = Vault.writeCount(prefs)
        SecurePrefs.put("t.n", 5)                    // read back from disk as an Int: the same number
        assertEquals(again, Vault.writeCount(prefs))
        SecurePrefs.put("t.same", "b")
        assertEquals(again + 1, Vault.writeCount(prefs))
    }

    @Test fun anOlderEntryWithNoChargesShowsAsBefore() {
        SecurePrefs.put("pnl.days.paper", """{"$tue":[589.0,2]}""")
        at(wed.atTime(9, 42))
        val day = DailyPnl.all(false).getValue(tue)
        assertEquals(589.0, day.pnl, 0.0)
        assertEquals(0.0, day.charges, 0.0)
    }

    @Test fun resetPaperClearsPaperHistoryAndKeepsZerodha() {
        at(wed.atTime(10, 0))
        DailyPnl.record(false, 300.0, 2); DailyPnl.record(true, 900.0, -1)
        Journal.put("paper:1", "paper note", setOf("Breakout")); Journal.put("kite:9", "live note", emptySet())
        DailyPnl.resetPaper(); Journal.resetPaper()
        assertNull(DailyPnl.all(false)[wed])
        assertEquals(900.0, DailyPnl.all(true).getValue(wed).pnl, 0.0)
        assertNull(Journal.of("paper:1"))
        assertEquals("live note", Journal.of("kite:9")?.note)
    }
}
