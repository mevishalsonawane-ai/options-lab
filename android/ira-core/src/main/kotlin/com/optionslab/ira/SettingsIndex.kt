package com.optionslab.ira

/**
 * Settings search (07 Oct 2026): the Settings tab has grown to many pages and well over a hundred rows, and Boss could no
 * longer find "the quiet hours switch" or "where the backup is". One curated entry per setting: the page it is on (its
 * Settings drawer key: CabinetScreen's `Drawer(...)` / DrawerPage keys; IraGoldAlgo's own Settings is [GOLD_PAGE]), the row's
 * key (the app's `SettingSpot` key, which brings the row into view and makes it pulse; [Entry.key] == [Entry.page] when the
 * entry is the page itself), its title as the app shows it, a short description, its path and a few words Boss may use
 * instead ([Entry.synonyms]: "sound", "speak", "lots", "gift", "recorder"...).
 *
 * The search box at the top of Settings and Jarvis's "where is the X setting" ([SettingWhere]) both read it. NAVIGATION ONLY:
 * nothing here switches, sets or changes anything - a found row is only opened and highlighted, and every switch keeps its
 * own PIN, fingerprint or confirmation exactly as before ([Entry.guarded] says which ask for one). IraGoldAlgo shows only the
 * entries marked [Entry.gold] (its Settings are fewer). Pure.
 *
 * A test checks every entry's page against CabinetScreen's drawer keys (or GoldScreens' own keys) and every row key against
 * the app's source.
 */
object SettingsIndex {
    /**
     * One setting. [key]: the row's SettingSpot key ([page] itself for a whole page). [path]: the pages and cards above it,
     * from the page's title down (the title itself is added by [pathText]). [gold]: also in IraGoldAlgo's Settings; [ira]: in
     * IraAlgo's. [guarded]: switched only with Boss's PIN, fingerprint or a confirmation (kept as it is).
     */
    data class Entry(
        val page: String, val key: String, val title: String, val description: String, val path: List<String>,
        val synonyms: List<String> = emptyList(), val gold: Boolean = false, val ira: Boolean = true, val guarded: Boolean = false,
    ) {
        /** "Settings → Voice and AI model → What Jarvis does by itself → Market alerts". */
        val pathText: String get() = (listOf(ROOT) + path + (if (path.lastOrNull() == title) emptyList() else listOf(title))).joinToString(ARROW)

        /** The page itself (no row to bring into view). */
        val isPage: Boolean get() = key == page
    }

    const val ROOT = "Settings"
    const val ARROW = " → "

    /** IraGoldAlgo's own Settings tab (GoldScreens' GoldSettings): its rows are not in IraAlgo's drawers. */
    const val GOLD_PAGE = "gold"

    // ---- the catalogue ----------------------------------------------------------------------------------------------

    private const val JARVIS = "Voice and AI model"
    private const val BY_ITSELF = "What Jarvis does by itself"
    private val VOICE = listOf(JARVIS, "Voice")
    private val AUTO = listOf(JARVIS, BY_ITSELF)

    private fun voice(key: String, title: String, d: String, vararg syn: String) =
        Entry("jarvis", "jarvis.voice.$key", title, d, VOICE, syn.toList())

    private fun group(name: String, title: String, d: String, vararg syn: String, guarded: Boolean = false) =
        Entry("jarvis", "jarvis.switch.group.$name", title, d, AUTO, syn.toList(), guarded = guarded)

    private fun sub(name: String, group: String, title: String, d: String, vararg syn: String) =
        Entry("jarvis", "jarvis.switch.sub.$name", title, d, AUTO + group, syn.toList())

    /** A behaviour with no switch of its own: found by its name, its row is its group's switch. */
    private fun member(name: String, groupTitle: String, title: String, d: String, vararg syn: String) =
        Entry("jarvis", "jarvis.switch.group.$name", title, "$d Switched with $groupTitle.", AUTO + groupTitle, syn.toList(), guarded = name == "GUARD")

    private fun page(page: String, title: String, d: String, vararg syn: String) = Entry(page, page, title, d, listOf(title), syn.toList())

    private val SECURITY = listOf("Security")
    private val UNLOCKING = listOf("Security", "Unlocking")
    private val GUARD = listOf("Bot settings", "Account guard")
    private val DAY = listOf("Schedules", "The Day")
    private val ORDERS = listOf("Zerodha", "Real orders")

    val ENTRIES: List<Entry> = listOf(
        // ---- Settings → Voice and AI model (Jarvis) ----
        voice("deaf", "Don't listen (microphone off)", "Switch Jarvis's microphone off altogether; he still speaks and you can still type.",
            "microphone", "mic", "listening", "mat suno", "deaf"),
        voice("wake", "Listen for \"Jarvis\"", "Jarvis listens for his name, then your question.", "voice", "wake word", "listening", "hey jarvis", "mic"),
        voice("typed", "Say replies to typed questions aloud", "Answers to what you type are spoken too.", "speak", "sound", "voice", "read aloud"),
        voice("cutin", "Let me cut in while Jarvis talks", "Speak over Jarvis to stop him and ask something else.", "interrupt", "barge in", "talking"),
        voice("mute", "Mute Jarvis (replies on screen only)", "Nothing spoken; every reply stays on screen. Or say \"Jarvis, mute\".",
            "mute", "sound", "silent", "voice", "speak", "talking", "quiet"),
        voice("answers", "Answers: short or detailed", "One precise line, or the full answer, spoken and in the chat.", "short answers", "detailed", "length"),
        voice("speaks", "Jarvis speaks", "What Jarvis says aloud by himself; answers and safety warnings are always spoken.",
            "speak", "voice", "sound", "talking", "notes aloud", "notification"),
        voice("google", "Use Google's speech service", "Hears \"Jarvis\" faster through the phone's speech service; your speech may go to Google.",
            "speech", "recognizer", "voice typing"),
        voice("onlyme", "Answer only my voice", "Words in other voices (TV, people nearby) are ignored.", "voice print", "background noise", "tv"),
        voice("brief", "Short spoken answers", "Only the key line is said; the full answer stays on screen.", "speak", "brief"),
        voice("strict", "Harder to wake", "Wakes only when \"Jarvis\" starts what you say.", "false starts", "wake word"),
        voice("quiet", "Quiet hours 22:00 to 07:00", "Nothing is said unasked at night; Jarvis still answers when you ask.",
            "quiet", "night", "sleep", "sound", "speak"),
        voice("saver", "Battery saver for listening", "Listens less while nothing is happening; alerts and stops never wait on it.",
            "battery", "power", "listening"),
        voice("hindi", "Spoken replies in Hindi", "Each answer translated and spoken in Hindi, every figure checked.", "hindi", "language", "speak"),
        Entry("jarvis", "jarvis.voice", "Your voice", "Teach Jarvis your voice (a few phrases), so he can answer only you and voice can trade.", listOf(JARVIS, "Voice"),
            listOf("voice print", "enroll", "train")),
        Entry("jarvis", "jarvis.voice", "Copy diagnostics (for help)", "Everything the app knows about Jarvis's ears, to paste in the chat.",
            listOf(JARVIS, "Voice"), listOf("diagnostics", "help", "support", "debug")),
        Entry("jarvis", "jarvis.solo", "Solo: Jarvis trades by himself (paper)", "Solo (midday)'s switch, its forward test and record. Paper only, never Zerodha.",
            listOf(JARVIS, "Solo"), listOf("solo", "midday", "paper", "self trade")),
        Entry("jarvis", "jarvis.ailive", "AI trades go live", "Off: Jarvis's trades go on paper even in Live. On: in Live, each one asked first and approved with your fingerprint.",
            AUTO, listOf("zerodha", "real money", "live", "fingerprint", "ai trades"), guarded = true),
        group("GUARD", "Guard my positions", "Jarvis sets a stop on a bought option of yours that has none, then trails it up. Switched on with your fingerprint.",
            "stop", "stops", "trail", "fingerprint", "guard", guarded = true),
        group("HELP", "Offer help on my positions", "A position with no stop, or one going nowhere: Jarvis offers a stop or a close (asks first).", "stop", "help"),
        group("OWN", "Act on his own, on paper", "Takes his own news and pattern ideas on PAPER and plans the paper arms each morning.", "news", "ideas", "paper"),
        group("MARKET", "Market alerts", "Opening gap, the opening read, range breaks, VIX spikes, sharp moves, Liquidity levels, open interest, expiry day, news on what you hold.",
            "alerts", "notification", "liquidity", "gift", "news", "vix", "opening"),
        group("COACH", "Coach me", "Overtrading, day target, the 09:00 check, the 15:35 wrap-up, tomorrow's plan, journal, reviews, Solo and Hero against their backtests.",
            "coach", "hero", "solo", "review", "wrap up", "journal", "alerts"),
        group("QUIET", "Quiet hours", "Nothing said unasked from 22:00 to 07:00.", "quiet", "night", "sleep", "notification"),
        sub("LIQSKIP", "Market alerts", "Skipped Liquidity breaks", "A quiet notification when Liquidity 15+5 skips a break because the next level is too close.",
            "liquidity", "notification", "skipped"),
        sub("OPENING", "Market alerts", "Opening read", "Just after 09:20: the gaps against the previous close (and GIFT Nifty), the open against Liquidity's levels, the first candle.",
            "gift", "opening", "gap", "liquidity"),
        sub("FORWARD", "Coach me", "Forward-test watch", "Liquidity 15+5, Solo and Hero against their backtests: a note when an arm's verdict changes.",
            "hero", "solo", "liquidity", "backtest", "drifting"),
        sub("LIQINSIGHT", "Coach me", "What's working (Liquidity)", "Saturday morning: Liquidity 15+5's paper trades cut by time, book, side, exit, room and weekday - what stands out beyond noise.",
            "liquidity", "patterns", "working", "insight"),
        member("GUARD", "Guard my positions", "Trail my stops", "Your own bought options: stop to what you paid at +20%, then 15% under the best price.", "trail", "stop"),
        member("HELP", "Offer help on my positions", "Offer a stop", "A position of yours with no stop for 2 minutes: Jarvis offers one.", "stop loss"),
        member("HELP", "Offer help on my positions", "Trades going nowhere", "Open 45 minutes and within 5% of what you paid: Jarvis offers to close it.", "stale"),
        member("OWN", "Act on his own, on paper", "Act on my own ideas (paper)", "A news or pattern idea of 3/5 or more: taken on PAPER by itself.", "news", "ideas"),
        member("OWN", "Act on his own, on paper", "Plan my day (paper arms)", "09:00-10:00: a paper arm that lost on days like today is parked, and armed again when the market suits it.", "park", "arms"),
        member("MARKET", "Market alerts", "Opening gap plan", "09:16: the gap and how the arms did on such days.", "gap", "opening"),
        member("MARKET", "Market alerts", "Opening range breaks", "Nifty or BankNifty leaving its first 15 minutes' range.", "breakout", "range"),
        member("MARKET", "Market alerts", "Market moments", "A gap filling, or the previous session's high or low passed.", "gap fill", "high", "low"),
        member("MARKET", "Market alerts", "Fear spikes", "India VIX up 10% or more on the day.", "vix", "fear"),
        member("MARKET", "Market alerts", "Sharp moves: what coincided", "A sharp 10-minute move, with the headlines and VIX around it.", "news", "sharp move"),
        member("MARKET", "Market alerts", "Liquidity levels heads-up", "BankNifty or FinNifty near a Liquidity 15+5 entry level.", "liquidity", "levels"),
        member("MARKET", "Market alerts", "Open interest walls", "The biggest call or put open interest moving to a new strike.", "oi", "open interest"),
        member("MARKET", "Market alerts", "Expiry day companion", "Expiry day: the straddle, max pain and the last hour's range.", "expiry", "straddle", "max pain"),
        member("MARKET", "Market alerts", "News on your positions", "A headline on an index you hold: good or bad for your side.", "news", "headlines"),
        member("COACH", "Coach me", "Overtrading warning", "More than 3 of your buys in 30 minutes: a word to slow down.", "overtrade"),
        member("COACH", "Coach me", "Day target", "Your day's target reached: told to protect the gains.", "target", "goal"),
        member("COACH", "Coach me", "Position heads-ups", "A position losing half, then three quarters, of your daily loss limit.", "loss", "heads up"),
        member("COACH", "Coach me", "Your usual, unasked", "A question you ask at the same hour most days, answered at that hour.", "routine", "usual"),
        member("COACH", "Coach me", "Morning check aloud", "09:00: the morning check is spoken even with listening off.", "morning", "speak", "voice"),
        member("COACH", "Coach me", "My own plan for the day", "Jarvis plans his own day and works through it.", "agenda", "plan"),
        member("COACH", "Coach me", "15:35 wrap-up", "The day's P&L, scorecard and tomorrow's events, spoken.", "wrap up", "summary", "evening"),
        member("COACH", "Coach me", "Tomorrow's plan", "After the close: one note for the next session.", "tomorrow", "plan"),
        member("COACH", "Coach me", "Journal my day", "After the close: today's journal drafted, then up to three short questions.", "journal", "diary"),
        member("COACH", "Coach me", "Weekly review", "After the week's last session: your trades this week against last week.", "week", "review"),
        member("COACH", "Coach me", "Monthly review", "After the month's last session: your trades this month against last month.", "month", "review"),
        member("COACH", "Coach me", "A word before an order", "A gentle reminder on the order review after a loss or past your usual day.", "pretrade", "reminder"),
        member("COACH", "Coach me", "Expiry-day position check", "14:45 on a day a position of yours expires: each one checked.", "expiry", "positions"),
        member("COACH", "Coach me", "Your words against today", "A rule or trade goal you set, against your trades today.", "rules", "goals"),
        member("COACH", "Coach me", "Strategies behaving unusually", "A strategy taking far more trades, or losing more in a row, than its tested record.", "bots", "strategies"),
        Entry("jarvis", "jarvis.model", "AI model", "The on-device model: download, choose or delete it.", listOf(JARVIS, "AI model"), listOf("llm", "download", "model", "ai")),

        // ---- Settings → Zerodha ----
        Entry("broker", "broker.staticip", "Static IP (needed for live orders)", "The static IP Zerodha needs for orders, and the relay server.",
            listOf("Zerodha"), listOf("ip", "relay", "static ip")),
        Entry("broker", "broker.login", "Log in to Zerodha", "Today's session, the API key, log out, change or erase the keys.",
            listOf("Zerodha", "Connection"), listOf("kite", "login", "log in", "api key", "session", "logout")),
        Entry("broker", "broker.mode", "Trading mode: Live or Paper", "Live sends real orders to Zerodha; Paper never does.",
            listOf("Zerodha", "Mode"), listOf("live", "paper", "live mode", "sandbox", "real money")),
        Entry("broker", "broker.orders", "Prepare the expiry order at 11:01", "Builds today's ticket and notifies you to review it; never sent by itself.",
            ORDERS, listOf("expiry", "ticket", "real orders")),
        Entry("broker", "broker.orders", "Live orders without PIN", "Confirming the review sends the order at once, no PIN or fingerprint. Turning it on asks for your PIN.",
            ORDERS, listOf("pin", "one tap", "fingerprint", "real orders"), guarded = true),
        Entry("broker", "broker.orders", "Product: NRML or MIS", "The product the app's orders use.", ORDERS, listOf("nrml", "mis", "intraday", "product")),

        // ---- Settings → Alerts ----
        Entry("alarms", "alarms.set", "Set a price alarm", "On NIFTY, BANKNIFTY, India VIX or any instrument: rings when it falls below or rises above a level.",
            listOf("Alerts"), listOf("alarm", "alerts", "price alert", "notification", "sound")),
        Entry("alarms", "alarms.standing", "Standing alarms", "Your alarms: switch one off or remove it.", listOf("Alerts"), listOf("alarm", "alerts", "remove")),
        Entry("alarms", "alarms.pnl", "Account P&L alerts", "When today's loss or profit reaches an amount: rings once a day.",
            listOf("Alerts"), listOf("pnl", "loss alert", "profit alert", "alerts", "notification")),

        // ---- Settings → Security ----
        Entry("security", "security.certificate", "Zerodha certificate", "Certificate pinning for Zerodha; re-trusting a new certificate asks for your PIN.",
            SECURITY, listOf("pin", "pinning", "certificate", "ssl", "ca"), guarded = true),
        Entry("security", "security.backup", "Backup and restore", "Back up the app to a file with a passphrase, or restore one.",
            SECURITY, listOf("backup", "restore", "export", "passphrase"), guarded = true),
        Entry("security", "security.widget", "Show my P&L on the widget", "Off by default: a home screen is seen by anyone holding the unlocked phone.",
            listOf("Security", "Home-screen widget"), listOf("widget", "home screen", "pnl"), gold = true),
        Entry("security", "security.integrity", "Refuse compromised devices", "The device check: do not open on a rooted, hooked or debugged phone.",
            listOf("Security", "Device integrity"), listOf("root", "rooted", "device check", "integrity", "security"), gold = true, guarded = true),
        Entry("security", "security.unlock", "Fingerprint", "Unlock the app and confirm orders with your fingerprint.",
            UNLOCKING, listOf("fingerprint", "biometric", "unlock", "face"), gold = true, guarded = true),
        Entry("security", "security.unlock", "Allow screenshots and screen recording", "Blocked at once; allowing them asks for your PIN.",
            UNLOCKING, listOf("screenshot", "screen recording", "capture"), gold = true, guarded = true),
        Entry("security", "security.unlock", "Lock after idle for", "How long the app stays open untouched before it locks.",
            UNLOCKING, listOf("idle", "timeout", "auto lock", "lock"), gold = true, guarded = true),
        Entry("security", "security.unlock", "Erase after wrong PINs", "Destroys the encryption key after too many wrong PINs.",
            UNLOCKING, listOf("pin", "wipe", "wrong pin"), gold = true, guarded = true),
        Entry("security", "security.unlock", "Hide figures on the lock screen", "Notifications show only \"Unlock to read\" while the phone is locked.",
            UNLOCKING, listOf("lock screen", "notification", "privacy", "hide"), gold = true),
        Entry("security", "security.unlock", "Change PIN", "Your app PIN; the current one is asked first.", UNLOCKING,
            listOf("pin", "password", "passcode"), gold = true, guarded = true),
        Entry("security", "security.unlock", "Seal now", "Locks the app at once.", UNLOCKING, listOf("lock now", "lock"), gold = true),
        Entry("security", "security.erase", "Erase everything personal", "Wipes the app's personal data; asks you to confirm first.",
            SECURITY, listOf("wipe", "reset", "delete", "erase"), gold = true, guarded = true),

        // ---- Settings → Schedules ----
        Entry("schedule", "schedule.holidays", "Market holidays", "The exchange holidays the app keeps; update them or add one.",
            listOf("Schedules"), listOf("holiday", "calendar", "closed")),
        Entry("schedule", "schedule.notifications", "Other notifications", "Risk and P&L alerts, price alarms, reminders, health changes and warnings.",
            listOf("Schedules", "Notifications"), listOf("notification", "alerts", "sound", "pop up")),
        Entry("schedule", "schedule.day", "Entry reminder 10:55", "Expiry days only.", DAY, listOf("reminder", "expiry")),
        Entry("schedule", "schedule.day", "Paper ticket 11:01", "Expiry days: records the paper ticket for you.", DAY, listOf("ticket", "paper", "expiry")),
        Entry("schedule", "schedule.day", "Settle 15:35", "Settles today's open ticket at the official window.", DAY, listOf("settlement", "settle")),
        Entry("schedule", "schedule.day", "Harvest 15:45", "The nightly harvest after the close, then the health check.", DAY, listOf("harvest", "nightly", "data")),
        Entry("schedule", "schedule.day", "Tell me when health changes", "PASS, WARN or FAIL, after each harvest.", DAY, listOf("health", "notification")),
        Entry("schedule", "schedule.day", "Warn when the index is within", "How close to an open paper ticket's breakeven the risk alert rings.",
            DAY, listOf("risk alert", "breakeven", "alerts")),
        Entry("schedule", "schedule.permissions", "Battery: Unrestricted", "Android may stop the order watch while IraAlgo is battery-optimized.",
            listOf("Schedules", "Permissions"), listOf("battery", "optimization", "background", "power")),
        Entry("schedule", "schedule.permissions", "Notifications and precise alarms", "Whether Android lets the app notify you and run its jobs on time.",
            listOf("Schedules", "Permissions"), listOf("notification", "permission", "alarm", "exact alarms")),
        Entry("schedule", "schedule.appearance", "Theme", "Follow the phone, light or dark.", listOf("Schedules", "Appearance"), listOf("dark mode", "light", "look", "colours")),
        Entry("schedule", "schedule.appearance", "Calm motion", "Fewer and shorter animations.", listOf("Schedules", "Appearance"), listOf("animation", "motion")),

        // ---- Settings → Bot settings ----
        Entry("risk", "risk.guards", "Kill switch", "Stops all new entries at once; exits and square-offs still go through. Asks you to confirm both ways.",
            GUARD, listOf("kill", "emergency", "stop all", "block"), guarded = true),
        Entry("risk", "risk.guards", "Daily loss limit", "New entries refused once the day's loss reaches it.", GUARD, listOf("loss", "max loss", "limit")),
        Entry("risk", "risk.guards", "Max drawdown", "From capital and from the peak.", GUARD, listOf("drawdown", "limit")),
        Entry("risk", "risk.guards", "Max open positions", "How many positions may be open at once.", GUARD, listOf("positions", "limit")),
        Entry("risk", "risk.guards", "Max orders per day", "Entries, stops and exits, paper and live.", GUARD, listOf("orders", "trades per day", "limit")),
        Entry("risk", "risk.guards", "Max value per order", "The most one order may be worth.", GUARD, listOf("order value", "limit")),
        Entry("risk", "risk.guards", "Max lots per instrument", "The most lots held in one instrument.", GUARD, listOf("lots", "quantity", "lot size", "limit")),
        Entry("risk", "risk.guards", "Max held in one instrument", "The most money held in one instrument.", GUARD, listOf("exposure", "limit")),
        Entry("risk", "risk.guards", "No new entries after", "A time of day after which no new entries are taken.", GUARD, listOf("cutoff", "time", "late")),
        Entry("risk", "risk.guards", "Square off on expiry day at 15:05", "Closes every option position expiring today, paper and live.",
            GUARD, listOf("expiry", "square off", "auto close")),
        Entry("risk", "risk.guards", "Keep the Expiry Put to settlement", "Its legs are left for the 15:30 settlement.", GUARD, listOf("expiry put", "settlement")),
        Entry("risk", "risk.guards", "Block naked option shorts", "Selling to open needs a bought option of the same index, expiry and type first.",
            GUARD, listOf("naked", "short", "selling")),

        // ---- Settings → Data and harvest ----
        Entry("data", "data.record", "Include this phone's captures", "The days this phone collected extend the record forward.",
            listOf("Data and harvest", "The Record"), listOf("captures", "record", "data")),
        Entry("data", "data.harvest", "Harvest now", "Collect today's option chains and bars now.", listOf("Data and harvest", "The Harvest"), listOf("harvest", "download", "data")),
        Entry("data", "data.recorder", "Record market data", "The market recorder: what the app already reads, kept for a later study, and its export.",
            listOf("Data and harvest", "Market recorder"), listOf("recorder", "record", "gift", "fii", "export")),
        Entry("data", "data.provenance", "Verify the record", "Re-reads every bundled chain against its provenance.", listOf("Data and harvest", "Provenance"),
            listOf("provenance", "check", "verify")),
        Entry("data", "data.delete", "Delete this phone's harvested data", "The data collected on this phone; asks you to confirm first.",
            listOf("Data and harvest"), listOf("delete", "wipe", "storage", "space"), guarded = true),

        // ---- whole pages (Research, App) ----
        page("signal", "Signal lab", "UT Bot and LinReg on a harvested day.", "ut bot", "linreg", "signals"),
        page("ic", "IC table", "Is the data predictable, net of cost?", "ic", "predictable"),
        page("sizing", "Sizing and tail risk", "What a bad day costs.", "sizing", "tail risk", "position size"),
        page("costs", "Cost calculator", "Itemised charges and spread.", "charges", "brokerage", "costs", "fees"),
        page("lots", "Lot sizes", "NSE lot history, from bhavcopy.", "lots", "lot size", "quantity"),
        page("notes", "Research notes", "What was tested, and what was closed.", "research", "notes"),
        page("whatsnew", "What's new", "Recent changes to the app and where to find them.", "changelog", "update", "new"),
        page("askguide", "What can I ask?", "Every question Jarvis answers, by topic.", "questions", "help", "guide"),
        page("todaynotes", "Today's notes", "What Jarvis said by himself today, by category.", "notes", "news", "today"),

        // ---- IraGoldAlgo's own Settings ----
        Entry(GOLD_PAGE, "gold.paper", "Lot size", "The paper trade's size in lots.", listOf("Paper"), listOf("lots", "lot size", "quantity"), gold = true, ira = false),
        Entry(GOLD_PAGE, "gold.paper", "Start again with", "Reset the paper account to an amount; asks you to confirm first.", listOf("Paper"),
            listOf("reset", "paper amount", "balance", "capital"), gold = true, ira = false, guarded = true),
        Entry(GOLD_PAGE, "gold.background", "Running in the background", "Notifications, precise alarms and battery, so the arm decides every candle on time.",
            listOf("Running in the background"), listOf("battery", "notification", "alarm", "background", "permission"), gold = true, ira = false),
        Entry(GOLD_PAGE, "gold.look", "Theme", "Phone, light or dark.", listOf("Look"), listOf("dark mode", "light", "look", "colours"), gold = true, ira = false),
        Entry(GOLD_PAGE, "gold.help", "Copy diagnostics", "A report to paste in the chat; keys, tokens and passwords are never included.",
            listOf("Help"), listOf("diagnostics", "help", "support", "debug"), gold = true, ira = false),
    )

    /** The entries a build shows: IraGoldAlgo only those marked [Entry.gold], IraAlgo those marked [Entry.ira]. */
    fun forBuild(gold: Boolean): List<Entry> = ENTRIES.filter { if (gold) it.gold else it.ira }

    // ---- the search -------------------------------------------------------------------------------------------------

    /** Words that say nothing about which setting ("how do I turn off the..."). */
    private val STOP = setOf("the", "a", "an", "my", "me", "i", "to", "of", "on", "off", "in", "for", "and", "or", "is", "it", "this", "that",
        "turn", "switch", "setting", "settings", "option", "options", "toggle", "where", "how", "do", "can", "find", "change", "set",
        "ki", "ka", "ke", "kahan", "kaha", "hai", "kaise", "band", "karu", "karun", "please", "jarvis", "boss", "want", "with", "from")

    private fun words(s: String): List<String> = spacedWords(s.lowercase().replace("'", "").replace("’", "").replace("&", " and "))
        .split(' ').filter { it.isNotEmpty() }

    /** "alerts" and "alert" alike. */
    private fun stem(w: String) =
        if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") && !w.endsWith("ews")) w.dropLast(1) else w

    /** The words of [query] that name something ([STOP] words dropped), stemmed. */
    fun terms(query: String): List<String> = words(query).filter { it !in STOP }.map { stem(it) }.distinct()

    /** The words of [query] that name something, as said (not stemmed): what Boss would type in the search box. */
    fun said(query: String): List<String> = words(query).filter { it !in STOP }.distinct()

    private class Hay(val title: List<String>, val syn: List<String>, val rest: List<String>)

    private val hays: Map<Entry, Hay> by lazy {
        ENTRIES.associateWith { e ->
            Hay(words(e.title).map { stem(it) }, e.synonyms.flatMap { words(it) }.map { stem(it) },
                (words(e.description) + e.path.flatMap { words(it) }).map { stem(it) })
        }
    }

    private fun hits(ws: List<String>, t: String) = ws.any { it.startsWith(t) }

    /** How well [e] answers [ts]: 0 when a word is found nowhere; a word in the title counts most, then a synonym. */
    private fun score(e: Entry, ts: List<String>): Int {
        val h = hays.getValue(e)
        var total = 0
        for (t in ts) {
            total += when {
                hits(h.title, t) -> 4
                hits(h.syn, t) -> 3
                hits(h.rest, t) -> 1
                else -> return 0
            }
        }
        return total
    }

    /**
     * The settings matching [query], best first (every word found in the title, a synonym, the description or the path - a
     * word may be the start of one: "notif"); a blank query or one of only [STOP] words finds nothing. [gold]: IraGoldAlgo's
     * entries only. [pages]: only the pages this build shows (null: all).
     */
    fun search(query: String, gold: Boolean, pages: Set<String>? = null): List<Entry> {
        val ts = terms(query)
        if (ts.isEmpty()) return emptyList()
        val phrase = words(query).joinToString(" ")
        return forBuild(gold).asSequence().filter { pages == null || it.page in pages }
            .map { e -> e to score(e, ts) + (if (words(e.title).joinToString(" ").startsWith(phrase)) 2 else 0) }
            .filter { it.second > 0 }
            .withIndex().sortedWith(compareBy({ -it.value.second }, { it.index })).map { it.value.first }.toList()
    }

    /** Only the entries whose every word is in their title or a synonym ([search]'s strongest), best first. */
    fun strong(query: String, gold: Boolean): List<Entry> {
        val ts = terms(query)
        if (ts.isEmpty()) return emptyList()
        return search(query, gold).filter { e -> val h = hays.getValue(e); ts.all { hits(h.title, it) || hits(h.syn, it) } }
    }
}
