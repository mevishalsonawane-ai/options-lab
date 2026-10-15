package com.optionslab.app.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.optionslab.app.security.Vault
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.signals.CallCheck
import com.optionslab.engine.signals.CallEval
import com.optionslab.engine.signals.CallModel
import com.optionslab.engine.signals.CallParser
import com.optionslab.engine.signals.CallParser.Draft
import com.optionslab.engine.signals.CallParser.Follow
import com.optionslab.engine.signals.CallParser.Reading
import com.optionslab.engine.signals.CallReader
import com.optionslab.engine.signals.ChannelScore
import com.optionslab.engine.signals.TelegramExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Signal tester (15 Oct 2026, Boss: Telegram channels post option calls a few minutes before the move; "capture such calls,
 * study them"). SHADOW ONLY - "shadow — never trades": it records every call from the channels Boss allow-listed and scores
 * each channel honestly. Nothing here places, changes, skips or resizes an order, paper or live; it has no path to the order
 * book, Zerodha or the paper account at all, so the kill switch is irrelevant to it. Trading on these calls would be a later,
 * separate decision, gated by research and Boss's PIN rules.
 *
 * Capture (opt-in; off by default; nothing uploaded anywhere):
 *  - [TelegramCallListener]: Telegram's notifications only, from chats whose title is on the allow-list ([accepts], decided
 *    in memory - everything else is dropped at once and never stored), timed to the second;
 *  - [SignalShareActivity]: Boss shares a message from Telegram (time = now, "shared (time approximate)");
 *  - the card's paste box (an editable time) and the import of a Telegram Desktop export (result.json: [import]).
 * Understanding ([ingest]): the rules ([CallParser]); when they are unsure, the on-device language model ([CallModel], Jarvis's
 * model, in its own background job with a timeout - never on the main thread or the tick / order path, and not while a position
 * or order is open: positions come first); when the model is not on the phone, the rules' reading waits in "Needs review". Then
 * each call is checked against reality ([CallCheck]: the strike grid, the distance from spot, the numbers) before it counts.
 * Boss's corrections ([confirm]) are kept per channel and given to the model as examples next time.
 * Evaluation ([round], the words lane, about a minute apart): each live call's option minutes (only while the call is live;
 * the existing feed and its limits) and the underlying's, priced by [CallEval] with the paper costs; per channel a scorecard
 * ([ChannelScore]); at 40 triggered calls Jarvis says once whether the channel beat random entries - never a promotion.
 * Privacy: the log is its own encrypted file in the no-backup folder ([FILE]): not in the phone backup, not in the GitHub
 * uploads (which go to IraAlgo only), not in the diagnostics (the diary gets counts only, never a channel name or text). The
 * message text is kept only for calls and follow-ups (and while a message waits for review); a not-a-call keeps its reason only.
 */
object SignalTesterHost {
    const val NOTE = CallParser.NOTE
    const val FILE = "signals.vault"
    const val KEEP = 3000
    const val MAX_CHANNELS = 40
    const val MAX_EXAMPLES = 8
    /** Live calls priced per round at most (their option minutes read once a round each). */
    const val MAX_LIVE = 8
    /** Telegram's apps whose notifications may be read (the official app and its known clients); no other app's ever. */
    val TELEGRAM: Set<String> = setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "org.telegram.plus")

    const val CALL = "call"
    const val FOLLOW = "follow"
    const val NOT = "not"
    const val REVIEW = "review"
    const val PENDING = "pending"

    const val NOTIFICATION = "notification"
    const val SHARED = "shared"
    const val PASTED = "pasted"
    const val IMPORTED = "imported"

    @Volatile private var file: File? = null
    @Volatile private var app: Context? = null
    private val written = Vault.LastWrite()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        app = context.applicationContext
        // Files only, no I/O here: start-up restores and watches the open positions first. The log is read on first use, in the
        // background (a notification arriving before it is in is checked there, never on the main thread).
        file = File(context.applicationContext.noBackupFilesDir, FILE)
    }

    /** One message as recorded. [text]: kept for calls, follow-ups and messages waiting for review only. */
    data class Rec(
        val id: String, val channel: String, val at: LocalDateTime, val source: String, val approx: Boolean = false, val edited: Boolean = false,
        val kind: String, val text: String? = null, val hash: Int = 0, val reason: String? = null, val by: String = "rules",
        val confidence: Double = 1.0, val unsure: List<String> = emptyList(), val draft: Draft? = null,
        val follow: Reading.FollowUp? = null, val link: String? = null, val claim: Boolean? = null,
        val expiry: LocalDate? = null, val symbol: String? = null, val key: String? = null, val lot: Int? = null,
        val checked: Boolean = false, val result: CallEval.Result? = null, val twins: List<Double> = emptyList(), val unpriced: String? = null,
        val twinsDone: Boolean = false,
    ) {
        /** Nothing more to price: not a call, a call that could not be priced, or its result is final (a finished trade's twins in). */
        val final: Boolean get() = kind != CALL || unpriced != null ||
            (result?.status in FINAL && (result?.status != CallEval.Status.DONE || twinsDone))
        val sourceWords: String get() = when (source) {
            SHARED -> "shared (time approximate)"; PASTED -> "pasted" + if (approx) " (time approximate)" else ""; IMPORTED -> "imported"; else -> "notification"
        }
    }

    private val FINAL = setOf(CallEval.Status.DONE, CallEval.Status.NOT_TRIGGERED, CallEval.Status.NO_DATA, CallEval.Status.CLOSED)

    data class View(val on: Boolean = false, val listen: Boolean = false, val allow: List<String> = emptyList(), val delaySec: Int = 30,
                    val validityMin: Int = 15, val cards: List<ChannelScore.Card> = emptyList(), val recs: List<Rec> = emptyList(),
                    val review: List<Rec> = emptyList(), val pending: Int = 0) {
        fun callsOf(channel: String): List<Rec> = recs.filter { it.channel == channel }.reversed()
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    private val lock = Any()
    private class Log(var on: Boolean = false, var listen: Boolean = false, val allow: MutableList<String> = ArrayList(), var delaySec: Int = 30,
                      var validityMin: Int = 15, val recs: MutableList<Rec> = ArrayList(),
                      val examples: MutableMap<String, MutableList<CallModel.Example>> = LinkedHashMap(), val told: MutableSet<String> = HashSet(),
                      var seq: Long = 0)
    private var cache: Log? = null

    /** What the notification listener checks in memory: the switches and the allow-list (normalised). */
    private data class Gate(val on: Boolean, val listen: Boolean, val allow: Set<String>)
    @Volatile private var gate: Gate? = null

    // ---- the log -------------------------------------------------------------------------------------------------------

    private fun draftJson(d: Draft) = JSONObject().apply {
        d.underlying?.let { put("u", it) }; d.strike?.let { put("k", it) }; d.right?.let { put("r", it.name) }; d.expiry?.let { put("e", it.toString()) }
        put("t", d.trig.key); d.trigger?.let { put("p", it) }; d.triggerHigh?.let { put("ph", it) }; d.stop?.let { put("s", it) }
        d.stopPct?.let { put("sp", it) }; put("tg", JSONArray(d.targets))
    }

    private fun dbl(o: JSONObject, k: String): Double? = if (o.has(k)) o.getDouble(k) else null

    private fun draftOf(o: JSONObject?): Draft? = o?.let {
        Draft(it.optString("u").ifEmpty { null }, dbl(it, "k"), it.optString("r").ifEmpty { null }?.let { r -> Right.valueOf(r) },
            it.optString("e").ifEmpty { null }?.let { e -> LocalDate.parse(e) }, CallParser.Trig.of(it.optString("t")), dbl(it, "p"), dbl(it, "ph"),
            dbl(it, "s"), dbl(it, "sp"), it.optJSONArray("tg")?.let { a -> (0 until a.length()).map { i -> a.getDouble(i) } }.orEmpty())
    }

    private fun followJson(f: Reading.FollowUp) = JSONObject().put("k", f.kind.key).put("partial", f.partial).apply {
        f.targetNo?.let { put("n", it) }; f.level?.let { put("lvl", it) }; f.ref?.let { put("ref", draftJson(it)) }
    }

    private fun followOf(o: JSONObject?): Reading.FollowUp? = o?.let {
        val k = Follow.of(it.optString("k")) ?: return null
        Reading.FollowUp(k, if (it.has("n")) it.getInt("n") else null, dbl(it, "lvl"), it.optBoolean("partial"), draftOf(it.optJSONObject("ref")))
    }

    private fun legJson(l: CallEval.Leg) = JSONObject().put("ea", l.entryAt.toString()).put("er", l.entryRaw).put("ef", l.entryFill).put("xa", l.exitAt.toString())
        .put("xr", l.exitRaw).put("xf", l.exitFill).put("why", l.why).put("q", l.qty).put("ch", l.charges)

    private fun legOf(v: CallEval.Variant, o: JSONObject) = CallEval.Leg(v, LocalDateTime.parse(o.getString("ea")), o.getDouble("er"), o.getDouble("ef"),
        LocalDateTime.parse(o.getString("xa")), o.getDouble("xr"), o.getDouble("xf"), o.getString("why"), o.getInt("q"), o.getDouble("ch"))

    private fun resultJson(r: CallEval.Result) = JSONObject().put("st", r.status.key).apply {
        r.note?.let { put("note", it) }; r.fillAt?.let { put("fa", it.toString()) }; r.fillRaw?.let { put("fr", it) }
        r.pre?.let { p -> put("pre", JSONObject().put("above", p.alreadyAbove).apply { p.ltp?.let { put("ltp", it) }; p.optionMove3?.let { put("om", it) }; p.underlyingMove3?.let { put("um", it) } }) }
        put("legs", JSONObject().apply { r.legs.forEach { (v, l) -> put(v.key, legJson(l)) } })
    }

    private fun resultOf(o: JSONObject?): CallEval.Result? = o?.let {
        val st = CallEval.Status.of(it.optString("st")) ?: return null
        val pre = it.optJSONObject("pre")?.let { p -> CallEval.Pre(dbl(p, "ltp"), dbl(p, "om"), dbl(p, "um"), p.optBoolean("above")) }
        val legs = LinkedHashMap<CallEval.Variant, CallEval.Leg>()
        it.optJSONObject("legs")?.let { m -> m.keys().forEach { k -> CallEval.Variant.of(k)?.let { v -> legs[v] = legOf(v, m.getJSONObject(k)) } } }
        CallEval.Result(st, pre, it.optString("fa").ifEmpty { null }?.let { a -> LocalDateTime.parse(a) }, dbl(it, "fr"), legs, it.optString("note").ifEmpty { null })
    }

    private fun recJson(r: Rec) = JSONObject().put("id", r.id).put("ch", r.channel).put("at", r.at.toString()).put("src", r.source).put("kind", r.kind)
        .put("by", r.by).put("conf", r.confidence).put("h", r.hash).put("checked", r.checked).apply {
            if (r.approx) put("approx", true); if (r.edited) put("edited", true)
            r.text?.let { put("text", it) }; r.reason?.let { put("reason", it) }
            if (r.unsure.isNotEmpty()) put("unsure", JSONArray(r.unsure))
            r.draft?.let { put("d", draftJson(it)) }; r.follow?.let { put("f", followJson(it)) }; r.link?.let { put("link", it) }; r.claim?.let { put("claim", it) }
            r.expiry?.let { put("exp", it.toString()) }; r.symbol?.let { put("sym", it) }; r.key?.let { put("key", it) }; r.lot?.let { put("lot", it) }
            r.result?.let { put("res", resultJson(it)) }; if (r.twins.isNotEmpty()) put("tw", JSONArray(r.twins)); r.unpriced?.let { put("unpriced", it) }
            if (r.twinsDone) put("twd", true)
        }

    private fun strs(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) } }.orEmpty()

    private fun recOf(o: JSONObject): Rec = Rec(
        o.getString("id"), o.getString("ch"), LocalDateTime.parse(o.getString("at")), o.optString("src", NOTIFICATION), o.optBoolean("approx"), o.optBoolean("edited"),
        o.getString("kind"), o.optString("text").ifEmpty { null }, o.optInt("h"), o.optString("reason").ifEmpty { null }, o.optString("by", "rules"),
        o.optDouble("conf", 1.0), strs(o.optJSONArray("unsure")), draftOf(o.optJSONObject("d")), followOf(o.optJSONObject("f")),
        o.optString("link").ifEmpty { null }, if (o.has("claim")) o.getBoolean("claim") else null,
        o.optString("exp").ifEmpty { null }?.let { LocalDate.parse(it) }, o.optString("sym").ifEmpty { null }, o.optString("key").ifEmpty { null },
        if (o.has("lot")) o.getInt("lot") else null, o.optBoolean("checked"), resultOf(o.optJSONObject("res")),
        o.optJSONArray("tw")?.let { a -> (0 until a.length()).map { a.getDouble(it) } }.orEmpty(), o.optString("unpriced").ifEmpty { null },
        o.optBoolean("twd"))

    private fun log(): Log {
        cache?.let { return it }
        val l = Log()
        val f = file
        val ok = f == null || runCatching {
            val o = JSONObject(String(Vault.readFileSteady(f) ?: return@runCatching, Charsets.UTF_8))
            l.on = o.optBoolean("on"); l.listen = o.optBoolean("listen")
            l.allow += strs(o.optJSONArray("allow"))
            l.delaySec = o.optInt("delay", 30).takeIf { it in CallEval.Settings.DELAYS } ?: 30
            l.validityMin = o.optInt("validity", 15).coerceIn(1, 120)
            l.seq = o.optLong("seq")
            o.optJSONArray("recs")?.let { a -> for (i in 0 until a.length()) runCatching { recOf(a.getJSONObject(i)) }.getOrNull()?.let { l.recs += it } }
            o.optJSONObject("ex")?.let { m -> m.keys().forEach { ch ->
                val a = m.getJSONArray(ch)
                l.examples[ch] = (0 until a.length()).map { i -> a.getJSONObject(i).let { e -> CallModel.Example(e.getString("t"), e.getString("j")) } }.toMutableList()
            } }
            l.told += strs(o.optJSONArray("told"))
        }.isSuccess
        if (!ok && f != null && f.exists()) runCatching { Vault.setAside(f) }
        cache = l
        publish(l)
        return l
    }

    private fun save(l: Log) {
        if (l.recs.size > KEEP) {
            // The oldest by their own time go first (an import of last year never pushes out this week's live calls).
            val drop = l.recs.sortedBy { it.at }.take(l.recs.size - KEEP).map { it.id }.toSet()
            l.recs.removeAll { it.id in drop }
        }
        val f = file
        if (f != null) runCatching {
            val o = JSONObject().put("on", l.on).put("listen", l.listen).put("allow", JSONArray(l.allow)).put("delay", l.delaySec).put("validity", l.validityMin)
                .put("seq", l.seq).put("recs", JSONArray(l.recs.map { recJson(it) })).put("told", JSONArray(l.told.sorted()))
                .put("ex", JSONObject().apply { l.examples.forEach { (ch, xs) -> put(ch, JSONArray(xs.map { JSONObject().put("t", it.text).put("j", it.json) })) } })
            written.write(f, o.toString().toByteArray(Charsets.UTF_8))
        }
        publish(l)
    }

    private fun publish(l: Log) {
        gate = Gate(l.on, l.listen, l.allow.map { norm(it) }.toSet())
        _view.value = View(l.on, l.listen, l.allow.toList(), l.delaySec, l.validityMin, cardsOf(l), l.recs.takeLast(400),
            l.recs.filter { it.kind == REVIEW }, l.recs.count { it.kind == PENDING })
    }

    private fun cardsOf(l: Log): List<ChannelScore.Card> = l.allow.map { ch -> ChannelScore.card(ch, l.recs.filter { it.channel == ch }.map { item(it) }) }

    private fun item(r: Rec): ChannelScore.Item = when (r.kind) {
        CALL -> {
            val res = r.result
            val trig = when (res?.status) { CallEval.Status.DONE -> true; CallEval.Status.NOT_TRIGGERED -> false; else -> null }
            ChannelScore.Item(ChannelScore.Kind.CALL, r.by != "you", trig, res?.legs?.mapValues { it.value.net }.orEmpty(),
                res?.pre?.movedBefore, res?.pre?.underlyingMove3, r.twins, r.edited)
        }
        FOLLOW -> ChannelScore.Item(ChannelScore.Kind.FOLLOW_UP, r.by != "you")
        NOT -> ChannelScore.Item(ChannelScore.Kind.NOT_A_CALL)
        else -> ChannelScore.Item(ChannelScore.Kind.REVIEW)
    }

    /** A chat title as compared with the allow-list: lower case, letters and digits, single spaces (emojis and marks dropped). */
    fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // ---- the switches and the allow-list (off the main thread) ------------------------------------------------------------

    fun on(): Boolean = synchronized(lock) { log().on }

    /** The tester's switch (off by default): off, nothing more is recorded and the listener drops everything. */
    fun setOn(on: Boolean): String = synchronized(lock) {
        val l = log()
        if (l.on != on) { l.on = on; save(l) }
        if (on) "Signal tester on ($NOTE)." else "Signal tester off: nothing more is recorded."
    }

    /**
     * Reading Telegram's notifications (off by default): the listener component is enabled only while this is on (Android then
     * lists IraAlgo under Notification access, where Boss grants it himself); off, the component is disabled.
     */
    fun setListen(context: Context, on: Boolean): String {
        synchronized(lock) { val l = log(); if (l.listen != on) { l.listen = on; save(l) } }
        runCatching {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, TelegramCallListener::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        return if (on) "Reading Telegram notifications from your allow-listed channels only. Grant IraAlgo notification access in the next screen."
        else "Not reading Telegram notifications."
    }

    /** Notification access granted to IraAlgo in Android's settings. */
    fun accessGranted(context: Context): Boolean = runCatching {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    fun allow(name: String): String = synchronized(lock) {
        val n = name.trim().take(80)
        val l = log()
        when {
            norm(n).isEmpty() -> "Type the channel's name as Telegram shows it."
            l.allow.any { norm(it) == norm(n) } -> "\"$n\" is already on the list."
            l.allow.size >= MAX_CHANNELS -> "The list holds $MAX_CHANNELS channels at most."
            else -> { l.allow += n; save(l); "Added \"$n\"." }
        }
    }

    /** Off the allow-list: nothing more is recorded from it (what is recorded stays, for its scorecard). */
    fun disallow(name: String): String = synchronized(lock) {
        val l = log()
        if (l.allow.removeAll { it == name }) save(l)
        "Removed \"$name\": nothing more is recorded from it."
    }

    fun setDelay(sec: Int) {
        synchronized(lock) {
            val l = log()
            if (sec in CallEval.Settings.DELAYS && l.delaySec != sec) { l.delaySec = sec; reprice(l); save(l) }
        }
    }

    fun setValidity(min: Int) {
        synchronized(lock) {
            val l = log()
            val m = min.coerceIn(1, 120)
            if (l.validityMin != m) { l.validityMin = m; reprice(l); save(l) }
        }
    }

    /**
     * A new reaction delay or window: the calls whose option can still be read (its expiry not past) are priced again by the
     * next rounds; an expired one keeps the result it was priced with (its prices cannot be read again from the free feed).
     */
    private fun reprice(l: Log) {
        val today = runCatching { Market.today() }.getOrDefault(LocalDate.now())
        for (i in l.recs.indices) {
            val r = l.recs[i]
            val readable = r.expiry == null || !r.expiry.isBefore(today)
            if (r.kind == CALL && r.result != null && readable) l.recs[i] = r.copy(result = null, twins = emptyList(), twinsDone = false)
        }
    }

    // ---- capture ---------------------------------------------------------------------------------------------------------

    /**
     * The listener's check, in memory only (no disk, no network): a Telegram app, the tester and the reading on, a title on the
     * allow-list. Null: not known yet (the log not read); the message then goes to the background job, which checks again.
     */
    fun accepts(pkg: String, title: String?): Boolean? {
        if (pkg !in TELEGRAM || title.isNullOrBlank()) return false
        val g = gate ?: return null
        return g.on && g.listen && channelOf(title, g.allow) != null
    }

    /** The allow-listed title [title] stands for ("Sensex Kings", or a group's "Ravi @ Sensex Kings"), normalised, or null. */
    private fun channelOf(title: String, allow: Set<String>): String? {
        val t = norm(title)
        if (t in allow) return t
        val after = norm(title.substringAfterLast(" @ ", ""))
        return after.takeIf { it.isNotEmpty() && it in allow }
    }

    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * From the listener ([TelegramCallListener]): the chat's [title] and its messages (text, epoch ms). Only Telegram's apps,
     * only allow-listed titles; each message once (a notification re-posted with older lines adds only the new ones).
     */
    fun fromNotification(pkg: String, title: String?, messages: List<Pair<String, Long>>) {
        val ok = accepts(pkg, title)
        if (ok == false || title == null) return
        val seenKey = norm(title)
        val fresh = messages.filter { (text, ms) -> text.isNotBlank() && ms > (lastSeen[seenKey] ?: 0L) }
        if (fresh.isEmpty()) return
        lastSeen[seenKey] = fresh.maxOf { it.second }
        // One background job, the lines in order (a follow-up after its call).
        scope.launch {
            for ((text, ms) in fresh.sortedBy { it.second }) runCatching {
                ingest(title, text, Instant.ofEpochMilli(ms).atZone(com.optionslab.engine.IST).toLocalDateTime().withNano(0), NOTIFICATION)
            }
        }
    }

    private fun nextId(l: Log): String { l.seq += 1; return "s${l.seq}" }

    /**
     * One message into the log (tests call it directly). Dropped unless the tester is on and [title] is allow-listed; a repeat of
     * the same text from the same channel within 3 minutes is dropped. Returns the records made.
     */
    internal fun ingest(title: String, text: String, at: LocalDateTime, source: String, approx: Boolean = false, edited: Boolean = false,
                        persist: Boolean = true): List<Rec> = synchronized(lock) {
        val l = log()
        if (!l.on) return emptyList()
        val key = channelOf(title, l.allow.map { norm(it) }.toSet()) ?: return emptyList()
        val ch = l.allow.first { norm(it) == key }
        val body = text.trim().take(4000)
        val h = body.hashCode()
        if (l.recs.any { it.channel == ch && it.hash == h && kotlin.math.abs(java.time.Duration.between(it.at, at).seconds) <= 180 }) return emptyList()
        val base = Rec(nextId(l), ch, at, source, approx, edited, PENDING, body, h)
        val rules = CallParser.read(body, at.toLocalDate())
        val out = if (CallReader.needsModel(rules)) listOf(base)
        else finish(l, base, CallReader.understand(body, at.toLocalDate()))
        l.recs += out
        if (persist) {
            save(l)
            if (out.any { it.kind == PENDING }) kickModel()
        }
        out
    }

    /** [base]'s understood readings as records: sure calls and follow-ups count; the rest waits for review; not-a-call keeps no text. */
    private fun finish(l: Log, base: Rec, merged: List<CallModel.Merged>): List<Rec> = merged.mapIndexed { i, m ->
        val id = if (merged.size == 1) base.id else "${base.id}.${i + 1}"
        val r = base.copy(id = id, by = m.by, confidence = m.confidence, unsure = m.unsure)
        when (val x = m.reading) {
            is Reading.NewCall -> r.copy(kind = if (m.sure) CALL else REVIEW, draft = x.draft)
            is Reading.FollowUp -> r.copy(kind = if (m.sure) FOLLOW else REVIEW, follow = x,
                link = CallReader.link(x, base.at, openCalls(l, base.channel)))
            is Reading.NotACall -> if (m.sure) r.copy(kind = NOT, text = null, reason = x.reason) else r.copy(kind = REVIEW, reason = x.reason)
        }
    }

    private fun openCalls(l: Log, ch: String) = l.recs.filter { it.channel == ch && it.kind == CALL && it.draft != null }.map { CallReader.Open(it.id, it.draft!!, it.at) }

    // ---- stage (b): the language model, its own background job ------------------------------------------------------------

    /** TEST SEAM: the model (prompt -> reply); [testModelMissing]: as if the model were not on the phone. */
    @Volatile internal var testModel: (suspend (String) -> String?)? = null
    @Volatile internal var testModelMissing: Boolean = false
    /** TEST SEAM: a position or order open (positions come first: the model waits). */
    @Volatile internal var testHolding: Boolean? = null

    private val modelBusy = AtomicBoolean(false)

    private fun holding(): Boolean = testHolding ?: (runCatching { SmartWorkers.holdingAny() }.getOrDefault(true) || com.optionslab.app.work.TradingBusy.active)

    private fun model(): (suspend (String) -> String?)? {
        if (testModelMissing) return null
        testModel?.let { return it }
        if (!com.optionslab.app.BuildConfig.JARVIS || !runCatching { com.optionslab.app.ira.IraModel.usable() }.getOrDefault(false)) return null
        return { p -> com.optionslab.app.ira.IraModel.complete(p, CallModel.MAX_TOKENS, CallModel.TIMEOUT_MS) }
    }

    /** Starts the model's pass in the background unless one runs. */
    private fun kickModel() {
        if (!modelBusy.compareAndSet(false, true)) return
        scope.launch { try { runCatching { modelPass() } } finally { modelBusy.set(false) } }
    }

    /**
     * The messages waiting for the model, read one by one: with the channel's confirmed examples, within [CallModel.TIMEOUT_MS].
     * Not while a position or order is open (they wait for the next pass); without the model, each waits in "Needs review" with
     * the rules' reading. Returns how many were read.
     */
    internal suspend fun modelPass(): Int {
        val todo = synchronized(lock) { log().recs.filter { it.kind == PENDING } }
        if (todo.isEmpty()) return 0
        val m = model()
        var n = 0
        for (r in todo) {
            // Positions first: a position or order opening mid-pass stops it at once (the rest wait for the next pass).
            if (m != null && holding()) break
            val text = r.text ?: continue
            val ex = synchronized(lock) { log().examples[r.channel].orEmpty().toList() }
            val reply = if (m == null) null else withTimeoutOrNull(CallModel.TIMEOUT_MS + 2_000) { runCatching { m(CallModel.prompt(text, ex)) }.getOrNull() }
            val merged = CallReader.understand(text, r.at.toLocalDate(), modelReply = reply, modelMissing = m == null)
            synchronized(lock) {
                val l = log()
                val i = l.recs.indexOfFirst { it.id == r.id && it.kind == PENDING }
                if (i >= 0) {
                    val made = finish(l, r, merged)
                    l.recs.removeAt(i); l.recs.addAll(i, made)
                    save(l)
                    n++
                }
            }
        }
        return n
    }

    // ---- Needs review ------------------------------------------------------------------------------------------------------

    /**
     * Boss's reading of a message in review: [d] the call as it should read (null: not a call). Kept as an example for that
     * channel's next model prompts. A call needs its index or stock, strike and CE / PE (nothing is filled in for him).
     */
    fun confirm(id: String, d: Draft?): String = synchronized(lock) {
        val l = log()
        val i = l.recs.indexOfFirst { it.id == id && it.kind == REVIEW }
        if (i < 0) return "That message is no longer waiting for review."
        val r = l.recs[i]
        if (d != null) { val miss = CallModel.missing(d); if (miss.isNotEmpty()) return "Fill in: " + miss.joinToString(", ") + "." }
        val reading: Reading = if (d == null) Reading.NotACall("not a call (you said so)") else Reading.NewCall(d, 1.0, emptyList())
        r.text?.let { t ->
            val xs = l.examples.getOrPut(r.channel) { ArrayList() }
            xs += CallModel.Example(t, CallModel.exampleJson(reading))
            while (xs.size > MAX_EXAMPLES) xs.removeAt(0)
        }
        l.recs[i] = if (d == null) r.copy(kind = NOT, text = null, reason = "not a call (you said so)", by = "you", unsure = emptyList(), draft = null)
        // Boss's reading counts as he confirmed it: it is not sent back by the reality check.
        else r.copy(kind = CALL, draft = d, by = "you", confidence = 1.0, unsure = emptyList(), follow = null, checked = true, result = null, twins = emptyList(), twinsDone = false)
        save(l)
        if (d == null) "Marked as not a call." else "Confirmed: ${d.line()}. It is tested from the next round ($NOTE)."
    }

    /** A follow-up in review confirmed as the reading shown. */
    fun confirmFollowUp(id: String): String = synchronized(lock) {
        val l = log()
        val i = l.recs.indexOfFirst { it.id == id && it.kind == REVIEW && it.follow != null }
        if (i < 0) return "That message is no longer waiting for review."
        val r = l.recs[i]
        val t = r.text
        if (t != null) {
            val xs = l.examples.getOrPut(r.channel) { ArrayList() }
            xs += CallModel.Example(t, CallModel.exampleJson(r.follow!!))
            while (xs.size > MAX_EXAMPLES) xs.removeAt(0)
        }
        l.recs[i] = r.copy(kind = FOLLOW, by = "you", unsure = emptyList())
        save(l)
        "Confirmed as a follow-up."
    }

    // ---- paste and import ---------------------------------------------------------------------------------------------------

    /** A message Boss shared from Telegram ([SignalShareActivity]): from [channel], at the moment of sharing (approximate). */
    fun shareNow(channel: String, text: String, at: LocalDateTime): String {
        val made = ingest(channel, text, at.withNano(0), SHARED, approx = true)
        return if (made.isEmpty()) "Not recorded (the tester is off, the channel is not on the list, or it was just recorded)." else "Recorded ($NOTE)."
    }

    /** The paste box: [text] from [channel] at [at] (Boss's time; [approx] when he left it at now). */
    fun paste(channel: String, text: String, at: LocalDateTime, approx: Boolean): String {
        if (!on()) return "Switch the Signal tester on first."
        val made = ingest(channel, text, at.withNano(0), PASTED, approx)
        return when {
            made.isEmpty() -> "Not recorded: is \"$channel\" on the allow-list, or was the same message recorded in the last 3 minutes?"
            made.any { it.kind == PENDING } -> "Recorded; the language model reads it next."
            made.any { it.kind == REVIEW } -> "Recorded; it waits for your review."
            else -> "Recorded: " + made.joinToString("; ") { r -> r.draft?.line() ?: r.follow?.kind?.words ?: (r.reason ?: "not a call") } + "."
        }
    }

    /**
     * A Telegram Desktop export (result.json): every message of each allow-listed chat in it, with its own time, recorded as
     * "imported" (edited ones marked: their first text is unknown, so they are not scored). Chats not on the allow-list are
     * skipped. Only the parsed calls keep their text; the raw chat is never stored. Off the main thread.
     */
    fun importExport(json: String): String {
        val chats = try { TelegramExport.read(json) } catch (e: TelegramExport.Bad) { return e.message ?: "Not a Telegram export." }
        if (!on()) return "Switch the Signal tester on first."
        val allow = synchronized(lock) { log().allow.map { norm(it) }.toSet() }
        val ok = chats.filter { norm(it.name) in allow }
        if (ok.isEmpty()) return "None of the chats in this file is on the allow-list (" + chats.joinToString(", ") { it.name }.take(200) + "). Add it first."
        var calls = 0; var all = 0
        for (c in ok) for (m in c.messages) {
            all++
            calls += ingest(c.name, m.text, m.at, IMPORTED, edited = m.edited, persist = false).count { it.kind == CALL || it.kind == PENDING || it.kind == REVIEW }
        }
        // One write of the log for the whole file, then the model's queue.
        val pending = synchronized(lock) { log().let { l -> save(l); l.recs.any { it.kind == PENDING } } }
        if (pending) kickModel()
        return "Imported $all messages from ${ok.joinToString(", ") { it.name }}: $calls look like calls. Past calls are priced only where the phone can still " +
            "read their option's prices (expired contracts cannot be read from the free feed)."
    }

    // ---- the round (Jarvis's words lane) ---------------------------------------------------------------------------------------

    /** TEST SEAM: a day's 1-minute bars of an instrument key (an index as "INDEX:<name>"). */
    @Volatile internal var testBars: ((String, LocalDate) -> List<Bar>?)? = null
    /** TEST SEAM: the contracts the phone holds. */
    @Volatile internal var testContracts: List<Upstox.Contract>? = null

    /** One round of the words lane. Never throws; off, it reads nothing. */
    suspend fun watch() {
        if (com.optionslab.app.BuildConfig.GOLD) return
        if (!runCatching { on() }.getOrDefault(false)) return
        val now = Market.now().toLocalDateTime().withNano(0)
        runCatching { round(now) }.onFailure { runCatching { Diag.record("signals", "round failed: ${it.javaClass.simpleName}") } }
    }

    /** One round at [now] (tests call it directly): the model's queue, each live call priced, follow-up claims, verdicts. */
    internal suspend fun round(now: LocalDateTime) {
        if (synchronized(lock) { log().recs.any { it.kind == PENDING } }) kickModel()
        val todo = synchronized(lock) { log().recs.filter { !it.final && !waitingForTheClose(it, now) } }.sortedByDescending { it.at }.take(MAX_LIVE)
        val bars = HashMap<String, List<Bar>>()
        for (r in todo) runCatching { price(r, now, bars) }
        runCatching { claims(now, bars) }
        verdicts()
    }

    /** [r]'s day is over by [now] (F&O closed, or a later day): its prices are final. */
    private fun dayOver(r: Rec, now: LocalDateTime): Boolean =
        r.at.toLocalDate().isBefore(now.toLocalDate()) || !now.toLocalTime().isBefore(com.optionslab.engine.NseHours.foCloseTime(r.at.toLocalDate()))

    /** A finished shadow trade waits for the close: its random-entry twins need the whole day's prices. */
    private fun waitingForTheClose(r: Rec, now: LocalDateTime): Boolean = r.result?.status == CallEval.Status.DONE && !r.twinsDone && !dayOver(r, now)

    private fun contracts(u: String): List<Upstox.Contract>? = testContracts?.filter { it.underlying == u } ?: runCatching {
        val saved = if (u == Paper.BFO_UNDERLYING) Market.cachedBfoContracts() else Market.cachedContracts()
        if (saved == null) { if (u == Paper.BFO_UNDERLYING) Market.refreshBfoInBackground() else Market.refreshContractsInBackground() }
        saved?.second?.filter { it.underlying == u }
    }.getOrNull()

    private fun update(r: Rec, f: (Rec) -> Rec) {
        synchronized(lock) {
            val l = log()
            val i = l.recs.indexOfFirst { it.id == r.id }
            if (i >= 0) {
                val n = f(l.recs[i])
                if (n != l.recs[i]) { l.recs[i] = n; save(l) }
            }
        }
    }

    private suspend fun price(r0: Rec, now: LocalDateTime, cache: MutableMap<String, List<Bar>>) {
        var r = r0
        val d = r.draft ?: return
        val u = d.underlying ?: return
        val day = r.at.toLocalDate()
        val today = day == now.toLocalDate()
        if (r.key == null) {
            // The list not read yet (a restart, offline): the next round. An empty one: the phone has none for it.
            val list = contracts(u) ?: return
            if (list.isEmpty()) { update(r) { it.copy(unpriced = "the phone has no contract list for $u") }; return }
            val exp = d.expiry ?: CallCheck.nearestExpiry(day, list.map { it.expiry })
            val c = list.firstOrNull { it.expiry == exp && it.strike == d.strike && it.right == d.right }
            if (c == null) {
                update(r) { it.copy(unpriced = "the contract is not in the phone's list (a past expiry cannot be read from the free feed)") }
                return
            }
            r = r.copy(expiry = c.expiry, symbol = c.tradingSymbol, key = c.instrumentKey, lot = c.lotSize)
            update(r0) { it.copy(expiry = r.expiry, symbol = r.symbol, key = r.key, lot = r.lot) }
        }
        val key = r.key ?: return
        val at = if (today) now else day.atTime(15, 45)
        // A failed read is tried again next round (never "no data" for a network blip).
        val opt = cache["$key|$day"] ?: (bars(key, day, at) ?: return).also { cache["$key|$day"] = it }
        val idx = cache["INDEX:$u|$day"] ?: (indexBars(u, day, at) ?: return).also { cache["INDEX:$u|$day"] = it }
        if (!r.checked) {
            val spot = idx.lastOrNull { !it.start.plusMinutes(1).isAfter(r.at) }?.close ?: idx.firstOrNull()?.open
            if (spot == null && today && now.isBefore(r.at.plusMinutes(5))) return
            val problems = CallCheck.problems(d, day, known = true, spot = spot, listed = true)
            if (problems.isNotEmpty()) { update(r) { it.copy(kind = REVIEW, unsure = problems, checked = true) }; return }
            r = r.copy(checked = true)
        }
        val trading = runCatching { Market.isTradingDay(day) }.getOrDefault(true)
        val setup = CallEval.Setup(r.at, u, d.right ?: Right.CE, d.trig, d.trigger, d.triggerHigh, d.stopLevel, d.targets, r.lot ?: 1, r.expiry == day, trading)
        val set = synchronized(lock) { log().let { CallEval.Settings(it.delaySec, it.validityMin) } }
        val res = CallEval.evaluate(setup, opt, idx, at, set)
        // The twins once the day is over (a random minute after now has no price yet).
        val twinsNow = res.status == CallEval.Status.DONE && dayOver(r, now)
        val twins = if (twinsNow) CallEval.twins(setup, res, opt, r.id + r.channel) else emptyList()
        update(r0) { it.copy(checked = true, result = res, twins = twins, twinsDone = twinsNow) }
    }

    /** [key]'s finished minutes of [day] by [at], or null when the read failed (tried again next round). */
    private suspend fun bars(key: String, day: LocalDate, at: LocalDateTime): List<Bar>? {
        testBars?.let { return it(key, day) }
        val nowSec = at.atZone(com.optionslab.engine.IST).toEpochSecond()
        val raw = runCatching {
            if (day == Market.today()) {
                val feed = Net.intradayQuick(key).filter { it.istDate == day && it.epochSecond + 60 <= nowSec }
                runCatching { LocalCandles.overlay(key, feed, nowSec) }.getOrDefault(feed)
            } else Net.history(key, day, day, tries = 2)
        }.getOrNull() ?: return null
        return raw.filter { it.istDate == day && it.epochSecond + 60 <= nowSec }
            .map { Bar(it.istDate.atTime(it.istMinute / 60, it.istMinute % 60), it.open, it.high, it.low, it.close) }.sortedBy { it.start }.distinctBy { it.start }
    }

    private suspend fun indexBars(u: String, day: LocalDate, at: LocalDateTime): List<Bar>? {
        testBars?.let { return it("INDEX:$u", day) }
        if (day == Market.today()) return runCatching { PassedArmsHost.indexBars(u, at) }.getOrNull()
        val key = runCatching { ChartFeed.instrumentKey(u) }.getOrNull() ?: return emptyList()
        return bars(key, day, at)
    }

    /** Each target or stop follow-up checked once against its call's prices: was it true? A few option reads a round at most. */
    private suspend fun claims(now: LocalDateTime, cache: MutableMap<String, List<Bar>>) {
        val todo = synchronized(lock) {
            val l = log()
            l.recs.filter { it.kind == FOLLOW && it.claim == null && it.link != null && it.follow?.kind in setOf(Follow.TARGET, Follow.STOP) }
                .mapNotNull { f -> l.recs.firstOrNull { it.id == f.link }?.let { c -> f to c } }
        }
        var reads = 0
        for ((f, c) in todo) {
            val d = c.draft ?: continue
            val fillAt = c.result?.fillAt ?: continue
            val key = c.key ?: continue
            val day = c.at.toLocalDate()
            val k = "$key|$day"
            val bs = cache[k] ?: run {
                if (reads >= 4) return@run null
                reads++
                bars(key, day, if (day == now.toLocalDate()) now else day.atTime(15, 45))?.also { cache[k] = it }
            } ?: continue
            val ok = CallReader.claimTrue(f.follow!!, d, bs, fillAt, f.at) ?: continue
            update(f) { it.copy(claim = ok) }
        }
    }

    /** Once per channel at 40 triggered calls: whether it beat random entries (Jarvis's chat only; never spoken, never promoted). */
    private fun verdicts() {
        val due = synchronized(lock) {
            val l = log()
            cardsOf(l).filter { it.triggered >= ChannelScore.REVIEW_CALLS && it.channel !in l.told }.onEach { l.told += it.channel }.also { if (it.isNotEmpty()) save(l) }
        }
        for (c in due) {
            runCatching { Diag.record("signals", "a channel reached ${ChannelScore.REVIEW_CALLS} triggered calls: ${if (c.passed) "beat" else "did not beat"} random entries") }
            if (com.optionslab.app.BuildConfig.JARVIS) runCatching { com.optionslab.app.ira.IraHub.note(ChannelScore.verdictLine(c)) }
        }
    }

    // ---- words ---------------------------------------------------------------------------------------------------------------

    /** Jarvis's answer to "how is channel X doing" (never on a locked phone: the caller checks). */
    fun talk(a: com.optionslab.ira.SignalTalk.Ask): String = synchronized(lock) {
        val l = log()
        com.optionslab.ira.SignalTalk.answer(a, cardsOf(l).map { com.optionslab.ira.SignalTalk.Channel(it.channel, ChannelScore.line(it), ChannelScore.details(it)) }, l.on)
    }

    /** The diagnostics line: counts only - never a channel's name or a message. */
    fun diagLine(): String = synchronized(lock) {
        val l = log()
        "Signal tester ($NOTE): ${if (l.on) "on" else "off"}, reading notifications ${if (l.listen) "on" else "off"} · ${l.allow.size} channels · " +
            "${l.recs.count { it.kind == CALL }} calls, ${l.recs.count { it.kind == REVIEW }} to review, ${l.recs.count { it.kind == PENDING }} for the model"
    }

    /** TEST ONLY: forget the memory (the file stays), as a restart would; the seams back to the app's own. */
    internal fun resetForTest() {
        synchronized(lock) {
            cache = null; gate = null; written.forget(); lastSeen.clear(); _view.value = View()
            testModel = null; testModelMissing = false; testHolding = null; testBars = null; testContracts = null
        }
    }

    /** TEST ONLY: wait until the model's background pass is idle. */
    internal fun modelIdleForTest(): Boolean = !modelBusy.get()
}
