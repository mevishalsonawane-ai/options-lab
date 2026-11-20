package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime

/**
 * Liquidity 15+5 in Live against the fake Kite, with the ORB's rules: arming in Live takes the PIN, an approval in
 * Live takes the PIN, an approved entry buys one lot at Zerodha and rests its stop 15% below the fill (an SL order,
 * as the ORB's), the kill switch refuses it, and a paper arm finding the app in Live asks instead of buying.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiquidityArmLiveTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private lateinit var now: LocalDateTime

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_002, "BANKNIFTY26OCT52000PE", "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        // The app's own contract list (the paper account's), which names the strike the arm buys.
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|LIQLIVECE", "BANKNIFTY-LIQ-52000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.PE, 30, "NSE_FO|LIQLIVEPE", "BANKNIFTY-LIQ-52000PE")))
        kite.requests.clear()
        val t = AutomationSupport.earlierToday()
        OrbArms.testNow = t
        now = t.toLocalDateTime().withNano(0)
    }

    @After fun down() {
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /**
     * The arms' saved state: both liquidity books armed in Live, the 5-minute book with a signal waiting for approval, at
     * [lots] a trade (null: no size saved, a book from before the setting).
     */
    private fun state(expires: LocalDateTime = now.plusMinutes(9), lots: Int? = 1, held: JSONArray = JSONArray(), noticed: Boolean? = null,
                      cleared: Boolean = true, updated: Boolean = false) {
        val books = listOf("liquidity15", "liquidity5")
        fun flags(v: Boolean) = JSONObject().apply { books.forEach { put(it, v) } }
        val bar = maxOf(now.minusMinutes(1), now.toLocalDate().atStartOfDay())
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", flags(true)).put("auto", flags(false)).put("liveOk", flags(cleared))
            // Saved after the 06 Oct update (its one-time switch-off already done): armed again by Boss.
            .put("migrated", JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION).put(com.optionslab.engine.orb.RetiredArms.UNRETIRE)
                // [updated]: saved after the 15 Nov update (its one-time clearance already done).
                .apply { if (updated) put(OrbArms.LIQ_SWITCH_CLEAR) })
            .put("positions", held)
            .put("pending", JSONObject().put("liquidity5", JSONObject().put("right", "CE").put("bar", bar.toString())
                .put("expires", expires.toString()).put("strike", 52_000).put("level", 52_050.0)
                // The account the approval notice named (review G2-1); absent: a signal saved before it was kept.
                .apply { noticed?.let { put("live", it) } }))
            // A size saved by Boss's own choice (kept by the 08 Oct one-lot change).
            .apply { lots?.let { put("liqLots", it); put("liqLotsChosen", true) } })
    }

    private fun row() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }

    /**
     * 15 Nov (Boss: "Liquidity should be always working if i have swtich on it on dashboard, no extra pin based approval needed"):
     * in Live, Boss's own switch on Home switches it on with no PIN of its own and clears every book for Zerodha; anything else
     * (Jarvis by voice, the day plan) still cannot switch it on in Live. Off stays one tap and clears the books.
     */
    @Test fun inLiveItsOwnSwitchArmsItWithNoPinAndNothingElseCan() {
        val refused = runBlocking { OrbArms.setArmed("liquidity", true, automatic = true, pinConfirmed = false) }
        assertEquals(OrbArms.LIQUIDITY_SWITCH_ONLY, refused)
        assertFalse(row().armed)
        assertTrue("nothing cleared", runBlocking { OrbArms.liquidityLiveCleared() }.values.none { it })
        val armed = runBlocking { OrbArms.setArmed("liquidity", true, automatic = true, bySwitch = true) }
        assertTrue(armed, armed.startsWith("Liquidity 15+5 armed on ZERODHA (live), fully automatic"))
        assertTrue(row().armed); assertTrue("would trade live now", row().liveOk)
        assertTrue(row().paperUntilSwitch.isEmpty())
        assertTrue(runBlocking { OrbArms.liquidityLiveCleared() }.values.all { it })
        assertTrue("arming sends nothing", kite.placed.isEmpty())
        // The flip is in the diary, saying it was the switch (no PIN).
        val end = System.currentTimeMillis() + 10_000
        fun has(t: String) = Diag.lines().any { it.contains("[switch] $t") }
        while (!has("Liquidity 15+5 switched on (live, by its switch; fully automatic)") && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(Diag.lines().takeLast(20).toString(), has("Liquidity 15+5 switched on (live, by its switch; fully automatic)"))
        // Off: one tap, no PIN; every book's clearance goes with it.
        assertEquals("Liquidity 15+5 disarmed.", runBlocking { OrbArms.setArmed("liquidity", false, automatic = true, bySwitch = true) })
        assertFalse(row().armed); assertFalse(row().liveOk)
        assertTrue(runBlocking { OrbArms.liquidityLiveCleared() }.values.none { it })
        // With the PIN (an older screen) it still arms and clears, as before.
        assertTrue(runBlocking { OrbArms.setArmed("liquidity", true, automatic = false, pinConfirmed = true) }.startsWith("Liquidity 15+5 armed on ZERODHA (live), you approve each entry"))
        assertTrue(row().liveOk)
    }

    /**
     * 15 Nov (review): in PAPER, Liquidity switched on any way but Boss's own switch on Home (Jarvis by voice, the day plan - both call
     * without it) is NOT cleared: when the app goes Live its books stay on paper and the row says so; Boss's switch on Home (in Paper
     * too) clears them; a switch-off clears them again.
     */
    @Test fun inPaperOnlyBosssOwnSwitchClearsItForLive() {
        AutomationSupport.liveSettings(live = false)
        // Jarvis by voice and the day plan call exactly this (no switch, no PIN).
        assertTrue(runBlocking { OrbArms.setArmed("liquidity", true, automatic = true, pinConfirmed = false) }.startsWith("Liquidity 15+5 armed on paper"))
        assertTrue(row().armed)
        assertTrue("not cleared for Zerodha", runBlocking { OrbArms.liquidityLiveCleared() }.values.none { it })
        assertFalse("in Paper: never live now", row().liveOk)
        AutomationSupport.liveSettings()
        assertFalse("in Live too: on paper until Boss switches it on", row().liveOk)
        assertEquals(6, row().paperUntilSwitch.size)
        // Boss's own switch on Home, in Paper: cleared.
        AutomationSupport.liveSettings(live = false)
        runBlocking { OrbArms.setArmed("liquidity", false, automatic = true, bySwitch = true) }
        assertTrue(runBlocking { OrbArms.setArmed("liquidity", true, automatic = true, bySwitch = true) }.startsWith("Liquidity 15+5 armed on paper"))
        assertTrue(runBlocking { OrbArms.liquidityLiveCleared() }.values.all { it })
        assertFalse("still Paper: not live now", row().liveOk)
        AutomationSupport.liveSettings()
        assertTrue("the app in Live: live by Boss's switch", row().liveOk)
        assertTrue(row().paperUntilSwitch.isEmpty())
        // Jarvis switching it on again (in Paper) takes the clearance away, never adds it.
        AutomationSupport.liveSettings(live = false)
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        assertTrue(runBlocking { OrbArms.liquidityLiveCleared() }.values.none { it })
        assertTrue("nothing sent", kite.placed.isEmpty())
    }

    /** 15 Nov (review): an index switched back on by an unpark (the row's tap or the timeline's redo) stays on paper until Boss's switch. */
    @Test fun anUnparkedIndexStaysOnPaperUntilBosssSwitch() {
        AutomationSupport.liveSettings(live = false)
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true, bySwitch = true) }
        assertEquals("Liquidity 15+5 FINNIFTY is parked again.", runBlocking { OrbArms.parkLiquidity("FINNIFTY") })
        assertEquals("Liquidity 15+5 FINNIFTY is back on, on paper.", runBlocking { OrbArms.unparkLiquidity("FINNIFTY") })
        val c = runBlocking { OrbArms.liquidityLiveCleared() }
        assertFalse(c.getValue("liquidity30_fin")); assertFalse(c.getValue("liquidity5_fin"))
        assertTrue("the other books keep Boss's clearance", c.getValue("liquidity15") && c.getValue("liquidity5_mid"))
        assertEquals(listOf("FINNIFTY 30-min", "FINNIFTY 5-min"), row().paperUntilSwitch)
        // The timeline's redo of an index switched on, in Live and with no PIN (the timeline never asks one for it): the index comes
        // back on, on paper only - never cleared for Zerodha.
        AutomationSupport.liveSettings()
        runBlocking { OrbArms.parkLiquidity("FINNIFTY") }
        val redo = com.optionslab.ira.AutoChange.liqIndexOn("FINNIFTY", parked = true)
        val said = com.optionslab.app.ira.Autopilot.apply(redo, false, byBoss = true)
        assertTrue(said.toString(), said?.startsWith("Liquidity 15+5 FINNIFTY is back on, on paper until you switch Liquidity on yourself on Home") == true)
        assertFalse(row().parked.contains("FINNIFTY"))
        val c2 = runBlocking { OrbArms.liquidityLiveCleared() }
        assertFalse(c2.getValue("liquidity30_fin")); assertFalse(c2.getValue("liquidity5_fin"))
        assertEquals(listOf("FINNIFTY 30-min", "FINNIFTY 5-min"), row().paperUntilSwitch)
        assertTrue("nothing sent", kite.placed.isEmpty())
    }

    /**
     * 15 Nov: once, on this update, every Liquidity book that is on (what Boss's switch shows) is cleared for Zerodha, whoever switched
     * it on - the FINNIFTY and MIDCPNIFTY books a one-time change put on paper too - said once (notice, diary, change timeline),
     * naming the books; their "paper only" status says so. A restart never does it again; loading sends nothing.
     */
    @Test fun theUpdateClearsTheBooksOnNowOnceAndSaysSo() {
        val four = listOf("liquidity15", "liquidity5", "liquidity30_fin", "liquidity5_fin")
        fun flags(v: Boolean) = JSONObject().apply { four.forEach { put(it, v) } }
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", flags(true)).put("auto", flags(true)).put("liveOk", flags(false)).put("liqLots", 1)
            // (The 9 Oct and 13 Oct parks marked done: FINNIFTY's books stay on, as after Boss's 15 Oct cleanup; not this update's.)
            .put("migrated", JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION).put(com.optionslab.engine.orb.RetiredArms.UNRETIRE)
                .put(com.optionslab.engine.orb.ParkedArms.MIGRATION).put(com.optionslab.engine.orb.FamilyPark.MIGRATION))
            .put("positions", JSONArray()))
        val cleared = runBlocking { OrbArms.liquidityLiveCleared() }
        assertTrue(cleared.toString(), cleared.values.all { it })
        assertTrue("would trade live now", row().liveOk)
        assertTrue(row().status, row().status.contains(OrbArms.LIQ_SWITCH_CLEARED))
        assertTrue("the load sends nothing", kite.placed.isEmpty())
        assertTrue(OrbArms.liquidityBnLiveHint)
        val end = System.currentTimeMillis() + 10_000
        // The arms' own diary line ("[orb]"). The notice's banner is kept in the diary too, as every banner is (Alerts.post, area
        // "info", "Liquidity: live by your switch: ..."): one event, two lines of different kinds - each counted once on its own.
        fun said() = Diag.lines().count { it.contains("[orb] ") && it.contains("now cleared for live by it") }
        fun noticed() = Diag.lines().count { it.contains("Liquidity: live by your switch: ") }
        while (said() < 1 && System.currentTimeMillis() < end) Thread.sleep(20)
        Thread.sleep(200)
        assertEquals(1, said())
        // (At most one: the banner shows the same words once in 20 s, so it may be held back if another test just posted them.)
        assertTrue("one notice at most: ${noticed()}", noticed() <= 1)
        assertTrue(Diag.lines().first { it.contains("[orb] ") && it.contains("now cleared for live by it") }.contains("MIDCPNIFTY 15-min"))
        // Once: a restart neither clears nor says it again.
        AutomationSupport.reloadFromDisk(OrbArms)
        runBlocking { OrbArms.view() }
        Thread.sleep(200)
        assertEquals(1, said()); assertTrue(noticed() <= 1)
        // Switched off: nothing is cleared, and the yield goes.
        runBlocking { OrbArms.setArmed("liquidity", false, automatic = true) }
        assertTrue(runBlocking { OrbArms.liquidityLiveCleared() }.values.none { it })
        assertFalse(OrbArms.liquidityBnLiveHint)
    }

    /** 15 Nov (review): the self-review's park to paper stays an automatic risk-lowering step - Boss's no-PIN tap never lifts it. */
    @Test fun aNoPinSwitchTapNeverLiftsTheReviewsParkThePinDoes() {
        assertNotNull(SmartWorkers.parkByAutopilot("liquidity"))
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true, bySwitch = true) }
        assertTrue("still parked to paper", SmartWorkers.reviewParked("liquidity"))
        assertTrue(row().reviewParked); assertFalse("parked: not live now", row().liveOk)
        assertEquals("Lifting Liquidity's park to paper or pause takes your PIN.", OrbArms.liftLiquidityPark(false))
        assertTrue(SmartWorkers.reviewParked("liquidity"))
        assertTrue(OrbArms.liftLiquidityPark(true).startsWith("Liquidity's park to paper and pause are lifted"))
        assertFalse(SmartWorkers.reviewParked("liquidity"))
        assertTrue(row().liveOk)
        assertEquals("Liquidity is not parked to paper or paused by its review.", OrbArms.liftLiquidityPark(true))
    }

    /**
     * 15 Nov (review): a book switched on after the update without Boss's own switch (here: no clearance stored, the update done) is on
     * paper in Live - its approval asks no PIN and sends nothing to Zerodha.
     */
    @Test fun aBookNotClearedByBosssSwitchIsApprovedOnPaperInLive() = withUpstox {
        state(cleared = false, updated = true)
        assertFalse(runBlocking { OrbArms.liquidityLiveCleared() }.getValue("liquidity5"))
        assertFalse(row().liveOk)
        val msg = runBlocking { OrbArms.approve("liquidity", pinConfirmed = false) }
        assertFalse("a paper entry takes no PIN", msg == "The app is in Live: approve with your PIN on Home → Strategies.")
        assertTrue("nothing sent to Zerodha", kite.placed.isEmpty())
        assertFalse("no live position", row().open?.live == true)
    }

    /**
     * 15 Nov (review): Reset paper keeps exactly its rule - an arm whose stored clearance is set stays on (Liquidity: switched on by
     * Boss's own switch); one that is not (switched on by Jarvis or the day plan) is switched off and loses any clearance.
     */
    @Test fun aResetKeepsOnlyTheBooksBossCleared() {
        AutomationSupport.liveSettings(live = false)
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }                  // Jarvis / the day plan: not cleared
        runBlocking { OrbArms.resetPaper() }
        assertFalse(row().armed)
        assertTrue(runBlocking { OrbArms.liquidityLiveCleared() }.values.none { it })
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true, bySwitch = true) }  // Boss's own switch
        runBlocking { OrbArms.resetPaper() }
        assertTrue("kept, as an arm cleared for Zerodha always was", row().armed)
        assertTrue(runBlocking { OrbArms.liquidityLiveCleared() }.values.all { it })
    }

    /**
     * 15 Nov (review): Boss's own switch lifts its self-review's PAUSE of paper entries ("switch it back on yourself"), but never its park
     * to paper (live entries to paper): that takes the PIN. Jarvis switching it on lifts neither.
     */
    @Test fun bosssSwitchLiftsThePaperPauseNotThePark() {
        AutomationSupport.liveSettings(live = false)
        assertNotNull(SmartWorkers.pausePaperByAutopilot("liquidity"))
        assertNotNull(SmartWorkers.parkByAutopilot("liquidity"))
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }                  // Jarvis / the day plan
        assertTrue("liquidity" in SmartWorkers.settings.value.paused)
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true, bySwitch = true) }  // Boss's own switch
        assertFalse("the paper pause is lifted", "liquidity" in SmartWorkers.settings.value.paused)
        assertTrue("the park to paper stays", SmartWorkers.reviewParked("liquidity"))
        assertTrue(OrbArms.liftLiquidityPark(true).startsWith("Liquidity's park to paper and pause are lifted"))
        assertFalse(SmartWorkers.reviewParked("liquidity"))
    }

    /**
     * 15 Nov (review): the self-review's pause blocks live entries too, so in LIVE Boss's no-PIN switch never lifts it; the row's Lift
     * button (the PIN) lifts the pause and the park together.
     */
    @Test fun inLiveTheSwitchNeverLiftsThePauseThePinDoes() {
        assertNotNull(SmartWorkers.pausePaperByAutopilot("liquidity"))
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true, bySwitch = true) }
        assertTrue("still paused", SmartWorkers.reviewPaused("liquidity"))
        assertTrue(row().reviewPaused)
        assertEquals("Lifting Liquidity's park to paper or pause takes your PIN.", OrbArms.liftLiquidityPark(false))
        assertTrue(SmartWorkers.reviewPaused("liquidity"))
        assertTrue(OrbArms.liftLiquidityPark(true).startsWith("Liquidity's park to paper and pause are lifted"))
        assertFalse(SmartWorkers.reviewPaused("liquidity"))
        assertFalse(row().reviewPaused)
    }

    /**
     * 15 Nov (review): in Live, "start all" says which Liquidity books stay on paper until Boss switches it on himself on Home; in Paper
     * (everything is on paper) it says nothing of it.
     */
    @Test fun startAllSaysWhichLiquidityBooksStayOnPaper() {
        fun said() = runBlocking { com.optionslab.app.ira.IraActions.prepare(com.optionslab.ira.Commands.parse("start all")!!) }.first
        val note = "stay on paper until you switch it on yourself on Home"
        AutomationSupport.liveSettings(live = false)
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }                  // Jarvis / the day plan: not cleared
        assertFalse("in Paper: nothing to say", said().contains(note))
        AutomationSupport.liveSettings()
        said().let { assertTrue(it, it.contains(note) && it.contains("BANKNIFTY 15-min")) }
        AutomationSupport.liveSettings(live = false)
        runBlocking { OrbArms.setArmed("liquidity", true, automatic = true, bySwitch = true) }  // Boss's own switch
        AutomationSupport.liveSettings()
        assertFalse(said().contains(note))
    }

    /**
     * 15 Nov (review): the update's one-time clearance during a restore not yet disarmed: the restored book's switches are off, the
     * change is marked done (saved) and clears nothing; books switched on after the restore (not by Boss's switch) are never cleared by
     * it later, and nothing is said.
     */
    @Test fun theUpdateDuringARestoreClearsNothing() {
        val books = listOf("liquidity15", "liquidity5")
        fun flags(v: Boolean) = JSONObject().apply { books.forEach { put(it, v) } }
        val before = JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION).put(com.optionslab.engine.orb.RetiredArms.UNRETIRE)
            .put(com.optionslab.engine.orb.ParkedArms.MIGRATION).put(com.optionslab.engine.orb.FamilyPark.MIGRATION)
        SecurePrefs.put(Backup.DISARM, true)
        try {
            AutomationSupport.orbState(context, JSONObject().put("armed", flags(true)).put("auto", flags(true)).put("liveOk", flags(false))
                .put("migrated", before).put("positions", JSONArray()))
            assertTrue(runBlocking { OrbArms.liquidityLiveCleared() }.values.none { it })
            val saved = JSONObject(String(com.optionslab.app.security.Vault.readFile(java.io.File(context.filesDir, "orb.vault"))!!, Charsets.UTF_8))
            val done = saved.getJSONArray("migrated").let { m -> (0 until m.length()).map { m.getString(it) } }
            assertTrue("marked done while restoring: $done", OrbArms.LIQ_SWITCH_CLEAR in done)
            assertTrue("no clearance stored", books.none { saved.optJSONObject("liveOk")?.optBoolean(it, false) == true })
        } finally { SecurePrefs.put(Backup.DISARM, false) }
        // After the restore, the books switched on again without Boss's switch (the book as saved, its change marked done): never cleared.
        AutomationSupport.orbState(context, JSONObject().put("armed", flags(true)).put("auto", flags(true)).put("liveOk", flags(false))
            .put("migrated", JSONArray(before.toString()).put(OrbArms.LIQ_SWITCH_CLEAR)).put("positions", JSONArray()))
        assertTrue(row().armed)
        assertTrue(runBlocking { OrbArms.liquidityLiveCleared() }.values.none { it })
        assertFalse("in Live, on paper until Boss's switch", row().liveOk)
        Thread.sleep(200)
        assertTrue(Diag.lines().none { it.contains("now cleared for live by it") })
    }

    /** 15 Nov: the switch never weakens another gate - the kill switch still refuses the live entry of a book cleared by its switch. */
    @Test fun aSwitchClearedBookStillMeetsTheKillSwitch() {
        // Saved with no clearance stored, cleared by the update's one-time change (it was on), its signal waiting for approval.
        state(cleared = false)
        assertTrue(runBlocking { OrbArms.liquidityLiveCleared() }.getValue("liquidity5"))
        SecurePrefs.put("g.kill", true)
        assertEquals("Refused: the kill switch is on", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertTrue(kite.placed.isEmpty())
    }

    /** 15 Nov: "AI trades go live" still guards the ORB family's live entries at the order door, the switch-cleared Liquidity books' too. */
    @Test fun aiTradesGoLiveOffStillRefusesAtTheDoor() {
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.BUY, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", "iraorb")
        PaperOnly.resetForTest(null)
        com.optionslab.app.ira.IraNewsTrades.paperFirst = true
        try {
            assertEquals("refused: \"AI trades go live\" is off", Broker.entryRecheck(o, "liquidity:BANKNIFTY"))
        } finally { com.optionslab.app.ira.IraNewsTrades.paperFirst = false }
    }

    @Test fun anApprovedLiveEntryBuysOneLotAndRestsA15PercentStop() {
        state()
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        val (buy, stop) = kite.placed
        val keys = setOf("tradingsymbol", "transaction_type", "order_type", "quantity", "product", "price", "trigger_price")
        // 19 Nov 2026 (Boss: "Go"): a marketable LIMIT at the offer plus 0.5%, up to the tick.
        assertEquals(mapOf("tradingsymbol" to sym, "transaction_type" to "BUY", "order_type" to "LIMIT", "quantity" to "30", "product" to "MIS",
            "price" to "201.10"), buy.form.filterKeys { it in keys })
        // 15% below the 200 fill: trigger 170, limit 5% under it (as the ORB's stop order).
        assertEquals(mapOf("tradingsymbol" to sym, "transaction_type" to "SELL", "order_type" to "SL", "quantity" to "30", "product" to "MIS",
            "price" to "161.50", "trigger_price" to "170.00"), stop.form.filterKeys { it in keys })
        val p = row().open!!
        assertTrue(p.live); assertEquals("liquidity5", p.arm)
        assertEquals(170.0, p.stopTrigger!!, 0.0); assertEquals(52_050.0, p.level!!, 0.0)
        assertNull("the approval is used up", row().pending)
    }

    /**
     * 20 Oct, enhancement round 1 (idea 3): the no-twin rule never changes a LIVE trade - the other book holding this option on
     * Zerodha is only recorded (shadow), and the entry goes on exactly as before.
     */
    @Test fun aLiveTwinIsOnlyLoggedAndTheEntryGoesOn() {
        state()
        val call = Paper.contractFor("BANKNIFTY", expiry, 52_000.0, Right.CE)!!.symbol
        OrbArms.testTwinHolders = { listOf(com.optionslab.ira.LiquidityTwin.Holding("liquidity15", call, live = true)) }
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertEquals("BUY", kite.placed.first().form["transaction_type"])
        val rec = LiquidityTest.twins().single()
        assertTrue(rec.live); assertFalse("shadow: nothing changed", rec.applied)
        assertTrue(LiquidityTest.twinLine(), LiquidityTest.twinLine().contains("1 only logged"))
    }

    /** 13 Oct review: Boss's Rs 500 trail is paper only - a LIVE entry with the switch on carries no trail, and is not labelled trailed. */
    @Test fun aLiveEntryWithBosssPaperTrailSwitchOnCarriesNoTrail() {
        val before = LiquidityTest.state.value
        LiquidityTest.setStateForTest(before.copy(trail = true))
        try {
            assertTrue(LiquidityTest.trailOn())
            state()
            assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
            val p = row().open!!
            assertTrue(p.live)
            assertNull("never on a live trade", p.trail)
            assertNull(p.trailFrom)
            // Its resting stop is the plain 15% one (170 under the 200 fill), not moved by any trail.
            assertEquals(170.0, p.stopTrigger!!, 0.0)
        } finally { LiquidityTest.setStateForTest(before) }
    }

    /** 22 Oct: Boss's breakeven at +10% is PAPER only - a live entry with the switch on (and the trail's too) carries neither. */
    @Test fun aLiveEntryWithBosssPaperBreakevenSwitchOnCarriesNoBreakeven() {
        val before = LiquidityTest.state.value
        LiquidityTest.setStateForTest(before.copy(trail = true, breakeven = true))
        try {
            assertTrue(LiquidityTest.breakevenOn())
            state()
            assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
            val p = row().open!!
            assertTrue(p.live)
            assertNull("never on a live trade", p.be)
            assertNull(p.trail)
            // Its resting stop is the plain 15% one (170 under the 200 fill), not moved by any of Boss's paper rules.
            assertEquals(170.0, p.stopTrigger!!, 0.0)
        } finally { LiquidityTest.setStateForTest(before) }
    }

    @Test fun twoLotsAtZerodhaBuyTwiceTheLotAndTheStopCoversThemAll() {
        state(lots = 2)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        val (buy, stop) = kite.placed
        assertEquals("60", buy.form["quantity"]); assertEquals("BUY", buy.form["transaction_type"])
        assertEquals("60", stop.form["quantity"]); assertEquals("SL", stop.form["order_type"])
        val p = row().open!!
        assertEquals(60, p.qty); assertEquals(30, p.lot)
        assertNull("not cut: no lots asked for kept", p.askedLots); assertNull(p.askedWords)
    }

    /**
     * 18 Nov 2026 (audit: B6; Boss, 4 Nov: "if I select 3 lots, buy 3 if the money is there, else try 2, else 1"): Liquidity's own live buy
     * of more lots than the free cash fits buys the lots it fits - one lot of 30 at about 200 is about Rs 6,000 (Rs 6,180 with the room for
     * the fill and charges) - and its stop covers what was bought.
     */
    @Test fun twoLotsTheCashFitsOnlyOneOfBuyOneLot() {
        kite.cash = 7_000.0
        state(lots = 2)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        val (buy, stop) = kite.placed
        assertEquals("30", buy.form["quantity"]); assertEquals("BUY", buy.form["transaction_type"])
        assertEquals("30", stop.form["quantity"]); assertEquals("SL", stop.form["order_type"])
        assertEquals(30, row().open!!.qty)
        assertTrue("the cash covers what was bought: nothing for the funds check", FundsCheck.recsOf().isEmpty())
        assertEquals(2, row().open!!.askedLots)
        assertEquals("bought 1 of 2 lots (free cash)", row().open!!.askedWords)
    }

    /** 18 Nov 2026 (audit: B6): three lots set, the cash fits two: two are bought - never more than Boss's lots. */
    @Test fun threeLotsTheCashFitsTwoOfBuyTwo() {
        SecurePrefs.put("g.lots", 3)
        kite.cash = 13_000.0
        state(lots = 3)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertEquals("60", kite.placed.first().form["quantity"])
        assertEquals(60, row().open!!.qty)
        // 20 Nov 2026 (audit): the lots asked for are kept on the trade, saved and read back, and said on its line.
        assertEquals(3, row().open!!.askedLots)
        assertEquals("bought 2 of 3 lots (free cash)", row().open!!.askedWords)
        OrbArms.forgetMemoryForTest()
        assertEquals("read back from the book", 3, row().open!!.askedLots)
    }

    /** 20 Nov 2026 (audit): the words of an entry that bought fewer lots than asked, with the reason; none when all were bought. */
    @Test fun theLotsAskedWords() {
        assertEquals("bought 1 of 2 lots (free cash)", OrbArms.lotsAskedWords(2, 1, 1))
        assertEquals("bought 1 of 3 lots (part filled)", OrbArms.lotsAskedWords(3, 3, 1))
        assertEquals("bought 1 of 3 lots (2 fit the free cash, 1 filled)", OrbArms.lotsAskedWords(3, 2, 1))
        assertEquals("bought 0 of 2 lots (part filled)", OrbArms.lotsAskedWords(2, 2, 0))
        assertNull("all bought", OrbArms.lotsAskedWords(2, 2, 2))
        assertNull("never more than asked", OrbArms.lotsAskedWords(2, 2, 3))
        assertNull(OrbArms.lotsAskedWords(0, 0, 0))
    }

    /** 20 Nov 2026 (coordinator): two lots, the cash fits both, only one fills: the trade says a part fill, not the free cash. */
    @Test fun aPartFillSaysPartFilledNotFreeCash() {
        kite.cash = 100_000.0
        state(lots = 2)
        kite.nextPlace(outcome = FakeKite.Outcome.PARTIAL_THEN_CANCEL, partialQty = 30)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertEquals("60", kite.placed.first().form["quantity"])
        assertEquals(30, row().open!!.qty)
        assertEquals("bought 1 of 2 lots (part filled)", row().open!!.askedWords)
        OrbArms.forgetMemoryForTest()
        assertEquals("read back from the book", "bought 1 of 2 lots (part filled)", row().open!!.askedWords)
    }

    /** 20 Nov 2026 (coordinator): three lots, the cash fits two, one fills: both reasons said. */
    @Test fun aCutAndAPartFillSayBoth() {
        SecurePrefs.put("g.lots", 3)
        kite.cash = 13_000.0
        state(lots = 3)
        kite.nextPlace(outcome = FakeKite.Outcome.PARTIAL_THEN_CANCEL, partialQty = 30)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertEquals("60", kite.placed.first().form["quantity"])
        assertEquals("bought 1 of 3 lots (2 fit the free cash, 1 filled)", row().open!!.askedWords)
    }

    /** 18 Nov 2026 (audit: B6): not even one lot fitting, the buy goes as before (Boss's lots; the funds check in shadow logs it). */
    @Test fun notEvenOneLotFittingTheBuyGoesAsBefore() {
        kite.cash = 5_000.0
        state(lots = 2)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertEquals("60", kite.placed.first().form["quantity"])
        val r = FundsCheck.recsOf().single()
        assertFalse("logged only", r.applied); assertEquals(2, r.lots)
    }

    /**
     * 19 Nov 2026 (Boss: "Go"): the lots cut prices at the LIMIT the buy goes at (201.10), not the offer: Rs 12,400 fits two lots at the
     * 200.05 offer (with the 3% room) but only one at the LIMIT - one lot is bought.
     */
    @Test fun theLotsCutPricesAtTheLimit() {
        kite.cash = 12_400.0
        state(lots = 2)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        val buy = kite.placed.first()
        assertEquals("LIMIT", buy.form["order_type"]); assertEquals("201.10", buy.form["price"])
        assertEquals("30", buy.form["quantity"])
        assertEquals(30, row().open!!.qty)
    }

    /**
     * 19 Nov 2026 (Boss: "Go"): a LIMIT buy that does not fill in the wait is cancelled - nothing booked, no stop, never chased with
     * another order; the approval is used up as before.
     */
    @Test fun anUnfilledLimitBuyIsCancelledAndNothingIsBooked() {
        state()
        kite.nextPlace(outcome = FakeKite.Outcome.OPEN)
        val msg = runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) }
        assertTrue(msg, msg.contains("did not fill"))
        val buy = kite.placed.single()
        assertEquals("LIMIT", buy.form["order_type"])
        assertEquals("cancelled by the app", "CANCELLED", kite.orders.values.single { it.side == "BUY" }.status)
        assertNull("nothing booked", row().open)
        assertTrue(row().today.isEmpty())
        assertTrue("no stop, no second buy", kite.placed.size == 1)
        assertNull("the approval used up", row().pending)
    }

    /** 19 Nov 2026 (Boss: "Go"): a LIMIT buy part filled in the wait: the rest is cancelled; what filled is booked with its stop. */
    @Test fun aPartFilledLimitBuyBooksWhatFilledWithItsStop() {
        state(lots = 2)
        kite.nextPlace(outcome = FakeKite.Outcome.PARTIAL, partialQty = 30)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        val (buy, stop) = kite.placed
        assertEquals("60", buy.form["quantity"]); assertEquals("LIMIT", buy.form["order_type"])
        val o = kite.orders.values.single { it.side == "BUY" }
        assertEquals("the rest cancelled", "CANCELLED", o.status); assertEquals(30, o.filled)
        assertEquals("SL", stop.form["order_type"]); assertEquals("30", stop.form["quantity"])
        assertEquals(30, row().open!!.qty)
    }

    @Test fun aBookSavedBeforeTheSizeTradesOneLot() {
        state(lots = null)
        assertEquals("the default is 1 lot since 08 Oct (research X1)", 1, row().lots)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertEquals("30", kite.placed.first().form["quantity"])
    }

    /** 08 Oct (research X1): 2 lots Boss never chose by hand go back to 1 lot once, with a notice; a chosen 2 stays. */
    @Test fun twoLotsNotChosenByHandGoBackToOneOnce() {
        AutomationSupport.orbState(context, JSONObject().put("positions", JSONArray()).put("liqLots", 2)
            .put("migrated", JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION)
                .put(com.optionslab.engine.orb.RetiredArms.UNRETIRE).put(com.optionslab.engine.orb.LiquidityRules.MIDCP_JOIN)))
        assertEquals(1, row().lots)
        // Boss's own 2 afterwards is kept, across a restart.
        runBlocking { OrbArms.setLiquidityLots(2, "Boss on the row") }
        AutomationSupport.reloadFromDisk(OrbArms)
        assertEquals(2, row().lots)
    }

    @Test fun threeLotsOverTheBotSettingsTwoAreRefusedByNameNeverSentSmaller() {
        state(lots = 3)
        val msg = runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) }
        assertTrue(msg, msg.startsWith("Refused: Bot settings allow 2 lots; Liquidity is set to 3."))
        assertTrue("nothing sent, not even 2 lots", kite.placed.isEmpty())
        assertNull(row().open)
        // Raised in Bot settings: the same size goes.
        SecurePrefs.put("g.lots", 3)
        state(lots = 3)
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertEquals("90", kite.placed.first().form["quantity"])
    }

    /**
     * 15 Nov (Boss: "Yes" to it in shadow): the funds check on the ORB family's own live entry. One lot of 30 at 200 is about Rs 6,000;
     * with Rs 5,000 free, in shadow (the default) the buy goes exactly as before and the would-skip is logged; switched on, it is not
     * sent. The figure is the margins the entry's own account read just read (nothing new).
     */
    @Test fun theFundsCheckInShadowLetsALiquidityLiveEntryGoAndLogsIt() {
        kite.cash = 5_000.0
        state()
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertEquals("sent as before", "BUY", kite.placed.first().form["transaction_type"])
        val r = FundsCheck.recsOf().single()
        assertFalse("logged only", r.applied); assertEquals(sym, r.symbol); assertEquals(1, r.lots)
    }

    @Test fun theFundsCheckOnRefusesALiquidityLiveEntryTheCashCannotPayFor() {
        FundsCheck.set(true)
        kite.cash = 5_000.0
        state()
        val msg = runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) }
        assertTrue(msg, msg.startsWith("Refused: ${FundsCheck.WORDS}"))
        assertTrue("nothing sent", kite.placed.isEmpty())
        assertNull("nothing held", row().open)
        assertTrue(FundsCheck.recsOf().single().applied)
    }

    /** On, with the cash there: the entry goes (it only ever skips what Zerodha would refuse). */
    @Test fun theFundsCheckOnLetsAnEntryTheCashCoversGo() {
        FundsCheck.set(true)
        state()
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertTrue(FundsCheck.recsOf().isEmpty())
    }

    @Test fun inLiveAnApprovalWithoutThePinSendsNothing() {
        state()
        assertEquals("The app is in Live: approve with your PIN on Home → Strategies.", runBlocking { OrbArms.approve("liquidity", pinConfirmed = false) })
        assertTrue(kite.requests.isEmpty())
        assertTrue("still waiting for the PIN", row().pending != null)
    }

    @Test fun theKillSwitchRefusesTheEntry() {
        SecurePrefs.put("g.kill", true)
        state()
        assertEquals("Refused: the kill switch is on", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertTrue(kite.placed.isEmpty())
    }

    // ---- Liquidity has priority over the ORB arms (Boss's 07 Oct decision); the live gates are unchanged ----

    /** ORB holding a BANKNIFTY call since 20 minutes ago: on paper, or at Zerodha when [live]. */
    private fun orbHolds(live: Boolean): JSONArray = JSONArray().put(JSONObject().put("arm", "orb")
        .put("symbol", "BANKNIFTY-ORB-52000CE").put("right", "CE").put("qty", 30).put("entry", 300.0)
        .put("entryTime", now.minusMinutes(20).toString()).put("signalBar", now.minusMinutes(25).toString()).put("live", live)
        .apply { if (live) put("kite", "BANKNIFTY26OCT52100CE") })

    @Test fun aLiveLiquidityEntryGoesBesideAPaperOrbPositionWithThePin() {
        state(held = orbHolds(live = false))
        // Without the PIN nothing is sent, as always.
        assertEquals("The app is in Live: approve with your PIN on Home → Strategies.", runBlocking { OrbArms.approve("liquidity", pinConfirmed = false) })
        assertTrue(kite.requests.isEmpty())
        // With it, ORB's paper call no longer stops Liquidity: it buys at Zerodha beside it.
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertEquals("BUY", kite.placed.first().form["transaction_type"])
        assertTrue(row().open!!.live)
        assertNotNull("ORB's paper call is untouched", runBlocking { OrbArms.view() }.arms.single { it.arm.source == "orb" }.open)
    }

    @Test fun twoLiveAutomaticPositionsOnOneIndexAreStillRefused() {
        state(held = orbHolds(live = true))
        val msg = runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) }
        assertTrue(msg, msg.startsWith("same_side_already_held: ORB holds BANKNIFTY-ORB-52000CE"))
        assertFalse("no priority over a live position", msg.contains("Liquidity has priority over ORB arms"))
        assertTrue("nothing sent", kite.placed.isEmpty())
        assertNull(row().open)
    }

    @Test fun theKillSwitchStillRefusesLiquidityBesideAPaperOrbPosition() {
        SecurePrefs.put("g.kill", true)
        state(held = orbHolds(live = false))
        assertEquals("Refused: the kill switch is on", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun anExpiredSignalIsNotEntered() {
        state(expires = now.minusMinutes(1))
        val msg = runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) }
        assertTrue(msg, msg.startsWith("The Liquidity 15+5 signal expired at "))
        assertTrue(kite.placed.isEmpty())
    }

    // ---- the order flow at a LIVE entry (9 Oct): CONFIRM may only skip; turning it on in Live takes the PIN ----

    private fun flow(buyers: Int) {
        FlowGate.testNowSec = 1_000
        OrderFlowLive.testRead = { u -> com.optionslab.app.testing.FlowFixtures.read(u, buyers, 1_000) }
    }

    @Test fun turningOrderFlowConfirmOnForALiveStrategyTakesThePin() {
        val confirm = com.optionslab.ira.OrderFlow.Mode.CONFIRM
        assertEquals(FlowGate.PIN_NEEDED, FlowGate.set("liquidity:BANKNIFTY", confirm))
        assertEquals("unchanged without the PIN", com.optionslab.ira.OrderFlow.Mode.SHADOW, FlowGate.setting("liquidity:BANKNIFTY").mode)
        assertTrue(FlowGate.needsPin("liquidity:BANKNIFTY", confirm))
        // A paper-only bot, or a mode that can skip nothing: no PIN.
        assertFalse(FlowGate.needsPin("orb_sweep", confirm))
        assertFalse(FlowGate.needsPin("orb", com.optionslab.ira.OrderFlow.Mode.OFF))
        FlowGate.set("liquidity:BANKNIFTY", confirm, pinConfirmed = true)
        assertEquals(confirm, FlowGate.setting("liquidity:BANKNIFTY").mode)
        // Already on: its threshold moves without asking again.
        assertFalse(FlowGate.needsPin("liquidity:BANKNIFTY", confirm))
        FlowGate.set("liquidity:BANKNIFTY", confirm, threshold = 65)
        assertEquals(65, FlowGate.setting("liquidity:BANKNIFTY").threshold)
        assertTrue("setting it sends nothing", kite.requests.isEmpty())
    }

    @Test fun orderFlowConfirmSkipsALiveEntryWhenTheFlowDisagrees() {
        FlowGate.set("liquidity:BANKNIFTY", com.optionslab.ira.OrderFlow.Mode.CONFIRM, pinConfirmed = true)
        flow(30)                                                             // sellers: against the call
        state()
        assertEquals(OrbArms.describe(FlowGate.SKIPPED), runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertTrue("nothing sent to Zerodha", kite.placed.isEmpty())
        assertNull(row().open)
        val s = FlowGate.signalsForTest().single()
        assertTrue(s.live && s.skipped)
    }

    @Test fun orderFlowNeverPlacesATradeTheStrategyDidNotSignal() {
        FlowGate.set("liquidity:BANKNIFTY", com.optionslab.ira.OrderFlow.Mode.CONFIRM, pinConfirmed = true)
        flow(95)                                                             // the flow strongly agrees with buying calls
        // Armed in Live, nothing waiting: however strong the flow, nothing is sent and nothing is logged.
        fun flags(v: Boolean) = JSONObject().apply { listOf("liquidity15", "liquidity5").forEach { put(it, v) } }
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", flags(true)).put("auto", flags(false)).put("liveOk", flags(true)).put("liqLots", 1).put("liqLotsChosen", true)
            .put("migrated", JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION).put(com.optionslab.engine.orb.RetiredArms.UNRETIRE))
            .put("positions", JSONArray()))
        assertEquals("Nothing is waiting for approval.", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertTrue(kite.placed.isEmpty())
        assertTrue(FlowGate.signalsForTest().isEmpty())
        // The strategy's own signal with the flow agreeing: exactly its own entry (1 lot and its stop), nothing more.
        state()
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertEquals(2, kite.placed.size)
        assertEquals("30", kite.placed.first().form["quantity"])
        val s = FlowGate.signalsForTest().single()
        assertEquals(com.optionslab.ira.OrderFlow.Agreement.AGREES, s.agreement); assertFalse(s.skipped); assertTrue(s.live)
    }

    // ---- review G2-1 / G2-5 (11 Oct): the approval decides the account again; a park to paper never reaches Zerodha ----

    /** The paper feed, served by a fake (no quote for the contract): a paper entry is refused without reaching the internet. */
    private fun <T> withUpstox(body: () -> T): T { val u = com.optionslab.app.testing.FakeUpstox(); try { return body() } finally { u.close() } }

    @Test fun parkedToPaperByItsReviewAnApprovalInLiveNeverReachesZerodha() = withUpstox {
        state()
        assertTrue(SmartWorkers.parkByAutopilot("liquidity") != null)
        val msg = runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) }
        assertFalse(msg, msg == "Entered at Zerodha (live).")
        assertTrue("nothing sent to Zerodha: ${kite.placed.map { it.form }}", kite.placed.isEmpty())
        assertFalse("no live position", row().open?.live == true)
        assertNull("the approval is used up", row().pending)
    }

    @Test fun parkedToPaperItsApprovalAsksNoPinAndStillSendsNothing() = withUpstox {
        state()
        SmartWorkers.parkByAutopilot("liquidity")
        val msg = runBlocking { OrbArms.approve("liquidity", pinConfirmed = false) }
        assertFalse("a paper entry takes no PIN", msg == "The app is in Live: approve with your PIN on Home → Strategies.")
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun aPaperNoticeIsNeverEnteredAtZerodha() {
        // The notice said "Paper account" (the switch was in Paper, or it was parked then); now the app is in Live, unparked.
        state(noticed = false)
        val msg = runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) }
        assertTrue(msg, msg.startsWith("Not bought: the signal was for the paper account"))
        assertTrue("nothing sent to Zerodha", kite.placed.isEmpty())
        assertNull(row().open)
        assertNull("the signal is used up: a new break asks again", row().pending)
    }

    @Test fun aLiveNoticeParkedSinceIsNotBoughtAnywhere() {
        state(noticed = true)
        SmartWorkers.parkByAutopilot("liquidity")
        val msg = runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) }
        assertTrue(msg, msg.startsWith("Not bought: the signal was for Zerodha (live)"))
        assertTrue(kite.placed.isEmpty())
        assertNull(row().open)
    }

    @Test fun aLiveNoticeStillLiveIsEnteredWithThePinAsBefore() {
        state(noticed = true)
        assertEquals("The app is in Live: approve with your PIN on Home → Strategies.", runBlocking { OrbArms.approve("liquidity", pinConfirmed = false) })
        assertTrue(kite.requests.isEmpty())
        assertEquals("Entered at Zerodha (live).", runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) })
        assertTrue(checkNotNull(row().open).live)
    }

    /** G2-5: under the arms' lock the park is read from memory only; not loaded yet counts as parked (fail closed). */
    @Test fun theParkIsReadFromMemoryAndUnloadedCountsAsParked() = withUpstox {
        val loaded = SmartWorkers::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        try {
            loaded.setBoolean(null, false)
            SecurePrefs.readsForTest.set(0)
            SecurePrefs.countedThread = Thread.currentThread()
            assertTrue("not loaded: parked", SmartWorkers.parkedToPaperNow("liquidity"))
            SecurePrefs.countedThread = null
            assertEquals("no settings read", 0L, SecurePrefs.readsForTest.get())
            // The approval loads the parks off the lock first: a park the review saved is honoured after a restart.
            SecurePrefs.put("brain.parked", "liquidity")
            state()
            val msg = runBlocking { OrbArms.approve("liquidity", pinConfirmed = true) }
            assertFalse(msg, msg == "Entered at Zerodha (live).")
            assertTrue(kite.placed.isEmpty())
            assertTrue("loaded by then", loaded.getBoolean(null))
            assertTrue(SmartWorkers.parkedToPaper("liquidity"))
        } finally {
            SecurePrefs.countedThread = null
            SmartWorkers.resetForTest()
        }
    }
}
