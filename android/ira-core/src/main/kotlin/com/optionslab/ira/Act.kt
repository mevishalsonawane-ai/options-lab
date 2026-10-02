package com.optionslab.ira

/**
 * Something the owner asks Jarvis to DO in the app (not an order to buy or sell - that is [OrderRequest]). The owner's
 * rule (2026-10-02): what adds risk (start, arm, kill switch off, Live mode, an alarm) is done at once; what stops or
 * closes ([Kind.reduces]) waits for one tap on Confirm. Jarvis only uses the app's own controls, and the app's limits
 * still apply. Pure: words in, the command out.
 */
data class Command(val kind: Kind, val target: String? = null, val number: Int? = null,
                   val market: Market? = null, val above: Boolean? = null, val level: Double? = null,
                   /** An event's day ([Kind.EVENT_ADD]). */ val day: java.time.LocalDate? = null) {
    enum class Kind(val reduces: Boolean) {
        STOP_ALL(true), START_ALL(false), STOP_ONE(true), START_ONE(false),
        CANCEL_ALL(true), CANCEL_ONE(true), CLOSE_ALL(true), CLOSE_ONE(true),
        KILL_ON(true), KILL_OFF(false), MODE_PAPER(true), MODE_LIVE(false),
        ALARM_ADD(false), ALARM_REMOVE(true), EVENT_ADD(false), EVENT_REMOVE(true), AUTOPILOT_ON(false), AUTOPILOT_OFF(true),
        /** Jarvis's own suggested trades: on paper (the default until proven) or in the app's mode; their daily loss limit. */
        JTRADES_PAPER(true), JTRADES_LIVE(false), JTRADES_LIMIT(false), JTRADES_RISK(false),
    }
}

object Commands {
    /** A question about doing something ("how do I stop...") is not a command. */
    private val QUESTION = Regex("^ (how|where|what|why|which|when|should|is there|are there|do i|does) ")
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
            if (f != null && (raw == null || raw.number == null && raw.target != null && f.kind != raw.kind)) return f
        }
        return raw
    }

    private fun parseAs(said: String): Command? {
        val text = Hinglish.normalize(said)
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9. ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (QUESTION.containsMatchIn(t)) return null
        val s = t.replace(Regex(" (please|jarvis|now|right now|immediately|can you|could you|will you|for me) "), " ")
            .replace(Regex("\\s+"), " ").let { " ${it.trim()} " }
        fun has(r: String) = Regex(r).containsMatchIn(s)
        fun num(r: String) = Regex(r).find(s)?.groupValues?.get(1)?.toIntOrNull()

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
            has(" (turn|switch) off (the )?kill switch | (turn|switch) (the )?kill switch off | (deactivate|disable|release|clear) (the )?kill switch | kill switch off ") -> return Command(Command.Kind.KILL_OFF)
            has(" (switch|go|change|move) (to |back to )?live( mode| trading)? | live mode on | (start|use) live (mode|trading) ") -> return Command(Command.Kind.MODE_LIVE)
            has(" (switch|go|change|move) (to |back to )?paper( mode| trading)? | paper mode on | (start|use) paper (mode|trading) ") -> return Command(Command.Kind.MODE_PAPER)
        }
        if (has(" (turn|switch) on (the )?autopilot | (enable|start) (the )?autopilot | autopilot on ")) return Command(Command.Kind.AUTOPILOT_ON)
        if (has(" (turn|switch) off (the )?autopilot | (disable|stop) (the )?autopilot | autopilot off ")) return Command(Command.Kind.AUTOPILOT_OFF)
        // Events: "add event RBI policy on 5 Dec", "remove event 2".
        Regex(" (add|note|remember|mark) (an |the )?event (.+?) (on|for) (.+) $").find(s)?.let { m ->
            return Command(Command.Kind.EVENT_ADD, target = m.groupValues[3].trim(), day = Events.date(m.groupValues[5], java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))))
        }
        if (has(" (remove|delete|cancel|clear) (the |my )?event")) return Command(Command.Kind.EVENT_REMOVE, number = num(" event (\\d+) "), target = rest(s, " event "))
        // Alarms: "alert me when nifty goes above 25000", "set an alarm on banknifty below 51000", "remove alarm 2".
        if (has(" (remove|delete|cancel|clear) (the |my )?(last )?(alarm|alert)s? ")) {
            return Command(Command.Kind.ALARM_REMOVE, number = num(" (?:alarm|alert) (\\d+) "), target = if (has(" last ")) "last" else null)
        }
        if (has(" (alert|alarm|notify|tell|ping|wake) ") && has(" (above|below|over|under|crosses|rises|falls|drops|goes|reaches) ")) {
            val m = Market.mentioned(s).firstOrNull()
            val lvl = Regex(" (\\d{2,6}(?:\\.\\d+)?) ").findAll(s).map { it.groupValues[1].toDouble() }.lastOrNull()
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
        // The bots: everything at once, or one strategy / arm by its number or name.
        if (has(" (stop|halt|pause|disarm|switch off|turn off) (all|every|everything)( the| my)?( strategies| arms| bots| algos| scripts| trading)? | stop trading | stop (the |my )?(bots|algos|arms|strategies) ")) return Command(Command.Kind.STOP_ALL)
        if (has(" (start|resume|restart) (all |the |my )?(bots|arms|strategies|algos|trading) again | (resume|restart) (all|trading|the bots|everything) | start trading again ") ||
            has(" (start|arm|switch on|turn on|run|enable|resume) (all|every|everything)( the| my)?( strategies| strategy| arms| arm| bots| algos| scripts)? | (start|arm|switch on|turn on|run|enable) (the |my )?(strategies|arms|bots|algos) $"))
            return Command(Command.Kind.START_ALL)
        Regex(" (stop|disarm|switch off|turn off|pause|halt) $ARM_NOUN ?(.+)$").find(s)?.let { m ->
            val what = m.groupValues[2].trim()
            if (what.isNotEmpty() && what != "listening") return one(Command.Kind.STOP_ONE, what)
        }
        Regex(" (start|arm|switch on|turn on|resume|run|enable) $ARM_NOUN ?(.+)$").find(s)?.let { m ->
            val what = m.groupValues[2].trim()
            if (what.isNotEmpty() && !Regex("^(listening|trading)$").matches(what)) return one(Command.Kind.START_ONE, what)
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
        Command.Kind.ALARM_ADD -> "set an alarm: ${c.market?.label ?: "?"} ${if (c.above == false) "below" else "above"} ${c.level?.let { "%,.2f".format(java.util.Locale.ENGLISH, it) } ?: "?"}"
        Command.Kind.ALARM_REMOVE -> "remove ${name ?: "that alarm"}"
        Command.Kind.AUTOPILOT_ON -> "turn the autopilot on (Jarvis adds the strategies that pass two years of testing, on paper, and retires its own that stop working)"
        Command.Kind.AUTOPILOT_OFF -> "turn the autopilot off"
        Command.Kind.JTRADES_PAPER -> "keep my suggested trades on paper"
        Command.Kind.JTRADES_LIVE -> "let my suggested trades go live (in the app's mode, real Zerodha orders in Live)"
        Command.Kind.JTRADES_RISK -> c.level?.let { "risk about Rs %,.0f on each of my trades (once proven)".format(java.util.Locale.ENGLISH, it) } ?: "take 1 lot on each of my trades"
        Command.Kind.JTRADES_LIMIT -> "set my trades' daily loss limit to ${c.level?.let { "Rs %,.0f".format(java.util.Locale.ENGLISH, it) } ?: "?"}"
        Command.Kind.EVENT_ADD -> "note the event \"${c.target}\" on ${c.day ?: "?"}"
        Command.Kind.EVENT_REMOVE -> "remove ${name ?: "that event"}"
    }
}
