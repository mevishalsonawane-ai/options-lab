package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The owner's diagnostics diary: what the app said and did (every banner it showed, the self-test steps, the
 * relay checks), kept encrypted on the phone, never sent anywhere. "Copy diagnostics" puts a redacted report on
 * the clipboard for the owner to paste to whoever helps them; nothing leaves the phone any other way.
 *
 * No secret ever reaches it: callers hand it only words already shown on screen, and [redact] takes out
 * anything that looks like a key, token or password (long runs of letters and digits) before it is kept.
 */
object Diag {
    private const val MAX = 1_500
    private lateinit var file: File
    private var cache: ArrayDeque<String>? = null

    private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext; file = File(context.applicationContext.noBackupFilesDir, "diag.vault") }

    private val SECRETISH = Regex("[A-Za-z0-9_\\-+/=]{24,}")
    private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(com.optionslab.engine.IST)

    /** Anything that could be a key, token, secret or password is replaced; the rest is kept as said. */
    fun redact(s: String): String {
        var out = SECRETISH.replace(s, "‹redacted›")
        // The account holder's name (Zerodha's welcome line) never reaches the report the owner shares.
        runCatching { Broker.userName }.getOrNull()?.takeIf { it.length >= 3 }?.let { out = out.replace(it, "‹name›", ignoreCase = true) }
        return out
    }

    /**
     * The file could not be read when the lines were last loaded (review, 14 Oct): the lines in memory are only the new ones,
     * so the file is never written over with them - the next write reads it again and adds them after it.
     */
    private var unread = false

    @Synchronized
    private fun diary(): ArrayDeque<String> {
        cache?.let { return it }
        val out = ArrayDeque<String>()
        unread = readInto(out).not()
        cache = out
        return out
    }

    /** The file's lines added to [out]; false when it exists and could not be read (a missing file reads as none). */
    private fun readInto(out: ArrayDeque<String>): Boolean = runCatching {
        val a = JSONArray(String(Vault.readFileSteady(file) ?: return true, Charsets.UTF_8))
        for (i in 0 until a.length()) out.addLast(a.getString(i))
        true
    }.getOrDefault(false)

    /** One writer thread: a banner shown from the screen's thread never waits on the disk. */
    private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diag").apply { isDaemon = true } }

    /**
     * Speed (2026-10-05): the diary is up to [MAX] lines, and every write re-encrypts all of it with the Keystore (the
     * phone's security chip, shared with every other vault and settings write). Written once per line, a burst of lines
     * (a self-test, a stream reconnect) kept the chip busy for seconds. Lines are now kept in memory at once and written
     * together [WRITE_AFTER_MS] after the first unwritten one; [flush] (the report, [lines]) writes them straight away.
     */
    private const val WRITE_AFTER_MS = 3_000L
    /** Lines kept in memory and not yet written (guarded by this object's lock). */
    private var dirty = false
    /** A write is scheduled (guarded by this object's lock). */
    private var scheduled = false

    /** Today's error lines by kind and the failures named only by a shrunk class (12 Oct; [com.optionslab.ira.DiagErrors]): counts, memory only. */
    val errors = com.optionslab.ira.DiagErrors.Tally()

    /** Keep one event: "[area] text", time-stamped (IST) now, written in the background. Never throws. */
    fun record(area: String, text: String) {
        if (!::file.isInitialized) return
        // Redacted on the diary's own thread (round 2): the caller - an order being sent - never waits on the vault read it
        // makes for the account holder's name.
        val head = "${TIME.format(Instant.now())} [$area] "
        runCatching { writer.execute {
            keep(head + redact(text.replace('\n', ' ')))
            // 12 Oct: the day's errors counted by kind on this thread (the order review and the app's health read them; no agent read the diary).
            runCatching { errors.add(java.time.LocalDate.now(com.optionslab.engine.IST), area, redact(text)) }
        } }
    }

    @Synchronized
    private fun keep(line: String) {
        runCatching {
            val l = diary()
            l.addLast(line)
            while (l.size > MAX) l.removeFirst()
            dirty = true
            if (!scheduled) {
                scheduled = true
                writer.schedule(Runnable { save() }, WRITE_AFTER_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Writes the lines kept since the last write (on the writer thread, or the caller's in [flush]). */
    @Synchronized
    private fun save(): Boolean {
        scheduled = false
        if (!dirty) return true
        if (unread) {
            // The earlier read failed: read again and put the new lines after the file's (never the new ones alone over it).
            val disk = ArrayDeque<String>()
            if (!readInto(disk)) return false
            cache?.let { disk.addAll(it) }
            while (disk.size > MAX) disk.removeFirst()
            cache = disk; unread = false
        }
        dirty = false
        // 14 Oct: a failed write leaves the lines marked unwritten (the next line tries again; a trim never lets them go).
        return runCatching { Vault.writeFile(file, JSONArray(diary().toList()).toString().toByteArray(Charsets.UTF_8)) }.isSuccess.also { if (!it) dirty = true }
    }

    /** Waits for the lines recorded so far and writes them (tests, and the report, read what was just recorded). */
    fun flush() { runCatching { writer.submit(Runnable { save() }).get(5, java.util.concurrent.TimeUnit.SECONDS) } }

    /**
     * Memory trim (audit, 14 Oct, item 8): the diary's lines in memory (up to [MAX]) written if any are new, then let go; the
     * next line or report reads the file again. On the diary's own thread (never the caller's, never the main thread).
     */
    fun trimMemory() {
        if (!::file.isInitialized) return
        runCatching { writer.execute { synchronized(this) { if (runCatching { save() }.getOrDefault(false) && !dirty) cache = null } } }
    }

    /** Lines held in memory now (0 when let go; the trim's figure). */
    @Synchronized fun linesInMemory(): Int = cache?.size ?: 0

    /** The app is crashing: the lines kept so far written now, on the crashing thread (never throws). */
    fun saveNow() { if (::file.isInitialized) runCatching { save() } }

    /**
     * The diary's lines as kept ("MM-dd HH:mm:ss [area] text", oldest first, already redacted), after the queued writes:
     * read on the phone only (Jarvis's "why was I logged out of Zerodha?"), never sent anywhere.
     */
    fun lines(): List<String> {
        if (!::file.isInitialized) return emptyList()
        flush()
        return synchronized(this) { diary().toList() }
    }

    /** The report the owner copies: the app's state (no identities, no keys), then the latest events and bot notes. */
    suspend fun report(): String = buildString {
        flush()
        val s = runCatching { AppSettings.load() }.getOrNull()
        append("${com.optionslab.app.ui.components.BrandName.name} diagnostics · ${TIME.format(Instant.now())} IST\n")
        append("App ${com.optionslab.app.BuildConfig.VERSION_NAME} (build ${com.optionslab.app.BuildConfig.COMMIT}) · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        append("Mode: ${if (s?.live == true) "LIVE" else "Paper"} · real orders allowed: ${s?.allowRealOrders} · kill switch: ${s?.guardKill}\n")
        append("Market open: ${Market.isOpen()} · Zerodha linked: ${Broker.linked} · logged in: ${Broker.loggedIn}\n")
        append("Static IP set: ${StaticIp.registered != null} · relay on: ${Relay.enabled} · relay connected: ${runCatching { Relay.connected }.getOrDefault(false)}\n")
        // Zerodha's live price stream: its state now and today's drops with the last one's reason (from the [stream] lines).
        append(runCatching { KiteStream.statusLine(synchronized(this@Diag) { diary().toList() }) }.getOrElse { "Live stream: could not read" }).append('\n')
        // The order watch: its last finished check, whether its service is alive, what it waits on, the battery setting
        // (the "[watch]" lines below say why it stopped or stalled).
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { com.optionslab.app.work.Heartbeat.statusLine(app) }.getOrElse { "Order watch: could not read" }).append('\n')
        if (com.optionslab.app.BuildConfig.GOLD) append(gold())
        // Battery, round 1: what runs in the background now (listening, the stream, the watch's pace, the AI model, the phone's charge).
        append(com.optionslab.app.work.BatteryNow.line(app)).append('\n')
        // Battery (14 Oct): which background loops ran today and for how long (the evening watch, the recorder, the stream).
        append(com.optionslab.app.work.BatteryMeter.line()).append('\n')
        // Speed (Boss, 5 Oct: "too slow"): the screen's stalls today, the longest and what was running then; the app's start.
        append(runCatching { Speed.line() }.getOrElse { "Speed: could not read" }).append('\n')
        // 17 Oct: any vault write still made on the screen's thread, with its keys or file and who asked.
        append(runCatching { com.optionslab.app.security.MainWrites.line() }.getOrElse { "Vault writes on the screen's thread: could not read" }).append('\n')
        // The settings vault's size (12 Oct): its biggest key classes and the large values kept in their own files - names and
        // sizes only, never a value - and any of those files read on the screen's thread (should be none).
        append(runCatching { com.optionslab.app.security.SecurePrefs.sizeLine() }.getOrElse { "Settings size: could not read" }).append('\n')
        // The app's own health (11 Oct): stalls today, the last ANR and crash and where, what its tuner slowed now.
        append(runCatching { AppHealth.diagLine() }.getOrElse { "Health: could not read" }).append('\n')
        // Memory audit (14 Oct): the start-up's order (positions first) and what the last memory trim let go.
        append(runCatching { PositionsFirst.diagLine() }.getOrElse { "Start-up: could not read" }).append('\n')
        append(runCatching { com.optionslab.app.work.ProcessExits.diagLine() }.getOrElse { "Last process end: could not read" }).append('\n')
        append("Memory: ").append(runCatching { MemoryTrim.lastLine ?: "no trim in this run" }.getOrDefault("could not read"))
            .append(runCatching { KiteStream.keptCounts().let { (p, c) -> " · stream memory: $p paths, $c instruments' candles" } }.getOrDefault(""))
            .append(runCatching { " · research index ${com.optionslab.app.ira.KnowledgeLibrary.madeFrom?.let { "open ($it)" } ?: "not open"}" }.getOrDefault(""))
            .append(runCatching { OrderFlowLive.droppedTicks().takeIf { it > 0 }?.let { " · order flow dropped $it tick batches" } ?: "" }.getOrDefault(""))
            .append('\n')
        // Order speed (9 Oct): today's typical and worst per step, signal to fill, the relay's and the direct round trip, the
        // phone clock's offset, and the warnings - durations only (Settings → Zerodha → Order speed shows the same).
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { OrderTiming.diagLine() }.getOrElse { "Order speed: could not read" }).append('\n')
        // The Live check (18 Oct): the last run's summary - counts and times only (each step's line is in the events below).
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { LiveSelfTest.diagLine(synchronized(this@Diag) { diary().toList() }) }.getOrElse { "Live check: could not read" }).append('\n')
        // Round 4: each fill's slippage against the price it meant (live and the paper book's modelled, apart), and the app's
        // books against Zerodha's as last checked (report only).
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { FillSlippage.diagLine() }.getOrElse { "Slippage: could not read" }).append('\n')
        // Liquidity BANKNIFTY's paper test (12 Oct): its fill quality, its freeze, the live stop rule and the go-live checklist's count.
        if (!com.optionslab.app.BuildConfig.GOLD) {
            append(runCatching { LiquidityTest.diagLine() }.getOrElse { "Liquidity fill quality: could not read" }).append('\n')
            // 19 Oct: the same-direction cap - today's refused (paper) and would-be refused (live, in shadow) entries, by arm.
            append(runCatching { ExposureCapHost.line() }.getOrElse { "Same-direction cap: could not read" }).append('\n')
            append("Liquidity BANKNIFTY: ").append(runCatching { LiquidityTest.note() }.getOrElse { "freeze could not be read" }).append(' ')
                .append(runCatching { com.optionslab.ira.LiquidityStopRule.line(OrbArms.liquidityStopHint, live = true) }.getOrElse { "" }).append('\n')
        }
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { ZerodhaSync.line() }.getOrElse { "Zerodha sync: could not read" }).append('\n')
        // The order review (12 Oct): when it last ran and its counts (no rupees).
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { OrderReviewHost.diagLine() }.getOrElse { "Order review: could not read" }).append('\n')
        // 12 Oct: the open problems any agent found (the Automation card shows the same).
        if (!com.optionslab.app.BuildConfig.GOLD) runCatching { ProblemsHost.diagLines() }.getOrDefault(emptyList()).forEach { append(it).append('\n') }
        // The watch's workers (10 Oct): each worker and step - its state, last run, time, errors - the slowest steps today,
        // Zerodha's shared request budget and reads, the entry door, the shared state and the one-index claims standing now.
        if (!com.optionslab.app.BuildConfig.GOLD) {
            runCatching { com.optionslab.app.work.WatchWorkers.diagLines() }.getOrElse { listOf("Workers: could not read") }.forEach { append(it).append('\n') }
            append(runCatching { com.optionslab.app.work.WatchWorkers.slowestLine(8) }.getOrElse { "Slowest steps today: could not read" }).append('\n')
            append(runCatching { Broker.sharingLine() }.getOrElse { "Zerodha requests: could not read" }).append('\n')
            append(runCatching { AppState.line() }.getOrElse { "Shared state: could not read" }).append('\n')
            append("One-index claims standing: ").append(runCatching { AutoExposure.claims().ifEmpty { listOf("none") }.joinToString("; ") }.getOrElse { "could not read" }).append('\n')
        }
        // GitHub sync (13 Oct): states and dates only - never the token or a key.
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { GitHubSync.diagLine() }.getOrElse { "GitHub sync: could not read" }).append('\n')
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { OrderFlowLive.coverageLine() }.getOrElse { "Order flow: could not read" }).append('\n')
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { FlowGate.diagLine() }.getOrElse { "Order flow modes: could not read" }).append('\n')
        // Honest paper (08 Oct): the bid/ask spread paper fills paid today (the "[paper] Stale price" lines below: fills on old prices).
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { Paper.spreadTodayLine() }.getOrElse { "Paper spread: could not read" }).append('\n')
        // Night (R3), paper only (08 Oct): on or off, what it holds overnight, its last decisions (the "[night]" lines below).
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { NightArm.diagLine() }.getOrElse { "Night (R3): could not read" }).append('\n')
        // VIX divergence (9 Oct, paper only, not proven): on or off, what it holds, each index's last decision (the "[vix-div]" lines below carry each signal's bid/ask).
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { VixDivArm.diagLine() }.getOrElse { "VIX divergence: could not read" }).append('\n')
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { HeroTicketArm.diagLine() }.getOrElse { "Hero ₹1,000 ticket: could not read" }).append('\n')
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { PassedArmsHost.diagLine() }.getOrElse { "Passed-research arms: could not read" }).append('\n')
        // 18 Oct: the paper strategies' live trades and their switches ([ArmLive]; memory only).
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { ArmLive.diagLine() }.getOrElse { "Arms live: could not read" }).append('\n')
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { WatchlistHost.diagLine() }.getOrElse { "Watchlist: could not read" }).append('\n')
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { TrendWatchHost.diagLine() }.getOrElse { "Trend watch: could not read" }).append('\n')
        // The Signal tester (15 Oct, shadow): counts only - never a channel name or a message.
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { SignalTesterHost.diagLine() }.getOrElse { "Signal tester: could not read" }).append('\n')
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { SignalPaperArm.diagLine() }.getOrElse { "Telegram calls (paper): could not read" }).append('\n')
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { TelegramCapture.diagLine() }.getOrElse { "Telegram login: could not read" }).append('\n')
        // The MCX paper arms (9 Oct, paper only, not proven): on or off, what each holds, its last decision (the "[mcx-arms]" lines below).
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { McxPaperArms.diagLine() }.getOrElse { "MCX paper arms: could not read" }).append('\n')
        // The approved forge ideas on paper (S2, paper only): how many hosted, each one's record (the "[forge-paper]" lines below).
        if (!com.optionslab.app.BuildConfig.GOLD) append(runCatching { GrammarArm.diagLine() }.getOrElse { "${GrammarArm.LABEL}: could not read" }).append('\n')
        // Speed, round 4 (Boss, 5 Oct: "Answer is taking a lot after question is asked"): where a question's wait goes,
        // stage by stage (heard→routed, routed→answered, answered→spoken) and the slowest - durations only, never words.
        append(redact(runCatching { com.optionslab.app.ira.IraHub.askSpeedLine() }.getOrElse { "Speed (asks): could not read" })).append('\n')
        // Jarvis's ears and his recent actions (Boss, 4 Oct: "is there a file of logs I can give you?").
        if (com.optionslab.app.BuildConfig.JARVIS) {
            append("\n-- Jarvis's ears --\n")
            append(redact(runCatching { com.optionslab.app.ira.JarvisVoice.report(app) }.getOrElse { "could not read: ${it.javaClass.simpleName}\n" }))
            append("AI model loaded: ${runCatching { com.optionslab.app.ira.IraModel.state.value.status }.getOrNull()}\n")
            append(redact(runCatching { com.optionslab.app.ira.IraModel.state.value.let { m -> "AI model: ${m.status}, loaded ${m.loaded}, writing ${m.writing}" + (m.message?.let { t -> " · $t" } ?: "") + "\n" } }.getOrDefault("")))
            append("\n-- Jarvis's activity (today) --\n")
            runCatching { com.optionslab.app.ira.IraActivity.lines() }.getOrDefault(emptyList()).takeLast(120).forEach { append(redact(it)).append('\n') }
            append("\n-- Words I could not place (today) --\n")
            runCatching { com.optionslab.app.ira.IraTools.missedToday() }.getOrDefault(emptyList()).ifEmpty { listOf("none") }.forEach { append(redact(it)).append('\n') }
            // Boss, 4 Oct: "add all AI logs there for now". Every AI record the app keeps, redacted.
            append("\n-- Jarvis's chat (latest 60, newest last) --\n")
            runCatching { com.optionslab.app.ira.IraHub.state.value.messages.takeLast(60) }.getOrDefault(emptyList()).forEach { m ->
                append(if (m.fromIra) "Jarvis: " else "You: ").append(redact(m.text.replace('\n', ' ').take(400))).append('\n') }
            fun section(title: String, body: () -> Any?) {
                append("\n-- $title --\n")
                val v = runCatching { body() }.getOrElse { "could not read: ${it.javaClass.simpleName}" }
                when (v) { is List<*> -> v.forEach { append(redact(it.toString())).append('\n') }; null -> append("(none)\n"); else -> append(redact(v.toString())).append('\n') }
            }
            section("What Jarvis does by himself") { com.optionslab.app.ira.Automations.Group.entries.map { g ->
                "${g.label}: ${if (com.optionslab.app.ira.Automations.on(g)) "on" else "off"}" + (com.optionslab.app.ira.Automations.last(g)?.let { (t, w) -> " · last $t: $w" } ?: "") } +
                listOf("AI trades go live: ${!com.optionslab.app.ira.IraNewsTrades.paperFirst}", "Stops done automatically: ${com.optionslab.app.ira.IraHub.autoStop}") }
            section("Jarvis's trades") { com.optionslab.app.ira.IraNewsTrades.record() }
            section("Today's suggestions (scorecard)") { com.optionslab.app.ira.IraNewsTrades.scorecard() }
            section("Solo") { com.optionslab.app.ira.IraSolo.status() }
            val goals = runCatching { com.optionslab.app.ira.IraGoals.say() }.getOrElse { "could not read" }
            section("Goals") { goals }
            val lessons = runCatching { com.optionslab.app.ira.IraAccount.lessons().let { (l, n) -> com.optionslab.ira.Lessons.say(l, n) } }.getOrElse { "could not read" }
            section("Lessons") { lessons }
            val tests = runCatching { com.optionslab.app.ira.IraExpert.say() }.getOrElse { "could not read" }
            section("Paper tests") { tests }
        }
        // Newest first: a long report pasted into a chat is cut at its end, and today's events are the ones that matter.
        append("\n-- Events (newest first) --\n")
        synchronized(this@Diag) { diary().toList() }.takeLast(400).asReversed().forEach { append(redact(it)).append('\n') }
        append("\n-- Strategy notes --\n")
        runCatching { Strategies.log().take(40).reversed() }.getOrDefault(emptyList()).forEach {
            append("${TIME.format(Instant.ofEpochMilli(it.at))} ${it.strategy}: ${redact(it.message)}\n")
        }
        append("\n-- Pine notes --\n")
        PineAuto.log.value.takeLast(40).forEach { append("${TIME.format(Instant.ofEpochMilli(it.at))} #${it.script}: ${redact(it.text)}\n") }
    }

    /** IraGoldAlgo: every arm's state, the price feed and what the phone allows in the background. No keys exist in this app. */
    internal fun gold(): String = buildString {
        val booksRead = GoldBooks.awaitBlocking()
        val t = GoldPaper.now()
        val liq = GoldPaper.book.value
        val tr = GoldTrendPaper.book.value
        val dp = GoldDipPaper.book.value
        val ts = GoldTasPaper.book.value
        append("\n-- Gold --\n")
        if (!booksRead) append("The saved paper books are still being read; the empty ones show below.\n")
        append("Now ${GoldPaper.when_(t)} · gold trading: ${com.optionslab.engine.gold.GoldLiquidity.inSession(t)}\n")
        append("Price ${liq.price?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none"} at ${liq.priceAt?.let { GoldPaper.when_(it) } ?: "-"} · feed delayed: ${GoldPaper.stale(liq, t)}\n")
        // Battery (round 8): with nothing to watch the 5-minute alarm leaves the feed unread, so an old price is expected then.
        if (!com.optionslab.ira.GoldPass.due(booksRead, liq.armed || tr.armed || dp.armed || ts.armed,
                liq.position != null || tr.position != null || dp.position != null || ts.position != null, tr.waitFlip))
            append("  (no arm armed and nothing held: the background pass skips the price read; the Gold screen reads it while open)\n")
        app?.let { c ->
            append("Notifications: ${runCatching { com.optionslab.app.work.Notifier.canPost(c) }.getOrNull()} · precise alarms: " +
                "${runCatching { com.optionslab.app.work.Jobs.canExact(c) }.getOrNull()} · left out of battery saving: " +
                "${runCatching { com.optionslab.app.ui.screens.BatteryCheck.unrestricted(c) }.getOrNull()}\n")
        }
        append("Paper account: start ${GoldPaper.usd(liq.start)} · lot ${liq.lots} · realised ${GoldPaper.usd(liq.realized + tr.realized + dp.realized + ts.realized)}\n")
        append("Liquidity 1h: armed ${liq.armed} · status \"${redact(liq.status)}\" · decided ${liq.decided ?: "-"}" +
            (if (liq.armed && liq.position == null) " · next ${GoldPaper.nextDecision(t)}" else "") + "\n")
        liq.position?.let { p -> append("  open: bought %.2f at ${GoldPaper.when_(p.entryTime)} · level %.2f · target ${p.target?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none"}\n".format(Locale.ENGLISH, p.entry, p.level)) }
        append("  trades ${liq.trades.size} · realised ${GoldPaper.usd(liq.realized)} · last signal ${liq.lastSignal ?: "none"}\n")
        append("${GoldTrendPaper.NAME}: armed ${tr.armed} · status \"${redact(tr.status)}\" · decided ${tr.decided ?: "-"}" +
            (if (tr.armed && tr.position == null) " · next ${GoldTrendPaper.nextDecision(t)}" else "") + "\n")
        append("  trend ${when (tr.up) { true -> "up"; false -> "down"; null -> "not known yet" }}" +
            (tr.line?.let { (if (tr.up == false) " · turns up on a 4-hour close above %.2f" else " · line %.2f").format(Locale.ENGLISH, it) } ?: "") +
            " · waiting for a flip after a lock sale: ${tr.waitFlip}\n")
        tr.position?.let { p -> append(("  open: bought %.2f at ${GoldPaper.when_(p.entryTime)} · %.2f lot · ATR %.2f · top (bid) %.2f · lock " +
            "${tr.stop?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "not started"}\n").format(Locale.ENGLISH, p.entry, p.lots, p.atr, p.peak)) }
        append("  trades ${tr.trades.size} · realised ${GoldPaper.usd(tr.realized)} · last signal ${tr.lastSignal ?: "none"}\n")
        append("${GoldDipPaper.NAME}: armed ${dp.armed} · status \"${redact(dp.status)}\" · decided ${dp.decided ?: "-"}\n")
        dp.position?.let { p -> append(("  open: bought %.2f at ${GoldPaper.when_(p.entryTime)} · %.2f lot · ATR %.2f · top (bid) %.2f · lock " +
            "${dp.stop?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "not started"}\n").format(Locale.ENGLISH, p.entry, p.lots, p.atr, p.peak)) }
        append("  trades ${dp.trades.size} · realised ${GoldPaper.usd(dp.realized)} · last signal ${dp.lastSignal ?: "none"}\n")
        append("${GoldTasPaper.NAME}: armed ${ts.armed} · status \"${redact(ts.status)}\" · decided ${ts.decided ?: "-"} · bought turn ${ts.usedTurn ?: "-"}\n")
        append("  tracker ${when (ts.up) { true -> "up"; false -> "down"; null -> "not known yet" }}" +
            (ts.line?.let { " · line %.2f".format(Locale.ENGLISH, it) } ?: "") + (ts.score?.let { " · score %+.0f%%".format(Locale.ENGLISH, it) } ?: "") + "\n")
        ts.position?.let { p -> append(("  open: bought %.2f at ${GoldPaper.when_(p.entryTime)} · %.2f of %.2f lot left · stop %.2f · R %.2f · targets hit ${p.hit}\n")
            .format(Locale.ENGLISH, p.entry, p.left, p.lots, p.stop, p.risk)) }
        append("  trades ${ts.trades.size} · realised ${GoldPaper.usd(ts.realized)} · last signal ${ts.lastSignal ?: "none"}\n")
        (liq.trades + tr.trades + dp.trades + ts.trades).sortedBy { it.exitTime }.takeLast(10).asReversed().forEach { x ->
            append("  ${GoldPaper.arm(x)}: ${GoldPaper.when_(x.entryTime)} -> ${GoldPaper.when_(x.exitTime)} · %.2f -> %.2f · ${x.why} · ${GoldPaper.usd(x.pnl)}\n"
                .format(Locale.ENGLISH, x.entry, x.exit))
        }
    }

    @Synchronized
    fun wipe() { cache = null; dirty = false; unread = false; if (::file.isInitialized) file.delete() }
}
