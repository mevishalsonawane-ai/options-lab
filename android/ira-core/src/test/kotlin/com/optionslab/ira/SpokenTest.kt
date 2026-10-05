package com.optionslab.ira

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Numbers and times said in words (2026-10-05): read as digits for questions - and never for anything that acts. */
class SpokenTest {
    private fun d(s: String) = Spoken.digits(s)

    // ---- Numbers ----

    @Test fun englishNumbers() {
        assertEquals("how far is nifty from 24500", d("how far is nifty from twenty four thousand five hundred"))
        assertEquals("how far is nifty from 24500", d("how far is nifty from twenty-four thousand five hundred"))
        assertEquals("how far is banknifty from 52000", d("how far is banknifty from fifty two thousand"))
        assertEquals("250 points", d("two hundred and fifty points"))
        assertEquals("2500", d("twenty five hundred"))
        assertEquals("100 points", d("hundred points"))
        assertEquals("100 points", d("a hundred points"))
        assertEquals("sensex 81000", d("sensex eighty one thousand"))
        assertEquals("120000", d("one lakh twenty thousand"))
        assertEquals("200000", d("two hundred thousand"))
        assertEquals("top 2 movers", d("top two movers"))
        assertEquals("21 points", d("twenty one points"))
    }

    @Test fun numbersHalfWrittenInDigits() {
        assertEquals("nifty 24500 ce price", d("nifty 24 5 hundred ce price"))
        assertEquals("nifty 24500", d("nifty twenty four five hundred"))
        assertEquals("nifty 24500", d("nifty 24 thousand 500"))
        assertEquals("nifty 24500", d("nifty 24 thousand five hundred"))
        assertEquals("200000", d("2 lakh"))
        assertEquals("150000", d("1.5 lakh"))
        assertEquals("how far is nifty from 25000", d("how far is nifty from 25k"))
        assertEquals("how far is nifty from 25000", d("how far is nifty from 25 k"))
        assertEquals("how far is nifty from 25500", d("how far is nifty from 25.5k"))
    }

    @Test fun hindiNumbers() {
        assertEquals("nifty 24500 se kitna door hai", d("nifty chaubees hazaar paanch sau se kitna door hai"))
        assertEquals("nifty 24500", d("nifty chaubees paanch sau"))
        assertEquals("225000", d("do lakh pachees hazaar"))
        assertEquals("120 points", d("ek sau bees points"))
        assertEquals("banknifty 52000", d("banknifty bavan hazaar"))
        assertEquals("5 percent", d("paanch percent"))
    }

    @Test fun fractionsAndDecimals() {
        assertEquals("0.5 percent", d("point five percent"))
        assertEquals("1.25 percent", d("one point two five percent"))
        assertEquals("1.5 percent", d("one and a half percent"))
        assertEquals("0.5 percent", d("half a percent"))
        assertEquals("0.5 percent", d("half percent"))
        assertEquals("150000", d("one point five lakh"))
        assertEquals("150000", d("dedh lakh"))
        assertEquals("250 points", d("dhai sau points"))
        assertEquals("1.5 percent", d("dedh percent"))
        assertEquals("24500", d("saade chaubees hazaar"))
        assertEquals("350", d("saade teen sau"))
        assertEquals("225000", d("sawa do lakh"))
        assertEquals("175000", d("paune do lakh"))
    }

    @Test fun wordsThatOnlySoundLikeNumbersStay() {
        for (s in listOf("how is nifty now", "how do i use this", "which one is losing", "no one knows", "nifty fifty kaisa hai", "bank fifty kaisa hai",
            "nifty ke saath banknifty", "first half of the day", "what's the point", "is it a good day", "ek baar aur batao", "teen", "so what",
            "how is nifty today", "nifty 24500 ce price", "nifty at 3:15 pm", "24,500"))
            assertEquals(s, d(s), s)
    }

    // ---- Times ----

    @Test fun englishTimes() {
        assertEquals("what was nifty at 10:30", d("what was nifty at half past ten"))
        assertEquals("what was nifty at 2:15", d("what was nifty at quarter past two"))
        assertEquals("what was nifty at 10:45", d("what was nifty at quarter to eleven"))
        assertEquals("what was nifty at 12:45", d("what was nifty at quarter to one"))
        assertEquals("what was banknifty at 10:30", d("what was banknifty at ten thirty"))
        assertEquals("what was nifty at 3:15 pm", d("what was nifty at three fifteen pm"))
        assertEquals("what was nifty at 3:15 pm", d("what was nifty at 3 15 pm"))
        assertEquals("what was nifty at 9:05 am", d("what was nifty at nine oh five am"))
        assertEquals("what was nifty at 10:00", d("what was nifty at ten o'clock"))
        assertEquals("what was nifty at 10 am", d("what was nifty at ten am"))
        assertEquals("what was nifty at 10:30", d("what was nifty at 10 30"))
        assertEquals("what was nifty at 11:45 am", d("what was nifty at eleven forty five a.m."))
    }

    @Test fun hindiTimes() {
        assertEquals("nifty at 10:30 kahan tha", d("nifty saade das baje kahan tha"))
        assertEquals("nifty at 1:30 kahan tha", d("nifty dedh baje kahan tha"))
        assertEquals("nifty at 2:30 kahan tha", d("nifty dhai baje kahan tha"))
        assertEquals("nifty at 10:15 kahan tha", d("nifty sawa das baje kahan tha"))
        assertEquals("nifty at 9:45 kahan tha", d("nifty paune das baje kahan tha"))
        assertEquals("nifty at 11 kahan tha", d("nifty gyarah baje kahan tha"))
        assertEquals("at 4 pm nifty kahan tha", d("shaam chaar baje nifty kahan tha"))
        assertEquals("at 9:30 am nifty kahan tha", d("subah saade nau baje nifty kahan tha"))
        assertEquals("nifty 10:30 tak kahan tha", d("nifty saade das baje tak kahan tha"))
    }

    @Test fun aNumberIsNotTakenForATime() {
        assertEquals("what was nifty at 10050", d("what was nifty at ten thousand fifty"))
        assertEquals("nifty 10 30", d("nifty ten thirty"), "no \"at\", no am / pm: two numbers, no time")
    }

    @Test fun theQuestionReadersUnderstandTheDigits() {
        val far = Spoken.question("how far is nifty from twenty four thousand five hundred")
        assertEquals(Distance.Asked(Market.NIFTY, 24500.0), Distance.asked(far))
        assertNull(Distance.asked("how far is nifty from twenty four thousand five hundred"), "before: not understood")
        assertEquals(Distance.Asked(Market.NIFTY, 24500.0), Distance.asked(Spoken.question("nifty chaubees hazaar paanch sau se kitna door hai")))
        assertEquals(Distance.Asked(Market.BANKNIFTY, 52000.0), Distance.asked(Spoken.question("how far is banknifty from 52k")))

        val move = Exposure.moveAsked(Spoken.question("what happens to my p&l if nifty moves hundred points"))
        assertEquals(Exposure.Shock(Market.NIFTY, 100.0, null, 0), move)
        assertNull(Exposure.moveAsked("what happens to my p&l if nifty moves hundred points"), "before: not understood")
        assertEquals(Exposure.Shock(Market.BANKNIFTY, null, 0.5, -1), Exposure.moveAsked(Spoken.question("if banknifty falls point five percent what do i lose")))

        assertEquals(OptionFacts.Asked.Quote(Market.NIFTY, 24500, "CE"), OptionFacts.asked(Spoken.question("nifty twenty four thousand five hundred ce price")))
        assertEquals(OptionFacts.Asked.Quote(Market.NIFTY, 24500, "CE"), OptionFacts.asked(Spoken.question("nifty 24 5 hundred ce price")))
        assertEquals(OptionFacts.Asked.Quote(Market.BANKNIFTY, 52000, "PE"), OptionFacts.asked(Spoken.question("banknifty bavan hazaar pe kitne ka hai")))

        assertEquals(24500.0, LevelInfo.asked(Spoken.question("what's at twenty four thousand five hundred")))

        assertEquals(LocalTime.of(10, 30), Lookback.time(Spoken.question("what was nifty at half past ten")))
        assertEquals(LocalTime.of(15, 15), Lookback.time(Spoken.question("what was nifty at three fifteen pm")))
        assertEquals(LocalTime.of(10, 30), Lookback.time(Spoken.question("nifty saade das baje kahan tha")))
        assertEquals(LocalTime.of(16, 0), Lookback.time(Spoken.question("shaam chaar baje nifty kahan tha")))
    }

    @Test fun understandReadsTheDigits() {
        assertEquals(listOf("how far is nifty from 24500"), Understand.questions(null, "umm how far is nifty from twenty four thousand five hundred"))
        assertEquals(listOf("what was nifty at 10:30"), Understand.questions(null, "what was nifty at half past ten"))
        assertEquals(listOf("how far is nifty from 25000", "what's my p&l"), Understand.questions(null, "how far is nifty from twenty five thousand and what's my p&l"))
        assertNull(Understand.questions(null, "how far is nifty from 25000"), "already digits: as said")
    }

    @Test fun aNumberAloneAsksNothing() {
        for (s in listOf("twenty four thousand", "two", "dedh lakh", "nifty", "24 5 hundred ce", "do lakh")) assertEquals(s, Spoken.question(s), s)
    }

    // ---- Nothing that acts is widened ----

    /** Commands, orders, alarms, reminders, goals, settings, notes - with numbers and times in words. */
    private val ACTING = listOf(
        "buy two lots of nifty twenty four thousand five hundred ce", "buy do lot nifty chaubees hazaar paanch sau ce", "sell one lot banknifty fifty two thousand pe",
        "nifty twenty four thousand five hundred ce do lot kharido", "exit at twenty five thousand", "close position two", "square off one lot",
        "set an alarm on nifty above twenty five thousand", "alert me when nifty crosses twenty five thousand", "tell me when nifty hits twenty five thousand",
        "nifty pachees hazaar pe alert lagao", "nifty twenty five thousand pahunche to batana", "tell me if banknifty falls one percent",
        "notify me if nifty drops point five percent", "wake me if sensex falls two percent",
        "remind me at half past ten to check nifty", "remind me in thirty minutes to check nifty", "mujhe saade das baje nifty dekhna yaad dilana",
        "remind me every day at quarter past nine to check the gap",
        "stop strategy two", "start strategy three at ten thirty", "stop all arms at half past two", "kill switch on at quarter past three",
        "at saade teen baje stop all strategies", "start all the arms tomorrow at nine twenty",
        "my target today is five thousand", "set my daily target to five thousand", "target five thousand today", "my goal this week is twenty thousand",
        "limit my loss to two thousand a day", "keep my losses under five thousand this week",
        "set max loss to five thousand", "change max lots to three", "max lots three", "set your risk to two thousand",
        "remember nifty support is twenty four thousand", "note down that i took two lots", "journal two lots at twenty four thousand",
        "switch to live mode", "do it automatically", "cancel order two", "trail my stop loss by fifty points",
        "my stop loss is twenty four thousand", "move my sl to twenty four thousand five hundred")

    @Test fun anythingThatActsIsNeverRead() {
        for (s in ACTING) {
            assertEquals(s, Spoken.question(s), s)
            val u = Understand.questions(null, s)
            assertTrue(u == null || u.none { it != s && Spoken.digits(it) != it && it.any(Char::isDigit) && !s.any(Char::isDigit) }, "$s -> $u")
        }
    }

    @Test fun theStrictReadersSeeExactlyWhatTheyDidBefore() {
        val now = LocalDateTime.of(2026, 10, 5, 9, 0)
        for (s in ACTING) {
            val q = Spoken.question(s)
            assertEquals(Commands.parse(s), Commands.parse(q), s)
            assertEquals(Ask.parse(s).order, Ask.parse(q).order, s)
            assertEquals(Ask.parse(s).command, Ask.parse(q).command, s)
            assertEquals(Reminder.parse(s, now), Reminder.parse(q, now), s)
            assertEquals(Later.split(s, now), Later.split(q, now), s)
            assertEquals(Later.mentionsTime(s), Later.mentionsTime(q), s)
            assertEquals(Goals.read(s), Goals.read(q), s)
        }
    }

    @Test fun wordsThatWouldActOnceReadAreLeftAsHeard() {
        // Read as digits these become an alarm or a target - so they stay as heard (the alarm still needs its digits).
        val alarm = "tell me if banknifty falls one percent"
        assertNotNull(Commands.parse(d(alarm)), "the digits would be an alarm")
        assertNull(Commands.parse(alarm))
        assertEquals(alarm, Spoken.question(alarm))
        val target = "my target today is five thousand"
        assertNotNull(Commands.parse(d(target)), "the digits would set the day's target")
        assertEquals(target, Spoken.question(target))
        assertNull(Understand.questions(null, target))
    }

    @Test fun everyQuestionReadNeverActs() {
        val now = LocalDateTime.of(2026, 10, 5, 9, 0)
        val qs = listOf("how far is nifty from twenty four thousand five hundred", "what happens to my p&l if nifty moves hundred points",
            "nifty twenty four thousand five hundred ce price", "what was nifty at half past ten", "nifty saade das baje kahan tha",
            "if banknifty falls point five percent what do i lose", "what's at twenty four thousand five hundred", "shaam chaar baje nifty kahan tha",
            "what was nifty at three fifteen pm", "how is strategy two doing", "top two movers")
        for (s in qs) {
            val q = Spoken.question(s)
            assertNotEquals(s, q, s)
            assertFalse(FollowUp.acts(q), q)
            assertNull(Commands.parse(q), q)
            assertNull(Ask.parse(q).order, q)
            assertNull(Ask.parse(q).command, q)
            assertFalse(Reminder.asked(q), q)
            assertNull(Goals.read(q), q)
            assertNull(Memory.toKeep(q), q)
            // A time read is a question's time: nothing is set for it (a timed request needs a command, and there is none).
            Later.split(q, now)?.let { assertNull(Ask.parse(it.rest).command, q); assertNull(Ask.parse(it.rest).order, q) }
        }
    }

    @Test fun askReadingIsUnchanged() {
        // Spoken is not part of Ask.reading (an order's market, strike and lots are read there): only Understand uses it.
        assertFalse(Ask.reading("how far is nifty from twenty five thousand").contains("25000"))
        assertNull(Ask.parse("buy two lots of nifty twenty four thousand five hundred ce").order?.strike?.takeIf { it == 24500 })
    }
}
