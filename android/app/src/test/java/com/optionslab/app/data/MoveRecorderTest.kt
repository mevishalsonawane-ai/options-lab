package com.optionslab.app.data

import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.MoveEvents
import com.optionslab.ira.OrderFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The big-move recorder on the phone ([MoveRecorder], fed by [OrderFlowLive]): thirteen minutes of synthetic full-mode
 * ticks with one big NIFTY futures candle at 10:31 give exactly one event file, holding the 5 minutes before (from memory)
 * and the 5 minutes after (written as they closed) of both futures, the cash index and VIX, with the books; the daily
 * 1-second file carries the new columns and the index rows; Jarvis answers from the saved minutes. Nothing trades.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class MoveRecorderTest : RobolectricTest() {
    private val nf = 13_000_001L
    private val bn = 13_000_257L
    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    private fun ms(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, 9, h, m, s).atZone(ist).toInstant().toEpochMilli()

    @Before fun up() {
        MoveRecorder.resetForTest(seed = 5)
        MoveRecorder.dir()?.deleteRecursively()
        OrderFlowLive.testManual = true
        OrderFlowLive.Recorder.testOpen = true
        Market.testClock = java.time.Clock.fixed(java.time.Instant.ofEpochMilli(ms(10, 40)), ist)
    }

    @After fun down() {
        Market.testClock = null
        OrderFlowLive.resetForTest()
        MoveRecorder.resetForTest()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun depth(p: Double) = KiteTicks.Depth(
        (0 until 5).map { KiteTicks.Level(p - 0.05 * (it + 1), 100L + 10 * it, 2 + it) },
        (0 until 5).map { KiteTicks.Level(p + 0.05 * (it + 1), 120L + 10 * it, 3 + it) })

    private fun fut(token: Long, last: Double, vol: Long, at: Long) = KiteTicks.Tick(token, last, volume = vol, oi = 1_000_000,
        buyQty = 9_000, sellQty = 7_000, lastQty = 10, lastTradeTime = at / 1000, depth = depth(last))

    @Test fun aSyntheticBigCandleGivesOneEventFileWithItsMinutesBeforeAndAfter() {
        OrderFlowLive.layoutForTest(listOf(OrderFlow.Board.Entry(nf, "NIFTY", OrderFlow.Role.FUTURE), OrderFlow.Board.Entry(bn, "BANKNIFTY", OrderFlow.Role.FUTURE)))
        val bigFrom = ms(10, 31); val bigTo = ms(10, 32)
        var nfPx = 25_000.0
        var vol = 1_000L
        var i = 0
        var t = ms(10, 24)
        while (t <= ms(10, 37, 20)) {
            // NIFTY's future is flat (a tick either way) but for 10:31, when it climbs 1.25 a second: 75 points, 30 bp - about
            // 15 times the usual 10:31 minute. BANKNIFTY's stays flat throughout.
            nfPx += if (t in bigFrom until bigTo) 1.25 else if (i % 2 == 0) 0.05 else -0.05
            vol += 10
            OrderFlowLive.offer(listOf(
                fut(nf, nfPx, vol, t), fut(bn, 55_000.0 + (if (i % 2 == 0) 0.05 else 0.0), vol, t),
                KiteTicks.Tick(256_265L, nfPx - 20), KiteTicks.Tick(264_969L, 13.5 - (if (t >= bigFrom) 0.2 else 0.0))), t)
            OrderFlowLive.secondForTest(t + 400)
            t += 1000; i++
        }
        // One file: NIFTY's big up minute (no BANKNIFTY event, no control this early).
        val files = MoveRecorder.files()
        assertEquals(listOf("2026-10-09_NIFTY_1031_big_up.csv.gz"), files.map { it.second.name })
        val e = java.util.zip.GZIPInputStream(files.single().second.inputStream()).bufferedReader().useLines { MoveEvents.parse(it) }!!
        assertTrue("the window closed with its last seconds", e.complete)
        assertEquals(MoveEvents.Kind.BIG_UP, e.kind)
        assertTrue("about 15x the usual: ${e.x}", e.x >= MoveEvents.N)
        // The future's seconds: from 5 minutes before the candle (from memory) to 5 minutes after it (as they closed), each once.
        val f = e.rows.filter { it.second.name == "NIFTY" && it.second.role == "FUTURE" }.map { it.first }
        assertEquals(f.size, f.distinct().size)
        assertTrue("from ${f.minOrNull()}", f.min() <= -295)
        assertTrue("to ${f.maxOrNull()}", f.max() >= 355)
        assertTrue("before ${f.count { it < 0 }}", f.count { it < 0 } >= 290)
        assertTrue("after ${f.count { it >= 60 }}", f.count { it >= 60 } >= 290)
        assertTrue(e.rows.filter { it.second.role == "FUTURE" }.all { it.second.bar.book != null })
        // The other index's future, the cash index and VIX, before and after.
        for ((name, role) in listOf("BANKNIFTY" to "FUTURE", "NIFTY" to MoveEvents.INDEX_ROLE, MoveEvents.VIX to MoveEvents.INDEX_ROLE)) {
            val r = e.rows.filter { it.second.name == name && it.second.role == role }.map { it.first }
            assertTrue("$name $role before", r.any { it < -200 })
            assertTrue("$name $role after", r.any { it > 300 })
        }
        // The context line and the summary.
        assertEquals("NIFTY", e.header["name"]); assertEquals("10:31", e.header["time"]); assertEquals("13.3", e.header["vix"])
        val s = MoveEvents.summarize(e)
        assertTrue(s.flowDuring > 0)
        assertTrue(MoveRecorder.items().single().line.startsWith("10:31 Nifty up 0.30%"))
        // Jarvis, from the saved minutes.
        val a = MoveRecorder.answer(MoveEvents.asked("why did nifty jump at 10:32")!!)
        assertTrue(a, a.startsWith("Nifty's big move at 10:31: up 0.30% in the minute"))
        // The daily 1-second file: the new columns, the futures' and the index's rows.
        kotlinx.coroutines.runBlocking { OrderFlowLive.Recorder.flush(ms(10, 38)) }
        val day = OrderFlowLive.Recorder.days().single().second
        val lines = java.util.zip.GZIPInputStream(day.inputStream()).bufferedReader().readLines()
        assertEquals(MoveEvents.DAILY_HEADER.trimEnd(), lines.first())
        val cols = MoveEvents.DAILY_HEADER.trimEnd().split(',').size
        assertTrue(lines.drop(1).all { it.split(',').size == cols })
        assertTrue(lines.any { it.contains(",NIFTY,INDEX,") })
        assertTrue(lines.count { it.contains(",NIFTY,FUTURE,") } > 700)
        // The footprint file: NIFTY's minutes at each price.
        val foot = OrderFlowLive.Recorder.footDays().single().second
        val fl = java.util.zip.GZIPInputStream(foot.inputStream()).bufferedReader().readLines()
        assertEquals(MoveEvents.FOOT_HEADER.trimEnd(), fl.first())
        assertTrue(fl.any { it.contains(",10:31,NIFTY,") })
    }

    @Test fun switchedOffNothingIsSavedAndTheStreamIsNotKeptOn() {
        MoveRecorder.on = false
        assertTrue(!OrderFlowLive.keepStreamOn())
        OrderFlowLive.layoutForTest(listOf(OrderFlow.Board.Entry(nf, "NIFTY", OrderFlow.Role.FUTURE)))
        var px = 25_000.0
        var t = ms(10, 24)
        var vol = 1_000L
        while (t <= ms(10, 33)) {
            px += if (t in ms(10, 31) until ms(10, 32)) 1.25 else 0.0
            vol += 10
            OrderFlowLive.offer(listOf(fut(nf, px, vol, t)), t)
            OrderFlowLive.secondForTest(t + 400)
            t += 1000
        }
        assertTrue(MoveRecorder.files().isEmpty())
        MoveRecorder.on = true
    }
}
