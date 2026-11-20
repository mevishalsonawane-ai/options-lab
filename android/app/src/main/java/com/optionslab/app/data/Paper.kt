package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.fmtG
import com.optionslab.engine.risk.PriceWatch
import com.optionslab.engine.sandbox.Instrument
import com.optionslab.engine.sandbox.InstrumentMaster
import com.optionslab.engine.sandbox.OrderChange
import com.optionslab.engine.sandbox.OrderRequest
import com.optionslab.engine.sandbox.OrderResult
import com.optionslab.engine.sandbox.Quote
import com.optionslab.engine.sandbox.Sandbox
import com.optionslab.engine.sandbox.SandboxConfig
import com.optionslab.engine.sandbox.SandboxEvent
import com.optionslab.engine.sandbox.SandboxJson
import com.optionslab.engine.sandbox.SandboxState
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The sandbox account: IraAlgo's paper-trading engine (engine/sandbox, proven
 * step-for-step against the Python) running on the phone.
 *
 * SANDBOX MODE NEVER TOUCHES THE BROKER. Prices are Upstox's public 1-minute
 * candles, the same feed the rest of sandbox mode uses; orders, fills,
 * margin, MIS square-off and expiry settlement are all simulated here.
 *
 * Symbols are IraAlgo's (NIFTY29SEP2624500PE: underlying, DDMMMYY, strike,
 * right), because the engine reads the expiry from the symbol. Every
 * contract ever traded is remembered with its lot and feed key, so a
 * position can still be closed and settled after it drops off the master.
 *
 * The account lives in one encrypted vault file.
 */
object Paper {
    private lateinit var file: File

    fun init(context: Context) { file = File(context.applicationContext.filesDir, "paper.vault") }

    /**
     * A contract the paper account trades. [exchange] "NFO" (index options, as always) or "MCX" (9 Oct: commodities; a
     * future's [right] is IX and its strike 0, its [lotSize] is the lot's units, its [feedKey] Upstox's MCX_FO key).
     */
    data class Contract(val symbol: String, val underlying: String, val expiry: LocalDate, val strike: Double, val right: Right,
                        val lotSize: Int, val feedKey: String, val exchange: String = "NFO") {
        val isMcx: Boolean get() = exchange == com.optionslab.engine.mcx.Mcx.EXCHANGE
    }

    /** The exchange a paper symbol trades on (NFO unless it is a remembered MCX contract). */
    private fun exchangeOf(symbol: String): String = exchangeIn(book().contracts, symbol)

    /** [exchangeOf] in a given list of [contracts] (one rule for the books and the no-price failsafe, 15 Oct). */
    internal fun exchangeIn(contracts: Map<String, Contract>, symbol: String): String = contracts[symbol]?.exchange ?: "NFO"

    /**
     * The open positions [symbols] as the no-price failsafe judges them (15 Oct, [Sweeper]): each one's price key as
     * [quotes] keys it, its exchange and its expiry (null when its contract is not remembered).
     */
    internal fun failsafeHeld(symbols: Collection<String>, contracts: Map<String, Contract>): List<PriceWatch.Held> =
        symbols.map { s -> exchangeIn(contracts, s).let { ex -> PriceWatch.Held(Sandbox.key(s, ex), ex, contracts[s]?.expiry) } }

    /** [why]: why the app cancelled an order (order id -> a reason key, see [cancel]); the newest [WHY_KEPT] only. */
    private data class Book(val state: SandboxState, val capital: BigDecimal, val contracts: Map<String, Contract>,
                            val why: Map<String, String> = emptyMap(),
                            /**
                             * 19 Oct (the same-direction cap, [com.optionslab.ira.SameDirectionCap]): the automatic entries' order ids
                             * (order id -> the arm that placed it; the newest [AUTO_KEPT] only). Boss's own orders are never here.
                             */
                            val auto: Map<String, String> = emptyMap())

    /** How many automatic entries' order ids are kept (the newest). */
    private const val AUTO_KEPT = 400

    /** How many cancel reasons are kept (the newest). */
    private const val WHY_KEPT = 300

    private val DDMMMYY = DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH)

    fun symbolOf(c: Upstox.Contract): String =
        c.underlying + c.expiry.format(DDMMMYY).uppercase(Locale.ENGLISH) + fmtG(c.strike) + c.right.name

    /** Volatile: [mcxExposureIfLoaded] reads it without the lock (the main thread never waits on a save's Keystore work). */
    @Volatile private var cache: Book? = null

    /**
     * ANR fix (9 Oct, MCX held all day): when the book was last written (wall-clock ms; guarded by this object's lock). A pass
     * that only re-marks the open positions to new prices is kept in memory and written at most every [MARK_SAVE_MS]: each
     * write is a Keystore encryption of the whole book, and the Keystore (StrongBox on a Pixel) takes one operation at a
     * time, so a write every 2-15 s for positions held all day kept every other vault read and write - the screen's
     * included - waiting behind it. Orders, fills, trades, cash and quantities are always written at once, as before.
     */
    private var savedAtMs = 0L
    internal const val MARK_SAVE_MS = 60_000L

    /**
     * The paper book holds an MCX position or has a working MCX order, read from memory only (no vault read, no lock):
     * null when the book has not been read in this process yet. For the main thread ([McxMarket.watchDueQuick]).
     */
    fun mcxExposureIfLoaded(): Boolean? {
        val st = cache?.state ?: return null
        val ex = com.optionslab.engine.mcx.Mcx.EXCHANGE
        return st.positions.any { it.quantity != 0 && it.exchange == ex } ||
            st.orders.any { (it.status == "open" || it.status == "trigger pending") && it.exchange == ex }
    }

    /** [state] from memory only (no vault read, no lock: safe on the main thread); null before the book is read. */
    fun stateIfLoaded(): SandboxState? = cache?.state

    /** [contractOf] from memory only (no vault read, no lock: safe on the main thread); null when not known yet. */
    fun contractIfLoaded(symbol: String): Contract? = cache?.contracts?.get(symbol)

    /**
     * Whether [after] differs from [before] only by the marks a price pass writes: each position's last price, P&L and
     * P&L % (and its updated time), and the funds' unrealised and total P&L (and their updated time). Nothing that an
     * order, a fill, a settlement or a reset changes.
     */
    internal fun marksOnly(before: SandboxState, after: SandboxState): Boolean {
        if (before.orders != after.orders || before.trades != after.trades || before.holdings != after.holdings) return false
        if (before.orderSeq != after.orderSeq || before.tradeSeq != after.tradeSeq) return false
        if (before.positions.size != after.positions.size) return false
        for (i in before.positions.indices) {
            val x = before.positions[i]; val y = after.positions[i]
            if (x.copy(ltp = y.ltp, pnl = y.pnl, pnlPercent = y.pnlPercent, updatedAt = y.updatedAt) != y) return false
        }
        val f = before.funds; val g = after.funds
        return f.copy(unrealizedPnl = g.unrealizedPnl, totalPnl = g.totalPnl, updatedAt = g.updatedAt) == g
    }

    /** [save], except that a marks-only change ([marksOnly]) within [MARK_SAVE_MS] of the last write stays in memory. */
    @Synchronized
    private fun saveMarked(b: Book) {
        val was = cache
        val age = System.currentTimeMillis() - savedAtMs
        if (was != null && savedAtMs > 0L && age in 0L until MARK_SAVE_MS && was.capital == b.capital && was.contracts == b.contracts &&
            was.why == b.why && marksOnly(was.state, b.state)) { cache = b; return }
        save(b)
    }

    /**
     * The book. 12 Oct (order speed): read from memory WITHOUT this object's lock when it is loaded - every reader (an
     * arm's [opens] check, [closingSide], [exitPrices], [state], [contractOf]) used to queue on the lock behind a pass that
     * held it, and a write's Keystore encryption ran under it. The book is an immutable value replaced whole under the lock,
     * so a lock-free read is always one whole book. Loading (once a process) is still under the lock.
     */
    private fun book(): Book {
        cache?.let { return it }
        return synchronized(this) { cache ?: load() }
    }

    /** Read the book from its vault file (or a fresh account); called under the lock by [book]. */
    private fun load(): Book {
        // 19 Nov 2026 (audit batch 2: after a restart with a Keystore hiccup the book was set aside and an empty account started, so every
        // arm booked its paper trades "closed_elsewhere"): a read that failed for a moment is not a damaged file. It is thrown, nothing is
        // cached and the file is kept, so a later call reads again (as the passed arms' book does) - at most every [readRetryGapMs]. Only
        // bytes that fail authentication or do not parse are set aside at once; a read that keeps failing [READ_FAILS_BEFORE_ASIDE] times
        // over [readAsideAfterMs] is set aside as before (the account cannot stay unreadable for good).
        val nowMs = System.currentTimeMillis()
        if (readFails > 0 && nowMs - lastReadFailMs in 0 until readRetryGapMs) throw java.io.IOException("the paper book could not be read now; read again shortly")
        val bytes: ByteArray? = try {
            // (Five tries, ~1.5 s, before a read counts as failed: most Keystore hiccups clear within it, so its callers rarely see one.)
            (testRead?.invoke() ?: Vault.readFileSteady(file, attempts = 5)).also { readFails = 0; firstReadFailMs = 0L }
        } catch (e: Vault.Tampered) {
            readFails = 0; firstReadFailMs = 0L
            null
        } catch (e: Vault.Erasing) {
            throw java.io.IOException("the paper book is being erased", e)
        } catch (e: Exception) {
            readFails += 1; lastReadFailMs = nowMs
            if (firstReadFailMs == 0L) firstReadFailMs = nowMs
            if (readFails < READ_FAILS_BEFORE_ASIDE || nowMs - firstReadFailMs < readAsideAfterMs) {
                runCatching { Diag.record("paper", "the paper book could not be read now (${e.javaClass.simpleName}, $readFails in a row); kept, read again shortly") }
                throw java.io.IOException("the paper book could not be read now", e)
            }
            runCatching { Diag.record("paper", "the paper book could not be read $readFails times in a row (${e.javaClass.simpleName}): set aside; a fresh account starts") }
            readFails = 0; firstReadFailMs = 0L
            null
        }
        val loaded = if (bytes == null) null else runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val cs = o.optJSONArray("contracts") ?: JSONArray()
            val contracts = (0 until cs.length()).map { cs.getJSONArray(it) }.associate {
                it.getString(0) to Contract(it.getString(0), it.getString(1), LocalDate.parse(it.getString(2)), it.getDouble(3),
                    Right.valueOf(it.getString(4)), it.getInt(5), it.getString(6), it.optString(7, "NFO").ifEmpty { "NFO" })
            }
            val w = o.optJSONObject("cancelWhy")
            val why = LinkedHashMap<String, String>()
            if (w != null) for (k in w.keys()) why[k] = w.optString(k)
            val a = o.optJSONObject("auto")
            val auto = LinkedHashMap<String, String>()
            if (a != null) for (k in a.keys()) auto[k] = a.optString(k)
            Book(SandboxJson.decode(o.getString("state")), BigDecimal(o.getString("capital")), contracts, why, auto)
        }.getOrNull()
        // An unreadable account is set aside, never silently overwritten.
        if (loaded == null && file.exists()) file.renameTo(File(file.parentFile, "paper.unreadable.${System.currentTimeMillis()}"))
        val b = loaded ?: fresh(SandboxConfig().startingCapital, emptyMap())
        cache = b
        runCatching { mcxExposureIfLoaded()?.let { McxMarket.notePaperExposure(it) } }
        followKnown(b)
        return b
    }

    /** The newest book not yet on disk (guarded by this object's lock); [flush] writes it. */
    private var pending: Book? = null
    /** One vault write at a time, in order; taken BEFORE this object's lock, never inside it. */
    private val writeLock = Any()

    /**
     * [block] under this object's lock, then the book it saved written to disk outside the lock, on the paper writer's own thread
     * ([writer]). 19 Oct (Boss's diagnostics: "Paper orders took 5.7 s typical from decision to sent today (18 orders)"): the
     * caller used to wait for that write (a Keystore encryption and two disk syncs, a second or more on a Pixel's StrongBox), so
     * an arm's entry waited on the snapshot's write, then the order's own, and the next index's entry waited behind both. Now
     * an order is answered from memory; the write follows on the writer at once (a process killed in that moment loses at
     * most the last change, which the next look writes again; the open positions are on disk within the second).
     */
    private inline fun <T> locked(block: () -> T): T {
        try { return synchronized(this) { block() } } finally { writer.kick() }
    }

    /** The paper book's own writer thread ([locked]): one write at a time, the newest book last ([flush]). */
    private val writer = com.optionslab.ira.WriteBehind(com.optionslab.ira.WriteBehind.thread("paper-writer")) { flush() }

    /**
     * TEST ONLY: the vault write runs on the caller's thread again (the tests that read the file right after an order). False in
     * the app; the order-speed tests turn it off. Throws unless BuildConfig.DEBUG.
     */
    @Volatile internal var writeInlineForTest = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test switch exists only in debug builds" }; field = v; writer.inline = v }

    /** Every change made so far written to disk now (a wipe, a test), waiting [timeoutMs] at most. Never on the order path. */
    internal fun awaitWrites(timeoutMs: Long = 10_000L): Boolean = writer.await(timeoutMs).also { flush() }

    /**
     * Writes the newest saved book to the vault, outside this object's lock (readers and other orders go on meanwhile); one
     * write at a time, never an older book over a newer one (each takes the newest pending under the write lock). A failed
     * write is noted and kept pending for the next save's write; the book in memory stays as saved.
     */
    private fun flush() {
        synchronized(writeLock) {
            val b = synchronized(this) { pending.also { pending = null } } ?: return
            try {
                write(b)
            } catch (e: Exception) {
                synchronized(this) { if (pending == null) pending = b }
                runCatching { Diag.record("paper", "The paper book could not be written (${e.javaClass.simpleName}); it is written again with the next change") }
            }
        }
    }

    private fun write(b: Book) {
        val cs = JSONArray()
        b.contracts.values.forEach { cs.put(JSONArray().put(it.symbol).put(it.underlying).put(it.expiry.toString()).put(it.strike).put(it.right.name).put(it.lotSize).put(it.feedKey).put(it.exchange)) }
        val why = JSONObject(); b.why.forEach { (k, v) -> why.put(k, v) }
        val auto = JSONObject(); b.auto.forEach { (k, v) -> auto.put(k, v) }
        Vault.writeFile(file, JSONObject().put("state", SandboxJson.encode(b.state)).put("capital", b.capital.toPlainString())
            .put("contracts", cs).put("cancelWhy", why).put("auto", auto).toString().toByteArray(Charsets.UTF_8))
    }

    private fun fresh(capital: BigDecimal, contracts: Map<String, Contract>) =
        Book(engine(capital, contracts).newState(Market.now()), capital, contracts)

    /**
     * The book saved: in memory at once (under this object's lock: every reader sees it), and on disk by [flush] right after
     * the caller's critical section ([locked]) - 12 Oct: the vault write (a Keystore encryption of the whole book and two
     * disk syncs, a second or more on a Pixel's StrongBox) ran UNDER the lock, so every other paper order, cancel, pass and
     * reader waited behind it (paper "sent -> answer" up to 62 s). The caller still returns only once its book is on disk.
     */
    @Synchronized
    private fun save(b: Book) {
        pending = b
        // An order placed, changed, cancelled or filled (any caller, or the book's own tick): Jarvis's kept account
        // figures are read afresh. Marking positions to the price alone does not count (the kept copy says its time).
        val ordersMoved = cache.let { was -> was == null || was.state.orders != b.state.orders || was.state.trades != b.state.trades }
        cache = b
        savedAtMs = System.currentTimeMillis()
        // 21 Oct, enhancement round 3 (idea 3): the open positions and working orders followed on the stream as their own owner.
        followKnown(b)
        // The plain hint the main thread reads instead of this vault ([McxMarket.watchDueQuick]); written only on a change.
        runCatching { mcxExposureIfLoaded()?.let { McxMarket.notePaperExposure(it) } }
        if (ordersMoved) runCatching { com.optionslab.app.ira.IraAccount.invalidate() }
    }

    private fun engine(capital: BigDecimal, contracts: Map<String, Contract>) = Sandbox(
        // The desktop sandbox's execution costs (TODO A9): stops slip 10 bps, a MARKET fill with no
        // bid/ask (the Upstox candle feed has none) slips 5 bps, and every leg pays its charges.
        // Every close's P&L reaches the balance (the desktop drops it when the position has no margin left to release).
        // Honest paper (08 Oct, research/X3_AUDIT.md): every aggressive fill also pays the bid/ask half-spread - the stream's
        // real bid/ask, else the measured default for the index - by max(slippage, half-spread), never both. Always on:
        // no setting, nothing a voice command or a backup can change.
        // MCX (9 Oct) by its own clock: MIS squared off at 23:20 (23:45 in US winter), expiry from the day's real close; its
        // charges on the commodity schedule and its fills on the MCX spreads (the engine switches on the exchange).
        SandboxConfig(startingCapital = capital, stopSlippageBps = BigDecimal("10"), spreadFallbackBps = BigDecimal("5"), chargesEnabled = true,
            pnlAlwaysToFunds = true, paperSpread = true, mcxSessionAware = true),
        InstrumentMaster { sym, ex ->
            when (ex) {
                "NFO" -> contracts[sym]?.takeIf { !it.isMcx && it.exchange == "NFO" }?.let { Instrument(sym, "NFO", "OPTIDX", it.lotSize, 0.05, it.expiry, it.strike) }
                // 15 Oct: SENSEX's options on BSE (the Hero ₹1,000 ticket, paper only); F&O hours, lots and charges as NFO's.
                "BFO" -> contracts[sym]?.takeIf { it.exchange == "BFO" }?.let { Instrument(sym, "BFO", "OPTIDX", it.lotSize, 0.05, it.expiry, it.strike) }
                com.optionslab.engine.mcx.Mcx.EXCHANGE -> contracts[sym]?.takeIf { it.isMcx }?.let { mcxInstrument(it) }
                else -> null
            }
        },
    )

    /**
     * An MCX contract as the engine sees it: its tick (from the day's MCX list, else the table), and Zerodha's margin per
     * lot (its public list, else the table) for a future or a sold option. MIS and NRML block the same on MCX.
     */
    private fun mcxInstrument(c: Contract): Instrument {
        val listed = runCatching { McxMarket.find(c.symbol) }.getOrNull()
        val future = c.right == Right.IX
        val table = com.optionslab.engine.mcx.Mcx.commodity(c.underlying)
        val tick = listed?.tick ?: (if (future) table?.tick else table?.optionTick) ?: 0.05
        val margin = com.optionslab.engine.mcx.McxMargin.perLot(c.underlying, runCatching { McxMarket.marginFeed() }.getOrDefault(emptyMap()))
        return Instrument(c.symbol, c.exchange, if (future) "FUTCOM" else "OPTFUT", c.lotSize, tick, c.expiry, c.strike.takeIf { !future }, marginPerLot = margin)
    }

    val state: SandboxState get() = book().state
    /** An open order or an open position in the paper book (no price read): Battery, round 9. */
    fun watching(): Boolean = watched(book().state).isNotEmpty()
    val capital: BigDecimal get() = book().capital
    /** The contract a paper symbol stands for (underlying, expiry, type, lot), when it has been traded here. */
    fun contractOf(symbol: String): Contract? = book().contracts[symbol]
    fun engine(): Sandbox = book().let { engine(it.capital, it.contracts) }

    /**
     * The Live check's own paper book (18 Oct): the paper account's very engine - its fills, spreads, slippage, charges and
     * rules - knowing only [c], for a state the caller keeps in memory and never saves. Nothing it does reaches this book,
     * its trades, any arm's results, the P&L records or research: the self-test trade lives and ends inside the check.
     */
    internal fun scratchEngine(c: Contract, capital: BigDecimal = BigDecimal("1000000")): Sandbox = engine(capital, mapOf(c.symbol to c))

    // ---- prices -----------------------------------------------------------------

    /**
     * The contract's price. With a Zerodha session the live tick from Zerodha's stream (updated many times a second,
     * the same price the exchange shows); otherwise, or until the stream has a tick for it, the last minute's close
     * from Upstox's public feed, with the day's range so the stale-quote check works.
     */
    suspend fun quote(c: Contract): Quote? {
        streamQuote(c)?.let { return it.also { remember(c.symbol, it) } }
        // 12 Oct: the quick read (5 s connect, 10 s read, one retry): the full one's 3 tries x 45 s and its rate-limit waits
        // held a paper pass for minutes ("fast check: paper orders" 4 min 15 s), and every stop check behind it.
        val (read, readAt) = candleRead(c)
        val bars = read.filter { it.istDate == Market.today() }
        // 28 Oct (FINNIFTY's untraded options): no trade today, so no candles - Zerodha's live book, when it has one ([restBook]).
        if (bars.isEmpty()) return restBook(c)
        // (20 Nov 2026, review: stamped with when the read came in, never later - a read taken up by a later look is not "just read".)
        candleBars[c.symbol] = readAt to bars
        return Quote(bars.last().close, high = bars.maxOf { it.high }, low = bars.minOf { it.low }, open = bars.first().open,
            volume = bars.sumOf { it.volume }).also { remember(c.symbol, it); candleQuotes[c.symbol] = readAt to it }
    }

    /**
     * 20 Nov 2026 (audit, B5 item 1: "a paper price read can wait 10-160 s"): how long one candle read for a paper price may hold its
     * caller. The feed's quick read waits out a rate limit (429) for 10 s up to 160 s before its second try; every paper stop, lock and
     * exit check that prices a contract off the stream waited with it. Past this the caller stops waiting and the price is "none now",
     * as for a failed read: the check is made again on the next pass. (Review: the read itself is not cancelled - it goes on in the
     * background, one at a time for a contract, and the next look takes its answer when it came in the last [PRICE_READ_KEEP_MS], so a
     * slow but working feed still prices the contract a pass later.) Only [quote] (every price a check reads) is bounded so; [minutes]
     * is not - its callers wait on it apart ([gapHighSince] waits [GAP_READ_WAIT_MS] while its read goes on in the background).
     */
    const val PRICE_READ_WAIT_MS = 6_000L

    /** TEST SEAM: [PRICE_READ_WAIT_MS] in the app, always (its setter throws in a release build). */
    @Volatile internal var priceReadWaitMs: Long = PRICE_READ_WAIT_MS
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the price-read seam exists only in debug builds" }; field = v }

    /** How long a price read that finished after its caller stopped waiting is still taken by the next look. */
    const val PRICE_READ_KEEP_MS = 15_000L

    /**
     * One candle read for a paper price, run apart from its caller ([candleRead]), started at [startedAt]; [doneAt]: when it finished
     * (0: still running).
     */
    private class PriceRead(val job: kotlinx.coroutines.Deferred<List<Upstox.Bar>>, val startedAt: Long) { @Volatile var doneAt = 0L }

    /**
     * The candle read of each contract its caller stopped waiting for ([priceReadWaitMs]) and left running in the background, for the next
     * look to take up ([PriceRead]); memory only. A read whose caller got its answer, or was itself cancelled, is never kept here.
     */
    private val priceReads = java.util.concurrent.ConcurrentHashMap<String, PriceRead>()

    /** Every price read still running, whoever waits on it (memory only): a test's feed going away stops them all ([settlePriceReadsForTest]). */
    private val readsInFlight: MutableSet<PriceRead> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** How many reads left for the next look were cancelled because another caller's took their place (the tests read it). */
    internal val overwrittenReads = java.util.concurrent.atomic.AtomicInteger()

    /**
     * TEST SEAM (a fake feed going away or starting): the price reads left running are stopped and forgotten, waited [waitMs] at most
     * each, so none outlives its test's feed (it would reach the real host) or lends an earlier test's prices to the next.
     */
    internal fun settlePriceReadsForTest(waitMs: Long = 2_000L) {
        val all = (priceReads.values + readsInFlight).distinct()
        priceReads.clear()
        all.forEach { it.job.cancel() }
        kotlinx.coroutines.runBlocking { all.forEach { kotlinx.coroutines.withTimeoutOrNull(waitMs) { it.job.join() } } }
    }

    /**
     * [c]'s candles from the paper feed and when they came in, waited [priceReadWaitMs] at most; throws when the read failed or is still
     * running then. Each look reads afresh, except after a read its caller stopped waiting for: the next look takes that one up - its
     * answer when it came in the last [PRICE_READ_KEEP_MS] (with its own time), or a wait on it while it has run under twice the wait
     * (a stuck one is dropped and a fresh read starts). A caller cancelled for its own reasons cancels its read, as before.
     */
    private suspend fun candleRead(c: Contract): Pair<List<Upstox.Bar>, Long> {
        val wait = priceReadWaitMs
        val now = System.currentTimeMillis()
        val prev = priceReads.remove(c.symbol)
        val left = prev?.takeIf { pr ->
            if (pr.job.isCompleted) now - pr.doneAt <= PRICE_READ_KEEP_MS && !pr.job.isCancelled else now - pr.startedAt <= 2 * wait
        }
        if (prev != null && left == null) prev.job.cancel()
        val r = left ?: PriceRead(gapReads.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { Net.intradayQuick(c.feedKey) }, now).also { pr ->
            readsInFlight.add(pr)
            pr.job.invokeOnCompletion { pr.doneAt = System.currentTimeMillis(); readsInFlight.remove(pr) }
            pr.job.start()
        }
        // (join, not await, inside the timeout: a failed read's own error is thrown by the await below, never mistaken for the timeout.
        // The timeout ends this wait only; the read goes on and is left for the next look.)
        val done = try {
            kotlinx.coroutines.withTimeoutOrNull(wait) { r.job.join(); true }
        } catch (e: kotlinx.coroutines.CancellationException) { r.job.cancel(); throw e }
        if (done == null) {
            // (Review P6: a read another caller left for this contract meanwhile is cancelled, never left running unowned.)
            priceReads.put(c.symbol, r)?.takeIf { it !== r }?.let { it.job.cancel(); overwrittenReads.incrementAndGet() }
            throw java.io.IOException("the paper price read of ${c.symbol} took over $wait ms (it goes on; the next look takes it)")
        }
        return r.job.await() to (r.doneAt.takeIf { it > 0 } ?: System.currentTimeMillis())
    }

    /** 15 Nov: the start (epoch seconds) of the newest candle [quote] last read for [symbol]; null: none. Memory only ([com.optionslab.ira.PriceNow]). */
    fun lastCandleSec(symbol: String): Long? = candleBars[symbol]?.second?.lastOrNull()?.epochSecond

    /** Today's 1-minute candles per symbol as [quote] last read them (when, bars): the minutes' highs and lows. */
    private val candleBars = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Upstox.Bar>>>()

    private fun epochMs(t: java.time.LocalDateTime): Long = t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    /** The day's candles of [c] that STARTED after the minute holding [since] (IST), from the ones [quote] read. */
    private fun barsAfter(c: Contract, since: java.time.LocalDateTime): List<Upstox.Bar>? {
        val bars = candleBars[c.symbol]?.second ?: return null
        val from = since.withSecond(0).withNano(0).atZone(com.optionslab.engine.IST).toEpochSecond()
        return bars.filter { it.epochSecond > from }.sortedBy { it.epochSecond }
    }

    /**
     * The highest price [c] traded after [since] (IST), for the profit lock's best price (research/HUNT_H20.md F2): every
     * tick of Zerodha's stream while it streams this contract, else the highs of the 1-minute candles that started after
     * [since]'s minute (the paper feed's; read again when the copy [quote] kept is older than [maxAgeMs]). Null: no price.
     */
    suspend fun highSince(c: Contract, since: java.time.LocalDateTime, maxAgeMs: Long = SHARED_QUOTE_MS): Double? {
        runCatching { kiteToken(c) }.getOrNull()?.takeIf { KiteStream.tick(it) != null }
            ?.let { tok -> KiteStream.highSince(tok, epochMs(since))?.let { return it } }
        val kept = candleBars[c.symbol]
        if (kept == null || System.currentTimeMillis() - kept.first !in 0..maxAgeMs) runCatching { quote(c) }
        return barsAfter(c, since)?.filter { it.istDate == Market.today() }?.maxOfOrNull { it.high }
    }

    /**
     * 30 Oct (the switched-off shadow, [OffShadow]): the lowest price [c] traded after [since] (IST), as [highSince] reads the
     * highest - the stream's kept ticks while it streams [c], else the lows of the 1-minute candles that started after [since]'s
     * minute. Null: no price. Never on the tick or order path (the candle read is a network read).
     */
    suspend fun lowSince(c: Contract, since: java.time.LocalDateTime, maxAgeMs: Long = SHARED_QUOTE_MS): Double? {
        runCatching { kiteToken(c) }.getOrNull()?.takeIf { KiteStream.tick(it) != null }
            ?.let { tok -> KiteStream.lowSince(tok, epochMs(since))?.let { return it } }
        val kept = candleBars[c.symbol]
        if (kept == null || System.currentTimeMillis() - kept.first !in 0..maxAgeMs) runCatching { quote(c) }
        return barsAfter(c, since)?.filter { it.istDate == Market.today() }?.mapNotNull { b -> b.low.takeIf { it > 0 } }?.minOrNull()
    }

    /**
     * Does Zerodha's stream stream [c] now (a fresh tick)? Then [highSince] reads only the stream's kept prices (its last five
     * minutes); without it, [highSince] already reads the 1-minute candles since the entry. No network.
     */
    fun streams(c: Contract): Boolean = runCatching { kiteToken(c) }.getOrNull()?.let { KiteStream.tick(it) != null } == true

    /** How long a gap's candle read may hold the caller (the arms' check, under its lock): the read itself goes on in the background. */
    const val GAP_READ_WAIT_MS = 3_000L

    /** The background reads of [gapHighSince] (they outlive a caller that stopped waiting; each keeps its candles when done). */
    private val gapReads = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    /** Each symbol's gap read in flight (one at a time: a slow feed never piles reads up) and when it started (memory only). */
    private val gapInFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<Upstox.Bar>>>()
    private val gapReadFrom = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** How old candles a gap read brought may be and still answer the caller's next try (it tries once a minute). */
    const val GAP_KEPT_MS = 120_000L

    /**
     * 22 Oct (R100 audit C, review): the highest 1-minute candle high of [c] today that started after [since]'s minute, for a
     * lock's or trail's best price after a gap longer than the stream keeps ([com.optionslab.engine.orb.ProfitLock.needsCandles]).
     * [GapHigh.read]: the candles were read ([GapHigh.high] null: none after [since]); false: none came within [waitMs].
     *
     * The caller is never held longer than [waitMs]: the feed's own read (two tries, up to about 30 s) runs in the background,
     * one per symbol at a time (a try while it is still running waits on it again rather than starting another), and keeps its
     * candles when it ends; the caller's next try (once a minute) then answers from them, up to [GAP_KEPT_MS] old, without a
     * new read. Candles [quote] kept in the last [SHARED_QUOTE_MS] answer too.
     */
    suspend fun gapHighSince(c: Contract, since: java.time.LocalDateTime, waitMs: Long = GAP_READ_WAIT_MS): GapHigh {
        val now = System.currentTimeMillis()
        val kept = candleBars[c.symbol]
        val from = gapReadFrom[c.symbol]
        val usable = kept != null && (now - kept.first in 0..SHARED_QUOTE_MS ||
            (from != null && kept.first >= from && now - kept.first in 0..GAP_KEPT_MS))
        if (!usable) {
            val read = gapInFlight[c.symbol]?.takeIf { it.isActive } ?: run {
                gapReadFrom[c.symbol] = now
                gapReads.async { runCatching { minutes(c) }.getOrDefault(emptyList()) }.also { gapInFlight[c.symbol] = it }
            }
            val bars = kotlinx.coroutines.withTimeoutOrNull(waitMs) { read.await() }
            if (bars.isNullOrEmpty()) return GapHigh(false, null)
        }
        return GapHigh(true, barsAfter(c, since)?.filter { it.istDate == Market.today() }?.maxOfOrNull { it.high })
    }

    /** A gap read's answer ([gapHighSince]): whether the candles were read, and their highest high after the minute asked (null: none). */
    data class GapHigh(val read: Boolean, val high: Double?)

    /**
     * 20 Nov 2026 (deep review of audit B5): the highest high of [c]'s CLOSED 1-minute candles today that started after [since]'s minute
     * (the minute in progress left out: as the research's minute highs). Read as [gapHighSince] does (never held longer than [waitMs]);
     * null: none, or not read in time. Never on the order or tick path.
     */
    suspend fun closedHighSince(c: Contract, since: java.time.LocalDateTime, waitMs: Long = GAP_READ_WAIT_MS): Double? {
        // (Read fresh each time - its caller asks at most once a minute a trade, in the background - never another reader's older copy.)
        val bars = kotlinx.coroutines.withTimeoutOrNull(waitMs) { runCatching { minutes(c) }.getOrNull() } ?: return null
        return closedHigh(bars, since, runCatching { Market.now().toEpochSecond() }.getOrElse { System.currentTimeMillis() / 1000 }, Market.today())
    }

    /** [closedHighSince]'s pick from [bars]: today's ([day]) minutes that started after [since]'s minute and had closed by [nowSec]. Pure. */
    internal fun closedHigh(bars: List<Upstox.Bar>, since: java.time.LocalDateTime, nowSec: Long, day: java.time.LocalDate): Double? {
        val from = since.withSecond(0).withNano(0).atZone(com.optionslab.engine.IST).toEpochSecond()
        return bars.filter { it.istDate == day && it.epochSecond > from && it.epochSecond + 60 <= nowSec && it.high.isFinite() && it.high > 0 }
            .maxOfOrNull { it.high }
    }

    /**
     * Where a resting SELL SL-M at [trigger], resting since [since] (placed or last moved), filled between two looks, as an
     * exchange fills it: at the trigger, or at the price that was already under it (a minute's open, a stream tick) - F1/F6.
     * The stream's ticks when it streams the contract, else the 1-minute candles [quote] read this pass; null: not reached
     * (or nothing to tell by), and the regular pass fills it on the last price as before.
     */
    private fun restingFill(c: Contract, since: java.time.LocalDateTime, trigger: Double): Double? {
        val tok = runCatching { kiteToken(c) }.getOrNull()?.takeIf { KiteStream.tick(it) != null }
        if (tok != null) return KiteStream.sellStopFill(tok, epochMs(since), trigger)
        val bars = barsAfter(c, since)?.filter { it.istDate == Market.today() } ?: return null
        return bars.firstNotNullOfOrNull { com.optionslab.engine.orb.ProfitLock.sellStopFill(trigger, it.open, it.low) }
    }

    /** [restingFill] for a resting BUY stop (a short's; 22 Oct, arms audit): the stream's ticks, else the minutes' highs. */
    private fun restingBuyFill(c: Contract, since: java.time.LocalDateTime, trigger: Double): Double? {
        val tok = runCatching { kiteToken(c) }.getOrNull()?.takeIf { KiteStream.tick(it) != null }
        if (tok != null) return KiteStream.buyStopFill(tok, epochMs(since), trigger)
        val bars = barsAfter(c, since)?.filter { it.istDate == Market.today() } ?: return null
        return bars.firstNotNullOfOrNull { com.optionslab.engine.orb.ProfitLock.buyStopFill(trigger, it.open, it.high) }
    }

    /** The last price per symbol read from the day's candles (not the stream), and when: what [tick] may hand on. */
    private val candleQuotes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Quote>>()

    /** The candle prices [tick]'s last pass read itself (symbol -> when read, quote); replaced whole by each pass. */
    @Volatile private var tickCandles: Map<String, Pair<Long, Quote>> = emptyMap()

    /** How old a price [tick] read may be when [stopPrice] hands it on (the same pass's next step, never a later pass). */
    const val TICK_PRICE_MS = com.optionslab.ira.StopPrice.TICK_PRICE_MS

    /**
     * Battery (round 6): the price for a trailing stop in the same watch pass, right after [tick]. Exactly what
     * [lastPrice] gives - the stream's tick when there is one (no network), else the day's candles - except that
     * without a stream the candle price [tick] itself read for this symbol under [TICK_PRICE_MS] ago is handed on
     * instead of downloading the same candles again. The feed moves once a minute, so a read 2 s later is the same
     * close; it is also the very price the stop orders were just checked against. Older, or not read by [tick]: a
     * fresh read, as before.
     */
    suspend fun stopPrice(c: Contract): Double? {
        // Round 7: the choice is [com.optionslab.ira.StopPrice.source] (pure, tested: never a read 2 s old or from a past pass).
        val live = runCatching { streamQuote(c) }.getOrNull()
        val tickRead = tickCandles[c.symbol]
        return when (com.optionslab.ira.StopPrice.source(live != null, tickRead?.first, System.currentTimeMillis())) {
            com.optionslab.ira.StopPrice.Source.STREAM -> live?.let { q -> remember(c.symbol, q); q.ltp } ?: lastPrice(c)
            com.optionslab.ira.StopPrice.Source.TICK_READ -> tickRead?.second?.ltp ?: lastPrice(c)
            com.optionslab.ira.StopPrice.Source.FRESH -> lastPrice(c)
        }
    }

    /**
     * [symbol]'s price from memory only (the trade manager's look, 10 Oct): the stream's fresh tick, else a price read in the
     * last [maxAgeMs]; null otherwise. No network, vault or file read (review R1: the contract's Zerodha token only from the
     * tokens kept - [resolveToken] looks a new one up off the look; a book not loaded yet answers from the reads kept).
     */
    fun memPrice(symbol: String, maxAgeMs: Long = 60_000L): Double? {
        cache?.contracts?.get(symbol)?.let { c -> runCatching { streamQuote(c, kept = true) }.getOrNull()?.ltp?.takeIf { it > 0 }?.let { return it } }
        return lastQuotes[symbol]?.takeIf { quoteClock() - it.first in 0..maxAgeMs }?.second?.ltp?.takeIf { it > 0 }
    }

    /**
     * [symbol]'s Zerodha token looked up now (the instruments file, the session), off any hot path (the trade manager's
     * attach): [memPrice] then reads it from memory. Never throws.
     */
    fun resolveToken(symbol: String) {
        runCatching { cache?.contracts?.get(symbol)?.let { kiteToken(it) } }
    }

    /** The last price read for each symbol and when (the screen re-prices every few seconds). */
    private val lastQuotes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Quote>>()
    private fun remember(symbol: String, q: Quote) { lastQuotes[symbol] = quoteClock() to q; noPriceWhy.remove(symbol) }
    /** The kept prices' ages run on the market clock ([Market.now]), so a price is never reused across a session or a day. */
    private fun quoteClock(): Long = Market.now().toInstant().toEpochMilli()

    /**
     * A price for a close or a cancel the owner just slid: the stream's tick, else one read in the last
     * [maxAgeMs], else a fresh read. The slide acts at once instead of waiting on the day's candles.
     */
    private suspend fun quickQuote(symbol: String, maxAgeMs: Long = 5_000): Quote? {
        val c = book().contracts[symbol] ?: return null
        streamQuote(c)?.let { return it }
        lastQuotes[symbol]?.let { (at, q) -> if (quoteClock() - at <= maxAgeMs) return q }
        return runCatching { quote(c) }.getOrNull()
    }

    /** Zerodha's instrument token per paper symbol (0 = not listed there), looked up once. */
    private val kiteTokens = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * TEST SEAM (JVM tests only): [symbol]'s Zerodha token as the instrument list would give it (null: forgotten), so a test can
     * stream a paper contract without a Zerodha session. Its use throws in a release build.
     */
    internal fun tokenForTest(symbol: String, token: Long?) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the token seam exists only in debug builds" }
        if (token == null) kiteTokens.remove(symbol) else kiteTokens[symbol] = token
    }

    /** TEST SEAM (JVM tests only): forget the 1-minute candles [quote] kept (and [restBook]'s read times), as a new process starts without them. */
    internal fun forgetCandlesForTest() {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the candle seam exists only in debug builds" }
        candleBars.clear(); gapInFlight.clear(); gapReadFrom.clear(); restBookAt.clear()
    }

    private fun kiteToken(c: Contract): Long? {
        kiteTokens[c.symbol]?.let { return it.takeIf { t -> t > 0 } }
        if (!Broker.loggedIn) return null
        if (c.isMcx) {
            // MCX: from the day's MCX list (none yet: asked again next time, not remembered as unlisted).
            val m = McxMarket.find(c.symbol) ?: return null
            return m.token.takeIf { it > 0 }?.also { kiteTokens[c.symbol] = it }
        }
        // 15 Oct: a SENSEX option (BFO, the Hero ₹1,000 ticket's paper side) from Zerodha's BFO list - read in the background,
        // never here (memory only); not read yet: asked again next time, not remembered as unlisted.
        if (c.exchange == "BFO") {
            val bfo = Broker.cachedBfoOptions() ?: run { Broker.loadBfoOptionsInBackground(); return null }
            val t = Broker.find(bfo, c.underlying, c.expiry, c.strike, c.right)?.token ?: 0L
            kiteTokens[c.symbol] = t
            return t.takeIf { it > 0 }
        }
        val list = Broker.cachedInstruments() ?: return null
        val t = Broker.find(list, c.underlying, c.expiry, c.strike, c.right)?.token ?: 0L
        // G1-2: "not listed" (0) is remembered only from today's list; from an older one the contract may simply be new, so it
        // is looked up again once the day's list has been downloaded.
        if (t > 0 || Broker.instrumentsAreToday()) kiteTokens[c.symbol] = t
        return t.takeIf { it > 0 }
    }

    /**
     * [c]'s book as the stream last had it, for the fill-quality record (Liquidity, 12 Oct): bid, ask, the quantities at the
     * best bid and offer, the five levels' sums (full mode) and the quote's age in ms, with its last price. Memory only (the
     * tokens already looked up and the stream's last tick: never the instruments file, the vault or the network) - safe on
     * the order path. Null when the stream has never had the contract.
     */
    data class BookNow(val last: Double?, val bid: Double?, val ask: Double?, val bidQty: Long?, val askQty: Long?,
                       val bid5: Long?, val ask5: Long?, val ageMs: Long,
                       /** 15 Nov: when [last] traded (epoch seconds; null: the packet carries none) - [com.optionslab.ira.PriceNow]. */
                       val lastTradeSec: Long? = null)

    fun bookNow(c: Contract, nowMs: Long = System.currentTimeMillis()): BookNow? = runCatching {
        val token = kiteTokens[c.symbol]?.takeIf { it > 0 } ?: return null
        val seen = KiteStream.seen(token) ?: return null
        val t = seen.tick
        BookNow(t.last.takeIf { it > 0 }, t.bid?.takeIf { it > 0 }, t.ask?.takeIf { it > 0 }, t.bidQty, t.askQty,
            t.depth?.bidQty, t.depth?.askQty, (nowMs - seen.at).coerceAtLeast(0L), t.lastTradeTime?.takeIf { it > 0 })
    }.getOrNull()

    /**
     * 15 Nov ([com.optionslab.ira.PriceNow]): what [c], held since [since], is worth now for judging its stop, lock or target - from
     * the stream's last packet alone (at most [maxAgeMs] old): its last price when that traded in the entry minute or later (and is
     * not far outside its book), else its book's bid when the book is sane; null: no packet, or neither. Memory only (the tokens
     * already looked up; with [lookup] also the day's list as the arms' own stream reads find it): never the vault or the network.
     */
    fun heldPriceNow(c: Contract, since: java.time.LocalDateTime, maxAgeMs: Long = Long.MAX_VALUE, nowMs: Long = System.currentTimeMillis(),
                     lookup: Boolean = false): Double? = runCatching {
        val t = packetOf(c, maxAgeMs, nowMs, lookup) ?: return null
        val sinceSec = since.withSecond(0).withNano(0).atZone(com.optionslab.engine.IST).toEpochSecond()
        val tt = t.lastTradeTime?.takeIf { it > 0 }
        val pn = com.optionslab.ira.PriceNow
        if (pn.lastCounts(t.last, tt, sinceSec) && !pn.farFromBook(t.last, tt, t.bid, t.ask)) t.last else pn.bookBid(t.bid, t.ask)
    }.getOrNull()

    /** 20 Nov 2026: the oldest stream price an arm's exit decision takes ([exitPriceNow]); older, a fresh quote is read instead. */
    const val EXIT_PRICE_MAX_AGE_MS = 5_000L

    /** 20 Nov 2026: how long [exitPriceNow]'s fresh read may take before the exit is decided without it (the minute lane goes on). */
    const val EXIT_READ_MS = 3_000L

    /** TEST ONLY: [exitPriceNow]'s fresh read in place of Zerodha's quote and the paper feed (null: as in the app). */
    @Volatile internal var exitReadForTest: (suspend (Contract) -> Double?)? = null

    /**
     * 20 Nov 2026 (audit: "S5, Flow and Ntfy use old stream prices as the current price when the stream is down"): [c]'s price for
     * an arm's exit decision (its stop, profit lock or target), held since [since]:
     *  - the stream's price when its packet is at most [EXIT_PRICE_MAX_AGE_MS] old ([heldPriceNow]'s rules: a trade since the entry,
     *    or a sane book's bid) - nothing read, as before;
     *  - else a fresh read, for at most [EXIT_READ_MS]: Zerodha's quote with a Zerodha session ([Broker.stopQuotes] in the entries'
     *    lane, below every live stop's read; its book's bid when its last price lies far outside it), else the paper feed's ([quote]).
     *    B5-D: none at all while a live stop's read waits in Zerodha's quote queue, nor once the paper arms' reads have had their
     *    share of the window ([ReadBudget]) - so they never hold the live copies' look behind them ([paperExitRead]);
     *  - else, so an exit is never skipped for want of a price, the stream's last price however old, as before this change.
     * Null only when there is no price at all (the arm then decides its time exits and square-off as before, and puts its stop
     * back). Never on the stream's thread: a background lane's look (the minute lane, the 15-second stop check).
     */
    suspend fun exitPriceNow(c: Contract, since: java.time.LocalDateTime, readMs: Long = EXIT_READ_MS): Double? {
        runCatching { heldPriceNow(c, since, EXIT_PRICE_MAX_AGE_MS, lookup = true) }.getOrNull()?.let { return it }
        // Review: the read runs on its own background job and is waited for [readMs] at most - the paper feed's read is a blocking
        // HTTP call that a timeout cannot stop, so the arm never waits on it past the bound (a late answer is simply not used).
        // B5-D: ONE bounded read from the paper reads' budget ([paperExitRead]) - Zerodha's quote, else the paper feed's (and its book
        // read) - or none at all, straight to the stream's last price, once the budget is spent or a live stop's read waits.
        val read = paperExitRead(readMs) { freshExitRead(c, since) }
        if (read != null && read.isFinite() && read > 0) return read
        runCatching { heldPriceNow(c, since, Long.MAX_VALUE, lookup = true) }.getOrNull()?.let { return it }
        // Merge review: no stream packet at all (a paper-only phone): the paper feed as before this change (bounded, its own job), so a
        // stop is never left unchecked for want of a price; it never touches Zerodha's quote queue.
        return boundedRead(readMs) { kotlinx.coroutines.withContext(Broker.Lane(com.optionslab.ira.RateGate.Priority.ENTRY)) { feedExitRead(c, since) } }
            ?.takeIf { it.isFinite() && it > 0 }
    }

    /** B5-D: the paper arms' fresh exit reads together: at most 4 s in any 15 s ([com.optionslab.ira.ReadBudget]). */
    @Volatile private var exitBudget = com.optionslab.ira.ReadBudget()

    /** TEST ONLY: the paper arms' read budget full again. */
    internal fun resetExitBudgetForTest() { exitBudget = com.optionslab.ira.ReadBudget() }

    /**
     * B5-D (the paper exit reads ran one after another ahead of the live copies' stop look): a paper arm's fresh exit read [block],
     * [readMs] at most - or none (null: the caller decides on the stream's last price, never skipping the exit) while a read of a lane
     * above the entries' (a live stop's) waits in Zerodha's quote queue, or once the paper arms' reads have spent their share of the
     * window ([exitBudget]). For S5, Flow and ntfy's exits.
     */
    internal suspend fun <T> paperExitRead(readMs: Long, block: suspend () -> T?): T? {
        if (runCatching { Broker.quoteWaitingAbove(com.optionslab.ira.RateGate.Priority.ENTRY) }.getOrDefault(false)) return null
        val budget = exitBudget
        val got = budget.reserve(readMs) ?: return null
        val t0 = System.nanoTime()
        try { return boundedRead(got.ms) { kotlinx.coroutines.withContext(Broker.Lane(com.optionslab.ira.RateGate.Priority.ENTRY)) { block() } } }
        finally { budget.settle(got, (System.nanoTime() - t0) / 1_000_000L) }
    }

    /** [exitPriceNow]'s fresh reads (their own threads: a blocking read never holds an arm). */
    private val exitReads = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * 20 Nov 2026 (review): [block] run on its own background job and waited for [ms] at most (null past it, or on a failure). A
     * blocking read inside it can outlive the wait, but never holds its caller. For an arm's exit reads only.
     */
    suspend fun <T> boundedRead(ms: Long, block: suspend () -> T?): T? {
        val job = exitReads.async { try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } }
        return try { kotlinx.coroutines.withTimeoutOrNull(ms) { job.await() } } finally { if (!job.isCompleted) job.cancel() }
    }

    /** Zerodha's quote of [c] in the entries' lane (below every live stop's read); null: none. */
    private suspend fun zerodhaExitRead(c: Contract): Double? =
        zerodhaKey(c)?.let { k -> Broker.stopQuotes(listOf(k), com.optionslab.ira.RateGate.Priority.ENTRY)[k] }?.let { q ->
            q.last.takeIf { it.isFinite() && it > 0 } ?: com.optionslab.ira.PriceNow.bookBid(q.bid, q.ask)
        }

    /** Zerodha's quote of [c] with a session ([zerodhaExitRead]), else the paper feed's ([quote], its book read included), judged as [priceNowFor] judges it. */
    private suspend fun freshExitRead(c: Contract, since: java.time.LocalDateTime): Double? {
        exitReadForTest?.let { return runCatching { it(c) }.getOrNull() }
        if (Broker.loggedIn && !c.isMcx) {
            val z = try { zerodhaExitRead(c) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (z != null) return z
        }
        return feedExitRead(c, since)
    }

    /** The paper feed's price of [c] ([quote], its book read included), judged as [priceNowFor] judges it; null: none. */
    private suspend fun feedExitRead(c: Contract, since: java.time.LocalDateTime): Double? {
        exitReadForTest?.let { return runCatching { it(c) }.getOrNull() }
        val q = (try { quote(c) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }) ?: return null
        val sinceSec = since.withSecond(0).withNano(0).atZone(com.optionslab.engine.IST).toEpochSecond()
        return com.optionslab.ira.PriceNow.ofQuote(q.ltp, q.noLast, q.bid, q.ask, null, lastCandleSec(c.symbol), sinceSec)
    }

    /** The stream's last packet of [c] when at most [maxAgeMs] old (memory only; keeps the contract followed); null: none. */
    private fun packetOf(c: Contract, maxAgeMs: Long, nowMs: Long, lookup: Boolean = false): com.optionslab.engine.KiteTicks.Tick? {
        val token = kiteTokens[c.symbol]?.takeIf { it > 0 }
            ?: (if (lookup && c.exchange == "NFO") Broker.cachedInstruments()?.let { Broker.find(it, c.underlying, c.expiry, c.strike, c.right)?.token } else null)?.takeIf { it > 0 }
            ?: return null
        runCatching { KiteStream.touch(listOf(token)) }
        val seen = KiteStream.seen(token) ?: return null
        return seen.tick.takeIf { nowMs - seen.at in 0..maxAgeMs }
    }

    /**
     * 15 Nov: [heldPriceNow] when the stream has a packet of [c] (nothing is read then), else a quote read as before and judged the
     * same way ([com.optionslab.ira.PriceNow.ofQuote]: a sane book's bid, or a candle close from the entry minute on); null: no price now.
     */
    suspend fun priceNowFor(c: Contract, since: java.time.LocalDateTime, maxAgeMs: Long = Long.MAX_VALUE): Double? {
        if (runCatching { packetOf(c, maxAgeMs, System.currentTimeMillis()) }.getOrNull() != null) return heldPriceNow(c, since, maxAgeMs)
        val q = runCatching { quote(c) }.getOrNull() ?: return null
        val sinceSec = since.withSecond(0).withNano(0).atZone(com.optionslab.engine.IST).toEpochSecond()
        return com.optionslab.ira.PriceNow.ofQuote(q.ltp, q.noLast, q.bid, q.ask, null, lastCandleSec(c.symbol), sinceSec)
    }

    /**
     * 15 Nov: whether a resting CLOSING stop of [c], resting since [since], must wait this pass because the price [q] it would be
     * judged on is not a price now: a stray bid (no last price and no real two-sided book, [com.optionslab.ira.PriceNow.bookBid]), or the stream's last price repeated from a
     * trade before the stop rested. An exchange stop triggers on trades only. Memory only.
     */
    private fun stopMustWait(c: Contract, q: Quote, since: java.time.LocalDateTime, sell: Boolean, trigger: Double?): Boolean = runCatching {
        if (c.isMcx) return false
        // Review: never held when the book's far side is itself through the trigger (a sale: even the ask is at or under it).
        if (trigger != null && (if (sell) q.ask > 0 && q.ask <= trigger else q.bid > 0 && q.bid >= trigger)) return false
        if (q.noLast) return com.optionslab.ira.PriceNow.bookBid(q.bid, q.ask) == null
        val t = packetOf(c, 5_000L, System.currentTimeMillis()) ?: return false
        if (kotlin.math.abs(t.last - q.ltp) > 1e-9) return false          // not the stream's last price (a candle, Zerodha's quote)
        val tt = t.lastTradeTime?.takeIf { it > 0 }
        val sinceSec = since.withSecond(0).withNano(0).atZone(com.optionslab.engine.IST).toEpochSecond()
        !com.optionslab.ira.PriceNow.lastCounts(t.last, tt, sinceSec) || com.optionslab.ira.PriceNow.farFromBook(t.last, tt, t.bid, t.ask)
    }.getOrDefault(false)

    /** A paper fill's quote may be this old at most when a newer one can be had ([place], 16 Oct). */
    const val FILL_FRESH_MS = 2_000L

    private fun quoteOf(t: com.optionslab.engine.KiteTicks.Tick): Quote = Quote(t.last, bid = t.bid ?: 0.0, ask = t.ask ?: 0.0, high = t.high,
        low = t.low, open = t.open, prevClose = t.close, volume = t.volume, bidQty = t.bidQty ?: 0L, askQty = t.askQty ?: 0L)

    /** Zerodha's quote for [c] read now (never the stream's memory), waited [FILL_FRESH_MS] at most (null: none in time, or not listed). */
    private suspend fun freshRestQuote(c: Contract): Quote? {
        val key = zerodhaKey(c) ?: return null
        val q = Broker.within(FILL_FRESH_MS) { Broker.quotesFresh(listOf(key))[key] } ?: return null
        // 28 Oct: no last price: its live book ([bookQuote]).
        if (q.last <= 0) return bookQuote(c, q.bid, q.ask)
        return Quote(q.last, bid = q.bid ?: 0.0, ask = q.ask ?: 0.0, open = q.open, volume = q.volume)
    }

    /** Zerodha's "EXCHANGE:TRADINGSYMBOL" for an NSE or BSE option (the day's lists in memory only; null: not known, or MCX). */
    private fun zerodhaKey(c: Contract): String? {
        kiteKeys[c.symbol]?.let { return it }
        val list = when (c.exchange) { "NFO" -> Broker.cachedInstruments(); "BFO" -> Broker.cachedBfoOptions(); else -> null } ?: return null
        return Broker.find(list, c.underlying, c.expiry, c.strike, c.right)?.let { "${c.exchange}:${it.tradingSymbol}" }?.also { kiteKeys[c.symbol] = it }
    }
    private val kiteKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The live tick from Zerodha's stream, if there is a fresh one; asking also keeps the contract subscribed. [kept]: the
     * token only from those already looked up (memory only; never the instruments file or the session).
     */
    private fun streamQuote(c: Contract, kept: Boolean = false): Quote? {
        val token = (if (kept) kiteTokens[c.symbol]?.takeIf { it > 0 } else runCatching { kiteToken(c) }.getOrNull()) ?: return null
        KiteStream.touch(listOf(token))
        val t = KiteStream.tick(token) ?: return null
        // 28 Oct: no last price (an option that has not traded): its live book, when it has a bid ([bookQuote]).
        if (t.last <= 0) return bookQuote(c, t.bid, t.ask, bidQty = t.bidQty ?: 0L, askQty = t.askQty ?: 0L)
        return Quote(t.last, bid = t.bid ?: 0.0, ask = t.ask ?: 0.0, high = t.high, low = t.low, open = t.open,
            prevClose = t.close, volume = t.volume, bidQty = t.bidQty ?: 0L, askQty = t.askQty ?: 0L)
    }

    // ---- 28 Oct (Boss: "Yes fix finnifty"): an option with a live book but no last price ------------------------------------

    /**
     * Why each symbol's latest price read found no usable price (when, the reason), for the arms' "no fresh price" line. Memory
     * only; cleared by the next price read that works ([remember]).
     */
    private val noPriceWhy = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /** Why [symbol]'s latest price read found no usable price, if that read was in the last minute; null otherwise. Memory only. */
    fun noPriceWhy(symbol: String): String? = noPriceWhy[symbol]?.takeIf { quoteClock() - it.first in 0..60_000L }?.second

    private fun noPrice(symbol: String, why: String) { noPriceWhy[symbol] = quoteClock() to why }

    /**
     * [c]'s live book as its quote when it has NO last price (FINNIFTY23NOV2624250PE on 28 Oct: Zerodha reports 0 for an option
     * that has not traded, and the candle feed has nothing for it). The quote's ltp is the bid - what a held long is worth, so its
     * stop, target and marks read the bid - and it is flagged [Quote.noLast], so the engine judges a buy on the ask (and marks a
     * short at the ask). Null when there is no bid (nobody to sell to: no price at all). An entry is allowed only on a sane book
     * ([com.optionslab.ira.BookPrice.judge], checked in [place]); MCX is left as before. Memory only.
     */
    private fun bookQuote(c: Contract, bid: Double?, ask: Double?, bidQty: Long = 0L, askQty: Long = 0L): Quote? {
        if (c.isMcx) return null
        val v = com.optionslab.ira.BookPrice.judge(bid, ask)
        v.why?.let { noPrice(c.symbol, it) }
        val b = v.bid ?: return null
        return Quote(b, bid = b, ask = ask?.takeIf { it.isFinite() && it > 0 } ?: 0.0, bidQty = bidQty, askQty = askQty, noLast = true)
    }

    /**
     * 28 Oct: the price an automatic entry is decided on from [q]: its last price, or with no last price ([Quote.noLast]) the ask a
     * buyer pays - only when the book is sane and Boss's switch is on ([BookPriceHost]); null otherwise ([noPriceWhy] says why).
     */
    fun entryPrice(c: Contract, q: Quote?): Double? {
        if (q == null) return null
        if (!q.noLast) return q.ltp.takeIf { it > 0 }
        bookEntryRefusal(c, q)?.let { noPrice(c.symbol, it); return null }
        return q.ask.takeIf { it > 0 }
    }

    /** Whether [c]'s entry would be priced on the book alone: [known] has no last price, or the stream's tick has none. Memory only. */
    private fun bookOnlyNow(c: Contract, known: Quote?): Boolean {
        if (known?.noLast == true) return true
        val tok = kiteTokens[c.symbol]?.takeIf { it > 0 } ?: return false
        return KiteStream.tick(tok)?.let { it.last <= 0 } == true
    }

    /** Why an entry may not fill on [q], a quote with no last price: the book's own reason, or Boss's switch off; null: it may. */
    private fun bookEntryRefusal(c: Contract, q: Quote): String? {
        com.optionslab.ira.BookPrice.judge(q.bid, q.ask).why?.let { return it }
        if (!BookPriceHost.paperOn()) return "no last price, and paper entries on the live book are switched off"
        return null
    }

    /**
     * Zerodha's quote for [c] when the candle feed has nothing today (logged in only; one read, [FILL_FRESH_MS] at most): with no
     * trade today its last price is not today's, so the quote is its live book ([bookQuote]). Null: no session, not listed, no
     * answer in time, or no bid.
     */
    private suspend fun restBook(c: Contract): Quote? {
        if (c.isMcx) return null
        // 28 Oct, review: only while the market is open (no pre-open book), and one Zerodha read per symbol per [REST_BOOK_MS] at
        // most: a held untraded option priced every pass never piles quote calls up (Zerodha's rate limit).
        if (!runCatching { Market.isOpen() }.getOrDefault(false)) { noPrice(c.symbol, "no trade today and the market is closed"); return null }
        val nowMs = quoteClock()
        val last = restBookAt[c.symbol]
        if (last != null && nowMs - last in 0 until REST_BOOK_MS) { noPrice(c.symbol, "no trade today; its book was read moments ago"); return null }
        restBookAt[c.symbol] = nowMs
        if (!Broker.loggedIn) { noPrice(c.symbol, "no trade today and no Zerodha session to read its book"); return null }
        val key = zerodhaKey(c) ?: run { noPrice(c.symbol, "no trade today and not found in Zerodha's list"); return null }
        // B5-D: a held position's read (its stop or exit is priced on it) goes in the safety lane; an entry's in the entries'.
        // (A paper arm's own exit read keeps the entries' lane it runs in: [paperExitRead].)
        val asked = kotlinx.coroutines.currentCoroutineContext()[Broker.Lane]?.priority
        val held = asked == null && runCatching { state.positions.any { it.symbol == c.symbol && it.quantity != 0 } }.getOrDefault(false)
        val r = Broker.within(FILL_FRESH_MS) {
            Broker.quotes(listOf(key), lane = asked ?: if (held) com.optionslab.ira.RateGate.Priority.SAFETY else com.optionslab.ira.RateGate.Priority.ENTRY)[key]
        }
            ?: run { noPrice(c.symbol, "no trade today and Zerodha's quote did not answer"); return null }
        return bookQuote(c, r.bid, r.ask)?.also { remember(c.symbol, it) }
    }

    /** When [restBook] last read each symbol from Zerodha (market clock, memory only). */
    private val restBookAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val REST_BOOK_MS = 5_000L

    // ---- 20 Nov 2026 (audit, B5 item 4): what a paper trade closed elsewhere is booked at ------------------------------------------

    /** [soldElsewhere]'s answer, with the names of a paper fill ([com.optionslab.engine.sandbox.Trade]) so the arms read it the same. */
    data class SoldElsewhere(val price: BigDecimal, val timestamp: java.time.LocalDateTime, val charges: BigDecimal,
                             val how: com.optionslab.ira.PaperElsewhere.How)

    /** The closing fills already booked to trades closed elsewhere (order id -> each trade's owner key -> its quantity). Memory only. */
    private val elsewhereClaims = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Int>>()

    /** How old a last price may be when a trade closed elsewhere with no fill is booked at it ([soldElsewhere]). */
    private const val ELSEWHERE_LAST_MS = 5 * 60_000L

    /**
     * What a paper trade ([owner]: its own key, the same on every look) of [qty] [symbol], entered at [since] in [product] (null: any),
     * is booked at when it left the paper book without its arm's own sale ([com.optionslab.ira.PaperElsewhere]): the last [closing]
     * fill of its product since then that other trades have not booked in full, else its expiry settlement, else its last price of the
     * last 5 minutes; null when none is known (the arm keeps its entry price, as before). The fill taken is kept for [owner] with [qty],
     * so other arms' trades on the same option never book more of it than it sold. Record only; memory and the paper book in hand (no
     * network). Never throws.
     */
    fun soldElsewhere(owner: String, symbol: String, since: java.time.LocalDateTime, product: String? = null, closing: String = "SELL",
                      qty: Int = 0, now: java.time.LocalDateTime = Market.now().toLocalDateTime().withNano(0)): SoldElsewhere? = runCatching {
        val st = state
        val sales = st.trades.filter { it.symbol == symbol && it.action.equals(closing, ignoreCase = true) }
            // (Review: a fill is named by its order id AND time - a paper reset or a new book numbers its orders afresh, and a claim kept
            // in memory for an earlier book's order must never block this one's.)
            .map { com.optionslab.ira.PaperElsewhere.Sale("${it.orderId}@${it.timestamp}", it.product, it.price.toDouble(), it.timestamp, it.charges.toDouble(), it.quantity) }
        val c = book().contracts[symbol]
        // The paper book settles an expired NSE/BSE contract after its session (no fill): its row then holds the settlement price.
        val expired = c != null && !c.isMcx && (now.toLocalDate().isAfter(c.expiry) ||
            (now.toLocalDate() == c.expiry && !now.toLocalTime().isBefore(java.time.LocalTime.of(15, 30))))
        // (Review: the row of the trade's own product, so another product's row closed later that day never lends its price.)
        val row = st.positions.filter { it.symbol == symbol && it.quantity == 0 && (product == null || it.product.equals(product, ignoreCase = true)) }
            .maxByOrNull { it.updatedAt }
        val byOthers = { id: String -> elsewhereClaims[id]?.entries?.filter { it.key != owner }?.sumOf { it.value } ?: 0 }
        val x = com.optionslab.ira.PaperElsewhere.pick(sales, product, since, byOthers,
            expired, row?.ltp?.toDouble(), c?.expiry?.atTime(15, 30), memPrice(symbol, ELSEWHERE_LAST_MS), now) ?: return@runCatching null
        x.orderId?.let { id -> claimElsewhere(id, owner, qty.coerceAtLeast(1)) }
        // (Review: a fill shared by several trades lends each its share of the order's charges - all its fill rows - never all of them.)
        val rows = x.orderId?.let { id -> sales.filter { it.orderId == id } }.orEmpty()
        val soldQty = rows.sumOf { it.quantity.toLong() }
        val charges = if (soldQty > 0 && qty in 1 until soldQty) rows.sumOf { it.charges } * qty / soldQty else x.charges
        SoldElsewhere(BigDecimal.valueOf(x.price), x.at, BigDecimal.valueOf(charges), x.how)
    }.getOrNull()

    private fun claimElsewhere(orderId: String, owner: String, qty: Int) {
        if (elsewhereClaims.size > 2_000) elsewhereClaims.clear()
        elsewhereClaims.getOrPut(orderId) { java.util.concurrent.ConcurrentHashMap() }[owner] = qty
    }

    /** TEST SEAM: forget the fills booked to trades closed elsewhere (a new process starts without them). */
    internal fun forgetElsewhereForTest() = elsewhereClaims.clear()

    /** When each symbol's paper entry last filled on the book alone (memory only): [ArmLive] never mirrors such a fill live. */
    private val bookFills = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Whether [symbol]'s paper entry filled on the book alone (no last price) in the last [withinMs]. Memory only. */
    fun filledOnBook(symbol: String, withinMs: Long = 120_000L): Boolean =
        bookFills[symbol]?.let { quoteClock() - it in 0..withinMs } == true

    /** TEST SEAM (JVM tests only): [symbol]'s paper entry as if it had just filled on the book alone. Throws in a release build. */
    internal fun bookFillForTest(symbol: String?) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the book-fill seam exists only in debug builds" }
        if (symbol == null) bookFills.clear() else bookFills[symbol] = quoteClock()
    }

    /**
     * The event-driven checks ([FastPath]): the Zerodha tokens of every symbol the paper book holds or has a working order
     * on (those with none listed are left out), and whether each of those symbols has a fresh stream price ([covered]:
     * then a paper pass reads no candle feed; an empty book is covered). No network, no vault write.
     */
    data class StreamCover(val tokens: Set<Long>, val covered: Boolean, val any: Boolean)

    fun streamCover(): StreamCover {
        // G1-3: memory only, never [book] - that waits on this object's monitor, which [save] holds through a vault write
        // (and tick/snapshot through whole engine passes); the fast-path consumer calling this also dispatches the LIVE
        // exit lane, which must never wait behind a paper write. Not loaded yet: not covered (the watch pass loads it).
        val b = cache ?: run { loadSoon(); return StreamCover(emptySet(), covered = false, any = false) }
        val syms = watched(b.state)
        if (syms.isEmpty()) { follow(emptySet(), wait = false); return StreamCover(emptySet(), covered = true, any = false) }
        var covered = true
        val tokens = HashSet<Long>()
        for (s in syms) {
            val tok = b.contracts[s]?.let { runCatching { kiteToken(it) }.getOrNull() }
            if (tok == null) { covered = false } else { tokens += tok; if (KiteStream.tick(tok) == null) covered = false }
        }
        // 21 Oct, enhancement round 3 (idea 3): the tokens looked up here (after a restart, the first time) followed at once.
        follow(tokens, wait = false)
        return StreamCover(tokens, covered, any = true)
    }

    /**
     * 21 Oct, enhancement round 3 (idea 3): the stream's owner for the paper book's open positions and working orders. Until now
     * they stayed on Zerodha's stream only by being priced (each price read "touched" them for three minutes), so a pass that
     * paused for longer let the stream drop them, and the stream's cap could leave them out. Now they are followed for as long as
     * they are open, first among the stream's instruments with the live positions (CLAUDE.md: open positions come first), and
     * let go when they close. Memory only, never the instruments file or the vault: [save] is on the order path.
     */
    const val STREAM_OWNER = "paper-positions"
    private var followedNow: Set<Long> = emptySet()
    /** Its own small lock (never this object's: [streamCover] must never wait on a paper write); held only for the stream's want. */
    private val followLock = java.util.concurrent.locks.ReentrantLock()

    /**
     * [wait] false (review: [streamCover], on the fast path's exit lane): only when the lock is free at once; taken, a save is
     * following the newest book right now, and the next look follows again (it heals within a look).
     */
    private fun follow(tokens: Set<Long>, wait: Boolean = true) {
        if (wait) followLock.lock() else if (!followLock.tryLock()) return
        try {
            if (tokens == followedNow) return
            followedNow = tokens
            runCatching { KiteStream.want(STREAM_OWNER, tokens) }
        } finally { followLock.unlock() }
    }

    /** [b]'s open positions and working orders, by the tokens already looked up (memory only; the rest come with [streamCover]). */
    private fun followKnown(b: Book) {
        runCatching {
            val syms = watched(b.state)
            if (syms.isEmpty()) { follow(emptySet()); return }
            // Those not looked up yet come with [streamCover], which looks them up and follows the whole set.
            follow(syms.mapNotNull { s -> kiteTokens[s]?.takeIf { it > 0 } }.toSet())
        }
    }

    private val loading = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True once the paper book is in memory ([streamCover] answers from memory only), or when there is no paper account to load. */
    internal fun loaded(): Boolean = cache != null || !::file.isInitialized

    /**
     * G1-3 follow-up: the book read once on a background thread when [streamCover] finds it not in memory yet, so a fresh
     * process's stream exits (the LIVE lane waits for the paper cover) are not held until the next watch pass loads it.
     */
    private fun loadSoon() {
        if (!::file.isInitialized) return
        if (loadInlineForTest) { runCatching { book() }; return }
        if (!loading.compareAndSet(false, true)) return
        Thread({ try { runCatching { book() } } finally { loading.set(false) } }, "paper-load").apply { isDaemon = true }.start()
    }

    /** The contract's latest price from the paper feed, or null when there is none today. */
    suspend fun lastPrice(c: Contract): Double? = runCatching { quote(c) }.getOrNull()?.ltp

    /**
     * Every symbol's quote, fetched in parallel (one round trip for the whole book, not one per position). [reuseMs] > 0
     * (words and cards only, [snapshot]): a price this process read at most that long ago is used instead of a new read.
     */
    private suspend fun quotes(symbols: Collection<String>, reuseMs: Long = 0L): Map<String, Quote> = kotlinx.coroutines.coroutineScope {
        val b = book()
        symbols.distinct().mapNotNull { s -> b.contracts[s]?.let { c -> s to c } }
            .map { (s, c) -> async { runCatching { if (reuseMs > 0L) recentQuote(c, reuseMs) else quote(c) }.getOrNull()?.let { Sandbox.key(s, c.exchange) to it } } }
            .mapNotNull { it.await() }.toMap()
    }

    /**
     * Battery (round 5): the stream's tick (no network), else a price read in the last [maxAgeMs], else a fresh read.
     * Without a Zerodha session every paper price is a download of the contract's whole day of 1-minute candles, and a
     * watch pass read it once for the stops ([tick]) and again, seconds later, for the position cards and for each
     * words-only check ([snapshot] with [SHARED_QUOTE_MS]). The feed moves once a minute; the stops always read fresh.
     */
    internal suspend fun recentQuote(c: Contract, maxAgeMs: Long): Quote? {
        streamQuote(c)?.let { return it.also { remember(c.symbol, it) } }
        lastQuotes[c.symbol]?.let { (at, q) -> if (quoteClock() - at in 0..maxAgeMs) return q }
        return quote(c)
    }

    /** How old a price [place] may use again (the arm usually read it just before deciding). */
    internal const val PLACE_REUSE_MS = 10_000L

    /** How old a price the words-only checks and the position cards may share ([snapshot]); never the stops or limits. */
    const val SHARED_QUOTE_MS = 20_000L

    /** Symbols the engine needs prices for: open orders and open positions. */
    private fun watched(s: SandboxState): Set<String> =
        (s.orders.filter { it.status == "open" || it.status == "trigger pending" }.map { it.symbol } +
            s.positions.filter { it.quantity != 0 }.map { it.symbol }).toSet()

    // ---- actions -----------------------------------------------------------------

    /** [capped]: refused by the same-direction cap (19 Oct): nothing was placed; the caller may keep it as a would-be entry. */
    data class Result(val ok: Boolean, val message: String, val events: List<SandboxEvent>, val orderId: String? = null,
                      val capped: Boolean = false,
                      /** 25 Oct: refused (or sized down) by one of Boss's new risk rules ([BossRiskHost]); null: not by them. */
                      val rule: BossRiskHost.Rule? = null)

    private fun describe(r: OrderResult, events: List<SandboxEvent>): Result {
        val fill = events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        val msg = when {
            !r.ok -> r.message ?: "refused"
            fill != null -> "Paper ${fill.action} ${fill.quantity} ${fill.symbol} filled @ ${"%.2f".format(Locale.ENGLISH, fill.price)}"
            else -> r.message ?: "Paper order placed"
        }
        return Result(r.ok, msg, events, r.orderId)
    }

    /** The one BSE index whose options the paper account takes (15 Oct, the Hero ₹1,000 ticket's SENSEX side). */
    const val BFO_UNDERLYING = "SENSEX"

    /**
     * The Hero ₹1,000 ticket's own door (15 Oct): SENSEX's options are BSE's (BFO), from the list saved on the phone today
     * ([Market.cachedBfoContracts]; never a download here); NIFTY's as [contractFor]. Every other caller keeps [contractFor],
     * where SENSEX is still not listed (nothing else in the app trades it).
     */
    fun ticketContract(underlying: String, expiry: LocalDate, strike: Double, right: Right): Contract? {
        if (underlying != BFO_UNDERLYING) return contractFor(underlying, expiry, strike, right)
        val saved = Market.cachedBfoContracts()?.takeIf { it.first == Market.today() }?.second ?: return null
        val c = saved.firstOrNull { it.underlying == underlying && it.expiry == expiry && it.strike == strike && it.right == right } ?: return null
        return Contract(symbolOf(c), c.underlying, c.expiry, c.strike, c.right, c.lotSize, c.instrumentKey, exchange = "BFO")
    }

    /** 25 Oct (ntfy alerts' stock signals): a listed NSE stock option as a paper contract (its list is [StockOptions]'). */
    fun stockContract(c: Upstox.Contract): Contract = Contract(symbolOf(c), c.underlying, c.expiry, c.strike, c.right, c.lotSize, c.instrumentKey)

    /** Resolve a listed option into a paper contract (and remember it). An MCX name resolves on MCX's list (9 Oct). */
    fun contractFor(underlying: String, expiry: LocalDate, strike: Double, right: Right): Contract? {
        if (com.optionslab.engine.mcx.Mcx.isMcxName(underlying))
            return McxMarket.find(underlying, expiry, strike, right.takeIf { it != Right.IX })?.let { McxMarket.paperContract(it) }
        val c = Market.contracts().firstOrNull { it.underlying == underlying && it.expiry == expiry && it.strike == strike && it.right == right }
            ?: return null
        return Contract(symbolOf(c), c.underlying, c.expiry, c.strike, c.right, c.lotSize, c.instrumentKey)
    }


    /**
     * TEST SEAM (JVM tests only): the tests' fake candle feeds are a few fixed minutes of the morning, so the 08 Oct checks on
     * the candle feed (a stale price is never an entry's fill, [staleEntry]; Pine's thin-option check and its best price
     * from the minute highs) are skipped unless a test turns this off. False in the app, always: its setter throws in a
     * release build.
     */
    @Volatile internal var testSkipFeedChecks: Boolean = false
        set(v) {
            check(com.optionslab.app.BuildConfig.DEBUG) { "the feed-check seam exists only in debug builds" }
            field = v
        }

    /** Today's 1-minute candles of [c] from the paper feed (kept, as [quote] keeps them, for the minutes' highs and lows). */
    suspend fun minutes(c: Contract): List<Upstox.Bar> {
        val bars = Net.intradayQuick(c.feedKey).filter { it.istDate == Market.today() }
        if (bars.isNotEmpty()) candleBars[c.symbol] = System.currentTimeMillis() to bars
        return bars
    }

    /**
     * Why a MARKET order that opens or adds to a position must not fill now (08 Oct, research/PROFIT_LOCK_8OCT.md fix 4):
     * without Zerodha's stream the only price is the last 1-minute candle's close, and when the contract has not traded
     * for a while ([com.optionslab.engine.risk.StaleEntry]) that close is not a price anyone could buy at (Pine #17 bought at
     * a 368.90 the market had left). Null when it may fill: the stream streams the contract, the candles are fresh, or the
     * order only reduces what is held (an exit always fills, on the candle as before).
     */
    private suspend fun staleEntry(c: Contract, action: String, priceType: String): String? {
        if (testSkipFeedChecks || priceType != "MARKET") return null
        val held = book().state.positions.filter { it.symbol == c.symbol }.sumOf { it.quantity }
        val opens = if (action.equals("BUY", true)) held >= 0 else held <= 0
        if (!opens) return null
        if (runCatching { streamQuote(c) }.getOrNull() != null) return null
        val kept = candleBars[c.symbol]
        val bars = if (kept != null && System.currentTimeMillis() - kept.first in 0..STALE_READ_MS) kept.second
            else runCatching { minutes(c) }.getOrNull() ?: return null
        return com.optionslab.engine.risk.StaleEntry.refusal(bars.map { it.epochSecond to it.volume }, System.currentTimeMillis() / 1000, c.symbol)
    }

    /** How old the candles [staleEntry] reads may be (the ones the caller's [quote] read just now): else read again. */
    private const val STALE_READ_MS = 30_000L

    // ---- honest paper: never a fill on a price over a minute old (08 Oct) ----------------------------------------

    /**
     * The last 1-minute candle behind [q] when [q] is the candle feed's price for [c] (the stream does not stream it) and
     * that price is older than [com.optionslab.engine.sandbox.PaperSpread.STALE_SECONDS]; null when [q] is fresh: the
     * stream's tick, Zerodha's quote, or a candle under a minute old.
     */
    private fun staleCandle(c: Contract, q: Quote?): Upstox.Bar? {
        if (testSkipFeedChecks || q == null) return null
        if (candleQuotes[c.symbol]?.second != q) return null
        val bar = candleBars[c.symbol]?.second?.maxByOrNull { it.epochSecond } ?: return null
        return bar.takeIf { com.optionslab.engine.sandbox.PaperSpread.isStale(it.epochSecond, System.currentTimeMillis() / 1000) }
    }

    /** Zerodha's quote for [c] by its quote API (logged in only; with the real best bid and ask); null without one. */
    private suspend fun restQuote(c: Contract): Quote? {
        if (!Broker.loggedIn) return null
        val key = if (c.isMcx) McxMarket.find(c.symbol)?.kiteKey ?: return null
            else if (c.exchange == "BFO") "BFO:" + (Broker.cachedBfoOptions()?.let { Broker.find(it, c.underlying, c.expiry, c.strike, c.right) } ?: return null).tradingSymbol
            else "NFO:" + (Broker.cachedInstruments()?.let { Broker.find(it, c.underlying, c.expiry, c.strike, c.right) } ?: return null).tradingSymbol
        val r = runCatching { Broker.quotes(listOf(key)) }.getOrNull()?.get(key) ?: return null
        // 28 Oct: no last price: its live book (an exit sells at the bid) - never on MCX ([bookQuote]).
        if (!(r.last > 0)) return bookQuote(c, r.bid, r.ask)?.also { remember(c.symbol, it) }
        return Quote(r.last, bid = r.bid ?: 0.0, ask = r.ask ?: 0.0).also { remember(c.symbol, it) }
    }

    /**
     * The quote an EXIT of [c] ([action]: the closing side) fills on. [q] itself when fresh; when it is a candle close over
     * a minute old: Zerodha's quote, else the last minute's low for a sell (its high for a buy) - the worse side, never the
     * stale close. With the note for diagnostics (null when [q] was fresh).
     */
    private suspend fun exitQuote(c: Contract, q: Quote?, action: String): ExitQuote {
        val bar = staleCandle(c, q) ?: return ExitQuote(q, null, false)
        val age = com.optionslab.engine.sandbox.PaperSpread.ageSeconds(bar.epochSecond, System.currentTimeMillis() / 1000)
        restQuote(c)?.let { return ExitQuote(it, "${c.symbol}: candle price ${age}s old and no stream, so the $action used Zerodha's quote ${"%.2f".format(Locale.ENGLISH, it.ltp)}", false) }
        val px = com.optionslab.engine.sandbox.PaperSpread.conservativeExit(action, bar.close, bar.low, bar.high)
        return ExitQuote((q ?: Quote(px)).copy(ltp = px), "${c.symbol}: candle price ${age}s old, no stream and no Zerodha quote, so the $action used the last minute's " +
            "${if (action.equals("BUY", true)) "high" else "low"} ${"%.2f".format(Locale.ENGLISH, px)}", true)
    }

    /** [exitQuote]'s answer: the quote, the diagnostics note (null: [quote] was fresh), and whether it is the stale minute's worse side (exits only). */
    private data class ExitQuote(val quote: Quote?, val note: String?, val worseSide: Boolean)

    /** The side that closes [symbol]'s open position (SELL when long or flat, BUY when short). */
    private fun closingSide(symbol: String): String =
        if (book().state.positions.filter { it.symbol == symbol }.sumOf { it.quantity } < 0) "BUY" else "SELL"

    /** Whether [action] on [symbol] opens or adds (true), or only reduces the position held. */
    private fun opens(symbol: String, action: String): Boolean {
        val held = book().state.positions.filter { it.symbol == symbol }.sumOf { it.quantity }
        return if (action.equals("BUY", true)) held >= 0 else held <= 0
    }

    /** What [exitPrices] hands the engine: the quotes to fill on, the entries that must wait, the diagnostics notes by symbol. */
    private data class ExitPrices(val quotes: Map<String, Quote>, val hold: Set<String>, val notes: Map<String, String>)

    /**
     * [q] made safe to fill on: each symbol with a working order whose price is a stale candle gets Zerodha's quote, else
     * the worse side of its last minute for its exits, and its entries wait ([ExitPrices.hold]). [alsoHeld]: every open
     * position's symbol too (the 15:15 square-off closes them at market).
     */
    private suspend fun exitPrices(q: Map<String, Quote>, alsoHeld: Boolean = false): ExitPrices {
        val st = book().state
        val working = st.orders.filter { it.status == "open" || it.status == "trigger pending" }
        val syms = working.map { it.symbol }.toSet() + (if (alsoHeld) st.positions.filter { it.quantity != 0 }.map { it.symbol } else emptyList())
        if (syms.isEmpty()) return ExitPrices(q, emptySet(), emptyMap())
        val out = HashMap(q); val hold = HashSet<String>(); val notes = HashMap<String, String>()
        for (sym in syms) {
            val c = book().contracts[sym] ?: continue
            val k = Sandbox.key(sym, c.exchange)
            val x = exitQuote(c, q[k], closingSide(sym))
            val fq = x.quote ?: continue
            val note = x.note ?: continue
            out[k] = fq; notes[sym] = note
            // Zerodha's quote is fresh: everything may fill on it. The candle's worse side is for exits only.
            if (x.worseSide) working.filter { it.symbol == sym && opens(sym, it.action) }.forEach { hold += it.orderId }
        }
        // 28 Oct, review: a resting ENTRY never fills on a quote with no last price unless an entry may fill there now (a sane book,
        // Boss's switch on: [bookEntryRefusal]) - so the switch's Undo and the spread rule hold for orders already resting. Exits are
        // never held by it.
        for (o in working) {
            val c = book().contracts[o.symbol] ?: continue
            val bq = out[Sandbox.key(o.symbol, c.exchange)]?.takeIf { it.noLast } ?: continue
            if (opens(o.symbol, o.action) && bookEntryRefusal(c, bq) != null) hold += o.orderId
        }
        // 15 Nov (Boss: "Yes fix those too"): a resting stop that closes a position waits while its only price is not a price now
        // ([stopMustWait]) - judged again on the next pass, never filled on a stray bid or a trade from before it rested.
        for (o in working) {
            if (o.status != "trigger pending" || opens(o.symbol, o.action)) continue
            val c = book().contracts[o.symbol] ?: continue
            val oq = out[Sandbox.key(o.symbol, c.exchange)] ?: continue
            if (stopMustWait(c, oq, o.updateTimestamp, o.action.equals("SELL", true), o.triggerPrice?.toDouble())) hold += o.orderId
        }
        return ExitPrices(out, hold, notes)
    }

    /** Entries already noted as held back on a stale price (noted once each). */
    private val heldNoted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Diagnostics for a pass that filled on a substitute price or held an entry back ([exitPrices]). */
    private fun noteStale(p: ExitPrices, events: List<SandboxEvent>) {
        if (p.notes.isEmpty()) return
        events.filterIsInstance<SandboxEvent.Fill>().filter { it.symbol in p.notes }
            .forEach { f -> runCatching { Diag.record("paper", "Stale price: " + p.notes.getValue(f.symbol) + "; filled @ ${"%.2f".format(Locale.ENGLISH, f.price)}") } }
        p.hold.filter { heldNoted.add(it) }.forEach { id -> runCatching { Diag.record("paper", "Stale price: order $id waits for a real price (an entry: no stream and a candle over a minute old; a stop: no trade since it rested and no real book)") } }
    }

    /** The diagnostics line: the bid/ask spread paper fills paid this session (Honest paper, 08 Oct). */
    fun spreadTodayLine(): String = book().let { b ->
        val t = engine(b.capital, b.contracts).tradeBook(b.state, Market.now())
        com.optionslab.engine.sandbox.PaperSpread.dayLine(t.sumOf { it.spread }, t.count { it.spread > 0 })
    }

    /**
     * [known]: the contract's quote when the caller has just read it (read here otherwise). [auto]: the arm placing it, for an
     * automatic entry (19 Oct: the same-direction cap counts and may refuse it); null for Boss's own orders and every exit -
     * those are never capped. [mirror]: the arm's own live key (ArmLive), when it has one: the cap only logs while that arm's
     * entry could also be mirrored live (19 Oct review).
     */
    suspend fun place(c: Contract, action: String, lots: Int, priceType: String, product: String, price: Double?, trigger: Double?,
                      known: Quote? = null, auto: String? = null, mirror: String? = null, exit: Boolean = false,
                      verdict: com.optionslab.engine.risk.OrderFingerprint.Verdict? = null, stop: Double? = null, target: Double? = null,
                      /** 19 Nov: the arm's own source for an arm's automatic entry ([BossRiskHost.paperEarly] tells R51, R72 and Liquidity apart by it). */
                      source: String? = null): Result {
        // 22 Oct (arms audit): an arm's EXIT ([exit]) only ever reduces what the book holds in [product]. An arm reads "held" and
        // then sells a moment later; the expiry square-off (15:05), the 15:15 square-off, the sweeper or Boss may have closed the
        // position in between, and a plain SELL on a flat book opened a paper SHORT nothing managed (the Hero tickets' and ORB's
        // 15:05 sales). Now such an exit is refused as "already closed" (the arm books it closed elsewhere), and one for more
        // than is held sells only what is held. Checked here before any price read, and again inside the book's lock, with the place.
        // (TEST ONLY, 19 Nov 2026: another paper close gets there just before this exit - the race the arms' exits must handle.)
        if (exit) testBeforeExit?.let { h -> testBeforeExit = null; h(c) }
        if (exit && reduceQty(book(), c.symbol, product, action, lots * c.lotSize) == null) return notOpen(c.symbol)
        // 22 Oct: the order fingerprint ([OrderVerdicts]): a paper order whose check stamped a [verdict] goes only when the
        // fingerprint of what is placed now (these arguments, [stop] / [target] what the caller rests) is the one the check
        // cleared, today. Refusing only lowers risk; an order placed with no verdict (a stop or target resting, an exit) is as before.
        if (verdict != null) OrderVerdicts.refusal(verdict, OrderVerdicts.paperFields(c, action, lots, priceType, product, price, trigger, Market.today(),
                verdict.fields.source, stop, target))?.let { return Result(false, "Not sent: $it", emptyList()) }
        // 28 Oct, review: an entry priced on the live book alone (no last price) is never mirrored live ([ArmLive.entered]), so the
        // paper cap and Boss's rules below apply to it in full, never "logged only: this entry may also go live". Memory only.
        val mirrorTo = if (runCatching { bookOnlyNow(c, known) }.getOrDefault(false)) ExposureCapHost.NO_LIVE else mirror
        // Order speed (paper): timed from here (the price read included) to the paper book's answer; the price's age is the
        // exchange stamp of this contract's last stream tick, when it streams.
        // 16 Oct (Boss's diagnostics: "Prices were up to 3.6 s old when used"): a fill never uses a quote older than
        // [FILL_FRESH_MS] when a newer one can be had - a stream tick that came in in the last 2 s first; else, for an ENTRY
        // only (logged in to Zerodha), Zerodha's own quote, waited [FILL_FRESH_MS] at most. An exit never waits on the network
        // here: the stream's fresh tick, else the caller's [known] quote, as before. Timed from before that read; the price
        // age is the exchange stamp of the tick actually used (none for a REST quote, read just now).
        // 19 Oct: an automatic entry the same-direction cap refuses now is refused before any price read (memory only); the
        // same check is made again inside the book's lock below, with the place, where it counts.
        if (auto != null && action.equals("BUY", true) && runCatching { opens(c.symbol, action) }.getOrDefault(false)) {
            val d = com.optionslab.ira.SameDirectionCap.direction(c.underlying, c.exchange, c.right.name)
            // 22 Oct, merge review: with its owner, so R72's kept place refuses here too, before any price read.
            val why = d?.let { capCheck(book(), it, auto) }
            // 19 Oct review: while this entry could also be mirrored live, the cap only logs (a refusal here would skip the live
            // mirror too); the live door applies the cap to the live order itself.
            if (d != null && why != null && ExposureCapHost.liveMirrorPossible(mirrorTo)) {
                runCatching { ExposureCapHost.refused(auto, c.symbol, d, known?.ltp, live = false, applied = false,
                    why = "$why (logged only: this entry may also go live)") }
            } else if (d != null && why != null) {
                runCatching { ExposureCapHost.refused(auto, c.symbol, d, known?.ltp ?: runCatching { streamQuote(c, kept = true) ?: lastQuotes[c.symbol]?.second }.getOrNull()?.ltp,
                    live = false, applied = true, why = why) }
                return Result(false, why, emptyList(), capped = true)
            }
        }
        // 25 Oct (Boss's decisions on the app audit, [BossRiskHost]): an automatic entry the paper account stop, the weekly loss stop
        // or the opposite-bets rule refuses is refused here, before any price read (memory only); the arm keeps it as a would-be entry.
        // While the entry could also be mirrored live, they only log (a new rule that could skip a live trade starts in shadow).
        val bossEntry = auto != null && action.equals("BUY", true) && runCatching { opens(c.symbol, action) }.getOrDefault(false)
        if (bossEntry) {
            val d = com.optionslab.ira.SameDirectionCap.direction(c.underlying, c.exchange, c.right.name)
            val bets = if (d == null) emptyList() else runCatching { bossBets(book()) }.getOrDefault(emptyList())
            BossRiskHost.paperEarly(auto!!, c.symbol, c.underlying, d, bets, mirrorTo, source)?.let { why ->
                return Result(false, why, emptyList(), capped = true, rule = BossRiskHost.Rule.entries.firstOrNull { r -> why.contains(r.words) })
            }
        }
        val token = runCatching { kiteToken(c) }.getOrNull()
        val tick = token?.let { tok -> runCatching { KiteStream.touch(listOf(tok)); KiteStream.freshTick(tok) }.getOrNull() }
        val freshTick = tick?.takeIf { it.last > 0 }
        // 28 Oct: a fresh tick with no last price is the contract's live book ([bookQuote]); none usable: Zerodha's quote, as before.
        val bookTick = if (freshTick == null && tick != null) bookQuote(c, tick.bid, tick.ask, tick.bidQty ?: 0L, tick.askQty ?: 0L) else null
        val reRead = freshTick == null && bookTick == null && token != null && Broker.loggedIn && runCatching { opens(c.symbol, action) }.getOrDefault(false)
        val stamp = if (freshTick != null) freshTick.exchangeTime else if (bookTick != null) tick?.exchangeTime
            else if (reRead) null else runCatching { token?.let { KiteStream.seen(it)?.tick?.exchangeTime } }.getOrNull()
        val t0 = runCatching { OrderTiming.sending(live = false, exchSec = stamp) }.getOrDefault(System.currentTimeMillis())
        val fresh = freshTick?.let { quoteOf(it) } ?: bookTick ?: if (reRead) freshRestQuote(c) else null
        // 12 Oct: the stream's tick, else a price read in the last [PLACE_REUSE_MS] (the candle feed moves once a minute and
        // the honest-paper checks below still judge its age), else a fresh read - not a download of the day's candles per order.
        val read = fresh?.also { remember(c.symbol, it) } ?: known ?: runCatching { recentQuote(c, PLACE_REUSE_MS) }.getOrNull()
        // 28 Oct: an entry on a quote with no last price only on a sane book, with Boss's switch on ([bookEntryRefusal]);
        // refused with the plain reason, nothing placed. An exit is never held back by it (it sells at the bid).
        val onBook = read?.noLast == true && runCatching { opens(c.symbol, action) }.getOrDefault(true)
        if (onBook) bookEntryRefusal(c, read!!)?.let { return Result(false, "${c.symbol}: $it", emptyList()) }
        // (Review: the price turned out to be the book's only now, after the rules above only logged for a possible live copy:
        // not filled this time - the next try applies them in full. Refusing only lowers risk.)
        if (onBook && auto != null && mirrorTo != ExposureCapHost.NO_LIVE && ExposureCapHost.liveMirrorPossible(mirrorTo))
            return Result(false, "${c.symbol}: priced on its book alone just now; tried again on the next pass", emptyList())
        // A paper entry never fills on a stale candle close (fix 4): refused with the reason, nothing placed. A live book read
        // just now is not a candle close (28 Oct).
        if (!onBook) staleEntry(c, action, priceType)?.let { return Result(false, it, emptyList()) }
        // Honest paper (08 Oct): no fill on a price over a minute old. An exit fills on Zerodha's quote or the stale minute's
        // worse side; an entry LIMIT or stop rests without it (it fills later on a fresh price; a MARKET entry is refused
        // above, as before). Noted in diagnostics.
        val x = when {
            !opens(c.symbol, action) -> exitQuote(c, read, action)
            priceType.equals("MARKET", true) -> ExitQuote(read, null, false)
            else -> staleCandle(c, read)?.let { ExitQuote(null, "${c.symbol}: candle price over a minute old and no stream, so the $priceType entry rests until a fresh price", false) }
                ?: ExitQuote(read, null, false)
        }
        val q = x.quote
        x.note?.let { runCatching { Diag.record("paper", "Stale price: $it") } }
        val entry = runCatching { opens(c.symbol, action) }.getOrDefault(true)
        // 25 Oct: the 2% risk cap on the price just read (a LIMIT's own price when it has one): over it, sized down to the lots that
        // fit, or refused when one lot is over it. The spread check is shadow only (logged, never changes the entry). Memory only.
        var placeLots = lots
        var sized: BossRiskHost.Rule? = null
        if (bossEntry) {
            val px = (if (priceType.equals("LIMIT", true)) price else null) ?: q?.ask?.takeIf { it > 0 } ?: q?.ltp?.takeIf { it > 0 }
            val v = BossRiskHost.paperSize(auto!!, c.symbol, lots, c.lotSize, px, stop, runCatching { book().capital.toDouble() }.getOrDefault(Double.NaN), mirrorTo)
            v.refusal?.let { return Result(false, it, emptyList(), capped = true, rule = v.rule) }
            if (v.lots in 1 until lots) { placeLots = v.lots; sized = v.rule }
            // Only on a quote with a book (the stream's): a candle price has none, and "no book" there says nothing about the market.
            if (!c.isMcx && ((q?.bid ?: 0.0) > 0 || (q?.ask ?: 0.0) > 0)) BossRiskHost.spreadShadow(auto, c.symbol, q?.bid, q?.ask, live = false)
        }
        // 19 Oct: an automatic BUY that opens an index option is counted by the same-direction cap ([capCheck]); its way (+1 a
        // call, -1 a put). Exits, sells, Boss's own orders and anything but the index options: null, never asked.
        // 20 Oct, review: the entry is COUNTED ([capWay]) even while the cap only logs it (an entry that may also go live): it is
        // a paper position the cap must still see once Live is off (before, it was never recorded and the cap undercounted).
        val capWay = if (auto != null && action.equals("BUY", true) && entry)
            com.optionslab.ira.SameDirectionCap.direction(c.underlying, c.exchange, c.right.name) else null
        val capDir = capWay?.takeIf { !ExposureCapHost.liveMirrorPossible(mirrorTo) }
        val r = locked {
            // Remember the contract and place in one step, so a concurrent place cannot overwrite either.
            val b0 = book()
            // The cap asked inside the book's lock, with the place: the first entry to reach it is the one that goes (two arms
            // on the same bar can never both take the last place). Memory only.
            if (capDir != null) capCheck(b0, capDir, auto)?.let { why -> return@locked Result(false, why, emptyList(), capped = true) }
            val b = if (b0.contracts[c.symbol] == c) b0 else b0.copy(contracts = b0.contracts + (c.symbol to c))
            // An exit reduces only: never more than is held now, never a short from a flat book (22 Oct, arms audit).
            val qty = if (exit) (reduceQty(b, c.symbol, product, action, lots * c.lotSize) ?: return@locked notOpen(c.symbol)) else placeLots * c.lotSize
            val out = engine(b.capital, b.contracts).place(b.state,
                OrderRequest(c.symbol, c.exchange, action, qty, priceType, product, price, trigger, "IraAlgo-Android"), q, Market.now())
            val id = out.result.orderId
            val autoNow = if (capWay != null && out.result.ok && id != null) (b.auto + (id to auto!!)).let { m ->
                if (m.size > AUTO_KEPT) m.entries.drop(m.size - AUTO_KEPT).associate { it.key to it.value } else m } else b.auto
            save(b.copy(state = out.state, auto = autoNow))
            describe(out.result, out.events)
        }
        // A refused automatic entry is kept as a would-be entry (its arm, option, way and the price it would have filled at).
        if (r.capped && capDir != null) runCatching { ExposureCapHost.refused(auto!!, c.symbol, capDir, q?.ltp, live = false, applied = true, why = r.message) }
        timed(r, t0)
        // 28 Oct: an entry filled on the book alone is said in diagnostics and noted for the live mirror (never mirrored: [filledOnBook]).
        if (onBook && r.ok) r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()?.let { f ->
            bookFills[c.symbol] = quoteClock()
            runCatching { Diag.record("paper", com.optionslab.ira.BookPrice.filledLine(c.symbol, q?.bid ?: 0.0, q?.ask ?: 0.0, f.price)) }
        }
        // 25 Oct: the IV of each automatic entry that filled (V1: record only, worked out in the background).
        if (bossEntry && r.ok) r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()?.let { f -> BossRiskHost.entryIv(auto!!, c, f.price, live = false) }
            ?: r.orderId?.let { BossRiskHost.ivRest(it, auto!!, c) }
        // The sandbox's modelled slippage (kept apart as paper): a LIMIT from its price, else from the price it read.
        val limit = priceType.equals("LIMIT", true) && price != null
        modelled(r, entry, if (limit) price else q?.ltp?.takeIf { it > 0 }, limit)
        // 25 Oct: an entry the 2% cap sized down says so (its fill is recorded above, as every fill).
        if (sized != null && r.ok) return r.copy(rule = sized)
        return r
    }

    /**
     * The same-direction cap's answer for an automatic entry betting [dir] (19 Oct): null when it may go, else the reason (the
     * caller records the would-be entry once out of the lock, [ExposureCapHost.refused]). The automatic entries open now are read
     * from [b] itself: its fills first in, first out, what it holds, and its working automatic BUY orders. Memory only; called
     * under the book's lock. A failure to judge lets the entry go (the cap only ever refuses).
     */
    private fun capCheck(b: Book, dir: Int, owner: String? = null): String? = runCatching {
        val max = ExposureCapHost.paperMax() ?: return@runCatching null
        // 22 Oct (Boss: "reserve R72's place"): at R72 Breadth Donchian's bar close the last place each way is kept for it.
        // 25 Oct (Boss's decision on the arms audit): only while R72 has a break due that way, not on every bar.
        val kept = runCatching { PassedArmsHost.placeKeptFor(Market.now().toLocalDateTime(), dir) }.getOrNull()
        com.optionslab.ira.SameDirectionCap.refusal(capOpen(b), dir, max, owner, kept)
    }.getOrNull()

    /** The automatic index-option entries open in [b] ([com.optionslab.ira.SameDirectionCap.openEntries]). */
    private fun capOpen(b: Book): List<com.optionslab.ira.SameDirectionCap.Open> {
        if (b.auto.isEmpty()) return emptyList()
        val st = b.state
        val held = HashMap<String, Int>()
        for (p in st.positions) held[p.symbol] = (held[p.symbol] ?: 0) + p.quantity
        val fills = st.trades.map { com.optionslab.ira.SameDirectionCap.Fill(it.orderId, it.symbol, it.action.equals("BUY", true), it.quantity, it.timestamp) }
        val working = st.orders.filter { (it.status == "open" || it.status == "trigger pending") && it.action.equals("BUY", true) }.map { it.orderId to it.symbol }
        return com.optionslab.ira.SameDirectionCap.openEntries(fills, held, working, { s ->
            b.contracts[s]?.let { c -> com.optionslab.ira.SameDirectionCap.direction(c.underlying, c.exchange, c.right.name) }
        }) { b.auto[it] }
    }

    /** 25 Oct: the automatic index-option entries open in [b] with their index, for the opposite-bets rule ([BossRiskHost]). */
    private fun bossBets(b: Book): List<com.optionslab.ira.BossRisk.Bet> = capOpen(b).mapNotNull { o ->
        b.contracts[o.symbol]?.let { com.optionslab.ira.BossRisk.Bet(o.owner, it.underlying, o.dir, o.symbol) }
    }

    /**
     * 25 Oct (Boss's decision on the app audit, H4): whether a position the paper book holds on [mcx]'s side (MCX, or everything
     * else) has no fresh stream tick ([maxAgeMs]): the stop lane then looks every 5 s instead of 15 s. Memory only (the tokens
     * already looked up; one not looked up yet counts as quiet - more looks, never fewer). False before the book is read, or with
     * nothing held on that side.
     */
    fun heldQuiet(mcx: Boolean, maxAgeMs: Long = 5_000L): Boolean = runCatching {
        val b = cache ?: return@runCatching false
        val syms = b.state.positions.filter { it.quantity != 0 && (b.contracts[it.symbol]?.isMcx == true) == mcx }.map { it.symbol }.toSet()
        syms.any { s -> val tok = kiteTokens[s]?.takeIf { it > 0 } ?: return@any true; KiteStream.tick(tok, maxAgeMs) == null }
    }.getOrDefault(false)

    /** The automatic index-option entries open now in the paper book, from memory (Bot settings' line; empty before it is read). */
    fun capOpenNow(): List<com.optionslab.ira.SameDirectionCap.Open> = cache?.let { runCatching { capOpen(it) }.getOrNull() }.orEmpty()

    /**
     * [c] remembered in the book's contracts without an order (19 Oct: a would-be entry the cap or a twin arm kept off the
     * account still needs its contract for its own would-be exits). Memory at once; written in the background.
     */
    fun rememberContract(c: Contract) {
        if (book().contracts[c.symbol] == c) return
        locked { val b = book(); if (b.contracts[c.symbol] != c) save(b.copy(contracts = b.contracts + (c.symbol to c))) }
    }

    /**
     * 25 Oct (app audit): the resting orders (stops, limits) that filled in [events] measured against their trigger or price
     * ([FillSlippage.resting]): the orders read from memory, the fill kept in the background.
     */
    private fun measureResting(events: List<SandboxEvent>) {
        val fills = events.filterIsInstance<SandboxEvent.Fill>()
        if (fills.isEmpty()) return
        runCatching {
            val orders = book().state.orders.associateBy { it.orderId }
            fills.forEach { f -> runCatching { FillSlippage.resting(f, orders[f.orderId]) } }
        }
    }

    /** Each paper fill in [r] measured against [intended] ([FillSlippage.paper]: kept in the background, never the book's). */
    private fun modelled(r: Result, entry: Boolean, intended: Double?, limit: Boolean) {
        if (!r.ok) return
        // The live recording's snapshot of each paper fill (a queue add; built and written in the background).
        r.events.filterIsInstance<SandboxEvent.Fill>().forEach { f ->
            runCatching { LiveRecorder.fill(f.symbol, f.action, !entry, f.quantity, f.price, intended, paper = true, arm = null) }
        }
        if (intended == null) return
        r.events.filterIsInstance<SandboxEvent.Fill>().forEach { f ->
            runCatching { FillSlippage.paper(f.symbol, f.action, entry, f.quantity, intended, f.price, limit) }
        }
    }

    /** Order speed: the paper book answered [r] (placed at [t0]); a fill in the same answer is known at once. */
    private fun timed(r: Result, t0: Long) {
        if (!r.ok) return
        runCatching {
            OrderTiming.answered(live = false, startedAt = t0)
            if (r.events.any { it is SandboxEvent.Fill }) OrderTiming.fillKnown(live = false, ms = 0L, viaStream = false)
        }
    }

    fun modify(orderId: String, quantity: Int?, price: Double?, trigger: Double?): Result = locked {
        val b = book()
        val out = engine(b.capital, b.contracts).modify(b.state, orderId, OrderChange(quantity, price, trigger), Market.now())
        save(b.copy(state = out.state))
        describe(out.result, out.events)
    }

    /**
     * Cancel a working order. [why] is why the app cancelled it, kept beside the order so Jarvis can say why later ("why was
     * my last order cancelled"): a key such as "position_closed", "exit:index_stop", "oco:stop", "unfilled_market", "you",
     * "jarvis", "strategy", "protection_removed" (worded by com.optionslab.ira.OrderWhy). Only a note: the cancel itself
     * is exactly as before.
     */
    suspend fun cancel(orderId: String, why: String? = null): Result {
        val sym = book().state.orders.firstOrNull { it.orderId == orderId }?.symbol
        val q = sym?.let { quickQuote(it) }
        locked {
            val b = book()
            val out = engine(b.capital, b.contracts).cancel(b.state, orderId, Market.now(), q)
            val ended = out.events.any { it is SandboxEvent.OrderUpdate && it.orderId == orderId && it.status == "cancelled" }
            save(b.copy(state = out.state, why = if (ended && why != null) noted(b.why, mapOf(orderId to why)) else b.why))
            return describe(out.result, out.events)
        }
    }

    /** Why the app cancelled paper order [orderId] (a key from [cancel]), when it noted one. */
    fun cancelReason(orderId: String): String? = book().why[orderId]

    private fun noted(old: Map<String, String>, add: Map<String, String>): Map<String, String> {
        val m = LinkedHashMap(old); m.putAll(add)
        return if (m.size <= WHY_KEPT) m else LinkedHashMap(m.entries.drop(m.size - WHY_KEPT).associate { it.key to it.value })
    }

    /** The positions a close is being sent for now ("symbol|product"): a second close of the same one is refused. */
    private val closing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The open quantity of [symbol] in [product] in [b]. */
    private fun heldIn(b: Book, symbol: String, product: String): Int =
        b.state.positions.filter { it.symbol == symbol && it.product.equals(product, ignoreCase = true) }.sumOf { it.quantity }

    /**
     * The quantity an arm's exit of [qty] may send (22 Oct, arms audit): [action] must close what [b] holds in [product] (a SELL
     * a long, a BUY a short), and never more than is held; null when nothing is held that way (the exit is refused: the position
     * was closed by the square-off, the sweeper or Boss, and the arm books it closed elsewhere). Pure on the book.
     */
    /** [reduceQty] on the book as it is now (tests). */
    internal fun reduceQtyNow(symbol: String, product: String, action: String, qty: Int): Int? = reduceQty(book(), symbol, product, action, qty)

    private fun reduceQty(b: Book, symbol: String, product: String, action: String, qty: Int): Int? {
        val held = heldIn(b, symbol, product)
        val closing = if (held > 0) "SELL" else if (held < 0) "BUY" else return null
        if (!closing.equals(action, ignoreCase = true)) return null
        // A close of the whole position still working (a square-off's MARKET order resting for a price) is already on its way:
        // an arm's exit sells only what it leaves, so the two can never add up to a short.
        val closingNow = b.state.orders.filter { it.symbol == symbol && it.product.equals(product, ignoreCase = true) &&
            it.action.equals(closing, ignoreCase = true) && it.status == "open" && it.strategy == "AUTO_SQUARE_OFF" }.sumOf { it.pendingQuantity }
        return minOf(qty, kotlin.math.abs(held) - closingNow).takeIf { it > 0 }
    }

    /** What a close of a position that is not open says (never the engine's "Missing required field: quantity"). */
    private fun notOpen(symbol: String) = Result(false, "No open paper position in $symbol: it is already closed.", emptyList())

    /**
     * Close the paper position in [symbol] / [product] at market. 12 Oct: a close of a row already flat (a second tap on a
     * position card's "Close position" or a stale list's Close, while the first close was still on its way) reached the
     * engine as an order of quantity 0 - "[error] Missing required field: quantity" three times at 09:05-09:06. Now a flat
     * position is said to be closed already, and a second close of the same position while one is on its way is refused.
     */
    suspend fun close(symbol: String, product: String): Result {
        if (heldIn(book(), symbol, product) == 0) return notOpen(symbol)
        val key = "$symbol|${product.uppercase(Locale.ENGLISH)}"
        if (!closing.add(key)) return Result(false, "$symbol: a close is already on its way.", emptyList())
        try { return closeOnce(symbol, product) } finally { closing.remove(key) }
    }

    private suspend fun closeOnce(symbol: String, product: String): Result {
        val stamp = runCatching { book().contracts[symbol]?.let { kiteToken(it) }?.let { KiteStream.seen(it)?.tick?.exchangeTime } }.getOrNull()
        val t0 = runCatching { OrderTiming.sending(live = false, exchSec = stamp) }.getOrDefault(System.currentTimeMillis())
        val read = quickQuote(symbol)
        // Honest paper (08 Oct): never closed on a candle price over a minute old ([exitQuote]); noted in diagnostics.
        val x = book().contracts[symbol]?.let { exitQuote(it, read, closingSide(symbol)) }
        val q = x?.quote ?: read
        x?.note?.let { runCatching { Diag.record("paper", "Stale price: $it") } }
        val r = locked {
            val b = book()
            val held = heldIn(b, symbol, product)
            if (held == 0) return notOpen(symbol)
            val e = engine(b.capital, b.contracts)
            val now = Market.now()
            // 22 Oct (arms audit): the position's resting exits (an arm's SL-M stop, a LIMIT target) come out of the book first.
            // Left resting after the close, a stop touched before the arm's next look filled - as a short (the expiry square-off's
            // close of an ORB or Hero option; the engine's own 15:15 square-off already cancels first).
            var s = b.state
            val closingSide = if (held > 0) "SELL" else "BUY"
            val taken = s.orders.filter { it.symbol == symbol && it.product.equals(product, ignoreCase = true) && it.action.equals(closingSide, true) &&
                (it.status == "open" || it.status == "trigger pending") }.map { it.orderId }
            for (id in taken) e.cancel(s, id, now, q).also { if (it.result.ok) s = it.state }
            val out = e.closePosition(s, symbol, exchangeOf(symbol), product, q, now)
            // The close refused (no price, margin): nothing is kept of it - the stop and target stay resting, as they were.
            if (!out.result.ok) return describe(out.result, out.events)
            save(b.copy(state = out.state, why = if (taken.isEmpty()) b.why else noted(b.why, taken.associateWith { "position_closed" })))
            describe(out.result, out.events)
        }
        timed(r, t0)
        modelled(r, entry = false, intended = q?.ltp?.takeIf { it > 0 }, limit = false)
        return r
    }

    /**
     * One pass of IraAlgo's scheduled jobs: catch up after downtime, fill
     * resting orders against fresh prices, square off MIS past 15:15, settle
     * expired contracts and T+1. Called by the Trade tab and the watch.
     */
    suspend fun tick(): List<SandboxEvent> {
        val events = tickLocked()
        // The Live check's "last checked" for paper positions (a volatile stamp; nothing written anywhere).
        WatchClock.paperChecked()
        // Resting paper orders that filled on this pass (stops, limits): the live recording's snapshot of each.
        events.filterIsInstance<SandboxEvent.Fill>().forEach { f ->
            runCatching { LiveRecorder.fill(f.symbol, f.action, f.action.equals("SELL", true), f.quantity, f.price, null, paper = true, arm = null, note = "resting") }
        }
        measureResting(events)
        // 25 Oct: a resting automatic entry that filled on this pass has its IV recorded (record only; memory, then the background).
        runCatching { BossRiskHost.ivFills(events) }
        // The engine's own 15:15 MIS square-off is named on the order it placed.
        events.filterIsInstance<SandboxEvent.SquareOff>().mapNotNull { it.result.orderId }
            .forEach { runCatching { Strategies.tagOwner("paper:$it", Origins.AUTO_SQUARE_OFF) } }
        return events
    }

    private suspend fun tickLocked(): List<SandboxEvent> {
        val syms = watched(book().state)
        val q = quotes(syms)
        // The candle prices this pass read itself, kept for [stopPrice] (the stream's ticks are read afresh there).
        tickCandles = syms.mapNotNull { sym ->
            val got = q[Sandbox.key(sym, exchangeOf(sym))] ?: return@mapNotNull null
            candleQuotes[sym]?.takeIf { com.optionslab.ira.StopPrice.handedOn(got, it.second) }?.let { sym to it }
        }.toMap()
        // Honest paper (08 Oct): the orders fill on fresh prices only ([exitPrices]); positions are still marked to [q].
        // From 15:14 on (NSE's 15:15 square-off, and every MCX evening position after it, MCX's 23:20 / 23:45 cut included).
        val fill = exitPrices(q, alsoHeld = !Market.now().toLocalTime().isBefore(java.time.LocalTime.of(15, 14)))
        locked {
            val b = book()
            val e = engine(b.capital, b.contracts)
            val now = Market.now()
            val events = ArrayList<SandboxEvent>()
            var s = b.state
            e.catchUp(s, now).also { s = it.state; events += it.events }
            // A resting SELL SL-M (a stop, or one the profit lock moved up) touched between two looks fills where it would
            // have at the exchange - its trigger, or the price already under it - not at whatever the price is now.
            // 22 Oct (arms audit): a resting BUY SL-M - a short's stop (the US-night silver future) - the same way, on a high.
            for (o in s.orders.filter { it.status == "trigger pending" && it.priceType == "SL-M" && (it.action == "SELL" || it.action == "BUY") }) {
                val c = b.contracts[o.symbol] ?: continue
                if (q[Sandbox.key(o.symbol, o.exchange)] == null) continue          // only on a pass that read this contract's price
                if (o.orderId in fill.hold) continue                                // 15 Nov: no price now for it ([stopMustWait])
                val trig = o.triggerPrice?.toDouble() ?: continue
                val px = runCatching { if (o.action == "BUY") restingBuyFill(c, o.updateTimestamp, trig) else restingFill(c, o.updateTimestamp, trig) }.getOrNull() ?: continue
                e.fillRestingStop(s, o.orderId, px, now, q[Sandbox.key(o.symbol, o.exchange)]).also { if (it.result.ok) { s = it.state; events += it.events } }
            }
            e.onQuotes(s, fill.quotes, now, fill.hold).also { s = it.state; events += it.events }
            // Marks positions to the fresh LTP, which expiry settlement prices from.
            e.positionBook(s, now, q).also { s = it.state; events += it.events }
            // The square-off's own events kept apart: only its cancels are the 15:15 square-off (reason text only).
            val squareFrom = events.size
            e.squareOffDue(s, now, fill.quotes).also { s = it.state; events += it.events }
            val squareTo = events.size
            e.settleExpiries(s, now).also { s = it.state; events += it.events }
            // The book's own cancels here: a contract's expiry, the 15:15 MIS square-off, or (the catch-up after a day's end,
            // anything else) the day ending - noted, so Jarvis can say why. Labels for the reason text only.
            val own = events.withIndex().filter { (_, ev) -> ev is SandboxEvent.OrderUpdate && ev.status == "cancelled" }.associate { (i, ev) ->
                val u = ev as SandboxEvent.OrderUpdate
                val o = s.orders.firstOrNull { it.orderId == u.orderId }
                val exp = o?.symbol?.let { sym -> b.contracts[sym]?.expiry }
                u.orderId to when {
                    exp != null && !exp.isAfter(now.toLocalDate()) -> "expiry"
                    i in squareFrom until squareTo && o?.product == "MIS" -> "square_off"
                    i in squareFrom until squareTo -> "expiry"
                    else -> "day_end"
                }
            }
            if (s != b.state) saveMarked(b.copy(state = s, why = if (own.isEmpty()) b.why else noted(b.why, own)))
            noteStale(fill, events)
            // 28 Oct, review: a resting buy filled on a quote with no last price is a book fill too: never mirrored live.
            runCatching {
                events.filterIsInstance<SandboxEvent.Fill>().filter { it.action.equals("BUY", true) && fill.quotes[Sandbox.key(it.symbol, it.exchange)]?.noLast == true }
                    .forEach { bookFills[it.symbol] = quoteClock() }
            }
            return events
        }
    }

    /** One resting stop the sweeper sold: its symbol, its trigger and where it filled. */
    data class Swept(val symbol: String, val trigger: Double, val price: Double, val orderId: String)

    /**
     * The missed-lock sweeper's paper half (08 Oct, Boss's item 7): every resting SELL SL-M whose contract's price now
     * ([quote]: the stream's tick, else the day's candles) is already at or under its trigger is filled now at that price
     * ([Sandbox.fillRestingStop]) - the resting order itself, so the owner (an ORB arm, a Pine script, a protection) sees
     * its stop filled and there is never a second sell. Also says whether any price was read ([Sweep.priced]) for the
     * no-price failsafe. Paper only: nothing reaches Zerodha.
     */
    suspend fun sweepMissedStops(): Sweep {
        val stops = book().state.orders.filter { it.status == "trigger pending" && it.priceType == "SL-M" && it.action == "SELL" }
        val held = book().state.positions.filter { it.quantity != 0 }.map { it.symbol }.toSet()
        val q = quotes(stops.map { it.symbol } + held)
        // Honest paper (08 Oct): a stop is never sold on a candle price over a minute old ([exitPrices]).
        val fill = exitPrices(q)
        val out = ArrayList<Swept>()
        val events = ArrayList<SandboxEvent>()
        locked {
            val b = book()
            val e = engine(b.capital, b.contracts)
            val now = Market.now()
            var s = b.state
            for (o in s.orders.filter { it.status == "trigger pending" && it.priceType == "SL-M" && it.action == "SELL" }) {
                val fq = fill.quotes[Sandbox.key(o.symbol, o.exchange)] ?: continue
                if (o.orderId in fill.hold) continue                                // 15 Nov: no price now for it ([stopMustWait])
                val px = fq.ltp
                val trig = o.triggerPrice?.toDouble() ?: continue
                if (!com.optionslab.engine.risk.MissedLock.missed(px, trig)) continue
                val r = e.fillRestingStop(s, o.orderId, px, now, fq)
                if (r.result.ok) { s = r.state; events += r.events; out += Swept(o.symbol, trig, s.orders.firstOrNull { it.orderId == o.orderId }?.averagePrice?.toDouble() ?: px, o.orderId) }
            }
            if (s != b.state) save(b.copy(state = s))
        }
        noteStale(fill, events)
        measureResting(events)
        return Sweep(out, failsafeHeld(held, book().contracts), q.keys)
    }

    /**
     * What [sweepMissedStops] did: the stops it sold, the open positions ([held]: their price keys, exchanges and expiries)
     * and the keys priced this pass ([quoted]). 15 Oct: the positions themselves, not two yes/no answers, so the no-price
     * failsafe can judge each in its own market's hours ([Sweeper]).
     */
    data class Sweep(val swept: List<Swept>, val held: List<PriceWatch.Held>, val quoted: Set<String>)

    /**
     * Views, priced with fresh quotes where the engine uses them. [reuseQuotesMs] > 0 only for words and cards (never the
     * loss limit, the square-off, stops or anything that orders): a price read that recently is used again; the book
     * itself is always read as it is now.
     */
    suspend fun snapshot(reuseQuotesMs: Long = 0L): Snapshot {
        val q = quotes(watched(book().state) + book().state.holdings.map { it.symbol }, reuseQuotesMs)
        locked {
            val b = book()
            val e = engine(b.capital, b.contracts)
            val now = Market.now()
            val funds = e.funds(b.state, now)
            val pos = e.positionBook(funds.state, now, q)
            val hold = e.holdings(pos.state, now, q)
            // The funds read again after re-pricing (and any expiry settlement), so they agree with the positions shown.
            val after = e.funds(hold.state, now)
            if (after.state != b.state) saveMarked(b.copy(state = after.state))
            return Snapshot(after.result, pos.result, e.orderBook(after.state, now), e.tradeBook(after.state, now), hold.result, q.isNotEmpty() || watched(after.state).isEmpty(),
                chargesSinceReset(after.state))
        }
    }

    /**
     * The books as this phone holds them, marked at the prices they were last marked to: no price read (no network) and
     * nothing saved. With when the account was last saved (epoch ms), so a figure from it carries its own "as of". Null
     * when the paper account was never used on this phone (nothing to show). For the "Open" home-screen widget.
     */
    fun localSnapshot(): Pair<Snapshot, Long>? {
        val savedAt = runCatching { file.lastModified() }.getOrDefault(0L)
        if (savedAt <= 0L) return null
        synchronized(this) {
            val b = book()
            val e = engine(b.capital, b.contracts)
            val now = Market.now()
            val funds = e.funds(b.state, now)
            val pos = e.positionBook(funds.state, now)
            val hold = e.holdings(pos.state, now)
            val after = e.funds(hold.state, now)
            return Snapshot(after.result, pos.result, e.orderBook(after.state, now), e.tradeBook(after.state, now), hold.result, false, chargesSinceReset(after.state)) to savedAt
        }
    }

    /** Every charge the account paid since its last reset (the funds' Total P&L is after them). */
    private fun chargesSinceReset(st: SandboxState): Double =
        st.trades.filter { !it.timestamp.isBefore(st.funds.lastResetDate) }.sumOf { it.charges.toDouble() }

    data class Snapshot(
        val funds: com.optionslab.engine.sandbox.FundsView,
        val positions: com.optionslab.engine.sandbox.PositionBook,
        val orders: com.optionslab.engine.sandbox.OrderBook,
        val trades: List<com.optionslab.engine.sandbox.TradeRow>,
        val holdings: com.optionslab.engine.sandbox.HoldingsBook,
        val priced: Boolean,
        /** Every charge paid since the last reset: the header's Total P&L is shown before them ([totalGross]). */
        val chargesSinceReset: Double = 0.0,
    ) {
        /**
         * The day's P&L AFTER charges: the positions' (realised today + unrealised) less the charges of today's trades.
         * Read from the positions, not the funds' running tally, so the calendar's kept figure and the loss limits can
         * never disagree (a build before the funds fix left the tally short). RISK LIMITS READ THIS ONE (the daily loss
         * limit, the guard, the loss breaker, Jarvis's checks): after charges is the safer figure. Screens show
         * [dayGross] with [dayCharges] under it (Boss, 5 Oct), never this as the headline.
         */
        val dayPnl: Double get() = positions.totalPnlToday - dayCharges

        /** Today's charges (the trade book holds today's trades only). */
        val dayCharges: Double get() = trades.sumOf { it.charges }

        /** Today's bid/ask spread, already in the fill prices (Honest paper, 08 Oct): words only, never subtracted again. */
        val daySpread: Double get() = trades.sumOf { it.spread }

        /** The day's P&L BEFORE charges, as every screen shows it (as Zerodha shows its own): display only, never a limit. */
        val dayGross: Double get() = com.optionslab.ira.PnlCharges.gross(dayPnl, dayCharges)

        /** The funds' Total P&L (since the last reset) before charges, for display: the funds themselves stay as they are. */
        val totalGross: Double get() = com.optionslab.ira.PnlCharges.gross(funds.totalPnl, chargesSinceReset)
    }

    /** Back to a fresh account with [capital]; the contracts seen are kept. */
    fun reset(capital: BigDecimal) { locked { save(fresh(capital, book().contracts)) }; elsewhereClaims.clear() }

    /**
     * TEST ONLY: [loadSoon] reads the book on the caller's thread - the background load as fast as it can be, done before
     * [streamCover] even returns (the race [FastPath]'s snapshot must survive). False in the app. Throws unless BuildConfig.DEBUG.
     */
    @Volatile internal var loadInlineForTest = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test switch exists only in debug builds" }; field = v }

    /** 19 Nov 2026 (audit batch 2): reads of the book that failed in a row ([load]), when the first and the last of them failed (wall ms). */
    @Volatile private var readFails = 0
    @Volatile private var firstReadFailMs = 0L
    @Volatile private var lastReadFailMs = 0L
    /** 19 Nov 2026 (audit batch 2): a read failing this many times in a row, over [readAsideAfterMs] at least, is set aside ([load]). */
    internal const val READ_FAILS_BEFORE_ASIDE = 5
    /** 19 Nov 2026 (audit batch 2): how long a failing read keeps the file before it is set aside, and the least gap between two tries. */
    @Volatile internal var readAsideAfterMs = 5 * 60_000L
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }
    @Volatile internal var readRetryGapMs = 2_000L
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }
    /** TEST ONLY (19 Nov 2026, audit batch 2): run once right before the next exit order (a test closes the position there: the race). */
    @Volatile internal var testBeforeExit: (suspend (Contract) -> Unit)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }
    /** TEST ONLY (19 Nov 2026): the vault read replaced (a Keystore hiccup). Debug builds only. */
    @Volatile internal var testRead: (() -> ByteArray?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** TEST SEAM: forget the book in memory (its file kept), as a new process starts. */
    internal fun forgetForTest() { awaitWrites(); synchronized(this) { cache = null; savedAtMs = 0L; readFails = 0; firstReadFailMs = 0L; lastReadFailMs = 0L }; follow(emptySet()) }

    fun wipe() {
        // The write lock first (as [flush] takes it): a write on its way never puts the file back after the delete.
        // 26 Oct ("Erase everything", [PersonalStores]): safe before [init] (no file yet), and the books set aside as unreadable
        // go too. Every save reads the book again under this lock ([locked]), so none can write the old one back after this.
        synchronized(writeLock) { synchronized(this) {
            pending = null; cache = null; savedAtMs = 0L; readFails = 0; firstReadFailMs = 0L; lastReadFailMs = 0L
            filesForErase().forEach { runCatching { it.delete() } }
        } }
        lastQuotes.clear(); candleQuotes.clear(); candleBars.clear(); tickCandles = emptyMap(); kiteTokens.clear(); kiteKeys.clear(); priceReads.clear(); elsewhereClaims.clear()
        heldNoted.clear()
        follow(emptySet())
    }

    /** 26 Oct: whether the book (or a write of it) is still in memory (the erase's tests). Memory only, never the vault. */
    internal fun holdsForErase(): Boolean = cache != null || pending != null

    /** 26 Oct: the book's vault file and any book set aside as unreadable ([load]); empty before [init]. */
    internal fun filesForErase(): List<File> {
        if (!::file.isInitialized) return emptyList()
        val aside = file.parentFile?.listFiles { f -> f.name.startsWith("paper.unreadable.") }.orEmpty().toList()
        return listOf(file) + aside
    }
}
