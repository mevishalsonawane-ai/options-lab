package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AloudTest {
    @Test fun figuresAreSaidAsAPersonSaysThem() {
        assertEquals("Nifty is at 24,612 (plus 0.21 percent).", Aloud.numbers(Wake.spoken("Nifty is at 24,612.40 (+0.21%).")))
        assertEquals("24,613 and 1,23,457 and 1.23 and 0.5", Aloud.numbers("24,612.50 and 1,23,456.78 and 1.2345 and 0.50"))
        assertEquals("999 became 1000.", Aloud.numbers("999 became 999.6."))
        assertEquals("up 120 points, then 1 point", Aloud.numbers("up 120pts, then 1 pt"))
        assertEquals("at 9:15 and 15:36", Aloud.numbers("at 09:15 and 15:35:42"))
        // Dates, versions and names with digits stay as written.
        assertEquals("on 05.10.2026 with v1.2.3 and NIFTY24600.5CE", Aloud.numbers("on 05.10.2026 with v1.2.3 and NIFTY24600.5CE"))
    }

    @Test fun bossOnceNotInEverySentence() {
        assertEquals("Yes, Boss. Nifty is up. It is fine.", Aloud.onceBoss("Yes, Boss. Nifty is up, Boss. Boss, it is fine."))
        assertEquals("Boss, yes, it is up.", Aloud.onceBoss("Boss, yes Boss, it is up."))
        assertEquals("No name here.", Aloud.onceBoss("No name here."))
        assertEquals("Bossy words stay, Boss.", Aloud.onceBoss("Bossy words stay, Boss."))
    }

    @Test fun aLongReplyIsCutWithTheRestInTheChat() {
        val text = "Nifty is at 24,612.40, Boss. It rose 0.21% today. Banks led. IT lagged. Volume was light."
        assertEquals("Nifty is at 24,612, Boss. It rose 0.21 percent today. Banks led. The rest is in the chat.", Aloud.say(text))
        assertEquals("Nifty is at 24,612, Boss. The rest is in the chat.", Aloud.say(text, Aloud.Length.SHORT))
        assertFalse(Aloud.say(text, Aloud.Length.FULL).contains("the chat"), "all of it fits: nothing left for the chat")
        // A short one is said whole, and always to Boss.
        assertEquals("Boss, the market is flat.", Aloud.say("The market is flat."))
        assertEquals("", Aloud.say("   "))
    }

    @Test fun hindiStaysHindi() {
        val hi = "बॉस, निफ्टी 24,612.40 पर है। आज यह +0.21% ऊपर है। बैंक आगे रहे। आईटी पीछे रहा।"
        assertTrue(Aloud.hindi(hi))
        val said = Aloud.say(hi)
        assertEquals("बॉस, निफ्टी 24,612 पर है। आज यह प्लस 0.21 प्रतिशत ऊपर है। बैंक आगे रहे। बाकी चैट में है।", said)
        assertFalse(said.contains("Boss"), "named once, in Hindi")
        assertFalse(said.contains("percent") || said.contains("rupees") || said.contains("chat"))
        assertEquals("Boss, निफ्टी 500 रुपये ऊपर।", Aloud.say("निफ्टी ₹500 ऊपर।"))
    }

    @Test fun bossSetsHowMuchIsSaid() {
        fun k(s: String) = Commands.parse(s)?.kind
        for (s in listOf("shorter", "Jarvis, shorter please", "say less", "too long", "less detail")) assertEquals(Command.Kind.BRIEF_ON, k(s), s)
        for (s in listOf("longer answers", "in more detail", "more detail always")) assertEquals(Command.Kind.BRIEF_OFF, k(s), s)
        // "Tell me more" still says the last answer in full; a question about length is never a setting.
        assertEquals(Command.Kind.MORE, k("more details"))
        assertEquals(null, k("is that shorter?"))
        assertEquals(null, k("don't be shorter"))
    }

    // ---- safety verdicts and warnings are never cut (review, 5 Oct) ----------------------------------------------------

    private fun stopCheck() = TradeCheck.Verdict(TradeCheck.Level.STOP,
        listOf(TradeCheck.Reason(TradeCheck.Level.STOP, "Zerodha is not logged in today."),
            TradeCheck.Reason(TradeCheck.Level.CAREFUL, "Expiry today: option prices decay and jump fast.")),
        listOf("Nifty is bullish on the 15-minute chart.", "Bank Nifty has no clear trend.")).say()

    @Test fun aTradeCheckSaidShortSaysItsVerdictFirstWithItsReasons() {
        val said = Aloud.say(stopCheck(), Aloud.Length.SHORT)
        assertTrue(said.startsWith("Boss, don't trade now."), said)
        assertTrue(said.contains("Zerodha is not logged in today."), said)
        assertTrue(said.contains("Expiry today"), said)
        assertTrue(said.contains("Boss"), said)
        // The market reads are what is left for the chat.
        assertFalse(said.contains("bullish"), said)
        assertTrue(said.endsWith("The rest is in the chat."), said)
    }

    @Test fun keepPutsTheVerdictFirstAndKeepsEveryWarning() {
        val parts = listOf("Nifty is up 0.4 percent.", "It broke 24,500.", "Careful: the kill switch is on.", "Volume is light.",
            "Do not trade past the loss limit.")
        assertEquals(listOf("Nifty is up 0.4 percent.", "Careful: the kill switch is on.", "Do not trade past the loss limit."), Aloud.keep(parts, 1))
        assertEquals(parts, Aloud.keep(parts, 8), "nothing cut when it fits")
        val careful = listOf("Nifty is bullish.", "Careful today.", "Event today: RBI policy.", "That is how the market is moving now, not a forecast.")
        assertEquals(listOf("Careful today.", "Event today: RBI policy."), Aloud.keep(careful, 1))
        val go = listOf("Nifty is bullish.", "Conditions are normal: fine to trade.", "Nothing unusual.", "That is how the market is moving now, not a forecast.")
        assertEquals(listOf("Conditions are normal: fine to trade."), Aloud.keep(go, 1))
        for (w in listOf("Don't trade now.", "do not trade today", "Careful today.", "Stop for today.", "The kill switch is on.",
                "You are near the daily loss limit.", "Warning: prices are stale.", "Not now, Boss."))
            assertTrue(Aloud.warning(w), w)
        assertFalse(Aloud.warning("Nifty is at 24,100."))
    }

    /** Review, 5 Oct: these were cut when said shorter ("Trailing stop moved." kept only by its "stop"). */
    @Test fun theWiderWarningsAreKept() {
        for (w in listOf("You are near the max loss.", "Maximum loss is close, Boss.", "Exit now.", "Margin is short.",
                "Expiry today: close MIS by 15:10.", "Expires today at 15:30.", "Trailing stop moved.", "Your stop loss was hit.",
                "Stop loss triggered on Nifty.", "Square off by 15:20.", "The square-off is at 15:15.", "Loss limit hit.",
                "The guard is on.", "Kill switch on.", "Do not trade the open.", "Careful with size."))
            assertTrue(Aloud.warning(w), w)
        for (w in listOf("Nifty is at 24,100.", "The trend is up.", "Volume is normal.", "Bank Nifty has no clear trend."))
            assertFalse(Aloud.warning(w), w)
        val parts = listOf("Nifty is up 0.4 percent.", "It broke 24,500.", "Volume is light.", "Expiry today: close MIS by 15:10.", "Margin is short.")
        assertEquals(listOf("Nifty is up 0.4 percent.", "Expiry today: close MIS by 15:10.", "Margin is short."), Aloud.keep(parts, 1))
    }

    @Test fun everyShorteningKeepsTheWarning() {
        val text = "Nifty is at 24,100. It is up today. The trend is up. Careful: India VIX is jumping. Volume is normal."
        // Wake.spoken (every short line), brief mode / Clarity (Aloud.say), the quiet hours (TalkHours.aloud).
        assertTrue(Wake.spoken(text, 1).contains("Careful: India VIX is jumping."))
        assertTrue(Aloud.say(text, 1).contains("India VIX is jumping"))
        assertTrue(TalkHours.aloud(text, java.time.LocalDateTime.of(2026, 10, 5, 22, 0), listOf(9, 10)).contains("Careful: India VIX is jumping."))
    }

    @Test fun rupeesSaidWithTheCommaAfterTheFigureNotInIt() {
        // Voice, round 26: "Rs 1,234, mostly brokerage" was said "1,234, rupees mostly", and a lakh followed by a comma
        // was never said in lakh.
        assertEquals("Charges 1,234 rupees, mostly brokerage.", Wake.spoken("Charges Rs 1,234, mostly brokerage."))
        assertEquals("Boss, brokerage 240 rupees, STT 1.02 lakh rupees, exchange 12 rupees; 1.03 lakh rupees in all.",
            Aloud.say("Boss, brokerage Rs 240, STT Rs 1,02,345, exchange Rs 12; Rs 1,02,600 in all."))
        assertEquals("बॉस, STT 1.02 लाख रुपये, बाकी ठीक है।", Aloud.say("बॉस, STT Rs 1,02,345, बाकी ठीक है।"))
        // Losses said with "minus"; unchanged figures and signs elsewhere.
        assertEquals("Boss, minus 450 rupees before charges, minus 497 rupees after.", Aloud.say("Boss, -Rs 450 before charges, -Rs 497 after."))
        assertEquals("P&L minus 500 rupees.", Wake.spoken("P&L Rs -500."))
        assertEquals("Options: plus 1,200 rupees a lot.", Wake.spoken("Options: Rs +1,200 a lot."))
    }

    @Test fun smallAmountsInRupeesAndPaiseBigOnesWhole() {
        assertEquals("Boss, P&L is 40 paise.", Aloud.say("Boss, P&L is Rs 0.40."))
        assertEquals("Boss, charges 12 rupees 40 paise and 151 rupees.", Aloud.say("Boss, charges Rs 12.4 and Rs 150.5."))
        assertEquals("Boss, 1 rupee 5 paise.", Aloud.say("Boss, Rs 1.05."))
        assertEquals("Boss, minus 3 rupees.", Aloud.say("Boss, -Rs 3.00."))
        assertEquals("Boss, net minus 12,346 rupees today.", Aloud.say("Boss, net -Rs 12,345.50 today."))
        assertEquals("बॉस, शुल्क 12 रुपये 40 पैसे है।", Aloud.say("बॉस, शुल्क Rs 12.40 है।"))
        assertEquals("बॉस, 1 रुपया 50 पैसे।", Aloud.say("बॉस, Rs 1.5।"))
        // Not rupees: points and percent keep their decimals; applying it twice changes nothing.
        assertEquals("Boss, Nifty minus 85.3 points, 0.46 percent.", Aloud.say("Boss, Nifty -85.30 pts, 0.46%."))
        assertEquals("12 rupees 40 paise", SayAs.figures(SayAs.figures("12.4 rupees")))
    }
}
