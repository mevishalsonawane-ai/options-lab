package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.sandbox.PaperSpread
import com.optionslab.engine.sandbox.SandboxEvent
import com.optionslab.ira.Findings
import com.optionslab.ira.GrammarPaper
import com.optionslab.ira.ShadowSim
import com.optionslab.ira.StrategyForge
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
 * The paper arm that hosts approved forge ideas (S2, 11 Oct: "when Boss approves 'start on paper' it must actually trade on
 * the PAPER account"). Any [StrategyForge] candidate Boss approved for paper ([StrategyForge.Stage.PAPER]) is hosted - at
 * most [GrammarPaper.MAX_HOSTED] at once - and traded by its own grammar on the live minute bars: the entry block and
 * filters through the forge's own signal code ([GrammarPaper.signal], [ShadowSim]), 1 lot, at the money or one strike in
 * the money as the grammar says, the nearest expiry; its exits ([GrammarPaper.exit]): the premium stop resting in the paper
 * book (it fills on a minute's low, the wick), the target, the no-progress exit, the time stop, the index-level exit, its
 * square-off, F&O's close and on expiry day the expiry square-off's 15:05.
 *
 * PAPER ONLY, always: every order here is the paper account's ([Paper.place] / [Paper.cancel]); this file never names the
 * broker, there is no live flag, and its file is not in the backup - nothing here can reach Zerodha whatever Live, "AI
 * trades go live" or a voice command says. New entries obey the kill switch, the bot's stop for today (the daily loss limit
 * included), the account day lock, the account-wide guard ([Guard.check], paper), F&O's hours, the entry cut-off (the rules'
 * own last entry, never after the account's) and a fresh index price. Exits always go (they lower risk). Its trades are
 * registered with the trade manager in SHADOW ([TradeManagerHost.sync], family [GrammarPaper.FAMILY]: records only).
 *
 * Each idea's paper record is judged against its forward test every pass ([GrammarPaper.judge]): worse than it, or a fall
 * beyond the capital rule, stops the idea (what it holds is sold) and a finding says why. Boss stops one from the Strategy
 * lab sheet ([stop]); a stopped idea is never started again by itself. Hosting: [host] (by the approve flow's listener
 * [onApproved], or by itself on the next pass for any approved idea not yet hosted). Its own encrypted file.
 */
object GrammarArm {
    const val LABEL = "Forge ideas (paper)"
    private const val WHO = "Strategy lab · forge"
    private const val TICK = 0.05

    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "grammar_arm.vault")
    }

    /** One hosted idea: what it trades by, what its forward test expects a trade, and whether it is still on. */
    data class Idea(
        val id: String, val name: String, val underlying: String, val rules: String, val since: LocalDate,
        val expect: Double, val expectN: Int, val active: Boolean = true, val stoppedOn: LocalDate? = null, val stopWhy: String? = null,
        /** The last decision minute acted on (or refused) and its day: a decision is looked at once. */
        val lastDecision: Int = -1, val decisionDay: LocalDate? = null, val status: String = "",
    )

    /** One paper trade of an idea. */
    data class Pos(
        val idea: String, val index: String, val symbol: String, val right: String, val qty: Int, val entry: Double,
        val entryDay: LocalDate, val entryTime: LocalDateTime, val decisionMin: Int, val indexAtEntry: Double, val expiry: LocalDate,
        val orderId: String?, val stopOrderId: String? = null, val exit: Double? = null, val exitTime: LocalDateTime? = null,
        val why: String? = null, val charges: Double = 0.0,
    ) {
        val open: Boolean get() = exit == null
        val net: Double? get() = exit?.let { (it - entry) * qty - charges }
    }

    private class Book(val ideas: MutableList<Idea> = ArrayList(), val positions: MutableList<Pos> = ArrayList(), val log: ArrayList<String> = ArrayList())

    /** One idea as the sheet shows it: its open trade, every closed trade's rupees (oldest first) and the judgement. */
    data class IdeaView(val idea: Idea, val open: Pos?, val nets: List<Double>, val verdict: String?)

    data class View(val ideas: List<IdeaView> = emptyList(), val log: List<String> = emptyList()) {
        val active: List<IdeaView> get() = ideas.filter { it.idea.active }
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    @Volatile private var cache: Book? = null
    private const val LOG_KEPT = 200
    private const val CLOSED_KEPT = 600

    private fun ready() = ::file.isInitialized

    /** Its book can be read now (15 Oct cleanup: a read before that counts as "holding", so nothing is switched off on it). */
    fun readyForCleanup(): Boolean = ready()

    /** The lab's switch (the strategy lab's builds only; never in IraGoldAlgo). */
    fun enabled(): Boolean = VariantRunner.enabled()

    // ---- the file ------------------------------------------------------------------------------------------------------------

    private fun date(s: String?): LocalDate? = s?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            o.optJSONArray("ideas")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    b.ideas += Idea(x.getString("id"), x.getString("name"), x.getString("underlying"), x.getString("rules"),
                        LocalDate.parse(x.getString("since")), x.getDouble("expect"), x.getInt("expectN"), x.optBoolean("active", false),
                        date(x.optString("stoppedOn")), x.optString("stopWhy").ifEmpty { null }, x.optInt("lastDecision", -1),
                        date(x.optString("decisionDay")), x.optString("status"))
                }
            }
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) b.positions += readPos(a.getJSONObject(i)) }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
        }.onFailure { if (file.exists()) Vault.setAside(file) }
        cache = b
        publish(b)
        return b
    }

    private fun readPos(p: JSONObject): Pos = Pos(
        p.getString("idea"), p.getString("index"), p.getString("symbol"), p.getString("right"), p.getInt("qty"), p.getDouble("entry"),
        LocalDate.parse(p.getString("entryDay")), LocalDateTime.parse(p.getString("entryTime")), p.getInt("decisionMin"),
        p.getDouble("indexAtEntry"), LocalDate.parse(p.getString("expiry")), p.optString("orderId").ifEmpty { null },
        p.optString("stopOrderId").ifEmpty { null }, if (p.has("exit")) p.getDouble("exit") else null,
        p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) }, p.optString("why").ifEmpty { null }, p.optDouble("charges", 0.0))

    private fun writePos(p: Pos): JSONObject = JSONObject().put("idea", p.idea).put("index", p.index).put("symbol", p.symbol).put("right", p.right)
        .put("qty", p.qty).put("entry", p.entry).put("entryDay", p.entryDay.toString()).put("entryTime", p.entryTime.toString())
        .put("decisionMin", p.decisionMin).put("indexAtEntry", p.indexAtEntry).put("expiry", p.expiry.toString()).put("orderId", p.orderId ?: "")
        .put("stopOrderId", p.stopOrderId ?: "").put("charges", p.charges)
        .apply { p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitTime", it.toString()) }; p.why?.let { put("why", it) } }

    private fun writeIdea(x: Idea): JSONObject = JSONObject().put("id", x.id).put("name", x.name).put("underlying", x.underlying).put("rules", x.rules)
        .put("since", x.since.toString()).put("expect", x.expect).put("expectN", x.expectN).put("active", x.active)
        .put("stoppedOn", x.stoppedOn?.toString() ?: "").put("stopWhy", x.stopWhy ?: "").put("lastDecision", x.lastDecision)
        .put("decisionDay", x.decisionDay?.toString() ?: "").put("status", x.status)

    /** What this process last wrote to the book's file: an unchanged save is not encrypted and synced again ([Vault.LastWrite]). */
    private val written = Vault.LastWrite()

    private fun save(b: Book) {
        val o = JSONObject()
        o.put("ideas", JSONArray().apply { b.ideas.forEach { put(writeIdea(it)) } })
        o.put("positions", JSONArray().apply { b.positions.forEach { put(writePos(it)) } })
        o.put("log", JSONArray().apply { b.log.takeLast(LOG_KEPT).forEach { put(it) } })
        runCatching { written.write(file, o.toString().toByteArray(Charsets.UTF_8)) }
        publish(b)
    }

    private fun nets(b: Book, id: String): List<Double> = b.positions.filter { it.idea == id && !it.open }.map { it.net ?: 0.0 }

    private fun publish(b: Book) {
        _view.value = View(b.ideas.map { x ->
            val nets = nets(b, x.id)
            IdeaView(x, b.positions.firstOrNull { it.idea == x.id && it.open }, nets, GrammarPaper.judge(nets, x.expect)?.why)
        }, b.log.takeLast(30))
        // The trade manager's one call: SHADOW (an unlisted family records only); this arm's own exits stay authoritative.
        runCatching { val today = Market.today(); TradeManagerHost.sync(b.positions.filter { it.open || it.exitTime?.toLocalDate() == today }.map { managed(b, it) }, owner = "grammar") }
    }

    private fun managerId(p: Pos): String = "${GrammarPaper.FAMILY}:${p.symbol}:${p.entryTime}"

    private fun ms(t: LocalDateTime): Long = t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    private fun rulesOf(x: Idea?): ShadowSim.Rules? = x?.let { ShadowSim.decode(it.rules) }

    /** [p] as the trade manager follows it: a bought option with the grammar's stop and target on its premium, out by its square-off. */
    private fun managed(b: Book, p: Pos): TradeManagerHost.ArmTrade {
        val x = b.ideas.firstOrNull { it.id == p.idea }
        val r = rulesOf(x)
        val (stop, target) = r?.let { GrammarPaper.levels(it, p.entry) } ?: (Double.NEGATIVE_INFINITY to Double.POSITIVE_INFINITY)
        val sq = r?.let { GrammarPaper.squareOffMin(it, p.entryDay, p.expiry == p.entryDay) } ?: (15 * 60 + 10)
        return TradeManagerHost.ArmTrade(GrammarPaper.FAMILY, managerId(p), x?.name ?: p.idea, p.index, if (p.right == "PE") -1 else 1, p.symbol,
            p.entry, p.qty, stop.takeIf { it > 0 && it.isFinite() }, target.takeIf { it.isFinite() }, ms(p.entryDay.atTime(sq / 60, sq % 60)),
            live = false, entryMs = ms(p.entryTime), exit = p.exit, exitWhy = p.why, exitMs = p.exitTime?.let { ms(it) })
    }

    private fun note(b: Book, text: String) {
        b.log += "${runCatching { Market.now().toLocalDateTime().withNano(0) }.getOrElse { LocalDateTime.now() }} $text"
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { Diag.record("forge-paper", "$LABEL: $text") }
    }

    private fun post(words: String) {
        runCatching { SmartWorkers.found(WHO, Findings.Kind.OTHER, Findings.ALL, words = words.take(300), strength = 50, ttlMs = 24 * 3_600_000L) }
    }

    // ---- Boss's hand and the approve flow ------------------------------------------------------------------------------------

    /** Read the book (the sheet calls it when shown, off the main thread). Approved ideas not yet hosted are taken in. */
    suspend fun refresh(): View {
        if (!ready()) return _view.value
        lock.withLock { val b = book(); if (adopt(b)) save(b) }
        return _view.value
    }

    /** The approve flow's listener: Boss said "start it on paper" for [id] - hosted at once (off the caller's thread). */
    fun onApproved(id: String) {
        if (!ready() || !enabled()) return
        scope.launch { runCatching { host(id) } }
    }

    /** Host the approved idea [id] (by its forge record). What to say. */
    suspend fun host(id: String): String {
        val rec = runCatching { VariantRunner.forge.value.find(id) }.getOrNull()
        return host(rec ?: return "There is no idea called $id.")
    }

    /** Host [rec] (it must be approved for paper, and there must be room). What to say. */
    suspend fun host(rec: StrategyForge.Record): String {
        if (!ready()) return "$LABEL is not ready yet."
        return lock.withLock {
            val b = book()
            val said = hostIn(b, rec)
            save(b)
            said
        }
    }

    private fun hostIn(b: Book, rec: StrategyForge.Record): String {
        val active = b.ideas.filter { it.active }.map { it.id }
        val stopped = b.ideas.filter { !it.active }.map { it.id }
        GrammarPaper.hostRefusal(rec, active, stopped)?.let { return "${rec.c.id} not started on paper: $it." }
        val (expect, n) = GrammarPaper.expectation(rec)
        val day = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        b.ideas += Idea(rec.c.id, rec.c.name, rec.c.underlying, ShadowSim.encode(rec.c.rules), day, expect, n, status = "waiting for its first signal")
        note(b, "${rec.c.id} hosted on paper: ${rec.c.rules.words(rec.c.underlying)} Its forward test: ${GrammarPaper.rs(expect)} a trade over $n trades.")
        post("${rec.c.name} now trades on paper (1 lot, paper only); its record is checked against its forward test every pass.")
        return "${rec.c.id} now trades on paper, 1 lot, by its own rules. Paper only: it never places a live order. " +
            "It stops by itself if it does worse than its forward test (${GrammarPaper.rs(expect)} a trade)."
    }

    /** Every approved idea not hosted yet (and never stopped) taken in while there is room. True when [b] changed. */
    private fun adopt(b: Book): Boolean {
        if (!enabled() || HiddenArms.blocks(com.optionslab.engine.orb.ArmsCleanup.FORGE)) return false
        val forge = runCatching { VariantRunner.forge.value }.getOrNull() ?: return false
        var changed = false
        for (r in forge.records) {
            if (r.stage != StrategyForge.Stage.PAPER || b.ideas.any { it.id.equals(r.c.id, ignoreCase = true) }) continue
            if (b.ideas.count { it.active } >= GrammarPaper.MAX_HOSTED) break
            hostIn(b, r); changed = true
        }
        return changed
    }

    /** Stop [id] (Boss from the sheet, or its own record): no new entries, what it holds sold now. What to say. */
    suspend fun stop(id: String, why: String = "stopped by you"): String {
        if (!ready()) return "$LABEL is not ready yet."
        return lock.withLock {
            val b = book()
            val i = b.ideas.indexOfFirst { it.id.equals(id, ignoreCase = true) && it.active }
            if (i < 0) return@withLock "$id is not on paper."
            stopIn(b, i, why)
            save(b)
            "${b.ideas[i].id} stopped on paper ($why)" + if (b.positions.any { it.idea == b.ideas[i].id && it.open }) "; its open trade is sold on the next pass." else "."
        }
    }

    private suspend fun stopIn(b: Book, i: Int, why: String) {
        val x = b.ideas[i]
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        b.ideas[i] = x.copy(active = false, stoppedOn = today, stopWhy = why, status = "stopped: $why")
        note(b, "${x.id} stopped: $why")
        // Stopping lowers risk: what it holds is sold now (a sale that does not fill is tried again next pass).
        for ((k, p) in b.positions.withIndex().toList()) if (p.idea == x.id && p.open) {
            val c = Paper.contractOf(p.symbol) ?: continue
            val next = runCatching { sell(b, p, c, "idea_stopped") }.getOrDefault(p)
            if (next != p) b.positions[k] = next
        }
    }

    /** The diagnostics line. */
    fun diagLine(): String {
        val v = _view.value
        val act = v.active
        return "$LABEL: ${act.size} of ${GrammarPaper.MAX_HOSTED} on paper" + act.joinToString("") { iv ->
            " · %s %d trades %,.0f".format(Locale.ENGLISH, iv.idea.id, iv.nets.size, iv.nets.sum()) + (if (iv.open != null) " (holding)" else "")
        } + (v.ideas.count { !it.idea.active }.takeIf { it > 0 }?.let { " · $it stopped" } ?: "")
    }

    // ---- the pass ------------------------------------------------------------------------------------------------------------

    /** Called by the market watch (its minute lane and the safety-net round): exits, the judgement, then each idea's entry. */
    suspend fun tick() {
        if (!ready() || !enabled() || !Market.isTradingDay()) return
        lock.withLock {
            val b = book()
            // 15 Oct (Boss's cleanup): hidden, it hosts no new idea and takes no new entry; what it holds still exits by its rules.
            val hidden = HiddenArms.blocks(com.optionslab.engine.orb.ArmsCleanup.FORGE)
            var changed = if (hidden) false else adopt(b)
            if (b.ideas.none { it.active } && b.positions.none { it.open }) { if (changed) save(b); return@withLock }
            val now = Market.now().toLocalDateTime().withNano(0)
            changed = runCatching { exits(b, now) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true } || changed
            changed = judgeAll(b) || changed
            for (i in b.ideas.indices) {
                if (!b.ideas[i].active || hidden) continue
                changed = runCatching { enter(b, i, now) }.getOrElse { note(b, "${b.ideas[i].id}: entry check failed: ${it.javaClass.simpleName}"); true } || changed
            }
            val closed = b.positions.count { !it.open }
            if (closed > CLOSED_KEPT) { var drop = closed - CLOSED_KEPT; b.positions.removeAll { !it.open && drop-- > 0 }; changed = true }
            if (changed) save(b)
        }
    }

    /** Each active idea's paper record against its forward test: worse, or past the capital rule, stops it (and says why). */
    private suspend fun judgeAll(b: Book): Boolean {
        var changed = false
        for (i in b.ideas.indices) {
            val x = b.ideas[i]
            if (!x.active) continue
            val v = GrammarPaper.judge(nets(b, x.id), x.expect) ?: continue
            if (!v.stop) continue
            stopIn(b, i, "auto-stopped: ${v.why}")
            post("${x.name} stopped on paper by itself: ${v.why}. Stopping only lowers risk; it is not started again by itself.")
            changed = true
        }
        return changed
    }

    private fun minuteOf(t: LocalDateTime): Int = t.hour * 60 + t.minute

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /** Today's finished 1-minute bars of [key] (the paper feed's, with the stream's whole minutes over it), oldest first. */
    private suspend fun minutes(key: String, now: LocalDateTime): List<Upstox.Bar> {
        val nowSec = now.atZone(com.optionslab.engine.IST).toEpochSecond()
        val feed = Net.intraday(key).filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
        return LocalCandles.overlay(key, feed, nowSec).filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }
    }

    private fun series(bars: List<Upstox.Bar>): ShadowSim.Series {
        val by = bars.associateBy { it.istMinute }.toSortedMap().values.toList()
        return ShadowSim.Series(IntArray(by.size) { by[it].istMinute }, DoubleArray(by.size) { by[it].open }, DoubleArray(by.size) { by[it].high },
            DoubleArray(by.size) { by[it].low }, DoubleArray(by.size) { by[it].close })
    }

    /** The previous session's high, low and close of [key] (read once a day, only for rules that need them). */
    @Volatile private var prev: Triple<String, Triple<Double, Double, Double>?, LocalDate>? = null

    private suspend fun prevDay(key: String, today: LocalDate): Triple<Double, Double, Double>? {
        prev?.let { if (it.first == key && it.third == today) return it.second }
        val d = runCatching { Net.daily(key, today.minusDays(10), today.minusDays(1)) }.getOrNull()
            ?.filter { it.istDate.isBefore(today) }?.maxByOrNull { it.epochSecond }?.let { Triple(it.high, it.low, it.close) }
        prev = Triple(key, d, today)
        return d
    }

    private fun needsPrev(r: ShadowSim.Rules) = r.entry in setOf(ShadowSim.Entry.LEVEL_SWEEP, ShadowSim.Entry.PREV_DAY_BREAK, ShadowSim.Entry.GAP_FADE)
    private fun needsVix(r: ShadowSim.Rules) = r.entry == ShadowSim.Entry.VIX_DIVERGENCE ||
        r.filters.any { it == ShadowSim.Filter.VIX_LOW || it == ShadowSim.Filter.VIX_HIGH }

    /** Whether the bot is stopped for today (Boss's stop, or the daily loss limit). Unknown counts as stopped. */
    private suspend fun stoppedToday(): Boolean =
        runCatching { Strategies.stoppedToday() }.getOrDefault(true) || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)

    private fun status(b: Book, i: Int, s: String): Boolean {
        if (b.ideas[i].status == s) return false
        b.ideas[i] = b.ideas[i].copy(status = s); return true
    }

    /** Idea [i]'s entry at [now], when its grammar fires and nothing refuses it. True when [b] changed. */
    private suspend fun enter(b: Book, i: Int, now: LocalDateTime): Boolean {
        val x = b.ideas[i]
        if (b.positions.any { it.idea == x.id && it.open }) return false
        val r = rulesOf(x) ?: run { stopIn(b, i, "its rules could not be read"); return true }
        val today = now.toLocalDate()
        val minute = minuteOf(now)
        // Cheap first: outside its window nothing is read.
        if (minute < r.fromMin) return status(b, i, "waiting for ${ShadowSim.hhmm(r.fromMin)}")
        if (minute > r.untilMin + GrammarPaper.LATE_MIN) return status(b, i, "no more entries today (last ${ShadowSim.hhmm(r.untilMin)})")
        val taken = b.positions.count { it.idea == x.id && it.entryDay == today }
        if (taken >= r.maxTrades) return status(b, i, "done for today ($taken of ${r.maxTrades})")
        val u = x.underlying
        val indexKey = Upstox.INDEX_KEYS[u] ?: return status(b, i, "no index feed for $u")
        val bars = minutes(indexKey, now)
        if (bars.isEmpty()) return status(b, i, "no index minutes yet")
        val listed = Market.contracts().filter { it.underlying == u }
        val expiry = com.optionslab.engine.orb.OrbRules.expiryOnOrAfter(today, listed.map { it.expiry }.distinct())
            ?: return status(b, i, "no listed expiry for $u")
        val p = if (needsPrev(r)) prevDay(indexKey, today) else null
        val vix = if (needsVix(r)) Upstox.INDEX_KEYS["INDIAVIX"]?.let { k -> runCatching { series(minutes(k, now)) }.getOrNull() } else null
        val busy = runCatching { SmartWorkers.brain.value.regimes[u]?.state }.getOrNull()?.let {
            when (it) { com.optionslab.ira.MarketBrain.State.BUSY -> true; com.optionslab.ira.MarketBrain.State.QUIET -> false; else -> null }
        }
        val day = ShadowSim.Day(today, u, listed.firstOrNull { it.expiry == expiry }?.lotSize ?: ShadowSim.lotOf(u), expiry == today, series(bars),
            emptyList(), p?.first, p?.second, p?.third, vix, busy)
        val last = if (x.decisionDay == today) x.lastDecision else -1
        val sig = GrammarPaper.signal(r, day, minute, taken, last) ?: return status(b, i, "watching (${taken} of ${r.maxTrades} today)")
        // Looked at once: a refused decision is not tried again on the next pass.
        b.ideas[i] = x.copy(lastDecision = sig.decisionMin, decisionDay = today)
        val facts = "${ShadowSim.hhmm(sig.decisionMin)}: ${r.entry.words} ${if (sig.dir > 0) "up" else "down"}, $u ${"%.2f".format(Locale.ENGLISH, sig.indexClose)}"
        fun refused(why: String): Boolean { b.ideas[i] = b.ideas[i].copy(status = "refused: $why"); note(b, "${x.id} $facts: refused ($why)"); return true }
        // The guards, read only now (a decision is rare): the kill switch and the account's cut-off are settings.
        val s = runCatching { AppSettings.load() }.getOrNull()
        GrammarPaper.entryRefusal(today, minute, r, s?.guardCutoff?.takeIf { it >= 0 }, s?.guardKill ?: true, stoppedToday(),
            runCatching { DayLockGuard.refusal(false) }.getOrNull(), expiry == today)?.let { return refused(it) }
        val fresh = bars.last().let { !PaperSpread.isStale(it.epochSecond, now.atZone(com.optionslab.engine.IST).toEpochSecond()) }
        if (!fresh) return refused("stale_index_price")
        val strike = GrammarPaper.strike(sig.indexClose, ShadowSim.stepOf(u), sig.dir, r.itm)
        val right = if (sig.dir > 0) Right.CE else Right.PE
        val c = Paper.contractFor(u, expiry, strike, right) ?: return refused("no_contract $u ${strike.toInt()} ${right.name}")
        // The account-wide guard (paper: it refuses nothing today, but every entry asks it).
        Guard.check(Guard.paperOrder(c, "BUY", 1, 0.0), null, exit = false, paper = true).takeIf { it.isNotEmpty() }
            ?.let { return refused("account guard: " + it.joinToString(" ")) }
        runCatching { LiveRecorder.signal("Grammar ${x.id}", c.symbol, u, right.name, null, paper = true) }
        val placed = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, auto = LABEL, mirror = ExposureCapHost.NO_LIVE)
        val fill = placed.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null) {
            placed.orderId?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            return refused("not_filled: ${placed.message}")
        }
        placed.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${x.id} (forge, paper) · entry") } }
        // The premium stop rests in the paper book: it fills on a minute's low (the wick), as the forge's walk settles it.
        val stopLevel = GrammarPaper.levels(r, fill.price).first
        val stopId = if (!(stopLevel > 0)) null else runCatching {
            Paper.place(c, "SELL", 1, "SL-M", "MIS", null, Math.floor(stopLevel / TICK + 1e-9) * TICK)
        }.getOrNull()?.takeIf { it.ok }?.orderId?.also { runCatching { Strategies.tagOwner("paper:$it", "${x.id} (forge, paper) · stop") } }
        b.positions += Pos(x.id, u, c.symbol, right.name, fill.quantity, fill.price, today, now, sig.decisionMin, sig.indexClose, expiry,
            placed.orderId, stopId, charges = chargesOf(placed.orderId))
        b.ideas[i] = b.ideas[i].copy(status = "bought ${c.symbol} @ ${"%.2f".format(Locale.ENGLISH, fill.price)}")
        note(b, "${x.id} $facts: bought ${c.symbol} ${fill.quantity} @ ${"%.2f".format(Locale.ENGLISH, fill.price)} · paper")
        runCatching { Notifier.post(app, 6995 + i, Notifier.BUY, "${x.id} bought ${c.symbol} · Paper",
            "Bought ${fill.quantity} @ ${"%.2f".format(Locale.ENGLISH, fill.price)} ($facts). A forge idea on paper only, not proven.", "almanac") }
        return true
    }

    /** Each open position's exit, when due. True when [b] changed. */
    private suspend fun exits(b: Book, now: LocalDateTime): Boolean {
        var changed = false
        val stopped = stoppedToday()
        val minute = minuteOf(now)
        for ((k, p) in b.positions.withIndex().toList()) {
            if (!p.open) continue
            val c = Paper.contractOf(p.symbol) ?: continue
            val x = b.ideas.firstOrNull { it.id == p.idea }
            // Its resting stop filled in the paper book (on a minute's low): that is the exit.
            val so = p.stopOrderId?.let { id -> Paper.state.orders.firstOrNull { it.orderId == id } }
            if (so != null && so.status == "complete") {
                val done = p.copy(exit = so.averagePrice?.toDouble() ?: p.entry, exitTime = so.updateTimestamp, why = "stop", stopOrderId = null,
                    charges = p.charges + chargesOf(so.orderId))
                b.positions[k] = done; closed(b, done); changed = true; continue
            }
            // Gone from the paper book without this arm selling it: the expiry or MIS square-off, the loss limit, or Boss.
            val held = Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity }
            if (held <= 0) {
                p.stopOrderId?.let { runCatching { Paper.cancel(it, "position_closed") } }
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
                val done = p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: now, why = "closed_elsewhere", stopOrderId = null,
                    charges = p.charges + (t?.charges?.toDouble() ?: 0.0))
                b.positions[k] = done; closed(b, done); changed = true; continue
            }
            val r = rulesOf(x)
            val why = when {
                x == null || !x.active -> "idea_stopped"
                r == null -> "rules_unreadable"
                stopped -> "operator_stop"
                now.toLocalDate().isAfter(p.entryDay) -> "eod"
                else -> {
                    val opt = runCatching { minutes(c.feedKey, now) }.getOrNull().orEmpty()
                        .map { GrammarPaper.Minute(it.istMinute, it.high, it.low, it.close) }
                    val ix = Upstox.INDEX_KEYS[p.index]?.let { key -> runCatching { minutes(key, now).lastOrNull()?.close }.getOrNull() }
                    // Walked from the first minute after the fill (not the decision minute: a dip before the fill is no stop).
                    GrammarPaper.exit(r, now.toLocalDate(), GrammarPaper.Held(p.entry, p.decisionMin, if (p.right == "PE") -1 else 1, p.indexAtEntry,
                        p.expiry == p.entryDay, fromMin = GrammarPaper.firstAfterFill(p.entryTime.toLocalTime())), opt, ix, minute)
                }
            } ?: TradeManagerHost.exitDue(managerId(p))?.let { "trade_manager" } ?: continue   // ACT only; SHADOW here: never
            val next = sell(b, p, c, why)
            if (next != p) { b.positions[k] = next; changed = true }
        }
        return changed
    }

    /** Sell [p] at market on paper (its resting stop taken out first; if that stop filled meanwhile, that is the exit). */
    private suspend fun sell(b: Book, p: Pos, c: Paper.Contract, why: String): Pos {
        p.stopOrderId?.let { id ->
            runCatching { Paper.cancel(id, "exit:$why") }
            val so = Paper.state.orders.firstOrNull { it.orderId == id }
            if (so?.status == "complete") {
                val done = p.copy(exit = so.averagePrice?.toDouble() ?: p.entry, exitTime = so.updateTimestamp, why = "stop", stopOrderId = null,
                    charges = p.charges + chargesOf(id))
                closed(b, done); return done
            }
        }
        // 22 Oct (arms audit): an exit only reduces what is held - never a paper short when the square-off got there first.
        val r = Paper.place(c, "SELL", (p.qty / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1), "MARKET", "MIS", null, null, exit = true)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null) {
            if (!r.ok && Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } <= 0) {
                // Already flat in the paper book (the 15:15 square-off, the sweeper or Boss): booked at that sale's price.
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
                val done = p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: Market.now().toLocalDateTime().withNano(0),
                    why = "closed_elsewhere", stopOrderId = null, charges = p.charges + (t?.charges?.toDouble() ?: 0.0))
                closed(b, done); return done
            }
            r.orderId?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            note(b, "${p.symbol}: the $why exit did not fill (${r.message}); tried again next pass")
            return p.copy(stopOrderId = null)
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${p.idea} (forge, paper) · exit") } }
        val done = p.copy(exit = fill.price, exitTime = Market.now().toLocalDateTime().withNano(0), why = why, stopOrderId = null,
            charges = p.charges + chargesOf(r.orderId))
        closed(b, done)
        return done
    }

    private fun closed(b: Book, p: Pos) {
        val i = b.ideas.indexOfFirst { it.id == p.idea }
        if (i >= 0 && b.ideas[i].active) b.ideas[i] = b.ideas[i].copy(status = "closed ${p.symbol}: ${p.why}")
        note(b, "${p.idea} ${p.symbol}: sold (${p.why}) @ ${"%.2f".format(Locale.ENGLISH, p.exit ?: 0.0)}, ${GrammarPaper.rs(p.net ?: 0.0)} after charges · paper")
        runCatching { Notifier.post(app, 6995 + i.coerceAtLeast(0), Notifier.SELL, "${p.idea} sold ${p.symbol} · Paper",
            "Sold (${p.why}) @ ${"%.2f".format(Locale.ENGLISH, p.exit ?: 0.0)}: ${GrammarPaper.rs(p.net ?: 0.0)} after charges. Paper only.", "almanac") }
    }

    /** TEST ONLY: closed paper trades of [id] with these rupees (a record to judge), as if traded on [day]. */
    internal suspend fun recordForTest(id: String, nets: List<Double>, day: LocalDate) {
        lock.withLock {
            val b = book()
            for ((k, n) in nets.withIndex()) {
                val t = day.atTime(10, 0).plusMinutes(k.toLong())
                b.positions += Pos(id, "BANKNIFTY", "TEST$k", "CE", 1, 100.0, day, t, 600, 50_000.0, day.plusDays(7), null,
                    exit = 100.0 + n, exitTime = t.plusMinutes(1), why = "test")
            }
            save(b)
        }
    }

    /** For tests (and AreaE's reset): the book forgotten (its file goes with the test's directory). */
    internal fun wipe() { cache = null; prev = null; _view.value = View() }
}
