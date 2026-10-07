package com.optionslab.ira

import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LotsWhatIfTest {
    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|consider|go (for|with)|switch to|increase|raise|more lots|bigger size|worth it|better to|safe to)\\b")
    private val TODAY: LocalDate = LocalDate.of(2026, 10, 6)   // a Tuesday

    /** A closed trade of [source] entered on [day] at [h]:[m], [lots] lots of [lot], held 15 minutes. */
    private fun t(source: String, day: LocalDate, h: Int, m: Int, entry: Double, exit: Double?, why: String, lots: Int = 2, lot: Int? = 35,
                  charges: Double = 100.0, live: Boolean = false): BotTrades.Trade {
        val at = LocalDateTime.of(day, java.time.LocalTime.of(h, m))
        val qty = lots * (lot ?: 35)
        return BotTrades.Trade(source, "BANKNIFTY26OCT56000CE", "CE", qty, entry, at, at.minusMinutes(5), exit, exit?.let { at.plusMinutes(15) },
            exit?.let { why }, charges, live, lot = lot)
    }

    private val d29 = LocalDate.of(2026, 9, 29); private val d30 = LocalDate.of(2026, 9, 30)
    private val d1 = LocalDate.of(2026, 10, 1); private val d5 = LocalDate.of(2026, 10, 5)

    /** Last week: +30, -15 (two trades on 30 Sep), FinNifty -10 a unit, +120; this week: +10. All at 2 lots as booked. */
    private val book = listOf(
        t("liquidity15", d29, 10, 0, 300.0, 330.0, "next_liquidity"),
        t("liquidity5", d30, 11, 0, 300.0, 285.0, "index_stop"),
        t("liquidity15", d30, 13, 0, 300.0, 290.0, "failed_break"),
        t("liquidity5_fin", d1, 10, 30, 100.0, 90.0, "failed_break", lot = 65),
        t("liquidity15", d1, 13, 0, 300.0, 420.0, "next_liquidity"),
        t("liquidity15", d5, 10, 0, 300.0, 310.0, "time_stop"),
        // Not the arm's: another arm, a live trade, a trade still open.
        t("orb", d30, 9, 35, 200.0, 260.0, "target"),
        t("liquidity15", d30, 12, 0, 300.0, 400.0, "next_liquidity", live = true),
        t("liquidity5", d5, 13, 0, 300.0, null, ""),
    )
    private val rows = LiquidityRecord.rows(book)

    private fun near(want: Double, got: Double, tol: Double = 0.006) = assertTrue(kotlin.math.abs(want - got) <= tol, "want $want, got $got")

    // ---- the charges: the sandbox's own F&O schedule, against figures worked by hand ----------------------------------

    @Test fun chargesAreTheSandboxSchedulesForTheQuantity() {
        // 1 lot of 35 bought at 300: 20 + exchange 3.73065 + SEBI 0.0105 + stamp 0.315 + GST 4.273317 = 28.33.
        near(28.33, SandboxCosts.charge("BUY", BigDecimal(300.0), 35).toDouble())
        // ...sold at 330: 20 + STT 17.325 + exchange 4.103715 + SEBI 0.01155 + GST 4.340748 = 45.78.
        near(45.78, SandboxCosts.charge("SELL", BigDecimal(330.0), 35).toDouble())
        near(74.11, LotsWhatIf.charges(300.0, 330.0, 35))
        // 3 lots (105): buy 37.79 (20 + 11.19195 + 0.0315 + 0.945 + 5.620221), sell 90.14 (20 + 51.975 + 12.311145 + 0.03465 + 5.822243).
        near(127.93, LotsWhatIf.charges(300.0, 330.0, 105))
        // Brokerage (with its GST) is flat an order: 3 lots pay 2 x Rs 47.20 less than three 1-lot trades.
        near(2 * LotsWhatIf.FLAT_PER_TRADE, 3 * LotsWhatIf.charges(300.0, 330.0, 35) - LotsWhatIf.charges(300.0, 330.0, 105), 0.03)
        near(47.2, LotsWhatIf.FLAT_PER_TRADE, 1e-9)
        // So the charges a lot fall with more lots: 74.11 at 1 lot, 42.64 at 3.
        assertTrue(LotsWhatIf.charges(300.0, 330.0, 105) / 3 < LotsWhatIf.charges(300.0, 330.0, 70) / 2)
        assertTrue(LotsWhatIf.charges(300.0, 330.0, 70) / 2 < LotsWhatIf.charges(300.0, 330.0, 35))
    }

    @Test fun eachTradeIsRecomputedAtTheSize() {
        assertEquals(35, LotsWhatIf.unitsPerLot(rows[0]))
        assertEquals(65, LotsWhatIf.unitsPerLot(rows[3]))
        near(1050.0 - 74.11, LotsWhatIf.netAt(rows[0], 1))
        near(3150.0 - 127.93, LotsWhatIf.netAt(rows[0], 3))
        near(-650.0 - LotsWhatIf.charges(100.0, 90.0, 65), LotsWhatIf.netAt(rows[3], 1))
        // A trade whose lot is not known counts its quantity as one lot.
        val unknown = LiquidityRecord.rows(listOf(t("liquidity15", d5, 11, 0, 300.0, 310.0, "time_stop", lots = 1, lot = null)))[0]
        assertEquals(35, LotsWhatIf.unitsPerLot(unknown))
        near(700.0 - LotsWhatIf.charges(300.0, 310.0, 70), LotsWhatIf.netAt(unknown, 2))
    }

    @Test fun theFiguresAtASize() {
        val f = LotsWhatIf.at(rows, 3)
        val nets = rows.map { LotsWhatIf.netAt(it, 3) }
        assertEquals(6, f.trades)
        near(nets.sum(), f.net)
        near(rows.sumOf { LotsWhatIf.chargesAt(it, 3) }, f.charges)
        // The worst trade is FinNifty's -10 on 195 units; the drawdown runs from the first trade's high through the three losers.
        assertEquals(rows[3], f.worst!!.first)
        near(nets[1] + nets[2] + nets[3], f.drawdown)
        // The worst day: 30 Sep's two losers, -15 and -10 on 105 units, less their charges (FinNifty's day nets +).
        assertEquals(d30, f.worstDay!!.first)
        near(nets[1] + nets[2], f.worstDay!!.second)
        // As booked: the book's own nets and charges, at the 2 lots it traded.
        val b = LotsWhatIf.booked(rows)
        assertEquals(2, b.lots)
        near(rows.sumOf { it.net }, b.net)
        near(600.0, b.charges)
        assertEquals("at 2 lots each", LotsWhatIf.usedWords(rows))
        val mixed = LiquidityRecord.rows(book.take(2) + t("liquidity15", d5, 10, 0, 300.0, 310.0, "time_stop", lots = 1))
        assertEquals("at 2 lots on 2, 1 lot on 1", LotsWhatIf.usedWords(mixed))
        assertNull(LotsWhatIf.booked(mixed).lots)
    }

    @Test fun theResearchScaled() {
        // 3 x Rs 227.66 + 2 x Rs 47.20 flat brokerage not paid again; 3 x the backtest's -Rs 29,726.34.
        near(777.38, LotsWhatIf.expectedPerTrade(3), 1e-6)
        near(227.66, LotsWhatIf.expectedPerTrade(1), 1e-9)
        near(-89_179.02, LotsWhatIf.backtestWorst(3), 1e-6)
    }

    // ---- the answer ---------------------------------------------------------------------------------------------------

    @Test fun oneSize() {
        val a = LotsWhatIf.answer(LotsWhatIf.Q(listOf(3), LiquidityRecord.Q(LiquidityRecord.Span.LAST_WEEK)), rows, TODAY)
        assertTrue(a.startsWith("Boss, Liquidity 15+5 at 3 lots last week (28 Sep - 4 Oct): 5 closed paper trades, traded at 2 lots each."), a)
        assertTrue(a.contains("\nAs booked: net "), a)
        assertTrue(a.contains("\nAt 3 lots: net "), a)
        assertTrue(a.contains("worst trade " + LiquidityRecord.rs(LotsWhatIf.netAt(rows[3], 3)) + " (1 Oct 10:30 FinNifty 5-min CE)"), a)
        assertTrue(a.contains("worst day 30 Sep"), a)
        assertTrue(a.contains("Against as booked: "), a)
        assertTrue(a.contains("5 trades are too few to read much from"), a)
        assertTrue(a.contains("at 3 lots about +Rs 777 a trade"), a)
        assertTrue(a.contains("at 3 lots the backtest's worst run would have been about -Rs 89,179"), a)
        assertTrue(a.contains("Risk: I don't know your capital, so I can't say what share of it a -Rs 89,179 run would be"), a)
        assertTrue(a.endsWith(LotsWhatIf.END), a)
        assertTrue(!ADVICE.containsMatchIn(a), a)
    }

    @Test fun twoSizesSideBySide() {
        val a = LotsWhatIf.answer(LotsWhatIf.Q(listOf(2, 3)), rows, TODAY)
        assertTrue(a.startsWith("Boss, Liquidity 15+5 at 2 lots vs 3 lots so far: 6 closed paper trades"), a)
        assertTrue(a.contains("\nAt 2 lots: ") && a.contains("\nAt 3 lots: "), a)
        assertTrue(a.contains("\n3 lots against 2 lots: "), a)
        assertTrue(a.contains("deeper"), a)
        assertTrue(a.contains("brokerage is flat an order"), a)
        assertTrue(a.contains("at 2 lots about") && a.contains("at 3 lots about"), a)
        assertTrue(a.endsWith(LotsWhatIf.END), a)
        assertTrue(!ADVICE.containsMatchIn(a), a)
    }

    @Test fun nothingToWorkOn() {
        val empty = LotsWhatIf.answer(LotsWhatIf.Q(listOf(1)), emptyList(), TODAY)
        assertTrue(empty.startsWith("Liquidity 15+5 has no closed paper trade in its book yet, Boss"), empty)
        assertTrue(empty.contains("at 1 lot +Rs 228 a trade") && empty.endsWith(LotsWhatIf.END), empty)
        val none = LotsWhatIf.answer(LotsWhatIf.Q(listOf(2), LiquidityRecord.Q(LiquidityRecord.Span.THIS_MONTH)), rows.take(3), TODAY)
        assertTrue(none.startsWith("Liquidity 15+5 closed no paper trade this month (from 1 Oct), Boss"), none)
        val big = LotsWhatIf.answer(LotsWhatIf.Q(listOf(5)), rows, TODAY)
        assertTrue(big.contains("(The arm's Lots setting offers 1, 2 or 3; more here is arithmetic only.)"), big)
        val bad = LotsWhatIf.answer(LotsWhatIf.Q(listOf(25)), rows, TODAY)
        assertTrue(bad.startsWith("I work a what-if for 1 to 10 lots, Boss") && bad.endsWith(LotsWhatIf.END), bad)
        assertEquals("Unlock the phone for that, Boss.", LotsWhatIf.LOCKED)
    }

    // ---- the question --------------------------------------------------------------------------------------------------

    @Test fun theQuestion() {
        assertEquals(LotsWhatIf.Q(listOf(3)), LotsWhatIf.asked("what if liquidity traded 3 lots"))
        assertEquals(LotsWhatIf.Q(listOf(1), LiquidityRecord.Q(LiquidityRecord.Span.THIS_WEEK)), LotsWhatIf.asked("how much with 1 lot this week"))
        assertEquals(LotsWhatIf.Q(listOf(3)), LotsWhatIf.asked("3 lot pe kitna banta"))
        assertEquals(LotsWhatIf.Q(listOf(2, 3)), LotsWhatIf.asked("is 2 lots better than 3"))
        assertEquals(LotsWhatIf.Q(listOf(3, 2)), LotsWhatIf.asked("3 lots instead of 2 for liquidity"))
        assertEquals(LotsWhatIf.Q(listOf(1, 2), LiquidityRecord.Q(LiquidityRecord.Span.THIS_MONTH)), LotsWhatIf.asked("liquidity 1 lot vs 2 lots this month"))
        assertEquals(LotsWhatIf.Q(listOf(2), LiquidityRecord.Q(LiquidityRecord.Span.LAST_N, n = 10)),
            LotsWhatIf.asked("what if liquidity had traded 2 lots over the last 10 trades"))
        assertEquals(LotsWhatIf.Q(listOf(3), LiquidityRecord.Q(LiquidityRecord.Span.LAST_WEEK)), LotsWhatIf.asked("how much would 3 lots have made last week"))
        assertEquals(LotsWhatIf.Q(listOf(3)), LotsWhatIf.asked("teen lot pe liquidity kitna banta"))
        // Never a change, the size now, a budget, advice, another arm or Boss's own trades.
        for (s in listOf("set liquidity to 2 lots", "liquidity ko 3 lot karo", "liquidity lots", "how many lots is liquidity trading", "increase lots",
            "change lots to 3", "how many lots can i buy with 20000", "should liquidity trade 3 lots", "what if i traded 3 lots",
            "what if hero traded 2 lots", "buy 2 lots of nifty", "is liquidity trading 2 lots", "liquidity 3 lots", "3 lots", "what if nifty falls 1%",
            "what if liquidity traded more", "how did liquidity do this week"))
            assertNull(LotsWhatIf.asked(s), s)
    }
}
