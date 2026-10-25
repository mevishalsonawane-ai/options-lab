package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.work.Notifier
import com.optionslab.engine.risk.FlatBy
import java.time.LocalDate

/**
 * The hard flat-by (22 Oct 2026, Boss: "a hard flat-by minute per segment"; the rule is the engine's [FlatBy]): every open
 * INTRADAY (MIS) arm position still open at its segment's minute is closed - paper and live, NSE and MCX, hidden arms too -
 * after the arms' own exits have had their chance (ORB 15:10, Hero and FT3 15:05, the passed arms 15:10 / 15:15, Pine and the
 * ntfy alerts 15:15, Solo 14:30, the paper book's own 15:15 MIS square-off, the live mirror's 15:15; MCX's evening call
 * 23:15 and the paper book's MIS cut). NSE index options 15:25, stock and fund 15:20, MCX ten minutes before its close.
 * On an expiry day the 15:05 square-off ([ExpirySquareOff]) still comes first.
 *
 * Positions designed to be held are left alone, as the expiry square-off leaves them: anything NRML (Night (R3)'s overnight
 * option, the Expiry Put ticket's legs, the MCX arms' futures, a positional strategy), and on the live side anything that is
 * not an arm's (Boss's own trades are his). Runs from the market watch every pass from the minute until the segment's close
 * until a pass finds nothing left (an exit sent is not a position closed), then not again that day. The kill switch never
 * stops it: closing only lowers risk. Off the order and tick path; it never slows the position watch (one minute compare
 * when nothing is due).
 */
object FlatBySquareOff {
    /**
     * TEST ONLY: a fixed clock for this pass's day and minute, as [ExpirySquareOff.testNow]. Null in the app, always; its
     * setter throws unless BuildConfig.DEBUG, and no app code sets it.
     */
    @Volatile internal var testNow: java.time.ZonedDateTime? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }; field = v }
    private fun todayIst(): LocalDate = testNow?.toLocalDate() ?: Market.today()
    private fun minuteNow(): Int = testNow?.let { it.hour * 60 + it.minute } ?: Market.minuteNow()

    /**
     * Which arm holds [symbol] live (memory only): the live mirror's arms ([ArmLive]), ORB's own live trades, Pine's.
     * Replaceable in tests. Null: not an arm's, so it is left alone.
     */
    /** The owner look's answer: [name] null means "not an arm's position" (Boss's own), told apart from a look that never answered. */
    private class Owner(val name: String?)

    @Volatile internal var liveOwner: suspend (String) -> String? = { sym ->
        ArmLive.ownerOf(sym)
            ?: runCatching { OrbArms.liveOpen().firstOrNull { it.second.kite == sym || it.second.symbol == sym }?.first }.getOrNull()
            ?: runCatching { PineAuto.liveSymbols() }.getOrDefault(emptyList()).firstOrNull { it == sym }?.let { "Pine" }
    }

    /** Whether the arms' live book is read (memory): until it is, no live position can be told an arm's, so the pass is not done. */
    @Volatile internal var liveBooksReady: () -> Boolean = { ArmLive.view.value.let { it.loaded && !it.unreadable } }

    /** "day|segment" done for the day (memory only: after a restart the pass just looks again, which is cheap). */
    private val done = HashSet<String>()
    /** What the owner was already told today ("day|what"), so a retry every pass does not repeat it. */
    private val told = HashSet<String>()

    private fun tellOnce(context: Context, key: String, text: String) {
        val k = "${todayIst()}|$key"
        synchronized(told) { if (!told.add(k)) return }
        // Its own notice per contract (one id each), so two refusals in one pass are both seen.
        runCatching { Notifier.post(context, 2040 + (key.hashCode() and 0x7fffffff) % 40, Notifier.RISK, "Flat-by", text, "trade") }
    }

    /** TEST: forget the day's done marks and notices. */
    internal fun resetForTest() { synchronized(done) { done.clear() }; synchronized(told) { told.clear() } }

    /** The segments whose flat-by is due now on [today] and not done yet. */
    internal fun dueNow(today: LocalDate, minute: Int): List<FlatBy.Segment> {
        val nse = Market.isTradingDay(today)
        // MCX: only on a day it trades (a holiday shut all day has no window; an evening-only session keeps the same close; a special
        // session - the Diwali Muhurat hour - has its own close, ten minutes before which it goes; 25 Oct review).
        val cal = mcxCalendar()
        val mcx = runCatching { cal.window(today) != null }.getOrDefault(false)
        return FlatBy.Segment.values().filter { seg ->
            (if (seg == FlatBy.Segment.MCX) mcx else nse) && FlatBy.due(seg, today, minute, cal) && synchronized(done) { "$today|$seg" !in done }
        }
    }

    /** MCX's calendar as the app has it (the fetched list, else the built-in one); memory after its first read. */
    internal fun mcxCalendar(): com.optionslab.engine.mcx.McxSession.Calendar = runCatching { McxMarket.calendar() }.getOrDefault(com.optionslab.engine.mcx.McxSession.DEFAULT)

    suspend fun maybeRun(context: Context, s: AppSettings) {
        val today = todayIst()
        val segments = dueNow(today, minuteNow())
        if (segments.isEmpty()) return
        val closed = ArrayList<String>()
        val stillOpen = HashMap<FlatBy.Segment, Int>()
        var unread = false

        // Paper: every MIS position of a due segment (the paper book's own 15:15 square-off normally got there first).
        runCatching {
            val snap = Paper.snapshot()
            for (p in snap.positions.positions.filter { it.quantity != 0 }) {
                if (!FlatBy.intraday(p.product)) continue
                val c = Paper.contractOf(p.symbol) ?: continue
                val seg = FlatBy.segment(c.exchange, c.underlying)
                if (seg !in segments) continue
                // Held by design: Night (R3)'s overnight option (NRML anyway, checked by name as the expiry square-off does).
                if (NightArm.holds(p.symbol)) continue
                stillOpen[seg] = (stillOpen[seg] ?: 0) + 1
                val r = Paper.close(p.symbol, p.product)
                r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", Origins.FLAT_BY) } }
                if (r.ok) closed += "paper ${p.symbol}" else tellOnce(context, "paper ${p.symbol}", "Paper ${p.symbol} could not be closed at its flat-by: ${r.message}. Retrying.")
            }
        }.onFailure { unread = true }

        // Live: an arm's MIS position (never Boss's own) of a due segment; the orders read before the positions (as the expiry
        // square-off since 20 Oct), shorts bought back before longs are sold.
        if (Broker.loggedIn) runCatching {
            // The arms' live book not read yet (a restart at 15:24): an arm's position cannot be told from Boss's own, so nothing
            // is closed and the day is not marked done; the next pass, with the book in, looks again.
            if (!runCatching { liveBooksReady() }.getOrDefault(false)) { unread = true; return@runCatching }
            val ins = (Broker.cachedInstruments().orEmpty() + Broker.cachedBfoOptions().orEmpty()).associateBy { it.tradingSymbol }
            val read = runCatching { Broker.orders() }.getOrElse { unread = true; return@runCatching }
            val working = read.filter { it.working }
            val positions = Broker.positionBook()
            val book = positions.net.filter { it.qty != 0 && FlatBy.intraday(it.product) }.sortedBy { if (it.qty < 0) 0 else 1 }
            for (p in book) {
                // (25 Oct review: the owner's look may wait on the arms' lock; two seconds at most, else the position is looked at next
                // pass. A look that timed out or failed is NOT "not an arm's": the pass counts as unread, so the segment is not marked
                // done for the day and the next pass looks again - merge review, 25 Oct: it was taken as Boss's own and the day closed.)
                val look = runCatching { kotlinx.coroutines.withTimeoutOrNull(2_000) { Owner(liveOwner(p.symbol)) } }.getOrNull()
                if (look == null) { unread = true; continue }
                val owner = look.name ?: continue
                val i = ins[p.symbol]
                // An underlying the app's list does not know: judged by the exchange alone (MCX, else an NSE option is an index's
                // only when its name says so).
                val underlying = i?.name ?: FlatBy.INDEX_UNDERLYINGS.firstOrNull { p.symbol.startsWith(it) } ?: ""
                val seg = FlatBy.segment(p.exchange, underlying)
                if (seg !in segments) continue
                stillOpen[seg] = (stillOpen[seg] ?: 0) + 1
                // Zerodha keeps MCX positions and orders in lots (one lot at a time, the contract's multiplier), as [McxGuard].
                val mcx = if (seg == FlatBy.Segment.MCX) runCatching { McxMarket.find(p.symbol) }.getOrNull() else null
                val lot = if (seg == FlatBy.Segment.MCX) mcx?.let { 1 } else i?.lotSize
                if (lot == null || lot <= 0) { tellOnce(context, "unknown ${p.symbol}", "$owner's ${p.symbol} (${p.qty}) is past its flat-by time and the app cannot tell its lot size, so it is NOT closed automatically. Close it yourself in Trade."); continue }
                val placed = SquareOffExit.send(p, lot, mcx?.tick ?: i?.tickSize ?: 0.05, read, working, positions, s.limits(), "flat-by", "iraalgoflat",
                    "${Origins.FLAT_BY} · $owner", "is past its flat-by time", "flat-by", multiplier = mcx?.multiplier ?: 1) { key, text -> tellOnce(context, key, text) }
                if (placed) closed += "live ${p.symbol} ($owner)"
            }
        }.onFailure { unread = true }
        if (!Broker.loggedIn && Broker.linked) unread = true

        if (!unread) synchronized(done) { for (seg in segments) if ((stillOpen[seg] ?: 0) == 0) done += "$today|$seg" }
        if (closed.isNotEmpty()) {
            runCatching { Diag.record("flatby", "Flat-by at ${FlatBy.text(minuteNow())}: sent the close of ${closed.joinToString()}") }
            // (A pass that sent an exit is not a pass that found nothing: the segment stays open until one does.)
            Notifier.post(context, 2033, Notifier.APPROVAL, "Flat-by: intraday positions closed",
                "Sent the close of ${closed.size} intraday position(s) still open at the flat-by: ${closed.joinToString()}. Checked again until none is left.", "trade")
        }
    }

    /** The diagnostics' line: the minutes, and what is done today. */
    fun diagLine(): String {
        val today = todayIst()
        val doneNow = synchronized(done) { FlatBy.Segment.values().filter { "$today|$it" in done } }
        return "Flat-by: index ${FlatBy.text(FlatBy.NSE_INDEX_MINUTE)}, stock ${FlatBy.text(FlatBy.NSE_STOCK_MINUTE)}, MCX ${FlatBy.text(FlatBy.minute(FlatBy.Segment.MCX, today, mcxCalendar()))}" +
            (if (doneNow.isEmpty()) "; nothing done yet today" else "; done today: " + doneNow.joinToString { it.label })
    }
}
