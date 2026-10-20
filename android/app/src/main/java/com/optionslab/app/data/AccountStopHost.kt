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
    private const val WRITE_EVERY_MS = 60_000L

    @Volatile private var on = false
    @Volatile private var since: java.time.LocalDate? = null
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

    /** The switch and the days read from the settings (a worker thread). Days recorded before this read are kept. */
    internal fun load() {
        runCatching {
            on = SecurePrefs.getBoolean(K_ON, false)
            since = SecurePrefs.getString(K_SINCE)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            val saved = AccountStop.decode(SecurePrefs.getString(K_DAYS))
            synchronized(lock) {
                // A day recorded in memory before the read wins over the saved one (it is newer).
                days = (saved.filter { s -> days.none { it.day == s.day } } + days).sortedBy { it.day }.takeLast(AccountStop.KEEP)
                status = AccountStop.status(days, today(), since)
            }
        }
        loaded = true
        publish()
    }

    /**
     * Zerodha's day P&L as just read ([net]; the daily loss limit's own read, in the watch pass). Recorded for today on a trading
     * day once the market has opened; written in the background at most once a minute (and at once when the rule is reached).
     */
    fun dayRead(net: Double, now: Long = System.currentTimeMillis()) {
        runCatching {
            if (!net.isFinite() || !Market.isTradingDay() || Market.minuteNow() < Market.OPEN) return
            val d = today()
            val (was, st) = synchronized(lock) {
                val was = status.stopped
                days = AccountStop.record(days, d, net)
                status = AccountStop.status(days, d, since)
                was to status
            }
            if (st.stopped && !was) {
                runCatching { Diag.record("risk", "Account stop rule reached (${st.why}): " +
                    if (on) "the bots' new live entries are refused" else "in shadow, nothing changes (Bot settings switches it on)") }
            }
            if ((st.stopped && !was) || now - writtenAt >= WRITE_EVERY_MS) {
                writtenAt = now
                val text = synchronized(lock) { AccountStop.encode(days) }
                scope.launch { runCatching { SecurePrefs.putAllSoon(mapOf(K_DAYS to text)) }; publish() }
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

    /** Today's would-be refusals in shadow. */
    private fun shadowToday(): Int = synchronized(lock) { if (shadowDay == today()) shadowSeen.size else 0 }

    /** The diagnostics' and Bot settings' line (this month's figure as of today). */
    fun line(): String = AccountStop.line(synchronized(lock) { AccountStop.status(days, today(), since) }, on, shadowToday())

    /**
     * Boss restarts the count after the rule was reached (Bot settings, with his PIN - the caller asks it): it counts again from
     * the next trading day. Never by itself and never by voice.
     */
    fun restart(pinConfirmed: Boolean): String {
        if (!pinConfirmed) return "Restarting the account stop rule's count takes your PIN."
        if (!status.stopped) return "The account stop rule is not reached: nothing to restart."
        val from = AccountStop.restartFrom(today())
        since = from
        synchronized(lock) { status = AccountStop.status(days, today(), from) }
        runCatching { SecurePrefs.putAllSoon(mapOf(K_SINCE to from.toString())) }
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

    /** TEST ONLY: off, no days, nothing seen. */
    internal fun resetForTest() {
        on = false; since = null; loaded = true; writtenAt = 0L
        synchronized(lock) { days = emptyList(); shadowSeen.clear(); shadowDay = null; status = AccountStop.status(emptyList(), today()) }
        _view.value = View()
    }
}
