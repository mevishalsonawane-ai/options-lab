package com.optionslab.app.data

import android.content.Context
import com.optionslab.ira.Findings
import com.optionslab.ira.ManagerConfig
import com.optionslab.ira.ManagerTeam
import com.optionslab.ira.SpecialistBook
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The trade manager's specialists on the phone ([ManagerTeam], [SpecialistBook]; Boss, 10 Oct): which committee each trade's
 * look uses ([configFor]: memory only - no settings vault, no Keystore, nothing read on the look), what each specialist said
 * at the latest look of every open trade ([views], for the Specialists sheet), their notable votes on the findings bus, and
 * the learning loop in the background:
 *
 *  - the research's block per strategy ([importResearch], key=value or JSON; nothing in it reaches the safety rules);
 *  - the weights the self-tuning sets each night after 15:45 ([reviewIfDue]) for SHADOW and paper ACT ([Book.tuned]), and the
 *    FROZEN weights LIVE ACT uses ([Book.frozen]), which change only when Boss accepts the suggestion with his PIN
 *    ([acceptSuggestion]);
 *  - mutes: Boss mutes a specialist for a strategy with no PIN ([mute]: muting only moves the manager back toward the
 *    original rules); un-muting one on a strategy whose LIVE trades the manager acts on takes the PIN ([unmute]); the
 *    self-tuning mutes one that hurt more than it helped (paper and shadow only) and says so on the findings bus;
 *  - the nightly line per strategy ([Book.lines]), posted as a finding and said by Jarvis.
 *
 * Kept in a plain JSON file under noBackupFilesDir (never backed up), read once on a worker thread and written from memory
 * at most every 5 s on its own thread.
 */
object ManagerSpecialists {
    private const val SAVE_MS = 5_000L
    /** A trade's view is published at most this often unless the Chair's line changed. */
    private const val VIEW_MS = 3_000L
    /** Who the specialists are on the findings bus ("trade manager · VWAP": never counted in any consensus, never a wake). */
    fun who(id: String): String = "${TradeManager.WHO} · ${ManagerTeam.nameOf(id)}"

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch { runCatching { load() } }
    }

    /**
     * Everything kept: per strategy family the [research] config, the self-tuned [tuned] weights and mutes (SHADOW and paper
     * ACT), the [frozen] ones (LIVE ACT), last night's [lines] and the day they were written ([reviewedOn]).
     */
    data class Book(
        val research: Map<String, ManagerConfig> = emptyMap(),
        val tuned: Map<String, SpecialistBook.Tuning> = emptyMap(),
        val frozen: Map<String, SpecialistBook.Tuning> = emptyMap(),
        val lines: List<String> = emptyList(),
        val reviewedOn: String? = null,
    )

    private val _book = MutableStateFlow(Book())
    val book: StateFlow<Book> = _book

    private class Cached(val book: Book, val cfg: ManagerConfig)
    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * The committee for [family]'s trade: the research's config (else the defaults) with the tuning applied - the [frozen] one
     * for a LIVE trade the manager acts on, the self-tuned one otherwise. Memory only; the same object until something changes.
     */
    fun configFor(family: String, frozen: Boolean): ManagerConfig {
        val b = _book.value
        val k = if (frozen) "$family|live" else family
        cache[k]?.takeIf { it.book === b }?.let { return it.cfg }
        val base = b.research[family] ?: ManagerConfig(family)
        val cfg = SpecialistBook.apply(base, if (frozen) b.frozen[family] else b.tuned[family])
        cache[k] = Cached(b, cfg)
        return cfg
    }

    /** The manager acts on [family]'s LIVE trades (its frozen weights apply; un-muting there takes the PIN). */
    fun liveActs(family: String): Boolean =
        TradeManagerHost.policy.value.mode(family, TradeManager.Account.LIVE) == TradeManager.Mode.ACT

    private fun changed(f: (Book) -> Book) {
        synchronized(this) { _book.value = f(_book.value) }
        dirty()
    }

    // ---- the latest look of every open trade ------------------------------------------------------------------------------

    /** One open trade's latest look: each specialist's vote, the Chair's line and its top three reasons. */
    data class View(val tradeId: String, val family: String, val label: String, val atMs: Long, val votes: List<ManagerTeam.Vote>,
                    val chair: String, val top: List<String>, val frozen: Boolean)

    private val _views = MutableStateFlow<Map<String, View>>(emptyMap())
    val views: StateFlow<Map<String, View>> = _views
    private val lastView = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * After a look at [t] ([tradeId]): its view published (at most every 3 s unless the Chair's line changed) and its new strong
     * votes on the findings bus with no direction (so no consensus counts them; the lean is in the evidence). Memory only.
     */
    fun observe(tradeId: String, t: TradeManager.ManagedTrade, step: ManagerTeam.Step, now: Long, frozen: Boolean) {
        val chair = ManagerTeam.chairLine(step.decision, step.lead, step.top)
        val was = lastView[tradeId]
        if (was == null || was.second != chair || now - was.first >= VIEW_MS || now < was.first) {
            lastView[tradeId] = now to chair
            val v = View(tradeId, t.family, t.label, now, step.votes, chair, step.top.map { "${ManagerTeam.nameOf(it.specialist)}: ${it.reason}" }, frozen)
            synchronized(_views) { _views.value = _views.value + (tradeId to v) }
        }
        for (p in step.posts) runCatching {
            val lean = if (p.kind == ManagerTeam.Kind.EXIT) -t.side.toDouble() else t.side.toDouble()
            SmartWorkers.found(who(p.specialist), Findings.Kind.OTHER, t.underlying, 0, null, p.strength,
                "${ManagerTeam.nameOf(p.specialist)} on ${t.label}: ${p.kind.name.lowercase(java.util.Locale.ENGLISH)} - ${p.reason}",
                ttlMs = 15 * 60_000L, evidence = mapOf("lean" to lean))
        }
    }

    /** [tradeId] is no longer followed: its view goes. */
    fun dropView(tradeId: String) {
        lastView.remove(tradeId)
        synchronized(_views) { if (tradeId in _views.value) _views.value = _views.value - tradeId }
    }

    // ---- Boss's hand: mutes, the live suggestion, the research's block ----------------------------------------------------

    const val PIN_UNMUTE = "The trade manager acts on this strategy's live trades: confirm with your PIN in Trade manager → Specialists " +
        "to un-mute a specialist there. Nothing changed."
    const val PIN_ACCEPT = "Accepting the tuned weights for live trades takes your PIN (Trade manager → Specialists). Nothing changed."

    private fun settledOf(family: String, id: String): Int =
        SpecialistBook.cards(TradeManagerHost.records.value).firstOrNull { it.family == family && it.specialist == id }?.settled ?: 0

    /** Boss mutes [id] for [family] (paper, shadow and live alike). No PIN: muting only moves the manager back toward the original rules. */
    fun mute(family: String, id: String, by: String = "you"): String {
        val f = TradeManager.familyOf(family) ?: return "No such strategy for the trade manager."
        if (id !in ManagerTeam.IDS) return "No such specialist. They are: ${ManagerTeam.SPECIALISTS.joinToString(", ") { it.name }}."
        val why = "muted by $by"
        changed { b ->
            b.copy(tuned = b.tuned + (family to SpecialistBook.mute(b.tuned[family] ?: SpecialistBook.Tuning(), id, why)),
                frozen = b.frozen + (family to SpecialistBook.mute(b.frozen[family] ?: SpecialistBook.Tuning(), id, why)))
        }
        val name = ManagerTeam.nameOf(id)
        runCatching { Diag.record("trade manager", "${f.name}: $name specialist $why") }
        return "$name specialist muted for ${f.name}: its exit and extension votes no longer count; its record goes on" +
            (if (id in ManagerTeam.VETOES) ", and its hard veto still stands" else "") +
            ". The lock and the original rules are untouched. No PIN needed: muting only moves the manager back toward the original rules."
    }

    /** Boss un-mutes [id] for [family]; on a strategy whose LIVE trades the manager acts on, only with [pinConfirmed]. */
    fun unmute(family: String, id: String, pinConfirmed: Boolean = false): String {
        val f = TradeManager.familyOf(family) ?: return "No such strategy for the trade manager."
        if (id !in ManagerTeam.IDS) return "No such specialist."
        val live = liveActs(family)
        if (live && !pinConfirmed) return PIN_UNMUTE
        val n = settledOf(family, id)
        changed { b ->
            val tuned = b.tuned + (family to SpecialistBook.unmute(b.tuned[family] ?: SpecialistBook.Tuning(), id, n))
            val frozen = if (!live || pinConfirmed) b.frozen + (family to SpecialistBook.unmute(b.frozen[family] ?: SpecialistBook.Tuning(), id, n)) else b.frozen
            b.copy(tuned = tuned, frozen = frozen)
        }
        val name = ManagerTeam.nameOf(id)
        runCatching { Diag.record("trade manager", "${f.name}: $name specialist un-muted") }
        return "$name specialist counts again for ${f.name}."
    }

    /** What the tuned weights would change on [family]'s LIVE trades (empty: nothing). */
    fun suggestion(family: String): List<String> {
        val b = _book.value
        return SpecialistBook.suggestion(b.tuned[family], b.frozen[family], b.research[family] ?: ManagerConfig(family))
    }

    /** Boss accepts the tuned weights and mutes for [family]'s LIVE trades: only with his PIN. */
    fun acceptSuggestion(family: String, pinConfirmed: Boolean): String {
        if (!pinConfirmed) return PIN_ACCEPT
        val f = TradeManager.familyOf(family) ?: return "No such strategy for the trade manager."
        val tuned = _book.value.tuned[family] ?: SpecialistBook.Tuning()
        changed { b -> b.copy(frozen = b.frozen + (family to tuned.copy(lastDay = null))) }
        runCatching { Diag.record("trade manager", "${f.name}: live specialists' weights set to the tuned ones on your PIN") }
        return "${f.name}'s live trades now use the tuned specialists' weights."
    }

    /** The research's block (key=value or JSON) for one or more strategies. What to say. */
    fun importResearch(text: String): String {
        val got = runCatching { ManagerConfig.parse(text) }.getOrDefault(emptyMap()).filterKeys { TradeManager.familyOf(it) != null }
        if (got.isEmpty()) return "Nothing in that block for the trade manager's specialists."
        changed { b -> b.copy(research = b.research + got) }
        runCatching { Diag.record("trade manager", "research's specialists block for ${got.keys.joinToString(", ")}") }
        return "The research's specialists settings are in for ${got.keys.joinToString(", ") { TradeManager.familyName(it) }}."
    }

    // ---- the nightly self-review ------------------------------------------------------------------------------------------

    /**
     * Once a trading day after 15:45 (the brain's worker, a worker thread): each strategy's report cards from the settled
     * records, the self-tuned weights stepped ([SpecialistBook.tune]: paper and shadow only - the frozen live ones never move
     * here), a newly muted specialist told on the findings bus with its reason, and one line per strategy posted and kept for
     * Jarvis. Never throws.
     */
    fun reviewIfDue(nowMs: Long = TradeManagerHost.nowMs()) {
        runCatching {
            if (!loaded) return
            val today = Market.today()
            if (!Market.isTradingDay(today) || Market.minuteNow() < 15 * 60 + 45) return
            if (_book.value.reviewedOn == today.toString()) return
            review(today.toString(), nowMs)
        }
    }

    /** The review itself (and by tests). */
    internal fun review(day: String, nowMs: Long) {
        val records = TradeManagerHost.records.value
        val cards = SpecialistBook.cards(records)
        val lines = ArrayList<String>()
        val muted = ArrayList<Pair<String, Pair<String, String>>>()
        changed { b ->
            val tuned = HashMap(b.tuned)
            for (f in cards.map { it.family }.distinct()) {
                val base = b.research[f] ?: ManagerConfig(f)
                val r = SpecialistBook.tune(f, base, tuned[f] ?: SpecialistBook.Tuning(), cards, day)
                tuned[f] = r.tuning
                for (m in r.newlyMuted) muted += f to m
                val trades = records.count { it.trade.family == f && it.vsOriginal != null && it.notes.any { n -> n.voters.isNotEmpty() } }
                lines += SpecialistBook.nightLine(f, cards, r.tuning, trades)
            }
            b.copy(tuned = tuned, lines = lines, reviewedOn = day)
        }
        for ((f, m) in muted) runCatching {
            val words = "${ManagerTeam.nameOf(m.first)} specialist muted for ${TradeManager.familyName(f)} on paper and shadow: ${m.second}"
            SmartWorkers.found("${TradeManager.WHO} · self-review", Findings.Kind.DRIFT, Findings.ALL, 0, null, 60, words, ttlMs = 24 * 3_600_000L)
            Diag.record("trade manager", words)
        }
        for (l in lines) runCatching {
            SmartWorkers.found("${TradeManager.WHO} · self-review", Findings.Kind.OTHER, Findings.ALL, 0, null, 50, l, ttlMs = 24 * 3_600_000L)
        }
    }

    // ---- words ------------------------------------------------------------------------------------------------------------

    /** Jarvis on the specialists: which one made a strategy exit, how they are doing, mute or un-mute one (no PIN by voice). */
    fun answer(a: TradeManager.Ask): String {
        val team = a.team ?: return "Ask me which specialist made a strategy exit, how the specialists are doing, or to mute one."
        val records = TradeManagerHost.records.value
        return when (team.kind) {
            TradeManager.TeamKind.WHICH -> SpecialistBook.answerWhich(a.family, records) { SmartWorkers.hhmm(it) }
            TradeManager.TeamKind.HOW -> SpecialistBook.answerHow(a.family, records, _book.value.tuned) +
                _book.value.lines.takeIf { it.isNotEmpty() }?.let { "\nLast night's review: " + it.joinToString("; ") }.orEmpty()
            TradeManager.TeamKind.MUTE, TradeManager.TeamKind.UNMUTE -> {
                val fam = a.family ?: return "For which strategy, Boss? Say, for example, \"mute the OI specialist for Pine\"."
                val id = team.specialist ?: return "Which specialist, Boss? They are: ${ManagerTeam.SPECIALISTS.joinToString(", ") { it.name }}."
                if (team.kind == TradeManager.TeamKind.MUTE) mute(fam, id, "you (by voice)") else unmute(fam, id, pinConfirmed = false)
            }
        }
    }

    /** The diagnostics' lines. */
    fun diagLines(): List<String> {
        val b = _book.value
        val out = ArrayList<String>()
        for ((f, tu) in b.tuned) if (tu.muted.isNotEmpty() || tu.weights.isNotEmpty())
            out += "Specialists ${TradeManager.familyName(f)}: " + (tu.muted.keys.map { "${ManagerTeam.nameOf(it)} muted" } +
                tu.weights.map { "${ManagerTeam.nameOf(it.key)} ${String.format(java.util.Locale.ENGLISH, "%.2f", it.value)}" }).joinToString(", ")
        if (b.research.isNotEmpty()) out += "Specialists research block: " + b.research.keys.joinToString(", ")
        b.lines.forEach { out += "Specialists review: $it" }
        return out
    }

    // ---- the file ---------------------------------------------------------------------------------------------------------

    private fun file(): File? = appContext?.let { File(File(it.noBackupFilesDir, "trademanager"), "specialists.json") }

    @Volatile private var loaded = false
    private val pendingSave = AtomicBoolean(false)

    private fun dirty() {
        if (!pendingSave.compareAndSet(false, true)) return
        scope.launch {
            kotlinx.coroutines.delay(SAVE_MS)
            pendingSave.set(false)
            runCatching { save() }
        }
    }

    private fun encT(t: SpecialistBook.Tuning): JSONObject = JSONObject()
        .put("w", JSONObject().apply { t.weights.forEach { (k, v) -> if (v.isFinite()) put(k, v) } })
        .put("m", JSONObject().apply { t.muted.forEach { (k, v) -> put(k, v) } })
        .put("h", JSONObject().apply { t.handUnmuted.forEach { (k, v) -> put(k, v) } })
        .apply { t.lastDay?.let { put("d", it) } }

    private fun decT(o: JSONObject): SpecialistBook.Tuning {
        val w = o.optJSONObject("w")?.let { j -> j.keys().asSequence().associateWith { j.getDouble(it) } }.orEmpty()
        val m = o.optJSONObject("m")?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } }.orEmpty()
        val h = o.optJSONObject("h")?.let { j -> j.keys().asSequence().associateWith { j.getInt(it) } }.orEmpty()
        return SpecialistBook.Tuning(w, m, h, if (o.has("d")) o.getString("d") else null)
    }

    private fun encMap(m: Map<String, SpecialistBook.Tuning>): JSONObject = JSONObject().apply { m.forEach { (k, v) -> put(k, encT(v)) } }
    private fun decMap(o: JSONObject?): Map<String, SpecialistBook.Tuning> =
        o?.let { j -> j.keys().asSequence().mapNotNull { k -> runCatching { k to decT(j.getJSONObject(k)) }.getOrNull() }.toMap() }.orEmpty()

    /** Written now (tests, and the coalesced save). */
    internal fun save() {
        if (!loaded) return
        val f = file() ?: return
        val b = _book.value
        val o = JSONObject().put("tuned", encMap(b.tuned)).put("frozen", encMap(b.frozen))
            .put("research", b.research.values.joinToString("\n\n") { ManagerConfig.encode(it) })
            .put("lines", JSONArray(b.lines))
        b.reviewedOn?.let { o.put("reviewedOn", it) }
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** Read once (a worker thread). */
    internal fun load() {
        if (loaded) return
        val f = file() ?: return
        val text = runCatching { if (f.isFile) f.readText() else null }.getOrNull()
        if (text != null) runCatching {
            val o = JSONObject(text)
            val lines = o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val research = ManagerConfig.parse(o.optString("research", ""))
            val got = Book(research, decMap(o.optJSONObject("tuned")), decMap(o.optJSONObject("frozen")), lines,
                if (o.has("reviewedOn")) o.getString("reviewedOn") else null)
            // (A change Boss made in the moment before the file was read wins: it is written over the file next.)
            synchronized(this) { if (_book.value == Book()) _book.value = got }
        }
        loaded = true
    }

    /** TEST ONLY: nothing kept, nothing shown (in memory). */
    internal fun resetForTest() {
        synchronized(this) { _book.value = Book() }
        cache.clear(); lastView.clear()
        synchronized(_views) { _views.value = emptyMap() }
        loaded = true
    }
}
