package com.optionslab.engine.signals

import com.optionslab.engine.Right
import java.time.LocalDate
import java.util.Locale

/**
 * ntfy alerts' reader (25 Oct 2026, Boss: "read the message and buy the option of the index on the basis of the message"): a
 * STRICT reading of one message from Boss's ntfy topic. What it reads is either a buy of an index option, an exit of what the
 * arm holds on an index, or a refusal with its reason - never a guess.
 *
 * SIGNAL SOURCE: an external message (whoever posts to the topic). It is NOT a researched or proven strategy; the app only
 * carries it out on the index option chosen by the strike rule, inside every guard.
 *
 * Accepted (any order, any case, extra spaces, a few Hindi words):
 *  - the index: NIFTY, BANKNIFTY (bank nifty, bnf, nifty bank), FINNIFTY (fin nifty, nifty fin service), MIDCPNIFTY (midcap nifty,
 *    nifty midcap select), SENSEX, and Yahoo's symbols (^NSEI, ^NSEBANK, NIFTY_FIN_SERVICE.NS, NIFTY_MID_SELECT.NS, ^BSESN); any other
 *    NIFTY index (NIFTY IT, PHARMA, NEXT 50 ...) is refused as an unknown index;
 *  - the direction: CE / call / bullish / long / up => buy a CE; PE / put / bearish / short / down => buy a PE;
 *  - a strike (a number of 1000 or more), else the strike rule picks one;
 *  - the expiry: "weekly" (the nearest, also the default), "next" (the one after), or a date ("30 oct", "oct 30", "30/10",
 *    "2026-10-30");
 *  - lots ("2 lots", "lots 2"); the entry on the premium ("@ 120", "at 120", "near 120": within 2% of it; "above 120": at or above
 *    it; none: at the market); the stop ("SL 90", "SL 20%"); the target ("TGT 180", "target 180").
 *  - "sell" / "exit" / "close" / "book" with an index: close what the arm holds on that index (an exit only, never a short).
 *  - Boss's sender's alerts (25 Oct): "^NSEI Tripwire Hit: 22600.0! (Moving DOWN)" (the title) buys a NIFTY PE ("UP": a CE) at the
 *    strike rule's strike (the level is the index's, never a strike); "Status: ^NSEI is at ..." check-ins are never trades.
 *    The arm reads the title and the body together ([text]). Boss's JSON signals ([signal]) also name NSE F&O stocks ("ADANIENT.NS",
 *    25 Oct: "add normal stocks"): the option they name is bought (its strike required); words alone stay index-only.
 * Refused, with the reason: no index or two of them, both CE and PE, no direction, stocks, futures, MCX, crypto, BANKEX,
 * a number not understood, prices that contradict each other, buy and exit in one message, negative or conditional words.
 * Pure.
 */
object NtfyParser {
    /** Which expiry the message asked for. */
    sealed class Expiry {
        /** The nearest listed expiry (weekly, or none given). */
        data object Nearest : Expiry()
        /** The one after the nearest. */
        data object Next : Expiry()
        /** That date (it must be listed). */
        data class On(val date: LocalDate) : Expiry()
    }

    /** How the entry is read: within 2% of the level, at or above it, or at the market. */
    enum class Trig(val key: String, val words: String) {
        ZONE("near", "near"), ABOVE("above", "above"), MARKET("market", "at market"),
        /** A JSON signal's premium: bought at the market while the premium is at most [UPTO] over it (never chased further). */
        UPTO("upto", "at most 5% over");
        companion object { fun of(k: String?): Trig = entries.firstOrNull { it.key == k } ?: MARKET }
    }

    /** The strike rule: the ATM strike, one strike in the money, or one out of the money. */
    enum class StrikeRule(val key: String, val word: String, val label: String) {
        ATM("atm", "atm", "ATM"), ITM1("itm1", "itm", "1 ITM"), OTM1("otm1", "otm", "1 OTM");
        companion object { fun of(k: String?): StrikeRule = entries.firstOrNull { it.key == k } ?: ATM }
    }

    sealed class Parsed {
        /** Buy [right] of [index]. Every field but those two may be missing (the arm's defaults then apply). */
        data class Buy(
            val index: String, val right: Right, val strike: Double? = null, val expiry: Expiry = Expiry.Nearest, val lots: Int? = null,
            val trig: Trig = Trig.MARKET, val entry: Double? = null, val stop: Double? = null, val stopPct: Double? = null,
            val target: Double? = null,
            /** "ATM" / "ITM" / "OTM" written in the message (over the arm's strike rule); null: the arm's rule. */
            val pick: StrikeRule? = null,
            /** A JSON signal's stop and first target on the INDEX (exits when the index crosses them; the premium stop still rests). */
            val indexStop: Double? = null, val indexTarget: Double? = null,
            /** A JSON signal's own time (tsIST), its id and its sender's words ("IraThinker v51 · ORB · score 0.7"). */
            val sentAt: java.time.LocalDateTime? = null, val signalId: String? = null, val from: String? = null,
            /** 25 Oct (Boss: "add normal stocks"): [index] is an NSE F&O stock (a JSON signal's "X.NS"), its option bought. */
            val stock: Boolean = false,
            /**
             * 26 Oct (IraThinker review): the source rule's own exits, on the option's PREMIUM. [srcStopPct] its stop as a percent under
             * the fill (mom60's "premiumStop" over its "premium": 30%), or [srcStop] as a premium when no premium came to convert it;
             * [srcTarget] a premium target; [exitBy] the time it is out ("exitBy") and [holdMin] its hold in minutes from the fill.
             * The arm only ever makes its own exits TIGHTER or EARLIER with them ([NtfyRules.sourceExits]): never a wider stop.
             */
            val srcStopPct: Double? = null, val srcStop: Double? = null, val srcTarget: Double? = null,
            val exitBy: java.time.LocalDateTime? = null, val holdMin: Int? = null,
            /** The source strategy for the record ("IraThinker mom60"), or null. */
            val strategy: String? = null,
            /** The sender marked it "paper": true: paper only, never told to the live path. */
            val paper: Boolean = false,
            /** The sender said its strike is estimated (guessed by a price band), not read from the exchange's list. */
            val strikeGuess: Boolean = false,
        ) : Parsed() {
            /** "NIFTY 24500 CE (weekly) at market, stop 90, target 180". */
            fun line(): String = listOfNotNull(index, strike?.let { num(it) } ?: pick?.label ?: "strike by the rule", right.name).joinToString(" ") +
                " (" + when (expiry) { Expiry.Nearest -> "nearest expiry"; Expiry.Next -> "next expiry"; is Expiry.On -> "${expiry.date}" } + ")" +
                (lots?.let { " $it lot${if (it == 1) "" else "s"}" } ?: "") +
                (if (trig == Trig.MARKET || entry == null) " at market" else " ${trig.words} ${num(entry)}") +
                ", stop " + (stop?.let { num(it) } ?: stopPct?.let { "${num(it)}%" } ?: "the arm's") +
                ", target " + (target?.let { num(it) } ?: "the arm's") +
                (if (indexStop != null || indexTarget != null) "; index stop ${indexStop?.let { num(it) } ?: "-"}, index target ${indexTarget?.let { num(it) } ?: "-"}" else "") +
                sourceWords()

            /** 26 Oct: "; the source rule: stop 30%, out by 10:45 · IraThinker mom60 (paper only)", or "" when it carries none. */
            fun sourceWords(): String {
                val rule = listOfNotNull(srcStopPct?.let { "stop ${num(it)}%" } ?: srcStop?.let { "stop ${num(it)}" }, srcTarget?.let { "target ${num(it)}" },
                    exitBy?.let { "out by %02d:%02d".format(Locale.ENGLISH, it.hour, it.minute) }, holdMin?.let { "hold $it min" })
                val tail = listOfNotNull(strategy, if (paper) "paper only" else null, if (strikeGuess) "strike estimated by the sender" else null)
                return (if (rule.isEmpty()) "" else "; the source rule: " + rule.joinToString(", ")) + (if (tail.isEmpty()) "" else " · " + tail.joinToString(" · "))
            }
        }

        /** Close what the arm holds on [index] (only [right] when named). Reduce-only. */
        data class Exit(val index: String, val right: Right? = null, val signalId: String? = null) : Parsed() {
            fun line(): String = "exit $index" + (right?.let { " ${it.name}" } ?: "")
        }

        /** Not traded, and why. */
        data class Refused(val reason: String) : Parsed()

        /**
         * 26 Oct (IraThinker review): information the arm cannot trade and never refuses as an error - a sell-side or multi-leg
         * trade (SELL_STRANGLE: IraAlgo only buys options), a price alert, the market's context. Read once, shown, never traded.
         */
        data class Info(val reason: String) : Parsed()
    }

    /** What an info message says when the sender asks for a sale or several legs (26 Oct). */
    const val ONLY_BUYS = "information, not traded: IraAlgo only buys options"

    /** A signal's premium may have risen this much before the buy (more: not chased). */
    const val UPTO = 0.05

    /**
     * One ntfy message read (25 Oct): a JSON signal ("{"type":"signal",...}" in the body, Boss's IraThinker) by [signal], else the
     * title and body as words by [parse].
     */
    fun read(title: String?, message: String?, today: LocalDate): Parsed {
        val body = message?.trim().orEmpty()
        return if (body.startsWith("{")) signal(body, today) else parse(text(title, message), today)
    }

    private val SYMBOLS: Map<String, String> = mapOf(
        "^NSEI" to "NIFTY", "NIFTY" to "NIFTY", "NIFTY50" to "NIFTY", "NIFTY 50" to "NIFTY",
        "^NSEBANK" to "BANKNIFTY", "BANKNIFTY" to "BANKNIFTY", "NIFTY BANK" to "BANKNIFTY", "NIFTYBANK" to "BANKNIFTY",
        "FINNIFTY" to "FINNIFTY", "NIFTY_FIN_SERVICE.NS" to "FINNIFTY", "NIFTY FIN SERVICE" to "FINNIFTY",
        "MIDCPNIFTY" to "MIDCPNIFTY", "NIFTY_MID_SELECT.NS" to "MIDCPNIFTY", "NIFTY MID SELECT" to "MIDCPNIFTY",
        "^BSESN" to "SENSEX", "SENSEX" to "SENSEX",
    )

    /** The NSE stock a Yahoo symbol names ("ADANIENT.NS" -> "ADANIENT", "M&M.NS" -> "M&M"), or null (BSE ".BO", an index, anything else). */
    fun stockOf(yahoo: String?): String? {
        val y = yahoo?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (!y.endsWith(".NS") || y.startsWith("^")) return null
        return y.removeSuffix(".NS").takeIf { Regex("[A-Z0-9&-]{1,20}").matches(it) }
    }

    /** The index a signal's symbol names, or null. */
    fun indexOf(symbol: String?): String? = symbol?.trim()?.uppercase(Locale.ROOT)?.let { SYMBOLS[it] }

    /**
     * Boss's IraThinker signal (25 Oct), one JSON object:
     *   {"type":"signal","test":false,"source":"IraThinker","version":51,"strategy":"...","signalId":"...",
     *    "tsIST":"2026-10-23T23:12:44+05:30","symbol":"NIFTY","yahooSymbol":"^NSEI","side":"BUY_PUT","entry":null,"stop":null,
     *    "target1":null,"target2":null,"score":null,"option":{"strike":null,"expiry":null,"kind":"PE","premium":null}}
     * [side] BUY_CALL / BUY_PUT buys a CE / PE; EXIT, EXIT_CALL, EXIT_PUT, SELL or CLOSE closes what the arm holds on the index.
     * entry / stop / target1 are the INDEX's levels (the signal is on the underlying; CLAUDE.md): the stop and target1 close the
     * trade when the index crosses them, beside the premium stop that always rests. option.strike / expiry / kind pick the contract
     * (null: the strike rule, the nearest expiry); option.premium caps the buy at [UPTO] over it. A test signal ("test": true),
     * an unknown symbol, a kind against the side, or levels on the wrong side of each other: read, never traded.
     */
    fun signal(json: String, today: LocalDate): Parsed {
        if (json.length > 4_000) return refuse("a signal too long to read")
        val o = runCatching { com.optionslab.engine.orb.MiniJson.parse(json) }.getOrNull() as? Map<*, *>
            ?: return refuse("a signal that is not valid JSON (check the sender's commas and quotes)")
        if (o["type"] != "signal") return refuse("a JSON message whose type is not \"signal\"")
        // Only an absent or false "test" trades: true, "true", 1 or anything else is a test.
        if (o["test"] != null && o["test"] != false) return refuse("a test signal (\"test\": ${o["test"]}): read, never traded")
        fun dbl(m: Map<*, *>?, k: String): Double? = (m?.get(k) as? Number)?.toDouble()?.takeIf { it.isFinite() }
        fun str(m: Map<*, *>?, k: String): String? = (m?.get(k) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        // 26 Oct (IraThinker review): what can never be bought is information, not an error: a sale, a multi-leg trade, a price
        // alert, the market's context. Read once and shown; never traded.
        info(o, str(o, "strategy")?.uppercase(Locale.ROOT), str(o, "side")?.uppercase(Locale.ROOT), str(o, "symbol"), dbl(o, "level"))?.let { return it }
        val id = str(o, "signalId")
        val a = indexOf(str(o, "yahooSymbol")); val b = indexOf(str(o, "symbol"))
        val named = listOfNotNull(str(o, "yahooSymbol"), str(o, "symbol"))
        if (a != null && b != null && a != b) return refuse("symbol and yahooSymbol name different indices ($b, $a)")
        // 25 Oct (Boss: "add normal stocks"): an NSE stock ("ADANIENT.NS") is an F&O stock whose option is bought; a BSE one is not.
        val yStock = stockOf(str(o, "yahooSymbol"))
        if ((a != null || b != null) && yStock != null) return refuse("symbol and yahooSymbol disagree (an index and the stock $yStock)")
        val stockSym = if (a == null && b == null) yStock else null
        if (a == null && b == null && stockSym == null) return refuse("not an index or NSE stock signal (${named.firstOrNull() ?: "no symbol"}): " +
            "NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX and NSE F&O stocks (\"X.NS\") are traded")
        val u = a ?: b ?: stockSym!!
        val side = str(o, "side")?.uppercase(Locale.ROOT) ?: return refuse("a signal with no side")
        val opt = o["option"] as? Map<*, *>
        val kind = str(opt, "kind")?.uppercase(Locale.ROOT)
        val kindRight = when (kind) { null -> null; "CE", "CALL" -> Right.CE; "PE", "PUT" -> Right.PE; else -> return refuse("an option kind not understood ($kind)") }
        when (side) {
            "EXIT", "SELL", "CLOSE" -> return Parsed.Exit(u, kindRight, id)
            "EXIT_CALL", "SELL_CALL", "CLOSE_CALL" -> return if (kindRight == Right.PE) refuse("an exit of a call whose kind is PE") else Parsed.Exit(u, Right.CE, id)
            "EXIT_PUT", "SELL_PUT", "CLOSE_PUT" -> return if (kindRight == Right.CE) refuse("an exit of a put whose kind is CE") else Parsed.Exit(u, Right.PE, id)
        }
        val right = when (side) {
            "BUY_CALL", "BUY_CE", "LONG" -> Right.CE
            "BUY_PUT", "BUY_PE", "SHORT" -> Right.PE
            else -> return refuse("a side not understood ($side): BUY_CALL, BUY_PUT or EXIT")
        }
        if (kindRight != null && kindRight != right) return refuse("side $side but option kind $kind")
        val strike = dbl(opt, "strike")
        if (stockSym == null && strike != null && strike < 1000) return refuse("an option strike not understood (${num(strike)})")
        if (stockSym != null && (strike == null || strike <= 0)) return refuse("a stock signal needs its option strike")
        val expiry = str(opt, "expiry")?.let { e ->
            val d = runCatching { LocalDate.parse(e.take(10)) }.getOrNull() ?: return refuse("an option expiry not understood ($e)")
            Expiry.On(d)
        } ?: Expiry.Nearest
        val premium = dbl(opt, "premium")?.takeIf { it > 0 }
        val entry = dbl(o, "entry"); val stop = dbl(o, "stop"); val t1 = dbl(o, "target1")
        // The index levels must agree with the side: a call's stop below and target above (a put's the other way round).
        val up = right == Right.CE
        fun below(x: Double?, y: Double?) = x == null || y == null || (if (up) x < y else x > y)
        if (!below(stop, entry)) return refuse("the index stop (${num(stop!!)}) is on the wrong side of the entry (${num(entry!!)}) for a ${right.name}")
        if (!below(entry, t1)) return refuse("the index target (${num(t1!!)}) is on the wrong side of the entry (${num(entry!!)}) for a ${right.name}")
        if (!below(stop, t1)) return refuse("the index stop and target are on the wrong sides for a ${right.name}")
        // tsIST: IraThinker stamps it when it SENDS (SystemFeed.nowIst, moments before the post); the arm counts the earlier of it
        // and ntfy's own time for the stale rule.
        val at = str(o, "tsIST")?.let { ist(it) }
        val from = listOfNotNull(str(o, "source"), (o["version"] as? Number)?.let { "v${it.toInt()}" }, str(o, "strategy"),
            dbl(o, "score")?.let { "score ${num(it)}" }).joinToString(" · ").ifEmpty { null }
        // 26 Oct (IraThinker review): the source rule's own exits on the premium. A field that is there but not understood refuses
        // the signal (strict: never a guess about how it exits).
        fun bad(k: String) = o.containsKey(k) && o[k] != null && dbl(o, k) == null
        for (k in listOf("premiumStop", "premiumStopPct", "stopPct", "premiumTarget", "holdMin", "holdMinutes"))
            if (bad(k)) return refuse("a $k not understood (${o[k]})")
        val pStop = dbl(o, "premiumStop")
        val pPct = dbl(o, "premiumStopPct") ?: dbl(o, "stopPct")
        val pTarget = dbl(o, "premiumTarget")
        if (pStop != null && pStop <= 0) return refuse("a premiumStop not understood (${num(pStop)})")
        if (pPct != null && (pPct <= 0 || pPct >= 100)) return refuse("the stop percentage must be between 0 and 100 (${num(pPct)})")
        if (pTarget != null && pTarget <= 0) return refuse("a premiumTarget not understood (${num(pTarget)})")
        if (pStop != null && premium != null && pStop >= premium) return refuse("the premium stop (${num(pStop)}) is not below the premium (${num(premium)})")
        if (pTarget != null && premium != null && pTarget <= premium) return refuse("the premium target (${num(pTarget)}) is not above the premium (${num(premium)})")
        if (pStop != null && pTarget != null && pStop >= pTarget) return refuse("the premium stop is not below the premium target")
        // The stop as a percent of the premium the sender priced (mom60: 30%), applied to IraAlgo's own fill; a premium stop with no
        // premium to compare stays a premium level.
        val srcPct = pPct ?: if (pStop != null && premium != null) round2((1 - pStop / premium) * 100) else null
        val srcStop = if (srcPct == null) pStop else null
        val hold = (dbl(o, "holdMin") ?: dbl(o, "holdMinutes"))?.let { h ->
            if (h != Math.floor(h) || h < 1 || h > 375) return refuse("a hold not understood (${num(h)} minutes; a whole number from 1 to 375)")
            h.toInt()
        }
        val exitBy = if (o.containsKey("exitBy") && o["exitBy"] != null) {
            val raw = o["exitBy"] as? String
            raw?.trim()?.let { ist(it) } ?: return refuse("an exitBy not understood (${o["exitBy"]})")
        } else null
        if (exitBy != null && at != null && !exitBy.isAfter(at)) return refuse("its exitBy ($exitBy) is not after its own time ($at)")
        val label = str(o, "strategy")?.let { st -> listOfNotNull(str(o, "source"), strategyWords(st)).joinToString(" ") }
        val paper = o.containsKey("paper") && o["paper"] != null && o["paper"] != false
        val guess = guessed(opt) || guessed(o)
        return Parsed.Buy(u, right, strike, expiry, null, if (premium != null) Trig.UPTO else Trig.MARKET, premium, null, null, null, null,
            indexStop = stop, indexTarget = t1, sentAt = at, signalId = id, from = from, stock = stockSym != null,
            srcStopPct = srcPct, srcStop = srcStop, srcTarget = pTarget, exitBy = exitBy, holdMin = hold, strategy = label, paper = paper,
            strikeGuess = guess)
    }

    /** An ISO time with its offset ("2026-10-26T10:45:00+05:30") as IST, or null. */
    private fun ist(s: String): java.time.LocalDateTime? =
        runCatching { java.time.OffsetDateTime.parse(s).atZoneSameInstant(com.optionslab.engine.IST).toLocalDateTime() }.getOrNull()

    private fun round2(x: Double) = Math.round(x * 100) / 100.0

    /** A sender's strategy name in words for the record: "MOM60" -> "mom60", "ORB_BREAKOUT" -> "ORB breakout". */
    fun strategyWords(s: String): String = when (s.uppercase(Locale.ROOT)) {
        "MOM60" -> "mom60"
        "ORB_BREAKOUT" -> "ORB breakout"
        "ORB_BREAKDOWN" -> "ORB breakdown"
        "GAP_FADE" -> "gap fade"
        "MANUAL" -> "manual share"
        else -> s
    }

    /**
     * 26 Oct (IraThinker review, the INFY 1130 CE refusal): the sender says its strike is an estimate, not read from the exchange's
     * list: "strikeEstimated": true, "strikeVerified": false, or "strikeSource" "estimate" / "estimated" / "guess" / "guessed" /
     * "band" (on the option block or the signal).
     */
    private fun guessed(m: Map<*, *>?): Boolean {
        if (m == null) return false
        if (m["strikeEstimated"] == true || m["strikeGuessed"] == true || m["strikeVerified"] == false) return true
        val src = (m["strikeSource"] as? String)?.trim()?.lowercase(Locale.ROOT) ?: return false
        return src in setOf("estimate", "estimated", "guess", "guessed", "band", "price band")
    }

    /**
     * 26 Oct (IraThinker review): a message that can never be bought, as information (null: read on). A sale or a multi-leg trade
     * (SELL_STRANGLE, a straddle, a spread, a condor, any "legs"): IraAlgo only buys options. A SELL / SELL_CALL / SELL_PUT side stays
     * an exit of what the arm holds. IraThinker's price alert and market-flush context are not trade signals.
     */
    private fun info(o: Map<*, *>, strategy: String?, side: String?, symbol: String?, level: Double?): Parsed.Info? {
        val multi = o["legs"] is List<*> || listOf("STRANGLE", "STRADDLE", "SPREAD", "CONDOR", "BUTTERFLY").any { w ->
            side?.contains(w) == true || strategy?.contains(w) == true }
        val sale = side != null && (side.startsWith("SELL_") || side.startsWith("SHORT_")) && side !in setOf("SELL_CALL", "SELL_PUT")
        return when {
            multi || sale -> Parsed.Info("$ONLY_BUYS (${side ?: strategy}: " + (if (multi) "a multi-leg" else "a sell-side") + " trade)")
            strategy == "PRICE_ALERT" -> Parsed.Info("information, not traded: a price alert" +
                (symbol?.let { " on $it" } ?: "") + (level?.let { " at ${num(it)}" } ?: "") + ", not a trade signal")
            strategy == "MARKET_FLUSH" -> Parsed.Info("information, not traded: the market's context (many stocks breaking down together), not a trade signal")
            else -> null
        }
    }

    /** A message longer than this is not one clear order. */
    const val MAX_CHARS = 600

    /** One ntfy message's text as read: its title (Boss's sender puts the direction there) and its body. */
    fun text(title: String?, message: String?): String = listOfNotNull(title?.takeIf { it.isNotBlank() }, message?.takeIf { it.isNotBlank() }).joinToString("\n")

    val INDICES: List<String> = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

    private val ALIASES: Map<String, String> = mapOf(
        "nifty" to "NIFTY", "nifty50" to "NIFTY", "nf" to "NIFTY",
        "banknifty" to "BANKNIFTY", "bnf" to "BANKNIFTY", "niftybank" to "BANKNIFTY",
        "finnifty" to "FINNIFTY", "midcpnifty" to "MIDCPNIFTY", "midcapnifty" to "MIDCPNIFTY", "sensex" to "SENSEX",
        // 25 Oct: Yahoo's index symbols, as Boss's alert sender writes them ("^NSEI Tripwire Hit ...").
        "nsei" to "NIFTY", "nsebank" to "BANKNIFTY", "bsesn" to "SENSEX",
    )

    private val CE_WORDS = setOf("ce", "call", "calls", "bullish", "bull", "long", "up")
    private val PE_WORDS = setOf("pe", "put", "puts", "bearish", "bear", "short", "down")
    private val RIGHT_WORDS = setOf("ce", "pe", "call", "calls", "put", "puts")
    private val EXIT_WORDS = setOf("sell", "exit", "close", "book", "booked", "squareoff")
    private val BUY_WORDS = setOf("buy")
    private val NEGATIVE = setOf("dont", "don", "not", "no", "never", "avoid", "if", "maybe", "wait")
    private val FUTURES = setOf("fut", "futs", "future", "futures")
    private val MCX = setOf("mcx", "crude", "crudeoil", "naturalgas", "natgas", "gold", "goldm", "silver", "silverm", "copper", "zinc",
        "aluminium", "lead", "nickel")
    private val CRYPTO = setOf("btc", "bitcoin", "eth", "ethereum", "crypto", "usdt", "sol", "doge", "xrp")
    private val ENTRY_ZONE = setOf("@", "at", "near", "around", "entry")
    private val ENTRY_ABOVE = setOf("above", "abv")
    private val STOP_WORDS = setOf("sl", "stop", "stoploss")
    private val TARGET_WORDS = setOf("tgt", "target", "targets", "tp")
    private val LOT_WORDS = setOf("lot", "lots")
    /** Words that may stand between a keyword and its number ("SL @ 90", "target of 180", "lots = 2"). */
    private val BRIDGE = setOf("@", "at", "rs", "inr", "of", "is", "=")
    /** Words that make a number an index LEVEL ("NIFTY PE below 24400"), never a strike: such a message is refused. */
    private val LEVEL_WORDS = setOf("below", "under", "breaks", "break", "breakdown", "breakout", "support", "resistance", "spot", "level",
        "levels", "crosses", "cross", "sustains", "sustain", "holds")
    /**
     * 25 Oct (review 2): the words that may follow "nifty" (or "nifty50"): the reader's own words. Any other word there names another
     * index ("NIFTY MNC", "NIFTY CONSUMER DURABLES", "NIFTY_TOTAL_MKT.NS" ...): refused as an unknown index, never read as NIFTY.
     */
    private val AFTER_NIFTY: Set<String> by lazy {
        CE_WORDS + PE_WORDS + EXIT_WORDS + BUY_WORDS + NEGATIVE + FUTURES + ENTRY_ZONE + ENTRY_ABOVE + STOP_WORDS + TARGET_WORDS + LOT_WORDS +
            BRIDGE + LEVEL_WORDS + setOf("atm", "itm", "otm", "weekly", "week", "next", "cmp", "market", "tripwire", "hit", "moving", "ns", "index",
            "option", "options", "opt", "now", "today", "expiry", "exp", "for", "looks", "and", "or", "to", "the", "%", "status", "checkin", "check",
            "intraday", "only", "alert", "alerts", "signal", "signals", "update", "trade", "strike", "qty", "ka", "ki", "ke", "me", "mein", "lo", "le",
            "nifty", "banknifty", "niftybank", "finnifty", "midcpnifty", "sensex", "bnf")
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val HINDI = listOf(
        "बैंक निफ्टी" to " banknifty ", "बैंकनिफ्टी" to " banknifty ", "फिन निफ्टी" to " finnifty ", "निफ्टी" to " nifty ", "सेंसेक्स" to " sensex ",
        "ख़रीदें" to " buy ", "खरीदें" to " buy ", "खरीदो" to " buy ", "बेचें" to " sell ", "बेचो" to " sell ", "कॉल" to " call ", "पुट" to " put ",
        "तेज़ी" to " bullish ", "तेजी" to " bullish ", "मंदी" to " bearish ", "निकलो" to " exit ", "स्टॉप लॉस" to " sl ", "स्टॉपलॉस" to " sl ",
        "टारगेट" to " target ", "लक्ष्य" to " target ", "लॉट" to " lot ", "₹" to " ",
    )

    private fun rx(p: String) = Regex(p)

    private val GROUPED_STRIKE = rx("\\b\\d{1,3}(?:,\\d{2})*,\\d{3}(?=\\s?(?:ce|pe|calls?|puts?)(?![a-z]))")

    /** The marker a NIFTY sector index leaves in the text ([SECTOR]): such a message is refused, never read as NIFTY. */
    private const val UNKNOWN_INDEX = "unknownindex"
    /** What an unknown Hindi word leaves in the text (ignored, except right after "nifty"). */
    private const val HINDI_WORD = "xhindi"

    /**
     * 25 Oct (review): "nifty" followed by any other index's word - NIFTY IT, NIFTY FIN (alone), PHARMA, AUTO, METAL, NEXT 50, REALTY,
     * FMCG, PSU BANK, MEDIA, ENERGY, INFRA, SMALLCAP, MIDCAP 100 / 150, NIFTY 100 / 200 / 500 ... - names an index that is not traded.
     * Run after the traded indices' own joins (NIFTY FIN SERVICE, NIFTY MIDCAP SELECT, NIFTY BANK) and before "next week" is joined,
     * so "NIFTY next week CE" stays a NIFTY trade. "NIFTY 50" is NIFTY; "NIFTY 24500 CE" a strike.
     */
    private val SECTOR = rx("\\bnifty\\s*(?:fin(?:ancial)?|it|pharma|auto|metal|next\\s*(?:50|fifty)|realty|fmcg|psu|media|energy|infra|" +
        "small\\s*cap|smlcap|small|mid\\s*cap|mid|100|150|200|500|pvt|private|healthcare|oil|cpse|commodities|consumption|services?|serv)\\b")

    private val JOINS = listOf(
        // 25 Oct (review): Yahoo's FINNIFTY and MIDCPNIFTY ("NIFTY_FIN_SERVICE.NS", "NIFTY_MID_SELECT.NS") before anything reads "nifty".
        rx("\\bnifty\\s*fin(?:ancial)?\\s*serv\\w*") to "finnifty", rx("\\bnifty\\s*mid\\s*(?:cap)?\\s*select\\b") to "midcpnifty",
        rx("\\bbank\\s*nifty\\b") to "banknifty", rx("\\bnifty\\s*bank\\b") to "niftybank", rx("\\bfin\\s*nifty\\b") to "finnifty",
        rx("\\bmid\\s*(?:cap|cp)\\s*nifty\\b") to "midcpnifty", SECTOR to " $UNKNOWN_INDEX ",
        rx("\\bnifty\\s*50\\b") to "nifty50", rx("\\bstop\\s*-?\\s*loss\\b") to "stoploss", rx("\\bs\\s*/\\s*l\\b") to "sl",
        rx("\\bsq(?:uare)?\\s*-?\\s*off\\b") to "squareoff", rx("\\bbuy\\s+above\\b") to "above", rx("\\bnext\\s+week(?:ly)?\\b") to "next",
        rx("\\b(?:this|current|curr)\\s+week\\b") to "weekly", rx("\\bdo\\s*n[o']?t\\b") to "dont", rx("\\bcan'?t\\b") to "not",
        rx("\\b(?:t|tgt|target)\\s?1\\b") to "tgt",
    )

    /** [raw] lower-cased, Hindi read, numbers joined ("24,500" -> 24500), ISO dates kept apart, other symbols dropped. */
    internal fun normalize(raw: String): String {
        var s = raw
        for ((a, b) in HINDI) s = s.replace(a, b)
        // Devanagari digits read as digits ("२४५००" -> 24500), never dropped.
        s = s.map { c -> if (c in '०'..'९') '0' + (c - '०') else c }.joinToString("")
        // 25 Oct (review 2): a Hindi word the reader does not know leaves a marker (it used to vanish): "निफ्टी आईटी कॉल" is not NIFTY.
        s = s.replace(rx("[\\u0900-\\u097F]+"), " $HINDI_WORD ")
        s = s.lowercase(Locale.ROOT)
        // 25 Oct (review): Yahoo's symbols read as words ("nifty_fin_service.ns" -> "nifty fin service ns") BEFORE the joins below.
        s = s.replace('_', ' ').replace(rx("(?<=[a-z])\\.ns\\b"), " ns")
        // Digit grouping only in a strike ("24,500 CE", "1,02,500 PE"): elsewhere a comma separates numbers ("tgt 180,220").
        s = s.replace(GROUPED_STRIKE) { m -> m.value.replace(",", "") }
        s = s.replace("don't", "dont").replace("’", "'")
        // An ISO date as one token ("2026-10-30" -> "d20261030") before the dashes go.
        s = s.replace(rx("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")) { m -> " d${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]} " }
        // A day/month date as one token ("30/10" -> "dm30.10"), never read as two numbers.
        s = s.replace(rx("(?<![\\d.])(\\d{1,2})\\s*/\\s*(\\d{1,2})(?![\\d.])")) { m -> " dm${m.groupValues[1]}.${m.groupValues[2]} " }
        for ((a, b) in JOINS) s = s.replace(a, b)
        s = s.replace(rx("\\brs\\.?\\s*(?=\\d)"), " ")
        s = s.replace(rx("[^a-z0-9.@%=\\s]"), " ")
        // Digits and letters apart ("24500ce", "ce24500", "30oct"), the date tokens kept whole.
        s = s.replace(rx("(?<![a-z])(\\d+(?:\\.\\d+)?)([a-z])"), "$1 $2").replace(rx("\\b(?!d\\d|dm\\d|nifty50\\b)([a-z]+)(\\d)"), "$1 $2")
        s = s.replace(rx("(?<!\\d)\\.(?!\\d)"), " ")
        return s.replace(rx("\\s+"), " ").trim()
    }

    private data class Tok(val t: String) {
        val n: Double? = if (t.isNotEmpty() && t[0].isDigit()) t.toDoubleOrNull() else null
        val num: Boolean get() = n != null
    }

    private val TOKEN = rx("nifty50|dm\\d{1,2}\\.\\d{1,2}|d\\d{8}|[a-z]+|\\d+(?:\\.\\d+)?|[@%=]")

    private fun tokens(norm: String): List<Tok> = TOKEN.findAll(norm).map { Tok(it.value) }.toList()

    private fun refuse(why: String) = Parsed.Refused(why)

    /**
     * [text] read. [today] gives the year of a date written without one (a date more than a week past is next year's).
     */
    fun parse(text: String, today: LocalDate): Parsed {
        if (text.length > MAX_CHARS) return refuse("too long to be one clear order (over $MAX_CHARS characters)")
        val norm = normalize(text)
        if (norm.isBlank()) return refuse("empty message")
        val toks = tokens(norm)
        val words = toks.map { it.t }.toSet()
        // 25 Oct (Boss's sender): "Status: ^NSEI is at 22600.0 / Regular check-in" is a status line, never a trade.
        if ("status" in words || "checkin" in words || ("check" in words && "in" in words)) return refuse("a status check-in, not a trade")
        val unknown = UNKNOWN_INDEX in words || toks.indices.any { i ->
            val nx = toks.getOrNull(i + 1) ?: return@any false
            when (toks[i].t) {
                "nifty", "nifty50" -> !nx.num && nx.t !in AFTER_NIFTY && !nx.t.startsWith("dm") && !Regex("d\\d{8}").matches(nx.t) &&
                    MONTHS.none { nx.t.startsWith(it) }
                // "Nifty Financial Services Ex-Bank" is another index than FINNIFTY.
                "finnifty" -> nx.t == "ex"
                else -> false
            }
        }
        if (unknown) return refuse("unknown index: a NIFTY sector or other index (IT, PHARMA, NEXT 50, MIDCAP 100 ...) is not traded; " +
            "only NIFTY 50, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX")
        if (words.any { it in NEGATIVE }) return refuse("negative or conditional wording (don't, not, avoid, if, wait) - not traded")
        if (words.any { it in CRYPTO }) return refuse("crypto is not traded")
        if (words.any { it in MCX }) return refuse("MCX is not traded (index options only)")
        if (words.any { it in FUTURES }) return refuse("futures are not traded (index options only)")
        if ("bankex" in words) return refuse("BANKEX is not traded (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX only)")
        val indices = toks.mapNotNull { ALIASES[it.t] }.distinct()
        val ce = words.any { it in CE_WORDS }
        val pe = words.any { it in PE_WORDS }
        val exit = words.any { it in EXIT_WORDS }
        if (exit) {
            if (words.any { it in BUY_WORDS }) return refuse("both buy and sell/exit in one message")
            if (indices.size > 1) return refuse("names two indices (${indices.joinToString(" and ")})")
            val u = indices.firstOrNull() ?: return refuse("an exit that names no index")
            if (ce && pe) return refuse("both CE and PE in one exit")
            return Parsed.Exit(u, if (ce) Right.CE else if (pe) Right.PE else null)
        }
        if (indices.size > 1) return refuse("names two indices (${indices.joinToString(" and ")})")
        val u = indices.firstOrNull() ?: return refuse("no index named (only NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX options are traded; " +
            "stocks are not)")
        if (ce && pe) return refuse("both CE and PE (call and put) in one message")
        if (!ce && !pe) return refuse("no direction (CE / PE, call / put, bullish / bearish)")
        val right = if (ce) Right.CE else Right.PE

        var trig = Trig.MARKET
        var entry: Double? = null
        var stop: Double? = null
        var stopPct: Double? = null
        var target: Double? = null
        var lots: Int? = null
        var expiry: Expiry = Expiry.Nearest
        var expirySet = false
        var market = false
        var pick: StrikeRule? = null
        val used = HashSet<Int>()

        fun numAfter(i: Int): Int {
            var k = i + 1
            while (k < toks.size && toks[k].t in BRIDGE && !toks[k].num) k++
            return if (k < toks.size && toks[k].num && k !in used) k else -1
        }
        fun setExpiry(e: Expiry): String? {
            if (expirySet && e != expiry) return "two different expiries"
            expiry = e; expirySet = true; return null
        }
        fun date(d: Int, m: Int, y: Int?): LocalDate? = runCatching {
            val x = LocalDate.of(y ?: today.year, m, d)
            if (y == null && x.isBefore(today.minusDays(7))) x.plusYears(1) else x
        }.getOrNull()

        var i = 0
        while (i < toks.size) {
            val w = toks[i].t
            when {
                w in ENTRY_ZONE || w in ENTRY_ABOVE -> {
                    // A number right before CE / PE is the strike ("at 24500 CE"), never the entry.
                    val k = numAfter(i).takeIf { it >= 0 && toks.getOrNull(it + 1)?.t !in RIGHT_WORDS } ?: -1
                    if (k >= 0) {
                        if (entry != null) return refuse("two entry prices")
                        entry = toks[k].n; trig = if (w in ENTRY_ABOVE) Trig.ABOVE else Trig.ZONE; used += k; i = k
                    }
                }
                w == "cmp" || w == "market" -> market = true
                w == "atm" || w == "itm" || w == "otm" -> {
                    val r = StrikeRule.entries.first { it.word == w }
                    if (pick != null && pick != r) return refuse("two strike choices (ATM / ITM / OTM)")
                    pick = r
                }
                w in STOP_WORDS -> {
                    val k = numAfter(i)
                    if (k < 0) return refuse("a stop word with no number after it")
                    if (stop != null || stopPct != null) return refuse("two stops")
                    if (toks.getOrNull(k + 1)?.t == "%") { stopPct = toks[k].n; used += k + 1 } else stop = toks[k].n
                    used += k; i = k
                }
                w in TARGET_WORDS -> {
                    val k = numAfter(i)
                    if (k < 0) return refuse("a target word with no number after it")
                    if (target != null) return refuse("two targets")
                    target = toks[k].n; used += k; i = k
                    // "TGT 180 / 220": the first is the target; the later ones are not used.
                    while (i + 1 < toks.size && toks[i + 1].num && toks[i + 1].n!! < 1000) { used += i + 1; i++ }
                }
                w in LOT_WORDS -> {
                    // "2 lots" (the number before) or "lots 2" (after).
                    val before = (i - 1).takeIf { it >= 0 && toks[it].num && it !in used }
                    val k = before ?: numAfter(i).takeIf { it >= 0 }
                    if (k == null) return refuse("a lot word with no number")
                    if (lots != null) return refuse("two lot sizes")
                    val n = toks[k].n!!
                    if (n != Math.floor(n) || n < 1 || n > 50) return refuse("lots must be a whole number from 1 to 50")
                    lots = n.toInt(); used += k
                    if (k > i) i = k
                }
                w == "weekly" || w == "week" -> setExpiry(Expiry.Nearest)?.let { return refuse(it) }
                w == "next" -> setExpiry(Expiry.Next)?.let { return refuse(it) }
                w.startsWith("dm") && w.length > 2 && w[2].isDigit() -> {
                    val (d, m) = w.removePrefix("dm").split(".").map { it.toInt() }
                    val x = date(d, m, null) ?: return refuse("not a date: ${d}/${m}")
                    setExpiry(Expiry.On(x))?.let { return refuse(it) }
                }
                w.length == 9 && w[0] == 'd' && w.drop(1).all { it.isDigit() } -> {
                    val x = date(w.substring(7, 9).toInt(), w.substring(5, 7).toInt(), w.substring(1, 5).toInt()) ?: return refuse("not a date")
                    setExpiry(Expiry.On(x))?.let { return refuse(it) }
                }
                MONTHS.any { w.startsWith(it) } && w.length <= 9 && monthWord(w) -> {
                    val m = MONTHS.indexOfFirst { w.startsWith(it) } + 1
                    // "30 oct" (the day before) or "oct 30" (after); an optional year after.
                    val b = (i - 1).takeIf { it >= 0 && toks[it].num && it !in used && (toks[it].n ?: 0.0) in 1.0..31.0 }
                    val a = (i + 1).takeIf { b == null && it < toks.size && toks[it].num && (toks[it].n ?: 0.0) in 1.0..31.0 }
                    val dk = b ?: a ?: return refuse("a month with no day")
                    used += dk
                    val yk = (maxOf(i, dk) + 1).takeIf { it < toks.size && toks[it].num && (toks[it].n ?: 0.0) in 2000.0..2100.0 }
                    yk?.let { used += it }
                    val x = date(toks[dk].n!!.toInt(), m, yk?.let { toks[it].n!!.toInt() }) ?: return refuse("not a date")
                    setExpiry(Expiry.On(x))?.let { return refuse(it) }
                    i = maxOf(i, dk, yk ?: 0)
                }
            }
            i++
        }
        // 25 Oct (Boss's sender): "^NSEI Tripwire Hit: 22600.0! (Moving DOWN)": its numbers are the index's level, never a strike
        // or a premium - the strike rule picks the strike.
        if ("tripwire" in words) toks.indices.filter { toks[it].num }.forEach { used += it }
        // What numbers are left: one strike (1000 or more) at most; any other number is not understood.
        val left = toks.indices.filter { toks[it].num && it !in used }
        val strikes = left.filter { toks[it].n!! >= 1000 }.map { toks[it].n!! }.distinct()
        val odd = left.filter { toks[it].n!! < 1000 }
        if (words.contains("%") && stopPct == null) return refuse("a percentage not understood (only 'SL 20%')")
        if (odd.isNotEmpty()) return refuse("a number not understood (${num(toks[odd.first()].n!!)}): write @ for the entry, SL for the stop, TGT for the target")
        if (strikes.size > 1) return refuse("two strikes named (${strikes.joinToString(" and ") { num(it) }})")
        // A level word beside a big number: an index level, not a strike (strict: refused, never guessed).
        if (strikes.isNotEmpty() && words.any { it in LEVEL_WORDS }) return refuse("names an index level (below / breaks / support ...), not a strike - not traded")
        // A premium of 1000 or more is an index level written as a price ("above 24500"): refused.
        listOfNotNull(entry, stop, target).firstOrNull { it >= 1000 }?.let { return refuse("a price of ${num(it)} looks like an index level, not a premium") }
        val strike = strikes.firstOrNull()
        stopPct?.let { if (it <= 0 || it >= 100) return refuse("the stop percentage must be between 0 and 100") }
        listOfNotNull(entry, stop, target).firstOrNull { it <= 0 }?.let { return refuse("a price of zero") }
        val e = entry
        if (market && e != null) return refuse("both a price and 'at market'")
        if (pick != null && strike != null) return refuse("both a strike and ATM / ITM / OTM")
        if (e != null && stop != null && stop!! >= e) return refuse("the stop (${num(stop!!)}) is not below the entry (${num(e)})")
        if (e != null && target != null && target!! <= e) return refuse("the target (${num(target!!)}) is not above the entry (${num(e)})")
        if (stop != null && target != null && stop!! >= target!!) return refuse("the stop is not below the target")
        return Parsed.Buy(u, right, strike, expiry, lots, if (e == null) Trig.MARKET else trig, e, stop, stopPct, target, pick)
    }

    private fun monthWord(w: String): Boolean = MONTHS.any { m -> w == m || (w.startsWith(m) && FULL_MONTHS.any { it.startsWith(w) }) }
    private val FULL_MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "sept", "october",
        "november", "december")

    /** "280", "280.5". */
    fun num(x: Double): String = if (x == Math.floor(x) && !x.isInfinite()) x.toLong().toString() else String.format(Locale.ENGLISH, "%.2f", x).trimEnd('0').trimEnd('.')
}
