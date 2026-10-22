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
        /** Review fix: how much of the CURRENT stop order's fill is already booked closed (a stop that part filled is booked once). */
        val stopBooked: Int = 0,
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
    /**
     * 18 Oct (final review F1): when the last read of the book failed (memory). While it is unreadable every lane that watches
     * keeps coming back ([recoverHint]) and reads it again, at most every [RETRY_MS], so its open live trades are watched the
     * moment it reads.
     */
    @Volatile private var readFailedMs = 0L
    /** Reads failed in a row (memory): the wait before the next read doubles from [RETRY_MS] to [RETRY_MAX_MS]. */
    @Volatile private var readFails = 0
    private const val RETRY_MS = 5_000L
    private const val RETRY_MAX_MS = 60_000L

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
        testHeard?.add("entered|${ask.key}|${ask.ref}|${ask.paperQty}|${ask.stop}")
        if (!ready() || LiveMirror.spec(ask.key) == null) return
        ask.stop?.let { level(ask.ref, it) }
        // In Paper (the usual case) nothing more happens: no worker, no read. Final review F7: the Live switch from memory
        // ([liveFlags]), never a settings read on whatever lane the paper fill is on.
        if (!liveFlags().first) return
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
        testHeard?.add("level|$ref|$paperLevel")
        levels[ref] = (runCatching { now().toLocalDate() }.getOrElse { LocalDate.now() }) to paperLevel
        if (levels.size > NOTES_KEPT) prune()
    }

    /** The paper trade [ref] is closed ([why]): its live trade is sold on the next look, which is asked now (memory only). */
    fun closed(ref: String, why: String) {
        testHeard?.add("closed|$ref|$why")
        closedRefs[ref] = (runCatching { now().toLocalDate() }.getOrElse { LocalDate.now() }) to why
        // 22 Oct: the time machine's forward log learns how the trade ended (one queue add; the write runs on its own scope).
        runCatching { TimeMachineHost.settle(ref, why) }
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
        val bytes = try {
            testReadFails?.let { if (it.getAndDecrement() > 0) throw java.io.IOException("test: the book could not be read") }
            Vault.readFileSteady(f)
        } catch (e: Vault.Tampered) { null.also { tampered(f) } } catch (e: Exception) {
            // Not read now (a Keystore hiccup): nothing cached, read again by every lane ([recoverHint]); meanwhile no live entry goes.
            readFailedMs = runCatching { nowMs() }.getOrDefault(System.currentTimeMillis())
            readFails = (readFails + 1).coerceAtMost(30)
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
        if (unreadable) runCatching { Diag.record("armlive", "the live book was read again: its open live trades are watched") }
        unreadable = false
        readFails = 0
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
            p.optBoolean("heldSeen", !p.has("heldSeen")), if (p.has("zeroSince")) p.getLong("zeroSince") else null, p.optInt("stopBooked", 0))
    }

    private fun writePos(p: Pos): JSONObject = JSONObject().put("key", p.key).put("ref", p.ref).put("symbol", p.symbol).put("exchange", p.exchange)
        .put("product", p.product).put("side", p.side).put("qty", p.qty).put("entryQty", p.entryQty).put("lot", p.lot).put("tick", p.tick)
        .put("mult", p.multiplier).put("entry", p.entry).put("entryTime", p.entryTime.toString()).put("paperEntry", p.paperEntry)
        .put("paperQty", p.paperQty).put("expiry", p.expiry?.toString() ?: "").put("entryOrderId", p.entryOrderId ?: "")
        .put("stopOrderId", p.stopOrderId ?: "").apply { p.stopTrigger?.let { put("stopTrigger", it) }; p.exit?.let { put("exit", it) } }
        .put("seen", p.seen).put("unconfirmed", p.unconfirmed).put("exitTime", p.exitTime?.toString() ?: "").put("why", p.why ?: "")
        .put("charges", p.charges).put("shadow", p.shadow).put("stopKind", p.stopKind).put("heldSeen", p.heldSeen)
        .apply { p.stopQty?.let { put("stopQty", it) }; p.zeroSinceMs?.let { put("zeroSince", it) } }.put("stopBooked", p.stopBooked)

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
        // A damaged book: its open live trades are taken back from Zerodha first, before any other start-up work (open positions first).
        if (recoverDue && Broker.loggedIn) runCatching { watch() }
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
    /**
     * TEST ONLY (review D8, the hosts' wiring tests): what the paper hosts told this object, in order - "entered|key|ref|qty|stop",
     * "level|ref|x", "closed|ref|why". Null in the app, always; its setter throws unless BuildConfig.DEBUG.
     */
    @Volatile internal var testHeard: MutableList<String>? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** TEST ONLY: the next this many reads of the book fail as a Keystore hiccup would (final review F1). Debug builds only. */
    @Volatile internal var testReadFails: java.util.concurrent.atomic.AtomicInteger? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** TEST ONLY: the legs [family]'s registered paper book gives now (null: none registered, or not read). */
    internal fun legsForTest(family: String): List<PaperLeg>? = hosts[family]?.open()

    private fun now(): LocalDateTime = (testNow ?: Market.now()).toLocalDateTime().withNano(0)
    private fun nowMs(): Long = (testNow ?: Market.now()).toInstant().toEpochMilli()

    // ---- the Live switch in memory (final review F2, F7) ------------------------------------------------------------------------

    /** The kept (settings generation, Live generation, (app in Live with real orders, "AI trades go live")). */
    @Volatile private var liveKept: Triple<Int, Int, Pair<Boolean, Boolean>>? = null

    /**
     * (the app in Live with real orders allowed, "AI trades go live" on), from memory: read from the settings only after one of
     * them was written ([SecurePrefs.liveHint]) or the settings were reloaded ([SecurePrefs.generationHint]), as the entry door
     * keeps its kill switch. A read that fails says Paper (nothing goes), and is not kept.
     */
    /**
     * [liveFlags] as last read, never reading anything (19 Oct: the same-direction cap asks it on the order path); null when the
     * settings changed since or it was never read.
     */
    internal fun liveFlagsKept(): Pair<Boolean, Boolean>? =
        liveKept?.takeIf { it.first == SecurePrefs.generationHint && it.second == SecurePrefs.liveHint }?.third

    internal fun liveFlags(): Pair<Boolean, Boolean> {
        val g = SecurePrefs.generationHint
        val l = SecurePrefs.liveHint
        liveKept?.let { if (it.first == g && it.second == l) return it.third }
        // Only the keys behind it (never the whole settings): Live is the mode "live", which always allows real orders
        // ([AppSettings.load]'s allowRealOrders).
        val now = runCatching {
            val app = SecurePrefs.getString("k.mode", AppSettings().mode) == "live"
            app to (app && !com.optionslab.app.ira.IraNewsTrades.paperFirst)
        }.getOrElse { return false to false }
        liveKept = Triple(g, l, now)
        return now
    }

    /**
     * Final review F2: why a mirrored entry ([LiveMirror.TAG]) of arm [arm] ("arm:<key>") may not go NOW, asked by the entry door
     * at the last moment ([Broker.entryRecheck]) - an entry can wait up to 15 s there after [gate] ran. The app in Live, "AI trades
     * go live", the arm's own Trade live and its Shadow, all from memory (the kept settings and this object's view): no disk, no
     * lock. Null: it may go.
     */
    internal fun doorRefusal(arm: String?): String? {
        val key = arm?.takeIf { it.startsWith("arm:") }?.removePrefix("arm:") ?: return "refused: a mirrored entry with no arm"
        val label = LiveMirror.spec(key)?.label ?: return "refused: no such mirrored arm"
        val (appLive, aiLive) = liveFlags()
        if (!appLive) return "refused: the app is in Paper"
        if (!aiLive) return "refused: \"AI trades go live\" is off"
        val v = _view.value
        if (!v.loaded || v.unreadable) return "refused: the arms' live book is not read"
        if (!v.on(key)) return "refused: $label's \"Trade live\" switch is off"
        if (v.shadowOn(key)) return "refused: $label is in shadow"
        return null
    }

    // ---- entries ---------------------------------------------------------------------------------------------------------------

    /**
     * The gate's answers now, in [LiveMirror.refusal]'s order, read only as far as needed: in Paper, with AI trades off or the arm's
     * own switch off nothing else is read. [shadow]: for a shadow trade the paper-only period does not refuse it (nothing is sent).
     */
    private suspend fun gate(key: String, shadow: Boolean): LiveMirror.Gate {
        val s = AppSettings.load()
        liveFlags()                                   // kept warm for the entry door's own look ([doorRefusal])
        val appLive = s.live && s.allowRealOrders
        val aiLive = appLive && runCatching { !com.optionslab.app.ira.IraNewsTrades.paperFirst }.getOrDefault(false)
        val armOn = lock.withLock { book().switches.toMap() }.let { sw -> if (testDefaultOff && key !in sw) false else LiveMirror.on(key, sw) }
        val first = LiveMirror.Gate(appLive = appLive, aiLive = aiLive, armOn = armOn)
        if (LiveMirror.refusal(first) != null) return first
        // Final review F3: a check that cannot be read refuses the entry (an entry never goes on a guess), as the day stop does.
        val g = first.copy(paperOnly = if (shadow) null else PaperOnly.entryRefusal(), kill = s.guardKill,
            stopped = runCatching { Strategies.stoppedToday() }.getOrDefault(true),
            lossLimit = runCatching { LossBreaker.liveDoorTripped() }.getOrDefault(true))
        if (LiveMirror.refusal(g) != null) return g
        val g2 = g.copy(dayLock = runCatching { DayLockGuard.refusal(true) }.getOrElse { "the day lock could not be checked" },
            accountStop = if (shadow) null else accountStop(), loggedIn = Broker.loggedIn)
        if (LiveMirror.refusal(g2) != null) return g2
        return g2.copy(compromised = !shadow && runCatching {
            if (testSecurityFails) throw java.io.IOException("test: the security check could not run")
            com.optionslab.app.security.Integrity.compromised(com.optionslab.app.security.Integrity.reportWithin(app, 60_000))
        }.getOrDefault(true))
    }

    /**
     * The account stop rule (Boss's: -Rs 40,000 from the best, or -Rs 13,000 in a calendar month) over the app's whole live record of
     * the arms - every real trade of these arms (all are kept) and the ORB family's - or null while neither is reached. Final review
     * F3: a record that cannot be read (this book's or the ORB family's) refuses the entry: [ACCOUNT_UNCHECKED].
     */
    private suspend fun accountStop(): String? = runCatching {
        val mine = lock.withLock { book().positions.filter { !it.open && !it.shadow } }.mapNotNull { p ->
            val t = p.exitTime ?: return@mapNotNull null
            com.optionslab.ira.LiquidityStopRule.Trade(t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli(), t.toLocalDate(), p.net ?: 0.0)
        }
        val orb = (if (testAccountFails) throw java.io.IOException("test: the ORB record could not be read") else OrbArms.closedLive()).mapNotNull { p ->
            val t = p.exitTime ?: return@mapNotNull null
            val net = p.exit?.let { (it - p.entry) * p.qty - p.charges } ?: return@mapNotNull null
            com.optionslab.ira.LiquidityStopRule.Trade(t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli(), t.toLocalDate(), net)
        }
        val st = com.optionslab.ira.LiquidityStopRule.check(mine + orb, now().toLocalDate())
        if (st.stopped) st.why else null
    }.getOrElse { ACCOUNT_UNCHECKED }

    /** The account stop rule's refusal when its record could not be read (final review F3). */
    internal const val ACCOUNT_UNCHECKED = "the account stop could not be checked"

    /** TEST ONLY: the phone's security check fails to run (final review F3). Debug builds only. */
    @Volatile internal var testSecurityFails: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** TEST ONLY: the ORB family's live record fails to read (final review F3). Debug builds only. */
    @Volatile internal var testAccountFails: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

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
        // Final review F5: Solo (midday) is in shadow while it is not proven, whatever its stored switch says.
        val soloHeld = a.key == LiveMirror.SOLO_MIDDAY && soloWhy() != null
        val shadowed = soloHeld || try { lock.withLock { val b = book(); LiveMirror.shadowOn(a.key, b.shadow) } } catch (e: Unreadable) {
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
        val s = AppSettings.load()
        // 21 Oct (review): an arm with [LiveMirror.Spec.capLots] (FT3: Rs 5,000 of a Rs 6-10 option, 3-41 lots by index, which the
        // order gates would refuse whole) goes live at the Bot settings' lot cap (all its lots with the cap off), said on its own
        // entry line; every other arm keeps its paper lots.
        val qty = LiveMirror.liveQty(lots, ins.lot, if (spec.capLots) s.guardMaxLots else 0)
        if (qty <= 0) { notMirrored(a, "the paper trade is under one lot"); return@withLock }
        val cut = LiveMirror.cutWords(lots, qty, ins.lot)
        val qkey = "${ins.exchange}:${ins.symbol}"
        val quote = runCatching { Broker.stopQuotes(listOf(qkey))[qkey] }.getOrNull()
        val last = quote?.last?.takeIf { it > 0 } ?: run { notMirrored(a, "no Zerodha price for ${ins.symbol}"); return@withLock }
        // Never an entry against what Zerodha holds the other way on this contract (two arms on one future, opposite ways).
        val net = runCatching { Broker.positionBook().net.filter { it.symbol == ins.symbol && it.exchange == ins.exchange && it.product == spec.product }.sumOf { it.qty } }
            .getOrElse { notMirrored(a, "Zerodha's positions could not be read"); return@withLock }
        LiveMirror.againstNet(a.side, net)?.let { notMirrored(a, it); return@withLock }
        val side = if (a.side >= 0) Kite.Side.BUY else Kite.Side.SELL
        // The paper arm's own order type: a LIMIT entry pays at most its paper limit (never chases), else MARKET.
        val o = if (a.limit != null) Kite.Order(ins.symbol, side, qty, ins.lot, spec.product, "LIMIT",
                Kite.onTick(a.limit, ins.tick, side), ins.tick, ins.exchange, LiveMirror.TAG, multiplier = ins.multiplier)
            else Kite.Order(ins.symbol, side, qty, ins.lot, spec.product, "MARKET", null, ins.tick, ins.exchange, LiveMirror.TAG, multiplier = ins.multiplier)
        val acct = runCatching { Broker.accountNow(needed = last * qty * ins.multiplier) }.getOrNull()
        Guard.check(Guard.liveOrder(o).copy(price = last), acct).takeIf { it.isNotEmpty() }?.let { notMirrored(a, "account guard: " + it.joinToString(" ")); return@withLock }
        Kite.refusals(o, s.limits(), Broker.sentToday(), false, refPrice = last).takeIf { it.isNotEmpty() }?.let { notMirrored(a, it.joinToString("; ")); return@withLock }
        // 22 Oct: the gate's verdict stamped with this exact order's fingerprint (the paper stop it will rest with it); the door
        // recomputes it right before Zerodha and refuses a difference ([OrderVerdicts]).
        val verdict = OrderVerdicts.cleared(OrderVerdicts.liveFields(o, now().toLocalDate(), "arm:${a.key}", stop = a.stop), "arm live gate")
        val base = Pos(a.key, a.ref, ins.symbol, ins.exchange, spec.product, if (a.side >= 0) 1 else -1, qty, qty, ins.lot, ins.tick, ins.multiplier,
            last, now(), a.paperEntry, a.paperQty, a.contract.expiry, null, seen = paperOpen(spec, a.ref) == true)
        // Shadow read again right before anything is sent (review: Shadow turned on while this entry was on its way).
        val shadowNow = shadowed || (a.key == LiveMirror.SOLO_MIDDAY && soloWhy() != null) ||
            try { lock.withLock { LiveMirror.shadowOn(a.key, book().shadow) } } catch (e: Unreadable) {
                note(a.key, "not mirrored: the live book could not be read"); return@withLock
            }
        if (shadowNow) { enterShadow(spec, a, base, o, quote, cut); return@withLock }
        val known = known()
        // (The entry door asks the switches again inside it, from memory: [doorRefusal].)
        testHeard?.add("door|${a.key}|${a.ref}")
        val id: String? = try {
            Broker.placeOrder(o, arm = "arm:${a.key}", intended = last, verdict = verdict, stop = a.stop)
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
            line(b, "%s: %s %s %d @ %.2f live%s%s".format(Locale.ENGLISH, spec.label, if (p.side > 0) "bought" else "sold", ins.symbol, p.qty, fill, cut,
                p.stopTrigger?.let { " · %s %.2f %s".format(Locale.ENGLISH, stopWords(p), it, if (p.stopOrderId != null) "at Zerodha" else "watched by the app (not rested yet)") }
                    ?: " · no stop on the premium (the app follows its paper exits)"))
            save(b)
        }
        runCatching { Diag.record("armlive", "${spec.label}: live entry ${ins.symbol} ${p.qty} @ %.2f".format(Locale.ENGLISH, fill)) }
        if (closedRefs.containsKey(a.ref)) nudge()
    }

    private fun stopWords(p: Pos): String = when (p.stopKind) { SAFETY -> "safety stop"; DISASTER -> "disaster stop"; else -> "stop" }

    /**
     * A shadow entry: what would have been sent, priced as the live mirror fills it (a LIMIT that would not fill: noted, not booked).
     * Final review F4: a MARKET buy pays Zerodha's ask (a sale gets the bid), never better than the app's measured live slippage
     * ([LiveMirror.shadowFill]); a LIMIT fills at the touch only when it is within its limit.
     */
    private suspend fun enterShadow(spec: LiveMirror.Spec, a: Ask, base: Pos, o: Kite.Order, quote: Broker.Quote?, cut: String = "") {
        val buy = base.side > 0
        val limit = o.price?.takeIf { o.orderType == "LIMIT" }
        val px = LiveMirror.shadowFill(buy, base.entry, quote?.bid, quote?.ask, base.tick, halfSpreadOf(base),
            if (limit != null) 0.0 else measured(com.optionslab.ira.Slippage.Leg.ENTRY, ENTRY_BASES))
        if (limit != null && (if (buy) px > limit + 1e-9 else px < limit - 1e-9)) {
            notMirrored(a, "shadow: its LIMIT %.2f would not have filled at %.2f".format(Locale.ENGLISH, limit, px)); return
        }
        var p = base.copy(entry = px, shadow = true, heldSeen = true, charges = charge(if (buy) "BUY" else "SELL", px, base.qty, base.exchange, base.symbol, base.multiplier))
        val (stop, kind) = firstStop(spec, p)
        p = p.copy(stopTrigger = stop, stopKind = kind)
        lock.withLock {
            val b = book()
            b.positions += p
            if (b.shadowSince[a.key] == null) b.shadowSince[a.key] = now()
            line(b, "%s: SHADOW %s %s %d @ %.2f (nothing sent)%s%s".format(Locale.ENGLISH, spec.label, if (p.side > 0) "would buy" else "would sell",
                p.symbol, p.qty, px, cut, stop?.let { " · %s %.2f".format(Locale.ENGLISH, stopWords(p), it) } ?: ""))
            save(b)
        }
        shadowSeen[p.ref] = System.currentTimeMillis() to nowMs()
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
        // 20 Oct, enhancement round 2: read and rested in the contract's one exit turn ([ExitCoordinator]; re-entrant when an exit
        // that did not go puts its stop back).
        return ExitCoordinator.turn(p.exchange, p.symbol, "arm ${p.label} stop") { restStopInTurn(p, trigger, ltp, spec, exitSide) }
    }

    private suspend fun restStopInTurn(p: Pos, trigger: Double, ltp: Double, spec: LiveMirror.Spec, exitSide: Kite.Side): Pos {
        // Every exit already resting on the contract: working orders and active GTTs (review: a GTT left out here let a second one
        // rest beside it, and the two could fire together into a short).
        var book: List<Broker.OrderRow> = emptyList()
        val covered = runCatching {
            book = ExitCoordinator.orders(p.exchange, p.symbol, "arm ${p.label} stop")
            book.filter { it.working && it.symbol == p.symbol && it.side == exitSide.name && it.product == p.product }
                .sumOf { ExitQty.remaining(it.qty, it.filled, it.pending) } +
                ExitCoordinator.gtts(p.exchange, p.symbol, "arm ${p.label} stop").filter { it.symbol == p.symbol && it.exchange == p.exchange }.sumOf { it.covers(exitSide.name, p.product) }
        }.getOrNull()
        // 21 Oct, enhancement round 3 (idea 1): held, checked against that orders read's fills ([ExitCoordinator.held]): a stop is
        // never rested again for lots an exit sold a moment ago (it would open a short when it triggered).
        val heldNow = if (covered == null) null else runCatching {
            ExitCoordinator.held(p.exchange, p.symbol, p.product, "arm ${p.label} stop", book) * p.side
        }.getOrNull()
        if (covered == null || heldNow == null) { stopFailed(p, trigger, "Zerodha's books could not be read"); return p.copy(stopTrigger = trigger) }
        val room = ((heldNow - covered).coerceAtLeast(0) / p.lot.coerceAtLeast(1)) * p.lot.coerceAtLeast(1)
        val q = minOf(p.qty, room)
        // Seen held is the look's to say, oldest first (review: the contract's total includes the other trades on it). A new stop
        // order has nothing of its fill booked yet.
        val seen = p.copy(stopBooked = 0)
        // Not held yet (the position book lags) or another exit already covers it: watched by the app, rested on a later look - and
        // said (throttled) when it is not yet held.
        if (q <= 0) { if (heldNow <= 0) stopFailed(p, trigger, "Zerodha does not show it held yet") ; return seen.copy(stopTrigger = trigger) }
        if (spec.rest == LiveMirror.Rest.GTT) {
            val sp = Kite.Spec(p.exchange, p.symbol, 0L, p.lot, p.tick)
            val (gtt, why) = Kite.protect(sp, p.product, q * p.side, ltp, trigger, null)
            if (gtt == null) { stopFailed(p, trigger, why.joinToString("; ")); return seen.copy(stopTrigger = trigger) }
            // A lost reply never leaves a second GTT: the list is read and one matching is adopted ([Broker.placeGttOnce]).
            val id = Broker.placeGttOnce(gtt, knownGtts())
            gttsKept = null
            if (id == null) { stopFailed(p, trigger, "Zerodha did not take it"); return seen.copy(stopTrigger = trigger) }
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

    /** The GTT ids this path already tracks (memory): never adopted for a lost reply. */
    private fun knownGtts(): Set<Long> = _view.value.open.mapNotNull { it.gttId }.toSet()

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
                gttsKept = null
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
                gttsKept = null
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
            gttsKept = null
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

    /** What Zerodha holds of [p]'s contract and product, as [p]'s side sees it (a long's +, a short's - flipped). */
    private suspend fun heldNow(p: Pos): Int =
        Broker.positionBook().net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty } * p.side

    /**
     * Sell [qty] of [p] ([why]) at the price [ltp]; [g] its GTT as last read (a fired one is taken out like a whole sale's stop).
     * First, before the stop is touched, Zerodha's books are read and the order's gates checked: an exit that cannot go leaves the
     * stop exactly as it rests (review). Then a PART sale shrinks the resting stop to what is left (a modify, never a cancel); a whole
     * sale - or a part sale against a GTT that already fired - takes it out first, confirmed (one that filled meanwhile is that much of
     * the exit, and the rest is still sold). Then a MARKET order for what Zerodha holds less what is already on its way out. A sale
     * that does not go, or does not fill, puts the stop back as it was in the same call (re-rested, or grown back). What it became:
     * the closed parts and the rest still open.
     */
    private suspend fun exitLive(p: Pos, qty: Int, why: String, ltp: Double?, g: Broker.GttRow? = null): List<Pos> {
        runCatching { OrderTiming.decided() }
        val exitSide = if (p.side > 0) Kite.Side.SELL else Kite.Side.BUY
        val want = minOf(qty, p.qty)
        if (want <= 0) return listOf(p)
        val fired = p.gttId != null && g?.status?.lowercase() == "triggered"
        val stopIds = setOfNotNull(p.slId) + (if (p.gttId != null) g?.orderIds.orEmpty() else emptyList())
        fun mineOf(rows: List<Broker.OrderRow>, skip: Set<String>) = rows.filter { it.working && it.symbol == p.symbol && it.side == exitSide.name &&
            it.product == p.product && it.id !in skip }
        // 1. The books and the gates first: nothing is touched when the exit could not go now.
        val orders0 = runCatching { Broker.orders() }.getOrNull() ?: return listOf(p)
        val held0 = runCatching { heldNow(p) }.getOrNull() ?: return listOf(p)
        val planned = ExitQty.sendable(held0.coerceAtLeast(0), mineOf(orders0, stopIds).sumOf { ExitQty.remaining(it.qty, it.filled, it.pending) }, want, p.lot)
        if (planned <= 0) return listOf(p)
        val probe = Kite.Order(p.symbol, exitSide, planned, p.lot, p.product, "MARKET", null, p.tick, p.exchange, LiveMirror.TAG, multiplier = p.multiplier)
        val bad = Kite.refusals(probe, AppSettings.load().limits(), Broker.sentToday(), false, exit = true)
        if (bad.isNotEmpty()) {
            com.optionslab.app.work.Alerts.post("${p.label}: the live exit was not sent (${bad.joinToString("; ")}); its stop stays at Zerodha. Close ${p.symbol} in Trade.",
                com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
            return listOf(p)
        }
        // 2. The stop: shrunk for a part, out (confirmed) for a whole sale or a fired GTT.
        val closed = ArrayList<Pos>()
        var cur = p
        var toSell = want
        val shrunk = want < p.qty && p.stopOrderId != null && !fired
        if (shrunk) {
            val so = p.slId?.let { id -> orders0.firstOrNull { it.id == id } }
            cur = shrinkStop(p, so, p.qty - want, ltp) ?: return listOf(p)          // not shrunk: tried again next look
        } else if (p.stopOrderId != null) {
            val out = stopOut(p) ?: return listOf(p)                               // not confirmed out: tried again next look
            cur = p.copy(stopOrderId = null, stopQty = null)
            if (out.first == "COMPLETE") {
                val f = out.second
                val px = f?.avgPrice?.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                // Only what is new of its fill (a part already booked by an earlier look is not booked again).
                val sold = minOf(((f?.filled?.takeIf { it > 0 } ?: p.qty) - p.stopBooked).coerceAtLeast(0), p.qty)
                if (sold > 0) {
                    runCatching { Notifier.orderFilled(app, exitSide.name, sold, p.symbol, px, "Live", "${p.label} · live ${stopWords(p)}", f?.orderId) }
                    val (done, rest) = closePart(cur, sold, px, p.stopWord, exitSide.name)
                    closed += done
                    val left = rest ?: return closed
                    cur = left
                    toSell = (want - sold).coerceAtLeast(0)
                    if (toSell == 0) return closed + (if (ltp != null) restStop(cur, cur.stopTrigger, ltp) else cur)
                }
            }
            cur = cur.copy(stopBooked = 0)
        }
        // The stop put back as it was when the sale does not go: re-rested when out, grown back when shrunk.
        // 21 Oct, round 3 review: what Zerodha holds as the sale read it (null: not read); a stop grown back never covers more.
        var heldSeen: Int? = null
        suspend fun back(x: Pos): Pos = when {
            x.stopOrderId == null -> if (ltp != null) restStop(x, x.stopTrigger, ltp) else x
            shrunk && (x.stopQty ?: x.qty) < x.qty -> {
                val so = x.slId?.let { id -> runCatching { Broker.orders().firstOrNull { it.id == id } }.getOrNull() }
                val to = minOf(x.qty, heldSeen?.coerceAtLeast(0) ?: x.qty)
                if (to > (x.stopQty ?: x.qty)) shrinkStop(x, so, to, ltp) ?: x else x
            }
            else -> x
        }
        // 3. The sale: what Zerodha holds less what is on its way out, now that the stop is out or shrunk.
        // 20 Oct, enhancement round 2: read and sent in the contract's one exit turn, from a read that shows every exit sent from
        // here ([ExitCoordinator]): another sender's exit sent a moment ago is subtracted, never sold again.
        val id = ExitCoordinator.turn(p.exchange, p.symbol, "arm ${p.label}") {
            val book = runCatching { ExitCoordinator.orders(p.exchange, p.symbol, "arm ${p.label}") }.getOrNull() ?: return closed + back(cur)
            val working = mineOf(book, emptySet())
            // 21 Oct, enhancement round 3 (idea 1): what is held, read after that orders read and checked against its fills
            // (an exit that filled a moment ago is never sold again while the positions catch up; [ExitCoordinator.held]).
            val held = runCatching { ExitCoordinator.held(cur.exchange, cur.symbol, cur.product, "arm ${p.label}", book) * cur.side }.getOrNull()
                ?: return closed + back(cur)
            heldSeen = held
            val sendable = ExitQty.sendable(held.coerceAtLeast(0), working.sumOf { ExitQty.remaining(it.qty, it.filled, it.pending) }, minOf(toSell, cur.qty), p.lot)
            if (sendable <= 0) return closed + back(cur)
            val o = Kite.Order(p.symbol, exitSide, sendable, p.lot, p.product, "MARKET", null, p.tick, p.exchange, LiveMirror.TAG, multiplier = p.multiplier)
            try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
                val found = if (Broker.definite(e)) null else runCatching { Broker.findRecentRetrying(o, known() + working.map { it.id }) }.getOrNull()
                found ?: run {
                    com.optionslab.app.work.Alerts.post("${p.label}: the live exit failed (${e.message?.take(160)}); retrying on the next look.",
                        com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
                    // Refused outright: the stop goes back now. A lost reply with no order found may still have gone: next look.
                    return closed + (if (Broker.definite(e)) back(cur) else cur)
                }
            }
        }
        runCatching { Strategies.tagOwner("kite:$id", "${p.label} · live $why") }
        var f = runCatching { Broker.awaitOrder(id, 15_000) }.getOrNull()
        if (f?.status !in DONE) { runCatching { Broker.cancel(id) }; f = runCatching { Broker.orderState(id) }.getOrNull() ?: f }
        if (f == null || f.filled <= 0) return closed + (if (f != null && f.status in DONE) back(cur) else cur)
        // Final review F6: an average Zerodha did not give is read from its trade book, else the price now, else the price paid.
        val px = f.avgPrice.takeIf { it > 0 } ?: orderPrice(id) ?: ltp?.takeIf { it > 0 } ?: p.entry
        runCatching { Notifier.orderFilled(app, exitSide.name, f.filled, p.symbol, px, "Live", "${p.label} · live $why", f.orderId) }
        val (done, rest) = closePart(cur, minOf(f.filled, cur.qty), px, why, exitSide.name)
        closed += done
        // The rest still open keeps a stop for all of it: rested again when out, grown back when a part sale only part filled.
        return closed + listOfNotNull(rest?.let { back(it) })
    }

    /**
     * Final review F6: the fills of what was closed of [p]'s contract outside this path (by hand, Zerodha's square-off), newest
     * first, as (quantity, price): Zerodha's trade book's exit-side fills that are none of the app's own orders ([mine]), else the
     * same from its order book ([orders]). Empty when neither shows one.
     */
    private suspend fun outsideFills(p: Pos, mine: Set<String>, orders: Collection<Broker.OrderRow>): List<Pair<Int, Double>> {
        val exitSide = if (p.side > 0) "SELL" else "BUY"
        runCatching { Broker.trades() }.getOrNull()?.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product &&
            it.side == exitSide && it.orderId !in mine && it.qty > 0 && it.price > 0 }?.sortedByDescending { it.at }
            ?.map { it.qty to it.price }?.takeIf { it.isNotEmpty() }?.let { return it }
        return orders.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product && it.side == exitSide &&
            it.id !in mine && it.filled > 0 && it.avg > 0 }.sortedByDescending { it.placedAt }.map { it.filled to it.avg }
    }

    /** The average price of [qty] units of [rows] (quantity, price) after the first [skip] units; null when none are left. */
    internal fun takeAvg(rows: List<Pair<Int, Double>>, skip: Int, qty: Int): Double? {
        var toSkip = skip; var need = qty; var sum = 0.0; var got = 0
        for ((n0, px) in rows) {
            var n = n0
            if (toSkip > 0) { val s0 = minOf(toSkip, n); toSkip -= s0; n -= s0 }
            if (n <= 0) continue
            if (need <= 0) break
            val take = minOf(need, n); sum += take * px; got += take; need -= take
        }
        return if (got > 0) sum / got else null
    }

    /** Order [id]'s average fill from Zerodha's trade book (an order whose own average was not given); null when it shows none. */
    private suspend fun orderPrice(id: String): Double? = runCatching { Broker.trades() }.getOrNull()?.filter { it.orderId == id && it.qty > 0 && it.price > 0 }
        ?.takeIf { it.isNotEmpty() }?.let { rows -> rows.sumOf { it.qty * it.price } / rows.sumOf { it.qty } }

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
    /**
     * A damaged book's open live trades are still to be taken back from Zerodha, or the book could not be read yet (final review
     * F1: it may hold open live trades) - memory only: every lane that watches (the minute lane, the live lane, the 15-second
     * check) and every keep-alive keeps running for it, and the watch reads the book again.
     */
    val recoverHint: Boolean get() = recoverDue || unreadable

    /** Whether a look has work now (memory only): a live trade held, a shadow trade open, a damaged or unread book. */
    val lookDue: Boolean get() = holdingHint || shadowHint || recoverHint

    private fun retryWaitMs(): Long = (RETRY_MS shl (readFails - 1).coerceIn(0, 4)).coerceAtMost(RETRY_MAX_MS)

    suspend fun watch(stream: Boolean = false) {
        if (!ready()) return
        if (cache == null) {
            // Final review F1: an unreadable book is read again by the minute lane and the 15-second check - never the live lane,
            // which comes every tick and must not wait on the Keystore - at most every 5 s, doubling to 60 s while it keeps failing.
            if (stream && unreadable) return
            if (unreadable && runCatching { nowMs() }.getOrDefault(0L) - readFailedMs in 0 until retryWaitMs()) return
            val ok = runCatching { lock.withLock { book() } }.isSuccess
            if (!ok) return
        }
        prune()
        if (!holdingHint && !shadowHint && !recoverDue) return
        if (!opLock.tryLock()) return                 // a look already running does this one's work
        try {
            if (recoverDue && Broker.loggedIn) runCatching { recover() }
            if (holdingHint) look(stream)
        } finally { opLock.unlock() }
        // The shadow trades after, under their own lock (review of the final fixes): their quote and candle reads never hold up
        // the live trades' looks (the live lane and the 15-second check skip a look while [opLock] is held).
        if (!stream && shadowHint && shadowLock.tryLock()) {
            try { runCatching { lookShadow() } } finally { shadowLock.unlock() }
        }
    }

    /** One shadow look at a time (never the live trades' [opLock]). */
    private val shadowLock = Mutex()

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
        if (open.isEmpty()) return
        if (!Broker.loggedIn) { waitingForLogin(open, stream); return }
        val t = now()
        val ms = nowMs()
        var orders = (if (stream) runCatching { Broker.ordersRecon() } else runCatching { Broker.orders() }).getOrNull()?.associateBy { it.id } ?: return
        var net = (if (stream) runCatching { Broker.positionsRecon().net } else runCatching { Broker.positionBook().net }).getOrNull() ?: return
        val keys = open.map { "${it.exchange}:${it.symbol}" }.distinct()
        val q = runCatching { quotes(keys, stream) }.getOrDefault(emptyMap())
        // The GTT list: read on a regular look; a stream look (the live lane, every tick) uses the last one read within 15 s.
        // Review: a GTT missing from a CACHED list may simply be newer than it: only a fresh list may say a GTT is gone.
        var gttsFresh = false
        var gtts = if (open.none { it.gttId != null }) emptyMap() else gttsKept?.takeIf { stream && System.currentTimeMillis() - it.first < 15_000L }?.second
            ?: runCatching { Broker.gtts().associateBy { it.id } }.getOrNull()?.also { gttsKept = System.currentTimeMillis() to it; gttsFresh = true }
        val stopped = runCatching { Strategies.stoppedToday() }.getOrDefault(false)
        val updates = LinkedHashMap<Pos, List<Pos>>()
        fun cur(p: Pos): Pos? = updates[p]?.let { it.firstOrNull { x -> x.open } } ?: if (p in updates) null else p
        // What [p0] became this look: the parts already closed earlier in the look are kept (a part sold by hand, a part-filled stop),
        // the open part replaced by [next].
        fun set(p0: Pos, next: List<Pos>) { updates[p0] = updates[p0].orEmpty().filter { !it.open } + next }
        fun ltpOf(p: Pos) = q["${p.exchange}:${p.symbol}"]?.last?.takeIf { it > 0 }
        fun heldOf(p: Pos) = net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty } * p.side
        // Contracts on which this look already sent, cancelled or closed something: their books are read again before the next
        // decision on them (review: a stop confirmed out a moment ago must not still count as covering a sibling).
        val dirty = HashSet<String>()
        suspend fun reread() {
            runCatching { Broker.orders() }.getOrNull()?.let { orders = it.associateBy { o -> o.id } }
            runCatching { Broker.positionBook().net }.getOrNull()?.let { net = it }
            // GTTs too (review: one deleted earlier in this look must not still count against a sibling's stop).
            if (gtts.orEmpty().isNotEmpty() || open.any { it.gttId != null })
                runCatching { Broker.gtts().associateBy { it.id } }.getOrNull()?.let { gtts = it; gttsKept = System.currentTimeMillis() to it; gttsFresh = true }
            dirty.clear()
        }

        // 1. Each trade's own news: an unconfirmed entry settled; its stop filled, refused or gone.
        for (p0 in open) {
            try {
                if (p0.unconfirmed) { set(p0, listOfNotNull(settle(p0))); continue }
                var p = p0
                val so = p.slId?.let { orders[it] }
                val g = p.gttId?.let { gtts?.get(it) }
                // Its stop filled at Zerodha - all of it, or a part while the rest still works: what is new of its fill is booked once.
                val newFill = if (so == null) 0 else ((if (so.status == "COMPLETE" && so.filled <= 0) so.qty else so.filled) - p.stopBooked).coerceAtLeast(0)
                if (so != null && newFill > 0) {
                    val px = so.avg.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                    val side = if (p.side > 0) "SELL" else "BUY"
                    val sold = minOf(newFill, p.qty)
                    runCatching { Notifier.orderFilled(app, side, sold, p.symbol, px, "Live", "${p.label} · live ${stopWords(p)}", p.slId) }
                    val (done, rest) = closePart(p, sold, px, p.stopWord, side)
                    // The rest keeps a working stop's id (what is new of its fill is booked once); a stop that is done (filled for less
                    // than the trade) is gone, so the next look rests a new one for the rest.
                    val after = if (so.working) rest?.copy(stopBooked = p.stopBooked + sold, stopQty = rest.stopQty?.let { (it - sold).coerceAtLeast(0) })
                        else rest?.copy(stopOrderId = null, stopQty = null, stopBooked = 0)
                    set(p0, listOfNotNull(done, after))
                    continue
                }
                if (so != null && !so.working && so.status == "COMPLETE") p = p.copy(stopOrderId = null, stopQty = null, stopBooked = 0)
                if (so != null && so.status in setOf("REJECTED", "CANCELLED")) {
                    com.optionslab.app.work.Alerts.post("${p.label}: the live stop at Zerodha was ${so.status.lowercase()} (${so.message.ifBlank { "no reason given" }}); " +
                        "the app watches it and rests it again.", com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
                    p = p.copy(stopOrderId = null, stopQty = null)
                }
                if (p.slId != null && so == null && p.day.isBefore(t.toLocalDate())) p = p.copy(stopOrderId = null, stopQty = null)
                if (p.gttId != null && gtts != null && gttsFresh && (g == null || g.status.lowercase() in setOf("deleted", "cancelled", "expired", "rejected", "disabled")))
                    p = p.copy(stopOrderId = null, stopQty = null)
                if (g?.status?.lowercase() == "triggered") {
                    // A fired GTT: its own orders are the stop. All filled: that was the stop; refused or cancelled: rested again.
                    val st = g.orderIds.mapNotNull { orders[it] }
                    if (st.isNotEmpty() && st.all { it.status == "COMPLETE" }) {
                        // All its orders filled: what is new of that fill is booked once, and the GTT is done - a rest (the GTT was for
                        // less than the trade) gets a new stop on the next look.
                        val px = st.first().avg.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                        val side = if (p.side > 0) "SELL" else "BUY"
                        val sold = minOf(((st.sumOf { it.filled }.takeIf { it > 0 } ?: p.qty) - p.stopBooked).coerceAtLeast(0), p.qty)
                        if (sold > 0) {
                            runCatching { Notifier.orderFilled(app, side, sold, p.symbol, px, "Live", "${p.label} · live ${stopWords(p)}", st.first().id) }
                            val (done, rest) = closePart(p, sold, px, p.stopWord, side)
                            set(p0, listOfNotNull(done, rest?.copy(stopOrderId = null, stopQty = null, stopBooked = 0))); continue
                        }
                        p = p.copy(stopOrderId = null, stopQty = null, stopBooked = 0)
                    }
                    if (st.isNotEmpty() && st.none { it.working }) p = p.copy(stopOrderId = null, stopQty = null)
                }
                if (p != p0) set(p0, listOf(p))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) { note(p0.key, "live look failed on ${p0.symbol}: ${e.javaClass.simpleName}") }
        }

        // 2. Each contract: what Zerodha holds against what these trades think they hold. Oldest first, a trade is seen held only
        //    while Zerodha holds it and every older one (review: a book lagging a new fill never closes the older trade); what is
        //    missing is charged only to trades already seen held (or zero past the grace), oldest first (the paper rule). An entry
        //    not yet confirmed takes no part.
        for ((contract, group) in open.groupBy { it.contract }) {
            val live = group.mapNotNull { g0 -> cur(g0)?.takeIf { !it.unconfirmed }?.let { c -> g0 to c } }.sortedBy { it.second.entryTime }
            if (live.isEmpty()) continue
            val held = heldOf(live.first().second)
            val counted = live.filter { (_, p) -> p.heldSeen || LiveMirror.zeroLongEnough(p.zeroSinceMs, ms) }.map { it.first }.toSet()
            var cum = 0
            for ((p0, p) in live) {
                cum += p.qty
                val seen = if (held >= cum) p.copy(heldSeen = true, zeroSinceMs = null)
                    else if (!p.heldSeen && p.zeroSinceMs == null) p.copy(zeroSinceMs = ms) else p
                if (seen != p) set(p0, listOf(seen))
            }
            var excess = live.filter { it.first in counted }.sumOf { (cur(it.first) ?: it.second).qty } - held.coerceAtLeast(0)
            if (excess <= 0) continue
            // Final review F6: what was closed outside is booked at what Zerodha's trade book (else its order book) says it sold
            // for; only with neither, at the price now (else the price paid), as before.
            val mine = known().toSet()
            // Each closed part takes the next of those fills (newest first), never the same fill twice (review of the final fixes).
            var fills: List<Pair<Int, Double>>? = null
            var used = 0
            suspend fun outsideAt(p: Pos, q: Int): Double? {
                val rows = fills ?: outsideFills(p, mine, orders.values).also { fills = it }
                return takeAvg(rows, used, q).also { if (it != null) used += q }
            }
            for ((p0, _) in live) {
                if (excess <= 0) break
                if (p0 !in counted) continue
                val p = cur(p0) ?: continue
                val take = minOf(p.qty, excess)
                dirty += contract
                if (take >= p.qty) {
                    // Its stop is confirmed out first: left resting, it would sell into a short. One that filled was the exit.
                    val out = if (p.stopOrderId != null) stopOut(p) else ("CANCELLED" to null)
                    if (out == null) continue
                    val side = if (p.side > 0) "SELL" else "BUY"
                    val filled = out.first == "COMPLETE" && (out.second?.filled ?: p.qty) - p.stopBooked > 0
                    val px = (if (filled) out.second?.avgPrice?.takeIf { it > 0 } ?: p.stopTrigger else outsideAt(p, p.qty)) ?: ltpOf(p) ?: p.entry
                    if (filled) runCatching { Notifier.orderFilled(app, side, p.qty, p.symbol, px, "Live", "${p.label} · live ${stopWords(p)}", p.slId) }
                    set(p0, listOf(closePart(p, p.qty, px, if (filled) p.stopWord else "closed_at_zerodha", side).first))
                } else {
                    val (done, rest) = closePart(p, take, outsideAt(p, take) ?: ltpOf(p) ?: p.entry, "closed_at_zerodha", if (p.side > 0) "SELL" else "BUY")
                    set(p0, listOfNotNull(done, rest))
                }
                excess -= take
            }
        }

        // 3. Each trade's decision, newest first on each contract, against the exits working on it (kept up to date as they change).
        val working = HashMap<String, Int>()     // order id -> remaining, the exits working on these contracts
        fun refill() { working.clear(); for (o in orders.values) if (o.working) working[o.id] = ExitQty.remaining(o.qty, o.filled, o.pending) }
        refill()
        for ((contract, group) in open.groupBy { it.contract }) {
            for (p0 in group.sortedByDescending { it.entryTime }) {
                val p1 = cur(p0)?.takeIf { !it.unconfirmed } ?: continue
                if (contract in dirty) { reread(); refill() }
                try {
                    val spec = LiveMirror.spec(p1.key)
                    val (p, paperSell, paperWhy) = paperSide(p1, legsOf(spec, stream))
                    val so = p.slId?.let { orders[it] }
                    val g = p.gttId?.let { gtts?.get(it) }
                    val exitSide = if (p.side > 0) "SELL" else "BUY"
                    val gttOrders = g?.orderIds.orEmpty().toSet()
                    // The other exits on the contract: working orders, and other active GTTs (the cached list can only miss newer ones,
                    // which leaves more room, never less; a new stop is only rested on a fresh read in [restStop]).
                    val others = orders.values.filter { it.working && it.symbol == p.symbol && it.side == exitSide && it.product == p.product &&
                        it.id != p.slId && it.id !in gttOrders }.sumOf { working[it.id] ?: 0 } +
                        gtts.orEmpty().values.filter { it.id != p.gttId && it.symbol == p.symbol && it.exchange == p.exchange }.sumOf { it.covers(exitSide, p.product) }
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
                            dirty += contract
                            exitLive(p, d.qty, why, ltp, g)
                        }
                        LiveMirror.Step.PLACE_STOP -> {
                            val w = wanted(p)
                            val kind = if (w != null && d.to == w && p.stopKind != DISASTER) OWN else p.stopKind
                            dirty += contract
                            listOf(restStop(p.copy(stopKind = kind), d.to, ltp ?: continue))
                        }
                        LiveMirror.Step.MOVE_STOP -> listOf(moveStop(p, so, d.to ?: continue, ltp ?: continue))
                        LiveMirror.Step.SHRINK_STOP -> {
                            val s2 = shrinkStop(p, so, d.qty, ltp)
                            dirty += contract
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

    /** Trades Boss was told are waiting for the day's Zerodha login to exit ("ref|day"; memory, final review F8). */
    private val loginAsked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Final review F8: no Zerodha login today, so nothing can be sold - its stop still rests at Zerodha where it can. A trade whose
     * exit is due (its paper trade closed or sold part, its square-off) is told to Boss once a day: he logs in, or closes it in Kite.
     */
    private fun waitingForLogin(open: List<Pos>, stream: Boolean) {
        val t = runCatching { now() }.getOrElse { return }
        if (loginAsked.size > 500) loginAsked.clear()
        for (p in open) {
            if (p.unconfirmed) continue
            val sell = runCatching { paperSide(p, legsOf(LiveMirror.spec(p.key), stream)).second }.getOrDefault(0)
            if (sell <= 0 && !LiveMirror.squareOffDue(t, p.exchange, p.product, p.expiry, p.day)) continue
            if (!loginAsked.add("${p.ref}|${t.toLocalDate()}")) continue
            com.optionslab.app.work.Alerts.error("${p.label} live trade waiting for the Zerodha login to exit (${p.symbol}). " +
                "Log in to Zerodha in the app, or close it in Kite.", WHO)
        }
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
     * would - priced from Zerodha's quote (or the last seen) - and nothing is ever sent. Final review F4: a stop is judged on the
     * lowest (a short's highest) price traded since the last look - the stream's ticks, else the minute candles - as the resting SL
     * would have filled on a wick; it fills at its trigger less the spread or the measured stop slippage (never past its limit),
     * and any other exit sells at Zerodha's bid (buys back at the ask), never better than the measured live slippage.
     */
    private suspend fun lookShadow() {
        val open = lock.withLock { book().positions.filter { it.open && it.shadow }.toList() }
        if (open.isEmpty()) return
        val t = now()
        val realMs = System.currentTimeMillis()
        val ms = nowMs()
        val q = if (Broker.loggedIn) runCatching { Broker.stopQuotes(open.map { "${it.exchange}:${it.symbol}" }.distinct()) }.getOrDefault(emptyMap()) else emptyMap()
        val exitSlip = measured(com.optionslab.ira.Slippage.Leg.EXIT, EXIT_BASES)
        val stopSlip = measured(com.optionslab.ira.Slippage.Leg.EXIT, STOP_BASES)
        // The minute candles read this look, by contract (one read each), and at most [CANDLE_READS] of them.
        val candleCalls = HashMap<Long, List<com.optionslab.engine.Upstox.Bar>?>()
        val updates = LinkedHashMap<Pos, List<Pos>>()
        for (p0 in open) {
            val (p, paperSell, paperWhy) = paperSide(p0, legsOf(LiveMirror.spec(p0.key), false))
            val quote = q["${p.exchange}:${p.symbol}"]?.takeIf { it.last > 0 }
            val ltp = quote?.last?.also { shadowMarks[p.ref] = it } ?: shadowMarks[p.ref]
            // The moment of its last look (null after a restart: judged on the price now only, as a stop moved up since must
            // not be judged on the prices before it moved).
            val since = shadowSeen[p.ref]
            val (known, extreme) = if (since != null && p.stopTrigger != null)
                runCatching { shadowExtreme(p, since, realMs, ms, candleCalls) }.getOrDefault(false to null) else (true to null)
            // A window that could not be read is kept for the next look (a wick in it is still seen then), never skipped.
            if (known) shadowSeen[p.ref] = realMs to ms
            // Review: a stop the price has reached is booked first - the resting SL at Zerodha would have filled before the paper
            // exit the app follows; with no price at all (and no wick seen) nothing is booked (never at the price paid).
            val atStop = LiveMirror.shadowStopReached(ltp, extreme, p.stopTrigger, p.side)
            val d0 = LiveMirror.decide(LiveMirror.Look(side = p.side, qty = p.qty, held = p.qty * p.side, ltp = ltp, tick = p.tick,
                stopTrigger = p.stopTrigger, stopResting = false, wanted = wanted(p), paperSell = paperSell, paperReason = paperWhy,
                squareOff = LiveMirror.squareOffDue(t, p.exchange, p.product, p.expiry, p.day), operatorStop = false))
            val d = if (atStop) LiveMirror.Decision(LiveMirror.Step.SELL, "stop", p.qty) else d0
            if (d.step == LiveMirror.Step.SELL && ltp == null && !atStop) { if (p != p0) updates[p0] = listOf(p); continue }
            when (d.step) {
                LiveMirror.Step.SELL -> {
                    val stopExit = d.why == "stop"
                    val hs = halfSpreadOf(p)
                    val trig = p.stopTrigger
                    // A wick that went through the SL's limit: the limit order rests and fills at its limit when the price comes back.
                    val px = if (stopExit && trig != null && !LiveMirror.runThrough(ltp, trig, p.tick, p.side))
                        (if (LiveMirror.runThrough(extreme, trig, p.tick, p.side)) LiveMirror.stopLimit(trig, p.tick, p.side)
                        else LiveMirror.shadowStopFill(trig, p.tick, p.side, maxOf(hs, stopSlip)))
                    else LiveMirror.shadowFill(p.side < 0, ltp ?: trig ?: p.entry, quote?.bid, quote?.ask, p.tick, hs, exitSlip)
                    val (done, rest) = closePart(p, d.qty, px, if (stopExit) p.stopWord else d.why ?: "exit", if (p.side > 0) "SELL" else "BUY")
                    updates[p0] = listOfNotNull(done, rest)
                    if (rest == null) { shadowMarks.remove(p.ref); shadowSeen.remove(p.ref) }
                }
                LiveMirror.Step.PLACE_STOP -> if (d.to != p.stopTrigger || p != p0) {
                    val w = wanted(p)
                    updates[p0] = listOf(p.copy(stopTrigger = d.to, stopKind = if (w != null && d.to == w && p.stopKind != DISASTER) OWN else p.stopKind))
                    // A stop that moved is judged only on the prices after it moved.
                    if (d.to != p.stopTrigger) shadowSeen[p.ref] = realMs to ms
                }
                else -> if (p != p0) updates[p0] = listOf(p)
            }
        }
        if (updates.isNotEmpty()) commit(updates)
    }

    /** Each open shadow trade's last look (real clock ms for the stream's ticks, the market clock ms for the candles); memory. */
    private val shadowSeen = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
    /** The minute candles failed (no historical-data plan, a refusal): not asked again before this (real ms). */
    @Volatile private var candlesOffUntil = 0L

    /**
     * The lowest price [p] traded since its last look ([since]: real and market ms) - a short's highest: the stream's ticks when it
     * streams the contract, else Zerodha's minute candles that began after it (read once per contract a look, [calls]; at most
     * [CANDLE_READS] reads, each within [CANDLE_MS]). (whether the window was read, the extreme or null for none traded); not read:
     * the caller keeps the window for the next look.
     */
    private suspend fun shadowExtreme(p: Pos, since: Pair<Long, Long>, realMs: Long, ms: Long,
                                      calls: MutableMap<Long, List<com.optionslab.engine.Upstox.Bar>?>): Pair<Boolean, Double?> {
        val tok = runCatching { Broker.tokenOf("${p.exchange}:${p.symbol}") }.getOrNull() ?: return true to null
        val long = p.side > 0
        (if (long) KiteStream.lowSince(tok, since.first) else KiteStream.highSince(tok, since.first))?.let { return true to it }
        // The minutes that began after the last look (a minute that began before it may hold prices from before the stop moved).
        val from = since.second - Math.floorMod(since.second, 60_000L) + 60_000L
        if (from > ms) return true to null
        if (!Broker.loggedIn || realMs < candlesOffUntil) return false to null
        val day = java.time.Instant.ofEpochMilli(from).atZone(com.optionslab.engine.IST).toLocalDate()
        val all = (if (calls.containsKey(tok)) calls[tok] else {
            if (calls.size >= CANDLE_READS) return false to null
            // From the session's start (MCX: its whole day): one read serves every shadow trade on the contract this look.
            val got = runCatching { kotlinx.coroutines.withTimeout(CANDLE_MS) {
                if (p.exchange == "MCX") Broker.minuteBars(tok, day, from = "09:00:00", to = "23:59:00") else Broker.minuteBars(tok, day)
            } }.getOrNull()
            calls[tok] = got
            if (got == null) candlesOffUntil = realMs + 2 * 60_000L
            got
        }) ?: return false to null
        val bars = all.filter { it.epochSecond * 1000 in from..ms }
        return true to (if (long) bars.mapNotNull { b -> b.low.takeIf { it > 0 } }.minOrNull() else bars.mapNotNull { b -> b.high.takeIf { it > 0 } }.maxOrNull())
    }

    private const val CANDLE_READS = 4
    private const val CANDLE_MS = 3_000L

    private val ENTRY_BASES = setOf(com.optionslab.ira.Slippage.Basis.SIGNAL, com.optionslab.ira.Slippage.Basis.LTP)
    private val EXIT_BASES = setOf(com.optionslab.ira.Slippage.Basis.LTP, com.optionslab.ira.Slippage.Basis.SIGNAL)
    private val STOP_BASES = setOf(com.optionslab.ira.Slippage.Basis.TRIGGER)

    /** The app's measured live slippage on [leg] (a share of the price; the arms' own fills first, else all; 0 until measured). */
    private fun measured(leg: com.optionslab.ira.Slippage.Leg, bases: Set<com.optionslab.ira.Slippage.Basis>): Double =
        runCatching { com.optionslab.ira.Slippage.medianShare(FillSlippage.fills(), leg, bases, strategy = "Arms (live)") }.getOrDefault(0.0)

    /** The half-spread a fill in [p]'s contract pays with no book read (the measured defaults, [com.optionslab.engine.sandbox.PaperSpread]). */
    private fun halfSpreadOf(p: Pos): Double = runCatching {
        com.optionslab.engine.sandbox.PaperSpread.halfSpread(p.symbol, null, p.exchange, now().toLocalTime())
    }.getOrDefault(com.optionslab.engine.sandbox.PaperSpread.UNKNOWN)

    private val shadowMarks = java.util.concurrent.ConcurrentHashMap<String, Double>()

    /** The prices of [keys]: the stream's fresh ticks with [stream], the rest Zerodha's (a tick only while 2 s fresh). */
    private suspend fun quotes(keys: List<String>, stream: Boolean): Map<String, Broker.Quote> {
        if (!stream) return Broker.stopQuotes(keys)
        val out = HashMap<String, Broker.Quote>()
        for (k in keys) {
            // 20 Oct, review: memory only (never the instruments file on a stream look); unknown goes to Zerodha's quote below.
            val tok = runCatching { Broker.tokenKnown(k) }.getOrNull() ?: continue
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
        val fill = st.avgPrice.takeIf { it > 0 } ?: orderPrice(oid) ?: p.entry
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
        val gtts = runCatching { Broker.gtts() }.getOrNull()
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
                pos.multiplier.toInt().coerceAtLeast(1), pos.avg.takeIf { it > 0 } ?: entry.avg, now(), pos.avg, kotlin.math.abs(pos.qty),
                // Its contract's expiry from the day's lists, so the 15:05 expiry square-off applies to it too (review).
                runCatching { (Broker.cachedInstruments().orEmpty() + Broker.cachedBfoOptions().orEmpty()).firstOrNull { it.tradingSymbol == pos.symbol }?.expiry }.getOrNull(),
                entry.id,
                seen = false, heldSeen = true, stopKind = SAFETY)
            // A stop of this path's own still resting on it is kept as its stop; else the safety stop rests.
            val so = orders.filter { it.working && it.tag == LiveMirror.TAG && it.symbol == pos.symbol && it.product == pos.product &&
                (it.type == "SL" || it.type == "SL-M") }.singleOrNull()
            // Its GTT still resting (a carried trade's stop) is kept too: a second one beside it could fire with it into a short.
            val exitSide = if (side > 0) "SELL" else "BUY"
            // A GTT has no tag: one whose quantity is this position's is preferred (else one Boss or a strategy set could be taken).
            val cands = gtts.orEmpty().filter { it.symbol == pos.symbol && it.exchange == pos.exchange && it.covers(exitSide, pos.product) > 0 }
            val gt = cands.firstOrNull { it.covers(exitSide, pos.product) == kotlin.math.abs(pos.qty) } ?: cands.singleOrNull()
            adopted += when {
                so != null -> p.copy(stopOrderId = so.id, stopTrigger = so.trigger, stopQty = ExitQty.remaining(so.qty, so.filled, so.pending), stopKind = OWN)
                gt != null -> p.copy(stopOrderId = GTT + gt.id, stopTrigger = gt.triggers.firstOrNull(), stopQty = gt.covers(exitSide, pos.product), stopKind = OWN)
                else -> restStop(p, LiveMirror.safetyLevel(p.entry, side, tick), pos.last.takeIf { it > 0 } ?: p.entry)
            }
        }
        lock.withLock {
            val b = book()
            b.positions += adopted
            adopted.forEach { line(b, "${it.label}: ${it.symbol} ${it.qty} taken back from Zerodha after the book was damaged") }
            save(b)
        }
        recoverDue = false
        // Positions this path could not match to an arm (a carried one from an earlier day has no entry in today's order book): Boss
        // is told to check them - they may be his own, another bot's, or an arm's whose record was lost with the book.
        val unmatched = net.filter { it.qty != 0 && it.exchange in setOf("NFO", "BFO", "MCX") }
            .filter { pos -> adopted.none { it.symbol == pos.symbol && it.product == pos.product } && "${pos.exchange}|${pos.symbol}|${pos.product}" !in have }
        if (unmatched.isNotEmpty()) com.optionslab.app.work.Alerts.error("Arms live: the damaged book could not be matched to ${unmatched.size} open " +
            "position(s) (${unmatched.joinToString { it.symbol }}). If any was an arm's live trade, it has no app stop now: check them in Trade.", WHO)
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
        val live = liveFlags().first
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
        cache = null; unreadable = false; recoverDue = false; gttsKept = null; legsKept.clear(); shadowMarks.clear(); shadowSeen.clear()
        readFailedMs = 0L; readFails = 0; liveKept = null; loginAsked.clear(); candlesOffUntil = 0L
        _view.value = View(); holdingHint = false; shadowHint = false; liveKeysHint = emptyList(); levels.clear(); closedRefs.clear()
    }

    /** For tests: as after a restart - the memory gone, the book on disk. */
    internal fun forgetMemoryForTest() { cache = null; legsKept.clear(); gttsKept = null; levels.clear(); closedRefs.clear(); shadowSeen.clear(); loginAsked.clear() }
}
