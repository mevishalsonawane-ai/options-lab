package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwitchOffTest {
    /** Boss's 5 Oct: ORB lost 10 of 15 paper trades, -Rs 977; Liquidity 15+5 down on the day. */
    private fun orbPaper(): List<Double> = List(10) { -200.0 } + List(5) { 204.6 }
    private val arms = listOf(
        SwitchOff.arm("Liquidity 15+5", true, listOf(-1_500.0, -2_229.0, 900.0, 1_200.0, -100.0, 400.0)),
        SwitchOff.arm("ORB", true, orbPaper()),
        SwitchOff.arm("ORB Fresh", true, listOf(-120.0, -80.0, 60.0, -40.0, -30.0, 10.0)),
        SwitchOff.arm("ORB Sweep", false, listOf(-50.0, -60.0)),
        SwitchOff.arm("Range Fade", false, listOf(100.0, 150.0, -20.0, 30.0, 40.0)),
    )

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|better switched off|fine to run|consider)\\b")

    @Test fun theArmsThatLostInBothAreListedArmedFirstAndNothingIsSwitched() {
        val a = SwitchOff.answer(SwitchOff.Q(), arms)
        val s = a.text
        assertTrue(s.startsWith("Boss, these 2 arms lost in both their two-year test and on paper:"), s)
        assertTrue(s.contains("- ORB Fresh (armed): its two-year BankNifty test lost in both years (-Rs 16,838 and -Rs 6,361 a lot); on paper 6 trades, 2 won, -Rs 200 net."), s)
        assertTrue(s.contains("- ORB (armed): its two-year BankNifty test lost in both years (-Rs 77,013 and -Rs 69,754 a lot); on paper 15 trades, 5 won, -Rs 977 net."), s)
        // The deeper paper loss first.
        assertTrue(s.indexOf("- ORB (armed)") < s.indexOf("- ORB Fresh (armed)"), s)
        assertTrue(s.contains("Lost in one record only: Liquidity 15+5 (armed): its two-year BankNifty test made money in both years"), s)
        assertTrue(s.contains("ORB Sweep (already off): its two-year BankNifty test lost in both years (-Rs 24,046 and -Rs 76,025 a lot), only 2 trades on paper so far (-Rs 110), too few to say - 5 needed"), s)
        assertTrue(s.contains("Range Fade (already off): its two-year BankNifty test lost in both years (-Rs 47,158 and -Rs 72,340 a lot), on paper 5 trades, 4 won, +Rs 300 net"), s)
        assertFalse(s.contains("Neither record lost"), s)
        assertTrue(s.contains(SwitchOff.WHERE) && s.contains("Or say \"stop ORB\" and I'll ask you to confirm first.") && s.endsWith(SwitchOff.NOTE), s)
        assertEquals(listOf("ORB", "ORB Fresh"), a.offer)
        assertFalse(ADVICE.containsMatchIn(s), s)
    }

    @Test fun oneArmAskedByName() {
        val a = SwitchOff.answer(SwitchOff.Q("orb fresh"), arms)
        assertTrue(a.text.startsWith("Boss, ORB Fresh (armed): its two-year BankNifty test lost in both years") && a.text.contains("It lost in both records."), a.text)
        assertEquals(listOf("ORB Fresh"), a.offer)
        val l = SwitchOff.answer(SwitchOff.Q("liquidity"), arms)
        assertTrue(l.text.contains("Liquidity 15+5 (armed)") && l.text.contains("It lost in one record only.") && l.offer.isEmpty(), l.text)
        val sweep = SwitchOff.answer(SwitchOff.Q("orb sweep"), arms)
        assertTrue(sweep.text.contains("Its test lost, and paper has too few trades yet to set beside it.") && sweep.offer.isEmpty(), sweep.text)
        val none = SwitchOff.answer(SwitchOff.Q("orb"), listOf(SwitchOff.arm("ORB Fresh", true, emptyList())))
        assertTrue(none.text.startsWith("Boss, I see no arm called orb") && none.offer.isEmpty(), none.text)
    }

    @Test fun nothingLostInBothIsSaidSoAndOffersNothing() {
        val a = SwitchOff.answer(SwitchOff.Q(), listOf(SwitchOff.arm("Liquidity 15+5", true, List(6) { 100.0 })))
        assertTrue(a.text.startsWith("Boss, no arm lost in both its two-year test and on paper") && a.text.contains("Neither record lost: Liquidity 15+5."), a.text)
        assertTrue(a.offer.isEmpty() && !a.text.contains("\"stop "), a.text)
        // An arm already off is never offered.
        val off = SwitchOff.answer(SwitchOff.Q(), listOf(SwitchOff.arm("ORB", false, orbPaper())))
        assertTrue(off.offer.isEmpty() && off.text.contains("- ORB (already off)"), off.text)
        assertTrue(SwitchOff.ask(SwitchOff.arm("ORB", true, orbPaper())).startsWith("Boss, ORB lost in both records:"))
    }

    private val ASKED = mapOf(
        "what should i switch off" to null, "What should I switch off?" to null, "which arms should i turn off" to null,
        "which bot should i disarm first" to null, "which strategies should i stop" to null, "what should be switched off" to null,
        "which arms lost in both the test and on paper" to null, "which bots lost both tested and paper" to null,
        "is any arm worth switching off" to null, "which arms are worth keeping" to null, "which orb arms should i switch off" to null,
        "what to switch off" to null, "should i switch off orb" to "orb", "should i disarm orb fresh" to "orb fresh",
        "should i stop the range fade arm" to "range fade", "should i turn off liquidity 15+5" to "liquidity",
        "should i switch off the orb arm" to "orb",
        "kaun sa bot band karun" to null, "kya band karna chahiye" to null, "orb band kar du kya" to "orb",
    )
    private val NOT = listOf(
        "stop orb", "switch off orb", "disarm orb fresh", "stop all bots", "what should i buy", "should i switch to live",
        "should i turn off the kill switch", "what should i switch off on my phone", "when should i switch off orb",
        "do i switch off my bots too fast", "which strategy is losing", "how are my bots doing", "should i stop trading today",
        "explain my bots' trades today", "should i close my positions", "what alerts should i turn off", "turn off alerts",
    )

    @Test fun theQuestionAndNotACommand() {
        for ((s, arm) in ASKED) {
            assertEquals(SwitchOff.Q(arm), SwitchOff.asked(s), s)
            val p = Ask.parse(s)
            assertTrue(p.order == null && p.command == null, "$s: ${p.command} ${p.order}")
            assertFalse(Bundle.acts(s), s)
            assertFalse(ArmHabits.asked(s), "ArmHabits takes \"$s\"")
            assertNull(BotTrades.asked(s), s)
        }
        for (s in NOT) assertNull(SwitchOff.asked(s), s)
        // The command itself still reads as the command that asks to confirm.
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("stop orb").command?.kind)
    }
}
