package com.optionslab.ira

import com.optionslab.ira.Command.Kind

/**
 * Everything Jarvis can do in the app, in one place (Boss, 4 Oct, part 2 of "an AGI for this app"): each action with
 * the area it belongs to and what it needs from Boss - nothing, his Confirm, his fingerprint, or never Jarvis at all.
 * Plans show this for every step, and "what can you do" reads from it. Pure.
 */
object Toolbox {
    enum class Need(val label: String) {
        READ("just answered"),
        CONFIRM("asks you first"),
        LOWERS("lowers risk; asks you first"),
        FINGERPRINT("asks for your fingerprint"),
        NEVER("never by me: yours alone, in the app"),
    }

    data class Tool(val name: String, val area: String, val need: Need)

    /** Each command Jarvis knows. */
    fun of(k: Kind): Tool = when (k) {
        Kind.STOP_ALL -> Tool("stop all strategies and arms", "Strategies", Need.LOWERS)
        Kind.START_ALL -> Tool("start all strategies and arms", "Strategies", Need.CONFIRM)
        Kind.STOP_ONE -> Tool("stop one strategy or arm", "Strategies", Need.LOWERS)
        Kind.START_ONE -> Tool("start one strategy or arm", "Strategies", Need.CONFIRM)
        Kind.AUTOPILOT_ON -> Tool("autopilot on", "Strategies", Need.CONFIRM)
        Kind.AUTOPILOT_OFF -> Tool("autopilot off", "Strategies", Need.LOWERS)
        Kind.CANCEL_ALL -> Tool("cancel all open orders", "Orders", Need.LOWERS)
        Kind.CANCEL_ONE -> Tool("cancel one order", "Orders", Need.LOWERS)
        Kind.CLOSE_ALL -> Tool("close all positions", "Positions", Need.LOWERS)
        Kind.CLOSE_ONE -> Tool("close one position", "Positions", Need.LOWERS)
        Kind.EXIT_ALL -> Tool("emergency exit (close all, kill switch on, bots stopped)", "Positions", Need.FINGERPRINT)
        Kind.KILL_ON -> Tool("kill switch on", "Safety", Need.LOWERS)
        Kind.KILL_OFF -> Tool("kill switch off", "Safety", Need.CONFIRM)
        Kind.MODE_PAPER -> Tool("switch the app to paper", "Safety", Need.LOWERS)
        Kind.MODE_LIVE -> Tool("switch the app to live", "Safety", Need.NEVER)
        Kind.SET_LIMIT, Kind.SET_REFUSED -> Tool("change a risk limit", "Safety", Need.CONFIRM)
        Kind.UNDO -> Tool("undo the last limit change", "Safety", Need.CONFIRM)
        Kind.ALARM_ADD -> Tool("set a price alarm", "Alerts", Need.READ)
        Kind.ALARM_REMOVE -> Tool("remove a price alarm", "Alerts", Need.CONFIRM)
        Kind.EVENT_ADD -> Tool("note an event", "Alerts", Need.READ)
        Kind.EVENT_REMOVE -> Tool("remove an event", "Alerts", Need.CONFIRM)
        Kind.JTRADES_PAPER -> Tool("keep my own trades on paper", "My trades", Need.LOWERS)
        Kind.JTRADES_LIVE -> Tool("let AI trades go live", "My trades", Need.NEVER)
        Kind.JTRADES_LIMIT, Kind.JTRADES_WEEKLY, Kind.JTRADES_RISK -> Tool("my own trades' loss limits and risk", "My trades", Need.CONFIRM)
        Kind.MUTE, Kind.UNMUTE, Kind.HINDI, Kind.ENGLISH, Kind.BRIEF_ON, Kind.BRIEF_OFF, Kind.MORE, Kind.VOICE_CHECK,
        Kind.PACE_SLOWER, Kind.PACE_FASTER, Kind.PACE_NORMAL, Kind.MUTE_FOR ->
            Tool("my voice: mute, language, short answers, voice check", "Voice", Need.CONFIRM)
        Kind.QUIET_ON, Kind.QUIET_OFF -> Tool("quiet hours", "Voice", Need.CONFIRM)
        Kind.TARGET_SET, Kind.TARGET_CLEAR -> Tool("your day's target", "Journal", Need.CONFIRM)
        Kind.NOTE -> Tool("note why you took a trade", "Journal", Need.CONFIRM)
        Kind.MISTAKE -> Tool("mark my answer wrong", "Learning", Need.CONFIRM)
        Kind.PREF_RESET, Kind.LEARN_RESET -> Tool("forget what I learned from you", "Learning", Need.CONFIRM)
        Kind.PRACTICE -> Tool("replay a past day for practice", "Learning", Need.READ)
    }

    /** What Jarvis answers without doing anything (the reading tools). */
    val READS = listOf(
        "market: prices, trend, levels, pivots, patterns, VIX, outlook for the hour, day or week",
        "options: the chain, PCR, open interest walls, IV rank",
        "news and events, FII / DII",
        "your account: positions, orders, P&L by day, funds and margin, risk limits",
        "strategies and arms: status, records, backtests",
        "my own and Solo's records, the scorecard and the weekly report card",
    )

    /** Is [text] a question Jarvis just answers (no command, no order)? */
    fun isRead(text: String): Boolean {
        val q = Ask.parse(text)
        if (q.command != null || q.order != null) return false
        return q.topics.any { it in setOf(Topic.OVERVIEW, Topic.TREND, Topic.LEVELS, Topic.PATTERNS, Topic.NEWS, Topic.VOLATILITY, Topic.ACCOUNT, Topic.TRADE_CHECK) }
    }

    /** What a step needs, in words (a command's, or "just answered" for a question). */
    fun needOf(step: String): Need = Ask.parse(step).command?.let { of(it.kind).need } ?: Need.READ

    /** "What can you do": the areas, and what each kind of action needs. */
    fun say(): String {
        val byNeed = Kind.entries.map { of(it) }.distinctBy { it.name }.groupBy { it.need }
        fun list(n: Need) = byNeed[n].orEmpty().joinToString(", ") { it.name }
        return "I read: " + READS.joinToString("; ") + ". " +
            "I do, asking you first: " + list(Need.CONFIRM) + ". " +
            "I lower risk, asking first: " + list(Need.LOWERS) + ". " +
            "With your fingerprint: " + list(Need.FINGERPRINT) + ". " +
            "Never by me: " + list(Need.NEVER) + ". " +
            "Orders I only prepare for the app's review. Say several in one go (\"stop all strategies, then kill switch on, then tell me my positions\") and I make it a plan."
    }
}
