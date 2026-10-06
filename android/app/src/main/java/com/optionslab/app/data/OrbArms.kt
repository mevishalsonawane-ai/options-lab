package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Arm
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.LiquidityShadow
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.PassRule
import com.optionslab.engine.orb.ProfitLock
import com.optionslab.engine.orb.Replay
import com.optionslab.engine.orb.RangeFadeRules
import com.optionslab.engine.orb.RetiredArms
import com.optionslab.engine.orb.SweepRules
import com.optionslab.ira.DayStop
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
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        val points: Double? get() = exit?.let { it - entry }
        /** The whole trade's P&L before charges: what is held now at [exit], plus any part sold early at [soldAt]. */
        val grossPnl: Double? get() = points?.let { it * qty + ((soldAt ?: entry) - entry) * sold }
    }

    /** A signal waiting for approval. Liquidity 15+5 also keeps its strike and the index levels (the ORB uses the day's legs). */
    data class Pending(val arm: String, val right: String, val signalBar: LocalDateTime, val expires: LocalDateTime,
                       val strike: Int? = null, val level: Double? = null, val target: Double? = null, val near: Boolean? = null,
                       val volSkip: Boolean? = null, val strong: Boolean? = null)

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
                        seen = p.optJSONArray("seen")?.let { seenOf(it) }.orEmpty())
                }
            }
            o.optJSONObject("pending")?.let { m -> m.keys().forEach { k -> val p = m.getJSONObject(k)
                b.pending[key(k)] = Pending(key(k), p.getString("right"), LocalDateTime.parse(p.getString("bar")), LocalDateTime.parse(p.getString("expires")),
                    if (p.has("strike")) p.getInt("strike") else null, if (p.has("level")) p.getDouble("level") else null,
                    if (p.has("target")) p.getDouble("target") else null, if (p.has("near")) p.getBoolean("near") else null,
                    if (p.has("volSkip")) p.getBoolean("volSkip") else null, if (p.has("strong")) p.getBoolean("strong") else null) } }
            o.optJSONObject("status")?.let { m -> m.keys().forEach { b.status[key(it)] = m.getString(it) } }
            o.optJSONObject("replays")?.let { m -> m.keys().forEach { b.replays[it] = m.getJSONObject(it) } }
            o.optJSONObject("upDays")?.let { m -> m.keys().forEach { b.upDays[it] = m.getBoolean(it) } }
            o.optJSONObject("since")?.let { m -> m.keys().forEach { b.since[it] = m.getString(it) } }
            o.optJSONArray("migrated")?.let { a -> for (i in 0 until a.length()) b.migrated += a.getString(i) }
        }.isSuccess
        if (!ok) {
            // Never overwrite what could not be read: set it aside and start clean, and say so.
            if (file.exists()) Vault.setAside(file)
            Notifier.post(app, 2016, Notifier.APPROVAL, "ORB arms could not be read",
                "Their saved state was set aside and both arms are disarmed. If an ORB position was open, check Trade → Paper now.", "trade")
            return Book().also { it.migrated += listOf(OFF_LOSERS, RetiredArms.MIGRATION); cache = it; hints(it) }
        }
        // A restore not yet disarmed (the app clears the flag once it has): the restored arms act as disarmed.
        if (com.optionslab.app.security.SecurePrefs.getBoolean(Backup.DISARM, false)) {
            b.armed.clear(); b.auto.clear(); b.liveOk.clear(); b.pending.clear()
        }
        cache = b
        hints(b)
        // A new book has nothing to change: it starts with every one-time change marked done (nothing is armed by itself).
        if (!existed) b.migrated += listOf(OFF_LOSERS, RetiredArms.MIGRATION)
        val restoring = com.optionslab.app.security.SecurePrefs.getBoolean(Backup.DISARM, false)
        // Each runs once, in this order: the 06 Oct switch-off, then the retirement that reverses it for Liquidity alone.
        if (switchOffLosers(b) or retire(b, restoring)) save(b)
        return b
    }

    /**
     * Whether an arm holds an open position, as of the last load or save: read without the lock, so the main
     * thread never waits for a pass that is placing orders.
     */
    @Volatile var holdingHint: Boolean = false
        private set

    /**
     * The arms' open positions as [com.optionslab.ira.AutoSide] reads them (each a bought option on its index), as of the
     * last load or save: read without the lock by the other automatic traders ([AutoExposure]).
     */
    @Volatile var exposureHint: List<com.optionslab.ira.AutoSide.Held> = emptyList()
        private set

    private fun hints(b: Book) {
        holdingHint = b.positions.any { it.open }
        exposureHint = exposureOf(b)
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
        com.optionslab.ira.AutoSide.Held.option(arm?.let { ownerOf(it) } ?: p.arm, p.symbol, und, p.right, long = true)
    }

    /**
     * Boss's 06 Oct rule ([com.optionslab.ira.AutoSide]): no automatic entry against another automatic position on the same
     * index, and at most one automatic position an index a side - the arms' own open positions (fresh from [b]) and every
     * other automatic trader's. Null when [c] may be bought, else the refusal the row and the log show.
     */
    private fun exposureRefusal(b: Book, c: Paper.Contract): String? =
        AutoExposure.check(AutoExposure.Source.ORB, c.underlying, com.optionslab.ira.AutoSide.direction(c.right.name, true),
            exposureOf(b) + runCatching { ShadowArms.exposureHint }.getOrDefault(emptyList()))

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
     * [RetiredArms]): ORB and ORB Fresh - and ORB Sweep or Range Fade, if armed again since - are switched off for good
     * ("switched off: lost on 6 years of real data"), an open position still managed to its exit; and Liquidity 15+5's
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
            b.positions.takeLast(2000).forEach { p ->
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
                        if (p.seen.isNotEmpty()) put("seen", seenJson(p.seen)) })
            }
        })
        o.put("pending", JSONObject().apply { b.pending.forEach { (k, p) -> put(k, JSONObject().put("right", p.right).put("bar", p.signalBar.toString()).put("expires", p.expires.toString())
            .apply { p.strike?.let { put("strike", it) }; p.level?.let { put("level", it) }; p.target?.let { put("target", it) }; p.near?.let { put("near", it) }
                p.volSkip?.let { put("volSkip", it) }; p.strong?.let { put("strong", it) } }) } })
        o.put("status", JSONObject(b.status as Map<*, *>))
        o.put("replays", JSONObject().apply { b.replays.entries.toList().takeLast(120).forEach { (k, v) -> put(k, v) } })
        o.put("upDays", JSONObject(b.upDays as Map<*, *>))
        o.put("since", JSONObject(b.since as Map<*, *>))
        o.put("migrated", JSONArray(b.migrated.sorted()))
        val text = o.toString()
        // Battery: an idle book (nothing armed, open or waiting) whose bytes are already on disk, as this process last
        // wrote them, is not encrypted and synced again ([com.optionslab.ira.OrbIdleSave]); anything else is, as before.
        val idle = b.armed.values.none { it } && b.positions.none { it.open } && b.pending.isEmpty()
        val untouched = writtenStat != null && file.exists() && writtenStat == (file.length() to file.lastModified())
        if (com.optionslab.ira.OrbIdleSave.writes(idle, text == writtenText, untouched)) {
            writtenText = null; writtenStat = null
            Vault.writeFile(file, text.toByteArray(Charsets.UTF_8))
            writtenText = text; writtenStat = file.length() to file.lastModified()
        }
        cache = b
        hints(b)
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
        /** Retired (Boss's 06 Oct choice, [RetiredArms]): never armed again; shown in the Retired section, no switch. */
        val retired: RetiredArms.Retired? = null,
        /** Liquidity 15+5 only: its paper trades since 06 Oct with and without each pre-registered candidate ([LiquidityShadow]). */
        val shadow: LiquidityShadow.Summary? = null,
    )

    data class View(
        val arms: List<ArmView>, val legs: Legs?, val range: Pair<Double, Double>?, val forward: PassRule.Verdict,
        val replay: JSONObject?, val replayDay: String?,
        /** The day's stop, when the bot is stopped for today: who stopped it and what resumes it ([DayStop.line]); null when it runs. */
        val stopped: String? = null,
        /** The retired arms' shadows and the new candidate ([ShadowArms]: no orders), one row a variant. */
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
        return viewLocked().copy(stopped = why?.let { DayStop.line(it) }, shadows = shadows)
    }

    private suspend fun viewLocked(): View = lock.withLock {
        val b = book()
        val day = today()
        val arms = ALL_ARMS.filter { it !in LiquidityRules.BOOKS }.map { a ->
            val open = b.positions.lastOrNull { it.arm == a.source && it.open }
            ArmView(a, b.armed[a.source] == true, b.auto[a.source] != false, b.status[a.source] ?: "", open, open?.let { marks[it.symbol] },
                b.pending[a.source], b.positions.filter { it.arm == a.source && it.day == day }, b.liveOk[a.source] == true,
                retired = RetiredArms.of(a.source))
        } + liquidityView(b, day)
        val lastReplay = b.replays.entries.lastOrNull()
        View(arms, b.legs?.takeIf { it.day == day }, b.range?.takeIf { b.rangeDay == day }, forward(b), lastReplay?.value, lastReplay?.key)
    }

    /** Every closed paper trade the arms' book keeps (its last 2000 positions), oldest first. Reads only. */
    suspend fun closedPaper(): List<Position> = lock.withLock { book().positions.filter { !it.open && !it.live } }

    /** Liquidity 15+5 as one row: armed when its books are, both books' trades, each book's state. */
    private fun liquidityView(b: Book, day: LocalDate): ArmView {
        val books = LiquidityRules.BOOKS.map { it.source }
        val open = b.positions.lastOrNull { it.arm in books && it.open }
        val status = LiquidityRules.BOOKS.joinToString("  ") { a ->
            "${LiquidityRules.underlyingOf(a)} ${LiquidityRules.minutesOf(a)}-min: ${describe(b.status[a.source] ?: "")}" }
        return ArmView(LiquidityRules.ARM, books.any { b.armed[it] == true }, books.all { b.auto[it] != false }, status, open,
            open?.let { marks[it.symbol] }, books.firstNotNullOfOrNull { b.pending[it] }, b.positions.filter { it.arm in books && it.day == day },
            books.all { b.liveOk[it] == true }, shadow = shadowOf(b))
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
            LiquidityShadow.Trade(it.day, net, it.near, it.arm, it.volSkip,
                exit1430 = if (alt == null || exitTime == null) null else LiquidityShadow.exitAllNet(net, exitTime, it.entry, it.qty, alt.at1430),
                itm2 = alt?.itm2, strong = it.strong)
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

    // ---- arming and approvals ------------------------------------------------------

    /**
     * [pinConfirmed]: the UI took the PIN or fingerprint (required to arm while the app is in Live). A retired arm
     * ([RetiredArms]: ORB, ORB Fresh, ORB Sweep, Range Fade) is never armed, whoever asks (a switch, Jarvis, a plan): the
     * refusal says why. Switching one off still works.
     */
    suspend fun setArmed(source: String, on: Boolean, automatic: Boolean, pinConfirmed: Boolean = false): String {
        if (on) RetiredArms.of(source)?.let { return RetiredArms.refusal(it) }
        return setArmedAny(source, on, automatic, pinConfirmed)
    }

    /**
     * TEST ONLY: arms (or disarms) any arm, a retired one too, so the tests still run the retired arms' engine paths (the
     * backtests and the evening replay keep their rules). Throws unless BuildConfig.DEBUG; no app code calls it.
     */
    internal suspend fun armForTest(source: String, on: Boolean, automatic: Boolean, pinConfirmed: Boolean = false): String {
        check(com.optionslab.app.BuildConfig.DEBUG) { "arming a retired arm exists only in debug builds" }
        return setArmedAny(source, on, automatic, pinConfirmed)
    }

    private suspend fun setArmedAny(source: String, on: Boolean, automatic: Boolean, pinConfirmed: Boolean): String = lock.withLock {
        val b = book()
        if (source == LiquidityRules.ARM.source) {
            // Both books follow the one switch, with the ORB's rules: the Paper / Live switch, the PIN for Live, automatic or approve.
            val live = liveNow()
            if (on && live && !pinConfirmed) return@withLock "The app is in Live: arm it with your PIN or fingerprint."
            for (a in LiquidityRules.BOOKS) {
                b.armed[a.source] = on; b.auto[a.source] = automatic; b.liveOk[a.source] = on && live && pinConfirmed
                if (!on) b.pending.remove(a.source)
            }
            save(b)
            val holding = b.positions.any { it.open && it.arm in LiquidityRules.BOOKS.map { a -> a.source } }
            return@withLock if (on) "${LiquidityRules.ARM.label} armed" + (if (live) " on ZERODHA (live), " else " on paper, ") +
                (if (automatic) "fully automatic" else "you approve each entry") + ": on the 15-minute and the 5-minute BANKNIFTY and FINNIFTY charts, " +
                "when a close takes a liquidity pool that sits on a swing zone, it buys the ATM call (up) or put (down), 1 lot, with a stop " +
                "15% below the price paid, and sells at the next liquidity level, when new liquidity forms, when the break fails, or at " +
                "15:10. Entries 09:20-14:00, one position per chart."
            else "${LiquidityRules.ARM.label} disarmed." + if (holding) " Its open position is still managed to its exit." else ""
        }
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
        save(b)
        val label = armOf(source).label
        if (on && armOf(source).hero) "$label armed on paper (it never trades on Zerodha) - ${HeroRules.NOT_PROVEN}. Fully automatic, " +
            "NIFTY expiry days only (from the instrument master): from 13:30 to 14:45, when the ATM straddle is 15% above its low " +
            "since 12:00 and NIFTY has moved 0.25% in 15 minutes, it buys the nearest OTM option on that side priced Rs 1-5 with a " +
            "LIMIT order, up to Rs 5,000 of premium, once a day. It sells half at 5x the price paid and the rest at 20x or " +
            "15:05 (the expiry square-off), with a stop at -60% of the premium on a minute's close. With the old exits it " +
            "lost on all 13 trades out of sample (-Rs 64,190). It disarms itself after ${HeroRules.MAX_LOSING_DAYS} losing expiry " +
            "days in a row or Rs 50,000 lost."
        else if (on && armOf(source).fade) "$label armed on paper (it never trades on Zerodha), fully automatic: when a 5-minute bar " +
            "reaches the outer tenth of the opening range and closes back inside, it buys the option toward the middle - 1 lot, " +
            "a 40-point stop, a 40-point target and the 15:10 exit, at most ${RangeFadeRules.MAX_ENTRIES} a day, from 10:30."
        else if (on && paperOnly) "$label armed on paper (it never trades on Zerodha), fully automatic: it fades a failed break of the " +
            "opening range - a 5-minute bar through the range high or low that closes back inside - with 1 lot, a 40-point stop, " +
            "an 80-point target and the 15:10 exit, at most ${SweepRules.MAX_ENTRIES} a day, from 10:05."
        else if (on) "$label armed" + (if (live) " on ZERODHA (live), " else " on paper, ") +
            (if (automatic) "fully automatic: it buys and sells by itself every trading day until you switch it off." else "you approve each entry.") +
            " It decides on 5-minute BANKNIFTY bars from 10:05."
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
        // A live entry is sent only after the owner's PIN or fingerprint (the UI asks first).
        if (liveNow() && !pinConfirmed) return "The app is in Live: approve with your PIN on Home → Strategies."
        if (source == LiquidityRules.ARM.source || armOf(source).liquidity) return approveLiquidity(source, pinConfirmed)
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
        b.pending.remove(key); b.status[key] = "skipped_by_you"; save(b); "Skipped."
    }

    private suspend fun approveLiquidity(source: String, pinConfirmed: Boolean): String = lock.withLock {
        val b = book()
        val key = if (source == LiquidityRules.ARM.source) liquidityBook(b) else source
        val p = key?.let { b.pending.remove(it) } ?: return@withLock "Nothing is waiting for approval."
        save(b)
        if (now().isAfter(p.expires)) return@withLock "The ${LiquidityRules.ARM.label} signal expired at ${hhmm(p.expires)}; a new break will ask again."
        val live = liveNow()
        if (live && !pinConfirmed) return@withLock "The app switched to Live: approve with your PIN on Home → Strategies."
        val signal = LiquidityRules.Signal(if (p.right == "CE") 1 else -1, p.level ?: return@withLock "The signal lost its level; a new break will ask again.", p.target)
        val msg = enterLiquidity(b, armOf(p.arm), signal, p.signalBar, p.strike ?: return@withLock "The signal lost its strike.", live, p.near, p.volSkip, p.strong)
        b.status[p.arm] = msg; save(b); describe(msg)
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
        try {
            tickLocked(t)
        } finally {
            heroFed.clear()
        }
    }

    private suspend fun tickLocked(t: LocalDateTime) {
        lock.withLock {
            val b = book()
            runCatching { priceCheck(b, t) }
            runCatching { liquidityExits(b, t) }
            runCatching { heroKillCheck(b) }
            val anyArmed = ALL_ARMS.any { b.armed[it.source] == true }
            if (!anyArmed || !OrbRules.inWindow(t.toLocalTime())) { save(b); return@withLock }
            val bars = runCatching { indexBars(t) }.getOrNull()
            for (arm in ALL_ARMS) {
                if (b.armed[arm.source] != true) continue
                // The Hero arm reads its own NIFTY data: the BANKNIFTY bars are not its concern.
                val s = if (bars == null && !arm.hero) "no_index_data" else runCatching { cycle(b, arm, t, bars.orEmpty()) }.getOrElse { "error: ${it.message}" }
                // "no_decision_bar" repeats within a bar; keep the bar's own verdict on screen.
                if (s != "no_decision_bar" || b.status[arm.source].isNullOrEmpty()) {
                    // Each bar's verdict goes to the diagnostics once (why it did or did not enter), with the range and the bar.
                    if (s != "no_decision_bar" && s != b.status[arm.source]) runCatching {
                        val last = bars?.lastOrNull()
                        Diag.record("orb", "${arm.label}: $s" + (b.range?.let { " · range %.1f-%.1f".format(java.util.Locale.ENGLISH, it.second, it.first) } ?: "") +
                            (last?.let { " · bar ${it.start.toLocalTime()} close %.1f".format(java.util.Locale.ENGLISH, it.close) } ?: ""))
                    }
                    b.status[arm.source] = s
                }
            }
            save(b)
        }
    }

    /** A lighter pass between minutes while a position is open: the stop, the target and the clock. */
    suspend fun priceCheckOnly() = lock.withLock { val b = book(); runCatching { priceCheck(b, now()) }; save(b) }

    /**
     * An entry left unapproved past its time ([Pending.expires]) is dropped - said once (a notice in place of the approval's,
     * and a diagnostics line), so an APPROVE arm that did not trade says why instead of going quiet.
     */
    private fun lapsed(arm: Arm, pd: Pending) {
        val mins = java.time.Duration.between(pd.signalBar, pd.expires).toMinutes()
        val text = "${arm.label}: the ${hhmm(pd.signalBar)} signal (BUY ${pd.right}) was not approved in $mins minutes and lapsed at " +
            "${hhmm(pd.expires)} - nothing was bought. A new signal asks again."
        runCatching { Diag.record("orb", text) }
        runCatching { Notifier.post(app, 6960 + ALL_ARMS.indexOf(arm), Notifier.APPROVAL, "${if (arm.liquidity) arm.label else "ORB"} signal lapsed: not approved in $mins minutes", text, "strategy") }
    }

    private suspend fun cycle(b: Book, arm: Arm, t: LocalDateTime, bars: List<Bar>): String {
        val day = t.toLocalDate()
        val watchKey = "${arm.source}|$day"
        // Holding a position or waiting for the owner's approval is running, not paused: re-entries keep their rules.
        fun watching() { bars.lastOrNull()?.let { b.watched[watchKey] = it.start.toString() } }
        if (b.positions.any { it.arm == arm.source && it.open }) { watching(); return "holding" }
        if (!t.toLocalTime().isBefore(OrbRules.SQUARE_OFF)) return "flat_after_square_off"
        if (Strategies.stoppedToday()) { b.watched.remove(watchKey); return "stopped_for_today" }
        if (arm.hero) return heroCycle(b, t)
        b.pending[arm.source]?.let { pd -> if (t.isAfter(pd.expires)) { b.pending.remove(arm.source); lapsed(arm, pd) } else { watching(); return "awaiting_approval" } }
        if (arm.liquidity) return liquidityCycle(b, arm, t)
        val rng = OrbRules.openingRange(bars) ?: return "waiting_for_opening_range"
        b.range = rng; b.rangeDay = day
        val legs = contracts(b, day, bars) ?: return "no_contract"
        val last = bars.last()
        val spent = b.decided.getOrPut("${arm.source}|$day") { HashSet() }
        if (!spent.add(last.start.toString())) return "no_decision_bar"
        val lastExit = b.positions.filter { it.arm == arm.source && it.day == day }.mapNotNull { it.exitTime }.maxOrNull()
        if (arm.fade) {
            // Range Fade: a bar at the edge of the range that closes back inside is faded toward the middle; paper only, automatic.
            val entries = b.positions.count { it.arm == arm.source && it.day == day }
            val (dir, why) = RangeFadeRules.entrySignal(bars, rng, lastExit, entries)
            watching()
            if (dir == 0) return why
            return enter(b, arm, if (dir > 0) legs.ce else legs.pe, last.start, live = false)
        }
        if (arm.sweep) {
            // ORB Sweep: a single bar's failed break is the signal (nothing to chase), paper only, automatic.
            val entries = b.positions.count { it.arm == arm.source && it.day == day }
            val (dir, why) = SweepRules.entrySignal(bars, rng, lastExit, entries)
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
        val (direction, why) = OrbRules.entrySignal(bars, rng, arm, lastExit, requireFresh = resumed)
        if (direction == 0) {
            watching()
            return if (why == "not_a_fresh_break" && !arm.freshOnly) "waiting_for_fresh_break_after_pause" else why
        }
        val right = if (direction > 0) "CE" else "PE"
        val c = if (direction > 0) legs.ce else legs.pe
        val live = liveNow()
        // Automatic unless the owner chose approvals, or the arm was armed in Paper and now finds the app in Live.
        if (b.auto[arm.source] == false || (live && b.liveOk[arm.source] != true)) {
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
        // One index, one side, for every automatic trader (Boss's 06 Oct rule): paper and live alike.
        exposureRefusal(b, c)?.let { return it }
        if (live) return enterLive(b, arm, c, signalBar)
        val ltp = Paper.lastPrice(c) ?: return "refused: no quote"             // never enter blind
        // The Upstox feed has no bid/ask, so the paper fill is the LTP slipped 5 bps: price the checks the same way.
        val expected = ltp * 1.0005
        if (OrbRules.stopTrigger(expected) == null) return "refused: premium %.2f is at or below 40, a 40-point stop has no level".format(Locale.ENGLISH, expected)
        if (Strategies.stoppedToday()) return "stopped_for_today"             // stop pressed mid-decision
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", 1, expected), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return "guard_refused: " + refusals.joinToString(" ")
        val buy = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null)
        val fill = filledOrCancelled(buy) ?: return "order_refused: ${if (buy.ok) "no price to fill at; the order was cancelled" else buy.message}"
        buy.orderId?.let { Strategies.tagOwner("paper:$it", "${arm.label} · entry") }
        Notifier.orderFilled(app, "BUY", fill.quantity, fill.symbol, fill.price, "Paper", "${arm.label} · entry", buy.orderId)
        val trigger = OrbRules.stopTrigger(fill.price)
        var stopId: String? = null
        if (trigger != null) {
            val stop = Paper.place(c, "SELL", 1, "SL-M", "MIS", null, trigger)
            if (stop.ok) { stopId = stop.orderId; stopId?.let { Strategies.tagOwner("paper:$it", "${arm.label} · stop") } }
        }
        b.positions += Position(arm.source, c.symbol, c.right.name, fill.quantity, fill.price, now(), signalBar, buy.orderId, stopId, trigger,
            charges = chargesOf(buy.orderId), ladder = ProfitLock.targetOf(arm) != null)
        marks[c.symbol] = fill.price
        return "entered"
    }

    /** Resting stop, +40 target, 15:10 exit, the 15:15 backstop and the operator stop, for every open position. */
    private suspend fun priceCheck(b: Book, t: LocalDateTime) {
        val stopped = Strategies.stoppedToday()
        val liveOpen = b.positions.withIndex().filter { it.value.open && it.value.live }
        if (liveOpen.isNotEmpty()) runCatching { priceCheckLive(b, t, liveOpen, stopped) }
        val open = b.positions.withIndex().filter { it.value.open && !it.value.live }
        if (open.isEmpty()) return
        val orders = Paper.state.orders.associateBy { it.orderId }
        for ((i, p) in open) {
            val c = Paper.contractOf(p.symbol) ?: continue
            // The resting stop filled in the paper book: that is the exit.
            val so = p.stopOrderId?.let { orders[it] }
            if (so != null && so.status == "complete") {
                b.positions[i] = p.copy(exit = so.averagePrice?.toDouble() ?: p.stopTrigger, exitTime = so.updateTimestamp, why = "stop",
                    charges = p.charges + chargesOf(so.orderId))
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
            val (seen, locked) = ladder(cur, ltp)
            if (seen !== cur) b.positions[i] = seen
            // The Hero arm's own exits (HeroRules.exitStep: half at 5x, the rest at 20x or 15:05, the -60% stop on a minute's
            // close), checked here every pass; the operator's stop still sells it all at once below.
            if (armOf(cur.arm).hero && !stopped) { b.positions[i] = heroManage(seen, c, ltp, t); continue }
            val why = when {
                stopped -> "operator_stop"
                !t.toLocalTime().isBefore(OrbRules.SQUARE_OFF) -> "session_end"
                // Liquidity 15+5 exits on index levels (liquidityExits); its 15% stop rests in the book, and if that order is
                // gone the app sells at the stop level itself.
                armOf(cur.arm).liquidity -> "stop".takeIf { cur.stopOrderId == null && cur.stopTrigger?.let { ltp <= it } == true }
                locked -> "profit_lock"
                else -> (if (armOf(cur.arm).sweep) SweepRules.exitReason(cur.entry, ltp, t) else OrbRules.exitReason(cur.entry, ltp, t))
                    .takeIf { it == "target" || (it == "stop" && cur.stopOrderId == null) }
            } ?: continue
            b.positions[i] = if (armOf(cur.arm).hero) heroExit(seen, c, why) else exit(seen, c, why)
        }
    }

    /** Take the resting stop out of the book first, then sell; if the stop filled meanwhile, that is the exit. */
    private suspend fun exit(p: Position, c: Paper.Contract, why: String): Position {
        p.stopOrderId?.let { id ->
            Paper.cancel(id, "exit:$why")
            val so = Paper.state.orders.firstOrNull { it.orderId == id }
            if (so?.status == "complete") return p.copy(exit = so.averagePrice?.toDouble(), exitTime = so.updateTimestamp, why = "stop",
                charges = p.charges + chargesOf(id))
        }
        val sell = Paper.place(c, "SELL", p.qty / c.lotSize.coerceAtLeast(1), "MARKET", "MIS", null, null)
        // Nothing sold (no price): the resting stop goes back in the book so the position is never left without one; the
        // sale is tried again on the next pass.
        val fill = filledOrCancelled(sell) ?: return p.copy(stopOrderId = p.stopOrderId?.let { restop(p, c) })
        sell.orderId?.let { Strategies.tagOwner("paper:$it", "${ownerOf(armOf(p.arm))} · $why") }
        Notifier.orderFilled(app, "SELL", fill.quantity, fill.symbol, fill.price, "Paper", "${ownerOf(armOf(p.arm))} · exit", sell.orderId)
        return p.copy(stopOrderId = null, exit = fill.price, exitTime = now(), why = why, charges = p.charges + chargesOf(sell.orderId))
    }

    /** The resting stop placed again at its trigger after an exit sold nothing; null when it cannot be. */
    private suspend fun restop(p: Position, c: Paper.Contract): String? {
        val trigger = p.stopTrigger ?: return null
        val r = runCatching { Paper.place(c, "SELL", p.qty / c.lotSize.coerceAtLeast(1), "SL-M", "MIS", null, trigger) }.getOrNull()
        return r?.takeIf { it.ok }?.orderId?.also { Strategies.tagOwner("paper:$it", "${armOf(p.arm).label} · stop") }
    }

    /**
     * The profit-lock ladder ([ProfitLock], its breakeven after charges) for a laddered position at [ltp]: the position with its best price updated,
     * and whether [ltp] gave back to the lock its earlier best had earned (then the app sells at market; the resting
     * -40 stop stays in place underneath until that sale takes it out).
     */
    private fun ladder(p: Position, ltp: Double): Pair<Position, Boolean> {
        val target = ProfitLock.targetOf(armOf(p.arm))?.takeIf { p.ladder } ?: return p to false
        val before = p.peak ?: p.entry
        // The breakeven rung sits at the price paid plus the round trip's charges (Boss's 06 Oct fix): a profit-lock exit is
        // never a certain small loss after charges.
        val locked = ProfitLock.exits(p.entry, target, before, ltp, ProfitLock.roundTripPerUnit(p.entry, p.qty))
        return (if (ltp > before) p.copy(peak = ltp) else p) to locked
    }

    // ---- the expiry-day Hero arm (paper only, not proven) -------------------------------

    /** The marker in the day's decided set once the Hero arm has stood down for the day on stale data. */
    private const val HERO_STOOD_DOWN = "hero_stood_down"
    /** The Hero arm's own notices (fired, disarmed); the arms' approvals use 6960 and up. */
    private const val HERO_NOTICE = 6990

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
        val spot = heroMinutes(Upstox.INDEX_KEYS.getValue(HeroRules.UNDERLYING), t).mapValues { it.value.close }
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
        if (scan.standDown) {
            spent += HERO_STOOD_DOWN
            runCatching { Diag.record("orb", "${HeroRules.ARM.label}: stood down for today: more than ${HeroRules.MAX_SKIPPED} minutes in a row of stale NIFTY or straddle data") }
            return HERO_STOOD_DOWN
        }
        // A stale minute is looked at again on the next pass (the data may still arrive); the scan counts it if it never does.
        if (scan.why == "skipped_stale_bar") return "hero_skipped_stale_bar"
        spent += "hero@$at"
        if (scan.signal == 0) return "hero_" + scan.why
        val right = if (scan.signal > 0) Right.CE else Right.PE
        val s0 = scan.spot ?: return "hero_skipped_stale_bar"
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
        val pick = HeroRules.pick(scan.signal, s0, chain) ?: return "hero_no_strike"
        val c = Paper.contractFor(HeroRules.UNDERLYING, day, pick.strike, right) ?: return "no_contract"
        // The lot size comes from the master, never a constant; unknown: no trade.
        if (c.lotSize <= 0) return "hero_lot_unknown"
        // The kill switch and Stop for today win (paper too, for this arm: plans only lower risk).
        if (runCatching { AppSettings.load().guardKill }.getOrDefault(true)) return "refused: the kill switch is on"
        if (Strategies.stoppedToday()) return "stopped_for_today"
        val q = runCatching { Paper.quote(c) }.getOrNull()
        val ask = q?.ask?.takeIf { it > 0 } ?: q?.ltp?.takeIf { it > 0 } ?: pick.ltp
        val limit = HeroRules.limitPrice(ask, pick.ltp)
        val lots = HeroRules.lots(limit, c.lotSize)
        if (lots <= 0) return "hero_zero_lots"
        exposureRefusal(b, c)?.let { return it }
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", lots, limit), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return "guard_refused: " + refusals.joinToString(" ")
        // Never a market order on these strikes: a LIMIT BUY on the PAPER account. Not filled at once, it is cancelled
        // (nothing held), and the arm may decide again on a later bar until 14:45.
        val buy = Paper.place(c, "BUY", lots, "LIMIT", "MIS", limit, null, known = q)
        val fill = filledOrCancelled(buy) ?: return "order_refused: " +
            (if (buy.ok) "the limit %.2f did not fill; the order was cancelled".format(Locale.ENGLISH, limit) else buy.message)
        buy.orderId?.let { Strategies.tagOwner("paper:$it", "${HeroRules.OWNER} · entry") }
        Notifier.orderFilled(app, "BUY", fill.quantity, fill.symbol, fill.price, "Paper", "${HeroRules.OWNER} · entry", buy.orderId)
        val text = ("NIFTY %s %.2f%% in 15 min, straddle %.0f%% above its low: bought %d %s @ %.2f on PAPER (limit %.2f). " +
            "Sells half at %.2f (5x), the rest at %.2f (20x) or 15:05; stop: a minute closing at or below %.2f (-60%%). %s.")
            .format(Locale.ENGLISH, if (scan.signal > 0) "up" else "down", (scan.mom ?: 0.0) * 100, (scan.stExp ?: 0.0) * 100,
                fill.quantity, fill.symbol, fill.price, limit, HeroRules.target1(fill.price), HeroRules.target2(fill.price),
                HeroRules.stopLevel(fill.price), HeroRules.NOT_PROVEN)
        runCatching { Notifier.post(app, HERO_NOTICE, Notifier.BUY, "${HeroRules.ARM.label} fired", text, "trade") }
        runCatching { Diag.record("orb", "${HeroRules.ARM.label}: $text") }
        // The book at the signal minute (bid, ask and their quantities from Zerodha's stream; else the last price alone).
        b.positions += Position(src, c.symbol, c.right.name, fill.quantity, fill.price, now(), day.atTime(at), buy.orderId, null, null,
            charges = chargesOf(buy.orderId), seen = listOf(heroSeen("signal", q, pick.ltp)))
        marks[c.symbol] = fill.price
        return "entered"
    }

    /**
     * The Hero arm's exit of everything it still holds (15:05, the -60% stop, the operator's stop): a LIMIT SELL at the bid
     * less a tick; not filled, the usual exit (a market sell, retried). The book at that moment is logged with the trade.
     */
    private suspend fun heroExit(p0: Position, c: Paper.Contract, why: String): Position {
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

    private suspend fun heldAtZerodha(sym: String): Int? = runCatching {
        Broker.positionBook().net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.qty }
    }.getOrNull()

    /**
     * The resting SL SELL 40 below a live fill. Its trigger, and its order id or null when it could not be
     * placed (the app then watches the stop itself). A lost reply is looked for before it counts as unplaced.
     */
    private suspend fun placeStop(label: String, sym: String, qty: Int, lot: Int, tick: Double, fill: Double, known: Collection<String>,
                                  liquidity: Boolean = false): Pair<String?, Double?> {
        // The ORB's stop is 40 points below the fill; Liquidity 15+5's is 15% below it.
        val trigger = (if (liquidity) LiquidityRules.stopTrigger(fill) else OrbRules.stopTrigger(fill))
            ?.let { com.optionslab.engine.Kite.onTick(it, tick, com.optionslab.engine.Kite.Side.SELL) }
            ?: return null to null
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, qty, lot, "MIS", "SL",
            stopLimit(trigger, tick), tick, "NFO", "iraorb", triggerPrice = trigger)
        val id = try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
            if (Broker.definite(e)) null else runCatching { Broker.findRecentRetrying(o, known) }.getOrNull()
        }
        if (id != null) Strategies.tagOwner("kite:$id", "$label · stop")
        else com.optionslab.app.work.Alerts.error("$label: the ${if (liquidity) "15%" else "−40"} stop could not be placed at Zerodha; the app watches it instead.", "ORB live")
        return id to trigger
    }

    /**
     * A live entry: MARKET BUY 1 lot MIS at Zerodha, then a resting SL SELL 40 below the
     * fill. Only reached after the owner approved with the PIN (see [approve]).
     */
    private suspend fun enterLive(b: Book, arm: Arm, c: Paper.Contract, signalBar: LocalDateTime, liquidity: LiquidityRules.Signal? = null,
                                  near: Boolean? = null, volSkip: Boolean? = null, strong: Boolean? = null): String {
        // A paper-only arm (the Hero arm among them) never reaches Zerodha, whatever called this.
        liveRefusal(arm.source)?.let { return it }
        val s = AppSettings.load()
        if (s.guardKill) return "refused: the kill switch is on"
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
        if (liquidity == null && OrbRules.stopTrigger(last) == null) return "refused: premium %.2f is at or below 40, a 40-point stop has no level".format(Locale.ENGLISH, last)
        if (Strategies.stoppedToday()) return "stopped_for_today"
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.BUY, ins.lotSize, ins.lotSize, "MIS", "MARKET", null,
            ins.tickSize, "NFO", "iraorb")
        val acct = Broker.accountNow()      // positions, funds and orders read in parallel
        val refusals = Guard.check(Guard.liveOrder(o).copy(price = last), acct)
        if (refusals.isNotEmpty()) return "guard_refused: " + refusals.joinToString(" ")
        val why = com.optionslab.engine.Kite.refusals(o, s.limits(), Broker.sentToday(), false, refPrice = last)
        if (why.isNotEmpty()) return "refused: " + why.joinToString("; ")
        val known = knownKite(b)
        val id: String? = try {
            Broker.placeOrder(o)
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
            b.positions += Position(arm.source, c.symbol, c.right.name, o.quantity, last, now(), signalBar, id, null, null,
                live = true, kite = sym, unconfirmed = true, level = liquidity?.level, target = liquidity?.target, ladder = ProfitLock.targetOf(arm) != null,
                near = near, volSkip = volSkip, strong = strong)
            runCatching { save(b) }
            com.optionslab.app.work.Alerts.error("${arm.label}: Zerodha did not confirm the buy of $sym. It is treated as held (no stop yet) " +
                "until the order book shows what filled.", "ORB live")
            return "entered_unconfirmed"
        }
        if (f.filled <= 0) return "order_refused: Zerodha ${f.status.lowercase()}" + (f.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        val fill = f.avgPrice.takeIf { it > 0 } ?: last
        Notifier.orderFilled(app, "BUY", f.filled, sym, fill, "Live", "${arm.label} · entry", f.orderId)
        val (stopId, trigger) = placeStop(arm.label, sym, f.filled, ins.lotSize, ins.tickSize, fill, known + listOfNotNull(id), liquidity = liquidity != null)
        b.positions += Position(arm.source, c.symbol, c.right.name, f.filled, fill, now(), signalBar, id, stopId, trigger,
            charges = kiteCharge("BUY", fill, f.filled), live = true, kite = sym, level = liquidity?.level, target = liquidity?.target,
            ladder = ProfitLock.targetOf(arm) != null, near = near, volSkip = volSkip, strong = strong)
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
        val (stopId, trigger) = if (spec != null) placeStop(label, sym, st.filled, spec.lotSize, spec.tickSize, fill, known + oid, liquidity = liq)
            else null to (if (liq) LiquidityRules.stopTrigger(fill) else OrbRules.stopTrigger(fill))
        return p.copy(qty = st.filled, entry = fill, entryOrderId = oid, stopOrderId = stopId, stopTrigger = trigger,
            charges = kiteCharge("BUY", fill, st.filled), unconfirmed = false)
    }

    /** Stop, target, 15:10 and the operator stop for positions held at Zerodha. */
    private suspend fun priceCheckLive(b: Book, t: LocalDateTime, open: List<IndexedValue<Position>>, stopped: Boolean) {
        if (!Broker.loggedIn) return
        val orders = Broker.orders().associateBy { it.id }
        val net = Broker.positionBook().net
        val keys = open.mapNotNull { it.value.kite }.map { "NFO:$it" }.distinct()
        val q = runCatching { Broker.quotes(keys) }.getOrDefault(emptyMap())
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
                Notifier.orderFilled(app, "SELL", so.filled, sym, px, "Live", "${armOf(p.arm).label} · stop", p.stopOrderId)
                b.positions[i] = p.copy(exit = px, exitTime = t, why = "stop", stopOrderId = null, charges = p.charges + kiteCharge("SELL", px, p.qty))
                continue
            }
            // A stop taken out at Zerodha (refused, or cancelled by hand or by Zerodha) leaves the position without one: say so.
            if (so != null && so.status in setOf("REJECTED", "CANCELLED")) com.optionslab.app.work.Alerts.error(
                "${armOf(p.arm).label}: the stop at Zerodha was ${so.status.lowercase()} (${so.message.ifBlank { "no reason given" }}). " +
                    "The app now watches the −40 stop itself.", "ORB live")
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
            val runThrough = cur.stopTrigger?.let { ltp < stopLimit(it, tick) } == true
            val (seen, locked) = ladder(cur, ltp)
            if (seen !== cur) b.positions[i] = seen
            val why = when {
                stopped -> "operator_stop"
                !t.toLocalTime().isBefore(OrbRules.SQUARE_OFF) -> "session_end"
                // Liquidity 15+5: its 15% stop rests at Zerodha; the app sells only if that is gone or the price ran through it.
                armOf(cur.arm).liquidity -> "stop".takeIf { (cur.stopOrderId == null || runThrough) && cur.stopTrigger?.let { ltp <= it } == true }
                locked -> "profit_lock"
                else -> OrbRules.exitReason(cur.entry, ltp, t).takeIf { it == "target" || (it == "stop" && (cur.stopOrderId == null || runThrough)) }
            } ?: continue
            val (done, rest) = exitLive(b, seen, sym, why)
            b.positions[i] = done
            rest?.let { b.positions += it }
        }
        drop.sortedDescending().forEach { b.positions.removeAt(it) }
    }

    /**
     * Take the resting stop out first, then MARKET SELL what is held; if the stop filled meanwhile, that is the exit.
     * Returns the position, and (when only part sold) the rest, still open, to be sold on the next pass.
     */
    private suspend fun exitLive(b: Book, p: Position, sym: String, why: String): Pair<Position, Position?> {
        val label = armOf(p.arm).label
        // The sell certainly did not go: a stop that was taken out is placed again so the position is never left unprotected.
        suspend fun unsold(): Pair<Position, Position?> {
            if (p.stopOrderId == null) return p to null
            val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: return p.copy(stopOrderId = null) to null
            val (id, _) = placeStop(label, sym, p.qty, spec.lotSize, spec.tickSize, p.entry, knownKite(b), liquidity = armOf(p.arm).liquidity)
            return p.copy(stopOrderId = id) to null
        }
        p.stopOrderId?.let { id ->
            runCatching { Broker.cancel(id) }
            val st = runCatching { Broker.orderState(id) }.getOrNull()
            if (st?.status == "COMPLETE") {
                val px = st.avgPrice.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                return p.copy(exit = px, exitTime = now(), why = "stop", stopOrderId = null, charges = p.charges + kiteCharge("SELL", px, p.qty)) to null
            }
            // Only sell once the stop is known to be out: a stop still working plus a market sell could both fill.
            if (st == null || st.status !in setOf("CANCELLED", "REJECTED")) return p to null
        }
        // What is held and what is already on its way out, read again after the stop came out. Only this position's own
        // orders are ever cancelled here: another sell resting on the symbol (the other arm's stop, a protection, the
        // owner's own order, an earlier pass's sell whose reply was lost) is subtracted instead, so both filling never
        // turns the long into a short.
        val working = runCatching { Broker.orders().filter { it.working && it.symbol == sym && it.side == "SELL" && it.product == "MIS" } }.getOrNull()
            ?: return p.copy(stopOrderId = null) to null
        val still = heldAtZerodha(sym) ?: return p.copy(stopOrderId = null) to null
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
        val id = try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
            val definite = Broker.definite(e)
            val found = if (definite) null else runCatching { Broker.findRecentRetrying(o, knownKite(b) + working.map { it.id }) }.getOrNull()
            found ?: run {
                com.optionslab.app.work.Alerts.error("$label: the Zerodha exit failed (${e.message}); retrying on the next pass.", "ORB live")
                // Refused outright: nothing went, so the stop goes back. A lost reply with no order found may still have gone.
                return if (definite) unsold() else p.copy(stopOrderId = null) to null
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

    private fun chargesOf(orderId: String?): Double =
        Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    // ---- Liquidity 15+5 ----------------------------------------------------------

    /** One book's decision on its chart's last completed bar: a pool taken on a swing zone buys the option, paper only. */
    private suspend fun liquidityCycle(b: Book, arm: Arm, t: LocalDateTime): String {
        val tf = LiquidityRules.minutesOf(arm)
        val und = LiquidityRules.underlyingOf(arm)
        val ones = liquidityMinutes(t, und)
        val bars = liquidityBars(ones, tf, t)
        if (bars.size < 2 * LiquidityRules.SWING_LOOKBACK + 2) return "liquidity_history_loading"
        val last = bars.last()
        val day = t.toLocalDate()
        if (last.start.toLocalDate() != day) return "no_decision_bar"
        if (!b.decided.getOrPut("${arm.source}|$day") { HashSet() }.add(last.start.toString())) return "no_decision_bar"
        if (!LiquidityRules.mayEnterAt(last.start.plusMinutes(tf.toLong()))) return "liquidity_outside_entry_hours"
        val s = LiquidityRules.signal(bars, LiquidityRules.zones(bars)) ?: return "no_liquidity_break"
        // The room filter and the 1-ITM strike (research liq2, adopted on paper 06 Oct by Boss's choice).
        if (!LiquidityRules.hasRoom(s, last.close, und)) return "liquidity_no_room"
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
        val live = liveNow()
        // As the ORB: automatic unless the owner chose approvals, or it was armed in Paper and now finds the app in Live.
        if (b.auto[arm.source] == false || (live && b.liveOk[arm.source] != true)) {
            val right = if (s.side > 0) "CE" else "PE"
            val expires = last.start.plusMinutes(2L * tf)                    // until the next bar completes
            b.pending[arm.source] = Pending(arm.source, right, last.start, expires, strike, s.level, s.target, near, volSkip, strong)
            Notifier.post(app, 6960 + ALL_ARMS.indexOf(arm), Notifier.APPROVAL, "${LiquidityRules.ARM.label}: approve BUY $und $strike $right",
                "The $und ${tf}-minute ${hhmm(last.start)} bar took a liquidity pool ${if (s.side > 0) "above" else "below"}. " +
                    (if (live) "LIVE on Zerodha, 1 lot: approve it on Home in the app" else "Paper account, 1 lot") +
                    ". Approve by ${hhmm(expires)} or it lapses.", "almanac", approve = "orb")
            return "awaiting_approval"
        }
        return enterLiquidity(b, arm, s, last.start, strike, live, near, volSkip, strong)
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
     * A MARKET BUY of 1 lot of the ATM option on the break (paper, or Zerodha when [live]), with its resting stop 15% below
     * the fill. [near], [volSkip]: candidates (a)'s and (c)'s shadow flags, kept with the position.
     */
    private suspend fun enterLiquidity(b: Book, arm: Arm, s: LiquidityRules.Signal, signalBar: LocalDateTime, strike: Int, live: Boolean,
                                       near: Boolean? = null, volSkip: Boolean? = null, strong: Boolean? = null): String {
        val right = if (s.side > 0) Right.CE else Right.PE
        val day = signalBar.toLocalDate()
        val und = LiquidityRules.underlyingOf(arm)
        val listed = Market.contracts().filter { it.underlying == und }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryAfter(day, listed) ?: return "no_contract"
        val c = Paper.contractFor(und, expiry, strike.toDouble(), right) ?: return "no_contract"
        exposureRefusal(b, c)?.let { return it }
        if (live) return enterLive(b, arm, c, signalBar, liquidity = s, near = near, volSkip = volSkip, strong = strong)
        val ltp = Paper.lastPrice(c) ?: return "refused: no quote"
        if (Strategies.stoppedToday()) return "stopped_for_today"
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", 1, ltp * 1.0005), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return "guard_refused: " + refusals.joinToString(" ")
        val buy = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null)
        val fill = filledOrCancelled(buy) ?: return "order_refused: ${if (buy.ok) "no price to fill at; the order was cancelled" else buy.message}"
        buy.orderId?.let { Strategies.tagOwner("paper:$it", "${LiquidityRules.ARM.label} · entry") }
        Notifier.orderFilled(app, "BUY", fill.quantity, fill.symbol, fill.price, "Paper", "${LiquidityRules.ARM.label} · entry", buy.orderId)
        // The owner's stop: a resting SL-M sell 15% below the fill (the book owns it, as the ORB's -40).
        val trigger = LiquidityRules.stopTrigger(fill.price)
        var stopId: String? = null
        if (trigger != null) {
            val stop = Paper.place(c, "SELL", 1, "SL-M", "MIS", null, trigger)
            if (stop.ok) { stopId = stop.orderId; stopId?.let { Strategies.tagOwner("paper:$it", "${LiquidityRules.ARM.label} · stop") } }
        }
        b.positions += Position(arm.source, c.symbol, c.right.name, fill.quantity, fill.price, now(), signalBar, buy.orderId, stopId, trigger,
            charges = chargesOf(buy.orderId), level = s.level, target = s.target, near = near, volSkip = volSkip, strong = strong)
        marks[c.symbol] = fill.price
        return "entered"
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
            val timeStop = if (!p.timed && LiquidityRules.timeStopDue(p.entryTime, t) && fresh != null) {
                LiquidityRules.timeStopFails(p.entry, fresh).also { fails -> if (!fails) { cur = p.copy(timed = true); b.positions[i] = cur } }
            } else false
            val why = when {
                LiquidityRules.indexStopHit(side, level, LiquidityRules.indexStopPoints(und), since) -> "index_stop"
                timeStop -> "time_stop"
                else -> LiquidityRules.exitReason(side, level, p.target, p.signalBar, bars, LiquidityRules.zones(bars), since)
            } ?: continue
            if (p.live) {
                // At Zerodha: the resting stop comes out first, then a MARKET sell (exitLive), in the account it entered.
                if (!Broker.loggedIn) continue
                val (done, rest) = exitLive(b, cur, cur.kite ?: continue, why)
                b.positions[i] = done
                rest?.let { b.positions += it }
                continue
            }
            val c = Paper.contractOf(p.symbol) ?: continue
            b.positions[i] = exit(cur, c, why)
        }
    }

    /**
     * TEST ONLY: earlier sessions' BANKNIFTY 1-minute bars for the liquidity levels (the feed's history). Null in the app,
     * always: [liquidityMinutes] then reads [Net.history]. Its setter throws unless BuildConfig.DEBUG; no app code sets it.
     */
    @Volatile internal var testHistoryBars: ((java.time.LocalDate) -> List<Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test history feed exists only in debug builds" }; field = v }

    /**
     * TEST ONLY: today's 1-minute bars of an index other than BANKNIFTY (FINNIFTY) at a given moment. Null in the app,
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

    /** An index's 1-minute bars (BANKNIFTY or FINNIFTY): the last ten calendar days' sessions plus today's. */
    private suspend fun liquidityMinutes(t: LocalDateTime, underlying: String = OrbRules.UNDERLYING): List<Bar> {
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
        val raw = when {
            !testing -> Net.intraday(key)
            bank -> testIndexBars?.invoke(t).orEmpty()
            else -> testOtherIndexBars?.invoke(underlying, t).orEmpty()
        }
        val today = toBars(raw).filter { it.start.toLocalDate() == day }
        return (hist + today).distinctBy { it.start }.sortedBy { it.start }.also { if (!testing) liquidityPass[underlying] = minute to it }
    }

    // ---- data ------------------------------------------------------------------

    /**
     * TEST ONLY: the BANKNIFTY 1-minute bars as the feed would return them at a given moment, so a test can run the
     * minute cycle over a whole day. Null in the app, always: [indexBars] then reads [Net.intraday], exactly as before.
     * Its setter throws unless BuildConfig.DEBUG (as [testNow]); no app code sets it.
     */
    @Volatile internal var testIndexBars: ((LocalDateTime) -> List<Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test index feed exists only in debug builds" }; field = v }

    /** Today's completed 5-minute BANKNIFTY bars, built from the 1-minute feed. */
    private suspend fun indexBars(t: LocalDateTime): List<Bar> {
        val ones = testIndexBars?.invoke(t) ?: Net.intraday(Upstox.INDEX_KEYS.getValue(OrbRules.UNDERLYING))
        return OrbRules.completed(fiveMinute(ones, t.toLocalDate(), t), t)
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
        val legs = lock.withLock { book().let { b -> if (b.replays.containsKey(day.toString())) return false; b.legs?.takeIf { it.day == day } } }
        val index = fiveMinute(Net.intraday(Upstox.INDEX_KEYS.getValue(OrbRules.UNDERLYING)), day)
        if (index.isEmpty()) return false
        val out = JSONObject().put("up", index.last().close > index.first().open)
        if (legs != null) {
            val ce = fiveMinute(Net.intraday(legs.ce.feedKey), day).associateBy { it.start }
            val pe = fiveMinute(Net.intraday(legs.pe.feedKey), day).associateBy { it.start }
            val aligned = index.filter { it.start in ce && it.start in pe }
            for (arm in OrbRules.ARMS) {
                val trades = Replay.day(arm, aligned, aligned.map { ce.getValue(it.start) }, aligned.map { pe.getValue(it.start) })
                out.put(arm.source, JSONArray().apply {
                    trades.forEach { tr -> put(JSONObject().put("bar", tr.signalBar).put("exitBar", tr.exitBar).put("right", tr.right)
                        .put("entry", tr.entry).put("exit", tr.exit).put("why", tr.why).put("pnl", tr.points * legs.ce.lotSize)) }
                })
            }
            out.put("strike", legs.strike).put("lot", legs.ce.lotSize)
        } else out.put("note", "No strike was fixed today (the arms were not running at 09:20), so there is nothing to replay the options on.")
        lock.withLock { val b = book(); b.replays[day.toString()] = out; b.upDays[day.toString()] = out.getBoolean("up"); save(b) }
        return true
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
        s == "no_decision_bar" -> "No decision bars now (entries 10:05-13:55; ORB Sweep to 14:25)."
        s == "flat_after_square_off" -> "Done for the day (square-off 15:10)."
        s == "stopped_for_today" -> DayStop.line(Strategies.stopHint() ?: DayStop.Why.BOSS)
        s == "awaiting_approval" -> if (liveNow()) "Breakout: waiting for your approval with PIN (live)." else "Breakout: waiting for your approval."
        s == "skipped_by_you" -> "You skipped the last signal."
        s == "no_contract" -> "The day's BANKNIFTY contracts could not be loaded."
        s == "no_index_data" -> "No BANKNIFTY bars yet."
        s == "no_liquidity_break" -> "Waiting for a close through a liquidity pool that sits on a swing zone."
        s == "liquidity_history_loading" -> "Loading the last days' BANKNIFTY candles for the liquidity levels."
        s == "liquidity_outside_entry_hours" -> "No new entries now (liquidity entries 09:20-14:00)."
        s == "liquidity_no_room" -> "Skipped a liquidity break: the next level ahead was closer than one index stop (30 BANKNIFTY / 15 FINNIFTY points)."
        s.startsWith("hero_") -> describeHero(s) + " ${HeroRules.NOT_PROVEN}."
        s.startsWith("guard_refused: ") -> "Refused by Bot settings: " + s.removePrefix("guard_refused: ")
        s.startsWith("refused: ") -> "Refused: " + s.removePrefix("refused: ")
        s.startsWith("order_refused: ") -> "The order was refused: " + s.removePrefix("order_refused: ")
        s.startsWith("error: ") -> "Could not check: " + s.removePrefix("error: ")
        // Boss's 06 Oct rule: the refusal as it is ("opposite_position_open: ORB Sweep holds ..."), then in words.
        com.optionslab.ira.AutoSide.refused(s) -> "$s. " + com.optionslab.ira.AutoSide.describe(s)
        else -> s
    }

    @Synchronized fun wipe() {
        cache = null; holdingHint = false; exposureHint = emptyList(); writtenText = null; writtenStat = null
        if (::file.isInitialized) file.delete()
    }
}
