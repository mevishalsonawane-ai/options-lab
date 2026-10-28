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
        /** One or two plain sentences: what changed (a few related fixes: up to five short ones). */
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
    private val OCT8: LocalDate = LocalDate.of(2026, 10, 8)
    private val OCT9: LocalDate = LocalDate.of(2026, 10, 9)
    private val OCT10: LocalDate = LocalDate.of(2026, 10, 10)
    private val OCT11: LocalDate = LocalDate.of(2026, 10, 11)
    private val OCT12: LocalDate = LocalDate.of(2026, 10, 12)
    private val OCT13: LocalDate = LocalDate.of(2026, 10, 13)
    private val OCT14: LocalDate = LocalDate.of(2026, 10, 14)
    private val OCT15: LocalDate = LocalDate.of(2026, 10, 15)
    private val OCT16: LocalDate = LocalDate.of(2026, 10, 16)
    private val OCT17: LocalDate = LocalDate.of(2026, 10, 17)
    private val OCT18: LocalDate = LocalDate.of(2026, 10, 18)
    private val OCT19: LocalDate = LocalDate.of(2026, 10, 19)
    private val OCT20: LocalDate = LocalDate.of(2026, 10, 20)
    private val OCT21: LocalDate = LocalDate.of(2026, 10, 21)
    private val OCT22: LocalDate = LocalDate.of(2026, 10, 22)
    private val OCT25: LocalDate = LocalDate.of(2026, 10, 25)
    private val OCT26: LocalDate = LocalDate.of(2026, 10, 26)
    private val OCT27: LocalDate = LocalDate.of(2026, 10, 27)
    private val OCT28: LocalDate = LocalDate.of(2026, 10, 28)
    private const val JARVIS_CHAT = "Ask Jarvis, by voice or in the chat (Home → Ira)"

    /** Every entry, newest first (within a day in the order the changes came). */
    val ENTRIES: List<Entry> = listOf(
        // 28 Oct (Boss: "Dont create a pine script or stratergies automatically, until 65% + winrate is found"): newest of all.
        Entry("2026-10-28-auto-ideas-65", OCT28, "Automatic ideas now need a 65% win rate",
            "Nothing new is made by itself unless it wins at least 65% of its trades. " +
                "That covers forge ideas, strategy lab variants, shadow re-arms and Jarvis's own Pine scripts. " +
                "The win rate must come from a forward record or a walk-forward test, after charges and slippage. " +
                "It also needs enough trades and a profit, on top of every test it already passes. " +
                "Below the rule an idea is only a note, held in the change log and the Strategy lab. " +
                "What you make or switch on yourself is not touched, and running arms stay on. " +
                "Changing the 65% needs your PIN, and one tap in the change timeline undoes the rule.",
            "Settings → Bot settings → Automatic ideas: minimum win rate; the change timeline; the Strategy lab"),
        // 28 Oct (Boss: "Add s5 trail to arms directly, if it does not perform I will turn it off"): the 65% rule came later that day, above it.
        Entry("2026-10-28-s5-trail-paper-arm", OCT28, "S5 Trail now trades on paper",
            "S5 Trail is the research network's morning pick, now a paper arm. " +
                "At 09:20, 09:25 and 09:30 it reads the five indices, India VIX and the option chain. " +
                "When the best expected net is ₹300 or more, it buys 1 lot of that ATM option on paper. " +
                "Its stop is −₹1,000 or −₹2,000 net. Once a trade is up ₹1,000, it locks 60% of the best. " +
                "It failed the research gates, so it runs as your exception, on paper only and never live. " +
                "It starts on, and one tap in the change timeline or on its row switches it off.",
            "Home → Strategies → its row; the change timeline",
            ask = "how is s5 trail doing"),
        // 28 Oct (Boss: "Yes fix finnifty"; R72 and R51's FINNIFTY entries "not bought: no fresh price"); S5 Trail's entry came later that day, above it.
        Entry("2026-10-28-finnifty-book-price", OCT28, "Untraded options can now be bought on paper",
            "FINNIFTY's 09:20 put and 11:05 call were not bought today: no fresh price. " +
                "Those options had not traded yet, so Zerodha showed no last price. " +
                "Their live bid and ask were there all along. " +
                "A paper buy now fills at the ask when the spread is sane. " +
                "A wide or one-sided book is refused, and the reason is said. " +
                "Stops and targets of such a position read the bid, so they are never slower. " +
                "Such a buy is never copied live. " +
                "One tap in the change timeline switches it off.",
            "Home → Strategies → each arm's row; the change timeline"),
        // 28 Oct (Boss: "Also check flow page on Irathinker and implement that too"; "Use live data from zerodha instead"; "Dont add
        // alert, just take orders from flow, and keep it for index only"): newest of all.
        Entry("2026-10-28-flow-paper-arm", OCT28, "Your alert app's flow now trades on paper",
            "The flow page of your alert app is now in this app, for indices only. " +
                "It runs the scanner, the score, the time machine and the risk gate on Zerodha's live prices. " +
                "A plan that passes all four becomes a paper order on a NIFTY, BANKNIFTY or FINNIFTY option. " +
                "There is no alert: the order shows on its ticket and in the change timeline. " +
                "It is not proven: in research no strategy of your alert app passed. " +
                "It starts on, on paper, and one tap in the change timeline switches it off. " +
                "Live needs your PIN and its own Trade live switch, which starts off.",
            "Settings → Automation → the flow page; Home → Strategies → its row",
            ask = "how is the flow arm doing"),
        // 27 Oct (Boss: "Check the battery usage too; the battery drained a lot today"; android/BATTERY_AUDIT_2026-10-27.md).
        Entry("2026-10-27-battery-audit", OCT27, "Less battery when nothing is held",
            "With nothing held, the Candle agent and Jarvis's background words rest at 30% battery or under the saver. " +
                "On the MCX evening the Candle agent reads only while something is held there. " +
                "The order flow's practice reads pause on a low battery. " +
                "The app asks the phone for its battery and screen far less often. " +
                "Open positions and orders never slow down: stops, targets, the stream and the watch run at full speed. " +
                "The uploaded diagnostics now carry the day's Battery today line.",
            "Lab → Health → Battery today (also in the diagnostics); android/BATTERY_AUDIT_2026-10-27.md"),
        // 27 Oct (research/LIQUIDITY_PAPER_CHECK_2026-10-27.md): newest of all.
        Entry("2026-10-27-paper-limit-pays-ask", OCT27, "Paper limit buys pay the real ask",
            "A paper buy with a price cap now fills at the ask when a real quote is there. " +
                "Before, it could fill on an old last price plus the spread. " +
                "On 26 Oct a FINNIFTY option was bought at 256.90 while the market traded 237.80 to 246.65. " +
                "If the ask is above the cap, the order waits. Paper only; live orders are not touched.",
            "Trade → Paper → tap a trade or an order"),
        // 26 Oct (the IraThinker review, irathinker-app/REVIEW_2026-10-26.md): newest of all.
        Entry("2026-10-26-ntfy-irathinker-fit", OCT26, "ntfy alerts follow your alert app's own exits",
            "Your alert app's mom60 trades now follow its own stop and hold, only where tighter or earlier. " +
                "This app's stop stays when the signal's is wider: never a wider stop. " +
                "These trades are labelled mom60, a paper signal, and not a proven strategy. " +
                "A strangle or any sale is shown as information: this app only buys options. " +
                "A stock strike off the exchange's strike grid is refused, saying when the sender estimated it. " +
                "The stale check uses the signal's sending time, as before.",
            "Settings → Automation → ntfy alerts → Alerts and Open",
            ask = "what did ntfy say"),
        // 26 Oct (research R105: the paper Donchian trades against the backtest).
        Entry("2026-10-26-donchian-paper-fixes", OCT26, "Donchian paper trades follow the research closer",
            "A paper stop or lock now books at its level, as a resting order would fill, not the later price. " +
                "Stops and locks are checked every 15 seconds even when prices are slow to arrive. " +
                "A Donchian break the phone could not settle at the bar close is tried again within 10 seconds. " +
                "Past expiry days move back to the trading day before a holiday, never the day after. " +
                "The Supertrend filter was checked against the research, bar by bar, and matches. " +
                "The stop check's time limit covers live stops too; the rest is paper only.",
            "Paper arms → R72 Breadth Donchian and R51 Donchian 20; the diagnostics' stop-check line"),
        // 26 Oct (the erase audit): "Erase everything" missed some stores, which saved their books again.
        Entry("2026-10-26-erase-all-stores", OCT26, "Erase everything now erases every book",
            "Erase everything missed some paper books, the risk records and parts of Jarvis's memory. " +
                "They kept a copy in memory and saved it again after the erase. " +
                "Now every store is cleared, in memory and on the phone, and a check covers each one. " +
                "Public market data still stays.",
            "More → Security → Erase everything personal"),
        // 26 Oct (Boss: "I think ntfy messages are getting read late, please check").
        Entry("2026-10-26-ntfy-faster", OCT26, "ntfy messages are acted on within seconds",
            "A message used to wait for the next minute's check; now it is decided within seconds of arriving. " +
                "The reader starts at 09:00 and, if it stops, starts again within a few seconds. " +
                "A dropped line is tried again within 15 seconds, not a minute. " +
                "Each message shows how many seconds it took to be read and decided, and today's line sums them up. " +
                "Every rule, limit and switch applies as before.",
            "Settings → Automation → ntfy alerts → Alerts; the diagnostics' ntfy line",
            ask = "what did ntfy say"),
        // 25 Oct (Boss: "Do whatever you think better for application", on the audits' open decisions).
        Entry("2026-10-25-boss-decisions", OCT25, "Your risk decisions are in",
            "Your account stop rule now also stops new paper entries, and a paper week down ₹20,000 stops them until Monday. " +
                "A paper trade risking over 2% of the account to its stop is sized down or skipped. " +
                "A second arm betting the other way on the same index is skipped on paper. " +
                "Liquidity buys on paper with a limit 0.5% over the signal price, and skips its index's expiry day. " +
                "The Hero ticket's trail starts at the fill; NG evening breakout is off; Night keeps its history while hidden. " +
                "Stops are looked at every 5 seconds while prices are quiet, and each entry's implied volatility is recorded. " +
                "R72 keeps a place under the cap only when it has a break due, and the Muhurat Sunday is listed. " +
                "Live trades only log these rules for now, and each paper change undoes in one tap.",
            "Bot settings → Risk rules; Autopilot → change timeline; the weekly stop's amount is on the Account guard card"),
        // 25 Oct (Boss: "find out what all the issues we have in the application", after the books): the app audit's fixes.
        Entry("2026-10-25-app-audit-fixes", OCT25, "Fixes from the app audit against the books",
            "Paper fills are now measured, stops against their trigger, and the diagnostics show them. " +
                "A moment's failed read can no longer switch the passed arms off or forget what they hold. " +
                "An MCX position is sold for today's stop only when that stop applies to MCX. " +
                "Switching an arm off from its row now shows in the change timeline, and one tap undoes it. " +
                "The daily loss stop's message lists each position and says when two arms bet opposite ways. " +
                "The paper loss limit's row says it sells what the bots hold, and loosening it asks your PIN. " +
                "A price stream sending heartbeats but no prices is opened again after 20 seconds.",
            "Autopilot → change timeline; Bot settings → Account guard; the full audit is in the repository's research folder"),
        // 25 Oct (Boss: "remove telegram full feature and add a new feature which will read message from ntfy"): under the app audit's entry.
        Entry("2026-10-25-ntfy-alerts", OCT25, "ntfy alerts replace Telegram",
            "Telegram is gone: its login, the Signal tester, its paper arm and their data on the phone. " +
                "Any open paper trade it held is still watched until it closes. " +
                "New: the app reads your ntfy topic, each message once, and shows it under Alerts with what it decided. " +
                "A clear buy message buys that index or stock option on paper, with a stop always. " +
                "Your signal messages are read too; test signals are shown, never traded. " +
                "Unclear, old or out-of-hours messages are not traded, and the reason is shown. " +
                "It is off until you switch it on, and live needs its own switch and your PIN. " +
                "It is an outside message, not a tested strategy.",
            "Settings → Automation → ntfy alerts (type your topic, then Test)",
            ask = "what did ntfy say"),
        // 22 Oct (Boss: borrow four ideas from Jake Schincariol's day-trader skill): the time machine, the order fingerprint, the three gates, the flat-by.
        Entry("2026-10-22-day-trader-borrow", OCT22, "Four safety ideas from a day-trader kit",
            "Every bot entry now gets a reality check from the index's own past days. " +
                "It says how often the target or the stop came first, and a one-word verdict. " +
                "It only reports; no trade is skipped, resized or delayed. " +
                "A real order goes only when it matches the exact order its checks cleared today. " +
                "Your own real order shows three gates: the checks, a typed yes, and your PIN. " +
                "The typed yes is on by default; turning it off asks your PIN. " +
                "Any bot position still open at 15:25 (stocks 15:20, MCX 10 minutes before its close) is closed. " +
                "Positions held by design are left alone.",
            "Home → Strategies → each bot's row; Settings → Zerodha → Real orders; Home → Automation card → Flat-by",
            ask = "what did the time machine say"),
        // 22 Oct (Boss: "Check each and every arm also hidden arms"): the arms audit's bug fixes, newest of all.
        Entry("2026-10-22-arms-audit-fixes", OCT22, "Fixes from the arms audit",
            "Every arm, the hidden ones too, was checked line by line against its research. " +
                "An arm's exit can no longer sell into a short when the 15:05 square-off got there first. " +
                "A hidden Pine script or shadow waiting for its exit opens nothing new. " +
                "While the hidden-arms list cannot be read, Jarvis's and the forge's ideas stay blocked and the list is read again. " +
                "An ORB time exit or your Stop for today sells even when no price can be read. " +
                "The US-night silver short's stop now rests in the paper book, and never trades against the trend arm's leg. " +
                "Jarvis's own trade ideas stay on paper, always, and would-be trades no longer count in the 40-trade test. " +
                "The day-lock line now shows the day's P&L beside its limit, not the limit alone.",
            "Home → Strategies → each arm's row; the audit table is in the repository's RESULT.md"),
        // 22 Oct (Boss: "Check why other arms did not trigger today"): fixes from the 22 Oct diagnostics.
        Entry("2026-10-22-diagnostics-fixes", OCT22, "Fixes from your 22 Oct diagnostics",
            "R72 Breadth Donchian, your primary arm, decides first and keeps one same-direction place at each bar close. " +
                "A passed arm whose option another paper arm holds keeps a would-be trade that names that arm. " +
                "A missed break now says why, and each passed arm has its own line in the uploaded diagnostics. " +
                "The passed arms follow their indices from 09:16, so 09:20 breaks are judged at 09:20. " +
                "Open positions' stops are looked at every 15 seconds on their own, never waiting behind other work. " +
                "Liquidity and ORB read the option's price at the signal and try once more when it is missing. " +
                "The Hero ₹1,000 ticket's diary says who switched it: you from its row, or an Undo. " +
                "Night start-ups are lighter, the night forge asks the battery far less, and the watch never runs past midnight.",
            "Home → Strategies → the passed arms' rows; Home → Strategies → Hero ₹1,000 ticket"),
        // 22 Oct (Boss: "if the 10% is received, make the stoploss to the start point"): their own paper switch (R100 A4 did not pass).
        Entry("2026-10-22-liquidity-breakeven", OCT22, "Your own Liquidity switch: breakeven at +10%",
            "A new switch can move a Liquidity paper trade's stop to the price paid once it is up 10%. " +
                "The stop also covers the charges, so a sale there loses only slippage, about ₹100 a lot. " +
                "It is off until you switch it on, and live trades never use it. " +
                "It did not pass research: 1,238 past trades made ₹2,45,083 with it and ₹2,68,404 without. " +
                "Its trades are labelled and kept apart from the plain-rules record. " +
                "Each flip shows in the Autopilot change timeline, and one tap undoes it.",
            "Home → Automation card → Strategy lab (Liquidity's breakeven at +10%); Autopilot → change timeline"),
        // 22 Oct (Boss: a Liquidity trade went up to about +Rs 4,500 and closed about -Rs 1,000): two exit fixes and research R100.
        Entry("2026-10-22-liquidity-exit-fixes", OCT22, "Liquidity exits keep working when one feed fails",
            "One index's failed candle read no longer stops the other Liquidity positions' exits. " +
                "Its own positions still get their 20-minute time stop, their -15% stop and 15:10. " +
                "On paper, after a restart or long stall, a lock or trail now counts the highest price in the gap. " +
                "Research R100 tested seven ways to keep a big open profit on Liquidity, and none passed. " +
                "Liquidity's paper and live rules stay as they were.",
            "Home → Dashboard → Strategies card → Liquidity 15+5 row; Copy diagnostics (Settings → Zerodha)"),
        // 21 Oct (Boss: "Add FT3 to the app paper bot until live, when live is started then it should move to live").
        Entry("2026-10-21-ft3-paper", OCT21, "FT3 forward test trades on paper",
            "FT3, the strongest hero-zero near-miss from research, now trades on paper on each index's expiry day. " +
                "From 11:00 to 12:55 it buys ₹5,000 of one cheap option near the money when its rule fits. " +
                "It sells at 15:05 and has no stop on paper. " +
                "It did not pass research, so its paper record is the forward test. " +
                "Live goes the usual way: Live with your PIN, AI trades live, its own switch, and Shadow off. " +
                "Live buys at most your lot cap and rests a disaster stop 80% below the price paid.",
            "Home → Strategies → FT3 near-ATM morning; Settings → Zerodha for its Trade live and Shadow",
            ask = "how is FT3 doing"),
        // 21 Oct (Boss, 20 Oct: "run a loop for enhancement and adding new features in the application"): round 5.
        Entry("2026-10-21-enhance-round-5", OCT21, "One GTT counted; payouts checked next morning",
            "Bot settings now counts what the one GTT did, today and over the last week. " +
                "It shows how many were placed, kept at the open, trailed, fired or replaced by two orders. " +
                "The guard against selling a filled exit twice no longer mistakes a position you converted for a sale. " +
                "A payout now counts only once the next morning's opening balance shows the money left. " +
                "The payout check only logs until you switch it on, as before.",
            "Bot settings → Stop and target as one GTT; Payouts and the drawdown"),
        // 21 Oct (Boss, 20 Oct: "run a loop for enhancement and adding new features in the application"): round 4.
        Entry("2026-10-21-enhance-round-4", OCT21, "Stop and target as one GTT; payouts in the drawdown",
            "A carried position's stop and target can rest at Zerodha as one GTT all day. " +
                "Whichever fires, Zerodha cancels the other, even with your phone off. " +
                "It is off until you switch it on with your PIN. " +
                "The guard against selling a filled exit twice now covers a stop resting since the morning too. " +
                "Money taken out of Zerodha is now counted when a drawdown stops entries. " +
                "It only logs until you switch it on, and never clears the kill switch by itself.",
            "Bot settings → Stop and target as one GTT; Payouts and the drawdown"),
        // 21 Oct (Boss: "Yes, give commodities their own day lock"), with fixes from his 21 Oct diagnostics.
        Entry("2026-10-21-commodities-own-lock", OCT21, "Commodities get their own paper day lock",
            "MCX paper bots now have their own day lock and daily loss limit on paper. " +
                "An NSE win or loss no longer stops the MCX evening bots, and MCX results never stop NSE bots. " +
                "Your stop for today, the kill switch and every Zerodha limit still stop both. " +
                "Home and the diagnostics now say plainly when a day lock is reached, with the amount and time. " +
                "US-night silver now reads the rupee from hourly prices, so it can trade again. " +
                "The candle agent no longer calls a live one minute chart stale. " +
                "Jarvis's slow pattern check now runs on its own and never holds his other checks. " +
                "Notes from an earlier day on the paper bots now show their date.",
            "Bot settings → Commodities day lock (paper); Home → MCX card; Autopilot → change timeline"),
        // 21 Oct (Boss, 20 Oct: "run a loop for enhancement and adding new features in the application"): round 3.
        Entry("2026-10-21-enhance-round-3", OCT21, "No filled exit sold twice; Sunday review catch-up",
            "Zerodha's positions can trail an exit that already filled, so a second exit could sell it again. " +
                "A new guard reads them again and never sells a filled exit twice. " +
                "It only logs what it would do until you switch it on in Bot settings. " +
                "Open paper positions now stay on the live price stream until they close. " +
                "If your phone was off or locked at 18:00, Jarvis says the Sunday review when you unlock it that evening. " +
                "Copied diagnostics are marked private, so the phone hides their preview. " +
                "Liquidity BANKNIFTY's live stop rule now keeps every live trade, so it never forgets its best day.",
            "Bot settings → One exit at a time; Jarvis on Sunday evenings"),
        // 20 Oct (Boss: "run a loop for enhancement and adding new features in the application"): round 2.
        Entry("2026-10-20-enhance-round-2", OCT20, "Exits take turns; MCX stops overnight; Sunday review",
            "Two of the app's exits on one contract now take turns, so one contract is never sold twice. " +
                "It only logs what it would do until you switch it on in Bot settings. " +
                "It never changes a size, and every exit still goes within a few seconds. " +
                "A carried MCX stop now rests at Zerodha overnight after MCX's late close. " +
                "Your account stop rule now reads each day after its charges, as the arms do. " +
                "The Candles page shows when it last read, with a chip for each instrument. " +
                "Only 5 and 15 minute candles become lessons; 1 minute ones are explained only. " +
                "On Sundays Jarvis reads you a short review of the week, paper and live apart, never on a locked phone.",
            "Bot settings → One exit at a time; Research → Candles; Jarvis on Sunday evenings"),
        // 20 Oct (Boss: "...after that run a loop for enhancement and adding new features in the application"): round 1.
        Entry("2026-10-20-enhance-round-1", OCT20, "Stops held overnight, no double contracts",
            "A carried position's stop and target now rest at Zerodha overnight, so a dead phone misses nothing. " +
                "On paper, the two Liquidity books no longer buy the same option; the second becomes a would-be trade. " +
                "Your account stop rule now watches the whole Zerodha account; it only logs until you switch it on. " +
                "The stop checks while holding now run every 15 seconds from start to start, and late ones are counted. " +
                "Every worker now uses one shared check of what is held, so none slows a stop by mistake. " +
                "The daily diagnostics count the entries the same-direction cap only logged.",
            "Bot settings → account stop rule; Home → Automation → Strategy lab (the no-twin rule)"),
        // 20 Oct (Boss: "Deep analysis of the whole application, find if there are any issues and resolve them").
        Entry("2026-10-20-deep-review-fixes", OCT20, "Whole app review: safer exits, honest records",
            "The 15:05 expiry exit reads orders first, so an exit that just filled is never sent twice. " +
                "Expiring SENSEX options are closed too, and an arm's own live trade is left to its own exit. " +
                "A carried position's stop and target at Zerodha are placed again when the next session opens. " +
                "The same-direction cap now counts every bot entry, and catches four orders sent on one signal. " +
                "Low battery never slows the stop checks, and the index prices stay on the live stream. " +
                "The Candle agent reads only finished candles and lists the newest first. " +
                "Backups, erasing and the data upload are safer; the upload goes only to a private repository.",
            "Trade → positions (the stops); Bot settings → same-direction cap; Research → Candles"),
        // 20 Oct (Boss: "Resolve all the problems/issues pushed to git by harvest"): the 19 Oct diagnostics file, fixed.
        Entry("2026-10-20-diagnostics-fixes", OCT20, "Fixes from your 19 Oct diagnostics",
            "The diagnostics now count only real failures as errors; warnings and notices are listed apart. " +
                "Jarvis's log is saved in the background, so the screen no longer waits for it. " +
                "A paper ticket's risk notice is said once, even after a restart, and says below the breakeven plainly. " +
                "The backup passphrase hint now says exactly how long it must be. " +
                "Your own phone's network trouble is no longer blamed on Zerodha. " +
                "The 10:00 gamma line is kept after a restart, and the late diagnostics file reads the right day. " +
                "No rupee figure of the passed arms leaves the phone in the diagnostics.",
            "Data & Harvest → GitHub sync (the daily diagnostics file); Settings → Backup (the passphrase)"),
        // 20 Oct (Boss: "add a new agent in the application who will only study the candle patterns").
        Entry("2026-10-20-candle-agent", OCT20, "Candle agent: every candle read and explained",
            "A new Candle agent studies each closed candle on NIFTY, BANKNIFTY, SENSEX and four MCX futures. " +
                "It says why a candle is green or red, and where its support and resistance came from. " +
                "It finds the earlier level a candle took support from, or the old high a rally ran into. " +
                "It shows the big players' hand: stop runs, heavy volume, option writers and sweeps. " +
                "It adds what that kind of candle did in our research, with the sample size and no edge said plainly. " +
                "It learns around the clock, shares its reads with the other agents and hears theirs. " +
                "Its own counts become Jarvis's lessons; any proposal stays a note for you. " +
                "It never trades, and it is among the first things let go when memory is short.",
            "Research → Candles", ask = "why was that candle green", to = "candles"),
        // 19 Oct (Boss: "Yes, keep only breadth donchain").
        Entry("2026-10-19-r51-record-only", OCT19, "R51 Donchian 20 records only; R72 trades",
            "Only R72 Breadth Donchian now buys the Donchian breaks on paper, as you asked. " +
                "R51 Donchian 20 is set to record only. " +
                "It still decides every break, but buys nothing; each one is kept as a would-be trade. " +
                "Would-be trades close by their own stop, target and time exit, and are counted apart. " +
                "So the two can still be compared on the same days. " +
                "Each passed arm's row now offers on, record only or off. " +
                "The change is in the Autopilot change log, and one tap puts R51 back on.",
            "Home → Strategies → R51 Donchian 20 (Record only); undo it in the Autopilot change log",
            ask = "how is the donchian arm doing", jarvisOnly = true),
        Entry("2026-10-19-exposure-speed", OCT19, "Faster paper orders and a cap on same-way bets",
            "Paper orders now go in well under a second, because saving the books no longer holds them up. " +
                "At most two automatic paper option positions may bet the same way on the indices at once. " +
                "Later ones are refused with the reason and kept as would-be trades, so every record still compares. " +
                "Exits and your own orders are never capped, and Liquidity's refused entries are counted apart. " +
                "On live it only logs what it would refuse, until you switch it on. " +
                "R51 and R72 no longer buy the same option twice; the second is kept as a would-be trade.",
            "Settings → Bot settings → Same-direction cap; undo it in the Autopilot change log"),
        Entry("2026-10-18-arms-live", OCT18, "Every paper strategy can trade live, starting in Shadow",
            "Every paper strategy now has its own Trade live switch, so it can trade live too. " +
                "Each one starts in Shadow: it records what it would do and sends nothing to Zerodha. " +
                "Turning Shadow off needs your PIN, and you can turn it back on any time. " +
                "Shadow records use Zerodha's ask and bid, the app's measured slippage and the charges. " +
                "Live stops rest at Zerodha, as a GTT for trades carried overnight. " +
                "Strategies with an index stop get a safety stop 30% below the fill; Hero and the Hero ticket get 80%. " +
                "Every switch is checked again just before an order goes, and a check that fails blocks the entry. " +
                "Exits, stops and square-offs always go.",
            "Settings → Zerodha, beside the Live switch: each strategy's Trade live and Shadow"),
        Entry("2026-10-18-live-check", OCT18, "Live check: test Zerodha, prices and orders in one tap",
            "The Live check tests your Zerodha login, funds, prices, option chain and order margins, and never places an order. " +
                "It times the price stream, its gaps and a reconnect, and checks each open position's stop is watched. " +
                "A paper trade at live prices is bought and closed, and kept out of every result. " +
                "Each step shows its time and passed, slow or failed. " +
                "Before a stop is judged, a price over two seconds old is checked with Zerodha first. " +
                "Prices are read faster, and the price stream never waits on saved data.",
            "More → Zerodha → Live check, or ask Jarvis to run the live check"),
        Entry("2026-10-18-live-like-paper", OCT18, "Live works like paper: your PIN, then the order goes",
            "In Live, your Buy, Sell and Close go straight to Zerodha after your PIN, with no review card. " +
                "Every check still runs first: the guard, kill switch, day lock, margin, lot size and market hours. " +
                "Going Live asks your PIN once and turns on AI trades too; going back to Paper turns both off. " +
                "Each arm keeps its own live choice, and Jarvis's own ideas stay on paper. " +
                "During your paper-only period no new live entry goes out, but exits, stops and square-offs always do. " +
                "In Paper you can still close a real Zerodha position with your PIN. " +
                "Orders Zerodha took despite an error reply are now found and protected, never left without a stop. " +
                "Live orders without PIN was switched off by this update; turn it on again only if you want it.",
            "Settings → Zerodha for the Live switch; Trade tab for Still open at Zerodha"),
        Entry("2026-10-18-whole-app-review", OCT18, "Kill switch needs your PIN; Jarvis quiet when locked",
            "The kill switch can no longer be cleared by mistake, and clearing it asks for your PIN. " +
                "Jarvis never says a request or an order's result aloud on a locked phone. " +
                "The MCX expiry exit reads your positions fresh before it sells. " +
                "Stops are now checked for strategy legs and for short positions too. " +
                "Live cards keep their last figures when a read fails, instead of going blank. " +
                "Paper arm fixes: Night, Gold lots, Signal tester stops and sandbox MIS orders. " +
                "Screens never wait on reading or saving data, so they stay smooth. " +
                "Login tokens are hidden in the app's logs.",
            "Home → Strategies card and Settings → Account guard for the kill switch"),
        Entry("2026-10-17-diagnostics-fixes", OCT17, "No screen freezes on saves; MCX evenings kept awake",
            "A switch or setting saved on a screen no longer freezes it: the save finishes in the background. " +
                "While the watch runs for a market, it keeps the phone awake, so evening MCX checks never sleep. " +
                "A watch problem clears itself once the watch runs again or MCX closes. " +
                "After a restart, the watch starts at once, before the slower books are read. " +
                "Diagnostics now say truly whether Telegram's login is in the app and logged in. " +
                "They also name the code behind any screen freeze.",
            "Copy diagnostics (Settings → Zerodha); Home → Automation card for open problems"),
        Entry("2026-10-16-donchian-supertrend", OCT16, "R51 Donchian 20 waits for Supertrend to agree (your ask)",
            "New paper trades of R51 Donchian 20 now need Supertrend (10, 3) to agree with the break. " +
                "Research R73 made +₹1,34,743 over 1,670 trades in the final test, against +₹81,845 without it. " +
                "It only skips trades, and a break it cannot judge is not taken. " +
                "R72 Breadth Donchian keeps its own rules. " +
                "It is on paper only, in the change log, and one tap undoes it.",
            "Home → Strategies → tap R51 Donchian 20's name for its rules and the filter's record",
            ask = "how is the donchian arm doing"),
        Entry("2026-10-16-breadth-donchian", OCT16, "Breadth Donchian on paper, beside Donchian (your choice)",
            "A new paper arm buys a Donchian break only when at least 4 of the five indices break together. " +
                "It runs beside R51 Donchian 20, so you can compare the two on the same days. " +
                "Research R72 made +₹1,71,366 over 1,202 trades in the final test, against Donchian's +₹55,439. " +
                "Its backtest drawdown of ₹1.99 lakh is beyond your −₹40k account stop, so it stays on paper only. " +
                "It is in the change log, and one tap undoes it.",
            "Home → Strategies → tap R72 Breadth Donchian's name for its risk and the comparison with Donchian",
            ask = "how is breadth donchian doing"),
        Entry("2026-10-16-passed-arms-l25", OCT16, "+5% locked once a paper trade has been +25% (your choice)",
            "This is for new paper trades of R51 Donchian 20, R52 Open outside value and Solo 2.0. " +
                "Each will lock +5% once the option has been +25%. " +
                "The 30-60-100 lock stays on top, and a lock never goes back down. " +
                "Research R66 found it a small improvement, not a proven one. " +
                "It made +₹5,396 more over 2,202 trades in the final test, and +₹8,042 in design. " +
                "It is your choice, on paper only, and open trades keep their exits. " +
                "It is in the change log, and one tap undoes it.",
            "Home → Strategies → tap a passed-research arm's name; tap an open position for its exits"),
        Entry("2026-10-16-diagnostics-fixes", OCT16, "MCX evenings watched and Telegram channels kept",
            "After Android closes the app in MCX's evening, the watch comes straight back for your armed MCX arms. " +
                "The open positions are read back side by side at a restart, and diagnostics name why Android closed the app. " +
                "Your Telegram channels now stay selected, and the window says which ones are read. " +
                "The clock warning now checks Zerodha's server time and allows for the network delay. " +
                "Paper fills use a price at most 2 seconds old, and a slow Zerodha read waits 5 seconds at most. " +
                "The fingerprint unlock no longer freezes the screen.",
            "Copy diagnostics (Settings → Zerodha). The Telegram login was removed on 25 Oct (ntfy alerts replace it)"),
        Entry("2026-10-16-passed-arms-lock", OCT16, "The 30-60-100 lock on three paper arms (your choice)",
            "New paper trades of R51 Donchian 20, R52 Open outside value and Solo 2.0 have no fixed target now. " +
                "Each keeps its own stop and time exit. " +
                "At +30% the stop moves to breakeven, at +60% it locks +30%, at +100% it locks +60%. " +
                "Research R64 found it about even with the old exits, so it is your choice on paper only. " +
                "It is in the change log, and one tap undoes it.",
            "Home → Strategies → the passed-research arms"),
        Entry("2026-10-16-passed-arms-exits", OCT16, "Passed arms always sell at their target, stop and time",
            "A second trade on the same option could be left with nothing selling it; now each trade is booked the moment it fills " +
                "and its exits are checked every 15 seconds. A safety net takes back any arm's paper position that has no record and " +
                "tells you, all on paper.",
            "Home → Strategies → the passed-research arms"),
        Entry("2026-10-16-telegram-login-fix", OCT16, "The Telegram login shows what it is doing",
            "Each step of the Telegram login now shows its result inside the login window, with a spinner while Telegram starts. " +
                "If Telegram does not answer within 20 seconds, it says what to check and offers Try again. " +
                "After you save your api keys, the login starts by itself. " +
                "Leaving the app to read the login code no longer closes the login.",
            "Removed on 25 Oct: the Telegram login is gone (ntfy alerts replace it)"),
        Entry("2026-10-16-open-trade-change", OCT16, "A position's change is now its open trade only",
            "A position's change now shows only the open trade's move on its average price, not earlier trades on the same option. " +
                "Below it, Exits shows the stop, target and exit time its strategy is holding it to.",
            "Tap any open position (Trade tab, Home or its notification)"),
        Entry("2026-10-16-trend-watch", OCT16, "Trend watch: the Hero ticket sold when its index turns",
            "The Hero ₹1,000 ticket's paper legs are sold when the index's trend turns against them. " +
                "That one rule passed research. " +
                "By your choice only the Hero ticket is watched. " +
                "With \"Other strategies and orders too\" on, each open position and pending order is checked too. " +
                "Then Jarvis writes a note: what turned, when, and the price it would sell at. " +
                "For every other strategy the turn lost against random exits, so those stay notes. " +
                "Live positions are never sold. " +
                "Each Hero sale is in the change log, and one tap undoes it.",
            "Home → Strategies → Trend watch (tap the name for the details and switches); ask Jarvis \"has the trend turned on my trades\"",
            ask = "has the trend turned on my trades"),
        Entry("2026-10-15-market-concepts", OCT15, "Market concepts, and Solo 2.0 says why",
            "Jarvis now explains 40 market ideas, such as OI, gamma exposure, bull traps, fake breakouts and order flow. " +
                "Each says what it means, where its signal comes from, and what our research found, with sample sizes. " +
                "None of them beat Solo 2.0 in our tests, and Jarvis says so. " +
                "Solo 2.0 now says why it took or skipped each candidate today. " +
                "The words come from the numbers it already scores, so what it trades is unchanged. " +
                "It stays on paper.",
            "More → Assistant → Market concepts (tap a name); ask Jarvis \"what is a bull trap\"; Home → Strategies → tap Solo 2.0's name",
            ask = "what is a bull trap"),
        Entry("2026-10-15-arms-cleanup", OCT15, "Fewer arms: the others switched off and hidden",
            "On your choice, a short list of arms keeps running as today. " +
                "Liquidity 15+5 on BANKNIFTY, FINNIFTY and MIDCPNIFTY, the Hero ₹1,000 ticket, R51, R52, Solo 2.0 and the Watchlist stay. " +
                "Solo midday and the MCX paper arms stay too. " +
                "Every other automatic trader is switched off and hidden, its code, settings and trades kept. " +
                "One holding a position keeps trading it to its own exit first. " +
                "FINNIFTY is back on with Liquidity, on paper, as your choice; NIFTY has no Liquidity book (it failed research). " +
                "Each one comes back in one tap from the Hidden arms list.",
            "Home → Strategies → Hidden arms (one tap restores each); every switch-off and restore is also in " +
                "Home → Automation card → Autopilot → Change log"),
        Entry("2026-10-15-compact-rows", OCT15, "Shorter strategy rows: tap a name for its details",
            "Each strategy row now shows just its name, one status line, its key numbers and its switch. " +
                "Tap the name for its rules, settings and research notes in a small box with one OK button.",
            "Home → Strategies, the MCX card, the Liquidity cards, Strategy lab and Autopilot: tap an arm's name"),
        Entry("2026-10-15-passed-paper-arms", OCT15, "Three paper arms that passed research",
            "R51 Donchian 20, R52 Open outside value and Solo 2.0 now trade on paper, 1 lot each. " +
                "They passed every research gate, so your paper rule switches them on at once. " +
                "Solo 2.0 is Jarvis's own trained Solo (R57), next to Solo midday, and stays on paper forever. " +
                "Each reads its signal from the index and buys the option, always with a stop. " +
                "Each keeps its own record and switches itself off if its forward test fails. " +
                "None ever trades on Zerodha, and one tap switches each off.",
            "Home → Strategies → the rows marked \"Passed research (R51/R52/R57) — paper only\" (switch, today, record, forward test); " +
                "the switch-on is also in Home → Automation card → Autopilot → Change log, where one tap undoes it",
            ask = "how are the passed arms doing"),
        Entry("2026-10-15-watchlist-shadow", OCT15, "Watchlist of near misses (shadow only)",
            "Three research near misses are followed in shadow: R47 C4 (an option-chain signal, marked non-default), R52 R4-8 and R52 H45-1. " +
                "They are never traded; each would-be trade is priced with the paper costs. " +
                "At 40 new trades Jarvis tells you once which passed on unseen data and which to drop.",
            "Home → Strategies → Watchlist (shadow); its line is also in the evening summary",
            ask = "what's on the watchlist"),
        Entry("2026-10-15-hero-1000-ticket", OCT15, "Hero ₹1,000 ticket (paper, your choice)",
            "On your choice, a new paper bot buys a call and a put of up to ₹1,000 each at 12:30 on NIFTY's and SENSEX's own expiry days, " +
                "and sells on your 25% trail (for now it starts once a ticket has doubled; your choice waits on a follow-up study) or at 15:05. It did not pass research (R50: about −₹525 per ₹1,000 on average; jackpots are rare), " +
                "its trades are kept in their own record, it never trades on Zerodha, and one tap switches it off.",
            "Home → Strategies → Hero ₹1,000 ticket (its switch, today's tickets, the 2× / 3× / trail-only exit and where the trail starts); " +
                "the change is also in Home → Automation card → Autopilot → Change log",
            ask = "how is the hero ticket doing"),
        Entry("2026-10-14-silverm-morning-off", OCT14, "SILVERM morning call switched off (paper)",
            "On your OK, the SILVERM morning call paper bot is switched off once: research R42 found no proven edge on cleaned prices " +
                "(+₹336 a trade over 53 holdout trades, no better than a random minute; walk-forward −₹44.6k over 129 trades). " +
                "An open position still closes by its own exit, and one tap on the change undoes it, on paper only.",
            "Home → Strategies → MCX (commodities) (its row and switch); the change is also in Home → Automation card → Autopilot → Change log"),
        Entry("2026-10-14-gamma-line-oi-wall-shadow", OCT14, "Morning gamma line, and the OI-wall check in shadow",
            "At 10:00 Jarvis says whether dealer gamma is negative or positive on each index whose chain is on the phone, how such days' " +
                "rest-of-day range compared in the research, and the biggest call- and put-gamma strikes to watch; it says nothing about direction and is never a trade signal. " +
                "On expiry days your OI-wall short-covering rule is checked from 13:45 to 14:30 and only written down, with what its ticket would have made in points " +
                "and one evening line from Jarvis; it never places an order.",
            "Settings → Jarvis → Market alerts (Morning gamma line; OI-wall check (shadow)); the OI-wall check is also in the evening Order Review and the diagnostics",
            jarvisOnly = true, to = "jarvis"),
        Entry("2026-10-14-signal-on-the-index", OCT14, "Signals read from the index, then the option is bought",
            "Strategies read their signal from the index and then buy the option (the default); a Pine script set to an option chart is refused; option-derived ideas are labelled.",
            "Research → Pine → a script → Auto-trade (its symbol); Home → Strategies (Hero's row); Home → Automation card → Strategy lab"),
        Entry("2026-10-14-memory-positions-first", OCT14, "Memory: open positions first, the rest gives way",
            "Memory: the app now gives back the memory it does not need (Jarvis's model, the research notes, old prices), so Android should stop closing it. " +
                "Open positions and orders always come first: their prices and stop checks never slow down, and after any restart they are restored and watched before anything else.",
            "Lab → Health → background trims (also in the diagnostics: Start-up)"),
        Entry("2026-10-14-battery-rest", OCT14, "Battery: background work rests when nothing needs it",
            "Battery: the evening MCX watch, the order-flow recorder and other background work now rest when nothing needs them, so the phone should last the day again. " +
                "With an MCX position open, paper or live, every stop and exit is checked as often as before, and a new setting chooses when Jarvis listens for his name (Always stays the default).",
            "Lab → Health → Background work today (also in the diagnostics); Jarvis's page → Listen for \"Jarvis\""),
        Entry("2026-10-13-orb-family-parked", OCT13, "ORB, ORB Fresh, Sweep and Range Fade parked",
            "On your choice, these four arms are paused once for research (they will be studied again on the full Dhan data), because each lost money after costs in every tested year; an open position is still managed to its own exit. " +
                "Each row says \"parked on your choice (13 Oct)\" with a tap to switch it back on, and the morning check and Jarvis no longer nag about them.",
            "Home → Strategies (each arm's row); the change is also in Home → Automation card → Autopilot → Change log"),
        Entry("2026-10-13-market-context", OCT13, "Market context: events, expiry, days like today",
            "Home now warns you the evening before and the morning of an RBI, Budget, US CPI or Fed day, with how BankNifty and Nifty moved on such days and how many days that is; on expiry days it shows how fast option time value melts, and from 09:21 the past days most like today, as context, not a forecast. " +
                "On the chart, add \"Ira: level map\" for support and resistance with how often each held, and long-press any candle and pick \"Explain this candle\" for what the app knows about those minutes; a new switch can pause paper arms on event days, and it is off.",
            "Home → Market context; Chart → indicators → Market context; Settings → Bot settings → Pause paper arms on event days",
            to = "chart"),
        Entry("2026-10-13-pretrade-checks", OCT13, "Three checks before each paper trade",
            "Before each paper trade (Liquidity aside) the app notes how much of the expected 30-minute move the charges eat. " +
                "It also notes where a stop sized to that move would sit, beside the fixed stop. " +
                "Each morning it rates the day normal, careful or paper-only, with how many past days back the rating. " +
                "We tested all three on two years of data and none beat chance. " +
                "The held-back tests had 499 trades for the charges, 15 for the stop and 122 days for the rating. " +
                "Hero had only 8 test trades and 2 held-back ones, far too few to judge. " +
                "So they are notes and advice only: they never skip, resize or move a trade. " +
                "The one switch, half size for paper arms on careful days, is off.",
            "Settings → Bot settings → Pre-trade checks; Order review; Arms · today; the Good morning check"),
        Entry("2026-10-13-shared-knowledge", OCT13, "All the agents now learn from one shared knowledge",
            "Every agent now reads one shared knowledge: the research, Jarvis's lessons and the latest findings. " +
                "The trade manager, order review, order flow, strategy lab and study now say what the research found. " +
                "The slippage record, the Liquidity test and the study now add to Jarvis's lessons. " +
                "Each night a lessons summary is saved for the research, with your money in it encrypted. " +
                "It only explains and ranks: it never adds a trade or raises a size. " +
                "A new skip rule only counts in shadow, and stays off until you turn it on.",
            "Home → Ira → What Jarvis has learned → Shared knowledge today; the shadow switch in Bot settings → Shared knowledge",
            ask = "what have you learned", jarvisOnly = true),
        Entry("2026-10-13-exit-research", OCT13, "Exit research: today's exits stay",
            "Five new ways to exit were tested against today's exits on every arm, over two years of data: VWAP bands, the next support or resistance, a 45-minute time stop, the expected range, and breakeven after +15%. " +
                "None beat today's exits after charges once chance was allowed for, so nothing in the app changes (Liquidity BANKNIFTY was only studied, as it is frozen to 26 Oct, and none helped it either).",
            "Not in the app: the research write-up HUNT_R18_EXITS.md"),
        Entry("2026-10-13-github-sync", LocalDate.of(2026, 10, 13), "Recordings and backups go to GitHub by themselves",
            "Each evening after the MCX evening close (15:45 on days without an MCX session), on Wi-Fi while charging, the phone uploads each recorded day and a backup to GitHub (a private data-only repository, never the app's code), sealed with your backup passphrase so GitHub sees only noise. " +
                "A new phone gets everything back with Restore from GitHub: strategies and Pine scripts return switched off, and your PIN, Live switches and tokens are never in it.",
            "Settings → Data and harvest → GitHub sync", to = "data"),
        Entry("2026-10-13-github-diagnostics", OCT13, "Each evening's upload carries the day's diagnostics",
            "With each evening's upload to the private data repository goes one small file for that day: each arm's signals, which were taken or skipped and why, its trades in points and percent, the app's health and the day's changes. " +
                "It is cleaned on the phone first (no tokens, ids, names, phone, e-mail or rupee amounts), so the research sessions can read it to find and fix problems.",
            "Settings → Data and harvest → GitHub sync (the Diagnostics line)", to = "data"),
        Entry("2026-10-12-tick-zone", OCT12, "Every second of order flow recorded",
            "While the market is open the app now records, every second, the order flow of the BANKNIFTY and NIFTY futures, their options three strikes either side of the money, and VIX: price, volume, VWAP, OI and the five-level book. " +
                "Each minute it also saves buying vs selling, the order-book balance, the spread, big trades and absorption; and \"Export recordings\" now asks your PIN and saves everything, your trades decrypted, to a file you pick.",
            "Settings → Data and harvest → Recordings", to = "data"),
        Entry("2026-10-12-order-review", OCT12, "Order review: every order studied daily",
            "A new agent studies every order and trade each day after the close - live, paper, the bots, Pine and your own - and lists the flaws with their numbers. " +
                "A flaw that keeps coming back is flagged for a fix or tested by the strategy lab on paper first; it never places, changes or cancels an order.",
            "Home → Automation card → Order review", ask = "what went wrong today", jarvisOnly = true),
        Entry("2026-10-12-majority-rule-context", OCT12, "Majority Rule 14: every agent knows it",
            "Jarvis, the Brain and a new Learn page now know the weekly Majority Rule 14 research: after a deep weekly sell-off NIFTY " +
                "tended to drift up over 1-3 months, but +1,000 points and the green candle added nothing, and buying options on it was break-even to losing. " +
                "As you decided it is context only: no alert, no strategy, never an entry and never more risk.",
            "Research → Learn → Majority Rule 14; Home → Automation card → Brain"),
        Entry("2026-10-11-home-automation-card", OCT11, "Home puts trading first",
            "Home now starts with your money and live orders, then Strategies, MCX and the BANKNIFTY chart. " +
                "The trade manager, Autopilot, Strategy lab, Order flow and Brain now share one Automation card. " +
                "Each has a one-line summary; tap it to open that section in full. " +
                "Every button and sheet is still there, one tap away. " +
                "A warning line shows when learning is frozen. " +
                "Home is lighter too: those sections refresh only while they are open.",
            "Home → Automation card → tap a line"),
        Entry("2026-10-11-self-improvement-round-2", OCT11, "The learning closes its loops, with brakes",
            "A proven variant now changes its strategy: on paper from the next session, live only with your PIN. " +
                "After a bad day it goes back to the defaults by itself; Liquidity may only tune its exits. " +
                "Ideas you approve trade on the paper account, never Zerodha, three at most. " +
                "The phone keeps the market's history, and learning leaves out event days. " +
                "A portfolio guard blocks a later entry when linked strategies would stack the same risk. " +
                "Today's changes sit on one screen with Undo everything today; learning freezes itself after a drawdown. " +
                "A monthly scorecard says what the automation earned or cost. " +
                "Jarvis learns your own habits, and Research has Learn pages, today's lesson and bot trade replays.",
            "Home → Automation card → Autopilot → Today's changes; Research → Learn",
            jarvisOnly = true),
        Entry("2026-10-11-strategy-lab-auto", OCT11, "Strategy lab: strategies improve and new ones are tried",
            "Each running strategy now has a few nearby variants that trade virtually beside it: no orders, not even paper. " +
                "A variant must beat the current rules for 20 days and pass strict luck checks. " +
                "On paper it is then applied by itself, with undo; on live it is only proposed, with your PIN. " +
                "Each night, while charging or after 18:00, the forge builds new option-buying ideas and backtests them after costs. " +
                "Almost all fail: walk-forward, a random-twin check and a count of every idea ever tried. " +
                "Survivors run a virtual forward test of 20 days before you are asked to start them on paper. " +
                "Jarvis learns from every result, and the graveyard shows how many ideas were tried.",
            "Home → Automation card → Strategy lab → tap a strategy or an idea for details",
            ask = "how are my strategies improving", jarvisOnly = true),
        Entry("2026-10-11-smarter-learning", OCT11, "Smarter learning: tested, replayed and tried on paper first",
            "Each lesson now carries a q-value: it is strong only if it holds when all lessons are tested together. " +
                "A lesson that holds only on busy days, or only on expiry days, says so. " +
                "Before Autopilot changes a paper or live setting, it replays the change on your stored trades. " +
                "If it would have done worse, the change is skipped. " +
                "A lesson still building is tried first as a paper experiment on each paper trade both ways, never on live. " +
                "Each change is kept or reverted as soon as the evidence is clear. " +
                "And the bar rises with every setting it has tried.",
            "Home → Automation card → Autopilot → Change log",
            ask = "what experiments are running", jarvisOnly = true),
        Entry("2026-10-11-self-healing-app", OCT11, "The app heals itself and reports its growth",
            "The app now watches its own health on this phone: screen stalls, freezes, crashes and order speed. " +
                "When stalls pile up on something minor, it slows that refresh a little and restores it after three healthy days. " +
                "It never slows orders, stops or live prices. " +
                "Each evening Jarvis picks up to three things to learn next and records more for them, never trading. " +
                "Every Sunday he tidies his memory. " +
                "On Saturday morning he sends a weekly report of what he learned, what Autopilot changed and how the app ran.",
            "Home → Automation card → Autopilot → Weekly report; Home → Ira → What Jarvis has learned; Copy diagnostics (the Health line)",
            ask = "how did you grow this week", jarvisOnly = true),
        Entry("2026-10-11-autopilot-learning", OCT11, "Autopilot learning: the agents learn and heal by themselves",
            "Jarvis and the agents now make the changes their lessons point to, by themselves, within strict bounds. " +
                "On shadow and paper they tune themselves: thresholds, weights, mutes and pausing a losing paper strategy. " +
                "On live they only lower risk by themselves; anything that adds live risk is still asked, with your PIN. " +
                "Every change is logged with its reason and numbers and can be undone with one tap. " +
                "After 15 trades or 5 days it is checked again, and reverts itself if it made things worse. " +
                "It never touches the PIN, Live, AI-live, the guard, the app lock, the day lock, sizes or open orders. " +
                "Switch it OFF, SAFE or SHADOW-ONLY in Settings; \"turn off autopilot\" by voice always works.",
            "Home → Automation card → Autopilot → Change log; Settings → Bot settings → Autopilot learning",
            ask = "how is autopilot doing", jarvisOnly = true),
        Entry("2026-10-11-jarvis-learns", OCT11, "Jarvis learns from the bots",
            "Each evening after the close, Jarvis studies what every bot, the trade manager, its specialists and the order flow kept. " +
                "He turns it into lessons, each with its numbers: how many trades, the rupees and how often it worked. " +
                "A lesson is strong only with 30 or more cases that agree over time. " +
                "New evidence strengthens a lesson; evidence against it weakens and then retires it. " +
                "When a strong lesson suggests a change, he asks you first and never changes anything himself. " +
                "He only suggests lowering risk or acting on paper; two noes rest a suggestion for 30 days. " +
                "He never touches the PIN, Live, the app lock or real orders.",
            "Home → Ira → What Jarvis has learned",
            ask = "what have you learned", jarvisOnly = true, to = "ira"),
        Entry("2026-10-11-chart-any-symbol", OCT11, "Chart any index, commodity or option",
            "Tap the symbol at the top of the Chart tab to chart something else. " +
                "Pick an index, Sensex and India VIX included, or any MCX commodity's near-month future. " +
                "Options go by underlying, expiry and strike, then CE or PE. " +
                "Or search any index, MCX contract or option; your last six symbols are one tap away. " +
                "The chart reopens on the symbol you charted last, and anything without prices is greyed with the reason.",
            "Chart tab → tap the symbol at the top (▾)", to = "chart"),
        Entry("2026-10-10-manager-research", OCT10, "The trade manager follows the research",
            "Research round 12 tested 14 ways to manage an open trade against random early exits. " +
                "One rule passed, on ORB Fresh: out after 20 minutes without 3% gain, or a close 12% under entry. " +
                "A lock under its best price, sized by its recent swings, only rises. " +
                "It now acts on ORB Fresh's paper trades only; the arm still loses overall. " +
                "Live stays a record, and every other strategy keeps its own exits while the manager records. " +
                "Each ORB arm has its own mode, and your own choices and mutes are kept.",
            "Home → Automation card → Trade manager → Trade manager record",
            ask = "what did the research say about the trade manager"),
        Entry("2026-10-10-manager-specialists", OCT10, "The trade manager is now a team of specialists",
            "Sixteen specialists watch an open trade, each reading its own data: order flow, VWAP, volume, OI, traps, news. " +
                "They share what they see, and a chair weighs their votes into one decision. " +
                "News, traps and bad data can still order an exit alone. " +
                "The lock still only rises, a stop is never widened and nothing is ever added. " +
                "Each specialist keeps a report card against the original rules. " +
                "On paper and shadow their weights tune slowly; a specialist that hurts is muted, and you can mute one yourself. " +
                "Live trades keep fixed weights until you accept a change with your PIN.",
            "Home → Automation card → Trade manager → Specialists",
            ask = "how are the manager's specialists doing"),
        Entry("2026-10-10-trade-manager", OCT10, "Solo and Pine manage their open trades",
            "After a fill, a trade manager watches the trade with the live order flow, VWAP, value area and findings. " +
                "It exits early when sellers or buyers take over, a trap turns against it, or news is about to start. " +
                "It moves the target out only with a lock at breakeven plus charges or better. " +
                "That lock rises on each move and trails new highs at half the open profit, never down. " +
                "It never adds lots and never widens a stop. " +
                "It acts on paper; on live it only records until you switch it on with your PIN. " +
                "Its record compares every trade with what the original rules would have done. " +
                "Every other strategy only records until its record proves it; the record then says ready, a hint, never a switch.",
            "Home → Automation card → Trade manager → Trade manager record, and the Solo and Pine trade cards",
            ask = "how is the trade manager doing"),
        Entry("2026-10-10-findings-bus", OCT10, "The bots share what they find",
            "Each strategy and checker posts what it finds: a sweep, a break, a pull, a stop hit, a big move. " +
                "Home shows each index's view: how many findings lean up and how many lean down. " +
                "That view is logged beside every signal, to test later whether it helps. " +
                "Reactions between bots can only skip an entry, and start as a record only. " +
                "Unreliable data pauses every new entry at once; exits always go.",
            "Home → Automation card → Brain (the bots' findings), and Copy diagnostics (Brain)",
            ask = "what are the bots saying about banknifty"),
        Entry("2026-10-10-smart-workers", OCT10, "Smarter bots: they wake, heal and check themselves",
            "Each bot now wakes when something it waits for happens, such as its candle closing, not on a fixed clock. " +
                "A check every 30 s stays as a safety net, every 15 s while anything is held. " +
                "When the stream, Zerodha, the relay or candles falter, the app uses a backup and tells you once. " +
                "New entries pause for 5 minutes around news such as RBI policy and US data, paper and live. " +
                "Trap alerts, an unreliable feed and big moves are only recorded beside each signal for now. " +
                "After 15:45 each strategy compares its recent trades with its backtest. " +
                "A drifting live strategy moves itself to paper and asks you; it never goes back to live alone. " +
                "Exits are never delayed.",
            "Home → Automation card → Brain (health chips), Settings → Zerodha → Order speed, and Copy diagnostics (Brain)",
            ask = "how are my strategies doing vs backtest"),
        Entry("2026-10-10-parallel-workers", OCT10, "The watch works on many things at once",
            "The order watch now runs the strategies side by side instead of one after another. " +
                "Stops, exits and the daily loss limit still run first, and never wait behind an entry. " +
                "Every new position passes one door, one at a time: the kill switch is checked again there. " +
                "Two bots can no longer enter the same index in the same second. " +
                "Zerodha is asked at most 8 times a second, and reads asked at once are shared. " +
                "The phone's own AI model uses fewer cores in market hours and gives way to trading. " +
                "Diagnostics list each worker and the slowest steps today.",
            "Settings → Zerodha → Order speed, and Copy diagnostics"),
        Entry("2026-10-09-big-move-recorder", OCT9, "Big moves saved, second by second",
            "The order flow now saves each big minute in full: 5 minutes before and after, second by second. " +
                "A big minute is a futures candle 4.7 times its usual size for that time of day. " +
                "Two ordinary minutes a day are saved too, to compare against. " +
                "The one-second file adds the best bid and offer, five levels, pulls, print sizes and the cash index. " +
                "The price stream stays on from 9:15 to 15:40 for it, except in battery saver. " +
                "A switch in the same sheet turns it off. " +
                "Files are kept 12 months and export with the market data. " +
                "It only records; no strategy uses it.",
            "Home → Automation card → Order flow (tap an index) → Big moves",
            ask = "why did banknifty jump at 10:32", to = "chart"),
        Entry("2026-10-09-fno-to-1540", OCT9, "F&O trades to 15:40",
            "NSE F&O now trades to 15:40; the app's order window and watch follow it. " +
                "Index values still close at 15:30, and every strategy keeps its own entry and exit times.",
            "Home's market status, the order forms and the market watch"),
        Entry("2026-10-09-order-flow-auction", OCT9, "Volume profile, gamma regime, heatmap and tape",
            "The order flow detail shows the future's volume profile: point of control, value area and its busy and thin prices. " +
                "It says whether price is inside or outside yesterday's value, and if a move outside is accepted. " +
                "It adds delta per minute with divergence, a 15-minute footprint, VWAP bands and the market profile. " +
                "A liquidity heatmap and a time and sales tape open from the same sheet. " +
                "Home and the GEX tool show the gamma regime and its zero-gamma level, with both dealer-sign conventions. " +
                "Optional chart lines for these levels and VWAP start off. " +
                "All of it is shown and logged beside each signal for research; no strategy uses it.",
            "Home → Automation card → Order flow (tap an index), Options → GEX, and the Chart's POC/VA and VWAP toggles",
            ask = "what's the volume profile on banknifty", to = "chart"),
        Entry("2026-10-09-order-flow", OCT9, "Live order flow, logged beside every strategy",
            "A live read of who is pushing, buyers or sellers, from Zerodha's full price stream. " +
                "It shows on the chart, the option chain and Home's Automation card, with a detail on tap. " +
                "Every strategy now logs the flow beside each signal, and the result later. " +
                "Per strategy you can set it off, shadow (log only) or confirm. " +
                "Confirm only skips an entry the flow disagrees with; it never places, adds or reverses a trade. " +
                "Turning confirm on in Live asks for your PIN; it is unproven and may skip winners. " +
                "A trap guard ignores pulled orders, fake walls, stop hunts and the open and close minutes. " +
                "One-second flow bars are kept 30 days and export with the market data.",
            "Home → Automation card → Order flow (and the Flow chip on the Chart)",
            ask = "what is the order flow on banknifty", to = "chart"),
        Entry("2026-10-09-live-prices-everywhere", OCT9, "Live exits from the price stream, local candles",
            "Live stops, targets and trails now decide on each streamed price; only the order itself goes to Zerodha. " +
                "Minute and bar-close arms decide on candles built from the stream, with the candle feed as the check. " +
                "Orders read nothing from the phone's locked storage and reuse a warm connection. " +
                "Screens update on every price, up to four times a second, and say live or delayed. " +
                "An optional setting keeps a stop-loss order resting at Zerodha for live positions.",
            "Settings → Zerodha (the order speed card and the exchange stop setting)"),
        Entry("2026-10-09-strategies-to-research", OCT9, "Saved strategies moved to Research",
            "Your saved strategies moved from Trade to Research → Strategies. " +
                "Trade now opens straight on your account; Home's Strategies card with the bots stays on Home.",
            "Research tab → Strategies (create, edit, arm, run and backtest your baskets)", to = "strategy"),
        Entry("2026-10-09-order-speed", OCT9, "Faster orders, and an order speed card",
            "Stops, targets and exits are now checked the moment a new price arrives, not every 15 seconds. " +
                "Bar-close entries are decided at the close itself; the 15-second check stays as a backup. " +
                "Zerodha's own fill notice now confirms your orders at once, without asking again. " +
                "The order speed card shows each step's typical and worst time, paper and live. " +
                "It warns when the relay is slow or prices are old, and suggests a Mumbai relay if far. " +
                "Every safety rule, approval and paper-only bot is unchanged.",
            "Settings → Zerodha → Order speed card, below the Live self-test. Copy diagnostics includes it too."),
        Entry("2026-10-09-two-paper-bots-and-parking", OCT9, "Two new paper bots, and two losing ones switched off",
            "Home → Strategies has two new paper-only bots, not proven, off until you switch them on. " +
                "VIX divergence buys a put when Nifty or Bank Nifty is up 0.2% while India VIX is up 2%. " +
                "Both down buys a call; it checks at 10:30, 11:30, 12:30 and 13:30, first signal only. " +
                "Its research made Rs 70 and Rs 112 a day in the holdout, but failed the luck checks. " +
                "US-night silver buys or sells 1 lot of the silver micro future at 09:05, following US silver overnight. " +
                "Its holdout made Rs 1,189 a trade in silver's tripling year; it sells too and has a 3% disaster stop. " +
                "Liquidity 15+5 FINNIFTY (−₹8,035 over 8 paper trades) and ORB Sweep (−₹4,498 over 6) were switched off on your OK. " +
                "Switch them back on in Home → Strategies any time.",
            "Home → Dashboard → Strategies card: the VIX divergence row, and Switch FINNIFTY back on under the Liquidity 15+5 row. " +
                "US-night silver: Home → MCX card"),
        Entry("2026-10-09-mcx-paper-arms", OCT9, "Three MCX ideas to try on paper, all off at first",
            "Home's MCX card has three paper-only MCX bots, each not proven and off until you switch it on. " +
                "Natural gas evening breakout: buys a call or put when 17:00-19:00's range breaks before 22:00, flat by 23:15. " +
                "Its research made about Rs 41 a day, then Rs 83, not significant, with a Rs 40,000 drawdown. " +
                "Silver mini morning call: buys a call two strikes out, 11-20 days to expiry, sells 4 hours later. " +
                "Its research made Rs 454 a trade over 51 trades, but failed 2 of its 4 checks. " +
                "12-month trend on mini futures: long or short monthly; needs Rs 8-10 lakh, often ruined at Rs 1 lakh. " +
                "All three are 1 lot, paper only, and never send an order to Zerodha. " +
                "Delivery futures are now closed 5 trading days before expiry, not 2.",
            "Home → MCX card. Settings → Bot settings → MCX expiry exit"),
        Entry("2026-10-09-mcx", OCT9, "MCX commodities: prices, charts, paper and expiry safety",
            "Crude oil, natural gas, gold, silver and the metals are now in the app. " +
                "You see near and next month futures with live prices, charts and option chains. " +
                "The watch now runs in MCX hours, to 23:30, and to 23:55 from 2 November. " +
                "Paper fills pay MCX's spread and charges, and the margin per lot is shown. " +
                "MCX options are closed by 23:00 the day before expiry, so they never turn into futures. " +
                "New option buys stop at 15:00 the day before expiry. " +
                "Gold, silver and metal futures are closed 5 trading days before expiry. " +
                "Only you trade MCX on Zerodha; no bot does.",
            "Options tab → Commodities. Options tab → MCX row for the chains. Settings → Bot settings → MCX expiry exit"),
        Entry("2026-10-08-x1-x2", OCT8, "Faster entries, 1 lot, and a paper night trade",
            "The arms now decide within seconds of each bar close, not a minute or two later. " +
                "Liquidity 15+5 on Bank Nifty is judged on its own, apart from FinNifty and Midcap Nifty. " +
                "Liquidity is back to 1 lot unless you chose more; change it in Strategies. " +
                "Jarvis says a lesson only after 30 trades in that group. " +
                "The Pine FinNifty breakdown auto-trade is switched off; you can arm it again. " +
                "New: Night (R3) on paper buys at 15:20 on a strong close and sells at 09:16. " +
                "It is not proven: about Rs 207 a day before the holdout, Rs 150 in it.",
            "Home → Dashboard → Strategies card (Liquidity's lots and the Night (R3) row). Pine scripts screen for the FinNifty script"),
        Entry("2026-10-08-honest-paper", OCT8, "Paper fills now pay the bid/ask spread",
            "Every paper buy and sell now pays the bid/ask spread, like a real order. " +
                "It uses the real bid and ask when Zerodha's stream has them. " +
                "Otherwise it uses the measured spread for each index. " +
                "So paper looks about Rs 100 worse per round trip, and closer to real money. " +
                "A paper fill never uses a price over a minute old. " +
                "Each paper trade shows the spread it paid.",
            "Trade → Paper → tap a trade or an order (Bid/ask spread). Home → Dashboard → Strategies card, and the day report"),
        Entry("2026-10-08-locks-and-day-lock", OCT8, "Safer exits, and a day lock at +Rs 8,000",
            "Pine's stop and profit lock now rest as one stop that only moves up, on every tick or minute high. " +
                "At Zerodha a backup GTT waits just under each bot's stop and moves up with it. " +
                "Pine skips an option that barely trades. " +
                "Pine, Solo and Liquidity exits are checked every 15 seconds, and a stop the price passed is sold at once. " +
                "With no prices for two minutes while you hold a position, you get a loud warning. " +
                "A paper buy never fills on a price minutes old, and Home shows paper's net since 1 Oct. " +
                "Once an account's day reaches +Rs 8,000, no new automatic trade starts that day; open trades keep their exits.",
            "Settings → Bot settings → Day lock (+Rs 8,000 at first, off at 0) and the paper start date. Home → Dashboard → Strategies card shows both"),
        Entry("2026-10-07-liquidity-priority", OCT7, "Liquidity 15+5 goes first on an index",
            "ORB, ORB Fresh, ORB Sweep and Range Fade no longer stop a Liquidity 15+5 trade: Liquidity enters beside them on paper, " +
                "and they wait while Liquidity holds the index (Liquidity has priority over ORB arms). Zerodha still needs your PIN " +
                "or fingerprint, your daily limits and kill switch are unchanged, and two live automatic trades on one index are still refused.",
            "Home → Dashboard → Strategies card: an arm's row says when it waited for Liquidity, and the evening replay marks those trades"),
        Entry("2026-10-07-profit-lock-stop", OCT7, "The profit lock now moves the stop itself",
            "When ORB, ORB Fresh, ORB Sweep or Range Fade reach a profit-lock rung, their resting stop order is moved up to the lock " +
                "(never down), so at Zerodha it sells there even with the app closed, and the best price counts every tick or each minute's high. " +
                "ORB Sweep's rungs are on its +80 target: +20, +40 and +60, as before.",
            "Home → Dashboard → Strategies card → an arm's open trade (its stop shows the lock)"),
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
