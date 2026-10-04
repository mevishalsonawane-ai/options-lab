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
        Talk(Regex("^ (how are you|how r you|how are you doing|how are you doing today|how are you today|how s it going|how is it going|how do you do|you ok|are you ok|kaise ho|kaisa hai jarvis|how have you been) $"), listOf(
            "I'm doing well, Boss, thanks for asking. Ready when you are.",
            "All good here, Boss. Watching the markets for you.",
            "Sharp and listening, Boss. How are you?",
            "Running smoothly, Boss. What can I do for you?")),
        Talk(Regex("^ ((how|what) (was|is|s) your day( going| been| today)?|how s your day( going)?|how is your day( going)?|how was your day today|" +
            "how did your day go|had a good day|did you have a good day|how was today for you) $"), listOf(
            "Busy, Boss: charts, headlines and the option chains all day. Better now that you're here. How was yours?",
            "A good one, Boss. I've been watching the markets and studying the patterns. How was your day?",
            "Quiet but useful, Boss: I read the news and kept an eye on your positions. And yours?",
            "Not bad at all, Boss. Plenty of candles to study. Tell me about yours.")),
        Talk(Regex("^ (what s up|whats up|sup|wassup|what are you doing|what are you up to|what you doing|kya kar rahe ho) $"), listOf(
            "Just watching the markets for you, Boss. What's on your mind?",
            "Keeping an eye on the screens, Boss. Need anything?",
            "Studying today's candles, Boss. What can I do for you?")),
        Talk(Regex("^ (i m fine|im fine|i am fine|i m good|im good|i am good|i m doing good|i m doing well|i am doing well|fine|good|not bad|all good|great|it was good|it was great|good day|my day was good) $"), listOf(
            "Glad to hear it, Boss.", "That's good to hear, Boss.", "Great, Boss. Let's keep it that way.")),
        Talk(Regex("^ (it was bad|bad day|not good|i m not good|i am not good|i m sad|im sad|i am sad|i m stressed|i am stressed|i m worried|i am worried|rough day|tough day) $"), listOf(
            "Sorry to hear that, Boss. Take it easy; the market will be there tomorrow.",
            "That's tough, Boss. A short break helps. I'll keep watch.",
            "I'm here, Boss. No trades are needed today if you'd rather rest.")),
        Talk(Regex("^ (thanks|thank you|thank you jarvis|thanks a lot|thx|shukriya|dhanyavaad|great thanks|nice|good job|well done|awesome|perfect) $"), listOf(
            "Always, Boss.", "Happy to help, Boss.", "Anytime, Boss.", "My pleasure, Boss.")),
        Talk(Regex("^ (who are you|what are you|what is your name|what s your name|whats your name|your name|introduce yourself) $"), listOf(
            "I'm Jarvis, Boss: your trading assistant inside IraAlgo. I watch the markets, your positions and the news, and I only act when you approve.",
            "Jarvis, Boss. I read the markets and your account, suggest trades, and never act without your yes.")),
        Talk(Regex("^ (who made you|who created you|who built you) $"), listOf(
            "I was built for you, Boss, as part of IraAlgo, and everything I know stays on this phone.")),
        Talk(Regex("^ (good night|goodnight) $"), listOf(
            "Good night, Boss. I'll keep an eye on things.", "Good night, Boss. Sleep well.")),
        Talk(Regex("^ (bye|bye bye|see you|see you later|talk later|that s all|thats all|nothing|never mind|nevermind|forget it|leave it) $"), listOf(
            "Alright, Boss. I'm here when you need me.", "See you, Boss.", "Talk soon, Boss.", "Okay, Boss.")),
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
    /** [recent]: the last few exchanges (Boss's words, Jarvis's reply), oldest first, so a reply can follow the talk. */
    fun prompt(question: String, now: LocalDateTime, recent: List<Pair<String, String>> = emptyList()): String =
        "You are Jarvis, a friendly assistant inside a trading app, talking to its owner, whom you call Boss. " +
            "Reply in one or two short spoken sentences. Do not state any numbers, prices, dates or market facts. " +
            "Do not give buy or sell advice. Do not say you did, set, placed or changed anything. " +
            "If it is about the markets or the account, say you can answer that if Boss asks you directly about it. " +
            "It is ${now.dayOfWeek.name.lowercase()} ${if (now.hour < 12) "morning" else if (now.hour < 17) "afternoon" else "evening"}.\n\n" +
            recent.takeLast(2).joinToString("") { (b, j) -> "Boss: ${b.trim().take(200)}\nJarvis: ${j.trim().take(200)}\n" } +
            "Boss: ${question.trim().take(300)}\nJarvis:"

    private val ADVICE = Regex("(?i)\\b(buy\\w*|bought|sell\\w*|sold|short\\w*|go(ing)? long|long position|invest\\w*|enter\\w*|entry|exit\\w*|" +
        "book\\w*|profits?|stop ?loss\\w*|target\\w*|hold\\w*|accumulat\\w*|average down|add to|call options?|put options?|calls|puts|square off|bullish|bearish)\\b")
    private val ACTED = Regex("(?i)\\bi\\b[^.!?]{0,25}\\b(placed|set|changed|turned|started|stopped|sold|bought|closed|cancell?ed|switched|updated|enabled|disabled|done|made|moved|added|removed|muted|unmuted)\\b|" +
        "\\b(is|are|has been|have been|was|were|got) (now )?(placed|set|on|off|done|closed|cancell?ed|enabled|disabled|started|stopped|switched|updated|changed)\\b|\\bdone\\b")
    private val FIGURES = Regex("(?i)\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|" +
        "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|lakh|lakhs|crore|crores|million|billion|percent|per cent|rupees?|points?)\\b|%|₹")

    /** The model's reply when it is safe to say (no figures, no advice, no claims of action, short), else null. */
    fun accept(reply: String?): String? {
        val r = reply?.trim()?.removePrefix("Jarvis:")?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (r.length < 2 || r.length > 280) return null
        if (Regex("\\d").containsMatchIn(r) || FIGURES.containsMatchIn(r)) return null
        if (ADVICE.containsMatchIn(r) || ACTED.containsMatchIn(r)) return null
        // At most two sentences.
        val parts = Regex("(?<=[.!?])\\s+").split(r).filter { it.isNotBlank() }
        // A reply cut short by the word limit ends at its last whole sentence.
        val whole = parts.take(2).let { ps -> if (ps.size > 1 && !Regex("[.!?]$").containsMatchIn(ps.last())) ps.dropLast(1) else ps }
        return whole.joinToString(" ")
    }

    private val PERSONAL = Regex(" (you|your|yours|yourself|you re|youre) ")
    private val BUSINESS = Regex(" (nifty|banknifty|bank nifty|finnifty|sensex|vix|gold|market|markets|stock|stocks|share|shares|option|options|call|put|" +
        "strike|order|orders|trade|trades|trading|position|positions|p l|pnl|profit|loss|strategy|strategies|arm|arms|bot|bots|setting|settings|limit|limits|" +
        "lot|lots|buy|sell|exit|close|stop|start|switch|turn|set|change|cancel|kill|live|paper|pin|lock|fingerprint|mute|unmute|voice|news|chart|price|level|levels) ")

    /**
     * Words to Jarvis himself ("how was your day", "do you like music") that name nothing it can look up or do: straight
     * to the model to chat, without first asking it which command was meant.
     */
    fun personal(text: String): Boolean {
        val t = norm(text)
        return PERSONAL.containsMatchIn(t) && !BUSINESS.containsMatchIn(t) && Market.mentioned(text).isEmpty()
    }

    /** About Jarvis himself with no model to ask: a friendly word, varied. */
    private val ABOUT_ME = listOf(
        "Good question, Boss. My world is mostly charts and headlines, but I'm glad you asked.",
        "I'm happiest watching the markets for you, Boss. Ask me anything about them.",
        "That's a nice thought, Boss. I'm better with Nifty than with life questions, but I'm listening.")

    fun aboutMe(turn: Int): String = ABOUT_ME[Math.floorMod(turn, ABOUT_ME.size)]

    /** When nothing else answers: varied words, never the same line twice running. */
    private val FALLBACK = listOf(
        "I'm not sure about that one, Boss. Ask me about Nifty, BankNifty, your positions or your P&L.",
        "That's outside what I know, Boss. I'm best with the markets and your account.",
        "I didn't quite get that, Boss. Try asking about the market, your trades or a strategy.",
        "Hmm, I can't help with that, Boss. Want the market status or your P&L instead?")

    fun fallback(turn: Int): String = FALLBACK[Math.floorMod(turn, FALLBACK.size)]
}
