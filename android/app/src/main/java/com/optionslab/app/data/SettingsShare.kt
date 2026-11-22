package com.optionslab.app.data

import android.content.Context
import android.content.Intent
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.ira.ArmAutoStops
import com.optionslab.ira.StateShare
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 20 Nov 2026 (Boss: "Can we create a button where i could share u all the setting details always which could help you analysis"):
 * "Share full settings and state" (top of Settings, and beside Copy diagnostics). ONE plain-text file, written to the cache's
 * exports/state/ folder (the exports FileProvider's only folder) and handed to Android's share sheet - never clipboard text: Boss's
 * 20 Nov note was cut at about 140 lines, so the arms' live state never reached it and it took several round trips to find R51 / R72's
 * "Not mirrored today: 8 (last: the account stop rule ...)".
 *
 * What it holds, under [StateShare]'s headings: the header (mode, AI live, kill switch, market, Zerodha logged in yes / no, the relay and
 * stream), each arm's live gates, every switch in Bot settings and Settings, every stop or limit that can block an entry with its figure,
 * the full [Diag.report], today's decisions per arm and today's diary by category. Every line goes through [StateShare.redact]: no key,
 * token, PIN, relay address, IP, user or client id, phone, e-mail or name. P&L figures stay (Boss's own; he shares the file himself).
 *
 * Built only on a worker ([share] moves it there), from the existing views and lines - memory wherever they allow; a read that may wait
 * (the strategies' book, the ORB view) is bounded by [WAIT_MS]. Nothing here is on the order or tick path, and nothing it calls changes
 * any state: it only reads.
 */
object SettingsShare {
    private const val SUB = "exports/state"
    private const val WAIT_MS = 3_000L
    /** The written file is deleted this long after it is written (it holds Boss's P&L outside the app's encryption). */
    internal const val KEEP_MS = 30 * 60_000L
    /** The full diagnostics report's own bound (it writes the diary first, then reads the strategies' book under its lock). */
    private const val REPORT_WAIT_MS = 10_000L
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(com.optionslab.engine.IST)

    /** TEST ONLY: the thread the last file was built on (the no-main-thread test). */
    @Volatile internal var builtOn: Thread? = null

    private fun yes(b: Boolean?) = when (b) { true -> "yes"; false -> "no"; null -> "not read" }
    private fun onOff(b: Boolean?) = when (b) { true -> "on"; false -> "off"; null -> "not read" }
    private inline fun line(fallback: String, f: () -> Any?): String =
        runCatching { f()?.toString() ?: "$fallback: none" }.getOrElse { "$fallback: could not read (${it.javaClass.simpleName})" }

    private suspend fun <T> bounded(f: suspend () -> T): T? =
        runCatching { kotlinx.coroutines.withTimeoutOrNull(WAIT_MS) { f() } }.getOrNull()

    /**
     * Values the app knows are secret or personal, removed wherever they appear as whole words (never kept after the build): the keys and
     * addresses, and - as the uploaded day file's scrub does ([DiagDayHost]) - the holder's name whole and word by word, the Zerodha user
     * id, the GitHub login and the data repository's owner (a personal handle).
     */
    internal fun known(): List<String> = listOfNotNull(
        runCatching { Broker.apiKey }.getOrNull(), runCatching { Broker.userId }.getOrNull(), runCatching { Broker.userName }.getOrNull(),
        runCatching { GitHubSync.login }.getOrNull(), com.optionslab.ira.CloudSync.OWNER,
        runCatching { Broker.streamToken() }.getOrNull(), runCatching { Relay.host }.getOrNull(),
        runCatching { Relay.user }.getOrNull()?.takeIf { it != "ubuntu" }, runCatching { Relay.publicKey }.getOrNull(),
        runCatching { StaticIp.registered }.getOrNull(),
        runCatching { com.optionslab.app.security.SecurePrefs.getString(NtfyReader.K_TOPIC) }.getOrNull(),
        runCatching { com.optionslab.app.security.SecurePrefs.getString(NtfyReader.K_SERVER) }.getOrNull(),
        runCatching { com.optionslab.app.security.SecurePrefs.getString(NtfyReader.K_TOKEN) }.getOrNull(),
    ).filter { it.isNotBlank() } + runCatching { DiagDayHost.nameWords(Broker.userName) }.getOrDefault(emptyList())

    // ---- 1. header ---------------------------------------------------------------------------------------------------------------

    private fun header(s: AppSettings?, diary: List<String>): List<String> {
        val ai = runCatching { !com.optionslab.app.ira.IraNewsTrades.paperFirst }.getOrNull()
        return listOf(
            "Time: ${TIME.format(Instant.now())} IST",
            "App ${com.optionslab.app.BuildConfig.VERSION_NAME} (build ${com.optionslab.app.BuildConfig.COMMIT}) · Android ${android.os.Build.VERSION.RELEASE} · " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "Mode: ${if (s?.live == true) "LIVE" else if (s == null) "not read" else "Paper"} · real orders allowed: ${yes(s?.allowRealOrders)} · " +
                "AI trades go live: ${onOff(ai)}",
            "Kill switch: ${onOff(s?.guardKill)}",
            "Market open: ${yes(runCatching { Market.isOpen() }.getOrNull())} · market day ${runCatching { Market.today() }.getOrNull()}",
            "Zerodha: set up ${yes(runCatching { Broker.linked }.getOrNull())} · logged in ${yes(runCatching { Broker.loggedIn }.getOrNull())}",
            "Relay: on ${yes(runCatching { Relay.enabled }.getOrNull())} · connected ${yes(runCatching { Relay.connected }.getOrNull())} · " +
                "static IP set ${yes(runCatching { StaticIp.registered != null }.getOrNull())}",
            line("Live stream") { KiteStream.statusLine(diary) },
            line("Arms' live book") { ArmLive.view.value.let { v -> "Arms' live book: " + if (!v.loaded) "not read yet" else if (v.unreadable) "UNREADABLE (no live entries)" else "read" } },
        )
    }

    // ---- 2. live gates per arm ---------------------------------------------------------------------------------------------------

    /** The gate's answers that are the same for every arm (each as an entry would read it now). */
    private class Common(val appLive: Boolean, val aiLive: Boolean, val kill: Boolean, val paperOnly: String?, val stopped: String?,
                         val mcxStopped: Boolean, val lossLimit: Boolean, val dayLock: String?, val accountStop: String,
                         val accountRefusal: String?, val loggedIn: Boolean)

    /** A read that may wait on a lock: (read, value); not read within [WAIT_MS] or failed: (false, null). */
    private suspend fun <T> tryRead(f: suspend () -> T): Pair<Boolean, T?> =
        runCatching { kotlinx.coroutines.withTimeoutOrNull(WAIT_MS) { true to f() } }.getOrNull() ?: (false to null)

    /**
     * Each answer as the entry's own gate ([ArmLive] gate) reads it - and, as there, a check that cannot be read counts as refusing
     * (never as passing): the day stop, the loss door, the day lock and the account stop.
     */
    private suspend fun common(s: AppSettings?): Common {
        val (appLive, aiLive) = runCatching { ArmLive.liveFlags() }.getOrDefault(false to false)
        val (indexRead, indexWhy) = tryRead { Strategies.stoppedWhy() }
        val stopped = if (!indexRead) "could not be checked in ${WAIT_MS / 1000} s (an entry now would be refused)"
            else indexWhy?.let { runCatching { com.optionslab.ira.DayStop.line(it) }.getOrDefault(it.toString()) }
        // The commodity side as [ArmLive]'s sideStopped reads it: its own stop, an older build's kept stop, or the index side's LOSS stop.
        val (mcxRead, mcxWhy) = tryRead { Strategies.mcxStoppedWhy() }
        val mcxStopped = !indexRead || !mcxRead || indexWhy == com.optionslab.ira.DayStop.Why.LOSS || mcxWhy != null ||
            runCatching { LossBreaker.bossStoppedToday() }.getOrDefault(true)
        val (acct, acctRefusal) = runCatching { ArmLive.accountStopNow() }
            .getOrElse { "Arms' live-copies account stop: could not read" to ArmLive.ACCOUNT_UNCHECKED }
        return Common(appLive, aiLive, s?.guardKill == true, runCatching { PaperOnly.entryRefusal() }.getOrElse { PaperOnly.ENTRY_UNREAD },
            stopped, mcxStopped, runCatching { LossBreaker.liveDoorTripped() }.getOrDefault(true),
            runCatching { DayLockGuard.refusal(true) }.getOrElse { "the day lock could not be checked" }, acct, acctRefusal,
            runCatching { Broker.loggedIn }.getOrDefault(false))
    }

    /** One part of a section: a read that fails says so on one line, and the rest of the file still builds. */
    private suspend fun part(name: String, f: suspend () -> List<String>): List<String> = try {
        if (testFailPart == name) throw IllegalStateException("test: $name fails")
        f()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e      // (the share cancelled: stop, never "could not be read" lines)
    } catch (e: Exception) { listOf("$name: could not be read (${e.javaClass.simpleName})") }

    /** The report's own worker ([text]): one that runs past its bound finishes there, never holding the share up. */
    private val reportScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** TEST ONLY: the part whose read throws (a failing view). Debug builds only. */
    @Volatile internal var testFailPart: String? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    private fun mirroredArms(c: Common, today: LocalDate): List<String> {
        val v = ArmLive.view.value
        val legs = runCatching { ArmLive.paperLegsNow() }.getOrDefault(emptyMap())
        val out = ArrayList<String>()
        out += "Mirrored arms (each follows its own paper trade at Zerodha): book ${if (!v.loaded) "not read yet" else if (v.unreadable) "UNREADABLE" else "read"}."
        for (spec in LiveMirror.ARMS) runCatching {
            if (spec.key == LiveMirror.SOLO_MIDDAY && !com.optionslab.app.BuildConfig.JARVIS) return@runCatching
            val on = v.on(spec.key)
            val shadow = v.shadowOn(spec.key)
            val (n, net) = v.record(spec.key)
            val (skipped, skipWhy) = v.notMirroredToday(spec.key, today)
            val gates = com.optionslab.engine.risk.OrderGates.arm(on, ArmLive.liveFlagsKept()?.second ?: c.aiLive, armedWithPin = on,
                fingerprint = runCatching { OrderVerdicts.lastMatched("arm:${spec.key}") }.getOrNull(),
                refusal = runCatching { OrderVerdicts.lastRefused("arm:${spec.key}") }.getOrNull())
            val mcx = spec.exchange == "MCX"
            val gate = LiveMirror.Gate(appLive = c.appLive, aiLive = c.aiLive, armOn = on, paperOnly = c.paperOnly, kill = c.kill,
                stopped = if (mcx) c.mcxStopped else c.stopped != null, lossLimit = c.lossLimit, dayLock = c.dayLock,
                // 20 Nov 2026 (main, Boss: "make it follow switch"): R51 / R72 pass it while his "Liquidity live stop rule" switch is off.
                accountStop = if (runCatching { ArmLive.accountStopActs(spec.key) }.getOrDefault(true)) c.accountRefusal else null, loggedIn = c.loggedIn)
            // The entry door's own look: an unread or unreadable book refuses every mirrored entry.
            val refusal = LiveMirror.refusal(gate) ?: if (!v.loaded || v.unreadable) "the arms' live book is not read" else null
            val paper = legs[spec.key].orEmpty()
            out += "- ${spec.label} [${spec.key}, ${spec.exchange} ${spec.product}]: ${ArmLive.stateWords(on, shadow)}" +
                " · open live ${v.openReal.count { it.key == spec.key }}, open in shadow ${v.openShadow.count { it.key == spec.key }}" +
                " · live record ${if (n == 0) "none" else "$n, ${LiveMirror.rs(net)}"} · shadow record ${LiveMirror.recordWords(v.shadowRecord(spec.key))}"
            out += "    Gates: " + gates.joinToString(" · ") { it.line() }
            out += "    A live entry now: " + when {
                shadow && on -> "recorded in shadow only (nothing sent)" + (refusal?.let { "; and refused anyway: $it" } ?: "")
                refusal != null -> "refused: $refusal"
                else -> "would go (every gate above passes; the phone's security check, the funds check, the guard and the door still apply)"
            }
            out += "    Not mirrored today: $skipped" + (skipWhy?.let { " (last: $it)" } ?: "")
            v.refused[spec.key]?.let { out += "    Last refused live entry: $it" }
            out += "    Paper now: ${paper.size} trade(s) known to the live path, ${paper.count { it.qty > 0 }} held" +
                paper.filter { it.qty > 0 }.mapNotNull { it.symbol }.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString()})" }.orEmpty()
            runCatching { ArmAutoStopsHost.view.value.rowLine(spec.key) }.getOrNull()?.let { out += "    Automatic stops: $it" }
        }.onFailure { out += "- ${spec.label}: could not be read (${it.javaClass.simpleName})" }
        return out
    }

    private fun passedArms(today: LocalDate): List<String> {
        val v = PassedArmsHost.view.value
        if (!v.loaded) return listOf("Passed-research arms (R51, R72, R52, Solo 2.0): not read yet.")
        return listOf("Passed-research arms' paper today (R51, R72, R52, Solo 2.0):") + PassedArmsHost.SOURCES.map { s ->
            val t = v.todayOf(s)
            "- ${PassedArmsHost.label(s)}: ${if (v.on(s)) "on" else "off (records would-be trades)"}" + (v.paused[s]?.let { " (switched itself off: $it)" } ?: "") +
                " · today ${t.size} paper trade(s), ${t.count { it.exit == null }} held" +
                (t.filter { it.exit == null }.map { it.symbol }.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString()})" } ?: "") +
                " · today's P&L ${LiveMirror.rs(v.todayPnl(s))} · ${v.status[s] ?: "no status"}"
        } + line("Passed arms") { PassedArmsHost.diagLine() } + "Today: $today"
    }

    private suspend fun orbArms(today: LocalDate): List<String> {
        val v = bounded { OrbArms.view() } ?: return listOf("ORB family and Liquidity 15+5: the arms' view could not be read in ${WAIT_MS / 1000} s.")
        val out = ArrayList<String>()
        out += "ORB family and Liquidity 15+5 (their own live path; armed in Live with the PIN, Liquidity by your switch on Home):"
        v.stopped?.let { out += "Stopped for today: $it" }
        for (a in v.arms) runCatching {
            val todayTrades = a.today.filter { it.day == today }
            out += "- ${a.arm.label} [${a.arm.source}]: ${if (a.armed) "on" else "off"} · ${if (a.automatic) "automatic" else "approve each"}" +
                " · cleared for Zerodha ${yes(a.cleared)} · would trade live now ${yes(a.liveOk)}" +
                (if (a.arm.paperOnly) " · goes live through its mirror" else "") + (if (a.bossParked) " · parked by your choice" else "") +
                " · today ${todayTrades.size} trade(s) (${todayTrades.count { it.live }} live), held now ${a.open?.let { "${it.symbol}${if (it.live) " LIVE" else ""}" } ?: "none"}"
            out += "    Status: ${a.status.ifBlank { "-" }}"
            a.offShadow?.let { out += "    $it" }
            if (a.arm == com.optionslab.engine.orb.LiquidityRules.ARM) {
                out += "    Lots: ${a.lots}" + (a.lotsAsk?.let { " (a restore's $it waits for you)" } ?: "") + " · parked indices: ${a.parked.ifEmpty { listOf("none") }.joinToString()}" +
                    " · review park to paper ${yes(a.reviewParked)} · review pause ${yes(a.reviewPaused)}" +
                    " · on paper until your switch: ${a.paperUntilSwitch.ifEmpty { listOf("none") }.joinToString()}"
                a.test?.stop?.forEach { out += "    $it" }
                runCatching { OrbArms.liquidityBookLines() }.getOrNull()?.forEach { out += "    Book: $it" }
                    ?: run { out += "    Books: not in memory yet" }
            }
        }.onFailure { out += "- ${runCatching { a.arm.label }.getOrDefault("an arm")}: could not be read (${it.javaClass.simpleName})" }
        return out
    }

    private suspend fun otherArms(): List<String> {
        val out = ArrayList<String>()
        val strat = bounded { Strategies.all() }
        out += if (strat == null) "Saved strategies: could not be read in ${WAIT_MS / 1000} s." else "Saved strategies (${strat.size}):"
        strat?.forEach { e -> out += "- ${e.def.name}: live ${onOff(e.def.liveEnabled)} · running ${yes(e.running)} · ${e.run?.status ?: "not run"}" +
            (e.def.scheduler?.let { " · scheduled" } ?: "") }
        val held = runCatching { PineAuto.held.value }.getOrDefault(emptyMap())
        out += "Pine scripts: ${held.size} held now (${held.values.count { it.live }} live)" +
            held.entries.joinToString("") { (id, h) -> "; #$id ${h.symbol} ${h.qty} ${if (h.live) "LIVE" else "paper"}" }
        for (f in listOf(
            "Night" to { NightArm.diagLine() }, "VIX divergence" to { VixDivArm.diagLine() }, "S5 Trail" to { S5Arm.diagLine(hideNet = false) },
            "CISD + network" to { CisdArm.diagLine(hideNet = false) }, "FT3" to { Ft3Arm.diagLine() }, "Flow" to { FlowArm.diagLine() },
            "ntfy alerts" to { NtfyArm.diagLine() }, "Hero ticket" to { HeroTicketArm.diagLine() }, "MCX paper arms" to { McxPaperArms.diagLine() },
            "Arms live" to { ArmLive.diagLine() },
        )) out += line(f.first) { f.second() }
        if (com.optionslab.app.BuildConfig.JARVIS) out += line("Solo") { com.optionslab.app.ira.IraSolo.status() }
        return out
    }

    // ---- 3. switches -------------------------------------------------------------------------------------------------------------

    private fun switches(s: AppSettings?): List<String> {
        val out = ArrayList<String>()
        out += "Automatic stops on R51, R72 and Liquidity (Bot settings; off takes the PIN):"
        val auto = runCatching { ArmAutoStopsHost.view.value }.getOrNull()
        for (r in ArmAutoStops.Rule.entries) out += "- ${r.switch}: ${if (auto?.loaded != true) "not read yet (counts as on)" else onOff(auto.isOn(r))}"
        out += line("Automatic stops") { ArmAutoStopsHost.line() }
        auto?.notice()?.let { out += "  Notice: $it" }
        val pv = PassedArmsHost.view.value
        out += "R151 picker (R72 / R51, Boss's exception): ${if (pv.loaded) onOff(pv.pickOn) else "not read"} · R64 lock ${onOff(pv.lockOn)} · " +
            "L25 ${onOff(pv.l25On)} · Supertrend filter ${onOff(pv.stOn)} · R140 wide-day skip ${onOff(pv.wideOn)} · R142 no-progress ${onOff(pv.np5On)} · W20 label ${onOff(pv.w20On)}"
        out += "Paper refusals go live (the paper-refusal live switch): ${if (pv.loaded) onOff(pv.refusalLiveOn) else "not read"} · today would have gone live: ${pv.refusalShadowToday}"
        out += line("Exit rule V4 / V5") { StepTrailExits.line() }
        out += "Exit rule switches: V4 (breakeven at +10%) ${onOff(runCatching { StepTrailExits.isOn() }.getOrNull())} · V5 lock on top ${onOff(runCatching { StepTrailExits.lockOn() }.getOrNull())}"
        out += line("Funds check") { FundsCheck.line() }
        out += line("Cheaper strike") { CheaperStrike.line() }
        // 20 Nov 2026: Liquidity's fresh-bar check (shadow until on) and its list wait (off until on with the PIN).
        out += line("Liquidity fresh-bar check") { LiquidityFreshBar.view.value.let { v -> if (!v.loaded) "not read yet (off)" else
            "fresh-bar check ${onOff(v.on)} (${v.wouldSkipToday} would-skips, ${v.skippedToday} skipped today) · list wait ${onOff(v.listOn)} " +
                "(${v.listWouldWaitToday} would-waits today)" } }
        // (20 Nov 2026, audit B5: the order count check, shadow by default.)
        out += line("Order count check") { OrderCountCheck.line() }
        out += line("Same-direction cap") { ExposureCapHost.line() }
        out += line("Same-direction cap, only logged") { ExposureCapHost.onlyLoggedLine() }
        out += line("Same-direction cap switches") { ExposureCapHost.view.value.let { "Same-direction cap switches: paper ${onOff(it.paperOn)}, live ${onOff(it.liveOn)}, max ${it.max}" } }
        out += line("Exposure cap (portfolio guard)") { PortfolioHost.view.value.let { "Exposure cap (portfolio guard, correlated arms): ${onOff(it.on)}, cap ${it.capPct}% of capital" } }
        out += line("Account stop rule") { AccountStopHost.line() }
        runCatching { BossRiskHost.lines() }.getOrElse { listOf("Boss's risk rules: could not read") }.forEach { out += it }
        out += line("Exit coordinator") { ExitCoordinator.line() }
        out += line("Positions-lag guard") { ExitCoordinator.lagLine() }
        out += line("Stop and target as one GTT") { DayOco.line() }
        out += line("Manual positions protect") { ManualCover.line() }
        out += line("Payout-aware drawdown") { PayoutWatch.line() }
        out += "Event shield (pause paper arms on event days): ${line("Event shield") { MarketContext.mode.value.words }}"
        if (com.optionslab.app.ira.Autopilot.enabled()) {
            out += "Autopilot: ${line("Autopilot") { com.optionslab.app.ira.Autopilot.mode.value }}"
            out += line("Autopilot") { com.optionslab.app.ira.Autopilot.diagLine() }
        }
        out += line("Idea win-rate rule") { IdeaRuleHost.rule().let { "Idea win-rate rule: ${onOff(it.on)}, minimum ${it.minPct}%" } }
        out += line("Learning freeze") { com.optionslab.app.ira.LearningFreeze.diagLine() }
        runCatching { TradeManagerHost.diagLines() }.getOrElse { listOf("Trade manager: could not read") }.forEach { out += it }
        out += line("Trend watch") { TrendWatchHost.diagLine() }
        out += line("Order flow modes") { FlowGate.diagLine() }
        out += "Paper-only period: ${line("Paper-only period") { PaperOnly.entryRefusal() ?: "none now" }}"
        out += "Live lots per arm trade: ${s?.armLiveLots ?: "not read"} · indices the arms trade live: ${s?.liveMarkets?.sorted()?.joinToString() ?: "not read"}"
        out += "The app settings (every value the app keeps in AppSettings: Settings, Bot settings and Zerodha's limits):"
        out += s?.let { StateShare.fields(it.toString())?.map { f -> "- $f" + notEnforced(f) } } ?: listOf("- the settings could not be read")
        return out
    }

    /**
     * 22 Nov 2026 (B6 review): AppSettings' old order limits, still saved but enforced nowhere. Since B6-F (22 Nov 2026) voice no longer
     * sets them either: "lots per order" sets the guard's max lots, "manual orders per day" its max orders per day.
     */
    internal val NOT_ENFORCED = setOf("maxOrdersPerDay", "maxLotsPerOrder", "maxOrderValue")

    /** The note a settings dump's line [f] ("name: value") carries when its setting is one of [NOT_ENFORCED]; "" otherwise. */
    internal fun notEnforced(f: String): String = if (f.substringBefore(':') in NOT_ENFORCED)
        " (kept, not enforced: the guard's max orders per day and max lots per instrument are)" else ""

    // ---- 4. stops and limits -----------------------------------------------------------------------------------------------------

    private fun stops(s: AppSettings?, c: Common): List<String> = listOf(
        "Daily loss limit: live ${s?.guardDailyLoss?.let { LiveMirror.rs(-it) } ?: "not read"}, paper ${s?.guardPaperDailyLoss?.let { LiveMirror.rs(-it) } ?: "not read"}, " +
            "MCX paper ${s?.mcxPaperDailyLoss?.let { LiveMirror.rs(-it) } ?: "not read"} · reached today: ${yes(runCatching { LossBreaker.trippedToday() }.getOrNull())}" +
            (runCatching { LossBreaker.tripToday() }.getOrNull()?.let { " ($it)" } ?: "") + " · blocks live entries now: ${yes(c.lossLimit)}",
        "Stop for today: " + (c.stopped ?: "not stopped") + " · MCX side stopped: ${yes(c.mcxStopped)}",
        "Day lock: live ${s?.dayLock?.let { LiveMirror.rs(it) } ?: "not read"}, MCX ${s?.mcxDayLock?.let { LiveMirror.rs(it) } ?: "not read"} · " +
            "blocking live: ${c.dayLock ?: "no"} · blocking paper: ${runCatching { DayLockGuard.refusal(false) }.getOrNull() ?: "no"}",
        line("Day lock") { DayLockGuard.diagLine() },
        line("Account stop (whole account)") { AccountStopHost.line() },
        c.accountStop,
        line("Liquidity live stop rule") { "Liquidity's live stop rule (its switch ${onOff(ArmAutoStopsHost.isOn(ArmAutoStops.Rule.LIVE_STOP))}): " +
            com.optionslab.ira.LiquidityStopRule.line(OrbArms.liquidityStopHint, live = true) },
        line("Liquidity paper stop rule") { com.optionslab.ira.LiquidityStopRule.line(OrbArms.liquidityStopPaperHint, live = false) },
        "Paper account stop and weekly stop (Boss's risk rules):",
    ) + runCatching { BossRiskHost.lines().take(2) }.getOrElse { listOf("could not read") } + listOf(
        // 22 Nov 2026 (B6 review): the guard's limits by their own names - the ones every entry is checked against. The old order limits
        // ("orders a day", "lots an order") are enforced nowhere and are no longer said here ([NOT_ENFORCED]).
        "Guard limits (every entry is checked against these): max open ${s?.guardMaxOpen}, max orders per day ${s?.guardMaxTrades}, " +
            "max lots per instrument ${s?.guardMaxLots}, max value ${s?.guardMaxValue}, max exposure ${s?.guardMaxExposure}, " +
            "drawdown ${s?.guardDrawdownPct}%, cutoff minute ${s?.guardCutoff}",
        "Paper-only period: ${c.paperOnly ?: "none now"}",
        "Zerodha logged in: ${yes(c.loggedIn)}",
    )

    // ---- 6. today's decisions ----------------------------------------------------------------------------------------------------

    private suspend fun decisions(today: List<String>, day: LocalDate): List<String> {
        val notes = ArrayList<String>()
        // (The arms' live book's own notes carry no date; their dated copies are the diary's [armlive] lines, in [today].)
        notes += runCatching { PassedArmsHost.view.value.log.filter { it.startsWith(day.toString()) } }.getOrDefault(emptyList())
        val dayMs = day.atStartOfDay(com.optionslab.engine.IST).toInstant().toEpochMilli()
        // (Strategies.log is newest first: turned oldest first like the rest, so each arm keeps its LATEST notes.)
        bounded { Strategies.log() }?.reversed()?.filter { it.at >= dayMs }?.forEach { notes += "${TIME.format(Instant.ofEpochMilli(it.at))} ${it.strategy}: ${it.message}" }
        runCatching { PineAuto.log.value }.getOrDefault(emptyList()).filter { it.at >= dayMs }
            .forEach { notes += "${TIME.format(Instant.ofEpochMilli(it.at))} Pine #${it.script}: ${it.text}" }
        val all = notes + today
        val labels = LiveMirror.ARMS.map { it.label } + listOf("Liquidity", "ORB", "Pine", "Strategy")
        val out = ArrayList<String>()
        for (l in labels.distinct()) {
            val mine = StateShare.naming(all, l)
            if (mine.isEmpty()) continue
            out += "-- $l (last ${mine.size}):"
            mine.forEach { out += "  $it" }
        }
        if (out.isEmpty()) out += "No arm note today."
        val undated = runCatching { ArmLive.view.value.log }.getOrDefault(emptyList())
        if (undated.isNotEmpty()) {
            out += "-- The arms' live book's last ${undated.size} notes (HH:MM only: they may include earlier days):"
            undated.forEach { out += "  $it" }
        }
        return out
    }

    private fun diary(today: List<String>): List<String> = StateShare.DIARY_AREAS.flatMap { a ->
        val l = StateShare.inArea(today, a)
        listOf("-- [$a] ${l.size} line(s) today (the last ${StateShare.DIARY_PER_AREA} at most):") + l.map { "  $it" }
    }

    // ---- the file ----------------------------------------------------------------------------------------------------------------

    /** The file's text, redacted. A worker thread only (never the main thread; [share] moves it there). */
    suspend fun text(): String {
        builtOn = Thread.currentThread()
        val s = runCatching { AppSettings.load() }.getOrNull()
        // Bounded: the report reads the strategies' book under its lock (and writes the diary first), and swallows its own reads'
        // failures, so it is built on its own worker and only WAITED for here; one past the bound finishes there and is dropped.
        val building = reportScope.async { Diag.report() }
        val report = try {
            kotlinx.coroutines.withTimeoutOrNull(REPORT_WAIT_MS) { building.await() }
                ?: "The diagnostics report could not be built within ${REPORT_WAIT_MS / 1000} s; the sections above and below stand."
        } catch (e: kotlinx.coroutines.CancellationException) { building.cancel(); throw e
        } catch (e: Exception) { "The diagnostics report could not be built: ${e.javaClass.simpleName}" }
        val diaryAll = runCatching { Diag.lines() }.getOrDefault(emptyList())
        val day = runCatching { Market.today() }.getOrElse { LocalDate.now(com.optionslab.engine.IST) }
        val todayLines = StateShare.today(diaryAll, day)
        val c = common(s)
        val sections = listOf(
            StateShare.Section(StateShare.HEADER, part("The header") { header(s, diaryAll) }),
            StateShare.Section(StateShare.ARMS, part("Mirrored arms") { mirroredArms(c, day) } + part("Passed-research arms") { passedArms(day) } +
                part("ORB family and Liquidity 15+5") { orbArms(day) } + part("Other arms") { otherArms() }),
            StateShare.Section(StateShare.SWITCHES, part("Switches") { switches(s) }),
            StateShare.Section(StateShare.STOPS, part("Stops and limits") { stops(s, c) }),
            StateShare.Section(StateShare.REPORT, listOf(report)),
            StateShare.Section(StateShare.DECISIONS, part("Today's decisions") { decisions(todayLines, day) }),
            StateShare.Section(StateShare.DIARY, part("Today's diary") { diary(todayLines) }),
        )
        return StateShare.build(sections, known())
    }

    /** 20 Nov 2026: the cache folder the last file was written under (the erase finds it without a context). */
    @Volatile private var cacheRoot: File? = null

    private fun folder(): File? = (cacheRoot ?: com.optionslab.app.ira.IraHub.appContext()?.cacheDir)?.let { File(it, SUB) }

    /** One daemon thread that deletes each written file after [KEEP_MS] (never the main thread). */
    private val sweeper by lazy {
        java.util.concurrent.ScheduledThreadPoolExecutor(1) { r -> Thread(r, "share-sweep").apply { isDaemon = true } }
    }

    /** [f] deleted unless a later share wrote it again since [written] (its own sweep deletes that one). */
    internal fun sweep(f: File, written: Long) { if (f.lastModified() == written) runCatching { f.delete() } }

    private val startScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * 22 Nov 2026 (B6 review, item 7): at the app's start, the files a share left in the cache's exports/state/ - the process may have died
     * before their [KEEP_MS] delete ran ([write]'s sweep lives in memory) - are swept ([sweepOld]). In the background, after the open
     * positions are restored and watched (CLAUDE.md); never the main thread.
     */
    fun init(context: Context) {
        val app = context.applicationContext
        startScope.launch {
            runCatching { PositionsFirst.awaitPositions() }
            // (The cache folder found here, not on the start's main thread: finding it may create it.)
            runCatching { val root = app.cacheDir; if (cacheRoot == null) cacheRoot = root; sweepOld(File(root, SUB), System.currentTimeMillis()) }
        }
    }

    /**
     * 22 Nov 2026 (B6 review, item 7): of [dir]'s files, each written [KEEP_MS] or more before [nowMs] - or more than [KEEP_MS] after it (a
     * clock that moved) - is deleted now; any other is deleted when its [KEEP_MS] are up (unless a later share wrote it again), so a file a
     * share is writing at this very moment is never taken. How many went now.
     */
    internal fun sweepOld(dir: File, nowMs: Long): Int {
        var gone = 0
        dir.listFiles()?.forEach { f ->
            val written = f.lastModified()
            val age = nowMs - written
            if (age >= KEEP_MS || age < -KEEP_MS) { if (runCatching { f.deleteRecursively() }.getOrDefault(false)) gone++ }
            else runCatching { sweeper.schedule(Runnable { sweep(f, written) }, KEEP_MS - age, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        return gone
    }

    /** TEST ONLY: the sweeps waiting now (each written file's delete [KEEP_MS] after it). */
    internal fun sweepsWaiting(): Int = sweeper.queue.size

    /** "Erase everything" ([PersonalStores]): the files written for sharing deleted. Nothing is kept in memory. */
    fun wipe() { folder()?.let { runCatching { it.deleteRecursively() } } }

    /** The files' folder (the erase's check). */
    internal fun filesForErase(): List<File> = listOfNotNull(folder())

    /** The file written in the cache's exports/state/ (older ones removed). A worker thread only. */
    suspend fun write(ctx: Context): File {
        val text = text()
        val day = runCatching { Market.today() }.getOrElse { LocalDate.now(com.optionslab.engine.IST) }
        cacheRoot = ctx.cacheDir
        val dir = File(ctx.cacheDir, SUB).apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val f = File(dir, StateShare.fileName(day))
        f.writeText(text, Charsets.UTF_8)
        runCatching { Diag.record("share", "settings and state file written (${text.lines().size} lines, redacted)") }
        // Not kept: deleted [KEEP_MS] later (long enough for the app Boss shares it to), at the next share, or by Erase everything.
        val written = f.lastModified()
        runCatching { sweeper.schedule(Runnable { sweep(f, written) }, KEEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return f
    }

    /** Builds the file on a worker and opens the share sheet on it (Boss's tap; the sheet started on the main thread). */
    suspend fun share(ctx: Context): String {
        // (The Uri too: FileProvider reads its paths and the file's canonical path from disk - never on the main thread.)
        val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".exports", write(ctx))
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "IraAlgo settings and state")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share full settings and state").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { ctx.startActivity(chooser) }
        return "Settings and state ready to share as one file: your P&L and settings, no keys or tokens."
    }
}
