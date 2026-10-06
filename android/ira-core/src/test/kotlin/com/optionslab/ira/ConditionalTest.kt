package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Understanding round 29: an action set to wait for a condition is never a command, an order or a yes - Jarvis says he
 * can't ([Conditional.SAY]) - while the same actions said plainly read exactly as before; and the newest answers' missed
 * wordings (AtmBuy, the positions' what-if in Hinglish, DayIndex, StraddleDecay said alone, "my small trades").
 */
class ConditionalTest {
    private val audit = CoverageTest()
    private val today: LocalDate = LocalDate.of(2026, 10, 5)

    private val CONDITIONAL = listOf(
        "agar nifty 100 point gire to sab band kar do", "agar nifty 100 point gire to sab band karo", "agar nifty gire to orb band kar do",
        "if nifty falls 100 points stop everything", "if nifty falls below 24000 exit all",
        "stop orb if nifty falls 100 points", "when nifty hits 25000 sell my call", "jab nifty 25000 ho jaye tab mera call sell kar do",
        "if banknifty breaks 52000 buy 1 lot atm call",
        "agar nifty upar jaye to sab cancel kar do", "if nifty crosses 25000 then square off everything", "agar nifty gire to 2 lot put kharido",
        "exit my put if it falls 50", "agar banknifty 500 point gire to mera put bech do",
        "jaise hi nifty 25000 cross kare sab orders cancel kar do",
    )

    /**
     * Said with a condition, but only taking risk off - the kill switch on, stop all, close all (review, 6 Oct): never refused
     * for the condition; each goes through as itself, to its own confirmation (still [Conditional.asked], never a yes, never
     * an order). Moved out of [CONDITIONAL].
     */
    private val RISK_OFF = listOf(
        "exit all if nifty falls below 24000" to Command.Kind.CLOSE_ALL, "agar loss 5000 ho jaye to kill switch on kar do" to Command.Kind.KILL_ON,
        "turn on kill switch if i lose 5000" to Command.Kind.KILL_ON, "as soon as nifty touches 24000 square off all" to Command.Kind.CLOSE_ALL,
        "in case nifty falls 1% stop all strategies" to Command.Kind.STOP_ALL, "once nifty breaks 24500 exit everything" to Command.Kind.CLOSE_ALL,
    )

    @Test fun aRiskReducingCommandIsNeverRefusedForItsCondition() {
        for ((s, k) in RISK_OFF) {
            assertTrue(Conditional.asked(s), s)
            assertEquals(k, Commands.parse(s)?.kind, s)
            val p = Ask.parse(s)
            assertEquals(k, p.command?.kind, s); assertEquals(null, p.order, s)
            assertTrue(Wake.yesNo(s) != true, s)
        }
        // A conditional order or one arm's stop stays refused.
        for (s in listOf("if banknifty breaks 52000 buy 1 lot atm call", "agar nifty gire to 2 lot put kharido", "stop orb if nifty falls 100 points",
            "when nifty hits 25000 sell my call", "agar banknifty 500 point gire to mera put bech do"))
            assertTrue(Commands.parse(s) == null && Ask.parse(s).order == null && Ask.parse(s).command == null, s)
        // Boss only supposing his own act is a what-if, never done; told beside it, the kill switch goes through.
        assertEquals(null, Commands.parse("if i square off everything"))
        assertEquals(Command.Kind.KILL_ON, Commands.parse("agar main 5000 loss karu to kill switch on kar do")?.kind)
    }

    @Test fun aConditionalInstructionNeverActsAndIsSaidSo() {
        for (s in CONDITIONAL) {
            assertTrue(Conditional.asked(s), s)
            assertEquals(null, Commands.parse(s), s)
            val p = Ask.parse(s)
            assertEquals(null, p.order, s); assertEquals(null, p.command, s)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
            assertTrue(!Reminder.asked(s) && !Reminder.cancelAsked(s) && !FollowUp.acts(s), s)
            Intents.quick(s)?.let { r -> assertTrue(Ask.parse(r).order == null && Ask.parse(r).command == null, "$s -> $r") }
            assertTrue(Understand.questions(null, s).orEmpty().none { FollowUp.acts(it) || Ask.parse(it).command != null || Ask.parse(it).order != null }, s)
            // Never a yes to anything waiting (a no word in it may still say no).
            assertTrue(Wake.yesNo(s) != true, s)
            // The hub's first branch after its parse: before any plan or question family.
            assertEquals("Conditional", audit.feature(s), s)
            assertEquals("Conditional", audit.feature(s, understood = true), s)
        }
        // The garbled arm's name is gone: "agar nifty 100 point fell to all" is no strategy.
        assertEquals(null, Ask.parse("agar nifty 100 point gire to sab band kar do").command)
        // Said: he can't, nothing was done, the app's own alarm, stop loss and limits - never an account figure.
        assertTrue(Conditional.SAY.contains("can't") && Conditional.SAY.contains("done nothing") && Conditional.SAY.contains("alert me when"))
        assertTrue(!Regex("Rs ?\\d|\\d+%").containsMatchIn(Conditional.SAY))
    }

    @Test fun theSameActionsSaidPlainlyReadExactlyAsBefore() {
        assertEquals(Command(Command.Kind.STOP_ALL), Ask.parse("sab band kar do").command)
        assertEquals(Command(Command.Kind.STOP_ONE, target = "orb"), Ask.parse("stop orb").command)
        assertEquals(Command(Command.Kind.STOP_ALL), Ask.parse("stop all strategies").command)
        assertEquals(Command(Command.Kind.KILL_ON), Ask.parse("kill switch on").command)
        assertEquals(Command(Command.Kind.KILL_ON), Ask.parse("turn on kill switch").command)
        assertEquals(Command(Command.Kind.EXIT_ALL), Ask.parse("exit all").command)
        assertEquals(Command(Command.Kind.STOP_ONE, target = "orb"), Ask.parse("orb band kar do").command)
        // "Kill switch" alone stays the account's risk question, never a command.
        assertEquals(null, Ask.parse("kill switch").command)
        // Words that set no condition: "if possible", "if you can", "at once".
        assertEquals(Command(Command.Kind.CLOSE_ALL), Ask.parse("square off everything if possible").command)
        assertEquals(Command(Command.Kind.CLOSE_ALL), Ask.parse("square off all if you can").command)
        assertEquals(Command(Command.Kind.CLOSE_ALL), Ask.parse("square off all positions at once").command)
        for (s in listOf("sab band kar do", "stop orb", "kill switch", "kill switch on", "exit all", "square off everything if possible", "square off all if you can"))
            assertTrue(!Conditional.asked(s), s)
    }

    @Test fun alarmsQuestionsNotesAndTimesAreNotConditional() {
        // The app's own alarms and reminders, as before.
        assertEquals(Command.Kind.ALARM_ADD, Ask.parse("alert me when banknifty goes below 51000").command?.kind)
        assertEquals(Command.Kind.ALARM_ADD, Ask.parse("remind me to sell when nifty hits 25000").command?.kind)
        // Questions about acting stay questions: the what-if, advice, the market's close.
        for (s in listOf("what if i buy nifty calls", "when does the market close today", "should i exit if nifty falls",
            "if nifty falls should i sell my call", "agar nifty gire to kya sab band kar du", "agar nifty 100 point gire to mera kya hoga",
            "if nifty is near 25000 alert me", "stop telling me when nifty moves", "note: if nifty falls i will sell", "kill switch", "exit all when it's 3:15"))
            assertTrue(!Conditional.asked(s), s)
        assertTrue(Exposure.moveAsked("agar nifty 100 point gire to mera kya hoga") != null)
        assertEquals(Command.Kind.NOTE, Ask.parse("note: if nifty falls i will sell").command?.kind)
        // A time alone is the timed request's (as before).
        assertEquals(Command.Kind.CLOSE_ALL, Ask.parse("exit all when it's 3:15").command?.kind)
    }

    @Test fun aYesWithAConditionIsUnclear() {
        for (s in listOf("haan agar nifty gire to", "yes if it falls", "ok when nifty is above 25000", "haan kar do agar nifty gire"))
            assertEquals(null, Wake.yesNo(s), s)
        // A no word still says no; a plain yes is still a yes.
        assertEquals(false, Wake.yesNo("no, not if it falls"))
        for (s in listOf("yes", "haan", "haan kar do", "ok", "yes go ahead", "yes as soon as possible")) assertEquals(true, Wake.yesNo(s), s)
    }

    /** The newest answers as Boss also says them (understanding round 29). */
    private val ROUND29 = listOf(
        // AtmBuy
        "atm put kitni baar double" to "AtmBuy", "how often does buying atm work" to "AtmBuy", "how often does buying atm options work" to "AtmBuy",
        "does buying atm options work" to "AtmBuy", "does buying atm options pay off" to "AtmBuy", "do atm buyers make money" to "AtmBuy",
        "atm buying kaam karta hai kya" to "AtmBuy", "how often does buying an atm call work" to "AtmBuy",
        // The positions' what-if in Hinglish
        "agar banknifty 200 point chadhe to mujhe kitna nuksan hoga" to "Account:MOVE", "nifty 100 point neeche gaya to mera kitna loss hoga" to "Account:MOVE",
        "nifty 200 point gire toh meri positions ka kya haal hoga" to "Account:MOVE", "nifty 1 percent gira to mere p&l pe kya asar hoga" to "Account:MOVE",
        "agar nifty 100 point upar jaye to mera kitna profit hoga" to "Account:MOVE", "banknifty 300 point gire to kitna jayega mera" to "Account:MOVE",
        // DayIndex
        "aaj kaunsa index pehle" to "DayIndex", "aaj kaunsa index pehle bologe" to "DayIndex", "which index first today" to "DayIndex",
        "which index are you leading with today" to "DayIndex", "kaunsa index pehle aaj" to "DayIndex",
        // StraddleDecay said alone: the record (the definition is asked as one, "what is straddle decay")
        "straddle decay" to "StraddleDecay", "the straddle decay" to "StraddleDecay", "nifty straddle decay" to "StraddleDecay",
        "atm straddle decay for banknifty" to "StraddleDecay",
        // SmallTrades
        "my small trades" to "SmallTrades", "show my small trades" to "SmallTrades", "my tiny trades" to "SmallTrades",
        "mere chhote trades dikhao" to "SmallTrades", "how are my small trades" to "SmallTrades",
    )

    @Test fun roundTwentyNineWordingsRouteAndNeverAct() {
        assertEquals(ROUND29.size, ROUND29.map { it.first }.distinct().size)
        val wrong = ROUND29.mapNotNull { (s, want) -> audit.feature(s).let { got -> if (got == want) null else "\"$s\": wanted $want, got $got" } }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        for ((s, _) in ROUND29) {
            val p = Ask.parse(s)
            assertEquals(null, p.order, s); assertEquals(null, p.command, s)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
            assertTrue(!Bundle.acts(s), s)
            assertTrue(!Reminder.asked(s) && !Reminder.cancelAsked(s) && !FollowUp.acts(s), s)
            assertEquals(null, Reminder.parse(s, today.atTime(10, 0)), s)
            assertTrue(Wake.yesNo(s) != true, s)
            assertTrue(!Conditional.asked(s), s)
        }
        // Each stays itself: a definition, a forecast, an order or Boss's own straddle is not the record; the what-if with an
        // exit in it is never the estimate; a small trade asked for is not his small trades.
        for (s in listOf("what is straddle decay", "explain straddle decay", "will the straddle decay today", "my straddle decay", "short straddle decay"))
            assertEquals(null, StraddleDecay.asked(s), s)
        for (s in listOf("should i buy atm options", "buy atm call", "will buying atm work today", "does selling atm options work"))
            assertEquals(null, AtmBuy.asked(s), s)
        for (s in listOf("agar nifty 100 point gire to mera call bech do", "nifty 100 point gire to kitna loss hoga kya karu"))
            assertEquals(null, Exposure.moveAsked(s), s)
        for (s in listOf("make my small trades bigger", "buy small trades")) assertEquals(null, SmallTrades.asked(s), s)
        for (s in listOf("aaj nifty kaisa hai", "which index is strongest today")) assertEquals(null, DayIndex.asked(s), s)
    }

    /**
     * Usefulness round 38: something concrete instead of a bare refusal - a price alarm offered at the level said (only an
     * alarm, only one index, one direction and one level; never a stop, exit, order or the kill switch), or the loss limit
     * for a loss amount (locked: where it is, no figure).
     */
    @Test fun aRefusedConditionOffersTheAlarmAtItsLevel() {
        fun alarm(s: String) = Conditional.instead(s)?.alarm
        assertEquals(Command(Command.Kind.ALARM_ADD, market = Market.NIFTY, above = false, level = 24000.0), alarm("if nifty falls below 24000 exit all"))
        assertEquals(Command(Command.Kind.ALARM_ADD, market = Market.NIFTY, above = false, level = 24000.0), alarm("exit all if Nifty falls below 24,000."))
        assertEquals(Command(Command.Kind.ALARM_ADD, market = Market.NIFTY, above = false, level = 24000.0), alarm("if nifty 50 falls below 24000 exit all"))
        assertEquals(Command(Command.Kind.ALARM_ADD, market = Market.NIFTY, above = true, level = 25000.0), alarm("if nifty crosses 25000 then square off everything"))
        assertEquals(Command(Command.Kind.ALARM_ADD, market = Market.NIFTY, above = true, level = 25000.0), alarm("jaise hi nifty 25000 cross kare sab orders cancel kar do"))
        assertEquals(Command(Command.Kind.ALARM_ADD, market = Market.BANKNIFTY, above = false, level = 51000.0), alarm("agar banknifty 51000 ke neeche jaye to sab band karo"))
        // Not clear enough: a move in points or percent, no index, two indices, no direction, gold, two levels - the plain SAY.
        for (s in listOf("agar nifty 100 point gire to sab band kar do", "if nifty falls 100 points stop everything", "in case nifty falls 1% stop all strategies",
            "exit my put if it falls 50", "when nifty hits 25000 sell my call", "once nifty breaks 24500 exit everything", "as soon as nifty touches 24000 square off all",
            "if nifty falls below 24000 and banknifty below 51000 exit all", "if gold falls below 4000 sell", "sell my 24500 put if nifty falls below 24000",
            "agar nifty upar jaye to sab cancel kar do"))
            assertEquals(null, alarm(s), s)
        // An option named: the level is its premium or strike, never the index's - no alarm (review, 6 Oct); still refused.
        for (s in listOf("if my banknifty call falls below 1500 exit", "if banknifty put premium drops below 1500 exit it",
            "agar nifty 24500 ce 100 ke neeche jaye to exit kar do", "if my nifty pe falls below 1200 sell it", "if nifty option falls below 1500 exit")) {
            assertTrue(Conditional.asked(s), s)
            assertEquals(null, alarm(s), s)
            assertEquals(null, Commands.parse(s), s)
        }
        // Hinglish "pe" as "at" is no option: the alarm is still offered.
        assertEquals(Command(Command.Kind.ALARM_ADD, market = Market.BANKNIFTY, above = false, level = 51000.0), alarm("agar banknifty 51000 pe gire to sab band karo"))
        // Never for anything that is not a refused condition: a plain command or an alarm already asked for.
        for (s in listOf("exit all", "alert me when nifty goes below 24000", "nifty below 24000"))
            assertEquals(null, Conditional.instead(s), s)
        // Only ever an alarm: the offered command is ALARM_ADD and nothing else.
        for (s in CONDITIONAL + RISK_OFF.map { it.first }) Conditional.instead(s)?.alarm?.let { assertEquals(Command.Kind.ALARM_ADD, it.kind, s) }
        val c = alarm("if nifty falls below 24000 exit all")!!
        assertEquals("Nifty below 24,000", Conditional.alarmWhat(c))
        val said = Conditional.alarmSay(c)
        assertTrue(said.contains("can't") && said.contains("done nothing") && said.contains("never trades") && said.contains("Shall I set it?"), said)
        assertTrue(Conditional.alarmAsk(c).endsWith("never trades."))
        // The offer is the very alarm the plain request sets (same command, so the same path and confirmations).
        assertEquals(Commands.parse("alert me when nifty goes below 24000"), c)
        // The words of the offer itself are never a yes to it.
        assertTrue(Wake.yesNo(said) != true)
    }

    @Test fun aLossConditionAnswersWithTheLossLimit() {
        fun loss(s: String) = Conditional.instead(s)?.loss
        assertEquals(5000.0, loss("turn on kill switch if i lose 5000"))
        assertEquals(5000.0, loss("agar loss 5000 ho jaye to kill switch on kar do"))
        assertEquals(5000.0, loss("agar 5000 ka loss ho to sab band karo"))
        assertEquals(3000.0, loss("if my loss crosses 3,000 exit all"))
        assertEquals(4000.0, loss("stop all strategies if i'm down 4k"))
        for (s in CONDITIONAL + RISK_OFF.map { it.first }) Conditional.instead(s)?.let { assertTrue(it.alarm == null || it.loss == null, s) }
        val open = Conditional.lossSay(5000.0, 6000.0, "Paper", locked = false)
        assertTrue(open.contains("Rs 6,000") && open.contains("more than the Rs 5,000") && open.contains("Bot settings") && open.contains("haven't changed"), open)
        assertTrue(Conditional.lossSay(5000.0, 2000.0, "Zerodha", false).contains("less than"))
        assertTrue(Conditional.lossSay(5000.0, 0.0, "Paper", false).contains("off for Paper"))
        // Locked (or not read): never a figure of his - only where it is.
        for (l in listOf(Conditional.lossSay(5000.0, 6000.0, "Paper", locked = true), Conditional.lossSay(5000.0, null, "Paper", false))) {
            assertTrue(!l.contains("6,000") && !l.contains("5,000") && l.contains("unlock") && l.contains("Bot settings"), l)
        }
    }
}
