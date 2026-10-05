package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boss, 5 Oct: "Jarvis stop", or just "Jarvis", said while Jarvis speaks did not stop him. A stop is read from the
 * newest words (a misread word of his own before it no longer hides it), from any of the readings, partial or final;
 * his own voice heard back never counts; and a stop over him only ever stops the voice - never a strategy.
 */
class StopOverHimTest {
    private val saying = "Boss, Nifty is at 24,612. It rose 0.21 percent today. Banks led. The rest is in the chat."

    @Test fun aStopAfterHisMisreadWordsStillCounts() {
        // "24612" (he said "24, 612") and "rows" (he said "rose") are misreadings of his own words, then Boss's stop.
        assertEquals(BargeIn.Cut.HUSH, BargeIn.cut("nifty is at 24612 stop", saying))
        assertEquals(BargeIn.Cut.HUSH, BargeIn.cut("it rows 0.21 percent bas", saying))
        assertEquals(BargeIn.Cut.HUSH, BargeIn.cut("banks lead ruko", saying))
        assertEquals(BargeIn.Cut.HUSH, BargeIn.cut("in the chat stop stop", saying))
        assertEquals(BargeIn.Cut.HUSH, BargeIn.cut("nifty is at 24612 please stop", saying))
    }

    @Test fun aStopWithHisTailReadAfterItCounts() {
        // Boss's "stop", then the speaker's next word or two read after it.
        assertEquals(BargeIn.Cut.HUSH, BargeIn.cut("stop the rest", saying))
        assertEquals(BargeIn.Cut.HUSH, BargeIn.cut("nifty 2461 wait banks", saying))
        // Boss's own longer words after it: his sentence, left to the turn's end.
        assertNull(BargeIn.cut("stop loss kahan lagaun", saying))
    }

    @Test fun theNameAloneOrWithAStopWordIsAlwaysACut() {
        for (w in listOf("jarvis", "Jarvis stop", "jarvis bas", "jarvis ruko", "jarvis chup", "jarvis enough", "jarvis wait", "jarvis quiet",
                "nifty is at 24612 jarvis", "the rest is jarvis stop"))
            assertEquals(BargeIn.Cut.NAME, BargeIn.cut(w, saying), w)
        // Even when every other word is his own: the name is never his (he never says it).
        assertEquals(BargeIn.Cut.NAME, BargeIn.cut("banks led jarvis", saying))
    }

    @Test fun anyReadingCounts() {
        assertEquals(BargeIn.Cut.NAME, BargeIn.cutAny(listOf("nifty is at", "nifty is at jarvis"), saying))
        assertEquals(BargeIn.Cut.HUSH, BargeIn.cutAny(listOf("banks led", "banks led stop"), saying))
        assertNull(BargeIn.cutAny(listOf("banks led", "the rest is in the chat"), saying))
        assertNull(BargeIn.cutAny(emptyList(), saying))
    }

    @Test fun hisOwnStopWordHeardBackIsNotBoss() {
        val sl = "Boss, I set a stop loss at 120. Wait for the close."
        // His own "stop" and "wait", heard with their own neighbours.
        assertNull(BargeIn.cut("set a stop loss", sl))
        assertNull(BargeIn.cut("a stop", sl))
        assertNull(BargeIn.cut("stop loss at", sl))
        assertNull(BargeIn.cut("wait for the", sl))
        assertNull(BargeIn.cut("stop", sl))                     // alone: nothing to tell it from his own
        // His word with other neighbours is Boss.
        assertEquals(BargeIn.Cut.HUSH, BargeIn.cut("stop stop", sl))
        assertEquals(BargeIn.Cut.HUSH, BargeIn.cut("at 120 please stop", sl))
        // Not a word of his: Boss.
        assertEquals(BargeIn.Cut.HUSH, BargeIn.cut("bas", sl))
    }

    @Test fun theNameAndStopWordsAreNeverHisEcho() {
        assertFalse(EmptyTurns.echo("banks led jarvis", saying))
        assertFalse(EmptyTurns.echo("banks led stop", saying))
        assertFalse(EmptyTurns.echo("banks led bas", saying))
        assertTrue(EmptyTurns.echo("banks led the rest is in the chat", saying))
    }

    @Test fun aStopOverHimNeverStopsAStrategy() {
        val stops = listOf("stop", "bas", "ruko", "chup", "enough", "wait", "quiet", "stop stop", "bas karo", "ruk jao", "stop please", "ok stop")
        for (w in stops) {
            // Said over him: only the voice stops.
            val c = BargeIn.cut(w, saying)
            assertTrue(c == BargeIn.Cut.HUSH || c == BargeIn.Cut.NAME, w)
            // With the name: "be quiet", never a question or a command.
            assertEquals(Wake.Heard.Hush, Wake.heard("jarvis $w", false), "jarvis $w")
            assertEquals(Wake.Heard.Hush, Wake.heard("jarvis $w", true), "jarvis $w (awake)")
            // And never read as stopping a strategy or every strategy.
            for (said in listOf(w, "jarvis $w")) {
                val k = Commands.parse(said)?.kind
                assertTrue(k != Command.Kind.STOP_ONE && k != Command.Kind.STOP_ALL, "$said -> $k")
            }
        }
        // "Jarvis" alone over him wakes him ("Yes, Boss?"), nothing else.
        assertEquals(Wake.Heard.Awake, Wake.heard("jarvis", false))
    }

    @Test fun goOnStillWorksAfterAStop() {
        val c = BargeIn.cutOff(saying, saying, saying.indexOf("rose"), account = false, now = 0L)
        assertTrue(BargeIn.goOn("go on"))
        assertTrue(BargeIn.goOn("jarvis go on"))
        assertEquals(BargeIn.Rest.Say("It rose 0.21 percent today. Banks led. The rest is in the chat."), BargeIn.rest(c, 1_000L, locked = false))
        // "go on" is never a stop.
        assertNull(BargeIn.cut("go on", saying))
    }

    @Test fun stopsAreMeasuredInDurationsOnly() {
        var t = CutStops.Tally()
        assertEquals("stops heard 0, stop-to-silence median not measured yet", CutStops.say(t))
        t = CutStops.heard(t); t = CutStops.silent(t, 120)
        t = CutStops.heard(t); t = CutStops.silent(t, 300)
        t = CutStops.heard(t); t = CutStops.silent(t, 80)
        assertEquals("stops heard 3, stop-to-silence median 120 ms", CutStops.say(t))
        t = CutStops.silent(t, 200)
        assertEquals(160L, CutStops.median(t))
        // Nonsense times are not kept.
        assertEquals(t, CutStops.silent(t, -5))
        assertEquals(t, CutStops.silent(t, CutStops.MAX_MS + 1))
        // Only the last few kept.
        var many = CutStops.Tally()
        repeat(CutStops.KEEP + 5) { many = CutStops.silent(many, it.toLong()) }
        assertEquals(CutStops.KEEP, many.ms.size)
        assertEquals(5L, many.ms.first())
    }

    @Test fun aTurnOverHisVoiceHearsThroughTheEchoCanceller() {
        assertTrue(CutIn.ownCapture(inSpeech = true, sdk = 36, echo = true, enrolled = false, broken = false))
        assertFalse(CutIn.ownCapture(inSpeech = false, sdk = 36, echo = true, enrolled = false, broken = false))
        assertFalse(CutIn.ownCapture(inSpeech = true, sdk = 36, echo = false, enrolled = false, broken = false))
        assertFalse(CutIn.ownCapture(inSpeech = true, sdk = 32, echo = true, enrolled = false, broken = false))
        assertFalse(CutIn.ownCapture(inSpeech = true, sdk = 36, echo = true, enrolled = true, broken = true))
        assertTrue(CutIn.ownCapture(inSpeech = false, sdk = 33, echo = false, enrolled = true, broken = false))
    }
}
