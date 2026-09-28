package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AppSettings through the settings vault: round trip, migrations, and what "live" implies. */
class SettingsTest : RobolectricTest() {
    @Test fun aFreshPhoneLoadsTheDefaults() {
        assertEquals(AppSettings(), AppSettings.load())
        assertEquals("sandbox", AppSettings.load().mode)
        assertFalse(AppSettings.load().allowRealOrders)
    }

    @Test fun everythingSavedLoadsBack() {
        val s = AppSettings(otmPct = 0.031, entry = "10:45", wingPct = 0.05, capital = 250_000.0, ticketLots = 3,
            idleSeconds = 60, theme = "dark", guardKill = true, guardDailyLoss = 1_500.0, guardCutoff = -1,
            guardPaperTrades = 40, otherAlerts = true, orderProduct = "MIS", reduceMotion = true)
        AppSettings.save(s)
        assertTrue(SecurePrefs.reload())
        assertEquals(s, AppSettings.load())
    }

    @Test fun noWingIsStoredAsMinusOneAndReadBackAsNull() {
        AppSettings.save(AppSettings(wingPct = null))
        assertEquals(-1.0, SecurePrefs.getDouble("s.wing", 0.0), 0.0)
        assertNull(AppSettings.load().wingPct)
    }

    @Test fun liveModeAlwaysAllowsRealOrders() {
        SecurePrefs.putAll(mapOf("k.mode" to "live", "k.allow" to false))
        val s = AppSettings.load()
        assertTrue(s.live)
        assertTrue(s.allowRealOrders)
    }

    @Test fun oldUntouchedGuardDefaultsMoveToTheDesktopOnes() {
        // Saved before settings v2: a max value of 5 lakh and a 14:30 cut-off were the old defaults.
        SecurePrefs.putAll(mapOf("g.value" to 500_000.0, "g.cutoff" to 14 * 60 + 30))
        val migrated = AppSettings.load()
        assertEquals(200_000.0, migrated.guardMaxValue, 0.0)
        assertEquals(14 * 60 + 55, migrated.guardCutoff)
        // Once saved as v2 the owner's own choice of the same numbers stands.
        AppSettings.save(migrated.copy(guardMaxValue = 500_000.0, guardCutoff = 14 * 60 + 30))
        val kept = AppSettings.load()
        assertEquals(500_000.0, kept.guardMaxValue, 0.0)
        assertEquals(14 * 60 + 30, kept.guardCutoff)
    }

    @Test fun aBadEntryTimeFallsBackToTheDefaultMinute() {
        assertEquals(AppSettings().entryMinute, AppSettings(entry = "not a time").entryMinute)
        assertEquals(10 * 60 + 45, AppSettings(entry = "10:45").entryMinute)
    }
}
