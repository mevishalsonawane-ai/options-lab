package com.optionslab.engine.risk

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The flat-by minutes per segment (22 Oct): 15:25 index, 15:20 stock, MCX ten minutes before its close, due until the close. */
class FlatByTest {
    private val summer = LocalDate.of(2026, 10, 22)   // US summer time: MCX closes 23:30
    private val winter = LocalDate.of(2026, 11, 3)    // US winter time: MCX closes 23:55

    @Test fun segmentsFromExchangeAndUnderlying() {
        assertEquals(FlatBy.Segment.NSE_INDEX, FlatBy.segment("NFO", "BANKNIFTY"))
        assertEquals(FlatBy.Segment.NSE_INDEX, FlatBy.segment("BFO", "sensex"))
        assertEquals(FlatBy.Segment.NSE_STOCK, FlatBy.segment("NFO", "RELIANCE"))
        assertEquals(FlatBy.Segment.NSE_STOCK, FlatBy.segment("NFO", "NIFTYBEES"))
        assertEquals(FlatBy.Segment.MCX, FlatBy.segment("MCX", "CRUDEOIL"))
        assertEquals(FlatBy.Segment.MCX, FlatBy.segment("mcx", "GOLD"))
    }

    @Test fun minutesPerSegment() {
        assertEquals(15 * 60 + 25, FlatBy.minute(FlatBy.Segment.NSE_INDEX, summer))
        assertEquals(15 * 60 + 20, FlatBy.minute(FlatBy.Segment.NSE_STOCK, summer))
        assertEquals(23 * 60 + 20, FlatBy.minute(FlatBy.Segment.MCX, summer))
        assertEquals(23 * 60 + 45, FlatBy.minute(FlatBy.Segment.MCX, winter))
        assertEquals("15:25", FlatBy.text(FlatBy.NSE_INDEX_MINUTE))
        assertEquals("23:45", FlatBy.text(FlatBy.minute(FlatBy.Segment.MCX, winter)))
    }

    /** 25 Oct review: a special MCX session (the Diwali Muhurat hour, a Budget Sunday) goes ten minutes before ITS close, not 23:20. */
    @Test fun anMcxSpecialSessionGoesTenMinutesBeforeItsOwnClose() {
        val muhurat = LocalDate.of(2026, 11, 8)                       // Sunday, 18:00-19:00 (the built-in list)
        assertEquals(18 * 60 + 50, FlatBy.minute(FlatBy.Segment.MCX, muhurat))
        assertEquals(19 * 60, FlatBy.until(FlatBy.Segment.MCX, muhurat))
        assertTrue(FlatBy.due(FlatBy.Segment.MCX, muhurat, 18 * 60 + 50))
        assertFalse(FlatBy.due(FlatBy.Segment.MCX, muhurat, 18 * 60 + 49))
        assertFalse(FlatBy.due(FlatBy.Segment.MCX, muhurat, 23 * 60 + 20), "the ordinary 23:20 is not due that day")
        val budget = LocalDate.of(2026, 2, 1)                          // Sunday, 09:00-17:00
        assertEquals(16 * 60 + 50, FlatBy.minute(FlatBy.Segment.MCX, budget))
        // An evening-only session keeps the ordinary close; a calendar handed in wins over the built-in list.
        assertEquals(23 * 60 + 20, FlatBy.minute(FlatBy.Segment.MCX, LocalDate.of(2026, 10, 20)))
        val cal = com.optionslab.engine.mcx.McxSession.Calendar(mapOf(summer to com.optionslab.engine.mcx.McxSession.Window(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(21, 0))))
        assertEquals(20 * 60 + 50, FlatBy.minute(FlatBy.Segment.MCX, summer, cal))
        assertTrue(FlatBy.line(muhurat).contains("MCX 18:50"), FlatBy.line(muhurat))
        assertTrue(FlatBy.line(summer).contains("MCX 23:20"), FlatBy.line(summer))
    }

    @Test fun dueFromTheMinuteUntilTheSegmentsClose() {
        val idx = FlatBy.Segment.NSE_INDEX
        assertFalse(FlatBy.due(idx, summer, 15 * 60 + 24))
        assertTrue(FlatBy.due(idx, summer, 15 * 60 + 25))
        assertTrue(FlatBy.due(idx, summer, 15 * 60 + 39))
        assertFalse(FlatBy.due(idx, summer, 15 * 60 + 40))        // F&O closed (15:40 from 3 Aug 2026)
        assertEquals(15 * 60 + 40, FlatBy.until(idx, summer))
        assertEquals(15 * 60 + 30, FlatBy.until(FlatBy.Segment.NSE_STOCK, LocalDate.of(2026, 7, 1)))
        assertTrue(FlatBy.due(FlatBy.Segment.NSE_STOCK, summer, 15 * 60 + 20))
        assertFalse(FlatBy.due(FlatBy.Segment.NSE_STOCK, summer, 15 * 60 + 19))
        val mcx = FlatBy.Segment.MCX
        assertFalse(FlatBy.due(mcx, summer, 23 * 60 + 19))
        assertTrue(FlatBy.due(mcx, summer, 23 * 60 + 20))
        assertTrue(FlatBy.due(mcx, summer, 23 * 60 + 29))
        assertFalse(FlatBy.due(mcx, summer, 23 * 60 + 30))
        assertTrue(FlatBy.due(mcx, winter, 23 * 60 + 50))
        assertEquals(23 * 60 + 55, FlatBy.until(mcx, winter))
    }

    @Test fun onlyMisIsIntraday() {
        assertTrue(FlatBy.intraday("MIS")); assertTrue(FlatBy.intraday("mis"))
        assertFalse(FlatBy.intraday("NRML")); assertFalse(FlatBy.intraday("CNC"))
    }

    @Test fun theCardLineNamesEveryMinute() {
        val l = FlatBy.line(summer)
        assertTrue(l.startsWith("Flat-by: index options 15:25 · stock and fund 15:20 · MCX 23:20 (10 min before its close) · expiry day 15:05 first."), l)
        assertTrue("MCX 23:45" in FlatBy.line(winter))
    }
}
