package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreMarketTest {
    private val today = LocalDate.of(2026, 10, 6)
    private val good = PreMarket.Setup(
        configured = true, loggedIn = true, staticIp = true, relay = true, pricesMissing = emptyList(), modelReady = true,
        voiceWanted = true, voiceProblem = null, mic = true, kill = false, live = true, dailyLoss = 2_000.0, maxTrades = 10,
        armed = setOf("ORB BankNifty"), usual = setOf("ORB BankNifty"), today = listOf("Nifty weekly expiry today"),
    )
    private val account = PreMarket.Account(available = 150_000.0, usualSize = 40_000.0, carried = emptyList())

    @Test fun asked() {
        for (q in listOf("am I ready to trade?", "Jarvis, am I ready to trade today?", "pre-market checklist", "premarket checklist",
                "go through my pre-market checklist", "read me the morning checklist", "are we ready", "am I set for the open?", "is everything ready for the open",
                "pre market checks", "give me my trading checklist", "checklist please", "are we all set for today boss", "readiness check"))
            assertTrue(PreMarket.asked(q), q)
        for (q in listOf("am I ready for live", "am I ready to go live", "can I go live", "should I trade today", "buy nifty 25000 ce", "run my checklist",
                "what matters right now", "brief me", "how is nifty", "am i ready", "is the market open"))
            assertFalse(PreMarket.asked(q), q)
    }

    @Test fun neitherAnOrderNorACommandAndNotTakenEarlier() {
        for (q in listOf("am I ready to trade?", "pre-market checklist", "go through my morning checklist", "are we ready")) {
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertFalse(Bundle.acts(q), q)
            assertFalse(DataAge.asked(q), q)
            assertNull(Honest.asked(q), q)
            assertFalse(Consistency.asked(q), q)
            assertFalse(CoPilot.asked(q), q)
            assertFalse(PatternCalls.asked(q), q)
            assertFalse(DayJournal.asked(q), q)
            assertNull(Learnings.asked(q), q)
            assertNull(Thinking.asked(q), q)
        }
    }

    @Test fun allPass() {
        val c = PreMarket.checks(good, account, locked = false)
        assertTrue(c.none { it.ok == false }, c.toString())
        val s = PreMarket.say(c, locked = false)
        assertTrue(s.startsWith("Pre-market checklist, Boss: all 11 checks pass"), s)
        assertTrue("✓ Margin: Rs 150,000 margin available, enough for your usual position of about Rs 40,000." in s, s)
        assertTrue("✓ Guards: Daily loss limit Rs 2,000 and at most 10 trades a day are set." in s, s)
        assertTrue("• Today: Nifty weekly expiry today." in s, s)
        assertTrue(s.endsWith("I changed nothing; each fix is yours to make."), s)
    }

    @Test fun failsSayTheFixAsHisStep() {
        val bad = good.copy(loggedIn = false, staticIp = false, relay = false, pricesMissing = listOf("Nifty", "BankNifty"), modelReady = false,
            mic = false, kill = true, dailyLoss = 0.0, armed = setOf("Pine 1"), usual = setOf("ORB BankNifty"), today = emptyList())
        val acct = account.copy(available = 10_000.0, carried = listOf("NIFTY 25000 CE, 75 long"))
        val c = PreMarket.checks(bad, acct, locked = false)
        val fails = c.filter { it.ok == false }.map { it.part }
        assertEquals(listOf(PreMarket.Part.LOGIN, PreMarket.Part.STATIC_IP, PreMarket.Part.RELAY, PreMarket.Part.PRICES, PreMarket.Part.MODEL,
            PreMarket.Part.VOICE, PreMarket.Part.KILL, PreMarket.Part.GUARDS, PreMarket.Part.BOTS, PreMarket.Part.MARGIN, PreMarket.Part.CARRIED), fails)
        assertTrue(c.filter { it.ok == false }.all { it.fix != null && "yourself" in it.fix!! || it.part == PreMarket.Part.CARRIED || it.part == PreMarket.Part.MARGIN })
        val s = PreMarket.say(c, locked = false)
        assertTrue(s.startsWith("Pre-market checklist, Boss: 0 of 11 pass, 11 to fix."), s)
        assertTrue("✗ Zerodha login: Zerodha is not logged in today. Fix: Log in yourself in Settings, Zerodha, before 9:15." in s, s)
        assertTrue("No live prices from BankNifty and Nifty." in s, s)
        assertTrue("Bots not as usual: usually armed but not now: ORB BankNifty; armed but not usually: Pine 1." in s, s)
        assertTrue("Carried overnight: NIFTY 25000 CE, 75 long." in s, s)
        assertTrue("• Today: No events or expiries today." in s, s)
        // Words only: nothing said reads as Jarvis doing it.
        for (w in listOf("I will", "I'll", "I have switched", "I armed", "I closed", "placing")) assertFalse(w in s, w)
    }

    @Test fun lockedPhoneLeavesTheAccountOutAndSaysNoAmount() {
        val c = PreMarket.checks(good, account, locked = true)
        assertTrue(c.none { it.part == PreMarket.Part.MARGIN || it.part == PreMarket.Part.CARRIED })
        val s = PreMarket.say(c, locked = true)
        assertFalse("Rs" in s, s)
        assertTrue("✓ Guards: Your daily loss limit and max trades a day are set." in s, s)
        assertTrue("Your margin and overnight positions need the phone unlocked." in s, s)
        // Not asked for the account (the 09:00 morning check): no note.
        assertFalse("unlocked" in PreMarket.say(PreMarket.checks(good, null, locked = true), locked = true, accountAsked = false))
    }

    @Test fun notesAreNotCounted() {
        val c = PreMarket.checks(good.copy(configured = false, loggedIn = false, staticIp = null, relay = null, voiceWanted = false, usual = null),
            account.copy(available = null), locked = false)
        assertTrue(c.filter { it.ok == null }.map { it.part }.containsAll(listOf(PreMarket.Part.LOGIN, PreMarket.Part.VOICE, PreMarket.Part.BOTS, PreMarket.Part.TODAY, PreMarket.Part.MARGIN)))
        assertTrue(c.none { it.part == PreMarket.Part.STATIC_IP || it.part == PreMarket.Part.RELAY })
        assertEquals("Armed: ORB BankNifty (too few days recorded to know your usual).", PreMarket.bots(setOf("ORB BankNifty"), null).text)
    }

    @Test fun bots() {
        assertEquals(true, PreMarket.bots(emptySet(), emptySet()).ok)
        assertEquals("No bots are armed, as usual.", PreMarket.bots(emptySet(), emptySet()).text)
        val miss = PreMarket.bots(emptySet(), setOf("ORB A", "ORB B"))
        assertEquals("Bots not as usual: usually armed but not now: ORB A and ORB B.", miss.text)
        assertEquals("Arm them yourself if you mean to run them today.", miss.fix)
    }

    @Test fun usualArmed() {
        val d = (1..5).associate { today.minusDays(it.toLong()) to if (it <= 3) setOf("ORB A", "Pine") else setOf("ORB A") }
        assertEquals(setOf("ORB A", "Pine"), PreMarket.usualArmed(d, today))
        assertNull(PreMarket.usualArmed(d.filterKeys { it >= today.minusDays(2) }, today))
        // Today's own record never counts toward the usual.
        assertEquals(setOf("ORB A", "Pine"), PreMarket.usualArmed(d + (today to setOf("New")), today))
        // Only the last 10 days.
        val old = (11..20).associate { today.minusDays(it.toLong()) to setOf("Old") }
        val recent = (1..10).associate { today.minusDays(it.toLong()) to setOf("ORB A") }
        assertEquals(setOf("ORB A"), PreMarket.usualArmed(old + recent, today))
    }

    @Test fun usualSize() {
        assertNull(PreMarket.usualSize(emptyList()))
        assertEquals(30.0, PreMarket.usualSize(listOf(10.0, 30.0, 50.0)))
        assertEquals(25.0, PreMarket.usualSize(listOf(10.0, 20.0, 30.0, 40.0, 0.0)))
        assertEquals(100.0, PreMarket.usualSize(List(30) { if (it < 10) 1.0 else 100.0 }))
    }

    @Test fun recordRoundTrips() {
        var kept: String? = null
        kept = PreMarket.record(kept, today, setOf("ORB A"))
        kept = PreMarket.record(kept, today, setOf("Pine, one|two"))
        kept = PreMarket.record(kept, today.minusDays(1), emptySet())
        val d = PreMarket.decode(kept)
        assertEquals(setOf("ORB A", "Pine, one|two"), d[today])
        assertEquals(emptySet(), d[today.minusDays(1)])
        val many = (0 until 40).fold(null as String?) { k, i -> PreMarket.record(k, today.minusDays(i.toLong()), setOf("X")) }
        assertEquals(PreMarket.DAYS * 2, PreMarket.decode(many).size)
        assertTrue(PreMarket.decode("garbage\n").isEmpty())
    }
}
