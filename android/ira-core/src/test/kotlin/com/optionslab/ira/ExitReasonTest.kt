package com.optionslab.ira

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 16 Oct: why Android ended the process, from its own record, in plain words ([ExitReason]). */
class ExitReasonTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun ms(t: String) = LocalDateTime.parse(t).atZone(ist).toInstant().toEpochMilli()

    @Test fun eachEndingIsSaidPlainlyWithWhatTheProcessWasAndItsMemory() {
        // Boss's 09:14:55 restart: Android's low-memory killer while the watch ran in the foreground.
        val l = ExitReason.line(ms("2026-10-16T09:14:39"), ist, ExitReason.LOW_MEMORY, 125, 412 * 1024L, 655 * 1024L, "lmk")
        assertEquals("process ended 16 Oct 09:14:39: Android's low-memory killer (the phone was short of memory) · it was running the " +
            "foreground watch · memory 412 MB (rss 655 MB) · Android: lmk", l)
        // A native crash (the Java crash handler never sees one), no memory figures, no description.
        val n = ExitReason.line(ms("2026-10-16T11:13:20"), ist, ExitReason.CRASH_NATIVE, 125, 0, 0, null)
        assertTrue(n.endsWith("a native crash (the language model or a native library) · it was running the foreground watch"), n)
        assertTrue(ExitReason.appFault(ExitReason.CRASH_NATIVE) && ExitReason.appFault(ExitReason.ANR) && ExitReason.appFault(ExitReason.CRASH))
        assertFalse(ExitReason.appFault(ExitReason.LOW_MEMORY))
        assertTrue("too much CPU or battery" in ExitReason.reason(ExitReason.EXCESSIVE_RESOURCE_USAGE))
        assertEquals("an unknown reason (99)", ExitReason.reason(99))
        assertEquals("cached (in the background, nothing running)", ExitReason.importance(400))
        assertEquals("on screen", ExitReason.importance(100))
        assertEquals("gone", ExitReason.importance(1000))
    }

    @Test fun androidsDescriptionIsCleanedAndCut() {
        val l = ExitReason.line(0, ist, 13, 300, 0, 0, "stop <script>token=abc\n" + "x".repeat(200))
        val tail = l.substringAfter(" · Android: ")
        assertFalse('<' in tail || '=' in tail || '\n' in tail, tail)
        assertTrue(tail.length <= 80, tail)
        // A description of nothing but symbols adds nothing.
        assertFalse(" · Android:" in ExitReason.line(0, ist, 13, 300, 0, 0, "<<>>"))
    }

    @Test fun onlyEndingsNotToldBeforeAreTold() {
        val all = listOf(30L to "c", 10L to "a", 20L to "b")
        assertEquals(listOf(20L to "b", 30L to "c"), ExitReason.fresh(all, sinceMs = 10))
        assertEquals(emptyList(), ExitReason.fresh(all, sinceMs = 30))
        assertEquals(listOf(30L to "c"), ExitReason.fresh(all, sinceMs = 0, max = 1))
        assertEquals(emptyList(), ExitReason.fresh(emptyList<Pair<Long, String>>(), 0))
    }
}
