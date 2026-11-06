package com.optionslab.engine.orb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R51's picker (a shadow log): its threshold, its log part, and a model with the wrong number of features. */
class R51PickerTest {
    @Test fun theThresholdAndThePart() {
        assertTrue(R51Picker.takes(R51Picker.THRESHOLD)); assertFalse(R51Picker.takes(0.4468))
        assertEquals("NIFTY call: W20 would keep, picker 0.512 would take", R51Picker.part("NIFTY", 1, false, 0.5123))
        assertEquals("SENSEX put: W20 would skip, picker 0.300 would skip", R51Picker.part("SENSEX", -1, true, 0.3))
        assertEquals("FINNIFTY put: W20 would keep, picker could not score it", R51Picker.part("FINNIFTY", -1, false, null))
        val s = R51Picker.score(DoubleArray(R72Picker.FEATS.size) { Double.NaN })
        assertTrue(s > 0 && s < 1, "all unknown: the medians' score")
    }

    @Test fun aModelNeedsANumberPerFeature() {
        assertFailsWith<IllegalArgumentException> { R72Picker.Model(DoubleArray(3), DoubleArray(3), DoubleArray(3), DoubleArray(3), 0.0, 0.5) }
    }
}
