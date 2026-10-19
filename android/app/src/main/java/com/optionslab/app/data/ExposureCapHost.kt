package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.SameDirectionCap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The same-direction cap's switches and its record (19 Oct, Boss: "Resolve the issues"; the rules are ira-core's
 * [SameDirectionCap]). At most N (2 by default) automatic open index-option positions betting the same way, across all arms.
 *
 *  - PAPER: on by default (Boss asked for it): the paper book refuses a later automatic entry inside its own lock ([Paper.place]
 *    with its arm), first to reach it wins. Logged once in the Autopilot change timeline, one tap undoes it; the number and the
 *    switch are in Settings → Bot settings.
 *  - LIVE: in SHADOW (CLAUDE.md: anything new that could skip a live trade starts logged only, behind a switch that is off by
 *    default): the entry door ([Broker]) records what it would have refused and changes nothing, unless [liveOn] is switched on.
 *  - Every refused (or would-be refused) entry is kept as a would-be entry with its price ([SameDirectionCap.Blocked]); Liquidity
 *    15+5's are counted apart, so its plain-rules record for the 26 Oct live decision stays separable.
 *
 * The order path reads only the switches kept in memory here (no settings read, no disk, no network: OrderPathRound2Test stays
 * 0); they are read once in the background at start. The record is written in the background, lazily.
 */
object ExposureCapHost {
    private const val K_ON = "g.sameDirCap.on"
    private const val K_MAX = "g.sameDirCap.max"
    private const val K_LIVE = "g.sameDirCap.live"
    private const val K_LOG = "sameDirCap.log"

    @Volatile private var paperOn = true
    @Volatile private var max = SameDirectionCap.DEFAULT_MAX
    @Volatile private var liveOn = false
    @Volatile private var loaded = false
    private val lock = Any()
    private var blocked: List<SameDirectionCap.Blocked> = emptyList()
    private val ledger = SameDirectionCap.LiveLedger()
    @Volatile private var ledgerDay: java.time.LocalDate? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class View(val paperOn: Boolean = true, val max: Int = SameDirectionCap.DEFAULT_MAX, val liveOn: Boolean = false,
                    val line: String = "", val openNow: List<String> = emptyList())
    private val _view = MutableStateFlow(View())
    /** Bot settings' card. */
    val view: StateFlow<View> = _view

    /** Reads the switches and the record in the background; logs the paper cap once in the Autopilot timeline. */
    fun init(context: Context) {
        scope.launch { load(); if (paperOn) runCatching { com.optionslab.app.ira.Autopilot.logSameDirCapOn(max) } }
    }

    /** The switches and the record read from the settings (a worker thread). */
    internal fun load() {
        runCatching {
            paperOn = SecurePrefs.getBoolean(K_ON, true)
            max = SecurePrefs.getInt(K_MAX, SameDirectionCap.DEFAULT_MAX).coerceIn(1, SameDirectionCap.CHOICES.max())
            liveOn = SecurePrefs.getBoolean(K_LIVE, false)
            val log = SecurePrefs.getString(K_LOG).orEmpty().lines().mapNotNull { SameDirectionCap.decode(it) }
            // Merged with any refusal kept before this read (never thrown away), oldest first.
            synchronized(lock) { blocked = (log + blocked).distinct().sortedBy { it.atMs }.takeLast(300) }
            runCatching { ArmLive.liveFlags() }
        }
        loaded = true
        publish()
    }

    /** The paper cap's number while it is on, null while it is off. Memory only (the order path). */
    fun paperMax(): Int? = if (paperOn) max else null

    /**
     * Whether an arm's paper entry may be mirrored to Zerodha right now (19 Oct review, CLAUDE.md's LIVE rule): the app in Live
     * with "AI trades go live" on and no paper-only period. While it may, the paper cap and the no-twin rule only LOG what they
     * would refuse - refusing the paper entry would also skip its live mirror, and a new rule that skips live trades starts in
     * shadow; the live door applies the cap itself (shadow unless its own switch is on). Memory only: the kept flags (warmed in
     * the background, [load]) and the period in memory. Unknown flags count as not live (the paper-only period refuses every
     * live entry at the door anyway until it is read).
     */
    fun liveMirrorPossible(mirror: String? = null): Boolean = runCatching {
        val (app, ai) = ArmLive.liveFlagsKept() ?: return@runCatching false
        if (!app || !ai || PaperOnly.entryRefusal() != null) return@runCatching false
        // [mirror]: the arm's own live key, when the caller knows it - its "Trade live" off or its Shadow on sends nothing live.
        if (mirror == null) return@runCatching true
        val v = ArmLive.view.value
        // Its book not read yet (the first seconds after a start, before the positions step reads it): its switches unknown, and
        // the live mirror itself waits for that read before it sends anything.
        v.loaded && v.on(mirror) && !v.shadowOn(mirror)
    }.getOrDefault(false)

    /**
     * An entry by [owner] on [symbol] betting [dir] at [price] was refused ([applied]) or, live in shadow, would have been: kept as
     * a would-be entry (once per arm and option in [SameDirectionCap.DEDUPE_MS]), said in the diagnostics, written in the
     * background. Memory only on the caller's thread; never throws.
     */
    fun refused(owner: String, symbol: String, dir: Int, price: Double?, live: Boolean, applied: Boolean, why: String) {
        val b = SameDirectionCap.Blocked(System.currentTimeMillis(), runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() },
            owner, symbol, dir, price, live, applied, why)
        val added = synchronized(lock) {
            val next = SameDirectionCap.add(blocked, b)
            (next !== blocked).also { blocked = next }
        }
        if (!added) return
        val tag = if (!live) "paper, refused" else if (applied) "live, refused" else "live, shadow: logged only, nothing changed"
        runCatching { Diag.record("risk", "$owner $symbol: $why ($tag; kept as a would-be entry" + (if (SameDirectionCap.isLiquidity(owner)) ", counted apart in Liquidity's record" else "") + ")") }
        scope.launch {
            runCatching { SecurePrefs.putAllLazy(mapOf(K_LOG to synchronized(lock) { blocked }.joinToString("\n") { it.encode() })) }
            publish()
        }
    }

    /** Today's would-be entries (Bot settings, the diagnostics, an arm's record). */
    fun today(): List<SameDirectionCap.Blocked> {
        val d = runCatching { Market.today() }.getOrNull() ?: return emptyList()
        return synchronized(lock) { blocked.filter { it.day == d } }
    }

    /** Liquidity 15+5's paper entries the cap refused today (its record counts them apart). */
    fun liquidityToday(): Int = today().count { !it.live && SameDirectionCap.isLiquidity(it.owner) }

    /** The diagnostics' and Bot settings' line. */
    fun line(): String = SameDirectionCap.line(synchronized(lock) { blocked }, runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() },
        paperOn, max, liveOn)

    private fun publish() {
        runCatching {
            _view.value = View(paperOn, max, liveOn, line(), Paper.capOpenNow().map { "${it.symbol} (${it.owner})" })
        }
    }

    /** Re-reads what Bot settings shows (the screen opening; a worker thread). */
    fun refresh() { if (!loaded) load() else { runCatching { ArmLive.liveFlags() }; publish() } }

    // ---- Bot settings -------------------------------------------------------------------------------------------------

    /** Boss's paper switch; [log]: into the Autopilot timeline (false for its own Undo). Returns what to say. */
    fun setPaperOn(on: Boolean, log: Boolean = true): String {
        paperOn = on
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        if (log) runCatching { com.optionslab.app.ira.Autopilot.logSameDirCap(on, max) }
        runCatching { Diag.record("risk", "Same-direction cap on paper ${if (on) "on" else "off"} (at most $max)") }
        publish()
        return if (on) "Same-direction cap on for paper: at most $max automatic index options betting the same way; later entries are refused and kept as would-be entries."
        else "Same-direction cap off for paper: automatic entries are no longer refused by it."
    }

    /** Boss's number (1-5). */
    fun setMax(n: Int): String {
        val v = n.coerceIn(1, SameDirectionCap.CHOICES.max())
        max = v
        runCatching { SecurePrefs.putAllSoon(mapOf(K_MAX to v)) }
        runCatching { Diag.record("risk", "Same-direction cap set to $v") }
        publish()
        return "At most $v automatic index option ${if (v == 1) "position" else "positions"} betting the same way."
    }

    /** Boss's live switch (off by default: live only logs what it would refuse). Turning it on only ever refuses entries. */
    fun setLiveOn(on: Boolean): String {
        liveOn = on
        runCatching { SecurePrefs.putAllSoon(mapOf(K_LIVE to on)) }
        runCatching { Diag.record("risk", "Same-direction cap on live ${if (on) "on: live entries past it are refused" else "in shadow: logged only"}") }
        publish()
        return if (on) "Live: the bots' entries past the cap are refused too (your own orders never)." else "Live: in shadow - what it would refuse is logged, nothing changes."
    }

    // ---- live (the entry door; memory only) -----------------------------------------------------------------------------

    private fun liveDir(symbol: String, exchange: String): Pair<Int, String>? {
        val u = com.optionslab.engine.Kite.underlyingOf(symbol) ?: return null
        val right = symbol.takeLast(2)
        return SameDirectionCap.direction(u, exchange, right)?.let { it to u }
    }

    private fun ledgerToday() {
        val d = runCatching { Market.today() }.getOrNull() ?: return
        if (ledgerDay != d) { ledger.clear(); ledgerDay = d }
    }

    /**
     * Inside the entry door, for a bot's live BUY ([owner]: its arm, else its tag): the refusal when the live switch is on, else
     * null - and in shadow (the switch off, the default) the refusal it would have given is only recorded. Memory only.
     */
    fun liveCheck(symbol: String, exchange: String, buy: Boolean, owner: String): String? = runCatching {
        if (!buy) return@runCatching null
        val (dir, _) = liveDir(symbol, exchange) ?: return@runCatching null
        ledgerToday()
        val why = SameDirectionCap.refusal(ledger.open(), dir, max) ?: return@runCatching null
        val on = liveOn
        refused(owner, symbol, dir, null, live = true, applied = on, why = why)
        if (on) "refused: $why" else null
    }.getOrNull()

    /** A bot's live BUY went through the door: counted until it closes. Memory only. */
    fun liveEntered(symbol: String, exchange: String, buy: Boolean, owner: String) {
        runCatching {
            if (!buy) return
            val (dir, _) = liveDir(symbol, exchange) ?: return
            ledgerToday()
            ledger.entered(symbol, dir, owner, System.currentTimeMillis())
        }
    }

    /** An exit order of [symbol] was sent live: one of its entries closed. Memory only. */
    fun liveExited(symbol: String) { runCatching { ledger.exited(symbol) } }

    /** Zerodha's positions read at [readAtMs] ([held]: net units by trading symbol): entries it shows closed are dropped. */
    fun livePositions(held: Map<String, Int>, readAtMs: Long) { runCatching { ledgerToday(); ledger.reconcile(held, readAtMs) } }

    /**
     * TEST ONLY: defaults back (2, live in shadow), no record, no live entries. [paperOn]: false for every other test (TestApp:
     * their arms' many same-way entries are not what they test); the cap's own tests switch it on.
     */
    internal fun resetForTest(paperOn: Boolean = true) {
        this.paperOn = paperOn; max = SameDirectionCap.DEFAULT_MAX; liveOn = false; loaded = true
        synchronized(lock) { blocked = emptyList() }
        ledger.clear(); ledgerDay = null
        _view.value = View()
    }
}
