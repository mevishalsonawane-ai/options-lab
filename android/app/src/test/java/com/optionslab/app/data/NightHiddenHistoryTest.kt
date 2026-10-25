package com.optionslab.app.data

import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.ArmsCleanup
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.LocalTime

/**
 * 25 Oct (Boss's decision on the arms audit): Night (R3) hidden by the 15 Oct cleanup still reads the day's OI build-up at 15:20
 * and keeps its history, so a restore is judged against a current window, not a stale one. It buys nothing while hidden.
 */
class NightHiddenHistoryTest : RobolectricTest() {
    @Test fun whileHiddenItStillRecordsTheDaysOiBuildUpAndBuysNothing() {
        val upstox = FakeUpstox()
        try {
            TradeFixtures.paperSettings()
            val day = AutomationSupport.tradingDayAt(15, 20).toLocalDate()
            Market.testClock = Clock.fixed(day.atTime(LocalTime.of(15, 20, 20)).atZone(IST).toInstant(), IST)
            TradeFixtures.seed(context, upstox)
            // Both indices' minutes to 15:19, flat (no entry signal is needed: hidden, it never gets that far).
            for ((u, px) in listOf("NIFTY" to 25_000.0, "BANKNIFTY" to 56_000.0)) {
                val out = ArrayList<FakeUpstox.Candle>()
                var t = LocalTime.of(9, 15)
                while (!t.isAfter(LocalTime.of(15, 19))) { out += FakeUpstox.Candle(t, px, px + 1, px - 1, px); t = t.plusMinutes(1) }
                upstox.minutes[Upstox.INDEX_KEYS.getValue(u)] = out
            }
            Vault.writeFile(File(context.filesDir, "night.vault"), JSONObject().put("armed", false).toString().toByteArray(Charsets.UTF_8))
            NightArm.init(context)
            AutomationSupport.reloadFromDisk(NightArm)
            HiddenArms.resetForTest(context)
            HiddenArms.hideForTest(ArmsCleanup.NIGHT)
            // NIFTY's build-up read today; BANKNIFTY's could not be read (a gap in the option feed).
            NightArm.testBuildUp = { u -> if (u == "NIFTY") 1.5 else null }
            val nifty0 = runBlocking { NightArm.historySizeForTest("NIFTY") }
            val bank0 = runBlocking { NightArm.historySizeForTest("BANKNIFTY") }
            runBlocking { NightArm.tick() }
            assertEquals("NIFTY's history has today's build-up", nifty0 + 1, runBlocking { NightArm.historySizeForTest("NIFTY") })
            assertEquals("no build-up read: nothing added", bank0, runBlocking { NightArm.historySizeForTest("BANKNIFTY") })
            val v = runBlocking { NightArm.refresh() }
            assertEquals(NightArm.HIDDEN_OI, v.status["NIFTY"])
            assertTrue(v.status["BANKNIFTY"].orEmpty(), v.status["BANKNIFTY"].orEmpty().startsWith(NightArm.HIDDEN_OI + " (no build-up read today)"))
            assertTrue("nothing bought while hidden", v.open.isEmpty())
            assertTrue(Paper.state.orders.isEmpty())
            // Decided once a day: the next look adds nothing more.
            runBlocking { NightArm.tick() }
            assertEquals(nifty0 + 1, runBlocking { NightArm.historySizeForTest("NIFTY") })
            // Not hidden and off: it reads nothing at all (as before).
            HiddenArms.resetForTest(context)
            Market.testClock = Clock.fixed(day.plusDays(1).atTime(LocalTime.of(15, 20, 20)).atZone(IST).toInstant(), IST)
            runBlocking { NightArm.tick() }
            assertEquals(nifty0 + 1, runBlocking { NightArm.historySizeForTest("NIFTY") })
        } finally {
            NightArm.testBuildUp = null
            HiddenArms.resetForTest(context)
            Market.testClock = null
            upstox.close()
        }
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }
}
