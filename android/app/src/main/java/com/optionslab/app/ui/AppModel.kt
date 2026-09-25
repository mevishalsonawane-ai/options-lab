package com.optionslab.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.optionslab.app.data.Alarms
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Ledger
import com.optionslab.app.data.Market
import com.optionslab.app.data.PriceAlarm
import com.optionslab.app.data.Store
import com.optionslab.app.security.Integrity
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.work.Jobs
import com.optionslab.app.work.Tasks
import com.optionslab.engine.BacktestReport
import com.optionslab.engine.Costs
import com.optionslab.engine.ExpiryPut
import com.optionslab.engine.Ic
import com.optionslab.engine.LinReg
import com.optionslab.engine.Manifest
import com.optionslab.engine.Monitor
import com.optionslab.engine.Provenance
import com.optionslab.engine.SignalBacktest
import com.optionslab.engine.Summary
import com.optionslab.engine.UtBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data class Busy(val label: String, val progress: Float = -1f) : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val why: String) : Load<Nothing>
}

/** One arm of the comparison: a named variant of the strategy. */
data class Arm(val name: String, val note: String, val params: ExpiryPut.Params)

data class ArmResult(val arm: Arm, val summary: Summary?, val skipped: Int, val curve: List<Double>)

data class HealthResult(val source: String, val window: List<Monitor.Row>, val total: Int, val checks: List<Monitor.Check>) {
    val verdict: Monitor.Status get() = Monitor.verdict(checks)
}

data class BrokerState(val configured: Boolean, val loggedIn: Boolean, val user: String?, val expires: java.time.ZonedDateTime?, val maskedKey: String)

data class Account(
    val funds: com.optionslab.app.data.Broker.Funds?,
    val book: com.optionslab.app.data.Broker.Positions,
    val orders: List<com.optionslab.app.data.Broker.OrderRow>,
    val trades: List<com.optionslab.app.data.Broker.Trade>,
    val holdings: List<com.optionslab.app.data.Broker.Holding>,
    val at: java.time.ZonedDateTime = Market.now(),
) {
    val positions: List<com.optionslab.app.data.Broker.Position> get() = book.net
}

/** Orders awaiting the owner's decision, with every gate's verdict attached. */
data class OrderPlan(
    val title: String,
    val session: LocalDate?,
    val legs: List<com.optionslab.engine.Kite.Order>,
    val quotes: Map<String, com.optionslab.app.data.Broker.Quote>,
    val refusals: List<List<String>>,
    val holdToSettlement: Boolean,
    /** Closing orders: each only reduces a position you already hold. */
    val exit: Boolean = false,
) {
    val sendable: Boolean get() = legs.isNotEmpty() && refusals.all { it.isEmpty() }
}

data class SignalResult(
    val underlying: String, val day: LocalDate, val indicator: String,
    val bars: SignalBacktest.Bars, val buys: BooleanArray, val sells: BooleanArray,
    val trades: List<SignalBacktest.Trade>, val expiry: LocalDate?,
)

class AppModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    private val _settings = MutableStateFlow(AppSettings.load())
    val settings: StateFlow<AppSettings> = _settings

    val backtest = MutableStateFlow<Load<BacktestReport>>(Load.Idle)
    val arms = MutableStateFlow<Load<List<ArmResult>>>(Load.Idle)
    val health = MutableStateFlow<Load<HealthResult>>(Load.Idle)
    val quotes = MutableStateFlow<Map<String, Market.Quote>>(emptyMap())
    val quoteNote = MutableStateFlow<String?>(null)
    val livePositions = MutableStateFlow<List<com.optionslab.app.data.Broker.Position>>(emptyList())
    val pnlSeries = MutableStateFlow<List<com.optionslab.app.data.PnlTracker.Point>>(emptyList())
    val ledger = MutableStateFlow<List<Ledger.Entry>>(emptyList())
    val alarms = MutableStateFlow<List<PriceAlarm>>(emptyList())
    val draft = MutableStateFlow<Load<Market.TicketDraft>>(Load.Idle)
    val openMark = MutableStateFlow<Double?>(null)
    val ic = MutableStateFlow<Load<Ic.IcResult>>(Load.Idle)
    val signal = MutableStateFlow<Load<SignalResult>>(Load.Idle)
    val integrity = MutableStateFlow<List<Integrity.Finding>>(emptyList())
    val provenance = MutableStateFlow<Load<List<Provenance.Drift>>>(Load.Idle)
    val message = MutableStateFlow<String?>(null)
    val jobState: StateFlow<Tasks.LiveState> = Tasks.state

    init {
        refreshLedger()
        refreshAlarms()
        viewModelScope.launch(Dispatchers.IO) { integrity.value = Integrity.report(ctx) }
        viewModelScope.launch(Dispatchers.IO) { pnlSeries.value = com.optionslab.app.data.PnlTracker.today() }
    }

    fun say(text: String) { message.value = text }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        viewModelScope.launch(Dispatchers.IO) {
            AppSettings.save(next)
            Jobs.scheduleAll(ctx)
        }
    }

    // ---- the backtest ---------------------------------------------------------

    fun runBacktest(force: Boolean = false) {
        if (!force && backtest.value is Load.Busy) return
        val s = _settings.value
        backtest.value = Load.Busy("Reading 170 expiry sessions", 0f)
        viewModelScope.launch(Dispatchers.Default) {
            backtest.value = try {
                if (force) Store.invalidate()
                Load.Done(Store.backtest(s) { done, total -> backtest.value = Load.Busy("Settling session $done of $total", done.toFloat() / total) })
            } catch (e: Exception) {
                Load.Failed(e.message ?: "the backtest failed")
            }
            val h = health.value
            if (h !is Load.Done || h.value.source == "backtest") runHealth("backtest")
        }
    }

    val defaultArms = listOf(
        Arm("Naked 0.75%", "the published headline: quoted spread, lot 65", ExpiryPut.Params()),
        Arm("Naked 1.00%", "the 100% cell: roll spread, never billed a loss", ExpiryPut.Params(otmPct = 0.01, regime = "roll")),
        Arm("Hedged 0.75% + wing", "put spread, wing 0.75% wider, dated lots", ExpiryPut.Params(wingPct = 0.0075, lot = ExpiryPut.LotChoice.Dated)),
        Arm("Naked, dated lots", "each session at its own NSE lot", ExpiryPut.Params(lot = ExpiryPut.LotChoice.Dated)),
        Arm("Naked, stress spread", "sizing beyond top-of-book depth", ExpiryPut.Params(regime = "stress")),
    )

    /** Every arm over one pass of the data - the chains are decoded once. */
    fun runArms(extra: Arm? = null) {
        if (arms.value is Load.Busy) return
        val list = defaultArms + listOfNotNull(extra)
        val s = _settings.value
        arms.value = Load.Busy("Running ${list.size} arms", 0f)
        viewModelScope.launch(Dispatchers.Default) {
            arms.value = try {
                val trades = list.map { ArrayList<ExpiryPut.Trade>() }
                val skips = IntArray(list.size)
                var n = 0
                for (session in Store.expirySessions(s.includeDeviceSessions)) {
                    list.forEachIndexed { i, arm ->
                        val (t, k) = ExpiryPut.runBacktest(listOf(session), arm.params)
                        trades[i] += t; skips[i] += k.size
                    }
                    n++
                    arms.value = Load.Busy("Session $n: ${session.day}", n / 170f)
                }
                Load.Done(list.mapIndexed { i, arm ->
                    val sorted = trades[i].sortedBy { it.session }
                    var acc = 0.0
                    ArmResult(arm, Summary.of(arm.name, sorted, skips[i]), skips[i], sorted.map { acc += it.netPnl; acc })
                })
            } catch (e: Exception) {
                Load.Failed(e.message ?: "the comparison failed")
            }
        }
    }

    // ---- health ---------------------------------------------------------------

    private var imported: List<Monitor.Row>? = null

    fun runHealth(source: String) {
        val s = _settings.value
        health.value = Load.Busy("Checking the kill conditions")
        viewModelScope.launch(Dispatchers.Default) {
            health.value = try {
                val rows = when (source) {
                    "paper" -> Ledger.settledRows()
                    "imported" -> imported ?: emptyList()
                    else -> Monitor.rows(Store.backtest(s).all)
                }
                if (rows.isEmpty()) Load.Failed(
                    if (source == "paper") "No settled paper trades yet. No trades is not the same as no problems."
                    else "empty trade ledger; no trades is not the same as no problems")
                else {
                    // Never hand the dated sentinel to the lot check: use the lot the window actually traded.
                    val pinned = if (s.datedLot || source != "backtest") null else s.pinnedLot
                    val (window, checks) = Monitor.healthOf(rows, s.healthLast, s.otmPct, pinned)
                    SecurePrefs.put("health.verdict", Monitor.verdict(checks).name)
                    Load.Done(HealthResult(source, window, rows.size, checks))
                }
            } catch (e: Exception) {
                Load.Failed(e.message ?: "the health check failed")
            }
        }
    }

    /** `regime --ledger`: a trade CSV written by the PC's `expiry-put --out`. */
    fun importLedger(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("could not read the file")
                val lines = text.trim().lines()
                val head = lines.first().split(",").map { it.trim() }
                fun col(name: String) = head.indexOf(name).also { require(it >= 0) { "the CSV has no '$name' column" } }
                val iS = col("session"); val iK = col("strike"); val iC = col("credit"); val iT = col("settlement")
                val iF = col("forward"); val iL = col("lot_size")
                val iI = head.indexOf("intrinsic"); val iO = head.indexOf("otm_realised")
                imported = lines.drop(1).filter { it.isNotBlank() }.map { l ->
                    val c = l.split(",")
                    Monitor.Row(LocalDate.parse(c[iS].take(10)), c[iK].toDouble(), c[iC].toDouble(), c[iT].toDouble(), c[iF].toDouble(),
                        c[iL].toDouble().toInt(), c.getOrNull(iI)?.toDoubleOrNull(), c.getOrNull(iO)?.toDoubleOrNull())
                }
                runHealth("imported")
            } catch (e: Exception) {
                health.value = Load.Failed("That file is not a trade ledger: ${e.message}")
            }
        }
    }

    // ---- live -----------------------------------------------------------------

    private var quoteLoop: Job? = null

    /** Poll while the Almanac is on screen; every 30 s in market hours. */
    fun startQuotes() {
        if (quoteLoop?.isActive == true) return
        quoteLoop = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val live = _settings.value.live
                    if (live && !com.optionslab.app.data.Broker.loggedIn) {
                        // Live means Zerodha. With no session there is nothing live to show,
                        // and another feed's numbers would be a different thing wearing the label.
                        quotes.value = emptyMap(); livePositions.value = emptyList()
                        quoteNote.value = "LIVE mode: log in to Zerodha (Cabinet → Zerodha) to see live prices."
                        delay(15_000)
                        continue
                    }
                    var err: String? = null
                    val q = listOf("NIFTY", "BANKNIFTY", "INDIAVIX").mapNotNull { sym ->
                        try { Market.quote(sym) } catch (e: Exception) { err = e.message; null }
                    }
                    quotes.value = q.associateBy { it.symbol }
                    quoteNote.value = when {
                        q.isNotEmpty() -> if (live) null else "SANDBOX: public Upstox candles, not your broker."
                        err != null -> err
                        Market.isOpen() -> "No prints yet - the feed may be slow."
                        else -> "Market closed. Showing nothing rather than a stale print."
                    }
                    if (live) runCatching { com.optionslab.app.data.Broker.positionBook() }.onSuccess { book ->
                        livePositions.value = book.net
                        trackPnl(book)
                    }
                    Ledger.openTicket()?.let { openMark.value = runCatching { Market.markOpenTicket(it) }.getOrNull() }
                    Tasks.checkAlarms(ctx, quotes.value.mapValues { it.value.last }, HashSet())
                } catch (e: Exception) {
                    quoteNote.value = e.message
                }
                delay(if (Market.isOpen()) 30_000 else 300_000)
            }
        }
    }

    fun stopQuotes() { quoteLoop?.cancel(); quoteLoop = null }

    fun draftTicket() {
        draft.value = Load.Busy("Pricing the live chain")
        viewModelScope.launch(Dispatchers.IO) {
            draft.value = try { Load.Done(Market.draftTicket(_settings.value)) } catch (e: Exception) { Load.Failed(e.message ?: "could not price the chain") }
        }
    }

    fun recordDraft() {
        val d = (draft.value as? Load.Done<Market.TicketDraft>)?.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Ledger.record(d.ticket, d.shortKey, d.wingKey)
                refreshLedger()
                say("Ticket recorded as PAPER. Nothing was sent - place it yourself if you want it.")
            } catch (e: Exception) { say("Not recorded: ${e.message}") }
        }
    }

    fun settle(session: LocalDate, manualSettlement: Double?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val e = Ledger.all().first { it.row.ticket.session == session }
                val price = manualSettlement ?: Market.settlement(e.row.ticket.underlying, session)
                val r = Ledger.settle(session, price, _settings.value.regime)
                refreshLedger()
                val tr = r.row.trade!!
                say("$session settled at %,.1f - net Rs %+,.0f (%s)".format(price, tr.netPnl, if (tr.won) "WIN" else "LOSS"))
            } catch (e: Exception) { say("Cannot settle: ${e.message}") }
        }
    }

    fun deleteTicket(session: LocalDate) {
        viewModelScope.launch(Dispatchers.IO) { Ledger.delete(session); refreshLedger() }
    }

    fun refreshLedger() { viewModelScope.launch(Dispatchers.IO) { ledger.value = Ledger.all() } }

    fun exportLedger(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(Ledger.csv().toByteArray()) }
                say("Ledger exported. That file is NOT encrypted - keep it somewhere private.")
            } catch (e: Exception) { say("Export failed: ${e.message}") }
        }
    }

    fun exportBacktest(uri: Uri) {
        val r = (backtest.value as? Load.Done<BacktestReport>)?.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = buildString {
                    append("session,strike,credit,settlement,intrinsic,lot_size,lots,qty,wing_strike,wing_debit,gross_pnl,cost,net_pnl,won,forward,otm_realised\n")
                    for (t in r.all) append(listOf(t.session, t.strike, t.credit, t.settlement, t.intrinsic, t.lotSize, t.lots, t.qty,
                        t.wingStrike ?: "", t.wingDebit, t.grossPnl, t.cost, t.netPnl, if (t.won) "True" else "False", t.forward, t.otmRealised).joinToString(",")).append('\n')
                }
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                say("Backtest ledger exported (${r.all.size} trades).")
            } catch (e: Exception) { say("Export failed: ${e.message}") }
        }
    }

    // ---- alarms ---------------------------------------------------------------

    fun refreshAlarms() { viewModelScope.launch(Dispatchers.IO) { alarms.value = Alarms.all().sortedBy { it.id } } }
    fun saveAlarm(a: PriceAlarm) { viewModelScope.launch(Dispatchers.IO) { Alarms.upsert(a); refreshAlarms() } }
    fun removeAlarm(id: Long) { viewModelScope.launch(Dispatchers.IO) { Alarms.remove(id); refreshAlarms() } }

    // ---- research ---------------------------------------------------------------

    fun runIc(underlying: String, regime: String, completeOnly: Boolean) {
        ic.value = Load.Busy("Measuring signed flow against the cost bar")
        viewModelScope.launch(Dispatchers.Default) {
            ic.value = try {
                val allowed = if (completeOnly) Store.manifest(underlying).filter { it.scope == Manifest.SAME_DAY }.map { it.session }.toSet() else null
                var days = Store.barSessions(underlying)
                if (allowed != null) days = days.filter { it.day in allowed }
                val total = Store.barDays(underlying).size.coerceAtLeast(1)
                Load.Done(Ic.measure(underlying, days, regime) { n -> ic.value = Load.Busy("Session $n", n.toFloat() / total) })
            } catch (e: Exception) {
                Load.Failed(e.message ?: "nothing to measure")
            }
        }
    }

    fun runSignal(underlying: String, day: LocalDate, indicator: String, keyValue: Double, atrPeriod: Int, length: Int, lot: Int) {
        signal.value = Load.Busy("Replaying $day")
        viewModelScope.launch(Dispatchers.Default) {
            signal.value = try {
                val sess = Store.barSession(underlying, day) ?: error("no partition for $day")
                val ix = sess.index ?: error("$day has no index bars")
                val bars = SignalBacktest.Bars(ix.minutes, ix.open ?: ix.close, ix.high ?: ix.close, ix.low ?: ix.close, ix.close)
                val (b, s) = when (indicator) {
                    "linreg" -> LinReg.flipSignals(LinReg.rollingTrend(bars.close, length))
                    else -> UtBot.signals(bars.high, bars.low, bars.close, keyValue, atrPeriod)
                }
                val nearest = sess.options.mapNotNull { it.expiry }.filter { !it.isBefore(day) }.minOrNull()
                val chain = sess.options.filter { it.expiry == nearest }
                val trades = SignalBacktest.run(bars, chain, b, s, lot, 1, _settings.value.regime, underlying = underlying)
                Load.Done(SignalResult(underlying, day, indicator, bars, b, s, trades, nearest))
            } catch (e: Exception) {
                Load.Failed(e.message ?: "replay failed")
            }
        }
    }

    fun verifyProvenance() {
        provenance.value = Load.Busy("Re-reading all 170 chains")
        viewModelScope.launch(Dispatchers.Default) {
            provenance.value = try {
                val recorded = Provenance.parseCsv(Store.provenanceCsv())
                val drift = ArrayList<Provenance.Drift>()
                // Fingerprint each session as it streams; nothing is held.
                val have = HashMap<LocalDate, Provenance.Fingerprint>()
                var n = 0
                for (sess in Store.expirySessions(false)) {
                    have[sess.day] = Provenance.fingerprint(sess)
                    n++
                    provenance.value = Load.Busy("Session $n of ${recorded.size}", n.toFloat() / recorded.size)
                }
                val rec = recorded.associateBy { it.session }
                for ((day, r) in rec) {
                    val fp = have[day]
                    if (fp == null) drift += Provenance.Drift("missing", day, "$day is recorded but absent")
                    else if (fp.nRows != r.nRows || fp.nStrikes != r.nStrikes || fp.nSettlementBars != r.nSettlementBars)
                        drift += Provenance.Drift("changed", day, "$day no longer matches its recorded shape")
                }
                for (day in have.keys) if (day !in rec) drift += Provenance.Drift("unrecorded", day, "$day was never recorded")
                Load.Done(drift.sortedBy { it.session })
            } catch (e: Exception) {
                Load.Failed(e.message ?: "verification failed")
            }
        }
    }

    fun refreshIntegrity() { viewModelScope.launch(Dispatchers.IO) { integrity.value = Integrity.report(ctx) } }

    fun startJob(k: Jobs.Kind) = Jobs.start(ctx, k)
    fun stopLive() = Jobs.stopLive(ctx)

    fun costs(premium: Double, lot: Int, lots: Int, regime: String) = Triple(
        Costs.sellToSettle(premium, lot, lots, regime), Costs.buyToSettle(premium, lot, lots, regime), Costs.roundTrip(premium, lot, lots, regime),
    )
    // ---- Zerodha --------------------------------------------------------------------

    val broker = MutableStateFlow(brokerState())
    val account = MutableStateFlow<Load<Account>>(Load.Idle)
    val plan = MutableStateFlow<Load<OrderPlan>>(Load.Idle)
    val sending = MutableStateFlow<Load<List<com.optionslab.app.data.Broker.Fill>>>(Load.Idle)
    val showKiteLogin = MutableStateFlow(false)

    private fun brokerState() = com.optionslab.app.data.Broker.let {
        BrokerState(it.configured, it.loggedIn, it.userName, it.expiresAt(), it.maskedKey())
    }

    fun refreshBroker() { viewModelScope.launch(Dispatchers.IO) { broker.value = brokerState() } }

    /** Returns an error to show, or null when saved. */
    fun saveBrokerCredentials(key: String, secret: String, redirect: String): String? = try {
        com.optionslab.app.data.Broker.saveCredentials(key, secret, redirect)
        broker.value = brokerState()
        null
    } catch (e: IllegalArgumentException) { e.message }

    fun forgetBroker() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { com.optionslab.app.data.Broker.logout() }
            com.optionslab.app.data.Broker.forget()
            broker.value = brokerState(); account.value = Load.Idle
            say("Zerodha credentials erased from this phone.")
        }
    }

    fun startKiteLogin() { if (com.optionslab.app.data.Broker.configured) showKiteLogin.value = true else say("Add your API key, secret and redirect URL first.") }

    /** Called by the login page for every navigation; true means "stop, it was ours". */
    fun onKiteNavigation(url: String): Boolean {
        val registered = com.optionslab.app.data.Broker.redirect ?: return false
        return when (val r = com.optionslab.engine.Kite.readRedirect(url, registered)) {
            com.optionslab.engine.Kite.Redirect.NotOurs -> false
            is com.optionslab.engine.Kite.Redirect.Refused -> { showKiteLogin.value = false; say("Zerodha login did not complete: ${r.why}"); true }
            is com.optionslab.engine.Kite.Redirect.Token -> {
                showKiteLogin.value = false
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val who = com.optionslab.app.data.Broker.completeLogin(r.requestToken)
                        broker.value = brokerState()
                        // Warm the instrument list: the live expiry calendar reads it.
                        runCatching { com.optionslab.app.data.Broker.instruments() }
                        say("Logged in to Zerodha as $who until 06:00 tomorrow.")
                        loadAccount()
                    } catch (e: Exception) { say("Zerodha login failed: ${e.message}") }
                }
                true
            }
        }
    }

    fun brokerLogout() {
        viewModelScope.launch(Dispatchers.IO) {
            com.optionslab.app.data.Broker.logout()
            broker.value = brokerState(); account.value = Load.Idle
            say("Logged out of Zerodha.")
        }
    }

    /** [quiet]: a background refresh keeps the last figures on screen instead of a spinner. */
    fun loadAccount(quiet: Boolean = false) {
        if (!quiet || account.value !is Load.Done) account.value = Load.Busy("Reading your Zerodha account")
        viewModelScope.launch(Dispatchers.IO) {
            account.value = try {
                val b = com.optionslab.app.data.Broker
                val book = b.positionBook()
                trackPnl(book)
                livePositions.value = book.net
                Load.Done(Account(runCatching { b.funds() }.getOrNull(), book, b.orders(),
                    runCatching { b.trades() }.getOrDefault(emptyList()), runCatching { b.holdings() }.getOrDefault(emptyList())))
            } catch (e: Exception) {
                broker.value = brokerState()
                Load.Failed(e.message ?: "could not read the account")
            }
        }
    }

    private fun gate(legs: List<com.optionslab.engine.Kite.Order>, hold: Boolean, exit: Boolean = false): List<List<String>> {
        val s = _settings.value
        val sent = com.optionslab.app.data.Broker.sentToday()
        return legs.mapIndexed { i, o -> com.optionslab.engine.Kite.refusals(o, s.limits(), sent + i, hold, exit) }
    }

    /** The day's P&L curve: only sampled while you hold (or held today) something. */
    private fun trackPnl(book: com.optionslab.app.data.Broker.Positions) {
        if (book.net.isEmpty() && pnlSeries.value.isEmpty()) return
        pnlSeries.value = com.optionslab.app.data.PnlTracker.record(book.pnl)
    }

    // ---- closing what you hold ---------------------------------------------------------

    /** Square off one open position: the opposite side, the whole open quantity, for review. */
    fun planSquareOff(pos: com.optionslab.app.data.Broker.Position) = planExits("Square off ${pos.symbol}", listOf(pos))

    /**
     * Close every open position. Shorts are bought back FIRST: selling a
     * long hedge while its short is still open would leave a naked short.
     */
    fun planSquareOffAll() {
        val open = livePositions.value.filter { it.open }
        if (open.isEmpty()) { say("No open positions."); return }
        planExits("Square off all (${open.size})", open.sortedBy { if (it.qty < 0) 0 else 1 })
    }

    private fun planExits(title: String, positions: List<com.optionslab.app.data.Broker.Position>) {
        plan.value = Load.Busy("Pricing the exit on Zerodha")
        viewModelScope.launch(Dispatchers.IO) {
            plan.value = try {
                val b = com.optionslab.app.data.Broker
                val q = b.quotes(positions.map { "${it.exchange}:${it.symbol}" })
                val legs = positions.mapNotNull { ps ->
                    val qt = q["${ps.exchange}:${ps.symbol}"]
                    com.optionslab.engine.Kite.squareOff(b.spec(ps.exchange, ps.symbol), ps.product, ps.qty, qt?.bid, qt?.ask, qt?.last ?: ps.last)
                }
                if (legs.isEmpty()) error("nothing is open")
                Load.Done(OrderPlan(title, null, legs, q, gate(legs, false, exit = true), false, exit = true))
            } catch (x: Exception) { Load.Failed(x.message ?: "could not prepare the exit") }
        }
    }

    /** Sell (part of) a delivery holding. */
    fun planSellHolding(h: com.optionslab.app.data.Broker.Holding, quantity: Int) {
        plan.value = Load.Busy("Pricing the sale on Zerodha")
        viewModelScope.launch(Dispatchers.IO) {
            plan.value = try {
                val b = com.optionslab.app.data.Broker
                val key = "${h.exchange}:${h.symbol}"
                val q = b.quotes(listOf(key))
                val qt = q[key]
                val leg = com.optionslab.engine.Kite.squareOff(b.spec(h.exchange, h.symbol), "CNC", quantity.coerceIn(1, h.qty), qt?.bid, qt?.ask, qt?.last ?: h.last)
                    ?: error("nothing to sell")
                Load.Done(OrderPlan("Sell ${h.symbol} from holdings", null, listOf(leg), q, gate(listOf(leg), false, exit = true), false, exit = true))
            } catch (x: Exception) { Load.Failed(x.message ?: "could not prepare the sale") }
        }
    }

    /**
     * Just before an exit is sent: is every leg still closing something you
     * hold, and no more than you hold? A position closed elsewhere since the
     * review would otherwise turn the "exit" into a fresh position.
     */
    private suspend fun exitsStillValid(legs: List<com.optionslab.engine.Kite.Order>): String? {
        val b = com.optionslab.app.data.Broker
        val net = b.positionBook().net
        val held = if (legs.any { it.product == "CNC" }) b.holdings() else emptyList()
        for (o in legs) {
            if (o.product == "CNC") {
                val h = held.firstOrNull { it.symbol == o.tradingSymbol && it.exchange == o.exchange }
                if (o.side != com.optionslab.engine.Kite.Side.SELL || h == null || o.quantity > h.qty) return "${o.tradingSymbol}: you no longer hold ${o.quantity} to sell"
                continue
            }
            val ps = net.firstOrNull { it.symbol == o.tradingSymbol && it.exchange == o.exchange && it.product == o.product }
            val open = ps?.qty ?: 0
            val closes = (o.side == com.optionslab.engine.Kite.Side.BUY && open < 0) || (o.side == com.optionslab.engine.Kite.Side.SELL && open > 0)
            if (!closes || o.quantity > kotlin.math.abs(open)) return "${o.tradingSymbol} changed since the review (open now ${open}); review the exit again"
        }
        return null
    }

    // ---- changing a working order --------------------------------------------------------

    /** Modify a working order after the owner re-proved who they are. */
    fun modifyOrder(o: com.optionslab.app.data.Broker.OrderRow, quantity: Int, type: String, price: Double?, trigger: Double?) {
        val s = _settings.value
        if (!s.live || !s.allowRealOrders) { say("Real orders are off (Cabinet → Zerodha); a modify is a real order change."); return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (compromisedFresh()) error("this device shows signs of compromise")
                val b = com.optionslab.app.data.Broker
                val now = b.orders().firstOrNull { it.id == o.id } ?: error("the order is gone")
                if (!now.working) error("it is ${now.status.lowercase()} now, so it cannot be changed")
                val spec = b.spec(now.exchange, now.symbol)
                val probe = com.optionslab.engine.Kite.Order(now.symbol, com.optionslab.engine.Kite.Side.valueOf(now.side), quantity, spec.lotSize,
                    now.product, type, price, spec.tickSize, now.exchange, triggerPrice = trigger)
                // A modify is not a new order, so the daily count does not apply; every other gate does.
                val why = com.optionslab.engine.Kite.refusals(probe, s.limits(), 0, false)
                if (why.isNotEmpty()) error(why.joinToString("; "))
                if (quantity < now.filled) error("it has already filled ${now.filled}")
                b.modify(now, quantity, type, price, trigger)
                say("Modify sent for ${o.id.takeLast(6)}.")
            } catch (e: Exception) { say("Not modified: ${e.message}") }
            loadAccount()
        }
    }

    /** The strategy's ticket for [session], turned into Zerodha orders for review. */
    fun planTicket(e: Ledger.Entry) {
        plan.value = Load.Busy("Looking up the contracts on Zerodha")
        viewModelScope.launch(Dispatchers.IO) {
            plan.value = try {
                val b = com.optionslab.app.data.Broker
                val tk = e.row.ticket
                val ins = b.instruments()
                val short = b.find(ins, tk.underlying, tk.expiry, tk.strike, com.optionslab.engine.Right.PE)
                    ?: error("${tk.underlying} ${tk.expiry} ${com.optionslab.engine.fmtG(tk.strike)} PE is not listed on Zerodha")
                val wing = tk.wingStrike?.let { w -> b.find(ins, tk.underlying, tk.expiry, w, com.optionslab.engine.Right.PE) ?: error("wing strike ${com.optionslab.engine.fmtG(w)} PE is not listed") }
                val q = b.quotes(listOfNotNull(short, wing).map { "NFO:${it.tradingSymbol}" })
                val sq = q["NFO:${short.tradingSymbol}"]
                val wq = wing?.let { q["NFO:${it.tradingSymbol}"] }
                // Sell at the best bid, buy at the best offer: a limit that fills,
                // never a market order into an expiry-day book.
                val legs = com.optionslab.engine.Kite.legsFor(tk, short, wing, _settings.value.orderProduct,
                    sq?.bid ?: sq?.last ?: tk.credit, wq?.ask ?: wq?.last)
                Load.Done(OrderPlan("Today's ticket: ${tk.underlying} ${com.optionslab.engine.fmtG(tk.strike)} PE", tk.session, legs, q, gate(legs, true), true))
            } catch (x: Exception) { Load.Failed(x.message ?: "could not prepare the order") }
        }
    }

    /** A single order typed by hand on the Zerodha page. */
    fun planManual(underlying: String, expiry: LocalDate, strike: Double, right: com.optionslab.engine.Right,
                   side: com.optionslab.engine.Kite.Side, lots: Int, product: String, limit: Double?) {
        plan.value = Load.Busy("Looking up the contract")
        viewModelScope.launch(Dispatchers.IO) {
            plan.value = try {
                val b = com.optionslab.app.data.Broker
                val ins = b.find(b.instruments(), underlying, expiry, strike, right) ?: error("that contract is not listed")
                val q = b.quotes(listOf("NFO:${ins.tradingSymbol}"))
                val qt = q["NFO:${ins.tradingSymbol}"]
                val px = limit ?: (if (side == com.optionslab.engine.Kite.Side.SELL) qt?.bid else qt?.ask) ?: qt?.last ?: 0.0
                val o = com.optionslab.engine.Kite.Order(ins.tradingSymbol, side, lots * ins.lotSize, ins.lotSize, product, "LIMIT",
                    com.optionslab.engine.Kite.onTick(px, ins.tickSize, side), ins.tickSize)
                Load.Done(OrderPlan("${side.name} ${ins.tradingSymbol}", null, listOf(o), q, gate(listOf(o), false), false))
            } catch (x: Exception) { Load.Failed(x.message ?: "could not prepare the order") }
        }
    }

    fun setLegPrice(i: Int, price: Double) {
        val cur = (plan.value as? Load.Done<OrderPlan>)?.value ?: return
        val legs = cur.legs.mapIndexed { j, o -> if (j == i) o.copy(price = price) else o }
        plan.value = Load.Done(cur.copy(legs = legs, refusals = gate(legs, cur.holdToSettlement, cur.exit)))
    }

    fun dismissPlan() { plan.value = Load.Idle; sending.value = Load.Idle }

    /**
     * Send the reviewed plan. The UI calls this only after the hold-to-send
     * gesture AND a fresh PIN or fingerprint check. Every gate is re-run
     * here; legs go one at a time and the next is sent only once the previous
     * one has COMPLETELY filled, so a hedge can never leave a naked short.
     */
    fun sendPlan() {
        val cur = (plan.value as? Load.Done<OrderPlan>)?.value ?: return
        val s = _settings.value
        if (!s.live) { say("Switch to LIVE mode (Cabinet → Zerodha) to send real orders; sandbox mode never touches the broker."); return }
        if (!s.allowRealOrders) { say("Real orders are switched off. Turn them on under Cabinet → Zerodha."); return }
        val again = gate(cur.legs, cur.holdToSettlement, cur.exit)
        if (again.any { it.isNotEmpty() }) { plan.value = Load.Done(cur.copy(refusals = again)); return }
        sending.value = Load.Busy("Sending to Zerodha")
        viewModelScope.launch(Dispatchers.IO) {
            val b = com.optionslab.app.data.Broker
            val fills = ArrayList<com.optionslab.app.data.Broker.Fill>()
            try {
                if (compromisedFresh()) { sending.value = Load.Failed("Refused: this device shows signs of compromise, so no real order is sent from it."); return@launch }
                if (cur.exit) exitsStillValid(cur.legs)?.let { why -> sending.value = Load.Failed("Not sent: $why"); loadAccount(); return@launch }
                for ((i, leg) in cur.legs.withIndex()) {
                    sending.value = Load.Busy("Leg ${i + 1} of ${cur.legs.size}: ${leg.side} ${leg.tradingSymbol}")
                    val id = try {
                        b.placeOrder(leg)
                    } catch (e: com.optionslab.app.data.Broker.KiteError) {
                        throw e
                    } catch (e: com.optionslab.app.data.Broker.NotLoggedIn) {
                        throw e
                    } catch (e: Exception) {
                        // The reply was lost, not necessarily the order: look for it before saying "not sent".
                        runCatching { b.findRecent(leg, fills.map { it.orderId }) }.getOrNull()
                            ?: throw java.io.IOException("${e.message}. No matching order was found at Zerodha; check the order book before trying again.")
                    }
                    val f = runCatching { b.awaitOrder(id) }.getOrElse { com.optionslab.app.data.Broker.Fill(id, "UNKNOWN", 0.0, 0, "status not confirmed; check the order book") }
                    fills += f
                    if (f.status != "COMPLETE" || f.filled < leg.quantity) {
                        sending.value = Load.Failed(
                            "Leg ${i + 1} ${f.status.lowercase()}${if (f.message.isNotBlank()) ": ${f.message}" else ""}. " +
                                if (i + 1 < cur.legs.size) "The remaining legs were NOT sent. Check Orders on the Zerodha page." else "Check Orders on the Zerodha page.")
                        cur.session?.let { Ledger.attachOrders(it, fills.map { x -> x.orderId }, null, null) }
                        refreshLedger(); loadAccount()
                        return@launch
                    }
                }
                cur.session?.let { day ->
                    val short = fills.last().avgPrice
                    val wing = if (fills.size > 1) fills.first().avgPrice else null
                    Ledger.attachOrders(day, fills.map { it.orderId }, short, wing)
                }
                sending.value = Load.Done(fills)
                refreshLedger(); loadAccount()
                say("Filled: " + fills.joinToString(" · ") { "%s @ %.2f".format(it.orderId.takeLast(6), it.avgPrice) })
            } catch (e: Exception) {
                sending.value = Load.Failed("Not sent: ${e.message}")
                if (fills.isNotEmpty()) cur.session?.let { Ledger.attachOrders(it, fills.map { x -> x.orderId }, null, null) }
                loadAccount()
            }
        }
    }

    // ---- the strategy module ------------------------------------------------------------------

    val strategies = MutableStateFlow<List<com.optionslab.app.data.Strategies.Entry>>(emptyList())
    val strategyLog = MutableStateFlow<List<com.optionslab.app.data.Strategies.LogLine>>(emptyList())

    /**
     * The device check, run fresh right before anything is sent: a debugger or
     * hooking framework attached after launch must still stop an order.
     */
    private fun compromisedFresh(maxAgeMs: Long = 0): Boolean {
        val r = Integrity.reportWithin(ctx, maxAgeMs)
        integrity.value = r
        return Integrity.compromised(r)
    }

    fun refreshStrategies(tick: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val st = com.optionslab.app.data.Strategies
            if (tick) runCatching { st.tickAll(compromisedFresh(60_000)) }
            strategies.value = st.all()
            strategyLog.value = st.log()
        }
    }

    private fun strategyDo(block: suspend () -> String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try { block()?.let { say(it) } } catch (e: Exception) { say(e.message ?: "failed") }
            refreshStrategies()
        }
    }

    /** Returns through [onResult] the validator's message, or null when saved. */
    fun saveStrategy(def: com.optionslab.engine.strategy.StrategyDef, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val err = com.optionslab.app.data.Strategies.save(def)
            withContext(Dispatchers.Main) { onResult(err) }
            refreshStrategies()
        }
    }

    fun deleteStrategy(id: Long) = strategyDo { com.optionslab.app.data.Strategies.delete(id) ?: "Deleted." }
    fun setStrategyLive(id: Long, on: Boolean) = strategyDo { com.optionslab.app.data.Strategies.setLiveEnabled(id, on); null }

    /** Paper start, or a LIVE start after the UI's PIN/fingerprint check. */
    fun startStrategy(id: Long, live: Boolean) = strategyDo {
        val s = _settings.value
        if (live && (!s.live || !s.allowRealOrders)) return@strategyDo "A live run needs LIVE mode and real orders on (Cabinet → Zerodha)."
        val msg = com.optionslab.app.data.Strategies.start(id, if (live) com.optionslab.engine.strategy.RunMode.LIVE else com.optionslab.engine.strategy.RunMode.SANDBOX,
            "manual", confirmedByOwner = live, compromised = compromisedFresh())
        // A run's stops are only checked while something polls it: keep the watch running.
        if (com.optionslab.app.data.Strategies.anyRunning()) withContext(Dispatchers.Main) { Jobs.start(ctx, Jobs.Kind.LIVE) }
        msg
    }

    fun stopStrategy(id: Long) = strategyDo { com.optionslab.app.data.Strategies.stop(id, "manual", compromisedFresh()) }
    fun closeStrategyLeg(id: Long, legId: Int) = strategyDo { com.optionslab.app.data.Strategies.closeLeg(id, legId, compromisedFresh()) }

    // ---- portfolio and SIP backtesters -----------------------------------------------------

    data class PortfolioView(val result: com.optionslab.engine.portfolio.PortfolioResult, val sources: Set<String>)
    data class SipView(val result: com.optionslab.engine.portfolio.SipResult, val sources: Set<String>)
    data class AnalyzerView(val result: com.optionslab.engine.portfolio.AnalyzerResult, val sources: Set<String>)

    val portfolio = MutableStateFlow<Load<PortfolioView>>(Load.Idle)
    val sip = MutableStateFlow<Load<SipView>>(Load.Idle)
    val analyzer = MutableStateFlow<Load<AnalyzerView>>(Load.Idle)

    private fun failure(e: Exception) = when (e) {
        is com.optionslab.engine.portfolio.PortfolioException -> e.message ?: "refused"
        is com.optionslab.app.data.Net.Offline -> "No connection for price history."
        else -> e.message ?: "failed"
    }

    fun runPortfolio(holdings: List<com.optionslab.engine.portfolio.Holding>, start: LocalDate, end: LocalDate, benchmark: String?,
                     rebalance: String, capital: Double) {
        portfolio.value = Load.Busy("Reading price history")
        viewModelScope.launch(Dispatchers.IO) {
            portfolio.value = try {
                val h = com.optionslab.app.data.History.load(holdings.map { it.symbol.trim().uppercase() to it.exchange }, benchmark, start, end) {
                    portfolio.value = Load.Busy(it)
                }
                portfolio.value = Load.Busy("Backtesting ${holdings.size} holdings")
                val req = com.optionslab.engine.portfolio.PortfolioRequest(holdings, start, end, benchmark = benchmark, rebalance = rebalance,
                    initialCapital = capital, source = "api")
                Load.Done(PortfolioView(com.optionslab.engine.portfolio.PortfolioBacktest.run(req, h.bars), h.sources))
            } catch (e: Exception) { Load.Failed(failure(e)) }
        }
    }

    fun runSip(symbol: String, exchange: String, start: LocalDate, end: LocalDate, amount: Double, frequency: String, day: Int,
               stepUp: Double, benchmark: String?) {
        sip.value = Load.Busy("Reading price history")
        viewModelScope.launch(Dispatchers.IO) {
            sip.value = try {
                val sym = symbol.trim().uppercase()
                val h = com.optionslab.app.data.History.load(listOf(sym to exchange), benchmark, start, end) { sip.value = Load.Busy(it) }
                sip.value = Load.Busy("Running the SIP")
                val req = com.optionslab.engine.portfolio.SipRequest(sym, exchange, start, end, amount, frequency, day, stepUp, benchmark = benchmark)
                Load.Done(SipView(com.optionslab.engine.portfolio.SipBacktest.run(req, h.bars), h.sources))
            } catch (e: Exception) { Load.Failed(failure(e)) }
        }
    }

    /** IraAlgo's Portfolio Analyzer on your Zerodha holdings: today's weights, backtested over the last year. */
    fun analyzeHoldings() {
        analyzer.value = Load.Busy("Reading your holdings")
        viewModelScope.launch(Dispatchers.IO) {
            analyzer.value = try {
                val b = com.optionslab.app.data.Broker
                if (!b.loggedIn) error("Log in to Zerodha to analyse your holdings.")
                val held = b.holdings()
                if (held.isEmpty()) error("No delivery holdings on Zerodha.")
                val rows = held.map { mapOf("symbol" to it.symbol, "exchange" to it.exchange, "quantity" to (it.qty + it.t1).toDouble(),
                    "average_price" to it.avg, "last_price" to it.last, "pnl" to it.pnl, "product" to it.product) }
                val today = Market.today()
                val tradable = held.filter { it.exchange == "NSE" || it.exchange == "BSE" }.map { it.symbol to it.exchange }
                val h = com.optionslab.app.data.History.load(tradable, "NIFTY", today.minusDays(365), today) { analyzer.value = Load.Busy(it) }
                analyzer.value = Load.Busy("Analysing ${held.size} holdings")
                Load.Done(AnalyzerView(com.optionslab.engine.portfolio.PortfolioAnalyzer.analyze(rows, h.bars, today), h.sources))
            } catch (e: Exception) { Load.Failed(failure(e)) }
        }
    }

    // ---- options tools --------------------------------------------------------------------

    val tools = MutableStateFlow<Load<com.optionslab.engine.options.ChainSnapshot>>(Load.Idle)
    val toolsSource = MutableStateFlow("")

    /** Price the nearest-expiry chain (Zerodha in LIVE, Upstox in SANDBOX) and run every chain screen on it. */
    fun loadTools(underlying: String, quiet: Boolean = false) {
        if (!quiet || tools.value !is Load.Done) tools.value = Load.Busy("Pricing the $underlying chain")
        viewModelScope.launch(Dispatchers.IO) {
            tools.value = try {
                if (_settings.value.live && !com.optionslab.app.data.Broker.loggedIn) error("LIVE mode: log in to Zerodha for today to price the chain.")
                val lc = Market.liveChain(underlying, near = 12)
                val symbols = lc.contracts.associate { (it.strike to it.right) to it.tradingSymbol }
                val rows = com.optionslab.engine.options.ChainSnapshot.rowsFrom(lc.series, symbols, lc.lotSize)
                toolsSource.value = lc.source + (lc.pricedAt?.let { " · %02d:%02d".format(it / 60, it % 60) } ?: "")
                Load.Done(com.optionslab.engine.options.ChainSnapshot.of(underlying, lc.expiry, lc.spot, lc.lotSize, rows, Market.now()))
            } catch (e: Exception) { Load.Failed(e.message ?: "could not price the chain") }
        }
    }

    /**
     * A strategy's legs as Zerodha orders for review. Buys go first so every
     * short is covered by its wing before it is sold.
     */
    fun planBasket(title: String, underlying: String, expiry: LocalDate, legs: List<com.optionslab.engine.options.StrategyLeg>) {
        plan.value = Load.Busy("Looking up the contracts on Zerodha")
        viewModelScope.launch(Dispatchers.IO) {
            plan.value = try {
                val b = com.optionslab.app.data.Broker
                val ins = b.instruments()
                val found = legs.filter { it.active && it.strike != null && it.optionType != null }.map { l ->
                    val right = if (l.optionType == com.optionslab.engine.options.OptionType.CE) com.optionslab.engine.Right.CE else com.optionslab.engine.Right.PE
                    val i = b.find(ins, underlying, expiry, l.strike!!, right) ?: error("${com.optionslab.engine.fmtG(l.strike!!)} $right is not listed on Zerodha")
                    l to i
                }
                val q = b.quotes(found.map { "NFO:${it.second.tradingSymbol}" })
                val product = _settings.value.orderProduct
                val orders = found.sortedBy { if (it.first.side == com.optionslab.engine.options.Side.BUY) 0 else 1 }.map { (l, i) ->
                    val side = if (l.side == com.optionslab.engine.options.Side.BUY) com.optionslab.engine.Kite.Side.BUY else com.optionslab.engine.Kite.Side.SELL
                    val qt = q["NFO:${i.tradingSymbol}"]
                    val px = (if (side == com.optionslab.engine.Kite.Side.SELL) qt?.bid else qt?.ask) ?: qt?.last ?: l.price
                    com.optionslab.engine.Kite.Order(i.tradingSymbol, side, l.lots * i.lotSize, i.lotSize, product, "LIMIT",
                        com.optionslab.engine.Kite.onTick(px, i.tickSize, side), i.tickSize)
                }
                Load.Done(OrderPlan(title, null, orders, q, gate(orders, false), false))
            } catch (x: Exception) { Load.Failed(x.message ?: "could not prepare the basket") }
        }
    }

    /** The same legs as paper MARKET orders (sandbox), buys first. */
    fun paperBasket(underlying: String, expiry: LocalDate, legs: List<com.optionslab.engine.options.StrategyLeg>) = paperDo {
        var last = com.optionslab.app.data.Paper.Result(false, "no legs", emptyList())
        val tradable = legs.filter { it.active && it.strike != null && it.optionType != null }
        for (l in tradable.sortedBy { if (it.side == com.optionslab.engine.options.Side.BUY) 0 else 1 }) {
            val right = if (l.optionType == com.optionslab.engine.options.OptionType.CE) com.optionslab.engine.Right.CE else com.optionslab.engine.Right.PE
            val c = com.optionslab.app.data.Paper.contractFor(underlying, expiry, l.strike!!, right) ?: error("${com.optionslab.engine.fmtG(l.strike!!)} $right is not listed")
            last = com.optionslab.app.data.Paper.place(c, l.side.name, l.lots, "MARKET", "NRML", null, null)
            if (!last.ok) return@paperDo com.optionslab.app.data.Paper.Result(false, "Stopped at ${c.symbol}: ${last.message}", last.events)
        }
        last.copy(message = "Paper basket placed: ${tradable.size} legs")
    }

    // ---- the sandbox paper account ------------------------------------------------------

    val paper = MutableStateFlow<Load<com.optionslab.app.data.Paper.Snapshot>>(Load.Idle)

    fun loadPaper(quiet: Boolean = false) {
        if (!quiet || paper.value !is Load.Done) paper.value = Load.Busy("Opening the paper account")
        viewModelScope.launch(Dispatchers.IO) {
            paper.value = try {
                runCatching { com.optionslab.app.data.Paper.tick() }
                Load.Done(com.optionslab.app.data.Paper.snapshot())
            } catch (e: Exception) { Load.Failed(e.message ?: "could not read the paper account") }
        }
    }

    private fun paperDo(action: suspend () -> com.optionslab.app.data.Paper.Result) {
        viewModelScope.launch(Dispatchers.IO) {
            try { say(action().message) } catch (e: Exception) { say("Paper order failed: ${e.message}") }
            loadPaper(quiet = true)
        }
    }

    fun paperPlace(underlying: String, expiry: LocalDate, strike: Double, right: com.optionslab.engine.Right, action: String, lots: Int,
                   priceType: String, product: String, price: Double?, trigger: Double?) = paperDo {
        val c = com.optionslab.app.data.Paper.contractFor(underlying, expiry, strike, right)
            ?: error("$underlying ${expiry} ${com.optionslab.engine.fmtG(strike)} $right is not listed")
        com.optionslab.app.data.Paper.place(c, action, lots, priceType, product, price, trigger)
    }

    fun paperCancel(id: String) = paperDo { com.optionslab.app.data.Paper.cancel(id) }
    fun paperModify(id: String, qty: Int?, price: Double?, trigger: Double?) = paperDo { com.optionslab.app.data.Paper.modify(id, qty, price, trigger) }
    fun paperClose(symbol: String, product: String) = paperDo { com.optionslab.app.data.Paper.close(symbol, product) }

    fun paperReset(capital: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            com.optionslab.app.data.Paper.reset(java.math.BigDecimal.valueOf(capital).setScale(2))
            say("Paper account reset to ${rs(capital)}.")
            loadPaper()
        }
    }

    /** Listed expiries (Upstox master) for the paper order form. */
    suspend fun paperExpiries(underlying: String): List<LocalDate> = withContext(Dispatchers.IO) {
        runCatching { Market.contracts().filter { it.underlying == underlying && !it.expiry.isBefore(Market.today()) }.map { it.expiry }.distinct().sorted().take(6) }
            .getOrDefault(emptyList())
    }

    fun cancelOrder(id: String, variety: String = "regular") {
        viewModelScope.launch(Dispatchers.IO) {
            try { com.optionslab.app.data.Broker.cancel(id, variety); say("Cancel requested for ${id.takeLast(6)}.") } catch (e: Exception) { say("Cancel failed: ${e.message}") }
            loadAccount()
        }
    }
}
