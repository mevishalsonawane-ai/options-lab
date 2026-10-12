package com.optionslab.ira

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Jarvis's knowledge base ([Knowledge], ARCHITECTURE L5 Level 4): the shipped pack's schema, the retrieval's quality on what
 * Boss asks (each question with the facts it must find), that no answer says a number its facts do not hold, that a locked
 * phone never gets Boss's own figures, and which questions are the research's.
 */
class KnowledgeTest {
    private val packFile: File = listOf("../app/src/main/assets/knowledge/pack.json.gz", "android/app/src/main/assets/knowledge/pack.json.gz")
        .map(::File).first { it.isFile }
    private val repo: File = packFile.canonicalFile.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile

    private companion object {
        @Volatile var shared: Knowledge.Index? = null
    }
    private val index: Knowledge.Index
        get() = shared ?: synchronized(KnowledgeTest::class) { shared ?: Knowledge.Index(packFile.inputStream().use { Knowledge.read(it) }).also { shared = it } }

    // ---- the pack ----------------------------------------------------------------------------------------------------------

    @Test fun thePackIsSmallVersionedAndWellFormed() {
        assertTrue(packFile.length() in 50_000L..4_000_000L, "${packFile.length()} bytes")
        val p = index.pack
        assertEquals(Knowledge.SCHEMA, p.schema)
        assertTrue(p.version.matches(Regex("[0-9a-f]{12}")), p.version)
        assertTrue(p.builtFrom.matches(Regex("20\\d\\d-\\d\\d-\\d\\d")), p.builtFrom)
        assertTrue(p.facts.size in 2_000..40_000, "${p.facts.size}")
        assertEquals(p.facts.size, p.facts.map { it.id }.distinct().size, "ids unique")
        val kinds = p.facts.groupingBy { it.kind }.eachCount()
        for (k in listOf("finding", "strategy", "study", "doc", "answer")) assertTrue((kinds[k] ?: 0) > 0, "no $k facts: $kinds")
        assertTrue((kinds["finding"] ?: 0) >= 100, "every FINDINGS.md bullet: $kinds")
        assertEquals(1296, kinds["answer"], "the h42 answers")
        val known = setOf("BANKNIFTY", "NIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "BANKEX", "INDIAVIX", "GOLD", "SILVER", "CRUDE", "NATURALGAS",
            "CRYPTO", "F&O stocks", "NIFTYNXT50")
        val sources = p.facts.map { it.src }.toSet()
        for (s in sources) assertTrue(File(repo, s).isFile, "source $s is not in the repo")
        for (f in p.facts) {
            assertTrue(f.id.matches(Regex("[a-z0-9][a-z0-9.\\-]*")), f.id)
            assertTrue(f.text.length in 20..1_200, "${f.id}: ${f.text.length} chars")
            assertTrue(f.verdict in setOf("positive", "weak", "negative", "context"), "${f.id}: ${f.verdict}")
            assertTrue(f.conf in setOf("high", "medium", "low"), "${f.id}: ${f.conf}")
            assertTrue(f.date.isEmpty() || f.date.matches(Regex("20\\d\\d-\\d\\d-\\d\\d")), "${f.id}: ${f.date}")
            assertTrue(f.sec.isNotBlank() && f.src.isNotBlank(), f.id)
            assertTrue(f.inst.all { it in known }, "${f.id}: ${f.inst}")
            assertFalse(f.text.contains("eyJ0" + "eXAi") || f.text.contains("11140" + "10050"), "${f.id}: a secret in the pack")
        }
        assertTrue(p.facts.count { it.date.isNotEmpty() } > p.facts.size * 0.98)
    }

    @Test fun everyStrategyCardSaysWhatItDoesItsVerdictPeriodAndCosts() {
        val cards = index.pack.facts.filter { it.kind == "strategy" }
        val whats = cards.filter { it.id.endsWith(".what") }
        assertTrue(whats.size >= 12, "${whats.size} strategy cards")
        for (w in whats) assertTrue(w.text.contains("Verdict:"), w.id)
        for (f in cards - whats.toSet()) assertTrue(f.text.contains("(Period:") && f.text.contains("Costs:"), f.id)
        for (id in listOf("liquidity-15-5", "orb", "orb-fresh", "orb-sweep", "range-fade", "expiry-hero", "night-r3", "short-straddle"))
            assertTrue(whats.any { it.id == "strategy.$id.what" }, id)
    }

    @Test fun parseRejectsAnotherSchemaAndSkipsBrokenFacts() {
        val bad = runCatching { Knowledge.parse("""{"schema":2,"version":"x","facts":[]}""") }
        assertTrue(bad.isFailure)
        val p = Knowledge.parse("""{"schema":1,"version":"v","built_from":"2026-10-12","facts":[{"id":"a","text":"ORB lost money"},{"text":"no id"},7]}""")
        assertEquals(listOf("a"), p.facts.map { it.id })
        assertEquals("context", p.facts[0].verdict)
        assertEquals("medium", p.facts[0].conf)
    }

    // ---- retrieval quality -------------------------------------------------------------------------------------------------

    /** What Boss asks, with the facts (any one of them) that must be among the top five. */
    private val EXPECT: List<Pair<String, List<String>>> = listOf(
        "how does banknifty behave on expiry after a gap down" to listOf("h42.q0076", "h42.q0074", "h42.q0077", "h42.q0078"),
        "how does banknifty behave after a gap down" to listOf("h42.q0076", "h42.q0074", "h42.q0077"),
        "how does nifty behave after a gap up" to listOf("h42.q0051", "hunt-h42.the-30-most-useful-answe.4"),
        "how often does the gap fill on banknifty according to research" to listOf("h42.q0067", "h42.q0076", "h42.q0070", "h42.q0073"),
        "what did research say about ORB" to listOf("strategy.orb.what", "strategy.orb.3", "study.orb.1", "strategy.orb.1"),
        "what did research find about ORB re-entries" to listOf("strategy.orb.2", "findings.orb-re-entry-churn-r14-1.2"),
        "is liquidity profitable" to listOf("strategy.liquidity-15-5.what", "strategy.liquidity-15-5.1", "strategy.liquidity-15-5.3"),
        "research kya kehta hai liquidity ke baare me" to listOf("strategy.liquidity-15-5.what", "study.liquidity.1"),
        "what is the edge of liquidity 15+5" to listOf("strategy.liquidity-15-5.what", "hunt-final.verdict.2"),
        "is selling straddles profitable" to listOf("strategy.short-straddle.what", "strategy.short-straddle.1"),
        "is buying straddles profitable" to listOf("strad-index.verdict.1", "findings.non-directional-buying.3", "strategy.long-straddle.1"),
        "what did the research find about vix" to listOf("study.vix.1", "study.vix.2", "hunt-h42.the-30-most-useful-answe.22"),
        "does the supertrend strategy make money" to listOf("strategy.supertrend-ema.what", "strategy.supertrend-ema.1"),
        "what did the study find about big candles" to listOf("strategy.big-candle-pullback.what", "strategy.big-candle-pullback.1"),
        "how does the market behave on expiry day" to listOf("hunt-h42.the-30-most-useful-answe.53", "hunt-h42.verdict-read-this-first.7"),
        "does machine learning work for option buying" to listOf("findings.learned-model-ml-long-py.3", "hunt-h1.what-the-models-learned.4"),
        "what did research say about majority rule" to listOf("strategy.majority-rule-14.what", "strategy.majority-rule-14.1"),
        "what does the research say about gann and fibonacci" to listOf("hunt-h37.honest-count-of-tries.5", "hunt-r4-gann-fib-magic.honesty-notes.8"),
        "is expiry hero profitable" to listOf("strategy.expiry-hero.what", "strategy.expiry-hero.1"),
        "does the night strategy work" to listOf("strategy.night-r3.what", "strategy.night-r3.1"),
        "what did the research say about overnight trades" to listOf("strategy.night-r3.what", "study.night.2", "hunt-h31.6-bottom-line.2"),
        "what did the research say about FII flows" to listOf("hunt-h40.verdict.2", "hunt-final.verdict.7"),
        "does VWAP work as a filter" to listOf("hunt-r10-orderflow-tools.2-test-results-history.26", "findings.green-candles-green-cand.13"),
        "research me mcx ke baare me kya hai" to listOf("strategy.mcx-bots.what", "strategy.mcx-bots.1"),
        "is gold liquidity profitable" to listOf("findings.liquidity-15-5-on-xauusd.1", "findings.xauusd-1-hour.1", "liquidity-gold.liquidity-15-5-on-xauusd.6"),
        "what did research say about candlestick patterns" to listOf("hunt-h25.summary-by-family-pre-ho.2"),
        "does order flow work" to listOf("hunt-r8-order-flow-traps.4-limits-honestly.5", "hunt-r7-order-flow.the-answer-in-plain-engl.5"),
        "what does research say about costs and spread" to listOf("hunt-h30.verdict.11", "hunt-h42.verdict-read-this-first.12"),
        "is orb fresh profitable" to listOf("strategy.orb-fresh.what", "strategy.orb-fresh.1"),
        "is range fade profitable" to listOf("strategy.range-fade.what", "strategy.range-fade.1"),
        "does orb sweep work" to listOf("strategy.orb-sweep.what", "strategy.orb-sweep.1"),
        "is the vix divergence strategy profitable" to listOf("strategy.vix-divergence.what", "strategy.vix-divergence.1"),
        "what did the research say about the trade manager" to listOf("study.trade-manager.3", "hunt-r12-trade-manager.the-answer-in-plain-engl.12"),
        "what did research find about pyramiding liquidity" to listOf("hunt-h7.verdict-plain-language.1", "hunt-h7.verdict-plain-language.2", "hunt-h7.caveats.3"),
        "how does banknifty behave in the first 30 minutes" to listOf("findings.green-candles-green-cand.4"),
        "does the profit lock help orb according to research" to listOf("findings.profit-lock-ladders.1", "study.orb.3"),
    )

    @Test fun eachQuestionFindsItsFacts() {
        assertTrue(EXPECT.size >= 30)
        val ids = index.pack.facts.map { it.id }.toSet()
        val missingIds = EXPECT.flatMap { it.second }.filter { it !in ids }
        assertTrue(missingIds.isEmpty(), "expected ids not in the pack (rebuilt with other ids?): $missingIds")
        val wrong = EXPECT.mapNotNull { (q, want) ->
            val got = Knowledge.search(index, q, 5).map { it.fact.id }
            if (got.any { it in want }) null else "\"$q\": wanted one of $want, got $got"
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} of ${EXPECT.size} missed:\n" + wrong.joinToString("\n"))
    }

    @Test fun theNamedIndexComesFirstAndOneDocDoesNotCrowdTheRest() {
        for (q in listOf("how does banknifty behave after a gap down", "how does nifty behave after a gap up")) {
            val want = if (q.contains("banknifty")) "BANKNIFTY" else "NIFTY"
            val top = Knowledge.search(index, q, 3)
            assertTrue(top.isNotEmpty() && top.all { want in it.fact.inst }, "$q: ${top.map { it.fact.id to it.fact.inst }}")
        }
        for ((q, _) in EXPECT) {
            val hits = Knowledge.search(index, q, 5)
            assertTrue(hits.groupingBy { it.fact.src }.eachCount().values.all { it <= 2 }, q)
            assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score }, q)
            assertTrue(hits.all { it.score >= Knowledge.MIN_SCORE }, q)
        }
        assertTrue(Knowledge.search(index, "zxqv plorb", 5).isEmpty())
        assertTrue(Knowledge.search(index, "the and of", 5).isEmpty())
    }

    @Test fun instrumentsAndSynonymsAreRead() {
        assertEquals(setOf("BANKNIFTY"), Knowledge.instruments("how does bank nifty behave"))
        assertEquals(setOf("BANKNIFTY"), Knowledge.instruments("BN on expiry"))
        assertEquals(setOf("NIFTY"), Knowledge.instruments("nifty after a gap up"))
        assertEquals(setOf("FINNIFTY", "INDIAVIX"), Knowledge.instruments("fin nifty and india vix"))
        assertEquals(setOf("GOLD"), Knowledge.instruments("XAUUSD liquidity"))
        assertEquals(Knowledge.tokens("gap-down days"), Knowledge.tokens("gap down day"))
        assertEquals(Knowledge.tokens("Bank Nifty"), Knowledge.tokens("BANKNIFTY"))
        // Hinglish: "munafa" (profit) brings the profit words in.
        val hi = Knowledge.search(index, "kya liquidity munafa deta hai", 5).map { it.fact.id }
        assertTrue(hi.any { it.startsWith("strategy.liquidity-15-5") || it.startsWith("study.liquidity") }, "$hi")
    }

    // ---- no number that is not a fact's ----------------------------------------------------------------------------------

    private val NUM = Regex("\\d[\\d,]*(\\.\\d+)?")
    private fun nums(s: String) = NUM.findAll(s).map { it.value.replace(",", "") }.toSet()

    @Test fun noAnswerSaysANumberItsFactsDoNotHold() {
        val questions = EXPECT.map { it.first } + listOf("is option buying profitable", "how often does nifty fill a gap down", "does the trade manager help")
        for (q in questions) for (locked in listOf(false, true)) {
            val hits = Knowledge.search(index, q, 3, locked)
            val said = Knowledge.say(hits)
            val facts = Knowledge.lines(hits)
            val extra = nums(said) - facts.flatMap { nums(it) }.toSet()
            assertTrue(extra.isEmpty(), "\"$q\": numbers $extra are in no fact")
            // The app's own check, the one the model's rewrite must pass, passes the draft itself.
            assertTrue(Ira().numbersBacked(said, facts), q)
            // Every fact said is a retrieved one, whole.
            for (h in hits.take(3)) assertTrue(said.contains(Knowledge.line(h.fact)), q)
        }
        assertTrue(nums(Knowledge.NOTHING).isEmpty() && nums(Knowledge.NOT_LOADED).isEmpty())
        assertEquals(Knowledge.NOTHING, Knowledge.say(emptyList()))
    }

    @Test fun theModelsNotesAreFactsWithinTheCapAndOnlyForResearchQuestions() {
        val q = "what did research say about ORB"
        val notes = Knowledge.forModel(index, q)
        assertTrue(notes.isNotEmpty())
        assertTrue(notes.sumOf { it.length + 2 } <= AgentLessons.MODEL_CHARS)
        val all = index.pack.facts.filter { !it.personal }.map { Knowledge.line(it) }.toSet()
        assertTrue(notes.all { it in all })
        assertTrue(Writer.capLessons(notes) == notes)
        assertTrue(Knowledge.forModel(index, "nifty kaha hai").isEmpty())
        assertTrue(Knowledge.forModel(null, q).isEmpty())
        // A model answer with a number from the notes passes the check; one with a made-up number does not.
        val n = notes.flatMap { nums(it) }.first { it.length >= 2 }
        assertNotNull(Writer.check("The research found $n in that test.", notes, "From the research."))
        assertEquals(null, Writer.check("The research found 98765 in that test.", notes, "From the research."))
    }

    // ---- a locked phone ----------------------------------------------------------------------------------------------------

    @Test fun aLockedPhoneNeverGetsBossOwnFigures() {
        val personal = index.pack.facts.filter { it.personal }
        assertTrue(personal.size >= 20, "the pack marks the studies of Boss's own days: ${personal.size}")
        assertTrue(personal.any { it.src.endsWith("HUNT_H22.md") })
        val asks = listOf("which strategy made boss's 5k days according to research", "what did research find about the 1 oct paper day",
            "research on the 8 oct logs", "what did the study say about boss's fills", "research h22 liquidity first day rules") + EXPECT.map { it.first }
        for (q in asks) {
            assertTrue(Knowledge.search(index, q, 10, locked = true).none { it.fact.personal }, q)
            assertTrue(Knowledge.forModel(index, q).none { line -> personal.any { Knowledge.line(it) == line } }, q)
        }
        // Unlocked, the same studies are found.
        assertTrue(asks.take(5).any { q -> Knowledge.search(index, q, 10).any { it.fact.personal } })
    }

    // ---- which questions are the research's --------------------------------------------------------------------------------

    @Test fun researchQuestionsAreAskedAndAccountOrTodayAreNot() {
        val yes = listOf("how does banknifty behave on expiry after a gap down", "what did research say about ORB", "is liquidity profitable",
            "research kya kehta hai liquidity ke baare me", "what did the study find about big candles", "does the night strategy work",
            "is selling straddles profitable", "is there an edge in gap fills", "what is the edge of liquidity 15+5", "what did the backtest say about orb",
            "historically how does nifty move after a big gap", "what happens on expiry day to banknifty", "does orb make money",
            "liquidity munafa deta hai kya", "what evidence is there for the supertrend strategy", "research me mcx ke baare me kya hai",
            "how does sensex react after a gap up", "what did the research say about FII flows", "does the trade manager help")
        val no = listOf("what is my p&l", "is my liquidity trade profitable", "is liquidity profitable today", "how is nifty behaving today",
            "nifty kaha hai", "buy banknifty 55000 ce", "stop orb", "what did i make on orb", "how does banknifty look right now",
            "mera orb profitable hai kya", "what happens if nifty falls 1%", "study page vwap", "hello jarvis", "what is a gap",
            "what did you study last night", "fii data kya hai", "what did you find about orb today")
        for (q in yes) assertTrue(Knowledge.asked(q), "should be the research's: $q")
        for (q in no) assertFalse(Knowledge.asked(q), "should not be the research's: $q")
    }
}
