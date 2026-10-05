package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestsTest {
    private fun v(id: Long, title: String, lapses: Long? = null, asked: Long = 0L, kind: Requests.Kind = Requests.Kind.COMMAND,
                  symbol: String? = null) =
        Requests.RequestView(id, kind, title, title, null, Requests.Venue.NONE, asked, lapses, symbol)

    @Test fun titlesAreShortAndWhyDropsTheConfirmTail() {
        assertEquals("stop ORB", Requests.title("stop ORB"))
        assertEquals("buy 1 lot of the NIFTY call at the money", Requests.title("buy 1 lot of the NIFTY call at the money, nearest expiry, with a 15% stop"))
        assertEquals(60, Requests.title("x".repeat(100)).length)
        assertEquals("ORB has lost 6 of its last 8 trades.", Requests.why("ORB has lost 6 of its last 8 trades. Tap Confirm to stop it."))
        assertNull(Requests.why("Tap Confirm to stop ORB."))
        assertNull(Requests.why(null))
        assertEquals("News: RBI cut rates.", Requests.why("News: RBI cut rates. The candles agree. Approve or reject."))
    }

    @Test fun theChatAndTheVoiceNameTheRequest() {
        assertEquals("New request: stop ORB — see Requests.", Requests.chatLine("stop ORB"))
        assertEquals("Request: stop ORB. Yes or no?", Requests.spoken("stop ORB"))
        assertEquals("Request: stop ORB. Boss, ORB is losing. Yes or no?", Requests.spoken("stop ORB", " Boss, ORB is losing. Yes or no?"))
        assertEquals("Requests 2", Requests.badge(2))
        assertEquals("Requests", Requests.badge(0))
    }

    @Test fun aLockedPhoneHearsOnlyTheCount() {
        assertEquals("Jarvis: 1 request waiting", Requests.lockedLine(1))
        assertEquals("Jarvis: 3 requests waiting", Requests.lockedLine(3))
        assertEquals("Jarvis", Requests.lockedLine(0))
        assertFalse(Requests.lockedLine(2).any { it.isDigit() && it != '2' })
    }

    @Test fun countdownAndAskedTexts() {
        assertEquals("Lapses in 10:00", Requests.lapseText(600_000, 0))
        assertEquals("Lapses in 9:05", Requests.lapseText(545_000, 0))
        assertEquals("Lapses in 0:01", Requests.lapseText(500, 0))
        assertEquals("Lapsed", Requests.lapseText(1_000, 1_000))
        assertEquals("Does not lapse", Requests.lapseText(null, 0))
        assertEquals("Asked just now", Requests.askedText(0, 59_000))
        assertEquals("Asked 4 min ago", Requests.askedText(0, 4 * 60_000 + 5_000))
        assertEquals("Asked 2 h ago", Requests.askedText(0, 2 * 3_600_000L))
    }

    @Test fun soonestLapseFirstAndLapsedOnesGo() {
        val list = listOf(v(1, "a", lapses = 900), v(2, "b", lapses = null), v(3, "c", lapses = 300), v(4, "d", lapses = 300, asked = -5), v(5, "e", lapses = 50))
        assertEquals(listOf(5L, 4L, 3L, 1L, 2L), Requests.sorted(list).map { it.id })
        assertEquals(listOf(4L, 3L, 1L, 2L), Requests.shown(list, now = 100, gold = false).map { it.id })
        assertTrue(Requests.shown(list, now = 0, gold = true).isEmpty(), "GOLD only talks: nothing actionable")
    }

    @Test fun recentKeepsTheNewestOnce() {
        var r = emptyList<Requests.Recent>()
        for (i in 1..12) r = Requests.keep(r, Requests.Recent(v(i.toLong(), "r$i"), Requests.Outcome.LAPSED, i.toLong()))
        assertEquals(Requests.RECENT_KEEP, r.size)
        assertEquals(12L, r.first().view.id)
        r = Requests.keep(r, Requests.Recent(v(5, "r5"), Requests.Outcome.APPROVED, 99))
        assertEquals(1, r.count { it.view.id == 5L })
        assertEquals(Requests.Outcome.FAILED, Requests.outcomeOf("Not protected: no price"))
        assertEquals(Requests.Outcome.FAILED, Requests.outcomeOf(null))
        assertEquals(Requests.Outcome.APPROVED, Requests.outcomeOf("Stopped ORB."))
    }

    @Test fun aButtonAnswersOnlyItsOwnRequest() {
        val waiting = setOf(10L, 11L, 12L)
        assertEquals(11L, Requests.tapTarget(11L, waiting))
        assertNull(Requests.tapTarget(13L, waiting), "answered or lapsed: nothing, never another")
        assertNull(Requests.tapTarget(11L, emptySet()))
        for (b in waiting) assertEquals(b, Requests.tapTarget(b, waiting))
    }

    @Test fun offerButtonsOnlyUnderTheNewestMessageThatEndsWithTheOffer() {
        val line = "BankNifty's levels next, Boss?"
        assertTrue(Requests.offerButtons("Nifty is at 24,100. $line", true, line, false))
        assertFalse(Requests.offerButtons("Nifty is at 24,100. $line", false, line, false), "superseded")
        assertFalse(Requests.offerButtons("Nifty is at 24,100. $line", true, null, false), "offer ended")
        assertFalse(Requests.offerButtons("Nifty is at 24,100. $line", true, line, true), "locked phone")
        assertFalse(Requests.offerButtons("Something else.", true, line, false))
    }

    @Test fun oneRequestWaitingAYesIsForIt() {
        assertEquals(Requests.Pick.Pass, Requests.pick("yes", true, 1, null, listOf(v(1, "stop ORB"))))
        // A strategy waiting (approved on the screen) does not count.
        assertEquals(Requests.Pick.Pass, Requests.pick("yes", true, 1, null, listOf(v(1, "stop ORB"), v(2, "Breakout", kind = Requests.Kind.STRATEGY))))
    }

    @Test fun twoWaitingABareYesPicksNone() {
        val two = listOf(v(1, "stop ORB"), v(2, "set a stop on NIFTY24OCT24000CE at 120.00", symbol = "NIFTY24OCT24000CE"))
        val p = Requests.pick("yes", true, 2, null, two)
        assertIs<Requests.Pick.Ambiguous>(p)
        assertEquals("You have 2 requests, Boss — open Requests, or say which one.", p.line)
        assertIs<Requests.Pick.Ambiguous>(Requests.pick("no", false, 2, null, two))
        // Not a yes or no: left as before (no request picked).
        assertEquals(Requests.Pick.Pass, Requests.pick("what is nifty doing", null, 2, null, two))
        // Named: asked again by itself, and only its next plain yes answers it.
        val r = Requests.pick("yes orb", true, 2, null, two)
        assertIs<Requests.Pick.Reask>(r)
        assertEquals(1L, r.id)
        assertEquals("Request: stop ORB. Just yes or no?", r.line)
        assertEquals(Requests.Pick.Pass, Requests.pick("yes", true, 1, 1, two))
        // Focused on one, but Jarvis since asked about another: not taken.
        assertIs<Requests.Pick.Ambiguous>(Requests.pick("yes", true, 2, 1, two))
        // A focus on one that is gone: not taken.
        assertIs<Requests.Pick.Ambiguous>(Requests.pick("yes", true, 9, 9, two))
    }

    @Test fun aNameSharedByTwoPicksNone() {
        val two = listOf(v(1, "stop ORB 15"), v(2, "stop ORB 30"))
        assertNull(Requests.named("yes orb", two))
        assertEquals(2L, Requests.named("the 30 one", two)?.id)
        assertIs<Requests.Pick.Ambiguous>(Requests.pick("yes orb", true, 1, null, two))
    }
}
