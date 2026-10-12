package com.optionslab.app.data

import android.content.Context
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.Findings
import com.optionslab.ira.OrderReview
import com.optionslab.ira.TradeManager
import com.optionslab.ira.VariantLab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The order review on the phone (Boss, 12 Oct: "an agent same as the others, who will study each and every order placed,
 * analyse it every day, find the flaws, and resolve them, enhance and make it more profitable"). The logic is ira-core's
 * [OrderReview]; this reads what the other agents already keep and acts only through their own doors:
 *
 *  - Once a trading day after F&O's close (15:45, in the nightly pass before Jarvis's learning, inside the battery / heat
 *    budget, never the main thread) it reads - from memory and the app's own files, never the network - the day's live
 *    orders as Zerodha's order book was last read by the app's regular passes, the paper book's orders, every arm's trade
 *    records (the trade manager's, with the original rules' exit beside each), the arms' best and worst prices, the round
 *    trips with their real charges, Boss's own hand trades and reasons, the slippage per fill, the sync check's mismatches
 *    and the order path's speed warnings, and reviews them. MCX's evening trades that close after it are reviewed the next
 *    morning (07:00-09:00) into the same day.
 *  - Recurring flaws only ([OrderReview.patterns]: 3 in 10 trading days, on 2 days or more): a bug-like one is a health
 *    finding, a diagnostics line and an activity note ("needs a fix") - nothing is changed; a parameter-like one is asked of
 *    the strategy lab as ONE shadow variant ([VariantRunner.ask]; its pre-check, minimum days, sequential test and
 *    Benjamini-Hochberg decide; paper first, live only with Boss's PIN), or kept as a proposal when the lab cannot test it;
 *    each pattern's days go to Jarvis's lessons ([lessonInputs]). Promoted changes are measured before and after on the
 *    strategy's own nets ([OrderReview.beforeAfter]).
 *  - One small file (noBackupFilesDir/orderreview/review.txt: 60 days of counts, the latest day in full, the lab requests),
 *    and memory. One evening notification only when something serious happened (no rupees; deduped per day).
 *
 * It NEVER places, modifies or cancels an order, and touches nothing on the order or tick path. Not in IraGoldAlgo.
 */
object OrderReviewHost {
    private const val DIR = "orderreview"
    private const val FILE = "review.txt"
    /** F&O closes 15:40: the day's review from 15:45. */
    const val FROM = 15 * 60 + 45
    /** The next morning's catch-up for MCX's evening trades (MCX closes 23:30 or 23:55). */
    const val MORNING_FROM = 7 * 60
    const val MORNING_UNTIL = 9 * 60
    /** The notification's id (the IRA channel, private on the lock screen). */
    const val NOTICE_ID = 5184
    /** A lab request for one pattern is not asked again within this many days of its last answer. */
    const val REASK_DAYS = 30L
    private const val RETRY_MS = 10 * 60_000L
    private val IST: ZoneId = com.optionslab.engine.IST

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var loaded = false
    @Volatile private var retryAtMs = 0L

    private val _book = MutableStateFlow(OrderReview.Book())
    /** What the review keeps (the Automation card's section reads it). */
    val book: StateFlow<OrderReview.Book> = _book

    /** TEST ONLY: the day's records instead of the stores ((day, from, to) -> the inputs). */
    @Volatile internal var testInputs: ((LocalDate, Long, Long) -> OrderReview.Inputs)? = null

    fun enabled(): Boolean = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    fun init(context: Context) {
        appContext = context.applicationContext
        if (enabled()) scope.launch { runCatching { ensureLoaded() } }
    }

    private fun file(): File? = appContext?.let { File(File(it.noBackupFilesDir, DIR), FILE) }

    /** The kept file read once (a worker thread; small). A file there that cannot be read is never taken as empty. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val f = file() ?: return
            val read = runCatching { if (f.isFile) f.readText() else null }
            if (read.isFailure) { runCatching { Diag.record("orders", "the order review's file could not be read: not loaded, nothing saved over it") }; return }
            if (_book.value == OrderReview.Book()) _book.value = OrderReview.decode(read.getOrNull())
            loaded = true
        }
    }

    private fun save(b: OrderReview.Book) {
        if (!loaded) return
        val f = file() ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            java.io.FileOutputStream(tmp).use { out -> out.write(OrderReview.encode(b).toByteArray(Charsets.UTF_8)); out.flush(); out.fd.sync() }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    // ---- when ----------------------------------------------------------------------------------------------------------

    private fun prevTradingDay(d: LocalDate): LocalDate {
        var p = d.minusDays(1); var g = 0
        while (!Market.isTradingDay(p) && g++ < 15) p = p.minusDays(1)
        return p
    }

    private fun ms(t: LocalDateTime): Long = t.atZone(IST).toInstant().toEpochMilli()

    /** The day to review now and whether it is the morning catch-up; null: not due. Memory only. */
    internal fun due(now: java.time.ZonedDateTime, b: OrderReview.Book): Pair<LocalDate, Boolean>? {
        val today = now.toLocalDate()
        val minute = now.hour * 60 + now.minute
        if (Market.isTradingDay(today) && minute >= FROM && b.lastRun != today) return today to false
        if (minute in MORNING_FROM until MORNING_UNTIL) {
            val prev = prevTradingDay(today)
            // Reviewed in the evening, before MCX's close: the late trades since are read into that day.
            val mcxClose = ms(prev.atTime(23, 56))
            if (b.lastRun == prev && b.cutoffMs in 1 until mcxClose) return prev to true
        }
        return null
    }

    /** Once a trading day after the close (and the next morning for MCX): the review. Cheap when not due. True when it ran. */
    suspend fun runIfDue(context: Context? = null, now: java.time.ZonedDateTime = Market.now()): Boolean {
        if (!enabled()) return false
        if (context != null && appContext == null) appContext = context.applicationContext
        if (!running.compareAndSet(false, true)) return false
        var asked = false
        try {
            withContext(Dispatchers.IO) { ensureLoaded() }
            if (!loaded) return false
            // Memory checks first: the battery / heat budget is asked only when a review is due.
            val (day, morning) = due(now, _book.value) ?: return false
            val nowMs = now.toInstant().toEpochMilli()
            if (nowMs < retryAtMs) return false
            asked = true
            if (!com.optionslab.app.work.BackgroundBudget.allow(com.optionslab.ira.BudgetRule.Job.ORDER_REVIEW)) return false
            if (run(day, morning, nowMs) == null) { retryAtMs = nowMs + RETRY_MS; return false }
            return true
        } finally {
            if (asked) runCatching { com.optionslab.app.work.BackgroundBudget.done(com.optionslab.ira.BudgetRule.Job.ORDER_REVIEW) }
            running.set(false)
        }
    }

    // ---- the pass -------------------------------------------------------------------------------------------------------

    /** The review of [day] (and the improvements, lessons, notices): the window since the last review's cutoff. */
    internal suspend fun run(day: LocalDate, morning: Boolean, nowMs: Long): OrderReview.Day? {
        val b0 = _book.value
        val dayStart = ms(day.atStartOfDay())
        val from = if (b0.cutoffMs > 0) maxOf(b0.cutoffMs, dayStart - 4 * 86_400_000L) else dayStart
        val inputs = withContext(Dispatchers.IO) { runCatching { testInputs?.invoke(day, from, nowMs) ?: gather(day, from, nowMs) }.getOrNull() } ?: return null
        val reviewed = withContext(Dispatchers.Default) { OrderReview.review(inputs) }
        val before = b0.last
        val dayResult = if (morning && before != null && before.day == day) OrderReview.merge(before, reviewed) else reviewed
        val logs = OrderReview.keep(b0.logs, OrderReview.log(dayResult))
        val patterns = OrderReview.patterns(logs)
        val improvements = patterns.map { OrderReview.improvement(it) }
        var b = b0.copy(logs = logs, last = dayResult, lastRun = day, cutoffMs = nowMs)
        b = withContext(Dispatchers.IO) { labRequests(b, improvements, day) }
        b = b.copy(proposals = (b.proposals + improvements.filter { it.route == OrderReview.Route.PROPOSAL }.map { it.words })
            .distinctBy { it.substringBefore(" - ") }.takeLast(OrderReview.MAX_PROPOSALS))
        b = tell(b, dayResult, improvements, day, morning)
        val kept = b
        withContext(Dispatchers.IO) { synchronized(lock) { _book.value = kept; save(kept) } }
        runCatching { Diag.record("orders", "reviewed $day${if (morning) " (MCX catch-up)" else ""}: ${reviewed.orders} orders, ${reviewed.trades} trades, " +
            "${reviewed.flaws.size} flaws, ${patterns.size} recurring") }
        return dayResult
    }

    /** The parameter-like patterns asked of the strategy lab (once per pattern per [REASK_DAYS]); every queued one handed over again. */
    private fun labRequests(b: OrderReview.Book, imps: List<OrderReview.Improvement>, day: LocalDate): OrderReview.Book {
        if (!VariantRunner.enabled()) return b
        var reqs = b.requests
        for (imp in imps) {
            val ask = imp.ask ?: continue
            val prev = reqs.lastOrNull { it.key == imp.pattern.key }
            if (prev != null && (prev.status == OrderReview.QUEUED || !day.isAfter(prev.day.plusDays(REASK_DAYS)))) continue
            reqs = reqs + OrderReview.Request(imp.pattern.key, day, ask)
            runCatching { com.optionslab.app.ira.IraActivity.add("Order review: asked the strategy lab to test ${imp.pattern.strategy}'s " +
                "${VariantLab.strategy(ask.labKey)?.param(ask.param)?.words ?: ask.param} ${if (ask.dir > 0) "higher" else "lower"} as a shadow variant " +
                "(${imp.pattern.category.words}, ${imp.pattern.count} times in ${imp.pattern.days} days). Nothing changes unless the lab's tests confirm it.") }
        }
        reqs = refresh(reqs)
        for (r in reqs) if (r.status == OrderReview.QUEUED) runCatching { VariantRunner.ask(seedOf(r)) }
        return b.copy(requests = reqs.takeLast(OrderReview.MAX_REQUESTS))
    }

    private fun seedOf(r: OrderReview.Request) = VariantLab.Seed("review:${r.key}:${r.day}", r.ask.labKey, r.ask.param, r.ask.dir, r.ask.why)

    /** Each request moved on from the lab's answer and its book: the variant's status, a promotion's change. Memory only. */
    private fun refresh(reqs: List<OrderReview.Request>): List<OrderReview.Request> {
        val lab = runCatching { VariantRunner.variants.value }.getOrNull() ?: return reqs
        return reqs.map { r ->
            var x = r
            if (x.status == OrderReview.QUEUED) VariantRunner.seededResult(seedOf(x).id)?.let { s ->
                if (s.final) x = x.copy(status = s.status.take(120), variant = s.variant, note = if (s.variant == null) "a proposal only" else "")
            }
            val v = x.variant?.let { id -> lab.variants.firstOrNull { it.id == id } }
            if (v != null) {
                val ch = lab.changes.lastOrNull { it.variant == v.id }
                x = x.copy(status = v.status.words, note = ch?.let { "${it.kind.words}, ${it.status.words}" + (it.effective?.let { e -> " from ${AgentLessons.day(e)}" } ?: "") } ?: x.note)
            }
            x
        }
    }

    /** The bug-like patterns flagged (finding, diagnostics, activity: once per pattern a day), and the evening notice. */
    private fun tell(b: OrderReview.Book, d: OrderReview.Day, imps: List<OrderReview.Improvement>, day: LocalDate, morning: Boolean): OrderReview.Book {
        val told = LinkedHashSet(b.told)
        val fixes = imps.filter { it.route == OrderReview.Route.NEEDS_FIX }
        for (f in fixes) {
            val k = "fix:${f.pattern.key}:$day"
            if (!told.add(k)) continue
            // A fact for the developer and Boss: what recurs, with its numbers. No safe automatic heal exists for these in the app, so
            // none is invented: nothing about any order is changed.
            runCatching { Diag.record("orders", "needs a fix: ${f.pattern.words()}") }
            runCatching { com.optionslab.app.ira.IraActivity.add("Order review - needs a fix: ${f.pattern.strategy}'s ${f.pattern.category.words} " +
                "(${f.pattern.count} times on ${f.pattern.days} days). Flagged; nothing was changed.") }
            runCatching { SmartWorkers.found("Order review", Findings.Kind.OTHER, Findings.ALL, strength = 60,
                words = "needs a fix: ${f.pattern.strategy} ${f.pattern.category.words} (${f.pattern.count}x in ${f.pattern.days} days)", ttlMs = 24 * 3_600_000L) }
        }
        val testing = b.requests.count { it.variant != null && it.status == VariantLab.Status.ALIVE.words }
        val notice = OrderReview.notice(d, fixes.size, testing)
        val ctx = appContext
        if (!morning && notice != null && told.add("notice:$day") && ctx != null)
            runCatching { com.optionslab.app.work.Notifier.post(ctx, NOTICE_ID, com.optionslab.app.work.Notifier.IRA, "Order review", notice, "ira") }
        return b.copy(told = told.toList().takeLast(100).toSet())
    }

    // ---- what the stores already keep ----------------------------------------------------------------------------------

    private fun kiteMs(s: String): Long? = runCatching { ms(LocalDateTime.parse(s.trim().replace(' ', 'T').take(19))) }.getOrNull()

    private fun nameOf(label: String?): String = com.optionslab.ira.Charges.owner(label)

    /** Every order and trade in [from]..[to] for [day], from memory and the app's own files (no network). A worker thread. */
    private suspend fun gather(day: LocalDate, from: Long, to: Long): OrderReview.Inputs {
        val owners = runCatching { Strategies.owners() }.getOrDefault(emptyMap())
        val orders = ArrayList<OrderReview.Order>()
        // Zerodha's order book as the app's regular passes last read it (never read for this).
        runCatching { AppState.orders.value?.value }.getOrNull()?.forEach { o ->
            val at = kiteMs(o.placedAt) ?: return@forEach
            if (at !in from..to) return@forEach
            val label = owners["kite:${o.id}"]
            val strategy = if (label != null) nameOf(label) else com.optionslab.ira.Slippage.strategyOf(o.tag, null, null)
            orders += OrderReview.Order("kite:${o.id}", OrderReview.Venue.LIVE, strategy, o.symbol, o.side, o.qty, o.filled, o.status, o.message, at, o.avg, o.type,
                manual = strategy == "Manual")
        }
        runCatching { Paper.state.orders }.getOrNull()?.forEach { o ->
            val at = ms(o.orderTimestamp)
            if (at !in from..to) return@forEach
            val strategy = nameOf(owners["paper:${o.orderId}"] ?: o.strategy)
            orders += OrderReview.Order("paper:${o.orderId}", OrderReview.Venue.PAPER, strategy, o.symbol, o.action, o.quantity, o.filledQuantity, o.status,
                o.rejectionReason.orEmpty(), at, o.averagePrice?.toDouble() ?: 0.0, o.priceType, manual = strategy == "Manual")
        }
        // The arms' trades as the trade manager records each (every arm is hooked), with the original rules' exit.
        runCatching { TradeManagerHost.ensureLoaded() }
        val records = runCatching { TradeManagerHost.records.value }.getOrDefault(emptyList())
        val arms = runCatching { OrbArms.closedPaper() + OrbArms.closedLive() }.getOrDefault(emptyList())
        // Each book on its own: a Zerodha book that cannot be read never hides the paper one.
        val trips = runCatching { TradeBook.trips(false).map { false to it } }.getOrDefault(emptyList()) +
            runCatching { TradeBook.trips(true).map { true to it } }.getOrDefault(emptyList())
        val bossTrades = runCatching { com.optionslab.app.ira.BossTradeLog.all() }.getOrDefault(emptyList())
        val usedTrips = HashSet<Any>()
        fun tripFor(live: Boolean, symbol: String, entryMs: Long) = trips.firstOrNull { (l, t) ->
            l == live && t !in usedTrips && t.symbol.equals(symbol, true) && kotlin.math.abs(ms(t.openedAt) - entryMs) <= 3 * 60_000L }?.second
        val trades = ArrayList<OrderReview.Trade>()
        val bars = Bars(day)
        for (r in records) {
            val t = r.trade
            val exitMs = r.actual?.atMs
            val inWindow = if (exitMs != null) exitMs in from..to else AgentLessons.dayOf(t.entryMs) == day
            if (!inWindow) continue
            val live = t.account == TradeManager.Account.LIVE
            val stop = t.originalStop
            val target = t.originalTarget
            val long = when { stop != null -> stop < t.entry; target != null -> target > t.entry; else -> true }
            val trip = tripFor(live, t.instrument, t.entryMs)?.also { usedTrips += it }
            val arm = arms.firstOrNull { p -> p.live == live && (p.kite ?: p.symbol).equals(t.instrument, true) && kotlin.math.abs(ms(p.entryTime) - t.entryMs) <= 3 * 60_000L }
            val sq = t.caps.squareOffMs.takeIf { it > 0 && AgentLessons.dayOf(it) == AgentLessons.dayOf(t.entryMs) }?.let { AgentLessons.minuteOf(it) }
            val exit = r.actual?.price
            val d = if (long) 1 else -1
            val est = exit?.let { d * (it - t.entry) * t.qty - t.chargesPerUnit * t.qty }
            val (path, after) = runCatching { bars.of(t.instrument, t.entryMs, exitMs) }.getOrDefault(emptyList<OrderReview.Bar>() to emptyList())
            trades += OrderReview.Trade(
                "${t.strategyId}:${t.tradeId}", if (live) OrderReview.Venue.LIVE else OrderReview.Venue.PAPER, nameOf(t.label), t.instrument, t.qty,
                t.entry, t.entryMs, exit, exitMs, long, stop, target, arm?.peak, arm?.low, r.actual?.why, r.original?.price, r.original?.why,
                managerActed = r.mode == TradeManager.Mode.ACT && r.actedAtMs != null, window = windowOf(t.label, day, live), squareOff = sq,
                net = trip?.net ?: est, charges = trip?.charges ?: t.chargesPerUnit * t.qty, path = path, after = after,
                tick = t.caps.tick)
        }
        // Every other round trip (Boss's hand trades, Pine, the protections, Jarvis's paper): the real nets, no rules to judge.
        for ((live, t) in trips) {
            if (t in usedTrips) continue
            val closed = ms(t.closedAt)
            if (closed !in from..to) continue
            val owner = runCatching { TradeBook.ownerOf(t, owners) }.getOrDefault("Manual")
            val manual = owner == "Manual"
            val plan = if (!manual) null else bossTrades.firstOrNull { it.symbol.equals(t.symbol, true) && kotlin.math.abs(it.openedMs - ms(t.openedAt)) <= 3 * 60_000L }
                ?.let { bt -> listOfNotNull(bt.why?.let { "why: ${it.words}" }, bt.exitWhy?.let { "exit: ${it.words}" }).joinToString("; ").ifEmpty { null } }
            trades += OrderReview.Trade(t.fillIds.firstOrNull() ?: "${t.symbol}@${t.openedAt}", if (live) OrderReview.Venue.LIVE else OrderReview.Venue.PAPER,
                owner, t.symbol, t.qty, t.entry, ms(t.openedAt), t.exit, closed, long = t.direction > 0, net = t.net, charges = t.charges,
                exitWhy = plan?.substringAfter("exit: ", "")?.ifEmpty { null }, manual = manual, plan = plan)
        }
        val fills = runCatching { FillSlippage.fills() }.getOrDefault(emptyList())
        val sync = runCatching { ZerodhaSync.seenOn(day.toString()) }.getOrDefault(emptyList())
        val speed = if (day == Market.today()) runCatching { OrderTiming.card().warnings }.getOrDefault(emptyList()) else emptyList()
        val tracked = records.filter { it.trade.account == TradeManager.Account.LIVE }.map { nameOf(it.trade.label) }.toSet()
        return OrderReview.Inputs(day, orders, trades, fills, sync, speed, tracked, to)
    }

    /** The ORB family's entry window on [day] (after its opening range, to its last entry and two minutes for the order); else null. */
    private fun windowOf(label: String, day: LocalDate, live: Boolean): IntRange? = runCatching {
        val key = OrderReview.labKeyOf(label) ?: return@runCatching null
        val arm = com.optionslab.ira.ArmParams.ORB_ARMS[key] ?: return@runCatching null
        val p = VariantRunner.orbParams(arm, day, live)
        (p.orEnd.hour * 60 + p.orEnd.minute)..(p.lastEntry.hour * 60 + p.lastEntry.minute + 2)
    }.getOrNull()

    /**
     * The traded option's stored minutes on the review's day (the harvest's, when it has kept the day): while held ([path]) and
     * after the exit ([after]). One session read per index; empty when not stored (no claim is then made).
     */
    private class Bars(val day: LocalDate) {
        private val sessions = HashMap<String, com.optionslab.engine.Session?>()
        fun of(symbol: String, entryMs: Long, exitMs: Long?): Pair<List<OrderReview.Bar>, List<OrderReview.Bar>> {
            val c = Paper.contractIfLoaded(symbol) ?: return emptyList<OrderReview.Bar>() to emptyList()
            if (c.isMcx) return emptyList<OrderReview.Bar>() to emptyList()
            val s = sessions.getOrPut(c.underlying) { runCatching { Store.barSession(c.underlying, day) }.getOrNull() } ?: return emptyList<OrderReview.Bar>() to emptyList()
            val right = if (c.right == com.optionslab.engine.Right.PE) com.optionslab.engine.Right.PE else com.optionslab.engine.Right.CE
            val sr = s.options.firstOrNull { it.right == right && kotlin.math.abs(it.strike - c.strike) < 1e-6 && (it.expiry == null || it.expiry == c.expiry) }
                ?: return emptyList<OrderReview.Bar>() to emptyList()
            val base = day.atStartOfDay(com.optionslab.engine.IST).toInstant().toEpochMilli()
            val inMin = AgentLessons.minuteOf(entryMs); val outMin = exitMs?.let { AgentLessons.minuteOf(it) } ?: Int.MAX_VALUE
            val path = ArrayList<OrderReview.Bar>(); val after = ArrayList<OrderReview.Bar>()
            for (k in 0 until sr.size) {
                val m = sr.minutes[k]
                val bar = OrderReview.Bar(base + m * 60_000L, sr.high?.get(k) ?: sr.close[k], sr.low?.get(k) ?: sr.close[k], sr.close[k])
                if (m in inMin until outMin) path += bar else if (exitMs != null && m > outMin) after += bar
            }
            return path to after
        }
    }

    // ---- what others read -----------------------------------------------------------------------------------------------

    /** The recurring patterns now and what each becomes (memory only). */
    fun improvements(b: OrderReview.Book = _book.value): List<OrderReview.Improvement> = OrderReview.patterns(b.logs).map { OrderReview.improvement(it) }

    /** Jarvis's lessons: each recurring pattern's days ([AgentLessons.Inputs.orders]); empty when off or not read. */
    fun lessonInputs(): List<AgentLessons.OrderFlawResult> {
        if (!enabled()) return emptyList()
        runCatching { ensureLoaded() }
        val b = _book.value
        return runCatching { OrderReview.lessonInputs(b.logs, OrderReview.patterns(b.logs)) }.getOrDefault(emptyList())
    }

    /** Requests the lab is testing now (a variant shadowing). */
    fun testing(b: OrderReview.Book = _book.value): Int = b.requests.count { it.variant != null && it.status == VariantLab.Status.ALIVE.words }

    /** The Automation card's line (memory only). */
    fun cardLine(b: OrderReview.Book, today: LocalDate): String = "Order review · " + OrderReview.cardLine(b.last, testing(b), today)

    /** The section's content, worked out from the books in memory. */
    data class View(val line: String, val warnings: List<String>, val flaws: List<String>, val summaries: List<String>, val lab: List<String>,
                    val proposals: List<String>, val fixes: List<String>, val notes: List<String>)

    fun view(b: OrderReview.Book, lab: VariantLab.Book, today: LocalDate): View {
        val d = b.last
        val imps = improvements(b)
        val warnings = (d?.flaws.orEmpty().filter { it.severity == OrderReview.Severity.HIGH }.map { OrderReview.flawLine(it) } +
            imps.filter { it.route == OrderReview.Route.NEEDS_FIX }.map { "Needs a fix: " + it.pattern.words() }).take(6)
        val labLines = b.requests.reversed().take(8).map { r ->
            val strategy = r.key.substringBefore('|')
            val v = r.variant?.let { id -> lab.variants.firstOrNull { it.id == id } }
            val what = v?.let { "${it.id} · ${it.moved()} · ${VariantLab.state(it)}" } ?: "${VariantLab.strategy(r.ask.labKey)?.param(r.ask.param)?.words ?: r.ask.param} ${if (r.ask.dir > 0) "higher" else "lower"}"
            val change = v?.let { vv -> lab.changes.lastOrNull { it.variant == vv.id && (it.status == VariantLab.ChangeStatus.APPLIED || it.status == VariantLab.ChangeStatus.ACCEPTED) } }
            val measured = change?.effective?.let { e ->
                val venue = if (change.kind == VariantLab.ChangeKind.LIVE_PROPOSAL) OrderReview.Venue.LIVE else OrderReview.Venue.PAPER
                " · measured: " + OrderReview.beforeAfter(b.logs, strategy, venue, e).words()
            } ?: ""
            "$strategy: $what · ${r.status}" + (if (r.note.isNotEmpty()) " (${r.note})" else "") + measured
        }
        return View(OrderReview.cardLine(d, testing(b), today), warnings,
            d?.flaws.orEmpty().sortedByDescending { it.severity.ordinal }.take(OrderReview.MAX_FLAWS).map { OrderReview.flawLine(it) },
            d?.summaries.orEmpty().map { OrderReview.summaryLine(it) }, labLines, b.proposals.reversed().take(6), imps.filter { it.route == OrderReview.Route.NEEDS_FIX }.map { it.words },
            d?.notes.orEmpty())
    }

    /** Jarvis's answer (an unlocked phone; the hub refuses on a locked one). A worker thread. */
    fun answer(ask: OrderReview.Ask): String {
        runCatching { ensureLoaded() }
        val b = _book.value
        return OrderReview.answer(b.last, ask, improvements(b), Market.today())
    }

    /** The diagnostics' line: when it last ran, counts only (no rupees). */
    fun diagLine(): String {
        val b = _book.value
        val d = b.last ?: return "Order review: not run yet"
        return "Order review: last ${d.day} (run ${b.lastRun}), ${d.orders} orders, ${d.trades} trades, ${d.flaws.size} flaws " +
            "(${d.flaws.count { it.severity == OrderReview.Severity.HIGH }} serious), ${OrderReview.patterns(b.logs).size} recurring, " +
            "${b.requests.size} lab requests (${testing(b)} testing), ${b.logs.size} days kept"
    }

    /** TEST ONLY: nothing kept, the file read (as empty). */
    internal fun resetForTest() {
        synchronized(lock) { _book.value = OrderReview.Book() }
        loaded = true; retryAtMs = 0L; running.set(false); testInputs = null
    }

    /** TEST ONLY: nothing in memory and the file not read yet (a new process). */
    internal fun reloadForTest() { synchronized(lock) { _book.value = OrderReview.Book() }; loaded = false }
}
