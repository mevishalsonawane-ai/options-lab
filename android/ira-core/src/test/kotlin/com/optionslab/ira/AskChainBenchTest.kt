package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What one question costs the hub's chain of question readers (IraHub.ask: each reader asked in turn until one claims
 * it), timed over 200 questions Boss says. A measure, not a gate: it prints the time per question and the costliest
 * readers, and only checks the corpus. Run with `-Dira.bench=1` (passed through by the build) for steadier numbers.
 */
class AskChainBenchTest {
    private val today = LocalDate.of(2026, 1, 5)

    /** The readers in the order IraHub.ask goes through them (the ones that need only the words). */
    private val chain: List<Pair<String, (String) -> Any?>> = listOf(
        "Corrections.wordsAsked" to { q -> Corrections.wordsAsked(q) },
        "Corrections.forgetWordAsked" to { q -> Corrections.forgetWordAsked(q) },
        "Routine.asked" to { q -> Routine.asked(q) },
        "Routine.forgetAsked" to { q -> Routine.forgetAsked(q) },
        "Sources.asked" to { q -> Sources.asked(q) },
        "AboutBoss.knowAsked" to { q -> AboutBoss.knowAsked(q) },
        "Memory.recallAsked" to { q -> Memory.recallAsked(q) },
        "Memory.forgetAsked" to { q -> Memory.forgetAsked(q) },
        "PatternCalls.asked" to { q -> PatternCalls.asked(q) },
        "Learnings.asked" to { q -> Learnings.asked(q) },
        "Learnings.undoAsked" to { q -> Learnings.undoAsked(q) },
        "NewsMoves.asked" to { q -> NewsMoves.asked(q) },
        "PreMarket.asked" to { q -> PreMarket.asked(q) },
        "ChainDrift.asked" to { q -> ChainDrift.asked(q) },
        "Headroom.asked" to { q -> Headroom.asked(q) },
        "SaidAbout.asked" to { q -> SaidAbout.asked(q) },
        "NeedsTrue.asked" to { q -> NeedsTrue.asked(q) },
        "Clarity.asked" to { q -> Clarity.asked(q) },
        "DayClock.asked" to { q -> DayClock.asked(q) },
        "Understand.questions" to { q -> Understand.questions(null, q) },
        "Bundle.acts" to { q -> Bundle.acts(q) },
        "DayJournal.asked" to { q -> DayJournal.asked(q) },
        "Ask.parse" to { q -> Ask.parse(q) },
        "AlertSense.asked" to { q -> AlertSense.asked(q) },
        "Airtime.asked" to { q -> Airtime.asked(q) },
        "Hearing.asked" to { q -> Hearing.asked(q) },
        "PatternCalls.asked#2" to { q -> PatternCalls.asked(q) },
        "Clarity.asked#2" to { q -> Clarity.asked(q) },
        "NewsMoves.asked#2" to { q -> NewsMoves.asked(q) },
        "TaxRecords.exportAsked" to { q -> TaxRecords.exportAsked(q) },
        "Learnings.asked#2" to { q -> Learnings.asked(q) },
        "PreMarket.asked#2" to { q -> PreMarket.asked(q) },
        "Headroom.asked#2" to { q -> Headroom.asked(q) },
        "SaidAbout.asked#2" to { q -> SaidAbout.asked(q) },
        "DataAge.asked" to { q -> DataAge.asked(q) },
        "Honest.asked" to { q -> Honest.asked(q) },
        "Thinking.asked" to { q -> Thinking.asked(q) },
        "SelfWhy.asked" to { q -> SelfWhy.asked(q) },
        "Consistency.asked" to { q -> Consistency.asked(q) },
        "CoPilot.asked" to { q -> CoPilot.asked(q) },
        "ChainDrift.asked#2" to { q -> ChainDrift.asked(q) },
        "ChainIntel.asked" to { q -> ChainIntel.asked(q) },
        "DayClock.asked#2" to { q -> DayClock.asked(q) },
        "Structure.asked" to { q -> Structure.asked(q) },
        "Breadth.asked" to { q -> Breadth.asked(q) },
        "TradeCase.asked" to { q -> TradeCase.asked(q) },
        "Scenarios.asked" to { q -> Scenarios.asked(q) },
        "Agenda.asked" to { q -> Agenda.asked(q) },
        "Improve.asked" to { q -> Improve.asked(q) },
        "Later.mentionsTime" to { q -> Later.mentionsTime(q) },
        "Reminder.cancelAsked" to { q -> Reminder.cancelAsked(q) },
        "Reminder.asked" to { q -> Reminder.asked(q) },
        "SelfCheck.asked" to { q -> SelfCheck.asked(q) },
        "Reminder.missedAsked" to { q -> Reminder.missedAsked(q) },
        "Reminder.heardAsked" to { q -> Reminder.heardAsked(q) },
        "Latency.asked" to { q -> Latency.asked(q) },
        "Reminder.usageAsked" to { q -> Reminder.usageAsked(q) },
        "Reminder.modelAsked" to { q -> Reminder.modelAsked(q) },
        "Distance.asked" to { q -> Distance.asked(q) },
        "OptionFacts.asked" to { q -> OptionFacts.asked(q) },
        "Sizing.asked" to { q -> Sizing.asked(q) },
        "DaySummary.asked" to { q -> DaySummary.asked(q) },
        "MarketDays.expiryAsked" to { q -> MarketDays.expiryAsked(q) },
        "MarketDays.asked" to { q -> MarketDays.asked(q, today) },
        "Outlook.asked" to { q -> Outlook.asked(q) },
        "NewsDesk.asked" to { q -> NewsDesk.asked(q) },
        "Memory.toKeep" to { q -> Memory.toKeep(q) },
        "AboutBoss.forgetAsked" to { q -> AboutBoss.forgetAsked(q) },
        "AutoStop.read" to { q -> AutoStop.read(q) },
        "Goals.read" to { q -> Goals.read(q) },
        "Goals.asked" to { q -> Goals.asked(q) },
        "Goals.clearAsked" to { q -> Goals.clearAsked(q) },
        "SelfWhy.asked#2" to { q -> SelfWhy.asked(q) },
        "Vetting.asked" to { q -> Vetting.asked(q) },
        "SelfCalibration.asked" to { q -> SelfCalibration.asked(q) },
        "Lessons.asked" to { q -> Lessons.asked(q) },
        "Sources.asked#2" to { q -> Sources.asked(q) },
        "Habits.asked" to { q -> Habits.asked(q) },
        "Intents.quick" to { q -> Intents.quick(q) },
        "Glossary.explain" to { q -> Glossary.explain(q) },
        "OptionQuote.asked" to { q -> OptionQuote.asked(q) },
        "MarketStory.asked" to { q -> MarketStory.asked(q) },
        "SharpMove.asked" to { q -> SharpMove.asked(q) },
        "MarketMemory.asked" to { q -> MarketMemory.asked(q) },
        "ExpiryDay.asked" to { q -> ExpiryDay.asked(q) },
        "Payoff.asked" to { q -> Payoff.asked(q) },
        "VixRank.asked" to { q -> VixRank.asked(q) },
        "SinceLast.asked" to { q -> SinceLast.asked(q) },
        "Briefing.asked" to { q -> Briefing.asked(q) },
        "Realised.asked" to { q -> Realised.asked(q) },
        "Together.asked" to { q -> Together.asked(q) },
        "Compare.asked" to { q -> Compare.asked(q) },
        "Odds.asked" to { q -> Odds.asked(q) },
        "ExpectedRange.asked" to { q -> ExpectedRange.asked(q) },
        "Moves.asked" to { q -> Moves.asked(q) },
        "BigPicture.asked" to { q -> BigPicture.asked(q) },
        "LevelInfo.asked" to { q -> LevelInfo.asked(q) },
        "OpeningRange.asked" to { q -> OpeningRange.asked(q) },
        "PeriodMove.asked" to { q -> PeriodMove.asked(q) },
        "Momentum.asked" to { q -> Momentum.asked(q) },
        "Pivots.asked" to { q -> Pivots.asked(q) },
        "DayStory.asked" to { q -> DayStory.asked(q) },
        "Gap.asked" to { q -> Gap.asked(q) },
        "Streak.asked" to { q -> Streak.asked(q) },
    )

    /** 200 questions of every kind (English, Hinglish, spoken numbers, commands, the account, the news). */
    private val corpus: List<String> = run {
        val all = (ReadOnceMoreTest.SAID + listOf(
            "how is nifty", "what's banknifty doing", "any news on rbi", "how does the market react to rbi news",
            "which news moves the market most", "am i ready to trade", "pre-market checklist", "how well are you hearing me",
            "what have you learned", "undo that learning", "how are your calls doing", "is my bot healthy",
            "what's the pcr on nifty", "where is max pain", "is the trend up", "how is breadth today", "make the case for a long",
            "what's on today", "explain theta to me", "what is iv crush", "stop all strategies", "buy 2 lots nifty atm ce",
            "exit all", "kill switch on", "how much did i make today", "show my positions", "why did you say that",
            "how sure are you", "are you consistent", "what do you know about me", "help me journal today",
            "how has the option chain drifted", "where is max pain now", "how much room do i have left", "what did i say about expiry",
            "for my 24500 put to work what needs to happen", "where is my breakeven", "keep your answers short", "when does nifty usually make its high",
        )).distinct()
        List(200) { all[it % all.size] }
    }

    private fun pass(): LongArray {
        val t = LongArray(chain.size)
        for (q in corpus) chain.forEachIndexed { i, (_, f) ->
            val s = System.nanoTime(); runCatching { f(q) }; t[i] += System.nanoTime() - s
        }
        return t
    }

    @Test fun chainCost() {
        assertEquals(200, corpus.size)
        val long = System.getProperty("ira.bench") != null
        repeat(if (long) 30 else 3) { pass() }   // warms the JIT and the kept patterns
        val runs = if (long) 20 else 2
        val total = LongArray(chain.size)
        val passes = mutableListOf<Long>()
        repeat(runs) { pass().also { passes += it.sum() }.forEachIndexed { i, v -> total[i] += v } }
        val perQ = total.sum() / 1000.0 / runs / corpus.size
        val sorted = passes.sorted()
        println("AskChainBench: %.1f us per question over the chain, best pass %.1f, median pass %.1f (%d readers, %d questions, %d runs)".format(
            perQ, sorted.first() / 1000.0 / corpus.size, sorted[sorted.size / 2] / 1000.0 / corpus.size, chain.size, corpus.size, runs))
        val newer = listOf("ChainDrift", "Headroom", "SaidAbout", "NeedsTrue", "Clarity", "DayClock")
        val inNewer = chain.indices.filter { i -> newer.any { chain[i].first.startsWith("$it.") } }
        println("  the newer readers (%s, %d calls): %.1f us per question".format(newer.joinToString(), inNewer.size,
            inNewer.sumOf { total[it] } / 1000.0 / runs / corpus.size))
        chain.indices.sortedByDescending { total[it] }.take(20).forEach { i ->
            println("  %-28s %.2f us".format(chain[i].first, total[i] / 1000.0 / runs / corpus.size))
        }
    }
}

/** The readings kept by the words ([Kept]): the same answer as reading again, a null kept too, nothing kept that may not be. */
class KeptTest {
    @Test fun keepsReadingsByTheWords() {
        val k = Kept<String?>(2)
        var reads = 0
        assertEquals("A", k.of("a") { reads++; "A" })
        assertEquals("A", k.of("a") { reads++; "B" })
        assertEquals(null, k.of("n") { reads++; null })
        assertEquals(null, k.of("n") { reads++; "x" })
        assertEquals(2, reads)
        k.of("c") { reads++; "C" }                       // the least recently used ("a") goes
        assertEquals(2, k.size)
        assertEquals("A2", k.of("a") { reads++; "A2" })
        assertEquals("E", k.of("e", keep = { false }) { "E" })
        assertEquals("E2", k.of("e", keep = { false }) { "E2" })
        assertTrue(runCatching { k.of("t") { error("x") } }.isFailure)
        assertEquals("T", k.of("t") { "T" })             // a reader that threw kept nothing
    }

    @Test fun keptReadersReadAsFresh() {
        val said = ReadOnceMoreTest.SAID.distinct()
        fun read(q: String) = listOf(Commands.parse(q), Corrections.acts(q), FollowUp.acts(q), Bundle.acts(q), Hinglish.normalize(q),
            Hinglish.question(q), Spelling.fix(q), Heard.fix(q), Ask.parse(q)).joinToString(" | ")
        val first = said.map { read(it) }
        assertEquals(first, said.map { read(it) })
        // Words that read as themselves come back as the very same words.
        said.forEach { q -> if (Hinglish.normalize(q) == q) assertSame(q, Hinglish.normalize(q)) }
    }

    @Test fun anEventIsNeverKept() {
        val a = Commands.parse("add event RBI policy on 5 Dec")
        val b = Commands.parse("add event RBI policy on 5 Dec")
        assertEquals(Command.Kind.EVENT_ADD, a?.kind)
        assertNotSame(a, b)
    }
}
