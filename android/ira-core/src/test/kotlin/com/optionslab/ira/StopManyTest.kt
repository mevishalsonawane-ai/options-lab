package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boss, 6 Oct: he switched off two arms, the words were read as a stop of everything, the rest stood still while showing
 * ARMED, and "start all" switched them all back on. A stop that names arms stops exactly those, each by itself; "all
 * except" stops the others and keeps the ones named; a yes said first is not a name; and "ORB" said in full is ORB.
 */
class StopManyTest {
    private val names = listOf("ORB", "ORB Fresh", "ORB Sweep", "Range Fade", "Liquidity 15+5", "Hero (expiry)")
    private val on = listOf(true, true, true, true, true, false)

    private fun cmd(s: String) = Ask.parse(s).command
    private fun stopped(s: String): List<String> {
        val c = cmd(s)!!
        val st = Commands.stops(c, names, on)
        assertTrue(st.unclear.isEmpty(), "$s: ${st.unclear}")
        return st.stop.map { names[it] }
    }

    @Test fun namesAfterAStopWordAreStoppedEachByItselfNeverAll() {
        for (s in listOf("stop the arms orb sweep and range fade", "turn off orb sweep and range fade", "orb sweep aur range fade band kar do",
            "range fade aur sweep band kardo", "stop orb sweep, range fade", "switch off ORB Sweep & Range Fade")) {
            val c = cmd(s)
            assertEquals(Command.Kind.STOP_ONE, c?.kind, s)
            assertFalse(c!!.keep, s)
            assertEquals(setOf("ORB Sweep", "Range Fade"), stopped(s).toSet(), s)
        }
        assertEquals(listOf("ORB Sweep", "Range Fade"), stopped("stop arms 3 and 4"))
        assertEquals(listOf("ORB Fresh", "ORB Sweep"), stopped("stop strategies 2 and 3"))
        assertEquals(listOf("ORB Fresh", "ORB Sweep"), stopped("stop strategy 2 and strategy 3"))
        assertEquals(listOf("ORB Sweep", "Range Fade", "ORB Fresh"), stopped("stop orb sweep, range fade and orb fresh"))
    }

    @Test fun oneNameAfterThePluralIsThatOneArm() {
        for (s in listOf("stop the bots orb sweep", "stop trading orb sweep", "stop the algos orb sweep")) {
            val c = cmd(s)!!
            assertEquals(Command.Kind.STOP_ONE, c.kind, s)
            assertEquals(2, Commands.pick(c, names), s)
        }
    }

    @Test fun theStopOfEverythingStaysAsItWas() {
        for (s in listOf("stop trading", "stop the bots", "stop all", "stop all strategies", "sab band kar do", "stop trading for today",
            "stop all strategies for today", "pause all bots", "stop everything"))
            assertEquals(Command.Kind.STOP_ALL, cmd(s)?.kind, s)
    }

    @Test fun allExceptStopsTheOthersAndKeepsTheOnesNamed() {
        val cases = mapOf(
            "stop all except orb" to listOf("ORB"),
            "stop everything except orb and orb fresh" to listOf("ORB", "ORB Fresh"),
            "range fade ko chhod ke sab band kar do" to listOf("Range Fade"),
            "orb aur orb fresh chhod ke baaki band kar do" to listOf("ORB", "ORB Fresh"),
            "range fade ke alawa sab band karo" to listOf("Range Fade"),
            "keep orb running and stop the rest" to listOf("ORB"),
            "stop everything but keep orb running" to listOf("ORB"),
            "stop all strategies apart from range fade" to listOf("Range Fade"),
        )
        for ((s, kept) in cases) {
            val c = cmd(s)
            assertNotEquals(Command.Kind.STOP_ALL, c?.kind, s)
            assertEquals(Command.Kind.STOP_ONE, c?.kind, s)
            assertTrue(c!!.keep, s)
            val st = Commands.stops(c, names, on)
            assertEquals(kept, st.stay.map { names[it] }, s)
            // Every arm that is on and not kept - never the one meant to stay, never one already off (Hero).
            assertEquals(names.indices.filter { on[it] && names[it] !in kept }, st.stop, s)
            val said = Commands.sayStops(st, names)
            assertTrue(said.startsWith("stop ") && kept.all { it in said.substringAfter(" - ") }, said)
        }
    }

    @Test fun aKeptNameThatMatchesNothingStopsNothingAndAsks() {
        val c = cmd("stop all except xyz")!!
        val st = Commands.stops(c, names, on)
        assertEquals(listOf("xyz"), st.unclear)
        val asked = Commands.unclearStops(st, names, c.keep)
        assertTrue(asked.contains("nothing was stopped") && asked.contains("1. ORB; 2. ORB Fresh"), asked)
        // "Orb" alone matches ORB in full; "sweep fade" matches none in full and several in part: asked.
        assertEquals(listOf("zzz"), Commands.stops(cmd("stop orb sweep and zzz")!!, names, on).unclear)
        // Cut off after "except": never the stop of everything.
        assertNull(cmd("stop all except"))
    }

    @Test fun aYesSaidFirstIsNotPartOfTheStop() {
        for (s in listOf("haan sab band kar do", "theek hai sab band kar do", "yes stop everything", "ok stop all", "haan ji, sab band karo"))
            assertEquals(Command.Kind.STOP_ALL, cmd(s)?.kind, s)
        val one = cmd("haan range fade band karo")!!
        assertEquals(Command.Kind.STOP_ONE, one.kind)
        assertEquals(3, Commands.pick(one, names))
        // A yes alone is still no command (the confirm path reads it).
        assertNull(cmd("haan"))
        assertNull(cmd("yes please"))
    }

    @Test fun aNameSaidInFullWinsOverTheNamesItBegins() {
        assertEquals(0, Commands.pick(cmd("stop ORB")!!, names))
        assertEquals(0, Commands.pick(cmd("stop the orb")!!, names))
        assertEquals(1, Commands.pick(cmd("stop orb fresh")!!, names))
        assertEquals(2, Commands.pick(cmd("stop sweep")!!, names))
        // Two names that share a word and neither said in full: still asked.
        assertNull(Commands.pick(Command(Command.Kind.STOP_ONE, target = "orb x"), listOf("ORB Fresh", "ORB Sweep")))
    }

    @Test fun otherStopsAreNotReadAsArms() {
        assertEquals(Command.Kind.MUTE, cmd("stop talking")?.kind)
        assertEquals(Command.Kind.CLOSE_ALL, cmd("stop the bots and close everything")?.kind)
        assertTrue(cmd("stop orb and close my positions")?.targets.isNullOrEmpty())
        assertTrue(cmd("stop offering trades and news")?.targets.isNullOrEmpty())
    }

    @Test fun theConfirmSaysEachOneAndAPlanCanHoldIt() {
        assertEquals("stop orb sweep and range fade, each by itself", Commands.describe(cmd("stop the arms orb sweep and range fade")!!))
        assertEquals("stop every strategy and arm that is on except orb, each by itself", Commands.describe(cmd("stop all except orb")!!))
        val steps = Plan.steps("stop all except orb, then turn on the kill switch") { s -> Ask.parse(s).let { it.order == null && it.command?.kind in Plan.ALLOWED } }
        assertEquals(2, steps?.size)
        assertTrue(Ask.parse(steps!![0]).command!!.keep)
    }
}
