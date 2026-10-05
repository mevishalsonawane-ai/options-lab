package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Voice, round 22: the spoken answer worked out in one pass ([SpokenReply]) says exactly the words the voice said before
 * (each of its ways reading the question by itself, and the words shaped twice), and the words it marks as shaped are a
 * fixed point of the speech engine's shaping, so skipping that second pass changes nothing.
 */
class SpokenReplyTest {
    private val at = LocalDateTime.of(2026, 10, 5, 10, 0)

    private val questions = listOf(
        "what is nifty doing", "how is bank nifty today", "nifty ka level kya hai", "what are the support and resistance levels for nifty",
        "any news on the market", "what's the trend", "tell me more", "how is vix", "why did bank nifty fall today",
        "what is the expected range today", "what patterns do you see on nifty", "is it a good time to buy", "stop all strategies",
        "buy 2 lots nifty 24500 ce", "hello jarvis", "what's my p and l today", "bank nifty levels batao", "")

    private val answers = VoiceBenchTest.LINES + listOf(
        "Nifty is at 24,612.40, up 0.4% today. Bank Nifty is flat. Boss, the trend is up - support 24,500 (strong) · resistance 24,800. Volume was light. IT lagged.",
        "The trend is up. It has been so since 10:15. Support is at 24,500 and resistance at 24,800. Bank Nifty is down 0.2%.",
        "Prices are 12 minutes old. Nifty is at 24,612.40. Support 24,500.",
        "Boss, the market is closed today. Prices are from Friday: Nifty 24,612.40 (+0.21%).")

    private val kinds = listOf("topic:LEVELS", "topic:TREND", "topic:NEWS", "topic:WHY", "topic:VOLATILITY", "topic:PATTERNS", "topic:OVERVIEW")

    /** The voice's words as worked out before this round (JarvisVoice.answer and say, the learnings given). */
    private fun before(q: String, text: String, more: Boolean, wishedLong: Boolean, brief: Boolean,
                       leading: List<FigureFirst.Record>, learned: List<TopicLength.Record>, shorter: List<Clarity.Record>,
                       echo: String?, late: Boolean): Pair<String, String> {
        val ordered = runCatching { FigureFirst.lead(q, text, leading) }.getOrDefault(text)
        val spoken = Aloud.say(ordered, when {
            more -> Aloud.Length.FULL.sentences
            wishedLong -> Aloud.Length.FULL.sentences
            brief -> Aloud.Length.SHORT.sentences
            else -> runCatching { TopicLength.sentences(q, learned) }.getOrNull() ?: runCatching { Clarity.sentences(q, shorter) }.getOrNull()
                ?: Aloud.Length.USUAL.sentences })
        val (words, full) = if (late) "About what you asked earlier: $spoken" to ordered else HeardBack.lead(echo, spoken) to HeardBack.full(echo, ordered)
        // say(): figures and pauses once more, on the way to the speech engine.
        return Pauses.shape(SayAs.figures(words, Aloud.hindi(words))) to full
    }

    /** The voice's words now: [SpokenReply.said], and the second shaping only when not [SpokenReply.Said.shaped]. */
    private fun now(q: String, text: String, more: Boolean, wishedLong: Boolean, brief: Boolean,
                    leading: List<FigureFirst.Record>, learned: List<TopicLength.Record>, shorter: List<Clarity.Record>,
                    echo: String?, late: Boolean): Pair<String, String> {
        val r = SpokenReply.said(q, text, more, wishedLong, brief, { leading }, { learned }, { shorter }, echo, late)
        val words = if (r.shaped) r.spoken else Pauses.shape(SayAs.figures(r.spoken, Aloud.hindi(r.spoken)))
        return words to r.full
    }

    @Test fun sameWordsAsBefore() {
        val rnd = Random(22)
        var compared = 0
        for (q in questions) for ((i, text) in answers.withIndex()) {
            // A spread of the voice's learnings and settings, the same for both.
            val k = kinds[(i + q.length) % kinds.size]
            val leading = if (rnd.nextBoolean()) listOf(FigureFirst.Record(k, 3, 4, at)) else emptyList()
            val learned = when (rnd.nextInt(3)) {
                0 -> emptyList()
                1 -> listOf(TopicLength.Record(k, TopicLength.Dir.SHORT, 3, 0, at))
                else -> listOf(TopicLength.Record(kinds[rnd.nextInt(kinds.size)], TopicLength.Dir.LONG, 3, 1, at))
            }
            val shorter = if (rnd.nextInt(3) == 0) listOf(Clarity.Record(k, 5, 6)) else emptyList()
            val more = rnd.nextInt(8) == 0; val wished = rnd.nextInt(8) == 0; val brief = rnd.nextInt(5) == 0
            val echo = if (rnd.nextInt(4) == 0) HeardBack.line(q.ifEmpty { "nifty 24500 ce" }) else null
            val late = rnd.nextInt(6) == 0
            assertEquals(before(q, text, more, wished, brief, leading, learned, shorter, echo, late),
                now(q, text, more, wished, brief, leading, learned, shorter, echo, late), "$q | $text")
            compared++
        }
        assertTrue(compared > 1000)
    }

    @Test fun figureFirstAndLengthsStillApply() {
        val q = "what are the support and resistance levels for nifty"
        val kind = Clarity.kind(q)!!
        val text = "The trend is up today. Support is at 24,500 and resistance at 24,800. Volume was light. IT lagged."
        val none = SpokenReply.said(q, text, false, false, false, { emptyList() }, { emptyList() }, { emptyList() })
        assertTrue(none.shaped)
        assertTrue(none.spoken.startsWith("Boss, the trend is up today."), none.spoken)
        val lead = SpokenReply.said(q, text, false, false, false, { listOf(FigureFirst.Record(kind, 3, 3, at)) }, { emptyList() }, { emptyList() })
        assertTrue(lead.spoken.startsWith("Boss, support is at 24,500"), lead.spoken)
        assertTrue(lead.full.startsWith("Support is at 24,500"))
        val short = SpokenReply.said(q, text, false, false, false, { emptyList() }, { listOf(TopicLength.Record(kind, TopicLength.Dir.SHORT, 2, 0, at)) }, { emptyList() })
        assertEquals("Boss, the trend is up today. The rest is in the chat.", short.spoken)
        // Boss's own "short answers" comes before a topic learned whole; "tell me more" before both.
        val whole = { listOf(TopicLength.Record(kind, TopicLength.Dir.LONG, 2, 0, at)) }
        assertEquals(short.spoken, SpokenReply.said(q, text, false, false, true, { emptyList() }, whole, { emptyList() }).spoken)
        assertFalse(SpokenReply.said(q, text, true, false, true, { emptyList() }, whole, { emptyList() }).spoken.contains("The rest is in the chat"))
        // A read-back or a late answer is shaped on the way to the voice, as before.
        assertFalse(SpokenReply.said(q, text, false, false, false, { emptyList() }, { emptyList() }, { emptyList() }, echo = HeardBack.line(q)).shaped)
        assertFalse(SpokenReply.said(q, text, false, false, false, { emptyList() }, { emptyList() }, { emptyList() }, late = true).shaped)
    }

    @Test fun learningsReadOnlyWhenNeeded() {
        var read = 0
        val q = "what is nifty doing"
        SpokenReply.said(q, "Nifty is up.", more = true, wishedLong = false, brief = false, { emptyList() }, { read++; emptyList() }, { read++; emptyList() })
        SpokenReply.said(q, "Nifty is up.", more = false, wishedLong = false, brief = true, { emptyList() }, { read++; emptyList() }, { read++; emptyList() })
        assertEquals(0, read)
    }

    /**
     * [Aloud.say]'s words are a fixed point of the speech engine's shaping: a second [SayAs.figures] then [Pauses.shape]
     * changes nothing - over every line the voice bench knows, and many made of figures, symbols and punctuation.
     */
    @Test fun aloudIsAlreadyShaped() {
        val rnd = Random(5)
        val bits = listOf("Nifty", "Bank Nifty", "Boss", "Boss,", "is at", "up", "down", "24,612.40", "1,23,456", "12,34,56,789", "123456",
            "Rs", "Rs 1,23,456", "₹2,50,00,000", "crore", "rupees", "lakh", "0.4%", "+0.21%", "-45", "- 5", "15+5", "9:15", "15:35:42",
            "(strong)", "(70 percent)", "(word tone +0.3)", "·", "|", "•", "-", "—", "\n- ", "NIFTY25O0724500CE", "BANKNIFTY26OCT52000PE",
            "NIFTY26OCTFUT", "24500 CE", "24,600PE", "PE ratio", "pts", "120pts", "points", "24,500 24,600", "percent", "Sensex",
            "निफ्टी", "बॉस", "रुपये", "।", ".", "!", "?", ",", "and", "the", "It", "today", "Monday", "7 October", "2 lots", "1.2345", "0.50")
        val made = List(12_000) {
            buildString { repeat(3 + rnd.nextInt(14)) { if (isNotEmpty() && rnd.nextInt(4) != 0) append(' '); append(bits[rnd.nextInt(bits.size)]) } }
        }
        val lines = VoiceBenchTest.LINES + answers + made
        val bad = ArrayList<String>()
        var settled = 0; var changed = 0
        for (t in lines) for (n in listOf(1, 2, 3, 8)) {
            val s = Aloud.say(t, n)
            val again = Pauses.shape(SayAs.figures(s, Aloud.hindi(s)))
            if (again != s) changed++
            if (!SpokenReply.settled(s)) continue
            settled++
            if (again != s) bad += "[${t.replace("\n", "\\n")}] in $n: <$s> -> <$again>"
        }
        bad.take(40).forEach { println("NOTFIXED $it") }
        // Every line a second pass would change is caught (on these made-up lines, many are: they are made to be awkward).
        assertTrue(bad.isEmpty(), "${bad.size} settled lines changed")
        assertTrue(changed > 0 && settled > lines.size)
        // The answers Jarvis really gives are settled: the second pass is skipped for them.
        val real = (VoiceBenchTest.LINES + answers).filter { it.isNotBlank() && !it.contains('\n') && !it.contains('·') && !it.contains('|') && !it.contains('•') }
        val share = real.count { SpokenReply.settled(Aloud.say(it, 3)) }.toDouble() / real.size
        println("SpokenReply: settled ${"%.0f".format(share * 100)} percent of the plain answers")
        assertTrue(share > 0.8, "settled share $share")
    }

    @Test fun warmSaysNothingAndKeepsNothing() {
        SpokenReply.warm()
        SpokenReply.warm()
    }

    /** What the voice's words cost per answer, before (each way reading the question, shaped twice) and now. A measure, not a gate. */
    @Test fun spokenCost() {
        val long = System.getProperty("ira.bench") != null
        val q = "what are the support and resistance levels for nifty"
        val kind = Clarity.kind(q)!!
        val leading = listOf(FigureFirst.Record(kind, 3, 3, at)); val learned = listOf(TopicLength.Record("topic:NEWS", TopicLength.Dir.SHORT, 3, 0, at))
        val shorter = listOf(Clarity.Record("topic:TREND", 5, 6))
        val text = answers[answers.size - 4]
        fun run(f: () -> Unit): Double { repeat(if (long) 2000 else 200) { f() }; val n = if (long) 5000 else 500
            val s = System.nanoTime(); repeat(n) { f() }; return (System.nanoTime() - s) / 1000.0 / n }
        val old = run { before(q, text, false, false, false, leading, learned, shorter, null, false) }
        val new = run { now(q, text, false, false, false, leading, learned, shorter, null, false) }
        println("SpokenReply: per answer before %.1f us, now %.1f us".format(old, new))
    }
}

class ReplyClockSplitTest {
    @Test fun voicePartOfTheWait() {
        val c = ReplyClock()
        c.heard(1_000)
        c.queued("answer#1", 1_900)
        assertNull(c.startedSplit("say#2", 2_000))
        assertEquals(ReplyClock.Split(1_400, 500), c.startedSplit("answer#1.0", 2_400))
        assertNull(c.started("answer#1", 2_500))
        // started() is the total, as before.
        c.heard(10_000); c.queued("answer#3", 10_200)
        assertEquals(700L, c.started("answer#3", 10_700))
    }

    @Test fun diagnosticsLine() {
        val waits = listOf(1_200L, 3_400L, 800L)
        assertEquals(Latency.say(waits), Latency.say(waits, emptyList()))
        assertEquals("Spoken answers: 3, typical wait 1.2 s, slowest 3.4 s, last 0.8 s. Typically 0.8 s to the answer and 0.4 s for " +
            "the voice to start; first word within 1.5 s: 2 of 3.", Latency.say(waits, listOf(400L, 600L, 300L)))
        // Only the newest waits have the voice's part (this run's earlier ones did not): those are split.
        assertEquals("Spoken answers: 3, typical wait 1.2 s, slowest 3.4 s, last 0.8 s. Typically 0.5 s to the answer and 0.3 s for " +
            "the voice to start; first word within 1.5 s: 1 of 1.", Latency.say(waits, listOf(300L)))
        assertEquals(Latency.say(waits), Latency.say(waits, listOf(1L, 2L, 3L, 4L)))
        var v = emptyList<Long>(); var w = emptyList<Long>()
        for ((t, ms) in listOf(1_000L to 300L, 0L to 0L, 2_000L to 2_500L)) { w = Latency.add(w, t); v = Latency.addVoice(v, w, t, ms) }
        assertEquals(listOf(1_000L, 2_000L), w)
        assertEquals(listOf(300L, 2_000L), v)
    }
}
