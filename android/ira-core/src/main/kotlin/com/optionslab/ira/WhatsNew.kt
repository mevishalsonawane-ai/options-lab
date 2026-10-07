package com.optionslab.ira

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "What's new" (06 Oct 2026): the app's own changelog in plain words, for Boss, who installs a new build often and needs to
 * know what changed and where to find it. Each entry has a stable [Entry.id] (what Home's card remembers as seen), its date,
 * a short title, one or two plain sentences, where to find it in the app, and a question to try with Jarvis when there is one.
 *
 * Shown on Home as a card while some entries are unseen ([unseen], newest first, [collapsed] to three until "Show all";
 * "Got it" marks them all seen, [markSeen]), in full under Settings → What's new, and said by Jarvis on "what's new" /
 * "what changed in the app" / "naya kya hai" ([asked], [answer]). "What's new in the market", "any news" and the like stay the news.
 * IraGoldAlgo shows only entries flagged [Entry.gold] (none so far, so it shows nothing). Pure.
 */
object WhatsNew {
    data class Entry(
        /** Stable: what is remembered as seen. Never reuse or rename one. */
        val id: String,
        val date: LocalDate,
        val title: String,
        /** One or two plain sentences: what changed. */
        val what: String,
        /** Where to find it in the app (the screen or tab and how to reach it). */
        val where: String,
        /** A question to try with Jarvis (null: none). */
        val ask: String? = null,
        /** Also in IraGoldAlgo (which only talks): shown there too. */
        val gold: Boolean = false,
        /** Only in a build with Jarvis (its question, chat or switch). */
        val jarvisOnly: Boolean = false,
        /** Where a tap on Home's card takes Boss (a Home shortcut, e.g. "chart", "pnl", "ira"); null: nowhere to open. */
        val to: String? = null,
    )

    /** Home's card shows this many until "Show all". */
    const val COLLAPSED = 3

    /** Jarvis names at most this many. */
    const val SPOKEN = 6

    /** Where the full list is. */
    const val FULL_LIST = "Settings → What's new"

    private val OCT6: LocalDate = LocalDate.of(2026, 10, 6)
    private val OCT7: LocalDate = LocalDate.of(2026, 10, 7)
    private const val JARVIS_CHAT = "Ask Jarvis, by voice or in the chat (Home → Ira)"

    /** Every entry, newest first (within a day in the order the changes came). */
    val ENTRIES: List<Entry> = listOf(
        Entry("2026-10-07-liquidity-midcpnifty", OCT7, "Liquidity 15+5 now also trades Midcap Nifty",
            "Liquidity 15+5 also runs on Midcap Nifty's 15-minute and 5-minute charts, on paper, with its rules unchanged and an " +
                "8-point index stop. It was positive before and in the research's locked holdout; Zerodha still needs your PIN or fingerprint.",
            "Home → Dashboard → Strategies card → Liquidity 15+5 row (one switch for all its charts)"),
        Entry("2026-10-07-orb-arms-back", OCT7, "ORB, ORB Fresh, ORB Sweep and Range Fade are back",
            "You brought the four arms back: each is switched on again on paper and has its own switch beside Liquidity 15+5. " +
                "Zerodha still needs your PIN or fingerprint, and their 2021–2026 record stays in the arms' detail.",
            "Home → Dashboard → Strategies card: each arm's row and switch (tap a row for its record)"),
        Entry("2026-10-06-solo-day", OCT6, "Ask what Solo did today",
            "Jarvis explains Solo (midday)'s day: what it looked at, which index it picked, or why it took no trade.",
            JARVIS_CHAT, ask = "what did Solo do today", jarvisOnly = true, to = "ira"),
        Entry("2026-10-06-liquidity-level-alert", OCT6, "Liquidity level sheet with a price alert",
            "Tap or long-press a Liquidity level on the chart to see its price, its kind, how far away it is and whether it was taken today. " +
                "One button sets a price alert there; it only notifies, nothing is ordered.",
            "Chart tab → BANKNIFTY or FINNIFTY with Liquidity levels on → tap a level → \"Alert me when price reaches it\". Your alerts are also in Settings → Alerts.",
            to = "chart"),
        Entry("2026-10-06-liquidity-why-not", OCT6, "Why no Liquidity trade today",
            "Jarvis tells you why Liquidity 15+5 did or did not trade today, chart by chart, and what would make it enter next.",
            JARVIS_CHAT, ask = "why no liquidity trade today", jarvisOnly = true, to = "ira"),
        Entry("2026-10-06-liquidity-paper-record", OCT6, "Liquidity 15+5 paper record chart",
            "A small chart of Liquidity 15+5's paper trades since 6 Oct against what its backtest expected, with the drawdown under it. " +
                "Tap it for a bigger view with every trade.",
            "Home → Dashboard → Strategies card → under the Liquidity 15+5 row (Paper record)"),
        Entry("2026-10-06-tomorrow-plan", OCT6, "Tomorrow's plan",
            "After the close, Jarvis leaves one note to prepare you for the next session: its date and expiries, Liquidity's levels for tomorrow, " +
                "Solo, the events and the FIIs. Facts only.",
            "Jarvis's chat (Home → Ira) after 15:45 on trading days. Its switch: Settings → Voice and AI model → What Jarvis does by itself → Coach me",
            ask = "what's the plan for tomorrow", jarvisOnly = true, to = "ira"),
        Entry("2026-10-06-liquidity-record", OCT6, "Liquidity 15+5's record over time",
            "Ask how Liquidity 15+5 did this week or on a day, its last few trades, which index works best, its streak, or whether it is on track.",
            JARVIS_CHAT, ask = "how did liquidity do this week", jarvisOnly = true, to = "ira"),
        Entry("2026-10-06-liquidity-replay", OCT6, "Replay a Liquidity trade",
            "Tap a closed Liquidity 15+5 paper trade to replay it: the index's minute chart around it with the level, stop, target, entry and exit, " +
                "the result after charges and the lesson.",
            "Home → Dashboard → Strategies card → tap Liquidity 15+5 → \"Arms · today\" → tap a closed trade"),
        Entry("2026-10-06-trade-lessons", OCT6, "A lesson from each Liquidity trade",
            "When a Liquidity 15+5 trade closes, Jarvis adds one or two plain sentences on how it compares with similar trades in the research. " +
                "The 15:35 wrap-up adds one line for the day.",
            "Jarvis's chat (Home → Ira)", ask = "explain my bots' trades today", jarvisOnly = true, to = "ira"),
        Entry("2026-10-06-liquidity-notifications", OCT6, "Clearer Liquidity notifications",
            "Each Liquidity 15+5 entry and exit notification now gives the side, strike, lots, price, the level it broke, the target and the stops; " +
                "an exit adds the reason, the result after charges and the time held. Expand one for a small chart.",
            "Your phone's notifications. A notice for skipped breaks (off at first): Settings → Voice and AI model → What Jarvis does by itself → " +
                "Market alerts → Skipped Liquidity breaks"),
        Entry("2026-10-06-today-glance", OCT6, "Today at a glance",
            "A small card on Home: the market's state, expiries and holidays, the cues before the open, the big-move risk in the session, " +
                "your strategies, live vs backtest and the next events. Tap its title to fold or open it.",
            "Home → Dashboard, at the top"),
        Entry("2026-10-06-weekly-review", OCT6, "Jarvis's weekly review",
            "After each week's last session Jarvis writes a short review: your paper results against last week, each strategy against its backtest, " +
                "and one thing to watch. The last 12 weeks are kept.",
            "Home → Ira → \"Jarvis's weekly review\" card", ask = "weekly review", jarvisOnly = true, to = "ira"),
        Entry("2026-10-06-market-data-viewer", OCT6, "Market data viewer",
            "See what the market recorder has saved: each day's news with how Nifty and BankNifty moved after each headline, the futures' open interest, " +
                "and GIFT Nifty and FII readings across days. First lessons appear after 20 recorded days.",
            "Settings → Data and harvest → Market recorder → \"Open recorded data\"", to = "data"),
        Entry("2026-10-06-liquidity-levels", OCT6, "Liquidity levels in words, and a heads-up",
            "Ask where Liquidity 15+5's next entry levels are on BankNifty and FinNifty and how far the price is. " +
                "While it is on, Jarvis also tells you when the price nears such a level in its entry hours.",
            "Ask Jarvis. The heads-up's switch: Settings → Voice and AI model → What Jarvis does by itself → Market alerts",
            ask = "where are the liquidity levels", jarvisOnly = true, to = "ira"),
        Entry("2026-10-06-live-vs-backtest", OCT6, "Live vs backtest",
            "Shows whether each running strategy's paper trades are in line with, below or above what its research expected. It needs 20 trades to judge.",
            "P&L tab → \"Live vs backtest\" card", jarvisOnly = true, to = "pnl"),
        Entry("2026-10-06-solo-midday", OCT6, "Solo (midday), on paper",
            "Solo now follows one tested rule: at 12:00 it buys the strongest mover of Nifty, BankNifty and FinNifty, one trade a day, on paper only. " +
                "It is not proven: it switches itself off after a Rs 25,000 fall, or if it has not made money after 60 trades.",
            "Settings → Voice and AI model → \"Solo: Jarvis trades by himself (paper)\" card. Also the Solo row in Today at a glance",
            ask = "how is Solo doing", jarvisOnly = true, to = "jarvis"),
        Entry("2026-10-06-morning-cues", OCT6, "Morning cues: GIFT Nifty and the FIIs",
            "Before the open: where GIFT Nifty points Nifty's opening, and whether the FIIs are mostly long or short index futures, from what the app records.",
            "Home → Dashboard → Today at a glance (before 09:15), the 09:00 morning check, or ask Jarvis",
            ask = "what's GIFT Nifty saying", jarvisOnly = true),
        Entry("2026-10-06-big-move-risk", OCT6, "Big-move risk now",
            "How likely a big, sudden move is right now against usual (low, normal, high or very high) and why. It never says which way.",
            "Home → Dashboard → Today at a glance in market hours (\"Big-move risk now\"), or ask Jarvis",
            ask = "is a big move likely now", jarvisOnly = true),
        Entry("2026-10-06-chart-liquidity", OCT6, "Liquidity levels on the chart",
            "On the BankNifty and FinNifty charts, a panel shows the levels Liquidity 15+5 watches, today's entries and exits, and the breaks it skipped. " +
                "Tap a marker for the trade's details.",
            "Chart tab → BANKNIFTY or FINNIFTY → \"Liquidity levels\" (on at first)", to = "chart"),
        Entry("2026-10-06-jarvis-trades-30-60", OCT6, "Jarvis's own trades: 30-point stop, 60-point target",
            "News trades and Jarvis's other trade ideas now always use a fixed 30-point stop and a 60-point target, with the profit locked in steps on the way. " +
                "An option priced 35 or less is not bought.",
            "Applies by itself to Jarvis's trades", ask = "what is the stop loss for news trades", jarvisOnly = true),
        Entry("2026-10-06-pine-30-60", OCT6, "Pine scripts: stop, target and profit lock required",
            "Every Pine script must now have a stop-loss and a target; new scripts start at 30 and 60 points, and the profit lock is always on. " +
                "Saved scripts without them got 30 and 60.",
            "Research tab → Pine scripts", to = "pine"),
        Entry("2026-10-06-liquidity-lots", OCT6, "Liquidity 15+5 size: 1 to 3 lots",
            "Choose 1, 2 or 3 lots for Liquidity 15+5 (2 at first). A raise asks you first, a cut applies at once, and an open trade keeps its size.",
            "Home → Dashboard → Strategies card → Liquidity 15+5 row → \"Lots: 1 · 2 · 3\"", ask = "how many lots is liquidity trading"),
        Entry("2026-10-06-liquidity-only", OCT6, "Liquidity 15+5 back on, on paper",
            "After six years of real data, Liquidity 15+5 went back on, on paper only, while the other four arms were set aside for a day.",
            "Home → Dashboard → Strategies card: the Liquidity 15+5 row"),
    )

    /** The entries this build shows: IraGoldAlgo only those marked [Entry.gold]; without Jarvis no Jarvis-only entry and no question. */
    fun forBuild(gold: Boolean, jarvis: Boolean, entries: List<Entry> = ENTRIES): List<Entry> = newestFirst(entries
        .filter { (!gold || it.gold) && (jarvis || !it.jarvisOnly) }
        .map { if (jarvis && !gold) it else it.copy(ask = null) })

    /** [entries] newest first: by date, and within a day as listed (the list is written newest first). */
    fun newestFirst(entries: List<Entry>): List<Entry> = entries.sortedByDescending { it.date }

    /** The entries not yet seen ([seen]: their ids), newest first. */
    fun unseen(entries: List<Entry>, seen: Set<String>): List<Entry> = newestFirst(entries.filter { it.id !in seen })

    /** What Home's card lists: the first [COLLAPSED] until [all]. */
    fun collapsed(entries: List<Entry>, all: Boolean): List<Entry> = if (all) entries else entries.take(COLLAPSED)

    /** How many [collapsed] leaves out ("Show all (n more)"); 0 when it shows them all. */
    fun hidden(entries: List<Entry>, all: Boolean): Int = if (all) 0 else (entries.size - COLLAPSED).coerceAtLeast(0)

    /**
     * [seen] with every one of [entries] added ("Got it"); ids no longer in [known] are dropped, so what is kept never grows
     * past the changelog.
     */
    fun markSeen(seen: Set<String>, entries: List<Entry>, known: List<Entry> = ENTRIES): Set<String> {
        val ids = known.mapTo(HashSet()) { it.id }
        return (seen + entries.map { it.id }).filterTo(sortedSetOf()) { it in ids }
    }

    /** The seen ids as kept in the app's settings (one string). */
    fun encode(seen: Set<String>): String = seen.sorted().joinToString(",")

    /** [encode]'s string back as ids (null or blank: none seen). */
    fun decode(kept: String?): Set<String> = kept.orEmpty().split(',').map { it.trim() }.filterTo(sortedSetOf()) { it.isNotEmpty() }

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /** "6 Oct". */
    fun day(d: LocalDate): String = d.format(DAY)

    /** The line under an entry: where to find it. */
    fun whereLine(e: Entry): String = "Where: ${e.where}"

    /** The line under an entry: the question to try (null: none). */
    fun askLine(e: Entry): String? = e.ask?.let { "Ask Jarvis: \"$it\"" }

    // ---- Jarvis -----------------------------------------------------------------------------------------------------

    private const val LEAD = "(?:(?:hey |ok |okay )?jarvis |boss |so |ok |okay |and |please |tell me |bata(?:o)? )*"
    private const val END = "(?: jarvis| boss| please| bhai| yaar)* $"
    private const val APP = "(?:the |this |your |our |my )?(?:app|apk|iraalgo|ira algo|update|latest update|new update|version|latest version|new version|build|latest build|new build|release|latest release)"
    private val ASKED = rx(
        // "What's new?", "what's new in the app", "what is new in this update", "show me what's new"
        "^ $LEAD(?:show me |so )?(?:what s|whats|what is|wats) new(?: (?:in|with|on) $APP)?$END|" +
        // "What has changed in the app", "what's changed in this build": only with the app named. A bare "what changed" is
        // SinceLast's (the market since Boss last asked).
        "^ $LEAD(?:what|wat) (?:s |has |have )?changed (?:in|with) $APP$END|" +
        "^ $LEAD(?:whats|what s) changed (?:in|with) $APP$END|" +
        // "What are the new features", "any new features", "new features", "what did you add", "what's in the new update"
        "^ $LEAD(?:what are the |any |show me the |list the )?new features(?: (?:in|of) $APP)?$END|" +
        "^ $LEAD(?:what|which) (?:new )?features (?:were|have been|got) added$END|" +
        "^ $LEAD(?:what s|whats|what is) in $APP$END|" +
        "^ $LEAD(?:show me |read me |open )?(?:the )?(?:changelog|change log|release notes|patch notes)$END|" +
        // Hinglish: "naya kya hai", "kya naya hai", "app mein naya kya hai", "naya kya aaya", "update mein kya badla"
        "^ $LEAD(?:(?:app|update|is update|naye update|nayi update|naya version|is version) (?:mein|me|main) )?(?:naya kya|kya naya)(?: hai| aaya| aaya hai| aya| aya hai| hua| hua hai| add hua| add hua hai)?(?: app (?:mein|me|main))?$END|" +
        "^ $LEAD(?:app|update|is update|naye update) (?:mein|me|main) kya (?:badla|change hua)(?: hai)?$END"
    )

    /** Does [text] ask what is new in the app? Never "what's new in the market", "any news", a bare "what changed" or "what's changed since I last asked" (SinceLast's). */
    fun asked(text: String): Boolean = askedKept.of(text) { ASKED.containsMatchIn(Spaced.words(text)) }

    private val askedKept = Kept<Boolean>(64)

    /** Jarvis's answer: the newest [max] of [entries], each with where it is and a question to try; then where the rest are. */
    fun answer(entries: List<Entry>, max: Int = SPOKEN): String {
        val all = newestFirst(entries)
        if (all.isEmpty()) return "Nothing new to tell you about the app just now, Boss."
        val shown = all.take(max)
        val days = shown.map { it.date }.distinct()
        val head = "What's new in the app" + (if (days.size == 1) " (${day(days[0])})" else "") + ", newest first:"
        val lines = shown.map { e ->
            "• ${e.title}" + (if (days.size > 1) " (${day(e.date)})" else "") + ": " +
                (e.ask?.let { "try \"$it\"." } ?: (e.where.trimEnd('.') + "."))
        }
        val rest = all.size - shown.size
        val tail = if (rest > 0) "And $rest more, each with where to find it, in $FULL_LIST." else "Each one with where to find it is in $FULL_LIST."
        return (listOf(head) + lines + tail).joinToString("\n")
    }
}
