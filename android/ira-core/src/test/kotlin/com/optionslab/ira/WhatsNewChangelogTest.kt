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
            // Each id starts with its own day (06 Oct, 07 Oct for the un-retired arms, 08 Oct for the locks; 20 Oct and on too).
            assertTrue(Regex("^2026-10-(0[6789]|[12][0-9])-[a-z0-9-]+$").matches(x.id), x.id)
            assertEquals(LocalDate.parse(x.id.take(10)), x.date, x.id)
            assertTrue(x.title.isNotBlank() && x.title.length <= 60, x.id)
            assertTrue(x.what.isNotBlank() && x.where.isNotBlank(), x.id)
            // One or two plain sentences; a few related fixes in one entry: up to eight short ones (20 words at most each).
            val sentences = Regex("[.!?](\\s|$)").findAll(x.what).count()
            val short = x.what.split(Regex("[.!?](\\s|$)")).all { it.trim().split(' ').size <= 20 }
            assertTrue(sentences in 1..2 || (sentences <= 8 && short), "${x.id}: $sentences sentences")
            // No internal names (camelCase words) and no developer jargon.
            for (t in listOf(x.title, x.what, x.where, x.ask.orEmpty()))
                assertFalse(Regex("\\b[a-z]+[A-Z][A-Za-z]*\\b|\\b[A-Z][a-z]+[A-Z][A-Za-z]*\\b").containsMatchIn(t.replace("BankNifty", "").replace("FinNifty", "").replace("GitHub", "")), "${x.id}: $t")
            for (w in listOf("ATR", "CUSUM", "OrbArms", "IraHub", "ira-core", "Compose", "vault", "regex", "detector"))
                assertFalse(w in x.what || w in x.where, "${x.id}: $w")
            x.to?.let { assertTrue(it in setOf("chart", "pnl", "ira", "jarvis", "data", "pine", "strategy", "candles"), "${x.id}: $it") }
            assertFalse(x.gold, "${x.id}: IraGoldAlgo only talks; none of today's changes is in it")
        }
    }

    /** 25 Oct (Boss: "find out what all the issues we have in the application"): the app audit's fixes, newest of all, plain words. */
    @Test fun theAppAuditFixesAreSaidPlainly() {
        val x = WhatsNew.ENTRIES.first()
        assertEquals("2026-10-25-app-audit-fixes", x.id)
        assertEquals(LocalDate.of(2026, 10, 25), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (w in listOf("Paper fills are now measured", "against their trigger", "failed read", "applies to MCX", "change timeline", "one tap undoes it",
            "lists each position", "opposite ways", "sells what the bots hold", "asks your PIN", "heartbeats but no prices"))
            assertTrue(w in x.what, w)
        assertTrue("Autopilot → change timeline" in x.where, x.where)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
        // The day-trader entry is the one under it.
        assertEquals("2026-10-22-day-trader-borrow", WhatsNew.ENTRIES[1].id)
    }

    /** 22 Oct (the four ideas borrowed from the day-trader skill): under the app audit's entry, plain words, each gate and the flat-by named. */
    @Test fun theDayTraderBorrowIsSaidPlainly() {
        val x = WhatsNew.ENTRIES[1]
        assertEquals("2026-10-22-day-trader-borrow", x.id)
        assertEquals(LocalDate.of(2026, 10, 22), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (w in listOf("reality check", "target or the stop came first", "one-word verdict", "only reports", "no trade is skipped, resized or delayed",
            "exact order its checks cleared today", "three gates", "typed yes", "on by default", "15:25", "stocks 15:20", "MCX 10 minutes before its close",
            "held by design are left alone"))
            assertTrue(w in x.what, w)
        assertTrue("Automation card → Flat-by" in x.where, x.where)
        assertEquals(SelfTalk.Kind.TIME_MACHINE, SelfTalk.asked(x.ask!!)?.kind)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }


    /** 22 Oct (Boss: "Check each and every arm also hidden arms"): the arms audit's fixes, under the day-trader entry, plain words. */
    @Test fun theArmsAuditFixesAreSaidPlainly() {
        val x = WhatsNew.ENTRIES[2]
        assertEquals("2026-10-22-arms-audit-fixes", x.id)
        assertEquals(LocalDate.of(2026, 10, 22), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (t in listOf(x.what, x.title, x.where)) assertFalse(Regex("[a-z][A-Z]").containsMatchIn(t), t)
        for (w in listOf("hidden ones too", "sell into a short", "15:05 square-off", "opens nothing new", "cannot be read", "read again",
            "even when no price can be read", "rests in the paper book", "stay on paper, always", "would-be trades no longer count", "P&L beside its limit"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
        // The 22 Oct diagnostics fixes are the one under it (the day-trader entry above it).
        assertEquals("2026-10-22-diagnostics-fixes", WhatsNew.ENTRIES[3].id)
    }

    /** 22 Oct (Boss: "Check why other arms did not trigger today"): the 22 Oct diagnostics fixes, under the arms audit's entry. */
    @Test fun the22OctDiagnosticsFixesAreSaidPlainly() {
        val x = WhatsNew.ENTRIES[3]
        assertEquals("2026-10-22-diagnostics-fixes", x.id)
        assertEquals(LocalDate.of(2026, 10, 22), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (w in listOf("primary arm, decides first", "keeps one same-direction place", "would-be trade", "names that arm", "says why", "from 09:16", "every 15 seconds", "price at the signal", "who switched it", "battery far less", "past midnight"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    /** 22 Oct (Boss: "if the 10% is received, make the stoploss to the start point"): their breakeven switch - under the 22 Oct diagnostics fixes. */
    @Test fun theBreakevenSwitchIsSaidPlainly() {
        val x = WhatsNew.ENTRIES[4]
        assertEquals("2026-10-22-liquidity-breakeven", x.id)
        assertEquals(LocalDate.of(2026, 10, 22), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (t in listOf(x.what, x.title, x.where)) assertFalse(Regex("[a-z][A-Z]").containsMatchIn(t), t)
        for (w in listOf("stop to the price paid once it is up 10%", "covers the charges", "off until you switch it on", "live trades never use it",
            "did not pass research", "1,238 past trades", "kept apart from the plain-rules record", "one tap undoes it"))
            assertTrue(w in x.what, w)
        assertTrue("Strategy lab" in x.where, x.where)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    /** 22 Oct (Boss: "it went up to 4.5k, and settled at -1k"): the Liquidity exit fixes and R100 - under the breakeven switch's entry. */
    @Test fun theLiquidityExitFixesAreSaidPlainly() {
        val x = WhatsNew.ENTRIES[5]
        assertEquals("2026-10-22-liquidity-exit-fixes", x.id)
        assertEquals(LocalDate.of(2026, 10, 22), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (t in listOf(x.what, x.title, x.where)) assertFalse(Regex("[a-z][A-Z]").containsMatchIn(t), t)
        for (w in listOf("no longer stops the other Liquidity positions' exits", "20-minute time stop", "On paper, after a restart or long stall", "highest price in the gap",
            "none passed", "stay as they were"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    /** 21 Oct (Boss: "Add FT3 to the app paper bot until live ..."): under the two 22 Oct Liquidity entries, plain words, with its question for Jarvis. */
    @Test fun ft3OnPaperIsSaidPlainly() {
        val x = WhatsNew.ENTRIES[6]
        assertEquals("2026-10-21-ft3-paper", x.id)
        assertEquals(LocalDate.of(2026, 10, 21), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (t in listOf(x.what, x.title, x.where)) assertFalse(Regex("[a-z][A-Z]").containsMatchIn(t), t)
        for (w in listOf("on paper on each index's expiry day", "From 11:00 to 12:55", "₹5,000", "sells at 15:05", "no stop on paper",
            "did not pass research", "forward test", "Live with your PIN", "Shadow off", "at most your lot cap", "disaster stop 80%"))
            assertTrue(w in x.what, w)
        assertEquals("how is FT3 doing", x.ask)
        assertTrue(Ft3Talk.asked(x.ask!!), "Jarvis reads its own question")
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    /** 21 Oct (Boss, 20 Oct: "run a loop for enhancement and adding new features"): round 5, under FT3's entry, plain words. */
    @Test fun theFifthEnhancementRoundIsSaidPlainly() {
        val x = WhatsNew.ENTRIES[7]
        assertEquals("2026-10-21-enhance-round-5", x.id)
        assertEquals(LocalDate.of(2026, 10, 21), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (t in listOf(x.what, x.title, x.where)) assertFalse(Regex("[a-z][A-Z]").containsMatchIn(t), t)
        for (w in listOf("counts what the one GTT did", "today and over the last week", "no longer mistakes a position you converted",
            "next morning's opening balance", "only logs until you switch it on"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    /** 21 Oct: round 4, under round 5's entry, plain words. */
    @Test fun theFourthEnhancementRoundIsSaidPlainly() {
        val x = WhatsNew.ENTRIES[8]
        assertEquals("2026-10-21-enhance-round-4", x.id)
        assertEquals(LocalDate.of(2026, 10, 21), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (t in listOf(x.what, x.title, x.where)) assertFalse(Regex("[a-z][A-Z]").containsMatchIn(t), t)
        for (w in listOf("as one GTT all day", "Zerodha cancels the other, even with your phone off", "off until you switch it on with your PIN",
            "a stop resting since the morning", "Money taken out of Zerodha", "never clears the kill switch by itself"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    /** 21 Oct (Boss: "Yes, give commodities their own day lock"): under round 4's entry (round 5's above it), plain words. */
    @Test fun theCommoditiesOwnDayLockIsSaidPlainly() {
        val x = WhatsNew.ENTRIES[9]
        assertEquals("2026-10-21-commodities-own-lock", x.id)
        assertEquals(LocalDate.of(2026, 10, 21), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (t in listOf(x.what, x.title, x.where)) assertFalse(Regex("[a-z][A-Z]").containsMatchIn(t), t)
        for (w in listOf("their own day lock and daily loss limit", "no longer stops the MCX evening bots", "still stop both",
            "with the amount and time", "hourly prices", "show their date"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    /** 21 Oct (Boss, 20 Oct: "run a loop for enhancement and adding new features"): round 3, under the commodities lock's entry, plain words. */
    @Test fun theThirdEnhancementRoundIsSaidPlainly() {
        val x = WhatsNew.ENTRIES[10]
        assertEquals("2026-10-21-enhance-round-3", x.id)
        assertEquals(LocalDate.of(2026, 10, 21), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (t in listOf(x.what, x.title, x.where)) assertFalse(Regex("[a-z][A-Z]").containsMatchIn(t), t)
        for (w in listOf("never sells a filled exit twice", "only logs what it would do until you switch it on", "stay on the live price stream",
            "when you unlock it that evening", "hides their preview", "keeps every live trade"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    /** 20 Oct (Boss: "run a loop for enhancement and adding new features"): round 2, under the 21 Oct entries, plain words. */
    @Test fun theSecondEnhancementRoundIsSaidPlainly() {
        val x = WhatsNew.ENTRIES[11]
        assertEquals("2026-10-20-enhance-round-2", x.id)
        assertEquals(LocalDate.of(2026, 10, 20), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (t in listOf(x.what, x.title, x.where)) assertFalse(Regex("[a-z][A-Z]").containsMatchIn(t), t)
        for (w in listOf("take turns", "never sold twice", "only logs what it would do until you switch it on", "never changes a size",
            "MCX stop now rests at Zerodha overnight", "after its charges", "a chip for each instrument", "explained only",
            "paper and live apart, never on a locked phone"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    /** 20 Oct (Boss: "...run a loop for enhancement and adding new features"): round 1, under round 2's entry, plain words. */
    @Test fun theFirstEnhancementRoundIsSaidPlainly() {
        val x = WhatsNew.ENTRIES[12]
        assertEquals("2026-10-20-enhance-round-1", x.id)
        assertEquals(LocalDate.of(2026, 10, 20), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (t in listOf(x.what, x.title, x.where)) assertFalse(Regex("[a-z][A-Z]").containsMatchIn(t), t)
        for (w in listOf("rest at Zerodha overnight", "would-be trade", "only logs until you switch it on", "from start to start",
            "one shared check of what is held", "only logged"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    /** 20 Oct (Boss: "Deep analysis of the whole application ... resolve them"): under round 1's entry (round 2's on top), plain words. */
    @Test fun theDeepReviewFixesAreSaidPlainly() {
        val x = WhatsNew.ENTRIES[13]
        assertEquals("2026-10-20-deep-review-fixes", x.id)
        assertEquals(LocalDate.of(2026, 10, 20), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (t in listOf(x.what, x.title, x.where)) assertFalse(Regex("[a-z][A-Z]").containsMatchIn(t), t)
        for (w in listOf("never sent twice", "SENSEX options are closed too", "placed again when the next session opens",
            "four orders sent on one signal", "never slows the stop checks", "only finished candles", "only to a private repository"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    /** 20 Oct (Boss: "Resolve all the problems/issues pushed to git by harvest"): under the deep review's entry, plain words. */
    @Test fun the19OctDiagnosticsFixesAreSaidPlainly() {
        assertEquals("2026-10-20-diagnostics-fixes", WhatsNew.ENTRIES[14].id)
        val x = WhatsNew.ENTRIES[14]
        assertEquals(LocalDate.of(2026, 10, 20), x.date)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, "${sentences.size}")
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        for (t in listOf(x.what, x.title, x.where)) assertFalse(Regex("[a-z][A-Z]").containsMatchIn(t.replace("GitHub", "")), t)
        for (w in listOf("only real failures as errors", "said once, even after a restart", "exactly how long", "No rupee figure"))
            assertTrue(w in x.what, w)
        // The Candle agent is the one after it.
        assertEquals("2026-10-20-candle-agent", WhatsNew.ENTRIES[15].id)
    }

    /** 20 Oct (Boss: "add a new agent ... who will only study the candle patterns"): the Candle agent - the newest entry. */
    @Test fun theCandleAgentIsSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-20-candle-agent" }
        assertEquals(15, WhatsNew.ENTRIES.indexOf(x), "under the 19 Oct diagnostics fixes")
        assertEquals(LocalDate.of(2026, 10, 20), x.date)
        for (w in listOf("why a candle is green or red", "support and resistance", "took support from", "the old high a rally ran into",
            "big players' hand", "with the sample size", "no edge said plainly", "learns around the clock", "shares its reads with the other agents",
            "stays a note for you", "It never trades", "among the first things let go when memory is short"))
            assertTrue(w in x.what, "$w: ${x.what}")
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, x.what)
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        assertEquals("Research → Candles", x.where)
        // 20 Oct, enhancement round 2: a tap on it opens Research → Candles (Home's "candles" shortcut).
        assertFalse(x.jarvisOnly); assertFalse(x.gold); assertEquals("candles", x.to)
        assertEquals(CandleAgent.Question.WHY_COLOUR, CandleAgent.asked(x.ask!!))
    }

    /** 19 Oct (Boss: "Yes, keep only breadth donchain"): R51 Donchian 20 record only, R72 Breadth Donchian trading - the newest entry. */
    @Test fun r51RecordOnlyIsSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-19-r51-record-only" }
        assertEquals(16, WhatsNew.ENTRIES.indexOf(x), "under the Candle agent's entry")
        assertEquals(LocalDate.of(2026, 10, 19), x.date)
        for (w in listOf("Only R72 Breadth Donchian now buys", "R51 Donchian 20 is set to record only", "buys nothing", "would-be trade",
            "counted apart", "on, record only or off", "one tap puts R51 back on"))
            assertTrue(w in x.what, "$w: ${x.what}")
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertTrue(sentences.size <= 8, x.what)
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        assertTrue("Record only" in x.where && "Autopilot change log" in x.where, x.where)
        assertEquals(PassedArmsTalk.Topic.DONCHIAN, PassedArmsTalk.asked(x.ask!!))
    }

    /** 19 Oct (Boss: "Resolve the issues"): faster paper orders, the same-direction cap and no duplicate Donchian contracts. */
    @Test fun theSpeedAndTheSameDirectionCapAreSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-19-exposure-speed" }
        assertEquals(17, WhatsNew.ENTRIES.indexOf(x), "under R51's record only entry")
        assertEquals(LocalDate.of(2026, 10, 19), x.date)
        for (w in listOf("well under a second", "At most two automatic paper option positions", "would-be trades", "never capped",
            "counted apart", "On live it only logs", "R51 and R72 no longer buy the same option twice"))
            assertTrue(w in x.what, "$w: ${x.what}")
        assertTrue("Same-direction cap" in x.where && "Autopilot change log" in x.where, x.where)
    }

    /** 18 Oct (Boss: "What ever strategies are present for paper, should be present for live too"; a Shadow switch per arm): the newest entry. */
    @Test fun liveArmsAndShadowAreSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-18-arms-live" }
        assertEquals(18, WhatsNew.ENTRIES.indexOf(x), "under the 19 Oct speed and cap entry")
        assertEquals(LocalDate.of(2026, 10, 18), x.date)
        for (w in listOf("Trade live switch", "starts in Shadow", "sends nothing to Zerodha", "needs your PIN", "ask and bid",
            "rest at Zerodha", "safety stop 30% below", "a check that fails blocks the entry", "Exits, stops and square-offs always go"))
            assertTrue(w in x.what, "$w: ${x.what}")
    }

    /** 18 Oct (Boss: "test each and everything ... should work at its minimum time"): the Live check, the newest entry. */
    @Test fun theLiveCheckIsSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-18-live-check" }
        assertEquals(19, WhatsNew.ENTRIES.indexOf(x), "under the live arms entry")
        assertEquals(LocalDate.of(2026, 10, 18), x.date)
        for (w in listOf("never places an order", "reconnect", "stop is watched", "kept out of every result", "over two seconds old is checked with Zerodha"))
            assertTrue(w in x.what, "$w: ${x.what}")
    }

    /** 18 Oct (Boss: "When i switch to live, i want same as paper, directly placing order with pin"): the newest entry, plain words. */
    @Test fun liveLikePaperIsSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-18-live-like-paper" }
        assertEquals(20, WhatsNew.ENTRIES.indexOf(x), "under the live check entry")
        assertEquals(LocalDate.of(2026, 10, 18), x.date)
        for (w in listOf("after your PIN", "no review card", "Every check still runs", "turns on AI trades too", "Jarvis's own ideas stay on paper",
            "paper-only period no new live entry", "exits, stops and square-offs always do", "close a real Zerodha position with your PIN",
            "Live orders without PIN was switched off by this update"))
            assertTrue(w in x.what, "$w: ${x.what}")
        // 18 Oct review: eight short sentences at most, each of 20 words or fewer.
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertEquals(8, sentences.size, x.what)
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
    }

    /** 18 Oct (the whole-app review): the newest entry, in plain words; the kill switch's PIN and the locked phone said first. */
    @Test fun theWholeAppReviewFixesAreSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-18-whole-app-review" }
        assertEquals(21, WhatsNew.ENTRIES.indexOf(x), "under the live-like-paper entry")
        assertEquals(LocalDate.of(2026, 10, 18), x.date)
        for (w in listOf("kill switch can no longer be cleared by mistake", "asks for your PIN", "locked phone", "MCX expiry exit reads your positions fresh",
            "strategy legs", "short positions", "Live cards keep their last figures", "Night", "Gold lots", "Signal tester stops", "sandbox MIS",
            "Screens never wait", "tokens are hidden"))
            assertTrue(w in x.what, w)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertEquals(8, sentences.size, x.what)
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        assertTrue("Account guard" in x.where, x.where)
        assertNull(x.ask)
        assertFalse(x.gold)
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
        // Entries newer than 12 Oct sit above these (13 Oct onwards); from 12 Oct's first entry down the order is fixed.
        val o = WhatsNew.ENTRIES.indexOfFirst { it.id == "2026-10-12-tick-zone" }
        assertTrue(o >= 0 && WhatsNew.ENTRIES.take(o).all { it.date > LocalDate.of(2026, 10, 12) }, "$o")
        assertTrue(WhatsNew.ENTRIES.take(o).any { it.id == "2026-10-13-exit-research" })
        assertEquals("2026-10-12-order-review", WhatsNew.ENTRIES[o + 1].id)
        assertEquals("2026-10-12-majority-rule-context", WhatsNew.ENTRIES[o + 2].id)
        assertEquals("2026-10-11-home-automation-card", WhatsNew.ENTRIES[o + 3].id)
        assertEquals("2026-10-11-self-improvement-round-2", WhatsNew.ENTRIES[o + 4].id)
        assertEquals("2026-10-11-strategy-lab-auto", WhatsNew.ENTRIES[o + 5].id)
        assertEquals("2026-10-11-smarter-learning", WhatsNew.ENTRIES[o + 6].id)
        assertEquals("2026-10-11-self-healing-app", WhatsNew.ENTRIES[o + 7].id)
        assertEquals("2026-10-11-autopilot-learning", WhatsNew.ENTRIES[o + 8].id)
        assertEquals("2026-10-11-jarvis-learns", WhatsNew.ENTRIES[o + 9].id)
        assertEquals("2026-10-11-chart-any-symbol", WhatsNew.ENTRIES[o + 10].id)
        assertEquals("2026-10-10-manager-research", WhatsNew.ENTRIES[o + 11].id)
        assertEquals("2026-10-10-manager-specialists", WhatsNew.ENTRIES[o + 12].id)
        assertEquals("2026-10-10-trade-manager", WhatsNew.ENTRIES[o + 13].id)
        assertEquals("2026-10-10-findings-bus", WhatsNew.ENTRIES[o + 14].id)
        assertEquals("2026-10-10-smart-workers", WhatsNew.ENTRIES[o + 15].id)
        assertEquals("2026-10-10-parallel-workers", WhatsNew.ENTRIES[o + 16].id)
        assertEquals("2026-10-09-big-move-recorder", WhatsNew.ENTRIES[o + 17].id)
        assertEquals("2026-10-09-fno-to-1540", WhatsNew.ENTRIES[o + 18].id)
        assertEquals("2026-10-09-order-flow-auction", WhatsNew.ENTRIES[o + 19].id)
        assertEquals("2026-10-09-order-flow", WhatsNew.ENTRIES[o + 20].id)
        assertEquals("2026-10-09-live-prices-everywhere", WhatsNew.ENTRIES[o + 21].id)
        assertEquals("2026-10-09-strategies-to-research", WhatsNew.ENTRIES[o + 22].id)
        assertEquals("2026-10-09-order-speed", WhatsNew.ENTRIES[o + 23].id)
        assertEquals("2026-10-09-two-paper-bots-and-parking", WhatsNew.ENTRIES[o + 24].id)
        assertEquals("2026-10-09-mcx-paper-arms", WhatsNew.ENTRIES[o + 25].id)
        assertEquals("2026-10-09-mcx", WhatsNew.ENTRIES[o + 26].id)
        assertEquals("2026-10-08-x1-x2", WhatsNew.ENTRIES[o + 27].id)
        assertEquals("2026-10-08-honest-paper", WhatsNew.ENTRIES[o + 28].id)
        assertEquals("2026-10-08-locks-and-day-lock", WhatsNew.ENTRIES[o + 29].id)
        assertEquals("2026-10-07-liquidity-priority", WhatsNew.ENTRIES[o + 30].id)
        assertEquals("2026-10-07-profit-lock-stop", WhatsNew.ENTRIES[o + 31].id)
        assertEquals("2026-10-07-liquidity-midcpnifty", WhatsNew.ENTRIES[o + 32].id)
        assertEquals("2026-10-07-orb-arms-back", WhatsNew.ENTRIES[o + 33].id)
        assertEquals("2026-10-06-solo-day", WhatsNew.ENTRIES[o + 34].id)
        assertEquals("2026-10-06-liquidity-only", WhatsNew.ENTRIES.last().id)
        assertEquals(WhatsNew.ENTRIES, WhatsNew.newestFirst(WhatsNew.ENTRIES))
    }

    @Test fun donchiansSupertrendFilterIsSaidAsSkipsOnlyOnPaper() {
        // 16 Oct (R73 review, Boss: "T7 supertrend agree"): the newest entry - R51 only, its research numbers, skips only, paper only.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-16-donchian-supertrend" }
        assertEquals(23, WhatsNew.ENTRIES.indexOf(x), "under the 18 Oct review fixes and the 17 Oct diagnostics fixes")
        for (w in listOf("R51 Donchian 20", "Supertrend (10, 3)", "R73", "+₹1,34,743", "1,670 trades", "+₹81,845", "only skips trades",
            "cannot judge is not taken", "R72 Breadth Donchian keeps its own rules", "paper only", "change log", "one tap undoes it"))
            assertTrue(w in x.what, w)
        assertTrue("R51 Donchian 20" in x.where)
        assertEquals(PassedArmsTalk.Topic.DONCHIAN, PassedArmsTalk.asked(x.ask!!))
        assertFalse(x.jarvisOnly); assertFalse(x.gold); assertNull(x.to)
    }

    @Test fun breadthDonchianIsSaidWithItsRiskAndPaperOnly() {
        // 16 Oct (Boss: "Okay add it", after R72): the newest entry - beside Donchian, its research, its risk, paper only, undone in one tap.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-16-breadth-donchian" }
        assertEquals(24, WhatsNew.ENTRIES.indexOf(x), "under R51's Supertrend filter's entry")
        for (w in listOf("at least 4 of the five indices break together", "beside R51 Donchian 20", "same days", "R72", "+₹1,71,366",
            "1,202 trades", "+₹55,439", "₹1.99 lakh", "−₹40k account stop", "paper only", "change log", "one tap undoes it"))
            assertTrue(w in x.what, w)
        assertTrue("R72 Breadth Donchian" in x.where)
        assertEquals(PassedArmsTalk.Topic.BREADTH, PassedArmsTalk.asked(x.ask!!))
        assertFalse(x.jarvisOnly); assertFalse(x.gold); assertNull(x.to)
    }

    @Test fun theL25StepIsSaidAsBossChoiceNotProven() {
        // 16 Oct (Boss's choice after R65 - R69: "Select the best"; R66 L25 a small, not proven improvement): the newest entry, paper only.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-16-passed-arms-l25" }
        assertEquals(25, WhatsNew.ENTRIES.indexOf(x), "under R72 Breadth Donchian's entry")
        for (w in listOf("R51 Donchian 20", "R52 Open outside value", "Solo 2.0", "lock +5% once the option has been +25%", "30-60-100 lock stays",
            "never goes back down", "R66", "small improvement, not a proven one", "+₹5,396", "2,202 trades", "+₹8,042", "your choice, on paper only",
            "open trades keep their exits", "change log", "one tap undoes it"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold); assertNull(x.to)
    }

    @Test fun theDiagnosticsFixesAreSaidPlainly() {
        // 16 Oct, Boss's diagnostics of 13:53: the newest entry; nothing it says asks Boss to change a phone setting.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-16-diagnostics-fixes" }
        assertEquals(26, WhatsNew.ENTRIES.indexOf(x), "the newest entry")
        for (w in listOf("MCX's evening", "armed MCX arms", "why Android closed the app", "Telegram channels now stay selected",
            "Zerodha's server time", "at most 2 seconds old", "5 seconds at most", "fingerprint"))
            assertTrue(w in x.what, w)
        assertFalse("automatic date and time" in x.what)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    @Test fun thePassedArmsLockIsSaidAsBossChoice() {
        // 16 Oct (Boss's choice: "All three"; research R64 A4 about even with the old exits): the newest entry, paper only.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-16-passed-arms-lock" }
        assertEquals(27, WhatsNew.ENTRIES.indexOf(x), "the newest entry but the diagnostics fixes of the same day")
        for (w in listOf("R51 Donchian 20", "R52 Open outside value", "Solo 2.0", "no fixed target", "own stop and time exit", "breakeven",
            "+60%", "about even", "your choice on paper only", "one tap undoes it"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    @Test fun thePassedArmsExitsFixIsSaidPlainly() {
        // 16 Oct (an R51 re-entry held past its target and time exit): the newest entry, paper only, the safety net said.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-16-passed-arms-exits" }
        assertEquals(28, WhatsNew.ENTRIES.indexOf(x), "the newest entry")
        for (w in listOf("second trade on the same option", "booked the moment it fills", "every 15 seconds", "safety net", "tells you", "on paper"))
            assertTrue(w in x.what, w)
        assertFalse(x.jarvisOnly); assertFalse(x.gold); assertNull(x.to)
    }

    @Test fun theOpenTradeChangeIsSaidPlainly() {
        // 16 Oct (Boss read "Change +48.57%" as the open trade's): under the same day's exits fix and Telegram login fix.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-16-open-trade-change" }
        assertEquals(30, WhatsNew.ENTRIES.indexOf(x), "under the passed arms' exits fix and the Telegram login fix")
        assertEquals(LocalDate.of(2026, 10, 16), x.date)
        for (w in listOf("open trade's move on its average price", "not earlier trades on the same option", "Exits", "stop, target and exit time"))
            assertTrue(w in x.what, w)
        assertTrue("Tap any open position" in x.where, x.where)
        assertFalse(x.jarvisOnly); assertFalse(x.gold); assertNull(x.to); assertNull(x.ask)
    }

    @Test fun theTrendWatchIsSaidWithWhatPassedAndWhatIsOnlyANote() {
        // 16 Oct (Boss: "if the trend changes, sell the orders"; research R63): the newest entry.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-16-trend-watch" }
        assertEquals(31, WhatsNew.ENTRIES.indexOf(x), "the newest entry")
        for (w in listOf("pending order", "index's trend", "writes a note", "would sell at", "Hero ₹1,000 ticket's paper legs", "passed research",
            "lost against random exits", "stay notes", "Live positions are never sold", "change log", "one tap undoes it"))
            assertTrue(w in x.what, w)
        assertTrue("Trend watch" in x.where)
        assertEquals("has the trend turned on my trades", x.ask)
    }

    @Test fun theMarketConceptsAreSaidHonestly() {
        // 15 Oct (Boss: Solo should "understand the concepts"): the newest entry; no concept is said to make money.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-15-market-concepts" }
        assertEquals(32, WhatsNew.ENTRIES.indexOf(x), "under the passed arms' exits fix")
        for (w in listOf("40 market ideas", "OI", "gamma exposure", "bull traps", "fake breakouts", "order flow", "signal comes from",
            "research found", "sample sizes", "None of them beat Solo 2.0", "why it took or skipped", "what it trades is unchanged", "on paper"))
            assertTrue(w in x.what, w)
        assertTrue("Market concepts" in x.where && "Solo 2.0's name" in x.where, x.where)
        assertEquals("what is a bull trap", x.ask)
        assertTrue(Concepts.asked(x.ask!!) != null)
        assertFalse(x.jarvisOnly); assertFalse(x.gold); assertNull(x.to)
    }

    @Test fun theArmsCleanupAndTheCompactRowsAreSaidPlainly() {
        // 15 Oct (Boss's questionnaire): the newest two entries.
        val c = WhatsNew.ENTRIES.first { it.id == "2026-10-15-arms-cleanup" }
        assertEquals(33, WhatsNew.ENTRIES.indexOf(c), "under the exits fix, the Telegram login fix, the open trade change and the market concepts")
        for (w in listOf("On your choice", "Liquidity 15+5 on BANKNIFTY, FINNIFTY and MIDCPNIFTY", "Hero ₹1,000 ticket", "R51", "R52", "Solo 2.0",
            "Watchlist", "Solo midday", "MCX paper arms", "switched off and hidden", "trades kept", "own exit first", "on paper",
            "NIFTY has no Liquidity book", "one tap", "Hidden arms list"))
            assertTrue(w in c.what, w)
        assertTrue("Home → Strategies → Hidden arms" in c.where && "Autopilot → Change log" in c.where, c.where)
        val r = WhatsNew.ENTRIES.first { it.id == "2026-10-15-compact-rows" }
        assertEquals(34, WhatsNew.ENTRIES.indexOf(r))
        for (w in listOf("name", "one status line", "key numbers", "switch", "Tap the name", "OK button")) assertTrue(w in r.what, w)
        for (x in listOf(c, r)) { assertFalse(x.jarvisOnly); assertFalse(x.gold); assertNull(x.to); assertNull(x.ask) }
    }

    @Test fun theHero1000TicketIsSaidAsBossChoiceThatDidNotPassResearch() {
        // 15 Oct (Boss: "add it directly to arm"): the newest entry; a paper arm on by Boss's choice, R50's verdict said with it.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-15-hero-1000-ticket" }
        assertEquals(37, WhatsNew.ENTRIES.indexOf(x), "the first 15 Oct entry (the passed arms, the Watchlist and the cleanup came later that day)")
        assertEquals(LocalDate.of(2026, 10, 15), x.date)
        for (w in listOf("On your choice", "₹1,000", "12:30", "NIFTY", "SENSEX", "own expiry days", "25% trail", "15:05", "did not pass research",
            "R50", "−₹525 per ₹1,000", "jackpots are rare", "own record", "never trades on Zerodha", "one tap switches it off"))
            assertTrue(w in x.what, w)
        assertTrue("Home → Strategies → Hero ₹1,000 ticket" in x.where && "2× / 3× / trail-only" in x.where && "Autopilot → Change log" in x.where, x.where)
        assertEquals("how is the hero ticket doing", x.ask)
        assertTrue(HeroTicketTalk.asked(x.ask!!))
        assertFalse(x.jarvisOnly); assertFalse(x.gold); assertNull(x.to)
    }

    @Test fun thePassedArmsAndTheWatchlistAreSaidWithTheirLabels() {
        // 15 Oct (Boss: "Both"): the newest two entries.
        val a = WhatsNew.ENTRIES.first { it.id == "2026-10-15-passed-paper-arms" }
        assertEquals(35, WhatsNew.ENTRIES.indexOf(a), "under the exits fix, the Telegram login fix, the open trade change, the market concepts and the arms cleanup's two entries")
        for (w in listOf("R51 Donchian 20", "R52 Open outside value", "Solo 2.0", "1 lot each", "passed every research gate", "paper rule",
            "stays on paper forever", "signal from the index", "stop", "own record", "forward test", "None ever trades on Zerodha", "one tap switches each off"))
            assertTrue(w in a.what, w)
        assertTrue("Passed research (R51/R52/R57) — paper only" in a.where && "Autopilot → Change log" in a.where, a.where)
        assertEquals(PassedArmsTalk.Topic.ALL, PassedArmsTalk.asked(a.ask!!))
        val w = WhatsNew.ENTRIES.first { it.id == "2026-10-15-watchlist-shadow" }
        assertEquals(36, WhatsNew.ENTRIES.indexOf(w))
        for (x in listOf("shadow", "R47 C4", "option-chain signal, marked non-default", "R52 R4-8", "R52 H45-1", "never traded", "paper costs",
            "40 new trades", "which to drop")) assertTrue(x in w.what, x)
        assertTrue("Watchlist (shadow)" in w.where && "evening summary" in w.where)
        assertEquals(PassedArmsTalk.Topic.WATCHLIST, PassedArmsTalk.asked(w.ask!!))
        for (x in listOf(a, w)) { assertFalse(x.jarvisOnly); assertFalse(x.gold); assertNull(x.to) }
    }

    @Test fun signalOnTheIndexIsSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-14-signal-on-the-index" }
        assertEquals(LocalDate.of(2026, 10, 14), x.date)
        assertEquals("Strategies read their signal from the index and then buy the option (the default); a Pine script set to an option " +
            "chart is refused; option-derived ideas are labelled.", x.what)
        assertTrue("Pine" in x.where && "Strategies" in x.where && "Strategy lab" in x.where, x.where)
        assertNull(x.ask)
        assertEquals(40, WhatsNew.ENTRIES.indexOf(x), "under the gamma line's entry (14 Oct, later that day; 15 Oct's six and 16 Oct's three on top)")
    }

    @Test fun theSilvermMorningCallSwitchOffIsSaidWithR42sNumbersAndTheUndo() {
        // 14 Oct (research R42, Boss's OK): the newest entry; a paper bot switched off once, as the 13 Oct ORB park was told.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-14-silverm-morning-off" }
        assertEquals(38, WhatsNew.ENTRIES.indexOf(x), "the newest 14 Oct entry (15 Oct's six and 16 Oct's three above it)")
        assertEquals(LocalDate.of(2026, 10, 14), x.date)
        for (w in listOf("R42", "53 holdout trades", "random minute", "129 trades", "open position still closes by its own exit", "one tap", "paper only"))
            assertTrue(w in x.what, w)
        assertTrue("MCX (commodities)" in x.where && "Autopilot → Change log" in x.where, x.where)
        assertNull(x.ask); assertFalse(x.jarvisOnly); assertFalse(x.gold)
    }

    @Test fun theGammaLineAndTheOiWallShadowAreSaidAsInformationOnly() {
        // 14 Oct (Boss approved): two non-trading additions, right under the SILVERM morning call's switch-off (later that day).
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-14-gamma-line-oi-wall-shadow" }
        assertEquals(39, WhatsNew.ENTRIES.indexOf(x), "under the SILVERM morning call's entry")
        assertEquals(LocalDate.of(2026, 10, 14), x.date)
        for (w in listOf("10:00", "dealer gamma", "says nothing about direction", "never a trade signal", "expiry days", "13:45 to 14:30",
            "only written down", "in points", "never places an order"))
            assertTrue(w in x.what, w)
        assertTrue("Settings → Jarvis → Market alerts" in x.where && "Order Review" in x.where, x.where)
        // No question to try here (the audit's list of features is fixed); the switch's own words name "what's gamma saying".
        assertNull(x.ask)
        assertEquals("", GammaMorning.asked("what's gamma saying"))
        assertTrue(x.jarvisOnly); assertEquals("jarvis", x.to)
    }

    @Test fun thePreTradeChecksAreSaidWithTheirTestResult() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-13-pretrade-checks" }
        assertEquals(LocalDate.of(2026, 10, 13), x.date)
        for (w in listOf("Liquidity aside", "none beat chance", "never skip, resize or move a trade", "half size for paper arms on careful days, is off"))
            assertTrue(w in x.what, w)
        // With the samples behind "none beat chance" (review, 13 Oct): the holdouts' sizes, and Hero's tiny one flagged.
        for (w in listOf("499 trades for the charges", "15 for the stop", "122 days for the rating", "Hero had only 8 test trades and 2 held-back ones"))
            assertTrue(w in x.what, w)
        assertTrue("${ChargesCheck.HOLDOUT_TRADES}" in x.what && "${SmartStop.HOLDOUT_TRADES}" in x.what && "${DangerMeter.HOLDOUT_DAYS}" in x.what)
    }

    @Test fun theDailyDiagnosticsAreSaidWithTheirPrivacy() {
        // G3 (13 Oct): one small file a day with the evening upload, cleaned on the phone; P&L only in points and percent.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-13-github-diagnostics" }
        for (w in listOf("one small file", "taken or skipped and why", "points and percent", "no tokens, ids, names, phone, e-mail or rupee amounts",
            "private data repository"))
            assertTrue(w in x.what, w)
        assertTrue("Diagnostics" in x.where && "GitHub sync" in x.where, x.where)
        assertEquals("data", x.to)
        assertFalse(x.jarvisOnly)
        // Said right after the sync it rides on.
        val ids = WhatsNew.ENTRIES.map { it.id }
        assertEquals(ids.indexOf("2026-10-13-github-sync") + 1, ids.indexOf(x.id))
    }

    @Test fun theGitHubSyncIsSaidWithItsSafety() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-13-github-sync" }
        // G4 (13 Oct): after the MCX evening close, 15:45 only on days without an MCX session.
        for (w in listOf("after the MCX evening close", "15:45 on days without an MCX session", "Wi-Fi while charging",
            "sealed with your backup passphrase", "Restore from GitHub", "switched off", "never in it", "never the app's code"))
            assertTrue(w in x.what, w)
        assertTrue("GitHub sync" in x.where, x.where)
        assertEquals("data", x.to)
        assertNull(x.ask)
    }

    @Test fun theOrderReviewIsSaidWithItsSafety() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-12-order-review" }
        assertEquals(LocalDate.of(2026, 10, 12), x.date)
        for (w in listOf("every order and trade", "after the close", "your own", "flagged for a fix", "strategy lab on paper first",
            "never places, changes or cancels an order"))
            assertTrue(w in x.what, w)
        assertEquals("Home → Automation card → Order review", x.where)
        assertEquals("what went wrong today", x.ask)
        assertTrue(OrderReview.asked(x.ask!!) != null)
        assertTrue(x.jarvisOnly)
    }

    @Test fun majorityRuleIsSaidAsContextOnly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-12-majority-rule-context" }
        assertEquals(LocalDate.of(2026, 10, 12), x.date)
        for (w in listOf("Majority Rule 14", "drift up over 1-3 months", "+1,000 points and the green candle added nothing", "break-even to losing",
            "context only", "no alert, no strategy, never an entry and never more risk"))
            assertTrue(w in x.what, w)
        assertTrue("Majority Rule 14" in x.where && "Brain" in x.where, x.where)
        assertNull(x.ask); assertNull(x.to); assertFalse(x.jarvisOnly)
    }

    @Test fun fnoTo1540SaysTheOrderWindowAndWatchFollowIt() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-09-fno-to-1540" }
        assertEquals(LocalDate.of(2026, 10, 9), x.date)
        assertTrue("NSE F&O now trades to 15:40; the app's order window and watch follow it." in x.what, x.what)
        assertTrue("15:30" in x.what && "own entry and exit times" in x.what, x.what)
        assertNull(x.ask)
        assertFalse(x.jarvisOnly)
    }

    @Test fun theParallelWorkersAreSaidPlainlyWithTheirSafety() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-10-parallel-workers" }
        for (w in listOf("side by side", "run first", "never wait behind an entry", "one door", "kill switch",
            "same index in the same second", "8 times a second", "gives way to trading", "slowest steps"))
            assertTrue(w in x.what, w)
        assertTrue("Order speed" in x.where && "diagnostics" in x.where, x.where)
        assertNull(x.ask)
    }

    @Test fun theTradeManagerIsSaidPlainlyWithItsSafety() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-10-trade-manager" }
        for (w in listOf("exits early", "lock at breakeven plus charges", "never down", "never adds lots", "never widens a stop",
            "acts on paper", "only records until you switch it on with your PIN", "what the original rules would have done",
            "Every other strategy only records", "a hint, never a switch"))
            assertTrue(w in x.what, w)
        assertEquals(TradeManager.Ask(false, null), TradeManager.asked(x.ask!!))
        assertTrue("Trade manager record" in x.where)
    }

    @Test fun theSelfHealingAppIsSaidPlainlyWithItsSafety() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-11-self-healing-app" }
        assertEquals("2026-10-11-self-healing-app", x.id)
        assertEquals(LocalDate.of(2026, 10, 11), x.date)
        for (w in listOf("watches its own health on this phone", "screen stalls, freezes, crashes and order speed", "slows that refresh a little",
            "restores it after three healthy days", "never slows orders, stops or live prices", "up to three things to learn next",
            "never trading", "Every Sunday he tidies his memory", "On Saturday morning he sends a weekly report"))
            assertTrue(w in x.what, w)
        assertTrue("Weekly report" in x.where && "What Jarvis has learned" in x.where && "Health" in x.where, x.where)
        assertTrue(x.jarvisOnly); assertEquals("how did you grow this week", x.ask)
        // Its question goes to the weekly self-report and acts on nothing.
        assertTrue(GrowthReport.asked(x.ask!!))
    }

    @Test fun theChartPickerIsSaidPlainlyAndOpensTheChart() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-11-chart-any-symbol" }
        assertEquals("2026-10-11-chart-any-symbol", x.id)
        assertEquals(LocalDate.of(2026, 10, 11), x.date)
        for (w in listOf("Tap the symbol at the top of the Chart tab", "Sensex and India VIX", "MCX commodity's near-month future",
            "underlying, expiry and strike, then CE or PE", "search any index, MCX contract or option", "last six symbols",
            "reopens on the symbol you charted last", "greyed with the reason"))
            assertTrue(w in x.what, w)
        assertTrue("Chart tab" in x.where, x.where)
        assertEquals("chart", x.to)
        // No question to try: the asks (and Jarvis's routing of them) are unchanged.
        assertNull(x.ask)
        assertFalse(x.jarvisOnly)
        assertFalse(x.gold)
    }

    @Test fun theStrategyLabIsSaidPlainlyAndHonestly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-11-strategy-lab-auto" }
        assertEquals("2026-10-11-strategy-lab-auto", x.id)
        assertEquals(LocalDate.of(2026, 10, 11), x.date)
        for (w in listOf("no orders, not even paper", "beat the current rules for 20 days", "applied by itself, with undo",
            "on live it is only proposed, with your PIN", "while charging or after 18:00", "Almost all fail", "random-twin check",
            "count of every idea ever tried", "start them on paper", "Jarvis learns from every result", "graveyard"))
            assertTrue(w in x.what, w)
        assertTrue("Strategy lab" in x.where, x.where)
        assertTrue(x.jarvisOnly); assertEquals("how are my strategies improving", x.ask)
        // Its question goes to the lab's own answer.
        assertEquals(LabTalk.Kind.IMPROVING, LabTalk.asked(x.ask!!)?.kind)
    }

    @Test fun smarterLearningIsSaidPlainlyWithItsSafety() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-11-smarter-learning" }
        assertEquals("2026-10-11-smarter-learning", x.id)
        assertEquals(LocalDate.of(2026, 10, 11), x.date)
        for (w in listOf("q-value", "all lessons are tested together", "only on busy days", "replays the change on your stored trades",
            "would have done worse", "paper experiment on each paper trade both ways, never on live", "as soon as the evidence is clear",
            "the bar rises with every setting it has tried"))
            assertTrue(w in x.what, w)
        assertTrue("Autopilot" in x.where && "Change log" in x.where, x.where)
        assertTrue(x.jarvisOnly); assertEquals("what experiments are running", x.ask)
        // Its question goes to Autopilot's own answer and acts on nothing.
        assertEquals(AutoChange.AskKind.EXPERIMENTS, AutoChange.asked(x.ask!!)?.kind)
    }

    @Test fun autopilotLearningIsSaidPlainlyWithItsSafety() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-11-autopilot-learning" }
        assertEquals("2026-10-11-autopilot-learning", x.id)
        assertEquals(LocalDate.of(2026, 10, 11), x.date)
        for (w in listOf("by themselves, within strict bounds", "On shadow and paper", "On live they only lower risk", "still asked, with your PIN",
            "undone with one tap", "After 15 trades or 5 days", "reverts itself if it made things worse",
            "never touches the PIN, Live, AI-live, the guard, the app lock, the day lock, sizes or open orders", "OFF, SAFE or SHADOW-ONLY",
            "\"turn off autopilot\" by voice always works"))
            assertTrue(w in x.what, w)
        assertTrue("Autopilot" in x.where && "Change log" in x.where && "Settings" in x.where, x.where)
        assertTrue(x.jarvisOnly); assertEquals("how is autopilot doing", x.ask)
        // Its question goes to Autopilot's own answer and acts on nothing.
        assertEquals(AutoChange.AskKind.HOW, AutoChange.asked(x.ask!!)?.kind)
    }

    @Test fun jarvisLearningIsSaidPlainlyWithItsSafety() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-11-jarvis-learns" }
        assertEquals("2026-10-11-jarvis-learns", x.id)
        assertEquals(LocalDate.of(2026, 10, 11), x.date)
        for (w in listOf("after the close", "trade manager", "specialists", "order flow", "each with its numbers", "30 or more cases that agree over time",
            "weakens and then retires", "asks you first and never changes anything himself", "lowering risk or acting on paper",
            "two noes rest a suggestion for 30 days", "never touches the PIN, Live, the app lock or real orders"))
            assertTrue(w in x.what, w)
        assertTrue("What Jarvis has learned" in x.where, x.where)
        assertTrue(x.jarvisOnly); assertEquals("ira", x.to)
        // Its question is the learning's own.
        assertEquals(AgentLessons.AskKind.ALL, AgentLessons.asked(x.ask!!)?.kind)
    }

    @Test fun theManagersResearchIsSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-10-manager-research" }
        assertEquals("2026-10-10-manager-research", x.id)
        for (w in listOf("ORB Fresh", "paper trades only", "still loses overall", "only rises", "Live stays a record", "choices and mutes are kept"))
            assertTrue(w in x.what, w)
        assertTrue(TradeManager.asked(x.ask!!)!!.research)
        assertTrue("Trade manager record" in x.where)
    }

    @Test fun theManagersSpecialistsAreSaidPlainlyWithTheirSafety() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-10-manager-specialists" }
        for (w in listOf("Sixteen specialists", "chair weighs their votes", "News, traps and bad data can still order an exit alone",
            "The lock still only rises", "never widened", "nothing is ever added", "report card against the original rules",
            "mute one yourself", "accept a change with your PIN"))
            assertTrue(w in x.what, w)
        assertEquals(TradeManager.TeamKind.HOW, TradeManager.asked(x.ask!!)!!.team!!.kind)
        assertTrue("Specialists" in x.where)
    }

    @Test fun theSmartWorkersAndTheFindingsAreSaidPlainlyWithTheirSafety() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-10-smart-workers" }
        for (w in listOf("wakes when something it waits for happens", "safety net, every 15 s while anything is held", "tells you once",
            "pause for 5 minutes around news", "paper and live", "only recorded", "After 15:45", "moves itself to paper and asks you",
            "never goes back to live alone", "Exits are never delayed"))
            assertTrue(w in x.what, w)
        assertTrue(DriftReview.asked(x.ask!!))
        val f = WhatsNew.ENTRIES.first { it.id == "2026-10-10-findings-bus" }
        for (w in listOf("posts what it finds", "lean up", "logged beside every signal", "can only skip an entry", "record only", "exits always go"))
            assertTrue(w in f.what, w)
        assertEquals("BANKNIFTY", Findings.asked(f.ask!!))
    }

    @Test fun theBigMoveRecorderSaysItOnlyRecordsAndRespectsTheBatterySaver() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-09-big-move-recorder" }
        for (w in listOf("5 minutes before and after", "4.7 times", "Two ordinary minutes", "best bid and offer", "cash index",
            "9:15 to 15:40", "battery saver", "turns it off", "12 months", "export with the market data", "no strategy uses it"))
            assertTrue(w in x.what, w)
        assertTrue("Order flow" in x.where && "Big moves" in x.where, x.where)
        assertEquals(MoveEvents.Ask("BANKNIFTY", 10 * 60 + 32, 1), MoveEvents.asked(x.ask!!))
    }

    @Test fun orderFlowSaysItOnlySkipsAndItsQuestionIsAnswered() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-09-order-flow" }
        assertTrue("only skips" in x.what && "never places" in x.what && "PIN" in x.what && "may skip winners" in x.what, x.what)
        assertTrue("Order flow" in x.where, x.where)
        assertEquals("BANKNIFTY", OrderFlow.asked(x.ask!!))
    }

    @Test fun theAuctionReadingsAreSaidAsDisplayAndLogOnly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-09-order-flow-auction" }
        for (w in listOf("volume profile", "yesterday's value", "divergence", "footprint", "VWAP bands", "market profile", "liquidity heatmap",
            "time and sales", "gamma regime", "zero-gamma", "both dealer-sign conventions", "start off", "no strategy uses it"))
            assertTrue(w in x.what, w)
        assertTrue("Order flow" in x.where && "GEX" in x.where && "POC/VA" in x.where, x.where)
        assertEquals("BANKNIFTY", OrderFlow.asked(x.ask!!))
        assertEquals(Auction.Topic.PROFILE, Auction.topic(x.ask!!))
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

    @Test fun savedStrategiesMovedToResearchIsSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-09-strategies-to-research" }
        assertEquals(LocalDate.of(2026, 10, 9), x.date)
        assertTrue("Your saved strategies moved from Trade to Research → Strategies." in x.what, x.what)
        for (t in x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }) assertTrue(t.trim().split(' ').size <= 20, t)
        assertTrue("Research tab → Strategies" in x.where, x.where)
        assertEquals("strategy", x.to)
        assertNull(x.ask)
    }

    @Test fun fasterOrdersAndTheOrderSpeedCardAreSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-09-order-speed" }
        assertEquals(LocalDate.of(2026, 10, 9), x.date)
        for (w in listOf("the moment a new price arrives", "not every 15 seconds", "the 15-second check stays as a backup",
            "fill notice", "typical and worst time, paper and live", "relay is slow", "Mumbai relay",
            "Every safety rule, approval and paper-only bot is unchanged"))
            assertTrue(w in x.what, w)
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertEquals(6, sentences.size, x.what)
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        assertTrue("Settings → Zerodha → Order speed card" in x.where && "Copy diagnostics" in x.where, x.where)
        assertNull(x.ask)
        assertFalse(x.gold)
    }

    @Test fun theTwoNewPaperBotsAndTheParkingAreSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-09-two-paper-bots-and-parking" }
        assertEquals(LocalDate.of(2026, 10, 9), x.date)
        for (w in listOf("two new paper-only bots", "not proven", "off until you switch them on", "VIX divergence", "0.2%", "India VIX is up 2%",
            "10:30, 11:30, 12:30 and 13:30, first signal only", "Rs 70 and Rs 112 a day", "failed the luck checks", "US-night silver",
            "1 lot", "09:05", "Rs 1,189 a trade", "tripling year", "sells too", "3% disaster stop",
            "Liquidity 15+5 FINNIFTY (−₹8,035 over 8 paper trades) and ORB Sweep (−₹4,498 over 6) were switched off on your OK",
            "Switch them back on in Home → Strategies any time"))
            assertTrue(w in x.what, w)
        // At most 20 words a sentence, eight sentences.
        val sentences = x.what.split(Regex("[.!?](\\s|$)")).filter { it.isNotBlank() }
        assertEquals(8, sentences.size, x.what)
        for (t in sentences) assertTrue(t.trim().split(' ').size <= 20, t)
        assertTrue("Strategies card" in x.where && "VIX divergence row" in x.where && "Home → MCX card" in x.where &&
            "Switch FINNIFTY back on" in x.where && "Liquidity 15+5 row" in x.where, x.where)
        assertNull(x.ask)
        assertFalse(x.gold)
    }

    @Test fun theMcxPaperArmsAreSaidPlainlyWithTheirResearchNumbers() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-09-mcx-paper-arms" }
        assertEquals(LocalDate.of(2026, 10, 9), x.date)
        assertTrue("MCX" in x.title && "paper" in x.title, x.title)
        for (w in listOf("paper-only", "not proven", "off until you switch it on", "Natural gas evening breakout", "17:00-19:00", "22:00",
            "23:15", "Rs 41 a day", "Rs 83", "not significant", "Rs 40,000 drawdown", "Silver mini morning call", "two strikes out",
            "11-20 days", "4 hours", "Rs 454 a trade over 51 trades", "failed 2 of its 4 checks", "12-month trend", "Rs 8-10 lakh",
            "often ruined at Rs 1 lakh", "1 lot", "never send an order to Zerodha", "5 trading days before expiry, not 2"))
            assertTrue(w in x.what, w)
        // Home's MCX card (review R5 #9): not the Strategies card any more.
        assertTrue("Home → MCX card" in x.where && "Strategies card" !in x.where && "Commodities →" !in x.where, x.where)
        assertTrue("Home's MCX card has three paper-only MCX bots" in x.what && "Commodities page" !in x.what, x.what)
        assertNull(x.ask)
        assertFalse(x.gold)
    }

    @Test fun mcxIsSaidPlainly() {
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-09-mcx" }
        assertEquals(LocalDate.of(2026, 10, 9), x.date)
        assertTrue("MCX" in x.title, x.title)
        for (w in listOf("Crude oil", "natural gas", "gold", "silver", "near and next month futures", "23:30", "23:55 from 2 November",
            "spread and charges", "closed by 23:00 the day before expiry", "15:00 the day before expiry", "5 trading days before expiry", "no bot does"))
            assertTrue(w in x.what, w)
        assertTrue("Options tab → Commodities" in x.where && "MCX expiry exit" in x.where, x.where)
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
        val want = mapOf("how are my strategies improving" to "LabTalk", "how did you grow this week" to "SelfGrowth", "what experiments are running" to "AutoChange", "how is autopilot doing" to "AutoChange","what have you learned" to "AgentLessons", "what did the research say about the trade manager" to "TradeManager", "how are the manager's specialists doing" to "TradeManager", "how is the trade manager doing" to "TradeManager", "what are the bots saying about banknifty" to "Findings","how are my strategies doing vs backtest" to "ForwardWatch",
            "why did banknifty jump at 10:32" to "MoveEvents", "what's the volume profile on banknifty" to "OrderFlow", "what is the order flow on banknifty" to "OrderFlow","what did Solo do today" to "SoloDay", "why no liquidity trade today" to "LiquidityWhyNot",
            "what's the plan for tomorrow" to "TomorrowPlan", "how did liquidity do this week" to "LiquidityRecord",
            "explain my bots' trades today" to "BotTrades", "weekly review" to "WeeklyReview", "where are the liquidity levels" to "LiquidityMap",
            "how is Solo doing" to "Solo", "what's GIFT Nifty saying" to "MorningCues", "is a big move likely now" to "BigMoveRisk",
            "what is the stop loss for news trades" to "Glossary", "how many lots is liquidity trading" to "Honest",
            "what went wrong today" to "OrderReview", "how is the hero ticket doing" to "HeroTicket",
            "how are the passed arms doing" to "PassedArms", "what's on the watchlist" to "PassedArms", "what is a bull trap" to "Concepts",
            "has the trend turned on my trades" to "TrendWatch", "how is breadth donchian doing" to "PassedArms", "how is the donchian arm doing" to "PassedArms",
            "why was that candle green" to "CandleAgent", "how is FT3 doing" to "Ft3", "what did the time machine say" to "TimeMachine")
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
        // From 12 Oct's first entry (newer entries are checked on their own).
        val from = WhatsNew.ENTRIES.dropWhile { it.id != "2026-10-12-tick-zone" }
        val t = WhatsNew.answer(from)
        val lines = t.lines()
        // The newest six span 12 and 11 Oct: each line names its day, the head none.
        assertEquals("What's new in the app, newest first:", lines.first())
        assertEquals(1 + WhatsNew.SPOKEN + 1, lines.size, t)
        assertEquals("• Every second of order flow recorded (12 Oct): Settings → Data and harvest → Recordings.", lines[1])
        assertEquals("• Order review: every order studied daily (12 Oct): try \"what went wrong today\".", lines[2])
        assertEquals("• Majority Rule 14: every agent knows it (12 Oct): Research → Learn → Majority Rule 14; Home → Automation card → Brain.", lines[3])
        assertEquals("• Home puts trading first (11 Oct): Home → Automation card → tap a line.", lines[4])
        assertEquals("• The learning closes its loops, with brakes (11 Oct): Home → Automation card → Autopilot → Today's changes; Research → Learn.", lines[5])
        assertEquals("• Strategy lab: strategies improve and new ones are tried (11 Oct): try \"how are my strategies improving\".", lines[6])
        assertEquals("And ${from.size - 6} more, each with where to find it, in Settings → What's new.", lines.last())
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

    @Test fun theTelegramLoginFixIsTheNewestEntry() {
        // 16 Oct (Boss: "Telegram login button not working"): under the same day's passed arms' exits fix; what changed and where, nothing about trading.
        val x = WhatsNew.ENTRIES.first { it.id == "2026-10-16-telegram-login-fix" }
        assertEquals(29, WhatsNew.ENTRIES.indexOf(x), "under the passed arms' exits fix")
        for (w in listOf("inside the login window", "spinner", "20 seconds", "Try again", "starts by itself", "no longer closes"))
            assertTrue(w in x.what, w)
        assertTrue("Telegram login" in x.where, x.where)
        assertFalse(x.jarvisOnly); assertFalse(x.gold); assertNull(x.ask)
    }
}
