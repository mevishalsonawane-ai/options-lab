package com.optionslab.app.data

import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.DiagDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The day's diagnostics file for the evening GitHub upload (TODO G3, 13 Oct; the report itself is ira-core's [DiagDay]).
 * Gathers, from what the app already keeps, each arm's day (its switch, park, verdicts, trades), the day's orders with who
 * placed them, the app's health lines, the diary's errors, and the change timeline; [DiagDay] scrubs and caps it.
 *
 * Called only from the GitHub sync's run ([GitHubSync.run], the evening worker's thread): never the main thread, the order
 * or the tick path. It reads memory and the files those parts already read for Copy diagnostics; it writes nothing. Every
 * part is read on its own: one that fails is left out, never the whole file. Texts pass [Diag.redact] (keys, the account
 * holder's name) before [DiagDay.scrub]'s final pass; no order id or account id is handed over. 14 Oct review: the other
 * arms' lines and the orders' messages can carry rupees (the arms' "-2,340 after charges", Kite's RMS margins): [DiagDay]
 * strips those before its scrub ([DiagDay.otherArmLine], [DiagDay.orderReason]) and leaves a live order's fill price out.
 */
object DiagDayHost {
    private val GOLD get() = com.optionslab.app.BuildConfig.GOLD

    /** [day]'s report, gzip-compressed JSON. Off the main thread. */
    suspend fun report(day: LocalDate): ByteArray = withContext(Dispatchers.IO) { DiagDay.encode(input(day)) }

    private fun r(s: String): String = runCatching { Diag.redact(s) }.getOrDefault(s)

    /** The words of the holder's [name] of 2 letters or more ("R. K. Om Sharma" → Om, Sharma; 14 Oct review). */
    internal fun nameWords(name: String?): List<String> =
        name.orEmpty().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.distinctBy { it.lowercase() }

    private fun ms(t: LocalDateTime): Long = t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    private fun trade(p: OrbArms.Position, label: String): DiagDay.Trade = DiagDay.Trade(
        arm = p.arm, live = p.live, right = p.right, entryAt = p.entryTime, entry = p.entry, exitAt = p.exitTime, exit = p.exit,
        why = p.why?.let(::r), lots = p.lots, source = Origins.display(label).first, unconfirmed = p.unconfirmed, trail = p.trail != null)

    /** What [DiagDay] reads for [day]. Each part on its own ([runCatching]): a part that fails is left out. */
    internal suspend fun input(day: LocalDate): DiagDay.Input {
        val diary = runCatching { Diag.lines() }.getOrDefault(emptyList())
        val s = runCatching { AppSettings.load() }.getOrNull()
        val mode = linkedMapOf<String, Boolean>()
        s?.let { mode["live"] = it.live; mode["real_orders_allowed"] = it.allowRealOrders; mode["kill_switch"] = it.guardKill }
        runCatching { mode["ai_trades_live"] = !com.optionslab.app.ira.IraNewsTrades.paperFirst }
        runCatching { PaperOnly.ensureLoaded(); mode["paper_only_period"] = PaperOnly.activeNow() != null }

        val arms = ArrayList<DiagDay.Arm>()
        if (!GOLD) runCatching {
            val view = OrbArms.view()
            val closed = runCatching { OrbArms.closedPaper() + OrbArms.closedLive() }.getOrDefault(emptyList())
            for (v in view.arms) {
                val liquidity = v.arm == LiquidityRules.ARM
                val sources = if (liquidity) LiquidityRules.BOOKS.map { it.source }.toSet() else setOf(v.arm.source)
                val owner = if (v.arm.hero) HeroRules.OWNER else v.arm.label
                arms += DiagDay.Arm(
                    key = v.arm.source, label = v.arm.label, armed = v.armed, automatic = v.automatic, liveCleared = v.liveOk,
                    parked = v.bossParked, parkedIndices = v.parked, status = r(v.status), pending = v.pending != null,
                    // The day's trades from the book, not the row's "today": a catch-up run the next morning reads the evening's day.
                    today = (closed + v.today + listOfNotNull(v.open)).filter { it.arm in sources && it.day == day }
                        .distinctBy { Triple(it.arm, it.symbol, it.entryTime) }.map { trade(it, owner) },
                    closed = closed.filter { it.arm in sources }.map { trade(it, owner) },
                    aliases = if (liquidity) LiquidityRules.BOOKS.flatMap { listOf(it.label, it.source) } else emptyList(),
                )
            }
        }

        // The decisions the arms keep in memory (Liquidity's books by their labels, Hero), oldest first; the other arms' come
        // from the diary's "[orb]" lines in DiagDay.
        val decisions = ArrayList<DiagDay.Decision>()
        if (!GOLD) {
            runCatching {
                val labels = LiquidityRules.BOOKS.associate { it.source to it.label }
                OrbArms.liquidityDecisions(day).forEach { d ->
                    decisions += DiagDay.Decision(d.at, labels[d.book] ?: d.book, r(d.verdict), d.bar?.toLocalTime())
                }
            }
            runCatching { OrbArms.heroDecisions(day).forEach { d -> decisions += DiagDay.Decision(d.at, HeroRules.ARM.label, r(d.verdict), d.minute) } }
        }

        val orders = if (GOLD) emptyList() else runCatching {
            val owners = runCatching { Strategies.owners() }.getOrDefault(emptyMap())
            OrderReviewHost.ordersIn(owners, ms(day.atStartOfDay()), ms(day.plusDays(1).atStartOfDay()) - 1).map { o ->
                DiagDay.Order(java.time.Instant.ofEpochMilli(o.atMs).atZone(com.optionslab.engine.IST).toLocalDateTime(),
                    o.venue == com.optionslab.ira.OrderReview.Venue.LIVE, r(o.strategy), o.symbol, o.side, o.qty, o.filled, o.status, o.type, o.avg, r(o.message))
            }
        }.getOrDefault(emptyList())

        val changes = if (GOLD) emptyList() else runCatching { com.optionslab.app.ira.ChangeTimelineHost.items(day) }.getOrDefault(emptyList())

        val health = linkedMapOf<String, String>()
        fun line(k: String, f: () -> String) { runCatching { health[k] = r(f()) } }
        // 20 Oct: the app's health and the stream are read for [day] itself (a catch-up the next morning used to give the new
        // day's "drops today 0" and "stream reconnects 0" beside the day's 15 drops); the others are as at generated_at.
        line("app_health") { AppHealth.diagLine(day) }
        line("speed") { Speed.line() }
        line("stream") { KiteStream.statusLine(diary, day) }
        line("github") { GitHubSync.diagLine() }
        if (!GOLD) {
            line("order_speed") { OrderTiming.diagLine() }
            // Slippage is a premium difference a unit (points of premium), said with "₹" on the phone: written as points here so
            // the final scrub's rupee rule leaves it, and no rupee figure is in the file.
            line("slippage") { FillSlippage.diagLine().replace(" ₹ (", " pts (") }
            line("order_review") { OrderReviewHost.diagLine() }
            line("order_flow") { FlowGate.diagLine() }
            line("zerodha_sync") { ZerodhaSync.line() }
        }
        val workers = if (GOLD) emptyList() else runCatching {
            com.optionslab.app.work.WatchWorkers.diagLines() + com.optionslab.app.work.WatchWorkers.slowestLine(8)
        }.getOrDefault(emptyList()).map(::r)
        val others = if (GOLD) emptyList() else listOf(
            { NightArm.diagLine() }, { VixDivArm.diagLine() }, { HeroTicketArm.diagLine() }, { PassedArmsHost.diagLine(hideNet = true) }, { WatchlistHost.diagLine() }, { TrendWatchHost.diagLine() }, { McxPaperArms.diagLine() }, { GrammarArm.diagLine() },
            // 20 Oct: the passed arms' line with every rupee net written "(net hidden)" (its counts kept).
            // 14 Oct (Boss approved): the morning gamma line (signs and strikes) and the OI-wall shadow log (points only).
            { GammaMorningHost.diagLine(day) }, { OiWallShadowHost.diagLine(day) },
        ).mapNotNull { f -> runCatching { r(f()) }.getOrNull() }

        // The holder's name whole and word by word, the Zerodha user id, the GitHub login and the repository owner (a personal
        // handle). 14 Oct review: every name word of 2 letters or more ("Om", "Raj"), not only the long ones; DiagDay.scrub
        // takes a short word out only as a whole word.
        val name = runCatching { Broker.userName }.getOrNull()
        val personal = listOf(name, runCatching { Broker.userId }.getOrNull(), runCatching { GitHubSync.login }.getOrNull(),
            com.optionslab.ira.CloudSync.OWNER) + nameWords(name)
        return DiagDay.Input(
            day = day, generatedAt = Market.now().toLocalDateTime().withNano(0),
            app = "${com.optionslab.app.BuildConfig.VERSION_NAME} (build ${com.optionslab.app.BuildConfig.COMMIT})",
            mode = mode, arms = arms, otherArms = others, decisions = decisions,
            decisionsSince = if (GOLD) null else runCatching { OrbArms.liquidityRecordSince }.getOrNull(),
            orders = orders, changes = changes, diary = diary, errorTally = runCatching { Diag.errors.of(day) }.getOrNull(),
            health = health, workers = workers, personal = personal,
        )
    }
}
