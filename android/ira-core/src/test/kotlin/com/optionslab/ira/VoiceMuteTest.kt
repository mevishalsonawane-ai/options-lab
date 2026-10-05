package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceMuteTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int, day: Int = 5) = LocalDateTime.of(2026, 10, day, h, m).atZone(ist).toInstant().toEpochMilli()

    @Test fun aBareHeardMuteIsNotEnough() {
        assertFalse(VoiceMute.heardClear("mute", named = false))
        assertFalse(VoiceMute.heardClear("muted", named = false))
        assertFalse(VoiceMute.heardClear("mute it", named = false))
        assertFalse(VoiceMute.heardClear("mute it", named = true))
        assertFalse(VoiceMute.heardClear("", named = true))
    }

    @Test fun theNameOrAClearPhraseMutes() {
        assertTrue(VoiceMute.heardClear("mute", named = true))
        assertTrue(VoiceMute.heardClear("mute please", named = true))
        assertTrue(VoiceMute.heardClear("mute yourself", named = false))
        assertTrue(VoiceMute.heardClear("please mute your voice", named = false))
        assertTrue(VoiceMute.heardClear("voice off", named = false))
        assertTrue(VoiceMute.heardClear("don't speak", named = false))
        assertTrue(VoiceMute.heardClear("chup ho jao", named = false))
        assertTrue(VoiceMute.heardClear("be quiet", named = false))
    }

    @Test fun aVoiceMuteLastsTheDay() {
        val m = VoiceMute.Mark(VoiceMute.By.VOICE, at(15, 1), "mute")
        assertTrue(VoiceMute.holds(m, LocalDate.of(2026, 10, 5), ist))
        assertFalse(VoiceMute.holds(m, LocalDate.of(2026, 10, 6), ist))
        val typed = VoiceMute.Mark(VoiceMute.By.TYPED, at(15, 1))
        assertTrue(VoiceMute.holds(typed, LocalDate.of(2026, 10, 9), ist))
        assertTrue(VoiceMute.holds(VoiceMute.Mark(VoiceMute.By.SETTINGS, at(9, 0)), LocalDate.of(2026, 10, 9), ist))
        assertTrue(VoiceMute.holds(null, LocalDate.of(2026, 10, 9), ist))
        assertEquals("I was muted by voice yesterday at 15:01; I'm speaking again today. Say \"Jarvis, mute yourself\" to mute me.",
            VoiceMute.morningLine(m, LocalDate.of(2026, 10, 6), ist))
        assertNull(VoiceMute.morningLine(m, LocalDate.of(2026, 10, 5), ist))
        assertNull(VoiceMute.morningLine(typed, LocalDate.of(2026, 10, 6), ist))
    }

    @Test fun diagnosticsLineAndEncoding() {
        val m = VoiceMute.Mark(VoiceMute.By.VOICE, at(15, 1), "mute")
        assertEquals("Muted by: voice at 15:01 (heard as 'mute')", VoiceMute.diagLine(true, m, ist))
        assertEquals("Muted by: typing at 15:01", VoiceMute.diagLine(true, m.copy(by = VoiceMute.By.TYPED), ist))
        assertEquals("Muted by: not muted", VoiceMute.diagLine(false, m, ist))
        assertEquals(m, VoiceMute.decode(VoiceMute.encode(m)))
        assertEquals(VoiceMute.Mark(VoiceMute.By.TYPED, 5L), VoiceMute.decode(VoiceMute.encode(VoiceMute.Mark(VoiceMute.By.TYPED, 5L))))
        assertNull(VoiceMute.decode("junk"))
        assertTrue(VoiceMute.why(m, ist).contains("15:01"))
    }

    @Test fun onlyTheMuteWordsAreKept() {
        assertEquals("mute yourself", VoiceMute.heardAs("Mute yourself!"))
        assertEquals("a mute phrase", VoiceMute.heardAs("my account number is 1234 and mute"))
    }

    @Test fun speakChoice() {
        assertEquals(SpeakChoice.Choice.IMPORTANT, SpeakChoice.Choice.of(null))
        assertEquals(SpeakChoice.Choice.ANSWERS, SpeakChoice.Choice.of("answers"))
        assertTrue(SpeakChoice.speaks(SpeakChoice.Choice.ANSWERS, SpeakChoice.Weight.ANSWER))
        assertTrue(SpeakChoice.speaks(SpeakChoice.Choice.ANSWERS, SpeakChoice.Weight.WARNING))
        assertFalse(SpeakChoice.speaks(SpeakChoice.Choice.ANSWERS, SpeakChoice.Weight.IMPORTANT))
        assertTrue(SpeakChoice.speaks(SpeakChoice.Choice.IMPORTANT, SpeakChoice.Weight.IMPORTANT))
        assertFalse(SpeakChoice.speaks(SpeakChoice.Choice.IMPORTANT, SpeakChoice.Weight.MINOR))
        assertTrue(SpeakChoice.speaks(SpeakChoice.Choice.EVERYTHING, SpeakChoice.Weight.MINOR))
        assertTrue(SpeakChoice.why(SpeakChoice.Choice.IMPORTANT, SpeakChoice.Weight.MINOR)!!.contains("minor"))
        assertNull(SpeakChoice.why(SpeakChoice.Choice.EVERYTHING, SpeakChoice.Weight.MINOR))
    }

    @Test fun whyNotSpeakingIsTheVoiceCheck() {
        for (s in listOf("why aren't you speaking?", "Jarvis, why aren't you speaking", "why only in chat", "why are you sending everything in chat?",
                "why don't you speak", "why are you silent")) assertEquals(Command.Kind.VOICE_CHECK, Commands.parse(s)?.kind, s)
        assertEquals(Command.Kind.MORE, Commands.parse("aur batao")?.kind)
        assertEquals(Command.Kind.MUTE, Commands.parse("mute")?.kind)        // typed: as Boss set it
    }

    @Test fun goOnAfterAShortAnswerSaysTheRest() {
        val c = BargeIn.finished("Nifty is at 22,555. The trend is up. Support is 22,400.", "Nifty is at 22,555.", false, 10L)!!
        assertEquals(BargeIn.Rest.Say("The trend is up. Support is 22,400."), BargeIn.rest(c, 20L, false))
        val t = BargeIn.finished("A. B. C. D.", "A. B. The rest is in the chat.", false, 0L)!!
        assertEquals(2, t.from)
        assertNull(BargeIn.finished("A. B.", "A. B.", false, 0L))
    }

    @Test fun spokenReplyIsShortByDefaultChoice() {
        val a = "Nifty is at 22,555.30 (+0.62% on the day) as of 14:05, in a range of 22,401 to 22,580 today. The 15-minute trend is up. Support is 22,400."
        val r = SpokenReply.said("where is nifty", a, more = false, wishedLong = false, brief = false, { emptyList() }, { emptyList() }, { emptyList() }, short = true)
        assertTrue(r.spoken.contains("Nifty is at 22,555"), r.spoken)
        assertFalse(r.spoken.contains("trend"), r.spoken)
        assertTrue(r.full.endsWith("Support is 22,400."), r.full)
        val d = SpokenReply.said("where is nifty", a, more = false, wishedLong = false, brief = false, { emptyList() }, { emptyList() }, { emptyList() })
        assertTrue(d.spoken.contains("trend"), d.spoken)
    }
}
