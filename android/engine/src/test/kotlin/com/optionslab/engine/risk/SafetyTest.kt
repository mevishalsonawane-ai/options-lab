package com.optionslab.engine.risk

import com.optionslab.engine.IST
import com.optionslab.engine.mcx.McxSession
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Boss's 08 Oct items 6-8: the live GTT backup, the missed-lock sweeper and the no-price failsafe. */
class SafetyTest {
    @Test fun theBackupGttRestsAtTheStopsLimitAndOnlyMovesUp() {
        // An SL at 327.50 with its limit at 311.10: the GTT waits under the limit, where the SL can no longer fill.
        assertEquals(311.1, LiveBackup.trigger(327.5, 311.1, 0.05))
        // No limit known: the bots' own (5%, at least 2 points), on the tick, rounded down.
        assertEquals(311.1, LiveBackup.trigger(327.5, null, 0.05))
        assertEquals(28.0, LiveBackup.trigger(30.0, null, 0.05))
        assertEquals(28.0, LiveBackup.trigger(30.0, 30.0, 0.05), "a limit not under the trigger is not used")
        assertNull(LiveBackup.trigger(2.0, null, 0.05), "no price left under it")
        assertNull(LiveBackup.trigger(Double.NaN, null, 0.05))
        assertNull(LiveBackup.trigger(300.0, null, 0.0))
        // Never lowered.
        assertTrue(LiveBackup.moves(null, 256.5))
        assertTrue(LiveBackup.moves(256.5, 311.1))
        assertFalse(LiveBackup.moves(311.1, 311.1))
        assertFalse(LiveBackup.moves(311.1, 256.5))
        assertFalse(LiveBackup.moves(311.1, null))
    }

    @Test fun whenOneFiresTheOtherComesOut() {
        assertEquals(LiveBackup.Step.CANCEL_STOP, LiveBackup.step("TRIGGER PENDING", "triggered"))
        assertEquals(LiveBackup.Step.CANCEL_STOP, LiveBackup.step("OPEN", "triggered"))
        assertEquals(LiveBackup.Step.DROP_GTT, LiveBackup.step("COMPLETE", "active"))
        assertEquals(LiveBackup.Step.DROP_GTT, LiveBackup.step("CANCELLED", "active"))
        assertEquals(LiveBackup.Step.DROP_GTT, LiveBackup.step(null, "active"))
        assertEquals(LiveBackup.Step.KEEP, LiveBackup.step("TRIGGER PENDING", "active"))
        assertEquals(LiveBackup.Step.KEEP, LiveBackup.step("COMPLETE", "triggered"))
        assertEquals(LiveBackup.Step.KEEP, LiveBackup.step("trigger pending", null))
    }

    @Test fun aLockThePriceIsAlreadyUnderIsSoldNow() {
        assertTrue(MissedLock.missed(320.0, 327.5))
        assertTrue(MissedLock.missed(327.5, 327.5))
        assertFalse(MissedLock.missed(327.55, 327.5))
        assertFalse(MissedLock.missed(null, 327.5))
        assertFalse(MissedLock.missed(320.0, null))
        assertFalse(MissedLock.missed(Double.NaN, 327.5))
        assertFalse(MissedLock.missed(0.0, 327.5))
        assertFalse(MissedLock.missed(320.0, Double.NaN))
        assertEquals(310.4, MissedLock.limit(320.0, 0.05), 1e-9)
        assertEquals(19.0, MissedLock.limit(20.0, 0.05), 1e-9)
        assertEquals(0.05, MissedLock.limit(0.5, 0.05), 1e-9)
        assertEquals("Lock missed at 327.50, sold at 320.00", MissedLock.say(327.5, 320.0))
        assertEquals("BANKNIFTY27OCT2654900PE: Lock missed at 799.30, sold at 798.55", MissedLock.say(799.3, 798.55, "BANKNIFTY27OCT2654900PE"))
    }

    @Test fun noPricesForTwoMinutesIsALoudWarning() {
        val t0 = 1_000_000L
        assertTrue(PriceWatch.useRest(null, t0))
        assertFalse(PriceWatch.useRest(t0 - 30_000, t0))
        assertTrue(PriceWatch.useRest(t0 - 30_001, t0))
        assertFalse(PriceWatch.alert(t0 - 120_000, t0 - 600_000, t0))
        assertTrue(PriceWatch.alert(t0 - 120_001, t0 - 600_000, t0))
        assertFalse(PriceWatch.alert(null, t0 - 60_000, t0), "just opened: the clock starts at the open")
        assertTrue(PriceWatch.alert(null, t0 - 130_000, t0))
        assertFalse(PriceWatch.alert(t0 - 900_000, t0 - 10_000, t0), "a price before the open does not count against it")
        assertEquals("Positions not protected: no prices since 14:11", PriceWatch.say(LocalTime.of(14, 11, 40)))
    }

    // ---- 15 Oct: the failsafe judges a position only while its own market trades -------------------------------------

    private val wed = LocalDate.of(2026, 10, 14)            // an ordinary Wednesday: NSE and MCX both trade
    private val dussehra = LocalDate.of(2026, 10, 20)       // NSE shut all day; MCX only from 17:00
    private val nseHolidays = setOf(dussehra)
    private val nseDay: (LocalDate, Int) -> Boolean = PriceWatch.nseOpen { it.dayOfWeek.value <= 5 && it !in nseHolidays }
    private val mcx = McxSession.Calendar()
    private fun ms(t: LocalDateTime) = t.atZone(IST).toInstant().toEpochMilli()

    private val nse = PriceWatch.Held("NFO:BANKNIFTY26OCT52000CE", "NFO", LocalDate.of(2026, 10, 27))
    private val gold = PriceWatch.Held("MCX:GOLDM26NOVFUT", "MCX")

    /** One sweep pass at [t]: the positions [held], those [quoted] priced; what the failsafe says (the silence's start). */
    private fun PriceWatch.Failsafe.pass(t: LocalDateTime, held: List<PriceWatch.Held>, quoted: Set<String> = emptySet(), all: Boolean = false): LocalDateTime? {
        val (holding, priced) = PriceWatch.judge(held, quoted, t, nseDay, mcx, all)
        return look(holding, priced, ms(t))?.let { java.time.Instant.ofEpochMilli(it).atZone(IST).toLocalDateTime() }
    }

    @Test fun anNsePositionIsJudgedOnlyInNsesHours() {
        fun judged(t: LocalDateTime, h: PriceWatch.Held = nse) = PriceWatch.inSession(h.exchange, t, nseDay, mcx, h.expiry)
        assertFalse(judged(wed.atTime(8, 0)))
        assertFalse(judged(wed.atTime(9, 0)), "MCX is open, NSE is not")
        assertFalse(judged(wed.atTime(9, 14, 59)))
        assertTrue(judged(wed.atTime(9, 15)))
        assertTrue(judged(wed.atTime(15, 39)))
        assertFalse(judged(wed.atTime(15, 40)), "F&O closes at 15:40")
        assertFalse(judged(wed.atTime(20, 0)))
        assertFalse(judged(LocalDate.of(2026, 10, 17).atTime(11, 0)), "a Saturday")
        assertFalse(judged(dussehra.atTime(11, 0)), "an NSE holiday")
        // Expiry day: judged to the close like any day; the day after, the expired option is not priced again.
        val expiring = nse.copy(expiry = wed)
        assertTrue(judged(wed.atTime(15, 4), expiring))
        assertTrue(judged(wed.atTime(15, 6), expiring), "still held after the 15:05 square-off: still watched")
        assertFalse(judged(wed.atTime(15, 40), expiring))
        assertFalse(judged(wed.plusDays(1).atTime(11, 0), expiring))
        // NSE written another way is still NSE (not MCX).
        assertTrue(PriceWatch.inSession("nfo", wed.atTime(10, 0), nseDay, mcx))
    }

    @Test fun anMcxPositionIsJudgedInMcxsOwnHours() {
        fun judged(t: LocalDateTime) = PriceWatch.inSession(gold.exchange, t, nseDay, mcx)
        assertFalse(judged(wed.atTime(8, 55)))
        assertTrue(judged(wed.atTime(9, 0)))
        assertTrue(judged(wed.atTime(9, 5)))
        assertTrue(judged(wed.atTime(23, 29)))
        assertFalse(judged(wed.atTime(23, 30)), "MCX closes at 23:30 in US summer time")
        assertTrue(judged(LocalDate.of(2026, 11, 4).atTime(23, 40)), "23:55 in US winter time")
        assertFalse(judged(dussehra.atTime(11, 0)), "Dussehra: MCX shut in the morning")
        assertTrue(judged(dussehra.atTime(17, 30)), "and open in the evening")
        assertTrue(PriceWatch.inSession("mcx", wed.atTime(10, 0), nseDay, mcx))
    }

    @Test fun beforeNsesOpenAnOvernightPositionWithNoPriceIsNotAnAlarm() {
        val f = PriceWatch.Failsafe()
        // The MCX pass from 09:00 (an MCX paper arm on), the NSE position with no candle of the day: nothing said.
        var t = wed.atTime(9, 0)
        while (t.isBefore(wed.atTime(9, 15))) { assertNull(f.pass(t, listOf(nse)), "$t"); t = t.plusSeconds(15) }
        // From 09:15 the silence counts: two minutes with no price is said once, from 09:15.
        assertNull(f.pass(wed.atTime(9, 15), listOf(nse)))
        assertNull(f.pass(wed.atTime(9, 17), listOf(nse)), "two minutes exactly: not yet")
        assertEquals(wed.atTime(9, 15), f.pass(wed.atTime(9, 17, 1), listOf(nse)))
        assertNull(f.pass(wed.atTime(9, 18), listOf(nse)), "said once for this silence")
        // A price, then silence again: said again, from the last price.
        assertNull(f.pass(wed.atTime(9, 19), listOf(nse), setOf(nse.key)))
        assertNull(f.pass(wed.atTime(9, 21), listOf(nse)))
        assertEquals(wed.atTime(9, 19), f.pass(wed.atTime(9, 21, 1), listOf(nse)))
    }

    @Test fun inTheSessionAPricedPositionIsNeverAnAlarm() {
        val f = PriceWatch.Failsafe()
        var t = wed.atTime(9, 15)
        while (t.isBefore(wed.atTime(9, 45))) { assertNull(f.pass(t, listOf(nse), setOf(nse.key))); t = t.plusSeconds(15) }
        // After the close the position is no longer judged: the evening's silence is no alarm, and the next morning
        // starts its clock at the open again (not at yesterday's last price).
        assertNull(f.pass(wed.atTime(15, 40), listOf(nse)))
        assertNull(f.pass(wed.atTime(21, 0), listOf(nse)))
        val thu = wed.plusDays(1)
        assertNull(f.pass(thu.atTime(9, 10), listOf(nse)))
        assertNull(f.pass(thu.atTime(9, 15), listOf(nse)))
        assertEquals(thu.atTime(9, 15), f.pass(thu.atTime(9, 17, 30), listOf(nse)))
    }

    @Test fun anMcxPositionAt0855IsNotJudgedAndAt0905ItIs() {
        val f = PriceWatch.Failsafe()
        assertNull(f.pass(wed.atTime(8, 55), listOf(gold)))
        assertNull(f.pass(wed.atTime(8, 59, 59), listOf(gold)))
        assertNull(f.pass(wed.atTime(9, 5), listOf(gold)))
        assertEquals(wed.atTime(9, 5), f.pass(wed.atTime(9, 7, 1), listOf(gold)), "MCX open and no price: said")
        // MCX priced and an NSE position unpriced before 09:15: priced (the NSE one is not judged yet).
        val g = PriceWatch.Failsafe()
        assertNull(g.pass(wed.atTime(9, 1), listOf(gold, nse), setOf(gold.key)))
        assertNull(g.pass(wed.atTime(9, 10), listOf(gold, nse), setOf(gold.key), all = true))
        assertNull(g.pass(wed.atTime(9, 14), listOf(gold, nse), setOf(gold.key), all = true))
        // From 09:15 Zerodha's "every one priced" counts the NSE one too.
        assertNull(g.pass(wed.atTime(9, 15), listOf(gold, nse), setOf(gold.key), all = true))
        assertEquals(wed.atTime(9, 14), g.pass(wed.atTime(9, 17, 1), listOf(gold, nse), setOf(gold.key), all = true))
    }

    @Test fun onAHolidayNothingIsSaidAndTheNextSessionIsJudged() {
        val f = PriceWatch.Failsafe()
        var t = dussehra.atTime(9, 0)
        while (t.isBefore(dussehra.atTime(16, 0))) { assertNull(f.pass(t, listOf(nse, gold)), "$t"); t = t.plusMinutes(5) }
        // MCX's evening session: the MCX position is judged; the NSE one stays not judged.
        assertNull(f.pass(dussehra.atTime(17, 0), listOf(nse, gold), setOf(gold.key)))
        assertNull(f.pass(dussehra.atTime(17, 30), listOf(nse, gold), setOf(gold.key), all = true))
        assertEquals(dussehra.atTime(17, 30), f.pass(dussehra.atTime(17, 32, 1), listOf(nse, gold)))
    }

    @Test fun nothingHeldForgetsTheClock() {
        val f = PriceWatch.Failsafe()
        val t0 = ms(wed.atTime(10, 0))
        assertNull(f.look(true, false, t0))
        assertEquals(t0, f.look(true, false, t0 + 181_000))
        // Nothing held: forgotten; the next holding starts its own two minutes.
        assertNull(f.look(false, false, t0 + 200_000))
        assertNull(f.look(true, false, t0 + 210_000))
        assertNull(f.look(true, false, t0 + 300_000))
        assertEquals(t0 + 210_000, f.look(true, false, t0 + 330_001))
    }

    @Test fun aPricedPaperBookNeverHidesALiveOneWithNoPrice() {
        // (holding, priced) per book: the paper account, Zerodha.
        assertEquals(true to false, PriceWatch.combine(listOf(true to true, true to false)))
        assertEquals(true to false, PriceWatch.combine(listOf(true to false, true to true)))
        assertEquals(true to true, PriceWatch.combine(listOf(true to true, true to true)))
        // A book holding nothing in its session does not count either way.
        assertEquals(true to true, PriceWatch.combine(listOf(false to false, true to true)))
        assertEquals(true to false, PriceWatch.combine(listOf(true to false, false to false)))
        assertEquals(false to false, PriceWatch.combine(listOf(false to false, false to false)))
        assertEquals(false to false, PriceWatch.combine(emptyList()))
        // Through the failsafe: the live position's silence is said although the paper one is priced.
        val f = PriceWatch.Failsafe()
        val t0 = ms(wed.atTime(10, 0))
        PriceWatch.combine(listOf(true to true, true to false)).let { (h, p) -> assertNull(f.look(h, p, t0)) }
        PriceWatch.combine(listOf(true to true, true to false)).let { (h, p) -> assertEquals(t0, f.look(h, p, t0 + 121_000)) }
    }

    @Test fun nothingHeldOrNothingInSessionIsNeitherHoldingNorPriced() {
        assertEquals(false to false, PriceWatch.judge(emptyList(), emptySet(), wed.atTime(10, 0), nseDay, mcx))
        assertEquals(false to false, PriceWatch.judge(listOf(nse), setOf(nse.key), wed.atTime(9, 10), nseDay, mcx))
        assertEquals(true to true, PriceWatch.judge(listOf(nse, nse.copy(key = "NFO:X")), setOf(nse.key), wed.atTime(10, 0), nseDay, mcx))
        assertEquals(true to false, PriceWatch.judge(listOf(nse, nse.copy(key = "NFO:X")), setOf(nse.key), wed.atTime(10, 0), nseDay, mcx, all = true))
    }
}
