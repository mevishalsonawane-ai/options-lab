package com.optionslab.app.ui.components

import androidx.compose.ui.unit.sp
import com.optionslab.app.testing.LayoutLint
import com.optionslab.app.ui.screens.timeTickEvery
import com.optionslab.app.ui.screens.timeTickLabels
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** The pure parts of chart text: axis font cap, label thinning, time-axis labels, and the lint's broken-number rule. */
class ChartTextTest {
    @Test fun axisLabelsGrowWithTheFontOnlyUpToTheCap() {
        assertEquals(10f, axisFontSize(10.sp, 1.0f).value, 1e-4f)
        assertEquals(10f, axisFontSize(10.sp, 1.3f).value, 1e-4f)
        // At font 2.0 the drawn size is 10 x 1.3 = 13 sp-equivalents, not 20.
        assertEquals(13f, axisFontSize(10.sp, 2.0f).value * 2.0f, 1e-3f)
    }

    @Test fun aLabelThatWouldTouchTheLastOneDrawnIsLeftOut() {
        // "09:15" at 0..40 and "11:00" at 38..78 overlapped ("09:1511:00"); the second is left out, the third kept.
        assertEquals(listOf(0, 2), nonOverlapping(listOf(0f to 40f, 38f to 78f, 90f to 130f), 4f))
        assertEquals(listOf(0, 1, 2), nonOverlapping(listOf(0f to 40f, 50f to 90f, 100f to 140f), 4f))
        assertEquals(emptyList<Int>(), nonOverlapping(emptyList(), 4f))
        // Kept labels never overlap, whatever comes in.
        val spans = (0 until 50).map { (it * 7f) to (it * 7f + 30f) }
        val kept = nonOverlapping(spans, 3f).map { spans[it] }
        kept.zipWithNext().forEach { (a, b) -> org.junit.Assert.assertTrue("$a then $b", b.first >= a.second + 3f) }
    }

    @Test fun ticksAreSpacedForTheWidestLabel() {
        assertEquals(6, timeTickEvery(40f, 9f, 10f))    // 50 px over 9 px candles
        assertEquals(1, timeTickEvery(5f, 9f, 0f))
    }

    private fun ist(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(com.optionslab.engine.IST).toEpochSecond()

    @Test fun timeLabelsAreShortAndDatedWhereTheDayChanges() {
        val oneDay = listOf(ist(2026, 9, 25, 9, 45), ist(2026, 9, 25, 10, 15), ist(2026, 9, 25, 10, 45))
        assertEquals(listOf("09:45", "10:15", "10:45"), timeTickLabels(oneDay, daily = false))
        val twoDays = listOf(ist(2026, 9, 24, 15, 0), ist(2026, 9, 25, 9, 30), ist(2026, 9, 25, 10, 0))
        assertEquals(listOf("24 Sep", "25 Sep", "10:00"), timeTickLabels(twoDays, daily = false))
        assertEquals(listOf("24 Sep", "25 Sep"), timeTickLabels(listOf(ist(2026, 9, 24, 9, 15), ist(2026, 9, 25, 9, 15)), daily = true))
    }

    @Test fun theLintFindsANumberBrokenAcrossLines() {
        // "₹20 / 0.0 / 0" and "130.0 / 0", as the screenshots showed them.
        assertEquals(listOf("₹200.00", "₹200.00"), LayoutLint.brokenNumbers("₹200.00", listOf(3, 6)))
        assertEquals(listOf("130.00"), LayoutLint.brokenNumbers("130.00", listOf(5)))
        assertEquals(listOf("(100.00%)"), LayoutLint.brokenNumbers("+100.00 (100.00%)", listOf(12)))
        // Breaks between words, and words that are not numbers, are fine.
        assertEquals(emptyList<String>(), LayoutLint.brokenNumbers("+100.00 (100.00%)", listOf(8)))
        assertEquals(emptyList<String>(), LayoutLint.brokenNumbers("Change since today's open", listOf(7)))
        assertEquals(emptyList<String>(), LayoutLint.brokenNumbers("NIFTY06OCT2624800CE", listOf(9)))
    }
}
