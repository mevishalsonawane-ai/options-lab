package com.optionslab.app.ira

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Everything Jarvis does by itself, in one place (the owner's wish, 2026-10-03): each automatic behaviour with its
 * own switch and when it last acted, shown on the Jarvis health screen. Every watcher asks [on] first and calls
 * [acted] when it did something.
 */
internal object Automations {
    enum class Auto(val label: String, val what: String, val key: String, val byDefault: Boolean = true) {
        // Off until Boss switches it on: it moves the stop orders of his own positions, live ones too (review, 3 Oct).
        TRAIL("Trail my stops", "Your own bought options: stop to what you paid at +20%, then 15% under the best price.", "jarvis.autotrail", byDefault = false),
        RESCUE("Offer a stop", "A position of yours with no stop for 2 minutes: Jarvis offers one (asks first).", "jarvis.auto.rescue"),
        // Off until Boss switches it on with his fingerprint (4 Oct): it places a real stop order on Zerodha positions.
        GUARD("Guard my positions", "A bought option of yours with no stop for 2 minutes: Jarvis sets the stop itself, 15% under what you paid, and tells you - Zerodha ones too. It only ever adds a stop that closes; it never opens or adds.", "jarvis.auto.guard", byDefault = false),
        STALE("Trades going nowhere", "Open 45 minutes and within 5% of what you paid: Jarvis offers to close it (asks first).", "jarvis.auto.stale"),
        COOLOFF("Cool-off after losses", "Two of Jarvis's trades lose in a row: no suggestions for 30 minutes.", "jarvis.auto.cooloff"),
        OVERTRADE("Overtrading warning", "More than 3 of your buys in 30 minutes: a word to slow down.", "jarvis.auto.overtrade"),
        TARGET("Day target", "Your day's target reached: told to protect the gains.", "jarvis.auto.target"),
        POSNEWS("News on your positions", "A headline on an index you hold: good or bad for your side.", "jarvis.auto.posnews"),
        MIS("Intraday square-off heads-up", "15:10: open intraday (MIS) Zerodha positions named before Zerodha squares them off itself (words only).", "jarvis.auto.mis"),
        RELAY("Relay server watch", "08:30-15:30: the static-IP relay server not answering twice in a row is told at once, and when it is back.", "jarvis.auto.relay"),
        FEED("Live prices stopped", "No prices for 2 minutes in market hours: told at once.", "jarvis.auto.feed"),
        EXPIRY("Expiry heads-up", "14:55 on expiry day: what the 15:05 square-off will close.", "jarvis.auto.expiry"),
        GAP("Opening gap plan", "09:16: the gap and how the arms did on such days.", "jarvis.auto.gap"),
        PLAN("Plan my day (paper arms)", "09:00-10:00: a PAPER arm that lost on days like today is parked, and one Jarvis parked is armed again when the market suits it; told why. Never an arm on Zerodha, never one you left off.", "jarvis.auto.plan"),
        ORB("Opening range breaks", "Nifty or BankNifty leaving its first 15 minutes' range: told once per side a day.", "jarvis.auto.orb"),
        VIX("Fear spikes", "India VIX up 10% or more on the day: told once.", "jarvis.auto.vix"),
        MOMENTS("Market moments", "Nifty or BankNifty filling its opening gap, or going past the previous session's high or low: told once each a day.", "jarvis.auto.moments"),
        SHARPMOVE("Sharp moves: what coincided", "Nifty or BankNifty moving 0.3% or more, and 3 times its usual, within 10 minutes: the headlines published around then, India VIX and the other indices over the same minutes - timing only, never a cause. Each move told once.", "jarvis.auto.sharpmove"),
        LIQUIDITY("Liquidity levels heads-up", "09:20-14:00, Liquidity 15+5 armed: BankNifty within 15 points (FinNifty 8) of a level whose close-through would be its entry with room - told once per level a day, at most once in 10 minutes per index; in the chat only when saving battery or on short answers (words only; the arm decides on its own).", "jarvis.auto.liquidity"),
        // Off until Boss switches it on (06 Oct): a quiet notification, information only - the arm's decision is its own.
        LIQSKIP("Skipped Liquidity breaks", "A Liquidity 15+5 break the arm skipped because the next level was too close (BankNifty under 30 points, FinNifty 15): a quiet notification, e.g. \"Skipped: BankNifty 5-min break of 54,180 - only 22 pts to the next level (needs 30)\". Off by default; never while saving battery (information only).", "jarvis.auto.liqskip", byDefault = false),
        // Its own switch under Market alerts (06 Oct), on by default: it only talks.
        OPENING("Opening read", "Just after the first 5-minute candle (09:20-09:25, once a trading day): one short note in the chat - Nifty's, BankNifty's and FinNifty's gap against the previous close (and what GIFT Nifty pointed to), where the open sits against Liquidity 15+5's levels and what the gap took, the first candle against the usual, the arm's switch, lots and first trigger, and the big-move line (facts only; nothing is armed or changed). Ask \"how did the market open\" any time.", "jarvis.auto.opening"),
        OI("Open interest walls", "The biggest call / put open interest moving to a new strike.", "jarvis.auto.oi"),
        EXPIRYDAY("Expiry day companion", "On Nifty's or BankNifty's expiry day at 09:30, 12:00, 13:30, 14:30 and 15:20: the at-the-money straddle and how much of it has gone, spot against max pain, and the last hour's range (facts only).", "jarvis.auto.expiryday"),
        MORNING_VOICE("Morning check aloud", "09:00: the morning check is spoken even with listening off (never when muted or in quiet hours).", "jarvis.auto.morningvoice"),
        HEADSUP("Position heads-ups", "One position of yours losing half, then three quarters, of your daily loss limit by itself, or an option you sold 80% decayed: told once each a day (words only; nothing is closed).", "jarvis.auto.headsup"),
        USUAL("Your usual, unasked", "A market question you ask at the same hour most days (4+ times): said at that hour's start, once a day. And a routine Jarvis noticed and you said yes to (a question you ask around the same time, on one weekday, or just after a losing trade): its answer said then, in words only - your P&L never on a locked phone.", "jarvis.auto.usual"),
        AGENDA("My own plan for the day", "Each morning Jarvis plans his own day (events, expiry, your goals and rules, his weak hours, paper tests, what he studied) and works through it; told at the wrap-up. It only speaks, studies or works on paper; a lesson is kept only on your yes.", "jarvis.auto.agenda"),
        WEEK("Weekly review", "After the week's last session: your own trades this week against last week, and the habits that cost money (losers held longer, trades in the first five minutes or right after a loss, a day of too many trades). Spoken without amounts; the numbers are in the chat.", "jarvis.auto.week"),
        MONTH("Monthly review", "After the month's last session: your own trades this month against last month - by index, time of day, weekday, holding time and the reasons you noted, your green days and best day against the rest, and the one habit that cost most. Spoken without amounts; the numbers are in the chat.", "jarvis.auto.month"),
        PRETRADE("A word before an order", "Opening an order to review just after a loss, past your usual number of trades or your own trade goal, in the first five minutes, or against a rule you asked me to remember: a gentle reminder on the review, with your own record (words only; the order is never blocked or changed).", "jarvis.auto.pretrade"),
        JOURNAL("Journal my day", "After the close: today's journal drafted from the facts - each of your own trades replayed, the reasons you noted, your rules kept or broken, my words before an order and what followed, your goals and limits - then up to three short questions, by voice on an unlocked phone only. Your answers are kept in the journal as you said them, never acted on. Spoken without amounts; the numbers are in the chat and on the P&L calendar's day.", "jarvis.auto.journal"),
        HEALTH("Expiry-day position check", "14:45 on a day a position of yours expires: each open position's time to expiry, time decay, distance from its strike, spread now and any stop or target, put in the chat, with a flag on what expires in the money and how it settles (index options in cash, stock options by delivery). Spoken without amounts or symbols; facts only. Ask \"check my positions\" any time.", "jarvis.auto.health"),
        WORDS("Your words against today", "A rule you asked me to remember (\"I don't trade on Fridays\", \"no trades before 9:30\", an index or expiry days you skip) or your \"no more than N trades a day\" goal, against your own trades today: pointed out once each a day, gently, on an unlocked phone only (words only; nothing is blocked or changed).", "jarvis.auto.words"),
        BOTS("Strategies behaving unusually", "Market hours: one of your strategies (an ORB arm, a Pine auto-trade script, a strategy or Solo) taking far more trades today than its tested day, or losing more in a row than its tested worst - its backtest, else its own record: told once a day, without amounts (the figures are in the chat), and stopping it is asked first (unless you chose automatic stops; never anything that adds risk). Ask \"how are my bots doing?\" any time.", "jarvis.auto.bots"),
        // Its own switch under Coach me (06 Oct), on by default: it only talks - never switches an arm or changes its lots or rules.
        FORWARD("Forward-test watch", "Liquidity 15+5, Solo (midday) and Hero against their backtests: when an arm's live-vs-backtest verdict changes from the last one told (too few trades to in line, in line to below expectation, a sustained run below first flagged, back in line) - and at 20 trades, and 40 and 60 for Solo's forward test - one note in the chat: its trades, net a trade against the backtest's and its band, what changed and what it means, and for Solo how far it is from its own -Rs 25,000 switch-off line (words only; nothing is switched, sized or changed - Solo's own switch-off stays its own). Ask \"is anything drifting\" any time.", "jarvis.auto.forward"),
        SUMMARY("15:35 wrap-up", "The day's P&L, scorecard and tomorrow's events, spoken.", "jarvis.auto.summary"),
        TOMORROW("Tomorrow's plan", "After the close (from 15:45, once a trading day): one note in the chat for the next session - its date and expiries, Liquidity 15+5's paper day, the levels it carries into tomorrow, its switch and lots, Solo and Hero, the events, the FIIs and the big-move read at the close (facts only; nothing is armed or changed). Ask \"what's the plan for tomorrow\" any time.", "jarvis.auto.tomorrow"),
        BACKUP("Backup reminder", "No backup in 7 days: a reminder in the morning check.", "jarvis.auto.backup"),
        SELFHEAL("Self-healing voice", "No listening for 3 minutes: the microphone is restarted.", "jarvis.auto.selfheal"),
        ACT_PAPER("Act on my own ideas (paper)", "A news or pattern idea with 3/5 confidence or more: Jarvis takes it on the PAPER account by itself and tells you (never real money).", "jarvis.auto.actpaper"),
        // Retired 06 Oct (kept so a saved name still reads): nothing uses it, it has no switch and is always off ([RETIRED]).
        SOLO_IDEAS("Solo's setups as ideas", "Retired 06 Oct: Solo (midday) is paper only and never offers its setups as trade ideas.", "jarvis.auto.soloideas", byDefault = false),
        QUIET("Quiet hours", "Nothing said unasked from 22:00 to 07:00.", "jarvis.quiet"),
    }

    /**
     * The switches Boss sees (4 Oct: "combine the AI settings that go together; drop those that stay on anyway"): each
     * behaviour belongs to one group with one switch; the safety helpers have none and are always on.
     */
    enum class Group(val label: String, val what: String, val key: String, val members: List<Auto>, val byDefault: Boolean = true,
                     /** Switched on only with the fingerprint (it touches real stop orders). */ val fingerprint: Boolean = false,
                     /** Behaviours under this group with a switch of their own (shown beneath it), each on only while the group is. */
                     val subs: List<Auto> = emptyList()) {
        GUARD("Guard my positions", "Your own bought options: a stop set by itself when one has none for 2 minutes (15% under what you paid), then trailed up - to what you paid at +20%, then 15% under the best price. Zerodha positions too; it only adds or raises stops that close, never opens or adds.",
            "jarvis.group.guard", listOf(Auto.GUARD, Auto.TRAIL), byDefault = false, fingerprint = true),
        HELP("Offer help on my positions", "A position with no stop, or one going nowhere for 45 minutes: Jarvis offers a stop or a close (asks first).",
            "jarvis.group.help", listOf(Auto.RESCUE, Auto.STALE)),
        OWN("Act on his own, on paper", "Takes his own ideas of 3/5 or more on PAPER (raising the bar where he loses) and plans the paper arms each morning.",
            "jarvis.group.own", listOf(Auto.ACT_PAPER, Auto.PLAN)),
        MARKET("Market alerts", "Opening gap plan, the opening read just after 09:20, opening range breaks, gaps filling, the previous day's high or low passed, fear (VIX) spikes, sharp moves and what coincided with them, the price nearing a Liquidity 15+5 entry level, open interest walls moving, the expiry-day straddle and max pain, and news on indices you hold.",
            "jarvis.group.market", listOf(Auto.GAP, Auto.ORB, Auto.MOMENTS, Auto.VIX, Auto.SHARPMOVE, Auto.LIQUIDITY, Auto.OI, Auto.EXPIRYDAY, Auto.POSNEWS),
            subs = listOf(Auto.LIQSKIP, Auto.OPENING)),
        COACH("Coach me", "A word when you overtrade or before an order sent just after a loss or past your usual day, your day's target reached, a position losing a big share of your daily loss limit or a sold option mostly decayed, your usual question answered at its hour, the 09:00 check, Jarvis's own plan for the day, the 15:35 wrap-up, tomorrow's plan after the close, your day's journal drafted with a few questions, the 14:45 check on positions expiring that day, a strategy of yours behaving unusually against its tested record, a paper arm's live-vs-backtest verdict changing, the week's and the month's reviews spoken, and your own words against today's trades (a rule or trade goal you set) pointed out once, on an unlocked phone.",
            "jarvis.group.coach", listOf(Auto.OVERTRADE, Auto.TARGET, Auto.HEADSUP, Auto.USUAL, Auto.MORNING_VOICE, Auto.AGENDA, Auto.SUMMARY, Auto.TOMORROW, Auto.JOURNAL, Auto.WEEK, Auto.MONTH, Auto.PRETRADE, Auto.HEALTH, Auto.WORDS, Auto.BOTS),
            subs = listOf(Auto.FORWARD)),
        QUIET("Quiet hours", "Nothing said unasked from 22:00 to 07:00.", "jarvis.group.quiet", listOf(Auto.QUIET)),
    }

    /** Always on, no switch: they only warn, cool off or heal (live prices stopped, expiry heads-up, cool-off, backup, voice). */
    val ALWAYS = setOf(Auto.MIS, Auto.RELAY, Auto.FEED, Auto.EXPIRY, Auto.COOLOFF, Auto.BACKUP, Auto.SELFHEAL)

    /** Retired: no switch, never on (the entries stay so a saved name still reads). */
    val RETIRED = setOf(Auto.SOLO_IDEAS)

    fun groupOf(a: Auto): Group? = Group.entries.firstOrNull { a in it.members || a in it.subs }

    /** A behaviour with its own switch beneath its group ([Group.subs]). */
    fun isSub(a: Auto): Boolean = Group.entries.any { a in it.subs }

    fun on(a: Auto): Boolean {
        if (a in RETIRED) return false
        if (a in ALWAYS) return true
        val g = groupOf(a) ?: return a.byDefault
        if (isSub(a)) return on(g) && runCatching { com.optionslab.app.security.SecurePrefs.getBoolean(a.key, a.byDefault) }.getOrDefault(a.byDefault)
        return on(g)
    }

    /** A sub-switch's own setting, whatever its group's ([on] also needs the group on). */
    fun ownSwitch(a: Auto): Boolean = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean(a.key, a.byDefault) }.getOrDefault(a.byDefault)

    fun on(g: Group): Boolean = runCatching {
        val p = com.optionslab.app.security.SecurePrefs
        // Before the groups each behaviour had its own switch: a group starts as Boss left its members (the guard only
        // from its own fingerprint switch, never from the old trail switch, which did not ask for it).
        // (OWN once had Solo's ideas among its members: their old switch, on by default then, still counts for it.)
        val was = if (g == Group.GUARD) p.getBoolean(Auto.GUARD.key, false)
            else g.members.any { p.getBoolean(it.key, it.byDefault) } || g == Group.OWN && p.getBoolean(Auto.SOLO_IDEAS.key, true)
        p.getBoolean(g.key, was)
    }.getOrDefault(false)

    fun set(g: Group, v: Boolean) {
        // His alerts or coaching switched off by Boss is counted for the day: his weekly review of how often he was too much.
        if (!v && (g == Group.MARKET || g == Group.COACH) && on(g)) runCatching { IraTools.count(com.optionslab.ira.Improve.ALERT_OFF) }
        runCatching { com.optionslab.app.security.SecurePrefs.put(g.key, v) }
    }

    /** A behaviour's switch is its group's (an always-on one has none). */
    fun set(a: Auto, v: Boolean) {
        if (isSub(a)) { runCatching { com.optionslab.app.security.SecurePrefs.put(a.key, v) }; return }
        groupOf(a)?.let { set(it, v) }
    }

    /** When the group last acted and what it did (the latest of its members). */
    fun last(g: Group): Pair<LocalDateTime, String>? = g.members.mapNotNull { last(it) }.maxByOrNull { it.first }

    /** [a] just did something: when, and what (one line). */
    fun acted(a: Auto, what: String) {
        runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.auto.last.${a.name}",
            LocalDateTime.now(ZoneId.of("Asia/Kolkata")).withNano(0).toString() + "|" + com.optionslab.ira.Secrets.redact(what).take(160)) }
    }

    /** When [a] last acted and what it did, or null. */
    fun last(a: Auto): Pair<LocalDateTime, String>? = runCatching {
        val v = com.optionslab.app.security.SecurePrefs.getString("jarvis.auto.last.${a.name}") ?: return@runCatching null
        LocalDateTime.parse(v.substringBefore('|')) to v.substringAfter('|')
    }.getOrNull()
}
