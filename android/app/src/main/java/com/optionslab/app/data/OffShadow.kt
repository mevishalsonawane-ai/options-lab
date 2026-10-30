package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.Right
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.orb.OffShadow as Rules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
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
 * The SWITCHED-OFF SHADOW on the phone (30 Oct 2026, Boss: "If i switch any arm off, it should be always run in shadow and record only,
 * so i can continuously observe it"). The rules are the engine's [Rules] (com.optionslab.engine.orb.OffShadow).
 *
 * An arm Boss switched off keeps deciding its signals every day (in Paper or Live) and, instead of buying, hands each would-be trade
 * here ([enter]). This book prices it as Zerodha would have filled it (the ask, the app's half-spread; [LiveMirror.shadowFill]), keeps
 * it with the arm's own exits ([Rules.Exits]: its stop, target, index stop, lock, trail, time exit, the square-offs), closes it on a
 * later look ([watch]) at the price the exit would have got (a stop at its resting SL's fill), and books the charges.
 *
 * What it never does: send an order. There is no call to [Paper.place], [Broker.placeOrder] or [ArmLive] anywhere here - a would-be
 * trade never touches the paper book's cash or positions and is never mirrored live. Labelled [Rules.LABEL] wherever it is shown.
 *
 * Battery and positions first: [watch] runs in the arms' rounds (the scheduler's wake rules, [SmartWorkers]), never in the stop lane,
 * the fast path or the live lane, and never holds a lock any position watch needs (its own lock only). Its price reads are network
 * reads on that worker; nothing here is on the tick or order path. Its book is its own encrypted file (no backup).
 */
object OffShadow {
    private const val FILE = "off_shadow.vault"
    private const val CLOSED_KEPT = 800
    private const val LOG_KEPT = 200
    private val lock = Mutex()
    /** 30 Oct (review): an entry with no price in memory is priced here, on its own worker - never under the asking arm's lock. */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    @Volatile private var file: File? = null
    private val written = Vault.LastWrite()

    fun init(context: Context) { file = File(context.applicationContext.noBackupFilesDir, FILE) }

    private fun ready() = file != null

    /**
     * One would-be trade asked by a switched-off arm: its switch [key] and [label], a [ref] unique to the signal (the same signal asked
     * twice is kept once), the [contract], [side] +1 a buy (-1 a sale), [view] +1 bullish on the underlying (-1 bearish), [lots], its
     * [exits] and [why] (the signal in words). [limit]: a LIMIT entry that pays at most this (not filled above it: noted, not kept).
     */
    data class Ask(
        val key: String, val label: String, val ref: String, val contract: Paper.Contract, val view: Int, val exits: Rules.Exits,
        val why: String, val side: Int = 1, val lots: Int = 1, val limit: Double? = null,
    )

    /** A kept would-be trade with the contract it was priced on (to read its price again on a later look). */
    data class Rec(val pos: Rules.Pos, val contract: Paper.Contract)

    private class Book(
        val recs: MutableList<Rec> = ArrayList(),
        val log: ArrayList<String> = ArrayList(),
        /** Every ref asked today (kept or not): the same signal is never kept twice. */
        val seen: MutableSet<String> = LinkedHashSet(),
    )

    /** What the rows, Strategy Lab, the diagnostics and Jarvis read (memory only). */
    data class View(val open: List<Rules.Pos> = emptyList(), val closed: List<Rules.Pos> = emptyList(), val log: List<String> = emptyList(),
                    val loaded: Boolean = false) {
        fun closedOf(key: String): List<Rules.Pos> = closed.filter { it.key == key }
        fun openOf(key: String): List<Rules.Pos> = open.filter { it.key == key }
        /** "shadow (switched off): 4 trades, Rs 1,200, 50% won · 1 open". */
        fun words(key: String): String = Rules.words(closedOf(key), openOf(key).size)
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view
    @Volatile private var cache: Book? = null
    @Volatile private var wipes = 0

    /** [key]'s open would-be trades with the contract each was priced on (for an arm that judges its own exit on them). */
    suspend fun openRecs(key: String): List<Rec> = if (!ready()) emptyList() else lock.withLock { book().recs.filter { it.pos.open && it.pos.key == key }.toList() }

    /** An open would-be trade is held (memory only): the arms' rounds run [watch]. */
    fun wantsPass(): Boolean = (ready() && cache == null) || _view.value.open.isNotEmpty()

    // ---- the book ---------------------------------------------------------------------------------------------------------

    private fun posJson(r: Rec): JSONObject {
        val p = r.pos; val c = r.contract; val x = p.exits
        return JSONObject().put("key", p.key).put("label", p.label).put("ref", p.ref).put("u", p.underlying).put("sym", p.symbol)
            .put("ex", p.exchange).put("side", p.side).put("view", p.view).put("qty", p.qty).put("lots", p.lots).put("mult", p.multiplier)
            .put("tick", p.tick).put("entry", p.entry).put("at", p.entryTime.toString()).put("best", p.best).put("charges", p.charges)
            .put("ewhy", p.entryWhy)
            .put("cexp", c.expiry.toString()).put("cstrike", c.strike).put("cright", c.right.name).put("clot", c.lotSize).put("cfeed", c.feedKey)
            .apply {
                p.expiry?.let { put("exp", it.toString()) }; p.stopNow?.let { put("stopNow", it) }
                p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitAt", it.toString()) }; p.why?.let { put("why", it) }
                x.stop?.let { put("xs", it) }; x.target?.let { put("xt", it) }; x.indexStop?.let { put("xis", it) }; x.indexTarget?.let { put("xit", it) }
                x.lockAfterRs?.let { put("xl", it) }; put("xk", x.lockKeep); x.trailPct?.let { put("xtr", it) }; x.exitAt?.let { put("xat", it.toString()) }
                if (x.carry) put("xc", true); x.exitOn?.let { put("xon", it.toString()) }
            }
    }

    private fun dbl(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
    private fun str(o: JSONObject, k: String): String? = o.optString(k).ifEmpty { null }

    private fun recOf(o: JSONObject): Rec {
        val x = Rules.Exits(dbl(o, "xs"), dbl(o, "xt"), dbl(o, "xis"), dbl(o, "xit"), dbl(o, "xl"), o.optDouble("xk", 0.6), dbl(o, "xtr"),
            str(o, "xat")?.let { LocalTime.parse(it) }, o.optBoolean("xc"), str(o, "xon")?.let { LocalDate.parse(it) })
        val p = Rules.Pos(o.getString("key"), o.getString("label"), o.getString("ref"), o.getString("u"), o.getString("sym"), o.getString("ex"),
            o.getInt("side"), o.getInt("view"), o.getInt("qty"), o.optInt("lots", 1), o.optInt("mult", 1), o.optDouble("tick", 0.05),
            o.getDouble("entry"), LocalDateTime.parse(o.getString("at")), str(o, "exp")?.let { LocalDate.parse(it) }, x, dbl(o, "stopNow"),
            o.optDouble("best", o.getDouble("entry")), dbl(o, "exit"), str(o, "exitAt")?.let { LocalDateTime.parse(it) }, str(o, "why"),
            o.optDouble("charges", 0.0), o.optString("ewhy"))
        val c = Paper.Contract(p.symbol, p.underlying, LocalDate.parse(o.getString("cexp")), o.getDouble("cstrike"), Right.valueOf(o.getString("cright")),
            o.getInt("clot"), o.optString("cfeed"), p.exchange)
        return Rec(p, c)
    }

    private fun book(): Book {
        cache?.let { return it }
        val gen = wipes
        val b = Book()
        val f = file
        if (f != null) runCatching {
            val o = JSONObject(String(Vault.readFileSteady(f) ?: return@runCatching, Charsets.UTF_8))
            o.optJSONArray("recs")?.let { a -> for (i in 0 until a.length()) runCatching { b.recs += recOf(a.getJSONObject(i)) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) b.seen += a.getString(i) }
        }.onFailure { if (f.exists() && !Vault.isErasing) runCatching { Vault.setAside(f) } }
        synchronized(written) { if (gen == wipes) cache = b }
        publish(b)
        return b
    }

    @Volatile private var savedMs = 0L
    private const val SAVE_EVERY_MS = 5 * 60_000L

    private fun save(b: Book) {
        savedMs = System.currentTimeMillis()
        val closed = b.recs.count { !it.pos.open }
        if (closed > CLOSED_KEPT) { var drop = closed - CLOSED_KEPT; b.recs.removeAll { !it.pos.open && drop-- > 0 } }
        val today = today().toString()
        b.seen.removeAll { !it.startsWith(today) }
        val o = JSONObject().put("recs", JSONArray(b.recs.map { posJson(it) })).put("log", JSONArray(b.log.takeLast(LOG_KEPT)))
            .put("seen", JSONArray(b.seen.toList()))
        synchronized(written) {
            if (cache !== b) return
            file?.let { f -> runCatching { written.write(f, o.toString().toByteArray(Charsets.UTF_8)) } }
        }
        publish(b)
    }

    private fun publish(b: Book) {
        if (b !== cache) return
        _view.value = View(b.recs.filter { it.pos.open }.map { it.pos }, b.recs.filter { !it.pos.open }.map { it.pos }, b.log.takeLast(40), true)
    }

    private fun note(b: Book, text: String) {
        b.log += "${now()} $text"
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { Diag.record("offshadow", text) }
    }

    private fun now(): LocalDateTime = (testNow ?: runCatching { Market.now().toLocalDateTime() }.getOrElse { LocalDateTime.now() }).withNano(0)
    private fun today(): LocalDate = now().toLocalDate()

    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    // ---- prices -----------------------------------------------------------------------------------------------------------

    /** A price read: (last, bid, ask), or null. The stream's tick first (memory), else the paper book's quote read (network). */
    private suspend fun price(c: Paper.Contract): Triple<Double, Double?, Double?>? {
        memPrice(c)?.let { return it }
        val last = runCatching { Paper.quote(c) }.getOrNull()?.takeIf { !it.noLast && it.ltp > 0 }?.ltp ?: return null
        return Triple(last, null, null)
    }

    /** A price from memory only (the stream's tick at most 10 s old, its bid and ask), or null. Never a read. */
    private fun memPrice(c: Paper.Contract): Triple<Double, Double?, Double?>? {
        testPrice?.let { return it(c.symbol) }
        val bn = runCatching { Paper.bookNow(c) }.getOrNull()?.takeIf { it.ageMs in 0..10_000 } ?: return null
        val last = bn.last?.takeIf { it > 0 } ?: return null
        return Triple(last, bn.bid, bn.ask)
    }

    private fun tickOf(c: Paper.Contract): Double =
        if (c.isMcx) runCatching { McxMarket.find(c.symbol)?.tick }.getOrNull()?.takeIf { it > 0 } ?: 0.05 else 0.05

    private fun halfSpread(c: Paper.Contract): Double = runCatching {
        com.optionslab.engine.sandbox.PaperSpread.halfSpread(c.symbol, null, c.exchange, now().toLocalTime())
    }.getOrDefault(com.optionslab.engine.sandbox.PaperSpread.UNKNOWN)

    private fun charge(buy: Boolean, price: Double, qty: Int, c: Paper.Contract): Double = runCatching {
        com.optionslab.engine.sandbox.SandboxCosts.charge(if (buy) "BUY" else "SELL", java.math.BigDecimal(price), qty, java.math.BigDecimal.ONE,
            c.exchange, c.symbol).toDouble()
    }.getOrDefault(0.0)

    private fun session(c: Paper.Contract): Boolean = testSession ?: runCatching { if (c.isMcx) McxMarket.isOpen() else Market.isOpen() }.getOrDefault(false)

    // ---- entries ----------------------------------------------------------------------------------------------------------

    /**
     * A switched-off arm's would-be trade [a]: priced and kept (nothing is sent anywhere). What to say (the arm's own row or diary
     * line). Called from the arm's own pass worker, never the tick or order path.
     */
    suspend fun enter(a: Ask): String {
        if (!ready()) return "shadow not ready"
        if (a.lots <= 0 || a.contract.lotSize <= 0) return "shadow: no size"
        val ref = "${today()}|${a.key}|${a.ref}"
        // The cheap checks first (memory, under the lock), the price read outside it.
        val refused = lock.withLock {
            val b = book()
            if (ref in b.seen) return "shadow: already recorded"
            Rules.refuse(a.key, today(), b.recs.map { it.pos })
        }
        if (refused != null) return "shadow: not recorded ($refused)"
        if (!session(a.contract)) return "shadow: not recorded (the market is closed)"
        // 30 Oct (review): the price from memory when the stream has it; else read on this book's own worker, so the asking arm (often
        // under its own lock, which its stop look also needs) never waits on a network read here.
        val mem = memPrice(a.contract)
        if (mem == null) {
            val claimed = lock.withLock { val b = book(); if (ref in b.seen) false else { b.seen += ref; true } }
            if (!claimed) return "shadow: already recorded"
            scope.launch {
                runCatching {
                    val q = price(a.contract)
                    // No price: noted, and the ref freed so the arm's next ask of the same signal is judged afresh.
                    if (q == null) lock.withLock { val b = book(); b.seen -= ref; note(b, "${a.label}: ${Rules.LABEL} not recorded (no price for ${a.contract.symbol})"); save(b) }
                    else keep(a, ref, q, claimed = true)
                }
            }
            return "${Rules.LABEL}: would ${if (a.side >= 0) "buy" else "sell"} ${a.contract.symbol} (priced on its own worker, nothing ${if (a.side >= 0) "bought" else "sold"})"
        }
        return keep(a, ref, mem, claimed = false)
    }

    /** [a] kept at the quote [q] ((last, bid, ask)) under [ref]; [claimed]: its ref is already in [Book.seen]. What to say. */
    private suspend fun keep(a: Ask, ref: String, q: Triple<Double, Double?, Double?>, claimed: Boolean): String {
        val (last, bid, ask) = q
        val tick = tickOf(a.contract)
        val buy = a.side >= 0
        val px = LiveMirror.shadowFill(buy, last, bid, ask, tick, halfSpread(a.contract), 0.0)
        if (a.limit != null && (if (buy) px > a.limit + 1e-9 else px < a.limit - 1e-9)) {
            // Not filled: the ref is free again, so the arm's next ask (as its paper LIMIT is retried) is judged afresh.
            if (claimed) lock.withLock { book().seen -= ref }
            return "shadow: its LIMIT %.2f would not have filled at %.2f".format(Locale.ENGLISH, a.limit, px)
        }
        val qty = a.lots * a.contract.lotSize
        val pos = Rules.Pos(a.key, a.label, ref, a.contract.underlying, a.contract.symbol, a.contract.exchange, if (buy) 1 else -1,
            if (a.view >= 0) 1 else -1, qty, a.lots, 1, tick, px, now(), a.contract.expiry, a.exits, charges = charge(buy, px, qty, a.contract),
            entryWhy = a.why.take(240))
        return lock.withLock {
            val b = book()
            if (!claimed && ref in b.seen) return@withLock "shadow: already recorded"
            b.seen += ref
            b.recs += Rec(pos, a.contract)
            lastLook[ref] = now()
            note(b, "%s: %s %s %s %d @ %.2f (nothing bought)%s · %s".format(Locale.ENGLISH, a.label, Rules.LABEL, if (buy) "would buy" else "would sell",
                pos.symbol, qty, px, a.exits.stop?.let { " · stop %.2f".format(Locale.ENGLISH, it) } ?: "", a.why.take(120)))
            save(b)
            "%s: would %s %s %d @ %.2f (nothing %s)".format(Locale.ENGLISH, Rules.LABEL, if (buy) "buy" else "sell", pos.symbol, qty, px, if (buy) "bought" else "sold")
        }
    }

    // ---- the watch --------------------------------------------------------------------------------------------------------

    /** Each open would-be trade's last look (memory): its stop is judged on the prices since. */
    private val lastLook = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()

    /** The underlying's latest price and extreme since [since] for an index stop (the passed arms' minutes; null: not read). */
    private suspend fun indexSeen(u: String, since: LocalDateTime, low: Boolean, now: LocalDateTime): Pair<Double?, Double?> {
        testIndex?.let { return it(u) }
        val bars = runCatching { PassedArmsHost.indexBars(u, now) }.getOrNull().orEmpty().filter { !it.start.isBefore(since.withSecond(0)) }
        val last = bars.lastOrNull()?.close?.takeIf { it > 0 }
        val ext = if (low) bars.mapNotNull { b -> b.low.takeIf { it > 0 } }.minOrNull() else bars.mapNotNull { b -> b.high.takeIf { it > 0 } }.maxOrNull()
        return last to ext
    }

    /**
     * One look at every open would-be trade: its price (and the extremes since its last look), the arm's exits ([Rules.decide]),
     * a close booked at the price the exit would have got. The reads run outside the lock; the book is written once.
     */
    suspend fun watch() {
        if (!ready()) return
        val open = lock.withLock { book().recs.filter { it.pos.open }.toList() }
        if (open.isEmpty()) return
        val t = now()
        val done = HashMap<String, Rules.Pos>()
        for (r in open) {
            val p = r.pos
            val c = r.contract
            val since = lastLook[p.ref] ?: t
            val q = price(c)
            val ltp = q?.first
            val long = p.side >= 0
            val extreme = if (q == null) null else runCatching { testExtreme?.invoke(p.symbol) ?: if (long) Paper.lowSince(c, since) else Paper.highSince(c, since) }.getOrNull()
            val bestSeen = if (q == null) null else runCatching { testExtreme?.invoke(p.symbol) ?: if (long) Paper.highSince(c, since) else Paper.lowSince(c, since) }.getOrNull()
            val needsIndex = p.exits.indexStop != null || p.exits.indexTarget != null
            val (ix, ixExt) = if (needsIndex) indexSeen(p.underlying, since, p.view >= 0, t) else (null to null)
            val best = Rules.bestAfter(p, listOfNotNull(bestSeen, ltp).let { if (long) it.maxOrNull() else it.minOrNull() })
            val cur = p.copy(best = best, stopNow = Rules.stopAfter(p, best))
            val exitCost = ltp?.let { charge(!long, it, p.qty, c) } ?: 0.0
            // 30 Oct (review): this look's low is judged against the stop as it stood BEFORE this look (a trail raised by this look's
            // high must not be "hit" by a low that came before that high); the raised stop counts from the next look.
            val out = Rules.decide(cur.copy(stopNow = p.stopNow), Rules.Look(t, ltp, extreme, ix, ixExt, bestSeen, exitCost, session(c)))
            if (q != null) lastLook[p.ref] = t
            if (out == null) { if (cur != p) done[p.ref] = cur; continue }
            // No price (the stop judged on a wick alone is still priced at its SL): a market exit waits for the next look.
            val qq: Triple<Double, Double?, Double?> = q ?: out.trigger?.takeIf { out.stop }?.let { Triple(it, null, null) } ?: run { if (cur != p) done[p.ref] = cur; null } ?: continue
            val hs = halfSpread(c)
            val trig = out.trigger
            val px = if (out.stop && trig != null && !LiveMirror.runThrough(ltp, trig, p.tick, p.side))
                LiveMirror.shadowStopFill(trig, p.tick, p.side, hs)
            else LiveMirror.shadowFill(!long, qq.first, qq.second, qq.third, p.tick, hs, 0.0)
            done[p.ref] = cur.copy(exit = px, exitTime = t, why = out.why, charges = p.charges + charge(!long, px, p.qty, c))
            lastLook.remove(p.ref)
        }
        if (done.isEmpty()) return
        lock.withLock {
            val b = book()
            var closedAny = false
            for (i in b.recs.indices) {
                val r = b.recs[i]
                val next = done[r.pos.ref] ?: continue
                if (!r.pos.open) continue
                b.recs[i] = r.copy(pos = next)
                if (!next.open) {
                    closedAny = true
                    note(b, "%s: %s closed %s (%s) @ %.2f · %s after charges".format(Locale.ENGLISH, next.label, Rules.LABEL, next.symbol,
                        next.why, next.exit, LiveMirror.rs(next.net ?: 0.0)))
                }
            }
            // 30 Oct (review): a new best price or a trailed stop alone is kept in memory and written at most every [SAVE_EVERY_MS]
            // (a whole-book rewrite each round was wasteful); a close is written at once.
            if (closedAny || System.currentTimeMillis() - savedMs >= SAVE_EVERY_MS) save(b) else publish(b)
        }
    }

    /**
     * The arm's own exit for a would-be trade it still follows ([ref] as asked, on any day: a carried one asked yesterday too), booked
     * now at the quote (the bid for a long).
     */
    suspend fun exitNow(key: String, ref: String, why: String) {
        if (!ready()) return
        val tail = "|$key|$ref"
        val r = lock.withLock { book().recs.lastOrNull { it.pos.ref.endsWith(tail) && it.pos.open } } ?: return
        val full = r.pos.ref
        val q = price(r.contract) ?: return
        val long = r.pos.side >= 0
        val px = LiveMirror.shadowFill(!long, q.first, q.second, q.third, r.pos.tick, halfSpread(r.contract), 0.0)
        lock.withLock {
            val b = book()
            val i = b.recs.indexOfFirst { it.pos.ref == full && it.pos.open }
            if (i < 0) return@withLock
            val p = b.recs[i].pos
            b.recs[i] = b.recs[i].copy(pos = p.copy(exit = px, exitTime = now(), why = why, charges = p.charges + charge(!long, px, p.qty, r.contract)))
            note(b, "%s: %s closed %s (%s) @ %.2f".format(Locale.ENGLISH, p.label, Rules.LABEL, p.symbol, why, px))
            save(b)
        }
        lastLook.remove(full)
    }

    // ---- words ------------------------------------------------------------------------------------------------------------

    /** [key]'s count while it is off, with no rupee figure (the rows show none): "shadow (switched off): 3 closed, 2 won · 1 open". */
    fun countWords(key: String): String {
        val v = _view.value
        val c = v.closedOf(key)
        val o = v.openOf(key).size
        return "${Rules.LABEL}: " + (if (c.isEmpty() && o == 0) "no would-be trade yet" else "${c.size} closed, ${c.count { (it.net ?: 0.0) > 0 }} won") +
            (if (o > 0) " · $o open" else "")
    }

    /** [key]'s row line while it is off ("shadow (switched off): ..."). Memory only. */
    fun line(key: String): String = _view.value.words(key)

    /** The diagnostics' line: every switched-off arm with a shadow record. Memory only; never a rupee figure on a locked phone. */
    fun diagLine(hideNet: Boolean = false): String {
        val v = _view.value
        val keys = (v.open + v.closed).map { it.key to it.label }.distinct()
        if (keys.isEmpty()) return "Switched-off shadow: no would-be trade yet (nothing is ever bought for it)."
        return "Switched-off shadow (nothing bought): " + keys.joinToString("; ") { (k, l) ->
            val c = v.closedOf(k)
            "$l ${c.size} closed" + (if (hideNet) "" else " " + LiveMirror.rs(c.sumOf { it.net ?: 0.0 })) + " · ${v.openOf(k).size} open"
        }
    }

    // ---- tests and the erase ----------------------------------------------------------------------------------------------

    // TEST SEAMS (JVM tests only): each setter throws in a release build, as every other seam's.
    @Volatile internal var testNow: LocalDateTime? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }
    @Volatile internal var testPrice: ((String) -> Triple<Double, Double?, Double?>?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }
    @Volatile internal var testExtreme: ((String) -> Double?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }
    @Volatile internal var testIndex: ((String) -> Pair<Double?, Double?>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }
    @Volatile internal var testSession: Boolean? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }

    /** For tests: the book forgotten (its file stays). */
    internal fun wipe() {
        synchronized(written) { wipes++; cache = null; _view.value = View(); lastLook.clear(); written.forget() }
        // (The seams' setters throw in a release build, where "Erase everything" also comes here: reset only in a debug build.)
        if (com.optionslab.app.BuildConfig.DEBUG) { testNow = null; testPrice = null; testExtreme = null; testIndex = null; testSession = null }
    }

    /** "Erase everything" ([PersonalStores]): its file deleted and the memory dropped. */
    internal fun wipeForErase() {
        synchronized(written) { for (f in filesForErase()) runCatching { f.delete() }; wipe() }
    }

    internal fun holdsForErase(): Boolean = cache?.let { it.recs.isNotEmpty() || it.log.isNotEmpty() } == true || filesForErase().isNotEmpty()

    internal fun filesForErase(): List<File> = listOfNotNull(file).flatMap { f -> listOf(f, File(f.path + ".tmp"), File(f.path + ".bak")) }.filter { it.exists() }
}
