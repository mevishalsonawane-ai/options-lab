package com.optionslab.engine.risk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixes 2 and 4 (08 Oct, research/PROFIT_LOCK_8OCT.md): no automatic buy of a thin option, no paper entry on a stale price. */
class ThinOptionTest {
    private val now = 1_760_000_000L - Math.floorMod(1_760_000_000L, 60L) + 30   // 30 s into a minute
    private val minute = now - 30

    /** The last [n] minutes up to the one forming now, each with [lots] lots of [lot] units (0 = no trade that minute). */
    private fun minutes(vararg lots: Int, lot: Int = 65): List<Pair<Long, Long>> =
        lots.mapIndexed { i, l -> minute - (lots.size - 1 - i) * 60L to l.toLong() * lot }

    @Test fun aBusyOptionIsBought() {
        assertNull(ThinOption.refusal(minutes(500, 800, 650, 900, 700, 300), now, 65, 300.0, 300.5))
        assertNull(ThinOption.refusal(minutes(500, 800, 650, 900, 700, 300), now, 65, null, null))
    }

    @Test fun anOptionThatBarelyTradesIsSkipped() {
        // FINNIFTY's monthly on 8 Oct: a few lots a minute, whole minutes with no trade.
        val why = ThinOption.refusal(minutes(3, 0, 0, 12, 0, 2, lot = 60), now, 60, null, null)!!
        assertEquals("it barely trades: 3 of the last 6 minutes traded, 17 lots in all (needs 4 minutes and 25 lots)", why)
        // Busy enough minutes, too few lots.
        assertNotNull(ThinOption.refusal(minutes(5, 5, 5, 5, 4, 0), now, 65, null, null))
        // Enough lots, but in too few minutes.
        assertNotNull(ThinOption.refusal(minutes(0, 0, 0, 200, 200, 200), now, 65, null, null))
        assertEquals("it barely trades: 0 of the last 6 minutes traded, 0 lots in all (needs 4 minutes and 25 lots)",
            ThinOption.refusal(emptyList(), now, 65, null, null))
        // One lot is said as one.
        assertTrue(ThinOption.refusal(minutes(1), now, 65, null, null)!!.contains("1 lot in all"))
    }

    @Test fun onlyTheLastSixMinutesCount() {
        val old = minutes(900, 900, 900, 900, 900, 900).map { it.first - 3_600 to it.second }
        assertNotNull(ThinOption.refusal(old, now, 65, null, null), "an hour ago does not count")
        assertEquals(4 to 4_000L, ThinOption.recent(listOf(minute to 1_000L, minute - 60 to 1_000L, minute - 120 to 0L,
            minute - 240 to 1_000L, minute - 300 to 1_000L, minute - 360 to 9_000L, minute + 60 to 7L), now))
        // A lot size of zero (unknown) counts units.
        assertNull(ThinOption.refusal(minutes(30, 30, 30, 30), now, 0, null, null))
    }

    @Test fun aWideSpreadIsSkippedWhenAQuoteHasOne() {
        // 300.00 / 302.00: half the gap is 1.00 on a middle of 301, 0.33% - more than 0.3%.
        assertEquals("the gap between buyers (300.00) and sellers (302.00) is 0.33% either side of the middle (more than 0.3%)",
            ThinOption.refusal(minutes(500, 800, 650, 900, 700, 300), now, 65, 300.0, 302.0))
        assertEquals(0.3, ThinOption.MAX_HALF_SPREAD_PCT)
        // 0.3% exactly passes; a crossed or missing quote is no quote.
        assertNull(ThinOption.refusal(minutes(500, 800, 650, 900, 700, 300), now, 65, 99.7, 100.3))
        assertNull(ThinOption.halfSpreadPct(0.0, 300.0))
        assertNull(ThinOption.halfSpreadPct(301.0, 300.0))
        assertNull(ThinOption.halfSpreadPct(Double.NaN, 300.0))
        assertNull(ThinOption.halfSpreadPct(300.0, null))
        assertEquals(0.5, ThinOption.halfSpreadPct(99.5, 100.5)!!, 1e-9)
    }

    @Test fun unreadMinutesLeaveOnlyTheSpread() {
        assertNull(ThinOption.refusal(null, now, 65, 300.0, 300.4))
        assertNotNull(ThinOption.refusal(null, now, 65, 300.0, 310.0))
        assertEquals("how much it trades could not be read (no recent candles and no bid or ask)", ThinOption.refusal(null, now, 65, null, null))
    }

    @Test fun anEntryNeverFillsOnAStaleCandle() {
        val sym = "FINNIFTY27OCT2624650PE"
        // Traded in the minute before this one (it ended 30 s ago): fresh.
        assertNull(StaleEntry.refusal(listOf(minute - 60 to 60L), now, sym))
        // The last trade's minute ended exactly two minutes ago: still fresh; a second more and it is stale.
        val twoAgo = now - 120 - 60
        assertNull(StaleEntry.refusal(listOf(twoAgo to 60L), now, sym))
        assertEquals("$sym has not traded for 2 minutes and the price stream is off, so its last candle's close is stale: " +
            "no entry was filled on it", StaleEntry.refusal(listOf(twoAgo - 1 to 60L), now, sym))
        // Minutes with no trade carry no price: the last one WITH a trade counts.
        assertNotNull(StaleEntry.refusal(listOf(minute - 600 to 60L, minute - 60 to 0L, minute to 0L), now, sym))
        // A feed with no volume at all counts its last candle.
        assertNull(StaleEntry.refusal(listOf(minute - 600 to 0L, minute to 0L), now, sym))
        assertEquals(minute + 60, StaleEntry.lastTradeEnd(listOf(minute to 0L)))
        assertEquals("no trade in $sym today, so there is no price to fill an entry at", StaleEntry.refusal(emptyList(), now, sym))
        assertNull(StaleEntry.lastTradeEnd(emptyList()))
        assertTrue(StaleEntry.refusal(listOf(minute - 180 to 60L), now, sym)!!.contains("for 2 minutes"))
    }
}
