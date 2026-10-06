package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiquidityRecordTest {
    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|consider|switch (it )?off|turn (it )?off|reduce|increase|fewer lots|more lots|change (the |its )?rules?)\\b")
    private val TODAY: LocalDate = LocalDate.of(2026, 10, 6)   // a Tuesday

    /** A closed trade of [source] entered on [day] at [h]:[m], [lots] lots of [lot], held 15 minutes. */
    private fun t(source: String, day: LocalDate, h: Int, m: Int, entry: Double, exit: Double?, why: String, lots: Int = 1, lot: Int? = 35,
                  charges: Double = 60.0, live: Boolean = false): BotTrades.Trade {
        val at = LocalDateTime.of(day, java.time.LocalTime.of(h, m))
        val qty = lots * (lot ?: 35)
        return BotTrades.Trade(source, "BANKNIFTY26OCT56000CE", "CE", qty, entry, at, at.minusMinutes(5), exit, exit?.let { at.plusMinutes(15) },
            exit?.let { why }, charges, live, lot = lot)
    }

    private val d29 = LocalDate.of(2026, 9, 29); private val d30 = LocalDate.of(2026, 9, 30)
    private val d1 = LocalDate.of(2026, 10, 1); private val d5 = LocalDate.of(2026, 10, 5); private val d6 = LocalDate.of(2026, 10, 6)

    /** Last week: A +990 (next level), B -585 (index stop), C -710 a lot on 2 FinNifty lots (failed break), D +4,140 (next level). This week: E +290 (time stop). */
    private val book = listOf(
        t("liquidity15", d29, 10, 0, 300.0, 330.0, "next_liquidity"),
        t("liquidity5", d30, 11, 0, 300.0, 285.0, "index_stop"),
        t("liquidity5_fin", d1, 10, 30, 100.0, 90.0, "failed_break", lots = 2, lot = 65, charges = 120.0),
        t("liquidity15", d1, 13, 0, 300.0, 420.0, "next_liquidity"),
        t("liquidity15", d5, 10, 0, 300.0, 310.0, "time_stop"),
        // Not the arm's record: another arm, a live trade, a trade still open.
        t("orb", d30, 9, 35, 200.0, 260.0, "target"),
        t("liquidity15", d30, 12, 0, 300.0, 400.0, "next_liquidity", live = true),
        t("liquidity5", d5, 13, 0, 300.0, null, ""),
    )
    private val rows = LiquidityRecord.rows(book)

    @Test fun theBookIsReadAsTheArmsOwnClosedPaperTrades() {
        assertEquals(5, rows.size)
        assertEquals(listOf(990.0, -585.0, -1420.0, 4140.0, 290.0), rows.map { it.net })
        assertEquals(listOf(990.0, -585.0, -710.0, 4140.0, 290.0), rows.map { it.perLot })
        assertEquals(listOf("BankNifty 15-min", "BankNifty 5-min", "FinNifty 5-min", "BankNifty 15-min", "BankNifty 15-min"), rows.map { it.book })
        assertEquals(listOf("BANKNIFTY", "BANKNIFTY", "FINNIFTY", "BANKNIFTY", "BANKNIFTY"), rows.map { it.index })
        assertEquals(2.0, rows[2].lots)
        // A book renamed since it was saved is still the arm's (FinNifty's 15-minute book became its 30-minute book); a lot
        // size not known counts as one lot; oldest exit first, whatever the order given.
        val renamed = LiquidityRecord.rows(listOf(t("liquidity15_fin", d6, 11, 0, 100.0, 110.0, "time_stop", lot = null), book[0]))
        assertEquals(listOf("BankNifty 15-min", "FinNifty 30-min"), renamed.map { it.book })
        assertEquals(1.0, renamed[1].lots)
        assertEquals("time_stop", renamed[1].why)
    }

    @Test fun tallyRunsAndTheChanceOfALosingRun() {
        val t = LiquidityRecord.tally(rows)
        assertEquals(5, t.trades); assertEquals(3, t.wins); assertEquals(2, t.losses)
        assertEquals(3415.0, t.net); assertEquals(4125.0, t.perLot); assertEquals(360.0, t.charges)
        assertEquals(825.0, t.perTrade); assertEquals(0.6, t.winRate)
        assertNull(LiquidityRecord.tally(emptyList()).perTrade); assertNull(LiquidityRecord.tally(emptyList()).winRate)
        val (cur, w, l) = LiquidityRecord.runs(rows)
        assertEquals(LiquidityRecord.Run(true, 2), cur); assertEquals(2, w); assertEquals(2, l)
        assertEquals(LiquidityRecord.Run(false, 0), LiquidityRecord.runs(emptyList()).first)
        assertEquals(LiquidityRecord.Run(false, 1), LiquidityRecord.runs(rows.take(2)).first)
        // Exact: one trade, a run of one loss is the loss rate; two trades, 1 - both won; a run longer than the trades never.
        assertEquals(0.6, LiquidityRecord.lossRunChance(1, 1, 0.6), 1e-12)
        assertEquals(1 - 0.4 * 0.4, LiquidityRecord.lossRunChance(2, 1, 0.6), 1e-12)
        assertEquals(0.36, LiquidityRecord.lossRunChance(2, 2, 0.6), 1e-12)
        assertEquals(0.36 + 0.4 * 0.36, LiquidityRecord.lossRunChance(3, 2, 0.6), 1e-12)
        assertEquals(0.0, LiquidityRecord.lossRunChance(3, 4, 0.6))
        assertEquals(1.0, LiquidityRecord.lossRunChance(3, 0, 0.6))
    }

    @Test fun theSpansAreTheDaysSaid() {
        val q = LiquidityRecord.Q()
        assertEquals(d5 to TODAY, LiquidityRecord.window(q.copy(span = LiquidityRecord.Span.THIS_WEEK), TODAY))
        assertEquals(LocalDate.of(2026, 9, 28) to LocalDate.of(2026, 10, 4), LiquidityRecord.window(q.copy(span = LiquidityRecord.Span.LAST_WEEK), TODAY))
        assertEquals(LocalDate.of(2026, 10, 1) to TODAY, LiquidityRecord.window(q.copy(span = LiquidityRecord.Span.THIS_MONTH), TODAY))
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 30), LiquidityRecord.window(q.copy(span = LiquidityRecord.Span.LAST_MONTH), TODAY))
        assertEquals(d5 to d5, LiquidityRecord.window(q.copy(span = LiquidityRecord.Span.YESTERDAY), TODAY))
        assertNull(LiquidityRecord.window(q, TODAY)); assertNull(LiquidityRecord.window(q.copy(span = LiquidityRecord.Span.LAST_N, n = 3), TODAY))
        // Yesterday on a Monday is the Friday; a day still to come this year is last year's.
        assertEquals(LocalDate.of(2026, 10, 2), LiquidityRecord.lastWeekdayBefore(d5))
        assertEquals(LocalDate.of(2026, 10, 3), LiquidityRecord.dayOf(MonthDay.of(10, 3), TODAY))
        assertEquals(LocalDate.of(2025, 12, 24), LiquidityRecord.dayOf(MonthDay.of(12, 24), TODAY))
        assertEquals(TODAY, LiquidityRecord.dayOf(MonthDay.of(10, 6), TODAY))
        // A day with no MonthDay given is today.
        assertEquals(TODAY to TODAY, LiquidityRecord.window(q.copy(span = LiquidityRecord.Span.DAY), TODAY))
        assertEquals(4, LiquidityRecord.pick(q.copy(span = LiquidityRecord.Span.LAST_WEEK), rows, TODAY).size)
        assertEquals(listOf(4140.0, 290.0), LiquidityRecord.pick(q.copy(span = LiquidityRecord.Span.LAST_N, n = 2), rows, TODAY).map { it.net })
        assertEquals(listOf(290.0), LiquidityRecord.pick(q.copy(span = LiquidityRecord.Span.LAST_N), rows, TODAY).map { it.net })
        assertEquals(2, LiquidityRecord.pick(q.copy(span = LiquidityRecord.Span.DAY, day = MonthDay.of(10, 1)), rows, TODAY).size)
        assertEquals(5, LiquidityRecord.pick(q, rows, TODAY).size)
    }

    @Test fun lastWeeksRecordInFull() {
        val a = LiquidityRecord.answer(LiquidityRecord.Q(LiquidityRecord.Span.LAST_WEEK), rows, TODAY)
        val lines = a.lines()
        assertEquals("Boss, Liquidity 15+5's paper record last week (28 Sep - 4 Oct): 4 trades, 2 won and 2 lost, net +Rs 3,125 after Rs 300 charges " +
            "(+Rs 3,835 a lot, +Rs 959 a lot a trade).", lines[0])
        assertTrue(a.contains("Best: 1 Oct 13:00 BankNifty 15-min CE: +Rs 4,140 a lot (next liquidity level) - a big win, in the top 10% of its wins in the research."), a)
        assertTrue(a.contains("Worst: 1 Oct 10:30 FinNifty 5-min CE: -Rs 710 a lot (failed break) - a normal loss for it."), a)
        assertTrue(a.contains("By index: BankNifty 3 trades (2 won), +Rs 4,545 a lot, +Rs 1,515 a trade; FinNifty 1 trade (0 won), -Rs 710 a lot, -Rs 710 a trade."), a)
        assertTrue(a.contains("By exit: next liquidity level 2 (2 won), +Rs 5,130 a lot; index stop 1 (0 won), -Rs 585 a lot; failed break 1 (0 won), -Rs 710 a lot."), a)
        assertTrue(a.contains("Streak: its last trade won (longest: 1 win, 2 losses in a row)."), a)
        assertTrue(a.contains("Against the research: +Rs 959 a lot a trade and 50% won, but 4 trades are too few to judge (it takes 20) - the backtest made " +
            "+Rs 228 a lot a trade and won 41%; the five-year study won 36% (middle win +Rs 689, middle loss -Rs 554 a lot)."), a)
        // Its forward trades start on 06 Oct (the new rules): none yet.
        assertTrue(a.contains("Live vs backtest (its forward trades since 6 Oct): no forward trade yet."), a)
        assertEquals(LiquidityRecord.NOTE, lines.last())
        assertTrue(!ADVICE.containsMatchIn(a), a)
    }

    @Test fun aDayAndTheLastFewAreToldOneByOne() {
        val day = LiquidityRecord.answer(LiquidityRecord.Q(LiquidityRecord.Span.DAY, day = MonthDay.of(10, 1)), rows, TODAY)
        assertTrue(day.startsWith("Boss, Liquidity 15+5's paper record on 1 Oct: 2 trades, 1 won and 1 lost"), day)
        assertTrue(day.contains("\n1. 1 Oct 10:30 FinNifty 5-min CE: -Rs 710 a lot (failed break).\n2. 1 Oct 13:00 BankNifty 15-min CE: +Rs 4,140 a lot (next liquidity level)."), day)
        val last = LiquidityRecord.answer(LiquidityRecord.Q(LiquidityRecord.Span.LAST_N, n = 10), rows, TODAY)
        assertTrue(last.startsWith("Boss, Liquidity 15+5's paper record over its last 5 trades: 5 trades, 3 won and 2 lost, net +Rs 3,415 after Rs 360 charges"), last)
        assertTrue(last.contains("\n5. 5 Oct 10:00 BankNifty 15-min CE: +Rs 290 a lot (time stop)."), last)
        val one = LiquidityRecord.answer(LiquidityRecord.Q(LiquidityRecord.Span.LAST_N, n = 1), rows, TODAY)
        assertTrue(one.startsWith("Boss, Liquidity 15+5's paper record for its last trade: 1 trade, 1 won and 0 lost"), one)
        // One trade: no best-and-worst pair, no index split, its streak.
        assertTrue(!one.contains("Best:") && !one.contains("By index:"), one)
        assertTrue(one.contains("Streak: its last trade won (longest: 1 win, 0 losses in a row)."), one)
        assertTrue(one.contains("1 trade is too few to judge"), one)
        // More than ten: the newest ten, and how many are left out.
        val many = (1..12).map { i -> t("liquidity15", LocalDate.of(2026, 9, i), 10, 0, 300.0, 310.0, "time_stop") }
        val all = LiquidityRecord.answer(LiquidityRecord.Q(LiquidityRecord.Span.LAST_N, n = 12), LiquidityRecord.rows(many), TODAY)
        assertTrue(all.contains("\n10. 12 Sep 10:00") && !all.contains("\n11.") && all.contains("And 2 earlier; ask for a shorter span for those."), all)
    }

    @Test fun nothingInTheSpanOrNothingAtAll() {
        assertEquals("Liquidity 15+5 has no closed paper trade in its book yet, Boss - nothing to judge. ${LiquidityRecord.NOTE}",
            LiquidityRecord.answer(LiquidityRecord.Q(), emptyList(), TODAY))
        val none = LiquidityRecord.answer(LiquidityRecord.Q(LiquidityRecord.Span.DAY, day = MonthDay.of(10, 3)), rows, TODAY)
        assertTrue(none.startsWith("Liquidity 15+5 closed no paper trade on 3 Oct, Boss. Its book has 5 closed trades, the last on 5 Oct (3 won, +Rs 4,125 a lot in all)."), none)
        val month = LiquidityRecord.answer(LiquidityRecord.Q(LiquidityRecord.Span.LAST_MONTH), rows.filter { it.day.monthValue == 10 }, TODAY)
        assertTrue(month.startsWith("Liquidity 15+5 closed no paper trade last month (September), Boss."), month)
        val yesterday = LiquidityRecord.answer(LiquidityRecord.Q(LiquidityRecord.Span.YESTERDAY), rows, TODAY)
        assertTrue(yesterday.startsWith("Boss, Liquidity 15+5's paper record on 5 Oct: 1 trade"), yesterday)
        val week = LiquidityRecord.answer(LiquidityRecord.Q(LiquidityRecord.Span.THIS_WEEK), rows, TODAY)
        assertTrue(week.startsWith("Boss, Liquidity 15+5's paper record this week (from 5 Oct): 1 trade"), week)
        val thisMonth = LiquidityRecord.answer(LiquidityRecord.Q(LiquidityRecord.Span.THIS_MONTH), rows, TODAY)
        assertTrue(thisMonth.startsWith("Boss, Liquidity 15+5's paper record this month (from 1 Oct): 3 trades"), thisMonth)
    }

    @Test fun theStreakAgainstWhatTheBacktestMakesNormal() {
        val a = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.STREAK), rows, TODAY)
        assertTrue(a.startsWith("Boss, Liquidity 15+5's streak: it is on 2 wins in a row (the last on 5 Oct). Its longest over 5 closed trades: 2 wins and 2 losses in a row."), a)
        val chance = LiquidityRecord.lossRunChance(5, 2, 1 - ForwardCheck.LIQUIDITY.winRate)
        assertTrue(a.contains("At the backtest's 41% win rate, a run of 2 or more losses within 5 trades comes in ${Math.round(100 * chance)}% of runs - a normal run for it."), a)
        // A long losing run, rare at that win rate.
        val losing = LiquidityRecord.rows((1..9).map { i -> t("liquidity5", LocalDate.of(2026, 9, i), 10, 0, 300.0, if (i <= 2) 330.0 else 285.0, "index_stop") })
        val l = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.STREAK), losing, TODAY)
        assertTrue(l.contains("it is on 7 losses in a row") && l.contains("rarer than usual for it"), l)
        // One trade: no run to weigh. A span named: its tally too.
        val one = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.STREAK), rows.take(1), TODAY)
        assertTrue(one.contains("its last trade won (29 Sep)") && !one.contains("At the backtest"), one)
        val week = LiquidityRecord.answer(LiquidityRecord.Q(LiquidityRecord.Span.LAST_WEEK, LiquidityRecord.Focus.STREAK), rows, TODAY)
        assertTrue(week.contains("\nBoss, Liquidity 15+5's paper record last week"), week)
    }

    @Test fun byIndexByExitAndTheBestAndWorst() {
        val idx = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.BY_INDEX), rows, TODAY)
        assertTrue(idx.contains("By index: BankNifty 4 trades (3 won), +Rs 4,835 a lot, +Rs 1,209 a trade; FinNifty 1 trade (0 won), -Rs 710 a lot"), idx)
        assertTrue(idx.contains("By book: BankNifty 15-min 3 trades (3 won), +Rs 5,420 a lot; BankNifty 5-min 1 trade (0 won), -Rs 585 a lot; FinNifty 5-min 1 trade (0 won), -Rs 710 a lot."), idx)
        assertTrue(idx.contains("Each index has fewer than 20 trades here: too few to say one works better."), idx)
        val many = LiquidityRecord.rows((1..22).map { i -> t("liquidity15", LocalDate.of(2026, 8, 1).plusDays(i.toLong()), 10, 0, 300.0, 310.0, "time_stop") })
        val enough = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.BY_INDEX), many, TODAY)
        assertTrue(!enough.contains("too few") && enough.contains("The research pins no split by index"), enough)

        val ex = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.BY_EXIT), rows, TODAY)
        assertTrue(ex.contains("next liquidity level 2 (2 won), +Rs 5,130 a lot - 67% of its wins vs 52% in the research"), ex)
        assertTrue(ex.contains("index stop 1 (0 won), -Rs 585 a lot - 50% of its losses vs 30% in the research"), ex)
        assertTrue(ex.contains("time stop 1 (1 won), +Rs 290 a lot - 33% of its wins vs 24% in the research"), ex)
        assertTrue(ex.contains("5 trades are too few to judge its exits against the research (it takes 20)."), ex)
        // An exit the research never booked: no share to set it against.
        val mine = LiquidityRecord.rows(listOf(t("liquidity15", d29, 10, 0, 300.0, 310.0, "closed_by_you")))
        assertTrue(LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.BY_EXIT), mine, TODAY).contains("By exit: closed by you 1 (1 won), +Rs 290 a lot."))

        val bw = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.BEST_WORST), rows, TODAY)
        assertTrue(bw.contains("Best: 1 Oct 13:00 BankNifty 15-min CE: +Rs 4,140 a lot") && bw.contains("Worst: 1 Oct 10:30 FinNifty 5-min CE: -Rs 710 a lot"), bw)
        val single = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.BEST_WORST), rows.take(1), TODAY)
        assertTrue(single.contains("Best: 29 Sep") && !single.contains("Worst:"), single)
    }

    @Test fun theSizeWordsAreTheResearchBuckets() {
        fun said(perLot: Double): String {
            val exit = 300.0 + (perLot + 60.0) / 35.0
            val r = LiquidityRecord.rows(listOf(t("liquidity15", d29, 10, 0, 300.0, exit, "time_stop")))
            assertTrue(abs(r[0].perLot - perLot) < 1e-6)
            return LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.BEST_WORST), r, TODAY)
        }
        assertTrue(said(2_000.0).contains("a good win, in the top quarter of its wins in the research"))
        assertTrue(said(689.0).contains("a normal win for it"))
        assertTrue(said(120.0).contains("a small win"))
        assertTrue(said(-200.0).contains("a small loss"))
        assertTrue(said(-554.0).contains("a normal loss for it"))
        assertTrue(said(-1_000.0).contains("larger than its usual loss, in the worst quarter in the research"))
        assertTrue(said(-1_600.0).contains("a big loss, among the worst 10% in the research"))
        assertTrue(said(-0.2).contains("Rs 0 a lot"))
    }

    @Test fun onTrackIsTheForwardChecksVerdict() {
        // Before 06 Oct the trades ran under the older rules: none is a forward trade yet.
        val none = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.ON_TRACK), rows, TODAY)
        assertTrue(none.startsWith("Boss, is Liquidity 15+5 on track? Live vs backtest (its forward trades since 6 Oct): no forward trade yet."), none)
        assertTrue(none.lines()[1].startsWith("Liquidity 15+5's paper record so far: 5 trades, 3 won and 2 lost"), none)
        // Three forward trades: too few, said plainly.
        val three = LiquidityRecord.rows(book + (0..2).map { i -> t("liquidity15", d6, 10 + i, 0, 300.0, 310.0, "time_stop") })
        val few = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.ON_TRACK), three, TODAY)
        assertTrue(few.contains("too few trades (<20) - 3 trades, +Rs 290 a lot a trade vs the expected +Rs 228 ± Rs 2,384. Too few to say either way yet."), few)
        // 25 forward trades losing Rs 2,000 a lot each: below what the backtest allows, and the CUSUM's sustained shortfall.
        val bad = LiquidityRecord.rows((0 until 25).map { i ->
            t("liquidity15", d6.plusDays((i / 3).toLong()), 10 + i % 3, 0, 300.0, 300.0 - 1940.0 / 35.0, "index_stop") })
        val b = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.ON_TRACK), bad, LocalDate.of(2026, 10, 20))
        assertTrue(b.contains("below expectation (25 trades) - 25 trades, -Rs 2,000 a lot a trade vs the expected +Rs 228 ± Rs 826; a sustained run below the backtest since trade "), b)
        assertTrue(!b.contains("Too few to say"), b)
        assertTrue(b.contains("Against the research: -Rs 2,000 a lot a trade and 0% won - below what the backtest allows for 25 trades (-Rs 598 at least)"), b)
        // In line, and above.
        val ok = LiquidityRecord.rows((0 until 25).map { i -> t("liquidity15", d6.plusDays((i / 3).toLong()), 10 + i % 3, 0, 300.0, 300.0 + (228.0 + 60.0) / 35.0, "time_stop") })
        val o = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.ON_TRACK), ok, LocalDate.of(2026, 10, 20))
        assertTrue(o.contains("in line - 25 trades, +Rs 228 a lot a trade") && o.contains("within what the backtest allows for 25 trades (-Rs 598 to +Rs 1,053)"), o)
        val great = LiquidityRecord.rows((0 until 25).map { i -> t("liquidity15", d6.plusDays((i / 3).toLong()), 10 + i % 3, 0, 300.0, 300.0 + 2060.0 / 35.0, "next_liquidity") })
        val g = LiquidityRecord.answer(LiquidityRecord.Q(focus = LiquidityRecord.Focus.ON_TRACK), great, LocalDate.of(2026, 10, 20))
        assertTrue(g.contains("above expectation") && g.contains("above what the backtest allows for 25 trades (+Rs 1,053 at most)"), g)
        assertTrue(listOf(none, few, b, o, g).none { ADVICE.containsMatchIn(it) })
    }

    @Test fun theQuestion() {
        val cases = mapOf(
            "how did liquidity do this week" to LiquidityRecord.Q(LiquidityRecord.Span.THIS_WEEK),
            "how is liquidity doing this week" to LiquidityRecord.Q(LiquidityRecord.Span.THIS_WEEK),
            "liquidity ne is hafte kaisa kiya" to LiquidityRecord.Q(LiquidityRecord.Span.THIS_WEEK),
            "how did liquidity do last week" to LiquidityRecord.Q(LiquidityRecord.Span.LAST_WEEK),
            "pichle hafte liquidity ne kitna kamaya" to LiquidityRecord.Q(LiquidityRecord.Span.LAST_WEEK),
            "liquidity this month" to LiquidityRecord.Q(LiquidityRecord.Span.THIS_MONTH),
            "how did liquidity 15+5 do last month" to LiquidityRecord.Q(LiquidityRecord.Span.LAST_MONTH),
            "how did liquidity do yesterday" to LiquidityRecord.Q(LiquidityRecord.Span.YESTERDAY),
            "liquidity on 3 Oct" to LiquidityRecord.Q(LiquidityRecord.Span.DAY, day = MonthDay.of(10, 3)),
            "how did liquidity do on October 3rd" to LiquidityRecord.Q(LiquidityRecord.Span.DAY, day = MonthDay.of(10, 3)),
            "liquidity 29th of september" to LiquidityRecord.Q(LiquidityRecord.Span.DAY, day = MonthDay.of(9, 29)),
            "liquidity last 10 trades" to LiquidityRecord.Q(LiquidityRecord.Span.LAST_N, n = 10),
            "liquidity's last ten trades" to LiquidityRecord.Q(LiquidityRecord.Span.LAST_N, n = 10),
            "liquidity ke last 5 trades" to LiquidityRecord.Q(LiquidityRecord.Span.LAST_N, n = 5),
            "liquidity last trade" to LiquidityRecord.Q(LiquidityRecord.Span.LAST_N, n = 1),
            "which index works best for liquidity" to LiquidityRecord.Q(focus = LiquidityRecord.Focus.BY_INDEX),
            "liquidity kis index pe accha chalta hai" to LiquidityRecord.Q(focus = LiquidityRecord.Focus.BY_INDEX),
            "banknifty or finnifty for liquidity" to LiquidityRecord.Q(focus = LiquidityRecord.Focus.BY_INDEX),
            "liquidity win streak" to LiquidityRecord.Q(focus = LiquidityRecord.Focus.STREAK),
            "liquidity ki streak" to LiquidityRecord.Q(focus = LiquidityRecord.Focus.STREAK),
            "is liquidity on track" to LiquidityRecord.Q(focus = LiquidityRecord.Focus.ON_TRACK),
            "liquidity track pe hai kya" to LiquidityRecord.Q(focus = LiquidityRecord.Focus.ON_TRACK),
            "is liquidity in line with research" to LiquidityRecord.Q(focus = LiquidityRecord.Focus.ON_TRACK),
            "liquidity by exit reason" to LiquidityRecord.Q(focus = LiquidityRecord.Focus.BY_EXIT),
            "liquidity best trade" to LiquidityRecord.Q(focus = LiquidityRecord.Focus.BEST_WORST),
            "liquidity worst trade last week" to LiquidityRecord.Q(LiquidityRecord.Span.LAST_WEEK, LiquidityRecord.Focus.BEST_WORST),
            "liquidity record" to LiquidityRecord.Q(),
            "liquidity ka record" to LiquidityRecord.Q(),
            "liquidity performance" to LiquidityRecord.Q(),
            "how has liquidity done so far" to LiquidityRecord.Q(),
            "how much has liquidity made" to LiquidityRecord.Q(),
            "liquidity win rate" to LiquidityRecord.Q(),
        )
        for ((s, want) in cases) assertEquals(want, LiquidityRecord.asked(s), s)
        // Not the record: the arm's own answers today, its levels, size, health, switch, why a trade went so, the backtest
        // alone, a forecast, a definition, nothing about liquidity at all.
        for (s in listOf("how is liquidity doing", "how did liquidity do today", "liquidity trades today", "liquidity levels", "where are the liquidity levels",
            "how many lots does liquidity trade", "is liquidity healthy", "liquidity bot health", "why did the liquidity bot exit",
            "should i switch off liquidity", "liquidity backtest record", "liquidity live vs backtest", "what is a liquidity pool",
            "will liquidity do well tomorrow", "how did liquidity fare on sessions like today", "liquidity ko 3 lot karo",
            "how did my bots do this week", "weekly review", "my weekly review", "my record this week", "liquidity on 31 feb"))
            assertNull(LiquidityRecord.asked(s), s)
    }
}
