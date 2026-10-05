package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfReviewTest {
    private val none = SelfReview.Facts(3, 3, emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList())

    @Test fun quietDayNothingToSay() { assertNull(SelfReview.say(none)) }

    @Test fun whatChangedAndTomorrow() {
        val s = SelfReview.say(none.copy(bar = 4, badHours = listOf("09:00-10:00"), badKinds = listOf("pattern hammer"),
            goalsAtRisk = listOf("Loss limit this week: -Rs 4,000.00 against -Rs 5,000.00 - close (80% used)."),
            heldUp = listOf("ORB 5")))!!
        assertTrue(s.startsWith("My own review: my lower-confidence trades lost, so I now act alone only at 4/5 or more; I leave 09:00-10:00 alone"), s)
        assertTrue(s.contains("For tomorrow: watch your goal: Loss limit this week"), s)
        assertTrue(s.endsWith("ORB 5 held up on paper - yours to take live if you want."), s)
        assertEquals("My own review: my record improved, so I act alone again from 3/5 or more.", SelfReview.say(none.copy(barYesterday = 4)))
        assertTrue(SelfReview.say(none.copy(bar = 6))!!.contains("no level (I only ask now)"))
    }
}
