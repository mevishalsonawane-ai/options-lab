package com.optionslab.engine.orb

import com.optionslab.engine.mcx.McxTrendRules
import com.optionslab.engine.orb.LiveMirror.Decision
import com.optionslab.engine.orb.LiveMirror.Gate
import com.optionslab.engine.orb.LiveMirror.Look
import com.optionslab.engine.orb.LiveMirror.Scale
import com.optionslab.engine.orb.LiveMirror.Step
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The live path of the paper strategies (18 Oct, Boss: every paper strategy present for live too), its pure rules. */
class LiveMirrorTest {
    private val day = LocalDate.of(2026, 10, 27)
    private fun at(h: Int, m: Int, d: LocalDate = day): LocalDateTime = d.atTime(h, m)

    // ---- who it is for ----------------------------------------------------------------------------------------------------------

    @Test fun everyPaperOnlyStrategyHasALivePathAndJarvisOwnIdeasHaveNone() {
        val keys = LiveMirror.ARMS.map { it.key }
        for (k in listOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE, OovArm.SOURCE, Solo2.SOURCE, LiveMirror.SOLO_MIDDAY,
                SweepRules.ARM.source, RangeFadeRules.ARM.source, HeroRules.ARM.source, HeroTicketRules.SOURCE, Ft3Rules.SOURCE, VixDivRules.SOURCE,
                NightRules.SOURCE, LiveMirror.NTFY_ALERTS, LiveMirror.SHADOW_PROMOTED, "mcx_ng_eve", "mcx_silverm_am", "mcx_trend12", "mcx_us_silver"))
            assertTrue(k in keys, k)
        assertEquals(keys.size, keys.toSet().size, "no key twice")
        // Jarvis's own free-form ideas stay on paper (CLAUDE.md): the news trades and the forge ideas are never mirrored.
        for (k in listOf("jarvis", "news", "forge", "grammar", "jarvis_news")) assertNull(LiveMirror.spec(k))
        // The ORB family's points stay points; MCX and Night carry overnight with a GTT, never an SL beside it.
        assertEquals(Scale.POINTS, LiveMirror.spec(SweepRules.ARM.source)!!.scale)
        assertEquals(Scale.PCT, LiveMirror.spec(DonchianArm.SOURCE)!!.scale)
        assertEquals(LiveMirror.Rest.GTT, LiveMirror.spec(NightRules.SOURCE)!!.rest)
        assertEquals("MCX", LiveMirror.spec("mcx_trend12")!!.exchange)
        assertEquals(LiveMirror.Rest.SL, LiveMirror.spec(Solo2.SOURCE)!!.rest)
        assertEquals(1, LiveMirror.MCX_LOTS)
        assertEquals("iraarm", LiveMirror.TAG)
    }

    @Test fun eachSwitchStartsOnAndOnlyBossTurnsItOff() {
        assertTrue(LiveMirror.DEFAULT_ON)
        assertTrue(LiveMirror.on(DonchianArm.SOURCE, emptyMap()))
        assertFalse(LiveMirror.on(DonchianArm.SOURCE, mapOf(DonchianArm.SOURCE to false)))
        assertTrue(LiveMirror.on(DonchianArm.SOURCE, mapOf(DonchianArm.SOURCE to true)))
        assertFalse(LiveMirror.on("nope", mapOf("nope" to true)), "an unknown arm never")
        // An external message (ntfy alerts, 25 Oct) starts off: only Boss switches it on.
        assertFalse(LiveMirror.on(LiveMirror.NTFY_ALERTS, emptyMap()))
        assertTrue(LiveMirror.on(LiveMirror.NTFY_ALERTS, mapOf(LiveMirror.NTFY_ALERTS to true)))
        assertEquals(listOf(LiveMirror.NTFY_ALERTS), LiveMirror.ARMS.filter { !it.defaultOn }.map { it.key })
    }

    // ---- the gate -----------------------------------------------------------------------------------------------------------------

    @Test fun anEntryGoesOnlyInLiveWithAiLiveAndTheArmOnAndEveryGuardClear() {
        val ok = Gate(appLive = true, aiLive = true, armOn = true)
        assertNull(LiveMirror.refusal(ok))
        assertEquals("the app is in Paper", LiveMirror.refusal(ok.copy(appLive = false)))
        assertEquals("\"AI trades go live\" is off", LiveMirror.refusal(ok.copy(aiLive = false)))
        assertEquals("its \"Trade live\" switch is off", LiveMirror.refusal(ok.copy(armOn = false)))
        assertEquals("paper-only until 26 Oct", LiveMirror.refusal(ok.copy(paperOnly = "paper-only until 26 Oct")))
        assertEquals("the kill switch is on", LiveMirror.refusal(ok.copy(kill = true)))
        assertEquals("the bot is stopped for today", LiveMirror.refusal(ok.copy(stopped = true)))
        assertEquals("the daily loss limit was reached today", LiveMirror.refusal(ok.copy(lossLimit = true)))
        assertEquals("day lock", LiveMirror.refusal(ok.copy(dayLock = "day lock")))
        assertEquals("the account stop rule: drawdown", LiveMirror.refusal(ok.copy(accountStop = "drawdown")))
        assertEquals("not logged in to Zerodha today", LiveMirror.refusal(ok.copy(loggedIn = false)))
        assertEquals("this phone failed the security check", LiveMirror.refusal(ok.copy(compromised = true)))
        // Paper wins over everything: in Paper nothing is said about Zerodha at all.
        assertEquals("the app is in Paper", LiveMirror.refusal(Gate(false, false, false, "p", true, true, true, "d", "a", false, true)))
    }

    // ---- size ---------------------------------------------------------------------------------------------------------------------

    @Test fun theLiveSizeIsThePaperLotsNeverMore() {
        assertEquals(2, LiveMirror.lots(60, 30))
        assertEquals(1, LiveMirror.lots(45, 30))
        assertEquals(0, LiveMirror.lots(20, 30))
        assertEquals(0, LiveMirror.lots(30, 0))
        assertEquals(0, LiveMirror.lots(0, 30))
        assertEquals(60, LiveMirror.liveQty(2, 30))
        assertEquals(30, LiveMirror.liveQty(2, 30, maxLots = 1))
        assertEquals(60, LiveMirror.liveQty(2, 30, maxLots = 5))
        // 21 Oct: the words for an entry cut to the lot cap - only when it is smaller than the paper's lots.
        assertEquals(" · 2 of the paper's 9 lots (your lot cap)", LiveMirror.cutWords(9, 60, 30))
        assertEquals("", LiveMirror.cutWords(2, 60, 30)); assertEquals("", LiveMirror.cutWords(9, 270, 30))
        assertEquals("", LiveMirror.cutWords(9, 0, 30)); assertEquals("", LiveMirror.cutWords(9, 60, 0))
        assertEquals(0, LiveMirror.liveQty(0, 30))
        assertEquals(0, LiveMirror.liveQty(1, 0))
    }

    // ---- levels -------------------------------------------------------------------------------------------------------------------

    @Test fun aPaperLevelIsTheSameShareOrTheSamePointsOnTheLiveFill() {
        // -15% on paper 200 -> 170; the live fill 210 -> 178.50.
        assertEquals(178.5, LiveMirror.level(200.0, 170.0, 210.0, Scale.PCT, 1, 0.05)!!, 1e-9)
        // The ORB family's -40 points: 200 -> 160 on paper, 210 -> 170 live.
        assertEquals(170.0, LiveMirror.level(200.0, 160.0, 210.0, Scale.POINTS, 1, 0.05)!!, 1e-9)
        // A long's stop rounds down to the tick, a short's up.
        assertEquals(10.15, LiveMirror.level(100.0, 85.0, 11.99, Scale.PCT, 1, 0.05)!!, 1e-9)
        assertEquals(10.2, LiveMirror.level(100.0, 85.0, 11.99, Scale.PCT, -1, 0.05)!!, 1e-9)
        // Unusable prices, or a level that is no price.
        assertNull(LiveMirror.level(0.0, 1.0, 1.0, Scale.PCT, 1, 0.05))
        assertNull(LiveMirror.level(1.0, Double.NaN, 1.0, Scale.PCT, 1, 0.05))
        assertNull(LiveMirror.level(1.0, 1.0, -1.0, Scale.PCT, 1, 0.05))
        assertNull(LiveMirror.level(1.0, 1.0, 1.0, Scale.PCT, 1, 0.0))
        assertNull(LiveMirror.level(1.0, -1.0, 1.0, Scale.PCT, 1, 0.05))
        assertNull(LiveMirror.level(Double.POSITIVE_INFINITY, 1.0, 1.0, Scale.PCT, 1, 0.05))
        assertNull(LiveMirror.level(1.0, 1.0, Double.NaN, Scale.PCT, 1, 0.05))
        assertNull(LiveMirror.level(1.0, 1.0, 1.0, Scale.PCT, 1, Double.NaN))
        assertNull(LiveMirror.level(200.0, 160.0, 30.0, Scale.POINTS, 1, 0.05), "40 points under a 30 fill is no price")
    }

    @Test fun theStopsLimitIsBeyondItsTriggerAsTheOrbArms() {
        assertEquals(152.0, LiveMirror.stopLimit(160.0, 0.05, 1), 1e-9)       // 5% under
        assertEquals(8.0, LiveMirror.stopLimit(10.0, 0.05, 1), 1e-9)          // at least 2 points
        assertEquals(0.05, LiveMirror.stopLimit(1.0, 0.05, 1), 1e-9)          // never under a tick
        assertEquals(168.0, LiveMirror.stopLimit(160.0, 0.05, -1), 1e-9)      // a short's buy: above
    }

    @Test fun aStopRestsOnlyOnTheRightSideOfThePriceAndOnlyEverTightens() {
        assertTrue(LiveMirror.mayRest(160.0, 200.0, 0.05, 1))
        assertTrue(LiveMirror.mayRest(199.95, 200.0, 0.05, 1))
        assertFalse(LiveMirror.mayRest(200.0, 200.0, 0.05, 1))
        assertTrue(LiveMirror.mayRest(240.0, 200.0, 0.05, -1))
        assertFalse(LiveMirror.mayRest(200.0, 200.0, 0.05, -1))
        assertFalse(LiveMirror.mayRest(Double.NaN, 200.0, 0.05, 1))
        assertFalse(LiveMirror.mayRest(160.0, Double.NaN, 0.05, 1))
        assertFalse(LiveMirror.mayRest(-1.0, 200.0, 0.05, 1))
        assertFalse(LiveMirror.mayRest(1.0, 0.0, 0.05, 1))
        assertEquals(180.0, LiveMirror.move(160.0, 180.0, 200.0, 0.05, 1))
        assertNull(LiveMirror.move(180.0, 160.0, 200.0, 0.05, 1), "never down")
        assertNull(LiveMirror.move(160.0, 200.0, 200.0, 0.05, 1), "not at the price")
        assertEquals(160.0, LiveMirror.move(null, 160.0, 200.0, 0.05, 1))
        assertNull(LiveMirror.move(160.0, null, 200.0, 0.05, 1))
        assertNull(LiveMirror.move(160.0, 180.0, null, 0.05, 1))
        assertEquals(220.0, LiveMirror.move(240.0, 220.0, 200.0, 0.05, -1))
        assertNull(LiveMirror.move(220.0, 240.0, 200.0, 0.05, -1), "a short's never up")
    }

    @Test fun atTheStopAndThroughItsLimit() {
        assertTrue(LiveMirror.atStop(160.0, 160.0, 1)); assertFalse(LiveMirror.atStop(160.05, 160.0, 1))
        assertTrue(LiveMirror.atStop(240.0, 240.0, -1)); assertFalse(LiveMirror.atStop(239.0, 240.0, -1))
        assertFalse(LiveMirror.atStop(null, 160.0, 1)); assertFalse(LiveMirror.atStop(150.0, null, 1))
        assertFalse(LiveMirror.atStop(0.0, 160.0, 1)); assertFalse(LiveMirror.atStop(150.0, 0.0, 1))
        assertTrue(LiveMirror.runThrough(151.9, 160.0, 0.05, 1)); assertFalse(LiveMirror.runThrough(152.0, 160.0, 0.05, 1))
        assertTrue(LiveMirror.runThrough(168.1, 160.0, 0.05, -1)); assertFalse(LiveMirror.runThrough(168.0, 160.0, 0.05, -1))
        assertFalse(LiveMirror.runThrough(null, 160.0, 0.05, 1)); assertFalse(LiveMirror.runThrough(100.0, null, 0.05, 1))
        assertFalse(LiveMirror.runThrough(0.0, 160.0, 0.05, 1)); assertFalse(LiveMirror.runThrough(100.0, -1.0, 0.05, 1))
    }

    // ---- square-off -----------------------------------------------------------------------------------------------------------

    @Test fun theMirrorSquaresAnIntradayNsePositionOffAt1515AndOnExpiryDayAt1505() {
        assertFalse(LiveMirror.squareOffDue(at(15, 14), "NFO", "MIS", null, day))
        assertTrue(LiveMirror.squareOffDue(at(15, 15), "NFO", "MIS", null, day))
        assertFalse(LiveMirror.squareOffDue(at(15, 4), "NFO", "MIS", day, day))
        assertTrue(LiveMirror.squareOffDue(at(15, 5), "NFO", "MIS", day, day))
        assertFalse(LiveMirror.squareOffDue(at(15, 5), "NFO", "MIS", day.plusDays(2), day), "another expiry")
        assertTrue(LiveMirror.squareOffDue(at(9, 20), "NFO", "MIS", null, day.minusDays(1)), "held over from yesterday")
        assertFalse(LiveMirror.squareOffDue(at(15, 20), "NFO", "NRML", null, day), "carried")
        assertFalse(LiveMirror.squareOffDue(at(15, 20), "MCX", "MIS", null, day), "MCX")
        assertTrue(LiveMirror.squareOffDue(at(15, 15), "BFO", "MIS", null, day), "SENSEX on BFO")
    }

    // ---- partial exits ----------------------------------------------------------------------------------------------------------

    @Test fun aPartialPaperSaleSellsTheSameShareLiveRoundedTowardLessRisk() {
        assertEquals(60, LiveMirror.toSell(60, 60, 60, null, 30))         // paper closed: all
        assertEquals(60, LiveMirror.toSell(60, 60, 60, 0, 30))
        assertEquals(0, LiveMirror.toSell(60, 60, 60, 60, 30))            // paper holds all: nothing
        assertEquals(30, LiveMirror.toSell(60, 60, 60, 30, 30))           // half
        assertEquals(60, LiveMirror.toSell(60, 60, 90, 30, 30))           // a third of 2 lots rounds down to none kept
        assertEquals(0, LiveMirror.toSell(30, 60, 60, 30, 30))            // already sold its share
        assertEquals(0, LiveMirror.toSell(0, 60, 60, null, 30))
        assertEquals(0, LiveMirror.toSell(60, 60, 0, 30, 30))
        assertEquals(0, LiveMirror.toSell(60, 60, 60, 90, 30))
        assertEquals(1, LiveMirror.toSell(2, 2, 2, 1, 0))                 // an unknown lot counts as 1
    }

    // ---- the watch ------------------------------------------------------------------------------------------------------------

    private val held = Look(side = 1, qty = 30, held = 30, ltp = 200.0, tick = 0.05, stopTrigger = 170.0, stopResting = true)

    @Test fun theWatchBooksAFilledStopAndAPositionClosedAtZerodha() {
        assertEquals(Step.STOP_FILLED, LiveMirror.decide(held.copy(stopFilled = true)).step)
        assertEquals(Decision(Step.HOLD), LiveMirror.decide(held.copy(held = null)))
        assertEquals(Step.CLOSED_OUTSIDE, LiveMirror.decide(held.copy(held = 0)).step)
        assertEquals(Step.CLOSED_OUTSIDE, LiveMirror.decide(held.copy(held = -30)).step)
        assertEquals(Step.CLOSED_OUTSIDE, LiveMirror.decide(held.copy(side = -1, held = 30)).step)
    }

    @Test fun bossesStopTheSquareOffAndThePaperExitSellAtOnceNeverMoreThanHeld() {
        assertEquals(Decision(Step.SELL, "operator_stop", 30), LiveMirror.decide(held.copy(operatorStop = true)))
        assertEquals(Decision(Step.SELL, "square_off", 30), LiveMirror.decide(held.copy(squareOff = true)))
        assertEquals(Decision(Step.SELL, "target", 30), LiveMirror.decide(held.copy(paperSell = 30, paperReason = "target")))
        assertEquals(Decision(Step.SELL, "paper_exit", 30), LiveMirror.decide(held.copy(paperSell = 60)))
        assertEquals(Decision(Step.SELL, "square_off", 15), LiveMirror.decide(held.copy(held = 15, squareOff = true)), "never more than Zerodha holds")
        assertEquals(Decision(Step.SELL, "square_off", 30), LiveMirror.decide(held.copy(side = -1, held = -30, squareOff = true)))
    }

    @Test fun theStopReachedWithNothingRestingOrThroughTheLimitSellsAtMarket() {
        assertEquals(Decision(Step.SELL, "stop", 30), LiveMirror.decide(held.copy(stopResting = false, ltp = 169.0)))
        assertEquals(Decision(Step.HOLD), LiveMirror.decide(held.copy(ltp = 169.0)))                      // Zerodha's stop sells it
        assertEquals(Decision(Step.SELL, "stop_run_through", 30), LiveMirror.decide(held.copy(ltp = 150.0)))
    }

    @Test fun theStopIsPlacedWhenNoneRestsAndMovedUpToThePaperLevel() {
        assertEquals(Decision(Step.PLACE_STOP, to = 170.0), LiveMirror.decide(held.copy(stopResting = false)))
        assertEquals(Decision(Step.PLACE_STOP, to = 185.0), LiveMirror.decide(held.copy(stopResting = false, wanted = 185.0)))
        assertEquals(Decision(Step.PLACE_STOP, to = 185.0), LiveMirror.decide(held.copy(stopResting = false, stopTrigger = null, wanted = 185.0)))
        assertEquals(Decision(Step.HOLD), LiveMirror.decide(held.copy(stopResting = false, stopTrigger = null)))
        assertEquals(Decision(Step.HOLD), LiveMirror.decide(held.copy(stopResting = false, stopTrigger = null, wanted = 200.0)), "not at the price")
        assertEquals(Decision(Step.HOLD), LiveMirror.decide(held.copy(stopResting = false, ltp = null)))
        assertEquals(Decision(Step.MOVE_STOP, to = 190.0), LiveMirror.decide(held.copy(wanted = 190.0)))
        assertEquals(Decision(Step.HOLD), LiveMirror.decide(held.copy(wanted = 160.0)))                   // never down
        assertEquals(Decision(Step.HOLD), LiveMirror.decide(held.copy(ltp = null)))
        // A short: its buy stop above, moved down.
        val short = Look(side = -1, qty = 30, held = -30, ltp = 200.0, tick = 0.05, stopTrigger = 230.0, stopResting = false, wanted = 220.0)
        assertEquals(Decision(Step.PLACE_STOP, to = 220.0), LiveMirror.decide(short))
        assertEquals(Decision(Step.MOVE_STOP, to = 220.0), LiveMirror.decide(short.copy(stopResting = true)))
    }

    @Test fun aLivePositionIsSoldForItsPaperTradeOnlyOnceThatWasSeenOpen() {
        assertTrue(LiveMirror.paperGone(seen = true, paperOpen = false))
        assertFalse(LiveMirror.paperGone(seen = false, paperOpen = false), "the paper view has not caught up yet")
        assertFalse(LiveMirror.paperGone(seen = true, paperOpen = null), "the book unread")
        assertFalse(LiveMirror.paperGone(seen = true, paperOpen = true))
    }

    @Test fun rupees() {
        assertEquals("Rs 1,234", LiveMirror.rs(1234.4))
        assertEquals("-Rs 40,000", LiveMirror.rs(-40_000.0))
        assertNotNull(LiveMirror.spec(LiveMirror.NTFY_ALERTS)?.signal?.takeIf { it.contains("not researched") })
        // 25 Oct: a live trade the removed Telegram calls arm held is kept by ntfy alerts' switch and book.
        assertEquals(LiveMirror.NTFY_ALERTS, LiveMirror.migratedKey("telegram_calls"))
        assertEquals(DonchianArm.SOURCE, LiveMirror.migratedKey(DonchianArm.SOURCE))
    }

    // ---- shadow and the safety stop (18 Oct) ----------------------------------------------------------------------------------

    @Test fun shadowStartsOnForEveryArmAndOnlyBossesChoiceTurnsItOff() {
        assertTrue(LiveMirror.SHADOW_DEFAULT)
        for (a in LiveMirror.ARMS) assertTrue(LiveMirror.shadowOn(a.key, emptyMap()), a.key)
        assertFalse(LiveMirror.shadowOn(DonchianArm.SOURCE, mapOf(DonchianArm.SOURCE to false)))
        assertTrue(LiveMirror.shadowOn(DonchianArm.SOURCE, mapOf(DonchianArm.SOURCE to true)))
        assertTrue(LiveMirror.shadowOn("unknown", mapOf("unknown" to false)), "an unknown arm is never live")
    }

    @Test fun aShadowExitFillsAsTheLiveMirrorWould() {
        assertEquals(170.0, LiveMirror.shadowExit(true, 169.0, 170.0, 0.05, 1), 1e-9)      // its SL's trigger
        assertEquals(150.0, LiveMirror.shadowExit(true, 150.0, 170.0, 0.05, 1), 1e-9)      // through the limit: at market
        assertEquals(230.0, LiveMirror.shadowExit(true, 231.0, 230.0, 0.05, -1), 1e-9)
        assertEquals(205.0, LiveMirror.shadowExit(false, 205.0, 170.0, 0.05, 1), 1e-9)     // any other exit: at market
        assertEquals(205.0, LiveMirror.shadowExit(true, 205.0, null, 0.05, 1), 1e-9)
    }

    @Test fun theShadowRecord() {
        val r = LiveMirror.record(listOf(100.0, -40.0, 60.0, -20.0))
        assertEquals(4, r.trades); assertEquals(100.0, r.net, 1e-9); assertEquals(0.5, r.winRate, 1e-9)
        assertEquals("4 trades, Rs 100, 50% won", LiveMirror.recordWords(r))
        assertTrue(LiveMirror.record(emptyList()).winRate.isNaN())
        assertEquals("no shadow trade yet", LiveMirror.recordWords(LiveMirror.record(emptyList())))
        assertEquals("1 trade, -Rs 5, 0% won", LiveMirror.recordWords(LiveMirror.record(listOf(-5.0))))
    }

    @Test fun theSafetyStopIs30PercentBeyondTheFillForTheArmsWithoutAPremiumStop() {
        assertEquals(140.0, LiveMirror.safetyLevel(200.0, 1, 0.05)!!, 1e-9)
        assertEquals(260.0, LiveMirror.safetyLevel(200.0, -1, 0.05)!!, 1e-9)
        assertNull(LiveMirror.safetyLevel(0.0, 1, 0.05))
        assertEquals(setOf(NightRules.SOURCE, LiveMirror.SOLO_MIDDAY, Solo2.SOURCE, VixDivRules.SOURCE, McxTrendRules.SOURCE, LiveMirror.SHADOW_PROMOTED),
            LiveMirror.ARMS.filter { it.safety }.map { it.key }.toSet())
        assertEquals(0.30, LiveMirror.SAFETY_PCT, 1e-9)
    }

    // ---- review fixes (18 Oct) ------------------------------------------------------------------------------------------------

    @Test fun aRestingStopIsShrunkToWhatIsHeldLessTheOtherWorkingExits() {
        val l = Look(side = 1, qty = 60, held = 30, ltp = 200.0, tick = 0.05, stopTrigger = 170.0, stopResting = true, stopQty = 60, lot = 30)
        assertEquals(Decision(Step.SHRINK_STOP, "held_less", 30), LiveMirror.decide(l))           // Boss sold one of two lots by hand
        assertEquals(Decision(Step.SHRINK_STOP, "held_less", 0), LiveMirror.decide(l.copy(otherWorking = 30)))   // another holder covers it
        assertEquals(Decision(Step.HOLD), LiveMirror.decide(l.copy(held = 60)))
        assertEquals(Decision(Step.HOLD), LiveMirror.decide(l.copy(held = 120, otherWorking = 60)))
        assertEquals(Decision(Step.SHRINK_STOP, "held_less", 20), LiveMirror.decide(l.copy(side = -1, held = -20, stopQty = 30, lot = 0)))
        assertEquals(Step.SELL, LiveMirror.decide(l.copy(squareOff = true)).step, "a sale due goes first")
    }

    @Test fun aTradeNeverSeenHeldIsNotClosedOnALaggingZeroRead() {
        val l = Look(side = 1, qty = 30, held = 0, ltp = 200.0, tick = 0.05, stopTrigger = 170.0, stopResting = true, heldSeen = false)
        assertEquals(Decision(Step.HOLD), LiveMirror.decide(l))
        assertEquals(Step.CLOSED_OUTSIDE, LiveMirror.decide(l.copy(heldSeen = true)).step)
        assertFalse(LiveMirror.zeroLongEnough(null, 100_000))
        assertFalse(LiveMirror.zeroLongEnough(50_000, 100_000))
        assertTrue(LiveMirror.zeroLongEnough(40_000, 100_000))
    }

    @Test fun throughAFiredGttsOwnLimit() {
        // A GTT's leg is 5% under its trigger: 170 -> 161.5; through it below that.
        assertTrue(LiveMirror.throughLimit(161.0, 170.0, 161.5, 0.05, 1))
        assertFalse(LiveMirror.throughLimit(162.0, 170.0, 161.5, 0.05, 1))
        assertTrue(LiveMirror.throughLimit(180.0, 170.0, 178.5, 0.05, -1))
        assertFalse(LiveMirror.throughLimit(null, 170.0, 161.5, 0.05, 1))
        assertFalse(LiveMirror.throughLimit(150.0, 170.0, -1.0, 0.05, 1))
        assertTrue(LiveMirror.throughLimit(151.0, 160.0, null, 0.05, 1), "no own limit: the SL's")
        val l = Look(side = 1, qty = 30, held = 30, ltp = 161.0, tick = 0.05, stopTrigger = 170.0, stopResting = true, stopLimit = 161.5)
        assertEquals(Decision(Step.SELL, "stop_run_through", 30), LiveMirror.decide(l))
    }

    @Test fun lateEntriesAndEntriesAgainstTheNetAreRefused() {
        assertNull(LiveMirror.tooLate(at(15, 14), "NFO", "MIS", null))
        assertNotNull(LiveMirror.tooLate(at(15, 15), "NFO", "MIS", null))
        assertNotNull(LiveMirror.tooLate(at(15, 5), "NFO", "MIS", day))
        assertNull(LiveMirror.tooLate(at(15, 20), "NFO", "NRML", null))
        assertNotNull(LiveMirror.tooLate(at(15, 6), "NFO", "NRML", day))
        assertNull(LiveMirror.tooLate(at(23, 0), "MCX", "NRML", day))
        assertNull(LiveMirror.againstNet(1, 0)); assertNull(LiveMirror.againstNet(1, 30)); assertNull(LiveMirror.againstNet(-1, -1))
        assertNotNull(LiveMirror.againstNet(-1, 1)); assertNotNull(LiveMirror.againstNet(1, -2))
    }

    @Test fun aCarriedNseOptionIsSquaredOffAt1505OnItsExpiryDay() {
        assertTrue(LiveMirror.squareOffDue(at(15, 5), "NFO", "NRML", day, day.minusDays(1)))
        assertFalse(LiveMirror.squareOffDue(at(15, 4), "NFO", "NRML", day, day.minusDays(1)))
        assertFalse(LiveMirror.squareOffDue(at(9, 16), "NFO", "NRML", day.plusDays(1), day.minusDays(1)))
        assertFalse(LiveMirror.squareOffDue(at(15, 5), "MCX", "NRML", day, day))
    }

    @Test fun closeJudgedArmsRestOnlyADisasterStop() {
        assertEquals(setOf(HeroRules.ARM.source, HeroTicketRules.SOURCE, Ft3Rules.SOURCE), LiveMirror.ARMS.filter { it.closeStop }.map { it.key }.toSet())
        // 21 Oct: only FT3's live size is cut to the lot cap; every other arm's over-cap order stays refused by the order gates.
        assertEquals(setOf(Ft3Rules.SOURCE), LiveMirror.ARMS.filter { it.capLots }.map { it.key }.toSet())
        // 21 Oct: FT3 is mirrored like every paper arm - "Trade live" on (Boss asked for it live), its Shadow on until Boss's PIN.
        val ft3 = LiveMirror.spec(Ft3Rules.SOURCE)!!
        assertEquals(LiveMirror.FT3, ft3.family); assertEquals(Ft3Rules.LABEL, ft3.label)
        assertTrue(ft3.defaultOn && LiveMirror.on(Ft3Rules.SOURCE, emptyMap()))
        assertTrue(LiveMirror.shadowOn(Ft3Rules.SOURCE, emptyMap()))
        assertEquals("MIS", ft3.product); assertEquals(LiveMirror.Rest.SL, ft3.rest); assertFalse(ft3.safety)
        assertTrue("option chain" in ft3.signal)
        assertEquals(4.0, LiveMirror.disasterLevel(20.0, 1, 0.05)!!, 1e-9)
        assertEquals(36.0, LiveMirror.disasterLevel(20.0, -1, 0.05)!!, 1e-9)
        assertNull(LiveMirror.disasterLevel(0.1, 1, 0.05))
        assertTrue(LiveMirror.spec(LiveMirror.SHADOW_PROMOTED)!!.safety)
    }

    // ---- final review F4: shadow P&L no better than live -------------------------------------------------------------------

    @Test fun aShadowMarketFillPaysTheAskOrGetsTheBidNeverBetterThanTheMeasuredSlippage() {
        // A real book: a buy at the ask, a sale at the bid.
        assertEquals(200.05, LiveMirror.shadowFill(true, 200.0, 199.95, 200.05, 0.05, 0.0016, 0.0), 1e-9)
        assertEquals(199.95, LiveMirror.shadowFill(false, 200.0, 199.95, 200.05, 0.05, 0.0016, 0.0), 1e-9)
        // No book (or a junk one): the last moved by the default half-spread, rounded against the order.
        assertEquals(200.35, LiveMirror.shadowFill(true, 200.0, null, null, 0.05, 0.0016, 0.0), 1e-9)
        assertEquals(199.65, LiveMirror.shadowFill(false, 200.0, null, 200.05, 0.05, 0.0016, 0.0), 1e-9)
        assertEquals(200.35, LiveMirror.shadowFill(true, 200.0, 100.0, 300.0, 0.05, 0.0016, 0.0), 1e-9)
        // The measured live slippage when it is worse than the touch (not added to it: it already holds the spread).
        assertEquals(201.0, LiveMirror.shadowFill(true, 200.0, 199.95, 200.05, 0.05, 0.0016, 0.005), 1e-9)
        assertEquals(199.0, LiveMirror.shadowFill(false, 200.0, 199.95, 200.05, 0.05, 0.0016, 0.005), 1e-9)
        assertEquals(200.05, LiveMirror.shadowFill(true, 200.0, 199.95, 200.05, 0.05, 0.0016, -0.01), 1e-9, "a better measured fill never helps")
        // Junk: a NaN slippage is none; no last price uses the book's mid; never under one tick.
        assertEquals(200.05, LiveMirror.shadowFill(true, 200.0, 199.95, 200.05, 0.05, Double.NaN, Double.NaN), 1e-9)
        assertEquals(200.05, LiveMirror.shadowFill(true, 0.0, 199.95, 200.05, 0.05, 0.0016, 0.0), 1e-9)
        assertTrue(LiveMirror.shadowFill(true, 0.0, null, null, 0.05, 0.0016, 0.0) <= 0.0)
        assertEquals(0.05, LiveMirror.shadowFill(false, 0.05, null, null, 0.05, 0.5, 0.0), 1e-9)
    }

    @Test fun aShadowStopIsReachedByAWickInsideTheMinuteAndFillsAtItsTriggerLessTheSlipNeverPastItsLimit() {
        // The price back above the stop now, but the minute's low went through it: the resting SL would have filled.
        assertTrue(LiveMirror.shadowStopReached(175.0, 168.0, 170.0, 1))
        assertFalse(LiveMirror.shadowStopReached(175.0, 171.0, 170.0, 1))
        assertFalse(LiveMirror.shadowStopReached(175.0, null, 170.0, 1))
        assertTrue(LiveMirror.shadowStopReached(169.0, null, 170.0, 1))
        assertFalse(LiveMirror.shadowStopReached(null, null, 170.0, 1))
        assertFalse(LiveMirror.shadowStopReached(160.0, 150.0, null, 1))
        // A short's stop above: its high.
        assertTrue(LiveMirror.shadowStopReached(95.0, 101.0, 100.0, -1))
        assertFalse(LiveMirror.shadowStopReached(95.0, 99.0, 100.0, -1))
        // Its fill: the trigger less the slip, on the tick against the order, never past the SL's limit (161.50 for 170).
        assertEquals(169.7, LiveMirror.shadowStopFill(170.0, 0.05, 1, 0.0016), 1e-9)
        assertEquals(170.0, LiveMirror.shadowStopFill(170.0, 0.05, 1, 0.0), 1e-9)
        assertEquals(161.5, LiveMirror.shadowStopFill(170.0, 0.05, 1, 0.2), 1e-9)
        assertEquals(170.0, LiveMirror.shadowStopFill(170.0, 0.05, 1, Double.NaN), 1e-9)
        // A short buys back above its trigger, never past its limit (105 for 100).
        assertEquals(100.2, LiveMirror.shadowStopFill(100.0, 0.05, -1, 0.0016), 1e-9)
        assertEquals(105.0, LiveMirror.shadowStopFill(100.0, 0.05, -1, 0.3), 1e-9)
        assertTrue(LiveMirror.SHADOW_BASIS.contains("ask") && LiveMirror.SHADOW_BASIS.contains("bid") && LiveMirror.SHADOW_BASIS.contains("charges"))
    }
}
