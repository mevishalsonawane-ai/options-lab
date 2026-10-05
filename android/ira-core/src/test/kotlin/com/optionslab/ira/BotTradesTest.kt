package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotTradesTest {
    private val day: LocalDate = LocalDate.of(2026, 10, 5)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private val now = at(15, 20)
    private val range = 54_200.0 to 54_000.0

    /** A five-minute bar from [start] as five 1-minute candles with that open, high, low and close. */
    private fun bar(start: LocalDateTime, o: Double, h: Double, l: Double, c: Double): List<Candle> = (0 until 5).map { i ->
        val t = start.plusMinutes(i.toLong())
        when (i) {
            0 -> Candle(t, o, maxOf(o, (o + c) / 2), minOf(o, (o + c) / 2), (o + c) / 2)
            1 -> Candle(t, (o + c) / 2, h, (o + c) / 2, (o + c) / 2)
            2 -> Candle(t, (o + c) / 2, (o + c) / 2, l, (o + c) / 2)
            else -> Candle(t, (o + c) / 2, maxOf(c, (o + c) / 2), minOf(c, (o + c) / 2), c)
        }
    }

    private val ones: List<Candle> =
        bar(at(10, 30), 54_150.0, 54_190.0, 54_140.0, 54_180.0) +      // inside the range
        bar(at(10, 35), 54_180.0, 54_270.0, 54_170.0, 54_260.0) +      // closes above the range high: the ORB's break
        bar(at(10, 45), 54_230.0, 54_240.0, 54_140.0, 54_150.0)        // back below the high from the top tenth: Range Fade's put

    private val orb = BotTrades.Trade("orb", "BANKNIFTY07OCT2654200CE", "CE", 35, 400.0, at(10, 40), at(10, 35),
        exit = 360.0, exitTime = at(10, 58), why = "stop", charges = 60.0)
    private val fade = BotTrades.Trade("range_fade", "BANKNIFTY07OCT2654200PE", "PE", 35, 380.0, at(10, 50), at(10, 45),
        exit = 420.0, exitTime = at(11, 20), why = "target", charges = 60.0)
    private val liq = BotTrades.Trade("liquidity5", "BANKNIFTY14OCT2654300CE", "CE", 35, 300.0, at(12, 0), at(11, 55),
        exit = 296.0, exitTime = at(12, 12), why = "time_stop", charges = 55.0, level = 54_300.0, target = 54_400.0)
    private val switches = listOf(BotTrades.Switch("ORB", true), BotTrades.Switch("ORB Fresh", false), BotTrades.Switch("ORB Sweep", false),
        BotTrades.Switch("Range Fade", true), BotTrades.Switch("Liquidity 15+5", true))

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|better switched off|switch (it )?off|turn (it )?off|fine to run|consider|will (rise|fall|go))\\b")

    @Test fun eachTradeIsChainedFromItsSignalToItsResult() {
        val r = BotTrades.read(listOf(orb, fade, liq), switches, range, ones, now)
        assertEquals(listOf("ORB", "Range Fade", "Liquidity 5m"), r.chains.map { it.label })
        val c = r.chains[0]
        assertTrue(c.signal.contains("10:35 bar closed at 54,260.00, above the range high 54,200.00") && c.signal.contains("took the call"), c.signal)
        assertTrue(c.exit.contains("Bought at 400.00 at 10:40") && c.exit.contains("40-point stop (360.00)") && c.exit.contains("sold at 360.00 at 10:58"), c.exit)
        assertTrue(c.result.contains("-40.00 points on 35 units (paper), -Rs 1,400.00, -Rs 1,460.00 after 60.00 charges"), c.result)
        assertTrue(c.notes.isEmpty(), c.notes.toString())
        val f = r.chains[1]
        assertTrue(f.signal.contains("top tenth") && f.signal.contains("fade of the high, so it took the put"), f.signal)
        assertTrue(f.exit.contains("out at its target (+40 points, 420.00)"), f.exit)
        val l = r.chains[2]
        assertTrue(l.signal.contains("5-minute BankNifty chart closed above the liquidity level at 54,300.00") && l.signal.contains("54,400.00, is its index target"), l.signal)
        assertTrue(l.exit.contains("time stop"), l.exit)
    }

    @Test fun whatDoesNotFitIsFlagged() {
        val r = BotTrades.read(listOf(orb, fade, liq), switches, range, ones, now)
        val flags = r.notes.map { it.flag }
        // ORB armed though its test lost in both years (Boss's 5 Oct morning).
        assertTrue(r.notes.any { it.flag == BotTrades.Flag.ARMED_TESTED_LOSING && it.text.startsWith("ORB is armed, though its two-year BankNifty test lost in both years") }, r.notes.toString())
        assertTrue(r.notes.any { it.flag == BotTrades.Flag.ARMED_TESTED_LOSING && it.text.startsWith("Range Fade is armed") })
        // Liquidity 15+5 made money in both years: not flagged; ORB Fresh is not armed: not flagged.
        assertTrue(r.notes.none { it.text.startsWith("Liquidity") && it.flag == BotTrades.Flag.ARMED_TESTED_LOSING })
        assertTrue(r.notes.none { it.text.startsWith("ORB Fresh") })
        // ORB's call and Range Fade's put overlapped 10:50-10:58.
        assertTrue(r.notes.any { it.flag == BotTrades.Flag.OPPOSITE_SIDES && it.text.contains("ORB held a call (10:40-10:58) while Range Fade held a put (10:50-11:20) on BankNifty: opposite sides for 8 minutes") }, r.notes.toString())
        // The time stop sold after 12 minutes: earlier than its 20-minute rule.
        assertTrue(r.notes.any { it.flag == BotTrades.Flag.EARLY_EXIT && it.text.contains("20-minute time stop, but it sold after 12 minutes") }, r.notes.toString())
        assertTrue(BotTrades.Flag.ENTRY_OFF_RULE !in flags && BotTrades.Flag.OUTSIDE_HOURS !in flags, flags.toString())
    }

    @Test fun anEntryAndAnExitOffTheirRules() {
        // ORB Fresh on the 10:30 bar, which closed inside the range; a stop booked well above its level; a 15:10 square-off at 14:40.
        val fresh = BotTrades.Trade("orb_fresh", "BANKNIFTY07OCT2654200CE", "CE", 35, 400.0, at(10, 35), at(10, 30),
            exit = 380.0, exitTime = at(10, 50), why = "stop")
        val late = BotTrades.Trade("orb", "BANKNIFTY07OCT2654200PE", "PE", 35, 300.0, at(14, 5), at(14, 0),
            exit = 310.0, exitTime = at(14, 40), why = "session_end")
        val r = BotTrades.read(listOf(fresh, late), emptyList(), range, ones, now)
        assertTrue(r.notes.any { it.flag == BotTrades.Flag.ENTRY_OFF_RULE && it.text.contains("10:30 bar closed at 54,180.00, inside the range") }, r.notes.toString())
        assertTrue(r.notes.any { it.flag == BotTrades.Flag.EARLY_EXIT && it.text.contains("booked as its stop, but it sold at 380.00") }, r.notes.toString())
        assertTrue(r.notes.any { it.flag == BotTrades.Flag.OUTSIDE_HOURS && it.text.contains("signal bar 14:00") }, r.notes.toString())
        assertTrue(r.notes.any { it.flag == BotTrades.Flag.EARLY_EXIT && it.text.contains("15:10 square-off, but it sold at 14:40") }, r.notes.toString())
    }

    @Test fun theProfitLockIsExplainedFromItsPeak() {
        val t = BotTrades.Trade("orb", "BANKNIFTY07OCT2654200CE", "CE", 35, 400.0, at(10, 40), at(10, 35),
            exit = 410.0, exitTime = at(11, 0), why = "profit_lock", ladder = true, peak = 421.0)
        val c = BotTrades.read(listOf(t), emptyList(), range, ones, now).chains.single()
        assertTrue(c.exit.contains("its best price 421.00 was 52% of the way to its target, which moved the stop to 410.00"), c.exit)
        assertTrue(c.notes.isEmpty(), c.notes.toString())
        val bad = t.copy(peak = 405.0)
        assertTrue(BotTrades.read(listOf(bad), emptyList(), range, ones, now).notes.any { it.flag == BotTrades.Flag.EXIT_OFF_RULE && it.text.contains("never reached the first rung (410.00)") })
    }

    @Test fun theAnswer() {
        val s = BotTrades.answer(BotTrades.Q(), listOf(orb, fade, liq), switches, range, ones, now)
        assertTrue(s.startsWith("Boss, your bots took 3 trades today (paper), 3 closed for "), s)
        assertTrue(s.contains("1. ORB, BANKNIFTY07OCT2654200CE: Signal:"), s)
        assertTrue(s.contains("Tested over two BankNifty years: ORB lost in both tested years (-Rs 77,013, -Rs 69,754 a lot)"), s)
        assertTrue(s.contains("Liquidity 15+5 made money in both tested years"), s)
        assertTrue(s.contains("What doesn't fit: "), s)
        assertTrue(s.endsWith(BotTrades.NOTE), s)
        assertTrue(!ADVICE.containsMatchIn(s), s)
        // One arm by name: only its trades.
        val o = BotTrades.answer(BotTrades.Q("orb"), listOf(orb, fade, liq), switches, range, ones, now)
        assertTrue(o.startsWith("Boss, ORB took 1 trade today (paper)") && !o.contains("Range Fade held") && !o.contains("Liquidity 5m"), o)
        assertTrue(o.contains("ORB is armed, though"), o)
        // A quiet day, and an arm that has not traded.
        val none = BotTrades.answer(BotTrades.Q(), emptyList(), switches, range, ones, now)
        assertTrue(none.startsWith("None of your bots has traded today, Boss.\nArmed now: ORB, Range Fade, Liquidity 15+5."), none)
        assertTrue(none.contains("ORB is armed, though"), none)
        assertTrue(BotTrades.answer(BotTrades.Q("orb_sweep"), listOf(orb), switches, range, ones, now).startsWith("ORB Sweep has not traded today, Boss."))
        // Yesterday's trades are not today's.
        val old = orb.copy(entryTime = orb.entryTime.minusDays(3), signalBar = orb.signalBar.minusDays(3))
        assertTrue(BotTrades.answer(BotTrades.Q(), listOf(old), switches, range, ones, now).startsWith("None of your bots has traded today"))
        // An open trade, and a live one.
        val open = liq.copy(exit = null, exitTime = null, why = null, live = true)
        val so = BotTrades.answer(BotTrades.Q(), listOf(open), switches, range, ones, now)
        assertTrue(so.contains("(1 at Zerodha, 0 on paper), 1 still open") && so.contains("still open, with its stop 15% under the price paid (255.00)"), so)
    }

    @Test fun theOpeningRangeFromTheCandlesWhenTheArmsHaveNone() {
        val morning = (0 until 50).map { i -> val t = at(9, 15).plusMinutes(i.toLong()); Candle(t, 54_100.0, 54_100.0 + i, 54_050.0 - i, 54_100.0) }
        assertEquals(54_149.0 to 54_001.0, BotTrades.openingRange(morning, day))
        assertNull(BotTrades.openingRange(morning.take(40), day))
        val c = BotTrades.read(listOf(orb), emptyList(), null, morning + ones, now).chains.single()
        assertTrue(c.signal.contains("above the range high 54,149.00"), c.signal)
    }

    @Test fun theQuestions() {
        mapOf(
            "explain my bots' trades today" to null, "explain my bots trades" to null, "explain today's bot trades" to null,
            "walk me through my bots' trades" to null, "walk me through ORB's trades" to "orb", "break down the Range Fade trade" to "range_fade",
            "why did ORB take that trade?" to "orb", "why did my bots trade today?" to null, "why did the liquidity bot exit?" to "liquidity",
            "why did ORB Fresh enter" to "orb_fresh", "what did my bots do today?" to null, "what trades did ORB take" to "orb",
            "did my bots follow their rules?" to null, "did ORB stick to its rules today" to "orb", "were my bots' trades by the rules" to null,
            "any contradictions in my bots" to null, "did my bots take opposite sides" to null, "are my bots fighting each other" to null,
            "my bots' trades today" to null, "today's bot trades" to null, "explain the ORB Sweep trades" to "orb_sweep",
            "mere bots ne aaj kya kiya" to null, "ORB ne trade kyun liya" to "orb", "bots ke trades samjhao" to null,
            "explain my algo trades" to null, "go through my paper bots' trades" to null,
        ).forEach { (s, bot) ->
            assertEquals(BotTrades.Q(bot), BotTrades.asked(s), s)
            val p = Ask.parse(s)
            assertTrue(p.order == null && p.command == null, s)
        }
        listOf("how are my bots doing", "is the ORB arm behaving", "which strategy is losing", "stop ORB", "arm ORB", "switch off ORB",
            "explain my trades", "explain my positions", "backtest ORB", "how did my bots do this week", "what did my bots do yesterday",
            "should I switch off ORB", "what is the opening range", "when nifty breaks its first 15 minute range how often does it hold",
            "why did the trade lose", "explain the orb strategy", "what will my bots do tomorrow", "mere bots kaise chal rahe hain",
        ).forEach { assertNull(BotTrades.asked(it), it) }
    }
}
