package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.ira.Command
import com.optionslab.ira.Commands
import com.optionslab.ira.DayStop
import com.optionslab.ira.SegmentLock
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.LocalDate

/**
 * 30 Oct (Boss: "Make stop bot different for index and comodities", then "Or remove commodities from stop bot button"): the day
 * stop split into the INDEX side (the main "Stop index bots" button, the tile) and the COMMODITY side (the MCX card's own "Stop
 * commodities"); Jarvis's "stop all" stops both. Through the real book ([Strategies]), the loss limits ([LossBreaker]), the live
 * door's memory-only read and Jarvis's prepared actions, on a pinned clock.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class SplitStopBotTest : RobolectricTest() {
    private val day = LocalDate.of(2026, 10, 21)          // Wed: NSE and MCX both open

    private fun at(h: Int, m: Int, d: LocalDate = day) {
        Market.testClock = java.time.Clock.fixed(d.atTime(h, m).atZone(IST).toInstant(), IST)
    }

    private fun paper(nse: Double, mcx: Double) {
        LossBreaker.testPaperDay = { LossBreaker.PaperDay(nse + mcx, listOf("NFO" to nse, "MCX" to mcx), listOf("NFO" to 0.0, "MCX" to 0.0)) }
    }

    private fun check() = runBlocking { LossBreaker.check(context) }
    private fun indexStopped() = runBlocking { Strategies.stoppedToday() }
    private fun mcxStopped() = runBlocking { LossBreaker.mcxStopped(Strategies.stoppedWhy(), Strategies.mcxStoppedWhy()) }
    private fun mcxKept() = runBlocking { Strategies.mcxStoppedKept() }

    @Before fun up() {
        com.optionslab.app.testing.Background.clearAlerts()
        SecurePrefs.putAll(mapOf("g.pLoss" to 6_000.0, AppSettings.PAPER_LOSS_VERSION_KEY to 1))
        at(10, 42)
        paper(0.0, 0.0)
    }

    @After fun down() { Market.testClock = null; LossBreaker.testPaperDay = null }

    // ---- the main button: index only ----------------------------------------------------------------------------------------

    @Test fun theMainButtonStopsTheIndexSideOnlyWhileMcxKeepsTrading() {
        val said = runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        assertTrue(said, said.startsWith("Index bots stopped for today") && said.endsWith("The commodity bots keep trading."))
        assertTrue(indexStopped())
        assertEquals(DayStop.Kept(DayStop.Why.BOSS, "10:42"), runBlocking { Strategies.stoppedKept() })
        assertNull("the commodity side is untouched", mcxKept())
        assertFalse("the MCX arms keep trading", mcxStopped())
        assertFalse(runBlocking { LossBreaker.mcxExitStop(Strategies.stoppedWhy(), Strategies.mcxStopSells()) })
        // The live entry door (memory only): an index entry is refused, an MCX one is not.
        assertTrue(Strategies.stopRefusesAtDoor(mcx = false))
        assertFalse(Strategies.stopRefusesAtDoor(mcx = true))
        // The tile's stop is the same side.
        runBlocking { Strategies.startAgain() }
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false, why = DayStop.Why.TILE) }
        assertEquals(DayStop.Why.TILE, runBlocking { Strategies.stoppedWhy() })
        assertFalse(mcxStopped())
        // Starting the index side again leaves the commodity side as it was.
        assertEquals("Index bots running: armed strategies start at their times.", runBlocking { Strategies.startAgain() })
        assertFalse(indexStopped())
        assertFalse(Strategies.stopRefusesAtDoor(mcx = false))
    }

    // ---- the commodity stop alone -------------------------------------------------------------------------------------------

    @Test fun theCommodityStopAloneLeavesTheIndexSideTrading() {
        val said = runBlocking { Strategies.stopCommodities(sell = true) }
        assertEquals("Commodity bots stopped for today: no new MCX entries; the MCX arms sell what they hold through their own exits. " +
            "The index bots keep trading.", said)
        assertFalse("the index side is untouched", indexStopped())
        assertEquals(DayStop.Kept(DayStop.Why.BOSS, "10:42"), mcxKept())
        assertTrue(mcxStopped())
        assertTrue(runBlocking { Strategies.mcxStopSells() })
        assertTrue("it sells what the MCX arms hold", runBlocking { LossBreaker.mcxExitStop(Strategies.stoppedWhy(), Strategies.mcxStopSells()) })
        assertTrue(Strategies.stopRefusesAtDoor(mcx = true))
        assertFalse(Strategies.stopRefusesAtDoor(mcx = false))
        // Pressed again later without the sale: the first time is kept and a sale asked today stays asked.
        at(11, 5)
        runBlocking { Strategies.stopCommodities(sell = false) }
        assertEquals("10:42", mcxKept()?.at)
        assertTrue(runBlocking { Strategies.mcxStopSells() })
        assertEquals("Commodity bots running: the armed MCX arms trade again.", runBlocking { Strategies.startCommoditiesAgain() })
        assertNull(mcxKept())
        assertFalse(mcxStopped())
        assertFalse(Strategies.stopRefusesAtDoor(mcx = true))
        assertEquals("The commodity bots are not stopped.", runBlocking { Strategies.startCommoditiesAgain() })
    }

    @Test fun theCommodityStopWithoutTheSaleStopsEntriesButSellsNothing() {
        runBlocking { Strategies.stopCommodities(sell = false) }
        assertTrue(mcxStopped())
        assertFalse(runBlocking { Strategies.mcxStopSells() })
        assertFalse("held MCX positions keep their own exits", runBlocking { LossBreaker.mcxExitStop(Strategies.stoppedWhy(), Strategies.mcxStopSells()) })
    }

    // ---- Jarvis's "stop all" stops both ------------------------------------------------------------------------------------

    @Test fun stopAllStopsBothSides() {
        val said = runBlocking { Strategies.stopAll(stopRunning = true, compromised = false) }
        assertTrue(said, said.startsWith("Index bots stopped for today") && said.contains("Commodity bots stopped for today"))
        assertFalse("never says the other side keeps trading", said.contains("keep trading"))
        assertTrue(indexStopped())
        assertTrue(mcxStopped())
        assertTrue(runBlocking { Strategies.mcxStopSells() })
    }

    // ---- loss stops, with and without commodities on their own limits ---------------------------------------------------------

    @Test fun anNsePaperLossStopsTheIndexSideOnlyWithTheSplitOn() {
        assertTrue(AppSettings.load().mcxOwnLimits)
        at(11, 30); paper(nse = -6_500.0, mcx = 300.0); check()
        assertEquals(SegmentLock.Trip.PAPER, LossBreaker.tripToday())
        assertEquals(DayStop.Why.LOSS, runBlocking { Strategies.stoppedWhy() })
        assertFalse("the MCX arms keep their own limit", mcxStopped())
        assertFalse(LossBreaker.mcxLossStop(DayStop.Why.LOSS))
        // Start again is refused on the index side; the commodity side was never stopped.
        val refused = runBlocking { Strategies.startAgain() }
        assertTrue(refused, refused.startsWith("Not started: the daily loss limit stopped the index bots today"))
        assertTrue(indexStopped())
        assertEquals("The commodity bots are not stopped.", runBlocking { Strategies.startCommoditiesAgain() })
        // Pressing the main button again keeps the loss stop's reason.
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        assertEquals(DayStop.Why.LOSS, runBlocking { Strategies.stoppedWhy() })
    }

    @Test fun anNsePaperLossStopsBothWithTheSplitOffAndNeitherStartsAgain() {
        DayLockGuard.setSplit(false)
        assertFalse(AppSettings.load().mcxOwnLimits)
        at(11, 30); paper(nse = -6_500.0, mcx = 300.0); check()
        assertEquals(SegmentLock.Trip.SHARED, LossBreaker.tripToday())
        assertTrue(indexStopped())
        assertTrue("one shared limit: both stop", mcxStopped())
        assertTrue(LossBreaker.mcxLossStop(runBlocking { Strategies.stoppedWhy() }))
        assertTrue(Strategies.stopRefusesAtDoor(mcx = true))
        // Neither side starts again today, even after Boss's own commodity stop.
        runBlocking { Strategies.stopCommodities(sell = false) }
        val mcx = runBlocking { Strategies.startCommoditiesAgain() }
        assertTrue(mcx, mcx.startsWith("Not started: the daily loss limit stopped the commodity bots today"))
        assertTrue(mcxStopped())
        assertTrue(runBlocking { Strategies.startAgain() }.startsWith("Not started"))
        assertTrue(indexStopped())
    }

    @Test fun theCommoditiesOwnLossStopsMcxOnlyAndItsStartIsRefused() {
        at(19, 12); paper(nse = 2_000.0, mcx = -6_200.0); check()
        assertTrue(LossBreaker.mcxTrippedToday())
        assertFalse("the index side trades on", indexStopped())
        assertTrue(mcxStopped())
        val said = runBlocking { Strategies.startCommoditiesAgain() }
        assertTrue(said, said.startsWith("Not started: the daily loss limit stopped the commodity bots today"))
        assertTrue(mcxStopped())
        // The main button and its start never touch it.
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false); Strategies.startAgain() }
        assertTrue(mcxStopped())
        assertFalse(indexStopped())
    }

    // ---- the kill switch stays global -----------------------------------------------------------------------------------------

    @Test fun theKillSwitchStaysGlobal() {
        com.optionslab.app.testing.TradeFixtures.killSwitch(true)
        runBlocking { Strategies.stopCommodities(sell = true); Strategies.startCommoditiesAgain() }
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false); Strategies.startAgain() }
        runBlocking { Strategies.stopAll(stopRunning = false, compromised = false) }
        assertTrue("no stop or start of either side touches the kill switch", AppSettings.load().guardKill)
        // The live door refuses an index entry and an MCX entry alike on it.
        for (ex in listOf("NFO", "MCX")) {
            val o = com.optionslab.engine.Kite.Order("NIFTY26OCT25000CE", com.optionslab.engine.Kite.Side.BUY, 65, 65, "NRML", "MARKET", null, exchange = ex)
            assertEquals(ex, "refused: the kill switch is on", Broker.entryRecheck(o))
        }
    }

    // ---- an older build's stop, and a restart ----------------------------------------------------------------------------------

    /** An older build's book: one stop ([why]) for both, no side of its own, no time. */
    private fun olderBook(why: String?) {
        val o = JSONObject().put("defs", JSONArray()).put("runs", JSONObject()).put("history", JSONArray()).put("brokerIds", JSONObject())
            .put("log", JSONArray()).put("nextRunId", 1).put("nextStrategyId", 1).put("stoppedDay", day.toString())
        why?.let { o.put("stoppedWhy", it) }
        Vault.writeFile(File(context.filesDir, "strategies.vault"), o.toString().toByteArray(Charsets.UTF_8))
        Strategies.forgetCacheForTest()
    }

    @Test fun anOlderBuildsStopMigratesAsBothStopped() {
        for (why in listOf("boss", "tile", null)) {
            olderBook(why)
            assertTrue(indexStopped())
            assertEquals(why ?: "boss", mcxKept()?.why?.wire)
            assertNull("an older stop has no time", mcxKept()?.at)
            assertTrue(mcxStopped())
            assertTrue("it sold what MCX held, as before", runBlocking { Strategies.mcxStopSells() })
        }
        // Lifted on one side, the other stays stopped; written back, it is this build's book from now on.
        olderBook("boss")
        runBlocking { Strategies.startCommoditiesAgain() }
        AutomationSupport.reloadFromDisk(Strategies)
        assertNull(mcxKept())
        assertTrue(indexStopped())
    }

    @Test fun anOlderBuildsLossStopFollowsTheLossRulesAsBefore() {
        olderBook("loss")
        assertEquals(DayStop.Why.LOSS, runBlocking { Strategies.stoppedWhy() })
        assertNull("a loss stop is not copied to the commodity side", mcxKept())
        // No account kept for it (an older build): the safe side, both stop - exactly as the older build did.
        assertTrue(mcxStopped())
        assertTrue(LossBreaker.mcxLossStop(DayStop.Why.LOSS))
    }

    @Test fun anOlderBuildsStopKeptForMcxStillHoldsUntilStartAgain() {
        // 21 Oct's key: Boss stopped after the NSE paper loss stop on an older build (the book kept LOSS; the MCX stop was apart).
        SecurePrefs.put(LossBreaker.K_BOSS, day.toString())
        LossBreaker.forgetForTest()
        assertTrue(mcxStopped())
        assertTrue(Strategies.stopRefusesAtDoor(mcx = true))
        assertEquals("Commodity bots running: the armed MCX arms trade again.", runBlocking { Strategies.startCommoditiesAgain() })
        assertFalse(LossBreaker.bossStoppedToday())
        assertFalse(mcxStopped())
    }

    @Test fun aRestartRestoresBothSides() {
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        at(10, 50)
        runBlocking { Strategies.stopCommodities(sell = false) }
        AutomationSupport.reloadFromDisk(Strategies)
        LossBreaker.forgetForTest()
        assertEquals(DayStop.Kept(DayStop.Why.BOSS, "10:42"), runBlocking { Strategies.stoppedKept() })
        assertEquals(DayStop.Kept(DayStop.Why.BOSS, "10:50"), mcxKept())
        assertFalse(runBlocking { Strategies.mcxStopSells() })
        // The lock-free hints for the screens and the door, as read back.
        assertEquals(DayStop.Kept(DayStop.Why.BOSS, "10:42"), Strategies.stopKeptHint())
        assertEquals(DayStop.Kept(DayStop.Why.BOSS, "10:50"), Strategies.mcxStopHint())
        assertTrue(Strategies.stopRefusesAtDoor(mcx = true) && Strategies.stopRefusesAtDoor(mcx = false))
        // The next day both sides run.
        at(9, 15, day.plusDays(1))
        assertFalse(indexStopped())
        assertNull(mcxKept())
        assertNull(Strategies.mcxStopHint())
        assertFalse(Strategies.stopRefusesAtDoor(mcx = true))
    }

    // ---- Jarvis's phrases and confirms -----------------------------------------------------------------------------------------

    private fun prepare(text: String) = runBlocking { com.optionslab.app.ira.IraActions.prepare(Commands.parse(text)!!) }

    @Test fun jarvisStopsAndStartsEachSideThroughTheSameConfirm() {
        val (mcxSaid, mcxAct) = prepare("stop the commodity bots")
        assertTrue(mcxSaid, mcxSaid.startsWith("stop the commodity bots for today"))
        assertTrue("stopping waits for its confirm tap, as stop all does", Commands.parse("stop the commodity bots")!!.kind.reduces)
        assertFalse("nothing happens before the confirm", mcxStopped())
        runBlocking { mcxAct!!() }
        assertTrue(mcxStopped())
        assertFalse(indexStopped())
        val (idxSaid, idxAct) = prepare("stop the index bots")
        assertTrue(idxSaid, idxSaid.startsWith("stop the index bots for today"))
        runBlocking { idxAct!!() }
        assertTrue(indexStopped())
        // "Start the commodity bots again" lifts that side only.
        val (startSaid, startAct) = prepare("start the commodity bots again")
        assertTrue(startSaid, startSaid.startsWith("start the commodity bots again"))
        runBlocking { startAct!!() }
        assertFalse(mcxStopped())
        assertTrue(indexStopped())
        // Asked again with nothing stopped: said, nothing to confirm.
        val (again, none) = prepare("start the commodity bots again")
        assertNull(none)
        assertEquals("The commodity bots are not stopped.", again)
    }

    @Test fun jarvisStopAllStopsBothAndStartAllStartsBoth() {
        val (said, act) = prepare("stop all")
        assertEquals("stop every strategy and arm for today (and close what they hold)", said)
        runBlocking { act!!() }
        assertTrue(indexStopped()); assertTrue(mcxStopped())
        val (startSaid, start) = prepare("start all")
        assertTrue(startSaid, startSaid.contains("lift today's stop") && startSaid.contains("start the commodity bots again"))
        runBlocking { start!!() }
        assertFalse(indexStopped()); assertFalse(mcxStopped())
    }

    @Test fun jarvisNeverStartsASideALossStopped() {
        at(19, 12); paper(nse = 2_000.0, mcx = -6_200.0); check()
        val (said, act) = prepare("start the commodity bots again")
        assertNull("nothing to confirm", act)
        assertTrue(said, said.startsWith("Not started: the daily loss limit stopped the commodity bots today"))
        assertTrue(mcxStopped())
    }

    @Test fun jarvisChecksTheSideItStopped() {
        // The self-check reads the side that was stopped ("stop all": both).
        val stopped = runBlocking { Strategies.stopCommodities(sell = true) }
        val r = runBlocking { com.optionslab.app.ira.IraActions.verified(Command.Kind.STOP_ALL, stopped, DayStop.Side.MCX) }
        assertTrue(r, r.endsWith("Checked: it took effect."))
        val bad = runBlocking { com.optionslab.app.ira.IraActions.verified(Command.Kind.STOP_ALL, "Stopped.", null) }
        assertTrue("the index side is not stopped, so stop all did not take", bad.startsWith("Not done as asked"))
    }

    // ---- every arm reads its own side ------------------------------------------------------------------------------------------

    private fun src(name: String): String = listOf(File("src/main/java/com/optionslab/app/data/$name"), File("app/src/main/java/com/optionslab/app/data/$name"))
        .firstOrNull { it.isFile }?.readText() ?: error("source $name not found")

    @Test fun everyNseArmReadsTheIndexSideAndTheMcxArmsTheCommoditySide() {
        val nse = listOf("GrammarArm.kt", "FlowArm.kt", "PassedArmsHost.kt", "HeroTicketArm.kt", "CisdArm.kt", "NtfyArm.kt", "Ft3Arm.kt",
            "S5Arm.kt", "VixDivArm.kt", "NightArm.kt", "OrbArms.kt", "ShadowArms.kt", "PineAuto.kt")
        for (f in nse) {
            val s = src(f)
            assertTrue("$f reads the index side's stop", s.contains("Strategies.stoppedToday()") || s.contains("Strategies.stoppedWhy()"))
            assertFalse("$f never reads the commodity side", s.contains("mcxStoppedWhy") || s.contains("mcxStoppedKept") || s.contains("mcxStopHint"))
        }
        val mcx = src("McxPaperArms.kt")
        assertTrue(mcx.contains("Strategies.mcxStoppedWhy()") && mcx.contains("Strategies.mcxStopSells()"))
        assertFalse("the MCX arms never read the index stop as theirs", mcx.contains("Strategies.stoppedToday()"))
        // The live mirror reads each arm's own side.
        assertTrue(src("ArmLive.kt").contains("sideStopped(LiveMirror.spec(key)?.exchange == \"MCX\")"))
        assertTrue(src("Broker.kt").contains("Strategies.stopRefusesAtDoor(mcx = o.exchange == \"MCX\")"))
    }

    @Test fun eachSidesStopAndStartIsLoggedNeverALossStop() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", com.optionslab.app.ira.Autopilot.enabled())
        fun entries() = com.optionslab.app.ira.Autopilot.book.value.entries.filter { it.change.kind == com.optionslab.ira.AutoChange.Kind.DAY_STOP }
        fun waitFor(n: Int) { val until = System.currentTimeMillis() + 10_000; while (entries().size < n && System.currentTimeMillis() < until) Thread.sleep(20) }
        runBlocking { Strategies.stopCommodities(sell = true) }
        waitFor(1)
        val e = entries().single()
        assertEquals("daystop:mcx", e.change.target)
        assertEquals(com.optionslab.ira.AutoChange.STOPPED, e.change.to)
        assertTrue(e.change.reason, e.change.reason.contains("at 10:42"))
        // Its Undo starts the commodity side again (no PIN: paper).
        assertFalse(com.optionslab.app.ira.Autopilot.undoNeedsPin(e.id))
        val said = com.optionslab.app.ira.Autopilot.undo(e.id)
        assertTrue(said, !mcxStopped())
        // A loss stop is never logged as Boss's.
        at(11, 30); paper(nse = -6_500.0, mcx = 0.0); check()
        Thread.sleep(200)
        assertTrue(entries().none { it.change.target == "daystop:index" })
    }
}
