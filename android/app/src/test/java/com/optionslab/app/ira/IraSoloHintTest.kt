package com.optionslab.app.ira

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 22 Nov 2026 (B6 review: the event-driven checks' thread read and parsed Solo's whole book from the vault on every snapshot): Solo's
 * "holding?" for that thread comes from memory ([IraSolo.holdingQuick]) - the book as last read or written; not read yet, it is read
 * in the background and counts as held meanwhile. A read that fails never changes it.
 */
class IraSoloHintTest : RobolectricTest() {
    private val key = "jarvis.solo.trades"

    private fun trade(closed: Boolean) = JSONObject().put("d", "2026-11-20").put("m", "NIFTY").put("s", "NIFTY26NOV24000CE").put("c", true)
        .put("q", 75).put("e", 100.0).put("em", 720).put("i", 24_000.0).put("lv", 23_950.0).put("tg", 120.0).put("w", "test").put("x", closed)

    @Before fun up() { IraSolo.forgetHintForTest() }
    @After fun down() { SecurePrefs.countedThread = null; IraSolo.forgetHintForTest() }

    private fun awaitTrue(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(5) }
        throw AssertionError("timed out waiting for $what")
    }

    /** Second review: the hint and the start-up's strict read agree - both by the closed flag ("x"), never the call / put side ("c"). */
    @Test fun theHintAndTheStrictReadAgreeOnAClosedPutAndAnOpenCall() {
        val put = trade(closed = true).put("c", false)
        SecurePrefs.put(key, JSONArray().put(put).toString())
        assertEquals(false, IraSolo.holdingOrThrow())
        assertEquals(false, IraSolo.holding())
        assertEquals(false, IraSolo.holdingHint)
        SecurePrefs.put(key, JSONArray().put(put).put(trade(closed = false)).toString())
        assertEquals(true, IraSolo.holdingOrThrow())
        assertEquals(true, IraSolo.holding())
        assertEquals(true, IraSolo.holdingHint)
    }

    @Test fun theHintAnswersFromMemoryAndFollowsTheBook() {
        SecurePrefs.put(key, JSONArray().put(trade(closed = true)).toString())
        assertNull("nothing read yet in this process", IraSolo.holdingHint)
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        assertTrue("not known yet: counts as held", IraSolo.holdingQuick())
        assertEquals("no vault read on the asking thread", 0L, SecurePrefs.readsForTest.get())
        SecurePrefs.countedThread = null
        // Read in the background: the closed trade is not held.
        awaitTrue("the background read") { IraSolo.holdingHint == false }
        SecurePrefs.countedThread = Thread.currentThread()
        assertEquals(false, IraSolo.holdingQuick())
        assertEquals(0L, SecurePrefs.readsForTest.get())
        SecurePrefs.countedThread = null
        // An open trade, read by the book's own reader: the hint follows.
        SecurePrefs.put(key, JSONArray().put(trade(closed = true)).put(trade(closed = false)).toString())
        assertTrue(IraSolo.holding())
        assertEquals(true, IraSolo.holdingHint)
        // A read that fails (an unreadable book) leaves the last answer, never "nothing held".
        SecurePrefs.put(key, "{not json")
        assertEquals("the everyday read still says nothing", false, IraSolo.holding())
        assertEquals(true, IraSolo.holdingHint)
        assertTrue(IraSolo.holdingQuick())
    }
}
