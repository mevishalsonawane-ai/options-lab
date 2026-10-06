package com.optionslab.ira

/**
 * Something the owner asks Jarvis to DO in the app (not an order to buy or sell - that is [OrderRequest]). The owner's
 * rule (2026-10-02): what adds risk (start, arm, kill switch off, Live mode, an alarm) is done at once; what stops or
 * closes ([Kind.reduces]) waits for one tap on Confirm. Jarvis only uses the app's own controls, and the app's limits
 * still apply. Pure: words in, the command out.
 */
data class Command(val kind: Kind, val target: String? = null, val number: Int? = null,
                   val market: Market? = null, val above: Boolean? = null, val level: Double? = null,
                   /** An event's day ([Kind.EVENT_ADD]). */ val day: java.time.LocalDate? = null,
                   /** An alarm by a move from the price now, in percent ([Kind.ALARM_ADD] without a level). */ val pct: Double? = null,
                   /** Lots said with a close ("close 1 lot of ..."): only a whole position is closed, so this is refused. */ val lots: Int? = null,
                   /**
                    * Several arms named in one stop ([Kind.STOP_ONE]: "stop orb sweep and range fade", "stop arms 3 and 4"), each as
                    * said (a name or a number) - one stop each, never all; or, with [keep], the ones that stay on ("stop all except
                    * orb", "orb chhod ke baaki band karo"): every other one that is on is stopped, each by itself ([Commands.stops]).
                    */
                   val targets: List<String> = emptyList(), val keep: Boolean = false,
                   /**
                    * The [targets] were set apart by a comma or "&" ("stop orb, sweep"): each is its own arm, never read together as
                    * one name ([Commands.stops]). Not shown in [toString] (the readings kept in tests stay as written).
                    */
                   val apart: Boolean = false) {
    /** As before [targets] and [keep] were added (the readings kept in tests stay as written); they are shown only when said. */
    override fun toString(): String = "Command(kind=$kind, target=$target, number=$number, market=$market, above=$above, level=$level, day=$day, pct=$pct, lots=$lots" +
        (if (targets.isNotEmpty() || keep) ", targets=$targets, keep=$keep" else "") + ")"

    enum class Kind(val reduces: Boolean) {
        STOP_ALL(true), START_ALL(false), STOP_ONE(true), START_ONE(false),
        CANCEL_ALL(true), CANCEL_ONE(true), CLOSE_ALL(true), CLOSE_ONE(true),
        KILL_ON(true), KILL_OFF(false), MODE_PAPER(true), MODE_LIVE(false),
        ALARM_ADD(false), ALARM_REMOVE(true), EVENT_ADD(false), EVENT_REMOVE(true), AUTOPILOT_ON(false), AUTOPILOT_OFF(true),
        /** Jarvis's own suggested trades: on paper (the default until proven) or in the app's mode; their daily loss limit. */
        JTRADES_PAPER(true), JTRADES_LIVE(false), JTRADES_LIMIT(false), JTRADES_RISK(false),
        /** Jarvis's voice: silent (replies on screen only) or speaking again; replies in Hindi or English. */
        MUTE(true), UNMUTE(true), HINDI(true), ENGLISH(true),
        /** One of the app's limits changed ([SettingsTalk]): always said back and confirmed; loosening needs Boss's voice. */
        SET_LIMIT(true), SET_REFUSED(true),
        /** The last limit change put back (confirmed; Boss's voice when that loosens a limit). */
        UNDO(true),
        /** Quiet hours (nothing spoken unasked at night) on or off. */
        QUIET_ON(true), QUIET_OFF(true),
        /** The suggestions the owner always rejected are offered again. */
        PREF_RESET(true),
        /** The day's P&L target set or cleared; a note on why the owner took a trade. */
        TARGET_SET(true), TARGET_CLEAR(true), NOTE(true),
        /** "That was wrong" (kept for fixing); the emergency exit (close all, kill switch on, stop the bots). */
        MISTAKE(true), EXIT_ALL(true),
        /** Short spoken answers on or off; "tell me more"; a past day replayed; Jarvis's weekly loss limit. */
        BRIEF_ON(true), BRIEF_OFF(true), MORE(true), PRACTICE(true), JTRADES_WEEKLY(false),
        /** What was learned from the owner's corrections is forgotten. */
        LEARN_RESET(true),
        /** "Why can't I hear you?": what stops the voice, said plainly. */
        VOICE_CHECK(true),
        /** How fast Jarvis speaks: slower, faster, back to normal (only his voice; nothing else changes). */
        PACE_SLOWER(true), PACE_FASTER(true), PACE_NORMAL(true),
        /** "Be quiet for 30 minutes": muted for [Command.number] minutes, then speaking again by itself. */
        MUTE_FOR(true),
        /**
         * "Don't listen", "mat suno", "sunna band karo" (Boss, 6 Oct): the microphone off altogether - no wake word, no
         * follow-ups, nothing heard - until Boss taps Listen again on the globe or in Settings. Only ever lowers what is
         * heard: no words, typed or said, switch listening back on ([NoListen]).
         */
        LISTEN_OFF(true),
    }
}

object Commands {
    /** A question about doing something ("how do I stop...") is not a command. */
    private val QUESTION = Regex("^ (how|where|what|whats|why|which|when|should|is|are|am|can i|could i|did|do you|do i|does|has|have|will|was|were|would|if|wonder|i wonder) | tell me (whether|what|why|how|where|when(?! [a-z0-9 ]{0,30}(above|below|over|under|cross[a-z]*|hits?|reach[a-z]*|touch[a-z]*|falls?|drops?|rises?|dips?|goes (up|down|to|above|below)|percent))|which) | is it [a-z0-9]* $| right $| or not $| (hua|hai|tha|hoga) kya $| kya (hua|hai) $")
    /** "Don't switch to live", "never start...": a negation is never a command. */
    private val NEGATION = Regex(" (don t|dont|do not|never|not|doesn t|didn t|won t) ")
    /** Commands a misspelt word may never become (only what the owner typed correctly). */
    private val NEVER_FROM_TYPO = setOf(Command.Kind.MODE_LIVE, Command.Kind.KILL_OFF, Command.Kind.JTRADES_LIVE, Command.Kind.AUTOPILOT_ON)
    /** Boss asking why Jarvis writes instead of speaking (Boss, 5 Oct: "why is it sending most things in chat?"). */
    private val VOICE_WHY = Regex("^ (jarvis )?(why (are|aren t|arent) you (not )?(speaking|talking)( to me)?( aloud| out loud)?|" +
        "why (are you|aren t you|arent you|do you|is it|is everything|everything|are things|are you sending( everything| things| most things)?|you are sending( everything| things)?) (only |just )?(in|to) (the )?chat|" +
        "why (only|just) (in )?(the )?chat|why (don t|dont|do not|won t|wont) you (speak|talk)( to me)?( aloud| out loud)?|" +
        "why (are you )?(not|no longer) (speaking|talking)|why are you silent|why (is|are) (your|you r) (voice|replies) (off|only on screen)|" +
        "why (aren t|arent|don t|dont) (i|we) hear(ing)? you|you (are|re) not speaking|you aren t speaking|you don t speak( any more| anymore)?|" +
        "aap bol kyun nahi rahe|bol kyun nahi rahe( ho)?|aawaz kyun nahi aa rahi|" +
        // Understanding round 24: Jarvis named, Indian English word order, "typing" for the chat, the Hindi said with "kyu".
        "why (is|isn t|isnt) jarvis (not )?(speaking|talking|silent|quiet)|why (you are|you re|u are|you r) not (speaking|talking)|" +
        "why (are you|you are|r u|are u) (only |just )?(typing|writing|texting)( only)?( instead of (speaking|talking))?|why (only|just) (text|typing|writing)|" +
        "why (is there )?no (sound|voice|audio)( from you)?|(aawaz|awaz|awaaz|aavaz|avaaz|voice) (kyun|kyu|kyon|why) nahi aa rahi( hai)?|" +
        // (As the Hindi reads once its "kyun" is turned to "why" - [Hinglish.normalize], which [Ask] parses commands from.)
        "(tum |aap )?bol (kyun|kyu|kyon|why) nahi rahe( ho)?)( jarvis| boss| any more| anymore)? $")
    /**
     * Jarvis's voice in Hinglish and the recognizer's split "your self" (understanding round 24), read as said, before the
     * Hindi verb is turned round ("awaz band karo" was read as "stop awaz", a strategy). Mute and unmute only: Jarvis's own
     * voice, nothing that trades.
     */
    private val MUTE_SAID = Regex("^ (jarvis )?((apni |apna |tumhari |aapki )?(awaz|aawaz|awaaz|aavaz|avaaz|voice) band (karo|kar do|kardo|kar dijiye|kijiye)|" +
        "mute (karo|kar do|kardo|ho jao|ho ja)|mute (your self|you self|urself|ur self|yourselves)|" +
        // (As [Hinglish.normalize] turns the verb round: "awaz band karo" -> "stop awaz".)
        "stop (apni |apna |tumhari |aapki |your )?(awaz|aawaz|awaaz|aavaz|avaaz|voice))( please| boss| jarvis| now)? $")
    private val UNMUTE_SAID = Regex("^ (jarvis )?((apni |apna |tumhari |aapki )?(awaz|aawaz|awaaz|aavaz|avaaz|voice) (chalu|on|wapas chalu) (karo|kar do|kardo)|" +
        "unmute (karo|kar do|kardo)|(start|begin) (speaking|talking) again|un mute (your self|you self)|" +
        "start (apni |apna |tumhari |aapki |your )?(awaz|aawaz|awaaz|aavaz|avaaz|voice))( please| boss| jarvis| now)? $")
    /**
     * "Aur bolo, Boss", "pura batao", "full details", "carry on": the full last answer ([Command.Kind.MORE]; it only says
     * what was already answered), read as said (round 24).
     */
    private val MORE_SAID = Regex("^ (jarvis )?(aur (batao|bataao|btao|bata|bolo|bataiye|boliye|sunao)|or (batao|bataao)|" +
        "(pura|poora|puri|poori|pure|poore) (batao|bataao|bolo|bataiye|answer)|full details|the full details|" +
        "carry on|continue|" +
        "tell me more|more|more details|go on|details|aur batao|" +
        // Understanding round 26: "say more", "keep going", "the rest", "tell me the rest", "baaki batao", "aage bolo", "poori baat
        // batao", "the full answer", "the whole thing".
        "say more|keep going|the rest|rest of it|the rest of it|(tell me|say|give me) the rest( of it)?|" +
        "(baaki|baki|baaki ka|baki ka|aage|aage ka) (batao|bataao|bolo|bataiye|boliye|sunao)|(poori|puri|pura|poora) baat (batao|bataao|bolo)|" +
        "(give me |say )?(the )?full answer|(say |tell me )?the whole thing|(give me |say )?the whole answer|" +
        // (As [Hinglish.normalize] turns "batao" round: "pura batao" -> "show pura". "Detail mein batao" and "explain in detail"
        // stay Boss's wish for the length of a topic, [TopicLength].)
        "show (pura|poora|puri|poori|pure|poore|or|details|full details|" +
        // ("Baaki batao" -> "show baaki", "poori baat batao" -> "show poori baat": round 26.)
        "baaki|baki|baaki ka|baki ka|aage|aage ka|(poori|puri|pura|poora) baat))( please| boss| jarvis| now)? $")
    private val ARM_NOUN = "(?:the )?(?:strategy|strategies|arm|arms|bot|bots|algo|script)?"
    /** Every market's names, longest first, as one alternation (for a removal by market). */
    private val ALIASES = Market.entries.flatMap { it.aliases }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    /**
     * [said] as typed; with a misspelt word ("start all statergies") read again with it fixed ([Spelling]) when that
     * finds a command and the words as typed found none, or only a name to look for.
     */
    fun parse(said: String): Command? = read.of(said, keep = { it?.kind != Command.Kind.EVENT_ADD }) { parseFresh(said) }

    /** The last words read ([Kept]): an event's day is today's, so an event is never kept. */
    private val read = Kept<Command?>(64)

    private fun parseFresh(said: String): Command? {
        val raw = parseAs(said)
        val fixed = Spelling.fix(said)
        if (fixed != said) {
            val f = parseAs(fixed)
            if (f != null && f.kind !in NEVER_FROM_TYPO && (raw == null || raw.number == null && raw.target != null && f.kind != raw.kind)) return f
        }
        return raw
    }

    private fun parseAs(said: String): Command? {
        // "Jarvis, note: I bought because of the hammer at support" - kept as said (before the question and negation checks).
        // ("Journal my day", "journal for today": the end-of-day journal assistant, [DayJournal] - not a note.)
        if (!DayJournal.asked(said)) rx("(?i)^\\s*(?:(?:hey |ok |okay )?jarvis[,.!]?\\s+)?(?:note|journal)(?: that| down)?\\s*[:,-]?\\s+(.{3,300})$").find(said.trim())?.let { m ->
            return Command(Command.Kind.NOTE, target = m.groupValues[1].trim())
        }
        // "Why aren't you speaking?" / "why only in chat?": the voice check (it only explains; nothing changes), asked or typed with "?".
        val asSaid = Spaced.words(said)
        if (VOICE_WHY.containsMatchIn(asSaid)) return Command(Command.Kind.VOICE_CHECK)
        // A question ("is live mode on?") is never a command.
        if (said.trim().endsWith("?")) return null
        if (MUTE_SAID.containsMatchIn(asSaid)) return Command(Command.Kind.MUTE)
        if (UNMUTE_SAID.containsMatchIn(asSaid)) return Command(Command.Kind.UNMUTE)
        // "Don't listen" (before the negation check: the "don't" is the command): the microphone off, until his button.
        if (NoListen.asked(said)) return Command(Command.Kind.LISTEN_OFF)
        if (MORE_SAID.containsMatchIn(asSaid)) return Command(Command.Kind.MORE)
        // An action said with a condition ("agar nifty 100 point gire to sab band kar do", "exit all if Nifty falls below 24000"):
        // Jarvis can't set one to wait, so it is never a command - neither done now nor read as an arm's garbled name
        // ([Conditional]; understanding round 29). The hub says so and suggests the app's own alarm, stop loss and limits.
        // (Nor Boss supposing his own act, "if I square off now" / "agar main exit karu to margin": a what-if, round 30.)
        // A risk-reducing command said with one - the kill switch on, stop all, exit all, close all - still goes through as
        // itself, to its own confirmation: a stop, a kill or an exit never adds risk, so the condition is never a reason to
        // refuse it (review, 6 Oct; [Conditional.passes]). A conditional order, start or one arm's stop stays refused.
        if (Conditional.asked(said) || Conditional.supposed(said)) return body(said).takeIf { Conditional.passes(said, it) }
        return body(said)
    }

    /** [parseAs] past the voice words and the conditional check. */
    private fun body(said: String): Command? {
        // "Stop too close?", "SL too tight": his stops asked about, never a STOP of an arm called "too close" ([StopNoise], round 30).
        if (StopNoise.asked(said)) return null
        // "Haan sab band kar do", "yes stop everything", "theek hai, stop orb": the yes is Boss agreeing with himself, never part
        // of what to stop (it was read as an arm called "haan all"): left out before the verb is turned round.
        val text = Hinglish.normalize(withoutYes(said))
        // "25,000" is one number; a full stop ends a sentence (but "52.5" keeps its point).
        val t = " " + text.lowercase().replace("%", " percent ").replace(rx("(\\d),(?=\\d{3})"), "$1").replace(rx("\\.(?!\\d)"), " ")
            .replace(rx("[^a-z0-9. ]"), " ").replace(rx("\\s+"), " ").trim() + " "
        val s = t.replace(rx(" (please|jarvis|hey|ok|okay|now|right now|immediately|can you|could you|for me) "), " ")
            .replace(rx("\\s+"), " ").let { " ${it.trim()} " }
        // "That was wrong": kept with what was said and answered (before the negation check: "not what I asked").
        if (rx("^ (that was wrong|that s wrong|thats wrong|that is wrong|wrong answer|you got (that|it) wrong|that s not right|thats not right|not what i asked|you misheard( me)?|you misunderstood( me)?) $").containsMatchIn(s))
            return Command(Command.Kind.MISTAKE)
        // Jarvis's voice (before the negation check: "don't speak" is a mute).
        if (rx("^ (un ?mute|unmute yourself|speak again|talk again|voice on|turn (on )?(your )?voice( on)?|you can (speak|talk)( now| again)?|start (speaking|talking)) $").containsMatchIn(s)) return Command(Command.Kind.UNMUTE)
        // A mute for a while: "be quiet for 30 minutes", "mute for an hour", "30 minute chup raho".
        rx("^ ((be quiet|mute|mute yourself|stay quiet|stay silent|go silent|don t speak|do not speak|stop talking|chup raho)( for)? (\\d{1,3}|an|a|one|half an) (min|mins|minute|minutes|hour|hours)|(\\d{1,3}) (minute|minutes|min|ghante|ghanta) (chup raho|mute|be quiet)) $").find(s)?.let { m ->
            val g = m.groupValues
            val num = g[4].ifEmpty { g[6] }
            val n = when (num) { "an", "a", "one" -> 1; "half an" -> 0; else -> num.toIntOrNull() ?: 0 }
            val unit = g[5].ifEmpty { g[7] }
            val minutes = if (unit.startsWith("h") || unit.startsWith("ghant")) (if (num == "half an") 30 else n * 60) else n
            if (minutes in 1..480) return Command(Command.Kind.MUTE_FOR, number = minutes)
        }
        // A mute needs clear words (a stray "quiet" or "silence" nearby is not one).
        if (rx("^ ((be |go |stay |keep )?(mute|muted)|be quiet|mute (yourself|your voice|the voice)|(be|go|stay|keep) (on )?silent|(don t|do not|stop) (speak|speaking|talk|talking)|voice off|turn (off )?(your )?voice( off)?) $").containsMatchIn(s)) return Command(Command.Kind.MUTE)
        if (rx("^ (why can t i hear you|why can i not hear you|i can t hear you|cant hear you|no voice|voice check|check (your|the) voice|why (are you|is your voice) (silent|not speaking|quiet)|why no voice|why (can t|cant|don t|dont|do not|can not|cannot) you (hear|listen to) me|why (are you|aren t you|arent you) (not )?(listening|hearing me)|you (are not|aren t|arent|don t|dont) (listening|hearing me)|(listening|mic|microphone) (is )?not working|(why |wht |wy )?(can t|cant|can not|cannot) (you )?(hear|here) me|(am i|can you|are you) (audible|hearing me|able to hear me)( to you| too you)?|(do|can) you (hear|here) me (now|at all|properly)|i (am|m) trying to (speak|talk)( to you)?( am i audible( to you| too you)?)?) $").containsMatchIn(s)) return Command(Command.Kind.VOICE_CHECK)
        // How fast he speaks (Boss, 4 Oct: answers to follow by ear).
        if (rx("^ ((speak|talk) (more )?(slower|slowly|slow)|(slower|slow down)( please)?|(thoda )?(dheere|dhire|aaram se) (bolo|boliye)) $").containsMatchIn(s)) return Command(Command.Kind.PACE_SLOWER)
        if (rx("^ ((speak|talk) (a bit )?(faster|quicker|quickly|fast)|(faster|speed up)( please)?|(thoda )?(jaldi|tez) (bolo|boliye)) $").containsMatchIn(s)) return Command(Command.Kind.PACE_FASTER)
        if (rx("^ ((speak|talk) (at )?(normal|normally|usual)( speed| pace)?|normal (speed|pace)|(speak|talk) normally|(normal|usual) (bolo|speed mein bolo)) $").containsMatchIn(s)) return Command(Command.Kind.PACE_NORMAL)
        if (rx("^ (reply|answer|speak|talk|respond)( to me)? in hindi $|^ hindi (mein|me) (bolo|baat karo|jawab do) $|^ hindi (replies|mode)( on)? $").containsMatchIn(s)) return Command(Command.Kind.HINDI)
        if (rx("^ (reply|answer|speak|talk|respond)( to me)? in english( again)? $|^ english (replies|mode)( on)? $").containsMatchIn(s)) return Command(Command.Kind.ENGLISH)
        if (QUESTION.containsMatchIn(s) || NEGATION.containsMatchIn(s)) return null
        fun has(r: String) = rx(r).containsMatchIn(s)
        fun num(r: String) = rx(r).find(s)?.groupValues?.get(1)?.toIntOrNull()
        // The words of a stop as read for the arms: a comma or "&" between names is an "and" here.
        val sl = (" " + text.lowercase().replace(rx("(\\d),(?=\\d{3})"), "$1").replace(rx("\\s*[,&]\\s*"), " and ").replace(rx("\\.(?!\\d)"), " ")
            .replace(rx("[^a-z0-9. ]"), " ").replace(rx("\\s+"), " ").trim() + " ")
            .replace(rx(" (please|jarvis|hey|ok|okay|now|right now|immediately|can you|could you|for me) "), " ").replace(rx("\\s+"), " ").let { " ${it.trim()} " }
        // A stop of all but some ("stop all except orb", "orb chhod ke baaki band karo", "stop everything but keep orb running"):
        // once said, it is that or nothing - the ones kept read as names, or nothing is done (Jarvis asks again). Never the stop
        // of everything, a close or anything else read from the rest of it ("stop all except orb and close positions", "stop
        // everything except orb in paper mode", a list too long; review, 6 Oct). Before every other action here.
        if (keepSaid(sl)) return stopAllBut(sl)

        if (rx("^ (undo|undo (that|it|the last change|my last change|last change|the change)|revert( that| it| the last change)?|put (it|that) back|change (it|that) back) $").containsMatchIn(s)) return Command(Command.Kind.UNDO)
        // The day's target: "my target today is 3000", "set my daily target to 5k", "clear my target".
        if (rx(" (clear|remove|cancel|delete|drop) (my |the |today s )?(daily |day s |day )?target ").containsMatchIn(s)) return Command(Command.Kind.TARGET_CLEAR)
        // ("Nifty target 25000 today" is a market level, not the owner's day target.)
        if (Market.mentioned(s).isEmpty()) rx(" target (\\d+(?:\\.\\d+)?) ?(k|thousand|lakh)? (?:today|for today|for the day) ").find(s)?.let { m ->
            val v = m.groupValues[1].toDouble() * when (m.groupValues[2]) { "k", "thousand" -> 1_000.0; "lakh" -> 100_000.0; else -> 1.0 }
            if (v >= 100) return Command(Command.Kind.TARGET_SET, level = v)
        }
        rx(" (?:my (?:daily |day s |day )?target(?: for today| for the day| today)?|(?:daily|day s|today s) target|target (?:for today|for the day|today)) (?:is |to |at |of |=|be )?(?:rs |rupees )?(\\d+(?:\\.\\d+)?) ?(k|thousand|lakh)?(?: rupees)? ").find(s)?.let { m ->
            val v = m.groupValues[1].toDouble() * when (m.groupValues[2]) { "k", "thousand" -> 1_000.0; "lakh" -> 100_000.0; else -> 1.0 }
            if (v >= 100) return Command(Command.Kind.TARGET_SET, level = v)
        }
        if (rx("^ (reset|clear|forget) (my |your )?(preferences|suggestion preferences|what i reject(ed)?) $").containsMatchIn(s)) return Command(Command.Kind.PREF_RESET)
        if (rx("^ (turn|switch) (on|off) (the )?quiet hours | quiet hours (on|off) |^ (enable|disable) (the )?quiet hours ").containsMatchIn(s))
            return Command(if (rx(" (off|disable) ").containsMatchIn(s.replace(" quiet hours ", " "))) Command.Kind.QUIET_OFF else Command.Kind.QUIET_ON)
        // "Tell me if BankNifty falls 1% from here": an alarm at a level worked out from the price now.
        if (has(" (alert|alarm|notify|tell|ping|wake|warn) ")) MoveAlarm.read(s)?.let { mv ->
            return Command(Command.Kind.ALARM_ADD, market = Market.mentioned(s).firstOrNull(), above = mv.up, pct = mv.pct)
        }
        // The emergency exit: everything closed, the kill switch on, the bots stopped.
        if (rx("^ (emergency exit|exit everything|panic( exit| button)?|close everything and stop|get me out( of everything)?|exit all( now)?|square off everything and stop) $").containsMatchIn(s))
            return Command(Command.Kind.EXIT_ALL)
        if (rx("^ (forget|reset|clear) (what you (have )?learned|your learning|what you learnt|the corrections) $").containsMatchIn(s)) return Command(Command.Kind.LEARN_RESET)
        if (rx("^ (brief mode( on)?|short answers( please)?|keep it short|be brief|shorter answers) $").containsMatchIn(s)) return Command(Command.Kind.BRIEF_ON)
        // How much Jarvis says aloud (Boss, 5 Oct): a preference for his voice only - nothing else changes.
        if (rx("^ (shorter|shorter please|say less|talk less|less detail|less detail please|too long|that was too long|keep it shorter) $").containsMatchIn(s)) return Command(Command.Kind.BRIEF_ON)
        if (rx("^ (brief mode off|full answers|detailed answers|long answers|answer in full|longer|longer answers|in more detail|more detail always|always more detail) $").containsMatchIn(s)) return Command(Command.Kind.BRIEF_OFF)
        if (rx("^ (tell me more|more|more details|go on|details|explain more|the full answer|repeat|repeat that|repeat it|say that again|say it again|say again|come again|pardon|sorry what|what did you say|once more|one more time|aur (batao|bolo|bataiye)|show aur|give me (the )?details|show (me )?(the )?details) $").containsMatchIn(s)) return Command(Command.Kind.MORE)
        if (Practice.asked(s) && TradeReplay.asked(s) == null && rx("^ (practi[cs]e|replay|simulate|rehearse) ").containsMatchIn(s)) return Command(Command.Kind.PRACTICE, target = text)
        rx(" (?:your|jarvis s|jarvis) (?:own )?(?:trades? )?weekly loss limit (?:to |at |of )?(?:rs |rupees )?(\\d{3,7}) ").find(t)?.let { m ->
            return Command(Command.Kind.JTRADES_WEEKLY, level = m.groupValues[1].toDouble())
        }
        // Boss's 06 Oct rule: a Pine strategy's stop-loss, target and profit lock never go off (refused, [PineProtection]).
        if (PineProtection.breaksRule(s)) return Command(Command.Kind.SET_REFUSED, target = PineProtection.REFUSED_TARGET)
        // The app's limits ("set max lots to 3"); never the PIN, real orders or the lock.
        if (SettingsTalk.forbidden(s)) return Command(Command.Kind.SET_REFUSED)
        if (!rx(" (your|jarvis s) | jarvis (own )?(trades? )?(daily )?(loss limit|risk) ").containsMatchIn(t)) SettingsTalk.parse(s)?.let { return it }
        // Jarvis's own trades (before Live / Paper mode: "let your trades go live" is not the app's mode).
        if (has(" (your|jarvis s|jarvis) (own )?trades? (go |to |on )?live | (let|put|send|switch|move|take) (your|jarvis s) (own )?trades? (go )?(to )?live ")) return Command(Command.Kind.JTRADES_LIVE)
        if (has(" (your|jarvis s) (own )?trades? (on|to|back to|go to) paper | keep (your|jarvis s) (own )?trades? (on )?paper ")) return Command(Command.Kind.JTRADES_PAPER)
        if (rx(" (?:your|jarvis s|jarvis) (?:own )?(?:trades? )?risk (?:per trade )?(?:off|none|zero) ").containsMatchIn(t)) return Command(Command.Kind.JTRADES_RISK)
        rx(" (?:your|jarvis s|jarvis) (?:own )?(?:trades? )?risk (?:per trade )?(?:to |at |of )?(?:rs |rupees )?(\\d{3,7}) ").find(t)?.let { m ->
            return Command(Command.Kind.JTRADES_RISK, level = m.groupValues[1].toDouble())
        }
        rx(" (?:your|jarvis s|jarvis) (?:own )?(?:trades? )?(?:daily )?loss limit (?:to |at |of )?(?:rs |rupees )?(\\d{3,7}) ").find(t)?.let { m ->
            return Command(Command.Kind.JTRADES_LIMIT, level = m.groupValues[1].toDouble())
        }
        when {
            has(" (turn|switch|put) (on )?(the )?kill switch on | (turn|switch) on (the )?kill switch | (activate|enable|engage) (the )?kill switch | kill switch on ") -> return Command(Command.Kind.KILL_ON)
            has(" (turn|switch) off (the )?kill switch | (turn|switch) (the )?kill switch off | (deactivate|disable|release|clear|stop) (the )?kill switch | kill switch off ") -> return Command(Command.Kind.KILL_OFF)
            has(" (switch|go|change|move) (to |back to )?live( mode| trading)? | live mode on | (start|use) live (mode|trading) ") -> return Command(Command.Kind.MODE_LIVE)
            has(" (switch|go|change|move) (to |back to )?paper( mode| trading)? | paper mode on | (start|use) paper (mode|trading) ") -> return Command(Command.Kind.MODE_PAPER)
        }
        if (has(" (turn|switch) on (the )?autopilot | (enable|start) (the )?autopilot | autopilot on ")) return Command(Command.Kind.AUTOPILOT_ON)
        if (has(" (turn|switch) off (the )?autopilot | (disable|stop) (the )?autopilot | autopilot off ")) return Command(Command.Kind.AUTOPILOT_OFF)
        // Events: "add event RBI policy on 5 Dec", "remove event 2".
        rx(" (add|note|remember|mark) (an |the )?event (.+) (on|for) (.+?) $").find(s)?.let { m ->
            return Command(Command.Kind.EVENT_ADD, target = m.groupValues[3].trim(), day = Events.date(m.groupValues[5], java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))))
        }
        if (has(" (remove|delete|cancel|clear) (the |my )?event")) return Command(Command.Kind.EVENT_REMOVE, number = num(" event (\\d+) "), target = rest(s, " event "))
        // Alarms: "alert me when nifty goes above 25000", "set an alarm on banknifty below 51000", "remove alarm 2".
        // "Remove the Nifty alarm", "clear all BankNifty alerts", "remove alarms on Sensex": a market's alarms, named right
        // in the removal (never from elsewhere in the sentence; two markets named: all of them, as before).
        val aliases = ALIASES
        val clause = rx(" (?:remove|delete|cancel|clear) (?:the |my |all |all my |all the )?(?:last )?($aliases) (?:index )?(?:alarm|alert)s?(?= |$)" +
            "| (?:remove|delete|cancel|clear) (?:the |my |all |all my |all the )?(?:alarm|alert)s? (?:on|for|of) (?:the )?($aliases)(?= |$)").find(s)
        val alarmMarket = clause?.let { Market.mentioned(it.value).firstOrNull() }?.takeIf { Market.mentioned(s).size == 1 }
        // A sentence that also sets an alarm or touches orders is not a removal by market.
        val notRemoval = has(" alert me | set (an |a )?(alarm|alert) | lagao | order | orders ")
        if (has(" (remove|delete|cancel|clear) (the |my |all |all my |all the )?(last )?(alarm|alert)s? ") || clause != null && !notRemoval) {
            return Command(Command.Kind.ALARM_REMOVE, number = num(" (?:alarm|alert) (\\d+) "),
                target = if (has(" last ")) "last" else if (has(" all ")) "all" else null, market = alarmMarket)
        }
        // An alarm needs its level: the number after above / below / reaches ("... above 25000 in 15 minutes" is 25000).
        // ("Remind me when Nifty hits 25000", "set alarm Nifty 24500", "Nifty 24500 pe alert lagao": the level is the
        // number; with no direction word the direction is asked.)
        // ("batana" alone is "tell me" - a question; only with a level verb, or an alert word, is it an alarm. An option's
        // price, premium or strike is never an alarm level here.)
        // (Hinglish "pe" before an alert word is "at", not a put: "Nifty 24500 pe alert lagao".)
        val optionWords = has(" (ce|call|put|premium|price|ltp) ") || (has(" pe ") && !has(" pe (alert|alarm|pahunche|pahunch jaye|aaye|aa jaye|cross kare|hit kare|touch kare) "))
        val setAlarm = !optionWords && has(" set (an |a )?(alarm|alert) | (alarm|alert) (lagao|laga do|set karo|set kar do|set) | (pahunche|pahunch jaye|aaye|aa jaye|cross kare|hit kare|touch kare) (to )?(batana|bata dena) ")
        val lvl = rx(" (?:above|below|over|under|crosses|crossing|cross|rises to|falls to|drops to|reaches|hits|hit|touches|touch|to) (\\d{2,6}(?:\\.\\d+)?) ").find(s)?.groupValues?.get(1)?.toDouble()
            ?: if (setAlarm) rx(" (\\d{4,6}(?:\\.\\d+)?) ").find(s)?.groupValues?.get(1)?.toDouble() else null
        if (lvl != null && (has(" (alert|alarm|notify|tell|ping|wake|remind|let me know) ") || setAlarm) &&
            (setAlarm || has(" (above|below|over|under|crosses|crossing|cross|rises|falls|drops|goes|reaches|hits|hit|touches|touch) "))) {
            val m = Market.mentioned(s).firstOrNull()
            val above = when { has(" (below|under|falls|drops|down to) ") -> false; has(" (above|over|rises|crosses|up to|reaches) ") -> true; else -> null }
            return Command(Command.Kind.ALARM_ADD, market = m, above = above, level = lvl)
        }
        // Orders and positions.
        if (has(" (cancel|stop|kill|remove) (all|every|each)( the| my)?( open| pending| working)? orders? | cancel (my |the )?(open |pending )?orders ")) return Command(Command.Kind.CANCEL_ALL)
        if (has(" cancel (the |my )?(last |latest )?order")) {
            return Command(Command.Kind.CANCEL_ONE, number = num(" order (\\d+) "), target = if (has(" (last|latest) ")) "last" else rest(s, " order "))
        }
        if (has(" (square off|squareoff|close|exit|sell off) (all|everything|every position|all positions|all my positions|my positions|the positions)( |$)") ||
            has("^ sell (all|everything|all positions|all my positions|my positions)( now)? $")) return Command(Command.Kind.CLOSE_ALL)
        if (has(" (square off|squareoff|close|exit) (the |my )?position")) {
            return Command(Command.Kind.CLOSE_ONE, number = num(" position (\\d+) "), target = rest(s, " position "))
        }
        // "Close the Nifty position", "exit 24500 CE", "sell my BankNifty call", "book profit in Nifty": one position, by its
        // words (a new sell is "sell" without "my" - never read as a close).
        // (A number of lots said with a close is kept aside: only a whole position closes, so the app refuses a part.)
        val lotWords = rx(" (\\d+|one|two|three|four|five|ek|do) lots?( of)? ")
        val saidLots = lotWords.find(s)?.groupValues?.get(1)?.let { it.toIntOrNull() ?: (listOf("one", "two", "three", "four", "five").indexOf(it) + 1).takeIf { n -> n > 0 } ?: if (it == "ek") 1 else 2 }
        val sc = s.replace(lotWords, " ")
        rx("^ (?:square off|squareoff|close|exit|sell my|book (?:my |the )?profits? (?:in|on)) (?:the |my )?((?:[a-z0-9]+ ){0,3}?)(position|trade|call|put|ce|pe|option)s?( |$)").find(sc)?.let { m ->
            val kind = m.groupValues[2].takeIf { it !in setOf("position", "trade", "option") }
            val words = listOfNotNull(m.groupValues[1].trim().takeIf { it.isNotEmpty() }, kind).joinToString(" ")
            return Command(Command.Kind.CLOSE_ONE, target = words.ifEmpty { null }, lots = saidLots)
        }
        if (rx("^ book (?:my |the )?profits? $").containsMatchIn(s)) return Command(Command.Kind.CLOSE_ONE)
        rx("^ book (?:my |the )?profits? (?:in|on) (?:the |my )?(.+?) $").find(s)?.let { return Command(Command.Kind.CLOSE_ONE, target = it.groupValues[1].trim()) }
        // The bots: everything at once, or one strategy / arm by its number or name. All but some ("stop all except orb", "orb
        // chhod ke baaki band karo") and several by name ("stop the arms orb sweep and range fade", "range fade aur sweep band
        // kardo") are read first: each is stopped by itself, never all of them (Boss, 6 Oct: two arms switched off became a stop
        // of everything). A comma or "&" between names is an "and" here.
        // (All but some was read above, before the other actions.)
        stopSeveral(sl, rx("[a-z0-9)]\\s*(?:,(?!\\d{3})|&)\\s*[a-z0-9(]").containsMatchIn(text.lowercase()))?.let { return it }
        if (has(" (stop|halt|pause|disarm|switch off|turn off) (all|every|everything)( the| my)?( strategies| arms| bots| algos| scripts| trading)? | stop trading | stop (the |my )?(bots|algos|arms|strategies) ")) return Command(Command.Kind.STOP_ALL)
        if (has(" (start|resume|restart) (all |the |my )?(bots|arms|strategies|algos|trading) again | (resume|restart) (all|trading|the bots|everything) | start trading again ") ||
            has(" (start|arm|switch on|turn on|run|enable|resume) (all|every)( the| my)? (strategies|strategy|arms|arm|bots|algos|scripts) | (start|arm|switch on|turn on|run|enable|resume) (everything|all) $| (start|arm|switch on|turn on|run|enable) (the |my )?(strategies|arms|bots|algos) $"))
            return Command(Command.Kind.START_ALL)
        // One strategy or arm: the verb comes first ("stop strategy 2"), and settings are never read as a name.
        val notArm = rx("kill switch|\\blive\\b|paper|\\bmode\\b|alert|alarm|autopilot|listening|^trading$|^(it|that|this|jarvis|everything)$|voice|notifications?|\\bloss\\b|talking|speaking|^(when|if|once|after|before|sending|telling|giving|the music|music|news|calling|reminding)\\b|timer|recording|backtest" +
            // "Stop correcting your confidence words" is Jarvis's own wording check ([WordFit]), never a strategy (routing round 11).
            "|^(correcting|matching|calibrating|adjusting) (your |his |the )?(confidence |frequency )?words\\b" +
            // "Turn on the flashlight" is the phone's, outside the app ([OutsideApp]), never a strategy (routing round 13).
            "|^(the |my )?(flashlight|torch|wifi|wi fi|bluetooth|hotspot)$" +
            // "Isko band karo" names nothing (a close by a pronoun, [Plan.pronounClose]): never a strategy called "isko".
            "|^(isko|usko|is|us|ise|use|isse|usse|ye|yeh|wo|woh|vo)$" +
            // "Stop stop" / "stop, wait" is Boss hushing Jarvis's voice ([BargeIn]), never a strategy called "stop" (5 Oct).
            "|^(stop|bas|ruko|rukko|chup|wait|enough|quiet|please)( (stop|bas|ruko|rukko|chup|wait|enough|quiet|please|now|it))*$" +
            // "Awaz band karo" / "awaz chalu karo" is Jarvis's voice (round 24), never a strategy called "awaz".
            "|^(apni |apna |tumhari |aapki )?(awaz|aawaz|awaaz|aavaz|avaaz)\\b" +
            // "Stop the follow up questions", "follow up band karo", "turn off follow ups": Jarvis's question offered at the end of
            // an answer ([NextAsk]'s undo, round 25), never a strategy called "follow up questions".
            "|^(the |your |these |ye |yeh )?(follow ?ups?|follow ?up (questions?|offers?|suggestions?|sawal|sawaal)|next questions?|next question offers?)$")
        rx("^ (stop|disarm|switch off|turn off|pause|halt) $ARM_NOUN ?(.+)$").find(s)?.let { m ->
            val what = m.groupValues[2].trim()
            if (what.isNotEmpty() && !notArm.containsMatchIn(what) && !habitUndo(s)) return one(Command.Kind.STOP_ONE, what)
        }
        rx("^ (start|arm|switch on|turn on|resume|run|enable) $ARM_NOUN ?(.+)$").find(s)?.let { m ->
            val what = m.groupValues[2].trim()
            if (what.isNotEmpty() && !notArm.containsMatchIn(what)) return one(Command.Kind.START_ONE, what)
        }
        return null
    }

    /** "Stop offering", "stop reminding me", "stop saying", "stop shortening"...: a speech habit of Jarvis's own named after "stop". */
    private val HABIT_VERB = rx("^ stop (offering|reminding|saying|shortening|cutting|skipping|adding|qualifying|giving|telling|leaving out|mentioning|naming|listing|putting|starting with|starting on|beginning with|beginning on|leading with|opening with|changing|switching|getting|checking|preparing|reading|keeping|loading|fetching|warming up|suggesting|asking|ending|finishing) ")

    /**
     * "Stop offering my morning question", "stop shortening your briefings", "stop reminding me why I turn your ideas down":
     * the undo of a speech habit Jarvis learned ([MorningAsks], [TurnDowns], [TalkHours], [HonestStars], [WordFit],
     * [MorningSense], [TopicLength], [LeadIndex], [LeadPart], [NextAsk], [MoreAfter], [SmallTrades], [DayIndex], [CheckTimes], [CondNeeds]) - never a strategy called "offering my morning question" (understanding round 17). Only when that
     * habit's own undo takes the very words: "stop orb", "stop the order watch", "stop offering trades" stay as they were.
     */
    private fun habitUndo(s: String): Boolean = HABIT_VERB.containsMatchIn(s) && (
        MorningAsks.asked(s) == MorningAsks.Request.RESET || TurnDowns.asked(s) == TurnDowns.Request.RESET ||
            TalkHours.asked(s) == TalkHours.Request.RESET || HonestStars.asked(s) == HonestStars.Request.RESET ||
            WordFit.asked(s) == WordFit.Request.OFF || MorningSense.asked(s) == MorningSense.Request.RESET ||
            TopicLength.asked(s) == TopicLength.Request.RESET || LeadIndex.asked(s) == LeadIndex.Request.RESET ||
            LeadPart.asked(s) == LeadPart.Request.RESET || NextAsk.asked(s) == NextAsk.Request.RESET ||
            MoreAfter.asked(s) == MoreAfter.Request.RESET || SmallTrades.asked(s) == SmallTrades.Request.RESET ||
            DayIndex.asked(s) == DayIndex.Request.RESET || CheckTimes.asked(s) == CheckTimes.Request.RESET ||
            CondNeeds.asked(s) == CondNeeds.Request.RESET)

    // ---- several arms in one stop, or all but some (Boss, 6 Oct) ---------------------------------------------------------

    /** "Haan", "yes", "ok", "theek hai" said first, before a verb that acts. */
    private val YES_FIRST = Regex("(?i)^\\s*(?:(?:haan ji|ji haan|haan|haa|han|ha|hanji|ji|yes|yeah|yep|yup|ok|okay|theek hai|thik hai|theek|thik|bilkul|sure)\\s*[,.!]*\\s+)+(?=\\S)")
    private val ACTS_AFTER_YES = Regex("(?i)\\b(stop|band|bandh|close|exit|square|cancel|halt|pause|disarm|switch|turn|start|chalu|shuru|resume|kill|roko|rok)\\b")

    /** [said] without a leading yes-word when a verb that acts follows it ("haan sab band kar do" -> "sab band kar do"). */
    internal fun withoutYes(said: String): String {
        // As [Hinglish.normalize] turns the verb round, the yes lands after it: "stop haan all".
        YES_AFTER_VERB.find(said)?.let { m -> if (m.range.last + 1 < said.trimEnd().length) return said.replaceRange(m.range, m.groupValues[1]) }
        val m = YES_FIRST.find(said) ?: return said
        val rest = said.substring(m.range.last + 1)
        return if (ACTS_AFTER_YES.containsMatchIn(rest)) rest else said
    }
    private val YES_AFTER_VERB = Regex("(?i)^(\\s*(?:stop|start|close|cancel)\\s+)(?:(?:haan ji|ji haan|haan|haa|han|ha|hanji|ji|yes|yeah|ok|okay|theek hai|thik hai|theek|thik|bilkul)\\s*[,.!]*\\s+)+(?=\\S)")

    private const val STOP_VERB = "(?:stop|halt|pause|disarm|switch off|turn off|shut down|shut off)"
    private const val GROUP = "(?:strategies|strategy|arms|arm|bots|bot|algos|algo|scripts|script|trading)"
    private const val EVERY = "(?:all of them|all|every|everything|each|sab|sabhi|saare)"
    private const val REST = "(?:everything else|all else|all the rest|the rest|rest|the others|all others|all the others|others|baaki all|baki all|all baaki|all baki|baaki|baki|bache hue|everyone else)"
    private const val EXCEPT = "(?:except for|except|but not|but|apart from|other than|besides|excluding|save for|leaving out|leaving|minus)"
    /** Hindi "chhod ke" / "ke alawa" as [Hinglish.normalize] leaves it ("ke" dropped, "sab" turned to "all"). */
    private const val SPARE = "(?:ko )?(?:chhod kar|chod kar|chhod ke|chod ke|chhodke|chodke|chhodkar|chodkar|chhod|chod|chhoda|chodo|chhodo|alawa|alava|siva|sivay|sivaay|siwa)"
    private const val TAIL = "(?: (?:for today|today|for the day|for the rest of the day|as well|too|also|bhi|ko))*"

    private val ALL_BUT = listOf(
        // "stop everything but keep orb running", "stop the rest, keep orb on"
        rx("^ $STOP_VERB (?:the |my )?(?:$EVERY|$REST)(?: the| my| of the| of my)?(?: $GROUP)?$TAIL(?: and| but)? (?:keep|leave) (.+?)(?: running| on| going| armed| alone| active| as it is| as they are)?$TAIL $"),
        // "stop all except orb", "stop all strategies except orb and orb fresh", "halt everything apart from range fade"
        rx("^ $STOP_VERB (?:the |my )?$EVERY(?: the| my| of the| of my)?(?: $GROUP)?$TAIL $EXCEPT (?:the |my )?(.+?)$TAIL $"),
        // "except orb stop all", "apart from orb stop everything"
        rx("^ $EXCEPT (?:the |my )?(.+?) $STOP_VERB (?:the |my )?(?:$EVERY|$REST)(?: the| my)?(?: $GROUP)?$TAIL $"),
        // "keep orb running and stop the rest", "keep orb on, stop everything else"
        rx("^ keep (?:the |my )?(.+?) (?:running|on|going|armed|alive|active)(?: and)?(?: then)? $STOP_VERB (?:the |my )?(?:$REST|$EVERY)(?: the| my)?(?: $GROUP)?$TAIL $"),
        // As [Hinglish.normalize] turns it round: "stop orb aur orb fresh chhod baaki", "stop range fade ko chhod all".
        rx("^ stop (?:the |my )?(.+?) $SPARE (?:$REST|$EVERY)(?: $GROUP)?$TAIL $"),
        // "sab band karo range fade chhod ke" (the verb in the middle): "stop all range fade chhod".
        rx("^ stop (?:$EVERY|$REST)(?: $GROUP)? (?:the |my )?(.+?) $SPARE$TAIL $"),
    )

    /** Words a name in a list never holds: another action, a setting, or "all". */
    private val NOT_A_NAME = rx("\\b(all|every|everything|close|exit|cancel|square|kill|switch|start|buy|sell|positions?|orders?|mode|paper|live|alerts?|alarms?|then|phir|stop|band|lots?|target|loss|sl|limit|except|keep|when|if|agar|jab)\\b")

    /** The names in [list] ("orb aur orb fresh", "3 and 4", "strategy 2 and strategy 3"), each as said, or null when one is not a name. */
    private fun names(list: String): List<String>? {
        val parts = list.trim().split(rx(" (?:and|aur|or|plus|ya|tatha) ")).map { p ->
            p.trim().replace(rx("^(?:(?:the|my|also|and) )+"), "").replace(rx("^$GROUP "), "").replace(rx("^(?:number |no |#)"), "")
                .replace(rx("(?: (?:running|on|going|armed|alone|active|for today|today|for the day|as well|too|also|bhi|ko|only|wala|wali))+$"), "").trim()
        }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 8) return null
        if (parts.any { it.split(' ').size > 5 || NOT_A_NAME.containsMatchIn(it) }) return null
        return parts
    }

    /** "Stop all except orb" and its kin: the ones that stay ([Command.keep]), never a stop of everything; null when unclear. */
    private fun stopAllBut(s: String): Command? {
        for (r in ALL_BUT) {
            val m = r.find(s) ?: continue
            val kept = names(m.groupValues[1]) ?: return null
            return Command(Command.Kind.STOP_ONE, targets = kept, keep = true)
        }
        return null
    }

    /** A stop word, "all" (or "the rest"), and an "except" / "chhod ke" / "keep ... running": all but some said, however it goes on. */
    private val KEEP_SAID = listOf(
        rx(" $EXCEPT( |$)"), rx(" $SPARE( |$)"),
        rx(" (?:keep|leave) (?:.+ )?(?:running|on|going|armed|alive|active|alone|as it is|as they are)( |$)"),
    )

    /** Is [s] a stop of all but some ([stopAllBut]) - read as that or as nothing, never as anything else? */
    private fun keepSaid(s: String): Boolean {
        if (ALL_BUT.any { it.containsMatchIn(s) }) return true
        if (!rx(" $STOP_VERB ").containsMatchIn(s) || !rx(" (?:$EVERY|$REST)( |$)").containsMatchIn(s)) return false
        return KEEP_SAID.any { it.containsMatchIn(s) }
    }

    /** What is never an arm's name in a stop: Jarvis's own voice and the like (as [body]'s own list). */
    private val NOT_ARM_NAME = rx("^(it|that|this|them|these|those|jarvis|yourself|talking|speaking|listening|voice|music|the music|notifications?|follow ?ups?|correcting)\\b")

    /**
     * "Stop the arms orb sweep and range fade", "stop arms 3 and 4", "turn off orb sweep and range fade", "stop range fade aur
     * sweep" (as normalized), "stop the bots orb sweep", "stop trading orb sweep": each one named is stopped by itself. One
     * name after "the bots" / "trading" is that one arm; "stop trading for today" names none (the stop of everything).
     */
    private fun stopSeveral(s: String, apart: Boolean): Command? {
        val m = rx("^ $STOP_VERB (?:the |my )?(?:($GROUP) )?(.+?) $").find(s) ?: return null
        val group = m.groupValues[1].isNotEmpty()
        val rest = m.groupValues[2].trim()
        if (rx("^(?:$EVERY|$REST|$GROUP)( |$)").containsMatchIn(rest)) return null
        if (rx("^(?:for today|today|for the day|for the rest of the day|as well|too|also|bhi|again)( |$)").containsMatchIn(rest)) return null
        // "Stop offering trades and news", "stop sending alerts": a habit of Jarvis's own, never arms ([habitUndo]).
        if (HABIT_VERB.containsMatchIn(s) || rx("^[a-z]+ing( |$)").containsMatchIn(rest)) return null
        val parts = names(rest) ?: return null
        if (parts.any { NOT_ARM_NAME.containsMatchIn(it) }) return null
        return when {
            parts.size >= 2 -> Command(Command.Kind.STOP_ONE, targets = parts, apart = apart)
            group -> one(Command.Kind.STOP_ONE, parts.single())
            else -> null
        }
    }

    /** What a stop of several ([Command.targets]) or of all but some ([Command.keep]) comes to among the arms named [names]. */
    data class Stops(
        /** The ones to stop, by index into the names (each stopped by itself). */
        val stop: List<Int>,
        /** The ones that stay on (said to be kept). */
        val stay: List<Int>,
        /** Names said that match no arm, or more than one: then nothing is stopped and Jarvis asks. */
        val unclear: List<String>,
    )

    /**
     * [c]'s arms among [names] ([on]: whether each is armed or running now). Several named: each must match exactly one; only
     * when they do not each name an arm of its own is the whole read as one name ("buy and hold", "range and fade") - never
     * when set apart by a comma or "&" ([Command.apart]), and never over arms each named ("stop orb and sweep" is ORB and ORB
     * Sweep, not "ORB Sweep" alone: review, 6 Oct). All but some: every one that is on and not kept. A name that matches none,
     * or several, is [Stops.unclear] - nothing is stopped then (Boss is asked).
     */
    fun stops(c: Command, names: List<String>, on: List<Boolean>): Stops {
        fun find(t: String): Int? = pick(one(Command.Kind.STOP_ONE, t), names)
        if (!c.keep) {
            val found = c.targets.map { it to find(it) }
            val each = found.map { it.second }
            val apart = each.all { it != null } && each.distinct().size == each.size
            if (!apart && !c.apart) exact(c.targets.joinToString(" and "), names)?.let { return Stops(listOf(it), emptyList(), emptyList()) }
            return Stops(found.mapNotNull { it.second }.distinct(), emptyList(), found.filter { it.second == null }.map { it.first })
        }
        val kept = c.targets.map { it to find(it) }
        val stay = kept.mapNotNull { it.second }.distinct()
        val unclear = kept.filter { it.second == null }.map { it.first }
        return Stops(names.indices.filter { it !in stay && on.getOrElse(it) { false } }, stay, unclear)
    }

    /** [st] in words for the confirm: "stop ORB Sweep and Range Fade, each by itself - ORB stays on". */
    fun sayStops(st: Stops, names: List<String>): String {
        fun list(ix: List<Int>) = ix.map { names[it] }.let { n -> if (n.size <= 1) n.joinToString("") else n.dropLast(1).joinToString(", ") + " and " + n.last() }
        val stays = if (st.stay.isEmpty()) "" else " - ${list(st.stay)} ${if (st.stay.size == 1) "stays" else "stay"} on"
        return "stop ${list(st.stop)}${if (st.stop.size > 1) ", each by itself" else ""}$stays"
    }

    /** Said when a name in a stop of several (or a kept one) matches no arm or more than one: nothing is stopped. */
    fun unclearStops(st: Stops, names: List<String>, keep: Boolean): String =
        "I couldn't tell which arm you meant by ${st.unclear.joinToString(" or ") { "\"$it\"" }}, Boss, so nothing was stopped. " +
            (if (keep) "Say the ones to keep running by name or number - " else "Say each one to stop by name or number - ") +
            names.mapIndexed { n, a -> "${n + 1}. $a" }.joinToString("; ") + "."

    /** The one name in [names] that [target] says in full ("ORB" is ORB, never ORB Fresh or ORB Sweep), or null. */
    private fun exact(target: String?, names: List<String>): Int? {
        fun key(x: String) = x.lowercase().replace(rx("[^a-z0-9 ]"), " ").split(rx("\\s+")).filter { it.length > 1 && it !in STOP_WORDS }.joinToString(" ")
        val want = target?.let { key(it) }?.takeIf { it.isNotEmpty() } ?: return null
        return names.indices.filter { key(names[it]) == want }.singleOrNull()
    }

    private fun one(kind: Command.Kind, what: String): Command {
        val n = rx("^(?:number |no |#)?(\\d{1,2})$").find(what)?.groupValues?.get(1)?.toIntOrNull()
            ?: rx("^(one|two|three|four|five|six|seven|eight|nine|ten)$").find(what)?.groupValues?.get(1)
                ?.let { listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten").indexOf(it) + 1 }
        return Command(kind, number = n, target = if (n == null) what else null)
    }

    /** The words after [after] (a name to match), or null. */
    private fun rest(s: String, after: String): String? = s.substringAfter(after, "").trim().takeIf { it.isNotEmpty() && !rx("^\\d+$").matches(it) }

    /**
     * The thing named by [c] among [names] (1-based numbers as Jarvis lists them, or the best name match), or null.
     * Among option positions ("paper NIFTY25O2124500PE (75)"), "put" / "call" (or "pe" / "ce") is a must: only a
     * position of that right can be picked, never the other; a strike said ("24500") matches inside the symbol. Two
     * equally good matches (or "put" and "call" both said): null, so Jarvis asks which.
     */
    fun pick(c: Command, names: List<String>): Int? {
        c.number?.let { return (it - 1).takeIf { i -> i in names.indices } }
        val want = c.target?.lowercase()?.replace(rx("[^a-z0-9 ]"), " ")?.split(rx("\\s+"))?.filter { it.length > 1 && it !in STOP_WORDS } ?: return null
        if (want.isEmpty()) return null
        // A name said in full wins over the names it begins ("stop ORB" is ORB, not ORB Fresh or ORB Sweep: Boss, 6 Oct).
        exact(c.target, names)?.let { return it }
        val toks = names.map { n -> n.lowercase().replace(rx("[^a-z0-9 ]"), " ").split(rx("\\s+")).filter { it.isNotEmpty() }.toSet() }
        val rights = toks.map { w -> w.firstNotNullOfOrNull { x -> OPTION_RIGHT.find(x)?.groupValues?.get(1) } }
        val saysPut = want.any { it in PUT_WORDS }
        val saysCall = want.any { it in CALL_WORDS }
        // Options among the names: the right said is required (both said: ask).
        val options = rights.any { it != null }
        if (options && saysPut && saysCall) return null
        val right = if (!options) null else if (saysPut) "pe" else if (saysCall) "ce" else null
        val scored = names.indices.map { i ->
            val w = toks[i]
            if (right != null && rights[i] != right) return@map i to 0
            i to want.count {
                (right != null && (it in PUT_WORDS || it in CALL_WORDS)) ||
                    it in w || w.any { x -> x.startsWith(it) } ||
                    // A strike (or a strike with its right, "24500pe") inside the symbol token.
                    (it.length >= 3 && it.any(Char::isDigit) && w.any { x -> x.length > it.length && x.contains(it) })
            }
        }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return null
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    private val STOP_WORDS = setOf("the", "my", "strategy", "arm", "bot", "script", "on", "of", "for", "and")
    private val PUT_WORDS = setOf("put", "puts", "pe")
    private val CALL_WORDS = setOf("call", "calls", "ce")
    /** An option symbol token's right: "nifty25o2124500pe" -> "pe". */
    private val OPTION_RIGHT = rx("^[a-z]+\\d[a-z0-9]*\\d(pe|ce)$")

    /** The command in a few words, for the confirm button and the reply. */
    fun describe(c: Command, name: String? = null): String = when (c.kind) {
        Command.Kind.STOP_ALL -> "stop every strategy and arm for today (and close what they hold)"
        Command.Kind.START_ALL -> "start every strategy and arm (and let them trade again today)"
        Command.Kind.STOP_ONE -> name?.let { "stop $it" } ?: when {
            c.keep -> "stop every strategy and arm that is on except ${c.targets.joinToString(" and ")}, each by itself"
            c.targets.isNotEmpty() -> "stop ${c.targets.joinToString(" and ")}, each by itself"
            else -> "stop that strategy"
        }
        Command.Kind.START_ONE -> "start ${name ?: "that strategy"}"
        Command.Kind.CANCEL_ALL -> "cancel every open order"
        Command.Kind.CANCEL_ONE -> "cancel ${name ?: "that order"}"
        Command.Kind.CLOSE_ALL -> "close every open position"
        Command.Kind.CLOSE_ONE -> "close ${name ?: "that position"}"
        Command.Kind.KILL_ON -> "turn the kill switch on (no new live positions)"
        Command.Kind.KILL_OFF -> "turn the kill switch off"
        Command.Kind.MODE_PAPER -> "switch to Paper mode"
        Command.Kind.MODE_LIVE -> "switch to Live mode (real Zerodha orders)"
        Command.Kind.ALARM_ADD -> "set an alarm: ${c.market?.label ?: "?"} ${if (c.above == false) "below" else "above"} " +
            (c.level?.let { "%,.2f".format(java.util.Locale.ENGLISH, it) } ?: c.pct?.let { "a ${if (c.above == false) "fall" else "rise"} of $it% from here" } ?: "?")
        Command.Kind.ALARM_REMOVE -> "remove ${name ?: "that alarm"}"
        Command.Kind.AUTOPILOT_ON -> "turn the autopilot on (Jarvis adds the strategies that pass two years of testing, on paper, and retires its own that stop working)"
        Command.Kind.AUTOPILOT_OFF -> "turn the autopilot off"
        Command.Kind.JTRADES_PAPER -> "keep my suggested trades on paper"
        Command.Kind.JTRADES_LIVE -> "let my suggested trades go live (in the app's mode, real Zerodha orders in Live)"
        Command.Kind.JTRADES_RISK -> c.level?.let { "risk about Rs %,.0f on each of my trades (once proven)".format(java.util.Locale.ENGLISH, it) } ?: "take 1 lot on each of my trades"
        Command.Kind.JTRADES_LIMIT -> "set my trades' daily loss limit to ${c.level?.let { "Rs %,.0f".format(java.util.Locale.ENGLISH, it) } ?: "?"}"
        Command.Kind.EVENT_ADD -> "note the event \"${c.target}\" on ${c.day ?: "?"}"
        Command.Kind.EVENT_REMOVE -> "remove ${name ?: "that event"}"
        Command.Kind.MUTE -> "stop speaking (replies on screen only)"
        Command.Kind.UNMUTE -> "speak again"
        Command.Kind.HINDI -> "reply in Hindi"
        Command.Kind.ENGLISH -> "reply in English"
        Command.Kind.SET_LIMIT -> c.target?.let { k -> runCatching { SettingsTalk.Key.valueOf(k) }.getOrNull() }
            ?.let { SettingsTalk.describe(it, null, c.level ?: 0.0) } ?: "change a setting"
        Command.Kind.SET_REFUSED -> "change a security setting"
        Command.Kind.UNDO -> "undo the last limit change"
        Command.Kind.QUIET_ON -> "turn quiet hours on (nothing said unasked from 22:00 to 07:00)"
        Command.Kind.QUIET_OFF -> "turn quiet hours off"
        Command.Kind.PREF_RESET -> "offer every kind of suggestion again"
        Command.Kind.TARGET_SET -> "set today's target to ${c.level?.let { AppFacts.amt(it) } ?: "?"}"
        Command.Kind.TARGET_CLEAR -> "clear today's target"
        Command.Kind.NOTE -> "note why you took the trade"
        Command.Kind.MISTAKE -> "note that my last answer was wrong"
        Command.Kind.EXIT_ALL -> "exit everything: close every open position, turn the kill switch on and stop every strategy and arm for today"
        Command.Kind.BRIEF_ON -> "give short spoken answers"
        Command.Kind.BRIEF_OFF -> "give full spoken answers"
        Command.Kind.MORE -> "say the full last answer"
        Command.Kind.PRACTICE -> "replay a past day"
        Command.Kind.LEARN_RESET -> "forget what I learned from your corrections"
        Command.Kind.VOICE_CHECK -> "check why my voice is not heard"
        Command.Kind.PACE_SLOWER -> "speak slower"
        Command.Kind.PACE_FASTER -> "speak faster"
        Command.Kind.PACE_NORMAL -> "speak at the normal pace"
        Command.Kind.MUTE_FOR -> "stay quiet for ${c.number} minutes"
        Command.Kind.LISTEN_OFF -> "stop listening (the microphone off until you tap Listen again)"
        Command.Kind.JTRADES_WEEKLY -> "set my trades' weekly loss limit to ${c.level?.let { "Rs %,.0f".format(java.util.Locale.ENGLISH, it) } ?: "?"}"
    }
}
