package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Speed, round 7: the chat bubbles' remembered short lines ([ShortAnswer.cached]). */
class ShortAnswerCacheTest {
    private val answers = listOf(
        null to "Nifty is at 24,612.40 (+0.42% on the day), in a range of 24,500 to 24,700. BankNifty is at 51,230.10 (-0.10% on the day). The market is closed.",
        "what's the P&L today" to AppFacts.pnl("Paper", 2575.4, 1000.0, 1575.4, 180.0) + " Paper: 2 open positions. Zerodha did not answer just now, so its orders are not included.",
        "how is the market" to "Boss, Nifty is at 24,612.40 (+0.42% on the day), in a range. Sensex last closed at 80,100.55 (-0.2% on the day). As of 14:05.",
        "positions" to "Zerodha: 2 open positions. Paper: 1 open position. Not protected: Insufficient funds.",
        "tell me about the day" to "It was a quiet session with narrow ranges across the indices and little news of note, though the afternoon saw some selling in banks, which pulled BankNifty down by about 0.3 percent before a late recovery.",
        "more" to "Everything in full. Second sentence here.",
        "funds" to "Paper funds: Rs 1,00,000.50 available, Rs 0 used. Live funds: Rs 2,345.10 available.",
    )

    @Test fun sameAsReadingEveryTime() {
        ShortAnswer.clearCache()
        for ((q, a) in answers) for (short in listOf(true, false)) {
            val first = ShortAnswer.cached(q, a, short)
            assertEquals(ShortAnswer.of(q, a, short), first)
            // A second bubble pass: the remembered reading itself.
            assertSame(first, ShortAnswer.cached(q, a, short))
        }
    }

    @Test fun keyedByQuestionAnswerAndChoice() {
        ShortAnswer.clearCache()
        val a = answers[1].second
        val short = ShortAnswer.cached("what's the P&L today", a)
        val whole = ShortAnswer.cached("what's the P&L today", a, short = false)
        val other = ShortAnswer.cached("more", a)
        assertTrue(short.details != null)
        assertEquals(a, whole.line)
        assertEquals(a, other.line)
    }

    @Test fun boundedAndCleared() {
        ShortAnswer.clearCache()
        repeat(ShortAnswer.MEMO_SIZE + 50) { i -> ShortAnswer.cached(null, "Answer $i. It has two sentences, the second with a figure $i.") }
        assertEquals(ShortAnswer.MEMO_SIZE, ShortAnswer.cachedCount())
        ShortAnswer.clearCache()
        assertEquals(0, ShortAnswer.cachedCount())
    }

    @Test fun aRememberedReadingIsFarCheaper() {
        ShortAnswer.clearCache()
        val rounds = 300
        repeat(50) { for ((q, a) in answers) ShortAnswer.of(q, a) }   // warm the JIT
        var t = System.nanoTime()
        repeat(rounds) { for ((q, a) in answers) ShortAnswer.of(q, a) }
        val fresh = System.nanoTime() - t
        for ((q, a) in answers) ShortAnswer.cached(q, a)
        t = System.nanoTime()
        repeat(rounds) { for ((q, a) in answers) ShortAnswer.cached(q, a) }
        val hit = System.nanoTime() - t
        println("ShortAnswer per bubble: read ${fresh / (rounds * answers.size)} ns, remembered ${hit / (rounds * answers.size)} ns")
        assertTrue(hit < fresh, "remembered $hit ns vs read $fresh ns")
    }
}
