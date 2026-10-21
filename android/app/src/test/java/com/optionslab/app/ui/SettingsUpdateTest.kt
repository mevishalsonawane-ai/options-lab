package com.optionslab.app.ui

import android.app.Application
import android.os.Looper
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Guard
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * 18 Oct, review: [AppModel.update] applies a change to what the vault holds, not to the screen's copy saved whole. The
 * guard turns the kill switch on by itself (a live drawdown); the screen's copy did not know, and the next theme or alert
 * change wrote the kill switch back off, silently, with new live entries allowed again. The screen now follows such writes.
 */
class SettingsUpdateTest : RobolectricTest() {
    private val store = androidx.lifecycle.ViewModelStore()

    /** A real AppModel, owned by a store the test clears (its coroutines end with the test). */
    private fun model(): AppModel =
        androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(context as Application))[AppModel::class.java]

    @After fun down() { store.clear() }

    private fun await(what: String, timeoutMs: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ok()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private val drawdown = listOf("Drawdown limit: 12.0% from the peak (limit 10%)")

    @Test fun aKillSwitchTheModelNeverHeardOfSurvivesALaterUnrelatedChange() {
        val model = model()
        assertFalse(model.settings.value.guardKill)
        // Written behind the model's back (no save notice). The model's first read of the vault (on IO, at its start) may or
        // may not have seen it yet; either way the change below must keep it (the stored-read race itself is in GuardTest).
        SecurePrefs.put(AppSettings.KILL_KEY, true)
        model.update { it.copy(theme = "dark") }
        await("the theme saved") { AppSettings.load().theme == "dark" }
        assertTrue("an unrelated change must never clear the kill switch", AppSettings.load().guardKill)
        assertTrue("nor the entry door's own read of it", SecurePrefs.getBoolean(AppSettings.KILL_KEY, false))
        await("the screen shows the stored kill switch") { model.settings.value.guardKill && model.settings.value.theme == "dark" }
    }

    @Test fun aGuardWrittenKillSwitchSurvivesALaterUnrelatedChange() {
        val model = model()
        Guard.onRefusal(drawdown)
        assertTrue("the guard's kill switch reads at once", AppSettings.load().guardKill)
        // At once, before the screen may have caught up: the stale-copy case.
        model.update { it.copy(pnlLossAlert = 5_000.0) }
        await("the alert saved") { AppSettings.load().pnlLossAlert == 5_000.0 }
        assertTrue(AppSettings.load().guardKill)
        await("the screen shows it") { model.settings.value.guardKill }
        // And a second unrelated change later still keeps it.
        model.update { it.copy(theme = "light") }
        await("the theme saved") { AppSettings.load().theme == "light" }
        assertTrue(AppSettings.load().guardKill)
        assertTrue(model.settings.value.guardKill)
    }

    @Test fun theScreenFollowsTheGuardsKillSwitchWithNoChangeOfItsOwn() {
        val model = model()
        assertFalse(model.settings.value.guardKill)
        Guard.onRefusal(drawdown)
        await("the screen shows the kill switch on") { model.settings.value.guardKill }
    }

    @Test fun anotherRefusalLeavesTheKillSwitchAndTheScreenAlone() {
        val model = model()
        Guard.onRefusal(listOf("Daily loss limit reached"))
        Guard.onRefusal(emptyList())
        model.update { it.copy(theme = "dark") }
        await("the theme saved") { AppSettings.load().theme == "dark" && model.settings.value.theme == "dark" }
        assertFalse("only a drawdown turns the kill switch on", AppSettings.load().guardKill)
        assertFalse(model.settings.value.guardKill)
    }

    @Test fun bossesOwnClearStillClearsAKillSwitchTheScreenHadNotSeen() {
        val model = model()
        SecurePrefs.put(AppSettings.KILL_KEY, true)
        model.update { it.copy(guardKill = false) }
        await("the kill switch cleared") { !AppSettings.load().guardKill }
        await("the screen agrees") { !model.settings.value.guardKill }
    }

    @Test fun quickChangesAllLandInTheOrderTheyWereMade() {
        val model = model()
        model.update { it.copy(theme = "dark") }
        model.update { it.copy(pnlLossAlert = 2_000.0) }
        model.update { it.copy(theme = "light") }
        model.update { it.copy(pnlProfitAlert = 3_000.0) }
        assertEquals("the screen shows every change at once", "light", model.settings.value.theme)
        await("all four saved") { AppSettings.load().let { it.theme == "light" && it.pnlLossAlert == 2_000.0 && it.pnlProfitAlert == 3_000.0 } }
        // Settled: nothing older lands later.
        Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
        val s = AppSettings.load()
        assertEquals("light", s.theme); assertEquals(2_000.0, s.pnlLossAlert, 0.0); assertEquals(3_000.0, s.pnlProfitAlert, 0.0)
        assertEquals(s, model.settings.value)
    }

    /**
     * 18 Oct (CI flake of the test below): on a vault from before the guard's v2 defaults the first change is written whole.
     * The guard's kill switch landing between that change's read and its write (the guard writes from the order path, at any
     * moment) was written back off. Forced here: the guard's write lands after [from] was read and before the write.
     */
    @Test fun aWholeWriteOfAnOlderVaultNeverWritesTheKillSwitchItDidNotChange() {
        assertTrue("a fresh vault is written whole", SecurePrefs.getInt("g.v", 1) < 2)
        val from = AppSettings.load()
        assertFalse(from.guardKill)
        SecurePrefs.put(AppSettings.KILL_KEY, true)                 // the guard, between the read and the write
        AppSettings.saveChanged(from, from.copy(theme = "dark"))
        assertTrue("the guard's kill switch is kept", AppSettings.load().guardKill)
        assertEquals("dark", AppSettings.load().theme)
        assertEquals("the whole write was made (migrated)", 2, SecurePrefs.getInt("g.v", 1))
        // Boss's own clear in a whole write still clears it.
        SecurePrefs.put("g.v", 1)
        val on = AppSettings.load()
        AppSettings.saveChanged(on, on.copy(guardKill = false))
        assertFalse(AppSettings.load().guardKill)
    }

    @Test fun aGuardWriteDuringQueuedChangesIsKeptByAll() {
        val model = model()
        model.update { it.copy(theme = "dark") }
        Guard.onRefusal(drawdown)
        model.update { it.copy(theme = "light") }
        await("the changes saved") { AppSettings.load().theme == "light" && model.settings.value.theme == "light" }
        Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AppSettings.load().guardKill)
        await("the screen shows it") { model.settings.value.guardKill }
    }

    /**
     * 18 Oct (review): "Live orders without PIN" now means one tap sends a real order with no PIN and no review, a stronger
     * meaning than Boss turned on. It reads off once with this update (the old key is never read again), and is on again only
     * when he turns it on (the switch asks his PIN), kept from then on.
     */
    @Test fun liveOrdersWithoutPinIsResetToOffOnceAndStaysOnOnlyWhenTurnedOnAgain() {
        SecurePrefs.put("k.oneTap", true)                           // on, from before this update
        assertFalse("reset to off with this update", AppSettings.load().oneTapOrders)
        val model = model()
        await("the screen's copy") { true }
        assertFalse(model.settings.value.oneTapOrders)
        // A save of anything else never turns it back on, nor writes the old key's "on" back.
        model.update { it.copy(theme = "dark") }
        await("saved") { AppSettings.load().theme == "dark" }
        assertFalse(AppSettings.load().oneTapOrders)
        // Boss turns it on again (after his PIN): kept.
        model.update { it.copy(oneTapOrders = true) }
        await("saved on") { AppSettings.load().oneTapOrders }
        assertEquals(true, SecurePrefs.getBoolean(AppSettings.ONE_TAP_KEY, false))
        model.update { it.copy(oneTapOrders = false) }
        await("saved off") { !AppSettings.load().oneTapOrders }
    }
}
