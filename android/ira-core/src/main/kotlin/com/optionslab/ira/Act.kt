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
                   /** An alarm by a move from the price now, in percent ([Kind.ALARM_ADD] without a level). */ val pct: Double? = null) {
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
    }
}

object Commands {
    /** A question about doing something ("how do I stop...") is not a command. */
    private val QUESTION = Regex("^ (how|where|what|whats|why|which|when|should|is|are|am|can i|could i|did|do you|do i|does|has|have|will|was|were|would|if|wonder|i wonder) | tell me (whether|what|why|how) | is it [a-z0-9]* $| right $| or not $| (hua|hai|tha|hoga) kya $| kya (hua|hai) $")
    /** "Don't switch to live", "never start...": a negation is never a command. */
    private val NEGATION = Regex(" (don t|dont|do not|never|not|doesn t|didn t|won t) ")
    /** Commands a misspelt word may never become (only what the owner typed correctly). */
    private val NEVER_FROM_TYPO = setOf(Command.Kind.MODE_LIVE, Command.Kind.KILL_OFF, Command.Kind.JTRADES_LIVE, Command.Kind.AUTOPILOT_ON)
    private val ARM_NOUN = "(?:the )?(?:strategy|strategies|arm|arms|bot|bots|algo|script)?"

    /**
     * [said] as typed; with a misspelt word ("start all statergies") read again with it fixed ([Spelling]) when that
     * finds a command and the words as typed found none, or only a name to look for.
     */
    fun parse(said: String): Command? {
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
        Regex("(?i)^\\s*(?:(?:hey |ok |okay )?jarvis[,.!]?\\s+)?(?:note|journal)(?: that| down)?\\s*[:,-]?\\s+(.{3,300})$").find(said.trim())?.let { m ->
            return Command(Command.Kind.NOTE, target = m.groupValues[1].trim())
        }
        // A question ("is live mode on?") is never a command.
        if (said.trim().endsWith("?")) return null
        val text = Hinglish.normalize(said)
        // "25,000" is one number; a full stop ends a sentence (but "52.5" keeps its point).
        val t = " " + text.lowercase().replace("%", " percent ").replace(Regex("(\\d),(?=\\d{3})"), "$1").replace(Regex("\\.(?!\\d)"), " ")
            .replace(Regex("[^a-z0-9. ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        val s = t.replace(Regex(" (please|jarvis|hey|ok|okay|now|right now|immediately|can you|could you|will you|for me) "), " ")
            .replace(Regex("\\s+"), " ").let { " ${it.trim()} " }
        // "That was wrong": kept with what was said and answered (before the negation check: "not what I asked").
        if (Regex("^ (that was wrong|that s wrong|thats wrong|that is wrong|wrong answer|you got (that|it) wrong|that s not right|thats not right|not what i asked|you misheard( me)?|you misunderstood( me)?) $").containsMatchIn(s))
            return Command(Command.Kind.MISTAKE)
        // Jarvis's voice (before the negation check: "don't speak" is a mute).
        if (Regex("^ (un ?mute|unmute yourself|speak again|talk again|voice on|turn (on )?(your )?voice( on)?|you can (speak|talk)( now| again)?|start (speaking|talking)) $").containsMatchIn(s)) return Command(Command.Kind.UNMUTE)
        // A mute needs clear words (a stray "quiet" or "silence" nearby is not one).
        if (Regex("^ ((be |go |stay |keep )?(mute|muted)|be quiet|mute (yourself|your voice|the voice)|(be|go|stay|keep) (on )?silent|(don t|do not|stop) (speak|speaking|talk|talking)|voice off|turn (off )?(your )?voice( off)?) $").containsMatchIn(s)) return Command(Command.Kind.MUTE)
        if (Regex("^ (why can t i hear you|why can i not hear you|i can t hear you|cant hear you|no voice|voice check|check (your|the) voice|why (are you|is your voice) (silent|not speaking|quiet)|why no voice) $").containsMatchIn(s)) return Command(Command.Kind.VOICE_CHECK)
        if (Regex("^ (reply|answer|speak|talk|respond)( to me)? in hindi $|^ hindi (mein|me) (bolo|baat karo|jawab do) $|^ hindi (replies|mode)( on)? $").containsMatchIn(s)) return Command(Command.Kind.HINDI)
        if (Regex("^ (reply|answer|speak|talk|respond)( to me)? in english( again)? $|^ english (replies|mode)( on)? $").containsMatchIn(s)) return Command(Command.Kind.ENGLISH)
        if (QUESTION.containsMatchIn(s) || NEGATION.containsMatchIn(s)) return null
        fun has(r: String) = Regex(r).containsMatchIn(s)
        fun num(r: String) = Regex(r).find(s)?.groupValues?.get(1)?.toIntOrNull()

        if (Regex("^ (undo|undo (that|it|the last change|my last change|last change|the change)|revert( that| it| the last change)?|put (it|that) back|change (it|that) back) $").containsMatchIn(s)) return Command(Command.Kind.UNDO)
        // The day's target: "my target today is 3000", "set my daily target to 5k", "clear my target".
        if (Regex(" (clear|remove|cancel|delete|drop) (my |the |today s )?(daily |day s |day )?target ").containsMatchIn(s)) return Command(Command.Kind.TARGET_CLEAR)
        Regex(" target (\\d+(?:\\.\\d+)?) ?(k|thousand|lakh)? (?:today|for today|for the day) ").find(s)?.let { m ->
            val v = m.groupValues[1].toDouble() * when (m.groupValues[2]) { "k", "thousand" -> 1_000.0; "lakh" -> 100_000.0; else -> 1.0 }
            if (v >= 100) return Command(Command.Kind.TARGET_SET, level = v)
        }
        Regex(" (?:my (?:daily |day s |day )?target(?: for today| for the day| today)?|(?:daily|day s|today s) target|target (?:for today|for the day|today)) (?:is |to |at |of |=|be )?(?:rs |rupees )?(\\d+(?:\\.\\d+)?) ?(k|thousand|lakh)?(?: rupees)? ").find(s)?.let { m ->
            val v = m.groupValues[1].toDouble() * when (m.groupValues[2]) { "k", "thousand" -> 1_000.0; "lakh" -> 100_000.0; else -> 1.0 }
            if (v >= 100) return Command(Command.Kind.TARGET_SET, level = v)
        }
        if (Regex("^ (reset|clear|forget) (my |your )?(preferences|suggestion preferences|what i reject(ed)?) $").containsMatchIn(s)) return Command(Command.Kind.PREF_RESET)
        if (Regex("^ (turn|switch) (on|off) (the )?quiet hours | quiet hours (on|off) |^ (enable|disable) (the )?quiet hours ").containsMatchIn(s))
            return Command(if (Regex(" (off|disable) ").containsMatchIn(s.replace(" quiet hours ", " "))) Command.Kind.QUIET_OFF else Command.Kind.QUIET_ON)
        // "Tell me if BankNifty falls 1% from here": an alarm at a level worked out from the price now.
        if (has(" (alert|alarm|notify|tell|ping|wake|warn) ")) MoveAlarm.read(s)?.let { mv ->
            return Command(Command.Kind.ALARM_ADD, market = Market.mentioned(s).firstOrNull(), above = mv.up, pct = mv.pct)
        }
        // The emergency exit: everything closed, the kill switch on, the bots stopped.
        if (Regex("^ (emergency exit|exit everything|panic( exit| button)?|close everything and stop|get me out( of everything)?|exit all( now)?|square off everything and stop) $").containsMatchIn(s))
            return Command(Command.Kind.EXIT_ALL)
        if (Regex("^ (forget|reset|clear) (what you (have )?learned|your learning|what you learnt|the corrections) $").containsMatchIn(s)) return Command(Command.Kind.LEARN_RESET)
        if (Regex("^ (brief mode( on)?|short answers( please)?|keep it short|be brief|shorter answers) $").containsMatchIn(s)) return Command(Command.Kind.BRIEF_ON)
        if (Regex("^ (brief mode off|full answers|detailed answers|long answers|answer in full) $").containsMatchIn(s)) return Command(Command.Kind.BRIEF_OFF)
        if (Regex("^ (tell me more|more|more details|go on|details|explain more|the full answer|repeat|repeat that|repeat it|say that again|say it again|say again|come again|pardon|sorry what|what did you say|once more|one more time) $").containsMatchIn(s)) return Command(Command.Kind.MORE)
        if (Practice.asked(s) && Regex("^ (practi[cs]e|replay|simulate|rehearse) ").containsMatchIn(s)) return Command(Command.Kind.PRACTICE, target = text)
        Regex(" (?:your|jarvis s|jarvis) (?:own )?(?:trades? )?weekly loss limit (?:to |at |of )?(?:rs |rupees )?(\\d{3,7}) ").find(t)?.let { m ->
            return Command(Command.Kind.JTRADES_WEEKLY, level = m.groupValues[1].toDouble())
        }
        // The app's limits ("set max lots to 3"); never the PIN, real orders or the lock.
        if (SettingsTalk.forbidden(s)) return Command(Command.Kind.SET_REFUSED)
        if (!Regex(" (your|jarvis s) | jarvis (own )?(trades? )?(daily )?(loss limit|risk) ").containsMatchIn(t)) SettingsTalk.parse(s)?.let { return it }
        // Jarvis's own trades (before Live / Paper mode: "let your trades go live" is not the app's mode).
        if (has(" (your|jarvis s|jarvis) (own )?trades? (go |to |on )?live | (let|put|send|switch|move|take) (your|jarvis s) (own )?trades? (go )?(to )?live ")) return Command(Command.Kind.JTRADES_LIVE)
        if (has(" (your|jarvis s) (own )?trades? (on|to|back to|go to) paper | keep (your|jarvis s) (own )?trades? (on )?paper ")) return Command(Command.Kind.JTRADES_PAPER)
        if (Regex(" (?:your|jarvis s|jarvis) (?:own )?(?:trades? )?risk (?:per trade )?(?:off|none|zero) ").containsMatchIn(t)) return Command(Command.Kind.JTRADES_RISK)
        Regex(" (?:your|jarvis s|jarvis) (?:own )?(?:trades? )?risk (?:per trade )?(?:to |at |of )?(?:rs |rupees )?(\\d{3,7}) ").find(t)?.let { m ->
            return Command(Command.Kind.JTRADES_RISK, level = m.groupValues[1].toDouble())
        }
        Regex(" (?:your|jarvis s|jarvis) (?:own )?(?:trades? )?(?:daily )?loss limit (?:to |at |of )?(?:rs |rupees )?(\\d{3,7}) ").find(t)?.let { m ->
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
        Regex(" (add|note|remember|mark) (an |the )?event (.+) (on|for) (.+?) $").find(s)?.let { m ->
            return Command(Command.Kind.EVENT_ADD, target = m.groupValues[3].trim(), day = Events.date(m.groupValues[5], java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))))
        }
        if (has(" (remove|delete|cancel|clear) (the |my )?event")) return Command(Command.Kind.EVENT_REMOVE, number = num(" event (\\d+) "), target = rest(s, " event "))
        // Alarms: "alert me when nifty goes above 25000", "set an alarm on banknifty below 51000", "remove alarm 2".
        if (has(" (remove|delete|cancel|clear) (the |my |all |all my |all the )?(last )?(alarm|alert)s? ")) {
            return Command(Command.Kind.ALARM_REMOVE, number = num(" (?:alarm|alert) (\\d+) "),
                target = if (has(" last ")) "last" else if (has(" all ")) "all" else null)
        }
        // An alarm needs its level: the number after above / below / reaches ("... above 25000 in 15 minutes" is 25000).
        // ("Remind me when Nifty hits 25000", "set alarm Nifty 24500", "Nifty 24500 pe alert lagao": the level is the
        // number; with no direction word the direction is asked.)
        val setAlarm = has(" set (an |a )?(alarm|alert) | (alarm|alert) (lagao|laga do|set karo|set kar do) | batana | bata dena ")
        val lvl = Regex(" (?:above|below|over|under|crosses|crossing|cross|rises to|falls to|drops to|reaches|hits|hit|touches|touch|at|to) (\\d{2,6}(?:\\.\\d+)?) ").find(s)?.groupValues?.get(1)?.toDouble()
            ?: if (setAlarm) Regex(" (\\d{4,6}(?:\\.\\d+)?) ").find(s)?.groupValues?.get(1)?.toDouble() else null
        if (lvl != null && (has(" (alert|alarm|notify|tell|ping|wake|remind) ") || setAlarm) &&
            (setAlarm || has(" (above|below|over|under|crosses|crossing|cross|rises|falls|drops|goes|reaches|hits|hit|touches|touch|at) "))) {
            val m = Market.mentioned(s).firstOrNull()
            val above = when { has(" (below|under|falls|drops|down to) ") -> false; has(" (above|over|rises|crosses|up to|reaches) ") -> true; else -> null }
            return Command(Command.Kind.ALARM_ADD, market = m, above = above, level = lvl)
        }
        // Orders and positions.
        if (has(" (cancel|stop|kill|remove) (all|every|each)( the| my)?( open| pending| working)? orders? | cancel (my |the )?(open |pending )?orders ")) return Command(Command.Kind.CANCEL_ALL)
        if (has(" cancel (the |my )?(last |latest )?order")) {
            return Command(Command.Kind.CANCEL_ONE, number = num(" order (\\d+) "), target = if (has(" (last|latest) ")) "last" else rest(s, " order "))
        }
        if (has(" (square off|squareoff|close|exit|sell off) (all|everything|every position|all positions|all my positions|my positions|the positions)( |$)")) return Command(Command.Kind.CLOSE_ALL)
        if (has(" (square off|squareoff|close|exit) (the |my )?position")) {
            return Command(Command.Kind.CLOSE_ONE, number = num(" position (\\d+) "), target = rest(s, " position "))
        }
        // "Close the Nifty position", "exit 24500 CE", "sell my BankNifty call", "book profit in Nifty": one position, by its
        // words (a new sell is "sell" without "my"/"the" - never read as a close).
        Regex("^ (?:square off|squareoff|close|exit|sell (?:my|the)|book (?:my |the )?profits? (?:in|on)) (?:the |my )?((?:[a-z0-9]+ ){0,3}?)(position|trade|call|put|ce|pe|option)s?( |$)").find(s)?.let { m ->
            val kind = m.groupValues[2].takeIf { it !in setOf("position", "trade", "option") }
            val words = listOfNotNull(m.groupValues[1].trim().takeIf { it.isNotEmpty() }, kind).joinToString(" ")
            return Command(Command.Kind.CLOSE_ONE, target = words.ifEmpty { null })
        }
        if (Regex("^ book (?:my |the )?profits? $").containsMatchIn(s)) return Command(Command.Kind.CLOSE_ONE)
        Regex("^ book (?:my |the )?profits? (?:in|on) (?:the |my )?(.+?) $").find(s)?.let { return Command(Command.Kind.CLOSE_ONE, target = it.groupValues[1].trim()) }
        // The bots: everything at once, or one strategy / arm by its number or name.
        if (has(" (stop|halt|pause|disarm|switch off|turn off) (all|every|everything)( the| my)?( strategies| arms| bots| algos| scripts| trading)? | stop trading | stop (the |my )?(bots|algos|arms|strategies) ")) return Command(Command.Kind.STOP_ALL)
        if (has(" (start|resume|restart) (all |the |my )?(bots|arms|strategies|algos|trading) again | (resume|restart) (all|trading|the bots|everything) | start trading again ") ||
            has(" (start|arm|switch on|turn on|run|enable|resume) (all|every)( the| my)? (strategies|strategy|arms|arm|bots|algos|scripts) | (start|arm|switch on|turn on|run|enable|resume) (everything|all) $| (start|arm|switch on|turn on|run|enable) (the |my )?(strategies|arms|bots|algos) $"))
            return Command(Command.Kind.START_ALL)
        // One strategy or arm: the verb comes first ("stop strategy 2"), and settings are never read as a name.
        val notArm = Regex("kill switch|\\blive\\b|paper|\\bmode\\b|alert|alarm|autopilot|listening|^trading$|^(it|that|this|jarvis|everything)$|voice|notifications?|\\bloss\\b|talking|speaking")
        Regex("^ (stop|disarm|switch off|turn off|pause|halt) $ARM_NOUN ?(.+)$").find(s)?.let { m ->
            val what = m.groupValues[2].trim()
            if (what.isNotEmpty() && !notArm.containsMatchIn(what)) return one(Command.Kind.STOP_ONE, what)
        }
        Regex("^ (start|arm|switch on|turn on|resume|run|enable) $ARM_NOUN ?(.+)$").find(s)?.let { m ->
            val what = m.groupValues[2].trim()
            if (what.isNotEmpty() && !notArm.containsMatchIn(what)) return one(Command.Kind.START_ONE, what)
        }
        return null
    }

    private fun one(kind: Command.Kind, what: String): Command {
        val n = Regex("^(?:number |no |#)?(\\d{1,2})$").find(what)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("^(one|two|three|four|five|six|seven|eight|nine|ten)$").find(what)?.groupValues?.get(1)
                ?.let { listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten").indexOf(it) + 1 }
        return Command(kind, number = n, target = if (n == null) what else null)
    }

    /** The words after [after] (a name to match), or null. */
    private fun rest(s: String, after: String): String? = s.substringAfter(after, "").trim().takeIf { it.isNotEmpty() && !Regex("^\\d+$").matches(it) }

    /** The thing named by [c] among [names] (1-based numbers as Jarvis lists them, or the best name match), or null. */
    fun pick(c: Command, names: List<String>): Int? {
        c.number?.let { return (it - 1).takeIf { i -> i in names.indices } }
        val want = c.target?.lowercase()?.replace(Regex("[^a-z0-9 ]"), " ")?.split(Regex("\\s+"))?.filter { it.length > 1 && it !in STOP_WORDS } ?: return null
        if (want.isEmpty()) return null
        val scored = names.mapIndexed { i, n ->
            val w = n.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).toSet()
            i to want.count { it in w || w.any { x -> x.startsWith(it) } }
        }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return null
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    private val STOP_WORDS = setOf("the", "my", "strategy", "arm", "bot", "script", "on", "of", "for", "and")

    /** The command in a few words, for the confirm button and the reply. */
    fun describe(c: Command, name: String? = null): String = when (c.kind) {
        Command.Kind.STOP_ALL -> "stop every strategy and arm for today (and close what they hold)"
        Command.Kind.START_ALL -> "start every strategy and arm (and let them trade again today)"
        Command.Kind.STOP_ONE -> "stop ${name ?: "that strategy"}"
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
        Command.Kind.JTRADES_WEEKLY -> "set my trades' weekly loss limit to ${c.level?.let { "Rs %,.0f".format(java.util.Locale.ENGLISH, it) } ?: "?"}"
    }
}
