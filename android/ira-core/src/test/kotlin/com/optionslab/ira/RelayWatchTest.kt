package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayWatchTest {
    @Test fun toldOnceAfterTwoFailsAndAgainWhenBack() {
        var s = RelayWatch.State()
        RelayWatch.next(s, false).let { (n, t) -> assertNull(t); s = n }
        RelayWatch.next(s, false).let { (n, t) -> assertNotNull(t); s = n }
        RelayWatch.next(s, false).let { (n, t) -> assertNull(t, "told once"); s = n }
        RelayWatch.next(s, true).let { (n, t) -> assertTrue(t!!.contains("answering again")); s = n }
        RelayWatch.next(s, true).let { (_, t) -> assertNull(t) }
    }

    @Test fun oneBlipIsNotTold() {
        val (s, t) = RelayWatch.next(RelayWatch.State(), false)
        assertNull(t)
        assertEquals(RelayWatch.State(), RelayWatch.next(s, true).first)
    }

    @Test fun onlyAroundTradingHours() {
        assertTrue(!RelayWatch.due(8 * 60) && RelayWatch.due(9 * 60) && !RelayWatch.due(16 * 60))
    }
}
