package com.optionslab.app.ira

import com.optionslab.app.data.Market
import com.optionslab.app.data.SmartWorkers
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * Jarvis learns from the agents ([JarvisLearning]): the nightly pass once a trading day after 15:45 (kept in
 * noBackupFilesDir, one finding, the evening's line), a strong lesson's change put to Boss as a request whose no is kept,
 * and his answers - "what have you learned?", "why do you think that?", "how are you growing?", "what do you know about the
 * OI specialist?" - with a strong lesson added to the trade manager's own answer. Nothing is changed by itself.
 */
class JarvisLearningTest : RobolectricTest() {
    private lateinit var day: LocalDate

    private fun at(t: LocalTime) { Market.testClock = Clock.fixed(day.atTime(t).atZone(IST).toInstant(), IST) }

    private fun ms(d: LocalDate) = d.atTime(11, 0).atZone(IST).toInstant().toEpochMilli()

    /** A settled managed Pine trade on [d]: the OI specialist led an early exit that cost [vs] against the original rules. */
    private fun settled(i: Int, d: LocalDate, vs: Double): TradeManager.Record {
        val t = ms(d)
        val trade = TradeManager.ManagedTrade("pine:x", "t$i", "Pine", "NIFTY", 1, "NIFTY25OCT25000CE", 100.0, 75, 70.0, 160.0,
            TradeManager.Caps(t + 3_600_000L), TradeManager.Account.PAPER, t, 1.0)
        val note = TradeManager.Note(t, TradeManager.EXIT_EARLY, "team", "out", 110.0, true, lead = "oi", voters = listOf("oi"))
        return TradeManager.Record(trade, TradeManager.Mode.ACT, listOf(note), actual = TradeManager.Exit(t, 110.0, "trade manager"),
            manager = TradeManager.Exit(t, 110.0, "trade manager"), original = TradeManager.Exit(t + 1, 110.0 - vs / 75, "STOP"), actedAtMs = t)
    }

    private fun inputs(d: LocalDate) = AgentLessons.Inputs(d, managed = (0 until 40).map { settled(it, d.minusDays(40L - it), -300.0) })

    @Before fun up() {
        assumeTrue("Jarvis's learning is in IraAlgo with Jarvis only", JarvisLearning.enabled())
        day = AutomationSupport.tradingDayAt(16, 0).toLocalDate()
        at(LocalTime.of(16, 0))
        JarvisLearning.resetForTest()
        SmartWorkers.resetForTest()
        JarvisLearning.init(context)
        runBlocking { IraHub.forgetAll() }
    }

    @After fun down() {
        JarvisLearning.resetForTest()
        SmartWorkers.resetForTest()
        runBlocking { IraHub.forgetAll() }
        IraHub.clearRequests()
        Market.testClock = null
    }

    @Test fun theNightlyPassRunsOnceAfterTheCloseKeepsItsFileAndTellsOnce() = runBlocking<Unit> {
        JarvisLearning.testInputs = { d -> inputs(d) }
        // Before 15:45: nothing.
        at(LocalTime.of(15, 0))
        assertFalse(JarvisLearning.learnIfDue(context))
        at(LocalTime.of(16, 0))
        assertTrue(JarvisLearning.learnIfDue(context))
        // Once a day.
        assertFalse(JarvisLearning.learnIfDue(context))
        val b = JarvisLearning.book.value
        assertEquals(day, b.lastPass)
        val oi = b.lessons.first { it.id == "spec:pine:oi" }
        assertEquals(AgentLessons.Confidence.STRONG, oi.confidence)
        // Kept in noBackupFilesDir (never the settings vault), and read back the same.
        val f = File(context.noBackupFilesDir, "jarvis/lessons.txt")
        assertTrue(f.isFile)
        assertEquals(b.lessons.map { it.id }, AgentLessons.decode(f.readText()).lessons.map { it.id })
        // One finding on the bus, and the evening's line.
        val posted = SmartWorkers.bus.recent(System.currentTimeMillis()).filter { it.who == "Jarvis" }
        assertEquals(1, posted.size)
        assertTrue(posted[0].words, posted[0].words.startsWith("Jarvis learned ") && posted[0].words.endsWith(" today"))
        val line = JarvisLearning.eveningLine()
        assertNotNull(line); assertTrue(line!!, line.startsWith("What I learned today:"))
        // Another day's report has no line of today's.
        assertNull(JarvisLearning.eveningLine(day.plusDays(1)))
    }

    @Test fun aStrongLessonIsPutToBossAndHisNoIsKept() = runBlocking<Unit> {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        JarvisLearning.testInputs = { d -> inputs(d) }
        val muted = com.optionslab.app.data.ManagerSpecialists.book.value
        JarvisLearning.runPass(day)
        // Put to Boss as a request (never done by itself): the OI specialist for Pine.
        val pending = IraHub.state.value.pending
        assertEquals(1, pending.size)
        val asked = IraHub.state.value.messages.last { it.action != null }
        assertTrue(asked.text, "OI specialist" in asked.text || "mute" in asked.text.lowercase())
        assertEquals(muted, com.optionslab.app.data.ManagerSpecialists.book.value)
        // His no: kept on the lesson, nothing changed.
        IraHub.cancelAction(pending.first())
        val oi = JarvisLearning.book.value.lessons.first { it.id == "spec:pine:oi" }
        assertEquals(1, oi.rejections)
        assertTrue(oi.weight < 1.0)
        assertEquals(muted, com.optionslab.app.data.ManagerSpecialists.book.value)
    }

    @Test fun hisAnswersCarryTheEvidence() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        JarvisLearning.setForTest(AgentLessons.pass(AgentLessons.Book(), inputs(day)).book)
        IraHub.ask("what have you learned")
        val all = IraHub.state.value.messages.last()
        assertTrue(all.fromIra)
        assertTrue(all.text, all.text.startsWith("From the agents I've learned "))
        assertTrue(all.facts.isNotEmpty() && all.facts.all { Regex("\\d").containsMatchIn(it) })
        IraHub.ask("why do you think that")
        val why = IraHub.state.value.messages.last().text
        assertTrue(why, why.startsWith("The evidence, Boss: ") && "40 settled votes" in why)
        IraHub.ask("how are you growing")
        assertTrue(IraHub.state.value.messages.last().text.startsWith("How I'm growing, Boss: "))
        IraHub.ask("what do you know about the OI specialist")
        assertTrue(IraHub.state.value.messages.last().text.contains("The OI specialist on Pine hurts"))
        // The trade manager's own answer gets one strong lesson, said as his.
        IraHub.ask("how is the trade manager doing")
        val mgr = IraHub.state.value.messages.last().text
        assertTrue(mgr, mgr.contains("What I've learned: The trade manager on Pine hurts"))
        // "Why do you think that" after anything else is not his lessons'.
        assertFalse(JarvisLearning.whyFresh(mgr))
        assertFalse(JarvisLearning.whyFresh(null))
    }

    @Test fun theModelGetsShortNotesOnlyWithNumbers() {
        JarvisLearning.setForTest(AgentLessons.pass(AgentLessons.Book(), inputs(day)).book)
        val notes = JarvisLearning.modelNotes("how is pine doing")
        assertTrue(notes.isNotEmpty())
        assertTrue(notes.sumOf { it.length + 2 } <= AgentLessons.MODEL_CHARS)
        assertTrue(notes.all { Regex("\\d").containsMatchIn(it) })
        JarvisLearning.resetForTest()
        assertTrue(JarvisLearning.modelNotes("how is pine doing").isEmpty())
    }
}
