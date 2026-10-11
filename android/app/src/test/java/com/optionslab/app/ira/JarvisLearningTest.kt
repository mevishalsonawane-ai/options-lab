package com.optionslab.app.ira

import com.optionslab.app.data.Market
import com.optionslab.app.data.SmartWorkers
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.async
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

    /** Asks Jarvis [q] and waits for his answer: these are answered off the main thread (review R5 #12). */
    private fun askAndWait(q: String) {
        val start = IraHub.state.value.messages.maxOfOrNull { it.id } ?: 0L
        IraHub.ask(q)
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && IraHub.state.value.messages.none { it.fromIra && it.id > start }) Thread.sleep(10)
    }
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

    @Test fun aPassWhoseRecordsCannotBeReadIsNotMarkedDoneAndIsTriedAgainLater() = runBlocking<Unit> {
        at(LocalTime.of(16, 0))
        JarvisLearning.testInputs = { throw IllegalStateException("records unreadable") }
        assertFalse(JarvisLearning.learnIfDue(context))
        assertTrue(JarvisLearning.book.value.lastPass != day)
        JarvisLearning.testInputs = { d -> inputs(d) }
        assertFalse("not retried on every look", JarvisLearning.learnIfDue(context))
        at(LocalTime.of(16, 11))
        assertTrue("the day's learning is not lost", JarvisLearning.learnIfDue(context))
        assertEquals(day, JarvisLearning.book.value.lastPass)
    }

    @Test fun twoAsksAtOnceRunOnePass() = runBlocking<Unit> {
        at(LocalTime.of(16, 0))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        JarvisLearning.testInputs = { d ->
            calls.incrementAndGet(); entered.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS); inputs(d)
        }
        val first = async(kotlinx.coroutines.Dispatchers.IO) { JarvisLearning.learnIfDue(context) }
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse("the second ask while a pass runs", JarvisLearning.learnIfDue(context))
        release.countDown()
        assertTrue(first.await())
        assertFalse(JarvisLearning.learnIfDue(context))
        assertEquals(1, calls.get())
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
        // Kept off the main thread (G6-7): the tap never waits on the lessons' lock.
        com.optionslab.app.testing.Background.await("his no kept") {
            JarvisLearning.book.value.lessons.first { it.id == "spec:pine:oi" }.rejections == 1
        }
        val oi = JarvisLearning.book.value.lessons.first { it.id == "spec:pine:oi" }
        assertEquals(1, oi.rejections)
        assertTrue(oi.weight < 1.0)
        assertEquals(muted, com.optionslab.app.data.ManagerSpecialists.book.value)
    }

    @Test fun hisAnswersCarryTheEvidence() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        JarvisLearning.setForTest(AgentLessons.pass(AgentLessons.Book(), inputs(day)).book)
        askAndWait("what have you learned")
        val all = IraHub.state.value.messages.last()
        assertTrue(all.fromIra)
        assertTrue(all.text, all.text.startsWith("From the agents I've learned "))
        assertTrue(all.facts.isNotEmpty() && all.facts.all { Regex("\\d").containsMatchIn(it) })
        askAndWait("why do you think that")
        val why = IraHub.state.value.messages.last().text
        assertTrue(why, why.startsWith("The evidence, Boss: ") && "40 settled votes" in why)
        askAndWait("how are you growing")
        assertTrue(IraHub.state.value.messages.last().text.startsWith("How I'm growing, Boss: "))
        askAndWait("what do you know about the OI specialist")
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

    @Test fun onALockedPhoneHisLessonsAreCountsOnly() {
        // G6-1: the lessons carry the bots' rupees (live round trips among them) - never said on a locked phone.
        val b = AgentLessons.pass(AgentLessons.Book(), inputs(day)).book
        JarvisLearning.setForTest(b)
        val money = Regex("Rs|₹")
        val (open, openFacts) = JarvisLearning.answer(AgentLessons.Ask(AgentLessons.AskKind.ALL))
        assertTrue(open, money.containsMatchIn(open + openFacts.joinToString()))
        for (k in AgentLessons.AskKind.entries) {
            val (said, facts) = JarvisLearning.answer(AgentLessons.Ask(k, AgentLessons.Topic.SPECIALIST), locked = true)
            assertEquals(AgentLessons.sayLocked(b), said)
            assertFalse(said, money.containsMatchIn(said))
            assertTrue(facts.isEmpty())
        }
        assertEquals(AgentLessons.LOCKED, JarvisLearning.why(locked = true))
    }

    @Test fun hisNoAndAutopilotsWordNeverWaitOnTheLessonsLock() {
        // G6-7: a consolidation (or a pass) holds the lessons' lock across its work and its save; "no" is tapped on the main thread.
        JarvisLearning.setForTest(AgentLessons.pass(AgentLessons.Book(), inputs(day)).book)
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { JarvisLearning.consolidate { b -> holding.countDown(); release.await(20, java.util.concurrent.TimeUnit.SECONDS); b to 0 } }
        worker.start()
        try {
            assertTrue(holding.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val t0 = System.nanoTime()
            JarvisLearning.answered("spec:pine:oi", false)
            JarvisLearning.autoStatus("spec:pine:oi", "applied")
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("the callers returned at once (${ms} ms), not after the lock", ms < 5_000)
        } finally { release.countDown(); worker.join(20_000) }
        com.optionslab.app.testing.Background.await("his no and Autopilot's word kept") {
            JarvisLearning.book.value.lessons.first { it.id == "spec:pine:oi" }.let { it.rejections == 1 && it.auto != null }
        }
    }

    @Test fun aLessonsFileThatCannotBeReadIsNeverSavedOver() = runBlocking<Unit> {
        // G6-8: one failed read of lessons.txt must not be taken as "no lessons" and written over.
        val kept = AgentLessons.pass(AgentLessons.Book(), inputs(day.minusDays(1))).book
        val f = File(context.noBackupFilesDir, "jarvis/lessons.txt").apply { parentFile?.mkdirs(); writeText(AgentLessons.encode(kept)) }
        val text = f.readText()
        JarvisLearning.reloadForTest()
        JarvisLearning.testRead = { throw java.io.IOException("read failed") }
        JarvisLearning.ensureLoaded()
        JarvisLearning.testInputs = { d -> inputs(d) }
        assertNull("no pass on an unread book", JarvisLearning.runPass(day))
        JarvisLearning.answered("spec:pine:oi", false)
        assertEquals(0, JarvisLearning.consolidate { it to 0 })
        Thread.sleep(300)
        assertEquals("the file is as it was", text, f.readText())
        // The next read works: his lessons are back, and the pass runs on them.
        JarvisLearning.testRead = null
        JarvisLearning.ensureLoaded()
        assertEquals(kept.lessons.map { it.id }, JarvisLearning.book.value.lessons.map { it.id })
        assertNotNull(JarvisLearning.runPass(day))
    }
}
