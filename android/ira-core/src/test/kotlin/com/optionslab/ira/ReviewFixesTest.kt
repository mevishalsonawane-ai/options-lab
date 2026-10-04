package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The line-by-line review's fixes, so they stay fixed. */
class ReviewFixesTest {
    @Test fun questionsAreNeverCommands() {
        assertNull(Commands.parse("Is live mode on?"))
        assertNull(Commands.parse("don't switch to live mode"))
        assertEquals(Command.Kind.STOP_ONE, Commands.parse("stop strategy 2")?.kind)
        assertEquals(Command.Kind.START_ALL, Commands.parse("start all strategies")?.kind)
    }

    @Test fun aTypoNeverTurnsLiveOn() {
        val k = Commands.parse("switch to lime mode")?.kind
        assertTrue(k != Command.Kind.MODE_LIVE)
    }

    @Test fun aQuestionIsNeverAnOrder() {
        assertFalse(Topic.ORDER in Ask.parse("Did I buy 2 lots of Nifty 24000 call?").topics)
    }

    @Test fun secretsAreHiddenToTheEndOfTheClause() {
        val r = Secrets.redact("My PIN number is 4321")
        assertFalse(r.contains("4321"), r)
        assertFalse(Secrets.redact("call me on 98765 43210").contains("43210"))
        assertFalse(Secrets.redact("aadhaar 1234-5678-9012").contains("9012"))
        assertEquals("strikes 24000 24100 24200", Secrets.redact("strikes 24000 24100 24200"))
        assertFalse(Secrets.redact("card 4111 1111 1111 1111").contains("1111 1111"))
    }

    @Test fun moreWaysOfSayingNo() {
        for (s in listOf("nhi", "haan... nhi", "not now", "hold off", "abort", "later", "noo")) assertEquals(false, Wake.yesNo(s), s)
        assertEquals(true, Wake.yesNo("yes go ahead"))
    }

    @Test fun minusOnlyBeforeANumber() {
        assertEquals("P&L minus 500 rupees.", Wake.spoken("P&L Rs -500."))
        assertTrue(!Wake.spoken("Nifty - steady.").contains("minus"))
    }

    @Test fun monthsNeedTheirOwnWord() {
        val today = LocalDate.of(2026, 10, 2)
        assertNull(Events.date("5 market days", today))
        assertEquals(LocalDate.of(2026, 12, 5), Events.date("5 december", today))
        assertEquals(LocalDate.of(2026, 12, 5), Events.date("dec 5", today))
    }

    @Test fun bossAddress() {
        assertEquals("Hi Boss. how can I help?", Address.boss("Hi there, how can I help?").replace(" ,", ""))
        assertEquals("Boss, Nifty's range is narrow.", Address.boss("Nifty's range is narrow."))
    }
}
