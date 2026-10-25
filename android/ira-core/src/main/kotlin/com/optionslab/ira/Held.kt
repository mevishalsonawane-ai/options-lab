package com.optionslab.ira

/**
 * 20 Oct, enhancement round 1 (idea 7): ONE answer to "is anything held?" (an open position or a working order, paper or
 * live, NSE or MCX) for every worker that slows down when nothing is held. There were four lists, each a little different,
 * which is how the T3 gap happened (one list missed the paper book, so its stops were checked once a minute).
 *
 * The app gathers [Sources] from memory only (the books' hints: no lock, no vault, no network); this decides. Anything not
 * known yet counts as held ([Sources.paperBook] and [Sources.protections] null), so a slow start-up never slows a stop.
 * Open positions come first: a source added here makes every worker check MORE often, never less. Pure.
 */
object Held {
    /**
     * What each book says, as last seen in memory. [paperBook]: the paper account's positions or working orders (null: not
     * loaded yet). [protections]: an active stop or target (null: not loaded yet). [mcxLive]: Zerodha's MCX positions, asked only
     * while MCX trades (the app passes false outside its hours).
     */
    data class Sources(
        val cards: Boolean = false,
        val paperBook: Boolean? = false,
        val protections: Boolean? = false,
        val orb: Boolean = false,
        val orbLive: Boolean = false,
        val pine: Boolean = false,
        val strategies: Boolean = false,
        val armLive: Boolean = false,
        val armLiveRecovering: Boolean = false,
        val passed: Boolean = false,
        val hero: Boolean = false,
        /** 21 Oct: the FT3 forward test's paper ticket. */
        val ft3: Boolean = false,
        val signal: Boolean = false,
        val night: Boolean = false,
        val vixDiv: Boolean = false,
        val mcxArms: Boolean = false,
        val mcxLive: Boolean = false,
    )

    /** Each source's name and whether it holds (unknown counts as held). */
    fun parts(s: Sources): List<Pair<String, Boolean>> = listOf(
        "Zerodha positions" to s.cards,
        "paper book" to (s.paperBook != false),
        "stops and targets" to (s.protections != false),
        "ORB and Liquidity" to (s.orb || s.orbLive),
        "Pine scripts" to s.pine,
        "strategies" to s.strategies,
        "arms' live trades" to (s.armLive || s.armLiveRecovering),
        "passed-research arms" to s.passed,
        "Hero ticket" to s.hero,
        "FT3" to s.ft3,
        "ntfy alerts" to s.signal,
        "Night (R3)" to s.night,
        "VIX divergence" to s.vixDiv,
        "MCX arms" to s.mcxArms,
        "MCX live" to s.mcxLive,
    )

    /** Anything held anywhere. */
    fun any(s: Sources): Boolean = parts(s).any { it.second }

    /** What holds, in words, for the diagnostics ("nothing" when nothing does). */
    fun line(s: Sources): String = parts(s).filter { it.second }.joinToString(", ") { it.first }.ifEmpty { "nothing" }
}
