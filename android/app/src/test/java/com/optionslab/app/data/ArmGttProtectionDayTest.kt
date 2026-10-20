package com.optionslab.app.data

import com.optionslab.app.ira.IraNewsTrades
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.orb.LiveMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate

/**
 * 20 Oct, enhancement round 2 (idea 12): one whole trading day on ONE contract with an arm's live trade resting its stop as a GTT
 * ([ArmLive], Night: carried, NRML) and Boss's own stop and target on the same contract ([Protections]): fills, a part fill, the
 * arm's stop reached beside Boss's, and Boss's own exit at the same moment as the protection's look. After every look, the exits
 * that could fire together (every active GTT, the larger of the protection's stop and target, which cancel each other, and any
 * other working sell) never add up to more than is held, and the account is never short. The arm yields to Boss's protection:
 * it takes its GTT out and sells nothing beside Boss's exits. Run with the exit coordinator on and in shadow.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ArmGttProtectionDayTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private val contract by lazy { Paper.Contract("BANKNIFTY-TEST-52000CE", "BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|TESTCE") }
    private val legs = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()
    private lateinit var day: LocalDate

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        IraNewsTrades.paperFirst = false
        ArmLive.testSync = true
        ArmLive.testDefaultOff = false
        FillSlippage.resetForTest()
        for (a in LiveMirror.ARMS) if (a.key != LiveMirror.SOLO_MIDDAY) runBlocking { ArmLive.setShadow(a.key, false, pinConfirmed = true, log = false) }
        for (f in LiveMirror.ARMS.map { it.family }.distinct())
            ArmLive.register(f) { legs.entries.filter { e -> LiveMirror.spec(e.value.first)?.family == f }.map { ArmLive.PaperLeg(it.key, it.value.first, it.value.second) } }
        ExitCoordinator.resetForTest(on = true)
        var d = LocalDate.now(com.optionslab.engine.IST).plusDays(1)
        while (!Market.isTradingDay(d)) d = d.plusDays(1)
        day = d
        at(9, 20)
        kite.requests.clear()
    }

    @After fun down() {
        ArmLive.testSync = false
        ArmLive.testNow = null
        Market.testClock = null
        ExitCoordinator.resetForTest()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** The clock (still ticking) at [h]:[m] IST on the test's trading day, for the market, the protections and the arms. */
    private fun at(h: Int, m: Int) {
        val when0 = day.atTime(h, m).atZone(com.optionslab.engine.IST)
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), when0.toInstant()))
        ArmLive.testNow = when0
    }

    private fun held() = kite.positions["NFO:$sym:NRML"]?.first ?: 0
    private fun look() = runBlocking { Protections.tick(); ArmLive.watch() }

    /** The most that the exits resting now could sell together. */
    private fun worst(): Int {
        val gtt = kite.gtts.values.filter { it.optString("status", "active") == "active" }
            .sumOf { it.getJSONArray("orders").getJSONObject(0).getInt("quantity") }
        val w = kite.orders.values.filter { it.working && it.side == "SELL" && it.symbol == sym }
        val pair = w.filter { it.tag == "iraprotect" }.maxOfOrNull { it.qty - it.filled } ?: 0
        return gtt + pair + w.filter { it.tag != "iraprotect" }.sumOf { it.qty - it.filled }
    }

    private fun safe(step: String) {
        assertTrue("$step: never short (held ${held()})", held() >= 0)
        assertTrue("$step: the exits that could fire together (${worst()}) never exceed what is held (${held()})", worst() <= held())
    }

    @Test fun anArmsGttAndAProtectionOnOneContractNeverSellMoreThanIsHeldAllDay() = day()

    @Test fun inShadowTheSameDayIsJustAsSafe() { ExitCoordinator.resetForTest(on = false); day() }

    private fun armSells() = kite.placed.count { it.form["tag"] == "iraarm" && it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }

    private fun day() {
        // 09:20 the Night arm's live trade: 30 bought, its stop rests as a GTT for its 30 at 170.
        legs["night"] = com.optionslab.engine.orb.NightRules.SOURCE to 30
        ArmLive.entered(ArmLive.Ask(com.optionslab.engine.orb.NightRules.SOURCE, "night", contract, 1, 30, 200.0, stop = 170.0))
        assertEquals(30, held())
        assertEquals(1, kite.gtts.size)
        safe("arm entered")
        // 09:25 Boss buys 3 lots more by hand (120 held) and protects all 120 with a stop at 150 and a target at 260.
        at(9, 25)
        kite.positions["NFO:$sym:NRML"] = 120 to 200.0
        val msg = runBlocking { Protections.protectLive(sym, "NFO", "NRML", 120, 200.0, 150.0, null, 260.0) }
        assertTrue(msg, msg.startsWith("Protected $sym"))
        val target = kite.orders.values.single { it.tag == "iraprotect" && it.type == "LIMIT" }
        val stop = kite.orders.values.single { it.tag == "iraprotect" && it.type == "SL" }
        // The arm's look: Boss's protection covers all that is held, so the arm takes its GTT out (it watches its own stop).
        at(9, 27)
        look()
        assertTrue("the arm yields to Boss's protection: no GTT beside it", kite.gtts.values.none { it.optString("status", "active") == "active" })
        safe("protected")
        // 10:00 the target part-fills one lot (held 90): the protection's look brings the stop down to what is held.
        at(10, 0)
        kite.fillPart(target.id, 30, 260.0)
        assertEquals(90, held())
        look()
        assertEquals(90, kite.order(stop.id).qty)
        safe("target part-filled")
        // 11:00 a fall to 165, under the arm's own stop (170): Boss's resting exits already cover all that is held, so the arm
        // sells nothing beside them (one stop at 150 protects all of it).
        at(11, 0)
        kite.quote("NFO:$sym", 165.0, 164.95, 165.05)
        look(); look()
        assertEquals("never a second sell beside Boss's exits", 0, armSells())
        assertEquals(90, held())
        safe("the arm's stop reached")
        // 11:30 Boss sells a lot by hand at the very moment of the protection's look.
        at(11, 30)
        runBlocking {
            val boss = async(Dispatchers.IO) { Broker.placeOrder(Kite.Order(sym, Kite.Side.SELL, 30, 30, "NRML", "MARKET", null, 0.05, "NFO", ""), exit = true) }
            val tick = async(Dispatchers.IO) { Protections.tick() }
            boss.await(); tick.await()
        }
        look()
        assertEquals(60, held())
        assertEquals("the stop follows what is held", 60, kite.order(stop.id).qty)
        safe("Boss's own exit")
        // 14:00 a fall through 150: the stop fills what is left; the target comes out (one cancels the other).
        at(14, 0)
        kite.quote("NFO:$sym", 140.0, 139.95, 140.05)
        kite.trigger(stop.id, 140.0)
        look(); look()
        assertEquals("flat at the end of the day, never short", 0, held())
        assertTrue("nothing left resting", kite.orders.values.none { it.working && it.symbol == sym })
        assertEquals(0, armSells())
        safe("stopped out")
    }

    /** The arm keeps its GTT beside Boss's stop on his own lots; the GTT fires and Boss's stop part-fills: never short. */
    @Test fun aGttFiringBesideBosssStopAndAPartFillNeverSellMoreThanIsHeld() {
        legs["night"] = com.optionslab.engine.orb.NightRules.SOURCE to 30
        ArmLive.entered(ArmLive.Ask(com.optionslab.engine.orb.NightRules.SOURCE, "night", contract, 1, 30, 200.0, stop = 170.0))
        at(9, 25)
        kite.positions["NFO:$sym:NRML"] = 120 to 200.0
        // Boss protects only his own 90 (a stop, no target): with the arm's GTT for its 30, exactly what is held.
        assertTrue(runBlocking { Protections.protectLive(sym, "NFO", "NRML", 90, 200.0, 150.0, null, null) }.startsWith("Protected"))
        val stop = kite.orders.values.single { it.tag == "iraprotect" }
        at(9, 27); look()
        assertEquals("the arm's GTT stays", 1, kite.gtts.values.count { it.optString("status", "active") == "active" })
        safe("both resting")
        // 11:00 a fall to 165: the GTT fires and fills the arm's 30; the arm books its stop, nothing else sells.
        at(11, 0)
        kite.quote("NFO:$sym", 165.0, 164.95, 165.05)
        kite.triggerGtt(kite.gtts.keys.single())
        look(); look()
        assertEquals(90, held())
        assertEquals("stop", ArmLive.view.value.closed.single().why)
        assertEquals(0, armSells())
        safe("GTT fired")
        // 14:00 Boss's stop triggers and only part fills (30 of 90): the rest still works for exactly what is held.
        at(14, 0)
        kite.quote("NFO:$sym", 140.0, 139.95, 140.05)
        kite.order(stop.id).status = "OPEN"
        kite.fillPart(stop.id, 30, 145.0)
        look()
        assertEquals(60, held())
        safe("stop part-filled")
        kite.fill(stop.id, 140.0)
        look()
        assertEquals("flat, never short", 0, held())
        assertTrue(kite.orders.values.none { it.working && it.symbol == sym })
    }
}
