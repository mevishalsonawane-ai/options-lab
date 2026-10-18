package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.risk.ExitQty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * The LIVE path of every paper strategy that had none (18 Oct 2026, Boss: "What ever strategies are present for paper, should be
 * present for live too"; "I want all be present for live too, i will switch it off, if i need it"). The rules are the engine's
 * [LiveMirror]; this object carries them out with the app's existing live machinery - [Broker.placeOrder] (the entry door: the
 * paper-only period, static IP, the kill switch, the daily loss limit, the bot's day stop, the day lock, the portfolio guard), the
 * account guard ([Guard.check]), the order gates ([Kite.refusals]: lots, value, freeze quantity), the SL order with its limit and
 * its moves (as the ORB and Pine arms'), the stop taken out before any exit, and the exit sized to what Zerodha holds less what
 * is already on its way out ([ExitQty]).
 *
 * How: each arm in [LiveMirror.ARMS] keeps trading its own paper book exactly as before. When its paper buy fills it tells this
 * object ([entered]). A live entry goes ONLY with the app in Live, "AI trades go live" on (only going Live with Boss's PIN turns it
 * on), the arm's own "Trade live" switch on, its Shadow OFF (18 Oct, Boss: "add a shadow button, so that even i move to live, until
 * is on, it will not trade" - on by default, off only with the PIN), outside the paper-only period and with every guard clear.
 * In shadow, what it would have sent is recorded as a SHADOW trade instead - priced as the live mirror would fill it, charges
 * included - and nothing is sent. A paper trade that was not mirrored is noted with why ("not mirrored: ..."), on its arm's row.
 *
 * Its exits follow its paper trade: the watch ([watch]) sells the live trade when its paper trade is closed, sells the same share
 * when the paper trade sold part (the Zerodha stop shrunk, never cancelled, for a part), moves the Zerodha stop to the paper
 * trade's stop or lock on the live fill, and keeps its own backstops (15:15 for an intraday NSE trade, 15:05 on an option's expiry
 * day, Boss's stop for today, the price through the stop's limit). A close-judged arm (the Hero arm, the Hero ticket) is sold by
 * its paper close-exit, with only a labelled DISASTER stop (-80%) resting; an arm with no premium stop of its own (Night, Solo
 * (midday), Solo 2.0's S1, VIX BANKNIFTY, the MCX trend, the index-stop shadow) rests a labelled SAFETY stop (-30%).
 *
 * At Zerodha, so a dead phone cannot miss them: an intraday trade's stop is a real SL order with a limit; a carried (NRML) trade's
 * is a single-leg GTT; NEVER both, never a second resting exit beside it, and never more resting exits on a contract than Zerodha
 * holds (each look shrinks a stop that is too big, newest first). A target or a time exit is the app's.
 *
 * Its book is its own encrypted file (no backup): every live trade, per arm, labelled live and apart from the arm's paper record,
 * all of them kept (the account stop rule runs on the whole record); shadow trades kept apart. A book that cannot be read refuses
 * every live entry until it is read, keeps the switches from their second copy in the settings, and a book that failed
 * authentication is rebuilt from Zerodha's own positions and this path's order tags, so no open live trade is ever dropped
 * unwatched. Open positions first: read at the start before other work ([PositionsFirst]), watched in the fast path's minute lane,
 * the live lane and the 15-second stop check. Nothing here runs on the tick path; every order and read happens on a worker;
 * [entered], [level] and [closed] only put a note in memory and return.
 */
object ArmLive {
    private lateinit var app: Context
    private var file: File? = null
    private val lock = Mutex()
    /** One live exit or watch pass at a time (they read the same books and send the same kinds of orders). */
    private val opLock = Mutex()
    /** One live entry at a time (the entry door is one at a time too); entries never hold the exits' lock. */
    private val entryLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val written = Vault.LastWrite()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.noBackupFilesDir, FILE)
        // Solo (midday)'s paper book (Jarvis builds only): read by the watch, never at the start (its trades are in the settings vault).
        if (com.optionslab.app.BuildConfig.JARVIS) register(LiveMirror.SOLO) { com.optionslab.app.ira.IraSolo.mirrorLegs() }
    }

    private const val FILE = "arm_live.vault"
    private const val LOG_KEPT = 120
    private const val SHADOW_KEPT = 600
    private const val NOT_MIRRORED_KEPT = 300
    private const val WHO = "Arms live"
    /** The switches' second copy in the settings vault (a book that cannot be read never brings every arm back on). */
    internal const val K_SWITCHES = "armlive.switches"
    internal const val K_SHADOW = "armlive.shadow"

    const val OWN = "own"
    const val SAFETY = "safety"
    const val DISASTER = "disaster"

    /**
     * One live (or shadow) trade of a mirrored arm. [key] the arm; [ref] its paper trade ("family:id"); [symbol] and [exchange]
     * Zerodha's; [side] +1 bought, -1 sold; [qty] held now of [entryQty] bought; [lot] Zerodha's order lot (MCX: 1, in lots);
     * [multiplier] units per quantity (MCX's lot; 1 elsewhere); [paperEntry] / [paperQty] its paper trade's fill and quantity;
     * [stopOrderId] the SL order's id, or "gtt:<id>"; [stopQty] what that stop is for; [stopKind] [OWN] (the arm's stop or lock),
     * [SAFETY] (-30%, an arm with no premium stop) or [DISASTER] (-80%, a close-judged arm); [seen] its paper trade was seen open;
     * [heldSeen] Zerodha has shown it held (only then is a zero read "closed outside"; [zeroSinceMs] the first zero read);
     * [unconfirmed] Zerodha has not said what the entry filled; [shadow] nothing was sent (a shadow trade).
     */
    data class Pos(
        val key: String, val ref: String, val symbol: String, val exchange: String, val product: String, val side: Int,
        val qty: Int, val entryQty: Int, val lot: Int, val tick: Double, val multiplier: Int, val entry: Double, val entryTime: LocalDateTime,
        val paperEntry: Double, val paperQty: Int, val expiry: LocalDate?, val entryOrderId: String?,
        val stopOrderId: String? = null, val stopTrigger: Double? = null, val seen: Boolean = false, val unconfirmed: Boolean = false,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0,
        val shadow: Boolean = false, val stopKind: String = OWN, val stopQty: Int? = null, val heldSeen: Boolean = false,
        val zeroSinceMs: Long? = null,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        /** Rupees after charges, once closed. */
        val net: Double? get() = exit?.let { (it - entry) * qty * multiplier.coerceAtLeast(1) * side - charges }
        val label: String get() = LiveMirror.spec(key)?.label ?: key
        val gttId: Long? get() = stopOrderId?.takeIf { it.startsWith(GTT) }?.removePrefix(GTT)?.toLongOrNull()
        val slId: String? get() = stopOrderId?.takeIf { !it.startsWith(GTT) }
        val contract: String get() = "$exchange|$symbol|$product"
        /** "stop", or "safety stop" / "disaster stop" for a protective one: the words its exit is booked with. */
        val stopWord: String get() = when (stopKind) { SAFETY -> "safety_stop"; DISASTER -> "disaster_stop"; else -> "stop" }
    }

    private const val GTT = "gtt:"

    /** A paper trade that was not mirrored live (Boss's row says how many, and why). */
    data class NotMirrored(val ref: String, val key: String, val at: LocalDateTime, val why: String)

    private class Book(
        val positions: MutableList<Pos> = ArrayList(),
        /** Boss's "Trade live" choices; an arm never set takes its default ([LiveMirror.Spec.defaultOn]). */
        val switches: MutableMap<String, Boolean> = HashMap(),
        val log: ArrayList<String> = ArrayList(),
        /** Each arm's last refused live entry, in words (its row says why nothing went). */
        val refused: MutableMap<String, String> = HashMap(),
        /** Boss's Shadow choices; an arm never set is in shadow ([LiveMirror.SHADOW_DEFAULT]). */
        val shadow: MutableMap<String, Boolean> = HashMap(),
        /** When each arm's current shadow began: its shadow record counts from then. */
        val shadowSince: MutableMap<String, LocalDateTime> = HashMap(),
        val notMirrored: ArrayList<NotMirrored> = ArrayList(),
    )

    /** What the screen and the tests read. */
    data class View(
        val switches: Map<String, Boolean> = emptyMap(), val open: List<Pos> = emptyList(), val closed: List<Pos> = emptyList(),
        val log: List<String> = emptyList(), val refused: Map<String, String> = emptyMap(),
        val shadow: Map<String, Boolean> = emptyMap(), val shadowSince: Map<String, LocalDateTime> = emptyMap(),
        /** The closed shadow trades kept. */
        val shadowClosed: List<Pos> = emptyList(),
        val notMirrored: List<NotMirrored> = emptyList(),
        /** The book has been read (the screen says "loading" before). */
        val loaded: Boolean = false,
        /** The book could not be read: every live entry is refused until it is. */
        val unreadable: Boolean = false,
    ) {
        fun on(key: String): Boolean = LiveMirror.on(key, switches)
        /** Whether [key] is in shadow: it sends nothing live, it records what it would have sent. */
        fun shadowOn(key: String): Boolean = LiveMirror.shadowOn(key, shadow)
        /** [key]'s live record: its closed REAL trades, each paper trade counted once (a part exit is not another trade), and the net. */
        fun record(key: String): Pair<Int, Double> = closed.filter { it.key == key }.let { c -> c.map { it.ref }.distinct().size to c.sumOf { it.net ?: 0.0 } }
        /** [key]'s shadow record since its shadow began: trades (once each), net after charges, win rate. */
        fun shadowRecord(key: String): LiveMirror.Record {
            val since = shadowSince[key]
            return LiveMirror.record(shadowClosed.filter { it.key == key && (since == null || !(it.exitTime ?: it.entryTime).isBefore(since)) }
                .groupBy { it.ref }.values.map { g -> g.sumOf { it.net ?: 0.0 } })
        }
        /** Today's paper trades of [key] not mirrored live, and the last reason. */
        fun notMirroredToday(key: String, today: LocalDate): Pair<Int, String?> =
            notMirrored.filter { it.key == key && it.at.toLocalDate() == today }.let { it.size to it.lastOrNull()?.why }
        val openReal: List<Pos> get() = open.filter { !it.shadow }
        val openShadow: List<Pos> get() = open.filter { it.shadow }
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view
    @Volatile private var cache: Book? = null
    /** The book's file could not be read (memory): no live entry until it is. */
    @Volatile private var unreadable = false
    /** The book failed authentication and was rebuilt: the open live trades are to be recovered from Zerodha on the next look. */
    @Volatile private var recoverDue = false

    /** The open live trades' quote keys ("NFO:SYMBOL"), for the price stream (memory only: the tick path reads it). */
    @Volatile var liveKeysHint: List<String> = emptyList()
        private set

    /** Something live is held (memory only): the fast path and the watch keep running for it. */
    @Volatile var holdingHint: Boolean = false
        private set

    /** A shadow trade is open (memory only): the watch's regular look prices it (no order, never the live lane). */
    @Volatile var shadowHint: Boolean = false
        private set

    // ---- the paper arms' notes (memory only, never blocking) -----------------------------------------------------------------

    /** A paper book that hosts mirrored arms: its paper trades now, from memory (null: not read - nothing is decided on it). */
    fun interface Host { fun open(): List<PaperLeg>? }

    /** One paper trade: its [ref] ("family:id", as given to [entered]), its arm [key], the quantity it still holds (0: closed). */
    data class PaperLeg(val ref: String, val key: String, val qty: Int)

    private val hosts = java.util.concurrent.ConcurrentHashMap<String, Host>()

    /** [family]'s paper book ([LiveMirror.Spec.family]), registered once at the app's start (and by the tests). */
    fun register(family: String, host: Host) { hosts[family] = host }

    /** Each paper trade's latest stop or lock level, as its own book last set it, and the day it was set (memory only). */
    private val levels = java.util.concurrent.ConcurrentHashMap<String, Pair<LocalDate, Double>>()
    /** Paper trades closed today, and why (memory only; after a restart their absence from the book says it). */
    private val closedRefs = java.util.concurrent.ConcurrentHashMap<String, Pair<LocalDate, String>>()
    private const val NOTES_KEPT = 2_000

    /** The memory notes of earlier days dropped, and never more than [NOTES_KEPT] (they are small; Paper days add them too). */
    private fun prune() {
        val today = runCatching { now().toLocalDate() }.getOrElse { return }
        levels.entries.removeIf { it.value.first.isBefore(today) }
        closedRefs.entries.removeIf { it.value.first.isBefore(today) }
        if (levels.size > NOTES_KEPT) levels.clear()
        if (closedRefs.size > NOTES_KEPT) closedRefs.clear()
    }

    private fun levelOf(ref: String): Double? = levels[ref]?.second
    private fun closedWhy(ref: String): String? = closedRefs[ref]?.second

    /**
     * A paper arm's buy just filled ([ask]): the live (or shadow) entry is asked on a worker; this returns at once (memory only).
     * Nothing goes unless every switch and guard allows it ([LiveMirror.refusal]).
     */
    fun entered(ask: Ask) {
        if (!ready() || LiveMirror.spec(ask.key) == null) return
        ask.stop?.let { level(ask.ref, it) }
        // In Paper (the usual case) nothing more happens: no worker, no read (the settings are in memory).
        if (!runCatching { AppSettings.load().let { it.live && it.allowRealOrders } }.getOrDefault(false)) return
        if (testSync) { kotlinx.coroutines.runBlocking { enterNow(ask) }; return }
        scope.launch { runCatching { enterNow(ask) }.onFailure { e -> note(ask.key, "live entry failed: ${e.javaClass.simpleName}") } }
    }

    /**
     * A paper buy to mirror: arm [key], paper trade [ref], its [contract], [side] (+1 a buy, -1 a sale), its [paperQty] filled at
     * [paperEntry], its stop [stop] (null: none on the premium) and, for a LIMIT entry, the [limit] it paid at most.
     */
    data class Ask(val key: String, val ref: String, val contract: Paper.Contract, val side: Int, val paperQty: Int, val paperEntry: Double,
                   val stop: Double? = null, val limit: Double? = null)

    /** The paper trade [ref]'s stop or lock is at [paperLevel] now: the live stop follows on the next look (memory only). */
    fun level(ref: String, paperLevel: Double?) {
        if (paperLevel == null || !paperLevel.isFinite() || paperLevel <= 0) return
        levels[ref] = (runCatching { now().toLocalDate() }.getOrElse { LocalDate.now() }) to paperLevel
        if (levels.size > NOTES_KEPT) prune()
    }

    /** The paper trade [ref] is closed ([why]): its live trade is sold on the next look, which is asked now (memory only). */
    fun closed(ref: String, why: String) {
        closedRefs[ref] = (runCatching { now().toLocalDate() }.getOrElse { LocalDate.now() }) to why
        if (closedRefs.size > NOTES_KEPT) prune()
        if (holdingHint || shadowHint) nudge()
    }

    /** A look soon, off the caller's thread (the minute lane and the stop check run it anyway). */
    fun nudge() {
        if (!ready() || !(holdingHint || shadowHint)) return
        if (testSync) return
        scope.launch { runCatching { watch(stream = false) } }
    }

    // ---- Boss's switches -----------------------------------------------------------------------------------------------------

    /**
     * Boss's "Trade live" for [key]: OFF at any time with no PIN (it lowers risk); ON only with [pinConfirmed] (it adds live risk).
     * Logged in the diagnostics and the Autopilot change timeline (one tap undoes it; undoing an "off" asks the PIN). What to say.
     */
    suspend fun setLive(key: String, on: Boolean, pinConfirmed: Boolean, log: Boolean = true): String {
        val spec = LiveMirror.spec(key) ?: return "No such arm. $UNCHANGED"
        if (!ready()) return "Not ready yet. $UNCHANGED"
        if (on && !pinConfirmed) return "Turning ${spec.label}'s live trading on needs your PIN. $UNCHANGED"
        val was = runCatching {
            lock.withLock {
                val b = book()
                val w = LiveMirror.on(key, b.switches)
                b.switches[key] = on
                line(b, "${spec.label}: Trade live ${if (on) "ON" else "OFF"} (you)")
                save(b)
                backupSwitches(b)
                w
            }
        }.getOrElse { return "Its book could not be read just now. $UNCHANGED" }
        if (was != on) {
            runCatching { Diag.record("armlive", "${spec.label}: Trade live ${if (on) "on (PIN)" else "off"} by you") }
            if (log) runCatching { com.optionslab.app.ira.Autopilot.logArmLive(key, spec.label, on) }
        }
        return if (on) "${spec.label} trades live again in Live with \"AI trades go live\" on" + (if (_view.value.shadowOn(key)) " - once you take it out of shadow." else ".")
        else "${spec.label}: no new live entries (paper only for now). Its open live trade, if any, is still managed to its exit."
    }

    /**
     * 18 Oct (Boss: "add a shadow button, so that even i move to live, until is on, it will not trade, but when i see thats making money,
     * i will make it off"): [key]'s Shadow. ON at any time with no PIN (each new live entry is recorded as a shadow trade; an open live
     * trade still runs to its own exits and stops); OFF - real live entries - only with [pinConfirmed], and for Solo (midday), Jarvis's
     * Solo, only once its forward test has passed ([com.optionslab.app.ira.IraSolo.provenWhy]). Logged in the diagnostics and the
     * Autopilot change timeline (one tap undoes it; an undo that leaves shadow asks the PIN). What to say.
     */
    suspend fun setShadow(key: String, on: Boolean, pinConfirmed: Boolean, log: Boolean = true): String {
        val spec = LiveMirror.spec(key) ?: return "No such arm. $UNCHANGED"
        if (!ready()) return "Not ready yet. $UNCHANGED"
        if (!on && !pinConfirmed) return "Taking ${spec.label} out of shadow (real live orders) needs your PIN. $UNCHANGED"
        if (!on && key == LiveMirror.SOLO_MIDDAY) soloWhy()?.let { return "${spec.label} stays in shadow: ${it.trimEnd('.')}. $UNCHANGED" }
        val was = runCatching {
            lock.withLock {
                val b = book()
                val w = LiveMirror.shadowOn(key, b.shadow)
                b.shadow[key] = on
                if (on && !w) b.shadowSince[key] = now()
                line(b, "${spec.label}: Shadow ${if (on) "ON" else "OFF"} (you)")
                save(b)
                backupSwitches(b)
                w
            }
        }.getOrElse { return "Its book could not be read just now. $UNCHANGED" }
        if (was != on) {
            runCatching { Diag.record("armlive", "${spec.label}: Shadow ${if (on) "on" else "off (PIN)"} by you") }
            if (log) runCatching { com.optionslab.app.ira.Autopilot.logArmShadow(key, spec.label, on) }
        }
        return if (on) "${spec.label} is in shadow: it records what it would trade live and sends nothing. An open live trade still runs to its exit."
        else "${spec.label} is out of shadow: it trades live in Live with \"AI trades go live\" on and its Trade live switch on."
    }

    /** The end of every refusal of [setLive] and [setShadow]: nothing was changed ([done]). */
    const val UNCHANGED = "Nothing changed."

    /** Whether [said] (what [setLive] or [setShadow] answered) is a change made, not a refusal. */
    fun done(said: String): Boolean = !said.endsWith(UNCHANGED)

    /**
     * Why Solo (midday) may not leave shadow, or null: it is Jarvis's Solo, so only once [com.optionslab.app.ira.IraSolo.provenWhy]
     * passes (review C5) - and never in a build without Jarvis.
     */
    internal fun soloWhy(): String? = if (!com.optionslab.app.BuildConfig.JARVIS) "Solo runs in Jarvis builds only."
        else runCatching { com.optionslab.app.ira.IraSolo.provenWhy() }.getOrElse { "its record could not be read just now." }

    // ---- the book ------------------------------------------------------------------------------------------------------------

    private fun ready(): Boolean = file != null

    /** The book could not be read: the caller refuses entries (and is told). */
    class Unreadable : java.io.IOException("the arms' live book could not be read")

    private fun book(): Book {
        cache?.let { return it }
        val f = file ?: return Book().also { cache = it }
        val bytes = try { Vault.readFileSteady(f) } catch (e: Vault.Tampered) { null.also { tampered(f) } } catch (e: Exception) {
            // Not read now (a Keystore hiccup): nothing cached, read again next time; meanwhile no live entry goes.
            if (!unreadable) {
                unreadable = true
                publishUnreadable()
                com.optionslab.app.work.Alerts.error("The arms' live book could not be read: no new live entry goes until it is. " +
                    "Check open Zerodha positions in Trade.", WHO)
            }
            throw Unreadable()
        }
        val b = Book()
        val parsed = bytes == null || runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) runCatching { b.positions += readPos(a.getJSONObject(i)) } }
            o.optJSONObject("switches")?.let { m -> m.keys().forEach { b.switches[it] = m.getBoolean(it) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONObject("refused")?.let { m -> m.keys().forEach { b.refused[it] = m.getString(it) } }
            o.optJSONObject("shadow")?.let { m -> m.keys().forEach { b.shadow[it] = m.getBoolean(it) } }
            o.optJSONObject("shadowSince")?.let { m -> m.keys().forEach { k -> runCatching { b.shadowSince[k] = LocalDateTime.parse(m.getString(k)) } } }
            o.optJSONArray("notMirrored")?.let { a -> for (i in 0 until a.length()) runCatching { a.getJSONObject(i).let { x ->
                b.notMirrored += NotMirrored(x.getString("ref"), x.getString("key"), LocalDateTime.parse(x.getString("at")), x.getString("why")) } } }
        }.isSuccess
        if (!parsed) tampered(f)
        // No file (new, or set aside) or a rebuilt one: Boss's switches from their second copy, never fresh defaults.
        if (bytes == null || !parsed) restoreSwitches(b)
        unreadable = false
        cache = b
        publish(b)
        return b
    }

    /** A book that failed authentication or did not parse: set aside, rebuilt, its open live trades recovered from Zerodha. */
    private fun tampered(f: File) {
        if (f.exists()) runCatching { Vault.setAside(f) }
        recoverDue = true
        com.optionslab.app.work.Alerts.error("The arms' live book was damaged and set aside: its open live trades are taken back from " +
            "Zerodha's positions on the next look. Check them in Trade.", WHO)
    }

    private fun restoreSwitches(b: Book) {
        runCatching { SecurePrefs.getString(K_SWITCHES)?.let { JSONObject(it) }?.let { m -> m.keys().forEach { b.switches.putIfAbsent(it, m.getBoolean(it)) } } }
        runCatching { SecurePrefs.getString(K_SHADOW)?.let { JSONObject(it) }?.let { m -> m.keys().forEach { b.shadow.putIfAbsent(it, m.getBoolean(it)) } } }
    }

    private fun readPos(p: JSONObject): Pos {
        fun s(k: String) = p.optString(k).ifEmpty { null }
        fun d(k: String) = if (p.has(k) && !p.isNull(k)) p.getDouble(k) else null
        return Pos(p.getString("key"), p.getString("ref"), p.getString("symbol"), p.getString("exchange"), p.getString("product"),
            p.getInt("side"), p.getInt("qty"), p.getInt("entryQty"), p.getInt("lot"), p.getDouble("tick"), p.optInt("mult", 1),
            p.getDouble("entry"), LocalDateTime.parse(p.getString("entryTime")), p.getDouble("paperEntry"), p.getInt("paperQty"),
            s("expiry")?.let { LocalDate.parse(it) }, s("entryOrderId"), s("stopOrderId"), d("stopTrigger"), p.optBoolean("seen"),
            p.optBoolean("unconfirmed"), d("exit"), s("exitTime")?.let { LocalDateTime.parse(it) }, s("why"), p.optDouble("charges", 0.0),
            p.optBoolean("shadow"), s("stopKind") ?: OWN, if (p.has("stopQty")) p.getInt("stopQty") else null,
            p.optBoolean("heldSeen", !p.has("heldSeen")), if (p.has("zeroSince")) p.getLong("zeroSince") else null)
    }

    private fun writePos(p: Pos): JSONObject = JSONObject().put("key", p.key).put("ref", p.ref).put("symbol", p.symbol).put("exchange", p.exchange)
        .put("product", p.product).put("side", p.side).put("qty", p.qty).put("entryQty", p.entryQty).put("lot", p.lot).put("tick", p.tick)
        .put("mult", p.multiplier).put("entry", p.entry).put("entryTime", p.entryTime.toString()).put("paperEntry", p.paperEntry)
        .put("paperQty", p.paperQty).put("expiry", p.expiry?.toString() ?: "").put("entryOrderId", p.entryOrderId ?: "")
        .put("stopOrderId", p.stopOrderId ?: "").apply { p.stopTrigger?.let { put("stopTrigger", it) }; p.exit?.let { put("exit", it) } }
        .put("seen", p.seen).put("unconfirmed", p.unconfirmed).put("exitTime", p.exitTime?.toString() ?: "").put("why", p.why ?: "")
        .put("charges", p.charges).put("shadow", p.shadow).put("stopKind", p.stopKind).put("heldSeen", p.heldSeen)
        .apply { p.stopQty?.let { put("stopQty", it) }; p.zeroSinceMs?.let { put("zeroSince", it) } }

    private fun save(b: Book) {
        // Every real trade is kept (the live record and the account stop rule read them all); shadow trades the last [SHADOW_KEPT].
        val sh = b.positions.filter { !it.open && it.shadow }
        if (sh.size > SHADOW_KEPT) { val gone = sh.take(sh.size - SHADOW_KEPT).toSet(); b.positions.removeAll { it in gone } }
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        while (b.notMirrored.size > NOT_MIRRORED_KEPT) b.notMirrored.removeAt(0)
        val o = JSONObject()
            .put("positions", JSONArray().apply { b.positions.forEach { put(writePos(it)) } })
            .put("switches", JSONObject().apply { b.switches.forEach { (k, v) -> put(k, v) } })
            .put("log", JSONArray().apply { b.log.forEach { put(it) } })
            .put("refused", JSONObject().apply { b.refused.forEach { (k, v) -> put(k, v) } })
            .put("shadow", JSONObject().apply { b.shadow.forEach { (k, v) -> put(k, v) } })
            .put("shadowSince", JSONObject().apply { b.shadowSince.forEach { (k, v) -> put(k, v.toString()) } })
            .put("notMirrored", JSONArray().apply { b.notMirrored.forEach { put(JSONObject().put("ref", it.ref).put("key", it.key).put("at", it.at.toString()).put("why", it.why)) } })
        file?.let { f -> runCatching { written.write(f, o.toString().toByteArray(Charsets.UTF_8)) } }
        publish(b)
    }

    /** The switches' second copy (a worker: only Boss's switch changes write it). */
    private fun backupSwitches(b: Book) {
        runCatching {
            SecurePrefs.putAll(mapOf(K_SWITCHES to JSONObject().apply { b.switches.forEach { (k, v) -> put(k, v) } }.toString(),
                K_SHADOW to JSONObject().apply { b.shadow.forEach { (k, v) -> put(k, v) } }.toString()))
        }
    }

    private fun publish(b: Book) {
        val open = b.positions.filter { it.open }
        val real = open.filter { !it.shadow }
        holdingHint = real.isNotEmpty()
        shadowHint = open.any { it.shadow }
        liveKeysHint = real.map { "${it.exchange}:${it.symbol}" }.distinct()
        _view.value = View(b.switches.toMap(), open, b.positions.filter { !it.open && !it.shadow }, b.log.takeLast(40), b.refused.toMap(),
            b.shadow.toMap(), b.shadowSince.toMap(), b.positions.filter { !it.open && it.shadow }, b.notMirrored.toList(), loaded = true)
    }

    private fun publishUnreadable() { _view.value = _view.value.copy(unreadable = true, loaded = true) }

    /** Read the book (the start's positions-first step and the screens). A worker thread. */
    suspend fun refresh(): View { if (ready()) runCatching { lock.withLock { book() } }; return _view.value }

    /** Whether a live trade of these arms is open (the book read when it is not yet). Unreadable: held (the watch decides). */
    suspend fun holding(): Boolean {
        if (!ready()) return false
        return runCatching { lock.withLock { book() } }.fold({ _view.value.openReal.isNotEmpty() }, { true })
    }

    /** Whether one of these arms' stops rests at Zerodha for [symbol] (the Live check's "stop watched"; memory only). */
    fun stopRests(symbol: String): Boolean = _view.value.openReal.any { it.symbol == symbol && it.stopOrderId != null }

    /** The arm of a live trade of [symbol] held now (memory only), for the order lists' "who placed it". */
    fun ownerOf(symbol: String): String? = _view.value.openReal.firstOrNull { it.symbol == symbol }?.label

    private fun line(b: Book, text: String) {
        val t = now()
        b.log += "%02d:%02d %s".format(Locale.ENGLISH, t.hour, t.minute, text)
    }

    private fun note(key: String, text: String) {
        runCatching { Diag.record("armlive", "${LiveMirror.spec(key)?.label ?: key}: $text") }
    }

    // ---- clocks and seams (the tests fix them) ------------------------------------------------------------------------------

    /** TEST ONLY: a fixed clock. Null in the app, always. Its setter throws unless BuildConfig.DEBUG. */
    @Volatile internal var testNow: java.time.ZonedDateTime? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }; field = v }
    /**
     * TEST SEAM: true in the JVM tests' app - an arm Boss never switched counts as off there, so the paper arms' own tests (Live,
     * AI trades on) stay about their paper books (as [PassedArmsHost.testNoAutoOn]); the live path's tests set it false. Its setter
     * throws in a release build.
     */
    @Volatile internal var testDefaultOff: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }
    /** TEST ONLY: [entered] runs the entry before it returns (no worker), so a test reads what it did at once. Debug builds only. */
    @Volatile internal var testSync: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }
    private fun now(): LocalDateTime = (testNow ?: Market.now()).toLocalDateTime().withNano(0)
    private fun nowMs(): Long = (testNow ?: Market.now()).toInstant().toEpochMilli()

    // ---- entries ---------------------------------------------------------------------------------------------------------------

    /**
     * The gate's answers now, in [LiveMirror.refusal]'s order, read only as far as needed: in Paper, with AI trades off or the arm's
     * own switch off nothing else is read. [shadow]: for a shadow trade the paper-only period does not refuse it (nothing is sent).
     */
    private suspend fun gate(key: String, shadow: Boolean): LiveMirror.Gate {
        val s = AppSettings.load()
        val appLive = s.live && s.allowRealOrders
        val aiLive = appLive && runCatching { !com.optionslab.app.ira.IraNewsTrades.paperFirst }.getOrDefault(false)
        val armOn = lock.withLock { book().switches.toMap() }.let { sw -> if (testDefaultOff && key !in sw) false else LiveMirror.on(key, sw) }
        val first = LiveMirror.Gate(appLive = appLive, aiLive = aiLive, armOn = armOn)
        if (LiveMirror.refusal(first) != null) return first
        val g = first.copy(paperOnly = if (shadow) null else PaperOnly.entryRefusal(), kill = s.guardKill,
            stopped = runCatching { Strategies.stoppedToday() }.getOrDefault(true),
            lossLimit = runCatching { LossBreaker.trippedToday() }.getOrDefault(false))
        if (LiveMirror.refusal(g) != null) return g
        val g2 = g.copy(dayLock = runCatching { DayLockGuard.refusal(true) }.getOrNull(), accountStop = if (shadow) null else accountStop(),
            loggedIn = Broker.loggedIn)
        if (LiveMirror.refusal(g2) != null) return g2
        return g2.copy(compromised = !shadow && runCatching {
            com.optionslab.app.security.Integrity.compromised(com.optionslab.app.security.Integrity.reportWithin(app, 60_000))
        }.getOrDefault(false))
    }

    /**
     * The account stop rule (Boss's: -Rs 40,000 from the best, or -Rs 13,000 in a calendar month) over the app's whole live record of
     * the arms - every real trade of these arms (all are kept) and the ORB family's - or null while neither is reached.
     */
    private suspend fun accountStop(): String? = runCatching {
        val mine = lock.withLock { book().positions.filter { !it.open && !it.shadow } }.mapNotNull { p ->
            val t = p.exitTime ?: return@mapNotNull null
            com.optionslab.ira.LiquidityStopRule.Trade(t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli(), t.toLocalDate(), p.net ?: 0.0)
        }
        val orb = runCatching { OrbArms.closedLive() }.getOrDefault(emptyList()).mapNotNull { p ->
            val t = p.exitTime ?: return@mapNotNull null
            val net = p.exit?.let { (it - p.entry) * p.qty - p.charges } ?: return@mapNotNull null
            com.optionslab.ira.LiquidityStopRule.Trade(t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli(), t.toLocalDate(), net)
        }
        val st = com.optionslab.ira.LiquidityStopRule.check(mine + orb, now().toLocalDate())
        if (st.stopped) st.why else null
    }.getOrNull()

    private data class Ins(val symbol: String, val exchange: String, val lot: Int, val tick: Double, val multiplier: Int)

    /** Zerodha's contract for [c], or why not. */
    private suspend fun zerodha(c: Paper.Contract): Pair<Ins?, String?> {
        if (c.isMcx) {
            val m = McxMarket.find(c.underlying, c.expiry, c.strike, c.right.takeIf { it != Right.IX }) ?: return null to "${c.symbol} is not in the MCX list"
            return Ins(m.tradingSymbol, "MCX", 1, m.tick, m.multiplier.coerceAtLeast(1)) to null
        }
        if (c.underlying == "SENSEX") {
            val l = Broker.cachedBfoOptions() ?: run { Broker.loadBfoOptionsInBackground(); return null to "SENSEX's options list is not read yet" }
            val i = Broker.find(l, c.underlying, c.expiry, c.strike, c.right) ?: return null to "${c.symbol} is not listed on Zerodha"
            return Ins(i.tradingSymbol, "BFO", i.lotSize, i.tickSize, 1) to null
        }
        val list = Broker.cachedInstruments() ?: runCatching { Broker.instruments() }.getOrNull() ?: return null to "Zerodha's instrument list could not be read"
        val i = list.firstOrNull { it.name == c.underlying && it.expiry == c.expiry && it.right == c.right && kotlin.math.abs(it.strike - c.strike) < 1e-6 }
            ?: return null to "${c.symbol} is not listed on Zerodha"
        return Ins(i.tradingSymbol, "NFO", i.lotSize, i.tickSize, 1) to null
    }

    /** A paper trade not mirrored live ([why]), noted on its arm's row (and the diagnostics) - only when the app is in Live. */
    private suspend fun notMirrored(a: Ask, why: String) {
        runCatching {
            lock.withLock {
                val b = book()
                b.refused[a.key] = "%s: %s".format(Locale.ENGLISH, now().toString().take(16).replace('T', ' '), why)
                if (b.notMirrored.none { it.ref == a.ref }) b.notMirrored += NotMirrored(a.ref, a.key, now(), why)
                save(b)
            }
        }
        note(a.key, "not mirrored: $why")
    }

    /** Kite order ids already tracked here and by every other bot: never adopted for a lost reply. */
    private suspend fun known(): List<String> =
        runCatching { Strategies.owners().keys.filter { it.startsWith("kite:") }.map { it.removePrefix("kite:") } }.getOrDefault(emptyList()) +
            runCatching { lock.withLock { book().positions.flatMap { listOfNotNull(it.entryOrderId, it.slId) } } }.getOrDefault(emptyList())

    private fun charge(side: String, price: Double, qty: Int, exchange: String, symbol: String, multiplier: Int): Double = runCatching {
        com.optionslab.engine.sandbox.SandboxCosts.charge(side, java.math.BigDecimal(price), qty, java.math.BigDecimal(multiplier.coerceAtLeast(1)),
            exchange, symbol).toDouble()
    }.getOrDefault(0.0)

    private val DONE = setOf("COMPLETE", "REJECTED", "CANCELLED")

    /** The stop a new trade rests on, and its kind: the arm's own (scaled to the fill), else the safety or disaster stop, else none. */
    private fun firstStop(spec: LiveMirror.Spec, p: Pos): Pair<Double?, String> {
        if (spec.closeStop) return LiveMirror.disasterLevel(p.entry, p.side, p.tick) to DISASTER
        wanted(p)?.let { return it to OWN }
        if (spec.safety) return LiveMirror.safetyLevel(p.entry, p.side, p.tick) to SAFETY
        return null to OWN
    }

    /** The live (or shadow) entry for [a], when the gate allows it. */
    private suspend fun enterNow(a: Ask) = entryLock.withLock {
        val spec = LiveMirror.spec(a.key) ?: return@withLock
        runCatching { OrderTiming.decided() }
        val shadowed = try { lock.withLock { val b = book(); LiveMirror.shadowOn(a.key, b.shadow) } } catch (e: Unreadable) {
            note(a.key, "not mirrored: the live book could not be read"); return@withLock
        }
        val g = gate(a.key, shadowed)
        LiveMirror.refusal(g)?.let { why ->
            // In Paper (the usual case) nothing is said: the arm trades its paper book as always.
            if (g.appLive) notMirrored(a, why)
            return@withLock
        }
        closedWhy(a.ref)?.let { notMirrored(a, "its paper trade is already closed ($it)"); return@withLock }
        // One live trade per paper trade, ever.
        if (lock.withLock { book().positions.any { it.ref == a.ref } }) return@withLock
        val (ins, insWhy) = zerodha(a.contract)
        if (ins == null) { notMirrored(a, insWhy ?: "no contract"); return@withLock }
        LiveMirror.tooLate(now(), ins.exchange, spec.product, a.contract.expiry)?.let { notMirrored(a, it); return@withLock }
        val lots = LiveMirror.lots(a.paperQty, a.contract.lotSize)
        val qty = LiveMirror.liveQty(lots, ins.lot)
        if (qty <= 0) { notMirrored(a, "the paper trade is under one lot"); return@withLock }
        val qkey = "${ins.exchange}:${ins.symbol}"
        val last = runCatching { Broker.stopQuotes(listOf(qkey))[qkey]?.last }.getOrNull()?.takeIf { it > 0 }
            ?: run { notMirrored(a, "no Zerodha price for ${ins.symbol}"); return@withLock }
        // Never an entry against what Zerodha holds the other way on this contract (two arms on one future, opposite ways).
        val net = runCatching { Broker.positionBook().net.filter { it.symbol == ins.symbol && it.exchange == ins.exchange && it.product == spec.product }.sumOf { it.qty } }
            .getOrElse { notMirrored(a, "Zerodha's positions could not be read"); return@withLock }
        LiveMirror.againstNet(a.side, net)?.let { notMirrored(a, it); return@withLock }
        val s = AppSettings.load()
        val side = if (a.side >= 0) Kite.Side.BUY else Kite.Side.SELL
        // The paper arm's own order type: a LIMIT entry pays at most its paper limit (never chases), else MARKET.
        val o = if (a.limit != null) Kite.Order(ins.symbol, side, qty, ins.lot, spec.product, "LIMIT",
                Kite.onTick(a.limit, ins.tick, side), ins.tick, ins.exchange, LiveMirror.TAG, multiplier = ins.multiplier)
            else Kite.Order(ins.symbol, side, qty, ins.lot, spec.product, "MARKET", null, ins.tick, ins.exchange, LiveMirror.TAG, multiplier = ins.multiplier)
        val acct = runCatching { Broker.accountNow(needed = last * qty * ins.multiplier) }.getOrNull()
        Guard.check(Guard.liveOrder(o).copy(price = last), acct).takeIf { it.isNotEmpty() }?.let { notMirrored(a, "account guard: " + it.joinToString(" ")); return@withLock }
        Kite.refusals(o, s.limits(), Broker.sentToday(), false, refPrice = last).takeIf { it.isNotEmpty() }?.let { notMirrored(a, it.joinToString("; ")); return@withLock }
        val base = Pos(a.key, a.ref, ins.symbol, ins.exchange, spec.product, if (a.side >= 0) 1 else -1, qty, qty, ins.lot, ins.tick, ins.multiplier,
            last, now(), a.paperEntry, a.paperQty, a.contract.expiry, null, seen = paperOpen(spec, a.ref) == true)
        if (shadowed) { enterShadow(spec, a, base, o); return@withLock }
        val known = known()
        val id: String? = try {
            Broker.placeOrder(o, arm = "arm:${a.key}", intended = last)
        } catch (e: Broker.NotLoggedIn) {
            notMirrored(a, "not logged in to Zerodha today"); return@withLock
        } catch (e: Exception) {
            if (Broker.definite(e)) { notMirrored(a, e.message ?: "Zerodha refused the order"); return@withLock }
            // The answer was lost, not necessarily the order: look before calling it unsent.
            try { Broker.findRecentRetrying(o, known) ?: run { notMirrored(a, "${e.message}; no matching order at Zerodha"); return@withLock } }
            catch (_: Exception) { null }
        }
        id?.let { runCatching { Strategies.tagOwner("kite:$it", "${spec.label} · live entry") } }
        var f = id?.let { runCatching { Broker.awaitOrder(it, 15_000) }.getOrNull() }
        if (id != null && f?.status !in DONE) {
            // Not finished in 15 s: the rest is cancelled so it can never fill later untracked; only what filled is booked.
            runCatching { Broker.cancel(id) }
            f = runCatching { Broker.orderState(id) }.getOrNull()
        }
        if (f == null || f.status !in DONE) {
            // Zerodha has not said how it ended: held as unconfirmed (no second entry meanwhile) until the order book settles it.
            lock.withLock { val b = book(); b.positions += base.copy(entryOrderId = id, unconfirmed = true); line(b, "${spec.label}: bought ${ins.symbol}, not yet confirmed"); save(b) }
            com.optionslab.app.work.Alerts.error("${spec.label}: Zerodha did not confirm the live ${side.name.lowercase()} of ${ins.symbol}. " +
                "It is treated as held until the order book shows what filled.", WHO)
            return@withLock
        }
        if (f.filled <= 0) { notMirrored(a, "Zerodha ${f.status.lowercase()}" + (f.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")); return@withLock }
        val fill = f.avgPrice.takeIf { it > 0 } ?: last
        runCatching { Notifier.orderFilled(app, side.name, f.filled, ins.symbol, fill, "Live", "${spec.label} · live entry", f.orderId) }
        // Seen held only once Zerodha's position book shows it (review C3): a book that lags the fill never closes the trade.
        var p = base.copy(entryOrderId = id, qty = f.filled, entryQty = f.filled, entry = fill, heldSeen = false,
            charges = charge(side.name, fill, f.filled, ins.exchange, ins.symbol, ins.multiplier))
        val (stop, kind) = firstStop(spec, p)
        p = p.copy(stopKind = kind)
        // The stop rests at once, on the paper trade's level on this fill (or the safety / disaster stop).
        p = restStop(p, stop, ltp = fill)
        lock.withLock {
            val b = book()
            b.positions += p
            b.refused.remove(a.key)
            line(b, "%s: %s %s %d @ %.2f live%s".format(Locale.ENGLISH, spec.label, if (p.side > 0) "bought" else "sold", ins.symbol, p.qty, fill,
                p.stopTrigger?.let { " · %s %.2f %s".format(Locale.ENGLISH, stopWords(p), it, if (p.stopOrderId != null) "at Zerodha" else "watched by the app (not rested yet)") }
                    ?: " · no stop on the premium (the app follows its paper exits)"))
            save(b)
        }
        runCatching { Diag.record("armlive", "${spec.label}: live entry ${ins.symbol} ${p.qty} @ %.2f".format(Locale.ENGLISH, fill)) }
        if (closedRefs.containsKey(a.ref)) nudge()
    }

    private fun stopWords(p: Pos): String = when (p.stopKind) { SAFETY -> "safety stop"; DISASTER -> "disaster stop"; else -> "stop" }

    /** A shadow entry: what would have been sent, priced as the live mirror fills it (a LIMIT that would not fill: noted, not booked). */
    private suspend fun enterShadow(spec: LiveMirror.Spec, a: Ask, base: Pos, o: Kite.Order) {
        val px = base.entry
        if (o.orderType == "LIMIT" && o.price != null && (if (base.side > 0) px > o.price!! + 1e-9 else px < o.price!! - 1e-9)) {
            notMirrored(a, "shadow: its LIMIT %.2f would not have filled at %.2f".format(Locale.ENGLISH, o.price, px)); return
        }
        var p = base.copy(shadow = true, heldSeen = true, charges = charge(if (base.side > 0) "BUY" else "SELL", px, base.qty, base.exchange, base.symbol, base.multiplier))
        val (stop, kind) = firstStop(spec, p)
        p = p.copy(stopTrigger = stop, stopKind = kind)
        lock.withLock {
            val b = book()
            b.positions += p
            if (b.shadowSince[a.key] == null) b.shadowSince[a.key] = now()
            line(b, "%s: SHADOW %s %s %d @ %.2f (nothing sent)%s".format(Locale.ENGLISH, spec.label, if (p.side > 0) "would buy" else "would sell",
                p.symbol, p.qty, px, stop?.let { " · %s %.2f".format(Locale.ENGLISH, stopWords(p), it) } ?: ""))
            save(b)
        }
        note(a.key, "shadow entry ${p.symbol} ${p.qty} @ %.2f (nothing sent)".format(Locale.ENGLISH, px))
    }

    /** Whether [ref] is open in its paper book now (null: that book not read). */
    private fun paperOpen(spec: LiveMirror.Spec, ref: String): Boolean? = hosts[spec.family]?.open()?.any { it.ref == ref && it.qty > 0 }

    /** The paper trade's stop or lock as the live level on [p]'s fill (null: none; never for a close-judged arm). */
    private fun wanted(p: Pos): Double? {
        val spec = LiveMirror.spec(p.key) ?: return null
        if (spec.closeStop) return null
        val lv = levelOf(p.ref) ?: return null
        return LiveMirror.level(p.paperEntry, lv, p.entry, spec.scale, p.side, p.tick)
    }

    // ---- the stop at Zerodha -------------------------------------------------------------------------------------------------

    private fun gttLeg(trigger: Double, tick: Double, side: Int): Double =
        Kite.onTick(if (side > 0) trigger * 0.95 else trigger * 1.05, tick, if (side > 0) Kite.Side.SELL else Kite.Side.BUY).coerceAtLeast(tick)

    /**
     * [p]'s stop rested at Zerodha at [at] (with the price at [ltp]): an SL order with its limit for an intraday trade, a single-leg
     * GTT for a carried one - only while the exits already working on the contract plus this one fit in what Zerodha holds. [p] with
     * its order and trigger, or with the trigger only (the app watches it, and rests it again on the next look - said, throttled).
     */
    private suspend fun restStop(p: Pos, at: Double?, ltp: Double): Pos {
        val spec = LiveMirror.spec(p.key) ?: return p
        val trigger = at ?: return p
        if (!LiveMirror.mayRest(trigger, ltp, p.tick, p.side)) return p.copy(stopTrigger = trigger)
        val exitSide = if (p.side > 0) Kite.Side.SELL else Kite.Side.BUY
        val covered = runCatching {
            Broker.orders().filter { it.working && it.symbol == p.symbol && it.side == exitSide.name && it.product == p.product }
                .sumOf { ExitQty.remaining(it.qty, it.filled, it.pending) }
        }.getOrNull()
        val heldNow = runCatching {
            Broker.positionBook().net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty } * p.side
        }.getOrNull()
        if (covered == null || heldNow == null) { stopFailed(p, trigger, "Zerodha's books could not be read"); return p.copy(stopTrigger = trigger) }
        val room = ((heldNow - covered).coerceAtLeast(0) / p.lot.coerceAtLeast(1)) * p.lot.coerceAtLeast(1)
        val q = minOf(p.qty, room)
        val seen = if (heldNow > 0) p.copy(heldSeen = true, zeroSinceMs = null) else p
        // Not held yet (the position book lags) or another exit already covers it: watched by the app, rested on a later look - and
        // said (throttled) when it is not yet held.
        if (q <= 0) { if (heldNow <= 0) stopFailed(p, trigger, "Zerodha does not show it held yet") ; return seen.copy(stopTrigger = trigger) }
        if (spec.rest == LiveMirror.Rest.GTT) {
            val sp = Kite.Spec(p.exchange, p.symbol, 0L, p.lot, p.tick)
            val (gtt, why) = Kite.protect(sp, p.product, q * p.side, ltp, trigger, null)
            if (gtt == null) { stopFailed(p, trigger, why.joinToString("; ")); return seen.copy(stopTrigger = trigger) }
            val id = runCatching { Broker.placeGtt(gtt) }.getOrElse { e -> stopFailed(p, trigger, e.message?.take(160) ?: "an error"); return seen.copy(stopTrigger = trigger) }
            return seen.copy(stopOrderId = GTT + id, stopTrigger = trigger, stopQty = q)
        }
        val o = Kite.Order(p.symbol, exitSide, q, p.lot, p.product, "SL", LiveMirror.stopLimit(trigger, p.tick, p.side), p.tick, p.exchange,
            LiveMirror.TAG, triggerPrice = trigger, multiplier = p.multiplier)
        val id = try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
            if (Broker.definite(e)) null else runCatching { Broker.findRecentRetrying(o, known()) }.getOrNull()
        }
        if (id == null) { stopFailed(p, trigger, "Zerodha refused it"); return seen.copy(stopTrigger = trigger) }
        runCatching { Strategies.tagOwner("kite:$id", "${spec.label} · live ${stopWords(p)}") }
        return seen.copy(stopOrderId = id, stopTrigger = trigger, stopQty = q)
    }

    private fun stopFailed(p: Pos, trigger: Double, why: String) {
        com.optionslab.app.work.Alerts.post("${p.label}: the live ${stopWords(p)} at %.2f could not rest at Zerodha (%s); the app watches it and tries again."
            .format(Locale.ENGLISH, trigger, why), com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
    }

    /** [p]'s resting stop moved to [to] (a modify of the same SL order or GTT: never a cancel and a new one, never down). */
    private suspend fun moveStop(p: Pos, so: Broker.OrderRow?, to: Double, ltp: Double): Pos {
        val r = runCatching {
            val gid = p.gttId
            if (gid != null) {
                val (gtt, why) = Kite.protect(Kite.Spec(p.exchange, p.symbol, 0L, p.lot, p.tick), p.product, (p.stopQty ?: p.qty) * p.side, ltp, to, null)
                requireNotNull(gtt) { why.joinToString("; ") }
                Broker.modifyGtt(gid, gtt)
            } else {
                val o = requireNotNull(so) { "the stop order was not read" }
                Broker.modify(o, o.qty, "SL", LiveMirror.stopLimit(to, p.tick, p.side), to)
            }
        }
        // Moved to the paper trade's own level: it is the arm's stop now (a safety stop that moved up is labelled as the arm's).
        if (r.isSuccess) return p.copy(stopTrigger = to, stopKind = if (p.stopKind == DISASTER) DISASTER else OWN)
        com.optionslab.app.work.Alerts.post("${p.label}: Zerodha did not move the live stop on ${p.symbol} to %.2f (%s); it stays at %s and is tried again."
            .format(Locale.ENGLISH, to, r.exceptionOrNull()?.message?.take(160) ?: "an error", p.stopTrigger?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none"),
            com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
        return p
    }

    /** [p]'s resting stop shrunk to [qty] still to sell (0: taken out, confirmed). [p] as it then stands, or null when not done. */
    private suspend fun shrinkStop(p: Pos, so: Broker.OrderRow?, qty: Int, ltp: Double?): Pos? {
        if (qty <= 0) {
            val out = stopOut(p) ?: return null
            if (out.first == "COMPLETE") return null           // it filled: the next look books it
            return p.copy(stopOrderId = null, stopQty = null)
        }
        val r = runCatching {
            val gid = p.gttId
            if (gid != null) {
                val px = ltp ?: error("no price to rest the GTT against")
                val (gtt, why) = Kite.protect(Kite.Spec(p.exchange, p.symbol, 0L, p.lot, p.tick), p.product, qty * p.side, px, p.stopTrigger ?: error("no trigger"), null)
                Broker.modifyGtt(gid, requireNotNull(gtt) { why.joinToString("; ") })
            } else {
                val o = requireNotNull(so) { "the stop order was not read" }
                Broker.modify(o, o.filled + qty, o.type, o.price.takeIf { it > 0 }, o.trigger.takeIf { it > 0 })
            }
        }
        if (r.isSuccess) {
            runCatching { Diag.record("armlive", "${p.label}: its stop on ${p.symbol} shrunk to $qty (Zerodha holds less, or another exit covers it)") }
            return p.copy(stopQty = qty)
        }
        return null
    }

    /**
     * [p]'s resting stop taken out (cancelled, or the GTT deleted; a fired GTT's own orders cancelled) and confirmed out. (state, fill):
     * COMPLETE with its fill when the stop had filled meanwhile; null when it could not be confirmed out (nothing else may be sold
     * then: both could fill).
     */
    private suspend fun stopOut(p: Pos): Pair<String, Broker.Fill?>? {
        p.gttId?.let { gid ->
            val g = runCatching { Broker.gtts().firstOrNull { it.id == gid } }.getOrElse { return null }
            if (g == null || g.status.lowercase() in setOf("deleted", "cancelled", "expired", "rejected", "disabled")) return "CANCELLED" to null
            if (g.status.lowercase() == "triggered") {
                // It fired: the orders it put in are the stop now. Working ones are cancelled and confirmed out; one that filled is the exit.
                for (oid in g.orderIds) {
                    var st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return null
                    if (st.status !in DONE) { runCatching { Broker.cancel(oid) }; st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return null }
                    if (st.status == "COMPLETE") return "COMPLETE" to st
                    if (st.status !in DONE) return null
                    if (st.filled > 0) return "COMPLETE" to st
                }
                return "CANCELLED" to null
            }
            runCatching { Broker.deleteGtt(gid) }
            val after = runCatching { Broker.gtts().firstOrNull { it.id == gid } }.getOrElse { return null }
            if (after?.status?.lowercase() == "triggered") return stopOut(p)
            return if (after == null || after.status.lowercase() != "active") "CANCELLED" to null else null
        }
        val id = p.slId ?: return "CANCELLED" to null
        val c0 = System.currentTimeMillis()
        runCatching { Broker.cancel(id) }
        val st = runCatching { Broker.orderState(id) }.getOrNull()
        runCatching { OrderTiming.stopCancel(System.currentTimeMillis() - c0) }
        // An SL order of an earlier session is gone with it (a DAY order): not in today's book, nothing of it can fill.
        if (st == null && p.day.isBefore(now().toLocalDate())) return "CANCELLED" to null
        return when (st?.status) {
            "COMPLETE" -> "COMPLETE" to st
            "CANCELLED", "REJECTED" -> if (st.filled > 0) "COMPLETE" to st else st.status to st
            else -> null
        }
    }

    // ---- exits ------------------------------------------------------------------------------------------------------------------

    /**
     * Sell [qty] of [p] ([why]) at the price [ltp]. A PART sale shrinks the resting stop to what is left (a modify: never cancelled,
     * so the rest is never without one); a whole sale takes it out first (one that filled meanwhile is the exit). Then a MARKET
     * order for what Zerodha holds less what is already on its way out. A sale that does not go (or does not fill) puts the stop back
     * in the same call. The closed part, and the rest still open (or null), to book.
     */
    private suspend fun exitLive(p: Pos, qty: Int, why: String, ltp: Double?): Pair<Pos, Pos?> {
        runCatching { OrderTiming.decided() }
        val part = qty < p.qty
        var cur = p
        if (part && p.stopOrderId != null) {
            val left = p.qty - qty
            val so = p.slId?.let { id -> runCatching { Broker.orders().firstOrNull { it.id == id } }.getOrNull() }
            cur = shrinkStop(p, so, left, ltp) ?: return p to null          // not shrunk: tried again next look
        } else if (!part) {
            val out = stopOut(p) ?: return p to null                       // not confirmed out: tried again next look
            if (out.first == "COMPLETE") {
                val f = out.second
                val px = f?.avgPrice?.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                val exitSide = if (p.side > 0) "SELL" else "BUY"
                val sold = minOf(f?.filled?.takeIf { it > 0 } ?: p.qty, p.qty)
                runCatching { Notifier.orderFilled(app, exitSide, sold, p.symbol, px, "Live", "${p.label} · live ${stopWords(p)}", f?.orderId) }
                return closePart(p, sold, px, p.stopWord, exitSide)
            }
            cur = p.copy(stopOrderId = null, stopQty = null)
        }
        val exitSide = if (p.side > 0) Kite.Side.SELL else Kite.Side.BUY
        suspend fun unsold(): Pair<Pos, Pos?> =
            (if (cur.stopOrderId == null && ltp != null) restStop(cur, cur.stopTrigger, ltp) else cur) to null
        val working = runCatching { Broker.orders().filter { it.working && it.symbol == p.symbol && it.side == exitSide.name && it.product == p.product } }.getOrNull()
            ?: return unsold()
        val held = runCatching { Broker.positionBook().net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty } }.getOrNull()
            ?: return unsold()
        val mine = if (p.side > 0) held else -held
        val sendable = ExitQty.sendable(mine.coerceAtLeast(0), working.sumOf { ExitQty.remaining(it.qty, it.filled, it.pending) }, minOf(qty, p.qty), p.lot)
        if (sendable <= 0) return unsold()
        val o = Kite.Order(p.symbol, exitSide, sendable, p.lot, p.product, "MARKET", null, p.tick, p.exchange, LiveMirror.TAG, multiplier = p.multiplier)
        val bad = Kite.refusals(o, AppSettings.load().limits(), Broker.sentToday(), false, exit = true)
        if (bad.isNotEmpty()) {
            com.optionslab.app.work.Alerts.error("${p.label}: the live exit was not sent (${bad.joinToString("; ")}). Close ${p.symbol} in Trade.", WHO)
            return unsold()
        }
        val id = try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
            val found = if (Broker.definite(e)) null else runCatching { Broker.findRecentRetrying(o, known() + working.map { it.id }) }.getOrNull()
            found ?: run {
                com.optionslab.app.work.Alerts.post("${p.label}: the live exit failed (${e.message?.take(160)}); retrying on the next look.",
                    com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
                // Refused outright: the stop goes back now. A lost reply with no order found may still have gone: next look.
                return if (Broker.definite(e)) unsold() else cur to null
            }
        }
        runCatching { Strategies.tagOwner("kite:$id", "${p.label} · live $why") }
        var f = runCatching { Broker.awaitOrder(id, 15_000) }.getOrNull()
        if (f?.status !in DONE) { runCatching { Broker.cancel(id) }; f = runCatching { Broker.orderState(id) }.getOrNull() ?: f }
        if (f == null || f.filled <= 0) return if (f != null && f.status in DONE) unsold() else cur to null
        val px = f.avgPrice.takeIf { it > 0 } ?: p.entry
        runCatching { Notifier.orderFilled(app, exitSide.name, f.filled, p.symbol, px, "Live", "${p.label} · live $why", f.orderId) }
        val (done, rest) = closePart(cur, minOf(f.filled, cur.qty), px, why, exitSide.name)
        // The rest of a whole sale that only part filled: its stop rests again at once.
        val rest2 = if (rest != null && rest.stopOrderId == null && ltp != null) restStop(rest, rest.stopTrigger, ltp) else rest
        return done to rest2
    }

    /** [sold] of [p] closed at [px] ([why]): the closed part, and the rest still open (null when all of it). */
    private fun closePart(p: Pos, sold: Int, px: Double, why: String, exitSide: String): Pair<Pos, Pos?> {
        val share = sold.toDouble() / p.qty
        val closedPart = p.copy(qty = sold, exit = px, exitTime = now(), why = why, stopOrderId = if (sold >= p.qty) null else p.stopOrderId,
            charges = p.charges * share + charge(exitSide, px, sold, p.exchange, p.symbol, p.multiplier))
        val rest = if (sold >= p.qty) null else p.copy(qty = p.qty - sold, charges = p.charges * (1 - share))
        return closedPart.copy(stopOrderId = null, stopQty = null) to rest
    }

    // ---- the watch ----------------------------------------------------------------------------------------------------------------

    /**
     * One look at every open live trade (the fast path's minute lane, the 15-second stop check, a paper exit's nudge) and, on a
     * regular look, every shadow trade. [stream]: prices from the stream's fresh ticks where it has them (the live lane). A worker
     * thread; never the tick path. Nothing open: returns at once (memory only).
     */
    suspend fun watch(stream: Boolean = false) {
        if (!ready()) return
        if (cache == null) {
            val ok = runCatching { lock.withLock { book() } }.isSuccess
            if (!ok) return
        }
        prune()
        if (!holdingHint && !shadowHint && !recoverDue) return
        if (!opLock.tryLock()) return                 // a look already running does this one's work
        try {
            if (recoverDue && Broker.loggedIn) runCatching { recover() }
            if (holdingHint) look(stream)
            if (!stream && shadowHint) runCatching { lookShadow() }
        } finally { opLock.unlock() }
    }

    /** Each paper book's legs as last read by a regular look, for the stream looks (memory only). */
    private val legsKept = java.util.concurrent.ConcurrentHashMap<String, List<PaperLeg>>()
    /** The last GTT list read (when, by id), for the stream looks. */
    @Volatile private var gttsKept: Pair<Long, Map<Long, Broker.GttRow>>? = null

    private fun legsOf(spec: LiveMirror.Spec?, stream: Boolean): List<PaperLeg>? = spec?.let { sp ->
        if (stream) legsKept[sp.family] else hosts[sp.family]?.open()?.also { legsKept[sp.family] = it }
    }

    /** What the paper trade [p] follows says now: whether it is gone, and how much of the live trade to sell for it. */
    private fun paperSide(p: Pos, legs: List<PaperLeg>?): Triple<Pos, Int, String?> {
        val leg = legs?.firstOrNull { it.ref == p.ref }
        var q = p
        if (leg != null && leg.qty > 0 && !q.seen) q = q.copy(seen = true)
        val gone = closedRefs.containsKey(p.ref) || LiveMirror.paperGone(q.seen, legs?.let { leg != null }) || leg?.qty == 0
        val sell = if (gone) q.qty else leg?.let { LiveMirror.toSell(q.qty, q.entryQty, q.paperQty, it.qty, q.lot) } ?: 0
        val why = closedWhy(p.ref) ?: if (gone) "paper_exit" else if (sell > 0) "paper_part" else null
        return Triple(q, sell, why)
    }

    private suspend fun look(stream: Boolean) {
        val open = lock.withLock { book().positions.filter { it.open && !it.shadow }.toList() }
        if (open.isEmpty() || !Broker.loggedIn) return
        val t = now()
        val ms = nowMs()
        val orders = (if (stream) runCatching { Broker.ordersRecon() } else runCatching { Broker.orders() }).getOrNull()?.associateBy { it.id } ?: return
        val net = (if (stream) runCatching { Broker.positionsRecon().net } else runCatching { Broker.positionBook().net }).getOrNull() ?: return
        val keys = open.map { "${it.exchange}:${it.symbol}" }.distinct()
        val q = runCatching { quotes(keys, stream) }.getOrDefault(emptyMap())
        // The GTT list: read on a regular look; a stream look (the live lane, every tick) uses the last one read within 15 s.
        val gtts = if (open.none { it.gttId != null }) emptyMap() else gttsKept?.takeIf { stream && System.currentTimeMillis() - it.first < 15_000L }?.second
            ?: runCatching { Broker.gtts().associateBy { it.id } }.getOrNull()?.also { gttsKept = System.currentTimeMillis() to it }
        val stopped = runCatching { Strategies.stoppedToday() }.getOrDefault(false)
        val updates = LinkedHashMap<Pos, List<Pos>>()
        fun cur(p: Pos): Pos? = updates[p]?.let { it.firstOrNull { x -> x.open } } ?: if (p in updates) null else p
        // What [p0] became this look: the parts already closed earlier in the look are kept (a part sold by hand, a part-filled stop),
        // the open part replaced by [next].
        fun set(p0: Pos, next: List<Pos>) { updates[p0] = updates[p0].orEmpty().filter { !it.open } + next }
        fun ltpOf(p: Pos) = q["${p.exchange}:${p.symbol}"]?.last?.takeIf { it > 0 }
        fun heldOf(p: Pos) = net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty } * p.side

        // 1. Each trade's own news: an unconfirmed entry settled; its stop filled, refused or gone.
        for (p0 in open) {
            try {
                if (p0.unconfirmed) { set(p0, listOfNotNull(settle(p0))); continue }
                var p = p0
                val so = p.slId?.let { orders[it] }
                val g = p.gttId?.let { gtts?.get(it) }
                if (so != null && so.status == "COMPLETE") {
                    val px = so.avg.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                    val side = if (p.side > 0) "SELL" else "BUY"
                    val sold = minOf(so.filled.takeIf { it > 0 } ?: p.qty, p.qty)
                    runCatching { Notifier.orderFilled(app, side, sold, p.symbol, px, "Live", "${p.label} · live ${stopWords(p)}", p.slId) }
                    val (done, rest) = closePart(p, sold, px, p.stopWord, side)
                    set(p0, listOfNotNull(done, rest)); continue
                }
                if (so != null && so.status in setOf("REJECTED", "CANCELLED")) {
                    com.optionslab.app.work.Alerts.post("${p.label}: the live stop at Zerodha was ${so.status.lowercase()} (${so.message.ifBlank { "no reason given" }}); " +
                        "the app watches it and rests it again.", com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
                    p = p.copy(stopOrderId = null, stopQty = null)
                }
                if (p.slId != null && so == null && p.day.isBefore(t.toLocalDate())) p = p.copy(stopOrderId = null, stopQty = null)
                if (p.gttId != null && gtts != null && (g == null || g.status.lowercase() in setOf("deleted", "cancelled", "expired", "rejected", "disabled")))
                    p = p.copy(stopOrderId = null, stopQty = null)
                if (g?.status?.lowercase() == "triggered") {
                    // A fired GTT: its own orders are the stop. All filled: that was the stop; refused or cancelled: rested again.
                    val st = g.orderIds.mapNotNull { orders[it] }
                    if (st.isNotEmpty() && st.all { it.status == "COMPLETE" }) {
                        val px = st.first().avg.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                        val side = if (p.side > 0) "SELL" else "BUY"
                        val sold = minOf(st.sumOf { it.filled }.takeIf { it > 0 } ?: p.qty, p.qty)
                        runCatching { Notifier.orderFilled(app, side, sold, p.symbol, px, "Live", "${p.label} · live ${stopWords(p)}", st.first().id) }
                        val (done, rest) = closePart(p, sold, px, p.stopWord, side)
                        set(p0, listOfNotNull(done, rest)); continue
                    }
                    if (st.isNotEmpty() && st.none { it.working }) p = p.copy(stopOrderId = null, stopQty = null)
                }
                if (p != p0) set(p0, listOf(p))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) { note(p0.key, "live look failed on ${p0.symbol}: ${e.javaClass.simpleName}") }
        }

        // 2. Each contract: what Zerodha holds against what these trades think they hold. Seen held; a part sold elsewhere is the
        //    oldest trade's first (the paper rule); all gone - closed outside, only once seen held (or zero past the grace).
        for ((_, group) in open.groupBy { it.contract }) {
            val live = group.mapNotNull { cur(it)?.let { c -> it to c } }.sortedBy { it.second.entryTime }
            if (live.isEmpty()) continue
            val held = heldOf(live.first().second)
            for ((p0, p) in live) {
                val seen = if (held > 0) p.copy(heldSeen = true, zeroSinceMs = null)
                    else if (!p.heldSeen && p.zeroSinceMs == null) p.copy(zeroSinceMs = ms) else p
                if (seen != p) set(p0, listOf(seen))
            }
            var excess = live.sumOf { (cur(it.first) ?: it.second).qty } - held.coerceAtLeast(0)
            if (excess <= 0) continue
            for ((p0, _) in live) {
                if (excess <= 0) break
                val p = cur(p0) ?: continue
                if (!(p.heldSeen || LiveMirror.zeroLongEnough(p.zeroSinceMs, ms))) continue
                val take = minOf(p.qty, excess)
                if (take >= p.qty) {
                    // Its stop is confirmed out first: left resting, it would sell into a short. One that filled was the exit.
                    val out = if (p.stopOrderId != null) stopOut(p) else ("CANCELLED" to null)
                    if (out == null) continue
                    val side = if (p.side > 0) "SELL" else "BUY"
                    val filled = out.first == "COMPLETE"
                    val px = (if (filled) out.second?.avgPrice?.takeIf { it > 0 } ?: p.stopTrigger else null) ?: ltpOf(p) ?: p.entry
                    if (filled) runCatching { Notifier.orderFilled(app, side, p.qty, p.symbol, px, "Live", "${p.label} · live ${stopWords(p)}", p.slId) }
                    set(p0, listOf(closePart(p, p.qty, px, if (filled) p.stopWord else "closed_at_zerodha", side).first))
                } else {
                    val (done, rest) = closePart(p, take, ltpOf(p) ?: p.entry, "closed_at_zerodha", if (p.side > 0) "SELL" else "BUY")
                    set(p0, listOfNotNull(done, rest))
                }
                excess -= take
            }
        }

        // 3. Each trade's decision, newest first on each contract, against the exits working on it (kept up to date as they change).
        val working = HashMap<String, Int>()     // order id -> remaining, the exits working on these contracts
        for (o in orders.values) if (o.working) working[o.id] = ExitQty.remaining(o.qty, o.filled, o.pending)
        for ((_, group) in open.groupBy { it.contract }) {
            for (p0 in group.sortedByDescending { it.entryTime }) {
                val p1 = cur(p0) ?: continue
                try {
                    val spec = LiveMirror.spec(p1.key)
                    val (p, paperSell, paperWhy) = paperSide(p1, legsOf(spec, stream))
                    val so = p.slId?.let { orders[it] }
                    val g = p.gttId?.let { gtts?.get(it) }
                    val exitSide = if (p.side > 0) "SELL" else "BUY"
                    val gttOrders = g?.orderIds.orEmpty().toSet()
                    val others = orders.values.filter { it.working && it.symbol == p.symbol && it.side == exitSide && it.product == p.product &&
                        it.id != p.slId && it.id !in gttOrders }.sumOf { working[it.id] ?: 0 }
                    val stopQty = when {
                        p.slId != null -> so?.let { working[it.id] }
                        p.gttId != null && g?.status?.lowercase() == "active" -> p.stopQty ?: p.qty
                        else -> null
                    }
                    val ltp = ltpOf(p)
                    val stopLimit = when {
                        p.gttId != null -> g?.legPrices?.firstOrNull()?.takeIf { it > 0 } ?: p.stopTrigger?.let { gttLeg(it, p.tick, p.side) }
                        else -> so?.price?.takeIf { it > 0 }
                    }
                    val look = LiveMirror.Look(side = p.side, qty = p.qty, held = heldOf(p) * p.side, ltp = ltp, tick = p.tick, stopTrigger = p.stopTrigger,
                        stopResting = p.stopOrderId != null, wanted = wanted(p), paperSell = paperSell, paperReason = paperWhy,
                        squareOff = LiveMirror.squareOffDue(t, p.exchange, p.product, p.expiry, p.day), operatorStop = stopped,
                        heldSeen = true, stopQty = stopQty, otherWorking = others, lot = p.lot, stopLimit = stopLimit)
                    val d = LiveMirror.decide(look)
                    val next: List<Pos>? = when (d.step) {
                        LiveMirror.Step.SELL -> {
                            val why = if (d.why == "stop" || d.why == "stop_run_through") (if (p.stopKind == OWN) d.why!! else p.stopWord) else d.why ?: "exit"
                            exitLive(p, d.qty, why, ltp).toList().filterNotNull()
                        }
                        LiveMirror.Step.PLACE_STOP -> {
                            val w = wanted(p)
                            val kind = if (w != null && d.to == w && p.stopKind != DISASTER) OWN else p.stopKind
                            listOf(restStop(p.copy(stopKind = kind), d.to, ltp ?: continue))
                        }
                        LiveMirror.Step.MOVE_STOP -> listOf(moveStop(p, so, d.to ?: continue, ltp ?: continue))
                        LiveMirror.Step.SHRINK_STOP -> {
                            val s2 = shrinkStop(p, so, d.qty, ltp)
                            if (s2 != null && p.slId != null) working[p.slId!!] = d.qty
                            listOf(s2 ?: p)
                        }
                        else -> null
                    }
                    if (next != null) set(p0, next) else if (p != p1) set(p0, listOf(p))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Throwable) { note(p0.key, "live look failed on ${p0.symbol}: ${e.javaClass.simpleName}; tried again next look") }
            }
        }
        runCatching { WatchClock.stopsChecked() }
        if (updates.isEmpty()) return
        commit(updates)
    }

    /** The look's changes into the book (each old trade replaced by what it became), the closed ones logged once. */
    private suspend fun commit(updates: Map<Pos, List<Pos>>) = lock.withLock {
        val b = book()
        for ((old, now) in updates) {
            val i = b.positions.indexOfFirst { it == old }
            if (i < 0) continue
            b.positions.removeAt(i)
            b.positions.addAll(i, now)
            now.filter { !it.open }.forEach { c ->
                line(b, "%s: %s%s %s %d @ %.2f (%s), %s after charges".format(Locale.ENGLISH, c.label, if (c.shadow) "SHADOW " else "",
                    if (c.side > 0) "sold" else "bought back", c.symbol, c.qty, c.exit ?: 0.0, c.why, LiveMirror.rs(c.net ?: 0.0)))
                if (b.positions.none { it.open && it.ref == c.ref }) { closedRefs.remove(c.ref); levels.remove(c.ref) }
            }
        }
        save(b)
    }

    /**
     * The shadow trades' look (a regular one, never the live lane): each follows its paper trade and its stop exactly as a live trade
     * would - priced from Zerodha's quote (or the last seen) - and nothing is ever sent.
     */
    private suspend fun lookShadow() {
        val open = lock.withLock { book().positions.filter { it.open && it.shadow }.toList() }
        if (open.isEmpty()) return
        val t = now()
        val q = if (Broker.loggedIn) runCatching { Broker.stopQuotes(open.map { "${it.exchange}:${it.symbol}" }.distinct()) }.getOrDefault(emptyMap()) else emptyMap()
        val updates = LinkedHashMap<Pos, List<Pos>>()
        for (p0 in open) {
            val (p, paperSell, paperWhy) = paperSide(p0, legsOf(LiveMirror.spec(p0.key), false))
            val ltp = q["${p.exchange}:${p.symbol}"]?.last?.takeIf { it > 0 }?.also { shadowMarks[p.ref] = it } ?: shadowMarks[p.ref]
            val d = LiveMirror.decide(LiveMirror.Look(side = p.side, qty = p.qty, held = p.qty * p.side, ltp = ltp, tick = p.tick,
                stopTrigger = p.stopTrigger, stopResting = false, wanted = wanted(p), paperSell = paperSell, paperReason = paperWhy,
                squareOff = LiveMirror.squareOffDue(t, p.exchange, p.product, p.expiry, p.day), operatorStop = false))
            when (d.step) {
                LiveMirror.Step.SELL -> {
                    val stopExit = d.why == "stop"
                    val px = LiveMirror.shadowExit(stopExit, ltp ?: p.entry, p.stopTrigger, p.tick, p.side)
                    val (done, rest) = closePart(p, d.qty, px, if (stopExit) p.stopWord else d.why ?: "exit", if (p.side > 0) "SELL" else "BUY")
                    updates[p0] = listOfNotNull(done, rest)
                    if (rest == null) shadowMarks.remove(p.ref)
                }
                LiveMirror.Step.PLACE_STOP -> if (d.to != p.stopTrigger || p != p0) updates[p0] = listOf(p.copy(stopTrigger = d.to))
                else -> if (p != p0) updates[p0] = listOf(p)
            }
        }
        if (updates.isNotEmpty()) commit(updates)
    }

    private val shadowMarks = java.util.concurrent.ConcurrentHashMap<String, Double>()

    /** The prices of [keys]: the stream's fresh ticks with [stream], the rest Zerodha's (a tick only while 2 s fresh). */
    private suspend fun quotes(keys: List<String>, stream: Boolean): Map<String, Broker.Quote> {
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

    /** An entry Zerodha had not confirmed: settled from the order book (what filled, with its stop), or dropped (nothing filled). */
    private suspend fun settle(p: Pos): Pos? {
        val spec = LiveMirror.spec(p.key)
        if (p.day.isBefore(now().toLocalDate())) {
            // An intraday entry of an earlier day is gone with Zerodha's square-off. A carried one may still be held: what Zerodha
            // holds of it (never more than was asked) is adopted with its stop, so it is never left unmanaged.
            val held = if (p.product == "MIS") 0 else runCatching {
                Broker.positionBook().net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty } * p.side
            }.getOrElse { return p }
            if (held <= 0) {
                com.optionslab.app.work.Alerts.error("${p.label}: the live entry in ${p.symbol} on ${p.day} was never confirmed by Zerodha; check that day's contract note.", WHO)
                return null
            }
            val q = minOf(held, p.qty)
            val ltp = runCatching { Broker.stopQuotes(listOf("${p.exchange}:${p.symbol}"))["${p.exchange}:${p.symbol}"]?.last }.getOrNull()?.takeIf { it > 0 } ?: p.entry
            com.optionslab.app.work.Alerts.error("${p.label}: the live entry in ${p.symbol} on ${p.day} was never confirmed, but Zerodha holds $q: it is managed as this arm's trade.", WHO)
            val got = p.copy(qty = q, entryQty = q, unconfirmed = false, heldSeen = true)
            val (stop, kind) = spec?.let { firstStop(it, got) } ?: (null to OWN)
            return restStop(got.copy(stopKind = kind), stop, ltp)
        }
        val side = if (p.side > 0) "BUY" else "SELL"
        val oid = p.entryOrderId ?: run {
            val row = runCatching { Broker.latestTagged(p.symbol, side, LiveMirror.TAG, p.entryTime.minusMinutes(1), known()) }.getOrElse { return p } ?: return null
            runCatching { Strategies.tagOwner("kite:${row.id}", "${p.label} · live entry") }
            row.id
        }
        var st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return p.copy(entryOrderId = oid)
        if (st.status !in DONE) {
            runCatching { Broker.cancel(oid) }
            st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return p.copy(entryOrderId = oid)
            if (st.status !in DONE) return p.copy(entryOrderId = oid)
        }
        if (st.filled <= 0) return null
        val fill = st.avgPrice.takeIf { it > 0 } ?: p.entry
        val got = p.copy(qty = st.filled, entryQty = st.filled, entry = fill, entryOrderId = oid, unconfirmed = false,
            charges = charge(side, fill, st.filled, p.exchange, p.symbol, p.multiplier))
        val (stop, kind) = spec?.let { firstStop(it, got) } ?: (null to OWN)
        return restStop(got.copy(stopKind = kind), stop, ltp = fill)
    }

    /**
     * After a damaged book was rebuilt: the live trades of these arms still held at Zerodha are taken back from its positions and this
     * path's order tags (today's entries tagged [LiveMirror.TAG], their owner labels), each with a safety stop and the square-offs, so
     * none is left unwatched. A carried one from an earlier day has no entry in today's book: Boss is told to manage it.
     */
    private suspend fun recover() {
        val orders = runCatching { Broker.orders() }.getOrNull() ?: return
        val net = runCatching { Broker.positionBook().net }.getOrNull() ?: return
        val owners = runCatching { Strategies.owners() }.getOrDefault(emptyMap())
        val have = lock.withLock { book().positions.filter { it.open && !it.shadow }.map { it.contract }.toSet() }
        val adopted = ArrayList<Pos>()
        for (pos in net.filter { it.qty != 0 && it.exchange in setOf("NFO", "BFO", "MCX") }) {
            val entry = orders.filter { it.tag == LiveMirror.TAG && it.symbol == pos.symbol && it.product == pos.product &&
                it.status == "COMPLETE" && it.side == (if (pos.qty > 0) "BUY" else "SELL") }.maxByOrNull { it.placedAt } ?: continue
            if ("${pos.exchange}|${pos.symbol}|${pos.product}" in have) continue
            val label = owners["kite:${entry.id}"]?.substringBefore(" · ") ?: continue
            val spec = LiveMirror.ARMS.firstOrNull { it.label == label } ?: continue
            val tick = runCatching { Broker.spec(pos.exchange, pos.symbol).tickSize }.getOrDefault(0.05)
            val lot = runCatching { Broker.spec(pos.exchange, pos.symbol).lotSize }.getOrDefault(1).coerceAtLeast(1)
            val side = if (pos.qty > 0) 1 else -1
            val p = Pos(spec.key, "recovered:${entry.id}", pos.symbol, pos.exchange, pos.product, side, kotlin.math.abs(pos.qty), kotlin.math.abs(pos.qty), lot, tick,
                pos.multiplier.toInt().coerceAtLeast(1), pos.avg.takeIf { it > 0 } ?: entry.avg, now(), pos.avg, kotlin.math.abs(pos.qty), null, entry.id,
                seen = false, heldSeen = true, stopKind = SAFETY)
            // A stop of this path's own still resting on it is kept as its stop; else the safety stop rests.
            val so = orders.filter { it.working && it.tag == LiveMirror.TAG && it.symbol == pos.symbol && it.product == pos.product &&
                (it.type == "SL" || it.type == "SL-M") }.singleOrNull()
            adopted += if (so != null) p.copy(stopOrderId = so.id, stopTrigger = so.trigger, stopQty = ExitQty.remaining(so.qty, so.filled, so.pending), stopKind = OWN)
                else restStop(p, LiveMirror.safetyLevel(p.entry, side, tick), pos.last.takeIf { it > 0 } ?: p.entry)
        }
        lock.withLock {
            val b = book()
            b.positions += adopted
            adopted.forEach { line(b, "${it.label}: ${it.symbol} ${it.qty} taken back from Zerodha after the book was damaged") }
            save(b)
        }
        recoverDue = false
        if (adopted.isNotEmpty()) com.optionslab.app.work.Alerts.error("Arms live: ${adopted.size} open live trade(s) taken back from Zerodha after the " +
            "book was damaged; each has a safety stop and its square-off. Check them in Trade.", WHO)
    }

    /**
     * An arm's live state in words, from its "Trade live" [on] and its [shadow] (review D6: every line about an arm's live path is
     * built from these, never fixed text): what it does when the app is in Live with AI trades on.
     */
    fun stateWords(on: Boolean, shadow: Boolean): String = when {
        !on -> "Trade live: off (paper only)"
        shadow -> "Trade live: on, in shadow (records, sends nothing)"
        else -> "Trade live: on, out of shadow (real orders in Live)"
    }

    /** [key]'s live state in words from the book now (memory only); with the app in Paper it says so. */
    fun stateOf(key: String): String {
        val v = _view.value
        if (!v.loaded) return "live state not read yet"
        val live = runCatching { AppSettings.load().let { it.live && it.allowRealOrders } }.getOrDefault(false)
        return stateWords(v.on(key), v.shadowOn(key)) + if (live) "" else "; the app is in Paper, so nothing is sent or shadowed"
    }

    /**
     * Jarvis's answer to "how is <arm> doing in shadow?" (or live): its shadow record and its live record, labelled apart, and its
     * state. [query]: the arm's name as said; null when no arm matches. Memory only (never on a locked phone: the caller checks).
     */
    fun talk(query: String): String? {
        val q = query.lowercase(Locale.ENGLISH)
        val spec = LiveMirror.ARMS.filter { it.key != LiveMirror.SOLO_MIDDAY || com.optionslab.app.BuildConfig.JARVIS }
            .sortedByDescending { it.label.length }
            .firstOrNull { s -> q.contains(s.label.lowercase(Locale.ENGLISH)) || q.contains(s.key.lowercase(Locale.ENGLISH).replace('_', ' ')) ||
                s.label.lowercase(Locale.ENGLISH).split(' ', '(', ')').filter { it.length >= 4 && it !in TALK_STOP }.any { w -> Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(q) } }
            ?: return null
        return armLine(spec.key)
    }

    private val TALK_STOP = setOf("shadow", "variants", "re-armed", "live", "trade", "calls", "doing")

    /** One arm's records for Jarvis and the talk files: SHADOW record and LIVE record, labelled apart, then its state. Memory only. */
    fun armLine(key: String): String {
        val spec = LiveMirror.spec(key) ?: return "No such arm."
        val v = _view.value
        val sr = v.shadowRecord(key)
        val (n, net) = v.record(key)
        val (skipped, why) = v.notMirroredToday(key, runCatching { now().toLocalDate() }.getOrElse { LocalDate.now() })
        return "${spec.label} in shadow: ${LiveMirror.recordWords(sr)}" + (v.shadowSince[key]?.let { " since ${it.toLocalDate()}" } ?: "") +
            ". Live (real orders): " + (if (n == 0) "no live trade yet" else "$n trade${if (n == 1) "" else "s"}, ${LiveMirror.rs(net)} after charges") +
            ". Now: ${stateOf(key)}." + (if (skipped > 0) " Not mirrored today: $skipped (last: $why)." else "")
    }

    /** The diagnostics' line. Memory only. */
    fun diagLine(): String {
        val v = _view.value
        val off = LiveMirror.ARMS.filter { !v.on(it.key) }.map { it.label }
        val live = LiveMirror.ARMS.filter { !v.shadowOn(it.key) }.map { it.label }
        return "Arms live: ${v.openReal.size} open" + (if (v.openReal.isEmpty()) "" else " (" + v.openReal.joinToString { "${it.label} ${it.symbol} ${it.qty}" +
            if (it.stopOrderId != null) " ${stopWords(it)} at Zerodha" else " stop watched by the app" } + ")") +
            ", ${v.openShadow.size} shadow open, ${v.closed.size} closed live; Trade live off: " + (off.joinToString().ifEmpty { "none" }) +
            "; out of shadow: " + (live.joinToString().ifEmpty { "none" }) + (if (v.unreadable) "; BOOK UNREADABLE: no live entries" else "")
    }

    /** For tests: the book forgotten (its file goes with the test's directory) and the memory notes cleared. */
    internal fun wipe() {
        cache = null; unreadable = false; recoverDue = false; gttsKept = null; legsKept.clear(); shadowMarks.clear()
        _view.value = View(); holdingHint = false; shadowHint = false; liveKeysHint = emptyList(); levels.clear(); closedRefs.clear()
    }

    /** For tests: as after a restart - the memory gone, the book on disk. */
    internal fun forgetMemoryForTest() { cache = null; legsKept.clear(); gttsKept = null; levels.clear(); closedRefs.clear() }
}
