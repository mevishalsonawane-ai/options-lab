package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradeCaseTest {
    private val today = LocalDate.of(2026, 10, 5)   // a Monday
    private fun at(h: Int, m: Int): LocalDateTime = today.atTime(h, m)

    private fun now(
        minute: Int = 11 * 60, marketOpen: Boolean = true, tradingDay: Boolean = true, live: Boolean = false, zerodha: Boolean = true,
        fresh: Boolean = true, kill: Boolean = false, breaker: Boolean = false, stopped: Boolean = false,
        dayPnl: Double? = null, limit: Double = 10_000.0, vix: Double? = 14.0, vixCh: Double? = 1.0,
        index: Double? = 0.3, gap: Double? = 0.1, burst: Double? = 0.05, usual: Double? = 0.15, orr: Double? = null,
        events: List<String> = emptyList(), expiry: Boolean = false, arms: List<String> = emptyList(), reads: List<String> = emptyList(),
    ) = TradeCheck.Now(marketOpen, tradingDay, minute, live, zerodha, null, fresh, kill, breaker, stopped, dayPnl, limit,
        vix, vixCh, index, gap, burst, usual, orr, events, expiry, arms, reads)

    private fun input(n: TradeCheck.Now = now(), t: LocalDateTime = at(n.minute / 60, n.minute % 60), mine: TradeCase.Mine? = null, locked: Boolean = false,
                      calibration: List<SelfCalibration.Outcome> = emptyList(), upcoming: List<Events.Event> = emptyList(),
                      bars: Map<Market, List<Candle>> = emptyMap(), regime: Regime.Kind? = null) =
        TradeCase.Input(n, t, bars, upcoming, calibration, regime, null, mine, locked)

    // Words that would make the case advice or a direction. (The case lays out facts; the decision is always Boss's.)
    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|i'd (buy|sell|trade|go)|go ahead|fine to trade|don't trade|do not trade|stop for today|" +
        "trade smaller|good time to (buy|sell|trade)|bad time to|take the trade|enter now|buy now|sell now|go long|go short|bullish bet|bearish bet|safe to trade|better switched off|fine to run)\\b")

    private fun noAdvice(s: String) {
        // (Boss's own words, quoted back, are his - Jarvis's words around them are checked.)
        assertFalse(ADVICE.containsMatchIn(s.replace(Regex("\"[^\"]*\""), "\"\"")), "advice in: $s")
        assertTrue(s.endsWith(TradeCase.YOURS), "the decision is Boss's: $s")
        assertTrue(s.contains("Boss"))
    }

    @Test fun askedPhrasings() {
        listOf("make the case", "Jarvis, make the case for trading now", "give me the case for and against trading today",
            "pros and cons of trading now", "for and against trading today?", "what are the pros and cons of trading right now",
            "talk me through it", "walk me through whether I should trade now", "think it through", "reason it out",
            "explain the trade check", "full trade check", "detailed trade check please",
            "should I trade now and why?", "Should I trade today, why or why not?", "why shouldn't I trade now?",
            "what would you watch now?", "what would you keep an eye on today", "trade karu ya nahi samjhao").forEach {
            assertTrue(TradeCase.asked(it), it)
        }
        listOf("should I trade now?", "buy 2 lots of nifty", "make a trade", "what is the case", "why is the market down",
            "walk me through the chart", "what would you buy", "talk to me", "pros and cons of iron condors", "case closed").forEach {
            assertFalse(TradeCase.asked(it), it)
        }
    }

    @Test fun askedIsNeverAnOrderOrCommand() {
        listOf("make the case for trading now", "pros and cons of trading now", "talk me through it", "explain the trade check",
            "should I trade now and why?", "what would you watch now?").forEach { q ->
            val p = Ask.parse(q)
            assertTrue(p.order == null && p.command == null, "$q: ${p.order} ${p.command}")
        }
    }

    @Test fun askedReachesTheAppAsSaid() {
        // Not split into two questions or cleaned into something else before the app reads it.
        listOf("make the case for trading now", "pros and cons of trading now", "talk me through it", "explain the trade check",
            "should I trade now and why?", "what would you watch now?", "Jarvis, make the case").forEach { q ->
            val qs = Understand.questions(null, q)
            assertTrue(qs == null || qs == listOf(q) || qs.singleOrNull()?.let { TradeCase.asked(it) } == true, "$q: $qs")
        }
    }

    @Test fun calmMiddayIsLaidOutBothWaysAndEndsWithBoss() {
        val c = TradeCase.build(input(now(reads = listOf("Nifty is bullish now (+0.3% on the day)."))))
        val s = c.say()
        noAdvice(s)
        assertTrue(s.startsWith("Here's the case as I see it, Boss"))
        assertTrue(s.contains("Nifty is bullish now") && s.contains("not a forecast"), s)
        assertTrue(c.steady.any { it.startsWith("There is time in the session: 3h 55m") }, c.steady.toString())
        assertTrue(c.steady.any { it.contains("Live prices are coming in") })
        assertTrue(c.steady.size <= TradeCase.MAX_STEADY)
        assertTrue(c.careful.isEmpty(), c.careful.toString())
        assertFalse(s.contains("On the careful side"))
    }

    @Test fun risksGoOnTheCarefulSideWithWhatToWatch() {
        val c = TradeCase.build(input(now(minute = 9 * 60 + 17, vix = 24.5, vixCh = 11.0, gap = 1.4, events = listOf("RBI policy"), expiry = true,
            burst = 0.6, usual = 0.15)))
        val s = c.say(); noAdvice(s)
        assertTrue(c.careful.any { it.contains("first five minutes") })
        assertTrue(c.careful.any { it.contains("India VIX is high at 24.5") })
        assertTrue(c.careful.any { it.contains("+11.0% today") })
        assertTrue(c.careful.any { it == "Event today: RBI policy." })
        assertTrue(c.careful.any { it.contains("expiry day") })
        assertTrue(c.careful.size <= TradeCase.MAX_CAREFUL)
        assertTrue(c.watch.any { it.startsWith("09:20") }, c.watch.toString())
        assertTrue(c.watch.size <= TradeCase.MAX_WATCH)
        assertTrue(s.contains("What I'd watch: 09:20"), s)
    }

    @Test fun guardsAndThePlumbing() {
        val c = TradeCase.build(input(now(live = true, zerodha = false, fresh = false, kill = true, breaker = true, stopped = true)))
        noAdvice(c.say())
        assertTrue(c.careful.contains("Zerodha is not logged in today."))
        assertTrue(c.careful.any { it.startsWith("Live prices are not coming in") })
        assertTrue(c.careful.any { it.startsWith("The kill switch is on") })
        assertTrue(c.careful.any { it.contains("loss breaker is tripped") })
        assertFalse(c.steady.any { it.contains("guards are clear") })
    }

    @Test fun closedMarketSaysSoAndStillLooksAhead() {
        val c = TradeCase.build(input(now(minute = 16 * 60, marketOpen = false), upcoming = listOf(
            Events.Event(today, "NIFTY expiry"), Events.Event(today.plusDays(1), "US Fed decision overnight (FOMC)"), Events.Event(today.plusDays(9), "Far away"))))
        val s = c.say(); noAdvice(s)
        assertTrue(c.careful.contains("The market is closed for the day."))
        assertTrue(c.watch.contains("coming up: tomorrow: US Fed decision overnight (FOMC)"), c.watch.toString())
        assertFalse(s.contains("Far away"))
        assertFalse(s.contains("There is time in the session"))
        val holiday = TradeCase.build(input(now(tradingDay = false, marketOpen = false)))
        assertEquals(listOf("The market is closed today."), holiday.careful)
    }

    @Test fun bossDayAgainstHisLimit() {
        val down = TradeCase.build(input(now(dayPnl = -6_000.0, limit = 10_000.0)))
        assertTrue(down.careful.contains("You are down Rs 6,000 today, 60% of your Rs 10,000 daily loss limit."), down.careful.toString())
        assertTrue(down.watch.any { it.startsWith("Room to the daily loss limit: Rs 4,000") }, down.watch.toString())
        noAdvice(down.say())
        val fine = TradeCase.build(input(now(dayPnl = 1_200.0)))
        assertTrue(fine.steady.any { it.startsWith("Your day so far is +Rs 1,200, well inside") }, fine.steady.toString())
    }

    @Test fun lockedPhoneNeverSaysBossesDayGoalsOrRules() {
        val mine = TradeCase.Mine(notes = listOf("no trades after 2 pm", "I get greedy after a win"),
            goals = listOf(Goals.Status(Goals.Goal(Goals.Kind.MAX_LOSS, Goals.Period.DAY, 5_000.0), "Loss limit today: x", broken = true, near = false, met = false)))
        val c = TradeCase.build(input(now(minute = 14 * 60 + 20, dayPnl = -6_000.0), mine = mine, locked = true))
        val s = c.say()
        noAdvice(s)
        assertTrue(s.contains(TradeCase.LOCKED_NOTE))
        val said = s.replace(TradeCase.LOCKED_NOTE, "")
        assertFalse(said.contains("Rs"), s)
        assertFalse(said.contains("6,000") || said.contains("2 pm") || said.contains("greedy") || said.contains("goal"), s)
        // Unlocked: all of it.
        val u = TradeCase.build(input(now(minute = 14 * 60 + 20, dayPnl = 500.0), mine = mine)).say()
        assertTrue(u.contains("\"no trades after 2 pm\", and it's 14:20"), u)
        assertTrue(u.contains("lose no more than Rs 5,000.00 today is already broken"), u)
        assertTrue(u.contains("greedy after a win"), u)
        assertFalse(u.contains(TradeCase.LOCKED_NOTE))
        noAdvice(u)
    }

    @Test fun rulesStartingSoonAreWatched() {
        val mine = TradeCase.Mine(notes = listOf("no new trades after 2 pm", "no trades before 9:45", "I don't trade BankNifty"))
        val c = TradeCase.build(input(now(minute = 13 * 60 + 10), mine = mine))
        assertTrue(c.watch.any { it == "14:00, when your rule \"no new trades after 2 pm\" starts (in 50 minutes)" }, c.watch.toString())
        assertTrue(c.watch.any { it.contains("\"I don't trade BankNifty\"") })
        assertFalse(c.careful.any { it.contains("9:45") }, "past 09:45 the rule is not in force")
        val early = TradeCase.build(input(now(minute = 9 * 60 + 30), mine = mine))
        assertTrue(early.careful.any { it.contains("\"no trades before 9:45\", and it's 09:30") }, early.careful.toString())
        assertTrue(early.watch.any { it.startsWith("09:45, when your rule") })
        noAdvice(early.say())
    }

    @Test fun goalsAndHabits() {
        val target = Goals.Status(Goals.Goal(Goals.Kind.TARGET, Goals.Period.DAY, 2_000.0), "Target today: +Rs 2,500.00 of +Rs 2,000.00 - reached; protect it.", broken = false, near = false, met = true)
        val trades = Goals.Status(Goals.Goal(Goals.Kind.MAX_TRADES, Goals.Period.DAY, 3.0), "Trades today: 3 of at most 3 - the day's limit is reached.", broken = false, near = true, met = false)
        // A losing trade closed five minutes ago.
        val own = listOf(Insights.Trip("NIFTY25O0725000CE", at(10, 30), at(10, 55), -400.0, "Manual"))
        val c = TradeCase.build(input(now(), mine = TradeCase.Mine(goals = listOf(target, trades), own = own, maxTrades = 3)))
        val s = c.say(); noAdvice(s)
        assertTrue(c.careful.contains("Your own goal to make Rs 2,000.00 today is reached."), c.careful.toString())
        assertTrue(c.careful.contains("Your own goal of no more than 3 trades a day is reached."))
        assertTrue(c.careful.any { it.startsWith("Your last trade lost, 5 minutes ago.") }, c.careful.toString())
        assertFalse(s.contains("protect it"))
        val calm = TradeCase.build(input(now(), mine = TradeCase.Mine(goals = listOf(Goals.Status(Goals.Goal(Goals.Kind.TARGET, Goals.Period.DAY, 2_000.0),
            "Target today: +Rs 500.00 of +Rs 2,000.00 (25%).", false, false, false)))))
        assertTrue(calm.steady.any { it.startsWith("Your own goals are inside") })
        assertTrue(calm.watch.contains("Target today: +Rs 500.00 of +Rs 2,000.00 (25%)"), calm.watch.toString())
    }

    @Test fun armsByTheirTestedRecord() {
        val c = TradeCase.build(input(now(minute = 10 * 60 + 30, orr = 0.5, arms = listOf("Liquidity 15+5", "ORB", "Unknown arm"))))
        assertTrue(c.steady.any { it.startsWith("Tested: Liquidity 15+5 made money in both years") } || c.steady.size == TradeCase.MAX_STEADY)
        assertTrue(c.careful.any { it.startsWith("Tested: ORB lost in both years") })
        assertTrue(c.careful.any { it.contains("narrow, 0.5 times") && it.contains("ORB breakouts failed most") })
        assertFalse(c.say().contains("Unknown arm"))
        noAdvice(c.say())
    }

    @Test fun hisOwnRecordInConditionsLikeNow() {
        // 12 ideas in the first 45 minutes, 2 worked: clearly poor; 12 around midday, 11 worked: clearly good.
        val bad = (1..12).map { k -> SelfCalibration.Outcome(SelfCalibration.Conditions(today.minusDays(k.toLong()).atTime(9, 40), Market.NIFTY, k % 2 == 0, "news"), if (k <= 2) 10.0 else -12.0) }
        val good = (1..12).map { k -> SelfCalibration.Outcome(SelfCalibration.Conditions(today.minusDays(k.toLong()).atTime(12, 30), Market.BANKNIFTY, k % 2 == 0, "pattern hammer"), if (k <= 11) 15.0 else -5.0) }
        val early = TradeCase.build(input(now(minute = 9 * 60 + 40), calibration = bad + good))
        assertTrue(early.careful.any { it.startsWith("My own ideas in the first 45 minutes have a clearly poor record (12 ideas, 2 worked") }, early.careful.toString())
        val noon = TradeCase.build(input(now(minute = 12 * 60 + 30), calibration = bad + good))
        assertTrue(noon.steady.any { it.startsWith("My own ideas around midday have held up") && it.contains("never makes me take more") }, noon.steady.toString())
        assertFalse(noon.careful.any { it.startsWith("My own ideas") })
        noAdvice(early.say()); noAdvice(noon.say())
        // Too few ideas: nothing said.
        assertTrue(TradeCase.calibration(input(calibration = bad.take(3))).let { it.first.isEmpty() && it.second.isEmpty() })
    }

    @Test fun whatStandsOutFromTheCandles() {
        val bars = mapOf(Market.NIFTY to Fixtures.indexDays(12, from = today.minusDays(16)))
        val day = bars.getValue(Market.NIFTY).last().t
        val c = TradeCase.build(input(now(minute = 15 * 60 + 29), t = day, bars = bars))
        // Either something stands out, or it says nothing does - never both, and never a cause or advice.
        assertTrue(c.standout.isNotEmpty() xor c.steady.any { it.startsWith("Nothing stands out about today") }, "${c.standout} ${c.steady}")
        noAdvice(c.say())
    }

    @Test fun neverAdviceAcrossManyDays() {
        val r = java.util.Random(3)
        repeat(200) {
            val n = now(minute = 9 * 60 + r.nextInt(400), marketOpen = r.nextBoolean(), live = r.nextBoolean(), zerodha = r.nextBoolean(),
                fresh = r.nextBoolean(), kill = r.nextInt(5) == 0, breaker = r.nextInt(5) == 0, stopped = r.nextInt(4) == 0,
                dayPnl = if (r.nextBoolean()) r.nextGaussian() * 6_000 else null, vix = 10 + r.nextDouble() * 20, vixCh = r.nextGaussian() * 6,
                index = r.nextGaussian(), gap = r.nextGaussian(), burst = r.nextGaussian() * 0.4, usual = 0.12,
                orr = if (r.nextBoolean()) 0.3 + r.nextDouble() else null, events = if (r.nextInt(3) == 0) listOf("CPI data") else emptyList(),
                expiry = r.nextInt(5) == 0, arms = TradeCheck.RECORD.keys.filter { r.nextBoolean() })
            val mine = TradeCase.Mine(notes = listOf("no trades after 2 pm", "skip expiry days", "I get greedy after a win", "my target today is 3000"))
            val locked = r.nextBoolean()
            val s = TradeCase.build(input(n, mine = mine, locked = locked)).say()
            noAdvice(s)
            if (locked) assertFalse(s.contains("day so far") || s.contains("You are down") || s.contains("Room to") || s.contains("greedy") || s.contains("2 pm") || s.contains("expiry days"), s)
        }
    }
}
