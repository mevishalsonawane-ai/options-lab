package com.optionslab.app.data

import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.sandbox.OrderRequest
import com.optionslab.engine.sandbox.OrderStatus
import com.optionslab.engine.sandbox.Sandbox
import com.optionslab.engine.sandbox.SandboxState
import com.optionslab.ira.LiveCheck
import com.optionslab.ira.LiveCheck.Budgets
import com.optionslab.ira.LiveCheck.Group
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Live check (18 Oct 2026; Boss: "test each and everything with actual works on live ... every thing should be perfect,
 * should work at its minimum time"), grown from the read-only self-test. Boss runs it on the phone in market hours (More
 * → Zerodha → Live check, or "Jarvis, run the live check"); optionally once by itself at 09:20 on a trading day (its own
 * switch, off by default). It proves each live piece works and how fast, and it NEVER places, changes or cancels an order:
 *
 *  - Zerodha account: the session, funds, holdings, GTTs, a quote and the contract list (reads), and that an order would
 *    leave from the registered static IP (nothing sent).
 *  - Option chain: NIFTY's ATM strike on the nearest expiry, its CE and PE quotes, and the lot, tick and freeze quantity.
 *  - Price stream: the app's own Zerodha stream ([KiteStream]) connected, the first tick of NIFTY, NIFTY BANK and the ATM CE,
 *    ticks a second over 10 s, the largest gap between ticks, the stream's price against a REST quote, and the reconnect after
 *    a forced drop - only when nothing is open, and never in the automatic run (open positions come first).
 *  - Order readiness: the exact 1-lot ATM CE buy the order form would send, checked against every rule and the contract;
 *    Zerodha's order margin and basket margin (its read-only calculators); a two-leg GTT stop and target, built and checked,
 *    never placed.
 *  - Positions: the position book and the order book, the paper book, and that every open position has its stop watched -
 *    its stop and target as the checks see them and its last check under 15 s ([WatchClock]).
 *  - Paper round trip on live prices: a 1-lot buy on the paper account's own engine ([Paper.scratchEngine]) with a state kept
 *    here and never saved, filled at the live price, a stop and a target attached, then closed. The trade is tagged
 *    [SELF_TEST] and never reaches the paper book, any arm's results, the P&L records or research: it exists only inside
 *    this check.
 *  - Data feeds: the Zerodha login, the last 1-minute bar's age of Zerodha's and Upstox's candles. (Dhan is no longer read.)
 *  - Clock: the phone's time against Zerodha's server clock (or the exchange's tick stamps), as drift.
 *
 * Every step runs off the screen's thread (the IO dispatcher), never on the order or tick path; each is timed against its
 * budget ([LiveCheck.Budgets]) and none stops the others. Outside market hours the stream and paper steps say "market
 * closed". Results carry counts, timings and pass / fail only - never an amount, a token or an order id - so the card can be
 * shown or screenshotted safely; each step and the summary go to the diagnostics diary ([LiveCheck.AREA]).
 */
object LiveSelfTest {
    /** The tag on the check's paper trade (the sandbox order's strategy): it is never anything's result. */
    const val SELF_TEST = "LiveSelfTest"

    /** The stream's follow list for the check's instruments (released when it ends). */
    const val OWNER = "live-check"

    /** The automatic 09:20 run's switch (off unless Boss turns it on) and the day it last ran. */
    const val KEY_AUTO = "livecheck.auto"
    private const val KEY_AUTO_DONE = "livecheck.auto.done"

    private const val NIFTY_TOKEN = 256265L
    private const val BANK_TOKEN = 260105L
    private const val NIFTY_KEY = "NSE:NIFTY 50"

    /**
     * What the check reads besides Zerodha's REST API ([Broker], the fake Kite in the tests): the price stream, the market's
     * hours, the public candle feed, the clock and the watch's stamps. [Real] in the app; the tests hand in their own.
     */
    interface Sources {
        fun marketOpen(): Boolean
        fun streamLive(): Boolean
        /** Follow [tokens] and bring the stream up (it starts only with a Zerodha session in market hours). */
        fun streamStart(tokens: Collection<Long>)
        fun streamRelease()
        /** The last tick of [token]: its price and when it reached the phone (ms); null: none. */
        fun seen(token: Long): Pair<Double, Long>?
        fun lastTickAt(): Long
        fun ticksNow(): Long
        fun dropForCheck(): Boolean
        suspend fun upstoxBars(key: String): List<Upstox.Bar>
        fun clockOffset(): Pair<Long, String>?
        fun paperCheckedAt(): Long
        fun stopsCheckedAt(): Long
        /**
         * Anything open anywhere, read again just before the forced drop: Zerodha's positions and working orders (a fresh read),
         * the paper account, a protection, and every arm's own book (ORB, the Strategy Module, Pine, MCX, night, VIX, Hero,
         * passed arms, Telegram calls, the ticket, Jarvis's trades). True too when any of it could not be read. Reads only.
         */
        suspend fun booksOpen(): Boolean
    }

    object Real : Sources {
        override fun marketOpen() = Market.isOpen()
        override fun streamLive() = KiteStream.status.value == KiteStream.Status.LIVE
        override fun streamStart(tokens: Collection<Long>) { KiteStream.want(OWNER, tokens); KiteStream.ensure() }
        override fun streamRelease() = KiteStream.want(OWNER, emptyList())
        override fun seen(token: Long) = KiteStream.seen(token)?.let { it.tick.last to it.at }
        override fun lastTickAt() = KiteStream.lastTickAt
        override fun ticksNow() = KiteStream.ticksNow()
        override fun dropForCheck() = KiteStream.dropForCheck()
        override suspend fun upstoxBars(key: String) = Net.intradayQuick(key)
        override fun clockOffset() = OrderTiming.latestClockOffset()
        override fun paperCheckedAt() = WatchClock.paperAt
        override fun stopsCheckedAt() = WatchClock.stopsAt
        override suspend fun booksOpen(): Boolean = runCatching {
            // Memory first (the watch's own "anything held" and MCX's), then the books already in memory, then Zerodha afresh.
            com.optionslab.app.work.WatchWorkers.heldQuick() || McxMarket.heldQuick() || Paper.watching() ||
                McxPaperArms.holdsNow() || NightArm.refresh().open.isNotEmpty() || VixDivArm.refresh().open.isNotEmpty() ||
                Ledger.openTicket() != null || (com.optionslab.app.BuildConfig.JARVIS && com.optionslab.app.ira.IraSolo.holdingOrThrow()) ||
                Broker.positionBook().net.any { it.open } || Broker.orders().any { it.working }
        }.getOrDefault(true)
    }

    /** The last run's steps (memory; the diary keeps them across a restart). */
    @Volatile var last: List<LiveCheck.Step> = emptyList()
        private set

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    val busy: Boolean get() = running.get()

    /** TEST ONLY: no last run left from an earlier test. Throws unless BuildConfig.DEBUG. */
    internal fun resetForTest() {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        last = emptyList()
        sourcesForTest = null
    }

    /** TEST ONLY: the sources a run uses by default (null: [Real], always, in the app). Its setter throws unless BuildConfig.DEBUG. */
    @Volatile internal var sourcesForTest: Sources? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }

    /**
     * The stream steps' waits: the tick-counting window and how often it looks, and how long a connect, a first tick and a
     * reconnect may take before they fail. The app's are [Budgets]'; the tests shorten them.
     */
    data class Limits(
        val windowMs: Long = Budgets.WINDOW_MS, val sampleMs: Long = 50L,
        val streamTimeoutMs: Long = Budgets.STREAM_TIMEOUT_MS, val reconnectTimeoutMs: Long = Budgets.RECONNECT_TIMEOUT_MS,
        /** The stream's price is compared with the REST quote only when its tick is at most this old. */
        val freshTickMs: Long = 5_000L,
    )

    /**
     * Runs every step in turn and reports each as it ends; a failed step does not stop the rest. [auto]: the 09:20 automatic
     * run (no forced stream drop). Returns the steps, or an empty list when a check is already running. [onStep] is called on
     * a background thread.
     */
    suspend fun run(onStep: (LiveCheck.Step) -> Unit = {}, auto: Boolean = false, src: Sources = sourcesForTest ?: Real,
                    limits: Limits = Limits()): List<LiveCheck.Step> {
        if (!running.compareAndSet(false, true)) return emptyList()
        try {
            val r = LiveCheck.Runner(Dispatchers.IO, reason = ::reason, onStep = { s ->
                onStep(s)
                runCatching { Diag.record(LiveCheck.AREA, LiveCheck.diaryLine(s)) }
            })
            // All of it off the screen's thread (each step's own code runs on IO too): [onStep] is called from there.
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    Steps(r, src, auto, limits).all()
                } finally {
                    runCatching { src.streamRelease() }
                }
            }
            val out = r.steps
            last = out
            runCatching { Diag.record(LiveCheck.AREA, LiveCheck.SUMMARY + LiveCheck.summary(out)) }
            return out
        } finally {
            running.set(false)
        }
    }

    /** One run's steps and what they hand on to each other (the ATM contract, its quote, the order built). */
    private class Steps(val r: LiveCheck.Runner, val src: Sources, val auto: Boolean, val limits: Limits) {
        val windowMs = limits.windowMs
        val sampleMs = limits.sampleMs
        val waitMs = limits.streamTimeoutMs
        var instruments: List<Kite.Instrument>? = null
        var ce: Kite.Instrument? = null
        var pe: Kite.Instrument? = null
        var ceQuote: Broker.Quote? = null
        var order: Kite.Order? = null
        var anythingOpen: Boolean? = null
        var streamUp = false
        val open = src.marketOpen()

        suspend fun all() {
            val session = r.step(Group.ACCOUNT, "Session", Budgets.BUILD_MS) {
                if (!Broker.configured) error("Zerodha is not set up on this phone")
                if (!Broker.loggedIn) error("not logged in today: log in first")
                LiveCheck.pass("logged in")
            }
            if (session.ok) {
                account()
                chain()
                streamFirstPart()
                orderReadiness()
                positions()
                reconnect()
                paperRoundTrip()
            }
            staticIp()
            feeds(session.ok)
            clock()
        }

        // ---- Zerodha account (reads) --------------------------------------------------------------------------------

        suspend fun account() {
            r.step(Group.ACCOUNT, "Funds", Budgets.READ_MS) { Broker.funds(); LiveCheck.pass("read") }
            r.step(Group.ACCOUNT, "Holdings", Budgets.READ_MS) { LiveCheck.pass("${Broker.holdings().size} holdings") }
            r.step(Group.ACCOUNT, "GTT triggers", Budgets.READ_MS) { LiveCheck.pass("${Broker.gtts().size} triggers") }
            r.step(Group.ACCOUNT, "Quote (NIFTY 50)", Budgets.READ_MS) {
                if (Broker.indexQuote("NIFTY", spark = false) != null) LiveCheck.pass("price received") else LiveCheck.fail("no price returned")
            }
            r.step(Group.ACCOUNT, "Contract list", Budgets.CONTRACTS_MS) {
                val all = Broker.instruments()
                instruments = all
                LiveCheck.pass("${all.size} contracts")
            }
        }

        suspend fun staticIp() {
            // The phone's outgoing IP is read from the internet (through the relay when it is on; kept for a minute): a read.
            r.step(Group.ACCOUNT, "Static IP for orders", Budgets.READ_MS * 2) {
                if (StaticIp.registered == null) error("no static IP registered in the app (More → Zerodha → Static IP)")
                // Unreadable is not a pass (review, 18 Oct): the order would go, and Zerodha would decide.
                if (StaticIp.current() == null) return@step LiveCheck.warn("the phone's outgoing IP could not be read, so it is not known to be the registered one")
                StaticIp.entryBlock()?.let { error(it) }
                LiveCheck.pass("orders would leave from the registered IP")
            }
        }

        // ---- the option chain ------------------------------------------------------------------------------------------

        suspend fun chain() {
            r.step(Group.CHAIN, "ATM strike (NIFTY)", Budgets.READ_MS) {
                val all = instruments ?: error("no contract list to find it in")
                val today = Market.today()
                val nifty = all.filter { it.name == "NIFTY" && !it.expiry.isBefore(today) }
                if (nifty.isEmpty()) error("no NIFTY options listed today")
                val expiry = nifty.minOf { it.expiry }
                val near = nifty.filter { it.expiry == expiry }
                val spot = Broker.indexQuote("NIFTY", spark = false)?.last?.takeIf { it > 0 } ?: error("no NIFTY price to centre the chain on")
                val strike = near.filter { it.right == Right.CE }.minByOrNull { kotlin.math.abs(it.strike - spot) }?.strike ?: error("no NIFTY CE listed")
                ce = near.firstOrNull { it.strike == strike && it.right == Right.CE }
                pe = near.firstOrNull { it.strike == strike && it.right == Right.PE }
                if (pe == null) error("the ${com.optionslab.engine.fmtG(strike)} PE is not listed")
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, expiry)
                LiveCheck.pass("${com.optionslab.engine.fmtG(strike)}, nearest expiry ${expiry} (${if (days == 0L) "today" else "in $days days"})")
            }
            r.step(Group.CHAIN, "ATM CE and PE quotes", Budgets.READ_MS) {
                val c = ce ?: error("no ATM strike")
                val p = pe ?: error("no ATM strike")
                val q = Broker.quotesFresh(listOf("NFO:${c.tradingSymbol}", "NFO:${p.tradingSymbol}"))
                val cq = q["NFO:${c.tradingSymbol}"]?.takeIf { it.last > 0 }
                val pq = q["NFO:${p.tradingSymbol}"]?.takeIf { it.last > 0 }
                ceQuote = cq
                when {
                    cq == null && pq == null -> LiveCheck.fail("neither the CE nor the PE was quoted")
                    cq == null || pq == null -> LiveCheck.fail("only the ${if (cq == null) "PE" else "CE"} was quoted")
                    listOf(cq.bid, cq.ask, pq.bid, pq.ask).any { it == null || it <= 0 } ->
                        if (open) LiveCheck.warn("both quoted, but a bid or an ask is missing") else LiveCheck.pass("both quoted (last prices; no bid or ask with the market closed)")
                    else -> LiveCheck.pass("both quoted, with a bid and an ask on each")
                }
            }
            r.step(Group.CHAIN, "Lot, tick and freeze quantity", Budgets.BUILD_MS) {
                val c = ce ?: error("no ATM strike")
                val freeze = Kite.freezeQuantity("NFO", c.tradingSymbol)
                when {
                    c.lotSize <= 0 -> LiveCheck.fail("the contract list gives no lot size")
                    !(c.tickSize > 0) -> LiveCheck.fail("the contract list gives no tick size")
                    freeze == null -> LiveCheck.fail("no freeze quantity known for NIFTY")
                    freeze < c.lotSize -> LiveCheck.fail("the freeze quantity is under one lot")
                    else -> LiveCheck.pass("lot ${c.lotSize}, tick ${com.optionslab.engine.fmtG(c.tickSize)}, freeze $freeze units (${freeze / c.lotSize} lots; " +
                        "the exchange's limit from the app's table: Zerodha's list does not carry it)")
                }
            }
        }

        // ---- the price stream ------------------------------------------------------------------------------------------

        private val streamSteps = listOf("Connected", "First tick: NIFTY 50", "First tick: NIFTY BANK", "First tick: ATM option",
            "Ticks per second", "Largest gap between ticks", "Stream against a REST quote")
        private var arrivals: List<List<Long>> = emptyList()
        private var window: Pair<Long, Long>? = null

        /** A fresh tick of [token] that reached the phone after [since]: how long after [since] (null: none in [limitMs]). */
        suspend fun firstTick(token: Long, since: Long, limitMs: Long): Long? {
            val until = since + limitMs
            while (System.currentTimeMillis() <= until) {
                src.seen(token)?.let { (last, at) -> if (at > since && last > 0) return at - since }
                delay(sampleMs.coerceAtMost(50L))
            }
            return null
        }

        suspend fun streamFirstPart() {
            if (!open) { streamSteps.forEach { r.step(Group.STREAM, it, 0) { LiveCheck.closed() } }; return }
            val ceToken = ce?.token
            val connected = r.step(Group.STREAM, "Connected", Budgets.CONNECT_MS, waitMs + 1_000) {
                val already = src.streamLive()
                src.streamStart(listOfNotNull(NIFTY_TOKEN, BANK_TOKEN, ceToken))
                if (already) return@step LiveCheck.pass("live (already open)")
                val until = System.currentTimeMillis() + waitMs
                while (!src.streamLive()) {
                    if (System.currentTimeMillis() > until) return@step LiveCheck.fail("not live after ${time(waitMs)}")
                    delay(50)
                }
                LiveCheck.pass("live")
            }
            streamUp = connected.ok
            if (!streamUp) { streamSteps.drop(1).forEach { r.step(Group.STREAM, it, 0) { LiveCheck.skipped("the stream is not connected") } }; return }
            for ((name, token) in listOf("First tick: NIFTY 50" to NIFTY_TOKEN, "First tick: NIFTY BANK" to BANK_TOKEN, "First tick: ATM option" to ceToken)) {
                r.step(Group.STREAM, name, Budgets.FIRST_TICK_MS, waitMs + 1_000) {
                    if (token == null) return@step LiveCheck.fail("no ATM option to follow")
                    val t0 = System.currentTimeMillis()
                    val after = firstTick(token, t0, waitMs) ?: return@step LiveCheck.fail("no tick in ${time(waitMs)}")
                    LiveCheck.pass("after $after ms")
                }
            }
            r.step(Group.STREAM, "Ticks per second", windowMs + 2_000, windowMs + 5_000) {
                val c0 = src.ticksNow()
                val from = System.currentTimeMillis()
                // Each of the check's own instruments' arrivals (review, 18 Oct): a stall in one of them is a gap even while the
                // stream's other instruments keep ticking.
                val mine = listOfNotNull(NIFTY_TOKEN, BANK_TOKEN, ceToken)
                val seen = mine.associateWith { ArrayList<Long>() }
                val lastAt = HashMap<Long, Long>()
                while (System.currentTimeMillis() - from < windowMs) {
                    for (t in mine) {
                        val at = src.seen(t)?.second ?: continue
                        if (at != lastAt[t]) { seen.getValue(t).add(at); lastAt[t] = at }
                    }
                    delay(sampleMs)
                }
                val to = System.currentTimeMillis()
                val c1 = src.ticksNow()
                arrivals = seen.values.toList()
                window = from to to
                // A reconnect in the window starts the connection's counter again: then only the new connection's ticks count.
                val count = if (c1 >= c0) c1 - c0 else c1
                LiveCheck.rate(count, to - from)
            }
            r.step(Group.STREAM, "Largest gap between ticks", Budgets.BUILD_MS) {
                val (from, to) = window ?: return@step LiveCheck.fail("no window was counted")
                // The worst of the three (the stream's own instruments for the check: NIFTY 50, NIFTY BANK and the ATM option).
                LiveCheck.gap(arrivals.maxOfOrNull { LiveCheck.largestGap(it, from, to) } ?: (to - from), sampleMs)
            }
            r.step(Group.STREAM, "Stream against a REST quote", Budgets.READ_MS) {
                val rest = Broker.quotesFresh(listOf(NIFTY_KEY))[NIFTY_KEY]?.last
                val tick = src.seen(NIFTY_TOKEN)
                // A stale stream price could agree in a flat market: only a tick of the last few seconds is compared.
                val age = tick?.let { System.currentTimeMillis() - it.second }
                if (age != null && age > limits.freshTickMs) return@step LiveCheck.fail("the stream's last NIFTY 50 tick is ${time(age)} old")
                LiveCheck.agree(tick?.first, rest)
            }
        }

        suspend fun reconnect() {
            val name = "Reconnect after a forced drop"
            // 18 Oct, review: the arms keep their paper positions in books of their own, and something may have opened since the
            // positions step: every book is read again just before the drop (outside the step's own time, which is the stream's).
            val held = if (open && streamUp && !auto && anythingOpen == false) src.booksOpen() else false
            r.step(Group.STREAM, name, Budgets.RECONNECT_MS, limits.reconnectTimeoutMs + 1_000) {
                when {
                    !open -> return@step LiveCheck.closed()
                    !streamUp -> return@step LiveCheck.skipped("the stream is not connected")
                    auto -> return@step LiveCheck.skipped("not in the automatic run: only a check you start drops the stream")
                    anythingOpen != false -> return@step LiveCheck.skipped(
                        if (anythingOpen == null) "the books could not be read, so the stream is not dropped"
                        else "a position or order is open, so the stream is not dropped")
                }
                if (held) return@step LiveCheck.skipped("an arm holds a position or order (or a book could not be read), so the stream is not dropped")
                val at = System.currentTimeMillis()
                if (!src.dropForCheck()) return@step LiveCheck.fail("no open connection to drop")
                val until = at + limits.reconnectTimeoutMs
                while (!(src.streamLive() && src.lastTickAt() > at)) {
                    if (System.currentTimeMillis() > until) return@step LiveCheck.fail("not back in ${time(limits.reconnectTimeoutMs)}")
                    delay(50)
                }
                LiveCheck.pass("back with a fresh tick after ${System.currentTimeMillis() - at} ms")
            }
        }

        // ---- order readiness (nothing is sent) -------------------------------------------------------------------------

        suspend fun orderReadiness() {
            val built = r.step(Group.ORDER, "1-lot ATM CE buy (built, not sent)", Budgets.READ_MS) {
                val c = ce ?: error("no ATM strike")
                val q = ceQuote ?: Broker.quotesFresh(listOf("NFO:${c.tradingSymbol}"))["NFO:${c.tradingSymbol}"]?.takeIf { it.last > 0 }
                    ?: error("no price for the ATM CE")
                ceQuote = q
                val s = AppSettings.load()
                // Exactly the hand order form's buy ([com.optionslab.app.ui.AppModel.planManual]): a LIMIT at the offer, on the tick.
                val px = Kite.onTick(q.ask?.takeIf { it > 0 } ?: q.last, c.tickSize, Kite.Side.BUY)
                val o = Kite.Order(c.tradingSymbol, Kite.Side.BUY, c.lotSize, c.lotSize, s.orderProduct, "LIMIT", px, c.tickSize)
                order = o
                // The day's order count is left out (0): this order is never sent, and the count is not the contract's rule.
                val why = Kite.refusals(o, s.limits(), 0, holdToSettlement = false, refPrice = q.last)
                if (why.isNotEmpty()) return@step LiveCheck.fail(why.joinToString("; ") { LiveCheck.clean(it) })
                val form = Kite.query(o.formBody())
                val want = mapOf("tradingsymbol" to c.tradingSymbol, "exchange" to "NFO", "transaction_type" to "BUY", "order_type" to "LIMIT",
                    "quantity" to c.lotSize.toString(), "product" to s.orderProduct, "validity" to "DAY")
                val wrong = want.filter { (k, v) -> form[k] != v }.keys
                if (wrong.isNotEmpty()) return@step LiveCheck.fail("the order's form is wrong in: ${wrong.joinToString()}")
                if (form["price"].isNullOrEmpty() || form["tag"].isNullOrEmpty()) return@step LiveCheck.fail("the order's form has no price or no tag")
                LiveCheck.pass("LIMIT ${s.orderProduct}, 1 lot of ${c.lotSize}, priced at the offer on the ${com.optionslab.engine.fmtG(c.tickSize)} tick; every rule passed")
            }
            r.step(Group.ORDER, "Order margin (Zerodha, read-only)", Budgets.READ_MS) {
                val o = order ?: error(if (built.ok) "no order built" else "the order could not be built")
                val rows = Broker.orderMargins(listOf(o))
                if (rows.size != 1 || rows.any { !it.isFinite() || it <= 0 }) return@step LiveCheck.fail("Zerodha did not price the order")
                LiveCheck.pass("Zerodha priced the order")
            }
            r.step(Group.ORDER, "Basket margin (Zerodha, read-only)", Budgets.READ_MS) {
                val o = order ?: error(if (built.ok) "no order built" else "the order could not be built")
                val m = Broker.basketMargin(listOf(o))
                when {
                    !m.required.isFinite() || m.required <= 0 -> LiveCheck.fail("Zerodha did not price the basket")
                    m.short -> LiveCheck.warn("Zerodha priced the basket; the free margin does not cover it")
                    else -> LiveCheck.pass("Zerodha priced the basket; the free margin covers it")
                }
            }
            r.step(Group.ORDER, "GTT stop and target (built, not placed)", Budgets.BUILD_MS) {
                val c = ce ?: error("no ATM strike")
                val o = order ?: error("no order built")
                val last = ceQuote?.last?.takeIf { it > 0 } ?: error("no price for the ATM CE")
                val spec = Kite.Spec("NFO", c.tradingSymbol, c.token, c.lotSize, c.tickSize)
                val (gtt, why) = Kite.protect(spec, o.product, c.lotSize, last, stop = last * 0.7, target = last * 1.5)
                if (gtt == null) return@step LiveCheck.fail(why.joinToString("; ") { LiveCheck.clean(it) })
                val form = Kite.query(gtt.formBody())
                fun onGrid(x: Double) = kotlin.math.abs(x / c.tickSize - Math.round(x / c.tickSize)) < 1e-6
                when {
                    gtt.type != "two-leg" || form["type"] != "two-leg" -> LiveCheck.fail("not a two-leg (stop and target) GTT")
                    gtt.triggers.size != 2 || gtt.triggers[0] >= gtt.triggers[1] -> LiveCheck.fail("the two triggers are not a stop below and a target above")
                    gtt.orders.size != 2 || gtt.orders.any { it.side != Kite.Side.SELL || it.quantity != c.lotSize || it.orderType != "LIMIT" } ->
                        LiveCheck.fail("its legs are not two LIMIT sells of one lot")
                    gtt.orders.any { it.price == null || !onGrid(it.price!!) } || gtt.triggers.any { !onGrid(it) } -> LiveCheck.fail("a price is off the tick")
                    form["condition"].isNullOrEmpty() || form["orders"].isNullOrEmpty() -> LiveCheck.fail("the GTT's form is incomplete")
                    else -> LiveCheck.pass("a two-leg GTT: stop and target triggers, two LIMIT sells on the tick; not placed")
                }
            }
        }

        // ---- positions and their stops -------------------------------------------------------------------------------

        suspend fun positions() {
            var live: List<Broker.Position>? = null
            var working: Int? = null
            var paper: SandboxState? = null
            r.step(Group.POSITIONS, "Position book (Zerodha)", Budgets.READ_MS) {
                val b = Broker.positionBook()
                live = b.net
                LiveCheck.pass("${b.net.count { it.open }} open, ${b.net.count { !it.open }} closed today")
            }
            r.step(Group.POSITIONS, "Order book (Zerodha)", Budgets.READ_MS) {
                val o = Broker.orders()
                working = o.count { it.working }
                LiveCheck.pass("${o.size} orders today, ${o.count { it.working }} working")
            }
            r.step(Group.POSITIONS, "Paper book", Budgets.READ_MS) {
                val st = Paper.state
                paper = st
                LiveCheck.pass("${st.positions.count { it.quantity != 0 }} open, ${st.orders.count { it.status == OrderStatus.OPEN || it.status == OrderStatus.TRIGGER_PENDING }} working")
            }
            val l = live
            val p = paper
            val w = working
            anythingOpen = if (l == null || p == null || w == null) null
                else l.any { it.open } || w > 0 || p.positions.any { it.quantity != 0 } ||
                    p.orders.any { it.status == OrderStatus.OPEN || it.status == OrderStatus.TRIGGER_PENDING }
            r.step(Group.POSITIONS, "Every open position's stop watched", Budgets.READ_MS) {
                if (l == null || p == null) return@step LiveCheck.fail("a position book could not be read")
                val prot = Protections.active()
                val now = System.currentTimeMillis()
                fun age(at: Long) = if (at <= 0L) null else (now - at).coerceAtLeast(0L)
                val held = ArrayList<LiveCheck.Held>()
                for (pos in l.filter { it.open }) {
                    val it0 = prot.lastOrNull { it.live && it.symbol == pos.symbol && it.exchange == pos.exchange }
                    held += LiveCheck.Held(paper = false, stop = it0?.stop != null, target = it0?.target != null, checkedAgoMs = age(src.stopsCheckedAt()))
                }
                for (pos in p.positions.filter { it.quantity != 0 }) {
                    val it0 = prot.lastOrNull { !it.live && it.symbol == pos.symbol }
                    val exitSide = if (pos.quantity > 0) "SELL" else "BUY"
                    val resting = p.orders.filter { it.symbol == pos.symbol && it.action == exitSide &&
                        (it.status == OrderStatus.OPEN || it.status == OrderStatus.TRIGGER_PENDING) }
                    val stop = it0?.stop != null || resting.any { it.priceType == "SL-M" || it.priceType == "SL" }
                    val target = it0?.target != null || resting.any { it.priceType == "LIMIT" }
                    held += LiveCheck.Held(paper = true, stop = stop, target = target, checkedAgoMs = age(src.paperCheckedAt()))
                }
                if (!open && held.isNotEmpty()) return@step LiveCheck.closed("market closed: ${held.size} open, checked at full pace in market hours")
                LiveCheck.watched(held)
            }
        }

        // ---- the paper round trip on live prices -------------------------------------------------------------------------

        suspend fun paperRoundTrip() {
            val names = listOf("Paper buy at the live price", "Paper fill", "Stop and target attached", "Paper position closed")
            if (!open) { names.forEach { r.step(Group.PAPER, it, 0) { LiveCheck.closed() } }; return }
            val c0 = ce
            if (c0 == null) { names.forEach { r.step(Group.PAPER, it, 0) { LiveCheck.skipped("no ATM option") } }; return }
            val contract = Paper.Contract(Paper.symbolOf(Upstox.Contract("NIFTY", c0.expiry, c0.strike, Right.CE, c0.lotSize, "kite:${c0.token}", c0.tradingSymbol)),
                "NIFTY", c0.expiry, c0.strike, Right.CE, c0.lotSize, "kite:${c0.token}")
            val engine = Paper.scratchEngine(contract)
            val key = Sandbox.key(contract.symbol, "NFO")
            var state = engine.newState(Market.now())
            var buyId: String? = null
            var exits: List<String> = emptyList()

            suspend fun liveQuote(): com.optionslab.engine.sandbox.Quote {
                val q = Broker.quotes(listOf("NFO:${c0.tradingSymbol}"))["NFO:${c0.tradingSymbol}"]?.takeIf { it.last > 0 }
                    ?: error("no live price for the ATM CE")
                return com.optionslab.engine.sandbox.Quote(q.last, bid = q.bid ?: 0.0, ask = q.ask ?: 0.0)
            }

            val placed = r.step(Group.PAPER, names[0], Budgets.READ_MS + Budgets.PAPER_PLACE_MS) {
                val out = engine.place(state, OrderRequest(contract.symbol, "NFO", "BUY", contract.lotSize, "MARKET", "NRML", strategy = SELF_TEST), liveQuote(), Market.now())
                state = out.state
                if (!out.result.ok) return@step LiveCheck.fail(LiveCheck.clean(out.result.message ?: "refused"))
                buyId = out.result.orderId
                LiveCheck.pass("1 lot, on the check's own paper book (never saved)")
            }
            val filled = r.step(Group.PAPER, names[1], Budgets.PAPER_FILL_MS, 10_000) {
                val id = buyId ?: return@step LiveCheck.skipped(if (placed.ok) "no order" else "the buy was not placed")
                val t0 = System.currentTimeMillis()
                var said: LiveCheck.Said? = null
                while (said == null) {
                    val row = engine.orderStatus(state, id)
                    said = when {
                        row == null -> LiveCheck.fail("the order is gone")
                        row.status == OrderStatus.COMPLETE -> LiveCheck.pass("filled on the live price after ${System.currentTimeMillis() - t0} ms")
                        row.status == OrderStatus.REJECTED || row.status == OrderStatus.CANCELLED -> LiveCheck.fail("the paper order was ${row.status}")
                        else -> null
                    }
                    if (said == null) {
                        // A MARKET buy fills at once on a fresh price; one held back (a stale quote) fills on the next live one.
                        // Once a second (review, 18 Oct): a quote not on the stream is a REST read, and Kite allows about one a second.
                        delay(1_000)
                        state = engine.onQuotes(state, mapOf(key to liveQuote()), Market.now()).state
                    }
                }
                said
            }
            r.step(Group.PAPER, names[2], Budgets.PAPER_ATTACH_MS) {
                val id = buyId?.takeIf { filled.verdict == LiveCheck.Verdict.PASS || filled.verdict == LiveCheck.Verdict.WARN }
                    ?: return@step LiveCheck.skipped("nothing filled to protect")
                val avg = engine.orderStatus(state, id)?.averagePrice?.takeIf { it > 0 } ?: error("no fill price")
                val stopAt = Kite.onTick(avg * 0.7, c0.tickSize, Kite.Side.SELL)
                val targetAt = Kite.onTick(avg * 1.5, c0.tickSize, Kite.Side.SELL)
                val stop = engine.place(state, OrderRequest(contract.symbol, "NFO", "SELL", contract.lotSize, "SL-M", "NRML", triggerPrice = stopAt, strategy = SELF_TEST), liveQuote(), Market.now())
                state = stop.state
                val target = engine.place(state, OrderRequest(contract.symbol, "NFO", "SELL", contract.lotSize, "LIMIT", "NRML", price = targetAt, strategy = SELF_TEST), liveQuote(), Market.now())
                state = target.state
                exits = listOfNotNull(stop.result.orderId, target.result.orderId)
                if (!stop.result.ok) return@step LiveCheck.fail("the stop was refused: ${LiveCheck.clean(stop.result.message ?: "")}")
                if (!target.result.ok) return@step LiveCheck.fail("the target was refused: ${LiveCheck.clean(target.result.message ?: "")}")
                // One pass of the engine on the live price: both must still rest (neither is anywhere near the price).
                state = engine.onQuotes(state, mapOf(key to liveQuote()), Market.now()).state
                val s1 = engine.orderStatus(state, stop.result.orderId!!)?.status
                val s2 = engine.orderStatus(state, target.result.orderId!!)?.status
                if (s1 != OrderStatus.TRIGGER_PENDING) return@step LiveCheck.fail("the stop is not resting (it is $s1)")
                if (s2 != OrderStatus.OPEN) return@step LiveCheck.fail("the target is not resting (it is $s2)")
                LiveCheck.pass("the stop rests as an SL-M (trigger pending), the target as a LIMIT (open)")
            }
            r.step(Group.PAPER, names[3], Budgets.PAPER_CLOSE_MS) {
                if (buyId == null || engine.orderStatus(state, buyId!!)?.status != OrderStatus.COMPLETE) return@step LiveCheck.skipped("nothing filled to close")
                for (id in exits) state = engine.cancel(state, id, Market.now()).state
                val q = liveQuote()
                val out = engine.closePosition(state, contract.symbol, "NFO", "NRML", q, Market.now())
                state = out.state
                if (!out.result.ok) return@step LiveCheck.fail("the close was refused: ${LiveCheck.clean(out.result.message ?: "")}")
                state = engine.onQuotes(state, mapOf(key to q), Market.now()).state
                val held = state.positions.filter { it.symbol == contract.symbol }.sumOf { it.quantity }
                val resting = state.orders.count { it.status == OrderStatus.OPEN || it.status == OrderStatus.TRIGGER_PENDING }
                when {
                    held != 0 -> LiveCheck.fail("still holding after the close")
                    resting != 0 -> LiveCheck.fail("$resting orders still resting after the close")
                    else -> LiveCheck.pass("flat: both exits cancelled and the position closed")
                }
            }
        }

        // ---- data feeds and the clock -------------------------------------------------------------------------------------

        suspend fun feeds(loggedIn: Boolean) {
            r.step(Group.FEEDS, "Zerodha login", Budgets.BUILD_MS) {
                if (!loggedIn) return@step LiveCheck.fail("not logged in today")
                val ends = Broker.expiresAt()
                val hours = ends?.let { java.time.Duration.between(java.time.Instant.now(), it.toInstant()).toHours() }
                if (hours != null && hours < 0) LiveCheck.fail("today's login has ended") else LiveCheck.pass("valid${hours?.let { ", for about $it more hours" } ?: ""}")
            }
            fun ageOf(bars: List<Upstox.Bar>): Long? = bars.filter { it.istDate == Market.today() }.maxByOrNull { it.epochSecond }
                ?.let { com.optionslab.engine.sandbox.PaperSpread.ageSeconds(it.epochSecond, Market.now().toEpochSecond()) }
            r.step(Group.FEEDS, "Zerodha 1-minute candles (NIFTY)", Budgets.READ_MS) {
                if (!open) return@step LiveCheck.closed()
                if (!loggedIn) return@step LiveCheck.skipped("not logged in")
                LiveCheck.age(ageOf(Broker.indexMinuteBars("NIFTY", Market.today())), "Zerodha")
            }
            r.step(Group.FEEDS, "Upstox public 1-minute candles (NIFTY)", Budgets.READ_MS * 2) {
                if (!open) return@step LiveCheck.closed()
                LiveCheck.age(ageOf(src.upstoxBars(Upstox.INDEX_KEYS.getValue("NIFTY"))), "Upstox")
            }
            r.step(Group.FEEDS, "Dhan", 0) { LiveCheck.skipped("not read: the app takes its market data from Zerodha and Upstox only") }
        }

        suspend fun clock() {
            r.step(Group.CLOCK, "Phone clock against the exchange", Budgets.BUILD_MS) {
                val got = src.clockOffset()
                LiveCheck.drift(got?.first, got?.second ?: "nothing to measure against yet: needs a Zerodha reply or a streamed tick")
            }
        }
    }

    /** "10 s", or "300 ms" for a wait that is not whole seconds. */
    private fun time(ms: Long): String = if (ms % 1000 == 0L) "${ms / 1000} s" else "$ms ms"

    /** Zerodha's own error text is already free of keys and URLs; anything else is named by its type only. */
    private fun reason(e: Throwable): String = when (e) {
        is Broker.KiteError, is Broker.NotLoggedIn, is IllegalStateException -> e.message ?: e.javaClass.simpleName
        is java.net.UnknownHostException -> "no internet connection"
        is java.net.SocketTimeoutException -> "Zerodha did not answer in time"
        else -> e.javaClass.simpleName
    }

    // ---- the automatic 09:20 run (its switch off by default) ---------------------------------------------------------------

    private val autoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The automatic run's switch (the settings vault; read off the screen's thread). */
    fun autoOn(): Boolean = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean(KEY_AUTO, false) }.getOrDefault(false)

    /** Turned on or off from the card (written in the background, never on the screen's thread). */
    fun setAuto(on: Boolean) {
        runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(KEY_AUTO to on)) }
        runCatching { Diag.record(LiveCheck.AREA, "the 09:20 automatic run turned ${if (on) "on" else "off"}") }
    }

    /**
     * From a background worker of the watch (never the order or tick path): at 09:20-09:35 on a trading day, once, with its
     * switch on, the check starts on its own thread and this returns at once. Read-only plus the paper round trip; no forced
     * stream drop. A run with a failure leaves a notice to look at the card.
     */
    fun autoMaybe() {
        if (com.optionslab.app.BuildConfig.GOLD || running.get()) return
        val now = Market.now()
        val minute = now.hour * 60 + now.minute
        // Battery: outside the window nothing is read, not even the settings vault.
        if (minute !in LiveCheck.AUTO_FROM_MIN until LiveCheck.AUTO_UNTIL_MIN) return
        val day = now.toLocalDate()
        val done = runCatching { com.optionslab.app.security.SecurePrefs.getString(KEY_AUTO_DONE)?.let { java.time.LocalDate.parse(it) } }.getOrNull()
        if (!LiveCheck.autoDue(autoOn(), Market.isTradingDay(day), minute, done, day)) return
        runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(KEY_AUTO_DONE to day.toString())) }
        autoScope.launch {
            val out = runCatching { run(auto = true) }.getOrDefault(emptyList())
            val bad = out.count { it.verdict == LiveCheck.Verdict.FAIL }
            if (bad > 0) runCatching {
                com.optionslab.app.work.Alerts.post("Live check at 09:20: $bad failed. More → Zerodha → Live check shows which.",
                    com.optionslab.app.work.Alerts.Kind.ERROR, throttle = true)
            }
        }
    }

    /** Jarvis's answer to "what did the live check find?": this session's last run with its failures, else the diary's summary. */
    fun lastAnswer(lines: List<String>): String =
        last.takeIf { it.isNotEmpty() }?.let { LiveCheck.answer(it) }
            ?: LiveCheck.lastSummary(lines)?.let { "$it, Boss." }
            ?: "The live check has not run yet, Boss: say \"run the live check\" in market hours."

    /** The diagnostics' line: the last run's summary (this run's, else the diary's). */
    fun diagLine(lines: List<String>): String =
        last.takeIf { it.isNotEmpty() }?.let { "Live check (last run in this session): ${LiveCheck.summary(it)}" }
            ?: LiveCheck.lastSummary(lines) ?: "Live check: not run yet"
}
