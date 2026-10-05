package com.optionslab.ira

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskSpeedTest {
    @Test fun stagesTimedInOrder() {
        val s = AskStages()
        val id = s.begin(1_000, voice = true)
        s.mark(id, AskStages.Stage.STARTED, 1_050)
        s.mark(id, AskStages.Stage.ROUTED, 1_300)
        s.mark(id, AskStages.Stage.ANSWERED, 4_300)
        s.mark(id, AskStages.Stage.SPOKEN, 4_800)
        assertEquals(listOf(AskStages.Done(true, 50, 300, 3_000, 500)), s.recent())
        val line = s.line()
        assertTrue(line.startsWith("Speed (asks): last 1 (1 spoken, 0 typed), typical heard→routed 0.3 s (0.1 s waiting in line), routed→answered 3.0 s, answered→spoken 0.5 s"), line)
        assertTrue(line.contains("slowest stage: routed→answered (typical 3.0 s)"), line)
        assertTrue(line.endsWith("last question 3.8 s in all."), line)
    }

    @Test fun outOfOrderAndClosedMarksIgnored() {
        val s = AskStages()
        val id = s.begin(1_000, voice = false)
        s.mark(id, AskStages.Stage.ANSWERED, 2_000)          // before routed: ignored
        s.mark(id, AskStages.Stage.SPOKEN, 2_100)            // before answered: ignored
        s.mark(id, AskStages.Stage.ROUTED, 1_200)
        s.mark(id, AskStages.Stage.ROUTED, 1_900)            // repeated: the first kept
        s.mark(id, AskStages.Stage.ANSWERED, 1_500)
        s.finish(id)
        s.mark(id, AskStages.Stage.SPOKEN, 9_000)            // closed
        assertEquals(listOf(AskStages.Done(false, null, 200, 300, null)), s.recent())
        assertTrue(s.line().contains("1 typed"))
    }

    @Test fun neverRoutedIsDroppedAndOldestOpenClosed() {
        val s = AskStages()
        s.finish(s.begin(0, true))
        assertTrue(s.recent().isEmpty())
        assertEquals("Speed (asks): no questions timed yet.", s.line())
        val first = s.begin(0, true)
        s.mark(first, AskStages.Stage.ROUTED, 100)
        repeat(AskStages.OPEN_MAX) { s.begin(200, true) }
        assertEquals(1, s.recent().size)                    // the oldest closed as it stood
    }

    @Test fun keepsOnlyTheLatest() {
        val s = AskStages(keep = 3)
        repeat(5) { i -> val id = s.begin(0, true); s.mark(id, AskStages.Stage.ROUTED, (i + 1) * 100L); s.finish(id) }
        assertEquals(listOf(300L, 400L, 500L), s.recent().map { it.routed })
    }

    @Test fun slowestStage() {
        val all = listOf(AskStages.Done(true, 0, 100, 200, 30_000), AskStages.Done(true, 0, 100, 200, 33_000))
        assertEquals("answered→spoken" to 33_000L, AskStages.slowest(all))
        assertNull(AskStages.slowest(emptyList()))
    }

    @Test fun keptFiguresUse() {
        assertEquals(KeptFigures.Use.READ, KeptFigures.use(emptyList()))
        assertEquals(KeptFigures.Use.READ, KeptFigures.use(listOf(1_000L, null)))
        assertEquals(KeptFigures.Use.KEPT, KeptFigures.use(listOf(1_000L, 29_999L)))
        assertEquals(KeptFigures.Use.KEPT_AND_REFRESH, KeptFigures.use(listOf(1_000L, 30_000L)))
        assertEquals(KeptFigures.Use.KEPT_AND_REFRESH, KeptFigures.use(listOf(4 * 60_000L)))
        assertEquals(KeptFigures.Use.READ, KeptFigures.use(listOf(5 * 60_000L)))
        assertEquals(KeptFigures.Use.READ, KeptFigures.use(listOf(-1L)))
        assertTrue(KeptFigures.standIn(listOf(10 * 60_000L)))
        assertFalse(KeptFigures.standIn(listOf(30 * 60_000L)))
        assertFalse(KeptFigures.standIn(listOf(1_000L, null)))
        assertFalse(KeptFigures.standIn(emptyList()))
        assertEquals(20_000L, KeptFigures.oldest(listOf(5L, 20_000L, null)))
    }

    @Test fun keptFiguresNote() {
        val now = LocalTime.of(10, 45, 30)
        assertNull(KeptFigures.note(59_000, now))
        assertEquals("As of 10:43, Boss (I'm reading your account again now):", KeptFigures.note(2 * 60_000L, now))
        assertEquals("As of 10:45, Boss (your account is slow to answer just now):", KeptFigures.note(10_000, now, slow = true))
        assertEquals("a b", KeptFigures.dress("b", "a"))
        assertEquals("b", KeptFigures.dress("b", null))
    }

    @Test fun modelWait() {
        assertEquals(0L, ModelWait.hindiWait(on = true, loaded = false))
        assertEquals(0L, ModelWait.hindiWait(on = false, loaded = true))
        assertEquals(ModelWait.HINDI_MS, ModelWait.hindiWait(on = true, loaded = true))
        assertTrue(ModelWait.HINDI_MS <= 3_000L)
        assertEquals(ModelWait.FREE_FORM_MS, ModelWait.left(-5))
        assertEquals(4_000L, ModelWait.left(8_000))
        assertEquals(0L, ModelWait.left(20_000))
        assertTrue(ModelWait.another(3_000)); assertFalse(ModelWait.another(2_999))
        assertEquals(0L, ModelWait.holdAfter(loaded = false)); assertEquals(600L, ModelWait.holdAfter(loaded = true))
    }

    @Test fun clearDropsAll() {
        val s = AskStages()
        val id = s.begin(0, true); s.mark(id, AskStages.Stage.ROUTED, 10); s.finish(id)
        s.begin(20, true)
        s.clear()
        assertTrue(s.recent().isEmpty())
    }
}
