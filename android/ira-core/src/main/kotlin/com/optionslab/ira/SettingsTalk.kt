package com.optionslab.ira

import java.util.Locale

/**
 * Changing the app's limits by asking Jarvis (the owner's wish, 2026-10-02): "set max lots to 3", "daily loss limit
 * 5000", "turn off the loss limit", "no new entries after 2:30 pm", "order product MIS". Only the risk limits, the
 * P&L alerts and the order product can be changed this way; the Live switch, real orders, one-tap orders, the PIN,
 * the fingerprint and the lock stay in Settings alone (no backdoor). Every change is said back old -> new and waits
 * for Confirm; one that loosens a limit ([loosens]) needs the owner's own voice. Pure.
 */
object SettingsTalk {
    enum class Key(val label: String, val unit: Unit, /** 0 (or -1 for times) switches it off. */ val canOff: Boolean = true) {
        DAILY_LOSS("the daily loss limit", Unit.RUPEES),
        PAPER_DAILY_LOSS("the paper daily loss limit", Unit.RUPEES, canOff = false),
        MAX_LOTS("max lots per instrument", Unit.COUNT),
        LOTS_PER_ORDER("max lots per order", Unit.COUNT, canOff = false),
        MAX_OPEN("max open positions", Unit.COUNT),
        MAX_TRADES("max orders per day", Unit.COUNT),
        PAPER_TRADES("max paper orders per day", Unit.COUNT, canOff = false),
        ORDERS_PER_DAY("max manual orders per day", Unit.COUNT, canOff = false),
        DRAWDOWN("the max drawdown", Unit.PERCENT),
        ORDER_VALUE("max value per order", Unit.RUPEES),
        EXPOSURE("max held in one instrument", Unit.RUPEES),
        CUTOFF("no new entries after", Unit.TIME),
        LOSS_ALERT("the P&L loss alert", Unit.RUPEES),
        PROFIT_ALERT("the P&L profit alert", Unit.RUPEES),
        PRODUCT("the order product", Unit.PRODUCT, canOff = false),
        EXPIRY_SQUARE_OFF("the expiry-day square-off at 15:05", Unit.SWITCH, canOff = false),
        NAKED_SHORTS("blocking naked option shorts", Unit.SWITCH, canOff = false),
    }

    enum class Unit { RUPEES, COUNT, PERCENT, TIME, PRODUCT, SWITCH }

    /** Words naming each setting, most specific first (so "paper daily loss" is not "daily loss"). */
    private val NAMES: List<Pair<Key, String>> = listOf(
        Key.PAPER_DAILY_LOSS to "paper (daily )?(loss|loss limit|max loss)",
        Key.PAPER_TRADES to "paper (max )?(orders|trades)( per day| a day)?",
        Key.LOSS_ALERT to "(p l |pnl )?loss alert",
        Key.PROFIT_ALERT to "(p l |pnl )?profit alert",
        Key.DAILY_LOSS to "(daily loss( limit)?|max(imum)? (daily )?loss|(?<!stop )loss limit|stop loss limit for the day)",
        Key.LOTS_PER_ORDER to "(max(imum)? )?lots (per|an|each|a) order",
        Key.MAX_LOTS to "max(imum)? lots( per instrument)?|lot limit",
        Key.MAX_OPEN to "max(imum)? (open )?positions|open positions limit",
        Key.ORDERS_PER_DAY to "(max(imum)? )?manual orders( per day| a day)?",
        Key.MAX_TRADES to "max(imum)? (orders|trades)( per day| a day)?|(orders|trades) (per|a) day( limit)?",
        Key.DRAWDOWN to "(max(imum)? )?drawdown( limit)?",
        Key.ORDER_VALUE to "(max(imum)? )?(order value|value per order)",
        Key.EXPOSURE to "(max(imum)? )?(exposure|held in one instrument)",
        Key.CUTOFF to "(entry )?cut ?off( time)?|no new (entries|positions|trades) after|last entry( time)?",
        Key.PRODUCT to "(order )?product",
        Key.EXPIRY_SQUARE_OFF to "expiry( day)? square ?off",
        Key.NAKED_SHORTS to "naked (option )?shorts?( block| blocking)?|block(ing)? naked (option )?shorts?",
    )

    /** Settings Jarvis never changes: said so, with where to change them. */
    private val NEVER = Regex(" (pin|password|fingerprint|biometric|one tap|real orders|lock|idle|backup|api key|api secret|wipe) ")

    /**
     * [s]: the lower-case, spaced text (commas in numbers removed, fillers gone). A change asked for, as a command
     * ([Command.Kind.SET_LIMIT], [Command.target] the key, [Command.level] the value), or null when it is not one.
     */
    fun parse(s: String): Command? {
        // A change is asked for with a verb first ("set max lots to 3", "turn off the loss limit"), or as the whole
        // sentence "<name> <value>" ("max lots 5", "daily loss limit off"); anything else ("the loss limit is 5000
        // right?", "tell me if max lots is 3") is a question, never a change.
        val verb = Regex("^ (set|change|make|update|edit|put|increase|raise|decrease|reduce|lower|turn (on|off)|switch (on|off)|disable|enable|remove|keep|allow|block) ").containsMatchIn(s)
        val (key, named) = NAMES.firstNotNullOfOrNull { (k, r) -> Regex(" ($r) ").find(s)?.let { k to it } } ?: return null
        val nameRx = NAMES.first { it.first == key }.second
        val bare = Regex("^ (the |my )?($nameRx) (is |to |at |of )?(\\S+( \\S+)?) $").containsMatchIn(s)
        val cutoffPhrase = key == Key.CUTOFF && Regex("^ no new (entries|positions|trades) after ").containsMatchIn(s)
        if (!verb && !bare && !cutoffPhrase) return null
        // "By 2000" is a change by an amount, not to it: asked again rather than guessed.
        if (Regex(" by \\d").containsMatchIn(s)) return null
        val after = s.substring(named.range.last)
        val rest = s.replaceRange(named.range, " ")
        // Off only when the words say so next to the name or as the verb ("square off" elsewhere is not "off").
        val off = Regex("^ (off|none|no limit|unlimited|disabled|removed)( |$)").containsMatchIn(after) ||
            Regex("^ (turn|switch) off |^ (disable|remove) ").containsMatchIn(s)
        val on = Regex("^ on( |$)").containsMatchIn(after) || Regex("^ (turn|switch) on |^ enable ").containsMatchIn(s)
        val value: Double = when (key.unit) {
            Unit.SWITCH -> if (key == Key.NAKED_SHORTS && !named.value.contains("block")) {
                // "Naked shorts" bare: what is said is about the shorts themselves - blocking them is the switch on.
                when { off || Regex("^ block ").containsMatchIn(s) -> 1.0; on || Regex("^ allow ").containsMatchIn(s) -> 0.0; else -> return null }
            } else when { off -> 0.0; on -> 1.0; key == Key.NAKED_SHORTS && Regex("^ block ").containsMatchIn(s) -> 1.0; else -> return null }
            Unit.PRODUCT -> if (!verb) return null else when { Regex(" mis ").containsMatchIn(rest) -> 0.0; Regex(" nrml | normal | carry ?forward ").containsMatchIn(rest) -> 1.0; else -> return null }
            Unit.TIME -> time(rest)?.toDouble() ?: if (off) -1.0 else return null
            else -> number(rest, key) ?: if (off && key.canOff) 0.0 else return null
        }
        return Command(Command.Kind.SET_LIMIT, target = key.name, level = value)
    }

    /** "Change my PIN": not by voice. */
    fun forbidden(s: String): Boolean = Regex("^ (set|change|make|update|edit|turn (on|off)|switch (on|off)|disable|enable|remove) ").containsMatchIn(s) && NEVER.containsMatchIn(s)

    /** The value after the setting's name: "5000", "5k", "1 lakh", "10 thousand", "2.5" (percent). */
    private fun number(s: String, key: Key): Double? {
        val m = Regex(" (\\d+(?:\\.\\d+)?) ?(k|thousand|lakh|lakhs|lac|l|crore)?(?= )").findAll(s).lastOrNull() ?: return null
        val n = m.groupValues[1].toDouble() * when (m.groupValues[2]) {
            "k", "thousand" -> 1_000.0; "lakh", "lakhs", "lac", "l" -> 100_000.0; "crore" -> 10_000_000.0; else -> 1.0 }
        return when (key.unit) {
            Unit.COUNT -> n.takeIf { it >= 1 && it <= 1_000 && it % 1.0 == 0.0 }
            Unit.PERCENT -> n.takeIf { it > 0 && it <= 100 }
            Unit.RUPEES -> n.takeIf { it >= 100 && it <= 100_000_000 }
            else -> null
        }
    }

    /** "2 30 pm", "14 30", "2 pm", "half past two" is not read: a minute of the day within market hours, or null. */
    private fun time(s: String): Int? {
        val m = Regex(" (\\d{1,2})(?: (\\d{2}))? ?(am|pm)? ").findAll(s).lastOrNull() ?: return null
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (m.groupValues[3] == "pm" && h < 12) h += 12
        if (m.groupValues[3].isEmpty() && h in 1..3) h += 12      // "2 30" in market hours is 14:30
        val at = h * 60 + min
        return at.takeIf { min < 60 && it in (9 * 60 + 15)..(15 * 60 + 30) }
    }

    fun show(key: Key, v: Double): String = when (key.unit) {
        Unit.RUPEES -> if (v <= 0) "off" else "Rs %,.0f".format(Locale.ENGLISH, v)
        Unit.COUNT -> if (v <= 0) "off" else v.toInt().toString()
        Unit.PERCENT -> if (v <= 0) "off" else "%s%%".format(Locale.ENGLISH, if (v % 1.0 == 0.0) v.toInt().toString() else v.toString())
        Unit.TIME -> if (v < 0) "off" else "%02d:%02d".format(Locale.ENGLISH, v.toInt() / 60, v.toInt() % 60)
        Unit.PRODUCT -> if (v == 0.0) "MIS" else "NRML"
        Unit.SWITCH -> if (v != 0.0) "on" else "off"
    }

    /** Does changing [key] from [old] to [new] allow more risk? (alerts and the product never do) */
    fun loosens(key: Key, old: Double, new: Double): Boolean = when (key.unit) {
        Unit.PRODUCT -> false
        Unit.SWITCH -> old != 0.0 && new == 0.0
        Unit.TIME -> (old >= 0 && new < 0) || (old >= 0 && new > old)
        else -> if (key == Key.LOSS_ALERT || key == Key.PROFIT_ALERT) false
            else (old > 0 && new <= 0) || (old > 0 && new > old)
    }

    /** "change max lots from 2 to 3". */
    fun describe(key: Key, old: Double?, new: Double): String =
        "change ${key.label} " + (old?.let { "from ${show(key, it)} " } ?: "") + "to ${show(key, new)}"
}
