package com.optionslab.ira

import com.optionslab.ira.WatchHealth.State
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchHealthTest {
    private val t0 = 1_000_000_000_000L

    @Test fun aSlowCheckIsNotADeadWatch() {
        assertEquals(State.OK, WatchHealth.state(t0, t0 - 60_000, t0 - 10_000))
        // 09:31 last check, 09:35 now: alive pulse 20 s ago = stuck on something, not stopped.
        assertEquals(State.BUSY, WatchHealth.state(t0, t0 - 4 * 60_000, t0 - 20_000))
        // No pulse for minutes: the service or the process is gone.
        assertEquals(State.DEAD, WatchHealth.state(t0, t0 - 4 * 60_000, t0 - 4 * 60_000))
        assertEquals(State.DEAD, WatchHealth.state(t0, 0, 0))
        // A clock that jumped back is not "fresh".
        assertEquals(State.DEAD, WatchHealth.state(t0, t0 + 10 * 60_000, 0))
    }

    @Test fun theNoticeSaysTheBatteryFixPlainly() {
        val dead = WatchHealth.notice(State.DEAD, "09:31", null, null, batteryRestricted = true)
        assertTrue(dead.startsWith("No check since 09:31. Stops, targets and strategy exits are not being watched."))
        assertTrue("set IraAlgo's battery to Unrestricted" in dead)
        assertTrue(dead.contains("Boss"))
        val ok = WatchHealth.notice(State.DEAD, null, null, null, batteryRestricted = false)
        assertEquals("The watch has not run today. Stops, targets and strategy exits are not being watched. Tap to open IraAlgo and restart it.", ok)
        val busy = WatchHealth.notice(State.BUSY, "09:31", "stops and targets", 250, batteryRestricted = false)
        assertTrue("4 min on stops and targets" in busy, busy)
        assertFalse("Unrestricted" in busy)
        assertEquals("Order watch stopped", WatchHealth.title(State.DEAD))
        assertEquals("Order watch is stuck", WatchHealth.title(State.BUSY))
    }

    @Test fun diaryLinesCarryNoMessages() {
        assertEquals("SocketTimeoutException", WatchHealth.cleanClass("java.net.SocketTimeoutException"))
        assertEquals("unknown", WatchHealth.cleanClass(null))
        // Whatever is handed in, only class-name characters survive (no spaces, '=', '/', ':' to carry a token or URL).
        val odd = WatchHealth.cleanClass("Weird access_token=abc/def:ghi")
        assertFalse(odd.contains("=") || odd.contains("/") || odd.contains(" "), odd)
        assertEquals("a check failed in stops and targets: IllegalStateException (the watch goes on)",
            WatchHealth.failure("stops and targets", "java.lang.IllegalStateException"))
        val stall = WatchHealth.stall(State.BUSY, "09:31", "index quotes", 240, true)
        assertTrue(stall.startsWith("STUCK") && "index quotes for 240s" in stall && "OPTIMIZED" in stall, stall)
        assertTrue(WatchHealth.stall(State.DEAD, null, "x", 1, false).let { it.startsWith("STOPPED") && "none today" in it && "waiting" !in it })
        assertEquals("ended: it threw OutOfMemoryError", WatchHealth.ended(WatchHealth.End.CRASHED, "java.lang.OutOfMemoryError"))
        assertTrue("dataSync" in WatchHealth.ended(WatchHealth.End.ANDROID_TIME_LIMIT, "dataSync"))
    }

    @Test fun aProcessRestartIsTold() {
        assertNull(WatchHealth.restart(null, "2026-10-05", crashSaved = false, byAndroid = false))
        assertNull(WatchHealth.restart("2026-10-02", "2026-10-05", crashSaved = false, byAndroid = false))
        assertEquals("restarted by Android", WatchHealth.restart(null, "2026-10-05", false, byAndroid = true))
        val r = WatchHealth.restart("2026-10-05", "2026-10-05", crashSaved = true, byAndroid = false)!!
        assertTrue("process had ended" in r && "crash report" in r, r)
    }

    @Test fun theRelayWarmUpBacksOff() {
        assertEquals(0L, WatchHealth.warmGapMs(0))
        assertEquals(2 * 60_000L, WatchHealth.warmGapMs(1))
        assertEquals(4 * 60_000L, WatchHealth.warmGapMs(2))
        assertEquals(8 * 60_000L, WatchHealth.warmGapMs(3))
        assertEquals(10 * 60_000L, WatchHealth.warmGapMs(4))
        assertEquals(10 * 60_000L, WatchHealth.warmGapMs(400))
    }

    @Test fun statusLineAndMorningLine() {
        val z = ZoneId.of("Asia/Kolkata")
        val line = WatchHealth.statusLine(t0, t0 - 240_000, t0 - 5_000, "index quotes", t0 - 200_000, true, z)
        assertTrue(line.startsWith("Order watch: last check ") && "service running" in line && "index quotes for 200s" in line && "Unrestricted" in line, line)
        assertTrue("not running" in WatchHealth.statusLine(t0, 0, 0, null, 0, null, z))
        assertTrue(WatchHealth.isBatteryFix("✗ " + WatchHealth.BATTERY_FIX))
        assertFalse(WatchHealth.isBatteryFix("✓ " + WatchHealth.BATTERY_OK))
        assertTrue("Unrestricted" in WatchHealth.BATTERY_FIX)
    }
}
