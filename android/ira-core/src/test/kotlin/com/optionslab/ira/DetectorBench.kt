package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test

/**
 * A rough timing of every question reader the coverage audit routes through (CoverageTest's route() and feature()), over
 * the audit's own phrases (speed round 10). Never asserted on: with IRA_BENCH=1 it times 30 passes and prints the whole
 * chain's cost per phrase and each reader's, slowest first, to stderr (and to the file IRA_BENCH_OUT names); with
 * IRA_BENCH=prof it also samples where the time goes (and where patterns are compiled at a reading). Without either,
 * each reader runs once over the phrases: a smoke check that none throws.
 *
 *     IRA_BENCH=1 ./gradlew :ira-core:cleanTest :ira-core:test --tests com.optionslab.ira.DetectorBench
 */
class DetectorBench {
    private val today: LocalDate = LocalDate.of(2026, 10, 5)

    @Suppress("UNCHECKED_CAST")
    private fun phrases(): List<String> {
        val c = CoverageTest()
        fun list(name: String): List<Any?> =
            CoverageTest::class.java.getDeclaredField(name).let { it.isAccessible = true; it.get(c) as List<Any?> }
        val asked = list("ASKED").map { (it as Pair<String, *>).first }
        val routed = list("ROUTED").map { (it as Pair<String, *>).first }
        val actions = list("ACTIONS").map { it as String }
        return (asked + routed + actions).distinct()
    }

    private val readers: List<Pair<String, (String) -> Any?>> = listOf(
        "AboutBoss.fact" to { q -> AboutBoss.fact(q) },
        "AboutBoss.forgetAsked" to { q -> AboutBoss.forgetAsked(q) },
        "AboutBoss.knowAsked" to { q -> AboutBoss.knowAsked(q) },
        "Agenda.asked" to { q -> Agenda.asked(q) },
        "Airtime.asked" to { q -> Airtime.asked(q) },
        "AlertSense.asked" to { q -> AlertSense.asked(q) },
        "AppAnswers.sections" to { q -> AppAnswers.sections(q) },
        "ArmHabits.asked" to { q -> ArmHabits.asked(q) },
        "Ask.parse" to { q -> Ask.parse(q) },
        "AskedAgain.asked" to { q -> AskedAgain.asked(q) },
        "AutoStop.read" to { q -> AutoStop.read(q) },
        "BigPicture.asked" to { q -> BigPicture.asked(q) },
        "BotTrades.asked" to { q -> BotTrades.asked(q) },
        "Breadth.asked" to { q -> Breadth.asked(q) },
        "Briefing.asked" to { q -> Briefing.asked(q) },
        "Bundle.acts" to { q -> Bundle.acts(q) },
        "Causes.asked" to { q -> Causes.asked(q) },
        "ChainDrift.asked" to { q -> ChainDrift.asked(q) },
        "ChainIntel.asked" to { q -> ChainIntel.asked(q) },
        "Chat.personal" to { q -> Chat.personal(q) },
        "Chat.smallTalk" to { q -> Chat.smallTalk(q, 0) },
        "Clarity.asked" to { q -> Clarity.asked(q) },
        "CoPilot.asked" to { q -> CoPilot.asked(q) },
        "Compare.asked" to { q -> Compare.asked(q) },
        "Consistency.asked" to { q -> Consistency.asked(q) },
        "Corrections.forgetWordAsked" to { q -> Corrections.forgetWordAsked(q) },
        "Corrections.wordsAsked" to { q -> Corrections.wordsAsked(q) },
        "DataAge.asked" to { q -> DataAge.asked(q) },
        "DayClock.asked" to { q -> DayClock.asked(q) },
        "DayCompare.asked" to { q -> DayCompare.asked(q) },
        "DayJournal.asked" to { q -> DayJournal.asked(q) },
        "DayStory.asked" to { q -> DayStory.asked(q) },
        "DaySummary.asked" to { q -> DaySummary.asked(q) },
        "Distance.asked" to { q -> Distance.asked(q) },
        "ExpectedRange.asked" to { q -> ExpectedRange.asked(q) },
        "ExpiryDay.asked" to { q -> ExpiryDay.asked(q) },
        "FigureFirst.asked" to { q -> FigureFirst.asked(q) },
        "FirstMove.asked" to { q -> FirstMove.asked(q) },
        "Gap.asked" to { q -> Gap.asked(q) },
        "GapRecord.asked" to { q -> GapRecord.asked(q) },
        "Glossary.explain" to { q -> Glossary.explain(q) },
        "Goals.asked" to { q -> Goals.asked(q) },
        "Goals.clearAsked" to { q -> Goals.clearAsked(q) },
        "Habits.asked" to { q -> Habits.asked(q) },
        "Headroom.asked" to { q -> Headroom.asked(q) },
        "Hearing.asked" to { q -> Hearing.asked(q) },
        "Honest.asked" to { q -> Honest.asked(q) },
        "HonestStars.asked" to { q -> HonestStars.asked(q) },
        "Improve.asked" to { q -> Improve.asked(q) },
        "InsideDays.asked" to { q -> InsideDays.asked(q) },
        "Intents.quick" to { q -> Intents.quick(q) },
        "LastHour.asked" to { q -> LastHour.asked(q) },
        "Latency.asked" to { q -> Latency.asked(q) },
        "Learnings.asked" to { q -> Learnings.asked(q) },
        "Learnings.undoAsked" to { q -> Learnings.undoAsked(q) },
        "Lessons.asked" to { q -> Lessons.asked(q) },
        "LevelInfo.asked" to { q -> LevelInfo.asked(q) },
        "Lookback.prevAsked" to { q -> Lookback.prevAsked(q) },
        "Lookback.time" to { q -> Lookback.time(q) },
        "MarketDays.asked" to { q -> MarketDays.asked(q, today) },
        "MarketDays.expiryAsked" to { q -> MarketDays.expiryAsked(q) },
        "MarketMemory.asked" to { q -> MarketMemory.asked(q) },
        "MarketStory.asked" to { q -> MarketStory.asked(q) },
        "Memory.forgetAsked" to { q -> Memory.forgetAsked(q) },
        "Memory.recallAsked" to { q -> Memory.recallAsked(q) },
        "Memory.toKeep" to { q -> Memory.toKeep(q) },
        "MindChange.asked" to { q -> MindChange.asked(q) },
        "Momentum.asked" to { q -> Momentum.asked(q) },
        "MorningSense.asked" to { q -> MorningSense.asked(q) },
        "Moves.asked" to { q -> Moves.asked(q) },
        "NeedsTrue.asked" to { q -> NeedsTrue.asked(q) },
        "NewsDesk.asked" to { q -> NewsDesk.asked(q) },
        "NewsMoves.asked" to { q -> NewsMoves.asked(q) },
        "Odds.asked" to { q -> Odds.asked(q) },
        "OpeningRange.asked" to { q -> OpeningRange.asked(q) },
        "OptionFacts.asked" to { q -> OptionFacts.asked(q) },
        "OptionQuote.asked" to { q -> OptionQuote.asked(q) },
        "OrderWhy.asked" to { q -> OrderWhy.asked(q) },
        "Outlook.asked" to { q -> Outlook.asked(q) },
        "OutsideApp.asked" to { q -> OutsideApp.asked(q) },
        "PatternCalls.asked" to { q -> PatternCalls.asked(q) },
        "Payoff.asked" to { q -> Payoff.asked(q) },
        "PeriodMove.asked" to { q -> PeriodMove.asked(q) },
        "Pivots.asked" to { q -> Pivots.asked(q) },
        "PreMarket.asked" to { q -> PreMarket.asked(q) },
        "PriorDay.asked" to { q -> PriorDay.asked(q) },
        "RangeBreaks.asked" to { q -> RangeBreaks.asked(q) },
        "Realised.asked" to { q -> Realised.asked(q) },
        "RelayHealth.asked" to { q -> RelayHealth.asked(q) },
        "Reminder.asked" to { q -> Reminder.asked(q) },
        "Reminder.cancelAsked" to { q -> Reminder.cancelAsked(q) },
        "Reminder.heardAsked" to { q -> Reminder.heardAsked(q) },
        "Reminder.missedAsked" to { q -> Reminder.missedAsked(q) },
        "Reminder.modelAsked" to { q -> Reminder.modelAsked(q) },
        "Reminder.tomorrow" to { q -> Reminder.tomorrow(q) },
        "Reminder.usageAsked" to { q -> Reminder.usageAsked(q) },
        "Routine.asked" to { q -> Routine.asked(q) },
        "Routine.forgetAsked" to { q -> Routine.forgetAsked(q) },
        "SaidAbout.asked" to { q -> SaidAbout.asked(q) },
        "Scenarios.asked" to { q -> Scenarios.asked(q) },
        "SelfCalibration.asked" to { q -> SelfCalibration.asked(q) },
        "SelfCheck.asked" to { q -> SelfCheck.asked(q) },
        "SelfWhy.asked" to { q -> SelfWhy.asked(q) },
        "SharpMove.asked" to { q -> SharpMove.asked(q) },
        "SinceLast.asked" to { q -> SinceLast.asked(q) },
        "SinceMorning.asked" to { q -> SinceMorning.asked(q) },
        "Sizing.asked" to { q -> Sizing.asked(q) },
        "Sources.asked" to { q -> Sources.asked(q) },
        "Streak.asked" to { q -> Streak.asked(q) },
        "StreamHealth.asked" to { q -> StreamHealth.asked(q) },
        "Structure.asked" to { q -> Structure.asked(q) },
        "Suggest.closest" to { q -> Suggest.closest(q) },
        "SwitchOff.asked" to { q -> SwitchOff.asked(q) },
        "TaxRecords.exportAsked" to { q -> TaxRecords.exportAsked(q) },
        "Thinking.asked" to { q -> Thinking.asked(q) },
        "Together.asked" to { q -> Together.asked(q) },
        "Tour.asked" to { q -> Tour.asked(q) },
        "TradeCase.asked" to { q -> TradeCase.asked(q) },
        "TrendReads.asked" to { q -> TrendReads.asked(q) },
        "Understand.questions" to { q -> Understand.questions(null, q) },
        "Vetting.asked" to { q -> Vetting.asked(q) },
        "VixRank.asked" to { q -> VixRank.asked(q) },
        "WeekAhead.asked" to { q -> WeekAhead.asked(q) },
        "Weekdays.asked" to { q -> Weekdays.asked(q) },
        "WordFit.asked" to { q -> WordFit.asked(q) },
        "WrongThing.asked" to { q -> WrongThing.asked(q) },
        "WrongThing.objected" to { q -> WrongThing.objected(q) },
        "ZerodhaSession.asked" to { q -> ZerodhaSession.asked(q) },
    )

    @Test fun timeEveryReader() {
        val qs = phrases()
        val on = System.getenv("IRA_BENCH") != null
        val rounds = if (on) 30 else 1
        if (on) repeat(3) { for ((_, f) in readers) for (q in qs) f(q) }
        val c = CoverageTest()
        if (on) repeat(3) { for (q in qs) c.feature(q) }
        val main = Thread.currentThread()
        val inclusive = HashMap<String, Int>()
        val top = HashMap<String, Int>()
        val compiled = HashMap<String, Int>()
        val sampling = java.util.concurrent.atomic.AtomicBoolean(System.getenv("IRA_BENCH") == "prof")
        val sampler = Thread {
            while (sampling.get()) {
                val st = main.stackTrace
                st.firstOrNull { it.className.startsWith("com.optionslab") }?.let { top.merge("${it.className}.${it.methodName}:${it.lineNumber}", 1, Int::plus) }
                if (st.any { it.className == "java.util.regex.Pattern" && it.methodName == "compile" || it.className == "java.util.regex.Pattern" && it.methodName == "<init>" })
                    st.firstOrNull { it.className.startsWith("com.optionslab") }?.let { compiled.merge("${it.className}.${it.methodName}:${it.lineNumber}", 1, Int::plus) }
                st.filter { it.className.startsWith("com.optionslab") }.map { "${it.className}.${it.methodName}" }.toSet()
                    .forEach { inclusive.merge(it, 1, Int::plus) }
                Thread.sleep(1)
            }
        }.apply { isDaemon = true; if (sampling.get()) start() }
        // The hub's whole chain per question: the best of the rounds (the least disturbed by the machine).
        var featureNs = Long.MAX_VALUE
        repeat(rounds) {
            val t0 = System.nanoTime()
            for (q in qs) c.feature(q)
            featureNs = minOf(featureNs, (System.nanoTime() - t0) / qs.size)
        }
        // Each reader timed on words the hub has just read (its kept readings warm, as on a spoken question).
        val acc = LongArray(readers.size)
        repeat(rounds) {
            for (q in qs) {
                c.feature(q)
                readers.forEachIndexed { i, (_, f) -> val t = System.nanoTime(); f(q); acc[i] += System.nanoTime() - t }
            }
        }
        val times = readers.mapIndexed { i, (n, _) -> n to acc[i] / rounds / qs.size }.sortedByDescending { it.second }
        sampling.set(false)
        if (sampler.isAlive) sampler.join()
        if (inclusive.isNotEmpty()) System.err.println(buildString {
            appendLine("PROF inclusive:")
            inclusive.entries.sortedByDescending { it.value }.take(120).forEach { appendLine("PROF ${it.value} ${it.key}") }
            appendLine("PROF compiling:")
            compiled.entries.sortedByDescending { it.value }.take(60).forEach { appendLine("PROF ${it.value} ${it.key}") }
            appendLine("PROF top:")
            top.entries.sortedByDescending { it.value }.take(100).forEach { appendLine("PROF ${it.value} ${it.key}") }
        }.also { s -> System.getenv("IRA_BENCH_OUT")?.let { java.io.File("$it.prof").writeText(s) } })
        if (!on) return
        val out = buildString {
            val rxKept = Rx::class.java.getDeclaredField("kept").let { it.isAccessible = true; (it.get(Rx) as Map<*, *>).size }
            appendLine("BENCH patterns kept by rx(): $rxKept of ${Rx.KEPT}")
            appendLine("BENCH phrases=${qs.size} rounds=$rounds feature()/phrase=$featureNs ns; readers summed/phrase=${times.sumOf { it.second }} ns")
            for ((n, t) in times) appendLine(String.format("BENCH %8d ns  %s", t, n))
        }
        System.err.println(out)
        System.getenv("IRA_BENCH_OUT")?.let { java.io.File(it).writeText(out) }
    }
}
