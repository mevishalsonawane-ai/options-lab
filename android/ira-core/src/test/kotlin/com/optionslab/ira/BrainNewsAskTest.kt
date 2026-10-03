package com.optionslab.ira

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrainTest {
    @Test fun aSnapshotOfAnIndexDay() {
        val bars = Fixtures.indexDays(25, drift = 0.3, seed = 11)
        val h = History(Market.NIFTY, bars)
        val s = assertNotNull(Brain.read(h))
        val today = bars.last().t.toLocalDate()
        val todays = bars.filter { it.t.toLocalDate() == today }
        assertEquals(bars.last().c, s.price)
        assertEquals(todays.maxOf { it.h }, s.high); assertEquals(todays.minOf { it.l }, s.low); assertEquals(todays.first().o, s.open)
        val prev = h.days.dropLast(1).last()
        assertEquals(h.day(prev).last().c, s.prevClose)
        assertEquals(s.price - s.prevClose!!, s.change!!, 1e-9)
        assertEquals(todays.take(15).maxOf { it.h }, s.openingHigh)
        assertEquals(2, s.trends.size)
        assertTrue(s.trend(15)!!.up, "a rising walk reads up on 15m")
        assertNotNull(s.mood); assertNotNull(s.rangeRatio)
        assertTrue(s.above.all { it.price > s.price }); assertTrue(s.below.all { it.price < s.price })
        assertTrue(s.above.zipWithNext().all { (a, b) -> a.price <= b.price })
        assertFalse(s.trading)                                  // 15:29 + 1 minute = the close
        assertTrue(s.patterns.zipWithNext().all { (a, b) -> !a.at.isBefore(b.at) })
    }

    @Test fun littleDataStillAnswers() {
        assertNull(Brain.read(History(Market.NIFTY, emptyList())))
        val one = Fixtures.indexDays(1).take(10)
        val s = assertNotNull(Brain.read(History(Market.NIFTY, one)))
        assertTrue(s.trends.isEmpty()); assertNull(s.mood); assertNull(s.prevClose); assertNull(s.change)
        assertTrue(s.trading)
    }

    @Test fun timesAreWrittenForEachMarket() {
        val t = LocalDate.of(2026, 9, 29).atTime(11, 15)
        assertEquals("11:15", Brain.when_(t, Market.NIFTY, t.toLocalDate()))
        assertEquals("11:15 UTC (16:45 IST)", Brain.when_(t, Market.GOLD, t.toLocalDate()))
        assertEquals("29 Sep 11:15", Brain.when_(t, Market.NIFTY))
    }
}

class NewsTest {
    private val rss = """<?xml version="1.0"?><rss><channel><title>Feed</title>
        <item><title><![CDATA[Sensex, Nifty rally to record high as banks surge]]></title><link>https://example.com/a</link><pubDate>Fri, 02 Oct 2026 09:30:00 +0530</pubDate></item>
        <item><title>Gold slips as dollar rises &amp; yields climb</title><link>https://example.com/b</link><pubDate>Fri, 02 Oct 2026 08:00:00 GMT</pubDate></item>
        <item><title></title><link>https://example.com/empty</link></item>
        <item><title>Markets are not weak, say analysts</title><link>https://example.com/c</link><pubDate>garbage</pubDate></item>
        </channel></rss>"""
    private val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title type="html">Bank Nifty slumps on RBI fears</title>
        <link href="https://example.com/d"/><updated>2026-10-02T04:00:00Z</updated></entry></feed>"""

    @Test fun readsRssAndAtom() {
        val a = News.parse(rss, "Feed A")
        assertEquals(3, a.size, "the empty title is skipped")
        assertEquals("Sensex, Nifty rally to record high as banks surge", a[0].title)
        assertEquals(Instant.parse("2026-10-02T04:00:00Z"), a[0].at)
        assertEquals("Gold slips as dollar rises & yields climb", a[1].title)
        assertNull(a[2].at)
        val b = News.parse(atom, "Feed B")
        assertEquals("https://example.com/d", b.single().link)
        assertEquals(Instant.parse("2026-10-02T04:00:00Z"), b.single().at)
        assertTrue(News.parse("not xml at all", "x").isEmpty())
        assertEquals("Tom's & \"Jerry\" é", News.parse("<item><title>Tom&#39;s &amp; &quot;Jerry&quot; &#233;</title></item>", "x").single().title)
    }

    @Test fun toneAndTags() {
        val a = News.parse(rss, "Feed A")
        assertEquals("positive", a[0].toneWord)
        assertTrue(Market.BANKNIFTY in a[0].markets && Market.NIFTY in a[0].markets && Market.SENSEX in a[0].markets)
        assertTrue(News.tone("Markets are not weak, say analysts") > 0, "negation flips")
        assertTrue(News.tone("Nifty falls as war fears grow") < 0)
        assertTrue(News.tone("Gold gains as war fears grow", Market.GOLD) > 0, "fear lifts gold")
        assertTrue(News.tone("Gold slips as dollar rises", Market.GOLD) < 0)
        assertEquals("neutral", Headline("x", "", "s", null, 0.1, emptyList()).toneWord)
        assertEquals("negative", Headline("x", "", "s", null, -0.5, emptyList()).toneWord)
        assertEquals(listOf(Market.GOLD), News.tag("Bullion steady"))
        assertTrue(News.tag("HDFC Bank results").contains(Market.BANKNIFTY))
        assertTrue(News.tag("Insurers rally").contains(Market.FINNIFTY))
        assertTrue(News.tag("India VIX jumps").contains(Market.VIX))
    }

    @Test fun mergeKeepsEachStoryOnceNewestFirst() {
        val a = News.parse(rss, "A"); val b = News.parse(rss.replace("Feed", "Other"), "B")
        val m = News.merge(listOf(a, b))
        assertEquals(3, m.size)
        assertTrue(m.zipWithNext().all { (x, y) -> (x.at ?: Instant.EPOCH) >= (y.at ?: Instant.EPOCH) })
    }
}

class AskTest {
    @Test fun topicsAndMarkets() {
        val q = Ask.parse("What is BankNifty doing today?")
        assertEquals(listOf(Market.BANKNIFTY), q.markets); assertTrue(Topic.OVERVIEW in q.topics); assertNull(q.order)
        assertTrue(Topic.WHY in Ask.parse("Why is gold up?").topics)
        assertTrue(Topic.LEVELS in Ask.parse("nifty support levels").topics)
        assertTrue(Topic.NEWS in Ask.parse("any news on banks").topics)
        assertTrue(Topic.PATTERNS in Ask.parse("any candle pattern on finnifty").topics)
        assertTrue(Topic.VOLATILITY in Ask.parse("is the market volatile").topics)
        assertTrue(Topic.ADVICE in Ask.parse("should I buy nifty calls?").topics)
        assertEquals(setOf(Topic.OFF_TOPIC), Ask.parse("who won the cricket match").topics)
        assertEquals(setOf(Topic.GREETING), Ask.parse("hello").topics)
        assertEquals(setOf(Topic.OVERVIEW), Ask.parse("hey sensex").topics)
    }

    @Test fun backtestRequests() {
        val q = Ask.parse("Backtest the hammer on Nifty 15 minute chart")
        assertTrue(Topic.BACKTEST in q.topics); assertEquals(PatternKind.HAMMER, q.pattern); assertEquals(15, q.minutes); assertEquals(listOf(Market.NIFTY), q.markets)
        assertEquals(PatternKind.BEARISH_ENGULFING, Ask.parse("make a strategy from the bearish engulfing on banknifty 1h").pattern)
        assertEquals(60, Ask.parse("strategy for breakout on finnifty hourly").minutes)
        assertEquals(PatternKind.BREAKOUT_DOWN, Ask.parse("backtest the breakdown").pattern)
        val plain = Ask.parse("backtest this")
        assertTrue(Topic.BACKTEST in plain.topics); assertNull(plain.pattern); assertNull(plain.minutes)
        assertTrue(Ira().answer("backtest this", emptyMap(), emptyList()).text.startsWith("Backtesting needs"))
    }

    @Test fun ordersAreReadNotGuessed() {
        val o = assertNotNull(Ask.parse("Buy 2 lots BankNifty 52000 CE").order)
        assertEquals(Market.BANKNIFTY, o.market); assertTrue(o.buy); assertEquals(2, o.lots); assertEquals(52000, o.strike); assertEquals("CE", o.right)
        assertTrue(o.missing.isEmpty())
        val p = assertNotNull(Ask.parse("sell one lot nifty 24500 put").order)
        assertFalse(p.buy); assertEquals(1, p.lots); assertEquals("PE", p.right)
        val vague = assertNotNull(Ask.parse("buy nifty").order)
        assertEquals(listOf("how many lots", "call or put", "which strike"), vague.missing)
        val gold = assertNotNull(Ask.parse("buy 1 lot gold").order)
        assertTrue(gold.missing.isEmpty())
        assertNull(Ask.parse("should I buy nifty?").order, "a question about buying is not an order")
        assertNull(Ask.parse("why the sell off in banks").order)
        assertNull(Ask.parse("where did nifty close").order)
        assertEquals(listOf("which index", "how many lots", "call or put", "which strike"), Ask.parse("buy something").order!!.missing)
        val atm = assertNotNull(Ask.parse("buy 1 lot nifty atm ce").order)
        assertTrue(atm.atm); assertNull(atm.strike); assertTrue(atm.missing.isEmpty()); assertEquals("BUY 1 lot Nifty ATM CE", atm.describe())
        assertFalse(assertNotNull(Ask.parse("buy 1 lot nifty 24500 ce atm").order).atm, "a strike named wins")
        assertNotNull(Ask.parse("buy 1 lot gold").order!!.refusal)
        assertNotNull(Ask.parse("buy 1 lot sensex 80000 ce").order!!.refusal)
        assertNull(Ask.parse("buy 1 lot finnifty 23000 pe").order!!.refusal)
    }
}

class IraTest {
    private val bars = Fixtures.indexDays(25, drift = -0.3, seed = 5)
    private val snap = Brain.read(History(Market.BANKNIFTY, bars))!!
    private val vix = Brain.read(History(Market.VIX, Fixtures.indexDays(25, start = 14.0, step = 0.02, seed = 9)))!!
    private val news = News.parse("<item><title>Bank Nifty slumps as banks fall</title><link>https://e.com/x</link></item>", "ET")
    private val ira = Ira()

    @Test fun answersFromTheFactsOnly() {
        val a = ira.answer("What is BankNifty doing today?", mapOf(Market.BANKNIFTY to snap), news)
        assertTrue(a.text.startsWith("BankNifty is at ${Market.BANKNIFTY.price(snap.price)}"), a.text)
        assertTrue(a.facts.isNotEmpty())
        assertTrue(ira.numbersBacked(a.text.substringBefore(" A ").substringBefore(" Here it"), a.facts + a.text.let { emptyList() }), a.text)
    }

    @Test fun whyBringsNewsAndVolatility() {
        val a = ira.answer("Why is bank nifty down?", mapOf(Market.BANKNIFTY to snap, Market.VIX to vix), news)
        assertTrue(a.text.contains("Bank Nifty slumps as banks fall"), a.text)
        assertTrue(a.text.contains("India VIX is"), a.text)
        assertTrue(a.facts.any { it.startsWith("headline (ET, negative)") })
    }

    @Test fun noAdviceNoGuessing() {
        assertTrue(ira.answer("should I buy banknifty?", mapOf(Market.BANKNIFTY to snap), emptyList()).text.endsWith("these are the facts for you to decide on."))
        assertTrue(ira.answer("who won the match", emptyMap(), emptyList()).text.startsWith("I only know"))
        assertTrue(ira.answer("hello", emptyMap(), emptyList()).text.startsWith("Hello"))
        assertEquals("I have no prices for Gold yet.", ira.answer("how is gold", emptyMap(), emptyList()).text)
        assertTrue(ira.answer("banknifty news", mapOf(Market.BANKNIFTY to snap), emptyList()).text.contains("Nothing specific on BankNifty lately."))
    }

    @Test fun ordersGoToReview() {
        val a = ira.answer("buy 1 lot banknifty 52000 ce", emptyMap(), emptyList())
        assertNotNull(a.order)
        assertTrue(a.text.contains("BUY 1 lot BankNifty 52000 CE") && a.text.contains("nothing is sent until you press Confirm"), a.text)
        assertTrue(ira.answer("buy nifty", emptyMap(), emptyList()).text.startsWith("To prepare that order I need"))
        val gold = ira.answer("buy 2 lots gold", emptyMap(), emptyList())
        assertTrue(gold.text.startsWith("I can't place gold orders") && gold.order == null, gold.text)
        assertNull(ira.answer("buy 1 lot sensex 80000 ce", emptyMap(), emptyList()).order)
    }

    @Test fun theNumberCheck() {
        val facts = listOf("last price 24,612.40 at 11:15", "change +0.21%")
        assertTrue(ira.numbersBacked("Nifty is at 24,612.40 (0.21) at 11:15", facts))
        assertFalse(ira.numbersBacked("Nifty is at 24,700", facts))
    }

    @Test fun patternsSayWhatTheBookKnows() {
        val book = PatternBook()
        listOf(15, 60).forEach { m -> book.learn(Market.BANKNIFTY, m, Candles.fold(bars, m, Market.BANKNIFTY)) }
        val a = Ira(book).answer("any pattern on banknifty", mapOf(Market.BANKNIFTY to snap), emptyList())
        if (snap.patterns.isNotEmpty()) assertTrue(a.text.contains("formed on the"), a.text)
        val facts = Ira(book).facts(snap)
        assertTrue(facts.first().startsWith("last price"))
    }
}
