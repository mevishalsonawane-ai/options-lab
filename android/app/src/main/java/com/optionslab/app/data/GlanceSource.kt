package com.optionslab.app.data

import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.ForwardCheck
import com.optionslab.ira.TodayGlance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.optionslab.ira.Market as IraMarket

/**
 * The facts behind Home's "Today at a glance" card ([TodayGlance]), read from what the app already keeps: the market
 * calendar ([Market], [Holidays]), the morning cues and big-move risk ([com.optionslab.app.ira.GlanceReads]), the arms'
 * book ([OrbArms.view]), Solo's and Jarvis's own trade records, the live-vs-backtest records ([ForwardRecords]) and the
 * event calendar ([com.optionslab.app.ira.IraEvents]). No network beyond what those sources already do (none of them
 * fetches here); nothing is armed, placed or changed. Every read runs on [Dispatchers.IO]; a source that cannot be read
 * is left out of the card, never guessed.
 */
internal object GlanceSource {
    /** How often the card is read again while Home is on screen. */
    const val EVERY_MS = 60_000L
    /** The live-vs-backtest records are read at most this often (they change when a trade closes). */
    private const val FORWARD_EVERY_MS = 5 * 60_000L
    private const val KEY_OPEN = "home.glance.open"
    private val INDICES = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")

    /** The last card read (shown at once when Home opens again, until the next read). */
    @Volatile var last: TodayGlance.Card? = null
        private set
    @Volatile private var forwardKept: Pair<Long, List<ForwardCheck.Result>>? = null

    /** The card open (true) or folded to its one line; remembered across launches. */
    fun expanded(): Boolean = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean(KEY_OPEN, true) }.getOrDefault(true)
    fun setExpanded(open: Boolean) { runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(KEY_OPEN to open)) } }

    /** Read every source and word the card. */
    suspend fun read(): TodayGlance.Card = withContext(Dispatchers.IO) { TodayGlance.card(facts()).also { last = it } }

    private val jarvis: Boolean get() = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    suspend fun facts(): TodayGlance.Facts {
        val now = Market.now().toLocalDateTime()
        val today = now.toLocalDate()
        val trading = runCatching { Market.isTradingDay(today) }.getOrDefault(Market.isWeekday(today))
        val phase = TodayGlance.phase(now, trading)
        val next = if (phase == TodayGlance.Phase.CLOSED) (1L..14L).map { today.plusDays(it) }
            .firstOrNull { d -> runCatching { Market.isTradingDay(d) }.getOrDefault(false) } else null
        // Each index's expiries from the contract list already on the phone (never a download), read once a pass.
        val listed = INDICES.associateWith { u -> runCatching { Market.upcomingExpiries(u) }.getOrDefault(emptyList()) }
        val expiries = if (trading) INDICES.filter { u -> today in listed[u].orEmpty() } else emptyList()
        val holidays = runCatching { Holidays.book().upcoming(today).take(3) }.getOrDefault(emptyList())

        val preOpen = phase == TodayGlance.Phase.PRE_OPEN && jarvis
        val gift = if (preOpen) runCatching { com.optionslab.app.ira.GlanceReads.gift(now) }.getOrNull() else null
        val fii = if (preOpen) runCatching { com.optionslab.app.ira.GlanceReads.fii(today) }.getOrNull() else null
        val risks = if (phase == TodayGlance.Phase.OPEN && jarvis) listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY).mapNotNull { m ->
            runCatching { com.optionslab.app.ira.GlanceReads.bigMove(m, now, trading) }.getOrNull()
        } else emptyList()

        val view = runCatching { OrbArms.view() }.getOrNull()
        val liq = view?.arms?.firstOrNull { it.arm.source == LiquidityRules.ARM.source }?.let { a ->
            val closed = a.today.filter { !it.open }
            TodayGlance.Liquidity(a.armed, a.lots, a.today.size, closed.takeIf { it.isNotEmpty() }?.sumOf { (it.grossPnl ?: 0.0) - it.charges },
                a.open?.let { TodayGlance.Open(it.symbol, it.entry, it.stopTrigger) })
        }
        val hero = view?.arms?.firstOrNull { it.arm.source == HeroRules.ARM.source }?.let { a ->
            val exp = listed[HeroRules.UNDERLYING].orEmpty()
            TodayGlance.Hero(a.armed, if (exp.isEmpty()) null else today in exp)
        }
        val solo = if (!jarvis) null else runCatching {
            val all = com.optionslab.app.ira.IraSolo.all()
            val mine = all.lastOrNull { it.midday && it.day == today.toString() }
            TodayGlance.Solo(com.optionslab.app.ira.IraSolo.on, mine?.let { TodayGlance.SoloTrade(it.symbol, !it.closed, it.net) },
                com.optionslab.app.ira.IraSolo.forward(all).trades)
        }.getOrNull()
        val jarvisOpen = if (!jarvis) null else runCatching { com.optionslab.app.ira.IraNewsTrades.all().count { !it.closed } }.getOrNull()
        val events = runCatching { com.optionslab.app.ira.IraEvents.upcoming(2) }.getOrDefault(emptyList())

        return TodayGlance.Facts(now, trading, next, expiries, holidays, gift, fii, risks, liq, solo, hero, jarvisOpen, forward(), events)
    }

    /** Liquidity 15+5's and Solo's live-vs-backtest checks ([ForwardRecords.rows]), read again at most every 5 minutes. */
    private suspend fun forward(nowMs: Long = System.currentTimeMillis()): List<ForwardCheck.Result> {
        forwardKept?.takeIf { nowMs - it.first < FORWARD_EVERY_MS }?.let { return it.second }
        val rows = runCatching { ForwardRecords.rows() }.getOrNull() ?: return forwardKept?.second.orEmpty()
        val keep = rows.filter { !it.shadow && it.result.expectation.key in setOf(ForwardCheck.LIQUIDITY.key, ForwardCheck.SOLO.key) }.map { it.result }
        forwardKept = nowMs to keep
        return keep
    }

    /** TEST ONLY: forget the kept card and reads. */
    internal fun forget() { last = null; forwardKept = null }
}
