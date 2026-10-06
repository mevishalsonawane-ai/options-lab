package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrbIdleSaveTest {
    /**
     * Counts the book writes of a trading session: [passes] once-a-minute watch passes, plus three 15-second stop checks
     * between them while [heldElsewhere] (a paper position or protection that is not the arms'). [armedFrom]: the pass
     * an arm is switched on (it stays on). [touchedAt]: passes before which the file was set aside, deleted or restored.
     * [policy]: whether the book is written, as the arms call it.
     */
    private fun session(passes: Int = 375, heldElsewhere: Boolean, armedFrom: Int? = null,
                        touchedAt: Set<Int> = emptySet(), policy: (Boolean, Boolean, Boolean) -> Boolean): Int {
        var writes = 0
        var written: String? = null      // what this process last wrote (null: nothing yet, as after a load)
        var untouched = false
        fun save(armed: Boolean, text: String) {
            if (policy(!armed, text == written, untouched)) { writes++; written = text; untouched = true }
        }
        for (p in 0 until passes) {
            if (p in touchedAt) untouched = false
            val armed = armedFrom != null && p >= armedFrom
            // An armed book records the bar it decided on (new text each pass); an idle one is the same text all day.
            val text = if (armed) "armed bar $p" else "idle"
            save(armed, text)
            if (heldElsewhere) repeat(3) { save(armed, text) }
        }
        return writes
    }

    private val before: (Boolean, Boolean, Boolean) -> Boolean = { _, _, _ -> true }
    private val now: (Boolean, Boolean, Boolean) -> Boolean = { i, s, u -> OrbIdleSave.writes(i, s, u) }

    @Test fun nothingArmedWritesOnce() {
        assertEquals(375, session(heldElsewhere = false, policy = before))
        assertEquals(1, session(heldElsewhere = false, policy = now))
    }

    @Test fun somethingElseHeldWritesOnce() {
        assertEquals(1_500, session(heldElsewhere = true, policy = before))
        assertEquals(1, session(heldElsewhere = true, policy = now))
    }

    @Test fun armedWritesEveryTimeAsBefore() {
        // Armed from the first pass: every pass and every stop check is written, as before.
        assertEquals(session(heldElsewhere = true, armedFrom = 0, policy = before), session(heldElsewhere = true, armedFrom = 0, policy = now))
        assertEquals(375, session(heldElsewhere = false, armedFrom = 0, policy = now))
        // Armed at 11:00 (pass 105): one idle write, then every pass as before.
        assertEquals(1 + (375 - 105), session(heldElsewhere = false, armedFrom = 105, policy = now))
    }

    @Test fun aTouchedFileIsWrittenAgain() {
        // Set aside, deleted or restored before passes 50 and 200: written again each time, then quiet.
        assertEquals(3, session(heldElsewhere = false, touchedAt = setOf(50, 200), policy = now))
    }

    @Test fun eachInputAloneKeepsTheWrite() {
        assertFalse(OrbIdleSave.writes(idle = true, sameText = true, fileUntouched = true), "idle, same bytes on disk: skipped")
        assertTrue(OrbIdleSave.writes(idle = false, sameText = true, fileUntouched = true), "armed, open or waiting: always written")
        assertTrue(OrbIdleSave.writes(idle = true, sameText = false, fileUntouched = true), "the text changed: written")
        assertTrue(OrbIdleSave.writes(idle = true, sameText = true, fileUntouched = false), "the file changed under it: written")
    }
}
