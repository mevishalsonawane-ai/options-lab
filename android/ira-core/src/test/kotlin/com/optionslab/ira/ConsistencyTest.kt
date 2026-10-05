package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsistencyTest {
    private val today = LocalDate.of(2026, 10, 9)   // a Friday
    private val prior = LocalDate.of(2026, 10, 8)
    private val expiry = LocalDate.of(2026, 10, 13)

    /** [minutes] 1-minute candles from 09:15 today along [path], with the prior session's close [prevClose]. */
    private fun day(minutes: Int, prevClose: Double = 23_950.0, path: (Int) -> Double): List<Candle> {
        val out = ArrayList<Candle>()
        out += Candle(prior.atTime(15, 29), prevClose, prevClose + 1, prevClose - 1, prevClose)
        var px = path(0)
        for (i in 0 until minutes) {
            val o = px; px = path(i + 1)
            out += Candle(today.atTime(9, 15).plusMinutes(i.toLong()), o, maxOf(o, px) + 0.5, minOf(o, px) - 0.5, px)
        }
        return out
    }

    /** A rising day in waves (trend-like up, above the prior close), last candle 13:44. */
    private val up = day(270) { i -> 24_000.0 + i * 0.5 + 30 * sin(2 * PI * i / 90) }
    /** A falling day. */
    private val down = day(270, prevClose = 24_100.0) { i -> 24_000.0 - i * 0.5 - 30 * sin(2 * PI * i / 90) }

    private fun strike(k: Double, ce: Long, pe: Long) = ChainIntel.Strike(k, ce, pe, null, null, 50.0, 50.0, 12.0, 12.5)

    /** A Nifty chain at [h]:[m] with spot [spot], the biggest call OI at [callWall] and put OI at [putWall]. */
    private fun chain(spot: Double, callWall: Double, putWall: Double, h: Int = 13, m: Int = 44, underlying: String = "NIFTY") =
        ChainIntel.Read(underlying, expiry, today.atTime(h, m), spot, 24_150.0, 12.4,
            (0..16).map { 23_750.0 + it * 50 }.map { k -> strike(k, if (k == callWall) 900_000 else 100_000, if (k == putWall) 900_000 else 100_000) })

    // Words that would make it advice or a forecast. (Boss's own quoted words are left out of the check.)
    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|bullish|bearish|will (rise|fall|go|break)|likely|expect|target|" +
        "don't trade|do not trade|stop trading|take the trade|safe to trade)\\b")

    private fun noAdvice(s: String) {
        val unquoted = s.replace(Regex("\"[^\"]*\""), "\"\"")
        assertFalse(ADVICE.containsMatchIn(unquoted), "advice in: $s")
    }

    // ---- (a) market facts pulling different ways ----

    @Test fun structureUpAgainstCallWritingJustAbove() {
        val s = assertNotNull(Structure.read(Market.NIFTY, up, today))
        val spot = s.last
        val leans = Consistency.leans(s, chain(spot, callWall = 24_300.0, putWall = 23_750.0))
        assertTrue(leans.any { it.source == Consistency.Source.STRUCTURE && it.sign == 1 }, "$leans")
        assertTrue(leans.any { it.source == Consistency.Source.CALL_WRITING && it.sign == -1 }, "$leans")
        // The put wall is far below (over 1% of spot): not "just below".
        assertFalse(leans.any { it.source == Consistency.Source.PUT_WRITING }, "$leans")
        val t = Consistency.tensions(leans)
        assertEquals(1, t.size, "$t")
        assertTrue(t[0].startsWith("Nifty's structure is trend-like up so far (net move "), t[0])
        assertTrue(t[0].contains(", but the chain's call writing is heaviest just above, at the 24,300 strike (the chain at 13:44)"), t[0])
        assertTrue(t[0].endsWith(" - both are facts; they point different ways."), t[0])
        noAdvice(t[0])
    }

    @Test fun nothingPullsApartWhenTheFactsAgree() {
        val s = assertNotNull(Structure.read(Market.NIFTY, up, today))
        // Rising day, put writing just below, no call wall near: all point the same way.
        val leans = Consistency.leans(s, chain(s.last, callWall = 24_550.0, putWall = 24_100.0), vixChangePct = -2.0)
        assertTrue(leans.all { it.sign > 0 }, "$leans")
        assertTrue(Consistency.tensions(leans).isEmpty())
        assertTrue(Consistency.tensions(emptyList()).isEmpty())
        assertTrue(Consistency.leans(null).isEmpty())
    }

    @Test fun aFallingDayAgainstPutWritingAndVix() {
        val s = assertNotNull(Structure.read(Market.NIFTY, down, today))
        val leans = Consistency.leans(s, chain(s.last, callWall = 24_550.0, putWall = (s.last / 50).toInt() * 50.0), vixChangePct = -6.0)
        val t = Consistency.tensions(leans)
        assertEquals(2, t.size, "$t")
        assertTrue(t[0].startsWith("Nifty's structure is trend-like down so far"), t[0])
        assertTrue(t[0].contains("but the chain's put writing is heaviest just below"), t[0])
        assertTrue(t[1].contains("India VIX is down 6.0% on the day"), t[1])
        t.forEach { noAdvice(it) }
        // At most this many.
        assertEquals(1, Consistency.tensions(leans, max = 1).size)
    }

    @Test fun anotherIndexOrDaysChainIsNotSetAgainstTheStructure() {
        val s = assertNotNull(Structure.read(Market.NIFTY, up, today))
        val bank = chain(s.last, callWall = 24_300.0, putWall = 23_750.0, underlying = "BANKNIFTY")
        assertFalse(Consistency.leans(s, bank).any { it.source == Consistency.Source.CALL_WRITING })
        val yesterday = chain(s.last, 24_300.0, 23_750.0).copy(at = prior.atTime(15, 0))
        assertFalse(Consistency.leans(s, yesterday).any { it.source == Consistency.Source.CALL_WRITING })
    }

    // ---- (c) his own data disagreeing ----

    @Test fun chainSpotAgainstTheCandle() {
        val c = up.last { it.t == today.atTime(13, 44) }
        // Agrees: inside the candle's range.
        assertNull(Consistency.priceCheck(Market.NIFTY, up, Consistency.Quote("the chain's spot", (c.h + c.l) / 2, today.atTime(13, 44, 30))))
        // A hair outside: within the threshold.
        assertNull(Consistency.priceCheck(Market.NIFTY, up, Consistency.Quote("the chain's spot", c.h + 5, today.atTime(13, 44, 30))))
        // Far apart: said, with the source he goes by and why.
        val r = chain(c.h + 100, 24_300.0, 23_750.0)
        val m = assertNotNull(Consistency.priceCheck(Market.NIFTY, up, Consistency.chainQuote(r)))
        assertEquals(c, m.candle)
        assertTrue(m.pct >= Consistency.DISAGREE_PCT)
        assertTrue(m.text.startsWith("My own numbers disagree, Boss: the Nifty option chain's spot at 13:44 is "), m.text)
        assertTrue(m.text.contains("but my Nifty 1-minute candle for 13:44 ran "), m.text)
        assertTrue(m.text.contains("I go by the candles: they are Nifty's own minute-by-minute prices"), m.text)
        noAdvice(m.text)
        // No candle near that minute (the candles stop at 13:44; the chain is from 14:30): not this check's to say.
        assertNull(Consistency.priceCheck(Market.NIFTY, up, Consistency.Quote("the chain's spot", c.h + 100, today.atTime(14, 30))))
        // Within two minutes: the nearest candle.
        assertNotNull(Consistency.priceCheck(Market.NIFTY, up, Consistency.Quote("the chain's spot", c.h + 100, today.atTime(13, 46))))
    }

    // ---- (b) Boss's words against today ----

    @Test fun weekdayRules() {
        assertEquals(DayOfWeek.FRIDAY, Consistency.dayRule("I don't trade on Fridays")?.day)
        assertEquals(DayOfWeek.MONDAY, Consistency.dayRule("no trading on Mondays")?.day)
        assertEquals(DayOfWeek.FRIDAY, Consistency.dayRule("I never trade Fridays")?.day)
        assertEquals(DayOfWeek.THURSDAY, Consistency.dayRule("thursdays off")?.day)
        val bn = assertNotNull(Consistency.dayRule("Don't trade BankNifty on Fridays"))
        assertEquals(Market.BANKNIFTY, bn.market)
        assertNull(Consistency.dayRule("I trade best on Fridays"))
        assertNull(Consistency.dayRule("my wife's birthday is on a Monday"))
        assertNull(Consistency.dayRule("no trades before 9:30"))
        // Not BossRules' to read: a weekday rule is not "avoid the market".
        assertNull(BossRules.of("I don't trade on Fridays"))
    }

    private fun deed(h: Int, m: Int, mk: Market? = Market.NIFTY) = Consistency.Deed(today.atTime(h, m), mk)

    @Test fun wordsAgainstToday() {
        val notes = listOf("I don't trade on Fridays", "no trades before 9:30", "I don't trade BankNifty", "skip expiry days", "no new trades after 2 pm")
        val deeds = listOf(deed(9, 20), deed(11, 0, Market.BANKNIFTY), deed(14, 10), deed(12, 0))
        val c = Consistency.clashes(notes, deeds, today, maxTrades = 3, expiryToday = setOf(Market.NIFTY))
        val byKey = c.associateBy { it.key.substringBefore(':') }
        assertEquals("Boss, you told me \"I don't trade on Fridays\" - it's Friday, and you have 4 trades today. ${Consistency.GENTLE}", byKey["DAY"]?.text)
        assertTrue(byKey["BEFORE"]!!.text.contains("one of today's trades opened at 09:20."), byKey["BEFORE"]!!.text)
        assertTrue(byKey["AFTER"]!!.text.contains("one of today's trades opened at 14:10, after 14:00."), byKey["AFTER"]!!.text)
        assertTrue(byKey["MARKET"]!!.text.contains("you have 1 BankNifty trade today."), byKey["MARKET"]!!.text)
        assertTrue(byKey["EXPIRY"]!!.text.contains("it's Nifty's expiry day, and you have 3 trades on it today."), byKey["EXPIRY"]!!.text)
        assertEquals("Boss, your goal is no more than 3 trades a day - you're at 4 today. ${Consistency.GENTLE}", byKey["MAXTRADES"]?.text)
        c.forEach { noAdvice(it.text); assertTrue(it.text.startsWith("Boss, "), it.text); assertTrue(it.text.endsWith(Consistency.GENTLE)) }
    }

    @Test fun noClashWhenWordsAndDeedsAgree() {
        // Another weekday, trades inside the hours, no BankNifty, not expiry, under the goal.
        val notes = listOf("I don't trade on Mondays", "no trades before 9:30", "I don't trade BankNifty", "skip expiry days")
        assertTrue(Consistency.clashes(notes, listOf(deed(10, 0), deed(11, 0)), today, maxTrades = 3).isEmpty())
        // At the goal is not past it.
        assertTrue(Consistency.clashes(emptyList(), listOf(deed(10, 0), deed(11, 0), deed(12, 0)), today, maxTrades = 3).isEmpty())
        // No trades today: nothing (yesterday's do not count).
        assertTrue(Consistency.clashes(listOf("I don't trade on Fridays"), listOf(Consistency.Deed(prior.atTime(10, 0), Market.NIFTY)), today).isEmpty())
        // "Don't trade BankNifty on Fridays" with only Nifty trades: no clash, and not read as "never BankNifty".
        assertTrue(Consistency.clashes(listOf("Don't trade BankNifty on Fridays"), listOf(deed(10, 0)), today).isEmpty())
        val bn = Consistency.clashes(listOf("Don't trade BankNifty on Fridays"), listOf(deed(10, 0), deed(10, 30, Market.BANKNIFTY)), today)
        assertEquals(1, bn.size)
        assertTrue(bn[0].text.contains("it's Friday, and you have 1 BankNifty trade today."), bn[0].text)
    }

    @Test fun eachClashOnceAtATime() {
        val c = Consistency.clashes(listOf("I don't trade on Fridays"), listOf(deed(10, 0), deed(11, 0)), today, maxTrades = 1)
        assertEquals(2, c.size)
        val first = assertNotNull(Consistency.next(c, emptySet()))
        val second = assertNotNull(Consistency.next(c, setOf(first.key)))
        assertTrue(first.key != second.key)
        assertNull(Consistency.next(c, setOf(first.key, second.key)))
    }

    // ---- asked ----

    @Test fun theQuestions() {
        listOf("any contradictions?", "Jarvis, are there any contradictions in the facts?", "do you see any conflicts", "any mixed signals today?",
            "do the facts agree?", "do your numbers add up?", "are the signals mixed?", "what's pulling different ways?",
            "check yourself for contradictions", "consistency check", "am I going against my own rules?", "am I breaking my rules today",
            "do the facts point the same way?").forEach { assertTrue(Consistency.asked(it), it) }
        listOf("what's the structure today?", "make the case", "is your data fresh?", "what are my rules", "what are my goals",
            "any news?", "conflict in the middle east", "buy nifty 24000 ce", "will the market rise").forEach { assertFalse(Consistency.asked(it), it) }
    }

    /** Mine stay mine: the other reasoning answers do not take these whole questions. */
    @Test fun routingKeepsTheOthers() {
        for (q in listOf("any contradictions?", "do the facts agree?", "what's pulling different ways?", "am I going against my own rules?", "consistency check")) {
            assertFalse(TradeCase.asked(q), q)
            assertNull(Structure.asked(q), q)
            assertNull(ChainIntel.asked(q), q)
            assertNull(Scenarios.asked(q), q)
            assertFalse(DataAge.asked(q), q)
            assertNull(Thinking.asked(q), q)
            assertNull(Honest.asked(q), q)
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
        }
    }

    @Test fun theAnswer() {
        val clash = Consistency.clashes(listOf("I don't trade on Fridays"), listOf(deed(10, 0), deed(11, 0)), today)
        val said = Consistency.say(listOf("A, but B - ${Consistency.BOTH}."), null, clash, locked = false)
        assertTrue(said.startsWith("Checking myself for contradictions, Boss. In the market facts: A, but B"), said)
        assertTrue(said.contains("Between your words and today: You told me \"I don't trade on Fridays\" - it's Friday, and you have 2 trades today."), said)
        assertTrue(said.endsWith("not advice: what to make of them is your call, Boss."), said)
        noAdvice(said)
        // Locked: Boss's words and trades are never said.
        val locked = Consistency.say(listOf("A, but B - ${Consistency.BOTH}."), null, clash, locked = true)
        assertFalse(locked.contains("Fridays"), locked)
        assertFalse(locked.contains("trades today"), locked)
        assertTrue(locked.contains(Consistency.LOCKED_NOTE), locked)
        // Nothing: said so.
        assertEquals(Consistency.NONE + " Nothing you did today goes against what you told me.", Consistency.say(emptyList(), null, emptyList(), false))
        assertEquals(Consistency.NONE + " " + Consistency.LOCKED_NOTE, Consistency.say(emptyList(), null, clash, true))
        noAdvice(Consistency.NONE)
    }

    // ---- in "make the case" ----

    @Test fun inTheCase() {
        val n = TradeCheck.Now(true, true, 13 * 60 + 45, false, true, null, true, false, false, false, null, 10_000.0,
            14.0, 1.0, 0.3, 0.1, 0.05, 0.15, null, emptyList(), false, emptyList(), emptyList())
        val s = assertNotNull(Structure.read(Market.NIFTY, up, today))
        val r = chain(s.last, callWall = 24_300.0, putWall = 23_750.0)
        val case = TradeCase.build(TradeCase.Input(n, today.atTime(13, 45), bars = mapOf(Market.NIFTY to up), locked = true, chainRead = r))
        assertEquals(1, case.tensions.size, "${case.tensions}")
        assertNull(case.mismatch)
        val said = case.say()
        assertTrue(said.contains("Where the facts pull apart: Nifty's structure is trend-like up so far"), said)
        assertTrue(said.endsWith(TradeCase.YOURS), said)
        // The chain's spot far from the candle: said which source he goes by.
        val off = TradeCase.build(TradeCase.Input(n, today.atTime(13, 45), bars = mapOf(Market.NIFTY to up), locked = true, chainRead = r.copy(spot = s.last + 150)))
        assertTrue(off.mismatch?.startsWith("My own numbers disagree, Boss:") == true, off.mismatch)
        assertTrue(off.say().contains("I go by the candles"))
        // No chain: no tension from it, no mismatch.
        val bare = TradeCase.build(TradeCase.Input(n, today.atTime(13, 45), bars = mapOf(Market.NIFTY to up), locked = true))
        assertTrue(bare.tensions.isEmpty()); assertNull(bare.mismatch)
    }

    @Test fun askedOfMyCase() {
        for (q in listOf("any contradictions in my case", "any contradictions in the case", "are there any conflicts in my trade case",
                "any contradictions in this case")) assertTrue(Consistency.asked(q), q)
        assertFalse(Consistency.asked("make the case"))
    }
}
