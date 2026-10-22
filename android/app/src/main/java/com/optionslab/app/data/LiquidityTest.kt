package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.orb.ArmPriority
import com.optionslab.ira.FillQuality
import com.optionslab.ira.LiquidityFreeze
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Liquidity BANKNIFTY's paper test on the phone (Boss, 12 Oct): its small state ([LiquidityFreeze.State]: the freeze, the live
 * stop rule's restart and Boss's "first live trade watched" tick) and its fill-quality records ([FillQuality]).
 *
 * Everything the order and tick paths ask ([frozen], [state], [record]) is memory only: a field read, or one list add under a
 * small lock - never the settings vault, the disk or the network, and nothing waits. The files are written later on this
 * object's own background scope, in noBackupFilesDir (never in a backup, so a restore cannot unfreeze anything or bring back
 * an old stop): the state as a few plain lines, the fills as encrypted lines (Vault), at most [FillQuality.KEEP].
 * Until the state is read from its file it is the default - frozen, nothing re-enabled - which can only hold changes back.
 * Every change to the state is one atomic step on its flow ([update]; 13 Oct review: a plain read-then-write could lose a
 * change made at the same moment by another thread) - never a lock, so nothing on the order or tick path waits.
 */
object LiquidityTest {
    private const val STATE = "liquidity_test.txt"
    private const val FILLS = "liquidity_fills.log"
    /** 20 Oct, enhancement round 1: the no-twin rule's would-be trades (encrypted lines, as the fills). */
    private const val TWINS = "liquidity_twins.log"
    /** The wait before buffered fills are written (several records of one entry are written together). */
    private const val FLUSH_MS = 2_000L

    @Volatile private var dir: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var loaded = false

    private val _state = MutableStateFlow(LiquidityFreeze.State())
    /** The paper test's state (the Strategy lab card and the Liquidity row read it). */
    val state: StateFlow<LiquidityFreeze.State> = _state

    private val fills = ArrayList<FillQuality.Rec>()

    /** Changes made before the saved state was read (applied again on top of it); [early] while it is not read yet. */
    private val edits = ArrayList<(LiquidityFreeze.State) -> LiquidityFreeze.State>()
    @Volatile private var early = true
    private val saveLock = Any()

    /** [f] applied to the state now (and again over the saved state once it is read); the state before it. Memory only. */
    private fun edit(f: (LiquidityFreeze.State) -> LiquidityFreeze.State): LiquidityFreeze.State = synchronized(edits) {
        if (early) { edits += f; while (edits.size > 200) edits.removeAt(0) }   // bounded (a store never read: tests only)
        _state.getAndUpdate(f)
    }
    private val flushing = AtomicBoolean(false)

    fun init(context: Context) {
        dir = context.applicationContext.noBackupFilesDir
        scope.launch {
            runCatching { ensureLoaded() }
            // 20 Oct, enhancement round 1: the no-twin rule on for paper by this update, logged once in the Autopilot timeline.
            if (noTwinOn()) runCatching { com.optionslab.app.ira.Autopilot.logLiqNoTwinOn() }
        }
    }

    /** The two files read once (a worker thread). A file that cannot be read leaves the defaults. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val d = dir ?: return
            val s = runCatching { File(d, STATE).takeIf { it.isFile }?.readText() }.getOrNull()
            // 20 Oct, review: the saved state, with every change made before it was read applied on top, in order. Before, a
            // change made first (a stop told, the trail) left the state non-default, so the file was ignored, and Boss's 13 Oct
            // unfreeze, the trail and the stop reset fell back to their defaults and were written over.
            synchronized(edits) {
                _state.value = edits.fold(LiquidityFreeze.decode(s)) { st, f -> f(st) }
                edits.clear()
                early = false
            }
            val kept = runCatching {
                File(d, FILLS).takeIf { it.isFile }?.readLines(Charsets.US_ASCII).orEmpty().mapNotNull { l ->
                    if (l.isBlank()) null else runCatching {
                        FillQuality.decode(String(Vault.decrypt(android.util.Base64.decode(l, android.util.Base64.NO_WRAP)), Charsets.UTF_8))
                    }.getOrNull()
                }
            }.getOrDefault(emptyList())
            synchronized(fills) {
                val now = ArrayList(fills)
                fills.clear(); fills.addAll((kept + now).distinct().sortedBy { it.atMs }.takeLast(FillQuality.KEEP))
            }
            loaded = true
        }
    }

    private fun today(): java.time.LocalDate = runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() }

    // ---- the freeze ---------------------------------------------------------------------------------------------------------

    /** Is Liquidity BANKNIFTY frozen for its paper test now? Memory only. */
    fun frozenNow(): Boolean = LiquidityFreeze.frozen(_state.value, today())

    /** Is [book] (an arm's source) one of Liquidity's BANKNIFTY books, frozen now? Memory only. */
    fun frozen(book: String): Boolean = book in ArmPriority.BN_BOOKS && frozenNow()

    /** The strategy key the order-flow gate and the brain use for Liquidity BANKNIFTY ("liquidity:BANKNIFTY"), frozen now. */
    fun frozenKey(key: String): Boolean = key.equals(com.optionslab.ira.FlowShadow.liquidityKey(ArmPriority.BN), ignoreCase = true) && frozenNow()

    /** The row's and the lab card's note. */
    fun note(): String = LiquidityFreeze.note(_state.value, today())

    /** Boss's choice from the Strategy lab card: unfrozen (automatic changes that lower risk may apply again) or frozen again. */
    fun setUnfrozen(unfrozen: Boolean): String {
        edit { it.copy(unfrozen = unfrozen) }
        saveSoon()
        runCatching { Diag.record("liquidity", "Liquidity BANKNIFTY ${if (unfrozen) "unfrozen" else "frozen again"} for its paper test (by you)") }
        return if (unfrozen) "Liquidity BANKNIFTY unfrozen: the strategy lab, Autopilot and the regime lessons may change it again (only ever lowering risk by themselves)."
            else "Liquidity BANKNIFTY frozen for its paper test until ${LiquidityFreeze.UNTIL.dayOfMonth} Oct: automatic changes only record."
    }

    /**
     * Boss's own Rs 500 give-back trail on the Liquidity PAPER books ([com.optionslab.engine.orb.LiquidityTrail]; from +Rs 1,000
     * a lot since R19c; research R19b and R19c failed, so it is their choice and off by default). Memory only: the order path
     * reads it at a paper entry.
     */
    fun trailOn(): Boolean = _state.value.trail

    /**
     * Boss's switch from the Strategy lab card (one tap on, one tap off): new paper entries carry the trail or plain rules;
     * open trades keep the rule they entered with. Live trades never use it. Said back and kept in the diagnostics.
     * When it really changes, it is also logged in the Autopilot change timeline as Boss's own choice
     * ([com.optionslab.app.ira.Autopilot.logBossTrail]: shown to Boss, undone in one tap, never counted as Autopilot's change);
     * [log] false: that Undo itself flipping it back, which is not logged again.
     */
    fun setTrail(on: Boolean, log: Boolean = true): String {
        val was = edit { it.copy(trail = on) }.trail
        saveSoon()
        val said = LiquidityFreeze.trailSaid(on)
        runCatching { Diag.record("liquidity", "$said (by you${if (log) "" else ", undone from the change timeline"})") }
        if (log && was != on) runCatching { com.optionslab.app.ira.Autopilot.logBossTrail(on) }
        return said
    }

    /**
     * Boss's own breakeven at +10% on the Liquidity PAPER books ([com.optionslab.engine.orb.LiquidityBreakeven]; 22 Oct, research
     * R100 A4 did not pass, so it is their choice and off by default). Memory only: the order path reads it at a paper entry.
     */
    fun breakevenOn(): Boolean = _state.value.breakeven

    /**
     * Boss's switch from the Strategy lab card (one tap on, one tap off): new paper entries carry the breakeven or plain rules;
     * open trades keep the rule they entered with. Live trades never use it. Said back and kept in the diagnostics. When it
     * really changes it is logged in the Autopilot change timeline as Boss's own choice ([com.optionslab.app.ira.Autopilot.logBossBreakeven]:
     * shown to Boss, undone in one tap, never counted as Autopilot's change); [log] false: that Undo itself flipping it back.
     */
    fun setBreakeven(on: Boolean, log: Boolean = true): String {
        val was = edit { it.copy(breakeven = on) }.breakeven
        saveSoon()
        val said = LiquidityFreeze.breakevenSaid(on)
        runCatching { Diag.record("liquidity", "$said (by you${if (log) "" else ", undone from the change timeline"})") }
        if (log && was != on) runCatching { com.optionslab.app.ira.Autopilot.logBossBreakeven(on) }
        return said
    }

    // ---- 20 Oct, enhancement round 1 (idea 3): the no-twin rule ([com.optionslab.ira.LiquidityTwin]) -----------------------------

    /** Whether the no-twin rule is on for the paper books (on by this update). Memory only: the order path reads it. */
    fun noTwinOn(): Boolean = _state.value.noTwin

    /**
     * Boss's switch from the Strategy lab card (or the change timeline's Undo, [log] false): paper entries that would double a
     * contract another Liquidity book holds become would-be trades (on) or are bought as before (off). Live trades are never
     * changed by it (shadow only). Logged in the Autopilot timeline when it really changes; one tap undoes it.
     */
    fun setNoTwin(on: Boolean, log: Boolean = true): String {
        val was = edit { it.copy(noTwin = on) }.noTwin
        saveSoon()
        val said = if (on) "Liquidity no-twin rule on: a paper entry on an option another Liquidity book already holds becomes a would-be trade (live is never changed)."
            else "Liquidity no-twin rule off: both books may buy the same option on paper, as before."
        runCatching { Diag.record("liquidity", "$said (by you${if (log) "" else ", undone from the change timeline"})") }
        if (log && was != on) runCatching { com.optionslab.app.ira.Autopilot.logBossNoTwin(on) }
        return said
    }

    private val twins = ArrayList<com.optionslab.ira.LiquidityTwin.Rec>()
    @Volatile private var twinsRead = false

    /**
     * One would-be trade the rule made (paper, [com.optionslab.ira.LiquidityTwin.Rec.applied]) or would have made (live, shadow):
     * kept in memory (a list add under a small lock), said in the diagnostics, written encrypted in the background. Never throws.
     */
    fun twin(r: com.optionslab.ira.LiquidityTwin.Rec) {
        runCatching {
            synchronized(twins) { twins += r; while (twins.size > com.optionslab.ira.LiquidityTwin.KEEP) twins.removeAt(0) }
            val d = dir ?: return
            scope.launch {
                runCatching { readTwins() }
                runCatching {
                    // The copy taken under the write lock (review): an older copy is never written after a newer one.
                    synchronized(saveLock) {
                        val text = synchronized(twins) { ArrayList(twins) }.joinToString("") { t ->
                            android.util.Base64.encodeToString(Vault.encrypt(t.encode().toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP) + "\n"
                        }
                        val f = File(d, TWINS); val tmp = File(d, "$TWINS.tmp")
                        tmp.writeText(text, Charsets.US_ASCII)
                        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                    }
                }
            }
        }
    }

    /** The twins file read once (a worker thread), merged with any kept before the read. */
    private fun readTwins() {
        if (twinsRead) return
        val d = dir ?: return
        val kept = runCatching {
            File(d, TWINS).takeIf { it.isFile }?.readLines(Charsets.US_ASCII).orEmpty().mapNotNull { l ->
                if (l.isBlank()) null else runCatching {
                    com.optionslab.ira.LiquidityTwin.decode(String(Vault.decrypt(android.util.Base64.decode(l, android.util.Base64.NO_WRAP)), Charsets.UTF_8))
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        synchronized(twins) {
            val now = ArrayList(twins)
            twins.clear(); twins.addAll((kept + now).distinct().sortedBy { it.atMs }.takeLast(com.optionslab.ira.LiquidityTwin.KEEP))
        }
        twinsRead = true
    }

    /** The would-be trades kept, oldest first (the file read once first: call off the main thread). */
    fun twins(): List<com.optionslab.ira.LiquidityTwin.Rec> {
        runCatching { readTwins() }
        return synchronized(twins) { ArrayList(twins) }
    }

    /** The would-be trades in memory now, without reading the file (the go-live check, under the arms' lock). */
    fun twinsInMemory(): List<com.optionslab.ira.LiquidityTwin.Rec> = synchronized(twins) { ArrayList(twins) }

    /** The diagnostics' and the Strategy lab card's line (off the main thread: the file may be read). */
    fun twinLine(): String = com.optionslab.ira.LiquidityTwin.line(twins(), today(), noTwinOn())

    /** Boss's tick: he watched the first live trade with the app open (or took the tick back). */
    fun setWatched(watched: Boolean) {
        edit { it.copy(watched = watched) }
        saveSoon()
    }

    /** Live entries re-enabled by Boss with his PIN ([OrbArms.reenableLiquidityLive]): the stop rule counts from [atMs]. */
    internal fun resetStop(atMs: Long) {
        edit { it.copy(stopResetAtMs = atMs, stopTold = "") }
        saveSoon()
    }

    /** The stop [key] told once; false when it already was. Memory first, written in the background. */
    internal fun markStopTold(key: String): Boolean {
        val before = edit { if (it.stopTold == key) it else it.copy(stopTold = key) }
        if (before.stopTold == key) return false
        saveSoon()
        return true
    }

    /** [block] on this object's background scope (a notification, a chat line): never on the caller's path. */
    internal fun later(block: suspend () -> Unit) { scope.launch { runCatching { block() } } }

    private fun saveSoon() {
        val d = dir ?: return
        scope.launch {
            // Never the defaults over the saved file: it is read first (here, a worker). One write at a time (one temp file).
            runCatching { ensureLoaded() }
            runCatching {
                synchronized(saveLock) {
                    val f = File(d, STATE)
                    val tmp = File(d, "$STATE.tmp")
                    tmp.writeText(LiquidityFreeze.encode(_state.value))
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                }
            }
        }
    }

    // ---- fill quality --------------------------------------------------------------------------------------------------------

    /** One record kept in memory (a list add under a small lock); written to the file a moment later in the background. */
    fun record(r: FillQuality.Rec) {
        synchronized(fills) {
            fills += r
            while (fills.size > FillQuality.KEEP) fills.removeAt(0)
        }
        if (dir != null && flushing.compareAndSet(false, true)) scope.launch {
            try { delay(FLUSH_MS); runCatching { ensureLoaded() }; runCatching { write() } } finally { flushing.set(false) }
        }
    }

    private fun write() {
        val d = dir ?: return
        val all = synchronized(fills) { ArrayList(fills) }
        val text = all.joinToString("") { r ->
            android.util.Base64.encodeToString(Vault.encrypt(FillQuality.encode(r).toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP) + "\n"
        }
        val f = File(d, FILLS)
        val tmp = File(d, "$FILLS.tmp")
        tmp.writeText(text, Charsets.US_ASCII)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** The records kept, oldest first (the file read once first: call off the main thread). */
    fun fills(): List<FillQuality.Rec> {
        runCatching { ensureLoaded() }
        return synchronized(fills) { ArrayList(fills) }
    }

    /** The records in memory now, without reading the file (for screens and the order path). */
    fun fillsInMemory(): List<FillQuality.Rec> = synchronized(fills) { ArrayList(fills) }

    /** Jarvis's "how are Liquidity fills". */
    fun answer(): String = FillQuality.answer(fills())

    fun diagLine(): String = FillQuality.diagLine(fills())

    /** The order review's notes for [day]. */
    fun reviewNotes(day: java.time.LocalDate): List<String> = FillQuality.reviewNotes(fills(), day)

    /** Everything erased (a wipe): memory and files. */
    fun wipe() {
        synchronized(fills) { fills.clear() }
        synchronized(twins) { twins.clear() }
        _state.value = LiquidityFreeze.State()
        dir?.let { d -> runCatching { File(d, STATE).delete() }; runCatching { File(d, FILLS).delete() }; runCatching { File(d, TWINS).delete() } }
    }

    /** TEST ONLY: as a fresh start - nothing kept, the defaults, the files read again on the next ask. */
    internal fun resetForTest() { wipe(); loaded = false; twinsRead = false; synchronized(edits) { edits.clear(); early = true } }

    /** TEST ONLY: memory forgotten (the files kept), as after a restart: the next ask reads the files. */
    internal fun forgetMemoryForTest() {
        synchronized(fills) { fills.clear() }
        synchronized(twins) { twins.clear() }; twinsRead = false
        _state.value = LiquidityFreeze.State()
        loaded = false
        synchronized(edits) { edits.clear(); early = true }
    }

    /** TEST ONLY: whether the fills file exists yet (written in the background). */
    internal fun fillsWrittenForTest(): Boolean = dir?.let { File(it, FILLS).isFile } == true
    internal fun stateWrittenForTest(): String? = dir?.let { File(it, STATE) }?.takeIf { it.isFile }?.readText()

    /** TEST ONLY: the state set directly (the freeze off, a watched tick). */
    internal fun setStateForTest(s: LiquidityFreeze.State) { _state.value = s; loaded = true; synchronized(edits) { edits.clear(); early = false } }
}
