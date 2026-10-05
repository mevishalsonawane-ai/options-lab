package com.optionslab.ira

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoPilotTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val now = today.atTime(12, 0)
    private val ist = ZoneId.of("Asia/Kolkata")

    private fun move(pct: Double, mins: Long, m: Market = Market.NIFTY, times: Double = 4.0) = CoPilot.Fact(CoPilot.Kind.MOVE,
        "${m.label} rose by $pct% in 10 minutes.", at = now.minusMinutes(mins), size = (times - 1).coerceIn(0.0, 4.0),
        sizeWhy = "$times times its usual 10-minute move", markets = setOf(m))

    /** The words that would make a brief advice; none may appear in anything Jarvis says here. */
    private val advice = Regex("\\b(should|recommend\\w*|suggest\\w*|advise\\w*|buy|sell|go long|go short|hedge|exit|enter|book profit|take profit|" +
        "stop ?loss|target|you need to|better to|must|worth (?:it|taking)|consider)\\b", RegexOption.IGNORE_CASE)

    private fun noAdvice(s: String) = assertNull(advice.find(s)?.value, s)

    @Test fun asked() {
        for (q in listOf("what matters right now?", "Jarvis, what matters most today", "brief me like a co-pilot", "co-pilot brief",
            "what's most important right now", "rank what matters", "top 3 things today", "brief me", "what do I need to know today", "catch me up"))
            assertTrue(CoPilot.asked(q), q)
        for (q in listOf("what's the main news today?", "any contradictions?", "what is the structure today", "how much does it matter",
            "brief mode on", "buy 1 lot nifty", "what's your plan today", "is your data fresh?"))
            assertFalse(CoPilot.asked(q), q)
        // Others' questions never taken by this one, nor this one's by them.
        for (q in listOf("what matters right now", "brief me like a co-pilot", "rank what matters")) {
            assertFalse(Consistency.asked(q) || Structure.asked(q) != null || NewsDesk.asked(q) != null || ChainIntel.asked(q) != null ||
                DataAge.asked(q) || Agenda.asked(q) || Improve.asked(q) || PatternCalls.asked(q), q)
        }
    }

    @Test fun ranksBySizeRecencyRelevanceAndConflict() {
        val facts = listOf(
            move(0.4, 50, times = 2.0),                                     // size 1, recency 2, weight 1 = 4
            move(0.9, 30, Market.BANKNIFTY, times = 5.0),                   // size 4, recency 2, weight 1 = 7
            CoPilot.Fact(CoPilot.Kind.EVENT, "Event today: RBI policy.", due = today),          // 2 + 1 = 3
            CoPilot.Fact(CoPilot.Kind.CONFLICT, "Nifty's structure is up, but call writing is heaviest just above - both are facts; they point different ways.",
                at = now.minusMinutes(5), markets = setOf(Market.NIFTY), conflict = true),     // 3 + 2 + 1 = 6
            CoPilot.Fact(CoPilot.Kind.CHAIN, "The biggest call OI is at 24,700.", at = now.minusHours(4), markets = setOf(Market.NIFTY)), // 0.5 + 0.5 = 1
        )
        val r = CoPilot.rank(facts, emptySet(), now, locked = false)
        // The event (3) is under the bar for a fourth fact, the chain (1) too.
        assertEquals(listOf(7.0, 6.0, 4.0), r.map { it.total })
        assertEquals(CoPilot.Part.SIZE, r[0].lead)
        assertEquals("the biggest move against usual today (5.0 times its usual 10-minute move)", CoPilot.reason(r[0], r, emptySet(), now))
        assertEquals(CoPilot.Part.RECENCY, r[1].lead)   // 3 for recency beats 2 for conflict
        assertEquals("the newest fact I have (5 minutes ago)", CoPilot.reason(r[1], r, emptySet(), now))

        // Boss holds Nifty: the Nifty facts climb, and the reason says why.
        val held = CoPilot.rank(facts, setOf(Market.NIFTY), now, locked = false)
        assertEquals(CoPilot.Kind.CONFLICT, held[0].fact.kind)
        assertEquals(9.0, held[0].total)
        assertEquals("the biggest move against usual today (5.0 times its usual 10-minute move)", CoPilot.reason(held[1], held, setOf(Market.NIFTY), now))
        assertEquals(CoPilot.Part.RELEVANCE, held[0].lead)
        assertEquals("it touches your open Nifty position", CoPilot.reason(held[0], held, setOf(Market.NIFTY), now))
    }

    @Test fun saidTopFewWithAReasonEach() {
        val facts = listOf(move(0.9, 30, Market.BANKNIFTY, times = 5.0), move(0.4, 50, times = 2.0),
            CoPilot.Fact(CoPilot.Kind.EVENT, "Event today: RBI policy.", due = today))
        val s = CoPilot.brief("Nifty 24,120.00 (+0.50%).", facts, emptySet(), now, locked = false)
        assertTrue(s.startsWith("Nifty 24,120.00 (+0.50%). What matters most right now, Boss, in order: First: BankNifty rose"), s)
        assertTrue("Ranked first: the biggest move against usual today (5.0 times its usual 10-minute move)." in s, s)
        assertTrue("Second: Nifty rose" in s && "Ranked second:" in s && "Third: Event today: RBI policy. Ranked third: it is on the calendar today." in s, s)
        assertTrue(s.endsWith(CoPilot.YOURS), s)
        noAdvice(s)
        // Nothing to rank: said plainly.
        val quiet = CoPilot.brief(null, emptyList(), emptySet(), now, locked = false)
        assertEquals("${CoPilot.QUIET} ${CoPilot.YOURS}", quiet)
    }

    @Test fun atMostFiveAndTwoOfAKind() {
        val facts = (1..6).map { move(0.5 + it, it.toLong(), times = 5.0) } + listOf(
            CoPilot.Fact(CoPilot.Kind.CONFLICT, "A pulls up, but B pulls down - both are facts; they point different ways.", at = now, conflict = true),
            CoPilot.Fact(CoPilot.Kind.LIMIT, "Your Zerodha day is at -1,800 rupees, 90% of your 2,000-rupee daily loss limit.", size = 3.6, account = true),
            CoPilot.Fact(CoPilot.Kind.DATA, "My prices are 9 minutes old, Boss.", weight = CoPilot.STALE_WEIGHT),
            CoPilot.Fact(CoPilot.Kind.NEWS, "News: \"RBI holds rates\" - 4 sources.", at = now.minusMinutes(20), size = 3.0),
        )
        val r = CoPilot.rank(facts, emptySet(), now, locked = false)
        assertEquals(5, r.size)
        assertEquals(2, r.count { it.fact.kind == CoPilot.Kind.MOVE })
        assertTrue(r.zipWithNext().all { (a, b) -> a.total >= b.total })
    }

    @Test fun lockedPhoneLeavesTheAccountOut() {
        val facts = listOf(
            CoPilot.Fact(CoPilot.Kind.POSITION, "You hold 2 open Nifty legs, +1,200 rupees on them now.", markets = setOf(Market.NIFTY), account = true),
            CoPilot.Fact(CoPilot.Kind.CONFLICT, "You told me \"I don't trade on Fridays\" - it's Friday, and you have 2 trades today.", conflict = true, account = true),
            CoPilot.Fact(CoPilot.Kind.EVENT, "Event today: my dentist (added by you).", due = today, account = true),
            move(0.5, 10, times = 3.0),
        )
        val s = CoPilot.brief(null, facts, setOf(Market.NIFTY), now, locked = true)
        assertFalse("You hold" in s || "Fridays" in s || "dentist" in s || "your open Nifty" in s, s)
        assertTrue(CoPilot.LOCKED_NOTE in s && "Nifty rose" in s, s)
        // Unlocked: Boss's words count, quoted, and ranked for being his.
        val open = CoPilot.brief(null, facts, setOf(Market.NIFTY), now, locked = false)
        assertTrue("You hold 2 open Nifty legs" in open && "it is your own words against today" in open, open)
        assertFalse(CoPilot.LOCKED_NOTE in open)
    }

    @Test fun adviceShapedFactsAreNeverRanked() {
        val bad = listOf("Analysts recommend buying banks.", "Top picks for the week: 5 stocks to buy.", "Time to buy the dip, says broker.",
            "You should book profits.", "Sell calls at 24,700.", "Nifty target price raised to 26,000.")
        for (t in bad) assertTrue(CoPilot.advisory(CoPilot.Fact(CoPilot.Kind.NEWS, t)), t)
        // Facts that merely mention selling or buying stay.
        for (t in listOf("FIIs net sold 3,000 crore today.", "News: \"Sell-off deepens in IT\" - 3 sources.", "The biggest call OI is at 24,700."))
            assertFalse(CoPilot.advisory(CoPilot.Fact(CoPilot.Kind.NEWS, t)), t)
        // Boss's own quoted words are his, not Jarvis's advice.
        assertFalse(CoPilot.advisory(CoPilot.Fact(CoPilot.Kind.CONFLICT, "You asked me to remember \"you should never sell calls\" - one trade opened at 09:20.", account = true)))
        val r = CoPilot.rank(bad.map { CoPilot.Fact(CoPilot.Kind.NEWS, it, at = now, size = 4.0) }, emptySet(), now, locked = false)
        assertTrue(r.isEmpty())
    }

    @Test fun recency() {
        fun at(m: Long) = CoPilot.recency(CoPilot.Fact(CoPilot.Kind.MOVE, "x", at = now.minusMinutes(m)), now)
        assertEquals(listOf(3.0, 2.0, 1.0, 0.5), listOf(at(10), at(45), at(120), at(300)))
        assertEquals(0.0, CoPilot.recency(CoPilot.Fact(CoPilot.Kind.MOVE, "x", at = now.minusDays(1)), now))
        assertEquals(1.0, CoPilot.recency(CoPilot.Fact(CoPilot.Kind.EVENT, "x", due = today.plusDays(1)), now))
    }

    // ---- the readers' facts ----

    /** Quiet days, then today a 0.8% jump from 11:00 to 11:06. */
    private fun bars(): List<Candle> {
        val out = ArrayList<Candle>()
        for (d in 1..3) for (i in 0 until 375) {
            val px = 25_000.0 + (i % 7) * 2.0
            out += Candle(today.minusDays(7L - d).atTime(9, 15).plusMinutes(i.toLong()), px, px + 1, px - 1, px)
        }
        var px = 25_000.0
        for (i in 0 until 240) {
            val t = today.atTime(9, 15).plusMinutes(i.toLong())
            val o = px
            px = if (t.hour == 11 && t.minute in 1..6) px * 1.0014 else 25_000.0 + (i % 7) * 2.0 + (if (!t.isBefore(today.atTime(11, 1))) 210.0 else 0.0)
            out += Candle(t, o, maxOf(o, px) + 1, minOf(o, px) - 1, px)
        }
        return out
    }

    @Test fun movesFromTheCandles() {
        val f = CoPilot.moves(Market.NIFTY, bars())
        assertTrue(f.isNotEmpty())
        assertTrue(f[0].text.startsWith("Nifty rose ") && "times its usual 10-minute move" in f[0].text, f[0].text)
        assertTrue(f[0].size > 0 && f[0].markets == setOf(Market.NIFTY))
        noAdvice(f[0].text)
    }

    @Test fun limitsEventsPositionsAndNews() {
        val lim = CoPilot.limits(mapOf("Zerodha" to 2_000.0, "Paper" to 5_000.0), mapOf("Zerodha" to -1_600.0, "Paper" to -1_000.0))
        assertEquals(1, lim.size)
        assertEquals("Your Zerodha day is at -1,600 rupees, 80% of your 2,000-rupee daily loss limit.", lim[0].text)
        assertTrue(lim[0].account && lim[0].size > 3.0)
        assertTrue(CoPilot.limits(mapOf("Zerodha" to 2_000.0), mapOf("Zerodha" to 500.0)).isEmpty())

        val ev = CoPilot.events(listOf(Events.Event(today, "RBI policy"), Events.Event(today.plusDays(1), "Nifty expiry"),
            Events.Event(today.plusDays(3), "Fed meeting"), Events.Event(today, "my review", owner = true)), today)
        assertEquals(3, ev.size)
        assertEquals(setOf(Market.NIFTY), ev[1].markets)
        assertTrue(ev[2].account)

        val pos = CoPilot.positions(listOf(Exposure.Leg("Zerodha", "NIFTY24OCT24500CE", 75, 100.0, 120.0, "NIFTY"),
            Exposure.Leg("Zerodha", "NIFTY24OCT24000PE", -75, 80.0, 70.0, "NIFTY")))
        assertEquals("You hold 2 open Nifty legs, +2,250 rupees on them now.", pos.single().text)
        assertTrue(pos.single().account)

        fun h(title: String, src: String, min: Long) = Headline(title, "", src, now.minusMinutes(min).atZone(ist).toInstant(), 0.0, emptyList())
        val news = listOf(h("RBI keeps repo rate unchanged at 6.5%", "Mint", 30), h("RBI keeps repo rate unchanged at 6.5 percent", "ET", 25),
            h("HDFC Bank shares rise after results", "Moneycontrol", 10))
        val n = CoPilot.news(news, now.atZone(ist).toInstant(), ist)
        assertEquals(2, n.size)
        assertTrue(n[0].text.contains("2 sources") && n[0].size == 1.0, n[0].text)
        assertTrue(Market.BANKNIFTY in n[1].markets, n[1].toString())
    }

    @Test fun clashesAndMismatchAndData() {
        val c = CoPilot.clashes(listOf(Consistency.Clash("DAY:x", "Boss, you told me \"I don't trade on Fridays\" - it's Friday, and you have 2 trades today. ${Consistency.GENTLE}")))
        assertEquals("You told me \"I don't trade on Fridays\" - it's Friday, and you have 2 trades today.", c.single().text)
        assertTrue(c.single().account && c.single().conflict)
        val d = CoPilot.data(listOf(DataAge.Check(DataAge.Source.PRICES, "Nifty", now.minusMinutes(20), 1200, live = true),
            DataAge.Check(DataAge.Source.NEWS, "", now.minusSeconds(30), 30, live = true)), now)
        assertEquals(1, d.size)
        assertEquals(CoPilot.STALE_WEIGHT, d[0].weight)
        val r = CoPilot.rank(d + move(0.4, 20, times = 2.0), emptySet(), now, locked = false)
        assertEquals("it decides how far my other numbers can be trusted", CoPilot.reason(r.first { it.fact.kind == CoPilot.Kind.DATA }, r, emptySet(), now))
        assertNull(CoPilot.mismatch(null))
        assertNull(CoPilot.structure(null))
        assertNull(CoPilot.record(emptyList(), now))
    }

    @Test fun aFullBriefCarriesNoAdvice() {
        val b = bars()
        val facts = CoPilot.moves(Market.NIFTY, b) + listOfNotNull(CoPilot.structure(Structure.read(Market.NIFTY, b, today))) +
            CoPilot.events(listOf(Events.Event(today, "RBI policy")), today) +
            CoPilot.limits(mapOf("Paper" to 1_000.0), mapOf("Paper" to -900.0))
        val at = today.atTime(13, 14)
        for (locked in listOf(false, true)) {
            val s = CoPilot.brief(null, facts, setOf(Market.NIFTY), at, locked)
            noAdvice(s)
            assertTrue(s.contains("Ranked first:") && s.endsWith(CoPilot.YOURS), s)
        }
        assertNotNull(Structure.read(Market.NIFTY, b, today))
    }

    @Test fun askedInHinglish() {
        for (q in listOf("abhi sabse important kya hai", "aaj sabse zaroori kya hai", "kya matter karta hai abhi", "abhi kya important hai"))
            assertTrue(CoPilot.asked(q), q)
        for (q in listOf("theta kya hai", "mera p&l kya hai", "important news kya hai", "nifty kya kar raha hai"))
            assertFalse(CoPilot.asked(q), q)
    }
}
