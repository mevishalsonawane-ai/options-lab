package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.app.testing.awaitValue
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.OrbRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 22 Oct: the ORB family's bar-close paper entry is priced at its signal and tried once more when the fill finds no price, as
 * Liquidity's ([LiquidityEntryPriceTest]). [OrbArmsDayTest]'s day: the opening range 53,800-54,000, the index at 54,150 from
 * 10:30, so the ORB buys the day's call on the 10:35 pass. Paper only; prices from the fake Upstox feed (no stream).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrbEntryPriceTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private var strike = 0
    private val ceKey = "NSE_FO|ORBPXCE"
    private val peKey = "NSE_FO|ORBPXPE"
    private val swing = listOf(53_900.0, 53_950.0, 54_000.0, 53_950.0, 53_900.0, 53_850.0, 53_800.0, 53_850.0, 53_900.0, 53_900.0)

    private fun price(m: Int): Double = when {
        m < 10 * 60 + 5 -> swing[(m - (9 * 60 + 15)) % swing.size]
        m < 10 * 60 + 30 -> 53_950.0
        else -> 54_150.0
    }

    private fun feed(t: LocalDateTime): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }
        .filter { !it.plusMinutes(1).isAfter(t) }
        .map { start -> val p = price(start.hour * 60 + start.minute)
            Upstox.Bar(start.atZone(IST).toEpochSecond(), p, p, p, p, 1000, 0) }

    @Before fun up() {
        upstox = FakeUpstox()                               // (clears Market.testClock: set it after)
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        day = AutomationSupport.tradingDayAt(9, 50).toLocalDate()
        at(LocalTime.of(9, 50))
        strike = OrbRules.atmStrike(price(9 * 60 + 24))
        val expiry = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, strike.toDouble(), Right.CE, 30, ceKey, "BANKNIFTY-ORBPX-${strike}CE"),
            Upstox.Contract("BANKNIFTY", expiry, strike.toDouble(), Right.PE, 30, peKey, "BANKNIFTY-ORBPX-${strike}PE")))
        upstox.price(ceKey, 300.0)
        upstox.price(peKey, 280.0)
        OrbArms.testIndexBars = { t -> feed(t) }
    }

    @After fun down() {
        com.optionslab.ira.ArmExits.clear()
        AutomationSupport.realClocks()
        Market.testClock = null
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(t: LocalTime) {
        val z = day.atTime(t).atZone(IST)
        OrbArms.testNow = z
        Market.testClock = Clock.fixed(z.toInstant(), IST)
    }

    private fun tick(t: LocalTime) { at(t); runBlocking { OrbArms.tick() } }

    private fun upToTheBreak() {
        at(LocalTime.of(9, 50))
        val msg = runBlocking { OrbArms.setArmed("orb", true, automatic = true) }
        assertTrue(msg, msg.startsWith("ORB armed on paper, fully automatic"))
        var t = LocalTime.of(9, 50)
        while (!t.isAfter(LocalTime.of(10, 34))) { tick(t); t = t.plusMinutes(1) }
        assertTrue("nothing bought before the break", Paper.state.orders.isEmpty())
    }

    private fun arm() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "orb" }
    private fun callReads(from: Int): Int = upstox.requests.drop(from).count { it.contains("ORBPXCE") }

    @Test fun aPlainEntryReadsOnceAndIsNotLabelled() {
        upToTheBreak()
        val from = upstox.requests.size
        tick(LocalTime.of(10, 35))
        val p = arm().today.single()
        assertTrue(p.open); assertEquals(30, p.qty)
        assertTrue("filled at the read price: ${p.entry}", p.entry in 300.0..303.0)
        assertFalse("bought on its first read and order: no label", p.priceRetry)
        assertEquals(1, callReads(from))
    }

    @Test fun anUnusablePriceIsReadAgainAndTheSameCallFillsOnTheSecondOrder() {
        upToTheBreak()
        upstox.scriptNext(ceKey, FakeUpstox.Scripted(listOf(FakeUpstox.Candle(LocalTime.of(10, 34), 300.0, 301.0, 299.0, 320.0))))
        val from = upstox.requests.size
        tick(LocalTime.of(10, 35))
        val p = arm().today.single()
        assertTrue(p.open); assertEquals(30, p.qty)
        assertTrue("filled at the fresh price: ${p.entry}", p.entry in 300.0..303.0)
        assertTrue("labelled: priced again for its fill", p.priceRetry)
        assertEquals(2, callReads(from))
        assertEquals(listOf("cancelled", "complete"), Paper.state.orders.filter { it.action == "BUY" }.map { it.status })
        assertEquals("its resting stop 40 below the fill", 1, Paper.state.orders.count { it.action == "SELL" && it.status == "trigger pending" })
        awaitValue("the retry's diagnostics line") {
            Diag.lines().firstOrNull { it.contains("ORB: ${p.symbol} had no price to fill at; priced again and filled on the second try") }
        }
        // The label survives a restart.
        AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue(arm().today.single().priceRetry)
    }

    @Test fun aFailedFirstReadIsReadOnceMoreAndTheEntryIsLabelled() {
        upToTheBreak()
        upstox.scriptNext(ceKey, FakeUpstox.Scripted(null), FakeUpstox.Scripted(null))
        val from = upstox.requests.size
        tick(LocalTime.of(10, 35))
        val p = arm().today.single()
        assertTrue(p.open); assertTrue(p.priceRetry)
        assertEquals("two failed tries, then one more read", 3, callReads(from))
        assertEquals(listOf("complete"), Paper.state.orders.filter { it.action == "BUY" }.map { it.status })
    }
}
