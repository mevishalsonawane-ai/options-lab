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
        for (k in listOf("app_health", "github", "order_speed", "slippage")) assertTrue("$k: ${lines.keys}", k in lines.keys)
        assertFalse(lines["slippage"].toString().contains("₹"))
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

    @Test fun anotherDayReadsNothingOfToday() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        Diag.record("orb", "ORB: guard_refused: kill switch is on")
        Diag.flush()
        val r = report(today.minusDays(2))
        assertEquals(today.minusDays(2).toString(), r["day"])
        assertEquals(emptyList<Any?>(), r["decisions"])
    }
}
