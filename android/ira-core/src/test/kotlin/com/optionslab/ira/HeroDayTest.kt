package com.optionslab.ira

import com.optionslab.engine.orb.HeroRules
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeroDayTest {
    /** Tue 06 Oct 2026: a NIFTY expiry in these tests; the next ones a week apart. */
    private val day: LocalDate = LocalDate.of(2026, 10, 6)
    private val expiries = listOf(day, day.plusDays(7), day.plusDays(14))
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)

    private fun facts(now: LocalDateTime, armed: Boolean? = true, decisions: List<HeroDay.Decision> = emptyList(),
                      today: List<HeroDay.Trade> = emptyList(), ex: List<LocalDate> = expiries, record: ForwardCheck.Result? = ForwardCheck.check(ForwardCheck.HERO, emptyList()),
                      status: String? = "", tradingDay: Boolean = true, since: LocalDateTime? = at(9, 0), allTime: Pair<Int, Double>? = 0 to 0.0) =
        HeroDay.Facts(now, tradingDay, ex, armed, status, decisions, since, today, record, allTime)

    private fun read(h: Int, m: Int, verdict: String, stExp: Double = 0.08, mom: Double = 0.001, side: Int = 0) =
        HeroDay.Decision(at(h, m).plusSeconds(5), verdict, LocalTime.of(h, m), spot = 24_812.4, atm = 24_800, ce = 9.2, pe = 9.3,
            straddle = 18.5, low = 18.5 / (1 + stExp), stExp = stExp, mom = mom, side = side)

    private val WHAT = HeroDay.Q(HeroDay.Kind.WHAT)
    private val WHY = HeroDay.Q(HeroDay.Kind.WHY)
    private val DAY = HeroDay.Q(HeroDay.Kind.DAY)
    private val NEXT = HeroDay.Q(HeroDay.Kind.NEXT)

    // ---- the question ---------------------------------------------------------------------------------------------

    @Test fun theQuestionsItTakes() {
        val kinds = mapOf(
            "what did hero do today" to HeroDay.Kind.WHAT, "what has hero done today" to HeroDay.Kind.WHAT, "hero ne aaj kya kiya" to HeroDay.Kind.WHAT,
            "hero ne kya kiya" to HeroDay.Kind.WHAT, "how did hero decide" to HeroDay.Kind.WHAT, "what is hero waiting for" to HeroDay.Kind.WHAT,
            "did hero trade today" to HeroDay.Kind.WHAT, "is hero trading today" to HeroDay.Kind.WHAT, "will hero trade today" to HeroDay.Kind.WHAT,
            "hero kab trade karega" to HeroDay.Kind.WHAT, "what did hero see" to HeroDay.Kind.WHAT, "did the hero arm fire today" to HeroDay.Kind.WHAT,
            "What did Hero do today?" to HeroDay.Kind.WHAT,
            "why no hero trade" to HeroDay.Kind.WHY, "why no hero trade today" to HeroDay.Kind.WHY, "why didn't hero trade" to HeroDay.Kind.WHY,
            "why hasn't hero traded today" to HeroDay.Kind.WHY, "hero ne trade kyu nahi liya" to HeroDay.Kind.WHY, "why didn't hero fire today" to HeroDay.Kind.WHY,
            "is today a hero day" to HeroDay.Kind.DAY, "is this a hero day" to HeroDay.Kind.DAY, "aaj hero day hai kya" to HeroDay.Kind.DAY,
            "is today an expiry day for hero" to HeroDay.Kind.DAY, "is it hero's expiry today" to HeroDay.Kind.DAY,
            "when is the next hero day" to HeroDay.Kind.NEXT, "next hero day kab hai" to HeroDay.Kind.NEXT, "agla hero day kab hai" to HeroDay.Kind.NEXT,
            "when is hero's next day" to HeroDay.Kind.NEXT)
        for ((s, k) in kinds) assertEquals(k, HeroDay.asked(s)?.kind, s)
    }

    @Test fun neverItsStatusSwitchSizeRecordExitsAnotherDayOrTheStock() {
        for (s in listOf("hero status", "hero arm status", "how is hero doing", "how did hero do today", "is hero on", "is hero armed",
            "hero on", "hero off", "switch on hero", "switch off hero", "stop hero", "start hero", "hero band karo", "hero chalu karo",
            "hero lots", "set hero to 2 lots", "how many lots does hero buy", "hero budget", "what is hero's budget", "what is hero",
            "explain the hero arm", "hero record", "hero paper record", "hero forward test", "hero net so far", "how much has hero made",
            "hero trades today", "why did hero exit", "what are hero's exits", "hero ki exits kya hai", "hero expiry", "is hero live",
            "why didn't hero trade yesterday", "what did hero do last week", "why didn't hero take that trade", "is tomorrow a hero day",
            "hero ka straddle kitna hai", "hero motocorp news", "how is hero motocorp doing", "what did hero motocorp do today",
            "why is hero motocorp share not trading", "is today expiry", "expiry today", "when is the next expiry", "what did solo do today",
            "why didn't liquidity trade", "hero ne kitna kamaya", "why did hero lose today"))
            assertNull(HeroDay.asked(s), s)
    }

    @Test fun neverAnOrderOrACommand() {
        for (s in listOf("what did hero do today", "why no hero trade", "is today a hero day", "hero ne aaj kya kiya", "when is the next hero day")) {
            val p = Ask.parse(s)
            assertNull(p.order, s); assertNull(p.command, s)
        }
    }

    // ---- the day ----------------------------------------------------------------------------------------------------

    @Test fun anExpiryDayIsSaidWithItsWindowAndRule() {
        val a = HeroDay.answer(DAY, facts(at(10, 0)))
        assertTrue(a.startsWith("Today, Tue 6 Oct, is a NIFTY expiry day (today's contracts expire) - Hero's kind of day, Boss."), a)
        assertTrue("It is armed: paper only, fully automatic." in a, a)
        assertTrue("entries 13:30-14:45, everything out by 15:05" in a, a)
        assertTrue(HeroDay.RULE in a, a)
        assertTrue("It has nothing to decide before 13:30." in a, a)
        assertTrue(HeroDay.UNPROVEN in a, a)
    }

    @Test fun notAnExpiryDayNamesTheNextOne() {
        val tue = day.plusDays(1)
        val a = HeroDay.answer(DAY, facts(tue.atTime(11, 0), ex = expiries.drop(1)))
        assertTrue(a.startsWith("Today is not a NIFTY expiry day, Boss, so Hero sits it out. Its next day is Tue 13 Oct (in 6 days)"), a)
        assertTrue("It trades only on NIFTY expiry days: " in a, a)
        val tomorrow = HeroDay.answer(NEXT, facts(day.plusDays(6).atTime(11, 0), ex = expiries.drop(1)))
        assertTrue("Its next day is Tue 13 Oct (tomorrow)" in tomorrow, tomorrow)
        assertFalse("It trades only on NIFTY expiry days" in tomorrow, tomorrow)
    }

    @Test fun theNextDayAfterAnExpiryDay() {
        val a = HeroDay.answer(NEXT, facts(at(9, 30)))
        assertTrue(a.startsWith("Today, Tue 6 Oct, is a NIFTY expiry day - Hero's kind of day, Boss. After today, the next is Tue 13 Oct (in 7 days)."), a)
    }

    @Test fun noSessionOrNoList() {
        val sat = LocalDate.of(2026, 10, 10)
        val a = HeroDay.answer(NEXT, facts(sat.atTime(10, 0), tradingDay = false, ex = expiries.drop(1)))
        assertTrue(a.startsWith("No session today, Boss - Hero trades only on a NIFTY expiry day. The next one is Tue 13 Oct (in 3 days)."), a)
        val none = HeroDay.answer(WHAT, facts(at(14, 0), ex = emptyList(), decisions = listOf(HeroDay.Decision(at(9, 20), "hero_no_master"))))
        assertTrue(none.startsWith("The NIFTY contract list is not on the phone, so I cannot tell whether today is an expiry day"), none)
        assertTrue("Its last verdict today (09:20): the NIFTY contract list was not loaded, so no trade (never a guess)." in none, none)
        val last = HeroDay.answer(WHAT, facts(day.plusDays(15).atTime(10, 0)))
        assertTrue("no later NIFTY expiry is in the contract list" in last, last)
    }

    // ---- the switch -------------------------------------------------------------------------------------------------

    @Test fun theSwitch() {
        assertTrue("It is switched off, so it does not trade" in HeroDay.answer(WHAT, facts(at(14, 0), armed = false)))
        assertTrue("I could not read its switch just now." in HeroDay.answer(WHAT, facts(at(14, 0), armed = null)))
        val self = HeroDay.answer(WHAT, facts(at(14, 0), armed = false, status = "hero_disarmed: 12 losing firing days in a row"))
        assertTrue("It disarmed itself (12 losing firing days in a row) and stays off until you arm it again by hand." in self, self)
        val off = HeroDay.answer(WHY, facts(at(15, 0), armed = false))
        assertTrue("No trade today: it was not armed." in off, off)
        assertTrue("Switched off, it will not trade today." in HeroDay.answer(DAY, facts(at(12, 0), armed = false)))
    }

    // ---- what it read and decided ------------------------------------------------------------------------------------

    @Test fun noSignalTellsTheReadsAndTheClosestItCame() {
        val ds = listOf(HeroDay.Decision(at(9, 20), "hero_waiting_for_window"),
            read(13, 30, "hero_straddle_not_expanded", stExp = 0.05, mom = 0.0010),
            read(13, 31, "hero_straddle_not_expanded", stExp = 0.12, mom = -0.0031),
            read(14, 45, "hero_no_momentum", stExp = 0.16, mom = 0.0012),
            HeroDay.Decision(at(14, 46), "hero_window_closed"))
        val a = HeroDay.answer(WHY, facts(at(15, 10), decisions = ds))
        assertTrue("Its entry window (13:30-14:45) has closed" in a, a)
        assertTrue("Its last read (14:45): NIFTY 24,812.40, the ATM straddle at the 24800 strike ₹18.50 (CE ₹9.20 + PE ₹9.30) - its low since 12:00 ₹15.95, 16% above it (it needs 15%); NIFTY up 0.12% in 15 minutes (it needs 0.25% either way)." in a, a)
        assertTrue("It decided on 3 minutes (13:30-14:45): 2 - the straddle was not 15% above its low; 1 - the straddle had expanded but NIFTY had not moved 0.25% in 15 minutes." in a, a)
        assertTrue("No trade today: no signal - the straddle's 15% and NIFTY's 0.25% never lined up in the same minute." in a, a)
        assertTrue("The closest it came: the straddle at most 16% above its low (14:45; it needs 15%); NIFTY's biggest 15-minute move 0.31% down (13:31; it needs 0.25%) - both in the same minute fire it." in a, a)
        assertTrue("Paper record (F07 exits, since Tue 6 Oct): no closed trade yet." in a, a)
    }

    @Test fun inTheWindowItIsStillWatching() {
        val a = HeroDay.answer(WHAT, facts(at(13, 50), decisions = listOf(read(13, 49, "hero_straddle_not_expanded"))))
        assertTrue("It is inside its entry window (13:30-14:45), out by 15:05." in a, a)
        assertTrue("No signal yet: " in a, a)
        assertTrue(HeroDay.RULE in a, a)
    }

    @Test fun beforeItsWindow() {
        val a = HeroDay.answer(WHY, facts(at(12, 30), decisions = listOf(HeroDay.Decision(at(9, 20), "hero_waiting_for_window"))))
        assertTrue("It is tracking the straddle's low since 12:00; entries open at 13:30" in a, a)
        assertTrue("No trade yet: its window opens at 13:30." in a, a)
        val early = HeroDay.answer(WHAT, facts(at(9, 0)))
        assertTrue("Its window: the straddle's low counts from 12:00" in early, early)
    }

    @Test fun aSignalSetAsideSaysWhy() {
        val ds = listOf(read(13, 52, "hero_zero_lots", stExp = 0.22, mom = 0.0031, side = 1).copy(strike = 24_900.0, price = 4.8, limit = 4.9, lots = 0),
            read(13, 53, "hero_no_strike", stExp = 0.24, mom = -0.0030, side = -1))
        val a = HeroDay.answer(WHY, facts(at(14, 0), decisions = ds))
        assertTrue("At 13:52 it fired up (a call) - picked the 24,900 at ₹4.80, limit ₹4.90, 0 lots: it fired, but one lot cost more than its ₹5,000 budget." in a, a)
        assertTrue("At 13:53 it fired down (a put): it fired, but no OTM option priced ₹1-5 passed the checks" in a, a)
        assertTrue("No trade yet: each signal it fired was set aside, as above; a later minute may fire again until 14:45." in a, a)
        val after = HeroDay.answer(WHY, facts(at(15, 0), decisions = ds))
        assertTrue("No trade today: each signal it fired was set aside, as above." in after, after)
    }

    @Test fun staleDataAndGuards() {
        val stood = HeroDay.answer(WHY, facts(at(14, 30), decisions = listOf(read(13, 30, "hero_straddle_not_expanded"),
            HeroDay.Decision(at(13, 37), "hero_stood_down", LocalTime.of(13, 37)))))
        assertTrue("No trade today: at 13:37 it stood down for the day: more than 5 minutes in a row of stale NIFTY or straddle data (it never trades on stale data)." in stood, stood)
        val kill = HeroDay.answer(WHY, facts(at(14, 30), decisions = listOf(read(14, 1, "refused: the kill switch is on", side = 1))))
        assertTrue("No trade today: it fired, but the kill switch is on (from 14:01)." in kill, kill)
        val stop = HeroDay.answer(WHY, facts(at(14, 30), decisions = listOf(HeroDay.Decision(at(13, 30), "stopped_for_today"))))
        assertTrue("No trade today: the bot was stopped for today (from 13:30)." in stop, stop)
        val master = HeroDay.answer(WHY, facts(at(14, 30), decisions = listOf(HeroDay.Decision(at(9, 20), "hero_no_master"))))
        assertTrue("No trade today: the NIFTY contract list was not loaded, so no trade (never a guess)." in master, master)
    }

    @Test fun noRecordOfTheWindow() {
        val late = HeroDay.answer(WHY, facts(at(15, 0), since = at(14, 50)))
        assertTrue("No trade today: the app started at 14:50, after its 13:30-14:45 window, so it did not decide." in late, late)
        val missed = HeroDay.answer(WHY, facts(at(15, 0)))
        assertTrue("No trade today and no decision on record: it decides only while the app's market watch runs in its window (the record is kept in memory since the app started at 09:00)." in missed, missed)
        val now = HeroDay.answer(WHAT, facts(at(14, 0)))
        assertTrue("No decision of this window is on record yet" in now, now)
    }

    // ---- the trade ----------------------------------------------------------------------------------------------------

    private val bought = HeroDay.Trade("NIFTY2610624900CE", true, 1300, 3.10, at(13, 53), at(13, 52),
        seen = listOf(HeroRules.Seen("signal", LocalTime.of(13, 52, 30), 3.05, 3.0, 3.1, 1300, 650)))

    @Test fun anOpenTradeWithItsExits() {
        val ds = listOf(read(13, 52, "entered", stExp = 0.22, mom = 0.0031, side = 1).copy(strike = 24_900.0, price = 3.05, limit = 3.15, lots = 1))
        val a = HeroDay.answer(WHAT, facts(at(14, 10), decisions = ds, today = listOf(bought.copy(mark = 4.2))))
        assertTrue("At 13:52 it fired up (a call) - picked the 24,900 at ₹3.05, limit ₹3.15, 1 lot: it bought." in a, a)
        assertTrue("It decided on 1 minute (at 13:52): 1 - it bought." in a, a)
        assertTrue("It bought 1300 NIFTY2610624900CE (a call, NIFTY up) on paper at ₹3.10 at 13:53 on the 13:52 signal - ₹4,030 of premium against its ₹5,000 budget." in a, a)
        assertTrue("Its exits (F07): half out at ₹15.50 (5x), the rest at ₹62.00 (20x) or 15:05, and a stop on a minute closing at or below ₹1.24 (-60%)." in a, a)
        assertTrue("The option's book at the signal: signal 13:52 · bid 3.00 ×1,300 / ask 3.10 ×650" in a, a)
        assertTrue("Still open: 1300 held, last ₹4.20, watched every pass against those exits." in a, a)
        assertFalse("No trade" in a, a)
    }

    @Test fun aClosedTradeWithItsHalfAndNet() {
        val t = bought.copy(qty = 650, sold = 650, soldAt = 15.5, soldTime = at(14, 5), exit = 1.2, exitTime = at(14, 40), why = "hero_stop", net = 7_200.0, charges = 120.0)
        val a = HeroDay.answer(WHY, facts(at(15, 10), today = listOf(t)))
        assertTrue("It did trade today." in a, a)
        assertTrue("It bought 1300 NIFTY2610624900CE" in a, a)
        assertTrue("It sold 650 at ₹15.50 at 14:05 - the 5x target." in a, a)
        assertTrue("The rest (650) went at ₹1.20 at 14:40: the -60% stop (a minute's close at or below 0.4x the price paid). Net ₹7,200 after ₹120 of charges." in a, a)
        val all = HeroDay.answer(WHAT, facts(at(15, 10), today = listOf(bought.copy(exit = 0.8, exitTime = at(15, 5), why = "hero_exit", net = -1_100.0))))
        assertTrue("All of it went at ₹0.80 at 15:05: the 15:05 time exit. Net −₹1,100 after ₹0 of charges." in all, all)
        val day = HeroDay.answer(DAY, facts(at(15, 10), today = listOf(t)))
        assertTrue("Today it bought NIFTY2610624900CE at ₹3.10 at 13:53, out at ₹1.20: the -60% stop" in day, day)
        assertTrue(", net ₹7,200." in day, day)
    }

    @Test fun eachExitsWords() {
        assertEquals("the 5x target", HeroDay.exitWords("hero_5x"))
        assertEquals("the 20x target", HeroDay.exitWords("hero_20x"))
        assertEquals("the 15:05 time exit", HeroDay.exitWords("hero_exit"))
        assertEquals("the bot's stop for the day", HeroDay.exitWords("operator_stop"))
        assertEquals("closed by you", HeroDay.exitWords("closed_by_you"))
        assertEquals("the 15:15 square-off backstop", HeroDay.exitWords("backstop_square_off"))
        assertEquals("exit not recorded", HeroDay.exitWords(null))
        assertEquals("something else", HeroDay.exitWords("something_else"))
    }

    @Test fun eachVerdictsWords() {
        assertEquals("waiting for 13:30", HeroDay.verdictWords("hero_waiting_for_window"))
        assertEquals("no new entries after 14:45", HeroDay.verdictWords("hero_window_closed"))
        assertEquals("done for today (one entry a day)", HeroDay.verdictWords("hero_done_for_today"))
        assertEquals("not a NIFTY expiry day", HeroDay.verdictWords("hero_not_expiry_day"))
        assertEquals("the minute's NIFTY or straddle data was stale, so it was skipped", HeroDay.verdictWords("hero_skipped_stale_bar"))
        assertEquals("it fired, but the option's contract was not in the list", HeroDay.verdictWords("no_contract"))
        assertEquals("it fired, but the lot size was not in the contract list", HeroDay.verdictWords("hero_lot_unknown"))
        assertEquals("it fired, but another automatic position holds NIFTY", HeroDay.verdictWords("refused: another automatic position holds NIFTY"))
        assertEquals("it fired, but the account guard refused the order (max lots)", HeroDay.verdictWords("guard_refused: max lots"))
        assertEquals("it fired, but the order did not go through (the limit 3.15 did not fill)", HeroDay.verdictWords("order_refused: the limit 3.15 did not fill"))
        assertEquals("it disarmed itself (Rs 50,000 lost)", HeroDay.verdictWords("hero_disarmed: Rs 50,000 lost"))
        assertEquals("holding its position", HeroDay.verdictWords("holding"))
        assertEquals("past the arms' square-off time", HeroDay.verdictWords("flat_after_square_off"))
        assertEquals("an error in its pass", HeroDay.verdictWords("error"))
        assertEquals("no new minute to decide on", HeroDay.verdictWords("no_decision_bar"))
        assertEquals("some new thing", HeroDay.verdictWords("some_new_thing"))
        assertTrue(HeroDay.firedButNot("hero_zero_lots") && HeroDay.firedButNot("guard_refused: x") && !HeroDay.firedButNot("hero_no_momentum"))
    }

    @Test fun readsWithoutAStraddleSayNothing() {
        assertNull(HeroDay.readWords(HeroDay.Decision(at(13, 30), "hero_skipped_stale_bar", LocalTime.of(13, 30))))
        assertEquals("the ATM straddle ₹20.00", HeroDay.readWords(HeroDay.Decision(at(13, 30), "x", LocalTime.of(13, 30), straddle = 20.0)))
        assertNull(HeroDay.tally(listOf(HeroDay.Decision(at(9, 20), "hero_waiting_for_window"))))
        assertNull(HeroDay.closest(emptyList()))
    }

    // ---- its record ---------------------------------------------------------------------------------------------------

    @Test fun thePaperRecordAndTheUnprovenLine() {
        val r = ForwardCheck.check(ForwardCheck.HERO, listOf(ForwardCheck.Trade(day, -2_000.0), ForwardCheck.Trade(day.plusDays(7), 9_000.0)))
        val a = HeroDay.recordLine(facts(at(15, 0), record = r, allTime = 5 to -10_000.0))
        assertTrue(a.startsWith("Paper record (F07 exits, since Tue 6 Oct): 2 closed trades, net ₹7,000 (too few trades (<20); drawdown −₹2,000 against the backtest's worst −₹53,035)."), a)
        assertTrue("In all, with its earlier exits: 5 trades, net −₹10,000." in a, a)
        assertTrue(a.endsWith(HeroDay.UNPROVEN), a)
        assertTrue(HeroDay.recordLine(facts(at(15, 0), record = null)).startsWith("Its paper record could not be read just now."))
        val same = HeroDay.recordLine(facts(at(15, 0), record = r, allTime = 2 to 7_000.0))
        assertFalse("In all" in same, same)
    }

    @Test fun neverAWordOfActing() {
        val answers = listOf(
            HeroDay.answer(WHAT, facts(at(14, 10), today = listOf(bought))),
            HeroDay.answer(WHY, facts(at(15, 0))),
            HeroDay.answer(DAY, facts(at(10, 0), armed = false)),
            HeroDay.answer(NEXT, facts(day.plusDays(1).atTime(10, 0), ex = expiries.drop(1))))
        for (a in answers) {
            assertTrue(HeroDay.UNPROVEN in a, a)
            for (w in listOf("I armed", "I have armed", "I placed", "I closed", "I switched", "I changed", "shall I", "should I arm", "I'll arm"))
                assertFalse(w in a, "$w: $a")
        }
    }
}
