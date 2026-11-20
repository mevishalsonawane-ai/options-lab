package com.optionslab.app.data

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.SoloMidday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 20 Nov 2026 (audit: "Solo 2.0's backstop is skipped on stale index data"): S1's index walk on minutes that stopped coming holds
 * for ever (no bar reaches its stop), so the premium backstop (-30%, 14:30) now judges it whenever the newest index minute is over
 * 3 minutes old - as it already did with no minutes at all or unknown levels.
 */
class SoloBackstopStaleTest : com.optionslab.app.testing.RobolectricTest() {
    private val day = LocalDateTime.of(2026, 11, 20, 0, 0)
    private fun bar(h: Int, m: Int, c: Double) = Bar(day.withHour(h).withMinute(m), c, c + 2, c - 2, c)

    @Test fun staleIndexMinutesHandS1ToItsPremiumBackstop() {
        val now = day.withHour(13).withMinute(5).withSecond(20)
        // The feed stopped at 12:40: the walk on those minutes holds (no stop reached), so it could never stop out.
        val stale = (0..40).map { bar(12, it % 60, 25_000.0) }.filter { it.start.hour == 12 }
        val w = SoloMidday.walk(SoloMidday.Open("NIFTY", 1, 25_000.0, 100.0, day.withHour(12)), stale, now)
        assertNull("the walk on stale minutes holds", w.exit)
        assertTrue(PassedArmsHost.s1Backstop(stale, 25_000.0, 100.0, now))
        // Fresh minutes (the newest is 13:03, two minutes back): the index walk judges it, as before.
        val fresh = stale + (0..3).map { bar(13, it, 25_000.0) }
        assertFalse(PassedArmsHost.s1Backstop(fresh, 25_000.0, 100.0, now))
    }

    @Test fun noMinutesOrUnknownLevelsStillUseTheBackstop() {
        val now = day.withHour(13).withMinute(5)
        val fresh = listOf(bar(13, 4, 25_000.0))
        assertTrue(PassedArmsHost.s1Backstop(emptyList(), 25_000.0, 100.0, now))
        assertTrue(PassedArmsHost.s1Backstop(fresh, null, 100.0, now))
        assertTrue(PassedArmsHost.s1Backstop(fresh, 25_000.0, null, now))
        assertEquals(false, PassedArmsHost.s1Backstop(fresh, 25_000.0, 100.0, now))
    }
}
