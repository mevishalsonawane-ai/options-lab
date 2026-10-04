package com.optionslab.ira

import com.optionslab.engine.options.ChainRow
import com.optionslab.engine.options.ChainSnapshot
import com.optionslab.engine.options.OptLeg
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChainReadTest {
    @Test fun theChainInWords() {
        val rows = (0..8).map { i ->
            val k = 24_000.0 + i * 50
            ChainRow(k, OptLeg("CE$i", 100.0 - i * 10, oi = if (k == 24_300.0) 900_000 else 100_000L + i, volume = 5_000, prevOi = 100_000),
                OptLeg("PE$i", 10.0 + i * 10, oi = if (k == 24_000.0) 800_000 else 90_000L + i, volume = 4_000, prevOi = 90_000))
        }
        val c = ChainSnapshot.of("NIFTY", LocalDate.of(2026, 10, 7), 24_160.0, 75, rows, java.time.ZonedDateTime.of(2026, 10, 2, 11, 0, 0, 0, ZoneId.of("Asia/Kolkata")))
        val l = ChainRead.lines(c)
        assertTrue(l[0].startsWith("NIFTY 2026-10-07 chain (spot 24,160): put-call ratio"), l[0])
        assertTrue(l.any { it.contains("biggest call OI at 24,300 (900,000), read as resistance; biggest put OI at 24,000 (800,000)") }, l.toString())
        assertTrue(l.any { it.startsWith("NIFTY OI added today: calls at 24,300 (+800,000), puts at 24,000 (+710,000)") }, l.toString())
        assertEquals(setOf(Section.CHAIN), AppAnswers.sections("what is the nifty pcr and max pain"))
    }
}
