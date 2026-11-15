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
import kotlinx.coroutines.delay
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
 *  - One small file (noBackupFilesDir/orderreview/review.vault, sealed with the vault's key since 15 Nov - it was plain text
 *    in review.txt before: 60 days of counts, the latest day in full, the lab requests), and memory. One evening
 *    notification only when something serious happened (no rupees; deduped per day).
 *  - 15 Nov (Boss's diagnostics: the day's live orders were gone after a restart): each day's live orders as last read are also
 *    kept in a small sealed file a day (orderreview/orders-<day>.vault, the vault's encryption; [snapshotOrders]), written by
 *    this host's own background watcher, a few days kept. A day missed (the app not running that evening) is reviewed at the
 *    next run ([due]), and a review that did not complete says so in the diary ([failed]).
 *
 * It NEVER places, modifies or cancels an order, and touches nothing on the order or tick path. Not in IraGoldAlgo.
 */
object OrderReviewHost {
    private const val DIR = "orderreview"
    /** 15 Nov: the kept review, sealed with the vault's key (its flaws carry order ids, symbols and rupees: account data). */
    private const val FILE = "review.vault"
    /** Before 15 Nov the review was kept here in plain text: read once at the first load, written sealed, then deleted. */
    private const val PLAIN_FILE = "review.txt"
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
    /**
     * 15 Nov: a missed day is caught up only when it is at most this many calendar days back (a Friday missed, read on the
     * Tuesday after a Monday holiday); an older one is left (its orders are no longer kept).
     */
    const val CATCH_UP_DAYS = 4L
    /** 15 Nov (review): on a trading day the missed day's catch-up runs before 09:00 (or from [FROM]), never in market hours. */
    const val CATCH_UP_BEFORE = 9 * 60
    /**
     * 15 Nov: the days of live-order snapshots kept: the catch-up's [CATCH_UP_DAYS], the review window's four days before it,
     * and one to spare. Older files are deleted ([pruneIfDue]).
     */
    const val SNAP_KEEP_DAYS = 9L
    private const val SNAP_PREFIX = "orders-"
    private const val SNAP_EXT = ".vault"
    /** After a snapshot was written, the next is not before this (the order book is read every few seconds while trading). */
    private const val SNAP_GAP_MS = 30_000L
    private val IST: ZoneId = com.optionslab.engine.IST

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var loaded = false
    @Volatile private var retryAtMs = 0L
    /** 26 Oct (Erase everything): bumped by [wipe]; a review that began before an erase never keeps or saves its book after it. */
    @Volatile private var wipeGen = 0

    private val _book = MutableStateFlow(OrderReview.Book())
    /** What the review keeps (the Automation card's section reads it). */
    val book: StateFlow<OrderReview.Book> = _book

    /** TEST ONLY: the day's records instead of the stores ((day, from, to) -> the inputs). */
    @Volatile internal var testInputs: ((LocalDate, Long, Long) -> OrderReview.Inputs)? = null

    fun enabled(): Boolean = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    fun init(context: Context) {
        appContext = context.applicationContext
        if (enabled()) scope.launch { com.optionslab.app.data.PositionsFirst.awaitPositions(); runCatching { ensureLoaded() } }
        // 15 Nov: the day's live orders kept as they are read (the diagnostics read them too, so not only in the Jarvis builds).
        if (!com.optionslab.app.BuildConfig.GOLD) watchOrders()
    }

    private fun dir(): File? = appContext?.let { File(it.noBackupFilesDir, DIR) }

    private fun file(): File? = dir()?.let { File(it, FILE) }

    private fun plainFile(): File? = dir()?.let { File(it, PLAIN_FILE) }

    /**
     * The kept file read once (a worker thread; small: a Keystore decryption). A file there that cannot be read just now (the
     * Keystore busy, an erase running) is never taken as empty: not loaded, nothing saved over it, read again next time.
     * 15 Nov: sealed ([FILE]). A sealed file that can never be opened (damaged, or its key gone) is an empty book, never a
     * crash: it is deleted and said in the diary. No sealed file but the plain one kept before 15 Nov ([PLAIN_FILE]): read
     * once, written sealed, and the plain file deleted (kept until the sealed write is done, so nothing is lost).
     */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val f = file() ?: return
            val plain = plainFile()
            var migrate = false
            val text: String? = try {
                if (f.isFile) com.optionslab.app.security.Vault.readFileSteady(f)?.toString(Charsets.UTF_8)
                else if (plain != null && plain.isFile) plain.readText().also { migrate = true }
                else null
            } catch (_: com.optionslab.app.security.Vault.Tampered) {
                runCatching { f.delete() }
                runCatching { Diag.record("orders", "the order review's file could not be opened (damaged): started again empty") }
                loaded = true
                return
            } catch (_: Throwable) {
                runCatching { Diag.record("orders", "the order review's file could not be read: not loaded, nothing saved over it") }
                return
            }
            if (_book.value == OrderReview.Book()) _book.value = OrderReview.decode(text)
            loaded = true
            // Written sealed now (the plain file is deleted by the save once the sealed one is written).
            if (migrate) save(_book.value)
        }
    }

    /**
     * The book written to its sealed file (a worker thread, under [lock]: a Keystore encryption and a disk sync). 15 Nov: through
     * the vault, as the app's other private files; once written, the plain file kept before 15 Nov is deleted. A write an erase
     * drops ([com.optionslab.app.security.Vault.writeFileWritten] false) deletes nothing.
     */
    private fun save(b: OrderReview.Book) {
        if (!loaded) return
        val f = file() ?: return
        runCatching {
            if (com.optionslab.app.security.Vault.writeFileWritten(f, OrderReview.encode(b).toByteArray(Charsets.UTF_8)))
                plainFile()?.takeIf { it.exists() }?.let { p -> p.delete(); File(p.parentFile, p.name + ".tmp").delete() }
        }
    }

    // ---- when ----------------------------------------------------------------------------------------------------------

    private fun prevTradingDay(d: LocalDate): LocalDate {
        var p = d.minusDays(1); var g = 0
        while (!Market.isTradingDay(p) && g++ < 15) p = p.minusDays(1)
        return p
    }

    private fun ms(t: LocalDateTime): Long = t.atZone(IST).toInstant().toEpochMilli()

    /**
     * The day to review now and whether it is the morning catch-up; null: not due. Memory only. [skipMissed]: a missed day's
     * review failed in this call, so today's own is asked for (15 Nov).
     */
    internal fun due(now: java.time.ZonedDateTime, b: OrderReview.Book, skipMissed: Boolean = false): Pair<LocalDate, Boolean>? {
        val today = now.toLocalDate()
        val minute = now.hour * 60 + now.minute
        // 15 Nov (review): a missed day is caught up only outside NSE's hours (before 09:00 or from 15:45; any hour on a day with
        // no session), so no new work runs while the market trades.
        val quiet = !Market.isTradingDay(today) || minute < CATCH_UP_BEFORE || minute >= FROM
        if (!skipMissed && quiet) missedDay(today, b)?.let { return it to false }
        if (Market.isTradingDay(today) && minute >= FROM && b.lastRun != today) return today to false
        if (minute in MORNING_FROM until MORNING_UNTIL) {
            val prev = prevTradingDay(today)
            // Reviewed in the evening, before MCX's close: the late trades since are read into that day.
            val mcxClose = ms(prev.atTime(23, 56))
            if (b.lastRun == prev && b.cutoffMs in 1 until mcxClose) return prev to true
        }
        return null
    }

    /**
     * 15 Nov (Boss's diagnostics: no review after 5 Nov): a day missed, because the app was not running that evening, was never
     * reviewed - the next evening reviewed only its own day. Now the most recent trading day before [today] is reviewed at the
     * next run outside market hours ([due]), however many evenings were missed, and before today's own review (so today's
     * never takes its place; one that fails never holds today's up, [runIfDue]): only that one day (its window still reaches back up to four days, see [run]), and only when it is at
     * most [CATCH_UP_DAYS] back. A review that never ran has missed nothing: its first is the evening's.
     */
    private fun missedDay(today: LocalDate, b: OrderReview.Book): LocalDate? {
        val last = b.lastRun ?: return null
        val missed = prevTradingDay(today)
        return missed.takeIf { last.isBefore(it) && !it.isBefore(today.minusDays(CATCH_UP_DAYS)) }
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
            if (due(now, _book.value) == null) return false
            val nowMs = now.toInstant().toEpochMilli()
            if (nowMs < retryAtMs) return false
            // 22 Oct: at a start in the background the shared knowledge's first build waits for the app's use; the review cites
            // it and its variants are checked against it ([VariantRunner.ask]): asked for here, before the budget's meter starts.
            SharedKnowledgeHost.awaitFirstBuild("the order review")
            asked = true
            if (!com.optionslab.app.work.BackgroundBudget.allow(com.optionslab.ira.BudgetRule.Job.ORDER_REVIEW)) return false
            // 15 Nov: a missed day first, then (the same call: the evening job runs once) today's own review - two at most.
            // Review: an Erase everything during the first stops the second (it would review the orders still in memory and
            // save them after the erase); a missed day whose review fails never holds up today's own.
            val gen = wipeGen
            var ran = false
            var skipMissed = false
            var pass = 0
            while (pass < 2) {
                pass++
                if (gen != wipeGen) break
                val (day, morning) = due(now, _book.value, skipMissed) ?: break
                val catchUp = !skipMissed && !morning && day == missedDay(now.toLocalDate(), _book.value)
                // 15 Nov: a review that throws is said in the diary (it used to fail without a word), and is tried again later.
                val done = try { run(day, morning, nowMs) } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (t: Throwable) { failed(day, "the review itself failed", t); null }
                if (done == null) {
                    retryAtMs = nowMs + RETRY_MS
                    if (catchUp) { skipMissed = true; continue }
                    break
                }
                ran = true
            }
            return ran
        } finally {
            if (asked) runCatching { com.optionslab.app.work.BackgroundBudget.done(com.optionslab.ira.BudgetRule.Job.ORDER_REVIEW) }
            running.set(false)
        }
    }

    /** The last "did not complete" line said (memory only): the retries every ten minutes say the same line once. */
    @Volatile private var lastFailed: String? = null

    /**
     * 15 Nov (Boss's diagnostics): the review of [day] did not complete - one diary line, so the day's diagnostics show it.
     * Only [what] (fixed words) and the failure's class name: never its message (it can carry an order's or the account's
     * details), no rupees. The same line is said once, not at every retry.
     */
    private fun failed(day: LocalDate, what: String, t: Throwable?) {
        val why = t?.javaClass?.simpleName?.takeIf { it.isNotEmpty() } ?: "no reason given"
        val line = "the review of $day did not complete: $what ($why); it is tried again later"
        if (line == lastFailed) return
        lastFailed = line
        runCatching { Diag.record("orders", line) }
    }

    // ---- the pass -------------------------------------------------------------------------------------------------------

    /** The review of [day] (and the improvements, lessons, notices): the window since the last review's cutoff. */
    internal suspend fun run(day: LocalDate, morning: Boolean, nowMs: Long): OrderReview.Day? {
        val gen = wipeGen
        val b0 = _book.value
        val dayStart = ms(day.atStartOfDay())
        val from = if (b0.cutoffMs > 0) maxOf(b0.cutoffMs, dayStart - 4 * 86_400_000L) else dayStart
        // 15 Nov: a missed day caught up on a later day ([due]) is read to its own end only, so the later day's orders are left
        // for that day's own review. The evening's review and MCX's morning catch-up read up to now, as before.
        val dayEnd = ms(day.plusDays(1).atStartOfDay()) - 1
        val late = !morning && nowMs > dayEnd
        val to = if (late) dayEnd else nowMs
        // 15 Nov: records that cannot be read are said in the diary ([failed]); it used to return without a word.
        val got = withContext(Dispatchers.IO) { runCatching { testInputs?.invoke(day, from, to) ?: gather(day, from, to) } }
        val inputs = got.getOrNull()
        if (inputs == null) { failed(day, "its records could not be read", got.exceptionOrNull()); return null }
        val reviewed = withContext(Dispatchers.Default) { cited(OrderReview.review(inputs)) }
        val before = b0.last
        val dayResult = if (morning && before != null && before.day == day) OrderReview.merge(before, reviewed) else reviewed
        val logs = OrderReview.keep(b0.logs, OrderReview.log(dayResult))
        val patterns = OrderReview.patterns(logs)
        val improvements = patterns.map { OrderReview.improvement(it) }
        var b = b0.copy(logs = logs, last = dayResult, lastRun = day, cutoffMs = to)
        b = withContext(Dispatchers.IO) { labRequests(b, improvements, day) }
        b = b.copy(proposals = (b.proposals + improvements.filter { it.route == OrderReview.Route.PROPOSAL }.map { it.words })
            .distinctBy { it.substringBefore(" - ") }.takeLast(OrderReview.MAX_PROPOSALS))
        b = tell(b, dayResult, improvements, day, morning)
        // 12 Oct: what it found goes where Boss looks - the open problems on the Automation card, and Jarvis's coach line.
        runCatching { toProblems(dayResult, improvements) }
        b = coach(b, dayResult, day)
        val kept = b
        withContext(Dispatchers.IO) { synchronized(lock) { if (gen == wipeGen) { _book.value = kept; save(kept) } } }
        lastFailed = null
        runCatching { Diag.record("orders", "reviewed $day${if (morning) " (MCX catch-up)" else if (late) " (a missed day, caught up)" else ""}: ${reviewed.orders} orders, ${reviewed.trades} trades, " +
            "${reviewed.flaws.size} flaws, ${patterns.size} recurring") }
        return dayResult
    }

    /**
     * [d]'s flaws with the research they touch (13 Oct, the shared knowledge): a charges-ate-the-day or re-entry flaw cites R14's
     * charges-versus-edge finding, a slippage flaw the real spread baseline (h24), a give-back the profit-lock study, and so on
     * ([com.optionslab.ira.SharedKnowledge.flawCite]). Words in the flaw's evidence only: the flaws found, their counts and their
     * rupees are the review's own, unchanged. Before the knowledge is built: as found.
     */
    internal fun cited(d: OrderReview.Day): OrderReview.Day {
        val s = SharedKnowledgeHost.snapshot
        if (s.size == 0 || d.flaws.isEmpty()) return d
        var n = 0
        val flaws = d.flaws.map { f ->
            if (f.evidence.contains("research:")) return@map f
            val c = runCatching { com.optionslab.ira.SharedKnowledge.flawCite(s, f.category.key, f.strategy) }.getOrNull() ?: return@map f
            n++
            f.copy(evidence = "${f.evidence} - $c")
        }
        if (n > 0) SharedKnowledgeHost.read("Order review", "the research behind $n flaw${if (n == 1) "" else "s"} (R14's charges vs edge, the real spread)")
        return d.copy(flaws = flaws)
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

    /** What Boss can do about [f] (the open problems' fix words). */
    private fun fixWords(f: OrderReview.Flaw): String? = when {
        f.category == OrderReview.Category.CHARGES_EAT || f.category == OrderReview.Category.REENTRY ->
            "cap its entries a side a day (the strategy lab's re-entry cap), or switch it off"
        f.category == OrderReview.Category.WRONG_SIDE -> "a long's target must be above the price paid: close it or place a limit exit instead"
        f.category == OrderReview.Category.ARM_PROTECT -> "leave a bot's positions to its own exits"
        f.fix == OrderReview.Fix.NEEDS_FIX -> "a developer fix (flagged; nothing was changed)"
        else -> null
    }

    /**
     * The day's flaws in sight ([OrderReview.inSight]: the serious ones and the classes no one saw on 12 Oct) and the recurring
     * patterns that need a fix or are proposals, as open problems ([ProblemsHost]); the last review's others are over.
     */
    private fun toProblems(d: OrderReview.Day, imps: List<OrderReview.Improvement>) {
        val keep = HashSet<String>()
        for (f in d.flaws.filter { OrderReview.inSight(it) }.sortedByDescending { it.severity.ordinal }.take(12)) {
            val key = "orders:${f.category.key}:${f.ref}".take(120)
            keep += key
            ProblemsHost.report(key, "Order review", if (f.severity == OrderReview.Severity.HIGH) com.optionslab.ira.OpenProblems.Severity.SERIOUS
                else com.optionslab.ira.OpenProblems.Severity.WARN, OrderReview.flawLine(f), fixWords(f))
        }
        for (i in imps.filter { it.route == OrderReview.Route.NEEDS_FIX || it.route == OrderReview.Route.PROPOSAL }) {
            val key = "orders:pattern:${i.pattern.key}".take(120)
            keep += key
            ProblemsHost.report(key, "Order review", if (i.route == OrderReview.Route.NEEDS_FIX) com.optionslab.ira.OpenProblems.Severity.SERIOUS
                else com.optionslab.ira.OpenProblems.Severity.WARN, i.words)
        }
        ProblemsHost.clearOthers("orders:", keep)
    }

    /**
     * Jarvis's coach line, once a day (12 Oct: ORB re-entered ~9 times a day for about nothing before charges, found only when Boss
     * noticed): a strategy whose charges ate the day, or that bought the same side again and again. Words only - no rupees, no
     * account figures - in the chat (never said aloud from here).
     */
    private fun coach(b: OrderReview.Book, d: OrderReview.Day, day: LocalDate): OrderReview.Book {
        val hit = d.flaws.filter { it.category == OrderReview.Category.CHARGES_EAT || it.category == OrderReview.Category.REENTRY }
        if (hit.isEmpty() || !com.optionslab.app.BuildConfig.JARVIS) return b
        val told = LinkedHashSet(b.told)
        if (!told.add("coach:$day")) return b
        val names = hit.map { "${it.strategy} (${it.venue.word})" }.distinct()
        val text = "Order review, Boss: " + names.joinToString(" and ") + " traded" +
            " many times today for about no edge before charges, so the charges ate the day. Fewer entries (a cap a side a day) or " +
            "switching it off is the fix; I changed nothing. It is on the Automation card under Problems found."
        runCatching { com.optionslab.app.ira.IraHub.note(text) }
        return b.copy(told = told.toList().takeLast(100).toSet())
    }

    // ---- what the stores already keep ----------------------------------------------------------------------------------

    private fun kiteAt(s: String): LocalDateTime? = runCatching { LocalDateTime.parse(s.trim().replace(' ', 'T').take(19)) }.getOrNull()

    private fun kiteMs(s: String): Long? = kiteAt(s)?.let { ms(it) }

    private fun nameOf(label: String?): String = com.optionslab.ira.Charges.owner(label)

    /**
     * Liquidity's go-live checklist lines from the kept day logs since [since] (memory only; null until the review is read and
     * has run once): (serious flaws, protection or guard misfires). Serious: the bug-like classes ("needs a fix"); misfires:
     * a protection's target on the wrong side, a stop replaced by nothing, a protection put on its own position. The logs keep
     * the strategy, not the index: these count Liquidity 15+5's trades on every index.
     */
    fun liquidityCounts(since: LocalDate, b: OrderReview.Book = _book.value): Pair<Int, Int>? {
        if (b.lastRun == null) return null
        val misfire = setOf(OrderReview.Category.WRONG_SIDE, OrderReview.Category.EMPTY_PROTECT, OrderReview.Category.ARM_PROTECT)
        var serious = 0; var misfires = 0
        for (log in b.logs.filter { !it.day.isBefore(since) }) for ((k, t) in log.flaws) {
            val parts = k.split('|')
            if (parts.size < 3 || com.optionslab.ira.ArmOwners.arm(parts[0]) != com.optionslab.ira.ArmOwners.LIQUIDITY) continue
            val cat = OrderReview.Category.of(parts[2]) ?: continue
            if (cat in misfire) misfires += t.n else if (cat.fix == OrderReview.Fix.NEEDS_FIX) serious += t.n
        }
        return serious to misfires
    }

    /**
     * The orders placed in [from]..[to] with who placed each ([owners]' labels): Zerodha's order book as the app's regular
     * passes last read it (never read for this) and the paper book. Also the day's diagnostics' orders (G3).
     *
     * 15 Nov (Boss's diagnostics: after a restart the day's earlier live orders were missing from the review and the day's
     * file): memory, and the live orders kept for those days ([snapshotOrders]) - one order once by its id, memory's (the
     * newest read) over the kept one. A worker thread: the kept days are read from their sealed files the first time.
     */
    internal fun ordersIn(owners: Map<String, String>, from: Long, to: Long): List<OrderReview.Order> {
        val orders = ArrayList<OrderReview.Order>()
        val live = LinkedHashMap<String, Broker.OrderRow>()
        runCatching { keptOrders(from, to) }.getOrNull()?.forEach { live[it.id] = it }
        runCatching { AppState.orders.value?.value }.getOrNull()?.forEach { live[it.id] = it }
        live.values.forEach { o ->
            val at = kiteMs(o.placedAt) ?: return@forEach
            if (at !in from..to) return@forEach
            // 15 Nov: else the arm noted before the order was sent (a rejected order whose id never reached the phone: [ArmOrderLabels]).
            val label = owners["kite:${o.id}"] ?: ArmOrderLabels.labelOf(o)
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
        return orders
    }

    // ---- the day's live orders, kept (15 Nov) --------------------------------------------------------------------------

    private val snapLock = Any()
    /** The kept live orders by day and order id, as read from or written to their files (guarded by [snapLock]). */
    private val snaps = HashMap<LocalDate, Map<String, Broker.OrderRow>>()
    /** The day the old files were last deleted on (guarded by [snapLock]). */
    private var snapPruned: LocalDate? = null
    /** Erase everything: an order book read before this moment is never kept after it (guarded by [snapLock]). */
    private var erasedAt = Long.MIN_VALUE
    private val watching = java.util.concurrent.atomic.AtomicBoolean(false)
    /** TEST ONLY: off for every other test ([resetForTest]: their orders are not what they test); the snapshot's own tests turn it on. */
    @Volatile internal var snapshotsOn = true

    private fun snapFile(day: LocalDate): File? = dir()?.let { File(it, "$SNAP_PREFIX$day$SNAP_EXT") }

    /** Every snapshot file there is (the vault's temporary files of one too). */
    private fun snapFiles(): List<File> = runCatching { dir()?.listFiles { f -> f.name.startsWith(SNAP_PREFIX) }?.toList() }.getOrNull().orEmpty()

    private fun dayOf(atMs: Long): LocalDate = java.time.Instant.ofEpochMilli(atMs).atZone(IST).toLocalDate()

    /**
     * What is kept of one order: what [ordersIn] reads, nothing more - with the exchange and product, which [ArmOrderLabels.labelOf]
     * matches a rejected order to its arm's refused try by.
     */
    private fun keptRow(o: Broker.OrderRow) = Broker.OrderRow(id = o.id, symbol = o.symbol, side = o.side, qty = o.qty, filled = o.filled, price = 0.0,
        avg = if (o.avg.isFinite()) o.avg else 0.0, status = o.status, message = o.message, placedAt = o.placedAt, exchange = o.exchange,
        product = o.product, type = o.type, tag = o.tag)

    private fun encodeRows(rows: Collection<Broker.OrderRow>): ByteArray {
        val a = org.json.JSONArray()
        for (o in rows) a.put(org.json.JSONObject().put("id", o.id).put("symbol", o.symbol).put("side", o.side).put("qty", o.qty).put("filled", o.filled)
            .put("avg", o.avg).put("status", o.status).put("message", o.message).put("placedAt", o.placedAt).put("type", o.type).put("tag", o.tag)
            .put("exchange", o.exchange).put("product", o.product))
        return a.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodeRows(plain: ByteArray): Map<String, Broker.OrderRow> {
        val a = org.json.JSONArray(String(plain, Charsets.UTF_8))
        val out = LinkedHashMap<String, Broker.OrderRow>()
        for (i in 0 until a.length()) {
            val j = a.optJSONObject(i) ?: continue
            val id = j.optString("id")
            if (id.isEmpty()) continue
            out[id] = Broker.OrderRow(id = id, symbol = j.optString("symbol"), side = j.optString("side"), qty = j.optInt("qty"), filled = j.optInt("filled"),
                price = 0.0, avg = j.optDouble("avg", 0.0).let { if (it.isFinite()) it else 0.0 }, status = j.optString("status"),
                message = j.optString("message"), placedAt = j.optString("placedAt"), exchange = j.optString("exchange", "NFO"),
                product = j.optString("product"), type = j.optString("type", "LIMIT"), tag = j.optString("tag"))
        }
        return out
    }

    /**
     * [day]'s kept live orders (under [snapLock]; a worker thread): from memory, else its sealed file read once. No file: none.
     * Null: the file could not be read just now (the Keystore busy, an erase running) - it is read again next time and is
     * never written over. A file that can never be opened again (its key is gone, or it is damaged) is deleted: it is only
     * a copy for the review, and a new one starts.
     */
    private fun snapOf(day: LocalDate): Map<String, Broker.OrderRow>? {
        snaps[day]?.let { return it }
        val f = snapFile(day) ?: return null
        val plain = try {
            if (f.isFile) com.optionslab.app.security.Vault.readFileSteady(f) else null
        } catch (_: com.optionslab.app.security.Vault.Tampered) {
            runCatching { f.delete() }; null
        } catch (_: Throwable) {
            return null
        }
        val rows = if (plain == null) emptyMap() else runCatching { decodeRows(plain) }.getOrElse { runCatching { f.delete() }; emptyMap() }
        snaps[day] = rows
        return rows
    }

    /** Once a day (under [snapLock]): the snapshots older than [SNAP_KEEP_DAYS] deleted, from memory and the disk. */
    private fun pruneIfDue(today: LocalDate) {
        if (snapPruned == today) return
        snapPruned = today
        val oldest = today.minusDays(SNAP_KEEP_DAYS)
        snaps.keys.removeAll { it.isBefore(oldest) }
        for (f in snapFiles()) {
            val d = runCatching { LocalDate.parse(f.name.removePrefix(SNAP_PREFIX).take(10)) }.getOrNull()
            if (d == null || d.isBefore(oldest)) runCatching { f.delete() }
        }
    }

    /**
     * 15 Nov (Boss's diagnostics: the evening's review and the day's file missed the live orders placed before a restart or a
     * low-memory kill - they were only in memory): the live orders in [list] (Zerodha's order book as just read) kept by the
     * day each was placed, in one small file a day, sealed with the vault's key as the app's other private files are (an
     * order's symbol, quantity, price and Zerodha's message are account data: never in plain text). Orders already kept stay;
     * one read again takes its newest state. A day whose orders did not change is not written again. Returns the files written.
     *
     * A worker thread only (a Keystore encryption and a disk sync): called from this host's own watcher ([watchOrders]),
     * never from the code that places an order or handles a tick, and nothing on those paths waits for it.
     */
    internal fun snapshotOrders(list: List<Broker.OrderRow>, today: LocalDate = Market.today()): Int {
        if (appContext == null) return 0
        val gen = wipeGen
        val oldest = today.minusDays(SNAP_KEEP_DAYS)
        val byDay = LinkedHashMap<LocalDate, LinkedHashMap<String, Broker.OrderRow>>()
        for (o in list) {
            if (o.id.isEmpty()) continue
            val d = kiteAt(o.placedAt)?.toLocalDate() ?: continue
            if (d.isBefore(oldest) || d.isAfter(today.plusDays(1))) continue
            byDay.getOrPut(d) { LinkedHashMap() }[o.id] = keptRow(o)
        }
        var wrote = 0
        synchronized(snapLock) {
            // An erase since this began: nothing is kept ([wipe] deletes, under this lock, whatever was written before it).
            if (gen != wipeGen) return 0
            pruneIfDue(today)
            for ((d, rows) in byDay) {
                val before = snapOf(d) ?: continue
                val merged = LinkedHashMap(before)
                merged.putAll(rows)
                if (merged == before) continue
                val f = snapFile(d) ?: continue
                // False when an erase dropped the write: then it is not taken as kept.
                val written = runCatching { com.optionslab.app.security.Vault.writeFileWritten(f, encodeRows(merged.values)) }.getOrDefault(false)
                if (written) { snaps[d] = merged; wrote++ }
            }
        }
        return wrote
    }

    /** The order book in memory now, kept ([snapshotOrders]); never one read before an erase. Returns the files written. */
    private fun snapshotLatest(): Int {
        synchronized(snapLock) {
            val v = AppState.orders.value ?: return 0
            if (v.at <= erasedAt) return 0
            return snapshotOrders(v.value)
        }
    }

    /**
     * This host's own watcher (its background scope, a worker thread; started once, after the open positions are restored):
     * whenever the app's regular passes have read Zerodha's order book into memory, the day's live orders are kept
     * ([snapshotOrders]), and after a write not again for [SNAP_GAP_MS] (the newest book is taken then). It only reads what
     * is already in memory; the read that published the book never waits for it.
     */
    private fun watchOrders() {
        if (!watching.compareAndSet(false, true)) return
        scope.launch {
            com.optionslab.app.data.PositionsFirst.awaitPositions()
            AppState.orders.collect {
                val wrote = if (snapshotsOn) runCatching { snapshotLatest() }.getOrDefault(0) else 0
                if (wrote > 0) delay(SNAP_GAP_MS)
            }
        }
    }

    /** The kept live orders of the days [from]..[to] (at most the days kept; a worker thread). */
    private fun keptOrders(from: Long, to: Long): List<Broker.OrderRow> {
        if (appContext == null || to < from) return emptyList()
        val lastDay = dayOf(to)
        var d = dayOf(from)
        val oldest = lastDay.minusDays(SNAP_KEEP_DAYS)
        if (d.isBefore(oldest)) d = oldest
        val out = ArrayList<Broker.OrderRow>()
        synchronized(snapLock) {
            runCatching { pruneIfDue(Market.today()) }
            while (!d.isAfter(lastDay)) {
                snapOf(d)?.let { out += it.values }
                d = d.plusDays(1)
            }
        }
        return out
    }

    /** Every order and trade in [from]..[to] for [day], from memory and the app's own files (no network). A worker thread. */
    private suspend fun gather(day: LocalDate, from: Long, to: Long): OrderReview.Inputs {
        val owners = runCatching { Strategies.owners() }.getOrDefault(emptyMap())
        val orders = ArrayList(ordersIn(owners, from, to))
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
        // 12 Oct: the protections set in the window (a target under the price paid, a stop replaced by nothing, a guard on an
        // arm's own position) and the diary's errors today by kind ("Missing required field: quantity" x3).
        val prots = runCatching { Protections.since(from - 86_400_000L) }.getOrDefault(emptyList())
        val protections = prots.filter { it.id in from..to }.map { p ->
            OrderReview.Protect(p.id, if (p.live) OrderReview.Venue.LIVE else OrderReview.Venue.PAPER, p.symbol, p.qty > 0, p.stop, p.target,
                replaced = prots.any { it !== p && it.live == p.live && it.symbol == p.symbol && it.id < p.id && it.note == "replaced" })
        }
        val errors = if (day == Market.today()) runCatching { Diag.errors.of(day).errors }.getOrDefault(emptyMap()) else emptyMap()
        val live = runCatching { AppSettings.load().live }.getOrDefault(false)
        // Liquidity's fill quality that day (12 Oct): the bid/ask, depth and timing at its signals and fills, as notes (facts).
        val quality = runCatching { LiquidityTest.reviewNotes(day) }.getOrDefault(emptyList())
        // The pre-trade checks in shadow (13 Oct, R17): the charges check and smart stop at each paper entry, as notes (facts).
        val pretrade = runCatching { PreTradeShadow.reviewNotes(day) }.getOrDefault(emptyList())
        // The OI-wall check in shadow (14 Oct, R26 rule P): on an expiry day, whether it fired and its ticket in points (a record only).
        val oiWall = if (com.optionslab.app.BuildConfig.GOLD) emptyList() else runCatching { OiWallShadowHost.reviewNotes(day) }.getOrDefault(emptyList())
        return OrderReview.Inputs(day, orders, trades, fills, sync, speed, tracked, to, protections, errors, live, fillNotes = quality + pretrade + oiWall)
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
        // 12 Oct: charges eating the day, re-entries and the protection and diary flaws are in sight too, not only the serious ones.
        val warnings = (d?.flaws.orEmpty().filter { OrderReview.inSight(it) }.sortedByDescending { it.severity.ordinal }.map { OrderReview.flawLine(it) } +
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

    /**
     * 26 Oct (Erase everything): the kept review (Boss's orders and trades, their flaws, the lab requests) forgotten and its file
     * deleted, under the same lock as its saves; a review in flight sees [wipeGen] changed and keeps nothing. Never the vault.
     * The file counts as read (it is gone), so the next review starts a new one.
     * 15 Nov: the kept live orders too ([snapshotOrders]): forgotten and every day's file deleted, under their own lock (a
     * snapshot in flight ends first and its file is deleted here; one that begins later sees [wipeGen] changed, and the order
     * book read before this erase is never kept again).
     */
    fun wipe() {
        synchronized(lock) {
            wipeGen++
            _book.value = OrderReview.Book()
            loaded = true; retryAtMs = 0L; lastFailed = null
            synchronized(snapLock) {
                snaps.clear(); snapPruned = null
                erasedAt = System.currentTimeMillis()
                filesForErase().forEach { runCatching { it.delete() } }
            }
        }
    }

    /** 26 Oct: a review kept in memory (memory only, for the erase's tests). 15 Nov: or a day's live orders. */
    internal fun holdsForErase(): Boolean = _book.value != OrderReview.Book() || synchronized(snapLock) { snaps.values.any { it.isNotEmpty() } }

    /**
     * 26 Oct: the review's file and its temporary file (none before [init]). 15 Nov: the sealed file and the vault's temporary
     * files of it, the plain file kept before 15 Nov and its temporary file, and every day's kept live orders.
     */
    internal fun filesForErase(): List<File> {
        val f = file() ?: return emptyList()
        val p = plainFile() ?: return emptyList()
        val temps = runCatching { dir()?.listFiles { x -> x.name.startsWith("$FILE.") && x.name.endsWith(".tmp") }?.toList() }.getOrNull().orEmpty()
        return listOf(f, p, File(p.parentFile, p.name + ".tmp")) + temps + snapFiles()
    }

    /** TEST ONLY: nothing kept, the file read (as empty); the live orders' snapshots forgotten (memory only) and their watcher off. */
    internal fun resetForTest() {
        synchronized(lock) { _book.value = OrderReview.Book() }
        loaded = true; retryAtMs = 0L; running.set(false); testInputs = null; lastFailed = null
        synchronized(snapLock) { snaps.clear(); snapPruned = null; erasedAt = Long.MIN_VALUE }
        snapshotsOn = false
    }

    /** TEST ONLY: nothing in memory and the files not read yet (a new process). */
    internal fun reloadForTest() {
        synchronized(lock) { _book.value = OrderReview.Book() }; loaded = false
        synchronized(snapLock) { snaps.clear(); snapPruned = null }
    }
}
