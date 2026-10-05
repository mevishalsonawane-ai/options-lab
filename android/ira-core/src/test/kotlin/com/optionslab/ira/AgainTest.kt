package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgainTest {
    private val said = "Boss, Nifty is at 24,612, up 0.21 percent. Support is 24,500 and resistance 24,700. The rest is in the chat."
    private val last = Again.Last(said, account = false, at = 1_000L)

    @Test fun aSlowRepeatIsHeardInEveryWay() {
        for (w in listOf("say that again slowly", "Jarvis, repeat it slower", "repeat that slowly please", "can you say it again a bit slower",
            "once more, slowly", "again slowly", "slowly again", "tell me that again more slowly", "say it slowly",
            "dobara dheere bolo", "phir se thoda dheere", "dheere se phir se bolo", "ek baar aur aaram se batao", "Jarvis dubara dhire boliye"))
            assertEquals(Again.Ask(Again.What.SLOW, true), Again.read(w), w)
    }

    @Test fun theLastFigureAskedAgain() {
        for (w in listOf("say the last number again", "what was that number?", "what was the figure you just said", "which level did you say",
            "repeat that figure", "tell me the level again", "the last number again please", "woh number phir se bolo", "kya number tha",
            "kitna bola", "Jarvis, what was that price again"))
            assertEquals(Again.What.NUMBER, Again.read(w)?.what, w)
        // A figure asked again is always said slowly.
        assertTrue(Again.read("say the last number again")!!.slow)
    }

    @Test fun allTheFiguresAskedAgain() {
        for (w in listOf("just the numbers", "only the figures please", "repeat the numbers", "give me the numbers once more",
            "sirf numbers batao", "numbers phir se bolo", "say all the figures again slowly"))
            assertEquals(Again.What.NUMBERS, Again.read(w)?.what, w)
    }

    @Test fun otherWordsAreNotARepeat() {
        // A lasting pace change stays a command; a plain repeat stays "tell me more"; real questions are questions.
        for (w in listOf("speak slower", "slow down", "thoda dheere bolo", "repeat that", "say that again", "tell me more",
            "what is the price", "tell me the last price", "what was the last price of BankNifty", "what is nifty at",
            "the market is slow", "is it moving slowly", "what levels matter today", "buy 2 lots of nifty", ""))
            assertNull(Again.read(w), w)
    }

    @Test fun theyNeverBecomeACommandOrAnOrder() {
        for (w in listOf("say that again slowly", "say the last number again", "just the numbers", "dobara dheere bolo", "kitna bola")) {
            val p = Ask.parse(w)
            assertNull(p.order, w)
            assertTrue(p.command == null || p.command?.kind == Command.Kind.MORE, w)
        }
    }

    @Test fun eachFigureWithTheWordsThatTellWhatItIs() {
        assertEquals(listOf("Nifty is at 24,612", "up 0.21 percent", "Support is 24,500", "resistance 24,700"), Again.figures(said))
        assertEquals(listOf("you lost 1.23 lakh rupees today"), Again.figures("Boss, you lost 1.23 lakh rupees today."))
        assertEquals(listOf("The high came at 9:40", "Your stop is Rs 1,400"), Again.figures("The high came at 9:40. Your stop is Rs 1,400."))
        // A long clause: the last few words before the figure.
        assertEquals(listOf("strongest call writing at 24,800"),
            Again.figures("Across the whole chain today the strongest call writing at 24,800 is what caps it."))
        assertEquals(listOf("Nifty 24,612"), Again.figures("Boss, the levels: Nifty 24,612."))
        assertEquals(emptyList(), Again.figures("Boss, the market is quiet. Nothing new."))
    }

    @Test fun theLastFigureIsSaid() {
        val r = Again.reply(Again.Ask(Again.What.NUMBER, true), last, 5_000L, locked = false)
        assertEquals(Again.Reply.Say("Boss, the last figure was resistance 24,700.", true, false), r)
    }

    @Test fun allTheFiguresAreSaidInOrder() {
        val r = Again.reply(Again.Ask(Again.What.NUMBERS, true), last, 5_000L, locked = false) as Again.Reply.Say
        assertEquals("Boss, the figures: Nifty is at 24,612; up 0.21 percent; support is 24,500; resistance 24,700.", r.text)
        val many = Again.Last("A 1. B 2. C 3. D 4. E 5. F 6. G 7.", false, 1_000L)
        val m = Again.reply(Again.Ask(Again.What.NUMBERS, true), many, 2_000L, false) as Again.Reply.Say
        assertTrue(m.text.endsWith("; and 2 more in the chat."), m.text)
    }

    @Test fun aSlowRepeatSaysTheSameWordsSlowerForThatAnswerOnly() {
        val r = Again.reply(Again.Ask(Again.What.SLOW, true), last, 5_000L, locked = false) as Again.Reply.Say
        assertEquals(said, r.text)
        assertTrue(r.slow)
        assertEquals(0.8f, Again.rate(1f, true), 0.001f)
        assertEquals(1.2f, Again.rate(1.2f, false), 0.001f)
        // Never faster than normal speech, never so slow it drags.
        assertEquals(1f, Again.rate(1.45f, true), 0.001f)
        assertEquals(0.56f, Again.rate(0.7f, true), 0.001f)
    }

    @Test fun anAccountAnswerIsNotRepeatedOnALockedPhone() {
        val acct = Again.Last("Boss, you are up Rs 3,400 today.", account = true, at = 1_000L)
        for (w in Again.What.values()) {
            val r = Again.reply(Again.Ask(w, true), acct, 5_000L, locked = true)
            assertTrue(r is Again.Reply.Refused, "$w")
            assertTrue((r as Again.Reply.Refused).why.contains("unlock the phone"), r.why)
        }
        // Unlocked: said.
        assertTrue(Again.reply(Again.Ask(Again.What.NUMBER, true), acct, 5_000L, locked = false) is Again.Reply.Say)
        // A market answer on a locked phone is fine.
        assertTrue(Again.reply(Again.Ask(Again.What.SLOW, true), last, 5_000L, locked = true) is Again.Reply.Say)
    }

    @Test fun nothingLatelyOrNoFigureIsSaidHonestly() {
        val none = Again.reply(Again.Ask(Again.What.SLOW, true), null, 5_000L, false)
        assertTrue(none is Again.Reply.None && none.why.startsWith("Boss,"), "$none")
        val old = Again.reply(Again.Ask(Again.What.SLOW, true), last, last.at + Again.KEEP_MS + 1, false)
        assertTrue(old is Again.Reply.None)
        val noFig = Again.reply(Again.Ask(Again.What.NUMBER, true), Again.Last("Boss, the market is quiet.", false, 1_000L), 2_000L, false)
        assertEquals(Again.Reply.None("Boss, there was no figure in what I just said."), noFig)
    }
}
