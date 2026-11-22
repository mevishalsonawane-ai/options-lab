package com.optionslab.app.work

import android.service.quicksettings.Tile
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 22 Nov 2026 (B6 review): the quick-settings tile left its last P&L on the tile when the panel closed (seen again on the lock screen,
 * however stale). Closing the panel puts the second line back to the state alone; an amount shows only while the panel is open, and
 * never on a locked phone with "hide amounts on the lock screen" on.
 */
class BotTileSubtitleTest : RobolectricTest() {
    @Test fun closingThePanelLeavesTheStateAloneWithNoAmount() {
        // (Built directly: Robolectric's service controller cannot drive a TileService; its tile comes from ShadowTileService.)
        val tile = BotTile()
        val t = tile.qsTile
        t.state = Tile.STATE_ACTIVE
        t.subtitle = "Today +₹5,400"
        tile.onStopListening()
        assertEquals("Running", t.subtitle)
        t.state = Tile.STATE_INACTIVE
        t.subtitle = "Today -₹1,200"
        tile.onStopListening()
        assertEquals("Stopped", t.subtitle)
    }

    /** Opening the panel never shows the last amount again before the first read (on the lock screen too). */
    @Test fun openingThePanelShowsTheStateAloneAtOnce() {
        val tile = BotTile()
        val t = tile.qsTile
        t.state = Tile.STATE_INACTIVE
        t.subtitle = "Today +₹5,400"
        tile.onStartListening()
        assertEquals("Stopped", t.subtitle)
        val reads = tile.liveForTest
        tile.onStopListening()            // (its first read, still on its way, is called off)
        // (Its last step runs on the main thread: the main looper is run until it has ended.)
        val until = System.currentTimeMillis() + 10_000
        while (reads?.isCompleted == false && System.currentTimeMillis() < until) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        assertEquals(true, reads?.isCompleted)
        assertTrue("never an amount after the panel closed: ${t.subtitle}", t.subtitle == "Running" || t.subtitle == "Stopped")
    }

    @Test fun anAmountShowsOnlyWhileThePanelIsOpenAndNeverLockedWhenHidden() {
        assertEquals("Today +₹5,400", BotTile.subtitle(on = true, pnl = "Today +₹5,400", listening = true, locked = false, hideOnLock = true))
        assertEquals("locked, hidden", "Running", BotTile.subtitle(on = true, pnl = "Today +₹5,400", listening = true, locked = true, hideOnLock = true))
        assertEquals("locked, Boss shows amounts there", "Today +₹5,400",
            BotTile.subtitle(on = true, pnl = "Today +₹5,400", listening = true, locked = true, hideOnLock = false))
        assertEquals("the panel closed meanwhile", "Stopped", BotTile.subtitle(on = false, pnl = "Today +₹5,400", listening = false, locked = false, hideOnLock = false))
        assertEquals("no figure read", "Running", BotTile.subtitle(on = true, pnl = null, listening = true, locked = false, hideOnLock = false))
    }
}
