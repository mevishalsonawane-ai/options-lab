package com.optionslab.ira

import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.WidgetLiquidity.Facts
import com.optionslab.ira.WidgetLiquidity.Open
import com.optionslab.ira.WidgetLiquidity.State
import com.optionslab.ira.WidgetLiquidity.Tone
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Liquidity 15+5's row on the home-screen widget: the arm's state, its open position or today's paper result, next trigger. */
class WidgetLiquidityTest {
    private val day: LocalDate = LocalDate.of(2026, 10, 7)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)

    private fun read(und: String, minutes: Int, price: Double, up: Double?, down: Double?, upTarget: Double? = null, downTarget: Double? = null): LiquidityMap.Read {
        fun side(s: Int, edge: Double?, target: Double?): LiquidityMap.Side {
            if (edge == null) return LiquidityMap.Side(s, null, null, null, null, false)
            val room = target?.let { s * (it - edge) }
            val enough = LiquidityRules.hasRoom(LiquidityRules.Signal(s, edge, target), edge, und)
            val lv = LiquidityMap.Level("pool", s, edge, onSwing = true)
            return LiquidityMap.Side(s, lv, lv, target, room, enough)
        }
        return LiquidityMap.Read(und, minutes, LiquidityMap.State.OK, price, at(11, 0), at(10, 55), side(1, up, upTarget), side(-1, down, downTarget),
            entryOpen = true, bars = 60)
    }

    private val call = Open("BANKNIFTY", "CE", "BANKNIFTY26OCT54000CE", live = false, qty = 70, entry = 120.0, ltp = 140.0,
        level = 54_000.0, target = 54_200.0, index = 54_120.0, indexAt = at(11, 0))

    @Test fun armedAndFlatSaysItsLotsAndTodaysPaperResult() {
        val none = WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, lots = 2), figures = true)
        assertEquals("Liquidity: armed 2 lots · no trades yet", none.line)
        assertEquals(Tone.PLAIN, none.tone)
        val won = WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, lots = 1, paperTrades = 3, paperNet = 1_240.4), figures = true)
        assertEquals("Liquidity: armed 1 lot · 3 paper trades +₹1,240 net", won.line)
        assertEquals(Tone.GAIN, won.tone)
        val lost = WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, paperTrades = 1, paperNet = -310.0), figures = true)
        assertEquals("Liquidity: armed · 1 paper trade −₹310 net", lost.line)
        assertEquals(Tone.LOSS, lost.tone)
    }

    @Test fun offAndStoppedForToday() {
        assertEquals("Liquidity: off", WidgetLiquidity.row(Facts(at(11, 0), State.OFF, lots = 2), true).line)
        assertEquals("Liquidity: stopped for today · 2 paper trades −₹1,000 net",
            WidgetLiquidity.row(Facts(at(11, 0), State.STOPPED, lots = 2, paperTrades = 2, paperNet = -1_000.0), true).line)
        assertNull(WidgetLiquidity.row(Facts(at(11, 0), State.STOPPED, reads = listOf(read("BANKNIFTY", 5, 54_120.0, 54_180.0, null))), true).detail,
            "no next trigger while stopped")
    }

    @Test fun rupeesAreHiddenUnlessTheOwnerShowsFigures() {
        val r = WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, lots = 2, paperTrades = 3, paperNet = 1_240.0), figures = false)
        assertEquals("Liquidity: armed 2 lots · 3 paper trades", r.line)
        assertEquals(Tone.PLAIN, r.tone)
        val o = WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, open = call), figures = false)
        assertEquals("Liquidity: CE open · stop 150 · target 80 pts", o.line)
        assertFalse(o.line.contains("₹") || o.detail!!.contains("₹"))
        // The percent change says how the account is doing: left out with the rupees (the prices stay).
        assertEquals("BANKNIFTY26OCT54000CE 140.00 vs 120.00", o.detail)
        assertEquals(Tone.PLAIN, o.tone)
        // A live position: the word "LIVE" is left out too.
        val live = WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, open = call.copy(live = true)), figures = false)
        assertEquals("Liquidity: CE open · stop 150 · target 80 pts", live.line)
        assertFalse(live.line.contains("LIVE") || live.detail!!.contains("%"))
        assertTrue(WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, open = call.copy(live = true)), figures = true).line.startsWith("Liquidity: LIVE CE"))
    }

    @Test fun aReadFromAnotherDayOrTooLongAgoInMarketHoursIsNotShown() {
        val f = Facts(at(11, 0), State.ARMED, lots = 2)
        assertTrue(WidgetLiquidity.fresh(f, at(11, 10), marketOpen = true))
        assertFalse(WidgetLiquidity.fresh(f, at(11, 11), marketOpen = true), "over 10 minutes old while the market is open")
        assertTrue(WidgetLiquidity.fresh(f, at(20, 0), marketOpen = false), "the market shut: today's read stands")
        assertFalse(WidgetLiquidity.fresh(f, at(11, 0).plusDays(1), marketOpen = false), "another day's read is never shown")
        assertFalse(WidgetLiquidity.fresh(f, at(9, 30).plusDays(1), marketOpen = true))
    }

    @Test fun anOpenPositionSaysItsPnlAndHowFarTheIndexStopAndTargetAre() {
        val r = WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, lots = 2, paperTrades = 1, open = call), figures = true)
        // Index stop for a BankNifty call: 54,000 − 30 = 53,970, so 150 pts below 54,120; target 54,200 is 80 pts up.
        assertEquals("Liquidity: CE +₹1,400 · stop 150 · target 80 pts", r.line)
        assertEquals("BANKNIFTY26OCT54000CE 140.00 vs 120.00 (+16.7%)", r.detail)
        assertEquals(Tone.GAIN, r.tone)

        val put = call.copy(right = "PE", symbol = "BANKNIFTY26OCT54000PE", live = true, ltp = 100.0, level = 54_100.0, target = 53_800.0, index = 54_140.0)
        val p = WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, open = put), true)
        // Put: stop 54,130, index 54,140 is past it; target 53,800 is 340 below.
        assertEquals("Liquidity: LIVE PE −₹1,400 · stop passed · target 340 pts", p.line)
        assertEquals(Tone.LOSS, p.tone)
        assertEquals("BANKNIFTY26OCT54000PE 100.00 vs 120.00 (−16.7%)", p.detail)
    }

    @Test fun anOpenPositionWithoutPricesOrLevels() {
        val bare = call.copy(ltp = null, index = null)
        val r = WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, open = bare), true)
        assertEquals("Liquidity: CE · index not read", r.line)
        assertEquals("BANKNIFTY26OCT54000CE bought at 120.00 · no price yet", r.detail)
        assertEquals("Liquidity: CE +₹1,400 · index not read",
            WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, open = call.copy(indexAt = at(11, 0).minusDays(1))), true).line, "an earlier day's index is no price for now")
        assertEquals("Liquidity: CE +₹1,400 · no target",
            WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, open = call.copy(level = null, target = null)), true).line)
        assertEquals("Liquidity: CE +₹1,400 · stop 180 pts · target reached",
            WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, open = call.copy(index = 54_150.0, target = 54_100.0)), true).line)
    }

    @Test fun whenFlatAndArmedTheNextTriggerWithRoom() {
        val reads = listOf(
            read("BANKNIFTY", 15, 54_120.0, 54_180.0, 53_900.0),
            read("FINNIFTY", 5, 25_500.0, null, 25_470.0),
        )
        val r = WidgetLiquidity.row(Facts(at(11, 0), State.ARMED, lots = 1, reads = reads), true)
        assertEquals("Next: FinNifty 5-min close below 25,470 · 30 pts away", r.detail)
        assertNull(WidgetLiquidity.row(Facts(at(14, 30), State.ARMED, reads = reads), true).detail, "after the last entry: no next")
        assertNull(WidgetLiquidity.row(Facts(at(11, 0), State.OFF, reads = reads), true).detail)
        assertNull(WidgetLiquidity.row(Facts(at(11, 0), State.ARMED), true).detail, "levels not read: nothing said")
        assertNull(WidgetLiquidity.nextLine(listOf(LiquidityMap.Read("BANKNIFTY", 5, LiquidityMap.State.LOADING))))
    }

    @Test fun theSecondLineNeedsATallEnoughWidget() {
        assertFalse(WidgetLiquidity.detailFits(0))
        assertFalse(WidgetLiquidity.detailFits(WidgetLiquidity.DETAIL_MIN_DP - 1))
        assertTrue(WidgetLiquidity.detailFits(WidgetLiquidity.DETAIL_MIN_DP))
    }
}
