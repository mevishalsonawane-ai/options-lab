package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.LiquidityShadow
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.ShadowRules
import com.optionslab.engine.orb.ShadowStudy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The shadow tracker (Boss's choice, 06 Oct 2026): beside ORB, ORB Fresh, ORB Sweep and Range Fade ([com.optionslab.engine.orb.RetiredArms]; un-retired 07 Oct, research rows here) the
 * research's best variant, and one new candidate, run on the app's live index and option prices - recording what each
 * WOULD have done, never placing an order (paper or live). The rules are [ShadowRules]'s (pinned there); entries and exits
 * are priced at the option's LTP with the paper account's fills and the real charges. Nothing here calls [Paper.place],
 * [Broker] or any order route - until Boss says yes to re-arming one ([promote]), and then on the PAPER account only.
 *
 * Runs inside the market watch's pass ([tick], after the money steps), only on trading days from 09:20 to 15:12, and only
 * reads what a decision needs: the index's 1-minute candles once a minute while a rule can still decide or a position
 * reads the index; the option's price while a shadow holds it; the chain's OI only on a signal (21 strikes once a day for
 * V43's PCR, 11 for an OI wall); NIFTY's daily candles once a day for O08's ATR. Its book is its own encrypted file, kept
 * on this phone only (not in a backup, like the other trading state no restore may bring back), and survives restarts.
 *
 * The losing-trades study's shadows ([ShadowStudy], Boss's 06 Oct "enhance the shadows") are priced and walked on the
 * option's own 1-minute candles, as the study replayed them: a decision waits as a [Pull] until the option's candles price it
 * (a MARKET buy at the decision minute's open, or a LIMIT 10 under it filled within 15 minutes), then each completed candle
 * is walked for the stop, the ladder and the target. Every shadow trade keeps its best and worst move while held and why it
 * lost ([ShadowStudy.lossGroup]); from 15:10 one candle read per option settles the move after the exit. Liquidity 15+5's
 * candidates (d) and (e) ([LiquidityShadow]) are priced here too, on its own paper trades, without touching them: (d) the
 * trade's option at the first look from 14:30, (e) the 2-ITM option's price at the trade's entry and at its exit.
 */
object ShadowArms {
    private lateinit var file: File
    private val lock = Mutex()

    fun init(context: Context) {
        file = File(context.applicationContext.noBackupFilesDir, "shadow.vault")
    }

    // ---- state ----------------------------------------------------------------------------------------------------

    /** One shadow trade. [paper]: placed on the paper account after Boss's yes ([promote]); false: virtual, no order at all. */
    data class Trade(
        val variant: String, val symbol: String, val feedKey: String, val underlying: String, val expiry: LocalDate, val strike: Int,
        val right: String, val qty: Int, val entry: Double, val entryTime: LocalDateTime, val signalBar: LocalDateTime,
        val extreme: Double? = null, val mid: Double? = null, val indexStop: Double? = null, val peak: Double = entry,
        /** The last price seen (an exit when the app was away at its time). */
        val last: Double? = null,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0,
        val paper: Boolean = false, val orderId: String? = null,
        /**
         * The best and worst move from [entry] while held, in premium points ([mfe] / [mae]); [trough] the lowest price seen
         * (the LTP shadows'); [dayMfe] the best move from the entry to 15:10 (after the exit too; null: not read); [group] why it
         * lost ([ShadowStudy.LossGroup] name; null: not a loser); [walked] the last candle the study's walk read; [settled]: the
         * 15:10 read is done.
         */
        val mfe: Double? = null, val mae: Double? = null, val trough: Double? = null, val dayMfe: Double? = null,
        val group: String? = null, val walked: LocalDateTime? = null, val settled: Boolean = false,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        val net: Double? get() = exit?.let { (it - entry) * qty - charges }
    }

    /** A study shadow's decision waiting for the option's candles to price it ([ShadowStudy.enter]); [from]: the decision minute. */
    data class Pull(val variant: String, val symbol: String, val feedKey: String, val underlying: String, val expiry: LocalDate, val strike: Int,
                    val right: String, val qty: Int, val signalBar: LocalDateTime, val from: LocalDateTime)

    /**
     * Liquidity 15+5's candidates (d) and (e) on one of its paper trades: (e)'s 2-ITM option ([symbol2], [strike2]) bought at
     * [entry2] and sold at [exit2] (the paper fills on its price at the trade's entry and exit; null: not priced), and (d)'s
     * sell of the trade's own option from 14:30 ([at1430]).
     */
    data class LiqAlt(val symbol2: String?, val feedKey2: String?, val underlying: String, val expiry: LocalDate, val strike2: Int, val right: String,
                      val qty: Int, val entry2: Double? = null, val exit2: Double? = null, val at1430: Double? = null)

    /** What Liquidity 15+5's row reads of a trade: (d)'s sell price from 14:30 and (e)'s net on the 2-ITM option. */
    data class LiqHint(val at1430: Double?, val itm2: Double?)

    private data class Book(
        var since: LocalDate? = null,
        val trades: MutableList<Trade> = ArrayList(),
        /** "variant|day" -> the bars (or "12:00") decided. */
        val decided: MutableMap<String, MutableSet<String>> = HashMap(),
        val status: MutableMap<String, String> = HashMap(),
        /** day -> V43's PCR at 10:00 (read once a day). */
        val pcr: MutableMap<String, Double> = HashMap(),
        /** day -> NIFTY's ATR14 (O08). */
        val atr: MutableMap<String, Double> = HashMap(),
        /** variant -> the day Boss said yes to re-arming it on paper. */
        val promoted: MutableMap<String, String> = HashMap(),
        /** variant -> re-armed on paper now (Boss can switch it off again). */
        val armed: MutableMap<String, Boolean> = HashMap(),
        /** The variants Jarvis has put to Boss (one request each, ever). */
        val asked: MutableSet<String> = HashSet(),
        /** The study shadows' decisions waiting for their price. */
        val pulls: MutableList<Pull> = ArrayList(),
        /** Liquidity 15+5's trades ([liqKey]) -> candidates (d) and (e). */
        val liq: MutableMap<String, LiqAlt> = LinkedHashMap(),
    )

    private var cache: Book? = null

    private fun d(o: JSONObject, k: String): Double? = if (o.has(k)) o.getDouble(k) else null

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        val ok = runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            o.optString("since").ifEmpty { null }?.let { b.since = LocalDate.parse(it) }
            o.optJSONArray("trades")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    b.trades += Trade(p.getString("variant"), p.getString("symbol"), p.getString("feedKey"), p.getString("underlying"),
                        LocalDate.parse(p.getString("expiry")), p.getInt("strike"), p.getString("right"), p.getInt("qty"), p.getDouble("entry"),
                        LocalDateTime.parse(p.getString("entryTime")), LocalDateTime.parse(p.getString("signalBar")),
                        d(p, "extreme"), d(p, "mid"), d(p, "indexStop"), p.optDouble("peak", p.getDouble("entry")), d(p, "last"),
                        d(p, "exit"), p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) }, p.optString("why").ifEmpty { null },
                        p.optDouble("charges", 0.0), p.optBoolean("paper", false), p.optString("orderId").ifEmpty { null },
                        d(p, "mfe"), d(p, "mae"), d(p, "trough"), d(p, "dayMfe"), p.optString("group").ifEmpty { null },
                        p.optString("walked").ifEmpty { null }?.let { LocalDateTime.parse(it) }, p.optBoolean("settled", false))
                }
            }
            o.optJSONArray("pulls")?.let { a ->
                for (i in 0 until a.length()) {
                    val q = a.getJSONObject(i)
                    b.pulls += Pull(q.getString("variant"), q.getString("symbol"), q.getString("feedKey"), q.getString("underlying"),
                        LocalDate.parse(q.getString("expiry")), q.getInt("strike"), q.getString("right"), q.getInt("qty"),
                        LocalDateTime.parse(q.getString("signalBar")), LocalDateTime.parse(q.getString("from")))
                }
            }
            o.optJSONObject("liq")?.let { m -> m.keys().forEach { k -> val q = m.getJSONObject(k)
                b.liq[k] = LiqAlt(q.optString("symbol2").ifEmpty { null }, q.optString("feedKey2").ifEmpty { null }, q.getString("underlying"),
                    LocalDate.parse(q.getString("expiry")), q.getInt("strike2"), q.getString("right"), q.getInt("qty"),
                    d(q, "entry2"), d(q, "exit2"), d(q, "at1430"))
            } }
            o.optJSONObject("decided")?.let { m -> m.keys().forEach { k -> val a = m.getJSONArray(k); b.decided[k] = (0 until a.length()).map { a.getString(it) }.toMutableSet() } }
            o.optJSONObject("status")?.let { m -> m.keys().forEach { b.status[it] = m.getString(it) } }
            o.optJSONObject("pcr")?.let { m -> m.keys().forEach { b.pcr[it] = m.getDouble(it) } }
            o.optJSONObject("atr")?.let { m -> m.keys().forEach { b.atr[it] = m.getDouble(it) } }
            o.optJSONObject("promoted")?.let { m -> m.keys().forEach { b.promoted[it] = m.getString(it) } }
            o.optJSONObject("armed")?.let { m -> m.keys().forEach { b.armed[it] = m.getBoolean(it) } }
            o.optJSONArray("asked")?.let { a -> for (i in 0 until a.length()) b.asked += a.getString(i) }
        }.isSuccess
        // Never overwrite what could not be read: set it aside and start a fresh record.
        if (!ok && file.exists()) runCatching { Vault.setAside(file) }
        cache = b
        hints(b)
        return b
    }

    /** What this process last wrote to the book's file: an unchanged save is not encrypted and synced again ([Vault.LastWrite]). */
    private val written = Vault.LastWrite()

    private fun save(b: Book) {
        val o = JSONObject()
        b.since?.let { o.put("since", it.toString()) }
        o.put("trades", JSONArray().apply {
            b.trades.takeLast(3000).forEach { p ->
                put(JSONObject().put("variant", p.variant).put("symbol", p.symbol).put("feedKey", p.feedKey).put("underlying", p.underlying)
                    .put("expiry", p.expiry.toString()).put("strike", p.strike).put("right", p.right).put("qty", p.qty).put("entry", p.entry)
                    .put("entryTime", p.entryTime.toString()).put("signalBar", p.signalBar.toString())
                    .apply { p.extreme?.let { put("extreme", it) }; p.mid?.let { put("mid", it) }; p.indexStop?.let { put("indexStop", it) } }
                    .put("peak", p.peak).apply { p.last?.let { put("last", it) }; p.exit?.let { put("exit", it) } }
                    .put("exitTime", p.exitTime?.toString() ?: "").put("why", p.why ?: "").put("charges", p.charges)
                    .put("paper", p.paper).put("orderId", p.orderId ?: "")
                    .apply { p.mfe?.let { put("mfe", it) }; p.mae?.let { put("mae", it) }; p.trough?.let { put("trough", it) }
                        p.dayMfe?.let { put("dayMfe", it) }; p.group?.let { put("group", it) }; p.walked?.let { put("walked", it.toString()) }
                        if (p.settled) put("settled", true) })
            }
        })
        o.put("pulls", JSONArray().apply {
            b.pulls.forEach { q ->
                put(JSONObject().put("variant", q.variant).put("symbol", q.symbol).put("feedKey", q.feedKey).put("underlying", q.underlying)
                    .put("expiry", q.expiry.toString()).put("strike", q.strike).put("right", q.right).put("qty", q.qty)
                    .put("signalBar", q.signalBar.toString()).put("from", q.from.toString()))
            }
        })
        o.put("liq", JSONObject().apply {
            b.liq.entries.toList().takeLast(3000).forEach { (k, q) ->
                put(k, JSONObject().put("symbol2", q.symbol2 ?: "").put("feedKey2", q.feedKey2 ?: "").put("underlying", q.underlying)
                    .put("expiry", q.expiry.toString()).put("strike2", q.strike2).put("right", q.right).put("qty", q.qty)
                    .apply { q.entry2?.let { put("entry2", it) }; q.exit2?.let { put("exit2", it) }; q.at1430?.let { put("at1430", it) } })
            }
        })
        val day = today().toString()
        o.put("decided", JSONObject().apply { b.decided.filterKeys { it.endsWith(day) }.forEach { (k, v) -> put(k, JSONArray(v.toList())) } })
        o.put("status", JSONObject(b.status as Map<*, *>))
        o.put("pcr", JSONObject().apply { b.pcr.filterKeys { it == day }.forEach { (k, v) -> put(k, v) } })
        o.put("atr", JSONObject().apply { b.atr.filterKeys { it == day }.forEach { (k, v) -> put(k, v) } })
        o.put("promoted", JSONObject(b.promoted as Map<*, *>))
        o.put("armed", JSONObject(b.armed as Map<*, *>))
        o.put("asked", JSONArray(b.asked.sorted()))
        written.write(file, o.toString().toByteArray(Charsets.UTF_8))
        cache = b
        hints(b)
    }

    // ---- the clock and the feeds (tests set them; the app never does) ---------------------------------------------

    /** TEST ONLY: a fixed clock. Null in the app, always ([Market.now]); its setter throws unless BuildConfig.DEBUG. */
    @Volatile internal var testNow: java.time.ZonedDateTime? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }; field = v }

    /**
     * TEST ONLY: a feed key's 1-minute candles (the index, an option's for its OI) at a moment, and an index's daily
     * candles. Null in the app, always: [Net.intraday] and [Net.daily] are read. Setters throw unless BuildConfig.DEBUG.
     */
    @Volatile internal var testFeed: ((String, LocalDateTime) -> List<Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v }
    @Volatile internal var testDaily: ((String) -> List<Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v }

    private fun now(): LocalDateTime = (testNow ?: Market.now()).toLocalDateTime()
    private fun today(): LocalDate = testNow?.toLocalDate() ?: Market.today()

    private suspend fun intraday(key: String, t: LocalDateTime): List<Upstox.Bar> = testFeed?.invoke(key, t) ?: Net.intraday(key)

    private fun toBars(raw: List<Upstox.Bar>, day: LocalDate): List<Bar> = raw.filter { it.istDate == day }.map {
        Bar(java.time.Instant.ofEpochSecond(it.epochSecond).atZone(com.optionslab.engine.IST).toLocalDateTime(), it.open, it.high, it.low, it.close)
    }.filter { val m = it.start.hour * 60 + it.start.minute; m in (9 * 60 + 15)..(15 * 60 + 29) }.distinctBy { it.start }.sortedBy { it.start }

    /** The index's 1-minute bars read on this pass's minute (read once a minute per index). */
    private val minuteCache = java.util.concurrent.ConcurrentHashMap<String, Pair<LocalDateTime, List<Bar>>>()

    private suspend fun minutes(underlying: String, t: LocalDateTime): List<Bar> {
        val minute = t.withSecond(0).withNano(0)
        if (testFeed == null) minuteCache[underlying]?.let { if (it.first == minute) return it.second }
        val key = Upstox.INDEX_KEYS[underlying] ?: LiquidityRules.INDEX_KEYS.getValue(underlying)
        // Battery: Liquidity 15+5's read of this minute (the same candles) when it made one; else one read of our own.
        val shared = if (testFeed == null) OrbArms.minutesReadThisMinute(underlying, t) else null
        return (shared ?: toBars(intraday(key, t), t.toLocalDate())).filter { !it.start.plusMinutes(1).isAfter(t) }
            .also { if (testFeed == null) minuteCache[underlying] = minute to it }
    }

    /** Completed 5-minute bars from the 1-minute ones; a bar missing its last minute waits two minutes after it ends. */
    private fun fiveMinute(ones: List<Bar>, t: LocalDateTime): List<Bar> {
        val have = ones.mapTo(HashSet()) { it.start }
        return LiquidityRules.completed(LiquidityRules.fold(ones, 5), 5, t).filter { b ->
            !t.isBefore(b.start.plusMinutes(7)) || b.start.plusMinutes(4) in have
        }
    }

    /**
     * The option's price: the stream's tick, or a candle price read in the last 50 s (the candle feed moves once a minute;
     * a shadow never needs a second download of the same minute), else a fresh read.
     */
    private suspend fun price(c: Paper.Contract): Double? = runCatching { Paper.recentQuote(c, 50_000L) }.getOrNull()?.ltp?.takeIf { it > 0 }

    private fun contractOf(p: Trade): Paper.Contract =
        Paper.contractOf(p.symbol) ?: Paper.contractFor(p.underlying, p.expiry, p.strike.toDouble(), Right.valueOf(p.right))
            ?: Paper.Contract(p.symbol, p.underlying, p.expiry, p.strike.toDouble(), Right.valueOf(p.right), p.qty, p.feedKey)

    private fun listed(underlying: String) = Market.contracts().filter { it.underlying == underlying }

    // ---- the pass -----------------------------------------------------------------------------------------------------

    /**
     * One pass of the market watch: each open shadow position checked against its exits, then each rule's decision on the
     * latest completed bar (once a bar). Virtual: no order - unless Boss re-armed that variant on paper ([promote]).
     */
    suspend fun tick() {
        if (!::file.isInitialized || com.optionslab.app.BuildConfig.GOLD) return
        val t = now()
        if (!Market.isTradingDay(t.toLocalDate())) return
        val tt = t.toLocalTime()
        if (tt.isBefore(OrbRules.WINDOW_FROM) || tt.isAfter(OrbRules.WINDOW_UNTIL)) return
        // Liquidity 15+5's paper trades of today, read before this lock (the arms' pass has run: the same minute's state).
        val liquidity = runCatching { OrbArms.liquidityToday(t.toLocalDate()) }.getOrDefault(emptyList())
        lock.withLock {
            val b = book()
            var changed = false
            if (b.since == null) { b.since = t.toLocalDate(); changed = true }
            changed = runCatching { exits(b, t) }.getOrDefault(false) or changed
            changed = runCatching { pulls(b, t) }.getOrDefault(false) or changed
            changed = runCatching { entries(b, t) }.getOrDefault(false) or changed
            changed = runCatching { settle(b, t) }.getOrDefault(false) or changed
            changed = runCatching { liquidityCandidates(b, t, liquidity) }.getOrDefault(false) or changed
            if (changed) save(b)
        }
    }

    private fun shadowOpen(p: Trade) = ShadowRules.Open(p.variant, if (p.right == "CE") 1 else -1, p.entry, p.entryTime, p.peak, p.extreme, p.mid, p.indexStop)

    /** A trade closed at [fill] ([why], at [at]): its charges, its move while held (the LTP's when no candle walked it), why it lost. */
    private fun closed(p: Trade, why: String, fill: Double, at: LocalDateTime, last: Double?): Trade =
        grouped(p.copy(exit = fill, exitTime = at, why = why, last = last ?: p.last, charges = ShadowRules.charges(p.entry, fill, p.qty)))

    /** [p] with its move while held filled in from the prices seen (when no candle walk set it) and why it lost. */
    private fun grouped(p: Trade): Trade {
        val q = p.copy(mfe = p.mfe ?: (p.peak - p.entry), mae = p.mae ?: p.trough?.let { p.entry - it })
        val x = q.exit ?: return q
        return q.copy(group = ShadowStudy.lossGroup(q.entry, x, q.qty, q.charges, q.why, q.mfe, q.dayMfe)?.name)
    }

    /** The option's 1-minute candles of [t]'s day (a study shadow's price and walk, the 15:10 settle); null when not read. */
    private suspend fun candles(feedKey: String, t: LocalDateTime): List<Bar>? = runCatching { toBars(intraday(feedKey, t), t.toLocalDate()) }.getOrNull()

    private suspend fun exits(b: Book, t: LocalDateTime): Boolean {
        var changed = false
        for ((i, p0) in b.trades.withIndex().filter { it.value.open }) {
            var p = p0
            val c = contractOf(p)
            val rule = ShadowStudy.ruleOf(p.variant)
            // A study shadow: its exits walked on the option's completed candles since the last one read.
            if (rule != null && !p.day.isBefore(t.toLocalDate())) {
                val opt = candles(p.feedKey, t)
                if (opt != null) {
                    val s = ShadowStudy.walk(rule, ShadowStudy.Walk(p.entry, p.entryTime.withSecond(0).withNano(0), p.peak, p.mfe, p.mae, p.walked), opt, t)
                    p = p.copy(peak = s.walk.peak, mfe = s.walk.mfe, mae = s.walk.mae, walked = s.walk.last)
                    val why = s.why
                    val fill = s.exit
                    if (why != null && fill != null) {
                        b.trades[i] = if (p.paper) exitPaper(p, c, why, fill)?.let { grouped(it) } ?: p0
                            else closed(p, why, fill, s.exitAt ?: t, fill)
                        changed = true
                        continue
                    }
                    if (p != p0) { b.trades[i] = p; changed = true }
                }
            }
            val ltp = price(c)
            // Exits that read the index (S17's structure, O08's stop) get its minutes; the others never read it.
            val ones = if (p.variant == ShadowRules.SWEEP_S17.id || p.variant == ShadowRules.MOMO_O08.id) runCatching { minutes(p.underlying, t) }.getOrDefault(emptyList()) else emptyList()
            // Left open from an earlier day (the app was away at its exit): closed at the last price it saw.
            val away = p.day.isBefore(t.toLocalDate())
            val why = if (away) "app_away" else ShadowRules.exitReason(shadowOpen(p), ltp, t, ones)
            if (why == null) {
                // The LTP's best and worst (a study shadow's peak is its candles' highs: only its last price is kept).
                val seen = if (ltp == null) p else if (rule != null) p.copy(last = ltp)
                    else p.copy(last = ltp, peak = maxOf(p.peak, ltp), trough = minOf(p.trough ?: p.entry, ltp))
                if (seen != p0) { b.trades[i] = seen; changed = true }
                continue
            }
            val px = (if (away) p.last else ltp) ?: if (away) p.entry else continue
            b.trades[i] = if (p.paper) exitPaper(p, c, why, px)?.let { grouped(it) } ?: continue
            else closed(p, why, ShadowRules.exitFill(why, p.entry, px), t, px)
            changed = true
        }
        return changed
    }

    private suspend fun entries(b: Book, t: LocalDateTime): Boolean {
        val day = t.toLocalDate()
        val tt = t.toLocalTime()
        var changed = false
        fun held(v: ShadowRules.Variant) = b.trades.any { it.variant == v.id && it.open }
        fun todays(v: ShadowRules.Variant) = b.trades.filter { it.variant == v.id && it.day == day }
        // BANKNIFTY: V43 (one a day, bars to 13:55), S17 (two, to 14:25), R20 (two, 12:00-13:55); decided on 5-minute bars.
        // The study's five ([ShadowStudy]): their own day caps, decided on the same bars.
        val study = ShadowRules.ALL.mapNotNull { v -> ShadowStudy.ruleOf(v.id)?.let { v to it.maxEntries } }
        val bank = (listOf(ShadowRules.ORB_V43 to 1, ShadowRules.SWEEP_S17 to 2, ShadowRules.FADE_R20 to 2) + study)
            .filter { (v, max) -> !held(v) && todays(v).size < max }.map { it.first }
        if (bank.isNotEmpty() && !tt.isBefore(LocalTime.of(10, 5)) && tt.isBefore(LocalTime.of(14, 32))) {
            val bars = fiveMinute(minutes(OrbRules.UNDERLYING, t), t)
            for (v in bank) changed = runCatching { decideBank(b, v, bars, t) }.getOrDefault(false) or changed
        }
        // NIFTY at 12:00: O08, from 12:00 to 12:03, once a day.
        val momo = ShadowRules.MOMO_O08
        val momoKey = "${momo.id}|$day"
        if (!held(momo) && b.decided[momoKey].isNullOrEmpty() && !tt.isBefore(ShadowRules.MOMO_AT) && !tt.isAfter(ShadowRules.MOMO_LAST_ENTRY.plusSeconds(59))) {
            changed = runCatching { decideMomo(b, t) }.getOrDefault(false) or changed
        }
        return changed
    }

    private suspend fun decideBank(b: Book, v: ShadowRules.Variant, bars: List<Bar>, t: LocalDateTime): Boolean {
        val day = t.toLocalDate()
        val last = bars.lastOrNull()?.takeIf { it.start.toLocalDate() == day } ?: return false
        if (!b.decided.getOrPut("${v.id}|$day") { HashSet() }.add(last.start.toString())) return false
        val today = b.trades.filter { it.variant == v.id && it.day == day }
        val listed = listed(OrbRules.UNDERLYING)
        val expiry = OrbRules.expiryOnOrAfter(day, listed.map { it.expiry }.distinct())
        val rule = ShadowStudy.ruleOf(v.id)
        if (rule != null) {
            val lastExit = today.mapNotNull { it.exitTime }.maxOrNull()
            val sd = ShadowStudy.decide(rule, bars, minutes(OrbRules.UNDERLYING, t), today.size, lastExit)
            val se = sd.entry
            b.status[v.id] = if (se == null) sd.why else pull(b, v, se, expiry)
            return true
        }
        val d = when (v) {
            ShadowRules.ORB_V43 -> {
                val first = ShadowRules.orbEntry(bars, today.size, b.pcr[day.toString()])
                // The PCR is read only once a fresh break needs it (once a day: 21 strikes either right, their OI before 10:00).
                if (first.why == "no_pcr" && expiry != null) {
                    readPcr(day, dayStrike(bars), expiry, t)?.let { b.pcr[day.toString()] = it }
                    ShadowRules.orbEntry(bars, today.size, b.pcr[day.toString()])
                } else first
            }
            else -> {
                val lastExit = today.mapNotNull { it.exitTime }.maxOrNull()
                val s = ShadowRules.edgeSignal(v == ShadowRules.SWEEP_S17, bars, today.size, lastExit)
                val e = s.entry
                if (e == null || expiry == null) s
                else {
                    val rng = OrbRules.openingRange(bars)!!
                    val oi = readOi(listed, expiry, ShadowRules.wallRight(e.side), ShadowRules.wallStrikes(e.side, e.strike), e.signalBar.plusMinutes(5), t)
                    if (ShadowRules.oiWall(e.side, rng, oi)) s else ShadowRules.Decision(null, "no_oi_wall")
                }
            }
        }
        val e = d.entry
        b.status[v.id] = if (e == null) d.why else enter(b, v, e, expiry, t)
        return true
    }

    /** V43's ATM for the PCR read: the strike its decision takes (the 09:20 bar's; there is one once the range is in). */
    private fun dayStrike(bars: List<Bar>): Int =
        OrbRules.atmStrike(bars.first { !it.start.toLocalTime().isBefore(OrbRules.STRIKE_BAR) }.close)

    private suspend fun decideMomo(b: Book, t: LocalDateTime): Boolean {
        val day = t.toLocalDate()
        val v = ShadowRules.MOMO_O08
        val key = day.toString()
        val atr = b.atr[key] ?: readAtr(day)?.also { b.atr[key] = it }
        val d = ShadowRules.momoEntry(minutes(v.underlying, t), atr, t)
        // Still to come this minute or the next: the 11:59 candle, or a daily read that failed (tried again on the next pass).
        if (d.entry == null && (d.why == "waiting_for_1159" || d.why == "no_atr")) { b.status[v.id] = d.why; return atr != null }
        b.decided.getOrPut("${v.id}|$day") { HashSet() }.add(ShadowRules.MOMO_AT.toString())
        val expiry = OrbRules.expiryOnOrAfter(day, listed(v.underlying).map { it.expiry }.distinct())
        b.status[v.id] = d.entry?.let { enter(b, v, it, expiry, t) } ?: d.why
        return true
    }

    /** A study shadow's decision: kept until the option's candles price it ([pulls]). */
    private fun pull(b: Book, v: ShadowRules.Variant, e: ShadowRules.Entry, expiry: LocalDate?): String {
        expiry ?: return "no_contract"
        val right = if (e.side > 0) Right.CE else Right.PE
        val c = Paper.contractFor(v.underlying, expiry, e.strike.toDouble(), right) ?: return "no_contract"
        b.pulls += Pull(v.id, c.symbol, c.feedKey, c.underlying, c.expiry, e.strike, right.name, c.lotSize, e.signalBar, e.signalBar.plusMinutes(5))
        return "pricing"
    }

    /**
     * The study shadows' decisions priced on the option's candles, each variant's earliest first (as the study priced each
     * signal before it looked at the next): filled, it is the trade (and the later ones go); unfilled or refused, the next.
     */
    private suspend fun pulls(b: Book, t: LocalDateTime): Boolean {
        if (b.pulls.isEmpty()) return false
        val day = t.toLocalDate()
        var changed = b.pulls.removeAll { it.from.toLocalDate() != day || ShadowStudy.ruleOf(it.variant) == null }
        for (id in b.pulls.map { it.variant }.distinct()) {
            val v = ShadowRules.of(id) ?: continue
            val rule = ShadowStudy.ruleOf(id) ?: continue
            while (true) {
                val q = b.pulls.filter { it.variant == id }.minByOrNull { it.from } ?: break
                // Held, or the day's cap reached: the later decisions go.
                val mine = b.trades.filter { it.variant == id }
                if (mine.any { it.open } || mine.count { it.day == day } >= rule.maxEntries) { b.pulls.remove(q); changed = true; continue }
                val opt = candles(q.feedKey, t) ?: break
                val r = ShadowStudy.enter(rule, opt, q.from, t)
                if (r.state == "waiting") break
                b.pulls.remove(q); changed = true
                val at = r.at
                val px = r.price
                b.status[id] = if (r.state == "filled" && at != null && px != null) record(b, v, q, px, at) else r.why
            }
        }
        return changed
    }

    /** A priced study shadow's entry: virtual, or placed on paper when re-armed. */
    private suspend fun record(b: Book, v: ShadowRules.Variant, q: Pull, fill: Double, at: LocalDateTime): String {
        val right = Right.valueOf(q.right)
        val base = Trade(v.id, q.symbol, q.feedKey, q.underlying, q.expiry, q.strike, q.right, q.qty, fill, at, q.signalBar, peak = fill, last = fill)
        if (b.armed[v.id] == true) {
            val c = Paper.contractFor(q.underlying, q.expiry, q.strike.toDouble(), right)
                ?: Paper.Contract(q.symbol, q.underlying, q.expiry, q.strike.toDouble(), right, q.qty, q.feedKey)
            val placed = enterPaper(v, c, base) ?: return "paper_entry_refused"
            b.trades += placed
            return "entered_on_paper"
        }
        b.trades += base
        return "entered_virtual"
    }

    /**
     * From 15:10, once a day: each of today's closed shadow trades read on its option's candles - its best and worst move
     * while held (the LTP shadows'; the study's walk has them already) and its best move to 15:10, which tells "wrong way from
     * the start" from "stopped, then it turned". A trade never settled keeps what its prices showed.
     */
    private suspend fun settle(b: Book, t: LocalDateTime): Boolean {
        if (t.toLocalTime().isBefore(OrbRules.SQUARE_OFF)) return false
        val day = t.toLocalDate()
        val due = b.trades.withIndex().filter { (_, p) -> !p.open && !p.settled && p.day == day && p.exitTime != null }
        if (due.isEmpty()) return false
        var changed = false
        val read = HashMap<String, List<Bar>?>()
        for ((i, p) in due) {
            val opt = read.getOrPut(p.feedKey) { candles(p.feedKey, t) } ?: continue
            val x = ShadowStudy.excursion(p.entry, p.entryTime, p.exitTime ?: continue, opt)
            val study = ShadowStudy.ruleOf(p.variant) != null
            b.trades[i] = grouped(p.copy(mfe = if (study) p.mfe ?: x.mfe else x.mfe ?: p.mfe, mae = if (study) p.mae ?: x.mae else x.mae ?: p.mae,
                dayMfe = x.dayMfe, settled = true))
            changed = true
        }
        return changed
    }

    // ---- Liquidity 15+5's candidates (d) and (e): its own paper trades, priced beside them --------------------------

    /** The key of a Liquidity 15+5 trade in the record (and [liquidityHint]). */
    fun liqKey(arm: String, entryTime: LocalDateTime, symbol: String): String = "$arm|$entryTime|$symbol"

    /**
     * Each of today's Liquidity 15+5 paper trades [positions]: on first sight, (e)'s 2-ITM option priced (only while the trade
     * is still open: one first seen closed is not counted); held from 14:30, (d)'s sell of its own option priced; closed, (e)'s
     * 2-ITM option sold at its price. Never an order: what Liquidity trades is not touched.
     */
    private suspend fun liquidityCandidates(b: Book, t: LocalDateTime, positions: List<OrbArms.Position>): Boolean {
        var changed = false
        for (p in positions) {
            if (p.live || p.unconfirmed) continue
            val key = liqKey(p.arm, p.entryTime, p.symbol)
            var a = b.liq[key]
            if (a == null) {
                val c1 = Paper.contractOf(p.symbol) ?: continue
                val side = if (p.right == "CE") 1 else -1
                val k2 = LiquidityShadow.itm2Strike(side, c1.strike.toInt(), c1.underlying)
                val c2 = Paper.contractFor(c1.underlying, c1.expiry, k2.toDouble(), c1.right)
                val e2 = if (p.open && c2 != null) price(c2)?.let { ShadowRules.entryFill(it) } else null
                a = LiqAlt(c2?.symbol, c2?.feedKey, c1.underlying, c1.expiry, k2, p.right, p.qty, entry2 = e2)
                b.liq[key] = a
                changed = true
            }
            if (p.open && a.at1430 == null && LiquidityShadow.exitAllDue(t)) {
                val ltp = Paper.contractOf(p.symbol)?.let { price(it) }
                if (ltp != null) { a = a.copy(at1430 = ShadowRules.exitFill("time_exit", p.entry, ltp)); b.liq[key] = a; changed = true }
            }
            val e2 = a.entry2
            val s2 = a.symbol2
            if (!p.open && e2 != null && a.exit2 == null && s2 != null) {
                val c2 = Paper.contractOf(s2) ?: Paper.contractFor(a.underlying, a.expiry, a.strike2.toDouble(), Right.valueOf(a.right))
                val ltp = c2?.let { price(it) }
                if (ltp != null) { b.liq[key] = a.copy(exit2 = ShadowRules.exitFill("exit", e2, ltp)); changed = true }
            }
        }
        return changed
    }

    /** Records the entry at the option's LTP and the paper fill (virtual), or places it on paper when re-armed. */
    private suspend fun enter(b: Book, v: ShadowRules.Variant, e: ShadowRules.Entry, expiry: LocalDate?, t: LocalDateTime): String {
        expiry ?: return "no_contract"
        val right = if (e.side > 0) Right.CE else Right.PE
        val c = Paper.contractFor(v.underlying, expiry, e.strike.toDouble(), right) ?: return "no_contract"
        val ltp = price(c) ?: return "no_quote"
        val fill = ShadowRules.entryFill(ltp)
        if (ShadowRules.refused(fill)) return "refused_premium_at_or_under_40"
        val base = Trade(v.id, c.symbol, c.feedKey, c.underlying, c.expiry, e.strike, right.name, c.lotSize, fill, t, e.signalBar,
            e.extreme, e.mid, e.indexStop, fill, ltp)
        if (b.armed[v.id] == true) {
            val placed = enterPaper(v, c, base) ?: return "paper_entry_refused"
            b.trades += placed
            return "entered_on_paper"
        }
        b.trades += base
        return "entered_virtual"
    }

    // ---- the chain's OI (read only on a signal) ---------------------------------------------------------------

    /** Each strike's OI on [right] as of the last minute before [cutoff] (strike -> OI); strikes not listed or not read are left out. */
    private suspend fun readOi(listed: List<Upstox.Contract>, expiry: LocalDate, right: String, strikes: List<Int>, cutoff: LocalDateTime,
                               t: LocalDateTime): Map<Int, Long> = coroutineScope {
        strikes.mapNotNull { k -> listed.firstOrNull { it.expiry == expiry && it.strike == k.toDouble() && it.right.name == right }?.let { k to it } }
            .map { (k, c) -> async {
                runCatching { intraday(c.instrumentKey, t) }.getOrNull()?.let { raw ->
                    ShadowRules.oiBefore(raw.filter { it.istDate == cutoff.toLocalDate() }.map {
                        java.time.Instant.ofEpochSecond(it.epochSecond).atZone(com.optionslab.engine.IST).toLocalDateTime() to it.oi
                    }, cutoff)?.let { k to it }
                }
            } }.awaitAll().filterNotNull().toMap()
    }

    /** V43's PCR at 10:00 over ATM-10..ATM+10, or null when too little was read (tried again on the next bar). */
    private suspend fun readPcr(day: LocalDate, atm: Int, expiry: LocalDate, t: LocalDateTime): Double? {
        val listed = listed(OrbRules.UNDERLYING)
        val strikes = ShadowRules.pcrStrikes(atm)
        val cutoff = day.atTime(ShadowRules.PCR_AT)
        val ce = readOi(listed, expiry, "CE", strikes, cutoff, t)
        val pe = readOi(listed, expiry, "PE", strikes, cutoff, t)
        if (ce.size + pe.size < strikes.size) return null
        return ShadowRules.pcr(ce, pe)
    }

    /** NIFTY's ATR14 for [day] from its daily candles (read once a day). */
    private suspend fun readAtr(day: LocalDate): Double? = runCatching {
        val key = Upstox.INDEX_KEYS.getValue(ShadowRules.MOMO_O08.underlying)
        val raw = testDaily?.invoke(key) ?: Net.daily(key, day.minusDays(45), day.minusDays(1))
        ShadowRules.atr14(raw.map { Bar(it.istDate.atStartOfDay(), it.open, it.high, it.low, it.close) }, day)
    }.getOrNull()

    // ---- re-armed on paper (only after Boss's yes) -------------------------------------------------------------

    /** The open paper positions of a re-armed variant, for the one-index-one-side rule ([AutoExposure]); read without a lock. */
    @Volatile var exposureHint: List<com.optionslab.ira.AutoSide.Held> = emptyList()
        private set

    /** The record's rows, as of the last load or save (the screen reads them without waiting on a pass). */
    @Volatile private var rowsHint: List<Row> = emptyList()

    /** Liquidity 15+5's trades ([liqKey]) -> candidates (d) and (e) as priced, as of the last load or save; read without a lock. */
    @Volatile var liquidityHint: Map<String, LiqHint> = emptyMap()
        private set

    private fun hints(b: Book) {
        liquidityHint = b.liq.mapValues { (_, a) ->
            val e2 = a.entry2
            val x2 = a.exit2
            LiqHint(a.at1430, if (e2 != null && x2 != null) LiquidityShadow.net(e2, x2, a.qty) else null)
        }
        exposureHint = b.trades.filter { it.open && it.paper }.map {
            com.optionslab.ira.AutoSide.Held.option("${ShadowRules.of(it.variant)?.let { v -> ShadowRules.armName(v) } ?: it.variant} (shadow)",
                it.symbol, it.underlying, it.right, long = true,
                rank = ShadowRules.of(it.variant)?.let { v -> com.optionslab.engine.orb.ArmPriority.rankOf(v.arms) } ?: com.optionslab.engine.orb.ArmPriority.Rank.OTHER)
        }
        rowsHint = rowsOf(b)
    }

    /** A paper MARKET buy of 1 lot (the guard, the day's stop and the one-index-one-side rule first); null when refused or unfilled. */
    private suspend fun enterPaper(v: ShadowRules.Variant, c: Paper.Contract, base: Trade): Trade? {
        if (Strategies.stoppedToday()) return null
        val own = OrbArms.exposureHint + exposureHint
        if (AutoExposure.check(AutoExposure.Source.ORB, c.underlying, com.optionslab.ira.AutoSide.direction(c.right.name, true), own,
                com.optionslab.engine.orb.ArmPriority.rankOf(v.arms), trader = AutoExposure.SHADOW) != null) return null
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        if (Guard.check(Guard.paperOrder(c, "BUY", 1, base.entry), snap?.let { Guard.paperAccount(it) }, paper = true).isNotEmpty()) return null
        val buy = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null)
        val (qty, px) = filled(buy) ?: return null
        val label = "${ShadowRules.armName(v)} (${v.name})"
        buy.orderId?.let { Strategies.tagOwner("paper:$it", "$label · entry") }
        return base.copy(qty = qty, entry = px, peak = px, paper = true, orderId = buy.orderId,
            charges = Paper.state.trades.filter { it.orderId == buy.orderId }.sumOf { it.charges.toDouble() })
    }

    /** A paper MARKET sell of the whole position; null when nothing sold (tried again next pass). */
    private suspend fun exitPaper(p: Trade, c: Paper.Contract, why: String, ltp: Double): Trade? {
        val held = Paper.state.positions.filter { it.symbol == p.symbol && it.product == "MIS" }.sumOf { it.quantity }
        // Gone from the paper book without this selling it (the 15:15 square-off, or Boss closed it): booked at the price seen.
        if (held <= 0) return p.copy(exit = ltp, exitTime = now(), why = "closed_outside", last = ltp)
        val sell = Paper.place(c, "SELL", p.qty / c.lotSize.coerceAtLeast(1), "MARKET", "MIS", null, null)
        val (_, px) = filled(sell) ?: return null
        val v = ShadowRules.of(p.variant)
        sell.orderId?.let { Strategies.tagOwner("paper:$it", "${v?.let { x -> "${ShadowRules.armName(x)} (${x.name})" } ?: p.variant} · $why") }
        return p.copy(exit = px, exitTime = now(), why = why, last = ltp,
            charges = p.charges + Paper.state.trades.filter { it.orderId == sell.orderId }.sumOf { it.charges.toDouble() })
    }

    /** The fill of a paper MARKET order just placed (quantity, price); an order left open is cancelled at once. */
    private suspend fun filled(r: Paper.Result): Pair<Int, Double>? {
        r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()?.let { return it.quantity to it.price }
        val id = r.orderId ?: return null
        if (!r.ok) return null
        Paper.cancel(id, "unfilled_market")
        val o = Paper.state.orders.firstOrNull { it.orderId == id } ?: return null
        return if (o.status == "complete") o.quantity to (o.averagePrice?.toDouble() ?: return null) else null
    }

    /**
     * Boss's yes to Jarvis's request ([com.optionslab.app.ira.IraBots.shadowPromotions]): the shadowed arm's variant runs its
     * rules from now, ON PAPER ONLY (never Zerodha: no live path exists here), until he switches it off ([disarm]).
     */
    suspend fun promote(id: String): String = lock.withLock {
        val v = ShadowRules.of(id) ?: return@withLock "No such shadow."
        val b = book()
        b.promoted[id] = today().toString(); b.armed[id] = true
        save(b)
        val msg = "${ShadowRules.armName(v)} re-armed with ${v.name} on paper only (Boss's yes): ${v.description}. It never trades on Zerodha."
        runCatching { Diag.record("orb", msg) }
        msg
    }

    /** Switches a re-armed variant off again: it goes back to recording only. An open paper position is still managed to its exit. */
    suspend fun disarm(id: String): String = lock.withLock {
        val v = ShadowRules.of(id) ?: return@withLock "No such shadow."
        val b = book()
        b.armed[id] = false
        save(b)
        "${ShadowRules.armName(v)} (${v.name}) switched off: back to the shadow, no orders."
    }

    // ---- what the screen and Jarvis read ---------------------------------------------------------------------

    /** One variant's record: its closed trades since the shadow started, the line the Shadows section shows, its trades. */
    data class Row(val variant: ShadowRules.Variant, val summary: ShadowRules.Summary, val since: LocalDate, val line: String,
                   val armed: Boolean, val promoted: Boolean, val asked: Boolean, val trades: List<Trade>, val status: String,
                   /** Why each closed losing trade lost ([ShadowStudy.lossGroup]). */
                   val losses: List<ShadowStudy.LossGroup> = emptyList())

    private fun rowsOf(b: Book): List<Row> {
        val since = b.since ?: return emptyList()
        return ShadowRules.ALL.map { v ->
            val mine = b.trades.filter { it.variant == v.id }
            val s = ShadowRules.summarize(mine.filter { !it.open }.sortedBy { it.exitTime }.mapNotNull { it.net })
            val armed = b.armed[v.id] == true
            Row(v, s, since, ShadowRules.line(v, s, since) + if (armed) " · re-armed on paper" else "", armed, b.promoted.containsKey(v.id),
                v.id in b.asked, mine, b.status[v.id] ?: "",
                mine.filter { !it.open }.mapNotNull { t -> t.group?.let { g -> ShadowStudy.LossGroup.entries.firstOrNull { it.name == g } } })
        }
    }

    /** Every variant's row (empty until the first pass on a market day). Never waits on a pass: its last rows then. */
    suspend fun rows(): List<Row> {
        if (!::file.isInitialized) return emptyList()
        if (!lock.tryLock()) return rowsHint
        return try { rowsOf(book()) } finally { lock.unlock() }
    }

    /** What Jarvis says when asked how the shadows (the research rows of ORB, ORB Fresh, ORB Sweep and Range Fade) are doing. */
    suspend fun answer(): String {
        val rows = rows()
        return ShadowRules.answer(rows.map { Triple(it.variant, it.summary, it.since) }, rows.filter { it.armed }.map { it.variant.id }.toSet(),
            rows.associate { it.variant.id to it.losses })
    }

    /** The variants that met the bar and were never put to Boss. */
    suspend fun due(): List<Row> = rows().filter { !it.promoted && !it.asked && ShadowRules.promotionDue(it.summary) }

    /** Marks [id] as put to Boss (one request each, ever). */
    suspend fun asked(id: String) {
        lock.withLock { val b = book(); if (b.asked.add(id)) save(b) }
    }

    @Synchronized fun wipe() {
        cache = null; exposureHint = emptyList(); rowsHint = emptyList(); liquidityHint = emptyMap(); minuteCache.clear()
        if (::file.isInitialized) file.delete()
    }
}
