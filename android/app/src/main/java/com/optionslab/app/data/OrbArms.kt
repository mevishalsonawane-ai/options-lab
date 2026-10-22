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
 * the app in Live asks for approval until it is armed again in Live. Each position
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
         * 22 Oct: a paper entry priced again for its fill - its first price read found none, or its first order found no price
         * and was cancelled - so the same trade was bought on the one more read or order. A label only (the trade is the one the
         * rules asked for), so these entries can be told apart from the plain record.
         */
        val priceRetry: Boolean = false,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        val points: Double? get() = exit?.let { it - entry }
        /** The whole trade's P&L before charges: what is held now at [exit], plus any part sold early at [soldAt]. */
        val grossPnl: Double? get() = points?.let { it * qty + ((soldAt ?: entry) - entry) * sold }
        /** Lots bought ([lot] known): [qty] plus any part sold early, over the lot; 1 when not known (before the size setting). */
        val lots: Double get() = lot?.takeIf { it > 0 }?.let { (qty + sold).toDouble() / it } ?: 1.0
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
        /** Armed while in Live with the owner's PIN or fingerprint: automatic live entries allowed. */
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
    )

    private var cache: Book? = null

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

    private fun book(): Book {
        cache?.let { return it }
        writtenText = null; writtenStat = null
        val b = Book()
        // A book saved by an earlier build (false: a new install, or wiped): only such a book goes through the one-time changes.
        var existed = false
        val ok = runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file)?.also { existed = true } ?: return@runCatching, Charsets.UTF_8))
            // A book saved under its old name (FINNIFTY's 15-minute liquidity book, now its 30-minute one) carries on under the new.
            fun key(k: String) = LiquidityRules.RENAMED[k] ?: k
            o.optJSONObject("armed")?.let { m -> m.keys().forEach { b.armed[key(it)] = m.getBoolean(it) } }
            o.optJSONObject("auto")?.let { m -> m.keys().forEach { b.auto[key(it)] = m.getBoolean(it) } }
            o.optJSONObject("liveOk")?.let { m -> m.keys().forEach { b.liveOk[key(it)] = m.getBoolean(it) } }
            o.optJSONObject("legs")?.let { l ->
                b.legs = Legs(LocalDate.parse(l.getString("day")), l.getInt("strike"), LocalDate.parse(l.getString("expiry")),
                    contractOf(l.getJSONArray("ce")), contractOf(l.getJSONArray("pe")))
            }
            o.optJSONArray("range")?.let { b.range = it.getDouble(0) to it.getDouble(1); b.rangeDay = LocalDate.parse(it.getString(2)) }
            o.optJSONObject("decided")?.let { m -> m.keys().forEach { k -> val a = m.getJSONArray(k); b.decided[k] = (0 until a.length()).map { a.getString(it) }.toMutableSet() } }
            o.optJSONObject("watched")?.let { m -> m.keys().forEach { k -> b.watched[k] = m.getString(k) } }
            o.optJSONArray("positions")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    b.positions += Position(key(p.getString("arm")), p.getString("symbol"), p.getString("right"), p.getInt("qty"), p.getDouble("entry"),
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
                        priceRetry = p.optBoolean("priceRetry", false))
                }
            }
            o.optJSONObject("pending")?.let { m -> m.keys().forEach { k -> val p = m.getJSONObject(k)
                b.pending[key(k)] = Pending(key(k), p.getString("right"), LocalDateTime.parse(p.getString("bar")), LocalDateTime.parse(p.getString("expires")),
                    if (p.has("strike")) p.getInt("strike") else null, if (p.has("level")) p.getDouble("level") else null,
                    if (p.has("target")) p.getDouble("target") else null, if (p.has("near")) p.getBoolean("near") else null,
                    if (p.has("volSkip")) p.getBoolean("volSkip") else null, if (p.has("strong")) p.getBoolean("strong") else null,
                    if (p.has("live")) p.getBoolean("live") else null) } }
            o.optJSONObject("status")?.let { m -> m.keys().forEach { b.status[key(it)] = m.getString(it) } }
            o.optJSONObject("replays")?.let { m -> m.keys().forEach { b.replays[it] = m.getJSONObject(it) } }
            o.optJSONObject("liqReplays")?.let { m -> m.keys().forEach { k -> m.optJSONArray(k)?.let { b.liqReplays[k] = it } } }
            o.optJSONObject("upDays")?.let { m -> m.keys().forEach { b.upDays[it] = m.getBoolean(it) } }
            o.optJSONObject("since")?.let { m -> m.keys().forEach { b.since[it] = m.getString(it) } }
            o.optJSONArray("migrated")?.let { a -> for (i in 0 until a.length()) b.migrated += a.getString(i) }
            b.liqLots = LiquidityLots.of(if (o.has("liqLots")) o.optInt("liqLots") else null)
            b.liqLotsAsk = LiquidityLots.of(if (o.has("liqLotsAsk")) o.optInt("liqLotsAsk") else null)
            b.liqLotsChosen = o.optBoolean("liqLotsChosen", false)
            o.optJSONArray("liqParked")?.let { a -> for (i in 0 until a.length()) b.parked += a.getString(i) }
            o.optJSONArray("famParked")?.let { a -> for (i in 0 until a.length()) a.getString(i).takeIf { FamilyPark.covers(it) }?.let { b.famParked += it } }
        }.isSuccess
        if (!ok) {
            // Never overwrite what could not be read: set it aside and start clean, and say so.
            if (file.exists()) Vault.setAside(file)
            Notifier.post(app, 2016, Notifier.APPROVAL, "ORB arms could not be read",
                "Their saved state was set aside and both arms are disarmed. If an ORB position was open, check Trade → Paper now.", "trade")
            return Book().also { it.migrated += listOf(OFF_LOSERS, RetiredArms.MIGRATION, RetiredArms.UNRETIRE, LiquidityRules.MIDCP_JOIN,
                LiquidityLots.ONE_LOT_MIGRATION, ParkedArms.MIGRATION, FamilyPark.MIGRATION, ArmsCleanup.LIQ_MIGRATION); cache = it; hints(it) }
        }
        // A restore not yet disarmed (the app clears the flag once it has): the restored arms act as disarmed.
        if (com.optionslab.app.security.SecurePrefs.getBoolean(Backup.DISARM, false)) {
            b.armed.clear(); b.auto.clear(); b.liveOk.clear(); b.pending.clear()
        }
        cache = b
        hints(b)
        // A new book has nothing to change: it starts with every one-time change marked done (nothing is armed by itself).
        if (!existed) b.migrated += listOf(OFF_LOSERS, RetiredArms.MIGRATION, RetiredArms.UNRETIRE, LiquidityRules.MIDCP_JOIN, LiquidityLots.ONE_LOT_MIGRATION,
            ParkedArms.MIGRATION, FamilyPark.MIGRATION, ArmsCleanup.LIQ_MIGRATION)
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
        if (switchOffLosers(b) or retire(b, restoring) or unretire(b, restoring) or joinMidcp(b, restoring) or sizeLiquidity(b) or oneLot(b) or park(b) or
            parkFamily(b)) save(b)
        return b
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
     * Whether a Liquidity BANKNIFTY book is armed and cleared for Zerodha (Live with Boss's PIN), as of the last load or save:
     * read without the lock by [AutoExposure.liveYield] - the other traders then take no live BANKNIFTY entry.
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
        b.positions.filter { it.arm in com.optionslab.engine.orb.ArmPriority.BN_BOOKS && !it.open && it.live == live && it.exitTime != null && it.trail == null }
            .map { p -> com.optionslab.ira.LiquidityStopRule.Trade(epochMs(p.exitTime!!), p.exitTime.toLocalDate(), (p.grossPnl ?: 0.0) - p.charges) }

    private fun stopHints(b: Book) {
        val st = runCatching { LiquidityTest.state.value }.getOrDefault(com.optionslab.ira.LiquidityFreeze.State())
        val day = today()
        val live = com.optionslab.ira.LiquidityStopRule.check(bnClosed(b, true), day, st.stopResetAtMs)
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

    private fun hints(b: Book) {
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
            .any { b.armed[it.source] == true && b.liveOk[it.source] == true }
        armedHint = ALL_ARMS.any { b.armed[it.source] == true }
        armedLabelsHint = ALL_ARMS.filter { b.armed[it.source] == true }.map { it.label }
        holdingHint = b.positions.any { it.open }
        liveHint = b.positions.any { it.open && it.live }
        liveSymbolsHint = b.positions.filter { it.open && it.live }.mapNotNull { it.kite }.distinct()
        exposureHint = exposureOf(b)
        liquidityLotsHint = lotsOf(b)
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
        else "Liquidity 15+5 $index is back on, on paper" + (if (liveNow()) "; in Live its entries wait for your approval until you arm Liquidity again with your PIN." else ".")
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
                        p.trail?.let { put("trail", it) }; p.trailFrom?.let { put("trailFrom", it) }
                        if (p.priceRetry) put("priceRetry", true) })
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
        val text = o.toString()
        // Battery: an idle book (nothing armed, open or waiting) whose bytes are already on disk, as this process last
        // wrote them, is not encrypted and synced again ([com.optionslab.ira.OrbIdleSave]); anything else is, as before.
        val idle = b.armed.values.none { it } && b.positions.none { it.open } && b.pending.isEmpty()
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
        cache = b
        hints(b)
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
                TradeManagerHost.exitsOf("orb", m, listOfNotNull(p.target?.takeIf { lp.nextLevelTarget }?.let { "target on the index at %.2f".format(java.util.Locale.ENGLISH, it) }, check)
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
        // Boss's paper trail raised the stop to its own level (13 Oct review: a lab lock variant's stop is the lock's, not the trail's).
        trailStopped(p) -> LiquidityTrail.WHY
        lockedStop(p) -> "profit_lock"
        else -> "stop"
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
        val trades = closed.filter { it.trail == null }.map { p -> com.optionslab.ira.Vetting.Trade(p.exitTime!!.toLocalDate(), perLot(p)) }
        val trailed = closed.filter { it.trail != null }
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
        val liveArmed = LiquidityRules.BOOKS.any { it.source in bn && b.armed[it.source] == true && b.liveOk[it.source] == true }
        val stop = listOfNotNull(
            if (liveArmed || liquidityStopHint.trades > 0) com.optionslab.ira.LiquidityStopRule.line(liquidityStopHint, live = true) else null,
            com.optionslab.ira.LiquidityStopRule.line(liquidityStopPaperHint, live = false))
        val recent = x.fills.filter { it.bankNifty }.takeLast(8).reversed().map { com.optionslab.ira.FillQuality.line(it) }
        val trailWords = if (trailed.isEmpty()) "" else " Kept apart, ${LiquidityTrail.TRAILED} (${LiquidityTrail.NOTE}): ${trailed.size} trade" +
            "${if (trailed.size == 1) "" else "s"}, ${"%+,.0f".format(java.util.Locale.ENGLISH, trailed.sumOf { perLot(it) })} rupees a lot."
        return LiquidityTestView(x.note, x.frozen, com.optionslab.ira.Vetting.judge(com.optionslab.ira.LiquiditySplit.name("BANKNIFTY"), trades, day).text() + trailWords,
            lines, com.optionslab.ira.LiquidityGoLive.head(lines), stop, liquidityStopHint.stopped, com.optionslab.ira.FillQuality.summary(x.fills), recent,
            x.state.watched)
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

    suspend fun view(): View {
        // (Read apart from the arms' lock: the bot's stop is the strategies' own.)
        val why = runCatching { Strategies.stoppedWhy() }.getOrNull()
        // (The shadows' rows too: they never wait on a pass.)
        val shadows = runCatching { ShadowArms.rows() }.getOrDefault(emptyList())
        // Liquidity BANKNIFTY's paper test reads its fill records and the order review before the lock (never under it).
        val extra = if (com.optionslab.app.BuildConfig.GOLD) null else runCatching { liqExtra() }.getOrNull()
        return viewLocked(extra).copy(stopped = why?.let { DayStop.line(it) }, shadows = shadows)
    }

    private suspend fun viewLocked(extra: LiqExtra? = null): View = lock.withLock {
        val b = book()
        val day = today()
        val arms = ALL_ARMS.filter { it !in LiquidityRules.BOOKS }.map { a ->
            val open = b.positions.lastOrNull { it.arm == a.source && it.open }
            val (cToday, cMonth, orders) = chargesOf(b.positions, a.source, day)
            ArmView(a, b.armed[a.source] == true, b.auto[a.source] != false, b.status[a.source] ?: "", open, open?.let { marks[it.symbol] },
                b.pending[a.source], b.positions.filter { it.arm == a.source && it.day == day }, b.liveOk[a.source] == true,
                record = RetiredArms.of(a.source), chargesToday = cToday, chargesMonth = cMonth, ordersMonth = orders,
                bossParked = a.source in b.famParked && b.armed[a.source] != true)
        } + liquidityView(b, day, extra)
        val lastReplay = b.replays.entries.lastOrNull()
        View(arms, b.legs?.takeIf { it.day == day }, b.range?.takeIf { b.rangeDay == day }, forward(b), lastReplay?.value, lastReplay?.key)
    }

    /** Every closed paper trade the arms' book keeps (its last 2000 positions), oldest first. Reads only. */
    suspend fun closedPaper(): List<Position> = lock.withLock { book().positions.filter { !it.open && !it.live } }

    /** The arms' closed Zerodha positions (the self-review sets them against the backtest apart from paper; reads only). */
    suspend fun closedLive(): List<Position> = lock.withLock { book().positions.filter { !it.open && it.live } }

    /** Today's closed positions, paper and live (the findings bus: a stop or a target hit). Reads only. */
    suspend fun closedToday(): List<Position> {
        val d = now().toLocalDate()
        return lock.withLock { book().positions.filter { !it.open && it.exitTime?.toLocalDate() == d } }
    }

    /** Liquidity 15+5 as one row: armed when its books are, both books' trades, each book's state. */
    private fun liquidityView(b: Book, day: LocalDate, extra: LiqExtra? = null): ArmView {
        val books = LiquidityRules.BOOKS.map { it.source }
        val open = b.positions.lastOrNull { it.arm in books && it.open }
        val status = LiquidityRules.BOOKS.joinToString("  ") { a ->
            "${LiquidityRules.underlyingOf(a)} ${LiquidityRules.minutesOf(a)}-min: ${describe(b.status[a.source] ?: "")}" }
        return ArmView(LiquidityRules.ARM, books.any { b.armed[it] == true }, books.all { b.auto[it] != false }, status, open,
            open?.let { marks[it.symbol] }, books.firstNotNullOfOrNull { b.pending[it] }, b.positions.filter { it.arm in books && it.day == day },
            // A parked index's books are off and never cleared for Zerodha: they do not count against the switch's Live state.
            ParkedArms.armable(b.parked).all { b.liveOk[it.source] == true }, shadow = shadowOf(b), lots = lotsOf(b), lotsAsk = b.liqLotsAsk,
            lotSizes = lotSizes(), parked = b.parked.sorted()).let { v ->
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
     */
    suspend fun setLiquidityLots(lots: Int, by: String): String = lock.withLock {
        if (!LiquidityLots.valid(lots)) return@withLock "Liquidity 15+5 trades 1, 2 or 3 lots, not $lots."
        val b = book()
        val old = lotsOf(b)
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
     */
    suspend fun setArmed(source: String, on: Boolean, automatic: Boolean, pinConfirmed: Boolean = false): String = lock.withLock {
        val b = book()
        if (source == LiquidityRules.ARM.source) {
            // Both books follow the one switch, with the ORB's rules: the Paper / Live switch, the PIN for Live, automatic or approve.
            val live = liveNow()
            if (on && live && !pinConfirmed) return@withLock "The app is in Live: arm it with your PIN or fingerprint."
            // A parked index's books stay off when the switch comes on (9 Oct, Boss's OK): only "switch it back on" brings them back.
            for (a in if (on) ParkedArms.armable(b.parked) else LiquidityRules.BOOKS) {
                b.armed[a.source] = on; b.auto[a.source] = automatic; b.liveOk[a.source] = on && live && pinConfirmed
                if (!on) b.pending.remove(a.source)
            }
            // Boss himself switched it on again (in Live with his PIN, or on paper): no longer parked or paused by its review.
            if (on && (!live || pinConfirmed)) runCatching { SmartWorkers.ownerRearmed("liquidity") }
            save(b)
            val holding = b.positions.any { it.open && it.arm in LiquidityRules.BOOKS.map { a -> a.source } }
            return@withLock if (on) "${LiquidityRules.ARM.label} armed" + (if (live) " on ZERODHA (live), " else " on paper, ") +
                (if (automatic) "fully automatic" else "you approve each entry") + ": on the BANKNIFTY 15- and 5-minute, FINNIFTY 30- and 5-minute " +
                "and MIDCPNIFTY 15- and 5-minute charts, " +
                "when a close takes a liquidity pool that sits on a swing zone, it buys the ATM call (up) or put (down), ${LiquidityLots.words(lotsOf(b))}, with a stop " +
                // (The stop of the variant in force today for that account, review G2-6: 15% on its constants.)
                "${pts(runCatching { liqParams(today(), live).stopPct }.getOrDefault(LiquidityRules.PREMIUM_STOP) * 100)}% below the price paid, " +
                "and sells at the next liquidity level, when new liquidity forms, when the break fails, or at " +
                "15:10. Entries 09:20-14:00, one position per chart." +
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

    /** After a restore: both arms off, nothing waiting for approval. */
    suspend fun disarmAll() = lock.withLock {
        val b = book(); b.armed.clear(); b.auto.clear(); b.liveOk.clear(); b.pending.clear(); save(b)
        com.optionslab.app.ira.IraCoach.forgetParked()
    }

    /**
     * Reset paper: the arms' paper positions, their paper day (bars decided, status) and waiting paper approvals go,
     * and every arm not armed for Zerodha is switched off. Zerodha positions and live-armed arms are untouched.
     */
    suspend fun resetPaper() = lock.withLock {
        val b = book()
        b.positions.removeAll { !it.live }
        val live = b.positions.filter { it.open }.map { it.arm }.toSet() + b.liveOk.filterValues { it }.keys
        if (!liveNow()) b.pending.clear()
        for (k in b.armed.keys.toList()) if (k !in live) { b.armed.remove(k); b.auto.remove(k) }
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
        return lock.withLock { approveLiquidityLocked(source, pinConfirmed) }
    }

    private suspend fun approveLiquidityLocked(source: String, pinConfirmed: Boolean): String {
        val b = book()
        val key = (if (source == LiquidityRules.ARM.source) liquidityBook(b) else source) ?: return "Nothing is waiting for approval."
        val waiting = b.pending[key] ?: return "Nothing is waiting for approval."
        val live = ArmAccount.approval(armOf(waiting.arm), liveNow(), SmartWorkers.parkedToPaperNow("liquidity"), waiting.live)
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
        try {
            tickLocked(t, appLive)
        } finally {
            heroFed.clear()
        }
    }

    private suspend fun tickLocked(t: LocalDateTime, appLive: Boolean?) {
        lock.withLock {
            val b = book()
            runCatching { priceCheck(b, t) }
            runCatching { liquidityExits(b, t) }
            runCatching { liquidityNotices(b, t) }
            runCatching { heroKillCheck(b) }
            val anyArmed = ALL_ARMS.any { b.armed[it.source] == true }
            // The armed arms' indices on Zerodha's stream when it runs: a bar's last minute is then known at its close.
            followIndices(b)
            if (!anyArmed || !OrbRules.inWindow(t.toLocalTime())) { save(b); return@withLock }
            // Each index is read at most once on this pass, shared by the books on it (13 Oct audit fix): BANKNIFTY's candles here
            // for the ORB family (and Liquidity's BANKNIFTY books), FINNIFTY's and MIDCPNIFTY's when their books decide.
            val reads = IndexData.Reads<List<Upstox.Bar>>()
            val bars = runCatching { indexBars(t, reads) }.getOrNull()
            // The strategy lab (11 Oct): each strategy ran today, and on the account its new entries go to - one record per lab
            // key (Liquidity's books are one strategy), live when any of its armed arms enters live (review G2-2). Memory only.
            runCatching {
                val parked = SmartWorkers.parkedToPaperNow("liquidity")
                ArmAccount.byKey(ALL_ARMS.filter { b.armed[it.source] == true }, VariantRunner::lab) { ArmAccount.entry(it, appLive == true, parked) }
                    .forEach { (k, live) -> VariantRunner.saw(k, live) }
            }
            for (arm in ALL_ARMS) {
                if (b.armed[arm.source] != true) continue
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
    suspend fun priceCheckOnly(fast: Boolean = false, stream: Boolean = false) = lock.withLock {
        val b = book(); val t = now()
        // [fast]: an event-driven look ([FastPath], up to several a second). A look that changed only the best or lowest
        // premium seen (peak, low: re-read from the stream's ticks anyway) keeps the book in memory and is written with the
        // next pass that saves it - no Keystore write per tick. Anything else (an exit, a stop moved, a sale) is written now.
        // [stream] (round 2): every live position's contract has a fresh stream tick: its exits decide from the ticks and
        // Zerodha's orders and positions as last read ([Broker.ordersRecon]); only an order goes over REST.
        val before = if (fast) untracked(b) else null
        runCatching { priceCheck(b, t, stream = fast && stream, regular = !fast) }
        runCatching { liquidityExits(b, t) }
        runCatching { liquidityNotices(b, t) }
        if (before != null && before == untracked(b)) { cache = b; hints(b) } else save(b)
    }

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
        if (arm.hero) return heroCycle(b, t)
        b.pending[arm.source]?.let { pd -> if (t.isAfter(pd.expires)) { b.pending.remove(arm.source); lapsed(arm, pd) } else { watching(); return "awaiting_approval" } }
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
        val listed = Market.contracts().filter { it.underlying == OrbRules.UNDERLYING }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryOnOrAfter(day, listed) ?: return null
        val ce = Paper.contractFor(OrbRules.UNDERLYING, expiry, strike.toDouble(), Right.CE) ?: return null
        val pe = Paper.contractFor(OrbRules.UNDERLYING, expiry, strike.toDouble(), Right.PE) ?: return null
        return Legs(day, strike, expiry, ce, pe).also { b.legs = it }
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
        val ltp = q?.ltp ?: return noQuote(c, price)
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
        val entry = buyPaperEntry(arm.label, c, 1, q, ownerOf(arm), mayRetryPrice(b)) { nq ->
            val again = nq.ltp * 1.0005
            if (OrbRules.stopTrigger(again, pp.stopPoints) == null) "refused: premium %.2f is at or below %s, a %s-point stop has no level"
                .format(Locale.ENGLISH, again, pts(pp.stopPoints), pts(pp.stopPoints))
            else paperRecheck(c, 1, nq)
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
        val stopped = Strategies.stoppedToday()
        val liveOpen = b.positions.withIndex().filter { it.value.open && it.value.live }
        if (liveOpen.isNotEmpty()) runCatching { priceCheckLive(b, t, liveOpen, stopped, stream, regular) }
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
                val backstop = cur.day.isBefore(t.toLocalDate()) || !t.toLocalTime().isBefore(LocalTime.of(15, 15))
                // Book the actual closing fill (slippage and charges included) when there is one.
                val sq = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && it.strategy == "AUTO_SQUARE_OFF" && !it.timestamp.isBefore(cur.entryTime) }
                b.positions[i] = cur.copy(exit = sq?.price?.toDouble() ?: ltp ?: cur.entry, exitTime = sq?.timestamp ?: t,
                    why = if (backstop) "backstop_square_off" else "closed_by_you",
                    stopOrderId = null, charges = cur.charges + (sq?.charges?.toDouble() ?: 0.0))
                continue
            }
            if (ltp == null) continue                                               // no price at all: hold
            // The highest price since the entry (F2): every stream tick, else the 1-minute candles' highs - laddered arms and
            // Boss's paper give-back trail only.
            val high = if (ladderTarget(cur) != null || cur.trail != null) runCatching { Paper.highSince(c, cur.entryTime) }.getOrNull() else null
            val (laddered, locked) = ladder(cur, ltp, high)
            // The trail's give-back from the best price BEFORE this look (a new best counts from the next look, as the ladder).
            val trailHit = LiquidityTrail.exits(cur.entry, cur.peak ?: cur.entry, ltp, cur.lot, cur.trail, cur.trailFrom)
            val seen = heldRange(trailPeak(laddered, ltp, high), ltp)
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
                    ?.let { if (it == "stop" && trailStopped(cur)) LiquidityTrail.WHY else it }
                    ?: LiquidityTrail.WHY.takeIf { trailHit }
                // The lock rests as the paper stop (moved up below), which the paper book fills; this sells at market only
                // when that stop is not there or has not filled (no fresh price in the book's own pass).
                locked -> "profit_lock"
                // The arm's own target (ORB Sweep +80), reached on the price now or on a high since the entry (F2).
                targetHit(cur, ltp, high) -> "target"
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

    /** Take the resting stop out of the book first, then sell; if the stop filled meanwhile, that is the exit. */
    private suspend fun exit(p: Position, c: Paper.Contract, why: String): Position {
        runCatching { OrderTiming.decided() }
        p.stopOrderId?.let { id ->
            Paper.cancel(id, "exit:$why")
            val so = Paper.state.orders.firstOrNull { it.orderId == id }
            if (so?.status == "complete") return p.copy(exit = so.averagePrice?.toDouble(), exitTime = so.updateTimestamp,
                why = stopWhy(p), charges = p.charges + chargesOf(id))
        }
        val sell = Paper.place(c, "SELL", p.qty / c.lotSize.coerceAtLeast(1), "MARKET", "MIS", null, null)
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
        val r = runCatching { Paper.place(c, "SELL", p.qty / c.lotSize.coerceAtLeast(1), "SL-M", "MIS", null, trigger) }.getOrNull()
        val id = r?.takeIf { it.ok }?.orderId?.also { Strategies.tagOwner("paper:$it", "${armOf(p.arm).label} · stop") }
        return if (id == null) p.copy(stopOrderId = null) else p.copy(stopOrderId = id, stopTrigger = trigger)
    }

    /** Where a laddered position's stop belongs now: the higher of its trigger and the lock its best price earned (F3). */
    private fun lockOrStop(p: Position, tick: Double = OrbRules.TICK): Double? = LiquidityTrail.restingStop(p.entry,
        ProfitLock.restingStop(p.entry, p.stopTrigger, ladderTarget(p), p.peak ?: p.entry, ProfitLock.roundTripPerUnit(p.entry, p.qty), tick),
        p.peak, p.lot, p.trail, tick, p.trailFrom)

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
        if (target == null && p.trail == null) return p
        val id = p.stopOrderId ?: return p
        // The higher of the ladder's lock and Boss's paper give-back trail ([LiquidityTrail.raise]: up only, below the price).
        val to = listOfNotNull(
            target?.let { ProfitLock.raise(p.entry, it, p.peak ?: p.entry, p.stopTrigger, ltp, ProfitLock.roundTripPerUnit(p.entry, p.qty)) },
            LiquidityTrail.raise(p.entry, p.peak, p.lot, p.trail, p.stopTrigger, ltp, activateRs = p.trailFrom),
        ).maxOrNull() ?: return p
        val r = runCatching { Paper.modify(id, null, null, to) }
        if (r.getOrNull()?.ok == true) {
            // 18 Oct: a paper-only arm's live trade moves its Zerodha stop to the same lock ([ArmLive]; memory only).
            if (runCatching { armOf(p.arm).paperOnly }.getOrDefault(false)) ArmLive.level(mirrorRef(p), to)
            return p.copy(stopTrigger = to)
        }
        val why = r.getOrNull()?.message ?: r.exceptionOrNull()?.javaClass?.simpleName ?: "an error"
        com.optionslab.app.work.Alerts.post("${armOf(p.arm).label}: the ${if (target == null) "₹500 trail" else "profit lock"} could not move the paper stop up to " +
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
        if (p.trail == null) return p
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
        return minutesOf(heroFed[key] ?: heroRead(key, t), t)
    }

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
        val master = runCatching { Market.contracts() }.getOrNull()?.filter { it.underlying == HeroRules.UNDERLYING }
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
        val c = Paper.contractFor(HeroRules.UNDERLYING, day, pick.strike, right) ?: return kept("no_contract", pick)
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
        // Never a market order on these strikes: a LIMIT BUY on the PAPER account. Not filled at once, it is cancelled
        // (nothing held), and the arm may decide again on a later bar until 14:45.
        val buy = Paper.place(c, "BUY", lots, "LIMIT", "MIS", limit, null, known = q, auto = HeroRules.OWNER)
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
            val sell = Paper.place(c, "SELL", p.qty / c.lotSize.coerceAtLeast(1), "LIMIT", "MIS", HeroRules.exitLimit(bid), null, known = q)
            val fill = filledOrCancelled(sell)
            if (fill != null) {
                sell.orderId?.let { Strategies.tagOwner("paper:$it", "${HeroRules.OWNER} · $why") }
                Notifier.orderFilled(app, "SELL", fill.quantity, fill.symbol, fill.price, "Paper", "${HeroRules.OWNER} · exit", sell.orderId)
                return p.copy(exit = fill.price, exitTime = now(), why = why, charges = p.charges + chargesOf(sell.orderId))
            }
        }
        // (Not sold at all: the next pass tries again with a fresh look, so this one is not kept.)
        return exit(p, c, why).let { if (it.open) p0 else it }
    }

    /** One look at the option's book for the Hero trade's log ([Position.seen]); without depth, the last price alone. */
    private fun heroSeen(what: String, q: com.optionslab.engine.sandbox.Quote?, fallback: Double?): HeroRules.Seen =
        HeroRules.Seen(what, now().toLocalTime().withNano(0), q?.ltp?.takeIf { it > 0 } ?: fallback, q?.bid?.takeIf { it > 0 },
            q?.ask?.takeIf { it > 0 }, q?.bidQty?.takeIf { it > 0 }, q?.askQty?.takeIf { it > 0 })

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
        val bars = heroStopBars?.takeIf { it.first == c.feedKey && it.second == minute }?.third
            ?: runCatching { heroMinutes(c.feedKey, t) }.getOrNull()?.also { heroStopBars = Triple(c.feedKey, minute, it) }
            ?: emptyMap()
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
        val sell = Paper.place(c, "SELL", step.lots, "LIMIT", "MIS", limit, null, known = q)
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
    private suspend fun placeStop(label: String, sym: String, qty: Int, lot: Int, tick: Double, fill: Double, known: Collection<String>,
                                  liquidity: Boolean = false, at: Double? = null, points: Double = OrbRules.STOP_POINTS,
                                  pct: Double = LiquidityRules.PREMIUM_STOP): Pair<String?, Double?> {
        // The ORB's stop is 40 points ([points]) below the fill; Liquidity 15+5's is 15% ([pct]) below it; [at]: a given trigger (the lock, F3).
        val trigger = (at ?: if (liquidity) LiquidityRules.stopTrigger(fill, pct) else OrbRules.stopTrigger(fill, points))
            ?.let { com.optionslab.engine.Kite.onTick(it, tick, com.optionslab.engine.Kite.Side.SELL) }
            ?: return null to null
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, qty, lot, "MIS", "SL",
            stopLimit(trigger, tick), tick, "NFO", "iraorb", triggerPrice = trigger)
        val id = try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
            if (Broker.definite(e)) null else runCatching { Broker.findRecentRetrying(o, known) }.getOrNull()
        }
        if (id != null) Strategies.tagOwner("kite:$id", "$label · stop")
        else com.optionslab.app.work.Alerts.error("$label: the ${if (at != null) "%.2f".format(Locale.ENGLISH, trigger) else if (liquidity) "${pts(pct * 100)}%" else "−${pts(points)}"} " +
            "stop could not be placed at Zerodha; the app watches it instead.", "ORB live")
        return id to trigger
    }

    /**
     * A live entry: MARKET BUY 1 lot MIS at Zerodha (Liquidity 15+5: its [lots]), then a resting SL SELL 40 below the
     * fill. Only reached after the owner approved with the PIN (see [approve]).
     */
    private suspend fun enterLive(b: Book, arm: Arm, c: Paper.Contract, signalBar: LocalDateTime, liquidity: LiquidityRules.Signal? = null,
                                  near: Boolean? = null, volSkip: Boolean? = null, strong: Boolean? = null, lots: Int = 1): String {
        runCatching { OrderTiming.decided() }
        // A paper-only arm (the Hero arm among them) never reaches Zerodha, whatever called this.
        liveRefusal(arm.source)?.let { return it }
        val s = AppSettings.load()
        if (s.guardKill) return "refused: the kill switch is on"
        // Liquidity's size over the Bot settings' max lots: said by name, never sent smaller (the guard's own checks follow).
        LiquidityLots.liveRefusal(lots, s.guardMaxLots)?.let { return it }
        // A phone that failed the security check never sends a real order on its own.
        val findings = runCatching { com.optionslab.app.security.Integrity.reportWithin(app, 60_000) }.getOrDefault(emptyList())
        if (com.optionslab.app.security.Integrity.compromised(findings)) return "refused: this phone failed the security check; no live order sent"
        if (!Broker.loggedIn) return "refused: not logged in to Zerodha today"
        val ins = (Broker.cachedInstruments() ?: runCatching { Broker.instruments() }.getOrNull())?.firstOrNull {
            it.name == c.underlying && it.expiry == c.expiry && it.right == c.right && kotlin.math.abs(it.strike - c.strike) < 1e-6
        } ?: return "refused: ${c.symbol} is not listed on Zerodha"
        val sym = ins.tradingSymbol
        val key = "NFO:$sym"
        val last = runCatching { Broker.quotes(listOf(key))[key]?.last }.getOrNull()?.takeIf { it > 0 } ?: return "refused: no quote"
        // The live account's parameters today (S1: a variant only after Boss's PIN, from the session after): its stop.
        val today0 = now().toLocalDate()
        val lp = paramsOf(arm, today0, live = true)
        val lq = liqParamsOf(arm.source, today0, live = true)
        if (liquidity == null && OrbRules.stopTrigger(last, lp.stopPoints) == null) return "refused: premium %.2f is at or below %s, a %s-point stop has no level"
            .format(Locale.ENGLISH, last, pts(lp.stopPoints), pts(lp.stopPoints))
        if (Strategies.stoppedToday()) return "stopped_for_today"
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.BUY, ins.lotSize * lots.coerceAtLeast(1), ins.lotSize, "MIS", "MARKET", null,
            ins.tickSize, "NFO", "iraorb")
        // Positions, funds and orders: the kept margins when fresh and roomy for this buy, else read now (in parallel).
        val acct = Broker.accountNow(needed = last * o.quantity)
        val refusals = Guard.check(Guard.liveOrder(o).copy(price = last), acct)
        if (refusals.isNotEmpty()) return "guard_refused: " + refusals.joinToString(" ")
        val why = com.optionslab.engine.Kite.refusals(o, s.limits(), Broker.sentToday(), false, refPrice = last)
        if (why.isNotEmpty()) return "refused: " + why.joinToString("; ")
        val known = knownKite(b)
        val id: String? = try {
            // The arm's key as its entry check asked the portfolio guard (the door refuses only this arm's blocked entry).
            // [last]: the price this entry decided on (its slippage is measured from it once it fills; nothing else changes).
            Broker.placeOrder(o, arm = if (liquidity != null) FlowShadow.liquidityKey(c.underlying) else arm.source, intended = last)
        } catch (e: Broker.NotLoggedIn) {
            return "refused: not logged in to Zerodha today"
        } catch (e: Exception) {
            if (Broker.definite(e)) return "order_refused: " + (e.message ?: "Zerodha refused the order")
            // The answer was lost, not necessarily the order: look (the book can trail the POST) before calling it unsent.
            try { Broker.findRecentRetrying(o, known) ?: return "order_refused: ${e.message}; no matching order found at Zerodha" }
            catch (_: Exception) { null }                                          // the order book could not be read either
        }
        id?.let { Strategies.tagOwner("kite:$it", "${arm.label} · entry") }
        var f = id?.let { runCatching { Broker.awaitOrder(it, 15_000) }.getOrNull() }
        if (id != null && f?.status !in DONE) {
            // Not finished in 15 s: the unfilled rest is cancelled so it can never fill later untracked; only what filled is booked.
            runCatching { Broker.cancel(id) }
            f = runCatching { Broker.orderState(id) }.getOrNull()
        }
        if (f == null || f.status !in DONE) {
            // Zerodha has not said how it ended: held as unconfirmed (so the arm buys nothing more) until the books settle it.
            b.positions += stamped(Position(arm.source, c.symbol, c.right.name, o.quantity, last, now(), signalBar, id, null, null,
                live = true, kite = sym, unconfirmed = true, level = liquidity?.level, target = liquidity?.target, ladder = ladderOn(arm, today0, true),
                near = near, volSkip = volSkip, strong = strong, lot = ins.lotSize.takeIf { liquidity != null && it > 0 }))
            runCatching { save(b) }
            com.optionslab.app.work.Alerts.error("${arm.label}: Zerodha did not confirm the buy of $sym. It is treated as held (no stop yet) " +
                "until the order book shows what filled.", "ORB live")
            return "entered_unconfirmed"
        }
        if (f.filled <= 0) return "order_refused: Zerodha ${f.status.lowercase()}" + (f.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        val fill = f.avgPrice.takeIf { it > 0 } ?: last
        Notifier.orderFilled(app, "BUY", f.filled, sym, fill, "Live", "${arm.label} · entry", f.orderId)
        val (stopId, trigger) = placeStop(arm.label, sym, f.filled, ins.lotSize, ins.tickSize, fill, known + listOfNotNull(id), liquidity = liquidity != null,
            points = lp.stopPoints, pct = lq.stopPct)
        b.positions += stamped(Position(arm.source, c.symbol, c.right.name, f.filled, fill, now(), signalBar, id, stopId, trigger,
            charges = kiteCharge("BUY", fill, f.filled), live = true, kite = sym, level = liquidity?.level, target = liquidity?.target,
            ladder = ladderOn(arm, today0, true), near = near, volSkip = volSkip, strong = strong,
            lot = ins.lotSize.takeIf { liquidity != null && it > 0 }))
        runCatching { save(b) }   // a live position is written down at once, not at the end of the pass
        marks[c.symbol] = fill
        return "entered_live"
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
        val oid = p.entryOrderId ?: run {
            // The order id itself was lost: the entry is found in today's book by its symbol, side, tag and time.
            val row = runCatching { Broker.latestTagged(sym, "BUY", "iraorb", p.entryTime, known) }.getOrElse { return p }
                ?: run {
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
            com.optionslab.app.work.Alerts.post("$label: the unconfirmed buy of $sym did not fill (${st.status.lowercase()}); nothing is held.",
                com.optionslab.app.work.Alerts.Kind.INFO, "ORB live")
            return null
        }
        val fill = st.avgPrice.takeIf { it > 0 } ?: p.entry
        Notifier.orderFilled(app, "BUY", st.filled, sym, fill, "Live", label, oid)
        val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull()
        val liq = armOf(p.arm).liquidity
        val pts0 = paramsOf(p).stopPoints; val pct0 = liqParams(p).stopPct
        val (stopId, trigger) = if (spec != null) placeStop(label, sym, st.filled, spec.lotSize, spec.tickSize, fill, known + oid, liquidity = liq,
                points = pts0, pct = pct0)
            else null to (if (liq) LiquidityRules.stopTrigger(fill, pct0) else OrbRules.stopTrigger(fill, pts0))
        return p.copy(qty = st.filled, entry = fill, entryOrderId = oid, stopOrderId = stopId, stopTrigger = trigger,
            charges = kiteCharge("BUY", fill, st.filled), unconfirmed = false)
    }

    /** Stop, target, 15:10 and the operator stop for positions held at Zerodha. */
    private suspend fun priceCheckLive(b: Book, t: LocalDateTime, open: List<IndexedValue<Position>>, stopped: Boolean,
                                       stream: Boolean = false, regular: Boolean = true) {
        if (!Broker.loggedIn) return
        // [stream]: Zerodha's orders and positions as last read (under 20 s, nothing sent since, the stream live: else read
        // now), and each price from the stream's fresh tick; a contract without one is quoted from Zerodha as before.
        val orders = (if (stream) Broker.ordersRecon() else Broker.orders()).associateBy { it.id }
        val net = (if (stream) Broker.positionsRecon() else Broker.positionBook()).net
        val keys = open.mapNotNull { it.value.kite }.map { "NFO:$it" }.distinct()
        val q = runCatching { liveQuotes(keys, stream) }.getOrDefault(emptyMap())
        val drop = ArrayList<Int>()
        for ((i, p) in open) {
            val sym = p.kite ?: continue
            if (p.unconfirmed) {
                val settled = runCatching { settleEntry(b, p, sym) }.getOrDefault(p)
                if (settled == null) drop += i else b.positions[i] = settled
                continue
            }
            val so = p.stopOrderId?.let { orders[it] }
            if (so != null && so.status == "COMPLETE") {
                val px = so.avg.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                // The stop the profit lock had moved up filled at Zerodha: that is the lock's exit (F1).
                val why = if (lockedStop(p)) "profit_lock" else "stop"
                Notifier.orderFilled(app, "SELL", so.filled, sym, px, "Live", "${armOf(p.arm).label} · ${why.replace('_', ' ')}", p.stopOrderId)
                b.positions[i] = p.copy(exit = px, exitTime = t, why = why, stopOrderId = null, charges = p.charges + kiteCharge("SELL", px, p.qty))
                continue
            }
            // A stop taken out at Zerodha (refused, or cancelled by hand or by Zerodha) leaves the position without one: say so.
            if (so != null && so.status in setOf("REJECTED", "CANCELLED")) com.optionslab.app.work.Alerts.error(
                "${armOf(p.arm).label}: the stop at Zerodha was ${so.status.lowercase()} (${so.message.ifBlank { "no reason given" }}). " +
                    "The app now watches " + (p.stopTrigger?.let { "the stop at %.2f".format(Locale.ENGLISH, it) } ?: "the stop") + " itself.", "ORB live")
            val cur = if (so != null && (so.status == "CANCELLED" || so.status == "REJECTED")) p.copy(stopOrderId = null).also { b.positions[i] = it } else p
            val held = net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.qty }
            val ltp = q["NFO:$sym"]?.last?.takeIf { it > 0 }
            ltp?.let { mark(p.symbol, it, t) }
            // Gone at Zerodha without the arm selling it: Zerodha's MIS square-off, or closed by hand.
            if (held <= 0) {
                // Its stop must be confirmed out first: left resting, it would sell into a short.
                val sid = cur.stopOrderId
                if (sid != null) {
                    runCatching { Broker.cancel(sid) }
                    if (runCatching { Broker.orderState(sid)?.status }.getOrNull() !in DONE) continue
                }
                val backstop = cur.day.isBefore(t.toLocalDate()) || !t.toLocalTime().isBefore(LocalTime.of(15, 15))
                val px = ltp ?: cur.entry
                b.positions[i] = cur.copy(exit = px, exitTime = t, why = if (backstop) "backstop_square_off" else "closed_by_you",
                    stopOrderId = null, charges = cur.charges + kiteCharge("SELL", px, cur.qty))
                continue
            }
            if (ltp == null) continue
            // The price ran through the stop's limit without it filling: sell at market instead.
            val tick = runCatching { Broker.spec("NFO", sym).tickSize }.getOrDefault(0.05)
            // "Keep a stop-loss order at the exchange" (Settings → Zerodha, off unless chosen): a position whose stop is not
            // resting at Zerodha (it could not be placed, or was refused or cancelled) gets it placed again at the arm's own
            // level on the regular pass - never on a fast look, at most once a minute - while the price is still above it.
            if (regular && cur.stopOrderId == null && held > 0) {
                val rested = runCatching { restExchangeStop(b, cur, sym, held, ltp, tick) }.getOrNull()
                if (rested != null) { b.positions[i] = rested; continue }
            }
            val runThrough = cur.stopTrigger?.let { ltp < stopLimit(it, tick) } == true
            // The highest price since the entry (F2): every stream tick, else the 1-minute candles' highs - laddered arms only.
            val leg = b.legs?.let { l -> listOf(l.ce, l.pe) }?.firstOrNull { it.symbol == cur.symbol }
            val high = if (ladderTarget(cur) != null && leg != null) runCatching { Paper.highSince(leg, cur.entryTime) }.getOrNull() else null
            // The lock the best price BEFORE this look earned, on the tick: when the stop already rests there, Zerodha sells it.
            val lockNow = ladderTarget(cur)?.let { ProfitLock.level(cur.entry, it, cur.peak ?: cur.entry, ProfitLock.roundTripPerUnit(cur.entry, cur.qty)) }
                ?.let { ProfitLock.onTick(it, tick) }
            val lockResting = lockNow != null && cur.stopOrderId != null && (cur.stopTrigger ?: Double.NEGATIVE_INFINITY) >= lockNow - 1e-9
            val (laddered, locked) = ladder(cur, ltp, high)
            val seen = heldRange(laddered, ltp)
            if (seen !== cur) b.positions[i] = seen
            val why = when {
                stopped -> "operator_stop"
                !t.toLocalTime().isBefore(OrbRules.SQUARE_OFF) -> "session_end"
                // Liquidity 15+5: its 15% stop rests at Zerodha; the app sells only if that is gone or the price ran through it.
                // Under a lock variant, an earned lock not resting there (a refused modify, no stop order) sells as the ORB arms'
                // (review G2-4).
                armOf(cur.arm).liquidity -> LiquidityRules.appSale(stopResting = cur.stopOrderId != null, runThrough = runThrough,
                    atStop = cur.stopTrigger?.let { ltp <= it } == true, locked = locked, lockResting = lockResting)
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
            val (done, rest) = exitLive(b, seen, sym, why)
            b.positions[i] = done
            rest?.let { b.positions += it }
        }
        drop.sortedDescending().forEach { b.positions.removeAt(it) }
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
            if (tk.last > 0) out[k] = Broker.Quote(tk.last, tk.bid, tk.ask, tk.open, tk.oi, tk.volume)
        }
        val rest = keys.filter { it !in out }
        if (rest.isNotEmpty()) out.putAll(Broker.stopQuotes(rest))
        return out
    }

    /** When each position's exchange stop was last placed again ([restExchangeStop]): at most once a minute each. */
    private val restoppedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * The exchange stop placed again for [p] (held [held] at Zerodha, price [ltp]) when the owner keeps one there
     * ([AppSettings.exchangeStops]): at the lock its best price earned when above its own trigger, else its trigger; only
     * while the price is above that level (a sell stop must rest below the price - at or under it the app's own check sells).
     * Null when nothing was placed (the setting off, too soon, no level, or Zerodha refused: the app keeps watching it).
     */
    private suspend fun restExchangeStop(b: Book, p: Position, sym: String, held: Int, ltp: Double, tick: Double): Position? {
        if (!runCatching { AppSettings.load().exchangeStops }.getOrDefault(false)) return null
        val key = "${p.arm}|$sym|${p.entryTime}"
        val now = System.currentTimeMillis()
        if (now - (restoppedAt[key] ?: 0L) < 60_000L) return null
        restoppedAt[key] = now
        val level = lockOrStop(p, tick) ?: p.stopTrigger ?: return null
        if (ltp <= level + tick) return null
        val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: return null
        val (id, trig) = placeStop(armOf(p.arm).label, sym, minOf(p.qty, held), spec.lotSize, spec.tickSize, p.entry, knownKite(b),
            liquidity = armOf(p.arm).liquidity, at = level)
        return if (id == null) null else p.copy(stopOrderId = id, stopTrigger = trig ?: level)
    }

    /**
     * F1 live: the profit lock MOVES the resting SL at Zerodha up to the lock the best price has earned - a modify of that
     * same order (trigger, and its limit under it), never a cancel and a new sell, never down, never to or above the price
     * now ([ProfitLock.raise]). Zerodha then sells at the lock by itself, the app running or not. A refused modify leaves
     * the old stop working, is said (throttled; Zerodha's own words, never a key or token) and is asked again next look.
     */
    private suspend fun raiseLiveStop(p: Position, sym: String, so: Broker.OrderRow?, ltp: Double, tick: Double): Position {
        val target = ladderTarget(p) ?: return p
        if (so == null || !so.working || so.id != p.stopOrderId) return p
        val to = ProfitLock.raise(p.entry, target, p.peak ?: p.entry, p.stopTrigger, ltp, ProfitLock.roundTripPerUnit(p.entry, p.qty), tick) ?: return p
        val r = runCatching {
            if (so.type == "SL-M") Broker.modify(so, so.qty, "SL-M", null, to) else Broker.modify(so, so.qty, "SL", stopLimit(to, tick), to)
        }
        if (r.isSuccess) return p.copy(stopTrigger = to)
        com.optionslab.app.work.Alerts.post("${armOf(p.arm).label}: Zerodha did not move the stop on $sym up to the profit lock " +
            "%.2f (%s). The stop stays at %s; it is tried again on the next check.".format(Locale.ENGLISH, to,
                r.exceptionOrNull()?.message?.take(160) ?: "an error", p.stopTrigger?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none"),
            com.optionslab.app.work.Alerts.Kind.ERROR, "ORB live", throttle = true)
        return p
    }

    /**
     * Take the resting stop out first, then MARKET SELL what is held; if the stop filled meanwhile, that is the exit.
     * Returns the position, and (when only part sold) the rest, still open, to be sold on the next pass.
     */
    private suspend fun exitLive(b: Book, p: Position, sym: String, why: String): Pair<Position, Position?> {
        runCatching { OrderTiming.decided() }
        val label = armOf(p.arm).label
        // The sell certainly did not go: a stop that was taken out is placed again so the position is never left unprotected.
        // F3: back at the lock its best price has earned when that is above its own trigger - never the -40 once a rung is
        // earned; if Zerodha refuses that (a sell stop must rest below the price), at its own trigger as before.
        suspend fun unsold(): Pair<Position, Position?> {
            if (p.stopOrderId == null) return p to null
            val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: return p.copy(stopOrderId = null) to null
            val liq = armOf(p.arm).liquidity
            val lock = lockOrStop(p, spec.tickSize)?.takeIf { l -> ladderTarget(p) != null && l > (p.stopTrigger ?: Double.NEGATIVE_INFINITY) + 1e-9 }
            if (lock != null) {
                val (id, trig) = placeStop(label, sym, p.qty, spec.lotSize, spec.tickSize, p.entry, knownKite(b), liquidity = liq, at = lock)
                if (id != null) return p.copy(stopOrderId = id, stopTrigger = trig ?: lock) to null
            }
            val (id, trig) = placeStop(label, sym, p.qty, spec.lotSize, spec.tickSize, p.entry, knownKite(b), liquidity = liq, at = p.stopTrigger)
            return p.copy(stopOrderId = id, stopTrigger = trig ?: p.stopTrigger) to null
        }
        p.stopOrderId?.let { id ->
            // The resting exchange stop comes out FIRST (timed for the order speed card); one that already filled is the exit.
            val c0 = System.currentTimeMillis()
            runCatching { Broker.cancel(id) }
            val st = runCatching { Broker.orderState(id) }.getOrNull()
            runCatching { OrderTiming.stopCancel(System.currentTimeMillis() - c0) }
            if (st?.status == "COMPLETE") {
                val px = st.avgPrice.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                return p.copy(exit = px, exitTime = now(), why = if (lockedStop(p)) "profit_lock" else "stop", stopOrderId = null,
                    charges = p.charges + kiteCharge("SELL", px, p.qty)) to null
            }
            // Only sell once the stop is known to be out: a stop still working plus a market sell could both fill.
            if (st == null || st.status !in setOf("CANCELLED", "REJECTED")) return p to null
        }
        // What is held and what is already on its way out, read again after the stop came out. Only this position's own
        // orders are ever cancelled here: another sell resting on the symbol (the other arm's stop, a protection, the
        // owner's own order, an earlier pass's sell whose reply was lost) is subtracted instead, so both filling never
        // turns the long into a short.
        // 20 Oct, enhancement round 2: read and sent in the contract's one exit turn, from a read that shows every exit sent from
        // here ([ExitCoordinator]): another sender's exit sent a moment ago is subtracted, never sold again.
        val id = ExitCoordinator.turn("NFO", sym, label) {
            val book = runCatching { ExitCoordinator.orders("NFO", sym, label) }.getOrNull() ?: return p.copy(stopOrderId = null) to null
            val working = book.filter { it.working && it.symbol == sym && it.side == "SELL" && it.product == "MIS" }
            // 21 Oct, enhancement round 3 (idea 1): held, checked against that read's fills ([ExitCoordinator.held]).
            val still = runCatching { ExitCoordinator.held("NFO", sym, "MIS", label, book) }.getOrNull() ?: return p.copy(stopOrderId = null) to null
            val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: return p.copy(stopOrderId = null) to null
            val qty = com.optionslab.engine.risk.ExitQty.sendable(still, working.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) },
                p.qty, spec.lotSize)
            if (qty <= 0) return p.copy(stopOrderId = null) to null                   // gone, or covered by what is working: next pass
            val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, qty, spec.lotSize, "MIS", "MARKET", null, spec.tickSize, "NFO", "iraorb")
            val bad = com.optionslab.engine.Kite.refusals(o, AppSettings.load().limits(), Broker.sentToday(), false, exit = true)
            if (bad.isNotEmpty()) {
                com.optionslab.app.work.Alerts.error("$label: the Zerodha exit was not sent (${bad.joinToString("; ")}). Close $sym in Trade.", "ORB live")
                return unsold()
            }
            try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
                val definite = Broker.definite(e)
                val found = if (definite) null else runCatching { Broker.findRecentRetrying(o, knownKite(b) + working.map { it.id }) }.getOrNull()
                found ?: run {
                    com.optionslab.app.work.Alerts.error("$label: the Zerodha exit failed (${e.message}); retrying on the next pass.", "ORB live")
                    // Refused outright: nothing went, so the stop goes back. A lost reply with no order found may still have gone.
                    return if (definite) unsold() else p.copy(stopOrderId = null) to null
                }
            }
        }
        Strategies.tagOwner("kite:$id", "$label · $why")
        var f = runCatching { Broker.awaitOrder(id, 15_000) }.getOrNull()
        if (f?.status !in DONE) {
            // Not finished in 15 s: the rest is cancelled (and tried afresh next pass); only what filled is booked.
            runCatching { Broker.cancel(id) }
            f = runCatching { Broker.orderState(id) }.getOrNull() ?: f
        }
        // Known to have filled nothing and to be finished (cancelled or rejected): the stop goes back.
        if (f == null || f.filled <= 0) return if (f != null && f.status in DONE) unsold() else p.copy(stopOrderId = null) to null
        val px = f.avgPrice.takeIf { it > 0 } ?: p.entry
        Notifier.orderFilled(app, "SELL", f.filled, sym, px, "Live", label, f.orderId)
        if (f.filled >= p.qty) return p.copy(stopOrderId = null, exit = px, exitTime = now(), why = why, charges = p.charges + kiteCharge("SELL", px, f.filled)) to null
        // Only part sold: that part is booked as closed, the rest stays open (no stop: the app watches it) and is sold next pass.
        val share = f.filled.toDouble() / p.qty
        return p.copy(qty = f.filled, stopOrderId = null, exit = px, exitTime = now(), why = why,
            charges = p.charges * share + kiteCharge("SELL", px, f.filled)) to
            p.copy(qty = p.qty - f.filled, stopOrderId = null, charges = p.charges * (1 - share))
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
        if (p.retried) "refused: no quote for ${c.symbol} after 2 reads" else "refused: no quote for ${c.symbol} (not read again: a position of these arms is open)"

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
                                      mayRetry: Boolean, recheck: suspend (com.optionslab.engine.sandbox.Quote) -> String?): EntryBuy {
        val sent1 = System.currentTimeMillis()
        val first = Paper.place(c, "BUY", lots, "MARKET", "MIS", null, null, known = known, auto = auto)
        filledOrCancelled(first)?.let { return EntryBuy(first, it, 1, sent1) }
        val id = first.orderId
        val cancelled = first.ok && id != null && Paper.state.orders.firstOrNull { it.orderId == id }?.status == "cancelled"
        // 22 Oct, review: never a second order while this book holds a position (its stop looks wait on the same lock).
        if (!cancelled || !mayRetry) return EntryBuy(first, null, 1, sent1)
        val q = retryQuote(c)
        // The checks made before the first order, made again at the new price (a check that fails to run refuses).
        val refusal = q?.let { nq -> runCatching { recheck(nq) }.getOrElse { e -> "refused: the checks could not be made again (${e.message})" } }
        val sent2 = System.currentTimeMillis()
        val again = if (q != null && refusal == null) Paper.place(c, "BUY", lots, "MARKET", "MIS", null, null, known = q, auto = auto) else null
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
            liquidityMinutes(t, und, reads = reads)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Its own index's candles could not be read on this pass: only the books on that index stop (any other error as before).
            return reads?.let { IndexData.gate(und, it) } ?: throw e
        }
        val bars = liquidityBars(ones, tf, t)
        if (bars.size < 2 * LiquidityRules.SWING_LOOKBACK + 2) return "liquidity_history_loading"
        val last = bars.last()
        val day = t.toLocalDate()
        if (last.start.toLocalDate() != day) return "no_decision_bar"
        if (!b.decided.getOrPut("${arm.source}|$day") { HashSet() }.add(last.start.toString())) return "no_decision_bar"
        // Each decision bar's verdict (and a break's level, target and close) is kept for Jarvis's words ([noteLiquidity]):
        // returned exactly as before; nothing reads it back to decide.
        fun kept(verdict: String, sig: LiquidityRules.Signal? = null): String {
            runCatching { noteLiquidity(t, arm.source, verdict, last.start, sig, last.close) }
            return verdict
        }
        if (!LiquidityRules.mayEnterAt(last.start.plusMinutes(tf.toLong()))) return kept("liquidity_outside_entry_hours")
        val s = LiquidityRules.signal(bars, LiquidityRules.zones(bars)) ?: return kept("no_liquidity_break")
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
        val strong = runCatching { strongOf(und, s, last.close, strike, t) }.getOrNull()
        // The findings bus (10 Oct): the sweep, for the other workers (a finding only; nothing acts on it by itself).
        SmartWorkers.found(LiquidityRules.ARM.label, com.optionslab.ira.Findings.Kind.SWEEP, und, s.side, s.level, 70,
            "sweep taken at ${"%,.0f".format(java.util.Locale.ENGLISH, s.level)} (${tf}-minute pool)")
        // Self-review (10 Oct): parked to paper by its own review, its entries go to the paper account until Boss re-arms it
        // in Live with his PIN ([SmartWorkers.ownerRearmed]); open live positions keep their exits. Memory only under the lock
        // (review G2-5: the parks were read off it; not read yet counts as parked). The approval decides it again the same way.
        val live = ArmAccount.entry(arm, appLive ?: liveNow(), SmartWorkers.parkedToPaperNow("liquidity"))
        // As the ORB: automatic unless the owner chose approvals, or it was armed in Paper and now finds the app in Live.
        if (b.auto[arm.source] == false || (live && b.liveOk[arm.source] != true)) {
            val right = if (s.side > 0) "CE" else "PE"
            val expires = last.start.plusMinutes(2L * tf)                    // until the next bar completes
            // The order flow at the signal, logged while it waits (decided at the approval, if Boss approves).
            FlowGate.outcome(FlowGate.gate(FlowShadow.liquidityKey(und), und, s.side, live, last.start.toString(), arm.source, decide = false), "awaiting_approval")
            b.pending[arm.source] = Pending(arm.source, right, last.start, expires, strike, s.level, s.target, near, volSkip, strong, live = live)
            Notifier.post(app, 6960 + ALL_ARMS.indexOf(arm), Notifier.APPROVAL, "${LiquidityRules.ARM.label}: approve BUY $und $strike $right",
                "The $und ${tf}-minute ${hhmm(last.start)} bar took a liquidity pool ${if (s.side > 0) "above" else "below"}. " +
                    (if (live) "LIVE on Zerodha, ${LiquidityLots.words(lotsOf(b))}: approve it on Home in the app" else "Paper account, ${LiquidityLots.words(lotsOf(b))}") +
                    ". Approve by ${hhmm(expires)} or it lapses.", "almanac", approve = "orb")
            return kept("awaiting_approval", s)
        }
        return kept(enterLiquidity(b, arm, s, last.start, strike, live, near, volSkip, strong), s)
    }

    /** Candidate (f)'s flag for signal [s] on [und] closing at [close], with the option bought at [strike] ([LiquidityShadow.strongMomentum]). */
    private suspend fun strongOf(und: String, s: LiquidityRules.Signal, close: Double, strike: Int, t: LocalDateTime): Boolean? {
        val expiry = OrbRules.expiryAfter(t.toLocalDate(), Market.contracts().filter { it.underlying == und }.map { it.expiry }.distinct()) ?: return null
        val (r, o) = if (s.side > 0) Right.CE to Right.PE else Right.PE to Right.CE
        val leg = Paper.contractFor(und, expiry, strike.toDouble(), r) ?: return null
        val opp = Paper.contractFor(und, expiry, strike.toDouble(), o) ?: return null
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
        val raw = if (testing) testOptionBars?.invoke(key, t).orEmpty() else Net.intraday(key)
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
        val listed = Market.contracts().filter { it.underlying == und }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryAfter(day, listed) ?: return "no_contract"
        val c = Paper.contractFor(und, expiry, strike.toDouble(), right) ?: return "no_contract"
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
                        LiquidityTest.record(quality(com.optionslab.ira.FillQuality.Kind.ENTRY, System.currentTimeMillis(), arm, c.symbol, true, p.entry,
                            atSignal?.last, Paper.bookNow(c), barCloseMs, sentMs, p.qty))
                    }
                }
        }
        // 22 Oct: the option's price at the signal ([entryQuote]: one more read if the first finds none), handed to the fill.
        val price = entryQuote(c, mayRetryPrice(b))
        val q = price.quote
        val ltp = q?.ltp ?: return noQuote(c, price)
        if (Strategies.stoppedToday()) return "stopped_for_today"
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", lots, ltp * 1.0005), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return "guard_refused: " + refusals.joinToString(" ")
        val entry = buyPaperEntry(arm.label, c, lots, q, ownerOf(arm), mayRetryPrice(b)) { nq -> paperRecheck(c, lots, nq) }
        val buy = entry.result
        val fill = entry.fill ?: return unfilled(c, entry)
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
        val trigger = LiquidityRules.stopTrigger(fill.price, lq.stopPct)
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
            trail = trail, trailFrom = LiquidityTrail.forEntryFrom(trail), priceRetry = price.retried || entry.retried))
        marks[c.symbol] = fill.price
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
            val bars = (liquidityPass[und]?.second ?: liquidityMinutesKept(und, t)).orEmpty()
                .let { LiquidityRules.completed(LiquidityRules.fold(it, tf), tf, t) }
            val strike = Paper.contractOf(p.symbol)?.strike
            val lots = p.lot?.let { p.lots }
            val key = "${p.arm}|${p.symbol}|${p.entryTime}"
            val level = p.level
            if (!p.entryTime.isBefore(since) && level != null) {
                val e = com.optionslab.ira.LiquidityNotice.Entry(book, p.live, p.right, strike, p.symbol, p.qty + p.sold, lots, p.entry,
                    p.entryTime, level, p.target, bars.firstOrNull { it.start == p.signalBar }?.close, p.stopTrigger,
                    LiquidityRules.indexStopPoints(und), liqParams(p).timeStopMinutes, liqParams(p).timeStopGain,
                    label = if (p.live) null else LiquidityTrail.label(p.trail))
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
                    p.grossPnl ?: 0.0, p.charges, tally, label = if (p.live) null else LiquidityTrail.label(p.trail))
                com.optionslab.app.work.LiquidityNotices.exit(app, key, x) { w, h ->
                    com.optionslab.ira.LiquidityNotice.chart(bars, tf, level, p.target, p.entryTime, exitTime, w, h)
                }
            }
        }
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

    /** Next liquidity touched, the break failed, or new liquidity formed: the open liquidity positions are sold. */
    private suspend fun liquidityExits(b: Book, t: LocalDateTime) {
        val open = b.positions.withIndex().filter { it.value.open && !it.value.unconfirmed && armOf(it.value.arm).liquidity }
        if (open.isEmpty()) return
        val minutes = HashMap<String, List<Bar>>()
        for ((i, p) in open) {
            val und = LiquidityRules.underlyingOf(armOf(p.arm))
            val ones = minutes.getOrPut(und) { liquidityMinutes(t, und).filter { !it.start.plusMinutes(1).isAfter(t) } }
            val tf = LiquidityRules.minutesOf(armOf(p.arm))
            val bars = liquidityBars(ones, tf, t)
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
            val look = LiquidityRules.exitNow(side, level, p.target, p.signalBar, p.entry, p.entryTime, t, bars, since, und, fresh, p.timed, liqParams(p))
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
        }
    }

    /**
     * TEST ONLY: earlier sessions' BANKNIFTY 1-minute bars for the liquidity levels (the feed's history). Null in the app,
     * always: [liquidityMinutes] then reads [Net.history]. Its setter throws unless BuildConfig.DEBUG; no app code sets it.
     */
    @Volatile internal var testHistoryBars: ((java.time.LocalDate) -> List<Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test history feed exists only in debug builds" }; field = v }

    /**
     * TEST ONLY: today's 1-minute bars of an index other than BANKNIFTY (FINNIFTY, MIDCPNIFTY) at a given moment. Null in the app,
     * always. While [testIndexBars] is set and this is not, the other indices have no bars (a test never reaches the network).
     */
    @Volatile internal var testOtherIndexBars: ((String, LocalDateTime) -> List<Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test index feed exists only in debug builds" }; field = v }

    /** Earlier sessions' minutes per index, fetched once a day (the levels need a few days of 15-minute bars). */
    private val liquidityHistory = java.util.concurrent.ConcurrentHashMap<String, Pair<java.time.LocalDate, List<Bar>>>()
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
                                         reads: IndexData.Reads<List<Upstox.Bar>>? = null): List<Bar> {
        val minute = t.withSecond(0).withNano(0)
        // The test feeds are read fresh every time (a cached pass must never carry one test's bars into another).
        val testing = testIndexBars != null || testHistoryBars != null || testOtherIndexBars != null
        if (!testing) liquidityPass[underlying]?.let { if (it.first == minute) return it.second }
        val day = t.toLocalDate()
        val bank = underlying == OrbRules.UNDERLYING
        val key = LiquidityRules.INDEX_KEYS.getValue(underlying)
        val hist = (if (!testing) liquidityHistory[underlying]?.takeIf { it.first == day }?.second else null) ?: run {
            val raw = if (testing) (if (bank) testHistoryBars?.invoke(day) else null).orEmpty()
                else runCatching { Net.history(key, day.minusDays(10), day.minusDays(1)) }.getOrDefault(emptyList())
            toBars(raw).filter { it.start.toLocalDate().isBefore(day) }.also { if (!testing && it.isNotEmpty()) liquidityHistory[underlying] = day to it }
        }
        // [reads] (the arms pass): today's candles shared with the pass's other books on this index, read once (13 Oct).
        val raw = reads?.readOrThrow(underlying) { todayMinutes(underlying, t) } ?: todayMinutes(underlying, t)
        val today = toBars(raw).filter { it.start.toLocalDate() == day }
        // Kept as this minute's read only once it has the minute just closed (round 2: a look at the close itself, before the
        // feed or the stream has that minute, is read again by the next look rather than kept for the rest of the minute).
        val whole = today.lastOrNull()?.start?.let { !it.isBefore(minute.minusMinutes(1)) } == true
        return (hist + today).distinctBy { it.start }.sortedBy { it.start }.also { if (!testing && keep && whole) liquidityPass[underlying] = minute to it }
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
    private suspend fun indexBars(t: LocalDateTime, reads: IndexData.Reads<List<Upstox.Bar>>): List<Bar> {
        val ones = reads.readOrThrow(OrbRules.UNDERLYING) { todayMinutes(OrbRules.UNDERLYING, t) }
        return OrbRules.completed(fiveMinute(ones, t.toLocalDate(), t), t)
    }

    /**
     * Today's 1-minute candles of [underlying]'s index as the arms read them: the feed with the stream's newer minutes
     * ([streamed]); in a test, the test feeds (BANKNIFTY's [testIndexBars], the others' [testOtherIndexBars]; an index
     * without a feed has none - a test never reaches the network). Throws when the feed cannot be read.
     */
    private suspend fun todayMinutes(underlying: String, t: LocalDateTime): List<Upstox.Bar> {
        val bank = underlying == OrbRules.UNDERLYING
        if (bank) testIndexBars?.let { return it(t) }
        if (testIndexBars != null || testHistoryBars != null || testOtherIndexBars != null)
            return if (bank) emptyList() else testOtherIndexBars?.invoke(underlying, t).orEmpty()
        val key = LiquidityRules.INDEX_KEYS[underlying] ?: Upstox.INDEX_KEYS.getValue(underlying)
        return streamed(underlying, Net.intraday(key), t)
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
        s == "liquidity_outside_entry_hours" -> "No new entries now (liquidity entries 09:20-14:00)."
        s == FlowGate.SKIPPED -> "Skipped by the order flow (CONFIRM): the flow did not agree with this entry at the decision."
        s.startsWith(com.optionslab.ira.LiquidityTwin.SKIPPED) -> "Not bought (a would-be trade, counted apart): " +
            s.substringAfter(": ").substringAfter("no-twin rule: ") + "."
        s == "liquidity_no_room" -> "Skipped a liquidity break: the next level ahead was closer than one index stop " +
            "(30 BANKNIFTY / 15 FINNIFTY / 8 MIDCPNIFTY points)."
        s.startsWith("hero_") -> describeHero(s) + " ${HeroRules.NOT_PROVEN}."
        s.startsWith("guard_refused: ") -> "Refused by Bot settings: " + s.removePrefix("guard_refused: ")
        s.startsWith("refused: ") -> "Refused: " + s.removePrefix("refused: ")
        s.startsWith("order_refused: ") -> "The order was refused: " + s.removePrefix("order_refused: ")
        s.startsWith("account_changed: ") -> "Not bought: " + s.removePrefix("account_changed: ") + "."
        s.startsWith("error: ") -> "Could not check: " + s.removePrefix("error: ")
        // Boss's 06 Oct rule: the refusal as it is ("opposite_position_open: ORB Sweep holds ..."), then in words.
        com.optionslab.ira.AutoSide.refused(s) -> "$s. " + com.optionslab.ira.AutoSide.describe(s)
        else -> s
    }

    /** TEST ONLY: the book forgotten from memory (its file kept), as a new process starts. */
    @Synchronized internal fun forgetMemoryForTest() {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        cache = null; writtenText = null; writtenStat = null
    }

    @Synchronized fun wipe() {
        cache = null; holdingHint = false; armedLabelsHint = emptyList(); liveHint = false; liveSymbolsHint = emptyList(); restoppedAt.clear(); exposureHint = emptyList(); liquidityLotsHint = LiquidityLots.CHOICES.first(); writtenText = null; writtenStat = null
        shownParams.clear()
        if (::file.isInitialized) file.delete()
    }
}
