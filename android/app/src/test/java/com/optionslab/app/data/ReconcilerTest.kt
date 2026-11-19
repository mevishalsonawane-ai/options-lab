package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.NightMarks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 19 Nov 2026 (audit batch 2: the independent safety net): Zerodha's positions against every book, every minute while the watch
 * runs. A position no book owns is a RISK notification once two reads a minute apart agree (again every 5 minutes); one a book
 * owns is never said; a failed read says nothing; "Zerodha holds something" keeps the watch's "is anything held?" true; and nothing
 * is ever sent to Zerodha.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReconcilerTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY25OCT24500CE"
    private val other = "BANKNIFTY25OCT52000PE"
    private val t0 = System.currentTimeMillis()

    @Before fun up() {
        assumeFalse("IraGoldAlgo has no Zerodha reconciler", com.optionslab.app.BuildConfig.GOLD)
        Background.calendar(WED)
        Background.at(WED, 11, 0)
        Background.grantNotifications(context)
        kite = FakeKite()
        kite.instruments += FakeKite.Ins(12_345_678, sym, "NIFTY", AutomationSupport.expiryAfter(20), 24_500.0, "CE", 75)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        kite.login()
        AutomationSupport.liveSettings()
        Reconciler.resetForTest()
        // This process never ran the app's start-up (another test's may have left it half-run): the books are read on demand.
        PositionsFirst.resetForTest(started = false)
        runBlocking { ArmLive.refresh() }
    }

    @After fun down() {
        Reconciler.resetForTest()
        NightMarks.testOffMain = false
        if (::kite.isInitialized) kite.close()
        Background.reset()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun risk() = Background.notifications(context).getNotification(Reconciler.NOTE_ID)

    @Test fun aPositionNoBookOwnsIsARiskAfterTwoReadsAMinuteApartAndNothingIsSent() {
        kite.position(other, -30, 210.0, product = "NRML")
        val writes = kite.writes.size
        runBlocking { Reconciler.step(context, t0) }
        assertNull("one read is never enough", risk())
        assertTrue("Zerodha holds something: the watch never sleeps", Reconciler.heldHint(t0))
        assertTrue(HeldNow.any())
        runBlocking { Reconciler.step(context, t0 + 30_000L) }
        assertNull("not due again within the minute", risk())
        runBlocking { Reconciler.step(context, t0 + 60_000L) }
        val n = risk()
        assertNotNull("two reads a minute apart agree: told", n)
        n!!
        assertEquals(com.optionslab.app.work.Notifier.APPROVAL, n.channelId)
        val text = Background.text(n).orEmpty()
        assertTrue(text, text.contains("Zerodha holds -30 $other") && text.contains("no exit stop rests"))
        assertTrue(text, text.contains("placed, changed or cancelled nothing"))
        assertTrue(Reconciler.line(), Reconciler.line().contains(other))
        assertEquals("report only: nothing placed, changed or cancelled", writes, kite.writes.size)
    }

    @Test fun anExitStopRestingAtZerodhaIsSaid() {
        kite.position(other, -30, 210.0, product = "NRML")
        kite.restsInKite(other, "BUY", 30, 260.0, type = "SL-M", product = "NRML")
        runBlocking { Reconciler.step(context, t0); Reconciler.step(context, t0 + 60_000L) }
        val text = Background.text(risk()!!).orEmpty()
        assertTrue(text, text.contains("an exit stop rests at Zerodha"))
    }

    @Test fun aLiveProtectionsPositionIsOwnedAndNeverSaid() {
        kite.position(sym, 75, 100.0)
        val msg = runBlocking { Protections.protectLive(sym, "NFO", "NRML", 75, 100.0, 90.0, null, null) }
        assertTrue(msg, msg.startsWith("Protected"))
        runBlocking { Reconciler.step(context, t0); Reconciler.step(context, t0 + 60_000L); Reconciler.step(context, t0 + 120_000L) }
        assertNull(risk())
        assertTrue(Reconciler.line(), Reconciler.line().contains("every Zerodha position is followed by a book"))
        assertTrue("still held at Zerodha", Reconciler.heldHint(t0 + 120_000L))
    }

    @Test fun flatAtZerodhaIsNothingHeldAfterTwoFlatReads() {
        runBlocking { Reconciler.step(context, t0) }
        assertFalse(Reconciler.heldHint(t0))
        assertNull(risk())
    }

    @Test fun notLoggedInOrOutsideTheHoursNothingIsRead() {
        kite.position(other, -30, 210.0, product = "NRML")
        Background.at(WED, 7, 0)
        val reads = kite.requests.size
        runBlocking { Reconciler.step(context, t0) }
        assertEquals("before the open (and MCX shut in this calendar's morning): no read", reads, kite.requests.size)
        assertFalse(Reconciler.heldHint(t0))
        Background.at(WED, 11, 0)
        Broker.dropCreds()
        com.optionslab.app.security.SecurePrefs.putAll(mapOf("kite.accessToken" to null))
        runBlocking { Reconciler.step(context, t0) }
        assertEquals("not logged in: no read", reads, kite.requests.size)
        assertTrue(Reconciler.line(), Reconciler.line().startsWith("Reconciler: not run yet"))
    }

    private fun cancelAll() = context.getSystemService(android.app.NotificationManager::class.java).cancelAll()

    /** 19 Nov 2026 (second review): a manual position "Stop and target on my manual positions" left alone on purpose is told once. */
    @Test fun aManualPositionLeftAloneOnPurposeIsToldOnce() {
        kite.position(other, -30, 210.0, product = "NRML")
        ManualCover.leftAloneForTest("NFO:$other")
        try {
            runBlocking { Reconciler.step(context, t0); Reconciler.step(context, t0 + 60_000L) }
            val text = Background.text(risk()!!).orEmpty()
            assertTrue(text, text.contains("your own position"))
            cancelAll()
            runBlocking { Reconciler.step(context, t0 + 360_000L); Reconciler.step(context, t0 + 720_000L) }
            assertNull("told once while it lasts, not every 5 minutes", risk())
        } finally { ManualCover.resetForTest() }
    }

    /** 19 Nov 2026 (second review): in Paper mode no automatic live trade can be open, so a Zerodha position is Boss's: told once. */
    @Test fun inPaperModeAZerodhaPositionIsToldOnce() {
        AutomationSupport.liveSettings(live = false)
        kite.position(other, -30, 210.0, product = "NRML")
        runBlocking { Reconciler.step(context, t0); Reconciler.step(context, t0 + 60_000L) }
        assertNotNull(risk())
        cancelAll()
        runBlocking { Reconciler.step(context, t0 + 360_000L) }
        assertNull(risk())
    }

    /** 19 Nov 2026 (second review): Zerodha holding more than the books own (a duplicate fill) is said; less (a partial exit) never. */
    @Test fun moreAtZerodhaThanTheBooksOwnIsSaidLessNever() {
        kite.position(sym, 75, 100.0)
        val msg = runBlocking { Protections.protectLive(sym, "NFO", "NRML", 75, 100.0, 90.0, null, null) }
        assertTrue(msg, msg.startsWith("Protected"))
        kite.position(sym, 150, 100.0)
        runBlocking { Reconciler.step(context, t0); Reconciler.step(context, t0 + 60_000L) }
        val text = Background.text(risk()!!).orEmpty()
        assertTrue(text, text.contains("Zerodha holds 150 $sym, more than the 75"))
        cancelAll()
        Reconciler.resetForTest()
        kite.position(sym, 50, 100.0)
        runBlocking { Reconciler.step(context, t0); Reconciler.step(context, t0 + 60_000L); Reconciler.step(context, t0 + 120_000L) }
        assertNull("a partial exit is never one", risk())
    }

    /** 19 Nov 2026 (second review): the quiet start's mark stays "1" while a live book holds (a live protection; MCX after its close). */
    @Test fun aHeldLiveBookKeepsTheMarkAtOne() {
        NightMarks.testOffMain = true
        Reconciler.markHeld(context, false)
        kite.position(sym, 75, 100.0)
        val msg = runBlocking { Protections.protectLive(sym, "NFO", "NRML", 75, 100.0, 90.0, null, null) }
        assertTrue(msg, msg.startsWith("Protected"))
        // The side lane's value: every book's hint (the watch's holdingAny reads HeldNow), then the MCX carry.
        Reconciler.markHeld(context, Reconciler.markFor(HeldNow.any(mcxLive = true)))
        assertEquals(com.optionslab.ira.QuietStart.HELD, NightMarks.get(context, Reconciler.HELD_MARK))
    }

    @Test fun aLiveMcxPositionCarriedPastTheCloseKeepsTheMarkAtOne() {
        NightMarks.testOffMain = true
        Background.at(WED, 23, 50)
        assertFalse("MCX closed", McxMarket.isOpen())
        McxMarket.markLive()
        try {
            assertFalse("the books' own hint counts MCX only while it trades", HeldNow.sources().mcxLive)
            Reconciler.markHeld(context, Reconciler.markFor(false))
            assertEquals(com.optionslab.ira.QuietStart.HELD, NightMarks.get(context, Reconciler.HELD_MARK))
        } finally {
            McxMarket.noteLive(emptyList())
        }
        Reconciler.markHeld(context, Reconciler.markFor(false))
        assertEquals("flat at Zerodha again: nothing held", com.optionslab.ira.QuietStart.FLAT, NightMarks.get(context, Reconciler.HELD_MARK))
    }

    @Test fun theQuietStartsHeldMarkIsWrittenOnChangeOnly() {
        NightMarks.testOffMain = true
        Reconciler.markHeld(context, true)
        assertEquals(com.optionslab.ira.QuietStart.HELD, NightMarks.get(context, Reconciler.HELD_MARK))
        Reconciler.markHeld(context, false)
        assertEquals(com.optionslab.ira.QuietStart.FLAT, NightMarks.get(context, Reconciler.HELD_MARK))
    }
}
