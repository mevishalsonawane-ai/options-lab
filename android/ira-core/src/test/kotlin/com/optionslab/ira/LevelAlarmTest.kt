package com.optionslab.ira

import com.optionslab.ira.LevelAlarm.Level
import com.optionslab.ira.LevelAlarm.Standing
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A liquidity level tapped on the chart: which one, the sheet's words, the alarm's direction and note, no duplicates. */
class LevelAlarmTest {
    private val day = LocalDate.of(2026, 10, 5)
    private val high = Level(54_180.0, 1, pool = true, from = 10, to = 40, taken = false)
    private val low = Level(54_000.0, -1, pool = false, from = 5, to = 20, taken = true)
    private val near = Level(54_186.0, 1, pool = false, from = 30, to = 40, taken = true)

    @Test fun aTapPicksTheNearestLevelWithinTheTolerance() {
        val all = listOf(high, low, near)
        assertEquals(0, LevelAlarm.hit(all, 54_182.0, 5.0, bar = 35))
        assertEquals(2, LevelAlarm.hit(all, 54_185.0, 5.0, bar = 35))
        // Too far from any level, or off the bars a level is drawn over (give or take one).
        assertNull(LevelAlarm.hit(all, 54_100.0, 5.0, bar = 35))
        assertNull(LevelAlarm.hit(all, 54_001.0, 5.0, bar = 25))
        assertEquals(1, LevelAlarm.hit(all, 54_001.0, 5.0, bar = 21))
        assertEquals(1, LevelAlarm.hit(all, 54_001.0, 5.0, bar = 4))
        assertEquals(1, LevelAlarm.hit(all, 54_001.0, 5.0, bar = 25, slack = 5))
        // The label column (no bar): only levels still in play.
        assertEquals(0, LevelAlarm.hit(all, 54_185.0, 10.0, activeOnly = true))
        assertNull(LevelAlarm.hit(all, 54_001.0, 5.0, activeOnly = true))
        // At the same distance, the level still in play first.
        val twin = listOf(near.copy(price = 54_180.0), high)
        assertEquals(1, LevelAlarm.hit(twin, 54_180.0, 1.0, bar = 35))
        assertNull(LevelAlarm.hit(emptyList(), 54_180.0, 1.0))
    }

    @Test fun pixelsBecomePoints() {
        // A plot 500 px high over 100 points: 12 px is 2.4 points, the middle is 54,050.
        assertEquals(2.4, LevelAlarm.tolerance(12.0, 54_000.0, 54_100.0, 500.0), 1e-9)
        assertEquals(54_050.0, LevelAlarm.priceAt(250.0, 54_000.0, 54_100.0, 500.0), 1e-9)
        assertEquals(54_100.0, LevelAlarm.priceAt(0.0, 54_000.0, 54_100.0, 500.0), 1e-9)
        assertEquals(0.0, LevelAlarm.tolerance(12.0, 54_000.0, 54_100.0, 0.0))
        assertEquals(54_100.0, LevelAlarm.priceAt(10.0, 54_000.0, 54_100.0, 0.0))
    }

    @Test fun theDirectionComesFromTheLastPrice() {
        assertTrue(LevelAlarm.above(54_000.0, 54_180.0))
        assertFalse(LevelAlarm.above(54_200.0, 54_180.0))
        assertTrue(LevelAlarm.above(54_180.0, 54_180.0))
    }

    @Test fun theNoteNamesTheLevel() {
        assertEquals("Liquidity level 54,180 (swing-high pool, 15-min)", LevelAlarm.note(high, 15))
        assertEquals("Liquidity level 54,000 (swing-low zone, 5-min)", LevelAlarm.note(low, 5))
        assertEquals("Liquidity level 54,180.35 (swing-high pool, 30-min)", LevelAlarm.note(high.copy(price = 54_180.35), 30))
        assertTrue(LevelAlarm.note(high.copy(price = 1e60), 15).length <= LevelAlarm.NOTE_MAX)
    }

    @Test fun theSheetSaysPriceKindDistanceAndTaken() {
        assertEquals(listOf("Level 54,180", "Swing-high pool · 15-min chart", "212.50 pts above the last price 53,967.50",
            "Not taken today: still in play", "An alert fires when the price rises to it"),
            LevelAlarm.sheet(high, 15, 53_967.5, null, day))
        assertEquals(listOf("Level 54,000", "Swing-low zone · 5-min chart", "180.00 pts below the last price 54,180", "Taken today at 11:15", "An alert fires when the price falls to it"),
            LevelAlarm.sheet(low, 5, 54_180.0, day.atTime(11, 15), day))
        assertEquals(listOf("Level 54,000", "Swing-low zone · 5-min chart", "The last price is not known yet", "Taken on 2 Oct, not today"),
            LevelAlarm.sheet(low, 5, null, LocalDate.of(2026, 10, 2).atTime(14, 0), day))
        assertEquals("At the last price 54,000", LevelAlarm.distance(54_000.0, 54_000.001))
        assertEquals("Taken: a close went through it", LevelAlarm.takenLine(low, null, day))
    }

    @Test fun duplicatesAndTheSymbolsLevelAlarms() {
        val key = "CHART:BANKNIFTY"
        val list = listOf(
            Standing(1, key, true, 54_180.0, LevelAlarm.note(high, 15)),
            Standing(2, key, false, 53_900.0, "my own"),
            Standing(3, "CHART:FINNIFTY", true, 54_180.0, LevelAlarm.note(high, 15)),
            Standing(4, key, false, 54_000.0, LevelAlarm.note(low, 5)),
        )
        assertTrue(LevelAlarm.duplicate(list, key, 54_180.001, true))
        assertFalse(LevelAlarm.duplicate(list, key, 54_180.0, false))
        assertFalse(LevelAlarm.duplicate(list, key, 54_181.0, true))
        assertFalse(LevelAlarm.duplicate(list, "CHART:SENSEX", 54_180.0, true))
        // Any alarm at that price and direction counts, not only a level's.
        assertTrue(LevelAlarm.duplicate(list, key, 53_900.0, false))
        assertEquals(listOf(4L, 1L), LevelAlarm.onLevels(list, key).map { it.id })
        assertEquals("54,180 · rises to it · swing-high pool, 15-min", LevelAlarm.line(list[0]))
        assertEquals("53,900 · falls to it", LevelAlarm.line(list[1]))
    }
}
