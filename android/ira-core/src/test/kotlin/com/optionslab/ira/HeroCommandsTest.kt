package com.optionslab.ira

import com.optionslab.engine.orb.HeroRules
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Stop hero", "hero band karo" and "start hero" name the expiry-day Hero arm by its own label ("Hero (expiry)") among
 * the app's arms, exactly as "stop range fade" names Range Fade: the stop or start then goes through Jarvis's usual
 * confirm. Nothing new is parsed - the arm's label is what makes it pickable.
 */
class HeroCommandsTest {
    private val arms = listOf("Strategy 1", "My Pine script", "ORB", "ORB Fresh", "ORB Sweep", "Range Fade", "Liquidity 15+5", HeroRules.ARM.label)

    @Test fun stopHeroStopsTheHeroArm() {
        for (s in listOf("stop hero", "hero band karo", "stop the hero arm", "switch off hero")) {
            val c = Ask.parse(s).command
            assertEquals(Command.Kind.STOP_ONE, c?.kind, s)
            assertEquals(arms.indexOf(HeroRules.ARM.label), Commands.pick(c!!, arms), s)
            assertEquals(null, Ask.parse(s).order, s)
        }
    }

    @Test fun startHeroOnlyArmsItThroughTheConfirm() {
        val c = Ask.parse("start hero").command
        assertEquals(Command.Kind.START_ONE, c?.kind)
        assertEquals(arms.indexOf(HeroRules.ARM.label), Commands.pick(c!!, arms))
        assertEquals("start Hero (expiry)", Commands.describe(c, HeroRules.ARM.label))
        assertEquals(null, Ask.parse("start hero").order)
    }

    @Test fun theOtherArmsAreStillPickedAsBefore() {
        assertEquals(arms.indexOf("Range Fade"), Commands.pick(Ask.parse("stop range fade").command!!, arms))
        assertEquals(arms.indexOf("ORB Sweep"), Commands.pick(Ask.parse("stop orb sweep").command!!, arms))
    }
}
