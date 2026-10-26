package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.sandbox.SandboxEvent
import com.optionslab.engine.signals.NtfyParser
import com.optionslab.engine.signals.NtfyParser.Parsed
import com.optionslab.engine.signals.NtfyRules
import com.optionslab.engine.signals.NtfyRules.Exit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * ntfy alerts (25 Oct 2026, Boss: "add a new feature which will read message from ntfy and every message should be read only once,
 * and before buying or selling, we should read the message and buy the option of the index on the basis of the message").
 *
 * SIGNAL SOURCE: an external message on Boss's ntfy topic ([NtfyReader] reads it), traded on the index option by the strike rule.
 * NOT a researched or proven strategy ([NtfyRules.NOTE]); every screen and Jarvis say so.
 *
 * Read once ([receive]): each message's ntfy id is written to this encrypted book BEFORE anything is decided on it; an id already
 * seen is ignored after a restart, a reconnect, a duplicate delivery, an overlapping catch-up poll or an app update (the ids are
 * kept in the book, [NtfyRules.ReadOnce]). A JSON signal's own id is remembered too: the same signal posted twice is traded once.
 * Each message gets an Alerts line: its time, its words, what was decided and why.
 *
 * Deciding ([tick], the watch's pass - never the reader's thread, never the main thread): the message is read by [NtfyParser]
 * (ambiguous or contradictory text, stocks, futures, MCX, crypto: not traded, with the reason); a stale one (older than the
 * stale minutes, or from an earlier day), one outside the market (a holiday, a weekend, before 09:15, after the square-off), a
 * day at its trade limit or loss cap, an index the arm already holds, the kill switch, the bot's stop for today, the day lock and
 * the account guard all refuse a NEW trade; the order carries its fingerprint ([OrderVerdicts]) and the paper book's own
 * same-direction cap; the time machine logs it (shadow). The contract: the message's strike when today's list has it, else the
 * strike rule (ATM, 1 ITM or 1 OTM, Boss's setting) on the index's price; the nearest expiry unless the message names one.
 * A LIMIT BUY on the PAPER account (1% over the price at most), 1 lot unless the message asks for more (never over Boss's cap).
 * The stop is mandatory and rests in the paper book as a SELL SL-M (the message's, else 20% under the fill, "our stop"); the
 * target is the message's, else 40% over the fill; a JSON signal's index stop and target close it when the index crosses them;
 * square-off 15:15 (15:05 on its expiry day). "Sell / exit / close / book" closes what it holds on that index - an exit only,
 * never a short.
 *
 * Live: never from here. Each paper buy, stop move and close is told to [ArmLive] (memory only), whose live trade goes only with
 * Live, "AI trades go live", Boss's PIN and this arm's own "Trade live" switch (off until Boss turns it on).
 *
 * Fast (26 Oct, Boss: "I think ntfy messages are getting read late"): a message kept waiting is decided by a pass of its own
 * started the moment it is read ([kick]: its own coroutine, after the read's lock is released; the same [tick], so every rule
 * above applies unchanged), not at the watch's next minute; the underlyings' prices come from the live stream when fresh. Each
 * message keeps its timing (the server's time, read, decided: [Alert.readMs], [Alert.actedMs]) for the page and the diagnostics.
 *
 * Open positions first (CLAUDE.md): its book is its own encrypted file, read FIRST after a restart ([PositionsFirst]); while one is
 * open its stop, target and square-off run in the watch's minute lane and the 15-second stop check ([manageOnly]). The removed
 * Telegram calls arm's open paper trades (if any) were moved into this book at the update ([migrate]) and are managed the same way
 * until they close - never orphaned.
 */
object NtfyArm {
    private const val FILE = "ntfy_arm.vault"
    /** 25 Oct: the removed Telegram calls arm's book: its open trades move here once ([migrate]), then the file goes. */
    internal const val OLD_FILE = "signal_paper.vault"
    const val SRC = "ntfy"
    const val ALERTS_KEPT = 200
    private val lock = Mutex()
    @Volatile private var file: File? = null
    @Volatile private var oldFile: File? = null
    private val written = Vault.LastWrite()

    fun init(context: Context) {
        file = File(context.applicationContext.noBackupFilesDir, FILE)
        oldFile = File(context.applicationContext.noBackupFilesDir, OLD_FILE)
        // Its paper trades for their live path ([ArmLive]; its live switch starts off - only Boss turns it on, with the PIN).
        ArmLive.register(LiveMirror.NTFY) {
            if (cache == null) null else _view.value.let { v ->
                val today = Market.today()
                (v.open + v.closed.filter { it.day == today }).map {
                    ArmLive.PaperLeg(it.ref, LiveMirror.NTFY_ALERTS, if (it.open) (it.qty - it.soldQty).coerceAtLeast(0) else 0)
                }
            }
        }
    }

    /** One paper trade. [src] "ntfy" (or a moved trade's old chat id, [legacy]); [ref] its live path's reference. */
    data class Pos(
        val src: String, val alertId: String, val index: String, val symbol: String, val right: String, val lots: Int, val qty: Int,
        val entry: Double, val entryTime: LocalDateTime, val orderId: String?, val stop: Double, val ourStop: Boolean, val stopOrderId: String?,
        val exitRule: String, val target: Double?, val lastTarget: Double?, val expiryDay: Boolean, val halfDone: Boolean = false,
        val soldQty: Int = 0, val soldValue: Double = 0.0,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0,
        val ref: String = "$SRC:$alertId", val legacy: Boolean = false, val idxStop: Double? = null, val idxTarget: Double? = null,
        /**
         * 26 Oct (IraThinker review): the source rule's exit time ([exitAt]: "exitBy" or the hold, before the square-off), the
         * source strategy for the record ([strategy], "IraThinker mom60"), and [paperOnly] (the sender marked it "paper": true;
         * labelled only - its live path is unchanged, decided by its own switch and Boss's PIN).
         */
        val exitAt: LocalDateTime? = null, val strategy: String? = null, val paperOnly: Boolean = false,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        /** Rupees after charges, once out (a half sold at T1 included). */
        val net: Double? get() = exit?.let { x -> soldValue + x * (qty - soldQty) - entry * qty - charges }
    }

    /** What happened to one message: [PENDING] waiting for the pass; the rest are final. */
    const val PENDING = "pending"
    const val TRADED = "traded"
    const val NOT_TRADED = "not traded"
    const val CLOSED = "closed"
    /** 26 Oct (IraThinker review): information the arm cannot trade (a sale, several legs, a price alert): final, not a refusal. */
    const val INFO = "information"

    /**
     * One message on the Alerts list: its time (the server's), its words, what was decided and why. [title]/[body] kept while it
     * is pending. 26 Oct: [readMs] when the app read it and [actedMs] when a pass first decided on it (epoch ms; 0: not yet, or
     * kept before 26 Oct) - its timing ([timing]).
     */
    data class Alert(val id: String, val at: LocalDateTime, val text: String, val state: String, val why: String,
                     val title: String? = null, val body: String? = null, val readAt: LocalDateTime = at,
                     val readMs: Long = 0L, val actedMs: Long = 0L, val doneMs: Long = 0L) {
        val timing: NtfyRules.Timing get() = NtfyRules.Timing(at.atZone(com.optionslab.engine.IST).toEpochSecond(), readMs, actedMs)
        /**
         * "read 1.2s · decided 2.0s" (after the server's time), or "" for a message kept before 26 Oct. "Decided" is the first pass's
         * decision (bought, refused, or waiting for its price); when it was settled later (a buy waiting for its price), " · done Xs".
         */
        val timingWords: String get() = timing.let { t ->
            val done = if (doneMs > 0 && actedMs > 0 && doneMs - actedMs >= 1_000) NtfyRules.lag(t.sentEpochS, doneMs) else null
            listOfNotNull(t.readS?.let { "read ${NtfyRules.secs(it)}" }, t.actedS?.let { "decided ${NtfyRules.secs(it)}" },
                done?.let { "done ${NtfyRules.secs(it)}" }).joinToString(" · ")
        }
    }

    private class Book(
        var on: Boolean = false,
        var settings: NtfyRules.Settings = NtfyRules.Settings(),
        val positions: MutableList<Pos> = ArrayList(),
        val alerts: MutableList<Alert> = ArrayList(),
        var read: NtfyRules.ReadOnce = NtfyRules.ReadOnce(),
        /** JSON signals' own ids already seen (the same signal posted twice is traded once). */
        val signals: LinkedHashSet<String> = LinkedHashSet(),
        val status: StatusMap = StatusMap(),
        val log: ArrayList<String> = ArrayList(),
        /** alertId -> an entry order a cancel could not take out (settled on the next pass, never a second BUY beside it). */
        val pending: MutableMap<String, String> = HashMap(),
        /** Messages whose BUY is being placed: written before the order, so a restart in between never orders them again. */
        val ordering: MutableSet<String> = HashSet(),
    )

    data class View(val on: Boolean = false, val settings: NtfyRules.Settings = NtfyRules.Settings(), val open: List<Pos> = emptyList(),
                    val closed: List<Pos> = emptyList(), val alerts: List<Alert> = emptyList(), val status: String? = null,
                    val log: List<String> = emptyList(), val last: String? = null) {
        val pending: Boolean get() = alerts.any { it.state == PENDING }
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view
    @Volatile private var cache: Book? = null

    // ---- the book --------------------------------------------------------------------------------------------------------

    private fun posJson(p: Pos) = JSONObject().put("chat", p.src).put("call", p.alertId).put("index", p.index).put("symbol", p.symbol).put("right", p.right)
        .put("lots", p.lots).put("qty", p.qty).put("entry", p.entry).put("at", p.entryTime.toString()).put("stop", p.stop).put("ours", p.ourStop)
        .put("rule", p.exitRule).put("exp", p.expiryDay).put("half", p.halfDone).put("soldQty", p.soldQty).put("soldValue", p.soldValue).put("charges", p.charges)
        .put("ref", p.ref).put("legacy", p.legacy)
        .apply {
            p.orderId?.let { put("order", it) }; p.stopOrderId?.let { put("stopOrder", it) }; p.target?.let { put("target", it) }
            p.lastTarget?.let { put("last", it) }; p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitAt", it.toString()) }; p.why?.let { put("why", it) }
            p.idxStop?.let { put("idxStop", it) }; p.idxTarget?.let { put("idxTarget", it) }
            p.exitAt?.let { put("srcExit", it.toString()) }; p.strategy?.let { put("strat", it) }; if (p.paperOnly) put("paperOnly", true)
        }

    private fun dbl(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null

    /** A trade as written ([posJson]); the removed arm's own trades ([legacy]) had no ref: theirs is rebuilt as its live path knew it. */
    private fun posOf(o: JSONObject, legacy: Boolean = o.optBoolean("legacy")): Pos {
        val src = o.getString("chat"); val call = o.getString("call")
        return Pos(src, call, o.getString("index"), o.getString("symbol"), o.getString("right"),
            o.getInt("lots"), o.getInt("qty"), o.getDouble("entry"), LocalDateTime.parse(o.getString("at")), o.optString("order").ifEmpty { null },
            o.getDouble("stop"), o.optBoolean("ours"), o.optString("stopOrder").ifEmpty { null }, o.optString("rule", Exit.T1_ALL.key), dbl(o, "target"),
            dbl(o, "last"), o.optBoolean("exp"), o.optBoolean("half"), o.optInt("soldQty"), o.optDouble("soldValue", 0.0),
            dbl(o, "exit"), o.optString("exitAt").ifEmpty { null }?.let { LocalDateTime.parse(it) }, o.optString("why").ifEmpty { null }, o.optDouble("charges", 0.0),
            ref = o.optString("ref").ifEmpty { if (legacy) LEGACY_REF + "$src:$call" else "$SRC:$call" }, legacy = legacy,
            idxStop = dbl(o, "idxStop"), idxTarget = dbl(o, "idxTarget"),
            exitAt = o.optString("srcExit").ifEmpty { null }?.let { LocalDateTime.parse(it) }, strategy = o.optString("strat").ifEmpty { null },
            paperOnly = o.optBoolean("paperOnly"))
    }
    /** The live path's reference of the removed arm's trades (kept so a live trade it may hold still finds its paper trade). */
    private const val LEGACY_REF = "telegram:"

    private fun alertJson(a: Alert) = JSONObject().put("id", a.id).put("at", a.at.toString()).put("text", a.text).put("state", a.state).put("why", a.why)
        .put("readAt", a.readAt.toString()).put("readMs", a.readMs).put("actedMs", a.actedMs).put("doneMs", a.doneMs)
        .apply { a.title?.let { put("title", it) }; a.body?.let { put("body", it) } }

    private fun alertOf(o: JSONObject) = Alert(o.getString("id"), LocalDateTime.parse(o.getString("at")), o.optString("text"), o.optString("state", NOT_TRADED),
        o.optString("why"), o.optString("title").ifEmpty { null }, o.optString("body").ifEmpty { null },
        o.optString("readAt").ifEmpty { null }?.let { LocalDateTime.parse(it) } ?: LocalDateTime.parse(o.getString("at")),
        o.optLong("readMs", 0L), o.optLong("actedMs", 0L), o.optLong("doneMs", 0L))

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        val f = file
        val ok = f == null || runCatching {
            val o = JSONObject(String(Vault.readFileSteady(f) ?: return@runCatching, Charsets.UTF_8))
            b.on = o.optBoolean("on")
            o.optJSONObject("settings")?.let { s ->
                b.settings = NtfyRules.Settings(s.optInt("lots", 1), s.optInt("stale", 5), s.optInt("max", 3), s.optDouble("cap", 3_000.0),
                    NtfyParser.StrikeRule.of(s.optString("strike")), exit = Exit.of(s.optString("exit")), stocks = s.optBoolean("stocks", true)).clean()
            }
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) b.positions += posOf(a.getJSONObject(i)) }
            o.optJSONArray("alerts")?.let { a -> for (i in 0 until a.length()) b.alerts += alertOf(a.getJSONObject(i)) }
            val ids = o.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            b.read = NtfyRules.ReadOnce(ids, o.optString("last").ifEmpty { null })
            o.optJSONArray("signals")?.let { a -> for (i in 0 until a.length()) b.signals += a.getString(i) }
            b.status.readFrom(o, runCatching { f.lastModified() }.getOrDefault(0L))
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONObject("pending")?.let { m -> m.keys().forEach { b.pending[it] = m.getString(it) } }
            o.optJSONArray("ordering")?.let { a -> for (i in 0 until a.length()) b.ordering += a.getString(i) }
        }.isSuccess
        if (!ok && f != null && f.exists()) runCatching { Vault.setAside(f) }
        cache = b
        migrate(b)
        return b
    }

    /**
     * 25 Oct: the removed Telegram calls arm's book. Its OPEN paper trades move into this book (managed here until they close: stop,
     * target, square-off - never orphaned); then its file is deleted (written only after this book holds them). Once only: the old
     * file is gone afterwards. True when this book changed.
     */
    private fun migrate(b: Book) {
        val old = oldFile?.takeIf { it.exists() } ?: return publish(b)
        val read = runCatching {
            val o = JSONObject(String(Vault.readFileSteady(old) ?: error("unreadable"), Charsets.UTF_8))
            val a = o.optJSONArray("positions") ?: return@runCatching emptyList<Pos>()
            (0 until a.length()).map { posOf(a.getJSONObject(it), legacy = true) }.filter { it.open }
        }
        // Not read (the Keystore not ready yet, or damaged): left in place and tried again on the next start - never deleted unread.
        val moved = read.getOrElse { runCatching { Diag.record("ntfy", "the removed Telegram calls arm's book could not be read yet: kept") }; return publish(b) }
        val fresh = moved.filter { m -> b.positions.none { it.ref == m.ref } }
        b.positions += fresh
        if (fresh.isNotEmpty()) note(b, "moved ${fresh.size} open paper trade(s) of the removed Telegram calls arm here: managed until they close")
        // The old file goes only once this book (with the moved trades) is on disk.
        val kept = if (fresh.isNotEmpty()) save(b) else { publish(b); true }
        if (kept) runCatching { if (old.delete()) Diag.record("ntfy", "the removed Telegram calls arm's book deleted") }
    }

    /** The book written (true when it reached the disk). */
    private fun save(b: Book): Boolean {
        val closed = b.positions.count { !it.open }
        if (closed > 400) { var drop = closed - 400; b.positions.removeAll { !it.open && drop-- > 0 } }
        while (b.alerts.size > ALERTS_KEPT) {
            val i = b.alerts.indexOfFirst { it.state != PENDING }.takeIf { it >= 0 } ?: break
            b.alerts.removeAt(i)
        }
        while (b.signals.size > NtfyRules.KEEP_IDS) b.signals.remove(b.signals.first())
        val s = b.settings
        val o = JSONObject().put("on", b.on)
            .put("settings", JSONObject().put("lots", s.lots).put("stale", s.staleMin).put("max", s.maxPerDay).put("cap", s.lossCap)
                .put("strike", s.strikeRule.key).put("exit", s.exit.key).put("stocks", s.stocks))
            .put("positions", JSONArray(b.positions.map { posJson(it) })).put("alerts", JSONArray(b.alerts.map { alertJson(it) }))
            .put("ids", JSONArray(b.read.ids)).put("signals", JSONArray(b.signals.toList()))
            .put("log", JSONArray(b.log.takeLast(200)))
            .put("pending", JSONObject().apply { b.pending.forEach { (k, v) -> put(k, v) } })
            .put("ordering", JSONArray(b.ordering.toList()))
        b.read.last?.let { o.put("last", it) }
        b.status.writeTo(o)
        // 26 Oct: under [written]'s lock, as [wipe]: a book "Erase everything" dropped meanwhile is never written back or shown.
        return synchronized(written) {
            if (cache !== b) return false
            val ok = file?.let { f -> runCatching { written.write(f, o.toString().toByteArray(Charsets.UTF_8)) }.getOrDefault(false) } ?: false
            publish(b)
            ok
        }
    }

    private fun publish(b: Book) {
        _view.value = View(b.on, b.settings, b.positions.filter { it.open }, b.positions.filter { !it.open }.takeLast(100), b.alerts.toList().reversed(),
            b.status[SRC], b.log.takeLast(30), b.read.last)
    }

    private fun note(b: Book, text: String) {
        b.log += "${now()} $text"
        while (b.log.size > 200) b.log.removeAt(0)
        // The diary gets what was decided, never the topic or the token.
        runCatching { Diag.record("ntfy", text) }
    }

    /** Read the book (start-up's positions-first step and the page call it, off the main thread). */
    suspend fun refresh(): View { lock.withLock { book() }; return _view.value }

    /** An open paper trade is held (memory only). */
    fun holding(): Boolean = _view.value.open.isNotEmpty()

    /** The watch needs this arm's pass (memory only: no file, no network): a trade held, or a message waiting to be decided. */
    fun wantsPass(): Boolean = holding() || _view.value.pending

    private fun now(): LocalDateTime = runCatching { Market.now().toLocalDateTime().withNano(0) }.getOrElse { LocalDateTime.now().withNano(0) }

    /** 26 Oct: now in epoch milliseconds (the market's clock), for each message's timing. */
    private fun nowMs(): Long = runCatching { Market.now().toInstant().toEpochMilli() }.getOrElse { System.currentTimeMillis() }

    /** The book has been read in this process (memory only). */
    fun loaded(): Boolean = cache != null

    // ---- Boss's switches ---------------------------------------------------------------------------------------------------

    /**
     * Boss's On switch (off by default). Off buys nothing new: what it holds still closes by its own exits. [log]: from the page
     * (logged in Autopilot's change timeline as Boss's, undone in one tap); false for the timeline's own Undo.
     */
    suspend fun setOn(on: Boolean, log: Boolean = true): String {
        val said = lock.withLock {
            val b = book()
            if (b.on == on) return@withLock "ntfy alerts is already ${if (on) "on" else "off"}."
            b.on = on
            note(b, "ntfy alerts ${if (on) "on (paper; ${NtfyRules.NOTE})" else "off"}" +
                if (!on && b.positions.any { it.open }) "; what it holds still closes by its own exits" else "")
            save(b)
            if (log) runCatching { com.optionslab.app.ira.Autopilot.ntfyPaperSwitchedByBoss(on) }
            if (on) "ntfy alerts: on, on paper. ${NtfyRules.NOTE.replaceFirstChar { it.uppercase() }}." else "ntfy alerts: off. Nothing new is bought."
        }
        runCatching { NtfyReader.ensure() }
        return said
    }

    suspend fun setSettings(s: NtfyRules.Settings): String = lock.withLock {
        val b = book()
        val c = s.clean()
        if (c != b.settings) { b.settings = c; note(b, "settings: at most ${c.lots} lot(s), stale after ${c.staleMin} min, ${c.maxPerDay} a day, " +
            "₹%,.0f loss cap, strike ${c.strikeRule.label}, stock options ${if (c.stocks) "on" else "off"}".format(Locale.ENGLISH, c.lossCap)); save(b) }
        "At most ${c.lots} lot(s) a trade, ${c.maxPerDay} trades a day, messages older than ${c.staleMin} minutes not traded, strike ${c.strikeRule.label}."
    }

    fun diagLine(): String {
        val v = _view.value
        return "${NtfyRules.LABEL}: ${if (v.on) "on" else "off"} (paper; live only with its own switch) · ${NtfyReader.diagWords()} · " +
            "${v.alerts.size} messages kept, ${v.alerts.count { it.state == TRADED }} traded · open ${v.open.size} · " +
            "${v.closed.size} closed %,.0f after charges · ${NtfyRules.NOTE}".format(Locale.ENGLISH, v.closed.sumOf { it.net ?: 0.0 }) +
            // 26 Oct: the day's timing (counts and seconds only: no topic, no message words).
            "\n" + daySummary()
    }

    /** 26 Oct: today's messages' timings (the server's day; those read before 26 Oct have none). Memory only. */
    fun dayTimings(v: View = _view.value, day: LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now() }): List<NtfyRules.Timing> =
        v.alerts.filter { it.at.toLocalDate() == day && it.readMs > 0 }.map { it.timing }

    /** 26 Oct: "ntfy: N messages, read in median Xs / max Ys, acted in median Xs / max Ys" for today. Memory only. */
    fun daySummary(v: View = _view.value): String = NtfyRules.daySummary(dayTimings(v))

    /** The last [n] messages in words, newest first (Jarvis, on an unlocked phone only). */
    fun lastWords(n: Int = 3): List<String> = _view.value.alerts.take(n).map { a ->
        "%02d:%02d %s - %s".format(Locale.ENGLISH, a.at.hour, a.at.minute, a.text.take(80), a.state + if (a.why.isNotBlank()) " (${a.why})" else "")
    }

    /** Jarvis's answer (an unlocked phone only; [com.optionslab.ira.NtfyTalk]): the last messages, what is open, the record. */
    suspend fun talk(): String {
        val v = refresh()
        return com.optionslab.ira.NtfyTalk.answer(v.on, lastWords(3), v.open.map { "${it.symbol} from ${NtfyParser.num(it.entry)}" },
            v.closed.sumOf { it.net ?: 0.0 }, v.closed.size)
    }

    // ---- reading once ------------------------------------------------------------------------------------------------------

    /**
     * Messages from the reader ([NtfyReader]; its own background coroutine). Each id is marked read and written to this book
     * BEFORE anything is decided; a seen id is skipped. Keepalive and open events are not messages. A message is checked for the
     * prefix, read by the parser and checked for staleness here; what may trade waits as [PENDING] for the watch's pass. Returns
     * how many were new.
     */
    suspend fun receive(events: List<NtfyRules.Event>, prefix: String = ""): Int {
        val (fresh, waiting) = receiveLocked(events, prefix)
        // 26 Oct (Boss: "ntfy messages are getting read late"): a message kept waiting (a buy or an exit) is decided at once by a
        // pass of its own, after this lock is released - not at the watch's next minute.
        if (waiting) kick()
        return fresh
    }

    private suspend fun receiveLocked(events: List<NtfyRules.Event>, prefix: String): Pair<Int, Boolean> = lock.withLock {
        val b = book()
        var fresh = 0
        val now = now()
        val readMs = nowMs()
        // 25 Oct (review): outside the reader's hours (a trading day, 09:00 to the F&O close) nothing read becomes pending.
        val outside = runCatching { NtfyRules.outsideHours(now, Market.isTradingDay(now.toLocalDate()), com.optionslab.engine.NseHours.foClose(now.toLocalDate())) }
            .getOrDefault("outside market hours, not traded: the market's day could not be read")
        for (e in events) {
            if (!e.isMessage || !b.read.mark(e.id)) continue
            fresh++
            val words = NtfyParser.text(e.title, e.message).replace(Regex("\\s+"), " ").trim().take(300)
            fun keep(state: String, why: String, title: String? = null, body: String? = null) {
                val a = Alert(e.id, e.at(), words, state, why, title, body, now, readMs, if (state != PENDING) readMs else 0L,
                    if (state != PENDING) readMs else 0L)
                b.alerts += a
                if (state != PENDING) note(b, "message ${e.id}: $state - $why${timed(a)}")
            }
            val body = e.message?.let { NtfyRules.afterPrefix(it, prefix) }
            val title = e.title?.let { NtfyRules.afterPrefix(it, prefix) }
            if (prefix.isNotBlank() && body == null && title == null) { keep(NOT_TRADED, "does not start with your prefix"); continue }
            if (outside != null) { keep(NOT_TRADED, outside); continue }
            // What is kept for the pass is exactly what was read here (the prefix taken off): the pass reads it the same way.
            val t0 = title ?: e.title; val b0 = body ?: e.message
            val p = NtfyParser.read(t0, b0, now.toLocalDate())
            // A signal's own time and the server's: the EARLIER counts (an old signal posted late is stale).
            val sent = (p as? Parsed.Buy)?.sentAt?.let { minOf(it, e.at()) } ?: e.at()
            val sid = ((p as? Parsed.Buy)?.signalId?.let { "$it|buy" }) ?: (p as? Parsed.Exit)?.signalId?.let { "$it|exit" }
            when {
                p is Parsed.Refused -> keep(NOT_TRADED, p.reason)
                // 26 Oct (IraThinker review): a sale, several legs, a price alert: information, read once, never traded.
                p is Parsed.Info -> keep(INFO, p.reason)
                sid != null && !b.signals.add(sid) -> keep(NOT_TRADED, "the same signal id was read before - acted on once only")
                // An exit only lowers risk: honoured the same day whatever the switch or its age.
                p is Parsed.Exit -> if (sent.toLocalDate() == now.toLocalDate()) keep(PENDING, "read: ${p.line()}", t0?.take(500), b0?.take(4_000))
                    else keep(NOT_TRADED, "stale, not acted on: an exit from an earlier day")
                !b.on -> keep(NOT_TRADED, "read; ntfy alerts is off")
                else -> NtfyRules.stale(sent, now, b.settings.staleMin)?.let { keep(NOT_TRADED, it) }
                    ?: keep(PENDING, "read: ${(p as? Parsed.Buy)?.line()}", t0?.take(500), b0?.take(4_000))
            }
        }
        if (fresh > 0 && !save(b)) {
            // Read once means written before acting: what could not be written is never acted on.
            for (i in b.alerts.indices) if (b.alerts[i].state == PENDING && b.alerts[i].id !in placing)
                b.alerts[i] = b.alerts[i].copy(state = NOT_TRADED, why = "could not be saved, so not acted on", title = null, body = null)
            note(b, "a message could not be saved: not acted on")
            publish(b)
        }
        fresh to (fresh > 0 && b.alerts.any { it.state == PENDING })
    }

    /** " (read 1.2s · decided 2.0s after it was sent)" for the diary's line, or "". Seconds only. */
    private fun timed(a: Alert): String = a.timingWords.takeIf { it.isNotEmpty() }?.let { " ($it after it was sent)" } ?: ""

    // ---- the quick pass (26 Oct) -------------------------------------------------------------------------------------------

    private val kickScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Kicks waiting for the quick pass: any number while one runs makes ONE more pass (conflated). */
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var kicker: Job? = null
    /** TEST SEAM: the quick pass off (the unit tests drive [tick] themselves); TestApp sets it, a test may clear it. */
    @Volatile internal var testNoKick = false
    /** Quick passes run in this process (memory; the diagnostics and the tests). */
    @Volatile var kickedPasses = 0
        private set

    /**
     * 26 Oct: a pass now, for a message just read ([receive]). Its own coroutine on the IO pool (never the reader's, the main
     * thread, the order or the tick path); kicks while one runs are coalesced into one more pass. It is [tick] itself - every rule,
     * guard, the kill switch, the day lock, the loss limits and the paper/live rules apply unchanged - and passes never overlap
     * ([passLock]). Returns at once.
     */
    fun kick() {
        if (com.optionslab.app.BuildConfig.GOLD || testNoKick) return
        synchronized(kicks) {
            if (kicker?.isActive != true) kicker = kickScope.launch {
                for (k in kicks) {
                    if (testNoKick || !_view.value.pending) continue
                    kickedPasses++
                    try { tick() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                        runCatching { Diag.record("ntfy", "quick pass failed: ${e.javaClass.simpleName}") }
                    }
                }
            }
        }
        kicks.trySend(Unit)
    }

    // ---- the pass ----------------------------------------------------------------------------------------------------------

    /** The minute lane and the 15-second stop check (open positions first): only the open trades' exits. Nothing held: returns at once. */
    suspend fun manageOnly() {
        if (com.optionslab.app.BuildConfig.GOLD || !holding()) return
        lock.withLock {
            val b = book()
            val changed = runCatching { exits(b, now(), indexLevels = false) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true }
            if (changed) save(b) else publish(b)
            Unit
        }
    }

    /**
     * 26 Oct: one pass at a time (the watch's, the bar-close check's and the quick one [kick]): the prices read before the lock
     * ([prefetch]) belong to the pass that read them. Never taken by [manageOnly]: the stop checks never wait on a pass's reads.
     */
    private val passLock = Mutex()

    /** The watch's pass: the open trades' exits first, then each waiting message. */
    suspend fun tick() {
        if (com.optionslab.app.BuildConfig.GOLD) return
        passLock.withLock { pass() }
    }

    private suspend fun pass() {
        val now = now()
        if (cache == null) lock.withLock { book() }
        // The prices this pass needs (the underlyings of held trades with their own levels, and of waiting buys), read BEFORE the
        // lock: the 15-second stop check never waits behind a network read.
        spotsNow = prefetch()
        lock.withLock {
            val b = book()
            var changed = runCatching { exits(b, now, indexLevels = true) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true }
            for (a in b.alerts.filter { it.state == PENDING }) {
                changed = runCatching { decide(b, a, now) }.getOrElse {
                    finish(b, a, NOT_TRADED, "error: ${it.javaClass.simpleName}"); true
                } || changed
                // 26 Oct: when a pass first decided on it (bought, refused, or left waiting for its price).
                changed = looked(b, a.id) || changed
            }
            if (changed) save(b) else publish(b)
            Unit
        }
    }

    /** This run's own orders being placed (memory): one in [Book.ordering] but not here was interrupted by a restart. */
    private val placing: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** 26 Oct: the first time a pass decided on message [id] (memory; saved with the book). True when it changed. */
    private fun looked(b: Book, id: String): Boolean {
        val i = b.alerts.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return false
        if (b.alerts[i].actedMs > 0L) return false
        b.alerts[i] = b.alerts[i].copy(actedMs = nowMs())
        return true
    }

    private fun finish(b: Book, a: Alert, state: String, why: String): Boolean {
        b.ordering.remove(a.id); placing.remove(a.id)
        val i = b.alerts.indexOfFirst { it.id == a.id }
        val doneAt = nowMs()
        val acted = (if (i >= 0) b.alerts[i].actedMs else a.actedMs).takeIf { it > 0L } ?: doneAt
        val done = a.copy(state = state, why = why, title = null, body = null, actedMs = acted, doneMs = doneAt)
        if (i >= 0) b.alerts[i] = done
        b.pending.remove(a.id)
        note(b, "message ${a.id}: $state - $why${timed(done)}")
        b.status[SRC] = why
        return true
    }

    private fun say(b: Book, why: String): Boolean {
        if (b.status.sameToday(SRC, why)) return false
        b.status[SRC] = why
        return true
    }

    private suspend fun decide(b: Book, a: Alert, now: LocalDateTime): Boolean {
        // Its BUY was being placed when the app stopped: never placed again (a fill is in the paper book, kept by the orphan watch).
        if (a.id in b.ordering && a.id !in placing && a.id !in b.pending)
            return finish(b, a, NOT_TRADED, "interrupted while its order was placed - not ordered again (check Paper for a fill)")
        val p = NtfyParser.read(a.title, a.body, a.at.toLocalDate())
        return when (p) {
            is Parsed.Refused -> finish(b, a, NOT_TRADED, p.reason)
            is Parsed.Info -> finish(b, a, INFO, p.reason)
            is Parsed.Exit -> exitMessage(b, a, p, now)
            is Parsed.Buy -> entry(b, a, p, now)
        }
    }

    /** "Sell / exit / close / book" [p]: what this arm holds on that index is sold at market - reduce-only, never a short. */
    private suspend fun exitMessage(b: Book, a: Alert, p: Parsed.Exit, now: LocalDateTime): Boolean {
        // 25 Oct (review): every buy on that index (and right, when named) still WAITING ends first - its working order cancelled - so
        // a buy read before the exit never fills after it ("NIFTY 24500 CE @ 120" pending, then "exit nifty", then the buy filled).
        val at = b.alerts.indexOfFirst { it.id == a.id }
        var cancelled = 0
        // Review 2: anything ended or recorded below is saved even when the exit itself waits for the next pass.
        var changed = false
        for (w in b.alerts.filterIndexed { i, x -> x.state == PENDING && x.id != a.id && (at < 0 || i < at) }) {
            val buy = runCatching { NtfyParser.read(w.title, w.body, w.at.toLocalDate()) }.getOrNull() as? Parsed.Buy ?: continue
            if (buy.index != p.index || (p.right != null && buy.right != p.right)) continue
            val id = b.pending[w.id]
            if (id != null) {
                runCatching { Paper.cancel(id, "exit message") }
                val o = Paper.state.orders.firstOrNull { it.orderId == id }
                when {
                    o == null || o.status == "cancelled" || o.status == "rejected" -> Unit
                    // Filled before the cancel: it is held now, recorded, and the exit below sells it.
                    o.status == "complete" -> { b.pending.remove(w.id); changed = true; Paper.contractOf(o.symbol)?.let { c ->
                        bought(b, w, buy, c, id, o.quantity, o.averagePrice?.toDouble() ?: return@let, now) }; continue }
                    // Still working: the exit waits for the next pass (never a fill after an exit, never a sale before it).
                    else -> return say(b, "an exit message: a waiting ${buy.index} buy could not be cancelled yet; tried again") || changed
                }
            }
            finish(b, w, NOT_TRADED, "cancelled by a later exit")
            cancelled++; changed = true
        }
        val ended = if (cancelled > 0) "cancelled $cancelled waiting buy(s); " else ""
        val mine = b.positions.withIndex().filter { (_, x) -> x.open && !x.legacy && x.index == p.index && (p.right == null || x.right == p.right?.name) }
        if (mine.isEmpty()) return finish(b, a, if (cancelled > 0) CLOSED else NOT_TRADED, ended + "nothing held on ${p.index}${p.right?.let { " ${it.name}" } ?: ""} to close - no order (never a short)")
        var sold = 0
        for ((i, x) in mine) {
            val c = Paper.contractOf(x.symbol) ?: continue
            val next = sellRest(x, c, "exit message")
            runCatching { ArmLive.level(next.ref, next.stop) }
            if (!next.open) { runCatching { ArmLive.closed(next.ref, next.why ?: "exit") }; sold++ }
            if (next != x) changed = true
            b.positions[i] = next
        }
        return if (sold == mine.size) finish(b, a, CLOSED, ended + "closed ${mine.joinToString { it.value.symbol }} (exit message) · paper")
        else say(b, "an exit message: the sale is tried again on the next pass") || changed
    }

    private suspend fun stoppedToday(): Boolean =
        runCatching { Strategies.stoppedToday() }.getOrDefault(true) || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /**
     * The highest and lowest traded price of [c] since [from] (the option's own minutes), or nulls. 25 Oct (review): never a network
     * read here (this runs under the lock, and the 15-second stop check waits on it): the minutes come from [prefetch], read before
     * the lock.
     */
    private fun rangeSince(c: Paper.Contract, from: LocalDateTime): Pair<Double?, Double?> {
        val since = from.withSecond(0).atZone(com.optionslab.engine.IST).toEpochSecond()
        val bars = barsNow[c.feedKey].orEmpty().filter { it.epochSecond >= since }
        return bars.maxOfOrNull { it.high } to bars.minOfOrNull { it.low }
    }

    /** The waiting buys' options' minutes ("near" / "above" only) and quotes, read for this pass before the lock ([prefetch]). */
    @Volatile private var barsNow: Map<String, List<Upstox.Bar>> = emptyMap()
    @Volatile private var quotesNow: Map<String, com.optionslab.engine.sandbox.Quote> = emptyMap()

    /** The minutes of [key] (the quick read: 5 s connect, one retry). Network: only ever called OFF the lock ([prefetch]). */
    private suspend fun bars(key: String): List<Upstox.Bar> = Net.intradayQuick(key)

    /**
     * The contract buy [p] would take on [today] (the message's expiry and strike, else the strike rule at [spot]), or null when
     * anything is missing. Memory and the phone's saved lists only - the same choice [entry] makes, with its reasons.
     */
    private fun contractFor(p: Parsed.Buy, today: LocalDate, s: NtfyRules.Settings, spot: Double?): Paper.Contract? {
        val list = listed(p.index, p.stock).takeIf { it.isNotEmpty() } ?: return null
        val exp = NtfyRules.expiryFor(list.map { it.expiry }, today, p.expiry) ?: return null
        val strikes = list.filter { it.expiry == exp && it.right == p.right }.map { it.strike }
        if (p.strike != null && p.strike !in strikes) return null
        val strike = p.strike ?: NtfyRules.strikeFor(strikes, spot ?: return null, p.right, p.pick ?: s.strikeRule) ?: return null
        return if (p.stock) list.firstOrNull { it.expiry == exp && it.right == p.right && it.strike == strike }?.let { Paper.stockContract(it) }
            else Paper.ticketContract(p.index, exp, strike, p.right)
    }

    /** TEST SEAM: the listed contracts and the index price (JVM tests only; null in the app). */
    @Volatile internal var testListed: ((String) -> List<Upstox.Contract>)? = null
    @Volatile internal var testSpot: ((String) -> Double?)? = null

    /** The index's (or an F&O stock's) listed options from the lists saved on the phone today: never a download in the pass (as FT3). */
    private fun listed(u: String, stock: Boolean = false): List<Upstox.Contract> {
        testListed?.let { return it(u) }
        if (stock) return StockOptions.listed(u).also { if (it.isEmpty() && !StockOptions.ready()) StockOptions.refreshInBackground() }
        val bfo = u == Paper.BFO_UNDERLYING
        val saved = runCatching { if (bfo) Market.cachedBfoContracts() else Market.cachedContracts() }.getOrNull()
        if (saved == null || saved.first != Market.today()) {
            runCatching { if (bfo) Market.refreshBfoInBackground() else Market.refreshContractsInBackground() }
            return emptyList()
        }
        return saved.second.filter { it.underlying == u }
    }

    /** The underlyings' prices read for this pass, before the lock ([prefetch]): the last finished minute (held trades' index levels). */
    @Volatile private var spotsNow: Map<String, Double> = emptyMap()
    /** 26 Oct: the waiting buys' underlyings' prices for the strike rule (the live stream's when fresh), read before the lock. */
    @Volatile private var entrySpotsNow: Map<String, Double> = emptyMap()

    /** The underlyings this pass may need, and their prices; stock lists warmed in memory. Off the lock; never throws. */
    private suspend fun prefetch(): Map<String, Double> {
        val v = _view.value
        // 26 Oct (review): two sets of prices. A held trade's index stop and target are judged on the last finished minute, as
        // before (its exit rule unchanged); a waiting buy's strike is chosen on the live stream's price when fresh (faster).
        val levels = LinkedHashSet<String>(); val buys = LinkedHashSet<String>()
        v.open.filter { it.idxStop != null || it.idxTarget != null }.forEach { levels += it.index }
        for (a in v.alerts) if (a.state == PENDING) (runCatching { NtfyParser.read(a.title, a.body, a.at.toLocalDate()) }.getOrNull() as? Parsed.Buy)?.let { p ->
            if (p.strike == null || p.stock) buys += p.index
            if (p.stock) runCatching { StockOptions.listed(p.index) }
        }
        val out = HashMap<String, Double>(); val entryOut = HashMap<String, Double>()
        for (u in levels) runCatching { spot(u, stream = false) }.getOrNull()?.let { out[u] = it }
        for (u in buys) runCatching { spot(u, stream = true) }.getOrNull()?.let { entryOut[u] = it }
        entrySpotsNow = entryOut
        // 25 Oct (review): each waiting buy's option quote (and, for "near" / "above", its minutes) read HERE, never under the lock:
        // the 15-second stop check never waits behind a network read.
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        val quotes = HashMap<String, com.optionslab.engine.sandbox.Quote>(); val bars = HashMap<String, List<Upstox.Bar>>()
        for (a in v.alerts) if (a.state == PENDING) (runCatching { NtfyParser.read(a.title, a.body, a.at.toLocalDate()) }.getOrNull() as? Parsed.Buy)?.let { p ->
            val c = runCatching { contractFor(p, today, v.settings, entryOut[p.index]) }.getOrNull() ?: return@let
            if (c.feedKey !in quotes) runCatching { Paper.quote(c) }.getOrNull()?.let { quotes[c.feedKey] = it }
            if ((p.trig == NtfyParser.Trig.ZONE || p.trig == NtfyParser.Trig.ABOVE) && c.feedKey !in bars) runCatching { bars(c.feedKey) }.getOrNull()?.let { bars[c.feedKey] = it }
        }
        quotesNow = quotes; barsNow = bars
        return out
    }

    /**
     * The index's (or the stock's) last price: with [stream], the live stream's when fresh (26 Oct: the strike rule of a quick
     * pass, with no network read), else its last finished minute (the quick read: 5 s connect, one retry), or null.
     */
    private suspend fun spot(u: String, stream: Boolean): Double? {
        testSpot?.let { return it(u) }
        if (stream) streamSpot(u)?.let { return it }
        val key = Ft3Arm.indexKey(u) ?: StockOptions.eqKey(u) ?: return null
        return runCatching { bars(key) }.getOrNull().orEmpty().filter { it.istDate == Market.today() }
            .maxByOrNull { it.epochSecond }?.close?.takeIf { it > 0 }
    }

    /** 26 Oct: [u]'s price from Zerodha's live stream (memory; at most 5 s old), or null. Never the network. */
    private fun streamSpot(u: String): Double? = runCatching {
        val token = KiteStream.indexToken(u) ?: return@runCatching null
        KiteStream.touch(listOf(token))
        KiteStream.tick(token)?.last?.takeIf { it > 0 }
    }.getOrNull()

    /** One buy message [p] at [now]. True when the book changed. */
    private suspend fun entry(b: Book, a: Alert, p: Parsed.Buy, now: LocalDateTime): Boolean {
        val s = b.settings
        fun done(why: String) = finish(b, a, NOT_TRADED, why)
        // An entry order a cancel could not take out: settled first, never a second BUY beside it.
        b.pending[a.id]?.let { id ->
            val o = Paper.state.orders.firstOrNull { it.orderId == id }
            return when {
                o == null || o.status == "cancelled" || o.status == "rejected" -> { b.pending.remove(a.id); true }
                o.status == "complete" -> { b.pending.remove(a.id); val c = Paper.contractOf(o.symbol) ?: return true
                    bought(b, a, p, c, id, o.quantity, o.averagePrice?.toDouble() ?: return true, now) }
                else -> { runCatching { Paper.cancel(id, "unfilled_limit") }; false }
            }
        }
        if (!b.on) return done("ntfy alerts was switched off before it was traded")
        // Another message's entry order still working: settled first, never a second BUY beside it.
        if (b.pending.keys.any { it != a.id }) return say(b, "an entry order is still working; settled before anything new")
        val sent = p.sentAt?.let { minOf(it, a.at) } ?: a.at
        val tradingDay = runCatching { Market.isTradingDay(now.toLocalDate()) }.getOrDefault(false)
        NtfyRules.marketRefusal(now, tradingDay, false)?.let { return done("not traded: $it") }
        // 25 Oct (review): a message sent before the open, or stale by now, is never bought (an 08:00 message was bought at 09:15).
        NtfyRules.entryRefusal(sent, now, s.staleMin)?.let { return done(it) }
        if (NtfyRules.phase(sent, now, s, false) == NtfyRules.Phase.LATE)
            return done(NtfyRules.stale(sent, now, s.staleMin) ?: "not filled within ${s.staleMin} minutes - no order")
        // 26 Oct (IraThinker review): the source rule's own exit time already passed: buying now could only be sold at once.
        p.exitBy?.let { if (!now.isBefore(it)) return done("not traded: the source rule's exit time (%02d:%02d) has passed".format(Locale.ENGLISH, it.hour, it.minute)) }
        val today = now.toLocalDate()
        val mine = b.positions.filter { !it.legacy && it.day == today }
        NtfyRules.refusal(mine.size + b.pending.size, mine.sumOf { it.net ?: 0.0 }, s)?.let { return done("not traded: $it") }
        if (b.positions.any { it.open && it.index == p.index }) return done("not traded: ntfy alerts already holds a ${p.index} trade")
        // The kill switch, the bot's stop for today, the day lock and the guard: nothing bypasses them.
        if (runCatching { AppSettings.load().guardKill }.getOrDefault(true)) return done("refused: the kill switch is on")
        if (stoppedToday()) return done("refused: the bot is stopped for today")
        runCatching { DayLockGuard.refusal(false) }.getOrNull()?.let { return done("refused: $it") }
        if (p.stock && !s.stocks) return done("not traded: stock options are switched off (Settings → Automation → ntfy alerts)")
        // The contract: the message's expiry (else the nearest), its strike when listed (else the strike rule on the index's price).
        val list = listed(p.index, p.stock)
        if (list.isEmpty()) return if (p.stock && StockOptions.ready()) done("not traded: ${p.index} has no listed options today (not an F&O stock)")
            else say(b, "waiting for today's ${p.index} options list")
        val exp = NtfyRules.expiryFor(list.map { it.expiry }, today, p.expiry) ?: return done("not traded: the expiry asked for is not listed today")
        val right = p.right
        val strikes = list.filter { it.expiry == exp && it.right == right }.map { it.strike }
        // A named strike must be listed: never another strike by a rule in its place (strict).
        if (p.strike != null && p.strike !in strikes) return done(
            // 26 Oct (IraThinker review, INFY 1130 CE): a stock's strike off the exchange's grid says so, and whether the sender guessed it.
            if (p.stock) NtfyRules.offGrid(p.index, p.strike, right, exp, strikes, p.strikeGuess)
            else "not traded: ${p.index} ${p.strike?.let { NtfyParser.num(it) }} ${right.name} is not listed for $exp")
        val strike = p.strike ?: run {
            val px = entrySpotsNow[p.index] ?: return say(b, "waiting for ${p.index}'s price")
            NtfyRules.strikeFor(strikes, px, right, p.pick ?: s.strikeRule) ?: return done("not traded: no ${p.index} strike for the rule")
        }
        val expiryDay = exp == today
        NtfyRules.marketRefusal(now, tradingDay, expiryDay)?.let { return done("not traded: $it") }
        val c = (if (p.stock) list.firstOrNull { it.expiry == exp && it.right == right && it.strike == strike }?.let { Paper.stockContract(it) }
            else Paper.ticketContract(p.index, exp, strike, right)) ?: return done("not traded: ${p.index} ${NtfyParser.num(strike)} ${right.name} is not in the phone's options list")
        if (c.lotSize <= 0) return done("not traded: lot size unknown")
        if (b.positions.any { it.open && it.symbol == c.symbol } || Paper.state.positions.any { it.symbol == c.symbol && it.quantity != 0 })
            return done("not traded: ${c.symbol} is already held - never two positions on one contract")
        // 25 Oct (review): the quote read before the lock ([prefetch]); never a network read here.
        val q = quotesNow[c.feedKey] ?: return say(b, "waiting for a fresh price of ${c.symbol}")
        val ltp = runCatching { Paper.bookNow(c)?.last }.getOrNull()?.takeIf { it > 0 } ?: q?.ltp?.takeIf { it > 0 }
        val (hi, lo) = if (p.trig == NtfyParser.Trig.ZONE || p.trig == NtfyParser.Trig.ABOVE) rangeSince(c, maxOf(sent, today.atTime(9, 15))) else null to null
        if (!NtfyRules.triggered(p.trig, p.entry, ltp, hi, lo))
            return say(b, "${c.symbol}: waiting for ${p.entry?.let { "${p.trig.words} ${NtfyParser.num(it)}" } ?: "a price"} (last ${ltp?.let { NtfyParser.num(it) } ?: "?"})")
        val px = ltp ?: return say(b, "waiting for a fresh price of ${c.symbol}")
        if (p.stop?.let { px <= it } == true) return done("not traded: ${c.symbol} at ${NtfyParser.num(px)} is already at its stop")
        if (p.srcStop?.let { px <= it } == true) return done("not traded: ${c.symbol} at ${NtfyParser.num(px)} is already at the source rule's stop")
        val lots = NtfyRules.lotsFor(p.lots, s)
        val limit = NtfyRules.round2(NtfyRules.tickDown(px * 1.01) + 0.05)
        // 26 Oct: IraAlgo's own exits, made tighter or earlier (never wider) by the source rule's ([NtfyRules.sourceExits]).
        val ex0 = NtfyRules.sourceExits(p, px, now, today.atTime(NtfyRules.squareOff(expiryDay)), s)
        val stop0 = ex0.stop; val target0 = ex0.target
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", lots, limit), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return done("refused by the account guard: " + refusals.joinToString(" "))
        // The guard's verdict stamped with this order's fingerprint, its stop and target with it ([OrderVerdicts]).
        val verdict = OrderVerdicts.stamp(OrderVerdicts.paperFields(c, "BUY", lots, "LIMIT", "MIS", limit, null, today, NtfyRules.OWNER,
            stop = stop0, target = target0), refusals, "paper guard")
        // Written before the order (read once): a restart between the order and the book's save never orders it again.
        b.ordering += a.id; placing += a.id
        if (!save(b)) { b.ordering.remove(a.id); placing.remove(a.id); return done("not traded: the book could not be saved before the order") }
        // A LIMIT BUY on the PAPER account (1% above the price at most); not filled at once, cancelled and tried next pass.
        val res = Paper.place(c, "BUY", lots, "LIMIT", "MIS", limit, null, known = q, auto = NtfyRules.OWNER, verdict = verdict, stop = stop0, target = target0)
        val fill = res.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill != null) return bought(b, a, p, c, res.orderId, fill.quantity, fill.price, now)
        val id = res.orderId?.takeIf { res.ok } ?: return say(b, "limit ${NtfyParser.num(limit)} for ${c.symbol} refused (${res.message}); tried again")
        runCatching { Paper.cancel(id, "unfilled_limit") }
        val o = Paper.state.orders.firstOrNull { it.orderId == id }
        return when {
            o == null || o.status == "cancelled" || o.status == "rejected" -> say(b, "limit ${NtfyParser.num(limit)} for ${c.symbol} not filled; tried again")
            o.status == "complete" -> bought(b, a, p, c, id, o.quantity, o.averagePrice?.toDouble() ?: limit, now)
            else -> { b.pending[a.id] = id; say(b, "an entry order is still working; settled before anything new") }
        }
    }

    /** [qty] of [c] bought at [price] for message [a]: its stop resting as a paper SELL SL-M. */
    private suspend fun bought(b: Book, a: Alert, p: Parsed.Buy, c: Paper.Contract, orderId: String?, qty: Int, price: Double, now: LocalDateTime): Boolean {
        val s = b.settings
        // 26 Oct (IraThinker review): the source strategy rides on the order's owner tag ("ntfy alerts (paper) · IraThinker mom60").
        val owner = NtfyRules.OWNER + (p.strategy?.let { " · $it" } ?: "")
        orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "$owner · entry") } }
        val expiryDay = c.expiry == now.toLocalDate()
        // IraAlgo's own stop, target and square-off, made tighter or earlier (never wider) by the source rule's exits.
        val ex = NtfyRules.sourceExits(p, price, now, now.toLocalDate().atTime(NtfyRules.squareOff(expiryDay)), s)
        val stop = ex.stop; val ours = ex.ours; val target = ex.target
        val lots = (qty / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
        // 25 Oct (review): the fill's price goes with it, so placing the stop never reads a quote under the lock.
        val stopId = runCatching { Paper.place(c, "SELL", lots, "SL-M", "MIS", null, stop, known = com.optionslab.engine.sandbox.Quote(price)) }.getOrNull()?.takeIf { it.ok }?.orderId
            ?.also { runCatching { Strategies.tagOwner("paper:$it", "$owner · stop") } }
        val pos = Pos(SRC, a.id, p.index, c.symbol, p.right.name, lots, qty, price, now, orderId, stop, ours, stopId, Exit.T1_ALL.key,
            target, target, expiryDay, charges = chargesOf(orderId), idxStop = p.indexStop, idxTarget = p.indexTarget,
            exitAt = ex.exitAt, strategy = p.strategy, paperOnly = p.paper)
        b.positions += pos
        // The time machine's reality check (shadow only; background): the stop and target as premium, out by 15:15.
        runCatching { TimeMachineHost.afterEntry(TimeMachineHost.Entry(LiveMirror.NTFY_ALERTS, NtfyRules.OWNER, pos.ref, p.index,
            if (p.right == Right.CE) 1 else -1, now, (price - stop).takeIf { it > 0 }, (target - price).takeIf { it > 0 }, premium = true,
            exitAt = ex.exitAt?.toLocalTime()?.takeIf { it.isBefore(NtfyRules.SQ) } ?: NtfyRules.SQ)) }
        // Its live path ([ArmLive], off until Boss switches it on): the same option and lots, a LIMIT 1% over the fill at most, the stop resting.
        // 26 Oct (review): a signal marked "paper": true is labelled paper only but still told to the live path as before (whose
        // own switch and Boss's PIN decide): skipping it would be a new live skip, which CLAUDE.md says must start in shadow.
        runCatching { ArmLive.entered(ArmLive.Ask(LiveMirror.NTFY_ALERTS, pos.ref, c, 1, qty, price, stop = stop, limit = price * 1.01)) }
        return finish(b, a, TRADED, "bought ${c.symbol} $qty @ ${NtfyParser.num(price)} · paper · stop ${NtfyParser.num(stop)}${if (ours) " (our stop)" else ""}, " +
            "target ${NtfyParser.num(target)}" + (p.from?.let { " · $it" } ?: "") + strategyWords(p) + (if (ex.notes.isEmpty()) "" else " · " + ex.notes.joinToString("; ")))
    }

    /**
     * 26 Oct (IraThinker review): " · IraThinker mom60 (a paper signal; not a proven strategy - research R110: about breakeven after
     * costs on real options)" for a source strategy; the sender's estimated strike said too. "" for a plain message.
     */
    internal fun strategyWords(p: Parsed.Buy): String {
        val label = p.strategy?.let { st ->
            val r110 = if (st.contains("mom60", ignoreCase = true)) "; research R110: about breakeven after costs on real options" else ""
            " · $st (" + (if (p.paper) "a paper signal; " else "") + "not a proven strategy$r110)"
        } ?: if (p.paper) " · a paper signal" else ""
        return label + if (p.strikeGuess) " · strike estimated by the sender" else ""
    }

    /** Each open trade's exit, when due ([indexLevels]: also a signal's index stop and target, read from the index's price). */
    private suspend fun exits(b: Book, now: LocalDateTime, indexLevels: Boolean): Boolean {
        var changed = false
        for (i in b.positions.indices) {
            val p = b.positions[i]
            if (!p.open) continue
            val lvl = if (indexLevels && (p.idxStop != null || p.idxTarget != null))
                NtfyRules.indexExit(Right.valueOf(p.right), spotsNow[p.index], p.idxStop, p.idxTarget) else null
            val next = runCatching { if (lvl != null) Paper.contractOf(p.symbol)?.let { sellRest(p, it, lvl) } ?: p else exitOne(p, now) }.getOrNull() ?: continue
            // Its stop (moved to cost at T1) for its live trade, and its close ([ArmLive]; memory only).
            runCatching { ArmLive.level(next.ref, next.stop) }
            if (!next.open) runCatching { ArmLive.closed(next.ref, next.why ?: "exit") }
            if (next != p) {
                b.positions[i] = next; changed = true
                if (!next.open) note(b, "sold ${p.symbol} (${next.why}): %s after charges · paper".format(Locale.ENGLISH, rs(next.net ?: 0.0)))
                else if (next.halfDone && !p.halfDone) note(b, "${p.symbol}: T1 reached - ${if (next.soldQty > 0) "half sold, " else ""}stop moved to cost")
            }
        }
        return changed
    }

    private fun rs(x: Double) = (if (x < 0) "-₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    private suspend fun exitOne(start: Pos, now: LocalDateTime): Pos {
        var p = start
        val c = Paper.contractOf(p.symbol) ?: return p
        // Its resting stop filled in the paper book: that is the exit.
        p.stopOrderId?.let { id ->
            val o = Paper.state.orders.firstOrNull { it.orderId == id }
            if (o != null && o.status == "complete") return closed(p, o.averagePrice?.toDouble() ?: p.stop, o.updateTimestamp,
                if (p.ourStop && !p.halfDone) "our stop" else if (p.halfDone) "stop at cost" else "stop", chargesOf(id))
        }
        // Gone from the paper book without this arm selling it (the paper square-off, the loss limit, or Boss).
        val held = Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity }
        if (held <= 0) {
            p.stopOrderId?.let { runCatching { Paper.cancel(it, "position_closed") } }
            val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
            return closed(p, t?.price?.toDouble() ?: p.entry, t?.timestamp ?: now, "closed_elsewhere", t?.charges?.toDouble() ?: 0.0)
        }
        val sq = p.day.atTime(NtfyRules.squareOff(p.expiryDay))
        if (!now.isBefore(sq) || p.day.isBefore(now.toLocalDate())) return sellRest(p, c, "square-off")
        // 26 Oct (IraThinker review): the source rule's hold is over (mom60: 60 minutes) - out at full cadence, like the stop.
        p.exitAt?.let { if (!now.isBefore(it)) return sellRest(p, c, "time exit (the source rule's hold)") }
        val ltp = runCatching { Paper.bookNow(c)?.last }.getOrNull()?.takeIf { it > 0 } ?: runCatching { Paper.quote(c)?.ltp }.getOrNull() ?: return p
        // No stop resting in the paper book: the arm checks the stop itself - through it, sold now - and puts the SL-M back while
        // above it; never a position riding to the square-off without a stop.
        if (!stopResting(p.stopOrderId)) {
            if (ltp <= p.stop) return sellRest(p, c, if (p.halfDone) "stop at cost" else if (p.ourStop) "our stop" else "stop")
            val lots = ((p.qty - p.soldQty) / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
            // Not while a sale already works for the symbol: a stop put in beside it could open a short on a flat book.
            val again = if (closingWorks(p.symbol)) null else runCatching { Paper.place(c, "SELL", lots, "SL-M", "MIS", null, p.stop) }.getOrNull()?.takeIf { it.ok }?.orderId
                ?.also { runCatching { Strategies.tagOwner("paper:$it", "${ownerOf(p)} · stop") } }
            if (again != null) p = p.copy(stopOrderId = again)
        }
        val rule = Exit.of(p.exitRule)
        val level = if (p.halfDone) p.lastTarget else p.target
        if (level == null || ltp < level) return p
        if (rule == Exit.HALF_COST && !p.halfDone) {
            // T1: half sold (when there is a half to sell), the stop moved to cost for the rest.
            val half = NtfyRules.halfLots(p.lots)
            var sold = 0; var value = 0.0; var ch = 0.0
            if (half > 0) {
                val r = Paper.place(c, "SELL", half, "MARKET", "MIS", null, null, exit = true)
                val f = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull() ?: return p
                sold = f.quantity; value = f.price * f.quantity; ch = chargesOf(r.orderId)
            }
            p.stopOrderId?.let { runCatching { Paper.modify(it, p.qty - sold, null, p.entry) } }
            return p.copy(halfDone = true, stop = p.entry, soldQty = sold, soldValue = value, charges = p.charges + ch)
        }
        return sellRest(p, c, if (p.halfDone) "last target" else "target")
    }

    /** A SELL for [symbol] still working in the paper book (open, or a trigger-pending stop): a second stop is never put in beside it. */
    private fun closingWorks(symbol: String): Boolean =
        Paper.state.orders.any { it.symbol == symbol && it.action == "SELL" && (it.status == "open" || it.status == "trigger pending") }

    /** Sell what is left of [p] at market (its resting stop taken out first; if it filled meanwhile, that is the exit). Reduce-only. */
    private suspend fun sellRest(p: Pos, c: Paper.Contract, why: String): Pos {
        p.stopOrderId?.let { id ->
            runCatching { Paper.cancel(id, "exit:$why") }
            val o = Paper.state.orders.firstOrNull { it.orderId == id }
            if (o != null && o.status == "complete") return closed(p, o.averagePrice?.toDouble() ?: p.stop, o.updateTimestamp, "stop", chargesOf(id))
        }
        val lots = ((p.qty - p.soldQty) / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
        // An exit only reduces what is held - never a paper short when the square-off got there first.
        val r = Paper.place(c, "SELL", lots, "MARKET", "MIS", null, null, exit = true)
        val f = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull() ?: run {
            // Already flat in the paper book (the square-off, the sweeper or Boss): closed elsewhere, at that sale's price.
            if (!r.ok && Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } <= 0) {
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
                return closed(p.copy(stopOrderId = null), t?.price?.toDouble() ?: p.entry, t?.timestamp ?: now(), "closed_elsewhere", t?.charges?.toDouble() ?: 0.0)
            }
            // Not filled (no fresh price): the stop goes back into the paper book at once, and the exit is tried next pass.
            r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            val again = if (closingWorks(p.symbol)) null else runCatching { Paper.place(c, "SELL", lots, "SL-M", "MIS", null, p.stop) }.getOrNull()?.takeIf { it.ok }?.orderId
            return p.copy(stopOrderId = again)
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${ownerOf(p)} · exit") } }
        return closed(p.copy(stopOrderId = null), f.price, now(), why, chargesOf(r.orderId))
    }

    /** 26 Oct: the owner tag of [p]'s orders, its source strategy with it ("ntfy alerts (paper) · IraThinker mom60"). */
    private fun ownerOf(p: Pos): String = NtfyRules.OWNER + (p.strategy?.let { " · $it" } ?: "")

    /** Whether stop order [id] still rests in the paper book (working or waiting for its trigger). */
    private fun stopResting(id: String?): Boolean {
        val o = id?.let { x -> Paper.state.orders.firstOrNull { it.orderId == x } } ?: return false
        return o.status != "cancelled" && o.status != "rejected" && o.status != "complete"
    }

    private fun closed(p: Pos, price: Double, at: LocalDateTime, why: String, charges: Double): Pos =
        p.copy(exit = price, exitTime = at, why = why, charges = p.charges + charges, stopOrderId = null)

    /**
     * For tests: the book forgotten (its file stays: the tests read it again, as after a restart), the quick pass cancelled.
     * 26 Oct: under [written]'s lock, as every save, so a pass that read the old book writes nothing after it ([save]).
     */
    internal fun wipe() {
        synchronized(written) {
            cache = null; _view.value = View(); testListed = null; testSpot = null; barsNow = emptyMap(); quotesNow = emptyMap(); kickedPasses = 0
            entrySpotsNow = emptyMap()
            written.forget()
        }
        // 26 Oct (review): a quick pass left from the last test never runs into the next one.
        synchronized(kicks) { kicker?.cancel(); kicker = null; while (kicks.tryReceive().isSuccess) Unit }
    }

    /**
     * 26 Oct, "Erase everything" ([PersonalStores]): [wipe], and its files deleted (the book, the removed arm's old book, the
     * set-aside copies) under [written]'s lock, so a write under way ends first. Memory and files only: no vault read; never
     * waits for the book's lock.
     */
    internal fun wipeForErase() {
        synchronized(written) {
            for (f in filesForErase()) runCatching { f.delete() }
            wipe()
        }
    }

    /** 26 Oct: anything of the book is held in memory (the erase's check; memory only). */
    internal fun holdsForErase(): Boolean = runCatching {
        val v = _view.value
        v.on || v.open.isNotEmpty() || v.closed.isNotEmpty() || v.alerts.isNotEmpty() || v.log.isNotEmpty() || v.last != null ||
            v.settings != NtfyRules.Settings() || cache?.let { b -> b.positions.isNotEmpty() || b.alerts.isNotEmpty() || b.read.ids.isNotEmpty() ||
                b.signals.isNotEmpty() || b.log.isNotEmpty() } == true
    }.getOrDefault(true)

    /** 26 Oct: its book, the removed arm's old book, and any copy of either set aside as unreadable. */
    internal fun filesForErase(): List<File> = listOfNotNull(file, oldFile).flatMap { f ->
        listOf(f) + f.parentFile?.listFiles { x -> x.name.startsWith(f.name + ".unreadable.") }.orEmpty()
    }
}
