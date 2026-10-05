package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraduationTest {
    private val day = LocalDate.of(2026, 10, 1)
    private fun closed(n: Int, rupees: Double, live: Boolean = false) = List(n) { JarvisTrades.Closed(day, rupees, live) }

    @Test fun toldOnceEachOnlyWhenProven() {
        val both = mapOf(Graduation.Who.JARVIS to true, Graduation.Who.SOLO to true)
        assertEquals(listOf(Graduation.Who.JARVIS, Graduation.Who.SOLO), Graduation.due(emptySet(), both))
        assertEquals(listOf(Graduation.Who.SOLO), Graduation.due(setOf("jarvis"), both))
        assertTrue(Graduation.due(setOf("jarvis", "solo"), both).isEmpty())
        assertTrue(Graduation.due(emptySet(), mapOf(Graduation.Who.JARVIS to false)).isEmpty())
    }

    @Test fun saysWhatItMeansAndBossesStepsAndSwitchesNothing() {
        val rec = closed(20, 300.0) + closed(2, -5000.0, live = true)
        val off = Graduation.say(Graduation.Who.JARVIS, rec, aiLiveOn = false, appLive = true)
        assertTrue(off.startsWith("Boss, my own trades passed their paper test: 20 closed on paper, net +Rs 6,000.00."), off)
        assertTrue(off.contains("switch on \"AI trades go live\"") && off.contains("fingerprint") && off.contains("I have switched nothing."), off)
        val on = Graduation.say(Graduation.Who.SOLO, closed(20, 100.0), aiLiveOn = true, appLive = true)
        assertTrue(on.startsWith("Boss, Solo's trades passed") && on.contains("approve it with your fingerprint"), on)
        val paperMode = Graduation.say(Graduation.Who.SOLO, closed(20, 100.0), aiLiveOn = true, appLive = false)
        assertTrue(paperMode.contains("stay on paper while the app is in Paper"), paperMode)
        assertFalse(off.contains("switch on \"AI trades go live\" for you"))
    }
}
