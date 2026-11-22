package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Arm
import com.optionslab.engine.orb.ArmAccount
import com.optionslab.engine.orb.ArmsCleanup
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.FamilyPark
import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.IndexData
import com.optionslab.engine.orb.LiquidityBreakeven
import com.optionslab.engine.orb.LiquidityLots
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.LiquidityShadow
import com.optionslab.engine.orb.LiquidityTrail
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.ParkedArms
import com.optionslab.engine.orb.PassRule
import com.optionslab.engine.orb.ProfitLock
import com.optionslab.engine.orb.Replay
import com.optionslab.engine.orb.RangeFadeRules
import com.optionslab.engine.orb.RetiredArms
import com.optionslab.engine.orb.SweepRules
import com.optionslab.ira.DayStop
import com.optionslab.ira.FlowShadow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * The two ORB paper arms (ORB and ORB Fresh), run on the phone exactly as the
 * desktop's services/ai_signals/orb_arm.py runs them (strategies/orb/ORB_STRATEGY.md):
 *
 *  - every pass of the market watch, each ARMED arm decides once on the last
 *    completed 5-minute BANKNIFTY bar (idempotent within a bar);
 *  - an entry is a MARKET BUY of 1 lot MIS, booked at the actual fill, followed
 *    straight away by a RESTING SL-M SELL at fill - 40 (the book owns the stop:
 *    the arm never sends a second sell for it);
 *  - +40 target, 15:10 session end and the operator's Stop for today exit with
 *    a MARKET SELL after the resting stop has been taken out of the book;
 *  - re-entry from the bar after the exit bar, one position per arm.
 *
 * WHERE: a new entry follows the app's Paper/Live switch at the moment it is
 * placed (the owner's choice, 2026-09-26). An automatic arm trades by itself in
 * Paper and in Live until it is switched off; arming it while the app is in Live
 * takes the PIN or fingerprint once (liveOk). An arm armed in Paper that finds
 * the app in Live asks for approval until it is armed again in Live. (15 Nov, Boss: Liquidity 15+5 needs no PIN of its own - its
 * switch on Home clears it for Zerodha whenever the app is in Live, through every other live gate; [liveCleared].) Each position
 * remembers its own account, and its stop and exit only ever go there:
 * flipping the switch while a position is open never moves its exit. Every
 * entry goes through the account guard (and in Live the kill switch and the
 * Bot settings caps as well).
 *
 * Prices are the Upstox public candles the paper account already uses; the
 * index is aggregated from 1-minute to 5-minute bars labelled by their start.
 * State lives in its own encrypted vault file and survives restarts.
 */
object OrbArms {
    private lateinit var file: File
    private lateinit var app: Context
    private val lock = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "orb.vault")
        // 18 Oct (Boss: every paper strategy present for live too): the paper-only arms' trades, for their live path ([ArmLive]).
        ArmLive.register(com.optionslab.engine.orb.LiveMirror.ORB) { mirrorLegs }
        // 22 Nov 2026 (B6 review): the square-offs subtract these arms' unsettled exit sales (lost, or on their way) and take no stop out beside one.
        SquareOffExit.lostPendingProviders["orb"] = { sym, product -> if (product == "MIS") unsettledSales(sym) else 0 }
    }

    /**
     * The paper-only arms' (ORB Sweep, Range Fade, the Hero arm) paper trades of today and those still open, for their live path
     * ([ArmLive]): each one's quantity held (0 once closed). Null until the book is read. Memory only, set with the hints.
     */
    @Volatile private var mirrorLegs: List<ArmLive.PaperLeg>? = null

    /** A paper trade's reference for its live path: its arm and its paper order (or its entry time). */
    private fun mirrorRef(p: Position): String = "orb:${p.arm}:${p.entryOrderId ?: p.entryTime}"

    /** [p] (a paper-only arm's paper buy just filled, its stop at [stop]) offered to its live path ([ArmLive]); memory only. */
    private fun mirror(p: Position, c: Paper.Contract, stop: Double?, limit: Double? = null) {
        if (p.live || !runCatching { armOf(p.arm).paperOnly }.getOrDefault(false)) return
        runCatching { ArmLive.entered(ArmLive.Ask(p.arm, mirrorRef(p), c, 1, p.qty, p.entry, stop = stop, limit = limit)) }
    }

    // ---- state -------------------------------------------------------------------

    data class Position(
        val arm: String, val symbol: String, val right: String, val qty: Int, val entry: Double, val entryTime: LocalDateTime,
        val signalBar: LocalDateTime, val entryOrderId: String?, val stopOrderId: String?, val stopTrigger: Double?,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0,
        /** True: entered at Zerodha under [kite] (its orders are Kite order ids); false: the paper account. */
        val live: Boolean = false, val kite: String? = null,
        /**
         * A live buy Zerodha has not confirmed (the reply or its status was lost): [qty] is at most what may have
         * filled, and no stop rests yet. Kept as open (so the arm buys nothing more) until the books settle it.
         */
        val unconfirmed: Boolean = false,
        /** Liquidity 15+5 only: the index level the entry broke, and the next liquidity level (the target) or null. */
        val level: Double? = null, val target: Double? = null,
        /**
         * [ladder]: entered under the profit-lock ladder ([ProfitLock], the owner's 2026-10-01 choice; every fixed-target arm);
         * [peak]: the best premium seen since the entry, which sets how much of the target is locked.
         */
        val ladder: Boolean = false, val peak: Double? = null,
        /** Liquidity 15+5: its 20-minute time stop has been decided (held, or sold). */
        val timed: Boolean = false,
        /**
         * Liquidity 15+5, the shadow of its pre-registered candidate (a) ([LiquidityShadow.nearLevel]): the next liquidity
         * level ahead was closer than one index stop at the signal, so (a) would have skipped it. Null: not recorded (an
         * entry from before 06 Oct's update, or another arm). It never changes what the arm trades.
         */
        val near: Boolean? = null,
        /**
         * Liquidity 15+5, the shadow of candidate (c), the volatility risk filter ([com.optionslab.engine.orb.VolFilter]):
         * true when it would have skipped the signal (a big candle just before, the last half hour's volatility in its top
         * 20% for the time of day, or 15:00-15:05); false when it would not, or could not tell (too little history). Null:
         * not recorded (an entry from before it was added, or another arm). It never changes what the arm trades.
         */
        val volSkip: Boolean? = null,
        /**
         * Liquidity 15+5, the shadow of candidate (f) ([LiquidityShadow.strongMomentum], a pre-registered forward test): true
         * when the signal bar closed more than 10.4 bp beyond the swept level and, over the 5 minutes before, the bought
         * option's premium rose while the opposite right's fell. Null: not recorded (the momentum could not be read, an
         * entry from before it was added, or another arm). It never changes what the arm trades.
         */
        val strong: Boolean? = null,
        /**
         * The Hero arm only ([HeroRules.exitStep]): the quantity already sold at the first target (5x), at [soldAt] and
         * [soldTime]; [qty] is then what is still held (bought = [qty] + [sold]). 0: nothing sold early.
         */
        val sold: Int = 0, val soldAt: Double? = null, val soldTime: LocalDateTime? = null,
        /** The Hero arm only: the option's bid / ask / quantities (or last price alone) at the signal and at each exit. */
        val seen: List<HeroRules.Seen> = emptyList(),
        /**
         * Liquidity 15+5: its contract's lot size at the entry ([LiquidityLots]: [qty] is the lots bought times it). Null: an
         * entry from before the size setting (1 lot), or another arm.
         */
        val lot: Int? = null,
        /**
         * Liquidity 15+5: the worst premium seen while held (its best is [peak]), kept on each price check for Jarvis's trade
         * lesson ([com.optionslab.ira.TradeLesson]). Null: not recorded (an entry from before it was kept, or another arm).
         */
        val low: Double? = null,
        /**
         * ORB Fresh on paper with the trade manager acting (R12): the level the manager's lock raised the resting stop to
         * (null: it never did - every other position). A stop that fills there is the manager's lock exit.
         */
        val mgrStop: Double? = null,
        /**
         * Review R1: the exits' parameters in force at the entry, kept on the position so a restart before the strategy lab's
         * book is read (or any later change) never manages it with other ones - the ORB family's stop and target points
         * ([stopPts], [targetPts]) and Liquidity 15+5's exit parameters ([liq]). Null: an entry from before they were kept, or
         * another arm (read from the lab, as before).
         */
        val stopPts: Double? = null, val targetPts: Double? = null,
        val liq: com.optionslab.engine.orb.LiquidityParams? = null,
        /**
         * Liquidity 15+5 on PAPER under Boss's own Rs 500 give-back trail ([LiquidityTrail], research R19b / R19c: failed, their
         * choice, off by default; from +Rs 1,000 a lot since R19c, see [trailFrom]): the give-back in rupees per lot it entered
         * with. Null: plain rules (every live trade, every other arm).
         */
        val trail: Double? = null,
        /**
         * Where that trail starts, rupees per lot: Boss's rule of 13 Oct (R19c), +Rs 1,000 ([LiquidityTrail.ACTIVATE_RS]). Null with a
         * trail: a trade entered before it, which keeps R19b's first rule (from +Rs 500).
         */
        val trailFrom: Double? = null,
        /**
         * 22 Oct: Liquidity 15+5 on PAPER under Boss's own breakeven ([LiquidityBreakeven], research R100 A4: did not pass, their
         * choice, off by default): the gain that moves the stop to the price paid plus charges (+10%). Null: plain rules (every
         * live trade, every other arm).
         */
        val be: Double? = null,
        /**
         * 22 Oct: a paper entry priced again for its fill - its first price read found none, or its first order found no price
         * and was cancelled - so the same trade was bought on the one more read or order. A label only (the trade is the one the
         * rules asked for), so these entries can be told apart from the plain record.
         */
        val priceRetry: Boolean = false,
        /**
         * 25 Oct (Boss's decision on the app audit, E1; research h14): a Liquidity paper entry bought with h14's capped limit - the
         * cap it was bought under (0.5% over the signal's price). A label, so these trades can be told apart from market-order ones.
         */
        val capped: Double? = null,
        /**
         * 15 Nov (Boss: "when a trade is bought the stop loss should be only 500 rs ... keep a 500 rs gap on every 500 rs gain"; per lot;
         * failed gates, Boss's exception - [com.optionslab.engine.orb.StepTrail]): a Liquidity 15+5 trade (paper or live) that entered with the
         * breakeven rule ([StepTrailExits]): its resting stop starts at its own -15% (the rule sets no entry stop since V4) and is moved up
         * to breakeven with its best price ([peak], persisted) once it is 10% up. A trade keeps the rule it entered with (persisted). A label
         * too: these trades can be told apart from the plain rules (NOT counted as [ownRules], so the BANKNIFTY live stop rule still counts
         * every live trade).
         */
        val fixed: Boolean = false,
        /**
         * 16 Nov (the fourth version, Boss's exception): with [fixed], the trade also moves its stop to breakeven once its best open P&L is
         * +₹1,000 a lot net, if that comes before +10% ([com.optionslab.engine.orb.StepTrail.Lock.BE1000]); false: V4 alone, as it entered.
         * Persisted.
         */
        val lock: Boolean = false,
        /**
         * 16 Nov (review): the quantity it was bought with (null: an older or an unconfirmed trade): the lock's per-lot levels stay on it
         * when a part is sold later (a partial live exit splits the trade and its quantity). Persisted.
         */
        val entryQty: Int? = null,
        /**
         * 16 Nov (review): its stop at Zerodha was cancelled by Boss (not by the exchange, Zerodha's risk system or a rejection,
         * [com.optionslab.ira.ManualCoverRule.endedByExchange]): their choice is respected - the app never puts it back by itself and
         * watches the level itself. Persisted.
         */
        val stopByYou: Boolean = false,
        /** 16 Nov (review): the app itself cancelled its stop (an exit taking it out first) and Zerodha had not shown it out yet. Persisted. */
        val appCancelled: Boolean = false,
        /**
         * 20 Nov 2026 (Boss's exception, [CheaperStrike]): a live trade bought on a cheaper strike than its signal chose because the free cash
         * could not pay for that one - the app's name of the contract the signal chose; null: the signal's own. Persisted, so its record can
         * always be told apart from the plain rules' (CLAUDE.md: every Liquidity change stays logged and labelled).
         */
        val stepFrom: String? = null,
        /**
         * 20 Nov 2026 (audit: lots asked for vs lots bought): a live entry that bought fewer lots than Boss's setting asked for - Boss's 4 Nov
         * free-cash cut, or a part fill - the lots his setting asked for ([lot]s of the contract; [entryQty] is what was bought). Null:
         * all bought (every other trade). A label only. Persisted.
         */
        val askedLots: Int? = null,
        /** 20 Nov 2026: with [askedLots], the lots sent after the free-cash cut (a part fill bought fewer). Null: as many as bought. Persisted. */
        val sentLots: Int? = null,
        /**
         * 20 Nov 2026 (deep review F1): the lost exit ([lostExits]) of this part's own sale, whose reply was lost - the one part held from a new
         * exit and a stop put back, and the one that takes that sale when it is found ([lostOf]). Ids are never reused, so a copy of a part
         * that kept it after its lost exit ended matches nothing. Memory only; the lost exit itself is saved with an open live row and
         * taken back after a restart ([restoreLost]), which gives the row a new id.
         */
        val lostId: Long? = null,
    ) {
        val open: Boolean get() = exit == null
        /** 20 Nov 2026: "bought 2 of 3 lots (free cash)" or "(part filled)" when this entry bought fewer lots than asked ([askedLots]); else null. */
        val askedWords: String? get() = askedLots?.let { asked ->
            val bought = (entryQty ?: (qty + sold)) / (lot?.takeIf { it > 0 } ?: return null)
            lotsAskedWords(asked, sentLots ?: bought, bought)
        }
        /**
         * A paper trade on exit rules Boss's switches added (a trail, the breakeven): kept out of the plain-rules record. 25 Oct (review):
         * h14's capped limit changes only how the entry is bought (never above the cap), not the rules, so a capped trade stays in the
         * record (and in its stop rule's count), labelled by [capped] so it can always be told apart.
         */
        val ownRules: Boolean get() = LiquidityBreakeven.ownRules(trail, be)
        /**
         * 15 Nov (CLAUDE.md: the plain-rules record stays separable): a trade on exits other than the rules' own - Boss's paper
         * trail or breakeven ([ownRules]) or the breakeven rule ([fixed]). The plain-rules records leave these out; the
         * BANKNIFTY stop rule still counts every live trade ([ownRules] alone, unchanged).
         */
        val ownExits: Boolean get() = ownRules || fixed
        /**
         * 15 Nov: the label of the exits it ran on when [ownExits] - Boss's trail / breakeven ([LiquidityBreakeven.label]),
         * the breakeven rule ([com.optionslab.engine.orb.StepTrail.LABEL]), or both joined; null: the plain rules.
         */
        val ownExitsLabel: String? get() = listOfNotNull(if (ownRules) LiquidityBreakeven.label(trail, be) else null,
            if (fixed) com.optionslab.engine.orb.StepTrail.labelOf(if (lock) com.optionslab.engine.orb.StepTrail.lockFor(arm) else null) else null)
            .joinToString(" and ").ifEmpty { null }
        val day: LocalDate get() = entryTime.toLocalDate()
        val points: Double? get() = exit?.let { it - entry }
        /** The whole trade's P&L before charges: what is held now at [exit], plus any part sold early at [soldAt]. */
        val grossPnl: Double? get() = points?.let { it * qty + ((soldAt ?: entry) - entry) * sold }
        /** Lots bought ([lot] known): [qty] plus any part sold early, over the lot; 1 when not known (before the size setting). */
        val lots: Double get() = lot?.takeIf { it > 0 }?.let { (qty + sold).toDouble() / it } ?: 1.0
    }

    /**
     * 20 Nov 2026 (audit: lots asked for vs lots bought): a live entry's journal words when it bought [bought] of the [asked] lots, with the
     * reason: Boss's 4 Nov free-cash cut sent [sent] ("bought 2 of 3 lots (free cash)"), the order part filled ("bought 1 of 3 lots (part
     * filled)"), or both ("bought 1 of 3 lots (2 fit the free cash, 1 filled)"). Null when all were bought. Pure (the arms' live copies too).
     */
    internal fun lotsAskedWords(asked: Int, sent: Int, bought: Int): String? = when {
        asked <= 0 || bought < 0 || bought >= asked -> null
        sent in (bought + 1) until asked -> "bought $bought of $asked lots ($sent fit the free cash, $bought filled)"
        sent < asked -> "bought $bought of $asked lots (free cash)"
        else -> "bought $bought of $asked lots (part filled)"
    }

    /** A signal waiting for approval. Liquidity 15+5 also keeps its strike and the index levels (the ORB uses the day's legs). */
    data class Pending(val arm: String, val right: String, val signalBar: LocalDateTime, val expires: LocalDateTime,
                       val strike: Int? = null, val level: Double? = null, val target: Double? = null, val near: Boolean? = null,
                       val volSkip: Boolean? = null, val strong: Boolean? = null,
                       /**
                        * Liquidity 15+5: the account its approval notice named (true: Zerodha); the approval enters on it or not at all
                        * ([ArmAccount.approval], review G2-1). Null for the ORB arms and a signal saved before it was kept.
                        */
                       val live: Boolean? = null)

    data class Legs(val day: LocalDate, val strike: Int, val expiry: LocalDate, val ce: Paper.Contract, val pe: Paper.Contract)

    private data class Book(
        val armed: MutableMap<String, Boolean> = HashMap(),
        val auto: MutableMap<String, Boolean> = HashMap(),
        /**
         * Armed while in Live with the owner's PIN or fingerprint: automatic live entries allowed. 15 Nov: kept for the Liquidity books
         * too (older books stay readable) but never read for them - their switch clears them ([liveCleared]).
         */
        val liveOk: MutableMap<String, Boolean> = HashMap(),
        var legs: Legs? = null,
        var range: Pair<Double, Double>? = null,
        var rangeDay: LocalDate? = null,
        val decided: MutableMap<String, MutableSet<String>> = HashMap(),   // "arm|day" -> bar starts
        /** "arm|day" -> the last bar the arm watched while running; a gap before the current bar means it was paused. */
        val watched: MutableMap<String, String> = HashMap(),
        val positions: MutableList<Position> = ArrayList(),
        val pending: MutableMap<String, Pending> = HashMap(),
        val status: MutableMap<String, String> = HashMap(),
        val replays: MutableMap<String, JSONObject> = LinkedHashMap(),     // day -> { arm: [trades], up: bool }
        val upDays: MutableMap<String, Boolean> = HashMap(),
        /** arm -> when it was last armed (the Hero arm's self-disarm counts its firing days from then). */
        val since: MutableMap<String, String> = HashMap(),
        /** The one-time changes this book has been through ([switchOffLosers]): each runs once, never again. */
        val migrated: MutableSet<String> = HashSet(),
        /** Liquidity 15+5's size: lots a NEW entry buys ([LiquidityLots]); null until set (then [LiquidityLots.DEFAULT]). */
        var liqLots: Int? = null,
        /** A restore's higher size, waiting for Boss to choose it ([LiquidityLots.restored]); null: nothing waits. */
        var liqLotsAsk: Int? = null,
        /** Boss chose [liqLots] himself (the row, or by voice on his yes) since 08 Oct; false: not known ([LiquidityLots.oneLot]). */
        var liqLotsChosen: Boolean = false,
        /**
         * Liquidity 15+5's indices parked on Boss's OK ([ParkedArms], 9 Oct: FINNIFTY): its one switch leaves their books off
         * until Boss switches the index back on from the row ([unparkLiquidity]).
         */
        val parked: MutableSet<String> = HashSet(),
        /**
         * ORB, ORB Fresh, ORB Sweep and Range Fade parked on Boss's choice ([FamilyPark], 13 Oct): each source stays here until
         * Boss switches that arm back on ([setArmed]). Its row says so; the warnings and Jarvis's switch-off question
         * leave it alone (it is off).
         */
        val famParked: MutableSet<String> = HashSet(),
        /**
         * Liquidity BANKNIFTY's evening replay (12 Oct, its go-live checklist): day -> the replayed signals of its 15- and
         * 5-minute books ([com.optionslab.engine.orb.LiquidityDayReplay]), the last 60 days.
         */
        val liqReplays: MutableMap<String, JSONArray> = LinkedHashMap(),
        /**
         * 19 Nov 2026 (review): when "Erase everything" started this book (market clock, ISO), the same day: the start-up sweep counts only
         * these arms' orders placed after it ([coldSweep]) - never a lot from before the erase taken back as a trade. Null: no erase today.
         */
        var sweepFrom: String? = null,
    ) {
        /** 26 Oct: the [wipeGen] this book was read in; one from before "Erase everything" is never saved or kept again. */
        @Volatile var gen: Int = 0
    }

    @Volatile private var cache: Book? = null
    /**
     * Bumped by [wipe] (26 Oct). Checked without a lock (the arms' passes place orders: no new lock on their path): a book read
     * before the erase is not kept, and its save is dropped - or, if its write slipped past the check, undone ([wipedSince]).
     */
    @Volatile private var wipeGen = 0

    private fun contractJson(c: Paper.Contract) = JSONArray().put(c.symbol).put(c.underlying).put(c.expiry.toString()).put(c.strike)
        .put(c.right.name).put(c.lotSize).put(c.feedKey)

    /** The Hero arm's book looks ([Position.seen]) as saved: what, time, LTP, bid, ask, quantities (absent: not known). */
    private fun seenJson(l: List<HeroRules.Seen>) = JSONArray().apply {
        l.forEach { s -> put(JSONObject().put("w", s.what).put("t", s.at.toString())
            .apply { s.ltp?.let { put("ltp", it) }; s.bid?.let { put("bid", it) }; s.ask?.let { put("ask", it) }
                s.bidQty?.let { put("bq", it) }; s.askQty?.let { put("aq", it) } }) }
    }

    private fun seenOf(a: JSONArray): List<HeroRules.Seen> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        runCatching {
            HeroRules.Seen(o.getString("w"), LocalTime.parse(o.getString("t")), if (o.has("ltp")) o.getDouble("ltp") else null,
                if (o.has("bid")) o.getDouble("bid") else null, if (o.has("ask")) o.getDouble("ask") else null,
                if (o.has("bq")) o.getLong("bq") else null, if (o.has("aq")) o.getLong("aq") else null)
        }.getOrNull()
    }

    private fun contractOf(a: JSONArray) = Paper.Contract(a.getString(0), a.getString(1), LocalDate.parse(a.getString(2)), a.getDouble(3),
        Right.valueOf(a.getString(4)), a.getInt(5), a.getString(6))

    /**
     * 19 Nov 2026 (audit batch 2: the book's read): reads of the book in a row that failed for a moment - a Keystore hiccup past
     * readFileSteady's retries (450 ms), never a file that fails authentication or does not parse. Each such read throws and keeps
     * nothing, so the next call reads again (as the passed arms' book does); only after [READ_FAILS_BEFORE_ASIDE] in a row is the file
     * set aside, and only once they have gone on for [READ_FAIL_ASIDE_MS] (the stream's looks come several a second: a Keystore
     * outage of a second or two is never mistaken for a broken book). Before, the first such read set the whole book aside and started
     * an empty one - open live trades forgotten.
     */
    @Volatile private var readFails = 0
    /** 19 Nov 2026 (audit batch 2): when (market clock, epoch ms) the reads in a row that failed for a moment began. */
    @Volatile private var readFailSince = 0L
    internal const val READ_FAILS_BEFORE_ASIDE = 5
    internal const val READ_FAIL_ASIDE_MS = 30_000L

    /** TEST ONLY (19 Nov 2026, audit batch 2): the book's vault read (null in the app: [Vault.readFileSteady]). */
    @Volatile internal var testRead: (() -> ByteArray?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }

    /**
     * 19 Nov 2026 (audit batch 2, P3): the parts of the book that decide what the arms may do - one that cannot be read turns every arm off
     * (and marks every one-time change done); the parkings too (lost, a switch-on would arm a parked index). Any other part (a record:
     * replays, the up days, the day's status, ...) is only dropped and named, the arms left as they were.
     */
    private val CRITICAL_PARTS = setOf("armed", "auto", "liveOk", "pending", "decided", "migrated", "positions", "liqParked", "famParked")

    /** Every one-time change a new or set-aside book starts with marked done (nothing is armed or cleared by itself). */
    private val ONE_TIME_CHANGES get() = listOf(OFF_LOSERS, RetiredArms.MIGRATION, RetiredArms.UNRETIRE, LiquidityRules.MIDCP_JOIN,
        LiquidityLots.ONE_LOT_MIGRATION, ParkedArms.MIGRATION, FamilyPark.MIGRATION, ArmsCleanup.LIQ_MIGRATION, LIQ_SWITCH_CLEAR, ORB_REREST)

    /** One saved position row as [encodeBook] wrote it ([key]: the book's renamed sources). Throws on a row it cannot read. */
    private fun positionOf(p: JSONObject, key: (String) -> String, gen: Int? = null): Position =
        Position(key(p.getString("arm")), p.getString("symbol"), p.getString("right"), p.getInt("qty"), p.getDouble("entry"),
            LocalDateTime.parse(p.getString("entryTime")), LocalDateTime.parse(p.getString("signalBar")),
            p.optString("entryOrderId").ifEmpty { null }, p.optString("stopOrderId").ifEmpty { null },
            if (p.has("stopTrigger")) p.getDouble("stopTrigger") else null,
            if (p.has("exit")) p.getDouble("exit") else null,
            p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) }, p.optString("why").ifEmpty { null },
            p.optDouble("charges", 0.0), p.optBoolean("live", false), p.optString("kite").ifEmpty { null },
            p.optBoolean("unconfirmed", false),
            if (p.has("level")) p.getDouble("level") else null, if (p.has("target")) p.getDouble("target") else null,
            p.optBoolean("ladder", false), if (p.has("peak")) p.getDouble("peak") else null, p.optBoolean("timed", false),
            if (p.has("near")) p.getBoolean("near") else null, if (p.has("volSkip")) p.getBoolean("volSkip") else null,
            if (p.has("strong")) p.getBoolean("strong") else null,
            sold = p.optInt("sold", 0), soldAt = if (p.has("soldAt")) p.getDouble("soldAt") else null,
            soldTime = p.optString("soldTime").ifEmpty { null }?.let { LocalDateTime.parse(it) },
            seen = p.optJSONArray("seen")?.let { seenOf(it) }.orEmpty(),
            lot = if (p.has("lot")) p.getInt("lot") else null, low = if (p.has("low")) p.getDouble("low") else null,
            mgrStop = if (p.has("mgrStop")) p.getDouble("mgrStop") else null,
            stopPts = if (p.has("stopPts")) p.getDouble("stopPts") else null,
            targetPts = if (p.has("targetPts")) p.getDouble("targetPts") else null,
            liq = p.optString("liq").ifEmpty { null }?.let { liqOf(it) },
            trail = if (p.has("trail")) p.getDouble("trail") else null,
            trailFrom = if (p.has("trailFrom")) p.getDouble("trailFrom") else null,
            be = if (p.has("breakeven")) p.getDouble("breakeven") else null,
            priceRetry = p.optBoolean("priceRetry", false),
            capped = if (p.has("capped")) p.getDouble("capped") else null,
            fixed = p.optBoolean("step500", false), lock = p.optBoolean("stepLock", false),
            entryQty = p.optInt("entryQty", 0).takeIf { it > 0 }, stopByYou = p.optBoolean("stopByYou", false),
            appCancelled = p.optBoolean("appCancelled", false), stepFrom = p.optString("stepFrom").ifEmpty { null },
            askedLots = p.optInt("askedLots", 0).takeIf { it > 0 },
            sentLots = p.optInt("sentLots", 0).takeIf { it > 0 }).let { pos ->
            p.optJSONObject("lostExit")?.takeIf { pos.open && pos.live }?.let { l -> runCatching { pos.copy(lostId = restoreLost(pos, l, gen)) }.getOrNull() } ?: pos
        }

    /**
     * 20 Nov 2026: [pos]'s lost exit sale as the book saved it ([l]) taken back into memory after a restart - the same one if it is
     * there already (a book read again in this process) - so its hold, the other trades' wait and its 2-minute window go on. Its id.
     */
    private fun restoreLost(pos: Position, l: JSONObject, gen: Int? = null): Long? {
        val sym = pos.kite ?: return null
        val since = l.getLong("since")
        val owner = managerId(pos)
        // (A read from before "Erase everything" brings nothing back, and says nothing.)
        if (gen != null && gen != wipeGen) return null
        // (Only a sale sent today: an older one - or one with no readable send time - is not held after a restart; said once.)
        fun dropped(why: String): Long? {
            if (lostDropSaid.size > 200) lostDropSaid.clear()
            if (lostDropSaid.add("$sym|$since")) {
                runCatching { Diag.record("orb", "$sym: a saved lost exit was not taken back after the restart ($why)") }
                runCatching { com.optionslab.app.work.Alerts.error("ORB live: a lost exit sale of $sym saved before the restart was not taken back " +
                    "($why). The app reads Zerodha and sells only what is still held; check Kite's order book.", "ORB live") }
            }
            return null
        }
        val sentAt = runCatching { LocalDateTime.parse(l.getString("sentAt")) }.getOrNull() ?: return dropped("its send time is missing or unreadable")
        if (sentAt.toLocalDate() != runCatching { Market.now().toLocalDate() }.getOrDefault(now().toLocalDate())) return dropped("it was sent on ${sentAt.toLocalDate()}")
        synchronized(lostExits) {
            // (A read from before "Erase everything" brings nothing back.)
            if (gen != null && gen != wipeGen) return null
            lostExits.values.firstOrNull { it.sym == sym && it.owner == owner && it.sinceMs == since }?.let { return it.id }
            val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, l.getInt("qty"), pos.lot ?: 1, "MIS", l.getString("type"),
                null, 0.05, "NFO", l.optString("tag", "iraorb"))
            val id = lostSeq.incrementAndGet()
            val known = l.optJSONArray("known")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            lostExits[id] = LostExit(id, sym, owner, l.optString("why", "exit"), o, known, since, label = l.optString("label"),
                sentAt = sentAt, foundId = l.optString("found").ifEmpty { null }, askedMs = l.optLong("asked", 0L))
            return id
        }
    }

    private fun book(): Book {
        cache?.let { return it }
        writtenText = null; writtenStat = null
        val gen = wipeGen
        val b = Book().also { it.gen = gen }
        // A book saved by an earlier build (false: a new install, or wiped): only such a book goes through the one-time changes.
        var existed = false
        // 19 Nov 2026 (audit batch 2): a read that failed for a moment is thrown (nothing kept; the next call reads again); only one
        // that fails authentication, does not parse, keeps failing or meets an erase is set aside, as before.
        var erasing = false
        var failed = false
        // (19 Nov 2026, review: read under the write lock - never the file of a write that ends after an erase, before it deletes it.)
        val bytes: ByteArray? = try {
            synchronized(ioLock) { testRead.let { if (it != null) it() else Vault.readFileSteady(file) } }.also { readFails = 0 }
        } catch (e: Vault.Erasing) {
            erasing = true; failed = true; null
        } catch (e: Vault.Tampered) {
            readFails = 0; failed = true; null
        } catch (e: Exception) {
            val nowMs = runCatching { epochMs(now()) }.getOrDefault(System.currentTimeMillis())
            if (readFails == 0) readFailSince = nowMs
            readFails += 1
            if (readFails < READ_FAILS_BEFORE_ASIDE || nowMs - readFailSince < READ_FAIL_ASIDE_MS) {
                if (readFails == 1) runCatching { Diag.record("orb", "the arms' book could not be read now (${e.javaClass.simpleName}); read again on each look until it can be") }
                throw java.io.IOException("the ORB arms' book could not be read now", e)
            }
            runCatching { Diag.record("orb", "the arms' book could not be read $readFails times in a row (${e.javaClass.simpleName}): set aside") }
            readFails = 0; failed = true; null
        }
        // What could not be read of a readable file: one part, or one position row - the rest is kept.
        val dropped = ArrayList<String>()
        var droppedLive = 0
        if (bytes != null) {
            existed = true
            val o = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            if (o == null) failed = true else {
                // A book saved under its old name (FINNIFTY's 15-minute liquidity book, now its 30-minute one) carries on under the new.
                fun key(k: String) = LiquidityRules.RENAMED[k] ?: k
                // 19 Nov 2026 (audit batch 2): each part read on its own - one that cannot be read is dropped and named, never the book.
                fun part(name: String, read: () -> Unit) { try { read() } catch (e: Exception) { dropped += name } }
                part("armed") { o.optJSONObject("armed")?.let { m -> m.keys().forEach { b.armed[key(it)] = m.getBoolean(it) } } }
                part("auto") { o.optJSONObject("auto")?.let { m -> m.keys().forEach { b.auto[key(it)] = m.getBoolean(it) } } }
                part("liveOk") { o.optJSONObject("liveOk")?.let { m -> m.keys().forEach { b.liveOk[key(it)] = m.getBoolean(it) } } }
                part("legs") {
                    o.optJSONObject("legs")?.let { l ->
                        b.legs = Legs(LocalDate.parse(l.getString("day")), l.getInt("strike"), LocalDate.parse(l.getString("expiry")),
                            contractOf(l.getJSONArray("ce")), contractOf(l.getJSONArray("pe")))
                    }
                }
                part("range") { o.optJSONArray("range")?.let { b.range = it.getDouble(0) to it.getDouble(1); b.rangeDay = LocalDate.parse(it.getString(2)) } }
                part("decided") { o.optJSONObject("decided")?.let { m -> m.keys().forEach { k -> val a = m.getJSONArray(k); b.decided[k] = (0 until a.length()).map { a.getString(it) }.toMutableSet() } } }
                part("watched") { o.optJSONObject("watched")?.let { m -> m.keys().forEach { k -> b.watched[k] = m.getString(k) } } }
                // (Today's only, by the day each was booked; none from a read made before "Erase everything".)
                runCatching {
                    val d = today().toString()
                    o.optJSONObject("exitSales")?.let { m -> if (gen == wipeGen) m.keys().forEach { k -> if (m.optString(k) == d) exitSales.putIfAbsent(k, d) } }
                }
                // (22 Nov 2026, B6 review: the app's own stop cancels of the last day - a cancel made before a restart stays the app's - and the
                // pending cancels of closed trades' found lost stops from today, taken up again (claimed: never another's lost reply or
                // orphan) and confirmed on the next regular looks. None from a read made before "Erase everything".)
                runCatching {
                    if (gen == wipeGen) o.optJSONObject("ownCancels")?.let { m ->
                        val now = wallMs()
                        m.keys().forEach { k -> val at = m.optLong(k, 0L); if (at > 0 && now - at < OWN_CANCEL_KEEP_MS && ownCancels.putIfAbsent(k, at) == null) ownCancelsRestored += k }
                    }
                }
                // (22 Nov 2026, B6 review, second round: and the stops Boss's manual exit from the app or a square-off marked as the app's
                // own cancels ([markOwnCancels]) - each ends the first time its stop is seen working again.)
                runCatching {
                    if (gen == wipeGen) o.optJSONObject("exitMarks")?.let { m ->
                        val now = wallMs()
                        m.keys().forEach { k -> val at = m.optLong(k, 0L); if (at > 0 && now - at < OWN_CANCEL_KEEP_MS && exitMarks.putIfAbsent(k, at) == null) exitMarksRestored += k }
                    }
                }
                runCatching {
                    val d = today().toString()
                    if (gen == wipeGen) o.optJSONObject("sweepCancels")?.let { m -> m.keys().forEach { k ->
                        val e = m.optJSONObject(k) ?: return@forEach
                        val what = e.optString("what")
                        if (e.optString("day") != d || '|' !in what) return@forEach
                        if (synchronized(sweepCancels) { sweepCancels.putIfAbsent(k, what) } == null) { sweepDays[k] = d; runCatching { Broker.claimStop(k) } }
                    } }
                }
                // One row that cannot be read never drops the others: it is named, and a live one is taken back from Zerodha (below).
                part("positions") {
                    o.optJSONArray("positions")?.let { a ->
                        for (i in 0 until a.length()) try {
                            b.positions += positionOf(a.getJSONObject(i), ::key, gen)
                        } catch (e: Exception) {
                            val row = a.optJSONObject(i)
                            val live = row?.optBoolean("live", false) == true
                            // (Only a live trade still open - no exit saved - may be held at Zerodha with no row now.)
                            if (live && !row.has("exit")) droppedLive++
                            dropped += "position ${i + 1}" + (row?.optString("symbol")?.takeIf { it.isNotEmpty() }?.let { " ($it${if (live) ", live" else ""})" } ?: "")
                        }
                    }
                }
                part("pending") {
                    o.optJSONObject("pending")?.let { m -> m.keys().forEach { k -> val p = m.getJSONObject(k)
                        b.pending[key(k)] = Pending(key(k), p.getString("right"), LocalDateTime.parse(p.getString("bar")), LocalDateTime.parse(p.getString("expires")),
                            if (p.has("strike")) p.getInt("strike") else null, if (p.has("level")) p.getDouble("level") else null,
                            if (p.has("target")) p.getDouble("target") else null, if (p.has("near")) p.getBoolean("near") else null,
                            if (p.has("volSkip")) p.getBoolean("volSkip") else null, if (p.has("strong")) p.getBoolean("strong") else null,
                            if (p.has("live")) p.getBoolean("live") else null) } }
                }
                part("status") { o.optJSONObject("status")?.let { m -> m.keys().forEach { b.status[key(it)] = m.getString(it) } } }
                part("replays") { o.optJSONObject("replays")?.let { m -> m.keys().forEach { b.replays[it] = m.getJSONObject(it) } } }
                part("liqReplays") { o.optJSONObject("liqReplays")?.let { m -> m.keys().forEach { k -> m.optJSONArray(k)?.let { b.liqReplays[k] = it } } } }
                part("upDays") { o.optJSONObject("upDays")?.let { m -> m.keys().forEach { b.upDays[it] = m.getBoolean(it) } } }
                part("since") { o.optJSONObject("since")?.let { m -> m.keys().forEach { b.since[it] = m.getString(it) } } }
                part("migrated") { o.optJSONArray("migrated")?.let { a -> for (i in 0 until a.length()) b.migrated += a.getString(i) } }
                b.liqLots = LiquidityLots.of(if (o.has("liqLots")) o.optInt("liqLots") else null)
                b.liqLotsAsk = LiquidityLots.of(if (o.has("liqLotsAsk")) o.optInt("liqLotsAsk") else null)
                b.liqLotsChosen = o.optBoolean("liqLotsChosen", false)
                part("liqParked") { o.optJSONArray("liqParked")?.let { a -> for (i in 0 until a.length()) b.parked += a.getString(i) } }
                part("famParked") { o.optJSONArray("famParked")?.let { a -> for (i in 0 until a.length()) a.getString(i).takeIf { FamilyPark.covers(it) }?.let { b.famParked += it } } }
                o.optString("sweepFrom").takeIf { it.isNotEmpty() }?.let { b.sweepFrom = it }
                if (dropped.any { it in CRITICAL_PARTS }) {
                    // (A part of the switches, today's decisions or the one-time changes lost: every arm is off until Boss switches it on
                    // again - lower risk - and every one-time change counts as done, so none runs again on what is left.)
                    b.armed.clear(); b.pending.clear()
                    b.migrated += ONE_TIME_CHANGES
                }
            }
        }
        if (failed) {
            // Never overwrite what could not be read: set it aside and start clean, and say so.
            if (file.exists()) Vault.setAside(file)
            Notifier.post(app, 2016, Notifier.APPROVAL, "ORB arms could not be read",
                "Their saved state was set aside and both arms are disarmed. If an ORB position was open, check Trade → Paper now.", "trade")
            // 19 Nov 2026 (audit batch 2): never an empty book while Zerodha may hold these arms' lots: the start-up sweep runs again on
            // the next look and takes back today's "iraorb" buys Zerodha still holds, each with its stop ([coldSweep]); said loudly.
            if (!erasing) rebuildFromZerodha("ORB live: the arms' saved book could not be read and was set aside. Their live trades are " +
                "being taken back from Zerodha's positions and today's orders of these arms, each with its stop; every arm is off. Check Trade and Kite now.")
            return Book().also { it.gen = gen; it.sweepFrom = erasedAt; it.migrated += ONE_TIME_CHANGES; cache = it; hints(it)
                if (!wipedSince(it) && it.sweepFrom != null && !file.exists()) runCatching { saveSoon(it) } }
        }
        if (dropped.isNotEmpty()) {
            // 19 Nov 2026 (audit batch 2): the file as it was kept beside it (never lost), and Boss told what could not be read.
            runCatching { synchronized(ioLock) { file.copyTo(File(file.parentFile, "${file.name}.unreadable.${System.currentTimeMillis()}")) } }
            val what = dropped.joinToString(", ")
            runCatching { Diag.record("orb", "the arms' book: could not read $what; the rest is kept") }
            val words = "ORB arms: part of their saved book could not be read ($what); the rest is kept" +
                (if (dropped.any { it in CRITICAL_PARTS }) " and every arm is off until you switch it on" else "") +
                (if (droppedLive > 0) ". A live trade's row was lost: its lot is taken back from Zerodha with its stop" else "") + ". Check Trade and Kite."
            if (droppedLive > 0) rebuildFromZerodha(words) else runCatching { com.optionslab.app.work.Alerts.error(words, "ORB live") }
        }
        // A restore not yet disarmed (the app clears the flag once it has): the restored arms act as disarmed.
        if (com.optionslab.app.security.SecurePrefs.getBoolean(Backup.DISARM, false)) {
            b.armed.clear(); b.auto.clear(); b.liveOk.clear(); b.pending.clear()
        }
        val afterErase = !existed && b.sweepFrom == null && erasedAt != null
        if (afterErase) b.sweepFrom = erasedAt
        cache = b
        hints(b)
        if (wipedSince(b)) return b
        // A new book has nothing to change: it starts with every one-time change marked done (nothing is armed by itself).
        if (!existed) b.migrated += listOf(OFF_LOSERS, RetiredArms.MIGRATION, RetiredArms.UNRETIRE, LiquidityRules.MIDCP_JOIN, LiquidityLots.ONE_LOT_MIGRATION,
            ParkedArms.MIGRATION, FamilyPark.MIGRATION, ArmsCleanup.LIQ_MIGRATION, LIQ_SWITCH_CLEAR, ORB_REREST)
        val restoring = com.optionslab.app.security.SecurePrefs.getBoolean(Backup.DISARM, false)
        // Each runs once, in this order: the 06 Oct switch-off, then the retirement that reverses it for Liquidity alone, then
        // the 07 Oct un-retirement (ORB, ORB Fresh, ORB Sweep and Range Fade back on, on paper); then the MIDCPNIFTY books
        // joining Liquidity's switch (on paper, when it is on); then Liquidity's size, when none was saved (Boss's 06 Oct
        // choice then, 1 lot since 08 Oct).
        // Then (08 Oct, research X1) a 2-lot size Boss never chose by hand goes back to 1 lot, told once.
        // Then (9 Oct, Boss's OK) Liquidity 15+5 FINNIFTY and ORB Sweep are parked: switched off once, on paper terms.
        // Then (13 Oct, Boss's choice) ORB, ORB Fresh, ORB Sweep and Range Fade are parked: switched off once, paper and live.
        // (15 Oct, Boss's cleanup: Liquidity's FINNIFTY and MIDCPNIFTY books are kept on by [keepLiquidityFour], run once by the
        // cleanup's worker after the start's positions step - never on this load.)
        // Then (15 Nov, Boss: Liquidity live by its own switch) the Liquidity books on now are cleared for Zerodha once ([clearBySwitch]).
        // Then (16 Nov) an ORB-family live trade already open with no stop at Zerodha keeps being watched by the app ([keepOpenWatched]).
        if (switchOffLosers(b) or retire(b, restoring) or unretire(b, restoring) or joinMidcp(b, restoring) or sizeLiquidity(b) or oneLot(b) or park(b) or
            parkFamily(b) or clearBySwitch(b, restoring) or keepOpenWatched(b)) save(b)
        // (19 Nov 2026, review: the first book after an erase is written at once with the erase's time, so a restart today still knows it.)
        else if (afterErase) runCatching { saveSoon(b) }
        return b
    }

    /**
     * 19 Nov 2026 (audit batch 2: never an empty book while Zerodha may hold these arms' lots): the book was set aside, or a live row of
     * it could not be read. This process's start-up sweep runs again on the next regular look ([coldSweep]): it takes back today's "iraorb"
     * buys that Zerodha still holds and no row names, each with its stop (an unclaimed stop of these arms taken first), as after a crash.
     * Boss is told loudly ([words]). Memory only.
     */
    private fun rebuildFromZerodha(words: String) {
        coldSwept = false; coldNextAt = 0L; coldFailSince = null; coldFailSaid = false
        // (22 Nov 2026, B6 review: a lost exit sale kept in what could not be read is not known - the square-offs wait [LOST_LET_GO_MS],
        // the most such a sale is held, for Zerodha's books to show what it did: [bookReadHint].)
        lostUnknownUntil = wallMs() + LOST_LET_GO_MS
        runCatching { com.optionslab.app.work.Alerts.error(words, "ORB live") }
    }

    /**
     * Whether an arm holds an open position, as of the last load or save: read without the lock, so the main
     * thread never waits for a pass that is placing orders.
     */
    @Volatile var holdingHint: Boolean = false
        private set

    /** Whether an arm holds an open position at Zerodha (its checks read Zerodha's orders and positions), as [holdingHint]. */
    @Volatile var liveHint: Boolean = false
        private set

    /**
     * The arms' open positions as [com.optionslab.ira.AutoSide] reads them (each a bought option on its index), as of the
     * last load or save: read without the lock by the other automatic traders ([AutoExposure]).
     */
    @Volatile var exposureHint: List<com.optionslab.ira.AutoSide.Held> = emptyList()
        private set

    /**
     * Liquidity 15+5's size (lots a new entry buys), as of the last load or save: read without the lock by Jarvis's limits
     * ([com.optionslab.app.ira.IraActions.setting]); never a decision on an order (the entry reads the book itself). Until the
     * book is read it holds the fewest lots, so any raise asked for is still treated as one (Boss's own voice, his yes).
     */
    @Volatile var liquidityLotsHint: Int = LiquidityLots.CHOICES.first()
        private set

    /** Liquidity 15+5's size now, from the book itself (Jarvis's "set liquidity to 3 lots" reads it before asking). */
    suspend fun liquidityLots(): Int = lock.withLock { lotsOf(book()) }

    /**
     * Whether any arm is armed, as of the last load or save: read without the lock by the watch, which then wakes a few
     * seconds after each bar close for the arms' decisions ([com.optionslab.engine.orb.BarClose]).
     */
    @Volatile var armedHint: Boolean = false
        private set

    /**
     * Zerodha's trading symbols of the arms' open live positions, as [liveHint] (round 2): the event-driven checks follow
     * them on the stream and decide their exits from its ticks.
     */
    @Volatile var liveSymbolsHint: List<String> = emptyList()
        private set

    /**
     * 18 Nov 2026: the quantity of the arms' open live positions by Zerodha symbol (memory only): a lost entry of the arms' live copies
     * ([ArmLive]) never takes these for its own fill. (Review F6: an entry still searched for - unconfirmed, its order id lost - is left
     * out: it may hold nothing, and two lost entries of the two books on one contract must never block each other's taking back.)
     */
    @Volatile var liveQtyHint: Map<String, Int> = emptyMap()
        private set

    /**
     * 18 Nov 2026 (review F1): today's closes of the arms' live positions (memory only), each marked when booked from outside the arm
     * ([OUTSIDE_WHYS]): the arms' live copies ([ArmLive]) never take a sale already booked here as the proof that their own trade closed.
     */
    @Volatile var liveClosedHint: List<com.optionslab.engine.orb.LiveMirror.Booked> = emptyList()
        private set

    /** For tests: [liveClosedHint] as given (as after this book booked those closes). */
    internal fun liveClosedHintForTest(booked: List<com.optionslab.engine.orb.LiveMirror.Booked>) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; liveClosedHint = booked }

    /**
     * The open live positions as the book holds them now, each with its arm's name (Zerodha reconciliation, [ZerodhaSync]:
     * read only). Takes the arms' lock: callers bound the wait.
     */
    suspend fun liveOpen(): List<Pair<String, Position>> = lock.withLock {
        book().positions.filter { it.open && it.live }.map { p -> (runCatching { armOf(p.arm).label }.getOrNull() ?: p.arm) to p }
    }

    /**
     * The armed arms' names, as [armedHint] (12 Oct): the policy check ([ProblemsHost.policySweep]) reads them from memory,
     * never taking the arms' lock.
     */
    @Volatile var armedLabelsHint: List<String> = emptyList()
        private set

    /**
     * Whether a Liquidity BANKNIFTY book is armed and cleared for Zerodha, as of the last load or save: read without the lock by
     * [AutoExposure.liveYield] - the other traders then take no live BANKNIFTY entry. 15 Nov: cleared by its switch alone
     * ([liveCleared]), so this is "a BANKNIFTY book is on" (it only ever makes the others yield a live entry).
     */
    @Volatile var liquidityBnLiveHint: Boolean = false
        private set

    private val NO_STOP = com.optionslab.ira.LiquidityStopRule.Status(false, null, null, 0.0, 0.0, 0.0, 0)

    /**
     * Liquidity BANKNIFTY's live stop rule ([com.optionslab.ira.LiquidityStopRule]) over its live record, as of the last load or
     * save: its entries read it (memory only). [liquidityStopPaperHint]: the same walk over its paper record ("would have stopped").
     */
    @Volatile var liquidityStopHint: com.optionslab.ira.LiquidityStopRule.Status = NO_STOP
        private set
    @Volatile var liquidityStopPaperHint: com.optionslab.ira.LiquidityStopRule.Status = NO_STOP
        private set

    /**
     * Liquidity BANKNIFTY's closed trades in [b] on one account, for the stop rule (rupees after charges, as booked); on paper
     * the plain-rules trades only (Boss's opt-in give-back trail, R19b, is kept apart).
     */
    private fun bnClosed(b: Book, live: Boolean): List<com.optionslab.ira.LiquidityStopRule.Trade> =
        // 15 Nov (review): on paper the breakeven rule's trades are kept apart too ([Position.ownExits]); live counts every trade, as before.
        b.positions.filter { it.arm in com.optionslab.engine.orb.ArmPriority.BN_BOOKS && !it.open && it.live == live && it.exitTime != null &&
            !(if (live) it.ownRules else it.ownExits) }
            .map { p -> com.optionslab.ira.LiquidityStopRule.Trade(epochMs(p.exitTime!!), p.exitTime.toLocalDate(), (p.grossPnl ?: 0.0) - p.charges) }

    private fun stopHints(b: Book) {
        val st = runCatching { LiquidityTest.state.value }.getOrDefault(com.optionslab.ira.LiquidityFreeze.State())
        val day = today()
        // 19 Nov (Boss's choice, [ArmAutoStopsHost]): with its switch off the rule stops no book (one reached before it went off still
        // does, until Boss re-enables it with his PIN). Memory only.
        val trades = bnClosed(b, true)
        val live = ArmAutoStopsHost.liveStatus(com.optionslab.ira.LiquidityStopRule.check(trades, day, st.stopResetAtMs), trades, day, st.stopResetAtMs)
        liquidityStopHint = live
        liquidityStopPaperHint = com.optionslab.ira.LiquidityStopRule.check(bnClosed(b, false), day)
        // Reached: told once (a notification with no amount, a line in Jarvis's chat), in the background - never on this path.
        if (live.stopped && LiquidityTest.markStopTold(live.key)) LiquidityTest.later {
            runCatching { Diag.record("orb", "Liquidity BANKNIFTY: live stop rule reached on ${live.day} (${live.why}); new entries go to paper until Boss re-enables it with his PIN") }
            runCatching { Notifier.post(app, LIQ_STOP_NOTICE, Notifier.APPROVAL, "Liquidity BANKNIFTY: live entries stopped", com.optionslab.ira.LiquidityStopRule.NOTICE, "almanac") }
            runCatching { com.optionslab.app.ira.IraActivity.add("Liquidity BANKNIFTY's live stop rule was reached (${live.why}): it takes no new live entry " +
                "until you re-enable it with your PIN on its row; exits continue.") }
        }
    }

    /**
     * Boss re-enables Liquidity BANKNIFTY's live entries after its stop rule, with his PIN ([pinConfirmed]; never by voice, a
     * backup or an automation): the rule counts afresh from now. Raising live risk again is his alone.
     */
    suspend fun reenableLiquidityLive(pinConfirmed: Boolean): String {
        if (!pinConfirmed) return "Re-enabling Liquidity BANKNIFTY's live entries takes your PIN."
        if (!liquidityStopHint.stopped) return "Liquidity BANKNIFTY's live stop rule is not reached: nothing to re-enable."
        LiquidityTest.resetStop(System.currentTimeMillis())
        lock.withLock { stopHints(book()) }
        runCatching { Diag.record("orb", "Liquidity BANKNIFTY: live entries re-enabled by Boss with his PIN; the stop rule counts from now") }
        return "Liquidity BANKNIFTY may take live entries again; its stop rule counts from now (-Rs 40,000 from its best, -Rs 13,000 a month)."
    }

    /** 19 Nov: the live stop rule worked out again now (its switch changed in Bot settings: [ArmAutoStopsHost]). A worker thread. */
    suspend fun refreshStopHints() { lock.withLock { stopHints(book()) } }

    private fun hints(b: Book, copy: Boolean = true) {
        runCatching { stopHints(b) }
        runCatching {
            val today = today()
            val legs = b.positions.filter { !it.live && armOf(it.arm).paperOnly && (it.open || it.day == today) }
                .map { ArmLive.PaperLeg(mirrorRef(it), it.arm, if (it.open) it.qty else 0) }
            val before = mirrorLegs?.associate { it.ref to it.qty }
            mirrorLegs = legs
            // A mirrored paper trade sold (all or part) since the last look: its live trade follows at once.
            if (before != null && legs.any { l -> (before[l.ref] ?: 0) > l.qty }) ArmLive.nudge()
        }
        liquidityBnLiveHint = LiquidityRules.BOOKS.filter { it.source in com.optionslab.engine.orb.ArmPriority.BN_BOOKS }
            .any { b.armed[it.source] == true && liveCleared(b, it.source) }
        armedHint = ALL_ARMS.any { b.armed[it.source] == true }
        armedLabelsHint = ALL_ARMS.filter { b.armed[it.source] == true }.map { it.label }
        holdingHint = b.positions.any { it.open }
        liveHint = b.positions.any { it.open && it.live }
        liveSymbolsHint = b.positions.filter { it.open && it.live }.mapNotNull { it.kite }.distinct()
        liveQtyHint = b.positions.filter { it.open && it.live && it.kite != null && !(it.unconfirmed && it.entryOrderId == null) }
            .groupBy { it.kite!! }.mapValues { e -> e.value.sumOf { it.qty } }
        runCatching {
            val today = today()
            liveClosedHint = b.positions.filter { !it.open && it.live && it.kite != null && it.exitTime?.toLocalDate() == today }
                .map { com.optionslab.engine.orb.LiveMirror.Booked("NFO", it.kite!!, "MIS", it.exitTime!!, it.qty, it.why in OUTSIDE_WHYS) }
        }
        exposureHint = exposureOf(b)
        liquidityLotsHint = lotsOf(b)
        // 19 Nov 2026 (audit batch 2): the copy [view] shows while the lock is held (not on a fast look that changed only the best price).
        if (copy) runCatching { viewBook = snapshotOf(b) }
    }

    /** The open positions of [b] for [com.optionslab.ira.AutoSide]: who holds what, on which index. */
    private fun exposureOf(b: Book): List<com.optionslab.ira.AutoSide.Held> = b.positions.filter { it.open }.map { p ->
        val arm = (ALL_ARMS + LiquidityRules.ARM).firstOrNull { it.source == p.arm }
        val und = com.optionslab.ira.AutoSide.underlyingOf(p.symbol) ?: when {
            arm == null -> OrbRules.UNDERLYING
            arm.liquidity -> LiquidityRules.underlyingOf(arm)
            arm.hero -> HeroRules.UNDERLYING
            else -> OrbRules.UNDERLYING
        }
        com.optionslab.ira.AutoSide.Held.option(arm?.let { ownerOf(it) } ?: p.arm, p.symbol, und, p.right, long = true,
            rank = arm?.let { com.optionslab.engine.orb.ArmPriority.rankOf(it) } ?: com.optionslab.engine.orb.ArmPriority.Rank.OTHER,
            live = p.live, book = p.arm)
    }

    /**
     * Boss's 06 Oct rule ([com.optionslab.ira.AutoSide]): no automatic entry against another automatic position on the same
     * index, and at most one automatic position an index a side - the arms' own open positions (fresh from [b]) and every
     * other automatic trader's. Null when [c] may be bought, else the refusal the row and the log show.
     *
     * Liquidity 15+5 has priority over the ORB arms (Boss's 07 Oct decision, research/HUNT_H21.md,
     * [com.optionslab.engine.orb.ArmPriority]): [arm]'s rank decides which holdings count - a Liquidity entry is never refused
     * by an ORB, ORB Fresh, ORB Sweep or Range Fade position (it runs beside it; both live at the broker still refuse), and an
     * ORB-family entry is refused while Liquidity holds the index, saying so. [live]: the entry goes to Zerodha. The live
     * gates (PIN, the account guard, the kill switch) are all still checked after this, unchanged.
     */
    private fun exposureRefusal(b: Book, c: Paper.Contract, arm: Arm, live: Boolean = false): String? {
        val dir = com.optionslab.ira.AutoSide.direction(c.right.name, true)
        val own = exposureOf(b) + runCatching { ShadowArms.exposureHint }.getOrDefault(emptyList())
        val rank = com.optionslab.engine.orb.ArmPriority.rankOf(arm)
        // Each Liquidity BANKNIFTY book is its own claim (12 Oct): its book's source goes with the check.
        val refusal = AutoExposure.check(AutoExposure.Source.ORB, c.underlying, dir, own, rank, live, book = arm.source)
        // The activity log: a Liquidity entry going beside another position (an ORB arm's, another trader's on BANKNIFTY, its
        // other BANKNIFTY book's) says why it was not refused.
        if (refusal == null && rank == com.optionslab.engine.orb.ArmPriority.Rank.LIQUIDITY) runCatching {
            AutoExposure.priorityNote(AutoExposure.Source.ORB, ownerOf(arm), c.underlying, dir, own, rank, live, book = arm.source)?.let { Diag.record("orb", it) }
        }
        return refusal
    }

    /** 15 Nov: the one-time clearance of the Liquidity books on at this update ([clearBySwitch]): its key in [Book.migrated]. */
    internal const val LIQ_SWITCH_CLEAR = "liq_switch_clear_2026_11_15"
    /** 15 Nov: what [clearBySwitch] adds to a book's "paper only" status. */
    const val LIQ_SWITCH_CLEARED = "since 15 Nov cleared for Zerodha by your switch on Home: it trades live while the app is in Live"
    /** The statuses earlier one-time changes left on Liquidity books they switched on, each saying "paper only" or "never cleared". */
    private val PAPER_ONLY_STATUS = setOf(RetiredArms.BACK_ON, LiquidityRules.MIDCP_JOINED, ArmsCleanup.LIQ_ON, "switched back on by you (paper)")

    /**
     * Once, on this update (Boss, 15 Nov: "Liquidity should be always working if i have swtich on it on dashboard, no extra pin based
     * approval needed"): every Liquidity book that is ON now - what Boss's dashboard switch shows - is cleared for Zerodha ([liveCleared]),
     * whoever switched it on before; from now on only Boss's own switch on Home or his PIN clears a book. Said once - a notice, a diary
     * line and the change timeline (its Undo parks Liquidity's live entries to paper) - naming the books; a "paper only" status left by an
     * earlier one-time change says so too. A restore not yet disarmed (nothing is on) and IraGoldAlgo clear nothing. True when [b] changed.
     */
    private fun clearBySwitch(b: Book, restoring: Boolean): Boolean {
        if (LIQ_SWITCH_CLEAR in b.migrated) return false
        b.migrated += LIQ_SWITCH_CLEAR
        if (restoring || com.optionslab.app.BuildConfig.GOLD) return true
        val on = LiquidityRules.BOOKS.filter { b.armed[it.source] == true }
        for (a in on) {
            b.liveOk[a.source] = true
            b.status[a.source]?.takeIf { it in PAPER_ONLY_STATUS }?.let { b.status[a.source] = "$it · $LIQ_SWITCH_CLEARED" }
        }
        if (on.isEmpty()) return true
        val names = on.joinToString(", ") { "${LiquidityRules.underlyingOf(it)} ${LiquidityRules.minutesOf(it)}-min" }
        val words = "Liquidity 15+5: your switch on Home is on, so these books are now cleared for live by it: $names. Whenever the app is " +
            "in Live they trade on Zerodha (AI trades go live, the guard, the kill switch and the day lock still apply). Switch Liquidity " +
            "off on Home to stop them; from now on only your own switch on Home clears a book."
        runCatching { Diag.record("orb", words) }
        runCatching { Notifier.post(app, LIQ_SWITCH_NOTICE, Notifier.SCHEDULE, "Liquidity: live by your switch", words, "almanac") }
        runCatching { com.optionslab.app.ira.Autopilot.logLiqSwitchCleared(names) }
        return true
    }

    /** 16 Nov: the one-time mark of this update ([keepOpenWatched]): its key in [Book.migrated]. */
    internal const val ORB_REREST = "orb_rerest_2026_11_16"

    /**
     * Once, on the 16 Nov update (review): an ORB-family live trade (not Liquidity 15+5, which already told the cases apart) open with no
     * stop at Zerodha was left that way by the earlier build, which never noted whether Boss had cancelled it in Kite - so it is taken as
     * their choice ([Position.stopByYou]) and keeps being watched by the app, as before, never put back by itself (said once); new trades
     * and stops the exchange or Zerodha end from now on are put back ([restExchangeStop]). Not with "Keep a stop-loss order at the
     * exchange" on: that build put them back already, and they still are. True when [b] changed.
     */
    private fun keepOpenWatched(b: Book): Boolean {
        if (ORB_REREST in b.migrated) return false
        b.migrated += ORB_REREST
        // (Review: with "Keep a stop-loss order at the exchange" on, the earlier build was already putting these back: they still are.)
        if (runCatching { AppSettings.load().exchangeStops }.getOrDefault(false)) return true
        val kept = ArrayList<String>()
        for (i in b.positions.indices) {
            val p = b.positions[i]
            if (!p.open || !p.live || p.unconfirmed || p.stopOrderId != null || p.stopByYou) continue
            if (runCatching { armOf(p.arm).liquidity }.getOrDefault(true)) continue
            b.positions[i] = p.copy(stopByYou = true)
            kept += p.symbol
        }
        // (Review: told to Boss, not only noted - Settings now says stops are always put back, and these are not.)
        if (kept.isNotEmpty()) runCatching { com.optionslab.app.work.Alerts.error("${kept.size} open live ORB trade(s) have no stop at Zerodha " +
            "(${kept.joinToString(", ")}). In case you cancelled it in Kite, the app does not put it back: it watches the stop itself " +
            "and sells there. Set a stop at Zerodha yourself if you want one there.", "ORB live") }
        return true
    }

    /** 15 Nov: [clearBySwitch]'s notice. */
    private const val LIQ_SWITCH_NOTICE = 6948

    /** The one-time switch-off on this update (Boss's choice, 06 Oct): its key in [Book.migrated]. */
    internal const val OFF_LOSERS = "off_losers_2026_10_06"
    /** What the switched-off arms' rows and the diagnostics say. */
    const val SWITCHED_OFF = "switched off: paper record negative (Boss's choice 06 Oct)"

    /**
     * Once, on this update (Boss's 06 Oct paper diagnostics): ORB Sweep, Range Fade and both Liquidity 15+5 books are
     * switched off - their paper record was negative. An open position is still managed to its exit (as any disarm);
     * nothing is sold here. Never armed again by the app: only Boss arms them. True when [b] changed (it is then saved).
     */
    private fun switchOffLosers(b: Book): Boolean {
        if (OFF_LOSERS in b.migrated) return false
        for (src in listOf(SweepRules.ARM.source, RangeFadeRules.ARM.source) + LiquidityRules.BOOKS.map { it.source }) {
            if (b.armed[src] != true) continue
            b.armed[src] = false; b.liveOk[src] = false; b.pending.remove(src)
            b.status[src] = SWITCHED_OFF
            val label = (ALL_ARMS + LiquidityRules.ARM).firstOrNull { it.source == src }?.label ?: src
            runCatching { Diag.record("orb", "$label: $SWITCHED_OFF" +
                (if (b.positions.any { it.arm == src && it.open }) "; its open position is still managed to its exit" else "")) }
        }
        b.migrated += OFF_LOSERS
        return true
    }

    /**
     * Once, on this update (Boss's choice after six years of real data, 06 Oct: "Keep only Liquidity on paper",
     * [RetiredArms]): ORB and ORB Fresh - and ORB Sweep or Range Fade, if armed again since - are switched off
     * ("switched off: lost on 6 years of real data"; Boss un-retired them on 07 Oct, [unretire], run right after), an open position still managed to its exit; and Liquidity 15+5's
     * books are switched back on, ON PAPER ONLY and automatic (never cleared for Zerodha: Live still takes Boss's PIN or
     * fingerprint), reversing [switchOffLosers] for Liquidity alone. A restore not yet disarmed ([restoring]) switches
     * nothing on. Nothing is switched on in IraGoldAlgo. True when [b] changed (it is then saved).
     */
    private fun retire(b: Book, restoring: Boolean): Boolean {
        if (RetiredArms.MIGRATION in b.migrated) return false
        val switches = (b.armed.keys + LiquidityRules.BOOKS.map { it.source }).distinct().map { src ->
            RetiredArms.Switch(src, b.armed[src] == true, b.positions.any { it.arm == src && it.open })
        }
        val books = if (com.optionslab.app.BuildConfig.GOLD) emptyList() else LiquidityRules.BOOKS
        for (c in RetiredArms.migrate(switches, books, done = false, restoring = restoring)) {
            b.armed[c.source] = c.armed; b.liveOk[c.source] = false; b.pending.remove(c.source)
            if (c.armed) { b.auto[c.source] = true; b.since[c.source] = now().toString() }
            b.watched.keys.removeAll { it.startsWith("${c.source}|") }
            b.status[c.source] = if (c.armed) RetiredArms.BACK_ON else RetiredArms.SWITCHED_OFF
            runCatching { Diag.record("orb", c.log) }
        }
        b.migrated += RetiredArms.MIGRATION
        return true
    }

    /**
     * Once, on this update (Boss's explicit choice, 07 Oct 2026: [RetiredArms.unretire]): ORB, ORB Fresh, ORB Sweep and
     * Range Fade come out of retirement - each not armed now is switched on, automatic, ON PAPER ONLY (liveOk false: never
     * cleared for Zerodha; Live still takes Boss's PIN or fingerprint, and in Live an entry waits for his approval with it),
     * logged "switched back on, paper only (Boss un-retired it 07 Oct 2026)". Their own rules, the account guard, the daily
     * loss limit, the day stop and the expiry square-off apply as to any arm. A restore not yet disarmed ([restoring]) and
     * IraGoldAlgo (which only talks) switch nothing on. True when [b] changed (it is then saved).
     */
    private fun unretire(b: Book, restoring: Boolean): Boolean {
        if (RetiredArms.UNRETIRE in b.migrated) return false
        val switches = RetiredArms.ALL.map { r -> RetiredArms.Switch(r.arm.source, b.armed[r.arm.source] == true) }
        val changes = if (com.optionslab.app.BuildConfig.GOLD) emptyList() else RetiredArms.unretire(switches, done = false, restoring = restoring)
        for (c in changes) {
            b.armed[c.source] = true; b.auto[c.source] = true; b.liveOk[c.source] = false; b.pending.remove(c.source)
            b.since[c.source] = now().toString()
            // Armed just now: the next decision takes only a fresh break, never one already under way.
            b.watched.keys.removeAll { it.startsWith("${c.source}|") }
            b.status[c.source] = RetiredArms.UNRETIRED
            runCatching { Diag.record("orb", c.log) }
        }
        b.migrated += RetiredArms.UNRETIRE
        return true
    }

    /**
     * Once, on this update (Boss's 07 Oct choice, research/HUNT_H4.md): Liquidity 15+5's MIDCPNIFTY books join its one switch
     * ([LiquidityRules.joinMidcp]). When the switch is on, each MIDCPNIFTY book not armed is switched on with the other books'
     * automatic / approve choice, ON PAPER: liveOk stays false, so it is never cleared for Zerodha by this change - in Live
     * its entry waits for Boss's approval with his PIN or fingerprint, and every guard applies as to the other books. When the
     * switch is off nothing changes (they come on with it). A restore not yet disarmed ([restoring]) and IraGoldAlgo (which
     * only talks) switch nothing on. True when [b] changed (it is then saved).
     */
    private fun joinMidcp(b: Book, restoring: Boolean): Boolean {
        if (LiquidityRules.MIDCP_JOIN in b.migrated) return false
        val changes = if (com.optionslab.app.BuildConfig.GOLD) emptyList()
            else LiquidityRules.joinMidcp(b.armed, done = false, restoring = restoring)
        // The switch's own choice: automatic unless one of its armed books asks for approvals.
        val automatic = LiquidityRules.BOOKS.filter { b.armed[it.source] == true }.all { b.auto[it.source] != false }
        for (c in changes) {
            b.armed[c.source] = true; b.auto[c.source] = automatic; b.liveOk[c.source] = false; b.pending.remove(c.source)
            b.since[c.source] = now().toString()
            // Armed just now: the next decision takes only a fresh break, never one already under way.
            b.watched.keys.removeAll { it.startsWith("${c.source}|") }
            b.status[c.source] = LiquidityRules.MIDCP_JOINED
            runCatching { Diag.record("orb", c.log) }
        }
        b.migrated += LiquidityRules.MIDCP_JOIN
        return true
    }

    /**
     * Once, for a book with no size saved (one from before the setting, or a new one): Liquidity 15+5 takes
     * [LiquidityLots.DEFAULT] (1 lot since 08 Oct, research X1), said in the arm log. True when [b] changed (it is then saved).
     */
    private fun sizeLiquidity(b: Book): Boolean {
        if (b.liqLots != null) return false
        b.liqLots = LiquidityLots.DEFAULT
        b.migrated += LiquidityLots.MIGRATION
        if (!com.optionslab.app.BuildConfig.GOLD) runCatching { Diag.record("orb", LiquidityLots.MIGRATED) }
        return true
    }

    /**
     * Once (08 Oct, research X1, Boss's yes): a book at 2 lots Boss never chose by hand ([Book.liqLotsChosen]; a size saved
     * before that was recorded counts as not known) goes back to 1 lot, said in the arm log and in a notice. A size Boss
     * chooses afterwards stays. True when [b] changed (it is then saved).
     */
    private fun oneLot(b: Book): Boolean {
        if (LiquidityLots.ONE_LOT_MIGRATION in b.migrated) return false
        b.migrated += LiquidityLots.ONE_LOT_MIGRATION
        val n = LiquidityLots.oneLot(b.liqLots, b.liqLotsChosen) ?: return true
        b.liqLots = n
        if (!com.optionslab.app.BuildConfig.GOLD) {
            runCatching { Diag.record("orb", "Liquidity 15+5: " + LiquidityLots.ONE_LOT_NOTICE) }
            runCatching { Notifier.post(app, 6958, Notifier.SCHEDULE, "Liquidity size: 1 lot", LiquidityLots.ONE_LOT_NOTICE + ".", "almanac") }
        }
        return true
    }

    /**
     * Once, on this update (Boss's OK, 9 Oct 2026: [ParkedArms]): Liquidity 15+5's FINNIFTY books (-Rs 8,035 over 8 paper
     * trades) and ORB Sweep (-Rs 4,498 over 6) are switched off on PAPER terms - an open position is still managed to its
     * exit, nothing is sold or armed here, and no other arm's Live clearance is touched (a parked book is not cleared for
     * Zerodha: switched back on in Live, it asks for the PIN again). FINNIFTY stays parked under Liquidity's one switch until
     * Boss switches it back on ([unparkLiquidity]); ORB Sweep has its own switch. Told once (the arm log and a notice) when
     * something was on. True when [b] changed (it is then saved).
     */
    private fun park(b: Book): Boolean {
        val plan = ParkedArms.plan(b.armed, done = ParkedArms.MIGRATION in b.migrated) ?: return false
        val parkedBooks = plan.parked.flatMap { i -> ParkedArms.books(i).map { it.source } }
        for (src in (plan.off + parkedBooks).distinct()) {
            val wasOn = b.armed[src] == true
            b.armed[src] = false; b.liveOk[src] = false; b.pending.remove(src)
            b.status[src] = ParkedArms.PARKED
            if (wasOn) {
                val label = (ALL_ARMS + LiquidityRules.ARM).firstOrNull { it.source == src }?.label ?: src
                runCatching { Diag.record("orb", "$label: ${ParkedArms.PARKED}" +
                    (if (b.positions.any { it.arm == src && it.open }) "; its open position is still managed to its exit" else "")) }
            }
        }
        b.parked += plan.parked
        b.migrated += ParkedArms.MIGRATION
        if (plan.off.isNotEmpty() && !com.optionslab.app.BuildConfig.GOLD) {
            runCatching { Diag.record("orb", ParkedArms.NOTICE) }
            runCatching { Notifier.post(app, 6956, Notifier.SCHEDULE, "Two losing paper arms switched off", ParkedArms.NOTICE, "almanac") }
        }
        return true
    }

    /**
     * Once, on this update (Boss's choice, 13 Oct 2026: "Switch off ORB, ORB Fresh, Sweep and Range Fade", [FamilyPark]): each
     * of the four that is armed is switched off - on paper, and in Live too (its Zerodha clearance goes; switched back on in
     * Live, it asks for the PIN again) - and all four are marked parked. Disarming only stops new entries: an open position is
     * still managed to its own exit, nothing is sold here, nothing is armed, Liquidity and the Hero arm are not touched.
     * Told once (the arm log, a notice, and the Autopilot change timeline, where one tap switches it back on, on paper) for
     * each arm that was on. Never runs again: an arm Boss switches back on stays on. True when [b] changed (it is then saved).
     */
    private fun parkFamily(b: Book): Boolean {
        val plan = FamilyPark.plan(b.armed, done = FamilyPark.MIGRATION in b.migrated) ?: return false
        for (src in plan.off) {
            val wasLive = b.liveOk[src] == true
            val automatic = b.auto[src] != false
            b.armed[src] = false; b.liveOk[src] = false; b.pending.remove(src)
            b.status[src] = FamilyPark.PARKED
            val label = ALL_ARMS.firstOrNull { it.source == src }?.label ?: src
            val holding = b.positions.any { it.arm == src && it.open }
            runCatching { Diag.record("orb", "$label: ${FamilyPark.PARKED}" + (if (wasLive) " (it was armed for Zerodha)" else "") +
                (if (holding) "; its open position is still managed to its exit" else "")) }
            if (!com.optionslab.app.BuildConfig.GOLD) runCatching { com.optionslab.app.ira.Autopilot.logBossPark(src, label, automatic, wasLive, holding) }
        }
        b.famParked += plan.parked
        b.migrated += FamilyPark.MIGRATION
        if (plan.off.isNotEmpty() && !com.optionslab.app.BuildConfig.GOLD) {
            runCatching { Diag.record("orb", FamilyPark.NOTICE) }
            runCatching { Notifier.post(app, 6959, Notifier.SCHEDULE, "ORB, ORB Fresh, Sweep and Range Fade parked", FamilyPark.NOTICE, "almanac") }
        }
        return true
    }

    /**
     * Once, on this update (Boss's cleanup, 15 Oct 2026: [ArmsCleanup.liquidityPlan]): Liquidity 15+5 keeps its four indices' books
     * as Boss chose - FINNIFTY unparked (9 Oct's park) and, when its switch is on, each FINNIFTY or MIDCPNIFTY book that is off
     * switched on with the switch's automatic / approve choice, ON PAPER (never cleared for Zerodha: in Live their entries wait for
     * Boss's approval until he arms Liquidity again with his PIN). Labelled "Boss's choice" on the books and logged in Autopilot's
     * change timeline (one tap parks the index again). BANKNIFTY's books are never touched, so its plain-rules record stays
     * separable for 26 Oct. A restore not yet disarmed switches nothing on; IraGoldAlgo nothing at all. Once (its key in the
     * book's one-time changes; a new book starts with it done). Called by [HiddenArms]'s worker after the positions step, off the
     * order path. The indices it changed (empty: nothing to do, or already done).
     */
    suspend fun keepLiquidityFour(): List<String> = lock.withLock {
        val b = book()
        if (ArmsCleanup.LIQ_MIGRATION in b.migrated) return@withLock emptyList()
        b.migrated += ArmsCleanup.LIQ_MIGRATION
        val restoring = com.optionslab.app.security.SecurePrefs.getBoolean(Backup.DISARM, false)
        if (com.optionslab.app.BuildConfig.GOLD) { save(b); return@withLock emptyList() }
        val changed = liquidityFour(b, restoring)
        save(b)
        changed
    }

    private fun liquidityFour(b: Book, restoring: Boolean): List<String> {
        val plan = ArmsCleanup.liquidityPlan(b.armed, b.parked, done = false, restoring = restoring) ?: return emptyList()
        val automatic = LiquidityRules.BOOKS.filter { b.armed[it.source] == true }.all { b.auto[it.source] != false }
        b.parked.removeAll(plan.unpark)
        for (src in plan.on) {
            b.armed[src] = true; b.auto[src] = automatic; b.liveOk[src] = false; b.pending.remove(src)
            b.since[src] = now().toString()
            // Switched on just now: the next decision takes only a fresh break, never one already under way.
            b.watched.keys.removeAll { it.startsWith("$src|") }
            b.status[src] = ArmsCleanup.LIQ_ON
        }
        val indices = (plan.unpark + plan.on.map { LiquidityRules.underlyingOf(armOf(it)) }).toSortedSet()
        for (i in indices) {
            runCatching { Diag.record("orb", "Liquidity 15+5 $i: " + ArmsCleanup.LIQ_ON +
                (if (plan.on.none { LiquidityRules.underlyingOf(armOf(it)) == i }) "; its books come on with the switch" else "")) }
            runCatching { com.optionslab.app.ira.Autopilot.logLiqIndexOn(i, parked = i in plan.unpark) }
        }
        return indices.toList()
    }

    /**
     * The timeline's Undo of the 15 Oct change for [index] (FINNIFTY or MIDCPNIFTY): the index parked again - its books off, on
     * paper terms (an open position is still managed to its exit; nothing is sold), switched back on from the row as before.
     */
    suspend fun parkLiquidity(index: String): String = lock.withLock {
        if (index !in ArmsCleanup.LIQ_INDICES) return@withLock "Only FINNIFTY or MIDCPNIFTY can be parked here."
        val b = book()
        b.parked += index
        for (a in ParkedArms.books(index)) {
            b.armed[a.source] = false; b.liveOk[a.source] = false; b.pending.remove(a.source)
            b.status[a.source] = "parked again by your undo; switch it back on under the row"
        }
        save(b)
        val holding = b.positions.any { it.open && ParkedArms.books(index).any { a -> a.source == it.arm } }
        runCatching { Diag.record("orb", "Liquidity 15+5 $index: parked again by your undo" + if (holding) "; its open position is still managed to its exit" else "") }
        "Liquidity 15+5 $index is parked again" + if (holding) "; its open position is still managed to its exit." else "."
    }

    // ---- 15 Oct: Boss's arms cleanup ([ArmsCleanup], [HiddenArms]) -----------------------------------------------------------

    /** [source]'s switch and whether it holds anything (an open or unconfirmed position); null when it is not one of the arms. */
    suspend fun cleanupNow(source: String): Pair<Boolean, Boolean>? = lock.withLock {
        if (ALL_ARMS.none { it.source == source }) return@withLock null
        val b = book()
        (b.armed[source] == true) to b.positions.any { it.arm == source && it.open }
    }

    /**
     * 15 Nov: what switching Liquidity 15+5 on in Live says when it is not Boss's own switch on Home (Jarvis by voice, the day plan,
     * an automation): only the switch clears it for Zerodha ([setArmed]'s bySwitch).
     */
    const val LIQUIDITY_SWITCH_ONLY = "The app is in Live: switch Liquidity 15+5 on with its own switch on Home (no PIN needed)."

    /** What an arm hidden by Boss's 15 Oct cleanup says instead of entering. */
    const val HIDDEN_NO_ENTRY = "hidden (your 15 Oct cleanup): no new entry; an open position keeps its own exits"

    /** [source]'s automatic / approve choice as the book keeps it (a restore puts it back with it). */
    suspend fun autoOf(source: String): Boolean = lock.withLock { book().auto[source] != false }

    /** The cleanup switches [source] off (only when it holds nothing): its automatic / approve choice is kept for a restore. */
    suspend fun cleanupOff(source: String): String = setArmed(source, false, automatic = autoOf(source))

    /**
     * Boss switches a parked Liquidity index back on (its row; [ParkedArms]). With Liquidity's switch on, the index's books
     * come on with the switch's automatic / approve choice, ON PAPER (never cleared for Zerodha here: in Live their entries
     * wait for his approval until he arms Liquidity again with his PIN); with it off, they come on with the switch.
     */
    suspend fun unparkLiquidity(index: String): String = lock.withLock {
        val b = book()
        if (index !in b.parked) return@withLock "Liquidity 15+5 $index is not parked."
        b.parked.remove(index)
        val on = LiquidityRules.BOOKS.any { b.armed[it.source] == true }
        val automatic = LiquidityRules.BOOKS.filter { b.armed[it.source] == true }.all { b.auto[it.source] != false }
        for (a in ParkedArms.books(index)) {
            b.status[a.source] = if (on) "switched back on by you (paper)" else ""
            if (on) {
                b.armed[a.source] = true; b.auto[a.source] = automatic; b.liveOk[a.source] = false; b.pending.remove(a.source)
                b.since[a.source] = now().toString()
                // Switched on just now: the next decision takes only a fresh break, never one already under way.
                b.watched.keys.removeAll { it.startsWith("${a.source}|") }
            }
        }
        save(b)
        runCatching { Diag.record("orb", "Liquidity 15+5 $index: switched back on by you" + if (on) ", on paper" else "; it comes on with the switch") }
        if (!on) "Liquidity 15+5 $index is no longer parked: it comes on with Liquidity's switch."
        // 15 Nov: not cleared for Zerodha here ([liveCleared]): on paper until Boss switches Liquidity on himself on Home.
        else "Liquidity 15+5 $index is back on, on paper" + (if (liveNow()) " until you switch Liquidity on yourself on Home (off, then on): that clears it for Zerodha." else ".")
    }

    /** Liquidity 15+5's size in [b]: lots a new entry buys. */
    private fun lotsOf(b: Book): Int = b.liqLots ?: LiquidityLots.DEFAULT

    /**
     * The positions a save keeps: the last [KEEP_POSITIONS], and every LIVE row before them. 21 Oct, enhancement round 3 (idea 6,
     * round 1's idea 14): Liquidity BANKNIFTY's live stop rule (-Rs 40,000 from its best, -Rs 13,000 a month) walks its live record
     * from this book; trimmed to the last 2,000 rows of every arm, a busy paper month pushed the oldest live trades - and the
     * rule's best point - out at the next restart, and the drawdown read smaller than it was. Live rows are few (real money), so
     * all of them are kept, oldest first, in the book's own encrypted file.
     */
    internal fun keptPositions(all: List<Position>): List<Position> {
        if (all.size <= KEEP_POSITIONS) return all
        return all.dropLast(KEEP_POSITIONS).filter { it.live } + all.takeLast(KEEP_POSITIONS)
    }
    internal const val KEEP_POSITIONS = 2000

    private fun save(b: Book) {
        // 26 Oct: a book read before "Erase everything" is dropped, never written back.
        if (b.gen != wipeGen) return
        val text = encodeBook(b)
        val idle = b.armed.values.none { it } && b.positions.none { it.open } && b.pending.isEmpty()
        // 19 Nov 2026 (second review): one order of writes with [saveSoon]: a write it left pending is dropped (this newer book carries it).
        synchronized(ioLock) {
            if (b.gen != wipeGen) return
            synchronized(writeOrder) { saveSeq++; pendingWrite = null }
            writeText(text, idle)
        }
        cache = b
        hints(b)
        if (wipedSince(b)) { runCatching { file.delete() }; writtenText = null; writtenStat = null; return }
        // The trade manager's one call (10 Oct; memory only, after the book is written): the ORB arms, Liquidity 15+5 and the
        // Hero arm, SHADOW by default - it records only; these arms' own exits stay authoritative (their exits do not take its word).
        runCatching {
            val today = today()
            TradeManagerHost.sync(b.positions.filter { it.open || it.exitTime?.toLocalDate() == today }.mapNotNull { runCatching { managed(it) }.getOrNull() })
        }
        // 16 Oct: each open position's own exits for the position popup's "Exits" line (memory only).
        runCatching { com.optionslab.ira.ArmExits.set("orb", b.positions.filter { it.open }.mapNotNull { runCatching { exitsOf(it) }.getOrNull() }) }
    }

    /**
     * 19 Nov 2026 (second review of B5: the entry's "intent" was written to the vault - up to 1.5 s on a Pixel's StrongBox, under the
     * book's lock - before its order went): [save], with the vault write on this book's own writer thread, as [Strategies] does. In
     * memory at once; written next unless a newer [save] or [saveSoon] wrote first - never an older book over a newer one ([saveSeq]
     * under [writeOrder]; one write at a time under [ioLock]). Never waits on the disk. A failed write is tried again with the next.
     */
    private fun saveSoon(b: Book) {
        if (b.gen != wipeGen) return
        val text = encodeBook(b)
        synchronized(writeOrder) {
            if (b.gen != wipeGen) return
            saveSeq++; pendingWrite = PendingWrite(saveSeq, text, b.gen)
        }
        cache = b
        hints(b)
        if (wipedSince(b)) return
        bookWriter.kick()
    }

    private class PendingWrite(val seq: Long, val text: String, val gen: Int)
    /** Guards [saveSeq] and [pendingWrite]; held only for a moment, never through a write. */
    private val writeOrder = Any()
    /** Held through each vault write of the book, taken before [writeOrder]: one at a time, so the newest book is the last on disk. */
    private val ioLock = Any()
    private var saveSeq = 0L
    private var pendingWrite: PendingWrite? = null
    private val bookWriter = com.optionslab.ira.WriteBehind(com.optionslab.ira.WriteBehind.thread("orb-writer")) { flushSoon() }

    private fun flushSoon() {
        synchronized(ioLock) {
            val w = synchronized(writeOrder) { pendingWrite.also { pendingWrite = null }?.takeIf { it.seq == saveSeq } } ?: return
            if (w.gen != wipeGen) return                          // erased since: never written back
            try {
                writeText(w.text, idle = false)
                // (Erased while it was being written: the file it wrote goes again.)
                if (w.gen != wipeGen) { runCatching { file.delete() }; writtenText = null; writtenStat = null }
            } catch (e: Exception) {
                synchronized(writeOrder) { if (pendingWrite == null && w.seq == saveSeq) pendingWrite = w }
                runCatching { Diag.record("orb", "The arms' book could not be written (${e.javaClass.simpleName}); it is written again with the next change") }
            }
        }
    }

    /**
     * TEST ONLY (19 Nov 2026, order speed): called right after a live entry's intent is handed to the writer, before its order goes - so
     * a test can time the POST from there. Null in the app; set only in debug builds.
     */
    @Volatile internal var testIntentQueued: (() -> Unit)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** TEST ONLY: every [saveSoon] on disk now, [timeoutMs] at most. Never on the order path. */
    internal fun awaitWrites(timeoutMs: Long = 10_000L): Boolean = bookWriter.await(timeoutMs).also { flushSoon() }

    /** The vault write itself, under [ioLock]: skipped when these bytes are already on disk (below). */
    private fun writeText(text: String, idle: Boolean) {
        // Battery: an idle book (nothing armed, open or waiting) whose bytes are already on disk, as this process last
        // wrote them, is not encrypted and synced again ([com.optionslab.ira.OrbIdleSave]); anything else is, as before.
        val untouched = writtenStat != null && file.exists() && writtenStat == (file.length() to file.lastModified())
        // ANR fix (9 Oct): an armed or holding book whose bytes are the ones on disk is not written again either - with a
        // position open the 15-second stop check saved the same book four times a minute, each a Keystore encryption that
        // every other vault read or write (the screen's included) queued behind. Anything that changed is written as before.
        val unchanged = text == writtenText && untouched
        if (com.optionslab.ira.OrbIdleSave.writes(idle, text == writtenText, untouched) && !unchanged) {
            writtenText = null; writtenStat = null
            Vault.writeFile(file, text.toByteArray(Charsets.UTF_8))
            writtenText = text; writtenStat = file.length() to file.lastModified()
        }
    }

    private fun encodeBook(b: Book): String {
        val o = JSONObject()
        o.put("armed", JSONObject(b.armed as Map<*, *>))
        o.put("auto", JSONObject(b.auto as Map<*, *>))
        o.put("liveOk", JSONObject(b.liveOk as Map<*, *>))
        b.legs?.let { l -> o.put("legs", JSONObject().put("day", l.day.toString()).put("strike", l.strike).put("expiry", l.expiry.toString())
            .put("ce", contractJson(l.ce)).put("pe", contractJson(l.pe))) }
        b.range?.let { o.put("range", JSONArray().put(it.first).put(it.second).put(b.rangeDay.toString())) }
        // Only today's decided bars matter; older days are dropped.
        val day = today().toString()
        o.put("decided", JSONObject().apply { b.decided.filterKeys { it.endsWith(day) }.forEach { (k, v) -> put(k, JSONArray(v.toList())) } })
        o.put("watched", JSONObject().apply { b.watched.filterKeys { it.endsWith(day) }.forEach { (k, v) -> put(k, v) } })
        // (20 Nov 2026: today's booked exit sales - a lost exit's search never takes one for its own after a restart.)
        // (Each with its own day, so a restart never stamps an older one as today's.)
        o.put("exitSales", JSONObject().apply { exitSales.filterValues { it == day }.keys.forEach { put(it, day) } })
        // (22 Nov 2026, B6 review: the app's own stop cancels, by order id and when marked - a restart never reads one as Boss's; and a
        // closed trade's found lost stop still being cancelled, with the day it was taken out - the sweep goes on after a restart that day.)
        ownCancelsKept().takeIf { it.isNotEmpty() }?.let { kept -> o.put("ownCancels", JSONObject().apply { kept.forEach { (id, at) -> put(id, at) } }) }
        // (22 Nov 2026, B6 review, second round: and the stops Boss's manual exit from the app or a square-off marked ([exitMarks]).)
        marksKept(exitMarks).takeIf { it.isNotEmpty() }?.let { kept -> o.put("exitMarks", JSONObject().apply { kept.forEach { (id, at) -> put(id, at) } }) }
        synchronized(sweepCancels) { if (sweepCancels.isNotEmpty()) o.put("sweepCancels", JSONObject().apply {
            sweepCancels.forEach { (id, what) -> put(id, JSONObject().put("what", what).put("day", sweepDays[id] ?: day)) } }) }
        o.put("positions", JSONArray().apply {
            keptPositions(b.positions).forEach { p ->
                put(JSONObject().put("arm", p.arm).put("symbol", p.symbol).put("right", p.right).put("qty", p.qty).put("entry", p.entry)
                    .put("entryTime", p.entryTime.toString()).put("signalBar", p.signalBar.toString())
                    .put("entryOrderId", p.entryOrderId ?: "").put("stopOrderId", p.stopOrderId ?: "")
                    .apply { p.stopTrigger?.let { put("stopTrigger", it) }; p.exit?.let { put("exit", it) } }
                    .put("exitTime", p.exitTime?.toString() ?: "").put("why", p.why ?: "").put("charges", p.charges)
                    .put("live", p.live).put("kite", p.kite ?: "").put("unconfirmed", p.unconfirmed)
                    .apply { p.level?.let { put("level", it) }; p.target?.let { put("target", it) } }
                    .put("ladder", p.ladder).apply { p.peak?.let { put("peak", it) } }.put("timed", p.timed)
                    .apply { p.near?.let { put("near", it) }; p.volSkip?.let { put("volSkip", it) }; p.strong?.let { put("strong", it) } }
                    .apply { if (p.sold > 0) put("sold", p.sold); p.soldAt?.let { put("soldAt", it) }; p.soldTime?.let { put("soldTime", it.toString()) }
                        if (p.seen.isNotEmpty()) put("seen", seenJson(p.seen)); p.lot?.let { put("lot", it) }; p.low?.let { put("low", it) }
                        p.mgrStop?.let { put("mgrStop", it) }
                        p.stopPts?.let { put("stopPts", it) }; p.targetPts?.let { put("targetPts", it) }; p.liq?.let { put("liq", liqText(it)) }
                        p.trail?.let { put("trail", it) }; p.trailFrom?.let { put("trailFrom", it) }; p.be?.let { put("breakeven", it) }
                        if (p.priceRetry) put("priceRetry", true); p.capped?.let { put("capped", it) }; if (p.fixed) put("step500", true); if (p.lock) put("stepLock", true)
                        p.entryQty?.let { put("entryQty", it) }; if (p.stopByYou) put("stopByYou", true); if (p.appCancelled) put("appCancelled", true)
                        p.stepFrom?.let { put("stepFrom", it) }
                        p.askedLots?.let { put("askedLots", it) }; p.sentLots?.let { put("sentLots", it) }
                        // (20 Nov 2026: its lost exit sale, so a restart keeps the hold, the others' wait and the 2-minute window.)
                        p.lostId?.takeIf { p.open && p.live }?.let { lostExits[it] }?.takeIf { !it.notFound }?.let { l -> put("lostExit", JSONObject()
                            .put("why", l.why).put("qty", l.order.quantity).put("type", l.order.orderType).put("tag", l.order.tag)
                            .put("since", l.sinceMs).put("sentAt", l.sentAt.toString()).put("label", l.label)
                            .put("known", org.json.JSONArray(l.known.toList())).apply { l.foundId?.let { put("found", it).put("asked", l.askedMs) } }) } })
            }
        })
        o.put("pending", JSONObject().apply { b.pending.forEach { (k, p) -> put(k, JSONObject().put("right", p.right).put("bar", p.signalBar.toString()).put("expires", p.expires.toString())
            .apply { p.strike?.let { put("strike", it) }; p.level?.let { put("level", it) }; p.target?.let { put("target", it) }; p.near?.let { put("near", it) }
                p.volSkip?.let { put("volSkip", it) }; p.strong?.let { put("strong", it) }; p.live?.let { put("live", it) } }) } })
        o.put("status", JSONObject(b.status as Map<*, *>))
        o.put("replays", JSONObject().apply { b.replays.entries.toList().takeLast(120).forEach { (k, v) -> put(k, v) } })
        o.put("liqReplays", JSONObject().apply { b.liqReplays.entries.toList().takeLast(60).forEach { (k, v) -> put(k, v) } })
        o.put("upDays", JSONObject(b.upDays as Map<*, *>))
        o.put("since", JSONObject(b.since as Map<*, *>))
        o.put("migrated", JSONArray(b.migrated.sorted()))
        b.liqLots?.let { o.put("liqLots", it) }
        b.liqLotsAsk?.let { o.put("liqLotsAsk", it) }
        if (b.liqLotsChosen) o.put("liqLotsChosen", true)
        if (b.parked.isNotEmpty()) o.put("liqParked", JSONArray(b.parked.sorted()))
        if (b.famParked.isNotEmpty()) o.put("famParked", JSONArray(b.famParked.sorted()))
        b.sweepFrom?.let { o.put("sweepFrom", it) }
        return o.toString()
    }

    /**
     * [p]'s exits as the position popup words them: the ORB family's resting stop and fixed target on the premium; Liquidity
     * 15+5's premium stop with its target on the index and its time check; the Hero arm's -60% stop on a minute's close and
     * its 5x / 20x targets. The exit time is the trade manager's ([managed]); a position not confirmed at Zerodha yet has none.
     */
    private fun exitsOf(p: Position): com.optionslab.ira.ArmExits.Entry? {
        val m = managed(p) ?: return null
        val arm = armOf(p.arm)
        return when {
            arm.hero -> TradeManagerHost.exitsOf("orb", m.copy(stop = HeroRules.stopLevel(p.entry),
                target = if (p.sold > 0) HeroRules.target2(p.entry) else HeroRules.target1(p.entry)),
                if (p.sold > 0) "half sold at 5×, stop on a 1-minute close" else "half at 5×, the rest at 20×, stop on a 1-minute close")
            arm.liquidity -> {
                val lp = liqParams(p)
                val check = if (p.timed) null else lp.timeStopMinutes.let { mins ->
                    p.entryTime.plusMinutes(mins).let { "%d-minute check at %02d:%02d".format(java.util.Locale.ENGLISH, mins, it.hour, it.minute) } }
                TradeManagerHost.exitsOf("orb", m, listOfNotNull(p.target?.takeIf { lp.nextLevelTarget }?.let { "target on the index at %.2f".format(java.util.Locale.ENGLISH, it) }, check,
                    // 15 Nov (Boss's exception): the breakeven rule it entered with, labelled, at its stop now. The label holds a "%"
                    // ("+5%"), so it is joined after the number is formatted, never put into the format pattern.
                    fixedWords(p))
                    .joinToString(" · ").ifEmpty { null })
            }
            else -> TradeManagerHost.exitsOf("orb", m)
        }
    }

    /** [p]'s id for the trade manager. */
    private fun managerId(p: Position): String = "orb:${p.arm}:${p.symbol}:${p.entryTime}"

    private fun epochMs(t: LocalDateTime): Long = t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    /**
     * [p] as the trade manager follows it: a bought option; its resting stop on the premium as it was handed over (the ORB
     * arms', Liquidity's 15%), the ORB arms' fixed target on the premium (Liquidity's target is an index level and the Hero
     * arm has none: none here), out by 15:10 (the Hero arm 15:05; on its option's expiry day the expiry square-off's 15:05). A position not confirmed at Zerodha yet is not handed over.
     */
    private fun managed(p: Position): TradeManagerHost.ArmTrade? {
        if (p.unconfirmed) return null
        val arm = armOf(p.arm)
        // One mode per arm (R12 round 2): ORB, ORB Fresh, ORB Sweep and Range Fade each their own family.
        val fam = when { arm.liquidity -> "liquidity"; arm.hero -> "hero"; else -> arm.source }
        val target = if (arm.liquidity || arm.hero) null else ProfitLock.targetOf(arm)?.let { p.entry + paramsOf(p).targetPoints }
        // The arm's own profit-lock ladder, so the record follows its original rules honestly (the record only).
        val ladder = ladderTarget(p)
        val cost = ProfitLock.roundTripPerUnit(p.entry, p.qty)
        val lockAt: ((Double) -> Double?)? = ladder?.let { tg -> { peak: Double -> ProfitLock.level(p.entry, tg, peak, cost)?.let { ProfitLock.onTick(it) } } }
        return TradeManagerHost.ArmTrade(fam, managerId(p), arm.label, com.optionslab.ira.AutoSide.underlyingOf(p.symbol) ?: OrbRules.UNDERLYING,
            if (p.right == "PE") -1 else 1, p.symbol, p.entry, p.qty, if (arm.hero) null else p.stopTrigger, target,
            TradeManagerHost.nseSquareOffMs(p.day, if (arm.hero) HeroRules.EXIT_AT else OrbRules.SQUARE_OFF, p.symbol), p.live, epochMs(p.entryTime), p.exit, p.why, p.exitTime?.let { epochMs(it) }, kite = p.kite,
            lockAt = lockAt)
    }

    /** Why a position's resting stop filled: the trade manager's lock (R12, ORB Fresh paper), the profit lock, or its stop. */
    private fun stopWhy(p: Position): String = when {
        p.mgrStop != null && p.stopTrigger != null && kotlin.math.abs(p.stopTrigger - p.mgrStop) < 1e-9 -> MANAGER_LOCK
        // 22 Oct: Boss's paper breakeven raised the stop to the price paid plus charges (checked first: its level is its own).
        beStopped(p) -> LiquidityBreakeven.WHY
        // 15 Nov (Boss's exception; review): a stop resting at the stop rule's own levels is that rule's exit ("breakeven lock", or an
        // older record's "₹500 stop" / "step trail") - before Boss's trail and the lock, whose "at or above the entry" would otherwise take a stop-rule stop as theirs.
        fixedWhy(p) != null -> fixedWhy(p)!!
        // Boss's paper trail raised the stop to its own level (13 Oct review: a lab lock variant's stop is the lock's, not the trail's).
        trailStopped(p) -> LiquidityTrail.WHY
        lockedStop(p) -> "profit_lock"
        else -> "stop"
    }

    /**
     * 16 Nov: the "Kept apart" note of closed rule trades [trailed] (all on the rule, none on Boss's own trail / breakeven): V4's record,
     * the fourth version's (R186's), or - a mix - each version's count with its record (review: their records stay apart).
     */
    internal fun fixedNote(trailed: List<Position>): String = when {
        trailed.all { it.lock } -> "your 16 Nov choice; ${com.optionslab.engine.orb.StepTrail.LOCK_RECORD}"
        trailed.none { it.lock } -> "your 15 Nov choice; ${com.optionslab.engine.orb.StepTrail.RECORD}"
        else -> "your 15 Nov choice (${trailed.count { !it.lock }}): ${com.optionslab.engine.orb.StepTrail.RECORD}; " +
            "your 16 Nov choice (${trailed.count { it.lock }}): ${com.optionslab.engine.orb.StepTrail.LOCK_RECORD}"
    }

    /** 15 Nov: [com.optionslab.engine.orb.StepTrail], by a short name (a getter: never read before this object's own start). */
    private val FIXED: com.optionslab.engine.orb.StepTrail get() = com.optionslab.engine.orb.StepTrail

    /** 15 Nov: [p]'s lot size (the rule's levels are per lot): its contract's lot at the entry, its quantity when not known. */
    private fun lotOf(p: Position): Int = p.lot?.takeIf { it > 0 } ?: p.qty.coerceAtLeast(1)

    /**
     * 15 Nov: [p]'s stop-rule stop now (null: not a stop-rule Liquidity trade, or no level), on the [tick]: the breakeven
     * once its best price ([Position.peak]) was 10% up.
     */
    internal fun fixedStopOf(p: Position, tick: Double = OrbRules.TICK): Double? =
        if (!p.fixed || !FIXED.covers(p.arm)) null else FIXED.levelOf(p.entry, lotOf(p), p.peak, tick, ladderOf(p, tick))

    /**
     * 16 Nov: [p]'s quantity at the entry ([Position.entryQty]; an older trade's: what it holds plus any part sold early), for the lock's
     * per-lot rupees: a part sale never changes where the lock starts (review).
     */
    private fun lockQty(p: Position): Int = (p.entryQty ?: (p.qty + p.sold)).coerceAtLeast(1)


    /**
     * 16 Nov: [p]'s lock levels (null: V4 alone, or not a rule trade): built once for the trade on its fill, lot and entry quantity and
     * kept, so the full-cadence checks never redo the charge maths. Pure memory; a trade whose fill or quantity is not the ladder's (a
     * late confirmation) gets its own. Bounded: the trades of a day.
     */
    private fun ladderOf(p: Position, tick: Double = OrbRules.TICK): com.optionslab.engine.orb.StepTrail.Ladder? =
        lockOf(p)?.let { FIXED.ladder(p.entry, lotOf(p), lockQty(p), it, tick) }

    /** 16 Nov: the fourth version's lock [p] entered with (Liquidity's breakeven at +₹1,000 a lot), or null: V4 alone, or not a rule trade. */
    private fun lockOf(p: Position): com.optionslab.engine.orb.StepTrail.Lock? = if (p.fixed && p.lock) FIXED.lockFor(p.arm) else null

    /**
     * 15 Nov: the rule's words for the position popup (null: not a rule trade): its stop once the breakeven is earned, else where the
     * breakeven starts (the rule sets no stop at the entry). Joined after formatting: the label must never sit in a format pattern.
     */
    private fun fixedWords(p: Position): String? {
        if (!p.fixed || !FIXED.covers(p.arm)) return null
        val en = java.util.Locale.ENGLISH
        val label = FIXED.labelOf(lockOf(p))
        // 16 Nov: with the lock, the breakeven starts at +10% or at the premium that nets +₹1,000 a lot, the lower.
        val from = listOfNotNull(FIXED.breakevenTrigger(p.entry), ladderOf(p)?.trigger(0)).minOf { it }
        return fixedStopOf(p)?.let { label + ": stop " + "%.2f".format(en, it) }
            ?: (label + ": breakeven from " + "%.2f".format(en, from))
    }

    /** 15 Nov: the reason a sale at [fixedStopOf] is booked with: "breakeven lock" once the breakeven is earned, else the entry stop's word (unused since V4: no entry stop). */
    internal fun ruleWhy(p: Position, tick: Double = OrbRules.TICK): String =
        if (FIXED.trailLevel(p.entry, lotOf(p), p.peak, tick, ladderOf(p, tick)) != null) FIXED.WHY_TRAIL else FIXED.WHY_STOP

    /** 15 Nov: whether the price now [ltp] is at or under [p]'s stop-rule stop while its stop (resting or watched) is lower. */
    internal fun fixedBelow(p: Position, ltp: Double, tick: Double = OrbRules.TICK): Boolean =
        fixedStopOf(p, tick)?.let { fs -> fs > (p.stopTrigger ?: Double.NEGATIVE_INFINITY) + 1e-9 && FIXED.stopHit(ltp, fs) } == true

    /** 15 Nov: what [p]'s resting stop is under the stop rule - "₹500 stop" (older records), "breakeven lock" - or null (its own, or not a rule trade). */
    internal fun fixedWhy(p: Position): String? = if (!p.fixed || !FIXED.covers(p.arm)) null
        else FIXED.whyAt(p.stopTrigger, p.entry, lotOf(p), ladder = ladderOf(p), best = p.peak)

    /**
     * 15 Nov: the first stop of a Liquidity entry filled at [fill] on a contract of [lot] - its own [own] (-15%); with the rule ([fixed]) the tighter of that
     * and the rule's entry stop, which is none since V4 ([com.optionslab.engine.orb.StepTrail.stopLevel]); never looser than its own.
     */
    private fun firstStop(own: Double?, fill: Double, lot: Int, fixed: Boolean, tick: Double = OrbRules.TICK): Double? =
        if (!fixed) own else FIXED.tighterStop(own, FIXED.stopLevel(fill, lot, tick))

    /**
     * 22 Oct: was [p]'s resting stop put where it is by Boss's paper breakeven at +10% ([LiquidityBreakeven])? Its trigger is the
     * breakeven's own level (the price paid plus charges, on the tick) - a stop the trail or a lock raised elsewhere is theirs.
     * Under a lab lock variant a stop the lock's own ladder reaches too (its breakeven rung is the same level) is the profit
     * lock's, as the trail's ([trailStopped], 13 Oct review).
     */
    private fun beStopped(p: Position): Boolean {
        if (p.be == null) return false
        val at = p.stopTrigger ?: return false
        val cost = ProfitLock.roundTripPerUnit(p.entry, p.qty)
        val own = LiquidityBreakeven.level(p.entry, p.peak, cost, p.be) ?: return false
        if (kotlin.math.abs(at - own) >= 1e-6) return false
        val lock = ProfitLock.restingStop(p.entry, null, ladderTarget(p), p.peak ?: p.entry, cost)
        return lock == null || lock < at - 1e-6
    }

    /**
     * Was [p]'s resting stop put where it is by Boss's paper give-back trail ([LiquidityTrail])? Its trigger is the trail's own
     * level at the best price so far. Without the lab's lock ladder nothing else raises a Liquidity stop to the price paid or
     * higher, so there any such stop is the trail's (a level from an earlier best whose move up had not gone through yet too).
     * Under a lock variant only the trail's own level counts: a stop at the lock's level is the profit lock's.
     */
    private fun trailStopped(p: Position): Boolean {
        if (p.trail == null) return false
        val at = p.stopTrigger ?: return false
        if (at < p.entry - 1e-9) return false
        val own = LiquidityTrail.level(p.entry, p.peak, p.lot, p.trail, activateRs = p.trailFrom)
        return (own != null && kotlin.math.abs(at - own) < 1e-6) || !p.ladder
    }

    /** An exit the trade manager decided (R12, ORB Fresh on paper only), and the fill of the stop its lock raised. */
    const val MANAGER_EXIT = "trade_manager"
    const val MANAGER_LOCK = "trade_manager_lock"
    /** ORB Fresh after a trade-manager exit: no new entry today (R12 did not test re-entries; fewer trades only lower risk). */
    const val MANAGER_DONE = "done_after_trade_manager_exit"

    /**
     * The trade manager's word for [p], from memory only (its records and policy; no settings vault, no Keystore): its exit
     * reason and its lock - ONLY for ORB Fresh on PAPER with the manager in ACT ([TradeManagerHost.exitDue] and
     * [TradeManagerHost.levels] are null in SHADOW). Null for every other arm and every live position: they record only.
     */
    private fun managerWord(p: Position): Pair<String?, Double?>? {
        if (p.live || p.arm != OrbRules.ORB_FRESH.source) return null
        val id = managerId(p)
        val lock = runCatching { TradeManagerHost.levels(id)?.second }.getOrNull()
        val exit = runCatching { TradeManagerHost.exitDue(id) }.getOrNull()
        return if (exit == null && lock == null) null else exit to lock
    }

    /**
     * The trade manager's lock moves the paper resting stop UP (raise-only, R12's chandelier lock): never down, never to or
     * above the price now (a price through the lock is the manager's exit instead). A refused modify leaves the stop as it was.
     */
    private fun raiseManagerStop(p: Position, lock: Double, ltp: Double): Position {
        val id = p.stopOrderId ?: return p
        val to = ProfitLock.onTick(lock)
        if (!to.isFinite() || to <= (p.stopTrigger ?: Double.NEGATIVE_INFINITY) + 1e-9 || to >= ltp - 1e-9) return p
        val r = runCatching { Paper.modify(id, null, null, to) }
        if (r.getOrNull()?.ok != true) return p
        runCatching { Diag.record("orb", "${armOf(p.arm).label}: the trade manager's lock raised the paper stop to %.2f".format(Locale.ENGLISH, to)) }
        return p.copy(stopTrigger = to, mgrStop = to)
    }

    /** The book's text as this process last wrote it, and the file's size and time just after (null: not since a load). */
    private var writtenText: String? = null
    private var writtenStat: Pair<Long, Long>? = null

    /**
     * ORB and ORB Fresh (the pre-registered forward test), plus ORB Sweep, Range Fade and the two books of Liquidity 15+5
     * (paper only, outside that test). The liquidity books share one switch ([LiquidityRules.ARM]) and one row on screen.
     */
    private val ALL_ARMS: List<Arm> = OrbRules.ARMS + SweepRules.ARM + RangeFadeRules.ARM + LiquidityRules.BOOKS +
        // The expiry-day Hero arm (HeroRules): NIFTY, paper only, not proven. Never in IraGoldAlgo.
        (if (com.optionslab.app.BuildConfig.GOLD) emptyList<Arm>() else listOf(HeroRules.ARM))

    /** The name an arm's orders carry ([Strategies.owners]): its label, except the Hero arm's ("Hero"). */
    private fun ownerOf(arm: Arm): String = if (arm.hero) HeroRules.OWNER else arm.label

    /**
     * Why an arm may never send an order to Zerodha, or null when it may (after the owner's PIN, in Live). Every
     * paper-only arm (ORB Sweep, Range Fade, the Hero arm) is refused here, whatever the app's switch says: the live
     * entry checks it first. Unknown arms are refused too.
     */
    internal fun liveRefusal(source: String): String? {
        val arm = (ALL_ARMS + LiquidityRules.ARM).firstOrNull { it.source == source } ?: return "refused: no such arm"
        return if (arm.paperOnly) "refused: ${arm.label} is paper only; it never trades on Zerodha" else null
    }

    private fun armOf(source: String): Arm = (ALL_ARMS + LiquidityRules.ARM).first { it.source == source }

    /**
     * [arm]'s parameters on [day] for the [live] account (S1, the strategy lab): its constants, or a variant the lab promoted
     * (paper by itself, live only with Boss's PIN) from the session after it was decided - never mid-day. Memory only
     * ([VariantRunner.orbParams]: no settings read, no I/O). A position is always managed with its own day's and account's.
     */
    private fun paramsOf(arm: Arm, day: LocalDate, live: Boolean): com.optionslab.engine.orb.OrbParams = VariantRunner.orbParams(arm, day, live)
    private fun paramsOf(p: Position): com.optionslab.engine.orb.OrbParams = paramsOf(armOf(p.arm), p.day, p.live).let { d ->
        if (p.stopPts == null && p.targetPts == null) d else d.copy(stopPoints = p.stopPts ?: d.stopPoints, targetPoints = p.targetPts ?: d.targetPoints)
    }
    /** Liquidity 15+5's EXIT parameters on [day] for the [live] account (its entries are frozen in code), memory only. */
    private fun liqParams(day: LocalDate, live: Boolean): com.optionslab.engine.orb.LiquidityParams = VariantRunner.liquidityParams(day, live)
    private fun liqParams(p: Position): com.optionslab.engine.orb.LiquidityParams = p.liq ?: liqParamsOf(p.arm, p.day, p.live)

    /**
     * [book]'s exit parameters: Liquidity's BANKNIFTY books trade their constants while frozen for the paper test ([LiquidityTest],
     * Boss 12 Oct: the strategy lab's variants are not applied to them, by itself or otherwise, until he unfreezes it); every
     * other book as [liqParams]. Memory only.
     */
    private fun liqParamsOf(book: String, day: LocalDate, live: Boolean): com.optionslab.engine.orb.LiquidityParams =
        if (runCatching { LiquidityTest.frozen(book) }.getOrDefault(true) && book in com.optionslab.engine.orb.ArmPriority.BN_BOOKS)
            com.optionslab.engine.orb.LiquidityParams.DEFAULT else liqParams(day, live)

    /** [p] with the exits' parameters in force now kept on it (review R1; at the entry - see [Position.stopPts]). */
    private fun stamped(p: Position): Position = runCatching {
        val a = armOf(p.arm)
        when {
            a.liquidity -> p.copy(liq = liqParamsOf(p.arm, p.day, p.live))
            a.source in com.optionslab.ira.ArmParams.ORB_ARMS -> paramsOf(a, p.day, p.live).let { p.copy(stopPts = it.stopPoints, targetPts = it.targetPoints) }
            else -> p
        }
    }.getOrDefault(p)

    /** Liquidity's kept exit parameters as one line: "stopPct,minutes,gain,target(1/0),lock". */
    internal fun liqText(l: com.optionslab.engine.orb.LiquidityParams): String =
        "${l.stopPct},${l.timeStopMinutes},${l.timeStopGain},${if (l.nextLevelTarget) 1 else 0},${l.lockPct}"
    internal fun liqOf(s: String): com.optionslab.engine.orb.LiquidityParams? = runCatching {
        val f = s.split(',')
        com.optionslab.engine.orb.LiquidityParams(f[0].toDouble(), f[1].toLong(), f[2].toDouble(), f[3] == "1", f[4].toDouble())
    }.getOrNull()
    /** Whether a new entry of [arm] on [day] for the [live] account rests under the profit-lock ladder (Liquidity: only with a lock variant). */
    private fun ladderOn(arm: Arm, day: LocalDate, live: Boolean): Boolean =
        if (arm.liquidity) liqParamsOf(arm.source, day, live).lockPct > 0 else ProfitLock.targetOf(arm) != null
    /** "40" for a whole number of points, else "37.5". */
    private fun pts(x: Double): String = if (kotlin.math.abs(x - Math.rint(x)) < 1e-6) "%.0f".format(Locale.ENGLISH, x) else "%.1f".format(Locale.ENGLISH, x)
    /** "a 40-point", "an 80-point" (the arming words). */
    private fun pointsWith(x: Double): String = pts(x).let { n -> (if (n.startsWith("8") || n == "11" || n == "18") "an " else "a ") + "$n-point" }

    /**
     * Each ORB-family arm's parameters as its last pass decided with them (memory only; review G2-6): its status words name
     * its own range end and entry times, not the defaults. Absent until its first pass: its constants.
     */
    private val shownParams = java.util.concurrent.ConcurrentHashMap<String, com.optionslab.engine.orb.OrbParams>()

    /** New entries follow the app's Paper/Live switch. */
    fun liveNow(): Boolean = AppSettings.load().let { it.live && it.allowRealOrders }

    /**
     * Whether book [source]'s new entries are cleared for Zerodha while the app is in Live (memory only: the book [b]).
     *
     * 15 Nov (Boss: "Liquidity should be always working if i have swtich on it on dashboard, no extra pin based approval needed"):
     * a Liquidity 15+5 book is cleared when it is on AND its stored liveOk is set - which only Boss's own switch on Home (in Paper
     * or Live, [setArmed]'s bySwitch) or his PIN sets; every switch-off clears it. A book switched on any other way (Jarvis by
     * voice, the day plan, the change timeline, an unpark, a one-time change, a restore) trades on paper until Boss switches it
     * on himself on Home. Its live entry still passes every other gate: the app in Live (which takes Boss's PIN), "AI trades go
     * live" (the order door), the guard, the kill switch, the day stop and the day lock, its live stop rule and its self-review's
     * park to paper. Every other arm: its own liveOk, set only when Boss armed it in Live with his PIN, exactly as before.
     */
    private fun liveCleared(b: Book, source: String): Boolean =
        if (LiquidityRules.BOOKS.any { it.source == source }) b.armed[source] == true && b.liveOk[source] == true else b.liveOk[source] == true

    /**
     * Liquidity's "to paper" for [arm]'s new entries ([ArmAccount]'s parked): its self-review's park to paper (memory), or - 15 Nov -
     * a book not cleared for Zerodha by Boss's own switch ([liveCleared]). Memory only.
     */
    private fun liqToPaper(b: Book, arm: Arm): Boolean =
        SmartWorkers.parkedToPaperNow("liquidity") || (arm.liquidity && !liveCleared(b, arm.source))

    /** 15 Nov: each Liquidity book's Zerodha clearance as its live entry reads it ([liveCleared]). Reads the book only. */
    internal suspend fun liquidityLiveCleared(): Map<String, Boolean> = lock.withLock {
        val b = book()
        LiquidityRules.BOOKS.associate { it.source to liveCleared(b, it.source) }
    }
    /**
     * TEST ONLY: a fixed clock for the arms' own time checks (session end, the backstop, a signal's expiry, entry
     * times). Null in the app, always: [now] is then [Market.now], exactly as before. Its setter throws unless
     * BuildConfig.DEBUG (as Broker.testEndpoint), and no app code sets it; only the unit tests do.
     */
    @Volatile internal var testNow: java.time.ZonedDateTime? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }; field = v }
    private fun now(): LocalDateTime = (testNow ?: Market.now()).toLocalDateTime()
    /** Today by the same clock as [now] (the tests pin both, so a run just after midnight IST sees the same day). */
    private fun today(): java.time.LocalDate = testNow?.toLocalDate() ?: Market.today()

    // ---- views for the UI ---------------------------------------------------------

    data class ArmView(
        val arm: Arm, val armed: Boolean, val automatic: Boolean, val status: String, val open: Position?, val mark: Double?,
        val pending: Pending?, val today: List<Position>, val liveOk: Boolean = false,
        /**
         * 15 Nov: armed and cleared for Zerodha, whatever the app's mode now (the live preview, the diagnostics, Jarvis's list). The
         * same as [liveOk] for every arm but Liquidity 15+5, whose [liveOk] is "would trade live now" (Paper behaves as before).
         */
        val cleared: Boolean = liveOk,
        /**
         * ORB, ORB Fresh, ORB Sweep and Range Fade: their 2021–2026 real-data record ([RetiredArms]), shown in the day's
         * detail as information only (Boss un-retired them 07 Oct: each has its switch like any arm); null for the others.
         */
        val record: RetiredArms.Retired? = null,
        /** Liquidity 15+5 only: its paper trades since 06 Oct with and without each pre-registered candidate ([LiquidityShadow]). */
        val shadow: LiquidityShadow.Summary? = null,
        /** Liquidity 15+5 only: lots each new entry buys ([LiquidityLots]); null for the other arms. */
        val lots: Int? = null,
        /** Liquidity 15+5 only: a restore's higher size waiting for Boss to choose it; null: nothing waits. */
        val lotsAsk: Int? = null,
        /** Liquidity 15+5 only: each index's lot size, when the contract list is on the phone (underlying -> lot). */
        val lotSizes: Map<String, Int> = emptyMap(),
        /** Liquidity 15+5 only: its indices parked on Boss's OK ([ParkedArms]: FINNIFTY, 9 Oct), each switched back on from the row. */
        val parked: List<String> = emptyList(),
        /**
         * What the arm paid in charges (both accounts, rupees) today and this calendar month, and its orders this month (a buy,
         * and a sell once closed) - R14's charges line ([com.optionslab.engine.orb.ReentryCap.chargesWords]).
         */
        val chargesToday: Double = 0.0, val chargesMonth: Double = 0.0, val ordersMonth: Int = 0,
        /** Liquidity 15+5 only: Liquidity BANKNIFTY's paper test (12 Oct) - its freeze, checklist, stop rule and fill quality. */
        val test: LiquidityTestView? = null,
        /**
         * ORB, ORB Fresh, ORB Sweep and Range Fade: parked on Boss's choice (13 Oct, [FamilyPark]) and still off - the row says
         * so with a tap to switch it back on; false once Boss switches it back on.
         */
        val bossParked: Boolean = false,
        /**
         * 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only"): while the arm is off (or
         * hidden), "off - runs in shadow (would-be trades recorded, nothing bought) · shadow (switched off): 3 would-be trades,
         * ..." - its would-be trades' record ([OffShadow]); null while it is on. Liquidity's row: its books together.
         */
        val offShadow: String? = null,
        /**
         * 15 Nov, Liquidity 15+5 only: its live entries parked to paper (by its self-review, or the change timeline's Undo - the data does
         * not tell them apart), lifted only with Boss's PIN ([liftLiquidityPark]). False while that is not read yet.
         */
        val reviewParked: Boolean = false,
        /** 15 Nov, Liquidity 15+5 only: its new entries (paper and live) paused by its self-review; in Live lifted only with the PIN. */
        val reviewPaused: Boolean = false,
        /**
         * 15 Nov, Liquidity 15+5 only: its books that are on but not cleared for Zerodha ([liveCleared]: switched on by Jarvis, the day
         * plan, the change timeline or an unpark) - on paper until Boss switches Liquidity on himself on Home. Empty: none.
         */
        val paperUntilSwitch: List<String> = emptyList(),
    ) {
        /** The charges line for the row's details, or null when nothing was paid this month. */
        val chargesLine: String? get() = com.optionslab.engine.orb.ReentryCap.chargesWords(chargesToday, chargesMonth, ordersMonth)
    }

    /**
     * Liquidity BANKNIFTY's paper test on its row (Boss, 12 Oct): the [freeze] note ([frozen] now), its [paperTest] verdict
     * against its backtest ([com.optionslab.ira.Vetting]), the day-10 go-live [checklist] ([checklistHead] its count), the live
     * stop rule's lines ([stop]; [stopped]: live entries stopped now) and the fill quality ([fillsSummary], the latest [fills]);
     * [watched]: Boss's tick for the first live trade. Information only.
     */
    data class LiquidityTestView(
        val freeze: String, val frozen: Boolean, val paperTest: String,
        val checklist: List<com.optionslab.ira.LiquidityGoLive.Line>, val checklistHead: String,
        val stop: List<String>, val stopped: Boolean, val fillsSummary: String, val fills: List<String>, val watched: Boolean,
    )

    /** What the paper test's lines read from outside the arms' book (read before the lock; memory or the small files). */
    private class LiqExtra(val fills: List<com.optionslab.ira.FillQuality.Rec>, val flaws: Pair<Int, Int>?, val ip: Pair<Boolean?, String>,
                           val state: com.optionslab.ira.LiquidityFreeze.State, val note: String, val frozen: Boolean)

    private fun liqExtra(): LiqExtra = LiqExtra(
        runCatching { LiquidityTest.fills() }.getOrDefault(emptyList()),
        runCatching { OrderReviewHost.liquidityCounts(com.optionslab.ira.Vetting.START) }.getOrNull(),
        runCatching { StaticIp.checkHint() }.getOrDefault(null to "not checked yet"),
        LiquidityTest.state.value, runCatching { LiquidityTest.note() }.getOrDefault(""), runCatching { LiquidityTest.frozenNow() }.getOrDefault(true))

    /** Liquidity BANKNIFTY's paper test from [b] (under the lock) and [x]. */
    private fun liquidityTestView(b: Book, day: LocalDate, x: LiqExtra): LiquidityTestView {
        val bn = com.optionslab.engine.orb.ArmPriority.BN_BOOKS
        val start = com.optionslab.ira.Vetting.START
        val paper = b.positions.filter { it.arm in bn && !it.live && !it.unconfirmed && !it.day.isBefore(start) }
        // Rupees per lot (the backtest's unit): Liquidity trades 1 lot; a bigger size is divided back. The plain-rules record
        // only: trades under Boss's paper give-back trail (R19b) are kept apart, so the 26 Oct decision reads the rules alone.
        val closed = paper.filter { !it.open && it.exitTime != null }
        val perLot = { p: Position -> ((p.grossPnl ?: 0.0) - p.charges) / p.lots.coerceAtLeast(1.0) }
        val trades = closed.filter { !it.ownExits }.map { p -> com.optionslab.ira.Vetting.Trade(p.exitTime!!.toLocalDate(), perLot(p)) }
        val trailed = closed.filter { it.ownExits }
        val entries = paper.map { p ->
            com.optionslab.ira.LiquidityGoLive.PaperEntry(p.arm, p.signalBar, if (p.right == "CE") 1 else -1,
                runCatching { Paper.contractOf(p.symbol)?.strike?.toInt() }.getOrNull(), p.entryTime, p.exitTime, LiquidityRules.minutesOf(armOf(p.arm)))
        }
        val lags = entries.map { e -> java.time.Duration.between(e.signalBar.plusMinutes(e.minutes.toLong()), e.entryTime).toMillis() / 1000.0 }
        // 20 Oct, enhancement round 1: the no-twin rule's would-be trades are the rule at work, never missed signals (memory only).
        val fid = com.optionslab.ira.LiquidityGoLive.fidelity(entries, liquidityReplays(b).filterKeys { !it.isBefore(start) },
            runCatching { com.optionslab.ira.LiquidityTwin.excused(LiquidityTest.twinsInMemory()) }.getOrDefault(emptySet()))
        val stats = com.optionslab.ira.FillQuality.stats(x.fills)
        val lines = com.optionslab.ira.LiquidityGoLive.lines(com.optionslab.ira.LiquidityGoLive.Inputs(trades, day, fid, lags, stats,
            x.flaws?.first, x.flaws?.second, x.ip.first, x.ip.second, x.state.watched))
        val liveArmed = LiquidityRules.BOOKS.any { it.source in bn && b.armed[it.source] == true && liveCleared(b, it.source) }
        val stop = listOfNotNull(
            if (liveArmed || liquidityStopHint.trades > 0) com.optionslab.ira.LiquidityStopRule.line(liquidityStopHint, live = true) else null,
            com.optionslab.ira.LiquidityStopRule.line(liquidityStopPaperHint, live = false))
        val recent = x.fills.filter { it.bankNifty }.takeLast(8).reversed().map { com.optionslab.ira.FillQuality.line(it) }
        // 22 Oct: Boss's breakeven at +10% too; the trail-only words as before.
        // 15 Nov: the breakeven rule's trades named as such (never "plain rules"); a mix says each label.
        val ownLabel = trailed.mapNotNull { it.ownExitsLabel }.distinct().let { l -> l.singleOrNull() ?: if (l.isEmpty()) "on your own exit rules" else "on your own exit rules (${l.joinToString("; ")})" }
        val ownNote = when {
            ownLabel == LiquidityTrail.TRAILED -> LiquidityTrail.NOTE
            // 16 Nov: the fourth version's trades (breakeven at +₹1,000 too) with R186's record.
            trailed.all { it.fixed && !it.ownRules } -> fixedNote(trailed)
            trailed.none { it.fixed } -> LiquidityBreakeven.NOTE
            else -> "your own choices, kept apart from the plain rules"
        }
        val trailWords = if (trailed.isEmpty()) "" else " Kept apart, $ownLabel ($ownNote): ${trailed.size} trade" +
            "${if (trailed.size == 1) "" else "s"}, ${"%+,.0f".format(java.util.Locale.ENGLISH, trailed.sumOf { perLot(it) })} rupees a lot."
        return LiquidityTestView(x.note, x.frozen, com.optionslab.ira.Vetting.judge(com.optionslab.ira.LiquiditySplit.name("BANKNIFTY"), trades, day).text() + trailWords,
            lines, com.optionslab.ira.LiquidityGoLive.head(lines), stop, liquidityStopHint.stopped, com.optionslab.ira.FillQuality.summary(x.fills), recent,
            x.state.watched)
    }

    /**
     * 15 Nov: Boss lifts Liquidity's self-review park to paper (its row; the park is an automatic risk-lowering step, so lifting it
     * takes his PIN [pinConfirmed], as [reenableLiquidityLive]). Off the order path (Boss's own action).
     */
    fun liftLiquidityPark(pinConfirmed: Boolean): String {
        if (!pinConfirmed) return "Lifting Liquidity's park to paper or pause takes your PIN."
        runCatching { SmartWorkers.ensureLoaded() }
        if (!SmartWorkers.reviewParked("liquidity") && !SmartWorkers.reviewPaused("liquidity")) return "Liquidity is not parked to paper or paused by its review."
        liftReview(LiftReview.PAUSE_AND_PARK)
        runCatching { Diag.record("orb", "Liquidity 15+5: its review's park to paper and pause lifted by Boss with his PIN") }
        return "Liquidity's park to paper and pause are lifted: its books cleared by your switch trade on Zerodha again while the app is in Live."
    }

    /** Boss's tick on the checklist: he watched the first live trade with the app open (or took it back). */
    fun setFirstLiveWatched(watched: Boolean) { LiquidityTest.setWatched(watched) }

    /** [arm]'s charges today and this month and its orders this month, from [positions] (the book keeps the last 2000). */
    internal fun chargesOf(positions: List<Position>, arm: String, day: LocalDate): Triple<Double, Double, Int> {
        val month = positions.filter { it.arm == arm && it.day.year == day.year && it.day.month == day.month }
        return Triple(month.filter { it.day == day }.sumOf { it.charges }, month.sumOf { it.charges },
            month.size + month.count { !it.open } + month.count { it.sold > 0 })
    }

    data class View(
        val arms: List<ArmView>, val legs: Legs?, val range: Pair<Double, Double>?, val forward: PassRule.Verdict,
        val replay: JSONObject?, val replayDay: String?,
        /** The day's stop, when the bot is stopped for today: who stopped it and what resumes it ([DayStop.line]); null when it runs. */
        val stopped: String? = null,
        /** The research shadows of ORB, ORB Fresh, ORB Sweep and Range Fade and the new candidate ([ShadowArms]: no orders), one row a variant. */
        val shadows: List<ShadowArms.Row> = emptyList(),
    )

    private val marks = java.util.concurrent.ConcurrentHashMap<String, Double>()
    /** When each [marks] price was last read from a quote (a fill's seed has none): a decision needs a fresh one. */
    private val markedAt = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()

    private fun mark(sym: String, px: Double, t: LocalDateTime) { marks[sym] = px; markedAt[sym] = t }

    /**
     * [fresh] (19 Nov 2026, audit batch 2, P1): the book itself, waiting for the lock as before - for a caller that acts on what the arms
     * hold (the stop rescue); otherwise, while the lock is held, the copy as last saved.
     */
    suspend fun view(fresh: Boolean = false): View {
        // (Read apart from the arms' lock: the bot's stop is the strategies' own.)
        val why = runCatching { Strategies.stoppedWhy() }.getOrNull()
        // (The shadows' rows too: they never wait on a pass.)
        val shadows = runCatching { ShadowArms.rows() }.getOrDefault(emptyList())
        // Liquidity BANKNIFTY's paper test reads its fill records and the order review before the lock (never under it).
        val extra = if (com.optionslab.app.BuildConfig.GOLD) null else runCatching { liqExtra() }.getOrNull()
        // 30 Oct: each switched-off arm's shadow line (memory only: the shadow's own view, never its lock or file).
        // 15 Nov: the app's Live switch for Liquidity's row ("would trade live now"), read before the lock (never under it).
        val appLive = runCatching { liveNow() }.getOrDefault(false)
        return (if (fresh) lock.withLock {
            // (The book not readable for a moment: the copy as last read, when there is one - never "nothing held".)
            try { viewOf(book(), extra, appLive) } catch (e: java.io.IOException) {
                viewBook?.takeIf { it.gen == wipeGen }?.let { viewOf(it, extra, appLive) } ?: throw e
            }
        } else viewLocked(extra, appLive)).let { v -> v.copy(stopped = why?.let { DayStop.line(it) }, shadows = shadows,
            arms = v.arms.map { a -> offLine(a)?.let { a.copy(offShadow = it) } ?: a }) }
    }

    private suspend fun viewLocked(extra: LiqExtra? = null, appLive: Boolean = false): View {
        // 19 Nov 2026 (audit batch 2): never waits on the arms' lock - some 20 readers (the stop rescue's watch, the screens, Jarvis) called
        // this and each waited behind a pass that could hold the lock for minutes. Free: the book itself, as before; held: the copy taken
        // when it was last read or saved ([viewBook]). Waits only when there is none yet (no book read in this process, or just erased).
        // (The book not readable for a moment - [book] throws: the copy as last read, when there is one.)
        if (lock.tryLock()) {
            try { return viewOf(book(), extra, appLive) } catch (e: java.io.IOException) {
                viewBook?.takeIf { it.gen == wipeGen }?.let { return viewOf(it, extra, appLive) }; throw e
            } finally { lock.unlock() }
        }
        viewBook?.takeIf { it.gen == wipeGen && cache != null }?.let { return viewOf(it, extra, appLive) }
        return lock.withLock { viewOf(book(), extra, appLive) }
    }

    /**
     * 19 Nov 2026 (audit batch 2): a copy of the book as last read or saved, taken under the lock at each [hints] - what [view] shows while
     * the lock is held, and what the pass picks its reads before the lock from ([passReads]). Never changed or saved; memory only.
     */
    @Volatile private var viewBook: Book? = null

    /** 19 Nov 2026 (audit batch 2): [b] copied (its maps, sets and rows; the rows themselves never change), for [viewBook]. */
    private fun snapshotOf(b: Book): Book = b.copy(
        armed = HashMap(b.armed), auto = HashMap(b.auto), liveOk = HashMap(b.liveOk),
        decided = b.decided.mapValuesTo(HashMap<String, MutableSet<String>>()) { HashSet(it.value) }, watched = HashMap(b.watched),
        positions = ArrayList(b.positions), pending = HashMap(b.pending), status = HashMap(b.status), replays = LinkedHashMap(b.replays),
        upDays = HashMap(b.upDays), since = HashMap(b.since), migrated = HashSet(b.migrated), parked = HashSet(b.parked),
        famParked = HashSet(b.famParked), liqReplays = LinkedHashMap(b.liqReplays)).also { it.gen = b.gen }

    /** 19 Nov 2026 (audit batch 2): the book as last read or saved, without waiting on the lock ([viewBook]); none yet: read under it. */
    private suspend fun bookNow(): Book = viewBook?.takeIf { it.gen == wipeGen && cache != null } ?: lock.withLock { snapshotOf(book()) }

    private fun viewOf(b: Book, extra: LiqExtra?, appLive: Boolean): View {
        val day = today()
        val arms = ALL_ARMS.filter { it !in LiquidityRules.BOOKS }.map { a ->
            val open = b.positions.lastOrNull { it.arm == a.source && it.open }
            val (cToday, cMonth, orders) = chargesOf(b.positions, a.source, day)
            ArmView(a, b.armed[a.source] == true, b.auto[a.source] != false, b.status[a.source] ?: "", open, open?.let { marks[it.symbol] },
                b.pending[a.source], b.positions.filter { it.arm == a.source && it.day == day }, b.liveOk[a.source] == true,
                record = RetiredArms.of(a.source), chargesToday = cToday, chargesMonth = cMonth, ordersMonth = orders,
                bossParked = a.source in b.famParked && b.armed[a.source] != true)
        } + liquidityView(b, day, extra, appLive)
        val lastReplay = b.replays.entries.lastOrNull()
        return View(arms, b.legs?.takeIf { it.day == day }, b.range?.takeIf { b.rangeDay == day }, forward(b), lastReplay?.value, lastReplay?.key)
    }

    /** Every closed paper trade the arms' book keeps (its last 2000 positions), oldest first. Reads only. */
    suspend fun closedPaper(): List<Position> = lock.withLock { book().positions.filter { !it.open && !it.live } }

    /** The arms' closed Zerodha positions (the self-review sets them against the backtest apart from paper; reads only). */
    suspend fun closedLive(): List<Position> = lock.withLock { book().positions.filter { !it.open && it.live } }

    /**
     * 20 Nov 2026 (the "Share full settings and state" file, [SettingsShare]): [closedLive] from the copy as last read or saved
     * ([viewBook]), never waiting on the arms' lock; null while no copy is in memory. Memory only.
     */
    internal fun closedLiveKept(): List<Position>? = viewBook?.takeIf { it.gen == wipeGen }?.positions?.filter { !it.open && it.live }

    /**
     * 20 Nov 2026 (the same file): each Liquidity 15+5 book on its own line - on or off, cleared for Zerodha by Boss's own switch or
     * not, its index parked, its status - from the copy as last read or saved ([viewBook]); null while none is in memory. Memory only.
     */
    internal fun liquidityBookLines(): List<String>? = viewBook?.takeIf { it.gen == wipeGen }?.let { b ->
        LiquidityRules.BOOKS.map { a ->
            val u = LiquidityRules.underlyingOf(a)
            "${a.label} ($u ${LiquidityRules.minutesOf(a)}-min): " + (if (b.armed[a.source] == true) "on" else "off") +
                " · cleared for Zerodha by your switch: " + (if (liveCleared(b, a.source)) "yes" else "no (paper until you switch it on on Home)") +
                (if (u in b.parked) " · its index is parked" else "") + (if (b.auto[a.source] == false) " · approve each entry" else " · automatic") +
                " · status: " + describe(b.status[a.source] ?: "").ifEmpty { "-" }
        }
    }

    /** Today's closed positions, paper and live (the findings bus: a stop or a target hit). Reads only. */
    suspend fun closedToday(): List<Position> {
        val d = now().toLocalDate()
        return lock.withLock { book().positions.filter { !it.open && it.exitTime?.toLocalDate() == d } }
    }

    /** Liquidity 15+5 as one row: armed when its books are, both books' trades, each book's state. */
    private fun liquidityView(b: Book, day: LocalDate, extra: LiqExtra? = null, appLive: Boolean = false): ArmView {
        val books = LiquidityRules.BOOKS.map { it.source }
        val open = b.positions.lastOrNull { it.arm in books && it.open }
        val status = LiquidityRules.BOOKS.joinToString("  ") { a ->
            "${LiquidityRules.underlyingOf(a)} ${LiquidityRules.minutesOf(a)}-min: ${describe(b.status[a.source] ?: "")}" }
        return ArmView(LiquidityRules.ARM, books.any { b.armed[it] == true }, books.all { b.auto[it] != false }, status, open,
            open?.let { marks[it.symbol] }, books.firstNotNullOfOrNull { b.pending[it] }, b.positions.filter { it.arm in books && it.day == day },
            // 15 Nov: "would trade live now" - the app in Live, not parked to paper by its review, and a book cleared by Boss's own
            // switch ([liveCleared]); in Paper it is false, as before. (A parked index's books are off: they never count.)
            appLive && !SmartWorkers.parkedToPaperNow("liquidity") && books.any { liveCleared(b, it) },
            cleared = books.any { liveCleared(b, it) },
            shadow = shadowOf(b), lots = lotsOf(b), lotsAsk = b.liqLotsAsk,
            lotSizes = lotSizes(), parked = b.parked.sorted(),
            reviewParked = runCatching { SmartWorkers.reviewParked("liquidity") }.getOrDefault(false),
            reviewPaused = runCatching { SmartWorkers.reviewPaused("liquidity") }.getOrDefault(false),
            paperUntilSwitch = LiquidityRules.BOOKS.filter { b.armed[it.source] == true && !liveCleared(b, it.source) }
                .map { "${LiquidityRules.underlyingOf(it)} ${LiquidityRules.minutesOf(it)}-min" }).let { v ->
                // Its books' charges together (R14's charges line).
                val c = books.map { chargesOf(b.positions, it, day) }
                v.copy(chargesToday = c.sumOf { it.first }, chargesMonth = c.sumOf { it.second }, ordersMonth = c.sumOf { it.third },
                    test = extra?.let { x -> runCatching { liquidityTestView(b, day, x) }.getOrNull() })
            }
    }

    /** Each Liquidity index's lot size from the contract list already on the phone (never a download); empty when none is. */
    private fun lotSizes(): Map<String, Int> = runCatching {
        val listed = Market.cachedContracts()?.second.orEmpty()
        LiquidityRules.UNDERLYINGS.mapNotNull { u -> listed.firstOrNull { it.underlying == u && it.lotSize > 0 }?.let { u to it.lotSize } }.toMap()
    }.getOrDefault(emptyMap())

    /**
     * Liquidity 15+5's size: [lots] (1, 2 or 3) for every NEW entry of its six books; an open position keeps its own
     * quantity. Raising it is Boss's alone: the row asks before it, and Jarvis only on his confirmed yes ([by] says who; the
     * arm log keeps it). A restore's waiting size is answered by this too. Paper takes it as it is; Live still goes through
     * the account guard, and a size the Bot settings do not allow is said now and refused at the entry, never traded smaller.
     * 19 Nov 2026 (audit batch 2: Liquidity's lots): a RAISE takes [pinConfirmed] (the row asks the PIN first) wherever the guard's
     * own rule asks it ([com.optionslab.app.security.GuardPin]: a PIN set, and Live or Zerodha set up) - Liquidity's books trade
     * live by Boss's switch alone, so more lots is more live risk. Lowering never asks; Jarvis never raises it in Live.
     */
    suspend fun setLiquidityLots(lots: Int, by: String, pinConfirmed: Boolean = false): String {
      // 19 Nov 2026 (audit batch 2, second review): the settings read before the book's lock, never under it.
      val asks = !pinConfirmed && liquidityLotsPinAsked()
      return lock.withLock {
        if (!LiquidityLots.valid(lots)) return@withLock "Liquidity 15+5 trades 1, 2 or 3 lots, not $lots."
        val b = book()
        val old = lotsOf(b)
        if (LiquidityLots.raises(old, lots) && asks) return@withLock LIQ_LOTS_PIN
        val waited = b.liqLotsAsk
        b.liqLots = lots; b.liqLotsAsk = null; b.liqLotsChosen = true
        if (old == lots && waited == null) return@withLock "Liquidity 15+5 already trades ${LiquidityLots.words(lots)}."
        save(b)
        runCatching { Diag.record("orb", "Liquidity 15+5: size ${LiquidityLots.words(old)} -> ${LiquidityLots.words(lots)} ($by)" +
            (waited?.let { "; the restored ${LiquidityLots.words(it)} no longer waits" } ?: "")) }
        val holding = b.positions.any { it.open && it.arm in LiquidityRules.BOOKS.map { a -> a.source } }
        val max = runCatching { AppSettings.load().guardMaxLots }.getOrDefault(0)
        "Liquidity 15+5 now buys ${LiquidityLots.words(lots)} of each contract's lot on every new entry" +
            (if (holding) "; the open position keeps its own quantity." else ".") +
            (LiquidityLots.liveRefusal(lots, max)?.let { " On Zerodha, Bot settings allow ${LiquidityLots.words(max)}: a live entry would be refused until you raise that." } ?: "")
      }
    }

    /** 19 Nov 2026 (audit batch 2): what is said when Liquidity's lots are raised without the PIN. */
    const val LIQ_LOTS_PIN = "Raising Liquidity 15+5's lots adds live risk: enter your app PIN on its row on Home. Nothing changed."

    /** 19 Nov 2026 (audit batch 2): whether raising Liquidity's lots asks the PIN now (the guard's own rule; unknown counts as yes). */
    internal fun liquidityLotsPinAsked(): Boolean = runCatching {
        com.optionslab.app.security.GuardPin.asks(true, runCatching { AppSettings.load().live }.getOrDefault(true))
    }.getOrDefault(true)

    /**
     * Liquidity 15+5's closed paper trades as [LiquidityShadow] counts them (from 06 Oct, each candidate's flag). Candidates (d)
     * and (e) are priced by the shadow tracker ([ShadowArms.liquidityHint], read without its lock); a trade it never saw is
     * not counted for them.
     */
    private fun shadowOf(b: Book): LiquidityShadow.Summary {
        val books = LiquidityRules.BOOKS.map { it.source }.toSet()
        val alts = runCatching { ShadowArms.liquidityHint }.getOrDefault(emptyMap())
        return LiquidityShadow.summarize(b.positions.filter { it.arm in books && !it.open && !it.live }.map {
            val net = (it.grossPnl ?: 0.0) - it.charges
            val alt = alts[ShadowArms.liqKey(it.arm, it.entryTime, it.symbol)]
            val exitTime = it.exitTime
            // Per lot ([LiquidityShadow.Trade.lots]): a 2- or 3-lot trade's rupees, and its candidates', count as one lot's.
            LiquidityShadow.Trade(it.day, net, it.near, it.arm, it.volSkip,
                exit1430 = if (alt == null || exitTime == null) null else LiquidityShadow.exitAllNet(net, exitTime, it.entry, it.qty, alt.at1430),
                itm2 = alt?.itm2, strong = it.strong, lots = it.lots)
        })
    }

    /** Liquidity 15+5's paper trades since 06 Oct, with and without each candidate rule (Jarvis's "which candidate helped"). Reads only. */
    suspend fun liquidityShadow(): LiquidityShadow.Summary {
        // (The shadow tracker's record loaded first, for candidates (d) and (e): it never waits on a pass.)
        runCatching { ShadowArms.rows() }
        return lock.withLock { shadowOf(book()) }
    }

    /**
     * Liquidity 15+5's paper positions entered on [day], open and closed: the shadow tracker prices candidates (d) and (e)
     * beside them ([ShadowArms]). Reads only.
     */
    suspend fun liquidityToday(day: LocalDate): List<Position> = lock.withLock {
        val books = LiquidityRules.BOOKS.map { it.source }.toSet()
        book().positions.filter { it.arm in books && !it.live && it.day == day }
    }

    /**
     * Liquidity 15+5's positions entered on [day], paper and live (Zerodha), open and closed - for Jarvis's words only
     * (the trade lessons). The lock is held only to copy the list. Reads only.
     */
    suspend fun liquidityTodayWithLive(day: LocalDate): List<Position> = lock.withLock {
        val books = LiquidityRules.BOOKS.map { it.source }.toSet()
        book().positions.filter { it.arm in books && it.day == day }
    }

    /**
     * Liquidity 15+5's open position of book [arm] in [symbol] for its live panel ([com.optionslab.ira.LiquidityOpen]): the position (the same one
     * [ArmView.open] shows), its last mark, and the last index price the arm's own pass read for its index with that bar's
     * minute (null: none read). Copies under the lock, nothing fetched or built; reads only.
     */
    data class LiquidityOpenNow(val position: Position, val mark: Double?, val index: Double?, val indexAt: LocalDateTime?)

    suspend fun liquidityOpenNow(arm: String, symbol: String): LiquidityOpenNow? = lock.withLock {
        val books = LiquidityRules.BOOKS.map { it.source }
        // That book's own open position (BANKNIFTY's, FINNIFTY's and MIDCPNIFTY's can be open at once): each panel reads its own index.
        val p = book().positions.lastOrNull { it.arm in books && it.arm == arm && it.symbol == symbol && it.open } ?: return@withLock null
        val und = LiquidityRules.BOOKS.firstOrNull { it.source == p.arm }?.let { LiquidityRules.underlyingOf(it) }
        val last = und?.let { liquidityPass[it]?.second?.lastOrNull() }
        LiquidityOpenNow(p, marks[p.symbol], last?.close, last?.start)
    }

    /** The pre-registered forward test on the closed arm trades, operator-closed trades excluded. */
    private fun forward(b: Book): PassRule.Verdict = PassRule.judge(
        // The paper-only arms' trades (ORB Sweep, Range Fade) and Liquidity 15+5's are not part of the ORB's pre-registered forward test.
        // The profit lock changed the ORB's exits (2026-10-01), so the test restarted: only trades entered under it count.
        b.positions.filter { !it.open && it.ladder && it.why != "operator_stop" && it.why != "closed_by_you" && ALL_ARMS.none { a -> a.source == it.arm && (a.paperOnly || a.liquidity) } }.map { p ->
            PassRule.Closed(p.day, (p.grossPnl ?: 0.0) - p.charges, b.upDays[p.day.toString()])
        })

    suspend fun holding(): Boolean = lock.withLock { book().positions.any { it.open } }

    /** Any arm (the liquidity books too) switched on - for the words lane's pace only, never a decision on an order. */
    suspend fun anyArmed(): Boolean = lock.withLock { book().armed.values.any { it } }

    /** Liquidity 15+5 armed (either book): the in-memory book only - no marks, shadows or rows built (the heads-up's read each round). */
    suspend fun liquidityArmed(): Boolean = lock.withLock { book().armed.let { a -> LiquidityRules.BOOKS.any { a[it.source] == true } } }

    /**
     * Liquidity 15+5's books on [day] for Jarvis's "why no liquidity trade today" ([com.optionslab.ira.LiquidityWhyNot]): each
     * book's switch, last verdict and how many bars it decided on, and its positions entered that day (paper and Zerodha).
     * A copy of the in-memory book (no marks, shadows or rows built, nothing fetched); reads only.
     */
    suspend fun liquidityDay(day: LocalDate): Pair<List<com.optionslab.ira.LiquidityWhyNot.BookState>, List<Position>> = lock.withLock {
        val b = book()
        val books = LiquidityRules.BOOKS.map { it.source }.toSet()
        LiquidityRules.BOOKS.map { a ->
            com.optionslab.ira.LiquidityWhyNot.BookState(a.source, b.armed[a.source] == true, b.status[a.source] ?: "", b.decided["${a.source}|$day"]?.size ?: 0)
        } to b.positions.filter { it.arm in books && it.day == day }
    }

    // ---- Liquidity 15+5's day in words (Jarvis's "why no liquidity trade today") ----------------

    /**
     * Today's Liquidity 15+5 decisions, oldest first: each decision bar's verdict (a break's side, level, target and close
     * with it) and each change of a book's verdict between bars. In memory only, guarded by itself - never the arms' lock -
     * and never read by a decision, an exit or an order: words for Jarvis. A new day's first record drops the earlier days'.
     */
    private val liqDecisions = ArrayList<com.optionslab.ira.LiquidityWhyNot.Decision>()
    private const val LIQ_DECISIONS_MAX = 600

    /** When this run's record began (the app's start): bars decided before it are counted by the book, not described. */
    val liquidityRecordSince: LocalDateTime = runCatching { Market.now().toLocalDateTime() }.getOrElse { LocalDateTime.now() }

    /** Keeps one Liquidity decision (see [liqDecisions]); an unchanged verdict between bars is kept once. Never throws into a pass. */
    private fun noteLiquidity(at: LocalDateTime, book: String, verdict: String, bar: LocalDateTime? = null, s: LiquidityRules.Signal? = null,
                              close: Double? = null) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        // An error's own message is never kept (it may carry an address): its kind only.
        val v = if (verdict.startsWith("error")) "error" else verdict
        synchronized(liqDecisions) {
            liqDecisions.removeAll { it.at.toLocalDate() != at.toLocalDate() }
            if (bar == null && liqDecisions.lastOrNull { it.book == book }?.verdict == v) return
            liqDecisions += com.optionslab.ira.LiquidityWhyNot.Decision(at, book, v, bar, s?.side, s?.level, s?.target, close)
            while (liqDecisions.size > LIQ_DECISIONS_MAX) liqDecisions.removeAt(0)
        }
    }

    /** Liquidity 15+5's decisions kept in memory for [day], oldest first: a copy, never waiting on the arms' lock. Reads only. */
    fun liquidityDecisions(day: LocalDate): List<com.optionslab.ira.LiquidityWhyNot.Decision> =
        synchronized(liqDecisions) { liqDecisions.filter { it.at.toLocalDate() == day } }

    // ---- the Hero arm's day in words (Jarvis's "what did hero do today") -------------------------

    /**
     * Today's Hero decisions, oldest first: each minute it decided with what it read (NIFTY, the ATM straddle, its legs and
     * low, the 15-minute move; a signal's pick, limit and lots) and each change of its verdict between minutes. In memory
     * only, guarded by itself - never the arms' lock - and never read by a decision, an exit or an order: words for Jarvis.
     * A new day's first record drops the earlier days'.
     */
    private val heroDecided = ArrayList<com.optionslab.ira.HeroDay.Decision>()

    /**
     * Keeps one Hero verdict (see [heroDecided]): at [minute] with the [scan] and the minutes it was made from, else (null) a
     * verdict between its minutes, kept once while unchanged; a minute's unchanged verdict (a stale minute looked at again)
     * is kept once too. Never throws into a pass.
     */
    private fun noteHero(at: LocalDateTime, verdict: String, minute: LocalTime? = null, scan: HeroRules.Scan? = null,
                         spot: Map<LocalTime, Double>? = null, straddle: Map<LocalTime, Double>? = null,
                         legs: Map<Pair<Double, Right>, Map<LocalTime, HeroRules.Leg>>? = null,
                         pick: HeroRules.Candidate? = null, limit: Double? = null, lots: Int? = null) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        // An error's own message is never kept (it may carry an address): its kind only.
        val v = if (verdict.startsWith("error")) "error" else verdict.take(200)
        val x = minute?.let { spot?.get(it) }
        val k = x?.let { HeroRules.atm(it) }
        fun leg(r: Right): Double? = if (k == null || minute == null) null else legs?.get(k.toDouble() to r)?.let { HeroRules.legPrice(it, minute) }
        val d = com.optionslab.ira.HeroDay.Decision(at, v, minute, scan?.spot ?: x, k, leg(Right.CE), leg(Right.PE),
            minute?.let { straddle?.get(it) }, minute?.let { m -> straddle?.filterKeys { !it.isAfter(m) }?.values?.minOrNull() },
            scan?.stExp, scan?.mom, scan?.signal ?: 0, pick?.strike, pick?.ltp, limit, lots)
        synchronized(heroDecided) {
            heroDecided.removeAll { it.at.toLocalDate() != at.toLocalDate() }
            val last = heroDecided.lastOrNull()
            if (last?.verdict == v && (minute == null || last?.minute == minute)) return
            heroDecided += d
            while (heroDecided.size > com.optionslab.ira.HeroDay.MAX_DECISIONS) heroDecided.removeAt(0)
        }
    }

    /** The Hero arm's decisions kept in memory for [day], oldest first: a copy, never waiting on the arms' lock. Reads only. */
    fun heroDecisions(day: LocalDate): List<com.optionslab.ira.HeroDay.Decision> =
        synchronized(heroDecided) { heroDecided.filter { it.at.toLocalDate() == day } }

    // ---- arming and approvals ------------------------------------------------------

    /**
     * [pinConfirmed]: the UI took the PIN or fingerprint (required to arm while the app is in Live). Every arm - ORB, ORB
     * Fresh, ORB Sweep and Range Fade too, since Boss un-retired them on 07 Oct - is armed and disarmed the same way.
     * [bySwitch]: Boss's own switch on Home's row ([com.optionslab.app.ui.AppModel.armOrb]) - 15 Nov: enough to switch Liquidity
     * 15+5 on in Live without a PIN of its own ([liveCleared]); every other arm ignores it.
     */
    suspend fun setArmed(source: String, on: Boolean, automatic: Boolean, pinConfirmed: Boolean = false, bySwitch: Boolean = false): String {
        // 15 Nov (review): what Liquidity's switch-on lifts of its self-review, done after the arms' lock (it may read the settings vault).
        var lift = LiftReview.NONE
        val said = setArmedLocked(source, on, automatic, pinConfirmed, bySwitch) { lift = it }
        if (lift != LiftReview.NONE) liftReview(lift)
        return said
    }

    /** 15 Nov: what Boss's own action lifts of Liquidity's self-review: nothing, its pause (Paper, his switch), or pause and park (his PIN). */
    private enum class LiftReview { NONE, PAUSE, PAUSE_AND_PARK }

    /**
     * Lifts Liquidity's self-review [what] (Boss's own action; off the arms' lock), and closes in the change timeline the Autopilot
     * entries that set what was lifted, so they no longer read as kept ([com.optionslab.app.ira.Autopilot.ownerLifted]).
     */
    private fun liftReview(what: LiftReview) {
        runCatching {
            SmartWorkers.ensureLoaded()
            val s = SmartWorkers.settings.value
            val paused = "liquidity" in s.paused
            val parked = what == LiftReview.PAUSE_AND_PARK && "liquidity" in s.parked
            if (what == LiftReview.PAUSE_AND_PARK) SmartWorkers.ownerRearmed("liquidity") else SmartWorkers.ownerResumedPaper("liquidity")
            if (paused || parked) runCatching { com.optionslab.app.ira.Autopilot.ownerLifted("liquidity", paused = paused, parked = parked) }
        }
    }

    private suspend fun setArmedLocked(source: String, on: Boolean, automatic: Boolean, pinConfirmed: Boolean, bySwitch: Boolean,
                                       lifts: (LiftReview) -> Unit): String = lock.withLock {
        val b = book()
        if (source == LiquidityRules.ARM.source) {
            // Both books follow the one switch: the Paper / Live switch, automatic or approve.
            val live = liveNow()
            // 15 Nov (Boss: "no extra pin based approval needed"): in Live, Boss's own switch on Home switches it on without a PIN of its
            // own (going Live already took it). Anything else - Jarvis by voice, the day plan, an automation - still cannot switch it on
            // in Live (automatic changes may only lower live risk); on paper it is as before.
            if (on && live && !pinConfirmed && !bySwitch) return@withLock LIQUIDITY_SWITCH_ONLY
            // A parked index's books stay off when the switch comes on (9 Oct, Boss's OK): only "switch it back on" brings them back.
            for (a in if (on) ParkedArms.armable(b.parked) else LiquidityRules.BOOKS) {
                // 15 Nov: cleared for Zerodha ([liveCleared]) only by Boss's own switch on Home or his PIN - in Paper or Live; any other
                // way of switching it on leaves it on paper. Every switch-off clears it.
                b.armed[a.source] = on; b.auto[a.source] = automatic; b.liveOk[a.source] = on && (bySwitch || pinConfirmed)
                if (!on) b.pending.remove(a.source)
            }
            // Its self-review's PAUSE (new entries, paper and live) and park to paper are automatic risk-lowering steps: Boss's own
            // switch lifts the pause only in Paper ("switch it back on yourself"); in Live only his PIN lifts either ([liftLiquidityPark]).
            // Done by [setArmed] after this lock.
            if (on && pinConfirmed) lifts(LiftReview.PAUSE_AND_PARK) else if (on && bySwitch && !live) lifts(LiftReview.PAUSE)
            save(b)
            // 25 Oct (app audit): every flip of an arm's switch is written down with when (the diary) - Liquidity's BANKNIFTY book included.
            switchNote(LiquidityRules.ARM.label, on, live, automatic, pin = pinConfirmed)
            val holding = b.positions.any { it.open && it.arm in LiquidityRules.BOOKS.map { a -> a.source } }
            return@withLock if (on) "${LiquidityRules.ARM.label} armed" + (if (live) " on ZERODHA (live), " else " on paper, ") +
                (if (automatic) "fully automatic" else "you approve each entry") + ": on the BANKNIFTY 15- and 5-minute, FINNIFTY 30- and 5-minute " +
                "and MIDCPNIFTY 15- and 5-minute charts, " +
                // 22 Oct (arms audit): the words say the rule as it runs (one strike in the money, the index stop, the time stop).
                "when a close takes a liquidity pool that sits on a swing zone, it buys the call (up) or put (down) one strike in the money, ${LiquidityLots.words(lotsOf(b))}, with a stop " +
                // (The stop of the variant in force today for that account, review G2-6: 15% on its constants.)
                "${pts(runCatching { liqParams(today(), live).stopPct }.getOrDefault(LiquidityRules.PREMIUM_STOP) * 100)}% below the price paid, " +
                "an index stop back through the broken level, a 20-minute time stop unless it is 5% up, and sells at the next liquidity " +
                "level, when new liquidity forms, when the break fails, or at 15:10. Entries 09:20-14:00, one position per chart." +
                (if (b.parked.isEmpty()) "" else " ${b.parked.sorted().joinToString(" and ")} stays parked (switch it back on under the row).")
            else "${LiquidityRules.ARM.label} disarmed." + if (holding) " Its open position is still managed to its exit." else ""
        }
        // 15 Oct (Boss's cleanup): a hidden arm is switched on only from the Hidden arms list (its restore), never here.
        if (on && !HiddenArms.mayArm(source)) return@withLock HiddenArms.refusal(armOf(source).label)
        val paperOnly = armOf(source).paperOnly
        val live = liveNow() && !paperOnly
        if (on && live && !pinConfirmed) return@withLock "The app is in Live: arm it with your PIN or fingerprint."
        b.armed[source] = on
        b.auto[source] = automatic
        // Armed (or disarmed) just now: the next decision takes only a fresh break, never one already under way.
        b.watched.keys.removeAll { it.startsWith("$source|") }
        b.liveOk[source] = on && live && pinConfirmed
        if (!on) b.pending.remove(source)
        // Armed again: the Hero arm's self-disarm counters start afresh (it needs a manual re-arm after tripping).
        if (on) b.since[source] = now().toString()
        // Switched back on by Boss after the 13 Oct park ([FamilyPark]): no longer parked, and never parked again.
        val unparked = on && b.famParked.remove(source)
        if (unparked) b.status[source] = ""
        save(b)
        switchNote(armOf(source).label, on, live, automatic)
        if (unparked) runCatching { Diag.record("orb", "${armOf(source).label}: switched back on by you after the 13 Oct park" + if (live) " (Live, with your PIN)" else ", on paper") }
        val label = armOf(source).label
        // What it is told it trades: the parameters in force today for the account it was armed on (review G2-6; its
        // constants unless the lab promoted a variant - then the same words with the variant's numbers). Memory only.
        val ap = runCatching { paramsOf(armOf(source), today(), live) }.getOrElse { com.optionslab.engine.orb.OrbParams.of(armOf(source)) }
        val (firstBar, _) = OrbRules.decisionBars(ap)
        if (on && armOf(source).hero) "$label armed on paper (its live trade at Zerodha follows only by its own \"Trade live\" switch, in Live with AI trades on) - ${HeroRules.NOT_PROVEN}. Fully automatic, " +
            "NIFTY expiry days only (from the instrument master): from 13:30 to 14:45, when the ATM straddle is 15% above its low " +
            "since 12:00 and NIFTY has moved 0.25% in 15 minutes, it buys the nearest OTM option on that side priced Rs 1-5 with a " +
            "LIMIT order, up to Rs 5,000 of premium, once a day. It sells half at 5x the price paid and the rest at 20x or " +
            "15:05 (the expiry square-off), with a stop at -60% of the premium on a minute's close. With the old exits it " +
            "lost on all 13 trades out of sample (-Rs 64,190). It disarms itself after ${HeroRules.MAX_LOSING_DAYS} losing expiry " +
            "days in a row or Rs 50,000 lost. ${HeroRules.SIGNAL_SOURCE}."
        else if (on && armOf(source).fade) "$label armed on paper (its live trade at Zerodha follows only by its own \"Trade live\" switch, in Live with AI trades on), fully automatic: when a 5-minute bar " +
            "reaches the outer ${if (kotlin.math.abs(ap.edge - 0.10) < 1e-9) "tenth" else pts(ap.edge * 100) + "%"} of the opening range and closes back inside, it buys the option toward the middle - 1 lot, " +
            "${pointsWith(ap.stopPoints)} stop, ${pointsWith(ap.targetPoints)} target and the 15:10 exit, at most ${RangeFadeRules.MAX_ENTRIES} a day, " +
            "from ${hhmm(RangeFadeRules.FIRST_BAR)}."
        else if (on && paperOnly) "$label armed on paper (its live trade at Zerodha follows only by its own \"Trade live\" switch, in Live with AI trades on), fully automatic: it fades a failed break of the " +
            "opening range - a 5-minute bar through the range high or low that closes back inside - with 1 lot, ${pointsWith(ap.stopPoints)} stop, " +
            "${pointsWith(ap.targetPoints)} target and the 15:10 exit, at most ${SweepRules.MAX_ENTRIES} a day, from ${hhmm(firstBar)}."
        else if (on) "$label armed" + (if (live) " on ZERODHA (live), " else " on paper, ") +
            (if (automatic) "fully automatic: it buys and sells by itself every trading day until you switch it off." else "you approve each entry.") +
            " It decides on 5-minute BANKNIFTY bars from ${hhmm(firstBar)}." +
            // R14's re-entry cap, when it is in force on this account.
            (if (ap.sideCap > 0) " At most ${ap.sideCap} ${if (ap.sideCap == 1) "entry" else "entries"} a side a day (research R14: re-entries cost charges and made about nothing)." else "")
        else "$label disarmed." + if (b.positions.any { it.arm == source && it.open }) " Its open position is still managed to its exit." else ""
    }

    /**
     * 25 Oct (app audit; 23 Oct's flips that left no record): one diary line for every flip of an arm's switch - which arm, on or
     * off, paper or live, automatic or approved - timed by the diary. Off the order path (the switch's own coroutine).
     */
    internal fun switchNote(label: String, on: Boolean, live: Boolean, automatic: Boolean, pin: Boolean = true) {
        runCatching { Diag.record("switch", switchWords(label, on, live, automatic, pin)) }
    }

    /** [pin]: false for Liquidity 15+5 switched on in Live by its switch alone (15 Nov, Boss: no PIN of its own). */
    internal fun switchWords(label: String, on: Boolean, live: Boolean, automatic: Boolean, pin: Boolean = true): String =
        if (on) "$label switched on (${if (!live) "paper" else if (pin) "live, with your PIN" else "live, by its switch"}; ${if (automatic) "fully automatic" else "you approve each entry"})"
        else "$label switched off"

    /** After a restore: both arms off, nothing waiting for approval. */
    suspend fun disarmAll() = lock.withLock {
        val b = book(); b.armed.clear(); b.auto.clear(); b.liveOk.clear(); b.pending.clear(); save(b)
        runCatching { Diag.record("switch", "every ORB-family and Liquidity arm switched off (a backup was restored)") }
        com.optionslab.app.ira.IraCoach.forgetParked()
    }

    /**
     * Reset paper: the arms' paper positions, their paper day (bars decided, status) and waiting paper approvals go,
     * and every arm not armed for Zerodha is switched off. Zerodha positions and live-armed arms are untouched.
     */
    suspend fun resetPaper() = lock.withLock {
        val b = book()
        b.positions.removeAll { !it.live }
        // (As before: an arm whose stored liveOk is set is kept - 15 Nov: for Liquidity, a book Boss cleared with his own switch.)
        val live = b.positions.filter { it.open }.map { it.arm }.toSet() + b.liveOk.filterValues { it }.keys
        if (!liveNow()) b.pending.clear()
        // (15 Nov: a book switched off here loses its clearance too.)
        for (k in b.armed.keys.toList()) if (k !in live) { b.armed.remove(k); b.auto.remove(k); b.liveOk.remove(k) }
        b.decided.keys.removeAll { it.substringBefore('|') !in live }
        b.watched.keys.removeAll { it.substringBefore('|') !in live }
        b.status.keys.removeAll { it !in live }
        save(b)
        hints(b)
    }

    /** The book whose signal the Liquidity 15+5 row shows (the row approves and skips for it). */
    private fun liquidityBook(b: Book): String? = LiquidityRules.BOOKS.map { it.source }.firstOrNull { b.pending.containsKey(it) }

    suspend fun approve(source: String, pinConfirmed: Boolean = false): String {
        // Liquidity 15+5 decides its account again at the approval (Live, unless its self-review parked it to paper) and asks
        // for the PIN itself when that account is Zerodha (review G2-1).
        if (source == LiquidityRules.ARM.source || armOf(source).liquidity) return approveLiquidity(source, pinConfirmed)
        // A live entry is sent only after the owner's PIN or fingerprint (the UI asks first).
        if (liveNow() && !pinConfirmed) return "The app is in Live: approve with your PIN on Home → Strategies."
        val p = lock.withLock { book().pending.remove(source)?.also { save(book()) } } ?: return "Nothing is waiting for approval."
        if (now().isAfter(p.expires)) return "The ${armOf(source).label} signal expired at ${hhmm(p.expires)}; a new break will ask again."
        return lock.withLock {
            val b = book()
            val legs = b.legs ?: return@withLock "The day's contracts are not loaded yet."
            // A paper approval never turns into a live order because the switch flipped meanwhile.
            val live = liveNow()
            if (live && !pinConfirmed) return@withLock "The app switched to Live: approve with your PIN on Home → Strategies."
            val msg = enter(b, armOf(source), if (p.right == "CE") legs.ce else legs.pe, p.signalBar, live)
            b.status[source] = msg; save(b); describe(msg)
        }
    }

    suspend fun skip(source: String): String = lock.withLock {
        val b = book()
        val key = if (source == LiquidityRules.ARM.source) liquidityBook(b) ?: source else source
        // (Liquidity's skipped signal is kept for Jarvis's words too; the skip itself is exactly as before.)
        b.pending[key]?.takeIf { pd -> LiquidityRules.BOOKS.any { it.source == pd.arm } }?.let { pd -> runCatching {
            noteLiquidity(now(), pd.arm, "skipped_by_you", pd.signalBar, pd.level?.let { LiquidityRules.Signal(if (pd.right == "CE") 1 else -1, it, pd.target) })
        } }
        b.pending.remove(key); b.status[key] = "skipped_by_you"; save(b); "Skipped."
    }

    /**
     * Liquidity 15+5's approval (review G2-1): its account is decided again now as at the signal - the app's switch and its
     * self-review's park to paper ([ArmAccount.approval]) - and must be the one the approval notice named, else nothing is
     * bought (a paper notice never becomes a Zerodha order). The PIN is asked only when that account is Zerodha.
     */
    private suspend fun approveLiquidity(source: String, pinConfirmed: Boolean): String {
        // The self-review's parks, read once per process off the arms' lock (a worker thread); under it only memory is read.
        runCatching { SmartWorkers.ensureLoaded() }
        // 19 Nov 2026 (audit batch 2): the instrument list loaded off the lock when it is not yet (under it only memory is read).
        runCatching { warmContracts(WARM_WAIT_MS) }
        return lock.withLock { approveLiquidityLocked(source, pinConfirmed) }
    }

    private suspend fun approveLiquidityLocked(source: String, pinConfirmed: Boolean): String {
        val b = book()
        val key = (if (source == LiquidityRules.ARM.source) liquidityBook(b) else source) ?: return "Nothing is waiting for approval."
        val waiting = b.pending[key] ?: return "Nothing is waiting for approval."
        val live = ArmAccount.approval(armOf(waiting.arm), liveNow(), liqToPaper(b, armOf(waiting.arm)), waiting.live)
        // A live entry is sent only after the owner's PIN or fingerprint (the UI asks first); the signal keeps waiting for it.
        if (live == true && !pinConfirmed) return "The app is in Live: approve with your PIN on Home → Strategies."
        val p = b.pending.remove(key) ?: return "Nothing is waiting for approval."
        save(b)
        if (now().isAfter(p.expires)) return "The ${LiquidityRules.ARM.label} signal expired at ${hhmm(p.expires)}; a new break will ask again."
        if (live == null) {
            val was = if (p.live == true) "Zerodha (live)" else "the paper account"
            val msg = "account_changed: the signal was for $was and its account changed since (the app's Live switch, or its self-review " +
                "parked it to paper) - nothing was bought; a new break will ask again"
            b.status[p.arm] = msg; save(b)
            return describe(msg)
        }
        val signal = LiquidityRules.Signal(if (p.right == "CE") 1 else -1, p.level ?: return "The signal lost its level; a new break will ask again.", p.target)
        val msg = enterLiquidity(b, armOf(p.arm), signal, p.signalBar, p.strike ?: return "The signal lost its strike.", live, p.near, p.volSkip, p.strong)
        b.status[p.arm] = msg; save(b); return describe(msg)
    }

    // ---- the minute cycle ---------------------------------------------------------

    /**
     * One pass: manage open positions, then let each armed arm decide on the last
     * completed bar. Called by the market watch every pass (after Paper.tick, so a
     * resting stop has already been matched against the latest price).
     */
    suspend fun tick() {
        if (!Market.isTradingDay()) return
        // The Hero arm's NIFTY candles (the index and the ATM straddle's legs, many strikes) are read BEFORE the lock is taken:
        // read under it they held every pass, the 15-second stop checks and the rows' refresh waiting on the network.
        val t0 = now()
        runCatching { heroPrefetch(t0) }
        // The prefetch read the network: the pass time is taken again, so the stops, the time exits and the decisions under
        // the lock use a fresh time. The prefetched bars are still used - unless the minute has turned meanwhile (they were
        // read for t0's minute; the new minute's decision reads its own, as it always did).
        val t = now()
        if (t.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) != t0.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)) heroFed.clear()
        // The pass's account (review G2-2/G2-3), read here off the arms' lock (a worker thread), only while an arm is armed: the
        // app's Live switch (null when it cannot be read: each decision then reads it itself, as before) and, once per process,
        // the self-review's parks (memory after that). One answer for the pass's parameters, entries and the lab's record.
        val appLive = if (armedHint) runCatching { liveNow() }.getOrNull() else null
        if (armedHint) runCatching { SmartWorkers.ensureLoaded() }
        // 30 Oct: the pass's index reads (each index read once a pass, as before); the armed arms' decisions use them exactly as
        // they did when they were made inside [tickLocked]. The switched-off arms' shadow has its own pass ([shadowTick]).
        // 19 Nov 2026 (audit batch 2: the phone showed "ORB arms max 9 min 40 s" - every candle read ran under the arms' lock): the pass's
        // index candles are read HERE, off the lock, within [PASS_READ_MS] ([prefetchIndices]) - the open Liquidity positions' indices, and
        // while an arm decides BANKNIFTY and each armed Liquidity book's index - and the instrument list loaded if it is not yet
        // ([warmContracts]). Under the lock only memory is read. Which indices: the book as last saved ([bookNow]), never a wait.
        val reads = runCatching { passReads(t) }.getOrElse { IndexData.Reads() }
        try {
            tickLocked(t, appLive, reads)
            // 19 Nov 2026 (audit batch 2): candidate (f)'s record-only option reads, after the pass and off the lock (never before an order).
            runCatching { strongLater() }
        } finally {
            heroFed.clear()
        }
    }

    /** 19 Nov 2026 (audit batch 2): the pass's index reads made before the lock ([tick]): what its exits and decisions will need. */
    private suspend fun passReads(t: LocalDateTime): IndexData.Reads<List<Upstox.Bar>> {
        val b = bookNow()
        val liq = LinkedHashSet<String>()
        b.positions.filter { it.open && !it.unconfirmed }.forEach { p ->
            runCatching { armOf(p.arm) }.getOrNull()?.takeIf { it.liquidity }?.let { liq += LiquidityRules.underlyingOf(it) }
        }
        val unds = LinkedHashSet<String>(liq)
        val armed = ALL_ARMS.filter { b.armed[it.source] == true }
        if (armed.isNotEmpty() && OrbRules.inWindow(t.toLocalTime())) {
            // (As the pass always read them: BANKNIFTY whenever an arm decides, and each armed Liquidity book's own index.)
            unds += OrbRules.UNDERLYING
            armed.filter { it.liquidity }.forEach { val u = LiquidityRules.underlyingOf(it); unds += u; liq += u }
            runCatching { warmContracts(WARM_WAIT_MS) }
        }
        if (unds.isEmpty()) return IndexData.Reads()
        return prefetchIndices(t, unds, liq, testPassReadMs ?: PASS_READ_MS)
    }

    /**
     * 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only, so i can continuously observe it"):
     * every arm that is off decides too and hands each would-be entry to [OffShadow]; it never orders anything. Its own pass, OUTSIDE
     * the arms' lock for its reads and (CI review) outside the watch's pass: the watch hands it to its late job, after the stops
     * ([OffShadow.runLate]), so the open positions' looks never wait on it. Its index reads are its own (a pass's worth, shared by
     * its arms).
     */
    suspend fun shadowTick() {
        if (!Market.isTradingDay()) return
        try {
            offShadowPass(now(), IndexData.Reads())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Diag.record("orb", "switched-off shadow: its pass failed (${e.javaClass.simpleName}); tried again next pass") }
        }
    }

    private suspend fun tickLocked(t: LocalDateTime, appLive: Boolean?, reads: IndexData.Reads<List<Upstox.Bar>>) {
        // 19 Nov 2026 (audit batch 2: one lock covered every decision, entry and exit, and the pass held it for minutes): the open positions'
        // exits first, then each armed arm's decision in its own short hold of the lock - an exit check (the 15-second look, the stream's)
        // waits at most for one arm's step, never for the whole pass. The candles were read before ([reads]); nothing here reads them.
        val decide = lock.withLock {
            val b = book()
            runCatching { priceCheck(b, t) }
            runCatching { liquidityExits(b, t, reads) }
            runCatching { liquidityNotices(b, t) }
            runCatching { heroKillCheck(b) }
            val anyArmed = ALL_ARMS.any { b.armed[it.source] == true }
            // The armed arms' indices on Zerodha's stream when it runs: a bar's last minute is then known at its close.
            followIndices(b)
            if (!anyArmed || !OrbRules.inWindow(t.toLocalTime())) { save(b); return@withLock false }
            // The strategy lab (11 Oct): each strategy ran today, and on the account its new entries go to - one record per lab
            // key (Liquidity's books are one strategy), live when any of its armed arms enters live (review G2-2). Memory only.
            runCatching {
                val parked = SmartWorkers.parkedToPaperNow("liquidity")
                ArmAccount.byKey(ALL_ARMS.filter { b.armed[it.source] == true }, VariantRunner::lab) {
                    ArmAccount.entry(it, appLive == true, parked || (it.liquidity && !liveCleared(b, it.source))) }
                    .forEach { (k, live) -> VariantRunner.saw(k, live) }
            }
            true
        }
        if (!decide) return
        // Each index is read at most once on this pass, shared by the books on it (13 Oct audit fix): BANKNIFTY's candles here
        // for the ORB family (and Liquidity's BANKNIFTY books), FINNIFTY's and MIDCPNIFTY's when their books decide.
        // (30 Oct: [reads] is made by [tick] for this pass, so the switched-off shadow after the lock shares it.)
        // (19 Nov 2026, audit batch 2: read before the lock - [passReads]; this only takes them from [reads].)
        val bars = runCatching { indexBars(t, reads, offline = true) }.getOrNull()
        for (arm in ALL_ARMS) lock.withLock {
            val b = book()
            if (b.armed[arm.source] != true) return@withLock
            // A book stops only when ITS index has no candles (13 Oct audit fix: one BANKNIFTY failure used to stop every book,
            // the FINNIFTY and MIDCPNIFTY ones too). The Hero arm reads its own NIFTY data; a Liquidity book reads its own
            // index when it decides ([liquidityCycle], from [reads]); the ORB family decides on the BANKNIFTY bars above.
            val none = if (arm.liquidity || bars != null) null else IndexData.bookIndex(arm.hero, null)?.let(IndexData::none)
            val s = none ?: runCatching { cycle(b, arm, t, bars.orEmpty(), appLive, reads) }.getOrElse { "error: ${it.message}" }
            // Liquidity's verdict between its decision bars, kept for Jarvis's words only (a bar's own was kept by its cycle).
            if (arm.liquidity && s != "no_decision_bar") runCatching { noteLiquidity(t, arm.source, s) }
            // Hero's verdict between its decision minutes, kept for Jarvis's words only (a minute's own was kept by its cycle).
            if (arm.hero && s != "no_decision_bar") runCatching { noteHero(t, s) }
            // "no_decision_bar" repeats within a bar; keep the bar's own verdict on screen.
            if (s != "no_decision_bar" || b.status[arm.source].isNullOrEmpty()) {
                // Each bar's verdict goes to the diagnostics once (why it did or did not enter), with the range and the bar.
                if (s != "no_decision_bar" && s != b.status[arm.source]) runCatching {
                    val last = bars?.lastOrNull()
                    Diag.record("orb", "${arm.label}: $s" + (b.range?.let { " · range %.1f-%.1f".format(java.util.Locale.ENGLISH, it.second, it.first) } ?: "") +
                        (last?.let { " · bar ${it.start.toLocalTime()} close %.1f".format(java.util.Locale.ENGLISH, it.close) } ?: "") +
                        // 14 Oct: Hero's signal reads the straddle's premium, not the index alone (not the default): said.
                        (if (arm.hero) " · ${HeroRules.SIGNAL_SOURCE}" else ""))
                }
                b.status[arm.source] = s
            }
        }
        lock.withLock {
            val b = book()
            // Again after the decisions: an entry made in this pass is told now, not a minute later (told once either way).
            runCatching { liquidityNotices(b, t) }
            save(b)
        }
    }

    /**
     * A lighter pass between minutes while a position is open: the stop, the target and the clock - and since 08 Oct
     * (research/PROFIT_LOCK_8OCT.md fix 3) Liquidity 15+5's index exits too, so a new minute's index candle is acted on
     * within 15 s, not on the next full pass. Under the arms' lock as the full pass: one sale in flight per position (a sold
     * position is closed; a failed sale is tried again).
     */
    suspend fun priceCheckOnly(fast: Boolean = false, stream: Boolean = false) {
        val t = now()
        // 19 Nov 2026 (audit batch 2: the index candles were read here under the arms' lock - a hung read held every stop check): the
        // stops, targets and the clock first, under the lock, from memory and Zerodha's books as before; the Liquidity index exits with
        // them when this minute's candles are already in memory. Otherwise a regular look reads them OFF the lock within [LOOK_READ_MS]
        // and takes the lock again for those exits alone; a fast look (the stream's) reads nothing - the next regular look or pass does.
        // 20 Nov 2026 (audit): the open Liquidity positions' charts folded from memory before the lock (pure maths, off it; [warmLiqFolds]).
        runCatching { warmLiqFolds(t) }
        val need = lock.withLock {
            val b = book()
            // [fast]: an event-driven look ([FastPath], up to several a second). A look that changed only the best or lowest
            // premium seen (peak, low: re-read from the stream's ticks anyway) keeps the book in memory and is written with the
            // next pass that saves it - no Keystore write per tick. Anything else (an exit, a stop moved, a sale) is written now.
            // [stream] (round 2): every live position's contract has a fresh stream tick: its exits decide from the ticks and
            // Zerodha's orders and positions as last read ([Broker.ordersRecon]); only an order goes over REST.
            val before = if (fast) untracked(b) else null
            runCatching { priceCheck(b, t, stream = fast && stream, regular = !fast) }
            val unds = liquidityIndicesOpen(b)
            runCatching { liqFoldPlan = liquidityChartsOpen(b) }
            val mem = unds.mapNotNull { u -> minutesInMemory(u, t)?.let { u to it } }.toMap()
            val ready = mem.size == unds.size
            // (A fast look judges every open Liquidity position now - the time stop on every look: those whose index minutes are in memory on
            // them, the rest quietly with none (as a failed read: the index exits wait); those minutes are read off the lock for the next look.)
            if ((ready || fast) && unds.isNotEmpty()) runCatching { liquidityExits(b, t, IndexData.Reads(), mem, quiet = !ready) }
            if (fast && !ready) refreshSoon(t, unds - mem.keys)
            runCatching { liquidityNotices(b, t) }
            if (before != null && before == untracked(b)) { cache = b; hints(b, copy = false); wipedSince(b) } else save(b)
            if (ready || fast) emptySet() else unds
        }
        if (need.isEmpty()) return
        val reads = prefetchIndices(t, need, need.toSet(), LOOK_READ_MS)
        lock.withLock {
            val b = book()
            runCatching { liquidityExits(b, t, reads) }
            runCatching { liquidityNotices(b, t) }
            save(b)
        }
    }

    /** TEST ONLY (19 Nov 2026, audit batch 2): an index's 1-minute bars as kept in memory for a minute ([minutesInMemory]); null in the app. */
    @Volatile internal var testMinutesInMemory: ((String, LocalDateTime) -> List<Upstox.Bar>?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }

    /** 19 Nov 2026 (audit batch 2): one off-lock read of the open positions' index candles at a time ([refreshSoon]). */
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 19 Nov 2026 (audit batch 2): a fast look found [unds]' minutes not in memory: they are read OFF the lock, in the background (one such
     * read at a time, [LOOK_READ_MS] at most), so the next look judges their index exits - the look itself never waits on the network.
     * Never in a test with the arms' feeds set (those are read by each regular look).
     */
    private fun refreshSoon(t: LocalDateTime, unds: Set<String>) {
        if (unds.isEmpty() || testFeeds() || !refreshing.compareAndSet(false, true)) return
        offLock.launch {
            try { prefetchIndices(t, unds, unds, LOOK_READ_MS) } finally { refreshing.set(false) }
        }
    }

    /** 19 Nov 2026 (audit batch 2): the indices of [b]'s open Liquidity positions (their index exits read these candles). */
    private fun liquidityIndicesOpen(b: Book): Set<String> = b.positions.filter { it.open && !it.unconfirmed }
        .mapNotNull { p -> runCatching { armOf(p.arm) }.getOrNull()?.takeIf { it.liquidity }?.let { LiquidityRules.underlyingOf(it) } }.toSet()

    /** 20 Nov 2026 (audit): the (index, chart minutes) of [b]'s open Liquidity positions ([warmLiqFolds]). */
    private fun liquidityChartsOpen(b: Book): Set<Pair<String, Int>> = b.positions.filter { it.open && !it.unconfirmed }
        .mapNotNull { p -> runCatching { armOf(p.arm) }.getOrNull()?.takeIf { it.liquidity }?.let { LiquidityRules.underlyingOf(it) to LiquidityRules.minutesOf(it) } }
        .toSet()

    /** [b]'s positions without the premiums tracked on every price (what [priceCheckOnly]'s fast look may leave unsaved). */
    private fun untracked(b: Book): List<Position> = b.positions.map { it.copy(peak = null, low = null) }

    /**
     * An entry left unapproved past its time ([Pending.expires]) is dropped - said once (a notice in place of the approval's,
     * and a diagnostics line), so an APPROVE arm that did not trade says why instead of going quiet.
     */
    private fun lapsed(arm: Arm, pd: Pending) {
        val mins = java.time.Duration.between(pd.signalBar, pd.expires).toMinutes()
        val text = "${arm.label}: the ${hhmm(pd.signalBar)} signal (BUY ${pd.right}) was not approved in $mins minutes and lapsed at " +
            "${hhmm(pd.expires)} - nothing was bought. A new signal asks again."
        runCatching { Diag.record("orb", text) }
        if (arm.liquidity) runCatching {
            noteLiquidity(now(), arm.source, com.optionslab.ira.LiquidityWhyNot.LAPSED, pd.signalBar,
                pd.level?.let { LiquidityRules.Signal(if (pd.right == "CE") 1 else -1, it, pd.target) })
        }
        runCatching { Notifier.post(app, 6960 + ALL_ARMS.indexOf(arm), Notifier.APPROVAL, "${if (arm.liquidity) arm.label else "ORB"} signal lapsed: not approved in $mins minutes", text, "almanac") }
    }

    /**
     * [appLive]: the app's Live switch as the pass read it (null: unread, [liveNow] is read here as before). [reads]: the pass's
     * index reads, shared by the books on each index.
     */
    private suspend fun cycle(b: Book, arm: Arm, t: LocalDateTime, bars: List<Bar>, appLive: Boolean? = null,
                              reads: IndexData.Reads<List<Upstox.Bar>>? = null): String {
        val day = t.toLocalDate()
        val watchKey = "${arm.source}|$day"
        // Holding a position or waiting for the owner's approval is running, not paused: re-entries keep their rules.
        fun watching() { bars.lastOrNull()?.let { b.watched[watchKey] = it.start.toString() } }
        if (b.positions.any { it.arm == arm.source && it.open }) { watching(); return "holding" }
        if (!t.toLocalTime().isBefore(OrbRules.SQUARE_OFF)) return "flat_after_square_off"
        if (Strategies.stoppedToday()) { b.watched.remove(watchKey); return "stopped_for_today" }
        // 22 Oct (arms audit): a hidden arm (Boss's cleanup) decides nothing new - also in the seconds between its exit and the
        // cleanup's switch-off, when a break could still create an approval and post "approve BUY" for an arm the list says is
        // off (the buy itself was refused, the notification was not). Memory only.
        if (HiddenArms.keyOfSource(arm.source)?.let { HiddenArms.blocks(it) } == true) { b.watched.remove(watchKey); return HIDDEN_NO_ENTRY }
        if (arm.hero) return heroCycle(b, t)
        b.pending[arm.source]?.let { pd -> if (t.isAfter(pd.expires)) { b.pending.remove(arm.source); lapsed(arm, pd) } else { watching(); return "awaiting_approval" } }
        // 25 Oct (h14's capped limit, paper): a signal waiting for its price to come back under the cap is worked first, until its
        // 3 minutes are up (never chased: the cap never moves).
        if (arm.liquidity) capWaits[arm.source]?.let { w -> watching(); return capStep(b, arm, w, t, appLive) }
        if (arm.liquidity) return liquidityCycle(b, arm, t, appLive, reads)
        // The account this arm's new entries go to (review G2-3): the app's Live switch (the paper-only arms: paper). The
        // signal's parameters, the order and the position's stamped exits all use it - never one account's entry rule with
        // another's exits. The parameters are the arm's constants unless the lab promoted a variant for that account from a
        // session before today (memory only).
        val live = !arm.paperOnly && (appLive ?: liveNow())
        val ap = paramsOf(arm, day, live)
        shownParams[arm.source] = ap
        // The range on screen is the 09:15-10:00 one (as Sweep and Range Fade always use) whatever an arm's variant (review G2-7):
        // never the last arm's to run.
        OrbRules.openingRange(bars)?.let { b.range = it; b.rangeDay = day }
        val rng = OrbRules.openingRange(bars, ap.orEnd) ?: return "waiting_for_opening_range"
        val legs = contracts(b, day, bars) ?: return "no_contract"
        PreTradeShadow.orbBars(bars)                    // R17 shadow: the bars its entry note reads (memory only)
        val last = bars.last()
        val spent = b.decided.getOrPut("${arm.source}|$day") { HashSet() }
        if (!spent.add(last.start.toString())) return "no_decision_bar"
        val lastExit = b.positions.filter { it.arm == arm.source && it.day == day }.mapNotNull { it.exitTime }.maxOrNull()
        // ORB Fresh after the trade manager took it out today (its exit or its lock): no new entry today. R12 did not test
        // re-entries after an early exit, and fewer trades only lower risk.
        if (arm.freshOnly && b.positions.any { it.arm == arm.source && it.day == day && (it.why == MANAGER_EXIT || it.why == MANAGER_LOCK) }) {
            watching(); return MANAGER_DONE
        }
        if (arm.fade) {
            // Range Fade: a bar at the edge of the range that closes back inside is faded toward the middle; paper only, automatic.
            val entries = b.positions.count { it.arm == arm.source && it.day == day }
            val (dir, why) = RangeFadeRules.entrySignal(bars, rng, lastExit, entries, ap.edge, ap.lastEntry)
            watching()
            if (dir == 0) return why
            return enter(b, arm, if (dir > 0) legs.ce else legs.pe, last.start, live = false)
        }
        if (arm.sweep) {
            // ORB Sweep: a single bar's failed break is the signal (nothing to chase), paper only, automatic.
            val entries = b.positions.count { it.arm == arm.source && it.day == day }
            val (dir, why) = SweepRules.entrySignal(bars, rng, lastExit, entries, ap.lastEntry, ap.orEnd)
            watching()
            if (dir == 0) return why
            return enter(b, arm, if (dir > 0) legs.ce else legs.pe, last.start, live = false)
        }
        // After a pause (stopped for the day, the kill switch, a refused entry, the app not running, armed just now) the
        // previous bar was not watched: a break already under way is not chased, only a fresh one is taken.
        // A pause is only one the owner made - armed just now, stopped for the day, the kill switch - never missed data
        // (a slow pass, a failed fetch, the app away); treating those as pauses left ORB idle all day on 29 Sep.
        val prevBar = bars.getOrNull(bars.size - 2)
        // Missed bars are never a pause: every pass reads the whole day's bars again, so after a slow pass, a failed fetch or
        // the app being away the arm decides on where the market is now (a break still under way is taken).
        val resumed = prevBar != null && !b.watched.containsKey(watchKey)
        // The re-entry cap (R14, [com.optionslab.engine.orb.ReentryCap]): today's entries per side of this arm, on either account.
        val taken = com.optionslab.engine.orb.ReentryCap.taken(b.positions.filter { it.arm == arm.source && it.day == day }.map { it.right })
        val (direction, why) = OrbRules.entrySignal(bars, rng, arm, lastExit, requireFresh = resumed, p = ap, taken = taken)
        if (direction == 0) {
            watching()
            return if (why == "not_a_fresh_break" && !arm.freshOnly) "waiting_for_fresh_break_after_pause" else why
        }
        val right = if (direction > 0) "CE" else "PE"
        val c = if (direction > 0) legs.ce else legs.pe
        // The findings bus (10 Oct): the range broken, for the other workers (a finding only).
        SmartWorkers.found(arm.label, com.optionslab.ira.Findings.Kind.BREAK, OrbRules.UNDERLYING, direction, last.close, 60,
            "range broken ${if (direction > 0) "up" else "down"}")
        // Automatic unless the owner chose approvals, or the arm was armed in Paper and now finds the app in Live.
        if (b.auto[arm.source] == false || (live && b.liveOk[arm.source] != true)) {
            // The order flow at the signal, logged while it waits (decided at the approval, if Boss approves).
            FlowGate.outcome(FlowGate.gate(arm.source, OrbRules.UNDERLYING, direction, live, last.start.toString(), c.right.name, decide = false, contract = c), "awaiting_approval")
            // Valid until the next bar completes: after that the signal is stale.
            b.pending[arm.source] = Pending(arm.source, right, last.start, last.start.plusMinutes(10))
            Notifier.post(app, 6960 + ALL_ARMS.indexOf(arm), Notifier.APPROVAL, "${arm.label}: approve BUY ${c.symbol}",
                "BANKNIFTY closed ${if (direction > 0) "above" else "below"} the opening range on the ${hhmm(last.start)} bar. " +
                    (if (live) "LIVE on Zerodha, 1 lot: approve it on Home in the app" +
                        (if (b.auto[arm.source] != false) " (arm it again while in Live to make it automatic)" else "") else "Paper account, 1 lot") +
                    ". Approve by ${hhmm(last.start.plusMinutes(10))} or it lapses.", "almanac", approve = "orb")
            watching()
            return "awaiting_approval"
        }
        // A refused entry (no price, a limit) is tried again on the next bar; only the kill switch counts as a pause.
        return enter(b, arm, c, last.start, live).also {
            if (!it.startsWith("entered") && runCatching { AppSettings.load().guardKill }.getOrDefault(false)) b.watched.remove(watchKey) else watching()
        }
    }

    /**
     * The day's strike from the first completed bar at or after 09:20 and its expiry, held all day: on an expiry day the
     * option expiring today (the owner's choice, 2026-10-01), otherwise the nearest expiry after today. ORB, ORB Fresh,
     * ORB Sweep and Range Fade trade these legs; Liquidity 15+5 picks its own contract (always the next expiry).
     */
    private fun contracts(b: Book, day: LocalDate, bars: List<Bar>): Legs? {
        b.legs?.let { if (it.day == day) return it }
        val ref = OrbRules.strikeBar(bars) ?: return null
        val strike = OrbRules.atmStrike(ref.close)
        // (19 Nov 2026, audit batch 2: the instrument list as it is in memory or on the phone - never its download under the arms' lock;
        // not loaded for today yet: no contract this pass, [warmContracts] loads it off the lock.)
        val listed = (listedNow() ?: return null).filter { it.underlying == OrbRules.UNDERLYING }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryOnOrAfter(day, listed) ?: return null
        val ce = contractNow(OrbRules.UNDERLYING, expiry, strike.toDouble(), Right.CE) ?: return null
        val pe = contractNow(OrbRules.UNDERLYING, expiry, strike.toDouble(), Right.PE) ?: return null
        return Legs(day, strike, expiry, ce, pe).also { b.legs = it }
    }

    /**
     * 19 Nov 2026 (audit batch 2: no network or disk under the arms' lock - the phone showed "ORB arms max 9 min 40 s"): today's instrument
     * list as [Market.contracts] returns it once loaded, from memory or the phone's saved list only - never the download. Null: not loaded
     * for today yet ([warmContracts] loads it off the lock).
     */
    private fun listedNow(): List<Upstox.Contract>? =
        runCatching { Market.cachedContracts()?.takeIf { it.first == Market.today() }?.second }.getOrNull()

    /** 19 Nov 2026 (audit batch 2): [Paper.contractFor] from [listedNow] only (null: not listed, or the list not loaded today). */
    private fun contractNow(underlying: String, expiry: LocalDate, strike: Double, right: Right): Paper.Contract? =
        listedNow()?.firstOrNull { it.underlying == underlying && it.expiry == expiry && it.strike == strike && it.right == right }
            ?.let { c -> Paper.Contract(Paper.symbolOf(c), c.underlying, c.expiry, c.strike, c.right, c.lotSize, c.instrumentKey) }

    /** 19 Nov 2026 (audit batch 2): the arms' own work off the lock (the instrument list's download). */
    private val offLock = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    /** 20 Nov 2026 (b5-stream merge): a lost exit sale's background search and its cancels read and write in the stop checks' lane. */
    private val SAFETY_LANE = Broker.Lane(com.optionslab.ira.RateGate.Priority.SAFETY)
    @Volatile private var warming: kotlinx.coroutines.Deferred<Unit>? = null
    internal const val WARM_WAIT_MS = 5_000L

    /** TEST ONLY (20 Nov 2026): the instrument list's download in [warmContracts] (null in the app: [Market.contracts]). Debug builds only. */
    @Volatile internal var testWarmDownload: (() -> Unit)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }

    /** TEST ONLY (20 Nov 2026): [warmContracts] itself. */
    internal suspend fun warmContractsForTest(waitMs: Long) = warmContracts(waitMs)

    /**
     * 19 Nov 2026 (audit batch 2): today's instrument list loaded OFF the arms' lock when it is not yet ([Market.contracts]: a download of
     * tens of megabytes), waited for [waitMs] at most - the download goes on by itself and a later pass finds it. Never in a test with the
     * arms' feeds set (their list is the one the test saved), unless the test gives the download ([testWarmDownload]).
     */
    private suspend fun warmContracts(waitMs: Long) {
        if ((testFeeds() && testWarmDownload == null) || listedNow() != null) return
        val job = synchronized(offLock) {
            // (P2: read back once here, off the lock, so [listedNow] under it never parses the file.)
            warming?.takeIf { it.isActive } ?: offLock.async { runCatching { testWarmDownload?.invoke() ?: Market.contracts() }; runCatching { Market.cachedContracts() }; Unit }.also { warming = it }
        }
        kotlinx.coroutines.withTimeoutOrNull(waitMs) { job.await() }
    }

    /** [live] is decided once by the caller, so the account cannot change between the check and the order. */
    private suspend fun enter(b: Book, arm: Arm, c: Paper.Contract, signalBar: LocalDateTime, live: Boolean): String {
        // 15 Oct (Boss's cleanup): a hidden arm opens nothing new, even while its last position runs to its exit (memory only).
        HiddenArms.keyOfSource(arm.source)?.let { k -> if (HiddenArms.blocks(k)) return HIDDEN_NO_ENTRY }
        // Order speed: the arm decided here (timing only; nothing about the decision changes).
        runCatching { OrderTiming.decided() }
        // The live recording's market snapshot at the signal (a queue add; nothing about the decision changes).
        runCatching { LiveRecorder.signal(arm.label, c.symbol, c.underlying, c.right.name, null, paper = !live) }
        // One index, one side, for every automatic trader (Boss's 06 Oct rule): paper and live alike; Liquidity first (07 Oct).
        exposureRefusal(b, c, arm, live)?.let { return it }
        // The order flow at this decision (9 Oct): logged beside the signal; under CONFIRM an entry it does not agree with is
        // skipped - it never places, enlarges or reverses anything (paper and live alike; the live path is only ever skipped).
        val flow = FlowGate.gate(arm.source, OrbRules.UNDERLYING, if (c.right == Right.CE) 1 else -1, live, signalBar.toString(), c.right.name, contract = c)
        if (flow.skip) return flow.pausedWhy ?: FlowGate.SKIPPED
        if (live) return enterLive(b, arm, c, signalBar).also { FlowGate.outcome(flow, it) }
        // 22 Oct: the price at the signal, handed to the fill; never enter blind.
        val price = entryQuote(c, mayRetryPrice(b))
        val q = price.quote
        // 28 Oct (Boss: "Yes fix finnifty"): with no last price, the ask a buyer pays on a sane book ([Paper.entryPrice]).
        val ltp = Paper.entryPrice(c, q) ?: return noQuote(c, price)
        // The Upstox feed has no bid/ask, so the paper fill is the LTP slipped 5 bps: price the checks the same way.
        val expected = ltp * 1.0005
        // The paper account's parameters today (its stop): this position is managed with them to its exit.
        val pp = paramsOf(arm, now().toLocalDate(), live = false)
        if (OrbRules.stopTrigger(expected, pp.stopPoints) == null) return "refused: premium %.2f is at or below %s, a %s-point stop has no level"
            .format(Locale.ENGLISH, expected, pts(pp.stopPoints), pts(pp.stopPoints))
        if (Strategies.stoppedToday()) return "stopped_for_today"             // stop pressed mid-decision
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", 1, expected), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return "guard_refused: " + refusals.joinToString(" ")
        // 22 Oct: the guard's verdict stamped with this order's fingerprint ([OrderVerdicts]; the stop rests on the fill, so it is not in it).
        // (25 Oct: the verdict's day is the market clock's, the one the paper book's door reads back - so the arm's own test clock on another day never refuses an entry.)
        val verdict = OrderVerdicts.stamp(OrderVerdicts.paperFields(c, "BUY", 1, "MARKET", "MIS", null, null, Market.today(), arm.source), refusals, "paper guard")
        val entry = buyPaperEntry(arm.label, c, 1, q, ownerOf(arm), mayRetryPrice(b), verdict = verdict) { nq ->
            val again = (Paper.entryPrice(c, nq) ?: nq.ltp) * 1.0005
            if (OrbRules.stopTrigger(again, pp.stopPoints) == null) "refused: premium %.2f is at or below %s, a %s-point stop has no level"
                .format(Locale.ENGLISH, again, pts(pp.stopPoints), pts(pp.stopPoints))
            // 22 Oct, merge review: the one index, one side rule again too (another arm may have entered on its own lock meanwhile).
            else exposureRefusal(b, c, arm) ?: paperRecheck(c, 1, nq)
        }
        val buy = entry.result
        val fill = entry.fill ?: return unfilled(c, entry)
        // 22 Oct: priced again for its fill (the one more read or order): labelled on the position, said in the diagnostics.
        val retried = price.retried || entry.retried
        buy.orderId?.let { Strategies.tagOwner("paper:$it", "${arm.label} · entry") }
        FlowGate.taken(flow, buy.orderId)
        Notifier.orderFilled(app, "BUY", fill.quantity, fill.symbol, fill.price, "Paper", "${arm.label} · entry", buy.orderId)
        val trigger = OrbRules.stopTrigger(fill.price, pp.stopPoints)
        var stopId: String? = null
        if (trigger != null) {
            val stop = Paper.place(c, "SELL", 1, "SL-M", "MIS", null, trigger)
            if (stop.ok) { stopId = stop.orderId; stopId?.let { Strategies.tagOwner("paper:$it", "${arm.label} · stop") } }
        }
        val pos = stamped(Position(arm.source, c.symbol, c.right.name, fill.quantity, fill.price, now(), signalBar, buy.orderId, stopId, trigger,
            charges = chargesOf(buy.orderId), ladder = ProfitLock.targetOf(arm) != null, priceRetry = retried))
        b.positions += pos
        marks[c.symbol] = fill.price
        // 22 Oct: the time machine's reality check, in the background (shadow only; one queue add here): the premium stop and
        // target against the index's own past sessions at this slot, and the ORB rule re-run on them.
        runCatching { TimeMachineHost.afterEntry(TimeMachineHost.Entry(arm.source, arm.label, mirrorRef(pos), OrbRules.UNDERLYING, if (c.right == Right.CE) 1 else -1,
            signalBar.plusMinutes(5), pp.stopPoints, pp.targetPoints, premium = true, exitAt = OrbRules.SQUARE_OFF)) }
        // 18 Oct: a paper-only arm's (ORB Sweep, Range Fade) live path: the same contract, lot and stop at Zerodha ([ArmLive]).
        // 22 Oct: not for an entry that needed the one more price read or order - before, it was not taken at all, so the live
        // path stays exactly as it was (paper only; said in the diagnostics).
        if (!retried) mirror(pos, c, trigger)
        else if (arm.paperOnly) runCatching { Diag.record("orb", "${arm.label}: ${c.symbol} filled on paper after a retry for its price - not offered to its live path") }
        // R17 shadow (I3 / I5): the charges check and smart stop noted beside the trade (memory only; nothing changes).
        runCatching { PreTradeShadow.orb(arm, c, expected, fill.quantity, signalBar, pp.stopPoints) }
        return "entered"
    }

    /** Resting stop, +40 target, 15:10 exit, the 15:15 backstop and the operator stop, for every open position. */
    private suspend fun priceCheck(b: Book, t: LocalDateTime, stream: Boolean = false, regular: Boolean = true) {
        if (watchFrom == null) watchFrom = t                                        // this process's first check ([bestSince])
        passPlaced.clear(); passIds.clear()
        val stopped = Strategies.stoppedToday()
        // 19 Nov 2026 (coordinator): this process's first regular look takes back the arms' own live buys no row holds (never on a stream
        // look) - before the open positions are watched, so what it adopts is watched from this pass on ([coldSweep]).
        if (regular && !stream && !coldSwept && Broker.loggedIn && epochMs(t) >= coldNextAt) {
            if (runCatching { coldSweep(b, t, stopped) }.getOrDefault(false)) coldSwept = true
            else { coldFailed(t); coldNextAt = epochMs(t) + COLD_RETRY_MS }        // (review: backed off - not every look's reads again)
        }
        // (19 Nov 2026, final check: on every regular look, whatever is open - a stray buy left after a lost entry's take-back is followed.)
        if (regular && !stream && strayLeft.isNotEmpty()) runCatching { strayLeftCheck(Broker.orders().associateBy { it.id }) }
        // 19 Nov 2026 (audit batch 2, L1): an unclaimed stop of these arms on a contract with no open trade, followed until it ends or the
        // contract is confirmed flat (then cancelled) - its reads only while one is followed.
        // (A contract a trade was booked gone on - on a stream look too - has its unclaimed stops found from a fresh read first.)
        if (regular && !stream && (flatOrphans.isNotEmpty() || flatSince.isNotEmpty())) runCatching {
            val orders = Broker.orders()
            val looked = flatSince.isEmpty() || noteFlatOrphans(b, orders, t)
            flatOrphanCheck(b, t, orders.associateBy { it.id }, Broker.positionBook().net, pruneMarks = looked)
        }
        val liveOpen = b.positions.withIndex().filter { it.value.open && it.value.live }
        if (liveOpen.isNotEmpty()) runCatching { priceCheckLive(b, t, liveOpen, stopped, stream, regular) }
        // 20 Nov 2026 (delta review): the lost stops no open trade asks after - its trade closed, or a split left none of its size - on
        // every regular look while there are any (or cancels of them to confirm), whatever is open: never only while a live trade is.
        if (regular && !stream && (Broker.lostKeys("orbstop:").isNotEmpty() || synchronized(sweepCancels) { sweepCancels.isNotEmpty() }))
            runCatching { sweepLostStops(b) }
        val open = b.positions.withIndex().filter { it.value.open && !it.value.live }
        if (open.isEmpty()) return
        val orders = Paper.state.orders.associateBy { it.orderId }
        for ((i, p) in open) {
            val c = Paper.contractOf(p.symbol) ?: continue
            // The resting stop filled in the paper book: that is the exit.
            val so = p.stopOrderId?.let { orders[it] }
            if (so != null && so.status == "complete") {
                // A stop the profit lock had moved up is the lock's exit (F1: the lock IS the resting stop); one the trade
                // manager's lock moved up (ORB Fresh paper, R12) is the manager's.
                b.positions[i] = p.copy(exit = so.averagePrice?.toDouble() ?: p.stopTrigger, exitTime = so.updateTimestamp,
                    why = stopWhy(p), charges = p.charges + chargesOf(so.orderId))
                continue
            }
            // A stop found cancelled or rejected is gone, not retried.
            val cur = if (so != null && (so.status == "cancelled" || so.status == "rejected")) p.copy(stopOrderId = null).also { b.positions[i] = it } else p
            // The paper account squared the MIS off at 15:15 (the backstop): book it at the last price.
            val net = Paper.state.positions.filter { it.symbol == p.symbol && it.product == "MIS" }.sumOf { it.quantity }
            val ltp = Paper.lastPrice(c)
            ltp?.let { mark(p.symbol, it, t) }
            // The position is gone from the paper book without the arm selling it: the 15:15 square-off
            // (or a restart after it), or the owner closed it (a notification's Close button, the Trade tab).
            // Its resting stop comes out of the book at once, so it can never fill as a short.
            if (net <= 0) {
                cur.stopOrderId?.let { runCatching { Paper.cancel(it, "position_closed") } }
                // Book the actual closing fill (slippage and charges included) when there is one.
                val sq = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && it.strategy == "AUTO_SQUARE_OFF" && !it.timestamp.isBefore(cur.entryTime) }
                // 22 Oct (arms audit): the app's 15:05 expiry square-off (its close carries that owner) is said as such, never
                // "closed by you".
                val expiryClose = sq != null && runCatching { Strategies.owners()["paper:${sq.orderId}"] }.getOrNull() == Origins.EXPIRY
                b.positions[i] = cur.copy(exit = sq?.price?.toDouble() ?: ltp ?: cur.entry, exitTime = sq?.timestamp ?: t,
                    why = goneWhy(cur.day, t, expiryClose),
                    stopOrderId = null, charges = cur.charges + (sq?.charges?.toDouble() ?: 0.0))
                continue
            }
            if (ltp == null) {
                // 22 Oct (arms audit): no price at all used to hold everything, the 15:10 square-off, Boss's Stop for today and the
                // Hero arm's 15:05 sale included. Those exits need no price: the sale goes at market (the paper book fills it on
                // its own exit price, or it rests unfilled and is tried again next pass). The price exits (stop, target, lock,
                // trail) still wait for a price, as before.
                val due = noPriceExit(armOf(cur.arm).hero, stopped, t.toLocalTime(), cur.day.isBefore(t.toLocalDate())) ?: continue
                // Review: once a minute per position (each try cancels and puts back the resting stop), said once a day.
                val tryKey = "${cur.arm}|${cur.symbol}|${cur.entryTime}"
                val minute = t.withSecond(0).withNano(0)
                if (noPriceTried.put(tryKey, minute) == minute) continue
                if (noPriceSaid.add("$tryKey|$due|${t.toLocalDate()}"))
                    runCatching { Diag.record("orb", "${armOf(cur.arm).label}: ${cur.symbol} has no price now; its $due exit is sent anyway (tried once a minute)") }
                b.positions[i] = if (armOf(cur.arm).hero) heroExit(cur, c, due) else exit(cur, c, due)
                continue
            }
            // The highest price since the entry (F2): every stream tick, else the 1-minute candles' highs - laddered arms and
            // Boss's paper give-back trail only.
            // (15 Nov review: a breakeven rule trade alone reads no candles here - its breakeven follows the best price seen ([heldRange]).)
            // 16 Nov (review): a breakeven rule trade reads them too (the shared [bestSince] / [Paper.highSince]: ticks, else the candles
            // after the entry minute), so a spike between two looks still earns its breakeven or lock, as the research's minute highs.
            val highs0 = if (ladderTarget(cur) != null || cur.trail != null || cur.be != null) bestSince(cur, c, t, mayRead = regular) else Highs(null, null)
            // (Review: a rule trade's highs read in the background ([RuleHighs]), never on the look itself; the look takes the last found.)
            val ruleHi = if (cur.fixed) { if (regular) RuleHighs.ask("paper:${managerId(cur)}", c, cur.entryTime); RuleHighs.peek("paper:${managerId(cur)}") } else null
            val highs = if (ruleHi == null) highs0 else highs0.copy(lock = listOfNotNull(highs0.lock, ruleHi).maxOrNull())
            val (laddered, locked) = ladder(cur, ltp, highs.lock)
            // The trail's give-back from the best price BEFORE this look (a new best counts from the next look, as the ladder).
            val trailHit = LiquidityTrail.exits(cur.entry, cur.peak ?: cur.entry, ltp, cur.lot, cur.trail, cur.trailFrom)
            // 22 Oct: Boss's breakeven at +10% (paper), from the best BEFORE this look as the trail.
            val beHit = cur.be != null &&
                LiquidityBreakeven.exits(cur.entry, cur.peak ?: cur.entry, ltp, ProfitLock.roundTripPerUnit(cur.entry, cur.qty), cur.be)
            val seen = heldRange(trailPeak(laddered, ltp, highs.lock), ltp)
            if (seen !== cur) b.positions[i] = seen
            // The Hero arm's own exits (HeroRules.exitStep: half at 5x, the rest at 20x or 15:05, the -60% stop on a minute's
            // close), checked here every pass; the operator's stop still sells it all at once below.
            if (armOf(cur.arm).hero && !stopped) { b.positions[i] = heroManage(seen, c, ltp, t); continue }
            // The trade manager's word: ORB Fresh on paper in ACT only (R12's proven rule); null for every other position.
            val mgr = managerWord(seen)
            val why = when {
                stopped -> "operator_stop"
                !t.toLocalTime().isBefore(OrbRules.SQUARE_OFF) -> "session_end"
                // Liquidity 15+5 exits on index levels (liquidityExits); its 15% stop rests in the book, and if that order is
                // gone the app sells at the stop level itself. Under a lock variant an earned lock sells as the ORB arms' below
                // (review G2-4: the book fills a resting lock first; a missing or unmoved stop never skips it).
                armOf(cur.arm).liquidity -> LiquidityRules.appSale(stopResting = cur.stopOrderId != null, runThrough = false,
                    atStop = cur.stopTrigger?.let { ltp <= it } == true, locked = locked, lockResting = false)
                    // Boss's paper trail given back to: sold as an earned lock is (its raised stop rests too, below). A stop the
                    // trail raised that the app sells itself is the trail's exit too.
                    ?.let { if (it != "stop") it else if (beStopped(cur)) LiquidityBreakeven.WHY else fixedWhy(cur)
                        ?: if (trailStopped(cur)) LiquidityTrail.WHY else it }
                    ?: LiquidityTrail.WHY.takeIf { trailHit }
                    ?: LiquidityBreakeven.WHY.takeIf { beHit }
                    // 15 Nov (Boss's exception): the stop rule's stop reached while the resting stop is lower (not moved up yet): sold
                    // ([exit] takes the resting stop out first).
                    ?: ruleWhy(cur).takeIf { fixedBelow(cur, ltp) }
                // The lock rests as the paper stop (moved up below), which the paper book fills; this sells at market only
                // when that stop is not there or has not filled (no fresh price in the book's own pass).
                locked -> "profit_lock"
                // The arm's own target (ORB Sweep +80), reached on the price now or on a high since the entry (F2).
                targetHit(cur, ltp, highs.target) -> "target"
                else -> OrbRules.exitReasonFor(armOf(cur.arm), cur.entry, ltp, t, paramsOf(cur)).takeIf { it == "stop" && cur.stopOrderId == null }
                    // Where its own rules hold, the trade manager's early exit, through the arm's own paper exit.
                    ?: mgr?.first?.let { MANAGER_EXIT }
            } ?: run {
                // Held: the profit lock moves the resting stop up to what the best price has earned (F1); then the trade
                // manager's lock, where it acts (ORB Fresh paper), up only.
                val raised = raisePaperStop(seen, ltp)
                b.positions[i] = mgr?.second?.let { raiseManagerStop(raised, it, ltp) } ?: raised
                null
            } ?: continue
            if (why == MANAGER_EXIT) runCatching { Diag.record("orb", "${armOf(cur.arm).label}: exited early by the trade manager - ${mgr?.first}") }
            b.positions[i] = if (armOf(cur.arm).hero) heroExit(seen, c, why) else exit(seen, c, why)
        }
    }

    /**
     * 22 Oct (arms audit): the exits that need no price - Boss's Stop for today ([stopped]), the 15:10 square-off, and the Hero
     * arm's 15:05 sale (or a Hero position carried from an earlier day, [carried]) - or null. Pure.
     */
    internal fun noPriceExit(hero: Boolean, stopped: Boolean, t: LocalTime, carried: Boolean): String? = when {
        stopped -> "operator_stop"
        !t.isBefore(OrbRules.SQUARE_OFF) -> "session_end"
        hero && (carried || HeroRules.exitDue(t)) -> HeroRules.ExitKind.TIME.why
        else -> null
    }

    /**
     * 22 Oct (arms audit): why a position gone from its book without the arm selling it closed: the app's 15:05 expiry square-off
     * ([expiryClose]), else the 15:15 backstop (or a later day), else Boss's own close. Pure.
     */
    internal fun goneWhy(day: LocalDate, now: LocalDateTime, expiryClose: Boolean): String = when {
        expiryClose -> "expiry_square_off"
        day.isBefore(now.toLocalDate()) || !now.toLocalTime().isBefore(LocalTime.of(15, 15)) -> "backstop_square_off"
        else -> "closed_by_you"
    }

    /** 22 Oct (arms audit, review): the minute each position's no-price exit was last tried, and the days it was said (memory only). */
    private val noPriceTried = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()
    private val noPriceSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 22 Oct (arms audit): a Liquidity approval lasts at most this long after its signal bar closed. */
    internal const val APPROVAL_MINUTES = 10L

    /** When a Liquidity approval for the [tf]-minute bar that started at [barStart] lapses: the next bar's close, [APPROVAL_MINUTES] at most. Pure. */
    internal fun approvalExpiry(barStart: LocalDateTime, tf: Int): LocalDateTime =
        barStart.plusMinutes(tf.toLong()).plusMinutes(minOf(tf.toLong(), APPROVAL_MINUTES))

    /** A paper exit refused because nothing is held any more ([Paper.place]'s exit rule): the position was closed elsewhere. */
    private fun alreadyClosed(r: Paper.Result): Boolean = !r.ok && "already closed" in r.message

    /**
     * 22 Nov 2026 (B6 review, WHO CANCELLED A STOP, as the arms' live copies): whether stop [so]'s end came while the market takes orders -
     * at Zerodha's own time of it (its exchange update time) when it gives one, never by how late this look came; else now. Not known:
     * false (Zerodha's: put back).
     */
    private fun cancelInHours(so: Broker.OrderRow): Boolean = runCatching {
        val at = so.updatedAt.takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it, ZERODHA_STAMP) }.getOrNull() }
        if (at != null) Market.testOrdersAnyTime || Market.isOpenAt(at.toLocalDate(), at.hour * 60 + at.minute) else Market.acceptsOrders()
    }.getOrDefault(false)
    private val ZERODHA_STAMP: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * 22 Nov 2026 (B6 review): a square-off ([SquareOffExit]: the 15:05 expiry square-off, the flat-by) is selling Zerodha contract [kite]'s
     * MIS row (these arms' own): its sale working in [orders] (a read of the order book; null: none in hand) or its gap on
     * ([SquareOffExit.inGap], memory: started just before its sale goes).
     */
    private fun squareOffSelling(kite: String, orders: Collection<Broker.OrderRow>?): Boolean = SquareOffExit.inGap("NFO", kite, "MIS") ||
        orders.orEmpty().any { it.symbol == kite && it.exchange == "NFO" && it.product == "MIS" && it.working && it.tag in SquareOffExit.TAGS }

    /**
     * 22 Oct (arms audit): the app's expiry square-off is closing [symbol] (Zerodha's [kite]) now - its contract expires today, it
     * is 15:05 or later and the square-off is on, or one of its orders ("iraalgoexpiry") is already there. The orders as read.
     */
    private fun expiryClosing(symbol: String, kite: String, t: LocalDateTime, orders: Collection<Broker.OrderRow>): Boolean {
        if (orders.any { it.tag == "iraalgoexpiry" && it.symbol == kite }) return true
        val expiry = runCatching { Paper.contractOf(symbol)?.expiry }.getOrNull() ?: return false
        val on = runCatching { AppSettings.load().expirySquareOff }.getOrDefault(true)
        return on && expiry == t.toLocalDate() && t.hour * 60 + t.minute >= com.optionslab.engine.NseHours.EXPIRY_SQUARE_OFF
    }

    /** When each open position's best price was last read in this process (memory only; by [managerId]): [bestSince]'s gap rule. */
    private val lastLook = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()
    /** When each position's gap read was last tried and found nothing (memory only): tried again once a minute. */
    private val gapTried = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()
    /** When this process began watching the positions (its first price check): a position bought before it had a restart. */
    @Volatile private var watchFrom: LocalDateTime? = null

    /** The two best prices one look reads for a position: [target]'s (as before this fix) and the one its lock or trail uses. */
    private data class Highs(val target: Double?, val lock: Double?)

    /**
     * The highest price [c] traded since [p]'s entry ([Paper.highSince]: every stream tick, else the 1-minute candles' highs).
     *
     * 22 Oct (R100 audit C and its review): a streamed contract's stream keeps only its last five minutes of prices, so after a
     * gap - a restart (the position was bought before this process began watching) or a stall longer than 4 minutes
     * ([ProfitLock.needsCandles]) - the 1-minute candles' highs since the entry are read too ([Paper.gapHighSince], at most
     * 3 s, so the arms' check is never held by the feed), and the higher of the two is the lock's or trail's best price
     * ([Highs.lock]). Only on the regular pass ([mayRead]): the stream's fast look never reads it and leaves the gap for the next
     * regular pass. A read that finds no candles is tried again once a minute ([ProfitLock.gapRetryDue]) until one succeeds.
     *
     * The candles' high only ever raises a lock's or trail's best price (so they can only sell sooner); the arm's own target is
     * still judged on [Highs.target], as before: a target that traded while nothing looked is not sold late at another price.
     * Paper positions only ([priceCheck]); a live position keeps the stream's best price as before (TODO.md X7).
     */
    private suspend fun bestSince(p: Position, c: Paper.Contract, t: LocalDateTime, mayRead: Boolean): Highs {
        val plain = runCatching { Paper.highSince(c, p.entryTime) }.getOrNull()
        if (!Paper.streams(c)) return Highs(plain, plain)            // no stream: [Paper.highSince] read the candles already
        val id = managerId(p)
        val from = watchFrom ?: t
        val gap = ProfitLock.needsCandles(lastLook[id]?.let { epochMs(it) }, epochMs(p.entryTime), epochMs(t), epochMs(from))
        if (lastLook.size > 200) lastLook.entries.removeIf { it.value.isBefore(t.minusDays(1)) }
        if (gapTried.size > 200) gapTried.entries.removeIf { it.value.isBefore(t.minusDays(1)) }
        if (!gap) { lastLook[id] = t; return Highs(plain, plain) }
        if (!mayRead || !ProfitLock.gapRetryDue(gapTried[id]?.let { epochMs(it) }, epochMs(t))) return Highs(plain, plain)
        val read = runCatching { Paper.gapHighSince(c, p.entryTime) }.getOrNull() ?: Paper.GapHigh(false, null)
        if (read.read) { lastLook[id] = t; gapTried.remove(id) } else gapTried[id] = t
        return Highs(plain, listOfNotNull(plain, read.high).maxOrNull())
    }

    /** Take the resting stop out of the book first, then sell; if the stop filled meanwhile, that is the exit. */
    private suspend fun exit(p: Position, c: Paper.Contract, why: String): Position {
        runCatching { OrderTiming.decided() }
        p.stopOrderId?.let { id ->
            Paper.cancel(id, "exit:$why")
            val so = Paper.state.orders.firstOrNull { it.orderId == id }
            if (so?.status == "complete") return p.copy(exit = so.averagePrice?.toDouble(), exitTime = so.updateTimestamp,
                why = stopWhy(p), charges = p.charges + chargesOf(id))
        }
        // 22 Oct (arms audit): an exit only ever reduces what the paper book holds. Already closed (the 15:05 expiry square-off or
        // Boss got there first) it is refused - never a short - and no stop goes back on a flat book: the next look books the
        // position from the paper book's own close ([priceCheck]'s "gone" path).
        val sell = Paper.place(c, "SELL", p.qty / c.lotSize.coerceAtLeast(1), "MARKET", "MIS", null, null, exit = true)
        if (alreadyClosed(sell)) return p.copy(stopOrderId = null)
        // Nothing sold (no price): the resting stop goes back in the book so the position is never left without one; the
        // sale is tried again on the next pass.
        val fill = filledOrCancelled(sell) ?: return p.stopOrderId?.let { restop(p, c) } ?: p.copy(stopOrderId = null)
        sell.orderId?.let { Strategies.tagOwner("paper:$it", "${ownerOf(armOf(p.arm))} · $why") }
        Notifier.orderFilled(app, "SELL", fill.quantity, fill.symbol, fill.price, "Paper", "${ownerOf(armOf(p.arm))} · exit", sell.orderId)
        return p.copy(stopOrderId = null, exit = fill.price, exitTime = now(), why = why, charges = p.charges + chargesOf(sell.orderId))
    }

    /**
     * The resting stop placed again after an exit sold nothing (F3): at the profit lock its best price has earned when that
     * is above its own trigger (never back at the -40 once a rung is earned), else at its trigger. The position without a
     * stop order when it cannot be placed (the app then watches it, and the sale is tried again on the next pass).
     */
    private suspend fun restop(p: Position, c: Paper.Contract): Position {
        val trigger = lockOrStop(p) ?: return p.copy(stopOrderId = null)
        val r = runCatching { Paper.place(c, "SELL", p.qty / c.lotSize.coerceAtLeast(1), "SL-M", "MIS", null, trigger, exit = true) }.getOrNull()
        val id = r?.takeIf { it.ok }?.orderId?.also { Strategies.tagOwner("paper:$it", "${armOf(p.arm).label} · stop") }
        return if (id == null) p.copy(stopOrderId = null) else p.copy(stopOrderId = id, stopTrigger = trigger)
    }

    /** Where a laddered position's stop belongs now: the higher of its trigger and the lock its best price earned (F3). */
    private fun lockOrStop(p: Position, tick: Double = OrbRules.TICK): Double? = listOfNotNull(LiquidityTrail.restingStop(p.entry,
        ProfitLock.restingStop(p.entry, p.stopTrigger, ladderTarget(p), p.peak ?: p.entry, ProfitLock.roundTripPerUnit(p.entry, p.qty), tick),
        p.peak, p.lot, p.trail, tick, p.trailFrom),
        // 22 Oct: Boss's paper breakeven, once earned (never back at the -15% after +10%).
        LiquidityBreakeven.level(p.entry, p.peak, ProfitLock.roundTripPerUnit(p.entry, p.qty), p.be, tick),
        // 15 Nov (Boss's exception): the breakeven rule's stop it entered with (the higher wins: never looser than its own).
        fixedStopOf(p, tick)).maxOrNull()

    /** The profit-lock ladder's target for a laddered position ([ProfitLock.targetOf] its arm), or null for none. */
    private fun ladderTarget(p: Position): Double? = if (!p.ladder) null else runCatching {
        val arm = armOf(p.arm)
        // Liquidity 15+5 is laddered only under a lock variant (its reference a share of the premium paid); every other arm on
        // its own target (S1: the variant's when one is in force for the position's day and account).
        if (arm.liquidity) liqParams(p).lockTarget(p.entry) else ProfitLock.targetOf(arm)?.let { paramsOf(p).targetPoints }
    }.getOrNull()

    /** True when the position's resting stop is a profit lock (moved up to the price paid or higher), not its own stop. */
    private fun lockedStop(p: Position): Boolean = p.ladder && (p.stopTrigger ?: Double.NEGATIVE_INFINITY) >= p.entry - 1e-9

    /** The arm's own +target (ORB Sweep +80, F5) reached on the price now or on the highest price since the entry (F2). */
    private fun targetHit(p: Position, ltp: Double, high: Double?): Boolean {
        val arm = runCatching { armOf(p.arm) }.getOrNull() ?: return false
        if (arm.liquidity || arm.hero) return false
        return maxOf(ltp, high ?: ltp) >= p.entry + paramsOf(p).targetPoints - 1e-9
    }

    /**
     * F1: the profit lock MOVES THE RESTING STOP. A laddered paper position's SL-M is modified up to the lock its best price
     * has earned ([ProfitLock.raise]: never down, never to or above the price now), so the paper book fills it where the
     * price reaches it. A refused modify leaves the stop where it was, is said (throttled), and is asked again next look.
     */
    private fun raisePaperStop(p: Position, ltp: Double): Position {
        val target = ladderTarget(p)
        if (target == null && p.trail == null && p.be == null && !p.fixed) return p
        val id = p.stopOrderId ?: return p
        // The higher of the ladder's lock, Boss's paper give-back trail ([LiquidityTrail.raise]) and, 22 Oct, Boss's paper breakeven
        // at +10% ([LiquidityBreakeven.raise]): up only, below the price.
        // Each rule's level with its name, so a refused move names the rule that asked for it (22 Oct review).
        val asks = listOfNotNull(
            target?.let { ProfitLock.raise(p.entry, it, p.peak ?: p.entry, p.stopTrigger, ltp, ProfitLock.roundTripPerUnit(p.entry, p.qty)) }?.let { it to "profit lock" },
            LiquidityTrail.raise(p.entry, p.peak, p.lot, p.trail, p.stopTrigger, ltp, activateRs = p.trailFrom)?.let { it to "₹500 trail" },
            p.be?.let { LiquidityBreakeven.raise(p.entry, p.peak, ProfitLock.roundTripPerUnit(p.entry, p.qty), it, p.stopTrigger, ltp) }
                ?.let { it to "breakeven at +10%" },
            // 15 Nov (Boss's exception): the rule's stop - breakeven once the option is 10% up - up only,
            // under the price now.
            fixedStopOf(p)?.takeIf { it > (p.stopTrigger ?: Double.NEGATIVE_INFINITY) + 1e-9 && it < ltp - 1e-9 }?.let { it to "breakeven" },
        )
        val (to, rule) = asks.maxByOrNull { it.first } ?: return p
        val r = runCatching { Paper.modify(id, null, null, to) }
        if (r.getOrNull()?.ok == true) {
            // 18 Oct: a paper-only arm's live trade moves its Zerodha stop to the same lock ([ArmLive]; memory only).
            if (runCatching { armOf(p.arm).paperOnly }.getOrDefault(false)) ArmLive.level(mirrorRef(p), to)
            return p.copy(stopTrigger = to)
        }
        val why = r.getOrNull()?.message ?: r.exceptionOrNull()?.javaClass?.simpleName ?: "an error"
        com.optionslab.app.work.Alerts.post("${armOf(p.arm).label}: the $rule could not move the paper stop up to " +
            "%.2f (%s). The stop stays at %s; it is tried again on the next check.".format(Locale.ENGLISH, to, why,
                p.stopTrigger?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none"),
            com.optionslab.app.work.Alerts.Kind.ERROR, "ORB", throttle = true)
        return p
    }

    /**
     * Liquidity 15+5: the best and worst premium seen while held ([Position.peak], [Position.low]) at [ltp], for Jarvis's trade
     * lesson - under a lock variant (laddered) the worst only, its best being the ladder's own ([ladder]; review G2-8). Every
     * other position comes back as it is. Never changes an exit or an order.
     */
    private fun heldRange(p: Position, ltp: Double): Position {
        if (!runCatching { armOf(p.arm).liquidity }.getOrDefault(false)) return p
        val lo = minOf(p.low ?: p.entry, ltp)
        if (p.ladder) return if (lo == p.low) p else p.copy(low = lo)
        val hi = maxOf(p.peak ?: p.entry, ltp)
        return if (hi == p.peak && lo == p.low) p else p.copy(peak = hi, low = lo)
    }

    /**
     * The profit-lock ladder ([ProfitLock], its breakeven after charges) for a laddered position at [ltp]: the position with
     * its best price updated from [ltp] and [high] (the highest price traded since the entry: every stream tick, else the
     * 1-minute candles' highs, F2), and whether [ltp] gave back to the lock its best BEFORE this look had earned (a rung
     * counts from the next price). The lock itself rests as the stop ([raisePaperStop], live [raiseLiveStop]); this is the
     * app's own check behind it.
     */
    private fun ladder(p: Position, ltp: Double, high: Double? = null): Pair<Position, Boolean> {
        val target = ladderTarget(p) ?: return p to false
        val before = p.peak ?: p.entry
        // The breakeven rung sits at the price paid plus the round trip's charges (Boss's 06 Oct fix): a profit-lock exit is
        // never a certain small loss after charges.
        val locked = ProfitLock.exits(p.entry, target, before, ltp, ProfitLock.roundTripPerUnit(p.entry, p.qty))
        val peak = ProfitLock.nextPeak(before, ltp, high)
        return (if (peak > before) p.copy(peak = peak) else p) to locked
    }

    /**
     * Boss's paper give-back trail ([LiquidityTrail]): the position with its best price updated from [ltp] and [high] (every
     * stream tick, else the 1-minute candles' highs), as the ladder's ([ladder]). Every other position comes back as it is.
     */
    private fun trailPeak(p: Position, ltp: Double, high: Double?): Position {
        if (p.trail == null && p.be == null && !p.fixed) return p           // 22 Oct: Boss's paper breakeven reads the best too; 15 Nov: the stop rule
        val before = p.peak ?: p.entry
        val peak = ProfitLock.nextPeak(before, ltp, high)
        return if (peak > before) p.copy(peak = peak) else p
    }

    // ---- the expiry-day Hero arm (paper only, not proven) -------------------------------

    /** The marker in the day's decided set once the Hero arm has stood down for the day on stale data. */
    private const val HERO_STOOD_DOWN = "hero_stood_down"
    /** The Hero arm's own notices (fired, disarmed); the arms' approvals use 6960 and up. */
    private const val HERO_NOTICE = 6990
    /** Liquidity BANKNIFTY's live stop rule reached (once). */
    private const val LIQ_STOP_NOTICE = 6988

    /**
     * TEST ONLY: a feed key's 1-minute bars (the NIFTY index, NIFTY options) as the feed would return them at a given
     * moment. Null in the app, always: [heroMinutes] then reads [Net.intraday]. Its setter throws unless BuildConfig.DEBUG
     * (as [testNow]); no app code sets it, and the tests clear it with the other test feeds.
     */
    @Volatile internal var testHeroBars: ((String, LocalDateTime) -> List<Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v; heroStopBars = null }

    /** The Hero arm's feeds read for this pass before the lock ([heroPrefetch]): feed key -> its bars. Cleared after the pass. */
    private val heroFed = java.util.concurrent.ConcurrentHashMap<String, List<Upstox.Bar>>()

    /** A feed key's bars as the feed returns them now (the test feed in tests). */
    private suspend fun heroRead(key: String, t: LocalDateTime): List<Upstox.Bar> = testHeroBars?.invoke(key, t) ?: Net.intraday(key)

    /**
     * Reads, outside the arms' lock, what the Hero arm's decision at [t] needs every pass: the NIFTY index and both legs of
     * each minute's ATM strike since 12:00 - only when it is armed, flat, on an expiry day in its window. The decision
     * itself ([heroCycle]) then runs under the lock on these bars, exactly as before; anything not read here (the chain
     * on the signal's side, a read that failed) is read there as it always was. Reads only.
     */
    private suspend fun heroPrefetch(t: LocalDateTime) {
        heroFed.clear()
        val src = HeroRules.ARM.source
        val day = t.toLocalDate()
        val at = t.toLocalTime().withSecond(0).withNano(0)
        // An open Hero position: its option's minutes for the -60% stop ([heroManage]), once a minute, read here outside the lock.
        val minute = t.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        val held = lock.withLock { book().positions.lastOrNull { it.arm == src && it.open && !it.live }?.symbol }
        held?.let { Paper.contractOf(it) }?.takeIf { c -> heroStopBars?.let { it.first == c.feedKey && it.second == minute } != true }?.let { c ->
            runCatching { heroRead(c.feedKey, t) }.getOrNull()?.let { heroFed[c.feedKey] = it }
        }
        if (at.isBefore(HeroRules.FIRST) || at.isAfter(HeroRules.LAST)) return
        if (Strategies.stoppedToday()) return
        // Only when [heroCycle] would read them this pass: armed, flat, not stood down, not done, this minute not yet decided.
        val due = lock.withLock {
            val b = book()
            val spent = b.decided["$src|$day"].orEmpty()
            b.armed[src] == true && b.positions.none { it.arm == src && it.open } && HERO_STOOD_DOWN !in spent && "hero@$at" !in spent &&
                HeroRules.mayEnter(b.positions.count { it.arm == src && it.day == day })
        }
        if (!due) return
        val master = runCatching { Market.contracts() }.getOrNull()?.filter { it.underlying == HeroRules.UNDERLYING }
        if (master.isNullOrEmpty() || !HeroRules.isExpiryDay(day, master.map { it.expiry })) return
        val today = master.filter { it.expiry == day }
        val spotKey = Upstox.INDEX_KEYS.getValue(HeroRules.UNDERLYING)
        val spotRaw = runCatching { heroRead(spotKey, t) }.getOrNull() ?: return
        heroFed[spotKey] = spotRaw
        val strikes = minutesOf(spotRaw, t).filterKeys { !it.isBefore(HeroRules.LOW_FROM) && !it.isAfter(at) }
            .values.map { HeroRules.atm(it.close).toDouble() }.distinct()
        for (k in strikes) for (r in listOf(Right.CE, Right.PE)) {
            val c = today.firstOrNull { kotlin.math.abs(it.strike - k) < 1e-6 && it.right == r } ?: continue
            if (heroFed.containsKey(c.instrumentKey)) continue
            runCatching { heroRead(c.instrumentKey, t) }.getOrNull()?.let { heroFed[c.instrumentKey] = it }
        }
    }

    /** [raw] keyed by the minute each bar CLOSES (a bar labelled 13:29 closes at 13:30), today's and closed by [t]. */
    private fun minutesOf(raw: List<Upstox.Bar>, t: LocalDateTime): Map<LocalTime, Upstox.Bar> {
        val day = t.toLocalDate()
        return raw.filter { it.istDate == day && it.istMinute < 15 * 60 + 30 }
            .associateBy { LocalTime.of(it.istMinute / 60, it.istMinute % 60).plusMinutes(1) }
            .filterKeys { !day.atTime(it).isAfter(t) }
    }

    /** A feed key's bars today keyed by the minute each one CLOSES (a bar labelled 13:29 closes at 13:30), closed by [t]. */
    private suspend fun heroMinutes(key: String, t: LocalDateTime): Map<LocalTime, Upstox.Bar> {
        // (Read before the lock this pass when it could be - [heroPrefetch]; else here, as it always was.)
        heroFed[key]?.let { return minutesOf(it, t) }
        if (testHeroBars != null) return minutesOf(heroRead(key, t), t)
        // 19 Nov 2026 (audit batch 2: no long network read under the arms' lock): a read here is quick and all of them together take at most
        // [HERO_LOCK_READ_MS] in any half minute (the open positions' checks wait on this lock); one not had in time is no bars.
        val nowMs = System.currentTimeMillis()
        val until = heroReadsUntil.takeIf { nowMs - it < 30_000L } ?: (nowMs + HERO_LOCK_READ_MS).also { heroReadsUntil = it }
        val left = until - nowMs
        if (left <= 0) throw java.io.IOException("$key: no time left to read it under the arms' lock now")
        return minutesOf(kotlinx.coroutines.withTimeoutOrNull(left) { Net.intradayQuick(key) }
            ?: throw java.io.IOException("$key: not read within ${HERO_LOCK_READ_MS / 1000} s"), t)
    }

    /** 19 Nov 2026 (audit batch 2): until when (epoch ms) the Hero arm's reads under the arms' lock may run ([heroMinutes]). */
    @Volatile private var heroReadsUntil = 0L
    internal const val HERO_LOCK_READ_MS = 6_000L

    /**
     * The Hero arm's decision on the last closed minute ([HeroRules]): NIFTY, today's expiry only, 13:30-14:45, one
     * entry a day, a LIMIT BUY on the PAPER account only (there is no live path: it never calls [enterLive]).
     */
    private suspend fun heroCycle(b: Book, t: LocalDateTime): String {
        val src = HeroRules.ARM.source
        // 15 Oct (Boss's cleanup): hidden, the expiry Hero takes no new ticket (memory only; its open one keeps its exits).
        if (HiddenArms.blocks(ArmsCleanup.HERO)) return HIDDEN_NO_ENTRY
        val day = t.toLocalDate()
        val spent = b.decided.getOrPut("$src|$day") { HashSet() }
        if (HERO_STOOD_DOWN in spent) return HERO_STOOD_DOWN
        if (!HeroRules.mayEnter(b.positions.count { it.arm == src && it.day == day })) return "hero_done_for_today"
        // Today's NIFTY options from today's instrument master; not loaded (or not fetched): no trade, never a guess.
        // (19 Nov 2026, audit batch 2: from memory or the phone only under the arms' lock - [heroPrefetch] loads it off the lock.)
        val master = listedNow()?.filter { it.underlying == HeroRules.UNDERLYING }
        if (master.isNullOrEmpty()) return "hero_no_master"
        if (!HeroRules.isExpiryDay(day, master.map { it.expiry })) return "hero_not_expiry_day"
        val at = t.toLocalTime().withSecond(0).withNano(0)                 // the last minute that has closed
        if (at.isBefore(HeroRules.FIRST)) return "hero_waiting_for_window"
        if (at.isAfter(HeroRules.LAST)) return "hero_window_closed"
        if ("hero@$at" in spent) return "no_decision_bar"
        val today = master.filter { it.expiry == day }
        // NIFTY's minutes as read: the closes decide (as before); the R17 shadow note reads their highs and lows.
        val spotBars = heroMinutes(Upstox.INDEX_KEYS.getValue(HeroRules.UNDERLYING), t)
        val spot = spotBars.mapValues { it.value.close }
        val legs = HashMap<Pair<Double, Right>, Map<LocalTime, HeroRules.Leg>>()
        suspend fun legsOf(strike: Double, right: Right): Map<LocalTime, HeroRules.Leg> {
            legs[strike to right]?.let { return it }
            val c = today.firstOrNull { kotlin.math.abs(it.strike - strike) < 1e-6 && it.right == right }
            val m: Map<LocalTime, HeroRules.Leg> = if (c == null) emptyMap() else runCatching { heroMinutes(c.instrumentKey, t) }.getOrDefault(emptyMap())
                .mapValues { HeroRules.Leg(it.value.close, it.value.volume) }
            legs[strike to right] = m
            return m
        }
        // Each minute's straddle at that minute's ATM strike, from 12:00; a stale leg leaves the minute out.
        val straddle = HashMap<LocalTime, Double>()
        for ((u, x) in spot) {
            if (u.isBefore(HeroRules.LOW_FROM) || u.isAfter(at)) continue
            val k = HeroRules.atm(x).toDouble()
            val ce = HeroRules.legPrice(legsOf(k, Right.CE), u) ?: continue
            val pe = HeroRules.legPrice(legsOf(k, Right.PE), u) ?: continue
            straddle[u] = ce + pe
        }
        val scan = HeroRules.scan(spot, straddle, at)
        // Each minute's verdict (with what it read: the straddle, its legs and low, the move; a signal's pick, limit and lots)
        // is kept for Jarvis's words ([noteHero]): returned exactly as before; nothing reads it back to decide.
        fun kept(verdict: String, pick: HeroRules.Candidate? = null, limit: Double? = null, lots: Int? = null): String {
            runCatching { noteHero(t, verdict, at, scan, spot, straddle, legs, pick, limit, lots) }
            return verdict
        }
        if (scan.standDown) {
            spent += HERO_STOOD_DOWN
            runCatching { Diag.record("orb", "${HeroRules.ARM.label}: stood down for today: more than ${HeroRules.MAX_SKIPPED} minutes in a row of stale NIFTY or straddle data") }
            return kept(HERO_STOOD_DOWN)
        }
        // A stale minute is looked at again on the next pass (the data may still arrive); the scan counts it if it never does.
        if (scan.why == "skipped_stale_bar") return kept("hero_skipped_stale_bar")
        spent += "hero@$at"
        if (scan.signal == 0) return kept("hero_" + scan.why)
        val right = if (scan.signal > 0) Right.CE else Right.PE
        val s0 = scan.spot ?: return kept("hero_skipped_stale_bar")
        // The chain on the signal side within 3% of spot, nearest the money first.
        val side = today.filter { it.right == right && kotlin.math.abs(it.strike - s0) <= s0 * HeroRules.SPOT_BAND }
            .filter { if (scan.signal > 0) it.strike > s0 else it.strike < s0 }.sortedBy { kotlin.math.abs(it.strike - s0) }
        val chain = ArrayList<HeroRules.Candidate>()
        for (o in side) {
            val bars = legsOf(o.strike, right).filterKeys { !it.isAfter(at) }
            val ltp = bars.maxByOrNull { it.key }?.value?.close ?: 0.0
            val traded = bars.any { (u, l) -> l.volume > 0 && !u.isBefore(at.minusMinutes(HeroRules.TRADED_BARS - 1)) }
            // The paper feed has no depth: the last price stands in for the ask (as the paper fill does).
            chain += HeroRules.Candidate(o.strike, ltp, ltp.takeIf { it > 0 }, traded)
        }
        val pick = HeroRules.pick(scan.signal, s0, chain) ?: return kept("hero_no_strike")
        val c = contractNow(HeroRules.UNDERLYING, day, pick.strike, right) ?: return kept("no_contract", pick)
        // The lot size comes from the master, never a constant; unknown: no trade.
        if (c.lotSize <= 0) return kept("hero_lot_unknown", pick)
        // The kill switch and Stop for today win (paper too, for this arm: plans only lower risk).
        if (runCatching { AppSettings.load().guardKill }.getOrDefault(true)) return kept("refused: the kill switch is on", pick)
        if (Strategies.stoppedToday()) return kept("stopped_for_today", pick)
        val q = runCatching { Paper.quote(c) }.getOrNull()
        val ask = q?.ask?.takeIf { it > 0 } ?: q?.ltp?.takeIf { it > 0 } ?: pick.ltp
        val limit = HeroRules.limitPrice(ask, pick.ltp)
        // The danger meter's half size (TODO I4): only when Boss switched it on, on a careful day; paper only, never more lots.
        val lots = DangerToday.paperLots(HeroRules.lots(limit, c.lotSize), day)
        if (lots <= 0) return kept("hero_zero_lots", pick, limit, lots)
        exposureRefusal(b, c, HeroRules.ARM)?.let { return kept(it, pick, limit, lots) }
        // The order flow at this minute (paper only, as the arm): logged; under CONFIRM skipped unless the flow agrees.
        val flow = FlowGate.gate(HeroRules.ARM.source, HeroRules.UNDERLYING, scan.signal, false, day.atTime(at).toString(), c.right.name, contract = c)
        if (flow.skip) return kept(flow.pausedWhy ?: FlowGate.SKIPPED, pick, limit, lots)
        runCatching { LiveRecorder.signal(HeroRules.ARM.label, c.symbol, c.underlying, c.right.name, limit, paper = true) }
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", lots, limit), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return kept("guard_refused: " + refusals.joinToString(" "), pick, limit, lots)
        // 22 Oct: the guard's verdict stamped with this order's fingerprint, its -60% stop level with it ([OrderVerdicts]).
        val verdict = OrderVerdicts.stamp(OrderVerdicts.paperFields(c, "BUY", lots, "LIMIT", "MIS", limit, null, Market.today(), src, stop = HeroRules.stopLevel(limit)), refusals, "paper guard")
        // Never a market order on these strikes: a LIMIT BUY on the PAPER account. Not filled at once, it is cancelled
        // (nothing held), and the arm may decide again on a later bar until 14:45.
        val buy = Paper.place(c, "BUY", lots, "LIMIT", "MIS", limit, null, known = q, auto = HeroRules.OWNER, verdict = verdict, stop = HeroRules.stopLevel(limit))
        // 22 Oct (arms audit): its name on the order at once, filled or not (a cancelled limit is never taken for Boss's own).
        buy.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${HeroRules.OWNER} · entry") } }
        val fill = filledOrCancelled(buy) ?: return kept("order_refused: " +
            (if (buy.ok) "the limit %.2f did not fill; the order was cancelled".format(Locale.ENGLISH, limit) else buy.message), pick, limit, lots)
        buy.orderId?.let { Strategies.tagOwner("paper:$it", "${HeroRules.OWNER} · entry") }
        FlowGate.taken(flow, buy.orderId)
        Notifier.orderFilled(app, "BUY", fill.quantity, fill.symbol, fill.price, "Paper", "${HeroRules.OWNER} · entry", buy.orderId)
        val text = ("NIFTY %s %.2f%% in 15 min, straddle %.0f%% above its low: bought %d %s @ %.2f on PAPER (limit %.2f). " +
            "Sells half at %.2f (5x), the rest at %.2f (20x) or 15:05; stop: a minute closing at or below %.2f (-60%%). %s.")
            .format(Locale.ENGLISH, if (scan.signal > 0) "up" else "down", (scan.mom ?: 0.0) * 100, (scan.stExp ?: 0.0) * 100,
                fill.quantity, fill.symbol, fill.price, limit, HeroRules.target1(fill.price), HeroRules.target2(fill.price),
                HeroRules.stopLevel(fill.price), HeroRules.NOT_PROVEN)
        runCatching { Notifier.post(app, HERO_NOTICE, Notifier.BUY, "${HeroRules.ARM.label} fired", text, "trade") }
        runCatching { Diag.record("orb", "${HeroRules.ARM.label}: $text") }
        // The book at the signal minute (bid, ask and their quantities from Zerodha's stream; else the last price alone).
        val pos = Position(src, c.symbol, c.right.name, fill.quantity, fill.price, now(), day.atTime(at), buy.orderId, null, null,
            charges = chargesOf(buy.orderId), seen = listOf(heroSeen("signal", q, pick.ltp)))
        b.positions += pos
        marks[c.symbol] = fill.price
        // 22 Oct: the time machine's reality check on NIFTY's 1-minute bars (shadow only; background): the -60% stop and the 5x target as premium.
        runCatching { TimeMachineHost.afterEntry(TimeMachineHost.Entry(src, HeroRules.ARM.label, mirrorRef(pos), HeroRules.UNDERLYING, scan.signal, day.atTime(at),
            fill.price - HeroRules.stopLevel(fill.price), HeroRules.target1(fill.price) - fill.price, premium = true, exitAt = HeroRules.EXIT_AT, barMinutes = 1)) }
        // 18 Oct: its live path ([ArmLive]): the same option and lots, a LIMIT at the same limit. Its -60% stop is judged on a minute's
        // close on paper, so the paper exit sells the live trade; only a labelled -80% disaster stop rests at Zerodha (review D2).
        mirror(pos, c, HeroRules.stopLevel(fill.price), limit)
        runCatching { PreTradeShadow.hero(HeroRules.ARM, spotBars, c, fill.price, fill.quantity, day, at) }  // R17 shadow (memory only)
        return kept("entered", pick, limit, lots)
    }

    /**
     * The Hero arm's exit of everything it still holds (15:05, the -60% stop, the operator's stop): a LIMIT SELL at the bid
     * less a tick; not filled, the usual exit (a market sell, retried). The book at that moment is logged with the trade.
     */
    private suspend fun heroExit(p0: Position, c: Paper.Contract, why: String): Position {
        runCatching { OrderTiming.decided() }
        val q = runCatching { Paper.quote(c) }.getOrNull()
        val p = p0.copy(seen = p0.seen + heroSeen(why, q, marks[p0.symbol]))
        // A stale feed at the exit: the last known price stands in for the bid.
        val bid = q?.bid?.takeIf { it > 0 } ?: q?.ltp?.takeIf { it > 0 } ?: marks[p.symbol]
        if (bid != null) {
            // 22 Oct (arms audit): an exit, reduce-only - the expiry square-off runs in the same minute as this 15:05 sale.
            val sell = Paper.place(c, "SELL", p.qty / c.lotSize.coerceAtLeast(1), "LIMIT", "MIS", HeroRules.exitLimit(bid), null, known = q, exit = true)
            if (alreadyClosed(sell)) return p0
            val fill = filledOrCancelled(sell)
            if (fill != null) {
                sell.orderId?.let { Strategies.tagOwner("paper:$it", "${HeroRules.OWNER} · $why") }
                Notifier.orderFilled(app, "SELL", fill.quantity, fill.symbol, fill.price, "Paper", "${HeroRules.OWNER} · exit", sell.orderId)
                return p.copy(exit = fill.price, exitTime = now(), why = why, charges = p.charges + chargesOf(sell.orderId))
            }
        }
        // (Not sold at all: the next pass tries again with a fresh look, so this one is not kept - except the stop [exit] put back
        // in the book, whose id must be kept or that stop would rest on with no record and could sell after the position is gone;
        // 25 Oct, merge review.)
        return exit(p, c, why).let { if (it.open) p0.copy(stopOrderId = it.stopOrderId, stopTrigger = it.stopTrigger) else it }
    }

    /** One look at the option's book for the Hero trade's log ([Position.seen]); without depth, the last price alone. */
    private fun heroSeen(what: String, q: com.optionslab.engine.sandbox.Quote?, fallback: Double?): HeroRules.Seen =
        HeroRules.Seen(what, now().toLocalTime().withNano(0), q?.ltp?.takeIf { it > 0 } ?: fallback, q?.bid?.takeIf { it > 0 },
            q?.ask?.takeIf { it > 0 }, q?.bidQty?.takeIf { it > 0 }, q?.askQty?.takeIf { it > 0 })

    /** 19 Nov 2026 (audit batch 2, F3): the most a background read of the Hero position's option minutes may take. */
    internal const val HERO_STOP_READ_MS = 20_000L

    /** TEST ONLY (20 Nov 2026): [HERO_STOP_READ_MS] shortened (null in the app). Debug builds only. */
    @Volatile internal var testHeroReadMs: Long? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }

    /** TEST ONLY (20 Nov 2026): whether a background read of the Hero position's option minutes runs now ([heroStopSoon]). */
    internal fun heroStopReadingForTest(): Boolean = heroStopReading.get()

    /** 19 Nov 2026 (audit batch 2, F3): one background read of the Hero position's option minutes at a time ([heroStopSoon]). */
    private val heroStopReading = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 19 Nov 2026 (audit batch 2, F3): [key]'s minutes of [t]'s minute read OFF the arms' lock into [heroStopBars] for the next look. */
    private fun heroStopSoon(key: String, t: LocalDateTime) {
        if (!heroStopReading.compareAndSet(false, true)) return
        val minute = t.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        offLock.launch {
            try {
                // (A deadline: one hung read never holds back every later one. A test feed is read so that the deadline ends it too.)
                val raw = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(testHeroReadMs ?: HERO_STOP_READ_MS) {
                        val f = testHeroBars
                        if (f != null) kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { f(key, t) } else Net.intraday(key)
                    }
                }.getOrNull()
                if (raw != null && heroStopBars?.second?.isAfter(minute) != true) heroStopBars = Triple(key, minute, minutesOf(raw, t))
            } finally { heroStopReading.set(false) }
        }
    }

    /** The option's 1-minute bars read for the Hero arm's stop this minute (feed key, minute, bars): one read a minute. */
    @Volatile private var heroStopBars: Triple<String, LocalDateTime, Map<LocalTime, Upstox.Bar>>? = null

    /**
     * The Hero arm's exits for its open paper position at [ltp] ([HeroRules.exitStep]): half at 5x and the rest at 20x
     * (LIMIT SELLs at the target, sent once the price trades a tick through it), 15:05, and the -60% stop on a minute's
     * close (both at the bid less a tick, [heroExit]). Up to two steps a pass (a jump through 5x and 20x at once).
     */
    private suspend fun heroManage(p0: Position, c: Paper.Contract, ltp: Double, t: LocalDateTime): Position {
        var p = p0
        val lot = c.lotSize.coerceAtLeast(1)
        val minute = t.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        // 19 Nov 2026 (audit batch 2, second review F3): from memory only - this minute's bars read before the lock by the pass
        // ([heroPrefetch]) or in the background ([heroStopSoon]); never a read here, under the arms' lock on the stop path. Not in memory
        // yet: read off the lock for the next look, and this look judges the targets and 15:05 on the price as usual.
        val bars = heroStopBars?.takeIf { it.first == c.feedKey && it.second == minute }?.third
            ?: heroFed[c.feedKey]?.let { minutesOf(it, t) }?.also { heroStopBars = Triple(c.feedKey, minute, it) }
            // (Meanwhile the minutes it last had of this option: every minute closed by then is still judged.)
            ?: run { heroStopSoon(c.feedKey, t); heroStopBars?.takeIf { it.first == c.feedKey }?.third ?: emptyMap() }
        val closes = if (p.day == t.toLocalDate()) HeroRules.stopMinutes(p.entryTime.toLocalTime(), bars.keys).mapNotNull { bars[it]?.close }
            else emptyList()
        repeat(2) {
            val step = HeroRules.exitStep(p.entry, p.qty / lot, p.sold > 0, ltp, closes, t.toLocalTime(), p.day.isBefore(t.toLocalDate()))
                ?: return p
            val limit = step.limit ?: return heroExit(p, c, step.kind.why)
            p = heroTarget(p, c, step, limit) ?: return p
            if (!p.open) return p
        }
        return p
    }

    /**
     * A target of the Hero arm: a LIMIT SELL of [step]'s lots at [limit] (never below it). Filled: the position with that
     * part sold (or closed, when it was all of it); not filled at once (the price fell back): cancelled, null, and the
     * next pass looks again.
     */
    private suspend fun heroTarget(p: Position, c: Paper.Contract, step: HeroRules.ExitStep, limit: Double): Position? {
        val q = runCatching { Paper.quote(c) }.getOrNull()
        val sell = Paper.place(c, "SELL", step.lots, "LIMIT", "MIS", limit, null, known = q, exit = true)
        val fill = filledOrCancelled(sell) ?: return null
        val why = step.kind.why
        sell.orderId?.let { Strategies.tagOwner("paper:$it", "${HeroRules.OWNER} · $why") }
        Notifier.orderFilled(app, "SELL", fill.quantity, fill.symbol, fill.price, "Paper", "${HeroRules.OWNER} · exit", sell.orderId)
        val seen = p.seen + heroSeen(why, q, fill.price)
        val charges = p.charges + chargesOf(sell.orderId)
        if (fill.quantity >= p.qty) return p.copy(exit = fill.price, exitTime = now(), why = why, charges = charges, seen = seen)
        runCatching { Diag.record("orb", "${HeroRules.ARM.label}: sold %d %s @ %.2f at 5x; %d held to 20x, 15:05 or the stop. %s."
            .format(Locale.ENGLISH, fill.quantity, fill.symbol, fill.price, p.qty - fill.quantity, HeroRules.NOT_PROVEN)) }
        return p.copy(qty = p.qty - fill.quantity, sold = p.sold + fill.quantity, soldAt = fill.price, soldTime = now(), charges = charges, seen = seen)
    }

    /**
     * The Hero arm disarms itself after [HeroRules.MAX_LOSING_DAYS] losing firing days in a row or [HeroRules.MAX_LOSS]
     * lost since it was last armed; it then needs a manual re-arm.
     */
    private fun heroKillCheck(b: Book) {
        val src = HeroRules.ARM.source
        if (b.armed[src] != true || ALL_ARMS.none { it.hero }) return
        val since = b.since[src]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val days = b.positions.filter { it.arm == src && !it.open && (since == null || !it.entryTime.isBefore(since)) }
            .groupBy { it.day }.toSortedMap().values.map { ps -> ps.sumOf { (it.grossPnl ?: 0.0) - it.charges } }
        val why = HeroRules.killReason(days) ?: return
        b.armed[src] = false; b.liveOk[src] = false; b.pending.remove(src)
        b.status[src] = "hero_disarmed: $why"
        runCatching { Notifier.post(app, HERO_NOTICE + 1, Notifier.RISK, "${HeroRules.ARM.label} disarmed itself",
            "$why. It stays off until you arm it again. ${HeroRules.NOT_PROVEN}.", "trade") }
        runCatching { Diag.record("orb", "${HeroRules.ARM.label}: disarmed itself: $why") }
    }

    private fun describeHero(s: String): String = when {
        s == HERO_STOOD_DOWN -> "Stood down for today: more than ${HeroRules.MAX_SKIPPED} minutes in a row of stale NIFTY or straddle data."
        s == "hero_done_for_today" -> "Done for today: one entry a day."
        s == "hero_no_master" -> "Today's NIFTY instrument master is not loaded: no trade."
        s == "hero_not_expiry_day" -> "Not a NIFTY expiry day (from the instrument master): it trades on expiry days only."
        s == "hero_waiting_for_window" -> "Expiry day: it watches from 13:30 (the straddle's low counts from 12:00)."
        s == "hero_window_closed" -> "No new entries after 14:45."
        s == "hero_skipped_stale_bar" -> "Skipped the last minute: the NIFTY or straddle data was stale."
        s == "hero_straddle_not_expanded" -> "Watching: the ATM straddle is not yet 15% above its low since 12:00."
        s == "hero_no_momentum" -> "The straddle has expanded, but NIFTY has not moved 0.25% in 15 minutes."
        s == "hero_no_strike" -> "Fired, but no OTM option priced Rs 1-5 passed the checks; later bars may fire again."
        s == "hero_zero_lots" -> "Fired, but one lot costs more than Rs 5,000: skipped."
        s == "hero_lot_unknown" -> "The lot size is not in the instrument master: no trade."
        s.startsWith("hero_disarmed: ") -> "Disarmed itself: ${s.removePrefix("hero_disarmed: ")}. Arm it again by hand to restart."
        else -> s
    }

    // ---- Zerodha (the app in Live) --------------------------------------------------

    /**
     * The limit under a live stop: SL, not SL-M (Zerodha can refuse SL-M on index options),
     * 5% (at least 2 points) below the trigger so it fills in a fast fall. If the price runs
     * through even that, the app's own check sells at market.
     */
    private fun stopLimit(trigger: Double, tick: Double): Double =
        com.optionslab.engine.Kite.onTick(trigger - maxOf(2.0, trigger * 0.05), tick, com.optionslab.engine.Kite.Side.SELL).coerceAtLeast(tick)

    private fun kiteCharge(side: String, price: Double, qty: Int): Double =
        com.optionslab.engine.sandbox.SandboxCosts.charge(side, java.math.BigDecimal(price), qty).toDouble()

    /** Terminal order states at Zerodha. */
    private val DONE = setOf("COMPLETE", "REJECTED", "CANCELLED")

    /** Kite order ids this phone already tracks (every bot's, and the arms' own): never adopted for a lost reply. */
    private suspend fun knownKite(b: Book): List<String> =
        runCatching { Strategies.owners().keys.filter { it.startsWith("kite:") }.map { it.removePrefix("kite:") } }.getOrDefault(emptyList()) +
            b.positions.flatMap { listOfNotNull(it.entryOrderId, it.stopOrderId) }

    /**
     * The resting SL SELL 40 below a live fill. Its trigger, and its order id or null when it could not be
     * placed (the app then watches the stop itself). A lost reply is looked for before it counts as unplaced.
     */
    /**
     * 20 Nov 2026 (audit B5 deep review): the key a trade's lost stop is looked for under - its arm, contract, fill and the part's own
     * quantity (each part of a split trade its own: one lost stop is never claimed by two parts).
     */
    private fun orbLostKey(label: String, sym: String, fill: Double, qty: Int) = "orbstop:$label:$sym:$fill:$qty"

    /**
     * 20 Nov 2026 (delta review: the key was kept with the quantity at the send, and a ladder sale since changed the trade's): the key
     * [p]'s lost stop is kept under - its own quantity's, else, when no other part of the same fill is open (whose own it could be), one
     * sent for more on its fill (a part sold since).
     */
    private fun lostKeyOf(b: Book, p: Position, label: String, sym: String): String {
        val exact = orbLostKey(label, sym, p.entry, p.qty)
        if (Broker.lostPending(exact)) return exact
        val prefix = "orbstop:$label:$sym:${p.entry}:"
        if (b.positions.any { it.open && it !== p && it != p && it.kite == sym && it.entry == p.entry && armOf(it.arm).label == label }) return exact
        return Broker.lostKeys(prefix).firstOrNull { k -> k.removePrefix(prefix).toIntOrNull()?.let { q -> q > p.qty } == true } ?: exact
    }

    /**
     * 20 Nov 2026 (delta review): a lost stop [l] found for more than [qty] (a part sold since it went) cut down to [qty] at Zerodha; one
     * that could not be cut is taken out (marked the app's own) and, once Zerodha shows it out, false returned (a stop rests again on a
     * later look). True: it is the trade's stop - covering no more than [qty], ended (its fill, if any, is the trade's, booked as usual),
     * unreadable, or not confirmed out (kept as the trade's own, never left resting with no owner beside a second one).
     * 22 Nov 2026 (B6 review: a stop taken out after part of it filled was said to "rest again" and that fill never booked): [Fit.Kept]
     * (true before), [Fit.Out] (false before: nothing of it filled) or [Fit.Filled] - taken out after part of it filled: the caller books
     * that part closed at the stop ([stopSold]). [b]: the book, the caller holding the arms' lock ([cancelOwnStop]).
     */
    private suspend fun fitFound(label: String, l: Broker.LostLook.Found, qty: Int, lot: Int, tick: Double, b: Book?): Fit {
        if (l.qty <= qty) return Fit.Kept
        val so = runCatching { Broker.orderRow(l.id) }.getOrNull() ?: return Fit.Kept
        if (!so.working) return Fit.Kept
        val to = com.optionslab.engine.risk.ExitQty.shrinkTo(so.qty, so.filled, qty, lot)
        if (to == null || (to > so.filled && runCatching {
                if (so.type == "SL-M") Broker.modify(so, to, "SL-M", null, so.trigger) else Broker.modify(so, to, "SL", stopLimit(so.trigger, tick), so.trigger)
            }.isSuccess)) {
            runCatching { Diag.record("orb", "$label: its lost stop ${l.id} was found for ${l.qty}, more than the $qty it holds now: cut to $qty") }
            return Fit.Kept
        }
        cancelOwnStop(l.id, b)
        val st = runCatching { Broker.orderState(l.id) }.getOrNull()
        if (st == null || (st.status != "CANCELLED" && st.status != "REJECTED")) {
            // (22 Nov 2026, B6 review: still working - the cancel may not have taken: its own-cancel mark ends soon, [ownCancelDidNotTake].)
            if (st != null && st.status in STILL_WORKING) ownCancelDidNotTake(l.id)
            runCatching { Diag.record("orb", "$label: its lost stop ${l.id} was found for ${l.qty}, more than the $qty it holds now; it could not be cut " +
                "or confirmed out, so it is kept as its stop") }
            return Fit.Kept
        }
        if (st.filled > 0) {
            runCatching { Diag.record("orb", "$label: its lost stop ${l.id} was found for ${l.qty}, more than the $qty it holds now, and could not be cut: " +
                "taken out after ${st.filled} of it filled - that part is booked closed at the stop") }
            return Fit.Filled(l.id, st.filled, st.avgPrice.takeIf { it > 0 } ?: so.trigger.takeIf { it > 0 } ?: 0.0)
        }
        runCatching { Diag.record("orb", "$label: its lost stop ${l.id} was found for ${l.qty}, more than the $qty it holds now, and could not be cut: taken out; it rests again") }
        return Fit.Out
    }

    /** 22 Nov 2026 (B6 review): what [fitFound] made of a found lost stop. */
    private sealed class Fit {
        /** It is the trade's stop (covering no more than asked, ended, unreadable, or not confirmed out). */
        object Kept : Fit()
        /** Taken out with nothing of it filled: a stop rests again on a later look. */
        object Out : Fit()
        /** Order [id] taken out after [qty] of it filled at [avg] (0: not known): that part of the trade is booked closed ([stopSold]). */
        data class Filled(val id: String, val qty: Int, val avg: Double) : Fit()
    }

    /**
     * 22 Nov 2026 (B6 review): [p] on [sym], whose found lost stop [f] was taken out after part of it filled ([Fit.Filled]): that part booked
     * closed at the stop (its fill's price, else the stop's trigger), the rest left open with no stop - a stop rests for it on a later look.
     * One that sold more than [p] holds is said loudly (Zerodha may show a short).
     */
    private fun stopSold(p: Position, f: Fit.Filled, sym: String): Pair<Position, Position?> {
        val sold = minOf(f.qty, p.qty)
        val px = f.avg.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
        val label = armOf(p.arm).label
        // (Labelled and kept as a booked exit sale, as every booked sale: a lost exit's search never takes it for its own.)
        runCatching { Strategies.tagOwnerSoon("kite:${f.id}", "$label · ${stopWhy(p)}") }
        runCatching { noteExitSale(f.id) }
        runCatching { Notifier.orderFilled(app, "SELL", sold, sym, px, "Live", "$label · ${FIXED.words(stopWhy(p).replace('_', ' '))}", f.id) }
        if (f.qty > p.qty) runCatching { com.optionslab.app.work.Alerts.error("$label: a stop ${f.id} on $sym whose answer was lost sold ${f.qty} " +
            "before it was taken out, more than the ${p.qty} this trade holds. Check $sym's position in Kite.", "ORB live") }
        val share = sold.toDouble() / p.qty
        val closed = p.copy(qty = sold, exit = px, exitTime = now(), why = stopWhy(p), stopOrderId = null,
            charges = p.charges * share + kiteCharge("SELL", px, sold))
        return if (sold >= p.qty) closed to null else closed to p.copy(qty = p.qty - sold, stopOrderId = null, charges = p.charges * (1 - share))
    }

    private suspend fun placeStop(label: String, sym: String, qty: Int, lot: Int, tick: Double, fill: Double, known: Collection<String>,
                                  liquidity: Boolean = false, at: Double? = null, points: Double = OrbRules.STOP_POINTS,
                                  pct: Double = LiquidityRules.PREMIUM_STOP, alert: Boolean = true,
                                  lostKey: String = orbLostKey(label, sym, fill, qty)): StopPlaced {
        // The ORB's stop is 40 points ([points]) below the fill; Liquidity 15+5's is 15% ([pct]) below it; [at]: a given trigger (the lock, F3).
        val trigger = (at ?: if (liquidity) LiquidityRules.stopTrigger(fill, pct) else OrbRules.stopTrigger(fill, points))
            ?.let { com.optionslab.engine.Kite.onTick(it, tick, com.optionslab.engine.Kite.Side.SELL) }
            ?: return StopPlaced(null, null, false, 0)
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, qty, lot, "MIS", "SL",
            stopLimit(trigger, tick), tick, "NFO", "iraorb", triggerPrice = trigger)
        // 15 Nov: whether Zerodha REFUSED it outright (nothing rests: another stop may go), apart from a lost reply.
        var refused = false
        // 20 Nov 2026 (audit B5 deep review): a stop of this contract whose reply was lost and is not found yet is UNKNOWN: looked for
        // again here, and no second stop is sent beside it while it may rest ([Broker.lookLost], two minutes; then Boss is told).
        when (val l = Broker.lookLost(lostKey)) {
            // (20 Nov 2026, delta review: never one the book already knows - another trade's - and cut to [qty] when found for more.)
            // (A found stop for fewer than [qty] covers only its own: [StopPlaced.covers] - the rest stays with none and rests again.)
            is Broker.LostLook.Found -> if (l.id !in known) {
                // (22 Nov 2026, B6 review: taken out after part of it filled - that fill handed back to the caller to book, [StopPlaced.sold].)
                when (val f = fitFound(label, l, qty, lot, tick, cache)) {
                    Fit.Out -> return StopPlaced(null, trigger, false, 0)
                    is Fit.Filled -> return StopPlaced(null, trigger, false, 0, sold = f)
                    Fit.Kept -> Unit
                }
                // (22 Nov 2026, B6 review: labelled without waiting on the Strategy Module's lock, held across its Zerodha calls.)
                runCatching { Strategies.tagOwnerSoon("kite:${l.id}", "$label · stop") }; return StopPlaced(l.id, l.trigger ?: trigger, false, minOf(l.qty, qty))
            }
            Broker.LostLook.Searching -> return StopPlaced(null, trigger, false, 0)
            else -> Unit
        }
        val id = try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
            if (Broker.definite(e)) { refused = true; null } else runCatching { Broker.findRecentRetrying(o, known) }.getOrNull()
                ?: null.also { Broker.noteLost(lostKey, o, known, "$label: its stop", liveOnly = true) }
        }
        // (22 Nov 2026, B6 review: labelled without waiting on the Strategy Module's lock, held across its Zerodha calls - as the square-offs.)
        if (id != null) runCatching { Strategies.tagOwnerSoon("kite:$id", "$label · stop") }
        else if (alert || !refused) com.optionslab.app.work.Alerts.error("$label: the ${if (at != null) "%.2f".format(Locale.ENGLISH, trigger) else if (liquidity) "${pts(pct * 100)}%" else "−${pts(points)}"} " +
            "stop could not be placed at Zerodha; the app watches it instead.", "ORB live")
        return StopPlaced(id, trigger, refused, if (id != null) qty else 0)
    }

    /**
     * 20 Nov 2026 (delta review): what [placeStop] did - the stop's id (null: none rests), its trigger, whether Zerodha refused it
     * outright, and the quantity it covers (a lost stop found for fewer than asked covers only its own; the caller splits on it).
     * 22 Nov 2026 (B6 review): [sold] - the trade's own lost stop found for more than asked, taken out after part of it filled: none rests,
     * and the caller books that part closed ([stopSold]). (Only a key sent for more than asked now can be found so: an entry's first stop
     * looks for its own exact quantity, so it is never set there.)
     */
    private data class StopPlaced(val id: String?, val trigger: Double?, val refused: Boolean, val covers: Int, val sold: Fit.Filled? = null)

    /**
     * 15 Nov (a Liquidity live trade always ends with a resting stop when one can rest): the first stop of a Liquidity live fill of [qty]
     * at [fill] on a contract of [lot]. With a rule entry stop ([fixed]; none since V4, so the -15% today) tighter than the -15%: it rests at that level at once
     * when it may rest at the fill; else only when the price NOW ([Broker.stopQuotes]) lets it - and otherwise, or when Zerodha refuses it
     * outright, the -15% rests at once and the app sells at the rule's level itself ([fixedBelow]), moving the stop up once it can rest
     * there ([raiseLiveStop]). A lost reply is never followed by a second stop (one may be resting). Without the rule: as before.
     */
    private suspend fun firstLiveStop(label: String, sym: String, qty: Int, lot: Int, tick: Double, fill: Double, known: Collection<String>,
                                      fixed: Boolean, points: Double, pct: Double): Pair<String?, Double?> {
        if (fixed) {
            val own = LiquidityRules.stopTrigger(fill, pct)
            val at = firstStop(own, fill, lot, true, tick)?.let { com.optionslab.engine.Kite.onTick(it, tick, com.optionslab.engine.Kite.Side.BUY) }
            if (at != null && FIXED.isTighter(own, at)) {
                // Placed at once when it may rest at the fill (no quote read on the way); the price now is read only
                // when it may not - and a refusal (the price fell through it meanwhile) still falls back to the -15% below.
                val restsAtFill = com.optionslab.engine.orb.LiveMirror.mayRest(at, fill, tick, 1)
                val now = if (restsAtFill) fill else "NFO:$sym".let { key -> runCatching { Broker.stopQuotes(listOf(key))[key]?.last }.getOrNull()?.takeIf { it > 0 } ?: fill }
                if (restsAtFill || com.optionslab.engine.orb.LiveMirror.mayRest(at, now, tick, 1)) {
                    val (id, trig, refused) = placeStop(label, sym, qty, lot, tick, fill, known, liquidity = true, at = at, points = points, pct = pct, alert = false)
                    if (id != null || !refused) return id to trig
                }
                runCatching { Diag.record("orb", "$label: the rule's stop %.2f on $sym could not rest at Zerodha (the price %.2f); its 15%% stop rests instead and the app sells at %.2f itself"
                    .format(Locale.ENGLISH, at, now, at)) }
            }
        }
        val (id, trig) = placeStop(label, sym, qty, lot, tick, fill, known, liquidity = true, points = points, pct = pct)
        return id to trig
    }

    /**
     * A live entry: MARKET BUY 1 lot MIS at Zerodha (Liquidity 15+5: its [lots]), then a resting SL SELL 40 below the
     * fill. Only reached after the owner approved with the PIN (see [approve]).
     */
    private suspend fun enterLive(b: Book, arm: Arm, c: Paper.Contract, signalBar: LocalDateTime, liquidity: LiquidityRules.Signal? = null,
                                  near: Boolean? = null, volSkip: Boolean? = null, strong: Boolean? = null, lots: Int = 1): String {
        // 18 Nov 2026 (review): counted while on its way, until its fill is in the book: the arms' live copies never take it for a lost entry's.
        entering.incrementAndGet()
        try { return enterLiveNow(b, arm, c, signalBar, liquidity, near, volSkip, strong, lots) } finally { entering.decrementAndGet() }
    }

    /** 18 Nov 2026: live entries of these arms on their way now (memory only; [entryInFlight]). */
    private val entering = java.util.concurrent.atomic.AtomicInteger()

    /** 18 Nov 2026: a live entry of these arms is on its way (memory only): [ArmLive] never takes a position for a lost entry meanwhile. */
    val entryInFlight: Boolean get() = entering.get() > 0

    private suspend fun enterLiveNow(b: Book, arm: Arm, c: Paper.Contract, signalBar: LocalDateTime, liquidity: LiquidityRules.Signal?,
                                     near: Boolean?, volSkip: Boolean?, strong: Boolean?, lots: Int,
                                     /** 20 Nov 2026 (Boss's exception, [CheaperStrike]): this entry is the cheaper strike [c] it chose (every gate again, 1 lot). */
                                     step: CheaperStrike.Pick? = null, t0: Long = System.nanoTime()): String {
        runCatching { OrderTiming.decided() }
        // (20 Nov 2026: whether the cheaper strike was already tried for this entry - never twice.)
        var stepTried = step != null
        // A paper-only arm (the Hero arm among them) never reaches Zerodha, whatever called this.
        liveRefusal(arm.source)?.let { return it }
        val s = AppSettings.load()
        if (s.guardKill) return "refused: the kill switch is on"
        // 3 Nov (Boss): only the indices selected in Bot settings → Indices go live.
        if (!com.optionslab.engine.orb.LiveMirror.marketOn(c.underlying, s.liveMarkets)) return "refused: ${c.underlying} is not one of your live indices (Bot settings → Indices)"
        // Liquidity's size over the Bot settings' max lots: said by name, never sent smaller (the guard's own checks follow).
        LiquidityLots.liveRefusal(lots, s.guardMaxLots)?.let { return it }
        // A phone that failed the security check never sends a real order on its own.
        val findings = runCatching { com.optionslab.app.security.Integrity.reportWithin(app, 60_000) }.getOrDefault(emptyList())
        if (com.optionslab.app.security.Integrity.compromised(findings)) return "refused: this phone failed the security check; no live order sent"
        if (!Broker.loggedIn) return "refused: not logged in to Zerodha today"
        // 19 Nov 2026 (coordinator): this process's cold-start sweep ([coldSweep]) runs here when no look has run it yet. It took a lot back:
        // that trade already exists and this entry's checks ran before it was here - no buy now. It could not run (the order book
        // unreadable, the arms' live copies' book not read): the entry goes as before - waiting would be a new skip of live entries, so
        // that is only logged in SHADOW ("would have waited") and said once; the sweep keeps trying on later looks.
        // (20 Nov 2026: the cheaper strike's own pass skips it - its arm's entry just ran it, on the same index.)
        if (!coldSwept && step == null) {
            val rows = b.positions.size
            coldSafety.clear()                                                       // (only this call's safety candidates count below)
            passPlaced.clear(); passIds.clear()
            val t0 = now()
            val done = runCatching { coldSweep(b, t0, Strategies.stoppedToday()) }.getOrDefault(false)
            if (done) coldSwept = true else coldFailed(t0)
            // (19 Nov 2026, review: only when a lot taken back is THIS arm's on this index - that trade already exists; another arm's or
            // another index's lets this entry go on.)
            val sameIndex = Regex("^${Regex.escape(c.underlying)}\\d")
            if (b.positions.drop(rows).any { sameIndex.containsMatchIn(it.symbol) && (it.arm == arm.source || "${arm.source}|${c.underlying}" in coldSafety) })
                return "refused: a lot from before the restart was taken back at Zerodha; nothing more bought now"
            if (!done) {
                runCatching { Diag.record("orb", "${arm.label}: shadow - would have waited for the start-up sweep of Zerodha's orders (not run yet); the entry goes") }
                if (!coldGateSaid) {
                    coldGateSaid = true
                    runCatching { com.optionslab.app.work.Alerts.error("${arm.label}: a live entry went before the arms could check Zerodha's orders " +
                        "for a buy from before the restart (the check is tried again on each look). Check Trade for a lot with no stop.", "ORB live") }
                }
            }
        }
        val ins = (Broker.cachedInstruments() ?: runCatching { Broker.instruments() }.getOrNull())?.firstOrNull {
            it.name == c.underlying && it.expiry == c.expiry && it.right == c.right && kotlin.math.abs(it.strike - c.strike) < 1e-6
        } ?: return "refused: ${c.symbol} is not listed on Zerodha"
        val sym = ins.tradingSymbol
        val key = "NFO:$sym"
        val quote = runCatching { Broker.quotes(listOf(key))[key] }.getOrNull()
        val last = quote?.last?.takeIf { it > 0 } ?: return "refused: no quote"
        // The live account's parameters today (S1: a variant only after Boss's PIN, from the session after): its stop.
        val today0 = now().toLocalDate()
        val lp = paramsOf(arm, today0, live = true)
        val lq = liqParamsOf(arm.source, today0, live = true)
        if (liquidity == null && OrbRules.stopTrigger(last, lp.stopPoints) == null) return "refused: premium %.2f is at or below %s, a %s-point stop has no level"
            .format(Locale.ENGLISH, last, pts(lp.stopPoints), pts(lp.stopPoints))
        if (Strategies.stoppedToday()) return "stopped_for_today"
        // 19 Nov 2026 (Boss: "Go"; live: Zerodha refused MARKET option buys for "Insufficient funds" - it blocks margin for a MARKET buy
        // at a protective price far above the premium, ~₹65k asked for lots costing ~₹17-19k with ₹50k free): the live buy goes as a
        // marketable LIMIT - the offer from the quote read above (else the last price) plus max(2 ticks, 0.5%), up to the tick, never above
        // the last price plus max(2 ticks, 2%) (a wide or stale offer is not chased: it rests there and is cancelled unfilled). It waits for
        // its fill; what has not filled after the wait is cancelled and only what filled is booked, with its stop (never chased). No usable
        // price: nothing is sent, and Boss is told. ([com.optionslab.engine.orb.LiveMirror.entryLimit] as the arms' live copies' branch has it.)
        val limitPx = com.optionslab.engine.orb.LiveMirror.entryLimit(quote?.ask, last, ins.tickSize, null) ?: run {
            runCatching { com.optionslab.app.work.Alerts.error("${arm.label}: no usable price to set the live LIMIT buy of $sym at; nothing was sent.", "ORB live") }
            return "refused: no usable price for the LIMIT buy of $sym"
        }
        var o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.BUY, ins.lotSize * lots.coerceAtLeast(1), ins.lotSize, "MIS", "LIMIT", limitPx,
            ins.tickSize, "NFO", "iraorb")
        // Positions, funds and orders: the kept margins when fresh and roomy for this buy, else read now (in parallel).
        val acct = Broker.accountNow(needed = limitPx * o.quantity)
        // 18 Nov 2026 (audit: B6; Boss, 4 Nov: "if I select 3 lots, buy 3 if the money is there, else try 2, else 1 ... make it default"):
        // Liquidity's own live buy of more than one lot is cut to the lots the free cash fits ([com.optionslab.engine.orb.LiveMirror.lotsThatFit],
        // as the arms' live copies do), priced at the higher of the last price and the offer - only ever fewer than Boss's lots, never
        // more. The free cash is the margins the account read above just used ([Broker.keptFreeCash], memory: nothing new is read);
        // unknown, or not even one lot fitting, the buy goes as before (the funds check below and Zerodha say why). Every check after
        // this one sees the order as it will be sent.
        val want = lots.coerceAtLeast(1)
        var buyLots = want
        if (want > 1) {
            val free = Broker.keptFreeCash()
            // (19 Nov 2026: priced at the LIMIT the buy goes at - what Zerodha blocks for it.)
            val fitPx = limitPx
            val fit = com.optionslab.engine.orb.LiveMirror.lotsThatFit(want, fitPx * ins.lotSize, free)
            if (free != null && fit in 1 until want) {
                buyLots = fit
                o = o.copy(quantity = ins.lotSize * fit)
                runCatching { Diag.record("orb", "${arm.label}: $sym" + com.optionslab.engine.orb.LiveMirror.fitWords(want, fit, free)) }
            }
        }
        // 20 Nov 2026 (audit): the lots Boss's setting asked for, kept on the trade when the cut above bought fewer (its journal line says so).
        // (20 Nov 2026: on the cheaper strike's pass, the lots the signal's own entry asked for - [CheaperStrike.Req.askedLots].)
        val wantAll = step?.req?.askedLots?.takeIf { it > want } ?: want
        val asked = wantAll.takeIf { buyLots < it && liquidity != null }
        val refusals = Guard.check(Guard.liveOrder(o).copy(price = limitPx), acct)
        if (refusals.isNotEmpty()) return "guard_refused: " + refusals.joinToString(" ")
        val why = com.optionslab.engine.Kite.refusals(o, s.limits(), Broker.sentToday(), false, refPrice = last)
        if (why.isNotEmpty()) return "refused: " + why.joinToString("; ")
        // 15 Nov (Boss: "Yes" to it in shadow; one ORB live buy of early Nov refused by Zerodha, "Insufficient funds"): the funds check -
        // a buy the free cash cannot pay for even one lot. Its figure is the margins the account read above just used
        // ([Broker.keptFreeCash], memory; nothing new is read); unknown, it says nothing. In SHADOW (its switch off, the default) the
        // order goes exactly as before and the would-skip is logged; on, it is not sent (kept as a would-be entry).
        // 20 Nov 2026 (Boss's exception, [CheaperStrike]): the lots cut above first; still not even one lot fitting (the same kept free cash),
        // a Liquidity buy steps to a cheaper strike (up to 3 out, 1 lot) through this whole entry again. It went: its answer. None fits, or a
        // gate refused the cheaper one before it went: this entry goes on exactly as before (the funds check below included).
        if (liquidity != null && !stepTried && com.optionslab.engine.orb.LiveMirror.shortOfOneLot(limitPx * ins.lotSize, Broker.keptFreeCash())) {
            stepTried = true
            liqStep(b, arm, c, sym, signalBar, liquidity, near, volSkip, strong, t0, want, Broker.keptFreeCash(), CheaperStrike.Trigger.PRE_SEND)?.let { return it }
        }
        FundsCheck.check(arm.label, sym, buyLots, limitPx * ins.lotSize, Broker.keptFreeCash(), limitPx)?.let { return it }
        val known = knownKite(b)
        // 15 Nov (Boss's exception): a Liquidity live entry enters with the breakeven rule while the switch is on (memory only); 16 Nov:
        // a new one with the fourth version's breakeven at +₹1,000 a lot too ([Position.lock]).
        val fixed = liquidity != null && StepTrailExits.forEntry(arm.source)
        val lockNew = fixed && StepTrailExits.lockOn()
        val armKey = if (liquidity != null) FlowShadow.liquidityKey(c.underlying) else arm.source
        // 22 Oct: the checks above cleared THIS order: stamped with its fingerprint (and the stop it rests), recomputed at the door.
        val liveStop = if (liquidity == null) OrbRules.stopTrigger(last, lp.stopPoints) else LiquidityRules.stopTrigger(last, lq.stopPct)
        // (25 Oct: dated by the market clock, the one the door ([Broker.placeOrder]) reads back; in the app both clocks are the same.)
        val verdict = OrderVerdicts.cleared(OrderVerdicts.liveFields(o, Market.today(), armKey, stop = liveStop), "ORB live checks")
        // 15 Nov (audit: rejected live orders shown with no arm): the arm's label noted before the order goes, memory only
        // ([ArmOrderLabels]), so an order Zerodha refuses but still keeps as REJECTED is shown with its arm. Nothing else changes.
        val owner = "${arm.label} · entry"
        // 18 Nov 2026 (audit: B5, a crash between the POST and the booking left an unowned lot with no stop, and the same bar could be
        // bought again): BEFORE the order goes, the entry is written down as an unconfirmed trade with no order id yet (its "intent") and
        // the book is saved with it - the decided bar too ([Book.decided], taken before this). After a restart the unconfirmed-entry
        // settle ([settleEntry]) looks for its order and what Zerodha holds: found or held, kept with its stop; else dropped with a word.
        // Here the intent row becomes the trade: dropped when nothing went, kept unconfirmed (its order id in) when Zerodha has not said.
        val sent = ArmOrderLabels.sending(o, owner, runCatching { Market.now().toInstant().toEpochMilli() }.getOrDefault(System.currentTimeMillis()))
        val intent = stamped(Position(arm.source, c.symbol, c.right.name, o.quantity, last, now(), signalBar, null, null, null,
            live = true, kite = sym, unconfirmed = true, level = liquidity?.level, target = liquidity?.target, ladder = ladderOn(arm, today0, true),
            near = near, volSkip = volSkip, strong = strong, lot = ins.lotSize.takeIf { liquidity != null && it > 0 }, fixed = fixed, lock = lockNew,
            stepFrom = step?.req?.let { stepFromName(it) }, askedLots = asked, sentLots = asked?.let { buyLots }))
        b.positions += intent
        // (19 Nov 2026, second review: written on the book's writer thread, so the order below never waits on the disk; any later save
        // of the book writes after it, never under it - [saveSoon].)
        runCatching { saveSoon(b) }
        testIntentQueued?.let { runCatching { it() } }
        // 19 Nov 2026 (audit batch 2, P4): a new live entry on the contract - an earlier "confirmed flat" mark on it no longer stands.
        flatSince.remove(sym)
        // (The row as it now stands in the book, found by identity: the book's lock is held for the whole entry.)
        var row = intent
        fun dropIntent() { val r = row; b.positions.removeAll { it === r }; runCatching { save(b) } }
        fun putIntent(p: Position) { val r = row; val i = b.positions.indexOfFirst { it === r }; if (i >= 0) b.positions[i] = p else b.positions += p; row = p }
        val id: String? = try {
            // The arm's key as its entry check asked the portfolio guard (the door refuses only this arm's blocked entry).
            // [last]: the price this entry decided on (its slippage is measured from it once it fills; nothing else changes).
            step?.sent = true
            Broker.placeOrder(o, arm = armKey, intended = last, verdict = verdict, stop = liveStop)
        } catch (e: Broker.NotLoggedIn) {
            dropIntent()
            step?.sent = false                                      // (nothing reached Zerodha: the arm's own entry decides as before)
            return "refused: not logged in to Zerodha today"
        } catch (e: Exception) {
            // 20 Nov 2026: the app's own door refused the cheaper strike - nothing reached Zerodha, so it never "went".
            if (CheaperStrike.appRefused(e)) step?.sent = false
            if (ArmOrderLabels.zerodhaRefused(e)) ArmOrderLabels.refused(sent)
            if (Broker.definite(e)) {
                dropIntent()
                // 20 Nov 2026 (Boss's exception): Zerodha's own refusal for funds (definite - never a lost reply) steps to a cheaper strike once.
                if (liquidity != null && !stepTried && CheaperStrike.definiteRefusal(e)) {
                    stepTried = true
                    // (The free cash read fresh: the kept figure is what let the refused order go. Unknown: no step.)
                    liqStep(b, arm, c, sym, signalBar, liquidity, near, volSkip, strong, t0, want, CheaperStrike.freshFreeCash(),
                        CheaperStrike.Trigger.REJECTED)?.let { return it }
                }
                // (A refusal at the app's own door is said once, by [liqStep], which lets the arm's own entry go on.)
                if (!CheaperStrike.appRefused(e)) step?.let { CheaperStrike.notBought(it, e.message ?: "Zerodha refused it") }
                return "order_refused: " + (e.message ?: "Zerodha refused the order")
            }
            // The answer was lost, not necessarily the order: look (the book can trail the POST) before calling it unsent.
            // (19 Nov 2026: not found is not proof it was never placed - a LIMIT may rest and fill later. The intent stays unconfirmed with no
            // order id: the settle's lost-id search finds it, cancels what still works and keeps what filled, or drops it after its wait.)
            try { Broker.findRecentRetrying(o, known) } catch (_: Exception) { null }   // null: not found yet, or the book unreadable
        }
        // (B5: its order id written down at once on the writer thread - never a disk wait before its stop goes; a restart before that write
        // finds the order by the search, which takes this arm's own unclaimed entry orders too, [settleEntry]. The labels after it never
        // stop its booking: a failure there is a missing label, not a lost trade.)
        if (id != null) { putIntent(intent.copy(entryOrderId = id)); runCatching { saveSoon(b) } }
        id?.let { runCatching { ArmOrderLabels.answered(it, owner, runCatching { Market.now().toInstant().toEpochMilli() }.getOrDefault(System.currentTimeMillis())) } }
        id?.let { runCatching { Strategies.tagOwner("kite:$it", owner) } }
        // 19 Nov 2026 (coordinator: the arms' lock is held through this wait - the other open trades' looks wait on it): a marketable LIMIT
        // fills at once or not at all, so 5 s at most; then the rest is cancelled and its end waited for (3 s at most) - still not ended,
        // the entry is kept unconfirmed with its order id and the settle finishes it on a later look, outside this wait.
        var f = id?.let { runCatching { Broker.awaitOrder(it, ENTRY_WAIT_MS) }.getOrNull() }
        var cancelledByApp = false
        if (id != null && f?.status !in DONE) {
            // Not finished in the wait: the unfilled rest is cancelled so it can never fill later untracked; only what filled is booked.
            // (19 Nov 2026: then its end waited for, a few seconds - Kite shows a cancel as pending for a moment.)
            cancelledByApp = runCatching { Broker.cancel(id) }.isSuccess
            f = runCatching { Broker.awaitOrder(id, 3_000) }.getOrNull()?.takeIf { it.status in DONE } ?: runCatching { Broker.orderState(id) }.getOrNull()
        }
        if (f == null || f.status !in DONE) {
            // Zerodha has not said how it ended: held as unconfirmed (so the arm buys nothing more) until the books settle it.
            putIntent(intent.copy(entryOrderId = id))
            runCatching { save(b) }
            com.optionslab.app.work.Alerts.error("${arm.label}: Zerodha did not confirm the buy of $sym. It is treated as held (no stop yet) " +
                "until the order book shows what filled.", "ORB live")
            return "entered_unconfirmed"
        }
        if (f.filled <= 0) {
            dropIntent()
            // (19 Nov 2026: the LIMIT not filled in the wait and cancelled by the app: nothing bought, never chased - said as such.)
            if (cancelledByApp && f.status == "CANCELLED") {
                runCatching { Diag.record("orb", "${arm.label}: the LIMIT buy of $sym at %.2f did not fill in 5 s; cancelled, nothing bought".format(Locale.ENGLISH, limitPx)) }
                step?.let { CheaperStrike.notBought(it, "the LIMIT did not fill in 5 s; cancelled") }
                return "unfilled: the LIMIT buy at %.2f did not fill in 5 s; cancelled, nothing bought".format(Locale.ENGLISH, limitPx)
            }
            // 20 Nov 2026 (Boss's exception): REJECTED for funds (a definite end in Zerodha's book) steps to a cheaper strike once.
            if (f.status == "REJECTED" && liquidity != null && !stepTried && CheaperStrike.fundsRefused(f.message)) {
                stepTried = true
                liqStep(b, arm, c, sym, signalBar, liquidity, near, volSkip, strong, t0, want, CheaperStrike.freshFreeCash(),
                    CheaperStrike.Trigger.REJECTED)?.let { return it }
            }
            step?.let { CheaperStrike.notBought(it, "Zerodha ${f.status.lowercase()}") }
            return "order_refused: Zerodha ${f.status.lowercase()}" + (f.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        }
        val fill = f.avgPrice.takeIf { it > 0 } ?: last
        // 15 Nov (Boss's exception): a Liquidity live entry's stop rests at Zerodha at its -15% ([firstLiveStop]; the rule sets no entry stop since
        // V4, its breakeven moves the stop up later), so a dead phone still sells there.
        val (stopId, trigger) = if (liquidity != null) firstLiveStop(arm.label, sym, f.filled, ins.lotSize, ins.tickSize, fill, known + listOfNotNull(id),
                fixed, lp.stopPoints, lq.stopPct)
            else placeStop(arm.label, sym, f.filled, ins.lotSize, ins.tickSize, fill, known + listOfNotNull(id), liquidity = false,
                points = lp.stopPoints, pct = lq.stopPct).let { (a, b, _) -> a to b }
        // (B5: the stop placed and the trade booked first - in the intent's place - then the fill's notice, which can never stop either.)
        putIntent(stamped(Position(arm.source, c.symbol, c.right.name, f.filled, fill, now(), signalBar, id, stopId, trigger,
            charges = kiteCharge("BUY", fill, f.filled), live = true, kite = sym, level = liquidity?.level, target = liquidity?.target,
            ladder = ladderOn(arm, today0, true), near = near, volSkip = volSkip, strong = strong,
            lot = ins.lotSize.takeIf { liquidity != null && it > 0 }, fixed = fixed, lock = lockNew, entryQty = f.filled,
            stepFrom = step?.req?.let { stepFromName(it) },
            // (20 Nov 2026: fewer lots than asked - the free-cash cut, or a part fill - kept with the lots sent, for the trade's words.)
            askedLots = wantAll.takeIf { liquidity != null && (buyLots < it || f.filled < buyLots * ins.lotSize) },
            sentLots = buyLots.takeIf { liquidity != null && (buyLots < wantAll || f.filled < buyLots * ins.lotSize) })))
        runCatching { save(b) }
        runCatching { Notifier.orderFilled(app, "BUY", f.filled, sym, fill, "Live", "${arm.label} · entry", f.orderId) }
        // 22 Oct: the time machine's reality check on the live entry too (shadow only; background).
        runCatching { TimeMachineHost.afterEntry(TimeMachineHost.Entry(arm.source, arm.label, "orb:${arm.source}:$id", c.underlying, if (c.right == Right.CE) 1 else -1,
            signalBar.plusMinutes(5), if (liquidity == null) lp.stopPoints else LiquidityRules.indexStopPoints(c.underlying),
            if (liquidity == null) lp.targetPoints else liquidity.target?.let { kotlin.math.abs(it - liquidity.level) }, premium = liquidity == null,
            exitAt = OrbRules.SQUARE_OFF, live = true)) }
        runCatching { save(b) }   // a live position is written down at once, not at the end of the pass
        marks[c.symbol] = fill
        // 20 Nov 2026 (Boss's exception): a buy on a cheaper strike is said to Boss ("Liquidity: funds short for BANKNIFTY 52000 CE, bought 52100 CE (1 lot)").
        step?.let { CheaperStrike.bought(it, (f.filled / ins.lotSize.coerceAtLeast(1)).coerceAtLeast(1)) }
        return "entered_live"
    }

    /** TEST ONLY: the order flow's re-ask for the cheaper contract skips it (a CONFIRM disagreement). False in the app; debug builds only. */
    @Volatile internal var testCheaperFlowSkip: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /**
     * Liquidity's approval notice's text: the bar that took the pool, the account and size, and (20 Nov 2026, Boss's choice) in Live while the
     * cheaper strike is on, that a cheaper strike may be bought; the time it lapses.
     */
    internal fun approvalText(und: String, tf: Int, bar: LocalDateTime, side: Int, live: Boolean, lotWords: String, expires: LocalDateTime,
                              cheaperOn: Boolean = CheaperStrike.isOn()): String =
        "The $und ${tf}-minute ${hhmm(bar)} bar took a liquidity pool ${if (side > 0) "above" else "below"}. " +
            (if (live) "LIVE on Zerodha, $lotWords: approve it on Home in the app" + (if (cheaperOn) ". $CHEAPER_ON_APPROVAL" else "")
            else "Paper account, $lotWords") + ". Approve by ${hhmm(expires)} or it lapses."

    /** 20 Nov 2026 (Boss's choice): what an approval notice in Live says while the cheaper strike is on. */
    internal const val CHEAPER_ON_APPROVAL = "If funds are short, a strike up to 300 points cheaper may be bought"

    /** 20 Nov 2026: the app's name of the contract a cheaper-strike [r] stepped from ([Position.stepFrom]). */
    private fun stepFromName(r: CheaperStrike.Req): String = r.fromName ?: r.fromSymbol

    /**
     * 20 Nov 2026 (Boss's exception, [CheaperStrike]): Liquidity's live buy of [c] (Zerodha's [fromSym]) cannot be paid for even one lot
     * ([free], [trigger]): the cheaper strike chosen - in Zerodha's list AND the app's own (its trade is booked on that contract: the
     * position's [Position.symbol] and [Position.kite] are both the cheaper option, so every exit, stop, square-off, restart and reconciler
     * check follows it as any live trade) - entered through the whole live entry again ([enterLiveNow]: every gate, 1 lot). Its answer when
     * its order went; null when none was chosen or a gate refused it before it went (the arm's own entry then goes on as before).
     */
    private suspend fun liqStep(b: Book, arm: Arm, c: Paper.Contract, fromSym: String, signalBar: LocalDateTime, liquidity: LiquidityRules.Signal,
                                near: Boolean?, volSkip: Boolean?, strong: Boolean?, t0: Long, askedLots: Int, free: Double?, trigger: CheaperStrike.Trigger): String? {
        if (!CheaperStrike.isOn() || !CheaperStrike.covers(arm.source)) return null
        val pick = CheaperStrike.find(CheaperStrike.Req(arm.label, arm.source, c.underlying, c.expiry, c.right, c.strike, fromSym, "NFO", "MIS", free,
            trigger, t0, now(), needContract = true, fromName = c.symbol, askedLots = askedLots)) ?: return null
        val c2 = pick.contract ?: return null
        // (The arms' exposure cap asked again for the cheaper contract, as its entry asked it for the signal's.)
        exposureRefusal(b, c2, arm, true)?.let { CheaperStrike.notBought(pick, "refused before it went ($it)"); return null }
        // (And the order flow for it: under CONFIRM it can skip.)
        // (The signal's own ticket - same strategy, bar and claim - so the flow log keeps one signal and the portfolio guard one claim: its
        // outcome is told once, by the signal's caller, never here.)
        val flow = FlowGate.gate(FlowShadow.liquidityKey(c2.underlying), c2.underlying, liquidity.side, true, signalBar.toString(), arm.source, contract = c2)
        if (flow.skip || testCheaperFlowSkip) { CheaperStrike.notBought(pick, "refused before it went (${flow.pausedWhy ?: FlowGate.SKIPPED})"); return null }
        // (Another Liquidity book already holding the cheaper option live: recorded only, as the signal's own contract's twin is in Live.)
        twinOf(b, arm, c2.symbol, true)?.let { holder ->
            val words = com.optionslab.ira.LiquidityTwin.words(runCatching { armOf(holder.book).label }.getOrDefault(holder.book), c2.symbol, true)
            runCatching { LiquidityTest.twin(com.optionslab.ira.LiquidityTwin.Rec(System.currentTimeMillis(), signalBar.toLocalDate(), arm.source, c2.symbol,
                pick.last, true, false, holder.book, signalBar)) }
            runCatching { Diag.record("orb", "${arm.label}: $words") }
        }
        // (Arms' lock: a refusal for funds ends the first order's wait at once - its REJECTED is a finished state - so the cheaper strike's own
        // entry adds no more than one ordinary entry's wait to the lock.)
        val r = enterLiveNow(b, arm, c2, signalBar, liquidity, near, volSkip, strong, 1, pick, t0)
        if (pick.sent) return r
        CheaperStrike.notBought(pick, "refused before it went ($r)")
        return null
    }

    /**
     * A live buy Zerodha had not confirmed: whatever of it still works is cancelled, then only what filled is
     * kept, with its stop. Null when nothing was bought; [p] (its order id filled in) while Zerodha cannot tell yet.
     */
    private suspend fun settleEntry(b: Book, p: Position, sym: String): Position? {
        val label = armOf(p.arm).label
        if (p.day.isBefore(today())) {
            com.optionslab.app.work.Alerts.error("$label: the buy of $sym on ${p.day} was never confirmed by Zerodha; check that day's contract note.", "ORB live")
            return null
        }
        val known = knownKite(b)
        // What filled ([filled] at [fill], order [oid]; null: taken back from the positions) kept, with its first stop at Zerodha.
        suspend fun kept(oid: String?, filled: Int, fill: Double): Position {
            val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull()
            val liq = armOf(p.arm).liquidity
            val pts0 = paramsOf(p).stopPoints; val pct0 = liqParams(p).stopPct
            val mine = known + listOfNotNull(oid)
            // 19 Nov 2026 (second review: a restart between the first stop's placement and the booked row's save placed a SECOND stop
            // here): a stop of these arms already resting on the contract since the entry that no trade claims is this entry's - taken
            // as its stop (resized to what filled), never a second one beside it. One that cannot be resized or taken out yet: kept
            // unconfirmed, asked again next pass. (Its orders or labels unreadable: booked with no stop at its level - the app watches
            // it, and the price check takes the resting one or places it from its own reads - never a second stop on a blind read, never
            // a filled lot left unwatched.)
            // (19 Nov 2026, final check: never grown here - what Zerodha holds is not read on this path; the price check caps it.)
            when (val o = orphanStop(b, label, sym, p.entryTime, filled, spec?.tickSize ?: 0.05, null, settling = true, entryOid = oid, grow = false)) {
                is Orphan.Wait -> return p
                Orphan.Unread, Orphan.Other -> {
                    Notifier.orderFilled(app, "BUY", filled, sym, fill, "Live", label, oid)
                    val level = if (liq) firstStop(LiquidityRules.stopTrigger(fill, pct0), fill, lotOf(p), p.fixed) else OrbRules.stopTrigger(fill, pts0)
                    return p.copy(qty = filled, entry = fill, entryOrderId = oid, stopOrderId = null, stopTrigger = level,
                        charges = kiteCharge("BUY", fill, filled), unconfirmed = false, entryQty = filled)
                }
                is Orphan.Took -> {
                    Notifier.orderFilled(app, "BUY", filled, sym, fill, "Live", label, oid)
                    return p.copy(qty = filled, entry = fill, entryOrderId = oid, stopOrderId = o.id, stopTrigger = o.trigger,
                        charges = kiteCharge("BUY", fill, filled), unconfirmed = false, entryQty = filled)
                }
                Orphan.None -> {}
            }
            Notifier.orderFilled(app, "BUY", filled, sym, fill, "Live", label, oid)
            // 15 Nov (Boss's exception): a rule Liquidity trade's first stop ([firstLiveStop]): its -15% (the rule sets no entry stop since V4; its
            // breakeven moves the stop up later).
            val (stopId, trigger) = if (spec != null && liq) firstLiveStop(label, sym, filled, spec.lotSize, spec.tickSize, fill, mine, p.fixed, pts0, pct0)
                else if (spec != null) placeStop(label, sym, filled, spec.lotSize, spec.tickSize, fill, mine, liquidity = false,
                    points = pts0, pct = pct0).let { (a, b, _) -> a to b }
                else null to (if (liq) firstStop(LiquidityRules.stopTrigger(fill, pct0), fill, lotOf(p), p.fixed) else OrbRules.stopTrigger(fill, pts0))
            if (stopId != null) placed(sym, filled, stopId)
            return p.copy(qty = filled, entry = fill, entryOrderId = oid, stopOrderId = stopId, stopTrigger = trigger,
                charges = kiteCharge("BUY", fill, filled), unconfirmed = false, entryQty = filled)
        }
        // 18 Nov 2026 (Boss, live: a Liquidity BANKNIFTY lot and R51's FINNIFTY lot were left at Zerodha with no stop and nothing watching
        // them): before an entry whose order cannot be found is called "never bought", Zerodha's positions are read - what it holds on
        // the contract (MIS) that no other open live trade of this book explains is this entry's fill, kept at once with its stop and the
        // rule it entered with: (quantity, its fill). Null: the positions could not be read (kept, asked again next pass); a quantity of
        // 0: nothing unexplained is held.
        // 18 Nov 2026 (audit: B4, a hole in the fix above: "no row for the contract" was read as "holds 0", so a lost-id entry could still
        // be dropped at three minutes on one short read): nothing held counts only on a read that LISTS the contract, and two such reads
        // must agree (each lost-id search is at least 10 s apart, [settleDue]) before the entry is called never bought - the first is kept
        // and asked again (null). A read with no row is no reading at all until [NO_ROW_WAIT_MS] after the entry (a buy that reached
        // Zerodha shows its row long before); only then may an entry Zerodha never listed go, still on two agreeing reads.
        val key = managerId(p)
        fun nothingHeld(): Pair<Int, Double>? {
            if (settleZero.size > 200) settleZero.clear()
            val n = (settleZero[key] ?: 0) + 1
            settleZero[key] = n
            return if (n >= 2) 0 to p.entry else null
        }
        suspend fun unexplained(): Pair<Int, Double>? {
            // (18 Nov 2026, review: the orders read first, then the positions, so the exit coordinator can tell a positions book trailing a
            // sale that already filled - [ExitCoordinator.held] - and a lot just sold is never kept with a stop on a flat position.)
            val orders = runCatching { Broker.orders() }.getOrNull() ?: return null
            val pb = runCatching { Broker.positionBook() }.getOrNull() ?: return null
            // (22 Nov 2026, B6 review: a read that left this contract's own row out as malformed tells nothing of what is held - not read,
            // asked again next pass - never "nothing held" toward calling the entry never bought.)
            if (pb.unknownForExit(sym, "MIS")) return null
            val rows = pb.net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }
            if (rows.isEmpty() && java.time.Duration.between(p.entryTime, now()).toMillis() < NO_ROW_WAIT_MS) return null
            if (rows.sumOf { it.qty } <= 0) return nothingHeld()
            // (Never while an arm's live copy is entering, nor before its book is read: a fill could be taken for this one's. Next pass.)
            if (ArmLive.entryInFlight || !ArmLive.view.value.loaded) return null
            // (20 Nov 2026, audit PC: the positions-lag guard acts whatever its switch says - it only ever counts less as held.)
            val lagFree = runCatching { ExitCoordinator.held("NFO", sym, "MIS", "ORB live", orders, first = pb, forceLag = lagForced()) }.getOrNull() ?: return null
            if (lagFree <= 0) return nothingHeld()
            // (What explains it: this book's other open live trades on it - one still settling from its own order id too - and the arms'
            // live copies' open buys of it, from memory.)
            val others = b.positions.filter { it !== p && it.open && it.live && (!it.unconfirmed || it.entryOrderId != null) && it.kite == sym }.sumOf { it.qty } +
                // (Review F6: never a lost entry of theirs still searched for - it may hold nothing, and must not block this one.)
                ArmLive.view.value.openReal.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side > 0 &&
                    !(it.unconfirmed && it.entryOrderId == null) }.sumOf { it.qty }
            val lot = runCatching { Broker.spec("NFO", sym).lotSize }.getOrDefault(1)
            // (Never more than was bought today by orders of no other owner - Boss's own in Kite, another bot's - nor than is held beyond
            // what was carried from an earlier day.)
            val held = minOf(rows.sumOf { it.qty }, lagFree)
            val today = com.optionslab.engine.orb.LiveMirror.boughtTodayCap(rows.map { it.dayBuyQty }, rows.sumOf { it.overnight }, held,
                orders.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side == "BUY" && it.tag != "iraorb" }.sumOf { it.filled })
            // (Review: what a lost entry of the arms' live copies took back a moment ago counts too - never one fill taken twice.)
            val q = ArmLive.takeLost("orb", "NFO", sym, "MIS") { other -> com.optionslab.engine.orb.LiveMirror.unexplainedQty(held, today, others + other, p.qty, lot) }
            val fill = rows.singleOrNull()?.avg?.takeIf { it > 0 && others == 0 } ?: p.entry
            if (q <= 0) return nothingHeld()
            settleZero.remove(key)
            if (q > 0) {
                com.optionslab.app.work.Alerts.error("$label: Zerodha did not confirm the buy of $sym and its order was not found, but Zerodha holds " +
                    "$q that no other trade explains: it is managed as this arm's trade, with its stop. Check it in Trade.", "ORB live")
                runCatching { Diag.record("orb", "$label: unconfirmed buy $sym taken back from Zerodha's positions: $q @ %.2f".format(Locale.ENGLISH, fill)) }
            }
            return q to fill
        }
        val searched = p.entryOrderId == null
        val oid = p.entryOrderId ?: run {
            // The order id itself was lost: the entry is found in today's book by its symbol, side, tag and time.
            // (19 Nov 2026: this arm's own entry orders that no trade here claims are searched too - an entry whose order id had not been
            // written down yet when the app stopped carries its owner label already ([enterLiveNow]); every other known order is left out.)
            val claimed = b.positions.flatMap { listOfNotNull(it.entryOrderId, it.stopOrderId) }.toSet()
            val ownEntries = runCatching { Strategies.owners().filter { it.key.startsWith("kite:") && it.value == "$label · entry" }
                .keys.map { it.removePrefix("kite:") }.filter { it !in claimed }.toSet() }.getOrDefault(emptySet())
            val row = runCatching { Broker.latestTagged(sym, "BUY", "iraorb", p.entryTime, known.filter { it !in ownEntries }) }.getOrElse { return p }
                ?: run {
                    if (strayBuyWorking(b, sym, p)) return p
                    val (q, fill) = unexplained() ?: return p
                    if (q > 0) return kept(null, q, fill)
                    // (18 Nov 2026: as the arms' live copies, kept unconfirmed - the arm buys nothing more - for three minutes from the
                    // entry while the order book may trail it, then called never bought; before, one read that missed it dropped it.)
                    if (ArmLive.unconfirmedWaits(p.entryTime, now())) return p
                    com.optionslab.app.work.Alerts.post("$label: no buy of $sym reached Zerodha; nothing is held.", com.optionslab.app.work.Alerts.Kind.INFO, "ORB live")
                    return null
                }
            Strategies.tagOwner("kite:${row.id}", "$label · entry")
            row.id
        }
        var st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return p.copy(entryOrderId = oid)
        if (st.status !in DONE) {
            runCatching { Broker.cancel(oid) }
            st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return p.copy(entryOrderId = oid)
            if (st.status !in DONE) return p.copy(entryOrderId = oid)
        }
        if (st.filled <= 0) {
            // 18 Nov 2026: an order found only by the search that filled nothing may be another try of the same entry: a position no
            // other trade here explains is still kept; not held either, it waits the same three minutes (the positions can trail).
            if (searched) {
                if (strayBuyWorking(b, sym, p)) return p
                val (q, fill) = unexplained() ?: return p
                if (q > 0) return kept(null, q, fill)
                if (ArmLive.unconfirmedWaits(p.entryTime, now())) return p
            }
            com.optionslab.app.work.Alerts.post("$label: the unconfirmed buy of $sym did not fill (${st.status.lowercase()}); nothing is held.",
                com.optionslab.app.work.Alerts.Kind.INFO, "ORB live")
            return null
        }
        return kept(oid, st.filled, st.avgPrice.takeIf { it > 0 } ?: p.entry)
    }

    /** Stop, target, 15:10 and the operator stop for positions held at Zerodha. */
    private suspend fun priceCheckLive(b: Book, t: LocalDateTime, open: List<IndexedValue<Position>>, stopped: Boolean,
                                       stream: Boolean = false, regular: Boolean = true) {
        if (!Broker.loggedIn) return
        // [stream]: Zerodha's orders and positions as last read (under 20 s, nothing sent since, the stream live: else read
        // now), and each price from the stream's fresh tick; a contract without one is quoted from Zerodha as before.
        // (20 Nov 2026: Zerodha's books unreadable - each lost exit's search still starts in the background (search only: a found or
        // not-found answer waits for a look that reads), so its failed reads count and Boss is told its state is unknown, at 15:10 again.)
        val (orders, pbook) = try {
            (if (stream) Broker.ordersRecon() else Broker.orders()).associateBy { it.id } to (if (stream) Broker.positionsRecon() else Broker.positionBook())
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            for (l in lostExits.values.toList()) runCatching { startLostSearch(l) }
            throw e
        }
        val net = pbook.net
        passIds.entries.removeAll { (id, v) -> if (orders.containsKey(id)) { passPlaced[v.first] = placedNow(v.first) - v.second; true } else false }
        // 22 Nov 2026 (B6 review): an own-cancel mark whose stop this read still shows working long after it - that cancel never took (the
        // book saved without it, so a restart never brings it back).
        if (ownCancels.keys.toList().count { orders[it]?.working == true && ownCancelSeenWorking(it, fresh = !stream) } > 0) runCatching { saveSoon(b) }
        val keys = open.mapNotNull { it.value.kite }.map { "NFO:$it" }.distinct()
        val q = runCatching { liveQuotes(keys, stream) }.getOrDefault(emptyMap())
        val drop = ArrayList<Int>()
        for ((i, p) in open) {
            val sym = p.kite ?: continue
            if (p.unconfirmed) {
                // 18 Nov 2026 (review): one whose order id was lost is searched for on a regular pass only, at most every 10 s (never on
                // a stream look: its order book and positions reads would spend the rate the open positions' exits need).
                if (p.entryOrderId == null && (stream || !settleDue(managerId(p), epochMs(t)))) continue
                val settled = runCatching { settleEntry(b, p, sym) }.getOrDefault(p)
                if (settled == null) drop += i else b.positions[i] = settled
                continue
            }
            // 20 Nov 2026 (audit PC; deep review F1): a lost exit holds only the SALE side of the part that sent it ([lostOf]): no new exit
            // ([exitLive]) and no stop put back (below) for it; while held that part's look waits (it has no stop, sells nothing, and is
            // never booked gone at the last price meanwhile). Every other trade on the contract keeps its full look. Past the hold, the
            // first look (any) starts a background search, at most every [LOST_EXIT_ASK_MS]; a later look acts on its answer - found:
            // booked as that part's exit from that look's order book; not found: its exit goes.
            // (A lost exit whose part is no longer open here - closed another way - is let go, said in the diary.)
            for (l in lostOn(sym)) if (b.positions.none { it.open && it.live && it.lostId == l.id } && lostExits.remove(l.id, l))
                runCatching { Diag.record("orb", "$sym: the lost exit's part is no longer open here; let go") }
            var lostSkip = false
            lostOf(p)?.let { lost ->
                if (lostExitHolds(lost)) { lostSkip = true; return@let }
                // (Any look only starts a background search - never the network under the lock; the next look acts on its answer.)
                val look = lostExitLook(lost)
                if (look?.hold == true) { lostSkip = true; return@let }
                val id = look?.found ?: return@let
                val found = look.lost ?: return@let
                lostSkip = true
                // (Booked from this look's own order book, never awaited: not finished, or not in it yet, the next look books it.)
                val booked = bookFoundRow(p, armOf(p.arm).label, sym, found.why, id, orders[id])
                if (booked != null) {
                    b.positions[i] = booked.first
                    booked.second?.let { b.positions += it }
                } else if (epochMs(now()) - found.askedMs in 0 until LOST_EXIT_FOUND_KEEP_MS) {
                    // (Still working - a market sale's protection turned into a resting limit: the rest is cancelled once, in the
                    // background, as an exit not finished in time always was; the next look books what filled.)
                    // (Asked again while it still works, at most every [LOST_EXIT_ASK_MS]: a cancel that failed is retried, never every tick.)
                    val ms = epochMs(now())
                    val ask = orders[id]?.working == true && ms - found.cancelAskedMs !in 0 until LOST_EXIT_ASK_MS
                    lostExits.putIfAbsent(found.id, found.copy(foundId = id, cancelAskedMs = if (ask) ms else found.cancelAskedMs))
                    if (ask) offLock.launch(SAFETY_LANE) { runCatching { Broker.cancel(id) } }
                } else {
                    runCatching { Diag.record("orb", "$sym: the lost exit found ($id) did not finish in Zerodha's book; let go") }
                    offLock.launch(SAFETY_LANE) { runCatching { Broker.cancel(id) } }
                    com.optionslab.app.work.Alerts.error("ORB live: the lost exit of $sym was found in Zerodha's order book ($id) but has not finished. " +
                        "The app reads Zerodha again and sells only what is still held; check Kite's order book.", "ORB live")
                }
            }
            if (lostSkip) continue
            val so = p.stopOrderId?.let { orders[it] }
            if (so != null && so.status == "COMPLETE") {
                val px = so.avg.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                // The stop the profit lock had moved up filled at Zerodha: that is the lock's exit (F1). 15 Nov: one at the stop rule's levels
                // is that rule's - every stop fill named by [stopWhy] (a live trade has no trail, breakeven or manager lock).
                val why = stopWhy(p)
                Notifier.orderFilled(app, "SELL", so.filled, sym, px, "Live", "${armOf(p.arm).label} · ${FIXED.words(why.replace('_', ' '))}", p.stopOrderId)
                b.positions[i] = p.copy(exit = px, exitTime = t, why = why, stopOrderId = null, charges = p.charges + kiteCharge("SELL", px, p.qty))
                continue
            }
            // A stop taken out at Zerodha (refused, or cancelled by hand or by Zerodha) leaves the position without one: say so.
            // 22 Oct (arms audit): not when the app's own 15:05 expiry square-off took it out to sell the position (it cancels an
            // arm's stop first): that is no stop lost, and the square-off sells it.
            // 16 Nov (review, Boss: "All the stops and profit locks should be on zerodha"): told apart by Zerodha's own words
            // ([com.optionslab.ira.ManualCoverRule.endedByExchange]) - ended by the exchange, its risk system or a rejection: a Liquidity
            // stop is placed again at Zerodha ([restExchangeStop]); cancelled by Boss (in Kite): respected, never put back by itself, and
            // the app watches the level itself. Each said as it is.
            // 16 Nov (Boss: "yes" to the ORB arms too): the same for every arm here (ORB and ORB Fresh trade live; any live trade of
            // the others too), not only Liquidity 15+5 - with the same guards, whatever "Keep a stop at the exchange" said.
            val ended = so != null && so.status in setOf("REJECTED", "CANCELLED")
            // (Review: a cancel the app made itself - an exit taking its stop out first - is never taken as Boss's.)
            // (Also any other of the app's own takers: a square-off's sale on the contract, Stop for today - never Boss's.)
            // (20 Nov 2026: a stop Boss's manual exit from the app took out - [exitMarks] - is the app's own too.)
            if (so != null && so.working) exitMarkSeenWorking(so.id)
            // (22 Nov 2026, B6 review: Stop for today no longer makes every cancel the app's own - only one the app marked (its exits with
            // Stop for today mark theirs, [cancelOwnStop]); an unmarked one follows the rule below like any other.)
            val appsOwn = ended && (ownCancel(so!!.id) || exitMarked(so.id) || p.appCancelled ||
                orders.values.any { it.symbol == sym && it.working && it.tag in SquareOffExit.TAGS } || SquareOffExit.inGap("NFO", sym, "MIS"))
            // (22 Nov 2026, B6 review, as the arms' live copies: a cancel with no mark of the app's own and blank or unrecognised words is
            // Boss's only while the market takes orders ([cancelInHours]); one outside market hours - the day's end - is Zerodha's.)
            val byExchange = ended && (com.optionslab.ira.ManualCoverRule.endedByExchange(so!!.status, so.message) || appsOwn || !cancelInHours(so))
            val level = p.stopTrigger?.let { "the stop at %.2f".format(Locale.ENGLISH, it) } ?: "the stop"
            // (Review: a stop Zerodha keeps refusing at each re-rest is said once, not at every try: said again only after a stop of
            // this trade was seen resting at Zerodha since ([endedSaid]).)
            if (so != null && so.working && so.id == p.stopOrderId) stopRested(p, sym)
            if (ended && !expiryClosing(p.symbol, sym, t, orders.values) && !appsOwn &&
                endedSaid.put(managerId(p), "${so!!.status}|${so.message}") != "${so.status}|${so.message}") com.optionslab.app.work.Alerts.error(
                "${armOf(p.arm).label}: the stop at Zerodha was ${so!!.status.lowercase()} (${so.message.ifBlank { "no reason given" }}). " + when {
                    !byExchange -> "It looks cancelled by you (in Kite), so the app does not put it back: it watches $level " +
                        "itself and sells there. Set a stop at Zerodha yourself if you want one there."
                    // (Review: no level it could rest at - no trigger, no lock: nothing is placed again.)
                    p.stopTrigger == null && !p.fixed && lockOrStop(p) == null -> "The app now watches the stop itself."
                    else -> "The app watches $level itself and places it again at Zerodha on its next check while the price allows it to rest " +
                        "there (not with Stop for today, at the square-off, or while you are exiting it by hand: those sell it)."
                }, "ORB live")
            // (Review: the app's own-cancel mark lasts only until Zerodha shows that stop again working - then a later cancel is Boss's to make.)
            val cur = if (ended) p.copy(stopOrderId = null, appCancelled = false,
                stopByYou = p.stopByYou || !byExchange).also { b.positions[i] = it }
                else if (p.appCancelled && so != null && so.working && !exitMarked(so.id)) p.copy(appCancelled = false).also { b.positions[i] = it } else p
            val held = net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.qty }
            val ltp = q["NFO:$sym"]?.last?.takeIf { it > 0 }
            ltp?.let { mark(p.symbol, it, t) }
            // Gone at Zerodha without the arm selling it: Zerodha's MIS square-off, or closed by hand.
            // 18 Nov 2026 (Boss, live: the Liquidity BANKNIFTY lot was booked closed on one positions read while Zerodha still held it -
            // its stop cancelled, nothing watching it): only once that is confirmed ([goneConfirmed]: sales outside the app in Zerodha's
            // order book that cover it, or 30 s of reads showing it gone - five minutes with no sale at all (review F3); a read with no row
            // for it never counts). Until then it is kept and its stop rests (moved up as usual); no stop is put back meanwhile. With no stop
            // resting, its exits (Stop for today and the session's end included) still try to sell it - each sale reads Zerodha's books again
            // first and sells only what is held; with a stop resting, every exit waits ([exitLive] takes no stop out on a read showing it gone,
            // 18 Nov 2026, audit B2).
            if (held > 0) { goneSince.remove(managerId(p)); goneNoRowSaid.remove(managerId(p)); goneAlertSaid.remove(managerId(p)) }
            if (held <= 0 && goneConfirmed(b, p, sym, net, orders.values, t)) {
                // Its stop must be confirmed out first: left resting, it would sell into a short.
                val sid = cur.stopOrderId
                if (sid != null) {
                    cancelOwnStop(sid, b)
                    if (runCatching { Broker.orderState(sid)?.status }.getOrNull() !in DONE) continue
                }
                // 22 Oct (arms audit): the app's expiry square-off ("iraalgoexpiry") sold it: said so, never "closed by you".
                val expiryClose = orders.values.any { it.symbol == sym && it.tag == "iraalgoexpiry" && it.filled > 0 }
                val px = ltp ?: cur.entry
                b.positions[i] = cur.copy(exit = px, exitTime = t, why = goneWhy(cur.day, t, expiryClose),
                    stopOrderId = null, charges = cur.charges + kiteCharge("SELL", px, cur.qty))
                // 19 Nov 2026 (audit batch 2, L1): the contract confirmed flat by these same rules - an unclaimed stop of these arms left on it
                // is cancelled on the next look without waiting again ([flatOrphanCheck]; picked up at the end of this pass).
                if (b.positions.none { it.open && it.live && it.kite == sym }) flatSince[sym] = epochMs(t) - com.optionslab.engine.orb.LiveMirror.SHORT_CONFIRM_SLOW_MS
                continue
            }
            // 18 Nov 2026: shown gone but not confirmed: while its stop rests at Zerodha, no exit of the app's own this pass (its sale would
            // take the resting stop out first and then find nothing held on the same bad read); the stop rests and is still moved up below.
            // (Review F4: with no stop resting, its own exits still go - see below.)
            val goneUnsure = held <= 0
            if (ltp == null) {
                // 22 Oct (arms audit): no quote at all used to hold everything, the 15:10 square-off and Boss's Stop for today
                // included (Zerodha's MIS auto square-off and the resting SL were then all that remained). Those go at market.
                val due = noPriceExit(armOf(cur.arm).hero, stopped, t.toLocalTime(), cur.day.isBefore(t.toLocalDate())) ?: continue
                if (goneUnsure && cur.stopOrderId != null && due !in ALWAYS_EXITS) continue
                val (done, rest) = exitLive(b, cur, sym, due, Pre(orders.values, net))
                b.positions[i] = done
                rest?.let { b.positions += it }
                continue
            }
            // The price ran through the stop's limit without it filling: sell at market instead.
            val tick = runCatching { Broker.spec("NFO", sym).tickSize }.getOrDefault(0.05)
            // Always (16 Nov, Boss's "All the stops and profit locks should be on zerodha": Liquidity 15+5 first, then every arm here -
            // it was the "Keep a stop-loss order at the exchange" setting, off unless chosen): a position whose stop is not resting at
            // Zerodha (it could not be placed, or the exchange or Zerodha refused or cancelled it) gets it placed again at the highest
            // level it may rest at on the regular pass - never on a fast look, at most once a minute - while the price is still above it.
            // (Review: never at or after the square-off or with Boss's Stop for today - those sell on this pass - and never beside another
            // sell working on the contract, a square-off's MARKET sale on its way: never two exits.)
            // (Review: held back only by this position's own exits - the 15:05 expiry square-off ([expiryClosing]), a working square-off
            // order ([SquareOffExit.TAGS]) or its own arm's exit sale still working ([ownSaleWorking]); another book's or Boss's resting
            // order never holds this position's stop back. Never a stop Boss cancelled themselves ([Position.stopByYou]).)
            if (regular && cur.stopOrderId == null && held > 0 && !stopped && !cur.stopByYou && t.toLocalTime().isBefore(OrbRules.SQUARE_OFF) &&
                !expiryClosing(cur.symbol, sym, t, orders.values) &&
                orders.values.none { it.symbol == sym && it.working && it.tag in SquareOffExit.TAGS } &&
                // (19 Nov 2026, final check: nor in the gap after a square-off's exit on it - a read can miss an order placed a moment ago.)
                !SquareOffExit.inGap("NFO", sym, "MIS") &&
                // (20 Nov 2026, audit PC: nor while a lost exit on it is neither found nor let go - it may still be selling; every trade's
                // level is still watched by the app meanwhile and sold there.)
                lostOf(cur) == null &&
                // (Nor for any trade on it while another part's lost sale there is unresolved or found but not yet booked - it may have
                // filled while the positions do not show it, and the stop is sized from them; the app watches every level meanwhile and
                // sells there. One known not found no longer holds it.)
                lostOn(sym).none { !it.notFound }) {
                // (19 Nov 2026, second review: beside its own working sale - or a stop of these arms resting unclaimed - nothing new rests,
                // but an unclaimed stop placed since its entry is still taken as its own: [restExchangeStop] with adoptOnly.)
                // (19 Nov 2026, review: a stop of these arms resting unclaimed is [orphanStop]'s to judge - taken, waited on, or a stop capped
                // beside it - never a reason by itself to place nothing.)
                val adoptOnly = ownSaleWorking(cur, sym, orders.values, b.positions.mapNotNull { it.stopOrderId }.toSet(), ownStops = false)
                val rested = runCatching { restExchangeStop(b, cur, sym, held, ltp, tick, orders.values, adoptOnly, positions = pbook) }.getOrNull()
                if (rested != null) { b.positions[i] = rested.first; rested.second?.let { b.positions += it }; continue }
            }
            val runThrough = cur.stopTrigger?.let { ltp < stopLimit(it, tick) } == true
            // The highest price since the entry (F2): every stream tick, else the 1-minute candles' highs - laddered arms only.
            val leg = b.legs?.let { l -> listOf(l.ce, l.pe) }?.firstOrNull { it.symbol == cur.symbol }
            // Live keeps the stream's best price only (22 Oct: [bestSince]'s gap read is paper only for now; TODO.md X7).
            val high = if (ladderTarget(cur) != null && leg != null) runCatching { Paper.highSince(leg, cur.entryTime) }.getOrNull() else null
            // The lock the best price BEFORE this look earned, on the tick: when the stop already rests there, Zerodha sells it.
            val lockNow = ladderTarget(cur)?.let { ProfitLock.level(cur.entry, it, cur.peak ?: cur.entry, ProfitLock.roundTripPerUnit(cur.entry, cur.qty)) }
                ?.let { ProfitLock.onTick(it, tick) }
            val lockResting = lockNow != null && cur.stopOrderId != null && (cur.stopTrigger ?: Double.NEGATIVE_INFINITY) >= lockNow - 1e-9
            val (laddered, locked) = ladder(cur, ltp, high)
            // 16 Nov (review): a live Liquidity rule trade's best price also takes the option's highs after its entry minute (the shared
            // [bestSince]: the stream's kept ticks, else the candles - those only on the regular pass), as the R51 / R72 live copies do.
            // (Review: never a read on the look itself: [RuleHighs] reads in the background, one at a time, and the look takes its last high.)
            val ruleHigh = if (cur.fixed) {
                if (regular) runCatching { Paper.contractOf(cur.symbol) }.getOrNull()?.let { pc -> RuleHighs.ask("orb:${managerId(cur)}", pc, cur.entryTime) }
                RuleHighs.peek("orb:${managerId(cur)}")
            } else null
            val seen = heldRange(laddered, ltp).let { s0 -> ruleHigh?.takeIf { it > (s0.peak ?: s0.entry) }?.let { s0.copy(peak = it) } ?: s0 }
            if (seen !== cur) b.positions[i] = seen
            val why = when {
                stopped -> "operator_stop"
                !t.toLocalTime().isBefore(OrbRules.SQUARE_OFF) -> "session_end"
                // Liquidity 15+5: its 15% stop rests at Zerodha; the app sells only if that is gone or the price ran through it.
                // Under a lock variant, an earned lock not resting there (a refused modify, no stop order) sells as the ORB arms'
                // (review G2-4).
                armOf(cur.arm).liquidity -> LiquidityRules.appSale(stopResting = cur.stopOrderId != null, runThrough = runThrough,
                    atStop = cur.stopTrigger?.let { ltp <= it } == true, locked = locked, lockResting = lockResting)
                    ?.let { if (it == "stop") fixedWhy(cur) ?: it else it }
                    // 15 Nov (Boss's exception): the stop rule's stop reached while the resting stop is lower (it could not rest or be
                    // moved there yet: the price was too near, or Zerodha refused it) - the app sells itself; [exitLive] takes that stop
                    // out first, then one MARKET sell (never two exits).
                    ?: ruleWhy(cur, tick).takeIf { fixedBelow(cur, ltp, tick) }
                // The lock rests at Zerodha as the moved-up stop (F1); the app sells at market only when it is not resting
                // there (a refused modify, no stop order) or the price ran through its limit.
                locked && (!lockResting || runThrough) -> "profit_lock"
                // Each arm's own target (F5: ORB Sweep +80, not the ORB's +40), on the price now or a high since the entry (F2).
                targetHit(cur, ltp, high) -> "target"
                else -> OrbRules.exitReasonFor(armOf(cur.arm), cur.entry, ltp, t, paramsOf(cur)).takeIf { it == "stop" && (cur.stopOrderId == null || runThrough) }
            } ?: run {
                // Held: the profit lock moves the resting SL at Zerodha up to what the best price has earned (F1).
                b.positions[i] = raiseLiveStop(seen, sym, so, ltp, tick)
                null
            } ?: continue
            // (Boss's Stop for today and the session's end are passed on to [exitLive], which with a stop resting waits while the read shows
            // it gone (18 Nov 2026, audit B2: never a stop taken out for a sale that cannot go; the next pass reads again). 18 Nov 2026,
            // review F4: every exit of its own goes while no stop rests at Zerodha - there is no stop to take out, and nothing else would
            // sell it at its level; [exitLive] reads Zerodha again through the exit coordinator and sells only what is held, nothing if gone.)
            if (goneUnsure && cur.stopOrderId != null && why !in ALWAYS_EXITS) { b.positions[i] = raiseLiveStop(seen, sym, so, ltp, tick); continue }
            val (done, rest) = exitLive(b, seen, sym, why, Pre(orders.values, net), ltp)
            b.positions[i] = done
            rest?.let { b.positions += it }
        }
        drop.sortedDescending().forEach { b.positions.removeAt(it) }
        // 19 Nov 2026 (audit batch 2, L1): from this pass's orders read, an unclaimed stop of these arms on a contract where no trade of this
        // book is open now (a trade closed this pass, or one never saved) is followed until it ends or the contract is confirmed flat.
        if (regular && !stream) runCatching { noteFlatOrphans(b, orders.values, t) }
        // 18 Nov 2026 (audit: B3): on the regular pass, a contract Zerodha holds LESS of than the live trades on it (confirmed) has its
        // trades and resting stops cut down to what is held ([shrinkToHeld]) - after the exits above, from this pass's reads.
        if (regular) runCatching { shrinkToHeld(b, t, net, orders, q) }
    }

    /**
     * 20 Nov 2026 (delta review): the lost stops ("orbstop:" keys) no open trade asks after. Its trade closed: looked for at once, and
     * found resting, cancelled as the app's own and confirmed - cancelled again on every regular pass until Zerodha shows it ended
     * ([sweepCancels]; as the arms' live copies take theirs out); given up on, it is gone - a stale key holds back a matching orphan's
     * adoption ([Broker.lostMayBe]) only for the search's two minutes. A split left no open part of its size (looked at once past
     * [LOST_SWEEP_AGE_MS], the moment its trade's own look may still take it): looked for; found, it is the stop of a part of its size
     * with none (whose own lost stop is not looked for), else left to the parts' own orphan check
     * ([orphanStop]: taken and resized by a part with none - no longer held back as a lost reply; counted as a working sale meanwhile).
     */
    private suspend fun sweepLostStops(b: Book) {
        for (key in Broker.lostKeys("orbstop:")) {
            val f = key.removePrefix("orbstop:").split(":")
            if (f.size < 4) continue
            val fill = f[f.size - 2].toDoubleOrNull() ?: continue
            val sym = f[f.size - 3]
            val label = f.dropLast(3).joinToString(":")
            val open = b.positions.filter { it.open && it.live && it.kite == sym && it.entry == fill && armOf(it.arm).label == label }
            if (open.any { lostKeyOf(b, it, label, sym) == key }) continue
            // (Looked for at once when nothing of this arm is open on the contract at all - its trade closed; otherwise only once past the
            // moment a trade's own look may still take it: a row settling, its entry not yet the fill, a part of a split.)
            val anyOnIt = b.positions.any { it.open && it.live && it.kite == sym && armOf(it.arm).label == label }
            if (anyOnIt && (Broker.lostAgeMs(key) ?: 0L) < (lostSweepAgeMsForTest ?: LOST_SWEEP_AGE_MS)) continue
            val l = Broker.lookLost(key) as? Broker.LostLook.Found ?: continue
            if (b.positions.any { it.open && it.stopOrderId == l.id }) continue
            if (open.isEmpty()) {
                // (Its trade closed: found resting, taken out as the app's own - never left on a closed trade; one that filled is said.)
                val st = runCatching { Broker.orderState(l.id) }.getOrNull()
                if (st != null && st.status in DONE) {
                    if (st.filled > 0) com.optionslab.app.work.Alerts.error("$label: a stop ${l.id} on $sym whose answer was lost filled ${st.filled} at " +
                        "Zerodha after its trade had closed. Check $sym's position in Kite.", "ORB live")
                    continue
                }
                Broker.claimStop(l.id)
                sweepCancel(l.id, "$label|$sym")
                runCatching { Diag.record("orb", "$label: a lost stop ${l.id} on $sym found after its trade closed: taken out") }
                continue
            }
            val i = b.positions.indexOfFirst { it.open && it.live && it.kite == sym && it.entry == fill && armOf(it.arm).label == label &&
                it.stopOrderId == null && it.qty == l.qty && !Broker.lostPending(orbLostKey(label, sym, fill, it.qty)) &&
                // (Review of the b5-orders merge, C1: never a part whose own lost sale is pending.)
                lostOf(it) == null }
            // (A part of its size held for its own lost sale: the stop rests as its cover, left to its own look - nothing said each pass.)
            if (i < 0 && open.any { it.stopOrderId == null && it.qty == l.qty && lostOf(it) != null }) continue
            if (i >= 0) {
                b.positions[i] = b.positions[i].copy(stopOrderId = l.id, stopTrigger = l.trigger ?: b.positions[i].stopTrigger)
                runCatching { Strategies.tagOwnerSoon("kite:${l.id}", "$label · stop") }
                Broker.claimStop(l.id); placed(sym, l.qty, l.id)
                runCatching { Diag.record("orb", "$label: a lost stop ${l.id} on $sym found after its trade was split: taken by the part of its size") }
            } else runCatching { Diag.record("orb", "$label: a lost stop ${l.id} on $sym found after its trade was split, with no part of its " +
                "size: left to the parts' orphan check") }
        }
        // (A closed trade's found stop: cancelled as the app's own and confirmed; until Zerodha shows it ended, cancelled again here on
        // every regular pass, Boss told after a few tries - never left resting unwatched on a contract nothing here holds.)
        val todayIso = runCatching { today().toString() }.getOrDefault("")
        for ((id, what) in synchronized(sweepCancels) { sweepCancels.toMap() }) {
            if (b.positions.any { it.open && it.stopOrderId == id }) { synchronized(sweepCancels) { sweepCancels.remove(id) }; sweepTries.remove(id); sweepDays.remove(id); continue }
            // (22 Nov 2026, B6 review: one from an earlier day - an intraday stop Zerodha ended at that day's close - is let go, said.)
            if (sweepDays[id]?.let { it != todayIso } == true) {
                synchronized(sweepCancels) { sweepCancels.remove(id) }; sweepTries.remove(id); sweepDays.remove(id)
                runCatching { Diag.record("orb", "${what.substringBefore('|')}: a closed trade's stop $id on ${what.substringAfter('|')} from an earlier day: no longer cancelled") }
                continue
            }
            cancelOwnStop(id, b)
            val st = runCatching { Broker.orderState(id) }.getOrNull()
            if (st != null && st.status in DONE) {
                synchronized(sweepCancels) { sweepCancels.remove(id) }; sweepTries.remove(id); sweepDays.remove(id)
                if (st.filled > 0) com.optionslab.app.work.Alerts.error("${what.substringBefore('|')}: a stop $id on ${what.substringAfter('|')} whose answer was " +
                    "lost filled ${st.filled} at Zerodha after its trade had closed. Check the position in Kite.", "ORB live")
                continue
            }
            val n = (sweepTries[id] ?: 0) + 1
            sweepTries[id] = n
            if (n == 3) com.optionslab.app.work.Alerts.error("${what.substringBefore('|')}: a stop $id on ${what.substringAfter('|')}, left by a closed trade, " +
                "could not be cancelled yet; the app keeps trying. Check the order book in Kite.", "ORB live")
        }
    }
    private const val LOST_SWEEP_AGE_MS = 30_000L
    /**
     * 20 Nov 2026 (delta review): closed trades' found lost stops being cancelled (id to "label|contract") and the tries.
     * 22 Nov 2026 (B6 review: memory only, a restart forgot them - and their own-cancel marks, kept in the book now, keep the flat contract's
     * check off them - so such a stop could be left resting with nothing watching it): kept in the book with the day each was taken out
     * ([sweepDays], [encodeBook]), taken up again when it is read the same day - claimed again ([Broker.claimStop]) - and cancelled and
     * confirmed on the next regular looks; one from an earlier day is let go. The tries are memory only (counted again after a restart).
     */
    private val sweepCancels = HashMap<String, String>()
    /** 22 Nov 2026 (B6 review): the day (ISO) each of [sweepCancels] was taken out ([sweepCancel]). Memory, and kept with it in the book. */
    private val sweepDays = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 22 Nov 2026 (B6 review): [id] ([what]: "label|contract") taken out as a closed trade's found lost stop today ([sweepCancels]). */
    private fun sweepCancel(id: String, what: String) {
        runCatching { today().toString() }.getOrNull()?.let { sweepDays[id] = it }
        synchronized(sweepCancels) { sweepCancels[id] = what }
    }
    private val sweepTries = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** 20 Nov 2026 (delta review): the longest a stop-level exit waits for its own stop whose answer was lost ([exitLive]). */
    private const val LOST_EXIT_WAIT_MS = 20_000L
    /** TEST SEAM: [LOST_SWEEP_AGE_MS] shortened. */
    @Volatile internal var lostSweepAgeMsForTest: Long? = null

    /**
     * 18 Nov 2026 (audit: B3): by contract, when (market-clock ms) Zerodha's positions first showed it holding less than the live trades on
     * it with no sale yet seen to explain it ([shrinkToHeld]). Memory only (a restart starts the count again: later, never sooner); bounded.
     */
    private val shortSince = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 19 Nov 2026 (coordinator): this process's cold-start sweep ([coldSweep]) has run to its end. Memory only: a new process sweeps again. */
    @Volatile private var coldSwept = false

    /** 19 Nov 2026 (review): when this process last erased the arms' book (market clock, ISO): a new book carries it ([Book.sweepFrom]). */
    @Volatile private var erasedAt: String? = null

    /** 19 Nov 2026 (review): the sweep on the looks is not asked again before this (market-clock ms) after one that could not finish. */
    @Volatile private var coldNextAt = 0L

    /**
     * 19 Nov 2026 (review): the arms a lot taken back with no arm known could have been ([coldSweep]'s safety candidates): an entry of any
     * of them on that index right after is refused too - the lot may be its own. Memory only.
     */
    private val coldSafety: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private const val COLD_RETRY_MS = 30_000L

    /**
     * 19 Nov 2026 (final check): by contract, what this pass placed as stops so far ([restExchangeStop], [coldSweep]) - the pass's own order
     * read does not show them yet, so each later cap counts them. Cleared at each pass (the arms' lock is held through it).
     */
    private val passPlaced = HashMap<String, Int>()
    /** The orders behind [passPlaced] (id -> contract, quantity): each comes off once a fresh order read of this pass shows it. */
    private val passIds = HashMap<String, Pair<String, Int>>()
    private fun placedNow(sym: String) = passPlaced[sym] ?: 0
    /** A stop of [qty] placed on [sym] this pass (order [id]); negative: one taken and cut below its size in the read. */
    private fun placed(sym: String, qty: Int, id: String? = null) {
        if (qty == 0) return
        passPlaced[sym] = placedNow(sym) + qty
        if (id != null) passIds[id] = sym to ((passIds[id]?.second ?: 0) + qty)
    }

    /** 19 Nov 2026 (final check): by lost entry ([managerId]), when (market-clock ms) it first waited on a still-working buy ([strayBuyWorking]). */
    private val strayWaitSince = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val STRAY_WAIT_MS = 90_000L

    /** 19 Nov 2026 (final check): contracts whose held lot the sweep could not account for, said once per process ([coldSweep]). */
    private val coldUnexplainedSaid: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** 19 Nov 2026 (coordinator): an entry went before the sweep could run - said once per process ([enterLiveNow]). */
    @Volatile private var coldGateSaid = false

    /**
     * 19 Nov 2026 (coordinator): when (market-clock ms) this process's sweep first could not run to its end, and whether Boss was told it
     * has kept failing for over a minute ([COLD_FAIL_SAY_MS]): a buy of these arms from today may be held with no row and no stop.
     */
    @Volatile private var coldFailSince: Long? = null
    @Volatile private var coldFailSaid = false
    private const val COLD_FAIL_SAY_MS = 60_000L

    /** 19 Nov 2026 (coordinator): a live LIMIT entry's wait for its fill under the arms' lock ([enterLiveNow]). */
    private const val ENTRY_WAIT_MS = 5_000L

    /** One sweep that did not run to its end at [t]: said once when that has gone on for over a minute since the first. */
    private fun coldFailed(t: LocalDateTime) {
        val ms = epochMs(t)
        val first = coldFailSince ?: ms.also { coldFailSince = it }
        if (!coldFailSaid && ms - first > COLD_FAIL_SAY_MS) {
            coldFailSaid = true
            runCatching { com.optionslab.app.work.Alerts.error("ORB live: for over a minute since their first try after the app started, the arms could not check Zerodha's " +
                "orders for a buy of theirs from before the restart - a lot may be held with no stop and nothing watching it. Check Trade and Kite.", "ORB live") }
        }
    }

    /**
     * 19 Nov 2026 (coordinator: the entry's intent is written beside its POST, so a process that dies within that write leaves a lot at
     * Zerodha that no row here holds - no stop, nothing watching it, and its bar not marked decided): on this process's first regular look
     * (and before its first live entry, [enterLiveNow]), today's filled "iraorb" BUY orders that no row of this book names as its entry
     * ([Position.entryOrderId]) are taken back - only what Zerodha holds on the contract (MIS) that nothing else explains, as a lost entry's
     * settle counts it ([settleEntry]): this book's open live trades on it and the arms' live copies' open buys of it, never more than was
     * bought today by these arms' own orders (another book's or Boss's buys are theirs), never what a sale already filled took
     * ([ExitCoordinator.held]), each fill claimed once across both books ([ArmLive.takeLost]); newest order first, in whole lots. Never
     * one labelled for another book, nor on a contract where a lost-id row of this book waits (its own settle takes it).
     * Each is the arm its owner label names ("<arm> · entry"); with no label, the one arm switched on live that trades that index (the
     * ORB family on BANKNIFTY, a Liquidity book on its own index), if exactly one is; else (unknown) a Liquidity book of that index with a
     * SAFETY stop at -15%, every such arm's bars marked decided. Its stop: an unclaimed stop of these arms taken first ([orphanStop]); else
     * placed at once - not from 15:10, with Stop for today or at the 15:05 expiry square-off (the same pass sells it). It keeps its arm's
     * rules (ladder, Boss's exit rule and lock) as a new entry would. The bars of the hour before its order are marked decided for its
     * arm (the same bar is never bought again). Said loudly. True when it ran to its end; false (asked again next look; a live entry
     * meanwhile goes, logged in shadow - [enterLiveNow]; over a minute of it is said - [coldFailed]) when a read failed, the arms' live copies' book is not read or is unreadable, one of their entries is in flight, or a buy
     * could not be judged yet (its contract not in the instrument list, no fill price, still working).
     */
    private suspend fun coldSweep(b: Book, t: LocalDateTime, stopped: Boolean): Boolean {
        val orders = runCatching { Broker.orders() }.getOrNull() ?: return false
        // 19 Nov 2026 (audit batch 2, L1): a stop of these arms left resting with no trade (a crash before its trade was saved) is followed.
        runCatching { noteFlatOrphans(b, orders, t) }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        // (19 Nov 2026, review: after "Erase everything" today, only these arms' orders placed after it count - never a lot from before.)
        val from = b.sweepFrom?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }?.takeIf { it.toLocalDate() == t.toLocalDate() }
        // (An order time that cannot be read after an erase today is not counted - never a lot from before it taken back.)
        fun counts(o: Broker.OrderRow) = o.tag == "iraorb" && o.exchange == "NFO" && o.product == "MIS" &&
            (from == null || runCatching { !LocalDateTime.parse(o.placedAt, fmt).isBefore(from) }.getOrDefault(false))
        val claimed0 = b.positions.mapNotNull { it.entryOrderId }.toSet()
        if (orders.none { counts(it) && it.side == "BUY" && it.id !in claimed0 && (it.filled > 0 || it.working) }) return true
        val owners = runCatching { Strategies.owners() }.getOrNull() ?: return false
        val armLive = ArmLive.view.value
        val byLabel = ALL_ARMS.associateBy { it.label }
        fun ours(o: Broker.OrderRow) = owners["kite:${o.id}"]?.let { it.endsWith(" · entry") && byLabel[it.removeSuffix(" · entry")] != null } ?: true
        val claimed = b.positions.mapNotNull { it.entryOrderId }.toSet()
        val lostOn = b.positions.filter { it.open && it.live && it.unconfirmed && it.entryOrderId == null }.mapNotNull { it.kite }.toSet()
        var again = false
        // (19 Nov 2026, review: one still working - part filled or not at all - is cancelled as the app's own and its end confirmed, then
        // what filled is judged; not ended yet: asked again (backed off). Never on a contract where a lost-id row's own order may be it.)
        val candidatesAll = orders.filter { counts(it) && it.side == "BUY" && it.id !in claimed && (it.filled > 0 || it.working) && ours(it) }
        if (candidatesAll.isEmpty()) return true
        if (!armLive.loaded || armLive.unreadable || ArmLive.entryInFlight) return false
        val mine = candidatesAll.mapNotNull { o ->
            if (o.symbol in lostOn) { again = true; return@mapNotNull null }
            if (!o.working) return@mapNotNull o
            runCatching { Broker.cancel(o.id, o.variety) }
            val st = runCatching { Broker.orderState(o.id) }.getOrNull()
            if (st == null || st.status !in DONE) { again = true; null } else o.copy(status = st.status, filled = st.filled, avg = st.avgPrice.takeIf { it > 0 } ?: o.avg)
        }
        val buys = mine.filter { it.filled > 0 }
        if (buys.isEmpty()) return !again
        // (The orders as they are now: a cancelled one's last fill counted, not the read's.)
        val latest = mine.associateBy { it.id }
        val ordersNow = orders.map { latest[it.id] ?: it }
        val pb = runCatching { Broker.positionBook() }.getOrNull() ?: return false
        val net = pb.net
        val instruments = Broker.cachedInstruments() ?: runCatching { Broker.instruments() }.getOrNull() ?: return false
        val today = t.toLocalDate()
        var changed = false
        for ((sym, list) in buys.groupBy { it.symbol }) {
            val ins = instruments.firstOrNull { it.tradingSymbol == sym } ?: run { again = true; null } ?: continue
            val lot = ins.lotSize.coerceAtLeast(1)
            val rows = net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }
            // (20 Nov 2026, audit PC: the positions-lag guard acts whatever its switch says - it only ever counts less as held.)
            val lagFree = runCatching { ExitCoordinator.held("NFO", sym, "MIS", "ORB live", ordersNow, first = pb, forceLag = lagForced()) }.getOrNull()
                ?: run { again = true; null } ?: continue
            val held = minOf(rows.sumOf { it.qty }, lagFree)
            if (held <= 0) continue
            val boughtToday = com.optionslab.engine.orb.LiveMirror.boughtTodayCap(rows.map { it.dayBuyQty }, rows.sumOf { it.overnight }, held,
                ordersNow.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side == "BUY" && it.tag != "iraorb" }.sumOf { it.filled })
            // (19 Nov 2026, review: never more than these arms' own net on it today - their buys less their sales, after an erase only its
            // own - less what this book's open trades hold and what its trades closed outside the app today took: Boss's, Pine's or a
            // strategy's lot on the contract is never taken for one of these arms'.)
            // (Trades closed outside the app count only for what sales outside these arms explain - one whose own sale filled while the app
            // was down, booked as closed outside, is already in their sales.)
            val outsideSold = ordersNow.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side == "SELL" && it.tag != "iraorb" }.sumOf { it.filled }
            val closedOutside = b.positions.filter { !it.open && it.live && it.kite == sym && it.exitTime?.toLocalDate() == today && it.why in OUTSIDE_WHYS }.sumOf { it.qty }
            var net = ordersNow.filter { it.symbol == sym && counts(it) }.sumOf { if (it.side == "BUY") it.filled else -it.filled } -
                b.positions.filter { it.open && it.live && it.kite == sym }.sumOf { it.qty } - minOf(closedOutside, outsideSold)
            var explained = b.positions.filter { it.open && it.live && it.kite == sym }.sumOf { it.qty } +
                // (Review F6, as the settle: never a lost entry of theirs still searched for - it may hold nothing, and must not hide this.)
                armLive.openReal.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side > 0 && !(it.unconfirmed && it.entryOrderId == null) }.sumOf { it.qty }
            for (o in list.sortedByDescending { it.placedAt }) {
                val owner = owners["kite:${o.id}"]
                if (owner != null && !owner.endsWith(" · entry")) continue                 // another book's
                val named = owner?.removeSuffix(" · entry")?.let { byLabel[it] }
                if (owner != null && named == null) continue                                 // labelled for no arm of this book
                val fill = o.avg.takeIf { it > 0 } ?: rows.singleOrNull()?.avg?.takeIf { it > 0 } ?: run { again = true; null } ?: continue
                var loose = 0
                val qty = ArmLive.takeLost("orb", "NFO", sym, "MIS") { other ->
                    loose = com.optionslab.engine.orb.LiveMirror.unexplainedQty(held, boughtToday, explained + other, o.filled, lot)
                    minOf(loose, net.coerceAtLeast(0) / lot * lot) }
                if (qty <= 0) {
                    // (19 Nov 2026, final check: held and explained by nothing, but more than these arms' own orders today account for -
                    // not taken back, never silent: said once.)
                    if (loose > 0 && coldUnexplainedSaid.add(sym)) runCatching { com.optionslab.app.work.Alerts.error("ORB live: Zerodha holds $loose of $sym " +
                        "that no trade here or of the arms' live copies explains, more than these arms' own orders today account for - not taken " +
                        "back as one of their trades. Check $sym in Kite: it may have no stop.", "ORB live") }
                    break
                }
                // (The arm: its label; else the one live arm on this index; else unknown - a Liquidity book of the index, a safety stop.)
                val candidates = ALL_ARMS.filter { a -> !a.paperOnly && !a.hero && b.armed[a.source] == true && b.liveOk[a.source] == true &&
                    (if (a.liquidity) LiquidityRules.underlyingOf(a) == ins.name else ins.name == OrbRules.UNDERLYING) }
                val arm = named ?: candidates.singleOrNull()
                val safety = arm == null
                val a = arm ?: LiquidityRules.BOOKS.firstOrNull { LiquidityRules.underlyingOf(it) == ins.name } ?: LiquidityRules.ARM15
                val at = runCatching { LocalDateTime.parse(o.placedAt, fmt) }.getOrNull() ?: t
                val trig = when {
                    safety || a.liquidity -> LiquidityRules.stopTrigger(fill, if (safety) LiquidityRules.PREMIUM_STOP else liqParamsOf(a.source, today, true).stopPct)
                    else -> OrbRules.stopTrigger(fill, paramsOf(a, today, live = true).stopPoints) ?: LiquidityRules.stopTrigger(fill, LiquidityRules.PREMIUM_STOP)
                } ?: com.optionslab.engine.Kite.onTick(fill * (1 - LiquidityRules.PREMIUM_STOP), ins.tickSize, com.optionslab.engine.Kite.Side.BUY)
                val paperSym = ins.name + ins.expiry.format(java.time.format.DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH)).uppercase(Locale.ENGLISH) +
                    com.optionslab.engine.fmtG(ins.strike) + ins.right.name
                // Its stop: an unclaimed one of these arms taken first (always - so a sale this pass takes it out as its own); else placed,
                // never from 15:10, with Stop for today or at the expiry square-off (the same pass sells it); unclear: none now, watched.
                val mayRest = !stopped && t.toLocalTime().isBefore(OrbRules.SQUARE_OFF) && !expiryClosing(paperSym, sym, t, orders) &&
                    !SquareOffExit.inGap("NFO", sym, "MIS")
                var stopId: String? = null
                var stopAt: Double = trig
                // (19 Nov 2026, review / final check: never a stop - taken or placed - for more than is held less every other working sale on
                // the contract (an unclaimed stop of these arms left out where it may be taken; stops this pass placed counted), as
                // [restExchangeStop]; the trade split when less; none left: no stop, said each minute.)
                val sells = orders.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side == "SELL" && it.working && !ownCancel(it.id) }
                val stopsHeld = b.positions.mapNotNull { it.stopOrderId }.toSet()
                val othersAll = sells.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) } + placedNow(sym)
                val othersButOrphans = sells.filter { !(it.tag == "iraorb" && (it.type == "SL" || it.type == "SL-M") && it.id !in stopsHeld) }
                    .sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) } + placedNow(sym)
                val want = minOf(qty, held - othersButOrphans).coerceAtLeast(0) / lot * lot
                var covered = 0
                var capLeftNone = false
                // (22 Nov 2026, B6 review: its lost stop - sent for more under this key - found and taken out after part of it filled.)
                var stopSoldPart: Fit.Filled? = null
                val so = if (want > 0) orphanStop(b, a.label, sym, at, want, ins.tickSize, orders, at = if (mayRest) trig else null, entryOid = o.id) else Orphan.None
                when (so) {
                    is Orphan.Took -> {
                        stopId = so.id; stopAt = so.trigger; covered = want
                        orders.firstOrNull { it.id == so.id }?.let { r -> placed(sym, want - com.optionslab.engine.risk.ExitQty.remaining(r.qty, r.filled, r.pending), so.id) }
                    }
                    Orphan.None, Orphan.Other -> if (mayRest) {
                        covered = minOf(qty, held - othersAll).coerceAtLeast(0) / lot * lot
                        capLeftNone = covered <= 0
                        if (covered > 0) {
                            val r = placeStop(a.label, sym, covered, lot, ins.tickSize, fill, knownKite(b) + o.id, liquidity = a.liquidity || safety, at = trig,
                                lostKey = orbLostKey(a.label, sym, fill, qty))      // (the row's own quantity: what the next look asks)
                            stopSoldPart = r.sold
                            val (id, tr, _, cov) = r
                            stopId = id; stopAt = tr ?: trig
                            if (id != null) { covered = cov; placed(sym, covered, id) }
                        }
                    }
                    else -> {}
                }
                val fixed = a.liquidity && StepTrailExits.forEntry(a.source)
                val row = stamped(Position(a.source, paperSym, ins.right.name, qty, fill, at, at.withSecond(0).withNano(0), o.id, stopId, stopAt,
                    charges = kiteCharge("BUY", fill, qty), live = true, kite = sym, ladder = ladderOn(a, today, true),
                    lot = lot.takeIf { a.liquidity }, fixed = fixed, lock = fixed && StepTrailExits.lockOn(), entryQty = qty))
                val soldPart = stopSoldPart
                if (soldPart != null) {
                    // (That part booked closed at the stop, the rest with none - it rests one on a later look.)
                    val (closed, left) = stopSold(row, soldPart, sym)
                    b.positions += closed
                    left?.let { b.positions += it }
                } else if (stopId != null && covered in 1 until qty) {
                    val part = covered.toDouble() / qty
                    b.positions += row.copy(qty = covered, charges = row.charges * part)
                    b.positions += row.copy(qty = qty - covered, stopOrderId = null, charges = row.charges * (1 - part))
                } else b.positions += row
                if (capLeftNone) coverSay(row, "${a.label}: no stop can rest at Zerodha for $sym taken back at the start - other " +
                    "working sales already cover all it holds; they are its only cover, and the app watches its level. Check $sym in Kite.")
                if (safety) coldSafety += candidates.map { "${it.source}|${ins.name}" }
                for (d in (if (safety) candidates + a else listOf(a)).distinct()) {
                    val spent = b.decided.getOrPut("${d.source}|$today") { HashSet() }
                    var m = at.withSecond(0).withNano(0).minusMinutes(60)
                    while (!m.isAfter(at)) { spent += m.toString(); m = m.plusMinutes(1) }
                }
                if (owner == null) runCatching { Strategies.tagOwner("kite:${o.id}", "${a.label} · entry") }
                explained += qty
                net -= qty
                changed = true
                runCatching { Diag.record("orb", "${a.label}: cold start took back buy ${o.id} of $sym ($qty @ %.2f) that no trade held".format(Locale.ENGLISH, fill)) }
                com.optionslab.app.work.Alerts.error((if (safety) "ORB live: a buy of these arms on $sym (order ${o.id}) was found at Zerodha with no trade here and no arm " +
                    "known for it: $qty taken back with a SAFETY stop at -15%" else "${a.label}: its buy of $sym (order ${o.id}) was found at Zerodha with no trade " +
                    "here (the app stopped as it was sent): $qty taken back") + (if (stopId != null) ", its stop resting at Zerodha at %.2f.".format(Locale.ENGLISH, stopAt)
                    else ", no stop placed yet - the app watches it.") + " Check it in Trade.", "ORB live")
            }
        }
        if (changed) runCatching { saveSoon(b) }
        return !again
    }

    /** 19 Nov 2026 (second review): the order tags of the app's other books ([shrinkToHeld] cuts on their contracts only on a sale proof). */
    private val OTHER_APP_TAGS = setOf("irapine", "iraalgostrat", "iraprotect", "iraarm")

    /**
     * 18 Nov 2026 (audit: B3, the resting stop was never shrunk when Zerodha held less than the book's quantity on a contract - only "held
     * at 0" was ever looked at): Zerodha's holding of each contract (MIS, a row listed and above 0: nothing held is [goneConfirmed]'s) is
     * compared with this book's open live trades on it plus the arms' live copies' open buys of it ([ArmLive.view], memory). When it is
     * smaller and that is CONFIRMED - as for a trade shown gone: sales outside the app in the order book that cover the difference, or the
     * reads agreeing for 30 s (some exit fill not booked yet) or five minutes (none) - this book's trades are cut, newest first, to what
     * Zerodha leaves them: each cut trade's resting stop is first modified down to what it keeps ([com.optionslab.engine.risk.ExitQty.shrinkTo],
     * as [Protections] does; one Zerodha will not reduce is taken out and confirmed out - the price check rests it again for what is held,
     * [restExchangeStop]), then the part gone is booked closed as a trade closed outside the app ([goneWhy]) and the rest stays open with
     * its stop. Never a cut while its stop's state is unknown or not yet out; never one that would close every trade of this book on the
     * contract (the other book may be the one short: the gone check and Boss decide that). The pass's reads only; no read on a quiet contract.
     */
    private suspend fun shrinkToHeld(b: Book, t: LocalDateTime, net: List<Broker.Position>, orders: Map<String, Broker.OrderRow>,
                                     q: Map<String, Broker.Quote>) {
        // (Only once the arms' live copies' book is read: what they hold on a contract counts as explained.)
        val armLive = ArmLive.view.value
        if (!armLive.loaded) return
        if (shortSince.size > 200) shortSince.clear()
        val today = t.toLocalDate()
        val mine = b.positions.withIndex().filter { it.value.open && it.value.live && !it.value.unconfirmed && it.value.kite != null }.groupBy { it.value.kite!! }
        for ((sym, list) in mine) {
            val rows = net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }
            val held = rows.sumOf { it.qty }
            if (rows.isEmpty() || held <= 0) { shortSince.remove(sym); continue }
            // (The other book's open buys of it, still settling ones left out: fewer explained here only ever cuts less.)
            val others = armLive.openReal.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side > 0 && !it.unconfirmed }.sumOf { it.qty }
            val ours = list.sumOf { it.value.qty }
            val short = ours + others - held
            val share = held - others
            if (short <= 0 || share <= 0) { shortSince.remove(sym); continue }
            // (A sale on it still working with a part filled - a trade's own stop part filled, any book's - is whose it is: never taken for
            // an outside one and charged to another trade; asked again once it ends and its book has booked it.)
            if (orders.values.any { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side == "SELL" && it.working && it.filled > 0 }) continue
            // Confirmed as a trade shown gone is ([goneConfirmed]): the order book's sales outside the app, netted over the day.
            val onIt = orders.values.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.filled > 0 }
            val known: Set<String> = if (onIt.none { it.tag.isBlank() }) emptySet() else knownKite(b).toSet()
            // (19 Nov 2026, second review: Boss's hand orders sent from the app's own screens - "iraalgo" - are his, as one in Kite is.)
            val outsideSells = onIt.filter { it.side == "SELL" && ((it.tag.isBlank() && it.id !in known) || it.tag == "iraalgo" || it.tag in SquareOffExit.TAGS) }.sumOf { it.filled }
            val carried = rows.sumOf { it.overnight } - list.filter { it.value.day.isBefore(today) }.sumOf { it.value.qty }
            val outsideBuys = onIt.filter { it.side == "BUY" && ((it.tag.isBlank() && it.id !in known) || it.tag == "iraalgo") }.sumOf { it.filled } + carried.coerceAtLeast(0)
            val booked = b.positions.filter { !it.open && it.live && it.kite == sym && it.exitTime?.toLocalDate() == today }.map { it.qty to (it.why in OUTSIDE_WHYS) } +
                armLive.closed.filter { it.exchange == "NFO" && it.symbol == sym && it.product == "MIS" && it.exitTime?.toLocalDate() == today }
                    .map { it.qty to (it.why == "closed_at_zerodha") }
            val sold = com.optionslab.engine.orb.LiveMirror.outsideSaleCovers(outsideSells, outsideBuys, booked.filter { it.second }.sumOf { it.first }, short)
            val unbooked = com.optionslab.engine.orb.LiveMirror.unbookedSale(onIt.filter { it.side == "SELL" }.sumOf { it.filled }, outsideBuys, booked.sumOf { it.first })
            val ms = epochMs(t)
            val first = shortSince.putIfAbsent(sym, ms)
            // (19 Nov 2026, second review: another of the app's own books traded it today - a strategy leg, Pine, a protection, the arms'
            // live copies - its fills can explain the difference in ways these sums do not see: only a sale outside the app covering it
            // confirms then, never the reads agreeing for a while.)
            val otherApp = onIt.any { it.tag in OTHER_APP_TAGS }
            if (otherApp && !sold || !com.optionslab.engine.orb.LiveMirror.shortConfirmed(true, sold, first ?: ms, ms, unbooked)) {
                if (first == null) runCatching { Diag.record("orb", "Zerodha's positions show $held of $sym, less than the ${ours + others} its live trades hold: " +
                    "nothing is changed until that is confirmed; the stops rest as they are") }
                continue
            }
            shortSince.remove(sym)
            val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: continue
            val lot = spec.lotSize.coerceAtLeast(1)
            val px = q["NFO:$sym"]?.last?.takeIf { it > 0 }
            var excess = ours - share
            for (iv in list.sortedByDescending { it.value.entryTime }) {
                if (excess <= 0) break
                val i = iv.index
                val p = b.positions[i]
                if (p !== iv.value) continue
                val cut = minOf(excess, p.qty)
                val keep = p.qty - cut
                var stopId = p.stopOrderId
                if (stopId != null) {
                    val so = orders[stopId]
                    var out = keep <= 0
                    if (!out) {
                        // (Its stop's state not in this read: nothing more on this contract this pass - never an older trade cut instead.)
                        if (so == null || !so.working) break
                        val to = com.optionslab.engine.risk.ExitQty.shrinkTo(so.qty, so.filled, keep, lot)
                        // (At the trade's own trigger now - this pass may have moved it up - with its limit under it, as [raiseLiveStop].)
                        val trig = p.stopTrigger ?: so.trigger
                        if (to != null && runCatching {
                                if (so.type == "SL-M") Broker.modify(so, to, "SL-M", null, trig) else Broker.modify(so, to, "SL", stopLimit(trig, spec.tickSize), trig)
                            }.isFailure) out = true
                    }
                    if (out) {
                        cancelOwnStop(stopId, b)
                        val st = runCatching { Broker.orderState(stopId)?.status }.getOrNull()
                        // (Filled meanwhile - booked from its fill next pass - or not out yet: nothing more on this contract this pass.)
                        if (st != "CANCELLED" && st != "REJECTED") break
                        stopId = null
                    }
                }
                val at = px ?: p.entry
                val part = cut.toDouble() / p.qty
                b.positions[i] = p.copy(qty = cut, exit = at, exitTime = t, why = goneWhy(p.day, t, expiryClose = false), stopOrderId = null,
                    charges = p.charges * part + kiteCharge("SELL", at, cut))
                if (keep > 0) b.positions += p.copy(qty = keep, stopOrderId = stopId, charges = p.charges * (1 - part))
                excess -= cut
                com.optionslab.app.work.Alerts.error("${armOf(p.arm).label}: Zerodha holds $held of $sym, less than its trades here (sold outside the app). " +
                    "$cut booked closed; " + (if (keep > 0) "$keep kept, " + (if (stopId != null) "its stop at Zerodha now covering $keep." else
                    "its stop taken out (Zerodha would not reduce it) and placed again for what is held on the next check.") else "its stop taken out."), "ORB live")
            }
        }
    }

    /**
     * 18 Nov 2026: by trade ([managerId]), when (market-clock ms) Zerodha's positions first showed it gone with no sale seen. Memory only
     * (a restart starts the count again: later, never sooner); bounded; cleared with [forgetStopSaid].
     */
    private val goneSince = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 18 Nov 2026 (review): how a close booked from outside the arm is named ([goneWhy]). */
    private val OUTSIDE_WHYS = setOf("closed_by_you", "backstop_square_off", "expiry_square_off")

    /** 18 Nov 2026 (review): the exits that go even while a position is shown gone but not confirmed: Boss's Stop for today and 15:10. */
    private val ALWAYS_EXITS = setOf("operator_stop", "session_end")

    /**
     * 18 Nov 2026 (audit: B2): the exits that are neither at a stop's level nor a square-off - a target, Liquidity's time stop and its
     * index exits on a new level: Boss's own sell order in Kite covering the lot is never cancelled for one of these ([exitLive]).
     */
    private val TAKE_EXITS = setOf("target", "time_stop", "failed_break", "new_liquidity", "next_liquidity")

    /** 19 Nov 2026 (review): by the stop put back after a refused Stop-for-today or 15:10 sale, when (market-clock ms). Memory only. */
    private val refusedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val REFUSED_RETRY_MS = 45_000L

    /**
     * 19 Nov 2026 (coordinator): by trade ([managerId]), the minute Boss was last told no stop can rest for it because other working
     * sales cover everything held ([coverSay]). Memory only; bounded.
     */
    private val coverSaid = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()

    /** Said loudly at most once a minute per trade, while it stays so ([coverSaid]). */
    private fun coverSay(p: Position, text: String) {
        val minute = now().withSecond(0).withNano(0)
        if (coverSaid.size > 200) coverSaid.clear()
        if (coverSaid.put(managerId(p), minute) != minute) runCatching { com.optionslab.app.work.Alerts.error(text, "ORB live") }
    }

    /** 18 Nov 2026 (audit: B2): by trade ([managerId]), the minute Boss was last told his own sell order covers its exit. Memory only; bounded. */
    private val bossSaid = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()

    /** 18 Nov 2026: when each lost-reply entry ([managerId]) was last searched for (market-clock ms): at most every 10 s. Memory only; bounded. */
    private val settleAsked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 18 Nov 2026 (audit: B4): by lost-id entry ([managerId]), the reads in a row that listed its contract with nothing unexplained held. Memory only. */
    private val settleZero = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 18 Nov 2026 (audit: B4): how long after a lost-id entry a positions read with no row for its contract is no reading at all. */
    private const val NO_ROW_WAIT_MS = 10 * 60_000L

    private fun settleDue(key: String, nowMs: Long): Boolean {
        if (settleAsked.size > 200) settleAsked.clear()
        val last = settleAsked[key]
        if (last != null && nowMs - last in 0 until 10_000L) return false
        settleAsked[key] = nowMs
        return true
    }
    /** 18 Nov 2026: the trades a positions read listed no row for, already said (once until it is seen held again). Memory only. */
    private val goneNoRowSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 18 Nov 2026 (review F3): the trades shown gone with no sale anywhere that Boss was told of (once until seen held or booked). Memory only. */
    private val goneAlertSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 18 Nov 2026: whether live trade [p] of book [b] on [sym], which the positions read [net] shows gone, is confirmed gone
     * ([com.optionslab.engine.orb.LiveMirror.shortConfirmed]): Zerodha lists a row for the contract, and its order book ([orders]) shows
     * sales of it since the entry outside the app (Boss's own in Kite, Zerodha's square-off, the app's square-off - never another of the
     * app's own exits, which explains nothing here) that cover this trade, or the reads have shown it gone long enough. Said once when it
     * is not (yet). The reads the pass already made, and the order labels only when it is shown gone.
     * 18 Nov 2026 (review F1): those sales are netted over the day ([com.optionslab.engine.orb.LiveMirror.outsideSaleCovers]): less Boss's
     * own buys of the contract today and what was carried (his buy and sale are a round trip, not this trade's close) and less what either
     * live book - this one and the arms' live copies ([ArmLive]) - already booked closed from outside today (one sale proves one close only).
     * 18 Nov 2026 (review F3): "long enough" is 30 s only while some exit-side fill on the contract today, the app's own included, is
     * not booked by either book (or another product's row holds it: a conversion); with none, five minutes, and Boss is told once at 30 s
     * (its stop rests meanwhile).
     */
    private suspend fun goneConfirmed(b: Book, p: Position, sym: String, net: List<Broker.Position>, orders: Collection<Broker.OrderRow>,
                                      t: LocalDateTime): Boolean {
        val key = managerId(p)
        if (goneSince.size > 200) goneSince.clear()
        if (goneNoRowSaid.size > 200) goneNoRowSaid.clear()
        if (goneAlertSaid.size > 200) goneAlertSaid.clear()
        val ms = epochMs(t)
        // (A trade from an earlier day that Zerodha squared off has no row any more: no row counts as a reading for it.)
        val rowShown = net.any { it.symbol == sym && it.exchange == "NFO" } || p.day.isBefore(t.toLocalDate())
        // (Review: over the whole day - Boss's buy before this entry and his sale after it are his own round trip - and Boss's own orders
        // told by having no tag: the order labels are read only when such an order is on the contract, never on every tick of a quiet one.)
        val today = t.toLocalDate()
        val booked = (b.positions.filter { !it.open && it.live && it.kite == sym && it.exitTime?.toLocalDate() == today }
            .map { com.optionslab.engine.orb.LiveMirror.Booked("NFO", sym, "MIS", it.exitTime!!, it.qty, it.why in OUTSIDE_WHYS) } +
            ArmLive.view.value.closed.filter { it.exchange == "NFO" && it.symbol == sym && it.product == "MIS" && it.exitTime?.toLocalDate() == today }
                .map { com.optionslab.engine.orb.LiveMirror.Booked(it.exchange, it.symbol, it.product, it.exitTime!!, it.qty, it.why == "closed_at_zerodha") })
        val onIt = orders.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.filled > 0 }
        val mine: Set<String> = if (onIt.none { it.tag.isBlank() }) emptySet() else knownKite(b).toSet()
        // (19 Nov 2026: Boss's hand orders sent from the app's own screens - "iraalgo" - are his, as one in Kite is; as [shrinkToHeld].)
        val outsideSells = onIt.filter { it.side == "SELL" && ((it.tag.isBlank() && it.id !in mine) || it.tag == "iraalgo" || it.tag in SquareOffExit.TAGS) }.sumOf { it.filled }
        // (And what was carried from an earlier day: Boss's own carried lot sold today proves nothing about this trade.)
        val carried = net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.overnight } -
            (if (p.day.isBefore(today)) p.qty else 0)
        val outsideBuys = onIt.filter { it.side == "BUY" && ((it.tag.isBlank() && it.id !in mine) || it.tag == "iraalgo") }.sumOf { it.filled } + carried.coerceAtLeast(0)
        val sold = com.optionslab.engine.orb.LiveMirror.outsideSaleCovers(outsideSells, outsideBuys, booked.filter { it.outside }.sumOf { it.qty }, p.qty)
        // (A conversion in Kite - another product's row now holding it - counts as a sale for the wait: 30 s, not five minutes.)
        val converted = net.filter { it.symbol == sym && it.exchange == "NFO" && it.product != "MIS" }.sumOf { it.qty } -
            ArmLive.view.value.openReal.filter { it.symbol == sym && it.exchange == "NFO" && it.product != "MIS" && it.side > 0 }.sumOf { it.qty } >= p.qty
        val unbooked = converted ||
            com.optionslab.engine.orb.LiveMirror.unbookedSale(onIt.filter { it.side == "SELL" }.sumOf { it.filled }, outsideBuys, booked.sumOf { it.qty })
        // (A read with no row for it starts no count: it is no reading at all.)
        val first = if (rowShown) goneSince.putIfAbsent(key, ms) else goneSince[key]
        if (com.optionslab.engine.orb.LiveMirror.shortConfirmed(rowShown, sold, first ?: ms, ms, unbooked)) {
            goneSince.remove(key); goneAlertSaid.remove(key); return true
        }
        if (if (rowShown) first == null else goneNoRowSaid.add(key)) runCatching { Diag.record("orb", "${armOf(p.arm).label}: Zerodha's positions " +
            (if (rowShown) "show $sym at 0 with no sale outside the app in its order book to explain it" else "did not list $sym") +
            ": not booked closed until it is confirmed; its stop stays and it is watched") }
        if (com.optionslab.engine.orb.LiveMirror.shortAlertDue(rowShown, sold, first, ms, unbooked) && goneAlertSaid.add(key))
            com.optionslab.app.work.Alerts.error("${armOf(p.arm).label}: Zerodha's positions have shown $sym at 0 for 30 s with no sale in its order " +
                "book. Kept as held, its stop resting at Zerodha; booked closed only if this lasts 5 minutes. Check it in Kite.", "ORB live")
        return false
    }

    /**
     * The prices of [keys] ("NFO:SYMBOL"): [stream] - each from its fresh stream tick (2 s), the rest from Zerodha's quote.
     * 18 Oct: else [Broker.stopQuotes] (a tick only while 2 s fresh, else Zerodha's quote call), never a tick up to 5 s old.
     */
    private suspend fun liveQuotes(keys: List<String>, stream: Boolean): Map<String, Broker.Quote> {
        if (!stream) return Broker.stopQuotes(keys)
        val out = HashMap<String, Broker.Quote>()
        for (k in keys) {
            val tok = runCatching { Broker.tokenOf(k) }.getOrNull() ?: continue
            val tk = KiteStream.freshTick(tok) ?: continue
            if (tk.last > 0) out[k] = Broker.stopGuard(Broker.Quote(tk.last, tk.bid, tk.ask, tk.open, tk.oi, tk.volume))   // 15 Nov: [com.optionslab.ira.PriceNow.stopLast]
        }
        val rest = keys.filter { it !in out }
        if (rest.isNotEmpty()) out.putAll(Broker.stopQuotes(rest))
        return out
    }

    /**
     * 16 Nov (review): whether a SALE that could sell [p]'s units is still working on [sym] (its own arm's sale whose reply was lost or
     * not finished yet, or Boss's own sale in Kite): a sale (not a resting stop) labelled with [p]'s arm, or with no label kept (not
     * known: the careful side). Another book's labelled sale, and any resting stop, never hold it back. Reads the order labels (a
     * regular pass only).
     */
    private suspend fun ownSaleWorking(p: Position, sym: String, orders: Collection<Broker.OrderRow>, claimed: Set<String> = emptySet(),
                                       ownStops: Boolean = true): Boolean {
        // (Review: any working SALE on the contract - not a resting stop - whose label is not another arm's: this arm's own, or one with
        // no label kept, Boss's own sale in Kite included: a stop never goes in beside a sale that could sell the same units.)
        // (And a resting stop of the arms' own ("iraorb") with no other book's label: this position's own stop whose reply was lost.)
        // (19 Nov 2026: never a stop another trade of this book holds as its own - a split trade's other part - that is its stop, not a sale.)
        val sales = orders.filter { it.symbol == sym && it.working && it.side == "SELL" && it.id != p.stopOrderId && it.id !in claimed &&
            ((it.type != "SL" && it.type != "SL-M") || (ownStops && it.tag == "iraorb")) }
        if (sales.isEmpty()) return false
        val owners = runCatching { Strategies.owners() }.getOrDefault(emptyMap())
        val label = armOf(p.arm).label
        return sales.any { o -> owners["kite:${o.id}"]?.substringBefore(" · ")?.let { it == label } ?: true }
    }

    /**
     * 16 Nov (review): the stop orders this process cancelled itself (an exit taking its stop out first, a gone position's stop): a
     * cancel of one of these is the app's, never taken as Boss's ([Position.stopByYou]). Bounded (the oldest dropped first).
     * 22 Nov 2026 (B6 review, WHO CANCELLED A STOP): by order id, when it was marked ([wallMs]); kept in the book too ([encodeBook]) and
     * queued to be saved before the cancel goes ([cancelOwnStop]), so a restart between a cancel and the pass's own save never reads it
     * as Boss's. A mark whose cancel never took ends ([ownCancelSeenWorking], [cancelOwnStop]), so Boss's own later cancel of it is his.
     */
    private val ownCancels = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val OWN_CANCELS_MEMORY = 500
    /** 22 Nov 2026 (B6 review): the newest own-cancel marks kept in the book, from the last day ([OWN_CANCEL_KEEP_MS]). */
    private const val OWN_CANCELS_KEPT = 200
    private const val OWN_CANCEL_KEEP_MS = 24 * 60 * 60_000L

    private fun ownCancel(id: String): Boolean = ownCancels.containsKey(id)

    /** 22 Nov 2026 (B6 review): [id] marked the app's own cancel now; true when it was not marked before. Memory (bounded). */
    private fun markOwnCancel(id: String): Boolean {
        ownCancelsRestored -= id; ownCancelsUnsure.remove(id)
        val fresh = ownCancels.put(id, wallMs()) == null
        if (ownCancels.size > OWN_CANCELS_MEMORY) ownCancels.entries.sortedBy { it.value }.take(ownCancels.size - OWN_CANCELS_MEMORY)
            .forEach { ownCancels.remove(it.key, it.value) }
        return fresh
    }

    /**
     * 22 Nov 2026 (B6 review: "expire own-cancel marks that never took"): the stop [id] seen still working [EXIT_MARK_SETTLE_MS] or more after
     * the app marked its cancel - that cancel never took: the mark ends (dropped from the book with its next save), so a later cancel of it
     * by Boss in Kite is his. One taken back from the book after a restart ends the first time its stop is seen working. A cancel the app
     * sends again marks it afresh. True when the mark ended.
     */
    private fun ownCancelSeenWorking(id: String, fresh: Boolean = true): Boolean {
        val at = ownCancels[id] ?: return false
        // (One taken back from the book after a restart ends at once: its cancel never took - the phone may have died before it went.)
        // (One seen still working right after its cancel ends once a FRESH read - [fresh]: never the stream's kept copy, which can be up to
        // [Broker.RECON_MS] old - still shows it working [OWN_CANCEL_RECHECK_MS] later: [ownCancelDidNotTake].)
        val unsure = fresh && ownCancelsUnsure[id]?.let { wallMs() - it !in 0 until OWN_CANCEL_RECHECK_MS } == true
        return (id in ownCancelsRestored || unsure || wallMs() - at !in 0 until EXIT_MARK_SETTLE_MS) &&
            ownCancels.remove(id, at).also { if (it) { ownCancelsRestored -= id; ownCancelsUnsure.remove(id) } }
    }

    /** 22 Nov 2026 (B6 review): the own-cancel marks taken back from the book after a restart, not made again since. Memory only. */
    private val ownCancelsRestored: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 22 Nov 2026 (B6 review): the own-cancel marks the book keeps ([encodeBook]): the newest [OWN_CANCELS_KEPT] of the last day. */
    private fun ownCancelsKept(): List<Pair<String, Long>> = marksKept(ownCancels)

    /** 22 Nov 2026 (B6 review): of the marks [m] (by order id, when marked), the newest [OWN_CANCELS_KEPT] of the last day - those the book keeps. */
    private fun marksKept(m: Map<String, Long>): List<Pair<String, Long>> {
        val now = wallMs()
        return m.entries.map { it.key to it.value }.filter { now - it.second < OWN_CANCEL_KEEP_MS }
            .sortedByDescending { it.second }.take(OWN_CANCELS_KEPT)
    }

    /**
     * 16 Nov (review): by trade ([managerId]), how its stop's last end was said ("status|message"): the same end again is not said
     * again until a stop of the trade was seen resting at Zerodha ([stopRested]). Memory only; the trades of a day.
     */
    private val endedSaid = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** 16 Nov (review): by [restKey], the re-rests tried since a stop of the trade was last seen resting (their back-off). Memory only. */
    private val restTries = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** 16 Nov (review): by [restKey], a re-rest's refusal already said (once until a stop of the trade rests again). Memory only. */
    private val restRefusedSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** [p]'s key for its re-rests ([restExchangeStop]) on [sym]. */
    private fun restKey(p: Position, sym: String): String = "${p.arm}|$sym|${p.entryTime}"

    /** [p]'s stop seen resting at Zerodha: a later end, refusal or re-rest of it is a new one (said again, tried at once). */
    private fun stopRested(p: Position, sym: String) {
        endedSaid.remove(managerId(p))
        val k = restKey(p, sym)
        restTries.remove(k); restRefusedSaid.remove(k)
    }

    /** The memory of [endedSaid], [restTries] and [restRefusedSaid] dropped (a new process, an erase). */
    private fun forgetStopSaid() { endedSaid.clear(); restTries.clear(); restRefusedSaid.clear(); goneSince.clear(); goneNoRowSaid.clear(); settleAsked.clear(); goneAlertSaid.clear(); shortSince.clear(); settleZero.clear(); bossSaid.clear(); coverSaid.clear(); refusedAt.clear(); strayWaitSince.clear(); coldUnexplainedSaid.clear(); strayLeft.clear(); flatOrphans.clear(); flatSince.clear() }

    /**
     * 20 Nov 2026 (integration review): [ids] (resting orders Boss's manual exit from the app is taking out first, AppModel's takeOverLeg)
     * are the app's own cancels, marked before the cancels go - so a stop of these arms found CANCELLED after that exit's sale was refused
     * or failed is put back at Zerodha (Boss's 16 Nov rule), never read as his cancel in Kite ([Position.stopByYou]). In memory at once
     * ([exitMarks]: used only to tell whose cancel a trade's own stop's end was - never to leave a still-working order out of the cover
     * counted on a contract); on the trade too ([Position.appCancelled], which lasts until Zerodha shows its stop working again), set
     * under the book's lock on this object's own IO scope and written on the book's own writer ([saveSoon]) - the caller never waits on
     * the lock, the encoding or the disk. A restart in the moment before that write reads such a cancel as before.
     * The memory mark ends when the stop is seen working again [EXIT_MARK_SETTLE_MS] after it was made (the cancel did not take: a later
     * cancel is Boss's), or when [unmarkOwnCancels] takes it back. Returns the book's write job (for the tests).
     * 22 Nov 2026 (B6 review, second round: the marks were memory only until that later write): the marks are kept in the book too
     * ([encodeBook], as [ownCancels]) and taken back after a restart (each ending the first time its stop is seen working). With the lock
     * free and the book read, the trades are marked and the book queued to be saved ([saveSoon]: encoded here, written to the disk by its
     * own writer thread) at once, before the caller's cancels go - the caller never waits on the lock or the disk; otherwise on this
     * object's IO scope as before (then it may be written after the cancels went: a restart in that moment reads them as before).
     */
    internal fun markOwnCancels(ids: Collection<String>): kotlinx.coroutines.Job? {
        if (ids.isEmpty()) return null
        val at = wallMs()
        for (id in ids) { exitMarks[id] = at; exitMarksRestored -= id }
        if (exitMarks.size > 500) exitMarks.entries.sortedBy { it.value }.take(exitMarks.size - 500).forEach { exitMarks.remove(it.key, it.value) }
        val marked = ids.toSet()
        if (lock.tryLock()) try {
            val b = cache
            if (b != null) { markTradesOn(b, marked, true); runCatching { saveSoon(b) }; return kotlinx.coroutines.CompletableDeferred(Unit) }
        } finally { lock.unlock() }
        return offLock.launch { runCatching { lock.withLock { markTrades(marked, true) } } }
    }

    /**
     * 20 Nov 2026 (integration review): [ids] marked by [markOwnCancels] whose cancel did not take (still working at Zerodha): the marks
     * taken back - in memory at once, on the trades on this object's IO scope - so Boss's own later cancel of them in Kite is his.
     */
    internal fun unmarkOwnCancels(ids: Collection<String>): kotlinx.coroutines.Job? {
        if (ids.isEmpty()) return null
        val gone = ids.toSet()
        for (id in gone) { exitMarks.remove(id); exitMarksRestored -= id }
        return offLock.launch { runCatching { lock.withLock { markTrades(gone, false) } } }
    }

    /**
     * Under [lock]: each open live trade whose stop is one of [ids] marked ([on]) or unmarked as the app's own cancel; the book written
     * (22 Nov 2026, B6 review: always - the marks themselves are kept in it, [exitMarks]).
     */
    private suspend fun markTrades(ids: Set<String>, on: Boolean) {
        val b = book()
        markTradesOn(b, ids, on)
        saveSoon(b)
    }

    /** Under [lock]: [markTrades] on [b] itself, nothing written; true when a trade changed. */
    private fun markTradesOn(b: Book, ids: Set<String>, on: Boolean): Boolean {
        var changed = false
        for (i in b.positions.indices) {
            val p = b.positions[i]
            val sid = p.stopOrderId ?: continue
            // (A mark taken back meanwhile is never written on.)
            if (p.open && p.live && sid in ids && p.appCancelled != on && (!on || exitMarks.containsKey(sid))) { b.positions[i] = p.copy(appCancelled = on); changed = true }
        }
        return changed
    }

    /**
     * By order id, when Boss's manual exit from the app or a square-off marked that stop its own ([markOwnCancels]). Memory, bounded; kept in
     * the book too (22 Nov 2026, B6 review: [encodeBook]).
     */
    private val exitMarks = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 22 Nov 2026 (B6 review): the [exitMarks] taken back from the book after a restart, not made again since. Memory only. */
    private val exitMarksRestored: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** A stop seen working this long after its mark: that cancel did not take. */
    internal const val EXIT_MARK_SETTLE_MS = 2 * 60_000L
    /** TEST ONLY (20 Nov 2026): the wall clock the own-cancel marks are timed by (null in the app: the system clock). Debug builds only. */
    @Volatile internal var testWallMs: (() -> Long)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }
    private fun wallMs(): Long = testWallMs?.invoke() ?: System.currentTimeMillis()
    private fun exitMarked(id: String): Boolean = exitMarks.containsKey(id)
    private fun exitMarkSeenWorking(id: String) {
        val at = exitMarks[id] ?: return
        // (22 Nov 2026, B6 review: one taken back from the book after a restart ends at once - its cancel never took.)
        if ((id in exitMarksRestored || wallMs() - at !in 0 until EXIT_MARK_SETTLE_MS) && exitMarks.remove(id, at)) exitMarksRestored -= id
    }

    /** 20 Nov 2026: true when [id] may be one of these arms' live stops (an open live trade claims it, or no book is read yet). Memory only. */
    internal fun mayOwnStop(id: String): Boolean = runCatching {
        cache?.positions?.toList()?.any { it.open && it.live && it.stopOrderId == id } ?: true
    }.getOrDefault(true)

    /**
     * Cancels the stop [id] as the app's own ([ownCancels]). 22 Nov 2026 (B6 review, WHO CANCELLED A STOP: the mark was memory only and the
     * book was saved after the pass - a restart between a confirmed cancel and that save read the stop CANCELLED with blank words as
     * Boss's, never put it back and told him he had cancelled it): a new mark is queued to be saved with the book BEFORE the cancel is sent
     * - [b], the book of a caller holding the arms' lock, handed to its writer ([saveSoon]: encoded here, written on the writer's own
     * thread, never the disk on this path); with none, the same when the lock is free now, else asked on this object's own worker (then
     * it may be saved just after the cancel went - [keepOwnCancels]). A cancel Zerodha refused
     * outright for a mark this call made (nothing of the app's was cancelled: the stop had ended already, or still works) takes it back.
     */
    private suspend fun cancelOwnStop(id: String, b: Book? = null) {
        val fresh = markOwnCancel(id)
        if (fresh) keepOwnCancels(b)
        val refused = runCatching { Broker.cancel(id) }.exceptionOrNull()?.let { Broker.definite(it) } == true
        // (Taken back in the book too: a restart never brings the mark back.)
        if (refused && fresh && ownCancels.remove(id) != null) keepOwnCancels(b)
    }

    /**
     * 22 Nov 2026 (B6 review): the own-cancel marks queued to be saved with the book ([saveSoon]: encoded here, written to the disk by the
     * book's own writer thread), before the cancel goes ([cancelOwnStop]); never the disk here, nor a wait on the lock. With no book in hand and the
     * lock busy, the save is queued once the lock is free - it may then be written after the cancel went. That is acceptable: only that
     * moment's restart could read the cancel as Boss's, and the pass holding the lock saves the book itself as it ends (with the mark).
     */
    private fun keepOwnCancels(b: Book?) {
        if (b != null) { runCatching { saveSoon(b) }; return }
        if (lock.tryLock()) try { cache?.let { runCatching { saveSoon(it) } } } finally { lock.unlock() }
        else offLock.launch { runCatching { lock.withLock { cache?.let { saveSoon(it) } } } }
    }

    /**
     * 22 Nov 2026 (B6 review, second round: "drop the mark when the cancel did not take"): Zerodha shows the stop [id] still working right
     * after the app's cancel of it. That cancel may not have taken - or Zerodha shows it a moment late (its book can show a cancel a moment
     * after the reply; the fake Kite's cancelLagReads since 19 Nov), and a mark dropped now would read the app's own cancel as Boss's (never
     * put back). So its mark no longer waits [EXIT_MARK_SETTLE_MS]: it ends the first time a fresh read of the order book still shows the
     * stop working [OWN_CANCEL_RECHECK_MS] or more after this ([ownCancelSeenWorking]), and Boss's own cancel of it in Kite after that is
     * his. A cancel the app sends again marks it afresh. Memory only.
     */
    private fun ownCancelDidNotTake(id: String) {
        if (!ownCancels.containsKey(id)) return
        if (ownCancelsUnsure.size > OWN_CANCELS_MEMORY) ownCancelsUnsure.clear()
        ownCancelsUnsure[id] = wallMs()
    }
    /** 22 Nov 2026 (B6 review): by order id, when the app's cancel of that stop was seen not taken right after ([ownCancelDidNotTake]). */
    private val ownCancelsUnsure = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 22 Nov 2026 (B6 review): how long after such a sighting a stop still seen working ends its mark (as the square-offs' gap). */
    internal const val OWN_CANCEL_RECHECK_MS = 15_000L

    /** 22 Nov 2026 (B6 review): an order status Zerodha shows for an order still working (as [Broker.OrderRow.working]). */
    private val STILL_WORKING = setOf("OPEN", "TRIGGER PENDING", "AMO REQ RECEIVED", "OPEN PENDING", "VALIDATION PENDING", "PUT ORDER REQ RECEIVED", "MODIFY PENDING")

    /**
     * 19 Nov 2026 (review): before a lost entry is looked for in Zerodha's positions ([settleEntry], no order found for it), a buy of these
     * arms on [sym] still working that no trade claims and no other arm's or book's label names - it may be this very entry, still filling -
     * is cancelled as the app's own, and true is returned: the entry stays unconfirmed and is judged again on the next look from what
     * Zerodha then holds. The orders unreadable: true (asked again). The labels unreadable: nothing cancelled, true while such a buy works.
     * False: none working.
     */
    private suspend fun strayBuyWorking(b: Book, sym: String, p: Position): Boolean {
        // (19 Nov 2026, final check: never more than [STRAY_WAIT_MS] from the first wait - then what Zerodha holds is taken back with its
        // stop as usual, and Boss is told a buy of these arms was still working on it.)
        val key = managerId(p)
        val ms = epochMs(now())
        // (The orders unreadable: asked again - the timer neither starts nor runs out on a read that saw nothing.)
        val found = strayFound(b, sym) ?: return true
        if (!found.waits) { strayWaitSince.remove(key); return false }
        if (strayWaitSince.size > 200) strayWaitSince.clear()
        val first = strayWaitSince.putIfAbsent(key, ms) ?: ms
        if (ms - first < STRAY_WAIT_MS) return true
        // (Past the limit: taken back now - and that buy is kept on [strayLeft], its cancel tried again on each look until it ends,
        // and what it fills after the take-back said, never left unwatched by silence.)
        if (strayWaitSince.replace(key, Long.MIN_VALUE / 2) != null && first != Long.MIN_VALUE / 2) runCatching { com.optionslab.app.work.Alerts.error(
            "${armOf(p.arm).label}: a buy of these arms on $sym kept working for over 90 s while its lost entry was being settled; what Zerodha holds " +
            "is taken back with its stop now, and that buy is cancelled again on each check. Check $sym in Kite.", "ORB live") }
        found.buys.forEach { strayLeft[it.id] = StrayLeft(sym, it.filled, now().toLocalDate()) }
        return false
    }

    /** 19 Nov 2026 (final check): stray buys of these arms still working after a lost entry was taken back past the 90 s, by order id. */
    private val strayLeft = java.util.concurrent.ConcurrentHashMap<String, StrayLeft>()

    /**
     * 19 Nov 2026 (audit batch 2, L2): one [strayLeft] buy: its contract, what it had filled when taken back, the day it was kept, and the
     * order reads in a row that did not list it. Kept only while it can still matter: one from an earlier day (Zerodha's order book is a
     * day's) or missing from [STRAY_MISSES] reads in a row is dropped - before, it stayed for good and every look read the orders for it.
     */
    private class StrayLeft(val sym: String, val filled: Int, val day: LocalDate, @Volatile var misses: Int = 0)
    internal const val STRAY_MISSES = 5

    /** TEST ONLY (19 Nov 2026, audit batch 2): the stray buys still followed. */
    internal fun strayLeftForTest(): Set<String> = strayLeft.keys.toSet()
    /** TEST ONLY (19 Nov 2026, audit batch 2): a stray buy followed as the 90 s take-back leaves it ([strayBuyWorking]). */
    internal fun strayLeftAddForTest(id: String, sym: String, filled: Int, day: LocalDate) { strayLeft[id] = StrayLeft(sym, filled, day) }

    /** Each regular look: [strayLeft]'s buys cancelled again until they end; one that filled more after the take-back is said. */
    private suspend fun strayLeftCheck(orders: Map<String, Broker.OrderRow>) {
        val today = now().toLocalDate()
        for ((id, v) in strayLeft.entries.toList()) {
            val sym = v.sym; val before = v.filled
            // (19 Nov 2026, audit batch 2, L2: an earlier day's, or one the order book has not listed for [STRAY_MISSES] reads: dropped, said.)
            if (v.day.isBefore(today)) { strayLeft.remove(id); runCatching { Diag.record("orb", "the stray buy of $sym (order $id) from ${v.day} is no longer followed") }; continue }
            val o = orders[id] ?: run {
                v.misses += 1
                if (v.misses >= STRAY_MISSES) { strayLeft.remove(id); runCatching { Diag.record("orb", "the stray buy of $sym (order $id) was not in " +
                    "Zerodha's orders for ${v.misses} reads in a row: no longer followed") } }
                null
            } ?: continue
            v.misses = 0
            if (o.working) { runCatching { Broker.cancel(o.id, o.variety) }; continue }
            strayLeft.remove(id)
            val more = o.filled - before
            if (more > 0) runCatching { com.optionslab.app.work.Alerts.error("ORB live: the stray buy of $sym (order $id) filled $more more after its lost entry was " +
                "taken back, then ended ${o.status.lowercase()}: those $more have no trade here and no stop. Check $sym in Kite.", "ORB live") }
            else runCatching { Diag.record("orb", "the stray buy of $sym (order $id) ended ${o.status.lowercase()} with nothing more filled") }
        }
    }

    /**
     * 19 Nov 2026 (audit batch 2, L1): stops of these arms ("iraorb" SL / SL-M SELL, NFO MIS) resting where no trade of this book claims
     * them and no live trade of it is open on the contract - left by a crash before its trade was saved, or beside a trade Boss's own
     * sale closed: id -> its contract. Each regular look follows them (an orders and a positions read, only while one is followed) until
     * it ends, a trade claims it, or the contract is confirmed flat ([flatOrphanCheck]): then it is cancelled as the app's own and its end
     * confirmed, and Boss is told. Left resting on nothing held, it could sell short. Memory only.
     */
    private val flatOrphans = java.util.concurrent.ConcurrentHashMap<String, FlatOrphan>()
    private class FlatOrphan(val sym: String, val day: LocalDate, @Volatile var misses: Int = 0)
    /** 19 Nov 2026 (audit batch 2, L1): by contract, when (market-clock ms) Zerodha's positions first showed it flat with such a stop on it. */
    private val flatSince = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** TEST ONLY (19 Nov 2026, audit batch 2): the unclaimed stops followed on contracts with no open trade. */
    internal fun flatOrphansForTest(): Set<String> = flatOrphans.keys.toSet()

    /**
     * 19 Nov 2026 (audit batch 2, L1): from an orders read in hand ([orders]), the unclaimed stops of these arms on contracts where no live
     * trade of [b] is open, followed from now ([flatOrphans]): labelled for no other book (no label, or one of these arms' "· stop"), not
     * one the app is taking out itself. Reads nothing.
     */
    private suspend fun noteFlatOrphans(b: Book, orders: Collection<Broker.OrderRow>, t: LocalDateTime): Boolean {
        val claimed = b.positions.mapNotNull { it.stopOrderId }.toSet()
        val openOn = b.positions.filter { it.open && it.live }.mapNotNull { it.kite }.toSet()
        val loose = orders.filter { it.tag == "iraorb" && it.exchange == "NFO" && it.product == "MIS" && it.side == "SELL" && it.working &&
            (it.type == "SL" || it.type == "SL-M") && it.id !in claimed && it.symbol !in openOn && !ownCancel(it.id) && !flatOrphans.containsKey(it.id) }
        if (loose.isEmpty()) return true
        // (The labels unreadable: false - nothing is followed yet, and the flat marks are kept to look again.)
        val owners = runCatching { Strategies.owners() }.getOrNull() ?: return false
        val labels = ALL_ARMS.map { "${it.label} · stop" }.toSet()
        if (flatOrphans.size > 200) flatOrphans.clear()
        for (o in loose) if (owners["kite:${o.id}"]?.let { it in labels } != false) flatOrphans.putIfAbsent(o.id, FlatOrphan(o.symbol, t.toLocalDate()))
        return true
    }

    /**
     * 19 Nov 2026 (audit batch 2, L1): each followed stop ([flatOrphans]) judged on this look's reads ([orders], [net]). Dropped when it
     * ended, a trade of [b] claims it or is open on its contract, it is from an earlier day, or the order book has not listed it for
     * [STRAY_MISSES] reads. Cancelled as the app's own when its contract is confirmed flat by the 18 Nov rules
     * ([com.optionslab.engine.orb.LiveMirror.shortConfirmed]: Zerodha lists the contract's MIS row at 0 (or less) and the reads have kept
     * showing it so for 30 s with a sale on it today, five minutes with none) - then its end is confirmed (not ended: cancelled again on the
     * next look) and Boss is told. Never while the contract is held (that stop may cover the lot: [coldSweep] and the trades' own checks
     * judge it).
     */
    private suspend fun flatOrphanCheck(b: Book, t: LocalDateTime, orders: Map<String, Broker.OrderRow>, net: List<Broker.Position>,
                                        pruneMarks: Boolean = true) {
        val today = t.toLocalDate()
        val ms = epochMs(t)
        // (A flat contract's mark is kept only while a stop on it is followed.)
        if (pruneMarks || flatSince.size > 200) flatSince.keys.retainAll(flatOrphans.values.map { it.sym }.toSet())
        for ((id, f) in flatOrphans.entries.toList()) {
            if (f.day.isBefore(today)) { flatOrphans.remove(id); continue }
            val o = orders[id] ?: run { f.misses += 1; if (f.misses >= STRAY_MISSES) flatOrphans.remove(id); null } ?: continue
            f.misses = 0
            if (!o.working || b.positions.any { it.stopOrderId == id || (it.open && it.live && it.kite == f.sym) }) {
                flatOrphans.remove(id); if (flatOrphans.values.none { it.sym == f.sym }) flatSince.remove(f.sym); continue
            }
            val rows = net.filter { it.symbol == f.sym && it.exchange == "NFO" && it.product == "MIS" }
            // (No row for it: no reading at all - nothing starts or ends; held: never a stop taken off a lot it may cover.)
            if (rows.isEmpty()) continue
            if (rows.sumOf { it.qty } > 0) { flatSince.remove(f.sym); continue }
            val first = flatSince.putIfAbsent(f.sym, ms) ?: ms
            val sale = orders.values.any { it.symbol == f.sym && it.exchange == "NFO" && it.product == "MIS" && it.side == "SELL" && it.filled > 0 }
            if (!com.optionslab.engine.orb.LiveMirror.shortConfirmed(true, false, first, ms, sale)) continue
            cancelOwnStop(id, b)
            val st = runCatching { Broker.orderState(id)?.status }.getOrNull()
            if (st !in DONE) continue
            flatOrphans.remove(id)
            if (flatOrphans.values.none { it.sym == f.sym }) flatSince.remove(f.sym)
            runCatching { Diag.record("orb", "a stop of these arms on ${f.sym} (order $id) that no trade held, resting on a flat contract: cancelled ($st)") }
            runCatching { com.optionslab.app.work.Alerts.error(if (st == "COMPLETE") "ORB live: a stop of these arms on ${f.sym} (order $id) that no trade " +
                "held had already sold while Zerodha showed the contract flat - it may now show a short. Check ${f.sym} in Kite now."
                else "ORB live: a stop of these arms on ${f.sym} (order $id) that no trade held was resting on a flat contract (nothing held): " +
                "cancelled, so it can never sell short. Check ${f.sym} in Kite.", "ORB live") }
        }
    }

    /** What [strayFound] saw: whether the entry waits, and the stray buys it cancels (none when the labels could not be read). */
    private class Stray(val waits: Boolean, val buys: List<Broker.OrderRow>)

    private suspend fun strayFound(b: Book, sym: String): Stray? {
        val orders = runCatching { Broker.orders() }.getOrNull() ?: return null
        val claimed = b.positions.mapNotNull { it.entryOrderId }.toSet()
        // (The labels unreadable: nothing is cancelled - it could be another arm's - but the entry waits while such a buy still works.)
        val owners = runCatching { Strategies.owners() }.getOrNull() ?: return Stray(orders.any { it.symbol == sym && it.tag == "iraorb" && it.side == "BUY" &&
            it.exchange == "NFO" && it.product == "MIS" && it.working && it.id !in claimed }, emptyList())
        val working = orders.filter { it.symbol == sym && it.tag == "iraorb" && it.side == "BUY" && it.exchange == "NFO" && it.product == "MIS" &&
            it.working && it.id !in claimed && owners["kite:${it.id}"] == null }
        working.forEach { o -> runCatching { Broker.cancel(o.id, o.variety) } }
        return Stray(working.isNotEmpty(), working)
    }

    /** 19 Nov 2026 (second review): what [orphanStop] found. */
    private sealed class Orphan {
        object None : Orphan()
        object Wait : Orphan()
        /** The orders or the owner labels could not be read: nothing is known either way. */
        object Unread : Orphan()
        /** 19 Nov 2026 (review): an unclaimed stop that may be another trade's (the wide window, another quantity): never taken. */
        object Other : Orphan()
        class Took(val id: String, val trigger: Double) : Orphan()
    }

    /**
     * 19 Nov 2026 (second review): a working stop of these arms ("iraorb" SL / SL-M SELL, MIS) on [sym] placed since [since] (a few
     * seconds' clock slack) that no trade of this book claims as its stop, not one the app is taking out ([ownCancels]), and labelled
     * for no other arm (no label, or "[label] · stop") - one placed just before the app stopped, its trade's row not saved with it.
     * [settling] (an unconfirmed entry being kept): one that filled since, in part or whole, counts too when it was placed for exactly
     * [qty] - taken as its stop, so the price check books its fill as this trade's exit.
     * [Orphan.Took]: taken as the stop for [qty], raised to [at] when that is above its own trigger (a refused raise keeps its own
     * trigger; the lock moves it up later), resized when it covers another quantity - never grown when [grow] is false - and confirmed
     * still at Zerodha (working, or filled: its fill is this trade's). [Orphan.Wait]: nothing new may rest now - a candidate's time
     * unreadable, more than one, one part filled for another quantity, one whose state is unknown, or one that could neither be resized
     * nor confirmed out. [Orphan.Other]: one that may be another trade's, or one that would have to grow while [grow] is false - never
     * taken. [Orphan.Unread]: the orders or labels could not be read. [Orphan.None]: none
     * (or the one found is out): a new stop may be placed. From [orders] when given, else one read.
     */
    private suspend fun orphanStop(b: Book, label: String, sym: String, since: LocalDateTime, qty: Int, tick: Double,
                                   orders: Collection<Broker.OrderRow>?, at: Double? = null, grow: Boolean = true,
                                   settling: Boolean = false, entryOid: String? = null): Orphan {
        val list = orders ?: runCatching { Broker.orders() }.getOrNull() ?: return Orphan.Unread
        val claimed = b.positions.mapNotNull { it.stopOrderId }.toSet()
        val owners = runCatching { Strategies.owners() }.getOrNull() ?: return Orphan.Unread
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        // (19 Nov 2026, review: from the entry order's own Zerodha time when it is known - both on Zerodha's clock; else the entry's own time
        // less 120 s, the phone's clock and Zerodha's may differ.)
        val exact = entryOid?.let { id -> list.firstOrNull { it.id == id }?.placedAt }?.let { runCatching { LocalDateTime.parse(it, fmt) }.getOrNull() }?.minusSeconds(5)
        val from = exact ?: since.minusSeconds(120)
        val stops = list.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side == "SELL" &&
            (it.working || (settling && it.status == "COMPLETE" && it.filled > 0)) &&
            it.tag == "iraorb" && (it.type == "SL" || it.type == "SL-M") && it.id !in claimed && !ownCancel(it.id) && !Broker.lostMayBe(it) &&
            (owners["kite:${it.id}"]?.let { l -> l == "$label · stop" } ?: true) }
        val placed = stops.map { it to runCatching { LocalDateTime.parse(it.placedAt, fmt) }.getOrNull() }
        if (placed.any { it.second == null }) return Orphan.Wait
        val found = placed.filter { !it.second!!.isBefore(from) }.map { it.first }
        if (found.isEmpty()) return Orphan.None
        val o = found.singleOrNull() ?: run {
            runCatching { Diag.record("orb", "$label: ${found.size} stops on $sym that no trade claims; none is taken and no new one rests until one is gone") }
            return Orphan.Wait
        }
        val own = o.trigger.takeIf { it > 0 } ?: return Orphan.Wait
        suspend fun took(trig: Double): Orphan {
            runCatching { Strategies.tagOwnerSoon("kite:${o.id}", "$label · stop") }
            runCatching { Diag.record("orb", "$label: a stop on $sym that no trade claimed (order ${o.id}, placed ${o.placedAt}) is taken as its stop for $qty") }
            Broker.claimStop(o.id)          // (20 Nov 2026, delta review: claimed; the caller forgets only its own trade's lost key)
            return Orphan.Took(o.id, trig)
        }
        // (One that sold already: taken as it is only when placed for exactly this trade's quantity - never resized or raised.)
        if (o.filled > 0) return if (settling && o.qty == qty) took(own) else Orphan.Wait
        val rem = com.optionslab.engine.risk.ExitQty.remaining(o.qty, o.filled, o.pending)
        // (The wide window - the entry's own Zerodha time not known - takes only a stop placed for exactly this quantity: another trade's
        // lost stop on the contract from shortly before is never taken and resized.)
        if (exact == null && rem != qty) return Orphan.Other
        // (Never grown - beside the trade's own sale, or at the settle where what is held is not read: another quantity, left alone.)
        if (rem < qty && !grow) return Orphan.Other
        val trig = maxOf(own, at ?: own)
        val resize = rem != qty
        val modified = (!resize && trig <= own + 1e-9) || runCatching {
            if (o.type == "SL-M") Broker.modify(o, qty, "SL-M", null, trig) else Broker.modify(o, qty, "SL", stopLimit(trig, tick), trig)
        }.isSuccess
        if (!modified && resize) {
            // (Not resized: taken out, confirmed out, before another may rest - never two stops selling more than is held.)
            cancelOwnStop(o.id, b)
            val st = runCatching { Broker.orderState(o.id)?.status }.getOrNull()
            return if (st == "CANCELLED" || st == "REJECTED") Orphan.None else Orphan.Wait
        }
        // (Confirmed still at Zerodha now - the read may be from before something ended it; unknown: asked again, never cancelled.)
        val st = runCatching { Broker.orderState(o.id)?.status }.getOrNull() ?: return Orphan.Wait
        return when {
            st in WORKING_STOP || st == "COMPLETE" -> took(if (modified) trig else own)
            st == "CANCELLED" || st == "REJECTED" -> Orphan.None
            else -> Orphan.Wait
        }
    }

    private val WORKING_STOP = setOf("OPEN", "TRIGGER PENDING", "MODIFY PENDING", "MODIFY VALIDATION PENDING", "VALIDATION PENDING",
        "PUT ORDER REQ RECEIVED", "OPEN PENDING", "AMO REQ RECEIVED")

    /**
     * When each position's exchange stop was last placed again ([restExchangeStop]): at most once a minute each (16 Nov review: then
     * 2, 4, 8, at most 16 minutes apart while Zerodha keeps ending it before it is seen resting - [restTries]).
     */
    private val restoppedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * The exchange stop placed again for [p] (held [held] at Zerodha, price [ltp]): at the lock its best price earned when above its own
     * trigger, else its trigger; only while the price is above that level (a sell stop must rest below the price - at or under it the
     * app's own check sells). The trade (with its stop) and, when the stop could cover only part of it, the rest with none (19 Nov 2026:
     * never a stop for more than is held less every other working sale); null when nothing was placed (too soon, no level, nothing left to
     * cover - said each minute - or Zerodha refused: the app keeps watching it).
     */
    private suspend fun restExchangeStop(b: Book, p: Position, sym: String, heldRead: Int, ltp: Double, tick: Double,
                                         orders: Collection<Broker.OrderRow>? = null, adoptOnly: Boolean = false,
                                         positions: Broker.Positions? = null): Pair<Position, Position?>? {
        // 16 Nov (Boss: "All the stops and profit locks should be on zerodha"): every arm's stop - the ORB family's own and its lock,
        // Liquidity 15+5's -15%, the breakeven, the breakeven at +₹1,000, its lock - is always put back at Zerodha (Liquidity first; the
        // ORB arms by Boss's "yes" the same day). [AppSettings.exchangeStops] is no longer read. The caller's guards decide when.
        // 6 Nov: Boss is exiting this contract by hand ([ArmLive.handsOff]): no new stop beside his sale (asked again next look).
        if (ArmLive.handsOffNow("NFO", sym)) return null
        // (Review of the b5-orders merge, C1: never for a part whose own lost sale is pending - no stop placed, and a lost stop found is not
        // attached; it rests as cover, left to its own lost reply, until that sale is booked or let go.)
        if (lostOf(p) != null) return null
        // 22 Nov 2026 (B6 review: the stop was sized from the pass's raw positions read - one trailing a sale that filled a moment ago, Boss's
        // own in Kite included, still showed the lots it sold, and a stop for them could sell into a short): what is held, lag-guarded against
        // the pass's own reads ([ExitCoordinator.held], forced - as the arms' live copies rest theirs), never more than the pass read. Never
        // read again here (no wait under the arms' lock): a fill the positions still trail counts as sold this pass - a smaller stop, or
        // none, until a later look sees them caught up. Not readable: nothing placed now.
        val held = if (orders == null || positions == null) heldRead else (runCatching {
            ExitCoordinator.held("NFO", sym, "MIS", armOf(p.arm).label, orders.toList(), first = positions, forceLag = lagForced(), readAgain = false)
        }.getOrNull() ?: return null).coerceAtMost(heldRead)
        // 20 Nov 2026 (audit B5 deep review): this trade's stop whose reply was lost and is not found yet: looked for first (the book read
        // in the background, never here), nothing new placed meanwhile and no try or back-off counted; found, it is the trade's stop.
        // (The trade split when its stop covers less: the part it covers keeps it, the rest has none - asked again next look.)
        fun split(q: Position, covered: Int): Pair<Position, Position?> {
            if (covered >= p.qty) return q to null
            val part = covered.toDouble() / p.qty
            return q.copy(qty = covered, charges = p.charges * part) to p.copy(qty = p.qty - covered, stopOrderId = null, charges = p.charges * (1 - part))
        }
        val lostKey = lostKeyOf(b, p, armOf(p.arm).label, sym)
        when (val l = Broker.lookLost(lostKey)) {
            // (20 Nov 2026, delta review: never one another trade of the book owns - it rests as usual below; found for more than is held now (a part sold
            // since), cut to it first, else taken out and rested again next look.)
            is Broker.LostLook.Found -> if (b.positions.none { it.open && it.stopOrderId == l.id }) {
                val lotF = runCatching { Broker.spec("NFO", sym) }.getOrNull()
                val fit = if (held > 0) minOf(p.qty, held) else p.qty
                // (22 Nov 2026, B6 review: taken out after part of it filled - that part booked closed at the stop, never "rests again" alone.)
                when (val f = fitFound(armOf(p.arm).label, l, fit, lotF?.lotSize?.coerceAtLeast(1) ?: 1, lotF?.tickSize ?: 0.05, b)) {
                    Fit.Out -> return null
                    is Fit.Filled -> return stopSold(p, f, sym)
                    Fit.Kept -> Unit
                }
                runCatching { Strategies.tagOwnerSoon("kite:${l.id}", "${armOf(p.arm).label} · stop") }
                val covers = minOf(l.qty, fit)
                // (Counted as placed this pass only when the pass's read does not already show it working.)
                if (orders?.any { it.id == l.id } != true) placed(sym, covers, l.id)
                return split(p.copy(stopOrderId = l.id, stopTrigger = l.trigger ?: p.stopTrigger), covers)
            }
            Broker.LostLook.Searching -> return null
            else -> Unit
        }
        // 19 Nov 2026 (coordinator): other working sales already cover everything held: no stop can rest - said each minute, before the
        // re-rest's back-off (which would otherwise say it at 1, 3, 7... minutes).
        val lot0 = runCatching { Broker.spec("NFO", sym).lotSize }.getOrNull()?.coerceAtLeast(1)
        val otherSells = orders?.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side == "SELL" && it.working && !ownCancel(it.id) }.orEmpty()
        val others = otherSells.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) } + placedNow(sym)
        // (Here a stop of these arms that no trade claims is left out - it may be this trade's own, taken below ([orphanStop]); the stop
        // placed if it is not is still capped by every other working sale, that one included.)
        val claimedStops = b.positions.mapNotNull { it.stopOrderId }.toSet()
        val othersButOrphans = otherSells.filter { !(it.tag == "iraorb" && (it.type == "SL" || it.type == "SL-M") && it.id !in claimedStops) }
            .sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) } + placedNow(sym)
        if (!adoptOnly && lot0 != null && minOf(p.qty, held - othersButOrphans) < lot0) {
            coverSay(p, "${armOf(p.arm).label}: no stop can rest at Zerodha for $sym - other working sales already cover all it holds; they are " +
                "its only cover, and the app watches its level. Check $sym in Kite.")
            return null
        }
        val key = restKey(p, sym)
        val now = System.currentTimeMillis()
        val tries = restTries[key] ?: 0
        // (adoptOnly: nothing new is placed - the minute and the back-off are left alone, so a stop rests as soon as its own sale ends.)
        val lastAt = restoppedAt[key]
        if (!adoptOnly && now - (lastAt ?: 0L) < (60_000L shl tries.coerceAtMost(4))) return null
        if (!adoptOnly) restoppedAt[key] = now
        // (19 Nov 2026, final check: a look that found nothing left to cover tried nothing - the minute and the back-off are put back, so the
        // next look judges again and the "no stop can rest" word really repeats each minute.)
        fun untried() { restTries[key] = tries; if (lastAt == null) restoppedAt.remove(key) else restoppedAt[key] = lastAt }
        // 15 Nov: the highest level that can rest at the price now - the lock or the stop rule's stop ([lockOrStop]), its
        // trigger, else (a Liquidity trade) its own -15%: one too near the price never leaves the position with no stop at Zerodha
        // (the app sells at the higher level itself).
        // Every other position as before (the lock or its trigger, only while the price is above it).
        // A stop-rule trade already at or under its rule's stop is not re-rested (its trigger kept): the same look sells it.
        if (p.fixed && fixedStopOf(p, tick)?.let { ltp <= it + 1e-9 } == true) return null
        val level = if (!p.fixed) (lockOrStop(p, tick) ?: p.stopTrigger ?: return null).takeIf { ltp > it + tick } ?: return null
            else listOfNotNull(lockOrStop(p, tick), p.stopTrigger, LiquidityRules.stopTrigger(p.entry, liqParams(p).stopPct))
                .filter { ltp > it + tick }.maxOrNull() ?: return null
        val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: return null
        // (Review: a refusal said once until a stop of the trade rests again ([restRefusedSaid]), not at every try; each try counted
        // for the back-off until one is seen resting ([stopRested]).)
        if (!adoptOnly) restTries[key] = tries + 1
        // 19 Nov 2026 (second review): a stop of these arms resting unclaimed since its entry is taken first ([orphanStop]), never a second.
        // (19 Nov 2026, final check: an unclaimed stop is taken for no more than is held less every other working sale - never grown past it;
        // the trade split when that is less.)
        val lotW = spec.lotSize.coerceAtLeast(1)
        val want = (if (adoptOnly) minOf(p.qty, held) else minOf(p.qty, held - othersButOrphans)).coerceAtLeast(0) / lotW * lotW
        val o = if (want > 0) orphanStop(b, armOf(p.arm).label, sym, p.entryTime, want, spec.tickSize, orders, at = level, grow = !adoptOnly,
            entryOid = p.entryOrderId) else Orphan.None
        when (o) {
            is Orphan.Wait, Orphan.Unread -> return null
            is Orphan.Took -> {
                orders?.firstOrNull { it.id == o.id }?.let { r -> placed(sym, want - com.optionslab.engine.risk.ExitQty.remaining(r.qty, r.filled, r.pending), o.id) }
                Broker.forgetLost(lostKey)            // (20 Nov 2026: taken as its stop another way - never looked for again)
                return split(p.copy(stopOrderId = o.id, stopTrigger = o.trigger), want)
            }
            Orphan.None, Orphan.Other -> if (adoptOnly) return null
        }
        // 19 Nov 2026 (coordinator): never a stop for more than Zerodha holds less every other working sale on the contract (from this
        // pass's read; none given: what is held, as before). None left: no stop - the other sales are its only cover, said each minute.
        // Less than the trade: the trade is split, the part the stop covers keeps it (its fill is never booked for more than it sold).
        val lot = spec.lotSize.coerceAtLeast(1)
        val qty = minOf(p.qty, held - others).coerceAtLeast(0) / lot * lot
        if (qty <= 0) {
            untried()
            coverSay(p, "${armOf(p.arm).label}: no stop can rest at Zerodha for $sym - other working sales already cover all it holds; they are " +
                "its only cover, and the app watches its level. Check $sym in Kite.")
            return null
        }
        val rest = placeStop(armOf(p.arm).label, sym, qty, spec.lotSize, spec.tickSize, p.entry, knownKite(b),
            liquidity = armOf(p.arm).liquidity, at = level, alert = key !in restRefusedSaid, lostKey = lostKey)
        // (22 Nov 2026, B6 review: its own lost stop found meanwhile and taken out after part of it filled - that part booked closed.)
        rest.sold?.let { return stopSold(p, it, sym) }
        val (id, trig, refused, covers) = rest
        if (id == null && refused) restRefusedSaid += key
        if (id == null) return null
        placed(sym, covers, id)
        return split(p.copy(stopOrderId = id, stopTrigger = trig ?: level), covers)
    }

    /**
     * F1 live: the profit lock MOVES the resting SL at Zerodha up to the lock the best price has earned - a modify of that
     * same order (trigger, and its limit under it), never a cancel and a new sell, never down, never to or above the price
     * now ([ProfitLock.raise]). Zerodha then sells at the lock by itself, the app running or not. A refused modify leaves
     * the old stop working, is said (throttled; Zerodha's own words, never a key or token) and is asked again next look.
     */
    private suspend fun raiseLiveStop(p: Position, sym: String, so: Broker.OrderRow?, ltp: Double, tick: Double): Position {
        val target = ladderTarget(p)
        // 15 Nov (Boss's exception): a stop-rule trade's resting SL under its rule's stop (an entry whose first stop could not rest
        // there, or the breakeven once the best is 10% up) is MODIFIED up to it - up only,
        // at least a tick under the price now ([com.optionslab.engine.orb.LiveMirror.mayRest]).
        val fixedTo = fixedStopOf(p, tick)?.let { com.optionslab.engine.Kite.onTick(it, tick, com.optionslab.engine.Kite.Side.BUY) }
            ?.takeIf { it > (p.stopTrigger ?: Double.NEGATIVE_INFINITY) + 1e-9 && com.optionslab.engine.orb.LiveMirror.mayRest(it, ltp, tick, 1) }
        if (target == null && fixedTo == null) return p
        if (so == null || !so.working || so.id != p.stopOrderId) return p
        val to = listOfNotNull(target?.let { ProfitLock.raise(p.entry, it, p.peak ?: p.entry, p.stopTrigger, ltp, ProfitLock.roundTripPerUnit(p.entry, p.qty), tick) },
            fixedTo).maxOrNull() ?: return p
        val r = runCatching {
            if (so.type == "SL-M") Broker.modify(so, so.qty, "SL-M", null, to) else Broker.modify(so, so.qty, "SL", stopLimit(to, tick), to)
        }
        if (r.isSuccess) return p.copy(stopTrigger = to)
        com.optionslab.app.work.Alerts.post("${armOf(p.arm).label}: Zerodha did not move the stop on $sym up to the ${if (to == fixedTo) ruleWhy(p, tick) else "profit lock"} " +
            "%.2f (%s). The stop stays at %s; it is tried again on the next check.".format(Locale.ENGLISH, to,
                r.exceptionOrNull()?.message?.take(160) ?: "an error", p.stopTrigger?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none"),
            com.optionslab.app.work.Alerts.Kind.ERROR, "ORB live", throttle = true)
        return p
    }

    // ---- 20 Nov 2026 (audit PC: a double sell after a Liquidity / ORB exit whose reply was lost) --------------------------------------

    /**
     * 20 Nov 2026 (audit PC): the positions-lag guard acts on these arms' live exits and their lost-entry and start-up checks whatever its
     * own switch says ([ExitCoordinator.held]'s forceLag), as on the arms' live copies since 18 Nov ([ArmLive]): it only ever sends, rests
     * or counts less. Said once in the diary while that switch is in shadow. Memory only.
     */
    private fun lagForced(): Boolean {
        if (!lagForcedSaid && runCatching { !ExitCoordinator.isLagOn() }.getOrDefault(false)) {
            lagForcedSaid = true
            runCatching { Diag.record("orb", "The positions-lag guard acts on Liquidity's and the ORB family's live exits although its switch is in " +
                "shadow (audit, 20 Nov 2026: an exit that filled a moment ago could be sold again). It only ever sends or rests less.") }
        }
        return true
    }
    @Volatile private var lagForcedSaid = false

    /**
     * 20 Nov 2026 (audit PC; deep review F1): an exit sale of these arms ([owner]: its trade's [managerId], for the words; the part that sent
     * it carries its [id] as [Position.lostId]) whose reply was lost and which the search right after could not find. It may still be
     * selling: that part sells nothing more for [LOST_EXIT_HOLD_MS], then it is searched for in Zerodha's order book (at most every
     * [LOST_EXIT_ASK_MS], on any look) - found, it is that part's exit; not found on a read past [LOST_LET_GO_MS], Boss is told and its exits
     * go on. Meanwhile the other trades on the contract with a stop resting wait with it in place ([exitLive]); one with none counts the
     * sale as going while no read shows it ([lostPending]). One per lost sale. Saved with its part's row in the book ([restoreLost]).
     */
    private data class LostExit(val id: Long, val sym: String, val owner: String, val why: String, val order: com.optionslab.engine.Kite.Order,
                                val known: Set<String>, val sinceMs: Long, val askedMs: Long = 0L,
                                /** Searches that could not read Zerodha's book (Boss told at [LOST_EXIT_TRIES]). */
                                val fails: Int = 0,
                                /** Found in Zerodha's book: its own part's next look books it ([lostExitLook]). */
                                val foundId: String? = null,
                                /** A search runs now ([searchLost]: at most one at a time). */
                                val searching: Boolean = false,
                                /** Not found on a read that succeeded past [LOST_LET_GO_MS] from the sale: the next look lets it go and sells. */
                                val notFound: Boolean = false,
                                /** The arm's name, for the found sale's owner label. */
                                val label: String = "",
                                /** Found still working: when its rest was last asked to be cancelled (in the background; again after [LOST_EXIT_ASK_MS]). */
                                val cancelAskedMs: Long = 0L,
                                /**
                                 * 20 Nov 2026: when it was sent by the phone's market clock ([Market.now]): its search looks from 3 minutes
                                 * before it ([Broker.findRecent]'s since: the same allowance for a phone clock ahead of Zerodha's as before) -
                                 * never a rolling window that a long run of failed reads could leave it behind.
                                 */
                                val sentAt: LocalDateTime = LocalDateTime.MIN,
                                /** "State unknown" said again at the session's end (15:10) while still unresolved. */
                                val lateSaid: Boolean = false)
    /**
     * 20 Nov 2026 (review: the twin edge): today's exit sales booked by these arms ([bookSale], [bookFoundRow]), by order id -> day: a lost
     * exit's search never takes one of them for its own. Saved with the book ([encodeBook]), today's only.
     */
    private val exitSales = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun noteExitSale(id: String) {
        val day = runCatching { today().toString() }.getOrDefault("")
        if (exitSales.size > 500) exitSales.entries.removeIf { it.value != day }
        exitSales[id] = day
    }
    /** "Saved lost exit dropped" said once per sale ([restoreLost]). */
    private val lostDropSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** By [LostExit.id] (never reused: a part's stale [Position.lostId] matches nothing). */
    private val lostExits = java.util.concurrent.ConcurrentHashMap<Long, LostExit>()
    private val lostSeq = java.util.concurrent.atomic.AtomicLong()
    internal const val LOST_EXIT_HOLD_MS = 10_000L
    internal const val LOST_EXIT_ASK_MS = 3_000L
    /**
     * Failed searches after which Boss is told, once, that the lost exit's state is unknown (it is never let go on failed reads:
     * [LOST_LET_GO_MS]).
     */
    internal const val LOST_EXIT_TRIES = 3
    /** A lost exit found but not finished in its part's looks' order books this long after is let go (said). */
    internal const val LOST_EXIT_FOUND_KEEP_MS = 60_000L
    /** The most one search of the order book for a lost exit may take (then: one failed try). */
    internal const val LOST_SEARCH_MS = 8_000L
    /**
     * 20 Nov 2026 (re-review finding 4, as Broker's lost replies): a lost exit is let go only on a read of the order book that succeeded
     * this long after it was sent and still does not show it - an empty search before then (the book can trail a sale that filled) keeps
     * it, and failed reads never let it go (Boss told once that its state is unknown).
     */
    internal const val LOST_LET_GO_MS = 120_000L

    /** [lostExitLook]'s answer: [hold] the lost sale's own part sells nothing now; else [found], the lost sale's order id. */
    private class LostLook(val hold: Boolean, val found: String? = null, val lost: LostExit? = null)
    private val LOST_HOLD = LostLook(hold = true)

    /** [p]'s exit [o] ([why]) on [sym] lost its reply and was not found: held for that part ([lostExits]), Boss told. Its id. Memory only. */
    private fun lostExit(p: Position, sym: String, why: String, o: com.optionslab.engine.Kite.Order, known: Set<String>, sentAt: LocalDateTime): Long {
        val id = lostSeq.incrementAndGet()
        lostExits[id] = LostExit(id, sym, managerId(p), why, o, known, epochMs(now()), label = armOf(p.arm).label, sentAt = sentAt)
        com.optionslab.app.work.Alerts.error("${armOf(p.arm).label}: the live exit of $sym got no reply from Zerodha and is not in its order book " +
            "yet. This trade sells nothing more, and the other trades on it with a stop resting wait, until the app finds it or a read of " +
            "the book at least ${LOST_LET_GO_MS / 60_000} minutes after it was sent does not show it; check Kite's order book.", "ORB live")
        return id
    }

    /** The lost exits on [sym]. Memory only. */
    private fun lostOn(sym: String): List<LostExit> = lostExits.values.filter { it.sym == sym }

    /**
     * 22 Nov 2026 (B6 review): the square-offs' look at these arms' unsettled exit sales on Zerodha contract [kite]
     * ([SquareOffExit.lostPendingProviders]): each lost sale neither let go nor booked - not found yet, or found and not booked yet, as this
     * book's own other trades wait for it ([exitLive]) - each sale on its way ([salesInFlight]), and (second round) each booked a moment
     * ago ([salesBooked]), by quantity. Memory only: never the
     * arms' lock, the book or the network. (Before this process has read the book its saved lost sales are not known: the square-offs
     * wait for it, [bookReadHint].)
     */
    internal fun unsettledSales(kite: String): Int =
        lostExits.values.filter { it.sym == kite && !it.notFound }.sumOf { it.order.quantity } + (salesInFlight[kite] ?: 0) + bookedLately(kite)

    /**
     * 22 Nov 2026 (B6 review, second round: a sale booked a moment ago may not show in a square-off's read of the books yet - an orders read
     * can miss an order placed a moment ago and a positions read can trail its fill): by Zerodha contract, each live exit sale of these arms
     * booked by its own exit ([bookSale]) - when, by the square-offs' clock, and how many filled - kept counted for the square-offs' gap after it
     * ([SquareOffExit.gapMs]). A square-off that sees it in its read as well sells less than it might (the rest goes on a later pass) -
     * never more. Memory only.
     */
    private val salesBooked = java.util.concurrent.ConcurrentHashMap<String, List<Pair<Long, Int>>>()
    private fun noteBooked(kite: String, qty: Int) {
        if (qty <= 0) return
        val now = runCatching { SquareOffExit.clock() }.getOrDefault(System.currentTimeMillis())
        val gap = SquareOffExit.gapMs
        salesBooked.compute(kite) { _, l -> l.orEmpty().filter { now - it.first in 0 until gap } + (now to qty) }
    }
    private fun bookedLately(kite: String): Int {
        val l = salesBooked[kite] ?: return 0
        val now = runCatching { SquareOffExit.clock() }.getOrDefault(System.currentTimeMillis())
        val gap = SquareOffExit.gapMs
        return l.filter { now - it.first in 0 until gap }.sumOf { it.second }
    }

    /**
     * 22 Nov 2026 (B6 review): by Zerodha contract, the quantity of these arms' exit sales on their way - from just before the order goes
     * until it is booked, refused or held as a lost sale ([exitLive]) - so a square-off whose read of the books came before it counts it as
     * going ([unsettledSales]), whatever the exit coordinator's switch says. Memory only.
     */
    private val salesInFlight = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 22 Nov 2026 (B6 review): whether this process has read the arms' book (memory): only then are its lost exit sales, kept in it across a
     * restart ([restoreLost]), known to the square-offs ([ExpirySquareOff.liveBooksReady], [FlatBySquareOff.liveBooksReady]). A book that
     * could not be read (set aside, or a live row of it dropped: [rebuildFromZerodha]) counts as read only [LOST_LET_GO_MS] later - a lost
     * sale it held is let go by then, and Zerodha's books show what it did.
     */
    internal val bookReadHint: Boolean get() = cache != null && wallMs() >= lostUnknownUntil
    /** 22 Nov 2026 (B6 review): until when (wall clock) a lost exit sale of a book that could not be read is not known ([bookReadHint]). */
    @Volatile private var lostUnknownUntil = 0L

    /**
     * 20 Nov 2026 (deep review F1): [p]'s own lost sale, while it is held for it. Memory only. (Review of the b5-orders merge: by [Position.lostId]
     * alone, whatever its stop - a part with a stop and a pending lost sale is still held and still searched; a lost stop found meanwhile is
     * never attached to it, [exitLive] and [restExchangeStop].)
     */
    private fun lostOf(p: Position): LostExit? = p.lostId?.let { lostExits[it] }

    /**
     * 20 Nov 2026 (deep review F1): what [p]'s sale on [sym] counts as already going: every other part's lost sale neither found nor let go
     * that [orders] (this exit's own read of Zerodha's order book) does not show - it may still be filling, unseen - so the contract is
     * never sold beyond what is held. One the read shows is counted there already (working, or filled for the positions-lag guard).
     */
    private suspend fun lostPending(sym: String, p: Position, orders: Collection<Broker.OrderRow>): Int {
        val lost = lostOn(sym).filter { it.foundId == null && it.id != p.lostId }.sortedBy { it.id }
        if (lost.isEmpty()) return 0
        // (A row shows a lost sale only when it could be it - NFO MIS, the same side, size, type and tag, not known when that sale went,
        // and claimed by no owner label (every sale the app knows the id of is labelled) - and each row shows one lost sale at most.)
        val owners = runCatching { Strategies.owners() }.getOrNull() ?: return lost.sumOf { it.order.quantity }
        val foundIds = lostExits.values.mapNotNull { it.foundId }.toSet()
        val free = orders.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side == "SELL" && "kite:${it.id}" !in owners &&
            it.id !in foundIds }.toMutableList()
        var pending = 0
        for (l in lost) {
            val tag = l.order.tag.filter { c -> c.isLetterOrDigit() }.take(20)
            val row = free.firstOrNull { it.qty == l.order.quantity && it.type == l.order.orderType && it.tag == tag && it.id !in l.known }
            if (row != null) free.remove(row) else pending += l.order.quantity
        }
        return pending
    }

    /** True while [l] is within its hold (no read). */
    private fun lostExitHolds(l: LostExit): Boolean = epochMs(now()) - l.sinceMs in 0 until LOST_EXIT_HOLD_MS

    /**
     * 20 Nov 2026 (audit PC; deep review F1; coordinator: no network on the tick path): the lost exit [l] as its own part's look sees it.
     * Null: go on as usual - it was not found on a read past [LOST_LET_GO_MS] (said, and let go: the part's exit sells through the forced
     * lag guard). [LostLook.hold]: within the hold, a search running or made a moment ago ([LOST_EXIT_ASK_MS]), not found yet, or the book
     * could not be read. Found: its order id (booked from the look's own order book, [bookFoundRow]).
     *
     * Past the hold a search is due at most every [LOST_EXIT_ASK_MS]: any look, regular, fast or stream, starts it in the background
     * ([searchLost], one at a time per lost sale, off the arms' lock) and returns at once - its answer, kept in memory, is acted on by the
     * next look with no wait (final review C1: never an order-book read holding the lock).
     */
    private fun lostExitLook(l: LostExit): LostLook? {
        val lost = lostExits[l.id] ?: return null
        val ms = epochMs(now())
        lost.foundId?.let { id ->
            if (!lostExits.remove(lost.id, lost)) return LOST_HOLD
            return LostLook(hold = false, found = id, lost = lost)
        }
        if (lost.notFound) {
            if (lostExits.remove(lost.id, lost)) lostNotFound(lost.sym)
            return null
        }
        if (ms - lost.sinceMs in 0 until LOST_EXIT_HOLD_MS) return LOST_HOLD
        if (lost.searching || ms - lost.askedMs in 0 until LOST_EXIT_ASK_MS) return LOST_HOLD
        val asked = lost.copy(askedMs = ms, searching = true)
        if (!lostExits.replace(lost.id, lost, asked)) return LOST_HOLD
        searchLost(asked)
        return LOST_HOLD
    }

    /** 20 Nov 2026: [l]'s background search started when one is due - nothing else (an answer waits for a look; [priceCheckLive]'s outage). */
    private fun startLostSearch(l: LostExit) {
        val lost = lostExits[l.id] ?: return
        if (lost.foundId != null || lost.notFound || lost.searching) return
        val ms = epochMs(now())
        if (ms - lost.sinceMs in 0 until LOST_EXIT_HOLD_MS || ms - lost.askedMs in 0 until LOST_EXIT_ASK_MS) return
        val asked = lost.copy(askedMs = ms, searching = true)
        if (lostExits.replace(lost.id, lost, asked)) searchLost(asked)
    }

    /**
     * 20 Nov 2026 (coordinator, on the found stop of a part held for its lost sale): in the background search, before its answer is
     * published (one at a time per lost sale), never under the arms' lock, at most [TIEBREAK_MS] (then the stop rests). The part's own lost
     * stop, once found ([Broker.peekLost], never taken) and still working at Zerodha with nothing filled, rests as its cover - except when
     * it is unambiguous that this part's lots are gone:
     *  - this part is the ONLY open live part on the contract (no other part of any book - open, settling or unconfirmed);
     *  - a fresh read of the orders shows no fill on the contract that no book accounts for: every order with a fill is this part's own
     *    lost sale (found, [ownSale]), an exit sale booked ([exitSales]) or an entry a book row holds - anything else, or a read that
     *    fails, and the stop rests;
     *  - Zerodha's positions, read fresh and lag-guarded ([ExitCoordinator.held], forced), then decide: nothing held - the stop is claimed
     *    and taken out as the app's own, confirmed by the regular pass ([sweepLostStops]' cancels: again each pass until Zerodha shows it
     *    ended, Boss told after three tries); less than the part's lots - the stop is cut to what is held; otherwise it rests.
     * Any case with more than one part on the contract: the stop rests, as before.
     */
    private suspend fun foundStopTiebreak(asked: LostExit, ownSale: String?) {
        val bk = cache ?: return
        val parts = runCatching { bk.positions.toList() }.getOrNull() ?: return
        val p = parts.firstOrNull { it.open && it.live && it.lostId == asked.id } ?: return
        if (parts.any { it !== p && it.open && it.kite == asked.sym }) return
        val label = armOf(p.arm).label
        val key = runCatching { lostKeyOf(bk, p, label, asked.sym) }.getOrNull() ?: return
        val id = Broker.peekLost(key) ?: return
        val orders = runCatching { Broker.orders(fresh = true) }.getOrNull() ?: return
        val so = orders.firstOrNull { it.id == id } ?: return
        if (!so.working || so.filled > 0) return
        val entries = parts.mapNotNull { it.entryOrderId }.toSet()
        if (orders.any { it.symbol == asked.sym && it.filled > 0 && it.id != ownSale && !exitSales.containsKey(it.id) && it.id !in entries }) return
        // (Final review: only when the part's own found sale explains the drop - never the positions alone: a blank or partial reply, or a
        // conversion to NRML, could show the lots gone while they are held. Its sale not found: the stop rests.)
        val sale = ownSale?.let { o -> orders.firstOrNull { it.id == o } } ?: return
        val held = runCatching { ExitCoordinator.held("NFO", asked.sym, "MIS", label, orders, forceLag = true) }.getOrNull() ?: return
        if (held >= p.qty) return
        if (held <= 0 && sale.filled < p.qty) return
        if (held > 0) {
            if (sale.filled != p.qty - held) return
            // (Its sale filled in part: the stop cut to what this part still holds; one that cannot be cut rests as it was.)
            val spec = runCatching { Broker.spec("NFO", asked.sym) }.getOrNull() ?: return
            val to = com.optionslab.engine.risk.ExitQty.shrinkTo(so.qty, so.filled, held, spec.lotSize.coerceAtLeast(1)) ?: return
            if (to >= so.qty) return
            val cut = runCatching {
                if (so.type == "SL-M") Broker.modify(so, to, "SL-M", null, so.trigger) else Broker.modify(so, to, "SL", stopLimit(so.trigger, spec.tickSize), so.trigger)
            }.isSuccess
            runCatching { Diag.record("orb", "$label: ${asked.sym} held $held of this part's ${p.qty} - its lost sale filled in part: its found lost stop " +
                "$id ${if (cut) "cut to $to" else "could not be cut; it rests as it was"}") }
            return
        }
        // (Its key forgotten only while it still holds this stop - never a newer lost reply under the same key.)
        if (Broker.peekLost(key) != id) return
        runCatching { Diag.record("orb", "$label: ${asked.sym} held $held - this part's lost sale filled: its found lost stop $id taken out") }
        Broker.forgetLost(key)
        Broker.claimStop(id)
        sweepCancel(id, "$label|${asked.sym}")
        cancelOwnStop(id)
    }
    /** The longest the found stop's tiebreaker may take ([foundStopTiebreak]); past it, the stop rests. */
    private const val TIEBREAK_MS = 8_000L

    /** 20 Nov 2026 (review: the twin edge): a found lost sale's own owner label - unique to that sale (its send time), never a plain exit's. */
    private fun lostLabel(l: LostExit): String {
        val at = runCatching { java.time.Instant.ofEpochMilli(l.sinceMs).atZone(com.optionslab.engine.IST).toLocalTime().toString() }.getOrDefault("${l.sinceMs}")
        return "${l.label} · ${l.why} (lost sale sent $at)"
    }

    /** 20 Nov 2026: the background search of [asked] ([lostExitLook]), off the arms' lock. */
    private fun searchLost(asked: LostExit) {
        offLock.launch(SAFETY_LANE) { runCatching { searchLostNow(asked) } }
    }

    /** TEST ONLY (20 Nov 2026): run before each search of a lost exit (null in the app). Debug builds only. */
    @Volatile internal var testLostSearch: (() -> Unit)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }

    /** TEST ONLY: whether a background search of a lost exit on [sym] is running. */
    internal fun lostSearchingForTest(sym: String): Boolean = lostOn(sym).any { it.searching }

    /**
     * 20 Nov 2026: one search of the order book for [asked] ([LOST_SEARCH_MS] at most), its answer kept in memory on the lost exit: found
     * ([LostExit.foundId]), not found ([LostExit.notFound]), or unreadable (one more failed try; the last one is "not found, unread").
     */
    private suspend fun searchLostNow(asked: LostExit) {
        var failed = false
        val found = try {
            testLostSearch?.let { f -> kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { f() } }
            kotlinx.coroutines.withTimeoutOrNull(LOST_SEARCH_MS) {
                // (Never an order another trade owns - an owner label, or an id a book row knows: after a restart the sent ids
                // are gone, and another trade's identical sale must never be taken for this one - and the earliest after it went.)
                // (Its own label - written when it was found, before a restart could lose that - never excludes it. Labels unreadable:
                // a failed read, never a search that could take another's sale.)
                val own = lostLabel(asked)
                val labelled = Strategies.owners().filter { (k, v) -> k.startsWith("kite:") && v != own }.keys.map { k -> k.removePrefix("kite:") }
                val rows = runCatching { cache?.positions?.toList() }.getOrNull().orEmpty().flatMap { listOfNotNull(it.entryOrderId, it.stopOrderId) }
                val others = lostExits.values.filter { it.id != asked.id }.mapNotNull { it.foundId }
                val known = asked.known + labelled + rows + others + exitSales.keys
                Broker.findRecent(asked.order, known, since = asked.sentAt.takeIf { it != LocalDateTime.MIN }?.minusMinutes(3), earliest = true) ?: ""
            } ?: run { failed = true; null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // (Never left "searching" for ever: the next due look searches again.)
            lostExits[asked.id]?.let { cur -> lostExits.replace(asked.id, cur, cur.copy(searching = false)) }
            throw e
        } catch (e: Exception) { failed = true; null }
        val cur = lostExits[asked.id] ?: return
        val next = when {
            failed -> cur.copy(searching = false, fails = cur.fails + 1)
            // (Not found: let go only when this successful read STARTED past [LOST_LET_GO_MS] from the sale; sooner, searched again.)
            found.isNullOrEmpty() -> cur.copy(searching = false, fails = 0, notFound = asked.askedMs - cur.sinceMs >= LOST_LET_GO_MS)
            else -> cur.copy(searching = false, fails = 0, foundId = found)
        }
        // (Said again once at the session's end - 15:10 - while it is still unknown: the trades on the contract are still waiting.)
        val late = failed && next.fails >= LOST_EXIT_TRIES && !cur.lateSaid && !now().toLocalTime().isBefore(OrbRules.SQUARE_OFF)
        // (The found stop's tiebreaker first, while this search still counts as running - never two at once, nor a let-go acted on meanwhile.)
        if (!failed) try {
            kotlinx.coroutines.withTimeoutOrNull(TIEBREAK_MS) { foundStopTiebreak(asked, found?.takeIf { it.isNotEmpty() }) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // (Never left "searching" for ever: the next due look searches again.)
            lostExits[asked.id]?.let { c -> lostExits.replace(asked.id, c, c.copy(searching = false)) }
            throw e
        } catch (_: Exception) { }
        lostExits.replace(asked.id, cur, if (late) next.copy(lateSaid = true) else next)
        if (failed && (next.fails == LOST_EXIT_TRIES || late)) {
            runCatching { Diag.record("orb", "${asked.sym}: the lost exit could not be searched for ($LOST_EXIT_TRIES reads failed); its state is unknown") }
            com.optionslab.app.work.Alerts.error("ORB live: the lost exit of ${asked.sym} could not be looked for - Zerodha's order book could not be " +
                "read. Its state is unknown: the app keeps looking and sells nothing more on it meanwhile; check Kite's order book.", "ORB live")
        }
        if (found?.isNotEmpty() == true) {
            runCatching { Diag.record("orb", "${asked.sym}: the lost exit found in Zerodha's book ($found): it is the exit") }
            // (Labelled at once with its own lost sale's label - never one another exit of the same arm and reason writes - after its id
            // is kept; and the book saved now, off the lock, so a restart keeps the found id.)
            runCatching { Strategies.tagOwner("kite:$found", lostLabel(asked)) }
            offLock.launch { runCatching { lock.withLock { saveSoon(book()) } } }
        }
    }

    /**
     * 20 Nov 2026 (review; coordinator: never a wait under the lock): [p]'s lost exit [id] ([why]) found in Zerodha's book, booked from the
     * look's own read of it ([row]) - never awaited. Finished with a fill: that is the exit ([bookFilled]: a part fill leaves the rest open with
     * no stop). Finished with nothing filled: [p] stays open with no stop (the next look rests one or sells). Not finished, or not in that
     * read: null - the next look books it. [p] normally has no stop resting (nothing attaches one while its lost sale is pending); one it
     * has all the same (a book saved by an earlier build) is kept when nothing filled, and taken out as the app's own - in the background -
     * when the found sale filled, never left resting unwatched beside it.
     */
    private suspend fun bookFoundRow(p: Position, label: String, sym: String, why: String, id: String, row: Broker.OrderRow?): Pair<Position, Position?>? {
        if (row == null || row.status !in DONE) return null
        runCatching { Strategies.tagOwnerSoon("kite:$id", "$label · $why") }           // (relabelled at booking: plain, as any exit sale)
        runCatching { noteExitSale(id) }
        if (row.filled <= 0) return p to null
        p.stopOrderId?.let { s ->
            runCatching { Diag.record("orb", "$label: $sym's lost exit $id found filled while a stop $s rested: the stop taken out") }
            offLock.launch(SAFETY_LANE) { runCatching { cancelOwnStop(s) } }
        }
        return bookFilled(p, label, sym, why, row.filled, row.avg, id)
    }

    private fun lostNotFound(sym: String) {
        val what = "not found in Zerodha's order book on a read at least ${LOST_LET_GO_MS / 60_000} minutes after it was sent"
        runCatching { Diag.record("orb", "$sym: the lost exit was $what; the exit may go again") }
        com.optionslab.app.work.Alerts.error("ORB live: the lost exit of $sym was $what. The app reads Zerodha again and sells only what is still held; " +
            "check Kite's order book.", "ORB live")
    }

    /** TEST ONLY: whether a lost exit is held on [sym]. */
    internal fun lostExitForTest(sym: String): Boolean = lostOn(sym).isNotEmpty()
    /** TEST ONLY: the found order id of [sym]'s lost exit, if any. */
    internal fun lostFoundForTest(sym: String): String? = lostOn(sym).firstNotNullOfOrNull { it.foundId }
    /** TEST ONLY: today's booked exit sales the lost search leaves out. */
    internal fun exitSalesForTest(): Set<String> = exitSales.keys.toSet()
    /** TEST ONLY: whether order [id] is marked as the app's own cancel. */
    internal fun ownCancelForTest(id: String): Boolean = ownCancel(id)
    /** TEST ONLY (22 Nov 2026): whether order [id] is marked by Boss's manual exit from the app or a square-off ([markOwnCancels]). */
    internal fun exitMarkedForTest(id: String): Boolean = exitMarked(id)
    /**
     * TEST ONLY (22 Nov 2026): the sizes of the cancels' memory - the stops being cancelled for closed trades, their days and tries (an
     * erase keeps them), and the own-cancel and exit marks taken back from the book (an erase drops them).
     */
    internal fun cancelMemoryForTest(): List<Int> =
        listOf(synchronized(sweepCancels) { sweepCancels.size }, sweepDays.size, sweepTries.size, ownCancelsRestored.size, exitMarksRestored.size)
    /** TEST ONLY (22 Nov 2026): the sales booked a moment ago ([salesBooked]) forgotten, as by a new process (each test's own start). */
    internal fun forgetSalesBookedForTest() { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; salesBooked.clear() }
    /** TEST ONLY: the owner label [sym]'s (first) lost exit would give its sale when found. */
    internal fun lostLabelForTest(sym: String): String? = lostOn(sym).minByOrNull { it.id }?.let { lostLabel(it) }

    /**
     * TEST ONLY: a look on [sym]'s (first) lost exit by the part whose sale was lost, then - a search started - the next look on its answer:
     * "hold", "none" (go on), or "found <id>".
     */
    internal suspend fun lostExitLookForTest(sym: String): String {
        val first = lostOn(sym).minByOrNull { it.id } ?: return "none"
        var look = lostExitLook(first)
        // (A search it started is waited for here - in the test only, off the lock - and its answer taken as the next look would.)
        if (lostExits[first.id]?.searching == true) {
            val end = System.currentTimeMillis() + 15_000
            while (lostExits[first.id]?.searching == true && System.currentTimeMillis() < end) kotlinx.coroutines.delay(10)
            look = lostExits[first.id]?.let { lostExitLook(it) }
        }
        return if (look == null) "none" else if (look.hold) "hold" else "found ${look.found}"
    }

    /** 18 Nov 2026 (audit: B1): Zerodha's orders and positions as the pass that decided an exit already read them ([exitLive]). */
    private class Pre(val orders: Collection<Broker.OrderRow>, val net: List<Broker.Position>)

    /**
     * Take the resting stop out first, then MARKET SELL what is held; if the stop filled meanwhile, that is the exit.
     * Returns the position, and (when only part sold) the rest, still open, to be sold on the next pass.
     */
    private suspend fun exitLive(b: Book, given: Position, sym: String, why: String, pre: Pre? = null,
                                 ltp: Double? = null): Pair<Position, Position?> {
        // 22 Nov 2026 (B6 review, second round: this exit and a square-off each looked at the other, then both could back off with a stop
        // already out, or both sell): one seller at a time on the contract ([SquareOffExit.claimExit]) - taken FIRST, before any stop of it
        // comes out, and released once this exit's sale went or it gave up. Held by a square-off: this exit waits, every stop resting (the
        // square-off sells it); the next look tries again. (A part's own exit inside this one - a split - holds it too.)
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", CLAIM_OWNER) ?: run {
            val minute = now().withSecond(0).withNano(0)
            if (coverSaid.size > 200) coverSaid.clear()
            if (coverSaid.put(managerId(given) + "|claim", minute) != minute)
                runCatching { Diag.record("orb", "${armOf(given.arm).label}: $sym's exit (${why.replace('_', ' ')}) waits: " +
                    "${SquareOffExit.claimWords(SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))} is selling it; this exit takes no stop out meanwhile") }
            return given to null
        }
        try { return exitLiveClaimed(b, given, sym, why, pre, ltp) } finally { claim.release() }
    }

    /** 22 Nov 2026 (B6 review): these arms' name on a contract's exit claim ([SquareOffExit.claimExit]). */
    internal const val CLAIM_OWNER = "orb"

    /** [exitLive] with the contract's exit claim held. */
    private suspend fun exitLiveClaimed(b: Book, given: Position, sym: String, why: String, pre: Pre?,
                                        ltp: Double?): Pair<Position, Position?> {
        // 6 Nov: Boss is exiting this contract by hand ([ArmLive.handsOff]): the arm's own sale waits (his covers it; next pass).
        if (ArmLive.handsOffNow("NFO", sym)) return given to null
        // 20 Nov 2026 (audit PC: a sale whose reply was lost could be sent again): while the lost sale of THIS part of a trade is unresolved
        // it sells nothing more - held; searched for in the background and booked by a later look ([lostExitLook], never the network under
        // the lock). Known not found, it is said and the exit goes on as below (reading Zerodha's books first, the forced lag guard counting
        // what is still held). Any other trade on the contract is never held here: it counts that sale as going ([lostPending]). Memory only.
        // (Review of the b5-orders merge, C1: FIRST, before this trade's lost stop is looked at - a found lost stop attached here would have
        // let the part sell again beside its first sale; it is never attached while that sale is pending, and rests as cover meanwhile.)
        lostOf(given)?.let { own ->
            if (!own.notFound || !lostExits.remove(own.id, own)) return given to null
            lostNotFound(sym)
        }
        // 20 Nov 2026 (delta review): this trade's own stop whose answer was lost may be resting at Zerodha - one of its own sells.
        //  - Found for all it holds (one for more cut to it first, [fitFound]): it is the trade's stop, taken out first as any (only when
        //    its sale will go - the usual path below).
        //  - Found for fewer: the trade is split - the part it covers keeps it as its stop and exits through the usual path (its stop out
        //    only when its sale will go; refused, a stop is put back); the rest, with none, is sold on the next pass.
        //  - Found beside a stop it already has (an extra cover): claimed and taken out, confirmed (a part it sold is booked closed).
        //  - Still looked for: a stop-level exit (the price [ltp] at or under that stop's trigger - a stop sent late would be refused, and
        //    one that landed would show in the book within seconds) waits at most [LOST_EXIT_WAIT_MS]; every other exit waits until the
        //    search ends (found, or given up on after a good read, [Broker.lookLost]) - never a sale beside a stop that may rest.
        val labelG = armOf(given.arm).label
        val ownLost = lostKeyOf(b, given, labelG, sym)
        val p0 = when (val l = Broker.lookLost(ownLost)) {
            is Broker.LostLook.Found -> {
                val specG = runCatching { Broker.spec("NFO", sym) }.getOrNull()
                if (b.positions.any { it.open && it.stopOrderId == l.id }) given
                else if (given.stopOrderId == null && l.qty < given.qty) {
                    runCatching { Strategies.tagOwnerSoon("kite:${l.id}", "$labelG · stop") }
                    Broker.claimStop(l.id)
                    val share = l.qty.toDouble() / given.qty
                    val part = given.copy(qty = l.qty, stopOrderId = l.id, stopTrigger = l.trigger ?: given.stopTrigger, appCancelled = false,
                        charges = given.charges * share)
                    val rest = given.copy(qty = given.qty - l.qty, charges = given.charges * (1 - share))
                    runCatching { Diag.record("orb", "$labelG: its lost stop ${l.id} on $sym, found at its exit, covers ${l.qty} of ${given.qty}: " +
                        "that part exits with it; the rest is sold next pass") }
                    val (done, left) = exitLive(b, part, sym, why, pre, ltp)
                    // (A part of that part left unsold with a stop of its own stays its own row - never folded into one with none; one with
                    // none joins the rest - one row, sold next pass.)
                    if (left?.stopOrderId != null) { b.positions += left; return done to rest }
                    return done to (left?.let { rest.copy(qty = rest.qty + it.qty, charges = rest.charges + it.charges) } ?: rest)
                } else if (given.stopOrderId == null &&
                    // (22 Nov 2026, B6 review: taken out - with or without a part filled - it goes on below, which books what it sold.)
                    fitFound(labelG, l, given.qty, specG?.lotSize?.coerceAtLeast(1) ?: 1, specG?.tickSize ?: 0.05, b) == Fit.Kept) {
                    runCatching { Strategies.tagOwnerSoon("kite:${l.id}", "$labelG · stop") }
                    Broker.claimStop(l.id)
                    given.copy(stopOrderId = l.id, stopTrigger = l.trigger ?: given.stopTrigger, appCancelled = false)
                } else {
                    Broker.claimStop(l.id)
                    cancelOwnStop(l.id, b)
                    val st = runCatching { Broker.orderState(l.id) }.getOrNull()
                    if (st == null || st.status !in DONE) {
                        // (22 Nov 2026, B6 review: still working - the cancel may not have taken: its own-cancel mark ends soon, [ownCancelDidNotTake].)
                        if (st != null && st.status in STILL_WORKING) ownCancelDidNotTake(l.id)
                        runCatching { Diag.record("orb", "$labelG: its lost stop ${l.id} on $sym, found at its exit, is not confirmed out yet: the exit waits a pass") }
                        return given to null
                    }
                    if (st.filled > 0) {
                        val sold = minOf(st.filled, given.qty)
                        val px = st.avgPrice.takeIf { it > 0 } ?: l.trigger ?: given.entry
                        val share = sold.toDouble() / given.qty
                        val closed = given.copy(qty = sold, exit = px, exitTime = now(), why = stopWhy(given), stopOrderId = null,
                            charges = given.charges * share + kiteCharge("SELL", px, sold))
                        return if (sold >= given.qty) closed to null else closed to given.copy(qty = given.qty - sold, charges = given.charges * (1 - share))
                    }
                    given
                }
            }
            Broker.LostLook.Searching -> {
                // (At the highest of the lost stop's trigger, the trade's own level and the lock or rule stop it has earned since - its
                // trigger cannot catch up while its stop is looked for; and a stop, lock or rule-stop exit is one whatever the price.)
                val tickG = runCatching { Broker.spec("NFO", sym).tickSize }.getOrDefault(OrbRules.TICK)
                val trig = listOfNotNull(Broker.lostTrigger(ownLost), given.stopTrigger, lockOrStop(given, tickG), fixedStopOf(given, tickG)).maxOrNull()
                val stopExit = why == "stop" || why == "profit_lock" || why == ruleWhy(given, tickG) || why == fixedWhy(given)
                val stopLevel = stopExit || (ltp != null && trig != null && ltp <= trig + 1e-9)
                // (Every other exit waits for the search's end. The search reads the book in the stop checks' lane (SAFETY: only exits and
                // stops go first, never entries or Jarvis), so it is not starved; a book that cannot be read keeps it going, and then the
                // exit's own read of the book fails too - it could not sell meanwhile either.)
                if (!stopLevel || (Broker.lostAgeMs(ownLost) ?: Long.MAX_VALUE) < LOST_EXIT_WAIT_MS) {
                    runCatching { Diag.record("orb", "$labelG: $sym's exit waits for its stop whose answer was lost (it may be resting)") }
                    return given to null
                }
                given
            }
            else -> given
        }
        // 19 Nov 2026 (review): Stop for today or the session's end refused a moment ago: its stop was put back; not taken out and sold
        // again every look - once every [REFUSED_RETRY_MS] (the stop rests meanwhile).
        if (why in ALWAYS_EXITS && p0.stopOrderId?.let { refusedAt[it] }?.let { epochMs(now()) - it < REFUSED_RETRY_MS } == true) return p0 to null
        // 20 Nov 2026 (re-review: a stop never comes out unless a sale will go): while another part's lost sale on the contract is
        // unresolved or found but not yet booked - it may have filled while Zerodha's positions do not show it - a trade with a resting
        // stop leaves it resting and its exit waits, any exit, 15:10 and Stop for today included. No read decides it (memory only); the
        // exit goes as usual, with its own fresh lag-guarded read, as soon as that sale is booked or let go.
        if (p0.stopOrderId != null && lostOn(sym).any { it.id != p0.lostId && !it.notFound }) {
            val minute = now().withSecond(0).withNano(0)
            if (coverSaid.size > 200) coverSaid.clear()
            if (coverSaid.put(managerId(p0) + "|lostwait", minute) != minute)
                runCatching { Diag.record("orb", "${armOf(p0.arm).label}: $sym's exit (${why.replace('_', ' ')}) waits for another trade's lost sale " +
                    "to be found; its stop rests at Zerodha meanwhile") }
            return p0 to null
        }
        // 22 Nov 2026 (B6 review: from 15:05 the expiry square-off sells an expiring lot of these arms itself, and the flat-by one still open
        // later; with the exit coordinator in shadow - its default - nothing kept the two senders apart): a square-off's sale working on the
        // contract (in the read this exit was decided on) or sent on it within its gap ([SquareOffExit.inGap]: a read can miss one sent a
        // moment ago) is selling it - this exit waits, its stop resting (the square-off takes it out itself), and the next look reads again.
        // Checked again just before the sale ([squareOffSelling]).
        if (squareOffSelling(sym, pre?.orders)) {
            val minute = now().withSecond(0).withNano(0)
            if (coverSaid.size > 200) coverSaid.clear()
            if (coverSaid.put(managerId(p0) + "|squareoff", minute) != minute)
                runCatching { Diag.record("orb", "${armOf(p0.arm).label}: $sym's exit (${why.replace('_', ' ')}) waits: a square-off is selling it") }
            return p0 to null
        }
        runCatching { OrderTiming.decided() }
        val label = armOf(p0.arm).label
        // 18 Nov 2026 (audit: B1, Liquidity's index and time exits skipped the "shown gone but not confirmed" guard - they took the
        // resting stop out, read nothing held on the same bad read and left the lot with no stop): what is held is read BEFORE a resting
        // stop is touched - the pass's own reads ([pre]) when the caller has them, else read now (orders first, then positions). Shown
        // gone (no row, or a row at 0), or not readable, the exit waits with its stop resting (the price check books it closed only once
        // that is confirmed, [goneConfirmed]).
        // 18 Nov 2026 (audit: B2, the stop was cancelled before the app knew a sale could go, and most returns then left the lot with no
        // stop): from the same reads, what may be sold is worked out FIRST (what is held less every other working sale - this trade's own
        // stop left out, as it is the one taken out); the stop comes out only when a sale will go, and that holds for Boss's Stop for
        // today and 15:10 too (they read again next pass; nothing else changes for them).
        // (Read here only while a stop rests: with none, nothing is taken out first, and the exit turn below reads again as before.)
        val seen = pre ?: if (p0.stopOrderId == null) null else run {
            val o = runCatching { Broker.orders() }.getOrNull()
            val n = runCatching { Broker.positionBook().net }.getOrNull()
            if (o != null && n != null) Pre(o, n) else null
        }
        if (seen == null && p0.stopOrderId != null) return p0 to null
        var p = p0
        // What a sell may still cover beside the other working sales (the pre-read's): the most a stop put back may rest for.
        var room = p0.qty
        if (seen != null) {
            // (20 Nov 2026, deep review F1: less a lost sale of another part that may still be filling, unseen - [lostPending].)
            val heldRaw = seen.net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.qty }
            val held = (heldRaw - lostPending(sym, p, seen.orders)).coerceAtLeast(0)
            // (Shown gone - or all of it covered by another part's lost sale that may still be filling: the stop stays resting.)
            if (p.stopOrderId != null && held <= 0) return p to null
            val lot = runCatching { Broker.spec("NFO", sym).lotSize }.getOrNull()
            if (lot != null && held > 0) {
                fun left(l: List<Broker.OrderRow>) = l.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) }
                var others = seen.orders.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side == "SELL" && it.working &&
                    it.id != p.stopOrderId }
                room = com.optionslab.engine.risk.ExitQty.sendable(held, left(others), p.qty, lot)
                if (room <= 0) {
                    val owners = runCatching { Strategies.owners() }.getOrDefault(emptyMap())
                    // (B2: a resting stop of these arms ("iraorb") that no trade here and no owner label claims - a stop whose reply was
                    // lost - is this trade's when it has none: adopted as its stop, then taken out as its own before the sale.)
                    val stops = b.positions.mapNotNull { it.stopOrderId }.toSet()
                    val orphan = if (p.stopOrderId != null) null else others.firstOrNull { it.tag == "iraorb" && (it.type == "SL" || it.type == "SL-M") &&
                        it.id !in stops && "kite:${it.id}" !in owners && !Broker.lostMayBe(it, ownLost) }
                    if (orphan != null) {
                        p = p.copy(stopOrderId = orphan.id, stopTrigger = orphan.trigger.takeIf { it > 0 } ?: p.stopTrigger, appCancelled = false)
                        runCatching { Strategies.tagOwnerSoon("kite:${orphan.id}", "$label · stop") }
                        Broker.forgetLost(ownLost); Broker.claimStop(orphan.id)
                        runCatching { Diag.record("orb", "$label: a stop of these arms on $sym that no trade claimed (order ${orphan.id}) is taken as this trade's stop") }
                        others = others.filter { it.id != orphan.id }
                        room = com.optionslab.engine.risk.ExitQty.sendable(held, left(others), p.qty, lot)
                    }
                    // (B2: Boss's own sell order in Kite - no tag, no owner label - covering the lot: said every minute. For a stop-level
                    // exit or a square-off it is cancelled and confirmed out first, then the app sells (as the square-offs do,
                    // [SquareOffExit]); a target or an index exit waits for him.)
                    // (Never while Zerodha holds more than the app's live trades on it - both books': Boss holds lots of his own there,
                    // which his order may be for.)
                    val bosses = others.filter { it.tag.isBlank() && "kite:${it.id}" !in owners }
                    if (room <= 0 && bosses.isNotEmpty()) {
                        // (19 Nov 2026, second review: never an unconfirmed row - an entry written down before its order went, a lost-id
                        // entry, the arms' live copies' unconfirmed buys - as shrinkToHeld: one may hold nothing and hide Boss's own lots.)
                        val apps = b.positions.filter { it.open && it.live && !it.unconfirmed && it.kite == sym }.sumOf { it.qty } +
                            ArmLive.view.value.openReal.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side > 0 && !it.unconfirmed }.sumOf { it.qty }
                        // (Against what Zerodha holds itself: the app's trades there - a part whose lost sale is pending included.)
                        val cancels = why !in TAKE_EXITS && heldRaw <= apps
                        var out = cancels
                        if (cancels) for (o in bosses) {
                            runCatching { Broker.cancel(o.id, o.variety) }
                            val st = runCatching { Broker.orderState(o.id)?.status }.getOrNull()
                            if (st != "CANCELLED" && st != "REJECTED") out = false
                        }
                        val minute = now().withSecond(0).withNano(0)
                        if (bossSaid.size > 200) bossSaid.clear()
                        if (bossSaid.put(managerId(p), minute) != minute) com.optionslab.app.work.Alerts.error("$label: your own sell order on $sym in Kite " +
                            "covers the lot the app is exiting (${why.replace('_', ' ')}). " + when {
                                out -> "It was cancelled; the app now sends its own exit sale (its fill or refusal is shown as usual)."
                                cancels -> "It could not be cancelled yet (or it filled); the app reads Zerodha again on its next check."
                                else -> "The app's exit waits for it; cancel it in Kite if you want the app to sell."
                            }, "ORB live")
                        // (Filled meanwhile, or not out yet: nothing more this pass - the next reads again; this trade's stop rests.)
                        if (cancels && !out) return p to null
                        if (out) {
                            others = others.filter { o -> bosses.none { it.id == o.id } }
                            room = com.optionslab.engine.risk.ExitQty.sendable(held, left(others), p.qty, lot)
                        }
                    }
                    // Nothing can be sold now: a resting stop is left resting (never taken out for a sale that cannot go); next pass.
                    if (room <= 0 && p.stopOrderId != null) return p to null
                }
            }
        }
        // The sell certainly did not go: a stop that was taken out is placed again at once so the position is never left unprotected.
        // F3: back at the lock its best price has earned when that is above its own trigger - never the -40 once a rung is
        // earned; if Zerodha refuses that (a sell stop must rest below the price), at its own trigger as before.
        // 18 Nov 2026 (audit: B2): for at most [cap] (what the reads show a sell may cover: never more than is held beside the other
        // working sales), and never beside a square-off's or this arm's own working sale, at the 15:05 expiry square-off, from 15:10,
        // with Stop for today, or for a stop Boss cancelled himself - then said, and the app watches it and sells it itself.
        // 19 Nov 2026 (second review, coordinator's decision): a sale REFUSED for certain ([refused]: the app's own limits, Zerodha's
        // definite refusal, an order that ended with nothing filled) always puts the stop back, as before B2 - with Stop for today on and
        // from 15:10 too (the next pass takes it out again for its own sale): a lot is never left with no stop because a sale was refused.
        // (Coordinator, later the same day: for no more than [cap] - Zerodha's holding less every other working sale - the trade split
        // when that is less; nothing left: none, said each minute.) The other holds above are for a sale that did not go because a read failed.
        // (The part left with none gets a stop from the price check only once the other sales no longer cover it ([restExchangeStop] caps
        // it the same way); meanwhile none rests for it and that is said each minute. Main always put the stop back after a refused
        // sale; this keeps that, capped so it never sells more than is held - only a lower risk, so no CLAUDE.md change.)
        suspend fun unsold(cap: Int, orders: Collection<Broker.OrderRow>?, refused: Boolean = false): Pair<Position, Position?> {
            if (p.stopOrderId == null) return p to null
            val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: return p.copy(stopOrderId = null) to null
            val lot = spec.lotSize.coerceAtLeast(1)
            // (19 Nov 2026, coordinator: a refused sale's put-back is capped too - [cap] is what the sale itself could cover, Zerodha's
            // holding less every other working sale on the contract - never a stop for 90 beside another sale for 60 when 90 are held.)
            val qty = minOf(p.qty, cap) / lot * lot
            val t = now()
            val list = orders.orEmpty()
            val owners = runCatching { Strategies.owners() }.getOrDefault(emptyMap())
            val ownSale = list.any { it.symbol == sym && it.working && it.side == "SELL" && it.id != p.stopOrderId && it.tag == "iraorb" &&
                it.type != "SL" && it.type != "SL-M" && (owners["kite:${it.id}"]?.substringBefore(" · ")?.let { l -> l == label } ?: true) }
            // (A refused Stop-for-today or 15:10 sale: the stop put back is not taken out again for [REFUSED_RETRY_MS] - by that stop's id,
            // so a part left with none is never held back.)
            fun putBack(id: String?) { if (refused && why in ALWAYS_EXITS && id != null) { if (refusedAt.size > 200) refusedAt.clear(); refusedAt[id] = epochMs(t) } }
            if (refused && qty <= 0) {
                coverSay(p, "$label: the exit of $sym was refused and no stop was put back: other working sales already cover all Zerodha holds " +
                    "of it - they are its only cover. Check $sym in Kite.")
                return p.copy(stopOrderId = null) to null
            }
            val heldBack = when {
                refused -> null
                p.stopByYou -> "you cancelled its stop yourself"
                runCatching { Strategies.stoppedToday() }.getOrDefault(false) -> "Stop for today"
                !t.toLocalTime().isBefore(OrbRules.SQUARE_OFF) -> "the square-off time"
                expiryClosing(p.symbol, sym, t, list) || list.any { it.symbol == sym && it.working && it.tag in SquareOffExit.TAGS } ||
                    SquareOffExit.inGap("NFO", sym, "MIS") -> "a square-off is selling it"
                ownSale -> "its own sale is still working"
                qty <= 0 -> "nothing it may cover"
                else -> null
            }
            if (heldBack != null) {
                com.optionslab.app.work.Alerts.error("$label: the exit of $sym did not go and its stop at Zerodha was not put back ($heldBack). " +
                    "The app watches it and sells it itself.", "ORB live")
                return p.copy(stopOrderId = null) to null
            }
            // (A stop for fewer than the trade holds: the trade is split - the part the stop covers keeps it, the rest stays open with
            // none, the app watching it - so a fill of that stop is never booked for more than it sold.)
            // (20 Nov 2026, delta review: on what the stop really covers - a lost stop found for fewer covers only its own.)
            fun rested(q: Position, covers: Int = qty): Pair<Position, Position?> {
                if (q.stopOrderId == null || covers >= q.qty) return q to null
                val part = covers.toDouble() / q.qty
                return q.copy(qty = covers, charges = q.charges * part) to q.copy(qty = q.qty - covers, stopOrderId = null, charges = q.charges * (1 - part))
            }
            val liq = armOf(p.arm).liquidity
            val lock = lockOrStop(p, spec.tickSize)?.takeIf { l -> ladderTarget(p) != null && l > (p.stopTrigger ?: Double.NEGATIVE_INFINITY) + 1e-9 }
            if (lock != null) {
                val atLock = placeStop(label, sym, qty, spec.lotSize, spec.tickSize, p.entry, knownKite(b), liquidity = liq, at = lock,
                    lostKey = ownLost)
                // (22 Nov 2026, B6 review: its own lost stop found meanwhile and taken out after part of it filled - that part booked closed.)
                atLock.sold?.let { return stopSold(p, it, sym) }
                val (id, trig, _, covers) = atLock
                if (id != null) { putBack(id); placed(sym, covers, id); return rested(p.copy(stopOrderId = id, stopTrigger = trig ?: lock), covers) }
            }
            val again = placeStop(label, sym, qty, spec.lotSize, spec.tickSize, p.entry, knownKite(b), liquidity = liq, at = p.stopTrigger,
                lostKey = ownLost)
            again.sold?.let { return stopSold(p, it, sym) }
            val (id, trig, _, covers) = again
            putBack(id)
            if (id != null) placed(sym, covers, id)
            return rested(p.copy(stopOrderId = id, stopTrigger = trig ?: p.stopTrigger), covers)
        }
        p.stopOrderId?.let { id ->
            // The resting exchange stop comes out FIRST (timed for the order speed card); one that already filled is the exit.
            val c0 = System.currentTimeMillis()
            cancelOwnStop(id, b)
            val st = runCatching { Broker.orderState(id) }.getOrNull()
            runCatching { OrderTiming.stopCancel(System.currentTimeMillis() - c0) }
            if (st?.status == "COMPLETE") {
                val px = st.avgPrice.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                return p.copy(exit = px, exitTime = now(), why = stopWhy(p), stopOrderId = null,
                    charges = p.charges + kiteCharge("SELL", px, p.qty)) to null
            }
            // Only sell once the stop is known to be out: a stop still working plus a market sell could both fill.
            // (16 Nov review: marked as the app's own cancel, so the next look - after a restart too - never takes it as Boss's.)
            // (22 Nov 2026, B6 review: Zerodha shows it still working - the cancel may not have taken: its own-cancel mark ends once the stop
            // is still seen working a moment later ([ownCancelDidNotTake]), so Boss's own later cancel of it in Kite is his; the next look
            // that sells sends the cancel again, marked afresh.)
            if (st != null && st.status in STILL_WORKING) ownCancelDidNotTake(id)
            if (st == null || st.status !in setOf("CANCELLED", "REJECTED")) return p.copy(appCancelled = true) to null
        }
        // What is held and what is already on its way out, read again after the stop came out. Only this position's own
        // orders are ever cancelled here: another sell resting on the symbol (the other arm's stop, a protection, the
        // owner's own order, an earlier pass's sell whose reply was lost) is subtracted instead, so both filling never
        // turns the long into a short.
        // 20 Oct, enhancement round 2: read and sent in the contract's one exit turn, from a read that shows every exit sent from
        // here ([ExitCoordinator]): another sender's exit sent a moment ago is subtracted, never sold again.
        // 18 Nov 2026 (audit: B2): every return below without a sale puts a stop that was taken out back at once ([unsold]); the sale's
        // quantity and the read it came from are kept for that.
        var sent = 0
        var read: Collection<Broker.OrderRow>? = seen?.orders
        // 22 Nov 2026 (B6 review): this sale counted as unsettled ([salesInFlight]) from just before it goes until it is booked, refused or
        // held as a lost sale - a square-off that read the books before it went never sells beside it.
        var inFlight = 0
        try {
            val id = ExitCoordinator.turn("NFO", sym, label) {
                val book = runCatching { ExitCoordinator.orders("NFO", sym, label) }.getOrNull() ?: return unsold(room, read)
                read = book
                val working = book.filter { it.working && it.symbol == sym && it.side == "SELL" && it.product == "MIS" }
                // 21 Oct, enhancement round 3 (idea 1): held, checked against that read's fills ([ExitCoordinator.held]).
                // 20 Nov 2026 (audit PC, as the arms' live copies since 18 Nov): the positions-lag guard acts here whatever its switch says
                // (forceLag: it only ever sends less) - an exit that filled a moment ago is never sold again while the positions catch up.
                val heldNow = runCatching { ExitCoordinator.held("NFO", sym, "MIS", label, book, forceLag = lagForced()) }.getOrNull() ?: return unsold(room, book)
                val still = (heldNow - lostPending(sym, p, book)).coerceAtLeast(0)
                val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: return unsold(room, book)
                val workingLeft = working.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) }
                val qty = com.optionslab.engine.risk.ExitQty.sendable(still, workingLeft, p.qty, spec.lotSize)
                // Gone, or covered by what is working: next pass. (B2: covered by another sale that appeared since - never a stop beside it;
                // gone on this read although the read before showed it held - never a stop that could sell into a short: the price check
                // puts it back within a minute while Zerodha shows it held, [restExchangeStop].)
                if (qty <= 0) {
                    if (p.stopOrderId != null) {
                        // (While another part's lost sale is pending no stop goes back on the contract: said loudly, the lot has none.)
                        if (lostOn(sym).any { !it.notFound && it.id != p.lostId }) coverSay(p, "$label: the exit of $sym found nothing to sell after " +
                            "its stop came out, and no stop goes back while another trade's lost sale on it is unresolved. Check $sym in Kite.")
                        else runCatching { Diag.record("orb", "$label: $sym's exit found nothing to sell after its stop came out (held $still); " +
                            "the price check puts the stop back while Zerodha shows it held") }
                    }
                    return p.copy(stopOrderId = null) to null
                }
                sent = qty
                val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, qty, spec.lotSize, "MIS", "MARKET", null, spec.tickSize, "NFO", "iraorb")
                val bad = com.optionslab.engine.Kite.refusals(o, AppSettings.load().limits(), Broker.sentToday(), false, exit = true)
                if (bad.isNotEmpty()) {
                    com.optionslab.app.work.Alerts.error("$label: the Zerodha exit was not sent (${bad.joinToString("; ")}). Close $sym in Trade.", "ORB live")
                    return unsold(qty, book, refused = true)
                }
                // 22 Nov 2026 (B6 review): a square-off's sale sent on it since this exit was decided is selling it - nothing from here beside
                // it ([unsold] puts no stop back beside a square-off's sale; the app watches the level, and the square-off sells what is left).
                // This sale counts as on its way FIRST, then the gap is looked at - a square-off starts its gap before its own last look at
                // these sales ([SquareOffExit.lostPendingProviders]): of the two senders, at most one sells.
                salesInFlight.merge(sym, qty, Int::plus); inFlight = qty
                SquareOffExit.saleStarting(sym)
                if (squareOffSelling(sym, book)) return unsold(qty, book)
                val sentAt = runCatching { Market.now().toLocalDateTime() }.getOrDefault(java.time.ZonedDateTime.now(com.optionslab.engine.IST).toLocalDateTime())
                try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
                    val definite = Broker.definite(e)
                    val found = if (definite) null else runCatching { Broker.findRecentRetrying(o, knownKite(b) + working.map { it.id }) }.getOrNull()
                    found ?: run {
                        // Refused outright: nothing went, so the stop goes back. A lost reply with no order found may still have gone.
                        if (definite) {
                            com.optionslab.app.work.Alerts.error("$label: the Zerodha exit failed (${e.message}); retrying on the next pass.", "ORB live")
                            return unsold(qty, book, refused = true)
                        }
                        // 20 Nov 2026 (audit PC): held for this part - it sells and rests nothing more while that sale may still be selling
                        // ([lostExits]); then searched for before it sells.
                        val lostId = lostExit(p, sym, why, o, knownKite(b).toSet() + working.map { it.id }, sentAt)
                        return p.copy(stopOrderId = null, lostId = lostId) to null
                    }
                }
            }
            return bookSale(p, label, sym, why, id) { unsold(sent, read, refused = true) }
        } finally {
            if (inFlight > 0) salesInFlight.computeIfPresent(sym) { _, v -> (v - inFlight).takeIf { it > 0 } }
        }
    }

    /**
     * The exit sale [id] of [p] ([why]) waited for and booked: what filled is the exit (a part: the rest stays open with no stop, sold next
     * pass). Not finished in 15 s, the rest is cancelled; known to have filled nothing and to be finished, [nothing] is what the trade
     * becomes (its stop put back). 20 Nov 2026 (audit PC): taken out of [exitLive] unchanged, so a lost exit found later in Zerodha's
     * book is booked the same way ([lostExitLook]).
     */
    private suspend fun bookSale(p: Position, label: String, sym: String, why: String, id: String,
                                 nothing: suspend () -> Pair<Position, Position?>): Pair<Position, Position?> {
        // (22 Nov 2026, B6 review: labelled without waiting under the arms' lock on the Strategy Module's own, held across its Zerodha
        // calls - as the square-offs label theirs, [Strategies.tagOwnerSoon]; every later read of the labels sees it.)
        runCatching { Strategies.tagOwnerSoon("kite:$id", "$label · $why") }
        runCatching { noteExitSale(id) }
        var f = runCatching { Broker.awaitOrder(id, 15_000) }.getOrNull()
        if (f?.status !in DONE) {
            // Not finished in 15 s: the rest is cancelled (and tried afresh next pass); only what filled is booked.
            runCatching { Broker.cancel(id) }
            f = runCatching { Broker.orderState(id) }.getOrNull() ?: f
        }
        // Known to have filled nothing and to be finished (cancelled or rejected): the stop goes back.
        if (f == null || f.filled <= 0) return if (f != null && f.status in DONE) nothing() else p.copy(stopOrderId = null) to null
        // (22 Nov 2026, B6 review: kept counted for the square-offs a moment more, in memory - [salesBooked]: their reads may trail it. Only
        // here, this exit's own sale just booked: a lost sale found later is booked by [bookFoundRow] and not counted - it is in the order
        // book they read, its fill counted by their lag guard.)
        noteBooked(sym, f.filled)
        return bookFilled(p, label, sym, why, f.filled, f.avgPrice, f.orderId)
    }

    /** [p]'s exit sale [orderId] ([why]) filled [filled] at [avg]: booked (a part: the rest stays open with no stop, sold next pass). Pure. */
    private fun bookFilled(p: Position, label: String, sym: String, why: String, filled: Int, avg: Double, orderId: String): Pair<Position, Position?> {
        val px = avg.takeIf { it > 0 } ?: p.entry
        // 15 Nov: the stop rule's exits named with their label on the card.
        runCatching { Notifier.orderFilled(app, "SELL", filled, sym, px, "Live", if (FIXED.words(why) != why) "$label · ${FIXED.words(why)}" else label, orderId) }
        if (filled >= p.qty) return p.copy(stopOrderId = null, exit = px, exitTime = now(), why = why, charges = p.charges + kiteCharge("SELL", px, filled)) to null
        // Only part sold: that part is booked as closed, the rest stays open (no stop: the app watches it) and is sold next pass.
        val share = filled.toDouble() / p.qty
        return p.copy(qty = filled, stopOrderId = null, exit = px, exitTime = now(), why = why,
            charges = p.charges * share + kiteCharge("SELL", px, filled)) to
            p.copy(qty = p.qty - filled, stopOrderId = null, charges = p.charges * (1 - share))
    }

    data class Filled(val quantity: Int, val symbol: String, val price: Double)

    /**
     * The fill of a MARKET order just placed. The paper book can accept one and leave it
     * OPEN (no fresh price): it is cancelled straight away so it can never fill later as an
     * untracked entry or a second exit. If it filled before the cancel, that fill counts.
     */
    private suspend fun filledOrCancelled(r: Paper.Result): Filled? {
        r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()?.let { return Filled(it.quantity, it.symbol, it.price) }
        val id = r.orderId ?: return null
        if (!r.ok) return null
        Paper.cancel(id, "unfilled_market")
        val o = Paper.state.orders.firstOrNull { it.orderId == id } ?: return null
        return if (o.status == "complete") Filled(o.quantity, o.symbol, o.averagePrice?.toDouble() ?: return null) else null
    }

    // ---- 22 Oct: a bar-close paper entry is priced at its signal, and tried once more when the fill finds no price ----
    // (21 Oct, 09:30:03, Liquidity 5m: "no price to fill at; the order was cancelled" - a real signal lost. The arm read the
    // option's price, then Paper.place priced the fill again on its own - the stream's 2 s tick, Zerodha's quote waited 2 s,
    // else a read kept 10 s - and when that left no usable price the MARKET order rested unfilled, was cancelled at once
    // and never tried again. The passed arms already hand their read to Paper.place; these arms now do the same.)

    /** How long an entry's one more price read may take at most (a short pause for the next tick, then one read). */
    internal const val ENTRY_RETRY_MS = 3_000L
    /** The pause before that read: the option was just put on the stream, its first tick may be on its way. */
    private const val ENTRY_RETRY_PAUSE_MS = 500L

    /** [entryQuote]'s answer: the price (null: none after both reads) and whether it took the one more read. */
    internal class SignalPrice(val quote: com.optionslab.engine.sandbox.Quote?, val retried: Boolean)

    /**
     * [c]'s price for a bar-close paper entry, read as soon as the arm has its option: the stream's tick, else a price read in
     * the last [Paper.FILL_FRESH_MS] (another book on the same bar), else one read ([Paper.recentQuote]: memory or the network,
     * never the disk or the vault). None: one more read ([retryQuote]).
     */
    internal suspend fun entryQuote(c: Paper.Contract, mayRetry: Boolean = true): SignalPrice {
        runCatching { Paper.recentQuote(c, Paper.FILL_FRESH_MS) }.getOrNull()?.takeIf { it.ltp > 0 }?.let { return SignalPrice(it, false) }
        // 22 Oct, review (CLAUDE.md: open positions come first): the one more read waits up to 3 s under the arms' lock, which
        // this book's open positions' stop looks share - so only when this book holds nothing.
        if (!mayRetry) return SignalPrice(null, false)
        return SignalPrice(retryQuote(c), true)
    }

    /** 22 Oct, review: an entry may wait for one more price read or order only while [b] holds nothing (its stops share the lock). */
    private fun mayRetryPrice(b: Book): Boolean = b.positions.none { it.open }

    /** The words of an entry with no price at its signal ([SignalPrice.retried]: after the one more read). */
    private fun noQuote(c: Paper.Contract, p: SignalPrice): String =
        (if (p.retried) "refused: no quote for ${c.symbol} after 2 reads" else "refused: no quote for ${c.symbol}; not read again while a position of these arms is open") +
            // 28 Oct: why there was no price (an option with no last price and a one-sided or wide book), when the read said.
            (runCatching { Paper.noPriceWhy(c.symbol) }.getOrNull()?.let { " ($it)" } ?: "")

    /** One more fresh price for [c] ([Paper.quote]: the stream's tick, else one read), [ENTRY_RETRY_MS] at most; null: none. */
    internal suspend fun retryQuote(c: Paper.Contract): com.optionslab.engine.sandbox.Quote? =
        kotlinx.coroutines.withTimeoutOrNull(ENTRY_RETRY_MS) {
            kotlinx.coroutines.delay(ENTRY_RETRY_PAUSE_MS)
            runCatching { Paper.quote(c) }.getOrNull()
        }?.takeIf { it.ltp > 0 }

    /**
     * A paper entry's answer: the last order's result, its fill (null: none), how many orders were placed, when that order was
     * sent, whether the entry was priced again after its first order, and the checks' refusal of a second order (null: none).
     */
    private class EntryBuy(val result: Paper.Result, val fill: Filled?, val orders: Int, val sentMs: Long, val retried: Boolean = false,
                           val refusal: String? = null)

    /**
     * The paper MARKET BUY of [lots] of [c] for a bar-close entry, priced with [known] (the arm's read at the signal; Paper.place
     * still prefers a fresher stream tick or Zerodha's quote). Accepted but not filled - no usable price, so the book cancelled
     * it ([filledOrCancelled]) - it is tried ONCE more on a fresh read ([retryQuote]): the same option, lots, type and product,
     * only priced again, after [recheck] (the stop for today and the account guard, at the new price) lets it. Never a second
     * order unless the first is confirmed cancelled; a refused order is never retried.
     */
    private suspend fun buyPaperEntry(label: String, c: Paper.Contract, lots: Int, known: com.optionslab.engine.sandbox.Quote?, auto: String?,
                                      mayRetry: Boolean, tag: String = "$label · entry",
                                      verdict: com.optionslab.engine.risk.OrderFingerprint.Verdict? = null,
                                      /** 25 Oct: h14's capped limit (Liquidity's paper entries): a LIMIT at it instead of MARKET. */
                                      limit: Double? = null,
                                      /** 19 Nov: the arm's source, so the paper account stop tells Liquidity apart by it ([ArmAutoStopsHost]). */
                                      source: String? = null,
                                      recheck: suspend (com.optionslab.engine.sandbox.Quote) -> String?): EntryBuy {
        val sent1 = System.currentTimeMillis()
        // 22 Oct: [verdict] the guard's verdict on this exact order (the paper book refuses a different one); the same order again
        // on a retry for its price is the same fingerprint.
        val first = Paper.place(c, "BUY", lots, if (limit != null) "LIMIT" else "MARKET", "MIS", limit, null, known = known, auto = auto, verdict = verdict, source = source)
        // 22 Oct (arms audit; the 21 Oct diagnostics showed Liquidity 5m's cancelled 09:30 entry as "IraAlgo-Android"): every
        // order carries its arm's name from the moment it is placed, filled or not, so a cancelled entry is never taken for one
        // of Boss's own in Orders, the order review or the uploaded diagnostics.
        first.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", tag) } }
        filledOrCancelled(first)?.let { return EntryBuy(first, it, 1, sent1) }
        val id = first.orderId
        val cancelled = first.ok && id != null && Paper.state.orders.firstOrNull { it.orderId == id }?.status == "cancelled"
        // 22 Oct, review: never a second order while this book holds a position (its stop looks wait on the same lock).
        if (!cancelled || !mayRetry) return EntryBuy(first, null, 1, sent1)
        val q = retryQuote(c)
        // The checks made before the first order, made again at the new price (a check that fails to run refuses).
        val refusal = q?.let { nq -> runCatching { recheck(nq) }.getOrElse { e -> "refused: the checks could not be made again (${e.message})" } }
        val sent2 = System.currentTimeMillis()
        val again = if (q != null && refusal == null) Paper.place(c, "BUY", lots, if (limit != null) "LIMIT" else "MARKET", "MIS", limit, null, known = q, auto = auto, verdict = verdict, source = source) else null
        again?.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", tag) } }
        val fill = again?.let { filledOrCancelled(it) }
        // Logged either way (the diagnostics' "orb" lines): an execution retry, never a change to what the arm trades.
        runCatching {
            Diag.record("orb", "$label: ${c.symbol} had no price to fill at; " + when {
                fill != null -> "priced again and filled on the second try @ %.2f".format(Locale.ENGLISH, fill.price)
                refusal != null -> "priced again, not bought: $refusal"
                again != null && !again.ok -> "priced again, the order was refused: ${again.message}"
                again != null -> "priced again, still not filled"
                else -> "no fresh price within %d s to try again".format(Locale.ENGLISH, ENTRY_RETRY_MS / 1000)
            })
        }
        return if (again != null) EntryBuy(again, fill, 2, sent2, retried = true)
            else EntryBuy(first, null, 1, sent1, retried = true, refusal = refusal)
    }

    /** The stop for today and the account guard for a paper BUY of [lots] of [c] at [q] (its last price slipped 5 bps). */
    private suspend fun paperRecheck(c: Paper.Contract, lots: Int, q: com.optionslab.engine.sandbox.Quote): String? {
        if (Strategies.stoppedToday()) return "stopped_for_today"
        val snap = runCatching { Paper.snapshot(reuseQuotesMs = Paper.SHARED_QUOTE_MS) }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", lots, q.ltp * 1.0005), snap?.let { Guard.paperAccount(it) }, paper = true)
        return if (refusals.isNotEmpty()) "guard_refused: " + refusals.joinToString(" ") else null
    }

    /** The verdict of an entry that did not fill: a check's refusal, the order's own, else "no price" naming the option. */
    private fun unfilled(c: Paper.Contract, e: EntryBuy): String = e.refusal ?: ("order_refused: " + when {
        !e.result.ok -> e.result.message
        e.orders >= 2 -> "no price to fill at for ${c.symbol} after 2 orders; both were cancelled"
        e.retried -> "no price to fill at for ${c.symbol}; the order was cancelled and no fresh price came within %d s to try again"
            .format(Locale.ENGLISH, ENTRY_RETRY_MS / 1000)
        else -> "no price to fill at for ${c.symbol}; the order was cancelled"
    })

    private fun chargesOf(orderId: String?): Double =
        Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    // ---- Liquidity 15+5 ----------------------------------------------------------

    /**
     * One book's decision on its chart's last completed bar: a pool taken on a swing zone buys the option, paper only.
     * [reads]: the pass's index reads; when this book's index could not be read, "no_index_data" naming it (13 Oct audit fix).
     */
    private suspend fun liquidityCycle(b: Book, arm: Arm, t: LocalDateTime, appLive: Boolean? = null,
                                       reads: IndexData.Reads<List<Upstox.Bar>>? = null): String {
        val tf = LiquidityRules.minutesOf(arm)
        val und = LiquidityRules.underlyingOf(arm)
        val ones = try {
            // (19 Nov 2026, audit batch 2: from the pass's reads made before the arms' lock - never a read here.)
            liquidityMinutes(t, und, reads = reads, offline = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HistoryNotKept) {
            // (19 Nov 2026, audit batch 2: the earlier sessions still being read - no entry on today's candles alone; a failed read of
            // today's candles is said as such first.)
            return reads?.let { IndexData.gate(und, it) } ?: "liquidity_history_loading"
        } catch (e: Exception) {
            // Its own index's candles could not be read on this pass: only the books on that index stop (any other error as before).
            return reads?.let { IndexData.gate(und, it) } ?: throw e
        }
        val bars = liquidityBars(ones, tf, t)
        if (bars.size < 2 * LiquidityRules.SWING_LOOKBACK + 2) return "liquidity_history_loading"
        val last = bars.last()
        val day = t.toLocalDate()
        if (last.start.toLocalDate() != day) return "no_decision_bar"
        val decidedBars = b.decided.getOrPut("${arm.source}|$day") { HashSet() }
        val late = LiquidityRules.window(last.start, tf, t) == LiquidityRules.Window.LATE
        val waitKey = "${arm.source}|${last.start}"
        // 20 Nov 2026 (audit): a bar is marked decided (saved) as before, BEFORE anything else; a signal that waits - for fresh candles
        // (the fresh-bar check on) or for today's instrument list - is kept in memory only ([barWaits]) and decided again by the next look
        // within its own fill window. Past that window, or after a restart (the memory is gone), it stays spent: never bought late.
        if (last.start.toString() in decidedBars) {
            val waited = barWaits[waitKey] ?: return "no_decision_bar"
            if (late) {
                if (!barWaits.remove(waitKey, waited)) return "no_decision_bar"
                val verdict = if (waited == WAIT_LIST) "no_contract"
                    else "refused: stale bar - its fill window passed while it waited for fresh candles (fresh-bar check on; never bought late)"
                runCatching { noteLiquidity(t, arm.source, verdict, last.start, null, last.close) }
                runCatching { Diag.record("orb", "${arm.label}: the ${hhmm(last.start)} bar's waiting signal: $verdict") }
                return verdict
            }
        }
        decidedBars.add(last.start.toString())
        val waitedBefore = barWaits.remove(waitKey)
        // Each decision bar's verdict (and a break's level, target and close) is kept for Jarvis's words ([noteLiquidity]):
        // returned exactly as before; nothing reads it back to decide.
        fun kept(verdict: String, sig: LiquidityRules.Signal? = null): String {
            runCatching { noteLiquidity(t, arm.source, verdict, last.start, sig, last.close) }
            return verdict
        }
        // (A signal kept waiting, its bar decided again by the next look within its fill window; bounded by the day. True: new, said.)
        fun waits(kind: String): Boolean {
            if (barWaits.size > 200) barWaits.keys.removeIf { !it.contains("|${day}T") }
            barWaits[waitKey] = kind
            return waitedBefore != kind
        }
        if (!LiquidityRules.mayEnterAt(last.start.plusMinutes(tf.toLong()))) return kept("liquidity_outside_entry_hours")
        val s = LiquidityRules.signal(bars, LiquidityRules.zones(bars)) ?: return kept("no_liquidity_break")
        // 20 Nov 2026 (audit: "Liquidity has no fresh-bar check"): a signal on a bar seen past its fill window, missing its own last minute,
        // or with the index's newest minute behind ([staleBar]). SHADOW by default ([LiquidityFreshBar]): logged "would have skipped (stale
        // bar)" and entered as before; paper always so. With Boss's switch on, a LIVE entry on it is not made: within its fill window the
        // signal waits for fresh candles (decided again by the next look); past that window it is refused - never bought late. (Only a
        // signal is judged: a bar with none is decided as before, so the check never adds an entry the old way would not have made.)
        val stale = runCatching { staleBar(ones, last, tf, t) }.getOrNull()
        if (stale != null) {
            // (Goes live as the entry itself would decide it: the app's account, the book's clearance and park, and its live stop rule.)
            val goesLive = runCatching { ArmAccount.entry(arm, appLive ?: liveNow(), liqToPaper(b, arm)) && !liveStopped(arm) }.getOrDefault(false)
            if (LiquidityFreshBar.judge(arm.label, last.start, stale, goesLive)) {
                if (!late) { waits(WAIT_STALE); return STALE_WAITING }
                return kept("refused: stale bar - $stale (fresh-bar check on; never bought late)", s)
            }
        }
        // 20 Nov 2026 (audit: a signal was lost as "no_contract" when today's instrument list was late): an entry that would go now while the
        // list is not loaded yet waits for it - decided again by the next look once the list lands ([warmContracts] loads it off the lock),
        // within the bar's own fill window only; past that window it is "no_contract" as before (never bought late). Said once. Only with
        // its switch on ([LiquidityFreshBar.listWaitOn]). (Before the expiry-day check too, which reads the same list. An approval is asked for as before: it enters when Boss approves.)
        if (listedNow() == null && !late) {
            val goes = runCatching { ArmAccount.entry(arm, appLive ?: liveNow(), liqToPaper(b, arm)) }.getOrDefault(false)
            // (Behind its own switch on the fresh-bar card, off by default - it can add a live entry, so it starts in shadow: off, the
            // signal goes on as before (no contract) and "would have waited for the list and entered" is logged and counted.)
            val entersNow = b.auto[arm.source] != false && !(goes && !liveCleared(b, arm.source))
            // (One that already waited when Boss switched it off is not counted again: it goes on as before.)
            if (entersNow && !LiquidityFreshBar.listWaitOn()) { if (waitedBefore != WAIT_LIST) runCatching { LiquidityFreshBar.listWouldWait(arm.label, last.start) } }
            else if (entersNow) {
                if (waits(WAIT_LIST)) runCatching {
                    Diag.record("orb", "${arm.label}: the ${hhmm(last.start)} bar's signal waits for today's instrument list (not loaded " +
                        "yet); decided again on the next look within its fill window")
                }
                return LIST_WAITING
            }
        }
        // 25 Oct (Boss's decision on the arms audit; the 22 Oct audit's F3 rule): every study Liquidity rests on skipped its index's
        // own expiry day, so on PAPER a book takes no entry that day (open positions keep their exits) - the 26 Oct comparison is like
        // for like. Its switch is on the Account guard page, logged in the Autopilot timeline (one tap back). A LIVE entry is only
        // logged (a new rule that skips a live trade starts in shadow). From the day's saved contract list (never a download here).
        val expiryToday = runCatching {
            HeroRules.isExpiryDay(day, Market.cachedContracts()?.takeIf { it.first == day }?.second.orEmpty().filter { it.underlying == und }.map { it.expiry }.distinct())
        }.getOrDefault(false)
        if (expiryToday) {
            val goesLive = ArmAccount.entry(arm, appLive ?: liveNow(), liqToPaper(b, arm))
            if (!goesLive && BossRiskHost.expirySkipPaper()) {
                runCatching { BossRiskHost.expiryNote(arm.label, und, LiquidityRules.EXPIRY_DAY_WORDS) }
                return kept("refused: ${LiquidityRules.EXPIRY_DAY_WORDS}", s)
            }
            if (goesLive) runCatching { BossRiskHost.expiryNote(arm.label, und, LiquidityRules.EXPIRY_DAY_WORDS, live = true) }
        }
        // The room filter and the 1-ITM strike (research liq2, adopted on paper 06 Oct by Boss's choice).
        if (!LiquidityRules.hasRoom(s, last.close, und)) {
            // Information only (its own switch, off by default): the skip is told, the decision above is unchanged.
            runCatching {
                com.optionslab.app.work.LiquidityNotices.skip(app, arm.source, last.start, com.optionslab.ira.LiquidityNotice.Skip(
                    com.optionslab.ira.LiquidityNotice.Book(und, tf), s.level, s.side * ((s.target ?: last.close) - last.close),
                    LiquidityRules.MIN_ROOM_STOPS * LiquidityRules.indexStopPoints(und)), t)
            }
            return kept("liquidity_no_room", s)
        }
        val strike = LiquidityRules.entryStrike(s.side, last.close, und)
        // Candidate (a)'s shadow (pre-registered 06 Oct): recorded with the trade, never acted on (with the room filter on,
        // entered trades no longer carry it).
        val near = LiquidityShadow.nearLevel(und, s.side, last.close, s.target)
        // Candidate (c)'s shadow, the volatility risk filter (Boss's choice 06 Oct): on the index minutes that closed by the
        // signal bar's close (today's and the earlier sessions already loaded for the levels); recorded, never acted on.
        // Unknown (too little history) is recorded as not skipped: the filter skips only what it can see.
        val volSkip = runCatching { com.optionslab.engine.orb.VolFilter.judge(ones, last.start.plusMinutes(tf.toLong())).wouldSkip == true }
            .getOrDefault(false)
        // Candidate (f)'s shadow (a pre-registered forward test, Boss's approval 06 Oct): a strong close beyond the swept level
        // and premium momentum on both rights at the strike over the 5 minutes before now (one minute read of each, on a signal
        // only); recorded, never acted on. Unreadable: not recorded.
        // 19 Nov 2026 (audit batch 2): its two option reads are no longer made here - under the arms' lock, before the order: asked for
        // after the pass, off the lock, and written onto this signal's trade or approval then ([strongLater]). Record only, as before.
        strongAsks += StrongAsk(arm.source, und, s, last.close, strike, t, last.start)
        val strong: Boolean? = null
        // The findings bus (10 Oct): the sweep, for the other workers (a finding only; nothing acts on it by itself).
        SmartWorkers.found(LiquidityRules.ARM.label, com.optionslab.ira.Findings.Kind.SWEEP, und, s.side, s.level, 70,
            "sweep taken at ${"%,.0f".format(java.util.Locale.ENGLISH, s.level)} (${tf}-minute pool)")
        // Self-review (10 Oct): parked to paper by its own review, its entries go to the paper account until Boss re-arms it
        // in Live with his PIN ([SmartWorkers.ownerRearmed]); open live positions keep their exits. Memory only under the lock
        // (review G2-5: the parks were read off it; not read yet counts as parked). The approval decides it again the same way.
        // 15 Nov (Boss): a book not cleared for Zerodha by Boss's own switch on Home ([liveCleared]) enters on paper ([liqToPaper]).
        val live = ArmAccount.entry(arm, appLive ?: liveNow(), liqToPaper(b, arm))
        // Automatic unless the owner chose approvals (a book that is not cleared already went to paper above).
        if (b.auto[arm.source] == false || (live && !liveCleared(b, arm.source))) {
            val right = if (s.side > 0) "CE" else "PE"
            // 22 Oct (arms audit): until the next bar completes, but never more than [APPROVAL_MINUTES] after the signal bar's close
            // (the research buys at the next bar's open; a 30-minute book's approval could otherwise buy half an hour late).
            val expires = approvalExpiry(last.start, tf)
            // The order flow at the signal, logged while it waits (decided at the approval, if Boss approves).
            FlowGate.outcome(FlowGate.gate(FlowShadow.liquidityKey(und), und, s.side, live, last.start.toString(), arm.source, decide = false), "awaiting_approval")
            b.pending[arm.source] = Pending(arm.source, right, last.start, expires, strike, s.level, s.target, near, volSkip, strong, live = live)
            Notifier.post(app, 6960 + ALL_ARMS.indexOf(arm), Notifier.APPROVAL, "${LiquidityRules.ARM.label}: approve BUY $und $strike $right",
                // (20 Nov 2026, Boss's choice: in Live, while the cheaper strike is on, it says a cheaper strike may be bought: [approvalText].)
                approvalText(und, tf, last.start, s.side, live, LiquidityLots.words(lotsOf(b)), expires), "almanac", approve = "orb")
            return kept("awaiting_approval", s)
        }
        return kept(enterLiquidity(b, arm, s, last.start, strike, live, near, volSkip, strong), s)
    }

    /** 19 Nov 2026 (audit batch 2): a signal whose candidate (f) flag is read after the pass ([strongLater]): its book, read and bar. */
    private class StrongAsk(val arm: String, val und: String, val s: LiquidityRules.Signal, val close: Double, val strike: Int, val t: LocalDateTime,
                            val bar: LocalDateTime)
    private val strongAsks = java.util.concurrent.ConcurrentLinkedQueue<StrongAsk>()
    /** 19 Nov 2026 (audit batch 2): the flags read so far today, by "book|signal bar" - a capped paper entry that fills later takes its own. */
    private val strongKept = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    internal const val STRONG_READ_MS = 8_000L

    /**
     * 19 Nov 2026 (audit batch 2): candidate (f)'s flags of this pass's signals ([strongAsks]), read OFF the arms' lock after the pass
     * ([STRONG_READ_MS] at most each, quick reads), then written under the lock - memory only, a moment - onto the signal's trade(s) and its
     * approval where they have none yet. Record only: nothing decides on it. Unreadable: not recorded (as before).
     */
    private suspend fun strongLater() {
        while (true) {
            val a = strongAsks.poll() ?: return
            val v = try { kotlinx.coroutines.withTimeoutOrNull(STRONG_READ_MS) { strongOf(a.und, a.s, a.close, a.strike, a.t) } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } ?: continue
            if (strongKept.size > 200) strongKept.clear()
            strongKept["${a.arm}|${a.bar}"] = v
            lock.withLock {
                val b = book()
                var changed = false
                for (i in b.positions.indices) {
                    val p = b.positions[i]
                    if (p.arm == a.arm && p.signalBar == a.bar && p.strong == null) { b.positions[i] = p.copy(strong = v); changed = true }
                }
                b.pending[a.arm]?.takeIf { it.signalBar == a.bar && it.strong == null }?.let { b.pending[a.arm] = it.copy(strong = v); changed = true }
                if (changed) save(b)
            }
        }
    }

    /** Candidate (f)'s flag for signal [s] on [und] closing at [close], with the option bought at [strike] ([LiquidityShadow.strongMomentum]). */
    private suspend fun strongOf(und: String, s: LiquidityRules.Signal, close: Double, strike: Int, t: LocalDateTime): Boolean? {
        // (19 Nov 2026, audit batch 2: read after the pass, off the arms' lock ([strongLater]); the instrument list as it is - never a download.)
        val expiry = OrbRules.expiryAfter(t.toLocalDate(), (listedNow() ?: return null).filter { it.underlying == und }.map { it.expiry }.distinct()) ?: return null
        val (r, o) = if (s.side > 0) Right.CE to Right.PE else Right.PE to Right.CE
        val leg = contractNow(und, expiry, strike.toDouble(), r) ?: return null
        val opp = contractNow(und, expiry, strike.toDouble(), o) ?: return null
        return LiquidityShadow.strongMomentum(s.side, close, s.level, optionMinutes(leg.feedKey, t), optionMinutes(opp.feedKey, t), t)
    }

    /**
     * TEST ONLY: an option's 1-minute candles at a given moment (candidate (f)'s read). Null in the app, always: [optionMinutes]
     * then reads [Net.intraday]. While another test feed is set and this is not, no option has candles (no network).
     */
    @Volatile internal var testOptionBars: ((String, LocalDateTime) -> List<Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test option feed exists only in debug builds" }; field = v }

    /** An option's 1-minute candles of [t]'s day. */
    private suspend fun optionMinutes(key: String, t: LocalDateTime): List<Bar> {
        val testing = testIndexBars != null || testHistoryBars != null || testOtherIndexBars != null || testOptionBars != null
        val raw = if (testing) testOptionBars?.invoke(key, t).orEmpty() else Net.intradayQuick(key)
        return toBars(raw).filter { it.start.toLocalDate() == t.toLocalDate() }
    }

    /**
     * A MARKET BUY of Liquidity's lots ([LiquidityLots], 1-3 of the contract's own lot) of the option on the break (paper, or Zerodha when [live]), with its resting stop 15% below
     * the fill. [near], [volSkip]: candidates (a)'s and (c)'s shadow flags, kept with the position.
     */
    private suspend fun enterLiquidity(b: Book, arm: Arm, s: LiquidityRules.Signal, signalBar: LocalDateTime, strike: Int, liveAsked: Boolean,
                                       near: Boolean? = null, volSkip: Boolean? = null, strong: Boolean? = null): String {
        runCatching { OrderTiming.decided() }
        val decidedMs = System.currentTimeMillis()
        // Liquidity BANKNIFTY's live stop rule (12 Oct): once reached, a new entry goes to the paper account only until Boss
        // re-enables it with his PIN. Exits never come here. It can only take an entry off Zerodha, never put one there.
        val live = liveAsked && !liveStopped(arm)
        if (liveAsked && !live) runCatching { Diag.record("orb", "${arm.label}: its live stop rule is reached - this entry goes to the paper account only") }
        val right = if (s.side > 0) Right.CE else Right.PE
        val day = signalBar.toLocalDate()
        val und = LiquidityRules.underlyingOf(arm)
        // (19 Nov 2026, audit batch 2: the instrument list from memory or the phone - never its download under the arms' lock.)
        val listed = (listedNow() ?: return "no_contract").filter { it.underlying == und }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryAfter(day, listed) ?: return "no_contract"
        val c = contractNow(und, expiry, strike.toDouble(), right) ?: return "no_contract"
        // Fill quality (12 Oct): the signal's book and timing, from memory only (the stream's last tick), before anything else.
        val barCloseMs = epochMs(signalBar.plusMinutes(LiquidityRules.minutesOf(arm).toLong()))
        val atSignal = runCatching { Paper.bookNow(c, decidedMs) }.getOrNull()
        runCatching { LiquidityTest.record(quality(com.optionslab.ira.FillQuality.Kind.SIGNAL, decidedMs, arm, c.symbol, live, atSignal?.last, null,
            atSignal, barCloseMs, null, 0)) }
        // The live recording's market snapshot at the signal (a queue add from memory; never changes the entry).
        runCatching { LiveRecorder.signal(arm.label, c.symbol, und, right.name, atSignal?.last, paper = !live) }
        exposureRefusal(b, c, arm, live)?.let { return it }
        // The order flow at this decision (per index; logged, and only ever a skip under CONFIRM), as the ORB's.
        val flow = FlowGate.gate(FlowShadow.liquidityKey(und), und, s.side, live, signalBar.toString(), arm.source, contract = c)
        if (flow.skip) return flow.pausedWhy ?: FlowGate.SKIPPED
        // 20 Oct, enhancement round 1 (idea 3, the 19 Oct triple stop): another Liquidity book already holds this very option on
        // this account. On PAPER, with the no-twin rule on (Boss's switch, on by this update), the entry is a would-be trade kept
        // with its price, never bought. LIVE: shadow only - recorded, and the entry goes on exactly as before.
        twinOf(b, arm, c.symbol, live)?.let { holder ->
            // Review: after the cap and the order flow (their own records stay whole); never while Boss has the paper test frozen.
            val applied = !live && runCatching { LiquidityTest.noTwinOn() && !LiquidityTest.frozen(arm.source) }.getOrDefault(false)
            val words = com.optionslab.ira.LiquidityTwin.words(runCatching { armOf(holder.book).label }.getOrDefault(holder.book), c.symbol, live)
            runCatching {
                LiquidityTest.twin(com.optionslab.ira.LiquidityTwin.Rec(System.currentTimeMillis(), day, arm.source, c.symbol, atSignal?.last,
                    live, applied, holder.book, signalBar))
            }
            runCatching { Diag.record("orb", "${arm.label}: $words") }
            if (applied) return "${com.optionslab.ira.LiquidityTwin.SKIPPED}: $words".also { FlowGate.outcome(flow, it) }
        }
        // Boss's size (1, 2 or 3 lots of this contract's own lot): read at the entry, so a change applies to new entries only.
        val lots = lotsOf(b)
        if (live) {
            val sentMs = System.currentTimeMillis()
            return enterLive(b, arm, c, signalBar, liquidity = s, near = near, volSkip = volSkip, strong = strong, lots = lots)
                .also { FlowGate.outcome(flow, it) }
                .also { r ->
                    if (r == "entered_live") runCatching {
                        val p = b.positions.last { it.arm == arm.source && it.live && it.open }
                        // (20 Nov 2026: a buy on a cheaper strike, [CheaperStrike], is not measured against this contract's signal price.)
                        if (p.symbol != c.symbol) return@runCatching
                        LiquidityTest.record(quality(com.optionslab.ira.FillQuality.Kind.ENTRY, System.currentTimeMillis(), arm, c.symbol, true, p.entry,
                            atSignal?.last, Paper.bookNow(c), barCloseMs, sentMs, p.qty))
                    }
                }
        }
        return liquidityPaperBuy(b, arm, s, signalBar, strike, c, lots, flow, atSignal, barCloseMs, near, volSkip, strong, null)
    }

    // ---- 25 Oct: research h14's capped limit on Liquidity's paper entries (Boss's decision on the app audit, E1) -------------

    /** A Liquidity paper signal waiting up to 3 minutes for its option to come back under the cap (memory only: a restart lets it go). */
    internal class CapWait(val arm: String, val signal: LiquidityRules.Signal, val signalBar: LocalDateTime, val strike: Int, val contract: Paper.Contract,
                           val lots: Int, val flow: FlowGate.Ticket, val atSignal: Paper.BookNow?, val barCloseMs: Long, val near: Boolean?,
                           val volSkip: Boolean?, val strong: Boolean?, val cap: Double, val until: LocalDateTime)

    private val capWaits = java.util.concurrent.ConcurrentHashMap<String, CapWait>()

    /** TEST ONLY: the signal's price the cap is set from (null in the app: the stream's last at the signal, else the price read). */
    @Volatile internal var testCapRef: Double? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }

    /** TEST ONLY: no signal waiting (a new process). */
    internal fun forgetCapWaitsForTest() { capWaits.clear() }

    /** The verdict while a signal waits for its price to come back under the cap. */
    internal const val CAP_WAITING = "capped_limit_waiting"

    /** One look at a waiting signal ([w]): bought once its price is under the cap, let go once its 3 minutes are up. */
    private suspend fun capStep(b: Book, arm: Arm, w: CapWait, t: LocalDateTime, appLive: Boolean?): String {
        // 25 Oct (review): the wait ends too if the account changed (the app went Live: never bought on paper by an old paper
        // signal), the entry hours ended, the switch went off or the bot stopped for today.
        val goesLive = runCatching { ArmAccount.entry(arm, appLive ?: liveNow(), liqToPaper(b, arm)) }.getOrDefault(true)
        val hours = LiquidityRules.mayEnterAt(w.signalBar.plusMinutes(LiquidityRules.minutesOf(arm).toLong()))
        if (t.isAfter(w.until) || !BossRiskHost.cappedPaper() || Strategies.stoppedToday() || goesLive || !hours) {
            capWaits.remove(arm.source)
            val why = (if (t.isAfter(w.until)) "capped limit (h14): ${w.contract.symbol} did not come back to %.2f or less within 3 minutes; let go (never chased)"
                else "capped limit (h14): the wait for ${w.contract.symbol} under %.2f ended (the account, the entry hours or a stop changed); let go").format(Locale.ENGLISH, w.cap)
            runCatching { BossRiskHost.cappedNote(arm.label, w.contract.symbol, why) }
            FlowGate.outcome(w.flow, "refused: $why")
            return "refused: $why"
        }
        // (19 Nov 2026, audit batch 2: candidate (f)'s flag as read after the signal's pass, when it was - [strongLater].)
        return liquidityPaperBuy(b, arm, w.signal, w.signalBar, w.strike, w.contract, w.lots, w.flow, w.atSignal, w.barCloseMs, w.near, w.volSkip,
            w.strong ?: strongKept["${w.arm}|${w.signalBar}"], w)
    }

    /**
     * Liquidity's paper buy of [lots] of [c] for signal [s] (the part of [enterLiquidity] after its checks): priced, the guard, the
     * order and the booking. With h14's capped limit on ([BossRiskHost.cappedPaper]) the order is a LIMIT at the cap - the signal's
     * price ([atSignal], else the price read now) + 0.5% - and, while the option's price is above it, the signal waits ([capWaits],
     * [capStep]) up to 3 minutes; [wait]: this look is that wait's.
     */
    private suspend fun liquidityPaperBuy(b: Book, arm: Arm, s: LiquidityRules.Signal, signalBar: LocalDateTime, strike: Int, c: Paper.Contract,
                                          lots: Int, flow: FlowGate.Ticket, atSignal: Paper.BookNow?, barCloseMs: Long, near: Boolean?, volSkip: Boolean?,
                                          strong: Boolean?, wait: CapWait?): String {
        // 22 Oct: the option's price at the signal ([entryQuote]: one more read if the first finds none), handed to the fill.
        val price = entryQuote(c, mayRetryPrice(b))
        val q = price.quote ?: return if (wait != null) CAP_WAITING else noQuote(c, price)
        val ltp = q.ltp
        if (Strategies.stoppedToday()) { capWaits.remove(arm.source); return "stopped_for_today" }
        val cap = wait?.cap ?: if (BossRiskHost.cappedPaper()) com.optionslab.ira.BossRisk.cap(testCapRef ?: atSignal?.last?.takeIf { it > 0 } ?: ltp) else null
        if (cap != null && !com.optionslab.ira.BossRisk.underCap(q.ask.takeIf { it > 0 } ?: ltp, cap)) {
            if (wait == null) {
                capWaits[arm.source] = CapWait(arm.source, s, signalBar, strike, c, lots, flow, atSignal, barCloseMs, near, volSkip, strong, cap,
                    now().plusMinutes(com.optionslab.ira.BossRisk.CAP_WAIT_MIN))
                runCatching { BossRiskHost.cappedNote(arm.label, c.symbol, "capped limit (h14): %s at %.2f is over the cap %.2f; waiting up to 3 minutes, never chasing".format(Locale.ENGLISH, c.symbol, q.ask.takeIf { it > 0 } ?: ltp, cap)) }
            }
            return CAP_WAITING
        }
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", lots, cap ?: (ltp * 1.0005)), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) {
            capWaits.remove(arm.source)
            return ("guard_refused: " + refusals.joinToString(" ")).also { if (wait != null) FlowGate.outcome(flow, it) }
        }
        // 22 Oct: the guard's verdict stamped with this order's fingerprint ([OrderVerdicts]).
        val verdict = OrderVerdicts.stamp(OrderVerdicts.paperFields(c, "BUY", lots, if (cap != null) "LIMIT" else "MARKET", "MIS", cap, null, Market.today(), arm.source), refusals, "paper guard")
        val entry = buyPaperEntry(arm.label, c, lots, q, ownerOf(arm), mayRetryPrice(b) && cap == null, tag = "${LiquidityRules.ARM.label} · entry", verdict = verdict, limit = cap, source = arm.source) { nq -> exposureRefusal(b, c, arm) ?: paperRecheck(c, lots, nq) }   // 22 Oct, merge review: the side rule again too
        val buy = entry.result
        // The capped LIMIT not filled at once (the price moved over the cap meanwhile; the book cancelled it): the signal waits on
        // within its 3 minutes, never chasing. A refusal (a rule, the guard) ends the wait.
        if (cap != null && entry.fill == null && buy.ok) {
            if (wait == null) capWaits[arm.source] = CapWait(arm.source, s, signalBar, strike, c, lots, flow, atSignal, barCloseMs, near, volSkip, strong, cap,
                now().plusMinutes(com.optionslab.ira.BossRisk.CAP_WAIT_MIN))
            return CAP_WAITING
        }
        capWaits.remove(arm.source)
        val fill = entry.fill ?: return unfilled(c, entry)
        if (cap != null) runCatching { BossRiskHost.cappedNote(arm.label, c.symbol, "capped limit (h14): bought at %.2f under the cap %.2f".format(Locale.ENGLISH, fill.price, cap)) }
        // The fill against the signal's price (the stream's last at the signal, else the price read just before the order); timed
        // from the order that filled (22 Oct: a second order's own send time, so the retry's wait is not counted as fill speed).
        runCatching { LiquidityTest.record(quality(com.optionslab.ira.FillQuality.Kind.ENTRY, System.currentTimeMillis(), arm, c.symbol, false, fill.price,
            atSignal?.last ?: ltp, Paper.bookNow(c), barCloseMs, entry.sentMs, fill.quantity)) }
        buy.orderId?.let { Strategies.tagOwner("paper:$it", "${LiquidityRules.ARM.label} · entry") }
        FlowGate.taken(flow, buy.orderId)
        Notifier.orderFilled(app, "BUY", fill.quantity, fill.symbol, fill.price, "Paper", "${LiquidityRules.ARM.label} · entry", buy.orderId)
        // The owner's stop: a resting SL-M sell 15% below the fill (the book owns it, as the ORB's -40), for all that filled. Its
        // exits are the paper account's parameters today (S9: a variant moves its exits only, from the session after).
        val lq = liqParamsOf(arm.source, now().toLocalDate(), live = false)
        // 15 Nov (Boss's exception, failed gates): the breakeven rule when the switch is on - its stop rests at the -15% and moves to breakeven
        // once the option is 10% up (memory only: the switch was read in the background); with no lot size known it is not used.
        val fixed = StepTrailExits.forEntry(arm.source) && c.lotSize > 0
        val trigger = firstStop(LiquidityRules.stopTrigger(fill.price, lq.stopPct), fill.price, c.lotSize, fixed)
        // Boss's own give-back trail (R19b / R19c failed: their opt-in switch, off by default): paper entries only, from +Rs 1,000 a lot.
        // Only with the contract's lot known (13 Oct review): without it the trail has no points and the trade would run on plain
        // rules while labelled "with ₹500 trail" and kept out of the plain-rules record.
        val trail = LiquidityTrail.forEntry(arm, live = false, switchOn = runCatching { LiquidityTest.trailOn() }.getOrDefault(false), lot = c.lotSize)
        var stopId: String? = null
        if (trigger != null) {
            val stop = Paper.place(c, "SELL", (fill.quantity / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1), "SL-M", "MIS", null, trigger)
            if (stop.ok) { stopId = stop.orderId; stopId?.let { Strategies.tagOwner("paper:$it", "${LiquidityRules.ARM.label} · stop") } }
        }
        b.positions += stamped(Position(arm.source, c.symbol, c.right.name, fill.quantity, fill.price, now(), signalBar, buy.orderId, stopId, trigger,
            charges = chargesOf(buy.orderId), level = s.level, target = s.target, near = near, volSkip = volSkip, strong = strong,
            lot = c.lotSize.takeIf { it > 0 }, ladder = lq.lockPct > 0,
            trail = trail, trailFrom = LiquidityTrail.forEntryFrom(trail),
            // 22 Oct: Boss's own breakeven at +10% (R100 A4 did not pass: their opt-in switch, off by default): paper entries only.
            be = LiquidityBreakeven.forEntry(arm, live = false, switchOn = runCatching { LiquidityTest.breakevenOn() }.getOrDefault(false)),
            priceRetry = price.retried || entry.retried, capped = cap, fixed = fixed, lock = fixed && StepTrailExits.lockOn(), entryQty = fill.quantity))
        marks[c.symbol] = fill.price
        // 22 Oct: the time machine's reality check (shadow only; background): the index stop and the level-to-target distance in index points.
        runCatching { TimeMachineHost.afterEntry(TimeMachineHost.Entry(arm.source, LiquidityRules.ARM.label, "orb:${arm.source}:${buy.orderId ?: now()}", c.underlying,
            if (c.right == Right.CE) 1 else -1, signalBar.plusMinutes(5), LiquidityRules.indexStopPoints(c.underlying), s.target?.let { kotlin.math.abs(it - s.level) },
            exitAt = OrbRules.SQUARE_OFF)) }
        return "entered"
    }

    /** One fill-quality record ([com.optionslab.ira.FillQuality]) from the book read then ([q]: null when the stream had none). */
    private fun quality(kind: com.optionslab.ira.FillQuality.Kind, atMs: Long, arm: Arm, symbol: String, live: Boolean, price: Double?,
                        signalPrice: Double?, q: Paper.BookNow?, barCloseMs: Long?, sentMs: Long?, qty: Int): com.optionslab.ira.FillQuality.Rec =
        com.optionslab.ira.FillQuality.Rec(kind, atMs, arm.source, symbol, live, price, signalPrice, q?.bid, q?.ask, q?.bidQty, q?.askQty,
            q?.bid5, q?.ask5, q?.ageMs, barCloseMs, sentMs, qty)

    /**
     * 20 Oct, enhancement round 1 ([com.optionslab.ira.LiquidityTwin]): the open position of ANOTHER Liquidity book in [b] on [symbol]
     * on the same account ([live]), or null. An unconfirmed live buy counts (it may have filled). Memory only (the book in hand).
     */
    private fun twinOf(b: Book, arm: Arm, symbol: String, live: Boolean): com.optionslab.ira.LiquidityTwin.Holding? {
        val open = b.positions.filter { p -> p.open && runCatching { armOf(p.arm).liquidity }.getOrDefault(false) }
            .map { com.optionslab.ira.LiquidityTwin.Holding(it.arm, it.symbol, it.live) }
        return com.optionslab.ira.LiquidityTwin.twinOf(arm.source, symbol, live, open + testTwinHolders?.invoke().orEmpty())
    }

    /**
     * TEST ONLY: another Liquidity book's open positions beside the book's own (the synthetic test day makes only the 5-minute
     * book trade). Null in the app, always; its setter throws unless BuildConfig.DEBUG (as [testIndexBars]).
     */
    @Volatile internal var testTwinHolders: (() -> List<com.optionslab.ira.LiquidityTwin.Holding>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test twin holders exist only in debug builds" }; field = v }

    /** Whether [arm] (a Liquidity BANKNIFTY book) has reached its live stop rule ([liquidityStopHint]). Memory only. */
    private fun liveStopped(arm: Arm): Boolean = arm.source in com.optionslab.engine.orb.ArmPriority.BN_BOOKS && liquidityStopHint.stopped

    /** Entries and exits told no later than this after they happened (an upgrade or a restart never tells an old trade). */
    private const val NOTICE_WINDOW_MIN = 15L

    /**
     * Liquidity 15+5's trade notices ([com.optionslab.app.work.LiquidityNotices]), after the pass: each position of the
     * arm that entered (confirmed) or exited in the last [NOTICE_WINDOW_MIN] minutes is told once, with the book's last bars
     * from the minutes this pass already read (nothing is fetched). Words and a picture only: it reads the book, never
     * changes it, and places nothing.
     */
    private fun liquidityNotices(b: Book, t: LocalDateTime) {
        val since = t.minusMinutes(NOTICE_WINDOW_MIN)
        val mine = b.positions.filter { p -> !p.unconfirmed && runCatching { armOf(p.arm).liquidity }.getOrDefault(false) &&
            (!p.entryTime.isBefore(since) || p.exitTime?.isBefore(since) == false) }
        if (mine.isEmpty()) return
        val day = t.toLocalDate()
        val closedToday = b.positions.filter { p -> !p.open && p.exitTime?.toLocalDate() == day &&
            runCatching { armOf(p.arm).liquidity }.getOrDefault(false) }
        for (p in mine) runCatching {
            val arm = armOf(p.arm)
            val und = LiquidityRules.underlyingOf(arm)
            val tf = LiquidityRules.minutesOf(arm)
            val book = com.optionslab.ira.LiquidityNotice.Book(und, tf)
            // (20 Nov 2026, audit: folded once a minute and reused by the minute's looks - [liqFold]; the same bars.)
            val src = (liquidityPass[und]?.second ?: liquidityMinutesKept(und, t)).orEmpty()
            val bars = liqFold(und, src, tf, t)?.noticeBars ?: LiquidityRules.completed(LiquidityRules.fold(src, tf), tf, t)
            val strike = Paper.contractOf(p.symbol)?.strike
            val lots = p.lot?.let { p.lots }
            val key = "${p.arm}|${p.symbol}|${p.entryTime}"
            val level = p.level
            if (!p.entryTime.isBefore(since) && level != null) {
                val e = com.optionslab.ira.LiquidityNotice.Entry(book, p.live, p.right, strike, p.symbol, p.qty + p.sold, lots, p.entry,
                    p.entryTime, level, p.target, bars.firstOrNull { it.start == p.signalBar }?.close, p.stopTrigger,
                    LiquidityRules.indexStopPoints(und), liqParams(p).timeStopMinutes, liqParams(p).timeStopGain,
                    label = if (p.live) null else LiquidityBreakeven.label(p.trail, p.be))
                com.optionslab.app.work.LiquidityNotices.entry(app, key, e) { w, h ->
                    com.optionslab.ira.LiquidityNotice.chart(bars, tf, level, p.target, p.entryTime, null, w, h)
                }
            }
            val exit = p.exit; val exitTime = p.exitTime
            if (exit != null && exitTime != null && !exitTime.isBefore(since)) {
                val tally = com.optionslab.ira.LiquidityNotice.tally(closedToday.filter { !it.exitTime!!.isAfter(exitTime) }
                    .map { (it.grossPnl ?: 0.0) - it.charges })
                val x = com.optionslab.ira.LiquidityNotice.Exit(book, p.live, p.right, strike, p.symbol, p.qty + p.sold, lots, p.entry, exit,
                    p.entryTime, exitTime, p.why ?: "exit", level, p.target, LiquidityRules.indexStopPoints(und),
                    p.grossPnl ?: 0.0, p.charges, tally, label = if (p.live) null else LiquidityBreakeven.label(p.trail, p.be))
                com.optionslab.app.work.LiquidityNotices.exit(app, key, x) { w, h ->
                    com.optionslab.ira.LiquidityNotice.chart(bars, tf, level, p.target, p.entryTime, exitTime, w, h)
                }
            }
        }
    }

    // ---- 20 Nov 2026 (audit: heavy maths under the Liquidity lock on every tick) ------------------------------------------------------

    /**
     * An index's 1-minute candles [src] as one [tf]-minute book's exits and notices read them at [t] (its [minute]): the minutes closed by
     * then ([ones]), the chart's completed bars ([bars], [liquidityBars]), their liquidity levels ([zones]) and the notices' bars
     * ([noticeBars]). Each worked out once, when first asked. Every comparison with [t] is against a whole minute (the candles start on
     * whole minutes, [liqFold] checks it), so any moment of that minute gives the same lists. Pure, memory only.
     */
    private class LiqFold(val src: List<Bar>, val tf: Int, val t0: LocalDateTime, val minute: LocalDateTime) {
        val ones: List<Bar> by lazy { src.filter { !it.start.plusMinutes(1).isAfter(t0) } }
        val bars: List<Bar> by lazy { liquidityBars(ones, tf, t0) }
        val zones: List<LiquidityRules.Zone> by lazy { LiquidityRules.zones(bars) }
        val noticeBars: List<Bar> by lazy { LiquidityRules.completed(LiquidityRules.fold(src, tf), tf, t0) }
    }
    private val liqFolds = java.util.concurrent.ConcurrentHashMap<String, LiqFold>()

    /**
     * 20 Nov 2026 (audit): [src]'s fold for [und]'s [tf]-minute book at [t] - the one already made this minute from this very list, else a new
     * one (kept for the minute's later looks). Null (the caller works it out as before) when a candle does not start on a whole minute.
     */
    private fun liqFold(und: String, src: List<Bar>, tf: Int, t: LocalDateTime): LiqFold? {
        val minute = t.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        val key = "$und|$tf|${System.identityHashCode(src)}"
        liqFolds[key]?.takeIf { it.src === src && it.minute == minute }?.let { return it }
        if (src.any { it.start.second != 0 || it.start.nano != 0 }) return null
        if (liqFolds.size > 48) liqFolds.clear()
        return LiqFold(src, tf, t, minute).also { liqFolds[key] = it }
    }

    /** 20 Nov 2026 (audit): the open Liquidity positions' (index, chart minutes) as the last look under the lock saw them ([warmLiqFolds]). */
    @Volatile private var liqFoldPlan: Set<Pair<String, Int>> = emptySet()

    /**
     * 20 Nov 2026 (audit): BEFORE the arms' lock, each open Liquidity position's index minutes kept in memory for this minute folded and their
     * levels found ([liqFold]), so the look under the lock only reuses them. Memory only (no read, no disk); a failure leaves the look to do it.
     */
    private fun warmLiqFolds(t: LocalDateTime) {
        for ((und, tf) in liqFoldPlan) runCatching {
            val src = minutesInMemory(und, t) ?: return@runCatching
            liqFold(und, src, tf, t)?.let { it.zones; it.noticeBars }
        }
    }

    /**
     * TEST ONLY: [src]'s fold made at [t] against the same lists worked out directly at [t2] (true: all equal), and whether an ask at [t2]
     * reused that fold. (false, false): no fold (a candle not on a whole minute).
     */
    internal fun liqFoldForTest(src: List<Bar>, tf: Int, t: LocalDateTime, t2: LocalDateTime): Pair<Boolean, Boolean> {
        val f = liqFold("TEST", src, tf, t) ?: return false to false
        val ones = src.filter { !it.start.plusMinutes(1).isAfter(t2) }
        val bars = liquidityBars(ones, tf, t2)
        fun z(l: List<LiquidityRules.Zone>) = l.map { listOf(it.kind, it.side, it.top, it.bottom, it.origin, it.known) }
        val same = f.ones == ones && f.bars == bars && z(f.zones) == z(LiquidityRules.zones(bars)) &&
            f.noticeBars == LiquidityRules.completed(LiquidityRules.fold(src, tf), tf, t2)
        return same to (liqFold("TEST", src, tf, t2) === f)
    }

    /** 20 Nov 2026 (audit): a signal whose bar waits for today's instrument list (decided again in its fill window; [liquidityCycle]). */
    internal const val LIST_WAITING = "instrument_list_loading"
    /** 20 Nov 2026 (audit): a live entry on a stale bar waiting for fresh candles, the fresh-bar check on (decided again; [LiquidityFreshBar]). */
    internal const val STALE_WAITING = "liquidity_stale_bar_waiting"
    private const val WAIT_LIST = "list"
    private const val WAIT_STALE = "stale"
    /**
     * 20 Nov 2026 (audit): the decided bars whose signal waits ("book|bar" -> [WAIT_LIST] or [WAIT_STALE]), decided again by the next look
     * within their fill window. Memory only, on purpose: after a restart such a bar stays decided (saved), so it is never bought late.
     */
    private val barWaits = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 20 Nov 2026 (audit: "Liquidity has no fresh-bar check"): why the [tf]-minute bar [last], built from the index's 1-minute candles
     * [ones], is too stale to enter on at [t] - seen past its fill window ([LiquidityRules.window]: its close plus
     * [LiquidityRules.FILL_SLACK_MINUTES]), its own last minute missing from [ones] (built from part of its minutes), or the index's
     * newest minute more than [LiquidityRules.FILL_SLACK_MINUTES] behind [t] (the feed fell behind) - or null: fresh. Pure.
     */
    internal fun staleBar(ones: List<Bar>, last: Bar, tf: Int, t: LocalDateTime): String? {
        fun hm(x: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, x.hour, x.minute)
        val close = last.start.plusMinutes(tf.toLong())
        if (LiquidityRules.window(last.start, tf, t) == LiquidityRules.Window.LATE)
            return "the ${hm(last.start)} bar is seen at ${hm(t)}, past its fill window (its close ${hm(close)} + ${LiquidityRules.FILL_SLACK_MINUTES} min)"
        val lastMinute = last.start.plusMinutes(tf - 1L)
        if (ones.none { it.start == lastMinute }) return "the ${hm(last.start)} bar's last minute (${hm(lastMinute)}) is missing from the index's candles"
        val newest = ones.maxOfOrNull { it.start } ?: return "no index candles"
        if (newest.plusMinutes(1 + LiquidityRules.FILL_SLACK_MINUTES).isBefore(t)) return "the index's newest minute is ${hm(newest)} at ${hm(t)}"
        return null
    }

    /**
     * The chart's completed bars. As [fiveMinute]: a bar still missing its last minute is kept out for two minutes after
     * it ends, so a late print cannot turn into a different decision (an entry, or a failed break) once decided.
     */
    private fun liquidityBars(ones: List<Bar>, tf: Int, t: LocalDateTime): List<Bar> {
        val have = ones.mapTo(HashSet()) { it.start }
        return LiquidityRules.completed(LiquidityRules.fold(ones, tf), tf, t).filter { b ->
            !t.isBefore(b.start.plusMinutes(tf + 2L)) || b.start.plusMinutes(tf - 1L) in have
        }
    }

    /**
     * Next liquidity touched, the break failed, or new liquidity formed: the open liquidity positions are sold.
     * 19 Nov 2026 (audit batch 2): under the arms' lock, so it reads no candles itself - each index's minutes come from [pre] (kept in
     * memory for this minute) or [reads] (read before the lock); an index in neither is a failed read for this look ([indexReadFailed]).
     */
    private suspend fun liquidityExits(b: Book, t: LocalDateTime, reads: IndexData.Reads<List<Upstox.Bar>>?, pre: Map<String, List<Bar>> = emptyMap(),
                                       /** 19 Nov 2026 (audit batch 2): a fast look - an index not in memory is not said as a failed read. */
                                       quiet: Boolean = false) {
        val open = b.positions.withIndex().filter { it.value.open && !it.value.unconfirmed && armOf(it.value.arm).liquidity }
        if (open.isEmpty()) return
        val minutes = HashMap<String, List<Bar>>()
        // 19 Nov 2026 (audit batch 2, last round): the indices whose earlier sessions are not in memory yet (a restart mid-day, the history
        // job still reading) but whose today's candles were read: their positions' exits are judged on today's minutes with the levels the
        // position stored - the index stop, the target, the failed break and the time stop need nothing else; only "new liquidity" (the
        // zones, which need the earlier sessions) waits for them. Entries keep waiting for the earlier sessions.
        val todayOnly = HashSet<String>()
        for ((i, p) in open) try {
            val und = LiquidityRules.underlyingOf(armOf(p.arm))
            // 22 Oct (R100 audit A): one index's failed candle read used to escape this loop, so every Liquidity position after it
            // (other indices too) got no index exit and no time stop on that pass. Now a failed read stops only the index exits of
            // that index's own positions ([indexReadFailed]: no bars, so no index stop, target, failed break or new level can be
            // judged); their time stop, which reads only the option's price, still decides, and the other indices go on as before.
            val tf = LiquidityRules.minutesOf(armOf(p.arm))
            // 20 Nov 2026 (audit: heavy maths under the Liquidity lock on every tick): the index minutes kept in memory for this minute
            // ([pre]) are filtered, folded into the book's chart and their levels found once a minute - before the lock where it can
            // ([warmLiqFolds]) - and reused by every look of that minute ([liqFold]); the same lists from the same minutes, so the same exits.
            val fold = pre[und]?.let { liqFold(und, it, tf, t) }
            val ones = fold?.ones ?: minutes.getOrPut(und) {
                (pre[und] ?: try { liquidityMinutes(t, und, reads = reads, offline = true) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: HistoryNotKept) {
                        val today = reads?.takeIf { und in it.fetched && !it.failed(und) }?.read(und) { throw e }
                        if (today != null) { todayOnly += und; toBars(today).filter { it.start.toLocalDate() == t.toLocalDate() } }
                        else { if (!quiet) indexReadFailed(und, t, e); emptyList() }
                    }
                    catch (e: Exception) { if (!quiet) indexReadFailed(und, t, e); emptyList() })
                    .filter { !it.start.plusMinutes(1).isAfter(t) }
            }
            val bars = fold?.bars ?: liquidityBars(ones, tf, t)
            val side = if (p.right == "CE") 1 else -1
            val since = ones.filter { !it.start.isBefore(p.entryTime.withSecond(0).withNano(0)) }
            val level = p.level ?: continue
            // The turn exits first: the index back through the broken level, then the 20-minute time stop (read once, on the
            // option's latest price), then the arm's own exits.
            // Decided only on a price read on this pass (never the fill's seed or a stale one). Held, it is marked decided;
            // sold, it stays undecided until the sale went through, so a failed sale is tried again next pass.
            var cur = p
            val fresh = marks[p.symbol]?.takeIf { markedAt[p.symbol]?.isBefore(t.minusMinutes(1)) == false }
            // The decision itself is pure ([LiquidityRules.exitNow]: the strategy lab replays the same), with the position's own
            // day's and account's exit parameters (S9; its constants unless a variant is in force).
            val look = if (und in todayOnly) LiquidityRules.exitNow(side, level, p.target, p.signalBar, p.entry, p.entryTime, t, bars, since, und, fresh,
                p.timed, liqParams(p), levels = { emptyList() })
                else LiquidityRules.exitNow(side, level, p.target, p.signalBar, p.entry, p.entryTime, t, bars, since, und, fresh, p.timed, liqParams(p),
                    levels = fold?.let { f -> { f.zones } } ?: { LiquidityRules.zones(bars) })
            if (look.timed && !p.timed) { cur = p.copy(timed = true); b.positions[i] = cur }
            val why = look.why ?: continue
            if (p.live) {
                // At Zerodha: the resting stop comes out first, then a MARKET sell (exitLive), in the account it entered.
                if (!Broker.loggedIn) continue
                val sentMs = System.currentTimeMillis()
                val (done, rest) = exitLive(b, cur, cur.kite ?: continue, why)
                b.positions[i] = done
                rest?.let { b.positions += it }
                // Fill quality (12 Oct): the exit's fill against the price it decided on (memory only).
                if (done.exit != null) runCatching { Paper.contractOf(p.symbol)?.let { c -> LiquidityTest.record(quality(com.optionslab.ira.FillQuality.Kind.EXIT,
                    System.currentTimeMillis(), armOf(p.arm), p.symbol, true, done.exit, fresh, Paper.bookNow(c), null, sentMs, done.qty)) } }
                continue
            }
            val c = Paper.contractOf(p.symbol) ?: continue
            val sentMs = System.currentTimeMillis()
            val done = exit(cur, c, why)
            b.positions[i] = done
            if (done.exit != null) runCatching { LiquidityTest.record(quality(com.optionslab.ira.FillQuality.Kind.EXIT, System.currentTimeMillis(),
                armOf(p.arm), p.symbol, false, done.exit, fresh, Paper.bookNow(c), null, sentMs, done.qty)) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // One position's error never skips the others' exits (22 Oct, R100 audit A); it is looked at again on the next pass.
            // Said at most once a minute per position (the fast looks run several times a second).
            val minute = t.withSecond(0).withNano(0)
            if (exitCheckSaid.put(p.symbol + "|" + p.entryTime, minute) != minute)
                runCatching { Diag.record("orb", "${runCatching { armOf(p.arm).label }.getOrDefault(p.arm)} ${p.symbol}: its exit check failed this pass " +
                    "(${e.javaClass.simpleName}); tried again next pass") }
        }
    }

    /** When each position's failed exit check was last said (memory only): once a minute. */
    private val exitCheckSaid = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()

    /** When each index's failed candle read was last said (memory only), so a feed that stays down is said once a minute. */
    private val indexReadSaid = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()

    /**
     * A Liquidity index's candles could not be read for the open positions' exits (22 Oct, R100 audit A): said in the diagnostics
     * (at most once a minute per index; memory and the diary's own thread only). Its positions keep their resting -15% stop, their
     * time stop and 15:10; their index exits wait for the next read.
     */
    private fun indexReadFailed(und: String, t: LocalDateTime, e: Exception) {
        val minute = t.withSecond(0).withNano(0)
        if (indexReadSaid.put(und, minute) == minute) return
        runCatching { Diag.record("orb", "Liquidity $und: its candles could not be read (${e.javaClass.simpleName}); its open positions' index exits " +
            "wait for the next read (the -15% stop, the time stop and 15:10 still act; the other indices are not affected)") }
    }

    /**
     * TEST ONLY: earlier sessions' BANKNIFTY 1-minute bars for the liquidity levels (the feed's history). Null in the app,
     * always: [liquidityMinutes] then reads [Net.history]. Its setter throws unless BuildConfig.DEBUG; no app code sets it.
     */
    @Volatile internal var testHistoryBars: ((java.time.LocalDate) -> List<Upstox.Bar>)? = null
        set(v) {
            check(com.optionslab.app.BuildConfig.DEBUG) { "the test history feed exists only in debug builds" }; field = v
            // (19 Nov 2026, audit batch 2: the earlier sessions are kept in memory for the day - a test's never carry into the next.)
            forgetHistoryForTest()
        }

    /**
     * TEST ONLY: today's 1-minute bars of an index other than BANKNIFTY (FINNIFTY, MIDCPNIFTY) at a given moment. Null in the app,
     * always. While [testIndexBars] is set and this is not, the other indices have no bars (a test never reaches the network).
     */
    @Volatile internal var testOtherIndexBars: ((String, LocalDateTime) -> List<Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test index feed exists only in debug builds" }; field = v }

    /** 19 Nov 2026 (audit batch 2, F1): each index's running read of its earlier sessions ([historyJob]). */
    private val historyJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Unit>>()

    /**
     * 19 Nov 2026 (audit batch 2, F1): [underlying]'s earlier sessions (the ten calendar days before [day]) read by a job of its own off the
     * arms' lock - one at a time per index, with the read's full tries and timeouts (never cut by a look's deadline: whoever waits for it
     * may stop waiting, the read goes on). Kept in memory for the day; none came back (empty, or every try failed): marked missing, tried
     * again after [HISTORY_MISS_MS], as before. In a test the history feed is its read.
     */
    private fun historyJob(underlying: String, day: LocalDate, bank: Boolean, key: String): kotlinx.coroutines.Deferred<Unit> =
        historyJobs.compute(underlying) { _, running ->
            running?.takeIf { it.isActive } ?: historyGen.let { gen -> offLock.async {
                val testing = testIndexBars != null || testHistoryBars != null || testOtherIndexBars != null
                val raw = if (testing) runCatching { (if (bank) testHistoryBars?.invoke(day) else null).orEmpty() }.getOrDefault(emptyList())
                    else try { Net.history(key, day.minusDays(10), day.minusDays(1)) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
                val bars = toBars(raw).filter { it.start.toLocalDate().isBefore(day) }
                // (Forgotten meanwhile - a test's reset, a new process's memory: what it read is not kept.)
                if (gen != historyGen) return@async
                if (bars.isNotEmpty()) { liquidityHistory[underlying] = day to bars; liquidityHistoryMiss.remove(underlying) }
                else liquidityHistoryMiss[underlying] = System.currentTimeMillis()
            } }
        }!!

    /** 19 Nov 2026 (audit batch 2): bumped when the earlier sessions are forgotten: a read started before is not kept. */
    @Volatile private var historyGen = 0

    /** TEST ONLY (19 Nov 2026, audit batch 2): every index's earlier sessions forgotten, as on a new day. */
    internal fun forgetHistoryForTest() {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        historyGen++; historyJobs.values.forEach { it.cancel() }; historyJobs.clear(); liquidityHistory.clear(); liquidityHistoryMiss.clear()
    }

    /** TEST ONLY (19 Nov 2026, audit batch 2): how many earlier-session minutes of [underlying] are kept in memory now. */
    internal fun historyKeptForTest(underlying: String): Int = liquidityHistory[underlying]?.second?.size ?: 0

    /** Earlier sessions' minutes per index, fetched once a day (the levels need a few days of 15-minute bars). */
    private val liquidityHistory = java.util.concurrent.ConcurrentHashMap<String, Pair<java.time.LocalDate, List<Bar>>>()

    /** 22 Oct (arms audit): a lagging read of an index's minutes (read at, its minute, the bars), kept [LAG_KEEP_MS]. */
    private val liquidityLag = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, LocalDateTime, List<Bar>>>()
    /** 22 Oct (arms audit): when an index's history read last came back empty (epoch ms): tried again after [HISTORY_MISS_MS]. */
    private val liquidityHistoryMiss = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal const val LAG_KEEP_MS = 5_000L
    internal const val HISTORY_MISS_MS = 60_000L

    /** A lagging read made at [readMs] for [readMinute] still stands at [nowMs] in [minute]: same minute, under [LAG_KEEP_MS]. Pure. */
    internal fun lagFresh(readMs: Long, readMinute: LocalDateTime, nowMs: Long, minute: LocalDateTime): Boolean =
        readMinute == minute && nowMs - readMs in 0 until LAG_KEEP_MS
    /** The minutes read on this pass per index (its books and the exits share one fetch). */
    private val liquidityPass = java.util.concurrent.ConcurrentHashMap<String, Pair<LocalDateTime, List<Bar>>>()

    /**
     * An index's 1-minute bars Liquidity 15+5 already read on [t]'s minute (today's only), or null when it did not: the
     * shadows ([ShadowArms]) share that read instead of downloading the same candles again. Reads nothing.
     */
    internal fun minutesReadThisMinute(underlying: String, t: LocalDateTime): List<Bar>? =
        liquidityPass[underlying]?.takeIf { it.first == t.withSecond(0).withNano(0) }?.second?.filter { it.start.toLocalDate() == t.toLocalDate() }

    private fun toBars(ones: List<Upstox.Bar>): List<Bar> = ones.map {
        Bar(java.time.Instant.ofEpochSecond(it.epochSecond).atZone(com.optionslab.engine.IST).toLocalDateTime(), it.open, it.high, it.low, it.close)
    }.filter { val m = it.start.hour * 60 + it.start.minute; m in (9 * 60 + 15)..(15 * 60 + 29) }

    /**
     * Jarvis's liquidity map (read only, [com.optionslab.ira.LiquidityMap]): an index's 1-minute bars as Liquidity 15+5 reads
     * them - the arm's own read of this minute when it made one, else the same candles read now. Never kept as the arm's
     * read of the minute, so the arm's own decision reads its candles exactly as before. Nothing is decided or traded.
     */
    suspend fun liquidityMinutesFor(underlying: String, t: LocalDateTime): List<Bar> = liquidityMinutes(t, underlying, keep = false)

    /**
     * The 1-minute bars Liquidity 15+5 read for [underlying] within the two minutes before [t] (earlier sessions and today's),
     * or null when it did not (not armed, or not run): Jarvis's heads-up shares the arm's read. Reads nothing.
     */
    fun liquidityMinutesKept(underlying: String, t: LocalDateTime): List<Bar>? =
        liquidityPass[underlying]?.takeIf { !it.first.isBefore(t.withSecond(0).withNano(0).minusMinutes(2)) }?.second

    /**
     * An index's 1-minute bars (BANKNIFTY, FINNIFTY or MIDCPNIFTY): the last ten calendar days' sessions plus today's. [keep] false: a
     * read for Jarvis, not kept as this minute's pass (the arm's own read stays its own).
     */
    private suspend fun liquidityMinutes(t: LocalDateTime, underlying: String = OrbRules.UNDERLYING, keep: Boolean = true,
                                         reads: IndexData.Reads<List<Upstox.Bar>>? = null,
                                         /** 19 Nov 2026 (audit batch 2): a read off the lock on a deadline ([prefetchIndices]): fewer tries. */
                                         quick: Boolean = false,
                                         /**
                                          * 19 Nov 2026 (audit batch 2): under the arms' lock - nothing is read from the network: the minute's read kept,
                                          * else today's candles from [reads] (read before the lock) and the earlier sessions already kept.
                                          */
                                         offline: Boolean = false): List<Bar> {
        val minute = t.withSecond(0).withNano(0)
        // The test feeds are read fresh every time (a cached pass must never carry one test's bars into another).
        val testing = testIndexBars != null || testHistoryBars != null || testOtherIndexBars != null
        if (!testing) liquidityPass[underlying]?.let { if (it.first == minute) return it.second }
        // 22 Oct (arms audit): while the feed lags (the minute just closed is not in it yet, so the read above is not kept), every
        // look of the stream lane read the index candles again under the arms' lock, the open positions' 15-second stop looks
        // waiting behind it. That read is now kept a few seconds ([LAG_KEEP_MS]) within its minute.
        val nowMs = System.currentTimeMillis()
        if (!testing && keep) liquidityLag[underlying]?.let { if (lagFresh(it.first, it.second, nowMs, minute)) return it.third }
        val day = t.toLocalDate()
        val bank = underlying == OrbRules.UNDERLYING
        val key = LiquidityRules.INDEX_KEYS.getValue(underlying)
        // 22 Oct (arms audit): a history read that came back empty is not tried again (6 tries) on every look: once a minute.
        // 19 Nov 2026 (audit batch 2, second review F1): the earlier sessions are read once a day per index by a job of their own, OFF the
        // lock, with its full tries and timeout ([historyJob]) - a look's deadline only stops waiting for it, never the read, so on a slow
        // link it still lands and the next look finds it in memory. Under the lock they are never read: not in memory yet, an entry waits
        // for them ("history loading"), and an open position's exits use only what needs no earlier session (today's minutes and the levels
        // it stored: [liquidityExits]). As before, for [HISTORY_MISS_MS] after a read that found none, today's candles are used alone.
        // (In a test too: the test feed is that job's read.)
        fun kept(): List<Bar>? = liquidityHistory[underlying]?.takeIf { it.first == day }?.second
            ?: if (liquidityHistoryMiss[underlying]?.let { System.currentTimeMillis() - it < HISTORY_MISS_MS } == true) emptyList() else null
        val hist = kept() ?: if (offline) throw HistoryNotKept(underlying) else {
            historyJob(underlying, day, bank, key).await()
            kept() ?: throw java.io.IOException("$underlying: its earlier sessions are not read yet")
        }
        // [reads] (the arms pass): today's candles shared with the pass's other books on this index, read once (13 Oct).
        // (19 Nov 2026, audit batch 2: under the lock, only what [reads] holds - an index not read before the lock is not read there.)
        val raw = reads?.readOrThrow(underlying) { if (offline) throw NotReadBeforeLock(underlying) else todayMinutes(underlying, t, quick) }
            ?: if (offline) throw NotReadBeforeLock(underlying) else todayMinutes(underlying, t, quick)
        val today = toBars(raw).filter { it.start.toLocalDate() == day }
        // Kept as this minute's read only once it has the minute just closed (round 2: a look at the close itself, before the
        // feed or the stream has that minute, is read again by the next look rather than kept for the rest of the minute).
        val whole = today.lastOrNull()?.start?.let { !it.isBefore(minute.minusMinutes(1)) } == true
        return (hist + today).distinctBy { it.start }.sortedBy { it.start }.also {
            if (!testing && keep && whole) liquidityPass[underlying] = minute to it
            else if (!testing && keep) liquidityLag[underlying] = Triple(nowMs, minute, it)
        }
    }

    // ---- 30 Oct: a switched-off arm runs in shadow (Boss: "If i switch any arm off, it should be always run in shadow and record
    // only, so i can continuously observe it") ---------------------------------------------------------------------------------
    //
    // An arm that is OFF - not armed, parked ([ParkedArms], [FamilyPark]) or hidden ([HiddenArms]) - still decides on every bar, on
    // the same index candles and with the same pure rules (OrbRules, SweepRules, RangeFadeRules, LiquidityRules, HeroRules) as when
    // it is on, and hands each would-be entry to [OffShadow] with its exits. It NEVER orders: nothing here calls [enter],
    // [buyPaperEntry], [enterLive], [Paper.place] or [Broker], and nothing here writes the arms' book. The ON path ([tickLocked],
    // [cycle] and everything after it) is untouched.
    //
    // Positions first (CLAUDE.md): the book is read under the arms' lock only for a moment (a copy of the switches and positions);
    // every network read (the index candles, the master, the option's price, the shadow's own price read) is made outside it, so
    // the open positions' 15-second stop looks never wait on the shadow. While a live position of these arms is open the shadow
    // skips its pass altogether ([liveHint]): the worker's time goes to that position alone.
    //
    // Signal source: the underlying (the index), as the arms; Hero's own signal reads the straddle's premium, as its ON path does
    // (labelled [HeroRules.SIGNAL_SOURCE] there).

    /**
     * TEST ONLY: whether the switched-off shadow runs while the arms' test feeds are set. Off by default, so the many arm tests written
     * before 30 Oct (which count the feed's reads and never set up [OffShadow]) run exactly as they did; the shadow's own tests
     * switch it on. In the app the test feeds are always null, so the shadow always runs there. Its setter throws in a release build.
     */
    @Volatile internal var testOffShadow: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }

    private fun testFeeds(): Boolean =
        testIndexBars != null || testHistoryBars != null || testOtherIndexBars != null || testOptionBars != null || testHeroBars != null

    /** Each switched-off arm's bars already decided today ("source|day" -> bar starts, memory only: a restart decides afresh). */
    private val offDecided = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** The last bar each switched-off ORB-family arm watched ("source|day" -> bar start): its fresh-break rule after a pause. */
    private val offWatched = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The day's strike and legs for the ORB family's shadow (as [contracts], but never written into the arms' book). */
    @Volatile private var offLegs: Legs? = null

    /**
     * A Liquidity would-be trade's own turn exits, kept to judge them on later passes ([offLiquidityExits]): its book, its ref as
     * asked, the side, the broken level, the target, the signal bar, the contract, its exit parameters and whether its time stop
     * was decided. Memory only: after a restart the shadow's resting exits (the premium stop, the index stop and target, 15:10)
     * still close it.
     */
    private class OffLiq(val book: String, val ref: String, val side: Int, val level: Double, val target: Double?, val signalBar: LocalDateTime,
                         val contract: Paper.Contract, val params: com.optionslab.engine.orb.LiquidityParams, @Volatile var timed: Boolean = false)

    /** Open Liquidity would-be trades by the shadow's full ref ("day|book|ref"). */
    private val offLiq = java.util.concurrent.ConcurrentHashMap<String, OffLiq>()

    /** One shadow pass at a time (the minute round and the bar-close check may both call [tick]): a second one is skipped. */
    private val offPassLock = Mutex()

    /** The book as the shadow reads it: the arms that are off, the positions, Liquidity's lots (a copy, taken under the lock). */
    private class OffSnap(val off: List<Arm>, val positions: List<Position>, val lots: Int)

    /** Hidden by Boss's cleanup ([HiddenArms]): off, so it runs in shadow (it still may not be switched on while hidden). */
    private fun hiddenNow(arm: Arm): Boolean = runCatching {
        HiddenArms.keyOfSource(arm.source)?.let { HiddenArms.blocks(it) } == true || (arm.hero && HiddenArms.blocks(ArmsCleanup.HERO))
    }.getOrDefault(false)

    private fun offNow(b: Book, arm: Arm): Boolean = b.armed[arm.source] != true || hiddenNow(arm)

    /** The row's shadow line for an arm that is off ([ArmView.offShadow]); null while it is on. Memory only. */
    private fun offLine(a: ArmView): String? {
        if (com.optionslab.app.BuildConfig.GOLD) return null
        if (a.armed && !hiddenNow(a.arm)) return null
        val keys = if (a.arm.liquidity) LiquidityRules.BOOKS.map { it.source }.toSet() else setOf(a.arm.source)
        val v = OffShadow.view.value
        return com.optionslab.engine.orb.OffShadow.OFF_WORDS + " · " +
            com.optionslab.engine.orb.OffShadow.words(v.closed.filter { it.key in keys }, v.open.count { it.key in keys })
    }

    /**
     * The other guards still apply to a would-be trade, so the shadow shows what the arm really would have done: the kill switch,
     * Boss's Stop for today and the paper day lock. (The account guard, the one-index-one-side rule, the order flow gate and the
     * no-twin rule are about the real paper book and are not asked: a would-be trade is never in that book.)
     */
    private suspend fun offRefusal(): String? = when {
        runCatching { AppSettings.load().guardKill }.getOrDefault(true) -> "refused: the kill switch is on"
        runCatching { Strategies.stoppedToday() }.getOrDefault(true) -> "stopped_for_today"
        else -> runCatching { DayLockGuard.refusal(false) }.getOrNull()
    }

    /** The would-be trades of [src] today, closed and open ([OffShadow]'s memory). */
    private fun offToday(sh: OffShadow.View, src: String, day: LocalDate): List<com.optionslab.engine.orb.OffShadow.Pos> =
        (sh.closedOf(src) + sh.openOf(src)).filter { it.day == day }

    /**
     * One shadow pass at [t]: the open Liquidity would-be trades' turn exits, then each switched-off arm's decision. [reads]: the
     * pass's index reads (shared with the armed arms' decisions just made). Never orders, never writes the arms' book.
     */
    private suspend fun offShadowPass(t: LocalDateTime, reads: IndexData.Reads<List<Upstox.Bar>>) {
        if (com.optionslab.app.BuildConfig.GOLD) return                    // IraGoldAlgo only talks: no arm runs there
        if (testFeeds() && !testOffShadow) return
        if (liveHint) return                                                // a live position open: its watch first (see above)
        if (!offPassLock.tryLock()) return
        try {
            val day = t.toLocalDate()
            offDecided.keys.removeAll { !it.endsWith("|$day") }
            offWatched.keys.removeAll { !it.endsWith("|$day") }
            offLiq.values.removeAll { it.signalBar.toLocalDate() != day }
            val sh = OffShadow.refresh()
            // The would-be trades' own turn exits, whether their arm is still off or was switched on since (a would-be trade runs
            // to its own exit, as a real one does).
            offLiquidityExits(t, reads, sh)
            if (!OrbRules.inWindow(t.toLocalTime()) || !t.toLocalTime().isBefore(OrbRules.SQUARE_OFF)) return
            if (Strategies.stoppedToday()) return
            val snap = lock.withLock { val b = book(); OffSnap(ALL_ARMS.filter { offNow(b, it) }, b.positions.toList(), lotsOf(b)) }
            if (snap.off.isEmpty()) return
            var orbBars: List<Bar>? = null
            var orbRead = false
            for (arm in snap.off) {
                val s = try {
                    when {
                        arm.hero -> offHero(t, snap, sh)
                        arm.liquidity -> offLiquidity(arm, t, snap, sh, reads)
                        else -> {
                            if (!orbRead) { orbRead = true; orbBars = runCatching { indexBars(t, reads) }.getOrNull() }
                            offOrb(arm, t, snap, sh, orbBars)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "error: ${e.javaClass.simpleName}"
                }
                // A would-be entry asked is said in the diagnostics (every other verdict stays in memory: the ON arm's own words
                // and status are never touched by the shadow).
                if (s.startsWith(OFF_ASKED)) runCatching { Diag.record("orb", "${arm.label}: $s") }
            }
        } finally {
            offPassLock.unlock()
        }
    }

    /** The start of the verdict when a would-be entry was handed to [OffShadow] (its own words follow). */
    private const val OFF_ASKED = "switched off - "

    /** The ORB family's legs for the shadow (as [contracts]: the first bar from 09:20, ATM, the expiry on or after today). */
    private fun offLegsFor(day: LocalDate, bars: List<Bar>): Legs? {
        offLegs?.let { if (it.day == day) return it }
        val ref = OrbRules.strikeBar(bars) ?: return null
        val strike = OrbRules.atmStrike(ref.close)
        val listed = Market.contracts().filter { it.underlying == OrbRules.UNDERLYING }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryOnOrAfter(day, listed) ?: return null
        val ce = Paper.contractFor(OrbRules.UNDERLYING, expiry, strike.toDouble(), Right.CE) ?: return null
        val pe = Paper.contractFor(OrbRules.UNDERLYING, expiry, strike.toDouble(), Right.PE) ?: return null
        return Legs(day, strike, expiry, ce, pe).also { offLegs = it }
    }

    /**
     * A switched-off ORB, ORB Fresh, ORB Sweep or Range Fade on the last completed BANKNIFTY bar ([bars]), as [cycle] decides it
     * on the PAPER account's parameters. Its exits as the shadow can hold them:
     *  - the premium stop [OrbParams.stopPoints] (-40) below the estimated fill, and the target [OrbParams.targetPoints] above it
     *    (+40; ORB Sweep +80), both in premium points - set from the option's price read at the signal (+5 bps, as the paper
     *    fill), as the shadow's own fill is priced after; a few paise apart at most;
     *  - the profit-lock ladder (a quarter of the way to the target locks breakeven, half locks a quarter, three quarters half):
     *    not expressible rung by rung - the nearest is a lock from half the target's rupees that keeps half of the best, which is
     *    the ladder's own level at its middle rung;
     *  - 15:10 ([OrbRules.SQUARE_OFF]); the expiry day's 15:05 is the shadow's own;
     *  - ORB Fresh's trade-manager exit (R12) cannot be expressed: its would-be trades run on the plain exits above.
     */
    private suspend fun offOrb(arm: Arm, t: LocalDateTime, snap: OffSnap, sh: OffShadow.View, bars: List<Bar>?): String {
        val day = t.toLocalDate()
        val key = "${arm.source}|$day"
        // Its own paper position (taken while it was on) still runs to its own exits in [priceCheck]: nothing new meanwhile.
        if (snap.positions.any { it.arm == arm.source && it.open }) return "holding"
        if (bars == null) return IndexData.none(OrbRules.UNDERLYING)
        fun watching() { bars.lastOrNull()?.let { offWatched[key] = it.start.toString() } }
        // One position per arm, as when it is on: a would-be trade still open is "holding".
        if (sh.openOf(arm.source).isNotEmpty()) { watching(); return "shadow_holding" }
        val ap = paramsOf(arm, day, live = false)
        val rng = OrbRules.openingRange(bars, ap.orEnd) ?: return "waiting_for_opening_range"
        val legs = offLegsFor(day, bars) ?: return "no_contract"
        val last = bars.last()
        if (!offDecided.getOrPut(key) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(last.start.toString())) return "no_decision_bar"
        val real = snap.positions.filter { it.arm == arm.source && it.day == day }
        val would = offToday(sh, arm.source, day)
        val lastExit = (real.mapNotNull { it.exitTime } + would.mapNotNull { it.exitTime }).maxOrNull()
        if (arm.freshOnly && real.any { it.why == MANAGER_EXIT || it.why == MANAGER_LOCK }) { watching(); return MANAGER_DONE }
        val entries = real.size + would.size
        val (dir, why) = when {
            arm.fade -> RangeFadeRules.entrySignal(bars, rng, lastExit, entries, ap.edge, ap.lastEntry)
            arm.sweep -> SweepRules.entrySignal(bars, rng, lastExit, entries, ap.lastEntry, ap.orEnd)
            else -> {
                // After a pause (just switched off, or a new process) only a fresh break is taken, as [cycle] after arming.
                val resumed = bars.size >= 2 && !offWatched.containsKey(key)
                val taken = com.optionslab.engine.orb.ReentryCap.taken(real.map { it.right } + would.map { if (it.view > 0) "CE" else "PE" })
                OrbRules.entrySignal(bars, rng, arm, lastExit, requireFresh = resumed, p = ap, taken = taken)
            }
        }
        watching()
        if (dir == 0) return if (why == "not_a_fresh_break" && !arm.freshOnly) "waiting_for_fresh_break_after_pause" else why
        val c = if (dir > 0) legs.ce else legs.pe
        offRefusal()?.let { return it }
        // The price at the signal (memory, else one read - never the one more read: the shadow never waits for a price).
        val est = Paper.entryPrice(c, entryQuote(c, mayRetry = false).quote) ?: return "shadow: no price for ${c.symbol}"
        val expected = est * 1.0005
        val stop = OrbRules.stopTrigger(expected, ap.stopPoints) ?: return "refused: premium %.2f is at or below %s, a %s-point stop has no level"
            .format(Locale.ENGLISH, expected, pts(ap.stopPoints), pts(ap.stopPoints))
        val ladder = ProfitLock.targetOf(arm) != null
        val exits = com.optionslab.engine.orb.OffShadow.Exits(stop = stop, target = expected + ap.targetPoints,
            lockAfterRs = if (ladder) ap.targetPoints * 0.5 * c.lotSize else null, lockKeep = 0.5, exitAt = OrbRules.SQUARE_OFF)
        val words = when {
            arm.fade -> "range fade ${if (dir > 0) "up" else "down"} on the ${hhmm(last.start)} BANKNIFTY bar"
            arm.sweep -> "failed break ${if (dir > 0) "below" else "above"} the range on the ${hhmm(last.start)} BANKNIFTY bar"
            else -> "BANKNIFTY closed ${if (dir > 0) "above" else "below"} the opening range on the ${hhmm(last.start)} bar"
        }
        return OFF_ASKED + OffShadow.enter(OffShadow.Ask(arm.source, arm.label, hhmm(last.start), c, dir, exits, words))
    }

    /**
     * A switched-off Liquidity book on its chart's last completed bar, as [liquidityCycle] decides it on PAPER (the room filter,
     * the 1-ITM strike, the expiry-day skip when Boss's paper switch is on). Its exits as the shadow can hold them:
     *  - the 15% premium stop ([LiquidityParams.stopPct]) under the estimated fill;
     *  - the index stop [LiquidityRules.indexStopPoints] back through the broken level, and the next liquidity level as the
     *    index target (when [LiquidityParams.nextLevelTarget]) - on the index, as the arm (the shadow judges "at or beyond";
     *    the arm "beyond": a hair stricter);
     *  - its 20-minute time stop, the failed break and new liquidity: judged here on later passes with the arm's own pure rule
     *    ([LiquidityRules.exitNow], [offLiquidityExits]) and closed with [OffShadow.exitNow];
     *  - Boss's ₹500 give-back trail when its switch is on: the nearest is a lock from +₹1,000 a lot that keeps half (the trail's
     *    own level when it starts; later it keeps a little more than the trail would). Boss's breakeven at +10% cannot be
     *    expressed and is left out (its would-be trades run on the plain exits). A lock variant: as the ORB family's ladder;
     *  - h14's capped limit (when Boss's paper switch is on): a LIMIT at the cap; the 3-minute wait for the price to come back
     *    under it is not modelled (not filled at once: not recorded);
     *  - 15:10 ([OrbRules.SQUARE_OFF]).
     */
    private suspend fun offLiquidity(arm: Arm, t: LocalDateTime, snap: OffSnap, sh: OffShadow.View, reads: IndexData.Reads<List<Upstox.Bar>>): String {
        val day = t.toLocalDate()
        if (snap.positions.any { it.arm == arm.source && it.open }) return "holding"
        if (sh.openOf(arm.source).isNotEmpty()) return "shadow_holding"
        val tf = LiquidityRules.minutesOf(arm)
        val und = LiquidityRules.underlyingOf(arm)
        val ones = try {
            liquidityMinutes(t, und, keep = false, reads = reads)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return IndexData.none(und)
        }
        val bars = liquidityBars(ones, tf, t)
        if (bars.size < 2 * LiquidityRules.SWING_LOOKBACK + 2) return "liquidity_history_loading"
        val last = bars.last()
        if (last.start.toLocalDate() != day) return "no_decision_bar"
        if (!offDecided.getOrPut("${arm.source}|$day") { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(last.start.toString())) return "no_decision_bar"
        if (!LiquidityRules.mayEnterAt(last.start.plusMinutes(tf.toLong()))) return "liquidity_outside_entry_hours"
        val s = LiquidityRules.signal(bars, LiquidityRules.zones(bars)) ?: return "no_liquidity_break"
        val expiryToday = runCatching {
            HeroRules.isExpiryDay(day, Market.cachedContracts()?.takeIf { it.first == day }?.second.orEmpty().filter { it.underlying == und }.map { it.expiry }.distinct())
        }.getOrDefault(false)
        if (expiryToday && runCatching { BossRiskHost.expirySkipPaper() }.getOrDefault(false)) return "refused: ${LiquidityRules.EXPIRY_DAY_WORDS}"
        if (!LiquidityRules.hasRoom(s, last.close, und)) return "liquidity_no_room"
        val strike = LiquidityRules.entryStrike(s.side, last.close, und)
        offRefusal()?.let { return it }
        val listed = Market.contracts().filter { it.underlying == und }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryAfter(day, listed) ?: return "no_contract"
        val c = Paper.contractFor(und, expiry, strike.toDouble(), if (s.side > 0) Right.CE else Right.PE) ?: return "no_contract"
        val lots = snap.lots
        val est = entryQuote(c, mayRetry = false).quote?.ltp?.takeIf { it > 0 } ?: return "shadow: no price for ${c.symbol}"
        val lq = liqParamsOf(arm.source, day, live = false)
        val cap = if (runCatching { BossRiskHost.cappedPaper() }.getOrDefault(false)) com.optionslab.ira.BossRisk.cap(est) else null
        val trail = LiquidityTrail.forEntry(arm, live = false, switchOn = runCatching { LiquidityTest.trailOn() }.getOrDefault(false), lot = c.lotSize)
        val trailFrom = LiquidityTrail.forEntryFrom(trail)
        val lockRef = lq.lockTarget(est)
        val (lockAfter, keep) = when {
            trail != null && trailFrom != null && trailFrom > 0 -> trailFrom * lots to ((trailFrom - trail) / trailFrom).coerceIn(0.0, 1.0)
            lockRef != null -> lockRef * 0.5 * lots * c.lotSize to 0.5
            else -> null to 0.6
        }
        val exits = com.optionslab.engine.orb.OffShadow.Exits(
            stop = LiquidityRules.stopTrigger(est * 1.0005, lq.stopPct),
            indexStop = s.level - s.side * LiquidityRules.indexStopPoints(und),
            indexTarget = s.target?.takeIf { lq.nextLevelTarget },
            lockAfterRs = lockAfter, lockKeep = keep, exitAt = OrbRules.SQUARE_OFF)
        val ref = hhmm(last.start)
        offLiq["$day|${arm.source}|$ref"] = OffLiq(arm.source, ref, s.side, s.level, s.target, last.start, c, lq)
        val words = "the $und ${tf}-minute ${hhmm(last.start)} bar took a liquidity pool ${if (s.side > 0) "above" else "below"} " +
            "(level %.1f)".format(Locale.ENGLISH, s.level)
        return OFF_ASKED + OffShadow.enter(OffShadow.Ask(arm.source, arm.label, ref, c, s.side, exits, words, lots = lots, limit = cap))
    }

    /**
     * The open Liquidity would-be trades' turn exits ([LiquidityRules.exitNow], the arm's own pure rule, in its order: the index
     * stop, the 20-minute time stop - decided once, on a fresh option price - then the next level, a failed break, new liquidity),
     * each closed by [OffShadow.exitNow] at the shadow's price. Reads outside the arms' lock; never orders.
     */
    private suspend fun offLiquidityExits(t: LocalDateTime, reads: IndexData.Reads<List<Upstox.Bar>>, sh: OffShadow.View) {
        if (offLiq.isEmpty()) return
        val open = sh.open.associateBy { it.ref }
        val day = t.toLocalDate()
        for ((full, w) in offLiq.entries.toList()) {
            val p = open[full]
            if (p == null) {
                // Not recorded (refused by the shadow) or already closed by its resting exits: nothing more to judge.
                if (sh.closed.any { it.ref == full } || !t.isBefore(w.signalBar.plusMinutes(LiquidityRules.minutesOf(armOf(w.book)) + 2L))) offLiq.remove(full)
                continue
            }
            try {
                val und = w.contract.underlying
                val ones = liquidityMinutes(t, und, keep = false, reads = reads).filter { !it.start.plusMinutes(1).isAfter(t) }
                val bars = liquidityBars(ones, LiquidityRules.minutesOf(armOf(w.book)), t)
                val since = ones.filter { !it.start.isBefore(p.entryTime.withSecond(0).withNano(0)) }
                // The option's price only when the time stop is due and not yet decided (one read, once).
                val fresh = if (!w.timed && LiquidityRules.timeStopDue(p.entryTime, t, w.params.timeStopMinutes))
                    runCatching { Paper.recentQuote(w.contract, Paper.FILL_FRESH_MS) }.getOrNull()?.ltp?.takeIf { it > 0 } else null
                val look = LiquidityRules.exitNow(w.side, w.level, w.target, w.signalBar, p.entry, p.entryTime, t, bars, since, und, fresh, w.timed, w.params)
                if (look.timed) w.timed = true
                val why = look.why ?: continue
                if (p.day != day) continue
                OffShadow.exitNow(w.book, w.ref, why)
                offLiq.remove(full)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // An index read that failed: judged again on the next pass (its resting exits still act meanwhile).
            }
        }
    }

    /**
     * The switched-off expiry Hero on the last closed minute, as [heroCycle] decides it (NIFTY, today's expiry, 13:30-14:45, one
     * entry a day; its signal reads the straddle's premium, as its ON path - [HeroRules.SIGNAL_SOURCE]). Its exits as the shadow
     * can hold them:
     *  - the -60% stop ([HeroRules.stopLevel]) on the LIMIT price, as a premium stop: the arm judges it on a minute's CLOSE, the
     *    shadow on the premium's low since its last look - stricter (a wick under it closes the would-be trade);
     *  - the 5x target ([HeroRules.target1]), where the arm sells half and keeps the rest for 20x: the shadow sells it all at 5x;
     *  - 15:05 ([HeroRules.EXIT_AT]).
     * Its lots as the arm's ([HeroRules.lots], the danger meter's half size); its LIMIT as the arm's ([HeroRules.limitPrice]).
     */
    private suspend fun offHero(t: LocalDateTime, snap: OffSnap, sh: OffShadow.View): String {
        val src = HeroRules.ARM.source
        val day = t.toLocalDate()
        if (snap.positions.any { it.arm == src && it.open }) return "holding"
        if (sh.openOf(src).isNotEmpty()) return "shadow_holding"
        val spent = offDecided.getOrPut("$src|$day") { java.util.concurrent.ConcurrentHashMap.newKeySet() }
        if (HERO_STOOD_DOWN in spent) return HERO_STOOD_DOWN
        if (!HeroRules.mayEnter(snap.positions.count { it.arm == src && it.day == day } + offToday(sh, src, day).size)) return "hero_done_for_today"
        val at = t.toLocalTime().withSecond(0).withNano(0)
        // The window first (no read at all outside it), then the master.
        if (at.isBefore(HeroRules.FIRST)) return "hero_waiting_for_window"
        if (at.isAfter(HeroRules.LAST)) return "hero_window_closed"
        if ("hero@$at" in spent) return "no_decision_bar"
        val master = runCatching { Market.contracts() }.getOrNull()?.filter { it.underlying == HeroRules.UNDERLYING }
        if (master.isNullOrEmpty()) return "hero_no_master"
        if (!HeroRules.isExpiryDay(day, master.map { it.expiry })) return "hero_not_expiry_day"
        val today = master.filter { it.expiry == day }
        val read = HashMap<String, Map<LocalTime, Upstox.Bar>>()
        suspend fun minutes(key: String): Map<LocalTime, Upstox.Bar> =
            read[key] ?: minutesOf(heroRead(key, t), t).also { read[key] = it }
        val spot = minutes(Upstox.INDEX_KEYS.getValue(HeroRules.UNDERLYING)).mapValues { it.value.close }
        val legs = HashMap<Pair<Double, Right>, Map<LocalTime, HeroRules.Leg>>()
        suspend fun legsOf(strike: Double, right: Right): Map<LocalTime, HeroRules.Leg> {
            legs[strike to right]?.let { return it }
            val c = today.firstOrNull { kotlin.math.abs(it.strike - strike) < 1e-6 && it.right == right }
            val m: Map<LocalTime, HeroRules.Leg> = if (c == null) emptyMap() else runCatching { minutes(c.instrumentKey) }.getOrDefault(emptyMap())
                .mapValues { HeroRules.Leg(it.value.close, it.value.volume) }
            legs[strike to right] = m
            return m
        }
        val straddle = HashMap<LocalTime, Double>()
        for ((u, x) in spot) {
            if (u.isBefore(HeroRules.LOW_FROM) || u.isAfter(at)) continue
            val k = HeroRules.atm(x).toDouble()
            val ce = HeroRules.legPrice(legsOf(k, Right.CE), u) ?: continue
            val pe = HeroRules.legPrice(legsOf(k, Right.PE), u) ?: continue
            straddle[u] = ce + pe
        }
        val scan = HeroRules.scan(spot, straddle, at)
        if (scan.standDown) { spent += HERO_STOOD_DOWN; return HERO_STOOD_DOWN }
        if (scan.why == "skipped_stale_bar") return "hero_skipped_stale_bar"
        spent += "hero@$at"
        if (scan.signal == 0) return "hero_" + scan.why
        val right = if (scan.signal > 0) Right.CE else Right.PE
        val s0 = scan.spot ?: return "hero_skipped_stale_bar"
        val side = today.filter { it.right == right && kotlin.math.abs(it.strike - s0) <= s0 * HeroRules.SPOT_BAND }
            .filter { if (scan.signal > 0) it.strike > s0 else it.strike < s0 }.sortedBy { kotlin.math.abs(it.strike - s0) }
        val chain = ArrayList<HeroRules.Candidate>()
        for (o in side) {
            val bars = legsOf(o.strike, right).filterKeys { !it.isAfter(at) }
            val ltp = bars.maxByOrNull { it.key }?.value?.close ?: 0.0
            val traded = bars.any { (u, l) -> l.volume > 0 && !u.isBefore(at.minusMinutes(HeroRules.TRADED_BARS - 1)) }
            chain += HeroRules.Candidate(o.strike, ltp, ltp.takeIf { it > 0 }, traded)
        }
        val pick = HeroRules.pick(scan.signal, s0, chain) ?: return "hero_no_strike"
        val c = Paper.contractFor(HeroRules.UNDERLYING, day, pick.strike, right) ?: return "no_contract"
        if (c.lotSize <= 0) return "hero_lot_unknown"
        offRefusal()?.let { return it }
        val q = runCatching { Paper.quote(c) }.getOrNull()
        val ask = q?.ask?.takeIf { it > 0 } ?: q?.ltp?.takeIf { it > 0 } ?: pick.ltp
        val limit = HeroRules.limitPrice(ask, pick.ltp)
        val lots = DangerToday.paperLots(HeroRules.lots(limit, c.lotSize), day)
        if (lots <= 0) return "hero_zero_lots"
        val exits = com.optionslab.engine.orb.OffShadow.Exits(stop = HeroRules.stopLevel(limit), target = HeroRules.target1(limit), exitAt = HeroRules.EXIT_AT)
        val words = "NIFTY %s %.2f%% in 15 min, straddle %.0f%% above its low at %s"
            .format(Locale.ENGLISH, if (scan.signal > 0) "up" else "down", (scan.mom ?: 0.0) * 100, (scan.stExp ?: 0.0) * 100, hhmm(at)) +
            " · " + HeroRules.SIGNAL_SOURCE
        return OFF_ASKED + OffShadow.enter(OffShadow.Ask(src, HeroRules.ARM.label, "hero@$at", c, scan.signal, exits, words, lots = lots, limit = limit))
    }

    // ---- data ------------------------------------------------------------------

    /**
     * TEST ONLY: the BANKNIFTY 1-minute bars as the feed would return them at a given moment, so a test can run the
     * minute cycle over a whole day. Null in the app, always: [indexBars] then reads [Net.intraday], exactly as before.
     * Its setter throws unless BuildConfig.DEBUG (as [testNow]); no app code sets it.
     */
    @Volatile internal var testIndexBars: ((LocalDateTime) -> List<Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test index feed exists only in debug builds" }; field = v }

    /**
     * Today's completed 5-minute BANKNIFTY bars, built from the 1-minute feed - read through the pass's [reads], so the
     * Liquidity BANKNIFTY books share the same read (13 Oct). Throws when BANKNIFTY's candles could not be read.
     */
    private suspend fun indexBars(t: LocalDateTime, reads: IndexData.Reads<List<Upstox.Bar>>, offline: Boolean = false): List<Bar> {
        // (19 Nov 2026, audit batch 2: [offline] - the arms' pass, whose reads were made before the lock: never read here.)
        val ones = reads.readOrThrow(OrbRules.UNDERLYING) { if (offline) throw NotReadBeforeLock(OrbRules.UNDERLYING) else todayMinutes(OrbRules.UNDERLYING, t) }
        return OrbRules.completed(fiveMinute(ones, t.toLocalDate(), t), t)
    }

    /**
     * Today's 1-minute candles of [underlying]'s index as the arms read them: the feed with the stream's newer minutes
     * ([streamed]); in a test, the test feeds (BANKNIFTY's [testIndexBars], the others' [testOtherIndexBars]; an index
     * without a feed has none - a test never reaches the network). Throws when the feed cannot be read.
     */
    private suspend fun todayMinutes(underlying: String, t: LocalDateTime, quick: Boolean = false): List<Upstox.Bar> {
        val bank = underlying == OrbRules.UNDERLYING
        // 19 Nov 2026 (audit batch 2): [quick] - a read off the lock on a deadline: one retry at most, short timeouts; a test feed is read
        // so that the deadline can end it, as it ends a network read.
        if (bank) testIndexBars?.let { f -> return if (quick) kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { f(t) } else f(t) }
        if (testIndexBars != null || testHistoryBars != null || testOtherIndexBars != null)
            return if (bank) emptyList() else testOtherIndexBars?.let { f ->
                if (quick) kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { f(underlying, t) } else f(underlying, t) }.orEmpty()
        val key = LiquidityRules.INDEX_KEYS[underlying] ?: Upstox.INDEX_KEYS.getValue(underlying)
        return streamed(underlying, if (quick) Net.intradayQuick(key) else Net.intraday(key), t)
    }

    /** 19 Nov 2026 (audit batch 2, last round): an index's earlier sessions not in memory yet under the arms' lock - never read there. */
    private class HistoryNotKept(index: String) : java.io.IOException("$index: its earlier sessions are not read yet")

    /** 19 Nov 2026 (audit batch 2): an index's candles asked for under the arms' lock that were not read before it - never read there. */
    private class NotReadBeforeLock(index: String) : java.io.IOException("$index: its candles were not read before the arms' lock this pass")

    /** 19 Nov 2026 (audit batch 2): how long the arms' pass may read the index candles before its decisions, all indices together. */
    internal const val PASS_READ_MS = 10_000L
    /** 19 Nov 2026 (audit batch 2): the same for a regular stop look's Liquidity index exits. */
    internal const val LOOK_READ_MS = 5_000L

    /**
     * 19 Nov 2026 (audit batch 2: the phone showed "bar-close entry check: ORB arms 10 min 11 s" - every candle read, with its six tries,
     * 45 s timeouts and 429 waits, ran under the arms' lock, the open positions' stop checks waiting behind it): today's candles of
     * [unds] read OFF the lock, each index once, all within [budgetMs] (the deadline ends a hung read); a [liq] index's earlier sessions
     * too (once a day) and the minute's read kept as Liquidity's own ([liquidityMinutes]). An index not read in time is a failed read for
     * this pass: only the books on it say "no data", and its positions keep every exit that needs no index candle. Never throws.
     */
    private suspend fun prefetchIndices(t: LocalDateTime, unds: Collection<String>, liq: Set<String>, budgetMs: Long): IndexData.Reads<List<Upstox.Bar>> {
        val started = System.nanoTime()
        try { return prefetchWithin(t, unds, liq, budgetMs) } finally {
            lastPrefetch = budgetMs to (System.nanoTime() - started) / 1_000_000
        }
    }

    /** TEST ONLY (20 Nov 2026): the pass's read budget (null in the app: [PASS_READ_MS]). */
    @Volatile internal var testPassReadMs: Long? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }

    /** 20 Nov 2026: the last read before the lock - its budget and how long it took (ms); memory only (the tests read it). */
    @Volatile internal var lastPrefetch: Pair<Long, Long>? = null
        private set

    private suspend fun prefetchWithin(t: LocalDateTime, unds: Collection<String>, liq: Set<String>, budgetMs: Long): IndexData.Reads<List<Upstox.Bar>> {
        val reads = IndexData.Reads<List<Upstox.Bar>>()
        val end = System.currentTimeMillis() + budgetMs
        for (u in unds.distinct()) {
            val left = end - System.currentTimeMillis()
            val got: Result<List<Upstox.Bar>> = try {
                if (left <= 0) Result.failure(java.io.IOException("$u: no time left to read its candles this pass"))
                else kotlinx.coroutines.withTimeoutOrNull(left) { todayMinutes(u, t, quick = true) }?.let { Result.success(it) }
                    ?: Result.failure(java.io.IOException("$u: its candles did not come within ${budgetMs / 1000} s"))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
            reads.read(u) { got.getOrThrow() }
        }
        // (19 Nov 2026, last round: today's candles of every index first, then the earlier sessions with the time left - one index's slow
        // history never leaves the others without today's candles.)
        for (u in unds.distinct()) {
            if (u !in liq || reads.failed(u)) continue
            val left2 = end - System.currentTimeMillis()
            if (left2 > 0) try {
                kotlinx.coroutines.withTimeoutOrNull(left2) { liquidityMinutes(t, u, reads = reads, quick = true) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
        return reads
    }

    /**
     * 19 Nov 2026 (audit batch 2): Liquidity's minutes of [underlying] already read for [t]'s minute (kept in memory), or null - the fast
     * looks' index exits use only these (no read at all on a look of the stream's). Never in a test with the arms' feeds set.
     */
    private fun minutesInMemory(underlying: String, t: LocalDateTime): List<Bar>? {
        testMinutesInMemory?.let { f -> return f(underlying, t)?.let { raw -> toBars(raw) } }
        if (testIndexBars != null || testHistoryBars != null || testOtherIndexBars != null) return null
        val minute = t.withSecond(0).withNano(0)
        liquidityPass[underlying]?.let { if (it.first == minute) return it.second }
        return liquidityLag[underlying]?.takeIf { lagFresh(it.first, it.second, System.currentTimeMillis(), minute) }?.third
    }

    /**
     * [raw] (the candle feed's minutes of [underlying]'s index today) with the minutes it does not have yet built from
     * Zerodha's stream, when the stream streams the index (08 Oct, research X1 change 2: a bar is decided within seconds
     * of its close, not when the feed catches up). Without the stream, [raw] as before. Never throws.
     */
    private suspend fun streamed(underlying: String, raw: List<Upstox.Bar>, t: LocalDateTime): List<Upstox.Bar> {
        // Round 2: the local candles by the exchange's stamp ([LocalCandles]): added only when whole and without a gap after
        // the feed's last minute; otherwise the feed's own candles, as before.
        val key = LiquidityRules.INDEX_KEYS[underlying] ?: Upstox.INDEX_KEYS[underlying] ?: return raw
        if (raw.none { it.istDate == t.toLocalDate() }) return raw
        val nowSec = t.atZone(com.optionslab.engine.IST).toEpochSecond()
        return runCatching { LocalCandles.overlay(key, raw, nowSec) }.getOrDefault(raw)
    }

    /**
     * The indices the armed arms read, followed on Zerodha's stream while it runs (the "index" owner never starts it on
     * its own: the stream stays as the rest of the app needs it). Never throws.
     */
    private fun followIndices(b: Book) = runCatching {
        val unds = ALL_ARMS.filter { b.armed[it.source] == true && !it.hero }
            .map { if (it.liquidity) LiquidityRules.underlyingOf(it) else OrbRules.UNDERLYING }.distinct()
        // 20 Oct, review: always with the indices the stream carries for everyone (NIFTY, BANKNIFTY, VIX). This owner's set
        // REPLACES the earlier one, so "no arm armed" removed them all: the big-move recorder, the local candles and the widget
        // lost their index ticks on every pass.
        KiteStream.want("index", KiteStream.INDEX_TOKENS + unds.mapNotNull { u -> Broker.indexKey(u)?.let { Broker.tokenOf(it) } })
    }

    /**
     * 1-minute bars folded into 5-minute bars labelled by their start. A bar still
     * missing a minute is kept out for two minutes after it ends, so a late print
     * cannot turn into a different signal once decided.
     */
    fun fiveMinute(ones: List<Upstox.Bar>, day: LocalDate, now: LocalDateTime? = null): List<Bar> =
        ones.filter { it.istDate == day }.groupBy { it.istMinute - it.istMinute % 5 }.toSortedMap().mapNotNull { (m, g) ->
            val start = day.atTime(m / 60, m % 60)
            val s = g.sortedBy { it.epochSecond }
            if (now != null && s.size < 5 && now.isBefore(start.plusMinutes(7))) return@mapNotNull null
            Bar(start, s.first().open, s.maxOf { it.high }, s.minOf { it.low }, s.last().close)
        }

    // ---- the evening replay (TODO A8) ----------------------------------------------

    /**
     * After 15:35 on a trading day: replay the day on its own bars (decide on a
     * bar's close, fill on the next bar's open) for both arms, beside what the
     * paper arms actually did, and note whether the index closed up or down for
     * the pass rule. No orders. Once a day.
     */
    @Volatile private var lastReplayTry = 0L

    suspend fun replayIfDue(): Boolean {
        // Called on every refresh: when the day's data is not in yet, try again at most every 10 minutes.
        if (System.currentTimeMillis() - lastReplayTry < 10 * 60_000) return false
        lastReplayTry = System.currentTimeMillis()
        runCatching { backfillUpDays() }
        val t = now()
        val day = t.toLocalDate()
        if (!Market.isTradingDay(day) || t.toLocalTime().isBefore(LocalTime.of(15, 35))) return false
        // Liquidity BANKNIFTY's own evening replay (its go-live checklist), once a day, apart from the ORB arms'.
        runCatching { liquidityReplay(day) }
        val legs = lock.withLock { book().let { b -> if (b.replays.containsKey(day.toString())) return false; b.legs?.takeIf { it.day == day } } }
        val index = fiveMinute(Net.intraday(Upstox.INDEX_KEYS.getValue(OrbRules.UNDERLYING)), day)
        if (index.isEmpty()) return false
        val out = JSONObject().put("up", index.last().close > index.first().open)
        if (legs != null) {
            val ce = fiveMinute(Net.intraday(legs.ce.feedKey), day).associateBy { it.start }
            val pe = fiveMinute(Net.intraday(legs.pe.feedKey), day).associateBy { it.start }
            val aligned = index.filter { it.start in ce && it.start in pe }
            // Liquidity has priority over the ORB arms (07 Oct): the day's Liquidity BANKNIFTY trades, so the replay marks
            // the trades the app's guard would have refused while Liquidity held the index ([com.optionslab.engine.orb.ArmPriority]).
            val liq = lock.withLock { liquidityLegs(book(), day) }
            for (arm in OrbRules.ARMS) {
                // The paper account's re-entry cap (R14) as the arm traded it today; 0: none.
                val cap = runCatching { paramsOf(arm, day, false).sideCap }.getOrDefault(0)
                val trades = Replay.day(arm, aligned, aligned.map { ce.getValue(it.start) }, aligned.map { pe.getValue(it.start) }, sideCap = cap)
                val refused = runCatching { com.optionslab.engine.orb.ArmPriority.refusedInReplay(arm, day, trades, liq) }.getOrDefault(emptyMap())
                out.put(arm.source, JSONArray().apply {
                    trades.forEachIndexed { i, tr -> put(JSONObject().put("bar", tr.signalBar).put("exitBar", tr.exitBar).put("right", tr.right)
                        .put("entry", tr.entry).put("exit", tr.exit).put("why", tr.why).put("pnl", tr.points * legs.ce.lotSize)
                        .apply { refused[i]?.let { put("refused", it) } }) }
                })
            }
            out.put("strike", legs.strike).put("lot", legs.ce.lotSize)
        } else out.put("note", "No strike was fixed today (the arms were not running at 09:20), so there is nothing to replay the options on.")
        lock.withLock { val b = book(); b.replays[day.toString()] = out; b.upDays[day.toString()] = out.getBoolean("up"); save(b) }
        return true
    }

    /**
     * Liquidity BANKNIFTY's evening replay of [day]: its 15- and 5-minute books' decisions on the day's index minutes, as the app
     * decides them live ([com.optionslab.engine.orb.LiquidityDayReplay]); kept for the go-live checklist's comparison. Reads the
     * feed (a worker thread, after the close); places nothing. Once a day.
     */
    private suspend fun liquidityReplay(day: LocalDate) {
        if (lock.withLock { book().liqReplays.containsKey(day.toString()) }) return
        val ones = liquidityMinutes(day.atTime(15, 31), OrbRules.UNDERLYING, keep = false)
        if (ones.none { it.start.toLocalDate() == day }) return
        val out = JSONArray()
        for (arm in LiquidityRules.BOOKS.filter { it.source in com.optionslab.engine.orb.ArmPriority.BN_BOOKS }) {
            com.optionslab.engine.orb.LiquidityDayReplay.signals(arm, ones, day).forEach { s ->
                out.put(JSONObject().put("book", arm.source).put("bar", s.bar.toString()).put("side", s.side).put("strike", s.strike).put("level", s.level))
            }
        }
        lock.withLock { val b = book(); b.liqReplays[day.toString()] = out; save(b) }
    }

    /** The kept replays as the checklist reads them. */
    private fun liquidityReplays(b: Book): Map<LocalDate, List<com.optionslab.ira.LiquidityGoLive.ReplaySignal>> =
        b.liqReplays.mapNotNull { (k, a) ->
            val d = runCatching { LocalDate.parse(k) }.getOrNull() ?: return@mapNotNull null
            d to (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o ->
                com.optionslab.ira.LiquidityGoLive.ReplaySignal(o.getString("book"), LocalDateTime.parse(o.getString("bar")), o.getInt("side"), o.getInt("strike"))
            } }.getOrNull() }
        }.toMap()

    /** [b]'s Liquidity trades on BANKNIFTY on [day], as the guard's replay reads them ([com.optionslab.engine.orb.ArmPriority.Leg]). */
    private fun liquidityLegs(b: Book, day: LocalDate): List<com.optionslab.engine.orb.ArmPriority.Leg> =
        b.positions.filter { it.day == day }.mapNotNull { p ->
            val arm = (ALL_ARMS + LiquidityRules.ARM).firstOrNull { it.source == p.arm }?.takeIf { it.liquidity } ?: return@mapNotNull null
            val und = com.optionslab.ira.AutoSide.underlyingOf(p.symbol) ?: LiquidityRules.underlyingOf(arm)
            if (und != OrbRules.UNDERLYING) return@mapNotNull null
            com.optionslab.engine.orb.ArmPriority.Leg(arm.source, com.optionslab.engine.orb.ArmPriority.Rank.LIQUIDITY, und,
                if (p.right == "CE") 1 else -1, p.entryTime, p.exitTime, p.live)
        }

    /** Up or down day (index close vs open) for past trade days the evening replay never recorded, for the pass rule. */
    private suspend fun backfillUpDays() {
        val missing = lock.withLock { book().let { b -> b.positions.map { it.day }.distinct().filter { it.isBefore(today()) && !b.upDays.containsKey(it.toString()) } } }
        if (missing.isEmpty()) return
        val key = Upstox.INDEX_KEYS.getValue(OrbRules.UNDERLYING)
        val found = HashMap<String, Boolean>()
        for (d in missing.takeLast(10)) {
            val bars = runCatching { fiveMinute(Net.history(key, d, d), d) }.getOrNull().orEmpty()
            if (bars.isNotEmpty()) found[d.toString()] = bars.last().close > bars.first().open
        }
        if (found.isNotEmpty()) lock.withLock { val b = book(); b.upDays.putAll(found); save(b) }
    }

    // ---- text ------------------------------------------------------------------

    private fun hhmm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)
    private fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

    /**
     * The arm's status in plain words, for [arm] (review G2-6): an ORB-family arm's opening range and entry times are its own
     * parameters as its last pass used them ([shownParams]); with its constants, or no arm given, the words are as before.
     */
    fun describe(s: String, arm: Arm?): String {
        val p = arm?.takeIf { it.source in com.optionslab.ira.ArmParams.ORB_ARMS }?.let { shownParams[it.source] }
        // (The re-entry cap (R14) changes no range or time: an arm whose only change is the cap keeps the usual words.)
        if (arm == null || p == null || p.copy(sideCap = 0) == com.optionslab.engine.orb.OrbParams.of(arm)) return describe(s)
        val (first, lastBar) = OrbRules.decisionBars(p)
        return when (s) {
            "waiting_for_opening_range" -> "Waiting for the opening range (09:15-${hhmm(p.orEnd)})."
            "not_at_the_edge" -> "Waiting for a bar at the edge of the range that closes back inside (Range Fade, " +
                "${hhmm(RangeFadeRules.FIRST_BAR)}-${hhmm(lastBar)})."
            "no_decision_bar" -> "No decision bars now (entries ${hhmm(if (arm.fade) RangeFadeRules.FIRST_BAR else first)}-${hhmm(lastBar)})."
            else -> describe(s)
        }
    }

    /**
     * The re-entry cap in force for [arm] today (R14, [com.optionslab.engine.orb.ReentryCap]) on each account, for the row's
     * details; null for an arm it does not apply to. Memory only (the lab's book as loaded): no I/O.
     */
    fun capLine(arm: Arm): String? {
        if (arm.source !in com.optionslab.engine.orb.ReentryCap.ARMS) return null
        val day = runCatching { today() }.getOrNull() ?: return null
        val paper = runCatching { paramsOf(arm, day, false).sideCap }.getOrDefault(0)
        val live = runCatching { paramsOf(arm, day, true).sideCap }.getOrDefault(0)
        fun w(c: Int) = if (c > 0) "at most $c ${if (c == 1) "entry" else "entries"} a side a day" else "no cap"
        return "Re-entries (research R14): paper ${w(paper)}, live ${w(live)}" +
            if (live == 0 && paper > 0) " (live changes only with your PIN in the Strategy lab)." else "."
    }

    /** The arm's status in plain words. */
    fun describe(s: String): String = when {
        s.isEmpty() -> "Waits for the market watch."
        s == "entered" -> "Entered (paper)."
        s == "entered_live" -> "Entered at Zerodha (live)."
        s == "entered_unconfirmed" -> "Bought at Zerodha, not yet confirmed: held until the order book shows what filled."
        s == "holding" -> "Holding a position."
        s == "waiting_for_opening_range" -> "Waiting for the opening range (09:15-10:00)."
        s == "inside_range" -> "Waiting for a breakout: the last bar closed inside the range."
        s == "no_sweep" -> "Waiting for a failed break: no bar has gone through the range and closed back inside."
        s == "not_at_the_edge" -> "Waiting for a bar at the edge of the range that closes back inside (Range Fade, 10:30-13:55)."
        s == "no_range" -> "The opening range is too narrow to fade."
        s == "day_limit_reached" -> "Done for today: ${SweepRules.MAX_ENTRIES} entries taken."
        s == "not_a_fresh_break" -> "Last bar continued an earlier break; ORB Fresh waits for a fresh one."
        s == "waiting_for_fresh_break_after_pause" -> "Armed or restarted while the price was already out of the range: this bar is not chased; the next bar out of the range is taken."
        s == "cooling_down_after_exit" -> "Just exited; may re-enter from the next bar."
        com.optionslab.engine.orb.ReentryCap.isCap(s) -> com.optionslab.engine.orb.ReentryCap.words(s)
        s == MANAGER_DONE -> "Done for today: the trade manager took today's trade out early (research R12's rule); no re-entry today."
        s == "no_decision_bar" -> "No decision bars now (entries 10:05-13:55; ORB Sweep to 14:25)."
        s == "flat_after_square_off" -> "Done for the day (square-off 15:10)."
        s == "stopped_for_today" -> DayStop.line(Strategies.stopHint() ?: DayStop.Why.BOSS)
        s == "awaiting_approval" -> if (liveNow()) "Breakout: waiting for your approval with PIN (live)." else "Breakout: waiting for your approval."
        s == "skipped_by_you" -> "You skipped the last signal."
        s == "no_contract" -> "The day's BANKNIFTY contracts could not be loaded."
        IndexData.isNone(s) -> "No ${IndexData.indexOf(s) ?: OrbRules.UNDERLYING} bars yet."
        s == "no_liquidity_break" -> "Waiting for a close through a liquidity pool that sits on a swing zone."
        s == "liquidity_history_loading" -> "Loading the last days' index candles for the liquidity levels."
        s == LIST_WAITING -> "A liquidity break waits for today's instrument list (still loading); decided again within its fill window."
        s == STALE_WAITING -> "A liquidity break on a late or stale bar is not entered live (fresh-bar check on); decided again if fresh candles land in time."
        s == "liquidity_outside_entry_hours" -> "No new entries now (liquidity entries 09:20-14:00)."
        s == FlowGate.SKIPPED -> "Skipped by the order flow (CONFIRM): the flow did not agree with this entry at the decision."
        s.startsWith(com.optionslab.ira.LiquidityTwin.SKIPPED) -> "Not bought (a would-be trade, counted apart): " +
            s.substringAfter(": ").substringAfter("no-twin rule: ") + "."
        s == CAP_WAITING -> "A liquidity break is waiting up to 3 minutes for its option to come back under the capped limit (research h14: 0.5% over the signal's price, never chasing)."
        s == "liquidity_no_room" -> "Skipped a liquidity break: the next level ahead was closer than one index stop " +
            "(30 BANKNIFTY / 15 FINNIFTY / 8 MIDCPNIFTY points)."
        s.startsWith("hero_") -> describeHero(s) + " ${HeroRules.NOT_PROVEN}."
        s.startsWith("guard_refused: ") -> "Refused by Bot settings: " + s.removePrefix("guard_refused: ")
        s.startsWith("refused: ") -> "Refused: " + s.removePrefix("refused: ")
        s.startsWith("order_refused: ") -> "The order was refused: " + s.removePrefix("order_refused: ")
        s.startsWith("unfilled: ") -> "Not bought: " + s.removePrefix("unfilled: ") + "."
        s.startsWith("account_changed: ") -> "Not bought: " + s.removePrefix("account_changed: ") + "."
        s.startsWith("error: ") -> "Could not check: " + s.removePrefix("error: ")
        // Boss's 06 Oct rule: the refusal as it is ("opposite_position_open: ORB Sweep holds ..."), then in words.
        com.optionslab.ira.AutoSide.refused(s) -> "$s. " + com.optionslab.ira.AutoSide.describe(s)
        else -> s
    }

    /** TEST ONLY: the book forgotten from memory (its file kept), as a new process starts. */
    @Synchronized internal fun forgetMemoryForTest() {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        cache = null; writtenText = null; writtenStat = null; coldSwept = false; coldGateSaid = false; coldFailSince = null; coldFailSaid = false; coldNextAt = 0L
        erasedAt = null; coldSafety.clear()
        lastLook.clear(); gapTried.clear(); watchFrom = null; indexReadSaid.clear(); exitCheckSaid.clear()   // as a new process
        readFails = 0
        forgetHistoryForTest()
        synchronized(ownCancels) { ownCancels.clear() }; exitMarks.clear(); lostExits.clear(); exitSales.clear(); barWaits.clear(); liqFolds.clear()
        forgetStopSaid(); lostSweepAgeMsForTest = null; synchronized(sweepCancels) { sweepCancels.clear() }; sweepTries.clear()
        sweepDays.clear(); ownCancelsRestored.clear(); ownCancelsUnsure.clear(); exitMarksRestored.clear(); salesBooked.clear()
    }

    /**
     * "Erase everything": the book and every hint from it dropped, the file deleted. 26 Oct: a pass still holding the old book
     * (it waits on the coroutine lock, which the erase never takes) saves nothing ([Book.gen]); the hints the old wipe missed
     * (armed, the paper legs for the live path, the BANKNIFTY live and stop hints) and the open positions' exits go too, and the
     * set-aside copies of the file. Never reads the vault; safe before [init].
     */
    @Synchronized fun wipe() {
        synchronized(ownCancels) { ownCancels.clear() }; exitMarks.clear(); lostExits.clear(); exitSales.clear(); barWaits.clear(); liqFolds.clear()
        // (22 Nov 2026, B6 review: the marks taken back with the erased book go with it. The closed trades' stops still being cancelled
        // ([sweepCancels], their days and tries) are NOT dropped: they are orders resting at Zerodha, not the book's record - forgotten, one
        // could rest unwatched on a contract still held (Boss's own lots) and sell them, or open a short; nothing else would take it out
        // after an erase. The sweep ends each once Zerodha shows it ended, and at the day's end.)
        ownCancelsRestored.clear(); ownCancelsUnsure.clear(); exitMarksRestored.clear()
        forgetStopSaid()
        // (19 Nov 2026: a pending write is dropped; never a wait on one running - the screen may erase - which deletes the file again
        // itself if it finishes after this erase ([flushSoon]).)
        synchronized(writeOrder) { wipeGen++; pendingWrite = null; saveSeq++ }
        coldSwept = false; coldGateSaid = false; coldFailSince = null; coldFailSaid = false; coldNextAt = 0L; coldSafety.clear()
        readFails = 0; strayLeft.clear(); strongAsks.clear(); strongKept.clear()
        erasedAt = runCatching { now().withNano(0).toString() }.getOrNull()
        cache = null; restoppedAt.clear(); writtenText = null; writtenStat = null
        synchronized(lostExits) { lostExits.clear() }                    // (again, after the gen bump; a read in flight brings none back)
        forgetHints()
        shownParams.clear()
        lastLook.clear(); gapTried.clear(); indexReadSaid.clear(); exitCheckSaid.clear()
        noPriceTried.clear(); noPriceSaid.clear(); liquidityLag.clear(); liquidityHistoryMiss.clear(); capWaits.clear()
        offDecided.clear(); offWatched.clear(); offLiq.clear(); offLegs = null              // 30 Oct: the switched-off shadow's memory
        runCatching { com.optionslab.ira.ArmExits.set("orb", emptyList()) }
        filesForErase().forEach { runCatching { it.delete() } }
    }

    /** TEST ONLY (19 Nov 2026, audit batch 2): holds the arms' lock until [release] opens, as a pass placing an order does. */
    internal suspend fun holdLockForTest(release: java.util.concurrent.CountDownLatch, held: java.util.concurrent.CountDownLatch? = null) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        lock.withLock { held?.countDown(); kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { release.await(10, java.util.concurrent.TimeUnit.MINUTES) } }
    }

    /** Every hint read without the lock, as before any book is read. */
    private fun forgetHints() {
        holdingHint = false; armedHint = false; armedLabelsHint = emptyList(); liveHint = false; liveSymbolsHint = emptyList(); liveQtyHint = emptyMap(); liveClosedHint = emptyList()
        exposureHint = emptyList(); liquidityLotsHint = LiquidityLots.CHOICES.first(); liquidityBnLiveHint = false
        liquidityStopHint = NO_STOP; liquidityStopPaperHint = NO_STOP; mirrorLegs = null
        viewBook = null
    }

    /** True (and [b] and its hints dropped) when an erase came after [b] was read: a pass that raced it keeps nothing. */
    private fun wipedSince(b: Book): Boolean {
        if (b.gen == wipeGen) return false
        if (cache === b) cache = null
        forgetHints()
        return true
    }

    /** Memory only, for the erase's tests. */
    internal fun holdsForErase(): Boolean = runCatching {
        cache?.let { b -> b.positions.isNotEmpty() || b.armed.values.any { it } || b.pending.isNotEmpty() || b.replays.isNotEmpty() ||
            b.liqReplays.isNotEmpty() || b.liqLots != null || b.liqLotsChosen } == true ||
            holdingHint || armedHint || armedLabelsHint.isNotEmpty() || liveHint || exposureHint.isNotEmpty() || !mirrorLegs.isNullOrEmpty()
    }.getOrDefault(true)

    /** The book's file and any set-aside copy (none before [init]). */
    internal fun filesForErase(): List<File> {
        if (!::file.isInitialized) return emptyList()
        val aside = runCatching { file.parentFile?.listFiles { x -> x.name.startsWith(file.name + ".unreadable.") }?.toList() }.getOrNull().orEmpty()
        return listOf(file) + aside
    }
}
