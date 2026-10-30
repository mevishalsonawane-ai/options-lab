package com.optionslab.ira

import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.mcx.McxMorningRules
import com.optionslab.engine.mcx.McxTrendRules
import com.optionslab.engine.mcx.McxUsSilverRules
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Battery, 14 Oct: the evening MCX watch, the recorders and Jarvis's ears rest when nothing needs them. */
class BatteryRestTest {
    private fun m(h: Int, min: Int) = h * 60 + min
    private val all = listOf(McxEveRules.SOURCE, McxMorningRules.SOURCE, McxTrendRules.SOURCE, McxUsSilverRules.SOURCE)

    // ---- the MCX watch ---------------------------------------------------------------------------------------------------

    @Test fun anOpenPositionPaperOrLiveKeepsThe15SecondPassAllEvening() {
        for (minute in listOf(m(15, 41), m(17, 30), m(20, 0), m(23, 29))) {
            val inWin = McxRest.inEntryMinutes(all, minute)
            assertEquals(15_000L, McxRest.cadenceMs(McxRest.held(paper = true, live = false, arms = false), inWin), "paper at $minute")
            assertEquals(15_000L, McxRest.cadenceMs(McxRest.held(paper = false, live = true, arms = false), inWin), "live at $minute")
            assertEquals(15_000L, McxRest.cadenceMs(McxRest.held(paper = false, live = false, arms = true), inWin), "arm's book at $minute")
        }
        assertEquals(15_000L, McxRest.cadenceMs(held = true, inEntryMinutes = false))
    }

    @Test fun nothingHeldOutsideTheArmsEntryMinutesIsOnceAMinute() {
        val armed = all
        // 16:30 (between the morning call and the evening break), 22:30 (after the break's last entry), 23:20.
        for (minute in listOf(m(15, 41), m(16, 30), m(18, 30), m(22, 30), m(23, 20))) {
            assertFalse(McxRest.inEntryMinutes(armed, minute), "minute $minute")
            assertEquals(60_000L, McxRest.cadenceMs(McxRest.held(false, false, false), McxRest.inEntryMinutes(armed, minute)))
        }
        // No arm switched on: never an entry window.
        assertFalse(McxRest.inEntryMinutes(emptyList(), m(20, 0)))
        assertEquals(60_000L, McxRest.cadenceMs(false, McxRest.inEntryMinutes(emptyList(), m(20, 0))))
    }

    @Test fun eachArmsEntryMinutesKeepThe15SecondPassWithALead() {
        // NG evening breakout: the 19:00-22:00 break (from 18:58) and its 3-minute grace (to 22:03).
        assertTrue(McxRest.inEntryMinutes(listOf(McxEveRules.SOURCE), m(18, 58)))
        assertTrue(McxRest.inEntryMinutes(listOf(McxEveRules.SOURCE), m(22, 3)))
        assertFalse(McxRest.inEntryMinutes(listOf(McxEveRules.SOURCE), m(18, 57)))
        assertFalse(McxRest.inEntryMinutes(listOf(McxEveRules.SOURCE), m(22, 4)))
        // SILVERM morning call: the 09:15-13:45 grid.
        assertTrue(McxRest.inEntryMinutes(listOf(McxMorningRules.SOURCE), m(9, 13)))
        assertTrue(McxRest.inEntryMinutes(listOf(McxMorningRules.SOURCE), m(13, 48)))
        assertFalse(McxRest.inEntryMinutes(listOf(McxMorningRules.SOURCE), m(13, 49)))
        // US-night silver: 09:05 and its grace.
        assertTrue(McxRest.inEntryMinutes(listOf(McxUsSilverRules.SOURCE), m(9, 3)))
        assertTrue(McxRest.inEntryMinutes(listOf(McxUsSilverRules.SOURCE), m(9, 10)))
        assertFalse(McxRest.inEntryMinutes(listOf(McxUsSilverRules.SOURCE), m(9, 11)))
        // The trend rule trades at the open.
        assertTrue(McxRest.inEntryMinutes(listOf(McxTrendRules.SOURCE), m(9, 0)))
        assertFalse(McxRest.inEntryMinutes(listOf(McxTrendRules.SOURCE), m(9, 31)))
        // An arm that is not switched on gives no window; an unknown arm none either.
        assertFalse(McxRest.inEntryMinutes(listOf(McxMorningRules.SOURCE), m(20, 0)))
        assertTrue(McxRest.entryMinutes("nope").isEmpty())
        assertEquals(15_000L, McxRest.cadenceMs(false, McxRest.inEntryMinutes(listOf(McxEveRules.SOURCE), m(20, 0))))
    }

    @Test fun theBrainTheStreamAndTheOrderFlowFollowMcxOnlyWhileHeldOrInAWindow() {
        assertTrue(McxRest.brainCounts(mcxOpen = true, held = true))
        assertFalse(McxRest.brainCounts(mcxOpen = true, held = false))
        assertFalse(McxRest.brainCounts(mcxOpen = false, held = true))
        assertTrue(McxRest.brainCounts(mcxOpen = true, held = false, inEntryMinutes = true), "an arm's entry minutes: the brake stays fresh")
        assertTrue(McxRest.keepsStream(true))
        assertFalse(McxRest.keepsStream(false))
        val eve = McxEveRules.SOURCE
        assertTrue(McxRest.followFutures(eve, armed = true, held = false, minute = m(20, 0), mcxOpen = true), "inside the break window")
        assertTrue(McxRest.followFutures(eve, armed = true, held = false, minute = m(18, 50), mcxOpen = true), "ten minutes ahead: warm at 19:00")
        assertFalse(McxRest.followFutures(eve, armed = true, held = false, minute = m(18, 49), mcxOpen = true))
        assertFalse(McxRest.followFutures(eve, armed = true, held = false, minute = m(22, 30), mcxOpen = true), "after it, nothing held")
        assertTrue(McxRest.followFutures(eve, armed = true, held = true, minute = m(22, 30), mcxOpen = true), "held: all evening")
        assertFalse(McxRest.followFutures(eve, armed = false, held = true, minute = m(20, 0), mcxOpen = true), "not armed")
        assertFalse(McxRest.followFutures(eve, armed = true, held = true, minute = m(20, 0), mcxOpen = false), "MCX shut")
    }

    // ---- the calendar ----------------------------------------------------------------------------------------------------

    private val holiday = LocalDate.of(2026, 10, 20)        // a weekday holiday in this test's calendar
    private fun trading(d: LocalDate) = d.dayOfWeek.value <= 5 && d != holiday
    private val close: (LocalDate) -> Int = { 15 * 60 + 40 }
    private fun at(s: String) = LocalDateTime.parse(s)

    @Test fun theNextOpenIsOneWaitAndZeroInHours() {
        // Inside the session: no wait.
        assertEquals(0L, RestClock.untilOpenMs(at("2026-10-14T10:00"), 555, close, ::trading))
        // Evening: tomorrow 09:15 is 17 h 15 min away, capped at three hours.
        assertEquals(RestClock.CAP_MS, RestClock.untilOpenMs(at("2026-10-14T16:00"), 555, close, ::trading))
        assertEquals(17 * 3_600_000L + 15 * 60_000L, RestClock.untilOpenMs(at("2026-10-14T16:00"), 555, close, ::trading, capMs = Long.MAX_VALUE))
        // Before the open the same morning, with a minute's lead.
        assertEquals(14 * 60_000L, RestClock.untilOpenMs(at("2026-10-14T09:00"), 555, close, ::trading, leadMs = 60_000L))
        // At the close minute: closed.
        assertTrue(RestClock.untilOpenMs(at("2026-10-14T15:40"), 555, close, ::trading) > 0)
        // Friday evening skips the weekend; Monday 19 Oct then the holiday on Tuesday 20 Oct is skipped too.
        assertEquals(63 * 3_600_000L + 15 * 60_000L,
            RestClock.untilOpenMs(at("2026-10-16T18:00"), 555, close, ::trading, capMs = Long.MAX_VALUE))
        assertEquals(41 * 3_600_000L + 15 * 60_000L,
            RestClock.untilOpenMs(at("2026-10-19T16:00"), 555, close, ::trading, capMs = Long.MAX_VALUE))
        // On the holiday itself, at 10:00: not open, the next session is the 21st.
        assertEquals(23 * 3_600_000L + 15 * 60_000L,
            RestClock.untilOpenMs(at("2026-10-20T10:00"), 555, close, ::trading, capMs = Long.MAX_VALUE))
        // A long rest goes in one-minute steps (the wait's clock stops in deep sleep).
        assertEquals(60_000L, RestClock.step(RestClock.CAP_MS))
        assertEquals(5_000L, RestClock.step(5_000L))
        assertEquals(0L, RestClock.step(-1L))
        // A calendar with no session at all: the cap.
        assertEquals(RestClock.CAP_MS, RestClock.untilOpenMs(at("2026-10-14T16:00"), 555, close, { false }))
    }

    // ---- the tick recorder -------------------------------------------------------------------------------------------------

    @Test fun batterySaverNarrowsTheRecordingWithoutStoppingIt() {
        assertTrue(TickRest.narrow(powerSave = true, percent = 90, charging = true))
        assertTrue(TickRest.narrow(powerSave = false, percent = 15, charging = false), "low and not charging")
        assertFalse(TickRest.narrow(powerSave = false, percent = 15, charging = true), "low but charging")
        assertFalse(TickRest.narrow(powerSave = false, percent = null, charging = false), "unknown")
        assertFalse(TickRest.narrow(powerSave = false, percent = 80, charging = false))
        assertEquals(1, TickRest.eachSide(true))
        assertEquals(TickZone.EACH_SIDE, TickRest.eachSide(false))
        // ATM±1 still records the futures, three strikes a side shrink to one: 3 strikes x 2 rights per index.
        val strikes = (0..20).map { 50_000.0 + it * 100 }
        fun opts(ix: String) = strikes.flatMapIndexed { i, k ->
            listOf(TickZone.Inst(1000L + i * 2, "$ix$k CE", ix, TickZone.Kind.CE, k, LocalDate.of(2026, 10, 14)),
                TickZone.Inst(1001L + i * 2, "$ix$k PE", ix, TickZone.Kind.PE, k, LocalDate.of(2026, 10, 14)))
        }
        val fut = mapOf("BANKNIFTY" to TickZone.Inst(1L, "BNF FUT", "BANKNIFTY", TickZone.Kind.FUT))
        val narrow = TickZone.watchList(fut, mapOf("BANKNIFTY" to opts("BANKNIFTY")), mapOf("BANKNIFTY" to 51_020.0), null, TickRest.eachSide(true))
        assertEquals(1 + 3 * 2, narrow.size)
        assertEquals(TickZone.Kind.FUT, narrow.first().kind)
        val wide = TickZone.watchList(fut, mapOf("BANKNIFTY" to opts("BANKNIFTY")), mapOf("BANKNIFTY" to 51_020.0), null, TickRest.eachSide(false))
        assertEquals(1 + 7 * 2, wide.size)
    }

    @Test fun theNarrowingIsWrittenInTheDiaryOnceEachWay() {
        assertNull(TickRest.narrowNote(null, false), "the first look, not narrowed: nothing to say")
        assertTrue(TickRest.narrowNote(null, true)!!.contains("ATM±1"))
        assertTrue(TickRest.narrowNote(false, true)!!.startsWith("battery saver:"))
        assertTrue(TickRest.narrowNote(true, false)!!.contains("ATM±3 again"))
        assertNull(TickRest.narrowNote(true, true))
        assertNull(TickRest.narrowNote(false, false))
    }

    @Test fun theLayoutIsRedoneOnlyWhenTheMoneyOrTheDayMoves() {
        val d = LocalDate.of(2026, 10, 14)
        val strikes = listOf(51_000.0, 51_100.0, 51_200.0)
        val k1 = TickRest.Key(d, mapOf("BANKNIFTY" to TickRest.atm(strikes, 51_040.0)), 3, listOf(1L))
        assertTrue(TickRest.relayout(null, k1), "the first")
        val sameStrike = k1.copy(atm = mapOf("BANKNIFTY" to TickRest.atm(strikes, 51_020.0)))
        assertFalse(TickRest.relayout(k1, sameStrike), "the index moved inside the strike")
        assertTrue(TickRest.relayout(k1, k1.copy(atm = mapOf("BANKNIFTY" to TickRest.atm(strikes, 51_160.0)))), "a strike up")
        assertTrue(TickRest.relayout(k1, k1.copy(day = d.plusDays(1))), "a new day")
        assertTrue(TickRest.relayout(k1, k1.copy(each = 1)), "battery saver")
        assertTrue(TickRest.relayout(k1, k1.copy(tokens = listOf(2L))), "the futures rolled")
        assertNull(TickRest.atm(strikes, null), "no price")
        assertNull(TickRest.atm(strikes, 0.0), "a zero price")
        assertNull(TickRest.atm(emptyList(), 51_000.0), "no strikes")
        assertEquals(60_000L, TickRest.DRAIN_MS)
        assertTrue(TickRest.DRAIN_MS / 1000 < TickZone.QUEUE_CAP, "the queue holds well over one drain's seconds: the rows are the same")
        // Memory (14 Oct): 90 seconds (was 600) - one drain and half a minute to spare.
        assertEquals(90, TickZone.QUEUE_CAP)
        assertTrue(TickZone.QUEUE_CAP - TickRest.DRAIN_MS / 1000 >= 30)
    }

    // ---- the market context --------------------------------------------------------------------------------------------------

    @Test fun theContextRefreshesEachMinuteInHoursAndThreeTimesOutside() {
        assertEquals(60_000L, ContextPace.nextMs(at("2026-10-14T10:00"), tradingDay = true))
        assertEquals(60_000L, ContextPace.nextMs(at("2026-10-14T08:45"), tradingDay = true))
        // After 15:45 the next is the evening one at 18:00.
        assertEquals(2 * 3_600_000L + 15 * 60_000L, ContextPace.nextMs(at("2026-10-14T15:45"), tradingDay = true))
        // After 18:00, just past midnight (00:01), capped at three hours.
        assertEquals(RestClock.CAP_MS, ContextPace.nextMs(at("2026-10-14T18:00"), tradingDay = true))
        assertEquals(2 * 60_000L, ContextPace.nextMs(at("2026-10-14T23:59"), tradingDay = true), "to 00:01")
        // A holiday or weekend: no minute refresh in the day, only the anchors (08:45 then 18:00).
        assertEquals(3_600_000L + 45 * 60_000L, ContextPace.nextMs(at("2026-10-17T07:00"), tradingDay = false))
        assertEquals(RestClock.CAP_MS, ContextPace.nextMs(at("2026-10-17T10:00"), tradingDay = false))
        // Never a zero wait.
        assertTrue(ContextPace.nextMs(at("2026-10-17T17:59:59.999"), tradingDay = false) >= 1_000L)
    }

    // ---- Jarvis's wake word ----------------------------------------------------------------------------------------------------

    @Test fun listenForJarvisDefaultsToAlwaysAndEachChoiceDoesWhatItSays() {
        assertEquals(ListenWhen.Mode.ALWAYS, ListenWhen.DEFAULT)
        assertEquals(ListenWhen.Mode.ALWAYS, ListenWhen.parse(null))
        assertEquals(ListenWhen.Mode.ALWAYS, ListenWhen.parse("garbage"))
        assertEquals(ListenWhen.Mode.MARKET_HOURS, ListenWhen.parse("MARKET_HOURS"))
        for (u in listOf(true, false)) for (c in listOf(true, false)) for (mh in listOf(true, false))
            assertTrue(ListenWhen.allowed(ListenWhen.Mode.ALWAYS, u, c, mh), "always is always")
        val uc = ListenWhen.Mode.UNLOCKED_OR_CHARGING
        assertTrue(ListenWhen.allowed(uc, unlocked = true, charging = false, marketHours = false))
        assertTrue(ListenWhen.allowed(uc, unlocked = false, charging = true, marketHours = false))
        assertFalse(ListenWhen.allowed(uc, unlocked = false, charging = false, marketHours = true), "locked on battery rests")
        val mh = ListenWhen.Mode.MARKET_HOURS
        assertTrue(ListenWhen.allowed(mh, unlocked = false, charging = false, marketHours = true))
        assertFalse(ListenWhen.allowed(mh, unlocked = true, charging = true, marketHours = false))
        assertTrue(ListenWhen.marketHours(true, m(9, 0), 940))
        assertTrue(ListenWhen.marketHours(true, m(15, 50), 940))
        assertFalse(ListenWhen.marketHours(true, m(15, 51), 940))
        assertFalse(ListenWhen.marketHours(true, m(8, 59), 940))
        assertFalse(ListenWhen.marketHours(false, m(11, 0), 940), "a holiday")
        assertEquals(0L, ListenWhen.recheckMs(ListenWhen.Mode.ALWAYS, null))
        assertEquals(5 * 60_000L, ListenWhen.recheckMs(uc, null))
        assertEquals(5 * 60_000L, ListenWhen.recheckMs(mh, 10 * 3_600_000L), "at most five minutes")
        assertEquals(30_000L, ListenWhen.recheckMs(mh, 1_000L), "never a busy loop")
        assertEquals(5 * 60_000L, ListenWhen.recheckMs(mh, null))
        assertTrue(ListenWhen.Mode.entries.all { it.label.isNotBlank() && it.what.isNotBlank() })
    }

    // ---- What's new --------------------------------------------------------------------------------------------------------

    @Test fun whatsNewSaysItInPlainEnglishFirst() {
        // 14 Oct: the gamma line's entry, the signal-on-the-index entry, then the memory fix's, sit above it (the same day,
        // after it); the battery's is the one right below them. The SILVERM morning call's switch-off (14 Oct, R42) is newest.
        // (15 Oct: the Hero ₹1,000 ticket's entry, then the passed arms' and the Watchlist's, sit above them all; the market concepts' and 16 Oct's exits fix on top.)
        val o = WhatsNew.ENTRIES.indexOfFirst { it.id == "2026-10-14-silverm-morning-off" }
        // (16 Oct: the passed arms' exits fix and the Telegram login fix, the open trade change on top of those; R72 Breadth Donchian,
        // then R51's Supertrend filter newest.)
        // (17 Oct: the diagnostics fixes on top of all; 18 Oct: the whole-app review's fixes above them, then live-like-paper's, the live check's and the live arms'.)
        // (19 Oct: the paper order speed and same-direction cap entry on top of all, then R51's record only above it.)
        // (20 Oct: the Candle agent's entry, then the 19 Oct diagnostics fixes newest of all.)
        // (20 Oct: the whole-app review's entry, then enhancement round 1's, then round 2's; 21 Oct: round 3's, the commodities' own lock, then round 4's, then round 5's, then FT3's; 22 Oct: the Liquidity exit fixes, then Boss's breakeven switch, then the 22 Oct diagnostics fixes newest of all.)
        // (22 Oct: the arms audit's fixes, then the four ideas borrowed from the day-trader skill; 25 Oct: ntfy alerts, the app audit's fixes, then Boss's decisions; 26 Oct: ntfy acted on within seconds, then the erase of every store, then the Donchian paper fixes, then ntfy following the alert app's own exits, newest of all.)
        // (27 Oct: paper limit buys paying the ask, then the battery audit; 28 Oct: IraThinker's flow on paper, then the FINNIFTY book price,
        // then S5 Trail on paper, then the 65% win-rate rule for automatic ideas; 29 Oct: CISD + network on paper; 30 Oct: the index and
        // commodity stops apart, newest of all.)
        assertEquals(52, o)
        assertTrue(WhatsNew.ENTRIES.take(o).all { it.date > java.time.LocalDate.of(2026, 10, 14) })
        assertEquals("2026-10-14-gamma-line-oi-wall-shadow", WhatsNew.ENTRIES[o + 1].id)
        assertEquals("2026-10-14-signal-on-the-index", WhatsNew.ENTRIES[o + 2].id)
        assertEquals("2026-10-14-memory-positions-first", WhatsNew.ENTRIES[o + 3].id)
        val x = WhatsNew.ENTRIES[o + 4]
        assertEquals("2026-10-14-battery-rest", x.id)
        assertTrue(x.what.startsWith("Battery: the evening MCX watch, the order-flow recorder and other background work now rest when nothing needs them"), x.what)
        assertTrue("paper or live" in x.what && "Always stays the default" in x.what, x.what)
        assertTrue("Health" in x.where && "Listen for" in x.where, x.where)
        assertTrue(x.id in WhatsNew.unseen(WhatsNew.ENTRIES, WhatsNew.decode(null)).map { it.id })
    }

    // ---- the meter ----------------------------------------------------------------------------------------------------------

    @Test fun theMeterCountsEachLoopsMinutesAndStartsAgainEachDay() {
        var now = at("2026-10-14T15:40").atZone(MarketRecord.IST).toInstant().toEpochMilli()
        val meter = LoopMeter({ now }) { java.time.Instant.ofEpochMilli(it).atZone(MarketRecord.IST).toLocalDate() }
        meter.on(LoopMeter.WATCH_EVENING)
        meter.on(LoopMeter.WATCH_EVENING)                // a second on keeps the first start
        now += 30 * 60_000L
        assertEquals(30, meter.minutes(LoopMeter.WATCH_EVENING), "running: counted so far")
        assertTrue(meter.running(LoopMeter.WATCH_EVENING))
        meter.off(LoopMeter.WATCH_EVENING)
        meter.off(LoopMeter.WATCH_EVENING)               // off twice: no double count
        now += 60 * 60_000L
        assertEquals(30, meter.minutes(LoopMeter.WATCH_EVENING))
        meter.add(LoopMeter.RECORDER, 5 * 60_000L)
        meter.add(LoopMeter.RECORDER, -1L)               // nonsense ignored
        meter.set(LoopMeter.STREAM, true)
        now += 10 * 60_000L
        meter.set(LoopMeter.STREAM, false)
        repeat(3) { meter.count(LoopMeter.MCX_SLOW) }
        meter.count(LoopMeter.MCX_FAST)
        val line = LoopMeter.line(meter)
        assertTrue(line.startsWith("Battery today: watch after 15:40 30 min (MCX passes: 1 at 15 s, 3 at 60 s)"), line)
        assertTrue(line.contains("tick recorder 5 min"), line)
        assertTrue(line.contains("stream on 10 min"), line)
        assertTrue(line.contains("order-flow pump 0 min"), line)
        // Across midnight: yesterday's figures go; a loop still running counts only from 00:00.
        meter.on(LoopMeter.ORDER_FLOW)
        now = at("2026-10-15T00:20").atZone(MarketRecord.IST).toInstant().toEpochMilli()
        assertEquals(0, meter.minutes(LoopMeter.WATCH_EVENING))
        assertEquals(0, meter.counted(LoopMeter.MCX_SLOW))
        assertEquals(20, meter.minutes(LoopMeter.ORDER_FLOW))
        assertFalse(meter.running(LoopMeter.RECORDER))
    }

    // ---- the battery audit, 27 Oct: the knowledge/words-only analysis --------------------------------------------------------

    @Test fun anythingHeldKeepsFullPaceWithTheSaverOnAndTheBatteryAt10Percent() {
        for (screen in listOf(true, false)) for (saver in listOf(true, false)) for (pct in listOf(10, 15, 30, null, 90))
            for (charging in listOf(true, false))
                assertEquals(AnalysisRest.Pace.FULL, AnalysisRest.pace(held = true, screenOn = screen, saver = saver, percent = pct, charging = charging),
                    "held, screen $screen saver $saver $pct% charging $charging")
        // And at FULL every worker keeps its normal wait.
        assertEquals(60_000L, AnalysisRest.wordsMs(AnalysisRest.Pace.FULL, 60_000L))
        assertEquals(300_000L, AnalysisRest.candleMs(AnalysisRest.Pace.FULL, 300_000L))
        assertTrue(AnalysisRest.flowFollows(AnalysisRest.Pace.FULL))
    }

    @Test fun nothingHeldTheAnalysisRestsOnALowBatteryOrTheSaverAndSlowsWithTheScreenOff() {
        // Low: 30% or less, not charging.
        assertTrue(AnalysisRest.low(30, false)); assertTrue(AnalysisRest.low(10, false))
        assertFalse(AnalysisRest.low(31, false)); assertFalse(AnalysisRest.low(10, true)); assertFalse(AnalysisRest.low(null, false))
        assertEquals(AnalysisRest.Pace.REST, AnalysisRest.pace(false, screenOn = true, saver = false, percent = 25, charging = false))
        assertEquals(AnalysisRest.Pace.REST, AnalysisRest.pace(false, screenOn = true, saver = true, percent = 90, charging = false))
        assertEquals(AnalysisRest.Pace.REST, AnalysisRest.pace(false, screenOn = false, saver = true, percent = 90, charging = true), "the saver wins even on the charger")
        assertEquals(AnalysisRest.Pace.SLOW, AnalysisRest.pace(false, screenOn = false, saver = false, percent = 80, charging = false))
        assertEquals(AnalysisRest.Pace.FULL, AnalysisRest.pace(false, screenOn = false, saver = false, percent = 80, charging = true), "on the charger")
        assertEquals(AnalysisRest.Pace.FULL, AnalysisRest.pace(false, screenOn = true, saver = false, percent = 80, charging = false))
        assertEquals(AnalysisRest.Pace.FULL, AnalysisRest.pace(false, screenOn = true, saver = false, percent = null, charging = false), "unknown battery")
        // What each pace means.
        assertEquals(120_000L, AnalysisRest.wordsMs(AnalysisRest.Pace.SLOW, 60_000L))
        assertEquals(AnalysisRest.REST_WORDS_MS, AnalysisRest.wordsMs(AnalysisRest.Pace.REST, 60_000L))
        assertEquals(AnalysisRest.REST_WORDS_MS, AnalysisRest.wordsMs(AnalysisRest.Pace.REST, 120_000L))
        assertEquals(600_000L, AnalysisRest.candleMs(AnalysisRest.Pace.SLOW, 300_000L))
        assertNull(AnalysisRest.candleMs(AnalysisRest.Pace.REST, 300_000L), "the Candle agent rests")
        assertFalse(AnalysisRest.flowFollows(AnalysisRest.Pace.REST))
        assertTrue(AnalysisRest.flowFollows(AnalysisRest.Pace.SLOW))
    }

    @Test fun theCandleAgentOnMcxsEveningOnlyWithSomethingHeldOrAnArmInItsEntryMinutes() {
        assertTrue(AnalysisRest.candlesOnMcxEvening(mcxHeld = true, inEntryMinutes = false), "a US-night silver short held to 23:20")
        assertTrue(AnalysisRest.candlesOnMcxEvening(mcxHeld = false, inEntryMinutes = true))
        assertFalse(AnalysisRest.candlesOnMcxEvening(mcxHeld = false, inEntryMinutes = false))
        // The MCX watch itself is untouched by this: held on MCX keeps its 15 s pass all evening, battery or not.
        for (minute in listOf(m(17, 0), m(20, 0), m(23, 20)))
            assertEquals(McxRest.HELD_MS, McxRest.cadenceMs(held = true, inEntryMinutes = McxRest.inEntryMinutes(all, minute)))
    }

    @Test fun theRecorderNarrowsAt30PercentNow() {
        assertTrue(TickRest.narrow(powerSave = false, percent = 30, charging = false))
        assertTrue(TickRest.narrow(powerSave = false, percent = 25, charging = false))
        assertFalse(TickRest.narrow(powerSave = false, percent = 31, charging = false))
        assertFalse(TickRest.narrow(powerSave = false, percent = 25, charging = true))
    }

    @Test fun thePowerCacheAsksAndroidAtMostOncePerTtlAndKeepsTheLastValueOnAFailure() {
        var now = 0L
        var value = 50
        var fail = false
        val c = PowerCache(30_000L, { now }) { if (fail) error("binder died") else value }
        repeat(100) { assertEquals(50, c.get()); now += 100 }      // 10 s of reads every 100 ms
        assertEquals(1, c.reads)
        value = 40; now = 30_000L
        assertEquals(40, c.get()); assertEquals(2, c.reads)
        fail = true; now = 61_000L
        assertEquals(40, c.get(), "a failed read keeps the last value")
        c.clear(); fail = false; value = 35
        assertEquals(35, c.get(), "cleared: read again at once")
        // A clock that went back reads again (never a value from the future kept for ever).
        now = 0L
        assertEquals(35, c.get()); assertEquals(5, c.reads)
        // The very first read failing has nothing to keep: the caller sees the failure (and its own fallback).
        val bad = PowerCache(30_000L, { 0L }) { error("no") }
        assertTrue(runCatching { bad.get() }.isFailure)
    }

    @Test fun theMetersFiguresSurviveARestartAndTheDayBeforesLineIsKeptPastMidnight() {
        var now = at("2026-10-26T20:00").atZone(MarketRecord.IST).toInstant().toEpochMilli()
        val dayOf = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(MarketRecord.IST).toLocalDate() }
        val first = LoopMeter({ now }, dayOf)
        first.on(LoopMeter.STREAM)
        repeat(4) { first.count(LoopMeter.CANDLE_BATCHES) }
        repeat(2) { first.count(LoopMeter.RECONNECTS) }
        now += 30 * 60_000L
        val saved = LoopMeter.encode(first.figuresFor(LocalDate.of(2026, 10, 26))!!)
        assertEquals(LocalDate.of(2026, 10, 26), LoopMeter.decode(saved)!!.day)
        // A new process (an hourly night job, a low-memory kill): the saved figures are added in.
        now += 10 * 60_000L
        val second = LoopMeter({ now }, dayOf)
        second.restore(LoopMeter.decode(saved)!!)
        second.count(LoopMeter.CANDLE_BATCHES)
        assertEquals(30, second.minutes(LoopMeter.STREAM))
        assertEquals(5, second.counted(LoopMeter.CANDLE_BATCHES))
        val line = LoopMeter.line(second)
        assertTrue(line.contains("stream on 30 min (2 reconnects)"), line)
        assertTrue(line.contains("Candle agent 5 batches"), line)
        // Past midnight the day before's line is still there for the catch-up upload; today starts from zero.
        second.on(LoopMeter.ORDER_FLOW)
        now = at("2026-10-27T02:26").atZone(MarketRecord.IST).toInstant().toEpochMilli()
        val y = second.lineFor(LocalDate.of(2026, 10, 26))!!
        assertTrue(y.contains("Candle agent 5 batches"), y)
        assertTrue(y.contains("order-flow pump 200 min"), "running 20:40 to midnight counted to midnight: $y")
        assertEquals(146, second.minutes(LoopMeter.ORDER_FLOW), "and today's part from 00:00")
        assertEquals(0, second.counted(LoopMeter.CANDLE_BATCHES))
        assertNull(second.lineFor(LocalDate.of(2026, 10, 24)), "an older day is not kept")
        // A new process after midnight restores the day before as the day before.
        val third = LoopMeter({ now }, dayOf)
        third.restore(LoopMeter.decode(saved)!!)
        assertTrue(third.lineFor(LocalDate.of(2026, 10, 26))!!.contains("Candle agent 4 batches"))
        assertEquals(0, third.counted(LoopMeter.CANDLE_BATCHES), "not added into today")
        // Older days and broken lines are ignored.
        third.restore(LoopMeter.Figures(LocalDate.of(2026, 10, 20), mapOf(LoopMeter.STREAM to 99L), emptyMap()))
        assertNull(LoopMeter.decode("not a line"))
        assertNull(LoopMeter.decode("2026-13-40|m:x=1"))
        assertEquals(mapOf(LoopMeter.STREAM to 5L), LoopMeter.decode("2026-10-26|m:${LoopMeter.STREAM}=5;c:bad=x;junk")!!.ms)
    }

    @Test fun theBatteryLineHasNoPersonalDataOnlyLoopNamesMinutesAndCounts() {
        val f = LoopMeter.Figures(LocalDate.of(2026, 10, 26), mapOf(LoopMeter.STREAM to 196 * 60_000L, LoopMeter.LOW_BATTERY to 45 * 60_000L),
            mapOf(LoopMeter.WORDS_ROUNDS to 194))
        val line = f.line()
        assertTrue(line.startsWith("Battery today: "), line)
        assertTrue(line.contains("stream on 196 min") && line.contains("Jarvis words 194 rounds") && line.contains("battery 30% or less 45 min"), line)
        assertFalse(line.contains("₹") || line.contains("@"), line)
    }
}
