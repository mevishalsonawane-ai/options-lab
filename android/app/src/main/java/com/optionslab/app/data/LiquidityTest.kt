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
 */
object LiquidityTest {
    private const val STATE = "liquidity_test.txt"
    private const val FILLS = "liquidity_fills.log"
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
    private val flushing = AtomicBoolean(false)

    fun init(context: Context) {
        dir = context.applicationContext.noBackupFilesDir
        scope.launch { runCatching { ensureLoaded() } }
    }

    /** The two files read once (a worker thread). A file that cannot be read leaves the defaults. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val d = dir ?: return
            val s = runCatching { File(d, STATE).takeIf { it.isFile }?.readText() }.getOrNull()
            if (_state.value == LiquidityFreeze.State()) _state.value = LiquidityFreeze.decode(s)
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
        _state.value = _state.value.copy(unfrozen = unfrozen)
        saveSoon()
        runCatching { Diag.record("liquidity", "Liquidity BANKNIFTY ${if (unfrozen) "unfrozen" else "frozen again"} for its paper test (by you)") }
        return if (unfrozen) "Liquidity BANKNIFTY unfrozen: the strategy lab, Autopilot and the regime lessons may change it again (only ever lowering risk by themselves)."
            else "Liquidity BANKNIFTY frozen for its paper test until ${LiquidityFreeze.UNTIL.dayOfMonth} Oct: automatic changes only record."
    }

    /** Boss's tick: he watched the first live trade with the app open (or took the tick back). */
    fun setWatched(watched: Boolean) {
        _state.value = _state.value.copy(watched = watched)
        saveSoon()
    }

    /** Live entries re-enabled by Boss with his PIN ([OrbArms.reenableLiquidityLive]): the stop rule counts from [atMs]. */
    internal fun resetStop(atMs: Long) {
        _state.value = _state.value.copy(stopResetAtMs = atMs, stopTold = "")
        saveSoon()
    }

    /** The stop [key] told once; false when it already was. Memory first, written in the background. */
    internal fun markStopTold(key: String): Boolean {
        synchronized(lock) {
            if (_state.value.stopTold == key) return false
            _state.value = _state.value.copy(stopTold = key)
        }
        saveSoon()
        return true
    }

    /** [block] on this object's background scope (a notification, a chat line): never on the caller's path. */
    internal fun later(block: suspend () -> Unit) { scope.launch { runCatching { block() } } }

    private fun saveSoon() {
        val d = dir ?: return
        scope.launch {
            runCatching {
                val f = File(d, STATE)
                val tmp = File(d, "$STATE.tmp")
                tmp.writeText(LiquidityFreeze.encode(_state.value))
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
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
        _state.value = LiquidityFreeze.State()
        dir?.let { d -> runCatching { File(d, STATE).delete() }; runCatching { File(d, FILLS).delete() } }
    }

    /** TEST ONLY: as a fresh start - nothing kept, the defaults, the files read again on the next ask. */
    internal fun resetForTest() { wipe(); loaded = false }

    /** TEST ONLY: memory forgotten (the files kept), as after a restart: the next ask reads the files. */
    internal fun forgetMemoryForTest() {
        synchronized(fills) { fills.clear() }
        _state.value = LiquidityFreeze.State()
        loaded = false
    }

    /** TEST ONLY: whether the fills file exists yet (written in the background). */
    internal fun fillsWrittenForTest(): Boolean = dir?.let { File(it, FILLS).isFile } == true
    internal fun stateWrittenForTest(): String? = dir?.let { File(it, STATE) }?.takeIf { it.isFile }?.readText()

    /** TEST ONLY: the state set directly (the freeze off, a watched tick). */
    internal fun setStateForTest(s: LiquidityFreeze.State) { _state.value = s; loaded = true }
}
