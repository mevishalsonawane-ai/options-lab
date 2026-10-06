package com.optionslab.ira.dhan

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The download, as a list of chunks ([Task]) each of which is one request and one file. Windows sit on a fixed grid of
 * dates, so the same chunk always has the same file. A chunk is DONE only when its data is complete ([Files.done]): its
 * file exists, its window has ended, and the store's fetch ledger ([Files.FETCHED]) says it was fetched on or after the
 * window's end (a file fetched while its window was still open is fetched again - from its last stored candle - once the
 * window has ended, so no window keeps a permanent hole). That is what makes a stopped download resume where it was. A
 * window that reaches today is refreshed on each run (from its last stored candle, appended) until it has ended.
 *
 * Dhan's limits: one-minute candles at most 90 days a call (windows of [MIN_WINDOW_DAYS]); expired options at most 30
 * days a call ([OPT_WINDOW_DAYS]); daily candles from the earliest Dhan has (windows of [DAY_WINDOW_DAYS] from [DAY_FROM]).
 * Pure.
 */
object Plan {
    const val MIN_WINDOW_DAYS = 85L
    const val OPT_WINDOW_DAYS = 25L
    const val DAY_WINDOW_DAYS = 1825L
    val DAY_FROM: LocalDate = LocalDate.of(2000, 1, 1)
    /** Grid anchor for the minute and option windows. */
    val ANCHOR: LocalDate = LocalDate.of(2015, 1, 1)
    /**
     * Strikes either side of the money asked of Dhan's rolling (expired) options. Dhan's v2 docs (Expired Options Data):
     * "ATM+10 / ATM-10 for index options near expiry, ATM+3 / ATM-3 for all other contracts". The plan only ever asks the
     * near series ([EXPIRY_CODE], as Dhan's own example asks it) - so index options get +-10 and company options +-3, and
     * nothing beyond what Dhan serves is requested. A strike Dhan refuses (DH-905, an input error) is counted failed, not
     * stored as an empty "done" file ([DhanApi.classify]).
     */
    const val INDEX_OFFSETS = 10
    const val STOCK_OFFSETS = 3
    /** The near contract ("1") of the rolling series, as Dhan's documented example asks it. */
    const val EXPIRY_CODE = 1

    /** The strike offsets asked for an option window of [group] (IDX: index options; EQ: company options). */
    fun offsets(group: Group): IntRange = if (group == Group.EQ) -STOCK_OFFSETS..STOCK_OFFSETS else -INDEX_OFFSETS..INDEX_OFFSETS

    /** The file names an option window of [group] holds when complete: CE/PE at every offset ("CE+0.csv.gz", ...). */
    fun optionFiles(group: Group): List<String> = offsets(group).flatMap { o -> listOf(true, false).map { "${side(it)}${sign(o)}.csv.gz" } }

    enum class Kind { EXPIRIES, CHAIN, DAY, MIN, OPT }

    /** Where the instrument's files go: idx (index), fut (near future), eq (company). */
    enum class Group(val dir: String) { IDX("idx"), FUT("fut"), EQ("eq") }

    /**
     * One request. [symbol] the app's name, [id] Dhan's security ID, [segment]/[instrument] as Dhan names them, the
     * window [from] (inclusive) to [to] (exclusive); for options the rolling [flag], the strike [offset] from the money
     * and the side ([call]).
     */
    data class Task(val kind: Kind, val group: Group, val symbol: String, val id: String, val segment: String, val instrument: String,
                    val from: LocalDate, val to: LocalDate, val flag: String = "", val offset: Int = 0, val call: Boolean = true) {
        /** The file this chunk is stored in, relative to the store's root. */
        val path: String get() = when (kind) {
            Kind.EXPIRIES -> "expiries/${safe(symbol)}.csv"
            Kind.CHAIN -> "chain/${safe(symbol)}/$from.json.gz"
            Kind.DAY -> "${group.dir}/${safe(symbol)}/day/$from.csv.gz"
            Kind.MIN -> "${group.dir}/${safe(symbol)}/min/$from.csv.gz"
            Kind.OPT -> "opt/${safe(symbol)}/$flag/$from/${side(call)}${sign(offset)}.csv.gz"
        }
        /** The window reaches [today]: refreshed each run until it has ended. */
        fun open(today: LocalDate): Boolean = kind == Kind.EXPIRIES || kind == Kind.CHAIN || to.isAfter(today)
        /** Days in the window. */
        val days: Long get() = ChronoUnit.DAYS.between(from, to)
    }

    fun side(call: Boolean) = if (call) "CE" else "PE"
    fun sign(offset: Int) = if (offset >= 0) "+$offset" else "$offset"
    /** A symbol as a folder name ("M&M" -> "M_M"). */
    fun safe(symbol: String): String = symbol.uppercase().map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("")

    /** The windows of [len] days on the grid from [anchor], covering [from] .. [today]; newest first. */
    fun windows(from: LocalDate, today: LocalDate, len: Long, anchor: LocalDate = ANCHOR): List<Pair<LocalDate, LocalDate>> {
        if (from.isAfter(today)) return emptyList()
        val first = ChronoUnit.DAYS.between(anchor, from).let { Math.floorDiv(it, len) }
        val last = ChronoUnit.DAYS.between(anchor, today).let { Math.floorDiv(it, len) }
        return (last downTo first).map { i -> anchor.plusDays(i * len) to anchor.plusDays((i + 1) * len) }
    }

    /** What Boss chose to download. Years count back from today. */
    data class Choice(
        val indexMinuteYears: Int = 5,
        val optionYears: Int = 2,
        val futures: Boolean = true,
        val stocks: Boolean = true,
        val stockMinuteYears: Int = 1,
        val stockOptions: Boolean = false,
        val stockOptionYears: Int = 1,
    )

    /**
     * Every chunk, in the order they are fetched: each index's expiry list and today's chain, its daily candles, its
     * minute candles, its near future, its expired options (newest window first, so the latest data lands first); then the
     * companies' daily and minute candles; then (only when chosen) the companies' expired options.
     */
    fun tasks(master: DhanUniverse.Master?, choice: Choice, today: LocalDate): List<Task> {
        val out = ArrayList<Task>()
        val dayWindows = windows(DAY_FROM, today, DAY_WINDOW_DAYS, DAY_FROM)
        fun minWindows(years: Int) = if (years <= 0) emptyList() else windows(today.minusYears(years.toLong()), today, MIN_WINDOW_DAYS)
        fun optWindows(years: Int) = if (years <= 0) emptyList() else windows(today.minusYears(years.toLong()), today, OPT_WINDOW_DAYS)
        for (ix in DhanUniverse.INDICES) {
            val id = DhanUniverse.indexId(ix, master) ?: continue
            if (ix.options) {
                out += Task(Kind.EXPIRIES, Group.IDX, ix.name, id, "IDX_I", "INDEX", today, today.plusDays(1))
                out += Task(Kind.CHAIN, Group.IDX, ix.name, id, "IDX_I", "INDEX", today, today.plusDays(1))
            }
            for ((f, t) in dayWindows) out += Task(Kind.DAY, Group.IDX, ix.name, id, "IDX_I", "INDEX", f, t)
            for ((f, t) in minWindows(choice.indexMinuteYears)) out += Task(Kind.MIN, Group.IDX, ix.name, id, "IDX_I", "INDEX", f, t)
        }
        if (choice.futures) for (ix in DhanUniverse.INDICES) {
            val fut = master?.nearFutures?.get(ix.name) ?: continue
            // The near contract's own history (Dhan serves a contract's candles while it trades; expired futures are not served).
            for ((f, t) in windows(today.minusDays(120), today, MIN_WINDOW_DAYS)) {
                out += Task(Kind.MIN, Group.FUT, ix.name, fut.id, fut.segment, fut.instrument, f, t)
            }
            // One file per contract (named by its expiry less 400 days), fetched again each run while it trades.
            out += Task(Kind.DAY, Group.FUT, ix.name, fut.id, fut.segment, fut.instrument, fut.expiry.minusDays(400), today.plusDays(1))
        }
        for (ix in DhanUniverse.INDICES) {
            if (!ix.options) continue
            val id = DhanUniverse.indexId(ix, master) ?: continue
            for ((f, t) in optWindows(choice.optionYears)) for (off in offsets(Group.IDX)) for (call in listOf(true, false)) {
                out += Task(Kind.OPT, Group.IDX, ix.name, id, ix.optSegment, "OPTIDX", f, t, ix.flag, off, call)
            }
        }
        if (choice.stocks && master != null) {
            val stocks = DhanUniverse.allConstituents().mapNotNull { s -> master.equityIds[s]?.let { s to it } }
            for ((s, id) in stocks) for ((f, t) in dayWindows) out += Task(Kind.DAY, Group.EQ, s, id, "NSE_EQ", "EQUITY", f, t)
            for ((s, id) in stocks) for ((f, t) in minWindows(choice.stockMinuteYears)) out += Task(Kind.MIN, Group.EQ, s, id, "NSE_EQ", "EQUITY", f, t)
            if (choice.stockOptions) for ((s, id) in stocks) for ((f, t) in optWindows(choice.stockOptionYears))
                for (off in offsets(Group.EQ)) for (call in listOf(true, false)) {
                    out += Task(Kind.OPT, Group.EQ, s, id, "NSE_FNO", "OPTSTK", f, t, "MONTH", off, call)
                }
        }
        return out
    }

    /** The chunks still to fetch: open, or not [done] (absent, or fetched before its window ended - see [Files.done]). */
    fun todo(tasks: List<Task>, today: LocalDate, done: (Task) -> Boolean): List<Task> = tasks.filter { it.open(today) || !done(it) }

    /** Monday to Friday: the calendar used when no exchange holiday list is given. */
    val WEEKDAYS: (LocalDate) -> Boolean = { it.dayOfWeek.value <= 5 }

    /** The last trading day in [from] .. [toExclusive] (per [tradingDay]), or null when there is none. */
    fun lastTradingDay(from: LocalDate, toExclusive: LocalDate, tradingDay: (LocalDate) -> Boolean = WEEKDAYS): LocalDate? {
        var d = toExclusive.minusDays(1)
        while (!d.isBefore(from)) { if (tradingDay(d)) return d; d = d.minusDays(1) }
        return null
    }
}
