package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.strategy.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The day's diagnostics as the phone gathers them (TODO G3, 13 Oct; the report is ira-core's DiagDay, tested there): the
 * arms' rows, the diary's arm lines as decisions, the health lines, and nothing secret in the file. Fresh phone, made-up values.
 */
class DiagDayHostTest : RobolectricTest() {
    // The diary stamps its lines with the real clock (IST), so the day read is today's.
    private val today: LocalDate = LocalDate.now(com.optionslab.engine.IST)

    private fun report(day: LocalDate = today): Map<*, *> {
        val gz = runBlocking { DiagDayHost.report(day) }
        return Json.parse(String(java.util.zip.GZIPInputStream(gz.inputStream()).readBytes(), Charsets.UTF_8)) as Map<*, *>
    }

    @Test fun aFreshPhoneGivesAWholeReport() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val r = report()
        assertEquals("iraalgo-diagnostics", r["kind"])
        assertEquals(today.toString(), r["day"])
        // Every arm has its row, off on a fresh phone; Liquidity's books are one row.
        val arms = (r["arms"] as List<*>).map { it as Map<*, *> }
        val labels = arms.map { it["label"] }
        assertTrue(labels.toString(), "Liquidity 15+5" in labels && "ORB" in labels)
        assertTrue(arms.all { it["state"] == "off" || it["state"] == "parked" })
        assertEquals(arms.size, labels.distinct().size)
        // The health lines it could read, by name.
        val lines = (r["health"] as Map<*, *>)["lines"] as Map<*, *>
        assertTrue(lines.keys.toString(), "github" in lines.keys)
        lines["slippage"]?.let { assertFalse(it.toString().contains("₹")) }
        // The repository owner's handle is personal: never in the file.
        assertFalse(Json.write(r).contains(com.optionslab.ira.CloudSync.OWNER))
        // The switches as states only.
        val mode = r["mode"] as Map<*, *>
        assertEquals(false, mode["live"])
    }

    @Test fun theDiarysArmLinesAreTheDaysDecisionsAndSecretsStayOut() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val fake = "github_pat_" + "TEST" + "q".repeat(40)
        Diag.record("orb", "ORB: guard_refused: kill switch is on · range 51200.0-51000.0 · bar 10:00 close 51250.5")
        Diag.record("orb", "Range Fade: refused: no quote")
        Diag.record("error", "Upload failed with $fake for order 251013000123456 and Rs 40,000")
        Diag.flush()
        val r = report()
        val ds = (r["decisions"] as List<*>).map { it as Map<*, *> }
        val orb = ds.first { it["arm"] == "ORB" }
        assertEquals("kill_switch", orb["kind"])
        assertEquals("skipped", orb["outcome"])
        assertEquals("10:00", orb["bar"])
        assertEquals("no_quote", ds.first { it["arm"] == "Range Fade" }["kind"])
        // ORB's row counts the skip as today's.
        val row = (r["arms"] as List<*>).map { it as Map<*, *> }.first { it["label"] == "ORB" }
        assertEquals(1L, ((row["today"] as Map<*, *>)["skipped"] as Number).toLong())
        val raw = Json.write(r)
        assertFalse(raw.contains("github_pat_"))
        assertFalse(raw.contains("251013000123456"))
        assertFalse(Regex("Rs\\s*\\d").containsMatchIn(raw))
        assertTrue(((r["health"] as Map<*, *>)["errors"] as Map<*, *>).isNotEmpty())
    }

    @Test fun theHoldersNameWordsOfTwoLettersOrMoreAreAllHandedOver() {
        // 14 Oct review of G3: short name words ("Om") were only taken out as part of the whole name.
        assertEquals(listOf("Om", "Prakash", "Rao"), DiagDayHost.nameWords("R. Om Prakash-Rao"))
        assertEquals(listOf("Om"), DiagDayHost.nameWords("Om om Om"))
        assertEquals(emptyList<String>(), DiagDayHost.nameWords(null))
        assertEquals(emptyList<String>(), DiagDayHost.nameWords(""))
        assertEquals(emptyList<String>(), DiagDayHost.nameWords("A. B."))
    }

    @Test fun theOtherArmsLinesCarryNoRupeeNet() {
        // 14 Oct review of G3: Night, VIX divergence, MCX and the forge ideas say their net "after charges" in rupees.
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        // The arms' lines as the phone has them (ira-core's DiagDayTest feeds each one with rupee amounts).
        val others = (report()["other_arms"] as List<*>).map { it.toString() }
        for (l in others) assertFalse(l, Regex("\\d\\s*after charges").containsMatchIn(l))
    }

    /**
     * 22 Oct (Boss: "No breadth donchain ran today"): the uploaded file cut the passed arms' one long line at 300 characters, so
     * R72 Breadth Donchian, R52 and Solo 2.0 never reached it. Now each arm has its own line, R72 first, each under the cut.
     */
    @Test fun everyPassedArmHasItsOwnLineR72FirstNoneCut() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val others = (report()["other_arms"] as List<*>).map { it.toString() }
        val heads = PassedArmsHost.SOURCES.map { PassedArmsHost.label(it) + ": " }
        for (h in heads) assertTrue(h + others, others.any { it.startsWith(h) })
        val firstPassed = others.indexOfFirst { l -> heads.any { l.startsWith(it) } }
        assertTrue(others[firstPassed], others[firstPassed].startsWith(com.optionslab.engine.orb.BreadthDonchianArm.LABEL + ": "))
        // Each arm's own line starts with its switch and what it holds (the part the cut never reaches).
        for (h in heads) assertTrue(others.first { it.startsWith(h) }, Regex("^.+: (on|off|record only)").containsMatchIn(others.first { it.startsWith(h) }))
    }

    @Test fun anotherDayReadsNothingOfToday() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        Diag.record("orb", "ORB: guard_refused: kill switch is on")
        Diag.flush()
        val r = report(today.minusDays(2))
        assertEquals(today.minusDays(2).toString(), r["day"])
        assertEquals(emptyList<Any?>(), r["decisions"])
    }
}
