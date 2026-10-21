package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 21 Oct (Boss's 11:09 diagnostics): an arm's status line keeps the day it was written ([StatusMap]); an earlier day's is marked. */
class StatusMapTest : RobolectricTest() {
    private fun on(d: LocalDate) { Market.testClock = java.time.Clock.fixed(d.atTime(11, 9).atZone(IST).toInstant(), IST) }
    private val tue = LocalDate.of(2026, 10, 20)
    private val wed = LocalDate.of(2026, 10, 21)

    @After fun down() { Market.testClock = null }

    @Test fun aLineWrittenYesterdayIsMarkedAndOneWrittenAgainTodayIsNot() {
        on(tue)
        val m = StatusMap()
        m["r52|NIFTY"] = "13:45 put break missed"
        m["mcx_us_silver"] = "waiting for 09:05"
        assertEquals("13:45 put break missed", m.shown().getValue("r52|NIFTY"))
        on(wed)
        assertEquals("from 20 Oct: 13:45 put break missed", m.shown().getValue("r52|NIFTY"))
        // The arms' "write only on a change" guard: the same words from yesterday are written (and stamped) again today.
        assertFalse(m.sameToday("mcx_us_silver", "waiting for 09:05"))
        m["mcx_us_silver"] = "waiting for 09:05"
        assertTrue(m.sameToday("mcx_us_silver", "waiting for 09:05"))
        assertEquals("waiting for 09:05", m.shown().getValue("mcx_us_silver"))
        // A sticky line is shown as it is.
        assertEquals("13:45 put break missed", m.shown { k, _ -> k == "r52|NIFTY" }.getValue("r52|NIFTY"))
    }

    @Test fun theDaysSurviveASaveAndACopyAndAnOlderBookTakesItsSavedDay() {
        on(tue)
        val m = StatusMap()
        m["a"] = "x"
        val o = JSONObject().put("status", JSONObject().put("a", "x"))
        m.writeTo(o)
        on(wed)
        val back = StatusMap().apply { readFrom(o, 0L) }
        assertEquals("from 20 Oct: x", back.shown().getValue("a"))
        assertEquals("from 20 Oct: x", back.copyMap().shown().getValue("a"))
        // A book from before this update (no days kept): its lines take the day the book was last saved.
        val old = JSONObject().put("status", JSONObject().put("b", "y"))
        val saved = tue.atTime(15, 0).atZone(IST).toInstant().toEpochMilli()
        assertEquals("from 20 Oct: y", StatusMap().apply { readFrom(old, saved) }.shown().getValue("b"))
        // Removed lines forget their day; putAll stamps today.
        back.remove("a"); assertFalse("a" in back.days)
        back.putAll(mapOf("c" to "z")); assertEquals(wed, back.days["c"])
    }
}
