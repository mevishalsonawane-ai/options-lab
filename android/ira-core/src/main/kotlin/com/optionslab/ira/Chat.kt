package com.optionslab.ira

import java.time.LocalDateTime

/**
 * Talking about anything else (the owner's wish, 2026-10-03: "how are you" got the same line every time): everyday
 * small talk answered at once and in varied words, and anything else given to the on-device model for a short friendly
 * reply - which is used only if it states no figures, gives no buy or sell advice and claims no action. Pure.
 */
object Chat {
    private data class Talk(val rx: Regex, val replies: List<String>)

    private val TALK = listOf(
        Talk(Regex("^ (how are you|how r you|how are you doing|how s it going|how is it going|how do you do|you ok|are you ok|kaise ho|kaisa hai jarvis|how have you been) $"), listOf(
            "I'm doing well, Boss, thanks for asking. Ready when you are.",
            "All good here, Boss. Watching the markets for you.",
            "Sharp and listening, Boss. How are you?",
            "Running smoothly, Boss. What can I do for you?")),
        Talk(Regex("^ (thanks|thank you|thank you jarvis|thanks a lot|thx|shukriya|dhanyavaad|great thanks|nice|good job|well done|awesome|perfect) $"), listOf(
            "Always, Boss.", "Happy to help, Boss.", "Anytime, Boss.", "My pleasure, Boss.")),
        Talk(Regex("^ (who are you|what are you|what is your name|what s your name|whats your name|your name|introduce yourself) $"), listOf(
            "I'm Jarvis, Boss: your trading assistant inside JarvisAlgo. I watch the markets, your positions and the news, and I only act when you approve.",
            "Jarvis, Boss. I read the markets and your account, suggest trades, and never act without your yes.")),
        Talk(Regex("^ (who made you|who created you|who built you) $"), listOf(
            "I was built for you, Boss, as part of JarvisAlgo, and everything I know stays on this phone.")),
        Talk(Regex("^ (good night|goodnight|bye|bye bye|see you|see you later|talk later|that s all|thats all|nothing|never mind|nevermind) $"), listOf(
            "Good night, Boss. I'll keep an eye on things.", "See you, Boss.", "Alright, Boss. I'm here when you need me.", "Talk soon, Boss.")),
        Talk(Regex("^ (tell me a joke|say something funny|make me laugh|joke) $"), listOf(
            "Why did the trader bring a ladder? Because the market was going up, Boss.",
            "I asked the market for a straight answer. It gave me a candle with two long wicks.",
            "My favourite exercise, Boss? Jumping to conclusions. Luckily my stop loss doesn't.")),
        Talk(Regex("^ (are you there|you there|hello there|can you hear me|are you listening) $"), listOf(
            "Right here, Boss.", "I'm listening, Boss.", "Yes, Boss. Go ahead.")),
        Talk(Regex("^ (i love you|you re the best|you are the best|you are awesome|you re awesome|good boy) $"), listOf(
            "That means a lot, Boss. Let's make it a good trading day.", "Thank you, Boss. The feeling is mutual.")),
        Talk(Regex("^ (i m bored|im bored|i am bored|i m tired|im tired|i am tired) $"), listOf(
            "Take a short break, Boss. I'll watch the screens and tell you if anything moves.",
            "Rest a bit, Boss. Tired eyes make costly trades. I'll keep watch.")),
    )

    private fun norm(text: String): String {
        var t = " " + text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        repeat(2) { t = t.replace(Regex(" (jarvis|hey|hi|hello|ok|okay|boss|please) "), " ").replace(Regex("\\s+"), " ").let { " ${it.trim()} " } }
        return t
    }

    /** Everyday small talk answered at once; [turn] varies the words so a reply is not repeated twice running. */
    fun smallTalk(text: String, turn: Int): String? {
        val t = norm(text)
        val k = TALK.firstOrNull { it.rx.containsMatchIn(t) } ?: return null
        return k.replies[Math.floorMod(turn, k.replies.size)]
    }

    /** For the on-device model: a short, friendly reply, no figures, no advice, no claims of action. */
    fun prompt(question: String, now: LocalDateTime): String =
        "You are Jarvis, a friendly assistant inside a trading app, talking to its owner, whom you call Boss. " +
            "Reply in one or two short spoken sentences. Do not state any numbers, prices, dates or market facts. " +
            "Do not give buy or sell advice. Do not say you did, set, placed or changed anything. " +
            "If it is about the markets or the account, say you can answer that if Boss asks you directly about it. " +
            "It is ${now.dayOfWeek.name.lowercase()} ${if (now.hour < 12) "morning" else if (now.hour < 17) "afternoon" else "evening"}.\n\n" +
            "Boss: ${question.trim().take(300)}\nJarvis:"

    private val ADVICE = Regex("(?i)\\b(buy|sell|short|go long|invest in|enter|exit|book profit|stop ?loss at|target)\\b")
    private val ACTED = Regex("(?i)\\b(i( have|['’]ve| just| already)? (placed|set|changed|turned|started|stopped|sold|bought|closed|cancelled|switched|updated|enabled|disabled))\\b")

    /** The model's reply when it is safe to say (no figures, no advice, no claims of action, short), else null. */
    fun accept(reply: String?): String? {
        val r = reply?.trim()?.removePrefix("Jarvis:")?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (r.length < 2 || r.length > 280) return null
        if (Regex("\\d").containsMatchIn(r)) return null
        if (ADVICE.containsMatchIn(r) || ACTED.containsMatchIn(r)) return null
        // At most two sentences.
        val parts = Regex("(?<=[.!?])\\s+").split(r).filter { it.isNotBlank() }
        return parts.take(2).joinToString(" ")
    }

    /** When nothing else answers: varied words, never the same line twice running. */
    private val FALLBACK = listOf(
        "I'm not sure about that one, Boss. Ask me about Nifty, BankNifty, your positions or your P&L.",
        "That's outside what I know, Boss. I'm best with the markets and your account.",
        "I didn't quite get that, Boss. Try asking about the market, your trades or a strategy.",
        "Hmm, I can't help with that, Boss. Want the market status or your P&L instead?")

    fun fallback(turn: Int): String = FALLBACK[Math.floorMod(turn, FALLBACK.size)]
}
