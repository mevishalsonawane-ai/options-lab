package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Understanding round 30: the newest answers as Boss says them (ExpiryHour said as a topic, StopNoise as "is my SL ok" or
 * "stop too close?", CheckTimes without "my" or in Hinglish word order), conditional instructions said the Hinglish way
 * without "agar" ("nifty 24000 aaye to exit kar dena", "2000 profit hote hi book kar lo") - never a command, an order or a
 * yes - and questions with "if" / "agar" / "when" in them that are not instructions and are never refused as one.
 */
class UnderstandingRound30Test {
    private val audit = CoverageTest()
    private val today: LocalDate = LocalDate.of(2026, 10, 6)

    private fun neverActs(s: String) {
        val p = Ask.parse(s)
        assertEquals(null, p.order, s); assertEquals(null, p.command, s)
        assertEquals(null, Commands.parse(s), s)
        assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
        assertTrue(!Bundle.acts(s) && !FollowUp.acts(s), s)
        assertTrue(!Reminder.asked(s) && !Reminder.cancelAsked(s), s)
        Intents.quick(s)?.let { r -> assertTrue(Ask.parse(r).order == null && Ask.parse(r).command == null, "$s -> $r") }
        assertTrue(Understand.questions(null, s).orEmpty().none { FollowUp.acts(it) || Ask.parse(it).command != null || Ask.parse(it).order != null }, s)
        assertTrue(Wake.yesNo(s) != true, s)
    }

    private val CONDITIONAL = listOf(
        "nifty 24000 aaye to exit kar dena", "nifty 24000 aaye toh exit kar dena", "nifty 24000 pe aaye to sab exit kar do",
        "nifty 24000 tak aaye to exit kar dena", "jaise hi profit 2000 ho book kar lo", "jaise hi profit 2000 ho jaye book kar lena",
        "profit 2000 ho jaye to book kar lo", "profit 2000 hua to book kar lena", "2000 profit hote hi book kar lo",
        "nifty 24000 aate hi exit kar dena", "loss 5000 ho to sab band kar do", "loss 3000 ho jaye to orb band kar dena",
        "nifty 25000 cross kare to call bech do", "banknifty 52000 tod de to put kharid lo", "nifty upar jaye to call kharid lo",
        "nifty 24000 ke neeche jaye to put bech do", "market khulte hi exit kar do",
        "if i lose 5000 turn on kill switch",
    )

    @Test fun hinglishConditionalsWithoutAgarNeverAct() {
        for (s in CONDITIONAL) {
            assertTrue(Conditional.asked(s), s)
            neverActs(s)
            assertEquals("Conditional", audit.feature(s), s)
        }
        // Round 29's still hold.
        for (s in listOf("agar nifty 100 point gire to sab band kar do", "exit all if nifty falls below 24000", "turn on kill switch if i lose 5000"))
            assertTrue(Conditional.asked(s), s)
    }

    /**
     * Review, 6 Oct: "X hai to Y", "X ho gaya hai to Y", "X ho raha hai to Y" is "since X, do Y now" in Hinglish - never a
     * condition, so these read exactly as before round 30 (as at 5ea8594, the garbled one-arm stop included).
     */
    @Test fun sinceSaidInThePresentTenseIsNoCondition() {
        assertEquals(Command(Command.Kind.KILL_ON), Commands.parse("jarvis loss bahut ho gaya hai to kill switch on kar do"))
        assertEquals(Command(Command.Kind.KILL_ON), Commands.parse("jarvis kill switch on kar do market crash ho raha hai to"))
        assertEquals(Command(Command.Kind.STOP_ONE, target = "nifty gir raha hai to all"), Commands.parse("nifty gir raha hai to sab band kar do"))
        assertEquals(Command(Command.Kind.STOP_ONE, target = "bahut volatile hai to all"), Commands.parse("bahut volatile hai to sab band kar do"))
        for (s in listOf("jarvis loss bahut ho gaya hai to kill switch on kar do", "jarvis kill switch on kar do market crash ho raha hai to",
            "nifty gir raha hai to sab band kar do", "bahut volatile hai to sab band kar do", "position hai to exit kar do")) {
            assertTrue(!Conditional.asked(s), s)
            assertEquals(null, Conditional.instead(s), s)
            assertEquals(Commands.parse(s), Ask.parse(s).command, s)
        }
        // Said with a real condition (subjunctive or future), the kill switch still goes through - a kill is never an added
        // risk - while an order or one arm's stop stays refused.
        assertEquals(Command.Kind.KILL_ON, Commands.parse("agar main 5000 loss karu to kill switch on kar do")?.kind)
        assertEquals(Command.Kind.KILL_ON, Commands.parse("loss 5000 ho jaye to kill switch on kar do")?.kind)
        for (s in listOf("loss 3000 ho jaye to orb band kar dena", "nifty 25000 cross kare to call bech do", "nifty upar jaye to call kharid lo"))
            neverActs(s)
    }

    @Test fun aYesSaidWithAHinglishConditionIsUnclear() {
        for (s in listOf("haan nifty 24000 aaye to kar do", "nifty 24000 aaye to exit kar dena", "kal expiry hai to exit kar do", "position hai to exit kar do"))
            assertEquals(null, Wake.yesNo(s), s)
        // "Theek hai to kar do" is "ok then, do it": still a yes, as are the plain ones.
        for (s in listOf("theek hai to kar do", "accha hai to kar do", "haan kar do", "theek hai kar do", "ok", "yes")) assertEquals(true, Wake.yesNo(s), s)
    }

    @Test fun questionsWithIfOrWhenAreNotRefused() {
        val asked = listOf(
            "what happens if nifty falls", "agar nifty gire to kya hoga", "when does the market open", "if i buy 2 lots what margin",
            "if i buy 2 lots how much margin", "if i buy 2 lots margin", "agar main 2 lot kharidu to margin", "agar main 2 lot kharidu to kitna margin lagega",
            "if i sell a put what is the risk", "if nifty goes to 25000 what happens to my call", "if i exit now how much do i book",
            "agar abhi exit karu to kitna milega", "if i buy atm call what is the breakeven", "if i stop orb what happens",
            "agar orb band karu to kya hoga", "if i close all now what is my pnl", "if i square off now what is my pnl", "if i exit now my pnl",
            "agar main call bechu to brokerage", "nifty kitna hai to batao", "mere paas position hai to kya karu", "when should i exit",
        )
        for (s in asked) {
            assertTrue(!Conditional.asked(s), s)
            assertTrue(audit.feature(s) != "Conditional", s)
            neverActs(s)
        }
        assertEquals("Account:FUNDS", audit.feature("if i buy 2 lots margin"))
        assertEquals("Account:FUNDS", audit.feature("agar main 2 lot kharidu to margin"))
        assertEquals("Account:PNL", audit.feature("if i square off now what is my pnl"))
        // Boss supposing his own act is never done, said with a figure or alone.
        for (s in listOf("if i buy 2 lots margin", "if i square off now", "if i buy 1 lot nifty call", "agar main exit karu", "when i exit"))
            assertTrue(Conditional.supposed(s), s)
        for (s in listOf("if i square off now", "if i buy 1 lot nifty call", "if i buy 2 lots", "if i sell my call")) neverActs(s)
        // Alarms stay alarms.
        assertEquals(Command.Kind.ALARM_ADD, Ask.parse("nifty 24000 aaye to bata dena").command?.kind)
        assertEquals(Command.Kind.ALARM_ADD, Ask.parse("alert me when banknifty goes below 51000").command?.kind)
    }

    @Test fun plainCommandsReadExactlyAsBefore() {
        assertEquals(Command(Command.Kind.STOP_ALL), Ask.parse("sab band kar do").command)
        assertEquals(Command(Command.Kind.STOP_ONE, target = "orb"), Ask.parse("stop orb").command)
        assertEquals(Command(Command.Kind.EXIT_ALL), Ask.parse("exit all").command)
        assertEquals(Command(Command.Kind.KILL_ON), Ask.parse("kill switch on").command)
        assertEquals(Command(Command.Kind.STOP_ALL), Ask.parse("stop all strategies").command)
        assertEquals(Command(Command.Kind.CLOSE_ALL), Ask.parse("close all").command)
        assertEquals(Command(Command.Kind.CLOSE_ALL), Ask.parse("square off everything if possible").command)
        assertEquals(Command.Kind.CLOSE_ONE, Ask.parse("exit my put").command?.kind)
        assertEquals(1, Ask.parse("sell 1 lot nifty 24000 call").order?.lots)
        assertEquals(2, Ask.parse("buy 2 lots banknifty atm put").order?.lots)
        for (s in listOf("sab band kar do", "stop orb", "exit all", "kill switch on", "close all", "sell 1 lot nifty 24000 call"))
            assertTrue(!Conditional.asked(s) && !Conditional.supposed(s) && !StopNoise.asked(s), s)
    }

    private val ROUND30 = listOf(
        // ExpiryHour said as the topic alone
        "expiry ke last hour mein premium" to "ExpiryHour", "last hour on expiry what happens to atm" to "ExpiryHour",
        "atm premium last hour expiry" to "ExpiryHour", "last hour of expiry atm option" to "ExpiryHour",
        "expiry ke aakhri ghante mein atm" to "ExpiryHour", "last hour on expiry day atm premiums" to "ExpiryHour",
        // StopNoise: "is it ok" and said short
        "is my sl ok" to "StopNoise", "mera stoploss sahi hai kya" to "StopNoise", "stop too close?" to "StopNoise", "stop too close" to "StopNoise",
        "is my stop ok" to "StopNoise", "is my stop loss fine" to "StopNoise", "are my stops fine" to "StopNoise", "mera sl theek hai kya" to "StopNoise",
        "mera stop loss sahi hai" to "StopNoise", "my stop too tight?" to "StopNoise", "sl too tight?" to "StopNoise", "stops too tight?" to "StopNoise",
        "is my sl right" to "StopNoise", "kya mera sl sahi hai" to "StopNoise", "are my sls ok" to "StopNoise", "mera sl sahi jagah hai kya" to "StopNoise",
        // CheckTimes without "my", and in Hinglish word order
        "when do i check pnl" to "CheckTimes", "when do i usually check p&l" to "CheckTimes", "what time do i check pnl" to "CheckTimes",
        "p&l kab dekhta hu main" to "CheckTimes", "kab dekhta hoon pnl" to "CheckTimes", "pnl kab dekhta hoon" to "CheckTimes",
    )

    @Test fun roundThirtyWordingsRouteAndNeverAct() {
        assertEquals(ROUND30.size, ROUND30.map { it.first }.distinct().size)
        val wrong = ROUND30.mapNotNull { (s, want) -> audit.feature(s).let { got -> if (got == want) null else "\"$s\": wanted $want, got $got" } }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        for ((s, _) in ROUND30) {
            neverActs(s)
            assertEquals(null, Reminder.parse(s, today.atTime(10, 0)), s)
            assertTrue(!Conditional.asked(s), s)
        }
        // Each stays itself: Boss's own premium, a forecast or a bare "last hour expiry" is not the record; advice, a change or a
        // bot's stop is not the noise check; when he checked once is not his usual times.
        for (s in listOf("expiry ke last hour mein mera premium", "last hour expiry", "expiry last hour atm will fall today")) assertEquals(null, ExpiryHour.asked(s), s)
        for (s in listOf("is my sl ok to move", "where should my stop be", "is orb stop ok", "stop orb too", "stop close")) assertTrue(!StopNoise.asked(s), s)
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("stop close").command?.kind)
        for (s in listOf("when did i check pnl", "pnl dikhao", "what is my pnl")) assertEquals(null, CheckTimes.asked(s), s)
    }
}
