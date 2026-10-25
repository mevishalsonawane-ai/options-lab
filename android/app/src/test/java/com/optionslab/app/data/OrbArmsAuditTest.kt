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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 22 Oct (arms audit): the ORB family's exits and gates that the audit found wrong. [OrbArmsDayTest]'s day: the opening range
 * 53,800-54,000, the index at 54,150 from 10:30, so the ORB buys the day's call on the 10:35 pass. Paper only.
 *  - Boss's Stop for today and the 15:10 square-off sell even when no price can be read (before: held, no price, no exit).
 *  - A position the app's expiry square-off closed is booked "expiry_square_off", never "closed by you".
 *  - A hidden arm waiting for its exit posts no "approve BUY" and keeps no approval.
 *  - An entry order carries its arm's name at once, also when it is cancelled unfilled.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrbArmsAuditTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private var strike = 0
    private val ceKey = "NSE_FO|ORBAUCE"
    private val peKey = "NSE_FO|ORBAUPE"
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
            Upstox.Contract("BANKNIFTY", expiry, strike.toDouble(), Right.CE, 30, ceKey, "BANKNIFTY-ORBAU-${strike}CE"),
            Upstox.Contract("BANKNIFTY", expiry, strike.toDouble(), Right.PE, 30, peKey, "BANKNIFTY-ORBAU-${strike}PE")))
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
    private fun bought(): OrbArms.Position {
        tick(LocalTime.of(10, 35))
        val p = arm().today.single()
        assertTrue(p.open)
        return p
    }

    // ---- the pure rules ---------------------------------------------------------------------------------

    @Test fun theExitsThatNeedNoPrice() {
        assertEquals("operator_stop", OrbArms.noPriceExit(false, true, LocalTime.of(11, 0), false))
        assertEquals("session_end", OrbArms.noPriceExit(false, false, LocalTime.of(15, 10), false))
        assertEquals("session_end", OrbArms.noPriceExit(false, false, LocalTime.of(15, 29), false))
        assertNull("a price exit waits for a price", OrbArms.noPriceExit(false, false, LocalTime.of(15, 9), false))
        assertEquals("hero_exit", OrbArms.noPriceExit(true, false, LocalTime.of(15, 5), false))
        assertNull(OrbArms.noPriceExit(true, false, LocalTime.of(15, 4), false))
        assertEquals("a Hero position from an earlier day", "hero_exit", OrbArms.noPriceExit(true, false, LocalTime.of(9, 20), true))
        assertEquals("the stop first", "operator_stop", OrbArms.noPriceExit(true, true, LocalTime.of(15, 12), true))
    }

    @Test fun whyAPositionGoneFromItsBookClosed() {
        val d = LocalDate.of(2026, 10, 27)
        assertEquals("expiry_square_off", OrbArms.goneWhy(d, d.atTime(15, 6), expiryClose = true))
        assertEquals("expiry_square_off", OrbArms.goneWhy(d, d.atTime(15, 20), expiryClose = true))
        assertEquals("backstop_square_off", OrbArms.goneWhy(d, d.atTime(15, 15), expiryClose = false))
        assertEquals("backstop_square_off", OrbArms.goneWhy(d.minusDays(1), d.atTime(9, 20), expiryClose = false))
        assertEquals("closed_by_you", OrbArms.goneWhy(d, d.atTime(11, 0), expiryClose = false))
    }

    @Test fun aLiquidityApprovalLapsesAtTheNextBarOrTenMinutesAfterTheClose() {
        val bar = LocalDate.of(2026, 10, 22).atTime(13, 30)
        assertEquals(bar.plusMinutes(10), OrbArms.approvalExpiry(bar, 5))           // 5-minute: the next bar's close
        assertEquals(bar.plusMinutes(25), OrbArms.approvalExpiry(bar, 15))          // 15-minute: 10 minutes after its close
        assertEquals(bar.plusMinutes(40), OrbArms.approvalExpiry(bar, 30))          // 30-minute: never an hour later
    }

    @Test fun aLaggingIndexReadIsKeptAFewSecondsWithinItsMinute() {
        val m = LocalDate.of(2026, 10, 22).atTime(10, 31)
        assertTrue(OrbArms.lagFresh(1_000, m, 1_000, m))
        assertTrue(OrbArms.lagFresh(1_000, m, 1_000 + OrbArms.LAG_KEEP_MS - 1, m))
        assertFalse("too old", OrbArms.lagFresh(1_000, m, 1_000 + OrbArms.LAG_KEEP_MS, m))
        assertFalse("another minute", OrbArms.lagFresh(1_000, m, 1_500, m.plusMinutes(1)))
        assertFalse("a clock stepped back", OrbArms.lagFresh(5_000, m, 1_000, m))
    }

    // ---- the day ------------------------------------------------------------------------------------------

    /** Boss's Stop for today with no price anywhere: the sale is sent (the stop comes out for it), never held silently. */
    @Test fun stopForTodayWithNoPriceStillSendsTheExit() {
        upToTheBreak()
        val p = bought()
        val stop = p.stopOrderId!!
        upstox.down = true                                          // no price at all from here
        at(LocalTime.of(11, 0))
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        tick(LocalTime.of(11, 0))
        assertEquals("the resting stop came out for the exit", "exit:operator_stop", Paper.cancelReason(stop))
        awaitValue("the exit's diagnostics line") { Diag.lines().firstOrNull { it.contains("${p.symbol} has no price now; its operator_stop exit is sent anyway") } }
        // Not sold yet (no price to fill at): the position is never left without a resting stop meanwhile.
        val open = arm().open
        assertNotNull("still held: no price to sell at", open)
        assertTrue("a stop rests again", Paper.state.orders.any { it.orderId == open!!.stopOrderId && it.status == "trigger pending" })
        assertTrue("never short", Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } >= 0)
    }

    /** The 15:10 square-off with no price: sent too. */
    @Test fun theSessionEndWithNoPriceStillSendsTheExit() {
        upToTheBreak()
        val p = bought()
        upstox.down = true
        tick(LocalTime.of(15, 10))
        assertEquals("exit:session_end", Paper.cancelReason(p.stopOrderId!!))
    }

    /** The app's 15:05 expiry square-off closed the position: booked as that, its stop out, nothing short. */
    @Test fun theExpirySquareOffIsBookedAsSuchNeverAsClosedByYou() {
        upToTheBreak()
        val p = bought()
        at(LocalTime.of(15, 6))
        val r = runBlocking { Paper.close(p.symbol, "MIS") }
        assertTrue(r.message, r.ok)
        runBlocking { Strategies.tagOwner("paper:${r.orderId}", Origins.EXPIRY) }
        assertEquals("the close took the arm's resting stop out first", "cancelled", Paper.state.orders.single { it.orderId == p.stopOrderId }.status)
        tick(LocalTime.of(15, 6))
        val closed = arm().today.single()
        assertFalse(closed.open)
        assertEquals("expiry_square_off", closed.why)
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
    }

    /** Boss's own close before 15:15 is still his. */
    @Test fun bossesOwnCloseIsStillClosedByYou() {
        upToTheBreak()
        val p = bought()
        at(LocalTime.of(11, 0))
        assertTrue(runBlocking { Paper.close(p.symbol, "MIS") }.ok)
        tick(LocalTime.of(11, 0))
        assertEquals("closed_by_you", arm().today.single().why)
    }

    /**
     * Closed by a square-off before the arm's own 15:10 sale: the arm books it from the book's close and sells nothing more, so
     * nothing goes short. (The sale's refusal itself, for a close landing between the held read and the sale, is
     * [PaperExitReduceTest.anExitOfAFlatPositionIsRefusedNeverAShort].)
     */
    @Test fun anExitAfterTheBookWasClosedNeverOpensAShort() {
        upToTheBreak()
        val p = bought()
        at(LocalTime.of(15, 6))
        assertTrue(runBlocking { Paper.close(p.symbol, "MIS") }.ok)
        // The arm's own 15:10 sale arrives after it: refused, and the next look books it from the book's close.
        tick(LocalTime.of(15, 10))
        tick(LocalTime.of(15, 11))
        assertFalse(arm().today.single().open)
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        assertEquals("one sale only: the close", 1, Paper.state.trades.count { it.symbol == p.symbol && it.action == "SELL" })
    }

    /** A hidden arm (still holding, so the cleanup waits) in approval mode: a break after its exit posts no approval. */
    @Test fun aHiddenArmPostsNoApproval() {
        HiddenArms.resetForTest(context)
        try {
            at(LocalTime.of(9, 50))
            runBlocking { OrbArms.setArmed("orb", true, automatic = false) }
            runBlocking { HiddenArms.hideAgain(com.optionslab.engine.orb.ArmsCleanup.ORB) }
            assertTrue(HiddenArms.blocks(com.optionslab.engine.orb.ArmsCleanup.ORB))
            var t = LocalTime.of(9, 50)
            while (!t.isAfter(LocalTime.of(10, 40))) { tick(t); t = t.plusMinutes(1) }
            assertEquals(OrbArms.HIDDEN_NO_ENTRY, arm().status)
            // No approval kept means no "approve BUY" notice either: it is posted only beside a kept approval.
            assertNull("no approval kept", arm().pending)
            assertTrue("nothing bought", Paper.state.orders.isEmpty())
        } finally { HiddenArms.resetForTest(context) }
    }

    /** An entry order cancelled unfilled (no usable price) carries the arm's name at once, as the one that fills. */
    @Test fun aCancelledEntryOrderCarriesItsArmsName() {
        upToTheBreak()
        upstox.scriptNext(ceKey, FakeUpstox.Scripted(listOf(FakeUpstox.Candle(LocalTime.of(10, 34), 300.0, 301.0, 299.0, 320.0))))
        tick(LocalTime.of(10, 35))
        val buys = Paper.state.orders.filter { it.action == "BUY" }
        assertEquals(listOf("cancelled", "complete"), buys.map { it.status })
        val owners = runBlocking { Strategies.owners() }
        for (o in buys) assertEquals(o.status, "ORB · entry", owners["paper:${o.orderId}"])
    }
}
