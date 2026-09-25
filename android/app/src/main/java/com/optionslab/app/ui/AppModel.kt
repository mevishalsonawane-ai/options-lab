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

data class Account(val funds: com.optionslab.app.data.Broker.Funds?, val positions: List<com.optionslab.app.data.Broker.Position>, val orders: List<com.optionslab.app.data.Broker.OrderRow>)

/** Orders awaiting the owner's decision, with every gate's verdict attached. */
data class OrderPlan(
    val title: String,
    val session: LocalDate?,
    val legs: List<com.optionslab.engine.Kite.Order>,
    val quotes: Map<String, com.optionslab.app.data.Broker.Quote>,
    val refusals: List<List<String>>,
    val holdToSettlement: Boolean,
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
                    if (live) livePositions.value = runCatching { com.optionslab.app.data.Broker.positions() }.getOrDefault(livePositions.value)
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

    fun loadAccount() {
        account.value = Load.Busy("Reading your Zerodha account")
        viewModelScope.launch(Dispatchers.IO) {
            account.value = try {
                val b = com.optionslab.app.data.Broker
                Load.Done(Account(runCatching { b.funds() }.getOrNull(), b.positions(), b.orders()))
            } catch (e: Exception) {
                broker.value = brokerState()
                Load.Failed(e.message ?: "could not read the account")
            }
        }
    }

    private fun gate(legs: List<com.optionslab.engine.Kite.Order>, hold: Boolean): List<List<String>> {
        val s = _settings.value
        val sent = com.optionslab.app.data.Broker.sentToday()
        return legs.mapIndexed { i, o -> com.optionslab.engine.Kite.refusals(o, s.limits(), sent + i, hold) }
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
        plan.value = Load.Done(cur.copy(legs = legs, refusals = gate(legs, cur.holdToSettlement)))
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
        if (Integrity.compromised(integrity.value)) { say("Refused: this device shows signs of compromise, so no real order is sent from it."); return }
        val again = gate(cur.legs, cur.holdToSettlement)
        if (again.any { it.isNotEmpty() }) { plan.value = Load.Done(cur.copy(refusals = again)); return }
        sending.value = Load.Busy("Sending to Zerodha")
        viewModelScope.launch(Dispatchers.IO) {
            val b = com.optionslab.app.data.Broker
            val fills = ArrayList<com.optionslab.app.data.Broker.Fill>()
            try {
                for ((i, leg) in cur.legs.withIndex()) {
                    sending.value = Load.Busy("Leg ${i + 1} of ${cur.legs.size}: ${leg.side} ${leg.tradingSymbol}")
                    val id = b.placeOrder(leg)
                    val f = b.awaitOrder(id)
                    fills += f
                    if (f.status != "COMPLETE" || f.filled < leg.quantity) {
                        sending.value = Load.Failed(
                            "Leg ${i + 1} ${f.status.lowercase()}${if (f.message.isNotBlank()) ": ${f.message}" else ""}. " +
                                if (i + 1 < cur.legs.size) "The remaining leg was NOT sent. Check Orders on the Zerodha page." else "Check Orders on the Zerodha page.")
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

    fun cancelOrder(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try { com.optionslab.app.data.Broker.cancel(id); say("Cancel requested for ${id.takeLast(6)}.") } catch (e: Exception) { say("Cancel failed: ${e.message}") }
            loadAccount()
        }
    }
}
