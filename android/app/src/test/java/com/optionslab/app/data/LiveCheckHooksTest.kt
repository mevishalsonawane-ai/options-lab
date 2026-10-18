package com.optionslab.app.data

import com.optionslab.app.ira.IraHub
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

/**
 * The Live check's small hooks in the shared code (18 Oct): the watch's "last checked" stamps set by the paper pass and the
 * stops pass, the stream's tick counter and its on-purpose drop with no connection open, the clock's latest offset, and
 * "Jarvis, run the live check" (never answered on a locked phone).
 */
class LiveCheckHooksTest : RobolectricTest() {
    @Before fun up() { assumeFalse("IraGoldAlgo has no Live check", com.optionslab.app.BuildConfig.GOLD) }

    @After fun down() { assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList()) }

    @Test fun thePaperPassAndTheStopsPassStampWhenTheyRan() {
        assertEquals("a fresh process: never checked", 0L, WatchClock.paperAt)
        assertEquals(0L, WatchClock.stopsAt)
        val t0 = System.currentTimeMillis()
        runBlocking { Paper.tick() }
        assertTrue(WatchClock.paperAt >= t0)
        assertEquals("the stops pass has not run", 0L, WatchClock.stopsAt)
        runBlocking { Protections.tick() }
        assertTrue(WatchClock.stopsAt >= t0)
        WatchClock.resetForTest()
        assertEquals(0L, WatchClock.paperAt)
    }

    @Test fun withNoConnectionTheStreamCountsNoTicksAndCannotBeDropped() {
        assertEquals(0L, KiteStream.ticksNow())
        assertFalse("nothing open to drop", KiteStream.dropForCheck())
        // Live by the test switch, but no socket: still nothing to drop (the loop is never touched).
        KiteStream.liveForTest(true)
        try { assertFalse(KiteStream.dropForCheck()) } finally { KiteStream.liveForTest(false) }
        assertEquals(KiteStream.Status.OFF, KiteStream.status.value)
    }

    @Test fun theClockOffsetPrefersZerodhasServerClock() {
        OrderTiming.forget()
        assertNull("nothing measured yet", OrderTiming.latestClockOffset())
        val now = System.currentTimeMillis()
        // A tick stamped 1 s before it reached the phone (plus the stamp's half second): the phone is 500 ms ahead.
        OrderTiming.clock(now, now / 1000 - 1)
        val tick = OrderTiming.latestClockOffset()!!
        assertEquals("the exchange's tick stamps, network delay included", tick.second)
        assertEquals(now - ((now / 1000 - 1) * 1000 + 500), tick.first)
        // A Zerodha reply dated in the same second as its round trip's middle: the server clock wins.
        OrderTiming.serverClock(now, now + 200, (now + 100) / 1000)
        val server = OrderTiming.latestClockOffset()!!
        assertEquals("Zerodha's server clock", server.second)
        assertEquals(now + 100 - ((now + 100) / 1000 * 1000 + 500), server.first)
        OrderTiming.forget()
    }

    @Test fun beforeTheDropAnythingUnknownCountsAsOpen() {
        // Jarvis's own paper trades unreadable: counted as open, so the check never drops the stream.
        com.optionslab.app.security.SecurePrefs.put("jarvis.solo.trades", "{not json")
        if (com.optionslab.app.BuildConfig.JARVIS) assertTrue(runBlocking { LiveSelfTest.Real.booksOpen() })
        // Readable and closed, but no Zerodha session to read the live books afresh: still counted as open.
        com.optionslab.app.security.SecurePrefs.put("jarvis.solo.trades", org.json.JSONArray().put(org.json.JSONObject().put("c", true)).toString())
        assertFalse(Broker.loggedIn)
        assertTrue(runBlocking { LiveSelfTest.Real.booksOpen() })
    }

    private fun ask(q: String, until: (String) -> Boolean = { true }): String {
        val start = IraHub.state.value.messages.maxOfOrNull { it.id } ?: 0L
        IraHub.ask(q)
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            val said = IraHub.state.value.messages.filter { it.fromIra && it.id > start }.map { it.text }
            said.lastOrNull()?.takeIf(until)?.let { return it }
            Thread.sleep(10)
        }
        return IraHub.state.value.messages.last().text
    }

    @Test fun jarvisRunsTheLiveCheckOnlyOnAnUnlockedPhone() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        val km = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
        km.setKeyguardLocked(true)
        try {
            assertEquals("Unlock the phone for that, Boss.", ask("run the live check"))
            assertTrue("nothing ran", LiveSelfTest.last.isEmpty())
        } finally { km.setKeyguardLocked(false) }
        // Zerodha is not set up here and the test app's market is closed: the check runs and says what failed, by name.
        val done = ask("live check karo") { it.startsWith("Live check done") }
        assertTrue(done, done.startsWith("Live check done, Boss: 1 of 4 passed, 3 failed, "))
        assertTrue(done, done.contains("Failed: Session (Zerodha is not set up on this phone)"))
        assertTrue("its steps are kept for the card", LiveSelfTest.last.isNotEmpty())
        assertFalse(done, Regex("\\d{9,}|₹\\s*\\d").containsMatchIn(done))
        val again = ask("do the self-test") { it.startsWith("Live check done") }
        assertTrue(again, again.startsWith("Live check done, Boss: "))
        // A question about it answers from the last run and never starts another (a run drops the stream).
        val runs = Diag.lines().count { it.contains("[live-check] summary: ") }
        val found = ask("what did the live check find") { it.startsWith("Live check done") }
        assertTrue(found, found.contains("Failed: Session (Zerodha is not set up on this phone)"))
        Thread.sleep(300)
        assertEquals("no new run", runs, Diag.lines().count { it.contains("[live-check] summary: ") })
    }
}
