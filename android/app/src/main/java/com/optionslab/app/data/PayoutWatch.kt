package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.risk.AccountGuard
import com.optionslab.ira.PayoutDrawdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 21 Oct, enhancement round 4 (idea 3): the payout-aware drawdown for the account guard's kill switch ([PayoutDrawdown] has the
 * why). Every margins read ([Broker.funds]) hands Kite's day payout here; each is kept with the drawdown peak that stood then.
 * When the guard refuses a live entry for a drawdown from the peak ([Guard]), the payouts made since that peak are counted:
 *  - In SHADOW (the default, Bot settings' switch off): what it would say is logged (once a day). Nothing else changes.
 *  - On: the refusal says so too, and Bot settings offers Boss to lower the peak by those payouts, with the PIN. It never turns the
 *    kill switch off, and never keeps it from going on by itself: a live change may only lower risk (CLAUDE.md).
 *
 * The guard's check reads memory only here (the send path: no disk, no settings, no network). The payouts are recorded and written
 * in the background.
 */
object PayoutWatch {
    private const val K_ON = "g.payoutDrawdown.on"
    private const val K_LIST = "g.payoutDrawdown.payouts"
    /** 21 Oct, enhancement round 5 (idea 5): each day's opening balance and the next one expected ([PayoutDrawdown.Balance]). */
    private const val K_BAL = "g.payoutDrawdown.balances"
    /** The guard's live peak, as [Guard] keeps it. */
    private const val K_PEAK = "guard.peak.live"

    @Volatile private var on = false
    @Volatile private var loaded = false
    @Volatile private var switched = false
    @Volatile private var payouts: List<PayoutDrawdown.Payout> = emptyList()
    @Volatile private var balances: List<PayoutDrawdown.Balance> = emptyList()
    /** 21 Oct, round 5: the last time payouts would have explained a drawdown that the next opening balance has not confirmed. */
    @Volatile private var lastWaiting: String? = null
    /** The last drawdown judged with payouts, with its peak and equity (review: one snapshot, never pieces of two calls). */
    private data class Last(val j: PayoutDrawdown.Judged?, val peak: Double, val equity: Double)
    @Volatile private var lastSnap = Last(null, Double.NaN, Double.NaN)
    private val last: PayoutDrawdown.Judged? get() = lastSnap.j
    /** The day it was said (once a day in the diary). */
    @Volatile private var saidDay: java.time.LocalDate? = null
    private val lock = Any()
    /**
     * 26 Oct: bumped by [wipe] (under [lock]; read without it, so the send path takes no lock): a read or a save begun before the
     * erase never brings the old payouts back.
     */
    @Volatile private var wipeGen = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [offer]: the payouts Bot settings offers to lower the peak by (on, and they alone explain the limit reached); null: none. */
    data class View(val on: Boolean = false, val line: String = "", val offer: Double? = null)
    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) { scope.launch { load() } }

    private fun today(): java.time.LocalDate = runCatching { Market.today() }.getOrElse { java.time.LocalDate.now(com.optionslab.engine.IST) }

    /** The switch and the kept payouts from the settings (a worker thread). Payouts seen before this read are kept. */
    internal fun load() {
        val gen = wipeGen
        val ok = runCatching {
            val saved = SecurePrefs.getBoolean(K_ON, false)
            val list = PayoutDrawdown.decode(SecurePrefs.getString(K_LIST))
            val bals = PayoutDrawdown.decodeBalances(SecurePrefs.getString(K_BAL))
            synchronized(lock) {
                // 26 Oct: an erase since this read began: what was read is the old book, dropped.
                if (gen != wipeGen) return@runCatching
                if (!switched) on = saved
                var merged = list
                for (p in payouts) merged = PayoutDrawdown.record(merged, p.day, p.amount, p.peak)
                payouts = merged
                // 21 Oct, round 5: balances read before this are laid over the saved ones (a day's first opening kept).
                var mb = bals
                for (b in balances) mb = PayoutDrawdown.recordBalance(mb, b.day, b.opening, b.expectedNext)
                balances = mb
            }
        }.isSuccess
        if (ok) loaded = true
        publish()
    }

    fun isOn(): Boolean = on

    fun refresh() { if (!loaded) load() else publish() }

    /** Boss's switch (Bot settings). On only adds words and the offer; it never changes what the guard refuses. */
    fun setOn(on: Boolean): String {
        this.on = on
        switched = true
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        runCatching { Diag.record("risk", "Payout-aware drawdown ${if (on) "on: payouts since the peak are said, and lowering the peak by them is offered (PIN)" else "in shadow: logged only"} (by you)") }
        publish()
        return if (on) "On: when a drawdown stops entries, the payouts since the peak are counted; if they alone explain it, Bot settings " +
            "offers to lower the peak by them (with your PIN). The kill switch is never cleared by itself."
        else "Shadow: what it would say is logged only."
    }

    /**
     * Kite's payout for today from a margins read ([amount]: utilised.payout). Returns at once: recorded with the peak that stands
     * now, and written, on a worker thread (this may run on the send path).
     */
    fun seen(amount: Double, opening: Double = Double.NaN, expectedNext: Double = Double.NaN) {
        val day = today()
        // 21 Oct, enhancement round 5 (idea 5): the day's balances, to confirm tomorrow that a payout really left ([PayoutDrawdown.left]).
        // Review: only reads on a trading day after Zerodha's morning run (08:30 IST): a read past midnight or on a holiday can
        // still show the day before's opening balance, which would never confirm a payout.
        val gen = wipeGen
        if (((opening.isFinite() && opening > 0.0) || expectedNext.isFinite()) && balanceReadCounts()) scope.launch {
            runCatching {
                if (!loaded) load()
                synchronized(lock) {
                    if (gen != wipeGen) return@runCatching      // 26 Oct: read before an erase

                    balances = PayoutDrawdown.recordBalance(balances, day, opening, expectedNext)
                    // Review: written under the lock, so two reads close together never save in reverse order.
                    if (loaded) SecurePrefs.putAllSoon(mapOf(K_BAL to PayoutDrawdown.encodeBalances(balances)))
                }
            }
        }
        // Review: a 0 read is kept too when the day has a payout (a request cancelled the same day removes it).
        if (!amount.isFinite() || amount < 0.0) return
        if (amount == 0.0 && payouts.none { it.day == day }) return
        scope.launch {
            runCatching {
                if (!loaded) load()
                val peak = SecurePrefs.getDouble(K_PEAK, Double.NaN)
                synchronized(lock) {
                    if (gen != wipeGen) return@runCatching      // 26 Oct: read before an erase
                    payouts = PayoutDrawdown.record(payouts, day, amount, peak)
                    // 26 Oct: queued under the lock (a queue, never a wait), so an erase cannot fall between the check and the save.
                    if (loaded) SecurePrefs.putAllSoon(mapOf(K_LIST to PayoutDrawdown.encode(payouts)))
                }
                publish()
            }
        }
    }

    /**
     * The guard refused a live entry for a drawdown from the peak ([a], the limit [limitPct]). Memory only. Logged once a day when
     * payouts alone explain it; the words for the refusal when on (null otherwise, and in shadow).
     */
    /** A margins read's balances count: a trading day, at or after 08:30 IST (after Zerodha's start-of-day run). */
    private fun balanceReadCounts(): Boolean = runCatching {
        val t = Market.now()
        Market.isTradingDay(t.toLocalDate()) && t.toLocalTime() >= java.time.LocalTime.of(8, 30)
    }.getOrDefault(false)

    fun drawdown(a: AccountGuard.Account, limitPct: Double): String? = runCatching {
        val d = today()
        // 21 Oct, enhancement round 5 (idea 5): only payouts the next day's opening balance shows leaving count (confirmed); those
        // not seen leaving, or not read yet, are said and never offered (a payout cancelled after its day's last read never left).
        val split = PayoutDrawdown.paidOutSplit(payouts, balances, a.peakEquity, d)
        if (split.all <= 0.0) { lastWaiting = null; return@runCatching null }
        val j = PayoutDrawdown.judge(a.peakEquity, a.equity, split.confirmed, limitPct)
        lastSnap = Last(j, a.peakEquity, a.equity)
        val wait = PayoutDrawdown.waitingWords(PayoutDrawdown.judge(a.peakEquity, a.equity, split.all, limitPct), j, split, on)
        lastWaiting = wait
        val w = (if (split.confirmed > 0.0) PayoutDrawdown.words(j, on) else null) ?: wait
        if (w == null) { publish(); return@runCatching null }
        if (saidDay != d) { saidDay = d; runCatching { Diag.record("risk", "Payout-aware drawdown: $w") } }
        publish()
        if (on) w else null
    }.getOrNull()

    /**
     * Boss confirmed (the PIN, [pinConfirmed]): the live drawdown peak lowered by the payouts that alone explain the last drawdown
     * judged, never under that equity; those payouts are then used. The kill switch is left as it is (Boss clears it). A worker thread.
     */
    fun lowerPeak(pinConfirmed: Boolean): String {
        if (!pinConfirmed) return "Enter your PIN to lower the drawdown peak."
        if (!on) return "Switch the payout-aware drawdown on first."
        val snap = lastSnap
        val j = snap.j?.takeIf { it.explained } ?: return "No drawdown explained by payouts to count."
        val peak = SecurePrefs.getDouble(K_PEAK, Double.NaN)
        if (!peak.isFinite() || kotlin.math.abs(peak - snap.peak) >= 0.5) return "The peak has changed since; nothing was lowered."
        val next = PayoutDrawdown.lowered(peak, j.paidOut, snap.equity)
        Guard.setPeak(live = true, next)
        val text = synchronized(lock) {
            payouts = PayoutDrawdown.used(payouts, peak, today())
            PayoutDrawdown.encode(payouts)
        }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_LIST to text)) }
        lastSnap = Last(null, Double.NaN, Double.NaN); lastWaiting = null
        runCatching { Diag.record("risk", "Drawdown peak lowered by payouts of Rs %,.0f: %,.0f to %,.0f (by you, with the PIN)".format(j.paidOut, peak, next)) }
        publish()
        return "The drawdown peak is now Rs %,.0f (lowered by payouts of Rs %,.0f). The kill switch is as it was: clear it yourself if you agree.".format(next, j.paidOut)
    }

    /** The diagnostics' and Bot settings' line. */
    fun line(): String {
        val n = payouts.size
        // 21 Oct, enhancement round 5 (idea 5): how many of them the next day's opening balance showed leaving.
        val bals = balances
        val by = payouts.groupingBy { PayoutDrawdown.left(it, bals) }.eachCount()
        val check = if (n == 0) "" else " (left, by the next opening balance: confirmed ${by[PayoutDrawdown.Left.CONFIRMED] ?: 0}, " +
            "not seen ${by[PayoutDrawdown.Left.NOT_SEEN] ?: 0}, not read yet ${by[PayoutDrawdown.Left.UNKNOWN] ?: 0})"
        val head = "Payout-aware drawdown: ${if (on) "on" else "shadow (logged only)"}; payouts kept $n$check"
        val w = PayoutDrawdown.words(last, on) ?: lastWaiting
        return if (w == null) head else "$head; $w"
    }

    private fun publish() {
        runCatching {
            val j = last
            _view.value = View(on, line(), if (on && j != null && j.explained) j.paidOut else null)
        }
    }

    /**
     * 26 Oct ("Erase everything", [PersonalStores]): the payouts, balances and last judgement dropped from memory, the switch back
     * to shadow; the saves already queued go with the settings' own wipe, and one begun before this never writes the old book
     * ([wipeGen]). Read again from the (empty) settings when next needed. Nothing is read here.
     */
    fun wipe() {
        synchronized(lock) { wipeGen++; payouts = emptyList(); balances = emptyList(); on = false; switched = false; loaded = false }
        lastSnap = Last(null, Double.NaN, Double.NaN); saidDay = null; lastWaiting = null
        _view.value = View()
    }

    /** 26 Oct: payouts or balances in memory (the erase's test). Memory only. */
    internal fun holdsForErase(): Boolean = payouts.isNotEmpty() || balances.isNotEmpty() || lastSnap.j != null || lastWaiting != null

    /** TEST ONLY: shadow, nothing kept. */
    internal fun resetForTest(on: Boolean = false) {
        this.on = on; loaded = true; switched = false
        synchronized(lock) { payouts = emptyList(); balances = emptyList() }
        lastSnap = Last(null, Double.NaN, Double.NaN); saidDay = null; lastWaiting = null
        _view.value = View(on)
    }

    /** TEST ONLY: a day's balances kept as if read ([PayoutDrawdown.recordBalance]). */
    internal fun keepBalanceForTest(day: java.time.LocalDate, opening: Double, expectedNext: Double) {
        synchronized(lock) { balances = PayoutDrawdown.recordBalance(balances, day, opening, expectedNext) }
    }

    /** TEST ONLY: the balances kept. */
    internal fun balancesForTest(): List<PayoutDrawdown.Balance> = balances

    /** TEST ONLY: the payouts kept. */
    internal fun payoutsForTest(): List<PayoutDrawdown.Payout> = payouts

    /** TEST ONLY: a payout kept as if seen on [day] under [peak]. */
    internal fun keepForTest(day: java.time.LocalDate, amount: Double, peak: Double) {
        synchronized(lock) { payouts = PayoutDrawdown.record(payouts, day, amount, peak) }
    }
}
