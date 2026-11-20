package com.optionslab.app.data

import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.LocalTime

/**
 * 20 Nov 2026 (audit: "Night loses the whole day on one failed read"): a 15:20 decision whose index read fails is not the day's
 * verdict. It is tried again after a back-off (10 s, doubling, at most 40 s) inside the entry window, and decided once a read works.
 */
class NightArmRetryTest : RobolectricTest() {
    @Test fun theBackOffDoublesFromTenSecondsAndStopsAtForty() {
        assertEquals(10_000L, NightArm.retryWaitMs(1))
        assertEquals(20_000L, NightArm.retryWaitMs(2))
        assertEquals(40_000L, NightArm.retryWaitMs(3))
        assertEquals(40_000L, NightArm.retryWaitMs(9))
        assertEquals("never below the first wait", 10_000L, NightArm.retryWaitMs(0))
    }

    @Test fun aFailedIndexReadIsTriedAgainAfterItsBackOffNotLostForTheDay() {
        val upstox = FakeUpstox()
        try {
            TradeFixtures.paperSettings()
            val day = AutomationSupport.tradingDayAt(15, 20).toLocalDate()
            fun at(t: LocalTime) { Market.testClock = Clock.fixed(day.atTime(t).atZone(IST).toInstant(), IST) }
            at(LocalTime.of(15, 20, 5))
            TradeFixtures.seed(context, upstox)
            for ((u, px) in listOf("NIFTY" to 25_000.0, "BANKNIFTY" to 56_000.0)) {
                val out = ArrayList<FakeUpstox.Candle>()
                var t = LocalTime.of(9, 15)
                while (!t.isAfter(LocalTime.of(15, 19))) { out += FakeUpstox.Candle(t, px, px + 1, px - 1, px); t = t.plusMinutes(1) }
                upstox.minutes[Upstox.INDEX_KEYS.getValue(u)] = out
            }
            // NIFTY's index minutes fail on every try of this look (the feed answers 503 three times).
            val nifty = Upstox.INDEX_KEYS.getValue("NIFTY")
            upstox.scriptNext(nifty, FakeUpstox.Scripted(null), FakeUpstox.Scripted(null), FakeUpstox.Scripted(null))
            // Switched off: the decision runs in full in shadow (nothing bought), so the day's verdict is plain to read.
            Vault.writeFile(File(context.filesDir, "night.vault"), JSONObject().put("armed", false).toString().toByteArray(Charsets.UTF_8))
            NightArm.init(context)
            AutomationSupport.reloadFromDisk(NightArm)
            NightArm.forgetRetriesForTest()
            NightArm.testBuildUp = { 1.5 }
            val nifty0 = runBlocking { NightArm.historySizeForTest("NIFTY") }

            runBlocking { NightArm.tick() }
            var v = runBlocking { NightArm.refresh() }
            assertTrue("NIFTY waits to try again: ${v.status["NIFTY"]}", v.status["NIFTY"].orEmpty().startsWith("night_retrying"))
            assertEquals("BANKNIFTY's read worked: decided", "night_no_previous_close", v.status["BANKNIFTY"])
            assertEquals("nothing recorded for NIFTY by the failed try", nifty0, runBlocking { NightArm.historySizeForTest("NIFTY") })

            // Inside its back-off: not tried again yet.
            at(LocalTime.of(15, 20, 10))
            val reads = upstox.requests.size
            runBlocking { NightArm.tick() }
            assertTrue(runBlocking { NightArm.refresh() }.status["NIFTY"].orEmpty().startsWith("night_retrying"))
            assertEquals("no read inside the back-off", reads, upstox.requests.size)

            // After it (10 s): tried again; the read works now and the day is decided.
            at(LocalTime.of(15, 20, 16))
            runBlocking { NightArm.tick() }
            v = runBlocking { NightArm.refresh() }
            assertEquals("night_no_previous_close", v.status["NIFTY"])
            assertEquals("the day's build-up recorded once", nifty0 + 1, runBlocking { NightArm.historySizeForTest("NIFTY") })
            // Decided once a day: a later look adds nothing.
            at(LocalTime.of(15, 21, 0))
            runBlocking { NightArm.tick() }
            assertEquals(nifty0 + 1, runBlocking { NightArm.historySizeForTest("NIFTY") })
            assertTrue("nothing bought (switched off)", Paper.state.orders.isEmpty())
        } finally {
            NightArm.testBuildUp = null
            NightArm.forgetRetriesForTest()
            Market.testClock = null
            upstox.close()
        }
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }
}
