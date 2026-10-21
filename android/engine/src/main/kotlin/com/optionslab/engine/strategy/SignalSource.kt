package com.optionslab.engine.strategy

/**
 * Where a signal strategy's signal may come from (14 Oct, Boss: "all the indicators and strategies should be on the
 * regular funds/index, and on the basis of that we buy options; make this default"). A signal leg names the instrument
 * its alerts trade, and an alert that names no leg id is matched to a leg by that symbol ([Signals.findLeg]). The default
 * is the underlying (the index, or for MCX the future). A leg that names an option is not the default: its alerts may be
 * set on that option's own chart, which the definition cannot tell. Such a leg keeps working exactly as it was set; it
 * is only labelled ([note]) wherever the strategy is shown. Batch strategies have no signal and are never labelled. Pure.
 */
object SignalSource {
    /** An option's trading symbol ends in its strike and CE or PE ("NIFTY26OCT24500CE", "GOLDM26DEC72000PE"). */
    private val OPTION = Regex("\\d(CE|PE)$")

    /** Whether [leg] names an option (its symbol reads as one, spaces aside). */
    fun isOptionLeg(leg: LegDef): Boolean = OPTION.containsMatchIn((leg.symbol ?: "").uppercase().replace(" ", ""))

    /** The signal legs of [def] that name an option (none for a batch strategy). */
    fun optionLegs(def: StrategyDef): List<LegDef> = if (def.kind != StrategyKind.SIGNAL) emptyList() else def.legs.filter { isOptionLeg(it) }

    /** The label for [def] when any signal leg names an option, or null when every one names the underlying. */
    fun note(def: StrategyDef): String? {
        val legs = optionLegs(def)
        if (legs.isEmpty()) return null
        val named = legs.joinToString(", ") { l -> "leg ${l.id} (${l.symbol})" }
        return "Not the default signal: $named ${if (legs.size == 1) "names an option, so its alerts" else "name options, so their alerts"} " +
            "may come from an option's own chart. The default reads the index (for MCX the future) and then buys the option; " +
            "this strategy keeps working as you set it."
    }
}
