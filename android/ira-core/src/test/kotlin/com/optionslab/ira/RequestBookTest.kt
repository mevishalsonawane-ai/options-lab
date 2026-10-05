package com.optionslab.ira

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestBookTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 10, 5, 11, 30).atZone(zone).toInstant().toEpochMilli()
    private fun ago(min: Long) = now - min * 60_000

    private fun view(id: Long, kind: Requests.Kind, title: String, what: String, venue: Requests.Venue, asked: Long, lapses: Long?) =
        Requests.RequestView(id, kind, title, what, null, venue, asked, lapses)

    @Test fun asksAreRead() {
        val waiting = listOf("what requests are waiting", "any requests", "do I have any requests", "what's waiting for my approval",
            "anything waiting for my approval", "what needs my approval", "what do I need to approve", "how many requests are pending",
            "pending requests", "koi request hai", "kitni requests hain", "jarvis what requests are waiting", "show me my requests",
            "anything for me to approve", "kya approve karna hai")
        for (s in waiting) assertEquals(false, RequestBook.asked(s)?.done, s)
        val done = mapOf(
            "what did I approve today" to Requests.Outcome.APPROVED, "what have I approved" to Requests.Outcome.APPROVED,
            "what did I decline today" to Requests.Outcome.DECLINED, "which requests did I reject" to Requests.Outcome.DECLINED,
            "what requests lapsed" to Requests.Outcome.LAPSED, "maine aaj kya approve kiya" to Requests.Outcome.APPROVED,
            "what happened to my requests" to null, "recent requests" to null,
        )
        for ((s, o) in done) {
            val a = RequestBook.asked(s)
            assertEquals(true, a?.done, s)
            assertEquals(o, a?.outcome, s)
        }
        assertEquals(true, RequestBook.asked("what did I approve today")?.today)
        assertEquals(false, RequestBook.asked("what have I approved")?.today)
        for (s in listOf("approve it", "approve the request", "decline all requests", "yes", "where is the requests panel",
            "how do I approve a request", "should I approve the orb stop", "why did you ask me to approve", "what pending orders do I have",
            "buy nifty 25000 call", "how is nifty", "what is my p&l", "what is a request"))
            assertNull(RequestBook.asked(s), s)
    }

    @Test fun waitingLeadsWithTheCountAndNames() {
        val list = listOf(
            view(1, Requests.Kind.COMMAND, "stop ORB", "stop the ORB strategy", Requests.Venue.NONE, ago(4), now + 5 * 60_000),
            view(2, Requests.Kind.TRADE, "buy Nifty 25000 CE", "buy 1 lot of Nifty 25000 CE at market", Requests.Venue.ZERODHA, ago(2), now + 9 * 60_000),
            view(3, Requests.Kind.GUARD, "old one", "set a stop", Requests.Venue.PAPER, ago(30), now - 1000),
        )
        val s = RequestBook.waiting(list, now).lines()
        assertEquals("2 requests waiting, Boss: stop ORB and buy Nifty 25000 CE.", s[0])
        assertTrue(s[1].startsWith("1. Command - stop the ORB strategy. No order. Asked 4 min ago. Lapses in 5:00."), s[1])
        assertTrue(s[2].contains("Zerodha · real money") && s[2].endsWith("needs your fingerprint."), s[2])
        assertTrue(s.last().contains("approves nothing"))
        assertTrue(s.none { "old one" in it })
        assertTrue(RequestBook.waiting(emptyList(), now).startsWith("No requests waiting, Boss."))
    }

    @Test fun doneByOutcomeAndToday() {
        val a = view(1, Requests.Kind.COMMAND, "stop ORB", "stop the ORB strategy", Requests.Venue.NONE, ago(60), null)
        val b = view(2, Requests.Kind.TRADE, "buy Nifty CE", "buy Nifty CE", Requests.Venue.ZERODHA, ago(50), null)
        val c = view(3, Requests.Kind.GUARD, "set a stop", "set a stop at 120", Requests.Venue.PAPER, ago(3000), null)
        val recent = listOf(
            Requests.Recent(b, Requests.Outcome.FAILED, ago(40)),
            Requests.Recent(a, Requests.Outcome.APPROVED, ago(55)),
            Requests.Recent(c, Requests.Outcome.DECLINED, ago(2900)),
        )
        val approved = RequestBook.done(RequestBook.Asked(true, Requests.Outcome.APPROVED, today = true), recent, 1, now, zone).lines()
        assertEquals("You approved 2 requests today, Boss: buy Nifty CE and stop ORB.", approved[0])
        assertTrue(approved[1].startsWith("10:50 Approved, but it failed - buy Nifty CE"), approved[1])
        assertTrue(approved.last().startsWith("1 still waiting"))
        val all = RequestBook.done(RequestBook.Asked(true), recent, 0, now, zone).lines()
        assertEquals("3 requests answered or ended, Boss: 1 approved, 1 declined, 1 failed.", all[0])
        assertTrue(all[3].startsWith("2026-10-03"), all[3])
        val none = RequestBook.done(RequestBook.Asked(true, Requests.Outcome.DECLINED, today = true), recent, 0, now, zone)
        assertTrue(none.startsWith("No request declined today, Boss"), none)
    }

    @Test fun lockedSaysOnlyHowMany() {
        assertEquals("2 requests waiting, Boss - unlock the phone for what they are.", RequestBook.locked(2))
        assertEquals("No requests waiting, Boss.", RequestBook.locked(0))
        assertTrue("none" in RequestBook.READ_FAILED)
    }
}
