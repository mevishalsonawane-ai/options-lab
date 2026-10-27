package com.optionslab.app.data

import android.content.Intent
import android.os.BatteryManager
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Battery
import com.optionslab.app.work.BatteryMeter
import com.optionslab.app.work.Tasks
import com.optionslab.app.work.WatchWorkers
import com.optionslab.engine.Right
import com.optionslab.engine.mcx.McxContract
import com.optionslab.engine.sandbox.Quote
import com.optionslab.ira.AnalysisRest
import com.optionslab.ira.LoopMeter
import com.optionslab.ira.McxRest
import com.optionslab.ira.StreamHealth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate
import java.time.LocalTime

/**
 * Battery audit, 27 Oct (Boss: "the battery drained a lot today"; android/BATTERY_AUDIT_2026-10-27.md): the knowledge and
 * words-only analysis rests with nothing held on a low battery or under the saver; anything held keeps every check, the
 * stream and the analysis at full pace whatever the battery; the phone's battery, saver and screen are asked of Android at
 * most every 30 s; the day's "Battery today" line is in the uploaded diagnostics and survives a restart.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class BatteryAuditTest : RobolectricTest() {
    private fun at(day: String, time: String) {
        Market.testClock = java.time.Clock.fixed(LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(com.optionslab.engine.IST).toInstant(),
            com.optionslab.engine.IST)
    }

    /** The phone's battery at [pct], [charging] or not, and Android's battery saver [saver]; read afresh. */
    private fun phone(pct: Int, charging: Boolean, saver: Boolean) {
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, pct).putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_STATUS, if (charging) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING))
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsPowerSaveMode(saver)
        Battery.resetForTest()
    }

    // A Thursday: NSE open to 15:40, MCX trading to 23:30. 22:30 is outside every MCX arm's entry minutes.
    private val day = "2026-10-08"
    private val option = McxContract(148600327L, 580470L, "CRUDEOIL26OCT8550CE", "CRUDEOIL", LocalDate.of(2026, 10, 15), 8550.0, Right.CE, 0.1, 100)

    /** The books read (as the watch's pass has done by then), so the memory-only checks know them; nothing live on MCX. */
    private fun booksRead() = runBlocking {
        Paper.snapshot(); Protections.active(); McxMarket.liveExposure(); McxPaperArms.armedNow(); McxMarket.noteLive(emptyList())
    }

    @Before fun up() { Battery.resetForTest(); BatteryMeter.resetForTest(context) }

    @After fun down() {
        Market.testClock = null
        Battery.resetForTest()
        Tasks.wordsRoundAt = 0L
        KiteStream.want(TEST_OWNER, emptyList())
        KiteStream.resetForTest()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun aHeldPositionKeepsFullCadenceAndTheStreamWithTheSaverOnAndTheBatteryAt10Percent() = runBlocking {
        at(day, "22:30")
        val r = Paper.place(McxMarket.paperContract(option), "BUY", 1, "MARKET", "NRML", null, null, Quote(242.0))
        assertTrue(r.message, r.ok)
        booksRead()
        phone(pct = 10, charging = false, saver = true)
        assertEquals(10 to false, Battery.state(context))
        assertTrue(WatchWorkers.saver(context))
        assertTrue(HeldNow.any(mcxLive = true))
        // The MCX watch: its 15 s pass, and the watch runs for it.
        assertEquals(McxRest.HELD_MS, McxMarket.watchCadenceMs())
        assertTrue(McxMarket.watchDue())
        // The analysis keeps its pace: held is FULL whatever the battery.
        assertEquals(AnalysisRest.Pace.FULL, Battery.analysisPace(context, HeldNow.any(mcxLive = true)))
        assertNotNull("the Candle agent goes on with an MCX position held", CandleAgentHost.delayMs(context))
        Tasks.wordsRoundAt = System.currentTimeMillis()
        val words = Tasks.wordsDelay(context)
        assertNotNull(words)
        assertTrue("words keep the saver's 2 minutes, not the rest's 5: $words", words!! <= 120_000L)
        // The stream: a held instrument's owner keeps it needed (the big-move recorder's own hold rests at 10%).
        KiteStream.want(TEST_OWNER, listOf(option.token))
        assertTrue(KiteStream.needed())
        assertTrue("MCX's hours with something held are the stream's hours", KiteStream.inHoursNow())
        // A flapping network while held: the reconnect waits are exactly as before (never past 4 s).
        var w = 0L
        repeat(6) { w = StreamHealth.waitFor(w, 20_000L, StreamHealth.Cause.NETWORK_CHANGED, held = true, jitter = 1.0) }
        assertEquals(StreamHealth.NETWORK_WAIT_MS, w)
    }

    @Test fun nothingHeldTheAnalysisRestsOnALowBattery() = runBlocking {
        at(day, "11:00")
        booksRead()
        assertFalse(HeldNow.any(mcxLive = true))
        phone(pct = 25, charging = false, saver = false)
        assertEquals(AnalysisRest.Pace.REST, Battery.analysisPace(context, false))
        assertNull("the Candle agent rests", CandleAgentHost.delayMs(context))
        Tasks.wordsRoundAt = System.currentTimeMillis()
        val words = Tasks.wordsDelay(context)!!
        assertTrue("Jarvis's words every 5 minutes: $words", words in 290_000L..AnalysisRest.REST_WORDS_MS)
        assertFalse("the big-move recorder lets the stream go", OrderFlowLive.keepStreamOn())
        // On the charger: back to the normal pace.
        phone(pct = 25, charging = true, saver = false)
        assertEquals(AnalysisRest.Pace.FULL, Battery.analysisPace(context, false))
        assertNotNull(CandleAgentHost.delayMs(context))
        assertTrue(Tasks.wordsDelay(context)!! <= 60_000L)
    }

    @Test fun nothingHeldOnMcxsEveningTheCandleAgentRestsButAHeldMcxPositionKeepsItAndTheWatchAtFullSpeed() = runBlocking {
        at(day, "22:30")
        booksRead()
        phone(pct = 80, charging = false, saver = false)
        assertNull("nothing held on MCX at 22:30: the Candle agent rests", CandleAgentHost.delayMs(context))
        assertEquals(McxRest.IDLE_MS, McxMarket.watchCadenceMs())
        // A paper silver short opens: the MCX watch goes to 15 s and the agent reads again.
        val r = Paper.place(McxMarket.paperContract(option), "SELL", 1, "MARKET", "NRML", null, null, Quote(242.0))
        assertTrue(r.message, r.ok)
        booksRead()
        assertEquals(McxRest.HELD_MS, McxMarket.watchCadenceMs())
        assertNotNull(CandleAgentHost.delayMs(context))
    }

    @Test fun theBatteryAndTheSaverAreAskedOfAndroidAtMostOnceIn30Seconds() {
        phone(pct = 50, charging = false, saver = false)
        val before = Battery.reads()
        repeat(500) { Battery.state(context); Battery.powerSave(context); Battery.screenOn(context); Battery.gap(context, 3_000) }
        assertTrue("500 rounds asked Android ${Battery.reads() - before} times", Battery.reads() - before <= 3)
    }

    @Test fun theUploadedDiagnosticsCarryTheDaysBatteryLineAndItSurvivesARestart() = runBlocking {
        val today = Market.today()
        BatteryMeter.meter.count(LoopMeter.CANDLE_BATCHES)
        val input = DiagDayHost.input(today)
        val line = input.health["battery"]
        assertNotNull(input.health.keys.toString(), line)
        assertTrue(line!!, line.startsWith("Battery today: ") && line.contains("Candle agent") && line.contains("stream on"))
        // Saved when read (the app reads it in DiagDayHost.report, on IO); a new process reads the figures back.
        val saved = BatteryMeter.kept()[java.time.Instant.now().atZone(com.optionslab.engine.IST).toLocalDate()]
        assertNotNull("saved when the line was read", saved)
        assertTrue(saved!!.counts[LoopMeter.CANDLE_BATCHES]!! >= 1)
        // A day the app never saw.
        assertTrue(BatteryMeter.lineFor(LocalDate.of(2020, 1, 1)).contains("not kept"))
    }

    @Test fun androidsMemoryTrimSavesTheFiguresOnABackgroundThread() {
        BatteryMeter.meter.count(LoopMeter.WORDS_ROUNDS)
        val main = Thread.currentThread().name
        BatteryMeter.saveSoon()
        val until = System.currentTimeMillis() + 5_000
        while (BatteryMeter.lastSaveThread == null && System.currentTimeMillis() < until) Thread.sleep(20)
        assertNotNull(BatteryMeter.lastSaveThread)
        assertFalse("saved on ${BatteryMeter.lastSaveThread}, not the caller's $main", BatteryMeter.lastSaveThread == main)
    }

    private companion object { const val TEST_OWNER = "battery-audit-test" }
}
