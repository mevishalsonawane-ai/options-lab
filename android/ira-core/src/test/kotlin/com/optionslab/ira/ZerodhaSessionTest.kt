package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZerodhaSessionTest {
    private val now = LocalDateTime.of(2026, 10, 5, 11, 30)
    private val login = "10-05 09:02:10 [zerodha] POST /session/token -> ok (412 ms)"
    private val profileEnd = "10-05 10:42:03 [info] Zerodha TokenException on GET /user/profile: Incorrect `api_key` or `access_token`. (session ended)"
    private val positionsEnd = "10-05 10:42:03 [info] Zerodha TokenException on GET /portfolio/positions: Incorrect `api_key` or `access_token`. (session ended)"
    private val failed = "10-05 10:42:03 [zerodha] GET /portfolio/positions -> FAILED KiteError: Zerodha session ended: Incorrect `api_key` or `access_token`. (230 ms)"
    private val kept = "10-05 10:05:44 [info] Zerodha TokenException on GET /portfolio/holdings: Incorrect `api_key` or `access_token`. (the profile still answers: session kept)"

    @Test fun asks() {
        listOf("why was I logged out of Zerodha?", "why was i logged out", "why did zerodha log me out", "why did kite log me out",
            "why do I keep getting logged out of zerodha", "why did my zerodha session end", "why did my kite session expire",
            "why did my zerodha login drop", "what happened to my zerodha session", "what happened to my kite login",
            "when did my zerodha session end", "when was I logged out of zerodha", "zerodha se logout kyun hua",
            "kite logout kyon ho gaya", "mera zerodha session kyun khatam hua", "why am i getting logged out of kite",
            "why did my session expire on zerodha").forEach { assertEquals(ZerodhaSession.Asked.WHY, ZerodhaSession.asked(it), it) }
        listOf("when does my zerodha session end", "when does my zerodha login expire", "how long is my kite session valid",
            "till when is my zerodha login valid", "when will zerodha log me out").forEach { assertEquals(ZerodhaSession.Asked.UNTIL, ZerodhaSession.asked(it), it) }
        listOf("log me in to zerodha", "log me out of zerodha", "am i logged in to kite", "is zerodha connected", "zerodha login karo",
            "why is nifty down", "how do i log in to zerodha", "what is my zerodha balance", "why did my order fail",
            "logout karo zerodha se kyun nahi", "why did you exit my position", "is the market open").forEach { assertNull(ZerodhaSession.asked(it), it) }
    }

    @Test fun readsTheDiary() {
        val ev = ZerodhaSession.events(listOf("10-05 09:00:00 [mode] Paper", login, kept, profileEnd, positionsEnd, failed,
            "10-05 10:45:00 [info] Zerodha session expired: asked to log in again"), now)
        assertEquals(listOf(ZerodhaSession.Kind.LOGIN, ZerodhaSession.Kind.KEPT, ZerodhaSession.Kind.ENDED, ZerodhaSession.Kind.ENDED,
            ZerodhaSession.Kind.NOTICED), ev.map { it.kind })
        assertEquals("/portfolio/positions", ev[3].path)
        // An older record (before the reason had its own line) is read as the end.
        val old = ZerodhaSession.events(listOf(failed), now)
        assertEquals(1, old.size); assertEquals(ZerodhaSession.Kind.ENDED, old[0].kind); assertEquals("GET", old[0].method)
        // Older than a week, or last year's December read in January: as of the right year.
        assertTrue(ZerodhaSession.events(listOf("09-20 10:00:00 [zerodha] POST /session/token -> ok"), now).isEmpty())
        val jan = LocalDateTime.of(2027, 1, 2, 10, 0)
        assertEquals(2026, ZerodhaSession.events(listOf("12-31 09:00:00 [zerodha] POST /session/token -> ok"), jan).single().at.year)
    }

    @Test fun zerodhaEndedItFirstSentence() {
        val said = ZerodhaSession.answer(ZerodhaSession.Asked.WHY, listOf(login, kept, profileEnd, positionsEnd, failed), now, true, false, null)
        assertTrue(said.startsWith("Boss, Zerodha ended your session at 10:42 today: a call for your positions was refused " +
            "(Kite said: \"Incorrect API key or access token\"), and the profile check was refused too"), said)
        assertTrue("Zerodha doesn't say why" in said, said)
        assertTrue("I never log in by voice" in said, said)
        assertFalse("`" in said || "api_key" in said, said)
        // The refused call before it, that did not end it.
        assertTrue("One refused call did not end it (a call for your holdings at 10:05 today" in said, said)
        // Aloud: the headline first, in Boss's words.
        assertTrue(Aloud.say(said).startsWith("Boss, Zerodha ended your session at 10:42 today"), Aloud.say(said))
    }

    @Test fun ownLogoutAndDailyEnd() {
        val own = ZerodhaSession.answer(ZerodhaSession.Asked.WHY,
            listOf(login, "10-05 11:10:00 [zerodha] DELETE /session/token -> ok (100 ms)"), now, true, false, null)
        assertTrue(own.startsWith("Boss, you logged out of Zerodha yourself from the app at 11:10 today."), own)
        assertFalse("Zerodha doesn't say why" in own, own)
        val daily = ZerodhaSession.answer(ZerodhaSession.Asked.WHY, listOf("10-04 09:05:00 [zerodha] POST /session/token -> ok"),
            now, true, false, null)
        assertTrue(daily.startsWith("Boss, your Zerodha login from 9:05 yesterday ran out at 6:00 this morning"), daily)
        // No diary line, but the kept login time says it ran out.
        val kept = ZerodhaSession.answer(ZerodhaSession.Asked.WHY, emptyList(), now, true, false, LocalDateTime.of(2026, 10, 5, 6, 0))
        assertTrue(kept.startsWith("Boss, your Zerodha login ran out at 6:00 this morning"), kept)
        val none = ZerodhaSession.answer(ZerodhaSession.Asked.WHY, emptyList(), now, true, false, null)
        assertTrue(none.startsWith("You're not logged in to Zerodha, Boss, and I have no record of why"), none)
    }

    @Test fun loggedInNow() {
        val until = ZerodhaSession.answer(ZerodhaSession.Asked.UNTIL, listOf(login), now, true, true, LocalDateTime.of(2026, 10, 6, 6, 0))
        assertEquals("You're logged in to Zerodha now, Boss, since 9:02 today; Zerodha ends every login at 6:00 tomorrow morning.", until)
        // Logged in again after Zerodha ended it: the end is said after the headline.
        val again = ZerodhaSession.answer(ZerodhaSession.Asked.WHY,
            listOf(profileEnd, positionsEnd, "10-05 10:50:00 [zerodha] POST /session/token -> ok"), now, true, true, null)
        assertTrue(again.startsWith("You're logged in to Zerodha now, Boss, since 10:50 today; Zerodha ends every login at 6:00 tomorrow morning. " +
            "Before that, Zerodha ended your session at 10:42 today"), again)
        assertFalse("never log in by voice" in again, again)
        val calm = ZerodhaSession.answer(ZerodhaSession.Asked.WHY, listOf(login), now, true, true, null)
        assertTrue("Nothing ended it early in the last 7 days." in calm, calm)
    }

    @Test fun notLinked() {
        assertTrue(ZerodhaSession.answer(ZerodhaSession.Asked.WHY, listOf(login), now, false, false, null).startsWith("Zerodha isn't linked"))
    }

    @Test fun requestsInWords() {
        assertEquals("an order", ZerodhaSession.request("POST", "/orders/regular"))
        assertEquals("an order cancel", ZerodhaSession.request("DELETE", "/orders/regular/123"))
        assertEquals("a call for your margins", ZerodhaSession.request("GET", "/user/margins"))
        assertEquals("a price quote", ZerodhaSession.request("GET", "/quote/ltp"))
        assertEquals("a request", ZerodhaSession.request("GET", "/something/else"))
    }
}
