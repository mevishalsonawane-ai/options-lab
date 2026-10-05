package com.optionslab.ira

import java.time.LocalDateTime

/**
 * Boss's first question of the day paid for every reader's patterns (2026-10-05): about 0.4 s on a desktop before a
 * word was understood, more on a phone. [up] reads a few made-up questions through the same readers once, off the main
 * thread, when Jarvis starts. Nothing is answered, said, kept or done: only the readers are made ready. Pure.
 */
object Warm {
    /** Made-up words of each kind (a question, a follow-up, a filler, Hinglish, the account, a command as words only). */
    private val SAID = listOf("how is nifty doing today", "umm and bank nifty?", "what are my positions", "nifty ka kya haal hai",
        "should I trade now?", "what is a hammer", "stop strategy 1")

    @Volatile private var done = false

    /** Every reader made ready once; later calls return at once. Safe on any thread. */
    fun up() {
        if (done) return
        val now = LocalDateTime.of(2026, 1, 5, 10, 0)
        var prev: String? = null
        for (q in SAID) {
            runCatching { Secrets.redact(q) }
            runCatching { Corrections.apply(q, emptyList()) }
            runCatching { Sources.asked(q) }
            runCatching { Understand.questions(prev, q) }
            runCatching { Commands.parse(q) }
            runCatching { Agenda.asked(q) }
            runCatching { Plan.steps(q) { false } }
            runCatching { Later.mentionsTime(q); Later.split(q, now) }
            runCatching { Reminder.asked(q); Reminder.cancelAsked(q) }
            runCatching { AboutBoss.fact(q) }
            runCatching { Intents.quick(q) }
            runCatching { Chat.personal(q) }
            runCatching { Glossary.explain(q) }
            runCatching { MarketDays.expiryAsked(q) }
            runCatching { Suggest.line(q) }
            runCatching { Ira().answer(q, emptyMap(), emptyList()) }
            prev = q
        }
        done = true
    }
}
