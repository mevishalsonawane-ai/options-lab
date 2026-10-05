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
}
