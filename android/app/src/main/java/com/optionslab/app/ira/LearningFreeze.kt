package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Diag
import com.optionslab.app.data.Market
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.AutoChange
import com.optionslab.ira.FreezeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The learning freeze gate (S14; the rule is ira-core's [FreezeRule]): when paper's or live's total P&L has fallen by more
 * than the line since the first automatic change of the last five trading days, every automatic change freezes. The other
 * systems ask [holds] before they apply anything - Autopilot's apply step, the strategy lab's promotion step, the health
 * tuner and the specialists' self-tuning - and go on recording.
 *
 * [holds] is one memory read (never the settings vault, never the disk: safe anywhere, the order path never asks). The
 * state is worked out off the main thread at most every [REFRESH_MS] ([refresh], from the brain's worker through
 * [SelfJobs]) from the P&L calendar's own figures and the agents' books, and kept in noBackupFilesDir/self/freeze.txt -
 * never in the settings vault and never in a backup, so a restored backup cannot unfreeze it. Only Boss unfreezes it
 * (Settings → Bot settings → Learning freeze): no PIN while only paper is on, his PIN while the live account is on. Never
 * by voice. Not in IraGoldAlgo.
 */
object LearningFreeze {
    private const val DIR = "self"
    private const val FILE = "freeze.txt"
    /** Boss's own line in rupees (Bot settings; 0 or absent: twice the average losing day of 60 days, at least ₹3,000). */
    const val K_THRESHOLD = "learn.freeze.threshold"
    const val REFRESH_MS = 30 * 60_000L
    /** The note an automatic change gets when it was recorded but not applied. */
    const val NOTE = "learning frozen: recorded, not applied"

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val refreshing = AtomicBoolean(false)
    @Volatile private var refreshedAt = 0L
    @Volatile private var loaded = false

    private val _state = MutableStateFlow(FreezeRule.State())
    val state: StateFlow<FreezeRule.State> = _state
    private val _verdicts = MutableStateFlow<List<FreezeRule.Verdict>>(emptyList())
    val verdicts: StateFlow<List<FreezeRule.Verdict>> = _verdicts

    /** TEST ONLY: the daily figures (paper, live) and the live switch instead of the app's. */
    @Volatile internal var testDaily: ((Boolean) -> Map<LocalDate, Double>)? = null
    @Volatile internal var testLive: Boolean? = null

    fun enabled(): Boolean = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    fun init(context: Context) {
        appContext = context.applicationContext
        if (enabled()) scope.launch { runCatching { ensureLoaded() } }
    }

    private fun file(): File? = appContext?.let { File(File(it.noBackupFilesDir, DIR), FILE) }

    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val s = runCatching { FreezeRule.decode(file()?.takeIf { it.isFile }?.readText()) }.getOrDefault(FreezeRule.State())
            if (_state.value == FreezeRule.State()) _state.value = s
            loaded = true
        }
    }

    private fun save() {
        val f = file() ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(FreezeRule.encode(_state.value))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /**
     * Is every automatic change frozen now? One memory read; a stale state is worked out again in the background (the
     * answer now is the last one worked out). The systems call it right before they apply a change.
     */
    fun holds(): Boolean {
        if (!enabled()) return false
        // Not read from its file yet (a cold start by a worker): frozen until it is - a change the freeze would block never
        // slips through the window (review G5-5). The read runs in the background, never on the caller's thread.
        if (!loaded && appContext != null) { scope.launch { runCatching { ensureLoaded() } }; return true }
        if (appContext != null && System.currentTimeMillis() - refreshedAt > REFRESH_MS && refreshing.compareAndSet(false, true))
            scope.launch { try { runCatching { refresh() } } finally { refreshing.set(false) } }
        return _state.value.frozen
    }

    private fun liveOn(): Boolean = testLive ?: runCatching { AppSettings.load().let { it.live && it.allowRealOrders } }.getOrDefault(false)

    private fun daily(live: Boolean): Map<LocalDate, Double> = testDaily?.invoke(live)
        ?: runCatching { com.optionslab.app.data.DailyPnl.all(live).mapValues { it.value.net } }.getOrDefault(emptyMap())

    /** The days any agent made an automatic change (Autopilot and the specialists' tuning, the lab's paper promotions, the health tuner). */
    internal fun changeDays(): Set<LocalDate> {
        val out = HashSet<LocalDate>()
        runCatching { Autopilot.ensureLoaded() }
        Autopilot.book.value.entries.filter { it.status != AutoChange.Status.OFFERED && it.status != AutoChange.Status.DECLINED && it.status != AutoChange.Status.SKIPPED }
            .forEach { out += it.day }
        runCatching { com.optionslab.app.data.VariantRunner.variants.value.changes.filter { it.kind == com.optionslab.ira.VariantLab.ChangeKind.AUTO_PAPER }.forEach { out += it.day } }
        runCatching { out += SelfJobs.healthDays() }
        return out
    }

    /** Boss's line (0: the default). A worker thread (the settings vault). */
    fun thresholdSetting(): Double = runCatching { SecurePrefs.getDouble(K_THRESHOLD, 0.0) }.getOrDefault(0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0

    const val PIN_LINE = "The live account is on: change the freeze line with your PIN. Nothing changed."

    /**
     * Boss sets his own line (0: the default). While the live account is on it takes the PIN ([pinConfirmed]): a wider line
     * lets the agents go on changing things for longer. A worker thread.
     */
    fun setThreshold(rs: Double, pinConfirmed: Boolean = false): String {
        if (liveOn() && !pinConfirmed) return PIN_LINE
        val v = rs.coerceIn(0.0, 10_00_000.0)
        runCatching { SecurePrefs.putAllSoon(mapOf(K_THRESHOLD to v)) }
        refreshedAt = 0L
        return if (v <= 0) "The freeze line is back to its default: twice your average losing day of 60 days, at least ₹3,000."
            else "The freeze line is now a fall of ₹${String.format(java.util.Locale.ENGLISH, "%,.0f", v)}."
    }

    /** The state worked out now (a worker thread): each account's verdict, a new freeze told once. */
    fun refresh(today: LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now() }) {
        if (!enabled()) return
        ensureLoaded()
        refreshedAt = System.currentTimeMillis()
        val days = changeDays()
        val over = thresholdSetting().takeIf { it > 0 }
        val since = _state.value.unfrozenOn
        val vs = listOf(FreezeRule.check(FreezeRule.Account.PAPER, daily(false), days, today, over, since),
            FreezeRule.check(FreezeRule.Account.LIVE, daily(true), days, today, over, since))
        _verdicts.value = vs
        val was = _state.value.frozen
        val next = synchronized(lock) { FreezeRule.next(_state.value, vs, today).also { _state.value = it } }
        save()
        if (!was && next.frozen) told(next)
    }

    private fun told(s: FreezeRule.State) {
        runCatching { Diag.record("learning", "frozen: ${s.why}") }
        runCatching { IraActivity.add("Learning frozen: ${s.why}. Every agent keeps recording; nothing is applied until you unfreeze it.") }
        runCatching { com.optionslab.app.data.SmartWorkers.found("learning freeze", com.optionslab.ira.Findings.Kind.OTHER, com.optionslab.ira.Findings.ALL,
            words = "Learning frozen: ${s.why}", strength = 70, ttlMs = 24 * 3_600_000L) }
        val ctx = appContext ?: return
        runCatching { com.optionslab.app.work.Notifier.post(ctx, 5182, com.optionslab.app.work.Notifier.IRA, "Learning frozen",
            "Automatic changes are frozen after a fall since the agents' changes. Settings → Bot settings to review.", "almanac") }
    }

    /** Does unfreezing need the PIN now (the live account is on)? A worker thread. */
    fun needsPin(): Boolean = liveOn()

    /** Boss unfreezes it (Bot settings): the PIN while live is on. What to say. */
    fun unfreeze(pinConfirmed: Boolean): String {
        ensureLoaded()
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        val next = synchronized(lock) { FreezeRule.unfreeze(_state.value, today, liveOn(), pinConfirmed)?.also { _state.value = it } }
            ?: return FreezeRule.PIN_NEEDED
        save()
        runCatching { Diag.record("learning", "unfrozen by you") }
        return if (next.frozen) "Learning is not frozen." else "Unfrozen: the agents may apply their changes again, within their usual bounds. The fall before today is not counted again."
    }

    /**
     * The strategy lab's day while learning is frozen: its records are kept (the variants' days, retirements, live
     * proposals put to Boss), but a paper promotion it made by itself is put straight back (its change marked undone) and
     * not announced. Unfrozen: [pass] as it is.
     */
    fun holdPromotions(pass: com.optionslab.ira.VariantLab.Pass): com.optionslab.ira.VariantLab.Pass {
        if (!holds()) return pass
        val applied = pass.events.filterIsInstance<com.optionslab.ira.VariantLab.Event.Applied>()
        if (applied.isEmpty()) return pass
        var b = pass.book
        // Withheld, not merely marked undone: the promotion's tune goes too, so the arm never trades it.
        for (e in applied) b = com.optionslab.ira.VariantLab.withhold(b, e.change.id) ?: b
        runCatching { Diag.record("learning", "frozen: ${applied.size} lab promotion(s) recorded, not applied") }
        return com.optionslab.ira.VariantLab.Pass(b, pass.events - applied.toSet(), pass.seeded)
    }

    fun diagLine(): String = FreezeRule.line(_state.value)

    /** "Is learning frozen?" */
    fun answer(): String {
        ensureLoaded()
        if (_verdicts.value.isEmpty()) runCatching { refresh() }
        return FreezeRule.say(_state.value, _verdicts.value)
    }

    /** TEST ONLY: not frozen, nothing kept. */
    internal fun resetForTest() {
        synchronized(lock) { _state.value = FreezeRule.State() }
        _verdicts.value = emptyList(); loaded = true; refreshedAt = System.currentTimeMillis(); testDaily = null; testLive = null
    }

    /** TEST ONLY: as on a cold start - [context] known, nothing read from the file yet. */
    internal fun unloadForTest(context: Context) {
        synchronized(lock) { _state.value = FreezeRule.State() }
        appContext = context.applicationContext; loaded = false; refreshedAt = System.currentTimeMillis()
    }

    /** TEST ONLY: [s] as the state (fresh: no background refresh). */
    internal fun setForTest(s: FreezeRule.State) { synchronized(lock) { _state.value = s }; loaded = true; refreshedAt = System.currentTimeMillis() }
}
