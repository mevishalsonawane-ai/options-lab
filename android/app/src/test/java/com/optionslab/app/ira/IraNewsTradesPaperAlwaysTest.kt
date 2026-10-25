package com.optionslab.app.ira

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.JarvisTrades
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 22 Oct (arms audit; CLAUDE.md: "Jarvis's own trades stay on paper"): with the app in Live, paper-first switched off and
 * Jarvis's own paper record proven (20 closed, making money), its own trade ideas still go on paper - the record no longer
 * earns them Zerodha. What it says about its trades says so.
 */
class IraNewsTradesPaperAlwaysTest : RobolectricTest() {
    private fun provenRecord() = IraNewsTrades.save(List(JarvisTrades.PROVEN_TRADES) { i ->
        IraNewsTrades.Pos("NIFTY26OCT25000CE", false, 100.0, 65, 110.0, 70.0, "news: test $i", "2026-10-1${i % 9}", closed = true, result = 500.0)
    })

    @Test fun ownIdeasStayOnPaperEvenInLiveWithAProvenRecord() {
        AutomationSupport.liveSettings()
        SecurePrefs.put(IraNewsTrades.PAPER_KEY, false)
        provenRecord()
        assertFalse(IraNewsTrades.paperFirst)
        assertNull("the record itself is proven", JarvisTrades.proven(IraNewsTrades.closedRecord()))
        assertFalse("a non-Solo idea never goes to Zerodha", IraNewsTrades.goesLive(false))
        val said = IraNewsTrades.record()
        assertTrue(said, said.contains("on paper, always"))
        assertTrue(said, said.contains(IraNewsTrades.OWN_TRADES_PAPER))
        assertFalse(said, said.contains("earned live trading"))
    }

    @Test fun inPaperModeNothingGoesLiveEither() {
        AutomationSupport.liveSettings(live = false)
        SecurePrefs.put(IraNewsTrades.PAPER_KEY, false)
        provenRecord()
        assertFalse(IraNewsTrades.goesLive(false))
        assertFalse(IraNewsTrades.goesLive(true))
    }
}
