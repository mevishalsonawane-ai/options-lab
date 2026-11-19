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
    /** 18 Nov 2026: an arm's live entry is on its way now (memory only): the ORB arms never take a position for a lost entry meanwhile. */
    val entryInFlight: Boolean get() = entryLock.isLocked
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
        /** 2 Nov: the paper contract this live trade copies (the paper book's own name for it): its paper notices stay down while open. */
        val paperSymbol: String? = null,
        /**
         * 15 Nov (Boss's exception, failed gates; [com.optionslab.engine.orb.StepTrail]): an R51 / R72 live (or shadow) trade whose paper
         * trade entered with the breakeven rule: this trade has its OWN, on its own fill and lots (Kite's P&L) - its stop resting
         * at Zerodha at its own stop, then moved up to breakeven with its best price ([best]) ([ruleStop]). Persisted.
         */
        val fixed: Boolean = false,
        /** 15 Nov: the best price seen while it was held (the stop rule's best P&L; persisted, so a restart never lowers its stop). */
        val best: Double? = null,
        /**
         * 16 Nov (the fourth version, Boss's exception): with [fixed], the trade also has R51 / R72's ₹500 step lock
         * ([com.optionslab.engine.orb.StepTrail.Lock.L500]) on its own fill, lots and [best]; false: V4 alone, as it entered. Persisted.
         */
        val lock: Boolean = false,
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
    /**
     * 26 Oct (Erase everything): bumped by [wipe], which cannot take [lock] (a coroutine lock). [loadGen] is the count when the
     * book in hand was read; a pass that read it before an erase neither saves it, backs its switches up nor shows it afterwards.
     */
    @Volatile private var wipeGen = 0
    @Volatile private var loadGen = 0
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

    /**
     * 2 Nov (Boss's square-off with an arm's stop resting): contracts Boss is exiting by hand right now ("EXCHANGE:SYMBOL" -> until,
     * ms; memory only). The arms leave them alone meanwhile - no stop moved, shrunk or rested again, no exit of their own - so the
     * stop Boss's exit turns into a sell is never put back to a trigger, and no second sell goes beside his. Given back when his
     * exit is done ([handsBack]); at most [HANDS_OFF_MS] in any case, so a trade is never left unwatched for long.
     */
    private val handsOffUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val HANDS_OFF_MS = 90_000L
    fun handsOff(exchange: String, symbol: String) { handsOffUntil["$exchange:$symbol"] = System.currentTimeMillis() + HANDS_OFF_MS }
    fun handsBack(exchange: String, symbol: String) { if (handsOffUntil.remove("$exchange:$symbol") != null) runCatching { nudge() } }
    internal fun handsOffNow(exchange: String, symbol: String): Boolean =
        handsOffUntil["$exchange:$symbol"]?.let { System.currentTimeMillis() < it } == true

    /** 2 Nov: the paper contracts an open live trade copies (memory only): their paper card and notice stay down ([Notifier.liveCopyShown]). */
    @Volatile var paperMirroredHint: Set<String> = emptySet()
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
    data class PaperLeg(val ref: String, val key: String, val qty: Int,
                        /** 15 Nov: the paper trade entered with the breakeven rule (R51 / R72; a rebuilt live trade reads it). */
                        val fixed: Boolean = false,
                        /** 15 Nov: the paper contract's symbol, so a rebuilt live trade finds ITS paper trade (null: not given). */
                        val symbol: String? = null,
                        /** 16 Nov: the paper trade entered with the fourth version's step lock too (a rebuilt live trade reads it). */
                        val lock: Boolean = false)

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
        // 28 Oct (Boss: "Yes fix finnifty"): a paper buy filled on the live book alone (the option had no last price) is never
        // mirrored: before this change such an entry was not bought at all, so live stays exactly as it was. Memory only.
        if (ask.side > 0 && runCatching { Paper.filledOnBook(ask.contract.symbol) }.getOrDefault(false)) {
            note(ask.key, "not mirrored live: ${ask.contract.symbol}'s paper buy was priced on its book alone (no last price); live is unchanged")
            return
        }
        if (testSync) { kotlinx.coroutines.runBlocking { enterNow(ask) }; return }
        scope.launch { runCatching { enterNow(ask) }.onFailure { e -> note(ask.key, "live entry failed: ${e.javaClass.simpleName}") } }
    }

    /**
     * A paper buy to mirror: arm [key], paper trade [ref], its [contract], [side] (+1 a buy, -1 a sale), its [paperQty] filled at
     * [paperEntry], its stop [stop] (null: none on the premium) and, for a LIMIT entry, the [limit] it paid at most.
     */
    data class Ask(val key: String, val ref: String, val contract: Paper.Contract, val side: Int, val paperQty: Int, val paperEntry: Double,
                   val stop: Double? = null, val limit: Double? = null,
                   /** 15 Nov: the paper trade entered with the breakeven rule ([Pos.fixed]; only R51 / R72 ever set it). */
                   val fixed: Boolean = false,
                   /** 16 Nov: and with the fourth version's step lock ([Pos.lock]). */
                   val lock: Boolean = false)

    /** The paper trade [ref]'s stop or lock is at [paperLevel] now: the live stop follows on the next look (memory only). */
    fun level(ref: String, paperLevel: Double?) {
        if (paperLevel == null || !paperLevel.isFinite() || paperLevel <= 0) return
        testHeard?.add("level|$ref|$paperLevel")
        levels[ref] = (runCatching { now().toLocalDate() }.getOrElse { LocalDate.now() }) to paperLevel
        if (levels.size > NOTES_KEPT) prune()
    }

    /**
     * The paper trade [ref] is closed ([why]): its live trade is sold on the next look, which is asked now (memory only). 28 Oct
     * (shadow logging): [pct], the paper trade's result in percent of its entry ([pct] below), goes to the time machine's forward
     * log with the reason (arithmetic only here; the log is written on its own scope).
     */
    fun closed(ref: String, why: String, pct: Double? = null) {
        testHeard?.add("closed|$ref|$why")
        closedRefs[ref] = (runCatching { now().toLocalDate() }.getOrElse { LocalDate.now() }) to why
        // 22 Oct: the time machine's forward log learns how the trade ended (one queue add; the write runs on its own scope).
        runCatching { TimeMachineHost.settle(ref, why, pct) }
        if (closedRefs.size > NOTES_KEPT) prune()
        if (holdingHint || shadowHint) nudge()
    }

    /** 28 Oct: a paper trade's result in percent of its [entry] ([side] -1 for a sale), or null when it cannot be told. */
    fun pct(entry: Double?, exit: Double?, side: Int = 1): Double? =
        if (entry == null || exit == null || !entry.isFinite() || !exit.isFinite() || entry <= 0) null else (exit - entry) * side / entry * 100.0

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
        val gen = wipeGen
        val f = file ?: return Book().also { cache = it; loadGen = gen }
        val bytes = try {
            testReadFails?.let { if (it.getAndDecrement() > 0) throw java.io.IOException("test: the book could not be read") }
            Vault.readFileSteady(f)
        } catch (e: Vault.Tampered) { null.also { tampered(f) } } catch (e: Vault.Erasing) {
            // 26 Oct, review: everything is being erased (the book with it): no entry, no alarm; read again after the erase.
            throw Unreadable()
        } catch (e: Exception) {
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
        var badRows = 0
        val parsed = bytes == null || runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            // 18 Nov 2026 (audit: an unparsable trade was dropped unsaid): one that cannot be read is said to Boss, and every open live
            // trade is taken back from Zerodha's positions on the next look ([recover]), so none is left unwatched.
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) runCatching { b.positions += readPos(a.getJSONObject(i)) }.onFailure { badRows++ } }
            o.optJSONObject("switches")?.let { m -> m.keys().forEach { b.switches[it] = m.getBoolean(it) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONObject("refused")?.let { m -> m.keys().forEach { b.refused[it] = m.getString(it) } }
            o.optJSONObject("shadow")?.let { m -> m.keys().forEach { b.shadow[it] = m.getBoolean(it) } }
            o.optJSONObject("shadowSince")?.let { m -> m.keys().forEach { k -> runCatching { b.shadowSince[k] = LocalDateTime.parse(m.getString(k)) } } }
            o.optJSONArray("notMirrored")?.let { a -> for (i in 0 until a.length()) runCatching { a.getJSONObject(i).let { x ->
                b.notMirrored += NotMirrored(x.getString("ref"), LiveMirror.migratedKey(x.getString("key")), LocalDateTime.parse(x.getString("at")), x.getString("why")) } } }
        }.isSuccess
        if (!parsed) tampered(f)
        else if (badRows > 0) {
            recoverDue = true
            com.optionslab.app.work.Alerts.error("The arms' live book had $badRows trade(s) that could not be read: open live trades are taken back " +
                "from Zerodha's positions on the next look. Check them in Trade.", WHO)
        }
        // No file (new, or set aside) or a rebuilt one: Boss's switches from their second copy, never fresh defaults.
        if (bytes == null || !parsed) restoreSwitches(b)
        if (unreadable) runCatching { Diag.record("armlive", "the live book was read again: its open live trades are watched") }
        unreadable = false
        readFails = 0
        loadGen = gen
        // 26 Oct: a book read while an erase ran is used by this pass only (never kept, shown or saved).
        if (gen != wipeGen) return b
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
        return Pos(LiveMirror.migratedKey(p.getString("key")), p.getString("ref"), p.getString("symbol"), p.getString("exchange"), p.getString("product"),
            p.getInt("side"), p.getInt("qty"), p.getInt("entryQty"), p.getInt("lot"), p.getDouble("tick"), p.optInt("mult", 1),
            p.getDouble("entry"), LocalDateTime.parse(p.getString("entryTime")), p.getDouble("paperEntry"), p.getInt("paperQty"),
            s("expiry")?.let { LocalDate.parse(it) }, s("entryOrderId"), s("stopOrderId"), d("stopTrigger"), p.optBoolean("seen"),
            p.optBoolean("unconfirmed"), d("exit"), s("exitTime")?.let { LocalDateTime.parse(it) }, s("why"), p.optDouble("charges", 0.0),
            p.optBoolean("shadow"), s("stopKind") ?: OWN, if (p.has("stopQty")) p.getInt("stopQty") else null,
            p.optBoolean("heldSeen", !p.has("heldSeen")), if (p.has("zeroSince")) p.getLong("zeroSince") else null, p.optInt("stopBooked", 0),
            s("paperSymbol"), p.optBoolean("step500", false), d("best"), p.optBoolean("stepLock", false))
    }

    private fun writePos(p: Pos): JSONObject = JSONObject().put("key", p.key).put("ref", p.ref).put("symbol", p.symbol).put("exchange", p.exchange)
        .put("product", p.product).put("side", p.side).put("qty", p.qty).put("entryQty", p.entryQty).put("lot", p.lot).put("tick", p.tick)
        .put("mult", p.multiplier).put("entry", p.entry).put("entryTime", p.entryTime.toString()).put("paperEntry", p.paperEntry)
        .put("paperQty", p.paperQty).put("expiry", p.expiry?.toString() ?: "").put("entryOrderId", p.entryOrderId ?: "")
        .put("stopOrderId", p.stopOrderId ?: "").apply { p.stopTrigger?.let { put("stopTrigger", it) }; p.exit?.let { put("exit", it) } }
        .put("seen", p.seen).put("unconfirmed", p.unconfirmed).put("exitTime", p.exitTime?.toString() ?: "").put("why", p.why ?: "")
        .put("charges", p.charges).put("shadow", p.shadow).put("stopKind", p.stopKind).put("heldSeen", p.heldSeen)
        .apply { p.stopQty?.let { put("stopQty", it) }; p.zeroSinceMs?.let { put("zeroSince", it) }; p.paperSymbol?.let { put("paperSymbol", it) } }
        .put("stopBooked", p.stopBooked).apply { if (p.fixed) put("step500", true); p.best?.let { put("best", it) }; if (p.lock) put("stepLock", true) }

    /**
     * The book written (and shown). 18 Nov 2026 (audit: a failed write was swallowed): false when it could not be written - Boss is told
     * once until a write works again, and the next look writes it again ([saveDue]).
     */
    private fun save(b: Book): Boolean {
        // 26 Oct: a book read before an erase is never written back over it.
        if (loadGen != wipeGen) return false
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
        val ok = file?.let { f -> runCatching {
            testWriteFails?.let { if (it.getAndDecrement() > 0) throw java.io.IOException("test: the book could not be written") }
            written.write(f, o.toString().toByteArray(Charsets.UTF_8))
        }.isSuccess } ?: true
        if (!ok && !saveDue) com.optionslab.app.work.Alerts.error("The arms' live book could not be saved: its open live trades are still " +
            "watched, and it is saved again on the next look. Check open Zerodha positions in Trade.", WHO)
        saveDue = !ok
        publish(b)
        return ok
    }

    /** 18 Nov 2026 (audit): the last write of the book failed (memory): the next look writes it again. */
    @Volatile private var saveDue = false

    /** The switches' second copy (a worker: only Boss's switch changes write it). */
    private fun backupSwitches(b: Book) {
        if (loadGen != wipeGen) return
        runCatching {
            SecurePrefs.putAll(mapOf(K_SWITCHES to JSONObject().apply { b.switches.forEach { (k, v) -> put(k, v) } }.toString(),
                K_SHADOW to JSONObject().apply { b.shadow.forEach { (k, v) -> put(k, v) } }.toString()))
        }
    }

    private fun publish(b: Book) {
        if (loadGen != wipeGen) return
        val open = b.positions.filter { it.open }
        val real = open.filter { !it.shadow }
        holdingHint = real.isNotEmpty()
        shadowHint = open.any { it.shadow }
        liveKeysHint = real.map { "${it.exchange}:${it.symbol}" }.distinct()
        // (Not one Zerodha has not confirmed yet: no live card stands for it, so the paper card stays.)
        paperMirroredHint = real.filter { !it.unconfirmed }.mapNotNull { it.paperSymbol }.toSet()
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

    /** TEST ONLY: the next this many writes of the book fail (18 Nov 2026 audit). Debug builds only. */
    @Volatile internal var testWriteFails: java.util.concurrent.atomic.AtomicInteger? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** TEST ONLY: the legs [family]'s registered paper book gives now (null: none registered, or not read). */
    internal fun legsForTest(family: String): List<PaperLeg>? = hosts[family]?.open()

    private fun now(): LocalDateTime = (testNow ?: Market.now()).toLocalDateTime().withNano(0)
    private fun nowMs(): Long = (testNow ?: Market.now()).toInstant().toEpochMilli()
    /** 15 Nov: the phone's clock for [ArmOrderLabels] - the market clock, the one Zerodha's order times are compared with ([Broker.findRecent]). */
    private fun labelMs(): Long = runCatching { Market.now().toInstant().toEpochMilli() }.getOrDefault(System.currentTimeMillis())

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
     * 30 Oct (Boss: "Make stop bot different for index and comodities"): whether a live entry of this side is stopped today. The
     * index side: its own day stop. The commodity side: its own stop (or an older build's kept for MCX), or the index side's LOSS
     * stop - which stopped every live entry before the split and still does (the live loss door, [LossBreaker.liveDoorTripped], is
     * read beside it). Boss's own index stop no longer stops an MCX live entry.
     */
    private suspend fun sideStopped(mcx: Boolean): Boolean {
        val index = Strategies.stoppedWhy()
        if (!mcx) return index != null
        return index == com.optionslab.ira.DayStop.Why.LOSS || Strategies.mcxStoppedWhy() != null || LossBreaker.bossStoppedToday()
    }

    /** The exit side of [sideStopped]: an MCX live position sells on the commodity stop that sells, or the index LOSS stop. */
    private suspend fun sideSells(mcx: Boolean): Boolean {
        val index = Strategies.stoppedWhy()
        if (!mcx) return index != null
        return index == com.optionslab.ira.DayStop.Why.LOSS || Strategies.mcxStopSells() || LossBreaker.bossStoppedToday()
    }

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
            // 30 Oct: an MCX arm reads the commodity side's stop, every other arm the index side's ([sideStopped]).
            stopped = runCatching { sideStopped(LiveMirror.spec(key)?.exchange == "MCX") }.getOrDefault(true),
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

    /** TEST ONLY: the live lot cap in place of [LiveMirror.liveLotCap] (0: none), for the multi-lot exit tests. Debug builds only. */
    @Volatile internal var testLotCap: Int? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

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

    /** 18 Nov 2026 (audit A4): [p] in place of the entry intent of paper trade [ref] ([enterNow]), or added when there is none. */
    private fun Book.putIntent(ref: String, p: Pos) {
        val i = positions.indexOfFirst { it.ref == ref && it.open && !it.shadow && it.unconfirmed && it.entryOrderId == null }
        if (i >= 0) positions[i] = p else positions += p
    }

    /** A paper trade not mirrored live ([why]), noted on its arm's row (and the diagnostics) - only when the app is in Live. */
    private suspend fun notMirrored(a: Ask, why: String) {
        runCatching {
            lock.withLock {
                val b = book()
                // 18 Nov 2026 (audit A4): the entry's intent written before its order went ([enterNow]) is dropped: nothing was bought.
                // (Only it can be an unconfirmed row of this paper trade with no order id here: one live trade per paper trade, ever.)
                b.positions.removeAll { it.ref == a.ref && it.open && !it.shadow && it.unconfirmed && it.entryOrderId == null }
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
        // 30 Oct (Boss: "1 lot per arm entry, never more unless Boss sets it"): every arm goes live at Boss's lots (3 Nov: his Bot
        // settings' setting, default 1), said on its own entry line - except ntfy alerts, whose lots are his setting on its page.
        // The order gates still apply to all.
        // 3 Nov (Boss): the live lots are Boss's own setting in Bot settings ([AppSettings.armLiveLots], default 1).
        var qty = testLotCap?.let { LiveMirror.liveQty(lots, ins.lot, it) } ?: LiveMirror.armLiveQty(lots, ins.lot, spec, s.armLiveLots)
        if (qty <= 0) { notMirrored(a, "the paper trade is under one lot"); return@withLock }
        var cut = LiveMirror.cutWords(lots, qty, ins.lot, s.armLiveLots)
        val qkey = "${ins.exchange}:${ins.symbol}"
        val quote = runCatching { Broker.stopQuotes(listOf(qkey))[qkey] }.getOrNull()
        val last = quote?.last?.takeIf { it > 0 } ?: run { notMirrored(a, "no Zerodha price for ${ins.symbol}"); return@withLock }
        // 4 Nov (Boss: "if I select 3 lots, buy 3 if the money is there, else try 2, else 1"; "make it default, don't have one
        // more option"): an index option buy of more than one lot is cut to the lots the free cash fits - only ever fewer than his
        // setting, never more. Priced at the higher of the last price, the offer and its own limit. A sale, a future or an MCX
        // option (its own segment's money) is never cut; not even one lot fitting, or the cash unreadable, goes as before.
        val want = qty / ins.lot
        // 15 Nov: the funds check below reuses the fit's own read when it ran (nothing new is read for it).
        val optionBuy = a.side > 0 && a.contract.right != Right.IX && ins.exchange != "MCX"
        val fitPx = maxOf(last, quote?.ask?.takeIf { it.isFinite() && it > 0 } ?: last, a.limit ?: 0.0)
        var fitFree: Double? = null
        if (want > 1 && optionBuy) {
            val free = Broker.freeCash()
            fitFree = free
            val px = fitPx
            val fit = LiveMirror.lotsThatFit(want, px * ins.lot * ins.multiplier, free)
            if (free != null && fit in 1 until want) {
                qty = fit * ins.lot
                cut += LiveMirror.fitWords(want, fit, free)
                runCatching { Diag.record("armlive", "${spec.label}: ${ins.symbol} ${LiveMirror.fitWords(want, fit, free).removePrefix(" · ")}") }
            }
        }
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
        // (25 Oct: dated by the market clock, the one the door reads back; in the app the arms' clock is the same one.)
        val verdict = OrderVerdicts.cleared(OrderVerdicts.liveFields(o, Market.today(), "arm:${a.key}", stop = a.stop), "arm live gate")
        val base = Pos(a.key, a.ref, ins.symbol, ins.exchange, spec.product, if (a.side >= 0) 1 else -1, qty, qty, ins.lot, ins.tick, ins.multiplier,
            last, now(), a.paperEntry, a.paperQty, a.contract.expiry, null, seen = paperOpen(spec, a.ref) == true, paperSymbol = a.contract.symbol,
            fixed = a.fixed && a.side > 0 && com.optionslab.engine.orb.StepTrail.covers(a.key),
            lock = a.fixed && a.lock && a.side > 0 && com.optionslab.engine.orb.StepTrail.lockFor(a.key) != null)
        // Shadow read again right before anything is sent (review: Shadow turned on while this entry was on its way).
        // 3 Nov (Boss): an index not selected in Bot settings → Indices stays in shadow (recorded, never sent).
        // (Read again here, just before anything is sent: a change made while this entry was on its way counts.)
        val marketOff = !LiveMirror.marketOn(a.contract.underlying, AppSettings.load().liveMarkets)
        if (marketOff) note(a.key, "${spec.label}: ${a.contract.underlying} is not one of your live indices (Bot settings → Indices): kept in shadow")
        val shadowNow = shadowed || marketOff || (a.key == LiveMirror.SOLO_MIDDAY && soloWhy() != null) ||
            try { lock.withLock { LiveMirror.shadowOn(a.key, book().shadow) } } catch (e: Unreadable) {
                note(a.key, "not mirrored: the live book could not be read"); return@withLock
            }
        if (shadowNow) { enterShadow(spec, a, base, o, quote, cut); return@withLock }
        // 10 Nov (Boss's diagnostics: R51 and R72 bought the same contract within minutes on 3, 4 and 6 Nov): SHADOW only - logged
        // when another arm already holds this contract live the same way; nothing is skipped or changed ([LiveMirror.sameContractWords]).
        // Here, after every gate and the shadow check, so only an entry really on its way to Zerodha is counted.
        runCatching {
            val others = lock.withLock {
                book().positions.filter { it.exit == null && !it.shadow && it.key != a.key && it.symbol == ins.symbol && it.exchange == ins.exchange &&
                    it.side == (if (a.side >= 0) 1 else -1) }.map { LiveMirror.spec(it.key)?.label ?: it.key }
            }
            LiveMirror.sameContractWords(spec.label, ins.symbol, others)?.let { note(a.key, it) }
        }
        // 15 Nov (Boss: "Yes" to it in shadow; 25 arm live buys of 2-6 Nov refused by Zerodha, "Insufficient funds"): the funds check -
        // an option buy (index or stock) the free cash cannot pay for even one lot. Its figure is what this path already read: the fit's read
        // above, else the margins the account guard's read just used ([Broker.keptFreeCash], memory). Unknown: it says nothing. In
        // SHADOW (its switch off, the default) the order goes exactly as before and the would-skip is logged; on, it is not sent.
        if (optionBuy) {
            val free = fitFree ?: Broker.keptFreeCash()
            FundsCheck.check(spec.label, ins.symbol, qty / ins.lot, fitPx * ins.lot * ins.multiplier, free, fitPx)?.let { why ->
                notMirrored(a, why); return@withLock
            }
        }
        // 18 Nov 2026 (audit A4: a process death between the POST and the booking left a live lot no trade owned, with no stop): the
        // entry's intent - this trade, held as unconfirmed with no order id yet - is written to the book BEFORE the order goes. After a
        // restart the look settles it against Zerodha ([settle]: found or held, taken back with its stop; neither, dropped with Boss
        // told). Not written: no order goes. (The entry path, never the tick path.)
        val intentKept = runCatching { lock.withLock { val b = book(); b.positions += base.copy(unconfirmed = true); save(b) } }.getOrDefault(false)
        if (!intentKept) { notMirrored(a, "the arms' live book could not be written before the order (nothing sent)"); return@withLock }
        val known = known()
        // (The entry door asks the switches again inside it, from memory: [doorRefusal].)
        testHeard?.add("door|${a.key}|${a.ref}")
        // 15 Nov (audit: 27 live orders Zerodha rejected, "Insufficient funds", were shown with no arm): this arm's label is noted
        // BEFORE the order goes (memory only: [ArmOrderLabels]), so an order Zerodha answers with a refusal but still shows as
        // REJECTED in its order book is shown with its arm. Before, the label was written only once Zerodha gave the order's id.
        // Nothing about the entry itself changes.
        val owner = "${spec.label} · live entry"
        val sent = ArmOrderLabels.sending(o, owner, labelMs())
        val id: String? = try {
            Broker.placeOrder(o, arm = "arm:${a.key}", intended = last, verdict = verdict, stop = a.stop)
        } catch (e: Broker.NotLoggedIn) {
            notMirrored(a, "not logged in to Zerodha today"); return@withLock
        } catch (e: Exception) {
            // 15 Nov: Zerodha's own refusal (no id): its try may name the REJECTED order in Zerodha's book. A lost reply, or the app's
            // own refusal at the door (nothing was sent), is left alone.
            if (ArmOrderLabels.zerodhaRefused(e)) ArmOrderLabels.refused(sent)
            // 3 Nov (live): Zerodha blocks MIS MARKET orders on a contract with little open interest ("MIS MARKET orders are blocked for
            // this FINNIFTY contract due to its open interest (OI) being less than 100 lots"): the same entry goes once as a LIMIT at
            // the offer, never more than 1% over the last price ([LiveMirror.blockedMarketLimit]); unfilled in 15 s it is cancelled below.
            val retry = if (Broker.definite(e) && o.orderType == "MARKET" && LiveMirror.marketBlocked(e.message))
                LiveMirror.blockedMarketLimit(side == Kite.Side.BUY, last, quote?.ask, quote?.bid)?.let { px ->
                    o.copy(orderType = "LIMIT", price = Kite.onTick(px, ins.tick, side)) } else null
            if (retry != null) {
                note(a.key, "${spec.label}: Zerodha blocks MARKET orders on ${ins.symbol} (low open interest): sent as a LIMIT at %.2f instead"
                    .format(Locale.ENGLISH, retry.price ?: 0.0))
                val v2 = OrderVerdicts.cleared(OrderVerdicts.liveFields(retry, Market.today(), "arm:${a.key}", stop = a.stop), "arm live gate (LIMIT, MARKET blocked)")
                val sent2 = ArmOrderLabels.sending(retry, owner, labelMs())               // 15 Nov: the LIMIT's own try, as above
                try { Broker.placeOrder(retry, arm = "arm:${a.key}", intended = last, verdict = v2, stop = a.stop) }
                catch (e2: Broker.NotLoggedIn) { notMirrored(a, "not logged in to Zerodha today"); return@withLock }
                catch (e2: Exception) {
                    if (ArmOrderLabels.zerodhaRefused(e2)) ArmOrderLabels.refused(sent2)
                    if (Broker.definite(e2)) { notMirrored(a, e2.message ?: "Zerodha refused the order"); return@withLock }
                    try { Broker.findRecentRetrying(retry, known) } catch (_: Exception) { null }
                }
            } else {
            if (Broker.definite(e)) { notMirrored(a, e.message ?: "Zerodha refused the order"); return@withLock }
            // The answer was lost, not necessarily the order: look before calling it unsent. 30 Oct (deep review): an order book that
            // does not show it yet is not proof it was never placed (the book can trail the POST) - the entry is held as UNCONFIRMED
            // with no order id, so [settle] keeps looking for it by its tag for [UNCONFIRMED_GRACE_MS] before dropping it, and a filled
            // order is never left at Zerodha with no stop and no watch.
            try { Broker.findRecentRetrying(o, known) } catch (_: Exception) { null }
            }
        }
        // 15 Nov: by its id in memory at once too (the order review reads it even if the labels' book could not be read just now).
        id?.let { ArmOrderLabels.answered(it, owner, labelMs()) }
        id?.let { runCatching { Strategies.tagOwner("kite:$it", owner) } }
        var f = id?.let { runCatching { Broker.awaitOrder(it, 15_000) }.getOrNull() }
        if (id != null && f?.status !in DONE) {
            // Not finished in 15 s: the rest is cancelled so it can never fill later untracked; only what filled is booked.
            runCatching { Broker.cancel(id) }
            f = runCatching { Broker.orderState(id) }.getOrNull()
        }
        if (f == null || f.status !in DONE) {
            // Zerodha has not said how it ended: held as unconfirmed (no second entry meanwhile) until the order book settles it.
            lock.withLock { val b = book(); b.putIntent(a.ref, base.copy(entryOrderId = id, unconfirmed = true)); line(b, "${spec.label}: bought ${ins.symbol}, not yet confirmed"); save(b) }
            com.optionslab.app.work.Alerts.error("${spec.label}: Zerodha did not confirm the live ${side.name.lowercase()} of ${ins.symbol}. " +
                "It is treated as held until the order book shows what filled.", WHO)
            return@withLock
        }
        if (f.filled <= 0) { notMirrored(a, "Zerodha ${f.status.lowercase()}" + (f.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")); return@withLock }
        val fill = f.avgPrice.takeIf { it > 0 } ?: last
        runCatching { Notifier.orderFilled(app, side.name, f.filled, ins.symbol, fill, "Live", "${spec.label} · live entry", f.orderId) }
        // 2 Nov (Boss: "when any live order is placed, I see 2 notifications, 1 of paper and 1 of live"): the live card is the one
        // shown; the paper copy's card and its "bought · Paper" notice are taken down (the paper trade itself goes on, in the app).
        runCatching { Notifier.liveCopyFilled(app, a.contract.symbol) }
        // Seen held only once Zerodha's position book shows it (review C3): a book that lags the fill never closes the trade.
        var p = base.copy(entryOrderId = id, qty = f.filled, entryQty = f.filled, entry = fill, heldSeen = false,
            charges = charge(side.name, fill, f.filled, ins.exchange, ins.symbol, ins.multiplier))
        val (stop, kind) = firstStop(spec, p)
        p = p.copy(stopKind = kind, stopTrigger = stop)
        // 30 Oct (deep review): the filled trade is WRITTEN before its stop is rested, so a process death in between still finds it
        // after the restart (watched first, its stop rested by the look: PLACE_STOP) - before, a death there left a live position
        // nothing knew of. While its stop is being rested the looks leave THIS trade alone ([resting]: no second stop, no exit that
        // would miss the stop just placed); every other live trade is looked at as usual.
        resting += a.ref
        p = try {
            lock.withLock { val b = book(); b.putIntent(a.ref, p); save(b) }
            // The stop rests at once, on the paper trade's level on this fill (or the safety / disaster stop).
            val rested = restStop(p, stop, ltp = fill)
            lock.withLock {
                val b = book()
                val i = b.positions.indexOfFirst { it.ref == a.ref && it.open && !it.shadow }
                if (i >= 0) b.positions[i] = rested else b.positions += rested
                save(b)
            }
            rested
        } finally { resting -= a.ref }
        lock.withLock {
            val b = book()
            b.refused.remove(a.key)
            line(b, "%s: %s %s %d @ %.2f live%s%s".format(Locale.ENGLISH, spec.label, if (p.side > 0) "bought" else "sold", ins.symbol, p.qty, fill, cut,
                p.stopTrigger?.let { " · %s %.2f %s".format(Locale.ENGLISH, stopWords(p), it, if (p.stopOrderId != null) "at Zerodha" else "watched by the app (not rested yet)") }
                    ?: " · no stop on the premium (the app follows its paper exits)"))
            save(b)
        }
        runCatching { Diag.record("armlive", "${spec.label}: live entry ${ins.symbol} ${p.qty} @ %.2f".format(Locale.ENGLISH, fill)) }
        if (closedRefs.containsKey(a.ref)) nudge()
    }

    private fun stopWords(p: Pos): String = when (p.stopKind) { SAFETY -> "safety stop"; DISASTER -> "disaster stop"
        // 15 Nov: the breakeven rule's stops (Boss's exception), named as such on the entry line and the fill's card.
        else -> stopWhyOf(p).takeIf { com.optionslab.engine.orb.StepTrail.isRuleWhy(it) }
            ?.let { com.optionslab.engine.orb.StepTrail.words(it) } ?: "stop" }

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

    /**
     * The paper trade's stop or lock as the live level on [p]'s fill (null: none; never for a close-judged arm). 15 Nov: with the breakeven
     * rule ([Pos.fixed]) the tighter of that and the live trade's own rule stop ([ruleStop]) - so its resting SL at Zerodha moves up to the
     * breakeven once earned, for the live position, a dead phone or not. 16 Nov (review): for a rule trade the paper level is the arm's OWN
     * exits only (its stop and R64's ladder lock: [PassedArmsHost] leaves the rule's breakeven and step lock out of it), so the rule's
     * levels come from the live trade alone - its live fill, entry lots and live best price (the stream, and the option's own minute highs:
     * [highs]) - never the paper trade's levels, worked out on the paper fill.
     */
    private fun wanted(p: Pos): Double? {
        val spec = LiveMirror.spec(p.key) ?: return null
        if (spec.closeStop) return null
        val scaled = levelOf(p.ref)?.let { LiveMirror.level(p.paperEntry, it, p.entry, spec.scale, p.side, p.tick) }
        return com.optionslab.engine.orb.StepTrail.tighterStop(scaled, ruleStop(p))
    }

    /** 15 Nov: [p]'s lot size for the stop rule (per lot: Zerodha's lot, in units for an option). */
    private fun ruleLot(p: Pos): Int = (p.lot * p.multiplier.coerceAtLeast(1)).coerceAtLeast(1)

    /** 16 Nov: [p]'s lock of the fourth version (R51 / R72's ₹500 step lock), or null: V4 alone (entered before it, or not a rule trade). */
    private fun ruleLock(p: Pos): com.optionslab.engine.orb.StepTrail.Lock? = if (p.fixed && p.lock) com.optionslab.engine.orb.StepTrail.lockFor(p.key) else null


    /**
     * 16 Nov: [p]'s lock levels (null: V4 alone), built once for the trade on its live fill, lot and entry quantity ([ruleQty]) and kept,
     * so the full-cadence watch never redoes the charge maths. Pure memory; a fill confirmed later gets its own. Bounded: a day's trades.
     */
    private fun ladderOf(p: Pos): com.optionslab.engine.orb.StepTrail.Ladder? =
        ruleLock(p)?.let { com.optionslab.engine.orb.StepTrail.ladder(p.entry, ruleLot(p), ruleQty(p), it, p.tick) }

    /**
     * 16 Nov: [p]'s quantity at the entry in units, for the lock's per-lot rupees (lots = this / [ruleLot]). The entry's (review): a part
     * that a stop sold never changes the levels, so the lock can never step down (R186 keeps the lots for the whole trade).
     */
    private fun ruleQty(p: Pos): Int = (maxOf(p.entryQty, p.qty) * p.multiplier.coerceAtLeast(1)).coerceAtLeast(1)

    /**
     * 15 Nov: [p]'s own stop-rule stop now - the breakeven once its [Pos.best] was 10% up; 16 Nov: or the step lock's, the higher - on its
     * tick (null: not a rule trade).
     */
    internal fun ruleStop(p: Pos): Double? = if (!p.fixed || p.side <= 0) null
        else com.optionslab.engine.orb.StepTrail.levelOf(p.entry, ruleLot(p), p.best, p.tick, ladderOf(p))

    /**
     * 15 Nov: the reason a sale at [ruleStop] is booked with: the breakeven's once earned (16 Nov: the step lock's when that is the level),
     * else the entry stop's (unused since V4: no entry stop).
     */
    internal fun ruleWhy(p: Pos): String {
        val lv = if (p.fixed) com.optionslab.engine.orb.StepTrail.trailLevel(p.entry, ruleLot(p), p.best, p.tick, ladderOf(p)) else null
        return when {
            lv == null -> com.optionslab.engine.orb.StepTrail.WHY_STOP
            com.optionslab.engine.orb.StepTrail.whyAt(lv, p.entry, ruleLot(p), p.tick, ladderOf(p), p.best) ==
                com.optionslab.engine.orb.StepTrail.WHY_LOCK -> com.optionslab.engine.orb.StepTrail.WHY_LOCK
            else -> com.optionslab.engine.orb.StepTrail.WHY_TRAIL
        }
    }

    /**
     * 15 Nov: whether [p] (after [withBest]) is worth saving over [was]: anything but a higher best price that earned no new step. Only a
     * new step is written (the best price changes nothing until it earns the breakeven at +10%: [com.optionslab.engine.orb.StepTrail.trailLevel]), so a
     * new high is never a vault write of its own on the stream's look.
     */
    private fun changed(was: Pos, p: Pos): Boolean = p != was && (p.copy(best = was.best) != was || ruleStop(p) != ruleStop(was))

    /**
     * 15 Nov: [p] with its best price raised to [ltp] (a rule trade only; never lowered). 16 Nov (review): or to [high] - the highest the
     * same option traded after its live entry minute ([highSince]: the stream's kept ticks, else the closed-minute candles' highs, as the
     * research's minute highs) - whichever is higher.
     */
    internal fun withBest(p: Pos, ltp: Double?, high: Double? = null): Pos {
        if (!p.fixed) return p
        val top = listOfNotNull(ltp?.takeIf { it > 0 && it.isFinite() }, high?.takeIf { it > 0 && it.isFinite() }).maxOrNull() ?: return p
        return if (top <= (p.best ?: p.entry)) p else p.copy(best = top)
    }

    /**
     * 16 Nov (review): the highest [p]'s option traded after its live entry minute, for a rule trade's best price - one shared helper with
     * the arms' paper books ([Paper.highSince]: the stream's kept ticks while it streams the contract, else the 1-minute candles after the
     * entry minute). Only on the regular look ([fresh]): the stream's fast look reads nothing more than its price (the tick path stays
     * memory only), the next regular look catches up. Null: not a rule trade, or no price. Never on the order path.
     */
    private fun highSince(p: Pos, fresh: Boolean): Double? {
        if (!p.fixed || p.side <= 0) return null
        // Review: never a read on the look itself - the read runs in the background ([RuleHighs]: one at a time per trade, the stream's
        // kept ticks and the candles after the entry minute, so a spike while the phone was away or across a restart still counts) and
        // the look takes the last high it found (memory). Asked on the regular look only.
        if (fresh) (p.paperSymbol ?: p.symbol).let { runCatching { Paper.contractOf(it) }.getOrNull() }?.let { RuleHighs.ask("live:${p.ref}", it, p.entryTime) }
        return RuleHighs.peek("live:${p.ref}")
    }


    /**
     * 15 Nov: the open paper trade of the arm [spec] a live trade rebuilt from Zerodha's [symbol] copies: found by the same contract (index,
     * expiry, strike, right) when exactly one open paper trade of the arm is on it; null with no confident match (the BFO list not read yet
     * after a restart, an unreadable book, none or two on it). Memory only.
     */
    private fun rebuiltLeg(spec: LiveMirror.Spec, symbol: String): PaperLeg? = runCatching {
        val ins = (Broker.cachedInstruments().orEmpty() + Broker.cachedBfoOptions().orEmpty()).firstOrNull { it.tradingSymbol == symbol }
            ?: return null
        hosts[spec.family]?.open()?.filter { leg -> leg.key == spec.key && leg.qty > 0 && leg.symbol?.let { Paper.contractOf(it) }?.let { c ->
            c.underlying == ins.name && c.expiry == ins.expiry && c.right == ins.right && kotlin.math.abs(c.strike - ins.strike) < 1e-6 } == true }
            ?.singleOrNull()
    }.getOrNull()

    /**
     * The breakeven rule and the step lock (in that order) of a live trade rebuilt from Zerodha's [symbol], placed at [placedAt] (Zerodha's
     * order time; null: not known). Its own paper trade's flags when one matches ([rebuiltLeg]). With no confident match: the rule is kept
     * (15 Nov re-review: it only tightens a live stop; following the switch could take it away from a trade that entered with it), and
     * the step lock (16 Nov review: an open trade keeps the version it entered with) goes by when the trade was placed
     * ([StepTrailExits.lockAt]: before the fourth version took effect, or while Boss had it off: V4 alone; else the lock). Only when that
     * cannot be told (no time, or no record of when it took effect) is the tighter side taken, and that is logged.
     */
    internal fun rebuiltRule(spec: LiveMirror.Spec, symbol: String, placedAt: LocalDateTime? = null): Pair<Boolean, Boolean> {
        if (!com.optionslab.engine.orb.StepTrail.covers(spec.key)) return false to false
        val leg = rebuiltLeg(spec, symbol)
        val fixed = leg?.fixed ?: true
        if (!fixed || com.optionslab.engine.orb.StepTrail.lockFor(spec.key) == null) return fixed to false
        if (leg != null) return true to leg.lock
        // Review: the history is read first when the start's background read has not landed yet (this is the rebuild's worker, after
        // the open positions were restored and their stops watched) - never judged on an empty memory.
        runCatching { StepTrailExits.ensureRead() }
        // Review: by the version in force when it was placed (the fourth version's start and Boss's switch-offs of it), never the switch now.
        val then = placedAt?.let { StepTrailExits.lockAt(it) }
        val lock = then ?: true.also { runCatching { Diag.record("arm-live", "${spec.label}: $symbol rebuilt with no paper match and no way to tell " +
            "its version (${if (placedAt == null) "no order time" else "no record of when the step lock started"}): the step lock kept, the tighter side") } }
        return true to lock
    }

    /** Zerodha's order time [placedAt] ("yyyy-MM-dd HH:mm:ss", IST) as a time (null: not given or not read). */
    internal fun placedTime(placedAt: String?): LocalDateTime? =
        placedAt?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDateTime.parse(it.replace(' ', 'T')) }.getOrNull() }

    /**
     * 15 Nov: what a SELL decided on a stop ([LiveMirror.decide]'s [why]) is booked as: a stop at the stop rule's levels is "₹500 stop" (older records) /
     * "breakeven lock" whatever its kind (a rebuilt trade's safety stop moved there too), checked before the safety / disaster words.
     */
    internal fun sellWhy(why: String?, p: Pos): String {
        if (why != "stop" && why != "stop_run_through") return why ?: "exit"
        val rule = stopWhyOf(p)
        return when {
            com.optionslab.engine.orb.StepTrail.isRuleWhy(rule) -> rule
            p.stopKind != OWN -> p.stopWord
            else -> why
        }
    }

    /** 15 Nov: what a stop exit of [p] is booked as - "₹500 stop" (older records) / "breakeven lock" when its stop rests at those levels, else [Pos.stopWord]. */
    internal fun stopWhyOf(p: Pos): String =
        // Any stop of its own or a safety stop at those levels (a rebuilt trade's starts as a safety stop); never the disaster stop.
        // 16 Nov (review): a stop resting at the rule's own level now is the rule's ([ruleWhy]) whatever the tick rounding; else any of
        // the rule's levels ([com.optionslab.engine.orb.StepTrail.whyAt]: the breakeven, a step lock); else the stop's own word.
        (if (p.fixed && p.stopKind != DISASTER) {
            val t = p.stopTrigger
            ruleStop(p)?.takeIf { t != null && kotlin.math.abs(it - t) < p.tick / 2 }?.let { ruleWhy(p) }
                ?: com.optionslab.engine.orb.StepTrail.whyAt(t, p.entry, ruleLot(p), p.tick, ladderOf(p), p.best)
        } else null) ?: p.stopWord

    // ---- the stop at Zerodha -------------------------------------------------------------------------------------------------

    private fun gttLeg(trigger: Double, tick: Double, side: Int): Double =
        Kite.onTick(if (side > 0) trigger * 0.95 else trigger * 1.05, tick, if (side > 0) Kite.Side.SELL else Kite.Side.BUY).coerceAtLeast(tick)

    /**
     * [p]'s stop rested at Zerodha at [at] (with the price at [ltp]): an SL order with its limit for an intraday trade, a single-leg
     * GTT for a carried one - only while the exits already working on the contract plus this one fit in what Zerodha holds. [p] with
     * its order and trigger, or with the trigger only (the app watches it, and rests it again on the next look - said, throttled).
     */
    private suspend fun restStop(p: Pos, at: Double?, ltp: Double): Pos {
        if (handsOffNow(p.exchange, p.symbol)) return p              // Boss is exiting it by hand ([handsOff])
        val spec = LiveMirror.spec(p.key) ?: return p
        val trigger = at ?: return p
        if (!LiveMirror.mayRest(trigger, ltp, p.tick, p.side)) return p.copy(stopTrigger = trigger)
        val exitSide = if (p.side > 0) Kite.Side.SELL else Kite.Side.BUY
        // 18 Nov 2026 (audit A2): an exit whose reply was lost may still be selling it: no stop beside it until that is known.
        if (lostExitHolds(p.contract)) return p.copy(stopTrigger = trigger)
        // 20 Oct, enhancement round 2: read and rested in the contract's one exit turn ([ExitCoordinator]; re-entrant when an exit
        // that did not go puts its stop back).
        val r = ExitCoordinator.turn(p.exchange, p.symbol, "arm ${p.label} stop") { restStopInTurn(p, trigger, ltp, spec, exitSide) }
        // 18 Nov 2026 (audit A4): a stop that now rests is written to the book at once (not only at the end of the look), so a restart
        // a moment later still knows it as this trade's own.
        if (r.stopOrderId != null && r.stopOrderId != p.stopOrderId) persistStop(r)
        return r
    }

    /**
     * 18 Nov 2026 (audit A4): [p]'s resting stop (its order id, trigger, quantity and kind) written at once into its open row of the book.
     * The look's own change of that row still lands ([commit] finds a row that differs only in its stop). Never on the tick path: the
     * look's and the entry's workers only.
     */
    private suspend fun persistStop(p: Pos) {
        runCatching {
            lock.withLock {
                val b = book()
                val i = b.positions.indexOfFirst { it.ref == p.ref && it.open && !it.shadow && it.contract == p.contract }
                val was = b.positions.getOrNull(i)
                val now = was?.copy(stopOrderId = p.stopOrderId, stopTrigger = p.stopTrigger, stopQty = p.stopQty, stopBooked = p.stopBooked, stopKind = p.stopKind)
                if (now != null && now != was) { b.positions[i] = now; save(b) }
            }
        }
    }

    /** 18 Nov 2026 (audit A4): [a] and [b] are one row but for their stop's fields (what [persistStop] writes). */
    private fun sameButStop(a: Pos, b: Pos): Boolean =
        a.copy(stopOrderId = b.stopOrderId, stopTrigger = b.stopTrigger, stopQty = b.stopQty, stopBooked = b.stopBooked, stopKind = b.stopKind) == b

    /**
     * 18 Nov 2026 (audit A4): a working SL / SL-M of this path ([LiveMirror.TAG]) on [p]'s contract, product and exit side that no open
     * trade of the book owns - [p]'s own stop placed just before a restart, whose id the book never got - newest first, or null. The
     * trade takes it as its stop instead of counting it as another order (as [recover] does after a damaged book).
     */
    private suspend fun orphanStop(p: Pos, rows: Collection<Broker.OrderRow>): Broker.OrderRow? {
        val exitSide = if (p.side > 0) "SELL" else "BUY"
        val cands = rows.filter { it.working && it.tag == LiveMirror.TAG && it.symbol == p.symbol && it.exchange == p.exchange &&
            it.product == p.product && it.side == exitSide && (it.type == "SL" || it.type == "SL-M") }
        if (cands.isEmpty()) return null
        val owned = runCatching { lock.withLock { book().positions.filter { it.open && !it.shadow }.mapNotNull { it.slId }.toSet() } }.getOrNull() ?: return null
        return cands.filter { it.id !in owned && it.id !in placedStops }.maxByOrNull { it.placedAt }
    }

    /**
     * 18 Nov 2026 (audit A4): the stops this run placed (memory, bounded): never taken for an orphan ([orphanStop]) - a sibling's stop
     * placed a moment ago, its id not written yet. After a restart it is empty, which is when an orphan can be.
     */
    private val placedStops: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
        // never rested again for lots an exit sold a moment ago (it would open a short when it triggered). 18 Nov 2026 (audit A2): with
        // the positions-lag guard acting whatever its switch says ([ExitCoordinator.held]'s forceLag: it only ever lowers what is held).
        val heldNow = if (covered == null) null else runCatching {
            ExitCoordinator.held(p.exchange, p.symbol, p.product, "arm ${p.label} stop", book, forceLag = true) * p.side
        }.getOrNull()
        if (covered == null || heldNow == null) { stopFailed(p, trigger, "Zerodha's books could not be read"); return p.copy(stopTrigger = trigger) }
        // Seen held is the look's to say, oldest first (review: the contract's total includes the other trades on it). A new stop
        // order has nothing of its fill booked yet.
        val seen = p.copy(stopBooked = 0)
        // 18 Nov 2026 (audit A4): this path's own stop resting on the contract that no open trade owns (placed just before a restart,
        // its id never written) is this trade's: taken as its stop, never counted as another order beside a second one.
        if (p.stopOrderId == null && spec.rest != LiveMirror.Rest.GTT) orphanStop(p, book)?.let { o ->
            runCatching { Diag.record("armlive", "${p.label}: its own stop ${o.id} on ${p.symbol} found resting with no trade owning it: taken as its stop") }
            runCatching { Strategies.tagOwner("kite:${o.id}", "${spec.label} · live ${stopWords(p)}") }
            return seen.copy(stopOrderId = o.id, stopTrigger = o.trigger.takeIf { it > 0 } ?: trigger, stopQty = ExitQty.remaining(o.qty, o.filled, o.pending))
        }
        val room = ((heldNow - covered).coerceAtLeast(0) / p.lot.coerceAtLeast(1)) * p.lot.coerceAtLeast(1)
        val q = minOf(p.qty, room)
        // 18 Nov 2026 (audit A1: Boss's own LIMIT sell in Kite left the arm's stop no room, unsaid): when an order of no part of this
        // path takes the room the stop needs, Boss is told loudly, once for the trade; the app watches the level itself and sells
        // there ([exitLive] cancels such a LIMIT first when the exit is due).
        val foreign = book.filter { it.working && it.symbol == p.symbol && it.side == exitSide.name && it.product == p.product && it.tag != LiveMirror.TAG }
        if (q < minOf(p.qty, heldNow.coerceAtLeast(0) / p.lot.coerceAtLeast(1) * p.lot.coerceAtLeast(1)) && foreign.isNotEmpty())
            sayBlocked(p, "covered", again = false, "${p.label}: its live ${stopWords(p)} on ${p.symbol} cannot rest at Zerodha for " +
                "${p.qty - q.coerceAtLeast(0)} because another sell order covers the lot (${foreign.joinToString { "${it.type} ${it.id}" }}). " +
                "The app watches %.2f itself; when the exit is due it cancels a LIMIT of yours first and sells.".format(Locale.ENGLISH, trigger))
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
        if (placedStops.size > 500) placedStops.clear()
        placedStops += id
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
        if (handsOffNow(p.exchange, p.symbol)) return p              // Boss is exiting it by hand ([handsOff])
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
        if (handsOffNow(p.exchange, p.symbol)) return null           // Boss is exiting it by hand ([handsOff])
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
    private suspend fun exitLive(p0: Pos, qty: Int, why: String, ltp: Double?, g: Broker.GttRow? = null): List<Pos> {
        runCatching { OrderTiming.decided() }
        val exitSide = if (p0.side > 0) Kite.Side.SELL else Kite.Side.BUY
        // 18 Nov 2026 (audit A2): never a second exit on a contract while one whose reply was lost may still be selling it.
        if (lostExits.containsKey(p0.contract)) return listOf(p0)
        val want = minOf(qty, p0.qty)
        if (want <= 0) return listOf(p0)
        // 1. The books and the gates first: nothing is touched when the exit could not go now.
        var orders0 = runCatching { Broker.orders() }.getOrNull() ?: return listOf(p0)
        // 18 Nov 2026 (audit A4): this path's own stop resting with no trade owning it (placed just before a restart) is this trade's:
        // taken out first like its own stop, never counted as another order that leaves the exit no room.
        val p = if (p0.stopOrderId != null || LiveMirror.spec(p0.key)?.rest == LiveMirror.Rest.GTT) p0 else orphanStop(p0, orders0)?.let { o ->
            p0.copy(stopOrderId = o.id, stopQty = ExitQty.remaining(o.qty, o.filled, o.pending), stopTrigger = p0.stopTrigger ?: o.trigger.takeIf { it > 0 })
        } ?: p0
        val fired = p.gttId != null && g?.status?.lowercase() == "triggered"
        val stopIds = setOfNotNull(p.slId) + (if (p.gttId != null) g?.orderIds.orEmpty() else emptyList())
        fun mineOf(rows: List<Broker.OrderRow>, skip: Set<String>) = rows.filter { it.working && it.symbol == p.symbol && it.side == exitSide.name &&
            it.product == p.product && it.id !in skip }
        var held0 = runCatching { heldNow(p) }.getOrNull() ?: return listOf(p)
        var planned = ExitQty.sendable(held0.coerceAtLeast(0), mineOf(orders0, stopIds).sumOf { ExitQty.remaining(it.qty, it.filled, it.pending) }, want, p.lot)
        // 18 Nov 2026 (audit A1: Boss's own LIMIT sell in Kite on the contract left the arm's due exit no room, so its stop, step lock,
        // paper exit, Stop for today and square-offs never sold, unsaid): when other working exits hold this due exit back, a plain LIMIT
        // of no part of the app (his target) is cancelled first, confirmed out, in the contract's exit turn, and the exit goes on fresh
        // reads (as [SquareOffExit] does for the square-offs); anything else that holds it back is said to Boss loudly, each minute.
        val most = ExitQty.sendable(held0.coerceAtLeast(0), 0, want, p.lot)
        // (Never cancelled for a sale the order gates would refuse anyway: that is said below, his order left as it is.)
        if (planned < most && Kite.refusals(Kite.Order(p.symbol, exitSide, most, p.lot, p.product, "MARKET", null, p.tick, p.exchange, LiveMirror.TAG,
                multiplier = p.multiplier), AppSettings.load().limits(), Broker.sentToday(), false, exit = true).isEmpty()) {
            when (freeBlocked(p, want, why, mineOf(orders0, stopIds), held0)) {
                Freed.NONE -> Unit
                Freed.WAIT -> return listOf(p)
                Freed.CANCELLED -> {
                    orders0 = runCatching { Broker.orders() }.getOrNull() ?: return listOf(p)
                    held0 = runCatching { heldNow(p) }.getOrNull() ?: return listOf(p)
                    planned = ExitQty.sendable(held0.coerceAtLeast(0), mineOf(orders0, stopIds).sumOf { ExitQty.remaining(it.qty, it.filled, it.pending) }, want, p.lot)
                }
            }
        }
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
                    val (done, rest) = closePart(cur, sold, px, stopWhyOf(p), exitSide.name)
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
            // 18 Nov 2026 (audit A2): the guard acts for these sales whatever its switch says (forceLag: it only ever sends less).
            val held = runCatching { ExitCoordinator.held(cur.exchange, cur.symbol, cur.product, "arm ${p.label}", book, forceLag = true) * cur.side }.getOrNull()
                ?: return closed + back(cur)
            heldSeen = held
            val sendable = ExitQty.sendable(held.coerceAtLeast(0), working.sumOf { ExitQty.remaining(it.qty, it.filled, it.pending) }, minOf(toSell, cur.qty), p.lot)
            if (sendable <= 0) return closed + back(cur)
            val o = Kite.Order(p.symbol, exitSide, sendable, p.lot, p.product, "MARKET", null, p.tick, p.exchange, LiveMirror.TAG, multiplier = p.multiplier)
            try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
                // 10 Nov (the 3 Nov refusal, for an exit): Zerodha blocks MARKET orders on a contract with little open interest, so
                // the same exit goes once as a LIMIT at the bid (the offer for a buy back), never more than 1% through the last price
                // ([LiveMirror.blockedMarketExit]); unfilled in 15 s it is cancelled below and the stop goes back, as for any exit.
                // Its answer lost and no order found: it may still have gone, so nothing is put back this look (as for a lost reply).
                val limit = if (Broker.definite(e) && LiveMirror.marketBlocked(e.message)) {
                    val key = "${p.exchange}:${p.symbol}"
                    val q = runCatching { Broker.quotes(listOf(key))[key] }.getOrNull()
                    LiveMirror.blockedMarketExit(o, q?.last?.takeIf { it > 0 } ?: ltp, q?.ask, q?.bid)
                } else null
                if (limit != null) {
                    try { Broker.placeOrder(limit, exit = true) } catch (e2: Exception) {
                        val seen = known().toSet() + working.map { it.id }
                        val again = if (Broker.definite(e2)) null else runCatching { Broker.findRecentRetrying(limit, seen) }.getOrNull()
                        again ?: run {
                            com.optionslab.app.work.Alerts.post("${p.label}: the live exit failed (MARKET blocked, then ${e2.message?.take(120)}); retrying on the next look.",
                                com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
                            if (!Broker.definite(e2)) lostExit(p, why, limit, seen)
                            return closed + (if (Broker.definite(e2)) back(cur) else cur)
                        }
                    }
                } else {
                    val seen = known().toSet() + working.map { it.id }
                    val found = if (Broker.definite(e)) null else runCatching { Broker.findRecentRetrying(o, seen) }.getOrNull()
                    found ?: run {
                        com.optionslab.app.work.Alerts.post("${p.label}: the live exit failed (${e.message?.take(160)}); retrying on the next look.",
                            com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
                        // Refused outright: the stop goes back now. A lost reply with no order found may still have gone: next look.
                        // 18 Nov 2026 (audit A2): and nothing more is sold or rested on the contract until the book shows it or the hold ends.
                        if (!Broker.definite(e)) lostExit(p, why, o, seen)
                        return closed + (if (Broker.definite(e)) back(cur) else cur)
                    }
                }
            }
        }
        return bookExit(p, cur, closed, id, why, ltp) { x -> back(x) }
    }

    /**
     * The exit order [id] of [p] ([why]) followed to its end and booked on [cur] after the parts already [closed]; [back] puts the stop
     * back for what is still open. 18 Nov 2026 (audit A2): one way for a sale just sent and for a lost exit found later in the book;
     * after a fill Zerodha's net on the contract is checked ([checkNet]: never the other way of the trade).
     */
    private suspend fun bookExit(p: Pos, cur: Pos, closed: List<Pos>, id: String, why: String, ltp: Double?, back: suspend (Pos) -> Pos): List<Pos> {
        val exitSide = if (p.side > 0) Kite.Side.SELL else Kite.Side.BUY
        runCatching { Strategies.tagOwner("kite:$id", "${p.label} · live $why") }
        var f = runCatching { Broker.awaitOrder(id, 15_000) }.getOrNull()
        if (f?.status !in DONE) { runCatching { Broker.cancel(id) }; f = runCatching { Broker.orderState(id) }.getOrNull() ?: f }
        if (f == null || f.filled <= 0) return closed + (if (f != null && f.status in DONE) back(cur) else cur)
        // Final review F6: an average Zerodha did not give is read from its trade book, else the price now, else the price paid.
        val px = f.avgPrice.takeIf { it > 0 } ?: orderPrice(id) ?: ltp?.takeIf { it > 0 } ?: p.entry
        runCatching { Notifier.orderFilled(app, exitSide.name, f.filled, p.symbol, px, "Live", "${p.label} · live $why", f.orderId) }
        val (done, rest) = closePart(cur, minOf(f.filled, cur.qty), px, why, exitSide.name)
        // 18 Nov 2026 (audit A2): Zerodha's net on the contract after the sale: the other way of the trade is said loudly.
        runCatching { Broker.positionBook().net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty } }
            .getOrNull()?.let { checkNet(p.label, p.exchange, p.symbol, p.product, p.side, it, now().toLocalDate()) }
        // The rest still open keeps a stop for all of it: rested again when out, grown back when a part sale only part filled.
        return closed + done + listOfNotNull(rest?.let { back(it) })
    }

    /** 18 Nov 2026 (audit A2): [o], sent to exit [p], lost its reply and was not found: held on its contract ([lostExits]), Boss told. */
    private fun lostExit(p: Pos, why: String, o: Kite.Order, known: Set<String>) {
        val ms = runCatching { nowMs() }.getOrDefault(System.currentTimeMillis())
        lostExits[p.contract] = LostExit(p.ref, why, o, known, ms)
        com.optionslab.app.work.Alerts.error("${p.label}: the live exit of ${p.symbol} got no reply from Zerodha and is not in its order book yet. " +
            "Nothing more is sold on it for ${LOST_EXIT_HOLD_MS / 1000} s while the app looks for it; check Kite's order book.", WHO)
    }

    /**
     * 18 Nov 2026 (audit A2): a lost exit on [contract] looked for in Zerodha's order book (at most each [LOST_EXIT_ASK_MS]), for its
     * trade [owner] as the look has it now (null: not open). Null: none, or it was not found within the hold (the exit may go again);
     * else what the trade became: as it is while the hold runs, or booked from the order found (working or filled: it is the exit).
     */
    private suspend fun lostExitLook(contract: String, owner: Pos?, ltp: Double?): List<Pos>? {
        val lost = lostExits[contract] ?: return null
        val ms = nowMs()
        val held = ms - lost.sinceMs < LOST_EXIT_HOLD_MS
        val keep = listOfNotNull(owner)
        // (The live lane comes every tick: the book is searched at most every few seconds. A book that cannot be read keeps the hold.)
        if (ms - lost.askedMs in 0 until LOST_EXIT_ASK_MS) return keep
        lostExits[contract] = lost.copy(askedMs = ms)
        val found = try { Broker.findRecent(lost.order, lost.known) } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { return keep }
        if (found == null) {
            if (held) return keep
            lostExits.remove(contract)
            runCatching { Diag.record("armlive", "${lost.order.tradingSymbol}: the lost exit was not found in ${LOST_EXIT_HOLD_MS / 1000} s; the exit may go again") }
            return null
        }
        lostExits.remove(contract)
        runCatching { Diag.record("armlive", "${lost.order.tradingSymbol}: the lost exit found in Zerodha's book ($found): it is the exit") }
        val p = owner?.takeIf { it.ref == lost.ref } ?: return keep
        return bookExit(p, p, emptyList(), found, lost.why, ltp) { x -> if (x.stopOrderId == null && ltp != null) restStop(x, x.stopTrigger, ltp) else x }
    }

    /** 18 Nov 2026 (audit A1): what [freeBlocked] did. */
    private enum class Freed { NONE, CANCELLED, WAIT }

    /**
     * 18 Nov 2026 (audit A1): [p]'s due exit of [want] ([why]) held back by the other exits working on its contract ([blockers]) with
     * [held] held. A plain LIMIT of no part of the app (Boss's own target in Kite) that holds it back is cancelled in the contract's
     * exit turn and confirmed out ([LiveMirror.blockersToCancel]; [Freed.CANCELLED]: the caller reads the books again). One that filled
     * meanwhile, or a cancel not confirmed: [Freed.WAIT] (the next look reads the books again). Nothing that may be cancelled: said to
     * Boss loudly, each minute while the exit is due ([Freed.NONE]).
     */
    private suspend fun freeBlocked(p: Pos, want: Int, why: String, blockers: List<Broker.OrderRow>, held: Int): Freed {
        val mine = known().toSet()
        fun words(rows: List<Broker.OrderRow>) = rows.joinToString { "${it.type} ${it.side.lowercase()} ${ExitQty.remaining(it.qty, it.filled, it.pending)}" +
            (if (it.type == "LIMIT") " @ %.2f".format(Locale.ENGLISH, it.price) else "") + " (${it.tag.ifBlank { "yours" }}, ${it.id})" }
        val ids = LiveMirror.blockersToCancel(held, blockers.sortedByDescending { it.placedAt }.map { o ->
            LiveMirror.Blocker(o.id, ExitQty.remaining(o.qty, o.filled, o.pending), cancellable = cancellable(o, mine)) }, want, p.lot)
        if (ids.isEmpty()) {
            val others = blockers.filter { it.tag != LiveMirror.TAG }
            if (others.isNotEmpty()) sayBlocked(p, "exit", again = true, "${p.label}: its live exit ($why) on ${p.symbol} is due but cannot sell: " +
                "other orders at Zerodha already cover the lot (${words(others)}). Cancel them in Kite or close ${p.symbol} yourself.")
            return Freed.NONE
        }
        return ExitCoordinator.turn(p.exchange, p.symbol, "arm ${p.label}") { cancelBlockers(p, ids, why, mine) }
    }

    /** 18 Nov 2026 (audit A1): an exit order the arm may cancel when its own exit is due: a plain LIMIT of no part of the app. */
    private fun cancellable(o: Broker.OrderRow, mine: Set<String>): Boolean =
        o.working && o.type == "LIMIT" && !o.tag.startsWith("ira", ignoreCase = true) && o.tag !in SquareOffExit.TAGS && o.id !in mine

    /** 18 Nov 2026 (audit A1): [freeBlocked]'s cancels, in the contract's exit turn, each confirmed out on a fresh read. */
    private suspend fun cancelBlockers(p: Pos, ids: List<String>, why: String, mine: Set<String>): Freed {
        val rows = runCatching { ExitCoordinator.orders(p.exchange, p.symbol, "arm ${p.label}") }.getOrNull() ?: return Freed.WAIT
        val done = ArrayList<Broker.OrderRow>()
        for (id in ids) {
            val o = rows.firstOrNull { it.id == id } ?: continue
            if (!o.working) { if (o.filled > 0) return Freed.WAIT else continue }            // filled or gone since: the books again
            if (!cancellable(o, mine)) return Freed.WAIT
            runCatching { Broker.cancel(o.id, o.variety) }
            val st = runCatching { Broker.orderState(o.id) }.getOrNull()
            if (st == null || st.status !in DONE) {
                sayBlocked(p, "cancel", again = true, "${p.label}: its live exit ($why) on ${p.symbol} is due; your sell order ${o.id} could not be " +
                    "cancelled yet, so nothing is sold beside it. Cancel it in Kite or close ${p.symbol} yourself.")
                return Freed.WAIT
            }
            done += o
            if (st.status == "COMPLETE" || st.filled > o.filled) return Freed.WAIT      // it sold meanwhile: the next look reads it
        }
        if (done.isNotEmpty()) {
            com.optionslab.app.work.Alerts.error("${p.label}: its live exit ($why) on ${p.symbol} was due, and your sell order" +
                (if (done.size > 1) "s " else " ") + done.joinToString { "${it.qty} @ %.2f (${it.id})".format(Locale.ENGLISH, it.price) } +
                " covered the lot, so ${if (done.size > 1) "they were" else "it was"} cancelled and the arm sells now.", WHO)
            runCatching { Diag.record("armlive", "${p.label}: cancelled ${done.joinToString { it.id }} on ${p.symbol} so its due exit ($why) could sell") }
        }
        return Freed.CANCELLED
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
        // 18 Nov 2026 (audit): a write that failed is tried again (the book in memory is the one to keep).
        if (saveDue && !stream) runCatching { lock.withLock { cache?.let { save(it) } } }
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

    /**
     * What the paper trade [p] follows says now: whether it is gone, and how much of the live trade to sell for it.
     * 2 Nov (live): [fresh] false = [legs] is the copy kept from the last regular look (a stream look). A trade missing from that copy
     * may simply be newer than it: R51's MIDCPNIFTY entry at 11:30:03 was sold 16 s later as "paper_exit" while its paper trade was
     * open. So only a fresh read of the paper book may call a missing trade gone; a stream look still acts on a leg it does see.
     */
    // 15 Nov (R51 / R72 with the breakeven rule): the paper trade's own rule exit (on the PAPER fill and best price) still sells its live
    // copy, as every paper exit does. Kept on purpose: the rule is per lot, so the live trade's own levels are the same distance from its
    // own fill and come at about the same price - and a live trade that stopped following its paper trade would lose the arm's other
    // exits (its time stop) and could be held to the square-off. An earlier exit only.
    private fun paperSide(p: Pos, legs: List<PaperLeg>?, fresh: Boolean = true): Triple<Pos, Int, String?> {
        val leg = legs?.firstOrNull { it.ref == p.ref }
        var q = p
        if (leg != null && leg.qty > 0 && !q.seen) q = q.copy(seen = true)
        val gone = closedRefs.containsKey(p.ref) || (fresh && LiveMirror.paperGone(q.seen, legs?.let { leg != null })) || leg?.qty == 0
        val sell = if (gone) q.qty else leg?.let { LiveMirror.toSell(q.qty, q.entryQty, q.paperQty, it.qty, q.lot) } ?: 0
        val why = closedWhy(p.ref) ?: if (gone) "paper_exit" else if (sell > 0) "paper_part" else null
        return Triple(q, sell, why)
    }

    /** 30 Oct: the trades whose first stop an entry is resting right now ([enterNow]): the looks leave them alone meanwhile. */
    private val resting: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 30 Oct (deep review): when each lost-reply entry was last searched for at Zerodha (ref -> ms): at most every [SETTLE_EVERY_MS]. */
    private val settleAsked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val SETTLE_EVERY_MS = 10_000L
    /** 30 Oct (review): a lost-reply entry is dropped only after the grace AND this many searches that did not find it. */
    private const val SETTLE_TRIES = 3
    private val settleTries = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 18 Nov 2026: by contract ([Pos.contract]), when (market-clock ms) Zerodha's positions first showed less than these trades hold, with
     * no sale seen ([LiveMirror.shortConfirmed]); dropped when a read shows it held again or the close is booked. Memory only (a
     * restart starts the count again: later, never sooner); bounded.
     */
    private val shortSince = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 18 Nov 2026 (review F3): the contracts whose short reads with no sale Boss was told of (once while they stay short). Memory only. */
    private val shortSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** 18 Nov 2026: the contracts a positions read listed no row for, already said (once until a row comes back). Memory only. */
    private val noRowSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** 18 Nov 2026 (audit A3): the ORB arms' and Liquidity's order tag ([OrbArms]): their entries are that book's, never Boss's. */
    private const val ORB_TAG = "iraorb"

    /**
     * 18 Nov 2026 (audit A1): when Boss was last told that another order on the contract holds back a trade's stop or exit ("ref|cause"
     * -> market-clock ms). Said once per trade and cause; an exit that is due and held back is said again each [BLOCK_AGAIN_MS]. Memory.
     */
    private val blockSaid = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val BLOCK_AGAIN_MS = 60_000L

    /** 18 Nov 2026 (audit A1): [text] said to Boss loudly for [p] and [cause]: once, or with [again] once each [BLOCK_AGAIN_MS]. */
    private fun sayBlocked(p: Pos, cause: String, again: Boolean, text: String) {
        val key = "${p.ref}|$cause"
        val ms = runCatching { nowMs() }.getOrDefault(System.currentTimeMillis())
        val last = blockSaid[key]
        if (last != null && (!again || ms - last in 0 until BLOCK_AGAIN_MS)) return
        if (blockSaid.size > 500) blockSaid.clear()
        blockSaid[key] = ms
        com.optionslab.app.work.Alerts.error(text, WHO)
        note(p.key, text)
    }

    /**
     * 18 Nov 2026 (audit A2): an exit whose reply was lost and whose order was not found ([exitLive]), by contract: nothing more is sold
     * or rested on that contract until the order book shows it (it is then the exit) or [LOST_EXIT_HOLD_MS] have passed with it not
     * found. [order] what was sent for trade [ref]; [known] the order ids known before it went; [sinceMs] when (market clock); [askedMs]
     * when the book was last searched for it; [why] the exit's reason. Memory only (a restart reads the books afresh).
     */
    private data class LostExit(val ref: String, val why: String, val order: Kite.Order, val known: Set<String>, val sinceMs: Long, val askedMs: Long = 0L)
    private val lostExits = java.util.concurrent.ConcurrentHashMap<String, LostExit>()
    private const val LOST_EXIT_HOLD_MS = 25_000L
    /** 18 Nov 2026 (audit A2): a lost exit is searched for at most this often (the live lane comes every tick). */
    private const val LOST_EXIT_ASK_MS = 3_000L

    /** 18 Nov 2026 (audit A2): whether a lost exit on [contract] still holds every sale and stop on it back (memory only). */
    private fun lostExitHolds(contract: String): Boolean = lostExits[contract]?.let { nowMs() - it.sinceMs < LOST_EXIT_HOLD_MS } == true

    /**
     * 18 Nov 2026 (audit A2): the contracts on which Boss was told Zerodha holds the other way of the arms' trades ("contract|day"):
     * once until it is no longer so. Memory only.
     */
    private val negSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 18 Nov 2026 (audit A2): Zerodha's net quantity [net] (signed) on a contract an arm traded today the [side] way: the other way of it
     * (a long's net below 0) is said loudly - two exits of one lot filled, a naked short. Once a day while it lasts.
     */
    private fun checkNet(label: String, exchange: String, symbol: String, product: String, side: Int, net: Int, day: LocalDate) {
        val key = "$exchange|$symbol|$product|$day"
        if (net * side >= 0) { negSaid.remove(key); return }
        if (negSaid.size > 200) negSaid.clear()
        if (!negSaid.add(key)) return
        com.optionslab.app.work.Alerts.error("$label: Zerodha shows $symbol at $net ($product), the other way of the arms' live trade - " +
            "more was sold than held. Close it in Kite now.", WHO)
        runCatching { Diag.record("armlive", "$label: $symbol net $net ($product) against the arms' side $side: Boss told") }
    }

    /**
     * Whether a lost-reply entry ([Pos.unconfirmed] with no order id) is searched for at Zerodha on this look: never on a stream look
     * (every tick: it would spend the order book's rate the exits need), never while another entry is in flight (its order could be
     * taken for this one's), and at most every [SETTLE_EVERY_MS].
     */
    private fun settleNow(p: Pos, stream: Boolean): Boolean {
        if (p.entryOrderId != null) return true
        if (stream || entryLock.isLocked) return false
        val nowMs = System.currentTimeMillis()
        val last = settleAsked[p.ref]
        if (last != null && nowMs - last < SETTLE_EVERY_MS) return false
        settleAsked[p.ref] = nowMs
        if (settleAsked.size > 200) settleAsked.clear()
        return true
    }

    private suspend fun look(stream: Boolean) {
        val open = lock.withLock { book().positions.filter { it.open && !it.shadow && it.ref !in resting && !handsOffNow(it.exchange, it.symbol) }.toList() }
        // (18 Nov 2026, review: a look that returns early never leaves a short count behind for a later trade on the contract.)
        if (open.isEmpty()) { shortSince.clear(); shortSaid.clear(); return }
        if (shortSince.size > 200) shortSince.clear()
        if (noRowSaid.size > 200) noRowSaid.clear()
        if (shortSaid.size > 200) shortSaid.clear()
        if (!Broker.loggedIn) { shortSince.clear(); shortSaid.clear(); waitingForLogin(open, stream); return }
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
        // 30 Oct: an MCX position sells on the commodity side's stop (and the index LOSS stop, as before), never on Boss's index stop.
        val mcxStopped = if (open.none { it.exchange == "MCX" }) false else runCatching { sideSells(mcx = true) }.getOrDefault(false)
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
        // 18 Nov 2026: contracts whose positions read this look is short of what these trades hold (or lists no row for them) and is
        // not confirmed yet (step 2): their trades are decided on below as still held - never shrunk or taken out on that read.
        val unsure = HashSet<String>()
        // (And the contracts shown short this look, confirmed or not: only their counts are kept.)
        val shortNow = HashSet<String>()
        suspend fun reread() {
            runCatching { Broker.orders() }.getOrNull()?.let { orders = it.associateBy { o -> o.id } }
            runCatching { Broker.positionBook().net }.getOrNull()?.let { net = it }
            // GTTs too (review: one deleted earlier in this look must not still count against a sibling's stop).
            if (gtts.orEmpty().isNotEmpty() || open.any { it.gttId != null })
                runCatching { Broker.gtts().associateBy { it.id } }.getOrNull()?.let { gtts = it; gttsKept = System.currentTimeMillis() to it; gttsFresh = true }
            dirty.clear()
        }

        // 1. Each trade's own news: an unconfirmed entry settled; its stop filled, refused or gone.
        // (18 Nov 2026: what of Zerodha's positions this look's settled entries already took back, by contract - never twice.)
        val claimed = HashMap<String, Int>()
        for (p0 in open) {
            try {
                if (p0.unconfirmed) { if (settleNow(p0, stream)) set(p0, listOfNotNull(settle(p0, claimed))); continue }
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
                    val (done, rest) = closePart(p, sold, px, stopWhyOf(p), side)
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
                            val (done, rest) = closePart(p, sold, px, stopWhyOf(p), side)
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
            // 18 Nov 2026 (Boss, live: R51's FINNIFTY lot was booked "closed_at_zerodha" on one positions read while Zerodha still
            // held it - its stop taken out, its step lock never sold): a read with no row at all for a contract these trades hold is
            // a bad read (Zerodha lists every contract traded today, closed ones at 0) - it says nothing, never "zero held". (Only for
            // trades entered today: one from an earlier day that Zerodha squared off or that expired has no row any more.)
            val first = live.first().second
            if (live.any { !it.second.day.isBefore(t.toLocalDate()) } && net.none { it.symbol == first.symbol && it.exchange == first.exchange }) {
                unsure += contract
                if (noRowSaid.add(contract)) note(first.key, "Zerodha's positions read did not list ${first.symbol}: not taken as closed; " +
                    "its stop stays and it is watched")
                continue
            }
            noRowSaid.remove(contract)
            val held = heldOf(first)
            // 18 Nov 2026 (audit A2): Zerodha holding the other way of these trades (two exits of one lot filled) is said loudly.
            checkNet(first.label, first.exchange, first.symbol, first.product, first.side, held * first.side, t.toLocalDate())
            val counted = live.filter { (_, p) -> p.heldSeen || LiveMirror.zeroLongEnough(p.zeroSinceMs, ms) }.map { it.first }.toSet()
            var cum = 0
            for ((p0, p) in live) {
                cum += p.qty
                val seen = if (held >= cum) p.copy(heldSeen = true, zeroSinceMs = null)
                    else if (!p.heldSeen && p.zeroSinceMs == null) p.copy(zeroSinceMs = ms) else p
                if (seen != p) set(p0, listOf(seen))
            }
            var excess = live.filter { it.first in counted }.sumOf { (cur(it.first) ?: it.second).qty } - held.coerceAtLeast(0)
            if (excess <= 0) { shortSince.remove(contract); shortSaid.remove(contract); continue }
            // 18 Nov 2026 (the same incident): one read short of what is held never closes a trade or takes its stop out. It is booked
            // closed only once confirmed ([LiveMirror.shortConfirmed]): exit-side fills on the contract in Zerodha's order book (Boss's own
            // sale in Kite, Zerodha's square-off, the app's square-off - never another of the app's own exits, which explains nothing here),
            // or the reads still short long enough after the first. Until then the trades are kept, their stops rest and the look below
            // still moves and sells them as held.
            // 18 Nov 2026 (review F1): the sales are netted over the whole day, never taken one by one: today's exit-side fills outside
            // the app (Boss's own in Kite - no tag, no order of the app's or of a fired GTT's; the app's square-off counted as his) less his
            // own entry-side fills today and what was carried from an earlier day (his buy and sale of the contract, before or after this
            // entry, are a round trip, not this trade's close) less what either live book (this one, and the ORB arms and Liquidity's)
            // already booked closed from outside today (the sale of a lot already booked proves nothing more) must cover what is missing
            // ([LiveMirror.outsideSaleCovers]). 18 Nov 2026 (review F3): with no exit-side fill at all left unbooked on the contract
            // today (the app's own included) and no other product's row holding it (a conversion in Kite), the reads must agree for five
            // minutes, not 30 s, and Boss is told once at 30 s. (Memory and the look's own reads only: the order labels are read only when
            // an untagged order is on the contract - Boss's own activity - never on every tick of a quiet one.)
            val today = t.toLocalDate()
            val booked = _view.value.takeIf { it.loaded }?.closed?.filter { it.contract == contract && it.exitTime?.toLocalDate() == today }
                ?.map { c -> LiveMirror.Booked(c.exchange, c.symbol, c.product, c.exitTime!!, c.qty, c.why == "closed_at_zerodha") }
                ?.plus(OrbArms.liveClosedHint.filter { it.exchange == first.exchange && it.symbol == first.symbol && it.product == first.product &&
                    it.at.toLocalDate() == today })
            val exitSide = if (first.side > 0) "SELL" else "BUY"
            val onIt = orders.values.filter { it.symbol == first.symbol && it.exchange == first.exchange && it.product == first.product && it.filled > 0 }
            val mine: Set<String> = if (onIt.none { it.tag.isBlank() }) emptySet() else known().toSet() + gtts.orEmpty().values.flatMap { it.orderIds }
            fun outside(o: Broker.OrderRow) = (o.tag.isBlank() && o.id !in mine) || o.tag in SquareOffExit.TAGS
            // (What was carried from an earlier day by others than these trades: Boss's own lot sold today proves nothing about them.)
            val carried = net.filter { it.symbol == first.symbol && it.exchange == first.exchange && it.product == first.product }
                .sumOf { it.overnight } * first.side - live.filter { it.second.day.isBefore(today) }.sumOf { it.second.qty }
            val outsideSells = onIt.filter { it.side == exitSide && outside(it) }.sumOf { it.filled }
            // 18 Nov 2026 (audit A3: a lot bought through the app's Trade screen ("iraalgo"), Pine or a strategy and sold in Kite made one
            // short read "confirmed"): every entry-side fill on the contract that is no entry of the two live books' trades counts, whatever
            // its tag - only this path's own entries ([LiveMirror.TAG], and any trade of this book's entry order) and the ORB arms' and
            // Liquidity's ([ORB_TAG]) are theirs.
            val ownEntries = (live.mapNotNull { it.second.entryOrderId } + _view.value.closed.mapNotNull { it.entryOrderId }).toSet()
            val outsideBuys = onIt.filter { it.side != exitSide && it.tag != LiveMirror.TAG && it.tag != ORB_TAG && it.id !in ownEntries }
                .sumOf { it.filled } + carried.coerceAtLeast(0)
            val converted = net.filter { it.symbol == first.symbol && it.exchange == first.exchange && it.product != first.product }
                .sumOf { it.qty } * first.side - open.filter { it.symbol == first.symbol && it.exchange == first.exchange && it.product != first.product &&
                it.side == first.side }.sumOf { it.qty } >= excess        // (beyond what these arms hold there themselves)
            val sold = booked != null && LiveMirror.outsideSaleCovers(outsideSells, outsideBuys, booked.filter { it.outside }.sumOf { it.qty }, excess)
            val unbooked = booked != null && (converted ||
                LiveMirror.unbookedSale(onIt.filter { it.side == exitSide }.sumOf { it.filled }, outsideBuys, booked.sumOf { it.qty }))
            // (18 Nov 2026, review: a count older than the oldest such trade's entry is another trade's: it starts again.)
            val oldestMs = live.filter { it.first in counted }.minOf { it.second.entryTime }.atZone((testNow ?: Market.now()).zone).toInstant().toEpochMilli()
            if ((shortSince[contract] ?: Long.MAX_VALUE) < oldestMs) shortSince.remove(contract)
            val firstShort = shortSince.putIfAbsent(contract, ms)
            shortNow += contract
            if (booked == null || !LiveMirror.shortConfirmed(rowShown = true, saleSeen = sold, firstShortMs = firstShort ?: ms, nowMs = ms, unbookedSale = unbooked)) {
                unsure += contract
                if (firstShort == null) note(first.key, "Zerodha's positions show $held of ${first.symbol} where these trades hold ${held + excess}, " +
                    "with no sale outside the app in its order book to explain it: not booked closed until it is confirmed; the stop stays and it is watched")
                if (LiveMirror.shortAlertDue(rowShown = true, saleSeen = sold, firstShortMs = firstShort, nowMs = ms, unbookedSale = unbooked) && shortSaid.add(contract))
                    com.optionslab.app.work.Alerts.error("${first.label}: Zerodha's positions have shown $held of ${first.symbol} for 30 s where these trades " +
                        "hold ${held + excess}, with no sale in its order book. Kept as held, its stop resting at Zerodha; booked closed only if this lasts " +
                        "5 minutes. Check it in Kite.", WHO)
                continue
            }
            shortSince.remove(contract); shortSaid.remove(contract)
            // Final review F6: what was closed outside is booked at what Zerodha's trade book (else its order book) says it sold
            // for; only with neither, at the price now (else the price paid), as before.
            // Each closed part takes the next of those fills (newest first), never the same fill twice (review of the final fixes).
            var fills: List<Pair<Int, Double>>? = null
            var used = 0
            suspend fun outsideAt(p: Pos, q: Int): Double? {
                val rows = fills ?: outsideFills(p, if (mine.isEmpty()) known().toSet() else mine, orders.values).also { fills = it }
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
                    set(p0, listOf(closePart(p, p.qty, px, if (filled) stopWhyOf(p) else "closed_at_zerodha", side).first))
                } else {
                    val (done, rest) = closePart(p, take, outsideAt(p, take) ?: ltpOf(p) ?: p.entry, "closed_at_zerodha", if (p.side > 0) "SELL" else "BUY")
                    set(p0, listOfNotNull(done, rest))
                }
                excess -= take
            }
        }

        // 18 Nov 2026 (review): a count is kept only while its contract is still short: one whose trades closed another way (a stop
        // fill, the app's own exit) never starts a later trade's count early. (A read with no row for it neither counts nor ends the
        // count: [unsure].)
        shortSince.keys.retainAll(shortNow + unsure)
        shortSaid.retainAll(shortNow + unsure)

        // 3. Each trade's decision, newest first on each contract, against the exits working on it (kept up to date as they change).
        val working = HashMap<String, Int>()     // order id -> remaining, the exits working on these contracts
        fun refill() { working.clear(); for (o in orders.values) if (o.working) working[o.id] = ExitQty.remaining(o.qty, o.filled, o.pending) }
        refill()
        for ((contract, group) in open.groupBy { it.contract }) {
            // 18 Nov 2026 (audit A2): an exit of this contract whose reply was lost is looked for first; while it may still be selling,
            // nothing more is sold, rested or moved on the contract (each trade watched, its stop where it rests); found, it is the exit.
            if (lostExits.containsKey(contract)) {
                val lost = lostExits[contract]
                val owner = group.firstOrNull { it.ref == lost?.ref }
                val ownerNow = owner?.let { cur(it) }?.takeIf { !it.unconfirmed }
                val r = runCatching { lostExitLook(contract, ownerNow, ownerNow?.let { ltpOf(it) }) }.getOrElse { listOfNotNull(ownerNow) }
                if (r != null) {
                    if (owner != null && ownerNow != null && r != listOf(ownerNow)) { set(owner, r); dirty += contract }
                    continue
                }
            }
            for (p0 in group.sortedByDescending { it.entryTime }) {
                val p1 = cur(p0)?.takeIf { !it.unconfirmed } ?: continue
                if (contract in dirty) { reread(); refill() }
                try {
                    val spec = LiveMirror.spec(p1.key)
                    val (pPaper, paperSell, paperWhy) = paperSide(p1, legsOf(spec, stream), fresh = !stream)
                    // 15 Nov: a stop-rule trade's best price raised to the price now (kept, so its stop never drops after a restart).
                    val p = withBest(pPaper, ltpOf(pPaper), highSince(pPaper, fresh = !stream))
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
                    // 18 Nov 2026: a short read not confirmed yet (step 2) counts as what these trades hold: their stop is moved up and
                    // they are sold as usual (each sale and new stop reads Zerodha's books again itself), never a stop shrunk or taken out.
                    // 18 Nov 2026 (review F5): a read showing only part held counts the same (the more of the read and these trades): two
                    // trades of 30 and a read of 30 never shrink or take out a sibling's stop until the short is confirmed and booked. (A
                    // sale reads Zerodha again and sells only what is held; the unsure window ends within [LiveMirror.SHORT_CONFIRM_SLOW_MS].)
                    val heldMine = if (contract in unsure) maxOf(heldOf(p), group.mapNotNull { cur(it)?.takeIf { c -> !c.unconfirmed } }.sumOf { it.qty })
                        else heldOf(p)
                    val look = LiveMirror.Look(side = p.side, qty = p.qty, held = heldMine * p.side, ltp = ltp, tick = p.tick, stopTrigger = p.stopTrigger,
                        stopResting = p.stopOrderId != null, wanted = wanted(p), paperSell = paperSell, paperReason = paperWhy,
                        squareOff = LiveMirror.squareOffDue(t, p.exchange, p.product, p.expiry, p.day), operatorStop = if (p.exchange == "MCX") mcxStopped else stopped,
                        heldSeen = true, stopQty = stopQty, otherWorking = others, lot = p.lot, stopLimit = stopLimit,
                        // 15 Nov (Boss's exception): its own stop-rule stop, sold by the app while the resting stop is lower (it could not
                        // rest there yet); [exitLive] takes that stop out first (one exit).
                        ruleStop = ruleStop(p), ruleWhy = ruleWhy(p))
                    val d = LiveMirror.decide(look)
                    val next: List<Pos>? = when (d.step) {
                        LiveMirror.Step.SELL -> {
                            // 15 Nov: a stop resting at the stop rule's levels ([stopWhyOf]) is booked as its own, run through or not.
                            val why = sellWhy(d.why, p)
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
                            // 18 Nov 2026 (audit A1): another order (Boss's own sell in Kite, a protection's) takes the room the stop
                            // needs: shrunk as before (never more resting than held), and Boss told loudly, once for the trade.
                            if (d.why == LiveMirror.SHRINK_COVERED) {
                                val foreign = orders.values.filter { it.working && it.symbol == p.symbol && it.side == exitSide && it.product == p.product &&
                                    it.id != p.slId && it.id !in gttOrders && it.tag != LiveMirror.TAG }
                                if (foreign.isNotEmpty()) sayBlocked(p, "covered", again = false, "${p.label}: its live ${stopWords(p)} on ${p.symbol} is cut to " +
                                    "${d.qty} of ${stopQty ?: p.qty} at Zerodha because another sell order covers the lot (${foreign.joinToString { "${it.type} ${it.id}" }}). " +
                                    (p.stopTrigger?.let { "The app watches %.2f itself; ".format(Locale.ENGLISH, it) } ?: "") +
                                    "when the exit is due it cancels a LIMIT of yours first and sells.")
                            }
                            val s2 = shrinkStop(p, so, d.qty, ltp)
                            dirty += contract
                            listOf(s2 ?: p)
                        }
                        else -> null
                    }
                    if (next != null) set(p0, next) else if (changed(p1, p)) set(p0, listOf(p))
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
            val sell = runCatching { paperSide(p, legsOf(LiveMirror.spec(p.key), stream), fresh = !stream).second }.getOrDefault(0)
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
            // 18 Nov 2026 (audit A4): or the same row whose stop was written meanwhile ([persistStop]).
            val i = b.positions.indexOfFirst { it == old }.takeIf { it >= 0 } ?: b.positions.indexOfFirst { it.open && !old.shadow && !it.shadow && sameButStop(it, old) }
            if (i < 0) continue
            b.positions.removeAt(i)
            b.positions.addAll(i, now)
            now.filter { !it.open }.forEach { c ->
                // 16 Nov (review): the rule's exits (breakeven lock, step lock) with their "failed gates, Boss's exception" label.
                line(b, "%s: %s%s %s %d @ %.2f (%s), %s after charges".format(Locale.ENGLISH, c.label, if (c.shadow) "SHADOW " else "",
                    if (c.side > 0) "sold" else "bought back", c.symbol, c.qty, c.exit ?: 0.0,
                    c.why?.let { com.optionslab.engine.orb.StepTrail.words(it) }, LiveMirror.rs(c.net ?: 0.0)))
                if (b.positions.none { it.open && it.ref == c.ref }) { closedRefs.remove(c.ref); levels.remove(c.ref); RuleHighs.forget("live:${c.ref}") }
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
            val (pPaper, paperSell, paperWhy) = paperSide(p0, legsOf(LiveMirror.spec(p0.key), false))
            val p = withBest(pPaper, q["${pPaper.exchange}:${pPaper.symbol}"]?.last?.takeIf { it > 0 })
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
                squareOff = LiveMirror.squareOffDue(t, p.exchange, p.product, p.expiry, p.day), operatorStop = false,
                ruleStop = ruleStop(p), ruleWhy = ruleWhy(p)))
            val d = if (atStop) LiveMirror.Decision(LiveMirror.Step.SELL, "stop", p.qty) else d0
            if (d.step == LiveMirror.Step.SELL && ltp == null && !atStop) { if (changed(p0, p)) updates[p0] = listOf(p); continue }
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
                    val (done, rest) = closePart(p, d.qty, px, if (stopExit) stopWhyOf(p) else d.why ?: "exit", if (p.side > 0) "SELL" else "BUY")
                    updates[p0] = listOfNotNull(done, rest)
                    if (rest == null) { shadowMarks.remove(p.ref); shadowSeen.remove(p.ref) }
                }
                LiveMirror.Step.PLACE_STOP -> if (d.to != p.stopTrigger || p != p0) {
                    val w = wanted(p)
                    updates[p0] = listOf(p.copy(stopTrigger = d.to, stopKind = if (w != null && d.to == w && p.stopKind != DISASTER) OWN else p.stopKind))
                    // A stop that moved is judged only on the prices after it moved.
                    if (d.to != p.stopTrigger) shadowSeen[p.ref] = realMs to ms
                }
                else -> if (changed(p0, p)) updates[p0] = listOf(p)
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
        // 15 Nov (review): the stream follows the contract and its path holds no trade since the last look - none traded (the path
        // keeps new trades only, [com.optionslab.ira.PriceNow.newTrade]); no candle read for it.
        if (KiteStream.freshTick(tok) != null) return true to null
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
            if (tk.last > 0) out[k] = Broker.stopGuard(Broker.Quote(tk.last, tk.bid, tk.ask, tk.open, tk.oi, tk.volume))   // 15 Nov: [com.optionslab.ira.PriceNow.stopLast]
        }
        val rest = keys.filter { it !in out }
        if (rest.isNotEmpty()) out.putAll(Broker.stopQuotes(rest))
        return out
    }

    /** 30 Oct (deep review): how long an entry with a lost reply and no order found is kept unconfirmed before it is dropped. */
    internal const val UNCONFIRMED_GRACE_MS = 3 * 60_000L

    /** Whether an unconfirmed entry made at [entryTime] is still within [UNCONFIRMED_GRACE_MS] at [now]. */
    internal fun unconfirmedWaits(entryTime: LocalDateTime, now: LocalDateTime): Boolean =
        java.time.Duration.between(entryTime, now).toMillis() < UNCONFIRMED_GRACE_MS

    /**
     * An entry Zerodha had not confirmed: settled from the order book (what filled, with its stop), or dropped (nothing filled).
     * 18 Nov 2026: or taken back from Zerodha's positions ([adoptHeld]) when its order cannot be found ([claimed]: what this look's
     * settled entries already took back, by contract). [inBook]: the book's own row of [p] has its entry order id (then [adoptHeld]
     * already counts it among the trades that explain a position, so it is never claimed twice).
     */
    private suspend fun settle(p: Pos, claimed: MutableMap<String, Int> = HashMap(), inBook: Boolean = p.entryOrderId != null): Pos? {
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
        val searched = p.entryOrderId == null
        val oid = p.entryOrderId ?: run {
            // 30 Oct (deep review): not found yet - kept (unconfirmed, no second entry meanwhile) for [UNCONFIRMED_GRACE_MS] from its
            // entry, then dropped with Boss told; before, one read that missed it dropped it at once.
            // 18 Nov 2026 (Boss, live: R51's FINNIFTY buy, its reply lost after "MARKET blocked, sent as a LIMIT", was dropped as "never
            // placed" three minutes on while Zerodha held it - no stop, no step lock, nothing watching it): before it is kept waiting or
            // dropped, Zerodha's positions are read - a position on the contract that no other trade here explains is this entry's
            // fill, taken back at once with its stop, its rule and lock as it entered ([adoptHeld]). Never dropped while one is held.
            val row = runCatching { Broker.latestTagged(p.symbol, side, LiveMirror.TAG, p.entryTime.minusMinutes(1), known()) }.getOrElse { return p }
                ?: run {
                    val held = adoptHeld(p, claimed) ?: return p                 // Zerodha's positions not read: kept, asked again
                    held.firstOrNull()?.let { settleTries.remove(p.ref); return it }
                    return if (unconfirmedWaits(p.entryTime, now()) || (settleTries.merge(p.ref, 1, Int::plus) ?: 1) < SETTLE_TRIES) p else null.also {
                        settleTries.remove(p.ref)
                        com.optionslab.app.work.Alerts.error("${p.label}: no live order for ${p.symbol} was found at Zerodha after its reply was lost; " +
                            "it is treated as never placed. Check Zerodha's order book.", WHO)
                    }
                }
            runCatching { Strategies.tagOwner("kite:${row.id}", "${p.label} · live entry") }
            row.id
        }
        var st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return p.copy(entryOrderId = oid)
        if (st.status !in DONE) {
            runCatching { Broker.cancel(oid) }
            st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return p.copy(entryOrderId = oid)
            if (st.status !in DONE) return p.copy(entryOrderId = oid)
        }
        if (st.filled <= 0) {
            // 18 Nov 2026: an order found only by the search (its reply lost) that filled nothing may be another try of the same entry
            // (a MARKET Zerodha refused before the LIMIT that filled): a position no other trade here explains is still taken back.
            if (!searched) return null
            // 18 Nov 2026 (review F2): the MARKET Zerodha refused and the LIMIT sent in the same second share their time, so the search can
            // find the refused one: any other of this path's entry orders on the contract since the entry that no trade here knows, still
            // working or filled, is the entry - settled from it (one still working is cancelled and confirmed out first; until then the
            // trade keeps its id and waits), never left working beside a lot taken from the positions (its later fill unmanaged).
            val again = runCatching { Broker.orders() }.getOrElse { return p }.let { rows ->
                val mine = known().toSet()
                rows.firstOrNull { it.id != oid && it.id !in mine && it.symbol == p.symbol && it.exchange == p.exchange && it.side == side &&
                    it.tag == LiveMirror.TAG && (it.working || it.filled > 0) && LiveMirror.placedSince(it.placedAt, p.entryTime.minusMinutes(3)) }
            }
            if (again != null) {
                runCatching { Strategies.tagOwner("kite:${again.id}", "${p.label} · live entry") }
                return settle(p.copy(entryOrderId = again.id), claimed, inBook = false)
            }
            val held = adoptHeld(p, claimed) ?: return p
            held.firstOrNull()?.let { settleTries.remove(p.ref); return it }
            // (Not held either: kept for the same grace and tries as an order not found - the positions can trail the fill of the try
            // that went - then dropped with Boss told, as there.)
            return if (unconfirmedWaits(p.entryTime, now()) || (settleTries.merge(p.ref, 1, Int::plus) ?: 1) < SETTLE_TRIES) p else null.also {
                settleTries.remove(p.ref)
                com.optionslab.app.work.Alerts.error("${p.label}: the live order for ${p.symbol} found at Zerodha after its reply was lost filled " +
                    "nothing (${st.status.lowercase()}) and nothing is held; it is treated as never placed. Check Zerodha's order book.", WHO)
            }
        }
        val fill = st.avgPrice.takeIf { it > 0 } ?: orderPrice(oid) ?: p.entry
        val got = p.copy(qty = st.filled, entryQty = st.filled, entry = fill, entryOrderId = oid, unconfirmed = false,
            charges = charge(side, fill, st.filled, p.exchange, p.symbol, p.multiplier))
        val (stop, kind) = spec?.let { firstStop(it, got) } ?: (null to OWN)
        // 18 Nov 2026: never taken again by a lost entry this look (review: only when the book's row does not already count it).
        if (!inBook) claimed.merge(p.contract, st.filled, Int::plus)
        return restStop(got.copy(stopKind = kind), stop, ltp = fill).also {
            // 2 Nov: confirmed late, it is a live copy now: one notice for it, as for an entry confirmed at once.
            p.paperSymbol?.let { sym -> runCatching { Notifier.liveCopyFilled(app, sym) } }
        }
    }

    /**
     * 18 Nov 2026 (review): lost entries taken back from Zerodha's positions in the last [LOST_CLAIM_MS], by contract
     * ("exchange|symbol|product"): (book, quantity, wall-clock ms). Memory only. A lost entry of the other book ([OrbArms], or these arms)
     * taken back a moment ago counts as explaining its share until that book's own view shows it, so the two books never take one
     * fill twice (two stops for one lot).
     */
    private val lostClaims = HashMap<String, MutableList<Triple<String, Int, Long>>>()
    private const val LOST_CLAIM_MS = 30_000L

    /**
     * 18 Nov 2026 (review): what lost entry of [book] may take back on the contract, at once and atomically: [compute] is given what the
     * OTHER book took back there in the last [LOST_CLAIM_MS] and returns the quantity; a quantity above 0 is claimed. Memory only, pure
     * arithmetic under a short lock (no read, no wait).
     */
    internal fun takeLost(book: String, exchange: String, symbol: String, product: String, compute: (Int) -> Int): Int = synchronized(lostClaims) {
        val now = System.currentTimeMillis()
        lostClaims.values.forEach { l -> l.removeAll { now - it.third !in 0..LOST_CLAIM_MS } }
        lostClaims.entries.removeAll { it.value.isEmpty() }
        val key = "$exchange|$symbol|$product"
        val q = compute(lostClaims[key].orEmpty().filter { it.first != book }.sumOf { it.second })
        if (q > 0) lostClaims.getOrPut(key) { ArrayList() } += Triple(book, q, now)
        q
    }

    private fun forgetLostClaims() = synchronized(lostClaims) { lostClaims.clear() }

    /**
     * 18 Nov 2026 (Boss, live: R51's FINNIFTY lot left at Zerodha unwatched): the unconfirmed entry [p] (its order not found) taken back
     * from Zerodha's positions - what it holds on [p]'s contract, product and side that no other open trade of this book explains
     * (nor one this look already took back: [claimed]), at most what [p] asked for, in whole lots. It is managed as [p]'s trade from
     * here: its stop rested at once (the arm's own level on this fill, else the safety stop), the rule and lock it entered with kept,
     * its best price from this fill. Its fill is Zerodha's average for the position when nothing else of the book is on the contract,
     * else the price at the entry. Null: Zerodha's positions could not be read (kept, asked again); empty: nothing unexplained held.
     */
    private suspend fun adoptHeld(p: Pos, claimed: MutableMap<String, Int>): List<Pos>? {
        // (18 Nov 2026, review: the orders read first, then the positions, so the exit coordinator can tell a positions book trailing a
        // sale that already filled - [ExitCoordinator.held] - and a lot just sold is never taken back with a stop on a flat position.)
        val orders = runCatching { Broker.orders() }.getOrNull() ?: return null
        val pb = runCatching { Broker.positionBook() }.getOrNull() ?: return null
        val rows = pb.net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }
        if (rows.sumOf { it.qty } * p.side <= 0) return emptyList()
        // (Never while another entry, of these arms or the ORB arms, is on its way: its fill could be taken for this one's. Asked again.)
        if (entryLock.isLocked || OrbArms.entryInFlight) return null
        val held = minOf(rows.sumOf { it.qty } * p.side,
            // 18 Nov 2026 (audit A2): the positions-lag guard acts here whatever its switch says (forceLag: it only ever lowers what is held).
            (runCatching { ExitCoordinator.held(p.exchange, p.symbol, p.product, WHO, orders, first = pb, forceLag = true) }.getOrNull() ?: return null) * p.side)
        if (held <= 0) return emptyList()
        // (One still settling from its own order id counts as explaining its share too.)
        val others = runCatching { lock.withLock { book().positions.filter { it.open && !it.shadow && (!it.unconfirmed || it.entryOrderId != null) &&
            it.ref != p.ref && it.contract == p.contract }
            .sumOf { it.qty } } }.getOrElse { return null } + (claimed[p.contract] ?: 0) +
            // (The ORB arms' and Liquidity's own live trades on the contract, from their memory: theirs, never this one's.)
            (if (p.exchange == "NFO" && p.product == "MIS" && p.side > 0) OrbArms.liveQtyHint[p.symbol] ?: 0 else 0)
        // (Never more than was bought today that way by orders of no other owner - Boss's own in Kite, another bot's - nor than is held
        // beyond what was carried from an earlier day.)
        val way = if (p.side > 0) "BUY" else "SELL"
        val today = LiveMirror.boughtTodayCap(rows.map { if (p.side > 0) it.dayBuyQty else it.daySellQty }, rows.sumOf { it.overnight } * p.side, held,
            orders.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product && it.side == way && it.tag != LiveMirror.TAG }
                .sumOf { it.filled })
        val q = takeLost("arm", p.exchange, p.symbol, p.product) { other -> LiveMirror.unexplainedQty(held, today, others + other, p.qty, p.lot) }
        if (q <= 0) return emptyList()
        claimed.merge(p.contract, q, Int::plus)
        val fill = rows.singleOrNull()?.avg?.takeIf { it > 0 && others == 0 } ?: p.entry
        val got = p.copy(qty = q, entryQty = q, entry = fill, unconfirmed = false, heldSeen = true, zeroSinceMs = null,
            charges = charge(if (p.side > 0) "BUY" else "SELL", fill, q, p.exchange, p.symbol, p.multiplier))
        val ltp = runCatching { Broker.stopQuotes(listOf("${p.exchange}:${p.symbol}"))["${p.exchange}:${p.symbol}"]?.last }.getOrNull()?.takeIf { it > 0 } ?: fill
        val (stop, kind) = LiveMirror.spec(p.key)?.let { firstStop(it, got) } ?: (null to OWN)
        com.optionslab.app.work.Alerts.error("${p.label}: Zerodha did not confirm the live entry in ${p.symbol} and its order was not found, but " +
            "Zerodha holds $q that no other trade explains: it is managed as this arm's trade, with its stop. Check it in Trade.", WHO)
        runCatching { Diag.record("armlive", "${p.label}: unconfirmed entry ${p.symbol} taken back from Zerodha's positions: $q @ %.2f"
            .format(Locale.ENGLISH, fill)) }
        p.paperSymbol?.let { sym -> runCatching { Notifier.liveCopyFilled(app, sym) } }
        return listOf(restStop(got.copy(stopKind = kind), stop, ltp))
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
            // 15 Nov / 16 Nov: the breakeven rule and the step lock its paper trade entered with ([rebuiltRule]; a buy only).
            val rule = if (side > 0) rebuiltRule(spec, pos.symbol, placedTime(entry.placedAt)) else (false to false)
            val p = Pos(spec.key, "recovered:${entry.id}", pos.symbol, pos.exchange, pos.product, side, kotlin.math.abs(pos.qty), kotlin.math.abs(pos.qty), lot, tick,
                pos.multiplier.toInt().coerceAtLeast(1), pos.avg.takeIf { it > 0 } ?: entry.avg, now(), pos.avg, kotlin.math.abs(pos.qty),
                // Its contract's expiry from the day's lists, so the 15:05 expiry square-off applies to it too (review).
                runCatching { (Broker.cachedInstruments().orEmpty() + Broker.cachedBfoOptions().orEmpty()).firstOrNull { it.tradingSymbol == pos.symbol }?.expiry }.getOrNull(),
                entry.id,
                seen = false, heldSeen = true, stopKind = SAFETY,
                // 15 Nov: an R51 / R72 trade taken back from Zerodha keeps the breakeven rule its paper trade entered
                // with, when the paper book shows exactly one open trade of that arm; else the switch now (it only lowers risk).
                fixed = rule.first, lock = rule.second)
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
            ". Now: ${stateOf(key)}." + (if (skipped > 0) " Not mirrored today: $skipped (last: $why)." else "") +
            // 15 Nov (Boss's exception): R51 / R72's live trades carry their own breakeven rule while the switch is on.
            (if (com.optionslab.engine.orb.StepTrail.covers(key) && StepTrailExits.isOn())
                " New live trades: ${com.optionslab.engine.orb.StepTrail.labelOf(com.optionslab.engine.orb.StepTrail.lockFor(key).takeIf { StepTrailExits.lockOn() })}, on their own fill and lots." else "")
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

    /**
     * The book forgotten (its file goes with the test's directory) and the memory notes cleared: the tests, between them and as a
     * restart (the book stays on disk). 26 Oct: also the count that stops a pass in flight from saving or showing the old book,
     * and the record of the last write. Never takes [lock] and never reads the vault.
     */
    internal fun wipe() {
        wipeGen++
        written.forget()
        cache = null; unreadable = false; recoverDue = false; gttsKept = null; legsKept.clear(); shadowMarks.clear(); shadowSeen.clear()
        readFailedMs = 0L; readFails = 0; liveKept = null; loginAsked.clear(); candlesOffUntil = 0L; shortSince.clear(); noRowSaid.clear(); shortSaid.clear(); forgetLostClaims()
        saveDue = false; blockSaid.clear(); lostExits.clear(); negSaid.clear(); placedStops.clear()
        _view.value = View(); holdingHint = false; shadowHint = false; liveKeysHint = emptyList(); paperMirroredHint = emptySet(); handsOffUntil.clear(); levels.clear(); closedRefs.clear()
        com.optionslab.engine.orb.StepTrail.clearLadders(); RuleHighs.clear()
    }

    /** 26 Oct (Erase everything): [wipe], and the book's files deleted (a set-aside copy too). */
    internal fun wipeForErase() {
        wipe()
        filesForErase().forEach { runCatching { it.delete() } }
    }

    /** 26 Oct: the live book or its notes in memory (memory only, for the erase's tests). */
    internal fun holdsForErase(): Boolean = cache?.let { it.positions.isNotEmpty() || it.switches.isNotEmpty() || it.log.isNotEmpty() || it.shadow.isNotEmpty() } == true ||
        _view.value.let { it.open.isNotEmpty() || it.closed.isNotEmpty() || it.log.isNotEmpty() || it.switches.isNotEmpty() } ||
        holdingHint || shadowHint || liveKeysHint.isNotEmpty() || levels.isNotEmpty() || closedRefs.isNotEmpty() || legsKept.isNotEmpty()

    /** 26 Oct: the book's file and any copy of it set aside as damaged (none before [init]). */
    internal fun filesForErase(): List<File> {
        val f = file ?: return emptyList()
        return listOf(f) + f.parentFile?.listFiles { x -> x.name.startsWith("${f.name}.unreadable.") }.orEmpty().toList()
    }

    /** For tests: as after a restart - the memory gone, the book on disk. */
    internal fun forgetMemoryForTest() { cache = null; legsKept.clear(); gttsKept = null; levels.clear(); com.optionslab.engine.orb.StepTrail.clearLadders(); RuleHighs.clear(); closedRefs.clear(); shadowSeen.clear(); loginAsked.clear(); handsOffUntil.clear(); shortSince.clear(); noRowSaid.clear(); shortSaid.clear(); forgetLostClaims(); blockSaid.clear(); lostExits.clear(); negSaid.clear(); placedStops.clear() }
}
