package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayIndexTest {
    /** A Wednesday. */
    private val today = LocalDate.of(2026, 10, 7)

    private fun day(bank: Int, nifty: Int): Map<String, Int> = buildMap {
        if (bank > 0) put("market:BANKNIFTY", bank)
        if (nifty > 0) put("market:NIFTY", nifty)
        put("topic:LEVELS", bank + nifty)
    }

    /**
     * The kinds tally ([SelfDoubt.count]): on each of the [weds] Wednesdays before today [bank] BankNifty and [nifty] Nifty
     * mentions, and on every other weekday of the last four weeks [others] Nifty mentions (so across all days Nifty leads).
     */
    private fun tally(weds: Int, bank: Int, nifty: Int, others: Int = 5, from: Long = 1): DoubtTally {
        val out = HashMap<LocalDate, Map<String, Int>>()
        for (w in from until from + weds) out[today.minusWeeks(w)] = day(bank, nifty)
        for (d in 1L..28L) {
            val x = today.minusDays(d)
            if (x.dayOfWeek != DayOfWeek.WEDNESDAY && x.dayOfWeek.value <= 5 && others > 0) out[x] = day(0, others)
        }
        return out
    }

    private fun snap(m: Market, price: Double) = Snapshot(m, today.atTime(10, 0), true, price, price * 0.99, price, price, price, null, null,
        emptyList(), null, null, emptyList(), emptyList(), emptyList())

    @Test fun learnedForThatWeekdayAloneFromTheTallyAlreadyKept() {
        // 3 Wednesdays x 3 BankNifty, 1 Nifty: 9 of 12, on 3 Wednesdays; every other day Nifty.
        val t = tally(3, 3, 1)
        val r = assertNotNull(DayIndex.learned(t, DayIndex.Log(), today))
        assertEquals(Market.BANKNIFTY, r.market); assertEquals(DayOfWeek.WEDNESDAY, r.day)
        assertEquals(9, r.times); assertEquals(3, r.others); assertEquals(3, r.days); assertEquals(today.minusWeeks(1), r.newest)
        assertTrue(r.say().startsWith("on Wednesdays, BankNifty was 9 of your 12 mentions"), r.say())
        assertFalse(r.say().contains("questions"), r.say())
        // Only Wednesdays: on a Thursday Nifty leads as always, and across all days it is not LeadIndex's habit.
        assertNull(DayIndex.learned(t, DayIndex.Log(), today.plusDays(1)))
        assertEquals(listOf(DayOfWeek.WEDNESDAY), DayIndex.learnedAll(t, DayIndex.Log(), today).map { it.day })
        assertNull(LeadIndex.learned(t, LeadIndex.Log(), today))
    }

    @Test fun belowTheThresholdsNothingIsLearned() {
        assertTrue(DayIndex.learnedAll(emptyMap(), DayIndex.Log(), today).isEmpty())
        // Two Wednesdays only (MIN_DAYS), however many mentions.
        assertNull(DayIndex.learned(tally(2, 6, 0), DayIndex.Log(), today))
        // Fewer than MIN_TIMES mentions.
        assertNull(DayIndex.learned(tally(4, 1, 0), DayIndex.Log(), today))
        // Under two in three of that day's mentions.
        assertNull(DayIndex.learned(tally(4, 2, 2), DayIndex.Log(), today))
        // Older than the window.
        assertNull(DayIndex.learned(tally(3, 3, 0, from = 5), DayIndex.Log(), today))
        // A weekend tally is never read.
        val sat = today.minusDays(4)
        val weekend = (0L until 4L).associate { w -> sat.minusWeeks(w) to day(5, 0) }
        assertTrue(DayIndex.learnedAll(weekend, DayIndex.Log(), sat).isEmpty())
    }

    @Test fun undoneAndCountedAfresh() {
        val t = tally(3, 3, 1)
        val log = DayIndex.reset(today)
        assertNull(DayIndex.learned(t, log, today))
        // Three fresh Wednesdays after the undo learn it again.
        val later = today.plusWeeks(3)
        val fresh = t + (1L..3L).associate { w -> today.plusWeeks(w) to day(3, 0) }
        assertNull(DayIndex.learned(fresh - today.plusWeeks(3), log, later.minusWeeks(1)))
        assertEquals(Market.BANKNIFTY, DayIndex.learned(fresh, log, later)?.market)
    }

    @Test fun neverOnALockedPhone() {
        val t = tally(3, 3, 1)
        assertEquals(Market.BANKNIFTY, DayIndex.lead(t, DayIndex.Log(), today, locked = false))
        assertNull(DayIndex.lead(t, DayIndex.Log(), today, locked = true))
        assertNull(DayIndex.lead(t, DayIndex.Log(), today.plusDays(1), locked = false))
    }

    @Test fun theMarketReadLeadsWithItWithTheSameWords() {
        val reads = listOf(TradeCheck.read(snap(Market.NIFTY, 24600.0)), TradeCheck.read(snap(Market.BANKNIFTY, 55100.0)))
        assertTrue(reads[0].startsWith("Nifty is ") && reads[1].startsWith("BankNifty is "), reads.toString())
        assertEquals(reads, DayIndex.leadReads(reads, null))
        assertEquals(reads, DayIndex.leadReads(reads, Market.NIFTY))
        assertEquals(reads.reversed(), DayIndex.leadReads(reads, Market.BANKNIFTY))
        assertEquals(listOf(reads[0]), DayIndex.leadReads(listOf(reads[0]), Market.BANKNIFTY))
        // The verdict said the same, its index lines swapped: the head, the reasons and the level unchanged.
        val v = TradeCheck.Verdict(TradeCheck.Level.CAREFUL, listOf(TradeCheck.Reason(TradeCheck.Level.CAREFUL, "India VIX is up.")), reads)
        val led = v.copy(reads = DayIndex.leadReads(v.reads, Market.BANKNIFTY))
        assertEquals(v.level, led.level); assertEquals(v.reasons, led.reasons)
        assertEquals(v.say().replace(reads.joinToString(" "), reads.reversed().joinToString(" ")), led.say())
        assertTrue(led.say().indexOf("BankNifty is") < led.say().indexOf("Nifty is "), led.say())
    }

    @Test fun bossCanAskAndUndo() {
        for (q in listOf("which index do you lead with on wednesdays", "Jarvis, which index do you lead with today?",
                "what index do you start the market read with", "which index do you mention first today",
                "why did you start with BankNifty today?", "why do you lead with bank nifty on wednesdays", "why is banknifty first today",
                "which index do i ask about most on wednesdays", "kis din kaunsa index pehle", "aaj banknifty pehle kyun",
                "budhvar ko banknifty pehle kyun bola"))
            assertEquals(DayIndex.Request.WHICH, DayIndex.asked(q), q)
        for (q in listOf(DayIndex.UNDO, "lead with nifty every day", "stop leading with banknifty on wednesdays", "stop leading with bank nifty",
                "stop starting with banknifty on wednesdays", "stop changing the index by day", "don't lead with banknifty on wednesdays",
                "forget which index i ask about on wednesdays", "har din nifty pehle lo", "budhvar ko banknifty pehle mat lo",
                "banknifty pehle mat lo aaj"))
            assertEquals(DayIndex.Request.RESET, DayIndex.asked(q), q)
        // LeadIndex's own (across all days), the market questions, the weekday record: never this one's.
        for (q in listOf("which index do you mention first", "why do you start with banknifty", "why do you say banknifty first",
                "mention nifty first again", "stop saying banknifty first", "banknifty pehle mat bolo", "how is the market",
                "market kaisa hai", "how is banknifty today", "how are wednesdays for banknifty", "nifty pehle batao",
                "start banknifty", "stop orb", "lead with nifty", "which index do i usually mean"))
            assertNull(DayIndex.asked(q), q)
    }

    /** Its "stop ..." undos, each a habit's undo ([Commands]' habitUndo): never a STOP of an arm by that name. */
    private val STOPS = listOf("stop leading with banknifty on wednesdays", "stop leading with bank nifty", "stop leading with banknifty today",
        "stop starting with banknifty on wednesdays", "stop changing the index by day", "stop switching the index by weekday",
        "jarvis stop leading with banknifty on wednesdays please")

    @Test fun itsStopIsTheHabitsUndoNeverAStrategyStop() {
        for (q in STOPS) {
            assertEquals(DayIndex.Request.RESET, DayIndex.asked(q), q)
            assertNull(Commands.parse(q), q)
            val p = Ask.parse(q); assertNull(p.command, q); assertNull(p.order, q); assertFalse(Bundle.acts(q), q)
            assertEquals("DayIndex", CoverageTest().feature(q), q)
        }
        // A real stop keeps its route.
        assertEquals(Command.Kind.STOP_ONE, Commands.parse("stop orb")?.kind)
        for (q in listOf("which index do you lead with on wednesdays", DayIndex.UNDO, "har din nifty pehle lo", "aaj banknifty pehle kyun")) {
            val p = Ask.parse(q); assertNull(p.order, q); assertNull(p.command, q); assertFalse(Bundle.acts(q), q)
            assertNull(LeadIndex.asked(q), q); assertNull(UsualIndex.asked(q), q)
        }
        // Asking about it or undoing it never counts toward it (not tallied).
        assertEquals(emptyMap(), SelfDoubt.count(emptyMap(), today, "stop leading with banknifty on wednesdays"))
        assertEquals(emptyMap(), SelfDoubt.count(emptyMap(), today, "why did you start with banknifty today"))
    }

    @Test fun whatIsSaid() {
        val t = tally(3, 3, 1)
        val rs = DayIndex.learnedAll(t, DayIndex.Log(), today)
        val said = DayIndex.say(rs, today)
        assertTrue(said.contains("on Wednesdays \"how's the market\" gives BankNifty's read first, today among them") &&
            said.contains(DayIndex.UNDO) && said.contains(DayIndex.ONLY_ORDER), said)
        assertFalse(DayIndex.say(rs, today.plusDays(1)).contains("today among them"))
        assertTrue(DayIndex.say(emptyList(), today).startsWith("I lead \"how's the market\" with Nifty every day"))
        assertTrue(DayIndex.sayReset(rs).startsWith("Done, Boss: Nifty first every day again"))
        assertTrue(DayIndex.sayReset(emptyList()).startsWith("I already lead with Nifty"))
        // Locked: one neutral reply, the index learned (or whether one was) never named.
        assertFalse(DayIndex.RESET_LOCKED.contains("BankNifty") || DayIndex.RESET_LOCKED.contains("Wednesday") || DayIndex.RESET_LOCKED.contains("already"))
        assertTrue(DayIndex.ledgerWhat(rs.single()).startsWith("BankNifty's read first in \"how's the market\" on Wednesdays"))
        // In the ledger, with its undo; reset by "undo everything you learned this week"; never on a locked phone.
        val inputs = Learnings.Inputs(tally = t)
        val item = Learnings.items(inputs, today.atTime(12, 0)).single { it.area == Learnings.Area.DAY_INDEX }
        assertEquals(DayIndex.UNDO, item.undo)
        assertTrue(Learnings.Area.DAY_INDEX.personal)
        val u = Learnings.undo(inputs, today.atTime(12, 0))
        assertEquals(listOf(Market.BANKNIFTY), u.dayIndex.map { it.market })
        assertFalse(u.empty)
        assertTrue(Learnings.offer(u).contains("Nifty first every day again"))
        assertFalse(Learnings.say(Learnings.items(inputs, today.atTime(12, 0)), Learnings.Ask.ALL, today, locked = true).contains("how's the market"))
        // Undone: gone from the ledger.
        val after = Learnings.Inputs(tally = t, dayIndex = DayIndex.reset(today))
        assertTrue(Learnings.items(after, today.atTime(12, 0)).none { it.area == Learnings.Area.DAY_INDEX })
    }
}
