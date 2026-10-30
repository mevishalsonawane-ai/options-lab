package com.optionslab.app.data

import com.optionslab.app.ira.IraNewsTrades
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 30 Oct (deep review; CLAUDE.md: every arm's live trade passes "AI trades go live"): the ORB family's and Pine's own live entries are
 * asked the app's Live and "AI trades go live" inside the entry door, at the last moment, from memory. Before, they went on Live and
 * their own PIN flag alone, so turning only "AI trades go live" off left them trading. Exits never come here; a saved strategy's
 * basket leg and Boss's own orders are not touched.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ArmDoorAiLiveTest : RobolectricTest() {
    private fun order(tag: String) = Kite.Order("NIFTY26N0324000CE", Kite.Side.BUY, 65, 65, "MIS", "MARKET", null, 0.05, "NFO", tag)

    @Before fun up() {
        AutomationSupport.liveSettings()
        IraNewsTrades.paperFirst = false
        PaperOnly.resetForTest(null)
        SecurePrefs.put("g.kill", false)
    }

    @After fun down() { IraNewsTrades.paperFirst = true; AutomationSupport.liveSettings(live = false) }

    @Test fun anOrbOrPineEntryNeedsLiveAndAiTradesGoLiveAtTheDoor() {
        for (tag in listOf("iraorb", "irapine")) assertNull(tag, Broker.entryRecheck(order(tag), "orb"))
        IraNewsTrades.paperFirst = true
        for (tag in listOf("iraorb", "irapine")) assertEquals(tag, "refused: \"AI trades go live\" is off", Broker.entryRecheck(order(tag), "orb"))
        IraNewsTrades.paperFirst = false
        AutomationSupport.liveSettings(live = false)
        for (tag in listOf("iraorb", "irapine")) assertEquals(tag, "refused: the app is in Paper", Broker.entryRecheck(order(tag), "orb"))
    }

    @Test fun aSavedStrategysLegAndBossesOwnOrderAreNotAskedIt() {
        IraNewsTrades.paperFirst = true
        assertNull(Broker.entryRecheck(order("iraalgostrat"), null))
        assertNull(Broker.entryRecheck(order("iraalgo"), null))
        assertEquals(setOf("iraorb", "irapine"), Broker.ARM_TAGS)
    }
}
