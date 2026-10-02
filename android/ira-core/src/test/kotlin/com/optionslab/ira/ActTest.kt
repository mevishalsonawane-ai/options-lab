package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActTest {
    private fun k(text: String) = Commands.parse(text)?.kind

    @Test fun commandsAreRead() {
        assertEquals(Command.Kind.STOP_ALL, k("stop all arms"))
        assertEquals(Command.Kind.STOP_ALL, k("Jarvis, stop all strategies"))
        assertEquals(Command.Kind.STOP_ALL, k("stop trading"))
        assertEquals(Command.Kind.START_ALL, k("start the bots again"))
        assertEquals(Command(Command.Kind.STOP_ONE, number = 1), Commands.parse("stop strategy 1"))
        assertEquals(Command(Command.Kind.START_ONE, number = 1), Commands.parse("start strategy one"))
        assertEquals(Command(Command.Kind.START_ONE, target = "breakout"), Commands.parse("arm jarvis breakout"))
        assertEquals(Command(Command.Kind.STOP_ONE, target = "orb 15"), Commands.parse("switch off the ORB 15"))
        assertEquals(Command.Kind.CANCEL_ALL, k("stop all orders"))
        assertEquals(Command.Kind.CANCEL_ALL, k("cancel all open orders"))
        assertEquals(Command(Command.Kind.CANCEL_ONE, number = 2), Commands.parse("cancel order 2"))
        assertEquals(Command(Command.Kind.CANCEL_ONE, target = "last"), Commands.parse("cancel the last order"))
        assertEquals(Command.Kind.CLOSE_ALL, k("square off all"))
        assertEquals(Command.Kind.CLOSE_ALL, k("close all positions"))
        assertEquals(Command(Command.Kind.CLOSE_ONE, number = 1), Commands.parse("close position 1"))
        assertEquals(Command.Kind.KILL_ON, k("turn on the kill switch"))
        assertEquals(Command.Kind.KILL_OFF, k("kill switch off"))
        assertEquals(Command.Kind.MODE_LIVE, k("switch to live"))
        assertEquals(Command.Kind.MODE_PAPER, k("go back to paper"))
        assertEquals(Command(Command.Kind.ALARM_ADD, market = Market.NIFTY, above = true, level = 25000.0), Commands.parse("alert me when nifty goes above 25000"))
        assertEquals(Command(Command.Kind.ALARM_ADD, market = Market.BANKNIFTY, above = false, level = 51000.0), Commands.parse("set an alarm on banknifty below 51000"))
        assertEquals(Command(Command.Kind.ALARM_REMOVE, number = 2), Commands.parse("remove alarm 2"))
    }

    @Test fun questionsAndOrdersAreNotCommands() {
        assertNull(Commands.parse("how do I stop all strategies"))
        assertNull(Commands.parse("where is the kill switch"))
        assertNull(Commands.parse("stop listening"))
        assertNull(Commands.parse("what is nifty doing"))
        assertNull(Commands.parse("buy 1 lot nifty 24000 ce"))
        assertEquals(setOf(Topic.COMMAND), Ask.parse("stop strategy 1").topics)
        assertEquals(setOf(Topic.COMMAND), Ask.parse("cancel all orders").topics)
        assertTrue(Topic.ACCOUNT in Ask.parse("how are my strategies doing").topics)
        assertTrue(Topic.ORDER in Ask.parse("buy 1 lot nifty 24000 ce").topics)
    }

    @Test fun targetsArePickedByNumberOrName() {
        val names = listOf("Jarvis: Breakout BankNifty 15m", "ORB 15", "Jarvis: Hammer Nifty 1h")
        assertEquals(0, Commands.pick(Command(Command.Kind.STOP_ONE, number = 1), names))
        assertNull(Commands.pick(Command(Command.Kind.STOP_ONE, number = 9), names))
        assertEquals(1, Commands.pick(Command(Command.Kind.STOP_ONE, target = "orb"), names))
        assertEquals(2, Commands.pick(Command(Command.Kind.STOP_ONE, target = "the hammer"), names))
        assertNull(Commands.pick(Command(Command.Kind.STOP_ONE, target = "jarvis"), names), "two match: ask, never guess")
        assertNull(Commands.pick(Command(Command.Kind.STOP_ONE, target = "gold"), names))
        assertTrue(Command.Kind.STOP_ONE.reduces && !Command.Kind.START_ONE.reduces && Command.Kind.CLOSE_ALL.reduces && !Command.Kind.KILL_OFF.reduces)
    }

    @Test fun jarvisOwnTradesAreNotTheAppsMode() {
        assertEquals(Command.Kind.JTRADES_LIVE, Commands.parse("Jarvis, let your trades go live")?.kind)
        assertEquals(Command.Kind.JTRADES_PAPER, Commands.parse("keep your trades on paper")?.kind)
        val l = Commands.parse("set Jarvis loss limit to 3000")
        assertEquals(Command.Kind.JTRADES_LIMIT, l?.kind); assertEquals(3000.0, l?.level)
        assertEquals(Command.Kind.MODE_LIVE, Commands.parse("switch to live mode")?.kind)
        assertEquals(true, Command.Kind.JTRADES_PAPER.reduces); assertEquals(false, Command.Kind.JTRADES_LIVE.reduces)
    }
}
