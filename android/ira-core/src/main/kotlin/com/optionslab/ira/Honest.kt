package com.optionslab.ira

/**
 * Questions the phone cannot answer truthfully, answered as such instead of with Nifty's numbers (coverage audit, round 5):
 * markets it has no data for ("what's crude doing", "how is the dow", "dollar rupee", "gift nifty"), company results,
 * VWAP (index candles carry no volume), a target or a bottom for the index (no predictions), and "how many lots can I
 * take" with no budget. Questions only: anything that could act ("set target", "alert me when crude...") is never read
 * here, and Boss's own ("my target") is his account's. Pure.
 */
object Honest {
    sealed interface Asked {
        /** A market the phone has no data for, as Boss would name it. */
        data class Elsewhere(val what: String) : Asked
        data object Results : Asked
        data class Vwap(val market: Market?) : Asked
        data class Target(val market: Market?) : Asked
        data object LotsNoBudget : Asked
        /**
         * "How many lots is Liquidity trading?" ([SettingsTalk.liquidityLotsAsked]): not a budget sum - the app answers it from
         * the arms' book (Liquidity 15+5's size, [liquidityLots]); [say] is only the words when that book cannot be read.
         */
        data object LiquidityLots : Asked
    }

    /** Words that act or are Boss's own: never read here. */
    private val NOT_HERE = Regex("(?i)\\b(my|mine|mera|meri|mere|our|set|sets|alert|alerts|alarm|alarms|remind|buy|sell|order|orders|stop|cancel|trail|news|headlines?|khabar|backtest)\\b")

    private val GIFT = Regex("(?i)\\b(gift|sgx)\\s*(nifty|nifti|niftee)\\b|\\bgift\\s*city\\b")
    private val WHY = Regex("(?i)\\b(why|kyun|kyu|kyon|reason|wajah)\\b")

    /** The markets the phone has nothing for, the words Boss says for each, and how Jarvis names it back. */
    private val ELSEWHERE: List<Pair<Regex, String>> = listOf(
        GIFT to "GIFT Nifty",
        Regex("(?i)\\b(crude|crude oil|brent|wti|oil price|oil prices|tel ka (rate|bhav)|natural gas|nat gas)\\b") to "crude or gas",
        Regex("(?i)\\b(dow|dow jones|doe jones|nasdaq|naz daq|s ?& ?p|s and p|wall street|us markets?|u s markets?|american markets?|us stocks|us futures|america ka market)\\b") to "US market",
        Regex("(?i)\\b(asia|asian markets?|nikkei|hang seng|hangseng|shanghai|kospi|japan market|china market)\\b") to "Asian market",
        Regex("(?i)\\b(europe|european markets?|ftse|dax|cac)\\b") to "European market",
        Regex("(?i)\\b(usd ?inr|dollar ?rupee|rupee ?dollar|dollar (rate|price|index|kitne|ka rate|ka bhav)|rupee (rate|today|vs|against|kaisa|ka rate|falling|gir)|rupaya|rupaiya|currency|forex|dxy)\\b") to "currency",
        Regex("(?i)\\b(us (bond )?yields?|bond yields?|treasury yields?|10 year yield)\\b") to "bond",
        Regex("(?i)\\b(bitcoin|crypto|ethereum)\\b") to "crypto",
        Regex("(?i)\\b(silver|chandi)\\b") to "silver",
    )

    private val RESULTS = Regex("(?i)\\b(results? (today|aaj|this week|kab|season|calendar)|any results|kiske results|whose results|(q[1-4]|quarterly) results?|earnings( today| season| calendar)?)\\b")

    private val VWAP = Regex("(?i)\\b(vwap|v wap|vee wap|v w a p|vwaap|vwp)\\b")
    /** The word itself asked ("what is VWAP?", "VWAP kya hota hai"): the glossary's. */
    private val VWAP_WORD = Regex("(?i)^\\s*(what\\s*(is|'s|s)|explain|define)\\s+(a\\s+)?(vwap|v wap|vee wap|v w a p|vwaap|vwp)\\s*\\??\\s*$|\\b(mean|means|meaning|matlab|kya hota|definition)\\b")

    private val TARGET = Regex("(?i)\\b(targets?|target price|price target|lakshya)\\b")
    private val OWN_TARGET = Regex("(?i)\\b(day|daily|day's|profit|loss|stop|weekly|monthly|goal)\\s+targets?\\b")
    private val BOTTOM = Regex("(?i)\\b(where is the (bottom|top)(?! (gainers?|losers?|strike|oi|open interest))|bottom kahan|top kahan|how low (can|will)|how high (can|will)|kitna aur girega|kitna aur gir(e|ega)|kitna aur (upar )?jayega)\\b")

    fun asked(text: String): Asked? {
        // (Liquidity 15+5's size asked - "how many lots is liquidity trading" - is its own answer, never the budget sum.)
        if (SettingsTalk.liquidityLotsAsked(text)) return Asked.LiquidityLots
        // ("How many lots can I buy?" is arithmetic asked without its budget, never an order: the hub reads orders first.)
        if (Sizing.needsBudget(text)) return Asked.LotsNoBudget
        if (NOT_HERE.containsMatchIn(text)) return null
        if (VWAP.containsMatchIn(text)) return if (VWAP_WORD.containsMatchIn(text)) null else Asked.Vwap(index(text))
        if (RESULTS.containsMatchIn(text) && !rx("(?i)\\b(trade|trades|strategy|strategies|test)\\b").containsMatchIn(text)) return Asked.Results
        // "Why is Nifty falling, is it crude?" is Nifty's why (its reasons are read from what the phone has); gold's own
        // dollar is gold's ("gold in dollars").
        val indexWhy = WHY.containsMatchIn(text) && index(text.replace(GIFT, " ")) != null
        for ((re, what) in ELSEWHERE) if (re.containsMatchIn(text) && !indexWhy && !(what == "currency" && Market.GOLD in Market.mentioned(text))) return Asked.Elsewhere(what)
        // (A day's, profit's or stop's target is Boss's own setting, not a call on the index.)
        if ((TARGET.containsMatchIn(text) && !OWN_TARGET.containsMatchIn(text)) || BOTTOM.containsMatchIn(text)) return Asked.Target(index(text))
        return null
    }

    private fun index(text: String): Market? = Market.mentioned(text).firstOrNull { it != Market.VIX && it != Market.GOLD }

    private fun names(follows: List<Market>): String {
        val l = follows.map { if (it == Market.GOLD) "gold" else if (it == Market.VIX) "VIX" else it.label }
        return if (l.size < 2) l.joinToString() else l.dropLast(1).joinToString(", ") + " and " + l.last()
    }

    /** The honest answer; [follows]: the markets this phone has (only gold on the gold-only app). */
    fun say(a: Asked, follows: List<Market> = Market.entries): String = when (a) {
        is Asked.Elsewhere -> "I don't have ${a.what} data on the phone, Boss - I follow ${names(follows)}. I won't guess at it."
        Asked.Results -> "I don't have company results or the earnings calendar on the phone, Boss - I follow ${names(follows)}. " +
            "The news may carry a big result when it comes."
        is Asked.Vwap -> "No VWAP, Boss: index candles carry no volume, so there is no volume-weighted price for ${a.market?.label ?: "the indices"}. " +
            "I can give you its levels, pivots or the opening range instead."
        is Asked.Target -> "I don't give targets or predictions, Boss - nobody knows where ${a.market?.label ?: "the market"} ends up. " +
            "I can give you its levels: support, resistance and the pivots."
        Asked.LotsNoBudget -> "Tell me the budget, Boss - like \"how many lots can I buy with 20000\" - and I'll work it out from the " +
            "at-the-money premium. Your margin is under Funds."
        Asked.LiquidityLots -> "Liquidity 15+5's lots are on its row on Home, Boss: Lots 1, 2 or 3."
    }

    private val INDEX_NAMES = mapOf("BANKNIFTY" to "BankNifty", "FINNIFTY" to "FinNifty", "MIDCPNIFTY" to "Midcap Nifty")

    /**
     * Liquidity 15+5's size said: [lots] a new entry, each index's quantity ([lotSizes]: underlying -> its lot, when known), and
     * whether the Bot settings' [maxLots] would refuse it on Zerodha (0: no cap). Nothing changes from here.
     */
    fun liquidityLots(lots: Int, lotSizes: Map<String, Int>, maxLots: Int): String =
        "Liquidity 15+5 buys $lots lot${if (lots == 1) "" else "s"} on each new entry, Boss" +
            (if (lotSizes.isEmpty()) "" else " (" + lotSizes.entries.joinToString(", ") { (u, l) -> "${lots * l} on ${INDEX_NAMES[u] ?: u}" } + ")") +
            "; an open position keeps the quantity it was bought with, and its 15% stop and exits cover all of it. " +
            (if (maxLots in 1 until lots) "On Zerodha the Bot settings allow $maxLots lot${if (maxLots == 1) "" else "s"}, so a live entry would be refused until one of them changes. " else "") +
            "Say \"set liquidity to 2 lots\" to change it - more lots waits for your yes."
}
