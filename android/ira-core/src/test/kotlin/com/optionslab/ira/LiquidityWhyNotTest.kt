package com.optionslab.ira

import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.LiquidityWhyNot.BookState
import com.optionslab.ira.LiquidityWhyNot.Decision
import com.optionslab.ira.LiquidityWhyNot.Facts
import com.optionslab.ira.LiquidityWhyNot.Kind
import com.optionslab.ira.LiquidityWhyNot.Q
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiquidityWhyNotTest {
    private val day: LocalDate = LocalDate.of(2026, 10, 6)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private val bn15 = LiquidityRules.ARM15.source
    private val bn5 = LiquidityRules.ARM5.source
    private val fn30 = LiquidityRules.FIN30.source
    private val fn5 = LiquidityRules.FIN5.source

    private fun books(armed: Boolean = true, status: String = "no_liquidity_break", decided: Int = 0) =
        LiquidityRules.BOOKS.map { BookState(it.source, armed, status, decided) }

    private fun level(side: Int, edge: Double) = LiquidityMap.Level("pool", side, edge, onSwing = true)

    /** A book's levels: [up]/[down] the triggers (null: none), [upTarget]/[downTarget] the next level beyond each. */
    private fun read(und: String, minutes: Int, price: Double, up: Double?, down: Double?, upTarget: Double? = null, downTarget: Double? = null,
                     priceAt: LocalDateTime = at(11, 0)): LiquidityMap.Read {
        fun side(s: Int, edge: Double?, target: Double?): LiquidityMap.Side {
            if (edge == null) return LiquidityMap.Side(s, null, null, null, null, false)
            val room = target?.let { s * (it - edge) }
            val enough = LiquidityRules.hasRoom(LiquidityRules.Signal(s, edge, target), edge, und)
            return LiquidityMap.Side(s, level(s, edge), level(s, edge), target, room, enough)
        }
        return LiquidityMap.Read(und, minutes, LiquidityMap.State.OK, price, priceAt, priceAt.minusMinutes(minutes.toLong()),
            side(1, up, upTarget), side(-1, down, downTarget), entryOpen = true, bars = 60)
    }

    private val reads = listOf(
        read("BANKNIFTY", 15, 54_120.0, 54_180.0, 53_900.0),
        read("BANKNIFTY", 5, 54_120.0, 54_150.0, 54_000.0, upTarget = 54_160.0),
        read("FINNIFTY", 30, 25_500.0, 25_560.0, null),
        read("FINNIFTY", 5, 25_500.0, null, 25_470.0),
    )

    private fun trade(source: String, right: String, h: Int, m: Int, exit: Double? = null, why: String? = null, exitAt: LocalDateTime? = null) =
        BotTrades.Trade(source, "BANKNIFTY26OCT54000CE", right, 30, 200.0, at(h, m), at(h, m).minusMinutes(5), exit, exitAt, why, 40.0,
            level = 54_100.0, target = 54_300.0, lot = 30)

    private fun facts(now: LocalDateTime = at(11, 0), armed: Boolean? = true, decided: Int = 0, status: String = "no_liquidity_break",
                      decisions: List<Decision> = emptyList(), trades: List<BotTrades.Trade> = emptyList(), stopped: DayStop.Why? = null,
                      since: LocalDateTime? = at(8, 50), tradingDay: Boolean = true, rs: List<LiquidityMap.Read> = reads) =
        Facts(now, tradingDay, armed, books(armed == true, status, decided), stopped, trades, decisions, since, rs)

    private fun noBreak(book: String, h: Int, m: Int, close: Double = 54_100.0) = Decision(at(h, m), book, "no_liquidity_break", at(h, m), close = close)

    // ---- the question ---------------------------------------------------------------------------------------------

    @Test fun theQuestionIsWhyNoTradeOrWhatItWaitsFor() {
        for (s in listOf("why no liquidity trade today?", "why didn't liquidity trade", "why didn't liquidity trade today", "why did liquidity not trade today",
            "why hasn't liquidity traded", "why is liquidity not trading", "why no trade from liquidity", "why didn't the liquidity bot trade",
            "liquidity ne trade kyu nahi liya", "liquidity ne trade kyun nahi liya", "liquidity kyu nahi chala", "aaj liquidity ne entry kyun nahi li",
            "how come liquidity didn't trade", "why did liquidity skip the break", "why did liquidity skip"))
            assertEquals(Q(LiquidityRules.UNDERLYINGS, waiting = false), LiquidityWhyNot.asked(s), s)
        for (s in listOf("what is liquidity waiting for", "what's liquidity waiting for", "what is the liquidity arm waiting for",
            "liquidity kis cheez ka wait kar raha hai", "what would make liquidity trade", "when will liquidity trade", "what does liquidity need to trade"))
            assertEquals(Q(LiquidityRules.UNDERLYINGS, waiting = true), LiquidityWhyNot.asked(s), s)
        assertEquals(Q(listOf("BANKNIFTY")), LiquidityWhyNot.asked("why no banknifty liquidity trade"))
        assertEquals(Q(listOf("FINNIFTY"), waiting = true), LiquidityWhyNot.asked("what is finnifty liquidity waiting for"))
        assertEquals(Q(listOf("BANKNIFTY", "FINNIFTY")), LiquidityWhyNot.asked("why didn't liquidity trade on bank nifty or fin nifty"))
    }

    @Test fun neverItsExitsRecordLevelsSizeSwitchAnotherDayOrAnotherArm() {
        for (s in listOf("why did the liquidity bot exit", "why did liquidity exit early", "liquidity trades today", "why did liquidity trade today",
            "why did liquidity 15+5 lose today", "how did liquidity do today", "liquidity levels", "where are the liquidity levels",
            "liquidity kis level ka wait kar raha hai", "which level is liquidity waiting for", "how far is the next liquidity pool",
            "is liquidity on track", "how many lots does liquidity trade", "why didn't liquidity trade yesterday", "why didn't liquidity trade last week",
            "liquidity ne kal trade kyu nahi liya", "why didn't liquidity trade on 3 oct", "why didn't orb trade", "why didn't orb or liquidity trade",
            "why no trade today", "what is a liquidity pool", "why is there no liquidity in the 52000 ce", "turn off liquidity", "liquidity",
            "why didn't liquidity make a profit", "what is the market waiting for", "should i switch off liquidity because it didn't trade"))
            assertNull(LiquidityWhyNot.asked(s), s)
    }

    // ---- the verdicts ---------------------------------------------------------------------------------------------

    @Test fun eachVerdictHasItsKind() {
        val want = mapOf(
            "entered" to Kind.ENTERED, "entered_live" to Kind.ENTERED, "entered_unconfirmed" to Kind.ENTERED, "awaiting_approval" to Kind.AWAITING,
            LiquidityWhyNot.LAPSED to Kind.LAPSED, "skipped_by_you" to Kind.SKIPPED_BY_YOU, "liquidity_no_room" to Kind.NO_ROOM,
            "no_liquidity_break" to Kind.NO_BREAK, "liquidity_outside_entry_hours" to Kind.OUTSIDE_HOURS, "holding" to Kind.HOLDING,
            "stopped_for_today" to Kind.STOPPED, "flat_after_square_off" to Kind.SQUARED_OFF, "liquidity_history_loading" to Kind.LOADING,
            "no_index_data" to Kind.NO_DATA, "no_contract" to Kind.NO_CONTRACT, "refused: no quote" to Kind.NO_QUOTE,
            "guard_refused: daily loss limit reached" to Kind.GUARD, "${AutoSide.OPPOSITE}: ORB holds a put" to Kind.EXPOSURE,
            "${AutoSide.SAME_SIDE}: Solo holds a call" to Kind.EXPOSURE, "order_refused: no price to fill at" to Kind.ORDER_REFUSED,
            "refused: something" to Kind.REFUSED, "error" to Kind.ERROR, "error: timeout" to Kind.ERROR, "whatever" to Kind.OTHER)
        for ((v, k) in want) assertEquals(k, LiquidityWhyNot.kind(v), v)
    }

    @Test fun eachBreakNotTakenSaysWhy() {
        fun d(v: String, side: Int? = 1, level: Double? = 54_180.0, target: Double? = 54_200.0, close: Double? = 54_185.0) =
            Decision(at(10, 30), bn15, v, at(10, 15), side, level, target, close)
        assertEquals("Skipped the 10:15 break above 54,180: the next level ahead, 54,200, was only 15 pts from the close (it needs 30)",
            LiquidityWhyNot.notTakenWords(d("liquidity_no_room"), "BANKNIFTY"))
        assertEquals("Skipped the 10:15 break below 25,400: the next level ahead, 25,390, was only 6 pts from the close (it needs 15)",
            LiquidityWhyNot.notTakenWords(d("liquidity_no_room", -1, 25_400.0, 25_390.0, 25_396.0), "FINNIFTY"))
        assertEquals("Skipped the 10:15 break above 54,180: the next level ahead was closer than 30 pts (the room rule)",
            LiquidityWhyNot.notTakenWords(d("liquidity_no_room", target = null), "BANKNIFTY"))
        assertEquals("The 10:15 break above 54,180 waited for your approval and lapsed - nothing was bought",
            LiquidityWhyNot.notTakenWords(d(LiquidityWhyNot.LAPSED), "BANKNIFTY"))
        assertEquals("You skipped the 10:15 break above 54,180", LiquidityWhyNot.notTakenWords(d("skipped_by_you"), "BANKNIFTY"))
        assertEquals("The 10:15 break above 54,180 was refused by Bot settings (your daily limits): daily loss limit reached",
            LiquidityWhyNot.notTakenWords(d("guard_refused: daily loss limit reached"), "BANKNIFTY"))
        assertTrue(LiquidityWhyNot.notTakenWords(d("${AutoSide.OPPOSITE}: ORB holds a put"), "BANKNIFTY")
            .startsWith("The 10:15 break above 54,180 was not entered: ORB holds a put the other way on this index"))
        assertEquals("The 10:15 break above 54,180 was not entered: there was no option price to buy at",
            LiquidityWhyNot.notTakenWords(d("refused: no quote"), "BANKNIFTY"))
        assertEquals("The 10:15 break above 54,180 was not entered: its option contract could not be loaded",
            LiquidityWhyNot.notTakenWords(d("no_contract"), "BANKNIFTY"))
        assertEquals("The 10:15 break above 54,180 was not entered: the order was refused (no price to fill at)",
            LiquidityWhyNot.notTakenWords(d("order_refused: no price to fill at"), "BANKNIFTY"))
        assertEquals("The 10:15 break above 54,180 came after the bot was stopped for today - not entered",
            LiquidityWhyNot.notTakenWords(d("stopped_for_today"), "BANKNIFTY"))
        assertEquals("The 10:15 break above 54,180 could not be checked (an error on that pass)", LiquidityWhyNot.notTakenWords(d("error"), "BANKNIFTY"))
        assertEquals("The 10:15 signal was refused: odd", LiquidityWhyNot.notTakenWords(d("refused: odd", side = null), "BANKNIFTY"))
        assertEquals("The 10:15 break above 54,180 asked for your approval", LiquidityWhyNot.notTakenWords(d("awaiting_approval"), "BANKNIFTY"))
        assertEquals("The 10:15 break above 54,180 was entered", LiquidityWhyNot.notTakenWords(d("entered"), "BANKNIFTY"))
        assertEquals("The 10:15 break above 54,180: some new thing", LiquidityWhyNot.notTakenWords(d("some_new_thing"), "BANKNIFTY"))
    }

    // ---- what would trigger the next entry ------------------------------------------------------------------------

    @Test fun theNextEntryIsTheNearestPoolOnASwingEachSideWithItsRoom() {
        assertEquals("Next (price 54,120): a 15-min close above 54,180 (60 pts up) buys a call; a 15-min close below 53,900 (220 pts down) buys a put",
            LiquidityWhyNot.nextWords(reads[0], at(11, 0)))
        // Above: only 10 points to the next level - it would be skipped.
        assertEquals("Next (price 54,120): a close above 54,150 (30 pts up) would be skipped: only 10 pts to the next level 54,160 (needs 30); " +
            "a 5-min close below 54,000 (120 pts down) buys a put", LiquidityWhyNot.nextWords(reads[1], at(11, 0)))
        assertEquals("Next (price 25,500): a 30-min close above 25,560 (60 pts up) buys a call; no pool sits on a swing below",
            LiquidityWhyNot.nextWords(reads[2], at(11, 0)))
        // Outside the entry hours: when they open again; a price already past the level, its bar not yet closed.
        val past = read("BANKNIFTY", 5, 54_190.0, 54_180.0, null, priceAt = at(14, 30))
        assertEquals("When its entry hours open again, next (price 54,190): a 5-min close above 54,180 (price already 10 pts past it, its bar not yet closed) " +
            "buys a call; no pool sits on a swing below", LiquidityWhyNot.nextWords(past, at(14, 30)))
        assertTrue(LiquidityWhyNot.nextWords(past.copy(priceAt = at(9, 0)), at(9, 0)).startsWith("When its entry hours open again, next (price 54,190):"))
        // A stale price says its time; an earlier day's, its day.
        assertTrue(LiquidityWhyNot.nextWords(read("BANKNIFTY", 5, 54_120.0, 54_180.0, null, priceAt = at(10, 40)), at(11, 0))
            .startsWith("Next (price 54,120 as of 10:40):"))
        assertTrue(LiquidityWhyNot.nextWords(read("BANKNIFTY", 5, 54_120.0, 54_180.0, null, priceAt = at(15, 29).minusDays(1)), at(9, 30))
            .startsWith("Next (price 54,120 as of 5 Oct 15:29):"))
        assertEquals("I could not read its levels just now", LiquidityWhyNot.nextWords(null, at(11, 0)))
        assertEquals("No candles to read its levels from just now",
            LiquidityWhyNot.nextWords(LiquidityMap.Read("BANKNIFTY", 5, LiquidityMap.State.NO_DATA), at(11, 0)))
        assertEquals("Its levels can't be read yet: 12 closed bars of the ${LiquidityMap.MIN_BARS} they need",
            LiquidityWhyNot.nextWords(LiquidityMap.Read("BANKNIFTY", 15, LiquidityMap.State.LOADING, bars = 12), at(11, 0)))
    }

    @Test fun theNearestTriggerIsOneWithRoom() {
        val (r, s) = assertNotNull(LiquidityWhyNot.nearest(reads))
        // BankNifty 5-min's 54,150 is nearer (30) but has no room; FinNifty 5-min's 25,470 (30 down) ties in points with it, but
        // with room - the nearest with room wins.
        assertEquals("FINNIFTY", r.underlying); assertEquals(5, r.minutes); assertEquals(-1, s.side)
        assertNull(LiquidityWhyNot.nearest(emptyList()))
        assertNull(LiquidityWhyNot.nearest(listOf(LiquidityMap.Read("BANKNIFTY", 5, LiquidityMap.State.LOADING))))
    }

    // ---- the answer -----------------------------------------------------------------------------------------------

    @Test fun notATradingDay() {
        val a = LiquidityWhyNot.answer(Q(), facts(tradingDay = false))
        assertTrue(a.startsWith("Today isn't a trading day, Boss, so Liquidity 15+5 had nothing to trade."), a)
    }

    @Test fun noBreakAllDaySaysSoAndWhatItWaitsFor() {
        val ds = (0 until 6).map { noBreak(bn15, 9 + (30 + it * 15) / 60, (30 + it * 15) % 60) }
        val a = LiquidityWhyNot.answer(Q(), facts(decided = 6, decisions = ds))
        val lines = a.lines()
        assertEquals("No Liquidity trade today, Boss: no close took a liquidity pool sitting on a swing zone - the only break it trades. " +
            "Nearest trigger: FinNifty 5-min, a close below 25,470 - 30 pts away (it would buy a put). It is in its entry hours now (to 14:00).", lines[0])
        assertEquals("BankNifty 15-min: Decided on 6 bars today: none closed through a liquidity pool sitting on a swing zone. " +
            "Next (price 54,120): a 15-min close above 54,180 (60 pts up) buys a call; a 15-min close below 53,900 (220 pts down) buys a put.", lines[1])
        assertTrue(lines[2].startsWith("BankNifty 5-min: Decided on 6 bars today: none closed"), lines[2])
        assertTrue(lines[3].startsWith("FinNifty 30-min: "), lines[3])
        assertTrue(lines[4].startsWith("FinNifty 5-min: "), lines[4])
        assertEquals("From the arm's own records - information only: nothing was armed, placed or changed.", lines.last())
        assertEquals(6, lines.size)
    }

    @Test fun aBreakSkippedForNoRoomIsToldWithItsLevelAndDistance() {
        val skip = Decision(at(10, 30), bn5, "liquidity_no_room", at(10, 25), 1, 54_180.0, 54_200.0, 54_185.0)
        val a = LiquidityWhyNot.answer(Q(), facts(decided = 20, decisions = listOf(noBreak(bn5, 10, 20), skip)))
        assertTrue(a.startsWith("No Liquidity trade today, Boss: 1 break was skipped because the next level ahead was too close (the room rule)."), a)
        assertTrue("BankNifty 5-min: Decided on 20 bars today. Skipped the 10:25 break above 54,180: the next level ahead, 54,200, was only 15 pts " +
            "from the close (it needs 30). Next" in a, a)
        assertFalse("no close took" in a.lines()[0], a)
    }

    @Test fun refusalsLapsesAndDataTroubleAreCounted() {
        val ds = listOf(
            Decision(at(9, 25), fn5, "liquidity_history_loading"),
            Decision(at(9, 40), fn5, "no_liquidity_break", at(9, 35)),
            Decision(at(10, 5), bn15, "guard_refused: daily loss limit reached", at(9, 45), -1, 53_900.0, 53_700.0, 53_880.0),
            Decision(at(10, 50), fn30, LiquidityWhyNot.LAPSED, at(10, 15), 1, 25_560.0, null, 25_570.0),
            Decision(at(11, 0), bn5, "error"),
        )
        val a = LiquidityWhyNot.answer(Q(), facts(decisions = ds))
        val head = a.lines()[0]
        assertTrue(head.startsWith("No Liquidity trade today, Boss: 1 break was refused before an order; 1 signal waited for your approval and was not taken; " +
            "its candles were missing or still loading at times."), head)
        assertTrue("The 09:45 break below 53,900 was refused by Bot settings (your daily limits): daily loss limit reached" in a, a)
        assertTrue("The 10:15 break above 25,560 waited for your approval and lapsed - nothing was bought" in a, a)
        assertTrue("FinNifty 5-min: Decided on 1 bar today: none closed through a liquidity pool sitting on a swing zone. Data: candles still loading at 09:25." in a, a)
        assertTrue("Data: a pass that could not check the chart at 11:00" in a, a)
    }

    @Test fun moreThanThreeBreaksAreCounted() {
        val ds = (0 until 5).map { Decision(at(10, it * 5), bn5, "liquidity_no_room", at(9, 55 + it).withMinute((it * 5) % 60), 1, 54_180.0, 54_200.0, 54_185.0) } +
            Decision(at(11, 0), bn5, LiquidityWhyNot.LAPSED, at(10, 40), -1, 54_000.0, null, 53_990.0)
        val a = LiquidityWhyNot.answer(Q(listOf("BANKNIFTY")), facts(decisions = ds))
        assertTrue("And 3 more breaks not taken (2 for no room, 1 lapsed unapproved)" in a, a)
        assertTrue(a.lines()[0].startsWith("No Liquidity trade today, Boss: 5 breaks were skipped because the next level ahead was too close (the room rule); " +
            "1 signal waited for your approval and was not taken."), a)
        // BankNifty asked: FinNifty's books are not told.
        assertFalse("FinNifty" in a, a)
    }

    @Test fun itTradedSaysSoAndPointsToTheTradesAndExits() {
        val ts = listOf(
            trade(bn5, "CE", 10, 5, exit = 230.0, why = "next_liquidity", exitAt = at(10, 32)),
            trade(fn30, "PE", 11, 45),
            trade("liquidity15_fin", "PE", 9, 50, exit = 170.0, why = "stop", exitAt = at(10, 1)),
        )
        val a = LiquidityWhyNot.answer(Q(), facts(trades = ts, status = "holding"))
        val head = a.lines()[0]
        assertTrue(head.startsWith("Liquidity 15+5 did trade today, Boss: 3 trades - FinNifty 30-min bought a put at 09:50, out at 10:01 on its 15% stop; " +
            "BankNifty 5-min bought a call at 10:05, out at 10:32 on the next level (its target); FinNifty 30-min bought a put at 11:45, still open."), head)
        assertTrue("\"Liquidity trades today\" walks through each one, and \"why did the liquidity bot exit\" explains the exits." in head, head)
        assertTrue("BankNifty 5-min: Traded: BankNifty 5-min bought a call at 10:05, out at 10:32 on the next level (its target). " +
            "Now holding its position - one position per chart, so no new entry until it exits." in a, a)
        assertFalse("No Liquidity trade" in a, a)
        // Asked what it waits for: said too.
        assertTrue("For its next entry it waits for a close through a liquidity pool" in LiquidityWhyNot.answer(Q(waiting = true), facts(trades = ts)))
        // Another day's trade is not today's.
        val old = trade(bn5, "CE", 10, 5).copy(entryTime = at(10, 5).minusDays(1))
        assertTrue(LiquidityWhyNot.answer(Q(), facts(trades = listOf(old))).startsWith("No Liquidity trade today"))
    }

    @Test fun exitWordsForEachReason() {
        fun exitOf(why: String?) = LiquidityWhyNot.answer(Q(listOf("BANKNIFTY")), facts(trades = listOf(trade(bn15, "CE", 10, 0, 210.0, why, at(10, 20))))).lines()[0]
        for ((why, words) in listOf("index_stop" to "its index stop", "time_stop" to "its 20-minute time stop", "failed_break" to "a failed break",
            "new_liquidity" to "new liquidity forming", "session_end" to "the 15:10 exit", "closed_by_you" to "your close", null to "an exit",
            "something_else" to "something else"))
            assertTrue("out at 10:20 on $words" in exitOf(why), "$why: ${exitOf(why)}")
        val live = LiquidityWhyNot.answer(Q(), facts(trades = listOf(trade(bn15, "CE", 10, 0).copy(live = true)))).lines()[0]
        assertTrue("BankNifty 15-min bought a call at Zerodha at 10:00, still open" in live, live)
    }

    @Test fun switchedOffStoppedEarlyAndUnread() {
        val off = LiquidityWhyNot.answer(Q(), facts(armed = false))
        assertTrue(off.startsWith("Liquidity 15+5 is switched off, Boss, so it takes no trades - it trades only while armed (Strategies)."), off)
        assertFalse("No decisions of it on record" in off, off)
        assertTrue("It was armed earlier today" in LiquidityWhyNot.answer(Q(), facts(armed = false, decisions = listOf(noBreak(bn15, 9, 30)))))
        val stopped = LiquidityWhyNot.answer(Q(), facts(stopped = DayStop.Why.LOSS, status = "stopped_for_today"))
        assertTrue(stopped.startsWith("No Liquidity trade today, Boss: ${DayStop.line(DayStop.Why.LOSS)}"), stopped)
        assertTrue("Now stopped for today" in stopped, stopped)
        val early = LiquidityWhyNot.answer(Q(), facts(now = at(9, 5)))
        assertTrue(early.startsWith("No Liquidity trade yet, Boss - it is early. Nearest trigger: FinNifty 5-min"), early)
        assertTrue("Its entry hours (09:20-14:00) haven't begun yet." in early.lines()[0], early)
        assertFalse("No decisions of it on record" in early, early)
        val late = LiquidityWhyNot.answer(Q(), facts(now = at(14, 40), decided = 3))
        assertTrue("Its entry hours (09:20-14:00) are over for today." in late.lines()[0], late)
        val nothing = LiquidityWhyNot.answer(Q(), facts(armed = null, status = ""))
        assertTrue(nothing.startsWith("No Liquidity trade today, Boss: I have no record of it deciding on a bar today (and I couldn't read its switch)."), nothing)
        assertTrue("No decisions of it on record today" in nothing, nothing)
        // The day's stop seen in the record (since lifted): said with its time.
        val lifted = LiquidityWhyNot.answer(Q(), facts(decisions = listOf(Decision(at(10, 0), bn15, "stopped_for_today"))))
        assertTrue("Stood still from 10:00 while the bot was stopped for the day" in lifted, lifted)
    }

    @Test fun eachBooksStateNow() {
        fun now(status: String) = LiquidityWhyNot.answer(Q(listOf("BANKNIFTY")), facts(status = status)).lines()[1]
        assertTrue("Now a signal waits for your approval (Home)" in now("awaiting_approval"))
        assertTrue("Done for the day (15:10)" in now("flat_after_square_off"))
        assertTrue("Now its candles are still loading" in now("liquidity_history_loading"))
        assertTrue("Now the index candles cannot be read" in now("no_index_data"))
        assertTrue("Its last pass could not check the chart" in now("error"))
        assertFalse("Now" in now("no_liquidity_break").substringBefore("Next"))
    }

    @Test fun waitingLeadsWithWhatItWaitsFor() {
        val a = LiquidityWhyNot.answer(Q(waiting = true), facts(decided = 4))
        assertTrue(a.startsWith("Boss, Liquidity 15+5 waits for a close through a liquidity pool that sits on a swing zone, with room to the next level " +
            "(one index stop: BankNifty 30 pts, FinNifty 15) - then it buys the call (up) or put (down). Nearest trigger: FinNifty 5-min, a close below " +
            "25,470 - 30 pts away (it would buy a put)."), a)
        // Asked of BankNifty: its nearest (15-min, 60 up; the 5-min's 30 up has no room).
        val bank = LiquidityWhyNot.answer(Q(listOf("BANKNIFTY"), waiting = true), facts())
        assertTrue("Nearest trigger: BankNifty 15-min, a close above 54,180 - 60 pts away (it would buy a call)." in bank, bank)
    }

    @Test fun theRecordsStartIsSaidWhenTheAppStartedLate() {
        assertTrue("(My record of its bars runs from 10:12, when the app last started; earlier bars are counted but not described.)" in
            LiquidityWhyNot.answer(Q(), facts(since = at(10, 12))))
        assertFalse("My record" in LiquidityWhyNot.answer(Q(), facts(since = at(9, 0))))
        assertFalse("My record" in LiquidityWhyNot.answer(Q(), facts(since = at(10, 12).minusDays(1))))
        assertFalse("My record" in LiquidityWhyNot.answer(Q(), facts(since = null)))
    }

    @Test fun aRenamedBooksRecordsCountAsItsNewBook() {
        val d = Decision(at(10, 30), "liquidity15_fin", "liquidity_no_room", at(10, 0), -1, 25_400.0, 25_390.0, 25_396.0)
        val a = LiquidityWhyNot.answer(Q(listOf("FINNIFTY")), facts(decisions = listOf(d)))
        assertTrue("FinNifty 30-min: Decided on 1 bar today. Skipped the 10:00 break below 25,400" in a, a)
    }

    @Test fun outsideHoursBarsAreSaidSo() {
        val ds = listOf(Decision(at(9, 20), bn5, "liquidity_outside_entry_hours", at(9, 15)))
        val a = LiquidityWhyNot.answer(Q(listOf("BANKNIFTY")), facts(now = at(9, 21), decisions = ds))
        assertTrue("BankNifty 5-min: Decided on 1 bar today, all outside its entry hours (09:20-14:00)." in a, a)
    }

    @Test fun neverAWordOfActing() {
        val answers = listOf(LiquidityWhyNot.answer(Q(), facts()), LiquidityWhyNot.answer(Q(waiting = true), facts(armed = false)),
            LiquidityWhyNot.answer(Q(), facts(trades = listOf(trade(bn5, "CE", 10, 5)))))
        for (a in answers) {
            assertTrue(a.endsWith("From the arm's own records - information only: nothing was armed, placed or changed."), a)
            for (w in listOf("I armed", "I placed", "I bought", "I sold", "switched it on", "I closed")) assertFalse(w in a, "$w: $a")
        }
        assertEquals("Unlock the phone for that, Boss.", LiquidityWhyNot.LOCKED)
    }

    // ---- review fixes ---------------------------------------------------------------------------------------------

    @Test fun aBarIsToldByItsLastRecordAndAWaitingSignalIsItsOwnReason() {
        val asked = Decision(at(10, 16), bn15, "awaiting_approval", at(10, 0), 1, 54_180.0, null, 54_190.0)
        // Asked, then lapsed (the same bar, recorded again by the arm): the lapse is the bar's story, headline and book alike.
        val lapsed = LiquidityWhyNot.answer(Q(listOf("BANKNIFTY")), facts(decided = 3, decisions = listOf(asked,
            Decision(at(10, 21), bn15, LiquidityWhyNot.LAPSED, at(10, 0), 1, 54_180.0, null, 54_190.0))))
        assertTrue(lapsed.startsWith("No Liquidity trade today, Boss: 1 signal waited for your approval and was not taken."), lapsed)
        assertFalse("no close took" in lapsed, lapsed)
        assertTrue("The 10:00 break above 54,180 waited for your approval and lapsed - nothing was bought" in lapsed, lapsed)
        assertFalse("none closed through" in lapsed.lines()[1], lapsed)
        // Skipped by you: the same.
        val skipped = LiquidityWhyNot.answer(Q(listOf("BANKNIFTY")), facts(decided = 3, decisions = listOf(asked,
            Decision(at(10, 18), bn15, "skipped_by_you", at(10, 0), 1, 54_180.0, null, 54_190.0))))
        assertTrue(skipped.startsWith("No Liquidity trade today, Boss: 1 signal waited for your approval and was not taken."), skipped)
        assertTrue("You skipped the 10:00 break above 54,180" in skipped, skipped)
        // Still waiting: its own reason, and the book says so too (not "none closed through a pool").
        val waiting = LiquidityWhyNot.answer(Q(listOf("BANKNIFTY")), facts(decided = 3, status = "awaiting_approval", decisions = listOf(asked)))
        assertTrue(waiting.startsWith("No Liquidity trade today, Boss: a signal is waiting for your approval."), waiting)
        assertFalse("no close took" in waiting, waiting)
        assertTrue("BankNifty 15-min: Decided on 3 bars today. The 10:00 break above 54,180 asked for your approval - it is waiting for you (Home)." in waiting, waiting)
        assertFalse("none closed through" in waiting.lines()[1], waiting)
    }

    @Test fun breaksNotTakenForAnyOtherReasonAreStillCountedInTheHeadline() {
        val ds = listOf(
            Decision(at(10, 5), bn15, "stopped_for_today", at(10, 0), 1, 54_180.0, null, 54_190.0),
            Decision(at(10, 35), bn15, "something_new", at(10, 30), -1, 53_900.0, null, 53_880.0),
        )
        val a = LiquidityWhyNot.answer(Q(listOf("BANKNIFTY")), facts(decided = 4, decisions = ds))
        assertTrue(a.startsWith("No Liquidity trade today, Boss: 2 breaks were not entered - see below."), a)
        assertFalse("Boss: ." in a, a)
        val err = LiquidityWhyNot.answer(Q(listOf("BANKNIFTY")), facts(decisions = listOf(Decision(at(10, 5), bn15, "error", at(10, 0)))))
        assertTrue(err.startsWith("No Liquidity trade today, Boss: 1 break was not entered - see below."), err)
        // Only bar-less records that explain nothing: never an empty reason.
        val bare = LiquidityWhyNot.answer(Q(listOf("BANKNIFTY")), facts(status = "", decisions = listOf(Decision(at(10, 5), bn15, "holding"))))
        assertTrue(bare.startsWith("No Liquidity trade today, Boss: I have no record of it deciding on a bar today."), bare)
    }

    @Test fun aTriggerAlreadyPassedIsNotTheNearest() {
        // BankNifty 15-min's price is already 20 above its 54,180 trigger (bar not closed): the nearest ahead is below, 300 pts.
        val passed = listOf(read("BANKNIFTY", 15, 54_200.0, 54_180.0, 53_900.0))
        val (r, s) = assertNotNull(LiquidityWhyNot.nearest(passed))
        assertEquals(15, r.minutes); assertEquals(-1, s.side)
        val a = LiquidityWhyNot.answer(Q(listOf("BANKNIFTY")), facts(decided = 2, rs = passed))
        assertTrue("Nearest trigger: BankNifty 15-min, a close below 53,900 - 300 pts away (it would buy a put)." in a.lines()[0], a)
        // The only trigger already passed: no nearest at all.
        assertNull(LiquidityWhyNot.nearest(listOf(read("BANKNIFTY", 15, 54_000.0, 53_950.0, null))))
    }
}
