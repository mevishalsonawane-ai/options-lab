package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.ArmLive
import com.optionslab.app.data.Paper
import com.optionslab.app.data.Strategies
import com.optionslab.app.security.PinLock
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.sandbox.Quote
import com.optionslab.ira.AutoChange
import com.optionslab.ira.Command
import com.optionslab.ira.DayStop
import com.optionslab.ira.SettingsTalk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 22 Nov 2026 (B6 review): Jarvis's gates and words found loose on a3cc6b95b -
 *  1. switching off "Block naked option shorts" or the expiry-day square-off by voice (or undoing a switch-on) takes the PIN, as the
 *     Account guard page does (one list for both);
 *  2. the commodity side's Stop for today is never lifted in Live without the PIN: [Strategies.startCommoditiesAgain], voice, and the
 *     change timeline's Undo (an old PAPER-scope entry too);
 *  4. the order limits said and set by voice are the ones the checks apply (the account guard's);
 *  7. Jarvis closing one named paper position on Boss's word, and the shade's "Close position", is his own hand close: an NSE intraday live
 *     copy keeps running with its own stop (said in the confirm), never sold;
 *  9. "cancel the last order" never picks a protective stop-loss; one named is said to be one.
 */
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class B6JarvisGatesTest : RobolectricTest() {
    private val pin = "482915"

    @Before fun up() { Paper.reset(BigDecimal("400000")) }

    @After fun down() {
        AutomationSupport.liveSettings(live = false)
        ArmLive.testHeard = null
        // (A copy a test detached is given back, so no other test in this run sees its mark.)
        runBlocking { ArmLive.undetach("passed:r51:b6") }
        ArmLive.viewForTest(ArmLive.View())
        Autopilot.resetForTest()
    }

    private fun stored(f: (AppSettings) -> AppSettings) = AppSettings.save(f(AppSettings.load()))
    private fun prepare(c: Command) = runBlocking { IraActions.prepare(c) }

    // ---- 1. the expiry-day square-off and the naked-shorts block --------------------------------------------------------------------

    @Test fun bothSwitchesAreInTheOneListThePageAndJarvisShare() {
        assertTrue(SettingsTalk.Key.NAKED_SHORTS in SettingsTalk.PIN_TO_LOOSEN)
        assertTrue(SettingsTalk.Key.EXPIRY_SQUARE_OFF in SettingsTalk.PIN_TO_LOOSEN)
        assertTrue(IraActions.LIMIT_PIN, IraActions.LIMIT_PIN.contains("expiry-day square-off") && IraActions.LIMIT_PIN.contains("naked option shorts"))
    }

    @Test fun inLiveWithAPinJarvisNeverSwitchesEitherOffButSwitchesThemOn() {
        stored { it.copy(mode = "live", allowRealOrders = true, guardNakedShort = true, expirySquareOff = true) }
        PinLock.setPin(pin.toCharArray())
        for (k in listOf(SettingsTalk.Key.NAKED_SHORTS, SettingsTalk.Key.EXPIRY_SQUARE_OFF)) {
            val (said, act) = prepare(Command(Command.Kind.SET_LIMIT, target = k.name, level = 0.0))
            assertEquals(k.name, IraActions.LIMIT_PIN, said)
            assertNull(k.name, act)
        }
        assertTrue(AppSettings.load().guardNakedShort)
        assertTrue(AppSettings.load().expirySquareOff)
        // Switching one on (a tightening) is never refused.
        stored { it.copy(guardNakedShort = false) }
        val (_, on) = prepare(Command(Command.Kind.SET_LIMIT, target = SettingsTalk.Key.NAKED_SHORTS.name, level = 1.0))
        assertNotNull(on)
        runBlocking { on!!() }
        assertTrue(AppSettings.load().guardNakedShort)
    }

    @Test fun aPinSetBetweenTheQuestionAndTheYesStillStopsIt() {
        stored { it.copy(mode = "live", allowRealOrders = true, expirySquareOff = true) }
        val (_, act) = prepare(Command(Command.Kind.SET_LIMIT, target = SettingsTalk.Key.EXPIRY_SQUARE_OFF.name, level = 0.0))
        assertNotNull("no PIN set yet: nothing to ask", act)
        PinLock.setPin(pin.toCharArray())
        assertEquals(IraActions.LIMIT_PIN, runBlocking { act!!() })
        assertTrue(AppSettings.load().expirySquareOff)
    }

    @Test fun withNoPinToAskJarvisSwitchesThemOffAsAsked() {
        stored { it.copy(mode = "live", allowRealOrders = true, guardNakedShort = true, expirySquareOff = true) }
        for (k in listOf(SettingsTalk.Key.NAKED_SHORTS, SettingsTalk.Key.EXPIRY_SQUARE_OFF)) {
            val (said, act) = prepare(Command(Command.Kind.SET_LIMIT, target = k.name, level = 0.0))
            assertTrue(said, said.contains("(this allows more risk)"))
            assertEquals("Done: ${k.label} is now off.", runBlocking { act!!() })
        }
        assertFalse(AppSettings.load().guardNakedShort)
        assertFalse(AppSettings.load().expirySquareOff)
    }

    @Test fun undoingASwitchOnTakesThePinAndGoesWithoutOne() {
        stored { it.copy(mode = "live", allowRealOrders = true, guardNakedShort = false, expirySquareOff = false) }
        // Boss switches both on in Settings (one save, one group in the change log); "undo" would switch them off again.
        stored { it.copy(guardNakedShort = true, expirySquareOff = true) }
        assertEquals(setOf(SettingsTalk.Key.NAKED_SHORTS, SettingsTalk.Key.EXPIRY_SQUARE_OFF),
            com.optionslab.app.data.SettingsLog.lastUndoable().map { it.key }.toSet())
        PinLock.setPin(pin.toCharArray())
        val (said, act) = prepare(Command(Command.Kind.UNDO))
        assertEquals(IraActions.LIMIT_PIN, said)
        assertNull(act)
        assertTrue(AppSettings.load().guardNakedShort && AppSettings.load().expirySquareOff)
    }

    @Test fun undoingASwitchOnWithNoPinSetSwitchesThemBackOff() {
        stored { it.copy(mode = "live", allowRealOrders = true, guardNakedShort = false, expirySquareOff = false) }
        stored { it.copy(guardNakedShort = true, expirySquareOff = true) }
        val (said, act) = prepare(Command(Command.Kind.UNDO))
        assertTrue(said, said.startsWith("undo: ") && said.contains("(this allows more risk)"))
        assertTrue(runBlocking { act!!() }.startsWith("Undone: "))
        assertFalse(AppSettings.load().guardNakedShort)
        assertFalse(AppSettings.load().expirySquareOff)
    }

    // ---- 2. the commodity side's Stop for today -------------------------------------------------------------------------------------

    private fun mcxStopped() = runBlocking { Strategies.mcxStoppedKept() } != null

    @Test fun inLiveWithAPinTheCommoditySideIsLiftedOnlyWithThePin() {
        PinLock.setPin(pin.toCharArray())
        AutomationSupport.liveSettings()
        runBlocking { Strategies.stopCommodities(sell = false) }
        assertTrue(mcxStopped())
        // The plain call (voice, the old card's tap): refused, said to open the app.
        assertEquals(Strategies.MCX_LIFT_PIN, runBlocking { Strategies.startCommoditiesAgain() })
        assertTrue("still stopped", mcxStopped())
        // The MCX card, after the PIN.
        assertTrue(runBlocking { Strategies.startCommoditiesAgain(pinConfirmed = true) }.startsWith("Commodity bots running"))
        assertFalse(mcxStopped())
        // The change timeline's Undo ([log] false) took the PIN itself.
        runBlocking { Strategies.stopCommodities(sell = false) }
        assertTrue(runBlocking { Strategies.startCommoditiesAgain(log = false) }.startsWith("Commodity bots running"))
        assertFalse(mcxStopped())
        // Not stopped: nothing to ask, nothing lifted.
        assertEquals("The commodity bots are not stopped.", runBlocking { Strategies.startCommoditiesAgain() })
    }

    @Test fun onPaperOrWithNoPinTheCommoditySideStartsAtOnce() {
        runBlocking { Strategies.stopCommodities(sell = false) }
        assertTrue(runBlocking { Strategies.startCommoditiesAgain() }.startsWith("Commodity bots running"))
        AutomationSupport.liveSettings()
        runBlocking { Strategies.stopCommodities(sell = false) }
        assertTrue("Live with no app PIN: nothing to ask", runBlocking { Strategies.startCommoditiesAgain() }.startsWith("Commodity bots running"))
    }

    @Test fun byVoiceInLiveTheCommoditySideIsNeverLifted() {
        PinLock.setPin(pin.toCharArray())
        AutomationSupport.liveSettings()
        runBlocking { Strategies.stopCommodities(sell = false) }
        val (said, act) = prepare(com.optionslab.ira.Ask.parse("start the commodity bots again").command!!)
        assertEquals(IraActions.MCX_LIFT_PIN, said)
        assertNull(act)
        // "Start all" with only the commodity side stopped: refused the same way.
        val (all, allAct) = prepare(com.optionslab.ira.Ask.parse("start all").command!!)
        assertEquals(IraActions.MCX_LIFT_PIN, all)
        assertNull(allAct)
        // Both sides stopped: both said, nothing started.
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        val (both, bothAct) = prepare(com.optionslab.ira.Ask.parse("start all").command!!)
        assertEquals(IraActions.BOTH_LIFT_PIN, both)
        assertNull(bothAct)
        assertTrue(mcxStopped())
        assertTrue(runBlocking { Strategies.stoppedToday() })
    }

    @Test fun byVoiceOnPaperTheCommoditySideStillStarts() {
        runBlocking { Strategies.stopCommodities(sell = false) }
        val (said, act) = prepare(com.optionslab.ira.Ask.parse("start the commodity bots again").command!!)
        assertTrue(said, said.startsWith("start the commodity bots again"))
        assertTrue(runBlocking { act!!() }.startsWith("Commodity bots running"))
        assertFalse(mcxStopped())
    }

    @Test fun anOlderPaperScopeEntrysUndoStillAsksThePinInLive() {
        val day = com.optionslab.app.data.Market.today()
        // A commodity stop logged by an earlier build (PAPER scope then).
        val old = AutoChange.dayStop(DayStop.Side.MCX, stopped = true, by = "by you", at = "10:42").copy(scope = AutoChange.Scope.PAPER)
        val (book, e) = AutoChange.record(AutoChange.Book(), old, AutoChange.Status.KEPT, 1L, day, by = "your choice")
        Autopilot.setForTest(book)
        assertFalse("on Paper: nothing to ask", Autopilot.undoNeedsPin(e.id))
        PinLock.setPin(pin.toCharArray())
        AutomationSupport.liveSettings()
        assertTrue("in Live with a PIN: the PIN", Autopilot.undoNeedsPin(e.id))
        assertEquals(Autopilot.PIN_UNDO, Autopilot.undo(e.id))
        // A new entry is LIVE scope by itself.
        assertEquals(AutoChange.Scope.LIVE, AutoChange.dayStop(DayStop.Side.MCX, stopped = true, by = "by you").scope)
    }

    /** 22 Nov 2026 (B6 review): with no PIN set, a day stop's timeline Undo has no PIN step (none could be passed); with one, it asks. */
    @Test fun aDayStopsUndoAsksThePinOnlyWhenOneIsSet() {
        val day = com.optionslab.app.data.Market.today()
        runBlocking { Strategies.stopCommodities(sell = false) }
        assertTrue(mcxStopped())
        val (book, e) = AutoChange.record(AutoChange.Book(), AutoChange.dayStop(DayStop.Side.MCX, stopped = true, by = "by you"),
            AutoChange.Status.KEPT, 1L, day, by = "your choice")
        Autopilot.setForTest(book)
        AutomationSupport.liveSettings()
        assertFalse("no PIN set: no PIN step, in Live too", Autopilot.undoNeedsPin(e.id))
        assertTrue(Autopilot.undo(e.id).startsWith("Undone"))
        assertFalse(mcxStopped())
        // With a PIN set: asked, on Paper too (as the index side's Undo always was).
        runBlocking { Strategies.stopCommodities(sell = false) }
        val (book2, e2) = AutoChange.record(AutoChange.Book(), AutoChange.dayStop(DayStop.Side.INDEX, stopped = true, by = "by you"),
            AutoChange.Status.KEPT, 2L, day, by = "your choice")
        val (book3, e3) = AutoChange.record(book2, AutoChange.dayStop(DayStop.Side.MCX, stopped = true, by = "by you"), AutoChange.Status.KEPT, 3L, day, by = "your choice")
        Autopilot.setForTest(book3)
        PinLock.setPin(pin.toCharArray())
        AutomationSupport.liveSettings(live = false)
        assertTrue(Autopilot.undoNeedsPin(e2.id))
        assertTrue(Autopilot.undoNeedsPin(e3.id))
        assertEquals(Autopilot.PIN_UNDO, Autopilot.undo(e3.id))
        assertTrue("still stopped", mcxStopped())
        // Stopping a side again (the Undo of a start) never asks.
        val (book4, e4) = AutoChange.record(AutoChange.Book(), AutoChange.dayStop(DayStop.Side.MCX, stopped = false, by = "by you"),
            AutoChange.Status.KEPT, 4L, day, by = "your choice")
        Autopilot.setForTest(book4)
        assertFalse(Autopilot.undoNeedsPin(e4.id))
    }

    // ---- 4. the order limits said and set by voice ---------------------------------------------------------------------------------

    /**
     * The settings log's raw key names, those of the two limits gone since included (its decoded list leaves them out), and a later save
     * keeps those rows (what they say is never lost).
     */
    @Test fun theSettingsLogStillNamesTheLimitsGoneSinceAcrossASave() {
        com.optionslab.app.security.SecurePrefs.putAll(mapOf("settings.history" to org.json.JSONArray()
            .put(org.json.JSONObject().put("t", "2026-10-05T10:00").put("k", "LOTS_PER_ORDER").put("o", 2.0).put("n", 1.0).put("b", "Jarvis"))
            .put(org.json.JSONObject().put("t", "2026-10-05T10:00").put("k", "MAX_LOTS").put("o", 2.0).put("n", 1.0).put("b", "Jarvis")).toString()))
        assertEquals(setOf("LOTS_PER_ORDER", "MAX_LOTS"), com.optionslab.app.data.SettingsLog.keysLogged())
        assertEquals("the decoded list drops the gone key", listOf(SettingsTalk.Key.MAX_LOTS), com.optionslab.app.data.SettingsLog.all().map { it.key })
        stored { it.copy(guardMaxOpen = it.guardMaxOpen + 1) }
        assertEquals(setOf("LOTS_PER_ORDER", "MAX_LOTS", "MAX_OPEN"), com.optionslab.app.data.SettingsLog.keysLogged())
        assertEquals(listOf(SettingsTalk.Key.MAX_LOTS, SettingsTalk.Key.MAX_OPEN), com.optionslab.app.data.SettingsLog.all().map { it.key })
    }

    /** Set on this phone: one line in the timeline, once ever; nothing tightened. Never set: nothing, and decided for good. */
    @Test fun theDeadLimitsAreSaidOnceInTheTimelineAndNothingIsTightened() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        Autopilot.setForTest(AutoChange.Book())
        stored { it.copy(maxLotsPerOrder = 1, guardMaxLots = 5, guardMaxTrades = 10) }
        assertTrue(Autopilot.logDeadOrderLimitsOnce())
        val e = Autopilot.book.value.entries.single { it.change.kind == AutoChange.Kind.SETTING_NOTE }
        assertEquals(AutoChange.Status.SKIPPED, e.status)
        assertTrue(e.change.reason, e.change.reason.contains("max lots per instrument 5") && e.change.reason.contains("max orders per day 10"))
        assertTrue("its text once, not twice", e.note.isNotEmpty() && !e.note.contains("never checked"))
        assertEquals("nothing tightened", 5, AppSettings.load().guardMaxLots)
        assertFalse("never again", Autopilot.logDeadOrderLimitsOnce())
        Autopilot.setForTest(AutoChange.Book())
        assertFalse("not even after the log is emptied", Autopilot.logDeadOrderLimitsOnce())
    }

    @Test fun neverSetTheyAreNeverSaid() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        Autopilot.setForTest(AutoChange.Book())
        assertFalse(Autopilot.logDeadOrderLimitsOnce())
        assertTrue(Autopilot.book.value.entries.none { it.change.kind == AutoChange.Kind.SETTING_NOTE })
        assertTrue("decided", com.optionslab.app.security.SecurePrefs.getBoolean(Autopilot.K_DEAD_LIMITS, false))
    }


    @Test fun lotsPerOrderAndManualOrdersByVoiceSetTheGuardsEnforcedLimits() {
        stored { it.copy(mode = "live", allowRealOrders = true, guardMaxLots = 2, guardMaxTrades = 10) }
        val lots = com.optionslab.ira.Ask.parse("set max lots per order to 1").command!!
        assertEquals(SettingsTalk.Key.MAX_LOTS.name, lots.target)
        val (said, act) = prepare(lots)
        assertEquals("change max lots per instrument from 2 to 1", said)
        runBlocking { act!!() }
        assertEquals(1, AppSettings.load().guardMaxLots)
        val orders = com.optionslab.ira.Ask.parse("set max manual orders per day to 5").command!!
        assertEquals(SettingsTalk.Key.MAX_TRADES.name, orders.target)
        runBlocking { prepare(orders).second!!() }
        assertEquals(5, AppSettings.load().guardMaxTrades)
        // A raise of the enforced limit is a loosening: the PIN, as on the page.
        PinLock.setPin(pin.toCharArray())
        assertEquals(IraActions.LIMIT_PIN, prepare(com.optionslab.ira.Ask.parse("set max lots per order to 3").command!!).first)
        assertEquals(1, AppSettings.load().guardMaxLots)
    }

    @Test fun theSettingsAnswerSaysTheGuardsOrderLimits() {
        val s = AppSettings().copy(guardMaxTrades = 10, guardMaxLots = 2, guardMaxValue = 200_000.0, maxOrdersPerDay = 4, maxLotsPerOrder = 7, maxOrderValue = 999_000.0)
        assertEquals("the account guard's order limits on Zerodha: at most 10 orders a day, 2 lots in one instrument, Rs 200,000.00 an order.",
            IraAccount.orderLimitsLine(s))
        assertEquals("the account guard's order limits on Zerodha: no orders-a-day limit, no lots limit, no order value limit.",
            IraAccount.orderLimitsLine(s.copy(guardMaxTrades = 0, guardMaxLots = 0, guardMaxValue = 0.0)))
        // The Live lots card's line names the guard's limit only.
        assertEquals("Above your order limits (max lots per instrument is 2, in the account guard): those entries are refused until you raise it too.",
            com.optionslab.app.ui.screens.armLotsCapNote(3, 2))
        assertNull(com.optionslab.app.ui.screens.armLotsCapNote(2, 2))
        assertNull("off", com.optionslab.app.ui.screens.armLotsCapNote(3, 0))
    }

    // ---- 7. Jarvis's (and the shade's) close of one paper position --------------------------------------------------------------------

    private val exp = com.optionslab.app.data.Market.today().plusDays(6)
    private val sym = "NIFTY" + exp.format(java.time.format.DateTimeFormatter.ofPattern("ddMMMyy", java.util.Locale.ENGLISH)).uppercase(java.util.Locale.ENGLISH) + "25000CE"

    /** One paper NIFTY call held ([product]) and an arm's live copy of it open at Zerodha (memory only: the arms' book is not started). */
    private fun heldWithLiveCopy(product: String = "MIS"): MutableList<String> {
        val c = Paper.Contract(sym, "NIFTY", exp, 25_000.0, Right.CE, 65, "NSE_FO|$sym", "NFO")
        assertTrue(runBlocking { Paper.place(c, "BUY", 1, "MARKET", product, null, null, Quote(100.0, bid = 99.9, ask = 100.1)) }.ok)
        val copy = ArmLive.Pos(DonchianArm.SOURCE, "passed:r51:b6", sym, "NFO", product, 1, 65, 65, 65, 0.05, 1, 100.0,
            LocalDateTime.now().withNano(0), 100.0, 65, null, "1001", stopOrderId = "1002", stopTrigger = 85.0, paperSymbol = sym)
        // (The arms' book read first - empty on this test's phone - so a later read keeps this view rather than publishing the book again.)
        runBlocking { ArmLive.refresh() }
        ArmLive.viewForTest(ArmLive.View(open = listOf(copy), loaded = true))
        return ArrayList<String>().also { ArmLive.testHeard = it }
    }

    private fun held() = Paper.state.positions.filter { it.symbol == sym }.sumOf { it.quantity }

    /** [block] with the paper close's price read served by a fake feed (never the real host). */
    private fun withFeed(block: () -> Unit) {
        val upstox = com.optionslab.app.testing.FakeUpstox()
        try { upstox.price("NSE_FO|$sym", 100.0); block() } finally { upstox.close() }
    }

    @Test fun jarvisClosingOnePaperPositionKeepsItsIntradayLiveCopyAndSaysSo() = withFeed {
        val heard = heldWithLiveCopy()
        val (said, act) = prepare(Command(Command.Kind.CLOSE_ONE, number = 1))
        assertTrue(said, said.contains("paper $sym"))
        assertTrue(said, said.contains("A live copy is open at Zerodha") && said.contains("nothing at Zerodha is sold for it"))
        val done = runBlocking { act!!() }
        assertEquals(0, held())
        assertTrue(done, done.contains("it stays open with its own stop"))
        assertEquals("detached, never sold", listOf("detach|passed:r51:b6|paper row closed by Jarvis on your word"), heard.filter { it.startsWith("detach|") })
    }

    @Test fun aCarriedLiveCopyIsSoldWithThePaperAndTheConfirmSaysSo() = withFeed {
        val heard = heldWithLiveCopy(product = "NRML")
        val (said, act) = prepare(Command(Command.Kind.CLOSE_ONE, number = 1))
        assertTrue(said, said.contains("sold at Zerodha with the paper trade"))
        runBlocking { act!!() }
        assertEquals(0, held())
        assertTrue("never detached", heard.none { it.startsWith("detach|") })
    }

    @Test fun closeAllAndTheEmergencyExitStillSellTheLiveCopiesWithThePaper() = withFeed {
        val heard = heldWithLiveCopy()
        val (said, act) = prepare(Command(Command.Kind.CLOSE_ALL))
        assertEquals("close 1 open position", said)
        runBlocking { act!!() }
        assertEquals(0, held())
        assertTrue("an automatic paper end: the copy goes with it", heard.none { it.startsWith("detach|") })
    }

    /**
     * The shade's "Close position" stays a plain paper close: the app is usually closed when it is used, so the live copy's words would
     * reach no screen - the copy is never detached, and goes with its paper trade as before.
     */
    @Test fun theShadesClosePositionStillSellsTheLiveCopyWithThePaper() = withFeed {
        val heard = heldWithLiveCopy()
        com.optionslab.app.work.Alerts.forgetPosted()
        runBlocking { com.optionslab.app.work.NotificationActionReceiver().closePaper(context, sym) }
        assertEquals(0, held())
        assertTrue("never detached: sold with its paper trade", heard.none { it.startsWith("detach|") })
        assertTrue(com.optionslab.app.work.Alerts.posted.none { it.text.contains("A live copy is open at Zerodha") })
        assertTrue("it still follows its paper trade", ArmLive.liveCopiesOpen(sym, "MIS").isNotEmpty())
    }

    /** Boss's yes comes after the arm's live copies of the row changed (it entered the contract again): nothing is closed, and he is told. */
    @Test fun aJarvisCloseConfirmedAfterTheLiveCopiesChangedClosesNothing() = withFeed {
        val heard = heldWithLiveCopy()
        val (said, act) = prepare(Command(Command.Kind.CLOSE_ONE, number = 1))
        assertTrue(said, said.contains("A live copy is open at Zerodha"))
        // Meanwhile that copy closed and the arm entered the same contract again: another copy, never mentioned to Boss.
        val again = ArmLive.view.value.open.single().copy(ref = "passed:r51:b6-again")
        ArmLive.viewForTest(ArmLive.View(open = listOf(again), loaded = true))
        assertEquals(com.optionslab.app.data.PaperHandClose.CHANGED, runBlocking { act!!() })
        assertEquals("the paper row is left open", 65, held())
        assertTrue("nothing detached", heard.none { it.startsWith("detach|") })
        // The arms' book unreadable at Confirm: never read as "no copies" - nothing closed, and said so.
        ArmLive.viewForTest(ArmLive.View(open = listOf(again), loaded = true, unreadable = true))
        assertEquals(com.optionslab.app.data.PaperHandClose.UNREAD, runBlocking { act!!() })
        assertEquals(65, held())
        // Only the copy Boss was told of, and it has closed since by its own stop: nothing can be left behind, so the close goes.
        ArmLive.viewForTest(ArmLive.View(open = emptyList(), loaded = true))
        runBlocking { act!!() }
        assertEquals(0, held())
        assertTrue("nothing detached", heard.none { it.startsWith("detach|") })
    }

    /** The arms' book unreadable when Boss asks: nothing is offered (a copy is never left running unsaid). */
    @Test fun aJarvisCloseIsNotOfferedWhileTheArmsBookCannotBeRead() = withFeed {
        heldWithLiveCopy()
        ArmLive.viewForTest(ArmLive.View(open = ArmLive.view.value.open, loaded = true, unreadable = true))
        val (said, act) = prepare(Command(Command.Kind.CLOSE_ONE, number = 1))
        assertEquals(com.optionslab.app.data.PaperHandClose.UNREAD, said)
        assertNull(act)
        assertEquals(65, held())
    }

    // ---- 9. "cancel the last order" --------------------------------------------------------------------------------------------------

    @Test fun cancelTheLastOrderNeverPicksAProtectiveStop() {
        val c = Paper.Contract(sym, "NIFTY", exp, 25_000.0, Right.CE, 65, "NSE_FO|$sym", "NFO")
        val q = Quote(100.0, bid = 99.9, ask = 100.1)
        runBlocking {
            assertTrue(Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, q).ok)
            assertTrue(Paper.place(c, "BUY", 1, "LIMIT", "MIS", 50.0, null, q).ok)        // a plain working order
            assertTrue(Paper.place(c, "SELL", 1, "SL-M", "MIS", null, 80.0, q).ok)        // the position's stop, placed last
        }
        fun working() = runBlocking { Paper.snapshot() }.orders.orders.filter { com.optionslab.ira.AppFacts.isOpen(it.status) }
        val last = com.optionslab.ira.Ask.parse("cancel the last order").command!!
        assertEquals("last", last.target)
        val (said, act) = prepare(last)
        assertTrue(said, said.contains("paper BUY") && !said.contains("stop-loss"))
        runBlocking { act!!() }
        assertEquals("only the stop is left", listOf("SL-M"), working().map { it.priceType })
        // Nothing but the stop: "last" cancels nothing and says why.
        val (again, none) = prepare(last)
        assertNull(none)
        assertTrue(again, again.contains("besides your stop-loss orders"))
        // Named, it can still go - and the confirm says what it is.
        val (named, namedAct) = prepare(Command(Command.Kind.CANCEL_ONE, number = 1))
        assertTrue(named, named.endsWith(IraActions.PROTECTIVE_NOTE))
        assertNotNull(namedAct)
    }
}
