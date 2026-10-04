package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchTest {
    private val calm = Fixtures.indexDays(10, step = 2.0, seed = 3)

    @Test fun calmMarketsRaiseNothing() {
        val s = Brain.read(History(Market.NIFTY, calm))!!
        assertTrue(Watch.check(mapOf(Market.NIFTY to s), mapOf(Market.NIFTY to calm), emptyList(), 6000.0).none { it.key.startsWith("burst") })
    }

    @Test fun aSuddenMoveAVixJumpAndALosingArmAreRaisedWithKeys() {
        val last = calm.last()
        val spike = (1..30).map { i -> Candle(last.t.plusMinutes(i.toLong()), last.c, last.c, last.c, last.c * (1 + 0.0004 * i)) }
        val bars = calm.dropLast(30) + spike.map { it.copy(t = it.t.minusMinutes(30)) }
        val a = Watch.check(emptyMap(), mapOf(Market.NIFTY to bars), listOf(Watch.ArmDay("ORB 15", -3_100.0), Watch.ArmDay("Calm", -100.0)), 6000.0)
        assertTrue(a.any { it.key.startsWith("burst|NIFTY|") && it.title.startsWith("Nifty +1.") }, a.toString())
        assertEquals(listOf("arm|ORB 15"), a.filter { it.key.startsWith("arm") }.map { it.key })
        assertTrue(a.first { it.key.startsWith("arm") }.text.contains("Rs 3,100") )
        val vix = Fixtures.indexDays(25, start = 14.0, step = 0.02, seed = 9)
        val vs = Brain.read(History(Market.VIX, vix))!!
        assertTrue(Watch.check(mapOf(Market.VIX to vs.copy(prevClose = vs.price / 1.09)), emptyMap(), emptyList(), 0.0).single().key == "vix-day")
        assertEquals(null, Watch.typical30(calm.take(100)))
    }
}
