package com.optionslab.app.data

import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.NightRules
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only, so i can continuously observe it"):
 * Night (R3) switched OFF still makes its 15:20 decision and hands a strong close's 1-ITM call to the switched-off shadow
 * ([OffShadow]) as a carried (NRML) would-be trade out at 09:16 the next session - never a paper order, never its live path.
 * Switched ON, the paper path is unchanged (its buy is placed on paper; the shadow gets nothing).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class NightArmOffShadowTest : RobolectricTest() {
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private lateinit var next: LocalDate
    private lateinit var callKey: String

    private fun at(d: LocalDate, t: LocalTime) { Market.testClock = Clock.fixed(d.atTime(t).plusSeconds(20).atZone(IST).toInstant(), IST) }

    @Before fun up() {
        upstox = FakeUpstox()                                // (clears Market.testClock: set it after)
        TradeFixtures.paperSettings()
        TradeFixtures.killSwitch(false)
        day = AutomationSupport.tradingDayAt(15, 20).toLocalDate()
        next = day.plusDays(1)
        while (!Market.isTradingDay(next)) next = next.plusDays(1)
        at(day, LocalTime.of(15, 20))
        TradeFixtures.seed(context, upstox)
        // NIFTY rises all day to close 15:19 at its high, 24,650 (the 1-ITM call: the 24,600 CE); BANKNIFTY flat.
        for ((u, end) in listOf("NIFTY" to 24_650.0, "BANKNIFTY" to 52_040.0)) {
            val out = ArrayList<FakeUpstox.Candle>()
            var t = LocalTime.of(9, 15)
            var i = 0
            while (!t.isAfter(LocalTime.of(15, 19))) {
                val px = if (u == "NIFTY") 24_500.0 + 150.0 * i / 374 else end
                out += FakeUpstox.Candle(t, px, px + 0.5, px - 0.5, px); t = t.plusMinutes(1); i++
            }
            upstox.minutes[Upstox.INDEX_KEYS.getValue(u)] = out
        }
        // The call trades all afternoon (the thin-option gate lets it through).
        callKey = TradeFixtures.key("NIFTY", TradeFixtures.nearExpiry, 24_600.0, "CE")
        upstox.minutes[callKey] = (0 until 20).map { m -> FakeUpstox.Candle(LocalTime.of(15, m), 100.0, 101.0, 99.0, 100.0) }
        Vault.writeFile(File(context.filesDir, "night.vault"), JSONObject().put("armed", false).toString().toByteArray(Charsets.UTF_8))
        NightArm.init(context)
        AutomationSupport.reloadFromDisk(NightArm)
        HiddenArms.resetForTest(context)
        // A strong close: the previous close 24,500 (+0.6%), breadth 70%, NIFTY's day-end OI build-up far above its history.
        NightArm.testPrevClose = { u -> if (u == "NIFTY") 24_500.0 else 52_040.0 }
        NightArm.testBreadth = { 0.7 }
        NightArm.testBuildUp = { u -> if (u == "NIFTY") 100.0 else null }
        OffShadow.init(context)
        OffShadow.wipe()
        OffShadow.testSession = true
        OffShadow.testPrice = { Triple(100.0, 99.9, 100.1) }
    }

    @After fun down() {
        NightArm.testPrevClose = null
        NightArm.testBreadth = null
        NightArm.testBuildUp = null
        ArmLive.testHeard = null
        OffShadow.wipe()
        HiddenArms.resetForTest(context)
        Market.testClock = null
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun offTheStrongCloseIsACarriedWouldBeTradeOutAt0916NextSessionAndNothingIsBought() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        assertFalse(runBlocking { NightArm.refresh() }.armed)
        // Off, 15:20's buying minutes still want a pass; the afternoon before does not.
        assertTrue(NightArm.wantsPass(LocalTime.of(15, 20, 20)))
        assertFalse(NightArm.wantsPass(LocalTime.of(14, 0)))
        val orders = Paper.state.orders.size
        runBlocking { NightArm.tick() }
        assertEquals("no paper order", orders, Paper.state.orders.size)
        assertTrue(Paper.state.positions.none { it.quantity != 0 })
        assertTrue(NightArm.view.value.open.isEmpty())
        assertTrue(heard.toString(), heard.none { it.startsWith("entered|") })
        val w = OffShadow.view.value.openOf(NightRules.SOURCE).single()
        assertTrue(w.symbol, w.symbol.contains("24600"))
        assertEquals(1, w.view); assertEquals(1, w.side); assertEquals(75, w.qty)
        assertTrue(w.exits.carry)
        assertEquals(next, w.exits.exitOn)
        assertEquals(LocalTime.of(9, 16), w.exits.exitAt)
        assertNull(w.exits.stop); assertNull(w.exits.target)
        val said = NightArm.view.value.status["NIFTY"].orEmpty()
        assertTrue(said, said.startsWith("switched off - "))
        assertTrue(NightArm.diagLine(), NightArm.diagLine().contains(com.optionslab.engine.orb.OffShadow.OFF_WORDS))
        // Decided for the day: a later look asks nothing more.
        runBlocking { NightArm.tick() }
        assertEquals(1, OffShadow.view.value.openOf(NightRules.SOURCE).size)
        // Held overnight: 15:30 today and 09:15 next session still open; 09:16 next session the shadow closes it itself.
        at(day, LocalTime.of(15, 30)); runBlocking { OffShadow.watch() }
        assertEquals(1, OffShadow.view.value.openOf(NightRules.SOURCE).size)
        at(next, LocalTime.of(9, 15)); runBlocking { OffShadow.watch() }
        assertEquals(1, OffShadow.view.value.openOf(NightRules.SOURCE).size)
        at(next, LocalTime.of(9, 16)); runBlocking { OffShadow.watch() }
        assertTrue(OffShadow.view.value.openOf(NightRules.SOURCE).isEmpty())
        assertEquals(1, OffShadow.view.value.closedOf(NightRules.SOURCE).size)
        assertEquals(orders, Paper.state.orders.size)
    }

    @Test fun onThePaperPathIsUnchangedAndTheShadowGetsNothing() {
        assertTrue(runBlocking { NightArm.setArmed(true) }.contains("armed on paper"))
        runBlocking { NightArm.tick() }
        val said = NightArm.view.value.status["NIFTY"].orEmpty()
        // On: the paper buy is placed (filled, or "not filled" when the fake book gives no price), never a would-be trade.
        assertTrue(said, said.startsWith("night_buy_ce") || said.startsWith("night_not_filled"))
        assertTrue(OffShadow.view.value.openOf(NightRules.SOURCE).isEmpty())
        assertTrue(OffShadow.view.value.closedOf(NightRules.SOURCE).isEmpty())
    }
}
