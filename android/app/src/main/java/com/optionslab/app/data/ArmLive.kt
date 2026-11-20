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
        /**
         * 19 Nov 2026 (audit batch 2: a paper-side event sold every live copy at market): its paper trade ended WITHOUT an exit decision of
         * the arm's rules (closed elsewhere, a paper reset, a paper row closed by hand, a contract the paper book forgot): this live trade no
         * longer follows a paper trade - it keeps its own stop and exits (its stop at Zerodha, its rule stop, its square-offs, Boss's hand).
         * Persisted, so a restart never takes the missing paper trade for a paper exit.
         */
        val detached: Boolean = false,
        /**
         * 19 Nov 2026 (audit batch 2, Boss 16 Nov: "a stop Boss cancelled himself is respected"): its stop at Zerodha was cancelled by Boss
         * (in Kite; not by the exchange, Zerodha's risk system, a rejection or the app itself): never put back by the app, which watches the
         * level itself and sells there. Persisted.
         */
        val stopByYou: Boolean = false,
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
        /** 19 Nov 2026 (final review): when the book was erased ("Erase everything"; market clock, ISO): the start's sweep counts only
         *  this path's orders placed after it ([sweepUnowned]). Carried by the new book an erase leaves. */
        var sweepFrom: String? = null,
        /**
         * 19 Nov 2026 (audit batch 2, B2-B): the stop orders and GTTs ("gtt:<id>") this path took out itself, the newest [OWN_CANCELS_KEPT]:
         * written with the book (on its own writer), so a restart never takes the app's own cancel for Boss's ([markOwnCancel]).
         */
        val ownCancels: ArrayList<String> = ArrayList(),
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
     * 19 Nov 2026 (third review): the first look of each process start sweeps today's live buys of this path that no trade of the book
     * owns ([sweepUnowned]) - an entry whose intent had not reached the disk when the app died after its order went (the intent is
     * written on the book's own writer, never delaying the order). Cleared once the sweep has read Zerodha's books.
     */
    @Volatile private var coldDue = true
    /**
     * 19 Nov 2026: the sweep is due and Zerodha was logged in when last asked off the tick lanes ([watch], [holding]; memory only, for
     * [recoverHint] on the tick lanes) - false again after a logout, at the next such look.
     */
    @Volatile private var coldPending = false
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
    fun handsOff(exchange: String, symbol: String) {
        handsOffUntil["$exchange:$symbol"] = System.currentTimeMillis() + HANDS_OFF_MS
        // (19 Nov 2026, audit batch 2: a stop his exit from the app takes out is the app's cancel, never taken as his in Kite.)
        if (handsOffLast.size > 200) handsOffLast.clear()
        handsOffLast["$exchange:$symbol"] = System.currentTimeMillis()
    }
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
        // (19 Nov 2026, audit batch 2: a detach note is never dropped while a trade of this book may still need it - it would sell.)
        if (detachNotes.isNotEmpty()) { val open = _view.value.open.map { it.ref }.toSet()
            detachNotes.entries.removeIf { it.value.first.isBefore(today) && it.key !in open } }
        if (levels.size > NOTES_KEPT) levels.clear()
        if (closedRefs.size > NOTES_KEPT) closedRefs.clear()
    }

    /**
     * 19 Nov 2026 (audit batch 2): paper trades that ended without an exit decision of their arm's rules ([detach]), and why, until the next
     * look has marked their live copy [Pos.detached] (memory only; the mark itself is persisted). Pruned only once a day has passed and no
     * open trade of this book is theirs.
     */
    private val detachNotes = java.util.concurrent.ConcurrentHashMap<String, Pair<LocalDate, String>>()

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
        // 19 Nov 2026 (audit batch 2, coordinator's decision B2-B): every automatic paper end - closed elsewhere or outside included (the
        // paper square-off, loss limit, sweeper, a book set aside) - still sells the live copy, as before. Only Boss's own reset or hand close
        // of the paper row detaches it ([detach]; such a copy ignores the paper's "closed elsewhere" that follows: [paperSide]). A rule exit
        // already told for the trade ([ruleExit]) keeps its reason over a later plain "closed elsewhere".
        val kept = closedRefs[ref]?.second
        if (!(LiveMirror.paperEndKeepsLive(why) && kept != null && !LiveMirror.paperEndKeepsLive(kept)))
            closedRefs[ref] = (runCatching { now().toLocalDate() }.getOrElse { LocalDate.now() }) to why
        // 22 Oct: the time machine's forward log learns how the trade ended (one queue add; the write runs on its own scope).
        runCatching { TimeMachineHost.settle(ref, why, pct) }
        if (closedRefs.size > NOTES_KEPT) prune()
        if (holdingHint || shadowHint) nudge()
    }

    /**
     * 19 Nov 2026 (audit batch 2, coordinator's decision): the arm's own exit [why] (its stop, lock, target, time or square-off) decided to
     * sell the paper trade [ref], but its sale found the paper book already flat (another paper close got there first). The paper trade is
     * booked "closed elsewhere", yet the arm meant to exit: its live copy is sold by that exit as before, never only detached. Memory only.
     */
    fun ruleExit(ref: String, why: String) {
        val w = if (LiveMirror.paperEndKeepsLive(why)) "exit" else why
        testHeard?.add("rule_exit|$ref|$w")
        // (The time machine's log is told by the paper trade's own close that follows; only the live sale is asked here.)
        closedRefs[ref] = (runCatching { now().toLocalDate() }.getOrElse { LocalDate.now() }) to w
        if (closedRefs.size > NOTES_KEPT) prune()
        if (holdingHint || shadowHint) nudge()
    }

    /**
     * 19 Nov 2026 (audit batch 2): the paper trade [ref] ended without an exit decision of its arm's rules ([why]: closed elsewhere, a paper
     * reset, a paper row closed by hand): its live copy, if any, stops following it and keeps its own stop and exits - [Pos.detached], set
     * by the next look and written with the book, and Boss told once. Memory only, never blocking (the paper hosts call it on their lanes).
     */
    fun detach(ref: String, why: String) {
        testHeard?.add("detach|$ref|$why")
        detachNotes[ref] = (runCatching { now().toLocalDate() }.getOrElse { LocalDate.now() }) to why
        if (holdingHint || shadowHint) nudge()
    }

    /**
     * 19 Nov 2026 (audit batch 2: closing a paper row by hand in Trade sold its live copy at market): the open live (and shadow) trades
     * copying the paper contract [paperSymbol] detached ([detach]) before the paper row is closed; those trades returned (memory only).
     */
    fun detachPaperSymbol(paperSymbol: String, why: String, product: String? = null): List<Pos> {
        // (B2-B: only a copy with its own 15:15 square-off is detached; a carried or MCX one goes with its paper trade, as before.)
        val hit = _view.value.open.filter { it.paperSymbol == paperSymbol && !it.detached && (product == null || it.product == product) &&
            LiveMirror.ownSquareOff(it.exchange, it.product) }
        hit.forEach { detach(it.ref, why) }
        return hit
    }

    /**
     * 19 Nov 2026 (audit batch 2): [detach] taken back for [ref] - the paper row Boss closed by hand did not close, so its live copy follows
     * its paper trade again (the mark taken off the book too, if a look already wrote it). A worker (it may write the book), never the tick
     * path; under the looks' own lock, so no look's changes are lost.
     */
    suspend fun undetach(ref: String) {
        detachNotes.remove(ref); detachSaid.remove(ref)
        if (!ready()) return
        runCatching { opLock.withLock { lock.withLock {
            val b = book()
            val i = b.positions.indexOfFirst { it.ref == ref && it.open && it.detached }
            if (i >= 0) { b.positions[i] = b.positions[i].copy(detached = false); save(b) }
        } } }
    }

    /**
     * 19 Nov 2026 (audit batch 2: a reset or a hand close followed by a kill before the next look could still sell a live copy): the live
     * trades [detach] noted are marked detached in the book and written now, Boss told once each. A worker (it writes the book), never the
     * tick path; under the looks' own lock, so no look's changes are lost.
     */
    suspend fun applyDetached() {
        if (!ready() || detachNotes.isEmpty()) return
        runCatching { opLock.withLock { lock.withLock {
            val b = book()
            val hit = ArrayList<Pos>()
            for (i in b.positions.indices) {
                val p = b.positions[i]
                if (p.open && !p.detached && detachNotes.containsKey(p.ref)) { b.positions[i] = p.copy(detached = true); hit += b.positions[i] }
            }
            // (Not said for one an exit of its arm's own rules is about to sell: [ruleExit].)
            if (hit.isNotEmpty()) { save(b); hit.forEach { if (!it.shadow && closedWhy(it.ref)?.let { w -> !LiveMirror.paperEndKeepsLive(w) } != true) saidDetached(it) } }
        } } }
    }

    /**
     * 19 Nov 2026 (audit batch 2: "Reset paper" emptied the paper book while saying nothing about Zerodha is touched, and every mirrored
     * arm then sold its live copy): every open live (and shadow) trade detached ([detach]) before the paper book is emptied; the live ones
     * returned. Memory only (the caller reads the book first: [refresh]).
     */
    fun detachAll(why: String): List<Pos> {
        val hit = _view.value.open.filter { !it.detached && LiveMirror.ownSquareOff(it.exchange, it.product) }
        hit.forEach { detach(it.ref, why) }
        return hit
    }

    /** 19 Nov 2026 (audit batch 2): the open live trades that still follow a paper trade ([paperSymbol]'s only, when given). Memory only. */
    fun liveCopiesOpen(paperSymbol: String? = null, product: String? = null): List<Pos> =
        _view.value.openReal.filter { !it.detached && (paperSymbol == null || it.paperSymbol == paperSymbol) && (product == null || it.product == product) }

    /**
     * 19 Nov 2026 (audit batch 2): what a paper reset or a paper row closed by hand does to the live [copies] (none: null) - in the confirm
     * dialog and the answer. Words only.
     */
    fun liveCopyWords(trades: List<Pos>): String? {
        val copies = trades.filter { !it.shadow }
        if (copies.isEmpty()) return null
        // (B2-B: true words - a copy with its own 15:15 square-off stays open; a carried or MCX one is sold with its paper trade.)
        val (kept, sold) = copies.partition { LiveMirror.ownSquareOff(it.exchange, it.product) }
        fun names(l: List<Pos>) = l.joinToString { "${it.label} ${it.symbol}" }
        val k = if (kept.isEmpty()) null else { val one = kept.size == 1
            "${if (one) "A live copy is" else "${kept.size} live copies are"} open at Zerodha (${names(kept)}): ${if (one) "it stays" else "they stay"} " +
                "open with ${if (one) "its" else "their"} own stop${if (one) "" else "s"} and the 15:15 square-off; nothing at Zerodha is sold for " +
                "${if (one) "it" else "them"}. Close ${if (one) "it" else "them"} in Trade or Kite if you want ${if (one) "it" else "them"} out." }
        val s = if (sold.isEmpty()) null else { val one = sold.size == 1
            "${if (one) "A live copy" else "${sold.size} live copies"} with no square-off of ${if (one) "its" else "their"} own (${names(sold)}) " +
                "${if (one) "is" else "are"} sold at Zerodha with the paper trade, as before." }
        return listOfNotNull(k, s).joinToString(" ")
    }

    /**
     * 19 Nov 2026 (audit batch 2): Zerodha's last price of the paper contract [c] (null: not listed at Zerodha, or no price) - the price a
     * would-be paper trade whose paper side had no fresh price is booked at, when its live copy is asked ([PassedArmsHost]). A worker
     * (an entry pass: the instruments list and one quote read), never the tick path.
     */
    internal suspend fun liveLast(c: Paper.Contract): Double? {
        val ins = runCatching { zerodha(c).first }.getOrNull() ?: return null
        val k = "${ins.exchange}:${ins.symbol}"
        return runCatching { Broker.stopQuotes(listOf(k))[k]?.last }.getOrNull()?.takeIf { it > 0 && it.isFinite() }
    }

    /** TEST ONLY: the screen's view set (19 Nov 2026: the paper reset dialog's live-copy line). Debug builds only. */
    internal fun viewForTest(v: View) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; _view.value = v }

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
        // 19 Nov 2026 (audit batch 2: ntfy's live gate): ntfy alerts goes on only with an access token or Boss's own server, from any
        // page (Bot settings' arms list, the timeline's Undo); else said and left off. Off always goes.
        if (on && key == LiveMirror.NTFY_ALERTS && !runCatching { NtfyReader.secured(NtfyReader.config()) }.getOrDefault(false)) return "${NtfyReader.LIVE_NEEDS_TOKEN} $UNCHANGED"
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
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) runCatching { b.positions += readPos(a.getJSONObject(i)) }.onFailure {
                // (A row that shows its exit was a closed trade: nothing to take back for it.)
                if (runCatching { a.getJSONObject(i).optString("exit").isEmpty() }.getOrDefault(true)) badRows++ } }
            o.optJSONObject("switches")?.let { m -> m.keys().forEach { b.switches[it] = m.getBoolean(it) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONObject("refused")?.let { m -> m.keys().forEach { b.refused[it] = m.getString(it) } }
            o.optJSONObject("shadow")?.let { m -> m.keys().forEach { b.shadow[it] = m.getBoolean(it) } }
            o.optJSONObject("shadowSince")?.let { m -> m.keys().forEach { k -> runCatching { b.shadowSince[k] = LocalDateTime.parse(m.getString(k)) } } }
            o.optString("sweepFrom").takeIf { it.isNotEmpty() }?.let { b.sweepFrom = it }
            o.optJSONArray("ownCancels")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { b.ownCancels += it } }
            synchronized(ownCancels) { ownCancels += b.ownCancels }
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
        // 19 Nov 2026 (final review): the new book an erase leaves carries when it was erased (written at once, below).
        val afterErase = bytes == null && b.sweepFrom == null && erasedAt != null
        if (afterErase) b.sweepFrom = erasedAt
        if (unreadable) runCatching { Diag.record("armlive", "the live book was read again: its open live trades are watched") }
        unreadable = false
        readFails = 0
        loadGen = gen
        // 26 Oct: a book read while an erase ran is used by this pass only (never kept, shown or saved).
        if (gen != wipeGen) return b
        cache = b
        publish(b)
        if (afterErase) runCatching { saveAsync(b) }
        return b
    }

    /** 19 Nov 2026 (final review): when this process last erased the book (market clock, ISO); a new book carries it ([Book.sweepFrom]). */
    @Volatile private var erasedAt: String? = null

    /** 19 Nov 2026 (final check): the erase time on disk ([ERASE_MARK]), only when it reads as a time; the sweep's worker only. */
    private fun markedErase(): String? = runCatching { eraseMark()?.takeIf { it.exists() }?.readText()?.trim()?.takeIf { LocalDateTime.parse(it) != null } }.getOrNull()

    /** 19 Nov 2026 (final check): the last erase's time on disk (only that), beside the book; null before [init]. */
    private fun eraseMark(): File? = file?.let { File(it.parentFile, ERASE_MARK) }
    internal const val ERASE_MARK = "arm_live_erased.txt"

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
            s("paperSymbol"), p.optBoolean("step500", false), d("best"), p.optBoolean("stepLock", false),
            p.optBoolean("detached", false), p.optBoolean("stopByYou", false))
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
        .apply { if (p.detached) put("detached", true); if (p.stopByYou) put("stopByYou", true) }

    /**
     * The book written (and shown). 18 Nov 2026 (audit: a failed write was swallowed): false when it could not be written - Boss is told
     * once until a write works again, and the next look writes it again ([saveDue]).
     */
    private fun save(b: Book): Boolean {
        // 26 Oct: a book read before an erase is never written back over it.
        if (loadGen != wipeGen) return false
        val bytes = bytesOf(b)
        val seq = saveSeq.incrementAndGet()
        val ok = writeSeq(seq, wipeGen, bytes)
        publish(b)
        return ok
    }

    /**
     * 19 Nov 2026 (second review of the audit, item 2): [b] shown at once and written on the book's own writer thread, in order - the
     * entry's intent, so its write (up to ~1.5 s with StrongBox) never delays the order nor holds [lock] from the other trades' looks.
     * Built under the caller's [lock]; a later [save] is never overtaken ([writeSeq] drops an older write), and an erase since drops it.
     */
    private fun saveAsync(b: Book) {
        if (loadGen != wipeGen) return
        val bytes = bytesOf(b)
        val seq = saveSeq.incrementAndGet()
        val wg = wipeGen
        publish(b)
        writer.execute { runCatching { writeSeq(seq, wg, bytes) } }
    }

    /** 19 Nov 2026: the order the book's saves were built in ([save], [saveAsync]). */
    private val saveSeq = java.util.concurrent.atomic.AtomicLong(0)
    /** 19 Nov 2026: the newest save written (guarded by [writeMon]). */
    private var writtenSeq = 0L
    /** 19 Nov 2026: the newest save that failed (guarded by [writeMon]). */
    private var failedSeq = 0L
    private val writeMon = Any()
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "armlive-book").apply { isDaemon = true } }

    /** TEST ONLY: a lost exit of trade [ref] on [symbol] (NFO, MIS) found as order [id] (19 Nov 2026, third review). */
    internal fun seedFoundLostForTest(ref: String, symbol: String, id: String) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        val o = Kite.Order(symbol, Kite.Side.SELL, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", LiveMirror.TAG)
        lostExits["NFO|$symbol|MIS"] = LostExit(ref, "paper_exit", o, emptySet(), runCatching { nowMs() }.getOrDefault(0L), foundId = id)
    }

    /** TEST ONLY: every write handed to the book's writer so far is done. */
    internal fun drainWritesForTest() { writer.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS) }

    /**
     * 19 Nov 2026: save [seq] ([bytes], built at erase generation [wg]) written, one at a time; never one older than a save already
     * written, nor one built before an erase. False when it could not be written: Boss is told once until a write works again, and
     * the next look writes the book again ([saveDue]).
     */
    private fun writeSeq(seq: Long, wg: Int, bytes: ByteArray): Boolean = synchronized(writeMon) {
        if (seq <= writtenSeq) return@synchronized true
        if (wg != wipeGen) return@synchronized false
        val ok = file?.let { f -> runCatching {
            testWriteFails?.let { if (it.getAndDecrement() > 0) throw java.io.IOException("test: the book could not be written") }
            written.write(f, bytes)
        }.isSuccess } ?: true
        if (ok) writtenSeq = seq
        // (An older write that works never clears a newer one's failure: the next look writes the book in memory again.)
        if (!ok) failedSeq = maxOf(failedSeq, seq)
        if (ok && seq < failedSeq) return@synchronized true
        if (!ok && !saveDue) com.optionslab.app.work.Alerts.error("The arms' live book could not be saved: its open live trades are still " +
            "watched, and it is saved again on the next look. Check open Zerodha positions in Trade.", WHO)
        saveDue = !ok
        ok
    }

    /** The book as written to disk (its kept rows trimmed first). */
    private fun bytesOf(b: Book): ByteArray {
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
        b.sweepFrom?.let { o.put("sweepFrom", it) }
        if (ownCancelsDue.isNotEmpty()) { val due = ownCancelsDue.toList(); due.forEach { if (it !in b.ownCancels) b.ownCancels += it }; ownCancelsDue.removeAll(due.toSet()) }
        while (b.ownCancels.size > OWN_CANCELS_KEPT) b.ownCancels.removeAt(0)
        if (b.ownCancels.isNotEmpty()) o.put("ownCancels", JSONArray().apply { b.ownCancels.forEach { put(it) } })
        return o.toString().toByteArray(Charsets.UTF_8)
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
        if ((recoverDue || coldDue) && Broker.loggedIn) { if (coldDue) coldPendingBound(); runCatching { watch() } }
        else if (coldDue) coldPending = false
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
            accountStop = if (shadow || !accountStopActs(key)) null else accountStop(), loggedIn = Broker.loggedIn)
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

    /**
     * 20 Nov 2026 (Boss: "Yes, make it follow switch", after "Its off then why separate button"): whether [accountStop] refuses arm
     * [key]'s live entries. For R51, R72 and Liquidity (every book; [com.optionslab.ira.ArmAutoStops.covers], by source) it follows
     * Boss's "Liquidity live stop rule applies to R51, R72 and Liquidity" switch: off, this same -Rs 40,000 / -Rs 13,000-a-month rule
     * no longer refuses them, whenever it was reached; on, it refuses as before. Every other arm keeps it always. Before the switches
     * are read every rule counts as on (the stop keeps acting). Memory only.
     */
    internal fun accountStopActs(key: String): Boolean = runCatching {
        ArmAutoStopsHost.acts(com.optionslab.ira.ArmAutoStops.Rule.LIVE_STOP, key)
    }.getOrDefault(true)

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
    private suspend fun notMirrored(a: Ask, why: String, dropIntent: Boolean = false) {
        runCatching {
            lock.withLock {
                val b = book()
                // 18 Nov 2026 (audit A4): [dropIntent] - from [enterNow] after it wrote this entry's intent, when nothing was bought: the
                // intent is dropped. (Never otherwise: an earlier entry of the same paper trade still settling is left alone.)
                if (dropIntent) b.positions.removeAll { it.ref == a.ref && it.open && !it.shadow && it.unconfirmed && it.entryOrderId == null }
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
        // (19 Nov 2026, audit batch 2: one closed elsewhere before its live entry went is still closed - not mirrored, as before.)
        detachNotes[a.ref]?.let { notMirrored(a, "its paper trade is already closed (${it.second})"); return@withLock }
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
        // setting, never more. Priced at the higher of the last price, the offer and its own limit (19 Nov 2026: a buy at the LIMIT it
        // is sent at, [LiveMirror.entryLimit] - the offer plus its buffer, capped). A sale, a future or an MCX
        // option (its own segment's money) is never cut; not even one lot fitting, or the cash unreadable, goes as before.
        val want = qty / ins.lot
        // 15 Nov: the funds check below reuses the fit's own read when it ran (nothing new is read for it).
        val optionBuy = a.side > 0 && a.contract.right != Right.IX && ins.exchange != "MCX"
        // 19 Nov 2026 (Boss's explicit choice: "Go" - no shadow phase; Zerodha refused MARKET option buys for "Insufficient funds", as it
        // blocks margin for them at a protective price far above the premium): every live BUY entry goes as a marketable LIMIT at the offer
        // plus a small buffer, never above the paper arm's own limit ([LiveMirror.entryLimit]); the lots that fit and the funds check are
        // priced at that LIMIT. Unfilled within [ENTRY_LIMIT_WAIT_MS] its rest is cancelled (never chased) and only what filled is booked.
        val entryPx = if (a.side > 0) LiveMirror.entryLimit(quote?.ask, last, ins.tick, a.limit) else null
        // (No usable LIMIT for a buy - a paper limit under one tick: never sent at a price above it.)
        if (a.side > 0 && entryPx == null) { notMirrored(a, "no usable entry price for ${ins.symbol}"); return@withLock }
        val fitPx = entryPx ?: maxOf(last, quote?.ask?.takeIf { it.isFinite() && it > 0 } ?: last, a.limit ?: 0.0)
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
        // A buy: the marketable LIMIT above (unfilled in the wait below, its rest is cancelled and only what filled is booked - never
        // chased). A sale: the paper arm's own order type, as before - a LIMIT entry pays at most its paper limit, else MARKET.
        val o = if (entryPx != null) Kite.Order(ins.symbol, side, qty, ins.lot, spec.product, "LIMIT", entryPx, ins.tick, ins.exchange,
                LiveMirror.TAG, multiplier = ins.multiplier)
            else if (a.limit != null) Kite.Order(ins.symbol, side, qty, ins.lot, spec.product, "LIMIT",
                Kite.onTick(a.limit, ins.tick, side), ins.tick, ins.exchange, LiveMirror.TAG, multiplier = ins.multiplier)
            else Kite.Order(ins.symbol, side, qty, ins.lot, spec.product, "MARKET", null, ins.tick, ins.exchange, LiveMirror.TAG, multiplier = ins.multiplier)
        // (One price for the account read and the guard: a buy's LIMIT, its ceiling; a sale never below the last price.)
        val guardPx = entryPx ?: maxOf(last, o.price ?: last)
        val acct = runCatching { Broker.accountNow(needed = guardPx * qty * ins.multiplier) }.getOrNull()
        Guard.check(Guard.liveOrder(o).copy(price = guardPx), acct).takeIf { it.isNotEmpty() }?.let { notMirrored(a, "account guard: " + it.joinToString(" ")); return@withLock }
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
        // told). (The entry path, never the tick path.) 19 Nov 2026 (second review): written on the book's own writer ([saveAsync]), so the
        // write never delays the order nor holds the other trades' looks; one that fails is said ([writeSeq]) and the entry goes as before
        // - never a new skip of a live entry.
        // (With the stop it would rest on the price now, so a restart that finds only the intent still has its level.)
        val intent = firstStop(spec, base).let { (lv, kind) -> base.copy(unconfirmed = true, stopTrigger = lv, stopKind = kind) }
        runCatching { lock.withLock { val b = book(); b.positions += intent; saveAsync(b) } }
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
            notMirrored(a, "not logged in to Zerodha today", dropIntent = true); return@withLock
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
                catch (e2: Broker.NotLoggedIn) { notMirrored(a, "not logged in to Zerodha today", dropIntent = true); return@withLock }
                catch (e2: Exception) {
                    if (ArmOrderLabels.zerodhaRefused(e2)) ArmOrderLabels.refused(sent2)
                    if (Broker.definite(e2)) { notMirrored(a, e2.message ?: "Zerodha refused the order", dropIntent = true); return@withLock }
                    try { Broker.findRecentRetrying(retry, known) } catch (_: Exception) { null }
                }
            } else {
            if (Broker.definite(e)) { notMirrored(a, e.message ?: "Zerodha refused the order", dropIntent = true); return@withLock }
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
        // (19 Nov 2026, decided with Boss's LIMIT entries to keep full cadence: a buy's marketable LIMIT is waited for at most 5 s, so the
        // entry lock - other arms' entries, the lost-entry search and the start's sweep wait on it - is never held long; a sale's MARKET
        // as before.)
        val wait = testEntryWaitMs ?: if (o.orderType == "LIMIT" && entryPx != null) ENTRY_LIMIT_WAIT_MS else ENTRY_WAIT_MS
        var f = id?.let { runCatching { Broker.awaitOrder(it, wait) }.getOrNull() }
        if (id != null && f?.status !in DONE) {
            // Not finished in the wait: the rest is cancelled so it can never fill later untracked, the cancel confirmed (up to
            // [CANCEL_CONFIRM_MS]); only what filled is booked, with its stop.
            runCatching { Broker.cancel(id) }
            f = runCatching { Broker.orderState(id) }.getOrNull()
            // (On the monotonic clock; a cancel that did not take - the order still working a second on - is sent again.)
            val start = System.nanoTime()
            var resentAt = start
            while ((f == null || f.status !in DONE) && (System.nanoTime() - start) / 1_000_000 < CANCEL_CONFIRM_MS) {
                kotlinx.coroutines.delay(250)
                f = runCatching { Broker.orderState(id) }.getOrNull() ?: f
                if (f?.status?.let { it in DONE } != true && (System.nanoTime() - resentAt) / 1_000_000 >= 1_000) {
                    resentAt = System.nanoTime(); runCatching { Broker.cancel(id) }
                }
            }
        }
        if (f == null || f.status !in DONE) {
            // Zerodha has not said how it ended: held as unconfirmed (no second entry meanwhile) until the order book settles it.
            lock.withLock { val b = book(); b.putIntent(a.ref, intent.copy(entryOrderId = id)); line(b, "${spec.label}: bought ${ins.symbol}, not yet confirmed"); save(b) }
            com.optionslab.app.work.Alerts.error("${spec.label}: Zerodha did not confirm the live ${side.name.lowercase()} of ${ins.symbol}. " +
                "It is treated as held until the order book shows what filled.", WHO)
            return@withLock
        }
        if (f.filled <= 0) { notMirrored(a, "Zerodha ${f.status.lowercase()}" + (f.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""), dropIntent = true); return@withLock }
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
        // (19 Nov 2026: a buy's marketable entry LIMIT still pays the measured entry slippage, as its MARKET did - never a better shadow
        // fill than live - but no more than its LIMIT; it would not have filled only when the offer itself is above that LIMIT. A sale's
        // paper LIMIT fills at its price, as before.)
        val raw = LiveMirror.shadowFill(buy, base.entry, quote?.bid, quote?.ask, base.tick, halfSpreadOf(base),
            if (limit != null && !buy) 0.0 else measured(com.optionslab.ira.Slippage.Leg.ENTRY, ENTRY_BASES))
        // (The offer; with no book, the last price plus its half-spread on the tick, as [LiveMirror.shadowFill] prices it.)
        val touch = if (buy && limit != null) quote?.ask?.takeIf { it.isFinite() && it > 0 }
            ?: Kite.onTick(base.entry * (1 + halfSpreadOf(base)), base.tick, Kite.Side.BUY) else raw
        val px = if (buy && limit != null && touch <= limit + 1e-9) minOf(raw, limit) else raw
        if (limit != null && (if (buy) touch > limit + 1e-9 else px < limit - 1e-9)) {
            notMirrored(a, "shadow: its LIMIT %.2f would not have filled at %.2f".format(Locale.ENGLISH, limit, if (buy) touch else px)); return
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
        // 20 Nov 2026 (deep review of audit B5): only the closed minutes' highs ([RuleHighs.askClosed]) - never the stream's raw ticks, whose
        // one stray print could raise the Zerodha step lock; the stream counts through [bestPx] alone, each price checked.
        // (R51 / R72 only: every other rule trade - Liquidity 15+5 - keeps its reads as they were.)
        if (fresh) (p.paperSymbol ?: p.symbol).let { runCatching { Paper.contractOf(it) }.getOrNull() }?.let {
            if (checkedBest(p)) RuleHighs.askClosed("live:${p.ref}", it, p.entryTime) else RuleHighs.ask("live:${p.ref}", it, p.entryTime) }
        return RuleHighs.peek("live:${p.ref}")
    }

    /**
     * 20 Nov 2026 (deep review of audit B5: a stray print could raise a live R51 / R72 copy's step lock at Zerodha and sell it early): the
     * price now that may raise [p]'s best ([withBest]) - the stream's last trade only when it passes the price-now checks
     * ([com.optionslab.ira.PriceNow.lastCounts] / [com.optionslab.ira.PriceNow.farFromBook]) and traded after the entry minute; else the
     * sane book's bid ([com.optionslab.ira.PriceNow.bookBid]). Not streamed, Zerodha's quote [q] as the live stop judges it
     * ([com.optionslab.ira.PriceNow.stopLast]: its last price unless far outside a sane book).
     * The lock levels themselves are unchanged. Memory only (the stream's kept tick).
     */
    internal fun bestPx(p: Pos, q: Broker.Quote?): Double? {
        if (!p.fixed) return null
        // (Every other rule trade - Liquidity 15+5 - as before: the quote's last price.)
        if (!checkedBest(p)) return q?.last?.takeIf { it > 0 }
        val entrySec = p.entryTime.withSecond(0).withNano(0).atZone(com.optionslab.engine.IST).toEpochSecond()
        // (The token from memory only: never the day's list built from its file on the stream's look.)
        val t = runCatching { Broker.tokenKnown("${p.exchange}:${p.symbol}")?.let { KiteStream.freshTick(it) } }.getOrNull()
        if (t != null) {
            val ts = t.lastTradeTime
            val book = com.optionslab.ira.PriceNow.bookBid(t.bid, t.ask)
            // A trade after the entry minute, and - with a sane book there - not more than [PriceNow.FAR] outside it (a stray print with a
            // trade time is as stray as one without).
            val ask = t.ask
            val inBook = book == null || ask == null ||
                (t.last >= book * (1 - com.optionslab.ira.PriceNow.FAR) && t.last <= ask * (1 + com.optionslab.ira.PriceNow.FAR))
            if (ts != null && ts >= entrySec + 60 && com.optionslab.ira.PriceNow.lastCounts(t.last, ts, entrySec) &&
                !com.optionslab.ira.PriceNow.farFromBook(t.last, ts, t.bid, t.ask) && inBook) return t.last
            return book ?: q?.let { com.optionslab.ira.PriceNow.bookBid(it.bid, it.ask) }
        }
        // Not streamed: Zerodha's quote, whose last price carries no trade time here - judged as the live stop's price is
        // ([com.optionslab.ira.PriceNow.stopLast]: the last price unless it lies far outside a sane book, then the bid).
        return q?.takeIf { it.last > 0 && it.last.isFinite() }?.let { com.optionslab.ira.PriceNow.stopLast(it.last, it.bid, it.ask) }
    }

    /** 20 Nov 2026: [p] is an R51 / R72 rule trade, whose best counts checked prices only ([bestPx], [highSince]). */
    internal fun checkedBest(p: Pos): Boolean = p.key == com.optionslab.engine.orb.DonchianArm.SOURCE || p.key == com.optionslab.engine.orb.BreadthDonchianArm.SOURCE


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
        // 19 Nov 2026 (audit batch 2): a stop Boss cancelled himself is never put back - the app watches the level and sells there.
        if (p.stopByYou) return p.copy(stopTrigger = trigger)
        // 19 Nov 2026 (audit batch 2): while Zerodha keeps refusing it, rested again only after its back-off ([refusedStop]: 5 s doubling
        // to 60 s) - no reads and no order meanwhile, the level watched by the app ([LiveMirror.decide] sells at it with nothing resting).
        restBackoff[p.ref]?.let { (_, until) -> if (runCatching { nowMs() }.getOrDefault(Long.MAX_VALUE) < until) return p.copy(stopTrigger = trigger) }
        val exitSide = if (p.side > 0) Kite.Side.SELL else Kite.Side.BUY
        // 18 Nov 2026 (audit A2): an exit whose reply was lost may still be selling it: no stop beside it until that is known.
        if (lostExitHolds(p.contract) || unsearchedLost.containsKey(p.contract)) return p.copy(stopTrigger = trigger)
        // 20 Oct, enhancement round 2: read and rested in the contract's one exit turn ([ExitCoordinator]; re-entrant when an exit
        // that did not go puts its stop back).
        // (Its id is written with the look's own save, or the entry's; a death before that leaves the stop resting with no trade owning
        // it, which the restart takes back as this trade's own: [orphanStop].)
        return ExitCoordinator.turn(p.exchange, p.symbol, "arm ${p.label} stop") { restStopInTurn(p, trigger, ltp, spec, exitSide) }
    }

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
        // (Review: with two trades on the contract, the one that fits this trade - its quantity, then the trigger nearest its own level -
        // newest first; one for more than this trade holds is never taken.)
        val want = p.stopTrigger
        return cands.filter { it.id !in owned && it.id !in placedStops && ExitQty.remaining(it.qty, it.filled, it.pending) in 1..p.qty }
            .sortedWith(compareBy<Broker.OrderRow>({ ExitQty.remaining(it.qty, it.filled, it.pending) != p.qty },
                { if (want == null) 0.0 else kotlin.math.abs(it.trigger - want) }).thenByDescending { it.placedAt }).firstOrNull()
    }

    /**
     * 18 Nov 2026 (audit A4): the stops this run placed (memory, bounded): never taken for an orphan ([orphanStop]) - a sibling's stop
     * placed a moment ago, its id not written yet. After a restart it is empty, which is when an orphan can be.
     */
    private val placedStops: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** TEST ONLY: the measured ENTRY slippage share, in place of the fills' (19 Nov 2026: the shadow LIMIT buy's test). Debug builds only. */
    @Volatile internal var testMeasured: Double? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** How long an entry order is waited for before its rest is cancelled (only what filled is booked). */
    private const val ENTRY_WAIT_MS = 15_000L
    /** 19 Nov 2026: a buy's marketable entry LIMIT is waited for at most this long (then its rest is cancelled). */
    private const val ENTRY_LIMIT_WAIT_MS = 5_000L
    /** 19 Nov 2026: how long an entry's cancel is read again for before it is held as unconfirmed ([settle] takes it from there). */
    private const val CANCEL_CONFIRM_MS = 3_000L
    /** TEST ONLY: [ENTRY_WAIT_MS] and [ENTRY_LIMIT_WAIT_MS] shortened (19 Nov 2026: the LIMIT entries' tests). Debug builds only. */
    @Volatile internal var testEntryWaitMs: Long? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** 19 Nov 2026 (second review): since when each trade (ref) has had no stop resting because the positions do not show its fill. */
    private val noStopSince = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val NO_STOP_SAY_MS = 60_000L

    /**
     * 18 Nov 2026 (audit A2): the arms' live exits and stops use the positions-lag guard whatever its switch says (it only ever sends or
     * rests less). 19 Nov 2026 (second review): said once in the diary while that switch is in shadow, so Boss can see it. Memory only.
     */
    private fun lagForced(): Boolean {
        if (!lagForcedSaid && runCatching { !ExitCoordinator.isLagOn() }.getOrDefault(false)) {
            lagForcedSaid = true
            runCatching { Diag.record("armlive", "The positions-lag guard acts on the arms' live exits and stops although its switch is in " +
                "shadow (audit A2, 18 Nov 2026: a trailing positions read was sold again). It only ever sends or rests less.") }
        }
        return true
    }
    @Volatile private var lagForcedSaid = false

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
            ExitCoordinator.held(p.exchange, p.symbol, p.product, "arm ${p.label} stop", book, forceLag = lagForced()) * p.side
        }.getOrNull()
        if (covered == null || heldNow == null) { stopFailed(p, trigger, "Zerodha's books could not be read"); return p.copy(stopTrigger = trigger) }
        // Seen held is the look's to say, oldest first (review: the contract's total includes the other trades on it). A new stop
        // order has nothing of its fill booked yet.
        val seen = p.copy(stopBooked = 0)
        // 18 Nov 2026 (audit A4): this path's own stop resting on the contract that no open trade owns (placed just before a restart,
        // its id never written) is this trade's: taken as its stop, never counted as another order beside a second one.
        if (p.stopOrderId == null && spec.rest != LiveMirror.Rest.GTT) orphanStop(p.copy(stopTrigger = trigger), book)?.let { o ->
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
        // (Only when those orders are what take the room: never for a sibling arm's stop or a positions read lagging a new fill.)
        val withoutForeign = ((heldNow - (covered - foreign.sumOf { ExitQty.remaining(it.qty, it.filled, it.pending) })).coerceAtLeast(0) /
            p.lot.coerceAtLeast(1)) * p.lot.coerceAtLeast(1)
        if (foreign.isNotEmpty() && q < minOf(p.qty, withoutForeign))
            sayBlocked(p, "covered", again = false, "${p.label}: its live ${stopWords(p)} on ${p.symbol} cannot rest at Zerodha for " +
                "${p.qty - q.coerceAtLeast(0)} because another sell order covers the lot (${foreign.joinToString { "${it.type} ${it.id}" }}). " +
                "The app watches %.2f itself; ".format(Locale.ENGLISH, trigger) + coveredWords(foreign))
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
            if (id == null) { refusedStop(p, trigger, "Zerodha did not take it"); return seen.copy(stopTrigger = trigger) }
            return seen.copy(stopOrderId = GTT + id, stopTrigger = trigger, stopQty = q)
        }
        val o = Kite.Order(p.symbol, exitSide, q, p.lot, p.product, "SL", LiveMirror.stopLimit(trigger, p.tick, p.side), p.tick, p.exchange,
            LiveMirror.TAG, triggerPrice = trigger, multiplier = p.multiplier)
        var refusal: String? = null
        val id = try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
            refusal = e.message?.take(160)
            if (Broker.definite(e)) null else runCatching { Broker.findRecentRetrying(o, known()) }.getOrNull()
        }
        // 19 Nov 2026 (audit batch 2): with its reason, said once, and rested again only after a back-off ([refusedStop]).
        if (id == null) { refusedStop(p, trigger, refusal?.let { "Zerodha refused it: $it" } ?: "Zerodha refused it"); return seen.copy(stopTrigger = trigger) }
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

    /**
     * 19 Nov 2026 (audit batch 2: when Zerodha kept refusing an arm's stop, every look - the live lane's on each tick included - read
     * Zerodha's books and sent it again): by trade (ref), the refusals in a row and when its stop may be tried again (market-clock ms).
     * Dropped once a stop of the trade is seen resting at Zerodha, or the trade closes. Memory only (a restart tries at once, then backs off).
     */
    private val restBackoff = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
    /** 19 Nov 2026 (audit batch 2): the trades whose stop refusal Boss was told of (once until a stop of the trade rests again). Memory. */
    private val restRefusedSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** 19 Nov 2026 (audit batch 2): by trade (ref), the stop order whose cancel Boss was told of (said once for each stop). Memory. */
    private val endedSaid = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 19 Nov 2026 (audit batch 2): Zerodha refused [p]'s stop at [trigger] ([why]: its answer, or a stop it took and then rejected): it is
     * tried again only after [LiveMirror.restBackoffMs] (5 s doubling to 60 s), the app watching the level meanwhile, and Boss is told
     * once, with the reason, until a stop of the trade rests at Zerodha again. Memory only.
     */
    /**
     * 19 Nov 2026 (audit batch 2): [p]'s stop is rested again only after [LiveMirror.restBackoffMs] - one more try in a row (5 s doubling to
     * 60 s). The wait in ms.
     */
    private fun backOff(p: Pos): Long {
        val ms = runCatching { nowMs() }.getOrDefault(System.currentTimeMillis())
        val tries = (restBackoff[p.ref]?.first ?: 0) + 1
        val wait = LiveMirror.restBackoffMs(tries)
        if (restBackoff.size > 500) restBackoff.clear()
        restBackoff[p.ref] = tries to ms + wait
        return wait
    }

    private fun refusedStop(p: Pos, trigger: Double?, why: String) {
        val wait = backOff(p)
        if (restRefusedSaid.size > 500) restRefusedSaid.clear()
        if (!restRefusedSaid.add(p.ref)) return
        val text = "${p.label}: Zerodha refused its live ${stopWords(p)} on ${p.symbol}" +
            (trigger?.let { " at %.2f".format(Locale.ENGLISH, it) } ?: "") + " ($why). The app watches the level itself and sells there; it tries " +
            "to rest the stop at Zerodha again in ${wait / 1000} s, then less often (at most every ${LiveMirror.REST_BACKOFF_MAX_MS / 1000} s)."
        com.optionslab.app.work.Alerts.error(text, WHO)
        note(p.key, text)
    }

    /**
     * 19 Nov 2026 (audit batch 2): the stop orders this process cancelled itself (an exit taking its stop out first): a cancel of one of
     * these is the app's, never taken as Boss's ([Pos.stopByYou]). Memory only, bounded (the oldest dropped first).
     */
    private val ownCancels: MutableSet<String> = java.util.Collections.newSetFromMap(object : java.util.LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 500
    })
    private fun ownCancel(id: String): Boolean = synchronized(ownCancels) { id in ownCancels }
    /**
     * [id] (an SL order's id, or "gtt:<id>") is being taken out by this path itself: kept in memory at once and written with the book on its
     * own writer ([saveAsync]: the cancel never waits on the disk), before the cancel goes - so a restart reads it as the app's, never Boss's.
     * 19 Nov 2026 (audit batch 2, B2-B).
     */
    private suspend fun markOwnCancel(id: String) = markOwnCancels(listOf(id))

    /**
     * 19 Nov 2026 (audit batch 2, final review): [ids] (SL order ids, or "gtt:<id>") are being cancelled by the app itself - an arm's exit, or
     * Boss's manual exit from the app taking the arms' resting stops out ([com.optionslab.app.ui.AppModel]'s takeOverLeg): in memory at once,
     * before the cancel goes, and written with the book on its own writer - never waiting on the book's lock or the vault (taken only when
     * free; else a write is asked on this object's own worker). So a restart never reads the app's own cancel as Boss's.
     */
    internal suspend fun markOwnCancels(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(ownCancels) { ownCancels += ids }
        if (!ready()) { ownCancelsDue += ids; return }
        if (lock.tryLock()) try {
            val b = cache
            if (b != null) { var add = false; for (id in ids) if (id !in b.ownCancels) { b.ownCancels += id; add = true }; if (add) saveAsync(b) }
            else { ownCancelsDue += ids; askOwnCancelsWrite() }
        } finally { lock.unlock() }
        else { ownCancelsDue += ids; askOwnCancelsWrite() }
    }

    /** 19 Nov 2026 (final review): one write of the book asked on this object's own worker for the marks still due (never more than one queued). */
    private val ownCancelsWriteAsked = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun askOwnCancelsWrite() {
        if (!ownCancelsWriteAsked.compareAndSet(false, true)) return
        scope.launch {
            try { runCatching { lock.withLock { runCatching { book() }; cache?.let { if (ownCancelsDue.isNotEmpty()) saveAsync(it) } } } }
            finally { ownCancelsWriteAsked.set(false) }
        }
    }

    /**
     * 19 Nov 2026 (final review): [ids] the app marked its own ([markOwnCancels]) whose cancel did not take (still working at Zerodha): the
     * marks taken back, so a later cancel of them by Boss in Kite is his. Memory at once; the book on its next write.
     */
    internal suspend fun unmarkOwnCancels(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(ownCancels) { ownCancels.removeAll(ids.toSet()) }
        ownCancelsDue.removeAll(ids.toSet())
        if (!ready()) return
        if (lock.tryLock()) try { cache?.let { b -> if (b.ownCancels.removeAll(ids.toSet())) saveAsync(b) } } finally { lock.unlock() }
        else scope.launch { runCatching { lock.withLock { cache?.let { b -> if (b.ownCancels.removeAll(ids.toSet())) saveAsync(b) } } } }
    }
    /** 19 Nov 2026 (B2-B): own-cancel marks still to be written (the book's lock was busy): folded into the next write ([bytesOf]). */
    private val ownCancelsDue: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private const val OWN_CANCELS_KEPT = 200

    /** TEST ONLY (19 Nov 2026): [markOwnCancel], as the app's own exit does before a cancel. Debug builds only. */
    internal suspend fun markOwnCancelForTest(id: String) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; markOwnCancel(id) }


    /**
     * 19 Nov 2026 (audit batch 2): when the app's own manual exit last took a contract in hand ([handsOff]; "EXCHANGE:SYMBOL" -> wall ms):
     * a stop on it that ended within [HANDS_OFF_OWN_MS] was taken out by that exit - the app's, never Boss's cancel in Kite. Memory only.
     */
    private val handsOffLast = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val HANDS_OFF_OWN_MS = 10 * 60_000L
    private fun handsOffRecently(exchange: String, symbol: String): Boolean =
        handsOffLast["$exchange:$symbol"]?.let { System.currentTimeMillis() - it in 0 until HANDS_OFF_OWN_MS } == true

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
                    if (st.status !in DONE) { markOwnCancel(oid); runCatching { Broker.cancel(oid) }; st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return null }
                    if (st.status == "COMPLETE") return "COMPLETE" to st
                    if (st.status !in DONE) return null
                    if (st.filled > 0) return "COMPLETE" to st
                }
                return "CANCELLED" to null
            }
            gttsKept = null
            markOwnCancel("$GTT$gid")          // (19 Nov 2026, audit batch 2: the app's own delete, never taken as Boss's)
            runCatching { Broker.deleteGtt(gid) }
            val after = runCatching { Broker.gtts().firstOrNull { it.id == gid } }.getOrElse { return null }
            if (after?.status?.lowercase() == "triggered") return stopOut(p)
            return if (after == null || after.status.lowercase() != "active") "CANCELLED" to null else null
        }
        val id = p.slId ?: return "CANCELLED" to null
        val c0 = System.currentTimeMillis()
        markOwnCancel(id)                  // (19 Nov 2026, audit batch 2: the app's own cancel, never taken as Boss's)
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
        // 18 Nov 2026 (audit A2): never a second exit on a contract while one whose reply was lost may still be selling it (its hold,
        // [LOST_EXIT_HOLD_MS]; past it the books below are read again before anything is sold).
        if (lostExitHolds(p0.contract)) return listOf(p0)
        // 19 Nov 2026 (second review): a hold that ended before the book was searched (only the live lane looked): searched once now,
        // before anything is sold. Found: held again, and the next regular look books it as the exit. Not found: said, and the exit goes.
        unsearchedLost[p0.contract]?.let { missed ->
            // (At most every few seconds, as the look searches: a book that cannot be read leaves it to the next try.)
            val ms = nowMs()
            if (ms - missed.askedMs in 0 until LOST_EXIT_ASK_MS) return listOf(p0)
            val asked = missed.copy(askedMs = ms)
            if (!unsearchedLost.replace(p0.contract, missed, asked)) return listOf(p0)
            val found = try { Broker.findRecent(missed.order, missed.known) } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { return listOf(p0) }
            // (Taken meanwhile by another look: that one decides; nothing is sold here this time.)
            if (!unsearchedLost.remove(p0.contract, asked)) return listOf(p0)
            if (found != null) { lostExits[p0.contract] = asked.copy(sinceMs = ms, foundId = found); return listOf(p0) }
            lostNotFound(missed)
        }
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
                    // (Should the sale not go this look after all, Boss is told so plainly - each minute while it is due.)
                    fun notNow(how: String): List<Pos> = listOf(p).also { sayBlocked(p, "after_cancel", again = true, "${p.label}: its live exit ($why) " +
                        "on ${p.symbol} did not go yet after your order was cancelled ($how); the app tries again on each look. Close ${p.symbol} in Kite " +
                        "if it must go now.") }
                    orders0 = runCatching { Broker.orders() }.getOrNull() ?: return notNow("Zerodha's orders could not be read")
                    held0 = runCatching { heldNow(p) }.getOrNull() ?: return notNow("Zerodha's positions could not be read")
                    planned = ExitQty.sendable(held0.coerceAtLeast(0), mineOf(orders0, stopIds).sumOf { ExitQty.remaining(it.qty, it.filled, it.pending) }, want, p.lot)
                    if (planned <= 0) return notNow("other orders still cover the lot, or nothing is held")
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
            val held = runCatching { ExitCoordinator.held(cur.exchange, cur.symbol, cur.product, "arm ${p.label}", book, forceLag = lagForced()) * cur.side }.getOrNull()
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
        // The rest still open keeps a stop for all of it: rested again when out, grown back when a part sale only part filled.
        val out = closed + done + listOfNotNull(rest?.let { back(it) })
        // 18 Nov 2026 (audit A2): Zerodha's net on the contract after the sale (after the rest's stop is back): the other way of the
        // trade, sold by the arms, is said loudly.
        runCatching {
            val rows = Broker.orders()
            val onIt = Broker.positionBook().net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }
            checkNet(p.label, p.exchange, p.symbol, p.product, p.side, onIt.sumOf { it.qty }, now().toLocalDate(), rows,
                { armsCarried(p)?.let { minOf(onIt.sumOf { it.overnight } * p.side, it) } })
        }
        return out
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
    private suspend fun lostExitLook(contract: String, owner: Pos?, ltp: Double?, stream: Boolean): List<Pos>? {
        val keep = listOfNotNull(owner)
        // 19 Nov 2026 (second review): the hold ends on time on every lane, with no read ([lostExitHolds]); past it, null. Ended before
        // the book was ever searched (only the live lane looked meanwhile), it is searched once first - here on a regular look, else by
        // the trade's own exit before it sells ([exitLive]) - and found, it is the exit.
        if (!lostExitHolds(contract)) {
            val missed = unsearchedLost[contract] ?: return null
            if (stream) return null
            val found = try { Broker.findRecent(missed.order, missed.known) } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { return null }
            if (!unsearchedLost.remove(contract, missed)) return null
            if (found == null) { lostNotFound(missed); return null }
            return lostFound(missed, found, owner, ltp, keep)
        }
        val lost = lostExits[contract] ?: return null
        val ms = nowMs()
        // (Never on the live lane's look, which comes every tick and must not wait on the order book: the minute lane and the 15-second
        // check search for it; the stream keeps the hold meanwhile, which still ends on time.)
        if (stream) return keep
        // (Found already by its trade's own exit ([exitLive]): booked now, never searched for again.)
        lost.foundId?.let { id ->
            if (owner?.ref != lost.ref || !lostExits.remove(contract, lost)) return keep
            return lostFound(lost, id, owner, ltp, keep)
        }
        // (Searched at most every few seconds. A book that cannot be read keeps the hold, which ends on time.)
        if (ms - lost.askedMs in 0 until LOST_EXIT_ASK_MS) return keep
        val asked = lost.copy(askedMs = ms)
        lostExits[contract] = asked
        // (While the search runs the hold never ends under it: one found as the hold ends is still the exit, never "not found".)
        searchingLost += contract
        var failed = false
        val found = try { Broker.findRecent(lost.order, lost.known) } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { failed = true; null } finally { searchingLost -= contract }
        // (A search that could not read the book is no search: past the hold it is searched once more before anything is sold.)
        if (failed) { lostExits.replace(contract, asked, lost); return keep }
        if (found == null) return keep
        lostExits.remove(contract)
        unsearchedLost.remove(contract)
        return lostFound(lost, found, owner, ltp, keep)
    }

    /** 19 Nov 2026: a lost exit [found] in Zerodha's book: it is the exit of its trade ([owner] when it is that trade). */
    private suspend fun lostFound(lost: LostExit, found: String, owner: Pos?, ltp: Double?, keep: List<Pos>): List<Pos> {
        runCatching { Diag.record("armlive", "${lost.order.tradingSymbol}: the lost exit found in Zerodha's book ($found): it is the exit") }
        val p = owner?.takeIf { it.ref == lost.ref } ?: return keep
        return bookExit(p, p, emptyList(), found, lost.why, ltp) { x -> stopBack(x, ltp) }
    }

    /** 19 Nov 2026: a lost exit not found in Zerodha's book within its hold, said to Boss. */
    private fun lostNotFound(lost: LostExit) {
        runCatching { Diag.record("armlive", "${lost.order.tradingSymbol}: the lost exit was not found in ${LOST_EXIT_HOLD_MS / 1000} s; the exit may go again") }
        com.optionslab.app.work.Alerts.error("Arms live: the lost exit of ${lost.order.tradingSymbol} was not found in Zerodha's order book in " +
            "${LOST_EXIT_HOLD_MS / 1000} s. The app reads Zerodha again and sells only what is still held; check Kite's order book.", WHO)
    }

    /**
     * 18 Nov 2026 (audit A2, review): [x]'s stop put back for what is still open after a lost exit found later did not sell it all, as
     * [exitLive]'s own put-back does: rested again when out, grown back (never beyond what Zerodha holds) when a part sale shrank it.
     */
    private suspend fun stopBack(x: Pos, ltp: Double?): Pos {
        if (x.stopOrderId == null) return if (ltp != null) restStop(x, x.stopTrigger, ltp) else x
        if ((x.stopQty ?: x.qty) >= x.qty || x.gttId != null) return x
        val so = x.slId?.let { id -> runCatching { Broker.orders().firstOrNull { it.id == id } }.getOrNull() } ?: return x
        val held = runCatching { heldNow(x) }.getOrNull() ?: return x
        val to = minOf(x.qty, held.coerceAtLeast(0) / x.lot.coerceAtLeast(1) * x.lot.coerceAtLeast(1))
        return if (to > (x.stopQty ?: x.qty)) shrinkStop(x, so, to, ltp) ?: x else x
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
        // (The orders a fired GTT put in carry no tag either - an arm's, a protection's, Liquidity's - and are never taken for Boss's: a
        // GTT list that cannot be read leaves nothing cancellable.)
        val gttOrders = if (blockers.none { it.type == "LIMIT" && it.tag.isBlank() }) emptySet()
            else runCatching { Broker.gtts().flatMap { it.orderIds }.toSet() }.getOrNull()
        val mine = known().toSet() + (gttOrders ?: blockers.map { it.id })
        fun words(rows: List<Broker.OrderRow>) = rows.joinToString { "${it.type} ${it.side.lowercase()} ${ExitQty.remaining(it.qty, it.filled, it.pending)}" +
            (if (it.type == "LIMIT") " @ %.2f".format(Locale.ENGLISH, it.price) else "") + " (${it.tag.ifBlank { "yours" }}, ${it.id})" }
        val ids = LiveMirror.blockersToCancel(held, blockers.sortedByDescending { it.placedAt }.map { o ->
            LiveMirror.Blocker(o.id, ExitQty.remaining(o.qty, o.filled, o.pending), cancellable = cancellable(o, mine)) }, want, p.lot)
        // 19 Nov 2026 (second review, as the ORB book does): never while Zerodha holds more of the contract than the app's live books'
        // confirmed trades on it (this book's and the ORB arms' / Liquidity's; an entry still searched for, with no order id, never
        // counts) - his order may be for his own lots. Said instead, each minute while the exit is due.
        if (ids.isNotEmpty()) {
            val apps = appConfirmedQty(p)
            if (apps == null || held > apps) {
                sayBlocked(p, "boss_lots", again = true, "${p.label}: its live exit ($why) on ${p.symbol} is due but cannot sell: your sell " +
                    "order(s) (${words(blockers.filter { it.tag != LiveMirror.TAG })}) cover the lot, and Zerodha holds $held where the app's live " +
                    "trades hold ${apps ?: "an unknown quantity"}, so they may be for your own lots and are not cancelled. Cancel them in Kite " +
                    "or close ${p.symbol} yourself.")
                return Freed.NONE
            }
        }
        if (ids.isEmpty()) {
            val others = blockers.filter { it.tag != LiveMirror.TAG }
            if (others.isNotEmpty()) sayBlocked(p, "exit", again = true, "${p.label}: its live exit ($why) on ${p.symbol} is due but cannot sell: " +
                "other orders at Zerodha already cover the lot (${words(others)}). Cancel them in Kite or close ${p.symbol} yourself.")
            return Freed.NONE
        }
        return ExitCoordinator.turn(p.exchange, p.symbol, "arm ${p.label}") { cancelBlockers(p, ids, why, mine) }
    }

    /**
     * 19 Nov 2026 (second review): what the app's live books hold of [p]'s contract, confirmed (an entry still searched for, with no order
     * id, left out): this book's open trades on it ([p] as it is now) and the ORB arms' / Liquidity's ([OrbArms.liveQtyHint], NFO
     * MIS buys). Null when this book cannot be read. Memory only.
     */
    private suspend fun appConfirmedQty(p: Pos): Int? {
        val mine = runCatching { lock.withLock { book().positions.filter { it.open && !it.shadow && it.contract == p.contract && it.ref != p.ref &&
            it.side == p.side && !(it.unconfirmed && it.entryOrderId == null) }.sumOf { it.qty } } }.getOrNull() ?: return null
        val orb = if (p.exchange == "NFO" && p.product == "MIS" && p.side > 0) OrbArms.liveQtyHint[p.symbol] ?: 0 else 0
        return mine + p.qty + orb
    }

    /** 18 Nov 2026 (audit A1, review): what the app will do when the exit is due, by the orders that take the stop's room ([foreign]). */
    private fun coveredWords(foreign: List<Broker.OrderRow>): String =
        if (foreign.any { it.type == "LIMIT" && !it.tag.startsWith("ira", ignoreCase = true) && it.tag !in SquareOffExit.TAGS })
            "when the exit is due it cancels your LIMIT first and sells."
        else "it cannot cancel ${if (foreign.size > 1) "those orders" else "that order"} for you: cancel ${if (foreign.size > 1) "them" else "it"} " +
            "in Kite, or the exit cannot sell when it is due."

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
            // (Polled unless Zerodha definitely refused it: a reply lost or timed out may still have cancelled it.)
            val taken = runCatching { Broker.cancel(o.id, o.variety) }.exceptionOrNull().let { e -> e == null || (e is Exception && !Broker.definite(e)) }
            // 19 Nov 2026 (second review): Zerodha's book shows a cancel it took a moment after its reply - read again for up to ~1 s.
            var st = runCatching { Broker.orderState(o.id) }.getOrNull()
            var tries = 0
            while (taken && (st == null || st.status !in DONE) && tries++ < 5) {
                kotlinx.coroutines.delay(200)
                st = runCatching { Broker.orderState(o.id) }.getOrNull() ?: st
            }
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
                " covered the lot, so ${if (done.size > 1) "they were" else "it was"} cancelled and the arm sells now (its books read again first).", WHO)
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
    val recoverHint: Boolean get() = recoverDue || unreadable || coldPending

    /** Whether a look has work now (memory only): a live trade held, a shadow trade open, a damaged or unread book. */
    // (19 Nov 2026, third review: and the start's sweep, so a login later in the day still reaches it on the minute lanes; memory only.)
    val lookDue: Boolean get() = holdingHint || shadowHint || recoverHint || coldDue

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
        val sweep = coldDue && !stream && runCatching { Broker.loggedIn }.getOrDefault(false)
        if (!stream && !sweep) coldPending = false
        if (!holdingHint && !shadowHint && !recoverDue && !sweep) return
        var lookFailed: Exception? = null
        if (holdingHint || recoverDue) {
            if (opLock.tryLock()) {
                try {
                    if (recoverDue && Broker.loggedIn) runCatching { recover() }
                    if (holdingHint) look(stream)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { lookFailed = e        // (the sweep below still runs; the failure is passed on after it)
                } finally { opLock.unlock() }
            } else if (!sweep) return                 // a look already running does this one's work
        }
        // 19 Nov 2026 (third review; final review): the start's sweep after the open trades were looked at (their stops first), under
        // its own lock - never [opLock], so its reads never make the live lane or the 15-second check skip a look - never on the live lane.
        // (Never while a damaged book's recovery is due: [recover] takes those lots back first, under [opLock], and the sweep after it.
        // Its time bound still runs meanwhile.)
        if (sweep && recoverDue) coldPendingBound()
        if (sweep && !recoverDue && sweepLock.tryLock()) {
            try {
                coldPendingBound()
                if (!recoverDue) runCatching {
                    val r = sweepUnowned()
                    if (r == Swept.DONE || (r == Swept.UNDECIDED && coldGiveUp())) { coldDue = false; coldPending = false; coldDecided.clear() }
                }
            } finally { sweepLock.unlock() }
        }
        lookFailed?.let { throw it }
        // The shadow trades after, under their own lock (review of the final fixes): their quote and candle reads never hold up
        // the live trades' looks (the live lane and the 15-second check skip a look while [opLock] is held).
        if (!stream && shadowHint && shadowLock.tryLock()) {
            try { runCatching { lookShadow() } } finally { shadowLock.unlock() }
        }
    }

    /** 19 Nov 2026 (final review): one start sweep at a time ([sweepUnowned]; never the live trades' [opLock]). */
    private val sweepLock = Mutex()

    /** TEST ONLY: the start's sweep is running now (19 Nov 2026, final review). */
    internal val sweepingForTest: Boolean get() = sweepLock.isLocked

    /**
     * 19 Nov 2026 (final review): the sweep keeps the tick lanes' [recoverHint] on ([coldPending]) for at most [COLD_PENDING_MAX_MS]
     * after it was first due; then Boss is told and only the minute lanes keep trying ([lookDue]: memory only).
     */
    private fun coldPendingBound() {
        if (!coldDue) return
        val ms = runCatching { nowMs() }.getOrDefault(System.currentTimeMillis())
        if (coldPendingSince == 0L) coldPendingSince = ms
        if (ms - coldPendingSince < COLD_PENDING_MAX_MS) { coldPending = true; return }
        if (coldPending || !coldPendingSaid) {
            coldPendingSaid = true
            com.optionslab.app.work.Alerts.error("Arms live: after the app restarted, today's live buys of the arms could not all be checked against " +
                "Zerodha for ${COLD_PENDING_MAX_MS / 60_000} minutes. It keeps trying each minute; check open positions in Trade.", WHO)
        }
        coldPending = false
    }
    @Volatile private var coldPendingSince = 0L
    @Volatile private var coldPendingSaid = false
    private const val COLD_PENDING_MAX_MS = 10 * 60_000L

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
    // 19 Nov 2026 (audit batch 2): a live trade Boss detached from its paper trade (his paper reset or hand close: [detach]; only one with
    // its own 15:15 square-off) follows no paper book any more: a missing or emptied paper trade, and the paper's own "closed elsewhere"
    // that follows, sell nothing - its own stop, rule stop and square-off remain. An exit of the arm's own rules told afterwards still sells it.
    private fun paperSide(p: Pos, legs: List<PaperLeg>?, fresh: Boolean = true): Triple<Pos, Int, String?> {
        if (p.detached || detachNotes.containsKey(p.ref)) {
            val q = if (p.detached) p else p.copy(detached = true)
            val ruleExit = closedWhy(p.ref)?.takeIf { !LiveMirror.paperEndKeepsLive(it) }
            return Triple(q, if (ruleExit != null) q.qty else 0, ruleExit)
        }
        val leg = legs?.firstOrNull { it.ref == p.ref }
        var q = p
        if (leg != null && leg.qty > 0 && !q.seen) q = q.copy(seen = true)
        val gone = closedRefs.containsKey(p.ref) || (fresh && LiveMirror.paperGone(q.seen, legs?.let { leg != null })) || leg?.qty == 0
        val sell = if (gone) q.qty else leg?.let { LiveMirror.toSell(q.qty, q.entryQty, q.paperQty, it.qty, q.lot) } ?: 0
        val why = closedWhy(p.ref) ?: if (gone) "paper_exit" else if (sell > 0) "paper_part" else null
        return Triple(q, sell, why)
    }

    /** 19 Nov 2026 (audit batch 2): the live trades whose detaching ([detach]) Boss was told of (memory only; once each). */
    private val detachSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 19 Nov 2026 (audit batch 2): Boss told, once for the trade, that [p]'s live copy stays open on its own stop and exits. */
    private fun saidDetached(p: Pos) {
        if (!detachSaid.add(p.ref)) return
        if (detachSaid.size > 500) { detachSaid.clear(); detachSaid.add(p.ref) }
        val why = detachNotes[p.ref]?.second ?: LiveMirror.PAPER_CLOSED_ELSEWHERE
        val text = "${p.label}: its paper trade ended without its own exit ($why), so its live ${p.symbol} is NOT sold: it stays open at Zerodha " +
            "with its own stop (" + (p.stopTrigger?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none on the premium") + "), its rule stop and the " +
            "15:15 square-off. Close it in Trade or Kite if you want it out."
        com.optionslab.app.work.Alerts.error(text, WHO)
        note(p.key, text)
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
    private data class LostExit(val ref: String, val why: String, val order: Kite.Order, val known: Set<String>, val sinceMs: Long, val askedMs: Long = 0L,
                                // 19 Nov 2026: the order found in Zerodha's book by an exit's own search, booked by the next regular look.
                                val foundId: String? = null)
    private val lostExits = java.util.concurrent.ConcurrentHashMap<String, LostExit>()
    // 19 Nov 2026 (second review): 10 s - the exit's own retries already searched for it for ~4-6 s before it was held.
    private const val LOST_EXIT_HOLD_MS = 10_000L
    /** 18 Nov 2026 (audit A2): a lost exit is searched for at most this often (the live lane comes every tick). */
    private const val LOST_EXIT_ASK_MS = 3_000L

    /**
     * 18 Nov 2026 (audit A2): a lost exit on [contract] still within its hold. 19 Nov 2026 (second review): past it, it is forgotten here
     * on any lane, memory only (no read), and Boss is told it was not found - the next exit reads Zerodha's books again before it sells.
     */
    private fun lostExitHolds(contract: String): Boolean {
        val lost = lostExits[contract] ?: return false
        if (contract in searchingLost || lost.foundId != null) return true
        val ms = runCatching { nowMs() }.getOrDefault(System.currentTimeMillis())
        if (ms - lost.sinceMs < LOST_EXIT_HOLD_MS) return true
        // (Searched and not found: said. Never searched: kept apart, searched once before anything is sold or rested on the contract.)
        if (lostExits.remove(contract, lost)) { if (lost.askedMs > 0) lostNotFound(lost) else unsearchedLost[contract] = lost }
        return false
    }

    /** 19 Nov 2026 (second review): lost exits whose hold ended before Zerodha's book was searched for them (memory). */
    private val unsearchedLost = java.util.concurrent.ConcurrentHashMap<String, LostExit>()
    /** 19 Nov 2026: the contracts whose lost exit is being searched for right now. */
    private val searchingLost: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 18 Nov 2026 (audit A2): the contracts on which Boss was told Zerodha holds the other way of the arms' trades ("contract|day"):
     * once until it is no longer so. Memory only.
     */
    private val negSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 19 Nov 2026 (second review): what this book's trades on [p]'s contract carried into today the trades' way (open, or closed today):
     * only that counts as bought in [checkNet] - never Boss's own carried lot. Memory only.
     */
    private suspend fun armsCarried(p: Pos): Int? = try {
        val today = now().toLocalDate()
        lock.withLock { book().positions.filter { !it.shadow && it.contract == p.contract && it.side == p.side && it.day.isBefore(today) &&
            (it.open || it.exitTime?.toLocalDate() == today) }.sumOf { it.qty } }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    /**
     * 18 Nov 2026 (audit A2): Zerodha's net quantity [net] (signed) on a contract an arm traded today the [side] way: the other way of it
     * (a long's net below 0) is said loudly when this path's own orders today ([orders], [LiveMirror.TAG]) sold more than they bought -
     * two exits of one lot filled, a naked short - never Boss's own short in Kite. Once a day while it lasts.
     */
    private suspend fun checkNet(label: String, exchange: String, symbol: String, product: String, side: Int, net: Int, day: LocalDate,
                         orders: Collection<Broker.OrderRow>, carriedNow: suspend () -> Int?) {
        val key = "$exchange|$symbol|$product|$day"
        if (net * side >= 0) { negSaid.remove(key); return }
        // (Read only now, the net the other way - rare: never on every tick. Not known: no alarm.)
        val carried = carriedNow() ?: return
        val mine = orders.filter { it.tag == LiveMirror.TAG && it.symbol == symbol && it.exchange == exchange && it.product == product && it.filled > 0 }
        val (out, inn) = (if (side > 0) "SELL" to "BUY" else "BUY" to "SELL")
        // (19 Nov 2026, second review: what was carried from an earlier day counts as bought - a carried lot the arm sells today.)
        if (!LiveMirror.armsSoldBeyond(mine.filter { it.side == out }.sumOf { it.filled }, mine.filter { it.side == inn }.sumOf { it.filled }, carried)) return
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

    /** TEST ONLY: how many looks of the live trades ran (19 Nov 2026, final review: a look is never skipped for the start's sweep). */
    internal val looksForTest = java.util.concurrent.atomic.AtomicInteger(0)

    private suspend fun look(stream: Boolean) {
        looksForTest.incrementAndGet()
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
                // 19 Nov 2026 (audit batch 2): a stop seen resting at Zerodha ends its re-rest back-off; a later refusal is a new one.
                if ((so != null && so.working) || (gttsFresh && g?.status?.lowercase() == "active")) { restBackoff.remove(p.ref); restRefusedSaid.remove(p.ref) }
                if (so != null && so.status in setOf("REJECTED", "CANCELLED")) {
                    // 19 Nov 2026 (audit batch 2, Boss 16 Nov: "All the stops and profit locks should be on zerodha"; a stop Boss cancelled
                    // himself is respected): told apart as the ORB arms do - ended by the exchange, its risk system or a rejection
                    // ([com.optionslab.ira.ManualCoverRule.endedByExchange]), or by the app itself (its own cancel, Stop for today, a
                    // square-off's sale on the contract or within its gap): put back at Zerodha as before. Cancelled by Boss (in Kite):
                    // never put back - the app watches the level itself and sells there ([Pos.stopByYou], persisted).
                    val opStop = if (p.exchange == "MCX") mcxStopped else stopped
                    val appsOwn = ownCancel(so.id) || opStop || SquareOffExit.inGap(p.exchange, p.symbol, p.product) || handsOffRecently(p.exchange, p.symbol) ||
                        orders.values.any { it.symbol == p.symbol && it.exchange == p.exchange && it.working && it.tag in SquareOffExit.TAGS }
                    // (B2-B, Boss's 16 Nov rule: a cancel with no mark of the app's own - kept in the book across a restart - made while the market
                    // takes orders (MCX by its own hours) is Boss's when Zerodha's words are blank or unrecognised: never put back, the app sells at
                    // the level itself. Exchange / RMS words are Zerodha's: put back at once. One outside market hours (the day's end) is Zerodha's
                    // too: put back after a back-off.)
                    val byExchange = com.optionslab.ira.ManualCoverRule.endedByExchange(so.status, so.message)
                    // (Judged at Zerodha's own time of the cancel when it gives one - never by how late this look came - else now; unknown: put back.)
                    val endedAt = placedTime(so.updatedAt)
                    val inHours = runCatching {
                        if (p.exchange == "MCX") McxMarket.acceptsOrders()
                        else if (endedAt != null) Market.testOrdersAnyTime || Market.isOpenAt(endedAt.toLocalDate(), endedAt.hour * 60 + endedAt.minute)
                        else Market.acceptsOrders()
                    }.getOrDefault(false)
                    val byYou = !appsOwn && !byExchange && inHours
                    if (so.status == "CANCELLED" && !appsOwn && !byExchange && !inHours) backOff(p)
                    val level = p.stopTrigger?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "its level"
                    // (Said once for each stop that ended; a refusal's back-off and its words: [refusedStop].)
                    if (so.status == "REJECTED") refusedStop(p, p.stopTrigger, so.message.ifBlank { "no reason given" })
                    else if (endedSaid.put(p.ref, so.id) != so.id) com.optionslab.app.work.Alerts.error("${p.label}: the live stop on ${p.symbol} at " +
                        "Zerodha was cancelled (${so.message.ifBlank { "no reason given" }}). " + (if (byYou) "It looks cancelled by you (in Kite), so " +
                        "the app does not put it back: it watches $level itself and sells there. Set a stop at Zerodha yourself if you want one there."
                        else "The app watches $level itself and rests it again at Zerodha."), WHO)
                    p = p.copy(stopOrderId = null, stopQty = null, stopByYou = p.stopByYou || byYou)
                }
                if (p.slId != null && so == null && p.day.isBefore(t.toLocalDate())) p = p.copy(stopOrderId = null, stopQty = null)
                if (p.gttId != null && gtts != null && gttsFresh && (g == null || g.status.lowercase() in setOf("deleted", "cancelled", "expired", "rejected", "disabled"))) {
                    // (19 Nov 2026, audit batch 2: a GTT Zerodha rejected or disabled is a refusal too: rested again after its back-off.)
                    if (g != null && g.status.lowercase() in setOf("rejected", "disabled")) refusedStop(p, p.stopTrigger, "the GTT was ${g.status.lowercase()} at Zerodha")
                    // 19 Nov 2026 (audit batch 2, coordinator's decision): a GTT deleted by Boss himself in Kite is respected
                    // like a cancelled SL - never put back, the app watches the level and sells there ([Pos.stopByYou]). Deleted by the app
                    // (its own exit taking it out, Stop for today, a square-off) is the app's; cancelled, expired, rejected, disabled or gone
                    // from the list is Zerodha's or unknown: put back as before.
                    val gid = p.gttId
                    val byYou = g != null && g.status.lowercase() == "deleted" && gid != null &&
                        !(ownCancel("$GTT$gid") || (if (p.exchange == "MCX") mcxStopped else stopped) || SquareOffExit.inGap(p.exchange, p.symbol, p.product) ||
                            handsOffRecently(p.exchange, p.symbol) ||
                            orders.values.any { it.symbol == p.symbol && it.exchange == p.exchange && it.working && it.tag in SquareOffExit.TAGS })
                    if (byYou && endedSaid.put(p.ref, "$GTT$gid") != "$GTT$gid") com.optionslab.app.work.Alerts.error("${p.label}: the live GTT stop on " +
                        "${p.symbol} at Zerodha was deleted. It looks deleted by you (in Kite), so the app does not put it back: it watches " +
                        (p.stopTrigger?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "its level") + " itself and sells there. Set a stop at Zerodha " +
                        "yourself if you want one there.", WHO)
                    p = p.copy(stopOrderId = null, stopQty = null, stopByYou = p.stopByYou || byYou)
                }
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
            checkNet(first.label, first.exchange, first.symbol, first.product, first.side, held * first.side, t.toLocalDate(), orders.values,
                { armsCarried(first)?.let { minOf(net.filter { it.symbol == first.symbol && it.exchange == first.exchange && it.product == first.product }
                    .sumOf { it.overnight } * first.side, it) } })
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
            val entryFills = onIt.filter { it.side != exitSide && it.tag != LiveMirror.TAG && it.tag != ORB_TAG && it.id !in ownEntries }
            val unbookedBuys = entryFills.sumOf { it.filled } + carried.coerceAtLeast(0)
            // 19 Nov 2026 (second review): another part of the app's own round trip (Pine, a strategy, the Trade screen, a protection: its
            // tag) is netted per tag - its buys less its own sales, never below 0 - as Boss's own buys are netted against his sales, so
            // his real sale in Kite after such a round trip still confirms the short read.
            val appTagged = onIt.filter { it.tag.isNotBlank() && it.tag != LiveMirror.TAG && it.tag != ORB_TAG && it.tag !in SquareOffExit.TAGS }
            val appTags = appTagged.map { it.tag }.toSet()
            val appNet = appTagged.groupBy { it.tag }.values.sumOf { l ->
                (l.filter { it.side != exitSide && it.id !in ownEntries }.sumOf { it.filled } - l.filter { it.side == exitSide }.sumOf { it.filled }).coerceAtLeast(0) }
            val outsideBuys = entryFills.filter { it.tag !in appTags }.sumOf { it.filled } + appNet + carried.coerceAtLeast(0)
            val converted = net.filter { it.symbol == first.symbol && it.exchange == first.exchange && it.product != first.product }
                .sumOf { it.qty } * first.side - open.filter { it.symbol == first.symbol && it.exchange == first.exchange && it.product != first.product &&
                it.side == first.side }.sumOf { it.qty } >= excess        // (beyond what these arms hold there themselves)
            val sold = booked != null && LiveMirror.outsideSaleCovers(outsideSells, outsideBuys, booked.filter { it.outside }.sumOf { it.qty }, excess)
            val unbooked = booked != null && (converted ||
                LiveMirror.unbookedSale(onIt.filter { it.side == exitSide }.sumOf { it.filled }, unbookedBuys, booked.sumOf { it.qty }))
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
        if (!stream) runCatching { lock.withLock { book().positions.filter { it.open }.map { it.ref }.toSet() } }.getOrNull()?.let { noStopSince.keys.retainAll(it) }

        // 19 Nov 2026 (third review): a lost exit found in Zerodha's book whose trade is no longer among the open ones (closed another way)
        // never holds the contract back for the others: forgotten, with a diary line.
        if (!stream && lostExits.values.any { it.foundId != null }) {
            val stillOpen = runCatching { lock.withLock { book().positions.filter { it.open && !it.shadow }.map { it.contract to it.ref }.toSet() } }.getOrNull()
            if (stillOpen != null) for ((c, l) in lostExits.entries.toList()) if (l.foundId != null && (c to l.ref) !in stillOpen && lostExits.remove(c, l))
                runCatching { Diag.record("armlive", "${l.order.tradingSymbol}: the lost exit ${l.foundId} found for a trade no longer open: forgotten") }
        }

        // 3. Each trade's decision, newest first on each contract, against the exits working on it (kept up to date as they change).
        val working = HashMap<String, Int>()     // order id -> remaining, the exits working on these contracts
        fun refill() { working.clear(); for (o in orders.values) if (o.working) working[o.id] = ExitQty.remaining(o.qty, o.filled, o.pending) }
        refill()
        for ((contract, group) in open.groupBy { it.contract }) {
            // 18 Nov 2026 (audit A2): an exit of this contract whose reply was lost is looked for first; while it may still be selling,
            // nothing more is sold, rested or moved on the contract (each trade watched, its stop where it rests); found, it is the exit.
            // 19 Nov 2026 (second review): only the trade whose exit was lost waits; the others on the contract are decided as ever (a sale
            // or a stop of theirs on it waits for the hold too: [exitLive], [restStop]).
            var waiting: String? = null
            if (lostExits.containsKey(contract) || unsearchedLost.containsKey(contract)) {
                val lost = lostExits[contract] ?: unsearchedLost[contract]
                val owner = group.firstOrNull { it.ref == lost?.ref }
                val ownerNow = owner?.let { cur(it) }?.takeIf { !it.unconfirmed }
                val r = runCatching { lostExitLook(contract, ownerNow, ownerNow?.let { ltpOf(it) }, stream) }.getOrElse { listOfNotNull(ownerNow) }
                if (r != null) {
                    if (owner != null && ownerNow != null && r != listOf(ownerNow)) { set(owner, r); dirty += contract }
                    waiting = lost?.ref
                }
            }
            for (p0 in group.sortedByDescending { it.entryTime }) {
                if (p0.ref == waiting) continue
                val p1 = cur(p0)?.takeIf { !it.unconfirmed } ?: continue
                if (contract in dirty) { reread(); refill() }
                try {
                    val spec = LiveMirror.spec(p1.key)
                    val (pPaper, paperSell, paperWhy) = paperSide(p1, legsOf(spec, stream), fresh = !stream)
                    if (pPaper.detached && !p1.detached && paperSell <= 0) saidDetached(pPaper)
                    // 15 Nov: a stop-rule trade's best price raised to the price now (kept, so its stop never drops after a restart).
                    val p = withBest(pPaper, bestPx(pPaper, q["${pPaper.exchange}:${pPaper.symbol}"]), highSince(pPaper, fresh = !stream))
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
                    // 19 Nov 2026 (second review): a lot left with no stop resting at Zerodha because its positions do not show the fill
                    // (a stop is never rested beyond what is held) is said loudly after a minute, and each minute while it lasts; the app
                    // watches the level meanwhile. Memory only.
                    val unshownHeld = heldOf(p)
                    // (This trade's share, oldest first: the trades on the contract entered no later than it, with it.)
                    val oldest = group.mapNotNull { cur(it)?.takeIf { c -> !c.unconfirmed } }.sortedWith(compareBy<Pos>({ it.entryTime }, { it.ref }))
                    val through = oldest.take(oldest.indexOfFirst { it.ref == p.ref }.let { if (it < 0) oldest.size else it + 1 }).sumOf { it.qty }
                    if (p.stopOrderId == null && p.stopTrigger != null && unshownHeld < through) {
                        val since = noStopSince.putIfAbsent(p.ref, ms) ?: ms
                        if (ms - since >= NO_STOP_SAY_MS) sayBlocked(p, "no_stop", again = true, "${p.label}: its live ${p.symbol} has had no stop " +
                            "resting at Zerodha for ${(ms - since) / 1000} s because Zerodha's positions do not show its fill (they show " +
                            "$unshownHeld of $through). The app watches %.2f itself; check the position in Kite.".format(Locale.ENGLISH, p.stopTrigger))
                    } else noStopSince.remove(p.ref)
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
                                // (Only when those orders are what take the room: never for a sibling arm's stop alone.)
                                val l = p.lot.coerceAtLeast(1)
                                val roomWithout = (heldMine - (others - foreign.sumOf { working[it.id] ?: 0 })).coerceAtLeast(0) / l * l
                                if (foreign.isNotEmpty() && roomWithout >= minOf(stopQty ?: p.qty, heldMine.coerceAtLeast(0)) / l * l) sayBlocked(p, "covered", again = false, "${p.label}: its live ${stopWords(p)} on ${p.symbol} is cut to " +
                                    "${d.qty} of ${stopQty ?: p.qty} at Zerodha because another sell order covers the lot (${foreign.joinToString { "${it.type} ${it.id}" }}). " +
                                    (p.stopTrigger?.let { "The app watches %.2f itself; ".format(Locale.ENGLISH, it) } ?: "") + coveredWords(foreign))
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
            val i = b.positions.indexOfFirst { it == old }
            if (i < 0) continue
            b.positions.removeAt(i)
            b.positions.addAll(i, now)
            now.filter { !it.open }.forEach { c ->
                // 16 Nov (review): the rule's exits (breakeven lock, step lock) with their "failed gates, Boss's exception" label.
                line(b, "%s: %s%s %s %d @ %.2f (%s), %s after charges".format(Locale.ENGLISH, c.label, if (c.shadow) "SHADOW " else "",
                    if (c.side > 0) "sold" else "bought back", c.symbol, c.qty, c.exit ?: 0.0,
                    c.why?.let { com.optionslab.engine.orb.StepTrail.words(it) }, LiveMirror.rs(c.net ?: 0.0)))
                if (b.positions.none { it.open && it.ref == c.ref }) { closedRefs.remove(c.ref); levels.remove(c.ref); RuleHighs.forget("live:${c.ref}")
                    detachNotes.remove(c.ref); detachSaid.remove(c.ref); restBackoff.remove(c.ref); restRefusedSaid.remove(c.ref); endedSaid.remove(c.ref) }
            }
        }
        // 20 Nov 2026 (audit B5, item 5): a look that only raised rule trades' best prices (a new breakeven or step lock earned: [changed])
        // writes the book on its own writer ([saveAsync], in order), so the stream's look never waits on the vault for it; anything else
        // (a sale, a stop placed or moved) is written before the look goes on, as before.
        if (updates.isNotEmpty() && updates.all { (old, now) -> now.size == 1 && now[0] == old.copy(best = now[0].best) }) saveAsync(b) else save(b)
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
            // (20 Nov 2026: an R51 / R72 shadow trade's best also from the closed minutes' highs, as its live trade's would be.)
            val p = withBest(pPaper, bestPx(pPaper, q["${pPaper.exchange}:${pPaper.symbol}"]), if (checkedBest(pPaper)) highSince(pPaper, fresh = true) else null)
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
        testMeasured?.takeIf { leg == com.optionslab.ira.Slippage.Leg.ENTRY } ?: runCatching { com.optionslab.ira.Slippage.medianShare(FillSlippage.fills(), leg, bases, strategy = "Arms (live)") }.getOrDefault(0.0)

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
            return restStop(got.copy(stopKind = if (stop != null) kind else got.stopKind), stop ?: got.stopTrigger, ltp)
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
        // 18 Nov 2026 (audit A4): with no level in memory (after a restart), the level its intent was written with ([enterNow]).
        return restStop(got.copy(stopKind = if (stop != null) kind else got.stopKind), stop ?: got.stopTrigger, ltp = fill).also {
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
            (runCatching { ExitCoordinator.held(p.exchange, p.symbol, p.product, WHO, orders, first = pb, forceLag = lagForced()) }.getOrNull() ?: return null) * p.side)
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
        return listOf(restStop(got.copy(stopKind = if (stop != null) kind else got.stopKind), stop ?: got.stopTrigger, ltp))
    }

    /**
     * 19 Nov 2026 (third review: the entry's intent is written on the book's own writer, so a death within ~1.5 s after its order went
     * could leave a filled lot of this path with no row and no stop). Today's filled buys tagged [LiveMirror.TAG] that no row of the book
     * names as its entry are taken back from Zerodha - never more than it holds beyond what the book's own trades and the ORB arms' /
     * Liquidity's explain, nor than was bought today by orders of no other owner ([LiveMirror.boughtTodayCap], [LiveMirror.unexplainedQty],
     * as [adoptHeld]). Each gets its arm (its owner label, else its one open paper trade on the contract, [rebuiltLeg]) and its stop (the
     * arm's level, else the safety stop); an arm that cannot be told is still taken back with a safety stop - never left unwatched - and
     * Boss is told loudly either way. A contract with an entry still settling on disk is left to that entry ([settle]). True once
     * Zerodha's books were read. Regular looks only.
     */
    /** 19 Nov 2026: what one start sweep did: all decided, some left after reading Zerodha's books, or its books not read (tried again). */
    private enum class Swept { DONE, UNDECIDED, NOT_READ }

    /**
     * 19 Nov 2026 (audit batch 2): the contracts this start's sweep already decided ([sweepUnowned]), each with the buys it decided on
     * ("id:filled", [buysSeen]) - a new or further filled buy there is decided again. Dropped when the sweep ends. Memory only.
     */
    private val coldDecided = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun buysSeen(buys: List<Broker.OrderRow>): String = buys.sortedBy { it.id }.joinToString(",") { "${it.id}:${it.filled}" }

    private suspend fun sweepUnowned(): Swept {
        val orders = runCatching { Broker.orders() }.getOrNull() ?: return Swept.NOT_READ
        val pb = runCatching { Broker.positionBook() }.getOrNull() ?: return Swept.NOT_READ
        // (After the reads, as [adoptHeld]: an entry that filled between them could be taken for an unowned one. Tried again.)
        if (entryLock.isLocked || OrbArms.entryInFlight) return Swept.NOT_READ
        val today = now().toLocalDate()
        val rowsAll = lock.withLock { book().positions.filter { !it.shadow }.toList() }
        // An entry buy of this path still working that no trade owns (its process died before it settled it): cancelled - nothing owns
        // it - and looked at again. Never a short trade's buy (its stop or exit), nor on a contract where an entry is still settling.
        val ownedIds = rowsAll.mapNotNull { it.entryOrderId }.toSet()
        // (19 Nov 2026, final review: an entry still settling from its own order id too - never its order cancelled under it.)
        val waitOn = rowsAll.filter { it.open && (it.side < 0 || it.unconfirmed) }.map { it.contract }.toSet()
        // (After "Erase everything" today, only this path's orders placed after it count - never a lot from before it.)
        // (The book's mark, else the erase's own file - read here, on the sweep's worker, never on the order or tick path.)
        val from = listOfNotNull(lock.withLock { book().sweepFrom }, markedErase()).mapNotNull { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            .maxOrNull()?.takeIf { it.toLocalDate() == today }
        fun counts(o: Broker.OrderRow) = from == null || placedTime(o.placedAt)?.let { !it.isBefore(from) } == true
        val stale = orders.filter { it.tag == LiveMirror.TAG && it.side == "BUY" && it.working && it.type != "SL" && it.type != "SL-M" &&
            it.id !in ownedIds && "${it.exchange}|${it.symbol}|${it.product}" !in waitOn && counts(it) }
        // (Their contracts are looked at again next time; the others go on now.)
        val staleOn = stale.map { "${it.exchange}|${it.symbol}|${it.product}" }.toSet()
        if (stale.isNotEmpty()) {
            stale.forEach { o -> runCatching { Broker.cancel(o.id, o.variety) } }
            runCatching { Diag.record("armlive", "after a start: ${stale.size} working buy(s) of the arms with no trade cancelled (${stale.joinToString { it.id }})") }
        }
        // (Every filled buy of this path, a short trade's stop buy-back included: the short's closed row is netted against it below.)
        val counted = orders.filter { counts(it) }
        val buysToday = counted.filter { it.tag == LiveMirror.TAG && it.side == "BUY" && it.filled > 0 }
        val owners = runCatching { Strategies.owners() }.getOrDefault(emptyMap())
        var undecided = staleOn.size
        for ((contract, buys) in buysToday.groupBy { "${it.exchange}|${it.symbol}|${it.product}" }) {
            if (contract in staleOn) continue
            // 19 Nov 2026 (audit batch 2: while another contract stayed undecided, the sweep ran again on each look for five minutes and
            // said a decided contract's "unexplained lot" again each time): a contract decided once in this start's sweep is not again.
            val seen = buysSeen(buys)
            if (coldDecided[contract] == seen) continue
            val decided = try { sweepOne(contract, buys, orders, counted, pb, owners, today) } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { false }
            if (!decided) undecided++ else { if (coldDecided.size > 500) coldDecided.clear(); coldDecided[contract] = seen }
        }
        return if (undecided == 0) Swept.DONE else Swept.UNDECIDED
    }

    /**
     * 19 Nov 2026: the start's sweep read Zerodha's books but could not decide everything (a fill not shown yet, an entry still settling,
     * an entry being cancelled): tried again on each regular look for [COLD_SWEEP_MS] after the first such try; then Boss is told to check
     * and the sweep ends (true). Books that could not be read are tried again without end (never this clock).
     */
    private fun coldGiveUp(): Boolean {
        val ms = runCatching { nowMs() }.getOrDefault(System.currentTimeMillis())
        if (coldFirstMs == 0L) { coldFirstMs = ms; return false }
        if (ms - coldFirstMs < COLD_SWEEP_MS) return false
        com.optionslab.app.work.Alerts.error("Arms live: after the app restarted, today's live buys of the arms could not all be checked against " +
            "Zerodha. If one is held with no arm trade, it has no app stop: check open positions in Trade.", WHO)
        return true
    }

    /** 19 Nov 2026: when the sweep first could not decide everything (0: not yet); it keeps trying for [COLD_SWEEP_MS]. */
    @Volatile private var coldFirstMs = 0L
    private const val COLD_SWEEP_MS = 5 * 60_000L

    /**
     * 19 Nov 2026 (third review): [sweepUnowned] for one contract ([buys]: today's filled buys of this path on it). What this path bought
     * there today beyond what the book's trades of today entered (open or closed, whatever their order ids) is unowned; never more than
     * Zerodha holds beyond what the book's open trades and the ORB arms' / Liquidity's explain, nor than was bought today by orders of no
     * other owner. True when decided (taken back, or nothing to take); false to try again.
     */
    private suspend fun sweepOne(contract: String, buys: List<Broker.OrderRow>, orders: List<Broker.OrderRow>, counted: List<Broker.OrderRow>,
                                 pb: Broker.Positions, owners: Map<String, String>, today: LocalDate): Boolean {
        val o0 = buys.first()
        val sym = o0.symbol; val exch = o0.exchange; val product = o0.product
        val rows = lock.withLock { book().positions.filter { !it.shadow && it.contract == contract }.toList() }
        // An entry still settling on disk is that entry's to settle ([settle]) - with its order id or without: decided once it has.
        if (rows.any { it.open && it.unconfirmed }) return false
        // (An entry still filling: decided once it is done. A short trade's own working stop or exit is not one.)
        if (rows.none { it.open && it.side < 0 } && buys.any { it.working && it.type != "SL" && it.type != "SL-M" }) return false
        // (What this path bought there today, less what the book's long trades of today entered and its short trades bought back today.)
        val balance = buys.sumOf { it.filled } - rows.filter { it.side > 0 && it.day == today && (!it.unconfirmed || it.entryOrderId != null) }.sumOf { it.qty } -
            rows.filter { it.side < 0 && !it.open && it.exitTime?.toLocalDate() == today }.sumOf { it.qty }
        // 19 Nov 2026 (final review: after an erase or a rebuilt book, what this path sold was not taken off): never more than this path's
        // own net today on the contract - its filled buys less its filled sales (a short trade still open counted back on its side) - less
        // what the book's open long trades hold. Its round trips explain nothing held now: Boss's, Pine's or a strategy's lot stays theirs.
        val mine = counted.filter { it.tag == LiveMirror.TAG && it.symbol == sym && it.exchange == exch && it.product == product && it.filled > 0 }
        // (A sale today of a lot carried from an earlier day closed that day's trade, never today's buy: counted back - no more than this
        // path's own sales today beyond what today's closed long trades sold. A short carried in and bought back today: taken off.)
        // (19 Nov 2026, final check: only today's trades are netted against today's orders - a lot carried in from an earlier day was bought
        // then, never by today's orders; a part of it sold today, whether its row is closed or still open, is counted back.)
        val tagSells = mine.filter { it.side == "SELL" }.sumOf { it.filled }
        val todayClosedLong = rows.filter { it.side > 0 && !it.open && it.day == today }.sumOf { it.qty }
        // (A part sold today is its own closed row - closed today - beside the open rest: counted once, there.)
        val carriedSoldToday = rows.filter { it.side > 0 && !it.open && it.day.isBefore(today) && it.exitTime?.toLocalDate() == today }.sumOf { it.qty }
        val carriedBack = minOf(carriedSoldToday, (tagSells - todayClosedLong).coerceAtLeast(0))
        val netLong = mine.filter { it.side == "BUY" }.sumOf { it.filled } - tagSells +
            rows.filter { it.open && it.side < 0 && it.day == today }.sumOf { it.qty } + carriedBack -
            rows.filter { it.side < 0 && !it.open && it.day.isBefore(today) && it.exitTime?.toLocalDate() == today }.sumOf { it.qty }
        val openLong = rows.filter { it.open && it.side > 0 && it.day == today && (!it.unconfirmed || it.entryOrderId != null) }.sumOf { it.qty }
        val asked = minOf(balance, netLong - openLong)
        if (asked <= 0) return true
        // (Nothing held: decided - unless its newest buy filled a moment ago and the positions may not show it yet.)
        val recent = buys.mapNotNull { placedTime(it.placedAt) }.maxOrNull()?.let { java.time.Duration.between(it, now()).seconds < 120 } ?: false
        val net = pb.net.filter { it.symbol == sym && it.exchange == exch && it.product == product }
        if (net.sumOf { it.qty } <= 0) return !recent
        val held = minOf(net.sumOf { it.qty },
            runCatching { ExitCoordinator.held(exch, sym, product, WHO, orders, first = pb, forceLag = lagForced()) }.getOrNull() ?: return false)
        if (held <= 0) return !recent
        val others = rows.filter { it.open && it.side > 0 && (!it.unconfirmed || it.entryOrderId != null) }.sumOf { it.qty } +
            (if (exch == "NFO" && product == "MIS") OrbArms.liveQtyHint[sym] ?: 0 else 0)
        val boughtToday = LiveMirror.boughtTodayCap(net.map { it.dayBuyQty }, net.sumOf { it.overnight }, held,
            orders.filter { it.symbol == sym && it.exchange == exch && it.product == product && it.side == "BUY" && it.tag != LiveMirror.TAG }.sumOf { it.filled })
        val spec0 = runCatching { Broker.spec(exch, sym) }.getOrNull() ?: return false
        val lot = spec0.lotSize.coerceAtLeast(1)
        val tick = spec0.tickSize.takeIf { it > 0 } ?: 0.05
        val booked = rows.mapNotNull { it.entryOrderId }.toSet()
        val entries = buys.filter { it.type != "SL" && it.type != "SL-M" }.ifEmpty { buys }
        // 19 Nov 2026 (final review): the orders taken back are the unowned ones: not a row's entry, nor the order a row with no order id
        // (taken back from the positions earlier) stands for - for each such trade, an order of its quantity nearest its entry time.
        // (Each such trade takes its quantity off one order that holds at least that much - its own quantity first, then the nearest in
        // time, then the older order; a time that cannot be read last. An order with nothing left is not unowned.)
        val rem = HashMap<String, Int>().apply { entries.filter { it.id !in booked }.forEach { put(it.id, it.filled) } }
        for (r in rows.filter { it.entryOrderId == null && !it.unconfirmed && it.side > 0 && it.day == today }.distinctBy { it.ref }) {
            entries.filter { (rem[it.id] ?: 0) >= r.entryQty }.sortedWith(compareBy<Broker.OrderRow>(
                { o -> if (rem[o.id] == r.entryQty) 0 else 1 },
                { o -> placedTime(o.placedAt)?.let { kotlin.math.abs(java.time.Duration.between(it, r.entryTime).seconds) } ?: Long.MAX_VALUE },
                { o -> o.id.toLongOrNull() ?: Long.MAX_VALUE })).firstOrNull()?.let { o -> rem[o.id] = (rem[o.id] ?: 0) - r.entryQty }
        }
        val pool = entries.filter { (rem[it.id] ?: 0) > 0 }
        // 19 Nov 2026 (final check): held, unexplained, and no unowned order left to stand for it. What this path's own booked orders of today
        // filled beyond what their trades took is its own lot: taken back all the same (priced at the position's average, a safety stop,
        // managed under an arm that rests an SL), never left unwatched. Anything beyond that may be Boss's: never taken - he is told. (Worked
        // out before the claim, so the claim is never more than what is taken.)
        val unmatched = pool.isEmpty()
        val cap = if (!unmatched) asked else {
            val todayIds = entries.map { it.id }.toSet()
            val ownFilled = entries.filter { it.id in booked }.sumOf { it.filled }
            val ownBooked = rows.filter { it.entryOrderId != null && it.entryOrderId in todayIds && it.side > 0 }.sumOf { it.qty }
            minOf(asked, ((ownFilled - ownBooked) / lot) * lot)
        }
        // (What the other book claimed a moment ago counts too - never its lot said to be Boss's.)
        var otherSeen = 0
        if (unmatched && cap <= 0) {
            takeLost("arm", exch, sym, product) { other -> otherSeen = other; 0 }
            val unexplained = LiveMirror.unexplainedQty(held, boughtToday, others + otherSeen, asked, lot)
            if (unexplained > 0) com.optionslab.app.work.Alerts.error("Arms live: after the app restarted, Zerodha holds $unexplained $sym that the " +
                "arms' book does not explain and no order of the arms stands for it. It is not taken as an arm's (it may be yours): check it in Trade.", WHO)
            return true
        }
        // (Its managing arm and its price known before anything is claimed: a claim is never left for a lot nobody took.)
        if (LiveMirror.ARMS.none { it.product == product }) return false
        if (pool.none { it.avg > 0 } && net.none { it.avg > 0 }) return false
        val q = takeLost("arm", exch, sym, product) { other -> otherSeen = other
            minOf(cap, LiveMirror.unexplainedQty(held, boughtToday, others + other, asked, lot)) }
        if (q <= 0) return true
        val takeQ = q
        val restUnexplained = if (unmatched) LiveMirror.unexplainedQty(held, boughtToday, others + otherSeen, asked, lot) - takeQ else 0
        val newest = pool.maxByOrNull { it.placedAt } ?: entries.maxByOrNull { it.placedAt }!!
        val newestId = newest.id.takeIf { !unmatched }
        // Its arm: the owner label of its order, else the one open paper trade on the contract of an arm not yet mirrored live.
        val refs = lock.withLock { book().positions.map { it.ref }.toSet() }
        val byLabel = if (unmatched) null else owners["kite:${newest.id}"]?.substringBefore(" · ")?.let { l -> LiveMirror.ARMS.firstOrNull { it.label == l } }
        val legs = if (unmatched) emptyList() else LiveMirror.ARMS.filter { it.exchange == exch && it.product == product }
            .mapNotNull { sp -> rebuiltLeg(sp, sym)?.takeIf { it.ref !in refs }?.let { sp to it } }
        val leg = (byLabel?.let { b -> legs.firstOrNull { it.first.key == b.key } }) ?: legs.singleOrNull().takeIf { byLabel == null }
        val spec = byLabel ?: leg?.first
        // Not told: managed under an arm of the same exchange and product (its exits and square-offs; one resting an SL order first, else
        // one resting a GTT - a carried lot), with the safety stop.
        val host = spec ?: LiveMirror.ARMS.firstOrNull { it.exchange == exch && it.product == product && it.rest == LiveMirror.Rest.SL }
            ?: LiveMirror.ARMS.firstOrNull { it.exchange == exch && it.product == product }
            ?: LiveMirror.ARMS.firstOrNull { it.product == product } ?: return false
        // (Its price: the average of the unowned orders taken back, newest first for what is taken - never blended with an owned one's.)
        var left = takeQ; var paid = 0.0; var paidQty = 0
        for (o in pool.sortedByDescending { it.placedAt }) { if (left <= 0) break; val k = minOf(left, rem[o.id] ?: 0); if (o.avg > 0) { paid += o.avg * k; paidQty += k }; left -= k }
        val fill = (if (paidQty > 0) paid / paidQty else 0.0).takeIf { it > 0 && !unmatched } ?: net.firstOrNull { it.avg > 0 }?.avg ?: return false
        val rule = if (spec != null) rebuiltRule(spec, sym, placedTime(newest.placedAt)) else (false to false)
        val ref = if (unmatched) "recovered:${sym}:${now()}:${System.nanoTime() % 1_000_000}" else leg?.second?.ref ?: "recovered:${newest.id}"
        val base = Pos(host.key, ref, sym, exch, product, 1, takeQ, takeQ, lot, tick, (net.firstOrNull()?.multiplier?.toInt() ?: 1).coerceAtLeast(1), fill,
            (if (unmatched) null else placedTime(newest.placedAt)?.takeIf { it.toLocalDate() == today }) ?: now(), fill, takeQ,
            runCatching { (Broker.cachedInstruments().orEmpty() + Broker.cachedBfoOptions().orEmpty()).firstOrNull { it.tradingSymbol == sym }?.expiry }.getOrNull(),
            newestId, seen = leg != null && !unmatched, heldSeen = true, stopKind = SAFETY, fixed = rule.first, lock = rule.second)
        val (lv, kind) = if (spec != null && !unmatched) firstStop(spec, base) else (null to SAFETY)
        val level = lv ?: LiveMirror.safetyLevel(fill, 1, tick)
        val ltp = runCatching { Broker.stopQuotes(listOf("$exch:$sym"))["$exch:$sym"]?.last }.getOrNull()?.takeIf { it > 0 } ?: fill
        val got = restStop(base.copy(stopKind = if (lv != null) kind else SAFETY), level, ltp)
        // Written at once (its stop already rests at Zerodha), so nothing later in the sweep can lose it.
        lock.withLock {
            val b = book()
            b.positions += got
            line(b, "${got.label}: ${got.symbol} ${got.qty} taken back from Zerodha after a start (no record of it in the book)")
            save(b)
        }
        val stopWords = got.stopTrigger?.let { "a stop at %.2f".format(Locale.ENGLISH, it) } ?: "no stop level"
        com.optionslab.app.work.Alerts.error(if (unmatched)
            "Arms live: Zerodha holds $takeQ $sym that the arms' book does not explain after the app restarted, and no order of the arms left to " +
                "stand for it: taken back with a safety stop ($stopWords) at the position's average, managed as ${host.label}'s. Check it in Trade now."
            else if (spec != null)
            "${spec.label}: a live buy of $takeQ $sym (order ${newest.id}) that the arms' book did not hold after the app restarted is taken back " +
                "from Zerodha with $stopWords and its square-off. Check it in Trade."
            else "Arms live: a live buy of $takeQ $sym (order ${newest.id}) by the arms, with no record after the app restarted and no arm that " +
                "could be told, is taken back from Zerodha with a safety stop ($stopWords) and its square-off, managed as ${host.label}'s. " +
                "Check it in Trade now.", WHO)
        if (restUnexplained > 0) com.optionslab.app.work.Alerts.error("Arms live: after the app restarted, Zerodha holds $restUnexplained more $sym " +
            "than the arms' own orders explain. That part is not taken as an arm's (it may be yours) and has no app stop: check it in Trade.", WHO)
        runCatching { Diag.record("armlive", "after a start: $sym $takeQ taken back (${if (unmatched) "no order of its own" else "order ${newest.id}"}, " +
            "arm ${spec?.label ?: "not told; as ${host.label}"})") }
        return true
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
        // 18 Nov 2026 (audit, review): the entries the book already has (a trade closed today included) are never taken back again - after
        // one unreadable row the rest of the book is still read, and Boss's own lot bought after an arm's trade closed is his.
        val booked = lock.withLock { book().positions.mapNotNull { it.entryOrderId }.toSet() }
        // (And what the readable open trades hold on each contract: Zerodha holding more there is said, never left unsaid.)
        val bookHeld = lock.withLock { book().positions.filter { it.open && !it.shadow }.groupBy { it.contract }.mapValues { (_, l) -> l.sumOf { it.qty * it.side } } }
        val partly = net.filter { it.qty != 0 && "${it.exchange}|${it.symbol}|${it.product}" in have &&
            kotlin.math.abs(it.qty) > kotlin.math.abs(bookHeld["${it.exchange}|${it.symbol}|${it.product}"] ?: 0) &&
            (it.qty > 0) == ((bookHeld["${it.exchange}|${it.symbol}|${it.product}"] ?: 0) > 0) }
        val adopted = ArrayList<Pos>()
        val partial = ArrayList<String>()
        val bigStop = ArrayList<String>()
        val bigGtt = ArrayList<String>()
        for (pos in net.filter { it.qty != 0 && it.exchange in setOf("NFO", "BFO", "MCX") }) {
            if ("${pos.exchange}|${pos.symbol}|${pos.product}" in have) continue
            val entry = orders.filter { it.tag == LiveMirror.TAG && it.symbol == pos.symbol && it.product == pos.product &&
                it.status == "COMPLETE" && it.side == (if (pos.qty > 0) "BUY" else "SELL") && it.id !in booked }.maxByOrNull { it.placedAt } ?: continue
            // 19 Nov 2026 (final check): never more than this path's own net today on the contract (its filled buys less its filled sales)
            // - its round trips explain nothing held now: Boss's, Pine's or a strategy's lot stays theirs.
            val mineToday = orders.filter { it.tag == LiveMirror.TAG && it.symbol == pos.symbol && it.exchange == pos.exchange && it.product == pos.product && it.filled > 0 }
            val way = if (pos.qty > 0) "BUY" else "SELL"
            // (Nothing is counted back for a lot carried in: whose it was cannot be told once the book is damaged - the rest is said, below.)
            val ownNet = mineToday.filter { it.side == way }.sumOf { it.filled } - mineToday.filter { it.side != way }.sumOf { it.filled }
            val takeQty = minOf(kotlin.math.abs(pos.qty), ownNet)
            if (takeQty <= 0) continue
            val label = owners["kite:${entry.id}"]?.substringBefore(" · ") ?: continue
            val spec = LiveMirror.ARMS.firstOrNull { it.label == label } ?: continue
            val tick = runCatching { Broker.spec(pos.exchange, pos.symbol).tickSize }.getOrDefault(0.05)
            val lot = runCatching { Broker.spec(pos.exchange, pos.symbol).lotSize }.getOrDefault(1).coerceAtLeast(1)
            val side = if (pos.qty > 0) 1 else -1
            // 15 Nov / 16 Nov: the breakeven rule and the step lock its paper trade entered with ([rebuiltRule]; a buy only).
            val rule = if (side > 0) rebuiltRule(spec, pos.symbol, placedTime(entry.placedAt)) else (false to false)
            val p = Pos(spec.key, "recovered:${entry.id}", pos.symbol, pos.exchange, pos.product, side, takeQty, takeQty, lot, tick,
                pos.multiplier.toInt().coerceAtLeast(1), pos.avg.takeIf { it > 0 } ?: entry.avg, now(), pos.avg, takeQty,
                // Its contract's expiry from the day's lists, so the 15:05 expiry square-off applies to it too (review).
                runCatching { (Broker.cachedInstruments().orEmpty() + Broker.cachedBfoOptions().orEmpty()).firstOrNull { it.tradingSymbol == pos.symbol }?.expiry }.getOrNull(),
                entry.id,
                seen = false, heldSeen = true, stopKind = SAFETY,
                // 15 Nov: an R51 / R72 trade taken back from Zerodha keeps the breakeven rule its paper trade entered
                // with, when the paper book shows exactly one open trade of that arm; else the switch now (it only lowers risk).
                fixed = rule.first, lock = rule.second)
            // A stop of this path's own still resting on it is kept as its stop; else the safety stop rests.
            // (Never one that would sell more than is taken back: that could sell Boss's lot too - this path's own SL is brought down to it.)
            val so = orders.filter { it.working && it.tag == LiveMirror.TAG && it.symbol == pos.symbol && it.product == pos.product &&
                (it.type == "SL" || it.type == "SL-M") }.singleOrNull()
            // Its GTT still resting (a carried trade's stop) is kept too: a second one beside it could fire with it into a short.
            val exitSide = if (side > 0) "SELL" else "BUY"
            // A GTT has no tag: one whose quantity is this position's is preferred (else one Boss or a strategy set could be taken).
            val cands = gtts.orEmpty().filter { it.symbol == pos.symbol && it.exchange == pos.exchange && it.covers(exitSide, pos.product) > 0 }
            val gt0 = cands.firstOrNull { it.covers(exitSide, pos.product) == takeQty } ?: cands.singleOrNull()
            val gt = gt0?.takeIf { it.covers(exitSide, pos.product) <= takeQty }
            if (gt0 != null && gt == null) bigGtt += pos.symbol
            val soLeft = so?.let { ExitQty.remaining(it.qty, it.filled, it.pending) } ?: 0
            // (A larger one that cannot be brought down is never taken as this trade's own - the app watches the level - and no second stop
            // rests beside it; Boss is told, below.)
            adopted += when {
                so != null && soLeft <= takeQty -> p.copy(stopOrderId = so.id, stopTrigger = so.trigger, stopQty = soLeft, stopKind = OWN)
                so != null -> shrinkStop(p.copy(stopOrderId = so.id, stopTrigger = so.trigger, stopQty = soLeft, stopKind = OWN), so, takeQty,
                    pos.last.takeIf { it > 0 })?.takeIf { (it.stopQty ?: 0) <= takeQty } ?: p.copy(stopTrigger = so.trigger).also { bigStop += pos.symbol }
                gt != null -> p.copy(stopOrderId = GTT + gt.id, stopTrigger = gt.triggers.firstOrNull(), stopQty = gt.covers(exitSide, pos.product), stopKind = OWN)
                gt0 != null -> p.copy(stopTrigger = gt0.triggers.firstOrNull() ?: LiveMirror.safetyLevel(p.entry, side, tick))
                else -> restStop(p, LiveMirror.safetyLevel(p.entry, side, tick), pos.last.takeIf { it > 0 } ?: p.entry)
            }
            if (takeQty < kotlin.math.abs(pos.qty)) partial += pos.symbol
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
        // 18 Nov 2026 (audit, review): a contract the readable trades hold only part of (an unreadable trade on it too, or Boss's own lot).
        if (partly.isNotEmpty()) com.optionslab.app.work.Alerts.error("Arms live: Zerodha holds more of ${partly.joinToString { it.symbol }} than the " +
            "arms' readable trades. If part of it was an arm's live trade, that part has no app stop now: check it in Trade.", WHO)
        // (19 Nov 2026, final check: what Zerodha holds beyond what this path's own orders explain is never taken - it may be Boss's.)
        if (bigStop.isNotEmpty() || bigGtt.isNotEmpty()) com.optionslab.app.work.Alerts.error("Arms live: a stop resting at Zerodha on " +
            "${(bigStop + bigGtt).distinct().joinToString()} covers more than the arms' trade taken back${if (bigStop.isNotEmpty()) " and could not be " +
            "brought down to it" else ""}: it may sell your own lot too. The app watches the trade's level itself; check that stop in Kite.", WHO)
        if (partial.isNotEmpty()) com.optionslab.app.work.Alerts.error("Arms live: Zerodha holds more of ${partial.joinToString()} than the arms' own " +
            "orders explain; only that part was taken back. The rest has no app stop: check it in Trade.", WHO)
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
     * 20 Nov 2026 (the "Share full settings and state" file, [SettingsShare]): every registered paper book's trades now, by arm key
     * ([Host.open] is memory only and never blocks). A book not read yet is left out.
     */
    internal fun paperLegsNow(): Map<String, List<PaperLeg>> =
        hosts.values.flatMap { h -> runCatching { h.open() }.getOrNull().orEmpty() }.groupBy { it.key }

    /**
     * 20 Nov 2026 (the same file): the arms' live-copies account stop ([accountStop]) as an entry would read it now, with its figures -
     * from memory only (this book's view and the ORB family's copy as last read, [OrbArms.closedLiveKept]), never the locks. The entry
     * itself still reads the books; a record not in memory is said, never guessed.
     */
    internal fun accountStopWords(): String = accountStopNow().first

    /**
     * [accountStopWords] with the refusal an entry would meet now: the rule's reason once reached, [ACCOUNT_UNCHECKED] while the ORB
     * family's record is not in memory (as the entry refuses an unread record); null while it may go.
     */
    internal fun accountStopNow(): Pair<String, String?> {
        val today = runCatching { now().toLocalDate() }.getOrElse { LocalDate.now(com.optionslab.engine.IST) }
        fun trade(t: LocalDateTime, net: Double) =
            com.optionslab.ira.LiquidityStopRule.Trade(t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli(), t.toLocalDate(), net)
        val mine = _view.value.closed.mapNotNull { p -> p.exitTime?.let { trade(it, p.net ?: 0.0) } }
        val orbKept = runCatching { OrbArms.closedLiveKept() }.getOrNull()
        val orb = orbKept.orEmpty().mapNotNull { p ->
            val t = p.exitTime ?: return@mapNotNull null
            val net = p.exit?.let { (it - p.entry) * p.qty - p.charges } ?: return@mapNotNull null
            trade(t, net)
        }
        val st = com.optionslab.ira.LiquidityStopRule.check(mine + orb, today)
        val words = "Arms' live-copies account stop (-Rs 40,000 from the best or -Rs 13,000 in a month, over the mirrored arms' and the ORB " +
            "family's live trades): " + (if (st.stopped) "REACHED, blocking every mirrored live entry: ${st.why}" +
                // 20 Nov 2026 (main, Boss: "make it follow switch"): R51 and R72 pass it while his "Liquidity live stop rule" switch is off.
                (if (!accountStopActs(com.optionslab.engine.orb.DonchianArm.SOURCE)) "; not acting on R51 and R72 while your \"Liquidity live stop rule\" switch is off" else "")
                else "not reached") +
            " · ${st.trades} live trade(s), drawdown ${LiveMirror.rs(st.drawdown)}, worst ${LiveMirror.rs(st.worstDrawdown)}, this month ${LiveMirror.rs(st.month)}" +
            (if (orbKept == null) " · the ORB family's live record is not in memory yet (an entry reads it; unread, it refuses)" else "") + "."
        return words to (if (st.stopped) st.why else if (orbKept == null) ACCOUNT_UNCHECKED else null)
    }

    /**
     * The book forgotten (its file goes with the test's directory) and the memory notes cleared: the tests, between them and as a
     * restart (the book stays on disk). 26 Oct: also the count that stops a pass in flight from saving or showing the old book,
     * and the record of the last write. Never takes [lock] and never reads the vault.
     */
    internal fun wipe() {
        wipeGen++
        written.forget()
        cache = null; unreadable = false; recoverDue = false; coldDue = false; coldPending = false; coldFirstMs = 0L; coldPendingSince = 0L; erasedAt = null; runCatching { eraseMark()?.delete() }; gttsKept = null; legsKept.clear(); shadowMarks.clear(); shadowSeen.clear()
        readFailedMs = 0L; readFails = 0; liveKept = null; loginAsked.clear(); candlesOffUntil = 0L; shortSince.clear(); noRowSaid.clear(); shortSaid.clear(); forgetLostClaims()
        saveDue = false; blockSaid.clear(); lostExits.clear(); unsearchedLost.clear(); searchingLost.clear(); negSaid.clear(); placedStops.clear(); noStopSince.clear(); forgetBatch2()
        _view.value = View(); holdingHint = false; shadowHint = false; liveKeysHint = emptyList(); paperMirroredHint = emptySet(); handsOffUntil.clear(); levels.clear(); closedRefs.clear()
        com.optionslab.engine.orb.StepTrail.clearLadders(); RuleHighs.clear()
    }

    /** 26 Oct (Erase everything): [wipe], and the book's files deleted (a set-aside copy too). */
    internal fun wipeForErase() {
        wipe()
        erasedAt = runCatching { now().toString() }.getOrNull()
        // 19 Nov 2026 (final check): also on disk at once, as a plain file holding only the time (a worker, never the tick or order path),
        // so a kill before the new book is first written still keeps it ([PersonalStores.KEPT_FILES]: nothing about the account).
        erasedAt?.let { t -> runCatching { eraseMark()?.let { f ->
            val tmp = File(f.parentFile, "$ERASE_MARK.tmp")
            try {
                java.io.FileOutputStream(tmp).use { out -> out.write(t.toByteArray(Charsets.UTF_8)); out.fd.sync() }
                tmp.renameTo(f)                                    // (replaces the old mark at once; it stays until this works)
            } finally { if (tmp.exists()) tmp.delete() }
        } } }
        // 19 Nov 2026 (third review): never beside a write in flight on the book's writer, which could put the book back after the delete
        // (a write built before the erase is dropped once this has run: [writeSeq]).
        synchronized(writeMon) { filesForErase().forEach { runCatching { it.delete() } } }
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

    /** 19 Nov 2026 (audit batch 2): its memory notes dropped (a new process, an erase, the tests). */
    private fun forgetBatch2() {
        detachNotes.clear(); detachSaid.clear(); restBackoff.clear(); restRefusedSaid.clear(); endedSaid.clear(); handsOffLast.clear(); coldDecided.clear()
        ownCancelsDue.clear()
        synchronized(ownCancels) { ownCancels.clear() }
    }

    /** For tests: as after a restart - the memory gone, the book on disk. */
    internal fun forgetMemoryForTest() { runCatching { drainWritesForTest() }; coldDue = true; coldPending = false; coldFirstMs = 0L; coldPendingSince = 0L; erasedAt = null; cache = null; legsKept.clear(); gttsKept = null; levels.clear(); com.optionslab.engine.orb.StepTrail.clearLadders(); RuleHighs.clear(); closedRefs.clear(); shadowSeen.clear(); loginAsked.clear(); handsOffUntil.clear(); shortSince.clear(); noRowSaid.clear(); shortSaid.clear(); forgetLostClaims(); blockSaid.clear(); lostExits.clear(); unsearchedLost.clear(); searchingLost.clear(); negSaid.clear(); placedStops.clear(); noStopSince.clear(); forgetBatch2() }
}
