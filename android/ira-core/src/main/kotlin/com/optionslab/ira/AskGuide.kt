package com.optionslab.ira

/**
 * "What can I ask Jarvis?" (06 Oct 2026): a tappable guide to the questions Jarvis answers, for Boss, who cannot keep every
 * family in his head. Groups (Liquidity 15+5, Solo, Hero, his strategies, the market and its levels, news and events, his
 * account and P&L, coach and reviews, plans, the app), each with a few example questions as Boss says them - Hinglish too
 * where Jarvis reads it - and each tagged with the family that answers it ([Example.family]: the hub's feature as the routing
 * audit names it). The tests route every example through the hub's own order and check none is an action: the guide names
 * questions only (nothing is switched, bought, sold, closed or set from it), so a tap on one is the same as Boss typing it.
 *
 * Shown as a sheet from the Ira page's question box and from Settings → App → "What can I ask?"; Jarvis's "what can you do"
 * points at it ([POINTER], [helpLine]). IraGoldAlgo (which only talks, about gold) shows only the examples marked
 * [Example.gold]. Pure.
 */
object AskGuide {
    /** One question as Boss says it, and the family that answers it. [gold]: also answered in IraGoldAlgo. */
    data class Example(val q: String, val family: String, val gold: Boolean = false)

    /** A group of questions: a stable [id] (the sheet remembers which are open), its title, one line on it, the examples. */
    data class Group(val id: String, val title: String, val blurb: String, val examples: List<Example>)

    private fun ex(q: String, family: String, gold: Boolean = false) = Example(q, family, gold)

    /** Every group, in the sheet's order. Each example is a question only, answered as said (the tests route each). */
    val GROUPS: List<Group> = listOf(
        Group("liquidity", "Liquidity 15+5", "Why it did or did not trade, its record and its levels", listOf(
            ex("why didn't liquidity trade today", "LiquidityWhyNot"),
            ex("what is liquidity waiting for", "LiquidityWhyNot"),
            ex("liquidity ne aaj trade kyu nahi liya", "LiquidityWhyNot"),
            ex("how did liquidity do this week", "LiquidityRecord"),
            ex("liquidity win rate", "LiquidityRecord"),
            ex("liquidity ke last 10 trades", "LiquidityRecord"),
            ex("what if liquidity traded 3 lots", "LotsWhatIf"),
            ex("where are the liquidity levels", "LiquidityMap"),
            ex("how many lots does liquidity trade", "Honest"),
        )),
        Group("solo", "Solo", "What Solo (midday) looked at, picked or passed on", listOf(
            ex("what did Solo do today", "SoloDay"),
            ex("did solo trade today", "SoloDay"),
            ex("how did solo decide today", "SoloDay"),
            ex("solo ne aaj kya kiya", "SoloDay"),
            ex("solo ne aaj trade kyun nahi liya", "SoloDay"),
            ex("how is Solo doing", "Solo"),
        )),
        Group("hero", "Hero", "Whether today is a Hero day, what it did and why", listOf(
            ex("is today a hero day", "HeroDay"),
            ex("what did hero do today", "HeroDay"),
            ex("why didn't hero trade today", "HeroDay"),
            ex("when is the next hero day", "HeroDay"),
            ex("hero ne aaj kya kiya", "HeroDay"),
            ex("what is hero waiting for", "HeroDay"),
        )),
        Group("strategies", "Your strategies", "What your bots did today, and why", listOf(
            ex("how are my strategies doing", "Account:BOTS"),
            ex("what did my bots do today", "BotTrades"),
            ex("mere bots ne aaj kya kiya", "BotTrades"),
            ex("are my bots fighting each other", "BotTrades"),
            ex("why did my bots lose today", "ArmDay"),
            ex("orb aaj kyun fail hua", "ArmDay"),
            ex("is anything drifting", "ForwardWatch"),
            ex("what's working for liquidity", "LiquidityInsight"),
            ex("how long does liquidity hold its trades", "LiquidityHold"),
        )),
        Group("market", "Market & levels", "The indices now, the day's shape and the option chain", listOf(
            ex("how is nifty", "Market"),
            ex("how did the market open", "OpeningRead"),
            ex("is gold up today", "Market", gold = true),
            ex("what's the structure today", "Structure"),
            ex("aaj trend day hai kya", "Structure"),
            ex("how far is nifty from 25000", "Distance"),
            ex("where is max pain", "Account:CHAIN"),
            ex("where is the most call writing", "ChainIntel"),
            ex("do gap downs usually fill", "GapRecord"),
        )),
        Group("news", "News & events", "The headlines, why the market moved, the week, the calendar and what you missed", listOf(
            ex("what's the main news", "NewsDesk"),
            ex("aaj ki main news kya hai", "NewsDesk"),
            ex("why is nifty falling", "Causes"),
            ex("nifty aaj kyun gira", "Causes"),
            ex("how does the market react to rbi news", "NewsMoves"),
            ex("what does this week look like", "WeekAhead"),
            ex("is tomorrow a holiday", "MarketDays"),
            ex("kal expiry hai kya", "MarketDays"),
            ex("catch me up", "CatchUp"),
        )),
        Group("account", "Your account & P&L", "Your P&L, positions, limits and streaks", listOf(
            ex("what is my p&l", "Account:PNL"),
            ex("mera pnl kitna hai", "Account:PNL"),
            ex("show my positions", "Account:POSITIONS"),
            ex("is my put healthy", "Account:HEALTH"),
            ex("where is my breakeven", "Account:NEED"),
            ex("how close am i to my limits", "Headroom"),
            ex("kitne trade bache hain", "Headroom"),
            ex("am i on a winning streak", "Account:STREAKS"),
        )),
        Group("coach", "Coach & reviews", "Your day, your week and your habits, reviewed", listOf(
            ex("what matters right now", "CoPilot"),
            ex("am i ready to trade", "PreMarket"),
            ex("how was my day", "DaySummary"),
            ex("aaj ka din kaisa raha", "DaySummary"),
            ex("what happened last friday", "DayRecap"),
            ex("help me journal today", "DayJournal"),
            ex("how did this week go", "WeeklyReview"),
            ex("do i overtrade after a loss", "AfterLoss"),
            ex("am i better at calls or puts", "WhereIWin"),
        )),
        Group("plans", "Plans", "Today's plan, tomorrow's and what-ifs", listOf(
            ex("what's the plan for today", "Agenda"),
            ex("aaj ka plan", "Agenda"),
            ex("what's the plan for tomorrow", "TomorrowPlan"),
            ex("kal ka plan batao", "TomorrowPlan"),
            ex("anything to do before tomorrow", "BeforeTomorrow"),
            ex("what if nifty falls 1%", "Scenarios"),
            ex("make the case for trading now", "TradeCase"),
        )),
        Group("app", "App", "Jarvis himself: what's new, how fresh the data is, where a setting is, a word explained", listOf(
            ex("what can you do", "Help", gold = true),
            ex("what can i ask you", "Tour"),
            ex("main kya pooch sakta hoon", "Tour"),
            ex("what's new in the app", "WhatsNew"),
            ex("what did you tell me today", "TodayNotes"),
            ex("where is the quiet hours setting", "SettingWhere"),
            ex("is your data fresh", "DataAge"),
            ex("what is theta", "Glossary", gold = true),
            ex("what have you learned this week", "Learnings"),
        )),
    )

    /** The groups this build shows: everything, or in IraGoldAlgo only the examples marked [Example.gold] (empty groups gone). */
    fun forBuild(gold: Boolean): List<Group> =
        if (!gold) GROUPS else GROUPS.map { g -> g.copy(examples = g.examples.filter { it.gold }) }.filter { it.examples.isNotEmpty() }

    /** Every example of [groups], in order. */
    fun all(groups: List<Group> = GROUPS): List<Example> = groups.flatMap { it.examples }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9%&+ ]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * The search box: the groups with only the examples whose words hold every word of [query] (any order, case and
     * punctuation ignored); a word in a group's title keeps the whole group ("hero" shows all of Hero's). A blank [query]
     * keeps everything; a group with nothing left is dropped.
     */
    fun filter(groups: List<Group>, query: String): List<Group> {
        val words = norm(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return groups
        return groups.mapNotNull { g ->
            val title = norm(g.title)
            if (words.all { title.contains(it) }) return@mapNotNull g
            val kept = g.examples.filter { e -> val t = norm(e.q); words.all { t.contains(it) } }
            if (kept.isEmpty()) null else g.copy(examples = kept)
        }
    }

    /** Where the sheet is (the Ira page's question box, in both apps; IraAlgo's Settings → App too). No question mark: the voice stops there. */
    const val POINTER = "Every question I answer, by topic, is one tap away: \"What can I ask\" beside the question box"

    /** The three questions Jarvis names after [POINTER]: one of each kind (a strategy, right now, his account). */
    val NAMED: List<String> = listOf("why didn't liquidity trade today", "what matters right now", "how close am i to my limits")

    /**
     * The line "what can you do" / "help" / "tum kya kar sakte ho" ends with: where the sheet is and [NAMED] to try. Two
     * short sentences, no question mark inside a quote (the voice would stop there).
     */
    fun helpLine(): String = "$POINTER. Try " + NAMED.dropLast(1).joinToString(", ") { "\"$it\"" } + " or \"${NAMED.last()}\"."
}
