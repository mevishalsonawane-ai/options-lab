package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.ProfitLock
import com.optionslab.engine.pine.Pine
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pine scripts that trade by themselves. On every completed candle of the chosen symbol and
 * interval the script runs; when its signal changes, the app buys the ATM option of the
 * nearest expiry after today (a CALL to go long, a PUT to go short, or just exits), as a
 * MARKET MIS order for the chosen lots, and sells the one it held.
 *
 * It follows the app's Paper / Live switch, decided at each order. Live needs the PIN (or
 * fingerprint) once when the script is switched on; every live order still passes the
 * account guard, the order limits, the kill switch and the static-IP check. Nothing is
 * decided outside market hours; at 15:15 what is held is sold; the day's stop (the
 * kill switch) sells and stands still. A newly switched-on script waits for the next
 * change of signal: it never jumps into a trade already under way.
 */
object PineAuto {
    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()

    /**
     * [since] > 0: a live buy Zerodha has not confirmed (since then, epoch ms), its order [order] (null when even the
     * id was lost). [qty] is then at most what may have filled; it is settled from the order book on the next passes.
     * [peak]: the option's best price seen since it was bought (the profit lock's high-water mark; the buy price at first).
     * Every holding is a bought option (a CALL or a PUT), never a sold one, so the lock is only ever a long's.
     * [stop]: its resting SELL stop (08 Oct, research/PROFIT_LOCK_8OCT.md fix 1) - the paper book's SL-M order id, or the
     * Zerodha SL order id when [live] - resting at [stopAt] (the stop-loss, moved up by the profit lock, never down); null
     * when none rests (the app's own checks then sell at market, as before). [at]: when it was bought (epoch ms; 0 for a
     * holding saved before 08 Oct), from which the best price is read off the ticks or the 1-minute candles' highs.
     */
    data class Held(val symbol: String, val right: String, val qty: Int, val lotSize: Int, val entry: Double, val day: String,
                    val live: Boolean, val kite: String?, val order: String? = null, val since: Long = 0L, val peak: Double = entry,
                    val stop: String? = null, val stopAt: Double? = null, val at: Long = 0L) {
        val unconfirmed: Boolean get() = since > 0
    }
    data class Line(val at: Long, val script: Long, val text: String)

    /**
     * 19 Nov 2026 (audit: a Pine live stop left resting at Zerodha after its position closed could later open a naked short): a live
     * holding confirmed closed at Zerodha whose resting stop and other own exits ("irapine" SELLs on [kite]) are not yet confirmed gone
     * there: [ids], each order id with what of it had filled when it was recorded (a fill beyond that sold what was no longer held).
     * Kept (saved with the book) and tried again on every check until Zerodha shows each one cancelled, rejected or complete; Boss is
     * told once after [REMOVE_TRIES] tries ([told]). [script]: whose holding it was; [day]: the session (a DAY order ends with it).
     * [scanned] false: Zerodha's orders could not be read when it closed, so its contract's other own exits are still to be looked for.
     * [at]: when its removal began (epoch ms; 19 Nov 2026, third review: what Boss was told of it is keyed on this, which never changes).
     */
    data class Removing(val script: Long, val kite: String, val ids: Map<String, Int>, val day: String, val scanned: Boolean = true,
                        val tries: Int = 0, val told: Boolean = false, val at: Long = 0L)

    private class Book(
        val held: HashMap<Long, Held> = HashMap(),
        val lastBar: HashMap<Long, Long> = HashMap(),
        val lastTarget: HashMap<Long, Int> = HashMap(),
        val liveOk: HashMap<Long, Boolean> = HashMap(),
        val log: ArrayList<Line> = ArrayList(),
        /** Script -> "day|rupees": what it made or lost on closed trades today. */
        val dayPnl: HashMap<Long, String> = HashMap(),
        /** Script -> the day it hit its own daily loss limit (no new trades until tomorrow). */
        val paused: HashMap<Long, String> = HashMap(),
        /**
         * Scripts switched off by the app's own rules (a duplicate of an older script, Boss's yes to switching a losing
         * one off) that still hold an option: script -> why. Their holding is managed to its exit (its stop, target, profit
         * lock, 15:15, a change of signal), and they buy nothing more.
         */
        val winding: HashMap<Long, String> = HashMap(),
        /** The one-time changes this book has been through ([dedupe]): each runs once, never again. */
        val migrated: HashSet<String> = HashSet(),
        /**
         * 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only"): the scripts SAVED FOR
         * AUTO-TRADING - switched on at least once (by Boss, or on Boss's yes to a Jarvis script) and still in the library. Only
         * these run in the switched-off shadow while off ([shadowable]); a script that was only ever written, imported or charted
         * is not an arm and is never shadowed. A book saved before 30 Oct: the scripts it had already run ([lastTarget],
         * [lastBar]: written only while a script was on).
         */
        val autoSaved: HashSet<Long> = HashSet(),
        /** 19 Nov 2026 (audit): live exits still being taken out at Zerodha after their holding closed ([Removing]). */
        val removing: ArrayList<Removing> = ArrayList(),
        /**
         * 19 Nov 2026 (audit): today's live closes, by "day|kite symbol|a" (every close: a sale, a stop's fill, closed outside) and
         * "day|kite symbol|o" (closed outside the app only), in units: one sale in Zerodha's order book proves one close only
         * ([flatConfirmed]).
         */
        val closed: HashMap<String, Int> = HashMap(),
        /**
         * 22 Nov 2026 (B6 review): the live exit sales whose answer was lost and that are still looked for ([holdLostExit]), by their key
         * ([lostExitKeyOf]): what the search needs (contract, quantity, type, when sent, the orders it may never take) - so a restart in
         * their window takes the hold back ([restoreLostSales]) and never sends the exit again beside one that may have filled.
         */
        val lostSales: HashMap<String, JSONObject> = HashMap(),
    )

    private val _held = MutableStateFlow<Map<Long, Held>>(emptyMap())
    val held: StateFlow<Map<Long, Held>> = _held
    private val _log = MutableStateFlow<List<Line>>(emptyList())
    val log: StateFlow<List<Line>> = _log
    @Volatile private var cache: Book? = null
    /** Bumped by [wipe] (26 Oct, Erase everything): a read under way when the erase came is not kept. */
    @Volatile private var wipes = 0

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.noBackupFilesDir, "pine_auto.vault")
        // 23 Nov 2026 (B6 follow-ups, item 2): the square-offs subtract a live holding's exit sale whose answer was lost and is still looked
        // for ([lostExit], [Broker.lostSales]) and take no stop out beside it. Only those are counted there - not a sale on its way - so
        // Pine's tag is not one the square-offs leave to that count: one of its sales not in their read holds the row back as another sender's.
        SquareOffExit.lostPendingProviders[CLAIM_OWNER] = { sym, product -> lostPending(sym, product) }
        // (24 Nov 2026, B6-I round 6: a lost exit saved in the book is not known until the book is read - [lostPending].)
        lostUnknownUntil = System.currentTimeMillis() + ArmLive.LOST_LET_GO_MS
    }

    /**
     * 24 Nov 2026 (B6-I round 6, final integration review: the provider answered 0 before the book was read, so a lost exit saved in it from
     * before a restart was not counted): the scripts' live exit sales whose answer was lost and that are still looked for on [symbol]'s
     * [product] row ([Broker.lostSales] - taken back from the book as it is read: [restoreLostSales]); -1 (not known) for a row a script may
     * hold ([pineMayHold]) while the book is not read yet in its first [ArmLive.LOST_LET_GO_MS] after the start, or for as long after it was
     * set aside as damaged - as the arms' live book's ([ArmLive.unsettledSales]). Memory only.
     */
    internal fun lostPending(symbol: String, product: String): Int {
        val wall = System.currentTimeMillis()
        if ((wall < lostAsideUntil || (cache == null && wall < lostUnknownUntil)) && pineMayHold(symbol, product)) return -1
        return Broker.lostSales(LOST_EXIT, symbol, product)
    }

    /**
     * 24 Nov 2026 (B6-I round 7, gate review: BSE's and MCX's options were held back too): a row a script may hold - an NSE option (NFO),
     * intraday, as the scripts trade - never a BSE index's option (BFO), a commodity's (MCX), a future or a stock. By the symbol's form
     * alone (the square-offs ask by symbol and product). Pure.
     */
    internal fun pineMayHold(symbol: String, product: String): Boolean {
        if (product != "MIS" || !OPTION_FORM.containsMatchIn(symbol)) return false
        val u = com.optionslab.ira.ManualCoverRule.underlying(symbol)
        return u !in BSE_INDICES && u !in com.optionslab.engine.mcx.Mcx.NAMES
    }
    /** (A strike's digits before CE / PE - never a stock whose name ends in CE.) */
    private val OPTION_FORM = Regex("\\d(CE|PE)$")
    private val BSE_INDICES = setOf("SENSEX", "BANKEX", "SENSEX50")
    /** 24 Nov 2026 (B6-I round 6): until when (wall clock) a lost exit saved in a book not read yet in this process is not known ([lostPending]). */
    @Volatile private var lostUnknownUntil = 0L
    /** 24 Nov 2026 (B6-I round 6): until when (wall clock) a lost exit kept in a book set aside as damaged is not known ([lostPending]). */
    @Volatile private var lostAsideUntil = 0L

    /** TEST ONLY: as a process that started [ms] ago with its book not read yet ([lostPending]); the file is kept. Debug builds only. */
    internal fun startedForTest(ms: Long) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        synchronized(this) { cache = null }
        lostUnknownUntil = System.currentTimeMillis() - ms + ArmLive.LOST_LET_GO_MS
    }

    private fun book(): Book {
        cache?.let { return it }
        val gen = wipes
        val b = runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching null, Charsets.UTF_8))
            val bk = Book()
            o.optJSONObject("held")?.let { m -> m.keys().forEach { k -> val h = m.getJSONObject(k)
                bk.held[k.toLong()] = Held(h.getString("symbol"), h.getString("right"), h.getInt("qty"), h.optInt("lot", 1), h.getDouble("entry"),
                    h.getString("day"), h.optBoolean("live"), h.optString("kite").ifBlank { null }, h.optString("order").ifBlank { null },
                    h.optLong("since", 0L),
                    // Saved before the profit lock: the buy price, so no rung counts as reached that was never seen.
                    h.optDouble("peak", Double.NaN).takeIf { it.isFinite() && it > 0 } ?: h.getDouble("entry"),
                    // Saved before 08 Oct: no resting stop, no buy time (the app's own checks, as before).
                    h.optString("stop").ifBlank { null }, h.optDouble("stopAt", Double.NaN).takeIf { it.isFinite() && it > 0 },
                    h.optLong("at", 0L)) } }
            o.optJSONObject("lastBar")?.let { m -> m.keys().forEach { k -> bk.lastBar[k.toLong()] = m.getLong(k) } }
            o.optJSONObject("lastTarget")?.let { m -> m.keys().forEach { k -> bk.lastTarget[k.toLong()] = m.getInt(k) } }
            o.optJSONObject("liveOk")?.let { m -> m.keys().forEach { k -> bk.liveOk[k.toLong()] = m.getBoolean(k) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) { val l = a.getJSONArray(i); bk.log += Line(l.getLong(0), l.getLong(1), l.getString(2)) } }
            o.optJSONObject("dayPnl")?.let { m -> m.keys().forEach { k -> bk.dayPnl[k.toLong()] = m.getString(k) } }
            o.optJSONObject("paused")?.let { m -> m.keys().forEach { k -> bk.paused[k.toLong()] = m.getString(k) } }
            o.optJSONObject("winding")?.let { m -> m.keys().forEach { k -> bk.winding[k.toLong()] = m.getString(k) } }
            o.optJSONArray("migrated")?.let { a -> for (i in 0 until a.length()) bk.migrated += a.getString(i) }
            // 30 Oct: the scripts saved for auto-trading; a book from before: those it had already run while on.
            o.optJSONArray("autoSaved")?.let { a -> for (i in 0 until a.length()) bk.autoSaved += a.getLong(i) }
                ?: run { bk.autoSaved += bk.lastTarget.keys; bk.autoSaved += bk.lastBar.keys }
            // 19 Nov 2026 (audit): the live exits still being taken out, and today's live closes (a book from before: none).
            o.optJSONArray("removing")?.let { a -> for (i in 0 until a.length()) { val r = a.getJSONObject(i)
                val ids = LinkedHashMap<String, Int>().apply { r.optJSONObject("ids")?.let { m -> m.keys().forEach { k -> put(k, m.getInt(k)) } } }
                bk.removing += Removing(r.getLong("script"), r.getString("kite"), ids, r.optString("day"), r.optBoolean("scanned", true),
                    r.optInt("tries"), r.optBoolean("told"), r.optLong("at", 0L)) } }
            o.optJSONObject("closed")?.let { m -> m.keys().forEach { k -> bk.closed[k] = m.getInt(k) } }
            // 22 Nov 2026 (B6 review, item 9): the app's own cancels saved with the book, so a restart never reads one as Boss's.
            o.optJSONArray("ownCancels")?.let { a -> for (i in 0 until a.length()) ownCancels += a.getString(i) }
            // (22 Nov 2026, B6 review, third look: and when each was made - so after a restart a fresh one still puts its stop back, and one
            // still working gets the same 15 s before its mark ends. One saved without a time: as before, ends at its first working sighting.)
            o.optJSONObject("ownCancelAt")?.let { m -> m.keys().forEach { k -> if (k in ownCancels) m.optLong(k, 0L).takeIf { it > 0L }?.let { ownCancelAt[k] = it } } }
            // 22 Nov 2026 (B6 review): the lost exit sales still looked for, their holds taken back ([restoreLostSales]).
            // (Never at the cost of the book: a record that cannot be read is left out.)
            runCatching { o.optJSONObject("lostSales")?.let { m -> m.keys().forEach { k -> m.optJSONObject(k)?.let { bk.lostSales[k] = it } } } }
            // A restore not yet disarmed (the app clears the flag once it has): no script may trade live on restored approvals.
            if (com.optionslab.app.security.SecurePrefs.getBoolean(Backup.DISARM, false)) bk.liveOk.clear()
            bk
        }.getOrNull()
        // 26 Oct: a read the erase refused ([Vault.Erasing]) is not a damaged file: never set aside (it is being deleted).
        if (b == null && file.exists() && !Vault.isErasing) {
            Vault.setAside(file)
            // (24 Nov 2026, B6-I round 6: a lost exit kept in the book set aside is not known - the square-offs wait as for the arms' book.)
            lostAsideUntil = System.currentTimeMillis() + ArmLive.LOST_LET_GO_MS
        }
        // 26 Oct: read before an erase that came meanwhile ([wipe]): not kept, not shown (its pass's saves are then dropped).
        return (b ?: Book()).also { bk ->
            val kept = synchronized(this) { (gen == wipes).also { if (it) { cache = bk; publish(bk) } } }
            // 22 Nov 2026 (B6 review): the lost exit sales saved with it taken back - only for the book kept (never one an erase dropped).
            if (kept) runCatching { restoreLostSales(bk) }
        }
    }

    /** What this process last wrote to the book's file: an unchanged save is not encrypted and synced again ([Vault.LastWrite]). */
    private val written = Vault.LastWrite()

    private fun save(b: Book) {
        // 26 Oct (Erase everything): a pass that read the book before [wipe] never writes it back or shows it again. The write
        // itself holds no lock (a live position's save is on the order's way): an erase that came during it is undone below.
        if (cache !== b) return
        val (seq, bytes) = encode(b)
        writeBook(b, seq, bytes, show = true)
    }

    /**
     * 22 Nov 2026 (B6 review): [b] encoded now (memory only) and written on this book's own writer thread ([writer]) - never a disk wait on
     * the caller's path (an own cancel's mark saved before the cancel goes, [cancelOwn]). Numbered as it is encoded, so it never lands
     * over a newer save ([writeBook]).
     */
    private fun saveSoon(b: Book) {
        if (cache !== b) return
        val (seq, bytes) = runCatching { encode(b) }.getOrElse { return }
        pendingWrite.set(Triple(b, seq, bytes))
        runCatching { writer.execute {
            // (Only the newest: one superseded meanwhile - by a later one here, or a save that writes itself - is never written, so a save on
            // the order's way seldom waits behind this thread's write.)
            val p = pendingWrite.getAndSet(null) ?: return@execute
            if (p.second < encodedSeq.get()) return@execute
            runCatching { writeBook(p.first, p.second, p.third) }
        } }
    }

    /** The newest encoding handed to [writer] and not written yet ([saveSoon]). */
    private val pendingWrite = java.util.concurrent.atomic.AtomicReference<Triple<Book, Long, ByteArray>?>(null)

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "pine-book-writer").apply { isDaemon = true } }
    private val writeLock = Any()
    /** The last encoding's number (never waits on a write: an own cancel's save is on the order's way), and the newest one written ([writeLock]). */
    private val encodedSeq = java.util.concurrent.atomic.AtomicLong()
    private var writtenSeq = 0L

    /** [b]'s bytes ([seq]) written unless a newer encoding already landed; false when [b] was erased meanwhile (the file deleted again). */
    private fun writeBook(b: Book, seq: Long, bytes: ByteArray, show: Boolean = false): Boolean =
        // (One write at a time, and the erase check with it: a later book's save waits here, so a delete below only ever undoes this write.)
        synchronized(writeLock) { writeLocked(b, seq, bytes, show) }

    private fun writeLocked(b: Book, seq: Long, bytes: ByteArray, show: Boolean): Boolean {
        if (synchronized(this) { cache !== b }) return false                          // erased (or replaced) before: never written
        if (seq > writtenSeq) { written.write(file, bytes); writtenSeq = seq }
        synchronized(this) {
            // Erased while it was written: the file it may have put back is deleted again, and nothing is shown.
            if (cache !== b) { written.forget(); if (::file.isInitialized) runCatching { file.delete() }; return false }
            if (show) publish(b)
        }
        return true
    }

    /** [b] as the file holds it, numbered (the book's lock is held by every caller: the numbers follow the book's own order). Memory only. */
    private fun encode(b: Book): Pair<Long, ByteArray> {
        while (b.log.size > 300) b.log.removeAt(0)
        val o = JSONObject()
        o.put("held", JSONObject().apply { b.held.forEach { (k, h) -> put(k.toString(), JSONObject().put("symbol", h.symbol).put("right", h.right)
            .put("qty", h.qty).put("lot", h.lotSize).put("entry", h.entry).put("day", h.day).put("live", h.live).put("kite", h.kite ?: "")
            .put("order", h.order ?: "").put("since", h.since).put("peak", h.peak).put("stop", h.stop ?: "")
            .put("stopAt", h.stopAt ?: 0.0).put("at", h.at)) } })
        o.put("lastBar", JSONObject().apply { b.lastBar.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("lastTarget", JSONObject().apply { b.lastTarget.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("liveOk", JSONObject().apply { b.liveOk.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("log", JSONArray().apply { b.log.forEach { put(JSONArray().put(it.at).put(it.script).put(it.text)) } })
        o.put("dayPnl", JSONObject().apply { b.dayPnl.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("paused", JSONObject().apply { b.paused.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("winding", JSONObject().apply { b.winding.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("migrated", JSONArray(b.migrated.sorted()))
        o.put("autoSaved", JSONArray(b.autoSaved.sorted()))
        o.put("removing", JSONArray().apply { b.removing.forEach { r -> put(JSONObject().put("script", r.script).put("kite", r.kite)
            .put("ids", JSONObject().apply { r.ids.forEach { (k, v) -> put(k, v) } }).put("day", r.day).put("scanned", r.scanned)
            .put("tries", r.tries).put("told", r.told).put("at", r.at)) } })
        o.put("closed", JSONObject().apply { b.closed.forEach { (k, v) -> put(k, v) } })
        // 22 Nov 2026 (B6 review, item 9): the app's own cancels, the newest [OWN_CANCELS_SAVED] (a cancel of a day's DAY order matters that day).
        val kept = synchronized(ownCancels) { ownCancels.toList() }.takeLast(OWN_CANCELS_SAVED)
        o.put("ownCancels", JSONArray(kept))
        o.put("ownCancelAt", JSONObject().apply { kept.forEach { k -> ownCancelAt[k]?.let { put(k, it) } } })
        o.put("lostSales", JSONObject().apply { b.lostSales.forEach { (k, v) -> put(k, v) } })
        return encodedSeq.incrementAndGet() to o.toString().toByteArray(Charsets.UTF_8)
    }

    private fun publish(b: Book) { synchronized(this) {
        // 26 Oct: an old book (read before an erase) is never shown again.
        if (cache !== b) return
        _held.value = HashMap(b.held); _log.value = b.log.toList()
        removingHint = b.removing.isNotEmpty()
    } }

    /** 19 Nov 2026 (audit): whether a closed live holding's exits are still being taken out at Zerodha ([Book.removing]). Memory only. */
    @Volatile private var removingHint = false

    private fun note(b: Book, id: Long, text: String) { b.log += Line(System.currentTimeMillis(), id, text) }

    // ---- one index, one side (Boss's 06 Oct rule, [com.optionslab.ira.AutoSide]) ----------------------------------

    /** Who holds a script's option, for the other traders' refusals: "Pine #4 · Jarvis: bullish engulfing Nifty 15m". */
    private fun whoOf(id: Long): String = "Pine #$id" + (PineScripts.get(id)?.name?.let { " · $it" } ?: "")

    private fun exposureOf(held: Map<Long, Held>): List<com.optionslab.ira.AutoSide.Held> = held.map { (id, h) ->
        com.optionslab.ira.AutoSide.Held.option(whoOf(id), h.symbol,
            com.optionslab.ira.AutoSide.underlyingOf(h.symbol) ?: PineScripts.get(id)?.auto?.symbol ?: h.symbol, h.right, long = true)
    }

    /** The scripts' open options as of the last load or save, read without the lock by the other traders ([AutoExposure]). */
    fun exposure(): List<com.optionslab.ira.AutoSide.Held> = exposureOf(_held.value)

    // ---- the same script saved twice (Boss's 06 Oct diagnostics, [com.optionslab.ira.PineDupes]) -------------------

    /** The one-time duplicates switch-off: its key in [Book.migrated]. */
    internal const val DUPES = "dupes_2026_10_06"

    /**
     * Once, on this update: of the armed scripts with the same code (comments and whitespace aside), symbol and chart, the
     * oldest keeps trading and the others are switched off - never deleted; an option one of them holds is managed to its
     * exit ([Book.winding]) - each said in its log. True when [b] changed (it is then saved).
     */
    private fun dedupe(b: Book): Boolean {
        if (DUPES in b.migrated) return false
        val items = PineScripts.loadNow() ?: return false
        val extra = com.optionslab.ira.PineDupes.extraArmed(items.map {
            com.optionslab.ira.PineDupes.Script(it.id, it.code, it.auto.symbol, it.auto.interval, it.auto.on)
        })
        for ((s, kept) in extra) {
            val item = PineScripts.get(s.id) ?: continue
            PineScripts.setAuto(s.id, item.auto.copy(on = false))
            b.liveOk.remove(s.id)
            if (b.held.containsKey(s.id)) b.winding[s.id] = "the same strategy as #$kept"
            note(b, s.id, com.optionslab.ira.PineDupes.note(kept))
        }
        b.migrated += DUPES
        return true
    }

    /** The one-time FINNIFTY breakdown switch-off (Boss's 08 Oct yes, research X1): its key in [Book.migrated]. */
    internal const val FIN_OFF = com.optionslab.ira.PineFinniftyOff.KEY

    /**
     * Once, on this update: the armed "breakdown below the last 20 candles' low" script on FINNIFTY 15-minute
     * ([com.optionslab.ira.PineFinniftyOff]) is switched off - never deleted; an option it holds is managed to its exit
     * ([Book.winding]) - said in its log and in a notice. Boss can arm it again. True when [b] changed (it is then saved).
     */
    private fun finniftyOff(b: Book): Boolean {
        if (FIN_OFF in b.migrated) return false
        val items = PineScripts.loadNow() ?: return false
        for (item in items.filter { it.auto.on && com.optionslab.ira.PineFinniftyOff.matches(it.name, it.code, it.auto.symbol, it.auto.interval) }) {
            PineScripts.setAuto(item.id, item.auto.copy(on = false))
            b.liveOk.remove(item.id)
            val holding = b.held.containsKey(item.id)
            if (holding) b.winding[item.id] = "switched off on Boss's 08 Oct yes (FINNIFTY too thin)"
            val text = com.optionslab.ira.PineFinniftyOff.notice(item.name)
            note(b, item.id, text + if (holding) " Its open option is managed to its exit; nothing more is bought." else "")
            runCatching { Diag.record("pine", text) }
            runCatching { Notifier.post(app, 6957, Notifier.SCHEDULE, com.optionslab.ira.PineFinniftyOff.TITLE, text, "pine") }
        }
        b.migrated += FIN_OFF
        return true
    }

    /**
     * 14 Oct (Boss: signal on the index, then buy the option): every switched-on script whose chart is not an index
     * ([com.optionslab.ira.PineUnderlying]) is switched off - never deleted - and said in its log; an option it holds is
     * managed to its exit (its stop, target, lock, 15:15; [Book.winding]), never dumped, and nothing more is bought. Reads the
     * scripts already in memory; writes only when one is found. True when [b] changed (it is then saved).
     */
    private fun offNonIndex(b: Book): Boolean {
        val bad = PineScripts.items.value.filter { it.auto.on && com.optionslab.ira.PineUnderlying.rootOf(it.auto.symbol) == null }
        for (item in bad) {
            PineScripts.setAuto(item.id, item.auto.copy(on = false))
            b.liveOk.remove(item.id)
            val holding = b.held.containsKey(item.id)
            if (holding) b.winding[item.id] = "its chart is not an index"
            note(b, item.id, "Switched off: ${com.optionslab.ira.PineUnderlying.refusal(item.auto.symbol)}." +
                if (holding) " Its open option is managed to its exit; nothing more is bought." else "")
        }
        return bad.isNotEmpty()
    }

    /**
     * 14 Oct: the armed scripts the saved file's load switched off for a chart that is not an index
     * ([PineScripts.takeRefused]). One holding an option is put in [Book.winding] at once, before anything else looks at
     * it: its stop, target, profit lock, 15:15 and the day's stop keep being watched ([watchOnlyLocked], [one]) and it is
     * never sold at market by the switch-off; nothing more is bought. Also without that note (a restart after the file
     * was rewritten switched off but before this book saved its winding): any holding of a switched-off script on a chart
     * that is not an index is managed the same way. Memory only (never reads the vault): safe on the fast position path.
     * True when [b] changed.
     */
    private fun refusedHeld(b: Book): Boolean {
        var changed = false
        val items = PineScripts.items.value
        val offNonIndex = b.held.keys.filter { id ->
            items.firstOrNull { it.id == id }?.let { !it.auto.on && com.optionslab.ira.PineUnderlying.rootOf(it.auto.symbol) == null } == true
        }
        for (id in PineScripts.takeRefused() + offNonIndex) {
            if (!b.held.containsKey(id) || id in b.winding) continue
            b.winding[id] = "its chart is not an index"
            note(b, id, "Its open option is managed to its exit (its stop, target and profit lock); nothing more is bought.")
            changed = true
        }
        return changed
    }

    /**
     * 14 Oct: two armed scripts saved under two spellings of one index ("NIFTY 50" and "NIFTY") with the same code and chart
     * were not the same to the one-time 06 Oct check ([dedupe]); written the index's own way on load they are, and both
     * would trade every signal. Once per load: of each such pair the first armed (the oldest) keeps trading and the later one
     * is switched off - never deleted - said in its log; an option it holds is managed to its exit ([Book.winding]). Other
     * scripts on the same index stay armed. True when [b] changed (it is then saved).
     */
    private fun renamedDupes(b: Book): Boolean {
        val items = PineScripts.loadNow() ?: return false
        val renamed = PineScripts.takeRenamed().ifEmpty { return false }      // nearly every pass: nothing to look at
        val extra = com.optionslab.ira.PineDupes.extraArmedAfterRename(items.map {
            com.optionslab.ira.PineDupes.Script(it.id, it.code, it.auto.symbol, it.auto.interval, it.auto.on)
        }, renamed)
        for ((s, kept) in extra) {
            val item = PineScripts.get(s.id) ?: continue
            PineScripts.setAuto(s.id, item.auto.copy(on = false))
            b.liveOk.remove(s.id)
            if (b.held.containsKey(s.id)) b.winding[s.id] = "the same strategy as #$kept"
            note(b, s.id, com.optionslab.ira.PineDupes.note(kept))
        }
        return extra.isNotEmpty()
    }

    /**
     * Switch a script off by the app's own rule (Boss's yes to switching a losing script off, [com.optionslab.ira.ArmCutoff]):
     * unlike [arm] off, what it holds is NOT sold now - it is managed to its exit, and nothing more is bought. Never
     * switches anything on. Returns "ok", or why not.
     */
    suspend fun windDown(id: Long, why: String): String = lock.withLock {
        val item = PineScripts.get(id) ?: return@withLock "not found"
        if (!item.auto.on) return@withLock "already off"
        val b = book()
        PineScripts.setAuto(id, item.auto.copy(on = false))
        b.liveOk.remove(id)
        val holding = b.held.containsKey(id)
        if (holding) b.winding[id] = why
        note(b, id, "Switched off: $why." + if (holding) " Its open option is managed to its exit; nothing more is bought." else "")
        save(b)
        "ok"
    }

    private fun label(item: PineScripts.Item) = "Pine · ${item.name}"

    // ---- the switched-off shadow (30 Oct 2026) ----------------------------------------------------------------------------

    /** A script's key in the switched-off shadow ([OffShadow]): "pine:<id>" (as the trade manager's). */
    fun shadowKey(id: Long): String = "pine:$id"

    /**
     * Whether a switched-off script runs in the shadow: one that places orders (alerts-only scripts never trade) on an index chart
     * (Boss's 14 Oct rule: the signal is read on the index; any other chart is never run), never in IraGoldAlgo. Memory only.
     */
    internal fun shadowable(item: PineScripts.Item): Boolean = !com.optionslab.app.BuildConfig.GOLD && item.auto.mode == "trade" &&
        com.optionslab.ira.PineUnderlying.rootOf(item.auto.symbol) != null

    /** What a script's row says while it is off: its switched-off shadow's record ("shadow (switched off): 3 trades, ..."). Memory only. */
    fun offWords(id: Long): String = OffShadow.line(shadowKey(id))

    /**
     * The script's own exits for a would-be trade bought near [price] ([qty] units), as the shadow's rules can hold them:
     * - its stop-loss [PineScripts.Auto.stopPts] under the price ([ProfitLock.pineBase], as the resting stop) and its target
     *   [PineScripts.Auto.targetPts] over it. The real ones count from the fill; the shadow fills at the ask plus the half-spread,
     *   a touch above [price], so the shadow's stop is that touch wider and its target that touch nearer (the nearest it allows).
     * - its profit lock, the nearest the shadow's one-rung lock (out when the net falls to a share of its best) allows: the
     *   earliest rung that keeps a share of the gain - the ladder's "at half the target, lock a quarter of it" (keeps 50%), or the
     *   trail's first step (+8% keeps 50% by default) when that comes sooner. The ladder's breakeven rung and later, tighter steps
     *   have no place there (a comment, not a guess).
     * - 15:15: the shadow's own NSE square-off (15:05 on the option's expiry day) applies to every would-be trade; with the
     *   script's own 15:15 sell switched off it is still the backstop (an MIS trade is squared off that day either way).
     * - a change of signal: [shadowSignalExit]. The daily loss limit has no place on one trade (not mapped).
     */
    internal fun shadowExits(a: PineScripts.Auto, price: Double, qty: Int): com.optionslab.engine.orb.OffShadow.Exits {
        val stop = ProfitLock.pineBase(price, a.stopPts)
        val target = if (a.targetPts.isFinite() && a.targetPts > 0) price + a.targetPts else null
        val rungs = if (!a.profitLock) emptyList() else {
            val ladder = ProfitLock.pineReference(a.targetPts, a.stopPts)?.let { listOf(0.5 * it to 0.5) }.orEmpty()
            val trail = a.trail.clean().steps.filter { it.startPct > 0 && it.keepPct > 0 }.map { price * it.startPct / 100.0 to it.keepPct / 100.0 }
            ladder + trail
        }
        val first = rungs.filter { it.first.isFinite() && it.first > 0 }.minByOrNull { it.first }
        return com.optionslab.engine.orb.OffShadow.Exits(stop = stop, target = target, lockAfterRs = first?.let { it.first * qty },
            lockKeep = first?.second ?: 0.6, exitAt = if (a.squareOff) java.time.LocalTime.of(15, 15) else null)
    }

    /**
     * A switched-off (or hidden) script's would-be entry: the option [enterNow] would have picked (the ATM [right] of the nearest
     * expiry after today, on the index close [spot]) recorded in the switched-off shadow with the script's exits ([shadowExits]).
     * Nothing is placed: no paper order, no Zerodha order, no live mirror. The order flow gate and the one-index-one-side claim are
     * not asked (both keep state for real entries; a would-be trade must not hold back a real one). [bar]: the signal's candle.
     */
    private suspend fun shadowEnter(b: Book, item: PineScripts.Item, right: Right, spot: Double, bar: Long, signal: String) {
        val id = item.id
        val u = item.auto.symbol
        val shadowSays = { text: String -> note(b, id, "Shadow (switched off): nothing bought, not recorded - $text") }
        // Unit tests that have not set the shadow's prices never read one for it (no test may reach the network); the app never
        // sets either seam.
        if (Paper.testSkipFeedChecks && OffShadow.testPrice == null) return
        // The shadow's book not read (not set up): nothing to record into, and no price read for it.
        if (!OffShadow.refresh().loaded) return
        if (u == "SENSEX") { shadowSays("SENSEX options trade on BSE"); return }
        val strike = OrbRules.atmStrike(spot, strikeStep(u))
        val listed = Market.contracts().filter { it.underlying == u }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryAfter(todayIst(), listed) ?: run { shadowSays("no $u expiry after today is listed"); return }
        val c = Paper.contractFor(u, expiry, strike.toDouble(), right) ?: run { shadowSays("$u $strike $right is not listed"); return }
        // The thin-option check stands for the shadow too: what the script would really have refused is not recorded as bought.
        thinRefusal(c)?.let { shadowSays("it would not have been bought ($it)"); return }
        val px = runCatching { Paper.lastPrice(c) }.getOrNull()?.takeIf { it > 0 } ?: run { shadowSays("no price for ${c.symbol}"); return }
        val lots = item.auto.lots.coerceIn(1, 50)
        val said = OffShadow.enter(OffShadow.Ask(shadowKey(id), label(item), "$id|$bar", c, if (right == Right.CE) 1 else -1,
            shadowExits(item.auto, px, lots * c.lotSize.coerceAtLeast(1)),
            "Pine $signal on $u ${item.auto.interval} at ${"%.2f".format(java.util.Locale.ENGLISH, spot)} (signal on the index)", lots = lots))
        // "shadow (switched off): would buy ... (nothing bought)", or why it was not recorded.
        note(b, id, said.replaceFirstChar { it.uppercase() })
    }

    /**
     * The script's signal exit for its open would-be trades: one the signal no longer backs ([want]: "CE", "PE" or null for none) is
     * closed in the shadow at the quote ([OffShadow.exitNow]), as a change of signal sells a real holding. Memory read; a close only
     * when one is due.
     */
    private suspend fun shadowSignalExit(item: PineScripts.Item, want: String?) {
        val key = shadowKey(item.id)
        val open = OffShadow.view.value.openOf(key).ifEmpty { return }
        val wantView = when (want) { "CE" -> 1; "PE" -> -1; else -> 0 }
        for (p in open) if (p.view != wantView) {
            val prefix = "${p.day}|$key|"
            if (p.ref.startsWith(prefix)) runCatching { OffShadow.exitNow(key, p.ref.removePrefix(prefix), "signal changed") }
        }
    }

    /**
     * TEST ONLY: a fixed clock and a candle source for the auto-trader's own checks (market hours, 15:15, the day,
     * the stale-candle guard). Both are null in the app, always: the time is then [Market.now] (market hours as
     * [Market.isOpen]) and candles come from [ChartFeed.bars], exactly as before. Their setters throw unless
     * BuildConfig.DEBUG (as Broker.testEndpoint), and no app code sets them; only the unit tests do.
     */
    @Volatile internal var testNow: java.time.ZonedDateTime? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }; field = v }
    @Volatile internal var testBars: ((symbol: String, interval: String) -> List<com.optionslab.engine.Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test candles exist only in debug builds" }; field = v }
    private fun clock(): java.time.ZonedDateTime = testNow ?: Market.now()
    private fun todayIst(): java.time.LocalDate = testNow?.toLocalDate() ?: Market.today()
    private fun isOpen(): Boolean = testNow?.let { Market.isOpenAt(it.toLocalDate(), it.hour * 60 + it.minute) }
        ?: Market.isOpen()
    private fun epochSecondNow(): Long = testNow?.toEpochSecond() ?: (System.currentTimeMillis() / 1000)

    /**
     * The book forgotten and its file deleted (26 Oct, Erase everything: [PersonalStores]). A pass under way keeps its old book
     * only until its save or publish, which are then dropped. Never reads the vault; never waits on the book's lock ([lock]).
     */
    fun wipe() {
        synchronized(this) {
            wipes++
            cache = null; written.forget(); _held.value = emptyMap(); _log.value = emptyList()
            stopNoted.clear(); removingHint = false; flatSince.clear(); flatAlertSaid.clear(); reentrySaid.clear()
            ownCancels.clear(); ownCancelAt.clear(); ownRestDue.clear(); lostExitWhy.clear(); pendingWrite.set(null); bookedSales.clear(); notedOnce.clear()
            squareMarks.clear()
            // (24 Nov 2026, B6-I round 6: erased - no lost exit is kept in a book any more: [lostPending] knows them all.)
            lostUnknownUntil = 0L; lostAsideUntil = 0L
        }
        // (22 Nov 2026, B6 review: the lost sales and buys still looked for under its keys forgotten with it - never one a later holding reads.)
        runCatching { (Broker.lostKeys("pineexit:") + Broker.lostKeys("pinebuy:")).forEach { Broker.forgetLost(it) } }
        if (::file.isInitialized) file.delete()
    }

    /** Whether its book (holdings, log, day's P&L, pauses) is held in memory or shown. Memory only. */
    internal fun holdsForErase(): Boolean = runCatching {
        cache?.let { b -> b.held.isNotEmpty() || b.log.isNotEmpty() || b.dayPnl.isNotEmpty() || b.paused.isNotEmpty() || b.winding.isNotEmpty() ||
            b.liveOk.isNotEmpty() || b.lastBar.isNotEmpty() || b.lastTarget.isNotEmpty() || b.autoSaved.isNotEmpty() ||
            b.removing.isNotEmpty() || b.closed.isNotEmpty() } == true || _held.value.isNotEmpty() || _log.value.isNotEmpty()
    }.getOrDefault(false)

    /** Its book's file (none before [init]). */
    internal fun filesForErase(): List<File> = if (::file.isInitialized) listOf(file) else emptyList()

    /**
     * Reset paper: paper holdings and their day's P&L / pause go; scripts holding at Zerodha keep theirs. A flat script's
     * tally and daily-loss pause are not marked paper or live, so they are cleared only while the app is in Paper: in Live
     * they are the Zerodha account's, as are those of a script cleared to trade live, and a reset of the paper account must never lift a live daily-loss pause.
     */
    suspend fun resetPaper() = lock.withLock {
        val b = book()
        val dropped = b.held.filterValues { !it.live }.keys.toSet()
        b.held.entries.removeAll { !it.value.live }
        // Never for a script cleared for Zerodha (even with the app back in Paper): its pause may be a live one.
        val live = OrbArms.liveNow()
        val clear = { id: Long -> b.liveOk[id] != true && (id in dropped || (!live && id !in b.held)) }
        b.dayPnl.keys.removeAll(clear)
        b.paused.keys.removeAll(clear)
        save(b); publish(b)
    }

    suspend fun load() = lock.withLock {
        val b = book()
        // 14 Oct: the scripts are read first, so a refused one's holding is in winding (watched) before any pass looks at it.
        PineScripts.loadNow()
        if (refusedHeld(b) or ruleNotes(b) or renamedDupes(b)) save(b)
        Unit
    }

    /**
     * The lines Boss's 06 Oct rule left when it protected an armed script on load ("Pine 'EMA': stop-loss 30 and target 60
     * added, profit lock on — Boss's 06 Oct rule"), written to its log once. True when any was.
     */
    private fun ruleNotes(b: Book): Boolean {
        val notes = PineScripts.takeRuleNotes()
        notes.forEach { (id, text) -> note(b, id, text) }
        return notes.isNotEmpty()
    }

    /**
     * Switch a script's auto-trading on or off. Off sells what it holds. [pinConfirmed]
     * (the PIN or fingerprint was asked just now) lets it place live orders. 30 Oct: off, it keeps reading its signal on every
     * completed candle and each would-be entry is recorded in the switched-off shadow ([OffShadow]); nothing is bought.
     */
    suspend fun arm(id: Long, on: Boolean, pinConfirmed: Boolean = false): String = lock.withLock {
        val item = PineScripts.get(id) ?: return@withLock "not found"
        // 15 Oct (Boss's cleanup): Pine auto-trading hidden - a script places orders again only after its restore (Hidden arms).
        // Alerts-only scripts never trade and are not hidden.
        if (on && item.auto.mode == "trade" && !HiddenArms.mayArm(com.optionslab.engine.orb.ArmsCleanup.PINE))
            return@withLock HiddenArms.refusal("Pine auto-trading")
        // Boss's 06 Oct rule: a script that places orders switches on only with a stop-loss and a target.
        if (on) com.optionslab.ira.PineProtection.armRefusal(item.auto.mode, item.auto.stopPts, item.auto.targetPts)?.let { return@withLock it }
        // 14 Oct (Boss): the signal is read on the index chart, then the option is bought. An option's own chart, an MCX
        // contract or any other symbol is refused and the script stays off; an index written another way is armed as its root.
        val root = if (on) com.optionslab.ira.PineUnderlying.rootOf(item.auto.symbol)
            ?: return@withLock com.optionslab.ira.PineUnderlying.refusal(item.auto.symbol) else item.auto.symbol
        val b = book()
        b.winding.remove(id)
        if (on) {
            b.liveOk[id] = pinConfirmed
            // 30 Oct: saved for auto-trading from now on - switched off later, it runs in the switched-off shadow ([shadowable]).
            b.autoSaved += id
            b.lastTarget.remove(id); b.lastBar.remove(id)
            PineScripts.setAuto(id, item.auto.copy(on = true, symbol = root))
            note(b, id, "Switched on (${if (item.auto.mode == "alert") "alerts only" else if (OrbArms.liveNow()) "Live" else "Paper"}): waiting for the next signal on $root ${item.auto.interval}")
        } else {
            PineScripts.setAuto(id, item.auto.copy(on = false))
            b.liveOk.remove(id)
            b.held[id]?.let { h -> runCatching { exit(b, id, item, h, "switched off") } }
            note(b, id, "Switched off")
        }
        save(b)
        "ok"
    }

    /** Called by the market watch every pass. */
    suspend fun tick() = lock.withLock {
        // A restore not yet disarmed: every script counts as switched off (what it holds is still sold).
        val disarm = com.optionslab.app.security.SecurePrefs.getBoolean(Backup.DISARM, false)
        val b = book()
        // 19 Nov 2026 (audit): the closed holdings' exits still being taken out as the pass starts (one try each this pass).
        val removing = b.removing.toList()
        // 14 Oct: a script refused on load for a chart that is not an index: its holding managed to its exit, never sold by
        // the switch-off (before the "no longer auto-trading" sale below).
        PineScripts.loadNow()
        if (refusedHeld(b)) save(b)
        if (ruleNotes(b)) save(b)
        // The one-time switch-off of duplicate scripts (Boss's 06 Oct diagnostics), before anything trades.
        if (!disarm && dedupe(b)) save(b)
        // The one-time FINNIFTY breakdown switch-off (Boss's 08 Oct yes), before anything trades.
        if (!disarm && finniftyOff(b)) save(b)
        // 14 Oct: a script switched on some other way on a chart that is not an index (the saved file and [arm] both refuse
        // one) is switched off before anything runs, said once; an option it holds is managed to its exit.
        if (!disarm && offNonIndex(b)) save(b)
        // 14 Oct: two spellings of one index, written the same on load, are one script: the later one is switched off.
        if (renamedDupes(b)) save(b)
        val all = PineScripts.items.value
        val on = if (disarm) emptyList() else all.filter { it.auto.on }
        // Switched off by the app's own rule while holding: managed to its exit, buying nothing more.
        b.winding.keys.removeAll { it !in b.held || all.firstOrNull { x -> x.id == it }?.auto?.on == true }
        val winding = if (disarm) emptyList() else all.filter { it.id in b.winding }
        // 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only"): every script switched on is
        // saved for auto-trading; one switched off again (by Boss, the kill switch, a restore, the app's own rules, or hidden) keeps
        // reading its signal and records each would-be entry in the switched-off shadow - nothing bought. A script deleted from the
        // library is forgotten (never while the library reads empty: an unread file is not a deletion).
        on.forEach { b.autoSaved += it.id }
        if (all.isNotEmpty()) all.map { it.id }.toSet().let { ids -> b.autoSaved.retainAll(ids) }
        // Off and holding nothing (a holding still being sold is left to its sale, never read twice in one pass); winding ones
        // are read below as before (their holding's exits first), with their would-be entries shadowed too.
        // 30 Oct (review): never while any script holds an option - this pass holds the lock the held one's fast look ([watchOnly],
        // the live lane) needs, and the shadow's reads must never delay it (positions first). At most [SHADOW_PER_PASS] a pass.
        val shadowOnly = if (b.held.isNotEmpty()) emptyList() else all.filter { it.id in b.autoSaved && on.none { x -> x.id == it.id } &&
            winding.none { x -> x.id == it.id } && it.id !in b.held && shadowable(it) }
            .let { l -> if (l.size <= SHADOW_PER_PASS) l else { val k = shadowTurn++ % l.size; (l.drop(k) + l.take(k)).take(SHADOW_PER_PASS) } }
        if (on.isEmpty() && b.held.isEmpty() && shadowOnly.isEmpty()) {
            // 19 Nov 2026 (audit): a closed live holding's exits still resting at Zerodha are taken out on every pass until confirmed.
            if (removing.isNotEmpty()) { removeExits(b, removing); save(b) }
            return@withLock
        }
        // Held by a script that is no longer on (turned off elsewhere, a restore, deleted): sell it.
        for ((id, h) in b.held.toMap()) if (on.none { it.id == id } && winding.none { it.id == id }) {
            val item = all.firstOrNull { it.id == id } ?: PineScripts.Item(id, "deleted script", "")
            runCatching { exit(b, id, item, h, "no longer auto-trading") }
        }
        val stopped = dayStopped()
        // 22 Oct (arms audit): a hidden script still holding an option (the cleanup waits for its exit) is managed to that exit
        // and buys nothing more - before, a signal flip sold the call and bought the put from an arm the Hidden arms list said
        // was off. The exits below run as before; only new entries are held back ([HiddenArms.blocks]: memory only).
        val hidden = HiddenArms.blocks(com.optionslab.engine.orb.ArmsCleanup.PINE)
        // 30 Oct: hidden is off - a hidden script's would-be entries go to the switched-off shadow (it still may not be switched on
        // while hidden: [arm]).
        for (item in on) runCatching { one(b, item, stopped, entries = !hidden, shadow = hidden && shadowable(item)) }.onFailure { e -> note(b, item.id, "Error: ${e.message ?: e.javaClass.simpleName}") }
        for (item in winding) runCatching { one(b, item, stopped, entries = false, shadow = shadowable(item)) }.onFailure { e -> note(b, item.id, "Error: ${e.message ?: e.javaClass.simpleName}") }
        // 30 Oct: switched off, holding nothing: its signal read on its candles as when on, each would-be entry to the shadow only.
        for (item in shadowOnly) runCatching { one(b, item, stopped, entries = false, shadow = true, quiet = true) }
            .onFailure { e -> runCatching { Diag.record("pine", "${label(item)} (shadow): ${e.javaClass.simpleName}") } }
        // 19 Nov 2026 (audit): after the holdings' own exits (they come first), a closed live holding's exits still resting at Zerodha
        // (one just closed this pass was tried at once).
        if (removing.isNotEmpty()) removeExits(b, removing)
        save(b)
    }

    /** 30 Oct (review): the switched-off scripts read in shadow on one pass, at most (each reads its candles). */
    private const val SHADOW_PER_PASS = 3
    /** Where the next pass's shadow scripts start (turn by turn, so each is read in its turn). Memory only. */
    @Volatile private var shadowTurn = 0

    /**
     * Whether the scripts stand still this pass: the day's stop, or the kill switch in Live. The kill switch guards Zerodha
     * only: in Paper mode the scripts keep trading ("Stop for today" still stops them). Who stopped the day is said in the
     * script's log (by Boss, the daily loss limit, the tile) - never just "stopped" ([stopSays], [stopResumes]).
     */
    private suspend fun dayStopped(): Boolean {
        val s = AppSettings.load()
        val dayWhy = Strategies.stoppedWhy()
        stopSays = dayWhy?.let { "the day's stop (${com.optionslab.ira.DayStop.by(it)})" } ?: "the day's stop (the kill switch is on in Live)"
        stopResumes = dayWhy?.let { com.optionslab.ira.DayStop.mayLift(it) } ?: false
        return dayWhy != null || (s.guardKill && OrbArms.liveNow())
    }

    /**
     * Whether any script holds an option now (as of the last load or save; read without the lock): the 15-second check runs.
     * 19 Nov 2026 (audit): also while a closed live holding's exits are still being taken out at Zerodha ([Book.removing]).
     */
    fun holding(): Boolean = _held.value.isNotEmpty() || removingHint

    /** Zerodha's trading symbols of the confirmed live holdings (round 2: followed on the stream, their exits decided from it). */
    fun liveSymbols(): List<String> = _held.value.values.filter { it.live && !it.unconfirmed }.mapNotNull { it.kite }.distinct()

    /**
     * True while a fast look decides from the stream (round 2, set under [lock] by [watchOnly]): Zerodha's orders as last
     * read ([Broker.ordersRecon]) and the stream's fresh tick for the price; only an order goes over REST.
     */
    @Volatile private var streamLook = false

    /**
     * The 15-second check between full passes (08 Oct, research/PROFIT_LOCK_8OCT.md fix 3): only the money exits of what
     * the scripts hold - a resting stop that filled, the stop-loss, the target, the profit lock (the resting stop moved up),
     * the script's daily loss limit, 15:15 and the day's stop. No candles are read for a signal and nothing is bought. Under
     * the same lock as [tick], so one holding never has two sells in flight; a holding the full pass must settle (a live
     * buy not yet confirmed, a script no longer on) is left to it.
     */
    suspend fun watchOnly(fast: Boolean = false, stream: Boolean = false) {
        // 19 Nov 2026 (second review): a fast look with no option held does nothing (a closed holding's exits are taken out by the
        // 15-second check and the pass), so it never waits on the lock behind a pass's candle reads - the LIVE lane's next books'
        // stops come first. Memory only.
        if (fast && _held.value.isEmpty()) return
        lock.withLock {
            streamLook = fast && stream
            try { watchOnlyLocked(fast) } finally { streamLook = false }
        }
    }

    private suspend fun watchOnlyLocked(fast: Boolean) {
        val b = book()
        // 19 Nov 2026 (audit): a closed live holding's exits still resting at Zerodha are taken out on every check until confirmed -
        // on the 15-second check only (never on a fast look: no Zerodha reads and no write per tick there), after the holdings' exits.
        if (b.held.isEmpty()) { if (!fast && b.removing.isNotEmpty()) { removeExits(b); save(b) }; return }
        val removing = if (fast) emptyList() else b.removing.toList()
        // [fast]: an event-driven look ([FastPath]). Only the best premium seen changed (peak): kept in memory and written with
        // the next save, never a Keystore write per tick; a stop moved, a sale or a note is written now as before.
        val before = if (fast) untracked(b) else null
        // 14 Oct: a script refused on load for a non-index chart: its holding is watched from the first look (memory only).
        refusedHeld(b)
        // A restore not yet disarmed: the full pass sells what is held.
        if (com.optionslab.app.security.SecurePrefs.getBoolean(Backup.DISARM, false)) return
        val stopped = dayStopped()
        val all = PineScripts.items.value
        val t = clock()
        val mins = t.hour * 60 + t.minute
        for ((id, h) in b.held.toMap()) {
            if (h.unconfirmed || h.day != todayIst().toString()) continue
            val item = all.firstOrNull { it.id == id } ?: continue
            if (!item.auto.on && id !in b.winding) continue
            runCatching {
                // 22 Nov 2026 (B6 review, item 2): a lost exit sale still looked for: nothing more sold, no stop beside it ([lostExit]; a fast
                // look only holds it - its booking or give-up is the 15-second check's and the pass's).
                if (lostExit(b, id, item, h, quick = fast) == LostExit.HELD) return@runCatching
                val h1 = b.held[id] ?: return@runCatching
                if (stopFilled(b, id, item, h1)) return@runCatching
                val cur = b.held[id] ?: return@runCatching
                if (stopped || (item.auto.squareOff && mins >= 15 * 60 + 15)) {
                    exit(b, id, item, cur, if (stopped) stopSays else "15:15 square-off")
                    return@runCatching
                }
                guard(b, id, item, cur)
                Unit
            }.onFailure { e -> note(b, id, "Error: ${e.message ?: e.javaClass.simpleName}") }
        }
        if (removing.isNotEmpty()) removeExits(b, removing)
        // 26 Oct: [b] is the cached book (an erase since: [publish] shows nothing, [save] writes nothing).
        if (before != null && before == untracked(b)) publish(b) else save(b)
    }

    /** Zerodha's orders: the kept read on a stream look ([streamLook]), else read now. */
    private suspend fun kiteOrders(): List<Broker.OrderRow> = if (streamLook) Broker.ordersRecon() else Broker.orders()

    /** What a fast look may leave unsaved: [b]'s holdings without their best premium seen, and its log's length. */
    private fun untracked(b: Book): Triple<Map<Long, Held>, Int, List<Removing>> =
        Triple(b.held.mapValues { it.value.copy(peak = 0.0) }, b.log.size, b.removing.toList())

    private fun stepSeconds(iv: String): Long = when (iv) { "1m" -> 60; "5m" -> 300; "15m" -> 900; "1h" -> 3600; else -> 86400 }
    private fun lookbackDays(iv: String): Long = when (iv) { "1m" -> 4; "5m" -> 12; "15m" -> 30; "1h" -> 90; else -> 700 }

    /** What holds the scripts this pass ([tick]): "the day's stop (by you)", "... (by the daily loss limit)", the kill switch. */
    @Volatile private var stopSays = "the day's stop"
    /** Whether that stop is lifted by "start all" / Start bot (never the daily loss limit's). */
    @Volatile private var stopResumes = false
    /** Scripts whose log already says today why they stand still (said once a day for each reason). */
    private val stopNoted = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun noteStopOnce(b: Book, id: Long) {
        if (!stopNoted.add("$id|${todayIst()}|$stopSays")) return
        note(b, id, "Paused by $stopSays: no new entries today" +
            if (stopResumes) " (\"start all\" to Jarvis, or Start bot on Home, resumes it)." else ".")
    }

    /**
     * [entries] false: a script switched off by the app's rule ([Book.winding]) - its holding managed to its exit, nothing bought.
     * 30 Oct: [shadow] - a would-be entry (the signal that would have bought, with entries off) goes to the switched-off shadow
     * ([shadowEnter]: priced and kept there, never an order). [quiet]: a switched-off script holding nothing - its log says only
     * what the shadow did (no pause, feed or script-error lines each pass).
     */
    private suspend fun one(b: Book, item: PineScripts.Item, stopped: Boolean, entries: Boolean = true, shadow: Boolean = false,
                            quiet: Boolean = false) {
        val id = item.id
        val tag = if (quiet) "${com.optionslab.engine.orb.OffShadow.LABEL.replaceFirstChar { it.uppercase() }}: " else ""
        val t = clock()
        val mins = t.hour * 60 + t.minute
        var h = b.held[id]
        // 22 Nov 2026 (B6 review): a live exit sale whose answer was lost and is still looked for comes first - before an unconfirmed buy is
        // settled and given its stop: a stop rested now could sit beside a sale that filled (on a flat contract). The holding is left alone
        // this pass - no stop beside the sale, no new sale, nothing bought ([lostExit]); found, it was booked; given up on, its stop is back.
        if (h != null && h.live && lostExit(b, id, item, h) == LostExit.HELD) {
            // (A buy not confirmed yet is still settled meanwhile - its unfilled rest cancelled, so nothing more is bought beside the sale -
            // but no stop is rested for it ([placeLiveStop] waits for the sale's search). Shown with nothing filled, it is kept for that
            // search to end: a sale that went with no buy is told there.)
            // (One whose sale was found already was settled there, just now.)
            if (foundSale(b, lostExitKeyOf(id, h)) == null) b.held[id]?.takeIf { it.unconfirmed }?.let { u -> settle(b, id, u)?.let { s -> b.held[id] = s } }
            return
        }
        h = b.held[id]
        // A buy Zerodha did not confirm: settled from the order book first (its unfilled rest cancelled).
        if (h != null && h.unconfirmed) {
            h = settle(b, id, h)
            // Confirmed now: its stop-loss rests at Zerodha from here on (fix 1), as after a confirmed buy.
            if (h != null && !h.unconfirmed && h.live && h.stop == null) h = placeLiveStop(b, item, h.copy(at = if (h.at > 0L) h.at else System.currentTimeMillis()))
            if (h == null) b.held.remove(id) else b.held[id] = h
        }
        // The resting stop filled (the paper book or Zerodha sold at it): that was the exit.
        if (h != null && !h.unconfirmed && stopFilled(b, id, item, h)) return
        h = b.held[id]
        // Sold outside the app (a notification's Close, the Trade tab, the broker's square-off).
        if (h != null && !h.unconfirmed && gone(b, id, h)) {
            note(b, id, "${h.symbol} is no longer held (closed outside the auto-trader)")
            managerClosed(id, h, null, "closed outside")
            b.held.remove(id)
            // 19 Nov 2026 (audit): a live one's stop and own exits are taken out and confirmed gone at Zerodha ([takeOutExits]).
            if (h.live) takeOutExits(b, id, h) else dropStop(h)
            h = null
        }
        if (h != null && (stopped || (item.auto.squareOff && mins >= 15 * 60 + 15) || h.day != todayIst().toString())) {
            exit(b, id, item, h, if (stopped) stopSays else "15:15 square-off"); return
        }
        // 22 Nov 2026 (B6 review): a stop the app took out for an exit whose re-rest could not be decided then ([ownRestDue]): decided now.
        if (h != null && !h.unconfirmed && h.live && h.stop == null && !streamLook) {
            val k = lostStopKey(item, h)
            ownRestDue.remove(k)?.let { sid ->
                when (ownRestAgain(h, runCatching { Broker.orders() }.getOrNull(), sid)) {
                    true -> stopNotRested += k
                    null -> ownRestDue[k] = sid
                    false -> Unit
                }
            }
        }
        // 20 Nov 2026 (audit B5 deep review): a live stop whose reply was lost is looked for on each check until found or given up on -
        // only while the holding is still held and due no exit, in market hours before 15:15, at the level it was sent at.
        if (h != null && !h.unconfirmed && h.live && h.stop == null && isOpen() && mins < 15 * 60 + 15 &&
            (Broker.lostPending(lostStopKey(item, h)) || lostStopKey(item, h) in stopNotRested)) {
            // (22 Nov 2026, B6 review, third look: a stop put back is sized on fresh reads - never above what Zerodha holds less its other sales.)
            h = placeLiveStop(b, item, h, h.stopAt, capToHeld = true)
            b.held[id] = h
        } else if (h != null && !h.unconfirmed && h.live && h.stop != null && isOpen() && mins < 15 * 60 + 15 && stopShort.containsKey(lostStopKey(item, h))) {
            // (20 Nov 2026, delta review: a stop resting for only part of the holding is grown once room appears.)
            h = topUpStop(b, item, h)
        }
        val a = item.auto
        val today = todayIst().toString()
        if (h != null && guard(b, id, item, h)) return
        h = b.held[id]
        if (a.maxDayLoss > 0 && realizedToday(b, id) <= -a.maxDayLoss && b.paused[id] != today) {
            b.paused[id] = today; note(b, id, "Daily loss limit reached: no more trades today")
        }
        if (b.paused[id] == today) { b.lastBar.remove(id); return }
        // Stopped for the day or the kill switch: a real pause - on the way back a signal that changed meanwhile is not chased.
        if (stopped) { b.lastBar.remove(id); if (!quiet) noteStopOnce(b, id); return }
        if (!isOpen() || mins >= 15 * 60 + 15) return
        // 14 Oct: a script refused for a chart that is not an index never reads a signal there - not even to exit; what it
        // holds goes only by its own stop, target, profit lock, 15:15 or the day's stop (above).
        if (!entries && com.optionslab.ira.PineUnderlying.rootOf(item.auto.symbol) == null) return
        val script = PineScripts.script(item) ?: run { if (!quiet) note(b, id, "The script has errors: nothing traded"); return }
        val step = stepSeconds(item.auto.interval)
        val now = epochSecondNow()
        // At most 15 s: this runs in the watch's risk steps, before the stops, the expiry square-off and the strategies.
        // The fetch runs apart and is only waited for, so even a read stuck in the network cannot hold the pass.
        val fetch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).async {
            testBars?.invoke(item.auto.symbol, item.auto.interval)
                ?: ChartFeed.bars(item.auto.symbol, item.auto.interval, now - lookbackDays(item.auto.interval) * 86400, null, quick = true)
        }
        val fetched = kotlinx.coroutines.withTimeoutOrNull(15_000) { fetch.await() }
            ?: run { fetch.cancel(); if (!quiet) note(b, id, "No candles for ${item.auto.symbol} within 15 s: this pass skipped"); return }
        val bars = fetched.filter { it.epochSecond + step <= now }                    // completed candles only
        val last = bars.lastOrNull() ?: return
        // A feed that failed today can hand back yesterday's candles: never trade on those, or on a stalled feed.
        if (step < 86_400 && (java.time.Instant.ofEpochSecond(last.epochSecond).atZone(com.optionslab.engine.IST).toLocalDate() != todayIst() ||
                now - last.epochSecond > step * 3 + 120)) return
        if (b.lastBar[id] == last.epochSecond) { owedExit(b, id, item, h); return }
        // Only a pause the owner made (stopped for the day, the kill switch, its daily loss limit) means a signal that changed
        // meanwhile is not chased. Missed candles (a slow pass, a failed fetch, the app away) are not a pause: every pass
        // reads the day's candles again and acts on the signal as it is now (29 Sep).
        val watchedBefore = b.lastBar[id]
        val resumed = bars.size >= 2 && b.lastTarget[id] != null && watchedBefore == null
        b.lastBar[id] = last.epochSecond
        val r = Pine.run(script, bars.map { PineScripts.toPine(it) }, PineScripts.inputValues(item, script), item.auto.symbol, item.auto.interval,
            budgetMs = 5_000)
        r.error?.let { if (!quiet) note(b, id, "Script stopped: ${it.message}"); return }
        val prev = b.lastTarget[id]
        val target = targetOf(item, script, r, prev ?: 0)
        b.lastTarget[id] = target
        // 30 Oct: a would-be trade of this script the signal no longer backs closes in the shadow (its signal exit), on or off.
        shadowSignalExit(item, wantFor(item, target))
        if (prev == null) { note(b, id, "${tag}Watching: the signal now is ${describe(target)}; it ${if (quiet) "records" else "trades"} on the next change"); return }
        if (target == prev) { owedExit(b, id, item, h); return }
        val want: String? = wantFor(item, target)
        if (resumed && !changedOnLastBar(item, script, r, bars, watchedBefore, prev, target)) {
            // What it holds is sold if the signal no longer backs it; nothing new is bought until the next change.
            note(b, id, "${tag}Back after a pause: the signal is now ${describe(target)}; it ${if (quiet) "records" else "trades"} on the next change, not this one")
            if (a.mode != "alert" && h != null && h.right != want) exit(b, id, item, h, "signal changed during a pause")
            return
        }
        // (B5 item 6: a late candle given back below is read again each pass until a fresh one comes - its signal is said once.)
        val lateCandle = step < 86_400 && !com.optionslab.ira.CandleFresh.pineFresh(last.epochSecond, step, now)
        if (!(lateCandle && staleSaid[id] == last.epochSecond))
            note(b, id, "${tag}Signal: ${describe(target)} at ${"%.2f".format(java.util.Locale.ENGLISH, last.close)}")
        if (a.mode == "alert" && entries) {
            // Alerts only: tell the owner, place nothing. 16 Oct: the banner (and so the diary) as information, not an error -
            // "jarvis: breakout above ... flat" was counted among the day's errors by the order review.
            Notifier.post(app, 5000 + (id % 1000).toInt(), Notifier.RISK, "${item.name}: ${describe(target)}",
                "${a.symbol} ${a.interval} at ${"%,.2f".format(java.util.Locale.ENGLISH, last.close)} · Pine signal (alerts only, no order placed)", "pine",
                banner = com.optionslab.app.work.Alerts.Kind.INFO)
            return
        }
        if (h?.right == want) return
        if (h != null) { exit(b, id, item, h, "signal changed"); if (b.held.containsKey(id)) return }
        if (want == null) return
        // Switched off by the app's rule: the holding was managed to its exit; nothing new is bought. 30 Oct: off (or hidden), the
        // entry it would have made is recorded in the switched-off shadow instead - placed before the live-PIN check and every
        // order step, so it can never reach [enter], the paper book or Zerodha.
        if (!entries) {
            if (shadow) runCatching { shadowEnter(b, item, Right.valueOf(want), last.close, last.epochSecond, describe(target)) }
                .onFailure { e -> runCatching { Diag.record("pine", "${label(item)} (shadow): ${e.javaClass.simpleName}") } }
            return
        }
        // 20 Nov 2026 (audit, B5 item 6: "Pine accepts a candle about 17 minutes old"): an ENTRY on a candle older than its own timeframe
        // allows - the newest completed one closed more than one candle plus the feed's lag ago ([com.optionslab.ira.CandleFresh.pineFresh])
        // - is not made in Paper: the candle is given back (its signal and candle as before it), so the change is read again on the next,
        // fresher one. In Live it is logged only (CLAUDE.md: a new rule that could skip live trades starts in shadow) and the entry goes as
        // before. Everything above - the exit of what was held, the signal exits, the stop, target, lock and 15:15 - is never held by it.
        if (lateCandle) {
            val age = (now - last.epochSecond - step) / 60
            if (!OrbArms.liveNow()) {
                if (watchedBefore == null) b.lastBar.remove(id) else b.lastBar[id] = watchedBefore
                b.lastTarget[id] = prev
                if (staleSaid.put(id, last.epochSecond) != last.epochSecond)
                    note(b, id, "${tag}The newest ${item.auto.interval} candle closed $age min ago (over one candle late): no entry on it; read again on the next")
                return
            }
            if (staleSaid.put(id, last.epochSecond) != last.epochSecond)
                note(b, id, "${tag}shadow (B5 item 6): the newest ${item.auto.interval} candle closed $age min ago (over one candle late); in Paper " +
                    "no entry would be made on it. Live is unchanged: logged only")
        }
        val live = OrbArms.liveNow()
        if (live && b.liveOk[id] != true) {
            note(b, id, "Live needs your PIN once: switch auto-trade off and on again for this script. Nothing was sent.")
            return
        }
        enter(b, item, Right.valueOf(want), last.close, live)
    }

    /** 20 Nov 2026 (audit, B5 item 6): the candle each script was last told is too old to enter on (memory only; once a candle). */
    private val staleSaid = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** 22 Nov 2026 (B6 review, third look): [text] noted once per [key] (a buy's "nothing held" while its sale is looked for). Memory, bounded. */
    private fun noteOnce(b: Book, id: Long, key: String, text: String) {
        if (notedOnce.size > 200) notedOnce.clear()
        if (notedOnce.add(key)) note(b, id, text)
    }
    private val notedOnce: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** TEST ONLY: forget which late candles were told (a new process starts without them). */
    internal fun forgetStaleForTest() { staleSaid.clear(); stopNotRested.clear(); stopShort.clear(); topUpAt.clear(); topUpGapMsForTest = null
        ownCancels.clear(); ownCancelAt.clear(); ownRestDue.clear(); lostExitWhy.clear(); bookedSales.clear(); notedOnce.clear(); squareMarks.clear() }

    /** The option a signal [target] backs ("CE" / "PE"), or null: none (flat, or short with no put). */
    private fun wantFor(item: PineScripts.Item, target: Int): String? = when {
        target > 0 -> "CE"
        target < 0 -> if (item.auto.shortWith == "put") "PE" else null
        else -> null
    }

    /**
     * A signal exit that did not go through (refused, no answer, not filled in 15 s, no price) is owed: what [h] holds is
     * sold on every pass while the signal as last read ([Book.lastTarget]) no longer backs it - not only on the candle the
     * signal changed. Never in alerts-only mode; nothing is bought here.
     */
    private suspend fun owedExit(b: Book, id: Long, item: PineScripts.Item, h: Held?) {
        if (h == null || item.auto.mode == "alert") return
        val target = b.lastTarget[id] ?: return
        if (h.right != wantFor(item, target)) exit(b, id, item, h, "signal changed (tried again)")
    }

    /**
     * Labels an order for the Orders and Trades lists ([Strategies.tagOwner]). Only a label: a vault write that fails (a
     * Keystore fault, a full disk) is noted and never aborts the order path - a buy already sent is still booked and its stop
     * placed, a sale already filled is still booked.
     */
    private suspend fun ownerTag(key: String, label: String) {
        try { Strategies.tagOwner(key, label) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { runCatching { Diag.record("pine", "order label $key not saved: ${e.javaClass.simpleName}") } }
    }

    /** Strike spacing used for the ATM option. */
    fun strikeStep(u: String) = when (u) { "BANKNIFTY", "SENSEX" -> 100; "MIDCPNIFTY" -> 25; else -> 50 }

    private fun describe(t: Int) = when { t > 0 -> "BUY"; t < 0 -> "SELL"; else -> "FLAT" }

    /** What [id] made or lost on closed trades today. */
    private fun realizedToday(b: Book, id: Long): Double {
        val v = b.dayPnl[id] ?: return 0.0
        val (day, amt) = v.split('|', limit = 2).let { it[0] to it.getOrNull(1) }
        return if (day == todayIst().toString()) amt?.toDoubleOrNull() ?: 0.0 else 0.0
    }

    private fun addPnl(b: Book, id: Long, pnl: Double) { b.dayPnl[id] = "${todayIst()}|${realizedToday(b, id) + pnl}" }

    /** Today's P&L of script [id]: closed trades and, when it holds one, the open option. */
    fun todayOf(id: Long): Double? = cache?.let { realizedToday(it, id) }

    /** The held option's last price, paper or Zerodha. */
    private suspend fun optionLtp(h: Held): Double? = if (!h.live) Paper.contractOf(h.symbol)?.let { Paper.lastPrice(it) }
        else h.kite?.let { sym ->
            if (!Broker.loggedIn) null
            else (if (streamLook) Broker.tokenOf("NFO:$sym")?.let { KiteStream.freshTick(it) }?.last?.takeIf { it > 0 } else null)
                ?: Broker.stopQuotes(listOf("NFO:$sym"))["NFO:$sym"]?.last?.takeIf { it > 0 }
        }

    // ---- the stop-loss and the profit lock as a resting stop (08 Oct, research/PROFIT_LOCK_8OCT.md fixes 1 and 3) ----------

    /**
     * The held option's own exits, checked on every look - the full pass ([one]) and the 15-second check ([watchOnly]) - on
     * every Pine trade (paper and live), on top of whatever exits the script itself has: the stop-loss, the target, the
     * profit lock and the script's daily loss limit. The stop, target and lock are compulsory (Boss's 06 Oct rule,
     * PineScripts.protect), and their rungs and percentages are unchanged.
     *
     * Since 08 Oct the stop-loss RESTS (a paper SL-M in the paper book, an SL at Zerodha) and the profit lock MOVES that
     * resting stop UP ([raiseStop]: never down, never to or above the price now), as the ORB arms' since 07 Oct; the best
     * price is every stream tick, else the 1-minute candles' highs since the buy ([highOf]), not one sampled price a pass.
     * The app still sells at market itself when the price is through a level the resting stop does not cover (on paper
     * always, as the ORB arms; at Zerodha when no stop rests there, or the price ran through its limit). The lock read for
     * that is the one the best price BEFORE this look earned. True when it was sold here (the pass stops for this script).
     */
    private suspend fun guard(b: Book, id: Long, item: PineScripts.Item, h0: Held): Boolean {
        val h = h0
        val a = item.auto
        if (h.unconfirmed || !isOpen() || !(a.stopPts > 0 || a.targetPts > 0 || a.maxDayLoss > 0 || a.profitLock)) return false
        // The profit lock only ever sells sooner: the higher of the target ladder (on the target, else twice the stop) and
        // the percentage trail on the gain (2026-10-06: every script, no stop or target needed).
        val lockRef = if (a.profitLock) ProfitLock.pineReference(a.targetPts, a.stopPts) else null
        val trail = if (a.profitLock) a.trail else null
        val ltp = runCatching { optionLtp(h) }.getOrNull() ?: return false
        val cost = ProfitLock.roundTripPerUnit(h.entry, h.qty)
        val tick = if (h.live) h.kite?.let { sym -> runCatching { Broker.spec("NFO", sym).tickSize }.getOrNull() } ?: OrbRules.TICK else OrbRules.TICK
        // What rests at Zerodha now: the stop-loss, or the lock it was moved up to. A price already under the SL's limit
        // may not fill it - then the app sells at market (the SL is taken out first).
        val rest = h.stopAt?.takeIf { h.stop != null }
        val base = ProfitLock.pineBase(h.entry, a.stopPts, tick)
        val lockNow = ProfitLock.lockLevel(h.entry, lockRef, trail, cost, h.peak)?.let { ProfitLock.onTick(it, tick) }
        val runThrough = rest != null && ltp < stopLimit(rest, tick)
        val coversStop = h.live && rest != null && base != null && rest >= base - 1e-9 && !runThrough
        val coversLock = h.live && rest != null && lockNow != null && rest >= lockNow - 1e-9 && !runThrough
        val today = todayIst().toString()
        // The trade manager (10 Oct): this holding handed over (memory only; paper acts, live records only by default). In ACT
        // its target may have moved out - only ever with its lock raised to breakeven plus charges or better - and it may want
        // the holding out now. The script's own stop, target and profit lock stay as they are; nothing here waits for it.
        val mid = runCatching { attachManager(id, item, h, lockRef, trail, cost, tick) }.getOrNull()
        val mgr = mid?.let { TradeManagerHost.levels(it) }
        val mgrTarget = mgr?.first?.takeIf { a.targetPts > 0 && it > h.entry + a.targetPts }
        val mgrLock = mgr?.second
        val coversMgr = h.live && rest != null && mgrLock != null && rest >= mgrLock - 1e-9 && !runThrough
        val due = mid?.let { TradeManagerHost.exitDue(it) }
        val why = when {
            a.stopPts > 0 && ltp <= h.entry - a.stopPts && !coversStop -> "stop-loss"
            a.targetPts > 0 && ltp >= (mgrTarget ?: (h.entry + a.targetPts)) -> if (mgrTarget != null) "target (extended by the trade manager)" else "target"
            ProfitLock.lockExits(h.entry, lockRef, trail, cost, h.peak, ltp) && !coversLock -> "profit lock"
            mgrLock != null && ltp <= mgrLock && !coversMgr -> "profit lock (the trade manager's)"
            a.maxDayLoss > 0 && realizedToday(b, id) + (ltp - h.entry) * h.qty <= -a.maxDayLoss -> "daily loss limit"
            due != null -> due
            else -> null
        }
        if (why != null) {
            val locked = if (why == "profit lock") ProfitLock.lockLevel(h.entry, lockRef, trail, cost, h.peak) else null
            note(b, id, "${h.symbol} at ${"%.2f".format(java.util.Locale.ENGLISH, ltp)}: $why" +
                (locked?.let { " (locked at ${"%.2f".format(java.util.Locale.ENGLISH, it)})" } ?: ""))
            exit(b, id, item, h, why)
            if (why == "daily loss limit") { b.paused[id] = today; note(b, id, "Daily loss limit reached: no more trades today") }
            return true
        }
        // Still held: the best price from every tick, else the minute highs since the buy, raises the high-water mark (kept
        // with the holding, so it survives a restart).
        val high = runCatching { highOf(h) }.getOrNull()
        val peak = ProfitLock.nextPeak(h.peak, ltp, high)
        var cur = h
        if (peak > h.peak) {
            val was = ProfitLock.lockLevel(h.entry, lockRef, trail, cost, h.peak)
            cur = h.copy(peak = peak)
            b.held[id] = cur
            val rung = ProfitLock.lockLevel(cur.entry, lockRef, trail, cost, cur.peak)
            // Said when it first locks and then on each rise of 1% of the buy price or more (the trail moves with every new
            // best: not a line a pass).
            if (rung != null && (was == null || rung >= was + maxOf(0.01 * h.entry, 0.05))) note(b, id, "${cur.symbol}: profit lock now at ${"%.2f".format(java.util.Locale.ENGLISH, rung)} " +
                "(best ${"%.2f".format(java.util.Locale.ENGLISH, peak)})")
        }
        // The lock moves the resting stop up to what the best price has earned.
        b.held[id] = raiseStop(b, id, item, cur, ltp, lockRef, trail, cost, tick, mgrLock)
        return false
    }

    /** A holding's id for the trade manager. */
    internal fun managerId(id: Long, h: Held): String = "pine:$id:${h.symbol}:${h.at}"

    /**
     * [h] handed to the trade manager (one call; idempotent, memory only): a bought option, its stop and target on the premium
     * from the script's settings, its own profit lock ([ProfitLock.lockLevel]) for the counterfactual, out by 15:15 (or F&O's
     * close; 15:05 on its option's expiry day, the expiry square-off). Its price: the stream's tick (live) or the paper book's price in memory. Its id ([managerId]).
     */
    private fun attachManager(id: Long, item: PineScripts.Item, h: Held, lockRef: Double?, trail: ProfitLock.Trail?, cost: Double, tick: Double): String {
        val mid = managerId(id, h)
        val a = item.auto
        val day = runCatching { java.time.LocalDate.parse(h.day) }.getOrDefault(todayIst())
        val ownMin = if (a.squareOff) minOf(15 * 60 + 15, Market.foClose(day)) else Market.foClose(day)
        val off = TradeManagerHost.nseSquareOffMs(day, java.time.LocalTime.of(ownMin / 60, ownMin % 60), h.symbol)
        val trade = com.optionslab.ira.TradeManager.ManagedTrade("pine:$id", mid, label(item),
            com.optionslab.ira.AutoSide.underlyingOf(h.symbol) ?: a.symbol, if (h.right == "CE") 1 else -1, h.kite ?: h.symbol, h.entry, h.qty,
            if (a.stopPts > 0) h.entry - a.stopPts else null, if (a.targetPts > 0) h.entry + a.targetPts else null,
            com.optionslab.ira.TradeManager.Caps(off, tick = tick),
            if (h.live) com.optionslab.ira.TradeManager.Account.LIVE else com.optionslab.ira.TradeManager.Account.PAPER,
            if (h.at > 0) h.at else clock().toInstant().toEpochMilli(), cost)
        val sym = h.symbol
        val kite = h.kite
        val live = h.live
        val entry = h.entry
        val lockAt: ((Double) -> Double?)? =
            if (lockRef != null || trail != null) { { peak: Double -> ProfitLock.lockLevel(entry, lockRef, trail, cost, peak) } } else null
        TradeManagerHost.attach(TradeManagerHost.Registration(
            trade,
            premium = {
                if (!live) Paper.memPrice(sym)
                else kite?.let { k -> Broker.tokenOf("NFO:$k")?.let { tok -> KiteStream.tick(tok)?.last }?.takeIf { it > 0 } }
            },
            bars = { contractAny(sym)?.let { c -> Paper.minutes(c) }?.map { com.optionslab.ira.TradeManager.Bar(it.epochSecond * 1000L, it.open, it.high, it.low, it.close) } },
            lockAt = lockAt,
            wake = { watchOnly(fast = true) },
        ))
        return mid
    }

    /** The trade manager told how [h] really ended ([price] null: no result read). */
    private fun managerClosed(id: Long, h: Held, price: Double?, why: String) {
        runCatching { TradeManagerHost.closed(managerId(id, h), price, why) }
        // (20 Nov 2026, delta review: its lost stop and retries forgotten - a later holding of the same key never inherits them; its own
        // exits are taken out at Zerodha as before.)
        lostStopKeyOf(id, h).let { k -> Broker.forgetLost(k); stopNotRested -= k; stopShort.remove(k); ownRestDue.remove(k) }
        // (22 Nov 2026, B6 review, item 2: and a lost exit sale of it, if one is still kept.)
        if (h.live) lostExitKeyOf(id, h).let { k -> Broker.forgetLost(k); lostExitWhy.remove(k); cache?.lostSales?.remove(k) }
    }

    /** A sell stop-limit's limit price: 5% (at least 2 points) under its trigger, so it fills in a fast fall (as the ORB arms'). */
    private fun stopLimit(trigger: Double, tick: Double): Double =
        com.optionslab.engine.Kite.onTick(trigger - maxOf(2.0, trigger * 0.05), tick, com.optionslab.engine.Kite.Side.SELL).coerceAtLeast(tick)

    /** The contract a held symbol stands for (the paper book's, else the listed one): the minutes' highs are read off it. */
    private fun contractAny(symbol: String): Paper.Contract? = Paper.contractOf(symbol) ?: runCatching {
        Market.contracts().firstOrNull { Paper.symbolOf(it) == symbol }?.let { Paper.contractFor(it.underlying, it.expiry, it.strike, it.right) }
    }.getOrNull()

    /** The highest price traded since [h] was bought: every stream tick, else the 1-minute candles' highs ([Paper.highSince]). */
    private suspend fun highOf(h: Held): Double? {
        if (h.at <= 0L || Paper.testSkipFeedChecks) return null
        val c = contractAny(h.symbol) ?: return null
        val since = java.time.Instant.ofEpochMilli(h.at).atZone(com.optionslab.engine.IST).toLocalDateTime()
        return Paper.highSince(c, since)
    }

    /**
     * The profit lock MOVES the resting stop: up to the lock [h]'s best price has earned ([ProfitLock.pineRaise]: never down,
     * never to or above the price now) - the paper book's SL-M modified, or a Zerodha modify of the same SL order (never a
     * cancel and a new sell). A refused modify leaves the stop where it was, is said (throttled; never a key or a token)
     * and is asked again on the next look. Never an order to Zerodha for a paper holding.
     */
    private suspend fun raiseStop(b: Book, id: Long, item: PineScripts.Item, h: Held, ltp: Double, ref: Double?, trail: ProfitLock.Trail?,
                                  cost: Double, tick: Double, mgrLock: Double? = null): Held {
        val sid = h.stop ?: return h
        // The trade manager's lock (ACT, after an extension) moves the same resting stop: only up, never to or above the price.
        val mine = mgrLock?.takeIf { it > (h.stopAt ?: Double.NEGATIVE_INFINITY) + 1e-9 && it < ltp - 1e-9 }
        val pine = ProfitLock.pineRaise(h.entry, ref, trail, cost, h.peak, h.stopAt, ltp, tick)
        val to = listOfNotNull(pine, mine).maxOrNull() ?: return h
        val by = if (mine != null && (pine == null || mine > pine)) "the trade manager's lock" else "profit lock"
        val was = h.stopAt?.let { "%.2f".format(java.util.Locale.ENGLISH, it) } ?: "none"
        if (!h.live) {
            val r = runCatching { Paper.modify(sid, null, null, to) }
            if (r.getOrNull()?.ok == true) {
                note(b, id, "${h.symbol}: resting stop moved up to ${"%.2f".format(java.util.Locale.ENGLISH, to)} ($by)")
                return h.copy(stopAt = to)
            }
            val why = r.getOrNull()?.message ?: r.exceptionOrNull()?.javaClass?.simpleName ?: "an error"
            com.optionslab.app.work.Alerts.post("${label(item)}: the profit lock could not move the paper stop up to " +
                "%.2f (%s). The stop stays at %s; it is tried again on the next check.".format(java.util.Locale.ENGLISH, to, why, was),
                com.optionslab.app.work.Alerts.Kind.ERROR, "Pine auto-trade", throttle = true)
            return h
        }
        val sym = h.kite ?: return h
        if (!Broker.loggedIn) return h
        val so = runCatching { kiteOrders() }.getOrNull()?.firstOrNull { it.id == sid } ?: return h
        if (!so.working) return h
        val r = runCatching {
            if (so.type == "SL-M") Broker.modify(so, so.qty, "SL-M", null, to) else Broker.modify(so, so.qty, "SL", stopLimit(to, tick), to)
        }
        if (r.isSuccess) {
            note(b, id, "$sym: resting stop moved up to ${"%.2f".format(java.util.Locale.ENGLISH, to)} at Zerodha ($by)")
            return h.copy(stopAt = to)
        }
        com.optionslab.app.work.Alerts.post("${label(item)}: Zerodha did not move the stop on $sym up to the profit lock " +
            "%.2f (%s). The stop stays at %s; it is tried again on the next check.".format(java.util.Locale.ENGLISH, to,
                r.exceptionOrNull()?.message?.take(160) ?: "an error", was),
            com.optionslab.app.work.Alerts.Kind.ERROR, "Pine live", throttle = true)
        return h
    }

    /**
     * The stop-loss placed to rest at Zerodha right after a confirmed live buy: an SL SELL of what was bought at
     * [ProfitLock.pineBase] (its limit under it, [stopLimit]), tagged as the script's own so its exits take it out first.
     * Only for a live holding (the entry already passed the PIN, the guard, the kill switch and the order limits); the
     * holding unchanged when it could not be placed (the app then watches the stop itself, as before).
     */
    private suspend fun placeLiveStop(b: Book, item: PineScripts.Item, h: Held, at: Double? = null, capToHeld: Boolean = false): Held {
        // 23 Nov 2026 (B6 follow-ups, item 2): every live stop - the first after a buy, and one put back ([capToHeld]) - goes in with the
        // contract's row claimed ([SquareOffExit.claimExit], as the ORB / Liquidity stops go in), so a square-off never works its sale out from
        // a read made without it; and none while another seller holds the row or a square-off's sale went on it a moment ago
        // ([SquareOffExit.inGap]): asked again on the next check ([stopNotRested]), which rests it on fresh reads. After a square-off's sale on
        // the row around or after this buy ([SquareOffExit.lastSaleAt]: no older than the buy less the square-offs' gap) the first stop too is
        // sized on fresh reads, never above what Zerodha holds. (Round 2: never for an older sale - a fresh read can still miss the new
        // buy's fill, and the stop would be left out with a "holds none" alert; a buy with no time of its own counts as after it.)
        // This book's own exit holding the row (a stop put back after its sale did not go - the scripts' lock: only this call chain) needs
        // no claim of its own. Memory only, never a wait.
        if (!h.live || h.unconfirmed) return h
        val sym = h.kite ?: return h
        val own = SquareOffExit.exitClaimedBy("NFO", sym, "MIS") == CLAIM_OWNER
        val claim = if (own) null else SquareOffExit.claimExit("NFO", sym, "MIS", CLAIM_STOP_OWNER)
        if ((!own && claim == null) || SquareOffExit.inGap("NFO", sym, "MIS")) {
            claim?.release()
            if (stopNotRested.size > 200) stopNotRested.clear()
            stopNotRested += lostStopKey(item, h)
            runCatching { Diag.record("pine", "${label(item)}: its stop on $sym is not placed this check - " +
                (if (!own && claim == null) "${SquareOffExit.claimWords(SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))} holds the row"
                    else "a square-off sold it a moment ago") + "; asked again on the next check") }
            return h
        }
        val fresh = capToHeld || SquareOffExit.lastSaleAt("NFO", sym, "MIS")?.let { h.at <= 0L || it >= h.at - SquareOffExit.gapMs } == true
        try { return placeLiveStopClaimed(b, item, h, at, fresh) } finally { claim?.release() }
    }

    /** 23 Nov 2026 (B6 follow-ups, item 2): Pine's name on a contract's row claim while one of its stops goes in at Zerodha ([placeLiveStop]). */
    internal const val CLAIM_STOP_OWNER = "pine-stop"

    /** [placeLiveStop]'s placing, with the row's claim held. 23 Nov 2026 (B6 follow-ups, item 2): taken out unchanged. */
    private suspend fun placeLiveStopClaimed(b: Book, item: PineScripts.Item, h: Held, at: Double?, capToHeld: Boolean): Held {
        if (!h.live || h.unconfirmed) return h
        val sym = h.kite ?: return h
        // 22 Nov 2026 (B6 review): never beside an exit sale of this holding whose answer was lost and is still looked for ([lostExit]: it may
        // have filled - a stop now would rest on a flat contract). Its search's end puts the stop back when it should be.
        if (Broker.lostPending(lostExitKeyOf(item.id, h)) || foundSale(b, lostExitKeyOf(item.id, h)) != null) return h
        // (23 Nov 2026, B6 review, ninth round: nor while Boss's own exit on the row whose reply was lost may be selling it - asked again on the
        // next check; the app watches the level meanwhile.)
        if (SquareOffExit.bossExitLost("NFO", sym, "MIS")) {
            if (stopNotRested.size > 200) stopNotRested.clear()
            stopNotRested += lostStopKey(item, h)
            return h
        }
        // (22 Nov 2026, B6 review: the contract's spec not read just now - tried again on the next checks, never left out silently.)
        val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: run { stopNotRested += lostStopKey(item, h); return h }
        // [at]: a stop put back where it rested (the profit lock may have moved it up: never lowered to the base).
        val trig = at ?: ProfitLock.pineBase(h.entry, item.auto.stopPts, spec.tickSize) ?: return h
        val o0 = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, h.qty, spec.lotSize, "MIS", "SL",
            stopLimit(trig, spec.tickSize), spec.tickSize, "NFO", "irapine", triggerPrice = trig)
        val bad = com.optionslab.engine.Kite.refusals(o0, AppSettings.load().limits(), Broker.sentToday(), false, exit = true)
        if (bad.isNotEmpty()) {
            stopNotRested -= lostStopKey(item, h)       // (20 Nov 2026: refused by the limits - not tried again on every check)
            com.optionslab.app.work.Alerts.error("${label(item)}: the stop on $sym was not placed at Zerodha (${bad.joinToString("; ")}); " +
                "the app watches it instead.", "Pine live")
            return h
        }
        // 20 Nov 2026 (audit B5 deep review): a stop of this holding whose reply was lost and is not found yet is UNKNOWN: looked for again
        // here, and no second stop is sent beside it while it may rest ([Broker.lookLost], two minutes; then Boss is told).
        val lostKey = lostStopKey(item, h)
        when (val l = Broker.lookLost(lostKey)) {
            // (20 Nov 2026, delta review: never one another holding owns or that is being taken out - then rested as usual below; found for more than
            // is held now, cut to it first, else taken out and rested again on a later check; for less, the rest asked again ([stopShort]).)
            is Broker.LostLook.Found -> if (b.held.values.none { it.stop == l.id } && b.removing.none { l.id in it.ids.keys }) {
                var covers = l.qty
                if (l.qty > h.qty) {
                    val so = runCatching { Broker.orderRow(l.id) }.getOrNull()
                    // (One that ended, or that cannot be read, is adopted as it is: [stopFilled] books a fill. One not cut is taken out, and
                    // only once Zerodha shows it out does a stop rest again; not confirmed out, it stays this holding's - never ownerless.)
                    val cut = so != null && so.working &&
                        runCatching { Broker.modify(so, so.filled + h.qty, so.type, so.price.takeIf { it > 0 }, so.trigger.takeIf { it > 0 }) }.isSuccess
                    if (cut) covers = h.qty
                    if (so != null && so.working && !cut) {
                        // (22 Nov 2026, B6 review: marked the app's own before it goes.)
                        cancelOwn(b, l.id, so.variety)
                        val st = runCatching { Broker.orderState(l.id)?.status }.getOrNull()
                        if (st == "CANCELLED" || st == "REJECTED") {
                            stopNotRested += lostKey
                            note(b, item.id, "$sym: its lost stop ${l.id} was found for ${l.qty}, more than the ${h.qty} held, and could not be cut: taken out; it rests again")
                            return h
                        }
                    }
                }
                ownerTag("kite:${l.id}", "${label(item)} · stop")
                // (For more than held - not cut, not confirmed out - it is kept and cut on the next checks ([topUpStop]).)
                if (covers != h.qty) stopShort[lostKey] = covers else stopShort.remove(lostKey)
                stopNotRested -= lostKey
                return h.copy(stop = l.id, stopAt = l.trigger ?: trig)
            }
            Broker.LostLook.Searching -> return h
            else -> Unit
        }
        // 20 Nov 2026 (audit B5 deep review, as ArmLive and the ORB arms do), from the order book and the positions (the kept reads when they
        // may stand, else fresh - never a wait on the stream's look beyond them):
        //  - a working stop of the scripts' on the contract that no holding of this book claims, that is not being taken out for a closed
        //    holding ([Book.removing]), and whose quantity fits this holding (the nearest to its level first) is this holding's own (its
        //    answer lost, given up on too early, or placed just before a restart): taken as its stop, never a second one beside it;
        //  - otherwise the stop rests only for what Zerodha holds less every other working sale on the contract (never more resting than
        //    held): none left, none is placed and the app watches the level; a part, the stop rests for that part.
        // A book that cannot be read: placed as before.
        var stopQty = h.qty
        // 22 Nov 2026 (B6 review, third look): [capToHeld] - a stop put back (not the first after a buy, whose fill the positions may still
        // trail) is sized on FRESH reads and never rests for more than Zerodha holds less every other working sale on the contract; either
        // read failing, it is not placed now and asked again on the next check ([stopNotRested]).
        // (On a stream look - the tick's path: no REST read - it waits for the next regular check, as [topUpStop] does.)
        if (capToHeld && streamLook) { if (stopNotRested.size > 200) stopNotRested.clear(); stopNotRested += lostKey; return h }
        val book = runCatching { if (capToHeld) Broker.orders() else Broker.ordersRecon() }.getOrNull()
        if (book == null && capToHeld) { if (stopNotRested.size > 200) stopNotRested.clear(); stopNotRested += lostKey; return h }
        if (book != null) {
            val removingIds = b.removing.flatMap { it.ids.keys }.toSet()
            val claimed = b.held.values.mapNotNull { it.stop }.toSet() + removingIds
            // (An earlier holding's exits still being taken out are neither this holding's nor its cover: they are on their way out.)
            val sells = book.filter { it.working && it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side == "SELL" && it.id !in removingIds }
            // (20 Nov 2026, delta review: never one another holding's lost reply may be - it takes it when found; one taken is claimed, and one for
            // less than is held leaves the rest asked again ([stopShort]).)
            // (22 Nov 2026, B6 review, item 9: never one the app is taking out itself - [ownCancels].)
            sells.filter { it.tag == "irapine" && (it.type == "SL" || it.type == "SL-M") && it.id !in claimed && !ownCancel(it.id) && !Broker.lostMayBe(it, lostKey) &&
                com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) in 1..h.qty }
                .minByOrNull { kotlin.math.abs(it.trigger - trig) }?.let { orphan ->
                    Broker.forgetLost(lostKey); stopNotRested -= lostKey; Broker.claimStop(orphan.id)
                    val covers = com.optionslab.engine.risk.ExitQty.remaining(orphan.qty, orphan.filled, orphan.pending)
                    if (covers < h.qty) stopShort[lostKey] = covers else stopShort.remove(lostKey)
                    runCatching { Diag.record("pine", "${label(item)}: a stop of the scripts' resting on $sym with no holding claiming it (${orphan.id}): taken as its stop") }
                    ownerTag("kite:${orphan.id}", "${label(item)} · stop")
                    return h.copy(stop = orphan.id, stopAt = orphan.trigger.takeIf { it > 0 } ?: trig)
                }
            val others = sells.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) }
            // (23 Nov 2026, B6 review, ninth round: less what Boss's own exit whose reply was lost may be selling.)
            val heldZ = runCatching { if (capToHeld) Broker.positionBook().net else Broker.positionsRecon().net }.getOrNull()
                ?.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }?.sumOf { it.qty }
                ?.let { SquareOffExit.lessBossLost(it, sym, "MIS", "NFO") }
            if (heldZ == null && capToHeld) { if (stopNotRested.size > 200) stopNotRested.clear(); stopNotRested += lostKey; return h }
            // (What Zerodha holds beyond this holding - other books' lots - is what the other sales may cover first; positions that
            // trail a fill, or cannot be read, count none, so the stop only ever rests for less, never more than held.)
            val extra = ((heldZ ?: h.qty) - h.qty).coerceAtLeast(0)
            val lot = spec.lotSize.coerceAtLeast(1)
            stopQty = ((h.qty - (others - extra).coerceAtLeast(0)).coerceAtLeast(0) / lot) * lot
            if (capToHeld && heldZ != null) stopQty = minOf(stopQty, ((heldZ - others).coerceAtLeast(0) / lot) * lot)
            if (stopQty <= 0) {
                // (Review: tried again on the next checks - the positions may trail the fill, or the other sale end - never given up;
                // said once until it rests.)
                if (stopNotRested.size > 200) stopNotRested.clear()
                if (stopNotRested.add(lostKey)) com.optionslab.app.work.Alerts.error("${label(item)}: the stop on $sym was not placed at Zerodha " +
                    "because other working sell orders already cover what Zerodha holds (${sells.joinToString { "${it.type} ${it.id}" }.ifEmpty { "none: it holds none" }}); the app " +
                    "watches the level and tries again on each check.", "Pine live")
                return h
            }
        }
        val o = if (stopQty < h.qty) o0.copy(quantity = stopQty) else o0
        val oid = try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
            // (22 Nov 2026, B6 review: Kite's unsure answer - [Broker.KiteUnsure] - was looked for in the door already: straight to the
            // background search, never a second ~4 s under the scripts' lock.)
            if (Broker.definite(e)) null else (if (e is Broker.KiteUnsure) null else runCatching { Broker.findRecentRetrying(o, knownKite()) }.getOrNull())
                ?: null.also { Broker.noteLost(lostKey, o, knownKite(), "${label(item)}: its stop", liveOnly = true) }
        }
        if (oid == null) {
            stopNotRested -= lostKey                    // (20 Nov 2026: refused or lost - never sent again on every check by the retry)
            com.optionslab.app.work.Alerts.error("${label(item)}: the stop at ${"%.2f".format(java.util.Locale.ENGLISH, trig)} on $sym could not be " +
                "placed at Zerodha; the app watches it instead.", "Pine live")
            return h
        }
        stopNotRested -= lostKey
        // (20 Nov 2026, delta review: rested for only a part - the rest grown on later checks once room appears ([topUpStop]).)
        if (stopQty < h.qty) stopShort[lostKey] = stopQty else stopShort.remove(lostKey)
        ownerTag("kite:$oid", "${label(item)} · stop")
        return h.copy(stop = oid, stopAt = trig)
    }

    /**
     * 20 Nov 2026 (delta review): [h]'s stop resting for only a part of it ([stopShort]) grown, by a modify of that same order, to what
     * Zerodha now leaves room for - what it holds (a fresh read; unreadable: nothing changed) less every other working sale on the
     * contract - never past the holding. Covering it all, it is no longer asked; one that ended is [stopFilled]'s (a part filled leaves
     * the rest with no stop, which is rested again: [bookStop]).
     */
    private suspend fun topUpStop(b: Book, item: PineScripts.Item, h: Held): Held {
        val key = lostStopKey(item, h)
        val sid = h.stop ?: return h
        val sym = h.kite ?: return h
        // (Never on the stream's fast look - no REST read on the tick path; the regular check grows it.)
        if (streamLook) return h
        // (At most every [TOP_UP_GAP_MS].)
        val now = System.currentTimeMillis()
        if (now - (topUpAt[key] ?: 0L) < (topUpGapMsForTest ?: TOP_UP_GAP_MS)) return h
        if (topUpAt.size > 200) topUpAt.clear()
        topUpAt[key] = now
        // (Fresh reads, never the kept ones: a stop is only grown on what Zerodha shows now.)
        val book = runCatching { Broker.orders() }.getOrNull() ?: return h
        val so = book.firstOrNull { it.id == sid } ?: return h
        if (!so.working) { stopShort.remove(key); return h }
        val covered = com.optionslab.engine.risk.ExitQty.remaining(so.qty, so.filled, so.pending)
        // (A found stop for more than is held, not cut when found: cut to the holding now.)
        if (covered > h.qty) {
            if (runCatching { Broker.modify(so, so.filled + h.qty, so.type, so.price.takeIf { it > 0 }, so.trigger.takeIf { it > 0 }) }.isSuccess) {
                note(b, item.id, "$sym: resting stop cut to the ${h.qty} held at Zerodha")
                stopShort.remove(key)
            }
            return h
        }
        if (covered == h.qty) { stopShort.remove(key); return h }
        // (23 Nov 2026, B6 review, ninth round: never grown beside Boss's own exit whose reply was lost - asked again on the next check.)
        if (SquareOffExit.bossExitLost("NFO", sym, "MIS")) return h
        val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: return h
        val removingIds = b.removing.flatMap { it.ids.keys }.toSet()
        val others = book.filter { it.working && it.id != sid && it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.side == "SELL" &&
            it.id !in removingIds }.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) }
        val heldZ = runCatching { Broker.positionBook().net }.getOrNull()
            ?.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }?.sumOf { it.qty }
            ?.let { SquareOffExit.lessBossLost(it, sym, "MIS", "NFO") } ?: return h
        val lot = spec.lotSize.coerceAtLeast(1)
        val room = (minOf(h.qty, heldZ - others).coerceAtLeast(0) / lot) * lot
        if (room <= covered) return h
        val r = runCatching { Broker.modify(so, so.filled + room, so.type, so.price.takeIf { it > 0 }, so.trigger.takeIf { it > 0 }) }
        if (r.isSuccess) {
            note(b, item.id, "$sym: resting stop grown to $room of ${h.qty} at Zerodha")
            if (room >= h.qty) stopShort.remove(key)
        }
        return h
    }

    /** 20 Nov 2026 (audit B5 deep review): holdings ([lostStopKey]) whose stop found no room to rest: tried again on each check. Memory. */
    private val stopNotRested: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 20 Nov 2026 (delta review): holdings ([lostStopKey]) whose stop rests for only part of them: what it covers; grown on each check. Memory. */
    private val stopShort = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val topUpAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val TOP_UP_GAP_MS = 5_000L
    /** TEST SEAM: [TOP_UP_GAP_MS] shortened. */
    @Volatile internal var topUpGapMsForTest: Long? = null

    /** 20 Nov 2026 (audit B5 deep review): the key [h]'s lost stop is looked for under (the script, the contract and the fill: what never changes for the holding). */
    private fun lostStopKey(item: PineScripts.Item, h: Held) = lostStopKeyOf(item.id, h)
    private fun lostStopKeyOf(id: Long, h: Held) = "pinestop:$id:${h.kite}:${h.entry}"

    /** The paper stop-loss placed to rest in the paper book right after a paper buy (an SL-M SELL); null when none could be. */
    private suspend fun placePaperStop(item: PineScripts.Item, c: Paper.Contract, lots: Int, entry: Double): Pair<String, Double>? {
        val trig = ProfitLock.pineBase(entry, item.auto.stopPts) ?: return null
        val oid = placeStopAt(c, lots, trig) ?: return null
        ownerTag("paper:$oid", "${label(item)} · stop")
        return oid to trig
    }

    /** A paper SL-M SELL of [lots] at [trigger] in the paper book; its order id, or null when the book refused it. */
    private suspend fun placeStopAt(c: Paper.Contract, lots: Int, trigger: Double): String? =
        runCatching { Paper.place(c, "SELL", lots, "SL-M", "MIS", null, trigger) }.getOrNull()?.takeIf { it.ok }?.orderId

    /**
     * Whether [h]'s resting stop has filled - in the paper book or at Zerodha: then that is the exit (booked with its own
     * price, said, and the holding dropped) and true is returned. A stop found cancelled or rejected is forgotten (the app's
     * own checks watch the levels again, as before); nothing is placed here.
     */
    private suspend fun stopFilled(b: Book, id: Long, item: PineScripts.Item, h: Held): Boolean {
        val sid = h.stop ?: return false
        if (!h.live) {
            val o = Paper.state.orders.firstOrNull { it.orderId == sid } ?: run { b.held[id] = h.copy(stop = null); return false }
            when (o.status) {
                "complete" -> {
                    bookStop(b, id, item, h, o.averagePrice?.toDouble() ?: h.stopAt ?: h.entry, o.quantity, sid)
                    return true
                }
                "cancelled", "rejected" -> {
                    note(b, id, "${h.symbol}: its resting stop is no longer in the paper book (${o.status}); the app watches the stop itself")
                    b.held[id] = h.copy(stop = null)
                }
            }
            return false
        }
        if (!Broker.loggedIn) return false
        val rows = runCatching { kiteOrders() }.getOrNull()
        // (22 Nov 2026, B6 review, third look: on a fresh read, an own cancel that never took ends here - before its stop's end is read.)
        if (rows != null && !streamLook) ownCancelsSeenWorking(rows)
        val so = rows?.firstOrNull { it.id == sid } ?: return false
        when (so.status) {
            "COMPLETE" -> {
                bookStop(b, id, item, h, so.avg.takeIf { it > 0 } ?: h.stopAt ?: h.entry, so.filled.takeIf { it > 0 } ?: h.qty, sid)
                return true
            }
            "CANCELLED", "REJECTED" -> {
                // 22 Nov 2026 (B6 review, item 9): one the app took out itself for an exit ([ownCancels]) is said as such, never as gone from
                // Zerodha; either way the app watches the level itself, as before.
                if (so.status == "CANCELLED" && ownCancel(sid)) {
                    // 22 Nov 2026 (B6 review): the app took it out for an exit and is the one to put it back (WHO CANCELLED A STOP): with no
                    // sale of this holding going or filled since that cancel, and Zerodha still holding the contract, it is asked again on the
                    // next checks ([stopNotRested]; [placeLiveStop] rests it only for what other working sales leave). With a sale seen, that
                    // sale's own booking decides; shown flat, the flat check does. Decided only on fresh reads: on a stream look (the kept
                    // book) or with the positions unread, it is left to the next regular check as it is.
                    // (Either way the holding has no stop from here - the app watches its level - and only the re-rest waits for fresh reads.)
                    // (22 Nov 2026, B6 review, third look: only while that cancel is fresh - [ownCancelFresh]; an older one is never put back.)
                    val key = lostStopKey(item, h)
                    val again = if (!ownCancelFresh(sid)) false else if (streamLook) null else ownRestAgain(h, rows, sid)
                    if (again == null) { if (ownRestDue.size > 200) ownRestDue.clear(); ownRestDue[key] = sid }
                    else if (again) stopNotRested += key
                    note(b, id, "${h.kite}: its resting stop was taken out by the app for an exit" +
                        if (again == true) " that did not go; it rests again on the next check" else "; the app watches the stop itself")
                    // (Still held here: that exit did not take it all - Boss told, in the app's own words.)
                    com.optionslab.app.work.Alerts.error("${label(item)}: the resting stop on ${h.kite} was taken out by the app for an exit that " +
                        "has not sold it all; " + (if (again == true) "it rests again at Zerodha on the next check." else "the app watches the stop itself."), "Pine live")
                } else com.optionslab.app.work.Alerts.error("${label(item)}: the resting stop on ${h.kite} is no longer at Zerodha " +
                    "(${so.status.lowercase()}); the app watches the stop itself.", "Pine live")
                b.held[id] = h.copy(stop = null)
            }
        }
        return false
    }

    /** The resting stop's fill as the exit: "profit lock" once the stop had been moved to the price paid or higher. */
    private fun bookStop(b: Book, id: Long, item: PineScripts.Item, h: Held, px: Double, qty: Int, orderId: String) {
        bookedSales += orderId                                     // (22 Nov 2026: booked - never taken later for another sale: [saleOf])
        val why = if ((h.stopAt ?: Double.NEGATIVE_INFINITY) >= h.entry - 1e-9) "profit lock" else "stop-loss"
        val sold = qty.coerceIn(0, h.qty)
        addPnl(b, id, (px - h.entry) * sold)
        if (h.live) h.kite?.let { bookClose(b, it, sold, outside = false) }
        if (sold >= h.qty) { b.held.remove(id); managerClosed(id, h, px, why) } else {
            b.held[id] = h.copy(qty = h.qty - sold, stop = null)
            // (20 Nov 2026, delta review: a part sold by the stop - the lots left get a stop again on the next checks.)
            if (h.live) stopNotRested += lostStopKeyOf(id, h)
        }
        val sym = if (h.live) h.kite ?: h.symbol else h.symbol
        runCatching { Notifier.orderFilled(app, "SELL", sold, sym, px, if (h.live) "Live" else "Paper", label(item), orderId) }
        note(b, id, "Sold $sold $sym at ${"%.2f".format(java.util.Locale.ENGLISH, px)} ($why, the resting stop" +
            (if (h.live) ", LIVE" else "") + ") · P&L ${"%+.0f".format(java.util.Locale.ENGLISH, (px - h.entry) * sold)}")
    }

    /**
     * A paper holding closed outside the app: its resting stop is taken out too (it must not sell what is no longer held).
     * 19 Nov 2026 (audit): a live one's goes through [takeOutExits] (cancelled and confirmed at Zerodha, tried again until it is).
     */
    private suspend fun dropStop(h: Held) {
        val sid = h.stop ?: return
        if (!h.live) runCatching { Paper.cancel(sid, "position_closed") }
    }

    // ---- the thin-option check (08 Oct, research/PROFIT_LOCK_8OCT.md fix 2) -------------------------------------------------

    /**
     * Why [c] is not bought because it barely trades ([com.optionslab.engine.risk.ThinOption]: too few trades in the last
     * minutes, or a wide gap between buyers and sellers when a quote has them), or null when it trades enough. Read from
     * the paper feed's 1-minute candles and the price stream's best bid and ask; paper and live alike.
     */
    private suspend fun thinRefusal(c: Paper.Contract): String? {
        if (Paper.testSkipFeedChecks) return null
        val minutes = runCatching { Paper.minutes(c) }.getOrNull()
        val q = runCatching { Paper.quote(c) }.getOrNull()
        return com.optionslab.engine.risk.ThinOption.refusal(minutes?.map { it.epochSecond to it.volume }, epochSecondNow(), c.lotSize,
            q?.bid, q?.ask)
    }

    /** +1 long, -1 short, 0 flat: the strategy's own position, or the last buy/sell signal. */
    /**
     * Whether the change from [prev] to [target] was made by the newest candle itself, not by one the pause hid:
     * the candles after the last one watched ([watched], epoch seconds; null when none) are replayed up to the
     * one before the newest, and the change is the newest candle's own when the signal was still [prev] there.
     * A feed that stalled for a while and came back does not lose a change made just now.
     */
    private fun changedOnLastBar(item: PineScripts.Item, s: Pine.Script, r: Pine.Run, bars: List<com.optionslab.engine.Upstox.Bar>,
                                 watched: Long?, prev: Int, target: Int): Boolean {
        val n = r.position.size - 1
        if (n < 1 || n != bars.size - 1) return false
        // A strategy's position after the newest candle holds the orders of the candles before it.
        if (item.auto.buy == "strategy" && s.kind == Pine.Kind.STRATEGY) return Math.signum(r.position[n]).toInt() != target
        val bi = s.signals.indexOf(item.auto.buy); val si = s.signals.indexOf(item.auto.sell)
        val from = if (watched == null) 0 else bars.indexOfLast { it.epochSecond <= watched } + 1
        var t = prev
        for (i in from until n) t = when {
            bi >= 0 && r.signals[bi][i] -> 1
            si >= 0 && r.signals[si][i] -> -1
            else -> t
        }
        return t == prev
    }

    private fun targetOf(item: PineScripts.Item, s: Pine.Script, r: Pine.Run, prev: Int): Int {
        val n = r.position.size - 1
        if (n < 0) return prev
        if (item.auto.buy == "strategy" && s.kind == Pine.Kind.STRATEGY) return Math.signum(r.nextPosition).toInt()
        val bi = s.signals.indexOf(item.auto.buy); val si = s.signals.indexOf(item.auto.sell)
        return when {
            bi >= 0 && r.signals[bi][n] -> 1
            si >= 0 && r.signals[si][n] -> -1
            else -> prev
        }
    }

    // ---- orders ---------------------------------------------------------------------------

    private suspend fun enter(b: Book, item: PineScripts.Item, right: Right, spot: Double, live: Boolean) {
        // [claimed]: the exposure check let this entry through and so claimed the index ("an entry being placed"). When
        // nothing was booked after all (refused, no price, not filled), the claim is dropped at once: no other automatic
        // trader is held back for 30 s by an entry that never happened.
        val claimed = arrayOf<String?>(null)
        try { enterNow(b, item, right, spot, live, claimed) }
        finally {
            claimed[0]?.let { u -> if (b.held[item.id] == null) runCatching { AutoExposure.release(AutoExposure.Source.PINE.name, u) } }
        }
    }

    private suspend fun enterNow(b: Book, item: PineScripts.Item, right: Right, spot: Double, live: Boolean, claimed: Array<String?>) {
        val id = item.id
        val u = item.auto.symbol
        if (u == "SENSEX") { note(b, id, "SENSEX options trade on BSE, which the app does not place orders on: use Alerts only"); return }
        val strike = OrbRules.atmStrike(spot, strikeStep(u))
        val today = todayIst()
        val listed = Market.contracts().filter { it.underlying == u }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryAfter(today, listed) ?: run { note(b, id, "No $u expiry after today is listed: nothing bought"); return }
        val c = Paper.contractFor(u, expiry, strike.toDouble(), right) ?: run { note(b, id, "$u $strike $right is not listed: nothing bought"); return }
        // One index, one side, for every automatic trader (Boss's 06 Oct rule): never against another automatic position on
        // this index, and never a second one the same way - two identical scripts never hold together. Paper and live alike.
        // The account day lock comes first there (08 Oct): the account this buy goes to.
        AutoExposure.check(AutoExposure.Source.PINE, c.underlying, com.optionslab.ira.AutoSide.direction(right.name, true), exposureOf(b.held),
            account = live)
            ?.let { note(b, id, "Not bought: $it"); return }
        claimed[0] = c.underlying
        // An option that barely trades is not bought (08 Oct, research/PROFIT_LOCK_8OCT.md fix 2): its fills and its stop's
        // fills would be fiction (FINNIFTY's monthly options, a few lots a minute and minutes with no trade at all).
        thinRefusal(c)?.let {
            note(b, id, "Not bought: $it")
            runCatching { Diag.record("pine", "${label(item)}: ${c.symbol} not bought: $it") }
            return
        }
        // The order flow at this decision (9 Oct): logged beside the signal; under CONFIRM an entry it does not agree with is
        // skipped (paper and live alike: it only ever skips, never places or reverses anything).
        val flow = FlowGate.gate("pine", u, if (right == Right.CE) 1 else -1, live,
            Market.now().toLocalDateTime().withSecond(0).withNano(0).toString(), "$id:${right.name}", contract = c)
        if (flow.skip) { note(b, id, flow.pausedWhy?.let { "Not bought: $it" } ?: "Not bought: skipped by the order flow (CONFIRM; the flow did not agree)"); return }
        runCatching { LiveRecorder.signal(label(item), c.symbol, c.underlying, right.name, null, paper = !live) }
        val lots = item.auto.lots.coerceIn(1, 50)
        if (!live) {
            val ltp = Paper.lastPrice(c) ?: run { note(b, id, "No price for ${c.symbol}: nothing bought"); return }
            val snap = runCatching { Paper.snapshot() }.getOrNull()
            val refusals = Guard.check(Guard.paperOrder(c, "BUY", lots, ltp * 1.0005), snap?.let { Guard.paperAccount(it) }, paper = true)
            if (refusals.isNotEmpty()) { note(b, id, "Guard refused: ${refusals.joinToString(" ")}"); return }
            val buy = Paper.place(c, "BUY", lots, "MARKET", "MIS", null, null, auto = label(item), mirror = ExposureCapHost.NO_LIVE)
            val fill = filledOrCancelled(buy) ?: run { note(b, id, "Paper buy not filled: ${buy.message}"); return }
            buy.orderId?.let { ownerTag("paper:$it", "${label(item)} · entry") }
            FlowGate.taken(flow, buy.orderId)
            Notifier.orderFilled(app, "BUY", fill.first, c.symbol, fill.second, "Paper", label(item), buy.orderId)
            // The stop-loss rests in the paper book from now on (fix 1): the book sells at it between two looks.
            val stop = placePaperStop(item, c, (fill.first / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1), fill.second)
            b.held[id] = Held(c.symbol, right.name, fill.first, c.lotSize, fill.second, today.toString(), false, null,
                stop = stop?.first, stopAt = stop?.second, at = System.currentTimeMillis())
            // 22 Oct: the time machine's reality check (shadow only; background): the script's premium stop, out by 15:15.
            runCatching { TimeMachineHost.afterEntry(TimeMachineHost.Entry("pine", label(item), "pine:$id:${buy.orderId}", c.underlying, if (right == Right.CE) 1 else -1,
                Market.now().toLocalDateTime(), stop?.second?.let { fill.second - it }?.takeIf { it > 0 }, null, premium = true, exitAt = java.time.LocalTime.of(15, 15))) }
            note(b, id, "Bought ${fill.first} ${c.symbol} at ${"%.2f".format(java.util.Locale.ENGLISH, fill.second)} (paper)" +
                (stop?.let { " · stop resting at ${"%.2f".format(java.util.Locale.ENGLISH, it.second)}" } ?: ""))
            return
        }
        val s = AppSettings.load()
        if (s.guardKill) { note(b, id, "Kill switch is on: nothing sent"); return }
        // A phone that failed the security check never sends a real order on its own.
        val findings = runCatching { com.optionslab.app.security.Integrity.reportWithin(app, 60_000) }.getOrDefault(emptyList())
        if (com.optionslab.app.security.Integrity.compromised(findings)) { note(b, id, "This phone failed the security check: no live order sent"); return }
        if (!Broker.loggedIn) { note(b, id, "Not logged in to Zerodha today: nothing sent"); return }
        // 22 Nov 2026 (B6 review, item 5): the list in memory only (the scripts' lock is held - the holdings' 15-second checks wait on it:
        // never a download here); none in memory yet, nothing is sent and the list is loaded in the background for the next change.
        val insList = Broker.instrumentsInMemory()
            ?: run { note(b, id, "Zerodha's instrument list is not loaded yet (loading it in the background): nothing sent"); return }
        val ins = insList.firstOrNull {
            it.name == c.underlying && it.expiry == c.expiry && it.right == c.right && kotlin.math.abs(it.strike - c.strike) < 1e-6
        } ?: run { note(b, id, "${c.symbol} is not listed on Zerodha: nothing sent"); return }
        val sym = ins.tradingSymbol
        val key = "NFO:$sym"
        // 3 Nov (Boss): only the indices ticked in Bot settings → Indices get live orders. (19 Nov 2026, third review: asked before the
        // retry below, so an entry refused here pays for no Zerodha calls; it reads only what it read before.)
        if (!com.optionslab.engine.orb.LiveMirror.marketOn(c.underlying, AppSettings.load().liveMarkets)) {
            note(b, id, "${c.underlying} is not one of your live indices (Bot settings → Indices): nothing sent"); return
        }
        // 19 Nov 2026 (third review): an earlier holding's stop or own exits on this contract still being taken out ([Book.removing]) -
        // every one is tried again first (not only those not yet looked for), before the quote and the checks this buy goes on.
        b.removing.filter { it.kite == sym }.takeIf { it.isNotEmpty() }?.let { removeExits(b, it) }
        val quote = runCatching { Broker.quotes(listOf(key))[key]?.last }.getOrNull()?.takeIf { it > 0 }
            ?: run { note(b, id, "No Zerodha quote for $sym: nothing sent"); return }
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.BUY, lots * ins.lotSize, ins.lotSize, "MIS", "MARKET", null,
            ins.tickSize, "NFO", "irapine")
        val acct = Broker.accountNow()      // positions, funds and orders read in parallel
        val refusals = Guard.check(Guard.liveOrder(o).copy(price = quote), acct)
        if (refusals.isNotEmpty()) { note(b, id, "Guard refused: ${refusals.joinToString(" ")}"); return }
        val why = com.optionslab.engine.Kite.refusals(o, s.limits(), Broker.sentToday(), false, refPrice = quote)
        if (why.isNotEmpty()) { note(b, id, "Refused: ${why.joinToString("; ")}"); return }
        // 19 Nov 2026 (second review): an earlier holding's stop on this contract is still being taken out at Zerodha ([Book.removing]):
        // with this buy both could rest against the new lots. Never skipped here (a new skip of a live entry starts in shadow, CLAUDE.md):
        // logged as what a wait would have done, and Boss told once a day; the removal still cancels only the earlier holding's orders.
        // (Each was tried again before the quote, above; one not yet looked for is not looked for while this buy is held.)
        b.removing.filter { it.kite == sym }.takeIf { it.isNotEmpty() }?.let { rs ->
            val what = listOfNotNull(rs.flatMap { it.ids.keys }.takeIf { it.isNotEmpty() }?.let { "stop (order ${it.joinToString(", ")})" },
                if (rs.any { !it.scanned }) "own exits (not yet looked for in Zerodha's order book)" else null).joinToString(" and ")
            note(b, id, "Shadow (logged only): would have waited - the earlier holding's $what on $sym is still being taken out at " +
                "Zerodha; buying as before")
            if (reentrySaid.size > 200) reentrySaid.clear()
            // (Told once for each earlier holding: keyed on when its removal began, which never changes.)
            val untold = rs.filter { reentrySaid.add("${it.script}|${it.kite}|${it.at}") }
            if (untold.isNotEmpty()) {
                val news = listOfNotNull(untold.flatMap { it.ids.keys }.takeIf { it.isNotEmpty() }?.let { "stop (order ${it.joinToString(", ")})" },
                    if (untold.any { !it.scanned }) "own exits (not yet looked for in Zerodha's order book)" else null).joinToString(" and ")
                com.optionslab.app.work.Alerts.error("${label(item)}: buying $sym again while the earlier holding's $news is not yet " +
                    "cancelled at Zerodha. It may sell the new lots: check the sells of $sym in Kite.", "Pine live")
            }
        }
        val known = knownKite()
        // 22 Oct: the checks above cleared this order: stamped with its fingerprint, recomputed at the door ([OrderVerdicts]).
        val verdict = OrderVerdicts.cleared(OrderVerdicts.liveFields(o, Market.today(), "pine"), "Pine live checks")
        // (22 Nov 2026, B6 review, item 3: when it was sent, by the clock the settle reads ([Held.since]) - the key its lost reply is kept under.)
        val sentMs = Market.now().toInstant().toEpochMilli()
        val orderId: String? = try { Broker.placeOrder(o, arm = "pine", verdict = verdict) } catch (e: Exception) {
            if (Broker.definite(e)) { note(b, id, "Zerodha refused the buy: ${e.message}"); return }
            // The answer was lost, not necessarily the order: look (the book can trail the POST) before calling it unsent.
            // 22 Nov 2026 (B6 review, item 3: "a lost Pine entry not found in about 4 s is treated as nothing bought"): not there yet is no
            // proof - the book can trail the POST, and Kite's unsure DataException / GeneralException ([Broker.KiteUnsure]) may have placed
            // it. Held as unconfirmed (below, as when the book is unreadable) and looked for in the background ([Broker.noteLost]); the
            // settle takes it once found ([settle]: its fill booked, its stop rested), or lets it go only once the book, read past
            // [Broker.LOST_SEARCH_MS], still does not show it.
            // (Kite's unsure answer - [Broker.KiteUnsure] - was looked for in the door already: never a second ~4 s under the scripts' lock.)
            (if (e is Broker.KiteUnsure) null else try { Broker.findRecentRetrying(o, known) } catch (_: Exception) { null })
                ?: null.also { Broker.noteLost(lostBuyKeyOf(id, sym, sentMs), o, known, "${label(item)}: its buy") }
        }
        orderId?.let { ownerTag("kite:$it", "${label(item)} · entry") }
        FlowGate.outcome(flow, "entered_live")
        var f = orderId?.let { runCatching { Broker.awaitOrder(it, 15_000) }.getOrNull() }
        if (orderId != null && f?.status !in DONE) {
            // Not finished in 15 s: the unfilled rest is cancelled so it can never fill later as an untracked entry;
            // only what filled is booked.
            runCatching { Broker.cancel(orderId) }
            f = runCatching { Broker.orderState(orderId) }.getOrNull()
        }
        if (f == null || f.status !in DONE) {
            // No answer: it may have filled, or still be working. Tracked as held (at most the full quantity) and
            // settled from the order book on the next passes, rather than left unwatched.
            // 25 Oct: [since] is compared with Zerodha's order time ([settle]: [Broker.latestTagged]), so it reads [Market.now] (the
            // system clock on the phone; in a test the market clock the fake Zerodha stamps its orders from), never another clock.
            b.held[id] = Held(c.symbol, right.name, o.quantity, ins.lotSize, quote, today.toString(), true, sym, orderId, sentMs)
            runCatching { save(b) }
            com.optionslab.app.work.Alerts.error("${label(item)}: Zerodha did not confirm the buy of $sym. It is treated as held until the order book shows what filled.", "Pine auto-trade")
            note(b, id, "Zerodha did not confirm the buy of $sym: tracked as held until checked")
            return
        }
        if (f.filled <= 0) { note(b, id, "Zerodha ${f.status.lowercase()}: no position"); return }
        val px = f.avgPrice.takeIf { it > 0 } ?: quote
        Notifier.orderFilled(app, "BUY", f.filled, sym, px, "Live", label(item), f.orderId)
        b.held[id] = Held(c.symbol, right.name, f.filled, ins.lotSize, px, today.toString(), true, sym, at = System.currentTimeMillis())
        runCatching { save(b) }   // a live position is written down at once, not at the end of the pass
        note(b, id, "Bought ${f.filled} $sym at ${"%.2f".format(java.util.Locale.ENGLISH, px)} (LIVE)")
        // Its stop-loss rests at Zerodha from now on (fix 1): it sells there with the app closed too.
        b.held[id]?.let { h -> b.held[id] = placeLiveStop(b, item, h) }
        runCatching { save(b) }
    }

    /** Terminal order states at Zerodha. */
    private val DONE = setOf("COMPLETE", "REJECTED", "CANCELLED")

    /** Kite order ids this phone already tracks (every bot's): never adopted for a lost reply. */
    private suspend fun knownKite(): List<String> =
        runCatching { Strategies.owners().keys.filter { it.startsWith("kite:") }.map { it.removePrefix("kite:") } }.getOrDefault(emptyList())

    /**
     * A live buy Zerodha had not confirmed: whatever of it still works is cancelled, then only what filled is
     * kept. Null when nothing was bought; [h] (its order id filled in) while Zerodha cannot tell yet.
     */
    private suspend fun settle(b: Book, id: Long, h: Held): Held? {
        val sym = h.kite ?: return null
        if (!Broker.loggedIn) return h
        if (h.day != todayIst().toString()) {
            // A day old: the MIS order and position are long settled; what is held now is read from the position book.
            note(b, id, "The buy of $sym on ${h.day} was never confirmed by Zerodha; check that day's contract note")
            return null
        }
        val oid = h.order ?: run {
            // 22 Nov 2026 (B6 review, item 3): its lost reply looked for in the background ([Broker.lookLost]): found, that order; given up
            // on (the book read past the window does not show it), nothing held; still looked for (or none kept), the book read now as before.
            val key = lostBuyKeyOf(id, sym, h.since)
            val lost = Broker.lookLost(key)
            when (lost) {
                is Broker.LostLook.Found -> { ownerTag("kite:${lost.id}", "Pine · entry"); return@run lost.id }
                Broker.LostLook.GaveUp -> { noteOnce(b, id, "nothing:$id:${h.since}", "No buy of $sym reached Zerodha: nothing held"); return null }
                else -> Unit
            }
            // The order id itself was lost: the entry is found in today's book by its symbol, side, tag and time.
            val since = java.time.Instant.ofEpochMilli(h.since).atZone(com.optionslab.engine.IST).toLocalDateTime().minusSeconds(30)
            val row = runCatching { Broker.latestTagged(sym, "BUY", "irapine", since, knownKite()) }.getOrElse { return h }
                ?: run {
                    // (22 Nov 2026, B6 review, item 3: not in the book yet is no proof - held while its lost reply is still looked for, or,
                    // with none kept (after a restart), for the search's window from its send; only then nothing held.)
                    if (lost == Broker.LostLook.Searching || Market.now().toInstant().toEpochMilli() - h.since in 0 until Broker.LOST_SEARCH_MS) return h
                    noteOnce(b, id, "nothing:$id:${h.since}", "No buy of $sym reached Zerodha: nothing held"); return null
                }
            // (Found by its tag: its background search is over.)
            Broker.forgetLost(key)
            ownerTag("kite:${row.id}", "Pine · entry")
            row.id
        }
        var st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return h.copy(order = oid)
        if (st.status !in DONE) {
            runCatching { Broker.cancel(oid) }
            st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return h.copy(order = oid)
            if (st.status !in DONE) return h.copy(order = oid)
        }
        if (st.filled <= 0) { noteOnce(b, id, "nothing:$id:${h.since}", "The unconfirmed buy of $sym did not fill (${st.status.lowercase()}): nothing held"); return null }
        val px = st.avgPrice.takeIf { it > 0 } ?: h.entry
        note(b, id, "Zerodha confirmed the buy: ${st.filled} $sym at ${"%.2f".format(java.util.Locale.ENGLISH, px)} (LIVE)")
        // (22 Nov 2026, B6 review, third look: stamped as bought when its buy was sent - [Held.at] is the holding's own from here (the trade
        // manager's id, the highs since it was bought, a saved lost sale's owner), never 0 shared by every settled holding.)
        return h.copy(qty = st.filled, entry = px, order = null, since = 0L, peak = maxOf(h.peak, px), at = if (h.at > 0L) h.at else h.since)
    }

    /** Sell what the script holds. Removes it from [b] once sold (or found gone). */
    private suspend fun exit(b: Book, id: Long, item: PineScripts.Item, h0: Held, why: String) {
        var h = h0
        if (!h.live) {
            val c = Paper.contractOf(h.symbol) ?: run { b.held.remove(id); return }
            // Its resting stop comes out first (a stop still resting plus a market sell could both fill); if it filled
            // meanwhile, that was the exit.
            h.stop?.let { sid ->
                runCatching { Paper.cancel(sid, "exit:pine") }
                val so = Paper.state.orders.firstOrNull { it.orderId == sid }
                if (so?.status == "complete") { bookStop(b, id, item, h, so.averagePrice?.toDouble() ?: h.stopAt ?: h.entry, so.quantity, sid); return }
                if (so == null || so.status == "cancelled" || so.status == "rejected") { h = h.copy(stop = null); b.held[id] = h }
                else { note(b, id, "${h.symbol}: its resting stop could not be taken out; the sale is tried again next pass"); return }
            }
            val net = Paper.state.positions.filter { it.symbol == h.symbol && it.product == "MIS" }.sumOf { it.quantity }
            if (net <= 0) { b.held.remove(id); managerClosed(id, h, null, "already closed"); note(b, id, "${h.symbol} already closed"); return }
            val lots = (minOf(net, h.qty) / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
            // 22 Oct (arms audit): an exit only reduces what the book holds - closed by the square-off between the read above
            // and this sale, it is refused (never a short), and the holding is booked as already closed.
            val sell = Paper.place(c, "SELL", lots, "MARKET", "MIS", null, null, exit = true)
            if (!sell.ok && sell.message.contains("already closed")) {
                b.held.remove(id); managerClosed(id, h, null, "already closed"); note(b, id, "${h.symbol} already closed"); return
            }
            val fill = filledOrCancelled(sell) ?: run {
                // Nothing sold (no price): the stop goes back in the book where it was, so the holding is never left without
                // one; the sale is tried again on the next pass.
                h.stopAt?.let { at -> placeStopAt(c, lots, at)?.let { sid -> b.held[id] = h.copy(stop = sid) } }
                note(b, id, "Paper sell of ${h.symbol} not filled (${sell.message}); retrying next pass"); return
            }
            sell.orderId?.let { ownerTag("paper:$it", "${label(item)} · $why") }
            Notifier.orderFilled(app, "SELL", fill.first, h.symbol, fill.second, "Paper", label(item), sell.orderId)
            addPnl(b, id, (fill.second - h.entry) * fill.first)
            b.held.remove(id)
            managerClosed(id, h, fill.second, why)
            note(b, id, "Sold ${fill.first} ${h.symbol} at ${"%.2f".format(java.util.Locale.ENGLISH, fill.second)} ($why) · P&L ${"%+.0f".format(java.util.Locale.ENGLISH, (fill.second - h.entry) * fill.first)}")
            return
        }
        val sym = h.kite ?: run { b.held.remove(id); return }
        if (!Broker.loggedIn) { note(b, id, "Not logged in to Zerodha: cannot sell $sym. Close it in Trade."); return }
        // 22 Nov 2026 (B6 review, item 2): an earlier sale of this holding whose answer was lost, still looked for: nothing more is sold and
        // no stop rests beside it ([lostExit]); found, it was booked there. Given up on: its stop is put back at once in market hours before
        // 15:15 (so no early return below leaves the holding with none), then sold as below - the stop taken out first, as always. From 15:15
        // no stop is rested again (as every re-rest): the square-off is tried again on each check until it sells.
        when (lostExit(b, id, item, h)) {
            LostExit.HELD -> return
            LostExit.GAVE_UP -> h = b.held[id] ?: return
            LostExit.NONE -> Unit
        }
        // A buy not yet confirmed: its unfilled rest is cancelled first, and only what filled is sold.
        if (h.unconfirmed) {
            h = settle(b, id, h) ?: run { b.held.remove(id); return }
            b.held[id] = h
        }
        // Its resting stop already filled at Zerodha: that was the exit (booked at its own price, never "already closed").
        h.stop?.let { sid ->
            val st = runCatching { Broker.orderState(sid) }.getOrNull()
            if (st?.status == "COMPLETE") {
                bookStop(b, id, item, h, st.avgPrice.takeIf { it > 0 } ?: h.stopAt ?: h.entry, st.filled.takeIf { it > 0 } ?: h.qty, sid)
                return
            }
        }
        val net = runCatching { Broker.positionBook().net }.getOrNull() ?: return
        val still = net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.qty }
        if (minOf(still, h.qty) <= 0 && !h.unconfirmed) {
            // 19 Nov 2026 (audit): shown flat - booked closed only once confirmed (a sale outside the app in Zerodha's order book that
            // covers it, or agreeing reads: [flatConfirmed]); until then kept, its stop left resting. Then its stop and every own exit
            // still working on the contract are taken out and confirmed gone at Zerodha ([takeOutExits]) - before, the holding was
            // dropped here and its SL SELL left resting, which a fall back to it would fill into a naked short.
            if (!flatConfirmed(b, id, h, net, runCatching { kiteOrders() }.getOrNull())) return
            b.held.remove(id); managerClosed(id, h, null, "already closed"); note(b, id, "$sym already closed")
            takeOutExits(b, id, h)
            return
        }
        managerId(id, h).let { flatSince.remove(it); flatAlertSaid.remove(it) }
        // 23 Nov 2026 (B6 follow-ups, item 2: the 15:05 expiry square-off or the flat-by could sell the contract beside this sale, each taking
        // the other's stop out): one seller at a time on the contract's row ([SquareOffExit.claimExit], as the ORB / Liquidity exits) - taken
        // FIRST, before its stop comes out, and released once this sale went and was booked, or it gave up. Held by another seller, or a
        // square-off's sale sent on it a moment ago ([SquareOffExit.inGap]): this exit waits with its stop resting, and the next check tries
        // again (the square-off sells it; the next look reads the books again). Memory only, never a wait.
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", CLAIM_OWNER) ?: run {
            val holder = SquareOffExit.exitClaimedBy("NFO", sym, "MIS")
            noteOnce(b, id, "claim:$id:$sym:${h.at}:$holder", "$sym: the exit ($why) waits - ${SquareOffExit.claimWords(holder)} is selling it; " +
                "its stop stays resting and the exit is tried again on the next check")
            return
        }
        try {
            if (SquareOffExit.inGap("NFO", sym, "MIS")) {
                noteOnce(b, id, "gap:$id:$sym:${h.at}", "$sym: the exit ($why) waits - a square-off sold it a moment ago; its stop stays resting " +
                    "and the exit is tried again on the next check")
                return
            }
            sellLive(b, id, item, h0, h, sym, why)
        } finally { claim.release() }
    }

    /** 23 Nov 2026 (B6 follow-ups, item 2): Pine's name on a contract's exit claim ([SquareOffExit.claimExit]). */
    internal const val CLAIM_OWNER = "pine"

    /**
     * [exit]'s live sale of [start] (the holding as [exit] left it; [h0]: as the exit was asked) on Zerodha contract [sym], with the
     * contract's exit claim held ([SquareOffExit.claimExit]). 23 Nov 2026 (B6 follow-ups, item 2): taken out of [exit] unchanged.
     */
    private suspend fun sellLive(b: Book, id: Long, item: PineScripts.Item, h0: Held, start: Held, sym: String, why: String) {
        var h = start
        // Its own exits still working (an earlier pass's, or one whose reply was lost) come out first; every other sell
        // resting on the symbol (a protection's stop, an ORB stop, the owner's own limit) is left alone and subtracted:
        // together they never sell more than is held, and none of them can block this exit.
        val orders = runCatching { Broker.orders() }.getOrNull() ?: return
        // (22 Nov 2026, B6 review, item 2: the stop it rests with now - after a lost sale given up on, the one just put back.)
        val stopBefore = h.stop
        // 22 Nov 2026 (B6 review, item 9): this holding's own only - never another holding's stop (one Pine holding per index and side, so on
        // this contract every other working "irapine" sale is its own: its stop, an earlier sale; or a closed holding's, on its way out) -
        // each marked the app's own cancel BEFORE it goes ([cancelOwn]), so its end is never read as Boss's or Zerodha's.
        val othersStops = b.held.filterKeys { it != id }.values.mapNotNull { it.stop }.toSet()
        orders.filter { it.working && it.symbol == sym && it.side == "SELL" && it.tag == "irapine" && it.id !in othersStops }.forEach { cancelOwn(b, it.id, it.variety) }
        // Its resting stop was among them: if it filled meanwhile, that was the exit; once out, the holding has none (a stop
        // that could not be taken out is still working and is subtracted below, so the two never sell more than is held).
        h.stop?.let { sid ->
            val st = runCatching { Broker.orderState(sid) }.getOrNull()
            if (st?.status == "COMPLETE") {
                bookStop(b, id, item, h, st.avgPrice.takeIf { it > 0 } ?: h.stopAt ?: h.entry, st.filled.takeIf { it > 0 } ?: h.qty, sid)
                return
            }
            if (st != null && st.status in setOf("CANCELLED", "REJECTED")) { h = h.copy(stop = null); b.held[id] = h }
        }
        // The resting stop was taken out for this sale: if the sale then does not go (refused, failed, not filled) it goes
        // back for what is still held, at the level it rested at (never lower), so the holding is never left without one.
        // (22 Nov 2026, B6 review, item 2: as it rested just before - after a lost sale given up on, the stop put back counts too.)
        val tookStop = (h0.stop != null || stopBefore != null) && h.stop == null
        suspend fun restop(left: Held) {
            if (tookStop && left.qty > 0) b.held[id] = placeLiveStop(b, item, left.copy(stop = null), left.stopAt, capToHeld = true)
        }
        // 20 Oct, enhancement round 2: read and sent in the contract's one exit turn, from an orders read that shows every exit sent
        // from here ([ExitCoordinator]); and the orders first, then the positions read again (an exit filling between the two reads
        // shows as still working, never as still held with nothing on its way out).
        val orderId = ExitCoordinator.turn("NFO", sym, "Pine ${label(item)}") {
            // (22 Nov 2026, B6 review, item 2: a read that fails after the stop came out puts it back, as the held read's failure below does.)
            val book = runCatching { ExitCoordinator.orders("NFO", sym, "Pine ${label(item)}") }.getOrNull() ?: run { restop(h); return }
            val working = book.filter { it.working && it.symbol == sym && it.side == "SELL" && it.product == "MIS" }
            // Review: the read failing here, after its stop came out, puts the stop back (as every other "not sold" path does).
            // 21 Oct, enhancement round 3 (idea 1): held, checked against that orders read's fills ([ExitCoordinator.held]).
            // (23 Nov 2026, B6 review, eighth round, M1: less what Boss's own exit whose reply was lost may be selling.)
            val heldRaw = runCatching { ExitCoordinator.held("NFO", sym, "MIS", "Pine ${label(item)}", book) }.getOrNull() ?: run { restop(h); return }
            val heldNow = SquareOffExit.lessBossLost(heldRaw, sym, "MIS", "NFO")
            val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: run { restop(h); return }
            val qty = com.optionslab.engine.risk.ExitQty.sendable(heldNow, working.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) },
                h.qty, spec.lotSize)
            if (qty <= 0) {
                note(b, id, if (h.unconfirmed) "$sym: the buy is not confirmed yet and nothing of it is held to sell; checked again next pass"
                    else "$sym: other exits already working at Zerodha cover what is held; checked again next pass")
                // 22 Nov 2026 (B6 review, item 2): its stop, taken out above for this sale, is never left out for good: while Zerodha still
                // holds the contract (only other sales cover it), it is asked again on the next checks ([stopNotRested]: rested by [one] for
                // what the other sales leave once there is room). Never now - there is none - and never when Zerodha holds nothing.
                // (23 Nov 2026, B6 review, eighth round: on what Zerodha holds, before Boss's lost exit is taken off - his exit may never have gone.)
                if (tookStop && heldRaw > 0 && !h.unconfirmed) stopNotRested += lostStopKey(item, h)
                return
            }
            val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, qty, spec.lotSize, "MIS", "MARKET", null, spec.tickSize, "NFO", "irapine")
            val bad = com.optionslab.engine.Kite.refusals(o, AppSettings.load().limits(), Broker.sentToday(), false, exit = true)
            if (bad.isNotEmpty()) {
                com.optionslab.app.work.Alerts.error("${label(item)}: the Zerodha exit was not sent (${bad.joinToString("; ")}). Close $sym in Trade.", "Pine live")
                restop(h)
                return
            }
            // 23 Nov 2026 (B6 follow-ups, item 2): this sale is about to go - said to the square-offs ([SquareOffExit.saleStarting]), then the
            // gap looked at once more, in the ORB exits' order. (A backstop: with the row's claim held no square-off starts a sale on it
            // meanwhile; what keeps a square-off off this sale once the claim is let go is its fresh read of the books, the exits sent from
            // here not in it yet ([ExitCoordinator.unshownPending]: Pine's tag is not left to a count) and, while its answer is lost,
            // [Broker.lostSales].) A square-off's sale on it a moment ago: none is sent beside it, and its stop is not put back beside that
            // sale either (asked again on the next checks, which rest it only for what Zerodha still holds).
            SquareOffExit.saleStarting(sym, "MIS")
            if (SquareOffExit.inGap("NFO", sym, "MIS")) {
                note(b, id, "$sym: a square-off sold it a moment ago; nothing sold beside it, the next check reads again")
                if (tookStop && !h.unconfirmed) stopNotRested += lostStopKey(item, h)
                return
            }
            // (22 Nov 2026, B6 review: when it is sent - a lost sale's search starts there, after a restart too.)
            val sentAt = runCatching { Market.now().toLocalDateTime() }.getOrElse { java.time.LocalDateTime.now() }
            try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
                val seen = knownKite() + orders.map { it.id }
                // (22 Nov 2026, B6 review: Kite's unsure answer - [Broker.KiteUnsure] - was looked for in the door already: not again here.)
                val found = if (Broker.definite(e) || e is Broker.KiteUnsure) null else runCatching { Broker.findRecentRetrying(o, seen) }.getOrNull()
                found ?: run {
                    // 22 Nov 2026 (B6 review, item 2: "a Pine live exit has no lost-exit hold"): a lost answer not found in ~4 s may still be a
                    // sale that filled - its stop is NOT put back beside it, and nothing more is sold, until the book shows it or a read of it
                    // past [Broker.LOST_SEARCH_MS] still does not ([lostExit]). Only Zerodha's own refusal puts the stop back at once.
                    if (!Broker.definite(e)) { holdLostExit(b, id, item, h, o, seen, why, e.message, sentAt); return }
                    com.optionslab.app.work.Alerts.error("${label(item)}: the Zerodha exit failed (${e.message}); retrying on the next pass.", "Pine live")
                    restop(h)
                    return
                }
            }
        }
        bookSale(b, id, item, h, orderId, why) { restop(it) }
    }

    /**
     * The live exit sale [orderId] of holding [h] ([why]) waited for and booked: what filled is the exit (all: the holding closed; a part:
     * the rest held, [restop] for it); nothing filled and ended, [restop] for all of it; its state unknown, left to the next pass (what is
     * held is read from Zerodha before anything is placed). 22 Nov 2026 (B6 review, item 2): taken out of [exit] whole, for a lost sale
     * found later too ([lostExit]).
     */
    private suspend fun bookSale(b: Book, id: Long, item: PineScripts.Item, h: Held, orderId: String, why: String, restop: suspend (Held) -> Unit) {
        val sym = h.kite ?: return
        ownerTag("kite:$orderId", "${label(item)} · $why")
        var f = runCatching { Broker.awaitOrder(orderId, 15_000) }.getOrNull()
        if (f?.status !in DONE) {
            // Not finished in 15 s: the rest is cancelled, so the next pass starts from what Zerodha says is held.
            runCatching { Broker.cancel(orderId) }
            f = runCatching { Broker.orderState(orderId) }.getOrNull() ?: f
        }
        if (f == null) return                // unknown: what is held is read from Zerodha on the next pass before anything is placed
        if (f.filled <= 0) { if (f.status in DONE) restop(h); return }
        val px = f.avgPrice.takeIf { it > 0 } ?: h.entry
        Notifier.orderFilled(app, "SELL", f.filled, sym, px, "Live", label(item), f.orderId)
        // (22 Nov 2026, B6 review, third look: booked - never taken later for a sale since its stop's cancel ([saleOf]), so the unsold rest's
        // stop, taken out for it, can still rest again.)
        bookedSales += orderId
        addPnl(b, id, (px - h.entry) * f.filled)
        bookClose(b, sym, f.filled, outside = false)
        if (f.filled >= h.qty && !h.unconfirmed) {
            b.held.remove(id); managerClosed(id, h, px, why)
            // (22 Nov 2026, B6 review: closed with a stop still on it - a lost sale found late - that stop and its own exits are taken out
            // and confirmed gone at Zerodha, never left resting on a flat contract.)
            if (h.stop != null) takeOutExits(b, id, h)
        } else {
            b.held[id] = h.copy(qty = (h.qty - f.filled).coerceAtLeast(0))
            if (f.status in DONE) b.held[id]?.let { restop(it) }       // the unsold rest keeps its stop
        }
        note(b, id, "Sold ${f.filled} $sym at ${"%.2f".format(java.util.Locale.ENGLISH, px)} ($why, LIVE)")
    }

    // ---- 22 Nov 2026 (B6 review, items 2, 3 and 9): lost sales and buys, and the app's own cancels ----------------------------------

    /** What a holding's lost exit sale ([lostExitKeyOf]) means for this look: none kept, held (still looked for, or just booked), given up on. */
    private enum class LostExit { NONE, HELD, GAVE_UP }

    /**
     * The key a live holding's lost exit sale is looked for under: the script and the contract (a script holds one holding at a time, and
     * one whose sale is still looked for is never closed another way meanwhile - [lostExit]).
     */
    private fun lostExitKeyOf(id: Long, h: Held) = "$LOST_EXIT$id:${h.kite}"
    /** The prefix of [lostExitKeyOf] (23 Nov 2026: the square-offs' count of these sales, [Broker.lostSales]). */
    private const val LOST_EXIT = "pineexit:"

    /** The key a live buy whose answer was lost is looked for under: the script, the contract and when it was sent ([Held.since]). */
    private fun lostBuyKeyOf(id: Long, sym: String, sentMs: Long) = "pinebuy:$id:$sym:$sentMs"

    /** Why each lost exit sale was sent (its key -> the words), for its booking once found. Memory only; bounded. */
    private val lostExitWhy = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Holding [h]'s exit sale [o] ([why]) answered with a lost reply and not found in the book: kept UNKNOWN under [lostExitKeyOf]
     * ([Broker.noteLost]; [seen]: never adopted for it), its stop left out, Boss told once. [lostExit] follows it on each later look.
     */
    private fun holdLostExit(b: Book, id: Long, item: PineScripts.Item, h: Held, o: com.optionslab.engine.Kite.Order, seen: Collection<String>,
                             why: String, err: String?, sentAt: java.time.LocalDateTime) {
        val key = lostExitKeyOf(id, h)
        val since = System.currentTimeMillis()
        Broker.noteLost(key, o, seen, "${label(item)}: its exit", sinceMs = since, sentAt = sentAt)
        if (lostExitWhy.size > 200) lostExitWhy.clear()
        lostExitWhy[key] = why
        // (22 Nov 2026, B6 review: kept with the book - written on its own writer now - so a restart in its window holds it too.)
        b.lostSales[key] = JSONObject().put("script", id).put("kite", o.tradingSymbol).put("qty", o.quantity).put("lot", o.lotSize)
            .put("tick", o.tickSize).put("type", o.orderType).put("product", o.product).put("sentAt", sentAt.toString()).put("since", since)
            .put("why", why).put("known", JSONArray(seen.toList().takeLast(300)))
        saveSoon(b)
        note(b, id, "${h.kite}: Zerodha's answer to the exit sale was lost ($err) and the sale is not in its order book yet: nothing more is " +
            "sold and no stop is put back beside it until the book shows it")
        com.optionslab.app.work.Alerts.error("${label(item)}: Zerodha's answer to the exit of ${h.kite} was lost and the sale is not in its order " +
            "book yet. Until the book shows it (about 2 minutes) nothing more is sold and no stop rests on it, so it is unprotected if the " +
            "sale did not go: check ${h.kite} in Kite.", "Pine live")
    }

    /**
     * 22 Nov 2026 (B6 review, item 2: "a Pine live exit has no lost-exit hold" - a MARKET sale whose answer was lost and that the book did not
     * show in ~4 s put the whole stop back beside a sale that may have filled): live holding [h]'s lost exit sale ([holdLostExit]). Still
     * looked for: [LostExit.HELD] - nothing more is sold and no stop rests beside it. Found: booked
     * as the sale ([bookSale]; nothing filled, its stop goes back), [LostExit.HELD]. Given up on - only once a read of the book that SUCCEEDED
     * past [Broker.LOST_SEARCH_MS] still does not show it ([Broker.lookLost]) - [LostExit.GAVE_UP]: the holding goes on as before, its stop
     * put back now (an exit then takes it out first, as always). Meanwhile the holding has NO stop and is not sold at its level either (a
     * second sale beside one that may have filled is what this prevents): Boss is told so when it is lost ([holdLostExit]). A fast look
     * ([quick], or a stream look) reads and sends nothing: one still kept is held. Memory only, but for the found sale's booking and the
     * stop put back.
     */
    private suspend fun lostExit(b: Book, id: Long, item: PineScripts.Item, h: Held, quick: Boolean = false): LostExit {
        if (!h.live) return LostExit.NONE
        val key = lostExitKeyOf(id, h)
        // (A sale found while its buy was still unconfirmed, kept until that buy is settled: [foundSale].)
        val kept = foundSale(b, key)
        if (quick || streamLook) return if (Broker.lostPending(key) || kept != null) LostExit.HELD else LostExit.NONE
        return when (val l = kept?.let { Broker.LostLook.Found(it, h.qty) } ?: Broker.lookLost(key)) {
            // (None kept in memory - its search ended another way: its saved record goes too, never held again after a restart.)
            null -> { if (b.lostSales.remove(key) != null) lostExitWhy.remove(key); LostExit.NONE }
            Broker.LostLook.Searching -> LostExit.HELD
            is Broker.LostLook.Found -> {
                if (kept == null) note(b, id, "${h.kite}: the exit sale whose answer was lost is in Zerodha's order book (order ${l.id})")
                // (22 Nov 2026, B6 review: a buy not confirmed yet is settled first, so the sale is booked against what was bought.)
                val cur = if (!h.unconfirmed) h else settle(b, id, h) ?: run {
                    lostExitWhy.remove(key); b.lostSales.remove(key)
                    b.held.remove(id); managerClosed(id, h, null, "its buy did not fill")
                    // (The sale found - and every other own exit still working on the contract - taken out and confirmed gone at Zerodha.)
                    takeOutExits(b, id, h)
                    com.optionslab.app.work.Alerts.error("${label(item)}: its exit sale ${l.id} of ${h.kite} went though its buy shows nothing filled. " +
                        "Check ${h.kite} in Kite now.", "Pine live")
                    return LostExit.HELD
                }
                b.held[id] = cur
                if (cur.unconfirmed) {
                    // (Its buy's state not read yet: the sale is kept, still held - no stop beside it, nothing more sold - and booked once the
                    // buy is settled on a later check; a stop rested for the buy's whole fill now could sit on a contract the sale emptied.)
                    (b.lostSales[key] ?: JSONObject().put("script", id).put("kite", h.kite)
                        .put("sentAt", runCatching { Market.now().toLocalDateTime() }.getOrElse { java.time.LocalDateTime.now() }.toString())
                        .also { b.lostSales[key] = it }).put("found", l.id)
                    return LostExit.HELD
                }
                val why = lostExitWhy.remove(key) ?: "exit"
                b.lostSales.remove(key); Broker.forgetLost(key)
                bookSale(b, id, item, cur, l.id, why) { left -> if (left.stop == null && left.qty > 0) b.held[id] = placeLiveStop(b, item, left, left.stopAt, capToHeld = true) }
                LostExit.HELD
            }
            Broker.LostLook.GaveUp -> {
                lostExitWhy.remove(key)
                b.lostSales.remove(key)
                // (22 Nov 2026, B6 review: in market hours before 15:15, as every re-rest; else asked again on a later check.)
                val mins = clock().let { it.hour * 60 + it.minute }
                if (h.stop == null && h.qty > 0) {
                    if (isOpen() && mins < 15 * 60 + 15) b.held[id] = placeLiveStop(b, item, h, h.stopAt, capToHeld = true) else stopNotRested += lostStopKey(item, h)
                }
                LostExit.GAVE_UP
            }
        }
    }

    /** 22 Nov 2026 (B6 review): the lost exit sale under [key] already found while its buy was not settled yet ([lostExit]), if any. */
    private fun foundSale(b: Book, key: String): String? = b.lostSales[key]?.optString("found")?.takeIf { it.isNotBlank() }

    /**
     * 22 Nov 2026 (B6 review, item 9): the orders the scripts' own exits take out themselves (a holding's stop and its own earlier sales -
     * [exit], [removeExits]), each marked BEFORE its cancel goes ([cancelOwn]): such an end is the app's, never read as Boss's or Zerodha's
     * (WHO CANCELLED A STOP) - said as the app's own ([stopFilled]) and never taken as a stop with no owner ([placeLiveStop]). Memory only,
     * bounded (the oldest dropped first), as the ORB arms' own marks; a mark whose cancel Zerodha refused is taken back.
     */
    private val ownCancels: MutableSet<String> = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 500
        }))

    private fun ownCancel(oid: String): Boolean = oid in ownCancels

    /**
     * 22 Nov 2026 (B6 review): when each own cancel went (the market's clock, as Zerodha's order times; memory only, bounded) - a sale of the
     * exit is looked for from then ([saleOf]).
     */
    private val ownCancelAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 22 Nov 2026 (B6 review, third look): the market clock in ms - the clock of [ownCancelAt] and of Zerodha's order times. */
    private fun marketMs(): Long = runCatching { Market.now().toInstant().toEpochMilli() }.getOrElse { System.currentTimeMillis() }

    /**
     * 22 Nov 2026 (B6 review, third look: "a stale own-cancel mark can put back a stop Boss cancelled"): a stop the app marked as its own
     * cancel and that a FRESH read of the order book ([rows]: never the stream's kept copy) still shows working [OWN_CANCEL_RECHECK_MS] or
     * more after the mark - that cancel never took: the mark ends (dropped from the book with its next save), so Boss's own later cancel of
     * it in Kite is his and never put back (WHO CANCELLED A STOP). One taken back from the book after a restart (no time kept) ends the first
     * time it is seen working. A cancel the app sends again marks it afresh. As the ORB arms' rule.
     */
    private fun ownCancelsSeenWorking(rows: List<Broker.OrderRow>) {
        val now = marketMs()
        for (r in rows) if (r.working && ownCancel(r.id)) {
            val at = ownCancelAt[r.id]
            if (at == null || now - at !in 0 until OWN_CANCEL_RECHECK_MS) {
                ownCancels -= r.id; ownCancelAt.remove(r.id)
                runCatching { Diag.record("pine", "${r.symbol}: the app's cancel of ${r.id} never took (still working): no longer marked its own") }
            }
        }
    }

    /** 22 Nov 2026 (B6 review, third look): how long after its own cancel the app may still put a stop back ([ownRestAgain]). */
    private const val OWN_CANCEL_FRESH_MS = 10 * 60_000L
    /** 22 Nov 2026 (B6 review, third look): a fresh read still showing an own-cancelled stop working this long after ends its mark. */
    internal const val OWN_CANCEL_RECHECK_MS = 15_000L

    /** Whether the app's own cancel of [sid] is still fresh: made in this process ([ownCancelAt]) at most [OWN_CANCEL_FRESH_MS] ago. */
    private fun ownCancelFresh(sid: String): Boolean = ownCancel(sid) && ownCancelAt[sid]?.let { marketMs() - it in 0..OWN_CANCEL_FRESH_MS } == true

    /** TEST ONLY (22 Nov 2026): the own cancel of [oid] marked [ms] earlier than it was. */
    internal fun ageOwnCancelForTest(oid: String, ms: Long) { ownCancelAt.computeIfPresent(oid) { _, v -> v - ms } }

    /**
     * 22 Nov 2026 (B6 review, third look): the exit sales this process booked against a holding ([bookSale]) - their fills are already out
     * of its quantity, so [saleOf] never takes one for a sale since the stop's cancel. Memory only, bounded.
     */
    private val bookedSales: MutableSet<String> = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
        object : java.util.LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 500
        }))

    /**
     * 22 Nov 2026 (B6 review): whether holding [h]'s stop, taken out by the app ([sid]) for an exit, is to rest again - only when no sale of
     * the contract went since ([saleOf]) and Zerodha still holds it, read afresh: [orders] (null: not read) and the positions now. Null: not
     * decided (a read failed) - asked again on the next regular check ([ownRestDue]); the app watches the level meanwhile.
     */
    private suspend fun ownRestAgain(h: Held, orders: List<Broker.OrderRow>?, sid: String): Boolean? {
        if (!ownCancelFresh(sid)) return false
        val rows = orders ?: return null
        if (rows.any { saleOf(it, h, ownCancelAt[sid]) }) return false
        val heldZ = runCatching { Broker.positionBook().net }.getOrNull()
            ?.filter { it.symbol == h.kite && it.exchange == "NFO" && it.product == "MIS" }?.sumOf { it.qty } ?: return null
        if (heldZ <= 0) return false
        // (22 Nov 2026, B6 review, third look: Zerodha holds less than the holding - sold some other way: never re-rested blindly; the app
        // watches the level, which sells only what Zerodha holds, and Boss is told.)
        if (heldZ < h.qty) {
            com.optionslab.app.work.Alerts.error("Pine: Zerodha holds $heldZ of ${h.kite}, less than the ${h.qty} the script holds: its stop, taken " +
                "out by the app for an exit, is not put back at Zerodha. The app watches the level; check ${h.kite} in Kite.", "Pine live")
            return false
        }
        return true
    }

    /** 22 Nov 2026 (B6 review): the own-cancelled stops whose re-rest is still to be decided on fresh reads ([ownRestAgain]), by stop key. Memory only. */
    private val ownRestDue = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 22 Nov 2026 (B6 review): whether order [r] is a sale on holding [h]'s contract that went - the scripts' own sell (not a stop) still
     * working, or any sale with a fill (any owner: Boss's in Kite, Zerodha's square-off, another arm's stop) placed since [sinceMs] (the stop's
     * cancel; not known - after a restart - since it was bought), a minute's allowance for the clocks; a time that cannot be read: it is. An
     * earlier part-sale already booked never counts; another owner's stop still resting is left to [placeLiveStop]'s sizing.
     */
    private fun saleOf(r: Broker.OrderRow, h: Held, sinceMs: Long?): Boolean {
        if (r.symbol != h.kite || r.exchange != "NFO" || r.side != "SELL") return false
        // (22 Nov 2026, B6 review, third look: one this process booked against a holding is already out of its quantity.)
        if (r.id in bookedSales) return false
        if (r.working) return r.tag == "irapine" && r.type != "SL" && r.type != "SL-M"
        if (r.filled <= 0) return false
        val since = sinceMs ?: h.at.takeIf { it > 0L } ?: return true
        // (22 Nov 2026, B6 review, third look: by when it FILLED - the exchange's last change of it - never when it was placed: a sale placed
        // before the cancel can fill after it.)
        val filledAt = runCatching {
            java.time.LocalDateTime.parse(r.updatedAt, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()
        }.getOrNull() ?: return true
        return filledAt >= since - 60_000L
    }

    /**
     * 22 Nov 2026 (B6 review): the lost exit sales saved with [bk] ([Book.lostSales]) taken back into memory as the book is read (after a
     * restart): each one's hold and its 2-minute window go on from when it was first noted ([Broker.noteLost] - the same one, if this process
     * still keeps it). Only one sent today, of a live holding its script still has on that contract; any other is dropped (and said).
     */
    private fun restoreLostSales(bk: Book) {
        val today = runCatching { Market.now().toLocalDate() }.getOrElse { todayIst() }
        for ((key, r) in bk.lostSales.toMap()) {
            val ok = runCatching {
                val script = r.getLong("script")
                val sym = r.getString("kite")
                val held = bk.held[script]?.takeIf { it.live && it.kite == sym && lostExitKeyOf(script, it) == key } ?: return@runCatching false
                // (Today's only - a found one too: by when it was sent, or one saved without that time by its holding's day.)
                val sentAt = r.optString("sentAt").takeIf { it.isNotBlank() }?.let { java.time.LocalDateTime.parse(it) }
                if ((sentAt?.toLocalDate()?.toString() ?: held.day) != today.toString()) return@runCatching false
                // (One found already, waiting for its buy to settle, needs no search: kept as it is.)
                if (r.optString("found").isNotBlank()) { lostExitWhy[key] = r.optString("why", "exit"); return@runCatching true }
                sentAt ?: return@runCatching false
                // (That holding's own: bought before the sale was noted - a later holding of the same script and contract never takes it.)
                val start = held.since.takeIf { it > 0L } ?: held.at
                if (start > 0L && start > r.getLong("since")) return@runCatching false
                val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, r.getInt("qty"), r.optInt("lot", held.lotSize),
                    r.optString("product", "MIS"), r.optString("type", "MARKET"), null, r.optDouble("tick", 0.05), "NFO", "irapine")
                val known = r.optJSONArray("known")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                Broker.noteLost(key, o, known, "Pine #$script: its exit", sinceMs = r.getLong("since"), sentAt = sentAt)
                lostExitWhy[key] = r.optString("why", "exit")
                true
            }.getOrDefault(false)
            if (!ok) {
                bk.lostSales.remove(key)
                runCatching { Diag.record("pine", "$key: a lost exit sale saved before the restart was not taken back (not today's, or its holding is gone)") }
            }
        }
    }

    /** How many of the app's own cancels are saved with the book ([ownCancels]). */
    private const val OWN_CANCELS_SAVED = 200

    /** TEST ONLY: whether [oid] is marked the app's own cancel. */
    internal fun ownCancelForTest(oid: String): Boolean = ownCancel(oid)

    /**
     * [oid] marked the app's own cancel ([ownCancels]) and the mark handed to the book's writer ([saveSoon]: no disk wait here) BEFORE the
     * cancel goes, then cancelled; a cancel Zerodha refused is unmarked. True when it was taken.
     */
    private suspend fun cancelOwn(b: Book, oid: String, variety: String = "regular"): Boolean {
        ownCancels += oid
        if (ownCancelAt.size > 500) ownCancelAt.entries.sortedBy { it.value }.take(ownCancelAt.size - 500).forEach { ownCancelAt.remove(it.key, it.value) }
        ownCancelAt[oid] = marketMs()
        saveSoon(b)
        val e = runCatching { Broker.cancel(oid, variety) }.exceptionOrNull() ?: return true
        // (Zerodha's refusal: it did not take - a later cancel of it is not the app's. A lost answer may have taken: kept marked.)
        if (Broker.definite(e)) { ownCancels -= oid; ownCancelAt.remove(oid) }
        return false
    }

    /**
     * 24 Nov 2026 (B6-I round 6, final integration review: a script's stop that a square-off or Boss's exit from the app took out was read as
     * gone from Zerodha - Boss's or the exchange's - so it was never put back, WHO CANCELLED A STOP): [ids], resting orders a square-off
     * ([SquareOffExit]) or Boss's exit from the app (AppModel) is about to take out, marked the app's own cancel here - before the cancel goes -
     * for the holdings' own stops only ([mayOwnStop]; every id while the book is not read yet), as the other books mark theirs. Such a stop found
     * cancelled while still held, with no sale since, rests again ([ownRestAgain]); a mark whose cancel never took ends as any own cancel's does
     * ([ownCancelsSeenWorking]). Memory at once; handed to the book's writer when its lock is free, else saved with its next save. Never waits.
     */
    internal fun markOwnCancels(ids: Collection<String>) {
        val mine = ids.filter { mayOwnStop(it) }
        if (mine.isEmpty()) return
        val at = marketMs()
        for (id in mine) {
            // (One this module marked itself - its own exit's cancel - stays its own: never taken back by [unmarkOwnCancels].)
            if (!ownCancel(id)) squareMarks += id
            ownCancels += id
            ownCancelAt[id] = at
        }
        marksSoon()
    }

    /** 24 Nov 2026 (B6-I round 6): [markOwnCancels]' marks taken back for [ids] whose cancel did not take (still working): a later cancel of them is Boss's. */
    internal fun unmarkOwnCancels(ids: Collection<String>) {
        val gone = ids.filter { squareMarks.remove(it) }
        if (gone.isEmpty()) return
        for (id in gone) { ownCancels -= id; ownCancelAt.remove(id) }
        marksSoon()
    }

    /** 24 Nov 2026 (B6-I round 6): whether [id] may be a live holding's resting stop - any id while the book is not read yet. Memory only. */
    internal fun mayOwnStop(id: String): Boolean = cache == null || _held.value.values.any { it.live && it.stop == id }

    /** 24 Nov 2026 (B6-I round 6): the marks [markOwnCancels] made (and only those), so [unmarkOwnCancels] never takes back one of the scripts' own. */
    private val squareMarks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The marks handed to the book's writer now when its lock is free (encoded under it); else they go with the book's next save. */
    private fun marksSoon() {
        if (!lock.tryLock()) return
        try { cache?.let { runCatching { saveSoon(it) } } } finally { lock.unlock() }
    }

    /**
     * Whether [h] is no longer held. 19 Nov 2026 (audit): a live one only once confirmed ([flatConfirmed]): one positions read showing
     * it at 0 never drops a held position (or takes its stop out).
     */
    private suspend fun gone(b: Book, id: Long, h: Held): Boolean = if (!h.live) {
        Paper.state.positions.filter { it.symbol == h.symbol && it.product == "MIS" }.sumOf { it.quantity } <= 0
    } else {
        val net: List<Broker.Position>? = if (!Broker.loggedIn || h.kite == null) null else runCatching { Broker.positionBook().net }.getOrNull()
        if (net == null) false
        else if (net.filter { it.symbol == h.kite && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.qty } > 0) {
            managerId(id, h).let { flatSince.remove(it); flatAlertSaid.remove(it) }; false
        } else flatConfirmed(b, id, h, net, runCatching { kiteOrders() }.getOrNull())
    }

    // ---- 19 Nov 2026 (audit): a closed live holding's stop is taken out at Zerodha, and confirmed ------------------------------

    /**
     * 19 Nov 2026 (second review): the contracts bought again while an earlier stop was being taken out, already told ("day|symbol|its
     * orders": told once for each earlier holding's orders). Memory only; bounded.
     */
    private val reentrySaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * TEST ONLY: the order labels as [flatConfirmed] reads them (a test makes the read fail). Null in the app, always: the labels are then
     * [Strategies.owners]. Its setter throws unless BuildConfig.DEBUG (as [testNow]); no app code sets it.
     */
    @Volatile internal var testOwners: (suspend () -> Map<String, String>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test labels exist only in debug builds" }; field = v }

    /** 19 Nov 2026 (second review): a [Removing] order's fill when it was recorded, not known (neither read answered). */
    private const val UNKNOWN_FILL = -1

    /** How many tries of [removeExits] before Boss is told that a closed holding's stop could not be taken out. */
    internal const val REMOVE_TRIES = 3

    /** When each live holding ([managerId]) was first read at 0 with a row shown (market-clock ms). Memory only; bounded. */
    private val flatSince = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** The holdings shown at 0 with no sale anywhere that Boss was told of (once until seen held or closed). Memory only; bounded. */
    private val flatAlertSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Today's live close of [qty] units of [sym] booked ([Book.closed]); [outside]: closed outside the app. Older days are dropped. */
    private fun bookClose(b: Book, sym: String, qty: Int, outside: Boolean) {
        if (qty <= 0) return
        val day = todayIst().toString()
        b.closed.keys.removeAll { !it.startsWith("$day|") }
        b.closed["$day|$sym|a"] = (b.closed["$day|$sym|a"] ?: 0) + qty
        if (outside) b.closed["$day|$sym|o"] = (b.closed["$day|$sym|o"] ?: 0) + qty
    }

    /**
     * Whether live holding [h] of script [id], which the positions read [net] shows at 0 (or less), is confirmed closed
     * ([com.optionslab.engine.orb.LiveMirror.shortConfirmed], as the arms' since 18 Nov): Zerodha lists a row for the contract (a read
     * with no row for a contract bought today is a bad read and never counts), and either its order book ([orders]; null: not read)
     * shows sales of it outside the app today (Boss's own in Kite, Zerodha's square-off, the app's square-off) that cover this
     * holding - less Boss's own buys today and what was carried, and less what this book and the arms' live books already booked closed
     * from outside today (one sale proves one close only) - or the reads have kept showing it at 0 for 30 s (some sale on the contract
     * not booked by any book) or five minutes (none; Boss is told once at 30 s). Until then it is kept, watched, its stop resting.
     * Confirmed, it is booked here as closed ([bookClose]; from outside when an outside sale proved it); the callers then drop it. A holding from an earlier day is
     * closed (an MIS position never carries into the next session; its DAY stop went with that session), as before.
     */
    private suspend fun flatConfirmed(b: Book, id: Long, h: Held, net: List<Broker.Position>, orders: List<Broker.OrderRow>?): Boolean {
        val sym = h.kite ?: return false
        val key = managerId(id, h)
        if (flatSince.size > 200) flatSince.clear()
        if (flatAlertSaid.size > 200) flatAlertSaid.clear()
        val ms = clock().toInstant().toEpochMilli()
        val today = todayIst()
        if (runCatching { java.time.LocalDate.parse(h.day).isBefore(today) }.getOrDefault(false)) { flatSince.remove(key); return true }
        val rowShown = net.any { it.symbol == sym && it.exchange == "NFO" }
        val onIt = orders.orEmpty().filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" && it.filled > 0 }
        // (Boss's own orders are told by having no tag (Kite's own app), or the app's hand-order tag ("iraalgo": the Trade tab, a
        // notification's Close) with no book's label: the order labels are read only when such an order is on the contract - never on
        // a stream look, which waits on no other book: there such an order is not taken as Boss's sale, the careful side.)
        val hand = { o: Broker.OrderRow -> o.tag.isBlank() || o.tag == "iraalgo" }
        val read = if (onIt.none(hand) || streamLook) null else runCatching { testOwners?.invoke() ?: Strategies.owners() }
        // (The labels not read: an order with no tag is still Boss's (none of the app's goes without one), the app's hand tag unknown.)
        val owners: Map<String, String>? = if (onIt.none(hand)) emptyMap() else if (streamLook) null else read?.getOrNull() ?: emptyMap()
        val labelsRead = read?.isSuccess != false
        // (19 Nov 2026, second review: a hand order of the app's - its own label "Manual ...", or none - counts as Boss's, as one in Kite.)
        val boss = { o: Broker.OrderRow -> owners != null && hand(o) &&
            owners["kite:${o.id}"].let { l -> if (o.tag.isBlank()) l == null else labelsRead && (l == null || l.startsWith(Origins.MANUAL)) } }
        val outsideSells = onIt.filter { it.side == "SELL" && (boss(it) || it.tag in SquareOffExit.TAGS) }.sumOf { it.filled }
        val carried = net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.overnight }
        // (19 Nov 2026, third review: a hand buy whose label is not known - not read, or a stream look - counts as Boss's buy, the careful
        // side: his buy and sale are a round trip, never proof that the script's holding closed.)
        val outsideBuys = onIt.filter { it.side == "BUY" && hand(it) && (owners == null || !labelsRead || boss(it)) }.sumOf { it.filled } +
            carried.coerceAtLeast(0)
        val day = today.toString()
        val orb = runCatching { OrbArms.liveClosedHint.filter { it.exchange == "NFO" && it.symbol == sym && it.product == "MIS" && it.at.toLocalDate() == today } }
            .getOrDefault(emptyList())
        val arms = runCatching { ArmLive.view.value.closed.filter { it.exchange == "NFO" && it.symbol == sym && it.product == "MIS" && it.exitTime?.toLocalDate() == today } }
            .getOrDefault(emptyList())
        val bookedOutside = (b.closed["$day|$sym|o"] ?: 0) + orb.filter { it.outside }.sumOf { it.qty } +
            arms.filter { it.why == "closed_at_zerodha" }.sumOf { it.qty }
        val bookedAll = (b.closed["$day|$sym|a"] ?: 0) + orb.sumOf { it.qty } + arms.sumOf { it.qty }
        val sold = com.optionslab.engine.orb.LiveMirror.outsideSaleCovers(outsideSells, outsideBuys, bookedOutside, h.qty)
        val unbooked = com.optionslab.engine.orb.LiveMirror.unbookedSale(onIt.filter { it.side == "SELL" }.sumOf { it.filled }, outsideBuys, bookedAll)
        // (A read with no row for it starts no count: it is no reading at all.)
        val first = if (rowShown) flatSince.putIfAbsent(key, ms) else flatSince[key]
        if (com.optionslab.engine.orb.LiveMirror.shortConfirmed(rowShown, sold, first ?: ms, ms, unbooked)) {
            flatSince.remove(key); flatAlertSaid.remove(key)
            // (Closed from outside only when an outside sale proved it, or when hand sales whose labels were not known cover it (19 Nov
            // 2026, third review: that sale is then used up): a close on agreeing reads alone uses up no sale of Boss's.)
            val handSells = onIt.filter { it.side == "SELL" && ((hand(it) && (owners == null || !labelsRead || boss(it))) || it.tag in SquareOffExit.TAGS) }
                .sumOf { it.filled }
            bookClose(b, sym, h.qty, outside = sold ||
                com.optionslab.engine.orb.LiveMirror.outsideSaleCovers(handSells, outsideBuys, bookedOutside, h.qty))
            return true
        }
        if (rowShown && first == null) note(b, id, "$sym: Zerodha's positions show it at 0 with no sale outside the app to explain it: " +
            "not taken as closed until confirmed; its stop stays at Zerodha and it is watched")
        if (com.optionslab.engine.orb.LiveMirror.shortAlertDue(rowShown, sold, first, ms, unbooked) && flatAlertSaid.add(key))
            com.optionslab.app.work.Alerts.error("Pine #$id: Zerodha's positions have shown $sym at 0 for 30 s with no sale in its order book. " +
                "Kept as held, its stop resting at Zerodha; taken as closed only if this lasts 5 minutes. Check it in Kite.", "Pine live")
        return false
    }

    /**
     * Live holding [h] of script [id], confirmed closed at Zerodha (already dropped from the book): its resting stop and every own exit
     * ("irapine" SELL) still working on its contract are taken out at Zerodha and confirmed gone ([removeExits]); one not confirmed is
     * kept in [Book.removing] and tried again on every check. No other script can hold the same contract meanwhile (one index, one
     * side), and a retry cancels only the orders recorded here, never a later holding's stop. A holding from an earlier day has none
     * left (its DAY orders ended with that session).
     */
    private suspend fun takeOutExits(b: Book, id: Long, h: Held) {
        val sym = h.kite ?: return
        val today = todayIst()
        if (runCatching { java.time.LocalDate.parse(h.day).isBefore(today) }.getOrDefault(false)) return
        // (19 Nov 2026, second review: never the kept orders of a stream look, which may miss an exit sent since: there the contract is
        // scanned on the next 15-second check ([Removing.scanned] false); the stop itself is taken out now.)
        val orders = if (streamLook) null else runCatching { Broker.orders() }.getOrNull()
        val ids = LinkedHashMap<String, Int>()
        // (What of the stop had filled: from the orders read (on a stream look, the kept one - [Broker.ordersRecon] reads afresh only
        // when none may stand for it), else its own state read (not on a stream look); not read: unknown ([UNKNOWN_FILL]), so a stop's
        // own fill is never told as a short.)
        h.stop?.let { sid -> ids[sid] = (orders ?: if (streamLook) runCatching { Broker.ordersRecon() }.getOrNull() else null)
            ?.firstOrNull { it.id == sid }?.filled
            ?: (if (streamLook) null else runCatching { Broker.orderState(sid) }.getOrNull()?.filled) ?: UNKNOWN_FILL }
        // (22 Nov 2026, B6 review, item 9: never another holding's stop.)
        val othersStops = b.held.filterKeys { it != id }.values.mapNotNull { it.stop }.toSet()
        orders.orEmpty().filter { it.working && it.symbol == sym && it.exchange == "NFO" && it.side == "SELL" && it.tag == "irapine" && it.id !in othersStops }
            .forEach { ids[it.id] = it.filled }
        // (19 Nov 2026, third review: an order an earlier holding's removal already holds stays with it - one order, one entry.)
        val pending = b.removing.flatMap { it.ids.keys }.toSet()
        ids.keys.removeAll(pending)
        if (ids.isEmpty() && orders != null) return
        val r = Removing(id, sym, ids, today.toString(), scanned = orders != null, at = System.currentTimeMillis())
        b.removing += r
        removeExits(b, listOf(r))
    }

    /**
     * Every [Book.removing] entry ([only]: just those, as they were) taken out at Zerodha: its contract's own exits looked for first when they
     * could not be when it closed ([Removing.scanned]: never while a script holds the contract again - its exits are its own); each
     * order cancelled and its state read back - one shown CANCELLED, REJECTED or COMPLETE is done, one still working (or not read) is
     * kept and tried again on the next check. After [REMOVE_TRIES] tries Boss is told once to cancel it in Kite; one that filled more
     * after its holding closed (it sold what was no longer held: a short) is told at once. An entry of an earlier session is dropped
     * (its DAY orders ended with it). Never places an order.
     */
    private suspend fun removeExits(b: Book, only: List<Removing>? = null) {
        if (b.removing.isEmpty()) return
        val today = todayIst().toString()
        // (Not logged in to Zerodha: nothing can be cancelled - each entry still counts a try, so Boss is told to cancel it in Kite.)
        val loggedIn = Broker.loggedIn
        for (r0 in (only ?: b.removing.toList())) {
            // (19 Nov 2026, third review: an entry handed in that is no longer the book's - replaced or done since - is skipped.)
            if (only != null && b.removing.none { it === r0 }) continue
            var r = r0
            if (r.day < today) { b.removing.remove(r0); continue }
            if (!loggedIn) {
                val i = b.removing.indexOfFirst { it === r0 }
                if (i >= 0) b.removing[i] = tellOnce(b, r.copy(tries = r.tries + 1), r.ids.keys, "not logged in to Zerodha")
                continue
            }
            // (Not on a stream look, which reads no order book over REST: the next 15-second check scans it.)
            // (19 Nov 2026, third review: never marked looked for without a real look. Held again, the contract's own exits are the new
            // holding's too: looked for once it is closed; Boss was told at the buy.)
            val heldAgain = !r.scanned && b.held.values.any { it.live && it.kite == r.kite }
            if (!r.scanned && !streamLook && !heldAgain) {
                val orders = runCatching { Broker.orders() }.getOrNull()
                if (orders != null) r = r.copy(scanned = true, ids = r.ids + orders.filter { it.working && it.symbol == r.kite &&
                    it.exchange == "NFO" && it.side == "SELL" && it.tag == "irapine" && it.id !in r.ids &&
                    b.removing.none { o -> o !== r0 && it.id in o.ids } }.associate { it.id to it.filled })
            }
            val left = LinkedHashMap<String, Int>()
            for ((oid, base0) in r.ids) {
                var base = base0
                var st = runCatching { Broker.orderState(oid) }.getOrNull()
                // (A fill not known when it was recorded: what a still-working order has filled now, before its cancel.)
                if (base == UNKNOWN_FILL && st != null && st.status !in DONE) base = st.filled
                if (st == null || st.status !in DONE) {
                    // (22 Nov 2026, B6 review, item 9: marked the app's own before it goes.)
                    cancelOwn(b, oid)
                    st = runCatching { Broker.orderState(oid) }.getOrNull()
                }
                if (st == null || st.status !in DONE) { left[oid] = base; continue }
                val text = if (base != UNKNOWN_FILL && st.filled > base) "Pine #${r.script}: its exit order $oid on ${r.kite} filled " +
                        "${st.filled - base} after the position was closed. A short of ${r.kite} may be open at Zerodha: check it in Kite now."
                    // (Its fill not known when it was recorded: what filled may have been the close itself, or a sale after it.)
                    else if (base0 == UNKNOWN_FILL && st.filled > 0) "Pine #${r.script}: its exit order $oid on ${r.kite} shows ${st.filled} " +
                        "filled; it is not known whether before or after the position was closed. Check the position of ${r.kite} in Kite."
                    else null
                if (text != null) {
                    runCatching { Diag.record("pine", text) }
                    com.optionslab.app.work.Alerts.error(text, "Pine live")
                    note(b, r.script, text)
                }
            }
            val i = b.removing.indexOfFirst { it === r0 }
            if (i < 0) continue
            if (left.isEmpty() && r.scanned) {
                b.removing.removeAt(i)
                note(b, r.script, "${r.kite}: its stop and exits at Zerodha are taken out (the position is closed)")
                continue
            }
            // (Only the scan left, put off on a stream look or while held again: not a failed try.)
            val tried = if (left.isEmpty() && (streamLook || heldAgain)) 0 else 1
            b.removing[i] = tellOnce(b, r.copy(ids = left, tries = r.tries + tried), left.keys,
                if (left.isEmpty()) "Zerodha's orders could not be read to find its exits" else null)
        }
    }

    /** [r] after a failed try: from [REMOVE_TRIES] tries on, Boss is told once to cancel [left] in Kite ([why]: what stood in the way). */
    private fun tellOnce(b: Book, r: Removing, left: Collection<String>, why: String?): Removing {
        if (r.tries < REMOVE_TRIES || r.told) return r
        val what = listOfNotNull(why, left.takeIf { it.isNotEmpty() }?.let { "order ${it.joinToString(", ")}" }).joinToString("; ")
        val text = "Pine #${r.script}: its stop on ${r.kite} could not be taken out at Zerodha after ${r.tries} tries, though the " +
            "position is closed ($what). Cancel any working sell of ${r.kite} in Kite now: if it fires it sells what is no longer " +
            "held. The app keeps trying."
        runCatching { Diag.record("pine", text) }
        com.optionslab.app.work.Alerts.error(text, "Pine live")
        note(b, r.script, text)
        return r.copy(told = true)
    }

    /** The fill of a MARKET paper order; one left open (no fresh price) is cancelled so it cannot fill later. */
    private suspend fun filledOrCancelled(r: Paper.Result): Pair<Int, Double>? {
        r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()?.let { return it.quantity to it.price }
        val oid = r.orderId ?: return null
        if (!r.ok) return null
        Paper.cancel(oid, "unfilled_market")
        val o = Paper.state.orders.firstOrNull { it.orderId == oid } ?: return null
        return if (o.status == "complete") o.quantity to (o.averagePrice?.toDouble() ?: return null) else null
    }
}
