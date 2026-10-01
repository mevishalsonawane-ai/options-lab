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

    @Synchronized
    private fun diary(): ArrayDeque<String> {
        cache?.let { return it }
        val out = ArrayDeque<String>()
        runCatching {
            val a = JSONArray(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            for (i in 0 until a.length()) out.addLast(a.getString(i))
        }
        cache = out
        return out
    }

    /** One writer thread: a banner shown from the screen's thread never waits on the disk. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "diag").apply { isDaemon = true } }

    /** Keep one event: "[area] text", time-stamped (IST) now, written in the background. Never throws. */
    fun record(area: String, text: String) {
        if (!::file.isInitialized) return
        val line = "${TIME.format(Instant.now())} [$area] ${redact(text.replace('\n', ' '))}"
        runCatching { writer.execute { keep(line) } }
    }

    @Synchronized
    private fun keep(line: String) {
        runCatching {
            val l = diary()
            l.addLast(line)
            while (l.size > MAX) l.removeFirst()
            Vault.writeFile(file, JSONArray(l.toList()).toString().toByteArray(Charsets.UTF_8))
        }
    }

    /** Waits for the writes queued so far (tests, and the report, read what was just recorded). */
    fun flush() { runCatching { writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) } }

    /** The report the owner copies: the app's state (no identities, no keys), then the latest events and bot notes. */
    suspend fun report(): String = buildString {
        flush()
        val s = runCatching { AppSettings.load() }.getOrNull()
        append("${com.optionslab.app.ui.components.BrandName.name} diagnostics · ${TIME.format(Instant.now())} IST\n")
        append("App ${com.optionslab.app.BuildConfig.VERSION_NAME} (build ${com.optionslab.app.BuildConfig.COMMIT}) · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        append("Mode: ${if (s?.live == true) "LIVE" else "Paper"} · real orders allowed: ${s?.allowRealOrders} · kill switch: ${s?.guardKill}\n")
        append("Market open: ${Market.isOpen()} · Zerodha linked: ${Broker.linked} · logged in: ${Broker.loggedIn}\n")
        append("Static IP set: ${StaticIp.registered != null} · relay on: ${Relay.enabled} · relay connected: ${runCatching { Relay.connected }.getOrDefault(false)}\n")
        if (com.optionslab.app.BuildConfig.GOLD) append(gold())
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

    /** IraGoldAlgo: both arms' state, the price feed and what the phone allows in the background. No keys exist in this app. */
    internal fun gold(): String = buildString {
        val t = GoldPaper.now()
        val liq = GoldPaper.book.value
        val tr = GoldTrendPaper.book.value
        append("\n-- Gold --\n")
        append("Now ${GoldPaper.when_(t)} · gold trading: ${com.optionslab.engine.gold.GoldLiquidity.inSession(t)}\n")
        append("Price ${liq.price?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none"} at ${liq.priceAt?.let { GoldPaper.when_(it) } ?: "-"} · feed delayed: ${GoldPaper.stale(liq, t)}\n")
        app?.let { c ->
            append("Notifications: ${runCatching { com.optionslab.app.work.Notifier.canPost(c) }.getOrNull()} · precise alarms: " +
                "${runCatching { com.optionslab.app.work.Jobs.canExact(c) }.getOrNull()} · left out of battery saving: " +
                "${runCatching { com.optionslab.app.ui.screens.BatteryCheck.unrestricted(c) }.getOrNull()}\n")
        }
        append("Paper account: start ${GoldPaper.usd(liq.start)} · lot ${liq.lots} · realised ${GoldPaper.usd(liq.realized + tr.realized)}\n")
        append("Liquidity 1h: armed ${liq.armed} · status \"${redact(liq.status)}\" · decided ${liq.decided ?: "-"}" +
            (if (liq.armed && liq.position == null) " · next ${GoldPaper.nextDecision(t)}" else "") + "\n")
        liq.position?.let { p -> append("  open: bought %.2f at ${GoldPaper.when_(p.entryTime)} · level %.2f · target ${p.target?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none"}\n".format(Locale.ENGLISH, p.entry, p.level)) }
        append("  trades ${liq.trades.size} · realised ${GoldPaper.usd(liq.realized)} · last signal ${liq.lastSignal ?: "none"}\n")
        append("${GoldTrendPaper.NAME}: armed ${tr.armed} · status \"${redact(tr.status)}\" · decided ${tr.decided ?: "-"}" +
            (if (tr.armed && tr.position == null) " · next ${GoldTrendPaper.nextDecision(t)}" else "") + "\n")
        append("  trend ${when (tr.up) { true -> "up"; false -> "down"; null -> "not known yet" }}" +
            (tr.line?.let { " · line %.2f".format(Locale.ENGLISH, it) } ?: "") + " · waiting for a flip after a lock sale: ${tr.waitFlip}\n")
        tr.position?.let { p -> append(("  open: bought %.2f at ${GoldPaper.when_(p.entryTime)} · %.2f lot · ATR %.2f · top (bid) %.2f · lock " +
            "${tr.stop?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "not started"}\n").format(Locale.ENGLISH, p.entry, p.lots, p.atr, p.peak)) }
        append("  trades ${tr.trades.size} · realised ${GoldPaper.usd(tr.realized)} · last signal ${tr.lastSignal ?: "none"}\n")
        (liq.trades + tr.trades).sortedBy { it.exitTime }.takeLast(10).asReversed().forEach { x ->
            append("  ${GoldPaper.arm(x)}: ${GoldPaper.when_(x.entryTime)} -> ${GoldPaper.when_(x.exitTime)} · %.2f -> %.2f · ${x.why} · ${GoldPaper.usd(x.pnl)}\n"
                .format(Locale.ENGLISH, x.entry, x.exit))
        }
    }

    @Synchronized
    fun wipe() { cache = null; if (::file.isInitialized) file.delete() }
}
