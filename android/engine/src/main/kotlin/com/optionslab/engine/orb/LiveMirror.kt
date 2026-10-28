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
         * 21 Oct (FT3): the live size is cut to the Bot settings' lot cap instead of the whole order being refused by the order
         * gates - for an arm that buys a rupee budget of cheap options (many lots). Off for every other arm, whose over-cap order
         * stays refused as before.
         */
        val capLots: Boolean = false,
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

    /** Solo (midday)'s key (its paper book is Jarvis's Solo; it is a strategy, not one of Jarvis's own ideas). */
    const val SOLO_MIDDAY = "solo_midday"
    /** The shadow variants Boss re-armed on paper ([ShadowRules]): one live switch for them all. */
    const val SHADOW_PROMOTED = "shadow_promoted"
    /** ntfy alerts (25 Oct): one live switch, off until Boss turns it on with the PIN. */
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
            "(a strike's price, IV against the ATM's, distance in straddles; five indices)", closeStop = true, capLots = true),
        Spec(VixDivRules.SOURCE, VixDivRules.LABEL, VIX, signal = "the index against India VIX", safety = true),
        Spec(NightRules.SOURCE, NightRules.LABEL, NIGHT, product = "NRML", rest = Rest.GTT, signal = "the index's close and the day-end OI build-up",
            safety = true),
        // 25 Oct: an external message, not a researched rule: its live switch starts off (Boss turns it on with the PIN).
        Spec(NTFY_ALERTS, "ntfy alerts", NTFY, signal = com.optionslab.engine.signals.NtfyRules.SIGNAL, defaultOn = false),
        // 28 Oct (Boss: "just take orders from flow"): IraThinker's flow - not proven (R110), so its live switch starts off too.
        Spec(com.optionslab.engine.signals.FlowRules.SOURCE, com.optionslab.engine.signals.FlowRules.LABEL, FLOW,
            signal = com.optionslab.engine.signals.FlowRules.SIGNAL, defaultOn = false),
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

    /** Each arm's Shadow starts ON: it records what it would have sent live, and sends nothing, until Boss turns it off (the PIN). */
    const val SHADOW_DEFAULT = true

    /** Whether [key]'s Shadow is on, from Boss's saved choices ([saved]: key -> on); an arm never set, or unknown, is on. */
    fun shadowOn(key: String, saved: Map<String, Boolean>): Boolean = spec(key) == null || (saved[key] ?: SHADOW_DEFAULT)

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

    /**
     * 21 Oct (review): the words for a live / shadow entry cut to the lot cap ([Spec.capLots]) - " · 2 of the paper's 9 lots (your
     * lot cap)" - or "" when it is the paper's size. Said on the arm's own entry line, so its record is read at its own size.
     */
    fun cutWords(paperLots: Int, liveQty: Int, lot: Int): String {
        if (lot <= 0 || liveQty <= 0) return ""
        val live = liveQty / lot
        return if (live < paperLots) " · $live of the paper's $paperLots lots (your lot cap)" else ""
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
    )

    /**
     * The watch's decision for one live position, in order: its stop filled; gone at Zerodha; Boss's stop; the mirror's square-off;
     * its paper trade sold (all or part); the stop reached with nothing resting, or the price through the resting stop's limit;
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
        // A resting stop for more than is held less the other working exits: shrunk (whole lots; 0 takes it out).
        if (l.stopResting && l.stopQty != null) {
            val room = (heldMine - l.otherWorking.coerceAtLeast(0)).coerceAtLeast(0)
            val lot = l.lot.coerceAtLeast(1)
            if (l.stopQty > room) return Decision(Step.SHRINK_STOP, "held_less", room / lot * lot)
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
