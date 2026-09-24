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
                    val q = listOf("NIFTY", "BANKNIFTY", "INDIAVIX").mapNotNull { runCatching { Market.quote(it) }.getOrNull() }
                    if (q.isNotEmpty()) { quotes.value = q.associateBy { it.symbol }; quoteNote.value = null }
                    else quoteNote.value = if (Market.isOpen()) "No prints yet - the feed may be slow." else "Market closed. Showing nothing rather than a stale print."
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
}
