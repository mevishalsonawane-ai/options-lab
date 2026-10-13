package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.LessonsBundle
import com.optionslab.ira.OrderReview
import com.optionslab.ira.SharedKnowledge
import com.optionslab.ira.VariantLab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The shared knowledge on the phone (13 Oct): built from the bundled pack in the background, read from memory by the agents
 * (the order review's flaws cite the research, a rejected variant is not tested again, order flow says it is unproven), each
 * agent's line for the sheet, the shadow switch off by default, and the nightly lessons bundle under the recordings folder with
 * Boss's money encrypted - decrypted only in the PIN-confirmed export.
 */
class SharedKnowledgeHostTest : RobolectricTest() {
    @Before fun up() {
        SharedKnowledgeHost.resetForTest()
        SharedKnowledgeHost.attachForTest(context)
        LiveRecorder.attachForTest(context)
        LiveRecorder.resetForTest()
    }

    @After fun down() {
        SharedKnowledgeHost.resetForTest()
        LiveRecorder.detachForTest()
    }

    private fun built(): SharedKnowledge.Snapshot = checkNotNull(SharedKnowledgeHost.refresh(LocalDate.of(2026, 10, 13)))

    @Test fun itIsBuiltFromTheBundledPackAndReadFromMemory() {
        assertEquals(0, SharedKnowledgeHost.snapshot.size)
        val s = built()
        assertTrue(s.size > 100)
        assertTrue(SharedKnowledgeHost.snapshot === s)
        assertTrue(SharedKnowledgeHost.diagLine(), SharedKnowledgeHost.diagLine().startsWith("Shared knowledge:"))
        // Order flow says the research verdict beside its log.
        val v = FlowGate.researchVerdict()
        assertNotNull(v)
        assertTrue(v!!, "still unproven" in v)
    }

    @Test fun theShadowSwitchIsOffByDefault() {
        assertFalse(SharedKnowledgeHost.shadowOn)
        assertFalse(SharedKnowledgeHost.shadowInMemory())
        SharedKnowledgeHost.shadowOn = true
        assertTrue(SharedKnowledgeHost.shadowInMemory())
        SharedKnowledgeHost.shadowOn = false
    }

    @Test fun orderReviewFlawsCiteTheResearchAndKeepTheirNumbers() {
        val day = LocalDate.of(2026, 10, 13)
        val flaw = OrderReview.Flaw("t1", "ORB", OrderReview.Venue.PAPER, OrderReview.Category.CHARGES_EAT, OrderReview.Severity.WARN, "9 trades, gross 120 vs charges 700", 580.0)
        val d = OrderReview.Day(day, 9, 9, listOf(flaw), emptyList())
        // Before the knowledge is built: as found.
        assertEquals(d, OrderReviewHost.cited(d))
        built()
        val c = OrderReviewHost.cited(d).flaws.single()
        assertTrue(c.evidence, c.evidence.startsWith("9 trades, gross 120 vs charges 700 - research:"))
        assertEquals(580.0, c.rupees, 0.0)
        assertEquals(flaw.category, c.category)
        assertEquals(c, OrderReviewHost.cited(OrderReviewHost.cited(d)).flaws.single())
        assertTrue(SharedKnowledgeHost.agentLines().first { it.startsWith("Order review:") }.contains("read the research behind 1 flaw"))
    }

    @Test fun aVariantTheResearchRejectedIsAnsweredAtOnceAndNotTested() {
        built()
        val seed = VariantLab.Seed("review:orb:test", "orb", com.optionslab.ira.ArmParams.SIDE_CAP, +1, "re-entries")
        VariantRunner.ask(seed)
        val r = VariantRunner.seededResult(seed.id)
        if (VariantRunner.enabled()) {
            assertNotNull(r)
            assertTrue(r!!.final && r.status.startsWith("not tested: the research rejected"))
        } else assertNull(r)
    }

    @Test fun everyAgentHasALineForTheSheet() {
        val lines = SharedKnowledgeHost.agentLines()
        assertEquals(SharedKnowledgeHost.AGENTS.size, lines.size)
        assertTrue(lines.all { "read nothing yet today" in it && "added nothing yet" in it })
        SharedKnowledgeHost.read("Strategy lab", "the research's verdicts as the forge's seeds")
        SharedKnowledgeHost.added("Strategy lab", "3 ideas tried")
        val lab = SharedKnowledgeHost.lines.value.first { it.startsWith("Strategy lab:") }
        assertEquals("Strategy lab: read the research's verdicts as the forge's seeds; added 3 ideas tried.", lab)
    }

    @Test fun theNightlyBundleKeepsMoneyEncryptedAndThePinExportDecryptsIt() {
        val s = built()
        val day = Market.today()
        com.optionslab.app.ira.JarvisLearning.resetForTest()
        val trips = (1..30).flatMap { k ->
            val d = day.minusDays(k.toLong())
            listOf(com.optionslab.ira.Insights.Trip("BANKNIFTY26OCT52000CE", d.atTime(10, 0), d.atTime(10, 30), 777.0, "ORB"),
                com.optionslab.ira.Insights.Trip("BANKNIFTY26OCT52000CE", d.atTime(11, 0), d.atTime(11, 30), -100.0, "ORB"))
        }
        com.optionslab.app.ira.JarvisLearning.setForTest(AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(day, trips = trips)).book)
        SharedKnowledgeHost.writeBundle(day, s, listOf(SharedKnowledge.ShadowSkip("research verdict: does not pay", "orb", 2, 2, -4321.0, "ORB: why")))
        val f = File(File(File(context.noBackupFilesDir, "recordings"), day.toString()), LessonsBundle.FILE)
        assertTrue(f.isFile)
        val text = f.readText()
        assertTrue(text.contains("\"money_enc\":"))
        assertFalse("no money in the clear", Regex("\"(rs|net|money|pnl)\"").containsMatchIn(text.replace("\"money_enc\"", "")))
        assertFalse("4321" in text)
        assertEquals(listOf(f), SharedKnowledgeHost.bundleFiles())
        // The PIN-confirmed export: the bundle with its money decrypted.
        val out = java.io.ByteArrayOutputStream()
        assertEquals(1, LiveRecorder.exportDecrypted(out))
        val entries = LinkedHashMap<String, String>()
        java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        val opened = entries.getValue("$day/${LessonsBundle.FILE}")
        assertTrue(opened, opened.contains("\"money\":{") && "4321" in opened && "money_enc" !in opened)
        // The plain export zip keeps it as stored (still encrypted).
        val zip = LiveRecorder.exportZip(context)
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.toString(), "$day/${LessonsBundle.FILE}" in names)
        com.optionslab.app.ira.JarvisLearning.resetForTest()
    }
}
