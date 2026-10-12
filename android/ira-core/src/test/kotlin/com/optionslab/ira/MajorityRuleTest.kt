package com.optionslab.ira

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Majority Rule 14 on weekly NIFTY ([MajorityRule], research R13): the steps and the zone, weekly closes from daily ones
 * (completed weeks only), Jarvis's words with the research's numbers, the agents' research note, and - Boss's decision of
 * 11 Oct - context only: the brain's weekly tag changes no entry, pause or guard decision, and nothing that sizes, gates or
 * enters reads it.
 */
class MajorityRuleTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    /** 15 weekly closes, oldest first, whose last 14 changes are [ups] ups then the rest downs. */
    private fun closes(ups: Int, start: Double = 20_000.0): List<Double> {
        val out = arrayListOf(start)
        repeat(14) { i -> out += out.last() + if (i < ups) 100.0 else -100.0 }
        return out
    }

    // ---- MR(14) and the zone -------------------------------------------------------------------------------------------------

    @Test fun mrMovesInStepsOf714AndTheZoneIsFourOfFourteenUp() {
        assertEquals(7.142857, MajorityRule.STEP, 1e-6)
        assertEquals(28.571428, MajorityRule.ZONE_MR, 1e-6)
        for (k in 0..14) {
            val mr = assertNotNull(MajorityRule.mr(closes(k)))
            assertEquals(100.0 * k / 14, mr, 1e-9, "$k ups")
            assertEquals(k, MajorityRule.ups(closes(k)))
            assertEquals(k <= 4, MajorityRule.zone(mr), "$k ups: $mr")
        }
        assertEquals(0.0, MajorityRule.mr(closes(0))!!, 1e-9)
        assertEquals(7.14, MajorityRule.mr(closes(1))!!, 0.005)
        assertEquals(28.57, MajorityRule.mr(closes(4))!!, 0.005)
        assertEquals(35.71, MajorityRule.mr(closes(5))!!, 0.005)
        assertEquals(100.0, MajorityRule.mr(closes(14))!!, 1e-9)
        // The said number 28.57 is in the zone; the next step (35.71) is not.
        assertTrue(MajorityRule.zone(28.57)); assertTrue(MajorityRule.zone(400.0 / 14)); assertFalse(MajorityRule.zone(500.0 / 14))
    }

    @Test fun onlyTheLastFourteenChangesCountAndFifteenClosesAreNeeded() {
        assertNull(MajorityRule.mr(closes(3).drop(1)), "14 closes give only 13 changes")
        assertNull(MajorityRule.mr(emptyList()))
        // Older history in front changes nothing; an equal close is not up; a bad close is left out.
        val longer = List(30) { 10_000.0 + it * 50 } + closes(2, 30_000.0)
        assertEquals(100.0 * 2 / 14, MajorityRule.mr(longer)!!, 1e-9)
        val flat = closes(4).toMutableList().also { it[it.size - 1] = it[it.size - 2] }
        assertEquals(4, MajorityRule.ups(flat))
        assertEquals(MajorityRule.ups(closes(6)), MajorityRule.ups(closes(6).toMutableList().also { it.add(3, Double.NaN) }))
    }

    // ---- weekly closes from daily ones ---------------------------------------------------------------------------------------

    /**
     * The fixture: NIFTY-like daily closes over 17 weeks to Friday 9 Oct 2026 - 12 weeks drifting up (alternating), then a
     * slide, with the week of 28 Sep - 2 Oct ending on Thursday 1 Oct (2 Oct is a holiday) at 22,422.
     */
    private fun fixture(): List<Pair<LocalDate, Double>> {
        val out = ArrayList<Pair<LocalDate, Double>>()
        var px = 24_000.0
        var day = LocalDate.of(2026, 6, 15)                     // a Monday
        val end = LocalDate.of(2026, 10, 9)
        var week = 0
        while (!day.isAfter(end)) {
            val dow = day.dayOfWeek.value
            if (dow <= 5 && day != LocalDate.of(2026, 10, 2)) {
                // Weeks 0-6 up most days, weeks 7-15 down, week 16 (5-9 Oct) flat to a hair lower.
                px += when {
                    week <= 6 -> if (dow % 2 == 1) 60.0 else -20.0
                    week <= 15 -> -40.0
                    else -> -2.4
                }
                // The research's live week: it closed at 22,422 on Thursday 1 Oct.
                if (day == LocalDate.of(2026, 10, 1)) px = 22_422.0
                out += day to px
            }
            if (dow == 7) week++
            day = day.plusDays(1)
        }
        return out
    }

    @Test fun weeksCloseOnTheirLastTradingDayAndOnlyCompletedWeeksCount() {
        val daily = fixture()
        // On Saturday 10 Oct the week of 5-9 Oct is complete.
        val sat = MajorityRule.weekly(daily, LocalDate.of(2026, 10, 10))
        assertEquals(17, sat.size)
        assertEquals(LocalDate.of(2026, 10, 9), sat.last().first)
        // The holiday week closes on Thursday 1 Oct at 22,422.
        assertEquals(LocalDate.of(2026, 10, 1) to 22_422.0, sat[sat.size - 2])
        // On Friday 9 Oct (or any weekday) that week is still in progress: left out.
        val fri = MajorityRule.weekly(daily, LocalDate.of(2026, 10, 9))
        assertEquals(16, fri.size); assertEquals(LocalDate.of(2026, 10, 1), fri.last().first)
        // A day after "today", a weekend day and a bad close never count; the input order does not matter.
        val noisy = daily.shuffled(java.util.Random(7)) + (LocalDate.of(2026, 10, 11) to 1.0) + (LocalDate.of(2026, 10, 20) to 99_999.0) +
            (LocalDate.of(2026, 10, 7) to Double.NaN)
        assertEquals(sat, MajorityRule.weekly(noisy, LocalDate.of(2026, 10, 10)))
    }

    @Test fun theFixtureIsInTheDeepWeeklySellOffZone() {
        val r = assertNotNull(MajorityRule.fromDaily(fixture(), LocalDate.of(2026, 10, 10)))
        // The last 14 weekly changes: 4 of the up weeks, then 10 down.
        assertEquals(4, r.ups)
        assertEquals(28.571428, r.mr, 1e-6)
        assertTrue(r.zone)
        assertEquals(LocalDate.of(2026, 10, 9), r.weekEnding)
        // Seen on Thursday 1 Oct's week alone (as of Friday 9 Oct): 5 of 14 up, just out of it.
        val fri = assertNotNull(MajorityRule.fromDaily(fixture(), LocalDate.of(2026, 10, 9)))
        assertEquals(5, fri.ups); assertFalse(fri.zone)
        // Too little history: no reading.
        assertNull(MajorityRule.fromDaily(fixture().takeLast(60), LocalDate.of(2026, 10, 10)))
        // A week missing from the phone's history breaks the run: weeks that are not neighbours are never compared.
        val gap = fixture().filterNot { it.first in LocalDate.of(2026, 8, 3)..LocalDate.of(2026, 8, 7) }
        val weeks = MajorityRule.weekly(gap, LocalDate.of(2026, 10, 10))
        assertEquals(16, weeks.size)
        assertEquals(9, MajorityRule.consecutive(weeks).size)
        assertNull(MajorityRule.reading(weeks))
        assertEquals(MajorityRule.weekly(fixture(), LocalDate.of(2026, 10, 10)), MajorityRule.consecutive(MajorityRule.weekly(fixture(), LocalDate.of(2026, 10, 10))))
    }

    // ---- Jarvis's words ------------------------------------------------------------------------------------------------------

    @Test fun jarvisSaysTheReadingAndTheResearchHonestly() {
        val r = MajorityRule.Reading(400.0 / 14, 4, LocalDate.of(2026, 10, 9), 22_520.4)
        val said = MajorityRule.say(r)
        assertTrue(said.startsWith("Majority Rule 14, Boss. NIFTY's MR(14) is 28.57 (4 of the last 14 weekly closes up, week ending 9 Oct): in the deep weekly sell-off zone."), said)
        // The research's own numbers (HUNT_R13_MAJORITY_RULE.md / FINDINGS.md R13).
        for (w in listOf("13 times", "about 8", "+6.2% after 4 weeks", "+7.4% after 8 weeks", "+0.9% and +1.8%", "33-50%", "36% for any green week",
            "break-even to losing", "no alert, no trade, never more size or risk"))
            assertTrue(w in said, w)
        val out = MajorityRule.say(r.copy(mr = 500.0 / 14, ups = 5))
        assertTrue("is 35.71 (5 of the last 14 weekly closes up, week ending 9 Oct): not in the deep weekly sell-off zone." in out, out)
        // No reading: how many weeks the phone holds, and the research's last live reading.
        val none = MajorityRule.say(null, 11)
        assertTrue("holds 11 completed weeks of NIFTY closes in a row and it needs 15" in none, none)
        assertTrue("week ending 1 Oct 2026, with NIFTY at 22,422" in none, none)
        assertTrue("1 completed week of" in MajorityRule.say(null, 1))
        // Never a word of Boss's account, never an order word that reads as one.
        for (s in listOf(said, out, none)) {
            assertFalse(Regex("(?i)\\b(your (p&l|pnl|account|position|positions|capital)|rs [0-9]|₹)").containsMatchIn(s), s)
            assertNull(Ask.parse(s).order, s)
        }
        assertTrue(MajorityRule.brainLine(r).startsWith("Weekly context: NIFTY in the deep weekly sell-off zone (MR(14) 28.57"))
        assertTrue("context only, no trading effect" in MajorityRule.brainLine(r))
    }

    @Test fun theQuestions() {
        for (q in listOf("what about majority rule?", "Jarvis, what about the majority rule", "majority rule 14", "Majority Rule", "MR 14", "mr14",
            "what is MR(14) now", "is nifty in the sell-off zone", "is Nifty in the deep weekly sell off zone?", "majority rules kya keh raha hai"))
            assertTrue(MajorityRule.asked(q), q)
        for (q in listOf("what is nifty at", "the majority of my trades lost", "what are my rules", "is a big move likely now", "mr president",
            "sell nifty", "what is the rule of 14", "weekly review"))
            assertFalse(MajorityRule.asked(q), q)
    }

    // ---- every agent knows it: Jarvis's research note --------------------------------------------------------------------------

    @Test fun theLessonsCarryTheResearchNoteAndItNeverBecomesALessonOrAProposal() {
        val note = AgentLessons.RESEARCH.single { it.id == "research:majority_rule_14" }
        assertEquals(MajorityRule.LESSON, note.text)
        for (w in listOf("tended to be higher 1-3 months later in % terms", "+1,000 points", "green-candle confirmation add nothing measurable",
            "break-even to losing", "Context only: no alert, no trade, never more size or risk"))
            assertTrue(w in note.text, w)
        // "What do you know about majority rule?": the note, on an empty book too.
        val a = assertNotNull(AgentLessons.asked("what do you know about majority rule"))
        assertEquals(AgentLessons.AskKind.ABOUT, a.kind); assertEquals("majority_rule", a.tag)
        val about = AgentLessons.sayAbout(AgentLessons.Book(), a)
        assertTrue(about.startsWith("From the research, Boss: Majority Rule 14 on weekly NIFTY"), about)
        // The model's notes: the note first when the question names it, within the cap; not when it does not.
        val notes = AgentLessons.forModel(AgentLessons.Book(), "what about majority rule?")
        assertEquals(listOf(MajorityRule.LESSON), notes)
        assertTrue(notes.sumOf { it.length + 2 } <= AgentLessons.MODEL_CHARS)
        assertTrue(AgentLessons.forModel(AgentLessons.Book(), "is nifty oversold on the weekly").isNotEmpty())
        assertTrue(AgentLessons.forModel(AgentLessons.Book(), "how is liquidity doing").isEmpty())
        // A night's pass never makes it a lesson, and nothing it says is ever proposed.
        val pass = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(LocalDate.of(2026, 10, 12)))
        assertTrue(pass.book.lessons.none { it.id.startsWith("research:") || "majority" in it.name.lowercase() })
        assertTrue(pass.proposals.isEmpty())
    }

    // ---- context only: no trading effect ---------------------------------------------------------------------------------------

    private fun ms(day: LocalDate, h: Int, m: Int) = day.atTime(h, m).atZone(ist).toInstant().toEpochMilli()

    @Test fun theWeeklyTagChangesNoEntryPauseOrGuardDecision() {
        val day = LocalDate.of(2026, 10, 12)
        val zone = MajorityRule.Reading(400.0 / 14, 4, LocalDate.of(2026, 10, 9), 22_520.4)
        val calm = MarketBrain.Context(atMs = ms(day, 9, 30))
        val news = calm.copy(windows = MarketBrain.windows(day, listOf("RBI policy"), listOf("NIFTY")))
        val noisy = news.copy(traps = mapOf("BANKNIFTY" to setOf("PULL_BID", "UNRELIABLE")), bigMove = MarketBrain.BigMove("NIFTY", -1, ms(day, 11, 0)),
            regimes = mapOf("NIFTY" to MarketBrain.Regime("NIFTY", MarketBrain.State.BUSY, 2.5, 2.2)))
        val policies = listOf(MarketBrain.Policy(), MarketBrain.Policy(MarketBrain.Cause.entries.associateWith { MarketBrain.Mode.ON }),
            MarketBrain.Policy(MarketBrain.Cause.entries.associateWith { MarketBrain.Mode.OFF }))
        val finding = Findings.Finding("order flow", Findings.Kind.ABSORPTION, "NIFTY", -1, 22_500.0, 80, ms(day, 10, 58))
        var checked = 0
        for (ctx in listOf(calm, news, noisy)) {
            val tagged = ctx.copy(weekly = zone)
            assertEquals(listOf(MajorityRule.TAG), tagged.weeklyTags()); assertTrue(ctx.weeklyTags().isEmpty())
            for (p in policies) for (u in listOf("NIFTY", "BANKNIFTY", "FINNIFTY", null)) for (side in listOf(1, -1))
                for (t in listOf(ms(day, 9, 20), ms(day, 9, 58), ms(day, 10, 2), ms(day, 11, 0) + 20_000, ms(day, 14, 50))) {
                    assertEquals(MarketBrain.causes(ctx, u, t), MarketBrain.causes(tagged, u, t))
                    assertEquals(MarketBrain.decide(ctx, p, u, t), MarketBrain.decide(tagged, p, u, t))
                    for (fs in listOf(emptyList(), listOf(finding)))
                        assertEquals(EntryBrake.decide(ctx, p, fs, Findings.Policy(), "liquidity", u, side, t),
                            EntryBrake.decide(tagged, p, fs, Findings.Policy(), "liquidity", u, side, t))
                    checked++
                }
            // The regime reads (what the arms and the trade manager use) are the same too.
            for (u in listOf("NIFTY", "BANKNIFTY", null)) {
                assertEquals(ctx.busy(u), tagged.busy(u)); assertEquals(ctx.quiet(u), tagged.quiet(u)); assertEquals(ctx.regimeOf(u), tagged.regimeOf(u))
            }
            assertEquals(ctx.activeWindows(ms(day, 10, 0)), tagged.activeWindows(ms(day, 10, 0)))
        }
        assertTrue(checked > 300)
        // Only the Brain section's words show it, before the pause line.
        val lines = MarketBrain.lines(news.copy(weekly = zone), ms(day, 10, 0), MarketBrain.Policy()) { "10:00" }
        assertTrue(lines[lines.size - 2].startsWith("Weekly context: NIFTY in the deep weekly sell-off zone"), lines.toString())
        assertTrue(lines.last().startsWith("Pause new entries during:"))
        assertEquals(lines.size - 1, MarketBrain.lines(news, ms(day, 10, 0), MarketBrain.Policy()) { "10:00" }.size)
    }

    private fun source(path: String): File? =
        listOf("../$path", "android/$path").map(::File).firstOrNull { it.exists() }

    @Test fun nothingThatSizesGatesOrEntersReadsIt() {
        // Who may name it: the rule itself, the brain that carries it, the lessons that say it, the worker that sets it and
        // Jarvis's hub that answers it. Nothing else - no arm, guard, sizing, gate, order path, lab or autopilot.
        val allowed = setOf("MajorityRule.kt", "MarketBrain.kt", "AgentLessons.kt", "SmartWorkers.kt", "IraHub.kt")
        val core = source("ira-core/src/main/kotlin") ?: return
        val roots = listOfNotNull(core, source("app/src/main/java"), source("engine/src/main/kotlin"))
        val names = Regex("\\bMajorityRule\\b|\\bweeklyTags\\b")
        // The brain's field read as a property (not a function call like LiquidityInsight.weekly(...), not a settings key string).
        val field = Regex("[A-Za-z_)\\]]\\??\\.weekly\\b(?!\\s*\\()(?![\"\\w.])")
        val wrong = ArrayList<String>()
        for (r in roots) r.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name !in allowed }.forEach { f ->
            val t = f.readText()
            if (names.containsMatchIn(t) || field.containsMatchIn(t)) wrong += f.path
        }
        assertTrue(wrong.isEmpty(), "read outside the context-only places: $wrong")
        // The hub only answers and keeps the reading; the worker only sets the brain's context.
        source("app/src/main/java/com/optionslab/app/data/SmartWorkers.kt")?.readText()?.let { sw ->
            assertTrue("_brain.update { it.copy(weekly = r) }" in sw)
            // Outside its one setter (and the comments) the worker never touches it: no entry, pause or door reads it.
            val code = sw.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/**") }
            assertEquals(listOf("    fun weekly(r: com.optionslab.ira.MajorityRule.Reading?) {", "        runCatching { _brain.update { it.copy(weekly = r) } }"),
                code.filter { Regex("\\bweekly\\b").containsMatchIn(it) })
        }
        source("app/src/main/java/com/optionslab/app/ira/IraHub.kt")?.readText()?.let { hub ->
            // The hub reads the reading only to say it and to set the brain's context.
            assertFalse(Regex("MajorityRule\\.[a-zA-Z]+\\([^)]*\\)[^\\n]*(order|place|lots|size|risk|arm)", RegexOption.IGNORE_CASE).containsMatchIn(hub))
        }
    }
}
