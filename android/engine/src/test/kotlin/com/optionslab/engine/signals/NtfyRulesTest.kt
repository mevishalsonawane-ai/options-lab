package com.optionslab.engine.signals

import com.optionslab.engine.Right
import com.optionslab.engine.signals.NtfyParser.Expiry
import com.optionslab.engine.signals.NtfyParser.StrikeRule
import com.optionslab.engine.signals.NtfyParser.Trig
import com.optionslab.engine.signals.NtfyRules.Exit
import com.optionslab.engine.signals.NtfyRules.Phase
import com.optionslab.engine.signals.NtfyRules.Settings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ntfy alerts' rules (25 Oct): read once, stale, market hours, expiry day, strike and expiry choice, stops, the wire. */
class NtfyRulesTest {
    private val day = LocalDate.of(2026, 10, 26)
    private fun at(h: Int, m: Int, s: Int = 0) = day.atTime(h, m, s)
    private val s = Settings()

    @Test fun readOnceSurvivesDuplicatesRestartsAndOrder() {
        val r = NtfyRules.ReadOnce()
        assertTrue(r.mark("a")); assertEquals("a", r.last)
        assertFalse(r.mark("a"), "a duplicate delivery")
        assertFalse(r.mark(" "), "no id")
        assertTrue(r.mark("c")); assertTrue(r.mark("b"), "out of order: still read once")
        assertEquals("b", r.last)
        // A restart: the kept ids and last id come back, and nothing is read twice.
        val again = NtfyRules.ReadOnce(r.ids, r.last)
        assertTrue(again.seen("a") && again.seen("b") && again.seen("c"))
        assertFalse(again.mark("c"))
        assertEquals("b", again.last)
        // Only the newest are kept.
        val small = NtfyRules.ReadOnce(keep = 2)
        small.mark("1"); small.mark("2"); small.mark("3")
        assertEquals(listOf("2", "3"), small.ids)
    }

    @Test fun staleMessages() {
        assertNull(NtfyRules.stale(at(10, 0), at(10, 4), 5))
        assertTrue(NtfyRules.stale(at(10, 0), at(10, 6), 5)!!.contains("6 minutes old"))
        assertTrue(NtfyRules.stale(at(10, 0).minusDays(1), at(9, 20), 5)!!.contains("earlier day"))
        assertTrue(NtfyRules.stale(at(10, 10), at(10, 0), 5)!!.contains("future"))
        assertNull(NtfyRules.stale(at(10, 1), at(10, 0), 5), "a minute of clock skew is fine")
    }

    @Test fun marketHoursHolidaysAndExpiryDay() {
        assertTrue(NtfyRules.marketRefusal(at(10, 0), false, false)!!.contains("market closed"))
        assertTrue(NtfyRules.marketRefusal(at(9, 0), true, false)!!.contains("before the market"))
        assertNull(NtfyRules.marketRefusal(at(9, 15), true, false))
        assertNull(NtfyRules.marketRefusal(at(15, 10), true, false))
        assertTrue(NtfyRules.marketRefusal(at(15, 10), true, true)!!.contains("15:05"))
        assertTrue(NtfyRules.marketRefusal(at(15, 15), true, false)!!.contains("15:15"))
        assertEquals(LocalTime.of(15, 5), NtfyRules.squareOff(true))
        assertEquals(LocalTime.of(15, 15), NtfyRules.squareOff(false))
    }

    @Test fun theEntryWindow() {
        assertEquals(Phase.OPEN, NtfyRules.phase(at(10, 0), at(10, 4), s, false))
        assertEquals(Phase.LATE, NtfyRules.phase(at(10, 0), at(10, 5), s, false))
        assertEquals(Phase.LATE, NtfyRules.phase(at(10, 0), at(10, 1).plusDays(1), s, false))
        // 25 Oct (review): a message sent before the open is never in its window (it was bought at 09:15, however old).
        assertEquals(Phase.LATE, NtfyRules.phase(at(9, 10), at(9, 15), s, false))
        assertEquals(Phase.LATE, NtfyRules.phase(at(8, 0), at(9, 15), s, false))
        assertEquals(Phase.OPEN, NtfyRules.phase(at(9, 15), at(9, 16), s, false))
        // Never past the square-off (15:05 on expiry day).
        assertEquals(Phase.LATE, NtfyRules.phase(at(15, 3), at(15, 5), s, true))
        assertEquals(Phase.OPEN, NtfyRules.phase(at(15, 3), at(15, 5), s, false))
    }

    @Test fun aMessageSentBeforeTheOpenOrStaleIsNeverBought() {
        assertEquals("not traded: sent before the open (09:15)", NtfyRules.entryRefusal(at(8, 0), at(9, 15), 5))
        assertEquals("not traded: sent before the open (09:15)", NtfyRules.entryRefusal(at(9, 14), at(9, 15), 5))
        assertTrue(NtfyRules.entryRefusal(at(9, 20), at(9, 30), 5)!!.startsWith("stale, not traded: 10 minutes old"))
        assertTrue(NtfyRules.entryRefusal(at(15, 0).minusDays(1), at(9, 20), 5)!!.contains("earlier day"))
        assertNull(NtfyRules.entryRefusal(at(9, 15), at(9, 16), 5))
        assertNull(NtfyRules.entryRefusal(at(10, 0), at(10, 5), 5))
    }

    @Test fun triggers() {
        assertTrue(NtfyRules.triggered(Trig.MARKET, null, 100.0, null, null))
        assertFalse(NtfyRules.triggered(Trig.MARKET, null, null, null, null))
        assertFalse(NtfyRules.triggered(Trig.MARKET, null, 0.0, null, null))
        assertTrue(NtfyRules.triggered(Trig.ZONE, null, 100.0, null, null), "no level: at the market")
        assertTrue(NtfyRules.triggered(Trig.ABOVE, 100.0, 101.0, null, null))
        assertTrue(NtfyRules.triggered(Trig.ABOVE, 100.0, 95.0, 100.0, 90.0))
        assertFalse(NtfyRules.triggered(Trig.ABOVE, 100.0, 95.0, 99.0, 90.0))
        assertFalse(NtfyRules.triggered(Trig.ABOVE, 100.0, null, null, null))
        assertTrue(NtfyRules.triggered(Trig.ZONE, 100.0, 101.9, null, null))
        assertFalse(NtfyRules.triggered(Trig.ZONE, 100.0, 103.0, null, null))
        assertTrue(NtfyRules.triggered(Trig.ZONE, 100.0, 103.0, 104.0, 99.0))
        assertFalse(NtfyRules.triggered(Trig.ZONE, 100.0, 110.0, 112.0, null))
        assertTrue(NtfyRules.triggered(Trig.UPTO, 100.0, 105.0, null, null))
        assertFalse(NtfyRules.triggered(Trig.UPTO, 100.0, 105.1, null, null), "never chased past 5%")
        assertFalse(NtfyRules.triggered(Trig.UPTO, 100.0, null, null, null))
        assertTrue(NtfyRules.triggered(Trig.UPTO, 100.0, 80.0, null, null))
    }

    @Test fun theStopIsMandatory() {
        assertEquals(90.0 to false, NtfyRules.stop(90.0, null, 100.0, s))
        assertEquals(80.0 to true, NtfyRules.stop(110.0, null, 100.0, s), "a stop above the fill: ours")
        assertEquals(80.0 to true, NtfyRules.stop(null, null, 100.0, s))
        assertEquals(75.0 to false, NtfyRules.stop(null, 25.0, 100.0, s))
        assertEquals(80.0 to true, NtfyRules.stop(null, 120.0, 100.0, s))
        assertEquals(0.05 to true, NtfyRules.stop(null, null, 0.05, s), "never below a tick")
        assertEquals(180.0, NtfyRules.target(180.0, 100.0))
        assertEquals(140.05, NtfyRules.target(90.0, 100.0), "a target below the fill: ours")
        assertEquals(140.05, NtfyRules.target(null, 100.0))
        assertEquals(1, NtfyRules.halfLots(2)); assertEquals(0, NtfyRules.halfLots(1))
        assertEquals(1, NtfyRules.lotsFor(null, s)); assertEquals(1, NtfyRules.lotsFor(4, s))
        assertEquals(3, NtfyRules.lotsFor(3, s.copy(lots = 5))); assertEquals(1, NtfyRules.lotsFor(0, s.copy(lots = 5)))
        assertEquals(12.3, NtfyRules.tickDown(12.34), 1e-9)
    }

    @Test fun dayLimits() {
        assertNull(NtfyRules.refusal(2, -100.0, s))
        assertTrue(NtfyRules.refusal(3, 0.0, s)!!.contains("3 trades today"))
        assertTrue(NtfyRules.refusal(0, -3_000.0, s)!!.contains("₹3,000"))
        val c = Settings(lots = 9, staleMin = 0, maxPerDay = 50, lossCap = 10.0).clean()
        assertEquals(Settings(lots = 5, staleMin = 1, maxPerDay = 10, lossCap = 500.0), c)
        assertEquals(Exit.HALF_COST, Exit.of("half")); assertEquals(Exit.T1_ALL, Exit.of(null))
    }

    @Test fun strikeRule() {
        val k = listOf(24400.0, 24450.0, 24500.0, 24550.0, 24600.0)
        assertEquals(24500.0, NtfyRules.strikeFor(k, 24510.0, Right.CE, StrikeRule.ATM))
        assertEquals(24450.0, NtfyRules.strikeFor(k, 24475.0, Right.CE, StrikeRule.ATM), "a tie: the lower")
        assertEquals(24450.0, NtfyRules.strikeFor(k, 24510.0, Right.CE, StrikeRule.ITM1))
        assertEquals(24550.0, NtfyRules.strikeFor(k, 24510.0, Right.CE, StrikeRule.OTM1))
        assertEquals(24550.0, NtfyRules.strikeFor(k, 24510.0, Right.PE, StrikeRule.ITM1))
        assertEquals(24450.0, NtfyRules.strikeFor(k, 24510.0, Right.PE, StrikeRule.OTM1))
        assertNull(NtfyRules.strikeFor(k, 24700.0, Right.CE, StrikeRule.OTM1), "past the list's edge")
        assertNull(NtfyRules.strikeFor(emptyList(), 24500.0, Right.CE, StrikeRule.ATM))
        assertNull(NtfyRules.strikeFor(k, null, Right.CE, StrikeRule.ATM))
        assertNull(NtfyRules.strikeFor(k, 0.0, Right.CE, StrikeRule.ATM))
    }

    @Test fun expiryChoice() {
        val l = listOf(day.minusDays(1), day.plusDays(2), day.plusDays(9), day.plusDays(2))
        assertEquals(day.plusDays(2), NtfyRules.expiryFor(l, day, Expiry.Nearest))
        assertEquals(day.plusDays(9), NtfyRules.expiryFor(l, day, Expiry.Next))
        assertEquals(day.plusDays(9), NtfyRules.expiryFor(l, day, Expiry.On(day.plusDays(9))))
        assertNull(NtfyRules.expiryFor(l, day, Expiry.On(day.plusDays(3))))
        assertNull(NtfyRules.expiryFor(l, day, Expiry.On(day.minusDays(1))), "a past expiry")
        assertEquals(day, NtfyRules.expiryFor(listOf(day), day, Expiry.Nearest), "expiry day itself")
        assertNull(NtfyRules.expiryFor(emptyList(), day, Expiry.Nearest))
    }

    @Test fun prefix() {
        assertEquals("NIFTY CE", NtfyRules.afterPrefix("NIFTY CE", ""))
        assertEquals(" NIFTY CE", NtfyRules.afterPrefix("  ira: NIFTY CE", "IRA:"))
        assertNull(NtfyRules.afterPrefix("NIFTY CE", "ira:"))
        assertNull(NtfyRules.afterPrefix("ir", "ira:"))
    }

    @Test fun indexLevels() {
        assertEquals("index stop", NtfyRules.indexExit(Right.CE, 24390.0, 24400.0, 24600.0))
        assertEquals("index target", NtfyRules.indexExit(Right.CE, 24600.0, 24400.0, 24600.0))
        assertNull(NtfyRules.indexExit(Right.CE, 24500.0, 24400.0, 24600.0))
        assertEquals("index stop", NtfyRules.indexExit(Right.PE, 24600.0, 24600.0, 24400.0))
        assertEquals("index target", NtfyRules.indexExit(Right.PE, 24399.0, 24600.0, 24400.0))
        assertNull(NtfyRules.indexExit(Right.PE, 24500.0, null, null))
        assertNull(NtfyRules.indexExit(Right.PE, null, 24600.0, 24400.0))
    }

    @Test fun theWire() {
        val e = NtfyRules.event("""{"id":"L6kU8hQ2kMhG","time":1792873217,"expires":1792916417,"event":"message","topic":"x","title":"T","message":"M","priority":3,"tags":["a"]}""")
        assertNotNull(e)
        assertTrue(e.isMessage); assertEquals("L6kU8hQ2kMhG", e.id); assertEquals("T", e.title); assertEquals("M", e.message)
        assertEquals(LocalDateTime.of(2026, 10, 25, 1, 50, 17), e.at())
        assertFalse(NtfyRules.event("""{"id":"k1","time":1,"event":"keepalive","topic":"x"}""")!!.isMessage)
        assertFalse(NtfyRules.event("""{"id":"o1","time":1,"event":"open","topic":"x"}""")!!.isMessage)
        assertFalse(NtfyRules.Event("", 1, "message", "m", null).isMessage)
        assertNull(NtfyRules.event(""))
        assertNull(NtfyRules.event("not json"))
        assertNull(NtfyRules.event("[1]"))
        assertNull(NtfyRules.event("""{"time":1,"event":"message"}"""))
        assertNull(NtfyRules.event("""{"id":"a","event":"message"}"""))
        assertNull(NtfyRules.event("""{"id":"a","time":1}"""))
        assertTrue(NtfyRules.topicOk("a_long-Random1"))
        assertFalse(NtfyRules.topicOk("has space")); assertFalse(NtfyRules.topicOk("")); assertFalse(NtfyRules.topicOk("x".repeat(65)))
        assertFalse(NtfyRules.topicOk("a/b"))
        assertEquals("https://ntfy.sh", NtfyRules.server(""))
        assertEquals("https://ntfy.example.org", NtfyRules.server(" https://ntfy.example.org/ "))
        assertEquals("https://localhost:8443", NtfyRules.server("https://localhost:8443"))
        assertNull(NtfyRules.server("http://ntfy.sh"), "https only")
        assertNull(NtfyRules.server("https://intranet"))
        assertNull(NtfyRules.server("https://ntfy.sh/?x=1"))
        assertEquals("https://s/t/json?since=abc", NtfyRules.streamUrl("https://s", "t", "abc"))
        assertEquals("https://s/t/json?poll=1&since=abc", NtfyRules.pollUrl("https://s", "t", "abc"))
        assertEquals("abc", NtfyRules.since("abc", 900, 1_000, 5))
        assertEquals("700", NtfyRules.since(null, null, 1_000, 5))
        assertEquals("700", NtfyRules.since("abc", 100, 1_000, 5), "an old id may have left the server's cache")
        assertEquals("700", NtfyRules.since("abc", null, 1_000, 5))
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 60_000L, 60_000L), listOf(0, 1, 2, 6, 99).map { NtfyRules.backoffMs(it) })
        assertEquals(1_000L, NtfyRules.backoffMs(-3))
        assertEquals("topic set (****)", NtfyRules.masked("secret"))
        assertEquals("no topic set", NtfyRules.masked(null)); assertEquals("no topic set", NtfyRules.masked(" "))
        assertTrue(NtfyRules.weakTopic("alerts1")); assertTrue(NtfyRules.weakTopic("abcdefghijklmnopqrstuvwxyz"))
        assertFalse(NtfyRules.weakTopic("k3J9-q2Lm_8xZpV4rT7wYb"))
        assertTrue(NtfyRules.SIGNAL.contains("not researched"))
    }
}
