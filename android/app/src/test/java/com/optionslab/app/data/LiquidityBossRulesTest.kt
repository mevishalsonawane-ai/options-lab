package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
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
 * 25 Oct (Boss's decisions on the audits): Liquidity's PAPER books skip their index's own expiry day (every study skipped it), and
 * buy with research h14's capped limit (0.5% over the signal's price, worked 3 minutes, never chasing). Both are logged; capped
 * trades are labelled apart from the plain-rules record. The synthetic BANKNIFTY day is [LiquidityArmTest]'s: the 5-minute book
 * buys the 54,000 CE at 13:05.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiquidityBossRulesTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|LIQCE"

    private fun bar(k: Int): DoubleArray = when (k) {
        20 -> doubleArrayOf(54_050.0, 54_100.0, 54_040.0, 54_060.0)
        26 -> doubleArrayOf(54_040.0, 54_070.0, 54_020.0, 54_030.0)
        45 -> doubleArrayOf(54_020.0, 54_150.0, 54_015.0, 54_140.0)
        else -> if (k > 45) doubleArrayOf(54_150.0, 54_150.0, 54_145.0, 54_150.0) else doubleArrayOf(54_000.0, 54_010.0, 53_990.0, 54_000.0)
    }

    private fun feed(t: LocalDateTime): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }
        .filter { !it.plusMinutes(1).isAfter(t) }
        .map { start ->
            val b = bar((start.hour * 60 + start.minute - (9 * 60 + 15)) / 5)
            Upstox.Bar(start.atZone(IST).toEpochSecond(), b[0], b[1], b[2], b[3], 1000, 0)
        }

    /**
     * The contract list: the call bought (next week's) and, with [expiryToday], an option expiring today too - today is then
     * BANKNIFTY's own expiry day (Liquidity buys the expiry after the day, as before).
     */
    private fun contracts(expiryToday: Boolean = false) {
        AutomationSupport.contracts(context, listOfNotNull(Upstox.Contract("BANKNIFTY", day.plusDays(7), 54_000.0, Right.CE, 30, ceKey, "BANKNIFTY-LIQ-54000CE"),
            if (expiryToday) Upstox.Contract("BANKNIFTY", day, 54_000.0, Right.CE, 30, "NSE_FO|LIQTODAY", "BANKNIFTY-LIQ-TODAY") else null))
    }

    @Before fun up() {
        upstox = FakeUpstox()
        TradeFixtures.paperSettings()
        day = AutomationSupport.tradingDayAt(12, 50).toLocalDate()
        at(LocalTime.of(12, 50))
        contracts()
        upstox.price(ceKey, 300.0)
        OrbArms.testIndexBars = { t -> feed(t) }
        OrbArms.testHistoryBars = { emptyList() }
        runBlocking { OrbArms.setLiquidityLots(1, "test") }
        BossRiskHost.resetForTest(paperOn = false)
    }

    @After fun down() {
        OrbArms.testCapRef = null
        OrbArms.forgetCapWaitsForTest()
        BossRiskHost.resetForTest(paperOn = false)
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
    private fun passes(from: LocalTime, until: LocalTime) { var t = from; while (!t.isAfter(until)) { tick(t); t = t.plusMinutes(1) } }
    private fun row() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }
    private fun arm() { assertTrue(runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }.startsWith("Liquidity 15+5 armed on paper")) }

    // ---- the expiry-day skip -----------------------------------------------------------------------------------------------

    @Test fun onItsIndexsOwnExpiryDayThePaperBookTakesNoEntryAndSaysWhy() {
        contracts(expiryToday = true)                        // today is BANKNIFTY's expiry
        BossRiskHost.setPaper(BossRiskHost.Rule.EXPIRY, true, log = false)
        arm()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 6))
        assertTrue("nothing bought on the expiry day", row().today.isEmpty())
        assertTrue(Paper.state.orders.none { it.action == "BUY" })
        val rec = BossRiskHost.recordsToday(BossRiskHost.Rule.EXPIRY).single()
        assertTrue(rec.applied); assertFalse(rec.live)
        assertEquals(com.optionslab.engine.orb.LiquidityRules.EXPIRY_DAY_WORDS, rec.why)
        assertTrue(OrbArms.liquidityDecisions(day).any { it.verdict == "refused: ${com.optionslab.engine.orb.LiquidityRules.EXPIRY_DAY_WORDS}" })
    }

    @Test fun withTheSkipOffOrOnAnotherDayItBuysAsBefore() {
        contracts(expiryToday = true)
        arm()                                                // the skip off (its Undo): the expiry day is traded as before
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertEquals(1, row().today.size)
        assertTrue(BossRiskHost.recordsToday(BossRiskHost.Rule.EXPIRY).isEmpty())
    }

    @Test fun onAnOrdinaryDayTheSkipChangesNothing() {
        BossRiskHost.setPaper(BossRiskHost.Rule.EXPIRY, true, log = false)
        arm()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertEquals(1, row().today.size)
    }

    // ---- h14's capped limit ------------------------------------------------------------------------------------------------

    @Test fun underTheCapItBuysWithALimitAndTheTradeIsLabelledApart() {
        BossRiskHost.setPaper(BossRiskHost.Rule.CAPPED, true, log = false)
        arm()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        val p = row().today.single()
        assertEquals("the cap: the signal's price (300) + 0.5%", 301.5, p.capped!!, 1e-9)
        assertTrue("never above the cap: ${p.entry}", p.entry <= 301.5 + 1e-9)
        assertFalse("the rules are the plain ones (only the entry's order differs): it stays in the record, labelled", p.ownRules)
        val buy = Paper.state.orders.single { it.action == "BUY" }
        assertEquals("LIMIT", buy.priceType)
        assertEquals(301.5, buy.price!!.toDouble(), 1e-9)
        assertTrue(BossRiskHost.recordsToday(BossRiskHost.Rule.CAPPED).any { it.why.startsWith("capped limit (h14): bought at") })
    }

    @Test fun overTheCapItWaitsUpToThreeMinutesNeverChasingThenLetsItGo() {
        BossRiskHost.setPaper(BossRiskHost.Rule.CAPPED, true, log = false)
        OrbArms.testCapRef = 250.0                           // the signal priced 250: cap 251.25, the option trades at 300
        arm()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertTrue("over the cap: nothing bought, waiting", row().today.isEmpty())
        assertTrue(row().status, row().status.contains("waiting up to 3 minutes for its option to come back under the capped limit"))
        passes(LocalTime.of(13, 6), LocalTime.of(13, 8))
        assertTrue("still over the cap: never chased", Paper.state.orders.none { it.action == "BUY" })
        tick(LocalTime.of(13, 9))                            // 3 minutes past 13:05:00: let go
        assertTrue(row().today.isEmpty())
        val let = BossRiskHost.recordsToday(BossRiskHost.Rule.CAPPED).last()
        assertTrue(let.why, let.why.contains("did not come back to 251.25 or less within 3 minutes; let go (never chased)"))
        // No re-entry on that bar.
        passes(LocalTime.of(13, 10), LocalTime.of(13, 12))
        assertTrue(Paper.state.orders.none { it.action == "BUY" })
    }

    @Test fun aPriceBackUnderTheCapWithinThreeMinutesIsBought() {
        BossRiskHost.setPaper(BossRiskHost.Rule.CAPPED, true, log = false)
        OrbArms.testCapRef = 295.0                           // cap 296.45; the option at 300 at 13:05
        arm()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertTrue(row().today.isEmpty())
        upstox.price(ceKey, 296.0)                           // back under the cap at 13:07
        passes(LocalTime.of(13, 6), LocalTime.of(13, 7))
        val p = row().today.single()
        assertEquals(296.45, p.capped!!, 1e-9)
        assertTrue("${p.entry}", p.entry <= 296.45 + 1e-9)
        assertTrue("the stop rests as always", p.stopTrigger != null)
    }
}
