package com.optionslab.engine.mcx

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MCX expiry safety for a Rs 1 lakh account (research/MCX_GUIDE.md 1d):
 *
 *  - an MCX option does not settle in cash: in the money (or close to it) at expiry it DEVOLVES into the future, and Zerodha
 *    then wants the whole futures margin (Rs 1.4-2.7 lakh) by 19:00 on expiry day. So every MCX option position is closed by
 *    [Config.optionExitTime] (23:00 IST) on the trading day BEFORE its expiry ([optionExitAt]; earlier when that day's
 *    session ends sooner);
 *  - Zerodha blocks fresh long NRML option positions on expiry day; the app refuses every new MCX option BUY on expiry day,
 *    and on the day before from [Config.buyCutoff] (15:00) - it would only be closed again that night ([buyRefusal]);
 *  - gold, silver and base-metal futures settle by delivery, which Zerodha does not allow: it squares them off itself at
 *    22:30 on the day before the tender period. The app closes them first: from the session open
 *    [Config.futureExitTradingDays] (2) trading days before expiry ([futureExitDue]), and refuses new ones from then.
 *    Crude and natural gas settle in cash and are left alone.
 *
 * Pure: the caller hands in the time (IST) and the MCX calendar.
 */
object McxExpiry {
    data class Config(
        /** Off: nothing is closed by itself (the owner's choice; on by default). */
        val enabled: Boolean = true,
        val optionExitTime: LocalTime = LocalTime.of(23, 0),
        val buyCutoff: LocalTime = LocalTime.of(15, 0),
        val futureExitTradingDays: Int = 2,
    )

    /** Why a position must go now. */
    enum class Why { OPTION_DEVOLVES, FUTURE_DELIVERY }

    /** A position that must be closed now, and the plain words for the owner. */
    data class Exit(val why: Why, val text: String)

    /** At least this long before a session's end the option exit is due, when the day ends before [Config.optionExitTime]. */
    private const val BEFORE_CLOSE_MIN = 15L

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** The trading day before [expiry] on MCX's calendar. */
    fun dayBefore(expiry: LocalDate, cal: McxSession.Calendar): LocalDate = cal.tradingDaysBefore(expiry, 1)

    /** When an option expiring on [expiry] must be closed: [Config.optionExitTime] on the trading day before (earlier if that session ends sooner). */
    fun optionExitAt(expiry: LocalDate, cal: McxSession.Calendar, cfg: Config = Config()): LocalDateTime {
        val d = dayBefore(expiry, cal)
        val w = cal.window(d)
        val latest = w?.close?.minusMinutes(BEFORE_CLOSE_MIN)
        val at = if (latest != null && latest.isBefore(cfg.optionExitTime)) latest else cfg.optionExitTime
        return d.atTime(at)
    }

    /** True from [optionExitAt] on (the expiry day and later included). */
    fun optionExitDue(expiry: LocalDate, now: LocalDateTime, cal: McxSession.Calendar, cfg: Config = Config()): Boolean =
        cfg.enabled && !now.isBefore(optionExitAt(expiry, cal, cfg))

    /** The day a physically settled future is closed: [Config.futureExitTradingDays] trading days before [expiry]. */
    fun futureExitDay(expiry: LocalDate, cal: McxSession.Calendar, cfg: Config = Config()): LocalDate =
        cal.tradingDaysBefore(expiry, cfg.futureExitTradingDays.coerceAtLeast(1))

    /** True from the open of [futureExitDay] for a future of a delivery-settled commodity ([Mcx.physical]); never for crude or gas. */
    fun futureExitDue(name: String, expiry: LocalDate, now: LocalDateTime, cal: McxSession.Calendar, cfg: Config = Config()): Boolean =
        cfg.enabled && Mcx.physical(name) && !now.toLocalDate().isBefore(futureExitDay(expiry, cal, cfg))

    /** What must happen to a held MCX contract now, or null when it may stay. */
    fun due(name: String, expiry: LocalDate, option: Boolean, now: LocalDateTime, cal: McxSession.Calendar, cfg: Config = Config()): Exit? = when {
        option && optionExitDue(expiry, now, cal, cfg) -> Exit(Why.OPTION_DEVOLVES,
            "$name option expires ${expiry.format(DAY)}. MCX options turn into futures if they end in the money, which needs " +
                "far more margin than the account has, so it is closed now (by ${say(optionExitAt(expiry, cal, cfg))}).")
        !option && futureExitDue(name, expiry, now, cal, cfg) -> Exit(Why.FUTURE_DELIVERY,
            "$name future expires ${expiry.format(DAY)} and settles by delivery, which Zerodha does not allow, so it is closed " +
                "now (${cfg.futureExitTradingDays} trading days before expiry).")
        else -> null
    }

    /**
     * Why a NEW position may not be opened now (null: it may): an option BUY on expiry day or on the day before from
     * [Config.buyCutoff]; a delivery-settled future from its exit day. A sell that opens a short option is refused by the
     * account guard's naked-short rule, not here. Applies whether or not [Config.enabled] (it only stops new risk).
     */
    fun entryRefusal(name: String, expiry: LocalDate, option: Boolean, buy: Boolean, now: LocalDateTime, cal: McxSession.Calendar,
                     cfg: Config = Config()): String? {
        val today = now.toLocalDate()
        if (option && buy) {
            if (!today.isBefore(expiry)) return "No new $name option buys on its expiry day (${expiry.format(DAY)}): Zerodha blocks them, and an MCX option at expiry turns into a future."
            if (today == dayBefore(expiry, cal) && !now.toLocalTime().isBefore(cfg.buyCutoff))
                return "No new $name option buys after ${say(cfg.buyCutoff)} the day before expiry (${expiry.format(DAY)}): it would have to be closed again by ${say(optionExitAt(expiry, cal, cfg).toLocalTime())} tonight."
        }
        if (!option && Mcx.physical(name) && !today.isBefore(futureExitDay(expiry, cal, cfg)))
            return "No new $name future this close to its expiry (${expiry.format(DAY)}): it settles by delivery, which Zerodha does not allow."
        return null
    }

    private fun say(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)
    private fun say(t: LocalDateTime): String = t.format(DAY) + " " + say(t.toLocalTime())
}
