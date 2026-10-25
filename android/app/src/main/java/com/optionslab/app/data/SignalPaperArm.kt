package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.sandbox.SandboxEvent
import com.optionslab.engine.signals.CallParser
import com.optionslab.engine.signals.SignalPaperRules
import com.optionslab.engine.signals.SignalPaperRules.Exit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * 18 Oct (Boss: "What ever strategies are present for paper, should be present for live too"): this file itself still places
 * only paper orders; its live path is [com.optionslab.app.data.ArmLive], told of each paper buy, stop or lock and close (memory
 * only). That live trade goes only in Live with "AI trades go live" on, its own "Trade live" switch on (off until Boss turns it on: its trigger is the option's own price), outside the paper-only
 * period and past every guard - so the words below about Zerodha describe this book, not that live path.
 *
 * Paper-trading a Telegram chat's calls (15 Oct 2026, Boss's explicit choice; the rules are [SignalPaperRules]). BOSS'S CHOICE -
 * NOT PROVEN, PAPER ONLY: every order is the paper account's ([Paper.place] / [Paper.cancel] / [Paper.modify]); there is no live
 * flag and no Zerodha call anywhere here, so nothing can reach Zerodha whatever Live, "AI trades go live" or a voice command says.
 *
 * Per chat a switch, OFF by default; Boss turns it on per chat (logged in Autopilot's change timeline as PAPER_ARM_ON,
 * "paperarm:telegram:<chat id>", Boss's, undone in one tap - the chat's name stays on this phone: the timeline carries its short
 * id). A confirmed new call of a chat that is on - read surely and past the reality check by the Signal tester
 * ([SignalTesterHost]) - is bought on paper (1 lot, MIS) once the premium trades at or above its entry, after the reaction delay
 * and within 15 minutes of the message; else "not triggered", no order. Its stop rests in the paper book as a SELL SL-M (the
 * call's, else "our stop" -20%); its exit rule sells at T1 (all, or half with the stop moved to cost) or the last target; the
 * square-off sells at 15:15 (15:05 on its expiry day). At most 3 a day per chat; a chat's day ends at its paper loss cap; never
 * two positions on the same contract. New trades obey the kill switch, the bot's stop for today, the day lock and the account
 * guard; exits always go. The shadow scorecard keeps running for every chat regardless, so the two can be compared.
 *
 * Open positions first (CLAUDE.md): its book is its own encrypted file, read FIRST after a restart ([PositionsFirst]); while
 * one is open its stop, target and square-off run in the watch's minute lane at full cadence ([manageOnly]); the stop also
 * rests in the paper book itself.
 */
object SignalPaperArm {
    private const val FILE = "signal_paper.vault"
    private val lock = Mutex()
    @Volatile private var file: File? = null
    private val written = Vault.LastWrite()

    fun init(context: Context) {
        file = File(context.applicationContext.noBackupFilesDir, FILE)
        // 18 Oct (Boss: every paper strategy present for live too): its paper trades, for their live path ([ArmLive]; its live switch
        // starts off - the trigger is the option's own price, so only Boss switches it on).
        ArmLive.register(com.optionslab.engine.orb.LiveMirror.TELEGRAM) {
            if (cache == null) null else _view.value.let { v ->
                val today = Market.today()
                (v.open + v.closed.filter { it.day == today }).map {
                    ArmLive.PaperLeg(mirrorRef(it), com.optionslab.engine.orb.LiveMirror.TELEGRAM_CALLS, if (it.open) (it.qty - it.soldQty).coerceAtLeast(0) else 0)
                }
            }
        }
    }

    /** A trade's reference for its live path ([ArmLive]): its chat's call (never the chat's name). */
    private fun mirrorRef(p: Pos): String = "telegram:${p.chat}:${p.callId}"

    /** A chat's short id (the change timeline and the paper tags carry it, never the name). */
    fun chatId(name: String): String = "c" + MessageDigest.getInstance("SHA-256").digest(SignalTesterHost.norm(name).toByteArray())
        .take(5).joinToString("") { "%02x".format(it) }

    /** One paper trade of a chat's call. */
    data class Pos(
        val chat: String, val callId: String, val index: String, val symbol: String, val right: String, val lots: Int, val qty: Int,
        val entry: Double, val entryTime: LocalDateTime, val orderId: String?, val stop: Double, val ourStop: Boolean, val stopOrderId: String?,
        val exitRule: String, val target: Double?, val lastTarget: Double?, val expiryDay: Boolean, val halfDone: Boolean = false,
        val soldQty: Int = 0, val soldValue: Double = 0.0,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        /** Rupees after charges, once out (a half sold at T1 included). */
        val net: Double? get() = exit?.let { x -> soldValue + x * (qty - soldQty) - entry * qty - charges }
    }

    private class Book(
        val on: MutableMap<String, Boolean> = LinkedHashMap(),
        var settings: SignalPaperRules.Settings = SignalPaperRules.Settings(),
        val positions: MutableList<Pos> = ArrayList(),
        /** Calls decided (bought, not triggered, refused): never looked at again. */
        val decided: MutableSet<String> = HashSet(),
        /** Each chat's last word ("bought ...", "not triggered", "paused for today"). */
        /** 21 Oct: with the day each line was written ([StatusMap]). */
        val status: StatusMap = StatusMap(),
        val log: ArrayList<String> = ArrayList(),
        /** callId -> an entry order a cancel could not take out (settled on the next pass, never a second BUY beside it). */
        val pending: MutableMap<String, String> = HashMap(),
        /** The day each chat's paper trading was first switched on (its month check counts from it). */
        val since: MutableMap<String, LocalDate> = HashMap(),
        /** Chats whose month check Jarvis has already shown. */
        val monthTold: MutableSet<String> = HashSet(),
    )

    data class View(val on: Map<String, Boolean> = emptyMap(), val settings: SignalPaperRules.Settings = SignalPaperRules.Settings(),
                    val open: List<Pos> = emptyList(), val closed: List<Pos> = emptyList(), val status: Map<String, String> = emptyMap(),
                    val log: List<String> = emptyList(), val decided: Set<String> = emptySet(), val since: Map<String, LocalDate> = emptyMap()) {
        fun isOn(chat: String): Boolean = on[chatId(chat)] == true
        fun closedOf(chat: String): List<Pos> = closed.filter { it.chat == chatId(chat) }
        fun openOf(chat: String): List<Pos> = open.filter { it.chat == chatId(chat) }
        fun statusOf(chat: String): String? = status[chatId(chat)]
        fun sinceOf(chat: String): LocalDate? = since[chatId(chat)]
        /** [chat]'s paper record against Boss's pass marks ([SignalPaperRules.monthCheck]; [card]: its shadow scorecard). */
        fun monthCheck(chat: String, card: com.optionslab.engine.signals.ChannelScore.Card?): List<SignalPaperRules.Mark> =
            SignalPaperRules.monthCheck(closedOf(chat).map { SignalPaperRules.Closed(it.day, it.net ?: 0.0) }, card)
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view
    @Volatile private var cache: Book? = null

    // ---- the book --------------------------------------------------------------------------------------------------------

    private fun posJson(p: Pos) = JSONObject().put("chat", p.chat).put("call", p.callId).put("index", p.index).put("symbol", p.symbol).put("right", p.right)
        .put("lots", p.lots).put("qty", p.qty).put("entry", p.entry).put("at", p.entryTime.toString()).put("stop", p.stop).put("ours", p.ourStop)
        .put("rule", p.exitRule).put("exp", p.expiryDay).put("half", p.halfDone).put("soldQty", p.soldQty).put("soldValue", p.soldValue).put("charges", p.charges)
        .apply {
            p.orderId?.let { put("order", it) }; p.stopOrderId?.let { put("stopOrder", it) }; p.target?.let { put("target", it) }
            p.lastTarget?.let { put("last", it) }; p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitAt", it.toString()) }; p.why?.let { put("why", it) }
        }

    private fun dbl(o: JSONObject, k: String): Double? = if (o.has(k)) o.getDouble(k) else null

    private fun posOf(o: JSONObject) = Pos(o.getString("chat"), o.getString("call"), o.getString("index"), o.getString("symbol"), o.getString("right"),
        o.getInt("lots"), o.getInt("qty"), o.getDouble("entry"), LocalDateTime.parse(o.getString("at")), o.optString("order").ifEmpty { null },
        o.getDouble("stop"), o.optBoolean("ours"), o.optString("stopOrder").ifEmpty { null }, o.optString("rule", Exit.T1_ALL.key), dbl(o, "target"),
        dbl(o, "last"), o.optBoolean("exp"), o.optBoolean("half"), o.optInt("soldQty"), o.optDouble("soldValue", 0.0),
        dbl(o, "exit"), o.optString("exitAt").ifEmpty { null }?.let { LocalDateTime.parse(it) }, o.optString("why").ifEmpty { null }, o.optDouble("charges", 0.0))

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        val f = file
        val ok = f == null || runCatching {
            val o = JSONObject(String(Vault.readFileSteady(f) ?: return@runCatching, Charsets.UTF_8))
            o.optJSONObject("on")?.let { m -> m.keys().forEach { b.on[it] = m.getBoolean(it) } }
            o.optJSONObject("settings")?.let { s ->
                b.settings = SignalPaperRules.Settings(s.optInt("lots", 1).coerceIn(1, 5), s.optInt("delay", 30), s.optInt("validity", 15),
                    Exit.of(s.optString("exit")), s.optInt("max", 3).coerceIn(1, 10), s.optDouble("cap", 3_000.0))
            }
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) b.positions += posOf(a.getJSONObject(i)) }
            o.optJSONArray("decided")?.let { a -> for (i in 0 until a.length()) b.decided += a.getString(i) }
            b.status.readFrom(o, runCatching { f.lastModified() }.getOrDefault(0L))
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONObject("pending")?.let { m -> m.keys().forEach { b.pending[it] = m.getString(it) } }
            o.optJSONObject("since")?.let { m -> m.keys().forEach { k -> runCatching { b.since[k] = LocalDate.parse(m.getString(k)) } } }
            o.optJSONArray("monthTold")?.let { a -> for (i in 0 until a.length()) b.monthTold += a.getString(i) }
        }.isSuccess
        if (!ok && f != null && f.exists()) runCatching { Vault.setAside(f) }
        cache = b
        publish(b)
        return b
    }

    private fun save(b: Book) {
        val closed = b.positions.count { !it.open }
        if (closed > 400) { var drop = closed - 400; b.positions.removeAll { !it.open && drop-- > 0 } }
        val s = b.settings
        val o = JSONObject().put("on", JSONObject().apply { b.on.forEach { (k, v) -> put(k, v) } })
            .put("settings", JSONObject().put("lots", s.lots).put("delay", s.delaySec).put("validity", s.validityMin).put("exit", s.exit.key)
                .put("max", s.maxPerDay).put("cap", s.lossCap))
            .put("positions", JSONArray(b.positions.map { posJson(it) })).put("decided", JSONArray(b.decided.sorted().takeLast(2000)))
            .put("status", JSONObject().apply { b.status.forEach { (k, v) -> put(k, v) } }).put("log", JSONArray(b.log.takeLast(200)))
            .put("pending", JSONObject().apply { b.pending.forEach { (k, v) -> put(k, v) } })
            .put("since", JSONObject().apply { b.since.forEach { (k, v) -> put(k, v.toString()) } }).put("monthTold", JSONArray(b.monthTold.sorted()))
        b.status.writeTo(o)
        file?.let { f -> runCatching { written.write(f, o.toString().toByteArray(Charsets.UTF_8)) } }
        publish(b)
    }

    private fun publish(b: Book) {
        _view.value = View(b.on.toMap(), b.settings, b.positions.filter { it.open }, b.positions.filter { !it.open }.takeLast(100), b.status.shown(), b.log.takeLast(30),
            b.decided.toSet(), b.since.toMap())
    }

    private fun note(b: Book, text: String) {
        b.log += "${runCatching { Market.now().toLocalDateTime().withNano(0) }.getOrElse { LocalDateTime.now().withNano(0) }} $text"
        while (b.log.size > 200) b.log.removeAt(0)
        // The diary gets the chat's short id only, never its name.
        runCatching { Diag.record("signal-paper", text) }
    }

    /** Read the book (start-up's positions-first step and the card call it, off the main thread). */
    suspend fun refresh(): View { lock.withLock { book() }; return _view.value }

    /** An open paper trade is held (memory only). */
    fun holding(): Boolean = _view.value.open.isNotEmpty()

    /**
     * The watch needs this arm's pass (memory only: no file, no network): a trade held, or a chat switched on with one of its
     * calls inside its entry window.
     */
    fun wantsPass(): Boolean = holding() || (_view.value.on.values.any { it } && candidates(_view.value, now(), _view.value.decided).isNotEmpty())

    private fun now(): LocalDateTime = runCatching { Market.now().toLocalDateTime().withNano(0) }.getOrElse { LocalDateTime.now().withNano(0) }

    /**
     * Boss's switch for [chat] (off by default). Off sells nothing it holds: its own exits still close it. [log]: from the card
     * (logged in Autopilot's change timeline as Boss's, undone in one tap); false for the timeline's own Undo.
     */
    suspend fun setOn(chat: String, on: Boolean, log: Boolean = true): String = setOnId(chatId(chat), on, log)

    /** [setOn] by the chat's short id (the change timeline's Undo has only the id). */
    suspend fun setOnId(id: String, on: Boolean, log: Boolean = true): String = lock.withLock {
        val b = book()
        if ((b.on[id] == true) == on) return@withLock "Paper trading of this chat's calls is already ${if (on) "on" else "off"}."
        b.on[id] = on
        if (on && id !in b.since) b.since[id] = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        note(b, "chat $id: paper trading of its calls ${if (on) "on (${SignalPaperRules.BOSS_CHOICE})" else "off"}" +
            if (!on && b.positions.any { it.open && it.chat == id }) "; what it holds still closes by its own exits" else "")
        save(b)
        if (log) runCatching { com.optionslab.app.ira.Autopilot.telegramPaperSwitchedByBoss(id, on) }
        if (on) "Paper-trading this chat's calls: on. ${SignalPaperRules.BOSS_CHOICE}." else "Paper-trading this chat's calls: off."
    }

    suspend fun setExit(e: Exit): String = lock.withLock {
        val b = book()
        if (b.settings.exit != e) { b.settings = b.settings.copy(exit = e); note(b, "exit rule for new trades: ${e.words}"); save(b) }
        "New paper trades: ${e.words}. Paper only."
    }

    suspend fun setLimits(maxPerDay: Int, lossCap: Double): String = lock.withLock {
        val b = book()
        b.settings = b.settings.copy(maxPerDay = maxPerDay.coerceIn(1, 10), lossCap = lossCap.coerceIn(500.0, 50_000.0))
        save(b)
        "At most ${b.settings.maxPerDay} paper trades a day per chat; a chat pauses for the day at a ₹%,.0f paper loss.".format(Locale.ENGLISH, b.settings.lossCap)
    }

    fun diagLine(): String {
        val v = _view.value
        return "${SignalPaperRules.LABEL}: ${v.on.count { it.value }} chats on (paper only) · ${v.settings.exit.words} · open ${v.open.size} · " +
            "${v.closed.size} closed %,.0f after charges · ${SignalPaperRules.BOSS_CHOICE}".format(Locale.ENGLISH, v.closed.sumOf { it.net ?: 0.0 })
    }

    // ---- the pass ----------------------------------------------------------------------------------------------------------

    /**
     * The calls to decide now: today's sure, checked calls of chats that are on, not decided yet (one past its window is decided
     * "not triggered" on the next pass). Imported and edited messages are history, never traded.
     */
    private fun candidates(v: View, now: LocalDateTime, decided: Set<String> = emptySet()): List<SignalTesterHost.Rec> =
        SignalTesterHost.view.value.recs.filter { r ->
            r.kind == SignalTesterHost.CALL && r.checked && r.draft?.complete == true && r.at.toLocalDate() == now.toLocalDate() &&
                v.on[chatId(r.channel)] == true && r.id !in decided && !r.edited && r.source != SignalTesterHost.IMPORTED
        }

    /** The minute lane (open positions first): only the open trades' exits. Nothing held: returns at once (memory only). */
    suspend fun manageOnly() {
        if (com.optionslab.app.BuildConfig.GOLD || !holding()) return
        lock.withLock {
            val b = book()
            val changed = runCatching { exits(b, now()) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true }
            if (changed) save(b) else publish(b)
        }
    }

    /** The watch's pass: the open trades' exits first, then each candidate call's entry. */
    suspend fun tick() {
        if (com.optionslab.app.BuildConfig.GOLD || !runCatching { Market.isTradingDay() }.getOrDefault(false)) return
        val now = now()
        if (cache == null) lock.withLock { book() }
        lock.withLock {
            val b = book()
            // An earlier day's unsettled entry order is the paper book's (its MIS square-off ended it): forgotten.
            var changed = b.pending.keys.removeAll { k -> b.decided.contains(k) }
            changed = runCatching { exits(b, now) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true } || changed
            val view = View(b.on.toMap(), b.settings)
            for (r in candidates(view, now, b.decided)) changed = runCatching { entry(b, r, now) }.getOrElse {
                b.status[chatId(r.channel)] = "error: ${it.javaClass.simpleName}"; true
            } || changed
            changed = monthChecks(b, now.toLocalDate()) || changed
            if (changed) save(b) else publish(b)
        }
    }

    /**
     * Once a chat has been on paper for [SignalPaperRules.MONTH_DAYS] days, Jarvis shows its record against Boss's pass marks once
     * (its dialog shows it all along). Never a promotion: it stays paper.
     */
    private fun monthChecks(b: Book, today: LocalDate): Boolean {
        var changed = false
        for ((id, since) in b.since) {
            if (id in b.monthTold || today.isBefore(since.plusDays(SignalPaperRules.MONTH_DAYS))) continue
            val card = SignalTesterHost.view.value.cards.firstOrNull { chatId(it.channel) == id } ?: continue
            val closed = b.positions.filter { !it.open && it.chat == id }.map { SignalPaperRules.Closed(it.day, it.net ?: 0.0) }
            val line = SignalPaperRules.monthLine(SignalPaperRules.monthCheck(closed, card))
            b.monthTold += id; changed = true
            note(b, "chat $id: month check - $line")
            if (com.optionslab.app.BuildConfig.JARVIS) runCatching {
                com.optionslab.app.ira.IraHub.note("Boss, a month of paper-trading ${card.channel}'s Telegram calls (${SignalPaperRules.BOSS_CHOICE}): $line")
            }
        }
        return changed
    }

    private fun say(b: Book, chat: String, why: String, final: Boolean = false): Boolean {
        if (final) note(b, "chat $chat: $why")
        if (b.status.sameToday(chat, why)) return final
        b.status[chat] = why
        return true
    }

    private suspend fun stoppedToday(): Boolean =
        runCatching { Strategies.stoppedToday() }.getOrDefault(true) || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /** The highest and lowest traded price of [c] since [from] (the option's own minutes), or nulls. */
    private suspend fun rangeSince(c: Paper.Contract, from: LocalDateTime): Pair<Double?, Double?> {
        val since = from.withSecond(0).atZone(com.optionslab.engine.IST).toEpochSecond()
        val bars = runCatching { Net.intradayQuick(c.feedKey) }.getOrNull().orEmpty().filter { it.epochSecond >= since }
        return bars.maxOfOrNull { it.high } to bars.minOfOrNull { it.low }
    }

    /** One call's entry at [now]. True when the book changed. */
    private suspend fun entry(b: Book, r: SignalTesterHost.Rec, now: LocalDateTime): Boolean {
        val chat = chatId(r.channel)
        val d = r.draft ?: return false
        fun done(why: String): Boolean { b.decided += r.id; return say(b, chat, why, final = true) }
        val s = b.settings
        val expiryDay = r.expiry == r.at.toLocalDate()
        // An entry order a cancel could not take out: settled first, never a second BUY beside it - and before the window's end
        // is looked at, so a call is never decided (and its order forgotten) while that order may still fill (18 Oct, review).
        b.pending[r.id]?.let { id ->
            val o = Paper.state.orders.firstOrNull { it.orderId == id }
            return when {
                o == null || o.status == "cancelled" || o.status == "rejected" -> { b.pending.remove(r.id); true }
                o.status == "complete" -> { b.pending.remove(r.id); val c = Paper.contractOf(o.symbol) ?: return true
                    bought(b, r, c, id, o.quantity, o.averagePrice?.toDouble() ?: return true, now) }
                else -> { runCatching { Paper.cancel(id, "unfilled_limit") }; false }
            }
        }
        when (SignalPaperRules.phase(r.at, now, s, expiryDay)) {
            SignalPaperRules.Phase.WAIT -> return say(b, chat, "a call came; waiting the ${s.delaySec} s reaction delay")
            SignalPaperRules.Phase.LATE -> return done("${d.line()}: not triggered in ${s.validityMin} minutes - no order")
            SignalPaperRules.Phase.OPEN -> Unit
        }
        val exp = r.expiry ?: return say(b, chat, "waiting for the call's contract to be found")
        val c = Paper.ticketContract(d.underlying!!, exp, d.strike!!, d.right!!) ?: return done("${d.line()}: not in the phone's options list - no order")
        if (c.lotSize <= 0) return done("lot size unknown - no order")
        val today = now.toLocalDate()
        val mine = b.positions.filter { it.chat == chat && it.day == today }
        SignalPaperRules.refusal(mine.size, mine.sumOf { it.net ?: 0.0 }, s)?.let { return done(it) }
        if (b.positions.any { it.open && it.symbol == c.symbol } || Paper.state.positions.any { it.symbol == c.symbol && it.quantity != 0 })
            return done("${c.symbol} is already held - never two positions on one contract")
        // The kill switch, the bot's stop for today, the day lock and the guard: nothing bypasses them.
        if (runCatching { AppSettings.load().guardKill }.getOrDefault(true)) return done("refused: the kill switch is on")
        if (stoppedToday()) return done("refused: the bot is stopped for today")
        runCatching { DayLockGuard.refusal(false) }.getOrNull()?.let { return done("refused: $it") }
        val q = runCatching { Paper.quote(c) }.getOrNull()
        val ltp = runCatching { Paper.bookNow(c)?.last }.getOrNull()?.takeIf { it > 0 } ?: q?.ltp?.takeIf { it > 0 }
        val from = maxOf(r.at, today.atTime(9, 15)).plusSeconds(s.delaySec.toLong())
        val (hi, lo) = rangeSince(c, from)
        if (!SignalPaperRules.triggered(d.trig, d.trigger, d.triggerHigh, ltp, hi, lo))
            return say(b, chat, "${d.line()}: waiting for ${d.trigger?.let { CallParser.num(it) } ?: "a price"} (last ${ltp?.let { CallParser.num(it) } ?: "?"})")
        val px = ltp ?: return say(b, chat, "waiting for a fresh price of ${c.symbol}")
        // Its stop must still be below the price: a call whose stop is already broken is not bought.
        if (d.stopLevel?.let { px <= it } == true) return done("${c.symbol} at ${CallParser.num(px)} is already at its stop - no order")
        val limit = CallParser.round2(SignalPaperRules.tickDown(px * 1.01) + 0.05)
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", s.lots, limit), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return done("refused by the account guard: " + refusals.joinToString(" "))
        // 22 Oct: the guard's verdict stamped with this order's fingerprint, the call's stop and first target with it ([OrderVerdicts]).
        val verdict = OrderVerdicts.stamp(OrderVerdicts.paperFields(c, "BUY", s.lots, "LIMIT", "MIS", limit, null, now.toLocalDate(), SignalPaperRules.OWNER,
            stop = d.stopLevel, target = d.targets.firstOrNull()), refusals, "paper guard")
        // A LIMIT BUY on the PAPER account (1% above the price at most); not filled at once, cancelled and tried next pass.
        val res = Paper.place(c, "BUY", s.lots, "LIMIT", "MIS", limit, null, known = q, auto = SignalPaperRules.OWNER, verdict = verdict,
            stop = d.stopLevel, target = d.targets.firstOrNull())
        val fill = res.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill != null) return bought(b, r, c, res.orderId, fill.quantity, fill.price, now)
        val id = res.orderId?.takeIf { res.ok } ?: return say(b, chat, "limit ${CallParser.num(limit)} for ${c.symbol} refused (${res.message}); tried again")
        runCatching { Paper.cancel(id, "unfilled_limit") }
        val o = Paper.state.orders.firstOrNull { it.orderId == id }
        return when {
            o == null || o.status == "cancelled" || o.status == "rejected" -> say(b, chat, "limit ${CallParser.num(limit)} for ${c.symbol} not filled; tried again")
            o.status == "complete" -> bought(b, r, c, id, o.quantity, o.averagePrice?.toDouble() ?: limit, now)
            else -> { b.pending[r.id] = id; say(b, chat, "an entry order is still working; settled before anything new") }
        }
    }

    /** [qty] of [c] bought at [price] for call [r]: its stop resting as a paper SELL SL-M, its exit rule kept. */
    private suspend fun bought(b: Book, r: SignalTesterHost.Rec, c: Paper.Contract, orderId: String?, qty: Int, price: Double, now: LocalDateTime): Boolean {
        val chat = chatId(r.channel)
        val d = r.draft!!
        val s = b.settings
        orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${SignalPaperRules.OWNER} · entry") } }
        val (stop, ours) = SignalPaperRules.stop(d.stopLevel, price, s)
        val lots = (qty / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
        val stopId = runCatching { Paper.place(c, "SELL", lots, "SL-M", "MIS", null, stop) }.getOrNull()?.takeIf { it.ok }?.orderId
            ?.also { runCatching { Strategies.tagOwner("paper:$it", "${SignalPaperRules.OWNER} · stop") } }
        val pos = Pos(chat, r.id, d.underlying!!, c.symbol, d.right!!.name, lots, qty, price, now, orderId, stop, ours, stopId, s.exit.key,
            SignalPaperRules.target(s.exit, d.targets), d.targets.lastOrNull(), r.expiry == now.toLocalDate(), charges = chargesOf(orderId))
        b.positions += pos
        // 22 Oct: the time machine's reality check (shadow only; background): the call's stop and first target as premium, out by 15:15.
        runCatching { TimeMachineHost.afterEntry(TimeMachineHost.Entry(com.optionslab.engine.orb.LiveMirror.TELEGRAM_CALLS, SignalPaperRules.OWNER, mirrorRef(pos),
            d.underlying!!, if (d.right!!.name == "CE") 1 else -1, now, (price - stop).takeIf { it > 0 }, d.targets.firstOrNull()?.let { it - price }?.takeIf { it > 0 },
            premium = true, exitAt = com.optionslab.engine.signals.CallEval.SQ)) }
        // 18 Oct: its live path ([ArmLive], off until Boss switches it on): the same option and lots, a LIMIT 1% over the fill at most,
        // the call's stop (or ours) resting at Zerodha and moved to cost at T1; targets stay the app's.
        runCatching { ArmLive.entered(ArmLive.Ask(com.optionslab.engine.orb.LiveMirror.TELEGRAM_CALLS, mirrorRef(pos), c, 1, qty, price, stop = stop,
            limit = price * 1.01)) }
        b.decided += r.id
        return say(b, chat, "bought ${c.symbol} $qty @ ${CallParser.num(price)} · paper · stop ${CallParser.num(stop)}${if (ours) " (our stop)" else ""}, " +
            "${s.exit.words} · ${SignalPaperRules.BOSS_CHOICE}", final = true)
    }

    /** Each open trade's exit, when due. True when [b] changed. */
    private suspend fun exits(b: Book, now: LocalDateTime): Boolean {
        var changed = false
        for (i in b.positions.indices) {
            val p = b.positions[i]
            if (!p.open) continue
            val next = runCatching { exitOne(p, now) }.getOrNull() ?: continue
            // 18 Oct: its stop (moved to cost at T1) for its live trade, and its close ([ArmLive]; memory only).
            runCatching { ArmLive.level(mirrorRef(next), next.stop) }
            if (!next.open) runCatching { ArmLive.closed(mirrorRef(next), next.why ?: "exit") }
            if (next != p) {
                b.positions[i] = next; changed = true
                if (!next.open) say(b, p.chat, "sold ${p.symbol} (${next.why}): %s after charges · paper".format(Locale.ENGLISH, com.optionslab.engine.signals.ChannelScore.rs(next.net ?: 0.0)), final = true)
                else if (next.halfDone && !p.halfDone) say(b, p.chat, "${p.symbol}: T1 reached - ${if (next.soldQty > 0) "half sold, " else ""}stop moved to cost", final = true)
            }
        }
        return changed
    }

    private suspend fun exitOne(start: Pos, now: LocalDateTime): Pos {
        var p = start
        val c = Paper.contractOf(p.symbol) ?: return p
        // Its resting stop filled in the paper book: that is the exit.
        p.stopOrderId?.let { id ->
            val o = Paper.state.orders.firstOrNull { it.orderId == id }
            if (o != null && o.status == "complete") return closed(p, o.averagePrice?.toDouble() ?: p.stop, o.updateTimestamp,
                if (p.ourStop && !p.halfDone) "our stop" else if (p.halfDone) "stop at cost" else "stop", chargesOf(id))
        }
        // Gone from the paper book without this arm selling it (the paper square-off, the loss limit, or Boss).
        val held = Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity }
        if (held <= 0) {
            p.stopOrderId?.let { runCatching { Paper.cancel(it, "position_closed") } }
            val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
            return closed(p, t?.price?.toDouble() ?: p.entry, t?.timestamp ?: now, "closed_elsewhere", t?.charges?.toDouble() ?: 0.0)
        }
        val sq = p.day.atTime(SignalPaperRules.squareOff(p.expiryDay))
        if (!now.isBefore(sq) || p.day.isBefore(now.toLocalDate())) return sellRest(p, c, "square-off")
        val ltp = runCatching { Paper.bookNow(c)?.last }.getOrNull()?.takeIf { it > 0 } ?: runCatching { Paper.quote(c)?.ltp }.getOrNull() ?: return p
        // No stop resting in the paper book (its SL-M was refused or could not be put back, or was cancelled): the arm checks the
        // stop itself - through it, sold now - and puts the SL-M back while above it; never a position riding to the square-off
        // without a stop (18 Oct, review).
        if (!stopResting(p.stopOrderId)) {
            if (ltp <= p.stop) return sellRest(p, c, if (p.halfDone) "stop at cost" else if (p.ourStop) "our stop" else "stop")
            val lots = ((p.qty - p.soldQty) / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
            // 25 Oct (merge review): not while a sale already works for the symbol (the square-off's market order waiting for a
            // price): a stop put in beside it would be left behind on a flat book and could open a short.
            val again = if (closingWorks(p.symbol)) null else runCatching { Paper.place(c, "SELL", lots, "SL-M", "MIS", null, p.stop) }.getOrNull()?.takeIf { it.ok }?.orderId
                ?.also { runCatching { Strategies.tagOwner("paper:$it", "${SignalPaperRules.OWNER} · stop") } }
            if (again != null) p = p.copy(stopOrderId = again)
        }
        val rule = Exit.of(p.exitRule)
        val level = if (p.halfDone) p.lastTarget else p.target
        if (level == null || ltp < level) return p
        if (rule == Exit.HALF_COST && !p.halfDone) {
            // T1: half sold (when there is a half to sell), the stop moved to cost for the rest.
            val half = SignalPaperRules.halfLots(p.lots)
            var sold = 0; var value = 0.0; var ch = 0.0
            if (half > 0) {
                // 22 Oct (arms audit): an exit only reduces what is held.
                val r = Paper.place(c, "SELL", half, "MARKET", "MIS", null, null, exit = true)
                val f = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull() ?: return p
                sold = f.quantity; value = f.price * f.quantity; ch = chargesOf(r.orderId)
            }
            p.stopOrderId?.let { runCatching { Paper.modify(it, p.qty - sold, null, p.entry) } }
            return p.copy(halfDone = true, stop = p.entry, soldQty = sold, soldValue = value, charges = p.charges + ch)
        }
        return sellRest(p, c, if (p.halfDone) "last target" else "target")
    }

    /** A SELL for [symbol] still working in the paper book (open, or a trigger-pending stop): a second stop is never put in beside it. */
    private fun closingWorks(symbol: String): Boolean =
        Paper.state.orders.any { it.symbol == symbol && it.action == "SELL" && (it.status == "open" || it.status == "trigger pending") }

    /** Sell what is left of [p] at market (its resting stop taken out first; if it filled meanwhile, that is the exit). */
    private suspend fun sellRest(p: Pos, c: Paper.Contract, why: String): Pos {
        p.stopOrderId?.let { id ->
            runCatching { Paper.cancel(id, "exit:$why") }
            val o = Paper.state.orders.firstOrNull { it.orderId == id }
            if (o != null && o.status == "complete") return closed(p, o.averagePrice?.toDouble() ?: p.stop, o.updateTimestamp, "stop", chargesOf(id))
        }
        val lots = ((p.qty - p.soldQty) / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
        // 22 Oct (arms audit): an exit only reduces what is held - never a paper short when the square-off got there first.
        val r = Paper.place(c, "SELL", lots, "MARKET", "MIS", null, null, exit = true)
        val f = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull() ?: run {
            // Already flat in the paper book (the square-off, the sweeper or Boss): closed elsewhere, at that sale's price.
            if (!r.ok && Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } <= 0) {
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
                return closed(p.copy(stopOrderId = null), t?.price?.toDouble() ?: p.entry, t?.timestamp ?: now(), "closed_elsewhere", t?.charges?.toDouble() ?: 0.0)
            }
            // Not filled (no fresh price): the stop goes back into the paper book at once, and the exit is tried next pass.
            r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            // 25 Oct (merge review): refused because a sale already works (the square-off's order waiting for a price): no stop
            // goes in beside it - it would be left behind on a flat book and could open a short.
            val again = if (closingWorks(p.symbol)) null else runCatching { Paper.place(c, "SELL", lots, "SL-M", "MIS", null, p.stop) }.getOrNull()?.takeIf { it.ok }?.orderId
            return p.copy(stopOrderId = again)
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${SignalPaperRules.OWNER} · exit") } }
        return closed(p.copy(stopOrderId = null), f.price, now(), why, chargesOf(r.orderId))
    }

    /** Whether stop order [id] still rests in the paper book (working or waiting for its trigger): not null, gone, cancelled or refused. */
    private fun stopResting(id: String?): Boolean {
        val o = id?.let { x -> Paper.state.orders.firstOrNull { it.orderId == x } } ?: return false
        return o.status != "cancelled" && o.status != "rejected" && o.status != "complete"
    }

    private fun closed(p: Pos, price: Double, at: LocalDateTime, why: String, charges: Double): Pos =
        p.copy(exit = price, exitTime = at, why = why, charges = p.charges + charges, stopOrderId = null)

    /** For tests: the book forgotten (its file goes with the test's directory). */
    internal fun wipe() { cache = null; _view.value = View() }
}
