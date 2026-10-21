package com.optionslab.ira

import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandleAgentTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val seed = CandleBook.seed(File("../app/src/main/assets/knowledge/candle_seed.json").takeIf { it.isFile }?.readText())
    private fun ms(t: java.time.LocalDateTime) = t.toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()

    /** A day of 5-minute candles that ends with a clear big tail at 14:00 (posted fresh at 14:06). */
    private fun dayWithTail(): List<CandleRead.Bar> {
        val bars = CandleSynth.days(day.minusDays(3), 2, 5, seed = 9, step = 4.0).filter { !it.t.isAfter(day.atTime(13, 55)) }
        val c = bars.last().c
        // Lower wick 71%, upper 17% (so not a hammer), body 12%: R87's big tail.
        return bars + CandleSynth.bar(day.atTime(14, 0), c, c + 12.0, c - 30.0, c + 5.0)
    }

    @Test fun aBatchReadsSharesWithNoDirectionAndNeverSwaysAStrategy() {
        val a = CandleAgent(seed)
        val bars = dayWithTail()
        val now = day.atTime(14, 6)
        val step = a.study(listOf(CandleAgent.Series("NIFTY", 5, bars)), emptyList(), ms(now), now, marketOpen = true)
        assertTrue(step.explained > 0)
        assertTrue(step.posts.isNotEmpty(), "the fresh big tail is shared")
        assertTrue(step.posts.all { it.kind == Findings.Kind.CANDLE && it.direction == 0 && it.who == CandleAgent.WHO && it.instrument == "NIFTY" })
        // History is read, only the fresh candles (closed in the last two candle lengths) are posted.
        assertTrue(step.posts.size <= 2, "${step.posts.map { it.words }}")
        val p = step.posts.last()                                    // the 14:00 candle, read last
        assertTrue(p.words.startsWith("5m ") && "at ${CandleRead.px(dayWithTail().last().c)}" in p.words, p.words)
        assertTrue("support" in p.words, p.words)
        assertEquals(1.0, p.evidence["lean"])
        // On the bus, a candle read never counts in any consensus or reaction, whatever its colour.
        val many = (0 until 5).map { p.copy(seq = it.toLong(), evidence = p.evidence + ("lean" to -1.0)) }
        assertEquals(0, Findings.consensus(many, "NIFTY", ms(now)).bullish + Findings.consensus(many, "NIFTY", ms(now)).bearish)
        assertTrue(Findings.hits(many, "orb", "NIFTY", 1, ms(now)).isEmpty())
        assertEquals(CandleAgent.Health.OK, a.status().health)
        assertTrue(a.exchanges().any { it.way == CandleAgent.Way.SENT })
    }

    @Test fun theExplanationSaysWhyGreenWhereTheLevelsAreAndTheResearchHonestly() {
        val a = CandleAgent(seed)
        val bars = dayWithTail()
        val now = day.atTime(14, 6)
        a.study(listOf(CandleAgent.Series("NIFTY", 5, bars)), emptyList(), ms(now), now, true)
        val ex = a.latest().first()
        val text = ex.lines.joinToString("\n")
        assertTrue(ex.read.bar.t == day.atTime(14, 0), ex.title)
        assertTrue("Green because" in text && "buyers won" in text && "sellers pushed it" in text, text)
        assertTrue("nearest support" in text.lowercase(), text)
        assertTrue("not a stronger level" in text, text)
        if (!seed.empty) assertTrue("History (R87, NIFTY 5m" in text && "no edge" in text, text)
        assertTrue("My own count" in text && "too few" in text, text)
        assertTrue(text.endsWith("nothing here trades."), text)
        assertTrue("an index has no volume" in text)
    }

    @Test fun itHearsTheOthersAndKeepsTheirTradesOffALockedPhone() {
        val a = CandleAgent(seed)
        val now = day.atTime(14, 6)
        val t = ms(now)
        val brain = Findings.Finding("the market brain", Findings.Kind.OTHER, Findings.ALL, atMs = t - 60_000, words = "busy day", seq = 1)
        val gamma = Findings.Finding("gamma", Findings.Kind.OTHER, "NIFTY", atMs = t - 60_000, words = "short gamma under 24,000", seq = 2)
        val stop = Findings.Finding("ORB", Findings.Kind.STOP_HIT, "NIFTY", atMs = t - 30_000, words = "my stop hit", seq = 3)
        val mine = Findings.Finding(CandleAgent.WHO, Findings.Kind.CANDLE, "NIFTY", atMs = t, words = "own", seq = 4)
        val old = Findings.Finding("news", Findings.Kind.NEWS_WINDOW, "NIFTY", atMs = t - 2 * CandleAgent.HEARD_MS, words = "old", seq = 5)
        a.study(listOf(CandleAgent.Series("NIFTY", 5, dayWithTail())), listOf(brain, gamma, stop, mine, old), t, now, true)
        // The same findings again (the bus keeps them standing): heard once.
        a.hear(listOf(brain, gamma, stop), t)
        val heard = a.heard("NIFTY", t, locked = false).map { it.words }
        assertEquals(setOf("busy day", "short gamma under 24,000", "my stop hit"), heard.toSet())
        assertFalse("my stop hit" in a.heard("NIFTY", t, locked = true).map { it.words })
        assertEquals(4, a.exchanges().count { it.way == CandleAgent.Way.HEARD }, "the old one is logged once and then dropped")
        val open = a.answer(CandleAgent.Question.MEANING, "NIFTY", locked = false, nowMs = t)
        val locked = a.answer(CandleAgent.Question.MEANING, "NIFTY", locked = true, nowMs = t)
        assertTrue("Around it the other agents said" in open && "my stop hit" in open, open)
        assertFalse("other agents" in locked || "stop hit" in locked, locked)
    }

    @Test fun otherAgentsAskAndItAnswersFromMemory() {
        val a = CandleAgent(seed)
        val now = day.atTime(14, 6)
        a.study(listOf(CandleAgent.Series("NIFTY", 5, dayWithTail())), emptyList(), ms(now), now, true)
        val lv = a.ask("the trade manager", CandleAgent.Ask.Levels("nifty"), ms(now))
        assertTrue(lv.support != null && lv.support!!.price < lv.read!!.bar.c)
        assertTrue("support" in lv.words && "not a wall" in lv.words, lv.words)
        val latest = a.ask("Jarvis", CandleAgent.Ask.Latest("NIFTY", 5), ms(now))
        assertEquals(day.atTime(14, 0), latest.read!!.bar.t)
        val odds = a.ask("the strategy lab", CandleAgent.Ask.Odds("NIFTY", 15, "big_lower_wick"), ms(now))
        if (!seed.empty) assertEquals(949, odds.research!!.n)
        assertTrue("No notable" in a.ask("x", CandleAgent.Ask.Latest("SENSEX"), ms(now)).words)
        assertTrue("no levels" in a.ask("x", CandleAgent.Ask.Levels("SENSEX"), ms(now)).words)
        val log = a.exchanges()
        assertEquals(5, log.count { it.way == CandleAgent.Way.ASKED }); assertEquals(5, log.count { it.way == CandleAgent.Way.ANSWERED })
        assertTrue(log.any { it.who == "the trade manager" })
    }

    @Test fun jarvisAnswersWhyGreenWhatItMeansAndWhereSupportIs() {
        val a = CandleAgent(seed)
        assertTrue("has not read" in a.answer(CandleAgent.Question.WHY_COLOUR, null, false, 0))
        val now = day.atTime(14, 6)
        a.study(listOf(CandleAgent.Series("NIFTY", 5, dayWithTail())), emptyList(), ms(now), now, true)
        val why = a.answer(CandleAgent.Question.WHY_COLOUR, "NIFTY", false, ms(now))
        assertTrue("Green because" in why && "did not tell the next candles' direction" in why, why)
        assertTrue("Support" in a.answer(CandleAgent.Question.SUPPORT, null, false, ms(now)).replaceFirst("support", "Support"))
        assertTrue("no notable candle on SENSEX" in a.answer(CandleAgent.Question.MEANING, "sensex", false, ms(now)))
    }

    @Test fun outcomesAreCountedOnceEvenAfterARestart() {
        val bars = CandleSynth.days(day.minusDays(3), 2, 5, seed = 2)
        val now = day.atTime(16, 0)
        val a = CandleAgent(seed)
        val s1 = a.study(listOf(CandleAgent.Series("NIFTY", 5, bars, sessionOver = true)), emptyList(), ms(now), now, marketOpen = false)
        assertTrue(s1.counted > 100 && s1.changed)
        assertTrue(s1.posts.isEmpty(), "a closed market's history is never announced")
        val again = a.study(listOf(CandleAgent.Series("NIFTY", 5, bars, sessionOver = true)), emptyList(), ms(now), now, false)
        assertEquals(0, again.counted); assertFalse(again.changed)
        val restarted = CandleAgent(seed, CandleBook.decode(a.encodeStats()))
        assertEquals(0, restarted.study(listOf(CandleAgent.Series("NIFTY", 5, bars, sessionOver = true)), emptyList(), ms(now), now, false).counted)
        assertEquals(a.encodeStats(), restarted.encodeStats())
    }

    @Test fun aFirstBatchOfDaysOfOneMinuteCandlesCountsAllOfItsHistory() {
        val bars = CandleSynth.days(day.minusDays(3), 2, 1, seed = 6, step = 2.0)        // 750 one-minute candles, more than the pending cap
        val now = day.atTime(16, 0)
        val a = CandleAgent(seed)
        val s = a.study(listOf(CandleAgent.Series("NIFTY", 1, bars, sessionOver = true)), emptyList(), ms(now), now, false)
        // Every candle with ATR (all but the first 14) and a settled outcome: the first day's last ones end with their session.
        assertEquals(bars.size - CandleRead.ATR_N, s.counted)
    }

    @Test fun pendingReadsSettleAsCandlesArriveInTheirOrder() {
        val all = CandleSynth.days(day, 1, 5, seed = 5)
        val a = CandleAgent(seed)
        var counted = 0
        for (k in listOf(20, 40, 60, all.size)) {
            val now = all[k - 1].t.plusMinutes(6)
            counted += a.study(listOf(CandleAgent.Series("NIFTY", 5, all.subList(0, k), sessionOver = k == all.size)), emptyList(), ms(now), now, k < all.size).counted
        }
        val b = CandleAgent(seed)
        val now = all.last().t.plusMinutes(6)
        val once = b.study(listOf(CandleAgent.Series("NIFTY", 5, all, sessionOver = true)), emptyList(), ms(now), now, false).counted
        assertEquals(once, counted)
        assertEquals(b.encodeStats(), a.encodeStats())
    }

    @Test fun theTwentyFourSevenStudyCountsOlderHistoryOnceAndAnnouncesNothing() {
        val live = CandleSynth.days(day, 1, 5, seed = 12)
        val older = CandleSynth.days(day.minusDays(10), 5, 5, seed = 13)            // the five sessions before
        val a = CandleAgent(seed)
        val now = day.atTime(16, 0)
        val first = a.study(listOf(CandleAgent.Series("NIFTY", 5, live, sessionOver = true)), emptyList(), ms(now), now, false).counted
        assertTrue(first > 0)
        val deep = a.study(listOf(CandleAgent.Series("NIFTY", 5, older + live, sessionOver = true, deep = true)), emptyList(), ms(now), now, false)
        assertTrue(deep.counted > 300, "${deep.counted}")
        assertTrue(deep.posts.isEmpty() && deep.explained == 0)
        // Again: nothing new to count, live or deep.
        assertEquals(0, a.study(listOf(CandleAgent.Series("NIFTY", 5, older + live, sessionOver = true, deep = true)), emptyList(), ms(now), now, false).counted)
        assertEquals(0, a.study(listOf(CandleAgent.Series("NIFTY", 5, live, sessionOver = true)), emptyList(), ms(now), now, false).counted)
    }

    @Test fun theBigPlayersHandInACandleIsSaidAndLearnedButNotOnALockedPhone() {
        val a = CandleAgent(seed)
        val bars = dayWithTail()
        val now = day.atTime(14, 6)
        val sweep = Findings.Finding("Liquidity 15+5", Findings.Kind.SWEEP, "NIFTY", atMs = ms(day.atTime(14, 2)), words = "sweep taken under the day low", seq = 7)
        a.study(listOf(CandleAgent.Series("NIFTY", 5, bars)), listOf(sweep), ms(now), now, true)
        val r = a.latest().first().read
        assertTrue("bp_sweep" in r.context.tags, "${r.context.tags}")
        val open = a.answer(CandleAgent.Question.MEANING, "NIFTY", false, ms(now))
        assertTrue("Big players:" in open && "sweep taken under the day low" in open && "context, not a signal" in open, open)
        assertFalse("sweep taken" in a.answer(CandleAgent.Question.MEANING, "NIFTY", true, ms(now)))
        assertTrue(a.historyLines(r).isEmpty() || a.historyLines(r).first().startsWith("History behind it: "))
    }

    @Test fun staleCandlesAreReadButNeverPosted() {
        val a = CandleAgent(seed)
        val bars = dayWithTail()
        val now = day.atTime(15, 0)                                  // the newest candle is an hour old while the market is open
        val s = a.study(listOf(CandleAgent.Series("NIFTY", 5, bars)), emptyList(), ms(now), now, marketOpen = true)
        assertTrue(s.posts.isEmpty())
        assertEquals(CandleAgent.Health.STALE, a.status().health)
    }

    @Test fun oneMinuteCandlesAreReadButOnlyFiveMinuteAndLongerArePostedOneASeries() {
        val a = CandleAgent(seed)
        val ones = CandleSynth.days(day, 1, 1, seed = 21, step = 3.0).filter { it.t.isBefore(day.atTime(14, 0)) }
        val now = day.atTime(14, 1)
        val s = a.study(listOf(CandleAgent.Series("NIFTY", 1, ones), CandleAgent.Series("NIFTY", 5, dayWithTail())), emptyList(), ms(now.plusMinutes(5)), now.plusMinutes(5), true)
        assertTrue(s.posts.size <= 1, "${s.posts.map { it.words }}")
        assertTrue(s.posts.all { it.words.startsWith("5m ") })
    }

    @Test fun anIndexIsNeverStaleWhileItsOwnMarketIsShut() {
        val a = CandleAgent(seed)
        val now = day.atTime(19, 0)                                          // MCX's evening: NSE is shut
        a.study(listOf(CandleAgent.Series("NIFTY", 5, dayWithTail(), open = false)), emptyList(), ms(now), now, marketOpen = true)
        assertEquals(CandleAgent.Health.OK, a.status().health)
    }

    @Test fun noDataOrAHolidayIsSaidPlainly() {
        val a = CandleAgent(seed)
        val now = day.atTime(11, 0)
        val s = a.study(listOf(CandleAgent.Series("NIFTY", 5, emptyList()), CandleAgent.Series("CRUDEOIL", 5, emptyList())), emptyList(), ms(now), now, false)
        assertEquals(0, s.posts.size + s.counted + s.explained)
        assertEquals(CandleAgent.Health.NO_DATA, a.status().health)
        assertTrue("no candles to read" in a.status().words())
    }

    @Test fun anExpiryDayAndMcxAreReadWithTheirOwnContext() {
        val a = CandleAgent(seed)
        val now = day.atTime(14, 6)
        a.study(listOf(CandleAgent.Series("NIFTY", 5, dayWithTail(), around = { CandleRead.Around(expiry = true, gamma = "long") })), emptyList(), ms(now), now, true)
        val text = a.latest().first().lines.joinToString(" ")
        assertTrue("of an expiry day" in text && "dealers long gamma" in text, text)
        val mcx = CandleSynth.days(day, 1, 15, start = 5_600.0, step = 6.0, vol = true)
        val b = CandleAgent(seed)
        val t = mcx.last().t.plusMinutes(16)
        b.study(listOf(CandleAgent.Series("CRUDEOIL", 15, mcx, sessionOpenMin = 9 * 60)), emptyList(), ms(t), t, true)
        if (b.latest().isNotEmpty() && !seed.empty) assertTrue("R85" in b.latest().first().lines.joinToString(" "))
    }

    @Test fun itsLessonsReachJarvisAtBuildingAtMostAndNeverPropose() {
        val s = CandleBook.Stats()
        s.cells["NIFTY|15|hammer|all"] = CandleBook.Cell(n5 = 300, up5 = 240)
        s.cells["NIFTY|15|*G|all"] = CandleBook.Cell(n5 = 3_000, up5 = 1_500)
        s.cells["NIFTY|15|*R|all"] = CandleBook.Cell(n5 = 3_000, up5 = 1_500)
        s.cells["NIFTY|15|big_lower_wick|all"] = CandleBook.Cell(n5 = 300, up5 = 150)
        val a = CandleAgent(seed, s)
        var book = AgentLessons.Book()
        var proposals = 0
        for (k in 0 until 40) {
            val d = day.plusDays(k.toLong())
            val p = AgentLessons.pass(book, AgentLessons.Inputs(d, candles = a.lessons(d)))
            book = p.book; proposals += p.proposals.size
        }
        val mine = book.lessons.filter { it.topic == AgentLessons.Topic.CANDLES }
        if (seed.empty) return
        assertEquals(2, mine.size)
        assertTrue(mine.all { it.confidence != AgentLessons.Confidence.STRONG })
        assertEquals(0, proposals)
        val said = mine.map { AgentLessons.said(it) }
        assertTrue(said.any { "differs from the research" in it }, "$said")
        assertTrue(said.any { "behaves as the research says" in it }, "$said")
        // ... and the shared knowledge carries them under "candles", never as a passed test.
        val snap = SharedKnowledge.build(null as SharedKnowledge.Research?, book, FindingsTally(), day, "")
        val items = SharedKnowledge.about(snap, topic = "candles")
        assertEquals(2, items.size)
        assertTrue(items.all { it.gate == SharedKnowledge.Gate.NONE })
        assertTrue(a.notes().single().contains("A note for Boss"))
        assertTrue(a.growth() in 0..100)
    }

    @Test fun aBatchIsCheapAndTheMemoryIsBounded() {
        val a = CandleAgent(seed)
        val bars1 = CandleSynth.days(day.minusDays(4), 3, 1, seed = 8, step = 2.0)
        val now = bars1.last().t.plusMinutes(2)
        val t0 = System.nanoTime()
        a.study(listOf(CandleAgent.Series("NIFTY", 1, bars1), CandleAgent.Series("NIFTY", 5, CandleSynth.days(day.minusDays(4), 3, 5)),
            CandleAgent.Series("NIFTY", 15, CandleSynth.days(day.minusDays(4), 3, 15))), emptyList(), ms(now), now, true)
        val sec = (System.nanoTime() - t0) / 1e9
        assertTrue(sec < 10.0, "a first batch of three days took $sec s")
        assertTrue(a.latest().size <= CandleAgent.KEEP_EXPLAINED && a.exchanges().size <= CandleAgent.KEEP_LOG)
        assertTrue(a.status().cells <= CandleBook.MAX_CELLS)
        // A later batch with nothing new reads nothing.
        val t1 = System.nanoTime()
        a.study(listOf(CandleAgent.Series("NIFTY", 1, bars1)), emptyList(), ms(now), now, true)
        assertTrue((System.nanoTime() - t1) / 1e9 < 2.0)
    }

    @Test fun anErrorIsKeptByItsNameOnly() {
        val a = CandleAgent(seed)
        a.failed(IllegalStateException("token=secret"))
        assertTrue("IllegalStateException" in a.status().words() && "secret" !in a.status().words())
    }
}
