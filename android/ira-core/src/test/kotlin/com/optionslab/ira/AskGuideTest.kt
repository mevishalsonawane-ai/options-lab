package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "What can I ask?" ([AskGuide]): every example is a real question - routed, through the hub's own order
 * ([CoverageTest.feature], the machinery the collision hunt uses), to the family it is tagged with - and none is an action
 * (nothing switched, bought, sold, closed or set), so the guide can never drift from what Jarvis answers.
 */
class AskGuideTest {
    private val audit = CoverageTest()

    @Test fun everyExampleRoutesToItsFamily() {
        val wrong = AskGuide.all().map { it to audit.feature(it.q) }.filter { (e, got) -> got != e.family }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n") { (e, got) -> "\"${e.q}\": tagged ${e.family}, routed $got" })
    }

    /** Words of an action: never in an example (the guide names questions only; "how close am I" is a distance, not a close). */
    private val ACTION = Regex("(?i)\\b(buy|buying|sell|selling|square|(?<!how )close|exit|stop|kill|switch|turn on|turn off|enable|disable|set|cancel|place|start|pause|resume|" +
        "activate|deactivate|arm|disarm|mute|unmute|forget|remind|delete|remove|chalu|chalao|band|hata|lagao|kharido|becho|karo|kar do)\\b")

    @Test fun noExampleActs() {
        for (e in AskGuide.all()) {
            val q = e.q
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, q)
            assertFalse(Bundle.acts(q), q); assertFalse(FollowUp.acts(q), q)
            assertNull(Commands.parse(q), q)
            assertFalse(Conditional.asked(q), q)
            assertTrue(Understand.questions(null, q).orEmpty().none { FollowUp.acts(it) || Ask.parse(it).command != null || Ask.parse(it).order != null }, q)
            assertFalse(ACTION.containsMatchIn(q), q)
            assertTrue(e.family != "Act" && e.family != "Conditional" && e.family != "Missed", q)
        }
    }

    @Test fun theGroupsAreWhole() {
        val groups = AskGuide.GROUPS
        assertEquals(listOf("liquidity", "solo", "hero", "strategies", "market", "news", "account", "coach", "plans", "app"), groups.map { it.id })
        assertEquals(groups.size, groups.map { it.title }.distinct().size)
        for (g in groups) {
            assertTrue(g.examples.size in 4..9, "${g.title}: ${g.examples.size}")
            assertTrue(g.title.isNotBlank() && g.blurb.isNotBlank() && !g.id.contains(','), g.id)
        }
        val all = AskGuide.all()
        assertEquals(all.size, all.map { it.q.lowercase() }.distinct().size, "no example twice")
        for (e in all) {
            assertEquals(e.q.trim(), e.q); assertFalse(e.q.endsWith("?"), e.q)
            assertTrue(e.q.isNotBlank() && e.q.length <= 60, e.q)
        }
        // Hinglish where Jarvis reads it: in most groups.
        val hinglish = Regex("\\b(kya|kyu|kyun|nahi|aaj|kal|hai|ne|ke|kitna|kitne|mera|mere|batao|pooch|kaisa)\\b")
        assertTrue(groups.count { g -> g.examples.any { hinglish.containsMatchIn(it.q) } } >= 8)
        // Its own families: Liquidity's, Solo's and Hero's groups hold theirs.
        assertTrue(groups.first { it.id == "liquidity" }.examples.map { it.family }.containsAll(listOf("LiquidityWhyNot", "LiquidityRecord", "LiquidityMap")))
        assertTrue(groups.first { it.id == "solo" }.examples.any { it.family == "SoloDay" })
        assertTrue(groups.first { it.id == "hero" }.examples.any { it.family == "HeroDay" })
        // The opening read sits with the market and its levels.
        assertTrue(groups.first { it.id == "market" }.examples.any { it.q == "how did the market open" && it.family == "OpeningRead" })
        // Today's notes sit with Jarvis himself (App).
        assertTrue(groups.first { it.id == "app" }.examples.any { it.q == "what did you tell me today" && it.family == "TodayNotes" })
    }

    @Test fun goldShowsOnlyWhatItAnswers() {
        assertEquals(AskGuide.GROUPS, AskGuide.forBuild(gold = false))
        val gold = AskGuide.forBuild(gold = true)
        assertTrue(gold.isNotEmpty())
        assertTrue(gold.all { g -> g.examples.isNotEmpty() && g.examples.all { it.gold } })
        // Nothing of the arms, the account or the indices' chain in IraGoldAlgo.
        val ids = gold.map { it.id }
        for (id in listOf("liquidity", "solo", "hero", "strategies", "account", "plans")) assertFalse(id in ids, id)
        assertTrue(AskGuide.all(gold).none { Regex("(?i)nifty|liquidity|solo|hero|bots|my ").containsMatchIn(it.q) }, AskGuide.all(gold).toString())
    }

    @Test fun theSearchBox() {
        val g = AskGuide.GROUPS
        assertEquals(g, AskGuide.filter(g, ""))
        assertEquals(g, AskGuide.filter(g, "   "))
        // A group's name keeps the whole group.
        val hero = AskGuide.filter(g, "hero")
        assertEquals(g.first { it.id == "hero" }, hero.first { it.id == "hero" })
        // Words in any order, case and punctuation ignored; only the examples holding every word.
        val f = AskGuide.filter(g, "TODAY liquidity?")
        assertEquals(listOf("liquidity"), f.map { it.id })
        assertTrue(f.single().examples.all { it.q.contains("liquidity") && it.q.contains("today") })
        assertEquals(listOf("why didn't liquidity trade today"), f.single().examples.map { it.q })
        // Hinglish words find Hinglish questions.
        assertTrue(AskGuide.filter(g, "kal").flatMap { it.examples }.map { it.q }.containsAll(listOf("kal expiry hai kya", "kal ka plan batao")))
        // Figures stay whole.
        assertEquals(listOf("liquidity ke last 10 trades"), AskGuide.filter(g, "10 trades").flatMap { it.examples }.map { it.q })
        assertTrue(AskGuide.filter(g, "15+5").any { it.id == "liquidity" })
        // Nothing matches: nothing shown.
        assertTrue(AskGuide.filter(g, "cricket score").isEmpty())
    }

    @Test fun theHelpAnswerPointsAtTheGuide() {
        val line = AskGuide.helpLine()
        assertTrue(line.startsWith(AskGuide.POINTER + ". Try "), line)
        assertEquals(3, AskGuide.NAMED.size)
        for (q in AskGuide.NAMED) {
            assertTrue(line.contains("\"$q\""), q)
            assertTrue(AskGuide.all().any { it.q == q }, "$q is in the guide")
            assertFalse(audit.feature(q) in listOf("Act", "Missed", "Help", "Tour"), q)
        }
        // No question mark inside a quote (the voice would stop there).
        assertFalse(line.contains("?"), line)
        for (s in listOf("what can you do", "help", "tum kya kar sakte ho")) {
            val a = Ira().answer(s, emptyMap(), emptyList(), voice = true).text
            assertTrue(a.contains(line), "$s: $a")
            assertTrue(a.startsWith("I can tell you about Nifty"), s)
        }
    }
}
