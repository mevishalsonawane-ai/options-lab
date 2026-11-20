package com.optionslab.engine.orb

import com.optionslab.engine.Kite
import com.optionslab.engine.mcx.McxArmRules
import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.mcx.McxMorningRules
import com.optionslab.engine.mcx.McxTrendRules
import com.optionslab.engine.mcx.McxUsSilverRules
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs

/**
 * The live path of every paper strategy that had none (18 Oct 2026, Boss: "What ever strategies are present for paper, should be
 * present for live too"; "I want all be present for live too, i will switch it off, if i need it").
 *
 * Each of these arms keeps trading its own paper book by its own rules, exactly as before; its plain-rules paper record stays its
 * own. Its LIVE path MIRRORS that paper trade at Zerodha: when its paper buy fills, the same contract (the same signal on the
 * underlying and the same strike rule - the arm's own choice), the same number of lots (never more), and the same exits - each
 * paper exit (stop, target, ladder, time stop, square-off, index exit, Supertrend gate) sells the live trade, a partial paper sale
 * sells the same share live, and every stop or lock level the paper trade moves to moves the Zerodha stop to the same level on the
 * live fill ([level]: the same percentage of the price paid - or, for the ORB family's point stops, the same points).
 *
 * Who it is for: the arms in [ARMS]. Jarvis's own free-form trade ideas (IraNewsTrades' own picks, the forge's ideas) are NOT
 * strategies and stay on paper (CLAUDE.md, kept on 18 Oct): no key here names them.
 *
 * The rules here are pure (no clock, no network, no orders); the app's ArmLive carries them out with the existing live machinery.
 */
object LiveMirror {
    private const val EPS = 1e-9

    /** The order tag of every mirrored live order (its own, so no other bot's lost-reply search ever adopts one of them). */
    const val TAG = "iraarm"

    /** The mirror's own NSE square-off backstop for an intraday (MIS) position: 15:15 (Boss's), whatever its paper arm does. */
    val SQUARE_OFF: LocalTime = LocalTime.of(15, 15)

    /** NSE's derivative segments here (NIFTY and the others on NFO, SENSEX on BFO). */
    private val NSE = setOf("NFO", "BFO")

    /** On its contract's expiry day an NSE option goes by 15:05 (CLAUDE.md's expiry square-off). */
    val EXPIRY_SQUARE_OFF: LocalTime = LocalTime.of(15, 5)

    /**
     * How a paper level becomes the live one: [PCT] the same share of the price paid (-15%, the 30/60/100 ladder, +5% once +25%,
     * the 25% trail); [POINTS] the same points from the price paid (the ORB family's -40 / +40 / +80).
     */
    enum class Scale { PCT, POINTS }

    /**
     * How the stop rests at Zerodha: [SL] a real SL order with a limit (intraday, MIS - it lives in the exchange's own book while
     * the session runs); [GTT] a single-leg GTT, the only broker-side order that outlives the session, for a carried (NRML)
     * position - never both for one position, so two resting exits can never fill together.
     */
    enum class Rest { SL, GTT }

    /**
     * One mirrored arm: its switch [key] (stable: "Trade live" is kept by it), its [label], the [family] that hosts its paper book,
     * the [scale] of its levels, its [exchange] and [product] (as its paper orders), how its stop [rest]s at Zerodha, and its
     * [signal] source in words (CLAUDE.md: signal on the underlying, trade the option); [defaultOn] its "Trade live" before Boss
     * sets it ([DEFAULT_ON], Boss's 18 Oct choice) - except a rule whose signal is read from an option's own price (CLAUDE.md: such a
     * rule is switched on only by Boss), which starts off.
     */
    data class Spec(
        val key: String, val label: String, val family: String, val scale: Scale = Scale.PCT, val exchange: String = "NFO",
        val product: String = "MIS", val rest: Rest = Rest.SL, val signal: String = "the index", val defaultOn: Boolean = DEFAULT_ON,
        /**
         * 18 Oct (Boss): an arm whose own rule has no stop on the premium (its stop is on the index, or it has none) rests a protective
         * "safety stop" at Zerodha [SAFETY_PCT] beyond the live fill when its paper trade gives no premium stop - protection only.
         */
        val safety: Boolean = false,
        /**
         * Review fix (18 Oct): the arm's paper stop or trail is judged on a minute's CLOSE (the Hero arm's -60%, the Hero ticket's 25%
         * trail): a tick-triggered SL at that level would stop the live trade out on a wick the paper trade holds through. Its paper
         * close-exit drives the live exit instead, and only a labelled "disaster stop" [DISASTER_PCT] beyond the fill rests at Zerodha.
         */
        val closeStop: Boolean = false,
        /**
         * 30 Oct (Boss: "1 lot per arm entry, never more unless Boss sets it"): every arm's live entry is Boss's lots (3 Nov: [armLiveQty],
         * his Bot settings' setting, default [LIVE_LOTS]), cut from
         * its paper lots and said on its entry line - except an arm whose size IS Boss's own setting ([bossLots]: ntfy alerts' "at most
         * N lots" on its page), which keeps its paper lots (the order gates still apply). Before, FT3 alone was cut (to the Bot settings'
         * lot cap) and every other arm sent its paper lots.
         */
        val bossLots: Boolean = false,
    )

    const val PASSED = "passed"
    const val SOLO = "solo"
    const val ORB = "orb"
    const val HERO_TICKET = "hero_ticket"
    /** 21 Oct (Boss: "Add FT3 to the app paper bot until live, when live is started then it should move to live"). */
    const val FT3 = "ft3"
    const val VIX = "vix"
    const val NIGHT = "night"
    /** 25 Oct: ntfy alerts' paper book (an external message, traded on the index option by the strike rule). */
    const val NTFY = "ntfy"
    /** 28 Oct: IraThinker's flow's paper book (the flow's own ORB plan on the index, not proven: R110). */
    const val FLOW = "flow"
    const val MCX = "mcx"
    const val SHADOW = "shadow"
    /** 30 Oct (Boss: "Make them live s5 and csid startergies live too"): S5 Trail's paper book. */
    const val S5 = "s5"
    /** 30 Oct: CISD + network's paper book. */
    const val CISD = "cisd"

    /** Solo (midday)'s key (its paper book is Jarvis's Solo; it is a strategy, not one of Jarvis's own ideas). */
    const val SOLO_MIDDAY = "solo_midday"
    /** The shadow variants Boss re-armed on paper ([ShadowRules]): one live switch for them all. */
    const val SHADOW_PROMOTED = "shadow_promoted"
    /** ntfy alerts (25 Oct): one live switch; OFF by default since 19 Nov 2026 (audit batch 2: only with an access token or Boss's own server). */
    const val NTFY_ALERTS = "ntfy_alerts"

    /**
     * 25 Oct: the Telegram calls arm was removed; a live trade it may still hold (its switch started off, and Boss was on paper only)
     * is kept by ntfy alerts' switch and paper book, so it is still watched and closed - never orphaned.
     */
    fun migratedKey(key: String): String = if (key == "telegram_calls") NTFY_ALERTS else key

    /** Every arm that trades on paper with no live path of its own, given one here. */
    val ARMS: List<Spec> = listOf(
        Spec(DonchianArm.SOURCE, DonchianArm.LABEL, PASSED, signal = "the index's 5-minute Donchian 20 break (Supertrend filter when on)"),
        Spec(BreadthDonchianArm.SOURCE, BreadthDonchianArm.LABEL, PASSED, signal = "4 of the five indices' Donchian breaks together"),
        Spec(OovArm.SOURCE, OovArm.LABEL, PASSED, signal = "the index opening outside yesterday's value area"),
        Spec(Solo2.SOURCE, Solo2.LABEL, PASSED, signal = "the index (Solo 2.0's setups)", safety = true),
        Spec(SOLO_MIDDAY, "Solo (midday)", SOLO, signal = "the index's 12:00 move against its ATR", safety = true),
        Spec(SweepRules.ARM.source, SweepRules.ARM.label, ORB, Scale.POINTS, signal = "BANKNIFTY's opening-range failed break"),
        Spec(RangeFadeRules.ARM.source, RangeFadeRules.ARM.label, ORB, Scale.POINTS, signal = "BANKNIFTY's opening-range edge fade"),
        Spec(HeroRules.ARM.source, HeroRules.ARM.label, ORB, signal = "NIFTY and its ATM straddle on expiry day", closeStop = true),
        Spec(SHADOW_PROMOTED, "Shadow variants re-armed", SHADOW, Scale.POINTS, signal = "BANKNIFTY or NIFTY (each variant's own rule)",
            safety = true),
        Spec(HeroTicketRules.SOURCE, HeroTicketRules.LABEL, HERO_TICKET, signal = "the expiry day at 12:30 (NIFTY, SENSEX)", closeStop = true),
        // 21 Oct: FT3 holds to 15:05 with no stop on paper; live rests only the labelled -80% disaster stop.
        Spec(Ft3Rules.SOURCE, Ft3Rules.LABEL, FT3, signal = "the expiry day's 5-minute marks 11:00-12:55 and the option chain at the mark " +
            "(a strike's price, IV against the ATM's, distance in straddles; five indices)", closeStop = true),
        Spec(VixDivRules.SOURCE, VixDivRules.LABEL, VIX, signal = "the index against India VIX", safety = true),
        Spec(NightRules.SOURCE, NightRules.LABEL, NIGHT, product = "NRML", rest = Rest.GTT, signal = "the index's close and the day-end OI build-up",
            safety = true),
        // 25 Oct: an external message, not a researched rule. 19 Nov 2026 (audit batch 2: ntfy's live gate): its live switch starts
        // OFF - anyone who learns the topic can post a message - and the app switches it on only with an access token or Boss's own
        // server set ([com.optionslab.app.data.NtfyArm]); then still only in Live, with AI trades on, Boss's PIN and its paper arm on.
        Spec(NTFY_ALERTS, "ntfy alerts", NTFY, signal = com.optionslab.engine.signals.NtfyRules.SIGNAL, bossLots = true, defaultOn = false),
        // 28 Oct (Boss: "just take orders from flow"): IraThinker's flow - not proven (R110). 30 Oct: ON by default too (Boss).
        Spec(com.optionslab.engine.signals.FlowRules.SOURCE, com.optionslab.engine.signals.FlowRules.LABEL, FLOW,
            signal = com.optionslab.engine.signals.FlowRules.SIGNAL),
        // 30 Oct (Boss: "Make them live s5 and csid startergies live too"; CLAUDE.md, 30 Oct): the two arms that failed the gates
        // by Boss's exception, live only through the same gates as every arm. S5 Trail's stop is its cell's rupee stop resting in
        // the paper book (the same share of the price on the live fill), its 60% lock a paper exit the live trade follows.
        // (Deep review: its rupee stop can be under one tick on a cheap option - no paper stop at all - so the labelled safety stop
        // rests at Zerodha then; with a paper stop that one rests, scaled to the live fill.)
        Spec(S5Trail.SOURCE, S5Trail.LABEL, S5, signal = "the index (R123's network at 09:20-09:30; the option chain as context)", safety = true),
        // CISD's stop is on the index (no premium stop until its lock rests): a labelled safety stop rests at Zerodha (protection).
        Spec(CisdNet.SOURCE, CisdNet.SHOWN, CISD, signal = "the index's 5-minute CISD (ICT's reading), R123's network as the filter",
            safety = true),
        Spec(McxEveRules.SOURCE, McxEveRules.LABEL, MCX, exchange = "MCX", product = "NRML", rest = Rest.GTT, signal = "the NATURALGAS future's evening break"),
        Spec(McxMorningRules.SOURCE, McxMorningRules.LABEL, MCX, exchange = "MCX", product = "NRML", rest = Rest.GTT, signal = "the SILVERM future's morning"),
        Spec(McxTrendRules.SOURCE, McxTrendRules.LABEL, MCX, exchange = "MCX", product = "NRML", rest = Rest.GTT, signal = "the MCX future's 12-month trend",
            safety = true),
        Spec(McxUsSilverRules.SOURCE, McxUsSilverRules.LABEL, MCX, exchange = "MCX", product = "NRML", rest = Rest.GTT, signal = "the SILVERM future, US night"),
    )

    /** The arm with [key], or null (an unknown key never trades live). */
    fun spec(key: String): Spec? = ARMS.firstOrNull { it.key == key }

    /**
     * Boss's 18 Oct words: each new live switch starts ON ("i will switch it off, if i need it"). It still trades live only with the
     * app in Live AND "AI trades go live" on - which only going Live with Boss's PIN turns on - and never in the paper-only period.
     */
    const val DEFAULT_ON = true

    /** Whether [key]'s "Trade live" is on, from Boss's saved choices ([saved]: key -> on; a key never set is its [Spec.defaultOn]). */
    fun on(key: String, saved: Map<String, Boolean>): Boolean = spec(key)?.let { saved[key] ?: it.defaultOn } ?: false

    // ---- the shadow (18 Oct, Boss: "add a shadow button, so that even i move to live, until is on, it will not trade") ----------

    /**
     * 30 Oct (Boss: "make all arms as live ... dont keep any in paper only or record only, make all live if i move to live market"):
     * each arm's live Shadow now starts OFF, so going Live (with Boss's PIN and "AI trades go live") trades every arm that is on. An
     * arm Boss put in shadow himself keeps his choice (it is saved); turning Shadow on never needs the PIN.
     */
    const val SHADOW_DEFAULT = false

    /** Whether [key]'s Shadow is on, from Boss's saved choices ([saved]: key -> on); an arm never set is [SHADOW_DEFAULT]; unknown: on. */
    fun shadowOn(key: String, saved: Map<String, Boolean>): Boolean =
        // (Solo (midday) is Jarvis's Solo: it starts in shadow, and the app keeps it there until it is proven, whatever is saved.)
        spec(key) == null || (saved[key] ?: (key == SOLO_MIDDAY || SHADOW_DEFAULT))

    /**
     * A shadow trade's exit price, as the live mirror would have filled it: at a stop ([stopExit]) its resting SL's trigger while the
     * price [ltp] is not through the SL's limit (beyond it the app sells at market: [ltp]); any other exit at [ltp] (a market order).
     */
    fun shadowExit(stopExit: Boolean, ltp: Double, trigger: Double?, tick: Double, side: Int): Double {
        if (!stopExit || trigger == null) return ltp
        return if (runThrough(ltp, trigger, tick, side)) ltp else trigger
    }

    /**
     * Final review F4 (shadow P&L no better than live): a shadow MARKET fill as Zerodha would give it. A buy ([buy]) pays the
     * [ask] and a sell gets the [bid] when the quote has a usable book ([com.optionslab.engine.sandbox.PaperSpread.fromBook]);
     * with none, the [last] price moved against the order by [halfSpread] (the measured default). Then never better than the
     * app's own measured live slippage on such fills ([measured]: a share of the price, a cost positive; 0 with too few fills):
     * the worse of the two - not their sum, since the measured slippage is taken against the last price and so already holds
     * the spread. On the [tick], rounded against the order, never under one tick.
     */
    fun shadowFill(buy: Boolean, last: Double, bid: Double?, ask: Double?, tick: Double, halfSpread: Double, measured: Double): Double {
        val book = if (bid != null && ask != null) com.optionslab.engine.sandbox.PaperSpread.fromBook(bid, ask) else null
        val hs = halfSpread.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val m = measured.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val ref = if (last.isFinite() && last > 0) last else if (book != null) (bid!! + ask!!) / 2 else return last
        val touch = if (book != null) (if (buy) ask!! else bid!!) else if (buy) ref * (1 + hs) else ref * (1 - hs)
        val worst = if (buy) maxOf(touch, ref * (1 + m)) else minOf(touch, ref * (1 - m))
        val t = if (tick.isFinite() && tick > 0) tick else 0.05
        return Kite.onTick(worst, t, if (buy) Kite.Side.BUY else Kite.Side.SELL).coerceAtLeast(t)
    }

    /**
     * Final review F4: whether a shadow trade's stop at [trigger] was reached since its last look - by the price now ([ltp]) or by
     * the extreme traded meanwhile ([extreme]: a long's lowest, a short's highest, from the stream's ticks or the minute candles),
     * so a wick through the stop inside the minute counts as the resting SL at Zerodha would have filled on it.
     */
    fun shadowStopReached(ltp: Double?, extreme: Double?, trigger: Double?, side: Int): Boolean =
        atStop(ltp, trigger, side) || atStop(extreme, trigger, side)

    /**
     * Final review F4: where the resting SL at [trigger] fills for a shadow trade: at the trigger moved against the order by
     * [slip] (a share: the half-spread, or the measured live stop slippage when worse), never past the SL's own limit
     * ([stopLimit]) - an SL fills at its limit or better. (A price through that limit now is a market sale: [shadowFill].)
     */
    fun shadowStopFill(trigger: Double, tick: Double, side: Int, slip: Double): Double {
        val s = slip.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val lim = stopLimit(trigger, tick, side)
        val raw = if (side >= 0) maxOf(trigger * (1 - s), lim) else minOf(trigger * (1 + s), lim)
        return Kite.onTick(raw, tick, if (side >= 0) Kite.Side.SELL else Kite.Side.BUY).let { if (side >= 0) maxOf(it, lim) else minOf(it, lim) }
            .coerceAtLeast(tick)
    }

    /** What a shadow record includes, said beside it on the card (final review F4). */
    const val SHADOW_BASIS = "shadow priced at Zerodha's ask to buy and bid to sell, with the app's measured slippage and the " +
        "charges; stops judged on each minute's low or high"

    /** A shadow record: its closed trades' nets ([nets], after charges): (trades, net, win rate 0..1; NaN with none). */
    data class Record(val trades: Int, val net: Double, val winRate: Double)

    fun record(nets: List<Double>): Record =
        Record(nets.size, nets.sum(), if (nets.isEmpty()) Double.NaN else nets.count { it > 0 }.toDouble() / nets.size)

    /** "12 trades, Rs 3,400, 58% won" (or "no trade yet"). */
    fun recordWords(r: Record): String = if (r.trades == 0) "no shadow trade yet" else
        "%d trade%s, %s, %.0f%% won".format(java.util.Locale.ENGLISH, r.trades, if (r.trades == 1) "" else "s", rs(r.net), r.winRate * 100)

    /**
     * The disaster stop of a close-judged arm ([Spec.closeStop]): 80% beyond the fill. For the Hero arm (its own stop -60% on a close)
     * and the Hero ticket (Rs 5-30 options that swing 30% and more in minutes while its 25% trail, judged on closes, holds them;
     * with the default 2x start no stop at all below 2x), a -30% tick stop would sell ahead of the paper trade on ordinary noise;
     * -80% fires only in a collapse the paper trade would also be in (a ticket's whole lot costs at most Rs 1,000).
     */
    const val DISASTER_PCT = 0.80

    /** The disaster stop for a live fill [fill] ([DISASTER_PCT] beyond it), on the [tick]; null when it is no price. */
    fun disasterLevel(fill: Double, side: Int, tick: Double): Double? =
        level(fill, if (side >= 0) fill * (1 - DISASTER_PCT) else fill * (1 + DISASTER_PCT), fill, Scale.PCT, side, tick)

    /** The safety stop's distance: 30% beyond the live fill. */
    const val SAFETY_PCT = 0.30

    /** The safety stop for a live fill [fill]: 30% below it for a long (above for a short), on the [tick]; null when it is no price. */
    fun safetyLevel(fill: Double, side: Int, tick: Double): Double? =
        level(fill, if (side >= 0) fill * (1 - SAFETY_PCT) else fill * (1 + SAFETY_PCT), fill, Scale.PCT, side, tick)

    // ---- entries --------------------------------------------------------------------------------------------------------------

    /**
     * What a live entry checks first, in order. [appLive] the app in Live with real orders allowed; [aiLive] "AI trades go live";
     * [armOn] the arm's own "Trade live"; [paperOnly] the paper-only period's refusal (null: none); [kill] the kill switch; [stopped]
     * the bot stopped for today; [lossLimit] the daily loss limit reached; [dayLock] the account day lock's refusal; [accountStop] the
     * account stop rule's (-Rs 40k drawdown or -Rs 13k a month on the live record); [loggedIn] to Zerodha today; [compromised] the
     * phone's security check.
     */
    data class Gate(
        val appLive: Boolean, val aiLive: Boolean, val armOn: Boolean, val paperOnly: String? = null, val kill: Boolean = false,
        val stopped: Boolean = false, val lossLimit: Boolean = false, val dayLock: String? = null, val accountStop: String? = null,
        val loggedIn: Boolean = true, val compromised: Boolean = false,
    )

    /** Why no live entry goes now, or null when it may. Every refusal only stops an entry; exits never come here. */
    fun refusal(g: Gate): String? = when {
        !g.appLive -> "the app is in Paper"
        !g.aiLive -> "\"AI trades go live\" is off"
        !g.armOn -> "its \"Trade live\" switch is off"
        g.paperOnly != null -> g.paperOnly
        g.kill -> "the kill switch is on"
        g.stopped -> "the bot is stopped for today"
        g.lossLimit -> "the daily loss limit was reached today"
        g.dayLock != null -> g.dayLock
        g.accountStop != null -> "the account stop rule: ${g.accountStop}"
        !g.loggedIn -> "not logged in to Zerodha today"
        g.compromised -> "this phone failed the security check"
        else -> null
    }

    /** The paper trade's lots: its quantity in whole lots (0 when it is less than one lot or the lot is unknown). */
    fun lots(paperQty: Int, paperLot: Int): Int = if (paperLot <= 0 || paperQty <= 0) 0 else paperQty / paperLot

    /**
     * The live quantity for [paperLots] at Zerodha's [lot]: the same lots, never more - and never past [maxLots] (the Bot
     * settings' cap; 0 or less: no cap here, the order gates still apply). 0: nothing to buy.
     */
    fun liveQty(paperLots: Int, lot: Int, maxLots: Int = 0): Int {
        if (paperLots <= 0 || lot <= 0) return 0
        val lots = if (maxLots > 0) minOf(paperLots, maxLots) else paperLots
        return lots * lot
    }

    /** 30 Oct (Boss): the live lots of one arm entry ([Spec.bossLots]: Boss's own size). 3 Nov: the default of Boss's setting. */
    const val LIVE_LOTS = 1
    /** 3 Nov: the most lots Boss's "live lots per arm trade" setting can ask for (the order gates still apply on top). */
    const val MAX_ARM_LOTS = 10

    /** 3 Nov (Boss): the indices Bot settings → Indices offers for live trading (MCX is not one of them: it keeps its own stop). */
    val INDEX_MARKETS = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

    /** How "none ticked" is kept (an empty value reads back as "not set", which would mean all five). */
    const val NO_MARKETS = "-"

    /** The kept setting ("NIFTY,BANKNIFTY", or [NO_MARKETS]) back to a set of known indices (unknown names dropped). */
    fun markets(csv: String): Set<String> = csv.split(',').map { it.trim().uppercase() }.filter { it in INDEX_MARKETS }.toSet()

    /** The setting as kept: never empty text ([NO_MARKETS] for none). */
    fun marketsKept(set: Set<String>): String = set.filter { it in INDEX_MARKETS }.sorted().joinToString(",").ifEmpty { NO_MARKETS }

    /** The index a Zerodha option symbol ("BANKNIFTY26NOV55200CE", "SENSEX26N0576100CE") belongs to, or null (not one of the five). */
    fun indexOf(symbol: String): String? = INDEX_MARKETS.firstOrNull { symbol.startsWith(it) && symbol.getOrNull(it.length)?.isDigit() == true }

    /** Whether a live entry on [underlying] may go: an index only when Boss selected it; anything else (MCX) is not decided here. */
    fun marketOn(underlying: String, selected: Set<String>): Boolean = underlying.uppercase() !in INDEX_MARKETS || underlying.uppercase() in selected

    /** 3 Nov (live): Zerodha's refusal of a MARKET order on a contract with little open interest (it takes a LIMIT). */
    fun marketBlocked(message: String?): Boolean = message?.contains("MARKET orders are blocked", ignoreCase = true) == true

    /**
     * 19 Nov 2026 (Boss: "Go"; Zerodha refused live MARKET option buys for "Insufficient funds" - it blocks margin for a MARKET option buy at
     * a protective price far above the premium, e.g. Rs 64,715 asked for a lot whose premium was about Rs 18,000): the LIMIT an arm's live
     * BUY entry is sent at - marketable: the offer [ask] (else the [last] price) plus max(2 ticks, 0.5%), rounded up to the [tick]. Never
     * chased: never above the [last] price plus max(2 ticks, [ENTRY_MAX_OVER_LAST]) (a wide or stale offer: it rests there and is cancelled
     * unfilled), nor above the paper arm's own [paperLimit] when it has one (each on its tick, rounded down). null: no usable price. Pure.
     */
    fun entryLimit(ask: Double?, last: Double, tick: Double, paperLimit: Double?): Double? {
        val lastOk = last.takeIf { it.isFinite() && it > 0 }
        val base = ask?.takeIf { it.isFinite() && it > 0 } ?: lastOk ?: return null
        val t = tick.takeIf { it.isFinite() && it > 0 } ?: 0.05
        val px = Kite.onTick(base + maxOf(2 * t, base * ENTRY_BUFFER), t, Kite.Side.BUY)
        val caps = listOfNotNull(
            lastOk?.let { Kite.onTick(it + maxOf(2 * t, it * ENTRY_MAX_OVER_LAST), t, Kite.Side.SELL) },
            paperLimit?.takeIf { it.isFinite() && it > 0 }?.let { Kite.onTick(it, t, Kite.Side.SELL) })
        return (listOf(px) + caps).minOrNull()?.takeIf { it > 0 }
    }

    /** 19 Nov 2026: the most an entry LIMIT is set above the last price ([entryLimit]; at least two ticks). */
    const val ENTRY_MAX_OVER_LAST = 0.02

    /** 19 Nov 2026: the share of the price an entry LIMIT is set above the offer ([entryLimit]; at least two ticks). */
    const val ENTRY_BUFFER = 0.005

    /**
     * 3 Nov: the LIMIT an entry is sent at when Zerodha blocks its MARKET order: the offer for a buy (the bid for a sell), never more
     * than 1% through the [last] price (it never chases); the last price ±0.5% when the book has no side. null: no usable price.
     */
    fun blockedMarketLimit(buy: Boolean, last: Double, ask: Double?, bid: Double?): Double? {
        if (!(last.isFinite() && last > 0)) return null
        return if (buy) minOf(ask?.takeIf { it.isFinite() && it > 0 } ?: last * 1.005, last * 1.01)
            else maxOf(bid?.takeIf { it.isFinite() && it > 0 } ?: last * 0.995, last * 0.99)
    }

    /**
     * 10 Nov (the 3 Nov refusal, for an EXIT): the MARKET exit [o] sent again as a LIMIT when Zerodha blocks MARKET orders on its
     * contract (low open interest), at the bid for a sale / the offer for a buy back ([blockedMarketLimit]: never more than 1%
     * through [last]; the last ±0.5% with no book side), on the tick. null: [o] is not a MARKET order or there is no usable price
     * (the exit then waits for the next look, as before). Same contract, side, quantity, product and tag: only the price is added.
     */
    fun blockedMarketExit(o: Kite.Order, last: Double?, ask: Double? = null, bid: Double? = null): Kite.Order? {
        if (o.orderType != "MARKET" || last == null) return null
        val buy = o.side == Kite.Side.BUY
        val px = blockedMarketLimit(buy, last, ask, bid) ?: return null
        val p = Kite.onTick(px, o.tickSize, o.side)
        if (!(p.isFinite() && p > 0)) return null
        return o.copy(orderType = "LIMIT", price = p)
    }

    /**
     * 10 Nov (Boss's diagnostics: R51 Donchian 20 and R72 Breadth Donchian bought the SAME contract within minutes on 3, 4 and 6 Nov,
     * twice the stake on one move): the line logged (the log adds the arm's own [label]) when its live entry would buy a contract [others] (their labels)
     * already hold live the same way. SHADOW ONLY (CLAUDE.md: anything new that could skip a live trade starts logged only, behind a switch
     * that is off by default): nothing is skipped or changed. null: no other arm holds it.
     */
    fun sameContractWords(label: String, symbol: String, others: List<String>): String? {
        val o = others.filter { it.isNotBlank() && it != label }.distinct()
        if (o.isEmpty()) return null
        return "$symbol is already held live by ${o.joinToString(", ")} (the same contract, the same way): a same-contract " +
            "guard would skip this entry. Shadow only: logged, nothing changed."
    }

    /** Boss's setting, kept within 1..[MAX_ARM_LOTS]. */
    fun armLots(set: Int): Int = set.coerceIn(1, MAX_ARM_LOTS)

    /** The lot cap [liveQty] applies to [spec]'s live entry: [LIVE_LOTS], or 0 (no cap here: its paper lots) for [Spec.bossLots]. */
    fun liveLotCap(spec: Spec): Int = if (spec.bossLots) 0 else LIVE_LOTS

    /**
     * 4 Nov (Boss: "if I select 3 lots, buy 3 if the money is there, else try 2, else 1"): the most of [want] lots of an option
     * buy whose premium ([perLot] rupees a lot at the price now, with [FIT_BUFFER] for the fill moving and the charges) fits the
     * free margin [free]. [want] when it all fits, or when the cost or the margin is not known; 0 when not even one lot fits (the
     * entry then goes as before and Zerodha says why). Never above [want]: it only ever buys less than Boss's setting.
     */
    fun lotsThatFit(want: Int, perLot: Double, free: Double?): Int {
        if (want <= 0) return 0
        if (!(perLot > 0) || free == null || !free.isFinite()) return want
        for (k in want downTo 1) if (k * perLot * (1 + FIT_BUFFER) <= free) return k
        return 0
    }

    /** 4 Nov: the room [lotsThatFit] keeps above the premium (3%: a MARKET fill a little above the last price, and the charges). */
    const val FIT_BUFFER = 0.03

    /**
     * 15 Nov (the phone's diagnostics: 25 arm live buys of 2-6 Nov rejected by Zerodha, "Insufficient funds"): whether the free cash
     * [free] cannot pay even one lot at [perLot] rupees (with [lotsThatFit]'s room) - the funds check's would-skip. False whenever a
     * figure is not known (the cash unread, a price missing): it says nothing then, and the entry goes as before.
     */
    fun shortOfOneLot(perLot: Double, free: Double?): Boolean =
        perLot > 0 && perLot.isFinite() && free != null && free.isFinite() && lotsThatFit(1, perLot, free) == 0

    /** 4 Nov: the words on the entry line when fewer lots were bought than Boss's setting. */
    fun fitWords(want: Int, fit: Int, free: Double): String =
        " · $fit of your $want lots: the free cash (Rs %,.0f) fits $fit".format(java.util.Locale.ENGLISH, free)

    /**
     * 3 Nov (Boss: "all the arms work on a single lot; give me an option in Bot settings to increase the lots, default 1"): the live
     * quantity of an arm's entry: exactly Boss's [setLots] (1..[MAX_ARM_LOTS]) lots at Zerodha's [lot], whatever the paper trade's
     * size (the paper keeps its own, so its record stays comparable) - once the paper trade holds at least one lot. An arm whose size
     * IS Boss's own setting elsewhere ([Spec.bossLots]: ntfy alerts) keeps its paper lots. 0: nothing to buy.
     */
    fun armLiveQty(paperLots: Int, lot: Int, spec: Spec, setLots: Int): Int {
        if (paperLots <= 0 || lot <= 0) return 0
        return (if (spec.bossLots) paperLots else armLots(setLots)) * lot
    }

    /**
     * The words for a live / shadow entry cut to [LIVE_LOTS] - " · 1 of the paper's 9 lots (1 lot per arm entry live)" - or "" when
     * it is the paper's size. Said on the arm's own entry line, so its record is read at its own size.
     */
    fun cutWords(paperLots: Int, liveQty: Int, lot: Int, setLots: Int = LIVE_LOTS): String {
        if (lot <= 0 || liveQty <= 0) return ""
        val live = liveQty / lot
        val per = "${armLots(setLots)} lot${if (armLots(setLots) == 1) "" else "s"} per arm entry live"
        return when {
            live < paperLots -> " · $live of the paper's $paperLots lots ($per)"
            // 3 Nov: Boss's setting above the paper's size.
            live > paperLots -> " · $live lots live, the paper trades $paperLots ($per, your Bot settings)"
            else -> ""
        }
    }

    // ---- levels ---------------------------------------------------------------------------------------------------------------

    /**
     * The live level for the paper trade's [paperLevel] (bought at [paperEntry]) on the live fill [liveEntry]: by [scale], on the
     * [tick] - a long's stop rounded down (a sell), a short's up (a buy). Null when any price is not usable or the level is not a
     * price (at or under one tick).
     */
    fun level(paperEntry: Double, paperLevel: Double, liveEntry: Double, scale: Scale, side: Int, tick: Double): Double? {
        if (!(paperEntry.isFinite() && paperEntry > 0 && paperLevel.isFinite() && paperLevel > 0 && liveEntry.isFinite() && liveEntry > 0 &&
                tick.isFinite() && tick > 0)) return null
        val raw = when (scale) {
            Scale.PCT -> liveEntry * paperLevel / paperEntry
            Scale.POINTS -> liveEntry + (paperLevel - paperEntry)
        }
        val t = Kite.onTick(raw, tick, if (side >= 0) Kite.Side.SELL else Kite.Side.BUY)
        return t.takeIf { it > tick + EPS }
    }

    /**
     * The SL order's limit for a stop at [trigger]: as the ORB and Pine arms' - 5% (at least 2 points) beyond it so it fills in a
     * fast move (below for a long's sell, above for a short's buy), on the [tick], never under one tick.
     */
    fun stopLimit(trigger: Double, tick: Double, side: Int): Double {
        val gap = maxOf(2.0, trigger * 0.05)
        return if (side >= 0) Kite.onTick(trigger - gap, tick, Kite.Side.SELL).coerceAtLeast(tick)
        else Kite.onTick(trigger + gap, tick, Kite.Side.BUY)
    }

    /** Whether a stop at [trigger] may rest at Zerodha with the price at [ltp]: a long's at least a tick below it, a short's above. */
    fun mayRest(trigger: Double, ltp: Double, tick: Double, side: Int): Boolean =
        trigger.isFinite() && ltp.isFinite() && trigger > 0 && ltp > 0 && (if (side >= 0) trigger <= ltp - tick + EPS else trigger >= ltp + tick - EPS)

    /**
     * Where the resting stop moves to: [wanted] when it is tighter than [current] (higher for a long, lower for a short; with no
     * stop yet, any) and may rest at [ltp] ([mayRest]); null - no move. Never loosened.
     */
    fun move(current: Double?, wanted: Double?, ltp: Double?, tick: Double, side: Int): Double? {
        if (wanted == null || ltp == null) return null
        if (current != null && (if (side >= 0) wanted <= current + EPS else wanted >= current - EPS)) return null
        return wanted.takeIf { mayRest(it, ltp, tick, side) }
    }

    /** The price at or through the stop (at or under it for a long, at or over it for a short). */
    fun atStop(ltp: Double?, trigger: Double?, side: Int): Boolean =
        ltp != null && trigger != null && ltp > 0 && trigger > 0 && (if (side >= 0) ltp <= trigger + EPS else ltp >= trigger - EPS)

    /** The price through the stop order's limit: it can no longer fill, so the app sells at market itself. */
    fun runThrough(ltp: Double?, trigger: Double?, tick: Double, side: Int): Boolean {
        if (ltp == null || trigger == null || ltp <= 0 || trigger <= 0) return false
        val lim = stopLimit(trigger, tick, side)
        return if (side >= 0) ltp < lim - EPS else ltp > lim + EPS
    }

    /**
     * The mirror's own square-off for a position on [exchange] in [product] at [t]: an NSE intraday (MIS) one at 15:15, and on its
     * contract's [expiry] day at 15:05; a held-over one (bought on an earlier day, [entryDay]) at once. A carried (NRML) NSE option
     * only on its contract's expiry day at 15:05 (never held into settlement); MCX never: it goes by its paper arm's own exit.
     */
    fun squareOffDue(t: LocalDateTime, exchange: String, product: String, expiry: LocalDate?, entryDay: LocalDate): Boolean {
        if (exchange !in NSE) return false
        if (product != "MIS") return expiry == t.toLocalDate() && !t.toLocalTime().isBefore(EXPIRY_SQUARE_OFF)
        if (entryDay.isBefore(t.toLocalDate())) return true
        val at = t.toLocalTime()
        if (!at.isBefore(SQUARE_OFF)) return true
        return expiry == t.toLocalDate() && !at.isBefore(EXPIRY_SQUARE_OFF)
    }

    /**
     * The live quantity to sell now so the live trade holds what its paper trade still holds: [liveQty] held of a live entry of
     * [liveEntryQty] at Zerodha's [lot]; the paper trade entered [paperEntryQty] and holds [paperHeldQty] (null: closed - all of it
     * goes). The same share in whole lots, rounded so the live trade never holds more of its entry than the paper trade does.
     */
    fun toSell(liveQty: Int, liveEntryQty: Int, paperEntryQty: Int, paperHeldQty: Int?, lot: Int): Int {
        if (liveQty <= 0) return 0
        if (paperHeldQty == null || paperHeldQty <= 0) return liveQty
        if (paperEntryQty <= 0 || paperHeldQty >= paperEntryQty) return 0
        val l = lot.coerceAtLeast(1)
        val entryLots = liveEntryQty / l
        // The live lots the paper share allows, rounded down: holding less than the paper share only ever lowers risk.
        val keepLots = (entryLots.toLong() * paperHeldQty / paperEntryQty).toInt()
        return (liveQty - keepLots * l).coerceAtLeast(0)
    }

    /**
     * 20 Nov 2026 (audit, B5 item 7: "a 1-lot live trade is sold whole on a paper part-exit"). [toSell] rounds the lots kept DOWN, so a
     * live copy smaller than its paper trade (1 lot live beside 2 or 4 on paper) is sold whole at the paper's first part-exit (the L25
     * ladder's first step, a half) though the arm's own rule still holds the rest. That stays the live behaviour: it is the lower-risk
     * one (an earlier, whole exit), and CLAUDE.md lets a live change that holds more than today start only in shadow, behind a switch
     * Boss turns on. What following the paper share more closely would sell instead - the lots kept rounded to the NEAREST lot, a tie
     * selling - is this; the app only logs it ([partSellsWhole]).
     */
    fun toSellNearest(liveQty: Int, liveEntryQty: Int, paperEntryQty: Int, paperHeldQty: Int?, lot: Int): Int {
        if (liveQty <= 0) return 0
        if (paperHeldQty == null || paperHeldQty <= 0) return liveQty
        if (paperEntryQty <= 0 || paperHeldQty >= paperEntryQty) return 0
        val l = lot.coerceAtLeast(1)
        val entryLots = liveEntryQty / l
        // Nearest, a tie down (2 x share > 1 keeps): never more than the live entry's own lots.
        val twice = 2L * entryLots * paperHeldQty
        val keepLots = ((twice + paperEntryQty - 1) / (2L * paperEntryQty)).toInt().coerceAtMost(entryLots)
        return (liveQty - keepLots * l).coerceAtLeast(0)
    }

    /**
     * Whether a paper part-exit (the paper trade still holds [paperHeldQty] > 0 of [paperEntryQty]) sells the WHOLE live trade under
     * [toSell] while [toSellNearest] would keep some of it: the case the shadow log names (B5 item 7). Arithmetic only.
     */
    fun partSellsWhole(liveQty: Int, liveEntryQty: Int, paperEntryQty: Int, paperHeldQty: Int?, lot: Int): Boolean =
        paperHeldQty != null && paperHeldQty > 0 && liveQty > 0 &&
            toSell(liveQty, liveEntryQty, paperEntryQty, paperHeldQty, lot) >= liveQty &&
            toSellNearest(liveQty, liveEntryQty, paperEntryQty, paperHeldQty, lot) < liveQty

    // ---- the watch ------------------------------------------------------------------------------------------------------------

    /** What the watch does with one live position this look. */
    enum class Step {
        /** The resting stop filled at Zerodha: book the exit at its price. */
        STOP_FILLED,
        /** Nothing is held at Zerodha any more and no exit of ours filled: closed there (Zerodha's square-off, or by hand). */
        CLOSED_OUTSIDE,
        /** Sell [Decision.qty] now (the resting stop taken out first): [Decision.why] says why. */
        SELL,
        /** Rest the stop at Zerodha at [Decision.to] (none rests now). */
        PLACE_STOP,
        /** Move the resting stop to [Decision.to] (tighter only). */
        MOVE_STOP,
        /**
         * The resting stop is for more than Zerodha holds less the other exits working on the contract (Boss sold part by hand, or
         * another holder's stop covers it): shrink it to [Decision.qty] (0: take it out) - a resting stop never sells into a short.
         */
        SHRINK_STOP,
        HOLD,
    }

    data class Decision(val step: Step, val why: String? = null, val qty: Int = 0, val to: Double? = null)

    /**
     * 18 Nov 2026 (audit A1): a [Step.SHRINK_STOP]'s reason when the other exits working on the contract ([Look.otherWorking]), not
     * what Zerodha holds, leave the resting stop less room than the trade (another order covers the lot): the app says so to Boss.
     */
    const val SHRINK_COVERED = "other_exit"

    /**
     * 18 Nov 2026 (audit A1): one exit order working on a contract beside a live trade's own: its [id], what it still has to sell
     * ([remaining]), and whether the arm may cancel it when its own exit is due ([cancellable]: a plain LIMIT order of no part of the
     * app - Boss's own target in Kite).
     */
    data class Blocker(val id: String, val remaining: Int, val cancellable: Boolean)

    /**
     * 18 Nov 2026 (audit A2): the arms' own orders sold more of a contract today ([sold]) than they bought today ([bought]) and than was
     * carried into today the trades' way ([carried], below 0 taken as 0) - two exits of one lot filled, a naked short of theirs. 19 Nov
     * 2026 (second review): the carried lot counts, so a lot carried from yesterday that the arm sells today, beside Boss's own short,
     * is never taken for one. Pure.
     */
    fun armsSoldBeyond(sold: Int, bought: Int, carried: Int): Boolean = sold > bought + carried.coerceAtLeast(0)

    /**
     * 18 Nov 2026 (audit A1): which of the other exits working on a contract ([others], in the order to cancel them: newest first) to
     * cancel so a due exit of [want] can sell what Zerodha holds ([held], as the trade's side sees it) in whole [lot]s: none when the
     * exit is not held back by them, or when only orders that may not be cancelled hold it back; else the fewest cancellable ones,
     * in order, that let it sell as much as cancelling all of them would. Never more than is held (what is sold stays
     * [ExitQty.sendable]). Pure.
     */
    fun blockersToCancel(held: Int, others: List<Blocker>, want: Int, lot: Int): List<String> {
        fun sendable(working: Int) = com.optionslab.engine.risk.ExitQty.sendable(held.coerceAtLeast(0), working.coerceAtLeast(0), want, lot)
        val now = sendable(others.sumOf { it.remaining })
        if (now >= want) return emptyList()
        val best = sendable(others.filter { !it.cancellable }.sumOf { it.remaining })
        if (best <= now) return emptyList()
        val out = ArrayList<String>()
        var left = others.sumOf { it.remaining }
        for (o in others) {
            if (!o.cancellable || o.remaining <= 0) continue
            out += o.id
            left -= o.remaining
            if (sendable(left) >= best) break
        }
        return out
    }

    /**
     * One live position as the watch sees it: [stopFilled] its resting stop filled at Zerodha (null: unknown or none); [held] the
     * quantity Zerodha holds of its contract and product (null: not read - nothing is decided on it); [ltp] the price now (null:
     * none fresh); [qty] the trade's own live quantity; [stopTrigger] where its stop rests or is watched; [stopResting] whether a
     * stop order rests at Zerodha; [wanted] the paper trade's level on the live fill ([level]; null: none); [paperSell] the
     * quantity its paper trade no longer holds ([toSell]); [paperReason] why the paper trade closed (when it did); [squareOff]
     * the mirror's own square-off is due ([squareOffDue]); [operatorStop] Boss's stop for today; [side] +1 long, -1 short.
     */
    data class Look(
        val side: Int, val qty: Int, val held: Int?, val ltp: Double?, val tick: Double, val stopTrigger: Double?, val stopResting: Boolean,
        val stopFilled: Boolean = false, val wanted: Double? = null, val paperSell: Int = 0, val paperReason: String? = null,
        val squareOff: Boolean = false, val operatorStop: Boolean = false,
        /**
         * Review fixes (18 Oct): [heldSeen] Zerodha has shown this trade held (or its zero reads have lasted the grace): only then is a
         * zero read "closed outside" - a position book that lags a fill never closes a trade; [stopQty] the resting stop's working
         * quantity (null: unknown); [otherWorking] the other exits working on the contract (another holder's stop, Boss's own sell,
         * a fired GTT's order); [lot] the order lot; [stopLimit] the resting order's own limit when known (a GTT's leg price), else
         * the SL limit the arms use ([stopLimit]).
         */
        val heldSeen: Boolean = true, val stopQty: Int? = null, val otherWorking: Int = 0, val lot: Int = 1, val stopLimit: Double? = null,
        /**
         * 15 Nov ([StepTrail], Boss's exception): the live trade's own breakeven-rule stop now (null: none). When the stop resting
         * (or watched) is lower - it could not rest there yet, the price too near, or Zerodha refused it - and the price now is at or under
         * it, the app sells ([ruleWhy] the reason). An earlier exit only.
         */
        val ruleStop: Double? = null, val ruleWhy: String = StepTrail.WHY_STOP,
    )

    /**
     * The watch's decision for one live position, in order: its stop filled; gone at Zerodha; Boss's stop; the mirror's square-off;
     * its paper trade sold (all or part); 15 Nov: the stop rule's stop ([Look.ruleStop]) reached while a lower stop rests; the stop reached
     * with nothing resting, or the price through the resting stop's limit;
     * the stop placed or moved up to the paper trade's level; else held.
     */
    fun decide(l: Look): Decision {
        if (l.stopFilled) return Decision(Step.STOP_FILLED, "stop")
        val held = l.held ?: return Decision(Step.HOLD)
        val heldMine = if (l.side >= 0) held else -held
        if (heldMine <= 0) return if (l.heldSeen) Decision(Step.CLOSED_OUTSIDE, "closed_outside") else Decision(Step.HOLD)
        val qty = minOf(l.qty, heldMine)
        if (l.operatorStop) return Decision(Step.SELL, "operator_stop", qty)
        if (l.squareOff) return Decision(Step.SELL, "square_off", qty)
        if (l.paperSell > 0) return Decision(Step.SELL, l.paperReason ?: "paper_exit", minOf(l.paperSell, qty))
        // 15 Nov ([StepTrail]): the rule's stop reached while the stop (resting or watched) is lower: sold by the app (its stop out first).
        val rs = l.ruleStop
        if (l.side >= 0 && rs != null && (l.stopTrigger == null || rs > l.stopTrigger + EPS) && StepTrail.stopHit(l.ltp, rs))
            return Decision(Step.SELL, l.ruleWhy, qty)
        // A resting stop for more than is held less the other working exits: shrunk (whole lots; 0 takes it out).
        if (l.stopResting && l.stopQty != null) {
            val room = (heldMine - l.otherWorking.coerceAtLeast(0)).coerceAtLeast(0)
            val lot = l.lot.coerceAtLeast(1)
            // 18 Nov 2026 (audit A1: Boss's own LIMIT sell in Kite on the contract shrank the arm's stop to nothing, unsaid): the
            // reason says when the other working exits, not what is held, take the room ([SHRINK_COVERED]), so the app tells Boss.
            if (l.stopQty > room) return Decision(Step.SHRINK_STOP,
                if (room < minOf(l.stopQty, heldMine) / lot * lot) SHRINK_COVERED else "held_less", room / lot * lot)
        }
        val trigger = l.stopTrigger
        if (!l.stopResting && atStop(l.ltp, trigger, l.side)) return Decision(Step.SELL, "stop", qty)
        if (l.stopResting && throughLimit(l.ltp, trigger, l.stopLimit, l.tick, l.side)) return Decision(Step.SELL, "stop_run_through", qty)
        val ltp = l.ltp ?: return Decision(Step.HOLD)
        if (!l.stopResting) {
            // No stop rests: the tighter of its own and the paper trade's level, placed when it may rest at this price.
            val best = listOfNotNull(trigger, l.wanted).let { if (l.side >= 0) it.maxOrNull() else it.minOrNull() } ?: return Decision(Step.HOLD)
            return if (mayRest(best, ltp, l.tick, l.side)) Decision(Step.PLACE_STOP, to = best) else Decision(Step.HOLD)
        }
        val to = move(trigger, l.wanted, ltp, l.tick, l.side) ?: return Decision(Step.HOLD)
        return Decision(Step.MOVE_STOP, to = to)
    }

    /** Through the resting order's own [limit] when known (a fired GTT's leg price), else through the SL limit the arms use. */
    fun throughLimit(ltp: Double?, trigger: Double?, limit: Double?, tick: Double, side: Int): Boolean {
        if (limit == null) return runThrough(ltp, trigger, tick, side)
        if (ltp == null || ltp <= 0 || limit <= 0) return false
        return if (side >= 0) ltp < limit - EPS else ltp > limit + EPS
    }

    /**
     * A zero position read for a trade never seen held counts as closed only after [GRACE_MS] of zero reads (a position book that
     * lags the fill never closes it): [zeroSinceMs] when the first zero read came (null: none yet), at [nowMs].
     */
    fun zeroLongEnough(zeroSinceMs: Long?, nowMs: Long): Boolean = zeroSinceMs != null && nowMs - zeroSinceMs >= GRACE_MS

    const val GRACE_MS = 60_000L

    /**
     * 18 Nov 2026 (Boss, live: R51's FINNIFTY lot and a Liquidity BANKNIFTY lot were booked closed while both were still held at
     * Zerodha, their stops taken out and nothing watching them): a positions read showing LESS than a live trade holds closes it only
     * once that is confirmed. Confirmed: Zerodha's positions show a row for the contract ([rowShown]: a read with no row for a
     * contract traded today is a bad read and never counts) and either an exit-side fill on it is in the order book ([saleSeen]: Boss's
     * own sale in Kite, Zerodha's square-off), or the reads have kept showing it short for [SHORT_CONFIRM_MS] since the first one
     * ([firstShortMs]; null: none yet) at [nowMs]. Until then the trade is kept, watched and its stop left resting. Pure.
     */
    fun shortConfirmed(rowShown: Boolean, saleSeen: Boolean, firstShortMs: Long?, nowMs: Long, unbookedSale: Boolean): Boolean =
        rowShown && (saleSeen || (firstShortMs != null && nowMs - firstShortMs >= shortWaitMs(unbookedSale)))

    /**
     * 18 Nov 2026 (review F3): how long the reads must keep showing a trade short, with no outside sale that covers it, before it is
     * booked closed: [SHORT_CONFIRM_MS] when some exit-side fill on the contract since the entry is not booked yet ([unbookedSale]: a
     * sale did happen, the app's own included, whose fill no book has taken), else [SHORT_CONFIRM_SLOW_MS] - with no sale at all in
     * Zerodha's order book, a held trade is never dropped on half a minute of reads. Pure.
     */
    fun shortWaitMs(unbookedSale: Boolean): Long = if (unbookedSale) SHORT_CONFIRM_MS else SHORT_CONFIRM_SLOW_MS

    /**
     * 18 Nov 2026 (review F3): whether Boss is told (once) that a trade's reads have shown it short for [SHORT_CONFIRM_MS] with no sale
     * in Zerodha's order book to explain it ([saleSeen] false, [unbookedSale] false): kept with its stop resting until
     * [SHORT_CONFIRM_SLOW_MS]. Pure.
     */
    fun shortAlertDue(rowShown: Boolean, saleSeen: Boolean, firstShortMs: Long?, nowMs: Long, unbookedSale: Boolean): Boolean =
        rowShown && !saleSeen && !unbookedSale && firstShortMs != null && nowMs - firstShortMs >= SHORT_CONFIRM_MS

    /** 18 Nov 2026: how long the reads must keep showing a trade short (with no sale seen) before it is booked closed: two reads at least. */
    const val SHORT_CONFIRM_MS = 30_000L

    /** 18 Nov 2026 (review F3): the same with no exit-side fill at all left unbooked on the contract (nothing sold it): five minutes. */
    const val SHORT_CONFIRM_SLOW_MS = 300_000L

    /**
     * 18 Nov 2026 (review F1): one close a live book has booked on a contract ([exchange]:[symbol] [product]) at [at], [qty] of it;
     * [outside]: booked as closed outside the app (Boss's own sale in Kite, Zerodha's square-off - "closed_at_zerodha",
     * "closed_by_you", a square-off), else the app's own exit or a stop's fill. Both live books publish theirs (memory only), so
     * neither takes a sale the other already booked as the proof of its own trade's close.
     */
    data class Booked(val exchange: String, val symbol: String, val product: String, val at: LocalDateTime, val qty: Int, val outside: Boolean)

    /**
     * 18 Nov 2026 (review F1): whether the sales outside the app in Zerodha's order book since the entry cover [missing] (the quantity a
     * short positions read says is gone): [outsideSells] (exit-side fills of no order of the app's, the app's square-off counted) less
     * [outsideBuys] (entry-side fills of no order of the app's: Boss's own buy and sell of the contract are a round trip, not a close of
     * the app's trade) less [bookedOutside] (what either book already booked closed from outside since then: one sale is the proof of
     * one close only). False when nothing is missing. Pure.
     */
    fun outsideSaleCovers(outsideSells: Int, outsideBuys: Int, bookedOutside: Int, missing: Int): Boolean =
        missing > 0 && outsideSells - outsideBuys.coerceAtLeast(0) - bookedOutside.coerceAtLeast(0) >= missing

    /**
     * 18 Nov 2026 (review F3): whether an exit-side fill on the contract since the entry is not booked yet: every exit-side fill since then,
     * the app's own included ([exitFills]), less the outside entry-side fills ([outsideBuys]: Boss's round trips) and less every close
     * the books booked since then ([booked]). Pure.
     */
    fun unbookedSale(exitFills: Int, outsideBuys: Int, booked: Int): Boolean =
        exitFills - outsideBuys.coerceAtLeast(0) - booked.coerceAtLeast(0) > 0

    /**
     * 18 Nov 2026: how much of what Zerodha holds on a contract ([held], as the entry's side sees it) a live entry Zerodha never confirmed
     * (its order not found) may take back as its own fill: what [others] (the open trades that already explain it) leave, never more than
     * the entry asked for ([asked]) nor than Zerodha says was bought today that way ([today]; null: not said), in whole [lot]s. 0: none. Pure.
     */
    fun unexplainedQty(held: Int, today: Int?, others: Int, asked: Int, lot: Int): Int {
        val l = lot.coerceAtLeast(1)
        return (minOf(held - others, asked, today ?: Int.MAX_VALUE) / l * l).coerceAtLeast(0)
    }

    /**
     * 18 Nov 2026 (review): the most of a held position ([held], as the entry's side sees it) that today's lost entry can have bought: what
     * is held beyond what was carried from an earlier day ([overnight]), and - when Zerodha gives every row's day quantity that way
     * ([dayQtys]; a null: not said) - what was bought today less what orders of other owners filled today ([otherOwners]: Boss's own in
     * Kite, another bot's). Never below 0. Pure.
     */
    fun boughtTodayCap(dayQtys: List<Int?>, overnight: Int, held: Int, otherOwners: Int): Int {
        val beyondCarried = held - overnight.coerceAtLeast(0)
        val day = if (dayQtys.isNotEmpty() && dayQtys.all { it != null }) dayQtys.sumOf { it!! } - otherOwners else null
        return minOf(beyondCarried, day ?: Int.MAX_VALUE).coerceAtLeast(0)
    }

    private val KITE_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * 18 Nov 2026: whether Zerodha's order time [placedAt] ("yyyy-MM-dd HH:mm:ss") is at or after [since]; false when it cannot be
     * read (an unknown time never confirms a close). Pure.
     */
    fun placedSince(placedAt: String, since: LocalDateTime): Boolean =
        runCatching { !LocalDateTime.parse(placedAt.trim(), KITE_TIME).isBefore(since) }.getOrDefault(false)

    /** Why a live entry may not go at [t] although the gate allows it: too late for an intraday trade (or its expiry square-off). */
    fun tooLate(t: LocalDateTime, exchange: String, product: String, expiry: LocalDate?): String? =
        if (squareOffDue(t, exchange, product, expiry, t.toLocalDate())) "too late in the day for a new live entry (its square-off is due)" else null

    /** Why a live entry may not go on a contract Zerodha holds the other way ([net], signed) in the same product: never a reversal. */
    fun againstNet(side: Int, net: Int): String? =
        if (net != 0 && (net > 0) != (side > 0)) "Zerodha holds this contract the other way (net $net): no live entry against it" else null

    /**
     * Whether a live position may be sold because its paper trade is gone: only once its paper trade was SEEN open after the live
     * fill ([seen]) and is now absent from a book that was read ([paperOpen] false; null: the book unread - nothing is decided).
     * A paper view that has not caught up with a new entry yet never sells a live one.
     */
    fun paperGone(seen: Boolean, paperOpen: Boolean?): Boolean = seen && paperOpen == false

    /**
     * 19 Nov 2026 (audit batch 2: a paper-side event sold every live copy at market): whether a paper trade that ended with [why] ended
     * WITHOUT an exit decision of its arm's own rules - closed elsewhere (the paper book holds none of it, or no longer knows its
     * contract: a reset, a paper row closed by hand, the paper square-off or loss limit, a paper book read afresh) or closed outside. Such
     * an end never sells the live copy: it keeps its own stop and exits (its stop at Zerodha, its square-offs, Boss's hand). Every other
     * reason (the arm's stop, target, lock, time or its own exit) is mirrored to live as before. Pure.
     */
    fun paperEndKeepsLive(why: String?): Boolean = why == PAPER_CLOSED_ELSEWHERE || why == PAPER_CLOSED_OUTSIDE

    /**
     * 19 Nov 2026 (audit batch 2, coordinator's decision B2-B): whether a live trade on [exchange] in [product] has a square-off of its own
     * ([squareOffDue]: an NSE intraday trade at 15:15) - only such a copy may be detached from its paper trade by Boss's reset or hand close;
     * a carried (NRML) or MCX one goes with its paper trade (its time exit is its arm's). Pure.
     */
    fun ownSquareOff(exchange: String, product: String): Boolean = exchange in NSE && product == "MIS"

    /** 19 Nov 2026 (audit batch 2): the paper arms' words for a paper trade closed by something other than their own rules. */
    const val PAPER_CLOSED_ELSEWHERE = "closed_elsewhere"
    const val PAPER_CLOSED_OUTSIDE = "closed_outside"

    /**
     * 19 Nov 2026 (audit batch 2: Zerodha refusing an arm's stop was asked again on every look, the live lane's included): how long the
     * app waits before it tries to rest a live trade's stop again after the [tries]-th refusal in a row - 5 s, doubling to 60 s at most.
     * The app watches the level itself meanwhile. Pure.
     */
    fun restBackoffMs(tries: Int): Long = (REST_BACKOFF_MS shl (tries - 1).coerceIn(0, 4)).coerceAtMost(REST_BACKOFF_MAX_MS)

    const val REST_BACKOFF_MS = 5_000L
    const val REST_BACKOFF_MAX_MS = 60_000L

    private val SHADOW_ASK = Regex("(?i)\\b(in|on) shadow\\b|\\bshadow (record|trades?|p&?l|net)\\b|\\blive (record|trades?|copy|path)\\b|\\bdoing live\\b")
    private val SHADOW_ACT = Regex("(?i)\\b(turn|switch|put|take|make|set)\\b.*\\b(on|off|out|into)\\b")

    /**
     * Whether [text] asks how an arm is doing in shadow or live ("how is Night doing in shadow?", "R51's live record"): a question only -
     * anything that would switch something ("turn shadow off") is never one (switches are Boss's, on the screen, with the PIN).
     */
    fun shadowAsked(text: String): Boolean = SHADOW_ASK.containsMatchIn(text) && !SHADOW_ACT.containsMatchIn(text)

    /** "Rs 1,234" / "-Rs 1,234" (a live record's words; never on a locked phone). */
    fun rs(x: Double): String = (if (x < 0) "-Rs " else "Rs ") + "%,.0f".format(java.util.Locale.ENGLISH, abs(x))

    /** The lots an MCX arm trades on paper (1, fixed), the live trade's size. */
    const val MCX_LOTS = McxArmRules.LOTS
}
