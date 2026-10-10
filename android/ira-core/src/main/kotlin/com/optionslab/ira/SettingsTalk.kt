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
        /**
         * Liquidity 15+5's size: lots each new entry buys (1, 2 or 3; Boss's 06 Oct choice). Kept in the arms' book, not the
         * app's settings; more lots is more risk ([loosens]), so a raise waits for Boss's own confirmed yes.
         */
        LIQUIDITY_LOTS("Liquidity 15+5's lots a trade", Unit.COUNT, canOff = false),
    }

    enum class Unit { RUPEES, COUNT, PERCENT, TIME, PRODUCT, SWITCH }

    /** Words naming each setting, most specific first (so "paper daily loss" is not "daily loss"). */
    private val NAMES: List<Pair<Key, String>> = listOf(
        Key.PAPER_DAILY_LOSS to "paper (daily )?(stop ?loss|sl|loss|loss limit|max loss)( (for|per) (the |a )?day)?",
        Key.PAPER_TRADES to "paper (max )?(orders|trades)( per day| a day)?",
        Key.LOSS_ALERT to "(p l |pnl )?loss alert",
        Key.PROFIT_ALERT to "(p l |pnl )?profit alert",
        Key.DAILY_LOSS to "(daily (stop ?loss|sl)( limit)?|(stop ?loss|sl|max loss) (for|per) (the |a )?day|daily loss( limit)?|max(imum)? (daily )?loss|(?<!stop )loss limit|stop loss limit for the day)",
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
        liquidityLots(s)?.let { return it }
        // A change is asked for with a verb first ("set max lots to 3", "turn off the loss limit"), or as the whole
        // sentence "<name> <value>" ("max lots 5", "daily loss limit off"); anything else ("the loss limit is 5000
        // right?", "tell me if max lots is 3") is a question, never a change.
        val verb = rx("^ (set|change|make|update|edit|put|increase|raise|decrease|reduce|lower|turn (on|off)|switch (on|off)|disable|enable|remove|keep|allow|block) ").containsMatchIn(s)
        val (key, named) = NAMES.firstNotNullOfOrNull { (k, r) -> rx(" ($r) ").find(s)?.let { k to it } } ?: return null
        val nameRx = NAMES.first { it.first == key }.second
        val bare = rx("^ (the |my )?($nameRx) (is |to |at |of )?(\\S+( \\S+)?) $").containsMatchIn(s)
        val cutoffPhrase = key == Key.CUTOFF && rx("^ no new (entries|positions|trades) after ").containsMatchIn(s)
        if (!verb && !bare && !cutoffPhrase) return null
        // "By 2000" is a change by an amount, not to it: asked again rather than guessed.
        if (rx(" by \\d").containsMatchIn(s)) return null
        val after = s.substring(named.range.last)
        val rest = s.replaceRange(named.range, " ")
        // Off only when the words say so next to the name or as the verb ("square off" elsewhere is not "off").
        val off = rx("^ (off|none|no limit|unlimited|disabled|removed)( |$)").containsMatchIn(after) ||
            rx("^ (turn|switch) off |^ (disable|remove) ").containsMatchIn(s)
        val on = rx("^ on( |$)").containsMatchIn(after) || rx("^ (turn|switch) on |^ enable ").containsMatchIn(s)
        val value: Double = when (key.unit) {
            Unit.SWITCH -> if (key == Key.NAKED_SHORTS && !named.value.contains("block")) {
                // "Naked shorts" bare: what is said is about the shorts themselves - blocking them is the switch on.
                when { off || rx("^ block ").containsMatchIn(s) -> 1.0; on || rx("^ allow ").containsMatchIn(s) -> 0.0; else -> return null }
            } else when { off -> 0.0; on -> 1.0; key == Key.NAKED_SHORTS && rx("^ block ").containsMatchIn(s) -> 1.0; else -> return null }
            Unit.PRODUCT -> if (!verb) return null else when { rx(" mis ").containsMatchIn(rest) -> 0.0; rx(" nrml | normal | carry ?forward ").containsMatchIn(rest) -> 1.0; else -> return null }
            Unit.TIME -> time(rest)?.toDouble() ?: if (off) -1.0 else return null
            else -> number(rest, key) ?: if (off && key.canOff) 0.0 else return null
        }
        return Command(Command.Kind.SET_LIMIT, target = key.name, level = value)
    }

    // ---- Liquidity 15+5's size (Boss, 06 Oct: "2-3 lots") -------------------------------------------------------------

    private const val LIQ = "liquidity( 15 5| 15 plus 5| fifteen plus five)?( arm| strategy| bot)?"
    private const val LOT_N = "(\\d{1,2}|one|two|three|four|five|ek|do|teen|char|paanch)"
    /** "Set liquidity to 2 lots", "liquidity ko 3 lot karo", "make liquidity trade 3 lots", "liquidity lots 2", "liquidity 2 lot kar do". */
    private val LIQ_SET = listOf(
        // A verb first ("set", "change", "make", "increase", "raise", "decrease", "reduce", "lower", "put", "trade") with the
        // arm and a number of lots.
        "^ (set|change|make|update|put|increase|raise|decrease|reduce|lower|bump|take|move|trade) (the |my )?$LIQ( lots?| size| quantity)?( (to|at|on|in|with))? $LOT_N lots?( (a|per|each) trade| each)? $",
        "^ (set|change|make|update|put|increase|raise|decrease|reduce|lower) (the |my )?$LIQ (lots?|size|quantity) (to |at )?$LOT_N( lots?)? $",
        "^ (make|let|have) (the |my )?$LIQ (trade|take|buy|use) $LOT_N lots?( (a|per|each) trade)? $",
        "^ (trade|use) $LOT_N lots? (on|for|in|with) (the |my )?$LIQ $",
        // As said bare: "liquidity lots 3", "liquidity 3 lots", "liquidity size 2".
        "^ (the |my )?$LIQ (lots?|size)( to| at)? $LOT_N( lots?)? $",
        "^ (the |my )?$LIQ (to |at )?$LOT_N lots? $",
        // Hinglish, verb last: "liquidity ko 3 lot karo", "liquidity 2 lot kar do", "liquidity mein 3 lot lagao" (and as the
        // verb is turned round, "set liquidity 2 lot").
        "^ (set )?(the |my )?$LIQ (ko |mein |me |main |par |pe )?$LOT_N lots? (ka |ke )?(karo|kar do|kardo|kar dijiye|kijiye|lagao|laga do|rakho|rakh do|chalao|kar)( na| please)? $",
        "^ set (the |my )?$LIQ (ko |mein |me )?$LOT_N lots? $",
    )
    private val WORD_N = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "paanch" to 5)

    /**
     * [s] (lower-case, spaced, as [parse] takes it): a change of Liquidity 15+5's size ([Key.LIQUIDITY_LOTS], [Command.level]
     * the lots said), or null. Any number of lots is read as said; the app says when it is not 1, 2 or 3.
     */
    fun liquidityLots(s: String): Command? {
        if (LIQ_SET.none { rx(it).containsMatchIn(s) }) return null
        val n = rx(" $LOT_N lots? ").find(s)?.groupValues?.get(1) ?: rx(" (lots?|size)( to| at)? $LOT_N ").find(s)?.groupValues?.lastOrNull() ?: return null
        val lots = n.toIntOrNull() ?: WORD_N[n] ?: return null
        if (lots < 1) return null
        return Command(Command.Kind.SET_LIMIT, target = Key.LIQUIDITY_LOTS.name, level = lots.toDouble())
    }

    /**
     * "How many lots is Liquidity trading?", "liquidity kitne lot mein trade kar raha hai", "what size is liquidity": Liquidity
     * 15+5's size asked (the hub answers from the arms' book). Never a change: [liquidityLots] reads those.
     */
    fun liquidityLotsAsked(text: String): Boolean {
        val t = " " + text.lowercase().replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "
        if (!t.contains(" liquidity ")) return false
        return rx(" (how many|kitne|kitna|kitni) lots?| what (size|lot size|position size)| (lots?|size) (is|does|do|will) (the |my )?liquidity|" +
            " liquidity( 15 5)?( s)? (lots?|size|lot size|position size)( is| kya hai| kitna hai| kitne hai)? $").containsMatchIn(t) &&
            !rx(" (set|change|make|increase|raise|decrease|reduce|lower|karo|kar do|kardo) ").containsMatchIn(t)
    }

    /** "Change my PIN": not by voice. */
    fun forbidden(s: String): Boolean = rx("^ (set|change|make|update|edit|turn (on|off)|switch (on|off)|disable|enable|remove) ").containsMatchIn(s) && NEVER.containsMatchIn(s)

    /** The value after the setting's name: "5000", "5k", "1 lakh", "10 thousand", "2.5" (percent). */
    private fun number(s: String, key: Key): Double? {
        val m = rx(" (\\d+(?:\\.\\d+)?) ?(k|thousand|lakh|lakhs|lac|l|crore)?(?= )").findAll(s).lastOrNull() ?: return null
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
        val m = rx(" (\\d{1,2})(?: (\\d{2}))? ?(am|pm)? ").findAll(s).lastOrNull() ?: return null
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
