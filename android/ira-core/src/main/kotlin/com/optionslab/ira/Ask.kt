package com.optionslab.ira

/** What a question is about. */
enum class Topic { OVERVIEW, WHY, TREND, LEVELS, PATTERNS, NEWS, VOLATILITY, ADVICE, ORDER, BACKTEST, ACCOUNT, HELP, COMMAND, TRADE_CHECK, GREETING, OFF_TOPIC, SUGGEST, EXPLAIN }

/**
 * An order the owner asked for in words. Ira never sends it: the app opens its own order review filled with this, and
 * only the owner's Confirm (and PIN in Live) sends it. Missing parts are listed so Ira asks instead of guessing.
 */
data class OrderRequest(
    val market: Market?, val buy: Boolean, val lots: Int?, val strike: Int?, val right: String?,   // "CE" / "PE"
    val missing: List<String>,
    /** "at the money": the listed strike nearest the index when the order is prepared. */
    val atm: Boolean = false,
) {
    /** Why the app cannot prepare this order at all, or null. Gold has no options here; Sensex options trade on BSE. */
    val refusal: String? get() = when (market) {
        Market.GOLD -> "I can't place gold orders: gold trades only through its own buy arms on the Gold page."
        Market.SENSEX -> "I can't place Sensex orders: its options trade on BSE, and IraAlgo orders go to NSE only."
        Market.VIX -> "India VIX can't be traded."
        else -> null
    }

    /** "BUY 2 lots BankNifty 52000 CE" (or "ATM CE"). */
    fun describe(): String = "${if (buy) "BUY" else "SELL"} $lots lot${if ((lots ?: 0) > 1) "s" else ""} ${market?.label} ${if (atm) "ATM" else "$strike"} $right"
}

data class Question(val text: String, val markets: List<Market>, val topics: Set<Topic>, val order: OrderRequest?,
                    /** Something to do in the app ("stop strategy 1"), when the words ask for one. */ val command: Command? = null,
                    /** A pattern named in the words ("backtest the hammer on nifty"), if any. */
                    val pattern: PatternKind? = null, /** A chart named in the words: 15 or 60 minutes, if any. */ val minutes: Int? = null)

/** Reads a question: which markets, what about, and whether it asks for an order. Pure, no model. */
object Ask {
    private val TOPIC_WORDS: List<Pair<Topic, List<String>>> = listOf(
        Topic.BACKTEST to listOf("backtest", "back test", "backtested", "strategy", "test this", "test the pattern", "test it", "make it an arm"),
        Topic.WHY to listOf("why", "reason", "what happened", "behind", "what moved", "moved the market", "what drove"),
        // ("Is this a reversal", "is it a bull trap", "chances of a bounce": the trend asked in traders' words - audit, 5 Oct.)
        Topic.TREND to listOf("trend", "direction", "bullish", "bearish", "going up", "going down", "heading", "reversal", "reversing",
            "bounce", "pullback", "bull trap", "bear trap"),
        Topic.LEVELS to listOf("level", "levels", "support", "resistance", "target", "range", "high", "low", "pool", "liquidity"),
        Topic.PATTERNS to listOf("pattern", "patterns", "candle", "candles", "engulfing", "hammer", "doji", "breakout", "breakdown", "double top", "double bottom"),
        Topic.NEWS to listOf("news", "headline", "headlines", "update", "updates", "what s new", "whats new", "anything new", "kya khabar", "khabar", "global cues"),
        Topic.VOLATILITY to listOf("volatile", "volatility", "vix", "fear", "calm", "busy", "wild", "quiet"),
        Topic.ADVICE to listOf("should i", "shall i", "is it good to", "worth buying", "worth selling", "recommend", "suggest", "tip", "tips", "advice"),
        Topic.OVERVIEW to listOf("doing", "today", "now", "price", "status", "how is", "how's", "update me", "summary", "overview", "market",
            "the fall", "the rally", "the crash", "the selloff"),
        Topic.GREETING to listOf("hello", "hi", "hey", "good morning", "good evening", "jarvis", "ira"),
    )

    /** The owner's own trading: "my orders", "how are my strategies doing", "today's p&l". */
    private val ACCOUNT = Regex(" (my|mine|our) ([a-z]+ ){0,3}(order|orders|trade|trades|position|positions|holding|holdings|p l|pnl|profit|profits|" +
        "loss|losses|strategy|strategies|arm|arms|bot|bots|algo|algos|studies|study|scripts?|account|portfolio|fills|mtm|m2m) " +
        "|( how am i doing | how did i do | today s p l | todays p l | today s pnl | todays pnl | p l today | pnl today )" +
        // Boss's own money and trades without "my" (4 Oct: "how much did I lose today" got the Nifty update).
        "|( (did|have) i (make|made|lose|lost|earn|earned|gain|gained) (?!a |an |the |it |that |this )| trades? did i | how many trades (did|have|today|i ))" +
        // "How much did I make today", "am I in profit": the owner's P&L, not the market's.
        "|( how much (money )?(did|have) i (make|made|earn|earned|lose|lost) | am i (up|down|in profit|in loss|making money|losing money) )" +
        // (Audit, 5 Oct: "am I green today" got the market, "how's my day looking" and "am I on paper or live" nothing.)
        "|( am i (green|red|in the green|in the red) | how (s|is) my day (going|looking)| (what s|whats|what is) my day looking like | " +
        "how much (more )?can i (still )?lose | am i (on|in) (paper|live)( or (paper|live))? | my buying power | how much can i trade with )")
    private val GREET = Regex(" (hello|hi|hey|good morning|good afternoon|good evening|jarvis|ira|boss|ok|okay|please|there) ")
    /** About Ira itself: what it can do, the voice. */
    private val HELP = Regex(" (what can you do|what all can you do|what else can you do|what all do you do|what all can you help (me )?with|what are all the things you can do|what do you do|who are you|what are you|help|how do i use|how to use|can you (hear|listen)|" +
        "listen to me|hear me|your voice|voice|speak to me|talk to me|can you talk|can you speak) ")

    /**
     * [said] as typed; when nothing in it is understood, read again with its near-miss words fixed ([Spelling]).
     * One spoken question is read several times (the voice's checks, the hub's branches, the answer) at a few ms each on
     * a phone, and its first reading builds every pattern: a question's reading is kept and reused ([READ_KEPT] at most).
     * Questions only - a command or an order is read afresh each time (an event's day is today's).
     */
    fun parse(said: String): Question {
        synchronized(read) { read[said] }?.let { return it }
        return parseFresh(said).also { q -> if (q.command == null && q.order == null) synchronized(read) { read[said] = q } }
    }

    /** How many questions' readings [parse] keeps. */
    const val READ_KEPT = 32

    /** The last questions read, the least recently used dropped first. */
    private val read = object : LinkedHashMap<String, Question>(READ_KEPT * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Question>?) = size > READ_KEPT
    }

    private fun parseFresh(said: String): Question {
        val q = parseAs(said)
        if (q.topics == setOf(Topic.OFF_TOPIC)) Spelling.fix(said).takeIf { it != said }?.let { return parseAs(it) }
        // "Senseks today": no market heard - read again with near-miss words fixed when that finds one (questions only).
        if (q.markets.isEmpty() && q.command == null && q.order == null)
            Spelling.fix(said).takeIf { it != said }?.let { f -> parseAs(f).takeIf { it.markets.isNotEmpty() && it.command == null && it.order == null }?.let { return it } }
        return q
    }

    /**
     * [said] as a question: misheard words fixed ([Heard]) and a common Hinglish question read as its English
     * ([Hinglish.question]). Never used to find a command or an order - those are read from the words as heard.
     */
    fun reading(said: String): String {
        val h = Heard.fix(said)
        return Hinglish.question(h).takeIf { it != h } ?: Hinglish.normalize(h)
    }

    private fun parseAs(said: String): Question {
        // Commands and orders: from the words as heard, exactly as strict as ever.
        val heard = Hinglish.normalize(said)
        val t0 = " " + heard.lowercase().replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "
        // (A question mark said - lost when Hinglish is read - still keeps a question from acting, as [Commands] does.)
        Commands.parse(heard)?.takeIf { !said.trim().endsWith("?") || it.kind == Command.Kind.NOTE }
            ?.let { c -> return Question(heard, Market.mentioned(heard), setOf(Topic.COMMAND), null, command = c) }
        // Everything else is a question, read with misheard and Hinglish words understood.
        val text = reading(said)
        val t = " " + text.lowercase().replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "
        // "What should I buy?" - the pattern expert's suggestion (with why), or why there is none now.
        if (SUGGEST.containsMatchIn(t)) return Question(text, Market.mentioned(text), setOf(Topic.SUGGEST), null)
        // "What is a hammer?" - the pattern explained, with its own record.
        if (rx(" (what is|what s|whats|what are|explain|meaning of|tell me about|define) (a |an |the )?").containsMatchIn(t) && named(t) != null && !rx(" (backtest|back test) ").containsMatchIn(t))
            return Question(text, Market.mentioned(text), setOf(Topic.EXPLAIN), null, pattern = named(t))
        // "What's moving?" / "top gainers": the app follows the indices, so every index's move (Boss, 4 Oct).
        if (rx("^ (so )?((what s|whats|what is) moving( today| now)?|top (gainers|losers|movers)( today)?|(biggest|big) (movers|moves)( today)?|any big moves( today)?|which (index|indices|market) (is|are) (moving|up|down|strongest|weakest)( today)?) $").containsMatchIn(t))
            return Question(text, listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX), setOf(Topic.OVERVIEW), null)
        // "What's going on?" (heard 2026-10-04 and sent to the chat, which knew nothing): the Nifty overview.
        if (rx("^ (so )?(what s|whats|what is) (going on|happening)( today| now| in the market)? $|^ (any|what s the) (update|updates)( today)? $|^ (kya chal raha hai|kya haal hai market ka) $" +
                "|^ (is there )?anything (i should know|important|i need to know)( about| in)?( the market)?( today| now)? $").containsMatchIn(t))
            return Question(text, Market.mentioned(text).ifEmpty { listOf(Market.NIFTY) }, setOf(Topic.OVERVIEW), null)
        // "Describe the Nifty chart": the trend, levels, today's range and the latest candle pattern.
        if (rx(" (describe|read|explain|walk me through|tell me about) (the |my )?([a-z]+ )?chart ").containsMatchIn(t))
            return Question(text, Market.mentioned(text).ifEmpty { listOf(Market.NIFTY) }, setOf(Topic.OVERVIEW, Topic.TREND, Topic.LEVELS, Topic.PATTERNS), null)
        // "Should I trade now?" - Jarvis's trade check (never a direction, never a single instrument).
        // (An index named - "is Nifty bullish" - asks that index's trend, not the market-wide check.)
        if (!rx(" (backtest|back test|engulfing|pattern|patterns|strategy|candle|candles|why|what happened|what moved|what drove) ").containsMatchIn(t) &&
            Market.mentioned(text).none { it != Market.VIX } && rx(" (bullish|bearish|market (good|bad|mood|today)|how is the market|is (the )?market (good|bad|up|down|bullish|bearish|trending|sideways)|which way is the market) ").containsMatchIn(t) ||
            rx(" (should|shall|can|could) i (trade(?! with)|be trading|stay out|sit out|take (a |any )?trades?)| (safe|good|right|ok|okay) (time |day )?to (trade|sell options|buy options|sell|buy)| trade (now|today) or not| should i stay out | is today (a )?(good|bad) (day )?(to|for) trad").containsMatchIn(t))
            return Question(text, Market.mentioned(text), setOf(Topic.TRADE_CHECK), null)
        // An order to place names its lots ("buy 2 lots..."); anything else about orders, P&L, strategies, limits or the app
        // is a question about the app.
        // A question ("Did I buy 2 lots of Nifty?") is never an order.
        val placed = if (said.trim().endsWith("?")) null else order(t0)
        // "Where is BankNifty trading?" asks the price, not where something is in the app.
        val priceAsk = Market.mentioned(text).isNotEmpty() && rx("^ (where is|where s|wheres|where) ").containsMatchIn(t) &&
            !rx(" (my|mine|our|order|orders|position|positions|chain|page|tab|screen|see|find|do i|can i) ").containsMatchIn(t)
        // "Yesterday's high on Nifty": the market's own figures, not the owner's history.
        // ("This week", "since the open", "pivots", "RSI"... on a named market are the market's too; stops, targets, alarms,
        // orders and positions stay the owner's.)
        val marketFigure = Market.mentioned(text).isNotEmpty() && !rx(" (my|mine|our|i|me) ").containsMatchIn(t) &&
            !rx(" (stop|stop loss|stoploss|sl|target|alarm|alert|order|orders|position|positions|square|squareoff) ").containsMatchIn(t) &&
            (rx(" (high|low|close|closing|open|opening|price|level|levels|range|history|performance|returns?|week|weekly|month|monthly|so far|running|risk) ").containsMatchIn(t) ||
                PeriodMove.asked(text) != null || Moves.asked(text) != null || Lookback.time(text) != null || Lookback.prevAsked(text) ||
                Pivots.asked(text) || OpeningRange.asked(text) || Momentum.asked(text) || DayStory.asked(text) || Gap.asked(text) || Streak.asked(text))
        // "If I bought the 24500 CE at 120, what is my profit at 24700": the payoff sum, not the account.
        val payoff = Payoff.asked(text) != null
        // "How many trend days this month", "the last 3 expiries": the market's remembered sessions (it never reads a "my").
        val memory = MarketMemory.asked(text) != null
        // "How was the market this week": the indices' own week ([PeriodMove]), not Boss's history (routing audit, 5 Oct).
        val marketSpan = rx(" (the )?(market|markets|indices|bazaar|bazar) ").containsMatchIn(t) && !rx(" (my|mine|our|i|me|we) ").containsMatchIn(t) &&
            PeriodMove.asked(text) != null
        // "Has the gap filled?" (routing round 11: read as his order fills): the index's gap today, not the account.
        val gapFill = rx(" (gap|gaps|gapped|gapup|gapdown) ").containsMatchIn(t) && rx(" (fill|fills|filled|filling) ").containsMatchIn(t) &&
            !rx(" (my|mine|our|i|me|we|order|orders|trade|trades|position|positions) ").containsMatchIn(t)
        // "How was yesterday for Nifty", "how was the market yesterday": the index's last session set beside today
        // ([DayCompare]), not Boss's history (routing round 13) - only with an index or the market named, never a "my".
        val marketDay = DayCompare.asked(text) != null ||
            // "What was yesterday's high?" with no index named (round 13: read as Boss's history): the index's prior session.
            Lookback.prevAsked(text) && !rx(" (my|mine|our|i|me|we) ").containsMatchIn(t)
        // ("Wrap up my day" holds the day's P&L: Boss's own, like any account question - review, 4 Oct.)
        val account = DaySummary.asked(text) || DaySummary.asked(said) || TradeReplay.asked(text) != null || MonthReview.asked(text) || Charges.asked(text) || TaxRecords.asked(text) || Exposure.moveAsked(text) != null || Exposure.rankAsked(text) || PositionHealth.asked(said) || BotHealth.asked(said) || Headroom.asked(said) != null || SaidAbout.asked(said) != null || NeedsTrue.asked(said) || MyStreaks.asked(said) || MyNumbers.asked(said) || !priceAsk && !marketFigure && !payoff && !memory && !marketDay && !marketSpan && !gapFill && (ACCOUNT.containsMatchIn(t) || AppAnswers.about(t) && placed?.lots == null)
        val order = if (account) null else placed
        // "Levels on all indices", "how are all the markets": the four indices.
        // (An order's markets are the words as heard: a misheard name never fills one in.)
        val markets = Market.mentioned(if (order != null) heard else text).ifEmpty { if (ALL_INDICES.containsMatchIn(t)) Reasoning.INDICES else emptyList() }
        val topics = LinkedHashSet<Topic>()
        if (order != null) topics += Topic.ORDER
        for ((topic, ws) in TOPIC_WORDS) if (ws.any { t.contains(" $it ") }) topics += topic
        if (topics.isEmpty() || topics == setOf(Topic.GREETING) && markets.isNotEmpty()) {
            topics.clear(); if (markets.isNotEmpty()) topics += Topic.OVERVIEW else topics += Topic.OFF_TOPIC
        }
        // "Jarvis, how was your day": the name (or a hello) with a sentence after it is not just a greeting.
        if (topics == setOf(Topic.GREETING)) {
            var rest = t
            repeat(3) { rest = GREET.replace(rest, " ") }
            if (rest.trim().split(" ").count { it.isNotBlank() } >= 3) { topics.clear(); topics += Topic.OFF_TOPIC }
        }
        if (Topic.BACKTEST in topics) { topics.remove(Topic.OVERVIEW); topics.remove(Topic.PATTERNS) }
        // The owner's own trading: unless a backtest is named outright, it is about the account, not the market.
        if (account && !rx(" (backtest|back test|backtested|test this|test it|test the pattern|make it an arm|(create|make|build|write|turn) .*(strategy|arm)) ").containsMatchIn(t)) { topics.clear(); topics += Topic.ACCOUNT }
        else if (order == null && markets.isEmpty() && Topic.BACKTEST !in topics && HELP.containsMatchIn(t)) { topics.clear(); topics += Topic.HELP }
        return Question(text, markets, topics, order, pattern = pattern(t), minutes = when {
            rx(" (1 hour|1h|hourly|60 minute|60m|one hour) ").containsMatchIn(t) -> 60
            rx(" (15 minute|15m|15 min|fifteen minute) ").containsMatchIn(t) -> 15
            else -> null
        })
    }

    private val ALL_INDICES = Regex(" (all|every|each of) (the |my )?(indices|indexes|index|markets) | all of them | across (the )?(indices|markets) ")

    private val SUGGEST = Regex(" (what should i (buy|trade)|what (to|can i|could i) (buy|trade)|suggest (an |a |me |some |any )*(order|trade|buy)|any (trade|setup|order|buy) (now|today|ideas?|for me)|any good (trade|setup)|trade ideas?|give me (a |an )?(trade|order)|which (option|order|trade) (should|to) |best (trade|setup|order) (now|today)|should i buy (anything|something|now)|anything to buy|(what s|whats|what is) your (recommendation|suggestion|pick|call)|what do you (recommend|suggest)|any (recommendations?|suggestions?)( now| today)?) ")

    /** Any pattern named in [t], the ones that cannot be backtested too (for "what is a doji?"). */
    private fun named(t: String): PatternKind? = pattern(t) ?: when {
        t.contains(" doji ") -> PatternKind.DOJI
        t.contains(" inside bar ") || t.contains(" inside candle ") -> PatternKind.INSIDE_BAR
        t.contains(" double top ") -> PatternKind.DOUBLE_TOP
        t.contains(" double bottom ") -> PatternKind.DOUBLE_BOTTOM
        else -> null
    }

    /** A pattern named in [t] (already lower-case, spaced). */
    private fun pattern(t: String): PatternKind? = when {
        t.contains(" bearish engulfing ") -> PatternKind.BEARISH_ENGULFING
        t.contains(" engulfing ") -> PatternKind.BULLISH_ENGULFING
        t.contains(" shooting star ") -> PatternKind.SHOOTING_STAR
        t.contains(" hammer ") -> PatternKind.HAMMER
        t.contains(" three white soldiers ") || t.contains(" three green ") -> PatternKind.THREE_WHITE_SOLDIERS
        t.contains(" three black crows ") || t.contains(" three red ") -> PatternKind.THREE_BLACK_CROWS
        t.contains(" breakdown ") || t.contains(" break down ") -> PatternKind.BREAKOUT_DOWN
        t.contains(" breakout ") || t.contains(" break out ") -> PatternKind.BREAKOUT_UP
        else -> null
    }

    /** "buy 2 lots banknifty 52000 ce" -> an [OrderRequest]; null when the words do not ask for an order. */
    private fun order(t: String): OrderRequest? {
        val buy = rx(" (buy|purchase) ").containsMatchIn(t)
        val sell = rx(" sell(?! off) ").containsMatchIn(t)
        if (!buy && !sell) return null
        // "Sell my 1 lot of Nifty 24500 CE", "square off 2 lots": closing what Boss holds, never a new order.
        if (rx(" (my|mine|existing|square off|squareoff|position|positions) ").containsMatchIn(t)) return null
        // A question about buying ("what if I buy...", "why did I buy...", "how much margin to buy...") is not an order.
        if (rx(" (should|shall|can|could|would|is it|worth|what|why|how|when|where|which|did|does|if|need to|do i|have i|has) ").containsMatchIn(t)) return null
        val market = Market.mentioned(t).firstOrNull { it != Market.VIX }
        val lots = rx(" (\\d+) (lot|lots) ").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: rx(" (one|two|three|four|five) (lot|lots) ").find(t)?.groupValues?.get(1)?.let { listOf("one", "two", "three", "four", "five").indexOf(it) + 1 }
        val right = when {
            rx(" (ce|call|calls) ").containsMatchIn(t) -> "CE"
            rx(" (pe|put|puts) ").containsMatchIn(t) -> "PE"
            else -> null
        }
        val strike = rx(" (\\d{4,6}) ").findAll(t).map { it.groupValues[1].toInt() }.firstOrNull { it >= 1000 }
        val atm = strike == null && rx(" (atm|at the money) ").containsMatchIn(t)
        val missing = buildList {
            if (market == null) add("which index")
            if (lots == null) add("how many lots")
            if (market != Market.GOLD) { if (right == null) add("call or put"); if (strike == null && !atm) add("which strike") }
        }
        return OrderRequest(market, buy, lots, strike, right, missing, atm)
    }
}
