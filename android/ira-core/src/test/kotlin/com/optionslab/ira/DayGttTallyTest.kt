package com.optionslab.ira

import com.optionslab.ira.DayGttTally.Event
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 21 Oct, enhancement round 5 (idea 1): the day GTT's counts for the diagnostics ([DayGttTally]). */
class DayGttTallyTest {
    private val d = LocalDate.of(2026, 10, 21)

    @Test fun eventsAddUpPerDay() {
        var l = DayGttTally.add(emptyList(), d, Event.PLACED)
        l = DayGttTally.add(l, d, Event.PLACED)
        l = DayGttTally.add(l, d, Event.TRAILED)
        l = DayGttTally.add(l, d.minusDays(1), Event.KEPT)
        assertEquals(2, l.single { it.day == d }[Event.PLACED])
        assertEquals(1, l.single { it.day == d }[Event.TRAILED])
        assertEquals(0, l.single { it.day == d }[Event.FIRED])
        assertEquals(listOf(d.minusDays(1), d), l.map { it.day })
    }

    @Test fun oldDaysAreDroppedAndNothingAddedForZero() {
        var l = DayGttTally.add(emptyList(), d.minusDays(20), Event.FIRED)
        l = DayGttTally.add(l, d, Event.GONE)
        assertEquals(listOf(d), l.map { it.day })
        // An earlier day added later (a late note) is kept while within the week of the newest day.
        l = DayGttTally.add(l, d.minusDays(3), Event.KEPT)
        assertEquals(listOf(d.minusDays(3), d), l.map { it.day })
        assertEquals(l, DayGttTally.add(l, d, Event.GONE, n = 0))
        assertEquals(l, DayGttTally.add(l, d, Event.GONE, n = -2))
    }

    @Test fun theWeekCountsSevenDaysToToday() {
        var l = emptyList<DayGttTally.Day>()
        for (i in 0L..7L) l = DayGttTally.add(l, d.minusDays(7 - i), Event.PLACED)  // eight days, the oldest 7 days ago
        // The add prunes against each day as added: all eight are kept (KEEP_DAYS), the week reads seven of them.
        assertEquals(7, DayGttTally.week(l, d)[Event.PLACED])
        assertTrue(DayGttTally.week(emptyList(), d).isEmpty())
    }

    @Test fun theLineSaysTodayTheWeekAndTheSwitch() {
        var l = DayGttTally.add(emptyList(), d, Event.PLACED)
        l = DayGttTally.add(l, d, Event.REPLACED)
        l = DayGttTally.add(l, d.minusDays(2), Event.FIRED)
        val line = DayGttTally.line(true, l, d)
        assertEquals("Stop and target as one GTT in the day: on; today placed 1, replaced by two orders 1; last 7 days placed 1, " +
            "fired 1, replaced by two orders 1 (2 days with any)", line)
        assertEquals("Stop and target as one GTT in the day: off; today nothing yet; last 7 days nothing yet", DayGttTally.line(false, emptyList(), d))
        assertTrue(DayGttTally.line(false, l, d.plusDays(1)).contains("today nothing yet; last 7 days placed 1"))
    }

    @Test fun encodeAndDecodeRoundTripAndSkipBadRows() {
        var l = DayGttTally.add(emptyList(), d, Event.PLACED)
        l = DayGttTally.add(l, d, Event.SHRUNK, 3)
        l = DayGttTally.add(l, d.minusDays(1), Event.KEPT)
        assertEquals(l, DayGttTally.decode(DayGttTally.encode(l)))
        assertTrue(DayGttTally.decode(null).isEmpty())
        assertTrue(DayGttTally.decode("").isEmpty())
        val bad = DayGttTally.decode("nonsense;2026-10-21|PLACED=2,WHAT=1,FIRED=x,KEPT=-1;2026-13-01|PLACED=1;2026-10-20")
        assertEquals(listOf(DayGttTally.Day(d, mapOf(Event.PLACED to 2))), bad)
    }

    @Test fun mergeAddsThisProcessesEventsToTheSaved() {
        val saved = DayGttTally.add(DayGttTally.add(emptyList(), d, Event.PLACED), d.minusDays(1), Event.FIRED)
        val mine = DayGttTally.add(DayGttTally.add(emptyList(), d, Event.PLACED), d, Event.TRAILED)
        val m = DayGttTally.merge(saved, mine)
        assertEquals(2, m.single { it.day == d }[Event.PLACED])
        assertEquals(1, m.single { it.day == d }[Event.TRAILED])
        assertEquals(1, m.single { it.day == d.minusDays(1) }[Event.FIRED])
        assertEquals(saved, DayGttTally.merge(saved, emptyList()))
    }
}
