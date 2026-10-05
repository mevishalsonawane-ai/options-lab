package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeforeTomorrowTest {
    private val mon = LocalDate.of(2026, 10, 5)
    private val tue = LocalDate.of(2026, 10, 6)

    private fun facts(
        next: LocalDate? = tue, configured: Boolean = true, live: Boolean = true,
        expiring: List<ExpiryEve.Leg>? = emptyList(), loggedIn: Boolean = true, zerodhaRead: Boolean = true,
        armed: List<String> = listOf("ORB Fresh"), records: Map<String, BeforeTomorrow.Record>? = mapOf("ORB Fresh" to BeforeTomorrow.Record(12, 7, 3200.0, 0.55)),
        staticIp: Boolean? = true, relay: Boolean? = true, battery: Boolean? = true, lastBackup: LocalDate? = mon.minusDays(2),
    ) = BeforeTomorrow.Facts(mon, next, configured, live, expiring, loggedIn, zerodhaRead, armed, records, staticIp, relay, battery, lastBackup)

    @Test fun asked() {
        for (s in listOf("what do i need to do before tomorrow", "What do I need to do before tomorrow?", "jarvis what do i have to do for tomorrow",
            "anything i need to do before tomorrow", "is there anything i need to do for tomorrow", "what should i sort out before tomorrow",
            "anything to do before tomorrow", "checklist for tomorrow", "tomorrow's checklist", "my to do list for tomorrow",
            "kal se pehle kya karna hai", "kal ke liye kya karna hai", "mujhe kal ke liye kuch karna hai kya", "kya karna padega kal se pehle",
            "what do i need to take care of before tomorrow's open", "what do i need to check before the next trading day"))
            assertTrue(BeforeTomorrow.asked(s), s)
        // Never an order, a market forecast, levels, expiry, the plan, a reminder - those are their own.
        for (s in listOf("what should i buy before tomorrow", "what do i need to do before tomorrow's expiry", "what expires tomorrow",
            "what's the plan for tomorrow", "nifty levels for tomorrow", "remind me what to do before tomorrow", "what should i trade tomorrow",
            "what do i need to do", "am i ready for tomorrow", "what will nifty do tomorrow", "close everything before tomorrow",
            "what do i need to do today", "pre market checklist", "kal kya expire ho raha hai", "kal nifty kya karega"))
            assertFalse(BeforeTomorrow.asked(s), s)
    }

    @Test fun neverAnOrderOrACommandAndRoutedInTheHubsOrder() {
        val audit = CoverageTest()
        for (s in listOf("what do i need to do before tomorrow", "kal se pehle kya karna hai", "checklist for tomorrow", "tomorrow's to do list")) {
            assertEquals("BeforeTomorrow", audit.feature(s), s)
            val p = Ask.parse(s)
            assertEquals(null, p.order, s); assertEquals(null, p.command, s)
            assertTrue(!Bundle.acts(s), s)
            assertEquals(null, Intents.quick(s), s)
            assertTrue(!Reminder.asked(s) && !Reminder.cancelAsked(s) && !FollowUp.acts(s) && !Reminder.tomorrow(s), s)
        }
        // Said with something to do, it is left to the multi-step plan.
        assertTrue(Bundle.acts("what do i need to do before tomorrow then close all positions") ||
            Ask.parse("what do i need to do before tomorrow then close all positions").command != null)
    }

    @Test fun allSetSaysNothingToDoAndEachFineItem() {
        val s = BeforeTomorrow.say(facts(live = false), false)
        assertTrue(s.startsWith("Before tomorrow (Tue 6 Oct), Boss: nothing to do."), s)
        assertTrue("Paper and Zerodha both read" in s, s)
        assertTrue("ORB Fresh (paper: 12 trades, 7 won, net Rs 3,200; its test won 55%)" in s, s)
        assertTrue("2 days ago" in s && "Unrestricted" in s && "static IP" in s && "relay server answers" in s, s)
        assertTrue(s.endsWith("Facts only - I changed nothing; each step is yours."), s)
    }

    @Test fun stepsComeFirst() {
        val legs = listOf(ExpiryEve.Leg("Zerodha", "NIFTY26O0625000CE", -75, "NRML"), ExpiryEve.Leg("Paper", "NIFTY26O0624800PE", 75, "MIS"))
        val s = BeforeTomorrow.say(facts(expiring = legs, battery = false, relay = false, staticIp = false, lastBackup = null), false)
        assertTrue(s.startsWith("Before tomorrow (Tue 6 Oct), Boss: 6 things to do."), s)
        val lines = s.lines()
        assertTrue(lines[1].startsWith("✗ Zerodha login: Log in to Zerodha yourself before 09:15 tomorrow"), s)
        assertTrue("2 legs of yours expire tomorrow (Tue 6 Oct): Paper NIFTY26O0624800PE and Zerodha NIFTY26O0625000CE." in s, s)
        assertTrue("never made a backup" in s && "battery setting isn't Unrestricted" in s && "relay server isn't answering" in s, s)
        // A Monday asked on Friday: "the next trading day", not "tomorrow".
        val fri = LocalDate.of(2026, 10, 9)
        assertTrue(BeforeTomorrow.say(facts().copy(today = fri, next = LocalDate.of(2026, 10, 12)), false).startsWith("Before the next trading day (Mon 12 Oct), Boss"))
    }

    @Test fun zerodhaNotReadIsSaidNeverTakenForNothing() {
        val failed = BeforeTomorrow.say(facts(zerodhaRead = false), false)
        assertFalse("both read" in failed, failed)
        assertTrue("Zerodha's positions couldn't be read just now" in failed, failed)
        val out = BeforeTomorrow.say(facts(loggedIn = false, zerodhaRead = false), false)
        assertTrue("Zerodha isn't logged in, so its positions weren't read" in out, out)
        val withLegs = BeforeTomorrow.say(facts(zerodhaRead = false, expiring = listOf(ExpiryEve.Leg("Paper", "X", 75))), false)
        assertTrue("only Paper's are listed" in withLegs, withLegs)
    }

    @Test fun lockedLeavesOutLegsRecordsAndAmounts() {
        val s = BeforeTomorrow.say(facts(expiring = null, records = null), true)
        assertTrue("needs the phone unlocked" in s && "Their records need the phone unlocked" in s, s)
        assertFalse("Rs" in s, s)
        assertTrue("ORB Fresh" in s, s)
        assertTrue(BeforeTomorrow.say(facts(next = null), false).startsWith("I couldn't tell the next trading day"))
        assertTrue("paper only" in BeforeTomorrow.say(facts(configured = false, loggedIn = false, zerodhaRead = false), false))
    }

    @Test fun expiryEveSaysWhetherZerodhaWasRead() {
        val d = tue
        assertTrue("both read" in ExpiryEve.answer(null, d, true, true))
        val failed = ExpiryEve.answer(null, d, false, true)
        assertFalse("both read" in failed, failed)
        assertTrue("couldn't be read just now" in failed, failed)
        assertTrue("isn't logged in" in ExpiryEve.answer(null, d, false, false))
        assertTrue("isn't logged in" in ExpiryEve.answer(null, d, false))
        assertEquals("x Zerodha's positions couldn't be read just now, so only your Paper legs are listed.", ExpiryEve.answer("x", d, false, true))
        assertEquals("x", ExpiryEve.answer("x", d, true, true))
        assertEquals("x", ExpiryEve.answer("x", d, false, false))
    }
}
