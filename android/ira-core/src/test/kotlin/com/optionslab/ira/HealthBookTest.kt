package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's own health ([HealthBook]) and its self-healing tuner ([HealthTuner]): clustering, bounds, restore, words, the file. */
class HealthBookTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val d0 = LocalDate.of(2026, 10, 12)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(ist).toInstant().toEpochMilli()
    private fun hhmm(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(ist).toLocalTime().toString().take(5)

    private fun stalls(b: HealthBook.Book, d: LocalDate, n: Int, where: String, ms: Long = 900): HealthBook.Book {
        var x = b
        repeat(n) { x = HealthBook.stall(x, at(d, 10, it), ms, where) }
        return x
    }

    @Test fun aStallIsSevenHundredMillisecondsOrMoreKeptWithItsPlace() {
        var b = HealthBook.stall(HealthBook.Book(), at(d0, 10), 699, "Choreographer.doFrame in BrainSection.invoke")
        assertNull(b.day(d0))
        b = HealthBook.stall(b, at(d0, 10), 700, "Choreographer.doFrame in BrainSection.invoke")
        b = HealthBook.stall(b, at(d0, 11), 1_500, "MessageQueue.next")
        val d = b.day(d0)!!
        assertEquals(2, d.stalls)
        assertEquals(1_500, d.longestMs)
        assertEquals(mapOf("BrainSection.invoke" to 1, "MessageQueue.next" to 1), d.places)
        assertEquals(listOf(700L, 1_500L), d.samples.map { it.ms })
        // Never more than the newest ten samples, the count goes on.
        b = stalls(b, d0, 20, "X.y")
        assertEquals(22, b.day(d0)!!.stalls)
        assertEquals(HealthBook.SAMPLES_DAY, b.day(d0)!!.samples.size)
    }

    @Test fun stallsClusteringOnACadenceLengthenItOneStepADayWithinItsBound() {
        val t = HealthTuner.tunable("brain.consensus")!!
        var b = stalls(HealthBook.Book(), d0, 2, "BrainSection.invoke")
        // Two: not a cluster.
        assertTrue(HealthTuner.evaluate(b, d0).second.isEmpty())
        b = stalls(b, d0, 1, "BrainSection.invoke")
        val (b1, a1) = HealthTuner.evaluate(b, d0)
        assertEquals(1, a1.size)
        assertEquals(HealthTuner.Adjust("brain.consensus", 15_000, 30_000, false, "3 stalls there on 12 Oct"), a1[0])
        assertEquals(30_000, HealthTuner.cadenceMs(b1, "brain.consensus"))
        // Once a day only.
        assertTrue(HealthTuner.evaluate(stalls(b1, d0, 5, "BrainSection.invoke"), d0).second.isEmpty())
        // Day after day of stalls: doubled to its bound and never beyond.
        var x = b1
        for (k in 1..5L) { x = stalls(x, d0.plusDays(k), 3, "BrainSection.invoke"); x = HealthTuner.evaluate(x, d0.plusDays(k)).first }
        assertEquals(t.maxMs, HealthTuner.cadenceMs(x, "brain.consensus"))
        assertTrue(x.tuned.values.all { it.ms in HealthTuner.defaultMs(it.key)..HealthTuner.tunable(it.key)!!.maxMs })
        // Unrelated stalls move nothing.
        assertTrue(HealthTuner.evaluate(stalls(HealthBook.Book(), d0, 9, "Unrelated.draw"), d0).second.isEmpty())
    }

    @Test fun overrunsOfItsWorkerLengthenTheFindingsWrite() {
        var b = HealthBook.overrun(HealthBook.Book(), at(d0, 10), "Market brain")
        b = HealthBook.overrun(b, at(d0, 11), "Market brain")
        val (b1, a) = HealthTuner.evaluate(b, d0)
        assertEquals(listOf("findings.flush"), a.map { it.key })
        assertEquals(60_000, HealthTuner.cadenceMs(b1, "findings.flush"))
        assertTrue("2 runs past the time limit" in a[0].why, a[0].why)
    }

    @Test fun healthyForThreeDaysRestoresIt() {
        var b = HealthTuner.evaluate(stalls(HealthBook.Book(), d0, 3, "MoveRecorder.write"), d0).first
        assertEquals(60_000, HealthTuner.cadenceMs(b, "moves.flush"))
        // Two healthy days after the step: still lengthened.
        assertTrue(HealthTuner.evaluate(b, d0.plusDays(3)).second.isEmpty())
        // A stall there on the 14th: the three days start again.
        val again = HealthBook.stall(b, at(d0.plusDays(2), 12), 800, "MoveRecorder.write")
        assertTrue(HealthTuner.evaluate(again, d0.plusDays(4)).second.isEmpty())
        // Three whole healthy days (13-15 Oct): on the 16th back to normal, and said so.
        val (b2, a) = HealthTuner.evaluate(b, d0.plusDays(4))
        assertEquals(listOf(HealthTuner.Adjust("moves.flush", 60_000, 30_000, true, "healthy for 3 days")), a)
        assertTrue(b2.tuned.isEmpty())
        assertEquals(30_000, HealthTuner.cadenceMs(b2, "moves.flush"))
        assertTrue(HealthTuner.evaluate(b2, d0.plusDays(5)).second.isEmpty())
    }

    @Test fun neverTheOrderPathStopsOrTheStream() {
        for (t in HealthTuner.TUNABLES) {
            assertFalse(HealthTuner.FORBIDDEN.containsMatchIn(t.key), t.key)
            assertTrue(t.maxMs in t.defaultMs..t.defaultMs * 4, t.key)
        }
        for (k in listOf("order.path", "stops.check", "stream.tick", "fastpath.lane", "exit.check")) {
            assertNull(HealthTuner.tunable(k), k)
            assertEquals(0L, HealthTuner.defaultMs(k))
        }
        // A tampered file cannot set a cadence outside its bounds, nor one that is not a tunable.
        val b = HealthBook.decode("H1|0\nT|brain.consensus|999999|2026-10-12|2026-10-12|x\nT|order.path|1|2026-10-12|2026-10-12|x\n")
        assertEquals(setOf("brain.consensus"), b.tuned.keys)
        assertEquals(60_000, HealthTuner.cadenceMs(b, "brain.consensus"))
    }

    @Test fun exitsAreReadOnceNewestFirst() {
        val a = HealthBook.Exit(at(d0, 10), HealthBook.ExitKind.ANR, "Input dispatching timed out")
        val c = HealthBook.Exit(at(d0, 11), HealthBook.ExitKind.CRASH, "OrbArms.tick|x")
        var b = HealthBook.exits(HealthBook.Book(), listOf(a, c))
        assertEquals(listOf(HealthBook.ExitKind.CRASH, HealthBook.ExitKind.ANR), b.exits.map { it.kind })
        assertEquals("OrbArms.tick x", b.exits[0].where)
        b = HealthBook.exits(b, listOf(a, c))
        assertEquals(2, b.exits.size)
    }

    @Test fun theDiagnosticsLineAndJarvisWords() {
        var b = stalls(HealthBook.Book(), d0, 3, "BrainSection.invoke", 1_200)
        b = HealthBook.exits(b, listOf(HealthBook.Exit(at(d0, 9, 30), HealthBook.ExitKind.ANR, "Input dispatching timed out")))
        b = HealthBook.orderPath(b, d0, 210, 4)
        b = HealthBook.reconnect(b, at(d0, 12)); b = HealthBook.saver(b, at(d0, 12), true); b = HealthBook.trim(b, at(d0, 12))
        b = HealthTuner.evaluate(b, d0).first
        assertEquals("Health: 3 stalls today (longest 1.2 s, most in BrainSection.invoke) · last ANR 12 Oct 09:30 in Input dispatching timed out · " +
            "no crash seen · tuner: the Brain card's consensus refresh every 30 s (normally 15 s, since 12 Oct) · stream reconnects 1, stale 0, " +
            "worker overruns 0, battery saver on, memory trims 1", HealthBook.diagLine(b, d0, ::hhmm))
        val h = HealthBook.sayHealthy(b, d0)
        assertTrue(h.startsWith("Not quite, Boss. Today: 3 stalls of 0.7 s or more (longest 1.2 s). Last 7 days: 3 stalls, 1 ANR, 0 crashes."), h)
        assertTrue("Orders today: signal to sent 210 ms typical over 4." in h, h)
        assertTrue("the order path, stops and the price stream are never slowed" in h, h)
        val s = HealthBook.sayWhySlow(b, d0, ::hhmm)
        assertTrue(s.startsWith("Today the screen stalled 3 times, longest 1.2 s: mostly in BrainSection.invoke (3)."), s)
        assertTrue("Android's battery saver was on" in s && "Android asked for memory back 1 time" in s, s)
        assertTrue("Last ANR: 12 Oct 09:30 in Input dispatching timed out." in s, s)
        assertEquals("Yes, Boss: the app is healthy. Today: 0 stalls of 0.7 s or more. Last 7 days: 0 stalls, 0 ANRs, 0 crashes.",
            HealthBook.sayHealthy(HealthBook.Book(), d0))
        assertTrue(HealthBook.sayWhySlow(HealthBook.Book(), d0, ::hhmm).startsWith("I saw no slowdowns, Boss"))
    }

    @Test fun asked() {
        for (q in listOf("is the app healthy?", "Jarvis, is the app healthy", "how healthy is the app", "app health", "is the app running smoothly"))
            assertEquals(HealthBook.Ask.HEALTHY, HealthBook.asked(q), q)
        for (q in listOf("why was the app slow?", "why is the app so slow", "why was the app lagging today", "why did the app hang"))
            assertEquals(HealthBook.Ask.SLOW, HealthBook.asked(q), q)
        for (q in listOf("is nifty healthy", "how are my positions", "why is nifty slow", "is everything working", "how fast are you"))
            assertNull(HealthBook.asked(q), q)
    }

    @Test fun theFileRoundTrips() {
        var b = stalls(HealthBook.Book(), d0, 4, "BrainSection.invoke")
        b = HealthBook.overrun(b, at(d0, 9), "Market brain")
        b = HealthBook.orderPath(b, d0, 250, 3)
        b = HealthBook.exits(b, listOf(HealthBook.Exit(at(d0, 8), HealthBook.ExitKind.CRASH, "OrbArms.tick")))
        b = HealthTuner.evaluate(b, d0).first
        assertEquals(b, HealthBook.decode(HealthBook.encode(b)))
        assertEquals(HealthBook.Book(), HealthBook.decode(null))
        // Fourteen days kept.
        var x = HealthBook.Book()
        for (k in 0..20L) x = HealthBook.reconnect(x, at(d0.plusDays(k), 10))
        assertEquals(HealthBook.KEEP_DAYS, x.days.size)
    }
}
