package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's own changelog ([WhatsNew]): its entries, what is unseen, Home's card's collapse, the seen ids, Jarvis's words. */
class WhatsNewChangelogTest {
    private val d1 = LocalDate.of(2026, 10, 5)
    private val d2 = LocalDate.of(2026, 10, 6)
    private fun e(id: String, d: LocalDate, ask: String? = null, gold: Boolean = false, jarvisOnly: Boolean = false) =
        WhatsNew.Entry(id, d, "Title $id", "What $id.", "Where $id", ask, gold, jarvisOnly)

    // ---- the entries ----

    @Test fun everyEntryIsWholeAndPlain() {
        val all = WhatsNew.ENTRIES
        assertTrue(all.size >= 20, "${all.size}")
        assertEquals(all.size, all.map { it.id }.distinct().size, "ids are unique")
        for (x in all) {
            // Each id starts with its own day (06 Oct, 07 Oct for the un-retired arms, 08 Oct for the locks).
            assertTrue(Regex("^2026-10-0[678]-[a-z0-9-]+$").matches(x.id), x.id)
            assertEquals(LocalDate.parse(x.id.take(10)), x.date, x.id)
            assertTrue(x.title.isNotBlank() && x.title.length <= 60, x.id)
            assertTrue(x.what.isNotBlank() && x.where.isNotBlank(), x.id)
            // One or two plain sentences; a few related fixes in one entry: up to eight short ones (20 words at most each).
            val sentences = Regex("[.!?](\\s|$)").findAll(x.what).count()
            val short = x.what.split(Regex("[.!?](\\s|$)")).all { it.trim().split(' ').size <= 20 }
            assertTrue(sentences in 1..2 || (sentences <= 8 && short), "${x.id}: $sentences sentences")
            // No internal names (camelCase words) and no developer jargon.
            for (t in listOf(x.title, x.what, x.where, x.ask.orEmpty()))
                assertFalse(Regex("\\b[a-z]+[A-Z][A-Za-z]*\\b|\\b[A-Z][a-z]+[A-Z][A-Za-z]*\\b").containsMatchIn(t.replace("BankNifty", "").replace("FinNifty", "")), "${x.id}: $t")
            for (w in listOf("ATR", "CUSUM", "OrbArms", "IraHub", "ira-core", "Compose", "vault", "regex", "detector"))
                assertFalse(w in x.what || w in x.where, "${x.id}: $w")
            x.to?.let { assertTrue(it in setOf("chart", "pnl", "ira", "jarvis", "data", "pine", "strategy"), "${x.id}: $it") }
            assertFalse(x.gold, "${x.id}: IraGoldAlgo only talks; none of today's changes is in it")
        }
    }

    @Test fun todaysChangesAreAllThere() {
        val ids = WhatsNew.ENTRIES.map { it.id.removePrefix("2026-10-06-") }.toSet()
        for (want in listOf("liquidity-only", "liquidity-lots", "solo-midday", "chart-liquidity", "liquidity-level-alert", "liquidity-notifications",
            "trade-lessons", "liquidity-replay", "liquidity-paper-record", "live-vs-backtest", "today-glance", "morning-cues", "big-move-risk",
            "market-data-viewer", "weekly-review", "tomorrow-plan", "liquidity-record", "liquidity-why-not", "solo-day", "liquidity-levels",
            "pine-30-60", "jarvis-trades-30-60"))
            assertTrue(want in ids, want)
        // Written newest first: 07 Oct's lead (Liquidity on MIDCPNIFTY, then the four arms back), then 06 Oct's last change of
        // the day; the first one closes.
        assertEquals("2026-10-08-x1-x2", WhatsNew.ENTRIES.first().id)
        assertEquals("2026-10-08-honest-paper", WhatsNew.ENTRIES[1].id)
        assertEquals("2026-10-08-locks-and-day-lock", WhatsNew.ENTRIES[2].id)
        assertEquals("2026-10-07-liquidity-priority", WhatsNew.ENTRIES[3].id)
        assertEquals("2026-10-07-profit-lock-stop", WhatsNew.ENTRIES[4].id)
        assertEquals("2026-10-07-liquidity-midcpnifty", WhatsNew.ENTRIES[5].id)
        assertEquals("2026-10-07-orb-arms-back", WhatsNew.ENTRIES[6].id)
        assertEquals("2026-10-06-solo-day", WhatsNew.ENTRIES[7].id)
        assertEquals("2026-10-06-liquidity-only", WhatsNew.ENTRIES.last().id)
        assertEquals(WhatsNew.ENTRIES, WhatsNew.newestFirst(WhatsNew.ENTRIES))
    }

    @Test fun liquidityOnMidcapNiftyIsOnPaperUnderTheOneSwitch() {
        val mid = WhatsNew.ENTRIES.first { it.id == "2026-10-07-liquidity-midcpnifty" }
        assertEquals(LocalDate.of(2026, 10, 7), mid.date)
        assertTrue("Midcap Nifty" in mid.title && "Liquidity 15+5" in mid.title, mid.title)
        assertTrue("on paper" in mid.what && "PIN or fingerprint" in mid.what && "8-point index stop" in mid.what, mid.what)
        assertTrue("Liquidity 15+5 row" in mid.where, mid.where)
        assertNull(mid.ask)
    }

    @Test fun liquidityHasPriorityOverTheOrbArmsAndTheLiveGatesStay() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-07-liquidity-priority" }
        assertEquals(LocalDate.of(2026, 10, 7), x.date)
        for (arm in listOf("ORB", "ORB Fresh", "ORB Sweep", "Range Fade", "Liquidity 15+5")) assertTrue(arm in x.what, arm)
        assertTrue("Liquidity has priority over ORB arms" in x.what && "beside them" in x.what, x.what)
        assertTrue("PIN or fingerprint" in x.what && "kill switch" in x.what && "still refused" in x.what, "the live gates are said as unchanged")
        assertTrue("Strategies card" in x.where && "evening replay" in x.where, x.where)
        assertNull(x.ask)
        assertFalse(x.gold)
    }

    @Test fun theX1AndX2ChangesAreSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-08-x1-x2" }
        assertEquals(LocalDate.of(2026, 10, 8), x.date)
        assertTrue("within seconds of each bar close" in x.what, "A: entries at the bar close")
        assertTrue("judged on its own" in x.what, "B: BANKNIFTY apart")
        assertTrue("back to 1 lot unless you chose more" in x.what, "C: 1 lot")
        assertTrue("after 30 trades" in x.what, "D: lessons")
        assertTrue("FinNifty breakdown" in x.what && "arm it again" in x.what, "E: the Pine script")
        assertTrue("Night (R3) on paper" in x.what && "Rs 207" in x.what && "Rs 150" in x.what && "not proven" in x.what, "F: the night arm")
        assertTrue("Strategies card" in x.where && "Night (R3) row" in x.where, x.where)
        assertNull(x.ask)
        assertFalse(x.gold)
    }

    @Test fun honestPaperIsSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-08-honest-paper" }
        assertEquals(LocalDate.of(2026, 10, 8), x.date)
        assertTrue("bid/ask spread" in x.title, x.title)
        assertTrue("like a real order" in x.what && "real bid and ask" in x.what && "measured spread" in x.what, x.what)
        assertTrue("over a minute old" in x.what && "spread it paid" in x.what, x.what)
        assertTrue("Trade → Paper" in x.where && "Strategies card" in x.where && "day report" in x.where, x.where)
        assertNull(x.ask)
        assertFalse(x.gold)
    }

    @Test fun theEighthOfOctobersFiveFixesAreSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-08-locks-and-day-lock" }
        assertEquals(LocalDate.of(2026, 10, 8), x.date)
        assertTrue("only moves up" in x.what && "every tick or minute high" in x.what, "fix 1: the resting stop")
        assertTrue("barely trades" in x.what, "fix 2: thin options")
        assertTrue("backup GTT" in x.what && "sold at once" in x.what && "loud warning" in x.what, "items 6-8: backup, sweeper, no-price warning")
        assertTrue("every 15 seconds" in x.what && "Solo" in x.what && "Liquidity" in x.what, "fix 3: the 15-second check")
        assertTrue("paper buy never fills" in x.what, "fix 4: stale prices")
        assertTrue("net since 1 Oct" in x.what && "paper start date" in x.where, "the paper running total on Home")
        assertTrue("+Rs 8,000" in x.what && "no new automatic trade" in x.what && "open trades keep their exits" in x.what, "fix 5: the day lock")
        assertTrue("Bot settings" in x.where && "off at 0" in x.where && "Strategies card" in x.where, x.where)
        assertNull(x.ask)
        assertFalse(x.gold)
    }

    @Test fun theProfitLockMovesTheRestingStop() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-07-profit-lock-stop" }
        assertEquals(LocalDate.of(2026, 10, 7), x.date)
        for (arm in listOf("ORB", "ORB Fresh", "ORB Sweep", "Range Fade")) assertTrue(arm in x.what, arm)
        assertTrue("moved up to the lock" in x.what && "never down" in x.what && "app closed" in x.what, x.what)
        assertTrue("+20, +40 and +60" in x.what, "ORB Sweep's rungs said as they are")
        assertTrue("Strategies card" in x.where, x.where)
        assertNull(x.ask)
        assertFalse(x.gold)
    }

    @Test fun theFourArmsAreBackOnPaperAndNothingSaysRetired() {
        val back = WhatsNew.ENTRIES.first { it.id == "2026-10-07-orb-arms-back" }
        assertEquals(LocalDate.of(2026, 10, 7), back.date)
        for (arm in listOf("ORB", "ORB Fresh", "ORB Sweep", "Range Fade")) assertTrue(arm in back.title, arm)
        assertTrue("on paper" in back.what && "PIN or fingerprint" in back.what, back.what)
        assertTrue("Strategies card" in back.where, back.where)
        // No entry still says they are retired or can't be switched on, nor points to a Retired list.
        for (x in WhatsNew.ENTRIES) for (t in listOf(x.title, x.what, x.where))
            assertFalse(Regex("(?i)retired|can't be switched on|cannot be armed").containsMatchIn(t), "${x.id}: $t")
    }

    @Test fun eachQuestionToTryIsAnsweredByItsOwnFeature() {
        val audit = CoverageTest()
        val want = mapOf("what did Solo do today" to "SoloDay", "why no liquidity trade today" to "LiquidityWhyNot",
            "what's the plan for tomorrow" to "TomorrowPlan", "how did liquidity do this week" to "LiquidityRecord",
            "explain my bots' trades today" to "BotTrades", "weekly review" to "WeeklyReview", "where are the liquidity levels" to "LiquidityMap",
            "how is Solo doing" to "Solo", "what's GIFT Nifty saying" to "MorningCues", "is a big move likely now" to "BigMoveRisk",
            "what is the stop loss for news trades" to "Glossary", "how many lots is liquidity trading" to "Honest")
        val asks = WhatsNew.ENTRIES.mapNotNull { it.ask }
        assertEquals(want.keys, asks.toSet())
        for (a in asks) {
            assertEquals(want[a], audit.feature(a), a)
            val p = Ask.parse(a)
            assertNull(p.order, a); assertNull(p.command, a); assertFalse(Bundle.acts(a), a)
        }
    }

    // ---- which build shows what ----

    @Test fun goldShowsOnlyItsOwnAndABuildWithoutJarvisNoJarvisLines() {
        assertTrue(WhatsNew.forBuild(gold = true, jarvis = true).isEmpty(), "none of today's is in IraGoldAlgo")
        val mixed = listOf(e("a", d2, ask = "x", gold = true), e("b", d2, ask = "y"), e("c", d2, jarvisOnly = true))
        assertEquals(listOf("a"), WhatsNew.forBuild(gold = true, jarvis = true, entries = mixed).map { it.id })
        assertNull(WhatsNew.forBuild(gold = true, jarvis = true, entries = mixed).single().ask, "IraGoldAlgo names no question of the other build")
        val noJarvis = WhatsNew.forBuild(gold = false, jarvis = false, entries = mixed)
        assertEquals(listOf("a", "b"), noJarvis.map { it.id })
        assertTrue(noJarvis.all { it.ask == null })
        assertEquals(WhatsNew.ENTRIES, WhatsNew.forBuild(gold = false, jarvis = true))
    }

    // ---- unseen, newest first, collapsed ----

    @Test fun unseenNewestFirstAndCollapsedToThree() {
        val list = listOf(e("old1", d1), e("new1", d2), e("new2", d2), e("old2", d1), e("new3", d2))
        assertEquals(listOf("new1", "new2", "new3", "old1", "old2"), WhatsNew.newestFirst(list).map { it.id })
        val unseen = WhatsNew.unseen(list, setOf("new2", "gone"))
        assertEquals(listOf("new1", "new3", "old1", "old2"), unseen.map { it.id })
        assertEquals(listOf("new1", "new3", "old1"), WhatsNew.collapsed(unseen, all = false).map { it.id })
        assertEquals(1, WhatsNew.hidden(unseen, all = false))
        assertEquals(unseen, WhatsNew.collapsed(unseen, all = true))
        assertEquals(0, WhatsNew.hidden(unseen, all = true))
        assertEquals(0, WhatsNew.hidden(unseen.take(2), all = false))
        assertTrue(WhatsNew.unseen(list, list.map { it.id }.toSet()).isEmpty())
        // Nothing seen yet (a phone updated from a build before this card): every entry is new.
        assertEquals(WhatsNew.ENTRIES.size, WhatsNew.unseen(WhatsNew.ENTRIES, WhatsNew.decode(null)).size)
    }

    @Test fun gotItMarksAllSeenAndTheIdsKeep() {
        val list = listOf(e("a", d2), e("b", d2), e("c", d1))
        val seen = WhatsNew.markSeen(setOf("a", "retired-entry"), list.take(2), known = list)
        assertEquals(setOf("a", "b"), seen, "an id no longer in the changelog is dropped")
        assertEquals(listOf("c"), WhatsNew.unseen(list, seen).map { it.id })
        val all = WhatsNew.markSeen(emptySet(), WhatsNew.ENTRIES)
        assertTrue(WhatsNew.unseen(WhatsNew.ENTRIES, all).isEmpty())
        assertEquals(all, WhatsNew.decode(WhatsNew.encode(all)))
        assertEquals("a,b", WhatsNew.encode(setOf("b", "a")))
        assertEquals(emptySet(), WhatsNew.decode(""))
        assertEquals(setOf("x", "y"), WhatsNew.decode(" x , ,y"))
    }

    @Test fun theCardsLines() {
        val x = WhatsNew.ENTRIES.first { it.id.endsWith("liquidity-lots") }
        assertEquals("Where: Home → Dashboard → Strategies card → Liquidity 15+5 row → \"Lots: 1 · 2 · 3\"", WhatsNew.whereLine(x))
        assertEquals("Ask Jarvis: \"how many lots is liquidity trading\"", WhatsNew.askLine(x))
        assertNull(WhatsNew.askLine(WhatsNew.ENTRIES.first { it.id.endsWith("today-glance") }))
        assertEquals("6 Oct", WhatsNew.day(d2))
    }

    // ---- Jarvis ----

    @Test fun theQuestion() {
        for (s in listOf("what's new", "What's new?", "whats new", "what is new", "Jarvis, what's new?", "what's new jarvis", "what's new in the app",
            "what is new in this update", "what's new in the latest version", "what's new in iraalgo", "show me what's new",
            "what changed in the app", "what's changed in the update", "app me kya naya hai", "what has changed in this build", "what are the new features",
            "any new features", "new features", "what features were added", "what's in the new update", "release notes", "show me the changelog",
            "naya kya hai", "kya naya hai", "naya kya aaya", "app mein naya kya hai", "app mein kya naya hai", "update mein kya badla",
            "is update mein naya kya hai", "naya kya hai app mein"))
            assertTrue(WhatsNew.asked(s), s)
        for (s in listOf("what's new in the market", "any news", "news", "what's happening", "latest news", "what's new today", "what is new today",
            "what's new with nifty", "what's new in banknifty", "anything new", "naya kya hai market mein", "what's new since the open",
            "what changed since this morning", "what's changed since i last asked", "what changed since last time", "what changed today",
            "what changed in nifty", "what changed in my positions", "what changed in how you work", "what changed in my bots this week",
            "what's the latest", "any updates", "subah se kya badla", "what's new on the news", "what can i ask you", "update the app",
            "what's up", "what's new with you",
            // A bare "what changed" is SinceLast's (the market since Boss last asked).
            "what changed", "what's changed", "what has changed", "What changed?"))
            assertFalse(WhatsNew.asked(s), s)
    }

    @Test fun jarvisSaysTheNewestSixWithTheirQuestions() {
        val t = WhatsNew.answer(WhatsNew.ENTRIES)
        val lines = t.lines()
        // Two days among the newest six: each line says its day.
        assertEquals("What's new in the app, newest first:", lines.first())
        assertEquals(1 + WhatsNew.SPOKEN + 1, lines.size, t)
        assertEquals("• Faster entries, 1 lot, and a paper night trade (8 Oct): Home → Dashboard → Strategies card (Liquidity's lots and " +
            "the Night (R3) row). Pine scripts screen for the FinNifty script.", lines[1])
        assertEquals("• Paper fills now pay the bid/ask spread (8 Oct): Trade → Paper → tap a trade or an order (Bid/ask spread). " +
            "Home → Dashboard → Strategies card, and the day report.", lines[2])
        assertEquals("• Safer exits, and a day lock at +Rs 8,000 (8 Oct): Settings → Bot settings → Day lock (+Rs 8,000 at first, off at 0) and the paper start date. " +
            "Home → Dashboard → Strategies card shows both.", lines[3])
        assertEquals("• Liquidity 15+5 goes first on an index (7 Oct): Home → Dashboard → Strategies card: an arm's row says when it " +
            "waited for Liquidity, and the evening replay marks those trades.", lines[4])
        assertEquals("• The profit lock now moves the stop itself (7 Oct): Home → Dashboard → Strategies card → an arm's open trade " +
            "(its stop shows the lock).", lines[5])
        assertEquals("• Liquidity 15+5 now also trades Midcap Nifty (7 Oct): Home → Dashboard → Strategies card → Liquidity 15+5 row " +
            "(one switch for all its charts).", lines[6])

        assertEquals("And ${WhatsNew.ENTRIES.size - 6} more, each with where to find it, in Settings → What's new.", lines.last())
        // Never a word of acting.
        assertFalse(Regex("(?i)\\b(placed|bought|sold|armed|switched on)\\b").containsMatchIn(lines.first() + lines.last()))
    }

    @Test fun jarvisOnFewOrNoneAndOnTwoDays() {
        assertEquals("Nothing new to tell you about the app just now, Boss.", WhatsNew.answer(emptyList()))
        val t = WhatsNew.answer(listOf(e("o", d1), e("n", d2, ask = "how is nifty")))
        assertEquals(listOf("What's new in the app, newest first:", "• Title n (6 Oct): try \"how is nifty\".", "• Title o (5 Oct): Where o.",
            "Each one with where to find it is in Settings → What's new."), t.lines())
        assertEquals(2, WhatsNew.answer(WhatsNew.ENTRIES, max = 2).lines().count { it.startsWith("• ") })
    }
}
