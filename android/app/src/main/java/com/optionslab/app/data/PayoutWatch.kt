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
    /** The guard's live peak, as [Guard] keeps it. */
    private const val K_PEAK = "guard.peak.live"

    @Volatile private var on = false
    @Volatile private var loaded = false
    @Volatile private var switched = false
    @Volatile private var payouts: List<PayoutDrawdown.Payout> = emptyList()
    /** The last drawdown judged with payouts, and the day it was said (once a day in the diary). */
    @Volatile private var last: PayoutDrawdown.Judged? = null
    @Volatile private var lastPeak = Double.NaN
    @Volatile private var lastEquity = Double.NaN
    @Volatile private var saidDay: java.time.LocalDate? = null
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [offer]: the payouts Bot settings offers to lower the peak by (on, and they alone explain the limit reached); null: none. */
    data class View(val on: Boolean = false, val line: String = "", val offer: Double? = null)
    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) { scope.launch { load() } }

    private fun today(): java.time.LocalDate = runCatching { Market.today() }.getOrElse { java.time.LocalDate.now(com.optionslab.engine.IST) }

    /** The switch and the kept payouts from the settings (a worker thread). Payouts seen before this read are kept. */
    internal fun load() {
        val ok = runCatching {
            val saved = SecurePrefs.getBoolean(K_ON, false)
            val list = PayoutDrawdown.decode(SecurePrefs.getString(K_LIST))
            if (!switched) on = saved
            synchronized(lock) {
                var merged = list
                for (p in payouts) merged = PayoutDrawdown.record(merged, p.day, p.amount, p.peak)
                payouts = merged
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
    fun seen(amount: Double) {
        if (!amount.isFinite() || amount <= 0.0) return
        val day = today()
        scope.launch {
            runCatching {
                if (!loaded) load()
                val peak = SecurePrefs.getDouble(K_PEAK, Double.NaN)
                val text = synchronized(lock) {
                    payouts = PayoutDrawdown.record(payouts, day, amount, peak)
                    PayoutDrawdown.encode(payouts)
                }
                if (loaded) SecurePrefs.putAllSoon(mapOf(K_LIST to text))
                publish()
            }
        }
    }

    /**
     * The guard refused a live entry for a drawdown from the peak ([a], the limit [limitPct]). Memory only. Logged once a day when
     * payouts alone explain it; the words for the refusal when on (null otherwise, and in shadow).
     */
    fun drawdown(a: AccountGuard.Account, limitPct: Double): String? = runCatching {
        val d = today()
        val paid = PayoutDrawdown.paidOutSince(payouts, a.peakEquity, d)
        if (paid <= 0.0) return@runCatching null
        val j = PayoutDrawdown.judge(a.peakEquity, a.equity, paid, limitPct)
        last = j; lastPeak = a.peakEquity; lastEquity = a.equity
        val w = PayoutDrawdown.words(j, on) ?: return@runCatching null
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
        val j = last?.takeIf { it.explained } ?: return "No drawdown explained by payouts to count."
        val peak = SecurePrefs.getDouble(K_PEAK, Double.NaN)
        if (!peak.isFinite() || kotlin.math.abs(peak - lastPeak) >= 0.5) return "The peak has changed since; nothing was lowered."
        val next = PayoutDrawdown.lowered(peak, j.paidOut, lastEquity)
        Guard.setPeak(live = true, next)
        val text = synchronized(lock) {
            payouts = PayoutDrawdown.used(payouts, peak, today())
            PayoutDrawdown.encode(payouts)
        }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_LIST to text)) }
        last = null
        runCatching { Diag.record("risk", "Drawdown peak lowered by payouts of Rs %,.0f: %,.0f to %,.0f (by you, with the PIN)".format(j.paidOut, peak, next)) }
        publish()
        return "The drawdown peak is now Rs %,.0f (lowered by payouts of Rs %,.0f). The kill switch is as it was: clear it yourself if you agree.".format(next, j.paidOut)
    }

    /** The diagnostics' and Bot settings' line. */
    fun line(): String {
        val n = payouts.size
        val head = "Payout-aware drawdown: ${if (on) "on" else "shadow (logged only)"}; payouts kept $n"
        val w = PayoutDrawdown.words(last, on)
        return if (w == null) head else "$head; $w"
    }

    private fun publish() {
        runCatching {
            val j = last
            _view.value = View(on, line(), if (on && j != null && j.explained) j.paidOut else null)
        }
    }

    /** TEST ONLY: shadow, nothing kept. */
    internal fun resetForTest(on: Boolean = false) {
        this.on = on; loaded = true; switched = false
        synchronized(lock) { payouts = emptyList() }
        last = null; lastPeak = Double.NaN; lastEquity = Double.NaN; saidDay = null
        _view.value = View(on)
    }

    /** TEST ONLY: the payouts kept. */
    internal fun payoutsForTest(): List<PayoutDrawdown.Payout> = payouts

    /** TEST ONLY: a payout kept as if seen on [day] under [peak]. */
    internal fun keepForTest(day: java.time.LocalDate, amount: Double, peak: Double) {
        synchronized(lock) { payouts = PayoutDrawdown.record(payouts, day, amount, peak) }
    }
}
