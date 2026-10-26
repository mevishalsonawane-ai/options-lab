package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.AccountStop
import com.optionslab.ira.LiquidityStopRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 20 Oct, enhancement round 1 (idea 4): Boss's account stop rule (-Rs 40,000 from the best, -Rs 13,000 a calendar month) over the
 * WHOLE Zerodha account ([AccountStop]): each trading day's figure is Zerodha's day P&L as the daily loss limit last read it
 * ([LossBreaker]). Before, only the arms' live path ([ArmLive]) and the ORB family checked it, over their own trades.
 *
 *  - It only blocks a bot's NEW live entry (the entry door, [Broker]: ORB and Liquidity, Pine, the arms' live trades) and an
 *    automatic scheduled live strategy start ([Strategies]); never an exit, never Boss's own order, never a size.
 *  - In SHADOW by default (CLAUDE.md: anything new that could skip a live trade starts logged only, behind a switch that is off):
 *    what it would refuse is recorded, nothing changes, until Boss switches it on in Bot settings.
 *  - Every other switch stays as it is: the kill switch, the daily loss limit, the day lock and the arms' own rule all still apply.
 *
 * The door reads only memory here (no settings read, no disk, no network: OrderPathRound2Test stays 0). The days are read once
 * in the background and written in the background, at most once a minute.
 */
object AccountStopHost {
    private const val K_DAYS = "accountStop.days"
    private const val K_ON = "g.accountStop.on"
    private const val K_SINCE = "g.accountStop.since"
    private const val K_REACHED = "g.accountStop.reached"
    private const val WRITE_EVERY_MS = 60_000L

    @Volatile private var on = false
    @Volatile private var since: java.time.LocalDate? = null
    /** The day and reason the rule was first reached (kept: it stays reached until Boss restarts the count). */
    @Volatile private var reached: Pair<java.time.LocalDate, String>? = null
    @Volatile private var loaded = false
    private val lock = Any()
    private var days: List<AccountStop.Day> = emptyList()
    @Volatile private var status: LiquidityStopRule.Status = AccountStop.status(emptyList(), java.time.LocalDate.now())
    @Volatile private var writtenAt = 0L
    /** Today's would-be refusals in shadow ("owner|symbol"), once each; the day they are for. */
    private val shadowSeen = HashSet<String>()
    @Volatile private var shadowDay: java.time.LocalDate? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class View(val on: Boolean = false, val line: String = "", val reached: Boolean = false)
    private val _view = MutableStateFlow(View())
    /** Bot settings' row. */
    val view: StateFlow<View> = _view

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) { scope.launch { load() } }

    private fun today(): java.time.LocalDate = runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() }

    /**
     * The switch and the days read from the settings (a worker thread). Days recorded before this read are kept. Review: [loaded]
     * only once the read succeeded (a vault not ready yet is read again on the next ask), and nothing is written before then, so
     * a failed read never writes today's figure alone over the saved history.
     */
    internal fun load() {
        val gen = erased
        val ok = runCatching {
            // 26 Oct: each part read before an erase ([wipe]) is not taken after it.
            val savedOn = SecurePrefs.getBoolean(K_ON, false)
            if (gen == erased) on = savedOn
            val savedSince = SecurePrefs.getString(K_SINCE)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            if (gen == erased) since = savedSince
            val r = AccountStop.decodeReached(SecurePrefs.getString(K_REACHED))
            val saved = AccountStop.decode(SecurePrefs.getString(K_DAYS))
            synchronized(lock) {
                if (gen != erased) return@synchronized
                if (reached == null) reached = r
                // A day recorded in memory before the read wins over the saved one (it is newer).
                days = (saved.filter { s -> days.none { it.day == s.day } } + days).sortedBy { it.day }.takeLast(AccountStop.KEEP)
                status = compute(today())
            }
        }.isSuccess
        if (ok) loaded = true
        publish()
    }

    /** The rule now, held reached once reached ([AccountStop.latched]); the first time it is reached, kept. Under [lock]. */
    private fun compute(d: java.time.LocalDate): LiquidityStopRule.Status {
        val st = AccountStop.status(days, d, since)
        if (st.stopped && reached == null) {
            val r = (st.day ?: d) to (st.why ?: "reached")
            reached = r
            if (loaded) runCatching { SecurePrefs.putAllSoon(mapOf(K_REACHED to AccountStop.encodeReached(r))) }
        }
        return AccountStop.latched(st, reached, since)
    }

    /**
     * Zerodha's day P&L as just read ([m2m]; the daily loss limit's own read, in the watch pass), less today's [charges] (null:
     * not known, the figure before charges as before). Recorded for today on a trading
     * day once the market has opened; written in the background at most once a minute (and at once when the rule is reached),
     * and only once the saved days were read.
     */
    fun dayRead(m2m: Double, now: Long = System.currentTimeMillis(), charges: Double? = null) {
        runCatching {
            // 20 Oct, enhancement round 2 (idea 6): less the day's charges ([AccountStop.afterCharges]).
            val net = AccountStop.afterCharges(m2m, charges) ?: return
            if (!Market.isTradingDay() || Market.minuteNow() < Market.OPEN) return
            // The watch pass, never the order path: the saved days are read here first if the start's read has not landed.
            if (!loaded) load()
            val d = today()
            val (was, st) = synchronized(lock) {
                val was = status.stopped
                days = AccountStop.record(days, d, net)
                status = compute(d)
                was to status
            }
            if (st.stopped && !was) {
                runCatching { Diag.record("risk", "Account stop rule reached (${st.why}): " +
                    if (on) "the bots' new live entries are refused" else "in shadow, nothing changes (Bot settings switches it on)") }
            }
            if (loaded && ((st.stopped && !was) || now - writtenAt >= WRITE_EVERY_MS)) {
                writtenAt = now
                val (text, gen) = synchronized(lock) { AccountStop.encode(days) to erased }
                val r = reached
                // 26 Oct: days copied before an erase ([wipe]) are never written after it.
                scope.launch { if (gen == erased) runCatching { SecurePrefs.putAllSoon(mapOf(K_DAYS to text, K_REACHED to AccountStop.encodeReached(r))) }; publish() }
            }
        }
    }

    /**
     * Inside the entry door, for a bot's new live entry ([owner]: its arm, else its tag), or an automatic live strategy start:
     * the refusal when the rule is reached and the switch is on; in shadow (the default) the refusal it would give is only
     * recorded (once a day per owner and contract) and null is returned. Memory only; never throws.
     */
    fun doorCheck(symbol: String, owner: String): String? = runCatching {
        val st = status
        val why = AccountStop.refusal(st) ?: return@runCatching null
        if (on) return@runCatching why
        val d = today()
        val first = synchronized(lock) {
            if (shadowDay != d) { shadowDay = d; shadowSeen.clear() }
            shadowSeen.add("$owner|$symbol")
        }
        if (first) runCatching { Diag.record("risk", "$owner $symbol: $why (live, shadow: logged only, nothing changed)") }
        null
    }.getOrNull()

    /** 25 Oct: the Zerodha days recorded (the live weekly loss stop's shadow, [BossRiskHost]). Memory only. */
    fun daysNow(): List<AccountStop.Day> = synchronized(lock) { days }

    /** Today's would-be refusals in shadow. */
    private fun shadowToday(): Int = synchronized(lock) { if (shadowDay == today()) shadowSeen.size else 0 }

    /** The diagnostics' and Bot settings' line (this month's figure as of today). */
    fun line(): String = AccountStop.line(synchronized(lock) { AccountStop.latched(AccountStop.status(days, today(), since), reached, since) }, on, shadowToday())

    /**
     * Boss restarts the count after the rule was reached (Bot settings, with his PIN - the caller asks it): it counts again from
     * the next trading day. Never by itself and never by voice.
     */
    fun restart(pinConfirmed: Boolean): String {
        if (!pinConfirmed) return "Restarting the account stop rule's count takes your PIN."
        if (!status.stopped) return "The account stop rule is not reached: nothing to restart."
        val from = AccountStop.restartFrom(today())
        since = from
        synchronized(lock) { reached = null; status = AccountStop.status(days, today(), from) }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_SINCE to from.toString(), K_REACHED to null)) }
        runCatching { Diag.record("risk", "Account stop rule: count restarted by you with your PIN (from $from)") }
        publish()
        return "The account stop rule counts again from $from (-Rs 40,000 from the best, -Rs 13,000 a month)."
    }

    /** Boss's switch (off by default: in shadow). On only ever refuses the bots' entries; never Boss's own, never an exit. */
    fun setOn(on: Boolean): String {
        this.on = on
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        runCatching { Diag.record("risk", "Account stop rule ${if (on) "on: the bots' new live entries are refused once it is reached" else "in shadow: logged only"} (by you)") }
        publish()
        return if (on) "Account stop rule on: once the account is -Rs 40,000 from its best or -Rs 13,000 in a month, the bots take no new live entry (your own orders and every exit still go)."
        else "Account stop rule in shadow: what it would refuse is logged, nothing changes."
    }

    /** Re-reads what Bot settings shows (the screen opening; a worker thread). */
    fun refresh() { if (!loaded) load() else publish() }

    private fun publish() {
        runCatching { _view.value = View(on, line(), status.stopped) }
    }

    /** 26 Oct: bumped by [wipe] (under [lock]): a read or a write of the days from before it is dropped. */
    @Volatile private var erased = 0

    /**
     * 26 Oct: "Erase everything" ([PersonalStores]): the switch, the days, the reached day, the restart and today's shadow refusals
     * forgotten; counted as read (the settings keys are gone with [SecurePrefs.wipe], never read here).
     */
    fun wipe() {
        synchronized(lock) {
            erased++
            on = false; since = null; reached = null; loaded = true; writtenAt = 0L
            days = emptyList(); shadowSeen.clear(); shadowDay = null; status = AccountStop.status(emptyList(), today())
        }
        _view.value = View()
    }

    /** 26 Oct: whether any day, the switch or a reached rule is still in memory (the erase's tests). Memory only. */
    internal fun holdsForErase(): Boolean = on || since != null || reached != null || synchronized(lock) { days.isNotEmpty() || shadowSeen.isNotEmpty() }

    /** TEST ONLY: as a fresh start before the saved days were read. */
    internal fun unloadForTest() { loaded = false; synchronized(lock) { days = emptyList(); reached = null; status = AccountStop.status(emptyList(), today()) } }

    /** TEST ONLY: off, no days, nothing seen. */
    internal fun resetForTest() {
        on = false; since = null; reached = null; loaded = true; writtenAt = 0L
        synchronized(lock) { days = emptyList(); shadowSeen.clear(); shadowDay = null; status = AccountStop.status(emptyList(), today()) }
        _view.value = View()
    }
}
