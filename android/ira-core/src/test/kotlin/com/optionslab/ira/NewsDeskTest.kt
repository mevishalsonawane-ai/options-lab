package com.optionslab.ira

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsDeskTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 5)   // a Monday
    private fun at(h: Int, m: Int, d: LocalDate = today): Instant = d.atTime(h, m).atZone(ist).toInstant()
    private fun h(title: String, source: String, t: Instant?, also: List<String> = emptyList()) =
        Headline(title, "https://example.com/${title.length}", source, t, News.tone(title), News.tag(title), also)
    private val now = at(13, 0)

    private val news = listOf(
        h("RBI keeps repo rate unchanged at 5.5%, retains neutral stance", "Economic Times", at(10, 5)),
        h("RBI keeps repo rate unchanged at 5.5% - Mint", "Google News", at(10, 9)),
        h("Reserve Bank keeps repo rate unchanged, stance neutral", "Moneycontrol", at(10, 20), also = listOf("Business Standard")),
        h("Infosys shares jump after strong Q2 results", "Moneycontrol", at(11, 40)),
        h("Infosys Q2 results: shares jump on strong guidance", "Economic Times", at(12, 10)),
        h("Tata Motors sales rise 12% in September", "Mint", at(9, 30)),
        h("HDFC Bank, ICICI Bank lead gains in banking stocks", "Business Standard", at(12, 30)),
        h("Gold steadies as dollar eases", "Reuters", at(8, 0)),
        h("Sensex ends lower on profit booking", "Mint", at(15, 40, today.minusDays(3))),
    )

    @Test fun tagsFromFixedWordLists() {
        assertEquals(listOf(NewsDesk.Tag.RBI), NewsDesk.tags("RBI keeps repo rate unchanged"))
        assertTrue(NewsDesk.Tag.BANKS !in NewsDesk.tags("Reserve Bank of India holds rates"), "the Reserve Bank is not a bank stock")
        assertTrue(NewsDesk.Tag.BANKS !in NewsDesk.tags("World Bank raises India growth forecast"))
        assertTrue(NewsDesk.Tag.BANKS in NewsDesk.tags("HDFC Bank, ICICI Bank lead gains"))
        assertEquals(listOf(NewsDesk.Tag.BANKNIFTY), NewsDesk.tags("Bank Nifty slips 1%"), "Bank Nifty is the index, not Nifty or a bank stock")
        val it = NewsDesk.tags("IT stocks drag Nifty; Infosys, TCS fall")
        assertTrue(NewsDesk.Tag.IT in it && NewsDesk.Tag.NIFTY in it)
        assertTrue(NewsDesk.Tag.IT !in NewsDesk.tags("Why it matters for markets"), "the word 'it' is not the sector")
        assertTrue(NewsDesk.Tag.AUTO in NewsDesk.tags("Maruti Suzuki car sales rise"))
        assertTrue(NewsDesk.Tag.PHARMA in NewsDesk.tags("Sun Pharma gets USFDA nod"))
        assertTrue(NewsDesk.Tag.FED in NewsDesk.tags("Fed holds rates, Powell signals patience"))
        assertTrue(NewsDesk.Tag.BUDGET in NewsDesk.tags("Budget 2027: fiscal deficit target kept"))
        assertTrue(NewsDesk.Tag.INFLATION in NewsDesk.tags("Retail inflation eases to 2.1% in September"))
        assertTrue(NewsDesk.Tag.EARNINGS in NewsDesk.tags("TCS Q2 results beat estimates"))
        assertTrue(NewsDesk.Tag.EARNINGS !in NewsDesk.tags("Sensex ends lower on profit booking"))
        assertTrue(NewsDesk.Tag.EARNINGS !in NewsDesk.tags("Bihar election results today"))
        assertTrue(NewsDesk.Tag.FLOWS in NewsDesk.tags("FPIs pull out Rs 8,000 crore"))
        assertTrue(NewsDesk.tags("Monsoon arrives in Kerala").isEmpty())
    }

    @Test fun theSameEventFromDifferentOutletsIsOneStory() {
        val s = NewsDesk.stories(news)
        val rbi = s.single { NewsDesk.Tag.RBI in it.tags }
        assertEquals(3, rbi.headlines.size)
        assertEquals(listOf("Economic Times", "Google News", "Moneycontrol", "Business Standard"), rbi.sources)
        assertEquals(4, rbi.sourceCount)
        assertEquals(at(10, 5), rbi.first); assertEquals(at(10, 20), rbi.latest)
        assertEquals("RBI keeps repo rate unchanged at 5.5%, retains neutral stance", rbi.title)
        val infy = s.single { NewsDesk.Tag.IT in it.tags }
        assertEquals(2, infy.sourceCount)
        assertTrue(NewsDesk.Tag.EARNINGS in infy.tags)
        // different events stay apart
        assertEquals(1, s.count { NewsDesk.Tag.AUTO in it.tags }); assertEquals(1, s.count { NewsDesk.Tag.BANKS in it.tags })
        assertEquals(6, s.size)
        assertTrue(s.zipWithNext().all { (a, b) -> (a.latest ?: Instant.EPOCH) >= (b.latest ?: Instant.EPOCH) })
        // the same words a day apart are two stories
        val again = NewsDesk.stories(listOf(h("Nifty hits record high", "A", at(10, 0)), h("Nifty hits record high", "B", at(10, 0, today.plusDays(1)))))
        assertEquals(2, again.size)
        assertFalse(NewsDesk.same(h("Nifty falls 1%", "A", at(10, 0)), h("Gold falls 1%", "B", at(10, 5))))
    }

    @Test fun mergeKeepsTheOtherSources() {
        val a = h("Sensex jumps 800 points", "Mint", at(10, 0)); val b = h("Sensex jumps 800 points", "PTI", at(9, 58))
        val m = News.merge(listOf(listOf(a), listOf(b)))
        assertEquals(1, m.size); assertEquals("Mint", m[0].source); assertEquals(listOf("PTI"), m[0].also)
        assertEquals(2, NewsDesk.stories(m).single().sourceCount)
        val one = News.merge(listOf(listOf(a)))
        assertEquals(a, one.single())
    }

    @Test fun questions() {
        for (q in listOf("What's the main news today?", "what is the top news", "Jarvis, biggest headlines today?", "today's headlines", "what's making news today",
            "what's the news today?", "Key stories today Boss"))
            assertEquals(NewsDesk.Ask.Main, NewsDesk.asked(q), q)
        assertEquals(NewsDesk.Ask.On(NewsDesk.Tag.BANKS), NewsDesk.asked("Any news on banks?"))
        assertEquals(NewsDesk.Ask.On(NewsDesk.Tag.BANKS), NewsDesk.asked("latest banking news"))
        assertEquals(NewsDesk.Ask.On(NewsDesk.Tag.IT), NewsDesk.asked("any news on IT?"))
        assertEquals(NewsDesk.Ask.On(NewsDesk.Tag.IT), NewsDesk.asked("headlines about tech stocks"))
        assertEquals(NewsDesk.Ask.On(NewsDesk.Tag.RBI), NewsDesk.asked("RBI news?"))
        assertEquals(NewsDesk.Ask.On(NewsDesk.Tag.FED), NewsDesk.asked("anything from the Fed in the news"))
        assertEquals(NewsDesk.Ask.On(NewsDesk.Tag.EARNINGS), NewsDesk.asked("any news on earnings today"))
        assertEquals(NewsDesk.Ask.On(NewsDesk.Tag.PHARMA), NewsDesk.asked("pharma news"))
        for (q in listOf("What news moved the market today?", "which news drove Nifty", "did news move the market today?", "news behind today's fall",
            "is news moving the market"))
            assertEquals(NewsDesk.Ask.Moved, NewsDesk.asked(q), q)
        // not these: the plain latest news and an index's news (Ira's), "it" the word, advice, forecasts, the app's feeds
        for (q in listOf("any news?", "any news on Nifty?", "news on BankNifty", "any news on it?", "should I buy banks on this news?",
            "will the RBI news move the market tomorrow?", "which news sources do you use?", "turn off news alerts", "what are the banks doing",
            "explain this move", "any news behind the move", "what happened at 11:20", "what's the structure today?"))
            assertNull(NewsDesk.asked(q), q)
    }

    @Test fun routedApartFromTheOtherAnswers() {
        val mine = listOf("What's the main news today?", "Any news on banks?", "What news moved the market today?", "any news on IT?", "RBI news?")
        for (q in mine) {
            assertNull(SharpMove.asked(q), q)
            assertNull(Structure.asked(q), q)
            assertNull(ChainIntel.asked(q), q)
            assertNull(AlertSense.asked(q), q)
            assertFalse(Airtime.asked(q), q)
            assertFalse(DataAge.asked(q), q)
            assertNull(Honest.asked(q), q)
            assertFalse(TradeCase.asked(q), q)
            assertNull(Scenarios.asked(q), q)
            assertFalse(DaySummary.asked(q), q)
            assertFalse(Outlook.asked(q), q)
            assertNull(MarketMemory.asked(q), q)
            assertFalse(Sources.asked(q), q)
            assertFalse(DayJournal.asked(q), q)
            assertNull(Thinking.asked(q), q)
            assertFalse(SelfWhy.asked(q), q)
            Ask.parse(q).let { p -> assertTrue(p.order == null && p.command == null, q) }   // the app routes these only then
        }
        // SharpMove's own questions stay its
        for (q in listOf("explain this move", "any news behind the move", "what happened at 11:20"))
            assertTrue(SharpMove.asked(q) != null && NewsDesk.asked(q) == null, q)
    }

    @Test fun theMainNewsToday() {
        val said = NewsDesk.answer(NewsDesk.Ask.Main, news, now, ist)
        assertTrue(said.startsWith("Today's main stories, Boss"), said)
        assertTrue(said.contains("1. \"RBI keeps repo rate unchanged at 5.5%, retains neutral stance\" - 4 sources (Economic Times, Google News, Moneycontrol, Business Standard), first at 10:05, latest 10:20; touches RBI."), said)
        assertTrue(said.contains("2. \"Infosys shares jump after strong Q2 results\" - 2 sources (Moneycontrol, Economic Times), first at 11:40, latest 12:10"), said)
        assertTrue(said.contains("3. \"HDFC Bank, ICICI Bank lead gains in banking stocks\" - 1 source (Business Standard), at 12:30; touches banks."), said)
        assertFalse(said.contains("profit booking"), "an old day is not today's news")
        assertFalse(Regex("(?i)\\b(buy|sell|should|will)\\b").containsMatchIn(said), said)
        val old = NewsDesk.answer(NewsDesk.Ask.Main, news.takeLast(1), now, ist)
        assertTrue(old.startsWith("No headline on the phone is from today yet") && old.contains("2 Oct 15:40"), old)
        assertTrue(NewsDesk.answer(NewsDesk.Ask.Main, emptyList(), now, ist).startsWith("I have no headlines"))
    }

    @Test fun newsOnASector() {
        val banks = NewsDesk.answer(NewsDesk.Ask.On(NewsDesk.Tag.BANKS), news, now, ist)
        assertTrue(banks.startsWith("On banks, Boss, 1 story in the last 24 hours"), banks)
        assertTrue(banks.contains("HDFC Bank, ICICI Bank lead gains") && !banks.contains("RBI"), banks)
        val it = NewsDesk.answer(NewsDesk.Ask.On(NewsDesk.Tag.IT), news, now, ist)
        assertTrue(it.contains("2 sources (Moneycontrol, Economic Times)"), it)
        val pharma = NewsDesk.answer(NewsDesk.Ask.On(NewsDesk.Tag.PHARMA), news, now, ist)
        assertTrue(pharma.startsWith("Nothing on pharma in the 8 headlines from the last 24 hours"), pharma)
    }

    /** A quiet Nifty day from 09:15 with a 0.8% jump from 11:00 to 11:06, and a few quiet days before it. */
    private fun bars(jump: Boolean = true): List<Candle> {
        val out = ArrayList<Candle>()
        for (d in 1..3) for (i in 0 until 375) {
            val px = 25_000.0 + (i % 7) * 2.0
            out += Candle(today.minusDays(7L - d).atTime(9, 15).plusMinutes(i.toLong()), px, px + 1, px - 1, px)
        }
        var px = 25_000.0
        for (i in 0 until 240) {
            val t = today.atTime(9, 15).plusMinutes(i.toLong())
            val o = px
            px = if (jump && t.hour == 11 && t.minute in 1..6) px * 1.0014 else 25_000.0 + (i % 7) * 2.0 + (if (jump && !t.isBefore(today.atTime(11, 1))) 210.0 else 0.0)
            out += Candle(t, o, maxOf(o, px) + 1, minOf(o, px) - 1, px)
        }
        return out
    }

    @Test fun newsThatMovedTheMarketIsOnlyTiming() {
        val b = bars()
        val moves = NewsDesk.sharpMoves(Market.NIFTY, b)
        assertEquals(1, moves.size, moves.toString())
        val mv = moves.single()
        assertTrue(mv.up && !mv.from.isAfter(today.atTime(11, 0)) && !mv.to.isBefore(today.atTime(11, 6)), mv.toString())
        val withNews = news + h("Government cuts GST on autos", "PTI", at(10, 58))
        val said = NewsDesk.answer(NewsDesk.Ask.Moved, withNews, now, ist, Market.NIFTY, mapOf(Market.NIFTY to b))
        assertTrue(said.startsWith("I can only line the news up with the moves, Boss."), said)
        assertTrue(said.contains("10:58 \"Government cuts GST on autos\" (PTI)"), said)
        assertFalse(said.contains("RBI keeps"), "news long before the move is not around it")
        assertTrue(said.endsWith(NewsDesk.TIMING), said)
        assertFalse(Regex("(?i)\\b(because|due to|caused by|led to|triggered)\\b").containsMatchIn(said), said)
        val quiet = NewsDesk.answer(NewsDesk.Ask.Moved, news, now, ist, Market.NIFTY, mapOf(Market.NIFTY to bars(jump = false)))
        assertTrue(quiet.startsWith("No sharp move in Nifty on its last session") && quiet.endsWith(NewsDesk.TIMING), quiet)
        val none = NewsDesk.answer(NewsDesk.Ask.Moved, news, now, ist, Market.NIFTY, emptyMap())
        assertTrue(none.contains("no Nifty candles") && none.endsWith(NewsDesk.TIMING), none)
        assertEquals(Market.BANKNIFTY, NewsDesk.market(listOf(Market.GOLD, Market.BANKNIFTY)))
        assertEquals(Market.NIFTY, NewsDesk.market(emptyList()))
    }
}
