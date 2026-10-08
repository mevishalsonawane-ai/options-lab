package com.optionslab.ira

import com.optionslab.engine.orb.LiquidityShadow
import com.optionslab.engine.orb.ShadowRules
import com.optionslab.ira.WeeklyReview.Group
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jarvis's weekly review: each of its eight parts from plain inputs, when it is due, how it is kept, and the question. */
class WeeklyReviewTest {
    private val mon: LocalDate = LocalDate.of(2026, 10, 5)            // Monday
    private val fri: LocalDate = mon.plusDays(4)
    private fun weekdays(d: LocalDate) = d.dayOfWeek.value <= 5

    private fun t(g: Group, name: String, day: LocalDate, net: Double, charges: Double = 40.0) = WeeklyReview.Trade(g, name, day, net, charges)

    // ---- 1. money ----

    @Test fun ownersFallIntoTheirGroups() {
        val pine = setOf("My EMA cross")
        assertEquals(Group.LIQUIDITY, WeeklyReview.groupOf("Liquidity 5m FINNIFTY", pine))
        assertEquals(Group.LIQUIDITY, WeeklyReview.groupOf("Liquidity 15+5", pine))
        assertEquals(Group.HERO, WeeklyReview.groupOf("Hero", pine))
        assertEquals(Group.SOLO, WeeklyReview.groupOf("Jarvis solo", pine))
        assertEquals(Group.JARVIS, WeeklyReview.groupOf("Jarvis news", pine))
        assertEquals(Group.MANUAL, WeeklyReview.groupOf("Manual", pine))
        assertEquals(Group.PINE, WeeklyReview.groupOf("My EMA cross", pine))
        assertEquals(Group.OTHER, WeeklyReview.groupOf("ORB", pine))
    }

    private val trades = listOf(
        t(Group.LIQUIDITY, "BankNifty 15-min", mon, 1_200.0), t(Group.LIQUIDITY, "BankNifty 15-min", mon.plusDays(1), -500.0),
        t(Group.LIQUIDITY, "FinNifty 5-min", mon.plusDays(2), 300.0),
        t(Group.SOLO, "Solo (midday)", mon.plusDays(3), -900.0),
        t(Group.PINE, "My EMA cross", fri, 250.0), t(Group.PINE, "Gap fill", fri, -50.0),
        t(Group.MANUAL, "Manual", mon.plusDays(1), 100.0),
        // Last week.
        t(Group.LIQUIDITY, "BankNifty 15-min", mon.minusDays(3), -800.0), t(Group.JARVIS, "Jarvis news", mon.minusDays(4), 60.0),
        // Two weeks back: not counted at all.
        t(Group.MANUAL, "Manual", mon.minusDays(10), 9_999.0),
    )

    @Test fun moneyByStrategyAgainstLastWeek() {
        val m = WeeklyReview.money(trades, mon, heroArmed = false)
        assertEquals(listOf("Liquidity 15+5", "Solo (midday)", "Pine scripts", "Jarvis's own trades", "Manual"), m.rows.map { it.name })
        val liq = m.rows.first()
        assertEquals(3, liq.trades); assertEquals(2, liq.won); assertEquals(1_000.0, liq.net, 1e-9); assertEquals(120.0, liq.charges, 1e-9)
        assertEquals(1, liq.prevTrades); assertEquals(-800.0, liq.prevNet, 1e-9); assertEquals(67, liq.winPct)
        assertEquals(listOf("BankNifty 15-min", "FinNifty 5-min"), m.detail[Group.LIQUIDITY]!!.map { it.name })
        assertEquals(listOf("Gap fill", "My EMA cross"), m.detail[Group.PINE]!!.map { it.name })
        assertEquals(7, m.total.trades); assertEquals(400.0, m.total.net, 1e-9); assertEquals(2, m.total.prevTrades)
        // Jarvis's own traded only last week: its row says so.
        val lines = WeeklyReview.moneyLines(m)
        assertEquals("Liquidity 15+5: 3 trades, net +₹1,000, 67% won, charges ₹120 (last week: 1 trade, −₹800).", lines[0])
        assertEquals("  · BankNifty 15-min: 2 trades, net +₹700, 50% won, charges ₹80 (last week: 1 trade, −₹800).", lines[1])
        assertTrue("Jarvis's own trades: no trades (last week: 1 trade, +₹60)." in lines, "$lines")
        assertEquals("Total: 7 trades, net +₹400, 57% won, charges ₹280; last week −₹740 on 2 trades.", lines.last())
        // Hero: a row only when armed or it traded; Liquidity always.
        assertTrue(m.rows.none { it.name == "Hero (expiry)" })
        assertTrue(WeeklyReview.money(emptyList(), mon, heroArmed = true).rows.map { it.name } == listOf("Liquidity 15+5", "Hero (expiry)"))
        assertEquals("Total: no paper trades closed this week; none last week.", WeeklyReview.moneyLines(WeeklyReview.money(emptyList(), mon, false)).last())
    }

    /** 08 Oct (research X1): Liquidity's BANKNIFTY record on its own, apart from FINNIFTY and MIDCPNIFTY. */
    @Test fun liquidityBankniftyIsJudgedOnItsOwn() {
        val tagged = trades.map { if (it.group == Group.LIQUIDITY) it.copy(index = if (it.name.startsWith("Bank")) "BANKNIFTY" else "FINNIFTY") else it }
        val m = WeeklyReview.money(tagged, mon, heroArmed = false)
        assertEquals(listOf("Liquidity 15+5 BANKNIFTY", "Liquidity 15+5 FINNIFTY"), m.liquidityByIndex.map { it.name })
        val bn = m.liquidityByIndex.first()
        assertEquals(2, bn.trades); assertEquals(700.0, bn.net, 1e-9); assertEquals(1, bn.prevTrades)
        val lines = WeeklyReview.moneyLines(m)
        assertTrue("  = Liquidity 15+5 BANKNIFTY on its own: 2 trades, net +₹700, 50% won, charges ₹80 (last week: 1 trade, −₹800)." in lines, "$lines")
        assertTrue("  = Liquidity 15+5 FINNIFTY on its own: 1 trade, net +₹300, 100% won, charges ₹40 (last week: none)." in lines, "$lines")
        // Untagged trades (an older record): no per-index rows.
        assertTrue(WeeklyReview.money(trades, mon, heroArmed = false).liquidityByIndex.isEmpty())
    }

    // ---- 2. live vs backtest ----

    private fun ft(i: Int, x: Double) = ForwardCheck.Trade(mon.plusDays(1 + i / 10L), x)
    private val liqInLine = WeeklyReview.Forward("Liquidity 15+5", ForwardCheck.check(ForwardCheck.LIQUIDITY, (0 until 24).map { ft(it, if (it % 2 == 0) 1_400.0 else -1_000.0) }))
    private val soloAlarm = WeeklyReview.Forward("Solo (midday)", ForwardCheck.check(ForwardCheck.SOLO, (0 until 12).map { ft(it, -3_000.0) }))
    private val heroFew = WeeklyReview.Forward("Hero (expiry)", ForwardCheck.check(ForwardCheck.HERO, (0 until 3).map { ft(it, -1_500.0) }))

    @Test fun verdictsAndTheCusumWarning() {
        val l = WeeklyReview.forwardLines(listOf(liqInLine, soloAlarm, heroFew))
        assertTrue(l[0].startsWith("Liquidity 15+5: in line ("), l[0])
        assertTrue(l[1].startsWith("Solo (midday): below expectation (12 trades)"), l[1])
        assertTrue(l[2].startsWith("Hero (expiry): too few trades (<20)"), l[2])
        assertEquals(1, l.count { it.startsWith("CUSUM warning - Solo (midday): a sustained run below its backtest since trade") })
        assertEquals(listOf("No running strategy to compare with its backtest this week."), WeeklyReview.forwardLines(emptyList()))
    }

    // ---- 3. shadows ----

    private fun sh(name: String, week: Int, weekNet: Double, trades: Int, net: Double, won: Double, lost: Double, promoted: Boolean = false) =
        WeeklyReview.Shadow(name, "ORB", week, weekNet, ShadowRules.Summary(trades, net, won, lost), promoted)

    @Test fun bestWorstAndTheBar() {
        val ss = listOf(sh("OP10", 3, 900.0, 41, -1_200.0, 4_000.0, 5_200.0), sh("V43", 2, -1_500.0, 63, 6_000.0, 20_000.0, 14_000.0),
            sh("O08", 0, 0.0, 12, 300.0, 1_300.0, 1_000.0), sh("FP10", 1, 50.0, 62, 1_000.0, 7_000.0, 6_000.0))
        val l = WeeklyReview.shadowLines(ss)
        assertEquals("Best shadow this week: OP10 (ORB), 3 trades, +₹900.", l[0])
        assertEquals("Worst: V43 (ORB), 2 trades, −₹1,500.", l[1])
        assertEquals("Toward the 60-trade bar: V43 60/60, +₹6,000 (met the bar); FP10 60/60, +₹1,000 (enough trades, not the net or profit factor); " +
            "OP10 41/60, −₹1,200; O08 12/60, +₹300.", l[2])
        assertEquals("No shadow closed a trade this week.", WeeklyReview.shadowLines(listOf(sh("O08", 0, 0.0, 12, 300.0, 1_300.0, 1_000.0)))[0])
        assertEquals(listOf("No shadow has a record yet."), WeeklyReview.shadowLines(emptyList()))
    }

    // ---- 4. candidates ----

    @Test fun candidatesHelpedSoFar() {
        val d = LiquidityShadow.SINCE
        // (a) drops the losing near-level trades; FINNIFTY 30m wins; (c) and (f) not yet recorded; (d) and (e) priced on one.
        val ts = listOf(
            LiquidityShadow.Trade(d, 1_000.0, false, "liquidity15", volSkip = null, exit1430 = 800.0, itm2 = 1_300.0),
            LiquidityShadow.Trade(d, -600.0, true, "liquidity5"),
            LiquidityShadow.Trade(d.plusDays(1), 400.0, false, "liquidity30_fin"),
        )
        val s = LiquidityShadow.summarize(ts)
        val c = WeeklyReview.candidates(s).associateBy { it.key }
        assertEquals(true, c["a"]!!.helped)
        assertEquals(false, c["b"]!!.helped)
        assertNull(c["c"]!!.helped); assertNull(c["f"]!!.helped)
        assertEquals(false, c["d"]!!.helped); assertEquals(true, c["e"]!!.helped)
        val l = WeeklyReview.candidateLines(s)
        assertTrue(l[0].startsWith("Liquidity 15+5 has 3 of 40 paper trades since 06 Oct"), l[0])
        assertEquals("Helped so far: (a), (e).", l[1])
        assertTrue("(a) skip a level within one index stop: helped so far - +₹700 a trade over 2 trades against +₹267." in l, "$l")
        assertTrue("(c) skip in high volatility: no trades to compare yet." in l, "$l")
        assertEquals("Nothing changes by itself: adopting one is yours.", l.last())
        assertEquals(listOf("Liquidity 15+5's record could not be read this time."), WeeklyReview.candidateLines(null))
    }

    // ---- 5. discipline ----

    @Test fun disciplineCountsThisWeekAgainstLast() {
        val at = mon.atTime(10, 0)
        val log = listOf(
            Activity.Entry(at, "Warned: 5 trades in 30 minutes."), Activity.Entry(at.plusDays(2), "Warned: 4 trades in 30 minutes."),
            Activity.Entry(at.plusDays(1), CoolOff.say(at.plusDays(1).plusMinutes(30))),
            Activity.Entry(at.minusDays(3), "Warned: 4 trades in 30 minutes."),
            Activity.Entry(at, "Gave the week's review of your own trades."),
        )
        val wrong = listOf(Mistakes.Entry(at, "nifty", "x"), Mistakes.Entry(at.minusDays(2), "a", "b"), Mistakes.Entry(at.minusDays(2), "c", "d"))
        // Boss's own trades: two costly ones in the first five minutes.
        val own = listOf(Insights.Trip("NIFTY X", mon.atTime(9, 16), mon.atTime(9, 40), -400.0, "Manual"),
            Insights.Trip("NIFTY Y", mon.plusDays(1).atTime(9, 17), mon.plusDays(1).atTime(9, 50), -300.0, "Manual"))
        val d = WeeklyReview.discipline(log, wrong, own, mon)
        assertEquals(2, d.overtrade); assertEquals(1, d.coolOffs); assertEquals(1, d.markedWrong)
        assertEquals(1, d.prevOvertrade); assertEquals(0, d.prevCoolOffs); assertEquals(2, d.prevMarkedWrong)
        assertEquals(listOf(WeekReview.Kind.FIRST_MINUTES), d.habits.map { it.kind })
        val l = WeeklyReview.disciplineLines(d)
        assertEquals("2 overtrading warnings (last week 1), 1 cooling-off (last week 0), 1 answer (last week 2) you marked wrong.", l[0])
        assertEquals("Your own trades: trades in the first five minutes cost you (2 trades, last week 0).", l[1])
    }

    // ---- 6. market ----

    private fun day(d: LocalDate, c: Double) = listOf(Candle(d.atTime(9, 15), c, c, c, c - 50), Candle(d.atTime(15, 29), c, c, c, c))

    @Test fun theMarketsWeek() {
        val nifty = day(mon.minusDays(3), 25_000.0) + day(mon, 25_100.0) + day(mon.plusDays(1), 24_700.0) + day(fri, 25_250.0)
        val bank = day(mon.minusDays(3), 55_000.0) + day(fri, 54_450.0)
        val vix = day(mon.minusDays(3), 12.5) + day(fri, 13.75)
        assertEquals(listOf(mon.minusDays(3) to 25_000.0, mon to 25_100.0), WeeklyReview.closes(day(mon, 25_100.0) + day(mon.minusDays(3), 25_000.0)))
        val fiiNow = MorningCues.Fii(mon.plusDays(3), 30_000, 70_000, 0, 0, 0, 0)
        val fiiBefore = MorningCues.Fii(mon.minusDays(3), 36_000, 64_000, 0, 0, 0, 0)
        val l = WeeklyReview.marketLines(WeeklyReview.MarketInput(mapOf(Market.NIFTY to WeeklyReview.closes(nifty), Market.BANKNIFTY to WeeklyReview.closes(bank),
            Market.VIX to WeeklyReview.closes(vix)), fiiNow, fiiBefore, 4, 5), mon)
        assertEquals("The week: Nifty +1.0% to 25,250.00, BankNifty -1.0% to 54,450.00.", l[0])
        assertEquals("Biggest day: Fri 9 Oct, Nifty +2.2%.", l[1])
        assertEquals("India VIX 12.50 to 13.75 (+1.25).", l[2])
        assertEquals("FIIs' index futures: 36% long on Fri 2 Oct to 30% on Thu 8 Oct - more short (-6 points). Positions, not a forecast.", l[3])
        assertEquals("Market recorder: 4 of 5 sessions recorded.", l[4])
        // Nothing on the phone: said so.
        val none = WeeklyReview.marketLines(WeeklyReview.MarketInput(emptyMap(), null, null, 0, 5), mon)
        assertEquals("No index closes on the phone for this week.", none[0])
        assertEquals("FII positioning: no NSE participant OI recorded this week.", none[1])
    }

    // ---- 7. next week ----

    @Test fun nextWeekFromTheCalendar() {
        val next = mon.plusWeeks(1)
        val holiday = next.plusDays(1)                                          // Tue 13 Oct shut: Nifty's expiry moves to Mon
        val trading = { d: LocalDate -> weekdays(d) && d != holiday }
        val exp = mapOf(Market.NIFTY to listOf(next, next.plusWeeks(1)), Market.SENSEX to listOf(next.plusDays(3)), Market.BANKNIFTY to listOf(next.plusDays(22)))
        val l = WeeklyReview.nextWeek(mon, trading, { d -> if (d == holiday) "Dussehra" else null }, exp,
            listOf(Events.Event(next.plusDays(2), "US Fed decision overnight (FOMC)"), Events.Event(next.plusWeeks(2), "Far away")), heroArmed = true)
        assertEquals(listOf(
            "Next week (Mon 12 Oct to Fri 16 Oct): 4 sessions.",
            "Expiries: Mon 12 Oct Nifty; Thu 15 Oct Sensex.",
            "Holidays: Tue 13 Oct (Dussehra).",
            "Events: Wed 14 Oct US Fed decision overnight (FOMC).",
            "Hero's expiry days: Mon 12 Oct (it is armed, paper only).",
        ), l)
        val bare = WeeklyReview.nextWeek(mon, ::weekdays, { null }, emptyMap(), emptyList(), heroArmed = false)
        assertEquals("Expiries: not loaded yet (the contracts load in the morning).", bare[1])
        assertEquals("Hero's expiry days: not known until the contracts load.", bare.last())
    }

    // ---- 8. the line to watch ----

    private val forbidden = Regex("(?i)\\b(more lots|bigger|increase|double|add (more|size)|switch (it )?(on|off)|turn (it )?(on|off)|disarm|buy|sell|₹)")

    @Test fun theWatchLineIsHonestAndNeverAdvice() {
        val few = WeeklyReview.Forward("Liquidity 15+5", ForwardCheck.check(ForwardCheck.LIQUIDITY, (0 until 7).map { ft(it, 100.0) }))
        val below = WeeklyReview.Forward("Liquidity 15+5", ForwardCheck.check(ForwardCheck.LIQUIDITY, (0 until 25).map { ft(it, if (it % 2 == 0) 300.0 else -1_700.0) }))
        assertEquals(ForwardCheck.Verdict.BELOW, below.result.verdict); assertFalse(below.result.alarm)
        val calm = WeeklyReview.Discipline(0, 0, 0, 0, 0, 0, emptyList())
        val busy = calm.copy(overtrade = 3)
        val cases = listOf(
            WeeklyReview.watch(listOf(liqInLine, soloAlarm), emptyList(), calm, null) to "Solo (midday) has kept coming in below its backtest since trade",
            WeeklyReview.watch(listOf(below), emptyList(), busy, null) to "Liquidity 15+5 is below expectation after 25 trades - one more week of trades before it's worth reviewing.",
            WeeklyReview.watch(listOf(liqInLine, few), emptyList(), busy, null) to "3 overtrading warnings this week",
            WeeklyReview.watch(listOf(liqInLine, few), emptyList(), calm, null) to "Liquidity 15+5 has 7 of 20 trades - too few to judge yet",
            WeeklyReview.watch(listOf(liqInLine), listOf(sh("V43", 1, 10.0, 54, 900.0, 3_000.0, 2_100.0)), calm, null) to "V43 is 6 trades from the 60-trade bar",
            WeeklyReview.watch(listOf(liqInLine), emptyList(), calm, null) to "Nothing stands out",
        )
        for ((got, want) in cases) {
            assertTrue(got.startsWith(want), "$got / $want")
            assertFalse(forbidden.containsMatchIn(got), got)
        }
    }

    // ---- when it is due ----

    @Test fun dueAfterTheWeeksLastSession() {
        assertTrue(WeeklyReview.isLastSession(fri, ::weekdays))
        assertFalse(WeeklyReview.isLastSession(fri.minusDays(1), ::weekdays))
        // Friday a holiday: Thursday is the week's last session.
        val friShut = { d: LocalDate -> weekdays(d) && d != fri }
        assertTrue(WeeklyReview.isLastSession(fri.minusDays(1), friShut))
        assertEquals(fri.minusDays(1), WeeklyReview.lastSession(mon, friShut))
        // Wednesday before a Thursday holiday is not the week's last (Friday trades).
        val thuShut = { d: LocalDate -> weekdays(d) && d != fri.minusDays(1) }
        assertFalse(WeeklyReview.isLastSession(mon.plusDays(2), thuShut))
        // Friday 15:44: last week's (if not made); 15:45: this week's; made: nothing.
        assertEquals(mon.minusWeeks(1), WeeklyReview.due(fri.atTime(15, 44), emptyList(), ::weekdays))
        assertNull(WeeklyReview.due(fri.atTime(15, 44), listOf(mon.minusWeeks(1)), ::weekdays))
        assertEquals(mon, WeeklyReview.due(fri.atTime(15, 45), listOf(mon.minusWeeks(1)), ::weekdays))
        assertNull(WeeklyReview.due(fri.atTime(18, 0), listOf(mon), ::weekdays))
        // The phone was off on Friday: Saturday or Monday morning catches it up - only the newest week.
        assertEquals(mon, WeeklyReview.due(fri.plusDays(1).atTime(9, 0), emptyList(), ::weekdays))
        assertEquals(mon, WeeklyReview.due(mon.plusWeeks(1).atTime(9, 0), emptyList(), ::weekdays))
        // A week with no session at all has no review.
        assertNull(WeeklyReview.due(mon.plusWeeks(1).atTime(9, 0), emptyList()) { false })
        assertTrue(WeeklyReview.READY_AT.hour == 15 && WeeklyReview.READY_AT.minute == 45)
        assertEquals(DayOfWeek.MONDAY, WeeklyReview.mondayOf(fri).dayOfWeek)
    }

    // ---- the review, kept and asked ----

    private fun input(soFar: Boolean = false) = WeeklyReview.Input(mon, fri, fri.atTime(15, 45), trades, heroArmed = false, forward = listOf(liqInLine, heroFew),
        shadows = listOf(sh("OP10", 3, 900.0, 41, -1_200.0, 4_000.0, 5_200.0)), liquidity = null, discipline = WeeklyReview.Discipline(0, 0, 0, 0, 0, 0, emptyList()),
        market = null, nextWeek = listOf("Next week (Mon 12 Oct to Fri 16 Oct): 5 sessions."), soFar = soFar)

    @Test fun theReviewInThreeSentences() {
        val r = WeeklyReview.build(input())
        assertEquals("Week of 5 Oct", r.title)
        assertEquals(listOf("Money (paper)", "Live vs backtest", "Shadows", "Liquidity candidates (a)-(f)", "Discipline", "Market", "Next week", "What I'd watch"),
            r.sections.map { it.title })
        assertEquals("The week of 5 Oct: paper net +₹400 on 7 trades, 57% won (last week −₹740). Live vs backtest: Liquidity 15+5 in line, " +
            "Hero (expiry) too few trades (<20). Hero (expiry) has 3 of 20 trades - too few to judge yet; the verdict needs more weeks of trades.", r.summary)
        // Plain: the same with no rupee figure (a locked phone, a notification).
        assertEquals("The week of 5 Oct: 7 paper trades, 57% won, ending up, better than last week. Live vs backtest: Liquidity 15+5 in line, " +
            "Hero (expiry) too few trades (<20). Hero (expiry) has 3 of 20 trades - too few to judge yet; the verdict needs more weeks of trades.", r.plain)
        assertFalse(r.plain.contains("₹"))
        assertEquals(3, r.summary.split(Regex("(?<=[.)])\\s+(?=[A-Z])")).size)
        val (title, text) = WeeklyReview.notice(r)
        assertEquals("Weekly review ready", title); assertEquals(r.plain, text); assertFalse(text.contains("₹"))
        assertEquals("Week of 5 Oct (so far)", WeeklyReview.build(input(soFar = true)).title)
        assertTrue(WeeklyReview.build(input(soFar = true)).summary.startsWith("This week so far: paper net"))
        assertTrue(WeeklyReview.say(WeeklyReview.build(input(soFar = true)), WeeklyReview.Which.LATEST, locked = false)
            .let { it.startsWith("Boss, this week so far: paper net") && it.endsWith("The whole review comes after the week's last session.") })
    }

    @Test fun keptTwelveWeeksAndReadBack() {
        val r = WeeklyReview.build(input()).let { it.copy(sections = it.sections + WeeklyReview.Section("Odd\ttitle \\ here", listOf("line\nwith a break", "\u001Eseparator"))) }
        val back = WeeklyReview.decode(WeeklyReview.encode(r))
        assertEquals(r.copy(sections = r.sections.dropLast(1) + WeeklyReview.Section("Odd\ttitle \\ here", listOf("line\nwith a break", "separator"))), back)
        assertNull(WeeklyReview.decode("garbage"))
        var all = emptyList<WeeklyReview.Review>()
        for (i in 0 until 15) all = WeeklyReview.keep(all, r.copy(monday = mon.minusWeeks(i.toLong())))
        assertEquals(WeeklyReview.KEEP_WEEKS, all.size)
        assertEquals(mon, all.first().monday); assertEquals(mon.minusWeeks(11), all.last().monday)
        // The same week again replaces it.
        all = WeeklyReview.keep(all, r.copy(watch = "again"))
        assertEquals(12, all.size); assertEquals("again", all.first().watch)
        assertEquals(all.map { WeeklyReview.decode(WeeklyReview.encode(it)) }, WeeklyReview.decodeAll(WeeklyReview.encodeAll(all)))
        assertEquals(emptyList(), WeeklyReview.decodeAll(null))
        // Picked: this week's when made, else the newest; "last week" the week before this one's.
        assertEquals(mon, WeeklyReview.pick(WeeklyReview.Which.LATEST, fri.plusDays(1), all)!!.monday)
        assertEquals(mon, WeeklyReview.pick(WeeklyReview.Which.LATEST, mon.plusWeeks(1), all)!!.monday)
        assertEquals(mon.minusWeeks(1), WeeklyReview.pick(WeeklyReview.Which.PREVIOUS, fri, all)!!.monday)
        assertNull(WeeklyReview.pick(WeeklyReview.Which.LATEST, fri, emptyList()))
        val said = WeeklyReview.say(all.first(), WeeklyReview.Which.LATEST, locked = true)
        assertTrue(said.startsWith("Boss, the week of 5 Oct: 7 paper trades") && said.endsWith("The whole review is on the Ira page."), said)
        assertFalse(said.contains("₹"))
        assertTrue(WeeklyReview.say(all.first(), WeeklyReview.Which.LATEST, locked = false).contains("+₹400"))
        assertTrue(WeeklyReview.say(null, WeeklyReview.Which.LATEST, false).startsWith("No weekly review yet, Boss"))
    }

    @Test fun theQuestion() {
        val latest = listOf("weekly review", "jarvis weekly review", "show me the weekly review", "is hafte ka review", "hafte ka review", "week ka review",
            "how did this week go", "how did the week go", "how was this week", "how was the week", "weekly report", "weekly recap",
            "review of the week", "week in review", "is hafta kaisa raha", "the weekly summary please", "what's in the weekly review",
            "give me the weekly round up", "is week ka hisaab", "weekly report card")
        for (q in latest) assertEquals(WeeklyReview.Which.LATEST, WeeklyReview.asked(q), q)
        val previous = listOf("last week's review", "review of last week", "pichle hafte ka review", "how did last week go", "how was last week",
            "previous week's report")
        for (q in previous) assertEquals(WeeklyReview.Which.PREVIOUS, WeeklyReview.asked(q), q)
        // Boss's own week, an index's, a strategy's, the bots', the month's, the week ahead, today's: not this.
        for (q in listOf("my weekly review", "how did my week go", "how did nifty do this week", "how was the week for banknifty", "how is liquidity doing",
            "how is solo doing", "how did my bots do this week", "how was the week for the bots", "monthly review", "how was the month",
            "what does this week look like", "week ahead", "plan for next week", "today's summary", "how did today go", "wrap up",
            "what is the weekly expiry", "is this an expiry week", "weekly", "review", "how are you", "what's the market doing this week"))
            assertNull(WeeklyReview.asked(q), q)
    }
}
